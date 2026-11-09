# Realtime Recording Quality Upgrade Design

Date: 2026-05-18

## Goal

Upgrade NeoProCamX recording output quality in every video mode and resolution by improving the real-time recording pipeline. The first priority is to remove the quality mismatch where live preview looks clean, but the preview during recording and the saved video look noisier, grainier, softer, or lower quality.

This pass focuses on real camera and encoder behavior only. It must not add fake pixel duplication, fake HDR, fake LOG, fake RAW, or fake noise claims. Hardware limits remain truthful: a phone sensor cannot produce unlimited dynamic range or literal zero noise, but the app should request and preserve the strongest clean signal the connected device can sustain.

## Scope

In scope:

- Real-time encoded recording quality for normal VIDEO, PRO, HDR, LOG, and other non-RAW encoded modes.
- Camera2 recording request cleanup: noise reduction, edge mode, hot-pixel correction, lens shading, distortion correction, aberration correction, tonemap, and stabilization where supported.
- Encoder preservation: bitrate floor, bitrate mode, codec complexity, QP bounds, GOP/all-intra policy where supported, and truthful color metadata.
- Existing computational denoise/detail policies that affect recording quality without changing UI.
- Unit tests and device validation for recording logs and saved metadata.

Out of scope for this pass:

- UI changes.
- Full post-processing workflow after save.
- RAW_SENSOR file format changes.
- New ML model integration.
- Major recording pipeline rewrite unless tests prove the existing policy boundaries cannot support the fix.

## Current Architecture

The recording path is split across existing policy layers:

- `camera`: Camera2 request templates, stream use-case roles, session targets, ISP cleanup request policy, and hardware capability checks.
- `pipeline`: scene/noise/detail decisions, temporal and chroma denoise strengths, render profile, and thermal/load limits.
- `recording`: MediaCodec plan, bitrate, codec selection, encoder format knobs, MOV metadata, and MediaStore save behavior.
- `ui`: recording start orchestration, preview/recording session refresh, analysis target sizing, and runtime performance caps.

Recent fixes already moved non-RAW encoded recording toward preview parity by using a preview-parity Camera2 request path and preserving SDR color metadata. This upgrade builds on those boundaries instead of creating another recording path.

## Design

### 1. Keep Preview And Recording On The Same Quality Intent

Non-RAW encoded recording must continue to use the preview-parity Camera2 request mode unless RAW_SENSOR capture is active. The record surface should not silently force a lower-quality `VIDEO_RECORD` intent when that causes ISP behavior to diverge from preview.

The implementation will add tests or strengthen existing tests to prove:

- Normal VIDEO/PRO/HDR/LOG encoded recording uses the preview-parity request path.
- RAW_SENSOR recording keeps its truthful RAW path.
- Record surface stream-use-case selection does not reintroduce a lower-quality parallel path for encoded video.

### 2. Raise Camera2 ISP Cleanup For Recording

For encoded video recording, the Camera2 cleanup policy should request the best supported hardware processing controls:

- high-quality noise reduction when available
- high-quality edge/detail mode when available
- hot-pixel correction
- lens shading correction
- distortion correction
- color aberration correction
- stable tonemap behavior for the selected signal family
- video stabilization only when supported and compatible with the selected mode

The policy must remain capability-driven. Unsupported Camera2 modes are skipped with existing fallback behavior rather than forced.

### 3. Strengthen Recording Denoise Without Smearing Detail

The computational profile should bias recording toward stronger chroma and temporal denoise while preserving edges and texture:

- higher chroma-noise suppression for RGB grain and colored speckle
- stronger low-light temporal smoothing with motion-aware limits
- spatial denoise floors raised only where motion and detail risk allow it
- sharpening kept natural and bounded to avoid halos
- HDR/LOG/SDR color delivery untouched by denoise tuning

The target is cleaner footage with fewer moving RGB grains during recording, not plastic skin or waxy detail.

### 4. Improve Encoder Preservation

The encoder policy should preserve more sensor detail once the camera pipeline produces a cleaner frame:

- keep or raise existing high-end bitrate floors where encoder support allows
- prefer stable high-quality bitrate mode and codec complexity settings
- apply bounded QP ranges when supported so the encoder does not crush shadows or smear texture
- preserve truthful 10-bit SDR/HDR/LOG metadata and MOV color atoms
- keep mode-specific color transfer decisions separate from quality decisions

This must not make normal SDR record as HDR, and LOG must not inherit HDR transfer metadata.

### 5. Thermal And FPS Guardrails

Quality improvements must not cause recording instability. If thermal or frame budget pressure appears, the app should preserve stable recording first and degrade only the heaviest optional processing. The base Camera2 cleanup and truthful encoder metadata should stay stable.

The implementation should log enough runtime state to verify which quality tier and encoder path were selected during a debug recording.

## Testing Strategy

Unit tests:

- Camera2 recording cleanup requests select high-quality supported modes.
- Encoded recording stays on preview-parity request/stream role while RAW keeps RAW behavior.
- Scene/render recording profile raises chroma and temporal denoise floors without over-sharpening.
- Encoder format policy applies high-quality preservation settings while keeping SDR, HDR, and LOG color metadata truthful.
- Bitrate/quality planning remains resolution and FPS aware.

Device validation:

- Build and run unit tests.
- Install the debug APK on the connected device.
- Record at least one debug VIDEO clip through the app's debug command path.
- Capture logcat and verify Camera2 pipeline, encoder format, quality tier, saved path, duration, size, bitrate evidence, and color metadata.
- Confirm saved normal SDR output is not labelled HDR.

## Acceptance Criteria

- Recording no longer switches into an obviously lower-quality processing path compared with preview for encoded modes.
- Recording pipeline requests the strongest supported ISP cleanup controls.
- RGB grain and moving chroma noise are reduced by stronger real-time denoise policy.
- Detail and edge clarity are preserved without aggressive oversharpening.
- Normal VIDEO/PRO SDR remain SDR; HDR remains HDR; LOG remains LOG; RAW remains RAW.
- Unit tests and debug device recording validation pass.
