# VulkanJointBilateralFilter / VulkanLogDenoiseFilter — Elite Upgrade Prompt

> Global contract in [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) (no-downgrade, no-vendor,
> commit-per-feature, golden-first, ABI-locked, device-gated) applies and is not restated.

| Field | Value |
|---|---|
| **Subsystem** | 4 — Denoise/Detail + HDR/Tone |
| **Prompt ID** | s4-f07 |
| **Maps to checkpoint(s)** | CP23 (activate a dormant flat-region detail-safe denoise pass) |
| **Current working level** | ⚪ test-only-scaffold (validated only by shader-contract tests; never compiled to SPV or dispatched) |
| **Feature rating (potential)** | 2/10 now → 5/10 target (only if they carry an algorithm the live nivukx path lacks) |
| **Code-quality rating (now)** | 5/10 (clean, small, parametrized shaders; but no SPV, no pipeline, no caller) |
| **Risk · Size** | M · M |
| **Depends on** | s4-f01 (live nivukx denoise — the incumbent; these only earn a place if complementary) |

## 1. What this feature is
Two small GLSL compute shaders that exist only as source + shader-contract tests:
- `VulkanJointBilateralFilter.comp` — a **joint/cross-bilateral** filter: it denoises the input image
  using a *separate guide image* (binding 1, `r16f`) for the range weight, so edges in the guide
  steer the smoothing (the classic "denoise color using a cleaner luma/structure guide" pattern).
- `VulkanLogDenoiseFilter.comp` — a **log-domain** denoise: it filters in a logarithmic/perceptual
  domain (the `ALPHA = 1.85` companding term) where noise is more uniform across the tonal range,
  conceptually adjacent to a variance-stabilizing approach.
Both operate on `rgb10_a2` transport (P010-adjacent). Neither is compiled to SPV, built into a
pipeline, or dispatched — they are pure scaffolding. The question this prompt answers honestly:
do they add anything the live `nivukx_denoise.comp` (s4-f01) lacks? If yes → WIRE one for CP23's
flat-region detail-safe pass. If no → RETIRE.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
- **Joint/cross-bilateral denoise** is a standard tool: smooth the noisy channel while a cleaner
  guide (often luma, or a less-noisy exposure) defines edges — preserving structure that a plain
  bilateral would blur. This is the basis of guided chroma denoise and flash/no-flash denoising.
- **Log-domain / VST denoise** addresses the signal-dependent nature of sensor noise: filtering in a
  companded/log space (or after an Anscombe transform) makes a fixed-strength filter behave uniformly
  across shadows and highlights — the same principle s4-f01/CP21 brings to the live path.
- **Flat-region detail-safe denoising** (CP23): cinema denoise distinguishes flat regions (denoise
  hard) from textured regions (denoise gently) so it never smears detail. A wavelet/NLM/joint-bilateral
  pass gated by a flatness measure is the textbook way to gain SNR in flats without losing MTF50.
The elite bar: *if* one of these is wired, it must improve flat-field SNR(dB) **without** reducing
MTF50 on a static chart and without adding chroma-crawl — i.e. it earns its place only by doing
something the live nivukx denoise measurably does not.

## 3. Current state in NeoProCamX (verified)
- Shaders: `app/src/main/cpp/shaders/vulkan/VulkanJointBilateralFilter.comp`
  (`SIGMA_S=2.4`, `SIGMA_R=0.082`, separate `guideImage` binding) and
  `app/src/main/cpp/shaders/vulkan/VulkanLogDenoiseFilter.comp` (`SIGMA_S=2.4`,
  `BASE_SIGMA_R=0.045`, `ALPHA=1.85` log companding, separate `guideImage`).
- **Test-only, verified:** the only references in the entire `app/src` tree are the shader-contract
  tests `app/src/test/.../rendering/VulkanJointBilateralFilterShaderContractTest.kt` and
  `VulkanLogDenoiseFilterShaderContractTest.kt`. There is **no SPV header** in `cpp/generated/`, **no
  `#include`** in `vulkan_bridge.cpp`, **no pipeline**, **no dispatch**. They never touch a pixel.
- **Relation to the live path:** the live denoise is `nivukx_denoise.comp` (s4-f01) — a 7×7 spatial
  bilateral in JzAzBz luma + temporal + local-SNR. The live path does **not** have (a) a *joint*
  bilateral using a separate guide image, nor (b) a *log-domain* filter. So these two are not exact
  duplicates of nivukx — they carry a genuinely different mechanism.

**Triage decision (honest):**
- **WIRE candidate (CP23) — at most ONE of them, behind a flatness gate.** The joint-bilateral
  (guide-driven, detail-safe in flats) is the better fit for CP23's "flat-region detail-safe pass."
  The log-domain idea is largely subsumed by s4-f01/CP21's Anscombe VST — prefer adding VST *to the
  live shader* over wiring a second log filter. So: **WIRE the joint-bilateral as the CP23 flat-region
  pass** (if the audit confirms it beats nivukx in flats), **RETIRE the log-denoise filter** as
  redundant with CP21.
- This is conditional: if the audit shows the joint-bilateral adds nothing over nivukx + CP21, RETIRE
  both. Do not wire a redundant pass just because it compiles.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **CP23 flat-region detail-safe pass (if WIRE):** a flatness measure gates the joint-bilateral so it
   denoises flats hard and textured regions gently. On a static ISO-12233 chart, MTF50 is **not
   reduced**; in a flat field, SNR(dB) improves **beyond** what s4-f01 (+CP21) alone achieves
   (the must-beat-the-incumbent bar).
