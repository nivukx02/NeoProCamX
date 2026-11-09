# Video Image Quality Pipeline Phase 4 Design

Date: 2026-05-20

## Goal

Phase 4 adds a single tested image-quality contract for VIDEO, PRO, HDR, LOG, and RAW video paths. Phases 1-3 made the mode graph, recording graph, and Camera2 session graph consistent. Phase 4 makes the final image-quality decisions consistent: tone mapping, denoise, sharpening, color delivery, LOG separation, and RAW truthfulness.

## Scope

In scope:

- Define the canonical pipeline order per delivery family.
- Keep HDR on processed ISP color with AWB, CCM, denoise, HDR merge, tone mapping, gamma or HDR transfer, then output.
- Keep LOG out of HDR transfer and preserve a flat grading signal.
- Keep RAW truthful: RAW_SENSOR data, black-level/calibration metadata, no fake HDR merge, no synthetic sharpening.
- Strengthen real-time chroma and temporal denoise for moving RGB grain while keeping detail-aware sharpening bounded.
- Wire the contract into `SceneRenderProfileEngine` final profile tuning.
- Add unit tests proving the contract and wiring.

Out of scope:

- New ML model integration.
- New UI controls.
- Replacing the Vulkan renderer.
- Claiming literal zero physical sensor noise. The implementation raises suppression floors under noise pressure while preserving motion and detail guardrails.

## Architecture

The implementation adds a pure policy in the pipeline layer:

- `VideoImageQualityPipelinePolicy`
- `VideoImageQualityPipelineRequest`
- `VideoImageQualityPipelinePlan`
- `VideoImagePipelineStage`
- `VideoImageSignalFamily`

`SceneRenderProfileEngine` resolves this policy after the existing scene, HDR version, LOG, computational cinema, and high-end runtime tuning. The final policy applies mode-specific floors and clamps at one point, so individual earlier branches cannot accidentally turn HDR into LOG, LOG into HDR, or RAW into processed video.

## Pipeline Orders

HDR:

`SENSOR_RAW -> DEMOSAIC -> AUTO_WHITE_BALANCE -> COLOR_CORRECTION_MATRIX -> NOISE_REDUCTION -> HDR_MERGE -> TONE_MAPPING -> GAMMA_CONVERSION -> OUTPUT`

SDR and PRO:

`SENSOR_RAW -> DEMOSAIC -> AUTO_WHITE_BALANCE -> COLOR_CORRECTION_MATRIX -> NOISE_REDUCTION -> TONE_MAPPING -> GAMMA_CONVERSION -> OUTPUT`

LOG:

`SENSOR_RAW -> DEMOSAIC -> AUTO_WHITE_BALANCE -> COLOR_CORRECTION_MATRIX -> NOISE_REDUCTION -> LOG_TRANSFER -> OUTPUT`

RAW:

`SENSOR_RAW -> BLACK_LEVEL_CALIBRATION -> RAW_METADATA -> RAW_CONTAINER_OUTPUT`

## Quality Rules

- HDR uses processed ISP, HDR merge, luma-preserving tone mapping, bounded saturation, highlight compression, and no LOG strength.
- LOG uses processed ISP before LOG transfer, keeps low contrast/saturation, disables HDR fusion, and keeps edge sharpening conservative.
- SDR and PRO use processed ISP, strong denoise under noise pressure, and natural sharpening without halos.
- RAW keeps RAW_SENSOR truth, requires calibration metadata, and avoids processed ISP or HDR merge.
- Severe RGB grain raises chroma denoise close to the ceiling and raises spatial denoise, while motion keeps temporal denoise bounded.
- Clean detailed scenes keep lower denoise and allow higher sharpening within a natural detail ceiling.

## Testing

Unit tests cover:

- HDR pipeline order exactly matches the required order.
- LOG excludes HDR merge and HDR transfer behavior.
- RAW uses RAW stages only and marks processed ISP disabled.
- Severe RGB grain raises denoise floors without allowing oversharpening.
- Clean detail keeps denoise lower than noisy scenes and allows stronger sharpening.
- `SceneRenderProfileEngine` uses the Phase 4 policy for HDR and LOG final clamps.

Device verification remains a separate Phase 5 matrix because it needs recorded clips across every mode/resolution/FPS.
