# NIVUKX Vision

Project-owned premium HDR color-volume profile built above the NDR transport path.

## Implementation Anchors

- `core-math/src/main/kotlin/com/neoproc/neoproccamx/recording/HdrImagingMath.kt`
- `app/src/main/java/com/neoproc/neoproccamx/pipeline/HdrVersionRenderPolicy.kt`
- `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingHdrVideoLookPolicy.kt`
- `app/src/main/cpp/shaders/vulkan/ycbcr_preview.frag`

## Purpose

NIVUKX Vision is the app-owned HDR rendering identity for high-contrast scenes, neon colors, and wide-gamut highlights.

It uses BT.2020/PQ-compatible transport where possible while doing the creative color-volume decisions inside Neo math and Vulkan shaders.

## Runtime Flow

1. UI or profile policy resolves the requested HDR mode to NIVUKX Vision.

2. HdrVersionRenderPolicy selects stronger shadow, chroma-denoise, texture, and highlight-compression floors than NDR10+.

3. Vulkan uses Neo mode parameters to apply the NIVUKX color-volume map and optional NLogs delivery path.

4. Recording metadata says NIVUKX Vision, not a third-party Dolby/Vendor profile.

## Core Math

```text
The signal starts from normalized scene luminance L in [0, 1] with headroom for highlights.
```
Perceptual HDR signal:

```text
V_pq = PQ(L * L_peak)
```
NIVUKX color-volume compression keeps hue stable by acting on perceptual chroma C:

```text
C = sqrt(a^2 + b^2)
```
```text
h = atan2(b, a)
```
```text
C_nivukx = C * (1 - tanh(omega * L^2) * gamma_compress)
```
```text
a' = C_nivukx * cos(h)
```
```text
b' = C_nivukx * sin(h)
```
The pipeline compresses chroma near white to avoid channel clipping:

```text
C_final = C_nivukx * (1 - smoothstep(0.85, 0.98, L) * 0.85)
```
10-bit code values are:

```text
D_10 = round(clamp(V_pq, 0, 1) * 1023)
```
## Truth Boundary

NIVUKX Vision is proprietary naming for NeoProCamX's own color-volume and tone mapping logic.

```text
The project explicitly avoids claiming Dolby certification for this mode.
```
