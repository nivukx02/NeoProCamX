# SNCE Learned-Model Registry (illuminant/seg/LUT/skin hooks) — Elite Upgrade Prompt

> Global contract in [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) applies and is not restated.
> ⚠️ **This prompt is NO-VENDOR by design.** It hardens the *hook architecture + classical
> fallbacks*. It does **NOT** ship any new `.tflite`. Every hook stays **null**; `isFullyClassical`
> stays **true**. Shipping a learned model is a deferred, out-of-contract action, not a goal here.

| Field | Value |
|---|---|
| **Subsystem** | 2 — Color science + SNCE engine |
| **Prompt ID** | s2-f09 |
| **Maps to checkpoint(s)** | CP4A (semantic-region color constancy — the *classical/MediaPipe* path; learned hook stays null) |
| **Current working level** | ⚪ test-only-scaffold (4 hooks default null; `isFullyClassical` true; expected model specs listed but no .tflite shipped) |
| **Feature rating (potential)** | 3/10 now → 7/10 target (architecture + classical fallback quality) |
| **Code-quality rating (now)** | 7/10 |
| **Risk · Size** | L · M |
| **Depends on** | s2-f10 (illuminant classical methods are the fallback), s2-f07/s2-f08 (seg/LUT consumers), s7-f0x (MediaPipe ImageSegmenter — an already-shipped model) |

