# Video Pipeline Graph Controller Design

## Goal

Create one authoritative video pipeline contract for NeoProCamX so preview, recording, Camera2, MediaCodec, Vulkan, HDR, LOG, RAW, denoise, sharpness, FPS, and UI status all use the same resolved mode state.

This is Phase 1 of the high-end quality stabilization work. It does not remove existing features. It creates a central decision layer that existing features can consume one by one.

## Current Problem

The project has strong feature coverage, but critical video decisions are still spread across large orchestration files, especially `CameraViewModel`, `CameraController`, and `RecordingManager`.

The same capture session can currently be influenced by separate decisions for:

- app mode and cinema mode
- HDR/LOG/RAW state
- preview size
- encoder size
- Camera2 request template and capture intent
- analysis reader size
- RAW/YUV reader inclusion
- color transfer and color standard
- denoise and sharpness tuning
- thermal and FPS policy
- preview and recording render quality

When these decisions drift, user-visible bugs appear:

- preview looks clean but recording looks noisy
- preview changes when recording starts
- LOG can inherit HDR transport or metadata
- normal video can save as HDR
- Camera2 can target a surface not present in the session
- resolution and FPS labels can disagree with the actual output
- RAW/YUV/JPEG readers can be enabled in a graph that does not match the selected mode

## Design Principle

There must be exactly one resolved `VideoPipelineContract` for the active video state.

Every downstream system should consume that contract instead of re-resolving its own interpretation of mode, HDR, LOG, RAW, resolution, FPS, color space, or quality level.

## Scope

Phase 1 introduces the contract and resolver, then wires only the safest high-impact decisions:

- preview/render quality hint
- recording-grade preview parity
- HDR/LOG/RAW delivery identity
- encoder color identity
- Camera2 parity identity
- QA/debug signature

Phase 1 does not rewrite the whole camera pipeline. It creates the source of truth and begins migration without breaking existing mode behavior.

## Non-Goals

- Do not remove any mode.
- Do not change the current UI layout.
- Do not claim unsupported hardware features as real.
- Do not fake RAW, HDR, LOG, 10-bit, or 12-bit capability.
- Do not rewrite Vulkan shaders in this phase.
- Do not rewrite MediaCodec muxing in this phase.

## New Core Types

### `VideoPipelineGraphController`

Package: `com.neoproc.neoproccamx.ui`

Responsibility:

- Build a complete `VideoPipelineContract` from current app state.
- Keep mode, preview, recording, encoder, color, and QA decisions in one place.
- Provide a stable graph signature for logging and refresh decisions.

### `VideoPipelineContract`

Fields:

- `mode`: active `AppMode`
- `cinemaMode`: active `CinemaVideoMode`
- `deliveryKind`: `SDR`, `HDR`, `LOG`, or `RAW`
- `requestedHdrMode`: requested HDR mode string
- `effectiveHdrMode`: encoder/transport HDR mode string
- `logEnabled`: true only for LOG delivery
- `rawEnabled`: true only for RAW delivery
- `tenBitRequested`: true when the selected delivery requires 10-bit recording
- `previewWidth` and `previewHeight`: visible preview target
- `encoderWidth` and `encoderHeight`: actual recording target
- `fps`: resolved camera and encoder FPS target
- `colorStandard`: MediaCodec color standard identity
- `colorTransfer`: MediaCodec color transfer identity
- `colorRange`: MediaCodec color range identity
- `cameraRequestFamily`: preview/recording request identity
- `recordingGradePreview`: true when video preview should match recording-grade processing
- `denoiseProfile`: selected denoise tuning identity
- `sharpnessProfile`: selected detail tuning identity
- `graphSignature`: stable string used for logs, testing, and session refresh decisions

### `VideoDeliveryKind`

Values:

- `SDR`
- `HDR`
- `LOG`
- `RAW`

Rules:

- `LOG` must not be `HDR`.
- `RAW` must not be treated as display-referred HDR.
- `HDR` requires HDR transport metadata only when the device and encoder support it.
- `SDR` must explicitly resolve to SDR metadata, even when the encoder supports 10-bit.

## Resolution Rules

### Normal Video

- Delivery kind: `SDR`
- Preview: display-referred SDR
- Recording: SDR color metadata
- Recording-grade preview: enabled in video mode
- LOG flags: false
- RAW flags: false
- HDR transport: off

### Pro Video

- Delivery kind: `SDR` unless HDR or LOG is explicitly selected
- Manual controls remain active
- Recording-grade preview: enabled
- Encoder metadata must match delivery kind

