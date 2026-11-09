#include "npv10_lossless_chunk_codec.h"
#include "npv10_split10_simd.h"
#include "npv10_range10_codec.h"
#include "npv10_p010_range_fusion.h"
#include "npv10_gpu_range_blocks.h"
#include "third_party/lz4/lz4.h"

#include <jni.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cstring>
#include <limits>
#include <vector>

#include <zlib.h>

namespace neoproc::npv10::lossless {
namespace {

constexpr std::uint8_t kMagic[4] = {'N', 'L', 'C', '2'};
constexpr std::uint32_t kVersion = 1u;
constexpr std::uint32_t kRangeVersion = 2u;
constexpr std::uint8_t kSplit10Lz4Flag = 1u;
constexpr std::uint8_t kRange10Flag = 2u;
constexpr std::size_t kHashBits = 16u;
constexpr std::size_t kHashSize = 1u << kHashBits;
constexpr std::size_t kMinimumMatch = 4u;
constexpr std::size_t kMaximumDistance = 65535u;
// A 1024-byte search step bounds live NLC2 CPU work under Android thermal throttling. Skipped
// positions become literals only: decoded P010 samples, CRC coverage, chunk boundaries, and every
// source bit remain identical. Four frame lanes preserve packing/compression overlap while avoiding
// hash probes that previously exhausted the bounded capture queue.
constexpr std::size_t kRealtimeSearchAcceleration = 1024u;
// Compression competes with capture packing and storage writes for the same thermally constrained
// memory bandwidth. Full-size camera chunks are therefore admitted to split/LZ4 only when a cheap
// virtual-split probe finds enough repeat structure to justify the full transform. Rejected
// chunks use the existing bit-exact raw NLC2 record; this changes neither source codes nor format.
constexpr std::size_t kCompressionAdmissionProbeFloorBytes = 32u * 1024u;
constexpr std::size_t kCompressionAdmissionMatchProbeBytes = 64u;
constexpr std::size_t kCompressionAdmissionEvidenceBytes = 1024u;
// Paired real-frame device probes exposed small-match search as the remaining live CPU cost.
// A 1024 search acceleration retains every source bit while accepting a larger lossless packet;
// the existing worst-case reservation and raw-chunk escape path bound that tradeoff. This changes
// neither LZ4 decoding nor ten-bit samples. The override is only for the standalone paired probe.
#if defined(NEO_NPV10_REALTIME_LZ4_ACCELERATION)
constexpr int kUpstreamRealtimeAcceleration = NEO_NPV10_REALTIME_LZ4_ACCELERATION;
#else
constexpr int kUpstreamRealtimeAcceleration = 1024;
#endif
static_assert(kUpstreamRealtimeAcceleration >= 1 && kUpstreamRealtimeAcceleration <= 65537);

struct Workspace {
    std::vector<std::uint8_t> split;
    std::vector<std::uint8_t> compressed;
    std::vector<std::uint8_t> raw;
    std::vector<std::uint8_t> pendingRecord;
    std::array<std::uint32_t, kHashSize> offsets{};
    std::array<std::uint16_t, kHashSize> generations{};
    std::uint16_t generation = 0u;
    LZ4_stream_t upstreamState{};

