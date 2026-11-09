# Vulkan Cinema Debayer

GPU-oriented RAW Bayer demosaic and highlight recovery path.

## Implementation Anchors

- `app/src/main/java/com/neoproc/neoproccamx/rendering/VulkanCinemaDebayerPipeline.kt`
- `app/src/main/cpp/shaders/vulkan/cinema_debayer.comp`
- `app/src/main/cpp/shaders/vulkan/demosaic_vulkan.comp`

## Purpose

Vulkan Cinema Debayer converts RAW Bayer sensor grids into scene-linear RGB before display or encoded delivery.

It exists so RAW workflows can bypass default display tone mapping and keep highlight latitude under app control.

## Runtime Flow

1. A RAW HardwareBuffer/Image is described by width, height, stride, black/white levels, and Bayer pattern.

2. The pipeline normalizes RAW samples to linear light.

3. The compute shader reconstructs missing color channels using edge-aware interpolation.

4. Highlight recovery and shoulder compression preserve super-white detail before output.

## Core Math

RAW normalization:

```text
r(x,y) = clamp((raw(x,y) - blackLevel) / (whiteLevel - blackLevel), 0, 1)
```
Bayer pattern selects the known channel at each pixel:

RGGB, GRBG, GBRG, or BGGR

Edge-directed green reconstruction compares horizontal and vertical gradients:

```text
H = abs((C[x-2,y] + 2C[x,y] + C[x+2,y]) - 2(G[x-1,y] + G[x+1,y]))
```
```text
V = abs((C[x,y-2] + 2C[x,y] + C[x,y+2]) - 2(G[x,y-1] + G[x,y+1]))
```
If H < V:

```text
G = (G[x-1,y] + G[x+1,y]) / 2 + (2C[x,y] - C[x-2,y] - C[x+2,y]) / 4
```
If V < H:

```text
G = (G[x,y-1] + G[x,y+1]) / 2 + (2C[x,y] - C[x,y-2] - C[x,y+2]) / 4
```
Highlight shoulder:

```text
L' = threshold + (1 - threshold) * (1 - exp(-strength * (L - threshold) / (1 - threshold)))
```
## Truth Boundary

The debayer path is active only when rawGpuDebayerActive and Vulkan support are true.

```text
RAW recording itself may remain RAW_SENSOR direct data, meaning debayer is not applied to the encoded stream unless explicitly routed.
```
