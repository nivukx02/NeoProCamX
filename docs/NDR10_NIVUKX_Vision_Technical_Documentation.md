# NDR10+ and NIVUKX Vision Technical Documentation

Document date: 2026-05-21  
Project: NeoProCamX  
Status: Original NeoProCamX computational cinema profiles

## 1. Purpose

NDR10+ and NIVUKX Vision are original NeoProCamX dynamic-range technologies.
They are not HDR10+, not Dolby Vision, and not vendor-certified display
formats. They are NeoProCamX computational cinema profiles that run over
standard Android-compatible transport when recording or previewing video.

The safe product definition is:

- NDR10+ = NeoProCamX Dynamic Range 10-bit Plus.
- NIVUKX Vision = NeoProCamX premium computational vision profile.
- Android transport = PQ / BT.2020 / HEVC Main10 where supported.
- Neo metadata = Neo dynamic sidecar and truthful profile metadata.

The product layer must show only:

- NDR10+
- NIVUKX Vision

The internal Android transport may still use:

- ST2084 / PQ transfer
- BT.2020 primaries
- HEVC Main10 encoder profile
- Android Camera2 dynamic range profiles

Those transport terms are compatibility requirements, not product branding.

## 2. Pipeline Overview

The NeoProCamX dynamic-range pipeline is:

```text
Sensor / ISP input
-> exposure and white-balance stabilization
-> luma and chroma noise control
-> scene-linear RGB estimation
-> Neo profile tone mapping
-> color-volume preservation
-> highlight rolloff
-> shadow compression
-> edge-safe detail enhancement
-> preview render path
-> recording encoder path
-> Neo truth metadata and sidecar
```

The preview path and recording path must use the same selected Neo profile.
Starting recording must not switch the color mode, tone curve, denoise behavior,
or sharpening behavior to a different profile.

## 3. Truthful Metadata Contract

For NDR10+:

```text
selectedProfile=NDR10+
profileFamily=NEOPROCAMX_NDR
activeTransport=ANDROID_PQ_BT2020_MAIN10
transfer=ST2084/PQ
primaries=BT.2020
matrix=BT.2020_NCL
encodedBitDepth=10
internalMathBitDepth=12
dynamicMetadataWritten=NEO_SIDECAR
vendorCertifiedHdr10Plus=false
vendorCertifiedDolbyVision=false
```

For NIVUKX Vision:

```text
selectedProfile=NIVUKX Vision
profileFamily=NEOPROCAMX_VISION
activeTransport=ANDROID_PQ_BT2020_MAIN10
transfer=ST2084/PQ
primaries=BT.2020
matrix=BT.2020_NCL
encodedBitDepth=10
internalMathBitDepth=12
dynamicMetadataWritten=NEO_SIDECAR
vendorCertifiedHdr10Plus=false
vendorCertifiedDolbyVision=false
```

Important rule:

```text
Do not claim official vendor dynamic metadata unless certified metadata is
actually written by a licensed/certified path.
```

## 4. Scene-Linear Input Model

Let the input be scene-linear RGB after camera ISP or Neo RAW preprocessing:

```text
R, G, B in [0, +inf)
```

BT.2020 luma is:

```text
Y = 0.2627 * R + 0.6780 * G + 0.0593 * B
```

To avoid divide-by-zero:

```text
eps = 1e-8
Y_safe = max(Y, eps)
```

Scene normalized luma:

```text
x = clamp(Y / sceneWhite, 0, 1)
```

Where:

```text
sceneWhite = estimated diffuse white or profile reference white
```

## 5. Shared Neo Helpers

Clamp:

```text
clamp(v, lo, hi) = min(max(v, lo), hi)
```

Smoothstep:

```text
t = clamp((x - edge0) / (edge1 - edge0), 0, 1)
smoothstep(edge0, edge1, x) = t * t * (3 - 2 * t)
```

Exponential shoulder:

```text
shoulder(u, a) = 1 - exp(-a * clamp(u, 0, 1))
```

Peak-normalized luma-preserving scale:

```text
scale = Y_mapped / max(Y, eps)
R1 = R * scale
G1 = G * scale
B1 = B * scale
```

## 6. NDR10+ Signal Formula

NDR10+ is the balanced Neo dynamic-range profile. It is designed for clean
10-bit recording, strong highlight protection, stable skin tones, and low
noise amplification.

The NDR10+ normalized signal shaping function is:

```text
S_ndr(x) = clamp(
    x^0.92 + smoothstep(0.0, 0.20, x) * 0.018 * (1 - x),
    0,
    1
)
```

Meaning:

- `x^0.92` gives a gentle midtone lift without strong shadow noise lift.
- `smoothstep(0.0, 0.20, x)` prevents abrupt toe behavior.
- `0.018 * (1 - x)` adds a controlled low-end toe that fades near highlights.

