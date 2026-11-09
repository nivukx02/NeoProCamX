# NeoGamut v2 (APEX) - Why These Values Changed

NeoGamut v2 keeps the legacy path behind `pipeline=v1` and adds a selectable
APEX path for the project-owned NeoGamut color pipeline. The validation harness
reports mathematical conformance only; it does not claim spectral lab
calibration or cinema-camera dynamic-range parity.

## Physical Changes

- CAT16 matrix: locked to the standard CIE CAT16 `M16` coefficients:
  `[0.401288, 0.650173, -0.051461; -0.250268, 1.204414, 0.045854; -0.002079, 0.048952, 0.953127]`.
  This removes coefficient drift between CPU policy and shader-facing matrix math.

- Degree of adaptation: replaced the old lux heuristic with the CIECAM16 form:
  `La = lux / (5pi)`, `D = clamp(F * (1 - (1/3.6) * exp((-La - 42) / 92)), 0, 1)`.
  APEX multiplies by metadata confidence so weak sensor metadata pulls adaptation toward identity.

- Cone scaling: adaptation is applied as
  `M16^-1 * diag(D * (rho_t / rho_s) + (1 - D)) * M16`.
  This is the CIECAM16-style cone response operation, not a whole-matrix lerp.

- White targets: APEX decouples source estimation from output target and defaults to D65
  (`x=0.31272`, `y=0.32903`) while retaining D55 and D50 constants.

- Dual illuminants: sensor matrices interpolate in mired space between DNG ColorMatrix1
  at 2856 K and ColorMatrix2 at D65. Camera2 `CameraCharacteristics` transforms are used
  when present.

## Aesthetic Changes

- Gamut compression: OkLCh chroma compression starts at 85% of the estimated hue boundary.
  In-gamut colors stay untouched; out-of-gamut chroma rolls off radially to avoid hue skew.

- Tone map: APEX uses toe `0.18`, shoulder `0.78`, white point `11.2`, and highlight
  desaturation `0.35`. The shader changes lightness while preserving hue/chroma structure.

- Memory colors: skin (`47-55 deg`), sky (`255-270 deg`), and foliage (`130-150 deg`)
  are clamped to no more than `2 deg` hue drift.

- Video AWB stability: ML, sensor AWB, and gray-edge estimates are fused with
  `mlConfidence * 0.6`, `0.3`, and `0.1`, then stabilized with EMA alpha `0.15`.

## Measured Gates

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.color.ColorValidationHarnessTest" --no-daemon --console=plain
```

The harness reports DeltaE2000, neutral `u'v'`, skin hue drift, video frame-to-frame
`u'v'`, and `pipeline=v1` bit-match gates across A/TL84/D50/D65/D75. Those are
the only superiority or correctness claims made here.
