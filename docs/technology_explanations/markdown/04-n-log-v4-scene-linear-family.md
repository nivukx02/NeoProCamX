# N-Log v4 Scene Linear Family

Original NeoProCamX log curve family for 10-bit wide-gamut log delivery.

## Implementation Anchors

- `core-math/src/main/kotlin/com/neoproc/neoproccamx/core/NeoLogCurve.kt`
- `app/src/main/java/com/neoproc/neoproccamx/ai/NLogMath.kt`
- `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingVideoEncoderPlanResolver.kt`
- `app/src/main/cpp/shaders/vulkan/ycbcr_preview.frag`

## Purpose

N-Log is the project-owned scene-referred log encoding family.

It is not an ARRI/Sony/Canon/RED clone. The code provides Base, Shadow, Highlight, and Cinema variants using the same reversible contract.

## Runtime Flow

1. UI selects a LOG mode or an N-Log color science profile.

2. NLogMath maps the label to BASE, SHADOW, HIGHLIGHT, or CINEMA.

3. NeoLogCurve.encode stores scene-linear signal into a 10-bit-friendly log code space.

4. Encoder planning requests Rec.2020/P010/Main10 where available for LOG recording.

## Core Math

Each variant is defined by toeCut, toeSlope, and logDensity.

The toe section is a C1 Hermite curve so the value and first derivative match the log body at toeCut.

Toe profile constants:

```text
toeHeight = toeCut * toeSlope
```
```text
grayLog = ln(1 + logDensity * (0.18 - toeCut))
```
```text
logScale = (0.50 - toeHeight) / grayLog
```
```text
For x >= toeCut:
```
```text
y = toeHeight + logScale * ln(1 + logDensity * (x - toeCut))
```
For x < toeCut:

```text
t = clamp(x / toeCut, 0, 1)
```
```text
h10 = t^3 - 2t^2 + t
```
```text
h01 = -2t^3 + 3t^2
```
```text
h11 = t^3 - t^2
```
```text
y = h10 * toeCut * toeEntrySlope + h01 * toeHeight + h11 * toeCut * logSlopeAtToe
```
10-bit code:

```text
code10 = round(clamp(y, 0, 1) * 1023)
```
## Truth Boundary

Dynamic range is marked DEVICE_MEASUREMENT_REQUIRED in code; the project does not claim a measured stop count without device evidence.

The retained ACEScc-style API aliases are documented as Neo-owned N-Log aliases, not certified ACEScc encoders.
