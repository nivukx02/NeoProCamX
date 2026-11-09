# NeoRadiance Math

Original HDR and cinema color computation library for tone, shadow, shoulder, and chroma preservation.

## Implementation Anchors

- `app/src/main/java/com/neoproc/neoproccamx/ai/NeoRadianceMath.kt`
- `app/src/main/java/com/neoproc/neoproccamx/ai/AIProcessor.kt`
- `app/src/main/java/com/neoproc/neoproccamx/ui/VideoFormatDeliveryPolicy.kt`

## Purpose

NeoRadiance is the project-owned math library for tone response, HDR consistency, and color-preserving highlight control.

It provides first-principles functions used by AI processing and video delivery decisions.

## Runtime Flow

1. Pixels are linearized from sRGB or sensor-derived RGB.

2. NeoRadiance computes luma, tone response, shadow lift, highlight compression, and chroma consistency.

3. AI and HDR processing use these weights to merge frames and preserve natural color.

## Core Math

Linear luminance:

```text
Y = 0.2126 R + 0.7152 G + 0.0722 B
```
Cascade Fold tone curve:

```text
p = ln(1 + 3.5 x)
```
```text
spine = tanh((p - 0.65) * (2.2 + 1.8 k))
```
```text
normalized = (spine + 1) / 2
```
```text
over = max(x - 1, 0)
```
```text
shoulder = over * (1 - exp(-over / (1.2 + 2s)))
```
```text
out = clamp(normalized + shoulder, 0, 1.35)
```
Depth Curve shadow lift:

```text
if x < 0.08: out = (x / 0.08)^(0.65 + 0.25 strength) * 0.08 + 0.02 x strength
```
```text
else: out = x
```
Quantum channel compression:

```text
maxC = max(R, G, B)
```
```text
scale = 1 / (1 + (1 - exp(-(maxC - 1) * (1.5 + 2 strength))) * strength)
```
```text
RGB' = RGB * scale
```
## Truth Boundary

NeoRadiance is original app math, not a substitute for a calibrated camera IDT.

Its physical accuracy depends on camera metadata, white balance, and actual sensor response.
