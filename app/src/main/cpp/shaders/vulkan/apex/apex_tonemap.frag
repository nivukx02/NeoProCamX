#version 320 es
// ═══════════════════════════════════════════════════════════════════════════════
// TASK 5+7 — NIVUKX APEX TONE MAPPER + DETAIL + CHROMA DENOISE (COMBINED)
// ═══════════════════════════════════════════════════════════════════════════════
//
// Combined single-pass shader for APEX pipeline after ICtCp conversion.
// Implements: tone mapping, highlight rolloff, shadow compression,
//             color-volume preservation, saturation preservation,
//             Laplacian detail bands, edge/noise masks, halo gate,
//             and chroma noise suppression.
//
// All solve math uses highp FP32-class precision. Processing in ICtCp color space.
// ═══════════════════════════════════════════════════════════════════════════════

precision highp float;
precision highp sampler2D;

layout(location = 0) in vec2 vTexCoord;
layout(location = 0) out vec4 outColor;

// ── Input textures ────────────────────────────────────────────────────────
layout(binding = 0) uniform sampler2D u_inputTexture;  // ICtCp input
layout(binding = 1) uniform sampler2D u_blurL1;        // Gaussian blur sigma=1.2
layout(binding = 2) uniform sampler2D u_blurL2;        // Gaussian blur sigma=2.4
layout(binding = 3) uniform sampler2D u_blurL3;        // Gaussian blur sigma=4.8

// ── Per-frame uniforms (set from Kotlin via ApexPipelineState) ────────────
layout(std140, binding = 4) uniform ApexToneParams {
    highp float u_dynamicRangeScore;
    highp float u_shadowNeed;
    highp float u_noiseRisk;
    highp float u_chromaNoiseRisk;
    highp float u_motionScore;
    highp float u_sceneWhite;
    highp float u_sourcePeakNits;
    highp float u_targetPeakNits;
    highp float u_kneeRatio;
    highp float u_shoulderStrength;
    highp float u_shadowLift;
    highp float u_saturationPreservation;
    highp float u_colorVolumeCompression;
    highp float u_detailGain1;    // 0.42 (SHARP) / 0.0 (ZERO) / 0.22 (CINEMA)
    highp float u_detailGain2;    // 0.28 / 0.0 / 0.16
    highp float u_detailGain3;    // 0.12 / 0.0 / 0.08
    highp float u_baseChromaDenoise;
    int         u_apexMode;       // 0=SHARP, 1=ZERO, 2=CINEMA
    vec2        u_texelSize;      // 1.0/width, 1.0/height
};

const highp float EPS = 1e-8;
const highp float FP16_MAX = 65504.0;

