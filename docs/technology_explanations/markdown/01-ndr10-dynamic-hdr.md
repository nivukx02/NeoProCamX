# NDR10+ Dynamic HDR

NeoProCamX dynamic HDR delivery profile using BT.2020/PQ-compatible 10-bit transport with app-owned tone mapping and metadata.

## Implementation Anchors

- `core-math/src/main/kotlin/com/neoproc/neoproccamx/recording/HdrImagingMath.kt`
- `app/src/main/java/com/neoproc/neoproccamx/pipeline/HdrVersionRenderPolicy.kt`
- `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingHdrVideoLookPolicy.kt`
- `app/src/main/cpp/shaders/vulkan/ycbcr_preview.frag`

## Purpose

NDR10+ is the project-owned dynamic range mode for HDR-looking video without claiming a licensed vendor profile.

The pipeline treats scene light as linear radiance, applies a dynamic shoulder, maps color into BT.2020/PQ transport when supported, and records truth metadata so the output is not mislabeled.

## Runtime Flow

```text
1. Camera2 exposes a 10-bit-capable stream when the device supports it.
```
2. Vulkan receives the frame through the preview/recording path and applies Neo tone, denoise, sharpness, and color-volume controls.

3. RecordingHdrVideoLookPolicy maps NDR10+ to BT.2020 with ST.2084/PQ transfer when the encoder can accept it.

4. EliteVideoQualityContract audits bit depth, transport, color science, and evidence before metadata is emitted.

## Core Math

Scene luminance is computed from linear RGB:

```text
Y = 0.2627002 R + 0.6779981 G + 0.0593017 B  (BT.2020 luma)
```
Dynamic percentile anchors are derived from a frame histogram H:

```text
CDF(k) = sum_{i=0..k} H_i
```
```text
P_q = (argmin_k CDF(k) >= q * CDF(255)) / 255 * L_peak
```
The dynamic knee uses highlight anchors:

```text
K = clamp(0.65 P90 + 0.35 P99, P50 + 1, 4000)
```
A smooth shoulder maps highlight lightness L:

```text
T(L) = L, if L <= K
```
```text
T(L) = K + (1 - K) * tanh(kappa * (L - K) / (1 - K)), if L > K
```
PQ encoding follows ST.2084:

```text
V = ((c1 + c2 * (L / 10000)^m1) / (1 + c3 * (L / 10000)^m1))^m2
```
```text
with m1=2610/16384, m2=2523/32, c1=3424/4096, c2=2413/128, c3=2392/128.
```
## Truth Boundary

NDR10+ is a NeoProCamX label. It is not advertised as vendor-certified HDR10+ unless actual platform/vendor evidence exists.

On devices without 10-bit/PQ encoder support, the project records fallback evidence instead of pretending true NDR10+ transport happened.
