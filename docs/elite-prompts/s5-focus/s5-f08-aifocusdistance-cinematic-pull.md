# AIFocusDistanceController Cinematic Transition / Focus-Pull API — Elite Upgrade Prompt

> Read [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) first. Its global contract applies and
> is not restated here.

| Field | Value |
|---|---|
| **Subsystem** | 5 — Focus / Autofocus |
| **Prompt ID** | s5-f08 |
| **Maps to checkpoint(s)** | new — propose CP-F9 (focus-pull engine wiring) |
| **Current working level** | ⚪ test-only-scaffold (focus-pull styles exist, **test-only**) |
| **Feature rating (potential)** | 2/10 now → 7/10 target |
| **Code-quality rating (now)** | 7/10 (curve library + pull styles are clean; never driven live) |
| **Risk · Size** | M · L |
| **Depends on** | s5-f07 (cine UI), s5-f05 (lens-distance drive), s5-f04 (coordinator) |

## 1. What this feature is
`AIFocusDistanceController` also carries a *cinematic transition* layer:
`applyCinematicTransition` (easing-curve-driven diopter move), `applyFocusPull` with four named
styles — SMOOTH / RACK / CREEP / SNAP, each mapping to a duration + easing curve —
`estimateTargetDioptersPredictive`, `calculateAdaptiveTransitionDuration`, and a full easing-curve
library. This is the engine that would execute a director's focus pull between two distances on a
chosen feel. Today it is exercised **only by tests** — no production path calls `applyFocusPull` /
`applyCinematicTransition`, so no focus pull ever reaches the lens.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
A focus pull's *feel* is everything: a SMOOTH dialogue rack vs. a fast RACK reveal vs. a slow CREEP
into a subject vs. a SNAP for a hard hit — pullers choose speed + curve deliberately (ARRI Hi-5 /
cmotion programmable pull profiles do exactly this). Elite behavior: (1) **endpoint-exact** — the
pull lands precisely on the target diopter, no overshoot/undershoot; (2) **curve-faithful** — the
motion follows the chosen easing curve sample-for-sample; (3) **adaptive duration** — longer
distance → proportionally longer pull (the `calculateAdaptiveTransitionDuration` job); (4)
**bounded jerk** — no mechanical snap that reads as a glitch (except deliberate SNAP). Translate to
targets: **each pull style reproduces its (duration, curve) profile within tolerance, is monotonic
with bounded jerk and endpoint-exact, and adaptive duration scales with pull distance.**

## 3. Current state in NeoProCamX (verified)
- `AIFocusDistanceController.kt` (`internal object`, line 62) exposes the pull API:
  `applyCinematicTransition` (154), `applyFocusPull(... pullStyle = FocusPullStyle.SMOOTH)` (283),
  with style→profile mapping SMOOTH→EASE_IN_OUT_CUBIC, RACK→EASE_IN_OUT_QUAD, CREEP→SMOOTHERSTEP,
  SNAP→EASE_OUT_QUAD (`AIFocusDistanceController.kt:291-294`); `FocusPullStyle` enum (304-310);
  easing-curve library incl. `SMOOTHSTEP`/`SMOOTHERSTEP` (57-59, 514-515).
- **Test-only:** the live coordinator calls `resolveDepthAwareFocusStep` (s5-f05), not the pull
  API; `applyFocusPull` / `applyCinematicTransition` have no production caller (the cine pull UI
  that would drive them, s5-f07, is itself unwired).
- Status: **⚪ scaffold.** Wiring + elevation prompt behind a no-downgrade gate.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Style-profile fidelity:** each of SMOOTH / RACK / CREEP / SNAP reproduces its mapped
   (duration, easing curve) profile sample-for-sample within tolerance; unit test per style.
2. **Endpoint-exact + monotonic + bounded jerk:** every pull lands exactly on the target diopter,
   is monotonic between endpoints, and has bounded jerk (SNAP exempted on the final segment); test
   asserts all three.
3. **Adaptive duration:** `calculateAdaptiveTransitionDuration` scales monotonically with pull
   distance within configured min/max bounds; parameter-sweep test.
4. **Wired to the lens (NEW):** the focus-pull engine drives the manual-lens-distance path through
   the cine UI (s5-f07), behind a no-downgrade gate; with the gate off, no pull is ever issued.
5. **Predictive target:** `estimateTargetDioptersPredictive` leads a moving target so the pull lands
   correct at completion; test asserts the predicted endpoint leads the last observation.
6. **No-downgrade default:** focus-pull defaults **OFF**; with it off, autofocus + lens drive behave
   exactly as today (s5-f04/f05 untouched). Owner enables device-side.
7. **Budget:** pull stepping is O(1) per frame; no per-frame model.

## 5. Implementation prompt (hand this to the agent)
**Scope:** wire `applyFocusPull` / `applyCinematicTransition` (SMOOTH/RACK/CREEP/SNAP) to the live
manual-lens-distance drive via the cine UI (s5-f07), behind a no-downgrade gate; lock the curve
fidelity. **Non-goals:** do not change the easing math; do not alter the default depth/AF path
(s5-f04/f05); do not enable pulls by default; no new model (no-vendor).

**Plan:**
1. **Golden-lock first.** Pin current lens-drive output (pull off = today) as golden.
2. Route the cine UI's pull trigger (s5-f07) through `applyFocusPull` → manual-lens-distance set,
   gated behind a flag.
3. Add per-style fidelity tests, endpoint/monotonic/jerk tests, adaptive-duration sweep, predictive
   endpoint test.

**Files:** `app/src/main/java/com/neoproc/neoproccamx/ai/AIFocusDistanceController.kt` (read-only
math), `CameraController` manual-lens path, the cine UI (s5-f07), new test cases.

**ABI:** none (Kotlin only).

**Gating:** focus-pull behind a flag defaulting OFF; default = today's lens drive exactly, proven by
the golden lock.

## 6. Acceptance & verification
- **Unit/golden (JVM):** per-style fidelity (SMOOTH/RACK/CREEP/SNAP); endpoint/monotonic/jerk;
  adaptive-duration sweep; predictive endpoint; pull-off lens-drive golden (today unchanged).
- **Device (vivo I2405 / Adreno):** trigger each pull style between two marks — motion feels
  correct per style, lands exactly, no glitch (except deliberate SNAP); disable — lens drive
  identical to today.

## 7. Risks & no-downgrade guardrails
- **Pull breaks lens drive:** gated OFF by default; pull-off golden locks today.
- **Overshoot / glitch:** endpoint-exact + bounded-jerk tests.
- **Wrong feel per style:** per-style fidelity tests against the mapped profile.
- **Lag:** O(1) per-frame stepping; no new model (no-vendor).

## 8. References
- Roadmap: propose CP-F9 in
  [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Reference: ARRI Hi-5 / cmotion programmable pull profiles; rack-focus craft; easing-curve theory.
- Source: `AIFocusDistanceController.kt:57-59, 62, 154, 283, 291-294, 304-310, 514-515`
  (pull API is test-only)
- Related prompts: s5-f07, s5-f05, s5-f04
