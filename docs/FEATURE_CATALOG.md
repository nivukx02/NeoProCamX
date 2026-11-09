# NeoProCamX — Complete Feature Catalog

> Companion to [`PROJECT_ANALYSIS.md`](./PROJECT_ANALYSIS.md). Every feature and sub-feature across all
> 8 subsystems, with its **working level**, **feature rating** (value if fully working, 1–10), and
> **code quality** (1–10). Verified against source `file:line`.

**Working-level legend:**
- 🟢 **live-default-on** — runs in the shipping app by default
- 🔵 **live-optional** — runs live when its mode/flag is selected
- 🟡 **partial** — partly wired; some sub-features inert
- 🟠 **dormant** — built (often well) but not wired into the running image path
- ⚪ **test-only-scaffold** — exercised only by tests / has no production caller

**Distribution (≈82 catalogued features):** 🟢🔵 live ≈ 40–45% · 🟡 partial ≈ 10% · 🟠⚪ dormant/test-only ≈ 50%.

---

## 1. Capture, RAW & Multi-frame — quality 7/10 · working: mixed

*Live capture backbone; dormant computational-RAW/multi-frame layer.*

### 🔵 RAW video/still capture & serialization (DNG + NPRAW) · feat 8 · quality 8
- Native `DngCreator` DNG from `RAW_SENSOR` Image + `TotalCaptureResult`, cinema metadata, GPS
- `RawVideoManager` — parallel RAW frame capture on the existing session, bounded write queue, dedicated writer thread, backpressure/frame-drop
- NPRAW compressed stream: predictive (left/top/diagonal) encode, zigzag+varint, adaptive quantization, packed-12/16-bit LE, deflate; per-frame CRC/black/white/DR/noise header
- `AsyncDirectIOEngine` streaming + MediaStore scoped storage; per-area capture profile tuning
- Software RAW fallback package when native RAW unavailable

### 🔵 ISP bypass for sensor-native capture · feat 8 · quality 9
- Linear 1:1 tonemap; EDGE/NOISE_REDUCTION/HOT_PIXEL/SHADING/COLOR_ABERRATION off
- Distortion-correction policy (sensor-native vs corrected)
- Capability gating via Camera2 characteristics (per-feature available-modes check) + user warning on incomplete bypass

### 🔵 RAW sensor calibration & noise model policies · feat 7 · quality 8
- Black level: dynamic per-frame metadata › static `BLACK_LEVEL_PATTERN` › scaled fallback; white-level clamp
- Shot/read noise coefficients from `SENSOR_NOISE_PROFILE` fed into the RAW buffer descriptor

### 🟡 OIS / gyro motion telemetry capture · feat 5 · quality 7
- Real `TYPE_GYROSCOPE` listener (`SENSOR_DELAY_FASTEST`) into a 1024-deep ring buffer
- `STATISTICS_OIS` sample + `LENS_OPTICAL_STABILIZATION_MODE` handling
- s1-f04: telemetry now **source-tagged** (gyro vs OIS clock domain) + timestamp-reconciled hand-off — `GyroExposureWindowReconciler` (camera `SENSOR_TIMESTAMP` ↔ gyro clock, exposure-window orientation), `GyroGlobalMotionTerm` (MFSR per-frame global-motion term, OIS-fused), `GyroRingHealth` (monotonicity/gap invariant); 22 JVM tests
- *Capture unchanged (golden-locked); consumer hand-offs DORMANT/flagged off (`gyroEisTelemetryConsumerEnabled`) — DEVICE-GATED gyro↔`SENSOR_TIMESTAMP` clock + OIS sign/scale calibration before any consumer goes live*

### 🟢 Camera2 session / stream configuration & capture orchestration · feat 8 · quality 8
- Single capture session with preview + record + RAW reader targets; stream-use-case policies
- Per-mode stream plan, dynamic-range profile (SDR/HLG10/HDR10) session rebuild on mode switch
- Capability inventory, HAL profile, session recovery/refresh

