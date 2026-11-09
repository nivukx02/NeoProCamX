# SegmentationEngine (MediaPipe selfie multiclass) — Elite Upgrade Prompt

> Read [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) first. Global contract applies; not restated.

| Field | Value |
|---|---|
| **Subsystem** | 7 — AI/ML + Vulkan rendering + Startup |
| **Prompt ID** | s7-f04 |
| **Maps to checkpoint(s)** | CP4A (semantic-region color constancy from masks) |
| **Current working level** | 🔵 live-optional |
| **Feature rating (potential)** | 7/10 |
| **Code-quality rating (now)** | 8/10 |
| **Risk · Size** | M · M |
| **Depends on** | s7-f01 (orchestrator), s7-f03 (depth mask fusion), CP42 |

## 1. What this feature is
`SegmentationEngine` runs the shipped MediaPipe `ImageSegmenter` (selfie multiclass) on strided
preview frames to produce per-pixel semantic masks (person/hair/skin/sky/etc.), with GPU→CPU
fallback, a radial-mask fallback, and luma-guided refinement. Today it drives the focus-peaking
mask; the elite target wires it into **semantic-region color constancy** (CP4A) so white-balance
and grade can be region-aware (skin vs sky vs foliage).

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
Pro color workflows do region-aware grading — secondary keys, skin-tone protection, sky
qualifiers — done by hand in a DI suite. The computational elite bar:
- **Region-aware white-point / grade:** AWB and the creative grade consult a semantic mask so
  skin stays skin under mixed lighting, sky stays neutral-blue, foliage stays green — instead of
  one global gain (Google's "semantic AWB" lineage).
- **Temporally stable masks:** mask edges must not crawl frame-to-frame (boundary flicker σ below
  threshold) or the region color will pump.
- **Edge-accurate masks:** luma-guided refine aligns mask boundaries to real edges so color
  correction doesn't bleed across a face/background boundary.
- **Bounded latency:** GPU-intensive — must defer / drop under load (governor), never block the
  imaging path.

## 3. Current state in NeoProCamX (verified)
- `app/src/main/java/com/neoproc/neoproccamx/ai/SegmentationEngine.kt` — real `ImageSegmenter`,
  GPU→CPU fallback, radial mask fallback, luma-guided refinement; drives the peaking mask
  (FEATURE_CATALOG §7). Self-loads its model (not via AINeuralEngine registry — s7-f08).
- **CP4A is open:** the mask reaches focus-peaking but NOT the color path; semantic-region color
  constancy (the high-value use) is not yet wired (roadmap CP4A, dep CP42). The SNCE engine has a
  segmentation hook that defaults null and ships no model (FEATURE_CATALOG §2, ⚪ registry).

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Semantic color constancy (CP4A):** mask drives a per-region WB/grade nudge (skin/sky/foliage)
   bounded to the existing SNCE clamp budget; test asserts a region-tagged synthetic frame gets
   the per-region nudge and a no-mask frame is bit-for-bit unchanged.
2. **Mask temporal stability:** on a static-scene sequence, mask boundary-flicker σ below
   threshold; test with fixed fixtures.
3. **Edge accuracy:** luma-guided refine aligns the mask edge to the luma edge within a pixel
   tolerance (synthetic edge fixture).
4. **Bounded budget:** segmentation dropped before face/subject under load (governor drop-order,
   shared s7-f01); GPU preferred, CPU fallback meets stride.
5. **No-downgrade default:** CP4A color-constancy path behind a flag defaulting OFF (zero color
   change); peaking-mask behavior unchanged.

## 5. Implementation prompt (hand this to the agent)
**Scope:** stabilize the mask and wire CP4A semantic-region color constancy behind a no-downgrade
gate. **Non-goals:** no new segmentation model (no-vendor); do not exceed the SNCE clamp budget;
do not change the peaking-mask contract.

**Plan:**
1. **Golden-lock:** pin current mask output + the live color path (SNCE clamp) so CP4A defaults to
   no color change.
2. **Mask stabilization:** temporal smoothing + luma-guided refine bounded so it doesn't lag
   motion; behind a flag.
3. **CP4A wiring:** feed the mask to a per-region WB/grade nudge inside the existing SNCE clamp
   (±0.05 WB / ±0.06 sat budget); region nudges sum within the global clamp.
4. **Tests:** region-nudge, no-mask-passthrough, boundary-flicker, edge-alignment.

**Files:** `ai/SegmentationEngine.kt`, the SNCE color controller, new
`color/SemanticRegionConstancyPolicy.kt`.

**ABI:** if region params reach a GPU uniform/push-constant, extend `GpuPushConstantLayout` +
layout test in the same commit and re-pin the shader-manifest hash. Prefer CPU-side LUT bake to
avoid ABI churn where possible.

**Gating:** CP4A color path behind a flag defaulting OFF (exact passthrough).

## 6. Acceptance & verification
- **Unit (JVM):** region-nudge, no-mask-passthrough (golden-lock), boundary-flicker, edge-align,
  governor drop-order.
- **Device (vivo / Adreno):** skin/sky/foliage stay correct under mixed lighting with CP4A on;
  mask edges don't crawl; segmentation drops before face under thermal load; peaking unchanged
  with CP4A off.

## 7. Risks & no-downgrade guardrails
- **Color pumping:** temporal mask stability + nudge inside the global SNCE clamp.
- **Boundary bleed:** edge-aware refine locked by test.
- **Over-correction:** region nudges sum within the existing ±clamp; ABI/std140 care if GPU-side.
- **Thermal:** dropped before face by governor.
- **Regression:** CP4A flag defaults OFF; golden-lock on color + mask.

## 8. References
- Roadmap: CP4A (dep CP42) in [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Source: `app/src/main/java/com/neoproc/neoproccamx/ai/SegmentationEngine.kt`
- Related prompts: s7-f01, s7-f03, s7-f08, s2 (SNCE color)
