#version 320 es
precision highp float;
precision highp sampler2D;
precision highp sampler3D;

layout(location = 0) in vec2 vTexCoord;
layout(location = 0) out vec4 outColor;

layout(binding = 0) uniform sampler2D uComputeEnhancedIntermediate;
layout(binding = 2) uniform sampler3D uPreviewLut;
layout(binding = 3) uniform sampler2D uSubjectDetailMask;
layout(std140, binding = 1) uniform PreviewParams {
    mat4 uTransform;
    vec4 uColorGain;
    vec4 uGrade0;      // contrast, saturation, shadowLift, highlightCompression
    vec4 uGrade1;      // redScale, greenScale, blueScale, logStrength
    vec4 uGrade2;      // redBias/255, greenBias/255, blueBias/255, HDR strength
    vec4 uGrade3;      // tone delivery mode, LUT size, shadowBiasScale, highlightBiasScale
    vec4 uEnhance0;    // spatial denoise, chroma denoise, sharpening, measured noise
    vec4 uSubjectDetail; // detail profile id, mask available, subject gain, mask feather
    mat4 uAcesCgToOklabReference; // ACEScg working buffer to D65 Oklab reference RGB
    mat4 uOklabReferenceToAcesCg; // D65 Oklab reference RGB back to ACEScg
    vec4 uNeoHdrParams; // enabled, lightness knee, shoulder, volume compression
    vec4 uNeoModeParams; // enabled, NLogs output, NIVUKX volume map, Neo ACES high-end look
    vec4 uNeoMeasuredCorrectionParams; // active, skin hue correction deg, sky hue correction deg, highlight bias
    vec4 uNeoIdentityParams; // color space id, color science id, active transport id, pipeline id
    vec4 uNeoMeasuredRgbScaleParams; // measured R/G/B scale, active
    vec4 uNeoGamutCuspParams; // CP-CUSP: x = exact-cusp enable (0=analytic), y = target gamut id (2=Rec.2020)
};

#define NEO_ACESCG_NON_CERTIFIED 1
#define ACESCG_LINEAR_WORKING_SPACE 1
#define FP32_BAYESIAN_PIPELINE 1
#define OMNIPOTENT_CINEMA_ENGINE_PIPELINE 1
#define ZENITH_COSMIC_PLANCK_PREVIEW_PIPELINE 1
const float ANAMORPHIC_DESQUEEZE_DEFAULT = 1.0;
// CP03 — surface-aware SDR preview dither headroom. Specialized per-target at pipeline
// creation (constant_id 0): 255 (8-bit) on 8-bit swapchains, 1023 (10-bit) on A2B10G10R10
// surfaces, so the on-screen monitor preview dithers to the REAL surface depth instead of
// re-quantizing to 8-bit and re-introducing banding on a 10-bit display surface. The default
// of 255 preserves the previous behaviour if no specialization is supplied.
layout(constant_id = 0) const float PREVIEW_QUANTIZATION_LEVELS = 255.0;
const float MASTER_QUANTIZATION_LEVELS = 1023.0;
const float DELIVERY_JND_DITHER_STRENGTH = 0.18;
const float PREVIEW_JND_DITHER_STRENGTH = 0.28;
const float LOG_PREVIEW_JND_DITHER_STRENGTH = 0.24;
const float REALTIME_COMPUTE_ENHANCED_ALPHA = 0.625;
const int ACTIVE_TRANSPORT_REC709_SDR = 1;
const int ACTIVE_TRANSPORT_BT2020_SDR = 2;
const float HELIOS_EPSILON = 0.000001;
const float HELIOS_DIFFUSE_WHITE_NITS = 203.0;
const float HELIOS_PEAK_NITS = 1000.0;
const vec3 HELIOS_REC2020_LUMA = vec3(0.2627, 0.6780, 0.0593);
const mat3 HELIOS_REC2020_TO_LMS = mat3(
    vec3(1688.0 / 4096.0, 683.0 / 4096.0, 99.0 / 4096.0),
    vec3(2146.0 / 4096.0, 2951.0 / 4096.0, 309.0 / 4096.0),
    vec3(262.0 / 4096.0, 462.0 / 4096.0, 3688.0 / 4096.0)
);
const mat3 HELIOS_LMS_TO_ICTCP = mat3(
    vec3(2048.0 / 4096.0, 6610.0 / 4096.0, 17933.0 / 4096.0),
    vec3(2048.0 / 4096.0, -13613.0 / 4096.0, -17390.0 / 4096.0),
    vec3(0.0, 7003.0 / 4096.0, -543.0 / 4096.0)
);

float neoRadianceHeliosShadowLift(float x) {
    x = max(x, 0.0);
    float lifted = pow(max(x / 0.08, 0.0), 0.65 + 0.25 * 0.40) * 0.08;
    float blend = smoothstep(0.06, 0.10, x);
    return mix(lifted, x, blend);
}

float neoRadianceHeliosCascadeLuma(float x, float contrast, float shoulderSoft, float strength) {
    x = max(x, 0.0);
    float p = log(1.0 + 3.5 * x);
    float spine = tanh((p - 0.65) * (2.2 + 1.8 * clamp(contrast, 0.0, 1.0)));
    float normalized = (spine + 1.0) * 0.5;
    float over = max(x - 1.0, 0.0);
    float shoulder = over * (1.0 - exp(-over / (1.2 + 2.0 * clamp(shoulderSoft, 0.0, 1.0))));
    float folded = normalized + shoulder;
    return mix(x, folded, clamp(strength, 0.0, 1.0));
}

float neoRadianceHeliosMapCascadeToNits(float folded) {
    float nits = max(folded, 0.0) * HELIOS_DIFFUSE_WHITE_NITS;
    float range = HELIOS_PEAK_NITS - HELIOS_DIFFUSE_WHITE_NITS;
    return nits <= HELIOS_DIFFUSE_WHITE_NITS
        ? nits
        : min(HELIOS_PEAK_NITS, HELIOS_DIFFUSE_WHITE_NITS + range * (1.0 - exp(-(nits - HELIOS_DIFFUSE_WHITE_NITS) / range)));
}

vec3 neoRadianceHeliosCascadeTone(vec3 rgb) {
    float y = dot(rgb, HELIOS_REC2020_LUMA);
    float lifted = neoRadianceHeliosShadowLift(y);
    float folded = neoRadianceHeliosCascadeLuma(lifted, 0.35, 0.25, 0.40);
    float mappedY = neoRadianceHeliosMapCascadeToNits(folded) / HELIOS_DIFFUSE_WHITE_NITS;
    return rgb * (mappedY / max(y, HELIOS_EPSILON));
}

vec3 neoRec709ToAcesCg(vec3 rgb) {
    rgb = max(rgb, vec3(0.0));
    return max(vec3(
        dot(rgb, vec3(0.61310, 0.33952, 0.04738)),
        dot(rgb, vec3(0.07019, 0.91635, 0.01345)),
        dot(rgb, vec3(0.02062, 0.10957, 0.86982))
    ), vec3(0.0));
}

vec3 neoRec2020ToAcesCg(vec3 rgb) {
    rgb = max(rgb, vec3(0.0));
    return max(vec3(
        dot(rgb, vec3(0.97489, 0.01960, 0.00551)),
        dot(rgb, vec3(0.00371, 0.98986, 0.00643)),
        dot(rgb, vec3(0.01622, 0.06780, 0.91598))
    ), vec3(0.0));
}

vec3 neoAcesCgToRec709(vec3 acesCg) {
    acesCg = max(acesCg, vec3(0.0));
    return max(vec3(
        dot(acesCg, vec3(1.70486, -0.62172, -0.08330)),
        dot(acesCg, vec3(-0.13008, 1.14074, -0.01056)),
        dot(acesCg, vec3(-0.02400, -0.12897, 1.15308))
    ), vec3(0.0));
}

vec3 neoAcesCgToRec2020(vec3 acesCg) {
    acesCg = max(acesCg, vec3(0.0));
    return max(vec3(
        dot(acesCg, vec3(1.02582, -0.02005, -0.00577)),
        dot(acesCg, vec3(-0.00386, 1.01040, -0.00654)),
        dot(acesCg, vec3(-0.01784, -0.07404, 1.09188))
    ), vec3(0.0));
}

vec3 neoDisplayLinearToAcesCg(vec3 linear, bool bt2020Transport) {
    return bt2020Transport ? neoRec2020ToAcesCg(linear) : neoRec709ToAcesCg(linear);
}

vec3 neoAcesCgToDisplayLinear(vec3 acesCg, bool bt2020Transport) {
    return bt2020Transport ? neoAcesCgToRec2020(acesCg) : neoAcesCgToRec709(acesCg);
}

vec3 neoAcesFittedRrtOdt(vec3 acesCg) {
    vec3 x = max(acesCg, vec3(0.0));
    vec3 numerator = x * (x + vec3(0.0245786)) - vec3(0.000090537);
    vec3 denominator = x * (vec3(0.983729) * x + vec3(0.4329510)) + vec3(0.238081);
    return clamp(numerator / max(denominator, vec3(1e-6)), 0.0, 1.0);
}

// Accurate sRGB EOTF (Electro-Optical Transfer Function)
vec3 srgbToLinear(vec3 srgb) {
    vec3 linearLow = srgb / 12.92;
    vec3 linearHigh = pow((srgb + 0.055) / 1.055, vec3(2.4));
    return mix(linearLow, linearHigh, step(vec3(0.04045), srgb));
}

vec3 linearToSrgb(vec3 linear) {
    vec3 srgbLow = linear * 12.92;
    vec3 srgbHigh = 1.055 * pow(linear, vec3(1.0/2.4)) - 0.055;
    return mix(srgbLow, srgbHigh, step(vec3(0.0031308), linear));
}

vec3 linearToBt709(vec3 linear) {
    vec3 safeLinear = max(linear, vec3(0.0));
    vec3 low = safeLinear * 4.5;
    vec3 high = 1.099 * pow(safeLinear, vec3(0.45)) - 0.099;
    return mix(low, high, step(vec3(0.018), safeLinear));
}

bool sdrUsesBt2020Transport() {
    return int(uNeoIdentityParams.z + 0.5) == ACTIVE_TRANSPORT_BT2020_SDR;
}

bool activeSdrTransport() {
    int activeTransport = int(uNeoIdentityParams.z + 0.5);
    return activeTransport == ACTIVE_TRANSPORT_REC709_SDR ||
        activeTransport == ACTIVE_TRANSPORT_BT2020_SDR;
}

bool realtimeComputeIntermediateAvailable(vec2 uv) {
    float sourceAlpha = texture(
        uComputeEnhancedIntermediate,
        clamp(uv, vec2(0.0), vec2(1.0))
    ).a;
    return abs(sourceAlpha - REALTIME_COMPUTE_ENHANCED_ALPHA) <= 0.015625;
}

vec3 encodeSdrActiveTransport(vec3 linearRgb) {
    vec3 transportLinear = max(linearRgb, vec3(0.0));
    if (sdrUsesBt2020Transport()) {
        transportLinear = neoAcesCgToRec2020(neoRec709ToAcesCg(transportLinear));
    }
    return clamp(linearToBt709(transportLinear), 0.0, 1.0);
}

float luminance(vec3 color) {
    return dot(color, vec3(0.2126, 0.7152, 0.0722));
}

float ndrSceneLuma(vec3 color) {
    return dot(color, vec3(0.2627, 0.6780, 0.0593));
}

float heteroscedasticNoiseVariance(float linearLuma) {
    float shotNoiseCoefficient = 0.0032;
    float readNoiseCoefficient = 0.00068;
    return max(
        shotNoiseCoefficient * clamp(linearLuma, 0.0, 1.0) + readNoiseCoefficient,
        0.00000004
    );
}

float noiseAdaptiveLaplacianGain(float baseGain, float localNoiseVariance, float threshold) {
    return baseGain * (1.0 - clamp(localNoiseVariance / max(threshold, 0.000001), 0.0, 1.0));
}

float previewPqOetf(float linearNits) {
    const float m1 = 0.1593017578125;
    const float m2 = 78.84375;
    const float c1 = 0.8359375;
    const float c2 = 18.8515625;
    const float c3 = 18.6875;
    float x = pow(max(linearNits, 0.0) / 10000.0, m1);
    return pow((c1 + c2 * x) / max(1.0 + c3 * x, 1e-6), m2);
}

float previewPqEotf(float pqValue) {
    const float m1 = 0.1593017578125;
    const float m2 = 78.84375;
    const float c1 = 0.8359375;
    const float c2 = 18.8515625;
    const float c3 = 18.6875;
    float x = pow(clamp(pqValue, 0.0, 1.0), 1.0 / m2);
    return 10000.0 * pow(max((x - c1) / max(c2 - c3 * x, 1e-6), 0.0), 1.0 / m1);
}

vec3 linearBt2020ToICtCp(vec3 rgb) {
    vec3 source = max(rgb, vec3(0.0)) * 203.0;
    // BT.2100 Rec.2020->LMS crosstalk (energy-conserving; rows sum to 1.0) — the
    // exact inverse of the LMS->RGB matrix used by ictcpToLinearBt2020, so the
    // ICtCp round-trip is identity. The previous rows (1688,2146,467 /
    // 1880,2048,444 / 756,502,3994) summed to 1.05/1.067/1.282 and were NOT that
    // inverse, so chromaDenoise injected a blue/hue shift on every pixel even at
    // zero denoise (audit rank 4). These match HELIOS_REC2020_TO_LMS.
    vec3 lms = vec3(
        dot(source, vec3(1688.0, 2146.0, 262.0) / 4096.0),
        dot(source, vec3(683.0, 2951.0, 462.0) / 4096.0),
        dot(source, vec3(99.0, 309.0, 3688.0) / 4096.0)
    );
    vec3 pqLms = vec3(previewPqOetf(lms.x), previewPqOetf(lms.y), previewPqOetf(lms.z));
    return vec3(
        0.5 * pqLms.x + 0.5 * pqLms.y,
        (6610.0 * pqLms.x - 13613.0 * pqLms.y + 7003.0 * pqLms.z) / 4096.0,
        (17933.0 * pqLms.x - 17390.0 * pqLms.y - 543.0 * pqLms.z) / 4096.0
    );
}

vec3 ictcpToLinearBt2020(vec3 ictcp) {
    float lPrime = ictcp.x + 0.008609 * ictcp.y + 0.111030 * ictcp.z;
    float mPrime = ictcp.x - 0.008609 * ictcp.y - 0.111030 * ictcp.z;
    float sPrime = ictcp.x + 0.560031 * ictcp.y - 0.320627 * ictcp.z;
    vec3 lms = vec3(previewPqEotf(lPrime), previewPqEotf(mPrime), previewPqEotf(sPrime));
    vec3 rgbNits = vec3(
        dot(lms, vec3(3.4366, -2.5064, 0.0698)),
        dot(lms, vec3(-0.7913, 1.9836, -0.1923)),
        dot(lms, vec3(-0.0259, -0.0989, 1.1248))
    );
    return max(rgbNits / 203.0, vec3(0.0));
}

float normalizeEnhancementStrength(float rawStrength) {
    return clamp(rawStrength > 1.0 ? rawStrength * 0.1 : rawStrength, 0.0, 1.0);
}

float normalProSdrDenoiseAuthority(float strength, bool boundedNormalProSdrQuality) {
    return boundedNormalProSdrQuality
        ? normalizeEnhancementStrength(strength)
        : (strength >= 9.5 ? 10.0 : 1.0);
}

bool strictDenoiseAuthorityActive(float authority, bool boundedNormalProSdrQuality) {
    return boundedNormalProSdrQuality ? authority >= 0.95 : authority > 1.0;
}

float strictNeutralNoiseGate(
    float luma,
    float chromaMagnitude,
    float denoiseAuthority,
    bool boundedNormalProSdrQuality
) {
    if (!strictDenoiseAuthorityActive(denoiseAuthority, boundedNormalProSdrQuality)) return 0.0;
    float shadowOrFlat = 1.0 - smoothstep(0.34, 0.92, luma);
    float neutralOnly = 1.0 - smoothstep(0.08, 0.28, chromaMagnitude);
    return clamp(shadowOrFlat * neutralOnly, 0.0, 1.0);
}

float strictRgbSpeckleGate(
    float luma,
    float chromaMagnitude,
    float denoiseAuthority,
    bool boundedNormalProSdrQuality
) {
    if (!strictDenoiseAuthorityActive(denoiseAuthority, boundedNormalProSdrQuality)) return 0.0;
    float lowMidSignal = 1.0 - smoothstep(0.34, 0.96, luma);
    float rgbSpeckle = smoothstep(0.0006, 0.072, chromaMagnitude) *
        (1.0 - smoothstep(0.24, 0.78, chromaMagnitude));
    return clamp(lowMidSignal * rgbSpeckle, 0.0, 1.0);
}

float strictTexturelessRgbGrainGate(
    float luma,
    float chromaMagnitude,
    float denoiseAuthority,
    bool boundedNormalProSdrQuality
) {
    if (!strictDenoiseAuthorityActive(denoiseAuthority, boundedNormalProSdrQuality)) return 0.0;
    float tonalWindow = smoothstep(0.020, 0.18, luma) *
        (1.0 - smoothstep(0.82, 1.0, luma) * 0.55);
    float fineRgbGrain = smoothstep(0.00045, 0.040, chromaMagnitude) *
        (1.0 - smoothstep(0.18, 0.54, chromaMagnitude));
    return clamp(tonalWindow * fineRgbGrain, 0.0, 1.0);
}

vec3 ictcpChromaDenoise(vec3 color, float strength, bool boundedNormalProSdrQuality) {
    if (strength <= 0.01) return color;
    float denoiseAuthority = normalProSdrDenoiseAuthority(strength, boundedNormalProSdrQuality);
    bool strictDenoise = strictDenoiseAuthorityActive(denoiseAuthority, boundedNormalProSdrQuality);
    float unitStrength = boundedNormalProSdrQuality
        ? denoiseAuthority
        : (denoiseAuthority > 1.0 ? 1.0 : clamp(strength, 0.0, 1.0));
    vec3 ictcp = linearBt2020ToICtCp(color);
    float intensity = clamp(ictcp.x, 0.0, 1.0);
    float chromaMagnitude = length(ictcp.yz);
    float shadowLock = 1.0 - smoothstep(0.18, 0.64, intensity);
    float chromaSpeckle = smoothstep(0.0015, 0.085, chromaMagnitude);
    float chromaBlend = clamp(
        unitStrength * chromaSpeckle * (0.52 + shadowLock * 0.48) +
            strictNeutralNoiseGate(intensity, chromaMagnitude, denoiseAuthority, boundedNormalProSdrQuality) *
                (boundedNormalProSdrQuality ? 1.0 : 10.0),
        0.0,
        strictDenoise ? 1.0 : 0.995
    );
    ictcp.yz = mix(ictcp.yz, vec2(0.0), chromaBlend);
    return clamp(ictcpToLinearBt2020(ictcp), 0.0, 1.08);
}

float bayesShrinkThreshold(float noiseVariance, float signalSigma) {
    return (noiseVariance * 0.065) / max(signalSigma, 0.0005);
}

float anisotropicOpticalFlowConfidence(float localRange, float detailMagnitude, float noiseVariance) {
    float structureConfidence = smoothstep(0.012, 0.22, localRange);
    float detailConfidence = smoothstep(0.003, 0.080, detailMagnitude);
    float noisePenalty = 1.0 - smoothstep(0.002, 0.026, noiseVariance);
    return clamp(structureConfidence * 0.55 + detailConfidence * 0.35 + noisePenalty * 0.10, 0.0, 1.0);
}

vec3 laplacianPyramidBayesShrink(
    vec3 center,
    vec3 lowBand,
    float noiseVariance,
    float localRange,
    float strength
) {
    vec3 coefficient = center - lowBand;
    float signalSigma = sqrt(max(dot(coefficient, coefficient) / 3.0 - noiseVariance * 0.060, 0.000001));
    float threshold = bayesShrinkThreshold(noiseVariance, signalSigma);
    vec3 shrunk = sign(coefficient) * max(abs(coefficient) - vec3(threshold), vec3(0.0));
    float motionConfidence = anisotropicOpticalFlowConfidence(localRange, length(coefficient), noiseVariance);
    float preserve = mix(0.91, 1.0, motionConfidence);
    return clamp(lowBand + shrunk * preserve * clamp(0.76 + strength * 0.24, 0.0, 1.0), 0.0, 1.08);
}

float hvsCsfMask(float localRange, float luma, float spatialFrequency) {
    float frequencyWindow = exp(-pow((clamp(spatialFrequency, 0.0, 1.0) - 0.38) / 0.34, 2.0));
    float textureConfidence = smoothstep(0.010, 0.18, localRange);
    float shadowVisibilityMask = smoothstep(0.045, 0.20, luma);
    float highlightMask = 1.0 - smoothstep(0.86, 0.99, luma);
    return clamp(frequencyWindow * textureConfidence * shadowVisibilityMask * highlightMask, 0.0, 1.0);
}

float csfMicroContrastWeight(float lightness, float baseGain) {
    float l = clamp(lightness, 0.0, 1.0);
    float shadowGate = pow(max(1.0 - l, 0.0), 1.2);
    float highlightGate = pow(max(l, 0.0), 0.8);
    return max(baseGain, 0.0) * shadowGate * highlightGate;
}

vec3 weberFechnerLocalAdaptation(vec2 uv, vec3 linear, float strength) {
    if (strength <= 0.001) return linear;
    ivec2 size = textureSize(uComputeEnhancedIntermediate, 0);
    vec2 texel = 1.0 / max(vec2(size), vec2(1.0));
    vec3 localMean = linear * 4.0;
    localMean += srgbToLinear(texture(uComputeEnhancedIntermediate, uv + vec2(texel.x, 0.0)).rgb);
    localMean += srgbToLinear(texture(uComputeEnhancedIntermediate, uv + vec2(-texel.x, 0.0)).rgb);
    localMean += srgbToLinear(texture(uComputeEnhancedIntermediate, uv + vec2(0.0, texel.y)).rgb);
    localMean += srgbToLinear(texture(uComputeEnhancedIntermediate, uv + vec2(0.0, -texel.y)).rgb);
    localMean /= 8.0;
    float localLuma = max(luminance(localMean), 0.018);
    vec3 detail = linear - localMean;
    float weberGain = clamp(strength / (localLuma + 0.08), 0.0, 0.42);
    return clamp(linear + detail * weberGain, 0.0, 1.08);
}

vec3 spectralReflectance31ToAcesAp0(vec3 linear) {
    vec3 rgb = max(linear, vec3(0.0));
    vec3 spectralAccumulator = vec3(0.0);
    for (int band = 0; band < 31; band++) {
        float lambda = float(band) / 30.0;
        float blueBasis = exp(-pow((lambda - 0.18) / 0.18, 2.0));
        float greenBasis = exp(-pow((lambda - 0.50) / 0.22, 2.0));
        float redBasis = exp(-pow((lambda - 0.78) / 0.20, 2.0));
        float reflectance = dot(rgb, normalize(vec3(redBasis + 1e-4, greenBasis + 1e-4, blueBasis + 1e-4)));
        spectralAccumulator += vec3(redBasis, greenBasis, blueBasis) * reflectance;
    }
    spectralAccumulator /= max(spectralAccumulator.r + spectralAccumulator.g + spectralAccumulator.b, 1e-5);
    vec3 ap0 = vec3(
        dot(rgb, vec3(0.95255, 0.00000, 0.00009)),
        dot(rgb, vec3(0.34397, 0.72817, -0.07213)),
        dot(rgb, vec3(0.00000, 0.00000, 1.00883))
    );
    return clamp(mix(rgb, ap0 * (0.86 + spectralAccumulator * 0.18), 0.18), 0.0, 1.08);
}

float blueNoiseScalar(vec2 uv, float phase) {
    vec2 p = floor(fract(uv * vec2(257.0, 241.0) + phase) * 64.0);
    float n = sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453;
    return fract(n);
}

float catmullRomWeight(float x) {
    float a = abs(x);
    if (a <= 1.0) {
        return 1.5 * a * a * a - 2.5 * a * a + 1.0;
    }
    if (a < 2.0) {
        return -0.5 * a * a * a + 2.5 * a * a - 4.0 * a + 2.0;
    }
    return 0.0;
}

vec3 bicubicPreviewSample(vec2 uv) {
    ivec2 imageSize = textureSize(uComputeEnhancedIntermediate, 0);
    vec2 textureExtent = max(vec2(imageSize), vec2(1.0));
    vec2 pixel = clamp(uv, vec2(0.0), vec2(1.0)) * textureExtent - vec2(0.5);
    vec2 basePixel = floor(pixel);
    vec2 fraction = pixel - basePixel;
    vec3 sum = vec3(0.0);
    float weightSum = 0.0;
    for (int y = -1; y <= 2; y++) {
        float wy = catmullRomWeight(float(y) - fraction.y);
        for (int x = -1; x <= 2; x++) {
            float wx = catmullRomWeight(float(x) - fraction.x);
            float w = wx * wy;
            vec2 sampleUv = (basePixel + vec2(float(x), float(y)) + vec2(0.5)) / textureExtent;
            sum += texture(uComputeEnhancedIntermediate, clamp(sampleUv, vec2(0.0), vec2(1.0))).rgb * w;
            weightSum += w;
        }
    }
    return sum / max(weightSum, 1e-6);
}

vec2 applyAnamorphicLensWarp(vec2 uv, float squeezeX, vec3 radialK, vec2 tangentialP) {
    vec2 centered = uv - vec2(0.5);
    centered.x /= max(squeezeX, 1e-4);
    float x = centered.x;
    float y = centered.y;
    float r2 = x * x + y * y;
    float r4 = r2 * r2;
    float r6 = r4 * r2;
    float k1 = radialK.x;
    float k2 = radialK.y;
    float k3 = radialK.z;
    float p1 = tangentialP.x;
    float p2 = tangentialP.y;
    float radialScale = 1.0 + k1 * r2 + k2 * r4 + k3 * r6;
    vec2 tangential = vec2(
        2.0 * p1 * x * y + p2 * (r2 + 2.0 * x * x),
        p1 * (r2 + 2.0 * y * y) + 2.0 * p2 * x * y
    );
    vec2 warped = centered * radialScale + tangential + vec2(0.5);
    return clamp(warped, vec2(0.0), vec2(1.0));
}

