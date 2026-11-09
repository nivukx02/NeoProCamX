#include <jni.h>

#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <android/log.h>
#include <android/performance_hint.h>
#include <android/trace.h>
#include <vulkan/vulkan.h>

#if defined(__ARM_NEON)
#include <arm_neon.h>
#endif

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <dlfcn.h>
#include <limits>
#include <mutex>
#include <pthread.h>
#include <sched.h>
#include <sys/mman.h>
#include <sys/resource.h>
#include <thread>
#include <unistd.h>
#include <zlib.h>

#include "npv10_dmc_native.h"
#include "npv10_lossless_chunk_codec.h"
#include "npv10_gpu_range_encoder.h"

#define NPV10_RAW_TAG "CamXNpv10Raw"
#define NPV10_RAW_I(...) __android_log_print(ANDROID_LOG_INFO, NPV10_RAW_TAG, __VA_ARGS__)
#define NPV10_RAW_W(...) __android_log_print(ANDROID_LOG_WARN, NPV10_RAW_TAG, __VA_ARGS__)

namespace {

class ScopedNpv10TraceSection final {
public:
    explicit ScopedNpv10TraceSection(const char* name) noexcept {
        ATrace_beginSection(name);
    }

    ~ScopedNpv10TraceSection() {
        ATrace_endSection();
    }

    ScopedNpv10TraceSection(const ScopedNpv10TraceSection&) = delete;
    ScopedNpv10TraceSection& operator=(const ScopedNpv10TraceSection&) = delete;
};

constexpr uint16_t kP010Shift = 6u;
constexpr uint16_t kRaw12Mask = 0x0FFFu;
constexpr uint16_t kRaw14Mask = 0x3FFFu;

uint16_t crc4Raw12(uint16_t sample) {
    const uint16_t raw = sample & kRaw12Mask;
    return static_cast<uint16_t>((raw ^ (raw >> 4u) ^ (raw >> 8u)) & 0x0Fu);
}

uint16_t packRaw12Word(uint16_t sample) {
    const uint16_t raw = sample & kRaw12Mask;
    return static_cast<uint16_t>(((raw >> 2u) << kP010Shift) | ((raw & 0x03u) << 4u) | crc4Raw12(raw));
}

uint16_t packRaw14Word(uint16_t sample, uint16_t parity) {
    const uint16_t raw = sample & kRaw14Mask;
    return static_cast<uint16_t>(((raw >> 4u) << kP010Shift) | ((raw & 0x0Fu) << 2u) | (parity & 0x03u));
}

uint16_t unpackRaw12Word(uint16_t word) {
    return static_cast<uint16_t>(((word >> kP010Shift) << 2u) | ((word >> 4u) & 0x03u));
}

uint16_t unpackRaw14Word(uint16_t word) {
    return static_cast<uint16_t>(((word >> kP010Shift) << 4u) | ((word >> 2u) & 0x0Fu));
}

uint16_t readSample(
    const uint8_t* plane,
    int row,
    int col,
    int rowStride,
    int pixelStride,
    int bitDepth,
    int fallback
) {
    if (plane == nullptr || rowStride <= 0 || pixelStride <= 0) {
        return static_cast<uint16_t>(fallback);
    }
    const int offset = row * rowStride + col * pixelStride;
    if (bitDepth >= 10) {
        const uint16_t packed = static_cast<uint16_t>(plane[offset] | (plane[offset + 1] << 8u));
        return packed > 1023u ? static_cast<uint16_t>(packed >> kP010Shift) : packed;
    }
    return static_cast<uint16_t>(plane[offset] << 2u);
}

void packP010Scalar(
    const uint8_t* y,
    const uint8_t* u,
    const uint8_t* v,
    int width,
    int height,
    int yRowStride,
    int uRowStride,
    int vRowStride,
    int yPixelStride,
    int uPixelStride,
    int vPixelStride,
    int bitDepth,
    uint16_t* target
) {
    int cursor = 0;
    for (int row = 0; row < height; ++row) {
        for (int col = 0; col < width; ++col) {
            target[cursor++] = static_cast<uint16_t>(readSample(
                y, row, col, yRowStride, yPixelStride, bitDepth, 0
            ) << kP010Shift);
        }
    }

    const int chromaWidth = (width + 1) / 2;
    const int chromaHeight = (height + 1) / 2;
    for (int row = 0; row < chromaHeight; ++row) {
        for (int col = 0; col < chromaWidth; ++col) {
            target[cursor++] = static_cast<uint16_t>(readSample(
                u, row, col, uRowStride, uPixelStride, bitDepth, 512
            ) << kP010Shift);
            target[cursor++] = static_cast<uint16_t>(readSample(
                v, row, col, vRowStride, vPixelStride, bitDepth, 512
            ) << kP010Shift);
        }
    }
}

void packRaw12Native(const uint16_t* source, uint16_t* target, int count) {
    int index = 0;
#if defined(__ARM_NEON)
    for (; index + 8 <= count; index += 8) {
        const uint16x8_t raw = vandq_u16(vld1q_u16(source + index), vdupq_n_u16(kRaw12Mask));
        const uint16x8_t msb = vshlq_n_u16(vshrq_n_u16(raw, 2), kP010Shift);
        const uint16x8_t lsb = vshlq_n_u16(vandq_u16(raw, vdupq_n_u16(0x03u)), 4);
        const uint16x8_t crc = vandq_u16(
            veorq_u16(veorq_u16(raw, vshrq_n_u16(raw, 4)), vshrq_n_u16(raw, 8)),
            vdupq_n_u16(0x0Fu)
        );
        vst1q_u16(target + index, vorrq_u16(vorrq_u16(msb, lsb), crc));
    }
#endif
    for (; index < count; ++index) {
        target[index] = packRaw12Word(source[index]);
    }
}

void packRaw14Native(const uint16_t* source, uint16_t* target, int count, int paritySeed) {
    int index = 0;
#if defined(__ARM_NEON)
    for (; index + 8 <= count; index += 8) {
        const uint16x8_t raw = vandq_u16(vld1q_u16(source + index), vdupq_n_u16(kRaw14Mask));
        const uint16x8_t msb = vshlq_n_u16(vshrq_n_u16(raw, 4), kP010Shift);
        const uint16x8_t lsb = vshlq_n_u16(vandq_u16(raw, vdupq_n_u16(0x0Fu)), 2);
        uint16_t parityValues[8];
        for (int lane = 0; lane < 8; ++lane) {
            parityValues[lane] = static_cast<uint16_t>((paritySeed + index + lane) & 0x03);
        }
        const uint16x8_t parity = vld1q_u16(parityValues);
        vst1q_u16(target + index, vorrq_u16(vorrq_u16(msb, lsb), parity));
    }
#endif
    for (; index < count; ++index) {
        target[index] = packRaw14Word(source[index], static_cast<uint16_t>(paritySeed + index));
    }
}

void unpackRaw12Native(const uint16_t* source, uint16_t* target, int count) {
    int index = 0;
#if defined(__ARM_NEON)
    for (; index + 8 <= count; index += 8) {
        const uint16x8_t word = vld1q_u16(source + index);
        const uint16x8_t msb = vshlq_n_u16(vshrq_n_u16(word, kP010Shift), 2);
        const uint16x8_t lsb = vandq_u16(vshrq_n_u16(word, 4), vdupq_n_u16(0x03u));
        vst1q_u16(target + index, vorrq_u16(msb, lsb));
    }
#endif
    for (; index < count; ++index) {
        target[index] = unpackRaw12Word(source[index]);
    }
}

void unpackRaw14Native(const uint16_t* source, uint16_t* target, int count) {
    int index = 0;
#if defined(__ARM_NEON)
    for (; index + 8 <= count; index += 8) {
        const uint16x8_t word = vld1q_u16(source + index);
        const uint16x8_t msb = vshlq_n_u16(vshrq_n_u16(word, kP010Shift), 4);
        const uint16x8_t lsb = vandq_u16(vshrq_n_u16(word, 2), vdupq_n_u16(0x0Fu));
        vst1q_u16(target + index, vorrq_u16(msb, lsb));
    }
#endif
    for (; index < count; ++index) {
        target[index] = unpackRaw14Word(source[index]);
    }
}

bool directU16(jobject buffer, JNIEnv* env, uint16_t** ptr, jlong* capacityBytes) {
    if (buffer == nullptr) return false;
    void* address = env->GetDirectBufferAddress(buffer);
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (address == nullptr || capacity <= 0) return false;
    *ptr = static_cast<uint16_t*>(address);
    *capacityBytes = capacity;
    return true;
}

bool directU8(jobject buffer, JNIEnv* env, uint8_t** ptr, jlong* capacityBytes) {
    if (buffer == nullptr) return false;
    void* address = env->GetDirectBufferAddress(buffer);
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (address == nullptr || capacity <= 0) return false;
    *ptr = static_cast<uint8_t*>(address);
    *capacityBytes = capacity;
    return true;
}

constexpr uint64_t kP010FourWordPaddingMask = 0x003F003F003F003FULL;
constexpr uint64_t kP010TenBitMask = 0x03FFULL;
constexpr int64_t kCrcCacheChunkBytes = 64 * 1024;

inline uint64_t packFourP010Words(uint64_t words) {
    return
        ((words >> 6u) & kP010TenBitMask) |
        (((words >> 22u) & kP010TenBitMask) << 10u) |
        (((words >> 38u) & kP010TenBitMask) << 20u) |
        (((words >> 54u) & kP010TenBitMask) << 30u);
}

inline void storeEightPackedP010Words(uint8_t* target, uint64_t first, uint64_t second) {
    // Two five-byte packet groups are one 80-bit little-endian stream. Writing 64+16 bits halves
    // the store count of the former 32+8-bit per-group path without changing a single packet bit.
    const uint64_t low = first | (second << 40u);
    const uint16_t high = static_cast<uint16_t>(second >> 24u);
    std::memcpy(target, &low, sizeof(low));
    std::memcpy(target + sizeof(low), &high, sizeof(high));
}

#if defined(__aarch64__) && defined(__ARM_NEON)
inline uint8x16_t packEightP010WordsNeon(uint16x8_t raw) {
    // Four 10-bit samples become five bytes. Shift each canonical sample by 0/2/4/6,
    // merge the adjacent high/low bytes, then table-compact two independent groups.
    // The unused six P010 bits are validated once across the surrounding 32-sample batch.
    alignas(16) static constexpr int16_t kLaneShifts[8] = {0, 2, 4, 6, 0, 2, 4, 6};
    alignas(16) static constexpr uint8_t kGroupBoundaryMask[16] = {
        0xFF, 0xFF, 0xFF, 0x00, 0xFF, 0xFF, 0xFF, 0x00,
        0xFF, 0xFF, 0xFF, 0x00, 0xFF, 0xFF, 0xFF, 0x00
    };
    alignas(16) static constexpr uint8_t kCompactIndices[16] = {
        0, 16, 17, 18, 19, 4, 20, 21, 22, 23,
        0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF
    };

    const uint16x8_t samples = vshrq_n_u16(raw, kP010Shift);
    const uint16x8_t shifted = vshlq_u16(samples, vld1q_s16(kLaneShifts));
    const uint8x16_t bytes = vreinterpretq_u8_u16(shifted);
    const uint8x16_t lowBytes = vuzp1q_u8(bytes, bytes);
    const uint8x16_t highBytes = vuzp2q_u8(bytes, bytes);
    const uint8x16_t nextLowBytes = vandq_u8(
        vextq_u8(lowBytes, vdupq_n_u8(0), 1),
        vld1q_u8(kGroupBoundaryMask)
    );
    const uint8x16x2_t table = {{lowBytes, vorrq_u8(highBytes, nextLowBytes)}};
    return vqtbl2q_u8(table, vld1q_u8(kCompactIndices));
}

inline void storeTenPackedP010Bytes(uint8_t* target, uint8x16_t packed) {
    vst1_u8(target, vget_low_u8(packed));
    const uint16_t tail = vgetq_lane_u16(vreinterpretq_u16_u8(packed), 4);
    std::memcpy(target + 8, &tail, sizeof(tail));
}
#endif

/**
 * Losslessly packs canonical contiguous P010 words and optionally returns the packet CRC32.
 *
 * Full-sensor input is divisible by 32 samples. That path validates 32 padding-bit groups with one
 * branch and writes four 80-bit streams per iteration. CRC is consumed in cache-sized pieces while
 * the just-produced packet is still hot, avoiding a second full-frame DRAM traversal in the writer.
 */
template <bool ComputeCrc>
bool packContiguousP010TenBitImpl(
    const uint8_t* source,
    int64_t sampleCount,
    uint8_t* target,
    int64_t targetBytes,
    uint32_t* crcOut
) {
    if (source == nullptr || target == nullptr || sampleCount < 0 || targetBytes < 0 ||
        sampleCount > (std::numeric_limits<int64_t>::max() - 7) / 10) {
        return false;
    }
    const int64_t expectedBytes = (sampleCount * 10 + 7) / 8;
    if (targetBytes != expectedBytes) return false;

    int64_t sampleIndex = 0;
    uint8_t* output = target;
    uint8_t* crcStart = target;
    uLong crc = ComputeCrc ? ::crc32(0L, Z_NULL, 0) : 0u;

    for (; sampleIndex + 32 <= sampleCount; sampleIndex += 32) {
        if (sampleIndex + 160 <= sampleCount) {
            __builtin_prefetch(source + (sampleIndex + 128) * 2, 0, 1);
        }
        const uint8_t* input = source + sampleIndex * 2;
#if defined(__aarch64__) && defined(__ARM_NEON)
        const uint16x8_t raw0 = vreinterpretq_u16_u8(vld1q_u8(input));
        const uint16x8_t raw1 = vreinterpretq_u16_u8(vld1q_u8(input + 16));
        const uint16x8_t raw2 = vreinterpretq_u16_u8(vld1q_u8(input + 32));
        const uint16x8_t raw3 = vreinterpretq_u16_u8(vld1q_u8(input + 48));
        const uint16x8_t padding = vandq_u16(
            vorrq_u16(vorrq_u16(raw0, raw1), vorrq_u16(raw2, raw3)),
            vdupq_n_u16(0x003Fu)
        );
        if (vmaxvq_u16(padding) != 0u) return false;

        storeTenPackedP010Bytes(output, packEightP010WordsNeon(raw0));
        storeTenPackedP010Bytes(output + 10, packEightP010WordsNeon(raw1));
        storeTenPackedP010Bytes(output + 20, packEightP010WordsNeon(raw2));
        storeTenPackedP010Bytes(output + 30, packEightP010WordsNeon(raw3));
#else
        uint64_t words[8];
        std::memcpy(words, input, sizeof(words));
        const uint64_t padding =
            words[0] | words[1] | words[2] | words[3] |
            words[4] | words[5] | words[6] | words[7];
        if ((padding & kP010FourWordPaddingMask) != 0u) return false;

        const uint64_t p0 = packFourP010Words(words[0]);
        const uint64_t p1 = packFourP010Words(words[1]);
        const uint64_t p2 = packFourP010Words(words[2]);
        const uint64_t p3 = packFourP010Words(words[3]);
        const uint64_t p4 = packFourP010Words(words[4]);
        const uint64_t p5 = packFourP010Words(words[5]);
        const uint64_t p6 = packFourP010Words(words[6]);
        const uint64_t p7 = packFourP010Words(words[7]);
        storeEightPackedP010Words(output, p0, p1);
        storeEightPackedP010Words(output + 10, p2, p3);
        storeEightPackedP010Words(output + 20, p4, p5);
        storeEightPackedP010Words(output + 30, p6, p7);
#endif
        output += 40;
        if constexpr (ComputeCrc) {
            const int64_t available = output - crcStart;
            if (available >= kCrcCacheChunkBytes) {
                crc = ::crc32(
                    crc,
                    reinterpret_cast<const Bytef*>(crcStart),
                    static_cast<uInt>(available)
                );
                crcStart = output;
            }
        }
    }

    for (; sampleIndex + 8 <= sampleCount; sampleIndex += 8) {
        uint64_t firstWords = 0u;
        uint64_t secondWords = 0u;
        const uint8_t* input = source + sampleIndex * 2;
        std::memcpy(&firstWords, input, sizeof(firstWords));
        std::memcpy(&secondWords, input + sizeof(firstWords), sizeof(secondWords));
        if (((firstWords | secondWords) & kP010FourWordPaddingMask) != 0u) return false;
        storeEightPackedP010Words(
            output,
            packFourP010Words(firstWords),
            packFourP010Words(secondWords)
        );
        output += 10;
        if constexpr (ComputeCrc) {
            const int64_t available = output - crcStart;
            if (available >= kCrcCacheChunkBytes) {
                crc = ::crc32(
                    crc,
                    reinterpret_cast<const Bytef*>(crcStart),
                    static_cast<uInt>(available)
                );
                crcStart = output;
            }
        }
    }

    for (; sampleIndex + 4 <= sampleCount; sampleIndex += 4) {
        uint64_t words = 0u;
        std::memcpy(&words, source + sampleIndex * 2, sizeof(words));
        if ((words & kP010FourWordPaddingMask) != 0u) return false;
        const uint64_t packed = packFourP010Words(words);
        const uint32_t packedLow = static_cast<uint32_t>(packed);
        std::memcpy(output, &packedLow, sizeof(packedLow));
        output[4] = static_cast<uint8_t>(packed >> 32u);
        output += 5;
        if constexpr (ComputeCrc) {
            const int64_t available = output - crcStart;
            if (available >= kCrcCacheChunkBytes) {
                crc = ::crc32(
                    crc,
                    reinterpret_cast<const Bytef*>(crcStart),
                    static_cast<uInt>(available)
                );
                crcStart = output;
            }
        }
    }

    uint64_t accumulator = 0u;
    int bitCount = 0;
    for (; sampleIndex < sampleCount; ++sampleIndex) {
        uint16_t word = 0u;
        std::memcpy(&word, source + sampleIndex * 2, sizeof(word));
        if ((word & 0x003Fu) != 0u) return false;
        accumulator |= static_cast<uint64_t>(word >> kP010Shift) << bitCount;
        bitCount += 10;
        while (bitCount >= 8) {
            if (output - target >= targetBytes) return false;
            *output++ = static_cast<uint8_t>(accumulator & 0xFFu);
            accumulator >>= 8u;
            bitCount -= 8;
        }
    }
    if (bitCount > 0) {
        if (output - target >= targetBytes) return false;
        *output++ = static_cast<uint8_t>(accumulator & 0xFFu);
        accumulator >>= bitCount;
    }
    if (output - target != targetBytes || accumulator != 0u) return false;
    if constexpr (ComputeCrc) {
        const int64_t available = output - crcStart;
        if (available > 0) {
            crc = ::crc32(
                crc,
                reinterpret_cast<const Bytef*>(crcStart),
                static_cast<uInt>(available)
            );
        }
        *crcOut = static_cast<uint32_t>(crc);
    }
    return true;
}

bool packContiguousP010TenBit(
    const uint8_t* source,
    int64_t sampleCount,
    uint8_t* target,
    int64_t targetBytes,
    uint32_t* crcOut
) {
    return crcOut == nullptr
        ? packContiguousP010TenBitImpl<false>(source, sampleCount, target, targetBytes, nullptr)
        : packContiguousP010TenBitImpl<true>(source, sampleCount, target, targetBytes, crcOut);
}

/**
 * Fast Camera2 P010 plane encoder for the standard Android two-plane layout.
 *
 * Camera2 exposes each luma row as contiguous 16-bit P010 words and each UV row as contiguous
 * interleaved 16-bit words on the production device. Packing complete byte-aligned rows lets the
 * proven 32-sample packet encoder operate directly on the Image planes: no 37.7 MiB canonical
 * copy, no second DRAM read, and no packet reordering. Contiguous planes are encoded as two large
 * cache-hot streams and require only one polynomial combine. Padded rows use one streaming CRC over
 * cache-sized output spans; they never pay one crc32_combine operation per image row.
 */
bool packStandardP010PlanesToTenBitSerial(
    const uint8_t* y,
    int64_t yCapacity,
    const uint8_t* uv,
    int64_t uvCapacity,
    int width,
    int height,
    int yRowStride,
    int uvRowStride,
    int yPixelStride,
    int uvPixelStride,
    uint8_t* target,
    int64_t targetCapacity,
    uint32_t* crcOut
) {
    if (y == nullptr || uv == nullptr || target == nullptr ||
        width <= 0 || height <= 0 ||
        yPixelStride != 2 || uvPixelStride != 4 ||
        yRowStride <= 0 || uvRowStride <= 0 ||
        (width & 3) != 0) {
        return false;
    }

    const int64_t chromaWidth = (static_cast<int64_t>(width) + 1) / 2;
    const int64_t chromaHeight = (static_cast<int64_t>(height) + 1) / 2;
    const int64_t samplesPerRow = static_cast<int64_t>(width);
    const int64_t packedRowBytes = samplesPerRow * 10 / 8;
    const int64_t targetBytes = packedRowBytes * (static_cast<int64_t>(height) + chromaHeight);
    const int64_t requiredYBytes = static_cast<int64_t>(height - 1) * yRowStride +
        static_cast<int64_t>(width) * 2;
    const int64_t requiredUvBytes = (chromaHeight - 1) * uvRowStride + chromaWidth * 4;
    if (packedRowBytes <= 0 || targetBytes <= 0 ||
        yCapacity < requiredYBytes || uvCapacity < requiredUvBytes ||
        targetCapacity < targetBytes) {
        return false;
    }

    const int64_t ySamples = samplesPerRow * static_cast<int64_t>(height);
    const int64_t yPackedBytes = ySamples * 10 / 8;
    const int64_t uvSamples = samplesPerRow * chromaHeight;
    const int64_t uvPackedBytes = uvSamples * 10 / 8;
    const int64_t contiguousRowBytes = samplesPerRow * 2;

    if (yRowStride == contiguousRowBytes && uvRowStride == contiguousRowBytes) {
        uint32_t yCrc = 0u;
        uint32_t uvCrc = 0u;
        if (!packContiguousP010TenBit(
                y,
                ySamples,
                target,
                yPackedBytes,
                crcOut == nullptr ? nullptr : &yCrc
            ) ||
            !packContiguousP010TenBit(
                uv,
                uvSamples,
                target + yPackedBytes,
                uvPackedBytes,
                crcOut == nullptr ? nullptr : &uvCrc
            )) {
            return false;
        }
        if (crcOut != nullptr) {
            *crcOut = static_cast<uint32_t>(::crc32_combine(
                static_cast<uLong>(yCrc),
                static_cast<uLong>(uvCrc),
                static_cast<z_off_t>(uvPackedBytes)
            ));
        }
        return yPackedBytes + uvPackedBytes == targetBytes;
    }

    int64_t outputOffset = 0;
    int64_t crcOffset = 0;
    uLong streamingCrc = crcOut == nullptr ? 0u : ::crc32(0L, Z_NULL, 0);
    auto updateStreamingCrc = [&](bool force) {
        if (crcOut == nullptr) return;
        const int64_t available = outputOffset - crcOffset;
        if (!force && available < kCrcCacheChunkBytes) return;
        if (available <= 0) return;
        streamingCrc = ::crc32(
            streamingCrc,
            reinterpret_cast<const Bytef*>(target + crcOffset),
            static_cast<uInt>(available)
        );
        crcOffset = outputOffset;
    };
    auto packRow = [&](const uint8_t* source) -> bool {
        if (!packContiguousP010TenBit(
                source,
                samplesPerRow,
                target + outputOffset,
                packedRowBytes,
                nullptr
            )) {
            return false;
        }
        outputOffset += packedRowBytes;
        updateStreamingCrc(false);
        return true;
    };

    for (int row = 0; row < height; ++row) {
        if (!packRow(y + static_cast<int64_t>(row) * yRowStride)) return false;
    }
    for (int64_t row = 0; row < chromaHeight; ++row) {
        if (!packRow(uv + row * uvRowStride)) return false;
    }
    if (outputOffset != targetBytes) return false;
    updateStreamingCrc(true);
    if (crcOut != nullptr) *crcOut = static_cast<uint32_t>(streamingCrc);
    return true;
}

constexpr int kNiv99TransferBt709 = 0;
constexpr int kNiv99TransferHlg = 1;
constexpr int kNiv99TransferPq = 2;
constexpr int kNiv99TransferSrgb = 3;
constexpr int kNiv99MatrixBt709 = 0;
constexpr int kNiv99MatrixBt2020Ncl = 1;
constexpr int kNiv99TransferLutSize = 4096;
constexpr float kNiv99LumaCodeOffset = 64.0f;
constexpr float kNiv99LumaCodeRange = 876.0f;
constexpr float kNiv99ChromaCodeOffset = 512.0f;
constexpr float kNiv99ChromaCodeRange = 896.0f;

inline float clampFinite(float value, float minimum, float maximum) {
    return std::max(minimum, std::min(maximum, value));
}

float decodeTransferAnalytic(float encoded, int transferCode) {
    const float value = clampFinite(encoded, 0.0f, 1.0f);
    if (transferCode == kNiv99TransferPq) {
        constexpr float m1 = 2610.0f / 16384.0f;
        constexpr float m2 = 2523.0f / 32.0f;
        constexpr float c1 = 3424.0f / 4096.0f;
        constexpr float c2 = 2413.0f / 128.0f;
        constexpr float c3 = 2392.0f / 128.0f;
        const float powered = std::pow(value, 1.0f / m2);
        const float numerator = std::max(powered - c1, 0.0f);
        const float denominator = std::max(c2 - c3 * powered, 1.0e-7f);
        return clampFinite(std::pow(numerator / denominator, 1.0f / m1), 0.0f, 1.0f);
    }
    if (transferCode == kNiv99TransferHlg) {
        constexpr float a = 0.17883277f;
        constexpr float b = 0.28466892f;
        constexpr float c = 0.55991073f;
        return value <= 0.5f
            ? value * value / 3.0f
            : (std::exp((value - c) / a) + b) / 12.0f;
    }
    if (transferCode == kNiv99TransferSrgb) {
        return value <= 0.04045f
            ? value / 12.92f
            : std::pow((value + 0.055f) / 1.055f, 2.4f);
    }
    return value < 0.08145f
        ? value / 4.5f
        : std::pow((value + 0.099f) / 1.099f, 1.0f / 0.45f);
}

float encodeTransferAnalytic(float linear, int transferCode) {
    const float value = clampFinite(linear, 0.0f, 1.0f);
    if (transferCode == kNiv99TransferPq) {
        constexpr float m1 = 2610.0f / 16384.0f;
        constexpr float m2 = 2523.0f / 32.0f;
        constexpr float c1 = 3424.0f / 4096.0f;
        constexpr float c2 = 2413.0f / 128.0f;
        constexpr float c3 = 2392.0f / 128.0f;
        const float powered = std::pow(value, m1);
        return clampFinite(
            std::pow((c1 + c2 * powered) / (1.0f + c3 * powered), m2),
            0.0f,
            1.0f
        );
    }
    if (transferCode == kNiv99TransferHlg) {
        constexpr float a = 0.17883277f;
        constexpr float b = 0.28466892f;
        constexpr float c = 0.55991073f;
        return value <= (1.0f / 12.0f)
            ? std::sqrt(3.0f * value)
            : a * std::log(12.0f * value - b) + c;
    }
    if (transferCode == kNiv99TransferSrgb) {
        return value <= 0.0031308f
            ? 12.92f * value
            : 1.055f * std::pow(value, 1.0f / 2.4f) - 0.055f;
    }
    return value < 0.018f
        ? 4.5f * value
        : 1.099f * std::pow(value, 0.45f) - 0.099f;
}

struct Niv99TransferLookupTables {
    std::array<std::array<float, kNiv99TransferLutSize + 1>, 4> decoded{};
    std::array<std::array<float, kNiv99TransferLutSize + 1>, 4> encoded{};

