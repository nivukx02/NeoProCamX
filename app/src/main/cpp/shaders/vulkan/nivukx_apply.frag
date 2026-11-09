#version 450

#define NIVUKX_DR_ELITE 1

layout(location = 0) in vec2 vTexCoord;
layout(location = 0) out vec4 outColor;

layout(binding = 0) uniform sampler2D uLinearAces;
layout(binding = 1) uniform NivukxToneBlock {
    vec4 anchorsToeKneeShoulderKappa;
    vec4 gradeExposureSaturationContrastWarmth;
    vec4 kalmanState;
} tone;

const mat3 ACES_AP1_TO_XYZ = mat3(
    0.662454, 0.272229, -0.005575,
    0.134004, 0.674082,  0.004061,
    0.156188, 0.053690,  1.010339
);

const mat3 XYZ_TO_AP1 = mat3(
     1.641023, -0.663663,  0.011722,
    -0.324803,  1.615332, -0.008284,
    -0.236424,  0.016756,  0.988395
);

vec3 jzazbzApproxFromAces(vec3 acesRgb) {
    vec3 xyz = max(ACES_AP1_TO_XYZ * acesRgb, vec3(0.0));
    float jz = pow(max(xyz.y, 0.0), 0.1593017578125);
    float az = xyz.x - xyz.y;
    float bz = xyz.z - xyz.y;
    return vec3(jz, az, bz);
}

vec3 acesFromJzazbzApprox(vec3 jzazbz) {
    float y = pow(max(jzazbz.x, 0.0), 1.0 / 0.1593017578125);
    vec3 xyz = vec3(jzazbz.y + y, y, jzazbz.z + y);
    return max(XYZ_TO_AP1 * xyz, vec3(0.0));
}

vec4 kalmanSmoothAnchors(vec4 currentAnchors, vec4 kalman) {
    float confidence = clamp(kalman.w, 0.0, 1.0);
    return mix(currentAnchors, vec4(kalman.xyz, currentAnchors.w), confidence * 0.25);
}

float nivukxThreeAnchorToneMap(float luminanceNits, vec4 anchors) {
    // T(L) toe knee shoulder: toe power, identity mid-band, tanh shoulder.
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
    float mapped = chroma <= knee ? chroma : knee + (boundary - knee) * tanh((chroma - knee) / max(boundary - knee, 0.000001));
    return vec3(jzazbz.x, jzazbz.yz * min(mapped, boundary) / chroma);
}

vec3 encodePqLike(vec3 linearRgb) {
    vec3 x = clamp(linearRgb, vec3(0.0), vec3(1.0));
    const float m1 = 2610.0 / 16384.0;
    const float m2 = 2523.0 / 32.0;
    const float c1 = 3424.0 / 4096.0;
    const float c2 = 2413.0 / 128.0;
    const float c3 = 2392.0 / 128.0;
    vec3 y = pow(x, vec3(m1));
    return pow((c1 + c2 * y) / (1.0 + c3 * y), vec3(m2));
}

void main() {
    vec3 linearAces = max(texture(uLinearAces, vTexCoord).rgb, vec3(0.0));
    vec4 anchors = kalmanSmoothAnchors(tone.anchorsToeKneeShoulderKappa, tone.kalmanState);
    vec3 jab = jzazbzApproxFromAces(linearAces);
    float inputNits = jab.x * 10000.0;
    float mappedNits = nivukxThreeAnchorToneMap(inputNits, anchors);
    jab.x = mappedNits / 10000.0;
    jab.yz *= tone.gradeExposureSaturationContrastWarmth.y;
    jab = radialChromaCompress(jab, 0.90, 0.42);
    vec3 mappedAces = acesFromJzazbzApprox(jab);
    outColor = vec4(encodePqLike(mappedAces), 1.0);
}
