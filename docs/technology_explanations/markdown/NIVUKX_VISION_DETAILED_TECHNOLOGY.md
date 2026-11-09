# NIVUKX Vision Detailed Technology Specification

Project: NeoProCamX
Technology owner: NeoProCamX
Status: Project-owned elite HDR and color-volume technology

## 1. Identity

NIVUKX Vision is the premium NeoProCamX HDR color-volume and tone-mapping
profile. It sits above NDR10+ in the product stack. NDR10+ is the balanced
natural dynamic-range mode; NIVUKX Vision is the high-end cinematic mode with
stronger highlight control, deeper color-volume protection, stronger metadata,
and stricter truth reporting.

NIVUKX Vision is not Dolby Vision. It does not embed Dolby RPU data and does
not claim Dolby certification. It uses project-owned metadata and open
standards transport where the device supports them.

Truth boundary:

```text
selectedProfile = NIVUKX Vision
productProfile = NIVUKX-DR_ELITE
vendorCertified = false
vendorRpuWritten = false
externalProfileUsed = false
truthBoundary = PROJECT_OWNED_NO_VENDOR_PROFILE
```

## 2. Pipeline Overview

NIVUKX Vision is designed as a scene-referred color pipeline:

```text
Camera2 / RAW / YUV input
-> linear sensor normalization
-> ACES AP1 scene-linear working gamut
-> JzAzBz appearance-space conversion
-> per-frame tone anchor solve
-> 16x16 R-tile local deltas
-> saliency-aware class protection
-> radial chroma compression
-> house look / parametric color grade
-> edge-aware denoise
-> Jz-only local contrast sharpening
-> ST.2084 PQ encode
-> HEVC/AV1 Main12 if honored, Main10 fallback if not
-> T0..T7 Truth Metadata Block
```

Preview, recording, and export should share the same parameters. Any fallback
from Main12 to Main10, or from HDR transport to SDR/wide-gamut fallback, must
be written into metadata.

## 3. Transport Policy

NIVUKX Vision requests 12-bit transport first:

```text
requestedBitDepth = 12
workingGamut = ACES_AP1_FP16_SCENE_LINEAR
appearanceModel = JZAZBZ_FP32
internalPrecision = FP32_TONE_SOLVE_FP16_LINEAR_STORAGE
transfer = ST2084_PQ
primaries = BT2020
range = LIMITED
metadataStack = T0_T1_T2_T3_T4_T5_RTILE_T6_SALIENCY_T7_KALMAN_TMB
```

Transport selection:

```text
if codec == AV1 and AV1 Main12 supported and source/storage >= 12:
    transport = AV1_MAIN12_BT2020_PQ
    effectiveBitDepth = 12
elif codec == HEVC and HEVC Main12 supported and source/storage >= 12:
    transport = HEVC_MAIN12_BT2020_PQ
    effectiveBitDepth = 12
elif Main10 transport available and source/storage >= 10:
    transport = HEVC_MAIN10_LITE_FALLBACK or AV1_MAIN10_LITE_FALLBACK
    effectiveBitDepth = 10
    fallbackReason = MAIN12_OR_AV1_12_UNAVAILABLE
else:
    transport = UNSUPPORTED_TRANSPORT_FALLBACK
    fallbackReason = MAIN10_TRANSPORT_UNAVAILABLE
```

## 4. ACES AP1 Working Space

NIVUKX Vision uses ACES AP1 as the internal working gamut for color operations.
The AP1 to XYZ matrix is:

```text
[X]   [ 0.6624541811  0.1340042065  0.1561876870 ] [R]
[Y] = [ 0.2722287168  0.6740817658  0.0536895174 ] [G]
[Z]   [-0.0055746495  0.0040607335  1.0103391003 ] [B]
```

The inverse XYZ to AP1 matrix is:

