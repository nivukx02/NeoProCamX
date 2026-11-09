# AI Subject / Face / Eye Tracking AF (FocusEngine) — Elite Upgrade Prompt

> Read [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) first. Its global contract applies and
> is not restated here.

| Field | Value |
|---|---|
| **Subsystem** | 5 — Focus / Autofocus |
| **Prompt ID** | s5-f03 |
| **Maps to checkpoint(s)** | new — propose CP-F4 (subject/face/eye-AF hardening) |
| **Current working level** | 🟢 live-default-on |
| **Feature rating (potential)** | 8/10 |
| **Code-quality rating (now)** | 8/10 |
| **Risk · Size** | M · M |
| **Depends on** | s5-f04 (coordinator follow), s5-f06 (depth), s7 FaceTracker/PoseTracker |

## 1. What this feature is
`FocusEngine` is the subject-awareness brain of autofocus. It ingests faces (MediaPipe
FaceLandmarker), bodies (PoseLandmarker), and objects (EfficientDet) plus depth, scores each
candidate (EYE > FACE > BODY > OBJECT), picks the best anchor — eye-vs-face decided by openness /
head pose / occlusion — predicts the subject's next position, and applies continuity logic so the
focus target doesn't flicker between subjects (cinematic smoothing on small moves, hard-cut on a
genuine subject switch). Its chosen target feeds the AI AF coordinator (s5-f04) which drives the
hardware metering region.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
The reference is Sony Real-time Eye AF / Real-time Tracking and Canon EOS iTR AF X / subject-
detection AF: (1) **eye-level precision** — the focus plane lands on the near eye, not the face
centroid, and tracks it through head turns, switching to face/body when the eye occludes; (2)
**identity continuity** — once a subject is chosen it is *held* through brief occlusion / other
faces entering frame (no jumping to the nearest/biggest face); (3) **predictive tracking** — the
AF leads a moving subject so focus is correct at the moment of exposure, not lagging; (4)
**deterministic switch** — switching subjects is a deliberate, smooth event, not jitter.
Translate to targets: **near-eye anchor when the eye is visible+open, subject-identity held
through N-frame occlusion, motion prediction reduces lag-at-exposure, and subject switches are
debounced (no per-frame flip-flop).**

## 3. Current state in NeoProCamX (verified)
- `FocusEngine.kt` scores subjects with explicit priorities: `EYE_PRIORITY=25`, `FACE_PRIORITY=15`,
  `BODY_PRIORITY=8`, `OBJECT_PRIORITY=5` (`FocusEngine.kt:29-32`).
- Eye-vs-face anchor selection via `FaceEyeFocusTargetPolicy.kt`; `subjectType` resolves to
  EYE/FACE/BODY/OBJECT with per-type confidence weighting (`FocusEngine.kt:133, 280-296`).
- Motion prediction + continuity/smoothing live in `FocusEngine` and feed `AIAutoFocusCoordinator`.
- Status: **live, default-on** (FaceTracker is default-on per catalog §7). Hardening prompt.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Eye anchor correctness:** when an open, unoccluded eye is detected, the chosen focus point is
   the near-eye landmark, not the face centroid; degrades to face on eye-closed/occluded/extreme
   pose. Unit test over a landmark fixture matrix.
2. **Subject-identity continuity:** once locked, the same subject is held through ≤ N frames of
   occlusion and through a competing face entering frame; switch only when the original is gone or
   a deliberate-switch threshold is crossed. Test asserts no switch under transient occlusion.
3. **Switch debounce / no flicker:** consecutive frames with near-tied scores do not flip the
   target every frame; hysteresis margin asserted by test.
4. **Predictive lead:** motion prediction outputs a lead position that reduces predicted lag-at-
   exposure for a constant-velocity track; test asserts predicted point leads the last observation.
5. **Score determinism:** identical detection inputs → identical chosen target + score (pure
   function); regression-locked.
6. **No-downgrade default:** current priorities, anchor rules, and smoothing outputs unchanged;
   any new hysteresis/lead tuning is additive behind the existing scoring contract and defaults to
   today's constants.
7. **Budget:** scoring stays within the existing AI pipeline stride/governor budget; no new model.

## 5. Implementation prompt (hand this to the agent)
**Scope:** golden-lock and harden subject scoring, eye/face anchoring, identity continuity,
switch debounce, and predictive lead. **Non-goals:** no new detector/model (no-vendor); do not
change the hardware-drive path (s5-f04); do not alter MediaPipe wiring.

**Plan:**
1. **Golden-lock** current target selection + score for a fixture set of detection frames.
2. Add eye-anchor matrix test (open/closed/occluded/pose) against `FaceEyeFocusTargetPolicy`.
3. Add identity-continuity test (lock → transient occlusion → same subject) and switch-debounce
   hysteresis test.
4. Add predictive-lead test for a constant-velocity subject.
5. If any new hysteresis/lead parameter is introduced, default it to reproduce today's output.

**Files:** `app/src/main/java/com/neoproc/neoproccamx/ai/FocusEngine.kt`,
`FaceEyeFocusTargetPolicy.kt`, new `FocusEngine*` test cases.

**ABI:** none (CPU/Kotlin only).

**Gating:** new tuning behind existing constants defaulting to today's values; golden lock proves
default selection unchanged.

## 6. Acceptance & verification
- **Unit/golden (JVM):** target-selection golden; eye-anchor matrix; identity continuity;
  switch debounce; predictive lead; score determinism.
- **Device (vivo I2405 / Adreno):** subject walks across frame + turns head — confirm focus holds
  near-eye, holds identity through a passer-by, no per-frame flicker, and leads the moving subject.

## 7. Risks & no-downgrade guardrails
- **Subject flicker / wrong subject:** hysteresis + identity-continuity tests.
- **Focus on face not eye:** eye-anchor matrix test.
- **Lag on moving subject:** predictive-lead test.
- **Regression in default selection:** golden lock; new params default to current constants.
- **Compute / thermal:** runs inside existing AI governor budget; no new model (no-vendor).

## 8. References
- Roadmap: propose CP-F4.
- Reference: Sony Real-time Eye AF / Real-time Tracking, Canon EOS iTR AF X subject detection.
- Source: `FocusEngine.kt:29-32, 133, 280-296`, `FaceEyeFocusTargetPolicy.kt`
- Related prompts: s5-f04, s5-f06
