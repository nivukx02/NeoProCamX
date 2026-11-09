# FaceTracker (MediaPipe FaceLandmarker) — Elite Upgrade Prompt

> Read [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) first. Global contract applies; not restated.

| Field | Value |
|---|---|
| **Subsystem** | 7 — AI/ML + Vulkan rendering + Startup |
| **Prompt ID** | s7-f02 |
| **Maps to checkpoint(s)** | new — propose CP93 (face-tracker hardening) |
| **Current working level** | 🟢 live-default-on |
| **Feature rating (potential)** | 8/10 |
| **Code-quality rating (now)** | 8/10 |
| **Risk · Size** | M · M |
| **Depends on** | s7-f01 (orchestrator cadence), s7-f10 (Kalman predictor) |

## 1. What this feature is
`FaceTracker` runs the shipped MediaPipe `FaceLandmarker` `.task` model on strided preview frames
to produce face bounding boxes + landmarks, with eye-landmark extraction that feeds eye-AF in the
focus engine. It is default-on, with a GPU→CPU delegate fallback, and is one of the cheapest /
most-critical AI stages (it is the last stage the governor should drop).

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
Pro AF systems (Sony Real-time Eye AF, ARRI/Canon face-priority) treat the face/eye target as a
**continuous, identity-stable** track, not a per-frame redetect. The elite bar:
- **Temporal identity locking:** the same physical face keeps the same track ID across frames;
  brief occlusion / head turn does not spawn a new track or hand off to a different face.
- **Eye-landmark precision for eye-AF:** left/right eye anchors stable enough to drive a focus
  diopter without jitter; openness/pose/occlusion gating so a blink or profile turn falls back to
  the face anchor instead of stabbing focus.
- **Bounded latency:** inference + landmark extract within the per-stage AI budget (target
  ≤ 6 ms/frame on GPU delegate), graceful CPU fallback that still meets the orchestrator stride.
- **No false-lock under motion blur / low light:** confidence-gated; below threshold the tracker
  reports no-face rather than a hallucinated box.

## 3. Current state in NeoProCamX (verified)
- `app/src/main/java/com/neoproc/neoproccamx/ai/FaceTracker.kt` — real MediaPipe `.task` model,
  GPU→CPU fallback, eye-landmark extraction for eye-AF, default-on (FEATURE_CATALOG §7).
- Self-loads its own model (does NOT go through `AINeuralEngine` registry — see s7-f08 honesty
  note). Eye anchors feed `FocusEngine` / `FaceEyeFocusTargetPolicy.kt`.
- Hardening gaps (verify in source): track-ID stability across frames, openness/occlusion gating
  thresholds, and predictor handoff (s7-f10) are the elite deltas, not new capability.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Track-ID stability:** a synthetic sequence of moving/occluded faces keeps a stable ID under
   IoU/centroid continuity; test asserts no spurious ID churn across N frames with ≤ K-frame gap.
2. **Eye-anchor jitter bound:** on a static face fixture, the eye anchor's frame-to-frame
   positional σ stays below a pixel threshold; blink frames fall back to face anchor (asserted).
3. **Confidence gating:** below-threshold detections report no-face (no hallucinated box) — test
   with a low-confidence/blur fixture.
4. **Budget:** inference + extract ≤ 6 ms/frame target on GPU delegate; CPU fallback still meets
   the FaceTracker stride; governor drops FaceTracker last.
5. **No-downgrade default:** today's detections/anchors unchanged unless the new
   identity-locking/gating flag is enabled; default reproduces current output.

## 5. Implementation prompt (hand this to the agent)
**Scope:** harden FaceTracker into an identity-stable, jitter-bounded, confidence-gated eye-AF
source. **Non-goals:** no new model / no model swap (no-vendor); do not change the eye-AF
diopter math (that's s5/focus); do not move it into the AINeuralEngine registry.

**Plan:**
1. **Golden-lock:** characterization test pinning current face/eye outputs on fixed fixtures.
2. **Identity locking:** add a pure track-association policy (IoU + centroid + age) producing
   stable track IDs; behind a flag defaulting to today's per-frame behavior.
3. **Anchor stabilization:** clamp eye-anchor jitter via the existing predictor handoff (s7-f10);
   add openness/occlusion fallback to the face anchor.
4. **Confidence gate:** assert below-threshold ⇒ no-face.

**Files:** `ai/FaceTracker.kt`, new `ai/FaceTrackAssociationPolicy.kt`,
`ai/FaceEyeFocusTargetPolicy.kt` (gating hooks).

**ABI:** none.

**Gating:** identity-locking + stabilization behind a flag defaulting to current behavior.

## 6. Acceptance & verification
- **Unit (JVM):** ID-stability, anchor-jitter, blink-fallback, confidence-gate, golden-lock.
- **Device (vivo / Adreno):** eye-AF stays locked on a slowly turning head; blink does not stab
  focus; profile turn falls back to face; GPU-delegate latency within budget; CPU fallback works
  when GPU delegate unavailable.

## 7. Risks & no-downgrade guardrails
- **Focus stabbing on blink:** openness gate + face-anchor fallback.
- **Track churn:** association policy with continuity → impossible-by-construction ID swap.
- **Hallucinated faces:** confidence gate reports no-face.
- **Regression:** flag defaults to current outputs; golden-lock.

## 8. References
- Roadmap: propose CP93 in [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Source: `app/src/main/java/com/neoproc/neoproccamx/ai/FaceTracker.kt`, `ai/FaceEyeFocusTargetPolicy.kt`
- Related prompts: s7-f01, s7-f10, s7-f08
