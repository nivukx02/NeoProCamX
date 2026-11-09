# NIVUKX Realtime Denoise (`nivukx_denoise.comp`, Pass 5) — Elite Upgrade Prompt

> Global contract in [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) (no-downgrade, no-vendor,
> commit-per-feature, golden-first, ABI-locked, device-gated) applies and is not restated.

| Field | Value |
|---|---|
| **Subsystem** | 4 — Denoise/Detail + HDR/Tone |
| **Prompt ID** | s4-f01 |
| **Maps to checkpoint(s)** | CP20 (motion-compensated temporal history), CP21 (Generalized-Anscombe VST), CP22 (perceptual chroma low-pass) |
| **Current working level** | 🟢 live-default-on (Pass 5 dispatched every preview frame) |
| **Feature rating (potential)** | 8/10 |
| **Code-quality rating (now)** | 8/10 (shader math sound; temporal/VST/chroma stages are the elite gap) |
| **Risk · Size** | M · L |
| **Depends on** | s3-f01 (Argus optical flow — `omniFlowFull` for CP20), s1-f03 (sensor noise model — shot/read for CP21) |

## 1. What this feature is
NIVUKX denoise is the live Pass-5 GPU compute stage that cleans the working ACEScg/linear image
before tone/grade. It runs an edge-aware 7×7 spatial bilateral in JzAzBz luma, a temporal denoise
against the previous-aligned frame with Tukey-biweight residual rejection, and a local-SNR
estimator that scales strength. It is the primary noise floor of every preview and record frame,
so it sets the SNR/grain character the rest of the pipeline inherits. Today it is real and
default-on; the elite gap is that its temporal history is *not motion-compensated*, its spatial
denoise operates in a non-variance-stabilized domain, and its chroma cleanup is a luma-subtract
speckle suppressor rather than a true perceptual low-pass.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
Cinema denoise is dominated by *information preservation*: ARRI/RED keep grain organic and never
smear edges, and IMAX DMR reconstructs grain/sharpness per-frame rather than blurring it away.
The computational gold standard NIVUKX must match:
- **Motion-compensated temporal accumulation.** Pixels are averaged along true motion trajectories
  (optical flow), not at fixed screen coordinates — this is what lets temporal denoise gain SNR
  without ghosting on a moving subject. Static temporal averaging (current state) ghosts.
- **Variance-Stabilizing Transform before denoising.** Raw sensor noise is Poisson-Gaussian
  (`σ² = σ_read² + σ_shot²·signal`), so it is *signal-dependent*. The Generalized Anscombe
  Transform (GAT) maps it to ≈unit-variance Gaussian, you denoise in that space, then apply the
  exact unbiased inverse. This is standard in scientific/astro and the correct foundation for
  bilateral/NLM strength to be uniform across the tonal range.
- **Perceptual opponent-channel chroma denoise.** Chroma noise is low-frequency and perceptually
  separable; cinema pipelines low-pass chroma in an opponent space (Jz a b / Lab-like) far more
  aggressively than luma, recovering "clean color, crisp detail." A luma-subtract speckle hack
  cannot match this without desaturating edges.
The elite bar: flat-field SNR(dB) improves measurably (toward the temporal-N gain) with **zero
ghosting** on a moving target, MTF50 on a static chart is **not reduced**, banding code-delta does
not increase, and chroma-crawl in opponent space drops — all within the per-frame preview budget.

## 3. Current state in NeoProCamX (verified)
- Shader source: `app/src/main/cpp/shaders/vulkan/nivukx_denoise.comp`; SPV header included
  `vulkan_bridge.cpp:41`.
- **It is genuinely live.** Pipeline created at `vulkan_bridge.cpp:4150-4162` (`"nivukx_denoise"`),
  descriptor sets allocated `4660/4692`, written `4394-4408`, and the pass is **bound and dispatched**
  every frame — `vkCmdBindPipeline(... m_nivukxDenoisePipeline)` `vulkan_bridge.cpp:6414`,
  descriptor bound `6418-6421`. Dispatch gated by pipeline+descriptor readiness `5543-5548`.