    Niv99TransferLookupTables() {
        for (int transfer = kNiv99TransferBt709; transfer <= kNiv99TransferSrgb; ++transfer) {
            for (int index = 0; index <= kNiv99TransferLutSize; ++index) {
                const float value = static_cast<float>(index) /
                    static_cast<float>(kNiv99TransferLutSize);
                decoded[static_cast<size_t>(transfer)][static_cast<size_t>(index)] =
                    decodeTransferAnalytic(value, transfer);
                encoded[static_cast<size_t>(transfer)][static_cast<size_t>(index)] =
                    encodeTransferAnalytic(value, transfer);
            }
        }
    }
};

const Niv99TransferLookupTables& niv99TransferLookupTables() {
    static const Niv99TransferLookupTables tables;
    return tables;
}

inline float lookupTransfer(const float* table, float value) {
    const float position = clampFinite(value, 0.0f, 1.0f) *
        static_cast<float>(kNiv99TransferLutSize);
    const int lower = std::min(static_cast<int>(position), kNiv99TransferLutSize - 1);
    const float fraction = position - static_cast<float>(lower);
    return table[static_cast<size_t>(lower)] +
        (table[static_cast<size_t>(lower + 1)] - table[static_cast<size_t>(lower)]) * fraction;
}

struct Niv99P010Parameters {
    float redGain;
    float greenGain;
    float blueGain;
    float spatialStrength;
    float chromaStrength;
    float sharpeningStrength;
    float kr;
    float kg;
    float kb;
    float redChromaScale;
    float blueChromaScale;
    float inverseKg;
    float inverseRedChromaScale;
    float inverseBlueChromaScale;
    int transferCode;
    bool whiteBalanceActive;
    float saturation = 1.0f;
    float temporalLumaStrength = 0.0f;
    float temporalChromaStrength = 0.0f;
    float shotNoiseCoefficient = 0.0f;
    float readNoiseCoefficient = 0.0f;
};

inline uint16_t loadP010Word(const uint8_t* address) {
    uint16_t word = 0u;
    std::memcpy(&word, address, sizeof(word));
    return word;
}

inline int loadP010Code(
    const uint8_t* plane,
    int row,
    int column,
    int rowStride,
    int pixelStride,
    int componentOffset = 0
) {
    return static_cast<int>(
        loadP010Word(
            plane + static_cast<int64_t>(row) * rowStride +
                static_cast<int64_t>(column) * pixelStride + componentOffset
        ) >> kP010Shift
    );
}

struct Niv99FilterWeightTables {
    std::array<float, 1024> luma{};
    std::array<float, 1024> chroma{};
    std::array<uint16_t, 1024> temporalLumaThreshold{};
    std::array<uint16_t, 1024> temporalChromaThreshold{};
    std::array<float, 1024> temporalLumaInverseThreshold{};
    std::array<float, 1024> temporalChromaInverseThreshold{};
    float detailThreshold = 0.0f;
    float reconstructionScale = 0.0f;
    float haloLimit = 0.0f;

