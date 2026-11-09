# NeoRadiance v2 HELIOS Truth Boundary

HELIOS is a math and delivery upgrade inside NeoProCamX. It does not replace calibrated sensor IDTs, lab spectral characterization, or camera dynamic range limits.

Physically anchored:
- Rec.2020 luminance coefficients.
- ST 2084 PQ constants.
- BT.2100 HLG constants.
- BT.2100 ICtCp matrices.
- 203-nit diffuse white and 1000-nit HDR mastering target.

Aesthetic or project policy:
- Cascade Fold contrast `0.35`, shoulder softness `0.25`, and strength `0.40`.
- Highlight desaturation amount `0.35`.
- Motion confidence rejection threshold `0.5`.
- EMA smoothing alpha `0.15`.

Fallback conditions:
- `pipeline=v1` routes to the legacy NeoRadiance math path for bit-exact regression.
- NaN/Inf or invalid luminance reduces output toward identity or a safe zero-luminance path.
- Missing HDR transport capability selects HLG when available, otherwise SDR Rec.709 with BT.2390-style down-map evidence.
- Motion confidence below `0.5` falls back to the reference exposure sample instead of merging ghosted frames.

No claim in this folder says HELIOS beats cinema cameras on sensor dynamic range. Tests only support the local math gates they execute.
