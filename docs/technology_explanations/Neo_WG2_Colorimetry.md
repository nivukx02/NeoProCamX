# Neo WG2 Colorimetry

Neo WG2 is the project-owned D55 scene-linear working gamut used by NeoProCamX. It is derived only from the published Neo primaries and D55 white point:

- Red: x=0.7347, y=0.2653
- Green: x=0.1152, y=0.8260
- Blue: x=0.0860, y=0.0110
- White: D55 x=0.3324, y=0.3474, XYZ=(0.956822107081, 1.000000000000, 0.921704087507)

## RGB to XYZ

Row-major, scene-linear:

```text
[ 0.774888888722  0.098962556523  0.082970661836 ]
[ 0.279812198418  0.709575275068  0.010612526514 ]
[ 0.000000000000  0.050512138225  0.871191949282 ]
```

White residual:

```text
M * [1,1,1] = (0.956822107081, 1.000000000000, 0.921704087507)
target D55  = (0.956822107081, 1.000000000000, 0.921704087507)
```

## XYZ to RGB

Analytic inverse:

```text
[  1.355511229305  -0.180015704859  -0.126903425212 ]
[ -0.534992948588   1.481565470514   0.032903846515 ]
[  0.031019154609  -0.085901895556   1.145944882961 ]
```

Unit tests gate inverse round-trip with Frobenius residual below 1e-6.

## Adaptation

The shipping adaptation matrix path is CAT16, exposed through `NeoGamutCat16Math.calculateCat16Matrix`. Bradford is also exposed for validation and comparison. Both are tested to map D55 white to D65 white within 1e-6.

## Coverage

Measured as CIE xy primary-triangle coverage, not as a certification claim:

| Target | Target Covered by Neo WG2 | Neo WG2 Area vs Target |
|---|---:|---:|
| Rec.709 / sRGB | 100.00% | 232.60% |
| DCI-P3 D65 | 99.35% | 171.47% |
| Rec.2020 | 97.06% | 123.02% |
| ACEScg-like AP1 | 93.04% | 115.68% |

Neo WG2 preserves negative scene-linear components through the linear pipeline and resolves out-of-gamut values only at the output gamut-map stage.
