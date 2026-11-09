# Camera2 Session Graph Parity Design

Date: 2026-05-20

## Problem

The app has a high-level video graph contract and a recording parity policy, but Camera2 session behavior can still diverge at recording start. The visible preview may be stable before recording, then change quality, noise, flicker, or color when the encoder surface, analysis size, readout override, or request targets are changed.

The goal of Phase 3 is to make Camera2 behave like a real video camera session:

- keep the visible preview stream stable during recording start and stop
- keep the preview surface capped independently from recording resolution
- send the selected internal recording resolution and FPS to the encoder path
- separate processed SDR, PRO, HDR, and LOG from RAW_SENSOR recording
- rebuild Camera2 sessions only when the stream graph truly changes
- make the decision path testable before wiring it into CameraViewModel and CameraController

This phase does not promise impossible hardware behavior. If the HAL changes ISP tuning when a record surface is present, the code will minimize avoidable session/request changes and log the remaining hardware boundary.

## Current Root Cause

The current implementation already has useful policy extraction:

- `VideoPipelineGraphController` defines the user-level video delivery contract.
- `RecordingPipelineParityPolicy` chooses the effective recording feed path.
- `CinemaStreamGraphPolicy` resolves the Camera2 stream inventory.
- `Camera2PipelineRequestPolicy` resolves request template, targets, antibanding, capture intent, and cleanup profile.

The remaining mismatch is that CameraViewModel still changes several lower-level Camera2 inputs at recording start:

- `setRecordingReadoutOverrideSize`
- `setRecordingEncoderTargetSize`
- `setAnalysisTargetSize`
- `setRecordSurface`
- `setPreviewContinuityRecordSurfaceEnabled`
- `refreshPreviewPipelineIfNeeded`

Those calls are valid individually, but there is no single authority that says which of them must change for a given video mode. That allows processed video preview and processed recording to become slightly different Camera2 graphs.

## Recommended Architecture

Add one pure policy: `Camera2SessionGraphParityPolicy`.

The policy converts the already-resolved video graph and recording parity plan into a Camera2 session graph plan. It will sit between CameraViewModel's user-facing video contract and CameraController's Camera2 surface/session setters.

Inputs:

- `VideoPipelineContract`
- `RecordingPipelineParityPlan`
- preview size selected for the live display
- encoder size selected for recording
- requested FPS
- RAW support
- Vulkan runtime support
- recording state: idle, prearmed, starting, recording, stopping

Outputs:

- preview target size for visible live display
- encoder target size for internal recording
- analysis target size
- readout override size
- whether the record surface must be in the session before start
- whether the repeating request may add only the encoder target
- whether a full Camera2 session rebuild is required
- whether RAW_SENSOR owns the readout
- session signature for refresh decisions
- diagnostic reason string for logs and tests

## Mode Rules

Processed SDR, PRO, HDR, and LOG:

- Use the processed preview-plus-encoder graph.
- Keep the visible preview size independently capped to the live display target, normally 1080p.
- Keep recording resolution independent through `recordingEncoderTargetSize`.
- Do not use RAW_SENSOR or YUV readout sidecars for encoded delivery in Phase 3. Any future sidecar path must add a new policy mode and tests instead of silently changing the processed recording graph.
- Prefer pre-armed persistent encoder surface attachment so recording start does not force a visible preview rebind.
- If the encoder surface is already attached, recording start updates only the repeating request target.

HDR:

- Uses processed HAL ISP path.
- Does not bypass ISP.
- Rebuilds the session only if OutputConfiguration dynamic range profile changes.
- Shares the same preview/encoder graph continuity rules as processed SDR and PRO.

LOG:

- Uses processed SDR transport for Phase 3 recording parity.
- Does not record as HDR by default.
- Shares the same stream graph as processed video.

RAW:

- Uses a separate RAW_SENSOR graph.
- Requires RAW reader/session rebuild when entering or leaving RAW.
- Does not pretend to share processed video parity.
- May use Camera2 direct record surface if needed for existing raw recording management.
- Keeps preview monitoring separate from original RAW capture.

## Session Refresh Rules

The new policy owns the session-refresh decision:

- No rebuild for recording start if the prearmed encoder surface is already in the session and the session signature is unchanged.
- Rebuild for entering or leaving RAW.
- Rebuild for HDR dynamic range OutputConfiguration profile changes.
- Rebuild when the encoder surface must be attached and was not prearmed.
- Do not rebuild for display-only preview state changes.
- Do not rebuild because the selected recording resolution differs from the preview resolution.

CameraViewModel should use the policy plan before calling CameraController setters. CameraController remains responsible for applying Camera2 details, but it should receive stable, already-resolved intent.

## Preview And Recording Size Rules

The visible preview surface is a monitoring stream, not the recording resolution. Phase 3 keeps this explicit:

- live display preview is capped by `CameraPreviewSessionSizingPolicy`
- encoder size follows `ResolvedRecordingSelection`
- analysis size follows the session parity plan and should not be promoted to full recording size unless required by RAW
- 8K/4K recording must not force an 8K/4K visible preview surface

This directly targets the bug where preview is good before recording but becomes noisy or unstable after recording starts.

## Error Handling And Diagnostics

The policy will include stable signatures and reason strings. CameraViewModel logs the plan during:

- mode switch
- prearm
- recording start
- recording stop
- fallback from prearmed session to rebuild

CameraController logs the final Camera2 request and stream graph as it does now. The new policy gives those logs a higher-level expected session identity so mismatches are easier to find.

## Testing

Add unit tests for `Camera2SessionGraphParityPolicy`:

- processed video keeps preview graph stable when moving from prearmed to recording
- HDR processed video only requests session rebuild for dynamic range profile change
- LOG processed video does not request HDR recording transport
- RAW requires RAW_SENSOR session rebuild and disables processed parity
- 1080p preview remains independent from 4K/8K encoder target
- encoder surface already attached means repeating-request update only
- encoder surface not attached means one controlled session rebuild

Update source contract tests only where they enforce the new policy boundary.

Run:

- focused new policy tests
- existing Camera2 stream/session policy tests
- existing recording parity tests
- `:app:testDebugUnitTest`
- `:app:assembleDebug`
- `:app:lintDebug`

## Out Of Scope

This phase does not rewrite color science, HDR tone mapping, denoise shaders, encoder bitrate, or RAW file format. Those depend on later image-processing phases. Phase 3 focuses on the Camera2 session and stream graph root cause that can make preview and recording use different paths.

## Acceptance Criteria

Phase 3 is complete when:

- there is one testable policy controlling Camera2 session graph parity
- CameraViewModel uses that policy before recording prearm/start/stop
- processed modes keep preview and recording on the same intended Camera2 graph
- RAW is explicitly separated from processed video
- preview size and encoder size are independent by contract
- focused and full verification commands pass
- logs expose the expected session graph identity during recording start