### 🟠 MFSR multi-frame super-resolution fusion (`mfsr_fusion.comp`) · feat 2 · quality 7
- Anisotropic steering-kernel regression (structure tensor, Mahalanobis weight); heteroscedastic noise + 3σ ghost-veto; per-frame flow+gyro alignment, Bayer demosaic-during-fusion
- *Dispatched every frame on **dummy** input array + dummy flow; real seed in never-called `unusedRealtimeComputeDiagnostics()`*

### 🟠 Vulkan cinema debayer preview (`cinema_debayer.comp`) · feat 2 · quality 7
- Adaptive directional interpolation + highlight reconstruction + per-channel WB (AHD-lite); activation gated to VIDEO+RAW
- *Submits a generic AHB import; the debayer shader itself is never dispatched (RAW import "deferred to CP10c")*

### 🟠 Standalone Vulkan demosaic pipeline (`demosaic_vulkan.comp`) · feat 1 · quality 6
- 12-bit RAW edge-directed demosaic → ACEScg compute pipeline *created and destroyed but never dispatched*

### 🟠 Sensor → XYZ → ACEScg color matrices (`NeoGamutApexPipeline`) · feat 3 · quality 8
- Correct DNG ForwardMatrix path (mired interpolation, CAT16 D50→D55→NeoWideGamut→AP1); dual-illuminant CCT read
- Older ColorMatrix path self-documented as inverted/dormant; *both have only a validation-harness caller*

### 🟠 Multi-exposure HDR fusion shaders (`dual_gain_fusion.comp`, `photon_hdr_fusion.comp`) · feat 2 · quality 6
- Dual-gain/photon merge policies + shaders compiled to SPIR-V; *never built into pipelines*

---

## 2. Color science + SNCE engine — quality 8/10 · working: mixed

*Live LUT/uniform grade + bounded SNCE nudge; the rich perceptual machinery is dormant.*

### 🔵 ColorScience preview/record 3D-LUT grade (creative look) · feat 8 · quality 8
- CPU-baked 3D LUT (`applyActiveColorPipeline` per cell), cached by config version/size; uploaded to GPU
- Profile looks, skin-tone-protected creative LUT, filmic curve, hue/sat/contrast/highlight/shadow grade
- LUT nulled in neutral/WYSIWYG mode

### 🟡 AcesColorEngine per-pixel refinement (ACEScg gamut + NDR10 tonemap + NivukxVision + film density) · feat 7 · quality 8
- `compressNeoWideGamutRgb`, `applyNdr10DynamicToneMap`, `applyNivukxVisionRgb`, `applySubtractiveFilmDensity`, highlight rolloff — baked into the preview LUT
- *Conditional: high-end & non-log & non-standards profile*

### 🟢 VulkanColorScienceUniforms (52-float ACEScg/Oklab matrices + HDR/NeoGamut params) GPU upload · feat 8 · quality 8
- ACEScg↔Oklab mat4s; HDR knee/shoulder/volume-compression; NeoGamut V1/V2-APEX mode; measured skin/sky corrections
- Uploaded as preview + encoder color-science with parity policy every render

### 🔵 ColorDriftGovernor temporal white-point stabilizer · feat 6 · quality 7
- Per-frame drift budget, clamps/rescales AWB gains; engaged only under the drift-enforcement flag + lockable-auto SDR

### 🟢 SNCE v2 live colour controller (residual-WB + Hunt-saturation nudge) · feat 6 · quality 8
- Default-on; analyzes camera YUV thumbnail (preview) + P010 (export); nudges grade slots 1/4/6, hard-clamped ±0.05 WB / ±0.06 sat
- *Extracts only 3 scalars; the full engine output is computed then discarded*

### 🟠 SNCE 23-float scene-brain (`SnceFrameConstants`) · feat 3 · quality 8
- Per-frame chain: IMU prior → 9-method illuminant → adaptive EWMA → CAM16 viewing conditions; real frame-to-frame scene-change score
- *Packed "for upload to the Vulkan colour pipeline" — never uploaded*