    explicit Niv99FilterWeightTables(const Niv99P010Parameters& parameters) {
        detailThreshold = 1.25f + parameters.spatialStrength * 2.5f;
        reconstructionScale = parameters.sharpeningStrength * 0.22f;
        haloLimit = 3.0f + 7.0f * (1.0f - parameters.spatialStrength);
        const float lumaThreshold = 3.0f + parameters.spatialStrength * 42.0f;
        const float chromaThreshold = 4.0f + parameters.chromaStrength * 64.0f;
        for (size_t difference = 0; difference < luma.size(); ++difference) {
            const float lumaEdge = std::max(
                0.0f,
                1.0f - static_cast<float>(difference) / lumaThreshold
            );
            const float chromaEdge = std::max(
                0.0f,
                1.0f - static_cast<float>(difference) / chromaThreshold
            );
            // A single joint edge gate controls the blend toward the four-neighbor mean. Using the
            // strongest local discontinuity is deliberately conservative: one real edge vetoes
            // cross-edge smoothing while flat noisy regions retain the former four-neighbor gain.
            const float lumaWeight = parameters.spatialStrength * 0.46f *
                lumaEdge * lumaEdge;
            const float chromaWeight = parameters.chromaStrength * 0.68f *
                chromaEdge * chromaEdge;
            luma[difference] = (4.0f * lumaWeight) / (1.0f + 4.0f * lumaWeight);
            chroma[difference] = (4.0f * chromaWeight) /
                (1.0f + 4.0f * chromaWeight);
            const float normalizedSignal = static_cast<float>(difference) / 1023.0f;
            const float sigmaCodes = std::sqrt(std::max(
                parameters.shotNoiseCoefficient * normalizedSignal +
                    parameters.readNoiseCoefficient,
                0.00000002f
            )) * 1023.0f;
            temporalLumaThreshold[difference] = static_cast<uint16_t>(clampFinite(
                12.0f + sigmaCodes * 3.0f,
                12.0f,
                96.0f
            ));
            temporalChromaThreshold[difference] = static_cast<uint16_t>(clampFinite(
                18.0f + sigmaCodes * 3.6f,
                18.0f,
                128.0f
            ));
            temporalLumaInverseThreshold[difference] =
                1.0f / static_cast<float>(temporalLumaThreshold[difference]);
            temporalChromaInverseThreshold[difference] =
                1.0f / static_cast<float>(temporalChromaThreshold[difference]);
        }
    }
};

inline float finishNiv99LumaFilter(
    int centerCode,
    int neighborSum,
    int maximumDifference,
    const Niv99FilterWeightTables& weights
) {
    const float center = static_cast<float>(centerCode);
    const float neighborMean = static_cast<float>(neighborSum) * 0.25f;
    const float filtered = center + (neighborMean - center) *
        weights.luma[static_cast<size_t>(maximumDifference)];
    const float detail = center - neighborMean;
    const float detailGate = clampFinite(
        (std::fabs(detail) - weights.detailThreshold) / 18.0f,
        0.0f,
        1.0f
    );
    const float reconstruction = clampFinite(
        detail * weights.reconstructionScale * detailGate,
        -weights.haloLimit,
        weights.haloLimit
    );
    return filtered + reconstruction;
}

bool filterNiv99Luma(
    const uint8_t* y,
    int width,
    int height,
    int row,
    int column,
    int rowStride,
    int pixelStride,
    const Niv99FilterWeightTables& weights,
    float* output
) {
    const int up = std::max(0, row - 1);
    const int down = std::min(height - 1, row + 1);
    const int left = std::max(0, column - 1);
    const int right = std::min(width - 1, column + 1);
    const uint16_t centerWord = loadP010Word(
        y + static_cast<int64_t>(row) * rowStride +
            static_cast<int64_t>(column) * pixelStride
    );
    if ((centerWord & 0x003Fu) != 0u) return false;
    const int centerCode = static_cast<int>(centerWord >> kP010Shift);
    const std::array<int, 4> neighbors = {
        loadP010Code(y, row, left, rowStride, pixelStride),
        loadP010Code(y, row, right, rowStride, pixelStride),
        loadP010Code(y, up, column, rowStride, pixelStride),
        loadP010Code(y, down, column, rowStride, pixelStride)
    };
    int neighborSum = 0;
    int maximumDifference = 0;
    for (int neighbor : neighbors) {
        neighborSum += neighbor;
        maximumDifference = std::max(maximumDifference, std::abs(neighbor - centerCode));
    }
    *output = finishNiv99LumaFilter(centerCode, neighborSum, maximumDifference, weights);
    return true;
}

bool filterNiv99LumaTwoByFourInterior(
    const uint8_t* y,
    int row,
    int column,
    int rowStride,
    const Niv99FilterWeightTables& weights,
    float output[2][4]
) {
    std::array<uint16_t, 4> upperWords{};
    std::array<uint16_t, 6> firstWords{};
    std::array<uint16_t, 6> secondWords{};
    std::array<uint16_t, 4> lowerWords{};
    const uint8_t* firstRow = y + static_cast<int64_t>(row) * rowStride;
    std::memcpy(upperWords.data(), firstRow - rowStride + static_cast<int64_t>(column) * 2, 8u);
    std::memcpy(firstWords.data(), firstRow + static_cast<int64_t>(column - 1) * 2, 12u);
    std::memcpy(secondWords.data(), firstRow + rowStride +
        static_cast<int64_t>(column - 1) * 2, 12u);
    std::memcpy(lowerWords.data(), firstRow + static_cast<int64_t>(rowStride) * 2 +
        static_cast<int64_t>(column) * 2, 8u);

    const uint16_t centerPadding = static_cast<uint16_t>(
        firstWords[1] | firstWords[2] | firstWords[3] | firstWords[4] |
        secondWords[1] | secondWords[2] | secondWords[3] | secondWords[4]
    );
    if ((centerPadding & 0x003Fu) != 0u) return false;
    for (size_t index = 0; index < 4u; ++index) {
        const int firstCenter = static_cast<int>(firstWords[index + 1u] >> kP010Shift);
        const int firstLeft = static_cast<int>(firstWords[index] >> kP010Shift);
        const int firstRight = static_cast<int>(firstWords[index + 2u] >> kP010Shift);
        const int firstUp = static_cast<int>(upperWords[index] >> kP010Shift);
        const int firstDown = static_cast<int>(secondWords[index + 1u] >> kP010Shift);
        const int firstMaximumDifference = std::max(
            std::max(std::abs(firstLeft - firstCenter), std::abs(firstRight - firstCenter)),
            std::max(std::abs(firstUp - firstCenter), std::abs(firstDown - firstCenter))
        );
        output[0][index] = finishNiv99LumaFilter(
            firstCenter,
            firstLeft + firstRight + firstUp + firstDown,
            firstMaximumDifference,
            weights
        );

        const int secondCenter = static_cast<int>(secondWords[index + 1u] >> kP010Shift);
        const int secondLeft = static_cast<int>(secondWords[index] >> kP010Shift);
        const int secondRight = static_cast<int>(secondWords[index + 2u] >> kP010Shift);
        const int secondUp = static_cast<int>(firstWords[index + 1u] >> kP010Shift);
        const int secondDown = static_cast<int>(lowerWords[index] >> kP010Shift);
        const int secondMaximumDifference = std::max(
            std::max(std::abs(secondLeft - secondCenter), std::abs(secondRight - secondCenter)),
            std::max(std::abs(secondUp - secondCenter), std::abs(secondDown - secondCenter))
        );
        output[1][index] = finishNiv99LumaFilter(
            secondCenter,
            secondLeft + secondRight + secondUp + secondDown,
            secondMaximumDifference,
            weights
        );
    }
    return true;
}

bool filterNiv99Chroma(
    const uint8_t* uv,
    int chromaWidth,
    int chromaHeight,
    int row,
    int column,
    int rowStride,
    int pixelStride,
    const Niv99FilterWeightTables& weights,
    float* outputCb,
    float* outputCr
) {
    const int up = std::max(0, row - 1);
    const int down = std::min(chromaHeight - 1, row + 1);
    const int left = std::max(0, column - 1);
    const int right = std::min(chromaWidth - 1, column + 1);
    const uint8_t* centerAddress = uv + static_cast<int64_t>(row) * rowStride +
        static_cast<int64_t>(column) * pixelStride;
    const uint16_t centerCbWord = loadP010Word(centerAddress);
    const uint16_t centerCrWord = loadP010Word(centerAddress + 2);
    if (((centerCbWord | centerCrWord) & 0x003Fu) != 0u) return false;
    const int centerCbCode = static_cast<int>(centerCbWord >> kP010Shift);
    const int centerCrCode = static_cast<int>(centerCrWord >> kP010Shift);
    const float centerCb = static_cast<float>(centerCbCode);
    const float centerCr = static_cast<float>(centerCrCode);
    const std::array<int, 4> rows = {row, row, up, down};
    const std::array<int, 4> columns = {left, right, column, column};
    int neighborCbSum = 0;
    int neighborCrSum = 0;
    int maximumDistance = 0;
    for (size_t index = 0; index < rows.size(); ++index) {
        const int neighborCb = loadP010Code(
            uv,
            rows[index],
            columns[index],
            rowStride,
            pixelStride
        );
        const int neighborCr = loadP010Code(
            uv,
            rows[index],
            columns[index],
            rowStride,
            pixelStride,
            2
        );
        maximumDistance = std::max(maximumDistance, std::max(
            std::abs(neighborCb - centerCbCode),
            std::abs(neighborCr - centerCrCode)
        ));
        neighborCbSum += neighborCb;
        neighborCrSum += neighborCr;
    }
    const float blend = weights.chroma[static_cast<size_t>(maximumDistance)];
    const float filteredCb = centerCb +
        (static_cast<float>(neighborCbSum) * 0.25f - centerCb) * blend;
    const float filteredCr = centerCr +
        (static_cast<float>(neighborCrSum) * 0.25f - centerCr) * blend;
    *outputCb = (filteredCb - kNiv99ChromaCodeOffset) /
        kNiv99ChromaCodeRange;
    *outputCr = (filteredCr - kNiv99ChromaCodeOffset) /
        kNiv99ChromaCodeRange;
    return true;
}

#if defined(__aarch64__) && defined(__ARM_NEON)
inline float32x4_t gatherNiv99FloatFour(
    const float* table,
    uint16x4_t indices
) {
    float32x2_t low = vdup_n_f32(table[vget_lane_u16(indices, 0)]);
    low = vset_lane_f32(table[vget_lane_u16(indices, 1)], low, 1);
    float32x2_t high = vdup_n_f32(table[vget_lane_u16(indices, 2)]);
    high = vset_lane_f32(table[vget_lane_u16(indices, 3)], high, 1);
    return vcombine_f32(low, high);
}

inline float32x4x2_t gatherNiv99AdjacentFloatPairs(
    const float* table,
    uint32x4_t indices
) {
    // Each interpolation consumes table[index] and table[index + 1]. Four unaligned 64-bit loads
    // plus ZIPs replace eight scalar loads and the former index/value spill arrays. Values and
    // interpolation arithmetic are unchanged bit-for-bit.
    const float32x2_t pair0 = vld1_f32(table + vgetq_lane_u32(indices, 0));
    const float32x2_t pair1 = vld1_f32(table + vgetq_lane_u32(indices, 1));
    const float32x2_t pair2 = vld1_f32(table + vgetq_lane_u32(indices, 2));
    const float32x2_t pair3 = vld1_f32(table + vgetq_lane_u32(indices, 3));
    const float32x2x2_t first = vzip_f32(pair0, pair1);
    const float32x2x2_t second = vzip_f32(pair2, pair3);
    float32x4x2_t gathered{};
    gathered.val[0] = vcombine_f32(first.val[0], second.val[0]);
    gathered.val[1] = vcombine_f32(first.val[1], second.val[1]);
    return gathered;
}

/**
 * Evaluates the exact scalar luma equation for eight adjacent pixels with four-wide NEON lanes.
 *
 * Weight lookup remains scalar because AArch64 NEON has no general gather instruction. Everything
 * around that lookup (five-tap differences, reconstruction gate, halo clamp and final blend) stays
 * vectorized. Inputs are finite ten-bit codes, so min/max have the same result as clampFinite().
 */
inline void finishNiv99LumaFilterEightNeon(
    uint16x8_t centerCodes,
    uint16x8_t neighborSums,
    uint16x8_t maximumDifferences,
    const Niv99FilterWeightTables& weights,
    float output[8]
) {
    const float32x4_t lowBlend = gatherNiv99FloatFour(
        weights.luma.data(),
        vget_low_u16(maximumDifferences)
    );
    const float32x4_t highBlend = gatherNiv99FloatFour(
        weights.luma.data(),
        vget_high_u16(maximumDifferences)
    );

    const float32x4_t quarter = vdupq_n_f32(0.25f);
    const float32x4_t inverseDetailRamp = vdupq_n_f32(1.0f / 18.0f);
    const float32x4_t zero = vdupq_n_f32(0.0f);
    const float32x4_t one = vdupq_n_f32(1.0f);
    const float32x4_t detailThreshold = vdupq_n_f32(weights.detailThreshold);
    const float32x4_t reconstructionScale = vdupq_n_f32(weights.reconstructionScale);
    const float32x4_t negativeHalo = vdupq_n_f32(-weights.haloLimit);
    const float32x4_t positiveHalo = vdupq_n_f32(weights.haloLimit);

    const auto finishHalf = [&](
        uint16x4_t centerHalf,
        uint16x4_t sumHalf,
        float32x4_t blend,
        size_t offset
    ) {
        const float32x4_t center = vcvtq_f32_u32(vmovl_u16(centerHalf));
        const float32x4_t neighborMean = vmulq_f32(
            vcvtq_f32_u32(vmovl_u16(sumHalf)),
            quarter
        );
        const float32x4_t detail = vsubq_f32(center, neighborMean);
        const float32x4_t detailGate = vminq_f32(
            one,
            vmaxq_f32(
                zero,
                vmulq_f32(vsubq_f32(vabsq_f32(detail), detailThreshold), inverseDetailRamp)
            )
        );
        const float32x4_t reconstruction = vminq_f32(
            positiveHalo,
            vmaxq_f32(
                negativeHalo,
                vmulq_f32(vmulq_f32(detail, reconstructionScale), detailGate)
            )
        );
        const float32x4_t filtered = vfmaq_f32(
            center,
            vsubq_f32(neighborMean, center),
            blend
        );
        vst1q_f32(output + offset, vaddq_f32(filtered, reconstruction));
    };

    finishHalf(vget_low_u16(centerCodes), vget_low_u16(neighborSums), lowBlend, 0u);
    finishHalf(vget_high_u16(centerCodes), vget_high_u16(neighborSums), highBlend, 4u);
}

bool filterNiv99LumaTwoByEightInteriorNeon(
    const uint8_t* y,
    int row,
    int column,
    int rowStride,
    const Niv99FilterWeightTables& weights,
    uint16x8_t* paddingAccumulator,
    float output[2][8]
) {
    const uint8_t* firstRow = y + static_cast<int64_t>(row) * rowStride;
    const uint16_t* firstCenterAddress = reinterpret_cast<const uint16_t*>(
        firstRow + static_cast<int64_t>(column) * 2
    );
    const uint16_t* secondCenterAddress = reinterpret_cast<const uint16_t*>(
        firstRow + rowStride + static_cast<int64_t>(column) * 2
    );

    const uint16x8_t upperWords = vld1q_u16(reinterpret_cast<const uint16_t*>(
        firstRow - rowStride + static_cast<int64_t>(column) * 2
    ));
    // Two overlapping ten-sample windows contain the left, center and right neighborhoods. Derive
    // the shifted vectors in registers instead of issuing three 16-byte loads for each source row.
    const uint16x8_t firstLeftWords = vld1q_u16(firstCenterAddress - 1);
    const uint16x8_t firstTailWords = vld1q_u16(firstCenterAddress + 7);
    const uint16x8_t firstCenterWords = vextq_u16(firstLeftWords, firstTailWords, 1);
    const uint16x8_t firstRightWords = vextq_u16(firstLeftWords, firstTailWords, 2);
    const uint16x8_t secondLeftWords = vld1q_u16(secondCenterAddress - 1);
    const uint16x8_t secondTailWords = vld1q_u16(secondCenterAddress + 7);
    const uint16x8_t secondCenterWords = vextq_u16(secondLeftWords, secondTailWords, 1);
    const uint16x8_t secondRightWords = vextq_u16(secondLeftWords, secondTailWords, 2);
    const uint16x8_t lowerWords = vld1q_u16(reinterpret_cast<const uint16_t*>(
        firstRow + static_cast<int64_t>(rowStride) * 2 + static_cast<int64_t>(column) * 2
    ));

    const uint16x8_t centerPadding = vandq_u16(
        vorrq_u16(firstCenterWords, secondCenterWords),
        vdupq_n_u16(0x003Fu)
    );
    *paddingAccumulator = vorrq_u16(*paddingAccumulator, centerPadding);

    const uint16x8_t upper = vshrq_n_u16(upperWords, kP010Shift);
    const uint16x8_t firstLeft = vshrq_n_u16(firstLeftWords, kP010Shift);
    const uint16x8_t firstCenter = vshrq_n_u16(firstCenterWords, kP010Shift);
    const uint16x8_t firstRight = vshrq_n_u16(firstRightWords, kP010Shift);
    const uint16x8_t secondLeft = vshrq_n_u16(secondLeftWords, kP010Shift);
    const uint16x8_t secondCenter = vshrq_n_u16(secondCenterWords, kP010Shift);
    const uint16x8_t secondRight = vshrq_n_u16(secondRightWords, kP010Shift);
    const uint16x8_t lower = vshrq_n_u16(lowerWords, kP010Shift);

    const uint16x8_t firstNeighborSum = vaddq_u16(
        vaddq_u16(firstLeft, firstRight),
        vaddq_u16(upper, secondCenter)
    );
    const uint16x8_t firstMaximumDifference = vmaxq_u16(
        vmaxq_u16(vabdq_u16(firstLeft, firstCenter), vabdq_u16(firstRight, firstCenter)),
        vmaxq_u16(vabdq_u16(upper, firstCenter), vabdq_u16(secondCenter, firstCenter))
    );
    finishNiv99LumaFilterEightNeon(
        firstCenter,
        firstNeighborSum,
        firstMaximumDifference,
        weights,
        output[0]
    );

    const uint16x8_t secondNeighborSum = vaddq_u16(
        vaddq_u16(secondLeft, secondRight),
        vaddq_u16(firstCenter, lower)
    );
    const uint16x8_t secondMaximumDifference = vmaxq_u16(
        vmaxq_u16(vabdq_u16(secondLeft, secondCenter), vabdq_u16(secondRight, secondCenter)),
        vmaxq_u16(vabdq_u16(firstCenter, secondCenter), vabdq_u16(lower, secondCenter))
    );
    finishNiv99LumaFilterEightNeon(
        secondCenter,
        secondNeighborSum,
        secondMaximumDifference,
        weights,
        output[1]
    );
    return true;
}

bool filterNiv99ChromaFourInteriorNeon(
    const uint8_t* uv,
    int row,
    int column,
    int rowStride,
    const Niv99FilterWeightTables& weights,
    uint16x8_t* paddingAccumulator,
    float outputCb[4],
    float outputCr[4]
) {
    const uint16_t* centerAddress = reinterpret_cast<const uint16_t*>(
        uv + static_cast<int64_t>(row) * rowStride + static_cast<int64_t>(column) * 4
    );
    // As above, load two six-cell windows and derive all three horizontal neighborhoods in
    // registers. This preserves the exact interleaved Cb/Cr samples while removing one vld2 per
    // four chroma cells.
    const uint16x4x2_t leftWords = vld2_u16(centerAddress - 2);
    const uint16x4x2_t tailWords = vld2_u16(centerAddress + 6);
    uint16x4x2_t centerWords{};
    uint16x4x2_t rightWords{};
    centerWords.val[0] = vext_u16(leftWords.val[0], tailWords.val[0], 1);
    centerWords.val[1] = vext_u16(leftWords.val[1], tailWords.val[1], 1);
    rightWords.val[0] = vext_u16(leftWords.val[0], tailWords.val[0], 2);
    rightWords.val[1] = vext_u16(leftWords.val[1], tailWords.val[1], 2);
    const uint16x4x2_t upperWords = vld2_u16(reinterpret_cast<const uint16_t*>(
        reinterpret_cast<const uint8_t*>(centerAddress) - rowStride
    ));
    const uint16x4x2_t lowerWords = vld2_u16(reinterpret_cast<const uint16_t*>(
        reinterpret_cast<const uint8_t*>(centerAddress) + rowStride
    ));

    const uint16x4_t centerPadding = vand_u16(
        vorr_u16(centerWords.val[0], centerWords.val[1]),
        vdup_n_u16(0x003Fu)
    );
    *paddingAccumulator = vorrq_u16(
        *paddingAccumulator,
        vcombine_u16(centerPadding, vdup_n_u16(0u))
    );

    const uint16x4_t centerCb = vshr_n_u16(centerWords.val[0], kP010Shift);
    const uint16x4_t centerCr = vshr_n_u16(centerWords.val[1], kP010Shift);
    const uint16x4_t leftCb = vshr_n_u16(leftWords.val[0], kP010Shift);
    const uint16x4_t leftCr = vshr_n_u16(leftWords.val[1], kP010Shift);
    const uint16x4_t rightCb = vshr_n_u16(rightWords.val[0], kP010Shift);
    const uint16x4_t rightCr = vshr_n_u16(rightWords.val[1], kP010Shift);
    const uint16x4_t upperCb = vshr_n_u16(upperWords.val[0], kP010Shift);
    const uint16x4_t upperCr = vshr_n_u16(upperWords.val[1], kP010Shift);
    const uint16x4_t lowerCb = vshr_n_u16(lowerWords.val[0], kP010Shift);
    const uint16x4_t lowerCr = vshr_n_u16(lowerWords.val[1], kP010Shift);

    const uint16x4_t maximumDistance = vmax_u16(
        vmax_u16(
            vmax_u16(vabd_u16(leftCb, centerCb), vabd_u16(leftCr, centerCr)),
            vmax_u16(vabd_u16(rightCb, centerCb), vabd_u16(rightCr, centerCr))
        ),
        vmax_u16(
            vmax_u16(vabd_u16(upperCb, centerCb), vabd_u16(upperCr, centerCr)),
            vmax_u16(vabd_u16(lowerCb, centerCb), vabd_u16(lowerCr, centerCr))
        )
    );
    const float32x4_t blend = gatherNiv99FloatFour(weights.chroma.data(), maximumDistance);

    const uint16x4_t neighborCbSum = vadd_u16(
        vadd_u16(leftCb, rightCb),
        vadd_u16(upperCb, lowerCb)
    );
    const uint16x4_t neighborCrSum = vadd_u16(
        vadd_u16(leftCr, rightCr),
        vadd_u16(upperCr, lowerCr)
    );
    const float32x4_t cb = vcvtq_f32_u32(vmovl_u16(centerCb));
    const float32x4_t cr = vcvtq_f32_u32(vmovl_u16(centerCr));
    const float32x4_t quarter = vdupq_n_f32(0.25f);
    const float32x4_t filteredCb = vfmaq_f32(
        cb,
        vsubq_f32(vmulq_f32(vcvtq_f32_u32(vmovl_u16(neighborCbSum)), quarter), cb),
        blend
    );
    const float32x4_t filteredCr = vfmaq_f32(
        cr,
        vsubq_f32(vmulq_f32(vcvtq_f32_u32(vmovl_u16(neighborCrSum)), quarter), cr),
        blend
    );
    const float32x4_t chromaOffset = vdupq_n_f32(kNiv99ChromaCodeOffset);
    const float32x4_t inverseChromaRange = vdupq_n_f32(1.0f / kNiv99ChromaCodeRange);
    vst1q_f32(outputCb, vmulq_f32(vsubq_f32(filteredCb, chromaOffset), inverseChromaRange));
    vst1q_f32(outputCr, vmulq_f32(vsubq_f32(filteredCr, chromaOffset), inverseChromaRange));
    return true;
}
#endif

void compressNiv99RgbToUnitGamut(float luma, float* red, float* green, float* blue) {
    float scale = 1.0f;
    const std::array<float, 3> channels = {*red, *green, *blue};
    for (float channel : channels) {
        const float delta = channel - luma;
        if (channel > 1.0f && delta > 1.0e-7f) {
            scale = std::min(scale, (1.0f - luma) / delta);
        } else if (channel < 0.0f && delta < -1.0e-7f) {
            scale = std::min(scale, (0.0f - luma) / delta);
        }
    }
    scale = clampFinite(scale, 0.0f, 1.0f);
    *red = luma + (*red - luma) * scale;
    *green = luma + (*green - luma) * scale;
    *blue = luma + (*blue - luma) * scale;
}

void processNiv99Pixel(
    float lumaCode,
    float cb,
    float cr,
    const Niv99P010Parameters& parameters,
    const float* decode,
    const float* encode,
    float* outputLuma,
    float* outputCb,
    float* outputCr
) {
    const float encodedLuma = (lumaCode - kNiv99LumaCodeOffset) / kNiv99LumaCodeRange;
    if (!parameters.whiteBalanceActive || encodedLuma <= 0.0f || encodedLuma >= 1.0f) {
        *outputLuma = encodedLuma;
        *outputCb = cb * parameters.saturation;
        *outputCr = cr * parameters.saturation;
        return;
    }

    float red = encodedLuma + parameters.redChromaScale * cr;
    float blue = encodedLuma + parameters.blueChromaScale * cb;
    float green = (encodedLuma - parameters.kr * red - parameters.kb * blue) *
        parameters.inverseKg;
    red = lookupTransfer(decode, red);
    green = lookupTransfer(decode, green);
    blue = lookupTransfer(decode, blue);
    const float originalLuma = clampFinite(
        parameters.kr * red + parameters.kg * green + parameters.kb * blue,
        0.0f,
        1.0f
    );
    red *= parameters.redGain;
    green *= parameters.greenGain;
    blue *= parameters.blueGain;
    const float correctedLuma = parameters.kr * red + parameters.kg * green + parameters.kb * blue;
    if (correctedLuma > 1.0e-7f) {
        const float luminanceScale = originalLuma / correctedLuma;
        red *= luminanceScale;
        green *= luminanceScale;
        blue *= luminanceScale;
    }
    compressNiv99RgbToUnitGamut(originalLuma, &red, &green, &blue);
    red = lookupTransfer(encode, red);
    green = lookupTransfer(encode, green);
    blue = lookupTransfer(encode, blue);
    const float correctedEncodedLuma =
        parameters.kr * red + parameters.kg * green + parameters.kb * blue;
    *outputLuma = correctedEncodedLuma;
    *outputCb = (blue - correctedEncodedLuma) * parameters.inverseBlueChromaScale *
        parameters.saturation;
    *outputCr = (red - correctedEncodedLuma) * parameters.inverseRedChromaScale *
        parameters.saturation;
}

#if defined(__aarch64__) && defined(__ARM_NEON)
inline float32x4_t clampNiv99UnitNeon(float32x4_t value) {
    return vmaxnmq_f32(vdupq_n_f32(0.0f), vminnmq_f32(vdupq_n_f32(1.0f), value));
}

inline float32x4_t lookupNiv99TransferFourNeon(
    const float* table,
    float32x4_t value
) {
    const float32x4_t position = vmulq_n_f32(
        clampNiv99UnitNeon(value),
        static_cast<float>(kNiv99TransferLutSize)
    );
    const uint32x4_t lower = vminq_u32(
        vcvtq_u32_f32(position),
        vdupq_n_u32(static_cast<uint32_t>(kNiv99TransferLutSize - 1))
    );
    const float32x4x2_t values = gatherNiv99AdjacentFloatPairs(table, lower);
    const float32x4_t fraction = vsubq_f32(position, vcvtq_f32_u32(lower));
    return vfmaq_f32(
        values.val[0],
        vsubq_f32(values.val[1], values.val[0]),
        fraction
    );
}

/** Four-cell white-balance transform matching processNiv99Pixel without scalar call overhead. */
void processNiv99PixelsFourNeon(
    const float lumaCode[4],
    const float cb[4],
    const float cr[4],
    const Niv99P010Parameters& parameters,
    const float* decode,
    const float* encode,
    float outputLuma[4],
    float outputCb[4],
    float outputCr[4]
) {
    const float32x4_t lumaCodes = vld1q_f32(lumaCode);
    const float32x4_t inputCb = vld1q_f32(cb);
    const float32x4_t inputCr = vld1q_f32(cr);
    const float32x4_t encodedLuma = vmulq_n_f32(
        vsubq_f32(lumaCodes, vdupq_n_f32(kNiv99LumaCodeOffset)),
        1.0f / kNiv99LumaCodeRange
    );
    if (!parameters.whiteBalanceActive) {
        vst1q_f32(outputLuma, encodedLuma);
        vst1q_f32(outputCb, vmulq_n_f32(inputCb, parameters.saturation));
        vst1q_f32(outputCr, vmulq_n_f32(inputCr, parameters.saturation));
        return;
    }

    const uint32x4_t activeMask = vandq_u32(
        vcgtq_f32(encodedLuma, vdupq_n_f32(0.0f)),
        vcltq_f32(encodedLuma, vdupq_n_f32(1.0f))
    );
    float32x4_t red = vfmaq_n_f32(encodedLuma, inputCr, parameters.redChromaScale);
    float32x4_t blue = vfmaq_n_f32(encodedLuma, inputCb, parameters.blueChromaScale);
    float32x4_t green = vmulq_n_f32(
        vsubq_f32(
            vsubq_f32(encodedLuma, vmulq_n_f32(red, parameters.kr)),
            vmulq_n_f32(blue, parameters.kb)
        ),
        parameters.inverseKg
    );

    red = lookupNiv99TransferFourNeon(decode, red);
    green = lookupNiv99TransferFourNeon(decode, green);
    blue = lookupNiv99TransferFourNeon(decode, blue);

    const float32x4_t originalLuma = clampNiv99UnitNeon(
        vfmaq_n_f32(
            vfmaq_n_f32(vmulq_n_f32(red, parameters.kr), green, parameters.kg),
            blue,
            parameters.kb
        )
    );
    red = vmulq_n_f32(red, parameters.redGain);
    green = vmulq_n_f32(green, parameters.greenGain);
    blue = vmulq_n_f32(blue, parameters.blueGain);
    const float32x4_t correctedLuma = vfmaq_n_f32(
        vfmaq_n_f32(vmulq_n_f32(red, parameters.kr), green, parameters.kg),
        blue,
        parameters.kb
    );
    const uint32x4_t validCorrectedLuma = vcgtq_f32(
        correctedLuma,
        vdupq_n_f32(1.0e-7f)
    );
    const float32x4_t luminanceScale = vbslq_f32(
        validCorrectedLuma,
        vdivq_f32(originalLuma, correctedLuma),
        vdupq_n_f32(1.0f)
    );
    red = vmulq_f32(red, luminanceScale);
    green = vmulq_f32(green, luminanceScale);
    blue = vmulq_f32(blue, luminanceScale);

    const float32x4_t zero = vdupq_n_f32(0.0f);
    const float32x4_t one = vdupq_n_f32(1.0f);
    const float32x4_t epsilon = vdupq_n_f32(1.0e-7f);
    const float32x4_t negativeEpsilon = vdupq_n_f32(-1.0e-7f);
    float32x4_t gamutScale = one;
    const uint32x4_t outOfGamutMask = vorrq_u32(
        vorrq_u32(
            vorrq_u32(vcgtq_f32(red, one), vcltq_f32(red, zero)),
            vorrq_u32(vcgtq_f32(green, one), vcltq_f32(green, zero))
        ),
        vorrq_u32(vcgtq_f32(blue, one), vcltq_f32(blue, zero))
    );
    // The common in-gamut block needs no compression. One horizontal test avoids six expensive
    // vector divisions without serializing every channel and high/low branch independently.
    if (vmaxvq_u32(outOfGamutMask) != 0u) {
        const auto includeGamutConstraint = [&](float32x4_t channel) {
            const float32x4_t delta = vsubq_f32(channel, originalLuma);
            const uint32x4_t highMask = vandq_u32(
                vcgtq_f32(channel, one),
                vcgtq_f32(delta, epsilon)
            );
            const uint32x4_t lowMask = vandq_u32(
                vcltq_f32(channel, zero),
                vcltq_f32(delta, negativeEpsilon)
            );
            const uint32x4_t constrained = vorrq_u32(highMask, lowMask);
            const float32x4_t boundary = vbslq_f32(
                highMask,
                vsubq_f32(one, originalLuma),
                vnegq_f32(originalLuma)
            );
            // High and low gamut constraints are mutually exclusive, so selecting the boundary
            // before division preserves the exact equation with one vector divide per channel.
            const float32x4_t candidate = vbslq_f32(
                constrained,
                vdivq_f32(boundary, delta),
                one
            );
            gamutScale = vminnmq_f32(gamutScale, candidate);
        };
        includeGamutConstraint(red);
        includeGamutConstraint(green);
        includeGamutConstraint(blue);
    }
    gamutScale = clampNiv99UnitNeon(gamutScale);
    red = vfmaq_f32(originalLuma, vsubq_f32(red, originalLuma), gamutScale);
    green = vfmaq_f32(originalLuma, vsubq_f32(green, originalLuma), gamutScale);
    blue = vfmaq_f32(originalLuma, vsubq_f32(blue, originalLuma), gamutScale);

    red = lookupNiv99TransferFourNeon(encode, red);
    green = lookupNiv99TransferFourNeon(encode, green);
    blue = lookupNiv99TransferFourNeon(encode, blue);
    const float32x4_t correctedEncodedLuma = vfmaq_n_f32(
        vfmaq_n_f32(vmulq_n_f32(red, parameters.kr), green, parameters.kg),
        blue,
        parameters.kb
    );
    const float32x4_t transformedCb = vmulq_n_f32(
        vsubq_f32(blue, correctedEncodedLuma),
        parameters.inverseBlueChromaScale
    );
    const float32x4_t transformedCr = vmulq_n_f32(
        vsubq_f32(red, correctedEncodedLuma),
        parameters.inverseRedChromaScale
    );
    vst1q_f32(outputLuma, vbslq_f32(activeMask, correctedEncodedLuma, encodedLuma));
    vst1q_f32(
        outputCb,
        vmulq_n_f32(vbslq_f32(activeMask, transformedCb, inputCb), parameters.saturation)
    );
    vst1q_f32(
        outputCr,
        vmulq_n_f32(vbslq_f32(activeMask, transformedCr, inputCr), parameters.saturation)
    );
}
#endif

inline uint16_t niv99LumaCode(float normalized) {
    const float code = normalized * kNiv99LumaCodeRange + kNiv99LumaCodeOffset;
    return static_cast<uint16_t>(clampFinite(std::round(code), 0.0f, 1023.0f));
}

inline uint16_t niv99ChromaCode(float normalized) {
    const float code = normalized * kNiv99ChromaCodeRange + kNiv99ChromaCodeOffset;
    return static_cast<uint16_t>(clampFinite(std::round(code), 0.0f, 1023.0f));
}

#if defined(__aarch64__) && defined(__ARM_NEON)
inline uint16x4_t quantizeNiv99CodesFourNeon(
    float32x4_t normalized,
    float codeRange,
    float codeOffset
) {
    const float32x4_t codes = vfmaq_n_f32(
        vdupq_n_f32(codeOffset),
        normalized,
        codeRange
    );
    const float32x4_t bounded = vmaxnmq_f32(
        vdupq_n_f32(0.0f),
        vminnmq_f32(vdupq_n_f32(1023.0f), codes)
    );
    // All admitted values are non-negative. FCVTAS therefore matches std::round's half-away-from-
    // zero rule while converting four output codes at once.
    return vmovn_u32(vcvtaq_u32_f32(bounded));
}

inline void storeThreeEightSampleNiv99OutputsNeon(
    uint8_t* firstYTarget,
    uint8_t* secondYTarget,
    uint8_t* uvTarget,
    uint16x8_t firstRowCodes,
    uint16x8_t secondRowCodes,
    uint16x8_t chromaCodes,
    bool emitP010
) {
    const uint16x8_t firstP010Words = vshlq_n_u16(firstRowCodes, kP010Shift);
    const uint16x8_t secondP010Words = vshlq_n_u16(secondRowCodes, kP010Shift);
    const uint16x8_t chromaP010Words = vshlq_n_u16(chromaCodes, kP010Shift);
    if (emitP010) {
        vst1q_u16(reinterpret_cast<uint16_t*>(firstYTarget), firstP010Words);
        vst1q_u16(reinterpret_cast<uint16_t*>(secondYTarget), secondP010Words);
        vst1q_u16(reinterpret_cast<uint16_t*>(uvTarget), chromaP010Words);
    } else {
        storeTenPackedP010Bytes(firstYTarget, packEightP010WordsNeon(firstP010Words));
        storeTenPackedP010Bytes(secondYTarget, packEightP010WordsNeon(secondP010Words));
        storeTenPackedP010Bytes(uvTarget, packEightP010WordsNeon(chromaP010Words));
    }
}
#endif

inline void storeFourNiv99Codes(
    uint8_t* target,
    uint16_t first,
    uint16_t second,
    uint16_t third,
    uint16_t fourth,
    bool emitP010
) {
    if (emitP010) {
        const std::array<uint16_t, 4> words{
            static_cast<uint16_t>(first << kP010Shift),
            static_cast<uint16_t>(second << kP010Shift),
            static_cast<uint16_t>(third << kP010Shift),
            static_cast<uint16_t>(fourth << kP010Shift)
        };
        std::memcpy(target, words.data(), sizeof(words));
        return;
    }
    const uint64_t packed =
        static_cast<uint64_t>(first) |
        (static_cast<uint64_t>(second) << 10u) |
        (static_cast<uint64_t>(third) << 20u) |
        (static_cast<uint64_t>(fourth) << 30u);
    const uint32_t low = static_cast<uint32_t>(packed);
    std::memcpy(target, &low, sizeof(low));
    target[4] = static_cast<uint8_t>(packed >> 32u);
}

struct Niv99P010RowJob {
    // Fine claims bound the amount of raster work stranded on a preempted efficiency core. At 4K
    // this leaves enough work per claim to amortize the relaxed atomic increment while materially
    // reducing heterogeneous-core tail latency; pixel order and math remain unchanged.
    static constexpr int kLumaRowsPerClaim = 4;
    static constexpr int kMaximumHeight = 16'384;
    static constexpr size_t kMaximumChunkCount =
        static_cast<size_t>(kMaximumHeight / kLumaRowsPerClaim);

