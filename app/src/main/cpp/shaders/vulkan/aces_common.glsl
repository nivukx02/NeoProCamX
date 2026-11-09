#ifndef NEO_ACES_COMMON_GLSL
#define NEO_ACES_COMMON_GLSL

#define NEO_ACESCG_NON_CERTIFIED 1
#define ACESCG_LINEAR_WORKING_SPACE 1

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

vec3 neoAcesFittedRrtOdt(vec3 acesCg) {
    vec3 x = max(acesCg, vec3(0.0));
    vec3 numerator = x * (x + vec3(0.0245786)) - vec3(0.000090537);
    vec3 denominator = x * (vec3(0.983729) * x + vec3(0.4329510)) + vec3(0.238081);
    return clamp(numerator / max(denominator, vec3(1e-6)), 0.0, 1.0);
}

#endif