### HDR Video

- Delivery kind: `HDR`
- Preview: HDR preview only when the selected HDR mode and display path support it
- Recording: HDR metadata only for actual HDR delivery
- LOG flags: false while HDR delivery is active
- Tone and color must stay in the HDR path, not the LOG path

### LOG Video

- Delivery kind: `LOG`
- Preview: LOG monitoring, not HDR
- Recording: LOG profile identity and non-HDR transfer unless a specific supported LOG-HDR transport is explicitly added later
- HDR flags: false for encoder metadata
- LUTs are monitoring transforms only unless export burn-in is explicitly selected

### RAW Video

- Delivery kind: `RAW`
- Preview: RAW monitoring or debayered monitoring
- Recording: truthful RAW only when `RAW_SENSOR` and storage bandwidth support the selected format
- Fallback: YUV/encoder fallback must be disclosed as fallback, not described as true RAW
- HDR/LOG flags: false for RAW file identity

## Camera2 Session Interaction

The contract does not directly open or close Camera2 sessions. It provides identity and target decisions that session code can consume.

Camera2 session refresh decisions should compare the previous and current `graphSignature`.

If the graph signature changes in fields that affect output surfaces, request template, dynamic range profile, RAW/YUV inclusion, encoder size, or analysis size, a session refresh is required.

If the graph signature only changes monitoring/display-only values, a session refresh is not required.

## Preview And Recording Parity

Preview and recording may use different surfaces and sizes, but they must use the same contract identity.

Allowed difference:

- preview display size can stay 1080p for performance
- encoder can record selected resolution internally

Not allowed:

- preview SDR while normal video records HDR
- LOG preview while encoder saves HDR
- recording switches Camera2 template without contract change
- denoise/sharpness profile changes only after pressing record
- encoder color metadata differs from delivery kind

## Quality Profiles

The contract should expose tuning identities, not hard-code shader math.

Initial profiles:

- `ENCODED_VIDEO_STRICT`: default for all non-RAW video preview and recording
- `HDR_CLEAN`: HDR-preserving denoise and highlight-safe sharpening
- `LOG_DETAIL`: low-contrast detail preserving profile for LOG capture
- `RAW_TRUTHFUL`: minimal display monitoring, no fake denoise claim on RAW data

Later phases can map these profiles into Vulkan shader constants and MediaCodec knobs.

## Testing Strategy

Phase 1 requires pure unit tests before production wiring.

Tests:

- normal video resolves SDR delivery and SDR encoder metadata
- HDR resolves HDR delivery and disables LOG identity
- LOG resolves LOG delivery and does not use HDR delivery identity
- RAW resolves RAW delivery and disables HDR/LOG delivery identities
- video preview uses recording-grade render quality before recording starts
- graph signature changes when mode, HDR, LOG, RAW, resolution, FPS, or encoder color identity changes
- graph signature stays stable for display-only monitoring changes

Device QA after implementation:

- launch app
- start normal video recording
- check logs for one contract signature before and during recording
- check no `startPreview failed`
- check no `unconfigured Input/Output Surface`
- check saved video metadata matches expected delivery kind

## Migration Plan

Phase 1 wiring:

1. Add the contract resolver and tests.
2. Feed `VideoRenderProfileQualityHintPolicy` from the contract.
3. Feed recording color identity from the contract.
4. Add contract signature logging to recording start and Camera2 pipeline logs.
5. Keep existing session refresh code, but compare contract signatures where already available.

Later phases:

1. Move Camera2 target size decisions into the contract.
2. Move RAW/YUV/JPEG reader inclusion into the contract.
3. Move HDR/LOG color metadata into the contract.
4. Move denoise and sharpness profiles into Vulkan constants.
5. Split `CameraViewModel` into smaller controllers around the contract.

## Success Criteria

- Full unit test suite passes.
- Android lint passes.
- Debug APK builds.
- Existing video modes remain available.
- Normal video, HDR, LOG, and RAW have distinct contract signatures.
- LOG contract cannot resolve as HDR.
- Normal video contract cannot save as HDR.
- RAW contract cannot silently claim fake RAW when hardware fallback is used.
- Preview and recording can be proven from logs to use the same graph identity.

## Risks

The main risk is changing too much at once. This phase intentionally starts with a pure resolver and a small wiring surface. Larger migrations should happen only after the contract is covered by tests and visible in QA logs.

Another risk is device-specific HAL behavior. The contract must represent requested and effective capability separately so the UI and logs can show truthful fallback instead of pretending every device can deliver every mode.
