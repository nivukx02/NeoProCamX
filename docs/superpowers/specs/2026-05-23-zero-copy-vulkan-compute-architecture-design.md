# Zero-Copy Vulkan Compute Architecture Design

Date: 2026-05-23
Project: NeoProCamX

## Goal

Upgrade NeoProCamX image processing so the real preview, recording, and NPV10 export enhancement stages can run through native Vulkan compute where the device supports it, while preserving existing modes, UI behavior, quality policies, metadata truth, and fallback safety.

The implementation must not expose or depend on private-vendor color science as product behavior. New color behavior is limited to NeoProCamX-owned profiles and public standards:

- Rec.709
- Rec.2020
- Neo Wide Gamut
- N-Log
- NDR10+
- NIVUKX Vision

Legacy or private-vendor profile strings may only be accepted as migration aliases and must resolve to NeoProCamX-owned profiles before reaching preview, recording, export, metadata, shaders, or quality certificates.

## Current Project Context

NeoProCamX already has a native Vulkan bridge with Android hardware-buffer import support. The project includes:

- Native Vulkan bridge code in `app/src/main/cpp/vulkan_bridge.cpp`.
- Android hardware-buffer import logic through `VK_ANDROID_external_memory_android_hardware_buffer`.
- JNI entry points for preview/encoder surfaces, render config updates, LUT updates, dynamic range mode updates, hardware-buffer submission, and imported-frame cleanup.
- Kotlin runtime management through `VulkanBridge.kt` and `VulkanRuntimeManager.kt`.
- Existing Vulkan shaders for preview, HDR merge, denoise, sharpening, and related rendering stages.
- CPU/Kotlin enhancement work in the NPV10 MOV export path, especially noise metrics, temporal blend, chroma suppression, local chroma spike smoothing, and edge-aware luma detail lifting.

The upgrade should extend this foundation rather than replace the whole architecture.

## Hard Limits

The implementation must be truthful about device capability.

- Live camera frames can use real zero-copy only when they arrive as importable Android `HardwareBuffer` objects and Vulkan import succeeds.
- Current private NPV10 masters are stored as byte payloads, so that path cannot honestly be called zero-copy unless the master storage format changes to a GPU-importable handle model.
- NPV10 export can still be accelerated through Vulkan storage buffers or staging images, but the quality report must label that as GPU-accelerated export, not zero-copy export.
- 4K 60 FPS and low CPU usage are targets, not universal guarantees. They must be gated by runtime capability checks, frame timing evidence, and fallback logs.
- CPU fallback is allowed only when reported truthfully in diagnostics and quality evidence.

## Architecture

### 1. GPU Enhancement Uniform Contract

Add a stable packed uniform model that carries existing quality-policy values from Kotlin into native Vulkan without changing the math meaning.

The contract should include:

- mode and transport classification
- color space and color science IDs
- N-Log, NDR10+, NIVUKX Vision selection
- temporal luma and chroma blend
- spatial denoise strength
- chroma denoise strength
- shadow chroma deadband
- local chroma spike smoothing strength
- fine, medium, and strong detail thresholds
- fine, medium, and strong detail gains
- alias-safe max detail delta
- noise-adaptive sharpening floor and ceiling
- ISO/noise metric inputs when available
- skin/detail protection strength
- preview/recording parity signature inputs

Kotlin remains responsible for policy decisions. Native Vulkan is responsible for executing the selected processing stages.

### 2. Kotlin Mapper

Add a mapper from `SceneRenderProfileEngine` and NPV10 export profiles into the GPU uniform contract.

Responsibilities:

- Convert nullable or mode-specific policy values into explicit shader-safe defaults.
- Normalize color and log selections into NeoProCamX-owned IDs only.
- Reject or migrate legacy/private-vendor profile names before JNI.
- Create a deterministic signature so preview, recording, and export can prove they received the same processing intent.

### 3. JNI Bridge Extension

Extend the existing native bridge with focused calls instead of creating a second native system.

Required JNI shape:

- update current GPU enhancement uniforms
- report GPU processing capability and selected path
- submit live hardware-buffer frames through the existing import path
- process NPV10 export frames through a GPU-accelerated path when supported
- return fallback reasons when the device or frame format cannot use GPU processing

All JNI calls must be defensive:

- validate array sizes and enum ranges
- reject unsupported formats clearly
- avoid silently clamping profile IDs into the wrong mode
- record capability and fallback reasons for certificates

### 4. Vulkan Compute Passes

Add compute stages that mirror the real CPU policy stages.

Initial pass set:

- `neo_temporal_denoise.comp`
- `neo_chroma_denoise.comp`
- `neo_detail_lift.comp`
- `neo_color_transform.comp`
- `neo_p010_export_enhance.comp`