- **The elite gaps (verified by the catalog §4 + roadmap Phase 2):**
  - *Temporal history is not motion-compensated* — temporal denoise compares against the
    previous frame at fixed coordinates; CP20 calls for using `omniFlowFull` (the live Argus flow
    field, s3-f01) to warp history along motion.
  - *No VST* — the spatial bilateral runs directly in JzAzBz luma, not in an Anscombe-stabilized
    domain; CP21 adds the GAT + exact unbiased inverse around the spatial denoise.
  - *Chroma cleanup is a luma-subtract speckle suppressor* — CP22 replaces it with a true
    perceptual opponent-channel low-pass.
- Net: this is a solid, real, default-on denoiser — the work is **hardening + elite extension**,
  not resurrection. Every change touches live pixels → golden-first is mandatory.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Motion-compensated temporal (CP20):** temporal history is warped by `omniFlowFull` before
   blending; on a moving-subject golden, residual ghosting ≤ baseline (chroma-crawl metric within
   baseline). On a static golden, temporal SNR(dB) gain ≥ current static-temporal gain (no loss).
2. **VST correctness (CP21):** GAT forward → denoise → exact unbiased inverse round-trips to the
   identity within tolerance when denoise strength = 0 (proves no DC/bias shift); with strength on,
   flat-field SNR(dB) improves uniformly across low/mid/high tones (variance is signal-independent
   post-transform). Noise coefficients come from `SENSOR_NOISE_PROFILE` (s1-f03), not constants.
3. **Perceptual chroma low-pass (CP22):** opponent-space chroma-crawl drops vs the luma-subtract
   path; edge chroma is **not** desaturated (ΔE2000 on a saturated edge chart within baseline);
   luma MTF50 unchanged (chroma low-pass must not touch luma detail).
4. **No-downgrade default:** each new stage is gated behind a specialization constant / strength
   whose default reproduces today's `nivukx_denoise.comp` output bit-for-bit until the owner
   enables it device-side. Today's preview/record pixels are untouched until that flip.
5. **Budget:** total Pass-5 cost stays within the preview frame budget; motion-comp warp reuses the
   already-computed `omniFlowFull` (no new flow pass). Under thermal/load the governor may drop the
   temporal stage to spatial-only (graceful, never a black-out).
6. **Regression gate (feeds CP25):** SNR/ghosting/MTF50 deltas are asserted by golden tests so a
   later edit cannot silently regress denoise.

## 5. Implementation prompt (hand this to the agent)
**Scope:** add motion-compensated temporal, GAT VST, and perceptual chroma low-pass to the live
NIVUKX denoise, each behind a no-downgrade gate. **Non-goals:** do not change the spatial
bilateral kernel radius/weights of the default path; do not introduce a new flow pass (reuse
`omniFlowFull`); do not add any model/library (no-vendor); do not touch the export-side denoise
(that unification is CP81, out of scope here).

**Plan:**
1. **STEP 0 — golden lock (mandatory).** Add a characterization test that freezes the current
   `nivukx_denoise.comp` output on representative frames (flat field, edge chart, moving subject
   synthetic) so the default-gate path is provably unchanged.
2. **STEP A — CP20 motion-comp temporal.** Bind `omniFlowFull` to the denoise descriptor set;
   warp the previous-frame history sample by the per-pixel flow before the Tukey-biweight temporal
   blend. Gate behind `SC_TEMPORAL_MOTION_COMP` (default off = current fixed-coordinate temporal).
3. **STEP B — CP21 GAT VST.** Wrap the spatial bilateral in a Generalized Anscombe forward
   transform (params from `SENSOR_NOISE_PROFILE`) and apply the **exact unbiased inverse** (not the
   naive algebraic inverse). Gate behind `SC_VST_DENOISE` (default off).