NDR10+ profile parameters:

```text
sourcePeakNits = 8400 + 2600 * dynamicRangeScore
targetPeakNits = 1420 + 220 * dynamicRangeScore
kneeRatio = 0.59
shoulderStrength = 3.72 + 0.48 * dynamicRangeScore
shadowLift = 0.0022 + 0.0028 * shadowNeed
saturationPreservation = 0.79
colorVolumeCompression = 0.54
encodedBitDepth = 10
internalMathBitDepth = 12
```

Recommended use:

- Normal high-quality NDR recording.
- Bright outdoor scenes.
- Mixed indoor/outdoor light.
- Skin-tone scenes needing natural contrast.
- Devices that can sustain 10-bit HEVC Main10.

## 7. NIVUKX Vision Signal Formula

NIVUKX Vision is the highest-end NeoProCamX profile. It is more aggressive
than NDR10+ in highlight shaping, color-volume protection, and shadow control.
It is intended for premium cinematic output.

The NIVUKX Vision normalized signal shaping function is:

```text
S_niv(x) = clamp(
    x^0.88 + smoothstep(0.0, 0.24, x) * 0.026 * (1 - x),
    0,
    1
)
```

Meaning:

- `x^0.88` gives more midtone energy than NDR10+.
- The toe starts across a wider 0.24 range for smoother low-end transitions.
- `0.026 * (1 - x)` gives more low-end shaping while avoiding highlight lift.

NIVUKX Vision profile parameters:

```text
sourcePeakNits = 10000 + 3200 * dynamicRangeScore
targetPeakNits = 1680 + 260 * dynamicRangeScore
kneeRatio = 0.55
shoulderStrength = 4.12 + 0.54 * dynamicRangeScore
shadowLift = 0.0018 + 0.0022 * shadowNeed
saturationPreservation = 0.74
colorVolumeCompression = 0.64
encodedBitDepth = 10
internalMathBitDepth = 12
```

Recommended use:

- Highest quality cinematic recording.
- High contrast scenes.
- Night scenes with bright highlights.
- Scenes where highlight rolloff is more important than maximum contrast.
- Premium mode where stronger processing cost is acceptable.

## 8. Highlight Rolloff

Neo highlight rolloff uses a soft exponential shoulder:

```text
kneeNits = sourcePeakNits * kneeRatio
u = (inputNits - kneeNits) / max(sourcePeakNits - kneeNits, eps)

if inputNits <= kneeNits:
    mappedNits = inputNits
else:
    mappedNits = kneeNits + (targetPeakNits - kneeNits) *
        (1 - exp(-shoulderStrength * clamp(u, 0, 1)))
```

This avoids hard clipping and prevents harsh digital highlight edges.

NDR10+ uses a softer shoulder for balance. NIVUKX Vision uses stronger shoulder
compression for smoother high-end highlight recovery.

## 9. Shadow Compression and Noise Safety

The profile must not brighten sensor noise as if it were real detail.

Shadow need can be estimated as:

```text
shadowNeed = clamp(shadowRatio + noiseRisk - exposureStability, 0, 1)
```

Shadow shaping:

```text
Y_shadow = Y_mapped + shadowLift * smoothstep(0.0, 0.18, 1 - x)
```

Noise safety gate:

```text
if chromaNoiseRisk > threshold:
    shadowLift = shadowLift * (1 - chromaNoiseRisk)
```

Important behavior:

- NDR10+ lifts shadows gently.
- NIVUKX Vision compresses shadows more cleanly and avoids digital grain lift.
- Neither profile should promise "zero physical sensor noise"; the correct
  target is stable, non-dancing, low-chroma-noise output.

## 10. Chroma Noise Suppression

Chroma noise is reduced more strongly than luma detail:

```text
Cb = B - Y
Cr = R - Y
noiseGate = clamp(chromaNoiseRisk, 0, 1)
motionGate = clamp(motionScore, 0, 1)

chromaSuppress = baseChromaDenoise *
    (0.65 + 0.35 * noiseGate) *
    (1.0 - 0.25 * motionGate)

Cb1 = Cb * (1 - chromaSuppress)
Cr1 = Cr * (1 - chromaSuppress)
```

Reconstruction:

```text
R1 = Y + Cr1
B1 = Y + Cb1
G1 = solve_from_luma(Y, R1, B1)
```

This reduces RGB dancing noise and magenta/green shadow artifacts without
destroying luma texture.

## 11. Color-Volume Preservation

After tone mapping, chroma must stay inside the target display volume.

Peak scale:

```text
peakScale = clamp(outputPeakNits / max(inputPeakNits, eps), 0.05, 1.0)
```

