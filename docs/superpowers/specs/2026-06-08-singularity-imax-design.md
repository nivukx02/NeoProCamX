# Singularity High-End Project Integration Design

Status: single-file authoritative repository design

Source package:
`NEOPROCAMX_SOVEREIGN_APEX_100000.0_SINGULARITY_IMAX_ELITE_ULTRA_PRO_MAX_DOCS`

This file contains the implementation design, the complete 256-stage catalog,
and the source-to-project traceability map.

## 1. Purpose

This design consolidates the complete 4.0, 5.0, 10.0, 100.0, and 100000.0
source package into one project-compatible authority for NeoProCamX.

The source package remains the product vision and requirement origin. This
document is the implementation authority for this repository. It preserves all
quality capabilities while resolving conflicts in package name, module layout,
stage counts, metadata generations, output semantics, and hardware fallback.

The program target is the highest image quality that the active Android device
can execute and prove. No quality feature is deleted because a device cannot
run it. Unsupported capability remains in the canonical registry and is
reported as unavailable, disabled, or fallback-executed.

The word "IMAX" in source document names describes an internal large-format
quality ambition. NeoProCamX does not claim official IMAX, IMAX Enhanced,
Dolby Vision, HDR10+, HDR10, ARRI, RED, Sony, or other external certification.

## 2. Precedence And Compatibility

When package documents conflict, use this order:

1. This repository design.
2. The 256-stage catalog in section 21 of this file.
3. The source-to-project traceability map in section 22 of this file.
4. Source documents 24 through 31.
5. Source documents 18 through 23.
6. Source documents 13 through 17.
7. Source documents 01 through 12.

The 18-stage, 24-stage, and 64-stage graphs are preserved as historical
functional groupings. They do not compete with the canonical 256-stage release
ledger. Existing 64-slot runtime telemetry is an implementation detail, not the
Singularity stage count.

SMF-4, SMF-5, SMF-10, SMF-100, NPV10, NPV12, N-LOG, NIVUKX Vision, and TMB
versions remain valid nested evidence formats. `SMF-SINGULARITY-1` is an
envelope over those records, not a replacement for their schemas.

The repository namespace remains:

```text
com.neoproc.neoproccamx
```

The source proposal `com.nivukx.neoprocamx` must not create a parallel package
tree. The Gradle project remains `:app` plus `:core-math` until a separately
approved module extraction is justified by build or ownership boundaries.

## 3. Non-Negotiable Quality Rules

- Sensor data and measured capture metadata are the truth source.
- Vulkan owns full-frame processed video work after hardware-buffer import.
- Kotlin owns lifecycle, state, policy, and compact metadata, not hot-path pixels.
- No full-frame Bitmap conversion, YUV roundtrip, or CPU image processing may be
  represented as the Singularity zero-copy path.
- NDR and LOG remain proprietary NeoProCamX/NIVUKX technologies. They must not be
  replaced or relabeled as vendor HDR standards.
- Official external certification flags remain false or absent in every mode.
- No active engine, model, shader, zero-copy path, RAW mode, codec, bit depth, or
  multi-camera feature may be claimed without runtime evidence.
- No hidden fallback or downgrade is permitted.
- Missing capability preserves the design requirement but lowers the runtime
  release class or hides the unsupported mode.
- No sharpening-created fake resolution, edge halos, ringing, waxy skin,
  temporal shimmer, chroma crawl, hallucinated text, frozen grain, sky banding,
  hard highlight clipping, corrupt output, or missing sidecar is acceptable for
  the top release class.
- Stability, file integrity, metadata truth, and sustained recording outrank a
  short-lived peak-quality configuration.

## 4. Project Architecture

Singularity extends the current application. It does not replace working camera,
rendering, recording, color, AI, NPV10, NDR, LOG, or validation systems.

```text
Camera2 capability and capture evidence
  -> stream, sensor, timing, lens, thermal, and memory contracts
  -> AHardwareBuffer/Vulkan or explicitly classified compatibility transport
  -> existing image-quality and AI execution surfaces
  -> encoder/private-master output surfaces
  -> 256-stage Singularity evidence ledger
  -> quantified quality gates
  -> Singularity release gate
  -> SMF-Singularity envelope and existing nested sidecars
  -> UI status, device-lab artifacts, and release report
```

Single-source authorities:

```text
SingularityFrameGraphRegistry  canonical 256-stage definition
SingularityStageEvidence       per-session/per-frame execution truth
SingularityQualityGate         QG01-QG16 results
SingularityReleaseGate         sole Singularity release-class authority
SmfSingularityMetadata         sole Singularity metadata envelope
```

Existing `RecordingQualityEvidenceCertificate`, `OmegaQualityTruthReport`,
`VulkanRenderGraphLock`, `NeoProfileTruthMetadata`, NPV10 audits, encoder
evidence, and device-lab artifacts remain evidence producers. They must not
independently issue a stronger Singularity release label.

## 5. Canonical 256-Stage Ledger

The complete stage names and IDs are defined in section 21 of this file.

The 256 stages are an accounting and proof graph. They are not a requirement for
256 shader dispatches, source files, allocations, or image copies. Multiple
logical stages may be satisfied by one validated native pass. One logical stage
may also span multiple implementations when the evidence maps them precisely.

Every stage record must contain:

```text
globalStageId
blockIndex
localStageIndex
stageName
functionalAuthority
inputResources
outputResources
minimumReleaseClass
requiredCapabilities
executionBackend
executionState
fallbackId
fallbackReason
gpuDurationNs
nnapiDurationNs
cpuControlDurationNs
confidence
qcGateIds
smfRecordKey
evidenceReferences
```

Allowed execution states:

```text
EXECUTED
IDENTITY_PASS
FALLBACK_EXECUTED
DISABLED_BY_TIER
DISABLED_BY_DEVICE
FAILED_WITH_DOWNGRADE
FAILED_RECORDING_STOPPED
```

Unknown, null, inferred-active, and name-only states are invalid.

## 6. Transport Classification

Every recording receives one transport classification:

```text
AHB_VULKAN_ZERO_COPY
AHB_GPU_COMPATIBILITY
ENCODER_SURFACE_DIRECT
PRIVATE_MASTER_GPU_EXPORT
RAW_SENSOR_PRIVATE_MASTER
CPU_COMPATIBILITY_NON_SINGULARITY
UNPROVEN
```

