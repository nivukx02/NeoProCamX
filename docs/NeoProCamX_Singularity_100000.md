# NeoProCamX Singularity 100000

Status: implemented and built through V19, with the earlier V16 NDR10+ 1080p30 row deployed and device-validated. V20 adds zero-grain clarity control, motion-locked microdetail, anti-motion-blur capture policy, source-pressure encoder budgeting, and measured release gates. V20 device qualification remains evidence-based and must not be inferred from the historical V16 row.

Generated for repository: `D:\NeoProCamX_Final\NeoProCamX`  
Generated date: `2026-06-09`  
Application namespace: `com.neoproc.neoproccamx`  
Android package: `com.neoproc.neoproccamx`  
Upgrade name: `NeoProCamX Singularity 100000`  
Elite upgrade name: `NeoProCamX Singularity 100000 V20 Elite 100X Zero-Grain Clarity and Motion-Locked Microdetail`  
Internal pipeline: `VUKX_SOVEREIGN_APEX_100000_0`

## 1. Executive Summary

`NeoProCamX Singularity 100000` is the repository-native high-end video quality, evidence, metadata, and release-gating upgrade for NeoProCamX. It is not a separate app and it does not create a parallel package namespace. It extends the existing Camera2, Vulkan/native, AI, color, NDR, LOG, RAW, NPV10, preview, recording, quality-gate, and metadata systems.

The upgrade is built around a canonical 256-stage evidence ledger, 16 quantified quality gates, truthful transport classification, SMF-Singularity metadata, and release classes that prevent false quality claims. Every high-end claim must be backed by runtime evidence. Unsupported features remain registered, visible, and disabled or fallback-executed instead of being silently claimed.

The latest real-device deployment and validation proved a real 10-bit HEVC NDR10+ recording path on device `10BF8M2NDX003P3` with BT.2020/PQ transport tags, proprietary NeoProCamX/NIVUKX identity preserved, and no Dolby Vision, HDR10+, HDR10, or other external vendor certification claimed.

Important truth: on the tested Vivo I2405 device, an offscreen AHardwareBuffer/EGL 10-bit recordable encoder surface was not proven. The tested row therefore used the visible compatibility route `APP_P010_BYTEBUFFER_ENCODER`. This still produced a verified real 10-bit HEVC file, but it must not be represented as `AHB_VULKAN_ZERO_COPY` on that device.

V20 Elite 100X extends the V19 qualification layer without inflating old results. It adds measured zero-grain clarity, microdetail honesty, sharpness safety, and anti-motion-blur authorities. A control value, denoise strength, sharpen strength, profile name, or intent flag is not proof that the recorded pixels are noise-free, sharper, or blur-free. Qualification requires measured output evidence and remains capped or blocked when that evidence is absent or unsafe.

A row that uses `APP_P010_BYTEBUFFER_ENCODER` must prove P010 byte-buffer ingress, queued frames, zero frame drops, EOS, and final P010 evidence before validation may report `PASS`. Strict processed Vulkan preview/recording routes must also carry the V19 per-frame Camera2 lineage certificate proving that the same capture-result timestamps reached preview and encoder endpoints. The earlier V16 artifact remains a truthful historical 10-bit output proof, but V20 does not retroactively qualify it for the new image-quality authorities.

## 2. Non-Certification Statement

The word `Singularity` is the NeoProCamX internal high-end quality and evidence architecture.

The source phrase `IMAX level` is treated only as an internal large-format ambition. NeoProCamX does not claim official certification or endorsement from:

| External system | Claimed by NeoProCamX Singularity 100000 |
| --- | --- |
| IMAX | `false` |
| IMAX Enhanced | `false` |
| Dolby Vision | `false` |
| HDR10+ | `false` |
| HDR10 certification | `false` |
| ARRI | `false` |
| RED | `false` |
| Sony | `false` |
| Any other vendor cinema certification | `false` |

NDR, LOG, NIVUKX Vision, NPV10, NPV12, TMB, Neo ACES, and APEX profiles remain proprietary NeoProCamX/NIVUKX technologies. They are not replaced by Dolby Vision, HDR10+, vendor LOG, vendor RAW, or any external profile.

Correct Android color transport signaling, such as BT.2020, PQ, HLG, matrix, range, profile, and bit depth tags, is still allowed when it truthfully describes the encoded pixels. Color signaling is not the same thing as vendor certification.

## 3. Canonical Values

These values are implemented in code and assets.

| Field | Value |
| --- | --- |
| Upgrade name | `NeoProCamX Singularity 100000` |
| App | `NeoProCamX` |
| Application ID | `com.neoproc.neoproccamx` |
| Format version | `SMF-SINGULARITY-1` |
| Pipeline | `VUKX_SOVEREIGN_APEX_100000_0` |
| Quality target | `SINGULARITY_LARGE_FORMAT_INTERNAL` |
| Source directive alias | `EXACT_IMAX_LEVEL_INTERNAL` |
| Ambition law | `10000000000000XXXX` |
| Frame graph stages | `256` |
| Frame graph registry | `SINGULARITY_FRAME_GRAPH_256_V1` |
| Schema major | `1` |
| Release policy version | `SINGULARITY_RELEASE_POLICY_1` |
| Gate policy version | `QG_SINGULARITY_1` |
| Elite policy version | `SINGULARITY_ELITE_100X_POLICY_V20` |
| Elite report version | `SINGULARITY_ELITE_100X_REPORT_V20` |
| Stage 5 engine version | `SINGULARITY_STAGE5_HIGH_END_ENGINES_V20` |
| GPU clarity policy | `V20_ZERO_GRAIN_CLARITY` |
| Camera request policy | `V20` |
| Threshold asset format | `SINGULARITY_THRESHOLDS_1` |
| Threshold set ID | `device-mode-specific` |
| Generic threshold profile | `foundation-generic-1080p30` |
| Generic threshold device class | `GENERIC_ANDROID_CAPABILITY_PROVEN` |
| Generic threshold resolution | `1920x1080` |
| Generic threshold frame rate | `30` |
| Official Dolby Vision certification claimed | `false` |
| Official HDR10+ certification claimed | `false` |
| Official HDR10 certification claimed | `false` |

## 3.1 V20 Elite 100X Gate

`NeoProCamX Singularity 100000 V20 Elite 100X Zero-Grain Clarity and Motion-Locked Microdetail` is the current stricter upgrade layer. It consumes Stage 2, Stage 4, Stage 5, Stage 6, the recording certificate, V19 per-frame Camera2 lineage evidence, V20 image-quality metrics, and runtime metadata. It can only raise confidence when proof exists. Missing proof caps the requested release class before `SingularityReleaseGate` runs.

V20 image-quality authorities:

| Authority | Passing threshold |
| --- | --- |
| `ZERO_GRAIN_CLARITY` | `zeroGrainClarityScore >= 0.82`, `grainResidualScore <= 0.08`, and `denoiseSafetyScore >= 0.80` |
| `MICRODETAIL_HONESTY` | `microdetailHonestyScore >= 0.84`, `sharpnessSafetyScore >= 0.82`, `edgeOvershootPercent <= 0.035`, and `fineTextureRetention >= 0.78` |
| `ANTI_MOTION_BLUR` | `antiMotionBlurScore >= 0.80`, `motionBlurResidual <= 0.10`, and `temporalShimmerPercent <= 0.030` |

V20 runtime controls:

| Control | Implemented behavior |
| --- | --- |
| Zero-grain lock | Normal/Pro SDR strict real-time paths raise spatial and chroma cleanup from measured luma, chroma, RGB variance, ISO pressure, and local chroma pressure. |
| RGB grain quarantine | Chroma cleanup is driven by measured chroma and frame-to-frame RGB variance, with local spike smoothing and bounded output. |
| Motion-safe temporal denoise | Temporal accumulation is capped as motion pressure rises: `0.84`, `0.66`, `0.54`, or `0.44` maximum blend depending on measured motion tier. |
| Motion-locked microdetail | Sharpening ceiling falls as noise, chroma pressure, and motion rise. Subject detail is limited by remaining sharpening headroom and skin protection. |
| Camera2 anti-motion capture | Auto-exposure processed video may request a stable FPS range and cap exposure to one-half, one-third, or one-quarter of frame duration. User manual ISO/exposure remains authoritative. |
| Encoder source-pressure budget | Automatic bitrate planning accounts for measured motion/noise, anti-motion-blur activation, detail profile, and source-quality pressure while remaining inside the real codec ceiling. |
| Mode isolation | The V20 GPU clarity override is limited to strict Normal/Pro SDR real-time paths. NDR, LOG, and RAW retain their existing proprietary transfer, curve, and monitor semantics. |

