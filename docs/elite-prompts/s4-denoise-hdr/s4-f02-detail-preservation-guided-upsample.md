# Detail Preservation + Guided Upsample — Elite Upgrade Prompt

> Global contract in [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) (no-downgrade, no-vendor,
> commit-per-feature, golden-first, ABI-locked, device-gated) applies and is not restated.

| Field | Value |
|---|---|
| **Subsystem** | 4 — Denoise/Detail + HDR/Tone |
| **Prompt ID** | s4-f02 |
| **Maps to checkpoint(s)** | CP24 (re-audit + upgrade detail/sharpen subsystem — auditor was dropped, redo) |
| **Current working level** | 🟢 live-default-on (both passes dispatched every preview frame) |
| **Feature rating (potential)** | 7/10 |
| **Code-quality rating (now)** | 7/10 (passes run; the detail mask + sharpen response are the elite gap) |
| **Risk · Size** | M · M |
| **Depends on** | s4-f01 (NIVUKX denoise — detail mask should be denoise-aware), s5 focus/depth maps (guided-upsample guide source) |

## 1. What this feature is
Two cooperating Pass stages. `detail_preservation.comp` builds a texture/edge detail mask and
re-injects high-frequency structure that denoise/tone stages would otherwise soften — the
"crisp without ringing" stage. `guided_upsample.comp` does a joint-guided 4× upsample of the
low-resolution focus/detail maps using the full-res luma as the guide, so masks computed cheaply
at quarter-res align to real edges at full res. Both are real and dispatched every frame
(detail at `vulkan_bridge.cpp:6163`, guided at `6199`), in the live pass chain right after MFSR
and before the knee/tone stages. The elite gap is that the detail response and the upsample
edge-stopping are tuned by hardcoded C++ constants, not scene- or noise-aware policy, and the
subsystem was never re-audited after its auditor was dropped (CP24).

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
The cinema rule is **detail without artifacts**: no halos, no ringing, no grain amplification.
- **Edge-aware, halo-free detail.** ARRI Textures and RED IPP2 add micro-contrast via local,
  edge-respecting operators that never overshoot at high-contrast edges. The reference behavior is
  an unsharp/guided response whose gain rolls off where local contrast (or noise) is high, so flat
  regions stay clean and edges stay crisp without a bright/dark fringe.
- **Guided / joint-bilateral upsampling.** The standard for cheaply-computed maps (depth, detail,
  focus) is joint-bilateral / guided-filter upsampling (He et al. guided filter; Kopf et al. joint
  bilateral upsampling): the high-res guide image drives the interpolation so the upsampled map
  snaps to true edges instead of bleeding across them. MTF50 of the guided result on a slanted
  edge should track the guide, not the low-res source.
- **IMAX DMR** reconstructs per-frame sharpness rather than applying a global sharpen, i.e.
  detail gain is *spatially adaptive* to local structure.
The elite bar: measurable MTF50 retention/gain on a static chart with **no overshoot ringing**
(edge-profile overshoot ≤ threshold), guided-upsample edge alignment within sub-pixel of the
full-res guide, and **no grain amplification** in flat fields (flat-field SNR not reduced).

## 3. Current state in NeoProCamX (verified)
- Shaders: `app/src/main/cpp/shaders/vulkan/detail_preservation.comp`,
  `app/src/main/cpp/shaders/vulkan/guided_upsample.comp`. SPV headers included
  `vulkan_bridge.cpp:36` (guided) and `38` (detail).
- **Both are genuinely live.** Descriptor layouts built `vulkan_bridge.cpp:3897` (guided,
  `"guided_upsample"` 3919) and `3944` (detail, `"detail_preservation"` 3966); pipelines created
  `3917-3918` / `3964-3965`; bound + dispatched every frame — detail
  `vkCmdBindPipeline(... detailPreservationPipeline)` `6163`, `vkCmdDispatch` `6185`; guided
  `vkCmdBindPipeline(... guidedUpsamplePipeline)` `6199`, `vkCmdDispatch` `6222`. They sit in the
  live chain: MFSR(6125) → **detail(6163) → guided(6199)** → knee(6249) → shadow → imax → nivukx.
- **The elite gap (CP24):** the detail mask response and the guided-upsample edge-stopping strength
  run on **hardcoded native defaults**, not scene/noise-aware policy. Per catalog §3 (subsystem 3
  OmniClarity), the Kotlin tuning-policy pattern exists elsewhere but here the response is fixed.
  The subsystem's auditor was dropped → CP24 is an explicit "redo the audit + upgrade" item.
- Net: real, default-on, correct-shape passes — work is **hardening + adaptive tuning**, not
  resurrection. Live pixels are touched → golden-first mandatory.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Halo-free detail (CP24):** on a high-contrast slanted-edge golden, detail re-injection
   increases MTF50 (or holds it) with edge-profile overshoot below a fixed threshold (no ringing).
   Quantify with the ISO-12233 ESF overshoot metric in `ImageQualityMetrics`.
2. **Noise-aware detail gain:** in a flat field, detail gain rolls off so flat-field SNR(dB) is
   **not reduced** (no grain amplification); the rolloff is driven by the local SNR estimate
   shared with s4-f01, not a constant.
