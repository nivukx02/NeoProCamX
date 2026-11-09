# Recording Pipeline Parity Design

## Goal

Phase 2 makes recording use the same effective video graph identity as preview. The target failure is a good idle preview changing into noisy, grainy, HDR-tagged, or otherwise different output after recording starts.

## Root Cause

Phase 1 introduced `VideoPipelineGraphController`, but several recording decisions still read raw/log/HDR UI state directly. Those direct reads can disagree with the effective graph after mode normalization. Examples include stale RAW toggles outside RAW mode, LOG disabled by HDR, HDR disabled by LOG/RAW, and prearmed encoder signatures built from requested fields instead of effective delivery fields.

## Design

Add a small `RecordingPipelineParityPolicy` in the UI layer. It accepts the Phase 1 `VideoPipelineContract` plus hardware/runtime capability flags and returns one recording parity plan:

- effective recording delivery kind
- Camera2 feed path
- session-plan raw/log/HDR/10-bit inputs
- encoder HDR mode, color space, LOG flag, and RAW flag
- stable parity signature for logging and prearm validation

Processed SDR/HDR/LOG modes use the preview-continuity Camera2 record-surface path. RAW uses the direct Camera2 record-surface path only when RAW is actually supported; unsupported RAW collapses to processed SDR recording parity instead of pretending a RAW path exists.

## Integration

`CameraViewModel` builds the parity plan from `activeVideoPipelineContract()` and uses it for:

- `RecordingCapabilityPolicy.resolveSessionPlan(...)`
- recording selection inputs
- bitrate recommendation inputs
- prearmed encoder surface signatures
- start-recording feed-path selection
- recording QA logs

This does not rewrite the Camera2 controller or encoder. It removes split-brain decisions above those components so preview and recording are driven by the same effective contract.

## Testing

Unit tests cover SDR, HDR, LOG, RAW, and unsupported RAW parity. Focused integration tests cover the graph policy and existing feed-path policy, then full unit/build/lint verification proves the broader app still compiles cleanly.