V20 does not promise literal zero sensor noise or zero motion blur on every device and scene. The release seal uses measured thresholds above; false `zero noise`, `IMAX`, or blur-free claims produce a downgrade or `NO-RELEASE`.

Elite 100X authorities:

| Authority | Required proof |
| --- | --- |
| `THRESHOLD_ATTESTATION` | Device/mode threshold set ID plus 64-character SHA-256. The generic `foundation-generic-1080p30` set must match canonical asset hash `C2AF2ED33C919F4CF57E0280A0BFEF3F0A3EC4E9E1496274157BA6EFDF20A6A9`. |
| `SOURCE_PRECISION` | 10-bit source for processed video modes; 12-bit original sensor proof for RAW elite output. |
| `OUTPUT_PRECISION` | 10-bit encoded/storage proof for processed modes; 12-bit storage proof for RAW/private master paths. |
| `TRANSPORT_PROOF` | `AHB_VULKAN_ZERO_COPY`, `ENCODER_SURFACE_DIRECT`, `PRIVATE_MASTER_GPU_EXPORT`, or `RAW_SENSOR_PRIVATE_MASTER` for full class. `AHB_GPU_COMPATIBILITY` caps at `SINGULARITY-75`; CPU/unproven transport caps at `CORE-HONEST`. |
| `QUALITY_GATE_CLOSURE` | QG01-QG16 all `PASS`, plus QG01-QG13 pixel/chart artifact attestation through `singularityEliteMeasuredGateIds` and `singularityEliteArtifactManifestSha256`. Metadata-only scores cannot qualify Elite 100X. |
| `CRITICAL_ENGINE_EVIDENCE` | Critical Stage 5 engines implemented and evidenced: exposure/dynamic range, noise/texture separation, motion/temporal, texture, resolution honesty, color, tone, QC, and RAW reconstruction when RAW is active. |
| `PREVIEW_ENCODE_ARTIFACTS` | QG12 and QG13 pass with encoded-vs-pre-encode and preview/file comparison artifact references. |
| `FRAME_LINEAGE_PARITY` | Per-frame Camera2 capture-result lineage certificate proves capture, image timestamp, preview submission, and encoder submission parity for strict processed recording routes. Missing proof caps at `SINGULARITY-75`; failed proof caps at `SINGULARITY-50`; false lineage claim blocks release. |
| `SUSTAINED_CADENCE` | Positive expected/actual frame counts, QG14/QG15 pass, and effective dropped frames within policy. |
| `ENCODE_DENSITY` | Measured output bitrate density, not only planned bitrate. HEVC Main10 floor is `0.180000` bits per pixel per frame; AV1 10-bit floor is `0.120000`; H.264 floor is `0.250000`. |
| `METADATA_INDEPENDENCE` | Proprietary profile preserved, no external HDR/IMAX/Dolby false claim, no vendor substitution, and no metadata false-claim flag. |

V19 frame-lineage values:

| Key | Required value |
| --- | --- |
| `camera2FrameLineageCertified` | `true` |
| `camera2FrameLineageFrameCount` | `>= 60` |
| `camera2FrameLineageDurationMs` | `>= 2000` |
| `camera2FrameLineageMatchRatio` | `>= 0.995` |
| `camera2FrameLineageStaleGenerationCount` | `0` |
| `camera2FrameLineageNonMonotonicTimestampCount` | `0` |
| `camera2FrameLineageDuplicateEncoderSubmissionCount` | `0` |
| `camera2FrameLineageEndpointOmissionCount` | `0` |
| `camera2FrameLineagePreviewEncoderFrameDifference` | `<= 1` |

The runtime preview-recording parity certificate also accepts the same accumulator output through:

| Key | Required value |
| --- | --- |
| `camera2FrameLineageCertificatePresent` | `true` when the lineage snapshot was physically produced. |
| `camera2FrameLineageEvidenceWindowFrames` | `>= 60` |
| `camera2FrameLineageEvidenceWindowDurationMs` | `>= 2000` |
| `camera2FrameLineageTimestampMatchRatio` | `>= 0.995` |
| `camera2FrameLineagePreviewEndpointOmissionCount` | `0` |
| `camera2FrameLineageEncoderEndpointOmissionCount` | `0` |
| `camera2FrameLineageSubmittedFrameDifference` | `<= 1` |

Elite 100X metadata keys:

| Key | Meaning |
| --- | --- |
| `singularityElite100xVersion` | `SINGULARITY_ELITE_100X_POLICY_V20` |
| `singularityElite100xReportVersion` | `SINGULARITY_ELITE_100X_REPORT_V20` |
| `singularityElite100xQualified` | `true` only when all Elite 100X authorities pass and release cap is `SINGULARITY-100`. |
| `singularityElite100xReleaseCap` | Maximum class allowed by the Elite 100X layer. |
| `singularityElite100xAuthoritySummary` | Count of `PASS`, `WARN`, `FAIL`, and `NOT_TESTED` authorities. |
| `singularityElite100xBlockingCodes` | Blocking Elite 100X failure codes. |
| `singularityElite100xCompatibilityProfiles` | Visible compatibility caps such as `ELITE-100X-COMPATIBILITY-CAPPED`. |
| `singularityElite100xReportJson` | Full machine-readable Elite 100X report. |

Production release integration:

```text
Stage 2 transport evidence
  + Stage 4 QG01-QG16 results
  + Stage 5 engine evidence
  + Stage 6 device matrix
  + runtime recording metadata
  -> SingularityElite100xPolicy
  -> release class cap
  -> SingularityReleaseGate
  -> SMF-Singularity/release metadata
```

This means a mode can still record truthfully when Elite 100X proof is missing, but it cannot receive the Elite 100X or stronger Singularity release claim until the missing proof is present.

## 4. Architecture

The implemented architecture is an evidence-first camera and recording pipeline:

```text
Camera2 capability and capture evidence
  -> stream, sensor, timing, lens, thermal, memory, and request contracts
  -> hardware/native/encoder/private-master transport classification
  -> existing image-quality, color, AI, NDR, LOG, RAW, and NPV10 surfaces
  -> preview and recording parity evidence
  -> 256-stage Singularity evidence ledger
  -> QG01-QG16 quantified quality gates
  -> Singularity release gate
  -> SMF-Singularity envelope and nested project sidecars
  -> UI status, validation artifacts, and release report
```

Primary authorities:

| Authority | Role |
| --- | --- |
| `SingularityFrameGraphRegistry` | Canonical 256-stage registry |
| `SingularityStageEvidence` | Per-stage runtime truth |
| `SingularityQualityGate` | QG01-QG16 result model and threshold contract |
| `SingularityReleaseGate` | Sole Singularity release-class authority |
| `SmfSingularityMetadata` | SMF-Singularity metadata envelope |
| `SingularityStage2Evidence` | Capture, transport, output, and stream evidence mapping |
| `SingularityStage3Evidence` | Existing pipeline mapping into stage ledger |
| `SingularityStage4QualityGateAutomation` | Automated QG01-QG16 gate production |
| `SingularityStage5HighEndEnginePolicy` | High-end engine accounting and fallback visibility |
| `SingularityStage6DeviceMatrix` | Production device matrix status model |
| `RecordingPipelineParityPolicy` | Preview/recording parity and block/fallback policy |
| `RecordingQualityEvidencePolicy` | Recording certificate and quality evidence |

## 5. Release Classes

