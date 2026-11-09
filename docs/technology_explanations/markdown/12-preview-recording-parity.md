# Preview Recording Parity

Policy system that keeps the viewfinder and encoded video on the same processing route where possible.

## Implementation Anchors

- `app/src/main/java/com/neoproc/neoproccamx/ui/RecordingPipelineParityPolicy.kt`
- `app/src/main/java/com/neoproc/neoproccamx/ui/RecordingPreviewContinuityPolicy.kt`
- `app/src/main/java/com/neoproc/neoproccamx/rendering/VulkanPreviewRecordingParityPolicy.kt`
- `app/src/main/cpp/vulkan_bridge.cpp`

## Purpose

Preview/Recording Parity is the WYSIWYG contract: the recorded file should match the live preview as closely as the hardware path allows.

The project prefers internal hardware import into the encoder when Vulkan support and stability evidence are present.

## Runtime Flow

1. The policy builds a parity plan from mode, Vulkan runtime support, RAW state, and hardware-import preference.

2. RAW video uses direct Camera2 RAW surfaces because RAW should remain sensor data.

3. Non-RAW PRO/LOG/NDR modes can use INTERNAL_HARDWARE_IMPORT_ENCODER when stable.

4. Parity certificates compare color space, denoise, sharpness, LUT, and stage signatures.

## Core Logic

Feed path selection:

```text
if rawVideoEnabled:
```
```text
    feed = CAMERA2_DIRECT_RECORD_SURFACE
```
```text
else if vulkanRuntimeSupported && (preferInternalHardwareImport || internalHardwareImportStable):
```
```text
    feed = INTERNAL_HARDWARE_IMPORT_ENCODER
```
else:

```text
    feed = CAMERA2_PREVIEW_PARITY_RECORD_SURFACE
```
Parity condition:

```text
parityPass = previewColorSpace == recordingColorSpace
```
```text
          && previewStages == recordingStages
```
```text
          && previewDenoiseProfile == recordingDenoiseProfile
```
```text
          && previewSharpnessProfile == recordingSharpnessProfile
```
## Truth Boundary

```text
Parity cannot be absolute if the device refuses hardware import, 10-bit transport, or encoder-surface binding.
```
The project records evidence and warnings instead of forcing a false parity claim.
