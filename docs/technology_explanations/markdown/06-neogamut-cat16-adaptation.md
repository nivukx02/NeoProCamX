# NeoGamut CAT16 Adaptation

CIE CAT16 white-point adaptation from dynamic scene white to Neo D55.

## Implementation Anchors

- `app/src/main/java/com/neoproc/neoproccamx/color/NeoGamutCat16Math.kt`
- `app/src/main/java/com/neoproc/neoproccamx/rendering/VulkanColorScienceUniformPolicy.kt`
- `app/src/main/cpp/shaders/vulkan/ycbcr_preview.frag`

## Purpose

CAT16 adaptation keeps colors stable when the sensor white balance changes.

The project computes a source-white to D55 matrix on the CPU and sends matrix uniforms to the Vulkan rendering path.

## Runtime Flow

1. White balance or kelvin/lux data provides a source white point.

2. NeoGamutCat16Math calculates a 3x3 adaptation matrix.

3. VulkanColorScienceUniformPolicy packages color-science uniforms.

4. The shader applies the matrix per pixel before OkLCh-style grading and tone mapping.

## Core Math

CAT16 cone response:

```text
rho_s = M16 * W_s
```
```text
rho_t = M16 * W_t
```
```text
scale_i = rho_t_i / rho_s_i
```
```text
M_adapt = M16_inv * diag(scale) * M16
```
The matrix in source is:

```text
M16 = [[0.401288, 0.650173, -0.051461],
```
```text
       [-0.250268, 1.204414, 0.045854],
```
```text
       [-0.002079, 0.048952, 0.953127]]
```
For a CIE xy white point:

```text
X = x / y, Y = 1, Z = (1 - x - y) / y
```
Adaptive kelvin/lux strength:

```text
M_final = I + (M_adapt - I) * adaptationStrength
```
```text
adaptationStrength = clamp(0.58 + smoothstep(8, 800, lux) * 0.40, 0.58, 0.98)
```
## Truth Boundary

CAT16 improves white adaptation but does not replace physical sensor calibration.

Bad or missing camera metadata causes identity fallback to avoid unstable color shifts.
