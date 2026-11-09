# NDR10+ Detailed Technology Specification

Project: NeoProCamX
Technology owner: NeoProCamX
Status: Project-owned dynamic range technology

## 1. Identity

NDR10+ means NeoProCamX Natural Dynamic Range 10-bit Plus. It is the
balanced Neo dynamic-range profile for natural highlight preservation, clean
shadow rendering, stable color, and 10-bit wide-gamut recording.

NDR10+ is not a vendor-certified HDR10+ profile. The project may use open
transport primitives such as BT.2020, BT.2100, ST.2084 PQ, HEVC Main10, AV1,
and Android MediaFormat color metadata, but the product profile and dynamic
tone logic are NeoProCamX-owned.

Truth boundary:

```text
selectedProfile = NDR10+
productFamily = NEOPROCAMX_NDR
vendorCertifiedHdr10Plus = false
vendorCertifiedDolbyVision = false
vendorRpuWritten = false
truthRule = record actual transport and fallback evidence
```

## 2. End-to-End Pipeline

NDR10+ operates as a scene-referred pipeline until final transport encoding:

```text
Camera2 / RAW / YUV input
-> sensor normalization and white balance stabilization
-> scene-linear RGB estimate
-> BT.2020 / Neo wide-gamut working RGB
-> luma and highlight statistics
-> NDR10+ tone anchor solve
-> luma-preserving tone map
-> neutral-white highlight repair
-> cyan/green highlight guard
-> radial color-volume compression
-> edge-aware denoise and detail preservation
-> PQ encode for BT.2020 transport
-> MediaCodec HEVC Main10 or fallback evidence
-> Neo truth metadata / sidecar
```

Preview and recording must resolve the same selected profile. If preview uses
NDR10+ but recording falls back to SDR or direct Camera2 bypass, the quality
contract must report that mismatch instead of pretending parity exists.

## 3. Transport Contract

Preferred recording transport:

```text
codec = HEVC or AV1
profile = Main10 when 10-bit is available
primaries = BT.2020
transfer = ST.2084 PQ
matrix = BT.2020 non-constant luminance
range = limited unless strict full-range mode is explicitly active
containerLabel = NDR10+
```

Fallback transport is allowed only with evidence:

```text
if Main10 unavailable:
    activeTransport = WIDE_GAMUT_SDR_OR_8BIT_FALLBACK
    fallbackReason = MAIN10_TRANSPORT_UNAVAILABLE
```

## 4. Scene-Linear Input Model

Let the input after camera normalization be scene-linear RGB:

```text
R >= 0, G >= 0, B >= 0
```

BT.2020 luminance is:

```text
Y = 0.2627 * R + 0.6780 * G + 0.0593 * B
Y_safe = max(Y, 1e-8)
```

The source signal is not display-referred at this stage. Tone mapping must
operate on luminance or perceptual lightness, not on final 10-bit PQ code
values.

## 5. Dynamic Profile Parameter Solve

NDR10+ uses a scene-adaptive profile. The core policy derives a scene boost
from highlight activity and dynamic-range score:

```text
avg = clamp(averageLuma, 0, 1)
highlights = clamp(highlightRatio, 0, 1)
dr = clamp(dynamicRangeScore, 0, 1)

sceneBoost = clamp(0.45 * highlights + 0.55 * dr, 0, 1)
shadowNeed = clamp(1 - avg, 0, 1)
```

NDR10+ default profile constants:

```text
sourcePeakNits = 8400 + 1600 * sceneBoost
targetPeakNits = 1420 + 220 * sceneBoost
kneeRatio = 0.59
shoulderStrength = 3.72 + 0.48 * sceneBoost
shadowLift = 0.0022 + 0.0028 * shadowNeed
saturationPreservation = 0.79
colorVolumeCompression = 0.54
dynamicMetadata = true
encoderBitDepth = 10
```

These values are tuned for natural sunlight rendering: preserve cloud texture,
avoid hard clipping, avoid cyan sky shift, and keep foreground shadows dark but
not crushed.

## 6. NDR10+ Signal Curve

