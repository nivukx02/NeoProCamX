# Neo 10-bit Dither Export

10-bit export protection using quantization-aware dithering for NPV10/NDR/NIVUKX/Log paths.

## Implementation Anchors

- `app/src/main/java/com/neoproc/neoproccamx/recording/EliteVideoQualityContract.kt`
- `app/src/main/java/com/neoproc/neoproccamx/recording/Npv10MovConverter.kt`
- `app/src/main/cpp/shaders/vulkan/neo_p010_export_enhance.comp`
- `app/src/main/cpp/shaders/vulkan/ycbcr_preview.frag`

## Purpose

```text
The Neo 10-bit dither path protects gradients during export and preview when signals are quantized into 10-bit or display-limited output.
```
It is especially important for skies, shadows, log curves, and HDR shoulder regions.

## Runtime Flow

1. Export policy identifies modes that need dithering, especially NPV10, NIVUKX Vision, NDR10+, and Neo Log.

2. Shader or converter applies a low-amplitude noise field before quantization.

3. Quantization maps continuous values into integer code values.

4. The dither turns coherent banding error into visually smoother high-frequency error.

## Core Math

Base 10-bit quantization without dither:

```text
code = round(clamp(x, 0, 1) * 1023)
```
TPDF dither uses the difference of two uniform random variables:

```text
d = (u1 - u2) / 1023
```
```text
x_dithered = clamp(x + d * amount, 0, 1)
```
```text
code = round(x_dithered * 1023)
```
Blue-noise/JND shaping can scale amount by luminance:

```text
A(Y) = (1 / 1023) * (0.18 + 0.42 * sqrt(Y)) * (1 - smoothstep(0.78, 1.0, Y))
```
```text
x_dithered = x + A(Y) * B(x mod 64, y mod 64, frame mod N)
```
Quantization error:

```text
e = x - code / 1023
```
Dither target: decorrelate e from the image structure.

## Truth Boundary

Dither does not add real bit depth; it masks quantization structure perceptually.

The master still needs true 10-bit source/storage evidence for native 10-bit claims.