## 1. What this feature is
`SnceModelRegistry.kt` + `SnceModelHooks.kt` hold **four optional learned-model hooks** — illuminant,
segmentation, adaptive-LUT, face-skin — each defaulting to `null` (`SnceModelHooks.kt:16-19`). When a
hook is null the **classical pipeline runs unchanged** (`SnceModelRegistry.kt:6-7`), and
`isFullyClassical` reports true (`SnceModelRegistry.kt:26-27`). `SnceModelAssets` documents the
*expected* asset filenames (C5 / FFCC illuminant, DeepLabV3+ seg, Image-Adaptive-3DLUT, Zero-DCE++ —
`SnceModelRegistry.kt:60-76`) but **no binaries are shipped**. This is a hook/strategy architecture: a
clean seam where a learned model *could* plug in, with a guaranteed classical fallback. Under the
NO-VENDOR rule, the value here is **the architecture and the strength of the classical fallbacks**, not
any model.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
The grounding is **robust, model-optional color constancy with a deterministic classical floor**:
- Modern phone ISPs run learned AWB (e.g. FFCC / CNN illuminant estimators) but **always** keep a
  classical gray-world / shades-of-gray / white-patch fallback so the pipeline degrades gracefully when
  the model is unavailable or low-confidence. The architecture is a **strategy pattern with a
  confidence-weighted fusion** (exactly s2-f10's 9-method fusion).
- **Semantic color constancy (CP4A):** high-end pipelines key corrections to scene regions (skin, sky,
  foliage) — but the *segmentation* can come from a classical/already-shipped segmenter (here:
  MediaPipe `ImageSegmenter`, an allowed shipped model), not a new color model. The learned-color hook
  remains optional.
- The discipline: a model is **never required**; a missing hook is a `null`, not a crash, and the
  fallback path is fully tested.
Elite bar, measurable (no new model): (a) every hook null → `isFullyClassical == true` and output is
bit-for-bit the classical path; (b) the hook contract is total (null-safe, no NPE) for all four hooks
under fuzzing; (c) the **classical fallbacks** meet their own quality bars (illuminant: s2-f10 fusion;
segmentation: MediaPipe/classical masks); (d) a hypothetical hook returning a degenerate result
(NaN/empty) is rejected and the classical path is used (honest-fallback, never a bad correction).

## 3. Current state in NeoProCamX (verified)
- Four hooks, all default null: `illuminantModel/segmentationModel/adaptiveLutModel/faceSkinModel`
  (`SnceModelHooks.kt:16-19`); `has*Model` booleans (`SnceModelRegistry.kt:21-24`).
- `isFullyClassical` (`SnceModelRegistry.kt:26-27`) and a debug summary that prints
  `illuminant=classical`, `seg=mediapipe/classical`, etc. (`SnceModelRegistry.kt:32-33`).
- `SnceModelAssets` documents expected `.tflite` filenames (C5, FFCC, DeepLabV3+, Image-Adaptive-3DLUT,
  Zero-DCE++ — `SnceModelRegistry.kt:60-76`) — **specs only, no binaries** (catalog §2 row 9).
- Segmentation hook doc explicitly says "Return null to fall back to MediaPipe / classical masks"
  (`SnceModelHooks.kt:53`) — i.e. the allowed shipped segmenter is the fallback, consistent with
  NO-VENDOR.
- So the architecture exists; what's missing is (a) hardened null-safety/fuzz coverage, (b)
  degenerate-output rejection, (c) CP4A wiring of the **classical/MediaPipe** semantic-region path
  (learned hook still null).

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Fully-classical invariant:** with all hooks null, `isFullyClassical == true` and the SNCE output is
   bit-for-bit the classical path — proven by a test that asserts both.
2. **Total, null-safe contract:** fuzzing each hook (null, present-but-throwing, present-returning
   garbage) never NPEs/crashes; a throwing/garbage hook is caught and the classical fallback used
   (honest-fallback test).
3. **Degenerate-output rejection:** a hook returning NaN/Inf/empty/out-of-range is rejected; the
   classical path runs; a test asserts the bad result never reaches pixels.
4. **CP4A classical semantic constancy:** region-keyed color correction driven by **MediaPipe
   ImageSegmenter** (shipped) / classical masks — gated, bounded by the ±0.05 WB / ±0.06 sat ceiling,
   and the learned segmentation hook stays null. Test: with the hook null, the MediaPipe/classical mask
   drives the bounded correction; with no mask, no-op.
5. **No-downgrade default + NO-VENDOR:** no `.tflite` added; default is the classical path bit-for-bit;
   CP4A semantic path is gated off until device-validated.
6. **Budget (ms):** classical fallbacks + mask consumption fit the existing SNCE budget; off cold-start.

## 5. Implementation prompt (hand this to the agent)
**Scope:** harden the hook architecture (null-safety, degenerate rejection, honest fallback) and wire
the **classical/MediaPipe** semantic-region color constancy (CP4A) behind a gate. **Non-goals:** do NOT
ship any new model; do NOT make a hook non-null; do not widen the ±0.05/±0.06 ceiling.

**Plan:**
1. **GOLDEN-LOCK FIRST.** Test asserting all-null → `isFullyClassical` true → classical output unchanged.
2. **Null-safety + honest-fallback + degenerate-rejection tests** per §4.2-4.3 (fuzz each hook).
3. **CP4A (classical):** consume the already-shipped MediaPipe `ImageSegmenter` mask (or classical mask)
   to apply a region-keyed, bounded color correction (skin/sky/foliage), gated off by default; learned
   segmentation hook stays null. Coordinate with s2-f10 (per-region illuminant) and the ±0.05/±0.06
   clamp (s2-f05).
4. **Document** in `SnceModelAssets` that binaries are intentionally absent (NO-VENDOR) and the hooks
   are deferred/device-gated insertion points only.

**Files:** `app/src/main/java/com/neoproc/neoproccamx/snce/SnceModelRegistry.kt`, `SnceModelHooks.kt`,
`SnceV2Engine.kt` (CP4A mask consumption); tests under `app/src/test/.../snce/`. No GPU ABI change.

**Gating:** all hooks null (NO-VENDOR); CP4A semantic path behind a flag defaulting off; corrections
bounded by the existing clamp.

## 6. Acceptance & verification
- **JVM:** fully-classical invariant, null-safety/fuzz, degenerate-rejection, CP4A-with-null-hook tests
  green; `isFullyClassical` stays true in the default suite.
- **No new asset:** a meta-test/assert that no `models/snce/*.tflite` is bundled (NO-VENDOR guard).
- **Device (vivo):** CP4A off → bit-match today; on → MediaPipe mask drives a bounded skin/sky/foliage
  correction with no look swing. Owner confirms.

## 7. Risks & no-downgrade guardrails
- **Accidental vendor model:** explicit no-asset guard; hooks stay null; CI meta-test.
- **NPE / crash from a hook:** total null-safe contract + fuzz tests.
- **Bad correction from a garbage model:** degenerate-output rejection → classical fallback.
- **Look swing from semantic correction:** bounded by the ±0.05/±0.06 ceiling; gated off by default.

## 8. References
- Roadmap CP4A (semantic-region color constancy, dep CP42): [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Memory: OmniClarity engine plan (no-vendor decision: keep MediaPipe/MiDaS, no new OSS models)
- Standard canon (learned AWB with classical floor, FFCC, semantic constancy): [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) §2
- Source: `app/src/main/java/com/neoproc/neoproccamx/snce/SnceModelHooks.kt:16-19, 53`; `SnceModelRegistry.kt:6-7, 21-27, 32-33, 60-76`
- Related prompts: s2-f05, s2-f07, s2-f08, s2-f10
