# NPV10 Uncompressed Cinema Pipeline Plan

## Steps

1. Add tests for NPV10 private storage policy, binary frame packing, master listing, and settings surface.
2. Implement NPV10 policy, frame packer, reader, writer, private master manager, and manual MOV converter backend.
3. Add a raw-YUV frame sink to `FramePipelineManager` for recording-only master capture.
4. Add persisted `Uncompressed Cinema Pipeline` preference and settings controller binding.
5. Wire `CameraViewModel` to start/stop the private NPV10 path without preparing a MediaCodec proxy when the setting is enabled.
6. Verify focused tests, unit tests, and debug assembly.

## Acceptance Checks

- `.npv10` masters are created under the app private master directory.
- New NPV10 code contains no MediaStore publishing path.
- Settings expose the uncompressed pipeline toggle and persist it.
- `FramePipelineManager` can deliver full copied YUV frames to the master writer.
- NPV10 recording state is visible through the same `gpuRecordState`/`recordState` surfaces as normal recording.
- Selected NPV10 masters can be converted manually into a MOV wrapper without auto-conversion after recording.