    bool prepare(std::size_t chunkBytes) {
        try {
            split.resize(chunkBytes);
            compressed.resize(chunkBytes + chunkBytes / 255u + 16u);
            raw.resize(chunkBytes);
            pendingRecord.resize(kChunkHeaderBytes + chunkBytes);
            ++generation;
            if (generation == 0u) {
                generations.fill(0u);
                generation = 1u;
            }
            return true;
        } catch (...) {
            return false;
        }
    }
};

thread_local Workspace gWorkspace;

std::size_t packedBytesForSamples(std::int64_t samples) noexcept {
    if (samples < 0 || samples >
        (std::numeric_limits<std::int64_t>::max() - 7) / 10) return 0u;
    return static_cast<std::size_t>((samples * 10 + 7) / 8);
}

std::uint32_t readLe32(const std::uint8_t* value) noexcept {
    std::uint32_t output = 0u;
    std::memcpy(&output, value, sizeof(output));
    return output;
}

void putBe32(std::uint8_t* target, std::uint32_t value) noexcept {
    target[0] = static_cast<std::uint8_t>(value >> 24u);
    target[1] = static_cast<std::uint8_t>(value >> 16u);
    target[2] = static_cast<std::uint8_t>(value >> 8u);
    target[3] = static_cast<std::uint8_t>(value);
}

std::uint32_t readBe32(const std::uint8_t* source) noexcept {
    return (static_cast<std::uint32_t>(source[0]) << 24u) |
        (static_cast<std::uint32_t>(source[1]) << 16u) |
        (static_cast<std::uint32_t>(source[2]) << 8u) |
        static_cast<std::uint32_t>(source[3]);
}

std::uint16_t packedCode(const std::uint8_t* source, std::size_t index) noexcept {
    const std::uint8_t* group = source + (index / 4u) * 5u;
    switch (index & 3u) {
        case 0u:
            return static_cast<std::uint16_t>(group[0] | ((group[1] & 0x03u) << 8u));
        case 1u:
            return static_cast<std::uint16_t>((group[1] >> 2u) | ((group[2] & 0x0Fu) << 6u));
        case 2u:
            return static_cast<std::uint16_t>((group[2] >> 4u) | ((group[3] & 0x3Fu) << 4u));
        default:
            return static_cast<std::uint16_t>((group[3] >> 6u) | (group[4] << 2u));
    }
}

bool splitPackedTenBit(
    const std::uint8_t* source,
    std::size_t sampleCount,
    std::uint8_t* target,
    std::size_t targetBytes
) noexcept {
    const std::size_t required = packedBytesForSamples(static_cast<std::int64_t>(sampleCount));
    if (source == nullptr || target == nullptr || required == 0u || targetBytes < required) return false;
    const std::size_t highOffset = sampleCount;
    std::memset(target + highOffset, 0, required - highOffset);
    std::size_t sample = splitPackedTenBitSimdPrefix(source, sampleCount, target);
    for (; sample + 4u <= sampleCount; sample += 4u) {
        const std::uint8_t* group = source + (sample / 4u) * 5u;
        const std::uint16_t s0 = static_cast<std::uint16_t>(group[0] | ((group[1] & 3u) << 8u));
        const std::uint16_t s1 = static_cast<std::uint16_t>((group[1] >> 2u) | ((group[2] & 15u) << 6u));
        const std::uint16_t s2 = static_cast<std::uint16_t>((group[2] >> 4u) | ((group[3] & 63u) << 4u));
        const std::uint16_t s3 = static_cast<std::uint16_t>((group[3] >> 6u) | (group[4] << 2u));
        target[sample] = static_cast<std::uint8_t>(s0);
        target[sample + 1u] = static_cast<std::uint8_t>(s1);
        target[sample + 2u] = static_cast<std::uint8_t>(s2);
        target[sample + 3u] = static_cast<std::uint8_t>(s3);
        target[highOffset + sample / 4u] = static_cast<std::uint8_t>(
            (s0 >> 8u) | ((s1 >> 8u) << 2u) | ((s2 >> 8u) << 4u) | ((s3 >> 8u) << 6u)
        );
    }
    for (; sample < sampleCount; ++sample) {
        const std::uint16_t value = packedCode(source, sample);
        target[sample] = static_cast<std::uint8_t>(value);
        target[highOffset + sample / 4u] |= static_cast<std::uint8_t>(
            (value >> 8u) << ((sample & 3u) * 2u)
        );
    }
    return true;
}

bool joinSplitTenBit(
    const std::uint8_t* source,
    std::size_t sourceBytes,
    std::size_t sampleCount,
    std::uint8_t* target,
    std::size_t targetBytes
) noexcept {
    const std::size_t required = packedBytesForSamples(static_cast<std::int64_t>(sampleCount));
    if (source == nullptr || target == nullptr || sourceBytes != required || targetBytes < required) {
        return false;
    }
    const std::size_t highOffset = sampleCount;
    std::size_t sample = joinSplitTenBitSimdPrefix(source, sampleCount, target);
    for (; sample + 4u <= sampleCount; sample += 4u) {
        const std::uint8_t high = source[highOffset + sample / 4u];
        const std::uint16_t s0 = static_cast<std::uint16_t>(source[sample] | ((high & 3u) << 8u));
        const std::uint16_t s1 = static_cast<std::uint16_t>(source[sample + 1u] | (((high >> 2u) & 3u) << 8u));
        const std::uint16_t s2 = static_cast<std::uint16_t>(source[sample + 2u] | (((high >> 4u) & 3u) << 8u));
        const std::uint16_t s3 = static_cast<std::uint16_t>(source[sample + 3u] | ((high >> 6u) << 8u));
        std::uint8_t* group = target + (sample / 4u) * 5u;
        group[0] = static_cast<std::uint8_t>(s0);
        group[1] = static_cast<std::uint8_t>((s0 >> 8u) | (s1 << 2u));
        group[2] = static_cast<std::uint8_t>((s1 >> 6u) | (s2 << 4u));
        group[3] = static_cast<std::uint8_t>((s2 >> 4u) | (s3 << 6u));
        group[4] = static_cast<std::uint8_t>(s3 >> 2u);
    }
    if (sample < sampleCount) {
        std::uint64_t accumulator = 0u;
        unsigned bitCount = 0u;
        std::size_t output = (sample / 4u) * 5u;
        for (; sample < sampleCount; ++sample) {
            const std::uint8_t high = source[highOffset + sample / 4u];
            const std::uint16_t value = static_cast<std::uint16_t>(
                source[sample] | (((high >> ((sample & 3u) * 2u)) & 3u) << 8u)
            );
            accumulator |= static_cast<std::uint64_t>(value) << bitCount;
            bitCount += 10u;
            while (bitCount >= 8u) {
                target[output++] = static_cast<std::uint8_t>(accumulator);
                accumulator >>= 8u;
                bitCount -= 8u;
            }
        }
        if (bitCount > 0u) target[output] = static_cast<std::uint8_t>(accumulator);
    }
    return true;
}

std::uint32_t hashSequence(std::uint32_t sequence) noexcept {
    return (sequence * 2654435761u) >> (32u - kHashBits);
}

std::uint16_t nextHashGeneration(Workspace* workspace) noexcept {
    ++workspace->generation;
    if (workspace->generation == 0u) {
        workspace->generations.fill(0u);
        workspace->generation = 1u;
    }
    return workspace->generation;
}

std::uint8_t virtualSplitByte(
    const std::uint8_t* packed,
    std::size_t sampleCount,
    std::size_t splitIndex
) noexcept {
    if (splitIndex < sampleCount) {
        return static_cast<std::uint8_t>(packedCode(packed, splitIndex));
    }
    const std::size_t highByteIndex = splitIndex - sampleCount;
    const std::size_t firstSample = highByteIndex * 4u;
    std::uint8_t high = 0u;
    for (std::size_t lane = 0u; lane < 4u && firstSample + lane < sampleCount; ++lane) {
        high |= static_cast<std::uint8_t>(
            (packedCode(packed, firstSample + lane) >> 8u) << (lane * 2u)
        );
    }
    return high;
}

std::uint32_t virtualSplitSequence(
    const std::uint8_t* packed,
    std::size_t sampleCount,
    std::size_t splitIndex
) noexcept {
    return static_cast<std::uint32_t>(virtualSplitByte(packed, sampleCount, splitIndex)) |
        (static_cast<std::uint32_t>(virtualSplitByte(packed, sampleCount, splitIndex + 1u)) << 8u) |
        (static_cast<std::uint32_t>(virtualSplitByte(packed, sampleCount, splitIndex + 2u)) << 16u) |
        (static_cast<std::uint32_t>(virtualSplitByte(packed, sampleCount, splitIndex + 3u)) << 24u);
}

/**
 * Samples the accelerated LZ4 search positions against a virtual split-10 view without
 * materialising or traversing the complete split buffer. Every candidate match has to continue for
 * up to a small fixed bound, and multiple matches are required before admission. Runtime is thus
 * bounded by a few thousand byte comparisons even for a highly structured camera chunk. A
 * negative decision always remains bit-exact because NLC2 already defines raw chunks.
 */
bool hasWorthwhileSplitLz4Savings(
    const std::uint8_t* packed,
    std::size_t sampleCount,
    std::size_t sourceBytes,
    Workspace* workspace
) noexcept {
    if (packed == nullptr || workspace == nullptr || sourceBytes == 0u) return false;
    if (sourceBytes < kCompressionAdmissionProbeFloorBytes) return true;

    const std::uint16_t generation = nextHashGeneration(workspace);
    const std::size_t matchLimit = sourceBytes >= 12u ? sourceBytes - 12u : 0u;
    std::size_t matchedEvidenceBytes = 0u;
    for (std::size_t input = 0u; sourceBytes >= 12u && input <= matchLimit;
         input += kRealtimeSearchAcceleration) {
        const std::uint32_t sequence = virtualSplitSequence(packed, sampleCount, input);
        const std::uint32_t hash = hashSequence(sequence);
        const std::size_t reference = workspace->offsets[hash];
        const bool validReference = workspace->generations[hash] == generation &&
            reference < input && input - reference <= kMaximumDistance;
        workspace->offsets[hash] = static_cast<std::uint32_t>(input);
        workspace->generations[hash] = generation;
        if (!validReference ||
            virtualSplitSequence(packed, sampleCount, reference) != sequence) {
            continue;
        }

        const std::size_t maximumMatch = std::min<std::size_t>(
            sourceBytes - input,
            kCompressionAdmissionMatchProbeBytes
        );
        std::size_t matchLength = kMinimumMatch;
        while (matchLength < maximumMatch &&
            virtualSplitByte(packed, sampleCount, reference + matchLength) ==
                virtualSplitByte(packed, sampleCount, input + matchLength)
        ) {
            ++matchLength;
        }
        matchedEvidenceBytes += matchLength - kMinimumMatch;
        if (matchedEvidenceBytes >= kCompressionAdmissionEvidenceBytes) {
            return true;
        }
    }
    return false;
}

std::size_t countMatchingBytes(
    const std::uint8_t* left,
    const std::uint8_t* right,
    std::size_t maximumBytes
) noexcept {
    std::size_t matched = 0u;
    while (maximumBytes - matched >= sizeof(std::uint64_t)) {
        std::uint64_t leftWord = 0u;
        std::uint64_t rightWord = 0u;
        std::memcpy(&leftWord, left + matched, sizeof(leftWord));
        std::memcpy(&rightWord, right + matched, sizeof(rightWord));
        const std::uint64_t difference = leftWord ^ rightWord;
        if (difference != 0u) {
#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
            // Every shipped Android ABI is little-endian. Clang lowers this to one count-trailing-
            // zeros instruction instead of up to eight dependent byte tests in every LZ4 match.
            return matched + static_cast<std::size_t>(__builtin_ctzll(difference) >> 3u);
#else
            // Inspect the integer's object representation so the first differing source byte is
            // still found correctly if a future supported ABI is not little-endian.
            const auto* differenceBytes = reinterpret_cast<const std::uint8_t*>(&difference);
            for (std::size_t byte = 0u; byte < sizeof(difference); ++byte) {
                if (differenceBytes[byte] != 0u) return matched + byte;
            }
#endif
        }
        matched += sizeof(std::uint64_t);
    }
    while (matched < maximumBytes && left[matched] == right[matched]) ++matched;
    return matched;
}

bool emitLength(
    std::uint8_t* output,
    std::size_t capacity,
    std::size_t* outputOffset,
    std::size_t length
) noexcept {
    if (output == nullptr || outputOffset == nullptr) return false;
    while (length >= 255u) {
        if (*outputOffset >= capacity) return false;
        output[(*outputOffset)++] = 255u;
        length -= 255u;
    }
    if (*outputOffset >= capacity) return false;
    output[(*outputOffset)++] = static_cast<std::uint8_t>(length);
    return true;
}

std::size_t compressLz4Block(
    const std::uint8_t* source,
    std::size_t sourceBytes,
    std::uint8_t* output,
    std::size_t outputCapacity,
    Workspace* workspace
) noexcept {
    if (source == nullptr || output == nullptr || workspace == nullptr || sourceBytes == 0u) return 0u;
    const std::uint16_t generation = nextHashGeneration(workspace);
    std::size_t anchor = 0u;
    std::size_t input = 0u;
    std::size_t outputOffset = 0u;
    const std::size_t matchLimit = sourceBytes >= 12u ? sourceBytes - 12u : 0u;
    while (sourceBytes >= 12u && input <= matchLimit) {
        const std::uint32_t sequence = readLe32(source + input);
        const std::uint32_t hash = hashSequence(sequence);
        const std::size_t reference = workspace->offsets[hash];
        const bool validReference = workspace->generations[hash] == generation &&
            reference < input && input - reference <= kMaximumDistance;
        workspace->offsets[hash] = static_cast<std::uint32_t>(input);
        workspace->generations[hash] = generation;
        if (!validReference || readLe32(source + reference) != sequence) {
            input += kRealtimeSearchAcceleration;
            continue;
        }

        const std::size_t literalLength = input - anchor;
        const std::size_t matchLength = kMinimumMatch + countMatchingBytes(
            source + reference + kMinimumMatch,
            source + input + kMinimumMatch,
            sourceBytes - input - kMinimumMatch
        );
        if (outputOffset >= outputCapacity) return 0u;
        const std::size_t tokenOffset = outputOffset++;
        const std::size_t matchCode = matchLength - kMinimumMatch;
        output[tokenOffset] = static_cast<std::uint8_t>(
            (std::min<std::size_t>(literalLength, 15u) << 4u) |
            std::min<std::size_t>(matchCode, 15u)
        );
        if (literalLength >= 15u &&
            !emitLength(output, outputCapacity, &outputOffset, literalLength - 15u)) return 0u;
        if (literalLength > outputCapacity - outputOffset) return 0u;
        std::memcpy(output + outputOffset, source + anchor, literalLength);
        outputOffset += literalLength;
        if (outputCapacity - outputOffset < 2u) return 0u;
        const std::size_t distance = input - reference;
        output[outputOffset++] = static_cast<std::uint8_t>(distance);
        output[outputOffset++] = static_cast<std::uint8_t>(distance >> 8u);
        if (matchCode >= 15u &&
            !emitLength(output, outputCapacity, &outputOffset, matchCode - 15u)) return 0u;
        input += matchLength;
        anchor = input;
        if (input >= 2u && input - 2u <= matchLimit) {
            const std::uint32_t tailHash = hashSequence(readLe32(source + input - 2u));
            workspace->offsets[tailHash] = static_cast<std::uint32_t>(input - 2u);
            workspace->generations[tailHash] = generation;
        }
    }

    const std::size_t literalLength = sourceBytes - anchor;
    if (outputOffset >= outputCapacity) return 0u;
    const std::size_t tokenOffset = outputOffset++;
    output[tokenOffset] = static_cast<std::uint8_t>(std::min<std::size_t>(literalLength, 15u) << 4u);
    if (literalLength >= 15u &&
        !emitLength(output, outputCapacity, &outputOffset, literalLength - 15u)) return 0u;
    if (literalLength > outputCapacity - outputOffset) return 0u;
    std::memcpy(output + outputOffset, source + anchor, literalLength);
    return outputOffset + literalLength;
}

bool decompressLz4Block(
    const std::uint8_t* source,
    std::size_t sourceBytes,
    std::uint8_t* output,
    std::size_t outputBytes
) noexcept {
    if (source == nullptr || output == nullptr || sourceBytes == 0u || outputBytes == 0u) return false;
    if (sourceBytes <= static_cast<std::size_t>(std::numeric_limits<int>::max()) &&
        outputBytes <= static_cast<std::size_t>(std::numeric_limits<int>::max()) &&
        LZ4_decompress_safe(
            reinterpret_cast<const char*>(source),
            reinterpret_cast<char*>(output),
            static_cast<int>(sourceBytes),
            static_cast<int>(outputBytes)
        ) == static_cast<int>(outputBytes)) {
        return true;
    }
    // Older NLC2 writers could end with a match instead of LZ4's final literal sequence. Preserve
    // their bounded decoder and the outer packed-source CRC check; never invalidate old masters.
    std::size_t input = 0u;
    std::size_t target = 0u;
    while (input < sourceBytes) {
        const std::uint8_t token = source[input++];
        std::size_t literalLength = token >> 4u;
        if (literalLength == 15u) {
            std::uint8_t next = 0u;
            do {
                if (input >= sourceBytes) return false;
                next = source[input++];
                if (literalLength > std::numeric_limits<std::size_t>::max() - next) return false;
                literalLength += next;
            } while (next == 255u);
        }
        if (literalLength > sourceBytes - input || literalLength > outputBytes - target) return false;
        std::memcpy(output + target, source + input, literalLength);
        input += literalLength;
        target += literalLength;
        if (input == sourceBytes) break;
        if (sourceBytes - input < 2u) return false;
        const std::size_t distance = static_cast<std::size_t>(source[input]) |
            (static_cast<std::size_t>(source[input + 1u]) << 8u);
        input += 2u;
        if (distance == 0u || distance > target) return false;
        std::size_t matchLength = (token & 0x0Fu) + kMinimumMatch;
        if ((token & 0x0Fu) == 15u) {
            std::uint8_t next = 0u;
            do {
                if (input >= sourceBytes) return false;
                next = source[input++];
                if (matchLength > std::numeric_limits<std::size_t>::max() - next) return false;
                matchLength += next;
            } while (next == 255u);
        }
        if (matchLength > outputBytes - target) return false;
        const std::size_t reference = target - distance;
        for (std::size_t index = 0u; index < matchLength; ++index) {
            output[target + index] = output[reference + index];
        }
        target += matchLength;
    }
    return input == sourceBytes && target == outputBytes;
}

void writeP010FromPacked(
    const std::uint8_t* packed,
    std::size_t sampleCount,
    std::uint8_t* p010
) noexcept {
    const std::size_t prefix = writeP010FromPackedSimdPrefix(packed, sampleCount, p010);
    for (std::size_t sample = prefix; sample < sampleCount; ++sample) {
        const std::uint16_t word = static_cast<std::uint16_t>(packedCode(packed, sample) << 6u);
        p010[sample * 2u] = static_cast<std::uint8_t>(word);
        p010[sample * 2u + 1u] = static_cast<std::uint8_t>(word >> 8u);
    }
}

}  // namespace

std::int64_t maximumStoredBytes(
    std::int64_t packedBytes,
    std::int64_t sampleCount
) noexcept {
    if (packedBytes <= 0 || sampleCount <= 0 ||
        packedBytesForSamples(sampleCount) != static_cast<std::size_t>(packedBytes)) return -1;
    const std::int64_t chunks = (sampleCount + kChunkSamples - 1) / kChunkSamples;
    if (chunks > (std::numeric_limits<std::int64_t>::max() - kFrameHeaderBytes - packedBytes) /
        kChunkHeaderBytes) return -1;
    return kFrameHeaderBytes + packedBytes + chunks * kChunkHeaderBytes;
}

static EncodeResult compressPackedTenBitWithEffortInPlace(
    std::uint8_t* packet,
    std::int64_t packedBytes,
    std::int64_t sampleCount64,
    std::int64_t capacity,
    std::uint32_t packedCrc32,
    bool upstreamLz4,
    int upstreamAcceleration,
    bool rangePacking = false,
    const GpuRangeBlocksView* gpuBlocks = nullptr
) noexcept {
    EncodeResult result{};
    if (upstreamAcceleration < 1 || upstreamAcceleration > 65537) return result;
    const std::int64_t maximumBytes = maximumStoredBytes(packedBytes, sampleCount64);
    if (packet == nullptr || sampleCount64 <= 0 || maximumBytes <= 0 || capacity < maximumBytes ||
        sampleCount64 > std::numeric_limits<std::uint32_t>::max() ||
        packedBytes > std::numeric_limits<std::uint32_t>::max()) return result;
    if (gpuBlocks != nullptr && (!rangePacking ||
            sampleCount64 > std::numeric_limits<std::uint32_t>::max() / 2u ||
            !gpuBlocks->validFor(static_cast<std::size_t>(sampleCount64)))) return result;

    const std::uint32_t sampleCount = static_cast<std::uint32_t>(sampleCount64);
    const std::uint32_t chunkCount = static_cast<std::uint32_t>(
        (sampleCount64 + kChunkSamples - 1) / kChunkSamples
    );
    const std::size_t maximumChunkBytes = packedBytesForSamples(kChunkSamples);
    Workspace& workspace = gWorkspace;
    if (!workspace.prepare(maximumChunkBytes)) return result;

    std::uint8_t frameHeader[kFrameHeaderBytes]{};
    std::memcpy(frameHeader, kMagic, sizeof(kMagic));
    putBe32(frameHeader + 4, rangePacking ? kRangeVersion : kVersion);
    putBe32(frameHeader + 8, static_cast<std::uint32_t>(packedBytes));
    putBe32(frameHeader + 12, sampleCount * 2u);
    putBe32(frameHeader + 16, sampleCount);
    putBe32(frameHeader + 20, static_cast<std::uint32_t>(kChunkSamples));
    putBe32(frameHeader + 24, chunkCount);
    putBe32(frameHeader + 28, packedCrc32);

    std::size_t outputOffset = kFrameHeaderBytes;
    uLong storedCrc = ::crc32(::crc32(0L, Z_NULL, 0), frameHeader, kFrameHeaderBytes);
    std::size_t pendingBytes = 0u;
    std::uint32_t compressedChunks = 0u;
    std::uint32_t rawChunks = 0u;
    for (std::uint32_t chunk = 0u; chunk < chunkCount; ++chunk) {
        const std::size_t firstSample = static_cast<std::size_t>(chunk) * kChunkSamples;
        const std::size_t samples = std::min<std::size_t>(
            kChunkSamples,
            static_cast<std::size_t>(sampleCount) - firstSample
        );
        const std::size_t sourceOffset = packedBytesForSamples(static_cast<std::int64_t>(firstSample));
        const std::size_t sourceBytes = packedBytesForSamples(static_cast<std::int64_t>(samples));
        if (sourceBytes == 0u || sourceOffset > static_cast<std::size_t>(packedBytes) - sourceBytes) {
            return result;
        }

        // Earlier compression can leave an entire worst-case record before this source chunk.
        // In that case the exclusively owned slot is already a safe input span: copying every
        // source byte to scratch needlessly consumes capture's thermally limited memory bandwidth.
        // Include the pending header/body and LZ4 expansion bound in the proof. Tight prefixes,
        // raw frames and overlapping tails retain the original one-chunk scratch ownership.
        const bool borrowInput = canBorrowPackedChunkInput(
            sourceOffset, sourceBytes, outputOffset, pendingBytes);
        const std::uint8_t* chunkInput = packet + sourceOffset;
        if (borrowInput) {
            result.borrowedInputBytes += static_cast<std::int64_t>(sourceBytes);
        } else {
            std::memcpy(workspace.raw.data(), chunkInput, sourceBytes);
            chunkInput = workspace.raw.data();
        }

        // The current source is either protected in scratch or lies strictly after the complete
        // worst-case destination. Publishing the pending record cannot destroy unread input.
        if (pendingBytes > 0u) {
            // Once the final source chunk has been copied to scratch there is no unread input left
            // in the packet. Its preceding raw record may therefore use the complete guarded slot;
            // limiting it to the tiny final source tail incorrectly rejected valid boundary shapes.
            const std::size_t safeThrough = chunk + 1u == chunkCount
                ? static_cast<std::size_t>(capacity)
                : sourceOffset + sourceBytes;
            if (outputOffset > safeThrough || pendingBytes > safeThrough - outputOffset) return result;
            std::memcpy(packet + outputOffset, workspace.pendingRecord.data(), pendingBytes);
            storedCrc = ::crc32(storedCrc, packet + outputOffset, static_cast<uInt>(pendingBytes));
            outputOffset += pendingBytes;
        }

        // The existing coarse LZ4 search needs almost worst-case bandwidth on real P010 noise.
        // Small exact ranges preserve that noise as residual bits instead of asking flash to
        // absorb expanded byte lanes. Bound this scratch attempt to a strictly smaller chunk;
        // high-entropy input still takes the original LZ4/raw route without changing precision.
        // The complete current source has protected ownership. Once preceding compression
        // leaves enough proved space, emit the identical range block directly into its
        // final record, just as the LZ4 path does below. This eliminates two whole-body
        // copies for those chunks; the first/tight chunk keeps the original lookahead.
        const std::size_t rangeSafeThrough = borrowInput ? sourceOffset : sourceOffset + sourceBytes;
        const std::size_t rangeBodyOffset = outputOffset + kChunkHeaderBytes;
        const std::size_t rangeDirectCapacity = rangeBodyOffset <= rangeSafeThrough
            ? rangeSafeThrough - rangeBodyOffset : 0u;
#if defined(NEO_NPV10_FORCE_LEGACY_RANGE)
        const bool rangeDirect = false; // Standalone paired old/new codec measurement only.
#else
        const bool rangeDirect = rangePacking && rangeDirectCapacity >= sourceBytes - 1u;
#endif
        (void)rangeDirectCapacity;
        // The previous pending record has already been published above. For a tight/first
        // chunk, its reusable body is therefore the final scratch destination, not another
        // intermediate buffer. encodeRange10 writes at most sourceBytes - 1 bytes and the
        // workspace reserves kChunkHeaderBytes + maximumChunkBytes. The source is either
        // scratch-owned or proved disjoint from the packet prefix; pendingRecord never aliases
        // either source. A failed range attempt may dirty this body, but the LZ4/raw fallback
        // replaces it before publication. No input, wire byte, CRC or lookahead lifetime changes.
        if (!rangeDirect && (workspace.pendingRecord.size() < kChunkHeaderBytes ||
            sourceBytes > workspace.pendingRecord.size() - kChunkHeaderBytes)) return result;
        auto* rangeDestination = rangeDirect ? packet + rangeBodyOffset
            : workspace.pendingRecord.data() + kChunkHeaderBytes;
        std::size_t rangeBytes = 0u;
        if (gpuBlocks != nullptr) {
            if (!gpuBlocks->copyChunk(chunk, samples, rangeDestination, sourceBytes - 1u,
                    &rangeBytes)) return {};
        } else if (rangePacking) {
            rangeBytes = encodeRange10(chunkInput, samples, rangeDestination, sourceBytes - 1u);
        }
        if (rangeBytes > 0u) {
            if (rangeDirect) {
                auto* record = packet + outputOffset;
                putBe32(record, kRange10Flag);
                putBe32(record + 4u, static_cast<std::uint32_t>(samples));
                putBe32(record + 8u, static_cast<std::uint32_t>(rangeBytes));
                const auto recordBytes = kChunkHeaderBytes + rangeBytes;
                storedCrc = ::crc32(storedCrc, record, static_cast<uInt>(recordBytes));
                outputOffset += recordBytes;
                pendingBytes = 0u;
                ++compressedChunks;
                continue;
            }
            pendingBytes = kChunkHeaderBytes + rangeBytes;
            auto* record = workspace.pendingRecord.data();
            putBe32(record, kRange10Flag);
            putBe32(record + 4u, static_cast<std::uint32_t>(samples));
            putBe32(record + 8u, static_cast<std::uint32_t>(rangeBytes));
            // The exact bounded range body is already in its pending record. Retain the
            // existing one-chunk publication delay without another full-body copy.
            ++compressedChunks;
            continue;
        }

        const bool attemptCompression = hasWorthwhileSplitLz4Savings(
            chunkInput,
            samples,
            sourceBytes,
            &workspace
        );
        if (attemptCompression && !splitPackedTenBit(
            chunkInput,
            samples,
            workspace.split.data(),
            sourceBytes
        )) return result;

        // A scratch-owned input permits writes through its former source end; borrowed input
        // permits writes only before its source start. Give LZ4 exactly that proved prefix and
        // write its result directly into the final
        // packet. The former scratch -> pendingRecord -> packet route wrote every compressed byte
        // three times; this path writes it once and retains identical NLC2 bytes and CRC semantics.
        const std::size_t safeThrough = borrowInput ? sourceOffset : sourceOffset + sourceBytes;
        const std::size_t bodyOffset = outputOffset + kChunkHeaderBytes;
        const std::size_t directCapacity = bodyOffset <= safeThrough
            ? safeThrough - bodyOffset
            : 0u;
        const std::size_t compressedBytes = attemptCompression && directCapacity > 0u
            ? (upstreamLz4
                ? static_cast<std::size_t>(LZ4_compress_fast_extState(
                    &workspace.upstreamState,
                    reinterpret_cast<const char*>(workspace.split.data()),
                    reinterpret_cast<char*>(packet + bodyOffset),
                    static_cast<int>(sourceBytes),
                    static_cast<int>(std::min<std::size_t>(
                        directCapacity, static_cast<std::size_t>(std::numeric_limits<int>::max())
                    )),
                    upstreamAcceleration
                ))
                : compressLz4Block(
                workspace.split.data(),
                sourceBytes,
                packet + bodyOffset,
                directCapacity,
                &workspace
            ))
            : 0u;
        const bool useCompressed = compressedBytes > 0u && compressedBytes < sourceBytes;
        if (useCompressed) {
            putBe32(packet + outputOffset, kSplit10Lz4Flag);
            putBe32(packet + outputOffset + 4u, static_cast<std::uint32_t>(samples));
            putBe32(packet + outputOffset + 8u, static_cast<std::uint32_t>(compressedBytes));
            const std::size_t recordBytes = kChunkHeaderBytes + compressedBytes;
            storedCrc = ::crc32(
                storedCrc,
                packet + outputOffset,
                static_cast<uInt>(recordBytes)
            );
            outputOffset += recordBytes;
            pendingBytes = 0u;
            ++compressedChunks;
        } else {
            // An incompressible chunk can extend past the unread source prefix by its header. Keep
            // the established one-chunk lookahead for that case: the next source chunk is copied
            // before this pending record is published, so no input byte can be destroyed early.
            pendingBytes = kChunkHeaderBytes + sourceBytes;
            if (pendingBytes > workspace.pendingRecord.size()) return result;
            std::uint8_t* record = workspace.pendingRecord.data();
            putBe32(record, 0u);
            putBe32(record + 4, static_cast<std::uint32_t>(samples));
            putBe32(record + 8, static_cast<std::uint32_t>(sourceBytes));
            std::memcpy(record + kChunkHeaderBytes, chunkInput, sourceBytes);
            ++rawChunks;
        }
    }
    if (outputOffset > static_cast<std::size_t>(capacity) - pendingBytes) return result;
    std::memcpy(packet + outputOffset, workspace.pendingRecord.data(), pendingBytes);
    storedCrc = ::crc32(storedCrc, packet + outputOffset, static_cast<uInt>(pendingBytes));
    outputOffset += pendingBytes;
    std::memcpy(packet, frameHeader, kFrameHeaderBytes);

    result.successful = outputOffset <= static_cast<std::size_t>(maximumBytes);
    result.storedBytes = static_cast<std::int64_t>(outputOffset);
    result.storedCrc32 = static_cast<std::uint32_t>(storedCrc);
    result.packedCrc32 = packedCrc32;
    result.compressedChunks = static_cast<std::int32_t>(compressedChunks);
    result.rawChunks = static_cast<std::int32_t>(rawChunks);
    return result;
}

EncodeResult compressPackedTenBitInPlace(
    std::uint8_t* packet,
    std::int64_t packedBytes,
    std::int64_t sampleCount,
    std::int64_t capacity,
    std::uint32_t packedCrc32,
    bool upstreamLz4
) noexcept {
    return compressPackedTenBitWithEffortInPlace(
        packet, packedBytes, sampleCount, capacity, packedCrc32,
        upstreamLz4, kUpstreamRealtimeAcceleration
    );
}

EncodeResult compressPackedTenBitRealtimeLz4InPlace(
    std::uint8_t* packet,
    std::int64_t packedBytes,
    std::int64_t sampleCount,
    std::int64_t capacity,
    std::uint32_t packedCrc32
) noexcept {
    return compressPackedTenBitInPlace(
        packet, packedBytes, sampleCount, capacity, packedCrc32, true
    );
}

EncodeResult compressPackedTenBitRealtimeLz4InPlace(
    std::uint8_t* packet,
    std::int64_t packedBytes,
    std::int64_t sampleCount,
    std::int64_t capacity,
    std::uint32_t packedCrc32,
    int acceleration
) noexcept {
    return compressPackedTenBitWithEffortInPlace(
        packet, packedBytes, sampleCount, capacity, packedCrc32, true, acceleration
    );
}

EncodeResult compressPackedTenBitRealtimeRangeLz4InPlace(
    std::uint8_t* packet,
    std::int64_t packedBytes,
    std::int64_t sampleCount,
    std::int64_t capacity,
    std::uint32_t packedCrc32,
    int acceleration
) noexcept {
    return compressPackedTenBitWithEffortInPlace(
        packet, packedBytes, sampleCount, capacity, packedCrc32, true, acceleration, true
    );
}

EncodeResult compressPackedTenBitGpuRangeLz4InPlace(
    std::uint8_t* packet, std::int64_t packedBytes, std::int64_t sampleCount,
    std::int64_t capacity, std::uint32_t packedCrc32, int acceleration,
    const GpuRangeBlocksView& gpuBlocks
) noexcept {
    if (packet == nullptr || capacity <= 0 || gpuBlocks.words == nullptr ||
            gpuBlocks.wordCount > std::numeric_limits<std::size_t>::max() / sizeof(std::uint32_t))
        return {};
    const auto a = reinterpret_cast<std::uintptr_t>(packet);
    const auto b = reinterpret_cast<std::uintptr_t>(gpuBlocks.words);
    const auto gpuBytes = gpuBlocks.wordCount * sizeof(std::uint32_t);
    if (static_cast<std::uint64_t>(capacity) > std::numeric_limits<std::uintptr_t>::max() - a ||
            gpuBytes > std::numeric_limits<std::uintptr_t>::max() - b ||
            (a <= b ? static_cast<std::uint64_t>(capacity) > b - a : gpuBytes > a - b))
        return {};
    return compressPackedTenBitWithEffortInPlace(packet, packedBytes, sampleCount,
        capacity, packedCrc32, true, acceleration, true, &gpuBlocks);
}

namespace {
struct ParallelP010ChunkJob {
    static constexpr std::size_t kMaximumChunks = 72u; // Existing maximum 4096x3072 P010 frame.
    const std::uint8_t* y;
    const std::uint8_t* uv;
    std::int32_t width, height, yRowStride, uvRowStride;
    std::uint8_t* packet;
    std::size_t samples, chunkCount;
    int acceleration;
    std::atomic<std::size_t> nextChunk{0u};
    std::atomic<bool> successful{true};
    std::array<EncodeResult, kMaximumChunks> results{};
};

void processParallelP010Chunks(void* opaque) noexcept {
    auto& job = *static_cast<ParallelP010ChunkJob*>(opaque);
    Workspace& workspace = gWorkspace;
    if (!workspace.prepare(packedBytesForSamples(kChunkSamples))) {
        job.successful.store(false, std::memory_order_relaxed);
        return;
    }
    constexpr auto maximumChunkBytes = std::size_t{kChunkSamples} / 4u * 5u;
    const auto lumaSamples = std::size_t{static_cast<unsigned>(job.width)} * job.height;
    while (job.successful.load(std::memory_order_relaxed)) {
        // A single chunk is the work claim: a slow/preempted helper cannot hold a whole frame.
        const auto chunk = job.nextChunk.fetch_add(1u, std::memory_order_relaxed);
        if (chunk >= job.chunkCount) break;
        const auto first = chunk * kChunkSamples;
        const auto count = std::min<std::size_t>(kChunkSamples, job.samples - first);
        const auto rawBytes = count / 4u * 5u;
        auto* record = job.packet + kFrameHeaderBytes + chunk * (maximumChunkBytes + kChunkHeaderBytes);
        auto* body = record + kChunkHeaderBytes;
        bool luma = first < lumaSamples;
        const auto planeIndex = luma ? first : first - lumaSamples;
        auto column = planeIndex % static_cast<std::size_t>(job.width);
        const auto row = planeIndex / static_cast<std::size_t>(job.width);
        auto stride = luma ? job.yRowStride : job.uvRowStride;
        auto rowsRemaining = static_cast<std::size_t>(luma ? job.height : job.height / 2) - row;
        const auto* sourceRow = (luma ? job.y : job.uv) + row * stride;
        bool rangeFits = true;
        std::size_t rangeBytes = 0u;
        for (std::size_t block = 0u; block < count; block += 64u) {
            std::size_t bytes = 0u;
            if (!packAndEncodeP010RangeBlock(sourceRow + column * 2u,
                    workspace.raw.data() + block / 4u * 5u, body + rangeBytes,
                    rangeFits ? rawBytes - 1u - rangeBytes : 0u, &bytes)) {
                job.successful.store(false, std::memory_order_relaxed);
                return;
            }
            if (bytes == 0u) rangeFits = false;
            else rangeBytes += bytes;
            column += 64u;
            if (column == static_cast<std::size_t>(job.width)) {
                column = 0u;
                if (--rowsRemaining != 0u) sourceRow += stride;
                else if (luma) {
                    luma = false;
                    sourceRow = job.uv;
                    stride = job.uvRowStride;
                    rowsRemaining = static_cast<std::size_t>(job.height / 2);
                }
            }
        }
        auto& result = job.results[chunk];
        result.packedCrc32 = static_cast<std::uint32_t>(::crc32(
            ::crc32(0L, Z_NULL, 0), workspace.raw.data(), static_cast<uInt>(rawBytes)));
        std::uint32_t flag = kRange10Flag;
        auto bytes = rangeBytes;
        if (!rangeFits) {
            const bool attempt = hasWorthwhileSplitLz4Savings(workspace.raw.data(), count, rawBytes, &workspace);
            if (attempt && !splitPackedTenBit(workspace.raw.data(), count, workspace.split.data(), rawBytes)) {
                job.successful.store(false, std::memory_order_relaxed);
                return;
            }
            const int compressed = attempt ? LZ4_compress_fast_extState(&workspace.upstreamState,
                reinterpret_cast<const char*>(workspace.split.data()), reinterpret_cast<char*>(body),
                static_cast<int>(rawBytes), static_cast<int>(rawBytes - 1u), job.acceleration) : 0;
            if (compressed > 0) { bytes = static_cast<std::size_t>(compressed); flag = kSplit10Lz4Flag; }
            else { bytes = rawBytes; flag = 0u; std::memcpy(body, workspace.raw.data(), rawBytes); }
        }
        putBe32(record, flag);
        putBe32(record + 4u, static_cast<std::uint32_t>(count));
        putBe32(record + 8u, static_cast<std::uint32_t>(bytes));
        result.storedBytes = static_cast<std::int64_t>(kChunkHeaderBytes + bytes);
        result.storedCrc32 = static_cast<std::uint32_t>(::crc32(
            ::crc32(0L, Z_NULL, 0), record, static_cast<uInt>(result.storedBytes)));
        result.rawChunks = flag == 0u ? 1 : 0;
        result.compressedChunks = flag == 0u ? 0 : 1;
        result.successful = true;
    }
}
} // namespace

bool prepareP010LosslessChunkWorkspace() noexcept {
    return gWorkspace.prepare(packedBytesForSamples(kChunkSamples));
}

EncodeResult compressP010PlanesParallelRangeLz4(
    const std::uint8_t* y, std::int64_t yCapacity,
    const std::uint8_t* uv, std::int64_t uvCapacity,
    std::int32_t width, std::int32_t height,
    std::int32_t yRowStride, std::int32_t uvRowStride,
    std::uint8_t* packet, std::int64_t capacity, int acceleration,
    LosslessChunkDispatcher dispatch
) noexcept {
    if (y == nullptr || uv == nullptr || packet == nullptr || dispatch == nullptr || width <= 0 ||
        height <= 0 || (width & 63) != 0 || (height & 1) != 0 || acceleration < 1 || acceleration > 65537 ||
        yRowStride < std::int64_t{width} * 2 || uvRowStride < std::int64_t{width} * 2) return {};
    const auto lumaSamples = std::int64_t{width} * height;
    const auto samples = lumaSamples + lumaSamples / 2;
    if (samples > std::int64_t{ParallelP010ChunkJob::kMaximumChunks} * kChunkSamples) return {};
    const auto packedBytes = samples / 4 * 5;
    const auto maximumBytes = maximumStoredBytes(packedBytes, samples);
    const auto requiredY = std::int64_t{height - 1} * yRowStride + std::int64_t{width} * 2;
    const auto requiredUv = std::int64_t{height / 2 - 1} * uvRowStride + std::int64_t{width} * 2;
    if (capacity < maximumBytes || yCapacity < requiredY || uvCapacity < requiredUv) return {};
    const auto disjoint = [](const void* a, std::int64_t aBytes, const void* b, std::int64_t bBytes) {
        const auto first = reinterpret_cast<std::uintptr_t>(a), second = reinterpret_cast<std::uintptr_t>(b);
        return aBytes > 0 && bBytes > 0 &&
            static_cast<std::uint64_t>(aBytes) <= std::numeric_limits<std::uintptr_t>::max() - first &&
            static_cast<std::uint64_t>(bBytes) <= std::numeric_limits<std::uintptr_t>::max() - second &&
            (first <= second ? static_cast<std::uint64_t>(aBytes) <= second - first
                             : static_cast<std::uint64_t>(bBytes) <= first - second);
    };
    if (!disjoint(y, requiredY, packet, capacity) || !disjoint(uv, requiredUv, packet, capacity)) return {};
    const auto chunks = static_cast<std::size_t>((samples + kChunkSamples - 1) / kChunkSamples);
    ParallelP010ChunkJob job{y, uv, width, height, yRowStride, uvRowStride, packet,
        static_cast<std::size_t>(samples), chunks, acceleration};
    // The dispatcher joins all helpers even after failure. No image can be released while a
    // helper still reads its planes, and no incomplete prefix is published as a valid frame.
    if (!dispatch(&job, processParallelP010Chunks) || !job.successful.load(std::memory_order_relaxed)) return {};
    EncodeResult result{};
    uLong packedCrc = ::crc32(0L, Z_NULL, 0), bodyCrc = ::crc32(0L, Z_NULL, 0);
    std::size_t outputOffset = kFrameHeaderBytes;
    constexpr auto chunkStride = std::size_t{kChunkSamples} / 4u * 5u + kChunkHeaderBytes;
    for (std::size_t chunk = 0u; chunk < chunks; ++chunk) {
        const auto& part = job.results[chunk];
        const auto sourceOffset = kFrameHeaderBytes + chunk * chunkStride;
        const auto count = std::min<std::size_t>(kChunkSamples, static_cast<std::size_t>(samples) - chunk * kChunkSamples);
        const auto rawBytes = count / 4u * 5u;
        if (!part.successful || part.storedBytes < kChunkHeaderBytes ||
            part.storedBytes > static_cast<std::int64_t>(rawBytes + kChunkHeaderBytes) || outputOffset > sourceOffset)
            return {};
        // Compaction is forward-only after the full join. Every destination ends at or before
        // its source end, so it cannot damage another unread chunk, including a raw final tail.
        std::memmove(packet + outputOffset, packet + sourceOffset, static_cast<std::size_t>(part.storedBytes));
        outputOffset += static_cast<std::size_t>(part.storedBytes);
        packedCrc = ::crc32_combine(packedCrc, part.packedCrc32, static_cast<z_off_t>(rawBytes));
        bodyCrc = ::crc32_combine(bodyCrc, part.storedCrc32, static_cast<z_off_t>(part.storedBytes));
        result.compressedChunks += part.compressedChunks;
        result.rawChunks += part.rawChunks;
    }
    std::memcpy(packet, kMagic, sizeof(kMagic));
    putBe32(packet + 4u, kRangeVersion);
    putBe32(packet + 8u, static_cast<std::uint32_t>(packedBytes));
    putBe32(packet + 12u, static_cast<std::uint32_t>(samples * 2));
    putBe32(packet + 16u, static_cast<std::uint32_t>(samples));
    putBe32(packet + 20u, kChunkSamples);
    putBe32(packet + 24u, static_cast<std::uint32_t>(chunks));
    putBe32(packet + 28u, static_cast<std::uint32_t>(packedCrc));
    result.successful = outputOffset <= static_cast<std::size_t>(maximumBytes);
    result.storedBytes = static_cast<std::int64_t>(outputOffset);
    result.packedCrc32 = static_cast<std::uint32_t>(packedCrc);
    result.storedCrc32 = static_cast<std::uint32_t>(::crc32_combine(
        ::crc32(0L, packet, kFrameHeaderBytes), bodyCrc, static_cast<z_off_t>(outputOffset - kFrameHeaderBytes)));
    return result;
}

EncodeResult compressP010PlanesRealtimeRangeLz4(
    const std::uint8_t* y, std::int64_t yCapacity,
    const std::uint8_t* uv, std::int64_t uvCapacity,
    std::int32_t width, std::int32_t height,
    std::int32_t yRowStride, std::int32_t uvRowStride,
    std::uint8_t* packet, std::int64_t capacity, int acceleration
) noexcept {
    EncodeResult result{};
    if (y == nullptr || uv == nullptr || packet == nullptr || width <= 0 || height <= 0 ||
        (width & 63) != 0 || (height & 1) != 0 || acceleration < 1 || acceleration > 65537 ||
        yRowStride < std::int64_t{width} * 2 || uvRowStride < std::int64_t{width} * 2) return result;
    const std::int64_t ySamples = std::int64_t{width} * height;
    const std::int64_t samples64 = ySamples + ySamples / 2;
    // All NLC2 header extents are u32, including the restored P010 byte count.
    if (samples64 > std::numeric_limits<std::uint32_t>::max() / 2u) return result;
    const std::int64_t packedBytes = samples64 / 4 * 5;
    const std::int64_t maximumBytes = maximumStoredBytes(packedBytes, samples64);
    const std::int64_t requiredY = std::int64_t{height - 1} * yRowStride + std::int64_t{width} * 2;
    const std::int64_t requiredUv = std::int64_t{height / 2 - 1} * uvRowStride + std::int64_t{width} * 2;
    if (maximumBytes <= 0 || capacity < maximumBytes || yCapacity < requiredY ||
        uvCapacity < requiredUv || static_cast<std::uint64_t>(capacity) >
            std::numeric_limits<std::size_t>::max()) return result;
    const auto disjoint = [](const void* first, std::int64_t firstBytes,
                             const void* second, std::int64_t secondBytes) noexcept {
        const auto a = reinterpret_cast<std::uintptr_t>(first);
        const auto b = reinterpret_cast<std::uintptr_t>(second);
        if (firstBytes <= 0 || secondBytes <= 0 ||
            static_cast<std::uint64_t>(firstBytes) > std::numeric_limits<std::uintptr_t>::max() - a ||
            static_cast<std::uint64_t>(secondBytes) > std::numeric_limits<std::uintptr_t>::max() - b) return false;
        return a <= b ? static_cast<std::uint64_t>(firstBytes) <= b - a
                      : static_cast<std::uint64_t>(secondBytes) <= a - b;
    };
    // The output cannot overwrite any unread plane or its padded rows. Validation
    // precedes workspace allocation and every source read/destination write.
    if (!disjoint(y, requiredY, packet, capacity) ||
        !disjoint(uv, requiredUv, packet, capacity)) return result;
    Workspace& workspace = gWorkspace;
    if (!workspace.prepare(packedBytesForSamples(kChunkSamples))) return result;
    const auto samples = static_cast<std::size_t>(samples64);
    const auto rowSamples = static_cast<std::size_t>(width);
    const auto chunkCount = static_cast<std::uint32_t>((samples64 + kChunkSamples - 1) / kChunkSamples);
#if defined(NEO_NPV10_FORCE_INDEXED_P010_PLANES)
    const auto lumaSamples = static_cast<std::size_t>(ySamples);
#else
    // A block never straddles a row: validated width and every block are multiples
    // of 64 samples. Keep a forward-only plane cursor instead of dividing the
    // absolute sample index by a runtime width for each of the 294,912 blocks in
    // a 12MP frame. Rows may be padded and a chunk may cross rows or the Y/UV
    // boundary. Do not construct a next-row pointer after the final chroma row.
    const std::uint8_t* sourceRow = y;
    std::size_t sourceColumn = 0u;
    std::size_t sourceRowsRemaining = static_cast<std::size_t>(height);
    std::size_t sourceRowStride = static_cast<std::size_t>(yRowStride);
    bool sourceIsLuma = true;
#endif
    std::size_t outputOffset = kFrameHeaderBytes;
    uLong packedCrc = ::crc32(0L, Z_NULL, 0);
    uLong bodyCrc = ::crc32(0L, Z_NULL, 0);
    for (std::size_t first = 0u; first < samples; first += kChunkSamples) {
        const auto count = std::min<std::size_t>(kChunkSamples, samples - first);
        const auto sourceBytes = count / 4u * 5u;
        const auto bodyOffset = outputOffset + kChunkHeaderBytes;
        if (bodyOffset > static_cast<std::size_t>(capacity) ||
            sourceBytes > static_cast<std::size_t>(capacity) - bodyOffset) return {};
        std::size_t rangeBytes = 0u;
        bool rangeFits = true;
        // The frame never exists as a packed full-frame intermediate. Keep one
        // canonical chunk only for its original CRC and LZ4/raw escape; emit range
        // blocks directly into the exclusively owned, pre-faulted output slot.
        for (std::size_t block = 0u; block < count; block += 64u) {
#if defined(NEO_NPV10_FORCE_INDEXED_P010_PLANES)
            // Standalone paired benchmark only; never a runtime quality option.
            const auto absolute = first + block;
            const bool luma = absolute < lumaSamples;
            const auto position = luma ? absolute : absolute - lumaSamples;
            const auto* plane = luma ? y : uv;
            const auto stride = static_cast<std::size_t>(luma ? yRowStride : uvRowStride);
            const auto* input = plane + (position / rowSamples) * stride + (position % rowSamples) * 2u;
#else
            const auto* input = sourceRow + sourceColumn * 2u;
#endif
            std::size_t encodedBytes = 0u;
            if (!packAndEncodeP010RangeBlock(input, workspace.raw.data() + block / 4u * 5u,
                    packet + bodyOffset + rangeBytes,
                    rangeFits ? sourceBytes - 1u - rangeBytes : 0u, &encodedBytes)) return {};
            if (encodedBytes == 0u) rangeFits = false;
            else rangeBytes += encodedBytes;
#if !defined(NEO_NPV10_FORCE_INDEXED_P010_PLANES)
            sourceColumn += 64u;
            if (sourceColumn == rowSamples) {
                sourceColumn = 0u;
                if (--sourceRowsRemaining != 0u) {
                    sourceRow += sourceRowStride;
                } else if (sourceIsLuma) {
                    sourceRow = uv;
                    sourceRowsRemaining = static_cast<std::size_t>(height / 2);
                    sourceRowStride = static_cast<std::size_t>(uvRowStride);
                    sourceIsLuma = false;
                }
            }
#endif
        }
        packedCrc = ::crc32(packedCrc, workspace.raw.data(), static_cast<uInt>(sourceBytes));
        std::uint32_t flag = kRange10Flag;
        std::size_t bytes = rangeBytes;
        if (!rangeFits) {
            const bool attempt = hasWorthwhileSplitLz4Savings(workspace.raw.data(), count,
                sourceBytes, &workspace);
            if (attempt && !splitPackedTenBit(workspace.raw.data(), count,
                    workspace.split.data(), sourceBytes)) return {};
            const int compressed = attempt ? LZ4_compress_fast_extState(&workspace.upstreamState,
                reinterpret_cast<const char*>(workspace.split.data()),
                reinterpret_cast<char*>(packet + bodyOffset), static_cast<int>(sourceBytes),
                static_cast<int>(sourceBytes - 1u), acceleration) : 0;
            if (compressed > 0) {
                bytes = static_cast<std::size_t>(compressed);
                flag = kSplit10Lz4Flag;
            } else {
                bytes = sourceBytes;
                flag = 0u;
                std::memcpy(packet + bodyOffset, workspace.raw.data(), sourceBytes);
            }
        }
        if (flag == 0u) ++result.rawChunks;
        else ++result.compressedChunks;
        auto* record = packet + outputOffset;
        putBe32(record, flag);
        putBe32(record + 4u, static_cast<std::uint32_t>(count));
        putBe32(record + 8u, static_cast<std::uint32_t>(bytes));
        bodyCrc = ::crc32(bodyCrc, record, static_cast<uInt>(kChunkHeaderBytes + bytes));
        outputOffset = bodyOffset + bytes;
    }
    std::memcpy(packet, kMagic, sizeof(kMagic));
    putBe32(packet + 4u, kRangeVersion);
    putBe32(packet + 8u, static_cast<std::uint32_t>(packedBytes));
    putBe32(packet + 12u, static_cast<std::uint32_t>(samples64 * 2));
    putBe32(packet + 16u, static_cast<std::uint32_t>(samples64));
    putBe32(packet + 20u, kChunkSamples);
    putBe32(packet + 24u, chunkCount);
    putBe32(packet + 28u, static_cast<std::uint32_t>(packedCrc));
    result.successful = outputOffset <= static_cast<std::size_t>(maximumBytes);
    result.storedBytes = static_cast<std::int64_t>(outputOffset);
    result.packedCrc32 = static_cast<std::uint32_t>(packedCrc);
    result.storedCrc32 = static_cast<std::uint32_t>(::crc32_combine(
        ::crc32(0L, packet, kFrameHeaderBytes), bodyCrc,
        static_cast<z_off_t>(outputOffset - kFrameHeaderBytes)));
    return result;
}

EncodeResult framePackedTenBitRealtimeInPlace(
    std::uint8_t* packet,
    std::int64_t packedBytes,
    std::int64_t sampleCount64,
    std::int64_t capacity,
    std::uint32_t packedCrc32
) noexcept {
    EncodeResult result{};
    const std::int64_t storedBytes64 = maximumStoredBytes(packedBytes, sampleCount64);
    if (packet == nullptr || sampleCount64 <= 0 || storedBytes64 <= 0 ||
        capacity < storedBytes64 ||
        sampleCount64 > std::numeric_limits<std::uint32_t>::max() ||
        packedBytes > std::numeric_limits<std::uint32_t>::max()) {
        return result;
    }

    // kChunkSamples is deliberately a whole packed-10 group. This makes every chunk boundary byte
    // aligned and lets the DMC recorder expand the packed source backwards without bit rewriting.
    static_assert(kChunkSamples % 4 == 0, "NLC2 realtime chunk boundaries must be byte aligned");
    const std::uint32_t sampleCount = static_cast<std::uint32_t>(sampleCount64);
    const std::uint32_t chunkCount = static_cast<std::uint32_t>(
        (sampleCount64 + kChunkSamples - 1) / kChunkSamples
    );
    const std::size_t storedBytes = static_cast<std::size_t>(storedBytes64);

    // Expand from the tail. memmove handles overlap inside one slot, and reverse traversal ensures
    // that inserting a later record can never overwrite source bytes belonging to an earlier one.
    for (std::uint32_t remaining = chunkCount; remaining > 0u; --remaining) {
        const std::uint32_t chunk = remaining - 1u;
        const std::size_t firstSample = static_cast<std::size_t>(chunk) * kChunkSamples;
        const std::size_t samples = std::min<std::size_t>(
            kChunkSamples,
            static_cast<std::size_t>(sampleCount) - firstSample
        );
        const std::size_t sourceOffset = packedBytesForSamples(
            static_cast<std::int64_t>(firstSample)
        );
        const std::size_t sourceBytes = packedBytesForSamples(static_cast<std::int64_t>(samples));
        const std::size_t recordOffset = kFrameHeaderBytes + sourceOffset +
            static_cast<std::size_t>(chunk) * kChunkHeaderBytes;
        const std::size_t bodyOffset = recordOffset + kChunkHeaderBytes;
        if (sourceBytes == 0u || sourceOffset > static_cast<std::size_t>(packedBytes) - sourceBytes ||
            bodyOffset > storedBytes || sourceBytes > storedBytes - bodyOffset) {
            return result;
        }
        std::memmove(packet + bodyOffset, packet + sourceOffset, sourceBytes);
        putBe32(packet + recordOffset, 0u);
        putBe32(packet + recordOffset + 4u, static_cast<std::uint32_t>(samples));
        putBe32(packet + recordOffset + 8u, static_cast<std::uint32_t>(sourceBytes));
    }

    std::uint8_t frameHeader[kFrameHeaderBytes]{};
    std::memcpy(frameHeader, kMagic, sizeof(kMagic));
    putBe32(frameHeader + 4, kVersion);
    putBe32(frameHeader + 8, static_cast<std::uint32_t>(packedBytes));
    putBe32(frameHeader + 12, sampleCount * 2u);
    putBe32(frameHeader + 16, sampleCount);
    putBe32(frameHeader + 20, static_cast<std::uint32_t>(kChunkSamples));
    putBe32(frameHeader + 24, chunkCount);
    putBe32(frameHeader + 28, packedCrc32);
    std::memcpy(packet, frameHeader, kFrameHeaderBytes);

    // The stored CRC protects the complete packet, including every raw-chunk header. zlib uses the
    // ARM CRC/PMULL implementation when supplied by the platform build, avoiding a second bespoke
    // scalar checksum implementation while retaining byte-for-byte compatibility.
    uLong storedCrc = ::crc32(0L, Z_NULL, 0);
    std::size_t crcOffset = 0u;
    while (crcOffset < storedBytes) {
        const uInt crcBytes = static_cast<uInt>(std::min<std::size_t>(
            storedBytes - crcOffset,
            std::numeric_limits<uInt>::max()
        ));
        storedCrc = ::crc32(storedCrc, packet + crcOffset, crcBytes);
        crcOffset += crcBytes;
    }

    result.successful = true;
    result.storedBytes = storedBytes64;
    result.storedCrc32 = static_cast<std::uint32_t>(storedCrc);
    result.packedCrc32 = packedCrc32;
    result.compressedChunks = 0;
    result.rawChunks = static_cast<std::int32_t>(chunkCount);
    return result;
}

bool decodePacketToP010(
    const std::uint8_t* packet,
    std::int64_t packetBytes,
    std::uint8_t* p010,
    std::int64_t p010Bytes
) noexcept {
    if (packet == nullptr || p010 == nullptr || packetBytes < kFrameHeaderBytes ||
        p010Bytes <= 0 || (p010Bytes & 1) != 0 ||
        static_cast<std::uint64_t>(packetBytes) > std::numeric_limits<std::size_t>::max() ||
        static_cast<std::uint64_t>(p010Bytes) > std::numeric_limits<std::size_t>::max() ||
        std::memcmp(packet, kMagic, sizeof(kMagic)) != 0 ||
        (readBe32(packet + 4) != kVersion && readBe32(packet + 4) != kRangeVersion)) return false;
    const auto version = readBe32(packet + 4);
    const auto packetExtent = static_cast<std::size_t>(packetBytes);
    const std::uint32_t packedBytes = readBe32(packet + 8);
    const std::uint32_t declaredP010Bytes = readBe32(packet + 12);
    const std::uint32_t sampleCount = readBe32(packet + 16);
    const std::uint32_t chunkSamples = readBe32(packet + 20);
    const std::uint32_t chunkCount = readBe32(packet + 24);
    const std::uint32_t expectedPackedCrc = readBe32(packet + 28);
    if (declaredP010Bytes != static_cast<std::uint64_t>(p010Bytes) ||
        sampleCount != static_cast<std::uint64_t>(p010Bytes / 2) ||
        chunkSamples != static_cast<std::uint32_t>(kChunkSamples) ||
        packedBytes != packedBytesForSamples(sampleCount) ||
        chunkCount != (static_cast<std::uint64_t>(sampleCount) + kChunkSamples - 1u) /
            kChunkSamples) return false;

    Workspace& workspace = gWorkspace;
    const std::size_t maximumChunkBytes = packedBytesForSamples(kChunkSamples);
    if (!workspace.prepare(maximumChunkBytes)) return false;
    std::vector<std::uint8_t>& packed = workspace.pendingRecord;
    if (packed.size() < kChunkHeaderBytes + maximumChunkBytes) return false;
    std::size_t inputOffset = kFrameHeaderBytes;
    std::size_t targetOffset = 0u;
    std::uint32_t decodedSamples = 0u;
    uLong packedCrc = ::crc32(0L, Z_NULL, 0);
    for (std::uint32_t chunk = 0u; chunk < chunkCount; ++chunk) {
        if (inputOffset > packetExtent || packetExtent - inputOffset < kChunkHeaderBytes) return false;
        const std::uint32_t flags = readBe32(packet + inputOffset);
        const std::uint32_t samples = readBe32(packet + inputOffset + 4u);
        const std::uint32_t storedBytes = readBe32(packet + inputOffset + 8u);
        inputOffset += kChunkHeaderBytes;
        const std::uint32_t expectedSamples = std::min<std::uint32_t>(
            kChunkSamples,
            sampleCount - decodedSamples
        );
        const std::size_t expectedChunkBytes = packedBytesForSamples(samples);
        // Subtract only the already validated cursor, never an untrusted stored length.
        // packetBytes - storedBytes previously wrapped when a corrupt length exceeded the
        // entire packet, admitting an out-of-bounds memcpy/LZ4 read on truncated recordings.
        // This also preserves the check on 32-bit Android without narrowing the packet extent.
        if (inputOffset > packetExtent || storedBytes > packetExtent - inputOffset) return false;
        if (samples != expectedSamples || storedBytes == 0u ||
            expectedChunkBytes > maximumChunkBytes) return false;

        if (flags == 0u) {
            if (storedBytes != expectedChunkBytes) return false;
            std::memcpy(packed.data(), packet + inputOffset, expectedChunkBytes);
        } else if (flags == kSplit10Lz4Flag) {
            if (!decompressLz4Block(
                    packet + inputOffset,
                    storedBytes,
                    workspace.split.data(),
                    expectedChunkBytes
                ) || !joinSplitTenBit(
                    workspace.split.data(),
                    expectedChunkBytes,
                    samples,
                    packed.data(),
                    expectedChunkBytes
                )) return false;
        } else if (flags == kRange10Flag && version == kRangeVersion) {
            if (!decodeRange10(packet + inputOffset, storedBytes, samples,
                              packed.data(), expectedChunkBytes)) return false;
        } else {
            return false;
        }
        packedCrc = ::crc32(packedCrc, packed.data(), static_cast<uInt>(expectedChunkBytes));
        writeP010FromPacked(packed.data(), samples, p010 + targetOffset);
        targetOffset += static_cast<std::size_t>(samples) * 2u;
        decodedSamples += samples;
        inputOffset += storedBytes;
    }
    return inputOffset == static_cast<std::size_t>(packetBytes) &&
        targetOffset == static_cast<std::size_t>(p010Bytes) &&
        decodedSamples == sampleCount &&
        static_cast<std::uint32_t>(packedCrc) == expectedPackedCrc;
}

}  // namespace neoproc::npv10::lossless

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10PacketCodecEngine_nativeDecodeChunkedLosslessP010Packet(
    JNIEnv* env,
    jclass,
    jobject sourcePacket,
    jint encodedBytes,
    jint decodedBytes,
    jobject targetP010
) {
    auto* source = static_cast<const std::uint8_t*>(env->GetDirectBufferAddress(sourcePacket));
    auto* target = static_cast<std::uint8_t*>(env->GetDirectBufferAddress(targetP010));
    const jlong sourceCapacity = env->GetDirectBufferCapacity(sourcePacket);
    const jlong targetCapacity = env->GetDirectBufferCapacity(targetP010);
    if (source == nullptr || target == nullptr || encodedBytes <= 0 || decodedBytes <= 0 ||
        sourceCapacity < encodedBytes || targetCapacity < decodedBytes) return JNI_FALSE;
    return neoproc::npv10::lossless::decodePacketToP010(
        source,
        static_cast<std::int64_t>(encodedBytes),
        target,
        static_cast<std::int64_t>(decodedBytes)
    ) ? JNI_TRUE : JNI_FALSE;
}