| Release class | Rank | Meaning |
| --- | ---: | --- |
| `SINGULARITY-100` | 100 | Highest class. Requires proven high-end hardware transport, all mandatory QG gates passing, finalized/playable output, metadata honesty, no hidden fallback, and complete evidence. |
| `SINGULARITY-75` | 75 | High-end class with truthful expensive-stage reductions allowed only when validated and recorded. |
| `SINGULARITY-50` | 50 | Stable high-end processed output with proven hardware transport and no blocking quality failure. |
| `SINGULARITY-25` | 25 | Hardware-backed, device-safe output with visible reductions and no corrupt output or false metadata. |
| `CORE-HONEST` | 1 | Stable compatibility path that does not prove required Singularity transport or advanced processing. |
| `NO-RELEASE` | 0 | Corrupt output, false metadata, unrecovered finalization failure, critical gate failure, or missing mandatory sidecar. |

The UI may show a provisional class while recording, but the finalized SMF-Singularity envelope is the release authority.

## 6. Transport Classifications

Every recording must resolve to one transport classification:

| Classification | Meaning |
| --- | --- |
| `AHB_VULKAN_ZERO_COPY` | Proven AHardwareBuffer/Vulkan path with validated ownership, synchronization, copy chain, and processed recording handoff. |
| `AHB_GPU_COMPATIBILITY` | Hardware-buffer/GPU compatibility path that is validated but does not meet the full zero-copy claim. |
| `ENCODER_SURFACE_DIRECT` | Direct encoder surface path with validated capability and output evidence. |
| `PRIVATE_MASTER_GPU_EXPORT` | Private-master GPU export path with integrity evidence. |
| `RAW_SENSOR_PRIVATE_MASTER` | Sensor RAW or private RAW master path with stream and storage evidence. |
| `CPU_COMPATIBILITY_NON_SINGULARITY` | Compatibility path. May be stable and high quality, but cannot receive a Singularity release class. |
| `UNPROVEN` | Insufficient proof. Cannot receive a Singularity release class. |

Latest tested path:

| Device | Tested row | Actual path |
| --- | --- | --- |
| Vivo I2405, serial `10BF8M2NDX003P3` | `ndr10_plus_1920x1080_30fps` | `APP_P010_BYTEBUFFER_ENCODER` compatibility route |

## 7. Stage Execution States

Every stage record must use one of these states:

| State | Meaning |
| --- | --- |
| `EXECUTED` | Stage ran with proven evidence. |
| `IDENTITY_PASS` | Stage was intentionally identity because the selected mode required no transform. |
| `FALLBACK_EXECUTED` | Stage ran through a visible fallback with fallback ID and reason. |
| `DISABLED_BY_TIER` | Stage is registered but unavailable for the selected release tier. |
| `DISABLED_BY_DEVICE` | Stage is registered but unavailable on the active device. |
| `FAILED_WITH_DOWNGRADE` | Stage failed and release class must be downgraded or blocked. |
| `FAILED_RECORDING_STOPPED` | Recording stopped or finalization failed in a way that affects the stage. |

Invalid states: null, unknown, inferred-active, name-only, or hidden fallback.

## 8. Stage Evidence Record Values

Every stage evidence record has this schema:

| Field | Required value behavior |
| --- | --- |
| `globalStageId` | Integer from `0` to `255` |
| `blockIndex` | Integer from `0` to `15` |
| `localStageIndex` | Integer from `0` to `15` |
| `stageName` | Stable lowercase API identifier |
| `functionalAuthority` | One of the 16 stage authorities |
| `inputResources` | Actual input resource references |
| `outputResources` | Actual output resource references |
| `minimumReleaseClass` | Class required by that authority |
| `requiredCapabilities` | Capability tokens needed for the stage |
| `executionBackend` | Real backend, for example Camera2, Vulkan, encoder, AI, metadata, or fallback |
| `executionState` | One of the allowed states |
| `fallbackId` | Required for `FALLBACK_EXECUTED` |
| `fallbackReason` | Required for fallback and failure states |
| `gpuDurationNs` | Non-negative GPU timing |
| `nnapiDurationNs` | Non-negative neural/NNAPI timing |
| `cpuControlDurationNs` | Non-negative control-plane timing |
| `confidence` | Decimal from `0.0` to `1.0` |
| `qcGateIds` | Linked QG gates |
| `smfRecordKey` | Stable SMF record key |
| `evidenceReferences` | File, metadata, certificate, or runtime references |

## 9. Quality Gates

All gates are canonical and must appear exactly once in QG output.

| Gate | Title | Unit | Mandatory | Responsible stages | Minimum class |
| --- | --- | --- | --- | --- | --- |
| `QG01` | Edge Overshoot | `percent` | `true` | `172,173,247` | `SINGULARITY-100` |
| `QG02` | Temporal Shimmer | `percent` | `true` | `126,174,247` | `SINGULARITY-100` |
| `QG03` | Chroma Crawl | `normalized` | `true` | `101,109,247` | `SINGULARITY-100` |
| `QG04` | Highlight Rolloff | `delta` | `true` | `87,212,247` | `SINGULARITY-100` |
| `QG05` | Shadow Depth | `delta` | `true` | `88,89,248` | `SINGULARITY-100` |
| `QG06` | Skin Integrity | `deltaE` | `true` | `146,162,198,248` | `SINGULARITY-100` |
| `QG07` | Hair And Fine Texture | `normalized` | `true` | `156,163,164,248` | `SINGULARITY-100` |
| `QG08` | Text Stability | `normalized` | `true` | `152,166,186,248` | `SINGULARITY-100` |
| `QG09` | Neural Resolution Honesty | `normalized` | `true` | `181,184,185,249` | `SINGULARITY-100` |
| `QG10` | Tone Stability | `percent` | `true` | `215,216,250` | `SINGULARITY-100` |
| `QG11` | Color Neutrality | `deltaE` | `true` | `193,194,207,250` | `SINGULARITY-100` |
| `QG12` | Encode Survival | `normalized` | `true` | `231,236,239,251` | `SINGULARITY-100` |
| `QG13` | Preview Match | `delta` | `true` | `216,230,251` | `SINGULARITY-100` |
| `QG14` | Thermal Endurance | `minutes` | `true` | `14,246,252` | `SINGULARITY-100` |
| `QG15` | Dropped Frame Control | `count` | `true` | `235,236,252` | `SINGULARITY-100` |
| `QG16` | Metadata Honesty | `boolean` | `true` | `240,241,253,254,255` | `SINGULARITY-100` |

Gate result states:

```text
PASS
WARN
FAIL
NOT_APPLICABLE
NOT_TESTED
```

Mandatory gate rule: `NOT_TESTED` or `FAIL` blocks `SINGULARITY-100`. `QG16` failure blocks release because metadata honesty is non-negotiable.

## 10. 16 Stage Authorities

| Block | Stage range | Authority | Minimum class | Capability token |
| ---: | --- | --- | --- | --- |
| 00 | `000-015` | Capture Contract | `SINGULARITY-25` | `camera2_capture_contract` |
| 01 | `016-031` | Sensor Calibration | `SINGULARITY-50` | `sensor_calibration_metadata` |
| 02 | `032-047` | RAW Reconstruction | `SINGULARITY-50` | `raw_reconstruction` |
| 03 | `048-063` | Optical Identity | `SINGULARITY-50` | `optical_identity` |
| 04 | `064-079` | Exposure Authority | `SINGULARITY-50` | `exposure_authority` |
| 05 | `080-095` | Dynamic Range Authority | `SINGULARITY-50` | `dynamic_range_authority` |
| 06 | `096-111` | Noise Authority | `SINGULARITY-50` | `noise_authority` |
| 07 | `112-127` | Motion Authority | `SINGULARITY-75` | `motion_authority` |
| 08 | `128-143` | Multi-Lens Authority | `SINGULARITY-75` | `multi_lens_authority` |
| 09 | `144-159` | Subject And Depth Authority | `SINGULARITY-75` | `subject_depth_authority` |
| 10 | `160-175` | Texture Authority | `SINGULARITY-50` | `texture_authority` |
| 11 | `176-191` | Resolution Authority | `SINGULARITY-75` | `resolution_authority` |
| 12 | `192-207` | Color Authority | `SINGULARITY-50` | `color_authority` |
| 13 | `208-223` | Tone And Cinema Look Authority | `SINGULARITY-50` | `tone_cinema_authority` |
| 14 | `224-239` | Output And Encode Authority | `SINGULARITY-25` | `output_encode_authority` |
| 15 | `240-255` | QC And Metadata Authority | `SINGULARITY-25` | `qc_metadata_authority` |

