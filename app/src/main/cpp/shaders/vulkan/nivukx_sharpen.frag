#version 450

#define NIVUKX_DR_ELITE 1

layout(location = 0) in vec2 vTexCoord;
layout(location = 0) out vec4 outColor;

layout(binding = 0) uniform sampler2D uDenoisedJzazbz;
layout(binding = 1) uniform NivukxSharpenBlock {
    vec4 strengthAcutanceSpecularPad;
} sharpenParams;

float noChromaSharpen(float chromaValue) {
    return chromaValue;
}

float sigmoidalLocalContrast(float detail, float strength) {
    return (2.0 / (1.0 + exp(-2.0 * strength * detail))) - 1.0;
}

float specularProtect(float jz, float p99) {
    return 1.0 - smoothstep(p99, 1.0, jz);
}

float laplacianBand01(vec2 uv, vec2 texel) {
    float center = texture(uDenoisedJzazbz, uv).r;
    float axial = texture(uDenoisedJzazbz, uv + vec2(texel.x, 0.0)).r +
        texture(uDenoisedJzazbz, uv - vec2(texel.x, 0.0)).r +
        texture(uDenoisedJzazbz, uv + vec2(0.0, texel.y)).r +
        texture(uDenoisedJzazbz, uv - vec2(0.0, texel.y)).r;
    float diagonal = texture(uDenoisedJzazbz, uv + texel).r +
        texture(uDenoisedJzazbz, uv - texel).r +
        texture(uDenoisedJzazbz, uv + vec2(texel.x, -texel.y)).r +
        texture(uDenoisedJzazbz, uv + vec2(-texel.x, texel.y)).r;
    float lowPass = (center * 4.0 + axial * 2.0 + diagonal) / 16.0;
    return center - lowPass;
}

void main() {
    vec4 jab = texture(uDenoisedJzazbz, vTexCoord);
    vec2 texel = 1.0 / vec2(textureSize(uDenoisedJzazbz, 0));
    float maxOvershoot = 0.04;
    float detail = laplacianBand01(vTexCoord, texel);
    float protectedStrength = sharpenParams.strengthAcutanceSpecularPad.x *
        specularProtect(jab.r, sharpenParams.strengthAcutanceSpecularPad.z);
    float lifted = sigmoidalLocalContrast(detail, protectedStrength);
    jab.r = clamp(jab.r + clamp(lifted, -maxOvershoot, maxOvershoot), 0.0, 1.0);
    jab.g = noChromaSharpen(jab.g);
    jab.b = noChromaSharpen(jab.b);
    outColor = jab;
}
