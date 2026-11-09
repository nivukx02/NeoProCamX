# AINeuralEngine (generic TFLite model registry) — Elite Upgrade Prompt

> Read [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) first. Global contract applies; not restated.

| Field | Value |
|---|---|
| **Subsystem** | 7 — AI/ML + Vulkan rendering + Startup |
| **Prompt ID** | s7-f08 |
| **Maps to checkpoint(s)** | new — propose CP98 (registry consolidation / honest prune) |
| **Current working level** | 🟡 partial (most registry entries dead-coded) |
| **Feature rating (potential)** | 4/10 |
| **Code-quality rating (now)** | 7/10 |
| **Risk · Size** | L · M |
| **Depends on** | s7-f06 (ObjectTracker — a real consumer), s7-f09 (NeuralDenoise — optional consumer) |

## 1. What this feature is
`AINeuralEngine` is a generic TFLite model registry + resolver + interpreter wrapper: a
`MODEL_ALIAS_REGISTRY` of 10 aliases → asset paths, `loadModel()` with GPU/NNAPI/CPU delegate
selection, and lifecycle management. The *intent* was a single shared loader for all AI models.
The *reality* is that almost every subsystem self-loads its own model (MediaPipe `.task` files via
their own loaders; ML Kit via its client), so most registry entries resolve assets that nothing
actually loads through this engine.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
N/A as a pixel feature — this is infrastructure. The "elite" bar is **honest, minimal, single-source
model management**: one loader that every model actually flows through, with truthful capability
reporting (which delegate ran, model present/absent), no dead aliases implying capabilities the app
doesn't ship. The discipline is the OmegaCapabilityGate ethos applied to infra: don't list a model
path that nothing loads.

## 3. Current state in NeoProCamX (verified)
- `app/src/main/java/com/neoproc/neoproccamx/ai/AINeuralEngine.kt` (1352 lines). `MODEL_ALIAS_REGISTRY`
  has 10 aliases (`AINeuralEngine.kt:117-127`): depth_estimation, segmentation, denoising,
  object_detection, face_detection, pose_estimation, super_resolution, image_enhancement,
  scene_classification, hdr_merge. Real `Interpreter` + `GpuDelegate`/`NnApiDelegate`
  (`AINeuralEngine.kt:21-22, 187-189, 643`), `loadModel()` at `583`.
- **The honesty problem — verified:** the ONLY non-test consumers of `AINeuralEngine` in
  `app/src/main` are `ObjectTracker.kt`, `NeuralDenoiseEngine.kt` (optional hook), and
  `AIPerformanceOptimizer.kt`/`AIConstants.kt` (config). Face/pose/depth/segmentation/scene each
  **self-load** (MediaPipe / ML Kit) despite having a registry alias — so `face_detection`,
  `pose_estimation`, `depth_estimation`, `segmentation`, `scene_classification` aliases are
  effectively dead. `super_resolution`, `image_enhancement`, `hdr_merge` aliases ship no consumer
  and (mostly) no binary. (`loadModel` has only one definition; only ObjectTracker/NeuralDenoise
  call into it for a live model.)

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Truthful registry:** every alias remaining in `MODEL_ALIAS_REGISTRY` has (a) a live consumer
   that loads through `AINeuralEngine`, OR (b) an explicit `// dormant: <reason>` annotation +
   exclusion from any "available capability" reporting. A meta-test asserts no alias claims a
   capability with zero consumer and no annotation.
2. **Single decision:** EITHER consolidate the self-loaders onto this engine (only where it adds
   value and stays no-vendor), OR prune the dead aliases and document that each subsystem owns its
   loader. Pick one and make the code match the doc — no "aspirational" registry.
3. **Honest delegate reporting:** `EngineStatus` reports which delegate actually ran (GPU/NNAPI/
   CPU) and model present/absent; test asserts no synthesized "loaded" when the asset is missing.
4. **No behavior change:** ObjectTracker + NeuralDenoise load paths bit-for-bit unchanged.
5. **Budget:** loading stays off the cold-start path (deferred), same as today.

## 5. Implementation prompt (hand this to the agent)
**Scope:** make the registry HONEST — consolidate or prune, annotate dormant aliases, truthful
status. **Non-goals:** do NOT add/swap models (no-vendor); do NOT migrate MediaPipe/ML Kit
self-loaders unless it is strictly equivalent and lower-risk (it usually is not — those SDKs own
their lifecycle, so the honest move is to PRUNE/annotate, not force them through this engine); do
not change ObjectTracker/NeuralDenoise behavior.

**Plan:**
1. **Golden-lock:** pin ObjectTracker + NeuralDenoise load/inference behavior and current
   `EngineStatus` output.
2. **Audit & decide:** mark each alias as live-consumer / dormant-annotated / prune. Recommended:
   keep `object_detection` + `denoising`; annotate the MediaPipe/ML Kit-owned ones as
   `// owned-by-<subsystem>, not loaded here`; prune `super_resolution`/`image_enhancement`/
   `hdr_merge` if no binary + no consumer.
3. **Meta-test:** add `AINeuralEngineRegistryHonestyTest` asserting (1)+(3).
4. **Status truth:** ensure `EngineStatus` never reports loaded for a missing asset.

**Files:** `ai/AINeuralEngine.kt`, `ai/EngineStatus.kt`, new
`ai/AINeuralEngineRegistryHonestyTest` (test).

**ABI:** none.

**Gating:** pure infra/honesty change; no live-pixel path touched ⇒ no specialization-constant
gate needed, but the golden-lock on the two real consumers is mandatory.

## 6. Acceptance & verification
- **Unit (JVM):** registry-honesty meta-test; status-truth test; ObjectTracker/NeuralDenoise
  golden-lock unchanged. Full suite green.
- **Device (vivo / Adreno):** ObjectTracker + optional NeuralDenoise still load/run; missing
  optional model degrades to classical fallback without a false "loaded" status.

## 7. Risks & no-downgrade guardrails
- **Breaking a real consumer:** golden-lock on ObjectTracker/NeuralDenoise before any prune.
- **Dishonest capability claims:** the honesty meta-test makes a dead alias impossible to keep
  silently.
- **Regression:** no live-pixel path; the two real load paths are pinned.

## 8. References
- Roadmap: propose CP98 in [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Source: `app/src/main/java/com/neoproc/neoproccamx/ai/AINeuralEngine.kt:117, 583, 643`,
  `ai/ObjectTracker.kt`, `ai/NeuralDenoiseEngine.kt`, `ai/EngineStatus.kt`
- Related prompts: s7-f06 (real consumer), s7-f09 (optional consumer), s7-f02/f03/f04/f05/f07
  (self-loaders to annotate)