## 11. Complete 256-Stage Catalog

The 256 stages are evidence stages. They are not a requirement for 256 separate shader dispatches or 256 file copies. A fused native pass may satisfy multiple logical stages only when the evidence records the mapping precisely.

### Block 00 - Capture Contract

| ID | Stage |
| ---: | --- |
| 000 | `frame_identity` |
| 001 | `session_identity` |
| 002 | `recording_intent` |
| 003 | `device_capability_snapshot` |
| 004 | `release_candidate_initialization` |
| 005 | `camera_id_binding` |
| 006 | `physical_camera_id_binding` |
| 007 | `stream_combination_validation` |
| 008 | `sensor_request_validation` |
| 009 | `manual_control_packet` |
| 010 | `frame_duration_contract` |
| 011 | `timestamp_alignment` |
| 012 | `imu_window_binding` |
| 013 | `rolling_shutter_metadata` |
| 014 | `thermal_memory_budget_snapshot` |
| 015 | `capture_evidence_record_initialization` |

### Block 01 - Sensor Calibration

| ID | Stage |
| ---: | --- |
| 016 | `black_level_ingest` |
| 017 | `white_level_ingest` |
| 018 | `bayer_pattern_validation` |
| 019 | `active_array_and_crop_validation` |
| 020 | `optical_black_correction` |
| 021 | `fixed_pattern_noise_profile` |
| 022 | `hot_pixel_map` |
| 023 | `sensor_defect_repair` |
| 024 | `lens_shading_map` |
| 025 | `sensor_vignetting_correction` |
| 026 | `row_column_banding_model` |
| 027 | `sensor_noise_profile` |
| 028 | `iso_gain_normalization` |
| 029 | `white_saturation_tagging` |
| 030 | `calibration_confidence` |
| 031 | `calibrated_sensor_output` |

### Block 02 - RAW Reconstruction

| ID | Stage |
| ---: | --- |
| 032 | `raw_packing_validation` |
| 033 | `raw_unpack_and_linearization` |
| 034 | `green_split_balance` |
| 035 | `edge_direction_estimation` |
| 036 | `luma_demosaic_reconstruction` |
| 037 | `chroma_demosaic_reconstruction` |
| 038 | `false_color_suppression` |
| 039 | `zipper_artifact_suppression` |
| 040 | `moire_risk_control` |
| 041 | `clipped_mosaic_reconstruction` |
| 042 | `post_demosaic_defect_repair` |
| 043 | `spectral_normalization` |
| 044 | `camera_rgb_transform` |
| 045 | `linear_range_sanitization` |
| 046 | `reconstruction_detail_confidence` |
| 047 | `linear_rgb_reconstruction_output` |

### Block 03 - Optical Identity

| ID | Stage |
| ---: | --- |
| 048 | `lens_profile_selection` |
| 049 | `focal_aperture_context` |
| 050 | `distortion_map_generation` |
| 051 | `geometric_distortion_correction` |
| 052 | `chromatic_aberration_map` |
| 053 | `lateral_chromatic_aberration_correction` |
| 054 | `longitudinal_chromatic_aberration_confidence` |
| 055 | `diffraction_risk_estimation` |
| 056 | `psf_mtf_estimation` |
| 057 | `deconvolution_confidence` |
| 058 | `optical_recovery` |
| 059 | `flare_veiling_glare_estimation` |
| 060 | `vignette_residual_correction` |
| 061 | `center_edge_resolution_balance` |
| 062 | `optical_artifact_qc` |
| 063 | `optically_corrected_output` |

### Block 04 - Exposure Authority

| ID | Stage |
| ---: | --- |
| 064 | `flicker_frequency_detection` |
| 065 | `exposure_schedule_feasibility` |
| 066 | `base_exposure_schedule` |
| 067 | `shadow_exposure_schedule` |
| 068 | `highlight_exposure_schedule` |
| 069 | `multi_exposure_synchronization` |
| 070 | `exposure_normalization` |
| 071 | `motion_exposure_delta` |
| 072 | `clipped_highlight_mask` |
| 073 | `deep_shadow_mask` |
| 074 | `skin_highlight_protection` |
| 075 | `neon_highlight_protection` |
| 076 | `exposure_confidence` |
| 077 | `exposure_downgrade_ladder` |
| 078 | `exposure_pumping_guard` |
| 079 | `exposure_authority_output` |

### Block 05 - Dynamic Range Authority

| ID | Stage |
| ---: | --- |
| 080 | `base_exposure_reconstruction` |
| 081 | `shadow_exposure_reconstruction` |
| 082 | `highlight_exposure_reconstruction` |
| 083 | `exposure_alignment_confidence` |
| 084 | `exposure_fusion_weights` |
| 085 | `ghost_safe_exposure_fusion` |
| 086 | `highlight_detail_recovery` |
| 087 | `highlight_shoulder_construction` |
| 088 | `shadow_chroma_floor` |
| 089 | `shadow_texture_preservation` |
| 090 | `local_dynamic_range_zone_map` |
| 091 | `global_dynamic_range_estimate` |
| 092 | `tone_headroom_estimate` |
| 093 | `fusion_artifact_qc` |
| 094 | `dynamic_range_fallback_selection` |
| 095 | `scene_linear_dynamic_range_output` |

### Block 06 - Noise Authority

| ID | Stage |
| ---: | --- |
| 096 | `shot_noise_model` |
| 097 | `read_noise_model` |
| 098 | `fixed_pattern_noise_residual` |
| 099 | `banding_risk_map` |
| 100 | `chroma_noise_model` |
| 101 | `chroma_crawl_risk_map` |
| 102 | `temporal_noise_model` |
| 103 | `texture_noise_classifier` |
| 104 | `protected_texture_masks` |
| 105 | `denoise_strength_map` |
| 106 | `spatial_noise_reduction` |
| 107 | `temporal_noise_reduction` |
| 108 | `banding_suppression` |
| 109 | `chroma_stabilization` |
| 110 | `detail_preservation_qc` |
| 111 | `noise_authority_output` |

### Block 07 - Motion Authority

| ID | Stage |
| ---: | --- |
| 112 | `gyro_sample_ingest` |
| 113 | `accelerometer_sample_ingest` |
| 114 | `sensor_imu_timestamp_alignment` |
| 115 | `rolling_shutter_motion_model` |
| 116 | `optical_flow_pyramid` |
| 117 | `dense_sparse_flow_solution` |
| 118 | `imu_flow_fusion` |
| 119 | `motion_occlusion_map` |
| 120 | `subject_motion_map` |
| 121 | `reference_frame_anchor` |
| 122 | `scene_cut_detection` |
| 123 | `temporal_fusion_weights` |
| 124 | `ghost_rejection_mask` |
| 125 | `temporal_accumulation` |
| 126 | `temporal_shimmer_qc` |
| 127 | `motion_authority_output` |

### Block 08 - Multi-Lens Authority

| ID | Stage |
| ---: | --- |
| 128 | `logical_camera_capability` |
| 129 | `physical_stream_feasibility` |
| 130 | `physical_lens_identity` |
| 131 | `lens_intrinsic_calibration` |
| 132 | `lens_extrinsic_calibration` |
| 133 | `multi_lens_timestamp_tolerance` |
| 134 | `multi_lens_exposure_match` |
| 135 | `multi_lens_white_balance_match` |
| 136 | `multi_lens_geometry_alignment` |
| 137 | `parallax_map` |
| 138 | `multi_lens_occlusion_mask` |
| 139 | `per_lens_quality_score` |
| 140 | `multi_lens_fusion_weights` |
| 141 | `multi_lens_fusion` |
| 142 | `multi_lens_seam_qc` |
| 143 | `primary_or_fused_lens_output` |

### Block 09 - Subject And Depth Authority

