# User-Authoritative Quality Settings Design

**Date:** 2026-05-27

**Problem**

The manual video bitrate default currently resolves to `180 Mbps` although the
requested default is `200 Mbps`. Separately, strict high-end runtime behavior
forces AI enhancement and noise reduction on, so an explicit user selection of
`Off` is stored but is not applied through the preview and recording control
path.

**Required Behavior**

1. A fresh/default manual video bitrate selection is `200 Mbps`.
2. Applying the high-end feature pack chooses the same `200 Mbps` target.
3. A saved manual bitrate selected by the user remains authoritative; startup
   must not silently rewrite an existing custom selection.
4. If an existing custom target exceeds the encoder-applied bitrate, settings
   show both the user target and applied bitrate rather than replacing the
   selected value in the label.
5. Explicit AI Enhancement and Noise Reduction selections are applied exactly
   as selected after startup and during runtime setting changes.
6. The settings UI displays the recording FPS actually selected/applied; a
   strict-quality display helper must not silently promote a valid `24 FPS`
   selection to another value.
7. Hardware-incompatible resolution, FPS, bit depth, and transport selections
   continue to use truthful capability fallback rather than fake application.

**Architecture**

Keep the existing settings controller and recording bitrate flow. Restore the
two default bitrate constants used by preferences and the high-end pack to
`200_000_000`. In `CameraViewModel`, keep the existing centralized effective
setting helpers, but make them return the user preference unchanged. This
preserves all existing call sites while eliminating the strict-quality
override that breaks the UI-to-pipeline setting contract. Make FPS selection
presentation report the nearest current recording value; any hardware
capability fallback remains in the existing resolution/frame-rate resolver.
Format custom bitrate status as a target plus applied value only when encoder
capability resolution or live measurement differs from the saved target.

**Validation**

- Source-contract tests require `200 Mbps` as the default and feature-pack
  target.
- Formatter and wiring tests require a capped custom bitrate to preserve the
  target while also displaying the applied encoder rate.
- A settings application contract requires AI and noise-reduction effective
  values to preserve the requested preference.
- A selector contract requires valid user-selected FPS to stay selected while
  strict high-end processing is enabled.
- Unit tests, debug build, and lint must pass.
- If an adb target is connected, install and launch the debug APK for device
  confirmation; do not overwrite an existing custom bitrate during deployment.
