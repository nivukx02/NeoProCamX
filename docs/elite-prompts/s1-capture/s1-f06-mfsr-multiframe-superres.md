# MFSR Multi-Frame Super-Resolution Fusion — Elite Upgrade Prompt

> ⭐ **Reference exemplar.** This file is the quality bar for every other prompt in
> `docs/elite-prompts/`. Read [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) first — its
> global contract (no-downgrade, no-vendor, commit-per-feature, golden-first, ABI-locked,
> device-gated) applies here and is not restated.

| Field | Value |
|---|---|
| **Subsystem** | 1 — Capture, RAW & Multi-frame |
| **Prompt ID** | s1-f06 |
| **Maps to checkpoint(s)** | CP12 (real ring + flow + noise), CP13 (per-frame flow indexing), depends on CP10/CP11 |
| **Current working level** | 🟠 dormant (dispatched on dummy input → active UB hazard, not a benign no-op) |
| **Feature rating (potential)** | 2/10 now → 9/10 target |
| **Code-quality rating (now)** | 7/10 (shader math is sound; the binding/seeding is broken) |
| **Risk · Size** | H · XL |
| **Depends on** | s1-f07 (cinema-debayer, CP10), s1-f09 (sensor→ACEScg, CP11), s3-f01 (Argus flow), s1-f04 (gyro telemetry) |

## 1. What this feature is
Multi-Frame Super-Resolution fuses a burst of slightly-displaced frames into one frame with more
real detail and less noise than any single capture. Hand tremor / motion dithers the scene
across the Bayer grid by sub-pixel amounts; aligning and merging those samples recovers
information below the single-frame Nyquist limit and averages down shot/read noise by √N. The
shader (`mfsr_fusion.comp`) implements anisotropic steering-kernel regression (structure-tensor
+ Mahalanobis weights), heteroscedastic noise weighting, and a 3σ ghost-veto — the correct
algorithm. It just isn't being fed real data.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
True cinema cameras mostly don't need MFSR — they oversample optically (IMAX 15/70mm resolves
~18K-equivalent, then downsamples to a grainless master; the ALEXA 35's large photosites carry
17 stops natively). The *computational* gold standard MFSR must match is Google's "Handheld
Multi-Frame Super-Resolution" (Wronski et al., SIGGRAPH 2019), the basis of Pixel's Super Res
Zoom and Night Sight:
- **Robust alignment** at sub-pixel precision (per-tile optical flow), with explicit
  **robustness masking** so misaligned/occluded regions fall back instead of ghosting.
- **Kernel regression accumulation** steered by the local structure tensor (kernels stretch
  *along* edges, shrink *across* them) — exactly what `mfsr_fusion.comp` already encodes.
- **Heteroscedastic, signal-dependent noise model** (`σ² = σ_read² + σ_shot²·signal`) driving
  per-sample confidence, so denoising strength tracks real sensor noise, not a constant.
- **Bayer-domain fusion** (demosaic-during-fusion) so resolution gain is captured before
  interpolation throws it away.
The elite bar: visible detail (MTF50) above the single-frame baseline on a static resolution
chart, SNR improvement approaching √N in flat fields, and **zero ghosting** on a moving subject —
all real-time-budgeted, never amplifying grain or inventing texture.

## 3. Current state in NeoProCamX (verified)
- Shader: `app/src/main/cpp/generated/mfsr_fusion_comp_spv.h` (included `vulkan_bridge.cpp:39`),
  descriptor layout built `vulkan_bridge.cpp:3996`, labeled `"mfsr_fusion"` (4018).
- **The break:** MFSR Pass 1 reads bindings 0/1 = `dummyImageArrayView` / `dummyImageView`
  (`vulkan_bridge.cpp:4295`, dummies created at `3667`, declared `619-626`). These are created
  `initialLayout=UNDEFINED` and **never cleared or uploaded** → the shader reads **uninitialized
  device memory**. With `noiseS=noiseO=0` and `sigmaThresh=0`, the 3σ veto rejects every frame
  f>0, so only the garbage "reference" contributes.
- **The real seed exists but is dead:** the genuine frame→intermediate transform lives only in
  `unusedRealtimeComputeDiagnostics()` (`vulkan_bridge.cpp:5880`) — **zero callers**. Its name is
  literally its status.
- Net: this is **not a benign no-op** — it is an active undefined-behavior hazard, masked only on
  drivers that happen to zero-initialize memory. (Per CP12 audit, 2026-06-25.)

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **No UB:** every binding the shader reads is deterministically initialized before first read
   (cleared or uploaded). Provable by a validation-layer run with zero "read of uninitialized /
   UNDEFINED layout" warnings.
2. **Real burst ring:** an N-frame (N=3–8, adaptive) RAW/luma ring buffer feeds binding 0, each
   layer a real captured frame, not a dummy.
3. **Per-frame alignment:** binding for flow is indexed **per frame** (fixes the CP13 indexing
   bug) using the live Argus flow field (s3-f01) + the gyro term (s1-f04) for global motion.
4. **Real noise model:** `noiseS`/`noiseO` (shot/read) come from `SENSOR_NOISE_PROFILE`
   (s1-f03), so `sigmaThresh` admits good frames and vetoes ghosts — not "reject all".