### 🔵 SNCE Stage-04 OCCS opponent lateral-inhibition GPU pass · feat 5 · quality 7
- `occs_opponent.comp` dispatched (forward DoG iGain=chromaGain=0.10, zero-gain copy-back); *params are compile-time constants, not driven by the scene-brain*

### 🟠 SNCE Stage-11 NRLI neural real-time 3D-LUT GPU pass · feat 3 · quality 7
- `nrli_lut_apply.comp` + identity 33³ LUT dispatched at strength 0 = exact passthrough; *activates only when a model LUT is loaded — none shipped*

### ⚪ SNCE learned-model registry (illuminant/segmentation/adaptive-LUT/face-skin hooks) · feat 3 · quality 7
- 4 hooks all default null; `isFullyClassical` true; expected model specs listed but *no .tflite binaries shipped*

### 🟡 SnceIlluminantEstimator (9-method fusion) + SnceCam16 appearance chain · feat 6 · quality 9
- 7 classical methods + IMU/CNN priors, confidence-weighted fusion; per-pixel CAM16 forward/inverse + appearance phenomena
- *Exercised live via processFrame, but only AWB gains escape to the grade*

### 🟠 OklabGamutCusp + OklabGamutMapping (exact cusp / hue-preserving clip) · feat 3 · quality 9
- Ottosson exact max-saturation + Halley cusp; per-hue cusp LUT; `gamutClipPreserveChroma` — pure, JVM-tested
- *Intended to replace the live shader's analytic cos(3h) guess; wiring is device-gated*

### ⚪ ColorValidationHarness + NeoGamutApexPipeline.processForOutput · feat 2 · quality 8
- ΔE2000 / neutral-Duv / skin-hue / frame-to-frame validation + full per-pixel APEX chain; *referenced only by its own test*

---

## 3. Motion (OmniClarity) + Stabilization (gyro-EIS) — quality 9/10 · working: mixed

*Motion stack live & default-on; the custom gyro-EIS stack is dormant pure-Kotlin math.*

### 🟢 Argus Hybrid Optical Flow (§2) · feat 8 · quality 9
- Stage-A coarse-to-fine block matching (Lowe distinctiveness + texture gating, parabola sub-pixel); forward + backward for consistency
- Stage-B variational Horn-Schunck/Jacobi refine, 8-iteration ping-pong, edge-aware anisotropic smoothness
- Occlusion + hybrid selection (refined-where-consistent, coarse fallback); 1/2-res downsample + flow upsample

### 🟢 Chronos motion-compensated temporal warp (`warp_reconstruct.comp`) · feat 7 · quality 9
- Backward warp `source = p − flow(p)` (CP52 geometrically honest); confidence-gated fusion (color + local-variance + fwd/bwd consistency + occlusion + boundary feather)
- Bounded sub-pixel detail-recovery blend; Kalman-Bucy temporal accumulator (6 iters); frame-0/scene-cut history gating

### 🟢 Riven motion deblur (`riven_motion_deblur.comp`) · feat 7 · quality 9
- Per-pixel 1D line-kernel deconvolution along motion direction; Wiener-style noise regularization + length gate; 16-tap line integral
- CP53 clamp-bounded iterative (van Cittert) deconvolution (`rlIterations` default 1); pass-through below min kernel

### ⚪ OmniClarity Kotlin tuning policies (Argus/Chronos/Riven) · feat 2 · quality 8
- Thermal/budget Stage-B drop, scene-cut alpha gating, noise-aware deblur strength — *referenced only by their own tests; live shaders run on hardcoded C++ defaults*

### 🟠 Gyro orientation integration (Quaternion + GyroOrientationIntegrator) · feat 2 · quality 9
- Hamilton quaternion (slerp/rotation-vector/axis-angle/exp map); trapezoidal angular-velocity integration; `rotationBetween()` with interpolated exposure-window endpoints — *zero production callers*

