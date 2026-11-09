# NPV10 Uncompressed Cinema Pipeline Design

## Goal

Add an optional high-end uncompressed video master path for users who want recording quality above the realtime MediaCodec proxy path.

When enabled, video recording writes a private NPV10 master from the camera YUV analysis stream. The app must not publish that master to MediaStore or the gallery. A later in-app converter flow lists these private masters and lets the user manually convert one to MOV.

## User-Visible Contract

- Setting: `Uncompressed Cinema Pipeline`.
- Enabled behavior: record a private `.npv10` master and no automatic proxy output for the uncompressed path.
- Storage: app-private master directory, not gallery-visible MediaStore output.
- Conversion: masters are listed from the private store for manual MOV conversion; conversion is explicit, never automatic.

## Technical Shape

- NPV10 stores frame metadata plus 10-bit little-endian YUV420 planar frame payloads.
- Source frames come from `FramePipelineManager` by copying full `YUV_420_888` frames before the camera `Image` is closed.
- The writer runs on a single background I/O thread with bounded frame backlog to avoid blocking the camera callback.
- The recording UI observes a composite recording state so the NPV10 path still looks like a recording session.

## Non-Goals For This Pass

- This pass does not implement a true sensor RAW demosaic replacement for devices that do not expose RAW video frames in realtime.
- This pass does not publish private masters to the public album.
- This pass does not auto-convert NPV10 to MOV after recording.
