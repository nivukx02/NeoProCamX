#pragma once

namespace neoproc::npv10::lossless {

// No textures, YUV conversion, floating-point arithmetic, filtering or colour transforms.
// CPU copies the original canonical P010 words into an exclusively owned integer SSBO.
inline constexpr const char* kP010RangeComputeShader = R"glsl(#version 310 es
precision highp int;
// Cooperative tiles coalesce both global-memory directions. Each lane then owns one block,
// packing its residuals in one pass without per-word integer division or repeated SSBO reads.
layout(local_size_x = 64) in;
layout(std430, binding = 0) readonly buffer OriginalP010 { uint original[]; };
layout(std430, binding = 1) writeonly buffer RangeBlocks { uint encoded[]; };
uniform highp uint blockCount;
uniform highp uint frameToken;

// A 33-word stride prevents all lanes in a wave from hitting the same shared-memory bank
// while reading the same pair position of their different 32-pair blocks. Padding is local
// workspace only; it is never interpreted as a sample or included in the wire representation.
shared uint sourceTile[2112];
shared uint resultTile[1472];

void main() {
    uint local = gl_LocalInvocationID.x;
    uint firstBlock = gl_WorkGroupID.x * 64u;
    // Uniform group admission: no invocation can skip a barrier needed by another lane.
    if (firstBlock >= blockCount) return;
    uint validBlocks = min(64u, blockCount - firstBlock);
    uint inputWords = validBlocks * 32u;
    for (uint index = local; index < inputWords; index += 64u) {
        sourceTile[(index >> 5u) * 33u + (index & 31u)] = original[firstBlock * 32u + index];
    }
    barrier();
    uint block = firstBlock + local;
    if (block < blockCount) {
        uint source = local * 33u;
        uint target = local * 23u;
        uint low0 = 1023u, low1 = 1023u, high0 = 0u, high1 = 0u, padding = 0u;
        for (uint pairIndex = 0u; pairIndex < 32u; ++pairIndex) {
            uint pair = sourceTile[source + pairIndex];
            padding |= pair & 0x003f003fu;
            uint even = (pair & 65535u) >> 6u;
            uint odd = pair >> 22u;
            low0 = min(low0, even); high0 = max(high0, even);
            low1 = min(low1, odd); high1 = max(high1, odd);
        }
        uint bits0 = high0 == low0 ? 0u : uint(findMSB(high0 - low0) + 1);
        uint bits1 = high1 == low1 ? 0u : uint(findMSB(high1 - low1) + 1);
        resultTile[target] = low0 | (bits0 << 10u) | (padding == 0u ? 0u : 0x80000000u);
        resultTile[target + 1u] = low1 | (bits1 << 10u);
        uint accumulator0 = 0u, accumulator1 = 0u;
        uint fill0 = 0u, fill1 = 0u, words0 = 0u, words1 = 0u;
        for (uint pairIndex = 0u; pairIndex < 32u; ++pairIndex) {
            uint pair = sourceTile[source + pairIndex];
            uint residual0 = ((pair & 65535u) >> 6u) - low0;
            uint residual1 = (pair >> 22u) - low1;
            accumulator0 |= residual0 << fill0;
            accumulator1 |= residual1 << fill1;
            fill0 += bits0; fill1 += bits1;
            if (fill0 >= 32u) {
                resultTile[target + 2u + words0++] = accumulator0;
                fill0 -= 32u;
                accumulator0 = fill0 == 0u ? 0u : residual0 >> (bits0 - fill0);
            }
            if (fill1 >= 32u) {
                resultTile[target + 12u + words1++] = accumulator1;
                fill1 -= 32u;
                accumulator1 = fill1 == 0u ? 0u : residual1 >> (bits1 - fill1);
            }
        }
        // Exactly 32 residuals make each lane end on a word boundary for every width 0..10.
        // Explicit tail initialization prevents data from any previous dispatch being observable.
        for (uint word = words0; word < 10u; ++word) resultTile[target + 2u + word] = 0u;
        for (uint word = words1; word < 10u; ++word) resultTile[target + 12u + word] = 0u;
        resultTile[target + 22u] = frameToken ^ block;
    }
    barrier();
    uint outputWords = validBlocks * 23u;
    for (uint index = local; index < outputWords; index += 64u) {
        encoded[firstBlock * 23u + index] = resultTile[index];
    }
}
)glsl";

} // namespace neoproc::npv10::lossless
