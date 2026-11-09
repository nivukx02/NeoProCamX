#pragma once

namespace neoproc::npv10::lossless {

// The range dispatch has finished reading its original SSBO before this dispatch starts.
// Reuse that SAME bounded buffer for compact per-chunk bytes: no extra frame/queue/workspace.
// CPU independently owns original packed samples/CRC and still handles exact LZ4/raw escapes.
inline constexpr const char* kP010RangeCompactShader = R"glsl(#version 310 es
precision highp int;
layout(local_size_x = 128) in;
layout(std430, binding = 1) readonly buffer RangeBlocks { uint blocks[]; };
layout(std430, binding = 0) writeonly buffer CompactChunks { uint compact[]; };
uniform highp uint sampleCount;
uniform highp uint frameToken;
shared uint prefixes[129];
shared uint errors[128];
shared uint chunkError;
// First compute each block's destination. Copy adjacent blocks with adjacent lanes afterwards,
// instead of making every lane stream 32 distant blocks through unrelated memory cache lines.
// Padded rows keep this shared prefix table free of stride-32 bank conflicts.
shared uint blockPrefixes[4224];

void main() {
    uint chunk = gl_WorkGroupID.x;
    uint firstSample = chunk * 262144u;
    if (firstSample >= sampleCount) return;
    uint samples = min(262144u, sampleCount - firstSample);
    uint blockCount = samples / 64u;
    uint local = gl_LocalInvocationID.x;
    uint first = local * 32u;
    uint last = min(first + 32u, blockCount);
    uint words = 0u, invalid = 0u;
    for (uint block = first; block < last; ++block) {
        blockPrefixes[local * 33u + block - first] = words;
        uint absolute = firstSample / 64u + block;
        uint inputOffset = absolute * 23u;
        uint header0 = blocks[inputOffset], header1 = blocks[inputOffset + 1u];
        uint bits0 = header0 >> 10u, bits1 = header1 >> 10u;
        bool valid = (header0 & ~16383u) == 0u && (header1 & ~16383u) == 0u &&
            bits0 <= 10u && bits1 <= 10u && blocks[inputOffset + 22u] == (frameToken ^ absolute);
        invalid |= valid ? 0u : 1u;
        words += valid ? 1u + bits0 + bits1 : 0u;
    }
    prefixes[local + 1u] = words;
    errors[local] = invalid;
    barrier();
    if (local == 0u) {
        prefixes[0] = 0u;
        chunkError = 0u;
        // One small scan per 262,144 samples, not a CPU scan/copy per 64 samples.
        for (uint lane = 0u; lane < 128u; ++lane) {
            prefixes[lane + 1u] += prefixes[lane];
            chunkError |= errors[lane];
        }
    }
    barrier();
    uint rawBytes = samples / 4u * 5u;
    uint rangeBytes = prefixes[128] * 4u;
    uint slotWords = min(sampleCount, 262144u) / 16u * 5u + 4u;
    uint target = chunk * slotWords;
    bool fits = chunkError == 0u && rangeBytes > 0u && rangeBytes < rawBytes;
    if (local == 0u) {
        compact[target] = fits ? rangeBytes : 0u;
        compact[target + 1u] = samples;
        compact[target + 2u] = frameToken ^ chunk;
        compact[target + 3u] = chunkError;
    }
    if (!fits) return; // Whole-group decision; original exact LZ4/raw escape remains on CPU.
    for (uint block = first; block < last; ++block) {
        blockPrefixes[local * 33u + block - first] += prefixes[local];
    }
    barrier();
    for (uint block = local; block < blockCount; block += 128u) {
        uint outputOffset = target + 4u + blockPrefixes[(block >> 5u) * 33u + (block & 31u)];
        uint inputOffset = (firstSample / 64u + block) * 23u;
        uint header0 = blocks[inputOffset], header1 = blocks[inputOffset + 1u];
        uint bits0 = header0 >> 10u, bits1 = header1 >> 10u;
        // NLC2's first 16-bit header offsets the even residual lane by two bytes. The
        // second header completes its last word; the odd residual lane is then aligned.
        // Every destination uint is written exactly once; no byte atomics or torn overlap.
        for (uint word = 0u; word <= bits0; ++word) {
            uint low = word == 0u ? header0 : blocks[inputOffset + 1u + word] >> 16u;
            uint high = word < bits0 ? blocks[inputOffset + 2u + word] : header1;
            compact[outputOffset + word] = low | (high << 16u);
        }
        for (uint word = 0u; word < bits1; ++word) {
            compact[outputOffset + bits0 + 1u + word] = blocks[inputOffset + 12u + word];
        }
    }
}
)glsl";

} // namespace neoproc::npv10::lossless