For normalized scene-linear signal `x in [0, 1]`, the NDR10+ shaping curve is:

```text
toe(x) = smoothstep(0.0, 0.20, x) * 0.018 * (1 - x)
S_ndr(x) = clamp(x^0.92 + toe(x), 0, 1)
```

Where:

```text
smoothstep(a, b, x):
    t = clamp((x - a) / (b - a), 0, 1)
    return t * t * (3 - 2 * t)
```

The `x^0.92` term gives slightly lifted midtones without making the image look
like fake mobile HDR. The toe term gives extra low-level code density while
decaying toward highlights so it does not flatten the sky.

## 7. Three-Anchor Tone Mapping

For elite NDR10+ delivery, the tone mapper can use three anchors:

```text
P01, P10, P50, P90, P99, P99.9, P99.99 = luminance percentiles in nits

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

The toe preserves shadow separation. The identity middle range protects
faces, trees, ground, and natural contrast. The tanh shoulder compresses
sunlight and clouds smoothly instead of clipping them into flat white.

## 8. Highlight Rolloff and White Recovery

NDR10+ prevents hard clipping by mapping only highlight luminance while keeping
color direction stable:

```text
Y_in = bt2020Luminance(R, G, B)
Y_mapped = T(Y_in * sourcePeakNits) / targetPeakNits
scale = Y_mapped / max(Y_in, 1e-8)
RGB_tone = RGB * scale
```

When channels approach white, the neutral highlight guard reduces color skew:

```text
highlight = smoothstep(0.72, 6.0, Y)
maxC = max(R, G, B)
minC = min(R, G, B)
chromaSpread = clamp((maxC - minC) / max(maxC, 1e-8), 0, 1)
neutralBlend = clamp(highlight * chromaSpread * 0.30, 0, 0.30)

R' = Y + (R - Y) * (1 - neutralBlend)
G' = Y + (G - Y) * (1 - neutralBlend)
B' = Y + (B - Y) * (1 - neutralBlend)
```

The result is rescaled back to the desired luminance so highlights become more
neutral without losing energy.

## 9. Cyan and Green Highlight Shift Guard

Bright sky and foliage can shift cyan/green when one channel clips earlier
than the others. NDR10+ uses a luma-preserving correction:

```text
shadowWindow = 1 - smoothstep(0.05, 0.32, Y)
coolDominance = smoothstep(1.18, 2.85, max(G, B) / max(R, 1e-8))
guard = clamp(shadowWindow * coolDominance * 0.58, 0, 0.48)

R1 = R + (max(R, max(G, B) * 0.52) - R) * guard
G1 = Y + (G - Y) * (1 - guard * 0.42)
B1 = Y + (B - Y) * (1 - guard * 0.58)

RGB_out = RGB1 * (Y / max(bt2020Luminance(RGB1), 1e-8))
```

This specifically targets the artificial cyan/green cast without globally
desaturating the image.

## 10. Color-Volume Compression

When the output peak is lower than the input peak, chroma must be compressed
without changing hue:

```text
peakScale = clamp(outputPeakNits / inputPeakNits, 0.05, 1.0)
targetY = Y * peakScale
scale = targetY / max(Y, 1e-8)
RGB_scaled = RGB * scale
gray = bt2020Luminance(RGB_scaled)

RGB_final = gray + (RGB_scaled - gray) *
    (1 - colorVolumeCompression * (1 - peakScale))
```

For NDR10+:

```text
colorVolumeCompression = 0.54
saturationPreservation = 0.79
```

The goal is not maximum saturation. The goal is natural color at high
luminance without clipped channels.

## 11. ST.2084 PQ Encode

NDR10+ uses standard ST.2084 PQ for compatible HDR transport:

```text
m1 = 2610 / 16384
m2 = 2523 / 32
c1 = 3424 / 4096
c2 = 2413 / 128
c3 = 2392 / 128
L_norm = clamp(L_nits / 10000, 0, 1)