5. **Measurable gain (device):** on a static ISO-12233 chart, fused MTF50 ≥ single-frame MTF50
   (no resolution loss, target measurable gain); flat-field SNR improves toward √N; ΔE2000 of a
   gray chart unchanged (no color shift). On a moving subject, **no ghosting** (chroma-crawl /
   residual within baseline).
6. **No-downgrade default:** until device-validated, MFSR output is **not blended into the
   visible path** (or blended at strength 0) — activation is a flag the owner flips on-device.
   Today's pixels must be untouched.
7. **Budget:** fits the preview frame budget with adaptive N (drop N / skip under thermal/load
   via the governor); never blocks the cold-start path.

## 5. Implementation prompt (hand this to the agent)
**Scope:** make MFSR fuse *real* frames correctly and safely, behind a no-downgrade gate.
**Non-goals:** do not change the shader's regression math (it's correct); do not wire MFSR into
the visible blend by default; do not add any new model/library (no-vendor).

**Plan:**
1. **STEP A — kill the UB (CP12 Step A), safely.** Either (a) `vkCmdClearColorImage` the dummy
   array/image to a deterministic value before any MFSR dispatch, or (b) collapse
   `activeFrameCount` so no uninitialized layer is read. ⚠️ A blind clear could mask a black-out
   if `intermediate` actually reaches output on some device — so gate the *visible* effect off
   and verify on-device before relying on it. Add a validation-layer assertion in debug builds.
2. **STEP B — real seed (CP12 Step B, device-gated).** Promote the transform in
   `unusedRealtimeComputeDiagnostics()` (`vulkan_bridge.cpp:5880`) into the live path: build the
   N-frame ring (reuse the existing capture session frames), upload each as a layer of binding 0.
3. **STEP C — per-frame flow (CP13).** Index the flow binding per frame; feed Argus flow
   (s3-f01) + integrate the gyro global-motion term (s1-f04, `GyroOrientationIntegrator`).
4. **STEP D — real noise model.** Pipe `SENSOR_NOISE_PROFILE` shot/read coefficients (s1-f03)
   into `noiseS`/`noiseO`/`sigmaThresh` push constants.
5. **STEP E — ordering (CP14).** Ensure WB-before-demosaic and hard-gradient green
   interpolation so fusion operates in the right domain.

**Files:** `app/src/main/cpp/vulkan_bridge.cpp` (ring upload, binding wiring, clear), the MFSR
push-constant struct + its Kotlin mirror, `RawVideoManager`/capture ring source, noise-model
plumbing from `HardwareAbstractionLayer`.

**ABI:** if `noiseS/noiseO/sigmaThresh/frameCount` move into push constants, extend
`GpuPushConstantLayout` + add `MfsrPushConstantLayoutTest` asserting slot order & int/float
typing in the SAME commit; re-pin the shader-manifest hash.

**Gating:** new visible blend behind a specialization constant / flag defaulting to **off**
(strength 0 = exact passthrough). Today's output unchanged until the owner enables it.

## 6. Acceptance & verification
- **Unit/golden (JVM):** noise-model mapping test (profile → push constants); per-frame flow
  indexing test (frame f reads flow layer f); a host port of the veto logic proving good frames
  admitted / ghosts rejected with non-zero `sigmaThresh`.
- **Native:** `:app:externalNativeBuildDebug` green; debug validation-layer run reports **zero**
  uninitialized-read / UNDEFINED-layout warnings on the MFSR pass.
- **Device (vivo I2405 / Adreno):** (1) static ISO-12233 chart — MFSR-on MTF50 ≥ MFSR-off, no
  color shift; (2) flat gray field — SNR(dB) improves; (3) moving hand/subject — no ghosting;
  (4) thermal soak — N backs off, no frame-time spike, no process kill.
- **Gate keys:** if MFSR contributes to recorded master, its capability must prove through
  `OmegaCapabilityGate` from real transport — never synthesize a pass.

## 7. Risks & no-downgrade guardrails
- **UB / black-out:** deterministic clear + visible-effect gated off + device verify before
  trusting → impossible to ship a black frame.
- **Ghosting:** real `sigmaThresh` + 3σ veto + per-frame flow → misaligned samples rejected.
- **Grain amplification:** real heteroscedastic noise model bounds denoise strength; no blind
  iteration count.
- **Lag / thermal:** adaptive N + governor backoff; off the cold-start path.
- **Color shift:** WB-before-demosaic ordering + ΔE2000 gray-chart check.

## 8. References
- Roadmap: CP10/CP11/CP12/CP13/CP14 in [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Tech: [`../technology_explanations/markdown/10-vulkan-cinema-debayer.md`](../technology_explanations/markdown/10-vulkan-cinema-debayer.md), [`../technology_explanations/markdown/09-neoradiance-raw-and-npraw-modes.md`](../technology_explanations/markdown/09-neoradiance-raw-and-npraw-modes.md)
- Reference: Wronski et al., "Handheld Multi-Frame Super-Resolution", SIGGRAPH 2019.
- Source: `app/src/main/cpp/vulkan_bridge.cpp:39, 619, 3667, 3996, 4295, 5880`
- Related prompts: s1-f07, s1-f09, s1-f03, s1-f04, s3-f01
