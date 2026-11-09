# NDR/NIVUKX Offline Tone Delta Summary

Generated: 2026-07-24T15:10:37

This is a CPU approximation of the realtime shader tone path applied to calibration PNGs.
It isolates tone math only; it does not include camera sensor AWB, local texture sampling, device display conversion, or encoder metadata.

## NDR10
- FOLIAGE: dR=+0.0115, dG=+0.0131, dB=+0.0359, dY=+0.0144, hue=4.50 deg, flags={}
- HIGHLIGHT: dR=-0.1788, dG=-0.0754, dB=-0.0054, dY=-0.0923, hue=0.27 deg, flags={'highlight_channel_imbalance': 1}
- NEUTRAL: dR=+0.0926, dG=+0.1120, dB=+0.1186, dY=+0.1083, hue=164.64 deg, flags={}
- SATURATED_CYAN: dR=+0.1237, dG=+0.0433, dB=+0.0699, dY=+0.0623, hue=2.56 deg, flags={}
- SATURATED_RED: dR=-0.2304, dG=+0.0275, dB=+0.0391, dY=-0.0265, hue=0.83 deg, flags={}
- SKIN: dR=-0.0684, dG=+0.0391, dB=+0.0496, dY=+0.0170, hue=8.24 deg, flags={'skin_hue_shift_gt_5deg': 1}
- SKY: dR=+0.0110, dG=+0.0364, dB=+0.0280, dY=+0.0304, hue=1.80 deg, flags={'sky_red_green_high_or_blue_low': 1}
- TEXTURE: dR=-0.0165, dG=+0.0271, dB=+0.0556, dY=+0.0199, hue=156.27 deg, flags={}

- NDR10: sky role still trends warm/cyan in offline tone math; tighten sky hue guard or reduce highlight neutral trim before live-camera retest.
- NDR10: skin role shifts red/hue in offline tone math; strengthen measured skin hue guard after knee curve.
- NDR10: highlight patches show channel imbalance; keep highlight balance guard active before final color guard.

## NIVUKX
- FOLIAGE: dR=+0.0239, dG=+0.0400, dB=+0.0480, dY=+0.0371, hue=4.50 deg, flags={}
- HIGHLIGHT: dR=-0.1382, dG=-0.0594, dB=-0.0084, dY=-0.0725, hue=0.32 deg, flags={'highlight_channel_imbalance': 1}
- NEUTRAL: dR=+0.1127, dG=+0.1335, dB=+0.1407, dY=+0.1296, hue=164.65 deg, flags={}
- SATURATED_CYAN: dR=+0.2048, dG=+0.1264, dB=+0.1228, dY=+0.1428, hue=0.22 deg, flags={}
- SATURATED_RED: dR=-0.2301, dG=+0.0282, dB=+0.0397, dY=-0.0259, hue=0.82 deg, flags={}
- SKIN: dR=-0.0549, dG=+0.0603, dB=+0.0683, dY=+0.0364, hue=9.87 deg, flags={'skin_hue_shift_gt_5deg': 1}
- SKY: dR=+0.0271, dG=+0.0663, dB=+0.0513, dY=+0.0569, hue=2.82 deg, flags={'sky_red_green_high_or_blue_low': 1}
- TEXTURE: dR=+0.0132, dG=+0.0591, dB=+0.0836, dY=+0.0511, hue=159.15 deg, flags={}

- NIVUKX: sky role still trends warm/cyan in offline tone math; tighten sky hue guard or reduce highlight neutral trim before live-camera retest.
- NIVUKX: skin role shifts red/hue in offline tone math; strengthen measured skin hue guard after knee curve.
- NIVUKX: highlight patches show channel imbalance; keep highlight balance guard active before final color guard.
