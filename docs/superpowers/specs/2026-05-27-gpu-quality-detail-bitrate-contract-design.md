# GPU Quality, Detail, And Bitrate Contract Design

**Date:** 2026-05-27  
**Scope:** Preview, processed recording, and NPV10 export quality refinement for VIDEO, PRO, NDR, LOG, and RAW-facing workflows.

## Objective

Improve perceived detail and controlled noise suppression in Vulkan-processed video and NPV10 derived output without falsifying hardware capabilities, destroying RAW sensor truth, or overriding user bitrate control.

This design does not claim literal zero sensor noise. Sensor noise is physical; the implementable target is lower measured flat-region luma/chroma variance with bounded edge overshoot and retained fine texture.

## Existing Baseline

The current code already provides:

- Vulkan internal hardware-import recording selection for processed recording when Vulkan support is available.
- Runtime proof gating so `recordingGpu=true` is reported only after imported encoder frames exist.
- Capability-gated ISP requests, including distortion correction OFF only when the Camera2 HAL exposes it.
- P010-native NPV10 selection that fails closed rather than presenting 8-bit-expanded data as native 10-bit.
- Final-pass 10-bit quantization dither for truthful recording delivery and existing deep-color NPV10/NDR/NIVUKX export dither support.
- Three bitrate behaviors: `CUSTOM`, `AUTO`, and `CINEMA_MASTER`.

The implementation must preserve these contracts.

## Routing And Sensor Truth

### Processed Modes

`VIDEO`, `PRO`, `NDR`, and `LOG` use the internal Vulkan encoder feed when Vulkan hardware import is selected and validated at runtime. Their recorded imagery may use LUT, denoise, sharpening, tone transfer, and other application-owned enhancement stages.

Telemetry may report:

```text
feed=INTERNAL_HARDWARE_IMPORT_ENCODER
previewGpu=true
recordingGpu=true
bypass=false
```

only after Vulkan-imported recording frames have been observed.

### RAW And NPV10 Masters

Native RAW12 storage remains sensor-truth data. Noise reduction or sharpening must not rewrite RAW12 packed master samples. RAW preview and derived publications may render enhanced imagery, but metadata and validation must distinguish those derivatives from the master payload.

Native P010 NPV10 output remains aligned as 10-bit P010 data. Enhancements and final dither may apply to NPV10 derived/export imagery where the format contract permits them.

### ISP And Geometry Controls

For GPU-owned processed recording and RAW truth paths, request available Camera2 low-processing or OFF controls, including distortion correction OFF when supported. Unsupported controls must be recorded as unavailable; the application must not claim strict hardware bypass on a device that did not expose or apply the request.

## Bitrate Contract

Bitrate control remains user-accessible in every encoded mode.

### `CUSTOM`

Use the user's selected bitrate and preserve that intent across `VIDEO`, `PRO`, `NDR`, and `LOG`, while clamping against:

- The active encoder's reported capability range.
- Existing resolution/mode safety bounds required to avoid encoder rejection.

`CUSTOM` must not silently become `AUTO` or `CINEMA_MASTER`.

### `AUTO`

Retain automatic bitrate resolution from the existing quality policy and runtime encoder constraints. Auto mode may adapt to resolution, frame rate, transport, and scene/runtime policy already implemented in the app.

### `CINEMA_MASTER`

Retain the existing high-quality mastered ceiling tiers before encoder capability clamping:

| Resolution tier | Ceiling |
| --- | ---: |
| Up to 1080p | 45 Mbps |
| 4K | 100 Mbps |
| 8K | 180 Mbps |

NPV10 uncompressed master storage is not a MediaCodec bitrate mode. Bitrate settings apply only when a derived encoded publication is created.

## Preview And Processed Recording Shader Math

### Noise Model

The shared Vulkan preview/processed-recording shader will use the requested texture-preserving heteroscedastic base estimate:

```glsl
sigma2(Y) = 0.0012 * clamp(Y, 0.0, 1.0) + 0.00022
```

with a small positive numerical floor. This lower variance model treats more structured high-frequency signal as valid texture; it is not a guarantee of greater denoise by itself.

### Motion-Aware Detail Retention

The existing Laplacian/Bayes-shrink path will retain more detail under motion:

```glsl
preserve = mix(0.85, 1.0, motionConfidence)
```

The filter must continue to reduce history/denoise blending where local motion or structural change makes temporal reuse unsafe.

### Halo-Guarded 9-Tap Sharpening

Replace simple color-versus-luma amplification as the primary high-precision sharpening mechanism with 3x3 sampled neighborhood detail extraction:

```text
detail = center - bilateralLowPass3x3(neighborhood)
output = center + detail * adaptiveStrength * maxGain
maxGain = 4.85
```

The local filter must:

- Weight neighbors according to spatial proximity and luminance similarity.
- Suppress gain in low-structure flat/noisy regions.
- Bound overshoot near strong step edges to avoid white or dark halos.
- Allow higher gain for coherent texture such as hair, fabric, and skin micro-detail.

## NPV10 Export Alignment

NPV10 derived/export processing will align with the same intent:

- Retain the existing heteroscedastic and edge-aware enhancement structure where it is already more complete than the preview path.
- Tune shared quality constants only when tests demonstrate improved texture retention without overshoot regression.
- Use a single final perceptual dither pass when writing quantized deep-color output for NPV10, NDR10+, NIVUKX Vision, and LOG-derived encoded output.
- Preserve P010 structure and low-bit alignment requirements.

No filter is applied to native RAW12 master payload bytes.

## Required Tests And Device Evidence

### Unit And Contract Tests

Add or update regression tests for:

- New preview shader noise coefficients, motion preserve value, and real 9-tap halo-guarded sharpening structure.
- Generated SPIR-V regeneration contract for the changed fragment shader.
- Vulkan recording routing for `VIDEO`, `PRO`, `NDR`, and `LOG`, and RAW sensor-truth routing.
- `CUSTOM`, `AUTO`, and `CINEMA_MASTER` bitrate behavior, including manual bitrate preservation under applicable capability/safety limits.
- NPV10 dither coverage and RAW/P010 integrity.
- Capability-gated distortion correction OFF and truthful telemetry.

### Build Verification

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain
```

Run release assembly as an additional packaging gate if the workspace remains buildable for release.

### Device Validation

When an authorized Android device is attached:

1. Install the new debug APK.
2. Launch the camera app and check the crash buffer.
3. Record a short processed-mode clip and collect logs proving internal hardware-import recording began and imported frames were validated.
4. Where possible, pull the encoded clip and inspect its bitrate/codec metadata.

Measured noise reduction and detail retention require an actual fixed-scene capture comparison. Results must be reported as measurements, not guarantees.

## Non-Goals

- Forcing unsupported Camera2 ISP bypass flags.
- Labelling enhanced output as native RAW.
- Replacing manual bitrate control with fixed universal bitrate ceilings.
- Claiming physically impossible absolute zero sensor noise.
- Broad restructuring of unrelated AI, UI, gallery, or media pipeline components.
