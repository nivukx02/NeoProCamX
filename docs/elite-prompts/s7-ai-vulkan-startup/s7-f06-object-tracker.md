# ObjectTracker (EfficientDet-Lite0 via AINeuralEngine) — Elite Upgrade Prompt

> Read [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) first. Global contract applies; not restated.

| Field | Value |
|---|---|
| **Subsystem** | 7 — AI/ML + Vulkan rendering + Startup |
| **Prompt ID** | s7-f06 |
| **Maps to checkpoint(s)** | new — propose CP96 (object-tracker hardening) |
| **Current working level** | 🔵 live-optional |
| **Feature rating (potential)** | 6/10 |
| **Code-quality rating (now)** | 7/10 |
| **Risk · Size** | M · S |
| **Depends on** | s7-f01 (orchestrator), s7-f08 (AINeuralEngine — its actual loader), s7-f10 (predictor) |

## 1. What this feature is
`ObjectTracker` runs EfficientDet-Lite0 detection (loaded via `AINeuralEngine` — this is one of
the few real registry consumers) to produce generic object boxes/labels feeding subject scoring
(OBJECT weight 5). It has a classical fallback (chroma-saliency / temporal-diff) when the model is
unavailable, and runs when face/pose tracking is enabled.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
Pro cameras add object/animal/vehicle AF as a *priority tier below* human subjects. The elite bar:
- **Detection→track continuity:** detections become tracks with stable IDs (IoU/centroid +
  temporal-diff bridging between strided detections) so a tracked object survives the frames
  where detection didn't run.
- **Priority subordination:** object targets never outrank a confident face/eye; they fill in
  when no human subject exists.
- **Honest fallback:** the classical chroma-saliency fallback is flagged distinctly from real
  detection so consumers can down-weight it.
- **Bounded latency:** mid-cost; dropped early under load.

## 3. Current state in NeoProCamX (verified)
- `app/src/main/java/com/neoproc/neoproccamx/ai/ObjectTracker.kt` — real EfficientDet-Lite0
  inference via `AINeuralEngine`; classical chroma-saliency / temporal-diff fallback; runs when
  face/pose enabled; OBJECT weight 5 in subject scoring (FEATURE_CATALOG §7, §5).
- It is one of the genuine `AINeuralEngine` registry consumers (contrast s7-f08, where most
  registry entries are dead-coded because subsystems self-load).
- Hardening gaps (verify in source): detection→track ID continuity across strides, priority
  subordination vs face, fallback honesty flag.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Track continuity:** detections bridge to tracks via IoU/centroid + temporal-diff between
   strided runs; test asserts a moving object keeps its ID across detection-skip frames.
2. **Priority subordination:** with a confident face present, object never becomes the AF target;
   test asserts ranking (face > pose-body > object).
3. **Fallback honesty:** chroma-saliency fallback flagged distinctly; consumers down-weight; no
   "detection" claim when the model didn't run.
4. **Budget & governor:** dropped early under load (drop-order test, after depth/segmentation).
5. **No-downgrade default:** subject scoring unchanged unless the continuity/subordination flag is
   enabled; default reproduces current OBJECT contribution.

## 5. Implementation prompt (hand this to the agent)
**Scope:** add detection→track continuity, lock priority subordination, flag the fallback.
**Non-goals:** no new model (no-vendor — keep EfficientDet-Lite0); do not raise OBJECT above
human subjects; this is the one place that DOES use the registry — keep it using `AINeuralEngine`.

**Plan:**
1. **Golden-lock:** pin current object output + OBJECT subject contribution.
2. **Continuity policy:** pure `ObjectTrackContinuityPolicy` (IoU/centroid + temporal-diff bridge)
   behind a flag defaulting to current per-detection behavior.
3. **Subordination assertion:** lock the ranking face > body > object in subject scoring.
4. **Fallback flag:** mark classical-fallback boxes; route the centroid through the predictor.

**Files:** `ai/ObjectTracker.kt`, new `ai/ObjectTrackContinuityPolicy.kt`, FocusEngine subject
ranking.

**ABI:** none.

**Gating:** continuity behind a flag defaulting to current behavior.

## 6. Acceptance & verification
- **Unit (JVM):** track-continuity, subordination ranking, fallback-flag, drop-order, golden-lock.
- **Device (vivo / Adreno):** a moving object keeps its track between strided detections; a face
  always wins the AF target; object drops early under thermal load; classical fallback engages
  when the model fails to load.

## 7. Risks & no-downgrade guardrails
- **Object hijacking AF from a face:** subordination ranking locked by test.
- **Track loss between strides:** temporal-diff bridge.
- **Fake detection from fallback:** fallback honesty flag.
- **Regression:** flag defaults to current contribution; golden-lock.

## 8. References
- Roadmap: propose CP96 in [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Source: `app/src/main/java/com/neoproc/neoproccamx/ai/ObjectTracker.kt`, `ai/AINeuralEngine.kt`
- Related prompts: s7-f01, s7-f02, s7-f05, s7-f08, s7-f10