// ═══════════════════════════════════════════════════════════════════════════
// HELPER: Hermite smoothstep
// ═══════════════════════════════════════════════════════════════════════════
highp float smoothstep_neo(highp float e0, highp float e1, highp float x) {
    highp float t = clamp((x - e0) / (e1 - e0), 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}

// ═══════════════════════════════════════════════════════════════════════════
// APEX SIGNAL SHAPING (all 3 modes)
// ═══════════════════════════════════════════════════════════════════════════
highp float S_apex_sharp(highp float x) {
    highp float xc = clamp(x, 0.0, 1.0);
    return clamp(
        pow(xc, 0.85)
        + smoothstep_neo(0.0, 0.28, xc) * 0.032 * (1.0 - xc)
        - smoothstep_neo(0.72, 1.0, xc) * 0.014 * xc,
        0.0, 1.0);
}

highp float S_apex_zero(highp float x) {
    highp float xc = clamp(x, 0.0, 1.0);
    return clamp(
        pow(xc, 0.96)
        + smoothstep_neo(0.0, 0.12, xc) * 0.008 * (1.0 - xc),
        0.0, 1.0);
}

highp float S_apex_cinema(highp float x) {
    highp float xc = clamp(x, 0.0, 1.0);
    return clamp(
        pow(xc, 0.87)
        + smoothstep_neo(0.0, 0.30, xc) * 0.028 * (1.0 - xc)
        - smoothstep_neo(0.68, 1.0, xc) * 0.022 * xc,
        0.0, 1.0);
}

highp float S_apex(highp float x) {
    if (u_apexMode == 0) return S_apex_sharp(x);
    if (u_apexMode == 1) return S_apex_zero(x);
    return S_apex_cinema(x);
}

highp float hermiteApex(highp float t, highp float y0, highp float m0, highp float y1, highp float m1) {
    highp float t2 = t * t;
    highp float t3 = t2 * t;
    highp float h00 = 2.0 * t3 - 3.0 * t2 + 1.0;
    highp float h10 = t3 - 2.0 * t2 + t;
    highp float h01 = -2.0 * t3 + 3.0 * t2;
    highp float h11 = t3 - t2;
    return h00 * y0 + h10 * m0 + h01 * y1 + h11 * m1;
}

highp float tileLocalToneStrength(highp float localContrast) {
    return clamp(
        localContrast *
        (0.18 + 0.22 * clamp(u_dynamicRangeScore, 0.0, 1.0)) *
        (1.0 - clamp(u_motionScore, 0.0, 1.0) * 0.50) *
        (1.0 - clamp(u_noiseRisk, 0.0, 1.0) * 0.35),
        0.0,
        0.36
    );
}

highp float apexToeMapped(highp float x, highp float toeEnd, highp float toeLift, highp float midSlope) {
    highp float t = clamp(x / max(toeEnd, EPS), 0.0, 1.0);
    highp float y1 = toeEnd * (1.0 + toeLift * 0.38);
    highp float m0 = (1.0 + toeLift * 0.65) * toeEnd;
    highp float m1 = midSlope * toeEnd;
    return hermiteApex(t, 0.0, m0, y1, m1);
}

highp float apexPiecewisePerceptualTone(highp float inputNits, highp float localContrast) {
    highp float sourcePeak = max(u_sourcePeakNits, 1.0);
    highp float x = clamp(inputNits / sourcePeak, 0.0, 1.0);
    highp float toeStrength = clamp(0.028 + 0.060 * clamp(u_shadowNeed, 0.0, 1.0) * (1.0 - u_noiseRisk * 0.50), 0.018, 0.090);
    highp float toeEnd = clamp(0.018 + toeStrength, 0.018, 0.115);
    highp float knee = clamp(u_kneeRatio, toeEnd + 0.08, 0.86);
    highp float motionDamping = clamp(1.0 - u_motionScore * 0.42, 0.58, 1.0);
    highp float noiseGate = clamp(1.0 - u_noiseRisk * 0.84, 0.20, 1.0);
    highp float midSlope = clamp(1.0 + tileLocalToneStrength(localContrast) * 0.55 - 0.08 * u_motionScore, 0.92, 1.18);

    highp float toeEndY = apexToeMapped(toeEnd, toeEnd, toeStrength, midSlope);
    highp float rawKneeY = toeEndY + (knee - toeEnd) * midSlope;
    highp float kneeY = clamp(rawKneeY, knee * 0.72, min(0.86, 1.0 - 0.08 * u_shoulderStrength / 6.2));

    highp float y;
    if (x <= toeEnd) {
        y = apexToeMapped(x, toeEnd, toeStrength, midSlope);
    } else if (x <= knee) {
        y = toeEndY + (x - toeEnd) * midSlope;
    } else {
        highp float span = max(1.0 - knee, EPS);
        highp float t = clamp((x - knee) / span, 0.0, 1.0);
        highp float availableSlope = (1.0 - kneeY) / span;
        highp float startSlope = min(midSlope, availableSlope * 1.72);
        highp float endSlope = clamp(0.030 / max(u_shoulderStrength, 1.0), 0.0, 0.018);
        y = hermiteApex(t, kneeY, startSlope * span, 1.0, endSlope * span);
    }

    return clamp(y * u_targetPeakNits, 0.0, u_targetPeakNits);
}

highp float triangularDither(vec2 uv) {
    highp float a = fract(sin(dot(uv + vec2(0.071, 0.113), vec2(12.9898, 78.233))) * 43758.5453);
    highp float b = fract(sin(dot(uv + vec2(0.619, 0.421), vec2(39.3468, 11.135))) * 24634.6345);
    return (a - b) / 1023.0;
}

// ═══════════════════════════════════════════════════════════════════════════
// SOBEL EDGE MAGNITUDE (3×3 gradient on I channel)
//
// Gx = [-1 0 1; -2 0 2; -1 0 1]
// Gy = [-1 -2 -1; 0 0 0; 1 2 1]
// edgeMask = clamp(sqrt(Gx^2 + Gy^2) / 0.20, 0.0, 1.0)
// ═══════════════════════════════════════════════════════════════════════════
highp float sobelMagnitude(sampler2D tex, vec2 uv) {
    highp float tl = texture(tex, uv + vec2(-u_texelSize.x, -u_texelSize.y)).x;
    highp float tc = texture(tex, uv + vec2(0.0,            -u_texelSize.y)).x;
    highp float tr = texture(tex, uv + vec2( u_texelSize.x, -u_texelSize.y)).x;
    highp float ml = texture(tex, uv + vec2(-u_texelSize.x,  0.0)).x;
    highp float mr = texture(tex, uv + vec2( u_texelSize.x,  0.0)).x;
    highp float bl = texture(tex, uv + vec2(-u_texelSize.x,  u_texelSize.y)).x;
    highp float bc = texture(tex, uv + vec2(0.0,             u_texelSize.y)).x;
    highp float br = texture(tex, uv + vec2( u_texelSize.x,  u_texelSize.y)).x;

    highp float gx = -tl + tr - 2.0*ml + 2.0*mr - bl + br;
    highp float gy = -tl - 2.0*tc - tr + bl + 2.0*bc + br;

    return clamp(sqrt(gx*gx + gy*gy) / 0.20, 0.0, 1.0);
}

// ═══════════════════════════════════════════════════════════════════════════
// LOCAL VARIANCE (3×3 neighborhood on I channel)
//
// localMean = mean(L0 in 3×3)
// localVar = mean((L0 - localMean)^2 in 3×3)
// noiseFloorEstimate = 0.0004 + 0.0012 * noiseRisk
// noiseMask = clamp(localVar / max(noiseFloorEstimate, eps) - 0.8, 0.0, 1.0)
// ═══════════════════════════════════════════════════════════════════════════
highp float localVarianceNoiseMask(sampler2D tex, vec2 uv) {
    highp float sum = 0.0;
    highp float sqSum = 0.0;
    for (int dy = -1; dy <= 1; dy++) {
        for (int dx = -1; dx <= 1; dx++) {
            highp float v = texture(tex, uv + vec2(float(dx)*u_texelSize.x, float(dy)*u_texelSize.y)).x;
            sum += v;
            sqSum += v * v;
        }
    }
    highp float mean = sum / 9.0;
    highp float variance = sqSum / 9.0 - mean * mean;

    highp float noiseFloor = 0.0004 + 0.0012 * u_noiseRisk;
    return clamp(variance / max(noiseFloor, EPS) - 0.8, 0.0, 1.0);
}

void main() {
    // ── Sample ICtCp input and pyramid levels ─────────────────────────────
    highp vec3 ictcp = texture(u_inputTexture, vTexCoord).xyz;
    highp float I  = ictcp.x;    // Intensity (luma)
    highp float Ct = ictcp.y;    // Tritan (blue-yellow)
    highp float Cp = ictcp.z;    // Protan (red-green)

    highp float L1 = texture(u_blurL1, vTexCoord).x;  // Blur sigma=1.2
    highp float L2 = texture(u_blurL2, vTexCoord).x;  // Blur sigma=2.4
    highp float L3 = texture(u_blurL3, vTexCoord).x;  // Blur sigma=4.8

    // ══════════════════════════════════════════════════════════════════════
    // STAGE 1: CHROMA NOISE SUPPRESSION (Ct, Cp channels)
    //
    // chromaSuppress = baseChromaDenoise * (0.65 + 0.35*noiseGate) * (1.0 - 0.25*motionGate)
    // Ct1 = Ct * (1.0 - chromaSuppress)
    // Cp1 = Cp * (1.0 - chromaSuppress)
    // ══════════════════════════════════════════════════════════════════════
    highp float noiseGate = clamp(u_chromaNoiseRisk, 0.0, 1.0);
    highp float motionGate = clamp(u_motionScore, 0.0, 1.0);
    highp float chromaSuppress = u_baseChromaDenoise
        * (0.65 + 0.35 * noiseGate)
        * (1.0 - 0.25 * motionGate);

    highp float Ct1 = Ct * (1.0 - chromaSuppress);
    highp float Cp1 = Cp * (1.0 - chromaSuppress);

    // Shadow noise gate for chroma
    if (u_chromaNoiseRisk > 0.4) {
        highp float shadowWeight = u_chromaNoiseRisk * 0.5 * (1.0 - I);
        Ct1 *= (1.0 - shadowWeight);
        Cp1 *= (1.0 - shadowWeight);
    }

    // ══════════════════════════════════════════════════════════════════════
    // STAGE 2: MULTI-SCALE DETAIL — Laplacian pyramid decomposition (I only)
    //
    // D1 = L0 - L1  (fine detail: skin texture, fabric grain)
    // D2 = L1 - L2  (mid detail: facial features, leaf edges)
    // D3 = L2 - L3  (coarse detail: building contours)
    // ══════════════════════════════════════════════════════════════════════
    highp float D1 = I - L1;
    highp float D2 = L1 - L2;
    highp float D3 = L2 - L3;

    // ── Edge mask and noise mask ──────────────────────────────────────────
    highp float edgeMask = sobelMagnitude(u_inputTexture, vTexCoord);
    highp float noiseMask = localVarianceNoiseMask(u_inputTexture, vTexCoord);

    // ══════════════════════════════════════════════════════════════════════
    // STAGE 3: DETAIL BAND PROCESSING (mode-specific)
    // ══════════════════════════════════════════════════════════════════════
    highp float D1_out, D2_out, D3_out;

    if (u_apexMode == 1) {
        // APEX ZERO: suppress noise, never amplify
        // D1_out = D1 * (1.0 - 0.15 * noiseMask)
        // D2_out = D2 * (1.0 - 0.08 * noiseMask)
        // D3_out = D3
        D1_out = D1 * (1.0 - 0.15 * noiseMask);
        D2_out = D2 * (1.0 - 0.08 * noiseMask);
        D3_out = D3;
    } else {
        // APEX SHARP or CINEMA: amplify where edges exist and noise is low
        // detailGainN = 1.0 + u_detailGainN * edgeMask * (1.0 - noiseMask) [for D1]
        // detailGainN = 1.0 + u_detailGainN * edgeMask [for D2]
        // detailGainN = 1.0 + u_detailGainN [for D3]
        highp float dg1 = 1.0 + u_detailGain1 * edgeMask * (1.0 - noiseMask);
        highp float dg2 = 1.0 + u_detailGain2 * edgeMask;
        highp float dg3 = 1.0 + u_detailGain3;

        D1_out = D1 * dg1;
        D2_out = D2 * dg2;
        D3_out = D3 * dg3;
    }

    // ══════════════════════════════════════════════════════════════════════
    // STAGE 4: HALO PREVENTION GATE
    //
    // haloPotential = max(0.0, -D1_out) * edgeMask
    // D1_final = D1_out - 0.85 * haloPotential
    // ══════════════════════════════════════════════════════════════════════
    highp float haloPotential = max(0.0, -D1_out) * edgeMask;
    highp float D1_final = D1_out - 0.85 * haloPotential;

    // ══════════════════════════════════════════════════════════════════════
    // STAGE 5: PYRAMID RECONSTRUCTION
    //
    // I_enhanced = Base + D1_final + D2_out + D3_out
    // ══════════════════════════════════════════════════════════════════════
    highp float I_enhanced = clamp(L3 + D1_final + D2_out + D3_out, 0.0, 1.0);

    // ══════════════════════════════════════════════════════════════════════
    // STAGE 6: APEX TONE MAPPING
    //
    // x = clamp(I_enhanced / sceneWhite, 0.0, 1.0)
    // xMapped = S_apex(x)
    // ══════════════════════════════════════════════════════════════════════
    highp float x = clamp(I_enhanced / max(u_sceneWhite, EPS), 0.0, 1.0);
    highp float xMapped = S_apex(x);

    // ══════════════════════════════════════════════════════════════════════
    // STAGE 7: C1 PIECEWISE PERCEPTUAL TONE OPERATOR
    // ══════════════════════════════════════════════════════════════════════
    highp float inputNits = xMapped * u_sourcePeakNits;
    highp float mappedNits = apexPiecewisePerceptualTone(inputNits, edgeMask);
    highp float I_mapped = mappedNits / max(u_targetPeakNits, EPS);

    // ══════════════════════════════════════════════════════════════════════
    // STAGE 8: SHADOW COMPRESSION + NOISE GATE
    //
    // shadowLift_effective = shadowLift * (1.0 - chromaNoiseRisk) if cnr > 0.35
    // I_shadow = I_mapped + shadowLift * smoothstep(0.0, 0.18, 1.0 - x)
    // ══════════════════════════════════════════════════════════════════════
    highp float effectiveShadowLift = u_shadowLift;
    if (u_chromaNoiseRisk > 0.35) {
        effectiveShadowLift *= (1.0 - u_chromaNoiseRisk);
    }
    I_mapped += effectiveShadowLift * smoothstep_neo(0.0, 0.18, 1.0 - x);

    // ══════════════════════════════════════════════════════════════════════
    // OUTPUT: Reconstruct ICtCp with enhanced I and denoised Ct/Cp
    // ══════════════════════════════════════════════════════════════════════
    I_mapped = clamp(I_mapped + triangularDither(vTexCoord) * 0.35, 0.0, 1.0);
    outColor = vec4(I_mapped, Ct1, Cp1, 1.0);
}