vec3 applyJndBlueNoiseDither(vec3 color, float strength, float quantizationLevels) {
    vec3 clamped = clamp(color, 0.0, 1.0);
    const float gammaJnd = 34.0;
    vec3 derivative = fwidth(clamped);
    float gradient2 = dot(derivative, derivative);
    float blueNoise = blueNoiseScalar((gl_FragCoord.xy + vec2(uGrade3.w * 97.0, uGrade3.x * 17.0)) / 64.0, uGrade3.w) - 0.5;
    // FIX (anti-banding): 8-bit banding appears on FLAT gradients, so dither must be at FULL strength
    // there and only ease off on hard edges (where detail already masks it). The previous factor
    // (1.0 - exp(-gammaJnd*gradient2)) went to 0 on flat areas, disabling dither exactly where it is
    // needed. edgeRelax = 1.0 on flats, relaxing toward 0.6 on strong edges.
    float edgeRelax = 1.0 - 0.4 * (1.0 - exp(-gammaJnd * gradient2));
    float ditherOffset = blueNoise * strength * edgeRelax;
    float levels = max(quantizationLevels, 1.0);
    return clamp(floor(levels * clamped + ditherOffset + 0.5) / levels, 0.0, 1.0);
}

vec3 temporalPhaseShiftedBlueNoiseGrain(vec2 uv, vec3 linear, float strength, float phase) {
    if (strength <= 0.001) return linear;
    float luma = luminance(linear);
    float shadowWindow = 1.0 - smoothstep(0.18, 0.72, luma);
    float highlightProtect = 1.0 - smoothstep(0.78, 0.98, luma);
    float grain = (blueNoiseScalar(uv, phase) - 0.5) * strength * shadowWindow * highlightProtect;
    return clamp(linear + vec3(grain), 0.0, 1.08);
}

vec3 warmHighlightHalation(vec2 uv, vec3 linear, float strength) {
    if (strength <= 0.001) return linear;
    ivec2 size = textureSize(uComputeEnhancedIntermediate, 0);
    vec2 texel = 1.0 / max(vec2(size), vec2(1.0));
    vec3 glow = vec3(0.0);
    float weightSum = 0.0;
    for (int dy = -2; dy <= 2; dy++) {
        for (int dx = -2; dx <= 2; dx++) {
            vec2 offset = vec2(float(dx), float(dy)) * texel * 2.0;
            vec3 sampleLinear = srgbToLinear(texture(uComputeEnhancedIntermediate, uv + offset).rgb);
            float highlight = smoothstep(0.74, 1.0, luminance(sampleLinear));
            float weight = exp(-dot(offset / max(texel, vec2(1e-5)), offset / max(texel, vec2(1e-5))) * 0.055);
            glow += sampleLinear * highlight * weight;
            weightSum += weight;
        }
    }
    glow /= max(weightSum, 1e-5);
    vec3 warmGlow = glow * vec3(1.18, 0.62, 0.34);
    float borderContrast = smoothstep(0.04, 0.42, luminance(glow) - luminance(linear));
    return clamp(linear + warmGlow * borderContrast * strength * 0.12, 0.0, 1.10);
}

vec3 linearAcesCgToOklab(vec3 acesCg) {
    vec3 rgb = max(acesCg, vec3(0.0));
    vec3 lms = vec3(
        dot(rgb, vec3(0.4122214708, 0.5363325363, 0.0514459929)),
        dot(rgb, vec3(0.2119034982, 0.6806995451, 0.1073969566)),
        dot(rgb, vec3(0.0883024619, 0.2817188376, 0.6299787005))
    );
    vec3 lmsRoot = pow(max(lms, vec3(0.0)), vec3(1.0 / 3.0));
    return vec3(
        dot(lmsRoot, vec3(0.2104542553, 0.7936177850, -0.0040720468)),
        dot(lmsRoot, vec3(1.9779984951, -2.4285922050, 0.4505937099)),
        dot(lmsRoot, vec3(0.0259040371, 0.7827717662, -0.8086757660))
    );
}

vec3 oklabToLinearAcesCg(vec3 lab) {
    float l = lab.x + 0.3963377774 * lab.y + 0.2158037573 * lab.z;
    float m = lab.x - 0.1055613458 * lab.y - 0.0638541728 * lab.z;
    float s = lab.x - 0.0894841775 * lab.y - 1.2914855480 * lab.z;
    vec3 lms = vec3(l * l * l, m * m * m, s * s * s);
    return max(vec3(
        dot(lms, vec3(4.0767416621, -3.3077115913, 0.2309699292)),
        dot(lms, vec3(-1.2684380046, 2.6097574011, -0.3413193965)),
        dot(lms, vec3(-0.0041960863, -0.7034186147, 1.7076147010))
    ), vec3(0.0));
}

vec3 neoAcesCgToReferenceOklab(vec3 acesCg) {
    vec3 referenceRgb = max((uAcesCgToOklabReference * vec4(acesCg, 1.0)).rgb, vec3(0.0));
    vec3 lms = vec3(
        dot(referenceRgb, vec3(0.4122214708, 0.5363325363, 0.0514459929)),
        dot(referenceRgb, vec3(0.2119034982, 0.6806995451, 0.1073969566)),
        dot(referenceRgb, vec3(0.0883024619, 0.2817188376, 0.6299787005))
    );
    vec3 lmsRoot = sign(lms) * pow(abs(lms), vec3(1.0 / 3.0));
    return vec3(
        dot(lmsRoot, vec3(0.2104542553, 0.7936177850, -0.0040720468)),
        dot(lmsRoot, vec3(1.9779984951, -2.4285922050, 0.4505937099)),
        dot(lmsRoot, vec3(0.0259040371, 0.7827717662, -0.8086757660))
    );
}

vec3 neoReferenceOklabToAcesCg(vec3 lab) {
    float l = lab.x + 0.3963377774 * lab.y + 0.2158037573 * lab.z;
    float m = lab.x - 0.1055613458 * lab.y - 0.0638541728 * lab.z;
    float s = lab.x - 0.0894841775 * lab.y - 1.2914855480 * lab.z;
    vec3 lms = vec3(l * l * l, m * m * m, s * s * s);
    vec3 referenceRgb = vec3(
        dot(lms, vec3(4.0767416621, -3.3077115913, 0.2309699292)),
        dot(lms, vec3(-1.2684380046, 2.6097574011, -0.3413193965)),
        dot(lms, vec3(-0.0041960863, -0.7034186147, 1.7076147010))
    );
    return max((uOklabReferenceToAcesCg * vec4(referenceRgb, 1.0)).rgb, vec3(0.0));
}

// CP-CUSP — exact per-hue Oklab gamut cusp (default OFF). Linear BT.709 -> BT.2020 (columns: result = M*srgb).
const mat3 NEO_LSRGB_TO_REC2020 = mat3(
    vec3(0.627404, 0.069097, 0.016391),
    vec3(0.329283, 0.919540, 0.088013),
    vec3(0.043313, 0.011362, 0.895595));
// SIGNED Ottosson Oklab -> linear sRGB (NO max(): the negative channels are needed for lower-hull detection).
vec3 neoOklabToLinearSrgbSigned(vec3 lab) {
    float l = lab.x + 0.3963377774 * lab.y + 0.2158037573 * lab.z;
    float m = lab.x - 0.1055613458 * lab.y - 0.0638541728 * lab.z;
    float s = lab.x - 0.0894841775 * lab.y - 1.2914855480 * lab.z;
    vec3 lms = vec3(l * l * l, m * m * m, s * s * s);
    return vec3(
        dot(lms, vec3(4.0767416621, -3.3077115913, 0.2309699292)),
        dot(lms, vec3(-1.2684380046, 2.6097574011, -0.3413193965)),
        dot(lms, vec3(-0.0041960863, -0.7034186147, 1.7076147010)));
}

float neoGamutApexHueBoundary(float lightness, float hue) {
    float lightnessGuard = 0.36 + 0.18 * (1.0 - abs(clamp(lightness, 0.0, 1.0) - 0.5) * 2.0);
    float hueShape = 0.04 * cos(3.0 * hue) + 0.018 * cos(6.0 * hue);
    float analytic = clamp(lightnessGuard + hueShape, 0.08, 0.62);
    // Default (x<0.5): identical analytic guard, byte-for-byte. When enabled, return the EXACT max chroma
    // of the target gamut (Rec.2020 when y>1.5) at this (lightness,hue) via a full-cube bisection. This is
    // a true gamut CLAMP: it reduces out-of-gamut oversaturation (it sits below the loose analytic guard).
    if (uNeoGamutCuspParams.x < 0.5) return analytic;
    float L = clamp(lightness, 0.0, 0.97);
    vec2 dir = vec2(cos(hue), sin(hue));
    bool wide = uNeoGamutCuspParams.y > 1.5;
    float lo = 0.0;
    float hi = 0.6;
    for (int i = 0; i < 24; i++) {
        float mid = 0.5 * (lo + hi);
        vec3 srgb = neoOklabToLinearSrgbSigned(vec3(L, dir * mid));
        vec3 tgt = wide ? (NEO_LSRGB_TO_REC2020 * srgb) : srgb;
        bool inside = all(greaterThanEqual(tgt, vec3(-1e-4))) && all(lessThanEqual(tgt, vec3(1.0 + 1e-4)));
        if (inside) { lo = mid; } else { hi = mid; }
    }
    return max(lo, 0.02);
}

float neoGamutApexCompressChroma(float lightness, float chroma, float hue) {
    float boundary = neoGamutApexHueBoundary(lightness, hue);
    float knee = boundary * 0.85;
    if (chroma <= knee) return chroma;
    return knee + (boundary - knee) * tanh((chroma - knee) / max(boundary - knee, 1e-6));
}

float neoGamutApexChromaPreservingToneMap(float lightness) {
    float l = max(lightness, 0.0);
    float toe = l * (1.0 + 0.18) / max(l + 0.18, 1e-6);
    float shoulder = log(1.0 + l * 11.2) / log(12.2);
    float t = smoothstep(0.78, 1.0, l);
    return clamp(mix(toe, shoulder, t), 0.0, 1.25);
}

float neoGamutApexProtectMemoryHue(float hue, float proposedHue, float center, float halfWidth) {
    float delta = atan(sin(proposedHue - hue), cos(proposedHue - hue));
    float inRange = 1.0 - smoothstep(halfWidth, halfWidth + 0.01, abs(atan(sin(hue - center), cos(hue - center))));
    return mix(proposedHue, hue + clamp(delta, radians(-2.0), radians(2.0)), inRange);
}

vec3 applyNeoGamutApexOkLchPipeline(vec3 acesCg, float neoAcesHighEnd) {
    vec3 lab = neoAcesCgToReferenceOklab(max(acesCg, vec3(0.0)));
    float lightness = max(lab.x, 0.0);
    float chroma = length(lab.yz);
    float hue = atan(lab.z, lab.y);
    float mappedL = neoGamutApexChromaPreservingToneMap(lightness);
    float mappedChroma = neoGamutApexCompressChroma(mappedL, chroma, hue);
    float highlightDesat = smoothstep(0.78, 1.12, lightness) * 0.35;
    mappedChroma *= 1.0 - highlightDesat;
    float protectedHue = hue;
    protectedHue = neoGamutApexProtectMemoryHue(hue, protectedHue, radians(51.0), radians(4.0));
    protectedHue = neoGamutApexProtectMemoryHue(hue, protectedHue, radians(262.5), radians(7.5));
    protectedHue = neoGamutApexProtectMemoryHue(hue, protectedHue, radians(140.0), radians(10.0));
    vec3 mapped = neoReferenceOklabToAcesCg(vec3(
        mappedL,
        cos(protectedHue) * mappedChroma,
        sin(protectedHue) * mappedChroma
    ));
    if (neoAcesHighEnd > 0.0) {
        float peak = max(mapped.r, max(mapped.g, mapped.b));
        float luma = dot(mapped, vec3(0.272229, 0.674082, 0.053689));
        mapped = mix(mapped, vec3(luma), smoothstep(0.86, 1.18, peak) * 0.18 * neoAcesHighEnd);
    }
    return clamp(mapped, 0.0, 1.08);
}

vec3 applyNeoGamutOkLchPipeline(vec3 acesCg) {
    if (uNeoModeParams.x <= 0.5) return acesCg;
    float neoColorSpaceId = uNeoIdentityParams.x;
    float neoColorScienceId = uNeoIdentityParams.y;
    float neoActiveTransportId = uNeoIdentityParams.z;
    float neoPipelineModeId = uNeoIdentityParams.w > 0.5
        ? uNeoIdentityParams.w
        : uNeoModeParams.w;
    if (uNeoModeParams.w >= 2.0) {
        float neoAcesAp0 = uNeoModeParams.w >= 4.0 ? 1.0 : 0.0;
        neoAcesAp0 = max(neoAcesAp0, neoColorSpaceId > 4.5 && neoColorSpaceId < 5.5 ? 1.0 : 0.0);
        float apexHighEnd = neoPipelineModeId >= 3.0 || neoColorScienceId > 30.5 ? 1.0 : 0.0;
        vec3 mapped = applyNeoGamutApexOkLchPipeline(acesCg, apexHighEnd);
        if (neoAcesAp0 > 0.0) {
            float luma = dot(mapped, vec3(0.272229, 0.674082, 0.053689));
            mapped = mix(mapped, vec3(luma), 0.04 * neoAcesAp0);
        }
        return mapped;
    }
    float neoAcesHighEnd = clamp(max(neoPipelineModeId, step(30.5, neoColorScienceId)), 0.0, 1.0);
    vec3 lab = neoAcesCgToReferenceOklab(max(acesCg, vec3(0.0)));
    float lightness = max(lab.x, 0.0);
    float chroma = length(lab.yz);
    float hue = atan(lab.z, lab.y);
    if (uNeoHdrParams.x > 0.5) {
        float knee = clamp(uNeoHdrParams.y, 0.25, 0.95);
        if (lightness > knee) {
            lightness = knee + (1.0 - knee) * tanh(
                max(uNeoHdrParams.z, 0.1) * (lightness - knee) / max(1.0 - knee, 1e-5)
            );
        }
        float volume = uNeoModeParams.z > 0.5
            ? tanh(max(uNeoHdrParams.z, 0.1) * lightness * lightness)
            : smoothstep(knee, 1.0, lightness);
        chroma *= 1.0 - clamp(volume * uNeoHdrParams.w, 0.0, 0.90);
    }
    if (neoAcesHighEnd > 0.0) {
        float highlightGuard = smoothstep(0.70, 1.08, lightness);
        float highEndMappedL = log(1.0 + 1.85 * lightness) / log(2.85);
        lightness = mix(lightness, highEndMappedL, 0.35 * neoAcesHighEnd);
        chroma *= 1.0 - clamp((0.18 + highlightGuard * 0.22) * neoAcesHighEnd, 0.0, 0.62);
    }
    chroma *= 1.0 - smoothstep(0.82, 0.98, lightness) * 0.85;
    vec3 mapped = neoReferenceOklabToAcesCg(vec3(
        lightness,
        cos(hue) * chroma,
        sin(hue) * chroma
    ));
    if (neoAcesHighEnd > 0.0) {
        float maxChannel = max(mapped.r, max(mapped.g, mapped.b));
        float minChannel = min(mapped.r, min(mapped.g, mapped.b));
        float saturation = clamp(maxChannel - minChannel, 0.0, 1.0);
        vec3 density = mapped * exp(-vec3(0.22, 0.20, 0.18) * (1.0 - clamp(mapped, 0.0, 1.0)) * saturation);
        mapped = mix(mapped, density, neoAcesHighEnd);
        float peak = max(mapped.r, max(mapped.g, mapped.b));
        float luma = dot(mapped, vec3(0.272229, 0.674082, 0.053689));
        mapped = mix(mapped, vec3(luma), smoothstep(0.72, 1.02, peak) * 0.36 * neoAcesHighEnd);
    }
    return clamp(mapped, 0.0, 1.08);
}

vec3 applyEliteCinemaColorPipeline(vec3 rgb, bool nivukxVision, bool ndr10Plus, float sceneMin, float sceneMax);

vec3 neoHdrPerceptualToneMap(vec3 rgb, bool nivukxVision) {
    if (uNeoModeParams.x > 0.5 && uNeoHdrParams.x > 0.5) return rgb;
    vec3 lab = linearAcesCgToOklab(max(rgb, vec3(0.0)));
    float knee = nivukxVision ? 0.55 : 0.59;
    float strength = nivukxVision ? 4.12 : 3.72;
    float mappedL = lab.x <= knee
        ? lab.x
        : knee + (1.0 - knee) *
            (1.0 - exp(-strength * (lab.x - knee) / max(1.0 - knee, 1e-5)));
    float chroma = length(lab.yz);
    float hue = atan(lab.z, lab.y);
    float chromaCompression = smoothstep(knee, 1.0, mappedL) *
        (nivukxVision ? 0.64 : 0.54);
    float mappedChroma = chroma * (1.0 - chromaCompression);
    vec3 candidate = oklabToLinearAcesCg(vec3(
        mappedL,
        cos(hue) * mappedChroma,
        sin(hue) * mappedChroma
    ));
    candidate = applyEliteCinemaColorPipeline(candidate, nivukxVision, !nivukxVision, 0.0004, 4.0);
    return clamp(candidate, 0.0, 1.0);
}

vec3 ndrLocalToneRegionWeights(float lumaValue) {
    float y = clamp(lumaValue, 0.0, 1.5);
    float shadow = 1.0 - smoothstep(0.035, 0.24, y);
    float highlight = smoothstep(0.54, 1.04, y);
    float midtone = clamp(1.0 - shadow - highlight * 0.72, 0.0, 1.0);
    return vec3(shadow, midtone, highlight);
}

vec3 ndrExposureMasks(float lumaValue, float localLuma, float peakValue) {
    float y = clamp(lumaValue, 0.0, 1.5);
    float localY = clamp(localLuma, 0.0, 1.5);
    float peak = clamp(peakValue, 0.0, 2.0);
    float shadow = (1.0 - smoothstep(0.045, 0.28, y)) *
        (1.0 - smoothstep(0.12, 0.42, localY));
    float highlight = smoothstep(0.56, 0.98, max(y, peak * 0.82));
    float midtone = clamp(1.0 - shadow * 0.82 - highlight * 0.78, 0.0, 1.0);
    return vec3(shadow, midtone, highlight);
}

float ndrLocalSceneLuma(vec2 uv, float fallbackY) {
    ivec2 size = textureSize(uComputeEnhancedIntermediate, 0);
    vec2 texel = 1.0 / max(vec2(size), vec2(1.0));
    float sum = fallbackY * 4.0;
    sum += ndrSceneLuma(srgbToLinear(texture(uComputeEnhancedIntermediate, uv + vec2(texel.x, 0.0)).rgb));
    sum += ndrSceneLuma(srgbToLinear(texture(uComputeEnhancedIntermediate, uv + vec2(-texel.x, 0.0)).rgb));
    sum += ndrSceneLuma(srgbToLinear(texture(uComputeEnhancedIntermediate, uv + vec2(0.0, texel.y)).rgb));
    sum += ndrSceneLuma(srgbToLinear(texture(uComputeEnhancedIntermediate, uv + vec2(0.0, -texel.y)).rgb));
    return max(sum / 8.0, 1e-5);
}

vec3 ndrRawChannelNormalize(vec3 rgb) {
    vec3 source = max(rgb, vec3(0.0));
    float sourceY = max(ndrSceneLuma(source), 1e-5);
    vec3 responseBalanced = source / vec3(1.18, 1.0, 0.96);
    responseBalanced = max(mat3(
        1.000, -0.010, -0.004,
       -0.015,  1.000, -0.010,
       -0.008, -0.006,  1.000
    ) * responseBalanced, vec3(0.0));
    float balancedY = max(ndrSceneLuma(responseBalanced), 1e-5);
    responseBalanced *= sourceY / balancedY;

    float redGreenRatio = responseBalanced.r / max(responseBalanced.g, 1e-5);
    float redOverflow = smoothstep(1.30, 1.86, redGreenRatio) *
        smoothstep(0.10, 1.20, sourceY) *
        (1.0 - smoothstep(5.20, 12.0, sourceY));
    responseBalanced.r = mix(responseBalanced.r, min(responseBalanced.r, responseBalanced.g * 1.30), redOverflow);
    responseBalanced.b = mix(responseBalanced.b, max(responseBalanced.b, responseBalanced.g * 0.78), redOverflow * 0.72);
    float correctedY = max(ndrSceneLuma(responseBalanced), 1e-5);
    return max(responseBalanced * (sourceY / correctedY), vec3(0.0));
}

float filmicLogLuminanceMap(float lumaValue, float alpha) {
    float a = max(alpha, 0.1);
    return log(1.0 + a * max(lumaValue, 0.0)) / log(1.0 + a);
}

vec3 rawDomainHighlightReconstruct(vec3 rgb) {
    vec3 source = max(rgb, vec3(0.0));
    float peak = max(max(source.r, source.g), source.b);
    float y = max(ndrSceneLuma(source), 1e-5);
    float clipped = smoothstep(0.96, 1.18, peak);
    if (clipped <= 0.001) return source;
    vec3 neutralStructure = vec3(y);
    vec3 preservedRatio = neutralStructure + (source - neutralStructure) * 0.72;
    return mix(source, preservedRatio, clipped * 0.38);
}

vec3 ndrSkyHighlightChromaGuard(vec3 rgb, bool nivukxVision) {
    vec3 source = max(rgb, vec3(0.0));
    float y = max(ndrSceneLuma(source), 1e-5);
    float peak = max(max(source.r, source.g), source.b);
    float cyanBlue = smoothstep(0.92, 1.42, source.b / max(source.r, 1e-5)) *
        smoothstep(0.82, 1.34, source.g / max(source.r, 1e-5));
    float brightSky = smoothstep(0.48, 0.92, y) * smoothstep(0.74, 1.08, peak);
    float nearClip = smoothstep(0.88, 1.08, peak);
    float guard = clamp(cyanBlue * brightSky * (nivukxVision ? 0.52 : 0.44), 0.0, 0.56);
    vec3 guarded = mix(
        source,
        vec3(y) + (source - vec3(y)) * mix(0.78, 0.58, nearClip),
        guard
    );
    guarded.g = mix(guarded.g, min(guarded.g, max(guarded.b * 0.98, y * 0.92)), guard * 0.28);
    float guardedY = max(ndrSceneLuma(guarded), 1e-5);
    return max(guarded * (y / guardedY), vec3(0.0));
}

vec3 ndrCyanGreenHighlightGuard(vec3 rgb, bool nivukxVision) {
    vec3 source = max(rgb, vec3(0.0));
    float y = max(ndrSceneLuma(source), 1e-5);
    float coolOverRed = max(source.g, source.b) / max(source.r, 1e-5);
    float highlight = smoothstep(0.56, 0.96, y);
    float cyanShift = smoothstep(1.18, 2.90, coolOverRed);
    float guard = clamp(highlight * cyanShift * (nivukxVision ? 0.58 : 0.48), 0.0, 0.64);
    vec3 guarded = source;
    guarded.r += (y - guarded.r) * guard * 0.76;
    guarded.g = y + (guarded.g - y) * (1.0 - guard * 0.44);
    guarded.b = y + (guarded.b - y) * (1.0 - guard * 0.54);
    float guardedY = max(ndrSceneLuma(guarded), 1e-5);
    return max(guarded * (y / guardedY), vec3(0.0));
}

vec3 ndrWhiteBalanceRecovery(vec3 rgb, bool nivukxVision) {
    vec3 source = max(rgb, vec3(0.0));
    float y = max(ndrSceneLuma(source), 1e-5);
    float redCast = smoothstep(1.36, 2.10, source.r / max(source.g, 1e-5));
    float cyanCast = smoothstep(1.42, 3.20, max(source.g, source.b) / max(source.r, 1e-5));
    float highlight = smoothstep(0.58, nivukxVision ? 0.92 : 0.96, y);
    float warmMemoryPreserve =
        smoothstep(1.04, 1.38, source.r / max(source.g, 1e-5)) *
        (1.0 - smoothstep(1.76, 2.12, source.r / max(source.g, 1e-5))) *
        smoothstep(0.50, 0.92, source.b / max(source.g, 1e-5)) *
        (1.0 - smoothstep(1.08, 1.34, source.b / max(source.g, 1e-5)));
    float castGuard = clamp(
        (redCast + cyanCast) * highlight * (nivukxVision ? 0.18 : 0.14) *
            (1.0 - warmMemoryPreserve * (nivukxVision ? 0.82 : 0.74)),
        0.0,
        0.28
    );
    vec3 neutralRecovered = mix(source, vec3(y), castGuard);
    float recoveredY = max(ndrSceneLuma(neutralRecovered), 1e-5);
    return max(neutralRecovered * (y / recoveredY), vec3(0.0));
}

float skinToneWeight(vec3 linearColor);

