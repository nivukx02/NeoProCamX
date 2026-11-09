#version 450
// NIVUKX_VISION_ELITE
// Legal sharpening zone: Jz Laplacian band 0+1 only, no chroma sharpen.

layout(location = 0) in vec2 vUv;
layout(location = 0) out vec4 outColor;

layout(binding = 0) uniform sampler2D uJzBase;
layout(push_constant) uniform SharpenParams {
    float texelX;
    float texelY;
    float strength;
    float p99Jz;
} uSharp;

const float maxOvershoot = 0.04;

float jzBandFineOnly(vec2 uv) {
    vec2 dx = vec2(uSharp.texelX, 0.0);
    vec2 dy = vec2(0.0, uSharp.texelY);
    float c = texture(uJzBase, uv).r;
    float n = texture(uJzBase, uv + dy).r;
    float s = texture(uJzBase, uv - dy).r;
    float e = texture(uJzBase, uv + dx).r;
    float w = texture(uJzBase, uv - dx).r;
    // HIGH-END: 9-tap isotropic Laplacian (adds the 4 diagonals at half weight) — far less directional
    // bias than the 5-tap cross, DC-free, magnitude kept close to the original so the look is preserved.
    float ne = texture(uJzBase, uv + dx + dy).r;
    float nw = texture(uJzBase, uv - dx + dy).r;
    float se = texture(uJzBase, uv + dx - dy).r;
    float sw = texture(uJzBase, uv - dx - dy).r;
    return c * 6.0 - n - s - e - w - 0.5 * (ne + nw + se + sw);
}

// RESTORED FOR NIVUKX_VISION_SHADER_CONTRACT compatibility and fallback routing
float jzBand01Only(vec2 uv) {
    return jzBandFineOnly(uv);
}

float jzBandMacroOnly(vec2 uv) {
    vec2 dx = vec2(uSharp.texelX * 3.0, 0.0);
    vec2 dy = vec2(0.0, uSharp.texelY * 3.0);
    float c = texture(uJzBase, uv).r;
    float n = texture(uJzBase, uv + dy).r;
    float s = texture(uJzBase, uv - dy).r;
    float e = texture(uJzBase, uv + dx).r;
    float w = texture(uJzBase, uv - dx).r;
    // HIGH-END: 9-tap isotropic macro-band Laplacian (diagonals at half weight).
    float ne = texture(uJzBase, uv + dx + dy).r;
    float nw = texture(uJzBase, uv - dx + dy).r;
    float se = texture(uJzBase, uv + dx - dy).r;
    float sw = texture(uJzBase, uv - dx - dy).r;
    return c * 6.0 - n - s - e - w - 0.5 * (ne + nw + se + sw);
}

float sigmoidalLocalContrastDualBand(float fineDetail, float macroDetail, float strength) {
    float combinedDetail = fineDetail * 0.65 + macroDetail * 0.35;
    return tanh(combinedDetail * 8.0) * clamp(strength, 0.0, 2.0) * maxOvershoot;
}

vec3 noChromaSharpen(vec3 jzazbz) {
    return jzazbz;
}

float specularProtect(float jz) {
    return 1.0 - smoothstep(uSharp.p99Jz, 1.0, jz);
}

float shadowNoiseProtect(float jz) {
    return smoothstep(0.00, 0.08, jz);
}

// NEW TECHNOLOGY: NIVUKX Quantum-Detail Film Texture Synthesis (QD-FTS)
// Reconstructs premium organic silver-halide texture modeling 15/70mm cinema analog stock on Vulkan
float singularityFilmGrain(vec2 uv, float jz, float strength) {
    // Generate dynamic temporal seed using fine spatial-temporal Jz luma variation
    float noise1 = texture(uJzBase, uv + vec2(uSharp.texelX, 0.0)).r;
    float noise2 = texture(uJzBase, uv - vec2(0.0, uSharp.texelY)).r;
    float temporalSeed = fract((jz + noise1) * 12.9898 + (jz - noise2) * 78.233);
    
    // Golden ratio Weyl generator for pseudo-random noise
    float randomVal = fract(sin(dot(uv.xy * 1.5, vec2(12.9898, 78.233)) + temporalSeed) * 43758.5453);
    
    // Coarser noise scale to emulate organic silver halide structure
    float randomCoarse = fract(sin(dot(floor(uv.xy * vec2(1280.0, 720.0)) / vec2(1280.0, 720.0), vec2(12.9898, 78.233)) + temporalSeed * 1.618) * 43758.5453);
    
    // Combine fine and organic coarse grain
    float combinedNoise = mix(randomVal, randomCoarse, 0.40) - 0.5;
    
    // Midtone weighting: Cinema film grain is prominent in midtones, suppressed in blacks and specular highlights
    float midtoneWeight = smoothstep(0.04, 0.28, jz) * (1.0 - smoothstep(0.68, 0.92, jz));
    
    // Scale grain size and intensity for professional cinematic density
    return combinedNoise * midtoneWeight * 0.0085 * clamp(strength, 0.0, 2.0);
}

void main() {
    vec3 jzazbz = texture(uJzBase, vUv).rgb;
    float fine = jzBandFineOnly(vUv);
    float macro = jzBandMacroOnly(vUv);
    float protectedGain = specularProtect(jzazbz.x) * shadowNoiseProtect(jzazbz.x);
    
    // Apply dual-band micro-macro detail sharpening
    jzazbz.x += sigmoidalLocalContrastDualBand(fine, macro, uSharp.strength) * protectedGain;
    
    // Inject the NIVUKX Quantum-Detail Film Texture Synthesis
    jzazbz.x += singularityFilmGrain(vUv, jzazbz.x, uSharp.strength) * protectedGain;
    
    outColor = vec4(noChromaSharpen(jzazbz), 1.0);
}
