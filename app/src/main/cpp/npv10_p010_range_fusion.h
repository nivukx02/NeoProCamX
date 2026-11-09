#pragma once

#include "npv10_range10_codec.h"

namespace neoproc::npv10::lossless {

#if defined(__aarch64__) && !defined(NEO_NPV10_FORCE_SCALAR_SPLIT) && \
    !defined(NEO_NPV10_FORCE_LEGACY_RANGE) && defined(__BYTE_ORDER__) && \
    __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
/** Sixteen lane-separated codes -> exactly twenty original packed bytes. */
inline void storeP010TenBitPairs(uint16x8_t even, uint16x8_t odd,
                                std::uint8_t* packed) noexcept {
    const auto lower = vorrq_u32(vmovl_u16(vget_low_u16(even)),
        vshlq_n_u32(vmovl_u16(vget_low_u16(odd)), 10u));
    const auto upper = vorrq_u32(vmovl_u16(vget_high_u16(even)),
        vshlq_n_u32(vmovl_u16(vget_high_u16(odd)), 10u));
    const auto mask = vdupq_n_u64((std::uint64_t{1u} << 20u) - 1u);
    const auto lowerWords = vreinterpretq_u64_u32(lower);
    const auto upperWords = vreinterpretq_u64_u32(upper);
    const auto first = vbslq_u64(mask, lowerWords, vshrq_n_u64(lowerWords, 12u));
    const auto last = vbslq_u64(mask, upperWords, vshrq_n_u64(upperWords, 12u));
    // Each 64-bit word holds forty useful bits. Compact only those five bytes;
    // do not store padding beyond the caller's exact twenty-byte destination.
    const uint8x16x2_t table = {{vreinterpretq_u8_u64(first), vreinterpretq_u8_u64(last)}};
    constexpr std::uint8_t indices[16] = {
        0, 1, 2, 3, 4, 8, 9, 10, 11, 12, 16, 17, 18, 19, 20, 24
    };
    vst1q_u8(packed, vqtbl2q_u8(table, vld1q_u8(indices)));
    const auto tail = static_cast<std::uint32_t>(vgetq_lane_u64(last, 1) >> 8u);
    std::memcpy(packed + 16u, &tail, sizeof(tail));
}
#endif

/**
 * Read exactly 64 canonical P010 words once. Produce the original packed bytes for
 * CRC/LZ4 and, when it fits, the existing NLC2 range body from the same values.
 * No sample is quantized. A zero range length means lossless LZ4/raw escape, not
 * failure; false means invalid P010 padding. Neither source nor output may alias.
 * The caller owns 128 readable input bytes and 80 writable packed bytes.
 */
#if defined(NEO_NPV10_ENABLE_FUSED_BLOCK_INLINE) && defined(__aarch64__) && defined(__clang__) && \
    !defined(NEO_NPV10_FORCE_OUTLINED_P010_BLOCK)
// Standalone comparison only. Clang outlines this 294,912-times-per-frame
// boundary, but paired actual-camera-frame runs on the target made explicit
// inlining slower (21.09 ms vs 20.16 ms). Keep production's measured compiler
// decision. Both variants retain stack protection and every sample/bounds check.
__attribute__((always_inline))
#endif
inline bool packAndEncodeP010RangeBlock(const std::uint8_t* source,
                                      std::uint8_t* packed,
                                      std::uint8_t* range,
                                      std::size_t rangeCapacity,
                                      std::size_t* rangeBytes) noexcept {
    if (source == nullptr || packed == nullptr || rangeBytes == nullptr ||
        (range == nullptr && rangeCapacity != 0u)) return false;
    *rangeBytes = 0u;
#if defined(__aarch64__) && !defined(NEO_NPV10_FORCE_SCALAR_SPLIT) && \
    !defined(NEO_NPV10_FORCE_LEGACY_RANGE) && defined(__BYTE_ORDER__) && \
    __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
    Range10NeonLane lanes[2];
    auto padding = vdupq_n_u16(0u);
    for (unsigned quarter = 0u; quarter < 4u; ++quarter) {
        const auto pair = vld2q_u16(reinterpret_cast<const std::uint16_t*>(
            source + quarter * 32u));
        padding = vorrq_u16(padding, vorrq_u16(pair.val[0], pair.val[1]));
        lanes[0].values[quarter] = vshrq_n_u16(pair.val[0], 6u);
        lanes[1].values[quarter] = vshrq_n_u16(pair.val[1], 6u);
    }
    if (vmaxvq_u16(vandq_u16(padding, vdupq_n_u16(63u))) != 0u) return false;
    for (unsigned quarter = 0u; quarter < 4u; ++quarter) {
        storeP010TenBitPairs(lanes[0].values[quarter], lanes[1].values[quarter], packed + quarter * 20u);
    }
    if (rangeCapacity == 0u) return true;
    unsigned minima[2], widths[2];
    for (unsigned lane = 0u; lane < 2u; ++lane) {
        const auto& v = lanes[lane].values;
        minima[lane] = vminvq_u16(vminq_u16(vminq_u16(v[0], v[1]),
            vminq_u16(v[2], v[3])));
        const auto maximum = vmaxvq_u16(vmaxq_u16(vmaxq_u16(v[0], v[1]),
            vmaxq_u16(v[2], v[3])));
        const unsigned span = maximum - minima[lane];
        widths[lane] = span == 0u ? 0u : 32u - static_cast<unsigned>(__builtin_clz(span));
    }
    const std::size_t bytes = 4u + 4u * (widths[0] + widths[1]);
    if (bytes > rangeCapacity) return true;
    std::size_t cursor = 0u;
    for (unsigned lane = 0u; lane < 2u; ++lane) {
        const unsigned header = minima[lane] | (widths[lane] << 10u);
        range[cursor++] = static_cast<std::uint8_t>(header);
        range[cursor++] = static_cast<std::uint8_t>(header >> 8u);
        range10StoreNeonBits(widths[lane], lanes[lane], minima[lane], range + cursor);
        cursor += widths[lane] * 4u;
    }
    *rangeBytes = cursor;
#else
    std::uint16_t codes[64];
    for (unsigned sample = 0u; sample < 64u; ++sample) {
        const unsigned word = source[sample * 2u] | (unsigned(source[sample * 2u + 1u]) << 8u);
        if ((word & 63u) != 0u) return false;
        codes[sample] = static_cast<std::uint16_t>(word >> 6u);
    }
    range10PackLane<10u>(codes, 64u, 0u, packed);
    if (rangeCapacity != 0u) *rangeBytes = encodeRange10(packed, 64u, range, rangeCapacity);
#endif
    return true;
}

} // namespace neoproc::npv10::lossless