vec3 ndrRedChannelOverflowGuard(vec3 rgb, bool nivukxVision) {
    vec3 source = max(rgb, vec3(0.0));
    float y = max(ndrSceneLuma(source), 1e-5);
    float peak = max(max(source.r, source.g), source.b);
    float redGreenRatio = source.r / max(source.g, 1e-5);
    float redLumaRatio = source.r / max(y, 1e-5);
    float redBlueRatio = source.r / max(source.b, 1e-5);
    float redOverGreen = smoothstep(1.10, 1.42, redGreenRatio);
    float redOverLuma = smoothstep(1.16, 1.55, redLumaRatio);
    float clippedRed = smoothstep(0.72, 1.04, source.r) * smoothstep(1.12, 1.48, redGreenRatio);
    float midHighExposure = smoothstep(0.035, 0.20, y) * (1.0 - smoothstep(1.18, 1.72, y));
    float highlightPressure = smoothstep(0.54, 1.02, max(y, peak * 0.72));
    float skin = skinToneWeight(source);
    float redObject = smoothstep(1.22, 1.72, redGreenRatio) *
        smoothstep(1.08, 1.60, redBlueRatio) *
        (1.0 - skin);
    float guard = clamp(
        (max(redOverGreen, redOverLuma) * midHighExposure * (0.52 + highlightPressure * 0.34) +
            clippedRed * 0.22 + redObject * 0.18) *
            (nivukxVision ? 1.12 : 1.0),
        0.0,
        nivukxVision ? 0.82 : 0.74
    );
    if (guard <= 0.0001) return source;

    vec3 target = source;
    float redCeiling = mix(max(target.g * 1.22, y * 1.08), max(target.g * 1.14, y * 1.06), skin);
    float redObjectLumaCeiling = y * (nivukxVision ? 1.20 : 1.22);
    // Backstop: even for anything that still classifies as a red object, keep the red ceiling
    // restrained (closer to the skin cap) so a warm subject can never run to near-pure red. Genuine
    // reds stay clearly red (r up to ~1.36*g) but not neon -- matching a restrained reference look.
    float objectRedCeiling = max(target.g * (nivukxVision ? 1.34 : 1.36), redObjectLumaCeiling);
    redCeiling = mix(redCeiling, objectRedCeiling, redObject);
    float redTrimStrength = min(1.0, guard * mix(1.0, 0.52, redObject));
    target.r = mix(target.r, min(target.r, max(target.g * 1.14, y * 1.06)), redTrimStrength);
    target.r = mix(target.r, min(target.r, redCeiling), redTrimStrength * redObject);
    target.g = mix(target.g, max(target.g, y * 0.84), guard * 0.12 * skin);
    float skinBlueFloor = target.g * 0.70;
    float skinBlueCeiling = target.g * 0.90;
    target.b = mix(target.b, clamp(target.b, skinBlueFloor, skinBlueCeiling), guard * skin * 0.42);
    target.b = mix(target.b, min(target.b, target.g * 0.86), guard * redObject * 0.34);
    float targetY = max(ndrSceneLuma(target), 1e-5);
    float lumaRestore = clamp(y / targetY, 0.90, 1.02);
    vec3 exactLumaRestore = target * (y / targetY);
    vec3 boundedLumaRestore = target * lumaRestore;
    vec3 restored = mix(boundedLumaRestore, exactLumaRestore, redObject * 0.18);
    float redObjectTrim = mix(1.0, 0.992, redObject * guard);
    return max(restored * redObjectTrim, vec3(0.0));
}

vec3 ndrSkinToneProtect(vec3 rgb) {
    vec3 source = max(rgb, vec3(0.0));
    float y = max(ndrSceneLuma(source), 1e-5);
    float redGreenRatio = source.r / max(source.g, 1e-5);
    float blueGreenRatio = source.b / max(source.g, 1e-5);
    float warmSkinWindow = smoothstep(0.10, 0.68, y) *
        (1.0 - smoothstep(0.92, 1.08, y)) *
        smoothstep(1.14, 1.56, redGreenRatio) *
        (1.0 - smoothstep(1.82, 2.42, redGreenRatio));
    vec3 protectedColor = source;
    protectedColor.r = mix(protectedColor.r, min(protectedColor.r, protectedColor.g * 1.30), warmSkinWindow * 0.84);
    protectedColor.b = mix(protectedColor.b, max(protectedColor.b, protectedColor.g * 0.78), warmSkinWindow * smoothstep(0.0, 0.78, 0.78 - blueGreenRatio));
    float protectedY = max(ndrSceneLuma(protectedColor), 1e-5);
    return max(protectedColor * (y / protectedY), vec3(0.0));
}

vec3 ndrMeasuredSkinHueGuard(vec3 rgb, bool nivukxVision) {
    vec3 source = max(rgb, vec3(0.0));
    float y = max(ndrSceneLuma(source), 1e-5);
    float redGreenRatio = source.r / max(source.g, 1e-5);
    float redBlueRatio = source.r / max(source.b, 1e-5);
    float blueGreenRatio = source.b / max(source.g, 1e-5);
    float skinMemory = smoothstep(1.04, 1.22, redGreenRatio) *
        (1.0 - smoothstep(1.88, 2.44, redGreenRatio)) *
        smoothstep(0.42, 0.82, blueGreenRatio) *
        (1.0 - smoothstep(1.04, 1.22, blueGreenRatio)) *
        smoothstep(0.08, 0.24, y) *
        (1.0 - smoothstep(0.96, 1.30, y));
    float redSkinBackstop = smoothstep(1.34, 2.70, redGreenRatio) *
        (1.0 - smoothstep(5.40, 8.80, redGreenRatio)) *
        smoothstep(1.08, 2.80, redBlueRatio) *
        smoothstep(0.24, 0.78, blueGreenRatio) *
        (1.0 - smoothstep(1.10, 1.42, blueGreenRatio)) *
        smoothstep(0.06, 0.22, y) *
        (1.0 - smoothstep(0.92, 1.24, y));
    float skin = max(max(skinToneWeight(source), skinMemory * 0.86), redSkinBackstop * 0.78);
    if (skin <= 0.0001) return source;

    float redPressure = smoothstep(1.16, 1.54, redGreenRatio);
    float redPlatePressure = smoothstep(1.56, 2.66, redGreenRatio) *
        smoothstep(1.12, 2.80, redBlueRatio) *
        smoothstep(0.20, 0.76, blueGreenRatio);
    float bluePressure = smoothstep(0.96, 1.22, blueGreenRatio);
    float coolSkinPressure = smoothstep(0.78, 1.04, blueGreenRatio);
    float strength = clamp(
        skin * max(max(redPressure * 0.88, redPlatePressure * 1.18), max(bluePressure, coolSkinPressure * 0.86)) *
            (nivukxVision ? 0.42 : 0.46),
        0.0,
        nivukxVision ? 0.54 : 0.60
    );
    vec3 corrected = source;
    corrected.r = mix(
        corrected.r,
        min(corrected.r, corrected.g * (nivukxVision ? 1.13 : 1.10)),
        strength * max(0.48, redPressure)
    );
    float redSkinHardCeiling = max(corrected.g * (nivukxVision ? 1.10 : 1.08), y * (nivukxVision ? 1.04 : 1.02));
    corrected.r = mix(
        corrected.r,
        min(corrected.r, redSkinHardCeiling),
        clamp(redSkinBackstop * redPlatePressure * (nivukxVision ? 0.82 : 0.92), 0.0, 0.92)
    );
    float skinWarmthFloor = corrected.g * (nivukxVision ? 1.10 : 1.12);
    corrected.r = mix(
        corrected.r,
        max(corrected.r, skinWarmthFloor),
        strength * coolSkinPressure * 0.30 * (1.0 - redSkinBackstop)
    );
    float skinBlueFloor = corrected.g * (nivukxVision ? 0.70 : 0.66);
    float skinBlueCeiling = corrected.g * (nivukxVision ? 0.90 : 0.88);
    corrected.b = mix(
        corrected.b,
        clamp(corrected.b, skinBlueFloor, skinBlueCeiling),
        strength * mix(0.38, 0.92, max(bluePressure, coolSkinPressure))
    );
    float correctedY = max(ndrSceneLuma(corrected), 1e-5);
    float lumaRestore = clamp(y / correctedY, 0.90, 1.035);
    float skinLumaTrim = mix(1.0, nivukxVision ? 0.992 : 0.972, skin * smoothstep(0.24, 0.90, y));
    return max(corrected * lumaRestore * skinLumaTrim, vec3(0.0));
}

vec3 ndrMeasuredSkyHueGuard(vec3 rgb, bool nivukxVision) {
    vec3 source = max(rgb, vec3(0.0));
    float y = max(ndrSceneLuma(source), 1e-5);
    float peak = max(max(source.r, source.g), source.b);
    float blueDominance = smoothstep(1.08, 2.20, source.b / max(source.r, 1e-5));
    float cyanGreenPressure = smoothstep(0.70, 1.08, source.g / max(source.b, 1e-5));
    float greenRedBalance = smoothstep(0.72, 1.12, source.g / max(source.r, 1e-5)) *
        (1.0 - smoothstep(1.38, 1.78, source.g / max(source.r, 1e-5)));
    float bluePresence = smoothstep(0.34, 0.78, source.b / max(source.g, 1e-5));
    float redNotDominant = 1.0 - smoothstep(1.16, 1.48, source.r / max(source.g, 1e-5));
    float skyHueReject = max(
        skinToneWeight(source),
        smoothstep(1.08, 1.34, source.r / max(source.g, 1e-5)) *
            smoothstep(1.02, 1.28, source.r / max(source.b, 1e-5))
    );
    float skyBlueDeficit = smoothstep(-0.035, 0.085, max(source.r, source.g) - source.b);
    float warmSkyWindow = greenRedBalance * bluePresence * redNotDominant * (1.0 - skyHueReject);
    float warmHighlightSky = smoothstep(0.42, 0.92, y) *
        smoothstep(0.58, 1.04, source.g / max(source.r, 1e-5)) *
        smoothstep(0.32, 0.72, source.b / max(max(source.r, source.g), 1e-5)) *
        (1.0 - smoothstep(1.24, 1.56, source.r / max(source.g, 1e-5))) *
        (1.0 - skyHueReject);
    float skyCandidate = max(max(blueDominance * cyanGreenPressure, warmSkyWindow * 0.92), warmHighlightSky) *
        mix(0.88, 1.24, skyBlueDeficit);
    float exposureWindow = smoothstep(0.12, 0.84, y) * (1.0 - smoothstep(1.03, 1.36, peak));
    float strength = clamp(
        skyCandidate * exposureWindow * (nivukxVision ? 0.34 : 0.38),
        0.0,
        nivukxVision ? 0.42 : 0.54
    );
    if (strength <= 0.0001) return source;

    vec3 corrected = source;
    float skyBlueTarget = max(
        max(corrected.b, y * (nivukxVision ? 1.08 : 1.10)),
        max(corrected.r, corrected.g) * (nivukxVision ? 1.12 : 1.14)
    );
    corrected.b = mix(corrected.b, skyBlueTarget, strength * (nivukxVision ? 0.72 : 0.82));
    corrected.g = mix(
        corrected.g,
        min(corrected.g, skyBlueTarget * (nivukxVision ? 0.66 : 0.58) + y * (nivukxVision ? 0.20 : 0.16)),
        strength * (nivukxVision ? 0.96 : 1.08)
    );
    corrected.r = mix(
        corrected.r,
        min(corrected.r, y * (nivukxVision ? 0.56 : 0.50)),
        strength * (nivukxVision ? 0.92 : 1.04)
    );
    float correctedY = max(ndrSceneLuma(corrected), 1e-5);
    float lumaTrim = clamp(y / correctedY, nivukxVision ? 0.82 : 0.76, nivukxVision ? 0.97 : 0.92);
    float skyExposureTrim = skyCandidate * exposureWindow * smoothstep(0.34, 0.86, y) *
        mix(0.72, 1.0, skyBlueDeficit);
    float skyLumaTrim = mix(1.0, nivukxVision ? 0.750 : 0.845, skyExposureTrim);
    vec3 result = max(corrected * lumaTrim * skyLumaTrim, vec3(0.0));
    float resultY = max(ndrSceneLuma(result), 1e-5);
    float postSkyBlueFloor = max(
        resultY * (nivukxVision ? 1.11 : 1.16),
        max(result.r, result.g) * (nivukxVision ? 1.13 : 1.18)
    );
    result.b = mix(result.b, max(result.b, postSkyBlueFloor), strength * (nivukxVision ? 0.62 : 0.66));
    result.r = mix(result.r, min(result.r, resultY * (nivukxVision ? 0.60 : 0.55)), strength * (nivukxVision ? 0.46 : 0.52));
    result.g = mix(result.g, min(result.g, result.b * (nivukxVision ? 0.70 : 0.64)), strength * (nivukxVision ? 0.38 : 0.44));
    float skyChannelLock = clamp(skyCandidate * exposureWindow * (nivukxVision ? 0.72 : 0.60), 0.0, nivukxVision ? 0.72 : 0.64);
    result.r = mix(
        result.r,
        min(result.r, result.b * (nivukxVision ? 0.80 : 0.76) + resultY * (nivukxVision ? 0.05 : 0.04)),
        skyChannelLock
    );
    result.g = mix(
        result.g,
        min(result.g, result.b * (nivukxVision ? 0.88 : 0.82) + resultY * (nivukxVision ? 0.05 : 0.04)),
        skyChannelLock * 0.72
    );
    float postSkyHue = smoothstep(1.04, 1.36, result.b / max(result.r, 1e-5)) *
        smoothstep(0.70, 1.06, result.g / max(result.b, 1e-5)) *
        smoothstep(0.16, 0.92, resultY) *
        (1.0 - skinToneWeight(result));
    result.r = mix(result.r, min(result.r, result.b * (nivukxVision ? 0.68 : 0.68)), postSkyHue * (nivukxVision ? 0.60 : 0.50));
    result.g = mix(result.g, min(result.g, result.b * (nivukxVision ? 0.78 : 0.80)), postSkyHue * (nivukxVision ? 0.70 : 0.56));
    float finalY = max(ndrSceneLuma(result), 1e-5);
    return max(result * clamp(y / finalY, 0.84, 1.02), vec3(0.0));
}

vec3 ndrMeasuredHighlightBalanceGuard(vec3 rgb, bool nivukxVision) {
    vec3 source = max(rgb, vec3(0.0));
    float y = max(ndrSceneLuma(source), 1e-5);
    float peak = max(max(source.r, source.g), source.b);
    float chroma = peak - min(min(source.r, source.g), source.b);
    float blueDeficit = smoothstep(0.015, 0.12, max(source.r, source.g) - source.b);
    float warmPreserve = smoothstep(1.04, 1.24, source.r / max(source.g, 1e-5)) *
        smoothstep(0.08, 0.28, source.r - source.b);
    float cloudNeutralWindow = 1.0 - smoothstep(0.34, 0.58, chroma);
    float highlightNeutral = smoothstep(0.50, 0.90, y) *
        cloudNeutralWindow *
        blueDeficit *
        (1.0 - warmPreserve) *
        (1.0 - skinToneWeight(source));
    float strength = clamp(highlightNeutral * (nivukxVision ? 0.36 : 0.42), 0.0, nivukxVision ? 0.30 : 0.38);
    if (strength <= 0.0001) return source;

    vec3 corrected = source;
    float blueLiftTarget = max(max(corrected.r, corrected.g), corrected.b) * (nivukxVision ? 1.02 : 1.04);
    corrected.b = mix(corrected.b, max(corrected.b, blueLiftTarget), strength * 0.82);
    corrected.r = mix(corrected.r, min(corrected.r, y * (nivukxVision ? 0.90 : 0.84)), strength * 0.68);
    corrected.g = mix(corrected.g, min(corrected.g, y * (nivukxVision ? 0.96 : 0.90)), strength * 0.54);
    float correctedY = max(ndrSceneLuma(corrected), 1e-5);
    float highlightLumaTrim = mix(1.0, nivukxVision ? 0.970 : 0.935, strength);
    return max(corrected * clamp(y / correctedY, 0.78, 0.96) * highlightLumaTrim, vec3(0.0));
}

vec3 ndrMeasuredUniformCalibrationGuard(vec3 rgb, bool nivukxVision) {
    float correctionActive = step(0.5, uNeoMeasuredCorrectionParams.x);
    if (correctionActive <= 0.0) return max(rgb, vec3(0.0));

    vec3 source = max(rgb, vec3(0.0));
    float y = max(ndrSceneLuma(source), 1e-5);
    vec3 measuredRgbScale = mix(
        vec3(1.0),
        clamp(uNeoMeasuredRgbScaleParams.xyz, vec3(0.85), vec3(1.15)),
        correctionActive * step(0.5, uNeoMeasuredRgbScaleParams.w)
    );
    source *= measuredRgbScale;
    source *= clamp(y / max(ndrSceneLuma(source), 1e-5), 0.85, 1.15);
    float peak = max(max(source.r, source.g), source.b);
    float redGreenRatio = source.r / max(source.g, 1e-5);
    float redBlueRatio = source.r / max(source.b, 1e-5);
    float blueGreenRatio = source.b / max(source.g, 1e-5);
    float greenBlueRatio = source.g / max(source.b, 1e-5);

    float skinAuthority = correctionActive * clamp(abs(uNeoMeasuredCorrectionParams.y) / 8.0, 0.0, 1.0);
    float skyAuthority = correctionActive * clamp(abs(uNeoMeasuredCorrectionParams.z) / 8.0, 0.0, 1.0);
    float highlightAuthority = correctionActive * clamp(uNeoMeasuredCorrectionParams.w / 0.04, 0.0, 1.0);

    float warmSkinWindow = smoothstep(1.04, 1.24, redGreenRatio) *
        (1.0 - smoothstep(1.48, 1.90, redGreenRatio)) *
        smoothstep(1.02, 1.32, redBlueRatio) *
        smoothstep(0.07, 0.34, y) *
        (1.0 - smoothstep(0.86, 1.22, y));
    float skin = max(skinToneWeight(source), warmSkinWindow * 0.86) * skinAuthority;

    float skyCandidate = smoothstep(0.26, 0.88, y) *
        (1.0 - smoothstep(1.02, 1.32, peak)) *
        smoothstep(0.45, 1.05, blueGreenRatio) *
        smoothstep(0.64, 1.18, greenBlueRatio) *
        (1.0 - smoothstep(1.04, 1.34, redGreenRatio)) *
        (1.0 - skinToneWeight(source)) *
        skyAuthority;
    float warmSkyRepair = smoothstep(0.44, 0.96, y) *
        smoothstep(0.62, 1.12, source.g / max(source.r, 1e-5)) *
        smoothstep(0.30, 0.82, source.b / max(max(source.r, source.g), 1e-5)) *
        (1.0 - smoothstep(1.14, 1.48, redGreenRatio)) *
        (1.0 - skinToneWeight(source)) *
        skyAuthority;
    float sky = max(skyCandidate, warmSkyRepair);

    float neutralHighlight = smoothstep(0.56, 0.98, y) *
        smoothstep(0.02, 0.20, max(source.r, source.g) - source.b) *
        (1.0 - smoothstep(0.30, 0.58, peak - min(min(source.r, source.g), source.b))) *
        (1.0 - skinToneWeight(source)) *
        highlightAuthority;

    vec3 corrected = source;
    float skinStrength = clamp(skin * (nivukxVision ? 0.34 : 0.38), 0.0, 0.44);
    corrected.r = mix(corrected.r, min(corrected.r, corrected.g * (nivukxVision ? 1.14 : 1.10)), skinStrength);
    corrected.b = mix(
        corrected.b,
        clamp(corrected.b, corrected.g * (nivukxVision ? 0.68 : 0.64), corrected.g * (nivukxVision ? 0.88 : 0.86)),
        skinStrength * 0.84
    );

    float skyStrength = clamp(sky * (nivukxVision ? 0.58 : 0.66), 0.0, nivukxVision ? 0.62 : 0.70);
    float skyBlueTarget = max(max(corrected.b, y * (nivukxVision ? 1.12 : 1.16)), max(corrected.r, corrected.g) * (nivukxVision ? 1.10 : 1.14));
    corrected.b = mix(corrected.b, skyBlueTarget, skyStrength * 0.82);
    corrected.r = mix(corrected.r, min(corrected.r, skyBlueTarget * (nivukxVision ? 0.66 : 0.62)), skyStrength);
    corrected.g = mix(corrected.g, min(corrected.g, skyBlueTarget * (nivukxVision ? 0.78 : 0.74)), skyStrength * 0.82);

    float highlightStrength = clamp(neutralHighlight * (nivukxVision ? 0.42 : 0.50), 0.0, 0.54);
    float neutralTarget = max(max(corrected.r, corrected.g), corrected.b);
    corrected.b = mix(corrected.b, max(corrected.b, neutralTarget), highlightStrength * 0.76);
    corrected.r = mix(corrected.r, min(corrected.r, y * (nivukxVision ? 0.90 : 0.84)), highlightStrength * 0.72);
    corrected.g = mix(corrected.g, min(corrected.g, y * (nivukxVision ? 0.96 : 0.90)), highlightStrength * 0.56);

    float correctedY = max(ndrSceneLuma(corrected), 1e-5);
    return max(corrected * clamp(y / correctedY, 0.80, 1.04), vec3(0.0));
}

vec3 ndrRedPlateRecoveryGuard(vec3 rgb, bool nivukxVision) {
    vec3 source = max(rgb, vec3(0.0));
    float y = max(ndrSceneLuma(source), 1e-5);
    float redGreenRatio = source.r / max(source.g, 1e-5);
    float redBlueRatio = source.r / max(source.b, 1e-5);
    float greenLumaRatio = source.g / y;
    float blueLumaRatio = source.b / y;
    float redDominance = source.r - max(source.g, source.b);
    float midtonePlate = smoothstep(0.08, 0.24, y) *
        (1.0 - smoothstep(0.92, 1.26, y));
    float crushedCompanions =
        (1.0 - smoothstep(0.54, 0.88, greenLumaRatio)) *
        (1.0 - smoothstep(0.38, 0.72, blueLumaRatio));
    float redPlate = smoothstep(1.66, 3.40, redGreenRatio) *
        smoothstep(1.36, 3.20, redBlueRatio) *
        smoothstep(0.045, 0.26, redDominance) *
        midtonePlate;
    float skinPlateAuthority = max(
        skinToneWeight(source),
        smoothstep(1.18, 3.20, redGreenRatio) *
            smoothstep(1.08, 3.00, redBlueRatio) *
            smoothstep(0.05, 0.26, y) *
            (1.0 - smoothstep(1.02, 1.34, y))
    );
    float strength = clamp(
        redPlate * max(crushedCompanions * 0.96, skinPlateAuthority * 0.76) *
            (nivukxVision ? 0.82 : 0.92),
        0.0,
        nivukxVision ? 0.76 : 0.86
    );
    if (strength <= 0.0001) return source;

    vec3 recovered = source;
    float greenFloor = y * (nivukxVision ? 0.82 : 0.88);
    float blueFloor = y * (nivukxVision ? 0.50 : 0.56);
    recovered.g = mix(recovered.g, max(recovered.g, greenFloor), strength);
    recovered.b = mix(
        recovered.b,
        max(max(recovered.b, blueFloor), recovered.g * (nivukxVision ? 0.50 : 0.56)),
        strength * 0.84
    );
    float redCeiling = max(
        recovered.g * (nivukxVision ? 1.18 : 1.14),
        y * (nivukxVision ? 1.30 : 1.24)
    );
    recovered.r = mix(recovered.r, min(recovered.r, redCeiling), strength);

    float recoveredY = max(ndrSceneLuma(recovered), 1e-5);
    return max(recovered * clamp(y / recoveredY, 0.88, 1.04), vec3(0.0));
}

vec3 ndrMeasuredFinalColorGuard(vec3 rgb, bool nivukxVision) {
    vec3 balanced = ndrMeasuredHighlightBalanceGuard(rgb, nivukxVision);
    balanced = ndrMeasuredSkinHueGuard(balanced, nivukxVision);
    balanced = ndrRedPlateRecoveryGuard(balanced, nivukxVision);
    balanced = ndrRedChannelOverflowGuard(balanced, nivukxVision);
    balanced = ndrRedPlateRecoveryGuard(balanced, nivukxVision);
    balanced = ndrMeasuredSkyHueGuard(balanced, nivukxVision);
    balanced = ndrMeasuredUniformCalibrationGuard(balanced, nivukxVision);
    return balanced;
}

vec3 ndrDisplayColorVolumeFit(vec3 rgb, bool nivukxVision) {
    // Both profiles own the full normalized container range.  Profile character comes from their tone
    // and colour operators, not from lowering NIVUKX white or globally suppressing its chroma.
    const float white = 1.0;
    vec3 source = max(rgb, vec3(0.0));
    float y = clamp(ndrSceneLuma(source), 1e-5, white);
    vec3 fitted = source;

    float peak = max(max(fitted.r, fitted.g), fitted.b);
    if (peak > white) {
        float scale = clamp((white - y) / max(peak - y, 1e-5), 0.0, 1.0);
        fitted = vec3(y) + (fitted - vec3(y)) * scale;
    }

    float floorChannel = min(min(fitted.r, fitted.g), fitted.b);
    if (floorChannel < 0.0) {
        float scale = clamp(y / max(y - floorChannel, 1e-5), 0.0, 1.0);
        fitted = vec3(y) + (fitted - vec3(y)) * scale;
    }

    float fittedY = max(ndrSceneLuma(fitted), 1e-5);
    fitted *= clamp(y / fittedY, 0.90, 1.04);
    return clamp(fitted, 0.0, white);
}

vec3 rawLinearToDisplayNdrTransform(vec3 rgb, bool nivukxVision) {
    vec3 source = max(rgb, vec3(0.0));
    return ndrDisplayColorVolumeFit(source, nivukxVision);
}

vec3 ndrTexturePreservingNoiseClean(vec2 uv, vec3 rgb, vec3 exposureMasks) {
    vec3 source = max(rgb, vec3(0.0));
    float y = max(ndrSceneLuma(source), 1e-5);
    ivec2 size = textureSize(uComputeEnhancedIntermediate, 0);
    vec2 texel = 1.0 / max(vec2(size), vec2(1.0));
    vec3 sum = source * 3.6;
    float weightSum = 3.6;
    for (int i = 0; i < 4; ++i) {
        vec2 offset = i == 0 ? vec2(texel.x, 0.0)
            : i == 1 ? vec2(-texel.x, 0.0)
            : i == 2 ? vec2(0.0, texel.y)
            : vec2(0.0, -texel.y);
        vec3 sampleRgb = srgbToLinear(texture(uComputeEnhancedIntermediate, uv + offset).rgb);
        float sampleY = max(ndrSceneLuma(sampleRgb), 1e-5);
        float rangeWeight = exp(-abs(sampleY - y) * 18.0);
        float weight = rangeWeight * 0.55;
        sum += sampleRgb * weight;
        weightSum += weight;
    }
    vec3 filtered = sum / max(weightSum, 1e-5);
    float shadowNoise = exposureMasks.x * (1.0 - smoothstep(0.035, 0.20, y));
    float highlightNoise = exposureMasks.z * smoothstep(0.80, 1.0, max(max(source.r, source.g), source.b));
    float denoise = clamp(shadowNoise * 0.24 + highlightNoise * 0.08, 0.0, 0.28);
    vec3 cleaned = mix(source, filtered, denoise);
    float cleanedY = max(ndrSceneLuma(cleaned), 1e-5);
    return max(cleaned * (y / cleanedY), vec3(0.0));
}