### 🟠 EIS warp + trajectory smoothing + rolling-shutter + OIS/EIS fusion + mode policy · feat 2 · quality 9
- `GyroEisWarp` (pinhole rotation→dx/dy/roll + crop clamp); `OrientationTrajectorySmoother` (one-pole SLERP path); `RollingShutterGrid` (per-scanline); `OisEisFusion`; `StabilizationModePolicy` (OFF/STANDARD/LOCKED)
- *Dormant — no GPU EIS warp shader for this path*

---

## 4. Denoise/Detail + HDR/Tone — quality 7/10 · working: mixed

*Live realtime GPU chain; a graveyard of branded-but-dormant shaders.*

### 🟢 NIVUKX realtime denoise (`nivukx_denoise.comp`, Pass 5) · feat 8 · quality 8
- Edge-aware 7×7 spatial bilateral in JzAzBz luma; temporal denoise vs previous-aligned with Tukey-biweight residual rejection; local SNR estimation

### 🟢 Detail preservation + guided upsample (`detail_preservation.comp`, `guided_upsample.comp`) · feat 7 · quality 7
- Texture/edge detail mask; joint-guided 4× upsample of focus/detail maps

### 🟢 Realtime HDR knee + shadow-lift tone mapping (`knee_curve.comp`, `shadow_lift.comp`, Pass 3B) · feat 7 · quality 7
- Adaptive knee self-clamped to [0.55,0.75], driven by per-mode color-science when HDR enabled; shadow lift + contrast recovery; maxCLL from live GPU HDR histogram

### 🔵 Still-capture HDR bracket merge (`hdr_merge.comp` via `hdr_merge_native.cpp`) · feat 7 · quality 7
- GPU compute exposure-bracket fusion; quality tiers FAST/BALANCED/ULTRA/CINEMA; CPU `stackHDR` fallback

### 🔵 AI HDR tone-map policy (`AIHdrToneMapPolicy` + `HdrImagingMath`) · feat 7 · quality 8
- Scene-intent classification; BT.2100 luma-preserving soft-shoulder tonemap; skin-tone weighting; strict-high-end escalation

