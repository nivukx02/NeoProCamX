# Premium Camera Experience Upgrade Design

Date: 2026-05-13

## Goal

Upgrade NeoProCamX's camera experience to feel premium, responsive, and reliable without changing the existing UI structure or camera features.

This is an experience-layer upgrade, not a visual redesign. The existing camera screen layout, controls, mode rail, HUD locations, settings entry points, HDR/LOG/RAW behavior, and recording features must remain intact.

## Non-Negotiable Scope

- Do not replace the existing UI.
- Do not move core controls or change the current screen structure.
- Do not remove, rename, or hide existing camera features.
- Do not break the already-fixed mode switch behavior for NORMAL, PRO, HDR, LOG, and RAW.
- Do not fake camera quality behavior or change imaging contracts while polishing UI.
- Do not add heavy animations that reduce preview FPS or recording stability.

## Approaches Considered

### 1. Visual Rebuild

This would restyle the camera shell, change layout hierarchy, and redesign the mode rail and controls. It could look newer, but it conflicts with the current instruction to keep the existing UI.

Decision: rejected for this task.

### 2. Minimal Bug Fixes Only

This would fix obvious UI bugs and avoid deeper interaction polish. It is low risk, but it would not reach the requested high-end experience level.

Decision: rejected because it under-delivers.

### 3. Experience-Layer Upgrade

This keeps the current UI and upgrades the systems behind touch feedback, transitions, render scheduling, state diffing, and device verification.

Decision: approved direction.

## Architecture

The work should add small testable policy units around the existing Android views instead of embedding more behavior directly into `MainActivity`.

Planned units:

- `CameraHapticFeedbackPolicy`: maps camera actions to consistent haptic intents.
- `CameraMotionPolicy`: defines safe durations, scales, alphas, and interruption rules for camera UI motion.
- `CameraUiRenderScheduler`: coalesces expensive UI refreshes to the display frame instead of letting every state collector render immediately.
- `CameraUiStateDiffPolicy`: decides whether HUD, mode rail, shutter, and panel state actually changed before touching views.
- `CameraInteractionTelemetryPolicy`: records lightweight timestamps for mode switch, panel open, shutter press, and recording transitions so lag can be measured.

Existing components stay in place:

- `activity_main.xml` remains the camera screen structure.
- `MainActivity` remains the integration point.
- Existing policies such as mode preset selection, mode rail auto-selection, recording button visual state, status HUD formatting, and preview stability remain authoritative.
- Existing ViewModel flows remain the source of state.

## Interaction Design

Touch feedback should feel deliberate and restrained:

- Tap-down uses a short scale response on buttons that already have press feedback.
- Release returns cleanly even if the user cancels or drags away.
- Mode changes use one tactile tick plus a short selected-state pop.
- Capture uses a firm confirmation haptic.
- Record start uses confirmation; record stop uses a stronger completion feel.
- Warnings and rejected actions use a distinct reject/error haptic.
- Repeated haptics are rate-limited so rapid state updates do not buzz the device.

Animations must be short, interrupt-safe, and layout-stable:

- No animation should change layout dimensions.
- No animation should block camera preview or recording.
- Mode rail selection animation should not override direct taps.
- Panel show/hide should cancel previous animations cleanly.
- Shutter state changes should avoid repeated animation when recording state has not changed.
- Accessibility and system animation scale should be respected where practical.

## Smoothness Design

The current `MainActivity` has many independent flow collectors that call render functions. This can cause redundant invalidation during camera state changes.

The upgrade should:

- Route repeated chrome refreshes through a frame-aligned scheduler.
- Skip view writes when text, selection, alpha, visibility, scale, or enabled state already matches the target.
- Keep high-frequency preview overlays sampled, but avoid triggering unrelated chrome renders from overlay-only updates.
- Keep mode-switch UI state and preview/session state synchronized without stale selected pills.
- Avoid allocation-heavy formatting inside high-frequency collectors.
- Keep all UI writes on the main thread and all camera work off the main thread.

## Data Flow

ViewModel and camera state flows continue to emit the truth.

New flow:

1. A state collector receives camera/UI state.
2. The collector updates lightweight local state or requests a named render pass.
3. `CameraUiRenderScheduler` coalesces compatible render requests until the next display frame.
4. The render pass uses `CameraUiStateDiffPolicy` before writing to Android views.
5. Haptic and motion policies are applied only for user-initiated interactions or real state transitions, not passive state replays.

## Error Handling

The upgrade must fail quietly:

- If haptics are unavailable, interactions still work.
- If a view is not laid out, animation falls back to direct final state.
- If a pending animation is interrupted, the next state owns the final visual result.
- If render scheduling is cancelled during lifecycle stop, no stale UI work should run after stop.
- If profiling tools are unavailable, Gradle tests and device logcat checks still provide verification.

## Performance Strategy

The implementation should optimize perceived smoothness without adding GPU or camera workload:

- Use frame-coalesced UI rendering for chrome updates.
- Diff before assigning view properties.
- Rate-limit haptics.
- Reuse interpolators and avoid repeated object creation in hot UI paths.
- Avoid long animations; target 80-180 ms for most microinteractions.
- Preserve existing preview/render throttles and overlay sampling.
- Measure with `gfxinfo`, logcat, and app-level timestamps on a connected device.

## Testing Strategy

Unit tests:

- Haptic mapping and rate limiting.
- Motion policy durations and scale bounds.
- Render scheduler coalescing and cancellation.
- UI diff policy skips unchanged values.
- Mode rail direct-tap suppression still wins over auto-selection.
- HDR/LOG/RAW preset selection remains authoritative.

Device QA:

- Launch app and confirm no crash.
- Tap NORMAL, PRO, HDR, LOG, RAW and confirm selected state refreshes correctly.
- Open/close manual controls and settings with no visual stuck state.
- Toggle overlays and confirm preview remains responsive.
- Start and stop recording and confirm shutter state, recording HUD, and haptics.
- Capture logcat around mode switching and recording.
- Capture `gfxinfo` frame stats before and after the smoothness pass.

Build verification:

- `:app:testDebugUnitTest`
- `:app:lintDebug`
- `:app:assembleDebug`
- Device install and launch when an adb device is attached.

## Code Change Boundaries

Expected code changes are limited to:

- New small policy classes under `app/src/main/java/com/neoproc/neoproccamx/ui`.
- Focused tests under `app/src/test/java/com/neoproc/neoproccamx/ui`.
- Targeted integration edits in `MainActivity`.
- Only resource edits that are necessary for existing UI animation polish, with no layout redesign.

No fixed line cap is imposed. The implementation should write as many lines as needed for maintainable, tested code, while avoiding broad rewrites.

## Acceptance Criteria

- Existing UI layout and controls are still recognizable and functionally unchanged.
- Mode switching refreshes immediately and does not stick on the previous mode.
- UI actions have consistent, rate-limited haptic feedback.
- Panel, shutter, and mode rail animations are smooth and interrupt-safe.
- Redundant render calls are reduced through scheduling and diffing.
- Unit tests cover the new policies.
- Lint, unit tests, debug build, device launch, and logcat checks pass.
- Any remaining hardware or Android platform limitations are reported clearly.
