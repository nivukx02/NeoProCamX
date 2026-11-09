# Camera ViewModel Controller Split Design

## Goal

Split `CameraViewModel` internals into mode, recording, preview, and settings controllers while keeping the existing public `CameraViewModel` API stable for `MainActivity`, fragments, and tests.

## Architecture

`CameraViewModel` remains the Android `ViewModel` facade and owner of lifecycle-scoped state. New focused controllers receive small host interfaces implemented by `CameraViewModel`, so each controller can operate on only the state/actions it needs. Public methods in `CameraViewModel` delegate to the appropriate controller; callers do not need to change.

## Controllers

- `CameraModeController`: app/cinema mode transitions, RAW/HDR/LOG mode state, and mode-switch batching hooks.
- `CameraRecordingController`: recording-facing configuration entrypoints and recording start/stop/toggle delegation.
- `CameraPreviewController`: preview aspect, surface/geometry-facing entrypoints, and preview source aspect calculation.
- `CameraSettingsController`: UI toggles, monitoring toggles, AI/manual-control settings, and persisted preference updates.

## Testing

Add source-structure contract tests that require the four controller files and representative `CameraViewModel` delegation calls. Preserve existing unit tests as behavioral regression coverage. Run targeted tests after each extraction, then the full unit suite, lint, and APK assembly.
