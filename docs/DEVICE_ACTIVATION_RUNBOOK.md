# NeoProCamX — Device-Activation Runbook (gated foundations)

> Companion to [`VERIFIED_EXECUTION_LEDGER.md`](./VERIFIED_EXECUTION_LEDGER.md). Every feature wired in the
> CP-QV15…CP-QV22 marathon ships **gated OFF, flag-off byte-identical** — the live path is unchanged until a
> flag is flipped. Visible activation is **device-gated**: it needs on-device (vivo I2405, Camera2 LEVEL_3)
> validation because the enabled-path correctness (GPU barriers/copy-back, lens actuator, gyro clock, LUT
> upload precision) cannot be verified in host JVM tests. This runbook is the activation + validation checklist.
>
> **Toggle mechanism:** most flags are in-process `object`s flipped from the Settings sheet or ADB
> `DebugCommandReceiver` (see each entry). Default is always OFF/identity.

---

## CP-QV15 — Adaptive thermal → OmniClarity motion stack  ·  `OmniClarityMotionTuningFlag`
- **This one is a LIVE improvement, not a dormant foundation** — but still gated pending a thermal soak.
- **On:** under SEVERE thermal (severity ≥ 3) the live motion stack sheds refine iters (8→4) + temporal blend
  (0.9→0.6), self-restoring on cooldown. Cool/moderate = native defaults (bit-identical).
- **Validate on device:** a multi-hour record soak on vivo I2405 — confirm the backoff engages under real heat,
  motion stays acceptable, and preview/recorded motion is not visibly degraded on a cool device. The
  encoder-yield was removed, so this also softens *recorded* motion when hot — confirm that's acceptable.
- **Risk if enabled blind:** none on a cool device (proven no-downgrade); only graceful degradation under heat.

## CP-QV16 — Gated single-frame spatial denoiser (`denoise.comp`)  ·  `DenoiseSpatialFlag`
- **On:** runs the wavelet/NLM/Anscombe-Oklab-Bayes/guided-filter denoiser as an extra pass into the
  `omniDeblur` scratch, copied back via the OCCS zero-gain passthrough.
- **Validate on device:** Vulkan validation-layer-clean dispatch (the STORAGE-only copy-back + barrier sequence
  is unverifiable in host tests); then a flat-region-noise vs edge-detail A/B — confirm variance drops without
  plastic skin / over-smoothing atop the live nivukx temporal denoise. Tune the 6 push-constants
  (spatialSigma/rangeSigma/chromaSigma/detailPreserve/noiseLevel/passIndex) per ISO.
- **Risk if enabled blind:** double-denoise over-smoothing + ~5 ms/frame; barrier bug could corrupt the frame.

## CP-QV17 — Gated optical-recovery sharpener (`sharpen.comp`)  ·  `SharpenOpticalFlag`
- **On:** Richardson-Lucy + wavefront-phase-retrieval + edge-adaptive unsharp, same scratch/copy-back as QV16.
- **Validate on device:** validation-clean dispatch; slanted-edge MTF50 gain WITHOUT ringing/halos. The PSF is a
  heuristic derived from radius/threshold (NOT a calibrated lens model) — a real per-frame PSF/defocus estimator
  is needed for correct optical recovery; until then keep `amount` conservative.
- **Risk if enabled blind:** ringing/halos + noise amplification; RL with a wrong PSF over-restores.

## CP-QV18 / CP-QV19 — Gyro-EIS foundation (goldens only, no flag)
- CPU grid producer + GPU warp shader are **integration-golden-locked** (fold==batch exact, never-reveal-border,
  OIS sign, `USE_EIS_WARP=false` identity). No flag flip here — these are correctness locks.
- **To actually stabilize (the real device work):** (1) calibrate the gyro↔`SENSOR_TIMESTAMP` clock offset and
  the OIS sign/scale on vivo I2405; (2) build the missing GPU upload path (grid → `eis_warp.comp` via
  `VkSpecializationInfo(USE_EIS_WARP=true)` + a dispatch in the live command buffer). Both are absent today.