2. **Guide correctness:** the guide image (luma/structure) drives the range weight so edges in the
   guide stop the smoothing; a synthetic edge in the guide blocks bleed across it (edge-position test).
3. **No chroma-crawl:** opponent-space chroma-crawl does not increase.
4. **RETIRE log-denoise:** removed (or quarantined, documented "superseded by CP21 VST in
   nivukx_denoise") with zero live-pixel change.
5. **No-downgrade default:** if wired, the pass is gated behind a spec-constant defaulting OFF
   (strength 0 = passthrough); today's pixels bit-for-bit unchanged until owner enables on-device.
6. **Budget:** the joint-bilateral is one extra compute pass — it must fit the preview budget and be
   governor-droppable, or run only on the export path. If it can't beat nivukx within budget, RETIRE.

## 5. Implementation prompt (hand this to the agent)
**Scope:** audit both filters against the live nivukx path; WIRE the joint-bilateral as the CP23
flat-region detail-safe pass only if it measurably wins; RETIRE the log-denoise (and the joint-bilateral
too if it doesn't win). **Non-goals:** do not wire both; do not add a redundant pass; do not change
the live `nivukx_denoise.comp` (s4-f01 owns it); no new model/library (no-vendor).

**Plan:**
1. **STEP 0 — audit (decide WIRE vs RETIRE).** Port both shaders' math to a host test; compare flat-field
   SNR + MTF50 + chroma-crawl against the live nivukx (+ CP21 VST if landed) on the same goldens.
   Record the numbers. This is the gate for everything below.
2. **STEP A — if WIRE: compile + pipeline.** Generate the SPV header for `VulkanJointBilateralFilter.comp`,
   `#include` it, build the descriptor layout (input/guide/output) + pipeline in `vulkan_bridge.cpp`,
   add a flatness-gated dispatch. Gate behind `SC_FLAT_DETAIL_SAFE_DENOISE` default OFF.
3. **STEP B — RETIRE log-denoise.** Remove (or quarantine + document) `VulkanLogDenoiseFilter.comp` as
   superseded by CP21; keep/retire its contract test accordingly. Prove zero live-dispatch delta via the
   CP36 dispatch-manifest test (s4-f06).
4. **STEP C — if the audit shows no win: RETIRE both** with the same zero-delta proof. Honest outcome.
5. **STEP D — device note.** If wired, on-device enable + the vivo flat/chart checks (below).

**Files:** `cpp/shaders/vulkan/VulkanJointBilateralFilter.comp` (+ generated SPV if wired),
`cpp/shaders/vulkan/VulkanLogDenoiseFilter.comp` (retire), `cpp/vulkan_bridge.cpp` (pipeline/descriptor/
dispatch if wired), the flatness-measure source (reuse the local-SNR estimate from s4-f01), the
shader-contract tests under `app/src/test/.../rendering/`.

**ABI:** if the joint-bilateral gains push constants (sigmas, flatness threshold), add a Kotlin mirror +
`GpuPushConstantLayout` extension + `*PushConstantLayoutTest` in the SAME commit and a shader-manifest
hash entry. std140 alignment required. On RETIRE, no ABI change.

**Gating:** any wired pass behind a spec-constant default OFF; RETIRE proven by the CP36 manifest test.

## 6. Acceptance & verification
- **Unit/golden (JVM):** STEP-0 audit numbers recorded; if wired, flat-field SNR-beats-nivukx test +
  MTF50-not-reduced test + guide edge-position test + chroma-crawl test; existing shader-contract tests
  updated; RETIRE proven by the full golden suite + CP36 manifest test staying green.
- **Native:** `:app:externalNativeBuildDebug` green; validation-layer clean on any new pass; manifest
  hash added if wired.
- **Device (vivo I2405 / Adreno), only if wired:** (1) flat field — SNR up beyond nivukx, no banding;
  (2) ISO-12233 chart — MTF50 held; (3) gate off — output bit-for-bit unchanged; (4) thermal — pass
  drops cleanly.
- **Gate keys:** any denoise/SNR claim proves through `OmegaCapabilityGate` from real pixels.

## 7. Risks & no-downgrade guardrails
- **Wiring a redundant pass (waste + lag):** STEP-0 must-beat-nivukx audit gate; RETIRE if it doesn't win.
- **Detail smear:** flatness gate + MTF50-not-reduced golden + guide edge-position test.
- **Live-pixel change on RETIRE:** CP36 dispatch-manifest test makes any live-set delta fail.
- **Lag / thermal:** one extra pass max, governor-droppable or export-only; default-off gate.
- **Double-counting VST:** RETIRE the log filter rather than duplicating CP21's Anscombe in the live shader.

## 8. References
- Roadmap: CP23 (and CP36 manifest test) in [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Tech: [`../technology_explanations/markdown/02-nivukx-vision.md`](../technology_explanations/markdown/02-nivukx-vision.md)
- Reference: joint/cross-bilateral filter (Eisemann & Durand flash/no-flash; Petschnigg et al.); guided filter (He et al.); log-domain / VST denoising; flat-region detail-safe NLM/wavelet denoise; ISO-12233 MTF50.
- Source: `app/src/main/cpp/shaders/vulkan/VulkanJointBilateralFilter.comp`, `.../VulkanLogDenoiseFilter.comp`; tests `app/src/test/java/com/neoproc/neoproccamx/rendering/VulkanJointBilateralFilterShaderContractTest.kt`, `.../VulkanLogDenoiseFilterShaderContractTest.kt`
- Related prompts: s4-f01 (live nivukx denoise — the incumbent + CP21 VST), s4-f06 (dormant denoise/sharpen shaders + CP36 manifest test)
