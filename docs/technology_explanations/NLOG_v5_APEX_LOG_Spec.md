# N-LOG v5 APEX-LOG

N-LOG v5 APEX-LOG is the NeoProCamX scene-referred logarithmic transfer family. It is original project IP: the curve is defined only by the first-principles constraints below and the solved constants in this file.

## Shared Contract

- Black: `x = 0 -> y = 0`
- Middle gray: `x = 0.18 -> y = 0.50`
- Domain: scene-linear `x >= 0`
- Encoded range: normalized `y in [0, 1]`
- Dynamic range evidence: `DEVICE_MEASUREMENT_REQUIRED`

The logarithmic body is:

```text
toeHeight = toeCut * toeSlope
grayLog   = ln(1 + logDensity * (0.18 - toeCut))
logScale  = (0.50 - toeHeight) / grayLog

y = toeHeight + logScale * ln(1 + logDensity * (x - toeCut))
```

The body inverse is:

```text
x = toeCut + (exp((y - toeHeight) / logScale) - 1) / logDensity
```

## C2 Quintic Toe

For `0 <= x < toeCut`, let `t = clamp(x / toeCut, 0, 1)`.

```text
Q(t) = c1*t + c3*t^3 + c4*t^4 + c5*t^5
```

No `t^2` term is used because `Q''(0)=0`, which gives a clean linear black onset.

With endpoint constraints:

```text
p0 = 0
v0 = toeCut * toeEntrySlope
a0 = 0
p1 = toeHeight
v1 = toeCut * m1
a1 = toeCut^2 * m2
m1 = logScale * logDensity
m2 = -logScale * logDensity^2
```

The solved coefficients are:

```text
c1 = v0
c3 = -6*v0 + 10*p1 - 4*v1 + 0.5*a1
c4 =  8*v0 - 15*p1 + 7*v1 - a1
c5 = -3*v0 +  6*p1 - 3*v1 + 0.5*a1
```

The toe inverse is unique because the solved toe is strictly monotonic. The implementation uses bounded Newton iteration with bisection fallback so it is stable near black and near `toeCut`.

## Exact Variant Table

| Variant | toeCut | toeSlope | toeEntrySlope | logDensity | toeHeight | logScale | xMax |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| BASE | 0.010 | 8.0 | 8.0 | 120 | 0.080000 | 0.137099 | 6.843034 |
| SHADOW | 0.004 | 10.0 | 10.0 | 70 | 0.040000 | 0.177657 | 3.164296 |
| HIGHLIGHT | 0.005 | 12.0 | 12.0 | 260 | 0.060000 | 0.114598 | 14.041075 |
| CINEMA | 0.008 | 10.0 | 10.0 | 110 | 0.080000 | 0.140385 | 6.377367 |

| Variant | m1 | m2 | c1 | c3 | c4 | c5 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| BASE | 16.451880 | -1974.2256 | 0.080000 | -0.436788 | 0.789056 | -0.352269 |
| SHADOW | 12.435990 | -870.5193 | 0.040000 | -0.045940 | 0.082136 | -0.036196 |
| HIGHLIGHT | 29.795480 | -7746.8248 | 0.060000 | -0.452743 | 0.816510 | -0.363766 |
| CINEMA | 15.442350 | -1698.6585 | 0.080000 | -0.228513 | 0.413487 | -0.184974 |

## Quantization

All bit depths use one parameterized quantizer:

```text
codeN = round_dither(clamp(y, 0, 1) * (2^N - 1))
N in {10, 12, 16}
```

TPDF dither is applied in code-value space and bounded to half a code step for deterministic encode/decode testing.

## Honesty Caveat

`xMax` is the scene-linear value that maps to code ceiling for the solved analytic curve. It is not a certified camera stop count. Certified dynamic range must be measured on device using the project validation harness and reported as `DEVICE_MEASUREMENT_REQUIRED` until that evidence exists.