PQ(L) = ((c1 + c2 * L_norm^m1) / (1 + c3 * L_norm^m1))^m2
```

10-bit code:

```text
code10 = round(clamp(PQ(L), 0, 1) * 1023)
```

With dithering:

```text
d = u1 - u2                  // triangular PDF in [-1, 1]
code10 = round(clamp(PQ(L) + d / 1023, 0, 1) * 1023)
```

## 12. Noise and Texture Model

NDR10+ keeps shadows clean without making foliage, skin, or fabric waxy. The
noise estimate is heteroscedastic:

```text
variance(Y) = 0.0012 * clamp(Y, 0, 1) + 0.00022
```

Temporal merge uses robust residual rejection:

```text
x = residual / cutoff
tukey(x) = 0                         if abs(x) >= 1
tukey(x) = (1 - x^2)^2               otherwise
```

Spatial denoise is edge-aware:

```text
W(i) = exp(-distance2(i) / 12.5) *
       exp(-(Y_i - Y_center)^2 / max(variance * 8, 1e-6))

RGB_clean = sum(W(i) * RGB_i) / sum(W(i))
```

Micro-texture is reintroduced only where SNR supports it:

```text
snr = signal / sqrt(variance)
snrDb = 20 * log10(max(snr, 1e-6))
preserve = smoothstep(12, 24, snrDb)
RGB_final = RGB_clean + (RGB_original - RGB_clean) * preserve * 0.35
```

## 13. Local Tone Mapping

NDR10+ local control separates sky, highlights, midtones, and foreground:

```text
tileGrid = 16 x 16
tilePayload = deltaKnee, deltaChroma, deltaExposure

K_tile = K_global + deltaKnee
C_tile = C_global * (1 + deltaChroma)
E_tile = exposure + deltaExposure
```

Class-aware policy:

```text
sky:      reduce knee, lock hue to local sky median
skin:     protect 60..320 nits and avoid hue rotation
foliage:  preserve green texture, do not crush low chroma
specular: use tanh shoulder, never flat clip
shadow:   lift detail only below noise-aware threshold
```

## 14. HDR Merge Stability

For multi-exposure capture, per-pixel radiance is merged from short, medium,
and long exposures:

```text
L_i = raw_i / exposureTime_i
clipWeight_i = 1 - smoothstep(clipStart, clipEnd, raw_i)
shadowWeight_i = smoothstep(noiseFloor, noiseFloor * 4, raw_i)
motionWeight_i = exp(-motionResidual_i^2 / sigmaMotion^2)
w_i = clipWeight_i * shadowWeight_i * motionWeight_i

L_merged = sum(w_i * L_i) / max(sum(w_i), 1e-8)
```

Short exposure protects sunlight and clouds. Long exposure recovers trees and
ground. Motion rejection prevents ghosting.

## 15. RAW-Domain Highlight Reconstruction

NDR10+ highlight reconstruction works before display mapping:

```text
if channel clipped:
    Y_neighborhood = robustMedian(validNeighborLuma)
    chromaRatio = median(validNeighborChannel / validNeighborLuma)
    channelRecovered = Y_neighborhood * chromaRatio
```

Edge-aware interpolation:

```text
gradientH = abs(Y[x-1,y] - Y[x+1,y])
gradientV = abs(Y[x,y-1] - Y[x,y+1])
use horizontal neighbors if gradientH < gradientV else vertical neighbors
```

The reconstruction target is cloud structure and sunlight boundary shape, not
invented detail.

## 16. Color Grading

NDR10+ grading is hue-stable. Saturation is a radial operation in perceptual
chroma rather than RGB multiplication:

```text
C = sqrt(a^2 + b^2)
h = atan2(b, a)
C' = C * saturation
a' = C' * cos(h)
b' = C' * sin(h)
```

Lightness contrast is applied only to the lightness axis:

```text
L' = 1 / (1 + exp(-contrast * (L - pivot)))
```

Highlight desaturation is local and smooth:

```text
C_final = C' * (1 - smoothstep(0.85, 0.98, L') * 0.85)
```

## 17. Kotlin Reference Code

```kotlin
data class NdrProfile(
    val sourcePeakNits: Double,
    val targetPeakNits: Double,
    val kneeRatio: Double,
    val shoulderStrength: Double,
    val shadowLift: Double,
    val saturationPreservation: Double,
    val colorVolumeCompression: Double
)

