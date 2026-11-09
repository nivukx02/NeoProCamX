# Video Color Stability Design

**Date:** 2026-05-27

**Problem**

While a subject is brought closer to or farther from the lens, its apparent
color changes during video preview and recording. Current code allows this:
tap focus clears the AE/AWB lock, and NDR/NIVUKX transport forces live auto
white balance even when stable or manual color is expected.

**Scope**

This design covers video modes and both processed and NPV10 workflows:
Normal, Pro, N-Log, NDR10+, NIVUKX Vision, RAW monitoring, and NPV10 capture
or export metadata. It does not promise sensor calibration that has not been
measured, change the selected creative color science, or process a private RAW
master as though it were rendered video.

## Required Behavior

1. Color Space and Color Science remain explicit user selections for video
   recording. Existing saved user selections remain acceptable.
2. Rec.709 output remains standards-correct BT.709 SDR metadata.
3. Rec.2020 output remains standards-correct BT.2020 metadata with transfer
   determined by the selected SDR, N-Log, NDR10+, or NIVUKX transport.
4. During an active video recording, a focus action must not unlock white
   balance or cause the camera request to compute a new color solution.
5. If video starts with auto WB, recording establishes a shot-stable AWB lock
   after the currently established solution is used.
6. If the user selected manual Kelvin/tint, that setting remains authoritative
   for processed video, including NDR10+ and NIVUKX Vision, when supported by
   the Camera2 request path.
7. RAW/NPV10 sensor masters remain sensor-truthful. Their selected color space
   and science describe monitoring/export intent; they do not claim the
   unrendered master was converted to Rec.709 or Rec.2020.

## Architecture

### Color Stability Policy

Add a pure policy in the camera layer that determines white-balance stability
behavior from:

- video/recording state
- RAW sensor master state
- requested HDR transport state
- manual Kelvin selection
- existing AE/AWB lock state
- whether the operation is a focus interaction

The policy returns:

- whether auto WB should be locked for the active shot
- whether manual WB may be applied under the selected transport
- whether focus may alter AWB lock state

This keeps request behavior testable without placing more conditional behavior
inside `CameraViewModel` or `CameraController`.

### Camera2 Request Application

`CameraController.applyAll()` must resolve white balance from the shared
stability policy. It must no longer force live auto AWB solely because the
active processed transport is NDR10+ or NIVUKX Vision. When manual Kelvin is
selected, the existing CCT or transform-matrix route stays active. When auto
WB is selected during recording, the resolved request locks AWB for the shot.

### Focus Interaction

`CameraViewModel.tapToFocus()` may release AE/WB only outside active video
recording. While recording, focus continues to update autofocus and exposure
metering as permitted, but it retains the recording color lock. Manual
focus/exposure lock behavior remains compatible with the same policy.

### Pipeline Coverage

- Processed preview and recording already share color-space and color-science
  parity fields. They retain the selected values while the sensor request is
  made stable.
- NPV10 preserves private master truth. Its metadata continues to carry the
  selected monitoring/export color intent, and converted MOV output uses the
  existing Rec.709 or Rec.2020 atom policy.

## Alternatives Rejected

**Lock all video preview continuously.** This avoids preview shifts, but makes
framing under changing illumination appear incorrectly fixed before a shot is
recorded.

**Require manual WB before every recording.** This is deterministic, but is
unnecessarily restrictive for auto-video use and breaks a practical workflow.

## Error Handling And Truthfulness

- If manual white balance is unavailable on a device, the app must use locked
  auto WB while recording rather than claiming manual WB was applied.
- If a requested wide-gamut or HDR transport is unsupported, existing fallback
  and metadata validation rules remain authoritative.
- No code may label an NPV10 sensor master as rendered Rec.709 or rendered
  Rec.2020 merely because its future export intent selected that color space.

## Test Contract

Add focused unit/contract tests before production changes:

1. A recording color-stability policy test proves auto WB locks during
   processed video recording and that focus cannot release the lock.
2. A policy test proves manual Kelvin is retained for NDR10+ and NIVUKX
   processed recording rather than forcibly replaced with live auto WB.
3. A source/wiring contract test proves `tapToFocus()` does not issue
   `setAeAwbLock(false)` during active video recording.
4. Existing color metadata tests must continue proving:
   - Rec.709 maps to BT.709 primaries/transfer/matrix.
   - Rec.2020 SDR maps to BT.2020 primaries/matrix with SDR transfer.
   - Rec.2020 PQ/HLG maps to BT.2020 with the selected transfer.
5. Existing NPV10 metadata and MOV conversion tests must continue passing to
   prove private master truth and export tagging are not regressed.

## Validation

Local validation:

- Run focused tests for the new policy, Camera2 color wiring, manual color
  selection, QuickTime color atoms, NPV10 conversion, and preview/recording
  parity.
- Run the complete debug unit-test suite.
- Run `:app:lintDebug` and `:app:assembleDebug`.

Device validation when an ADB target is attached:

- Record a fixed-color object while moving focus from near to far in Normal,
  Pro, N-Log, NDR10+, NIVUKX Vision, and NPV10 monitoring/export workflows.
- Collect Camera2 request logs proving stable AWB/manual Kelvin state while
  recording.
- Inspect exported files with `ffprobe` or MediaInfo for color primaries,
  transfer, matrix, resolution, and codec truth.
- Compare extracted frames before/after focus changes for avoidable chroma
  drift; report measured evidence rather than claiming perfect color.