    const uint8_t* y = nullptr;
    const uint8_t* uv = nullptr;
    uint8_t* target = nullptr;
    int width = 0;
    int height = 0;
    int chromaWidth = 0;
    int chromaHeight = 0;
    int yRowStride = 0;
    int uvRowStride = 0;
    int yPixelStride = 0;
    int uvPixelStride = 0;
    int64_t outputRowBytes = 0;
    int64_t yOutputBytes = 0;
    bool emitP010 = false;
    const uint8_t* previousY = nullptr;
    const uint8_t* previousUv = nullptr;
    int previousYRowStride = 0;
    int previousUvRowStride = 0;
    int previousYPixelStride = 0;
    int previousUvPixelStride = 0;
    bool temporalHistoryActive = false;
    const Niv99P010Parameters* parameters = nullptr;
    const Niv99FilterWeightTables* filterWeights = nullptr;
    const float* decodeTransfer = nullptr;
    const float* encodeTransfer = nullptr;
    bool enableLumaBlockReuse = true;
    bool computeOutputCrc = true;
    int chunkCount = 0;
    std::array<uint32_t, kMaximumChunkCount> yChunkCrc;
    std::array<uint32_t, kMaximumChunkCount> uvChunkCrc;
    std::atomic<int> nextRow{0};
    std::atomic<bool> successful{true};
};

inline float applyNiv99TemporalSample(
    const Niv99P010RowJob* job,
    float currentCode,
    int previousCode,
    bool chroma
) {
    if (!job->temporalHistoryActive) return currentCode;
    const int boundedCurrent = std::max(0, std::min(1023, static_cast<int>(std::lround(currentCode))));
    const uint16_t threshold = chroma
        ? job->filterWeights->temporalChromaThreshold[static_cast<size_t>(boundedCurrent)]
        : job->filterWeights->temporalLumaThreshold[static_cast<size_t>(boundedCurrent)];
    const float residual = std::fabs(currentCode - static_cast<float>(previousCode));
    if (residual >= static_cast<float>(threshold)) return currentCode;
    const float requested = chroma
        ? job->parameters->temporalChromaStrength
        : job->parameters->temporalLumaStrength;
    if (requested <= 0.0f) return currentCode;
    const float inverseThreshold = chroma
        ? job->filterWeights->temporalChromaInverseThreshold[static_cast<size_t>(boundedCurrent)]
        : job->filterWeights->temporalLumaInverseThreshold[static_cast<size_t>(boundedCurrent)];
    const float normalizedResidual = residual * inverseThreshold;
    const float robust = 1.0f - normalizedResidual * normalizedResidual;
    const float weight = requested * robust * robust;
    return currentCode + (static_cast<float>(previousCode) - currentCode) * weight;
}

#if defined(__aarch64__) && defined(__ARM_NEON)
inline uint16x4_t gatherNiv99TemporalThresholdFour(
    const uint16_t* table,
    uint16x4_t indices
) {
    uint16x4_t values = vdup_n_u16(table[vget_lane_u16(indices, 0)]);
    values = vset_lane_u16(table[vget_lane_u16(indices, 1)], values, 1);
    values = vset_lane_u16(table[vget_lane_u16(indices, 2)], values, 2);
    values = vset_lane_u16(table[vget_lane_u16(indices, 3)], values, 3);
    return values;
}

/**
 * Four-sample form of [applyNiv99TemporalSample]. FCVTAS matches lround for the admitted finite,
 * non-negative P010 code range, and the comparison remains strict so a residual equal to the
 * threshold is rejected exactly as it is by the scalar reference.
 */
inline float32x4_t applyNiv99TemporalFourNeon(
    float32x4_t currentCodes,
    uint16x4_t previousCodes,
    const uint16_t* thresholdTable,
    const float* inverseThresholdTable,
    float requestedStrength
) {
    if (requestedStrength <= 0.0f) return currentCodes;

    const int32x4_t rounded = vcvtaq_s32_f32(currentCodes);
    const int32x4_t bounded = vmaxq_s32(
        vdupq_n_s32(0),
        vminq_s32(vdupq_n_s32(1023), rounded)
    );
    const uint16x4_t indices = vmovn_u32(vreinterpretq_u32_s32(bounded));
    const uint16x4_t thresholds = gatherNiv99TemporalThresholdFour(
        thresholdTable,
        indices
    );
    const float32x4_t inverseThresholds = gatherNiv99FloatFour(
        inverseThresholdTable,
        indices
    );
    const float32x4_t previous = vcvtq_f32_u32(vmovl_u16(previousCodes));
    const float32x4_t threshold = vcvtq_f32_u32(vmovl_u16(thresholds));
    const float32x4_t residual = vabsq_f32(vsubq_f32(currentCodes, previous));
    const uint32x4_t admitted = vcltq_f32(residual, threshold);
    const float32x4_t normalizedResidual = vmulq_f32(residual, inverseThresholds);
    const float32x4_t robust = vsubq_f32(
        vdupq_n_f32(1.0f),
        vmulq_f32(normalizedResidual, normalizedResidual)
    );
    const float32x4_t weight = vmulq_n_f32(
        vmulq_f32(robust, robust),
        requestedStrength
    );
    const float32x4_t blended = vfmaq_f32(
        currentCodes,
        vsubq_f32(previous, currentCodes),
        weight
    );
    return vbslq_f32(admitted, blended, currentCodes);
}

bool processNiv99P010EightColumnInteriorBlockNeon(
    Niv99P010RowJob* job,
    int row,
    int column,
    uint16x8_t* paddingAccumulator
) {
    const Niv99P010Parameters& parameters = *job->parameters;
    const Niv99FilterWeightTables& filterWeights = *job->filterWeights;
    const int chromaRow = row / 2;
    const int chromaColumn = column / 2;
    float cb[4] = {};
    float cr[4] = {};
    if (!filterNiv99ChromaFourInteriorNeon(
            job->uv,
            chromaRow,
            chromaColumn,
            job->uvRowStride,
            filterWeights,
            paddingAccumulator,
            cb,
            cr
        )) {
        return false;
    }

    float filteredY[2][8] = {};
    if (!filterNiv99LumaTwoByEightInteriorNeon(
            job->y,
            row,
            column,
            job->yRowStride,
            filterWeights,
            paddingAccumulator,
            filteredY
        )) {
        return false;
    }

    if (job->temporalHistoryActive) {
        const uint16_t* previousFirstRow = reinterpret_cast<const uint16_t*>(
            job->previousY + static_cast<int64_t>(row) * job->previousYRowStride +
                static_cast<int64_t>(column) * sizeof(uint16_t)
        );
        const uint16_t* previousSecondRow = reinterpret_cast<const uint16_t*>(
            reinterpret_cast<const uint8_t*>(previousFirstRow) + job->previousYRowStride
        );
        const uint16x8_t previousFirstCodes = vshrq_n_u16(
            vld1q_u16(previousFirstRow),
            kP010Shift
        );
        const uint16x8_t previousSecondCodes = vshrq_n_u16(
            vld1q_u16(previousSecondRow),
            kP010Shift
        );
        const uint16_t* lumaThresholds = filterWeights.temporalLumaThreshold.data();
        const float* lumaInverseThresholds =
            filterWeights.temporalLumaInverseThreshold.data();
        vst1q_f32(
            filteredY[0],
            applyNiv99TemporalFourNeon(
                vld1q_f32(filteredY[0]),
                vget_low_u16(previousFirstCodes),
                lumaThresholds,
                lumaInverseThresholds,
                parameters.temporalLumaStrength
            )
        );
        vst1q_f32(
            filteredY[0] + 4,
            applyNiv99TemporalFourNeon(
                vld1q_f32(filteredY[0] + 4),
                vget_high_u16(previousFirstCodes),
                lumaThresholds,
                lumaInverseThresholds,
                parameters.temporalLumaStrength
            )
        );
        vst1q_f32(
            filteredY[1],
            applyNiv99TemporalFourNeon(
                vld1q_f32(filteredY[1]),
                vget_low_u16(previousSecondCodes),
                lumaThresholds,
                lumaInverseThresholds,
                parameters.temporalLumaStrength
            )
        );
        vst1q_f32(
            filteredY[1] + 4,
            applyNiv99TemporalFourNeon(
                vld1q_f32(filteredY[1] + 4),
                vget_high_u16(previousSecondCodes),
                lumaThresholds,
                lumaInverseThresholds,
                parameters.temporalLumaStrength
            )
        );

        const uint16x4x2_t previousChromaWords = vld2_u16(
            reinterpret_cast<const uint16_t*>(
                job->previousUv +
                    static_cast<int64_t>(chromaRow) * job->previousUvRowStride +
                    static_cast<int64_t>(chromaColumn) * 2 * sizeof(uint16_t)
            )
        );
        const float32x4_t chromaOffset = vdupq_n_f32(kNiv99ChromaCodeOffset);
        const float32x4_t inverseChromaRange = vdupq_n_f32(1.0f / kNiv99ChromaCodeRange);
        const uint16_t* chromaThresholds = filterWeights.temporalChromaThreshold.data();
        const float* chromaInverseThresholds =
            filterWeights.temporalChromaInverseThreshold.data();
        const float32x4_t temporalCbCodes = applyNiv99TemporalFourNeon(
            vfmaq_n_f32(chromaOffset, vld1q_f32(cb), kNiv99ChromaCodeRange),
            vshr_n_u16(previousChromaWords.val[0], kP010Shift),
            chromaThresholds,
            chromaInverseThresholds,
            parameters.temporalChromaStrength
        );
        const float32x4_t temporalCrCodes = applyNiv99TemporalFourNeon(
            vfmaq_n_f32(chromaOffset, vld1q_f32(cr), kNiv99ChromaCodeRange),
            vshr_n_u16(previousChromaWords.val[1], kP010Shift),
            chromaThresholds,
            chromaInverseThresholds,
            parameters.temporalChromaStrength
        );
        vst1q_f32(cb, vmulq_f32(vsubq_f32(temporalCbCodes, chromaOffset), inverseChromaRange));
        vst1q_f32(cr, vmulq_f32(vsubq_f32(temporalCrCodes, chromaOffset), inverseChromaRange));
    }

    // With no residual WB and unity saturation, color reconstruction is mathematically the
    // identity: only the spatial/temporal luma and chroma estimates must be quantized. Avoiding the
    // redundant YUV->RGB transfer-LUT round trip preserves those exact codes while reducing the
    // common captured-WB conversion path's arithmetic and memory pressure.
    if (!parameters.whiteBalanceActive && parameters.saturation == 1.0f) {
        const uint16x8_t firstRowCodes = vcombine_u16(
            quantizeNiv99CodesFourNeon(vld1q_f32(filteredY[0]), 1.0f, 0.0f),
            quantizeNiv99CodesFourNeon(vld1q_f32(filteredY[0] + 4), 1.0f, 0.0f)
        );
        const uint16x8_t secondRowCodes = vcombine_u16(
            quantizeNiv99CodesFourNeon(vld1q_f32(filteredY[1]), 1.0f, 0.0f),
            quantizeNiv99CodesFourNeon(vld1q_f32(filteredY[1] + 4), 1.0f, 0.0f)
        );
        const uint16x4_t cbCodes = quantizeNiv99CodesFourNeon(
            vld1q_f32(cb),
            kNiv99ChromaCodeRange,
            kNiv99ChromaCodeOffset
        );
        const uint16x4_t crCodes = quantizeNiv99CodesFourNeon(
            vld1q_f32(cr),
            kNiv99ChromaCodeRange,
            kNiv99ChromaCodeOffset
        );
        const uint16x4x2_t interleavedChroma = vzip_u16(cbCodes, crCodes);
        const uint16x8_t chromaCodes = vcombine_u16(
            interleavedChroma.val[0],
            interleavedChroma.val[1]
        );
        const int64_t columnOffset = job->emitP010
            ? static_cast<int64_t>(column) * sizeof(uint16_t)
            : static_cast<int64_t>(column / 4) * 5;
        uint8_t* firstYTarget = job->target + static_cast<int64_t>(row) * job->outputRowBytes +
            columnOffset;
        uint8_t* secondYTarget = firstYTarget + job->outputRowBytes;
        uint8_t* uvTarget = job->target + job->yOutputBytes +
            static_cast<int64_t>(chromaRow) * job->outputRowBytes + columnOffset;
        storeThreeEightSampleNiv99OutputsNeon(
            firstYTarget,
            secondYTarget,
            uvTarget,
            firstRowCodes,
            secondRowCodes,
            chromaCodes,
            job->emitP010
        );
        return true;
    }

    float representativeLuma[4] = {};
    float processedLuma[4] = {};
    float outputCb[4] = {};
    float outputCr[4] = {};
    for (int cell = 0; cell < 4; ++cell) {
        const int firstColumn = cell * 2;
        representativeLuma[cell] = 0.25f * (
            filteredY[0][firstColumn] + filteredY[0][firstColumn + 1] +
            filteredY[1][firstColumn] + filteredY[1][firstColumn + 1]
        );
    }
    processNiv99PixelsFourNeon(
        representativeLuma,
        cb,
        cr,
        parameters,
        job->decodeTransfer,
        job->encodeTransfer,
        processedLuma,
        outputCb,
        outputCr
    );
    const float32x4_t lumaDeltaCodes = vfmsq_n_f32(
        vfmaq_n_f32(
            vdupq_n_f32(kNiv99LumaCodeOffset),
            vld1q_f32(processedLuma),
            kNiv99LumaCodeRange
        ),
        vld1q_f32(representativeLuma),
        1.0f
    );
    const float32x4x2_t firstDuplicatedDelta = vzipq_f32(lumaDeltaCodes, lumaDeltaCodes);
    const float32x4_t firstRowLow = vaddq_f32(
        vld1q_f32(filteredY[0]),
        firstDuplicatedDelta.val[0]
    );
    const float32x4_t firstRowHigh = vaddq_f32(
        vld1q_f32(filteredY[0] + 4),
        firstDuplicatedDelta.val[1]
    );
    const float32x4_t secondRowLow = vaddq_f32(
        vld1q_f32(filteredY[1]),
        firstDuplicatedDelta.val[0]
    );
    const float32x4_t secondRowHigh = vaddq_f32(
        vld1q_f32(filteredY[1] + 4),
        firstDuplicatedDelta.val[1]
    );
    const uint16x8_t firstRowCodes = vcombine_u16(
        vmovn_u32(vcvtaq_u32_f32(vmaxnmq_f32(
            vdupq_n_f32(0.0f),
            vminnmq_f32(vdupq_n_f32(1023.0f), firstRowLow)
        ))),
        vmovn_u32(vcvtaq_u32_f32(vmaxnmq_f32(
            vdupq_n_f32(0.0f),
            vminnmq_f32(vdupq_n_f32(1023.0f), firstRowHigh)
        )))
    );
    const uint16x8_t secondRowCodes = vcombine_u16(
        vmovn_u32(vcvtaq_u32_f32(vmaxnmq_f32(
            vdupq_n_f32(0.0f),
            vminnmq_f32(vdupq_n_f32(1023.0f), secondRowLow)
        ))),
        vmovn_u32(vcvtaq_u32_f32(vmaxnmq_f32(
            vdupq_n_f32(0.0f),
            vminnmq_f32(vdupq_n_f32(1023.0f), secondRowHigh)
        )))
    );
    const uint16x4_t cbCodes = quantizeNiv99CodesFourNeon(
        vld1q_f32(outputCb),
        kNiv99ChromaCodeRange,
        kNiv99ChromaCodeOffset
    );
    const uint16x4_t crCodes = quantizeNiv99CodesFourNeon(
        vld1q_f32(outputCr),
        kNiv99ChromaCodeRange,
        kNiv99ChromaCodeOffset
    );
    const uint16x4x2_t interleavedChroma = vzip_u16(cbCodes, crCodes);
    const uint16x8_t chromaCodes = vcombine_u16(
        interleavedChroma.val[0],
        interleavedChroma.val[1]
    );

    const int64_t columnOffset = job->emitP010
        ? static_cast<int64_t>(column) * sizeof(uint16_t)
        : static_cast<int64_t>(column / 4) * 5;
    uint8_t* firstYTarget = job->target + static_cast<int64_t>(row) * job->outputRowBytes +
        columnOffset;
    uint8_t* secondYTarget = firstYTarget + job->outputRowBytes;
    uint8_t* uvTarget = job->target + job->yOutputBytes +
        static_cast<int64_t>(chromaRow) * job->outputRowBytes + columnOffset;
    storeThreeEightSampleNiv99OutputsNeon(
        firstYTarget,
        secondYTarget,
        uvTarget,
        firstRowCodes,
        secondRowCodes,
        chromaCodes,
        job->emitP010
    );
    return true;
}
#endif

bool processNiv99P010RowPair(Niv99P010RowJob* job, int row) {
    const Niv99P010Parameters& parameters = *job->parameters;
    const Niv99FilterWeightTables& filterWeights = *job->filterWeights;
    const int chromaRow = row / 2;
    uint8_t* firstYTarget = job->target + static_cast<int64_t>(row) * job->outputRowBytes;
    uint8_t* secondYTarget = firstYTarget + job->outputRowBytes;
    uint8_t* uvTarget = job->target + job->yOutputBytes +
        static_cast<int64_t>(chromaRow) * job->outputRowBytes;
#if defined(__aarch64__) && defined(__ARM_NEON)
    uint16x8_t interiorPadding = vdupq_n_u16(0u);
#endif
    for (int column = 0; column < job->width;) {
#if defined(__aarch64__) && defined(__ARM_NEON)
        const int chromaColumn = column / 2;
        const bool interiorContiguousEight = job->enableLumaBlockReuse &&
            job->yPixelStride == 2 && job->uvPixelStride == 4 &&
            (!job->temporalHistoryActive ||
                (job->previousYPixelStride == 2 && job->previousUvPixelStride == 4)) &&
            row > 0 && row + 1 < job->height - 1 &&
            column > 0 && column + 8 < job->width &&
            chromaColumn > 0 && chromaColumn + 4 < job->chromaWidth;
        if (interiorContiguousEight) {
            if (!processNiv99P010EightColumnInteriorBlockNeon(
                    job,
                    row,
                    column,
                    &interiorPadding
                )) {
                return false;
            }
            column += 8;
            continue;
        }
#endif
        float cb[2] = {0.0f, 0.0f};
        float cr[2] = {0.0f, 0.0f};
        for (int cell = 0; cell < 2; ++cell) {
            if (!filterNiv99Chroma(
                    job->uv,
                    job->chromaWidth,
                    job->chromaHeight,
                    chromaRow,
                    column / 2 + cell,
                    job->uvRowStride,
                    job->uvPixelStride,
                    filterWeights,
                    &cb[cell],
                    &cr[cell]
                )) {
                return false;
            }
        }

        float filteredY[2][4] = {};
        uint16_t outputY[2][4] = {};
        float outputCb[2] = {0.0f, 0.0f};
        float outputCr[2] = {0.0f, 0.0f};
        const bool interiorContiguousBlock = job->enableLumaBlockReuse &&
            job->yPixelStride == 2 &&
            row > 0 && row + 1 < job->height - 1 &&
            column > 0 && column + 3 < job->width - 1;
        if (interiorContiguousBlock) {
            if (!filterNiv99LumaTwoByFourInterior(
                    job->y,
                    row,
                    column,
                    job->yRowStride,
                    filterWeights,
                    filteredY
                )) {
                return false;
            }
        } else {
            for (int localRow = 0; localRow < 2; ++localRow) {
                const int sourceRow = row + localRow;
                for (int localColumn = 0; localColumn < 4; ++localColumn) {
                    const int sourceColumn = column + localColumn;
                    if (!filterNiv99Luma(
                            job->y,
                            job->width,
                            job->height,
                            sourceRow,
                            sourceColumn,
                            job->yRowStride,
                            job->yPixelStride,
                            filterWeights,
                            &filteredY[localRow][localColumn]
                        )) {
                        return false;
                    }
                }
            }
        }
        if (job->temporalHistoryActive) {
            for (int localRow = 0; localRow < 2; ++localRow) {
                for (int localColumn = 0; localColumn < 4; ++localColumn) {
                    const int previousCode = loadP010Code(
                        job->previousY,
                        row + localRow,
                        column + localColumn,
                        job->previousYRowStride,
                        job->previousYPixelStride
                    );
                    filteredY[localRow][localColumn] = applyNiv99TemporalSample(
                        job,
                        filteredY[localRow][localColumn],
                        previousCode,
                        false
                    );
                }
            }
            for (int cell = 0; cell < 2; ++cell) {
                const int previousCb = loadP010Code(
                    job->previousUv,
                    chromaRow,
                    column / 2 + cell,
                    job->previousUvRowStride,
                    job->previousUvPixelStride,
                    0
                );
                const int previousCr = loadP010Code(
                    job->previousUv,
                    chromaRow,
                    column / 2 + cell,
                    job->previousUvRowStride,
                    job->previousUvPixelStride,
                    2
                );
                const float cbCode = cb[cell] * kNiv99ChromaCodeRange + kNiv99ChromaCodeOffset;
                const float crCode = cr[cell] * kNiv99ChromaCodeRange + kNiv99ChromaCodeOffset;
                cb[cell] = (applyNiv99TemporalSample(job, cbCode, previousCb, true) -
                    kNiv99ChromaCodeOffset) / kNiv99ChromaCodeRange;
                cr[cell] = (applyNiv99TemporalSample(job, crCode, previousCr, true) -
                    kNiv99ChromaCodeOffset) / kNiv99ChromaCodeRange;
            }
        }
        // P010 is 4:2:0: all four pixels in a 2x2 cell share one Cb/Cr pair. Evaluate the
        // transfer-aware chromatic adaptation once at that cell's representative luma instead of
        // repeating the identical chroma decision four times. The returned luma delta is applied
        // to each independently filtered luma sample, retaining full-resolution detail.
        for (int cell = 0; cell < 2; ++cell) {
            const int firstColumn = cell * 2;
            const float representativeLuma = 0.25f * (
                filteredY[0][firstColumn] + filteredY[0][firstColumn + 1] +
                filteredY[1][firstColumn] + filteredY[1][firstColumn + 1]
            );
            float processedLuma = 0.0f;
            processNiv99Pixel(
                representativeLuma,
                cb[cell],
                cr[cell],
                parameters,
                job->decodeTransfer,
                job->encodeTransfer,
                &processedLuma,
                &outputCb[cell],
                &outputCr[cell]
            );
            const float representativeNormalized =
                (representativeLuma - kNiv99LumaCodeOffset) / kNiv99LumaCodeRange;
            const float lumaDelta = processedLuma - representativeNormalized;
            for (int localRow = 0; localRow < 2; ++localRow) {
                for (int cellColumn = 0; cellColumn < 2; ++cellColumn) {
                    const int localColumn = firstColumn + cellColumn;
                    const float filteredNormalized =
                        (filteredY[localRow][localColumn] - kNiv99LumaCodeOffset) /
                            kNiv99LumaCodeRange;
                    outputY[localRow][localColumn] =
                        niv99LumaCode(filteredNormalized + lumaDelta);
                }
            }
        }
        const int64_t columnOffset = job->emitP010
            ? static_cast<int64_t>(column) * sizeof(uint16_t)
            : static_cast<int64_t>(column / 4) * 5;
        storeFourNiv99Codes(
            firstYTarget + columnOffset,
            outputY[0][0],
            outputY[0][1],
            outputY[0][2],
            outputY[0][3],
            job->emitP010
        );
        storeFourNiv99Codes(
            secondYTarget + columnOffset,
            outputY[1][0],
            outputY[1][1],
            outputY[1][2],
            outputY[1][3],
            job->emitP010
        );
        storeFourNiv99Codes(
            uvTarget + columnOffset,
            niv99ChromaCode(outputCb[0]),
            niv99ChromaCode(outputCr[0]),
            niv99ChromaCode(outputCb[1]),
            niv99ChromaCode(outputCr[1]),
            job->emitP010
        );
        column += 4;
    }
#if defined(__aarch64__) && defined(__ARM_NEON)
    if (vmaxvq_u16(interiorPadding) != 0u) return false;
#endif
    return true;
}

void processNiv99P010RowChunks(Niv99P010RowJob* job) {
    while (job->successful.load(std::memory_order_relaxed)) {
        const int firstRow = job->nextRow.fetch_add(
            Niv99P010RowJob::kLumaRowsPerClaim,
            std::memory_order_relaxed
        );
        if (firstRow >= job->height) return;
        const int rowLimit = std::min(
            job->height,
            firstRow + Niv99P010RowJob::kLumaRowsPerClaim
        );
        for (int row = firstRow; row < rowLimit; row += 2) {
            if (!processNiv99P010RowPair(job, row)) {
                job->successful.store(false, std::memory_order_relaxed);
                return;
            }
        }
        if (job->computeOutputCrc) {
            const size_t chunkIndex = static_cast<size_t>(
                firstRow / Niv99P010RowJob::kLumaRowsPerClaim
            );
            const int completedLumaRows = rowLimit - firstRow;
            const int completedChromaRows = completedLumaRows / 2;
            const uint8_t* yChunk = job->target +
                static_cast<int64_t>(firstRow) * job->outputRowBytes;
            const uint8_t* uvChunk = job->target + job->yOutputBytes +
                static_cast<int64_t>(firstRow / 2) * job->outputRowBytes;
            const uInt yChunkBytes = static_cast<uInt>(
                static_cast<int64_t>(completedLumaRows) * job->outputRowBytes
            );
            const uInt uvChunkBytes = static_cast<uInt>(
                static_cast<int64_t>(completedChromaRows) * job->outputRowBytes
            );
            const uLong initialCrc = ::crc32(0L, Z_NULL, 0);
            job->yChunkCrc[chunkIndex] = static_cast<uint32_t>(::crc32(
                initialCrc,
                reinterpret_cast<const Bytef*>(yChunk),
                yChunkBytes
            ));
            job->uvChunkCrc[chunkIndex] = static_cast<uint32_t>(::crc32(
                initialCrc,
                reinterpret_cast<const Bytef*>(uvChunk),
                uvChunkBytes
            ));
        }
    }
}

/**
 * Byte-identical ordinary P010 packet work divided into four-luma-row claims. Each claim owns
 * disjoint canonical Y and UV output ranges, so heterogeneous CPU cores can finish out of order
 * without changing raster order or packet bytes. CRCs are calculated while each written range is
 * cache-hot and are combined in canonical Y-then-UV order after every worker has completed.
 */
struct StandardP010PackRowJob {
    // A 4K frame previously created 540 claims and up to 1,080 crc32_combine calls. Thirty-two
    // rows still leave 68 independently scheduled claims for the six active lanes, while removing
    // most atomic-claim and polynomial-combine overhead. Claims own disjoint raster ranges, so this
    // scheduling change remains byte-for-byte and CRC-for-CRC identical to the four-row path.
    static constexpr int kLumaRowsPerClaim = 32;
    static constexpr int kMaximumHeight = Niv99P010RowJob::kMaximumHeight;
    static constexpr size_t kMaximumChunkCount =
        static_cast<size_t>(
            (kMaximumHeight + kLumaRowsPerClaim - 1) / kLumaRowsPerClaim
        );

    const uint8_t* y = nullptr;
    const uint8_t* uv = nullptr;
    uint8_t* target = nullptr;
    int width = 0;
    int height = 0;
    int chromaHeight = 0;
    int yRowStride = 0;
    int uvRowStride = 0;
    int64_t packedRowBytes = 0;
    int64_t yPackedBytes = 0;
    bool computeOutputCrc = false;
    int chunkCount = 0;
    std::array<uint32_t, kMaximumChunkCount> yChunkCrc{};
    std::array<uint32_t, kMaximumChunkCount> uvChunkCrc{};
    std::atomic<int> nextRow{0};
    std::atomic<bool> successful{true};
};

void processStandardP010PackRowChunks(StandardP010PackRowJob* job) {
    while (job->successful.load(std::memory_order_relaxed)) {
        const int firstRow = job->nextRow.fetch_add(
            StandardP010PackRowJob::kLumaRowsPerClaim,
            std::memory_order_relaxed
        );
        if (firstRow >= job->height) return;
        const int rowLimit = std::min(
            job->height,
            firstRow + StandardP010PackRowJob::kLumaRowsPerClaim
        );
        for (int row = firstRow; row < rowLimit; ++row) {
            if (!packContiguousP010TenBit(
                    job->y + static_cast<int64_t>(row) * job->yRowStride,
                    job->width,
                    job->target + static_cast<int64_t>(row) * job->packedRowBytes,
                    job->packedRowBytes,
                    nullptr
                )) {
                job->successful.store(false, std::memory_order_relaxed);
                return;
            }
        }

        const int firstChromaRow = firstRow / 2;
        const int chromaRowLimit = rowLimit / 2;
        for (int row = firstChromaRow; row < chromaRowLimit; ++row) {
            if (!packContiguousP010TenBit(
                    job->uv + static_cast<int64_t>(row) * job->uvRowStride,
                    job->width,
                    job->target + job->yPackedBytes +
                        static_cast<int64_t>(row) * job->packedRowBytes,
                    job->packedRowBytes,
                    nullptr
                )) {
                job->successful.store(false, std::memory_order_relaxed);
                return;
            }
        }

        if (job->computeOutputCrc) {
            const size_t chunkIndex = static_cast<size_t>(
                firstRow / StandardP010PackRowJob::kLumaRowsPerClaim
            );
            const int completedLumaRows = rowLimit - firstRow;
            const int completedChromaRows = chromaRowLimit - firstChromaRow;
            const uint8_t* yChunk = job->target +
                static_cast<int64_t>(firstRow) * job->packedRowBytes;
            const uint8_t* uvChunk = job->target + job->yPackedBytes +
                static_cast<int64_t>(firstChromaRow) * job->packedRowBytes;
            const uLong initialCrc = ::crc32(0L, Z_NULL, 0);
            job->yChunkCrc[chunkIndex] = static_cast<uint32_t>(::crc32(
                initialCrc,
                reinterpret_cast<const Bytef*>(yChunk),
                static_cast<uInt>(
                    static_cast<int64_t>(completedLumaRows) * job->packedRowBytes
                )
            ));
            job->uvChunkCrc[chunkIndex] = static_cast<uint32_t>(::crc32(
                initialCrc,
                reinterpret_cast<const Bytef*>(uvChunk),
                static_cast<uInt>(
                    static_cast<int64_t>(completedChromaRows) * job->packedRowBytes
                )
            ));
        }
    }
}

/**
 * Dynamically resolved ADPF API. NDK performance-hint symbols start at API 33; resolving them from
 * libandroid keeps the native library loadable on every older Android version supported by the app.
 */
class Niv99PerformanceHintApi final {
public:
    using GetManager = APerformanceHintManager* (*)();
    using CreateSession = APerformanceHintSession* (*)(
        APerformanceHintManager*,
        const int32_t*,
        size_t,
        int64_t
    );
    using UpdateTargetWorkDuration = int (*)(APerformanceHintSession*, int64_t);
    using ReportActualWorkDuration = int (*)(APerformanceHintSession*, int64_t);
    using SetThreads = int (*)(APerformanceHintSession*, const pid_t*, size_t);
    using CloseSession = void (*)(APerformanceHintSession*);

