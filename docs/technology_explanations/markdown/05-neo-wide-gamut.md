# Neo WG2 / Neo Wide Gamut

Proprietary D55 wide-gamut working space used by Neo color science.

## Implementation Anchors

- `app/src/main/java/com/neoproc/neoproccamx/color/ColorScience.kt`
- `app/src/main/java/com/neoproc/neoproccamx/color/AcesColorEngine.kt`
- `app/src/main/java/com/neoproc/neoproccamx/color/NeoGamutCat16Math.kt`

## Purpose

Neo WG2 is the corrected project-owned wide-gamut color space for highly saturated colors and D55 cinematic white.

It sits alongside standards such as Rec.709 and Rec.2020 but uses custom primaries and matrices.

## Primaries

CIE 1931 xy primaries:

```text
Red   = (0.7347, 0.2653)
```
```text
Green = (0.1152, 0.8260)
```
```text
Blue  = (0.0860, 0.0110)
```
```text
White = D55 = (0.3324, 0.3474)
```
## Core Math

RGB to XYZ matrix used in source. This matrix is primary-normalized so `[1,1,1]` resolves to D55 XYZ `(0.956822107081, 1.000000000000, 0.921704087507)`.

```text
[X]   [0.774888889  0.098962557  0.082970662] [R]
```
```text
[Y] = [0.279812198  0.709575275  0.010612527] [G]
```
```text
[Z]   [0.000000000  0.050512138  0.871191949] [B]
```
XYZ to Neo matrix used by NeoGamutCat16Math:

```text
[R]   [ 1.355511229 -0.180015705 -0.126903425] [X]
```
```text
[G] = [-0.534992949  1.481565471  0.032903847] [Y]
```
```text
[B]   [ 0.031019155 -0.085901896  1.145944883] [Z]
```
A color is converted to a target space by:

```text
RGB_target = M_XYZ_to_target * M_adapt(D55,targetWhite) * M_neo_to_XYZ * RGB_neo
```
CAT16 is the shipping chromatic adaptation path. Bradford is exposed for validation and comparison.

## Truth Boundary

Neo Wide Gamut is a project-owned color space, not a certification program.

The source maps P3/ACES style user selections into Neo Wide Gamut as an internal working route where appropriate.