```text
[R]   [ 1.6410233797 -0.3248032942 -0.2364246952 ] [X]
[G] = [-0.6636628587  1.6153315917  0.0167563477 ] [Y]
[B]   [ 0.0117218943 -0.0082844420  0.9883948585 ] [Z]
```

## 5. JzAzBz Appearance Model

NIVUKX Vision performs tone and gamut decisions in JzAzBz-style appearance
coordinates so that hue is preserved at high brightness.

XYZ preprocessing:

```text
X' = 1.15 * X - 0.15 * Z
Y' = 0.66 * Y + 0.34 * X
```

Cone response:

```text
L =  0.41478972 * X' + 0.579999 * Y' + 0.0146480 * Z
M = -0.20151000 * X' + 1.120649 * Y' + 0.0531008 * Z
S = -0.01660080 * X' + 0.264800 * Y' + 0.6684799 * Z
```

Nonlinear response:

```text
n(v) = (3424/4096 + (2413/128) * (max(v,0)/10000)^(2610/16384)) /
       (1 + (2392/128) * (max(v,0)/10000)^(2610/16384))

L' = n(L)^(1.7 * 2523/32)
M' = n(M)^(1.7 * 2523/32)
S' = n(S)^(1.7 * 2523/32)
```

JzAzBz:

```text
Iz = 0.5 * (L' + M')
Jz = ((1 - 0.56) * Iz) / (1 - 0.56 * Iz) - 1.6295499532821566e-11
Az = 3.524000 * L' - 4.066708 * M' + 0.542708 * S'
Bz = 0.199076 * L' + 1.096799 * M' - 1.295875 * S'
```

Chroma and hue:

```text
C = sqrt(Az^2 + Bz^2)
h = atan2(Bz, Az)
```

All saturation and gamut compression preserve `h` unless an explicit creative
look intentionally rotates hue.

## 6. Three-Anchor Tone Mapping

NIVUKX Vision uses a toe/knee/shoulder solve:

```text
tau = clamp(0.7 * P01 + 0.3 * P10, 0.0001, P50 - 0.1)
K = clamp(0.65 * P90 + 0.35 * P99, P50 + 1, 4000)
S = clamp(0.5 * P99.9 + 0.5 * P99.99, K + 1, L_peak)
gammaToe = clamp(1.05 + clamp(P10 / max(P50, 0.0001), 0, 1) * 0.15, 1.05, 1.20)
kappa = clamp(1.0 + clamp((P99 - P90) / L_peak, 0, 1) * 1.5, 1.0, 2.5)
```

Tone map:

```text
T(L) = tau * (L / tau)^gammaToe                         if L <= tau
T(L) = L                                                if tau < L <= K
T(L) = K + (S - K) * tanh((L - K) / ((S - K) * kappa))  if L > K
```

Default anchor set used for metadata examples:

```text
luminanceNits = [0.02, 0.10, 1.0, 12.0, 90.0, 500.0, 1000.0, 3800.0, 6000.0, 8000.0]
peakNits = 10000
```

## 7. NIVUKX Scene Parameters

NIVUKX Vision solves color-volume parameters from the scene:

```text
omega = clamp(0.5 + 0.4 * max(p99, 0) / L_peak, 0.5, 1.2)
gammaCompress = clamp(0.25 + 0.6 * max(p99 - p50, 0) / L_peak, 0.25, 0.85)
```

These parameters drive highlight chroma compression:

```text
C_nivukx = C * (1 - tanh(omega * Jz^2) * gammaCompress)
Az' = C_nivukx * cos(h)
Bz' = C_nivukx * sin(h)
```

The operation is radial: hue stays constant while chroma is reduced near
extreme highlight pressure.

## 8. Radial Gamut Compression

For a Jz/hue-specific chroma boundary `Cmax`, NIVUKX Vision uses a soft knee:

```text
rho = 0.85 + 0.10 * smoothstep(P50, P99, Jz * L_peak)
knee = rho * Cmax

if C <= knee:
    C_mapped = C
else:
    span = max((1 - rho) * Cmax, 1e-12)
    C_mapped = knee + span * tanh((C - knee) / span)

scale = C_mapped / max(C, 1e-12)
Az' = Az * scale
Bz' = Bz * scale
```

Default boundary LUT:

```text
jzSamples = 33
hueSamples = 33
Cmax(j, h) = clamp(
    0.20 + 0.14 * (1 - jz)^0.65 + 0.035 * max(sin(hue * 2*pi), 0),
    0.16,
    0.42
)
```

## 9. Near-White Guard

Near white, saturated channel values can clip. NIVUKX Vision reduces chroma
only when all channels are close to the ceiling:

```text
m = min(1 - R, 1 - G, 1 - B)
s = smoothstep(0.02, 0.10, m)
nearWhiteScale = 0.15 + 0.85 * s

Az' = Az * nearWhiteScale
Bz' = Bz * nearWhiteScale
```

This prevents colored highlights from turning into clipped patches while
allowing saturated neon and fire to remain rich below the near-white zone.

## 10. Color Grading Model

NIVUKX Vision exposes six creative knobs:

```text
exposureEv in [-4, 4]
contrast in [0.7, 1.4]
saturation in [0.7, 1.5]
shadowWarmthDegrees in [-8, 8]
midtoneTintDegrees in [-8, 8]
highlightShoulderGain in [0.6, 1.0]
```

Shadow and midtone hue rotation are weighted by Jz:

```text
shadowWeight = 1 - smoothstep(0.18, 0.42, Jz)
midWeight = smoothstep(0.18, 0.38, Jz) *
            (1 - smoothstep(0.56, 0.82, Jz))

rotateDegrees = shadowWarmthDegrees * shadowWeight +
                midtoneTintDegrees * midWeight
```

Hue rotation in the Az/Bz plane:

```text
Az_rot = Az * cos(theta) - Bz * sin(theta)
Bz_rot = Az * sin(theta) + Bz * cos(theta)
```

Saturation:

```text
Az_final = Az_rot * saturation
Bz_final = Bz_rot * saturation
```

Exposure is tracked by the tone solve; chroma grading remains non-destructive.

## 11. House Looks

NIVUKX Vision ships project-owned 33^3 LUT definitions:

```text
NVX-Cinema-Neutral:
    saturation = 1.00
    warm = 0.000
    contrast = 1.00

NVX-Vivid-Neon:
    saturation = 1.16
    warm = -0.015
    contrast = 1.08

NVX-Filmic-Warm:
    saturation = 0.94
    warm = 0.030
    contrast = 0.96
```

LUT generator:

```text
luma = 0.2722287168 * R + 0.6740817658 * G + 0.0536895174 * B
contrastPivot = 0.42

Rc = clamp((R - pivot) * contrast + pivot, 0, 1)
Gc = clamp((G - pivot) * contrast + pivot, 0, 1)
Bc = clamp((B - pivot) * contrast + pivot, 0, 1)

Rout = clamp(luma + (Rc - luma) * saturation + warm * (1 - luma), 0, 1)
Gout = clamp(luma + (Gc - luma) * saturation + warm * 0.30 * (1 - luma), 0, 1)
Bout = clamp(luma + (Bc - luma) * saturation - warm * (1 - luma), 0, 1)
```

Each generated LUT text is SHA-256 hashed and stored in metadata.

## 12. NLogs12 Optional Master Curve

NIVUKX Vision can use an internal 12-bit NLogs route for master evidence:

```text
A = 250.0
B = 0.0078125
C = log10(B)
D = log10(A + B) - C
L_peak = 10000

encode(L_nits):
    normalized = clamp(L_nits / L_peak, 0, 1)
    return clamp((log10(A * normalized + B) - C) / D, 0, 1)

decode(code):
    x = clamp(code, 0, 1)
    normalized = clamp((10^(x * D + C) - B) / A, 0, 1)
    return normalized * L_peak
```