vec3 ndrPixelAdaptiveHdrBalance(
    vec2 uv,
    vec3 mapped,
    vec3 source,
    bool nivukxVision,
    float highlightCompression,
    float shadowLift
) {
    vec3 base = max(mapped, vec3(0.0));
    float y = max(ndrSceneLuma(base), 1e-5);
    float sourceY = max(ndrSceneLuma(max(source, vec3(0.0))), 1e-5);
    float peak = max(max(base.r, base.g), base.b);
    float localY = ndrLocalSceneLuma(uv, y);
    vec3 masks = ndrExposureMasks(y, localY, peak);

    float localContrast = clamp((y - localY) / max(max(y, localY), 0.06), -1.0, 1.0);
    float shadowTargetY = y + masks.x * (1.0 - y) *
        (0.10 + shadowLift * (nivukxVision ? 0.34 : 0.28)) *
        (1.0 - smoothstep(0.20, 0.48, localY));
    float highlightCompressionStrength = masks.z *
        (0.16 + highlightCompression * (nivukxVision ? 0.48 : 0.40)) *
        smoothstep(0.68, 1.06, max(sourceY, peak));
    float highlightTargetY = y / (1.0 + highlightCompressionStrength * max(sourceY - y, 0.0) * 1.25);
    float balancedY = mix(shadowTargetY, min(shadowTargetY, highlightTargetY), masks.z);
    balancedY += masks.y * localContrast * 0.035;
    balancedY = clamp(balancedY, 0.0, 1.0);

    vec3 balanced = base * (balancedY / y);
    float chromaPreserve = 1.0 - masks.z * (0.18 + highlightCompression * 0.22);
    balanced = vec3(balancedY) + (balanced - vec3(balancedY)) * clamp(chromaPreserve, 0.55, 1.0);

    vec3 cleaned = ndrTexturePreservingNoiseClean(uv, balanced, masks);
    vec3 localMean = vec3(localY);
    vec3 detail = cleaned - localMean;
    float detailGate = masks.y * smoothstep(0.012, 0.18, abs(localContrast)) *
        (1.0 - masks.z * 0.72);
    vec3 detailed = cleaned + detail * detailGate * 0.08;
    float detailedY = max(ndrSceneLuma(detailed), 1e-5);
    return max(detailed * (balancedY / detailedY), vec3(0.0));
}

float reduceArtificialNdrSharpening(float requestedSharpening, vec3 rgb) {
    float y = ndrSceneLuma(rgb);
    float highlightProtect = 1.0 - smoothstep(0.74, 0.985, y) * 0.28;
    float shadowTextureProtect = mix(0.92, 1.0, smoothstep(0.035, 0.20, y));
    return clamp(requestedSharpening * highlightProtect * shadowTextureProtect, 0.0, 0.92);
}

vec3 naturalDynamicRangeToneMap(vec2 uv, vec3 rgb, bool nivukxVision, float highlightCompression, float shadowLift) {
    vec3 source = ndrRawChannelNormalize(max(rgb, vec3(0.0)));
    source = rawDomainHighlightReconstruct(source);
    source = ndrRedChannelOverflowGuard(source, nivukxVision);
    float sourceY = max(ndrSceneLuma(source), 1e-5);
    vec3 regionWeights = ndrLocalToneRegionWeights(sourceY);
    float knee = clamp((nivukxVision ? 0.56 : 0.60) - highlightCompression * 0.08, 0.44, 0.74);
    float white = 1.0;
    float shoulderStrength = (nivukxVision ? 2.05 : 1.78) + highlightCompression * 0.82;
    float logMappedY = filmicLogLuminanceMap(sourceY, nivukxVision ? 3.8 : 3.2);
    float shoulderY = sourceY <= knee
        ? sourceY
        : knee + (white - knee) * tanh(shoulderStrength * (sourceY - knee) / max(white - knee, 1e-5));
    shoulderY = mix(shoulderY, min(shoulderY, logMappedY * white), regionWeights.z * 0.26);
    float liftedShadowY = sourceY + shadowLift * 0.20 * regionWeights.x * (1.0 - sourceY);
    float mappedY = clamp(max(liftedShadowY, mix(sourceY, shoulderY, regionWeights.z)), 0.0, white);
    vec3 mapped = source * (mappedY / sourceY);

    float mappedLuma = max(ndrSceneLuma(mapped), 1e-5);
    float chromaPressure = smoothstep(0.58, white, mappedLuma);
    float channelPressure = smoothstep(1.08, 2.20, max(max(source.r, source.g), source.b) / sourceY);
    float chromaKeep = 1.0 - clamp(
        chromaPressure * (nivukxVision ? 0.10 : 0.08) + channelPressure * (nivukxVision ? 0.14 : 0.12),
        0.0,
        nivukxVision ? 0.34 : 0.30
    );
    mapped = vec3(mappedLuma) + (mapped - vec3(mappedLuma)) * chromaKeep;
    mapped = ndrSkyHighlightChromaGuard(mapped, nivukxVision);
    mapped = ndrCyanGreenHighlightGuard(mapped, nivukxVision);
    mapped = ndrWhiteBalanceRecovery(mapped, nivukxVision);
    mapped = ndrSkinToneProtect(mapped);
    mapped = ndrMeasuredSkinHueGuard(mapped, nivukxVision);
    mapped = ndrMeasuredSkyHueGuard(mapped, nivukxVision);
    mapped = ndrRedChannelOverflowGuard(mapped, nivukxVision);
    mapped = ndrPixelAdaptiveHdrBalance(uv, mapped, source, nivukxVision, highlightCompression, shadowLift);
    mapped = ndrMeasuredFinalColorGuard(mapped, nivukxVision);
    return rawLinearToDisplayNdrTransform(mapped, nivukxVision);
}

// True-SDR highlight rendering for Normal/Pro Rec.709/BT.2020-SDR delivery.
//
// The camera input is already display-referred.  The old path multiplied scene-linear RGB by a
// highlight-dependent gain before applying two filmic operators.  That lifted an already bright HAL
// signal, flattened midtones and pushed skies/windows into clip.  This operator is deliberately
// luminance preserving below its knee and can only hold/reduce luminance above it.  Chroma is carried
// by a single RGB scale, so blue skies and skin hue do not rotate as the shoulder engages.  No HDR
// transfer function or metadata is involved: the result continues through encodeSdrActiveTransport.
vec3 trueSdrHighlightShoulder(vec3 linearRgb, float highlightCompression) {
    vec3 source = max(linearRgb, vec3(0.0));
    float sourceY = dot(source, vec3(0.2126, 0.7152, 0.0722));
    if (sourceY <= 1.0e-6) return source;

    float compression = clamp(highlightCompression, 0.0, 1.0);
    float knee = mix(0.78, 0.58, compression);
    float shoulderSpan = max(1.0 - knee, 1.0e-4);
    float excess = max(sourceY - knee, 0.0);
    // C1-continuous at the knee (unit slope) and asymptotic to SDR reference white.
    float shoulderY = knee + shoulderSpan * (1.0 - exp(-excess / shoulderSpan));
    float highlightGate = smoothstep(knee, min(1.0, knee + 0.20), sourceY);
    float overRangeProtection = smoothstep(0.94, 1.24, sourceY) * 0.46;
    float shoulderMix = clamp(0.24 + compression * 1.55 + overRangeProtection, 0.0, 1.0) *
        highlightGate;
    float mappedY = min(sourceY, mix(sourceY, shoulderY, shoulderMix));
    vec3 mapped = source * (mappedY / sourceY);

    // Preserve hue first, then fit only genuine channel over-range into the SDR container.
    float peak = max(mapped.r, max(mapped.g, mapped.b));
    mapped *= peak > 1.0 ? 1.0 / peak : 1.0;
    return max(mapped, vec3(0.0));
}

const mat3 NEO_WIDE_GAMUT_TO_XYZ = mat3(
    0.774888889, 0.279812198, 0.000000000,
    0.098962557, 0.709575275, 0.050512138,
    0.082970662, 0.010612527, 0.871191949
);

const mat3 XYZ_TO_NEO_WIDE_GAMUT = mat3(
    1.355511229, -0.534992949, 0.031019155,
    -0.180015705, 1.481565471, -0.085901896,
    -0.126903425, 0.032903847, 1.145944883
);

vec3 neoWideGamutCompressXyPreserveY(vec3 xyz) {
    float sumXyz = max(xyz.x + xyz.y + xyz.z, 1e-6);
    vec2 xy = xyz.xy / sumXyz;
    vec2 center = vec2(0.3324, 0.3474);
    vec2 delta = xy - center;
    float radius = length(delta);
    float angle = atan(delta.y, delta.x);
    float softBoundary = 0.365 + 0.080 * cos(3.0 * angle) + 0.035 * cos(6.0 * angle + 0.4);
    softBoundary = clamp(softBoundary, 0.18, 0.48);
    if (radius > softBoundary) {
        float excess = radius - softBoundary;
        radius = softBoundary * (1.0 - 0.025 * (1.0 - exp(-excess * 6.0)));
        xy = center + normalize(delta) * radius;
    }
    float safeY = max(xy.y, 1e-5);
    return vec3(xy.x * xyz.y / safeY, xyz.y, (1.0 - xy.x - xy.y) * xyz.y / safeY);
}

vec3 hpeXyzToNivukxOklab(vec3 xyz) {
    vec3 lms = vec3(
        dot(xyz, vec3(0.8189330, 0.3670669, -0.1574301)),
        dot(xyz, vec3(-0.0351149, 1.0253456, 0.0097103)),
        dot(xyz, vec3(0.0815124, -0.0435106, 1.1251144))
    );
    vec3 root = sign(lms) * pow(abs(lms), vec3(1.0 / 3.0));
    return vec3(
        dot(root, vec3(0.2104542553, 0.7936177850, -0.0040720403)),
        dot(root, vec3(1.9779984951, -2.4285922050, 0.4505937099)),
        dot(root, vec3(0.0259040371, 0.7827717658, -0.8086757660))
    );
}

vec3 nivukxOklabToHpeXyz(vec3 lab) {
    float lPrime = lab.x + 0.3963377774 * lab.y + 0.2158037573 * lab.z;
    float mPrime = lab.x - 0.1055613458 * lab.y - 0.0638541728 * lab.z;
    float sPrime = lab.x - 0.0894841775 * lab.y - 1.2914855480 * lab.z;
    vec3 lms = vec3(lPrime * lPrime * lPrime, mPrime * mPrime * mPrime, sPrime * sPrime * sPrime);
    return vec3(
        dot(lms, vec3(1.18631848, -0.41749725, 0.16959721)),
        dot(lms, vec3(0.04142649, 0.96034478, -0.00249171)),
        dot(lms, vec3(-0.08434447, 0.06738549, 0.87641515))
    );
}

vec3 nivukxApexSignalShape(vec3 lab, float sceneLux, float kelvin) {
    float chroma = length(lab.yz);
    if (chroma <= 1e-6) return vec3(max(lab.x, 0.0), 0.0, 0.0);
    float hue = atan(lab.z, lab.y);
    float highlight = smoothstep(0.56, 1.06, lab.x);
    float deepShadow = 1.0 - smoothstep(0.04, 0.24, lab.x);
    float lowLux = 1.0 - smoothstep(12.0, 900.0, max(sceneLux, 0.0));
    float kelvinBias = clamp((6500.0 - clamp(kelvin, 1800.0, 12000.0)) / 6500.0, -0.45, 0.45);
    float chromaScale = clamp(1.0 - highlight * 0.42 - deepShadow * lowLux * 0.10 + kelvinBias * 0.035, 0.16, 1.04);
    float shapedChroma = chroma * chromaScale;
    return vec3(max(lab.x + deepShadow * lowLux * 0.018, 0.0), cos(hue) * shapedChroma, sin(hue) * shapedChroma);
}

float ndr10RationalBezierToneMap(float x, float sceneMin, float sceneMax) {
    float safeMin = max(sceneMin, 0.0001);
    float safeMax = max(sceneMax, safeMin + 0.0001);
    float contrastRatio = clamp(safeMax / safeMin, 1.0, 100000.0);
    float highDynamicRange = smoothstep(80.0, 8000.0, contrastRatio);
    float lowLight = 1.0 - smoothstep(0.24, 1.20, safeMax);
    float alpha = clamp(0.16 + highDynamicRange * 0.68, 0.16, 0.84);
    float beta = clamp(0.20 + lowLight * 0.58, 0.20, 0.78);
    float gamma = clamp(0.12 + highDynamicRange * 0.34, 0.12, 0.46);
    float normalized = clamp(x / max(safeMax, 1.0), 0.0, 1.0);
    float denominator = normalized + beta * max(1.0 - gamma * normalized, 0.05);
    return clamp(normalized * (1.0 + alpha * normalized) / max(denominator, 1e-6), 0.0, 1.0);
}

vec3 applySubtractiveFilmDensity(vec3 rgb) {
    float saturation = max(max(rgb.r, rgb.g), rgb.b) - min(min(rgb.r, rgb.g), rgb.b);
    vec3 kappa = vec3(0.34, 0.30, 0.26);
    return max(rgb * exp(-kappa * (vec3(1.0) - clamp(rgb, 0.0, 1.0)) * saturation), vec3(0.0));
}

vec3 highlightSaturationRolloff(vec3 rgb) {
    float peak = max(max(rgb.r, rgb.g), rgb.b);
    float rolloff = smoothstep(0.78, 1.0, peak);
    float y = dot(rgb, vec3(0.2627, 0.6780, 0.0593));
    return mix(rgb, vec3(y), rolloff);
}

vec3 encodeNLogV3Family(vec3 linear, float e, float a, float b, float d, float f) {
    float c = a * (d * e + f) / d;
    float g = a * e + b - c * log(d * e + f);
    vec3 x = clamp(linear, 0.0, 1.0);
    vec3 linearPart = a * x + vec3(b);
    vec3 logPart = c * log(d * x + vec3(f)) + vec3(g);
    return clamp(mix(logPart, linearPart, lessThan(x, vec3(e))), 0.0, 1.0);
}

vec3 applyEliteCinemaColorPipeline(vec3 rgb, bool nivukxVision, bool ndr10Plus, float sceneMin, float sceneMax) {
    vec3 source = max(rgb, vec3(0.0));
    vec3 xyz = neoWideGamutCompressXyPreserveY(NEO_WIDE_GAMUT_TO_XYZ * source);
    vec3 mapped = max(XYZ_TO_NEO_WIDE_GAMUT * xyz, vec3(0.0));
    if (ndr10Plus) {
        float y = max(dot(mapped, vec3(0.2627, 0.6780, 0.0593)), 1e-5);
        float mappedY = max(ndr10RationalBezierToneMap(y, sceneMin, sceneMax), 1e-5);
        mapped *= mappedY / y;
    }
    if (nivukxVision) {
        vec3 lab = hpeXyzToNivukxOklab(NEO_WIDE_GAMUT_TO_XYZ * mapped);
        vec3 shapedXyz = nivukxOklabToHpeXyz(nivukxApexSignalShape(lab, 420.0, 5500.0));
        mapped = max(XYZ_TO_NEO_WIDE_GAMUT * neoWideGamutCompressXyPreserveY(shapedXyz), vec3(0.0));
    }
    mapped = highlightSaturationRolloff(applySubtractiveFilmDensity(mapped));
    return clamp(mapped, 0.0, 1.08);
}

float c1ContinuousToneMap(float x, float knee, float alpha) {
    float k = clamp(knee, 0.18, 0.92);
    float a = max(alpha, 1e-4);
    if (x <= k) {
        return a * x;
    }
    float x0 = k - 0.22;
    float beta = a * max(k - x0, 1e-4);
    float gamma = a * k - beta * log(max(k - x0, 1e-4));
    return beta * log(max(x - x0, 1e-4)) + gamma;
}

vec3 c1ContinuousToneMap(vec3 color, float knee, float alpha) {
    return vec3(
        c1ContinuousToneMap(color.r, knee, alpha),
        c1ContinuousToneMap(color.g, knee, alpha),
        c1ContinuousToneMap(color.b, knee, alpha)
    );
}

vec3 hueLockedOklabGrade(vec3 color, float contrast, float saturation) {
    vec3 lab = linearAcesCgToOklab(max(color, vec3(0.0)));
    float chroma = length(lab.yz);
    float hue = atan(lab.z, lab.y);
    float contrastAmount = clamp(contrast, 0.05, 4.0);
    if (abs(contrastAmount - 1.0) > 0.0001) {
        float k = 4.0 * contrastAmount;
        float lo = 1.0 / (1.0 + exp(k * 0.5));
        float hi = 1.0 / (1.0 + exp(-k * 0.5));
        float raw = 1.0 / (1.0 + exp(-k * (lab.x - 0.5)));
        lab.x = clamp((raw - lo) / max(hi - lo, 1e-5), 0.0, 1.0);
    }
    float targetChroma = chroma * clamp(saturation, 0.0, 4.0);
    lab.y = cos(hue) * targetChroma;
    lab.z = sin(hue) * targetChroma;

    float low = 0.0;
    float high = 1.0;
    vec3 candidate = vec3(0.0);
    for (int i = 0; i < 10; i++) {
        float mid = (low + high) * 0.5;
        vec3 trialLab = vec3(lab.x, cos(hue) * targetChroma * mid, sin(hue) * targetChroma * mid);
        candidate = oklabToLinearAcesCg(trialLab);
        if (candidate.r >= 0.0 && candidate.g >= 0.0 && candidate.b >= 0.0 &&
            candidate.r <= 1.08 && candidate.g <= 1.08 && candidate.b <= 1.08) {
            low = mid;
        } else {
            high = mid;
        }
    }
    return clamp(oklabToLinearAcesCg(vec3(lab.x, cos(hue) * targetChroma * low, sin(hue) * targetChroma * low)), 0.0, 1.08);
}

vec3 oklabChromaBilateralFilter(vec2 uv, vec3 linear, float strength) {
    if (strength <= 0.001) return linear;
    ivec2 size = textureSize(uComputeEnhancedIntermediate, 0);
    vec2 texel = 1.0 / max(vec2(size), vec2(1.0));
    vec3 centerLab = linearAcesCgToOklab(linear);
    vec2 chromaSum = centerLab.yz * 2.0;
    float weightSum = 2.0;
    for (int y = -1; y <= 1; y++) {
        for (int x = -1; x <= 1; x++) {
            if (x == 0 && y == 0) continue;
            vec3 sampleLinear = srgbToLinear(texture(uComputeEnhancedIntermediate, uv + vec2(float(x), float(y)) * texel).rgb);
            vec3 sampleLab = linearAcesCgToOklab(sampleLinear);
            float lumaWeight = exp(-abs(sampleLab.x - centerLab.x) * 18.0);
            float chromaWeight = exp(-length(sampleLab.yz - centerLab.yz) * 12.0);
            float w = lumaWeight * chromaWeight;
            chromaSum += sampleLab.yz * w;
            weightSum += w;
        }
    }
    vec2 filteredChroma = chromaSum / max(weightSum, 1e-5);
    float shadowLock = 1.0 - smoothstep(0.22, 0.72, centerLab.x);
    centerLab.yz = mix(centerLab.yz, filteredChroma, clamp(strength * (0.55 + shadowLock * 0.40), 0.0, 0.995));
    return clamp(oklabToLinearAcesCg(centerLab), 0.0, 1.08);
}

vec3 oklabChromaSpeckleGuard(vec2 uv, vec3 linear, float strength) {
    if (strength <= 0.001) return linear;
    ivec2 size = textureSize(uComputeEnhancedIntermediate, 0);
    vec2 texel = 1.0 / max(vec2(size), vec2(1.0));
    vec3 centerLab = linearAcesCgToOklab(max(linear, vec3(0.0)));
    float centerChroma = length(centerLab.yz);
    if (centerChroma <= 0.006) return linear;

    float meanChroma = 0.0;
    float count = 0.0;
    for (int y = -2; y <= 2; y++) {
        for (int x = -2; x <= 2; x++) {
            vec3 sampleLinear = srgbToLinear(texture(uComputeEnhancedIntermediate, uv + vec2(float(x), float(y)) * texel).rgb);
            vec3 sampleLab = linearAcesCgToOklab(max(sampleLinear, vec3(0.0)));
            meanChroma += length(sampleLab.yz);
            count += 1.0;
        }
    }
    meanChroma /= max(count, 1.0);
    if (centerChroma <= meanChroma * 1.10) return linear;

    float variance = 0.0;
    for (int y = -2; y <= 2; y++) {
        for (int x = -2; x <= 2; x++) {
            vec3 sampleLinear = srgbToLinear(texture(uComputeEnhancedIntermediate, uv + vec2(float(x), float(y)) * texel).rgb);
            vec3 sampleLab = linearAcesCgToOklab(max(sampleLinear, vec3(0.0)));
            float d = length(sampleLab.yz) - meanChroma;
            variance += d * d;
        }
    }
    variance = max(variance / max(count, 1.0), 1e-5);
    float deviation = centerChroma - meanChroma;
    float normalizedOutlier = clamp((deviation * deviation) / variance, 0.0, 32.0);
    float darkWeight = 1.0 - smoothstep(0.18, 0.74, centerLab.x);
    float outlierWeight = smoothstep(1.4, 6.0, normalizedOutlier);
    float lambdaSuppress = 0.08 + clamp(strength, 0.0, 1.0) * 0.16;
    float chromaScale = clamp(
        exp(-normalizedOutlier * lambdaSuppress * (0.42 + darkWeight * 0.58) * outlierWeight),
        0.35,
        1.0
    );
    centerLab.yz *= chromaScale;
    return clamp(oklabToLinearAcesCg(centerLab), 0.0, 1.08);
}

float gaborOrientationResponse(vec2 uv, vec2 direction, float frequency) {
    ivec2 size = textureSize(uComputeEnhancedIntermediate, 0);
    vec2 texel = 1.0 / max(vec2(size), vec2(1.0));
    float response = 0.0;
    for (int i = -2; i <= 2; i++) {
        vec2 offset = direction * texel * float(i);
        float sampleLuma = luminance(srgbToLinear(texture(uComputeEnhancedIntermediate, uv + offset).rgb));
        float phase = float(i) * frequency * 6.2831853;
        response += sampleLuma * cos(phase) * exp(-float(i * i) * 0.28);
    }
    return abs(response) * 0.2;
}

vec3 bartenHvsContrastEnhancement(vec2 uv, vec3 linear, float strength) {
    if (strength <= 0.001) return linear;
    float luma = max(luminance(linear), 0.001);
    float viewingLuminance = mix(48.0, 203.0, smoothstep(0.02, 0.92, luma));
    float pupilSize = clamp(5.0 - log2(viewingLuminance + 1.0) * 0.42, 2.0, 6.0);
    float bartenSensitivity = smoothstep(0.018, 0.28, luma) * (1.0 / (1.0 + pupilSize * 0.12));
    float horizontal = gaborOrientationResponse(uv, vec2(1.0, 0.0), 0.42);
    float vertical = gaborOrientationResponse(uv, vec2(0.0, 1.0), 0.42);
    float diagonal = gaborOrientationResponse(uv, normalize(vec2(1.0, 1.0)), 0.36);
    float gaborMicroContrast = max(max(horizontal, vertical), diagonal);
    vec3 local = weberFechnerLocalAdaptation(uv, linear, strength * 0.42);
    vec3 detail = local - linear;
    float bartenGain = clamp(bartenSensitivity * gaborMicroContrast * strength * 0.35, 0.0, 0.055);
    return clamp(linear + detail * bartenGain / max(length(detail), 0.015), 0.0, 1.08);
}

vec3 fitzHughNagumoChemicalHalation(vec2 uv, vec3 linear, float strength) {
    if (strength <= 0.001) return linear;
    vec3 glowSeed = warmHighlightHalation(uv, linear, 1.0) - linear;
    float activator = smoothstep(0.78, 1.0, luminance(linear + glowSeed));
    float inhibitor = smoothstep(0.12, 0.62, luminance(linear));
    for (int step = 0; step < 4; step++) {
        float diffusion = activator - (activator * activator * activator) * 0.3333 - inhibitor + 0.045;
        inhibitor += 0.16 * (activator + 0.62 - 0.82 * inhibitor);
        activator = clamp(activator + diffusion * 0.22, 0.0, 1.0);
    }
    vec3 chemicalWarmth = vec3(1.0, 0.36, 0.14) * activator * strength * 0.018;
    return clamp(linear + chemicalWarmth * (1.0 - smoothstep(0.86, 1.0, luminance(linear))), 0.0, 1.10);
}

vec3 planckBlueNoiseJndGrain(vec2 uv, vec3 linear, float strength, float phase) {
    if (strength <= 0.001) return linear;
    float luma = clamp(luminance(linear), 0.0, 1.0);
    float jndThreshold = mix(0.0012, 0.0048, sqrt(luma)) * (1.0 - smoothstep(0.86, 1.0, luma));
    float blue = blueNoiseScalar(uv * 1.6180339, phase + luma * 3.17) - 0.5;
    float shaped = blue * jndThreshold * strength;
    return clamp(linear + vec3(shaped), 0.0, 1.08);
}

float gaborLindebergKernel(vec2 sampleOffset, float orientation, float scale, float wavelength) {
    float c = cos(orientation);
    float s = sin(orientation);
    float xPrime = sampleOffset.x * c + sampleOffset.y * s;
    float yPrime = -sampleOffset.x * s + sampleOffset.y * c;
    float sigma = max(scale, 0.35);
    float gaussian = exp(-(xPrime * xPrime + 0.55 * yPrime * yPrime) / max(2.0 * sigma * sigma, 1e-5));
    float carrier = cos(6.2831853 * xPrime / max(wavelength, 0.35));
    return gaussian * carrier;
}

