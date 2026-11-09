#version 320 es
// ═══════════════════════════════════════════════════════════════════════════════
// TASK 2 — NIVUKX APEX ICtCp COLOR SPACE CONVERSION SHADER
// ═══════════════════════════════════════════════════════════════════════════════
//
// Forward: Linear BT.2020 RGB → LMS → PQ(LMS) → ICtCp
// Inverse: ICtCp → PQ(LMS) → Linear LMS → Linear BT.2020 RGB
//
// All math FP16 (highp). Exact constants from ITU-R BT.2100.
// Reference: BT.2100 ICtCp perceptual transform.
// ═══════════════════════════════════════════════════════════════════════════════

precision highp float;
precision highp sampler2D;

layout(location = 0) in vec2 vTexCoord;
layout(location = 0) out vec4 outColor;

layout(binding = 0) uniform sampler2D u_inputTexture;

// Direction: 0 = forward (RGB→ICtCp), 1 = inverse (ICtCp→RGB)
layout(std140, binding = 1) uniform ApexIctcpParams {
    int u_direction;
};

// ── PQ (ST2084) Constants ─────────────────────────────────────────────────
const highp float PQ_M1 = 0.1593017578125;          // 2610/(4096*4)
const highp float PQ_M2 = 78.84375;                  // 2523*128/4096
const highp float PQ_C1 = 0.8359375;                 // 3424/4096
const highp float PQ_C2 = 18.8515625;                // 2413/128
const highp float PQ_C3 = 18.6875;                   // 2392/128

// ── BT.2020 RGB → LMS matrix (row-major) ─────────────────────────────────
// | L |   | 1688  2146   467 |   | R |
// | M | = | 1880  2048   444 | × | G | / 4096
// | S |   |  756   502  3994 |   | B |
const highp mat3 RGB_TO_LMS = mat3(
    1688.0/4096.0, 1880.0/4096.0,  756.0/4096.0,  // column 0
    2146.0/4096.0, 2048.0/4096.0,  502.0/4096.0,  // column 1
     467.0/4096.0,  444.0/4096.0, 3994.0/4096.0   // column 2
);

// ── PQ LMS → ICtCp matrix ────────────────────────────────────────────────
// I  =  0.5000 * L' + 0.5000 * M'
// Ct = (6610*L' - 13613*M' + 7003*S') / 4096
// Cp = (17933*L' - 17390*M' - 543*S') / 4096
const highp mat3 PQ_LMS_TO_ICTCP = mat3(
       0.5,          6610.0/4096.0,  17933.0/4096.0,  // column 0
       0.5,        -13613.0/4096.0, -17390.0/4096.0,  // column 1
       0.0,          7003.0/4096.0,   -543.0/4096.0   // column 2
);

// ── ICtCp → PQ LMS inverse coefficients ──────────────────────────────────
// L' = I + 0.008609 * Ct + 0.111030 * Cp
// M' = I - 0.008609 * Ct - 0.111030 * Cp
// S' = I + 0.560031 * Ct - 0.320627 * Cp
const highp float INV_CT_L = 0.008609;
const highp float INV_CP_L = 0.111030;
const highp float INV_CT_S = 0.560031;
const highp float INV_CP_S = 0.320627;

// ── LMS → Linear BT.2020 RGB inverse matrix ──────────────────────────────
// | R |   |  3.4366 -2.5064  0.0698 |   | L |
// | G | = | -0.7913  1.9836 -0.1923 | × | M |
// | B |   | -0.0259 -0.0989  1.1248 |   | S |
const highp mat3 LMS_TO_RGB = mat3(
     3.4366, -0.7913, -0.0259,  // column 0
    -2.5064,  1.9836, -0.0989,  // column 1
     0.0698, -0.1923,  1.1248   // column 2
);

// ═══════════════════════════════════════════════════════════════════════════
// PQ OETF: Linear nits → PQ encoded [0,1]
//
// Ym = (x / 10000)^m1
// PQ = ((c1 + c2*Ym) / (1 + c3*Ym))^m2
// ═══════════════════════════════════════════════════════════════════════════
highp float pq_oetf(highp float linearNits) {
    highp float x = max(linearNits, 0.0) / 10000.0;
    highp float ym = pow(x, PQ_M1);
    highp float num = PQ_C1 + PQ_C2 * ym;
    highp float den = 1.0 + PQ_C3 * ym;
    return pow(num / den, PQ_M2);
}