`SINGULARITY-100` requires a proven GPU/hardware-backed path for all full-frame
processed video work. `CPU_COMPATIBILITY_NON_SINGULARITY` and `UNPROVEN` cannot
receive a Singularity release class.

An existing P010 or byte-buffer export may remain a high-quality project output,
but it cannot be called zero-copy unless the session evidence proves the exact
buffer ownership and copy chain.

Required transport evidence includes:

- Camera2 stream combination and format.
- Hardware-buffer format, usage, dimensions, stride, and ownership.
- Vulkan extension and import success.
- Fence/semaphore synchronization path.
- Queue family and queue fallback.
- Preview and encoder handoff.
- Readback/copy count.
- Per-frame allocation and pool behavior.

## 7. Camera2 Contract

The Camera2 layer must provide:

- Deterministic open, configure, repeat, stop, close, and disconnect recovery.
- Stream-combination validation before exposing a mode.
- Manual exposure, ISO, frame duration, focus, and white balance where supported.
- Stable repeating-request ownership without race conditions.
- Logical and physical camera identity with physical-stream proof.
- Capture-result recording for exposure, sensitivity, frame duration, focus,
  aperture, focal length, rolling-shutter skew, color transform, black level,
  timestamp, stabilization, and active physical camera.
- Centralized device-quirk profiles.
- Explicit autofocus, exposure, and white-balance instability reporting.

RAW and multi-camera modes must be hidden when their real stream combinations
cannot be created. TEIV exposure fusion must follow:

```text
3 exposure -> 2 exposure -> single-frame recovery -> mode unavailable
```

The selected step and reason must be recorded.

## 8. Vulkan And Native Contract

Minimum advanced-mode requirements:

- Vulkan 1.1 plus Android hardware-buffer import.
- Vulkan 1.2 or newer preferred.
- Required extension, format, descriptor, and synchronization validation.
- Timeline semaphore when validated; binary semaphore fallback when validated.
- Dedicated compute/transfer queues only when the device proves them reliable.
- Frame-graph-owned pooled resources with no unbounded per-frame allocation.
- Explicit image layout, access mask, queue ownership, and lifetime tracking.
- Stable descriptor layouts and 16-byte-safe push-constant layouts.
- Shader compile, SPIR-V validation, binding validation, golden-image validation,
  and minimum/maximum-resolution stress tests.
- Per-stage or fused-pass GPU timings for qualifying high-end classes.

Required shader group union:

```text
raw_cleanup
demosaic
spectral_normalize
color_normalize
optical_correction
optical_recovery
exposure_fusion
dynamic_range
noise_authority
motion_flow
temporal_accumulation
multi_lens_fusion
depth_subject
texture_authority
contrast_pyramid
resolution_lift
tone_mapping
color_forge
lut_chroma
cinema_look
output_transform
qc_metrics
```

Each shader registry item must define:

```text
shaderId
file
stageIds
minimumReleaseClass
requiredExtensions
inputContract
outputContract
descriptorBindings
pushConstantBytes
workgroup
precisionContract
latencyBudgetNs
fallbackId
qcGateIds
smfRecordKey
validationAssets
binaryHash
```

## 9. Neural And AI Contract

Neural execution is optional by hardware but mandatory in the accounting model.
For the current target SDK, LiteRT/TensorFlow Lite with a validated GPU or other
hardware delegate is the preferred production path. NNAPI remains a supported
legacy adapter for devices where it is already proven, but it is not the
long-term primary architecture because Android deprecated NNAPI in Android 15.
Execution may use a validated delegate, a validated native accelerator, NNAPI,
a shader equivalent, or disablement. A CPU fallback is allowed only for bounded
analysis/control work that cannot become a full-frame real-time Singularity
pixel path.

Every model entry must expose:

```text
modelId
engine
version
file
fileHash
inputTensorContract
outputTensorContract
quantization
expectedAccelerator
actualAccelerator
minimumReleaseClass
latencyBudgetNs
confidenceOutput
fallbackId
qcGateIds
smfRecordKey
```

Required neural authorities:

- Noise estimation and texture/noise separation.
- Motion/flow assistance where validated.
- Subject, face, skin, semantic-zone, and depth assistance.
- Resolution lift with tile overlap, seam checks, temporal stability, and
  hallucination-risk output.
- Autofocus assistance with manual override and hunting prevention.

Missing SNNE, SNAR, SNAF, or similarly named assets must be represented as
unavailable. Existing unrelated TFLite assets cannot satisfy those identities.

## 10. Image-Quality Authorities

### Sensor And Reconstruction

- Black/white level, optical black, FPN, hot-pixel, defect, lens-shading, range,
  Bayer-pattern, and saturation evidence.
- Edge-aware demosaic with green-split, zipper, false-color, moire, and clipped
  mosaic handling.
- Spectral/camera-color normalization with confidence.

### Dynamic Range

- Exposure feasibility before fusion.
- Motion-aware alignment and ghost-safe weights.
- Smooth highlight shoulder and protected skin/neon highlights.
- Deep shadows with texture and without gray wash or chroma pollution.
- Frame-stable tone decisions with scene-cut-aware response.

### Noise

- Separate shot, read, banding, chroma, and temporal noise evidence.
- Texture/noise classification and protected skin, hair, fabric, foliage, and
  text zones.
- Denoise must reduce measured noise without erasing protected texture.
- Chroma crawl, banding, waxiness, and temporal smearing are blocking failures
  for the highest class.

### Motion And Fusion

- Timestamp-aligned gyro/IMU and rolling-shutter evidence.
- Optical-flow confidence, occlusion, scene-cut, subject-motion, and reference
  frame decisions.
- Temporal accumulation must reject unstable motion.
- Multi-lens fusion requires stream, timestamp, intrinsic/extrinsic calibration,
  exposure/WB matching, parallax, occlusion, and seam evidence.

### Detail And Resolution

- Confidence-gated optical recovery and deconvolution.
- Distinct protection for skin, hair, fabric, foliage, text, clipped edges, and
  noisy zones.
