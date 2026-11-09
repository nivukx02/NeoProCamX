# Manual Color Science Registry

User-facing color-space and look registry centered on Neo-owned profiles.

## Implementation Anchors

- `app/src/main/java/com/neoproc/neoproccamx/color/ColorScience.kt`
- `app/src/main/java/com/neoproc/neoproccamx/ui/controls/SettingsOptionCatalog.kt`
- `app/src/main/java/com/neoproc/neoproccamx/ui/VideoProfileIntentResolver.kt`

## Purpose

```text
The manual color registry exposes standards and Neo-owned color technologies without depending on third-party vendor looks.
```
Its main proprietary entries are Neo Wide Gamut, NDR10+, NIVUKX Vision, and the N-Log family.

## Runtime Flow

1. The user selects color space, color science, LUT, or look from settings.

2. SettingsOptionCatalog normalizes labels such as Rec.2020-NDR10+, Rec.2020-NIVUKX, Neo Wide Gamut, and N-Log variants.

3. ColorScience resolves the target profile and matrix.

4. Vulkan receives uniforms and LUT decisions for preview/recording parity.

## Core Math

Saturation and hue should be manipulated in perceptual coordinates:

```text
C = sqrt(a^2 + b^2)
```
```text
h = atan2(b, a)
```
```text
C' = C * saturation
```
```text
a' = C' * cos(h)
```
```text
b' = C' * sin(h)
```
Lightness contrast uses a sigmoid:

```text
L' = 1 / (1 + exp(-k * (L - pivot)))
```
Color-space conversion is matrix based:

```text
RGB_out = M_XYZ_to_out * M_adapt * M_in_to_XYZ * RGB_in
```
## Truth Boundary

Creative looks such as Warm Cinema or Bleach Bypass are app looks, not camera-manufacturer profiles.

```text
Vendor profile names should fall back to Neo profiles or be normalized away unless explicitly licensed and implemented.
```
