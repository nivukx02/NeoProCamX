#pragma once

#include <cstddef>
#include <cstdint>

#if defined(__aarch64__) && !defined(NEO_NPV10_FORCE_SCALAR_SPLIT)
#include <arm_neon.h>
#endif

namespace neoproc::npv10::lossless {

/**
 * Exact integer permutation of packed ten-bit samples into low-byte/high-two-bit planes.
 * AArch64 guarantees Advanced SIMD; other ABIs keep the existing scalar implementation.
 * The caller validates sizes and supplies disjoint buffers. Returns the complete 32-sample prefix
 * processed; the existing scalar loop owns every remaining sample and partial high-bit byte.
 */
inline std::size_t splitPackedTenBitSimdPrefix(
    const std::uint8_t* source, std::size_t samples, std::uint8_t* target
) noexcept {
#if defined(__aarch64__) && !defined(NEO_NPV10_FORCE_SCALAR_SPLIT)
    constexpr std::uint8_t indices[8] = {0, 5, 10, 15, 20, 25, 30, 35};
    const auto groupIndices = vld1_u8(indices);
    const auto zero = vdup_n_u8(0);
    std::size_t sample = 0;
    for (; samples - sample >= 32; sample += 32) {
        const auto* packed = source + (sample / 4) * 5;
        // Exactly 40 source bytes. A full third vector would read eight bytes beyond the final
        // chunk on page-boundary input. Combine the valid eight-byte load with explicit zeros.
        const uint8x16x3_t table = {{
            vld1q_u8(packed), vld1q_u8(packed + 16),
            vcombine_u8(vld1_u8(packed + 32), zero)
        }};
        const auto a = vqtbl3_u8(table, groupIndices);
        const auto b = vqtbl3_u8(table, vadd_u8(groupIndices, vdup_n_u8(1)));
        const auto c = vqtbl3_u8(table, vadd_u8(groupIndices, vdup_n_u8(2)));
        const auto d = vqtbl3_u8(table, vadd_u8(groupIndices, vdup_n_u8(3)));
        const auto e = vqtbl3_u8(table, vadd_u8(groupIndices, vdup_n_u8(4)));
        const uint8x8x4_t low = {{
            a,
            vorr_u8(vshr_n_u8(b, 2), vshl_n_u8(c, 6)),
            vorr_u8(vshr_n_u8(c, 4), vshl_n_u8(d, 4)),
            vorr_u8(vshr_n_u8(d, 6), vshl_n_u8(e, 2))
        }};
        const auto high = vorr_u8(
            vorr_u8(vand_u8(b, vdup_n_u8(0x03)), vand_u8(c, vdup_n_u8(0x0c))),
            vorr_u8(vand_u8(d, vdup_n_u8(0x30)), vand_u8(e, vdup_n_u8(0xc0)))
        );
        vst4_u8(target + sample, low);
        vst1_u8(target + samples + sample / 4, high);
    }
    return sample;
#else
    (void)source;
    (void)samples;
    (void)target;
    return 0;
#endif
}

/** Exact inverse of the existing split layout; no new on-disk flag or sample transform. */
inline std::size_t joinSplitTenBitSimdPrefix(
    const std::uint8_t* source, std::size_t samples, std::uint8_t* target
) noexcept {
#if defined(__aarch64__) && !defined(NEO_NPV10_FORCE_SCALAR_SPLIT)
    constexpr std::uint8_t firstIndices[16] = {
        0, 8, 16, 24, 32, 1, 9, 17, 25, 33, 2, 10, 18, 26, 34, 3
    };
    constexpr std::uint8_t secondIndices[16] = {
        11, 19, 27, 35, 4, 12, 20, 28, 36, 5, 13, 21, 29, 37, 6, 14
    };
    constexpr std::uint8_t tailIndices[8] = {22, 30, 38, 7, 15, 23, 31, 39};
    const auto first = vld1q_u8(firstIndices);
    const auto second = vld1q_u8(secondIndices);
    const auto tail = vld1_u8(tailIndices);
    std::size_t sample = 0;
    for (; samples - sample >= 32; sample += 32) {
        const auto low = vld4_u8(source + sample);
        const auto high = vld1_u8(source + samples + sample / 4);
        const auto p0 = low.val[0];
        const auto p1 = vorr_u8(vand_u8(high, vdup_n_u8(0x03)), vshl_n_u8(low.val[1], 2));
        const auto p2 = vorr_u8(vorr_u8(vshr_n_u8(low.val[1], 6),
            vand_u8(high, vdup_n_u8(0x0c))), vshl_n_u8(low.val[2], 4));
        const auto p3 = vorr_u8(vorr_u8(vshr_n_u8(low.val[2], 4),
            vand_u8(high, vdup_n_u8(0x30))), vshl_n_u8(low.val[3], 6));
        const auto p4 = vorr_u8(vshr_n_u8(low.val[3], 2), vand_u8(high, vdup_n_u8(0xc0)));
        const uint8x16x3_t table = {{
            vcombine_u8(p0, p1), vcombine_u8(p2, p3), vcombine_u8(p4, vdup_n_u8(0))
        }};
        auto* packed = target + (sample / 4) * 5;
        // Exactly 40 bytes: never write a complete third vector into a partial final group.
        vst1q_u8(packed, vqtbl3q_u8(table, first));
        vst1q_u8(packed + 16, vqtbl3q_u8(table, second));
        vst1_u8(packed + 32, vqtbl3_u8(table, tail));
    }
    return sample;
#else
    (void)source;
    (void)samples;
    (void)target;
    return 0;
#endif
}

/** Expand packed codes to canonical little-endian P010 without floating point or quantization. */
inline std::size_t writeP010FromPackedSimdPrefix(
    const std::uint8_t* source, std::size_t samples, std::uint8_t* target
) noexcept {
#if defined(__aarch64__) && !defined(NEO_NPV10_FORCE_SCALAR_SPLIT) && \
    defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
    // Public native callers may supply byte-aligned slices. Retain the scalar path when a
    // uint16_t store would violate the C++ alignment contract, even if the CPU permits it.
    if (reinterpret_cast<std::uintptr_t>(target) % alignof(std::uint16_t) != 0) return 0;
    constexpr std::uint8_t indices[8] = {0, 5, 10, 15, 20, 25, 30, 35};
    const auto groupIndices = vld1_u8(indices);
    const auto zero = vdup_n_u8(0);
    std::size_t sample = 0;
    for (; samples - sample >= 32; sample += 32) {
        const auto* packed = source + (sample / 4) * 5;
        const uint8x16x3_t table = {{
            vld1q_u8(packed), vld1q_u8(packed + 16),
            vcombine_u8(vld1_u8(packed + 32), zero)
        }};
        const auto a = vqtbl3_u8(table, groupIndices);
        const auto b = vqtbl3_u8(table, vadd_u8(groupIndices, vdup_n_u8(1)));
        const auto c = vqtbl3_u8(table, vadd_u8(groupIndices, vdup_n_u8(2)));
        const auto d = vqtbl3_u8(table, vadd_u8(groupIndices, vdup_n_u8(3)));
        const auto e = vqtbl3_u8(table, vadd_u8(groupIndices, vdup_n_u8(4)));
        const uint16x8x4_t words = {{
            vorrq_u16(vshlq_n_u16(vmovl_u8(a), 6), vshlq_n_u16(vmovl_u8(b), 14)),
            vorrq_u16(vshlq_n_u16(vmovl_u8(vshr_n_u8(b, 2)), 6), vshlq_n_u16(vmovl_u8(c), 12)),
            vorrq_u16(vshlq_n_u16(vmovl_u8(vshr_n_u8(c, 4)), 6), vshlq_n_u16(vmovl_u8(d), 10)),
            vorrq_u16(vshlq_n_u16(vmovl_u8(vshr_n_u8(d, 6)), 6), vshlq_n_u16(vmovl_u8(e), 8))
        }};
        vst4q_u16(reinterpret_cast<std::uint16_t*>(target + sample * 2), words);
    }
    return sample;
#else
    (void)source;
    (void)samples;
    (void)target;
    return 0;
#endif
}

}  // namespace neoproc::npv10::lossless