    Niv99PerformanceHintApi() noexcept {
        library_ = ::dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
        if (library_ == nullptr) return;
        getManager = reinterpret_cast<GetManager>(
            ::dlsym(library_, "APerformanceHint_getManager")
        );
        createSession = reinterpret_cast<CreateSession>(
            ::dlsym(library_, "APerformanceHint_createSession")
        );
        updateTargetWorkDuration = reinterpret_cast<UpdateTargetWorkDuration>(
            ::dlsym(library_, "APerformanceHint_updateTargetWorkDuration")
        );
        reportActualWorkDuration = reinterpret_cast<ReportActualWorkDuration>(
            ::dlsym(library_, "APerformanceHint_reportActualWorkDuration")
        );
        setThreads = reinterpret_cast<SetThreads>(
            ::dlsym(library_, "APerformanceHint_setThreads")
        );
        closeSession = reinterpret_cast<CloseSession>(
            ::dlsym(library_, "APerformanceHint_closeSession")
        );
    }

    bool available() const noexcept {
        return getManager != nullptr && createSession != nullptr &&
            reportActualWorkDuration != nullptr && closeSession != nullptr;
    }

    GetManager getManager = nullptr;
    CreateSession createSession = nullptr;
    UpdateTargetWorkDuration updateTargetWorkDuration = nullptr;
    ReportActualWorkDuration reportActualWorkDuration = nullptr;
    SetThreads setThreads = nullptr;
    CloseSession closeSession = nullptr;

private:
    // Process-lifetime handle: the executor closes every hint session deterministically before its
    // workers exit, while retaining libandroid avoids static-destruction-order hazards at shutdown.
    void* library_ = nullptr;
};

Niv99PerformanceHintApi& niv99PerformanceHintApi() {
    static Niv99PerformanceHintApi api;
    return api;
}

/** Process-lifetime, fixed-size executor. Threads are created only during native prewarm. */
class Niv99P010RowExecutor {
public:
    Niv99P010RowExecutor() = default;
    Niv99P010RowExecutor(const Niv99P010RowExecutor&) = delete;
    Niv99P010RowExecutor& operator=(const Niv99P010RowExecutor&) = delete;

    ~Niv99P010RowExecutor() {
        shutdown();
    }

    bool prepare() noexcept {
        std::lock_guard<std::mutex> lifecycleLock(lifecycleMutex_);
        if (started_) return true;
        {
            std::lock_guard<std::mutex> stateLock(stateMutex_);
            stopping_ = false;
            generation_ = 0u;
            completedHelpers_ = 0u;
            activeJob_ = nullptr;
            activeProcessor_ = nullptr;
        }
        size_t created = 0u;
        try {
            for (; created < helpers_.size(); ++created) {
                helpers_[created] = std::thread(
                    &Niv99P010RowExecutor::workerLoop,
                    this,
                    created
                );
            }
        } catch (...) {
            {
                std::lock_guard<std::mutex> stateLock(stateMutex_);
                stopping_ = true;
            }
            workAvailable_.notify_all();
            for (size_t index = 0; index < created; ++index) {
                if (helpers_[index].joinable()) helpers_[index].join();
            }
            return false;
        }
        {
            std::unique_lock<std::mutex> stateLock(stateMutex_);
            workersReady_.wait(stateLock, [this] {
                return liveHelpers_.load(std::memory_order_acquire) == helpers_.size();
            });
        }
        preparePerformanceHintSession();
        started_ = true;
        return true;
    }

    bool execute(Niv99P010RowJob* job) noexcept {
        if (job == nullptr) return false;
        const bool isolatedOfflineConversion = job->emitP010 && !job->computeOutputCrc;
        return executeWork(
            job,
            [](void* opaqueJob) noexcept {
                processNiv99P010RowChunks(static_cast<Niv99P010RowJob*>(opaqueJob));
            },
            isolatedOfflineConversion,
            kNiv99TargetFrameDurationNanos
        ) && job->successful.load(std::memory_order_relaxed);
    }

    bool execute(StandardP010PackRowJob* job) noexcept {
        if (job == nullptr) return false;
        return executeWork(
            job,
            [](void* opaqueJob) noexcept {
                processStandardP010PackRowChunks(
                    static_cast<StandardP010PackRowJob*>(opaqueJob)
                );
            },
            false,
            kOrdinaryPackTargetDurationNanos
        ) && job->successful.load(std::memory_order_relaxed);
    }

    // Same capture-safe helper ceiling, submission serialization, join and ADPF authority.
    // Fused lossless work used to bypass these existing helpers and occupy one core per frame.
    bool executeLosslessChunks(void* job, void (*processor)(void*) noexcept) noexcept {
        return executeWork(job, processor, false, kOrdinaryPackTargetDurationNanos);
    }

    /** Test-only lifecycle exercise used by instrumentation to prove joined, restartable workers. */
    bool exerciseLifecycleForValidation(int cycles) noexcept {
        if (cycles <= 0) return false;
        for (int cycle = 0; cycle < cycles; ++cycle) {
            if (!prepare() || liveHelpers_.load(std::memory_order_acquire) != helpers_.size()) {
                return false;
            }
            shutdown();
            if (liveHelpers_.load(std::memory_order_acquire) != 0u) return false;
        }
        return prepare() &&
            liveHelpers_.load(std::memory_order_acquire) == helpers_.size();
    }

    size_t liveHelperCount() const noexcept {
        return liveHelpers_.load(std::memory_order_acquire);
    }

private:
    using RowProcessor = void (*)(void*) noexcept;

    bool executeWork(
        void* job,
        RowProcessor processor,
        bool isolatedOfflineConversion,
        int64_t targetDurationNanos
    ) noexcept {
        if (job == nullptr || processor == nullptr || !prepare()) return false;
        // Android can move a foreground media-processing service from the top-app cpuset to a smaller
        // background cpuset while a conversion is running. Bound persistent helpers to the current
        // allowed CPU count and reserve one CPU for the urgent Kotlin submitter. Query every frame
        // because the mask can change without recreating this executor. Pixel order, coverage,
        // precision, and math are unchanged.
        const size_t requestedHelperCount = isolatedOfflineConversion
            ? kOfflineHelperThreadCount
            : kCaptureSafeHelperThreadCount;
        const size_t activeHelperCount = activeHelperCountForCurrentAffinity(
            requestedHelperCount
        );
        {
            std::unique_lock<std::mutex> submissionLock(submissionMutex_, std::defer_lock);
            {
                ScopedNpv10TraceSection waitTrace("NIV99 P010 submission wait");
                submissionLock.lock();
            }
            ScopedNpv10TraceSection executeTrace("NIV99 P010 execute");
            configurePerformanceHintSession(
                activeHelperCount,
                targetDurationNanos
            );
            const auto workStarted = std::chrono::steady_clock::now();
            {
                std::lock_guard<std::mutex> stateLock(stateMutex_);
                activeJob_ = job;
                activeProcessor_ = processor;
                activeHelperCount_ = activeHelperCount;
                completedHelpers_ = 0u;
                ++generation_;
            }
            workAvailable_.notify_all();
            processor(job);
            {
                std::unique_lock<std::mutex> stateLock(stateMutex_);
                workCompleted_.wait(stateLock, [this, activeHelperCount] {
                    return completedHelpers_ == activeHelperCount;
                });
                activeJob_ = nullptr;
                activeProcessor_ = nullptr;
                activeHelperCount_ = 0u;
            }
            const int64_t actualDurationNanos =
                std::chrono::duration_cast<std::chrono::nanoseconds>(
                    std::chrono::steady_clock::now() - workStarted
                ).count();
            reportPerformanceHint(actualDurationNanos);
        }
        return true;
    }

    void preparePerformanceHintSession() noexcept {
        if (performanceHintSession_ != nullptr) return;
        Niv99PerformanceHintApi& api = niv99PerformanceHintApi();
        if (!api.available()) {
            static std::atomic<bool> warningEmitted{false};
            if (!warningEmitted.exchange(true, std::memory_order_relaxed)) {
                NPV10_RAW_W("NIV99 ADPF performance-hint API unavailable");
            }
            return;
        }
        std::array<int32_t, kHelperThreadCount> threadIds{};
        for (size_t index = 0; index < helperThreadIds_.size(); ++index) {
            threadIds[index] = helperThreadIds_[index].load(std::memory_order_acquire);
            if (threadIds[index] <= 0) {
                NPV10_RAW_W("NIV99 ADPF helper thread identity unavailable");
                return;
            }
        }
        APerformanceHintManager* const manager = api.getManager();
        if (manager == nullptr) {
            NPV10_RAW_W("NIV99 ADPF manager unavailable");
            return;
        }
        performanceHintSession_ = api.createSession(
            manager,
            threadIds.data(),
            threadIds.size(),
            kNiv99TargetFrameDurationNanos
        );
        if (performanceHintSession_ == nullptr) {
            NPV10_RAW_W("NIV99 ADPF session creation failed");
            return;
        }
        NPV10_RAW_I(
            "NIV99 ADPF session active helpers=%zu targetNs=%lld",
            threadIds.size(),
            static_cast<long long>(kNiv99TargetFrameDurationNanos)
        );
        configuredTargetDurationNanos_ = kNiv99TargetFrameDurationNanos;
    }

    void configurePerformanceHintSession(
        size_t activeHelperCount,
        int64_t targetDurationNanos
    ) noexcept {
        if (performanceHintSession_ == nullptr || targetDurationNanos <= 0) return;
        Niv99PerformanceHintApi& api = niv99PerformanceHintApi();
        if (api.updateTargetWorkDuration != nullptr &&
            configuredTargetDurationNanos_ != targetDurationNanos) {
            if (api.updateTargetWorkDuration(performanceHintSession_, targetDurationNanos) == 0) {
                configuredTargetDurationNanos_ = targetDurationNanos;
            } else {
                static std::atomic<bool> targetWarningEmitted{false};
                if (!targetWarningEmitted.exchange(true, std::memory_order_relaxed)) {
                    NPV10_RAW_W("NIV99 ADPF target-duration update failed");
                }
            }
        }

        if (api.setThreads == nullptr) {
            static std::atomic<bool> setThreadsWarningEmitted{false};
            if (!setThreadsWarningEmitted.exchange(true, std::memory_order_relaxed)) {
                NPV10_RAW_W("NIV99 ADPF active-helper update unavailable");
            }
            return;
        }
        if (configuredActiveHelperCount_ == activeHelperCount) {
            return;
        }

        // Only persistent native helpers belong to the ADPF session. Kotlin packing workers rotate
        // across frames; adding their transient submitter IDs would continuously mutate session
        // membership and destabilize scheduler hints. One allowed CPU is reserved for that urgent
        // submitter by activeHelperCountForCurrentAffinity().
        std::array<pid_t, kHelperThreadCount> threadIds{};
        size_t threadCount = 0u;
        for (size_t index = 0; index < activeHelperCount; ++index) {
            const int32_t helperThreadId = helperThreadIds_[index].load(std::memory_order_acquire);
            if (helperThreadId <= 0) {
                static std::atomic<bool> identityWarningEmitted{false};
                if (!identityWarningEmitted.exchange(true, std::memory_order_relaxed)) {
                    NPV10_RAW_W("NIV99 ADPF active helper identity unavailable");
                }
                return;
            }
            threadIds[threadCount++] = static_cast<pid_t>(helperThreadId);
        }
        if (api.setThreads(performanceHintSession_, threadIds.data(), threadCount) == 0) {
            configuredActiveHelperCount_ = activeHelperCount;
        } else {
            static std::atomic<bool> membershipWarningEmitted{false};
            if (!membershipWarningEmitted.exchange(true, std::memory_order_relaxed)) {
                NPV10_RAW_W("NIV99 ADPF active-thread membership update failed");
            }
        }
    }

    void reportPerformanceHint(int64_t actualDurationNanos) noexcept {
        if (performanceHintSession_ == nullptr || actualDurationNanos <= 0) return;
        Niv99PerformanceHintApi& api = niv99PerformanceHintApi();
        if (api.reportActualWorkDuration(performanceHintSession_, actualDurationNanos) != 0) {
            static std::atomic<bool> warningEmitted{false};
            if (!warningEmitted.exchange(true, std::memory_order_relaxed)) {
                NPV10_RAW_W("NIV99 ADPF duration report failed");
            }
        }
    }

    void closePerformanceHintSession() noexcept {
        if (performanceHintSession_ == nullptr) return;
        Niv99PerformanceHintApi& api = niv99PerformanceHintApi();
        api.closeSession(performanceHintSession_);
        performanceHintSession_ = nullptr;
        configuredActiveHelperCount_ = 0u;
        configuredTargetDurationNanos_ = 0;
    }

    static size_t activeHelperCountForCurrentAffinity(size_t requested) noexcept {
        cpu_set_t allowedCpus;
        CPU_ZERO(&allowedCpus);
        if (::sched_getaffinity(0, sizeof(allowedCpus), &allowedCpus) != 0) {
            // Affinity discovery is expected on Android/Linux. If a vendor kernel rejects it, use a
            // conservative bounded topology and report it once rather than silently oversubscribing.
            static std::atomic<bool> warningEmitted{false};
            if (!warningEmitted.exchange(true, std::memory_order_relaxed)) {
                NPV10_RAW_W(
                    "NIV99 worker affinity query failed; limiting helpers to %zu",
                    kUnknownAffinityHelperThreadCount
                );
            }
            return std::min(requested, kUnknownAffinityHelperThreadCount);
        }

        size_t allowedCpuCount = 0u;
        for (int cpu = 0; cpu < CPU_SETSIZE; ++cpu) {
            if (CPU_ISSET(cpu, &allowedCpus)) ++allowedCpuCount;
        }
        // The submitting packing worker executes one row claim itself and remains outside the stable
        // ADPF helper membership. Reserve one allowed CPU so helper work cannot oversubscribe it.
        const size_t affinityCapacity = allowedCpuCount > 1u ? allowedCpuCount - 1u : 1u;
        return std::min(requested, affinityCapacity);
    }

    void workerLoop(size_t helperIndex) noexcept {
        char threadName[16] = {};
        (void)std::snprintf(threadName, sizeof(threadName), "NIV99-Row-%zu", helperIndex + 1u);
        (void)::pthread_setname_np(::pthread_self(), threadName);
        helperThreadIds_[helperIndex].store(static_cast<int32_t>(::gettid()), std::memory_order_release);
        liveHelpers_.fetch_add(1u, std::memory_order_release);
        workersReady_.notify_one();
        // Match the submitter's urgent-display priority so one lower-priority helper cannot become
        // the frame tail. Live DMC activates only five helpers; together with submit/persistence it
        // still leaves one physical compute lane available for Camera2 result delivery.
        (void)::setpriority(PRIO_PROCESS, 0, kNiv99HelperThreadNice);
        uint64_t observedGeneration = 0u;
        for (;;) {
            void* job = nullptr;
            RowProcessor processor = nullptr;
            size_t activeHelperCount = 0u;
            {
                std::unique_lock<std::mutex> stateLock(stateMutex_);
                workAvailable_.wait(stateLock, [this, observedGeneration] {
                    return stopping_ || generation_ != observedGeneration;
                });
                if (stopping_) break;
                observedGeneration = generation_;
                job = activeJob_;
                processor = activeProcessor_;
                activeHelperCount = activeHelperCount_;
            }
            if (job != nullptr && processor != nullptr && helperIndex < activeHelperCount) {
                processor(job);
                std::lock_guard<std::mutex> stateLock(stateMutex_);
                ++completedHelpers_;
                if (completedHelpers_ == activeHelperCount) workCompleted_.notify_one();
            }
        }
        helperThreadIds_[helperIndex].store(0, std::memory_order_release);
        liveHelpers_.fetch_sub(1u, std::memory_order_release);
    }

    void shutdown() noexcept {
        std::lock_guard<std::mutex> lifecycleLock(lifecycleMutex_);
        if (!started_) return;
        {
            std::lock_guard<std::mutex> submissionLock(submissionMutex_);
            closePerformanceHintSession();
            std::lock_guard<std::mutex> stateLock(stateMutex_);
            stopping_ = true;
        }
        workAvailable_.notify_all();
        for (std::thread& helper : helpers_) {
            if (helper.joinable()) helper.join();
        }
        started_ = false;
    }