## 13. PQ Encode

Final HDR transport uses ST.2084 PQ:

```text
m1 = 2610 / 16384
m2 = 2523 / 32
c1 = 3424 / 4096
c2 = 2413 / 128
c3 = 2392 / 128

L_norm = clamp(L_nits / 10000, 0, 1)
PQ(L) = ((c1 + c2 * L_norm^m1) / (1 + c3 * L_norm^m1))^m2
```

Bit-depth quantization:

```text
code10 = round(clamp(PQ(L), 0, 1) * 1023)
code12 = round(clamp(PQ(L), 0, 1) * 4095)
```

## 14. Denoise

NIVUKX Vision denoise is linear-radiance-first and edge-aware:

```text
Y = dot(RGB, [0.2627, 0.6780, 0.0593])
variance = 0.0012 * clamp(Y, 0, 1) + 0.00022
```

Temporal merge:

```text
residual = length(current - previousAligned)
cutoff = sqrt(variance) * 4
x = residual / max(cutoff, 1e-6)
tukey = 0 if abs(x) >= 1 else (1 - x^2)^2
burstMerged = mix(current, previousAligned, tukey * 0.42)
```

Spatial edge-aware denoise:

```text
spatial = exp(-(dx^2 + dy^2) / 12.5)
range = exp(-(Y_sample - Y_center)^2 / max(variance * 8, 1e-6))
W = spatial * range
RGB_clean = sum(W * RGB_sample) / sum(W)
```

Micro-texture reinjection:

```text
snr = max(signal, 1e-6) / sqrt(max(variance, 1e-8))
snrDb = 20 * log10(snr)
preserve = smoothstep(12, 24, snrDb)
RGB_final = RGB_clean + (RGB_original - RGB_clean) * preserve * 0.35
```

## 15. Sharpening

NIVUKX Vision does not sharpen chroma. It sharpens only the Jz/lightness band:

```text
lowPass = (center * 4 + axial * 2 + diagonal) / 16
detail = center - lowPass
sigmoidDetail = 2 / (1 + exp(-2 * strength * detail)) - 1
specularProtect = 1 - smoothstep(P99, 1, Jz)
lifted = sigmoidDetail * specularProtect
Jz_out = clamp(Jz + clamp(lifted, -0.04, 0.04), 0, 1)
Az_out = Az
Bz_out = Bz
```

Maximum overshoot is 4 percent by design.

## 16. GLSL Tone Mapping Reference

```glsl
float nivukxThreeAnchorToneMap(float luminanceNits, vec4 anchors) {
    float tau = max(anchors.x, 0.0001);
    float knee = max(anchors.y, tau + 0.0001);
    float shoulder = max(anchors.z, knee + 1.0);
    float kappa = clamp(anchors.w, 1.0, 2.5);
    if (luminanceNits <= tau) {
        return tau * pow(max(luminanceNits / tau, 0.0), 1.10);
    }
    if (luminanceNits <= knee) return luminanceNits;
    float span = max(shoulder - knee, 1.0);
    return knee + span * tanh((luminanceNits - knee) / (span * kappa));
}

vec3 radialChromaCompress(vec3 jzazbz, float softKnee, float boundary) {
    float chroma = length(jzazbz.yz);
    if (chroma <= 0.000001) return jzazbz;
    float knee = boundary * clamp(softKnee, 0.0, 1.0);
    float mapped = chroma <= knee
        ? chroma
        : knee + (boundary - knee) * tanh((chroma - knee) / max(boundary - knee, 0.000001));
    return vec3(jzazbz.x, jzazbz.yz * min(mapped, boundary) / chroma);
}
```

## 17. Kotlin Reference Code