### 🟠 Dormant shaders · feat 2 · quality 4–5
- `photon_hdr_fusion.comp` (header not even #included); `dual_gain_fusion.comp` (#included only for a word-count log); `ndr10_knee_curve.comp` (superseded live by plain `knee_curve.comp`); `denoise.comp` + `sharpen.comp` (no native consumer); `nivukx_vision_denoise.comp` (SPV-only)

### ⚪ VulkanJointBilateralFilter / VulkanLogDenoiseFilter · feat 2 · quality 5
- Joint-bilateral & log-domain denoise shaders validated only by shader-contract tests

---

## 5. Focus / Autofocus — quality 7.5/10 · working: mixed

*Live continuous/tap/AI-subject AF; the cinema rack/pull/DoF API is dormant.*

### 🟢 Continuous AF mode selection (hardware) · feat 8 · quality 8
- Prefers `CONTINUOUS_VIDEO/PICTURE`; afMode vs manual/AI lens-distance resolution; AF regions gated on `maxAfRegions`

### 🟢 Tap-to-focus (metering region + AF trigger) · feat 8 · quality 8
- Builds focus+exposure regions; sensor-rect mapping; preview→sensor geometry; subject AF trigger / metering on tap

### 🟢 AI subject / face / eye tracking AF (FocusEngine) · feat 8 · quality 8
- Subject-awareness scoring (EYE 25 / FACE 15 / BODY 8 / OBJECT 5); eye-vs-face anchor (openness/pose/occlusion); motion prediction; continuity + cinematic smoothing/hard-cut on switch

### 🟢 AI AF coordinator: metering follow + anti-hunting (CP-F1) · feat 8 · quality 8
- Per-target smoothing + motion-compensated prediction; accept/hold/retrigger gating; fed **real** hardware lens diopters; `shouldSuppressRetrigger` anti-pump on scanning/hunting/risk

### 🟡 Depth-driven lens-distance drive (AIFocusDistanceController PID) · feat 4 · quality 8
- Depth→diopters + PID smoothing + lens override + confidence gating; *hard-gated off whenever `maxAfRegions>0` — i.e. essentially all modern phones*

### 🔵 DepthEstimator (MiDaS monocular depth + temporal fusion + bokeh) · feat 6 · quality 8
- TFLite GPU/NNAPI/CPU fallback; multi-frame temporal fusion + edge-aware refine + guided upsample; synthetic fallback; feeds subject scoring & masks (bokeh renderer has no production caller)

### ⚪ CameraFocusControl cine API (rack focus, eased transitions, predictive focus, DoF, peaking intensity, optimal-AF-mode, face/zone/spot regions) · feat 2 · quality 7
- `selectOptimalAfMode`, `buildFace/Zone/SpotFocusRegion`, `calculateRackFocusStep`, `calculateEasedFocusTransition`, `predictNextFocusPosition`, `isFocusHunting`, `calculateDepthOfField`, `calculateFocusPeakingIntensity` — *zero production callers*

### ⚪ AIFocusDistanceController cinematic transition / focus-pull API · feat 2 · quality 7
- `applyCinematicTransition` (easing), `applyFocusPull` (SMOOTH/RACK/CREEP/SNAP), `estimateTargetDioptersPredictive`, `calculateAdaptiveTransitionDuration`, easing-curve library — *test-only*

### 🟡 Focus peaking & overlays (UI) · feat 5 · quality 6
- Live GL/Vulkan Sobel peaking + AI subject-mask region; overlay geometry/animation/reticle policies; *standalone `FocusPeakingOverlay` CPU View removed in CP-F10 (s5-f09) — live overlay-mask peaking is the single source of truth*

### 🟡 Lens selection policy (cinema multi-camera) · feat 5 · quality 7
- `selectPreferredCandidate` + `classifyLensType` + 35mm-equivalent labeling (live); `selectLowLight/Video/CandidateForLensType` (dormant)

---

## 6. Recording / Encode / NPV10 export — quality 8.3/10 · working: mixed

*Solid live HEVC encoder; the "elite" gate/master layers are largely opt-in or test-only.*

### 🟢 HEVC MediaCodec encode (RecordingManager, MOV-only) · feat 8.5 · quality 8.5
- MediaCodec/MediaMuxer surface+bytebuffer encode; Main/Main10; HEVC level resolution; CBR/VBR/CQ; content-adaptive bitrate; pre-armed persistent input surface; A/V sync; MOV-only via QuickTime backend (MP4 hard-disabled)

### 🟢 RecordingVideoEncoderPlanResolver (profile/level/bitrate/I-frame) · feat 8 · quality 9
- Main10/Main/Main12 resolution; HEVC/AVC/AV1 level by pixels+samples/sec; tiered quality; I-frame interval (incl. all-intra=0); 10-bit gating w/ simulated-fallback detection; tanh adaptive bitrate

### 🔵 All-intra HEVC mastering (AllIntraHevcPolicy) · feat 7.5 · quality 8.5
- Device capability probe; `KEY_I_FRAME_INTERVAL=0`; bitrate clamp to encoder caps; honest IPB fallback

### 🔵 NPV10 uncompressed private master (Npv10RecordingManager / FileWriter) · feat 7 · quality 8.5
- P010/planar-10-bit master write; preflight quality gate; backpressure FAIL_STRICT_MASTER; pause/resume; device-evidence + certificate sidecars; no-dropped-frame policy *(gated behind a flag that defaults OFF)*

### 🔵 NPV10 → MOV CPU/P010 export (Npv10MovConverter) · feat 6.5 · quality 8
- Triple-buffered reader/enhance/writer; P10→P010 repack; CPU export enhancement; native Vulkan accelerator + CPU fallback; SNCE nudge; focus-map sidecar + T.35 SEI; QuickTime color-atom tagging; SHA-256 immutability audit

### 🟡 WYSIWYG / preview-record-export parity · feat 5.5 · quality 7.5
- Record path = GPU encoder surface (parity with preview); *NPV10 master = raw sensor YUV snapshot (NOT GPU-processed)*; export color transport bound to live ODT

### 🔵 HevcColorMetadataRewriter (SPS VUI + T.35 SEI) · feat 7 · quality 8.5
- Annex-B SPS VUI color rewrite; ITU-T T.35 prefix-SEI build/inject; RBSP↔EBSP emulation-byte handling

### ⚪ EliteVisionQualityContract + EliteVisionMeasurementHarness (A1-A15/S1-S10) · feat 3 · quality 8
- 25-criterion pass/fail evaluator; ImageQualityMetrics-driven measurement from golden frames; Tier-Omega projection — *no production caller*

### 🔵 Tier-Omega quality gate (RecordingQualityEvidencePolicy) · feat 6 · quality 8.5
- Blocking PASS/FAIL certificate; Tier-Omega metric-evidence requirement; strict-10bit/NPV10/BT2100/N-Log rules; GPU/zero-copy claim validation

### 🔵 Zero-shutter-lag / persistent input surface · feat 6.5 · quality 8
- `createPersistentInputSurface` pre-arm; persistent-vs-codec-owned selection

---

## 7. AI/ML + Vulkan rendering + Startup — quality 8/10 · working: mostly-live

*The strongest working cluster.*

### 🟢 AICameraPipeline (multi-stage AI frame orchestrator) · feat 8 · quality 8
- YUV ingestion; per-stage striding + adaptive load governor; staleness-cached fusion; zero-copy AHB tensor attempt; subject/focus + HDR/low-light suggestion output

### 🟢 FaceTracker (MediaPipe FaceLandmarker) · feat 8 · quality 8
- Real `.task` model; GPU→CPU fallback; eye-landmark extraction for eye-AF; default-on

### 🔵 DepthEstimator (MiDaS v2.1 small TFLite) · feat 7 · quality 8
- Real 66MB interpreter; GPU/NNAPI/CPU selection; radial-depth fallback; *gated behind aiAssist (off during record/high-load)*

### 🔵 SegmentationEngine (MediaPipe selfie multiclass) · feat 7 · quality 8
- Real ImageSegmenter; GPU→CPU fallback; radial mask fallback; luma-guided refinement; drives peaking mask

### 🔵 PoseTracker (MediaPipe PoseLandmarker) · feat 6 · quality 8
- Real 9.4MB model; GPU→CPU fallback; feeds subject-awareness

### 🔵 ObjectTracker (EfficientDet-Lite0 via AINeuralEngine) · feat 6 · quality 7
- Real inference; classical chroma-saliency/temporal-diff fallback; runs when face/pose enabled

### 🔵 SceneDetector (ML Kit ImageLabeling) · feat 6 · quality 7
- Real labeling client; histogram fallback; scene-type → HDR/low-light hints

### 🟡 AINeuralEngine (generic TFLite model registry) · feat 4 · quality 7
- 8-model asset registry + resolver; real Interpreter w/ GPU/NNAPI; *most registry entries dead-coded (each subsystem self-loads)*

### 🔵 EnhancementEngine + NeuralDenoiseEngine · feat 5 · quality 7
- Classical bilateral denoise + unsharp + micro-contrast; optional TFLite denoise hook; bypass/small-image early-exits; scene-adaptive

### 🔵 PredictiveSubjectTracker (Kalman) · feat 6 · quality 7
- Position/velocity/accel state; prediction lead-time; feeds the focus engine

### 🟢 Vulkan bridge native runtime (`vulkan_bridge.cpp`) · feat 9 · quality 9
- Real VkInstance/device/global init; AHB import + cinema RAW debayer compute; preview+encoder render; deferred background pipeline compile (~2.5s SPIR-V); graceful raw-passthrough

### 🟢 VulkanRuntimeManager (frame-import orchestration) · feat 8 · quality 8
- Lazy non-blocking init; frame-import lifecycle; cinema-debayer activation; preview/encoder binding; import-stats telemetry

### 🟢 Cold-start path (splash + BootTrace + deferred compile) · feat 7 · quality 8
- `installSplashScreen` held until first preview frame + safety deadline; BootTrace milestone profiler (debug-only); heavy-pipeline compile deferred off cold-start

---

## 8. UI + Quality gates + Build/Test infra — quality 8/10 · working: mixed

*Polished live UI; the measurement-to-gate loop closes only in tests.*

### 🟢 Camera HUD overlays · feat 8 · quality 8
- Real Canvas histogram/waveform/zebra/focus-peaking/false-color/grid/safe-area/face-box/thermal-rec HUD; vsync-gated telemetry coalescer fed by live pipeline StateFlows; standalone monitor widgets

### 🟢 Manual camera controls (ISO/SS/WB/MF/EV/AF/metering/RAW) · feat 8 · quality 8
- 8-tab manual controls backed by the ViewModel; pure value/label/range mappers; tap-to-focus + focus-EV touch handling; custom seek control

### 🟢 Settings system (6 sections) · feat 7 · quality 8
- CAMERA/RECORD/COLOR/FOCUS/MONITORING/SYSTEM section factory + option catalog + UI factory; real toggles (AI Enhancement, AI Motion Compensation, Noise Reduction, Resolution, Frame Rate)

### 🟢 CameraViewModel / MainActivity composite UI state + render path · feat 7 · quality 6
- `CameraViewModel` 10,183 lines / 102 StateFlows / 452 funcs · `MainActivity` 3,191 lines (god objects)
- ~150 extracted pure `*Policy` classes each unit-tested; coalesced render scheduler; lifecycle-scoped collectors

### 🟡 ImageQualityMetrics measurement harness · feat 8 · quality 9
- ISO-12233 slanted-edge MTF50 (ESF→LSF→Hamming→DFT); flattest-tile SNR; full CIEDE2000; ramp banding; temporal flicker; opponent-space chroma-crawl
- *Only the live-frame SNR primitive runs in production; MTF50/dE2000/banding/flicker run only in tests/goldens*

### ⚪ EliteVisionMeasurementHarness → Tier-Omega real-metric producer · feat 4 · quality 8
- `measure()` computes MTF50/SNR/dE2000/flicker from golden frames; `toTierOmegaMetadata()` projects onto gate keys — *no production caller*

### 🔵 RecordingQualityEvidencePolicy (Tier-Omega / strict 10-bit gate) · feat 7 · quality 8
- 1402-line gate: proof/parity/frame/mode-truth/probe/runtime/tierOmega/omega groups; per-mode HDR/LOG/RAW/NPV10 transport truth; bitrate-floor + manual-cap; preview=record=export signature parity

### 🟢 OmegaCapabilityGate (anti-fraud capability truth gate) · feat 8 · quality 9
- Validates SDR/NDR/NIVUKX/N-Log/RAW/NPV10/Vulkan/manual-bitrate against real transport+bit-depth; blocks external/licensed/unproven claims as `NOT_AVAILABLE` rather than faking a pass

### ⚪ Singularity quality-gate scaffolding (QG01-16, FrameGraphRegistry, ProductionReadinessGate, AssetManifestValidator) · feat 4 · quality 6
- `allPassingResults()` synthesizes 16 PASS results with no measurement; ProductionReadinessGate + AssetManifestValidator have zero live callers

### 🟢 Test-coverage net + build/test infra · feat 8 · quality 8
- 562 test files vs 625 main; 122 UI + 107 quality + 96 recording + 88 camera + 72 rendering; 95 contract/golden/characterization (freeze per-mode RGB via reflection); pure-JVM (no Robolectric); source-hygiene meta-tests

---

*End of catalog. For the executive rating, LOC totals, strengths/weaknesses and methodology, see
[`PROJECT_ANALYSIS.md`](./PROJECT_ANALYSIS.md).*
