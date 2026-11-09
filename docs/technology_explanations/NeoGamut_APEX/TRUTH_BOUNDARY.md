# NeoGamut v2 (APEX) Truth Boundary

## Physically Grounded

- CAT16 `M16`, cone-domain degree-of-adaptation scaling, and xy-to-XYZ white-point math.
- Mired interpolation between dual-illuminant sensor matrices.
- Scene-linear AP1 working transform, Oklab/OkLCh grading math, and guarded matrix fallback.
- `epsilon = 1e-6` divide guards and determinant fallback to identity when adaptation is unsafe.

## Aesthetic Look Decisions

- Gamut-compression knee at `85%` of estimated hue boundary.
- Tone-map toe `0.18`, shoulder `0.78`, white point `11.2`, and highlight desaturation `0.35`.
- Memory-color hue guard ranges and the `2 deg` drift limit.
- ML AWB blend weights and `0.15` temporal EMA.

## Fallback Conditions

APEX falls back toward identity or legacy behavior when:

- `pipeline=v1` is present in the selected color space or color science string.
- Any adaptation input is NaN/Inf.
- Source white or target white has invalid `y`.
- CAT16 source cone response is near zero.
- The computed adaptation matrix is non-finite or has `abs(det) < 1e-5`.
- Metadata confidence is low; `D` is multiplied by `metaConfidence`, so confidence `0` yields identity.
- Camera2 dual-illuminant matrices are unavailable; built-in DNG reference CCTs remain the default.
- ML color intelligence is unavailable or late; shader uniforms use the latest fused/stabilized CPU state and never block.

## Claims Not Made

- No claim to replace spectral camera profiling or lab calibration.
- No claim to exceed cinema cameras in sensor dynamic range.
- No Dolby, HDR10+, IMAX, or third-party vendor certification claim.
- No guarantee that every Android encoder/display can preserve the full AP1/APEX intent; unsupported device behavior must be reported as fallback evidence.
