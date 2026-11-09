# CameraFocusControl Cine API (rack focus, eased transitions, DoF, peaking) — Elite Upgrade Prompt

> Read [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) first. Its global contract applies and
> is not restated here.

| Field | Value |
|---|---|
| **Subsystem** | 5 — Focus / Autofocus |
| **Prompt ID** | s5-f07 |
| **Maps to checkpoint(s)** | new — propose CP-F8 (cinema focus-pull UI + cine-API wiring) |
| **Current working level** | ⚪ test-only-scaffold (**zero production callers**) |
| **Feature rating (potential)** | 2/10 now → 7/10 target |
| **Code-quality rating (now)** | 7/10 (clean, well-factored math; just never called) |
| **Risk · Size** | M · L |
| **Depends on** | s5-f08 (AIFocusDistanceController pull API), s5-f09 (peaking), s5-f01 (afMode) |

## 1. What this feature is
`CameraFocusControl` is a library of cinema focus primitives: optimal-AF-mode selection,
face/zone/spot focus-region builders, rack-focus stepping, eased focus transitions, predictive
next-focus position, focus-hunting detection, depth-of-field (hyperfocal/near/far) computation,
and focus-peaking intensity. It is the toolkit a cinema focus-pull UI would call to do controlled,
director-grade focus moves. Today every one of these functions has **zero production callers** —
it is pure, well-tested math with no UI or pipeline wiring, so none of it reaches the lens or the
screen.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
This is the digital follow-focus / wireless lens-control world: ARRI Hi-5 / cforce motors, cmotion
cPRO, Preston FI+Z. A focus puller programs marks (A/B/C points), pulls between them on a defined
curve and speed, reads exact DoF from the lens's depth scale + the chosen T-stop, and the system
shows distance/DoF feedback. Elite behavior: (1) **mark-to-mark rack** between named focus
distances on a chosen easing curve at a chosen duration; (2) **accurate DoF readout** — hyperfocal,
near, far limits from focal length, T-stop, CoC, and subject distance; (3) **smooth, breathing-
aware moves** (no jerk, controlled speed); (4) **hunting detection** so the operator knows when AF
is unstable. Translate to targets: **rack/ease curves match the analytic easing within tolerance,
DoF math matches the textbook hyperfocal formulae within tolerance, and a real focus-pull UI can
drive the lens through these primitives.**

## 3. Current state in NeoProCamX (verified)
- `CameraFocusControl.kt` exposes the full cine toolkit: `selectOptimalAfMode` (164),
  `buildFaceFocusRegion` (239), `buildZoneFocusRegion` (277), `buildSpotFocusRegion` (324),
  `calculateRackFocusStep` (350), `calculateEasedFocusTransition` (379), `predictNextFocusPosition`
  (402), `isFocusHunting` (425), `calculateDepthOfField` (454), `calculateFocusPeakingIntensity`
  (499).
- **Zero production callers:** a `grep` for `calculateRackFocusStep | calculateEasedFocusTransition
  | calculateDepthOfField | selectOptimalAfMode` across `app/src` returns **only**
  `CameraFocusControl.kt` itself — no `camera/`, `ui/`, or pipeline caller. The math is tested in
  isolation but never wired.
- Status: **⚪ scaffold.** Wiring + elevation prompt behind a no-downgrade gate.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **DoF accuracy:** `calculateDepthOfField` matches the textbook hyperfocal / near-far formulae
   (H = f²/(N·c) + f; near/far from subject distance) within tolerance over a parameter sweep.
   Unit test against analytic values.
2. **Rack/ease fidelity:** `calculateEasedFocusTransition` + `calculateRackFocusStep` reproduce the
   chosen easing curve sample-for-sample within tolerance; monotonic, bounded-jerk, endpoint-exact.
3. **Wired focus-pull UI (NEW):** a cinema focus-pull control (set marks A/B, pick curve + duration,
   trigger) drives the lens through these primitives, behind a no-downgrade gate. The control is
   additive UI; with the gate off it is invisible and changes nothing.
4. **DoF / peaking readout (NEW):** DoF (near/far/hyperfocal) and peaking-intensity are surfaced to
   the operator when the cine mode is enabled.
5. **Hunting detection live:** `isFocusHunting` feeds an operator indicator (and may inform the
   coordinator's risk gate, s5-f04) when cine mode is on.
6. **No-downgrade default:** the cine UI + lens-pull path defaults **OFF**; with it off, autofocus
   behaves exactly as today (s5-f01..f04 untouched). Owner enables device-side.
7. **Budget:** focus-pull stepping is O(1) per frame; DoF/peaking readout is cheap; no per-frame
   model.

## 5. Implementation prompt (hand this to the agent)
**Scope:** wire `CameraFocusControl`'s rack/ease/DoF/peaking/hunting primitives into a cinema
focus-pull UI + manual-lens drive path, behind a no-downgrade gate. **Non-goals:** do not change
the math (it's tested); do not alter the default autofocus path (s5-f01..f04); do not enable the
cine mode by default; no new model (no-vendor).

**Plan:**
1. **Golden-lock first.** Pin current autofocus request output (cine mode off = today) as golden.
2. Add a cine focus-pull control (set A/B marks, easing curve, duration) gated behind a flag;
   route its per-frame step through the manual-lens-distance path (and `AIFocusDistanceController`
   pull API, s5-f08).
3. Surface DoF (near/far/hyperfocal) + peaking-intensity + hunting indicator when cine mode is on.
4. Add the DoF-vs-analytic test and the rack/ease-fidelity test (locking the primitives to spec).

**Files:** `app/src/main/java/com/neoproc/neoproccamx/camera/CameraFocusControl.kt` (read-only
math), the cine focus-pull UI (new), `CameraController` manual-lens path, new test cases.

**ABI:** none (Kotlin / UI).

**Gating:** cine mode behind a flag defaulting OFF; default = today's autofocus exactly, proven by
the golden lock.

## 6. Acceptance & verification
- **Unit/golden (JVM):** DoF-vs-analytic test; rack/ease-fidelity test; cine-off autofocus golden
  (today's request unchanged).
- **Device (vivo I2405 / Adreno):** enable cine mode, set A/B marks, trigger a rack — lens pulls
  smoothly on the chosen curve; DoF/peaking/hunting readouts correct; disable cine mode — autofocus
  identical to today.

## 7. Risks & no-downgrade guardrails
- **Cine path breaks autofocus:** entire feature gated OFF by default; cine-off golden locks today.
- **Jerky / inaccurate pull:** rack/ease-fidelity test (monotonic, bounded-jerk, endpoint-exact).
- **Wrong DoF readout:** DoF-vs-analytic test.
- **Lag:** O(1) per-frame stepping; no new model (no-vendor).

## 8. References
- Roadmap: propose CP-F8 in
  [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Reference: ARRI Hi-5 / cforce, cmotion cPRO, Preston FI+Z follow-focus; hyperfocal / DoF math;
  rack-focus & focus-breathing control.
- Source: `CameraFocusControl.kt:164, 239, 277, 324, 350, 379, 402, 425, 454, 499` (zero production
  callers)
- Related prompts: s5-f08, s5-f09, s5-f01, s5-f04
