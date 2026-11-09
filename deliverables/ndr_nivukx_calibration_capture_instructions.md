# NDR10+ / NIVUKX Vision Calibration Capture

Display `deliverables/ndr_nivukx_tone_color_reference_v2.png` full-screen on a second display.
The same PNG is also packaged at `app/src/main/assets/calibration/ndr_nivukx_calibration_target.png`.

Required display setup:
- Disable adaptive brightness.
- Disable eye comfort/night light.
- Disable vivid/display enhancement modes.
- Use fixed brightness. Start at 70 percent; do not change it during the captures.
- Clean the display and keep it perpendicular to the camera.

Capture order:
1. Record 5 seconds in normal SDR/reference mode.
2. Record 5 seconds in NDR10+.
3. Record 5 seconds in NIVUKX Vision.

Framing:
- Fill the camera preview with the full chart.
- Keep all chart edges visible.
- Tap focus on the `TAP FOCUS` target before each recording.
- Lock exposure if the app allows it after the chart is stable.
- Do not crop, zoom, or rotate between modes.

Requested filenames:
- `NORMAL_REFERENCE_CHART`
- `NDR10_REFERENCE_CHART`
- `NIVUKX_REFERENCE_CHART`

Send back the three recorded clips or still frames. The reference files are
`deliverables/ndr_nivukx_tone_color_reference_v2.json` and
`app/src/main/assets/calibration/ndr_nivukx_calibration_reference.json`.