- See also **CP-QV22** (the consumer seam) below.

## CP-QV20 — Cinematic rack focus  ·  `CinematicFocusPullFlag`  (ADB-triggerable)
- **On + ADB:** `cine_focus_pull true`, then `focus_pull_mark_a/_b <diopters>`, `focus_pull_curve
  <ease_in_out|linear_rack>`, `focus_pull_duration_ms <ms>`, `focus_pull_trigger true`. The pull seeds manual
  focus at mark A, steps A→B endpoint-exact, and holds B (no snap-back).
- **Validate on device:** requires the sensor to report `LENS_INFO_MINIMUM_FOCUS_DISTANCE > 0` (else the whole
  manual-focus path is inert). Confirm the pull *feels* cinematic (smooth easing, holds B, no AF re-hunt) and
  that flag-off leaves autofocus exactly as today (locked by `CinematicFocusPullCineOffContractTest`).
- **Risk if enabled blind:** none when off (byte-identical); when on, a mistuned curve/duration just looks off.

## CP-QV21 — Replaceable NRLI 3D-LUT  ·  `SnceNrliLutFlag` + `VulkanBridge.updateNrliLut(lut, strength)`
- **On:** call `updateNrliLut(SnceNrliAnalyticLut.buildNearIdentityLut(), strength)` with a small `strength` to
  replace the identity 33³ LUT on the live NRLI enhance pass. Default `strength=0` = exact passthrough.
- **Validate on device:** validation-clean GPU 3D-LUT re-upload (rgba16f precision), then raise `strength` slowly
  and confirm the analytic near-identity look is subtle + neutral on the grey axis. Because the NRLI pass runs on
  the default-ON perceptual path, a wrong LUT/strength recolours the WHOLE live frame — start near-identity.
- **Risk if enabled blind:** whole-frame recolour if the LUT/strength is wrong. Keep `strength` low; the analytic
  LUT is bounded ±0.03 from identity.

## CP-QV22 — StabilizationSessionController EIS consumer seam  ·  `gyroEisTelemetryConsumerEnabled`
- **On:** feeds per-frame gyro/OIS telemetry into the StabilizationSessionController (`mode` still OFF = identity
  grid). Produces a warp grid that currently goes **nowhere** (no GPU upload path — see QV18/19).
- **Validate on device:** first the gyro clock + OIS calibration (as QV18/19); then wire the GPU warp upload; then
  flip `mode` to an active stabilization mode. Until the upload path exists this is a dormant consumer.
- **Risk if enabled blind:** per-frame CPU allocation on the camera callback thread (grid producer) even in OFF
  mode — that's why it's double-gated (flag + `mode`). No pixel change (no upload path).

---

## Hard device walls (NOT activatable in software — need hardware/driver support on vivo I2405)
| Wall | Blocker |
|---|---|
| Visible MFSR super-resolution fusion | 1×1 dummy input; no real multi-frame ring |
| HDR dual-gain / photon fusion | no LCG/HCG conversion-gain ring on the sensor path |
| Depth-driven lens-distance PID | disabled whenever `maxAfRegions > 0` (every modern phone) |
| Zero-copy P010 transfer (`Cp73ZeroCopyTransferFlag`) | native hard wall; ~331 ms/frame copy is CPU-bound |
| SNCE 23-float scene-brain GPU UBO | only upload path is the strict-length per-frame `EnhancementUniforms` ABI + contaminated producer — needs the live ABI changed + device validation, not a safe gated commit |
| Oklab exact gamut-cusp / xy-boundary | lives on the P010 **export** shader surface (not the live vulkan_bridge path) |

*Every gated foundation above is committed with a host golden / ABI lock and, where native, a validation-clean
build — so activation is a flag-flip + on-device validation, not new development.*