| ID | Stage |
| ---: | --- |
| 144 | `face_detection` |
| 145 | `face_landmark_tracking` |
| 146 | `skin_confidence_mask` |
| 147 | `subject_segmentation` |
| 148 | `depth_inference` |
| 149 | `depth_confidence` |
| 150 | `foreground_priority_map` |
| 151 | `background_protection_map` |
| 152 | `text_region_mask` |
| 153 | `sky_region_mask` |
| 154 | `foliage_region_mask` |
| 155 | `neon_region_mask` |
| 156 | `hair_fabric_region_mask` |
| 157 | `protected_neutral_mask` |
| 158 | `semantic_mask_temporal_stabilization` |
| 159 | `subject_depth_authority_output` |

### Block 10 - Texture Authority

| ID | Stage |
| ---: | --- |
| 160 | `microtexture_confidence_map` |
| 161 | `edge_confidence_map` |
| 162 | `skin_texture_guard` |
| 163 | `hair_texture_guard` |
| 164 | `fabric_texture_guard` |
| 165 | `foliage_texture_guard` |
| 166 | `text_legibility_guard` |
| 167 | `clipped_edge_guard` |
| 168 | `noise_sharpening_rejection` |
| 169 | `local_contrast_pyramid` |
| 170 | `midtone_detail_recovery` |
| 171 | `confidence_gated_deconvolution` |
| 172 | `edge_halo_clamp` |
| 173 | `ringing_suppression` |
| 174 | `texture_temporal_consistency_qc` |
| 175 | `texture_authority_output` |

### Block 11 - Resolution Authority

| ID | Stage |
| ---: | --- |
| 176 | `native_resolution_confidence` |
| 177 | `resolution_lift_requirement` |
| 178 | `temporal_resolution_feasibility` |
| 179 | `temporal_resolution_inputs` |
| 180 | `temporal_resolution_lift` |
| 181 | `neural_resolution_capability` |
| 182 | `neural_tile_plan` |
| 183 | `neural_tile_overlap_and_blend` |
| 184 | `neural_resolution_lift` |
| 185 | `hallucination_risk_map` |
| 186 | `text_hallucination_rejection` |
| 187 | `tile_seam_validation` |
| 188 | `resolution_temporal_consistency` |
| 189 | `validated_spatial_upscale_fallback` |
| 190 | `output_acuity_qc` |
| 191 | `resolution_authority_output` |

### Block 12 - Color Authority

| ID | Stage |
| ---: | --- |
| 192 | `camera_color_matrix` |
| 193 | `white_balance_gain_stabilization` |
| 194 | `neutral_axis_protection` |
| 195 | `scene_illuminant_estimation` |
| 196 | `chromatic_adaptation` |
| 197 | `working_gamut_conversion` |
| 198 | `skin_color_protection` |
| 199 | `sky_color_protection` |
| 200 | `neon_color_protection` |
| 201 | `foliage_color_protection` |
| 202 | `saturation_chroma_authority` |
| 203 | `gamut_mapping` |
| 204 | `lut_selection` |
| 205 | `tetrahedral_lut_interpolation` |
| 206 | `per_zone_lut_blending` |
| 207 | `color_accuracy_qc_output` |

### Block 13 - Tone And Cinema Look Authority

| ID | Stage |
| ---: | --- |
| 208 | `scene_luminance_analysis` |
| 209 | `display_output_target_selection` |
| 210 | `tone_curve_selection` |
| 211 | `local_tone_zone_map` |
| 212 | `highlight_rolloff` |
| 213 | `shadow_depth_mapping` |
| 214 | `midtone_contrast_mapping` |
| 215 | `tone_temporal_stabilization` |
| 216 | `preview_file_tone_match` |
| 217 | `creative_grain_policy` |
| 218 | `creative_grain_synthesis` |
| 219 | `highlight_halation` |
| 220 | `highlight_bloom` |
| 221 | `optional_anamorphic_streak` |
| 222 | `optional_vignette_gate_character` |
| 223 | `tone_cinema_authority_output` |

### Block 14 - Output And Encode Authority

| ID | Stage |
| ---: | --- |
| 224 | `output_mode_capability_validation` |
| 225 | `output_transform_selection` |
| 226 | `output_bit_depth_validation` |
| 227 | `output_primaries_validation` |
| 228 | `output_transfer_validation` |
| 229 | `output_range_matrix_validation` |
| 230 | `final_gpu_output_transform` |
| 231 | `encoder_capability_probe` |
| 232 | `codec_profile_level_selection` |
| 233 | `bitrate_gop_quality_plan` |
| 234 | `encoder_surface_preparation` |
| 235 | `timestamp_cadence_contract` |
| 236 | `encoder_frame_submission` |
| 237 | `muxer_sample_write` |
| 238 | `container_finalization_and_integrity` |
| 239 | `playable_output_verification` |

### Block 15 - QC And Metadata Authority

| ID | Stage |
| ---: | --- |
| 240 | `stage_ledger_finalization` |
| 241 | `smf_singularity_envelope_initialization` |
| 242 | `camera_device_evidence_record` |
| 243 | `vulkan_transport_evidence_record` |
| 244 | `neural_model_evidence_record` |
| 245 | `encoder_output_evidence_record` |
| 246 | `thermal_memory_drop_evidence_record` |
| 247 | `qg01_qg04_edge_motion_chroma_highlight` |
| 248 | `qg05_qg08_shadow_skin_texture_text` |
| 249 | `qg09_neural_resolution_honesty` |
| 250 | `qg10_qg11_tone_color_stability` |
| 251 | `qg12_qg13_encode_preview_parity` |
| 252 | `qg14_qg16_endurance_drop_metadata` |
| 253 | `singularity_release_class_evaluation` |
| 254 | `sidecar_finalization_and_recovery` |
| 255 | `final_release_seal` |

## 12. Implementation Stage Completion

The repository implementation sequence has six stages. The status below describes the codebase integration, not a universal device certification for every possible phone and every mode.

| Implementation stage | Scope | Repository status |
| --- | --- | --- |
| Stage 1 | Authority and evidence foundation | Complete in code. 256-stage registry, stage evidence model, release classes, QG types, release gate, SMF envelope, and asset manifest validators are present. |
| Stage 2 | Transport and capture evidence | Complete in code. Camera2 stream/request/capture evidence, transport classification, output evidence, copy/readback/sync evidence, and stage mapping for capture/output ranges are present. |
| Stage 3 | Existing pipeline mapping | Complete in code. Existing NDR, LOG, RAW, NPV10, color, tone, shader/native, AI, and preview/recording parity evidence map into the ledger. Unsupported authorities are disabled or fallback-executed visibly. |
| Stage 4 | Quality gate automation | Complete in code. QG01-QG16 are generated, threshold asset is validated, preview/encode/metadata gates are represented, and gate output feeds release policy. |
| Stage 5 | Missing high-end engine accounting | Complete in code as evidence-gated authority. High-end engines report implemented/evidenced, needs device evidence, fallback, disabled, or not tested. Missing neural or hardware-specific engines are not falsely claimed. |
| Stage 6 | Production device matrix | Complete in code/scripts. Real-device, scene, endurance, performance, and release-gate entry points are present. Latest executed proof is one passing V16 NDR10+ row on the connected Vivo device. |

## 13. Stage 5 Engine Authorities

Stage 5 tracks high-end engine authority as a complete set. Every engine must report a status.

