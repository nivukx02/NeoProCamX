# QuickTime MOV Backend Design

Date: 2026-04-25
Status: Approved direction
User choice: Option 1

## Goal

Add a real `.mov` recording backend to NeoProCamX for all video modes without silently falling back to MP4.

User-visible outcome:
- `PRO`, `LOG`, and `HDR` record to true QuickTime `.mov`
- `RAW` records to true QuickTime `.mov`
- Only `RAW` is labeled and treated as `Original Lossless`
- `PRO`, `LOG`, and `HDR` remain real MOV files, but are not described as sensor-original RAW

## Current State

Today the app records through Android `MediaMuxer` and writes MP4 only.

Current hard limits in the codebase:
- [RecordingManager.kt](D:/NeoProCamX_Final/NeoProCamX/app/src/main/java/com/neoproc/neoproccamx/recording/RecordingManager.kt) uses `MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4`
- [RecordingCodecCatalog.kt](D:/NeoProCamX_Final/NeoProCamX/app/src/main/java/com/neoproc/neoproccamx/recording/RecordingCodecCatalog.kt) hardcodes `.mp4` and `video/mp4`
- RAW video is currently written as frame payloads and packages, not as MOV

This means MOV cannot be added honestly as a filename change. It requires a different container writer.

## Requirements

Functional requirements:
- The app must output `.mov` for `PRO`, `RAW`, `LOG`, and `HDR`
- The MOV file must be a real QuickTime-compatible container, not an MP4 renamed to `.mov`
- Recording start/stop behavior must stay aligned with the current camera pipeline
- Existing preview, focus, aspect, HDR, LOG, and RAW feature behavior must not be downgraded

Truthfulness requirements:
- `RAW` may be shown as `Original Lossless MOV`
- `PRO`, `LOG`, and `HDR` may be shown as `QuickTime MOV`
- The UI must not claim `Original Lossless` for encoded non-RAW modes

Non-goals:
- No hidden MP4 fallback when the user selected MOV
- No “export later” workflow in place of direct recording
- No feature removal to make MOV easier

## Approaches Considered

### 1. In-project native QuickTime writer

Description:
- Add a dedicated native MOV writer inside the app’s existing NDK layer
- Feed encoded AVC/HEVC/AV1/AAC samples into the native writer for `PRO`, `LOG`, and `HDR`
- Feed RAW sample payloads and sensor metadata into the native writer for `RAW`

Pros:
- No fake container behavior
- No dependency on Android `MediaMuxer`
- Full control over RAW container semantics
- Avoids licensing ambiguity from bundling a third-party muxer stack
- Fits the project’s existing native/Vulkan footprint

Cons:
- Highest implementation effort
- Requires careful atom/index writing and recovery handling
- Needs strong test coverage for container correctness

Recommendation:
- Recommended and approved

### 2. FFmpeg/libavformat native MOV backend

Description:
- Integrate a native FFmpeg-based muxer for encoded MOV
- Add a custom RAW path on top for sensor-origin packaging

Pros:
- Faster path for encoded MOV support
- Mature MOV handling for AVC/HEVC/AV1

Cons:
- Still needs a custom RAW solution
- Adds large native dependency surface
- Introduces packaging and licensing review risk for this proprietary app

Decision:
- Keep as fallback reference path, not the approved primary design

### 3. Record as MP4/raw package, then convert to MOV

Description:
- Keep current direct recorder
- Convert outputs into MOV after recording completes

Pros:
- Smallest short-term code change

Cons:
- Not direct-to-MOV
- Breaks the user requirement for real MOV recording
- Adds more failure points and latency after stop

Decision:
- Rejected

## Approved Design

NeoProCamX will add an internal native QuickTime recording backend with two recording paths under one container abstraction:

1. Encoded MOV path for `PRO`, `LOG`, and `HDR`
2. RAW MOV path for `RAW`

Both paths produce `.mov`, but only RAW is marked as original-lossless.

## Architecture

### 1. Recording container abstraction

Add a container backend interface above the current muxing layer.

Planned shape:
- `RecordingContainerBackend`
- `AndroidMp4Backend` for the current path
- `QuickTimeMovBackend` for the new path

Responsibility split:
- `RecordingManager` remains responsible for encoder lifecycle, timing, and audio capture
- The backend becomes responsible for container creation, track setup, sample writing, finalization, and recovery

### 2. Encoded MOV path

Used by:
- `PRO`
- `LOG`
- `HDR`
- standard non-RAW video modes

Flow:
- Camera/renderer continues feeding `MediaCodec`
- `MediaCodec` produces encoded access units plus codec config buffers
- Kotlin passes codec configuration and samples into JNI
- Native QuickTime writer creates video/audio tracks and writes QuickTime atoms
- Final output path uses `.mov`

Codec handling:
- AVC writes an `avcC` sample description payload
- HEVC writes an `hvcC` sample description payload
- AV1 writes an `av1C` sample description payload
- AAC writes the matching audio sample description

Important rule:
- If MOV initialization fails, recording fails with a user-visible error instead of silently falling back to MP4

### 3. RAW MOV path

Used by:
- `RAW` video mode only

Design intent:
- Preserve sensor-origin payloads without debayering into a normal encoded video stream
- Keep the raw payload lossless from capture through file write

