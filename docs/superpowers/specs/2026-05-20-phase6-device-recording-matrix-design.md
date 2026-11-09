# Phase 6 Device Recording Matrix Design

## Goal

Phase 6 turns the Phase 5 recording matrix into a repeatable physical-device QA flow. It must exercise each SDR, HDR, LOG, and RAW row through the existing debug `record_clip` automation, capture per-row logs, query saved MediaStore metadata where Android exposes it, and report whether the recording matched the expected delivery identity.

## Scope

In scope:

- Render one deterministic adb broadcast command for every `RecordingDeviceMatrixPolicy` row.
- Allow a Phase 6 validation namespace so generated clips are easy to find in MediaStore.
- Validate saved MOV metadata against each row's expected MIME type, extension, resolution, frame rate, color family, and delivery identity.
- Classify known vendor/platform log noise separately from app-blocking failures.
- Add a PowerShell runner that installs/launches the app, records matrix rows, saves per-row logcat/crash artifacts, queries MediaStore, and writes a CSV summary.

Out of scope:

- Forcing hardware to support unsupported frame rates, HDR transfers, or RAW paths.
- Treating platform codec/vendor warnings as app bugs when the clip records and metadata is internally consistent.
- Changing the image-processing feature set.

## Architecture

Phase 6 keeps the app-side source of truth in Kotlin:

- `RecordingDeviceMatrixPolicy` owns the matrix rows.
- `RecordingDeviceMatrixCommandPolicy` converts a row into a typed adb broadcast command.
- `RecordingDeviceMatrixMetadataPolicy` validates observed clip metadata and expected log evidence.
- `RecordingDeviceMatrixLogClassifier` separates blocking failures from known platform noise.

The device runner lives under `tools/` and mirrors the Kotlin matrix as executable adb commands. The runner is deliberately plain PowerShell so it can run on the current Windows workstation without additional dependencies.

## Validation Rules

All rows must:

- Use `record_clip`.
- Include preset, aspect, resolution, fps, video mode, HDR mode, color space, LOG flag, RAW flag, and validation label.
- Save as `video/quicktime` with `.mov` extension.
- Produce a `Recording saved` log or be reported as failed.

Processed SDR/HDR/LOG rows must keep `VIDEO_PREVIEW_RECORDING_PARITY`. RAW rows must be allowed to use the direct RAW path.

SDR rows must not resolve as HDR. LOG rows must keep HDR off. HDR rows must resolve as HDR with the requested HLG or PQ family.

Resolution and frame-rate mismatches are warnings when the logs show a hardware/camera fallback. Delivery-kind mismatches, wrong container MIME/extension, missing saved clip, crashes, or recording errors are failures.

## Device Output

The runner writes artifacts to `qa-artifacts/phase6-recording-matrix/`:

- `phase6-recording-matrix-summary.csv`
- `phase6-recording-matrix-summary.json`
- `<row-id>.logcat.txt`
- `<row-id>.crash.txt`
- `<row-id>.mediastore.txt`

The final report must distinguish verified rows from rows skipped or failed due to hardware limitations.