fun resolveNdr10PlusProfile(avg: Double, highlightRatio: Double, drScore: Double): NdrProfile {
    val sceneBoost = (highlightRatio.coerceIn(0.0, 1.0) * 0.45 +
        drScore.coerceIn(0.0, 1.0) * 0.55).coerceIn(0.0, 1.0)
    val shadowNeed = (1.0 - avg.coerceIn(0.0, 1.0)).coerceIn(0.0, 1.0)
    return NdrProfile(
        sourcePeakNits = 8400.0 + sceneBoost * 1600.0,
        targetPeakNits = 1420.0 + sceneBoost * 220.0,
        kneeRatio = 0.59,
        shoulderStrength = 3.72 + sceneBoost * 0.48,
        shadowLift = 0.0022 + shadowNeed * 0.0028,
        saturationPreservation = 0.79,
        colorVolumeCompression = 0.54
    )
}

fun ndr10PlusSignal(x: Double): Double {
    val v = x.coerceIn(0.0, 1.0)
    val t = smoothstep(0.0, 0.20, v) * 0.018 * (1.0 - v)
    return (v.pow(0.92) + t).coerceIn(0.0, 1.0)
}
```

## 18. GLSL Reference Code

```glsl
float smoothstep01(float edge0, float edge1, float x) {
    float t = clamp((x - edge0) / max(edge1 - edge0, 1e-6), 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}

float ndr10PlusSignal(float x) {
    float v = clamp(x, 0.0, 1.0);
    float toe = smoothstep01(0.0, 0.20, v) * 0.018 * (1.0 - v);
    return clamp(pow(v, 0.92) + toe, 0.0, 1.0);
}

float ndrShoulder(float l, float knee, float shoulder, float kappa) {
    if (l <= knee) return l;
    float span = max(shoulder - knee, 1.0);
    return knee + span * tanh((l - knee) / (span * clamp(kappa, 1.0, 2.5)));
}

vec3 neutralHighlightGuard(vec3 rgb) {
    float y = dot(rgb, vec3(0.2627, 0.6780, 0.0593));
    float maxC = max(max(rgb.r, rgb.g), rgb.b);
    float minC = min(min(rgb.r, rgb.g), rgb.b);
    float spread = clamp((maxC - minC) / max(maxC, 1e-8), 0.0, 1.0);
    float blend = clamp(smoothstep01(0.72, 6.0, y) * spread * 0.30, 0.0, 0.30);
    return vec3(
        y + (rgb.r - y) * (1.0 - blend),
        y + (rgb.g - y) * (1.0 - blend),
        y + (rgb.b - y) * (1.0 - blend)
    );
}
```

## 19. Metadata

NDR10+ metadata should include:

```text
selectedProfile = NDR10+
activeTransport = HEVC_MAIN10_BT2020_PQ or fallback value
primaries = BT2020
transfer = ST2084_PQ
matrix = BT2020_NCL
sourcePeakNits = solved value
targetPeakNits = solved value
kneeRatio = 0.59
shoulderStrength = solved value
colorVolumeCompression = 0.54
vendorCertified = false
fallbackReason = NONE or measured fallback
```

## 20. Quality Gates

NDR10+ is valid only when:

```text
previewProfile == recordingProfile
recordingColorStandard == BT2020
recordingTransfer == ST2084_PQ when HDR transport is claimed
bitDepth >= 10 when NDR10+ 10-bit is claimed
fallback evidence exists if bitDepth < 10
shader profile hash matches preview and recording path
dynamic metadata never claims external vendor certification
```

## 21. Visual Target

NDR10+ should look like natural scene luminance compressed into cinematic
display HDR:

```text
highlight preservation > dynamic range > natural color > cinematic contrast > detail retention
```

The desired look is:

```text
natural sunlight
deep but detailed shadows
smooth sky gradients
no cyan highlight shift
no crushed blacks
no fake over-bright mobile HDR
no aggressive halo sharpening
```

