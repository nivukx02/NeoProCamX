# Tap-to-Focus (metering region + AF trigger) — Elite Upgrade Prompt

> Read [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) first. Its global contract applies and
> is not restated here.

| Field | Value |
|---|---|
| **Subsystem** | 5 — Focus / Autofocus |
| **Prompt ID** | s5-f02 |
| **Maps to checkpoint(s)** | new — propose CP-F3 (tap-to-focus hardening) |
| **Current working level** | 🟢 live-default-on |
| **Feature rating (potential)** | 8/10 |
| **Code-quality rating (now)** | 8/10 |
| **Risk · Size** | L · S |
| **Depends on** | s5-f01 (afMode), s5-f04 (AI coordinator hand-off) |

## 1. What this feature is
On a viewfinder tap, the app builds AF + AE metering regions, maps the preview-space tap point
through the active crop/zoom/orientation transform into sensor coordinates, attaches the region
to the capture request, and triggers an AF scan (and optionally an AE re-meter) at that point. It
must also hand off cleanly to / from the AI subject tracker so a manual tap overrides automatic
tracking and a subsequent loss-of-subject returns to continuous AF.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
Touch-AF is a broadcast/hybrid feature (Canon's touch-and-drag AF, Sony's touch-focus, the iPhone
Pro tap-to-focus-and-lock). The elite bar: (1) **spatial accuracy** — the focused point is
exactly under the finger across all zoom/crop/orientation states (no parallax/rotation error);
(2) **instant override** — a tap interrupts continuous/tracking AF immediately, like a focus
puller grabbing the wheel; (3) **deterministic region size** — the metering box is a documented
fraction of frame, not jittery; (4) **clean release** — focus-and-hold vs focus-and-track is a
defined contract. Translate to targets: **tap→sensor mapping is exact (round-trip error 0 px
within rounding) under every crop/rotation, region geometry is deterministic, and the AF trigger
fires exactly once per tap.**

## 3. Current state in NeoProCamX (verified)
- Tap region construction + sensor-rect mapping live in `TapFocusRequestPolicy.kt`; the focus
  region is only built when `maxAfRegions > 0` (`TapFocusRequestPolicy.kt:33`), otherwise AF
  falls back to a center/continuous strategy.
- Preview→sensor geometry, AF trigger, and subject-metering reset on tap are driven through
  `CameraController` request assembly and the touch gesture policy
  (`ui/TouchFocusGesturePolicy.kt`).
- Tap-vs-AI-tracking hand-off: the AI coordinator clears subject metering on tracking loss
  (`AIAutoFocusCoordinator.kt:330-333` — `resetTrackingState(clearMeteringRegion = true)`).
- Status: **live, default-on.** Hardening prompt — geometry and trigger already reach hardware.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Exact geometry round-trip:** preview→sensor→preview mapping of a tap point is within
   rounding error for the full matrix of {zoom ratio, crop region, sensor orientation 0/90/180/270,
   front/back mirror}. Unit test asserts max round-trip error ≤ 1 px.
2. **Deterministic region size:** the AF/AE metering box is a fixed documented fraction of the
   active array (e.g. 1/8 linear), clamped to bounds; test asserts size + clamping at frame edges.
3. **Single trigger per tap:** exactly one `CONTROL_AF_TRIGGER_START` is emitted per tap, with the
   correct cancel/idle reset sequence; test asserts the trigger state machine.
4. **Override precedence:** a tap clears any active AI subject metering region in the same request
   (no stale region survives a tap); test asserts coordinator state after tap.
5. **maxAfRegions==0 fallback:** when the device reports no AF regions, the tap still triggers a
   center AF scan and never builds an out-of-spec region (regression-locked).
6. **No-downgrade default:** all current mapping/region/trigger outputs unchanged; new work is
   additive tests + extraction.
7. **Budget:** O(1) per tap; zero per-frame cost.

## 5. Implementation prompt (hand this to the agent)
**Scope:** golden-lock and harden the tap geometry, region sizing, trigger state machine, and the
tap↔AI-tracker hand-off. **Non-goals:** do not redesign the touch gesture UI, do not change
afMode preference (s5-f01), do not touch AI scoring (s5-f03).

**Plan:**
1. **Golden-lock** the current preview→sensor mapping for the full transform matrix (capture the
   live output as the golden).
2. Add the round-trip geometry test, region-size/clamp test, and trigger-state-machine test.
3. Add the override-precedence test asserting a tap clears AI subject metering.
4. Add the `maxAfRegions==0` fallback regression test.

**Files:** `app/src/main/java/com/neoproc/neoproccamx/camera/TapFocusRequestPolicy.kt`,
`ui/TouchFocusGesturePolicy.kt`, `AIAutoFocusCoordinator.kt` (hand-off, read-only), new test cases.

**ABI:** none.

**Gating:** pure tests + extraction; no-downgrade default = identical mapping/region/trigger output
for all existing inputs, proven by the golden lock.

## 6. Acceptance & verification
- **Unit/golden (JVM):** geometry round-trip matrix green; region-size/clamp; trigger state machine;
  override-precedence; `maxAfRegions==0` fallback.
- **Device (vivo I2405 / Adreno):** tap at frame corners and center at 1×/2×/max zoom in portrait
  + landscape — focused point lands under the finger; confirm exactly one AF scan per tap; confirm
  tap interrupts AI tracking immediately.

## 7. Risks & no-downgrade guardrails
- **Mis-mapped tap (focus wrong spot):** exhaustive transform-matrix round-trip test.
- **Double / missed trigger:** explicit trigger state-machine test.
- **Stale AI region after tap:** override-precedence test.
- **Out-of-bounds region at edges:** clamp test at frame borders.
- **Lag:** O(1) per tap; no per-frame addition.

## 8. References
- Roadmap: propose CP-F3.
- Reference: Canon touch-and-drag AF, Sony touch focus, Camera2 `CONTROL_AF_TRIGGER` + metering
  regions, sensor active-array crop mapping.
- Source: `TapFocusRequestPolicy.kt:17, 33`, `AIAutoFocusCoordinator.kt:330`,
  `ui/TouchFocusGesturePolicy.kt`
- Related prompts: s5-f01, s5-f03, s5-f04
