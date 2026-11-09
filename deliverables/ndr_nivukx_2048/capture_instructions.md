# NDR10+ / NIVUKX Vision 2048-Patch Elite Calibration

- 2048 measured colors split into 256 pages with 8 patches per image.
- Use this after the 144-patch quick target passes.
- For offline simulation, run `tools/calibration/offline_ndr_nivukx_tone_simulator.py --reference app/src/main/assets/calibration/ndr_nivukx_2048/ndr_nivukx_2048_reference.json --max-edge 720`.
- For live camera capture, show each page full-screen with fixed display brightness and disabled display enhancement.
- Measure only the inner sample boxes described by `sample_bounds_normalized`.
