# MOV Recording Pipeline Design

## Goal

Upgrade the recording pipeline into a premium MOV-only MediaCodec/MediaStore path while preserving truthful output. The app must keep original SDR, HDR, LOG, and RAW intent where the hardware supports it, publish recordings reliably through MediaStore, and avoid fake quality claims when Android hardware falls back.

## Current Context

`RecordingManager` already owns the live recording flow:

- MediaCodec video encoder setup with surface input.
- Optional AAC audio capture and encoding.
- A custom `QuickTimeMovBackend` container path.
- A bounded muxer sample queue.
- MediaStore pending insert and final publish.
- HDR static metadata, 10-bit capability checks, bitrate policy, all-intra HEVC probing, orientation hint, and telemetry.

The main issue is not missing functionality; it is responsibility concentration. `RecordingManager` currently handles encoder format construction, MOV backend lifecycle, muxer sample writing, output target creation, MediaStore publishing, cleanup, and stop finalization in one large class. The upgrade should split these responsibilities without changing the public recording flow or app features.

## Non-Goals

- Do not add MP4 as a selectable output.
- Do not replace the existing QuickTime MOV backend with a new native-first system.
- Do not fake 10-bit, RAW, HDR, all-intra, or cinema labels when the hardware does not deliver them.
- Do not change preview aspect, UI layout, camera modes, or recording feature behavior.

## Architecture

`RecordingManager` remains the facade used by `CameraViewModel`. It continues to expose `prepare()`, `start()`, `stop()`, `encoderSurface`, state flows, duration, and output telemetry.

Internally, the recording path is separated into focused units:

- `RecordingMovOutputContractPolicy`
  Resolves the MOV-only output contract: file extension, MIME type, user-visible container label, route, metadata labels, and description inputs.

- `RecordingMediaStoreOutputStore`
  Owns Android output target lifecycle: create pending MediaStore row, open `ParcelFileDescriptor`, publish with `IS_PENDING=0`, delete failed partial entries, and handle legacy public directory fallback.

- `RecordingMovSessionController`
  Owns `QuickTimeMovBackend` lifecycle: open, orientation hint, video/audio track creation, sample writing, close, and abort.

- `RecordingEncoderFormatPolicy`
  Builds the video `MediaFormat` from the resolved recording plan: bitrate, frame rate, color format, operating rate, priority, profile, bitrate mode, HDR color keys, HDR static metadata, and all-intra HEVC application.

- `RecordingStopFinalizationPolicy`
  Provides deterministic stop budgets for track discovery, codec drain, muxer queue drain, writer completion, and final publish. This keeps stop behavior fast without closing a partially written container too early.

## Data Flow

1. `CameraViewModel.startRecording()` selects resolution, mode, codec, HDR, LOG, RAW, bitrate, and orientation as it does today.
2. `RecordingManager.prepare()` resolves the MOV-only output contract and encoder format.
3. The MediaCodec video encoder is configured with a surface input.
4. `RecordingMediaStoreOutputStore` creates a pending `.mov` item:
   - Android 10+: `MediaStore.Video` with `video/quicktime`, `RELATIVE_PATH=DCIM/CamX`, `IS_PENDING=1`.
   - Older Android: public `DCIM/CamX` file plus media scan after close.
5. `RecordingMovSessionController` opens the QuickTime MOV backend using the writable path.
6. `RecordingManager.start()` starts video and optional audio encoders, starts the muxer writer, and records duration.
7. Encoder output format changes add video/audio tracks through the MOV session controller.
8. Encoded samples enter `RecordingMuxerSampleQueue`, then the writer serializes them into the MOV backend.
9. `RecordingManager.stop()` signals EOS, stops audio, drains codecs, waits for queued samples, closes the MOV backend, then asks the output store to publish the item.
10. On failure, the output store deletes the pending MediaStore row or legacy partial file.

## MOV-Only Output Contract

Every normal recording output must use:

- Extension: `.mov`
- MIME type: `video/quicktime`
- MediaStore collection: `MediaStore.Video`
- Relative path: `DCIM/CamX`
- Container backend: `QuickTimeMovBackend`

Labels must remain truthful:

- RAW mode: "Original Quality-Preserving MOV" only when RAW delivery is actually active.
- HDR/LOG: "QuickTime MOV" with metadata describing HDR/LOG details.
- All-intra: report actual hardware-engaged all-intra, not only requested all-intra.
- 10-bit: report actual 10-bit output unless the pipeline is in simulated fallback.

## MediaCodec Quality Strategy

The encoder policy should apply the strongest valid configuration accepted by the device:

- Surface input color format.
- Effective bitrate from `RecordingVideoEncoderPlanResolver`.
- Codec profile and operating rate when available.
- Color standard, transfer, and range for HDR/LOG/SDR.
- HDR static metadata only when policy says it matches the color transfer.
- All-intra HEVC only when `AllIntraHevcPolicy.probe()` and `applyToFormat()` both succeed.
- Runtime bitrate hinting and sync frame request remain available during recording.

Unsupported settings must degrade honestly. For example, if Main10 is unavailable, record fallback status in output metadata and UI telemetry instead of pretending the file is 10-bit.

## MediaStore Reliability

The output store must be the only owner of publish and cleanup rules:

- Insert with `IS_PENDING=1`.
- Open a writable descriptor in `rw` mode.
- Close the descriptor before publish.
- Publish with `IS_PENDING=0`, updated `DATE_MODIFIED`, and final description.
- Delete pending rows on prepare/start/stop failure.
- Delete legacy partial files on failure.
- Never leave a failed zero-byte item visible in the library.

## Stop And Finalization

Stop must prioritize valid files over premature speed:

- Signal video EOS after the upstream encoder surface is detached.
- Stop and drain audio before final backend close.
- Wait for video format and muxer start with a bounded budget.
- Wait for video/audio drain completion with a bounded budget.
- Wait for pending muxer samples to flush.
- Wait for the writer job to finish before closing the MOV backend.
- Close the backend before publishing MediaStore.
- Abort backend and cleanup output target on error.

The budgets should be policy-driven and unit-tested so future changes do not reintroduce hangs or partial-file saves.

## Testing Strategy

Unit tests:

- MOV-only output contract always returns `.mov` and `video/quicktime`.
- RAW, LOG, HDR, and SDR produce truthful labels and descriptions.
- MediaStore lifecycle policy creates pending, publish, and cleanup commands correctly.
- Stop finalization policy chooses close, wait, or cleanup for representative states.
- Encoder format policy applies HDR metadata only for valid HDR transfers.
- All-intra actual status is preserved in metadata.
- Existing muxer queue, output routing, container selection, bitrate, HDR, and recording manager policy tests continue to pass.

Device smoke test:

- Install debug APK.
- Launch app.
- Start a short recording in VIDEO/PRO.
- Stop recording.
- Verify no crash, no stuck recording state, and a `.mov` MediaStore item appears under `DCIM/CamX`.

## Hardware Truth

Software can improve recording reliability, metadata correctness, bitrate policy, and save lifecycle. It cannot force unsupported hardware encoders to produce real 10-bit, RAW video, HDR dynamic metadata, all-intra, or higher resolution than the encoder/camera path accepts. The implementation must surface fallback state honestly and keep the saved MOV internally consistent.

## Acceptance Criteria

- Recording output remains MOV-only.
- MediaStore publish/cleanup is handled by a dedicated output store.
- MOV backend lifecycle is handled by a dedicated session controller.
- Encoder `MediaFormat` creation is policy-driven and unit-tested.
- Stop finalization is policy-driven and unit-tested.
- Existing recording behavior remains intact.
- Full unit tests, debug build, lint, install, launch, and recording smoke test are run before completion is claimed.
