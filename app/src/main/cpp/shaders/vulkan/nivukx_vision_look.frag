#version 450
// NIVUKX_VISION_ELITE
// Parametric JzAzBz look pass: exposure/contrast/saturation/hue knobs are metadata driven.

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 outColor;

layout(binding = 0) uniform sampler2D uBaseJzAzBz;
layout(push_constant) uniform LookKnobs {
    float exposureEv;
    float contrast;
    float saturation;
    float shadowWarmthDegrees;
    float midtoneTintDegrees;
    float highlightShoulderGain;
} uLook;

vec2 rotateHue(vec2 azbz, float degrees) {
    float r = radians(clamp(degrees, -8.0, 8.0));
    float c = cos(r);
    float s = sin(r);
    return vec2(azbz.x * c - azbz.y * s, azbz.x * s + azbz.y * c);
}

vec3 nivukxThreeWayGrader(vec3 jzazbz) {
    float jz = jzazbz.x;
    float shadowWeight = 1.0 - smoothstep(0.18, 0.42, jz);
    float midWeight = smoothstep(0.18, 0.38, jz) * (1.0 - smoothstep(0.56, 0.82, jz));
    float hueDegrees = uLook.shadowWarmthDegrees * shadowWeight + uLook.midtoneTintDegrees * midWeight;
    vec2 chroma = rotateHue(jzazbz.yz, hueDegrees);
    chroma *= clamp(uLook.saturation, 0.7, 1.5);
    float exposure = exp2(clamp(uLook.exposureEv, -4.0, 4.0));
    float contrastJz = ((jz * exposure) - 0.38) * clamp(uLook.contrast, 0.7, 1.4) + 0.38;
    float shoulder = mix(contrastJz, tanh(contrastJz), 1.0 - clamp(uLook.highlightShoulderGain, 0.6, 1.0));
    return vec3(clamp(shoulder, 0.0, 1.0), chroma);
}

void main() {
    vec3 jzazbz = texture(uBaseJzAzBz, vUv).rgb;
    outColor = vec4(nivukxThreeWayGrader(jzazbz), 1.0);
}