- Halo and ringing clamps are mandatory.
- Sharpening cannot create fake resolution or sharpen noise.
- Neural or temporal super-resolution requires seam, hallucination, text, and
  temporal-consistency gates.
- A clean extreme-detail profile must be available with grain, halation, bloom,
  anamorphic streaks, gate weave, and vignette disabled.

### Color, Tone, And Cinema Look

- Stable white balance and neutral-axis protection.
- Skin, sky, neon, foliage, and protected-neutral handling.
- Deliberate gamut mapping and exact-identity LUT behavior at zero strength.
- Tetrahedral LUT interpolation where supported.
- No exposure pumping, white-balance jumping, tone flicker, sky banding, or neon
  channel clipping.
- Grain, halation, bloom, streaks, vignette, and gate effects are opt-in creative
  presentation stages. They cannot hide reconstruction defects or qualify a
  failed base image.
- Preview and recorded output must match within calibrated display limits.

## 11. Output Modes

Source-mode names are mapped to current NeoProCamX products without replacing
their existing contracts.

### Normal SDR And Pro SDR

- Existing Normal SDR and Pro SDR identities remain available.
- Their high-end paths target real 10-bit capture/processing/output when the
  selected device, stream graph, encoder, and container prove it.
- An 8-bit fallback must be labeled as 8-bit compatibility output; it cannot be
  emitted under a false 10-bit identity.
- Clean extreme-detail processing is the default high-quality presentation.

### NDR10X-SINGULARITY

- Maps to the current proprietary NDR/NIVUKX dynamic-range family.
- 10-bit output requires proven P010-to-HEVC Main10 or an equivalent real path.
- Primaries, transfer, matrix, and range must match actual pixels.
- Vendor HDR certification flags remain false or absent.
- Existing NDR architecture and profile identity must not be replaced.

### NLOG-SINGULARITY

- Maps to the current proprietary N-LOG/NLOG family.
- Existing N-LOG curve/version remains authoritative.
- 10-bit preferred; 12-bit only when a real 12-bit capture, process, container,
  and playback/decoding path is proven.
- LUT export and grading metadata are required where the mode promises them.

### NRAW-SINGULARITY

- Maps to supported RAW_SENSOR, RAW10, RAW12, RAW14, DNG, or project-private RAW
  master paths.
- No tone mapping, denoise, creative LUT, or sharpened delivery transform may be
  represented as unprocessed RAW.
- Availability depends on validated Camera2 stream combinations and storage.

### NPV10-SINGULARITY-MASTER

- Preserves the existing project NPV10 private-master architecture.
- Native P010 10-bit and supported RAW12/RAW14 payload contracts remain intact.
- Uncompressed/private-master semantics and integrity auditing remain intact.
- It must not be redefined as an ordinary 8-bit social-delivery MP4.
- Existing NPV10 integrity, post-record audit, conversion, and sidecar evidence
  remain authoritative.

### NPV10-SINGULARITY-REVIEW

- Optional derivative generated from a valid NPV10 master.
- May use HEVC, AV1, or H.264 according to capability and requested compatibility.
- It never replaces, weakens, or relabels the master.

## 12. Codec And Finalization Contract

- Probe actual codec, profile, level, color format, bit depth, surface input,
  resolution, frame rate, bitrate, and sustained behavior.
- HEVC Main10 is preferred for real-time 10-bit delivery.
- AV1 is allowed only when hardware-backed and validated for the target mode.
- H.264 is compatibility-only and cannot carry a 10-bit master claim.
- Media format construction and color metadata policy must remain centralized.
- Android `MediaMuxer` production output uses its supported formats, principally
  MP4 for this project. MKV requires a separately integrated and validated muxer;
  it must not be promised through `MediaMuxer`.
- Encoder input must use the processed surface/hardware path where required.
- Output cadence and timestamps must match the camera contract.
- Playback validation must inspect decodability, duration, frame count/cadence,
  bit depth/profile, color tags, and container finalization.

Stop order:

```text
stop capture submission
close pending frame admission
drain Vulkan/native graph
signal and drain encoder
write muxer trailer
verify container integrity
flush nested metadata
finalize SMF-Singularity envelope
persist recovery diagnostics
release native and surface resources
restore preview
```

Failure must produce a recoverable diagnostic record. A corrupt or unfinalized
file always blocks release.

## 13. SMF-Singularity Envelope

Required top-level fields:

```json
{
  "formatVersion": "SMF-SINGULARITY-1",
  "app": "NeoProCamX",
  "applicationId": "com.neoproc.neoproccamx",
  "pipeline": "VUKX_SOVEREIGN_APEX_100000_0",
  "qualityTarget": "SINGULARITY_LARGE_FORMAT_INTERNAL",
  "sourceDirectiveAlias": "EXACT_IMAX_LEVEL_INTERNAL",
  "ambitionLaw": "10000000000000XXXX",
  "officialImaxCertificationClaimed": false,
  "officialDolbyVisionCertificationClaimed": false,
  "officialHdr10PlusCertificationClaimed": false,
  "officialHdr10CertificationClaimed": false,
  "frameGraphStages": 256,
  "schemaMajor": 1,
  "schemaMinor": 0,
  "releasePolicyVersion": "SINGULARITY_RELEASE_POLICY_1",
  "gatePolicyVersion": "QG_SINGULARITY_1",
  "thresholdSetId": "device-mode-specific",
  "thresholdSetHash": "required-at-release",
  "qualityClass": "CORE-HONEST",
  "outputModeRequested": "NPV10-SINGULARITY-MASTER",
  "outputModeActual": "NPV10-SINGULARITY-MASTER",
  "releaseLabel": "CORE-HONEST",
  "compatibilityProfiles": []
}
```

Required record groups:

- App/build/device identity and capability probe.
- Camera IDs, physical IDs, stream graph, request, and capture results.
- Transport classification and copy/readback proof.
- All 256 stage states and evidence references.
- Shader IDs, hashes, validation state, and timings.
- Model IDs, hashes, accelerator, confidence, fallback, and timings.
- Color, tone, LUT, creative-look, preview, and output-transform decisions.
- Encoder settings, actual codec/profile/bit depth, cadence, and bitrate evidence.
- Thermal, memory, storage, battery, dropped-frame, and downgrade events.
- QG01-QG16 results, thresholds, metrics, failure codes, and review evidence.
- Nested metadata schema/version references for NPV10, NPV12, N-LOG, NIVUKX
  Vision, TMB, or other active project formats.
