# NeoRadiance v2 HELIOS WHY

NeoRadiance v2 HELIOS keeps the current v1 path behind `pipeline=v1` and adds a separate scene-linear Rec.2020 HDR path.

Changed values:
- Diffuse white is `203 nits` and peak is `1000 nits` to anchor HDR math in absolute display light.
- HELIOS luminance uses Rec.2020 coefficients `0.2627, 0.6780, 0.0593`; Rec.709 coefficients remain only on legacy and SDR output paths.
- Cascade Fold now runs on luminance only, then scales RGB by `Y_out / Y`, preserving hue ratios.
- Shadow knee at `0.08` blends from lifted toe to linear using `smoothstep(0.06, 0.10, x)` to remove slope discontinuity.
- PQ constants use the exact ST 2084 values from the implementation contract.
- HLG constants use the BT.2100 values from the implementation contract.
- ICtCp uses the BT.2100 Rec.2020-to-LMS and LMS-to-ICtCp matrices from the implementation contract.
- Multi-frame radiance merge weights exposure samples with `1 - (2z - 1)^12` and rejects moving/ghosted samples below `0.5` confidence.
- Temporal brightness smoothing uses EMA alpha `0.15`.

Measured by `RadianceValidationHarness`:
- ICtCp hue drift gate: `<= 2 degrees`
- PQ 203-nit round-trip gate: `<= 1%`
- Shadow knee slope discontinuity gate: `<= 0.12`
- Synthetic merge SNR gate: `>= +6 dB` for four aligned frames
- Brightness pumping gate: `<= 1%`

These are deterministic math gates, not camera-lab claims.
