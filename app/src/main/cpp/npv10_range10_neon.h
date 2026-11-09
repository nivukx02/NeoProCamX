#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <cstring>

#if defined(__aarch64__) && !defined(NEO_NPV10_FORCE_SCALAR_SPLIT) && \
    !defined(NEO_NPV10_FORCE_LEGACY_RANGE) && defined(__BYTE_ORDER__) && \
    __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
#include <arm_neon.h>

namespace neoproc::npv10::lossless {

/**
 * The existing NLC2 range block, computed directly in Advanced SIMD registers.
 * AArch64 guarantees these instructions. Other ABIs and partial blocks retain the
 * established implementation. No new codec, approximation, quantizer, padding or
 * sample order is introduced. The caller supplies exactly 80 readable source bytes.
 */
struct Range10NeonLane final {
    uint16x8_t values[4];
};

/**
 * Unpack sixteen original ten-bit codes directly into their two eight-code lanes.
 * Each gathered LE16 word contains a complete code: even offsets alternate 0/4
 * bits and odd offsets 2/6 bits. The right shifts discard neighbouring codes,
 * never source precision. Two table lookups replace five separate byte streams
 * and their widening/interleave work. Read exactly twenty bytes, including the
 * final four-byte load, so the last quarter remains safe at a protected page.
 */
inline void range10LoadNeonWordPairs(const std::uint8_t* packed,
                                    Range10NeonLane& even,
                                    Range10NeonLane& odd,
                                    unsigned quarter) noexcept {
    constexpr std::uint8_t evenIndices[16] = {
        0, 1, 2, 3, 5, 6, 7, 8, 10, 11, 12, 13, 15, 16, 17, 18
    };
    constexpr std::uint8_t oddIndices[16] = {
        1, 2, 3, 4, 6, 7, 8, 9, 11, 12, 13, 14, 16, 17, 18, 19
    };
    constexpr std::int16_t evenShifts[8] = {0, -4, 0, -4, 0, -4, 0, -4};
    constexpr std::int16_t oddShifts[8] = {-2, -6, -2, -6, -2, -6, -2, -6};
    std::uint32_t tail = 0u;
    std::memcpy(&tail, packed + 16u, sizeof(tail));
    // Only table indices 0..19 are used; replicated tail lanes are never read.
    const uint8x16x2_t table = {{vld1q_u8(packed),
        vreinterpretq_u8_u32(vdupq_n_u32(tail))}};
    const auto mask = vdupq_n_u16(1023u);
    even.values[quarter] = vandq_u16(vshlq_u16(vreinterpretq_u16_u8(
        vqtbl2q_u8(table, vld1q_u8(evenIndices))), vld1q_s16(evenShifts)), mask);
    odd.values[quarter] = vandq_u16(vshlq_u16(vreinterpretq_u16_u8(
        vqtbl2q_u8(table, vld1q_u8(oddIndices))), vld1q_s16(oddShifts)), mask);
}

inline void range10LoadNeonHalf(const std::uint8_t* packed, Range10NeonLane& even,
                               Range10NeonLane& odd, unsigned half) noexcept {
    constexpr std::uint8_t indices[8] = {0, 5, 10, 15, 20, 25, 30, 35};
    const auto groups = vld1_u8(indices);
    // Exactly 40 bytes, including the final eight-byte load: never read across a
    // guarded frame/chunk boundary. No canonical P010 scratch-store/reload pass.
    const uint8x16x3_t table = {{vld1q_u8(packed), vld1q_u8(packed + 16),
        vcombine_u8(vld1_u8(packed + 32), vdup_n_u8(0))}};
    const auto a = vqtbl3_u8(table, groups);
    const auto b = vqtbl3_u8(table, vadd_u8(groups, vdup_n_u8(1)));
    const auto c = vqtbl3_u8(table, vadd_u8(groups, vdup_n_u8(2)));
    const auto d = vqtbl3_u8(table, vadd_u8(groups, vdup_n_u8(3)));
    const auto e = vqtbl3_u8(table, vadd_u8(groups, vdup_n_u8(4)));
    const auto p0 = vorrq_u16(vmovl_u8(a),
        vshlq_n_u16(vmovl_u8(vand_u8(b, vdup_n_u8(3))), 8));
    const auto p1 = vorrq_u16(vmovl_u8(vshr_n_u8(b, 2)),
        vshlq_n_u16(vmovl_u8(vand_u8(c, vdup_n_u8(15))), 6));
    const auto p2 = vorrq_u16(vmovl_u8(vshr_n_u8(c, 4)),
        vshlq_n_u16(vmovl_u8(vand_u8(d, vdup_n_u8(63))), 4));
    const auto p3 = vorrq_u16(vmovl_u8(vshr_n_u8(d, 6)), vshlq_n_u16(vmovl_u8(e), 2));
    even.values[half * 2u] = vzip1q_u16(p0, p2);
    even.values[half * 2u + 1u] = vzip2q_u16(p0, p2);
    odd.values[half * 2u] = vzip1q_u16(p1, p3);
    odd.values[half * 2u + 1u] = vzip2q_u16(p1, p3);
}

template<unsigned Bits>
inline void range10StoreNeonEight(uint16x8_t values, uint16x8_t minimum,
                                 std::uint8_t* output) noexcept {
    static_assert(Bits >= 1u && Bits <= 10u);
    const auto residuals = vsubq_u16(values, minimum);
#if !defined(NEO_NPV10_FORCE_LEGACY_RANGE_LANE_STORE)
    if constexpr (Bits <= 8u) {
        // Each adjacent residual pair fits in sixteen bits. Merge within the existing
        // little-endian words, then narrow, instead of unzipping and widening every
        // pair to 32/64 bits. The discarded upper halves contain no source bits.
        // The second merge uses the same proof for four residuals in 32 bits.
        // This emits the existing wire representation, including all noisy residuals;
        // nine/ten-bit lanes retain the full-width implementation below.
        const auto words = vreinterpretq_u32_u16(residuals);
        const auto pairs = vmovn_u32(vorrq_u32(words, vshrq_n_u32(words, 16u - Bits)));
        const auto doubleWords = vreinterpretq_u64_u32(vmovl_u16(pairs));
        const auto halves = vmovn_u64(vorrq_u64(doubleWords,
            vshrq_n_u64(doubleWords, 32u - 2u * Bits)));
        const auto first = static_cast<std::uint64_t>(vget_lane_u32(halves, 0));
        const auto second = static_cast<std::uint64_t>(vget_lane_u32(halves, 1));
        const std::uint64_t packed = first | (second << (4u * Bits));
        // Exact-size stores remain valid for unaligned output and a guarded final lane.
        std::memcpy(output, &packed, Bits);
        return;
    }
#endif
    // Four two-code words fit in 20 bits. Merge pairs in 64-bit lanes before
    // packing the final 8..80 bits; no shift ever truncates a ten-bit residual.
    const auto even = vget_low_u16(vuzp1q_u16(residuals, residuals));
    const auto odd = vget_low_u16(vuzp2q_u16(residuals, residuals));
    const auto pairs = vorrq_u32(vmovl_u16(even), vshlq_n_u32(vmovl_u16(odd), Bits));
    const auto lower = vmovl_u32(vget_low_u32(pairs));
    const auto upper = vmovl_u32(vget_high_u32(pairs));
    const auto halves = vorrq_u64(vzip1q_u64(lower, upper),
        vshlq_n_u64(vzip2q_u64(lower, upper), 2u * Bits));
    const std::uint64_t first = vgetq_lane_u64(halves, 0);
    const std::uint64_t second = vgetq_lane_u64(halves, 1);
    const std::uint64_t low = first | (second << (4u * Bits));
    std::memcpy(output, &low, Bits < 8u ? Bits : 8u);
    if constexpr (Bits > 8u) {
        const auto high = static_cast<std::uint16_t>(second >> (64u - 4u * Bits));
        std::memcpy(output + 8u, &high, Bits - 8u);
    }
}

/** Pack eight residuals without transferring each word through general-purpose registers. */
template<unsigned Bits>
inline uint8x8_t range10NarrowNeonWord(uint16x8_t values, uint16x8_t minimum) noexcept {
    static_assert(Bits >= 1u && Bits <= 7u);
    const auto residuals = vsubq_u16(values, minimum);
    const auto words = vreinterpretq_u32_u16(residuals);
    const auto pairs = vmovn_u32(vorrq_u32(words, vshrq_n_u32(words, 16u - Bits)));
    const auto doubleWords = vreinterpretq_u64_u32(vmovl_u16(pairs));
    const auto halves = vmovn_u64(vorrq_u64(doubleWords,
        vshrq_n_u64(doubleWords, 32u - 2u * Bits)));
    const auto widened = vmovl_u32(halves);
    return vreinterpret_u8_u64(vorr_u64(vget_low_u64(widened),
        vshl_n_u64(vget_high_u64(widened), 4u * Bits)));
}

/** A 32-code lane has a four-byte-aligned length, but its destination need not be aligned. */
template<unsigned Bytes>
inline void range10StoreNeonLaneTail(std::uint8_t* output, uint8x16_t values) noexcept {
    static_assert(Bytes == 4u || Bytes == 8u || Bytes == 12u);
    if constexpr (Bytes >= 8u) vst1_u8(output, vget_low_u8(values));
    if constexpr (Bytes == 4u) {
        const auto tail = vgetq_lane_u32(vreinterpretq_u32_u8(values), 0);
        std::memcpy(output, &tail, sizeof(tail));
    } else if constexpr (Bytes == 12u) {
        const auto tail = vgetq_lane_u32(vreinterpretq_u32_u8(values), 2);
        std::memcpy(output + 8u, &tail, sizeof(tail));
    }
}

/**
 * Compact adjacent bounded fields in-place in a SIMD word. A shift/OR alone is
 * incorrect when the shift is smaller than the first field: it mixes that field
 * into itself. Select its original low bits and only the shifted neighbour's
 * high bits. All bits of both fields survive; the empty inter-field gap does not.
 */
template<unsigned Bits>
inline uint8x16_t range10CompactNeonByteOctets(uint8x16_t values) noexcept {
    static_assert(Bits >= 3u && Bits <= 7u);
    const auto bytePairs = vreinterpretq_u16_u8(values);
    const auto pairs = vbslq_u16(vdupq_n_u16((1u << Bits) - 1u), bytePairs,
        vshrq_n_u16(bytePairs, 8u - Bits));
    const auto pairWords = vreinterpretq_u32_u16(pairs);
    const auto quads = vbslq_u32(vdupq_n_u32((1u << (2u * Bits)) - 1u), pairWords,
        vshrq_n_u32(pairWords, 16u - 2u * Bits));
    const auto quadWords = vreinterpretq_u64_u32(quads);
    return vreinterpretq_u8_u64(vbslq_u64(
        vdupq_n_u64((std::uint64_t{1} << (4u * Bits)) - 1u), quadWords,
        vshrq_n_u64(quadWords, 32u - 4u * Bits)));
}

/** Exact 3/5/6/7-bit lane packing without repeated widening to 32/64-bit scratch. */
template<unsigned Bits>
inline void range10StoreNeonCompactLane(const Range10NeonLane& lane,
                                       uint16x8_t minimum,
                                       std::uint8_t* output) noexcept {
    static_assert(Bits == 3u || Bits == 5u || Bits == 6u || Bits == 7u);
    const auto first = vcombine_u8(vmovn_u16(vsubq_u16(lane.values[0], minimum)),
        vmovn_u16(vsubq_u16(lane.values[1], minimum)));
    const auto last = vcombine_u8(vmovn_u16(vsubq_u16(lane.values[2], minimum)),
        vmovn_u16(vsubq_u16(lane.values[3], minimum)));
    if constexpr (Bits == 3u) {
        // Six-bit pairs fit in bytes; twelve-bit quads and 24-bit octets then
        // fit in their existing word lanes. Emit exactly twelve bytes, even at
        // an unaligned guarded end. Never read or write padding past the lane.
        const auto pairs = vorrq_u8(vuzp1q_u8(first, last),
            vshlq_n_u8(vuzp2q_u8(first, last), 3u));
        const auto pairWords = vreinterpretq_u16_u8(pairs);
        const auto quads = vbslq_u16(vdupq_n_u16(63u), pairWords,
            vshrq_n_u16(pairWords, 2u));
        const auto quadWords = vreinterpretq_u32_u16(quads);
        const auto octets = vbslq_u32(vdupq_n_u32(4095u), quadWords,
            vshrq_n_u32(quadWords, 4u));
        constexpr std::uint8_t indices[16] = {
            0, 1, 2, 4, 5, 6, 8, 9, 10, 12, 13, 14, 255, 255, 255, 255
        };
        range10StoreNeonLaneTail<12u>(output,
            vqtbl1q_u8(vreinterpretq_u8_u32(octets), vld1q_u8(indices)));
    } else {
        const uint8x16x2_t table = {{range10CompactNeonByteOctets<Bits>(first),
            range10CompactNeonByteOctets<Bits>(last)}};
        constexpr auto indices = [] {
            std::array<std::uint8_t, 32u> result{};
            for (unsigned byte = 0u; byte < result.size(); ++byte) {
                result[byte] = byte < Bits * 4u
                    ? static_cast<std::uint8_t>((byte / Bits) * 8u + byte % Bits) : 0xffu;
            }
            return result;
        }();
        vst1q_u8(output, vqtbl2q_u8(table, vld1q_u8(indices.data())));
        range10StoreNeonLaneTail<Bits * 4u - 16u>(output + 16u,
            vqtbl2q_u8(table, vld1q_u8(indices.data() + 16u)));
    }
}

/**
 * Join the four eight-code words in registers and publish one exact-size lane.
 * The encoded NLC2 bytes are identical to four range10StoreNeonEight calls. No
 * source code, range width, header, checksum, allocation or queue limit changes.
 * Nine/ten-bit lanes retain their established full-width implementation.
 */
template<unsigned Bits>
inline void range10StoreNeonWholeNarrowLane(const Range10NeonLane& lane,
                                           uint16x8_t minimum,
                                           std::uint8_t* output) noexcept {
    static_assert(Bits >= 1u && Bits <= 8u);
#if defined(NEO_NPV10_ENABLE_COMPACT_RANGE_LANES)
    // Native byte-contract tests pass, but real-frame timings did not establish
    // a repeatable speed benefit. Keep this standalone probe opt-in; production
    // retains its measured lane store until a physical comparison justifies it.
    if constexpr (Bits == 3u || Bits == 5u || Bits == 6u || Bits == 7u) {
        range10StoreNeonCompactLane<Bits>(lane, minimum, output);
        return;
    }
#endif
    if constexpr (Bits == 8u) {
        vst1q_u8(output, vcombine_u8(vmovn_u16(vsubq_u16(lane.values[0], minimum)),
            vmovn_u16(vsubq_u16(lane.values[1], minimum))));
        vst1q_u8(output + 16u, vcombine_u8(vmovn_u16(vsubq_u16(lane.values[2], minimum)),
            vmovn_u16(vsubq_u16(lane.values[3], minimum))));
#if !defined(NEO_NPV10_FORCE_LEGACY_SMALL_RANGE_LANES)
    } else if constexpr (Bits == 1u || Bits == 2u || Bits == 4u) {
        // Small residuals need no 32/64-bit widening or cross-table byte gather. Pair the
        // already bounded byte codes directly, then merge pairs only as far as needed.
        // These are exact residual bits, not quantization: subtraction still uses the
        // complete ten-bit origin and the caller's measured 1/2/4-bit range. Emit exactly
        // 4/8/16 bytes, including unaligned/guarded tails, in the original sample order.
        const auto first = vcombine_u8(vmovn_u16(vsubq_u16(lane.values[0], minimum)),
            vmovn_u16(vsubq_u16(lane.values[1], minimum)));
        const auto last = vcombine_u8(vmovn_u16(vsubq_u16(lane.values[2], minimum)),
            vmovn_u16(vsubq_u16(lane.values[3], minimum)));
        const auto pairs = vorrq_u8(vuzp1q_u8(first, last),
            vshlq_n_u8(vuzp2q_u8(first, last), Bits));
        if constexpr (Bits == 4u) {
            vst1q_u8(output, pairs);
        } else {
            const auto pairWords = vreinterpretq_u16_u8(pairs);
            const auto quads = vmovn_u16(vorrq_u16(pairWords,
                vshrq_n_u16(pairWords, 8u - 2u * Bits)));
            if constexpr (Bits == 2u) {
                vst1_u8(output, quads);
            } else {
                const auto quadWords = vreinterpretq_u16_u8(vcombine_u8(quads, vdup_n_u8(0)));
                const auto octets = vmovn_u16(vorrq_u16(quadWords, vshrq_n_u16(quadWords, 4u)));
                const auto word = vget_lane_u32(vreinterpret_u32_u8(octets), 0);
                std::memcpy(output, &word, sizeof(word));
            }
        }
#endif
    } else {
        const uint8x16x2_t table = {{
            vcombine_u8(range10NarrowNeonWord<Bits>(lane.values[0], minimum),
                range10NarrowNeonWord<Bits>(lane.values[1], minimum)),
            vcombine_u8(range10NarrowNeonWord<Bits>(lane.values[2], minimum),
                range10NarrowNeonWord<Bits>(lane.values[3], minimum))}};
        constexpr auto firstIndices = [] {
            std::array<std::uint8_t, 16u> indices{};
            for (unsigned byte = 0u; byte < 16u; ++byte) {
                indices[byte] = byte < Bits * 4u
                    ? static_cast<std::uint8_t>((byte / Bits) * 8u + byte % Bits) : 0xffu;
            }
            return indices;
        }();
        const auto first = vqtbl2q_u8(table, vld1q_u8(firstIndices.data()));
        if constexpr (Bits * 4u >= 16u) {
            vst1q_u8(output, first);
            if constexpr (Bits * 4u > 16u) {
                constexpr auto tailIndices = [] {
                    std::array<std::uint8_t, 16u> indices{};
                    for (unsigned byte = 0u; byte < 16u; ++byte) {
                        const auto position = byte + 16u;
                        indices[byte] = position < Bits * 4u
                            ? static_cast<std::uint8_t>((position / Bits) * 8u + position % Bits) : 0xffu;
                    }
                    return indices;
                }();
                const auto tail = vqtbl2q_u8(table, vld1q_u8(tailIndices.data()));
                range10StoreNeonLaneTail<Bits * 4u - 16u>(output + 16u, tail);
            }
        } else {
            range10StoreNeonLaneTail<Bits * 4u>(output, first);
        }
    }
}

template<unsigned Bits>
inline void range10StoreNeonLane(const Range10NeonLane& lane, unsigned minimum,
                                std::uint8_t* output) noexcept {
    if constexpr (Bits != 0u) {
        const auto origin = vdupq_n_u16(static_cast<std::uint16_t>(minimum));
#if !defined(NEO_NPV10_FORCE_EIGHT_SAMPLE_RANGE_LANES) && \
    !defined(NEO_NPV10_FORCE_LEGACY_RANGE_LANE_STORE)
        if constexpr (Bits <= 8u) {
            range10StoreNeonWholeNarrowLane<Bits>(lane, origin, output);
            return;
        }
#endif
        range10StoreNeonEight<Bits>(lane.values[0], origin, output);
        range10StoreNeonEight<Bits>(lane.values[1], origin, output + Bits);
        range10StoreNeonEight<Bits>(lane.values[2], origin, output + Bits * 2u);
        range10StoreNeonEight<Bits>(lane.values[3], origin, output + Bits * 3u);
    }
}

// At 12MP this dispatch executes 589,824 times per frame. Clang otherwise
// outlines the large switch even with -O3, forcing the live SIMD lanes through
// stack storage and reloading them at each call. Keep the dispatch with its
// owning block so values stay in registers; this does not change codec bytes.
#if defined(NEO_NPV10_ENABLE_REGISTER_RESIDENT_RANGE_STORE)
__attribute__((always_inline))
#endif
inline void range10StoreNeonBits(unsigned bits, const Range10NeonLane& lane,
                                unsigned minimum, std::uint8_t* output) noexcept {
    switch (bits) {
#define NEO_RANGE_NEON_CASE(B) case B: range10StoreNeonLane<B>(lane, minimum, output); break
        NEO_RANGE_NEON_CASE(0); NEO_RANGE_NEON_CASE(1); NEO_RANGE_NEON_CASE(2);
        NEO_RANGE_NEON_CASE(3); NEO_RANGE_NEON_CASE(4); NEO_RANGE_NEON_CASE(5);
        NEO_RANGE_NEON_CASE(6); NEO_RANGE_NEON_CASE(7); NEO_RANGE_NEON_CASE(8);
        NEO_RANGE_NEON_CASE(9); NEO_RANGE_NEON_CASE(10);
#undef NEO_RANGE_NEON_CASE
        default: break; // A validated ten-bit min/max span always has width 0..10.
    }
}

#if defined(NEO_NPV10_ENABLE_REGISTER_RESIDENT_RANGE_STORE)
__attribute__((always_inline))
#endif
inline std::size_t range10EncodeNeonBlock(const std::uint8_t* packed,
                                        std::uint8_t* output,
                                        std::size_t capacity) noexcept {
    Range10NeonLane lanes[2];
#if !defined(NEO_NPV10_FORCE_LEGACY_RANGE_WORD_LOAD)
    // Native all-code/guard tests and paired real-camera-frame measurements validate
    // the byte-equivalent path. The legacy flag is for standalone comparisons only;
    // it is never set by application build variants or selected during recording.
    for (unsigned quarter = 0u; quarter < 4u; ++quarter)
        range10LoadNeonWordPairs(packed + quarter * 20u, lanes[0], lanes[1], quarter);
#else
    range10LoadNeonHalf(packed, lanes[0], lanes[1], 0u);
    range10LoadNeonHalf(packed + 40u, lanes[0], lanes[1], 1u);
#endif
    unsigned minima[2];
    unsigned widths[2];
    for (unsigned i = 0u; i < 2u; ++i) {
        const auto& v = lanes[i].values;
        minima[i] = vminvq_u16(vminq_u16(vminq_u16(v[0], v[1]), vminq_u16(v[2], v[3])));
        const unsigned maximum = vmaxvq_u16(vmaxq_u16(vmaxq_u16(v[0], v[1]), vmaxq_u16(v[2], v[3])));
        const unsigned span = maximum - minima[i];
        widths[i] = span == 0u ? 0u : 32u - static_cast<unsigned>(__builtin_clz(span));
    }
    const std::size_t bytes = 4u + 4u * (widths[0] + widths[1]);
    if (bytes > capacity) return 0u;
    std::size_t cursor = 0u;
    for (unsigned lane = 0u; lane < 2u; ++lane) {
        const unsigned header = minima[lane] | (widths[lane] << 10u);
        output[cursor++] = static_cast<std::uint8_t>(header);
        output[cursor++] = static_cast<std::uint8_t>(header >> 8u);
        range10StoreNeonBits(widths[lane], lanes[lane], minima[lane], output + cursor);
        cursor += 4u * widths[lane];
    }
    return cursor;
}

/** Eight residuals occupy exactly Bits bytes, including the nine/ten-bit cases. */
template<unsigned Bits>
inline uint16x8_t range10ReadNeonEight(const std::uint8_t* input,
                                     uint16x8_t minimum) noexcept {
    static_assert(Bits >= 1u && Bits <= 10u);
    // A bounded copy is intentional: loading sixteen bytes directly would cross the final
    // packet/guard-page boundary for narrow lanes. Constant-sized memcpy is lowered inline.
    alignas(16) std::uint8_t bytes[16] = {};
    std::memcpy(bytes, input, Bits);
    constexpr std::uint8_t positions[8] = {
        0u, Bits / 8u, 2u * Bits / 8u, 3u * Bits / 8u,
        4u * Bits / 8u, 5u * Bits / 8u, 6u * Bits / 8u, 7u * Bits / 8u
    };
    constexpr std::int16_t shifts[8] = {
        0, -static_cast<int>(Bits & 7u), -static_cast<int>((2u * Bits) & 7u),
        -static_cast<int>((3u * Bits) & 7u), -static_cast<int>((4u * Bits) & 7u),
        -static_cast<int>((5u * Bits) & 7u), -static_cast<int>((6u * Bits) & 7u),
        -static_cast<int>((7u * Bits) & 7u)
    };
    const auto table = vld1q_u8(bytes);
    const auto indices = vld1_u8(positions);
    const auto words = vorrq_u16(vmovl_u8(vqtbl1_u8(table, indices)),
        vshlq_n_u16(vmovl_u8(vqtbl1_u8(table, vadd_u8(indices, vdup_n_u8(1)))), 8));
    const auto residuals = vandq_u16(vshlq_u16(words, vld1q_s16(shifts)),
        vdupq_n_u16((1u << Bits) - 1u));
    return vaddq_u16(residuals, minimum);
}

template<unsigned Bits>
inline bool range10ReadNeonLane(const std::uint8_t* input, unsigned minimum,
                               Range10NeonLane& lane) noexcept {
    const auto origin = vdupq_n_u16(static_cast<std::uint16_t>(minimum));
    if constexpr (Bits == 0u) {
        for (auto& values : lane.values) values = origin;
    } else {
        lane.values[0] = range10ReadNeonEight<Bits>(input, origin);
        lane.values[1] = range10ReadNeonEight<Bits>(input + Bits, origin);
        lane.values[2] = range10ReadNeonEight<Bits>(input + Bits * 2u, origin);
        lane.values[3] = range10ReadNeonEight<Bits>(input + Bits * 3u, origin);
    }
    // Corrupt origin/residual combinations must still fail, never wrap or clamp a sensor code.
    const auto& v = lane.values;
    return vmaxvq_u16(vmaxq_u16(vmaxq_u16(v[0], v[1]), vmaxq_u16(v[2], v[3]))) <= 1023u;
}

inline bool range10ReadNeonBits(unsigned bits, const std::uint8_t* input,
                               unsigned minimum, Range10NeonLane& lane) noexcept {
    switch (bits) {
#define NEO_RANGE_NEON_READ_CASE(B) case B: return range10ReadNeonLane<B>(input, minimum, lane)
        NEO_RANGE_NEON_READ_CASE(0); NEO_RANGE_NEON_READ_CASE(1); NEO_RANGE_NEON_READ_CASE(2);
        NEO_RANGE_NEON_READ_CASE(3); NEO_RANGE_NEON_READ_CASE(4); NEO_RANGE_NEON_READ_CASE(5);
        NEO_RANGE_NEON_READ_CASE(6); NEO_RANGE_NEON_READ_CASE(7); NEO_RANGE_NEON_READ_CASE(8);
        NEO_RANGE_NEON_READ_CASE(9); NEO_RANGE_NEON_READ_CASE(10);
#undef NEO_RANGE_NEON_READ_CASE
        default: return false;
    }
}

/** Validates both complete lanes before writing exactly eighty original packed bytes. */
inline std::size_t range10DecodeNeonBlock(const std::uint8_t* input,
                                        std::size_t inputBytes,
                                        std::uint8_t* packed) noexcept {
    Range10NeonLane lanes[2];
    std::size_t cursor = 0u;
    for (auto& lane : lanes) {
        if (cursor > inputBytes || inputBytes - cursor < 2u) return 0u;
        const unsigned header = input[cursor] | (input[cursor + 1u] << 8u);
        cursor += 2u;
        const unsigned minimum = header & 1023u;
        const unsigned bits = (header >> 10u) & 15u;
        if ((header & 0xc000u) != 0u || bits > 10u) return 0u;
        const std::size_t bytes = bits * 4u;
        if (bytes > inputBytes - cursor ||
            !range10ReadNeonBits(bits, input + cursor, minimum, lane)) return 0u;
        cursor += bytes;
    }
    const auto zero = vdupq_n_u16(0);
    for (unsigned group = 0u; group < 4u; ++group) {
        range10StoreNeonEight<10>(vzip1q_u16(lanes[0].values[group], lanes[1].values[group]),
            zero, packed + group * 20u);
        range10StoreNeonEight<10>(vzip2q_u16(lanes[0].values[group], lanes[1].values[group]),
            zero, packed + group * 20u + 10u);
    }
    return cursor;
}

} // namespace neoproc::npv10::lossless
#endif
