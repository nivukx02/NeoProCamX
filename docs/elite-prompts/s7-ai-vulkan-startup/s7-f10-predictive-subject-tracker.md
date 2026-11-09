# PredictiveSubjectTracker (Kalman) — Elite Upgrade Prompt

> Read [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) first. Global contract applies; not restated.

| Field | Value |
|---|---|
| **Subsystem** | 7 — AI/ML + Vulkan rendering + Startup |
| **Prompt ID** | s7-f10 |
| **Maps to checkpoint(s)** | new — propose CP9A (predictor hardening) |
| **Current working level** | 🔵 live-optional |
| **Feature rating (potential)** | 6/10 |
| **Code-quality rating (now)** | 7/10 |
| **Risk · Size** | M · S |
| **Depends on** | s7-f02 (face), s7-f05 (pose), s7-f06 (object) — its measurement sources |

## 1. What this feature is
`PredictiveSubjectTracker` is the Kalman filter that smooths and forward-predicts the subject
position. It holds a position/velocity/acceleration state, exposes a prediction lead-time, and
feeds the focus engine so AF can lead a moving subject and ride through strided-detection gaps and
brief occlusion. It is the shared smoother that f02/f05/f06 anchors should route through.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
Pro subject AF (Sony Real-time Tracking) predicts subject trajectory so focus *leads* motion and
survives detection dropouts — the AF box does not jitter on noisy per-frame detections, and it
coasts smoothly through a brief occlusion instead of snapping to background. The elite bar:
- **Lead-time prediction:** the filter predicts the subject position at the next focus actuation
  given measured velocity/accel, compensating detection→actuation latency.
- **Jitter suppression with low lag:** process/measurement noise tuned so the track is smooth
  (low σ) yet responsive (bounded lag) — the classic Kalman tradeoff, made measurable.
- **Coast-through-occlusion:** with no measurement, the filter coasts on the model for a bounded
  number of frames, then decays confidence (no permanent ghost track).
- **Innovation gating:** an implausibly large jump (outlier detection) is rejected, not followed.

## 3. Current state in NeoProCamX (verified)
- `app/src/main/java/com/neoproc/neoproccamx/ai/PredictiveSubjectTracker.kt` — position/velocity/
  accel Kalman state; prediction lead-time; feeds the focus engine (FEATURE_CATALOG §7). Related
  math in `ai/SubjectTrackingMath.kt` / `ai/TrackingMath.kt`.
- Hardening gaps (verify in source): innovation/outlier gating, coast-through-occlusion with
  confidence decay, noise-parameter tuning made measurable (smoothness vs lag).

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Prediction accuracy:** on a synthetic constant-velocity / constant-accel track, predicted
   position at lead-time matches ground truth within tolerance; test asserts EPE below threshold.
2. **Jitter vs lag:** on a noisy-measurement fixture, output σ below threshold while step-response
   lag stays within a bounded number of frames (both asserted — proves the tuning).
3. **Coast-through-occlusion:** with measurements withheld for K frames, the track coasts on the
   model and confidence decays; after the gap a measurement re-locks without overshoot.
4. **Innovation gating:** an outlier jump beyond a gate is rejected; test asserts the track is not
   dragged to the outlier.
5. **No-downgrade default:** focus-engine target unchanged unless the gating/coast flag is enabled;
   default reproduces current predictor behavior.

## 5. Implementation prompt (hand this to the agent)
**Scope:** add innovation gating + coast-through-occlusion + measurable noise tuning; make f02/f05/
f06 route their anchors through this predictor. **Non-goals:** no new model (pure math, no-vendor);
do not change the focus actuation math.

**Plan:**
1. **Golden-lock:** pin current predictor output on fixed measurement sequences.
2. **Innovation gate:** reject outlier measurements (Mahalanobis/χ² gate); behind a flag.
3. **Coast + decay:** model-only coast for K frames with confidence decay on missing measurement.
4. **Tuning tests:** lock smoothness-σ and step-lag bounds so future changes can't silently
   over-smooth (lag) or under-smooth (jitter).
5. **Route anchors:** ensure face/pose/object centroids feed through the predictor (used by s7-f02/
   f05/f06).

**Files:** `ai/PredictiveSubjectTracker.kt`, `ai/SubjectTrackingMath.kt`, FocusEngine subject hook.

**ABI:** none.

**Gating:** gating/coast behind a flag defaulting to current behavior.

## 6. Acceptance & verification
- **Unit (JVM):** prediction-EPE, jitter-vs-lag, coast-through-occlusion, innovation-gate,
  golden-lock.
- **Device (vivo / Adreno):** AF leads a subject walking across frame; rides through a brief pass-
  behind-object without snapping to background; no jitter on a static subject.

## 7. Risks & no-downgrade guardrails
- **Over-smoothing (focus lag):** locked step-lag bound test.
- **Following an outlier:** innovation gate.
- **Permanent ghost track:** confidence decay after coast.
- **Regression:** flag defaults to current behavior; golden-lock.

## 8. References
- Roadmap: propose CP9A in [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Source: `app/src/main/java/com/neoproc/neoproccamx/ai/PredictiveSubjectTracker.kt`, `ai/SubjectTrackingMath.kt`
- Related prompts: s7-f02, s7-f05, s7-f06, s5 focus
