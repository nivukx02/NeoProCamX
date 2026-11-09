# Phase 5 Recording Device Matrix Plan

## Steps

1. Inspect the existing debug recording command path and graph/parity policies.
2. Write failing unit tests for the recording device matrix contract.
3. Add a `RecordingDeviceMatrixPolicy` that resolves matrix rows through existing graph, parity, MOV, and RAW policies.
4. Verify every row can generate debug extras that `DebugCommandParser` accepts.
5. Run focused tests, then the full debug unit test and assemble tasks.
6. Run Android lint.
7. Install and launch the debug APK on the connected phone.
8. Capture app logs and, where possible, run a short debug clip command for a representative row.

## Acceptance Criteria

- Matrix tests pass and prove SDR, HDR, LOG, and RAW coverage.
- LOG rows keep HDR disabled.
- SDR rows do not save as HDR by contract.
- HDR rows retain HDR metadata by contract.
- RAW row expects the existing 12-bit RAW contract.
- All rows select MOV output.
- The app builds with no lint errors.
- The app installs and launches on the connected device without a crash in captured logs.
