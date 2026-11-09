# Still-Capture HDR Bracket Merge (`hdr_merge.comp`) — Elite Upgrade Prompt

> Global contract in [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) (no-downgrade, no-vendor,
> commit-per-feature, golden-first, ABI-locked, device-gated) applies and is not restated.

| Field | Value |
|---|---|
| **Subsystem** | 4 — Denoise/Detail + HDR/Tone |
| **Prompt ID** | s4-f04 |
| **Maps to checkpoint(s)** | CP35 (still HDR merge 8-bit → ACEScg 16f) |
| **Current working level** | 🔵 live-optional (runs on still HDR capture via `StillCaptureImagePipeline`) |
| **Feature rating (potential)** | 7/10 |
| **Code-quality rating (now)** | 7/10 (real GPU merge + CPU fallback; the 8-bit precision floor is the elite gap) |
| **Risk · Size** | M · L |
| **Depends on** | s4-f03 (tone curve applied to merged result), s4-f05 (AI HDR policy / tier selection) |

## 1. What this feature is
A GPU compute exposure-bracket fusion for still capture: `hdr_merge.comp` (driven by
`hdr_merge_native.cpp`, surfaced via `HdrMergeNative.kt`) merges an N-frame exposure bracket
(N ≤ 8) into one high-dynamic-range still, with quality tiers FAST/BALANCED/ULTRA/CINEMA and a CPU
`stackHDR` fallback when the GPU path is unavailable. It is the still-photo counterpart to the
realtime video tone path (s4-f03). It is live-optional — invoked from
`StillCaptureImagePipeline` (and `AIProcessor`) on HDR still capture. **The elite gap (CP35):** the
merge ingests/produces **8-bit sRGB** (the shader has a `linearToSrgb8` quantizer) even though it
computes in ACEScg linear — so it throws away dynamic range at the I/O boundary. Elite target is an
**8-bit → ACEScg 16f** merge: read the 8-bit brackets, fuse in ACEScg, and keep a 16-bit float
result so the subsequent tone map (s4-f03) has real headroom instead of pre-quantized highlights.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
- **Debevec/Malik radiometric merge.** The canonical exposure-bracket fusion recovers a linear
  radiance map by weighting each exposure by a confidence (well-exposedness) curve that
  down-weights clipped/black pixels, scaled by the per-frame exposure ratio. The result is a
  *scene-linear* radiance estimate, not a tone-mapped 8-bit image.
- **High-precision working space.** Cinema/HDR-still pipelines fuse and store in a wide-gamut,
  high-precision linear space (ACEScg / OpenEXR 16f), tone-mapping only at the very end — so no
  dynamic range is lost between capture and master. Quantizing to 8-bit sRGB *before* the merge or
  before tone mapping is the classic precision-loss mistake.
- **Ghost suppression.** Multi-exposure merges reject moving content per-pixel (deghosting) so a
  bracket of a moving scene doesn't double-image — analogous to the 3σ veto in MFSR.
The elite bar: a radiance-weighted merge in ACEScg 16f with measurable dynamic-range gain over any
single bracket frame (recovered tonal range, SNR in shadows), **no ghosting** on a moving bracket,
and **no precision loss** at the I/O boundary (round-trip of a 16f ramp through the merge is
monotonic and not posterized).

## 3. Current state in NeoProCamX (verified)
- Shader: `app/src/main/cpp/shaders/vulkan/hdr_merge.comp`; native driver
  `app/src/main/cpp/hdr_merge_native.cpp` (includes `generated/hdr_merge_comp_spv.h`,
  `kMaxHdrFrames = 8u`, `HdrPushConstants{ width,height,frameCount,fastApproximation,
  exposureScales[8], normalizationFactors[8] }`).
- **It is genuinely live-optional.** Kotlin surface `capture/HdrMergeNative.kt` is called from
  `capture/StillCaptureImagePipeline.kt` and `ai/AIProcessor.kt` (real production callers, not
  tests). CPU `stackHDR` fallback exists. Quality tiers FAST/BALANCED/ULTRA/CINEMA selected via
  `fastApproximation` + tier policy.
- **The 8-bit floor (CP35) confirmed in source:** `hdr_merge.comp` computes in ACEScg
  (`NEO_ACESCG_NON_CERTIFIED`, `ACESCG_LINEAR_WORKING_SPACE`, sRGB↔ACEScg matrices) but has a
  `linearToSrgb8(...)` quantizer (`hdr_merge.comp:46`) — the result is rendered back to **8-bit
  sRGB**, discarding the high-precision radiance the merge produced.
- Net: real, working, optional still merge — work is **raise the I/O precision (8-bit → ACEScg
  16f)** and add radiometric weighting + deghosting rigor, behind a no-downgrade gate. The default
  still-JPEG output must be unchanged until the 16f path is enabled.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **8-bit → ACEScg 16f (CP35):** the merge ingests the 8-bit brackets, fuses in ACEScg, and emits
   a **16-bit float** ACEScg result (no `linearToSrgb8` on the elite path). A 16f ramp round-tripped
   through the merge is monotonic and shows no 8-bit posterization (banding code-delta near zero).
2. **Radiometric (Debevec) weighting:** each frame weighted by a well-exposedness curve scaled by
   `exposureScales`; clipped/black pixels down-weighted. On a synthetic 3-bracket scene, recovered
   shadow SNR(dB) and highlight detail exceed any single bracket frame.
3. **Deghosting:** on a moving-subject bracket, no double-image (per-pixel reference rejection);
   ghosting metric within baseline.