3. **Guided-upsample edge fidelity:** upsampled focus/detail map edges align to the full-res luma
   guide within sub-pixel error on a synthetic edge; a quarter-res map with a misplaced edge snaps
   to the guide edge (joint-bilateral behavior), measurable as edge-position error.
4. **Re-audit closure (CP24):** the dropped audit is re-established as a characterization/golden
   test set so the subsystem cannot silently regress again.
5. **No-downgrade default:** any new adaptive response is gated behind a flag/spec-constant whose
   default reproduces today's hardcoded-constant output bit-for-bit until the owner enables it.
6. **Budget:** both passes stay within the preview frame budget; guided upsample remains a single
   joint pass (no extra full-res convolutions); governor may skip detail under thermal/load.

## 5. Implementation prompt (hand this to the agent)
**Scope:** re-audit and elevate the detail/guided subsystem to halo-free, noise-aware, edge-faithful
behavior behind a no-downgrade gate. **Non-goals:** do not change the live default response (gate
all new behavior); do not merge this with the dormant `sharpen.comp` (that is s4-f06 — decide there);
do not add any model/library (no-vendor).

**Plan:**
1. **STEP 0 — golden lock + re-audit.** Re-establish the dropped audit as a golden set: freeze the
   current detail+guided output on edge/flat/texture goldens (proves default unchanged) and add the
   overshoot + SNR + edge-position metrics from `ImageQualityMetrics`.
2. **STEP A — noise-aware detail gain.** Feed the local SNR estimate (shared with s4-f01) into the
   detail re-injection so gain rolls off in noisy/flat regions. Gate behind `SC_DETAIL_NOISE_AWARE`
   (default off = current constant gain).
3. **STEP B — overshoot control.** Add an edge-overshoot clamp to the detail response so high-contrast
   edges cannot ring. Gate behind the same constant or `SC_DETAIL_HALO_GUARD`.
4. **STEP C — guided-upsample edge-stopping audit.** Verify/upgrade the guide-driven weighting so
   upsampled maps snap to guide edges; tune the range/spatial sigmas as policy, gated.
5. **STEP D — device note.** Per-gate on-device enable + the vivo checks (below).

**Files:** `app/src/main/cpp/shaders/vulkan/detail_preservation.comp`,
`app/src/main/cpp/shaders/vulkan/guided_upsample.comp`, `app/src/main/cpp/vulkan_bridge.cpp`
(any new push-constant/UBO plumbing for adaptive params; SNR feed), the SPV regen + included headers,
a new tuning-policy Kotlin class if the response becomes scene-driven (unit-tested in isolation).

**ABI:** if adaptive params (detail gain, overshoot clamp, upsample sigmas) enter push constants/UBO,
extend `GpuPushConstantLayout` + the matching `*PushConstantLayoutTest` in the SAME commit and re-pin
the shader-manifest hashes for both shaders. std140 alignment required.

**Gating:** spec-constants default OFF so both shaders emit today's output bit-for-bit; owner flips
on-device after validation.

## 6. Acceptance & verification
- **Unit/golden (JVM):** STEP-0 lock green with gates off; overshoot metric below threshold with
  halo-guard on; flat-field SNR not reduced with noise-aware gain on; guided-upsample edge-position
  test passes.
- **Native:** `:app:externalNativeBuildDebug` green; validation-layer clean on both passes;
  shader-manifest hashes re-pinned.
- **Device (vivo I2405 / Adreno):** (1) slanted-edge chart — MTF50 held/up, no visible ring;
  (2) flat field — no grain amplification; (3) defocus map on a real depth scene — upsampled mask
  hugs subject edges; (4) thermal — detail skips cleanly, no frame-time spike.
- **Gate keys:** detail/sharpen contributes to the master; any sharpness claim proves through
  `OmegaCapabilityGate` from real pixels.

## 7. Risks & no-downgrade guardrails
- **Ringing / halos:** edge-overshoot clamp + ESF overshoot golden → halos impossible-by-construction;
  default-off gate means zero risk until validated.
- **Grain amplification:** noise-aware rolloff + flat-field SNR golden.
- **Edge bleed (upsample):** guide-driven weighting + edge-position test.
- **Lag / thermal:** single joint upsample pass + governor skip of detail under load.
- **Silent regression (the original failure):** STEP-0 re-audit golden set is the CP24 deliverable.

## 8. References
- Roadmap: CP24 (and CP25 denoise/detail gate) in [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Tech: [`../technology_explanations/markdown/02-nivukx-vision.md`](../technology_explanations/markdown/02-nivukx-vision.md)
- Reference: He et al. "Guided Image Filtering"; Kopf et al. "Joint Bilateral Upsampling" (SIGGRAPH 2007); ISO-12233 slanted-edge MTF50 + edge overshoot; ARRI Textures / RED IPP2 detail handling; IMAX DMR per-frame sharpness.
- Source: `app/src/main/cpp/shaders/vulkan/detail_preservation.comp`, `.../guided_upsample.comp`, `app/src/main/cpp/vulkan_bridge.cpp:36, 38, 3897, 3944, 6163, 6199`
- Related prompts: s4-f01 (shared local-SNR estimate), s4-f06 (dormant `sharpen.comp` decision), s4-f07 (dormant bilateral filters)