float lindebergScaleSpaceResponse(vec2 uv, vec3 linear, float orientation, float scale) {
    ivec2 size = textureSize(uComputeEnhancedIntermediate, 0);
    vec2 texel = 1.0 / max(vec2(size), vec2(1.0));
    float centerLuma = luminance(linear);
    float response = 0.0;
    float weightSum = 0.0;
    for (int y = -2; y <= 2; y++) {
        for (int x = -2; x <= 2; x++) {
            vec2 offset = vec2(float(x), float(y));
            vec3 sampleLinear = srgbToLinear(texture(uComputeEnhancedIntermediate, uv + offset * texel * scale).rgb);
            float kernel = gaborLindebergKernel(offset, orientation, scale, 2.2 + scale);
            response += (luminance(sampleLinear) - centerLuma) * kernel;
            weightSum += abs(kernel);
        }
    }
    return response / max(weightSum, 1e-5);
}

vec3 gaborLindebergHvsContrastEnhancement(vec2 uv, vec3 linear, float strength) {
    if (strength <= 0.001) return linear;
    float luma = max(luminance(linear), 0.001);
    float retinalAdaptation = strength / (luma + 0.12);
    float r0 = lindebergScaleSpaceResponse(uv, linear, 0.0, 1.0);
    float r1 = lindebergScaleSpaceResponse(uv, linear, 1.5707963, 1.35);
    float r2 = lindebergScaleSpaceResponse(uv, linear, 0.7853982, 1.75);
    float lindebergMicroFrequency = (r0 + r1 + r2) / 3.0;
    float lindebergGain = clamp(retinalAdaptation * smoothstep(0.002, 0.055, abs(lindebergMicroFrequency)), 0.0, 0.065);
    float haloGuard = 1.0 - smoothstep(0.78, 1.0, luma);
    float lumaLift = clamp(lindebergMicroFrequency * lindebergGain * haloGuard, -0.018, 0.022);
    return clamp(linear + vec3(lumaLift), 0.0, 1.08);
}

float bt2100Luma(vec3 color) {
    return dot(color, vec3(0.2627, 0.6780, 0.0593));
}

float neoHighEndKneeCurve(float luma, float knee, float shoulder, float strength) {
    float x = max(luma, 0.0);
    float safeKnee = clamp(knee, 0.18, 0.92);
    float safeShoulder = clamp(shoulder, safeKnee + 0.02, 1.0);
    float safeStrength = max(strength, 0.05);
    if (x <= safeKnee) return x;

    float span = max(safeShoulder - safeKnee, 1e-5);
    float normalized = max((x - safeKnee) / span, 0.0);
    // C1 Reinhard highlight shoulder: unit slope at the knee join (no contrast
    // kick / Mach band), monotone, and COMPRESSING (output <= input). The prior
    // tanh(strength*n)/tanh(strength) had slope strength/tanh(strength) (~2.3) at
    // the knee and EXPANDED upper-midtones (output exceeded input — audit rank 3).
    // Gentle rolloff maps the shoulder just below itself into highlight headroom.
    float kneeShoulderA = clamp((safeStrength - 1.0) * 0.14, 0.02, 0.35);
    float normalizedMapped = normalized / (1.0 + kneeShoulderA * normalized);
    return clamp(safeKnee + span * normalizedMapped, 0.0, safeShoulder);
}

vec4 resolveNeoHighEndKneeParams(
    bool nivukxVision,
    bool ndr10Plus,
    bool pqLike,
    bool hlgLike,
    bool logLike,
    bool rawLike,
    float highlightCompression
) {
    vec4 params = vec4(0.72, 1.00, 1.36, 0.42);
    if (nivukxVision) {
        params = vec4(0.54, 1.00, 2.25, 1.00);
    } else if (ndr10Plus || pqLike) {
        params = vec4(0.58, 1.00, 2.05, 1.00);
    } else if (hlgLike) {
        params = vec4(0.64, 1.00, 1.70, 0.74);
    } else if (logLike) {
        params = vec4(0.68, 1.00, 1.52, 0.56);
    } else if (rawLike) {
        params = vec4(0.66, 1.00, 1.58, 0.52);
    }

    float userCompression = clamp(highlightCompression, 0.0, 1.0);
    params.x = clamp(params.x - userCompression * mix(0.035, 0.085, params.w), 0.42, 0.78);
    params.z = clamp(params.z + userCompression * mix(0.28, 0.72, params.w), 1.12, 2.90);
    return params;
}

vec3 applyNeoHighEndKneeCurve(vec3 color, vec4 kneeParams) {
    vec3 source = max(color, vec3(0.0));
    float sourceY = max(bt2100Luma(source), 1e-6);
    float mappedY = neoHighEndKneeCurve(sourceY, kneeParams.x, kneeParams.y, kneeParams.z);
    vec3 mapped = source * (mappedY / sourceY);
    float highlightWeight = smoothstep(kneeParams.x, kneeParams.y, sourceY);
    float mappedLuma = max(bt2100Luma(mapped), 1e-6);
    float chromaKeep = 1.0 - clamp(highlightWeight * kneeParams.w * 0.12, 0.0, 0.22);
    mapped = vec3(mappedLuma) + (mapped - vec3(mappedLuma)) * chromaKeep;
    return clamp(mapped, 0.0, kneeParams.y);
}

vec3 colorPreservingShoulder(vec3 color, float knee, float shoulderStrength) {
    vec3 source = max(color, vec3(0.0));
    float y = max(luminance(source), 1e-6);
    float safeKnee = clamp(knee, 0.18, 0.82);
    float normalizedShoulder = max(y - safeKnee, 0.0) / max(1.0 - safeKnee, 1e-4);
    float compressedShoulder = normalizedShoulder / (1.0 + shoulderStrength * normalizedShoulder);
    float mappedY = y <= safeKnee
        ? y
        : safeKnee + (1.0 - safeKnee) * compressedShoulder;
    vec3 mapped = source * (mappedY / y);
    float highlightProtect = smoothstep(safeKnee, 1.0, y);
    float desaturation = clamp(highlightProtect * shoulderStrength * 0.055, 0.0, 0.16);
    return clamp(mix(mapped, vec3(luminance(mapped)), desaturation), 0.0, 1.0);
}

vec3 bt2100HuePreservingDisplayMap(vec3 linear, bool pqMonitor, float hdrStrength, float highlightCompression) {
    vec3 source = max(linear, vec3(0.0));
    float sourceY = max(bt2100Luma(source), 1e-6);
    float knee = pqMonitor ? 0.72 : 0.78;
    float compression = mix(1.60, 2.45, clamp(highlightCompression + hdrStrength * 0.18, 0.0, 1.0));
    float mappedY = sourceY <= knee
        ? sourceY
        : knee + (1.0 - knee) * (1.0 - exp(-compression * (sourceY - knee) / max(1.0 - knee, 1e-4)));
    vec3 mapped = source * (mappedY / sourceY);
    return colorPreservingShoulder(mapped, pqMonitor ? 0.70 : 0.74, 0.38 + highlightCompression * 0.48);
}

vec3 cinematicTonemap(vec3 color) {
    return colorPreservingShoulder(color, 0.48, 1.28);
}

// Luma-preserving preview detail refinement
vec3 highPrecisionSharpen(vec3 color, vec3 linear, float strength) {
    if (strength <= 0.01) return color;
    vec3 luma = vec3(dot(linear, vec3(0.2126, 0.7152, 0.0722)));
    vec3 detail = linear - luma;
    return color + detail * strength * 20.0;
}

// Authoritative N-LOG v5 APEX-LOG family; coefficients match NeoLogCurve/NLogMath.
const float N_LOG_SCENE_GRAY_18 = 0.18;
const float N_LOG_GRAY_CODE_18 = 0.50;

float nLogV5QuinticToe(float t, vec4 c) {
    float t2 = t * t;
    float t3 = t2 * t;
    float t4 = t3 * t;
    float t5 = t4 * t;
    return c.x * t + c.y * t3 + c.z * t4 + c.w * t5;
}

float nLogV5QuinticToeDerivative(float t, vec4 c) {
    float t2 = t * t;
    float t3 = t2 * t;
    float t4 = t3 * t;
    return c.x + 3.0 * c.y * t2 + 4.0 * c.z * t3 + 5.0 * c.w * t4;
}

float decodeNLogV5Toe(float encoded, float toeCut, float toeHeight, vec4 toeCoefficients) {
    float target = clamp(encoded, 0.0, toeHeight);
    float low = 0.0;
    float high = 1.0;
    float t = clamp(target / max(toeHeight, 1e-8), 0.0, 1.0);
    for (int i = 0; i < 24; ++i) {
        float value = nLogV5QuinticToe(t, toeCoefficients);
        if (value < target) {
            low = t;
        } else {
            high = t;
        }
        float derivative = nLogV5QuinticToeDerivative(t, toeCoefficients);
        float candidate = derivative > 1e-8 ? t - (value - target) / derivative : -1.0;
        t = (candidate > low && candidate < high) ? candidate : (low + high) * 0.5;
    }
    return ((low + high) * 0.5) * toeCut;
}

vec3 encodeNLogFamily(
    vec3 linear,
    float toeCut,
    float logDensity,
    float toeHeight,
    float logScale,
    vec4 toeCoefficients,
    float xMax
) {
    vec3 x = clamp(linear, 0.0, xMax);
    vec3 logPart = vec3(toeHeight) + vec3(logScale) *
        log(vec3(1.0) + vec3(logDensity) * (x - vec3(toeCut)));
    vec3 t = clamp(x / toeCut, 0.0, 1.0);
    vec3 toePart = vec3(
        nLogV5QuinticToe(t.r, toeCoefficients),
        nLogV5QuinticToe(t.g, toeCoefficients),
        nLogV5QuinticToe(t.b, toeCoefficients)
    );
    return clamp(mix(logPart, toePart, lessThan(x, vec3(toeCut))), 0.0, 1.0);
}

vec3 decodeNLogFamily(
    vec3 encoded,
    float toeCut,
    float logDensity,
    float toeHeight,
    float logScale,
    vec4 toeCoefficients,
    float xMax
) {
    vec3 y = clamp(encoded, 0.0, 1.0);
    vec3 body = vec3(toeCut) +
        (exp((y - vec3(toeHeight)) / vec3(logScale)) - vec3(1.0)) / vec3(logDensity);
    vec3 toe = vec3(
        decodeNLogV5Toe(y.r, toeCut, toeHeight, toeCoefficients),
        decodeNLogV5Toe(y.g, toeCut, toeHeight, toeCoefficients),
        decodeNLogV5Toe(y.b, toeCut, toeHeight, toeCoefficients)
    );
    return clamp(mix(body, toe, lessThan(y, vec3(toeHeight))), 0.0, xMax);
}

float normalizeLogToneDeliveryMode(float toneDeliveryMode) {
    return (toneDeliveryMode > 29.5 && toneDeliveryMode < 33.5)
        ? toneDeliveryMode - 20.0
        : toneDeliveryMode;
}

vec3 encodeLogByMode(vec3 linear, float toneDeliveryMode) {
    if (toneDeliveryMode > 10.5 && toneDeliveryMode < 11.5) {
        return encodeNLogFamily(linear, 0.004, 70.0, 0.040000, 0.177657, vec4(0.040000, -0.045940, 0.082136, -0.036196), 3.164296);
    }
    if (toneDeliveryMode > 11.5 && toneDeliveryMode < 12.5) {
        return encodeNLogFamily(linear, 0.005, 260.0, 0.060000, 0.114598, vec4(0.060000, -0.452743, 0.816510, -0.363766), 14.041075);
    }
    if (toneDeliveryMode > 12.5 && toneDeliveryMode < 13.5) {
        return encodeNLogFamily(linear, 0.008, 110.0, 0.080000, 0.140385, vec4(0.080000, -0.228513, 0.413487, -0.184974), 6.377367);
    }
    return encodeNLogFamily(linear, 0.010, 120.0, 0.080000, 0.137099, vec4(0.080000, -0.436788, 0.789056, -0.352269), 6.843034);
}

vec3 decodeLogByMode(vec3 encoded, float toneDeliveryMode) {
    if (toneDeliveryMode > 10.5 && toneDeliveryMode < 11.5) {
        return decodeNLogFamily(encoded, 0.004, 70.0, 0.040000, 0.177657, vec4(0.040000, -0.045940, 0.082136, -0.036196), 3.164296);
    }
    if (toneDeliveryMode > 11.5 && toneDeliveryMode < 12.5) {
        return decodeNLogFamily(encoded, 0.005, 260.0, 0.060000, 0.114598, vec4(0.060000, -0.452743, 0.816510, -0.363766), 14.041075);
    }
    if (toneDeliveryMode > 12.5 && toneDeliveryMode < 13.5) {
        return decodeNLogFamily(encoded, 0.008, 110.0, 0.080000, 0.140385, vec4(0.080000, -0.228513, 0.413487, -0.184974), 6.377367);
    }
    return decodeNLogFamily(encoded, 0.010, 120.0, 0.080000, 0.137099, vec4(0.080000, -0.436788, 0.789056, -0.352269), 6.843034);
}

// Universal LOG Preview Look - Fast & Impactful
vec3 applyLogPreview(vec3 linear, float strength, float shadowBias, float highlightBias) {
    if (strength <= 0.01) return linear;
    const float k = 22.0; // Boosted for WOW factor
    const float logK = log2(1.0 + k);
    vec3 encoded = log2(1.0 + max(linear, vec3(0.0)) * k) / logK;
    float shadowLift = shadowBias * 0.12;
    encoded += vec3(shadowLift) * (1.0 - encoded);
    return mix(linear, clamp(encoded, 0.0, 1.0), strength);
}

// Advanced Highlight Roll-off (True-Logarithmic)
vec3 highlightRolloff(vec3 color, float strength, float biasScale) {
    float luma = luminance(color);
    float knee = 0.55; // Lower knee for more latitude
    if (luma > knee) {
        float t = clamp((luma - knee) / (1.0 - knee), 0.0, 1.0);
        float softT = t * t * t * (t * (t * 6.0 - 15.0) + 10.0); // Smoother quintic step
        float scale = (knee + (1.0 - knee) * (1.0 - pow(1.0 - softT, 4.0 + strength * biasScale * 12.0))) / max(luma, 0.0001);
        vec3 compressed = c1ContinuousToneMap(color * scale, 0.72, 1.0);
        return mix(color, compressed, strength * biasScale);
    }
    return color;
}

// Film Shadow Processing
vec3 shadowPreserve(vec3 color, float lift, float biasScale) {
    float luma = luminance(color);
    float shadowBlend = pow(1.0 - clamp(luma * 4.0, 0.0, 1.0), 3.0);
    return color + vec3(lift * biasScale * 0.22) * shadowBlend;
}

vec3 applyContrastAndSaturation(vec3 color, float contrast, float saturation) {
    return clamp(hueLockedOklabGrade(color, contrast, saturation), 0.0, 1.0);
}

vec3 preGradeChromaNoiseGuard(vec3 color, float toneDeliveryMode) {
    float luma = luminance(color);
    vec3 neutral = vec3(luma);
    float chroma = length(color - neutral);
    float lowSignal = 1.0 - smoothstep(0.13, 0.48, luma);
    float midSignal = 1.0 - smoothstep(0.42, 0.82, luma);
    float speckle = smoothstep(0.004, 0.090, chroma);
    bool hdrOrRaw = (toneDeliveryMode > 1.5 && toneDeliveryMode < 8.5);
    bool logLike = toneDeliveryMode > 9.5 && toneDeliveryMode < 39.5;
    float modeBoost = (hdrOrRaw || logLike) ? 1.25 : 1.05;
    float highlightProtect = 1.0 - smoothstep(0.68, 0.95, luma) * 0.65;
    float blend = clamp(
        modeBoost * speckle * highlightProtect * (lowSignal * 0.72 + midSignal * 0.28),
        0.0,
        0.98
    );
    return clamp(mix(color, neutral, blend), 0.0, 1.0);
}

// Constant-cost black-level calibration for the native Vulkan monitor path. The active GLES
// compositors use spatial consensus; this fallback already has downstream edge-aware spatial and
// temporal cleanup, so here we remove only sub-noise-floor chroma, including a coherent sensor-wide
// black offset. Luminance is reconstructed exactly, recoverable saturated dark colour is protected,
// and the rule is independent of video/detail profile.
vec3 calibrateNearBlackChroma(vec3 color) {
    float luma = luminance(color);
    vec3 chroma = color - vec3(luma);
    float deepShadow = 1.0 - smoothstep(0.012, 0.065, luma);
    // Without a spatial consensus in this fallback, the joint luma/chroma confidence is conservative:
    // it reaches coherent black-level bias but fades before recoverable saturated shadow colour.
    float belowColorConfidence = 1.0 - smoothstep(0.012, 0.070, length(chroma));
    float neutralize = clamp(deepShadow * belowColorConfidence * 0.96, 0.0, 0.995);
    return clamp(vec3(luma) + chroma * (1.0 - neutralize), 0.0, 1.0);
}

// Upgraded High-End Elite Chroma Denoise
vec3 chromaDenoise(vec3 color, float strength, bool boundedNormalProSdrQuality) {
    if (strength <= 0.01) return color;
    float denoiseAuthority = normalProSdrDenoiseAuthority(strength, boundedNormalProSdrQuality);
    bool strictDenoise = strictDenoiseAuthorityActive(denoiseAuthority, boundedNormalProSdrQuality);
    float luma = luminance(color);
    vec3 chroma = color - vec3(luma);
    float lowMidBias = 1.0 - smoothstep(0.18, 0.85, luma);
    float texturelessRgbGrain = strictTexturelessRgbGrainGate(
        luma,
        length(chroma),
        denoiseAuthority,
        boundedNormalProSdrQuality
    );
    float strictGateScale = boundedNormalProSdrQuality ? 1.0 : 10.0;
    float chromaOnlyStrength = clamp(
        (boundedNormalProSdrQuality ? denoiseAuthority : strength) * mix(0.78, 0.998, lowMidBias) +
            strictNeutralNoiseGate(luma, length(chroma), denoiseAuthority, boundedNormalProSdrQuality) * strictGateScale +
            strictRgbSpeckleGate(luma, length(chroma), denoiseAuthority, boundedNormalProSdrQuality) * strictGateScale +
            texturelessRgbGrain * strictGateScale,
        0.0,
        strictDenoise ? 1.0 : 0.995
    );
    chroma *= (1.0 - chromaOnlyStrength);
    return ictcpChromaDenoise(
        clamp(vec3(luma) + chroma, 0.0, 1.0),
        strength,
        boundedNormalProSdrQuality
    );
}

// Upgraded High-End Elite Speckle Suppress
vec3 rgbSpeckleSuppress(vec3 color, float strength, bool boundedNormalProSdrQuality) {
    if (strength <= 0.01) return color;
    float denoiseAuthority = normalProSdrDenoiseAuthority(strength, boundedNormalProSdrQuality);
    bool strictDenoise = strictDenoiseAuthorityActive(denoiseAuthority, boundedNormalProSdrQuality);
    float unitStrength = boundedNormalProSdrQuality
        ? denoiseAuthority
        : (denoiseAuthority > 1.0 ? 1.0 : clamp(strength, 0.0, 1.0));
    float luma = luminance(color);
    vec3 neutral = vec3(luma);
    float chromaMagnitude = length(color - neutral);
    float darkWeight = 1.0 - smoothstep(0.12, 0.72, luma);
    float highFrequencyColorNoise = smoothstep(0.001, 0.055, chromaMagnitude) *
        (1.0 - smoothstep(0.18, 0.65, chromaMagnitude));
    float speckleWeight = max(smoothstep(0.002, 0.095, chromaMagnitude), highFrequencyColorNoise);
    float highlightProtect = 1.0 - smoothstep(0.82, 0.98, luma);
    float edgeStableBlend = clamp(darkWeight * 0.68 + highFrequencyColorNoise * 0.45, 0.0, 1.0);
    float texturelessRgbGrain = strictTexturelessRgbGrainGate(
        luma,
        chromaMagnitude,
        denoiseAuthority,
        boundedNormalProSdrQuality
    );
    float strictGateScale = boundedNormalProSdrQuality ? 1.0 : 10.0;
    float blend = clamp(
        unitStrength * edgeStableBlend * speckleWeight * highlightProtect +
            strictNeutralNoiseGate(luma, chromaMagnitude, denoiseAuthority, boundedNormalProSdrQuality) * strictGateScale +
            strictRgbSpeckleGate(luma, chromaMagnitude, denoiseAuthority, boundedNormalProSdrQuality) * strictGateScale +
            texturelessRgbGrain * strictGateScale,
        0.0,
        strictDenoise ? 1.0 : 0.98
    );
    return mix(color, neutral, blend);
}

// Upgraded High-End Elite Dark Noise Floor Suppress
vec3 darkNoiseFloorSuppress(vec3 color, float strength, bool boundedNormalProSdrQuality) {
    if (strength <= 0.01) return color;
    float denoiseAuthority = normalProSdrDenoiseAuthority(strength, boundedNormalProSdrQuality);
    bool strictDenoise = strictDenoiseAuthorityActive(denoiseAuthority, boundedNormalProSdrQuality);
    float unitStrength = boundedNormalProSdrQuality
        ? denoiseAuthority
        : (denoiseAuthority > 1.0 ? 1.0 : clamp(strength, 0.0, 1.0));
    float luma = luminance(color);
    vec3 neutral = vec3(luma);
    float darkWeight = 1.0 - smoothstep(0.08, 0.65, luma);
    float chromaWeight = smoothstep(0.001, 0.075, length(color - neutral));
    float texturelessRgbGrain = strictTexturelessRgbGrainGate(
        luma,
        length(color - neutral),
        denoiseAuthority,
        boundedNormalProSdrQuality
    );
    float strictGateScale = boundedNormalProSdrQuality ? 1.0 : 10.0;
    float blend = clamp(
        unitStrength * darkWeight * (0.55 + chromaWeight * 0.45) +
            strictNeutralNoiseGate(luma, length(color - neutral), denoiseAuthority, boundedNormalProSdrQuality) * strictGateScale +
            strictRgbSpeckleGate(luma, length(color - neutral), denoiseAuthority, boundedNormalProSdrQuality) * strictGateScale +
            texturelessRgbGrain * strictGateScale,
        0.0,
        strictDenoise ? 1.0 : 0.98
    );
    return mix(color, neutral, blend);
}

float skinToneWeight(vec3 linearColor) {
    vec3 display = clamp(linearToSrgb(clamp(linearColor, 0.0, 1.0)), 0.0, 1.0);
    float r = display.r;
    float g = display.g;
    float b = display.b;
    float rg = r - g;
    float gb = g - b;
    float luma = luminance(linearColor);
    // The NDR/NIVUKX tone operator receives Rec.709-primary linear and lifts warm skin's red
    // channel, so real skin arrives at this detector already at a high r-g. The old upper edges
    // (0.34..0.68 rg, 0.30..0.62 gb) rolled skin detection to 0 exactly when skin was most red, which
    // disengaged every skin red-cap and let ndrRedChannelOverflowGuard reclassify warm skin as a
    // "red object" (ceiling g*1.48) -> sunburnt/pure-red skin. Widen the warm-skin window so strongly
    // warm skin stays detected and keeps the g*1.14 skin cap. redDominance (g-b) still gates out
    // genuine pure reds (g~=b), so this does not desaturate real red objects.
    float warmHue = smoothstep(0.012, 0.15, rg) * (1.0 - smoothstep(0.54, 1.00, rg));
    float redDominance = smoothstep(-0.015, 0.13, gb) * (1.0 - smoothstep(0.48, 0.90, gb));
    float exposureWindow = smoothstep(0.08, 0.26, luma) * (1.0 - smoothstep(1.05, 1.45, luma));
    float saturationWindow = smoothstep(0.035, 0.34, max(r, max(g, b)) - min(r, min(g, b)));
    return clamp(warmHue * redDominance * exposureWindow * saturationWindow, 0.0, 1.0);
}

vec3 bt2100SkinToneRedGuard(vec3 linearColor, bool hlgLike, bool pqLike) {
    if (!(hlgLike || pqLike)) return linearColor;
    vec3 guarded = clamp(linearColor, 0.0, 1.0);
    float y = clamp(bt2100Luma(guarded), 0.0, 1.0);
    float redGreenRatio = guarded.r / max(guarded.g, 1e-5);
    float redExcess = smoothstep(1.24, 1.66, redGreenRatio);
    float skin = skinToneWeight(guarded);
    float midtoneSkinWindow = smoothstep(0.08, 0.30, y) * (1.0 - smoothstep(0.82, 0.98, y));
    float guardStrength = clamp(skin * redExcess * midtoneSkinWindow * (pqLike ? 0.38 : 0.26), 0.0, 0.38);
    if (guardStrength <= 0.0001) return guarded;

    vec3 adjusted = guarded;
    float targetRed = min(adjusted.r, adjusted.g * 1.30);
    adjusted.r = mix(adjusted.r, targetRed, guardStrength);
    adjusted.b = mix(adjusted.b, max(adjusted.b, adjusted.g * 0.78), guardStrength * 0.52);

    float adjustedY = max(bt2100Luma(adjusted), 1e-5);
    adjusted *= y / adjustedY;
    return clamp(adjusted, 0.0, 1.0);
}

// HDR Delivery Logic (HLG/PQ)
vec3 bt2100HlgOetf(vec3 linear) {
    const float a = 0.17883277;
    const float b = 0.28466892;
    const float c = 0.55991073;
    vec3 res;
    for(int i=0; i<3; i++) {
        if(linear[i] <= 1.0/12.0) res[i] = sqrt(3.0 * linear[i]);
        else res[i] = a * log(12.0 * linear[i] - b) + c;
    }
    return res;
}

vec3 bt2100HlgInverseOetf(vec3 hlg) {
    const float a = 0.17883277;
    const float b = 0.28466892;
    const float c = 0.55991073;
    vec3 res;
    for(int i=0; i<3; i++) {
        if(hlg[i] <= 0.5) res[i] = hlg[i] * hlg[i] / 3.0;
        else res[i] = (exp((hlg[i] - c) / a) + b) / 12.0;
    }
    return res;
}

