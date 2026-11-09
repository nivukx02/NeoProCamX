# PoseTracker (MediaPipe PoseLandmarker) — Elite Upgrade Prompt

> Read [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) first. Global contract applies; not restated.

| Field | Value |
|---|---|
| **Subsystem** | 7 — AI/ML + Vulkan rendering + Startup |
| **Prompt ID** | s7-f05 |
| **Maps to checkpoint(s)** | new — propose CP95 (pose hardening) |
| **Current working level** | 🔵 live-optional |
| **Feature rating (potential)** | 6/10 |
| **Code-quality rating (now)** | 8/10 |
| **Risk · Size** | M · S |
| **Depends on** | s7-f01 (orchestrator), s7-f10 (predictor) |

## 1. What this feature is
`PoseTracker` runs the shipped MediaPipe `PoseLandmarker` (9.4 MB model) on strided preview frames
to produce body-keypoint landmarks that feed subject-awareness scoring (BODY weight 8 in the
focus engine). GPU→CPU fallback. It is a mid-cost AI stage that improves subject tracking when a
face is small/absent (full-body or back-turned subjects).

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
Pro subject AF (Sony, Canon) falls back from eye→face→head→body so a turned-away or distant
subject stays tracked. The elite bar for pose here:
- **Graceful subject-priority cascade:** body keypoints extend the track when face confidence
  drops, with a stable handoff (no jump) between the face anchor and a body-derived anchor.
- **Temporal smoothness:** keypoints fed to the focus engine are jitter-bounded (predictor
  handoff) so the AF target doesn't oscillate on noisy limbs.
- **Confidence gating:** low-confidence keypoints are dropped rather than dragging the subject
  centroid; below threshold reports no-pose.
- **Bounded latency:** mid-cost stage; dropped after depth/segmentation but before face under
  load.

## 3. Current state in NeoProCamX (verified)
- `app/src/main/java/com/neoproc/neoproccamx/ai/PoseTracker.kt` — real 9.4 MB `PoseLandmarker`
  model; GPU→CPU fallback; feeds subject-awareness (FEATURE_CATALOG §7). Self-loads its model
  (not via AINeuralEngine registry — s7-f08).
- Subject scoring consumes BODY at weight 8 (FocusEngine subject-awareness, FEATURE_CATALOG §5).
- Hardening gaps (verify in source): face↔body anchor handoff stability, keypoint confidence
  gating, predictor smoothing on the body centroid.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Stable face↔body handoff:** when face confidence drops below threshold, the subject anchor
   transitions to a body-derived centroid without a positional jump beyond a tolerance; test with
   a synthetic face-fade sequence.
2. **Keypoint confidence gating:** low-confidence keypoints excluded from the centroid; below
   threshold ⇒ no-pose (asserted).
3. **Jitter bound:** body-centroid fed to focus is smoothed via the predictor (s7-f10); σ below
   threshold on a static-pose fixture.
4. **Budget & governor:** dropped after depth/segmentation, before face (drop-order test).
5. **No-downgrade default:** subject scoring unchanged unless the handoff/gating flag is enabled;
   default reproduces current BODY contribution.

## 5. Implementation prompt (hand this to the agent)
**Scope:** harden the face↔body handoff, keypoint gating, and centroid smoothing.
**Non-goals:** no new model (no-vendor); do not change subject-weight constants; do not move into
the AINeuralEngine registry.

**Plan:**
1. **Golden-lock:** pin current pose output + BODY subject contribution on fixed fixtures.
2. **Handoff policy:** pure `PoseFaceHandoffPolicy` producing a continuous anchor; behind a flag
   defaulting to current behavior.
3. **Confidence gate + centroid smoothing:** exclude low-confidence keypoints; route the centroid
   through the predictor (s7-f10).
4. **Tests:** handoff-no-jump, confidence-gate, jitter-σ, governor drop-order, golden-lock.

**Files:** `ai/PoseTracker.kt`, new `ai/PoseFaceHandoffPolicy.kt`, FocusEngine subject hook.

**ABI:** none.

**Gating:** handoff/gating behind a flag defaulting to current behavior.

## 6. Acceptance & verification
- **Unit (JVM):** handoff, confidence-gate, jitter, drop-order, golden-lock.
- **Device (vivo / Adreno):** subject stays tracked as a person turns away (face→body); no AF
  jump on handoff; pose drops before face under thermal load; GPU/CPU fallback works.

## 7. Risks & no-downgrade guardrails
- **AF jump on handoff:** continuity-bounded anchor transition.
- **Centroid drag from noisy limbs:** confidence gate + predictor smoothing.
- **Thermal:** dropped before face by governor.
- **Regression:** flag defaults to current contribution; golden-lock.

## 8. References
- Roadmap: propose CP95 in [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Source: `app/src/main/java/com/neoproc/neoproccamx/ai/PoseTracker.kt`
- Related prompts: s7-f01, s7-f02, s7-f10, s7-f08, s5 focus
