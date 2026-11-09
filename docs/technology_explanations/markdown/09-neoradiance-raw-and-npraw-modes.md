# NeoRadiance RAW and NPRAW Modes

Project-owned RAW mode family with explicit bit-depth and compression metadata.

## Implementation Anchors

- `app/src/main/java/com/neoproc/neoproccamx/recording/CinemaVideoConfig.kt`
- `app/src/main/java/com/neoproc/neoproccamx/pipeline/RawVideoManager.kt`
- `app/src/main/java/com/neoproc/neoproccamx/recording/RawVideoContractPolicy.kt`
- `app/src/main/java/com/neoproc/neoproccamx/pipeline/RawVideoBitDepthPolicy.kt`

## Purpose

NeoRadiance RAW/NPRAW modes describe how RAW sensor data is stored or transformed inside the app.

Modes include NEO_SPECTRAL_RAW, NEO_PURE_RAW, NEO_FINE_RAW, and NEO_DENSE_RAW, plus compatibility lossless modes.

## Runtime Flow

1. The selected RAW compression mode is normalized by RawCompressionMode.fromName.

2. RawVideoContractPolicy resolves video capture to the project RAW contract.

3. RawVideoManager writes DNG or project stream payloads depending on mode.

4. Bit-depth policy reports 12/14/16-bit intent separately from actual camera capability.

## Core Math

Raw signal normalization:

```text
rawNorm = clamp((rawCode - blackLevel) / (whiteLevel - blackLevel), 0, 1)
```
Exposure-normalized radiance:

```text
radiance = rawNorm / max(exposureSeconds * analogGain, epsilon)
```
RAW bit-depth mapping by mode:

OFF, LOSSLESS, LOSSLESS_ENHANCED, VISUALLY_LOSSLESS: 16-bit

HIGH_QUALITY, NEO_SPECTRAL_RAW, NEO_PURE_RAW, NEO_FINE_RAW: 14-bit

HIGH_COMPRESSION: 12-bit uncompressed RAW video contract

NEO_DENSE_RAW: 16-bit deep predictive pipeline

Approximate compression/storage UI hint:

```text
storageBytes ~= rawBytes / compressionRatio(mode)
```
## Truth Boundary

```text
RAW mode can only be true sensor RAW when Camera2 exposes RAW_SENSOR/RAW_PRIVATE and the device supports the requested bit depth.
```
```text
If 12-bit RAW is not exposed by the HAL, the project should report unavailable instead of faking RAW.
```