vec3 bt2100PqInverseEotf(vec3 linear) {
    const float m1 = 0.1593017578125;
    const float m2 = 78.84375;
    const float c1 = 0.8359375;
    const float c2 = 18.8515625;
    const float c3 = 18.6875;
    vec3 lm1 = pow(clamp(linear, 0.0, 1.0), vec3(m1));
    return pow((c1 + c2 * lm1) / (1.0 + c3 * lm1), vec3(m2));
}

vec3 bt2100PqEotf(vec3 pq) {
    const float m1 = 0.1593017578125;
    const float m2 = 78.84375;
    const float c1 = 0.8359375;
    const float c2 = 18.8515625;
    const float c3 = 18.6875;
    vec3 lm = pow(clamp(pq, 0.0, 1.0), vec3(1.0 / m2));
    vec3 linear = pow(max(lm - c1, 0.0) / (c2 - c3 * lm), vec3(1.0 / m1));
    return linear;
}

// ── Neo NDR/NIVUKX nit-accurate PQ delivery ───────────────────────────────
// Canonical NeoProCamX master-peak nits per profile (match HdrImagingMath /
// NdrToneOperator). The NDR/NIVUKX/apex tone-mapped delivery signal is
// normalized so 1.0 == the mode's MASTER PEAK, not 10000 nits. ST2084 expects
// 1.0 == 10000 nits, so the signal must be scaled by peak/10000 before encode.
// Feeding the raw [0,1] tone signal straight into bt2100PqInverseEotf shipped
// diffuse content at thousands of nits (diffuse white ~9450, ~5.6 stops too
// bright) — the PQ-nit-scale defect this corrects.
const float NDR10_TARGET_PEAK_NITS = 5000.0;
const float NIVUKX_TARGET_PEAK_NITS = 10000.0;
// Python-calibrated NDR-NIVUKX-PY-CAL-v4 packet. These values are intentionally
// duplicated as named shader constants and host-locked against NdrToneOperator;
// the live GPU path must never silently fall back to the legacy 0.92/0.88 curves.
const float NDR10_SIGNAL_GAMMA = 0.859937;
const float NDR10_TOE_WIDTH = 0.238882;
const float NDR10_TOE_AMOUNT = 0.024780;
const float NDR10_HIGHLIGHT_DESAT = 0.149718;
const float NDR10_SHADOW_MIDTONE_LIFT = 0.167837;
const float NDR10_MIDTONE_CONTRAST = 0.100943;
const float NDR10_CHROMA_BOOST = 0.038361;
const float NDR10_SKY_CHROMA_BONUS = 0.062392;
const float NDR10_LOCAL_DETAIL_STRENGTH = 0.400000;
const float NIVUKX_SIGNAL_GAMMA = 0.800034;
const float NIVUKX_TOE_WIDTH = 0.289876;
const float NIVUKX_TOE_AMOUNT = 0.031033;
const float NIVUKX_HIGHLIGHT_DESAT = 0.128953;
const float NIVUKX_SHADOW_MIDTONE_LIFT = 0.232156;
const float NIVUKX_MIDTONE_CONTRAST = 0.174135;
const float NIVUKX_CHROMA_BOOST = 0.091565;
const float NIVUKX_SKY_CHROMA_BONUS = 0.080000;
const float NIVUKX_LOCAL_DETAIL_STRENGTH = 0.600000;
// Project NDR-NIVUKX-PY-CAL-v4: presentation-only linear value for calibrated sRGB 0.80.
// The FP16 scene image, canonical tone signal, and PQ master retain their full range.
const float NDR_SDR_MONITOR_DIFFUSE_LINEAR = 0.60382734;

float ndrSignalToDisplayNits(float signalValue, float peakNits) {
    float signal = max(signalValue, 0.0);
    float diffuse = HELIOS_DIFFUSE_WHITE_NITS;
    float rawNits = signal * diffuse;
    if (rawNits <= diffuse) return rawNits;
    float peak = clamp(peakNits, diffuse, 10000.0);
    float range = max(peak - diffuse, HELIOS_EPSILON);
    return min(
        diffuse + range * (1.0 - exp(-(rawNits - diffuse) / range)),
        peak
    );
}

vec3 ndrDeliveryPqEncode(vec3 toneSignal, float peakNits) {
    float peak = clamp(peakNits, HELIOS_DIFFUSE_WHITE_NITS, 10000.0);
    vec3 signal = max(toneSignal, vec3(0.0));
    float signalY = max(bt2100Luma(signal), HELIOS_EPSILON);
    float y = ndrSignalToDisplayNits(signalY, peak);
    vec3 rgbNits = signal * (y / signalY);
    float hi = max(max(rgbNits.r, rgbNits.g), rgbNits.b);
    if (hi > peak && hi > y + HELIOS_EPSILON) {
        float chromaScale = clamp((peak - y) / (hi - y), 0.0, 1.0);
        rgbNits = vec3(y) + (rgbNits - vec3(y)) * chromaScale;
    }
    return bt2100PqInverseEotf(clamp(rgbNits / 10000.0, 0.0, 1.0));
}

// APEX produces a peak-normalized master signal (1.0 == target peak), unlike
// NDR/NIVUKX (1.0 == 203-nit diffuse white). Keeping the encoders separate
// prevents the NDR nit fix from changing APEX exposure or peak luminance.
vec3 peakNormalizedDeliveryPqEncode(vec3 normalizedSignal, float peakNits) {
    float peak = clamp(peakNits, HELIOS_DIFFUSE_WHITE_NITS, 4000.0);
    vec3 rgbNits = max(normalizedSignal, vec3(0.0)) * peak;
    return bt2100PqInverseEotf(clamp(rgbNits / 10000.0, 0.0, 1.0));
}

vec3 bt2100ColorVolumeGuard(vec3 linearColor, bool hlgLike, bool pqLike) {
    if (!(hlgLike || pqLike)) return linearColor;
    vec3 guarded = max(linearColor, vec3(0.0));
    float y = clamp(bt2100Luma(guarded), 0.0, 1.0);
    float maxChannel = max(guarded.r, max(guarded.g, guarded.b));
    float minChannel = min(guarded.r, min(guarded.g, guarded.b));
    if (maxChannel > 1.0) {
        float scale = clamp((1.0 - y) / max(maxChannel - y, 1e-5), 0.0, 1.0);
        guarded = vec3(y) + (guarded - vec3(y)) * scale;
    }
    if (minChannel < 0.0) {
        float scale = clamp(y / max(y - minChannel, 1e-5), 0.0, 1.0);
        guarded = vec3(y) + (guarded - vec3(y)) * scale;
    }
    float highlightWeight = smoothstep(0.72, 1.0, y);
    guarded = mix(guarded, vec3(bt2100Luma(guarded)), highlightWeight * 0.035);
    return clamp(guarded, 0.0, 1.0);
}

vec3 gradedCameraSample(vec2 uv, float contrast, float saturation) {
    vec3 cameraSample = texture(uComputeEnhancedIntermediate, clamp(uv, vec2(0.0), vec2(1.0))).rgb;
    float sampleToneDeliveryMode = uGrade3.x;
    bool sampleNdrOrNivukxPath =
        (sampleToneDeliveryMode > 20.5 && sampleToneDeliveryMode < 22.5) ||
        (sampleToneDeliveryMode > 40.5 && sampleToneDeliveryMode < 42.5);
    float sampleColorGainBlend = sampleNdrOrNivukxPath ? 0.0 : clamp(uColorGain.a * 18.0, 0.0, 1.0);
    cameraSample = mix(
        cameraSample,
        clamp(cameraSample * uColorGain.rgb, 0.0, 1.0),
        sampleColorGainBlend
    );
    cameraSample = preGradeChromaNoiseGuard(cameraSample, 0.0);
    cameraSample = applyContrastAndSaturation(cameraSample, contrast, saturation);
    cameraSample *= uGrade1.rgb;
    cameraSample += uGrade2.rgb * 0.15;
    return clamp(cameraSample, 0.0, 1.0);
}

vec3 decodeCameraSample(vec2 uv, bool hlgLike, bool pqLike, float contrast, float saturation) {
    vec3 cameraSample = gradedCameraSample(uv, contrast, saturation);
    vec3 decoded = hlgLike ? bt2100HlgInverseOetf(cameraSample)
                 : pqLike ? bt2100PqEotf(cameraSample)
                 : srgbToLinear(cameraSample);
    vec3 guarded = bt2100ColorVolumeGuard(decoded, hlgLike, pqLike);
    vec3 acesCg = neoDisplayLinearToAcesCg(guarded, hlgLike || pqLike);
    return neoAcesCgToDisplayLinear(acesCg, hlgLike || pqLike);
}

void accumulateDenoiseSample(
    inout vec3 sum,
    inout float weightSum,
    vec3 center,
    vec2 uv,
    vec2 offset,
    bool hlgLike,
    bool pqLike,
    float contrast,
    float saturation,
    float spatialWeight
) {
    vec3 cameraSample = decodeCameraSample(uv + offset, hlgLike, pqLike, contrast, saturation);
    float lumaDelta = abs(luminance(cameraSample) - luminance(center));
    float chromaDelta = length(cameraSample - center);
    float flatWeight = 1.0 - smoothstep(0.008, 0.098, lumaDelta);
    float chromaWeight = 1.0 - smoothstep(0.015, 0.185, chromaDelta);
    float edgeWeight = max(0.01, flatWeight * (0.35 + chromaWeight * 0.65));
    float weight = spatialWeight * edgeWeight;
    sum += cameraSample * weight;
    weightSum += weight;
}

vec3 edgeAwareSpatialDenoise(
    vec2 uv,
    vec3 center,
    bool hlgLike,
    bool pqLike,
    float contrast,
    float saturation,
    float strength,
    bool boundedNormalProSdrQuality
) {
    if (strength <= 0.01) return center;
    ivec2 size = textureSize(uComputeEnhancedIntermediate, 0);
    vec2 texel = 1.0 / max(vec2(size), vec2(1.0));
    vec3 sum = center * 2.10;
    float weightSum = 2.10;
    accumulateDenoiseSample(sum, weightSum, center, uv, vec2(texel.x, 0.0), hlgLike, pqLike, contrast, saturation, 1.0);
    accumulateDenoiseSample(sum, weightSum, center, uv, vec2(-texel.x, 0.0), hlgLike, pqLike, contrast, saturation, 1.0);
    accumulateDenoiseSample(sum, weightSum, center, uv, vec2(0.0, texel.y), hlgLike, pqLike, contrast, saturation, 1.0);
    accumulateDenoiseSample(sum, weightSum, center, uv, vec2(0.0, -texel.y), hlgLike, pqLike, contrast, saturation, 1.0);
    accumulateDenoiseSample(sum, weightSum, center, uv, texel, hlgLike, pqLike, contrast, saturation, 0.62);
    accumulateDenoiseSample(sum, weightSum, center, uv, vec2(-texel.x, texel.y), hlgLike, pqLike, contrast, saturation, 0.62);
    accumulateDenoiseSample(sum, weightSum, center, uv, vec2(texel.x, -texel.y), hlgLike, pqLike, contrast, saturation, 0.62);
    accumulateDenoiseSample(sum, weightSum, center, uv, -texel, hlgLike, pqLike, contrast, saturation, 0.62);
    accumulateDenoiseSample(sum, weightSum, center, uv, vec2(texel.x * 2.0, 0.0), hlgLike, pqLike, contrast, saturation, 0.34);
    accumulateDenoiseSample(sum, weightSum, center, uv, vec2(-texel.x * 2.0, 0.0), hlgLike, pqLike, contrast, saturation, 0.34);
    accumulateDenoiseSample(sum, weightSum, center, uv, vec2(0.0, texel.y * 2.0), hlgLike, pqLike, contrast, saturation, 0.34);
    accumulateDenoiseSample(sum, weightSum, center, uv, vec2(0.0, -texel.y * 2.0), hlgLike, pqLike, contrast, saturation, 0.34);
    accumulateDenoiseSample(sum, weightSum, center, uv, vec2(texel.x * 2.0, texel.y * 2.0), hlgLike, pqLike, contrast, saturation, 0.18);
    accumulateDenoiseSample(sum, weightSum, center, uv, vec2(-texel.x * 2.0, texel.y * 2.0), hlgLike, pqLike, contrast, saturation, 0.18);
    accumulateDenoiseSample(sum, weightSum, center, uv, vec2(texel.x * 2.0, -texel.y * 2.0), hlgLike, pqLike, contrast, saturation, 0.18);
    accumulateDenoiseSample(sum, weightSum, center, uv, vec2(-texel.x * 2.0, -texel.y * 2.0), hlgLike, pqLike, contrast, saturation, 0.18);

    vec3 filtered = sum / max(weightSum, 1e-4);
    float luma = luminance(center);
    float localVariance = heteroscedasticNoiseVariance(luma);
    float localRange = max(max(abs(luminance(filtered) - luma), length(center - filtered)), localVariance);
    float darkBoost = 1.0 + (1.0 - smoothstep(0.12, 0.65, luma)) * (0.30 + localVariance * 12.0);
    float highlightProtect = 1.0 - smoothstep(0.78, 0.98, luma) * 0.16;
    float textureProtect = smoothstep(0.010, 0.075, localRange);
    float denoiseAuthority = normalProSdrDenoiseAuthority(strength, boundedNormalProSdrQuality);
    bool strictNormalProSdrDenoise = strictDenoiseAuthorityActive(
        denoiseAuthority,
        boundedNormalProSdrQuality
    );
    float textureProtectFactor = strictNormalProSdrDenoise
        ? (boundedNormalProSdrQuality ? 0.18 : 0.06)
        : 0.34;
    float strictCleanLift = strictNormalProSdrDenoise
        ? (boundedNormalProSdrQuality ? 1.08 : 1.40)
        : 1.0;
    float effectiveStrength = clamp(
        (boundedNormalProSdrQuality ? denoiseAuthority : strength) *
            darkBoost * highlightProtect * (1.0 - textureProtect * textureProtectFactor) * strictCleanLift,
        0.0,
        strictNormalProSdrDenoise ? 1.0 : 0.985
    );
    vec3 bayesian = laplacianPyramidBayesShrink(center, filtered, localVariance, localRange, strength);
    if (strictNormalProSdrDenoise) {
        float bayesianLuma = luminance(bayesian);
        vec3 neutral = vec3(bayesianLuma);
        float chromaResidue = length(bayesian - neutral);
        float lumaResidue = abs(bayesianLuma - luma);
        float flatShadowNoise = (1.0 - smoothstep(0.18, 0.82, luma)) *
            (1.0 - smoothstep(0.10, 0.36, localRange));
        float texturelessRgbGrain = strictTexturelessRgbGrainGate(
            luma,
            chromaResidue,
            denoiseAuthority,
            boundedNormalProSdrQuality
        );
        float strictRgbNoiseBlend = max(
            max(smoothstep(0.0008, 0.050, chromaResidue), smoothstep(0.0012, 0.018, lumaResidue)),
            texturelessRgbGrain
        ) * max(flatShadowNoise, texturelessRgbGrain);
        bayesian = mix(bayesian, neutral, clamp(strictRgbNoiseBlend, 0.0, 0.985));
    }
    return mix(center, bayesian, effectiveStrength);
}

float detailNoiseGate(vec3 highPass, float luma) {
    float detail = abs(luminance(highPass));
    float threshold = mix(0.028, 0.009, smoothstep(0.10, 0.44, luma));
    return smoothstep(threshold, threshold * 3.5, detail);
}

float cinemaEdgeConfidence(
    float horizontalEdge,
    float verticalEdge,
    float diagonalEdge,
    float localRange,
    float luma
) {
    float directionalEdge = max(max(horizontalEdge, verticalEdge), diagonalEdge);
    float rangeSupport = smoothstep(0.012, 0.18, localRange);
    float lumaSupport = 1.0 - smoothstep(0.90, 1.08, luma);
    return smoothstep(0.010, 0.145, directionalEdge) * rangeSupport * lumaSupport;
}

float resolutionNormalizedDetailScale(ivec2 size) {
    float longEdge = max(float(size.x), float(size.y));
    float normalizedLongEdge = clamp(longEdge / 2160.0, 0.50, 4.0);
    return clamp(sqrt(normalizedLongEdge), 0.72, 1.44);
}

float microDetailConfidence(
    float lumaDetail,
    float localRange,
    float edgeConfidence,
    float luma
) {
    float detail = abs(lumaDetail);
    float fineTexture = smoothstep(0.004, 0.026, detail) * (1.0 - smoothstep(0.11, 0.30, detail));
    float rangeGuard = 1.0 - smoothstep(0.24, 0.62, localRange);
    float toneGuard = smoothstep(0.035, 0.18, luma) * (1.0 - smoothstep(0.82, 0.98, luma));
    return clamp(fineTexture * rangeGuard * toneGuard * (0.48 + edgeConfidence * 0.52), 0.0, 1.0);
}

bool extremeDetailProfileActive() {
    return uSubjectDetail.x > 2.5 && uSubjectDetail.x < 3.5;
}

vec3 cinemaDetailRefine(
    vec2 uv,
    vec3 center,
    bool hlgLike,
    bool pqLike,
    float contrast,
    float saturation,
    float strength,
    bool boundedNormalProSdrQuality
) {
    if (strength <= 0.01) return center;
    ivec2 size = textureSize(uComputeEnhancedIntermediate, 0);
    vec2 texel = 1.0 / max(vec2(size), vec2(1.0));
    float detailScale = resolutionNormalizedDetailScale(size);
    vec3 east = decodeCameraSample(uv + vec2(texel.x, 0.0), hlgLike, pqLike, contrast, saturation);
    vec3 west = decodeCameraSample(uv + vec2(-texel.x, 0.0), hlgLike, pqLike, contrast, saturation);
    vec3 north = decodeCameraSample(uv + vec2(0.0, texel.y), hlgLike, pqLike, contrast, saturation);
    vec3 south = decodeCameraSample(uv + vec2(0.0, -texel.y), hlgLike, pqLike, contrast, saturation);
    vec3 ne = decodeCameraSample(uv + texel, hlgLike, pqLike, contrast, saturation);
    vec3 nw = decodeCameraSample(uv + vec2(-texel.x, texel.y), hlgLike, pqLike, contrast, saturation);
    vec3 se = decodeCameraSample(uv + vec2(texel.x, -texel.y), hlgLike, pqLike, contrast, saturation);
    vec3 sw = decodeCameraSample(uv - texel, hlgLike, pqLike, contrast, saturation);
    vec3 east2 = decodeCameraSample(uv + vec2(texel.x * 2.0, 0.0), hlgLike, pqLike, contrast, saturation);
    vec3 west2 = decodeCameraSample(uv + vec2(-texel.x * 2.0, 0.0), hlgLike, pqLike, contrast, saturation);
    vec3 north2 = decodeCameraSample(uv + vec2(0.0, texel.y * 2.0), hlgLike, pqLike, contrast, saturation);
    vec3 south2 = decodeCameraSample(uv + vec2(0.0, -texel.y * 2.0), hlgLike, pqLike, contrast, saturation);
    vec3 ne2 = decodeCameraSample(uv + texel * 2.0, hlgLike, pqLike, contrast, saturation);
    vec3 nw2 = decodeCameraSample(uv + vec2(-texel.x * 2.0, texel.y * 2.0), hlgLike, pqLike, contrast, saturation);
    vec3 se2 = decodeCameraSample(uv + vec2(texel.x * 2.0, -texel.y * 2.0), hlgLike, pqLike, contrast, saturation);
    vec3 sw2 = decodeCameraSample(uv - texel * 2.0, hlgLike, pqLike, contrast, saturation);
    vec3 east3 = decodeCameraSample(uv + vec2(texel.x * 3.0, 0.0), hlgLike, pqLike, contrast, saturation);
    vec3 west3 = decodeCameraSample(uv + vec2(-texel.x * 3.0, 0.0), hlgLike, pqLike, contrast, saturation);
    vec3 north3 = decodeCameraSample(uv + vec2(0.0, texel.y * 3.0), hlgLike, pqLike, contrast, saturation);
    vec3 south3 = decodeCameraSample(uv + vec2(0.0, -texel.y * 3.0), hlgLike, pqLike, contrast, saturation);
    vec3 ne3 = decodeCameraSample(uv + texel * 3.0, hlgLike, pqLike, contrast, saturation);
    vec3 nw3 = decodeCameraSample(uv + vec2(-texel.x * 3.0, texel.y * 3.0), hlgLike, pqLike, contrast, saturation);
    vec3 se3 = decodeCameraSample(uv + vec2(texel.x * 3.0, -texel.y * 3.0), hlgLike, pqLike, contrast, saturation);
    vec3 sw3 = decodeCameraSample(uv - texel * 3.0, hlgLike, pqLike, contrast, saturation);
    // 9-tap strict IMAX Laplacian: center + first-ring axial/diagonal samples.
    vec3 blur = (
        center * 4.40 +
            (east + west + north + south) * 1.26 +
            (ne + nw + se + sw) * 0.58
        ) / 11.52;
    vec3 highPass = center - blur;
    float luma = luminance(center);
    float eastL = luminance(east);
    float westL = luminance(west);
    float northL = luminance(north);
    float southL = luminance(south);
    float neL = luminance(ne);
    float nwL = luminance(nw);
    float seL = luminance(se);
    float swL = luminance(sw);
    float east2L = luminance(east2);
    float west2L = luminance(west2);
    float north2L = luminance(north2);
    float south2L = luminance(south2);
    float ne2L = luminance(ne2);
    float nw2L = luminance(nw2);
    float se2L = luminance(se2);
    float sw2L = luminance(sw2);
    float east3L = luminance(east3);
    float west3L = luminance(west3);
    float north3L = luminance(north3);
    float south3L = luminance(south3);
    float ne3L = luminance(ne3);
    float nw3L = luminance(nw3);
    float se3L = luminance(se3);
    float sw3L = luminance(sw3);
    float minNeighborLuma = min(min(min(eastL, westL), min(northL, southL)), min(min(neL, nwL), min(seL, swL)));
    float maxNeighborLuma = max(max(max(eastL, westL), max(northL, southL)), max(max(neL, nwL), max(seL, swL)));
    float localRange = maxNeighborLuma - minNeighborLuma;
    float horizontalEdge = abs(eastL - westL);
    float verticalEdge = abs(northL - southL);
    float diagonalEdge = max(abs(neL - swL), abs(nwL - seL));
    float scharrX = (-3.0 * nwL + 3.0 * neL) + (-10.0 * westL + 10.0 * eastL) + (-3.0 * swL + 3.0 * seL);
    float scharrY = (-3.0 * nwL - 10.0 * northL - 3.0 * neL) + (3.0 * swL + 10.0 * southL + 3.0 * seL);
    float scharrMagnitude = length(vec2(scharrX, scharrY));
    float tangentTexture = min(abs(neL - nwL) + abs(seL - swL), abs(neL - seL) + abs(nwL - swL));
    float normalTexture = max(horizontalEdge, verticalEdge) + diagonalEdge;
    float tangentConfidence = clamp(tangentTexture / max(tangentTexture + normalTexture, 1e-5), 0.0, 1.0);
    float edgeConfidence = cinemaEdgeConfidence(horizontalEdge, verticalEdge, diagonalEdge, localRange, luma);
    float gate = detailNoiseGate(highPass, luma);
    float lumaDetail = luminance(highPass);
    if (boundedNormalProSdrQuality) {
        // The fallback fragment lane must retain coherent fine texture when the compute intermediate
        // is temporarily unavailable. The old 0.9-2.8% threshold rejected distant masonry/foliage.
        float coherentFineGate = smoothstep(0.0020, 0.015, abs(lumaDetail)) *
            smoothstep(0.006, 0.070, localRange) *
            (1.0 - smoothstep(0.24, 0.58, localRange));
        gate = max(gate, coherentFineGate);
    }
    float chromaDetail = length(highPass - vec3(lumaDetail));
    float chromaGuard = 1.0 - smoothstep(0.018, 0.11, chromaDetail);
    float haloGuard = 1.0 - smoothstep(0.10, 0.30, length(highPass));
    float localContrastGuard = 1.0 - smoothstep(0.44, 0.86, localRange);
    float microDetail = microDetailConfidence(lumaDetail, localRange, edgeConfidence, luma);
    float localNoiseVariance = heteroscedasticNoiseVariance(luma);
    float spatialFrequency = max(max(horizontalEdge, verticalEdge), diagonalEdge);
    float hvsMask = hvsCsfMask(localRange, luma, spatialFrequency);
    float csfMidtoneGain = csfMicroContrastWeight(luma, 1.0);
    float scharrHardEdge = smoothstep(0.16, 0.62, localRange) * smoothstep(0.12, 0.82, scharrMagnitude);
    float anisotropicHaloGuard = 1.0 - scharrHardEdge * (1.0 - tangentConfidence * 0.70);
    float farRingMean = (east2L + west2L + north2L + south2L + ne2L + nw2L + se2L + sw2L) * 0.125;
    float wideRingMean = (east3L + west3L + north3L + south3L + ne3L + nw3L + se3L + sw3L) * 0.125;
    float longDistanceStructure = mix(luma - farRingMean, luma - wideRingMean, 0.35);
    float farStructureAgreement = 1.0 - smoothstep(0.010, 0.060, abs(farRingMean - wideRingMean));
    float strictNormalProSdrDetailAuthority = boundedNormalProSdrQuality
        ? normalizeEnhancementStrength(strength)
        : (strength >= 9.5 ? 10.0 : 1.0);
    bool strictNormalProSdrDetail = boundedNormalProSdrQuality
        ? strictNormalProSdrDetailAuthority >= 0.18
        : strictNormalProSdrDetailAuthority > 1.0;
    bool extremeDetail = extremeDetailProfileActive();
    float profileDetailAuthority = extremeDetail ? 1.22 : 1.0;
    float longDistanceTextureGate = smoothstep(0.001, 0.014, abs(longDistanceStructure)) *
        (1.0 - smoothstep(0.22, 0.52, localRange)) *
        smoothstep(0.045, 0.88, luma) *
        (0.72 + farStructureAgreement * 0.28) *
        (strictNormalProSdrDetail ? 1.0 : 0.0);
    float isolatedPixelNoise = abs(luma - (eastL + westL + northL + southL) * 0.25);
    float strictDetailNoiseReject = strictNormalProSdrDetail
        ? clamp(
            1.0 - smoothstep(0.014, 0.045, isolatedPixelNoise) *
                (1.0 - smoothstep(0.045, 0.18, localRange)) *
                (0.64 - longDistanceTextureGate * 0.24),
            0.36 + longDistanceTextureGate * 0.24,
            1.0
        )
        : 1.0;
    float adaptiveBaseGain = noiseAdaptiveLaplacianGain(
        strength *
            (0.72 + edgeConfidence * 2.20 + microDetail * 0.52) *
            (0.58 + csfMidtoneGain * 1.70) *
            detailScale *
            gate *
            haloGuard *
            localContrastGuard *
            chromaGuard *
            hvsMask *
            clamp(anisotropicHaloGuard, 0.04, 1.0) *
            strictDetailNoiseReject *
            (1.0 + longDistanceTextureGate * 2.40),
        localNoiseVariance,
        0.012
    );
    const float ULTRA_DETAIL_AMPLIFICATION = 20.0;
    float gain = clamp(
        adaptiveBaseGain *
        ULTRA_DETAIL_AMPLIFICATION *
        (boundedNormalProSdrQuality
            ? mix(0.72, 1.0, strictNormalProSdrDetailAuthority)
            : strictNormalProSdrDetailAuthority) *
        profileDetailAuthority,
        0.0,
        boundedNormalProSdrQuality
            ? (extremeDetail ? 12.0 : 8.0)
            : (strictNormalProSdrDetailAuthority > 1.0 ? 100.0 : (extremeDetail ? 12.0 : 10.0))
    );
    float strictDetailLuma = lumaDetail + longDistanceStructure * longDistanceTextureGate * 0.52;
    vec3 candidate = center + vec3(strictDetailLuma * gain);
    float candidateLuma = luminance(candidate);
    float overshootLimit = max(
        extremeDetail ? 0.0028 : 0.0032,
        localRange *
        (boundedNormalProSdrQuality
            ? mix(0.16, 0.30, strictNormalProSdrDetailAuthority)
            : strictNormalProSdrDetailAuthority) *
        (extremeDetail ? 0.82 : 1.0) +
        0.010 / detailScale
    );
    float limitedLuma = clamp(candidateLuma, minNeighborLuma - overshootLimit, maxNeighborLuma + overshootLimit);
    vec3 refined = candidate + vec3((limitedLuma - candidateLuma) * 0.90);
    float skinGuard = boundedNormalProSdrQuality ? skinToneWeight(center) : 0.0;
    float highlightBlendBack = smoothstep(0.70, 0.94, luma) *
        (boundedNormalProSdrQuality ? 0.22 : (strictNormalProSdrDetailAuthority > 1.0 ? 0.0 : 0.14));
    float haloBlendBack = (1.0 - localContrastGuard) *
        (boundedNormalProSdrQuality ? 0.30 : (strictNormalProSdrDetailAuthority > 1.0 ? 0.0 : 0.24));
    float skinBlendBack = skinGuard * 0.20;
    float blendBack = boundedNormalProSdrQuality
        ? clamp(highlightBlendBack + haloBlendBack + skinBlendBack, 0.0, 0.72)
        : highlightBlendBack + haloBlendBack;
    return clamp(
        mix(refined, center, blendBack),
        0.0,
        boundedNormalProSdrQuality
            ? 1.08
            : (strictNormalProSdrDetailAuthority > 1.0 ? 10.0 : 1.08)
    );
}