```kotlin
fun resolveSceneParameters(p50: Double, p99: Double, peak: Double = 10_000.0): SceneParameters {
    val p = peak.coerceAtLeast(1.0)
    return SceneParameters(
        omega = (0.5 + 0.4 * p99.coerceAtLeast(0.0) / p).coerceIn(0.5, 1.2),
        gammaCompress = (0.25 + 0.6 * ((p99 - p50).coerceAtLeast(0.0) / p)).coerceIn(0.25, 0.85)
    )
}

fun compressRadialChroma(color: JzAzBz, cMax: Double, p50: Double, p99: Double, lPeak: Double): JzAzBz {
    val c = hypot(color.az, color.bz)
    if (c <= 1e-12) return color
    val normalizedJz = color.jz * lPeak.coerceAtLeast(1.0)
    val rho = (0.85 + 0.10 * smoothstep(p50, p99, normalizedJz)).coerceIn(0.0, 0.999999)
    val knee = rho * cMax
    val mapped = if (c <= knee) c else {
        val span = ((1.0 - rho) * cMax).coerceAtLeast(1e-12)
        knee + span * tanh((c - knee) / span)
    }.coerceAtMost(cMax)
    val scale = mapped / c
    return color.copy(az = color.az * scale, bz = color.bz * scale)
}
```

## 18. Truth Metadata Block

NIVUKX Vision metadata is tiered:

```text
T0_PROVENANCE:
    productProfile, selectedProfile, truthBoundary

T1_STREAM:
    codec, transport, primaries, transfer, range,
    requestedBitDepth, honoredBitDepth, fallbackReason

T2_TITLE:
    width, height, fps, videoBitrateBps

T3_SCENE:
    workingGamut = ACES_AP1_FP16_SCENE_LINEAR
    appearanceModel = JZAZBZ_FP32
    toneMap = THREE_ANCHOR_TOE_KNEE_SHOULDER

T4_FRAME:
    toeNits, kneeNits, shoulderNits, gammaToe, kappa

T5_RTILE:
    tileGrid = 16x16
    deltaFields = KNEE_CHROMA_EXPOSURE
    maxMetadataBitrateRatio = 0.004

T6_SALIENCY:
    FACE_SKIN_SKY_FOLIAGE_SPECULAR_TEXT_NEON_FIRE_WATER_GENERIC
    maxMasks = 16
    vendorCertified = false

T7_KALMAN_GOP:
    state = KAPPA_TAU_KNEE_RHO
    smoothing = CUT_AWARE
    vendorRpuWritten = false
```

The TMB is Ed25519 signed and hash chained in the local implementation.

## 19. Comparison to NDR10+

```text
NDR10+:
    balanced natural HDR
    10-bit target
    sourcePeak around 8400..10000 nits
    targetPeak around 1420..1640 nits
    colorVolumeCompression = 0.54
    profile intent = natural highlight preservation

NIVUKX Vision:
    premium HDR color-volume profile
    12-bit requested, 10-bit fallback recorded honestly
    sourcePeak up to 10000 nits
    targetPeak around 1680..1940 nits
    colorVolumeCompression = 0.64
    profile intent = stronger color-volume control and richer cinematic grade
```

## 20. Quality Gates

NIVUKX Vision is valid only when:

```text
selectedProfile == NIVUKX Vision
vendorCertified == false
vendorRpuWritten == false
workingGamut == ACES_AP1_FP16_SCENE_LINEAR
appearanceModel == JZAZBZ_FP32
transport fallback is written into TMB
preview and recording shader parameters match
chroma compression preserves hue
sharpening does not alter chroma
metadata reports honored bit depth
```

## 21. Visual Target

NIVUKX Vision should produce:

```text
smooth 10000 nit highlight handling
deep color without hue clipping
rich neon/fire/sky rendering
clean shadows without plastic texture
high acutance without halos
scene-stable dynamic tone mapping
truthful playback on standard PQ decoders
```

The core rule is:

```text
project-owned color science, open transport, signed truth metadata, no vendor-profile imitation
```

