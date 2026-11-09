#version 320 es
// ═══════════════════════════════════════════════════════════════════════════════
// TASK 3 — GAUSSIAN BLUR (SEPARABLE 5-TAP)
// ═══════════════════════════════════════════════════════════════════════════════
//
// Separable 1D Gaussian blur for Laplacian pyramid construction.
// Run as 2-pass: horizontal then vertical.
// Operates on I (intensity) channel of ICtCp only.
//
// Kernel weights for 3 sigma levels:
//   sigma=1.2: [0.0625, 0.2500, 0.3750, 0.2500, 0.0625]
//   sigma=2.4: [0.1065, 0.2270, 0.3330, 0.2270, 0.1065]
//   sigma=4.8: [0.1353, 0.2160, 0.2974, 0.2160, 0.1353]
// ═══════════════════════════════════════════════════════════════════════════════

precision highp float;
precision highp sampler2D;

layout(location = 0) in vec2 vTexCoord;
layout(location = 0) out vec4 outColor;

layout(binding = 0) uniform sampler2D u_inputTexture;

layout(std140, binding = 1) uniform ApexBlurParams {
    vec2 u_direction;   // (1/w, 0) for horizontal; (0, 1/h) for vertical
    int  u_sigmaLevel;  // 0=sigma1.2, 1=sigma2.4, 2=sigma4.8
};

// ── Pre-computed kernel weights (normalized) ──────────────────────────────
// sigma=1.2
const highp float w12[5] = float[5](0.0625, 0.2500, 0.3750, 0.2500, 0.0625);
// sigma=2.4
const highp float w24[5] = float[5](0.1065, 0.2270, 0.3330, 0.2270, 0.1065);
// sigma=4.8
const highp float w48[5] = float[5](0.1353, 0.2160, 0.2974, 0.2160, 0.1353);

void main() {
    highp float result = 0.0;
    highp float ctResult = 0.0;
    highp float cpResult = 0.0;

    for (int i = -2; i <= 2; i++) {
        vec2 offset = u_direction * float(i);
        highp vec3 sampledColor = texture(u_inputTexture, vTexCoord + offset).xyz;
        highp float w;

        if (u_sigmaLevel == 0)       w = w12[i + 2];
        else if (u_sigmaLevel == 1)  w = w24[i + 2];
        else                         w = w48[i + 2];

        result   += sampledColor.x * w;  // I channel
        ctResult += sampledColor.y * w;  // Ct passthrough (for convenience)
        cpResult += sampledColor.z * w;  // Cp passthrough
    }

    outColor = vec4(result, ctResult, cpResult, 1.0);
}