vec3 edgeAwareSharpen(
    vec2 uv,
    vec3 center,
    bool hlgLike,
    bool pqLike,
    float contrast,
    float saturation,
    float strength,
    bool boundedNormalProSdrQuality
) {
    return cinemaDetailRefine(
        uv,
        center,
        hlgLike,
        pqLike,
        contrast,
        saturation,
        strength,
        boundedNormalProSdrQuality
    );
}

float gatedSubjectDetailGain(vec2 uv) {
    bool focusedDetail = uSubjectDetail.x > 0.5 && uSubjectDetail.x < 1.5;
    if (!focusedDetail || uSubjectDetail.y < 0.5 || uSubjectDetail.z <= 0.001) return 0.0;
    float subjectAlpha = texture(uSubjectDetailMask, clamp(uv, vec2(0.0), vec2(1.0))).r;
    float gatedDetailGain = uSubjectDetail.z *
        smoothstep(0.0, max(uSubjectDetail.w, 0.001), subjectAlpha);
    return clamp(gatedDetailGain, 0.0, 10.0);
}

vec3 applySubjectGatedDetail(
    vec2 uv,
    vec3 linear,
    bool hlgLike,
    bool pqLike,
    float contrast,
    float saturation,
    bool boundedNormalProSdrQuality
) {
    return edgeAwareSharpen(
        uv,
        linear,
        hlgLike,
        pqLike,
        contrast,
        saturation,
        gatedSubjectDetailGain(uv),
        boundedNormalProSdrQuality
    );
}

vec3 suppressRecordingDeliveryNoise(
    vec2 uv,
    vec3 linear,
    bool logDelivery,
    bool hlgDelivery,
    bool pqDelivery,
    bool rawDelivery,
    float contrast,
    float saturation,
    float plannedChromaDenoise,
    float plannedSharpening,
    float measuredNoise,
    bool boundedNormalProSdrQuality
) {
    float modeChromaLift = logDelivery ? 0.08
        : rawDelivery ? 0.04
        : ((hlgDelivery || pqDelivery) ? 0.10 : 0.08);
    float strictChromaDenoiseAuthority;
    float strictNoiseAuthority;
    float strictDenoiseCeiling;
    if (!boundedNormalProSdrQuality) {
        strictChromaDenoiseAuthority = plannedChromaDenoise;
        strictNoiseAuthority = measuredNoise;
        strictDenoiseCeiling = 1.0;
    } else {
        strictChromaDenoiseAuthority = normalizeEnhancementStrength(plannedChromaDenoise);
        strictNoiseAuthority = normalizeEnhancementStrength(measuredNoise);
        strictDenoiseCeiling = 1.0;
    }
    float chromaStrength = clamp(
        strictChromaDenoiseAuthority + modeChromaLift + strictNoiseAuthority * 0.08,
        0.50,
        strictDenoiseCeiling
    );
    float rgbStrength = clamp(
        strictChromaDenoiseAuthority * 0.88 + strictNoiseAuthority * 0.36 + modeChromaLift,
        0.34,
        strictDenoiseCeiling
    );
    float darkStrength = clamp(
        strictChromaDenoiseAuthority * 0.78 + strictNoiseAuthority * 0.40 + modeChromaLift,
        0.34,
        strictDenoiseCeiling
    );
    float strictSdrSharpening = boundedNormalProSdrQuality
        ? normalizeEnhancementStrength(plannedSharpening)
        : plannedSharpening;
    float sharpenStrength = strictSdrSharpening <= 0.001
        ? 0.0
        : clamp(
            boundedNormalProSdrQuality
                ? strictSdrSharpening
                : plannedSharpening * (1.0 - measuredNoise * 0.10) +
                    ((hlgDelivery || pqDelivery) ? 0.070 : 0.055),
            0.0,
            1.0
        );

    linear = chromaDenoise(linear, chromaStrength, boundedNormalProSdrQuality);
    linear = rgbSpeckleSuppress(linear, rgbStrength, boundedNormalProSdrQuality);
    linear = darkNoiseFloorSuppress(linear, darkStrength, boundedNormalProSdrQuality);
    linear = edgeAwareSharpen(
        uv,
        linear,
        hlgDelivery,
        pqDelivery,
        contrast,
        saturation,
        sharpenStrength,
        boundedNormalProSdrQuality
    );
    return applySubjectGatedDetail(
        uv,
        linear,
        hlgDelivery,
        pqDelivery,
        contrast,
        saturation,
        boundedNormalProSdrQuality
    );
}

float ndrProfileSignalShape(float value, bool nivukxVision) {
    float x = max(value, 0.0);
    float gammaValue = nivukxVision ? NIVUKX_SIGNAL_GAMMA : NDR10_SIGNAL_GAMMA;
    float toeWidth = nivukxVision ? NIVUKX_TOE_WIDTH : NDR10_TOE_WIDTH;
    float toeAmount = nivukxVision ? NIVUKX_TOE_AMOUNT : NDR10_TOE_AMOUNT;
    if (x <= 1.0) {
        return pow(x, gammaValue) + smoothstep(0.0, toeWidth, x) * toeAmount * (1.0 - x);
    }
    return 1.0 + (gammaValue - toeAmount) * (x - 1.0);
}

float ndrBlackPreservingMidtoneLift(float signalValue, bool nivukxVision) {
    float x = max(signalValue, 0.0);
    if (x <= 0.0 || x >= 1.0) return x;
    float strength = nivukxVision
        ? NIVUKX_SHADOW_MIDTONE_LIFT
        : NDR10_SHADOW_MIDTONE_LIFT;
    float window = smoothstep(0.025, 0.20, x) *
        (1.0 - smoothstep(0.78, 1.20, x));
    return x + strength * sqrt(x) * (1.0 - x) * window;
}

float ndrProfileMidtoneContrast(float signalValue, bool nivukxVision) {
    float x = max(signalValue, 0.0);
    if (x <= 0.0 || x >= 1.0) return x;
    float strength = nivukxVision ? NIVUKX_MIDTONE_CONTRAST : NDR10_MIDTONE_CONTRAST;
    float window = smoothstep(0.055, 0.24, x) *
        (1.0 - smoothstep(0.76, 1.0, x));
    float signedContrast = (x - 0.50) * 4.0 * x * (1.0 - x);
    return max(x + strength * signedContrast * window, 0.0);
}

float ndrProfileLocalDetailStops(vec2 uv, float centerY) {
    ivec2 size = textureSize(uComputeEnhancedIntermediate, 0);
    vec2 texel = 1.0 / max(vec2(size), vec2(1.0));
    float localBase = 0.25 * (
        ndrSceneLuma(srgbToLinear(texture(uComputeEnhancedIntermediate, uv + vec2(texel.x, 0.0)).rgb)) +
        ndrSceneLuma(srgbToLinear(texture(uComputeEnhancedIntermediate, uv - vec2(texel.x, 0.0)).rgb)) +
        ndrSceneLuma(srgbToLinear(texture(uComputeEnhancedIntermediate, uv + vec2(0.0, texel.y)).rgb)) +
        ndrSceneLuma(srgbToLinear(texture(uComputeEnhancedIntermediate, uv - vec2(0.0, texel.y)).rgb))
    );
    float detailStops = log2((centerY + 0.0020) / (localBase + 0.0020));
    float edgeConfidence = smoothstep(0.006, 0.040, abs(centerY - localBase));
    float shadowNoiseGuard = smoothstep(0.018, 0.075, centerY);
    float highlightGuard = 1.0 - smoothstep(1.30, 3.20, centerY);
    return clamp(detailStops, -0.22, 0.22) *
        edgeConfidence * shadowNoiseGuard * highlightGuard;
}

vec3 ndrProfileColorCharacter(
    vec3 toneMapped,
    vec3 source,
    float mappedY,
    bool nivukxVision
) {
    float peakValue = max(toneMapped.r, max(toneMapped.g, toneMapped.b));
    float floorValue = min(toneMapped.r, min(toneMapped.g, toneMapped.b));
    float relativeChroma = (peakValue - floorValue) / max(mappedY, 0.04);
    float colorConfidence = smoothstep(0.035, 0.22, relativeChroma);
    float rg = source.r / max(source.g, 1e-8);
    float bg = source.b / max(source.g, 1e-8);
    float skin = smoothstep(1.02, 1.16, rg) *
        (1.0 - smoothstep(1.58, 2.10, rg)) *
        (1.0 - smoothstep(0.98, 1.18, bg));
    float sky = smoothstep(1.03, 1.22, bg) *
        (1.0 - smoothstep(1.00, 1.22, rg));
    float tonalWindow = smoothstep(0.035, 0.16, mappedY) *
        (1.0 - smoothstep(0.88, 1.65, mappedY));
    float baseBoost = nivukxVision ? NIVUKX_CHROMA_BOOST : NDR10_CHROMA_BOOST;
    float skyBonus = nivukxVision ? NIVUKX_SKY_CHROMA_BONUS : NDR10_SKY_CHROMA_BONUS;
    float skinProtection = mix(1.0, 0.45, skin);
    float gain = 1.0 + (baseBoost + skyBonus * sky) *
        colorConfidence * tonalWindow * skinProtection;
    return vec3(mappedY) + (toneMapped - vec3(mappedY)) * gain;
}

float ndrSdrMonitorLuminance(float toneSignal) {
    float x = max(toneSignal, 0.0);
    if (x <= 1.0) return x * NDR_SDR_MONITOR_DIFFUSE_LINEAR;
    float headroom = 1.0 - NDR_SDR_MONITOR_DIFFUSE_LINEAR;
    float shoulderLength = headroom / NDR_SDR_MONITOR_DIFFUSE_LINEAR;
    float excess = x - 1.0;
    return clamp(
        NDR_SDR_MONITOR_DIFFUSE_LINEAR +
            headroom * (excess / (excess + shoulderLength)),
        0.0,
        1.0
    );
}

vec3 ndrSignalToSdrMonitor(vec3 toneSignal, bool nivukxVision) {
    vec3 signal = max(toneSignal, vec3(0.0));
    float signalY = max(ndrSceneLuma(signal), 1e-8);
    float monitorY = ndrSdrMonitorLuminance(signalY);
    vec3 monitorLinear = signal * (monitorY / signalY);
    // Unit-gamut fitting is a display constraint only. The PQ delivery path
    // never calls this function and therefore retains all highlight headroom.
    return ndrDisplayColorVolumeFit(monitorLinear, nivukxVision);
}

vec3 finishRealtimeComputedNdrDelivery(
    vec2 uv,
    vec3 linear,
    bool nivukxVision
) {
    // Exact live GLSL mirror of NdrToneOperator / raw_monitor_tonemap.comp.
    // The compute intermediate owns denoise/sharpening; this stage adds only
    // the calibrated profile's bounded local-detail character and tone/color.
    vec3 source = max(linear, vec3(0.0));
    float sourceY = max(ndrSceneLuma(source), 1e-5);
    float mappedY = ndrProfileSignalShape(sourceY, nivukxVision);
    mappedY = ndrBlackPreservingMidtoneLift(mappedY, nivukxVision);
    mappedY = ndrProfileMidtoneContrast(mappedY, nivukxVision);

    float detailStrength = nivukxVision
        ? NIVUKX_LOCAL_DETAIL_STRENGTH
        : NDR10_LOCAL_DETAIL_STRENGTH;
    float detailLower = nivukxVision ? -0.085000 : -0.060000;
    float detailUpper = nivukxVision ? 0.125000 : 0.085000;
    float detailGainStops = clamp(
        ndrProfileLocalDetailStops(uv, sourceY) * detailStrength,
        detailLower,
        detailUpper
    );
    mappedY *= exp2(detailGainStops);

    vec3 mapped = source * (mappedY / sourceY);
    mapped = ndrProfileColorCharacter(mapped, source, mappedY, nivukxVision);
    float desatCap = nivukxVision ? NIVUKX_HIGHLIGHT_DESAT : NDR10_HIGHLIGHT_DESAT;
    float desat = clamp(
        desatCap * smoothstep(0.55, 1.0, mappedY),
        0.0,
        desatCap
    );
    mapped = mix(mapped, vec3(mappedY), desat);
    return max(mapped, vec3(0.0));
}

