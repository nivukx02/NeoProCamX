# Computational Cinema Maximum Quality Design

Date: 2026-05-13

## Goal

Upgrade NeoProCamX into a premium computational cinema camera app that always attempts the strongest real software pipeline the device can sustain while preserving existing features and reporting hardware limits truthfully.

The target look is better than normal phone-camera output and closer to premium iPhone Pro / IMAX-style cinema: clean low light, wide dynamic range, controlled highlights, deep but not crushed blacks, natural skin tone, crisp detail without halos, stable motion, high-bitrate HEVC/HDR delivery, and filmic color.

## Hard Truths

The app can improve denoise, tone mapping, detail recovery, digital zoom, color, stabilization, bitrate, preview rendering, and post-processing. It cannot create real sensor dynamic range, real optical resolution, real 10-bit/HDR, true RAW, full-sensor video, optical stabilization, or heat capacity when the phone hardware does not expose them.

Unsupported features must fall back with direct diagnostics. No fake 8K, fake RAW, fake HDR, fake 10-bit, or pixel-duplication upscaling is allowed.

## Architecture

Add a single computational-cinema planning contract in the `pipeline` package:

`ComputationalCinemaRequest + ComputationalCinemaHardware + ComputationalCinemaRuntime -> ComputationalCinemaPlan`

The plan becomes the common decision point for:

- live GPU/Vulkan stages
- AI inference density
- multi-frame denoise and HDR fusion
- AI super-resolution eligibility
- skin-tone protection and cinematic LUT strength
- gyro/EIS/OIS stabilization usage
- HEVC/HDR/10-bit/high-bitrate recording intent
- post-processing fallback when live processing is too heavy
- factual user warnings

Existing packages keep their ownership:

- `camera`: Camera2 capability truth, RAW/YUV/encoder stream selection, OIS/EIS support, HDR dynamic-range support, ISP-bypass key coverage.
- `pipeline`: frame analysis, multi-frame processing plan, AI/GPU workload, backpressure, thermal downgrade.
- `rendering`: Vulkan preview path, LUT/tone presentation, HDR display delivery.
- `recording`: HEVC/AV1/AVC codec plan, Main10, bitrate, HDR metadata, container/muxer.
- `color`: natural skin tone, filmic contrast, LOG/HDR/profile/LUT policy.
- `ui`: mode and quality selection only; no invented capability claims.

## Modes

- Pro Video: natural high-detail video with manual control, high bitrate, and truthful HDR/10-bit.
- Cinematic IMAX-style: maximum filmic contrast, highlight protection, deep blacks, wide-gamut color, high clarity, and conservative sharpening.
- AI Super-Resolution Zoom: real AI/GPU-assisted detail recovery only when zoom and runtime headroom justify it; otherwise post-processing.
- Night Video Enhancement: high temporal/chroma denoise, lower sharpening, skin-tone protection, and exposure stability.
- Smart HDR Photo/Video: multi-frame or dynamic-range HDR when supported, fallback tone mapping when not.
- Face-Priority Exposure: face/skin-aware AE bias and highlight protection.
- Natural Skin Tone Engine: protects hue, saturation, and local contrast around detected faces.
- Filmic LUT Presets: controlled LUT strength with LOG/HDR preview parity.
- High-Bitrate Recording: HEVC Main10/HDR where supported, high bitrate floors with encoder pressure fallback.
- GPU/Vulkan Preview Processing: zero-copy GPU path where available, CPU/preview fallback otherwise.

## Quality Levels

- Fast: stable FPS first. Uses light denoise, light tone map, smaller AI input, no live SR during recording.
- Balanced: default premium path. Uses live denoise, HDR tone, skin protection, LUT, and selective SR when stable.
- Maximum: strongest path. Uses dense multi-frame processing, AI SR, cinematic LUT, high bitrate, and post-processing fallback when live budget is exceeded.

Maximum is a target, not a promise to overrule hardware. If thermal, FPS, encoder pressure, or Camera2 capability blocks a live stage, the app must keep recording stable and queue post-processing where possible.

## Processing Order

Camera2 RAW/YUV/PRIVATE input -> exposure/WB/face priority -> multi-frame noise model -> HDR fusion/tone map -> temporal detail recovery -> AI/GPU super-resolution for zoom -> local contrast -> natural sharpening -> skin-tone protection -> filmic LUT/color grading -> gyro/EIS/OIS stabilization -> HEVC/HDR/high-bitrate encode.

## Performance Strategy

The resolver must cap live complexity by frame budget, thermal state, dropped frames, encoder pressure, and hardware capability. It should prefer stable recorded frames over heavy live preview effects. Heavy Maximum stages move to post-processing when live frame budget is unsafe.

## Testing Strategy

Pure policy tests must prove:

- Maximum IMAX-style enables every real stage on capable hardware.
- No hardware HDR/10-bit/RAW/SR support produces fallback diagnostics instead of fake claims.
- Thermal and FPS pressure demote live stages and enable post-processing fallback.
- Night mode prioritizes temporal/chroma denoise over sharpening.
- Zoom mode enables AI SR only when zoom, support, and runtime headroom allow it.
- Fast/Balanced/Maximum produce distinct workloads.

Device validation must still run build, unit tests, install, launch, crash-buffer check, and camera-mode smoke tests on the connected phone.
