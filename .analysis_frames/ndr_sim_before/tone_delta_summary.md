# NDR/NIVUKX Offline Tone Delta Summary

Generated: 2026-07-24T15:10:36

This is a CPU approximation of the realtime shader tone path applied to calibration PNGs.
It isolates tone math only; it does not include camera sensor AWB, local texture sampling, device display conversion, or encoder metadata.

## NDR10
- FOLIAGE: dR=+0.0149, dG=+0.0270, dB=+0.0704, dY=+0.0276, hue=12.52 deg, flags={}
- HIGHLIGHT: dR=-0.1514, dG=-0.0421, dB=+0.0254, dY=-0.0605, hue=56.38 deg, flags={'highlight_channel_imbalance': 20}
- NEUTRAL: dR=+0.0058, dG=+0.0511, dB=+0.0781, dY=+0.0434, hue=120.41 deg, flags={}
- SATURATED_BLUE: dR=+0.0264, dG=+0.0235, dB=+0.1167, dY=+0.0309, hue=2.86 deg, flags={}
- SATURATED_CYAN: dR=+0.1815, dG=-0.0048, dB=+0.0387, dY=+0.0379, hue=7.77 deg, flags={}
- SATURATED_GREEN: dR=+0.3386, dG=-0.1705, dB=+0.5794, dY=-0.0081, hue=55.22 deg, flags={}
- SATURATED_ORANGE: dR=-0.2559, dG=+0.0918, dB=+0.2020, dY=+0.0259, hue=9.53 deg, flags={}
- SATURATED_RED: dR=-0.2171, dG=+0.0372, dB=+0.0470, dY=-0.0161, hue=9.40 deg, flags={'warm_color_red_overpush': 1}
- SATURATED_YELLOW: dR=-0.0234, dG=+0.1006, dB=+0.3519, dY=+0.0924, hue=4.80 deg, flags={}
- SHADOW: dR=+0.1751, dG=+0.1757, dB=+0.1871, dY=+0.1764, hue=32.99 deg, flags={}
- SKIN: dR=-0.0573, dG=+0.0631, dB=+0.0648, dY=+0.0376, hue=6.78 deg, flags={'skin_red_push': 1, 'skin_hue_shift_gt_5deg': 10}
- SKY: dR=+0.0305, dG=+0.0532, dB=+0.0187, dY=+0.0459, hue=7.48 deg, flags={'sky_red_green_high_or_blue_low': 10}
- TEXTURE: dR=-0.0142, dG=+0.0299, dB=+0.0697, dY=+0.0234, hue=63.10 deg, flags={}

- NDR10: sky role still trends warm/cyan in offline tone math; tighten sky hue guard or reduce highlight neutral trim before live-camera retest.
- NDR10: skin role shifts red/hue in offline tone math; strengthen measured skin hue guard after knee curve.
- NDR10: highlight patches show channel imbalance; keep highlight balance guard active before final color guard.

## NIVUKX
- FOLIAGE: dR=+0.0360, dG=+0.0517, dB=+0.0886, dY=+0.0511, hue=14.58 deg, flags={}
- HIGHLIGHT: dR=-0.1237, dG=-0.0253, dB=+0.0242, dY=-0.0427, hue=64.53 deg, flags={'highlight_channel_imbalance': 19}
- NEUTRAL: dR=+0.0337, dG=+0.0762, dB=+0.0995, dY=+0.0689, hue=121.05 deg, flags={}
- SATURATED_BLUE: dR=+0.0409, dG=+0.0370, dB=+0.1495, dY=+0.0460, hue=2.72 deg, flags={}
- SATURATED_CYAN: dR=+0.2373, dG=+0.0438, dB=+0.0688, dY=+0.0867, hue=6.39 deg, flags={}
- SATURATED_GREEN: dR=+0.3319, dG=-0.1826, dB=+0.5622, dY=-0.0194, hue=57.69 deg, flags={}
- SATURATED_ORANGE: dR=-0.2467, dG=+0.1216, dB=+0.2368, dY=+0.0516, hue=11.36 deg, flags={}
- SATURATED_RED: dR=-0.2167, dG=+0.0412, dB=+0.0598, dY=-0.0123, hue=10.10 deg, flags={'warm_color_red_overpush': 1}
- SATURATED_YELLOW: dR=+0.0010, dG=+0.1324, dB=+0.3807, dY=+0.1224, hue=6.55 deg, flags={}
- SHADOW: dR=+0.1941, dG=+0.1955, dB=+0.2069, dY=+0.1960, hue=33.16 deg, flags={}
- SKIN: dR=-0.0389, dG=+0.0929, dB=+0.0969, dY=+0.0651, hue=17.36 deg, flags={'skin_red_push': 1, 'skin_hue_shift_gt_5deg': 12}
- SKY: dR=+0.0685, dG=+0.0722, dB=+0.0179, dY=+0.0675, hue=9.38 deg, flags={'sky_red_green_high_or_blue_low': 10}
- TEXTURE: dR=+0.0131, dG=+0.0601, dB=+0.0990, dY=+0.0529, hue=64.74 deg, flags={}

- NIVUKX: sky role still trends warm/cyan in offline tone math; tighten sky hue guard or reduce highlight neutral trim before live-camera retest.
- NIVUKX: skin role shifts red/hue in offline tone math; strengthen measured skin hue guard after knee curve.
- NIVUKX: highlight patches show channel imbalance; keep highlight balance guard active before final color guard.