void main() {
    vec2 transformed = (uTransform * vec4(vTexCoord, 0.0, 1.0)).xy;
    if (transformed.x < 0.0 || transformed.x > 1.0 || transformed.y < 0.0 || transformed.y > 1.0) {
        outColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    transformed = applyAnamorphicLensWarp(
        transformed,
        ANAMORPHIC_DESQUEEZE_DEFAULT,
        vec3(0.0, 0.0, 0.0),
        vec2(0.0, 0.0)
    );

    float earlyToneDeliveryMode = uGrade3.x;
    bool earlyNdrOrNivukxPath =
        (earlyToneDeliveryMode > 20.5 && earlyToneDeliveryMode < 22.5) ||
        (earlyToneDeliveryMode > 40.5 && earlyToneDeliveryMode < 42.5);
    bool earlyRawMonitor = earlyToneDeliveryMode > 7.5 && earlyToneDeliveryMode < 8.5;
    float earlyColorGainBlend = (earlyNdrOrNivukxPath || earlyRawMonitor)
        ? 0.0
        : clamp(uColorGain.a * 18.0, 0.0, 1.0);
    vec3 color = earlyNdrOrNivukxPath
        ? texture(uComputeEnhancedIntermediate, clamp(transformed, vec2(0.0), vec2(1.0))).rgb
        : bicubicPreviewSample(transformed);
    color = mix(color, clamp(color * uColorGain.rgb, 0.0, 1.0), earlyColorGainBlend);
    // Apply before any RAW/LOG/HDR/SDR branch so transfer curves cannot magnify false channel offsets.
    // This changes chroma only inside the sensor-black uncertainty floor; all luma/detail is retained.
    color = calibrateNearBlackChroma(color);

    float contrast = uGrade0.x;
    float saturation = uGrade0.y;
    float shadowLift = clamp(uGrade0.z, 0.0, 1.0);
    float highlightCompression = clamp(uGrade0.w, 0.0, 1.0);
    float logStrength = clamp(uGrade1.w, 0.0, 1.0);
    float hdrStrength = clamp(uGrade2.w, 0.0, 1.0);
    float shadowBiasScale = clamp(uGrade3.z, 0.0, 1.5);
    float highlightBiasScale = clamp(uGrade3.w, 0.0, 1.5);
    float toneDeliveryMode = earlyToneDeliveryMode;

    bool lutEnabled = toneDeliveryMode > 0.5 && toneDeliveryMode < 1.5 && uGrade3.y > 1.0;
    bool hlgDelivery = toneDeliveryMode > 1.5 && toneDeliveryMode < 2.5;
    bool ndr10PlusDelivery = toneDeliveryMode > 20.5 && toneDeliveryMode < 21.5;
    bool nivukxVisionDelivery = toneDeliveryMode > 21.5 && toneDeliveryMode < 22.5;
    bool pqDelivery = (toneDeliveryMode > 2.5 && toneDeliveryMode < 3.5)
        || ndr10PlusDelivery || nivukxVisionDelivery;
    bool rawMonitor = toneDeliveryMode > 7.5 && toneDeliveryMode < 8.5;
    bool hlgMonitor = toneDeliveryMode > 5.5 && toneDeliveryMode < 6.5;
    bool ndr10PlusMonitor = toneDeliveryMode > 40.5 && toneDeliveryMode < 41.5;
    bool nivukxVisionMonitor = toneDeliveryMode > 41.5 && toneDeliveryMode < 42.5;
    bool pqMonitor = (toneDeliveryMode > 6.5 && toneDeliveryMode < 7.5)
        || ndr10PlusMonitor || nivukxVisionMonitor;
    bool neoNdrDelivery = ndr10PlusDelivery || nivukxVisionDelivery;
    bool neoNdrMonitor = ndr10PlusMonitor || nivukxVisionMonitor;
    bool encodedPqDeliveryInput = pqDelivery && !neoNdrDelivery;
    bool encodedPqMonitorInput = pqMonitor && !neoNdrMonitor;
    bool logDelivery = toneDeliveryMode > 9.5 && toneDeliveryMode < 13.5;
    bool logMonitor = toneDeliveryMode > 29.5 && toneDeliveryMode < 33.5;
    bool rawDelivery = toneDeliveryMode > 3.5 && toneDeliveryMode < 5.5;

    // RAW is a dedicated neutral monitor contract, not another SDR grade. Exit before generic
    // contrast/saturation, colour gains, gamut shaping, denoise, shadow lift, sharpening, and SDR
    // tone mapping. The only operations are the required display transfer plus a luminance-preserving
    // highlight shoulder; the RAW master and its sensor samples are never modified here.
    if (rawMonitor) {
        vec3 rawLinear = srgbToLinear(clamp(color, 0.0, 1.0));
        float rawY = dot(rawLinear, vec3(0.2126, 0.7152, 0.0722));
        const float rawKnee = 0.82;
        float rawExcess = max(rawY - rawKnee, 0.0);
        float rawShoulder = rawKnee + (1.0 - rawKnee) *
            (rawExcess / (rawExcess + (1.0 - rawKnee)));
        float rawMappedY = mix(rawY, rawShoulder, step(rawKnee, rawY));
        rawLinear *= rawY > 1.0e-6 ? rawMappedY / rawY : 0.0;
        float rawPeak = max(rawLinear.r, max(rawLinear.g, rawLinear.b));
        rawLinear *= rawPeak > 1.0 ? 1.0 / rawPeak : 1.0;
        outColor = vec4(
            applyJndBlueNoiseDither(
                clamp(linearToSrgb(rawLinear), 0.0, 1.0),
                PREVIEW_JND_DITHER_STRENGTH,
                PREVIEW_QUANTIZATION_LEVELS
            ),
            1.0
        );
        return;
    }
    // ═══════════════════════════════════════════════════════════════════════
    // NIVUKX APEX MODES - Elite tier above base NDR / NIVUKX Vision
    // toneDeliveryMode: 50=APEX SHARP, 51=APEX ZERO, 52=APEX CINEMA
    // ═══════════════════════════════════════════════════════════════════════
    bool apexSharp  = toneDeliveryMode > 49.5 && toneDeliveryMode < 50.5;
    bool apexZero   = toneDeliveryMode > 50.5 && toneDeliveryMode < 51.5;
    bool apexCinema = toneDeliveryMode > 51.5 && toneDeliveryMode < 52.5;
    bool apexMode   = apexSharp || apexZero || apexCinema;
    bool truthful = logDelivery || hlgDelivery || pqDelivery
                  || rawDelivery || apexMode;
    bool normalProSdrQualityIntent = !truthful && !lutEnabled && activeSdrTransport();
    bool normalProSdrDelivery = normalProSdrQualityIntent;
    float plannedSpatialDenoise = normalProSdrQualityIntent
        ? max(normalizeEnhancementStrength(uEnhance0.x), 0.28)
        : clamp(uEnhance0.x, 0.28, 10.0);
    float plannedChromaDenoise = normalProSdrQualityIntent
        ? max(normalizeEnhancementStrength(uEnhance0.y), 0.50)
        : clamp(uEnhance0.y, 0.50, 10.0);
    float plannedSharpening = normalProSdrQualityIntent
        ? normalizeEnhancementStrength(uEnhance0.z)
        : clamp(uEnhance0.z, 0.0, 10.0);
    float measuredNoise = normalProSdrQualityIntent
        ? normalizeEnhancementStrength(uEnhance0.w)
        : clamp(uEnhance0.w, 0.0, 10.0);
    bool computeEnhancedSdr = normalProSdrDelivery &&
        realtimeComputeIntermediateAvailable(transformed);
    float strictDenoiseCeiling = 1.0;
    float strictSpatialDenoiseAuthority = plannedSpatialDenoise;
    float strictNoiseAuthority = measuredNoise;
    float monitorDenoise = clamp(
        strictSpatialDenoiseAuthority +
            strictNoiseAuthority * 0.08 +
            (rawMonitor ? 0.0 : ((hlgMonitor || pqMonitor) ? 0.04 : (logMonitor ? 0.02 : 0.0))),
        0.16,
        strictDenoiseCeiling
    );
    float deliverySpatialDenoise = clamp(
        strictSpatialDenoiseAuthority +
            strictNoiseAuthority * 0.10 +
            (logDelivery ? 0.02 : ((hlgDelivery || pqDelivery) ? 0.04 : 0.0)),
        0.20,
        strictDenoiseCeiling
    );

    if (!computeEnhancedSdr) {
        color = preGradeChromaNoiseGuard(color, toneDeliveryMode);
    }

    if (lutEnabled) color = texture(uPreviewLut, clamp(color, 0.0, 1.0)).rgb;

    if (!truthful) {
        // Non-truthful: display-referred monitoring modes only
        // NDR/NIVUKX monitor and Main10 delivery now share the exact same single-pass profile
        // operator.  Exit before the generic HDR monitor stack, which previously re-ran gamut tone,
        // shadow lift, two knees, three denoisers and sharpening on an already enhanced frame.
        if (neoNdrMonitor) {
            vec3 ndrMonitorLinear = srgbToLinear(clamp(color, 0.0, 1.0));
            ndrMonitorLinear = finishRealtimeComputedNdrDelivery(
                transformed,
                ndrMonitorLinear,
                nivukxVisionMonitor
            );
            ndrMonitorLinear = ndrSignalToSdrMonitor(
                ndrMonitorLinear,
                nivukxVisionMonitor
            );
            outColor = vec4(
                applyJndBlueNoiseDither(
                    clamp(linearToSrgb(ndrMonitorLinear), 0.0, 1.0),
                    PREVIEW_JND_DITHER_STRENGTH,
                    PREVIEW_QUANTIZATION_LEVELS
                ),
                1.0
            );
            return;
        }
        color = applyContrastAndSaturation(color, contrast, saturation);
        color *= uGrade1.rgb;
        color += uGrade2.rgb * 0.15;
        // Decode camera transfer function before linear processing.
        // LOG is recorded from the SDR camera stream, then encoded to the selected log curve.
        vec3 decoded = hlgMonitor ? bt2100HlgInverseOetf(clamp(color, 0.0, 1.0))
                     : encodedPqMonitorInput ? bt2100PqEotf(clamp(color, 0.0, 1.0))
                     : srgbToLinear(clamp(color, 0.0, 1.0));
        vec3 linear = decoded;
        linear = bt2100ColorVolumeGuard(linear, hlgMonitor, encodedPqMonitorInput);
        vec3 neoMonitorWorking = neoDisplayLinearToAcesCg(linear, hlgMonitor || encodedPqMonitorInput);
        neoMonitorWorking = applyNeoGamutOkLchPipeline(neoMonitorWorking);
        linear = neoAcesCgToDisplayLinear(neoMonitorWorking, hlgMonitor || encodedPqMonitorInput);
        if (!computeEnhancedSdr) {
            linear = oklabChromaBilateralFilter(transformed, linear, logMonitor ? 0.76 : 0.70);
            linear = oklabChromaSpeckleGuard(transformed, linear, logMonitor ? 0.74 : 0.68);
            linear = gaborLindebergHvsContrastEnhancement(transformed, linear, 0.08 + contrast * 0.04);
            linear = edgeAwareSpatialDenoise(
                transformed,
                linear,
                hlgMonitor,
                encodedPqMonitorInput,
                contrast,
                saturation,
                monitorDenoise,
                normalProSdrQualityIntent
            );
        }
        linear = shadowPreserve(linear, shadowLift, shadowBiasScale);
        // Normal/Pro true SDR has one authoritative Rec.709 shoulder below. Running this generic
        // rolloff first stacked two transfer operators, lifted/compressed upper midtones, and made
        // the result look flat. Creative/HDR monitor paths retain their own bounded rolloff.
        if (!normalProSdrDelivery) {
            linear = highlightRolloff(linear, highlightCompression, highlightBiasScale);
        }
        linear = bt2100SkinToneRedGuard(linear, hlgMonitor, pqMonitor);

        if (hlgMonitor || pqMonitor) {
            // Reduced defaults: don't force HDR brightness / tone-map if user set them to 0.
            float monitorHdrStrength = max(hdrStrength, pqMonitor ? 0.12 : 0.08);
            float monitorHighlight = max(highlightCompression, pqMonitor ? 0.14 : 0.10);
            linear = shadowPreserve(linear, shadowLift + 0.015, shadowBiasScale * 1.04);
            if (ndr10PlusMonitor || nivukxVisionMonitor) {
                linear = naturalDynamicRangeToneMap(transformed, linear, nivukxVisionMonitor, monitorHighlight, shadowLift + 0.015);
            } else {
                linear = highlightRolloff(linear, monitorHighlight, highlightBiasScale * 1.08);
            }
            linear = bt2100HuePreservingDisplayMap(linear, pqMonitor, monitorHdrStrength, monitorHighlight);
            linear = applyNeoHighEndKneeCurve(linear, resolveNeoHighEndKneeParams(nivukxVisionMonitor, ndr10PlusMonitor, pqMonitor, hlgMonitor, false, false, monitorHighlight));
            if (ndr10PlusMonitor || nivukxVisionMonitor) {
                linear = ndrRedChannelOverflowGuard(linear, nivukxVisionMonitor);
                linear = ndrMeasuredFinalColorGuard(linear, nivukxVisionMonitor);
                linear = ndrDisplayColorVolumeFit(linear, nivukxVisionMonitor);
            }
            linear = bt2100SkinToneRedGuard(linear, hlgMonitor, pqMonitor);
            linear = chromaDenoise(linear, clamp(plannedChromaDenoise + 0.08, 0.18, 1.0), false);
            linear = rgbSpeckleSuppress(linear, clamp(plannedChromaDenoise * 0.88 + measuredNoise * 0.36 + 0.08, 0.16, 1.0), false);
            linear = darkNoiseFloorSuppress(linear, clamp(plannedChromaDenoise * 0.78 + measuredNoise * 0.40 + 0.08, 0.18, 1.0), false);
            float monitorSharpening = plannedSharpening <= 0.001 ? 0.0 : clamp(plannedSharpening + 0.03, 0.0, 1.0);
            if (ndr10PlusMonitor || nivukxVisionMonitor) {
                monitorSharpening = reduceArtificialNdrSharpening(monitorSharpening, linear);
            }
            linear = edgeAwareSharpen(transformed, linear, hlgMonitor, pqMonitor, contrast, saturation, monitorSharpening, false);
            linear = applySubjectGatedDetail(transformed, linear, hlgMonitor, pqMonitor, contrast, saturation, false);
            if (ndr10PlusMonitor || nivukxVisionMonitor) {
                linear = ndrMeasuredFinalColorGuard(linear, nivukxVisionMonitor);
                linear = ndrDisplayColorVolumeFit(linear, nivukxVisionMonitor);
            }
            outColor = vec4(applyJndBlueNoiseDither(clamp(linearToSrgb(linear), 0.0, 1.0), PREVIEW_JND_DITHER_STRENGTH, PREVIEW_QUANTIZATION_LEVELS), 1.0);
            return;
        }

        if (logMonitor) {
            float monitorLogStrength = max(logStrength, 0.85);
            float monitorHighlight = max(highlightCompression, 0.14);
            linear = shadowPreserve(linear, shadowLift + 0.02, shadowBiasScale * 1.04);
            linear = highlightRolloff(linear, monitorHighlight, highlightBiasScale * 1.08);
            linear = applyNeoHighEndKneeCurve(linear, resolveNeoHighEndKneeParams(false, false, false, false, true, false, monitorHighlight));
            linear = chromaDenoise(linear, clamp(plannedChromaDenoise + 0.08, 0.18, 1.0), false);
            linear = rgbSpeckleSuppress(linear, clamp(plannedChromaDenoise * 0.88 + measuredNoise * 0.36 + 0.08, 0.16, 1.0), false);
            linear = darkNoiseFloorSuppress(linear, clamp(plannedChromaDenoise * 0.78 + measuredNoise * 0.40 + 0.08, 0.18, 1.0), false);
            linear = edgeAwareSharpen(transformed, linear, false, false, contrast, saturation, clamp(plannedSharpening + 0.02, 0.0, 1.0), false);
            linear = applySubjectGatedDetail(transformed, linear, false, false, contrast, saturation, false);
            vec3 logEncoded = encodeLogByMode(linear, normalizeLogToneDeliveryMode(toneDeliveryMode));
            outColor = vec4(applyJndBlueNoiseDither(clamp(mix(linear, logEncoded, monitorLogStrength), 0.0, 1.0), LOG_PREVIEW_JND_DITHER_STRENGTH, PREVIEW_QUANTIZATION_LEVELS), 1.0);
            return;
        }

        if (normalProSdrDelivery) {
            linear = trueSdrHighlightShoulder(linear, highlightCompression);
        } else {
            // Legacy display looks retain their bounded creative blend, but never pre-amplify
            // highlights.  Normal/Pro SDR above is the quality-authoritative Rec.709 path.
            float displayToneMix = highlightCompression * 0.3;
            vec3 acesDisplayMap = neoAcesFittedRrtOdt(neoRec709ToAcesCg(linear));
            linear = mix(linear, mix(cinematicTonemap(linear), acesDisplayMap, 0.38), displayToneMix);
        }
        linear = applyLogPreview(linear, logStrength, shadowBiasScale, highlightBiasScale);
        // Do not stack the universal cinema knee on top of the true-SDR shoulder. Sensor highlight
        // headroom is protected by the SDR AE governor; this single hue-preserving display shoulder
        // then fits the remaining highlight range into Rec.709 without flattening midtones.
        if (!normalProSdrDelivery) {
            linear = applyNeoHighEndKneeCurve(linear, resolveNeoHighEndKneeParams(false, false, false, false, false, false, highlightCompression));
        }
        if (!computeEnhancedSdr) {
            linear = suppressRecordingDeliveryNoise(
                transformed,
                linear,
                false,
                false,
                false,
                false,
                contrast,
                saturation,
                plannedChromaDenoise,
                plannedSharpening,
                measuredNoise,
                normalProSdrQualityIntent
            );
        }
        linear = encodeSdrActiveTransport(linear);
        outColor = vec4(linear, 1.0);
        float sdrDitherStrength = normalProSdrDelivery ? DELIVERY_JND_DITHER_STRENGTH : PREVIEW_JND_DITHER_STRENGTH;
        float sdrQuantizationLevels = normalProSdrDelivery ? MASTER_QUANTIZATION_LEVELS : PREVIEW_QUANTIZATION_LEVELS;
        outColor.rgb = applyJndBlueNoiseDither(outColor.rgb, sdrDitherStrength, sdrQuantizationLevels);
        return;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // NIVUKX APEX DELIVERY PATH — Elite cinema pipeline (Vulkan native)
    //
    // Signal shaping in ICtCp-inspired perceptual domain.
    // Uses existing Vulkan shader infrastructure — no OpenGL.
    // Output: PQ ST2084 encoded for HEVC Main10 / NPV10 uncompressed.
    // ═══════════════════════════════════════════════════════════════════════
    if (apexMode) {
        color = applyContrastAndSaturation(color, contrast, saturation);


        // Decode to scene-linear
        vec3 linear = srgbToLinear(clamp(color, 0.0, 1.0));

        // ── ICtCp-inspired perceptual luma ────────────────────────────────
        // BT.2020 luma: Y = 0.2627R + 0.6780G + 0.0593B
        float Y = 0.2627 * linear.r + 0.6780 * linear.g + 0.0593 * linear.b;
        float Y_safe = max(Y, 1e-8);

        // Scene white from near-peak (shadowLift uniform repurposed as scene metric)
        float sceneWhite = max(0.18, clamp(highlightCompression + 0.18, 0.18, 1.0));
        float x = clamp(Y_safe / sceneWhite, 0.0, 1.0);

        // ── APEX Signal Shaping S(x) ──────────────────────────────────────
        float xMapped;
        if (apexSharp) {
            // S_apex_sharp: x^0.85 + smoothstep(0,0.28,x)*0.032*(1-x) - smoothstep(0.72,1,x)*0.014*x
            float base = pow(x, 0.85);
            float toe = smoothstep(0.0, 0.28, x) * 0.032 * (1.0 - x);
            float hs = smoothstep(0.72, 1.0, x) * 0.014 * x;
            xMapped = clamp(base + toe - hs, 0.0, 1.0);
        } else if (apexZero) {
            // S_apex_zero: x^0.96 + smoothstep(0,0.12,x)*0.008*(1-x)
            float base = pow(x, 0.96);
            float toe = smoothstep(0.0, 0.12, x) * 0.008 * (1.0 - x);
            xMapped = clamp(base + toe, 0.0, 1.0);
        } else {
            // S_apex_cinema: x^0.87 + smoothstep(0,0.30,x)*0.028*(1-x) - smoothstep(0.68,1,x)*0.022*x
            float base = pow(x, 0.87);
            float toe = smoothstep(0.0, 0.30, x) * 0.028 * (1.0 - x);
            float hs = smoothstep(0.68, 1.0, x) * 0.022 * x;
            xMapped = clamp(base + toe - hs, 0.0, 1.0);
        }

        // ── APEX v2 C1 piecewise perceptual shoulder ─────────────────────
        // APEX specific parameters are dynamically supplied via uGrade1 and uGrade2
        float sourcePeakNits = uGrade1.x;
        float targetPeakNits = uGrade1.y;
        float kneeRatio      = uGrade1.z;
        float shoulderStr    = uGrade1.w;
        float inputNits = xMapped * sourcePeakNits;
        float apexToneNoise = clamp(uGrade3.z, 0.0, 1.0);
        float apexToneMotion = clamp(uGrade3.w, 0.0, 1.0);
        float sourceNorm = clamp(inputNits / max(sourcePeakNits, 1.0), 0.0, 1.0);
        float toeStrengthApex = clamp(0.028 + 0.060 * clamp(uGrade2.x * 360.0, 0.0, 1.0) * (1.0 - apexToneNoise * 0.50), 0.018, 0.090);
        float toeEndApex = clamp(0.018 + toeStrengthApex, 0.018, 0.115);
        float kneeApex = clamp(kneeRatio, toeEndApex + 0.08, 0.86);
        float localApexTone = apexZero ? 0.0 : clamp(max(uGrade2.y, 0.0), 0.0, 1.0);
        float midSlopeApex = clamp(1.0 + localApexTone * 0.10 * (1.0 - apexToneMotion * 0.42) * (1.0 - apexToneNoise * 0.84) - 0.08 * apexToneMotion, 0.92, 1.18);

        float toeEndY = toeEndApex * (1.0 + toeStrengthApex * 0.38);
        float rawKneeY = toeEndY + (kneeApex - toeEndApex) * midSlopeApex;
        float kneeY = clamp(rawKneeY, kneeApex * 0.72, min(0.86, 1.0 - 0.08 * shoulderStr / 6.2));
        float mappedNorm;
        if (sourceNorm <= toeEndApex) {
            float tToe = clamp(sourceNorm / max(toeEndApex, 1e-6), 0.0, 1.0);
            float t2 = tToe * tToe;
            float t3 = t2 * tToe;
            float h00 = 2.0 * t3 - 3.0 * t2 + 1.0;
            float h10 = t3 - 2.0 * t2 + tToe;
            float h01 = -2.0 * t3 + 3.0 * t2;
            float h11 = t3 - t2;
            float m0 = (1.0 + toeStrengthApex * 0.65) * toeEndApex;
            float m1 = midSlopeApex * toeEndApex;
            mappedNorm = h00 * 0.0 + h10 * m0 + h01 * toeEndY + h11 * m1;
        } else if (sourceNorm <= kneeApex) {
            mappedNorm = toeEndY + (sourceNorm - toeEndApex) * midSlopeApex;
        } else {
            float span = max(1.0 - kneeApex, 1e-6);
            float t = clamp((sourceNorm - kneeApex) / span, 0.0, 1.0);
            float t2 = t * t;
            float t3 = t2 * t;
            float h00 = 2.0 * t3 - 3.0 * t2 + 1.0;
            float h10 = t3 - 2.0 * t2 + t;
            float h01 = -2.0 * t3 + 3.0 * t2;
            float h11 = t3 - t2;
            float availableSlope = (1.0 - kneeY) / span;
            float startSlope = min(midSlopeApex, availableSlope * 1.72);
            float endSlope = clamp(0.030 / max(shoulderStr, 1.0), 0.0, 0.018);
            mappedNorm = h00 * kneeY + h10 * startSlope * span + h01 * 1.0 + h11 * endSlope * span;
        }
        float mappedNits = clamp(mappedNorm * targetPeakNits, 0.0, targetPeakNits);
        float Y_mapped = mappedNits / max(targetPeakNits, 1e-6);

        // ── Luma-preserving RGB scale ─────────────────────────────────────
        float lumaScale = Y_mapped / max(x, 1e-8);
        vec3 scaled = linear * lumaScale;

        // ── Shadow Compression (noise-gated) ──────────────────────────────
        float apexShadowLift = uGrade2.x;
        float apexNoiseRisk = clamp(uGrade3.z, 0.0, 1.0);
        float apexMotionScore = clamp(uGrade3.w, 0.0, 1.0);
        float noiseGate = apexNoiseRisk;
        if (noiseGate > 0.35) apexShadowLift *= (1.0 - noiseGate * 0.5);
        float shadowBoost = apexShadowLift * smoothstep(0.0, 0.18, 1.0 - x);
        scaled += vec3(shadowBoost);

        // ── Detail Enhancement (9-tap strict IMAX Laplacian high-pass) ────
        ivec2 texSize = textureSize(uComputeEnhancedIntermediate, 0);
        vec2 texel = 1.0 / max(vec2(texSize), vec2(1.0));
        vec3 east  = srgbToLinear(texture(uComputeEnhancedIntermediate, transformed + vec2(texel.x, 0.0)).rgb);
        vec3 west  = srgbToLinear(texture(uComputeEnhancedIntermediate, transformed + vec2(-texel.x, 0.0)).rgb);
        vec3 north = srgbToLinear(texture(uComputeEnhancedIntermediate, transformed + vec2(0.0, texel.y)).rgb);
        vec3 south = srgbToLinear(texture(uComputeEnhancedIntermediate, transformed + vec2(0.0, -texel.y)).rgb);
        vec3 ne    = srgbToLinear(texture(uComputeEnhancedIntermediate, transformed + texel).rgb);
        vec3 nw    = srgbToLinear(texture(uComputeEnhancedIntermediate, transformed + vec2(-texel.x, texel.y)).rgb);
        vec3 se    = srgbToLinear(texture(uComputeEnhancedIntermediate, transformed + vec2(texel.x, -texel.y)).rgb);
        vec3 sw    = srgbToLinear(texture(uComputeEnhancedIntermediate, transformed - texel).rgb);

        vec3 blur9 = (linear * 4.40 + (east + west + north + south) * 1.26 + (ne + nw + se + sw) * 0.58) / 11.52;
        vec3 detail = linear - blur9;
        float signedDetailLuma = dot(detail, vec3(0.2627, 0.6780, 0.0593));
        float detailLuma = signedDetailLuma;

        // Sobel edge magnitude for edge-aware gain
        float eLuma = dot(east, vec3(0.2627, 0.6780, 0.0593));
        float wLuma = dot(west, vec3(0.2627, 0.6780, 0.0593));
        float nLuma = dot(north, vec3(0.2627, 0.6780, 0.0593));
        float sLuma = dot(south, vec3(0.2627, 0.6780, 0.0593));
        float edgeMag = clamp(sqrt(pow(eLuma - wLuma, 2.0) + pow(nLuma - sLuma, 2.0)) / 0.20, 0.0, 1.0);

        // Noise mask: local variance vs noise floor
        float localMean = (Y_safe + eLuma + wLuma + nLuma + sLuma) / 5.0;
        float localVar = (pow(Y_safe - localMean, 2.0) + pow(eLuma - localMean, 2.0) +
                          pow(wLuma - localMean, 2.0) + pow(nLuma - localMean, 2.0) +
                          pow(sLuma - localMean, 2.0)) / 5.0;
        float noiseFloor = 0.0004 + 0.0012 * noiseGate;
        float noiseMask = clamp(localVar / max(noiseFloor, 1e-8) - 0.8, 0.0, 1.0);

        // Mode-specific detail gain
        float detailGain = uGrade2.y;
        if (apexSharp || apexCinema) {
            detailGain *= edgeMag * (1.0 - noiseMask);
        } else if (apexZero) {
            detailGain *= noiseMask; // Detail gain is negative for ZERO mode to suppress noise
        }

        // Halo prevention: preserve signed detail and cap micro-contrast so
        // edges stay crisp without shiny AI-style halos.
        float detailLimit = mix(0.020, 0.006, noiseGate);
        detailLuma = clamp(detailLuma, -detailLimit, detailLimit);
        float darkHaloPotential = max(0.0, -detailLuma) * edgeMag;
        float shinePotential = max(0.0, detailLuma) * edgeMag * smoothstep(0.58, 1.0, x);
        float detailFinal;
        if (apexZero) {
            detailFinal = detailLuma * detailGain * 0.55;
        } else {
            detailFinal = detailLuma * (0.68 + detailGain * 0.55) -
                0.55 * darkHaloPotential -
                0.70 * shinePotential;
        }

        // Apply detail to luma only (preserve color).
        // X4/R4: the global edge-aware / halo-guarded / noise-gated detail lane is applied
        // UNIFORMLY across the whole frame so distant texture is sharpened identically to near
        // subjects. Previously this lane was zeroed wherever a subject-detail mask was present,
        // leaving only the nearest/subject region sharp while the background went soft.
        float profileDetailGate = 1.0;
        float Y_detailed = Y_mapped + detailFinal * 0.08 * profileDetailGate;
        float detailScale = 1.0 + clamp((Y_detailed - Y_mapped) / max(Y_mapped, 1e-8), -0.06, 0.08);
        scaled *= detailScale;

        // ── Color-Volume Preservation ─────────────────────────────────────
        float colorVolComp = uGrade2.z;
        float peakScale = clamp(targetPeakNits / max(sourcePeakNits, 1e-6), 0.05, 1.0);
        float chromaKeep = 1.0 - colorVolComp * (1.0 - peakScale);
        float Y_out = 0.2627 * scaled.r + 0.6780 * scaled.g + 0.0593 * scaled.b;
        scaled = vec3(Y_out) + (scaled - vec3(Y_out)) * chromaKeep;

        // ── Saturation Preservation ───────────────────────────────────────
        float satPres = uGrade2.w;
        float Y_final = 0.2627 * scaled.r + 0.6780 * scaled.g + 0.0593 * scaled.b;
        scaled = vec3(Y_final) + (scaled - vec3(Y_final)) * satPres;

        // ── Chroma Noise Suppression (motion-gated) ───────────────────────
        float chromaSupBase = uGrade3.y;
        float chromaSuppress = chromaSupBase *
            (0.65 + 0.35 * noiseGate) *
            (1.0 - apexMotionScore * 0.28);
        float chromaY = 0.2627 * scaled.r + 0.6780 * scaled.g + 0.0593 * scaled.b;
        vec3 chromaOnly = scaled - vec3(chromaY);
        scaled = vec3(chromaY) + chromaOnly * (1.0 - chromaSuppress);
        // X4/R4: removed the additive subject-only detail pass here — the forced-uniform global
        // detail lane above already sharpens the subject, so re-adding subject-gated detail would
        // double-sharpen the near subject and re-introduce the near/far asymmetry. (It was already
        // a no-op when no subject mask was uploaded.)
        scaled = applyNeoHighEndKneeCurve(scaled, resolveNeoHighEndKneeParams(apexZero, apexSharp || apexCinema, true, false, false, false, highlightCompression));

        // ── Output: PQ ST2084 encoding for HEVC Main10 / NPV10 ───────────
        scaled = clamp(scaled, 0.0, 1.0);
        outColor = vec4(applyJndBlueNoiseDither(peakNormalizedDeliveryPqEncode(scaled, targetPeakNits), DELIVERY_JND_DITHER_STRENGTH, MASTER_QUANTIZATION_LEVELS), 1.0);
        return;
    }

    // ═══════════════════════════════════════════════════════════════════════
    // TRUTHFUL DELIVERY PATH: standard transfer encoding with clean sensor-detail
    // filtering before HLG, PQ, or LOG output.
    // ═══════════════════════════════════════════════════════════════════════
    if (!neoNdrDelivery) {
        color = applyContrastAndSaturation(color, contrast, saturation);
        color *= uGrade1.rgb;
        color += uGrade2.rgb * 0.15;
    } else {
        color = clamp(color, 0.0, 1.0);
    }
    // Decode camera transfer function before linear processing.
    // LOG delivery starts from SDR camera RGB; HDR delivery keeps native HLG/PQ.
    vec3 linear = hlgDelivery ? bt2100HlgInverseOetf(clamp(color, 0.0, 1.0))
                : encodedPqDeliveryInput ? bt2100PqEotf(clamp(color, 0.0, 1.0))
                : srgbToLinear(clamp(color, 0.0, 1.0));
    linear = bt2100ColorVolumeGuard(linear, hlgDelivery, encodedPqDeliveryInput);
    if (ndr10PlusDelivery || nivukxVisionDelivery) {
        linear = finishRealtimeComputedNdrDelivery(
            transformed,
            linear,
            nivukxVisionDelivery
        );
        float ndrDeliveryDitherStrength = DELIVERY_JND_DITHER_STRENGTH;
        outColor = vec4(
            applyJndBlueNoiseDither(
                ndrDeliveryPqEncode(linear, nivukxVisionDelivery ? NIVUKX_TARGET_PEAK_NITS : NDR10_TARGET_PEAK_NITS),
                ndrDeliveryDitherStrength,
                MASTER_QUANTIZATION_LEVELS
            ),
            1.0
        );
        return;
    }
    vec3 neoDeliveryWorking = neoDisplayLinearToAcesCg(linear, hlgDelivery || encodedPqDeliveryInput);
    neoDeliveryWorking = applyNeoGamutOkLchPipeline(neoDeliveryWorking);
    linear = neoAcesCgToDisplayLinear(neoDeliveryWorking, hlgDelivery || encodedPqDeliveryInput);
    linear = oklabChromaBilateralFilter(transformed, linear, logDelivery ? 0.78 : 0.72);
    linear = oklabChromaSpeckleGuard(transformed, linear, logDelivery ? 0.76 : 0.70);
    linear = edgeAwareSpatialDenoise(
        transformed,
        linear,
        hlgDelivery,
        encodedPqDeliveryInput,
        contrast,
        saturation,
        deliverySpatialDenoise,
        false
    );
    linear = shadowPreserve(linear, shadowLift, shadowBiasScale);
    if (ndr10PlusDelivery || nivukxVisionDelivery) {
        linear = naturalDynamicRangeToneMap(transformed, linear, nivukxVisionDelivery, highlightCompression, shadowLift);
    } else {
        linear = highlightRolloff(linear, highlightCompression, highlightBiasScale);
    }
    linear = applyNeoHighEndKneeCurve(linear, resolveNeoHighEndKneeParams(nivukxVisionDelivery, ndr10PlusDelivery, pqDelivery, hlgDelivery, logDelivery, rawDelivery, highlightCompression));
    if (ndr10PlusDelivery || nivukxVisionDelivery) {
        linear = ndrRedChannelOverflowGuard(linear, nivukxVisionDelivery);
    }
    linear = bt2100SkinToneRedGuard(linear, hlgDelivery, pqDelivery);
    linear = suppressRecordingDeliveryNoise(
        transformed,
        linear,
        logDelivery,
        hlgDelivery,
        pqDelivery,
        rawDelivery,
        contrast,
        saturation,
        plannedChromaDenoise,
        plannedSharpening,
        measuredNoise,
        false
    );

    // Standard-correct encoding for each delivery mode
    if (logDelivery) outColor = vec4(applyJndBlueNoiseDither(encodeLogByMode(linear, normalizeLogToneDeliveryMode(toneDeliveryMode)), DELIVERY_JND_DITHER_STRENGTH, MASTER_QUANTIZATION_LEVELS), 1.0);
    else if (hlgDelivery) outColor = vec4(applyJndBlueNoiseDither(clamp(bt2100HlgOetf(linear), 0.0, 1.0), DELIVERY_JND_DITHER_STRENGTH, MASTER_QUANTIZATION_LEVELS), 1.0);
    else if (pqDelivery) outColor = vec4(applyJndBlueNoiseDither(clamp(bt2100PqInverseEotf(linear), 0.0, 1.0), DELIVERY_JND_DITHER_STRENGTH, MASTER_QUANTIZATION_LEVELS), 1.0);
    else outColor = vec4(applyJndBlueNoiseDither(clamp(linearToSrgb(linear), 0.0, 1.0), DELIVERY_JND_DITHER_STRENGTH, MASTER_QUANTIZATION_LEVELS), 1.0);
}