Color-volume compression:

```text
compression = colorVolumeCompression
chromaKeep = 1 - compression * (1 - peakScale)
```

Apply to each channel:

```text
R_out = Y_out + (R_scaled - Y_out) * chromaKeep
G_out = Y_out + (G_scaled - Y_out) * chromaKeep
B_out = Y_out + (B_scaled - Y_out) * chromaKeep
```

NIVUKX Vision uses stronger color-volume compression than NDR10+ to protect
neon colors, saturated highlights, and skin tones near clipping.

## 12. Neo Dynamic Metadata Sidecar

NeoProCamX dynamic metadata is a sidecar, not official vendor dynamic metadata.

Recommended sidecar fields:

```text
selectedProfile
activeTransport
transfer
primaries
matrix
encodedBitDepth
internalMathBitDepth
sourcePeakNits
targetPeakNits
kneeRatio
shoulderStrength
shadowLift
saturationPreservation
colorVolumeCompression
p01Nits
p50Nits
p95Nits
p99Nits
maxCllNits
maxFallNits
dynamicRangeScore
shadowRatio
highlightRatio
motionScore
noiseRisk
npv10MasterAvailable
vendorCertifiedHdr10Plus
vendorCertifiedDolbyVision
```

Truth rule:

```text
dynamicMetadataWritten=NEO_SIDECAR
vendorCertifiedHdr10Plus=false
vendorCertifiedDolbyVision=false
```

## 13. Android Transport Contract

The profile can be original while the transport is standard.

For normal encoded video:

```text
container=MP4/MOV
codec=HEVC Main10 when available
transfer=ST2084/PQ
primaries=BT.2020
matrix=BT.2020_NCL
encodedBitDepth=10
```

For NPV10 private master:

```text
container=NPV10
payload=P010_LE, YUV420P10_LE, or YUV420_8BIT_EXPANDED
storageBitDepth=10
sourceBitDepth=truthful device value
galleryVisible=false
manualMovConversion=true
```

If the source is 8-bit YUV expanded into 10-bit storage, metadata must say:

```text
sourceBitDepth=8
storageBitDepth=10
truthfulNative10Bit=false
```

## 14. Comparison

NDR10+:

- Balanced high-quality Neo dynamic range.
- Lower processing risk than NIVUKX Vision.
- Clean highlights and stable shadows.
- Best default premium dynamic-range mode.

NIVUKX Vision:

- Highest-end Neo dynamic range.
- Strongest highlight protection.
- Stronger color-volume compression.
- Better for cinematic scenes and difficult highlights.
- More computationally expensive.

Standard Android transport:

- Required for device playback.
- Does not define the Neo look.
- Does not make the mode a vendor-certified product.

## 15. Implementation Rules

Required:

```text
Preview and recording use the same selectedProfile.
Normal SDR must not save as NDR.
Log must save as Log, not NDR.
NDR10+ must not request certified vendor metadata.
NIVUKX Vision must not request certified vendor metadata.
Legacy vendor text must normalize to Neo modes internally.
Sidecar metadata must be truthful.
```

Forbidden:

```text
Do not show vendor product names as NeoProCamX product modes.
Do not write vendor enhancement metadata unless licensed and certified.
Do not claim official certification.
Do not label 8-bit fallback as native 10-bit.
Do not change preview behavior when recording starts.
```

## 16. Important User-Facing Explanation

Recommended wording:

```text
NDR10+ and NIVUKX Vision are original NeoProCamX computational cinema
profiles. They use standard Android 10-bit PQ/BT.2020 transport for playback
compatibility, but their tone mapping, color handling, metadata sidecar, and
processing behavior are NeoProCamX technology.
```

Short wording:

```text
NDR10+ and NIVUKX Vision are NeoProCamX original dynamic-range profiles.
They are not vendor-certified HDR formats.
```

## 17. Quality Targets

The measurable quality targets are:

```text
lower chroma noise
stable shadows
lower RGB dancing noise
smooth highlight rolloff
skin-tone preservation
no fake vendor metadata
truthful 10-bit reporting
preview/recording parity
manual NPV10 private-master workflow
```

Suggested measurements:

```text
SNR improvement
chroma noise level
highlight clipping rate
deltaE color error
SSIM
VMAF
frame drop count
metadata truth check
ffprobe or MediaInfo transport check
adb log verification
```

## 18. Final Definition

NDR10+ is NeoProCamX's balanced 10-bit dynamic-range profile.

NIVUKX Vision is NeoProCamX's premium cinematic dynamic-range profile.

Both are original NeoProCamX technologies. Both can use Android PQ/BT.2020
Main10 transport. Neither should be represented as an official vendor-certified
dynamic metadata format.

