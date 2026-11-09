# AI AF Coordinator: Metering Follow + Anti-Hunting (CP-F1) — Elite Upgrade Prompt

> Read [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) first. Its global contract applies and
> is not restated here.

| Field | Value |
|---|---|
| **Subsystem** | 5 — Focus / Autofocus |
| **Prompt ID** | s5-f04 |
| **Maps to checkpoint(s)** | CP-F1 (anti-hunting) **DONE** — propose CP-F5 (scene-cut / risk gating extensions) |
| **Current working level** | 🟢 live-default-on |
| **Feature rating (potential)** | 8/10 |
| **Code-quality rating (now)** | 8/10 |
| **Risk · Size** | M · M |
| **Depends on** | s5-f03 (FocusEngine target), s5-f01 (afMode), s5-f05 (lens-distance drive) |

## 1. What this feature is
`AIAutoFocusCoordinator` is the bridge between the AI subject target (s5-f03) and the hardware.
It follows the chosen subject by moving the AF metering region (on region-capable devices) or by
driving lens distance (on region-less devices, s5-f05), with per-target smoothing,
motion-compensated prediction, accept/hold/retrigger gating, and anti-hunting suppression
(`shouldSuppressRetrigger` blocks an AF retrigger while the lens is already scanning/hunting or
when the move is below a risk threshold). It is fed **real** hardware lens diopters, not synthetic
values. CP-F1 (anti-pump) is already landed.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
A focus puller never "hunts" — they commit to a target and ride it smoothly; they do not snap back
and forth searching. The servo-AF equivalents (Sony Real-time Tracking, Canon Dual Pixel servo)
add: (1) **scene-cut awareness** — on a hard cut / whip-pan the system re-acquires deliberately
rather than chasing transient garbage; (2) **velocity-aware damping** — fast genuine motion gets
quick follow, micro-jitter gets heavy damping; (3) **risk gating** — low-confidence or
low-contrast moments *hold* the last good position instead of scanning. Translate to targets:
**zero pump cycles on a static subject, bounded follow-jerk on a moving subject, deliberate
re-acquire across a scene cut, and hold-on-low-confidence rather than hunt.**

## 3. Current state in NeoProCamX (verified)
- `AIAutoFocusCoordinator.kt` follows the subject: region path when `maxAfRegions > 0`
  (`AIAutoFocusCoordinator.kt:338`), lens-distance path otherwise via
  `AIFocusDistanceController.resolveDepthAwareFocusStep(...)` (`AIAutoFocusCoordinator.kt:348-356`).
- Anti-hunting: `shouldSuppressRetrigger` + accept/hold/retrigger gating (CP-F1) — per-target
  smoothing + motion-compensated prediction; fed real hardware diopters (catalog §5).
- Tracking-loss handling resets metering and returns to continuous AF
  (`AIAutoFocusCoordinator.kt:330-333`).
- Stability metrics live in `AIFocusStabilityMetrics.kt`.
- Status: **live, default-on; CP-F1 done.** This prompt's elite work is the *extension*:
  scene-cut detection + explicit risk gating, behind a no-downgrade gate.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Zero pump on static subject:** with a static, well-lit subject, the coordinator issues zero
   AF retriggers over a fixed window (the CP-F1 guarantee — keep it locked with a regression test).
2. **Bounded follow-jerk:** for a constant-velocity subject, the region/lens follow trajectory has
   bounded jerk (third derivative below a threshold); test asserts smoothing limits.
3. **Scene-cut re-acquire (NEW):** on a detected hard cut / whip-pan (large global-motion or
   subject-discontinuity signal), the coordinator performs a single deliberate re-acquire rather
   than chasing — test asserts one re-acquire, not a pump train.
4. **Risk gating (NEW):** when subject confidence or scene contrast falls below threshold, the
   coordinator *holds* the last good target instead of retriggering; test asserts hold-not-hunt.
5. **No-downgrade default:** scene-cut + risk-gating extensions default OFF (or to thresholds that
   reproduce today's behavior); CP-F1 anti-pump output is bit-for-bit unchanged until the owner
   enables the extension. Locked by a golden replaying a recorded target stream.
6. **Budget:** decision is O(1) per frame on the existing coordinator tick; no new per-frame model.

## 5. Implementation prompt (hand this to the agent)
**Scope:** add scene-cut-aware re-acquire and explicit low-confidence/low-contrast risk gating to
the coordinator, behind a no-downgrade gate; regression-lock CP-F1. **Non-goals:** do not change
the subject scoring (s5-f03); do not enable the lens-distance PID on region-capable devices
(that's s5-f05's hybrid); do not touch tap geometry (s5-f02).

**Plan:**
1. **Golden-lock CP-F1 first.** Replay a recorded subject-target stream; pin the current
   retrigger/hold/follow decisions as the golden.
2. Add a scene-cut signal input (reuse the existing global-motion / subject-discontinuity data the
   coordinator already receives) and a single-deliberate-re-acquire branch behind a gate.
3. Add explicit risk gating (confidence/contrast threshold → hold) behind a gate defaulting to
   today's behavior.
4. Extend `AIFocusStabilityMetrics` to count pump cycles, follow-jerk, and re-acquire events for
   tests.

**Files:** `app/src/main/java/com/neoproc/neoproccamx/ai/AIAutoFocusCoordinator.kt`,
`AIFocusStabilityMetrics.kt`, new coordinator test cases.

**ABI:** none (Kotlin only).

**Gating:** scene-cut + risk-gating gated behind a flag/threshold defaulting to current behavior;
golden proves CP-F1 output unchanged.

## 6. Acceptance & verification
- **Unit/golden (JVM):** CP-F1 zero-pump regression; bounded-jerk test; scene-cut single-re-acquire
  test; risk-gating hold-not-hunt test; default-off golden proving today's stream unchanged.
- **Device (vivo I2405 / Adreno):** static subject (zero pump); subject walking (smooth follow);
  whip-pan / cut (one deliberate re-acquire, no pump train); low-light low-contrast (holds).

## 7. Risks & no-downgrade guardrails
- **Regression of CP-F1 anti-pump:** golden replay locks current decisions before any change.
- **Over-eager hold (focus stuck):** risk threshold tuned + gated; default reproduces today.
- **Chasing a cut:** scene-cut re-acquire is single-shot, asserted by test.
- **Lag:** O(1) per coordinator tick; no new model (no-vendor).

## 8. References
- Roadmap: CP-F1 (done); propose CP-F5 (scene-cut / risk gating) in
  [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Memory: focus-and-startup-upgrades (CP-F1 anti-hunting DONE).
- Reference: Sony Real-time Tracking, Canon Dual Pixel servo AF, focus-puller craft.
- Source: `AIAutoFocusCoordinator.kt:330-333, 338, 348-356`, `AIFocusStabilityMetrics.kt`
- Related prompts: s5-f03, s5-f05, s5-f01
