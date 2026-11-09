# Pixel Reality

Runtime truth-reporting layer for preview, recording, AI, and RAW processing claims.

## Implementation Anchors

- `app/src/main/java/com/neoproc/neoproccamx/ui/PixelProcessingRealityPolicy.kt`
- `app/src/main/java/com/neoproc/neoproccamx/ui/RecordingPipelineParityPolicy.kt`
- `app/src/test/java/com/neoproc/neoproccamx/ui/PixelProcessingRealityPolicyTest.kt`

## Purpose

Pixel Reality prevents the app from claiming that a feature is active when the actual frame path bypasses it.

It classifies each stage as real GPU shader, real GPU compute, CPU analysis, Camera2 ISP, planned-only, or unsupported.

## Runtime Flow

1. RecordingPipelineParityPolicy resolves the recording feed path.

2. Runtime evidence reports whether native preview, Vulkan, AI, multi-frame, GPU import, and RAW debayer paths are active.

3. PixelProcessingRealityPolicy builds per-stage evidence and a signature string.

4. UI and metadata can use the signature to avoid fake quality claims.

## Core Logic

Preview custom GPU flag:

```text
previewGpu = nativePreviewActive && vulkanRuntimeSupported
```
Recording custom GPU flag:

```text
recordingGpu = vulkanRuntimeSupported && feedPath == INTERNAL_HARDWARE_IMPORT_ENCODER && recordingGpuFrameImportValidated
```
Custom GPU active:

```text
customGpuActive = recordingGpu
```
Camera2 custom-processing bypass:

```text
bypass = !recordingGpu && feedPath != INTERNAL_HARDWARE_IMPORT_ENCODER
```
The signature is:

PIXEL_REALITY|feed=<path>|previewGpu=<bool>|recordingGpu=<bool>|customGpuActive=<bool>|customGpuBypass=<bool>|real=<stages>|planned=<stages>|unsupported=<stages>

## Truth Boundary

A stage appears as real for recording only after recordingGpuFrameImportValidated is true.

This is a verification layer, not an image-processing algorithm.