4. **Tone-map hand-off:** the 16f result feeds the s4-f03 tone curve so highlights are rolled by the
   peak-nits path, not pre-clamped (proves the precision is actually used downstream).
5. **No-downgrade default:** the 16f path is gated; the default still-capture output (8-bit JPEG)
   is bit-for-bit unchanged until the owner enables 16f. CPU `stackHDR` fallback preserved.
6. **Budget:** still capture is not realtime-budgeted, but the merge must not regress capture
   latency on the default path; 16f doubles working-buffer size — bounded by `kMaxHdrFrames`.

## 5. Implementation prompt (hand this to the agent)
**Scope:** raise the still HDR merge to an ACEScg 16f radiance pipeline with radiometric weighting
and deghosting, behind a no-downgrade gate. **Non-goals:** do not change the default 8-bit output
(gate the 16f path); do not remove the CPU fallback; do not add a new model/library (no-vendor —
Debevec weighting is classical); the final tone map stays in s4-f03.

**Plan:**
1. **STEP 0 — golden lock.** Freeze the current 8-bit merge output on a fixed bracket set (proves
   default unchanged). Add metrics: banding code-delta, shadow SNR, ghosting on a synthetic move.
2. **STEP A — CP35 16f I/O.** Add a 16f ACEScg output path to `hdr_merge.comp` (skip
   `linearToSrgb8`), with the result buffer/format plumbed in `hdr_merge_native.cpp`. Gate behind a
   `fastApproximation`-adjacent flag / new `outputAcesCg16f` push-constant field (default off = 8-bit).
3. **STEP B — radiometric weighting.** Replace any naive average with a Debevec well-exposedness
   weight × `exposureScales`, down-weighting clipped/black. Bounded; gated.
4. **STEP C — deghosting.** Add per-pixel reference rejection (reference exposure + residual veto)
   for moving content. Gated.
5. **STEP D — hand-off + device note.** Route the 16f result into the s4-f03 tone curve; record the
   vivo still-capture checks.

**Files:** `app/src/main/cpp/shaders/vulkan/hdr_merge.comp`, `app/src/main/cpp/hdr_merge_native.cpp`
(`HdrPushConstants` extension + 16f buffer/format), `capture/HdrMergeNative.kt` (tier/flag surface),
`capture/StillCaptureImagePipeline.kt` (16f hand-off), SPV regen + header.

**ABI:** extending `HdrPushConstants` (e.g. `outputAcesCg16f`, deghost params) **requires** mirroring
the Kotlin struct, extending `GpuPushConstantLayout` + the matching `*PushConstantLayoutTest` in the
SAME commit, and re-pinning the `hdr_merge` shader-manifest hash. std140 alignment required (note the
`exposureScales[8]`/`normalizationFactors[8]` arrays already in the struct).

**Gating:** flag/push-constant defaults to the current 8-bit path; 16f is owner-enabled. CPU fallback
untouched.

## 6. Acceptance & verification
- **Unit/golden (JVM):** STEP-0 8-bit lock green with gate off; a host port of the Debevec weighting
  proves clipped/black down-weighting; a 16f-ramp round-trip test proves no posterization; ghosting
  test on a synthetic moving bracket.
- **Native:** `:app:externalNativeBuildDebug` green; validation-layer clean on the merge dispatch;
  manifest hash re-pinned; layout test extended.
- **Device (vivo I2405 / Adreno):** (1) static 3-bracket HDR scene — recovered shadow+highlight
  detail exceeds any single frame, no banding; (2) moving-subject bracket — no double-image;
  (3) default capture (gate off) — output identical to pre-change; (4) capture-latency not regressed.
- **Gate keys:** if the 16f master feeds a recorded/exported HDR still, the dynamic-range claim must
  prove through `OmegaCapabilityGate` from real bit-depth — never synthesized.

## 7. Risks & no-downgrade guardrails
- **Precision regression / banding:** 16f working buffer + skip 8-bit quantizer on the elite path +
  ramp round-trip golden; default 8-bit path untouched (gate off).
- **Ghosting:** per-pixel reference rejection + ghosting golden.
- **Color shift:** ACEScg working space matrices verified; tone map deferred to s4-f03.
- **Memory / latency:** 16f bounded by `kMaxHdrFrames`; default path unchanged; CPU fallback kept.
- **Silent regression:** STEP-0 8-bit golden lock + the CP36 dispatch-manifest characterization test.

## 8. References
- Roadmap: CP35 (and CP36 HDR dispatch-manifest test) in [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Tech: [`../technology_explanations/markdown/01-ndr10-dynamic-hdr.md`](../technology_explanations/markdown/01-ndr10-dynamic-hdr.md)
- Reference: Debevec & Malik "Recovering High Dynamic Range Radiance Maps" (SIGGRAPH 1997); exposure-bracket well-exposedness weighting; ACEScg / OpenEXR 16f working space; multi-exposure deghosting.
- Source: `app/src/main/cpp/shaders/vulkan/hdr_merge.comp:46`, `app/src/main/cpp/hdr_merge_native.cpp`, `capture/HdrMergeNative.kt`, `capture/StillCaptureImagePipeline.kt`, `ai/AIProcessor.kt`
- Related prompts: s4-f03 (tone-map hand-off), s4-f05 (AI HDR tier policy), s4-f06 (dormant `photon_hdr_fusion`/`dual_gain_fusion` — the *video* dynamic-range path)
