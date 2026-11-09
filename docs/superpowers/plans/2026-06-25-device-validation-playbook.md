# Device-Validation Playbook — vivo I2405 / Adreno

The remaining elite-upgrade work is gated on **on-device validation** (no device in the build
environment). This playbook turns "device-gated" into a concrete, ordered checklist so the owner's
on-device session is fast and unambiguous.

**How to use:** go top to bottom. **Tier 1** is already live on this branch — just *observe and
confirm*. **Tier 2** needs me to land a guarded (default-off) implementation first; you flip the flag,
validate, and if it passes I make it default-on in a follow-up commit. **Pass bar everywhere = "no
downgrade vs current, and visibly better or equal."** If an item regresses anything, mark FAIL and I
revert/retune — never ship a downgrade.

Build for device test:
`JAVA_HOME=".../Android Studio/jbr" ./gradlew :app:assembleDebug -Pandroid.ndkPath=".../ndk/29.0.14206865"`

General A/B method: record the **same scene twice** (tripod), once on `main`/baseline, once on this
branch (or feature-on vs feature-off), then compare on a calibrated monitor + scrub frame-by-frame.

---

## TIER 1 — already live this session; observe & confirm (no code change)

### V1. CP52 — geometrically honest Chronos warp  ✅ live, default-on (preview + record)
- **What changed:** removed fabricated gyro/Einstein/geodesic distortions from the motion-compensated
  temporal warp; the backward warp is now `source = p − flow` (the true optical-flow correspondence).
- **Test:** pan across a textured, high-detail scene (foliage, brick) at moderate speed, handheld and
  on tripod. Also a person walking across frame.
- **PASS:** moving edges are **sharper / less smeared** than baseline; no new ghosting or wobble on
  moving subjects; static areas unchanged. **FAIL:** any new smear/ghost/warble.

### V2. CP53 — iterative Riven deblur, `rlIterations` sweep  ✅ live; default = 1 (no-op)
- **What to do:** the default `rlIterations = 1` is bit-identical to before. To evaluate the upgrade,
  raise it. In `app/src/main/cpp/vulkan_bridge.cpp` `struct RivenDeblurPushConstants`, set
  `rlIterations = 2` (then 3), rebuild, record motion-blur scenes (fast pan, moving car, hand wave).
- **PASS:** motion-blurred edges recover **more detail** with **no ringing/halo** and **no amplified
  noise** vs `rlIterations = 1`. Pick the highest count that still passes; tell me the value and I set
  it as the default. **FAIL at N:** keep the previous N (or 1).

### V3. CP40-4A — SNCE scene-change WB reset  ✅ live, default-on
- **What changed:** white balance now snaps on hard scene cuts instead of slowly crawling.
- **Test:** while recording, cut hard between very different lighting (indoor tungsten → point at a
  window / daylight), and separately do gentle pans.
- **PASS:** on a **hard cut**, WB re-settles **fast** (≈instant) and cleanly; on **gentle motion**,
  WB stays **stable** (no nervous flicker). **FAIL:** WB jumps around during normal panning (too
  sensitive → tell me to lower `SnceV2Engine.SCENE_CHANGE_GAIN` from 4).

---

## TIER 2 — I land guarded code first, then you validate (ordered by value × confidence)

> For each: I add the implementation behind a **default-off flag** (so it ships dark, zero risk), you
> enable it on device, validate against the pass bar, and I make it default-on only on PASS.

### V4. CP33 — wire `ndr10_knee_curve` peak-nits highlight knee   (HDR highlights)
- **State:** the knee-curve compute shader exists + SPV is compiled but is **not dispatched**; the live
  tone map is SDR-referred (clamps highlights ~0.92).
- **I land:** the dispatch of `ndr10_knee_curve.comp` before encode, behind `ndr10KneeEnabled` (off).
- **Test:** high-dynamic-range scenes — sky + ground, specular highlights, backlit subject, candle.
- **PASS:** highlights **roll off smoothly** with recovered detail instead of clipping flat to white;
  no banding in gradients; midtones/skin unchanged. **FAIL:** banding, dull/greyed highlights, or any
  midtone shift.

### V5. CP40 — exact Oklab gamut cusp upload   (saturated-colour accuracy)
- **State:** exact cusp + gamut clip are implemented & unit-proven on CPU (`OklabGamutCusp` /
  `OklabGamutMapping`); the live shader still uses the analytic `cos(3h)+cos(6h)` boundary guess.
