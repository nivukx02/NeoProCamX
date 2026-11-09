# NeoProCamX — Elite Upgrade Prompts (Index)

**78 self-contained implementation prompts — one per feature — to raise every NeoProCamX
feature to high-end cinema / IMAX tier.** Each prompt explains how the feature works in real
professional cameras, what exists in our code today (`file:line`), a measurable definition of
"elite", and a hand-to-an-agent work order. Generated 2026-06-26.

## How to use this folder
1. **Read [`_PROMPT_STANDARD.md`](./_PROMPT_STANDARD.md) once.** It is the global contract every
   prompt inherits — **no-downgrade, no-vendor, commit-per-feature, golden-first, ABI-locked,
   honest-gates, device-gated, no-lag**. The prompts do not restate it; they assume it.
2. Pick a feature prompt. Re-verify its §3 "current state" against live source (code moves).
3. Execute: golden-lock the live path → implement behind a no-downgrade gate → build + tests
   green → commit with the CP ID → record the owner device-validation step.
4. The exemplar [`s1-capture/s1-f06-mfsr-multiframe-superres.md`](./s1-capture/s1-f06-mfsr-multiframe-superres.md)
   is the quality bar all others match.

**Companions:** [`../PROJECT_ANALYSIS.md`](../PROJECT_ANALYSIS.md) (ratings) ·
[`../FEATURE_CATALOG.md`](../FEATURE_CATALOG.md) (inventory) ·
[`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md) (CP roadmap).

**Status legend:** 🟢 live-default-on · 🔵 live-optional · 🟡 partial · 🟠 dormant · ⚪ test-only-scaffold
&nbsp;&nbsp;|&nbsp;&nbsp; **feat** = value if fully working (1–10) · **q** = code quality now (1–10)

---

## Recommended execution order
The audit's core finding: *the code is high-quality but ~55% dormant — wiring is the dominant
lever, not new code.* Sequence the marathon to unlock the most live pixels per unit risk:

1. **Foundation already done** — CP00–CP04, CP01 measurement harness, CP-Q1..Q6 (see roadmap).
2. **Color wiring (highest visible payoff, lowest risk):** s2-f11 (Oklab cusp, CP40 foundation
   done) → s2-f06/s2-f07 (SNCE scene-brain → GPU, CP41) → s2-f10 (illuminant/CAM16, CP42).
3. **Capture/RAW spine:** s1-f09 (sensor→ACEScg, CP11 done) → s1-f07/s1-f08 (debayer/demosaic
   dispatch, CP10) → s1-f06 (MFSR real input, CP12/13) → s1-f03 (noise model).
4. **Dynamic range:** s4-f03 (remove SDR-referred clamp, CP30) → s4-f06 wire photon/dual-gain
   (CP31/32) → s4-f01 motion-compensated temporal denoise (CP20).
5. **Stabilization:** s3-f05 → s3-f06 (gyro-EIS GPU warp, CP60–65) — biggest dormant block.
6. **Cross-surface parity:** s6-f06/s6-f05 (NPV10 true pixel parity, CP80–82) → s8-f06 (close
   measurement→gate loop, CP-Q1).
7. **Maintainability (parallelizable anytime):** s8-f04 (dissolve the two god objects).
8. **Hardening passes (live features → bulletproof):** s7-* Vulkan/AI cluster, s5-* focus,
   s6-f01/f02 encode, s8-f01/f02 UI.

> Each device-gated step ends at the JVM/native-build boundary, fully unit-tested + dormant, and
> the owner validates on the vivo I2405 / Adreno before the gate flips on. No fake validation.

---

## s1 — Capture, RAW & Multi-frame  ([dir](./s1-capture/))
| Prompt | Feature | Status | feat·q | CP |
|---|---|:--:|:--:|---|
| [s1-f01](./s1-capture/s1-f01-raw-dng-npraw.md) | RAW video/still capture & serialization (DNG + NPRAW) | 🔵 | 8·8 | CP16 |
| [s1-f02](./s1-capture/s1-f02-isp-bypass.md) | ISP bypass for sensor-native capture | 🔵 | 8·9 | — |
| [s1-f03](./s1-capture/s1-f03-raw-calibration-noise-model.md) | RAW sensor calibration & noise-model policies | 🔵 | 7·8 | CP12/15 |
| [s1-f04](./s1-capture/s1-f04-ois-gyro-telemetry.md) | OIS / gyro motion telemetry capture | 🟡 | 5·7 | CP60 |
| [s1-f05](./s1-capture/s1-f05-camera2-session-orchestration.md) | Camera2 session / stream config & orchestration | 🟢 | 8·8 | — |
| [s1-f06](./s1-capture/s1-f06-mfsr-multiframe-superres.md) ⭐ | MFSR multi-frame super-resolution fusion | 🟠 | 2·7 | CP12/13 |
| [s1-f07](./s1-capture/s1-f07-vulkan-cinema-debayer.md) | Vulkan cinema debayer preview | 🟠 | 2·7 | CP10 |
| [s1-f08](./s1-capture/s1-f08-vulkan-demosaic-pipeline.md) | Standalone Vulkan demosaic pipeline | 🟠 | 1·6 | CP10 |
| [s1-f09](./s1-capture/s1-f09-sensor-to-acescg-matrices.md) | Sensor → XYZ → ACEScg color matrices | 🟠 | 3·8 | CP11 |
| [s1-f10](./s1-capture/s1-f10-multiexposure-hdr-fusion-shaders.md) | Multi-exposure HDR fusion shaders (dual-gain/photon) | 🟠 | 2·6 | CP31/32 |

## s2 — Color science + SNCE engine  ([dir](./s2-color/))
| Prompt | Feature | Status | feat·q | CP |
|---|---|:--:|:--:|---|
| [s2-f01](./s2-color/s2-f01-colorscience-3dlut-grade.md) | ColorScience preview/record 3D-LUT grade | 🔵 | 8·8 | — |
| [s2-f02](./s2-color/s2-f02-aces-color-engine-refinement.md) | AcesColorEngine per-pixel refinement | 🟡 | 7·8 | CP30/34 |
| [s2-f03](./s2-color/s2-f03-vulkan-colorscience-uniforms.md) | VulkanColorScienceUniforms GPU upload | 🟢 | 8·8 | — |
| [s2-f04](./s2-color/s2-f04-colordrift-governor.md) | ColorDriftGovernor temporal white-point stabilizer | 🔵 | 6·7 | CP47 |
| [s2-f05](./s2-color/s2-f05-snce-v2-live-controller.md) | SNCE v2 live colour controller | 🟢 | 6·8 | CP47 |
| [s2-f06](./s2-color/s2-f06-snce-23float-scene-brain.md) | SNCE 23-float scene-brain | 🟠 | 3·8 | CP41 |
| [s2-f07](./s2-color/s2-f07-snce-occs-opponent-pass.md) | SNCE Stage-04 OCCS opponent GPU pass | 🔵 | 5·7 | CP41/46/49 |
| [s2-f08](./s2-color/s2-f08-snce-nrli-neural-lut.md) | SNCE Stage-11 NRLI neural 3D-LUT GPU pass | 🟠 | 3·7 | CP43 |
| [s2-f09](./s2-color/s2-f09-snce-learned-model-registry.md) | SNCE learned-model registry (hooks; no-vendor) | ⚪ | 3·7 | CP4A |
| [s2-f10](./s2-color/s2-f10-snce-illuminant-cam16.md) | SnceIlluminantEstimator (9-method) + CAM16 chain | 🟡 | 6·9 | CP42/44 |
| [s2-f11](./s2-color/s2-f11-oklab-gamut-cusp-mapping.md) | OklabGamutCusp + OklabGamutMapping | 🟠 | 3·9 | CP40 |
| [s2-f12](./s2-color/s2-f12-colorvalidation-harness-apex.md) | ColorValidationHarness + NeoGamutApex.processForOutput | ⚪ | 2·8 | CP-Q4 |

## s3 — Motion (OmniClarity) + Stabilization  ([dir](./s3-motion/))
| Prompt | Feature | Status | feat·q | CP |
|---|---|:--:|:--:|---|
| [s3-f01](./s3-motion/s3-f01-argus-optical-flow.md) | Argus hybrid optical flow | 🟢 | 8·9 | CP50/54 |
| [s3-f02](./s3-motion/s3-f02-chronos-temporal-warp.md) | Chronos motion-compensated temporal warp | 🟢 | 7·9 | CP52 |
| [s3-f03](./s3-motion/s3-f03-riven-motion-deblur.md) | Riven motion deblur | 🟢 | 7·9 | CP53 |
| [s3-f04](./s3-motion/s3-f04-omniclarity-tuning-policies.md) | OmniClarity Kotlin tuning policies → push constants | ⚪ | 2·8 | CP91 |
| [s3-f05](./s3-motion/s3-f05-gyro-orientation-integration.md) | Gyro orientation integration (quaternion) | 🟠 | 2·9 | CP60 |
| [s3-f06](./s3-motion/s3-f06-eis-warp-stabilization-stack.md) | EIS warp + trajectory + rolling-shutter + OIS/EIS + modes | 🟠 | 2·9 | CP60–65 |

## s4 — Denoise/Detail + HDR/Tone  ([dir](./s4-denoise-hdr/))
| Prompt | Feature | Status | feat·q | CP |
|---|---|:--:|:--:|---|
| [s4-f01](./s4-denoise-hdr/s4-f01-nivukx-realtime-denoise.md) | NIVUKX realtime denoise (Pass 5) | 🟢 | 8·8 | CP20/21/22 |
| [s4-f02](./s4-denoise-hdr/s4-f02-detail-preservation-guided-upsample.md) | Detail preservation + guided upsample | 🟢 | 7·7 | CP24 |
| [s4-f03](./s4-denoise-hdr/s4-f03-realtime-hdr-knee-shadow-lift.md) | Realtime HDR knee + shadow-lift (remove SDR clamp) | 🟢 | 7·7 | CP30/33/34 |
| [s4-f04](./s4-denoise-hdr/s4-f04-still-hdr-bracket-merge.md) | Still-capture HDR bracket merge | 🔵 | 7·7 | CP35 |
| [s4-f05](./s4-denoise-hdr/s4-f05-ai-hdr-tonemap-policy.md) | AI HDR tone-map policy | 🔵 | 7·8 | CP30 |
| [s4-f06](./s4-denoise-hdr/s4-f06-dormant-hdr-denoise-shaders.md) | Dormant shaders (photon/dual-gain/knee/denoise/sharpen) — wire vs retire | 🟠 | 2·4-5 | CP31/32/33/23 |
| [s4-f07](./s4-denoise-hdr/s4-f07-vulkan-bilateral-logdenoise-filters.md) | VulkanJointBilateral / VulkanLogDenoise filters | ⚪ | 2·5 | CP23 |

## s5 — Focus / Autofocus  ([dir](./s5-focus/))
| Prompt | Feature | Status | feat·q | CP |
|---|---|:--:|:--:|---|
| [s5-f01](./s5-focus/s5-f01-continuous-af-mode.md) | Continuous AF mode selection (hardware) | 🟢 | 8·8 | — |
| [s5-f02](./s5-focus/s5-f02-tap-to-focus.md) | Tap-to-focus (metering + AF trigger) | 🟢 | 8·8 | — |
| [s5-f03](./s5-focus/s5-f03-ai-subject-face-eye-af.md) | AI subject / face / eye tracking AF | 🟢 | 8·8 | — |
| [s5-f04](./s5-focus/s5-f04-ai-af-coordinator-antihunting.md) | AI AF coordinator: metering follow + anti-hunting | 🟢 | 8·8 | CP-F1 |
| [s5-f05](./s5-focus/s5-f05-depth-lens-distance-pid.md) | Depth-driven lens-distance drive (PID) | 🟡 | 4·8 | — |
| [s5-f06](./s5-focus/s5-f06-depth-estimator-midas.md) | DepthEstimator (MiDaS + temporal fusion + bokeh) | 🔵 | 6·8 | — |
| [s5-f07](./s5-focus/s5-f07-camerafocuscontrol-cine-api.md) | CameraFocusControl cine API (rack/pull/DoF/peaking) | ⚪ | 2·7 | — |
| [s5-f08](./s5-focus/s5-f08-aifocusdistance-cinematic-pull.md) | AIFocusDistanceController cinematic focus-pull API | ⚪ | 2·7 | — |
| [s5-f09](./s5-focus/s5-f09-focus-peaking-overlays.md) | Focus peaking & overlays (UI) | 🟡 | 5·6 | — |
| [s5-f10](./s5-focus/s5-f10-lens-selection-policy.md) | Lens selection policy (cinema multi-camera) | 🟡 | 5·7 | — |

## s6 — Recording / Encode / NPV10  ([dir](./s6-recording/))
| Prompt | Feature | Status | feat·q | CP |
|---|---|:--:|:--:|---|
| [s6-f01](./s6-recording/s6-f01-hevc-mediacodec-encode.md) | HEVC MediaCodec encode (MOV-only) | 🟢 | 8.5·8.5 | — |
| [s6-f02](./s6-recording/s6-f02-encoder-plan-resolver.md) | RecordingVideoEncoderPlanResolver | 🟢 | 8·9 | — |
| [s6-f03](./s6-recording/s6-f03-all-intra-hevc-mastering.md) | All-intra HEVC mastering | 🔵 | 7.5·8.5 | CP70/71 |
| [s6-f04](./s6-recording/s6-f04-npv10-uncompressed-master.md) | NPV10 uncompressed private master | 🔵 | 7·8.5 | — |
| [s6-f05](./s6-recording/s6-f05-npv10-mov-export.md) | NPV10 → MOV CPU/P010 export | 🔵 | 6.5·8 | CP72/73/80/81 |
| [s6-f06](./s6-recording/s6-f06-wysiwyg-parity.md) | WYSIWYG preview-record-export parity | 🟡 | 5.5·7.5 | CP82 |
| [s6-f07](./s6-recording/s6-f07-hevc-color-metadata-rewriter.md) | HevcColorMetadataRewriter (SPS VUI + T.35 SEI) | 🔵 | 7·8.5 | — |
| [s6-f08](./s6-recording/s6-f08-elite-vision-quality-contract.md) | EliteVisionQualityContract + MeasurementHarness | ⚪ | 3·8 | CP-Q1 |
| [s6-f09](./s6-recording/s6-f09-tier-omega-quality-gate.md) | Tier-Omega quality gate | 🔵 | 6·8.5 | CP-Q3 |
| [s6-f10](./s6-recording/s6-f10-zero-shutter-lag-surface.md) | Zero-shutter-lag / persistent input surface | 🔵 | 6.5·8 | — |

## s7 — AI/ML + Vulkan rendering + Startup  ([dir](./s7-ai-vulkan-startup/))
| Prompt | Feature | Status | feat·q | CP |
|---|---|:--:|:--:|---|
| [s7-f01](./s7-ai-vulkan-startup/s7-f01-ai-camera-pipeline.md) | AICameraPipeline (AI frame orchestrator) | 🟢 | 8·8 | CP90/92 |
| [s7-f02](./s7-ai-vulkan-startup/s7-f02-face-tracker.md) | FaceTracker (MediaPipe FaceLandmarker) | 🟢 | 8·8 | — |
| [s7-f03](./s7-ai-vulkan-startup/s7-f03-depth-estimator-midas.md) | DepthEstimator (MiDaS v2.1 small) | 🔵 | 7·8 | — |
| [s7-f04](./s7-ai-vulkan-startup/s7-f04-segmentation-engine.md) | SegmentationEngine (MediaPipe multiclass) | 🔵 | 7·8 | CP4A |
| [s7-f05](./s7-ai-vulkan-startup/s7-f05-pose-tracker.md) | PoseTracker (MediaPipe PoseLandmarker) | 🔵 | 6·8 | — |
| [s7-f06](./s7-ai-vulkan-startup/s7-f06-object-tracker.md) | ObjectTracker (EfficientDet-Lite0) | 🔵 | 6·7 | — |
| [s7-f07](./s7-ai-vulkan-startup/s7-f07-scene-detector.md) | SceneDetector (ML Kit ImageLabeling) | 🔵 | 6·7 | — |
| [s7-f08](./s7-ai-vulkan-startup/s7-f08-ai-neural-engine.md) | AINeuralEngine registry (consolidate/prune) | 🟡 | 4·7 | — |
| [s7-f09](./s7-ai-vulkan-startup/s7-f09-enhancement-neural-denoise.md) | EnhancementEngine + NeuralDenoiseEngine | 🔵 | 5·7 | — |
| [s7-f10](./s7-ai-vulkan-startup/s7-f10-predictive-subject-tracker.md) | PredictiveSubjectTracker (Kalman) | 🔵 | 6·7 | — |
| [s7-f11](./s7-ai-vulkan-startup/s7-f11-vulkan-bridge-native-runtime.md) | Vulkan bridge native runtime (vulkan_bridge.cpp) | 🟢 | 9·9 | CP73 |
| [s7-f12](./s7-ai-vulkan-startup/s7-f12-vulkan-runtime-manager.md) | VulkanRuntimeManager (frame-import orchestration) | 🟢 | 8·8 | — |
| [s7-f13](./s7-ai-vulkan-startup/s7-f13-cold-start-path.md) | Cold-start path (splash + BootTrace + deferred compile) | 🟢 | 7·8 | — |

## s8 — UI + Quality gates + Build/Test  ([dir](./s8-ui-quality/))
| Prompt | Feature | Status | feat·q | CP |
|---|---|:--:|:--:|---|
| [s8-f01](./s8-ui-quality/s8-f01-camera-hud-overlays.md) | Camera HUD overlays (scopes/peaking/false-color) | 🟢 | 8·8 | — |
| [s8-f02](./s8-ui-quality/s8-f02-manual-camera-controls.md) | Manual camera controls (ISO/SS/WB/MF/EV/AF) | 🟢 | 8·8 | — |
| [s8-f03](./s8-ui-quality/s8-f03-settings-system.md) | Settings system (6 sections) | 🟢 | 7·8 | — |
| [s8-f04](./s8-ui-quality/s8-f04-viewmodel-mainactivity-godobjects.md) | CameraViewModel / MainActivity (dissolve god objects) | 🟢 | 7·6 | — |
| [s8-f05](./s8-ui-quality/s8-f05-image-quality-metrics-harness.md) | ImageQualityMetrics measurement harness | 🟡 | 8·9 | CP01/Q* |
| [s8-f06](./s8-ui-quality/s8-f06-elite-vision-measurement-producer.md) | EliteVisionMeasurementHarness → Tier-Omega producer | ⚪ | 4·8 | CP-Q1/Q3 |
| [s8-f07](./s8-ui-quality/s8-f07-recording-quality-evidence-policy.md) | RecordingQualityEvidencePolicy (strict 10-bit gate) | 🔵 | 7·8 | CP-Q3 |
| [s8-f08](./s8-ui-quality/s8-f08-omega-capability-gate.md) | OmegaCapabilityGate (anti-fraud truth gate) | 🟢 | 8·9 | — |
| [s8-f09](./s8-ui-quality/s8-f09-singularity-quality-gate-scaffolding.md) | Singularity QG scaffolding (de-synthesize or retire) | ⚪ | 4·6 | — |
| [s8-f10](./s8-ui-quality/s8-f10-test-coverage-build-infra.md) | Test-coverage net + build/test infra | 🟢 | 8·8 | — |

---

## Honesty notes carried from generation (verify before relying)
These were flagged by the authoring pass as not-fully-source-verified or as design decisions the
catalog blurs — resolve them when you pick up the prompt:
- **s1-f07/s1-f08 shader identity:** the catalog says `cinema_debayer.comp`, but the only RAW
  demosaic *created* in the native bridge is `demosaic_vulkan.comp`; `cinema_debayer.comp` is a
  source + Kotlin orchestrator not `#include`d/dispatched. Decide which becomes the live demosaic.
- **Proposed CP IDs** (CP-F2.., CP93–CP9C, CP52b/54b) are *proposals*, not reserved — confirm the
  next free index in the roadmap before committing.
- **Numeric targets** (bitrate floors, ms/frame budgets, EPE/MTF50 ceilings) are engineering
  *targets* from tech docs or set as the elite bar — not current measured values.
- **Commit hashes / test counts** quoted in §3/§8 are taken from the roadmap's stated findings,
  not re-confirmed via `git show`.
