# NDR10+ / NIVUKX Vision 144-Patch Calibration

Use the 18 PNG files in `deliverables/ndr_nivukx_144/`.
Each page has 8 large color patches. Capture every page in the same framing and lighting.

Required sequence per page:
1. Normal SDR reference capture.
2. NDR10+ capture.
3. NIVUKX Vision capture.

Naming convention:
- `NORMAL_144_PAGE_01`
- `NDR10_144_PAGE_01`
- `NIVUKX_144_PAGE_01`
- Continue through PAGE_18.

Rules:
- Display page full-screen.
- Keep device, display brightness, and ambient light fixed.
- Disable adaptive brightness, eye comfort/night light, and vivid display modes.
- Tap focus on the page focus target before each recording.
- Keep all four registration corners visible.

Reference JSON: `deliverables/ndr_nivukx_144/ndr_nivukx_144_reference.json`.