    // Seven helpers are kept warm so mode changes never allocate on the frame path. Offline and packed
    // live work activate five plus the submitter; live persistence can use one of the two reserve cores.
    static constexpr size_t kHelperThreadCount = 7u;
    static constexpr size_t kOfflineHelperThreadCount = 5u;
    static constexpr size_t kCaptureSafeHelperThreadCount = 5u;
    static constexpr size_t kUnknownAffinityHelperThreadCount = 4u;
    static constexpr int kNiv99HelperThreadNice = -8;
    // The pixel stage cannot consume the whole 40 ms 4K25 presentation period: decode, encoder
    // submission and persistence need bounded headroom. ADPF therefore receives the measured
    // 30 ms compute budget while Android retains thermal and power authority.
    static constexpr int64_t kNiv99TargetFrameDurationNanos = 30'000'000LL;
    // Packing must finish well inside the 40 ms 4K25 frame period so writer jitter cannot consume
    // all queue headroom. This is a scheduling hint only; it does not alter pixels or quality.
    static constexpr int64_t kOrdinaryPackTargetDurationNanos = 18'000'000LL;
    std::array<std::thread, kHelperThreadCount> helpers_{};
    std::array<std::atomic<int32_t>, kHelperThreadCount> helperThreadIds_{};
    std::mutex lifecycleMutex_;
    std::mutex submissionMutex_;
    std::mutex stateMutex_;
    std::condition_variable workAvailable_;
    std::condition_variable workCompleted_;
    std::condition_variable workersReady_;
    void* activeJob_ = nullptr;
    RowProcessor activeProcessor_ = nullptr;
    size_t activeHelperCount_ = 0u;
    uint64_t generation_ = 0u;
    size_t completedHelpers_ = 0u;
    std::atomic<size_t> liveHelpers_{0u};
    APerformanceHintSession* performanceHintSession_ = nullptr;
    size_t configuredActiveHelperCount_ = 0u;
    int64_t configuredTargetDurationNanos_ = 0;
    bool stopping_ = false;
    bool started_ = false;
};

Niv99P010RowExecutor& niv99P010RowExecutor() {
    // Construct the dynamically resolved API before the executor so static teardown closes the
    // session while its function table is still alive.
    (void)niv99PerformanceHintApi();
    static Niv99P010RowExecutor executor;
    return executor;
}

/**
 * Production ordinary-P010 packer. Full-resolution, even-height Camera2 surfaces use the prewarmed
 * row executor; unusual dimensions retain the proven scalar/NEON implementation. Falling back after
 * an executor startup or worker failure is safe because the serial implementation deterministically
 * overwrites the complete target and revalidates every P010 padding bit.
 */
bool packStandardP010PlanesToTenBit(
    const uint8_t* y,
    int64_t yCapacity,
    const uint8_t* uv,
    int64_t uvCapacity,
    int width,
    int height,
    int yRowStride,
    int uvRowStride,
    int yPixelStride,
    int uvPixelStride,
    uint8_t* target,
    int64_t targetCapacity,
    uint32_t* crcOut
) {
    if (y == nullptr || uv == nullptr || target == nullptr ||
        width <= 0 || height <= 0 || height > StandardP010PackRowJob::kMaximumHeight ||
        (width & 3) != 0 || (height & 1) != 0 ||
        yPixelStride != 2 || uvPixelStride != 4 ||
        yRowStride <= 0 || uvRowStride <= 0) {
        return packStandardP010PlanesToTenBitSerial(
            y,
            yCapacity,
            uv,
            uvCapacity,
            width,
            height,
            yRowStride,
            uvRowStride,
            yPixelStride,
            uvPixelStride,
            target,
            targetCapacity,
            crcOut
        );
    }

    const int chromaWidth = width / 2;
    const int chromaHeight = height / 2;
    const int64_t packedRowBytes = static_cast<int64_t>(width) * 10 / 8;
    const int64_t yPackedBytes = packedRowBytes * height;
    const int64_t targetBytes = yPackedBytes + packedRowBytes * chromaHeight;
    const int64_t requiredYBytes = static_cast<int64_t>(height - 1) * yRowStride +
        static_cast<int64_t>(width) * sizeof(uint16_t);
    const int64_t requiredUvBytes = static_cast<int64_t>(chromaHeight - 1) * uvRowStride +
        static_cast<int64_t>(chromaWidth) * 2 * sizeof(uint16_t);
    const int64_t maximumClaimBytes =
        packedRowBytes * StandardP010PackRowJob::kLumaRowsPerClaim;
    if (packedRowBytes <= 0 || yPackedBytes <= 0 || targetBytes <= 0 ||
        requiredYBytes <= 0 || requiredUvBytes <= 0 ||
        maximumClaimBytes <= 0 ||
        maximumClaimBytes > static_cast<int64_t>(std::numeric_limits<uInt>::max()) ||
        yCapacity < requiredYBytes || uvCapacity < requiredUvBytes ||
        targetCapacity < targetBytes) {
        return false;
    }

    StandardP010PackRowJob rowJob;
    rowJob.y = y;
    rowJob.uv = uv;
    rowJob.target = target;
    rowJob.width = width;
    rowJob.height = height;
    rowJob.chromaHeight = chromaHeight;
    rowJob.yRowStride = yRowStride;
    rowJob.uvRowStride = uvRowStride;
    rowJob.packedRowBytes = packedRowBytes;
    rowJob.yPackedBytes = yPackedBytes;
    rowJob.computeOutputCrc = crcOut != nullptr;
    rowJob.chunkCount = (
        height + StandardP010PackRowJob::kLumaRowsPerClaim - 1
    ) / StandardP010PackRowJob::kLumaRowsPerClaim;
    rowJob.nextRow.store(0, std::memory_order_relaxed);
    rowJob.successful.store(true, std::memory_order_relaxed);

    {
        ScopedNpv10TraceSection trace("NPV10 row-parallel P010 pack");
        if (!niv99P010RowExecutor().execute(&rowJob)) {
            return packStandardP010PlanesToTenBitSerial(
                y,
                yCapacity,
                uv,
                uvCapacity,
                width,
                height,
                yRowStride,
                uvRowStride,
                yPixelStride,
                uvPixelStride,
                target,
                targetCapacity,
                crcOut
            );
        }
    }

    if (crcOut != nullptr) {
        ScopedNpv10TraceSection crcTrace("NPV10 P010 CRC combine");
        uLong combinedCrc = ::crc32(0L, Z_NULL, 0);
        for (int chunk = 0; chunk < rowJob.chunkCount; ++chunk) {
            const int firstRow = chunk * StandardP010PackRowJob::kLumaRowsPerClaim;
            const int rowCount = std::min(
                height - firstRow,
                StandardP010PackRowJob::kLumaRowsPerClaim
            );
            combinedCrc = ::crc32_combine(
                combinedCrc,
                rowJob.yChunkCrc[static_cast<size_t>(chunk)],
                static_cast<z_off_t>(static_cast<int64_t>(rowCount) * packedRowBytes)
            );
        }
        for (int chunk = 0; chunk < rowJob.chunkCount; ++chunk) {
            const int firstRow = chunk * StandardP010PackRowJob::kLumaRowsPerClaim;
            const int rowCount = std::min(
                height - firstRow,
                StandardP010PackRowJob::kLumaRowsPerClaim
            ) / 2;
            combinedCrc = ::crc32_combine(
                combinedCrc,
                rowJob.uvChunkCrc[static_cast<size_t>(chunk)],
                static_cast<z_off_t>(static_cast<int64_t>(rowCount) * packedRowBytes)
            );
        }
        *crcOut = static_cast<uint32_t>(combinedCrc);
    }
    return true;
}

bool acceptNiv99TemporalHistory(
    const uint8_t* currentY,
    const uint8_t* previousY,
    int width,
    int height,
    int currentRowStride,
    int currentPixelStride,
    int previousRowStride,
    int previousPixelStride
) {
    if (currentY == nullptr || previousY == nullptr || width <= 0 || height <= 0) return false;
    constexpr int64_t kMaximumSamples = 4096;
    constexpr int kGrossMotionThresholdCodes = 96;
    constexpr double kSceneCutMeanThresholdCodes = 72.0;
    constexpr double kSceneCutGrossRatio = 0.45;
    const int64_t pixelCount = static_cast<int64_t>(width) * height;
    const int64_t step = std::max<int64_t>(1, pixelCount / kMaximumSamples);
    uint64_t totalDifference = 0u;
    uint64_t grossDifferenceCount = 0u;
    uint64_t sampleCount = 0u;
    for (int64_t index = 0; index < pixelCount; index += step) {
        const int row = static_cast<int>(index / width);
        const int column = static_cast<int>(index - static_cast<int64_t>(row) * width);
        const int current = loadP010Code(
            currentY,
            row,
            column,
            currentRowStride,
            currentPixelStride
        );
        const int previous = loadP010Code(
            previousY,
            row,
            column,
            previousRowStride,
            previousPixelStride
        );
        const uint32_t difference = static_cast<uint32_t>(std::abs(current - previous));
        totalDifference += difference;
        if (difference >= kGrossMotionThresholdCodes) ++grossDifferenceCount;
        ++sampleCount;
    }
    if (sampleCount == 0u) return false;
    const double meanDifference = static_cast<double>(totalDifference) /
        static_cast<double>(sampleCount);
    const double grossRatio = static_cast<double>(grossDifferenceCount) /
        static_cast<double>(sampleCount);
    return meanDifference < kSceneCutMeanThresholdCodes && grossRatio < kSceneCutGrossRatio;
}

bool processNiv99P010PlanesToTenBit(
    const uint8_t* y,
    int64_t yCapacity,
    const uint8_t* uv,
    int64_t uvCapacity,
    int width,
    int height,
    int yRowStride,
    int uvRowStride,
    int yPixelStride,
    int uvPixelStride,
    uint8_t* target,
    int64_t targetCapacity,
    const Niv99P010Parameters& parameters,
    bool enableLumaBlockReuse,
    uint32_t* crcOut,
    bool emitP010 = false,
    const uint8_t* previousY = nullptr,
    int64_t previousYCapacity = 0,
    const uint8_t* previousUv = nullptr,
    int64_t previousUvCapacity = 0,
    int previousYRowStride = 0,
    int previousUvRowStride = 0,
    int previousYPixelStride = 0,
    int previousUvPixelStride = 0,
    bool* temporalUsedOut = nullptr
) {
    if (temporalUsedOut != nullptr) *temporalUsedOut = false;
    if (y == nullptr || uv == nullptr || target == nullptr ||
        (crcOut == nullptr && !emitP010) ||
        target == y || target == uv ||
        width <= 0 || height <= 0 || height > Niv99P010RowJob::kMaximumHeight ||
        (width & 3) != 0 || (height & 1) != 0 ||
        yPixelStride < 2 || uvPixelStride < 4 ||
        yRowStride <= 0 || uvRowStride <= 0) {
        return false;
    }
    const int chromaWidth = width / 2;
    const int chromaHeight = height / 2;
    const int64_t outputRowBytes = emitP010
        ? static_cast<int64_t>(width) * sizeof(uint16_t)
        : static_cast<int64_t>(width) * 10 / 8;
    const int64_t yOutputBytes = outputRowBytes * height;
    const int64_t targetBytes = yOutputBytes + outputRowBytes * chromaHeight;
    const int64_t requiredYBytes = static_cast<int64_t>(height - 1) * yRowStride +
        static_cast<int64_t>(width - 1) * yPixelStride + 2;
    const int64_t requiredUvBytes = static_cast<int64_t>(chromaHeight - 1) * uvRowStride +
        static_cast<int64_t>(chromaWidth - 1) * uvPixelStride + 4;
    if (requiredYBytes <= 0 || requiredUvBytes <= 0 ||
        yCapacity < requiredYBytes || uvCapacity < requiredUvBytes ||
        targetCapacity < targetBytes ||
        targetBytes > static_cast<int64_t>(std::numeric_limits<uInt>::max())) {
        return false;
    }
    const bool temporalRequested = parameters.temporalLumaStrength > 0.0f ||
        parameters.temporalChromaStrength > 0.0f;
    const int64_t requiredPreviousYBytes = previousY != nullptr
        ? static_cast<int64_t>(height - 1) * previousYRowStride +
            static_cast<int64_t>(width - 1) * previousYPixelStride + 2
        : 0;
    const int64_t requiredPreviousUvBytes = previousUv != nullptr
        ? static_cast<int64_t>(chromaHeight - 1) * previousUvRowStride +
            static_cast<int64_t>(chromaWidth - 1) * previousUvPixelStride + 4
        : 0;
    const bool previousLayoutValid = temporalRequested && previousY != nullptr && previousUv != nullptr &&
        previousYRowStride > 0 && previousUvRowStride > 0 &&
        previousYPixelStride >= 2 && previousUvPixelStride >= 4 &&
        requiredPreviousYBytes > 0 && requiredPreviousUvBytes > 0 &&
        previousYCapacity >= requiredPreviousYBytes && previousUvCapacity >= requiredPreviousUvBytes;
    const bool temporalHistoryActive = previousLayoutValid && acceptNiv99TemporalHistory(
        y,
        previousY,
        width,
        height,
        yRowStride,
        yPixelStride,
        previousYRowStride,
        previousYPixelStride
    );
    const Niv99FilterWeightTables filterWeights(parameters);
    const auto& transferTables = niv99TransferLookupTables();

    Niv99P010RowJob rowJob;
    rowJob.y = y;
    rowJob.uv = uv;
    rowJob.target = target;
    rowJob.width = width;
    rowJob.height = height;
    rowJob.chromaWidth = chromaWidth;
    rowJob.chromaHeight = chromaHeight;
    rowJob.yRowStride = yRowStride;
    rowJob.uvRowStride = uvRowStride;
    rowJob.yPixelStride = yPixelStride;
    rowJob.uvPixelStride = uvPixelStride;
    rowJob.outputRowBytes = outputRowBytes;
    rowJob.yOutputBytes = yOutputBytes;
    rowJob.emitP010 = emitP010;
    rowJob.previousY = previousY;
    rowJob.previousUv = previousUv;
    rowJob.previousYRowStride = previousYRowStride;
    rowJob.previousUvRowStride = previousUvRowStride;
    rowJob.previousYPixelStride = previousYPixelStride;
    rowJob.previousUvPixelStride = previousUvPixelStride;
    rowJob.temporalHistoryActive = temporalHistoryActive;
    rowJob.parameters = &parameters;
    rowJob.filterWeights = &filterWeights;
    rowJob.decodeTransfer = transferTables.decoded[
        static_cast<size_t>(parameters.transferCode)
    ].data();
    rowJob.encodeTransfer = transferTables.encoded[
        static_cast<size_t>(parameters.transferCode)
    ].data();
    rowJob.enableLumaBlockReuse = enableLumaBlockReuse;
    rowJob.computeOutputCrc = crcOut != nullptr;
    rowJob.chunkCount = (
        height + Niv99P010RowJob::kLumaRowsPerClaim - 1
    ) / Niv99P010RowJob::kLumaRowsPerClaim;
    rowJob.nextRow.store(0, std::memory_order_relaxed);
    rowJob.successful.store(true, std::memory_order_relaxed);
    if (!niv99P010RowExecutor().execute(&rowJob)) return false;
    if (crcOut != nullptr) {
        uLong combinedCrc = ::crc32(0L, Z_NULL, 0);
        for (int chunk = 0; chunk < rowJob.chunkCount; ++chunk) {
            const int firstRow = chunk * Niv99P010RowJob::kLumaRowsPerClaim;
            const int rowCount = std::min(
                height - firstRow,
                Niv99P010RowJob::kLumaRowsPerClaim
            );
            const z_off_t bytes = static_cast<z_off_t>(
                static_cast<int64_t>(rowCount) * outputRowBytes
            );
            combinedCrc = ::crc32_combine(combinedCrc, rowJob.yChunkCrc[chunk], bytes);
        }
        for (int chunk = 0; chunk < rowJob.chunkCount; ++chunk) {
            const int firstRow = chunk * Niv99P010RowJob::kLumaRowsPerClaim;
            const int rowCount = std::min(
                height - firstRow,
                Niv99P010RowJob::kLumaRowsPerClaim
            ) / 2;
            const z_off_t bytes = static_cast<z_off_t>(
                static_cast<int64_t>(rowCount) * outputRowBytes
            );
            combinedCrc = ::crc32_combine(combinedCrc, rowJob.uvChunkCrc[chunk], bytes);
        }
        *crcOut = static_cast<uint32_t>(combinedCrc);
    }
    if (temporalUsedOut != nullptr) *temporalUsedOut = temporalHistoryActive;
    return true;
}

bool resolveNiv99P010Parameters(
    float redCorrection,
    float greenCorrection,
    float blueCorrection,
    float spatialDenoise,
    float chromaDenoise,
    float sharpeningCeiling,
    float confidence,
    int matrixCode,
    int transferCode,
    Niv99P010Parameters* output,
    float saturation = 1.0f,
    float temporalLumaStrength = 0.0f,
    float temporalChromaStrength = 0.0f,
    float shotNoiseCoefficient = 0.0f,
    float readNoiseCoefficient = 0.0f
) {
    if (output == nullptr ||
        !std::isfinite(redCorrection) || !std::isfinite(greenCorrection) ||
        !std::isfinite(blueCorrection) || !std::isfinite(spatialDenoise) ||
        !std::isfinite(chromaDenoise) || !std::isfinite(sharpeningCeiling) ||
        !std::isfinite(confidence) || !std::isfinite(saturation) ||
        !std::isfinite(temporalLumaStrength) || !std::isfinite(temporalChromaStrength) ||
        !std::isfinite(shotNoiseCoefficient) || !std::isfinite(readNoiseCoefficient) ||
        redCorrection < 0.25f || redCorrection > 4.0f ||
        greenCorrection < 0.25f || greenCorrection > 4.0f ||
        blueCorrection < 0.25f || blueCorrection > 4.0f ||
        matrixCode < kNiv99MatrixBt709 || matrixCode > kNiv99MatrixBt2020Ncl ||
        transferCode < kNiv99TransferBt709 || transferCode > kNiv99TransferSrgb) {
        return false;
    }
    const float boundedConfidence = clampFinite(confidence, 0.0f, 1.0f);
    const float kr = matrixCode == kNiv99MatrixBt2020Ncl ? 0.2627f : 0.2126f;
    const float kb = matrixCode == kNiv99MatrixBt2020Ncl ? 0.0593f : 0.0722f;
    const float kg = 1.0f - kr - kb;
    const float redChromaScale = 2.0f * (1.0f - kr);
    const float blueChromaScale = 2.0f * (1.0f - kb);
    *output = {
        std::pow(redCorrection, boundedConfidence),
        std::pow(greenCorrection, boundedConfidence),
        std::pow(blueCorrection, boundedConfidence),
        clampFinite(spatialDenoise, 0.0f, 0.62f),
        clampFinite(chromaDenoise, 0.0f, 0.86f),
        clampFinite(sharpeningCeiling, 0.0f, 0.72f) * boundedConfidence,
        kr,
        kg,
        kb,
        redChromaScale,
        blueChromaScale,
        1.0f / kg,
        1.0f / redChromaScale,
        1.0f / blueChromaScale,
        transferCode,
        std::fabs(redCorrection - 1.0f) > 1.0e-5f ||
            std::fabs(greenCorrection - 1.0f) > 1.0e-5f ||
            std::fabs(blueCorrection - 1.0f) > 1.0e-5f
    };
    output->saturation = clampFinite(saturation, 0.5f, 2.0f);
    output->temporalLumaStrength = clampFinite(temporalLumaStrength, 0.0f, 0.72f);
    output->temporalChromaStrength = clampFinite(temporalChromaStrength, 0.0f, 0.64f);
    output->shotNoiseCoefficient = clampFinite(shotNoiseCoefficient, 0.0f, 0.25f);
    output->readNoiseCoefficient = clampFinite(readNoiseCoefficient, 0.0f, 0.25f);
    return true;
}

/**
 * Deterministic native oracle for the exact 2x4 luma-neighbor reuse optimization.
 *
 * Every synthetic P010 scene is processed twice through the complete NIV99 packer. The reference
 * pass forces the original scalar luma reads; the candidate pass enables production block reuse.
 * Equality includes every packed luma/chroma bit and the independently calculated packet CRC.
 */
int64_t verifyNiv99P010BlockReuseParity() {
    constexpr int kWidth = 64;
    constexpr int kHeight = 32;
    constexpr int kChromaHeight = kHeight / 2;
    constexpr int kRowStride = kWidth * 2 + 16;
    constexpr int64_t kPackedRowBytes = static_cast<int64_t>(kWidth) * 10 / 8;
    constexpr int64_t kPackedBytes = kPackedRowBytes * (kHeight + kChromaHeight);
    constexpr int kSceneCount = 10;
    std::array<uint8_t, kRowStride * kHeight> y{};
    std::array<uint8_t, kRowStride * kChromaHeight> uv{};
    std::array<uint8_t, kRowStride * kHeight> previousY{};
    std::array<uint8_t, kRowStride * kChromaHeight> previousUv{};
    std::array<uint8_t, static_cast<size_t>(kPackedBytes)> scalarOutput{};
    std::array<uint8_t, static_cast<size_t>(kPackedBytes)> blockOutput{};

    const std::array<Niv99P010Parameters, 3> parameterSets = {{
        {
            1.0f, 1.0f, 1.0f,
            0.62f, 0.86f, 0.72f,
            0.2126f, 0.7152f, 0.0722f,
            1.5748f, 1.8556f,
            1.0f / 0.7152f, 1.0f / 1.5748f, 1.0f / 1.8556f,
            kNiv99TransferBt709, false,
            1.0f, 0.61f, 0.57f, 0.003f, 0.002f
        },
        {
            1.08f, 1.0f, 0.93f,
            0.37f, 0.71f, 0.41f,
            0.2126f, 0.7152f, 0.0722f,
            1.5748f, 1.8556f,
            1.0f / 0.7152f, 1.0f / 1.5748f, 1.0f / 1.8556f,
            kNiv99TransferHlg, true,
            1.0f, 0.70f, 0.64f, 0.005f, 0.003f
        },
        {
            0.91f, 1.02f, 1.11f,
            0.18f, 0.45f, 0.24f,
            0.2627f, 0.6780f, 0.0593f,
            1.4746f, 1.8814f,
            1.0f / 0.6780f, 1.0f / 1.4746f, 1.0f / 1.8814f,
            kNiv99TransferPq, true,
            1.0f, 0.48f, 0.62f, 0.008f, 0.004f
        }
    }};

    auto storeP010 = [](uint8_t* address, uint16_t code) {
        const uint16_t word = static_cast<uint16_t>((code & 0x03FFu) << kP010Shift);
        std::memcpy(address, &word, sizeof(word));
    };
    uint32_t randomState = 0x6D2B79F5u;
    auto nextRandom = [&randomState]() {
        randomState ^= randomState << 13u;
        randomState ^= randomState >> 17u;
        randomState ^= randomState << 5u;
        return randomState;
    };

    int64_t comparedBytes = 0;
    for (int scene = 0; scene < kSceneCount; ++scene) {
        for (int row = 0; row < kHeight; ++row) {
            for (int column = 0; column < kWidth; ++column) {
                uint16_t code = 512u;
                switch (scene) {
                    case 0: code = 64u; break;
                    case 1: code = 940u; break;
                    case 2: code = static_cast<uint16_t>(64 + column * 876 / (kWidth - 1)); break;
                    case 3: code = column < kWidth / 2 ? 96u : 900u; break;
                    case 4: code = row < kHeight / 2 ? 112u : 884u; break;
                    case 5: code = ((row + column) & 1) == 0 ? 72u : 932u; break;
                    case 6: code = (row == kHeight / 2 && column == kWidth / 2) ? 1023u : 64u; break;
                    case 7: code = static_cast<uint16_t>(64u + nextRandom() % 65u); break;
                    case 8: code = static_cast<uint16_t>(876u + nextRandom() % 65u); break;
                    default: code = static_cast<uint16_t>(nextRandom() & 0x03FFu); break;
                }
                storeP010(
                    y.data() + static_cast<int64_t>(row) * kRowStride + column * 2,
                    code
                );
                const int temporalOffset = ((row * 3 + column * 5 + scene) % 7) - 3;
                const uint16_t previousCode = static_cast<uint16_t>(std::max(
                    0,
                    std::min(1023, static_cast<int>(code) + temporalOffset)
                ));
                storeP010(
                    previousY.data() + static_cast<int64_t>(row) * kRowStride + column * 2,
                    previousCode
                );
            }
        }
        for (int row = 0; row < kChromaHeight; ++row) {
            for (int column = 0; column < kWidth / 2; ++column) {
                const uint16_t cb = scene < 2
                    ? 512u
                    : static_cast<uint16_t>(64u + nextRandom() % 897u);
                const uint16_t cr = scene < 2
                    ? 512u
                    : static_cast<uint16_t>(64u + nextRandom() % 897u);
                uint8_t* pair = uv.data() + static_cast<int64_t>(row) * kRowStride + column * 4;
                storeP010(pair, cb);
                storeP010(pair + 2, cr);
                uint8_t* previousPair = previousUv.data() +
                    static_cast<int64_t>(row) * kRowStride + column * 4;
                const int cbOffset = ((row + column * 3 + scene) % 5) - 2;
                const int crOffset = ((row * 3 + column + scene) % 5) - 2;
                storeP010(previousPair, static_cast<uint16_t>(std::max(
                    0,
                    std::min(1023, static_cast<int>(cb) + cbOffset)
                )));
                storeP010(previousPair + 2, static_cast<uint16_t>(std::max(
                    0,
                    std::min(1023, static_cast<int>(cr) + crOffset)
                )));
            }
        }

        scalarOutput.fill(0xA5u);
        blockOutput.fill(0x5Au);
        uint32_t scalarCrc = 0u;
        uint32_t blockCrc = 0u;
        bool scalarTemporalUsed = false;
        bool blockTemporalUsed = false;
        const Niv99P010Parameters& parameters =
            parameterSets[static_cast<size_t>(scene) % parameterSets.size()];
        if (!processNiv99P010PlanesToTenBit(
                y.data(), y.size(), uv.data(), uv.size(),
                kWidth, kHeight, kRowStride, kRowStride, 2, 4,
                scalarOutput.data(), scalarOutput.size(), parameters, false, &scalarCrc, false,
                previousY.data(), previousY.size(), previousUv.data(), previousUv.size(),
                kRowStride, kRowStride, 2, 4, &scalarTemporalUsed
            ) ||
            !processNiv99P010PlanesToTenBit(
                y.data(), y.size(), uv.data(), uv.size(),
                kWidth, kHeight, kRowStride, kRowStride, 2, 4,
                blockOutput.data(), blockOutput.size(), parameters, true, &blockCrc, false,
                previousY.data(), previousY.size(), previousUv.data(), previousUv.size(),
                kRowStride, kRowStride, 2, 4, &blockTemporalUsed
            )) {
            return -1;
        }
        if (!scalarTemporalUsed || !blockTemporalUsed) return -50 - scene;
        if (scalarCrc != blockCrc ||
            std::memcmp(scalarOutput.data(), blockOutput.data(), scalarOutput.size()) != 0) {
            return -2 - scene;
        }
        comparedBytes += kPackedBytes;
    }

#if defined(__aarch64__) && defined(__ARM_NEON)
    // The fast path defers horizontal padding reduction until the end of each row pair. Prove that
    // this batching still rejects corrupt luma and chroma padding rather than silently masking it.
    uint32_t rejectedCrc = 0u;
    uint8_t* corruptLuma = y.data() + static_cast<int64_t>(4) * kRowStride + 8 * 2;
    corruptLuma[0] |= 0x01u;
    if (processNiv99P010PlanesToTenBit(
            y.data(), y.size(), uv.data(), uv.size(),
            kWidth, kHeight, kRowStride, kRowStride, 2, 4,
            blockOutput.data(), blockOutput.size(), parameterSets[1], true, &rejectedCrc
        )) {
        return -100;
    }
    corruptLuma[0] &= 0xFEu;

    uint8_t* corruptChroma = uv.data() + static_cast<int64_t>(2) * kRowStride + 4 * 4;
    corruptChroma[0] |= 0x01u;
    if (processNiv99P010PlanesToTenBit(
            y.data(), y.size(), uv.data(), uv.size(),
            kWidth, kHeight, kRowStride, kRowStride, 2, 4,
            blockOutput.data(), blockOutput.size(), parameterSets[2], true, &rejectedCrc
        )) {
        return -101;
    }
#endif
    return comparedBytes;
}

/**
 * Carries the variable packet byte count and its CRC through the existing allocation-free JNI
 * result. The high 32 bits are a positive byte count; the low 32 bits preserve every CRC bit.
 */
jlong preparedPayloadToken(
    const neoproc::npv10::DirectMappedPreparedPayload& prepared
) noexcept {
    if (prepared.storedBytes <= 0 ||
        prepared.storedBytes > std::numeric_limits<std::int32_t>::max()) return -1;
    const std::uint64_t token =
        (static_cast<std::uint64_t>(prepared.storedBytes) << 32u) |
        static_cast<std::uint64_t>(prepared.storedCrc32);
    return static_cast<jlong>(token);
}

} // namespace

extern "C" JNIEXPORT jobject JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10NativeFrameBufferAllocator_nativeAllocate(
    JNIEnv* env,
    jclass,
    jlong capacity
) {
    if (capacity <= 0 ||
        static_cast<uint64_t>(capacity) > static_cast<uint64_t>(std::numeric_limits<size_t>::max())) {
        return nullptr;
    }
    const size_t bytes = static_cast<size_t>(capacity);
    void* address = mmap(
        nullptr,
        bytes,
        PROT_READ | PROT_WRITE,
        MAP_PRIVATE | MAP_ANONYMOUS,
        -1,
        0
    );
    if (address == MAP_FAILED) {
        NPV10_RAW_W("Native export mmap failed for %zu bytes", bytes);
        return nullptr;
    }
    jobject buffer = env->NewDirectByteBuffer(address, capacity);
    if (buffer == nullptr || env->ExceptionCheck()) {
        munmap(address, bytes);
        return nullptr;
    }
    return buffer;
}