// ═══════════════════════════════════════════════════════════════════════════
// PQ EOTF: PQ encoded [0,1] → Linear nits
//
// Xp = x^(1/m2)
// Lin = 10000 * max(Xp - c1, 0) / (c2 - c3*Xp)
// ═══════════════════════════════════════════════════════════════════════════
highp float pq_eotf(highp float pqValue) {
    highp float xp = pow(clamp(pqValue, 0.0, 1.0), 1.0 / PQ_M2);
    highp float num = max(xp - PQ_C1, 0.0);
    highp float den = PQ_C2 - PQ_C3 * xp;
    return 10000.0 * num / max(den, 1e-6);
}

// ═══════════════════════════════════════════════════════════════════════════
// FORWARD: Linear BT.2020 RGB → ICtCp
// ═══════════════════════════════════════════════════════════════════════════
highp vec3 linearBT2020_to_ICtCp(highp vec3 rgb) {
    // Step 1: RGB → LMS (absolute nits)
    highp vec3 lms = RGB_TO_LMS * rgb;

    // Step 2: LMS → PQ-encoded LMS
    highp vec3 pqLms = vec3(
        pq_oetf(lms.x),
        pq_oetf(lms.y),
        pq_oetf(lms.z)
    );

    // Step 3: PQ LMS → ICtCp
    return PQ_LMS_TO_ICTCP * pqLms;
}

// ═══════════════════════════════════════════════════════════════════════════
// INVERSE: ICtCp → Linear BT.2020 RGB
// ═══════════════════════════════════════════════════════════════════════════
highp vec3 ICtCp_to_linearBT2020(highp vec3 ictcp) {
    highp float I  = ictcp.x;
    highp float Ct = ictcp.y;
    highp float Cp = ictcp.z;

    // Step 1: ICtCp → PQ LMS
    highp float Lp = I + INV_CT_L * Ct + INV_CP_L * Cp;
    highp float Mp = I - INV_CT_L * Ct - INV_CP_L * Cp;
    highp float Sp = I + INV_CT_S * Ct - INV_CP_S * Cp;

    // Step 2: PQ → Linear LMS (nits)
    highp vec3 lms = vec3(
        pq_eotf(Lp),
        pq_eotf(Mp),
        pq_eotf(Sp)
    );

    // Step 3: LMS → Linear BT.2020 RGB
    // Clamp for FP16 safety: max = 65504.0
    return clamp(LMS_TO_RGB * lms, 0.0, 65504.0);
}

// ═══════════════════════════════════════════════════════════════════════════
// APEX SIGNAL SHAPING FUNCTIONS
// ═══════════════════════════════════════════════════════════════════════════

// Hermite smoothstep — zero derivative at both edges
highp float smoothstep_neo(highp float e0, highp float e1, highp float x) {
    highp float t = clamp((x - e0) / (e1 - e0), 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}

// Exponential shoulder — asymptotic approach to 1.0
highp float shoulder_fn(highp float u, highp float a) {
    return 1.0 - exp(-a * clamp(u, 0.0, 1.0));
}

// S_apex_sharp(x): x^0.85 + toe - highlight_subtraction
highp float S_apex_sharp(highp float x) {
    highp float xc = clamp(x, 0.0, 1.0);
    highp float base = pow(xc, 0.85);
    highp float toe = smoothstep_neo(0.0, 0.28, xc) * 0.032 * (1.0 - xc);
    highp float hs = smoothstep_neo(0.72, 1.0, xc) * 0.014 * xc;
    return clamp(base + toe - hs, 0.0, 1.0);
}

// S_apex_zero(x): x^0.96 + minimal toe
highp float S_apex_zero(highp float x) {
    highp float xc = clamp(x, 0.0, 1.0);
    highp float base = pow(xc, 0.96);
    highp float toe = smoothstep_neo(0.0, 0.12, xc) * 0.008 * (1.0 - xc);
    return clamp(base + toe, 0.0, 1.0);
}

// S_apex_cinema(x): x^0.87 + silky toe - film highlight rolloff
highp float S_apex_cinema(highp float x) {
    highp float xc = clamp(x, 0.0, 1.0);
    highp float base = pow(xc, 0.87);
    highp float toe = smoothstep_neo(0.0, 0.30, xc) * 0.028 * (1.0 - xc);
    highp float hs = smoothstep_neo(0.68, 1.0, xc) * 0.022 * xc;
    return clamp(base + toe - hs, 0.0, 1.0);
}

void main() {
    highp vec4 inputSample = texture(u_inputTexture, vTexCoord);

    if (u_direction == 0) {
        // Forward: Linear BT.2020 RGB → ICtCp
        highp vec3 ictcp = linearBT2020_to_ICtCp(inputSample.rgb);
        outColor = vec4(ictcp, inputSample.a);
    } else {
        // Inverse: ICtCp → Linear BT.2020 RGB
        highp vec3 rgb = ICtCp_to_linearBT2020(inputSample.rgb);
        outColor = vec4(rgb, inputSample.a);
    }
}