4. **STEP C — CP22 perceptual chroma low-pass.** Replace the luma-subtract chroma path with an
   opponent-channel (Jz a b) low-pass that leaves luma untouched. Gate behind `SC_PERCEPTUAL_CHROMA`
   (default off = current speckle suppressor).
5. **STEP D — device note.** Each gate's on-device enable + the exact vivo check (below).

**Files:** `app/src/main/cpp/shaders/vulkan/nivukx_denoise.comp` (the three gated stages),
`app/src/main/cpp/vulkan_bridge.cpp` (omniFlowFull binding into the nivukx denoise descriptor set;
noise-profile push-constant plumbing), the noise-model plumbing from `HardwareAbstractionLayer`
(`SENSOR_NOISE_PROFILE`), the SPV regen + included header.

**ABI:** if VST noise coefficients / chroma-low-pass radius enter a push constant or UBO, extend
`GpuPushConstantLayout` + the matching `*PushConstantLayoutTest` in the SAME commit and re-pin the
shader-manifest hash for `nivukx_denoise`. std140 alignment is not optional.

**Gating:** three independent specialization constants, all defaulting OFF so the shader emits
today's output bit-for-bit; the owner flips each on-device after validation.

## 6. Acceptance & verification
- **Unit/golden (JVM):** the STEP-0 characterization lock stays green with all gates default-off;
  a host port of the GAT forward+inverse proves identity at strength 0; a noise-profile→push-constant
  mapping test.
- **Native:** `:app:externalNativeBuildDebug` green; debug validation-layer run on Pass 5 reports
  zero warnings; shader-manifest hash re-pinned.
- **Device (vivo I2405 / Adreno):** (1) flat gray field — SNR(dB) improves with VST+temporal on,
  no banding increase; (2) moving hand — no ghosting with motion-comp temporal on; (3) saturated
  edge chart — chroma-crawl drops, edge ΔE2000 within baseline, luma MTF50 unchanged; (4) thermal
  soak — temporal stage backs off cleanly, no frame-time spike.
- **Gate keys:** denoise contributes to the recorded master; any quality claim must prove through
  `OmegaCapabilityGate` from real pixels, never synthesized.

## 7. Risks & no-downgrade guardrails
- **Ghosting (temporal):** motion-comp warp + Tukey-biweight residual rejection + occlusion fallback
  → misaligned history rejected; default-off gate means zero risk until validated.
- **Grain amplification / DC shift (VST):** exact unbiased inverse + identity-at-strength-0 test →
  no bias; noise coefficients bounded from the real profile, not a free knob.
- **Chroma desaturation (chroma low-pass):** opponent-space low-pass leaves luma untouched + edge
  ΔE2000 golden → edges stay colored.
- **Lag / thermal:** reuse `omniFlowFull` (no new flow pass) + governor drops temporal under load.
- **Silent regression:** STEP-0 golden lock + CP25 SNR/ghosting/MTF50 gate make any future drift fail CI.

## 8. References
- Roadmap: CP20/CP21/CP22 (and CP25 gate, CP81 export-unification) in [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Tech: [`../technology_explanations/markdown/02-nivukx-vision.md`](../technology_explanations/markdown/02-nivukx-vision.md), [`../technology_explanations/markdown/NIVUKX_VISION_DETAILED_TECHNOLOGY.md`](../technology_explanations/markdown/NIVUKX_VISION_DETAILED_TECHNOLOGY.md)
- Reference: Generalized Anscombe VST (Mäkitalo–Foi exact unbiased inverse); motion-compensated temporal denoise; Tukey biweight robust estimation; IMAX DMR grain reconstruction.
- Source: `app/src/main/cpp/shaders/vulkan/nivukx_denoise.comp`, `app/src/main/cpp/vulkan_bridge.cpp:41, 4150, 4394, 5543, 6414`
- Related prompts: s3-f01 (Argus flow / omniFlowFull), s1-f03 (sensor noise model), s4-f07 (dormant bilateral/log-denoise filters considered for CP23)