- **I land:** upload `cuspLut()` as a 1D LUT and sample it in the gamut-compression shader
  (`neo_p010_export_enhance.comp:1168`), behind `exactGamutCuspEnabled` (off). (Needs the wide-gamut
  primaries variant — I'll generate the LUT for the app's actual target gamut, not just sRGB.)
- **Test:** highly saturated subjects — red flowers, neon, saturated blue sky, green foliage, skin.
- **PASS:** saturated colours hold **hue** while compressing (no hue twist toward orange/purple at the
  boundary); smoother than the analytic guess; skin unaffected. **FAIL:** any hue shift or posterized
  boundary.

### V6. CP10c — RAW debayer dispatch   (real 12-bit RAW reconstruction)
- **State:** the demosaic pipeline is **created but never dispatched** (RAW preview silently uses the
  YUV ISP chain). **Critical:** must use the CP11 forward matrices
  (`buildSensorToXyzD50FromForward` → `xyzD50ToAcesAp1Matrix`), NOT the latent inverted
  `readDualIlluminantSensorMatrices` path (that would color-cast).
- **I land:** RAW R16 import + UBO (CP11 matrices) + demosaic dispatch + YUV fallback, behind
  `rawDebayerEnabled` (off).
- **Test:** RAW mode on a ColorChecker / neutral grey card under daylight and tungsten; fine-detail
  resolution chart.
- **PASS:** neutral patches stay **neutral** (no cast) and colours match a reference (ΔE small) vs the
  ISP path; **more real detail / less ISP smudging**. **FAIL:** any colour cast on neutrals (→ matrix
  path wrong) or softer detail.

### V7. CP12 — MFSR multi-frame fusion + the uninitialized-memory fix
- **State:** MFSR is dispatched but reads **uninitialized device memory** (dummy bindings) — an active
  hazard. STEP A = deterministically clear the dummies (remove UB). STEP B = real N-frame RAW ring +
  per-frame-indexed flow + sensor noise model.
- **I land:** STEP A first (clear-to-defined), behind nothing if you confirm `intermediate` is not in
  the visible path, else behind a flag; then STEP B behind `mfsrFusionEnabled` (off).
- **Test:** low-light static scene on tripod (multi-frame SNR), then with subject motion (ghosting
  check).
- **PASS:** static low-light shows **lower noise / more detail**; moving subjects show **no ghosting**
  (the veto mask works). **FAIL:** ghosting, oversharpening, or black/garbage frames.

### V8. CP60–65 — gyro-EIS GPU push   (true electronic stabilization)
- **State:** full EIS math stack is built & unit-proven on CPU (`stabilization/*`: quaternion
  integrator, smoother, rolling-shutter grid, OIS fusion, mode policy). The **GPU push** is unwired.
- **I land:** gyro↔`SENSOR_TIMESTAMP` reconciliation + warp-grid push to the warp shader, behind
  `gyroEisEnabled` (off), with a bounded crop margin and STANDARD mode.
- **Test:** walk-and-record, handheld pan, and a stationary tripod control. **The critical risk** is
  clock-domain alignment — watch for *wrong-way* warping.
- **PASS:** handheld footage is **steadier** with no swimming/jello and the tripod control is
  **unchanged** (crop only); rolling-shutter skew on fast pans is reduced. **FAIL:** any swimming,
  jello, wrong-way warp, or tripod-control degradation → I retune the timestamp offset.

### V9. CP80–82 — NPV10 export parity
- **State:** NPV10 master export uses a separate CPU P010 fallback path — it does **not** run the GPU
  OmniClarity/SNCE stack, so the master can diverge from preview/record (not WYSIWYG).
- **I land:** route the export through the same GPU stack (or a math-equivalent path), behind
  `npv10GpuParityEnabled` (off).
- **Test:** export the same take via NPV10 and compare against the recorded file frame-by-frame.
- **PASS:** export matches preview/record (same warp/colour/tone); **FAIL:** any visible divergence.

---

## Reporting back
For each item, tell me **PASS / FAIL** and, on FAIL, *what* regressed (one line). On PASS I land the
default-on follow-up commit; on FAIL I retune or revert. We complete the program one validated
checkpoint at a time — never shipping a downgrade.