| Engine ID | Stage range | Role |
| --- | --- | --- |
| `RAW_RECONSTRUCTION` | `032-047` | RAW packing, unpacking, demosaic, spectral normalization, and linear reconstruction authority. |
| `OPTICAL_RECOVERY` | `048-063` | Lens identity, distortion, chromatic correction, MTF/PSF, deconvolution confidence, and optical artifact QC. |
| `EXPOSURE_DYNAMIC_RANGE` | `064-095` | Exposure scheduling, multi-exposure feasibility, dynamic range fusion, highlight/shadow recovery, and fallback selection. |
| `NOISE_TEXTURE_SEPARATION` | `096-111` | Noise models, denoise strength maps, temporal/spatial noise reduction, banding suppression, chroma stabilization, and detail preservation. |
| `MOTION_TEMPORAL` | `112-127` | IMU, rolling shutter, optical flow, motion maps, temporal accumulation, and shimmer QC. |
| `MULTI_LENS_FUSION` | `128-143` | Logical/physical lens capability, calibration, alignment, parallax, lens fusion, and seam QC. |
| `SUBJECT_DEPTH` | `144-159` | Face, skin, subject, depth, semantic masks, temporal stabilization, and subject/depth output. |
| `TEXTURE_AUTHORITY` | `160-175` | Skin, hair, fabric, foliage, text, halo, ringing, and texture consistency guards. |
| `RESOLUTION_HONESTY` | `176-191` | Native resolution confidence, temporal/neural lift, hallucination risk, seam validation, and output acuity. |
| `COLOR_AUTHORITY` | `192-207` | Camera color, WB stability, gamut conversion, protected color zones, LUT, and color QC. |
| `TONE_STABILITY` | `208-223` | Luminance, display target, tone curve, highlight/shadow mapping, preview/file match, and creative look identity/disablement. |
| `QC_AUTOMATION` | `247-255` | QG rollups, release evaluation, sidecar finalization, and final seal. |

Allowed Stage 5 statuses:

```text
IMPLEMENTED_AND_EVIDENCED
IMPLEMENTED_NEEDS_DEVICE_EVIDENCE
FALLBACK_EXECUTED
DISABLED_BY_DEVICE
NOT_TESTED
```

## 14. Output Mode Behavior

| Mode family | Singularity behavior |
| --- | --- |
| Normal SDR | Existing Normal SDR identity is preserved. High-end path targets real 10-bit capture/processing/output only when stream graph, encoder, and container prove it. 8-bit fallback must be labeled as compatibility output. |
| Pro SDR | Existing Pro SDR identity is preserved. Pro controls, stronger bitrate policy, color/tone evidence, and parity checks apply. |
| NDR / NDR10+ / NIVUKX Vision | Proprietary NeoProCamX/NIVUKX dynamic-range modes. They are not Dolby Vision or HDR10+ certification modes. Android BT.2020/PQ/HLG transport tags are allowed only as truthful color signaling. |
| LOG / N-LOG | Proprietary NeoProCamX/NIVUKX LOG family. Existing curve/version remains authoritative. Vendor LOG substitution is not allowed. |
| RAW monitor / NRAW | RAW is available only when real Camera2 RAW stream combinations and storage paths are proven. Processed RGB output cannot be relabeled as unprocessed RAW. |
| NPV10 master | Existing private master semantics are preserved. It is not redefined as ordinary 8-bit social MP4. Integrity auditing and sidecar evidence remain authoritative. |
| NPV10 review | Optional derivative generated from a valid NPV10 master. It never replaces or weakens the master. |
| APEX Sharp / Zero / Cinema | Presentation and enhancement families are evidence-gated. Clean extreme-detail profile disables creative grain, halation, bloom, streaks, gate weave, and vignette unless explicitly selected. |

## 15. Color Science And Color Space Selection

Manual color science and color space selection before recording is part of the upgrade. The app must keep color identity, transfer, matrix, range, LUT, and output transform truthful.

Supported authority names represented by the design:

| Color or tone authority | Behavior |
| --- | --- |
| `Rec.709` | SDR delivery and compatibility color space when selected and proven. |
| `Rec.2020` | Wide-gamut delivery/transport when selected and encoded truthfully. |
| `Neo Wide Gamut` | Proprietary NeoProCamX wide-gamut working space identity. |
| `ACES AP0/AP1` | Project color-authority mapping target where supported. |
| `Neo ACES high-end` | NeoProCamX high-end ACES-style color authority without vendor certification claim. |
| `NDR10+` | Proprietary NDR color/tone family with truthful Android HDR transport tags where applicable. |
| `NIVUKX Vision` | Proprietary NeoProCamX/NIVUKX vision profile family. |
| Manual color science selection | User-selected color authority is recorded before capture and must match output metadata. |
| Manual color space selection | User-selected primaries/transfer/matrix/range must match encoded pixels or force fallback/downgrade. |

## 16. Preview And Recording Parity

The upgrade requires the preview pipeline and recording pipeline to match in framing, tone intent, color intent, active mode, and visible reduction reporting.

The latest deploy fix improved the parity policy:

| Policy area | Behavior after upgrade |
| --- | --- |
| Hard failures | Transport, format, bit depth, geometry, bypass, hard color mismatch, and performance failures can block recording. |
| Evidence-only identity mismatch | Signature, quality signature, enhancement identity, and grade identity mismatches remain recorded for QG13/release impact but no longer falsely block a valid recording start. |
| Recording block flag | `recordingBlocked` now applies to hard failures only. |
| QG13 | Preview/recording mismatch can still cap release quality through evidence and gate policy. |
| User visibility | Downgrade/fallback reason remains visible and persisted. |

Files updated for the final deploy blocker:

| File | Purpose |
| --- | --- |
| `app/src/main/java/com/neoproc/neoproccamx/ui/RecordingPipelineParityPolicy.kt` | Separates hard blocking failures from evidence-only parity failures. |
| `app/src/test/java/com/neoproc/neoproccamx/ui/RecordingPipelineParityPolicyTest.kt` | Verifies parity policy behavior. |
| `app/src/main/java/com/neoproc/neoproccamx/ui/MainActivity.kt` | Debug record-clip automation now waits for active recording before scheduling stop. |
| `app/src/test/java/com/neoproc/neoproccamx/ui/RecordingStartSessionRefreshContractTest.kt` | Verifies debug recording stop scheduling contract. |

## 17. SMF-Singularity Envelope

The SMF envelope is the top-level truth record. It wraps existing NeoProCamX evidence formats instead of replacing them.

Required top-level values:

```json
{
  "formatVersion": "SMF-SINGULARITY-1",
  "app": "NeoProCamX",
  "applicationId": "com.neoproc.neoproccamx",
  "pipeline": "VUKX_SOVEREIGN_APEX_100000_0",
  "qualityTarget": "SINGULARITY_LARGE_FORMAT_INTERNAL",
  "sourceDirectiveAlias": "EXACT_IMAX_LEVEL_INTERNAL",
  "ambitionLaw": "10000000000000XXXX",
  "officialDolbyVisionCertificationClaimed": false,
  "officialHdr10PlusCertificationClaimed": false,
  "officialHdr10CertificationClaimed": false,
  "frameGraphStages": 256,
  "releasePolicyVersion": "SINGULARITY_RELEASE_POLICY_1",
  "gatePolicyVersion": "QG_SINGULARITY_1"
}
```

Nested metadata identities remain valid:

| Nested evidence | Purpose |
| --- | --- |
| `NeoProfileTruthMetadata` | NDR/LOG/profile truth, vendor claim flags, transport notes. |
| `RecordingQualityEvidenceCertificate` | Codec, bit depth, color, bitrate, cadence, file, and quality evidence. |
| `OmegaQualityTruthReport` | Existing quality truth evidence. |
| `NPV10 container version` | Private master evidence. |
| `NPV12 profile version` | Private profile evidence where active. |
| `N-LOG curve version` | Proprietary LOG curve evidence. |
| `nivukx-vision-a1-a15-v1` | NIVUKX Vision nested evidence where active. |
| `nivukx-tmb-draft-12` | TMB nested evidence where active. |

## 18. Real-Device Deployment Values

Latest deployed APK:

| Field | Value |
| --- | --- |
| APK path | `app\build\outputs\apk\debug\app-debug.apk` |
| APK size | `276,753,521` bytes |
| APK last write time | `2026-06-08 20:01:52` |
| Installed device | `10BF8M2NDX003P3` |
| Device model family | Vivo I2405 |
| Package | `com.neoproc.neoproccamx` |
| Version code | `1` |
| Version name | `1.0` |
| Minimum SDK | `29` |
| Target SDK | `36` |
| Device install time | `2026-06-08 20:02:20` |

Build/test commands that passed:

```powershell
.\gradlew.bat :app:testDebugUnitTest --rerun-tasks --no-daemon --no-configuration-cache --console=plain
.\gradlew.bat :app:assembleDebug --no-daemon --no-configuration-cache --console=plain
adb -s 10BF8M2NDX003P3 install -r app\build\outputs\apk\debug\app-debug.apk
```

Full unit suite result:

```text
BUILD SUCCESSFUL in 5m 1s
```

## 19. Real-Device V16 Validation Values

Validation command:

```powershell
.\tools\quality\run-v16-real-device-encoder-validation.ps1 `
  -AdbPath 'C:\Users\acer\AppData\Local\Android\Sdk\platform-tools\adb.exe' `
  -Serial 10BF8M2NDX003P3 `
  -PackageName com.neoproc.neoproccamx `
  -OutputDir app\build\stage6-device-test\v16-passcheck `
  -IncludeRows ndr10_plus_1920x1080_30fps `
  -MaxRows 1 `
  -IsolateRows `
  -SkipInstall
```

Validation summary:

```text
[V16] Summary PASS=1 NOT_PROVEN=0 FAIL=0
```

Report path:

```text
app\build\stage6-device-test\v16-passcheck\v16-real-device-encoder-report.md
```

Measured row:

| Field | Value |
| --- | --- |
| Status | `PASS` |
| Row ID | `ndr10_plus_1920x1080_30fps` |
| Mode | `HDR` |
| Codec | `hevc` |
| Pixel format | `yuv420p10le` |
| Resolution | `1920x1080` |
| FPS | `30.01877111281148` |
| Bitrate | `156,496,589 bps` |
| Bit depth | `10` |
| Transfer | `ST2084/PQ` |
| Primaries | `BT.2020` |
| Matrix | `BT.2020-NCL` |
| Frame count | `96` |
| Rotation | `270` degrees |
| Mirrored | `false` |
| Recorded file bytes | `62,663,334` |
| MediaStore ID | `1000145193` |
| MediaStore display name | `CAMX_GPU_v15_ndr10_plus_1920x1080_30fps_20260608_200319.mov` |
| MediaStore color standard | `6` |
| MediaStore color transfer | `6` |
| ByteBuffer P010 frames queued | `0` |
| ByteBuffer P010 frame drops | `-1` |
| Device log validated | `true` |
| Strict 10 quality proven | `true` |
| GPU path report value | `APP_P010_BYTEBUFFER_ENCODER` |
| CPU percent report value | `20` |
| Max frame ms report value | `0` |
| Failures | empty |
| Warnings | empty |

Vendor and proprietary-identity values:

| Field | Value |
| --- | --- |
| `VendorHdrCertificationFlagsFalse` | `true` |
| `VendorHdrCertification` | `false` |
| `OfficialHdr10PlusMetadataWritten` | `false` |
| `VendorCertifiedHdr10Plus` | `false` |
| `VendorCertifiedDolbyVision` | `false` |
| `DolbyRpuWritten` | `false` |
| `ExternalHdrStandardSubstitution` | `false` |
| `ProprietaryProfilePreserved` | `true` |
| `AndroidHdrTransportOnly` | `true` |
| `NeoHdrNoVendorDependency` | `true` |
| `NLogVendorDependency` | `false` |
| `SyntheticGrainEnabled` | `false` |

ffprobe values:

```text
codec_name=hevc
width=1920
height=1080
pix_fmt=yuv420p10le
color_space=bt2020nc
color_transfer=smpte2084
color_primaries=bt2020
r_frame_rate=30/1
avg_frame_rate=96000000/3197999
duration=3.197999
bit_rate=156496589
bits_per_raw_sample=N/A
nb_read_frames=96
rotation=-90
```

Generated proof artifacts:

| Artifact | Path |
| --- | --- |
| V16 report | `app\build\stage6-device-test\v16-passcheck\v16-real-device-encoder-report.md` |
| Local proof video | `app\build\stage6-device-test\v16-passcheck\v15\ndr10_plus_1920x1080_30fps\ndr10_plus_1920x1080_30fps.mov` |
| Quality certificate | `app\build\stage6-device-test\v16-passcheck\v15\ndr10_plus_1920x1080_30fps\ndr10_plus_1920x1080_30fps.quality-certificate.txt` |
| MediaInfo JSON | `app\build\stage6-device-test\v16-passcheck\v15\ndr10_plus_1920x1080_30fps\ndr10_plus_1920x1080_30fps.mediainfo.json` |
| ffprobe output | `app\build\stage6-device-test\v16-passcheck\v15\ndr10_plus_1920x1080_30fps\ndr10_plus_1920x1080_30fps.ffprobe.txt` |

## 19.1 V18 Strict P010 Validation

The V18 validator tightens V16 behavior for the `APP_P010_BYTEBUFFER_ENCODER` compatibility path.

Required values for a P010 byte-buffer row to remain `PASS`:

| Evidence | Required value |
| --- | --- |
| `GpuProcessingPath` | `APP_P010_BYTEBUFFER_ENCODER` triggers strict P010 proof checks. |
| `ByteBufferP010Proof` | `true` |
| `ByteBufferP010FramesQueued` | Greater than `0` |
| `ByteBufferP010FrameDrops` | `0` |
| Log evidence | Must contain `P010 bytebuffer EOS`. |
| Final summary | Must contain `P010 bytebuffer evidence ->`. |

If any of those values are missing, V16/V18/V19/V20 marks the row `NOT_PROVEN` instead of `PASS`. This preserves truthful compatibility output while preventing a high-end claim based only on codec, bit depth, color tags, or name-only frame lineage.

## 19.2 Atomic Certificate Sidecar

The recording quality certificate now writes through an atomic temp-file path with file-descriptor sync before rename.

| File | Upgrade |
| --- | --- |
| `RecordingQualityCertificateStore.kt` | Writes `*.quality-certificate.txt.tmp`, flushes and syncs it, then renames to the final certificate. |
| `RecordingQualityEvidencePolicyTest.kt` | Verifies certificate content and that no temp file remains after a successful write. |

This reduces the risk of partial evidence files after interrupted stop/finalization.

## 20. How To Access The Upgrade In The App

Use the installed app package `com.neoproc.neoproccamx`.

Verified real-device access path:

1. Open `NeoProCamX`.
2. Enter the video recording mode.
3. Select the high-end HDR/NDR profile.
4. Select `NDR10+` / NDR proprietary mode.
5. Select `1920x1080`.
6. Select `30 fps`.
7. Select a wide-gamut/PQ-capable NDR color transport profile where available, such as BT.2020/PQ NDR.
8. Start recording.
9. Stop recording and wait for finalization.
10. Check the saved `.mov` and generated certificate/report evidence.

V20 Normal/Pro SDR access path:

1. Open video mode.
2. Select `Normal SDR` or `Pro SDR`.
3. Select `Natural Detail` for motion-locked microdetail or `Extreme Detail` for the strongest evidence-gated cleanup/detail profile.
4. Use automatic exposure to allow the V20 motion-stable Camera2 policy to shorten exposure when measured motion crosses the policy threshold. Manual ISO/exposure remains under user control.
5. Select automatic bitrate so source motion/noise pressure can raise the encoder floor. Manual bitrate and cinema-master plans remain authoritative.
6. Record and stop normally, then inspect the quality certificate and SMF-Singularity sidecar for V20 policy, metric, fallback, and release-class evidence.

V20 is automatic inside the eligible Normal/Pro SDR real-time graph. There is no separate vendor HDR, Dolby Vision, or IMAX switch.

Expected HUD/status behavior:

| UI item | Expected behavior |
| --- | --- |
| Active mode | Shows selected output mode, for example NDR/NDR10+ family. |
| Resolution/FPS | Shows active size and frame rate. |
| Bit depth evidence | Shows 10-bit only when proven by output evidence. |
| Transport | Shows the actual path or fallback. On the tested device, the proven path was `APP_P010_BYTEBUFFER_ENCODER`. |
| Quality class | Shows provisional class during recording and finalized class only after evidence is sealed. |
| Fallback reason | Visible when the device uses compatibility fallback. |
| Finalization | Shows safe stop/finalization state. |