extern "C" JNIEXPORT void JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10NativeFrameBufferAllocator_nativeRelease(
    JNIEnv* env,
    jclass,
    jobject buffer
) {
    if (buffer == nullptr) return;
    void* address = env->GetDirectBufferAddress(buffer);
    const jlong capacity = env->GetDirectBufferCapacity(buffer);
    if (address == nullptr || capacity <= 0) return;
    munmap(address, static_cast<size_t>(capacity));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10PlaybackNativeRenderer_nativeRenderP010Bt709(
    JNIEnv* env,
    jclass,
    jobject payloadBuffer,
    jint width,
    jint height,
    jint scale,
    jintArray outputArray
) {
    uint16_t* payload = nullptr;
    jlong payloadCapacityBytes = 0;
    if (!directU16(payloadBuffer, env, &payload, &payloadCapacityBytes) ||
        outputArray == nullptr || width <= 0 || height <= 0 || scale <= 0) {
        return JNI_FALSE;
    }
    const int64_t ySamples = static_cast<int64_t>(width) * height;
    const int64_t chromaWidth = (static_cast<int64_t>(width) + 1) / 2;
    const int64_t chromaHeight = (static_cast<int64_t>(height) + 1) / 2;
    if (ySamples <= 0 || chromaWidth <= 0 || chromaHeight <= 0) return JNI_FALSE;
    const int64_t totalSamples = ySamples + chromaWidth * chromaHeight * 2;
    if (totalSamples > std::numeric_limits<int64_t>::max() / 2 ||
        payloadCapacityBytes < totalSamples * 2) {
        return JNI_FALSE;
    }
    const int destinationWidth = std::max(1, width / scale);
    const int destinationHeight = std::max(1, height / scale);
    const int64_t outputPixels = static_cast<int64_t>(destinationWidth) * destinationHeight;
    if (outputPixels <= 0 || outputPixels > env->GetArrayLength(outputArray)) return JNI_FALSE;

    struct Bt709LookupTables {
        float luma[1024];
        float cbToGreen[1024];
        float crToGreen[1024];
        float crToRed[1024];
        float cbToBlue[1024];

        Bt709LookupTables() {
            for (int code = 0; code < 1024; ++code) {
                luma[code] = static_cast<float>(code - 64) / 876.0f;
                const float chroma = static_cast<float>(code - 512) / 896.0f;
                cbToGreen[code] = 0.1873f * chroma;
                crToGreen[code] = 0.4681f * chroma;
                crToRed[code] = 1.5748f * chroma;
                cbToBlue[code] = 1.8556f * chroma;
            }
        }
    };
    static const Bt709LookupTables lut;

    // Do not hold a JNI critical section while converting an entire frame. ART may suspend
    // garbage collection for the lifetime of a critical array, which turns long/high-resolution
    // private-master playback into process-wide stalls. GetIntArrayElements preserves the exact
    // output pixels while allowing the VM to copy or pin according to its current GC policy.
    auto* output = env->GetIntArrayElements(outputArray, nullptr);
    if (output == nullptr || env->ExceptionCheck()) return JNI_FALSE;
    auto channel8 = [](float value) -> int {
        const float bounded = std::max(0.0f, std::min(1.0f, value));
        return static_cast<int>(bounded * 255.0f + 0.5f);
    };
    for (int y = 0; y < destinationHeight; ++y) {
        const int sourceY = std::min(y * scale, height - 1);
        const int64_t yRow = static_cast<int64_t>(sourceY) * width;
        const int64_t chromaRow = static_cast<int64_t>(sourceY / 2) * chromaWidth;
        const int64_t outputRow = static_cast<int64_t>(y) * destinationWidth;
        for (int x = 0; x < destinationWidth; ++x) {
            const int sourceX = std::min(x * scale, width - 1);
            const int yCode = std::min(1023, static_cast<int>(payload[yRow + sourceX] >> kP010Shift));
            const int64_t chromaIndex = ySamples +
                (chromaRow + static_cast<int64_t>(sourceX / 2)) * 2;
            const int cbCode = std::min(1023, static_cast<int>(payload[chromaIndex] >> kP010Shift));
            const int crCode = std::min(1023, static_cast<int>(payload[chromaIndex + 1] >> kP010Shift));
            const float linearY = lut.luma[yCode];
            const int red = channel8(linearY + lut.crToRed[crCode]);
            const int green = channel8(
                linearY - lut.cbToGreen[cbCode] - lut.crToGreen[crCode]
            );
            const int blue = channel8(linearY + lut.cbToBlue[cbCode]);
            output[outputRow + x] = static_cast<jint>(
                0xFF000000u | static_cast<uint32_t>(red << 16) |
                static_cast<uint32_t>(green << 8) | static_cast<uint32_t>(blue)
            );
        }
    }
    env->ReleaseIntArrayElements(outputArray, output, 0);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10PacketCodecEngine_nativePackP010TenBitPacket(
    JNIEnv* env,
    jclass,
    jobject sourceBuffer,
    jint sampleCount,
    jobject targetBuffer
) {
    uint8_t* source = nullptr;
    uint8_t* target = nullptr;
    jlong sourceCapacity = 0;
    jlong targetCapacity = 0;
    if (!directU8(sourceBuffer, env, &source, &sourceCapacity) ||
        !directU8(targetBuffer, env, &target, &targetCapacity) ||
        sampleCount < 0) {
        return JNI_FALSE;
    }
    const int64_t sourceBytes = static_cast<int64_t>(sampleCount) * 2;
    const int64_t targetBytes = (static_cast<int64_t>(sampleCount) * 10 + 7) / 8;
    if (sourceCapacity < sourceBytes || targetCapacity < targetBytes) return JNI_FALSE;

    return packContiguousP010TenBit(
        source,
        sampleCount,
        target,
        targetBytes,
        nullptr
    ) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10PacketCodecEngine_nativePackP010TenBitPacketWithCrc(
    JNIEnv* env,
    jclass,
    jobject sourceBuffer,
    jint sampleCount,
    jobject targetBuffer
) {
    uint8_t* source = nullptr;
    uint8_t* target = nullptr;
    jlong sourceCapacity = 0;
    jlong targetCapacity = 0;
    if (!directU8(sourceBuffer, env, &source, &sourceCapacity) ||
        !directU8(targetBuffer, env, &target, &targetCapacity) ||
        sampleCount < 0) {
        return -1;
    }
    const int64_t sourceBytes = static_cast<int64_t>(sampleCount) * 2;
    const int64_t targetBytes = (static_cast<int64_t>(sampleCount) * 10 + 7) / 8;
    if (sourceCapacity < sourceBytes || targetCapacity < targetBytes) return -1;

    uint32_t crc = 0u;
    if (!packContiguousP010TenBit(source, sampleCount, target, targetBytes, &crc)) return -1;
    return static_cast<jlong>(crc);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10PacketCodecEngine_nativeEncodeChunkedLosslessP010Packet(
    JNIEnv* env,
    jclass,
    jobject sourceBuffer,
    jint sampleCount,
    jobject targetBuffer
) {
    std::uint8_t* source = nullptr;
    std::uint8_t* target = nullptr;
    jlong sourceCapacity = 0;
    jlong targetCapacity = 0;
    if (!directU8(sourceBuffer, env, &source, &sourceCapacity) ||
        !directU8(targetBuffer, env, &target, &targetCapacity) ||
        sampleCount <= 0) {
        return -1;
    }
    const std::int64_t sourceBytes = static_cast<std::int64_t>(sampleCount) * 2;
    const std::int64_t packedBytes =
        (static_cast<std::int64_t>(sampleCount) * 10 + 7) / 8;
    const std::int64_t maximumBytes = neoproc::npv10::lossless::maximumStoredBytes(
        packedBytes,
        sampleCount
    );
    if (sourceCapacity < sourceBytes || maximumBytes <= 0 || targetCapacity < maximumBytes) {
        return -1;
    }

    std::uint32_t packedCrc = 0u;
    if (!packContiguousP010TenBit(
            source,
            sampleCount,
            target,
            packedBytes,
            &packedCrc
        )) {
        return -1;
    }
    const auto encoded = neoproc::npv10::lossless::compressPackedTenBitInPlace(
        target,
        packedBytes,
        sampleCount,
        targetCapacity,
        packedCrc
    );
    if (!encoded.successful) return -1;
    neoproc::npv10::DirectMappedPreparedPayload prepared{};
    prepared.storedBytes = encoded.storedBytes;
    prepared.storedCrc32 = encoded.storedCrc32;
    prepared.compressedChunks = encoded.compressedChunks;
    prepared.rawChunks = encoded.rawChunks;
    return preparedPayloadToken(prepared);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10PacketCodecEngine_nativeUnpackP010TenBitPacket(
    JNIEnv* env,
    jclass,
    jobject sourceBuffer,
    jint sampleCount,
    jobject targetBuffer
) {
    uint8_t* source = nullptr;
    uint8_t* target = nullptr;
    jlong sourceCapacity = 0;
    jlong targetCapacity = 0;
    if (!directU8(sourceBuffer, env, &source, &sourceCapacity) ||
        !directU8(targetBuffer, env, &target, &targetCapacity) ||
        sampleCount < 0) {
        return JNI_FALSE;
    }
    const int64_t sourceBytes = (static_cast<int64_t>(sampleCount) * 10 + 7) / 8;
    const int64_t targetBytes = static_cast<int64_t>(sampleCount) * 2;
    if (sourceCapacity < sourceBytes || targetCapacity < targetBytes) return JNI_FALSE;

    int sampleIndex = 0;
    int sourceOffset = 0;
    constexpr uint64_t kTenBitMask = 0x03FFULL;
    for (; sampleIndex + 4 <= sampleCount; sampleIndex += 4, sourceOffset += 5) {
        uint64_t packed = 0u;
        std::memcpy(&packed, source + sourceOffset, 5);
        const uint64_t words =
            ((packed & kTenBitMask) << 6u) |
            (((packed >> 10u) & kTenBitMask) << 22u) |
            (((packed >> 20u) & kTenBitMask) << 38u) |
            (((packed >> 30u) & kTenBitMask) << 54u);
        std::memcpy(target + static_cast<int64_t>(sampleIndex) * 2, &words, sizeof(words));
    }

    uint64_t accumulator = 0u;
    int bitCount = 0;
    for (; sampleIndex < sampleCount; ++sampleIndex) {
        while (bitCount < 10) {
            if (sourceOffset >= sourceBytes) return JNI_FALSE;
            accumulator |= static_cast<uint64_t>(source[sourceOffset++]) << bitCount;
            bitCount += 8;
        }
        const uint16_t word = static_cast<uint16_t>((accumulator & kTenBitMask) << kP010Shift);
        std::memcpy(target + static_cast<int64_t>(sampleIndex) * 2, &word, sizeof(word));
        accumulator >>= 10u;
        bitCount -= 10;
    }
    return sourceOffset == sourceBytes && accumulator == 0u ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10FramePacker_nativePackP010Neon(
    JNIEnv* env,
    jclass,
    jbyteArray yBytes,
    jbyteArray uBytes,
    jbyteArray vBytes,
    jint width,
    jint height,
    jint yRowStride,
    jint uRowStride,
    jint vRowStride,
    jint yPixelStride,
    jint uPixelStride,
    jint vPixelStride,
    jint bitDepth,
    jobject targetBuffer
) {
    uint16_t* target = nullptr;
    jlong capacityBytes = 0;
    if (!directU16(targetBuffer, env, &target, &capacityBytes)) return JNI_FALSE;
    if (yBytes == nullptr || uBytes == nullptr || vBytes == nullptr) return JNI_FALSE;
    if (width <= 0 || height <= 0) return JNI_FALSE;
    const int chromaWidth = (width + 1) / 2;
    const int chromaHeight = (height + 1) / 2;
    // int64 math: width*height in `int` overflows to a NEGATIVE expectedBytes for absurd
    // dimensions, which would pass `capacityBytes < expectedBytes` and let packP010Scalar
    // overrun `target`. Matches the already-safe sibling nativeCopyP010ImagePlanes().
    const int64_t expectedBytes = (static_cast<int64_t>(width) * height +
        static_cast<int64_t>(chromaWidth) * chromaHeight * 2) * 2;
    if (capacityBytes < expectedBytes) return JNI_FALSE;

    // Validate SOURCE plane lengths before GetByteArrayElements so packP010Scalar's strided
    // readSample() (reads plane[offset(,+1)]) cannot walk off the end of an undersized Java
    // array -> OOB read. readSample() yields a neutral fallback when a stride is <=0 and reads
    // NOTHING, so we only require bytes when both strides are positive (never false-reject a
    // valid fallback frame). sampleBytes tracks readSample's actual per-sample footprint.
    const int sampleBytes = (bitDepth >= 10) ? 2 : 1;
    auto planeNeedsBytes = [sampleBytes](int rows, int cols, int rowStride, int pixelStride) -> jlong {
        if (rows <= 0 || cols <= 0 || rowStride <= 0 || pixelStride <= 0) return 0;
        return static_cast<jlong>(rows - 1) * rowStride +
            static_cast<jlong>(cols - 1) * pixelStride + sampleBytes;
    };
    if (env->GetArrayLength(yBytes) < planeNeedsBytes(height, width, yRowStride, yPixelStride) ||
        env->GetArrayLength(uBytes) < planeNeedsBytes(chromaHeight, chromaWidth, uRowStride, uPixelStride) ||
        env->GetArrayLength(vBytes) < planeNeedsBytes(chromaHeight, chromaWidth, vRowStride, vPixelStride)) {
        return JNI_FALSE;
    }

    jboolean yCopy = JNI_FALSE;
    jboolean uCopy = JNI_FALSE;
    jboolean vCopy = JNI_FALSE;
    auto* y = reinterpret_cast<uint8_t*>(env->GetByteArrayElements(yBytes, &yCopy));
    auto* u = reinterpret_cast<uint8_t*>(env->GetByteArrayElements(uBytes, &uCopy));
    auto* v = reinterpret_cast<uint8_t*>(env->GetByteArrayElements(vBytes, &vCopy));
    if (y == nullptr || u == nullptr || v == nullptr) {
        if (y != nullptr) env->ReleaseByteArrayElements(yBytes, reinterpret_cast<jbyte*>(y), JNI_ABORT);
        if (u != nullptr) env->ReleaseByteArrayElements(uBytes, reinterpret_cast<jbyte*>(u), JNI_ABORT);
        if (v != nullptr) env->ReleaseByteArrayElements(vBytes, reinterpret_cast<jbyte*>(v), JNI_ABORT);
        return JNI_FALSE;
    }
    packP010Scalar(
        y, u, v, width, height,
        yRowStride, uRowStride, vRowStride,
        yPixelStride, uPixelStride, vPixelStride,
        bitDepth, target
    );
    env->ReleaseByteArrayElements(yBytes, reinterpret_cast<jbyte*>(y), JNI_ABORT);
    env->ReleaseByteArrayElements(uBytes, reinterpret_cast<jbyte*>(u), JNI_ABORT);
    env->ReleaseByteArrayElements(vBytes, reinterpret_cast<jbyte*>(v), JNI_ABORT);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10FramePacker_nativeCopyP010ImagePlanes(
    JNIEnv* env,
    jclass,
    jobject yBuffer,
    jobject uvBuffer,
    jint yBufferOffset,
    jint uvBufferOffset,
    jint width,
    jint height,
    jint yRowStride,
    jint uvRowStride,
    jint yPixelStride,
    jint uvPixelStride,
    jobject targetBuffer
) {
    uint8_t* y = nullptr;
    uint8_t* uv = nullptr;
    uint8_t* target = nullptr;
    jlong yCapacity = 0;
    jlong uvCapacity = 0;
    jlong targetCapacity = 0;
    if (!directU8(yBuffer, env, &y, &yCapacity) ||
        !directU8(uvBuffer, env, &uv, &uvCapacity) ||
        !directU8(targetBuffer, env, &target, &targetCapacity)) {
        return JNI_FALSE;
    }
    if (yBufferOffset < 0 || uvBufferOffset < 0 ||
        static_cast<jlong>(yBufferOffset) > yCapacity ||
        static_cast<jlong>(uvBufferOffset) > uvCapacity) {
        return JNI_FALSE;
    }
    y += yBufferOffset;
    uv += uvBufferOffset;
    yCapacity -= yBufferOffset;
    uvCapacity -= uvBufferOffset;
    if (width <= 0 || height <= 0 ||
        yRowStride <= 0 || uvRowStride <= 0 ||
        yPixelStride < 2 || uvPixelStride < 4) {
        return JNI_FALSE;
    }
    const int chromaWidth = (width + 1) / 2;
    const int chromaHeight = (height + 1) / 2;
    const int yPayloadRowBytes = width * 2;
    const int uvPayloadRowBytes = chromaWidth * 4;
    const int64_t yPayloadBytes = static_cast<int64_t>(yPayloadRowBytes) * height;
    const int64_t expectedBytes = yPayloadBytes +
        static_cast<int64_t>(uvPayloadRowBytes) * chromaHeight;
    if (targetCapacity < expectedBytes) return JNI_FALSE;
    const int64_t requiredYBytes = static_cast<int64_t>(height - 1) * yRowStride +
        static_cast<int64_t>(width - 1) * yPixelStride + 2;
    const int64_t requiredUvBytes = static_cast<int64_t>(chromaHeight - 1) * uvRowStride +
        static_cast<int64_t>(chromaWidth - 1) * uvPixelStride + 4;
    if (yCapacity < requiredYBytes || uvCapacity < requiredUvBytes) return JNI_FALSE;

    if (yPixelStride == 2 && yRowStride == yPayloadRowBytes) {
        std::memcpy(target, y, static_cast<size_t>(yPayloadBytes));
    } else {
        for (int row = 0; row < height; ++row) {
            const uint8_t* src = y + static_cast<int64_t>(row) * yRowStride;
            uint8_t* dst = target + static_cast<int64_t>(row) * yPayloadRowBytes;
            if (yPixelStride == 2) {
                std::memcpy(dst, src, yPayloadRowBytes);
                continue;
            }
            for (int column = 0; column < width; ++column) {
                const uint8_t* sample = src + static_cast<int64_t>(column) * yPixelStride;
                dst[column * 2] = sample[0];
                dst[column * 2 + 1] = sample[1];
            }
        }
    }

    uint8_t* uvTarget = target + yPayloadBytes;
    if (uvPixelStride == 4 && uvRowStride == uvPayloadRowBytes) {
        std::memcpy(
            uvTarget,
            uv,
            static_cast<size_t>(uvPayloadRowBytes) * chromaHeight
        );
    } else {
        for (int row = 0; row < chromaHeight; ++row) {
            const uint8_t* src = uv + static_cast<int64_t>(row) * uvRowStride;
            uint8_t* dst = uvTarget + static_cast<int64_t>(row) * uvPayloadRowBytes;
            if (uvPixelStride == 4) {
                std::memcpy(dst, src, uvPayloadRowBytes);
                continue;
            }
            for (int column = 0; column < chromaWidth; ++column) {
                const uint8_t* sample = src + static_cast<int64_t>(column) * uvPixelStride;
                const int out = column * 4;
                dst[out] = sample[0];
                dst[out + 1] = sample[1];
                dst[out + 2] = sample[2];
                dst[out + 3] = sample[3];
            }
        }
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10FramePacker_nativePackP010ImagePlanesToPackedTenBit(
    JNIEnv* env,
    jclass,
    jobject yBuffer,
    jobject uvBuffer,
    jint width,
    jint height,
    jint yRowStride,
    jint uvRowStride,
    jint yPixelStride,
    jint uvPixelStride,
    jobject targetBuffer
) {
    uint8_t* y = nullptr;
    uint8_t* uv = nullptr;
    uint8_t* target = nullptr;
    jlong yCapacity = 0;
    jlong uvCapacity = 0;
    jlong targetCapacity = 0;
    if (!directU8(yBuffer, env, &y, &yCapacity) ||
        !directU8(uvBuffer, env, &uv, &uvCapacity) ||
        !directU8(targetBuffer, env, &target, &targetCapacity)) {
        return JNI_FALSE;
    }
    if (width <= 0 || height <= 0 ||
        yRowStride <= 0 || uvRowStride <= 0 ||
        yPixelStride < 2 || uvPixelStride < 4) {
        return JNI_FALSE;
    }

    const int64_t chromaWidth = (static_cast<int64_t>(width) + 1) / 2;
    const int64_t chromaHeight = (static_cast<int64_t>(height) + 1) / 2;
    const int64_t ySamples = static_cast<int64_t>(width) * height;
    const int64_t sampleCount = ySamples + chromaWidth * chromaHeight * 2;
    if (sampleCount <= 0 || sampleCount > (std::numeric_limits<int64_t>::max() - 7) / 10) {
        return JNI_FALSE;
    }
    const int64_t targetBytes = (sampleCount * 10 + 7) / 8;
    const int64_t requiredYBytes = static_cast<int64_t>(height - 1) * yRowStride +
        static_cast<int64_t>(width - 1) * yPixelStride + 2;
    const int64_t requiredUvBytes = (chromaHeight - 1) * uvRowStride +
        (chromaWidth - 1) * uvPixelStride + 4;
    if (requiredYBytes <= 0 || requiredUvBytes <= 0 ||
        yCapacity < requiredYBytes || uvCapacity < requiredUvBytes ||
        targetCapacity < targetBytes) {
        return JNI_FALSE;
    }

    if (packStandardP010PlanesToTenBit(
            y,
            yCapacity,
            uv,
            uvCapacity,
            width,
            height,
            yRowStride,
            uvRowStride,
            yPixelStride,
            uvPixelStride,
            target,
            targetCapacity,
            nullptr
        )) {
        return JNI_TRUE;
    }

    constexpr uint64_t kTenBitMask = 0x03FFULL;
    constexpr uint64_t kFourWordPaddingMask = 0x003F003F003F003FULL;
    int64_t outputOffset = 0;
    uint64_t accumulator = 0u;
    int bitCount = 0;

    auto appendWord = [&](const uint8_t* source) -> bool {
        uint16_t word = 0u;
        std::memcpy(&word, source, sizeof(word));
        if ((word & 0x003Fu) != 0u) return false;
        accumulator |= static_cast<uint64_t>(word >> kP010Shift) << bitCount;
        bitCount += 10;
        while (bitCount >= 8) {
            if (outputOffset >= targetBytes) return false;
            target[outputOffset++] = static_cast<uint8_t>(accumulator & 0xFFu);
            accumulator >>= 8u;
            bitCount -= 8;
        }
        return true;
    };

    auto appendFourContiguousWords = [&](const uint8_t* source) -> bool {
        if (bitCount != 0) {
            return appendWord(source) && appendWord(source + 2) &&
                appendWord(source + 4) && appendWord(source + 6);
        }
        uint64_t words = 0u;
        std::memcpy(&words, source, sizeof(words));
        if ((words & kFourWordPaddingMask) != 0u || outputOffset + 5 > targetBytes) {
            return false;
        }
        const uint64_t packed =
            ((words >> 6u) & kTenBitMask) |
            (((words >> 22u) & kTenBitMask) << 10u) |
            (((words >> 38u) & kTenBitMask) << 20u) |
            (((words >> 54u) & kTenBitMask) << 30u);
        const uint32_t packedLow = static_cast<uint32_t>(packed);
        std::memcpy(target + outputOffset, &packedLow, sizeof(packedLow));
        target[outputOffset + 4] = static_cast<uint8_t>(packed >> 32u);
        outputOffset += 5;
        return true;
    };

    for (int row = 0; row < height; ++row) {
        const uint8_t* sourceRow = y + static_cast<int64_t>(row) * yRowStride;
        int column = 0;
        if (yPixelStride == 2) {
            for (; column + 4 <= width; column += 4) {
                if (!appendFourContiguousWords(sourceRow + static_cast<int64_t>(column) * 2)) {
                    return JNI_FALSE;
                }
            }
        }
        for (; column < width; ++column) {
            if (!appendWord(sourceRow + static_cast<int64_t>(column) * yPixelStride)) {
                return JNI_FALSE;
            }
        }
    }

    for (int64_t row = 0; row < chromaHeight; ++row) {
        const uint8_t* sourceRow = uv + row * uvRowStride;
        int64_t column = 0;
        if (uvPixelStride == 4) {
            // Two interleaved UV groups are exactly four consecutive P010 words.
            for (; column + 2 <= chromaWidth; column += 2) {
                if (!appendFourContiguousWords(sourceRow + column * 4)) return JNI_FALSE;
            }
        }
        for (; column < chromaWidth; ++column) {
            const uint8_t* pair = sourceRow + column * uvPixelStride;
            if (!appendWord(pair) || !appendWord(pair + 2)) return JNI_FALSE;
        }
    }

    if (bitCount > 0) {
        if (outputOffset >= targetBytes) return JNI_FALSE;
        target[outputOffset++] = static_cast<uint8_t>(accumulator & 0xFFu);
        accumulator >>= bitCount;
        bitCount = 0;
    }
    return outputOffset == targetBytes && accumulator == 0u ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10FramePacker_nativePackP010ImagePlanesToPackedTenBitWithCrc(
    JNIEnv* env,
    jclass,
    jobject yBuffer,
    jobject uvBuffer,
    jint width,
    jint height,
    jint yRowStride,
    jint uvRowStride,
    jint yPixelStride,
    jint uvPixelStride,
    jobject targetBuffer
) {
    uint8_t* y = nullptr;
    uint8_t* uv = nullptr;
    uint8_t* target = nullptr;
    jlong yCapacity = 0;
    jlong uvCapacity = 0;
    jlong targetCapacity = 0;
    if (!directU8(yBuffer, env, &y, &yCapacity) ||
        !directU8(uvBuffer, env, &uv, &uvCapacity) ||
        !directU8(targetBuffer, env, &target, &targetCapacity)) {
        return -1;
    }
    uint32_t crc = 0u;
    if (!packStandardP010PlanesToTenBit(
            y,
            yCapacity,
            uv,
            uvCapacity,
            width,
            height,
            yRowStride,
            uvRowStride,
            yPixelStride,
            uvPixelStride,
            target,
            targetCapacity,
            &crc
        )) {
        return -1;
    }
    return static_cast<jlong>(crc);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10FramePacker_nativePrepareP010LosslessChunkExecutor(
    JNIEnv*, jclass
) {
    std::atomic<bool> successful{true};
    const bool joined = niv99P010RowExecutor().executeLosslessChunks(&successful,
        [](void* state) noexcept {
            if (!neoproc::npv10::lossless::prepareP010LosslessChunkWorkspace()) {
                static_cast<std::atomic<bool>*>(state)->store(false, std::memory_order_relaxed);
            }
        });
    return joined && successful.load(std::memory_order_relaxed) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10FramePacker_nativePrepareP010PackingExecutor(
    JNIEnv*,
    jclass
) {
    return niv99P010RowExecutor().prepare() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10FramePacker_nativePrepareNiv99P010Processor(
    JNIEnv*,
    jclass
) {
    const auto& tables = niv99TransferLookupTables();
    return std::isfinite(tables.decoded[kNiv99TransferBt709][0]) &&
        std::isfinite(tables.encoded[kNiv99TransferPq][kNiv99TransferLutSize]) &&
        niv99P010RowExecutor().prepare()
        ? JNI_TRUE
        : JNI_FALSE;
}

// Qualification entry points deliberately stay outside the live ImageReader ownership boundary.
// They receive caller-owned samples; the live JNI path below retains its strict last-read/release
// ordering, including when a qualification run and a camera session share this native library.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10GpuLosslessEncoder_nativePrepare(
    JNIEnv*, jclass, jint width, jint height
) {
    return niv99P010RowExecutor().prepare() &&
        neoproc::npv10::lossless::prepareGpuRangeEncoder(width, height) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10GpuLosslessEncoder_nativeClose(JNIEnv*, jclass) {
    neoproc::npv10::lossless::closeGpuRangeEncoder();
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10GpuLosslessEncoder_nativeEncode(
    JNIEnv* env, jclass, jobject yBuffer, jobject uvBuffer, jint width, jint height,
    jint yRowStride, jint uvRowStride, jobject outputBuffer, jboolean useGpu
) {
    namespace codec = neoproc::npv10::lossless;
    std::uint8_t* y = nullptr; std::uint8_t* uv = nullptr; std::uint8_t* output = nullptr;
    jlong yCapacity = 0, uvCapacity = 0, outputCapacity = 0;
    if (!directU8(yBuffer, env, &y, &yCapacity) || !directU8(uvBuffer, env, &uv, &uvCapacity) ||
            !directU8(outputBuffer, env, &output, &outputCapacity)) return nullptr;
    const codec::GpuP010Input input{y, uv, yCapacity, uvCapacity, width, height, yRowStride, uvRowStride};
    codec::GpuRangeTimings timings{};
    const auto started = std::chrono::steady_clock::now();
    const auto result = useGpu == JNI_TRUE
        ? codec::encodeP010WithGpuRange(input, output, outputCapacity, 1024,
            [](const codec::GpuP010Input& original, std::uint8_t* target, std::int64_t capacity,
               std::uint32_t* crc) {
                return packStandardP010PlanesToTenBit(original.y, original.yCapacity,
                    original.uv, original.uvCapacity, original.width, original.height,
                    original.yRowStride, original.uvRowStride, 2, 4, target, capacity, crc);
            }, &timings)
        : codec::compressP010PlanesRealtimeRangeLz4(y, yCapacity, uv, uvCapacity, width, height,
            yRowStride, uvRowStride, output, outputCapacity, 1024);
    if (!result.successful) return nullptr;
    const auto total = std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now() - started).count();
    const jlong values[] = {result.storedBytes, result.storedCrc32, result.packedCrc32,
        result.compressedChunks, result.rawChunks, timings.uploadNs, timings.originalPackAndCrcNs,
        timings.completionWaitNs, timings.assemblyNs, total};
    auto encoded = env->NewLongArray(static_cast<jsize>(std::size(values)));
    if (encoded != nullptr) env->SetLongArrayRegion(encoded, 0, static_cast<jsize>(std::size(values)), values);
    return encoded;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10GpuLosslessEncoder_nativeEncodeParallel(
    JNIEnv* env, jclass, jobject yBuffer, jobject uvBuffer, jint width, jint height,
    jint yRowStride, jint uvRowStride, jobject outputBuffer
) {
    std::uint8_t* y = nullptr; std::uint8_t* uv = nullptr; std::uint8_t* output = nullptr;
    jlong yCapacity = 0, uvCapacity = 0, outputCapacity = 0;
    if (!directU8(yBuffer, env, &y, &yCapacity) || !directU8(uvBuffer, env, &uv, &uvCapacity) ||
        !directU8(outputBuffer, env, &output, &outputCapacity)) return nullptr;
    const auto started = std::chrono::steady_clock::now();
    const auto result = neoproc::npv10::lossless::compressP010PlanesParallelRangeLz4(
        y, yCapacity, uv, uvCapacity, width, height, yRowStride, uvRowStride, output, outputCapacity, 1024,
        [](void* job, void (*processor)(void*) noexcept) noexcept {
            return niv99P010RowExecutor().executeLosslessChunks(job, processor);
        });
    if (!result.successful) return nullptr;
    const auto total = std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now() - started).count();
    const jlong values[] = {result.storedBytes, result.storedCrc32, result.packedCrc32,
        result.compressedChunks, result.rawChunks, 0, 0, 0, 0, total};
    auto encoded = env->NewLongArray(static_cast<jsize>(std::size(values)));
    if (encoded != nullptr) env->SetLongArrayRegion(encoded, 0, static_cast<jsize>(std::size(values)), values);
    return encoded;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10FramePacker_nativePackP010ImagePlanesToDirectMappedTenBitWithCrc(
    JNIEnv* env,
    jclass,
    jobject yBuffer,
    jobject uvBuffer,
    jint yBufferOffset,
    jint uvBufferOffset,
    jint width,
    jint height,
    jint yRowStride,
    jint uvRowStride,
    jint yPixelStride,
    jint uvPixelStride,
    jlong directMappedCommitHandle,
    jlong frameIndex,
    jobject onPlanesCopied
) {
    ScopedNpv10TraceSection trace("NPV10 DMC packed P010");
    // Resolve the callback before acquiring native processing ownership. A lookup exception
    // must not leave an active slot that close()/abort() can never drain.
    jmethodID releaseSource = nullptr;
    if (onPlanesCopied != nullptr) {
        jclass callbackClass = env->GetObjectClass(onPlanesCopied);
        if (callbackClass == nullptr) return -1;
        releaseSource = env->GetMethodID(callbackClass, "run", "()V");
        env->DeleteLocalRef(callbackClass);
        if (releaseSource == nullptr) return -1;
    }
    uint8_t* y = nullptr;
    uint8_t* uv = nullptr;
    jlong yCapacity = 0;
    jlong uvCapacity = 0;
    if (!directU8(yBuffer, env, &y, &yCapacity) ||
        !directU8(uvBuffer, env, &uv, &uvCapacity) ||
        yBufferOffset < 0 || uvBufferOffset < 0 ||
        static_cast<jlong>(yBufferOffset) > yCapacity ||
        static_cast<jlong>(uvBufferOffset) > uvCapacity ||
        width <= 0 || height <= 0 || (width & 3) != 0 || (height & 1) != 0) {
        return -1;
    }
    const int64_t packedRowBytes = static_cast<int64_t>(width) * 10 / 8;
    const int64_t packedBytes = packedRowBytes * (static_cast<int64_t>(height) + height / 2);
    if (packedRowBytes <= 0 || packedBytes <= 0) return -1;

    // Admit the measured 12MP AArch64 path only. Other ABIs/layouts and processed
    // NIV99 sources retain their original pixel/ownership paths. The encoder's
    // one-chunk workspace replaces a full-frame pack->unpack round trip; it does
    // not add a frame lease or enlarge the pre-faulted capture ring.
#if defined(__aarch64__)
    const bool requestFusedLosslessPreparation = width == 4096 && height == 3072 &&
        yPixelStride == 2 && uvPixelStride == 4;
#else
    const bool requestFusedLosslessPreparation = false;
#endif
    neoproc::npv10::DirectMappedPayloadView target{};
    if (!neoproc::npv10::acquireDirectMappedPayload(
            directMappedCommitHandle,
            frameIndex,
            packedBytes,
            &target,
            requestFusedLosslessPreparation
        )) {
        return -1;
    }
    y += yBufferOffset;
    uv += uvBufferOffset;
    yCapacity -= yBufferOffset;
    uvCapacity -= uvBufferOffset;
    uint32_t crc = 0u;
    neoproc::npv10::lossless::EncodeResult fusedEncoded{};
    const auto preparationStarted = std::chrono::steady_clock::now();
    if (target.fusedLosslessPreparation) {
        ScopedNpv10TraceSection fusedTrace("NPV10 fused P010 lossless preparation");
        fusedEncoded = neoproc::npv10::lossless::compressP010PlanesParallelRangeLz4(
            y, yCapacity, uv, uvCapacity, width, height, yRowStride, uvRowStride,
            target.data, target.capacity, target.losslessAcceleration,
            [](void* job, void (*processor)(void*) noexcept) noexcept {
                return niv99P010RowExecutor().executeLosslessChunks(job, processor);
            });
        crc = fusedEncoded.packedCrc32;
    }
    const bool packed = target.fusedLosslessPreparation ? fusedEncoded.successful : packStandardP010PlanesToTenBit(
        y,
        yCapacity,
        uv,
        uvCapacity,
        width,
        height,
        yRowStride,
        uvRowStride,
        yPixelStride,
        uvPixelStride,
        target.data,
        target.capacity,
        &crc
    );
    const auto preparationElapsedNs = std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now() - preparationStarted).count();
    // The bounded private DMC slot now owns every original ten-bit sample and its CRC,
    // either already in its exact lossless packet or in the original packed form.
    // No code below this point reads y/uv or their ByteBuffers. Return the scarce HAL
    // ImageReader lease BEFORE publication (and before legacy independent compression).
    // Fused preparation consumes the still-owned image; no post-release plane reads.
    bool sourceReleaseSucceeded = true;
    if (packed && releaseSource != nullptr) {
        ScopedNpv10TraceSection releaseTrace("NPV10 release camera image before publication");
        env->CallVoidMethod(onPlanesCopied, releaseSource);
        sourceReleaseSucceeded = !env->ExceptionCheck();
    }
    neoproc::npv10::DirectMappedPreparedPayload prepared{};
    const bool finished = target.fusedLosslessPreparation
        ? neoproc::npv10::finishDirectMappedEncodedPayloadProcessing(
            directMappedCommitHandle, frameIndex, packed && sourceReleaseSucceeded,
            fusedEncoded, preparationElapsedNs, &prepared)
        : neoproc::npv10::finishDirectMappedPayloadProcessing(
        directMappedCommitHandle,
        frameIndex,
        packed && sourceReleaseSucceeded,
        crc,
        &prepared
    );
    return packed && sourceReleaseSucceeded && finished ? preparedPayloadToken(prepared) : -1;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10FramePacker_nativeProcessNiv99P010ImagePlanesToPackedTenBitWithCrc(
    JNIEnv* env,
    jclass,
    jobject yBuffer,
    jobject uvBuffer,
    jint yBufferOffset,
    jint uvBufferOffset,
    jint width,
    jint height,
    jint yRowStride,
    jint uvRowStride,
    jint yPixelStride,
    jint uvPixelStride,
    jobject targetBuffer,
    jfloat redCorrection,
    jfloat greenCorrection,
    jfloat blueCorrection,
    jfloat spatialDenoise,
    jfloat chromaDenoise,
    jfloat sharpeningCeiling,
    jfloat confidence,
    jint matrixCode,
    jint transferCode
) {
    uint8_t* y = nullptr;
    uint8_t* uv = nullptr;
    uint8_t* target = nullptr;
    jlong yCapacity = 0;
    jlong uvCapacity = 0;
    jlong targetCapacity = 0;
    if (!directU8(yBuffer, env, &y, &yCapacity) ||
        !directU8(uvBuffer, env, &uv, &uvCapacity) ||
        !directU8(targetBuffer, env, &target, &targetCapacity)) {
        return -1;
    }
    if (yBufferOffset < 0 || uvBufferOffset < 0 ||
        static_cast<jlong>(yBufferOffset) > yCapacity ||
        static_cast<jlong>(uvBufferOffset) > uvCapacity) {
        return -1;
    }
    y += yBufferOffset;
    uv += uvBufferOffset;
    yCapacity -= yBufferOffset;
    uvCapacity -= uvBufferOffset;
    Niv99P010Parameters parameters{};
    if (!resolveNiv99P010Parameters(
            redCorrection,
            greenCorrection,
            blueCorrection,
            spatialDenoise,
            chromaDenoise,
            sharpeningCeiling,
            confidence,
            matrixCode,
            transferCode,
            &parameters
        )) {
        return -1;
    }
    uint32_t crc = 0u;
    if (!processNiv99P010PlanesToTenBit(
            y,
            yCapacity,
            uv,
            uvCapacity,
            width,
            height,
            yRowStride,
            uvRowStride,
            yPixelStride,
            uvPixelStride,
            target,
            targetCapacity,
            parameters,
            true,
            &crc
        )) {
        return -1;
    }
    return static_cast<jlong>(crc);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10FramePacker_nativeProcessNiv99CanonicalP010ToP010(
    JNIEnv* env,
    jclass,
    jobject currentBuffer,
    jobject previousBuffer,
    jobject outputBuffer,
    jint currentOffset,
    jint previousOffset,
    jint width,
    jint height,
    jfloat redCorrection,
    jfloat greenCorrection,
    jfloat blueCorrection,
    jfloat saturation,
    jfloat spatialDenoise,
    jfloat chromaDenoise,
    jfloat sharpeningCeiling,
    jfloat temporalLumaStrength,
    jfloat temporalChromaStrength,
    jfloat shotNoiseCoefficient,
    jfloat readNoiseCoefficient,
    jfloat confidence,
    jint matrixCode,
    jint transferCode
) {
    ScopedNpv10TraceSection trace("NIV99 conversion P010");
    uint8_t* current = nullptr;
    uint8_t* previous = nullptr;
    uint8_t* output = nullptr;
    jlong currentCapacity = 0;
    jlong previousCapacity = 0;
    jlong outputCapacity = 0;
    if (!directU8(currentBuffer, env, &current, &currentCapacity) ||
        !directU8(outputBuffer, env, &output, &outputCapacity) ||
        currentOffset < 0 || previousOffset < 0 || width <= 0 || height <= 0 ||
        (width & 3) != 0 || (height & 1) != 0) {
        return -1;
    }
    const bool hasPrevious = previousBuffer != nullptr;
    if (hasPrevious && !directU8(previousBuffer, env, &previous, &previousCapacity)) return -1;
    const int64_t yPlaneBytes = static_cast<int64_t>(width) * height * sizeof(uint16_t);
    const int64_t frameBytes = yPlaneBytes + yPlaneBytes / 2;
    if (yPlaneBytes <= 0 || frameBytes <= yPlaneBytes ||
        static_cast<int64_t>(currentOffset) + frameBytes > currentCapacity ||
        outputCapacity < frameBytes || output == current ||
        (hasPrevious && (
            static_cast<int64_t>(previousOffset) + frameBytes > previousCapacity ||
            output == previous
        ))) {
        return -1;
    }

    current += currentOffset;
    currentCapacity -= currentOffset;
    if (hasPrevious) {
        previous += previousOffset;
        previousCapacity -= previousOffset;
    }
    Niv99P010Parameters parameters{};
    if (!resolveNiv99P010Parameters(
            redCorrection,
            greenCorrection,
            blueCorrection,
            spatialDenoise,
            chromaDenoise,
            sharpeningCeiling,
            confidence,
            matrixCode,
            transferCode,
            &parameters,
            saturation,
            temporalLumaStrength,
            temporalChromaStrength,
            shotNoiseCoefficient,
            readNoiseCoefficient
        )) {
        return -1;
    }

    bool temporalUsed = false;
    const bool processed = processNiv99P010PlanesToTenBit(
        current,
        currentCapacity,
        current + yPlaneBytes,
        currentCapacity - yPlaneBytes,
        width,
        height,
        width * static_cast<int>(sizeof(uint16_t)),
        width * static_cast<int>(sizeof(uint16_t)),
        static_cast<int>(sizeof(uint16_t)),
        static_cast<int>(sizeof(uint16_t) * 2),
        output,
        outputCapacity,
        parameters,
        true,
        nullptr,
        true,
        hasPrevious ? previous : nullptr,
        hasPrevious ? previousCapacity : 0,
        hasPrevious ? previous + yPlaneBytes : nullptr,
        hasPrevious ? previousCapacity - yPlaneBytes : 0,
        width * static_cast<int>(sizeof(uint16_t)),
        width * static_cast<int>(sizeof(uint16_t)),
        static_cast<int>(sizeof(uint16_t)),
        static_cast<int>(sizeof(uint16_t) * 2),
        &temporalUsed
    );
    if (!processed) return -1;
    constexpr uint64_t kTemporalUsedFlag = uint64_t{1} << 32u;
    return static_cast<jlong>(temporalUsed ? kTemporalUsedFlag : 0u);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10FramePacker_nativeProcessNiv99P010ImagePlanesToDirectMappedTenBitWithCrc(
    JNIEnv* env,
    jclass,
    jobject yBuffer,
    jobject uvBuffer,
    jint yBufferOffset,
    jint uvBufferOffset,
    jint width,
    jint height,
    jint yRowStride,
    jint uvRowStride,
    jint yPixelStride,
    jint uvPixelStride,
    jlong directMappedCommitHandle,
    jlong frameIndex,
    jfloat redCorrection,
    jfloat greenCorrection,
    jfloat blueCorrection,
    jfloat spatialDenoise,
    jfloat chromaDenoise,
    jfloat sharpeningCeiling,
    jfloat confidence,
    jint matrixCode,
    jint transferCode
) {
    uint8_t* y = nullptr;
    uint8_t* uv = nullptr;
    jlong yCapacity = 0;
    jlong uvCapacity = 0;
    if (!directU8(yBuffer, env, &y, &yCapacity) ||
        !directU8(uvBuffer, env, &uv, &uvCapacity) ||
        yBufferOffset < 0 || uvBufferOffset < 0 ||
        static_cast<jlong>(yBufferOffset) > yCapacity ||
        static_cast<jlong>(uvBufferOffset) > uvCapacity ||
        width <= 0 || height <= 0 || (width & 3) != 0 || (height & 1) != 0) {
        return -1;
    }
    Niv99P010Parameters parameters{};
    if (!resolveNiv99P010Parameters(
            redCorrection,
            greenCorrection,
            blueCorrection,
            spatialDenoise,
            chromaDenoise,
            sharpeningCeiling,
            confidence,
            matrixCode,
            transferCode,
            &parameters
        )) {
        return -1;
    }
    const int64_t packedRowBytes = static_cast<int64_t>(width) * 10 / 8;
    const int64_t packedBytes = packedRowBytes * (static_cast<int64_t>(height) + height / 2);
    if (packedRowBytes <= 0 || packedBytes <= 0) return -1;

    neoproc::npv10::DirectMappedPayloadView target{};
    if (!neoproc::npv10::acquireDirectMappedPayload(
            directMappedCommitHandle,
            frameIndex,
            packedBytes,
            &target
        )) {
        return -1;
    }
    y += yBufferOffset;
    uv += uvBufferOffset;
    yCapacity -= yBufferOffset;
    uvCapacity -= uvBufferOffset;
    uint32_t crc = 0u;
    const bool processed = processNiv99P010PlanesToTenBit(
        y,
        yCapacity,
        uv,
        uvCapacity,
        width,
        height,
        yRowStride,
        uvRowStride,
        yPixelStride,
        uvPixelStride,
        target.data,
        target.capacity,
        parameters,
        true,
        &crc
    );
    neoproc::npv10::DirectMappedPreparedPayload prepared{};
    const bool finished = neoproc::npv10::finishDirectMappedPayloadProcessing(
        directMappedCommitHandle,
        frameIndex,
        processed,
        crc,
        &prepared
    );
    return processed && finished ? preparedPayloadToken(prepared) : -1;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10FramePacker_nativeVerifyNiv99P010BlockReuseParity(
    JNIEnv*,
    jclass
) {
    return static_cast<jlong>(verifyNiv99P010BlockReuseParity());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10FramePacker_nativeExerciseNiv99P010ProcessorLifecycle(
    JNIEnv*,
    jclass,
    jint cycles
) {
    Niv99P010RowExecutor& executor = niv99P010RowExecutor();
    if (!executor.exerciseLifecycleForValidation(cycles)) return -1;
    return static_cast<jint>(executor.liveHelperCount());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10FramePacker_nativePackRaw12Npv10(
    JNIEnv* env,
    jclass,
    jobject sourceDirectU16,
    jobject targetDirectU16,
    jint sampleCount
) {
    uint16_t* source = nullptr;
    uint16_t* target = nullptr;
    jlong sourceBytes = 0;
    jlong targetBytes = 0;
    if (!directU16(sourceDirectU16, env, &source, &sourceBytes) ||
        !directU16(targetDirectU16, env, &target, &targetBytes)) {
        return JNI_FALSE;
    }
    if (sourceBytes < sampleCount * 2LL || targetBytes < sampleCount * 2LL) return JNI_FALSE;
    packRaw12Native(source, target, sampleCount);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10FramePacker_nativeUnpackRaw12Npv10(
    JNIEnv* env,
    jclass,
    jobject sourceDirectU16,
    jobject targetDirectU16,
    jint sampleCount
) {
    uint16_t* source = nullptr;
    uint16_t* target = nullptr;
    jlong sourceBytes = 0;
    jlong targetBytes = 0;
    if (!directU16(sourceDirectU16, env, &source, &sourceBytes) ||
        !directU16(targetDirectU16, env, &target, &targetBytes)) {
        return JNI_FALSE;
    }
    if (sourceBytes < sampleCount * 2LL || targetBytes < sampleCount * 2LL) return JNI_FALSE;
    unpackRaw12Native(source, target, sampleCount);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10FramePacker_nativePackRaw14Npv10(
    JNIEnv* env,
    jclass,
    jobject sourceDirectU16,
    jobject targetDirectU16,
    jint sampleCount,
    jint frameParitySeed
) {
    uint16_t* source = nullptr;
    uint16_t* target = nullptr;
    jlong sourceBytes = 0;
    jlong targetBytes = 0;
    if (!directU16(sourceDirectU16, env, &source, &sourceBytes) ||
        !directU16(targetDirectU16, env, &target, &targetBytes)) {
        return JNI_FALSE;
    }
    if (sourceBytes < sampleCount * 2LL || targetBytes < sampleCount * 2LL) return JNI_FALSE;
    packRaw14Native(source, target, sampleCount, frameParitySeed);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10FramePacker_nativeUnpackRaw14Npv10(
    JNIEnv* env,
    jclass,
    jobject sourceDirectU16,
    jobject targetDirectU16,
    jint sampleCount
) {
    uint16_t* source = nullptr;
    uint16_t* target = nullptr;
    jlong sourceBytes = 0;
    jlong targetBytes = 0;
    if (!directU16(sourceDirectU16, env, &source, &sourceBytes) ||
        !directU16(targetDirectU16, env, &target, &targetBytes)) {
        return JNI_FALSE;
    }
    if (sourceBytes < sampleCount * 2LL || targetBytes < sampleCount * 2LL) return JNI_FALSE;
    unpackRaw14Native(source, target, sampleCount);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10RecordingManager_nativeRawAcquisitionCapabilitySummary(
    JNIEnv* env,
    jobject,
    jobject hardwareBufferObject,
    jlong vulkanDeviceHandle
) {
    AHardwareBuffer* hardwareBuffer = AHardwareBuffer_fromHardwareBuffer(env, hardwareBufferObject);
    if (hardwareBuffer == nullptr) {
        return env->NewStringUTF("npv10RawNative=unavailable hardwareBuffer=null");
    }
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(hardwareBuffer, &desc);

    // Contract markers for the zero-copy Vulkan import path. The Java layer
    // keeps this capability closed unless the full device path validates.
    constexpr const char* extensionName = "VK_ANDROID_external_memory_android_hardware_buffer";
    VkSemaphoreTypeCreateInfo timelineInfo{};
    timelineInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO;
    timelineInfo.semaphoreType = VK_SEMAPHORE_TYPE_TIMELINE;
    timelineInfo.initialValue = 0;
    auto vkGetSemaphoreFdKHRPtr = reinterpret_cast<PFN_vkGetSemaphoreFdKHR>(
        vkGetDeviceProcAddr(reinterpret_cast<VkDevice>(vulkanDeviceHandle), "vkGetSemaphoreFdKHR")
    );
    const bool timelineReady = timelineInfo.semaphoreType == VK_SEMAPHORE_TYPE_TIMELINE;
    const bool fdExportReady = vkGetSemaphoreFdKHRPtr != nullptr || vulkanDeviceHandle == 0;
    char summary[256];
    std::snprintf(
        summary,
        sizeof(summary),
        "npv10RawNative=ready externalMemory=%s ahb=%ux%u format=%u timeline=%s semaphoreFdExport=%s",
        extensionName,
        desc.width,
        desc.height,
        desc.format,
        timelineReady ? "VK_SEMAPHORE_TYPE_TIMELINE" : "missing",
        fdExportReady ? "vkGetSemaphoreFdKHR" : "missing"
    );
    return env->NewStringUTF(summary);
}
