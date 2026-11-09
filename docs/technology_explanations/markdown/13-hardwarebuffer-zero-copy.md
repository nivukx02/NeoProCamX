# HardwareBuffer Zero Copy

Camera2-to-Vulkan-to-MediaCodec buffer path designed to avoid app-visible CPU copies.

## Implementation Anchors

- `app/src/main/java/com/neoproc/neoproccamx/pipeline/HardwareBufferZeroCopyVerifier.kt`
- `app/src/main/cpp/native_buffer_manager.cpp`
- `app/src/main/cpp/native_buffer_manager.h`
- `app/src/main/cpp/vulkan_bridge.cpp`

## Purpose

The zero-copy path keeps frames in hardware buffers so GPU and encoder surfaces can exchange them without JVM byte-array copies.

This reduces latency, garbage collection, and memory bandwidth pressure.

## Runtime Flow

1. Camera2 writes into an ImageReader or Surface backed by HardwareBuffer.

2. Image.hardwareBuffer is acquired and validated.

3. Native Vulkan imports the external memory or samples from the buffer-backed image.

4. Vulkan renders into the encoder surface, and MediaCodec consumes the frame.

## Core Logic

Required usage flags:

```text
usage = GPU_SAMPLED_IMAGE | GPU_COLOR_OUTPUT
```
Frame validation checks:

```text
zeroCopyAvailable = hardwareBuffer != null
```
```text
isTrulyZeroCopy = validationTimeNs < 100000
```
The performance model is:

```text
T_frame = T_camera_queue + T_import + T_shader + T_encoder_queue
```
Zero-copy targets T_import approximately 0 and avoids:

```text
T_copy = bytesPerFrame / memoryBandwidth
```
```text
For 4K P010, bytesPerFrame ~= (3840*2160 + 2*1920*1080) * 2 ~= 24.9 MB
```
Avoiding even one full copy per frame saves about 1.5 GB/s at 60 fps.

## Truth Boundary

HardwareBuffer presence does not prove the entire path is zero-copy; it is a zero-copy candidate.

The project uses runtime validation and import counters to distinguish real import from fallback.
