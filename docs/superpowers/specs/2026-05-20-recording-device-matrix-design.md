# Phase 5 Recording Device Matrix Design

## Goal

Phase 5 adds a production-oriented recording QA matrix for the Android camera app. The matrix makes each supported video delivery path explicit and testable: normal SDR video, pro SDR video, HDR HLG, HDR PQ/HDR10, LOG, and RAW sensor recording.

The intent is to prevent regressions where the preview looks correct but the recording session silently switches to a different delivery identity, especially the recurring failure class where SDR or LOG clips are saved with HDR routing.

## Scope

This phase is a contract and device-execution layer around the existing recording pipeline. It does not add a new image-processing algorithm. It verifies and documents how the existing graph should be exercised on a device.

In scope:

- A stable mode matrix covering SDR, HDR, LOG, and RAW recording families.
- Expected delivery kind, bit depth, container family, color metadata family, and preview parity per row.
- Generated debug command extras for the existing `record_clip` automation path.
- Unit tests that prove each matrix row resolves through the same graph and parity policies used by the app.
- Device smoke deployment and log capture after the code builds.

Out of scope:

- Claiming that every physical camera sensor can produce 10-bit SDR or 12-bit RAW if the HAL does not expose it.
- Replacing Camera2, MediaCodec, or MediaStore.
- Long-duration thermal validation. That belongs in a later device-performance phase.

## Architecture

The new policy lives in the UI contract layer because it coordinates UI mode selection, video graph identity, recording parity, and debug automation. It depends on existing policies instead of duplicating camera behavior:

- `VideoPipelineGraphController` resolves SDR/HDR/LOG/RAW identity and color metadata.
- `RecordingPipelineParityPolicy` resolves whether preview and recording share the processed path or RAW direct path.
- `RecordingContainerSelectionPolicy` enforces MOV output.
- `RawVideoContractPolicy` supplies the RAW bit-depth expectation.
- `DebugCommandParser` remains the runtime command reader.

The matrix policy returns immutable rows. Each row contains both human-readable expectations and concrete debug extras that can be sent through the existing debug receiver.

## Matrix Rows

The first version covers these rows:

- Normal SDR video: 10-bit requested SDR transport, Rec.709 metadata, preview/recording parity.
- Pro SDR video: 10-bit requested SDR transport, Rec.709 metadata, preview/recording parity.
- HDR HLG: 10-bit HDR transport, Rec.2020/HLG metadata, preview/recording parity.
- HDR10 PQ: 10-bit HDR transport, Rec.2020/PQ metadata, preview/recording parity.
- LOG Sony: 10-bit LOG transport, HDR disabled, Rec.2020 LOG color family, preview/recording parity.
- LOG RED: 10-bit LOG transport, HDR disabled, Rec.2020 LOG color family, preview/recording parity.
- RAW 12-bit: RAW_SENSOR delivery, 12-bit RAW expectation, direct RAW recording path, MOV output contract.

## Correctness Rules

Every processed recording row must keep `VIDEO_PREVIEW_RECORDING_PARITY` so recording cannot switch to a lower-quality or differently graded path. RAW is the only row allowed to use the RAW direct path.

LOG rows must resolve with HDR off. HDR rows must resolve as HDR, use Rec.2020 metadata, and request 10-bit transport. SDR rows must remain SDR and must not request HDR metadata.

Every row must generate a `record_clip` debug command with a unique validation label. The command must include mode, preset, duration, resolution, HDR mode, color space, LOG flag, RAW flag, and RAW compression where applicable.

## Testing

Unit tests verify:

- Coverage across SDR, HDR, LOG, and RAW families.
- MOV output for all rows.
- 10-bit expectations for processed video rows and 12-bit expectation for RAW.
- LOG is never routed as HDR.
- HDR rows carry HDR metadata and expected transfer families.
- Generated debug extras parse back through `DebugCommandParser`.
- Graph and recording parity policies agree with the matrix expectations.

Device validation installs the debug APK, launches the app, runs at least a short debug recording row when device state permits, and captures logcat evidence for crashes and app-owned warnings/errors.