Processing order:

1. Import or bind current frame.
2. Bind previous frame when temporal denoise is enabled and dimensions match.
3. Apply temporal denoise before sharpening or detail lifting.
4. Apply luma/chroma denoise with stronger shadow chroma control.
5. Apply local chroma spike smoothing.
6. Apply edge-aware detail lift bounded by `aliasSafeMaxDetailDelta`.
7. Apply natural sharpening with noise-adaptive floor and ceiling.
8. Apply NeoProCamX color transform or log/HDR output transform.
9. Write to preview, encoder, or export destination.

No global sharpening pass should bypass denoise or edge confidence.

### 5. Live Preview and Recording Path

The live path should reuse the existing hardware-buffer import and preview/encoder surface support.

Rules:

- Preview and recording must consume the same mode decision and GPU uniform signature.
- The app must not add extra Camera2 streams just to satisfy the GPU path unless a capability gate proves the stream combination is supported.
- If the recording path falls back to Camera2 direct recording and bypasses custom processing, the quality certificate must say so.
- Normal and Pro modes must remain SDR unless the selected mode explicitly requests NDR10+ or NIVUKX Vision.
- N-Log must remain log SDR/wide-gamut behavior, not HDR transport.

### 6. NPV10 Export Path

The existing CPU enhancement loop should become a fallback path.

Preferred export flow:

1. Read private NPV10 frame payload and metadata.
2. Validate payload bit depth, dimensions, stride, timestamps, and color metadata.
3. Upload or bind the payload to a Vulkan buffer/image when supported.
4. Run the same denoise, chroma, detail, and color transform contract used by preview/recording.
5. Write the processed frame into MOV export with correct color tags.
6. Record export path evidence as GPU accelerated or CPU fallback.

Zero-frame export, mislabeled 8-bit fallback, unsupported format, and wrong color metadata must fail clearly.

## Vendor-Free Color Science

The implementation must remove private-vendor color behavior from the product path.

Allowed behavior:

- Rec.709 SDR
- Rec.2020 SDR or HDR transport when supported
- Neo Wide Gamut mapped to public transport metadata truthfully
- N-Log Base, Shadow, Highlight, and Cinema
- NDR10+
- NIVUKX Vision

Disallowed behavior:

- private-vendor color-space names in UI choices, metadata, certificates, shader mode names, or new code paths
- official third-party HDR claims unless certified metadata is truly written
- profile labels that imply private-vendor compatibility

Legacy inputs should migrate to the closest NeoProCamX-owned profile and log that migration.

## Capability Gate

GPU processing is enabled only when all required capabilities are present:

- Vulkan instance and device creation succeeds.
- Compute queue is available.
- Android hardware-buffer import extension is available for live zero-copy frames.
- Required image or buffer formats are supported for sampled and storage access.
- Descriptor limits support current, previous, and output bindings.
- Synchronization primitives are available for safe preview/recording use.
- Thermal and frame timing state are inside allowed runtime bounds.

If any check fails, the app must select a named fallback path and expose it to logs and quality reports.

## Diagnostics and Quality Evidence

Every recording/export should be able to report:

- selected mode
- selected color space and color science
- GPU processing enabled or disabled
- zero-copy live import enabled or disabled
- export path type
- shader stage list
- fallback reason if any
- frame count
- dropped-frame count
- timing variance
- resolution, FPS, bit depth, transfer, primaries, matrix, and codec metadata
- preview/recording processing signature match or mismatch

No path should claim production 10/10 quality without evidence.

## Testing Strategy

Add or update tests for:

- uniform mapping from scene profiles to GPU contract
- vendor-free color profile normalization
- legacy/private-vendor aliases migrating to NeoProCamX-owned profiles
- preview and recording parity signatures
- NPV10 export path selecting GPU acceleration only when supported
- CPU fallback reporting a truthful reason
- shader contract files containing expected uniform names and stage order
- N-Log not being tagged as HDR
- NDR10+ and NIVUKX Vision using truthful Android-compatible transport metadata only when supported

Build verification must run with warnings as failures after implementation.

## Implementation Boundaries

This upgrade should not redesign the app UI, remove camera modes, delete existing NPV10 functionality, bypass unit tests, or silently change user-facing feature behavior.

The first implementation phase should focus on:

1. Vendor-free color normalization in the GPU-bound path.
2. GPU uniform contract and mapper tests.
3. JNI bridge extension for enhancement uniforms and capability reporting.
4. Vulkan compute shader scaffolding and contract validation.
5. NPV10 export GPU acceleration gate with CPU fallback evidence.
6. Preview/recording parity evidence updated to include GPU processing signatures.

Later phases can deepen full live encoder GPU processing once real-device capability evidence proves the target device supports the required format and synchronization path.