- File path/URI, size, integrity hash where practical, playability, muxer result,
  sidecar finalization, release class, and release reasons.

The sidecar must be written physically, finalized atomically where possible, and
recoverable after interrupted stop. A metadata builder alone is insufficient.

## 14. Quantified Quality Gates

Every gate returns exactly one state:

```text
PASS
WARN
FAIL
NOT_APPLICABLE
NOT_TESTED
```

Canonical gates:

```text
QG01 Edge Overshoot
QG02 Temporal Shimmer
QG03 Chroma Crawl
QG04 Highlight Rolloff
QG05 Shadow Depth
QG06 Skin Integrity
QG07 Hair And Fine Texture
QG08 Text Stability
QG09 Neural Resolution Honesty
QG10 Tone Stability
QG11 Color Neutrality
QG12 Encode Survival
QG13 Preview Match
QG14 Thermal Endurance
QG15 Dropped Frame Control
QG16 Metadata Honesty
```

Every gate result contains:

```text
gateId
status
mandatory
measuredValue
threshold
unit
evidenceReference
failureCode
responsibleStageId
fallbackDecision
recordingImpact
```

Thresholds live in the future runtime asset:

```text
app/src/main/assets/quality/singularity_thresholds.json
```

Thresholds are mode, resolution, frame-rate, device-class, and calibration
specific. A `NOT_TESTED` mandatory gate blocks `SINGULARITY-100`. A `FAIL`
blocks the class associated with that gate and writes the exact failure code.

## 15. Release Classes

```text
SINGULARITY-100
SINGULARITY-75
SINGULARITY-50
SINGULARITY-25
CORE-HONEST
NO-RELEASE
```

`SINGULARITY-100` requires:

- Proven high-end hardware transport and processed recording path.
- All mandatory QG01-QG16 results are `PASS`.
- No critical stage failure or unreported fallback.
- All active stages and fused-pass mappings are recorded.
- Required shader/model registries validate.
- The output file finalizes, plays, and matches its metadata.
- No critical thermal collapse or repeated dropped-frame pattern.
- Required scene, endurance, and large-screen review evidence passes.
- SMF-Singularity and required nested sidecars finalize.
- External certification flags are false or absent.

`SINGULARITY-75` requires all truth, transport, output, finalization, metadata,
and mandatory safety gates to pass. Only validated expensive-stage reductions
are allowed, and every reduction is recorded.

`SINGULARITY-50` requires stable high-end processed output, proven hardware
transport, playable/finalized output, and no blocking gate failure. Calibrated
warnings and optional-stage disablement are allowed.

`SINGULARITY-25` requires a validated hardware-backed device-safe output with
truthful disclosed reductions, no corrupt output, and no false metadata.

Lower classes are evidence-based operating classes, not permission for hidden
quality loss. Exact thresholds and maximum allowed disabled/fallback stages must
be defined in `singularity_thresholds.json`.

`CORE-HONEST` is the highest label for a stable compatibility path that does not
prove the required Singularity transport or advanced processing.

`NO-RELEASE` applies to crashes, corrupt/unplayable files, false metadata,
missing mandatory sidecars, unrecovered finalization failure, or a critical
quality/safety failure.

## 16. Device, Scene, And Endurance Validation

Required functional tests:

- Permission, open/close, disconnect, background/foreground, and recovery.
- Preview start/stop, rotation where supported, manual exposure/ISO/focus/WB.
- Stream combination, logical/physical camera, and unsupported-mode hiding.
- Vulkan probe, AHardwareBuffer import, synchronization, shader dispatch.
- Neural model load, accelerator selection, failure, and fallback.
- Codec probe, recording, cadence, muxer finalization, and playback.
- NPV10 master write/audit and derivative conversion where selected.
- SMF-Singularity write, atomic finalization, and recovery.
- Thermal, memory, battery, storage-pressure, and storage-full behavior.

Required recording modes where hardware supports them:

```text
1080p30
1080p60
4K24
4K30
4K60
NDR10X-SINGULARITY
NLOG-SINGULARITY
NRAW-SINGULARITY
NPV10-SINGULARITY-MASTER
```

Required scenes:

```text
daylight face
backlit face
night face
night street
neon sign/street
bright window interior
black fabric
white fabric
hair detail
printed text
foliage movement
architecture lines
slow pan
handheld walking
fast crossing subject
mixed-light skin
sky gradient
metal reflection
```

Required charts:

```text
resolution chart
slanted edge
color checker
neutral/gray ramp
step wedge
skin-tone reference
noise target
moving texture target
```

Required endurance:

```text
5-minute preview
10-minute 1080p recording
10-minute 4K recording where supported
20-minute thermal stress
20 repeated start/stop cycles
background/foreground transition
low-battery session
storage-pressure session
near-full and full-storage finalization
```

Review surfaces include phone, tablet/laptop, calibrated 4K display where
available, 200-percent crop, normal-speed motion, and frame-by-frame motion.

## 17. UI Contract

The existing camera UI must expose:

- Active output mode, resolution, frame rate, bit-depth evidence, and record time.
- Active quality class and transport classification.
- Visible downgrade/fallback reason.
- Manual exposure, ISO, focus, white balance, and existing professional controls.
- Focus peaking, zebra/false-color, thermal, storage, and recording warnings.
- Unsupported modes hidden before recording.
- Safe stop/finalization state and sidecar failure state.

The UI cannot display a stronger class than the finalized SMF envelope. During
recording it may display a provisional class explicitly marked as provisional.

## 18. Runtime Assets

Planned manifests:

```text
app/src/main/assets/models/model_manifest.json
app/src/main/assets/luts/lut_manifest.json
app/src/main/assets/quality/singularity_thresholds.json
app/src/main/assets/looks/look_manifest.json
app/src/main/assets/shaders/shader_manifest.json
```

Existing assets remain authoritative. Manifests must enumerate real files and
hashes. Missing source-proposed models, LUTs, or shaders are unavailable entries,
not generated identities.

## 19. Implementation Sequence

### Stage 1: Authority And Evidence Foundation

