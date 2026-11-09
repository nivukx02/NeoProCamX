# Vulkan Processed Recording Path

GPU-rendered frame path that sends processed frames to MediaCodec when hardware import validates.

## Implementation Anchors

- `app/src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt`
- `app/src/main/java/com/neoproc/neoproccamx/rendering/VulkanBridge.kt`
- `app/src/main/java/com/neoproc/neoproccamx/ui/RecordingPipelineParityPolicy.kt`
- `app/src/main/cpp/vulkan_bridge.cpp`

## Purpose

The Vulkan processed recording path makes recorded frames use the same shader stack as the preview instead of receiving low-complexity Camera2 direct frames.

It is the practical route for WYSIWYG denoise, LUT, tone mapping, sharpness, and stabilization parity.

## Runtime Flow

1. Camera frames are imported into Vulkan.

2. ycbcr_preview.frag applies color science, denoise, sharpness, and delivery encoding.

3. The Vulkan bridge binds the encoder surface and submits the processed frame.

4. MediaCodec encodes the GPU-produced frame.

```text
5. Runtime logs and Pixel Reality indicate recordingGpu=true only after import and encoder binding succeed.
```
## Core Logic

The path is considered valid when:

```text
vulkanRuntimeSupported = true
```
```text
feedPath = INTERNAL_HARDWARE_IMPORT_ENCODER
```
```text
recordingGpuFrameImportValidated = true
```
```text
encoderSurfaceBound = true
```
Then:

```text
recordingGpu = true
```
```text
activeStages = {TONE_MAP, COLOR_PROFILE, LUT, DENOISE, SHARPNESS, STABILIZATION}
```
Frame cost model:

```text
T_total = T_import + T_shader + T_encoder_submit
```
```text
The goal is to keep T_total <= frameInterval, where frameInterval = 1000 / fps ms.
```
## Truth Boundary

If the encoder surface cannot be bound or no frame imports, the app must report fallback.

GPU path quality depends on shader correctness, encoder bit depth, and device surface support.
