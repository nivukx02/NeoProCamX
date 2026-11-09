# Manual Camera Controls (ISO / SS / WB / MF / EV / AF / metering / RAW) — Elite Upgrade Prompt

> Global contract in [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) applies and is not restated.

| Field | Value |
|---|---|
| **Subsystem** | 8 — UI + Quality gates + Build/Test infra |
| **Prompt ID** | s8-f02 |
| **Maps to checkpoint(s)** | new — propose CP-U2 (manual-control precision hardening) |
| **Current working level** | 🟢 live-default-on |
| **Feature rating (potential)** | 8/10 |
| **Code-quality rating (now)** | 8/10 |
| **Risk · Size** | L · M |
| **Depends on** | s8-f01 (HUD feedback), s8-f04 (controls dispatch through the ViewModel) |

## 1. What this feature is
The operator's direct hold on the camera: an 8-tab manual panel for ISO, shutter speed, white
balance, manual focus distance, exposure compensation, AF mode, metering, and RAW toggle. Values are
mapped through pure value/label/range mappers, dispatched to Camera2, and surfaced back on the HUD.
Tap-to-focus and a focus/EV touch handler translate screen gestures into sensor-space control.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
Cinema bodies (ARRI ALEXA 35, RED V-RAPTOR, Sony VENICE 2) expose deterministic, repeatable manual
control:
- **Shutter as angle** (45°–360°), not just seconds — operators reason in shutter angle vs frame rate;
  180° at 24p = 1/48 s. The UI should offer angle and convert exactly.
- **ISO as native/base ISO steps** with dual-base awareness (VENICE 800/3200) — selecting base ISO
  changes noise floor, not just gain.
- **White balance in Kelvin + tint (CC/magenta-green)**, settable to the 10 K, with presets
  (3200 K tungsten, 5600 K daylight).
- **Manual focus in physical distance / focus marks**, repeatable to the same diopter; follow-focus
  precision.
- **EV / exposure index** with 1/3-stop detents; metering modes (spot/centre/matrix) with a visible
  metered value.
- Every control is **monotonic, detented, and round-trips** (set X → readback X), and **RAW** capture
  is a hard transport guarantee, not a label.

## 3. Current state in NeoProCamX (verified)
- Dispatch + state sync: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraManualControlDispatcher.kt`,
  `.../ui/CameraManualControlStateSyncPolicy.kt`.
- Pure mappers: `.../ui/controls/ManualControlValueMapper.kt`,
  `.../ui/controls/ManualControlLabelFormatter.kt`, `.../ui/ManualControlUtils.kt`.
- Cinema policy: `.../ui/controls/CinemaManualControlPolicy.kt`; panel UI
  `.../ui/controls/ManualControlsFragment.kt`.
- Per FEATURE_CATALOG §8: "8-tab manual controls backed by the ViewModel; pure value/label/range
  mappers; tap-to-focus + focus-EV touch handling; custom seek control." Live and default-on.
- **Gap (verify on read):** shutter likely exposed in seconds only (no shutter-angle mode); WB likely
  Kelvin-only without an explicit tint axis; readback round-trip not asserted by a contract test;
  dual-base ISO not surfaced.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Shutter-angle mode (new, opt-in):** angle↔seconds conversion exact for the active fps
   (180° @ 24p == 1/48 s within float tol) — host test on the mapper. Default remains seconds.
2. **WB Kelvin + tint:** white balance settable in Kelvin and a CC tint axis; mapper round-trips
   (set→readback) within quantization step — host test.
3. **Monotonic detented mappers:** ISO/SS/EV mappers are monotonic with documented detent steps
   (1/3-stop); a test asserts monotonicity + detent count + round-trip for each axis.
4. **RAW is honest:** the RAW toggle, when on, is proven by `OmegaCapabilityGate` from real transport
   (s8-f08) — the UI cannot claim RAW the pipeline didn't deliver.
5. **Tap-to-focus geometry:** preview→sensor mapping accurate (a known tap maps to the expected
   sensor rect within tolerance) — host test on the geometry transform.
6. **No-downgrade default:** every existing control behaves bit-identically; shutter-angle and tint
   are additive opt-in surfaces with defaults reproducing today's behaviour.

## 5. Implementation prompt (hand this to the agent)
**Scope:** harden the manual mappers (monotonicity, round-trip, shutter-angle, WB tint) and lock
them with contract tests. **Non-goals:** do not change Camera2 session orchestration; do not bypass
the dispatcher; no new dependency.

**Plan:**
1. **Golden-lock** current mapper outputs (value→label tables) with a characterization test.
2. Add shutter-angle conversion + WB tint to the mappers, defaulting to the current behaviour.
3. Add monotonicity/detent/round-trip contract tests for ISO/SS/EV/WB/MF.
4. Route the RAW toggle's "claim" through the Omega gate so it can only assert proven transport.

**Files:** `ManualControlValueMapper.kt`, `ManualControlLabelFormatter.kt`, `ManualControlUtils.kt`,
`CinemaManualControlPolicy.kt`; tap geometry helper in the dispatcher. No ABI/push-constant change.

**Tests:** `ManualControlMapperContractTest` (monotonic + detent + round-trip per axis),
`ShutterAngleConversionTest`, `WbKelvinTintRoundTripTest`, `TapToFocusGeometryTest`.

**Gating:** shutter-angle/tint additive and default-off; existing mapping is the default path.

## 6. Acceptance & verification
- **JVM:** all mapper/contract tests green; existing label-table characterization unchanged for default.
- **Device (vivo):** set ISO/SS/WB/MF/EV → readback matches; tap-to-focus lands on the tapped subject;
  RAW toggle only enables when transport is genuinely RAW.
- **Gate keys:** RAW claim must prove through `OmegaCapabilityGate` (NOT_AVAILABLE if unproven).

## 7. Risks & no-downgrade guardrails
- **Exposure jump on mode switch:** shutter-angle conversion validated; default seconds path untouched.
- **False RAW claim:** Omega gate gates the claim — impossible to advertise unproven RAW.
- **Control drift:** round-trip contract tests catch any mapper regression in CI.

## 8. References
- Reference: ARRI/RED/Sony manual-control conventions (shutter angle, dual-base ISO, Kelvin+tint, focus marks); SMPTE 180°-shutter convention.
- Source: `ui/CameraManualControlDispatcher.kt`, `ui/CameraManualControlStateSyncPolicy.kt`, `ui/controls/{ManualControlValueMapper,ManualControlLabelFormatter,CinemaManualControlPolicy,ManualControlsFragment}.kt`, `ui/ManualControlUtils.kt`.
- Related prompts: s8-f01, s8-f04, s8-f08.