- Implement the 256-stage registry from the named catalog.
- Implement stage evidence and execution states.
- Implement QG01-QG16 types and threshold schema.
- Implement the release gate and SMF envelope.
- Add shader/model/LUT/look manifest validators.
- Integrate existing recording, Omega, Vulkan, NPV10, metadata, and thermal
  evidence without changing active camera behavior.

### Stage 2: Transport And Capture Evidence

- Prove Camera2 stream/request/capture-result contracts.
- Classify AHardwareBuffer/Vulkan/encoder/private-master transports.
- Record copy/readback, synchronization, timing, and memory-pool evidence.
- Map existing capture and transport code to stages 000-031 and 224-239.

### Stage 3: Existing Pipeline Mapping

- Map real shaders, native passes, AI engines, color/tone policies, NDR, LOG,
  RAW, and NPV10 behavior to the stage ledger.
- Represent unimplemented authorities as explicit disabled/fallback records.
- Add preview-file parity evidence.

### Stage 4: Quality Gate Automation

- Implement deterministic chart and artifact metrics.
- Add encoded-versus-pre-encode comparison where the path permits it.
- Add scene, motion, neural, thermal, cadence, and metadata gates.
- Produce machine-readable artifacts and release reports.

### Stage 5: Missing High-End Engines

- Implement missing reconstruction, motion, multi-lens, texture, resolution,
  color, tone, and QC functionality in risk-ordered increments.
- Require golden images, temporal tests, fallback tests, and device evidence for
  every new native/shader/model feature.

### Stage 6: Production Device Matrix

- Execute all functional, mode, scene, chart, endurance, and large-screen tests.
- Calibrate device-class thresholds.
- Allow release labels only for tested device/mode combinations.
- Add these repository-native validation entry points:

```text
tools/quality/run-singularity-device-lab.ps1
tools/quality/run-singularity-scene-matrix.ps1
tools/quality/run-singularity-endurance.ps1
tools/quality/run-singularity-performance-gate.ps1
tools/quality/run-singularity-release-gate.ps1
tools/quality/singularity-scenes.json
```

- Add focused JVM and instrumented contracts for release policy, required device
  capabilities, scene coverage, endurance, UI status, and the native path.

## 20. Acceptance Criteria

The specification upgrade is complete when:

- The source package has one project-compatible authority.
- All 256 stages have stable names and IDs.
- All source shader groups, neural authorities, output modes, metadata records,
  quality gates, UI requirements, and device-lab requirements are represented.
- Historical stage/metadata generations cannot override the current authority.
- The existing package namespace, Gradle structure, NDR/LOG identity, NPV10
  master architecture, and existing evidence systems are preserved.
- Every source capability maps to an existing owner, a new required component,
  an optional-by-hardware component, or a documented deferred implementation.
- No unsupported feature is represented as active.
- No runtime quality reduction can occur without visible and persisted evidence.
- Implementation can proceed in testable stages without rewriting the app into a
  parallel architecture.

## 21. Complete 256-Stage Catalog

This catalog resolves the source package's unnamed 256-stage ambiguity. It
preserves the earlier 18-stage, 24-stage, and explicit 64-stage functions while
expanding them into 16 authorities of 16 stable logical stages.

These are evidence stages, not necessarily separate GPU dispatches. Fused native
passes must report every logical stage they satisfy.

### Block 00 - Capture Contract

- 000 `frame_identity`
- 001 `session_identity`
- 002 `recording_intent`
- 003 `device_capability_snapshot`
- 004 `release_candidate_initialization`
- 005 `camera_id_binding`
- 006 `physical_camera_id_binding`
- 007 `stream_combination_validation`
- 008 `sensor_request_validation`
- 009 `manual_control_packet`
- 010 `frame_duration_contract`
- 011 `timestamp_alignment`
- 012 `imu_window_binding`
- 013 `rolling_shutter_metadata`
- 014 `thermal_memory_budget_snapshot`
- 015 `capture_evidence_record_initialization`

### Block 01 - Sensor Calibration

- 016 `black_level_ingest`
- 017 `white_level_ingest`
- 018 `bayer_pattern_validation`
- 019 `active_array_and_crop_validation`
- 020 `optical_black_correction`
- 021 `fixed_pattern_noise_profile`
- 022 `hot_pixel_map`
- 023 `sensor_defect_repair`
- 024 `lens_shading_map`
- 025 `sensor_vignetting_correction`
- 026 `row_column_banding_model`
- 027 `sensor_noise_profile`
- 028 `iso_gain_normalization`
- 029 `white_saturation_tagging`
- 030 `calibration_confidence`
- 031 `calibrated_sensor_output`

### Block 02 - RAW Reconstruction

- 032 `raw_packing_validation`
- 033 `raw_unpack_and_linearization`
- 034 `green_split_balance`
- 035 `edge_direction_estimation`
- 036 `luma_demosaic_reconstruction`
- 037 `chroma_demosaic_reconstruction`
- 038 `false_color_suppression`
- 039 `zipper_artifact_suppression`
- 040 `moire_risk_control`
- 041 `clipped_mosaic_reconstruction`
- 042 `post_demosaic_defect_repair`
- 043 `spectral_normalization`
- 044 `camera_rgb_transform`
- 045 `linear_range_sanitization`
- 046 `reconstruction_detail_confidence`
- 047 `linear_rgb_reconstruction_output`

### Block 03 - Optical Identity

- 048 `lens_profile_selection`
- 049 `focal_aperture_context`
- 050 `distortion_map_generation`
- 051 `geometric_distortion_correction`
- 052 `chromatic_aberration_map`
- 053 `lateral_chromatic_aberration_correction`
- 054 `longitudinal_chromatic_aberration_confidence`
- 055 `diffraction_risk_estimation`
- 056 `psf_mtf_estimation`
- 057 `deconvolution_confidence`
- 058 `optical_recovery`
- 059 `flare_veiling_glare_estimation`
- 060 `vignette_residual_correction`
- 061 `center_edge_resolution_balance`
- 062 `optical_artifact_qc`
- 063 `optically_corrected_output`

### Block 04 - Exposure Authority

