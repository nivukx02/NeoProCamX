# Depth-Driven Lens-Distance Drive (AIFocusDistanceController PID) — Elite Upgrade Prompt

> Read [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) first. Its global contract applies and
> is not restated here.

| Field | Value |
|---|---|
| **Subsystem** | 5 — Focus / Autofocus |
| **Prompt ID** | s5-f05 |
| **Maps to checkpoint(s)** | new — propose CP-F6 (depth-biased hybrid AF) |
| **Current working level** | 🟡 partial (PID built + tested, but **hard-gated OFF on all region-capable devices**) |
| **Feature rating (potential)** | 4/10 now → 8/10 target |
| **Code-quality rating (now)** | 8/10 (math + PID are sound; the path is dead on real phones) |
| **Risk · Size** | M · L |
| **Depends on** | s5-f06 (DepthEstimator), s5-f04 (coordinator), s5-f01 (afMode) |

## 1. What this feature is
`AIFocusDistanceController` converts a normalized subject depth into a target lens diopter, then
PID-smooths the lens drive, applies a manual-lens override, and gates on subject/edge confidence.
On phones with **no** hardware AF regions it is the *only* way to follow a subject. The problem:
the controller is invoked from the coordinator **only when `maxAfRegions == 0`** — and essentially
every modern phone reports `maxAfRegions > 0`, so on real devices this entire path is short-
circuited and never reaches the lens. The elite theme is therefore not "turn the PID on everywhere"
(that would fight the superior hardware AF) but a **hybrid: use depth to BIAS the hardware AF
metering region / seed the drive direction**, instead of replacing it.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
This is the digital analogue of a focus puller using a tape-measure / LiDAR rangefinder (cmotion
cPRO, ARRI Hi-5 with the ARRI/cmotion distance system, Preston light-ranger): the puller knows the
subject distance and *pre-positions* the lens, then the fine servo locks it. Modern phones already
have excellent PDAF/Dual-Pixel hardware AF; the elite move is to feed it a **depth prior** so it
drives the *right direction first* and doesn't hunt past the subject — exactly how a rangefinder
biases a manual pull. Translate to targets: **depth prior reduces hardware-AF acquisition time and
overshoot vs. hardware-AF-alone, never makes it worse, and degrades to pure hardware AF when depth
confidence is low.**

## 3. Current state in NeoProCamX (verified)
- `AIFocusDistanceController` is an `internal object` (`AIFocusDistanceController.kt:62`) exposing
  `resolveDepthAwareFocusStep(...)` (depth→diopters + PID + override + confidence gating).
- **The hard gate:** `AIAutoFocusCoordinator.applyAiDrivenFocus()` begins with
  `if (camera.maxAfRegions > 0) { ... return }` (`AIAutoFocusCoordinator.kt:338`) — the PID call at
  `AIAutoFocusCoordinator.kt:348` is **only reached when the device has zero AF regions**. On all
  modern phones (`maxAfRegions > 0`) this entire branch is dead.
- The PID/diopter math itself is sound and unit-tested; it simply never drives the lens on real
  hardware.
- Status: **partial / effectively dormant on shipping devices.** Wiring + elevation prompt.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Depth-prior bias (NEW), region-capable path:** on `maxAfRegions > 0` devices, when depth
   confidence is high, the coordinator places the AF metering region at the subject's depth-derived
   location and may emit a *one-shot* lens-distance hint in the drive direction — it does NOT take
   over continuous servo. Test asserts the region/hint is depth-consistent and bounded.
2. **No-fight guarantee:** the depth bias only ever *augments* hardware AF; if depth confidence is
   low the path is a no-op and hardware AF runs exactly as today. Test asserts low-confidence ⇒
   identical request to baseline.
3. **Region-less path preserved:** on `maxAfRegions == 0` devices the existing full PID drive is
   unchanged (regression-locked).
4. **Acquisition improvement (device):** with depth-bias ON, hardware-AF acquisition time and
   overshoot are ≤ hardware-AF-alone on a subject step (measured on device); never worse.
5. **No-downgrade default:** the depth-bias hybrid defaults **OFF**; with it off, every request
   (region-capable and region-less) is bit-for-bit today's output. Owner flips it on device-side.
6. **Budget:** depth prior reuses the already-computed DepthEstimator output (s5-f06); zero new
   per-frame inference.

## 5. Implementation prompt (hand this to the agent)
**Scope:** add a depth-prior hybrid that *biases* hardware AF on region-capable devices, behind a
no-downgrade gate; preserve the existing region-less PID path exactly. **Non-goals:** do NOT enable
the full PID lens-takeover on region-capable devices (it fights hardware AF — explicitly forbidden);
do not add a new depth model (no-vendor, reuse s5-f06); do not change subject scoring (s5-f03).

**Plan:**
1. **Golden-lock first.** Pin the current request output for both `maxAfRegions == 0` and `> 0`
   devices (the `> 0` path currently emits no AI-driven focus — that no-op is the golden).
2. Add a gated branch in `applyAiDrivenFocus` for `maxAfRegions > 0`: when depth confidence is high,
   compute a depth-derived metering region (and optional one-shot direction hint) and feed it
   through the existing region path; otherwise no-op.
3. Reuse `resolveDepthAwareFocusStep` only to derive the *prior* (direction/region), not to seize
   continuous servo.
4. Add low-confidence-degrades-to-baseline tests + region-less regression lock.

**Files:** `app/src/main/java/com/neoproc/neoproccamx/ai/AIAutoFocusCoordinator.kt`,
`AIFocusDistanceController.kt`, new test cases.

**ABI:** none (Kotlin only).

**Gating:** the depth-bias hybrid behind a flag defaulting OFF; default reproduces today's request
exactly on both device classes, proven by the golden lock.

## 6. Acceptance & verification
- **Unit/golden (JVM):** region-less PID regression; depth-bias-OFF golden (both device classes
  unchanged); depth-consistent region test; low-confidence ⇒ baseline test.
- **Device (vivo I2405 / Adreno — `maxAfRegions > 0`):** subject step (near↔far) — with depth-bias
  ON, acquisition time + overshoot ≤ baseline; with it OFF, identical to today.

## 7. Risks & no-downgrade guardrails
- **Fighting hardware AF:** hybrid only biases the region / one-shot hint — never takes over servo;
  forbidden-by-design and asserted by test.
- **Bad depth → wrong focus:** confidence gate degrades to pure hardware AF baseline.
- **Region-less regression:** existing PID path regression-locked.
- **Lag / thermal:** reuses existing depth output; zero new inference (no-vendor).

## 8. References
- Roadmap: propose CP-F6 (depth-biased hybrid AF) in
  [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Reference: cmotion cPRO, ARRI Hi-5 distance system, Preston light-ranger (rangefinder-biased pull).
- Source: `AIAutoFocusCoordinator.kt:337-368` (the `maxAfRegions > 0` gate at line 338),
  `AIFocusDistanceController.kt:62`
- Related prompts: s5-f06, s5-f04, s5-f01
