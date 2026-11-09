#version 450
// NIVUKX_VISION_ELITE
// Vision look layer only: AP1 scene-linear fork in, AP1 scene-linear out.

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 outColor;

layout(binding = 0) uniform sampler2D uSceneAp1;
layout(push_constant) uniform VisionSolve {
    float p50Nits;
    float p99Nits;
    float peakNits;
    float cmaxScale;
} uSolve;

const mat3 AP1_TO_XYZ = mat3(
    0.6624541811, 0.2722287168, -0.0055746495,
    0.1340042065, 0.6740817658,  0.0040607335,
    0.1561876870, 0.0536895174,  1.0103391003
);

const mat3 XYZ_TO_AP1 = mat3(
     1.6410233797, -0.6636628587,  0.0117218943,
    -0.3248032942,  1.6153315917, -0.0082844420,
    -0.2364246952,  0.0167563477,  0.9883948585
);

float jzCurve(float x) {
    float v = max(x, 0.0) / 10000.0;
    float p = pow(v, 2610.0 / 16384.0);
    float n = (3424.0 / 4096.0 + (2413.0 / 128.0) * p) /
        (1.0 + (2392.0 / 128.0) * p);
    return pow(n, 1.7 * 2523.0 / 32.0);
}

float jzCurveInverse(float x) {
    float n = pow(clamp(x, 0.0, 1.0), 1.0 / (1.7 * 2523.0 / 32.0));
    float numerator = max(n - 3424.0 / 4096.0, 0.0);
    float denominator = max(2413.0 / 128.0 - (2392.0 / 128.0) * n, 1e-8);
    return pow(numerator / denominator, 16384.0 / 2610.0) * 10000.0;
}

vec3 ap1ToXyz(vec3 ap1) {
    return AP1_TO_XYZ * ap1;
}

vec3 xyzToAp1(vec3 xyz) {
    return XYZ_TO_AP1 * xyz;
}

vec3 xyzToJzAzBz(vec3 xyz) {
    float xp = 1.15 * xyz.x - 0.15 * xyz.z;
    float yp = 0.66 * xyz.y + 0.34 * xyz.x;
    float l = 0.41478972 * xp + 0.579999 * yp + 0.0146480 * xyz.z;
    float m = -0.2015100 * xp + 1.120649 * yp + 0.0531008 * xyz.z;
    float s = -0.0166008 * xp + 0.264800 * yp + 0.6684799 * xyz.z;
    float lp = jzCurve(l);
    float mp = jzCurve(m);
    float sp = jzCurve(s);
    float iz = 0.5 * (lp + mp);
    float jz = ((1.0 - 0.56) * iz) / max(1.0 - 0.56 * iz, 1e-6);
    float az = 3.524000 * lp - 4.066708 * mp + 0.542708 * sp;
    float bz = 0.199076 * lp + 1.096799 * mp - 1.295875 * sp;
    return vec3(max(jz, 0.0), az, bz);
}

vec3 jzAzBzToXyz(vec3 jzazbz) {
    float jz = jzazbz.x + 1.6295499532821566e-11;
    float iz = jz / max(1.0 - 0.56 + 0.56 * jz, 1e-6);
    float lp = iz + 0.13860504 * jzazbz.y + 0.05804732 * jzazbz.z;
    float mp = iz - 0.13860504 * jzazbz.y - 0.05804732 * jzazbz.z;
    float sp = iz - 0.09601924 * jzazbz.y - 0.81189190 * jzazbz.z;
    float l = jzCurveInverse(lp);
    float m = jzCurveInverse(mp);
    float s = jzCurveInverse(sp);
    float xp = 1.92422644 * l - 1.00479231 * m + 0.03765140 * s;
    float yp = 0.35031676 * l + 0.72648119 * m - 0.06538442 * s;
    float z = -0.09098281 * l - 0.31272829 * m + 1.52276656 * s;
    float x = (xp + 0.15 * z) / 1.15;
    float y = (yp - 0.34 * x) / 0.66;
    return vec3(x, y, z);
}

float cmax33x33Analytic(float jz, float hueRadians) {
    float hueWave = max(sin(hueRadians), 0.0);
    return clamp((0.20 + 0.14 * pow(1.0 - clamp(jz, 0.0, 1.0), 0.65) + 0.035 * hueWave) * uSolve.cmaxScale, 0.16, 0.42);
}

vec3 radialChromaCompressVision(vec3 jzazbz) {
    float c = length(jzazbz.yz);
    if (c <= 1e-7) return jzazbz;
    float h = atan(jzazbz.z, jzazbz.y);
    float cMax = cmax33x33Analytic(jzazbz.x, h);
    float rho = 0.85 + 0.10 * smoothstep(uSolve.p50Nits, uSolve.p99Nits, jzazbz.x * uSolve.peakNits);
    float knee = rho * cMax;
    float span = max((1.0 - rho) * cMax, 1e-6);
    float mapped = c <= knee ? c : knee + span * tanh((c - knee) / span);
    float scale = min(mapped, cMax) / c;
    // huePreservedExactly: az/bz are scaled radially, never rotated.
    return vec3(jzazbz.x, jzazbz.y * scale, jzazbz.z * scale);
}

float nearWhiteGuardScale(vec3 ap1) {
    float m = min(min(1.0 - ap1.r, 1.0 - ap1.g), 1.0 - ap1.b);
    float s = smoothstep(0.02, 0.10, m);
    return 0.15 + 0.85 * s;
}

void main() {
    vec3 ap1 = max(texture(uSceneAp1, vUv).rgb, vec3(0.0));
    vec3 jzazbz = xyzToJzAzBz(ap1ToXyz(ap1));
    vec3 compressed = radialChromaCompressVision(jzazbz);
    float guard = nearWhiteGuardScale(ap1);
    compressed.yz *= guard;
    outColor = vec4(clamp(xyzToAp1(jzAzBzToXyz(compressed)), vec3(0.0), vec3(65504.0)), 1.0);
}