- 064 `flicker_frequency_detection`
- 065 `exposure_schedule_feasibility`
- 066 `base_exposure_schedule`
- 067 `shadow_exposure_schedule`
- 068 `highlight_exposure_schedule`
- 069 `multi_exposure_synchronization`
- 070 `exposure_normalization`
- 071 `motion_exposure_delta`
- 072 `clipped_highlight_mask`
- 073 `deep_shadow_mask`
- 074 `skin_highlight_protection`
- 075 `neon_highlight_protection`
- 076 `exposure_confidence`
- 077 `exposure_downgrade_ladder`
- 078 `exposure_pumping_guard`
- 079 `exposure_authority_output`

### Block 05 - Dynamic Range Authority

- 080 `base_exposure_reconstruction`
- 081 `shadow_exposure_reconstruction`
- 082 `highlight_exposure_reconstruction`
- 083 `exposure_alignment_confidence`
- 084 `exposure_fusion_weights`
- 085 `ghost_safe_exposure_fusion`
- 086 `highlight_detail_recovery`
- 087 `highlight_shoulder_construction`
- 088 `shadow_chroma_floor`
- 089 `shadow_texture_preservation`
- 090 `local_dynamic_range_zone_map`
- 091 `global_dynamic_range_estimate`
- 092 `tone_headroom_estimate`
- 093 `fusion_artifact_qc`
- 094 `dynamic_range_fallback_selection`
- 095 `scene_linear_dynamic_range_output`

### Block 06 - Noise Authority

- 096 `shot_noise_model`
- 097 `read_noise_model`
- 098 `fixed_pattern_noise_residual`
- 099 `banding_risk_map`
- 100 `chroma_noise_model`
- 101 `chroma_crawl_risk_map`
- 102 `temporal_noise_model`
- 103 `texture_noise_classifier`
- 104 `protected_texture_masks`
- 105 `denoise_strength_map`
- 106 `spatial_noise_reduction`
- 107 `temporal_noise_reduction`
- 108 `banding_suppression`
- 109 `chroma_stabilization`
- 110 `detail_preservation_qc`
- 111 `noise_authority_output`

### Block 07 - Motion Authority

- 112 `gyro_sample_ingest`
- 113 `accelerometer_sample_ingest`
- 114 `sensor_imu_timestamp_alignment`
- 115 `rolling_shutter_motion_model`
- 116 `optical_flow_pyramid`
- 117 `dense_sparse_flow_solution`
- 118 `imu_flow_fusion`
- 119 `motion_occlusion_map`
- 120 `subject_motion_map`
- 121 `reference_frame_anchor`
- 122 `scene_cut_detection`
- 123 `temporal_fusion_weights`
- 124 `ghost_rejection_mask`
- 125 `temporal_accumulation`
- 126 `temporal_shimmer_qc`
- 127 `motion_authority_output`

### Block 08 - Multi-Lens Authority

- 128 `logical_camera_capability`
- 129 `physical_stream_feasibility`
- 130 `physical_lens_identity`
- 131 `lens_intrinsic_calibration`
- 132 `lens_extrinsic_calibration`
- 133 `multi_lens_timestamp_tolerance`
- 134 `multi_lens_exposure_match`
- 135 `multi_lens_white_balance_match`
- 136 `multi_lens_geometry_alignment`
- 137 `parallax_map`
- 138 `multi_lens_occlusion_mask`
- 139 `per_lens_quality_score`
- 140 `multi_lens_fusion_weights`
- 141 `multi_lens_fusion`
- 142 `multi_lens_seam_qc`
- 143 `primary_or_fused_lens_output`

### Block 09 - Subject And Depth Authority

- 144 `face_detection`
- 145 `face_landmark_tracking`
- 146 `skin_confidence_mask`
- 147 `subject_segmentation`
- 148 `depth_inference`
- 149 `depth_confidence`
- 150 `foreground_priority_map`
- 151 `background_protection_map`
- 152 `text_region_mask`
- 153 `sky_region_mask`
- 154 `foliage_region_mask`
- 155 `neon_region_mask`
- 156 `hair_fabric_region_mask`
- 157 `protected_neutral_mask`
- 158 `semantic_mask_temporal_stabilization`
- 159 `subject_depth_authority_output`

### Block 10 - Texture Authority

- 160 `microtexture_confidence_map`
- 161 `edge_confidence_map`
- 162 `skin_texture_guard`
- 163 `hair_texture_guard`
- 164 `fabric_texture_guard`
- 165 `foliage_texture_guard`
- 166 `text_legibility_guard`
- 167 `clipped_edge_guard`
- 168 `noise_sharpening_rejection`
- 169 `local_contrast_pyramid`
- 170 `midtone_detail_recovery`
- 171 `confidence_gated_deconvolution`
- 172 `edge_halo_clamp`
- 173 `ringing_suppression`
- 174 `texture_temporal_consistency_qc`
- 175 `texture_authority_output`

### Block 11 - Resolution Authority

- 176 `native_resolution_confidence`
- 177 `resolution_lift_requirement`
- 178 `temporal_resolution_feasibility`
- 179 `temporal_resolution_inputs`
- 180 `temporal_resolution_lift`
- 181 `neural_resolution_capability`
- 182 `neural_tile_plan`
- 183 `neural_tile_overlap_and_blend`
- 184 `neural_resolution_lift`
- 185 `hallucination_risk_map`
- 186 `text_hallucination_rejection`
- 187 `tile_seam_validation`
- 188 `resolution_temporal_consistency`
- 189 `validated_spatial_upscale_fallback`
- 190 `output_acuity_qc`
- 191 `resolution_authority_output`

### Block 12 - Color Authority

- 192 `camera_color_matrix`
- 193 `white_balance_gain_stabilization`
- 194 `neutral_axis_protection`
- 195 `scene_illuminant_estimation`
- 196 `chromatic_adaptation`
- 197 `working_gamut_conversion`
- 198 `skin_color_protection`
- 199 `sky_color_protection`
- 200 `neon_color_protection`
- 201 `foliage_color_protection`
- 202 `saturation_chroma_authority`
- 203 `gamut_mapping`
- 204 `lut_selection`
- 205 `tetrahedral_lut_interpolation`
- 206 `per_zone_lut_blending`
- 207 `color_accuracy_qc_output`

### Block 13 - Tone And Cinema Look Authority