## 21. What This Upgrade Improves

Camera and stream control:

| Area | Upgrade effect |
| --- | --- |
| Camera2 flow | Deterministic open/configure/repeat/stop/close evidence and recovery behavior. |
| CameraX/Camera2 boundary | Camera2 remains the runtime capture authority; evidence is centralized. |
| Lens selection | Logical/physical camera identity and physical-stream proof are represented. |
| FPS policy | Frame duration, cadence, and output FPS are measured and recorded. |
| Stream graph | Stream combination validation occurs before mode exposure. |
| Metering regions | Manual control packet and capture-result truth are recorded. |
| Exposure/WB/focus policies | Request intent and capture-result evidence are captured. |
| Stabilization | Stabilization and motion evidence are represented through capture and motion authorities. |
| Preview sizing/aspect | Preview/file parity is explicitly measured and can affect QG13/release class. |

Focus and AI:

| Area | Upgrade effect |
| --- | --- |
| Tap focus | Preserved through request policy and metering evidence. |
| AI autofocus | Accounted as optional hardware/model authority and never falsely claimed if unavailable. |
| Face/eye focus | Face/landmark stages are present and evidence-gated. |
| Object/subject tracking | Subject segmentation and foreground priority stages are present and evidence-gated. |
| AF target stability | Motion, subject, and focus evidence are available for stability analysis. |
| Focus distance control | Manual control packet and capture-result truth are recorded where hardware supports it. |

Quality and color:

| Area | Upgrade effect |
| --- | --- |
| SDR quality | Normal SDR and Pro SDR retain identity with stronger bit-depth, bitrate, tone, and denoise/sharpening truth. |
| NDR quality | Proprietary NDR is preserved, evidence-gated, and not replaced by vendor HDR certification. |
| LOG quality | Proprietary N-LOG is preserved, evidence-gated, and not replaced by vendor LOG. |
| RAW monitor | RAW paths require real RAW stream/storage evidence. |
| NPV10 | Private master semantics and integrity auditing remain stronger than social-delivery MP4. |
| Color science | Manual color science and color space selection are tied to metadata truth. |
| YCbCr/GPU uniforms | Shader/native bridge, uniforms, and parity evidence are represented through Stage 3 and QG13. |
| Denoise/sharpen/detail | Noise, texture, halo, ringing, and hallucination gates prevent fake detail claims. |
| Bitrate enforcement | Encoder capability, profile, GOP/bitrate plan, sample write, and finalization evidence are stage-gated. |

Metadata and release honesty:

| Area | Upgrade effect |
| --- | --- |
| SMF envelope | Adds `SMF-SINGULARITY-1` as top-level proof envelope. |
| Stage ledger | Adds 256-stage accounting. |
| Release gate | Prevents stronger release label without evidence. |
| QG gates | Adds QG01-QG16 quality and metadata gates. |
| Vendor flags | Forces Dolby Vision, HDR10+, HDR10 certification flags false unless real certification exists. |
| Fallback reporting | No hidden fallback or downgrade is allowed. |

## 22. What You Should Expect From This Upgrade

On hardware that proves the full path, the upgrade can unlock higher release classes by proving high-end transport, full gate pass, high bit depth, strong metadata, and stable finalization.

On hardware that cannot prove a full zero-copy or GPU encoder surface path, the app still records the best truthful supported output and visibly reports fallback. This is why the tested Vivo I2405 row produced a real 10-bit HEVC BT.2020/PQ file through `APP_P010_BYTEBUFFER_ENCODER` instead of falsely claiming `AHB_VULKAN_ZERO_COPY`.

Expected improvements:

| User expectation | Implemented behavior |
| --- | --- |
| Higher quality video | 10-bit HEVC NDR10+ proof was recorded for 1080p30 on the tested device. |
| Better detail/sharpness | V20 applies a noise- and motion-dependent sharpening ceiling, motion-locked microdetail policy, halo/ringing controls, and measured microdetail/sharpness release thresholds. |
| Lower noise | V20 raises spatial/chroma cleanup from measured luma, chroma, RGB variance, and ISO pressure while motion-capping temporal denoise. Measured grain residual and denoise safety determine qualification. |
| Less motion blur | Auto-exposure processed video can request a stable FPS range and a one-half/one-third/one-quarter-frame exposure cap. Measured residual blur and shimmer still decide release status. |
| Better color | Rec.709, Rec.2020, Neo Wide Gamut, ACES-style authorities, NDR, and NIVUKX identities are represented with metadata truth. |
| Preview same as recording | Preview/recording parity is evidence-gated through QG13. |
| No external vendor substitution | Vendor certification and substitution flags are explicitly false in proof. |
| No hidden downgrade | Fallbacks are recorded in stage evidence, certificates, reports, and UI status. |
| Better performance discipline | FPS, dropped frames, thermal, cadence, and stop/finalization behavior are part of the release gate. |
| Elite 100X proof discipline | V20 class claims require threshold SHA, pixel/chart artifact attestation, V19 per-frame Camera2 lineage parity, measured bitrate density, critical engine evidence, zero-grain clarity, microdetail honesty, anti-motion-blur evidence, and strict transport proof. |

## 23. Remaining Certification Scope

The implementation is complete in the repository, but universal `SINGULARITY-100` certification for every phone, mode, resolution, frame rate, scene, and duration still requires running the full production matrix.

Required remaining proof for broad release claims:

| Scope | Required proof |
| --- | --- |
| All modes | Run Normal SDR, Pro SDR, NDR, LOG, RAW, NPV10 master, and review profiles. |
| All resolutions | Run 1080p30, 1080p60, 4K24, 4K30, 4K60 where the device supports them. |
| All device classes | Run on each target Android device and store device-specific threshold hashes. |
| All quality gates | Produce QG01-QG16 and V20 zero-grain/microdetail/motion measured results, not placeholders or control-strength proxies. |
| Endurance | Run 5-minute preview, 10-minute recording, 20-minute thermal stress, repeated start/stop, and storage-pressure tests. |
| Scene matrix | Run daylight face, backlit face, night face, neon, foliage, text, fabric, sky gradient, motion, and chart scenes. |
| Large-screen review | Validate on phone, laptop/tablet, calibrated 4K display where available, 200-percent crop, normal speed, and frame-by-frame motion. |

Until those complete on a device/mode pair, the app must report the strongest truthful class supported by actual evidence.

## 24. Validation Entry Points

Repository quality scripts:

| Script | Purpose |
| --- | --- |
| `tools\quality\run-v16-real-device-encoder-validation.ps1` | Real-device encoder and output validation. |
| `tools\quality\run-singularity-device-lab.ps1` | Device-lab execution entry point. |
| `tools\quality\run-singularity-scene-matrix.ps1` | Scene-matrix execution entry point. |
| `tools\quality\run-singularity-endurance.ps1` | Endurance execution entry point. |
| `tools\quality\run-singularity-performance-gate.ps1` | Performance gate execution entry point. |
| `tools\quality\run-singularity-release-gate.ps1` | Release-gate execution entry point. |
| `tools\quality\singularity-scenes.json` | Scene matrix definition. |

Core Gradle validation:

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon --no-configuration-cache --console=plain
.\gradlew.bat :app:assembleDebug --no-daemon --no-configuration-cache --console=plain
```

## 25. Final Truth Statement

`NeoProCamX Singularity 100000` is the high-end evidence-driven upgrade name for the implemented repository architecture.

The current deployment proves this exact claim:

```text
NeoProCamX built and deployed successfully.
The V16 real-device validation produced a PASS for NDR10+ 1920x1080 30fps.
The output is HEVC 10-bit yuv420p10le with BT.2020 primaries, PQ transfer, BT.2020-NCL matrix, 96 frames, 30.018771 fps, and 156,496,589 bps bitrate.
No Dolby Vision, HDR10+, HDR10, IMAX, or other external certification was claimed.
The proprietary NDR/NIVUKX identity was preserved.
The tested device used a visible P010 bytebuffer compatibility encoder path, not a proven AHB/Vulkan zero-copy encoder path.
```

That is the correct high-end result for the tested device and the correct access path for this upgrade today.
