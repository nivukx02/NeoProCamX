# Camera Contract Architecture

## Contract

NeoProCamX now routes camera capability claims through `CameraCapabilityInventory` and
`CameraStreamPlanResolver` in the `camera` package. The contract separates four facts:

- `RAW_SENSOR` output is the only path that may be labeled true sensor readout.
- YUV and encoder outputs can request reduced ISP processing, but they remain best-effort
  vendor pipelines and must not be labeled true ISP bypass.
- ISP bypass request keys are planned independently. The app only writes OFF/contrast-curve
  keys that the device exposes, and it labels bypass complete only when every required key
  is available.
- Full-sensor readout is truthful only when a supported RAW stream is at least as large
  as the device pixel-array size and the needed request keys are available.
- PRO and LOG remain encoder-delivered modes; YUV is analysis support, not the recorded
  delivery surface.

The fallback order is explicit:

- RAW request: full/supported RAW -> high-resolution YUV -> encoder-only -> unsupported.
- LOG/PRO request: encoder with YUV analysis -> encoder-only -> YUV-only -> unsupported.

## Runtime Inventory

`CameraController.loadCharacteristics()` builds a current-camera inventory from Camera2:

- Pixel array and active-array dimensions.
- Standard and high-resolution RAW/YUV/JPEG output sizes.
- MediaRecorder encoder output sizes.
- RAW, manual sensor, tonemap contrast curve, and ISP-off request-key support.

Strict high-end mode logs a separate stream plan for `PRO`, `LOG`, and `RAW` so runtime
diagnostics show the actual route selected by hardware capability instead of a generic
"full sensor" claim.

## Package Boundaries

- `camera`: owns Camera2 capability inventory, stream/readout planning, request-key truth,
  and session/request application.
- `recording`: owns codec, container, bit-depth, bitrate, color metadata, and muxing plans.
- `rendering`: owns Vulkan preview delivery, tone mapping, display transforms, and preview
  color presentation.
- `pipeline`: owns frame processing, AI analysis, native compute, and frame-health policy.
- `capture`: owns still capture coordination and RAW/JPEG output completion.
- `ui`: owns user intent and display state only; it should not invent camera capability
  claims or bypass labels.

Dependency direction should stay one-way: UI asks for a mode, camera resolves a stream
contract, recording/rendering/pipeline consume the contract details they own. Shared facts
should move through small policy objects or immutable data classes, not through direct
cross-package state mutation.

## Hardware Limits

No Android app can guarantee full-sensor video, zero noise, true ISP bypass, or uncompressed
12-bit RAW on every phone. Camera2 exposes what each device allows. The project should
always request the highest supported path, then report the exact fallback and avoid claiming
hardware behavior the device did not expose.