- 208 `scene_luminance_analysis`
- 209 `display_output_target_selection`
- 210 `tone_curve_selection`
- 211 `local_tone_zone_map`
- 212 `highlight_rolloff`
- 213 `shadow_depth_mapping`
- 214 `midtone_contrast_mapping`
- 215 `tone_temporal_stabilization`
- 216 `preview_file_tone_match`
- 217 `creative_grain_policy`
- 218 `creative_grain_synthesis`
- 219 `highlight_halation`
- 220 `highlight_bloom`
- 221 `optional_anamorphic_streak`
- 222 `optional_vignette_gate_character`
- 223 `tone_cinema_authority_output`

### Block 14 - Output And Encode Authority

- 224 `output_mode_capability_validation`
- 225 `output_transform_selection`
- 226 `output_bit_depth_validation`
- 227 `output_primaries_validation`
- 228 `output_transfer_validation`
- 229 `output_range_matrix_validation`
- 230 `final_gpu_output_transform`
- 231 `encoder_capability_probe`
- 232 `codec_profile_level_selection`
- 233 `bitrate_gop_quality_plan`
- 234 `encoder_surface_preparation`
- 235 `timestamp_cadence_contract`
- 236 `encoder_frame_submission`
- 237 `muxer_sample_write`
- 238 `container_finalization_and_integrity`
- 239 `playable_output_verification`

### Block 15 - QC And Metadata Authority

- 240 `stage_ledger_finalization`
- 241 `smf_singularity_envelope_initialization`
- 242 `camera_device_evidence_record`
- 243 `vulkan_transport_evidence_record`
- 244 `neural_model_evidence_record`
- 245 `encoder_output_evidence_record`
- 246 `thermal_memory_drop_evidence_record`
- 247 `qg01_qg04_edge_motion_chroma_highlight`
- 248 `qg05_qg08_shadow_skin_texture_text`
- 249 `qg09_neural_resolution_honesty`
- 250 `qg10_qg11_tone_color_stability`
- 251 `qg12_qg13_encode_preview_parity`
- 252 `qg14_qg16_endurance_drop_metadata`
- 253 `singularity_release_class_evaluation`
- 254 `sidecar_finalization_and_recovery`
- 255 `final_release_seal`

### Catalog Invariants

- IDs are contiguous from 000 through 255.
- Every block has exactly 16 stages.
- Stage names are stable API identifiers.
- Renaming or reordering requires a metadata-version migration.
- A disabled stage remains present in the ledger.
- An implementation may fuse stages only when it records the mapping.
- Creative stages 217 through 222 default to identity/disabled in clean
  extreme-detail profiles.

## 22. Source-To-Project Traceability

### 22.1 Repository Baseline

```text
Gradle modules        : :app, :core-math
Application namespace: com.neoproc.neoproccamx
Minimum Android       : 29
Target Android        : 36
Native language       : C++20
Primary ABI           : arm64-v8a
Camera                : Camera2
GPU/native            : Vulkan, AHardwareBuffer, EGL/native bridges
Recording             : MediaCodec, MediaMuxer, MOV, NPV10 private master
AI                    : TFLite/MediaPipe/NNAPI-facing bridges and assets
```

Source package names describe responsibilities. They are not instructions to
duplicate existing classes or create `com.nivukx.neoprocamx`.

### 22.2 Kotlin Ownership Mapping

| Source responsibility | Existing project owner | Required integration |
|---|---|---|
| `SovereignCameraController` | `camera/CameraController.kt`, `CameraDeviceOrchestrator.kt` | Extend evidence; do not replace |
| `CaptureSessionGraph` | `CinemaStreamGraphPolicy.kt`, session surface/target policies | Add Singularity stream proof |
| `ManualSensorController` | `Camera2PipelineRequestPolicy.kt`, UI manual dispatcher, focus/exposure policies | Record request and capture-result truth |
| `LogicalCameraRouter` | lens selection and capability policies | Add physical-stream/calibration evidence |
| `SingularityPipelineController` | `ui/VideoPipelineGraphController.kt`, `pipeline/PipelineOrchestrator.kt`, `FramePipelineManager.kt` | Coordinate stage evidence |
| `DeviceTierSelector` | `quality/OmegaCapabilityGate.kt`, `core-math HighEndRuntimeTier`, capability policies | Feed release gate, no competing label |
| `ThermalBudgetController` | `ThermalRuntimePolicyResolver.kt`, `PredictiveThermalKalmanFilter.kt`, core thermal state | Persist events and class impact |
| `MemoryBudgetController` | `MemoryProfiler.kt`, pools/backpressure policies | Add preflight and runtime evidence |
| `OutputModeController` | profile intent, format delivery, encoder plan policies | Add Singularity aliases without replacing modes |
| `QualityClassController` | new `quality/SingularityReleaseGate.kt` plus existing UI status policies | Sole Singularity label authority |
| `MetadataPolicy` | `NeoProfileTruthMetadata.kt`, NIVUKX emitters, evidence certificates | Feed new envelope |
| `SmfSingularityModels` | new `recording/SmfSingularityMetadata.kt` | Envelope existing schema versions |

All new Kotlin components belong under:

```text
app/src/main/java/com/neoproc/neoproccamx/
```

Pure image math with no Android dependencies may live in `:core-math`.
Evidence types currently marked `internal` remain in `:app` unless deliberately
exposed through a stable public contract.

### 22.3 Native Ownership Mapping

| Source native concept | Existing project surface | Required action |
|---|---|---|
| Device probe | `vulkan_bridge.cpp`, Kotlin Vulkan runtime/capability policies | Consolidate evidence |
| Hardware-buffer transport | `native_buffer_bridge.cpp`, `native_buffer_manager.*`, offscreen encoder bridge | Prove ownership/copy chain |
| Frame graph | Vulkan bridges, image pipeline policies, shaders | Add 256-stage logical mapping |
| Queue/sync manager | current Vulkan native implementation | Expose queue and sync evidence |
| Shader registry | `app/src/main/cpp/shaders/**`, generated SPIR-V | Add manifest and hashes |
| Noise engine | denoise shaders and current noise policies | Map real behavior; missing SNNE remains unavailable |
| Resolution engine | scaling/detail policies and shaders | Add honesty/seam gates before neural identity |
| Autofocus engine | AI focus coordinator and camera focus policies | Preserve manual override |
| Tone/color/look | color science, uniforms, LUTs, tone/look shaders | Map existing versions and parity |
| MediaCodec writer | recording manager/codec policies/native encoder bridge | Preserve existing recording ownership |
| SMF writer | existing metadata/certificate/sidecar writers | Add atomic Singularity envelope |
| Theatrical QC | quality tools, unit/device tests, evidence gates | Add QG01-QG16 automation |