Container strategy:
- Reuse the existing RAW frame packaging logic as the source payload format
- Wrap each RAW frame payload as a sample in a dedicated QuickTime video track using a NeoProCamX vendor sample entry
- Add a timed metadata track carrying:
  - sensor size
  - CFA / Bayer pattern
  - black level
  - white level
  - timestamp
  - ISO
  - exposure
  - white balance metadata
  - lens/focus metadata
  - orientation

Truthfulness:
- This path is the only one allowed to use `Original Lossless MOV`
- Playback compatibility in generic gallery players may be limited; ingest compatibility is expected to rely on NeoProCamX tools first

### 4. Native writer module

Add a new native component beside the existing Vulkan bridge:
- `quicktime_writer.cpp`
- `quicktime_writer_jni.cpp`
- supporting headers/helpers for atom writing and recovery

Responsibilities:
- write `ftyp`, `moov`, `mdat`, and timing/sample tables
- support large files with `co64`
- reserve/finalize indexes safely on stop
- persist codec private data from `MediaCodec` config buffers
- write vendor RAW sample descriptions and metadata track entries

### 5. File identity and UI

Update the recording catalog and UI model so:
- all selected MOV modes use `.mov`
- MIME/container labels reflect QuickTime semantics
- RAW mode displays `Original Lossless MOV`
- non-RAW MOV modes display `MOV` or `QuickTime MOV`

The UI must not show `RAW`-style claims for encoded `PRO`, `LOG`, or `HDR`.

## Data Flow

### Encoded MOV

1. User selects non-RAW video mode with MOV enabled
2. `RecordingManager` prepares encoders as today
3. `QuickTimeMovBackend` opens native writer
4. Video and audio codec config are registered as tracks
5. Encoded samples are written as they drain from `MediaCodec`
6. Stop finalizes `moov` and closes the file

### RAW MOV

1. User selects RAW video mode with MOV enabled
2. RAW capture path produces sensor-origin frame payloads
3. `QuickTimeMovBackend` opens RAW track plus metadata track
4. Each frame is written as a RAW MOV sample with matching timed metadata
5. Stop finalizes indexes and writes closing atoms

## Error Handling

Hard rules:
- No silent fallback from MOV to MP4
- No silent fallback from RAW MOV to debayered encoded MOV
- If a mode is unsupported on the current device/backend state, recording is blocked with a clear error

Failure cases to handle:
- native writer init failure
- unsupported codec/profile to MOV mapping
- missing codec config buffers
- partial stop/finalization interruption
- file descriptor or MediaStore write failure
- RAW metadata mismatch

Recovery behavior:
- abort current recording safely
- release encoders/camera surfaces cleanly
- mark incomplete output as failed
- leave the camera app usable for the next attempt

## Testing

Unit tests:
- MOV selection policy chooses QuickTime backend when requested
- no MP4 fallback when MOV is selected
- codec config mapping for AVC/HEVC/AV1/AAC
- RAW mode gets the RAW MOV path only
- UI labeling distinguishes `Original Lossless MOV` from normal MOV

Native/integration tests:
- atom layout and index writing for short and long recordings
- encoded MOV opens in reference tools
- RAW MOV retains frame count, timestamps, and metadata continuity
- stop/failure recovery leaves no corrupted active session state

Device QA:
- start/stop recording in `PRO`, `LOG`, `HDR`, and `RAW`
- background/foreground transitions
- rotation during recording
- thermal and long-record sessions
- gallery/file-manager visibility for `.mov`

Acceptance criteria:
- every requested video mode writes `.mov`
- RAW path remains lossless and sensor-origin
- encoded MOV paths remain feature-complete and do not downgrade preview/focus/recording behavior

## Implementation Notes

Planned file areas:
- [RecordingManager.kt](D:/NeoProCamX_Final/NeoProCamX/app/src/main/java/com/neoproc/neoproccamx/recording/RecordingManager.kt)
- [RecordingCodecCatalog.kt](D:/NeoProCamX_Final/NeoProCamX/app/src/main/java/com/neoproc/neoproccamx/recording/RecordingCodecCatalog.kt)
- [RawVideoManager.kt](D:/NeoProCamX_Final/NeoProCamX/app/src/main/java/com/neoproc/neoproccamx/pipeline/RawVideoManager.kt)
- [RawOutputPackaging.kt](D:/NeoProCamX_Final/NeoProCamX/app/src/main/java/com/neoproc/neoproccamx/capture/RawOutputPackaging.kt)
- [app/src/main/cpp/CMakeLists.txt](D:/NeoProCamX_Final/NeoProCamX/app/src/main/cpp/CMakeLists.txt)
- new native QuickTime writer sources under `app/src/main/cpp`

## Risks

- RAW MOV interoperability will be narrower than encoded MOV interoperability
- Native atom writing mistakes can produce files that look valid but fail on ingest
- Very high bitrate/high resolution sessions may expose backpressure gaps earlier than MP4
- The current project is not in a git repository here, so the design doc cannot be committed from this workspace state

## Spec Self-Review

Checked:
- no placeholder sections
- architecture matches the approved direction
- scope is focused on the MOV backend only
- user-facing language makes the RAW vs non-RAW truth boundary explicit