Do not add empty JNI methods matching the source names. Add a native entry point
only when it owns real work or exposes real evidence not available through an
existing bridge.

### 22.4 Output Compatibility

| Source output | Project-compatible interpretation |
|---|---|
| NDR10X-SINGULARITY | Alias/evidence class over current proprietary NDR/NIVUKX modes |
| NLOG-SINGULARITY | Alias/evidence class over current proprietary N-LOG family |
| NRAW-SINGULARITY | Supported RAW_SENSOR/RAW10/RAW12/RAW14/DNG/private RAW paths |
| NPV10-SINGULARITY-MASTER | Existing NPV10 private 10-bit/RAW master, unchanged |
| NPV10-SINGULARITY-REVIEW | Optional converted derivative, never the master |
| Normal SDR / Pro SDR | Existing identities preserved; real 10-bit path when proven |

Source text describing NPV10 as an ordinary 8-bit social MP4 is superseded
because adopting it would downgrade the existing project master architecture.

### 22.5 Metadata Compatibility

`SMF-SINGULARITY-1` is a top-level evidence envelope. It records nested schema
identities instead of flattening or replacing them:

```text
nivukx-tmb-draft-12
nivukx-vision-a1-a15-v1
NPV10 container version
NPV12 profile version
N-LOG curve version
NeoProfileTruthMetadata version
RecordingQualityEvidenceCertificate version
OmegaQualityTruthReport version
```

Historical SMF-4/5/10/100 fields are migrated into equivalent envelope records.
They are not emitted as competing current release authorities.

### 22.6 Source Document Coverage

| Source documents | Preserved authority |
|---|---|
| 01-03 | Architecture, zero-copy, Vulkan, threading, synchronization |
| 04 | Neural routing, SNNE/SNAR/SNAF/semantic zones, fallback |
| 05-06 | Color, tone, look, NDR/LOG/RAW/NPV, sidecar, finalization |
| 07-08 | Device tiers, downgrade visibility, build order, validation |
| 09-12 | Large-screen quality, anti-artifact rules, theatrical review |
| 13-17 | Explicit 64-stage functions, 16 authorities, SMF-10 evidence |
| 18-23 | Sensor-to-theater path, QG01-QG16, Android hard contracts |
| 24-31 | 256-stage ledger, project map, registries, outputs, release matrix |

No document group is discarded. Later documents determine naming/version
precedence; earlier documents retain any quality requirement not contradicted by
repository truth or a later explicit rule.

### 22.7 Capability Status Vocabulary

Every source capability must have one status:

```text
IMPLEMENTED_AND_EVIDENCED
IMPLEMENTED_NEEDS_EVIDENCE
PARTIALLY_IMPLEMENTED
REQUIRED_NEW_IMPLEMENTATION
OPTIONAL_BY_HARDWARE
DISABLED_BY_DEVICE
DEFERRED_WITH_OWNER
SUPERSEDED_BY_STRONGER_PROJECT_CONTRACT
```

`DEFERRED_WITH_OWNER` requires a target subsystem and acceptance gate.
`SUPERSEDED_BY_STRONGER_PROJECT_CONTRACT` is used for conflicts such as the
source NPV10 review definition versus the current private-master implementation.

### 22.8 Initial Capability Classification

| Domain | Initial status | Notes |
|---|---|---|
| Camera2/manual controls | Implemented needs evidence | Existing broad policy/controller coverage |
| Stream combination truth | Partially implemented | Add canonical session evidence |
| AHardwareBuffer/Vulkan transport | Partially implemented | Native code exists; full copy-chain proof required |
| 256-stage release ledger | Required new implementation | Catalog now defined |
| Shader manifest | Required new implementation | Real shaders exist |
| SNNE/SNAR/SNAF named models | Required new implementation or disabled | Do not alias unrelated assets |
| Subject/depth/segmentation | Partially implemented | Existing MediaPipe/TFLite assets and engines |
| NDR/LOG color systems | Implemented needs evidence | Preserve proprietary contracts |
| NPV10 master | Implemented needs evidence | Preserve stronger current behavior |
| SMF envelope | Required new implementation | Must wrap existing evidence |
| QG01-QG16 automation | Partially implemented | Existing tests/gates feed new authority |
| Device-lab matrix | Partially implemented | Extend scripts and artifacts |

### 22.9 Android Platform Adaptation

- NNAPI is retained as a legacy compatibility adapter. New production neural
  execution should prefer the project's TensorFlow Lite/LiteRT path with a
  validated hardware delegate because NNAPI is deprecated from Android 15.
- Android `MediaMuxer` does not provide MKV output. The default production
  container remains MP4/MOV according to the existing recording backend. MKV is
  allowed only after integrating and validating a separate muxer.
- Correct BT.2020, HLG, PQ, matrix, and range signaling must remain when it
  truthfully describes encoded pixels. Certification flags and vendor dynamic
  metadata remain false; removing correct color signaling would reduce quality.
- Preview may use reduced compute or resolution to maintain frame stability, but
  framing, tone intent, color intent, and active reductions must be reported and
  validated against the recorded file.

Platform references:

- Android NNAPI migration:
  `https://developer.android.com/ndk/guides/neuralnetworks/migration-guide`
- Android MediaMuxer output formats:
  `https://developer.android.com/reference/android/media/MediaMuxer.OutputFormat`

### 22.10 Change-Control Rule

Implementation must not rewrite unrelated dirty files merely to match source
names. Each work package must:

- Name its exact owner files.
- Preserve existing public behavior unless the approved requirement changes it.
- Add tests proportional to the runtime risk.
- Prove no NDR, LOG, RAW, NPV10, preview, recording, or metadata regression.
- Record any unavoidable hardware fallback visibly and in the sidecar.
