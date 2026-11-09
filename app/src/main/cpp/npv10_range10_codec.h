#pragma once

#include <algorithm>
#include <array>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include "npv10_split10_simd.h"
#include "npv10_range10_neon.h"

namespace neoproc::npv10::lossless {

/**
 * Bit-exact 64-sample range blocks, with separate even/odd lanes for interleaved U/V.
 * Each lane begins with a LE16 (minimum | bitWidth << 10); the top two bits are reserved.
 * It is followed by ceil(laneSamples * bitWidth / 8) little-endian residual bits. Both
 * headers are present even for a one-sample tail; an empty lane has a zero header.
 * The outer NLC2 chunk owns sample count, bounds and the original packed-sample CRC.
 * No prediction crosses a block, row, frame, or recording. No sample is quantized.
 */
constexpr std::size_t kRange10BlockSamples = 64u;

// Eight residuals consume exactly Bits bytes. Compile-time shifts remove the per-sample bit
// cursor/byte-emission loop from full blocks; memcpy also permits unaligned packet destinations.
template<unsigned Bits>
inline void range10PackLane(const std::uint16_t* values, std::size_t count,
                            unsigned minimum, std::uint8_t* output) noexcept {
    if constexpr (Bits == 0u) return;
    std::size_t i = 0u;
    for (; count - i >= 8u; i += 8u) {
        const std::uint64_t first = static_cast<std::uint64_t>(values[i] - minimum) |
            (static_cast<std::uint64_t>(values[i + 1u] - minimum) << Bits) |
            (static_cast<std::uint64_t>(values[i + 2u] - minimum) << (2u * Bits)) |
            (static_cast<std::uint64_t>(values[i + 3u] - minimum) << (3u * Bits));
        const std::uint64_t second = static_cast<std::uint64_t>(values[i + 4u] - minimum) |
            (static_cast<std::uint64_t>(values[i + 5u] - minimum) << Bits) |
            (static_cast<std::uint64_t>(values[i + 6u] - minimum) << (2u * Bits)) |
            (static_cast<std::uint64_t>(values[i + 7u] - minimum) << (3u * Bits));
        const std::uint64_t low = first | (second << (4u * Bits));
#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
        std::memcpy(output, &low, std::min<unsigned>(Bits, 8u));
        if constexpr (Bits > 8u) {
            const auto high = static_cast<std::uint16_t>(second >> (64u - 4u * Bits));
            std::memcpy(output + 8u, &high, Bits - 8u);
        }
#else
        for (unsigned byte = 0; byte < std::min<unsigned>(Bits, 8u); ++byte)
            output[byte] = static_cast<std::uint8_t>(low >> (byte * 8u));
        if constexpr (Bits > 8u) {
            const auto high = second >> (64u - 4u * Bits);
            for (unsigned byte = 8u; byte < Bits; ++byte)
                output[byte] = static_cast<std::uint8_t>(high >> ((byte - 8u) * 8u));
        }
#endif
        output += Bits;
    }
    std::uint32_t accumulator = 0u;
    unsigned pending = 0u;
    for (; i < count; ++i) {
        accumulator |= static_cast<std::uint32_t>(values[i] - minimum) << pending;
        pending += Bits;
        while (pending >= 8u) {
            *output++ = static_cast<std::uint8_t>(accumulator);
            accumulator >>= 8u;
            pending -= 8u;
        }
    }
    if (pending > 0u) *output = static_cast<std::uint8_t>(accumulator);
}

template<unsigned Bits, unsigned Index>
inline unsigned range10Residual(std::uint64_t low, std::uint16_t high) noexcept {
    constexpr unsigned shift = Index * Bits;
    constexpr unsigned mask = (1u << Bits) - 1u;
    if constexpr (shift >= 64u) return (high >> (shift - 64u)) & mask;
    else if constexpr (shift + Bits > 64u)
        return static_cast<unsigned>((low >> shift) | (static_cast<std::uint64_t>(high) << (64u - shift))) & mask;
    else return static_cast<unsigned>(low >> shift) & mask;
}

template<unsigned Bits>
inline bool range10UnpackLane(const std::uint8_t* input, std::size_t count,
                              unsigned minimum, std::uint16_t* values) noexcept {
    if constexpr (Bits == 0u) {
        std::fill_n(values, count, static_cast<std::uint16_t>(minimum));
        return true;
    }
    std::size_t i = 0u;
    for (; count - i >= 8u; i += 8u) {
        std::uint64_t low = 0u;
        std::uint16_t high = 0u;
#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
        std::memcpy(&low, input, std::min<unsigned>(Bits, 8u));
        if constexpr (Bits > 8u) std::memcpy(&high, input + 8u, Bits - 8u);
#else
        for (unsigned byte = 0u; byte < std::min<unsigned>(Bits, 8u); ++byte)
            low |= static_cast<std::uint64_t>(input[byte]) << (8u * byte);
        if constexpr (Bits > 8u) {
            for (unsigned byte = 8u; byte < Bits; ++byte)
                high |= static_cast<std::uint16_t>(input[byte]) << (8u * (byte - 8u));
        }
#endif
        const unsigned residuals[8] = {
            range10Residual<Bits, 0>(low, high), range10Residual<Bits, 1>(low, high),
            range10Residual<Bits, 2>(low, high), range10Residual<Bits, 3>(low, high),
            range10Residual<Bits, 4>(low, high), range10Residual<Bits, 5>(low, high),
            range10Residual<Bits, 6>(low, high), range10Residual<Bits, 7>(low, high)
        };
        for (unsigned lane = 0; lane < 8u; ++lane) {
            if (residuals[lane] > 1023u - minimum) return false;
            values[i + lane] = static_cast<std::uint16_t>(minimum + residuals[lane]);
        }
        input += Bits;
    }
    std::uint32_t accumulator = 0u;
    unsigned available = 0u;
    for (; i < count; ++i) {
        while (available < Bits) {
            accumulator |= static_cast<std::uint32_t>(*input++) << available;
            available += 8u;
        }
        const unsigned code = minimum + (accumulator & ((1u << Bits) - 1u));
        if (code > 1023u) return false;
        values[i] = static_cast<std::uint16_t>(code);
        accumulator >>= Bits;
        available -= Bits;
    }
    return accumulator == 0u;
}

inline void range10PackBits(unsigned bits, const std::uint16_t* values, std::size_t count,
                            unsigned minimum, std::uint8_t* output) noexcept {
    switch (bits) {
#define NEO_RANGE_PACK_CASE(B) case B: range10PackLane<B>(values, count, minimum, output); break
        NEO_RANGE_PACK_CASE(0); NEO_RANGE_PACK_CASE(1); NEO_RANGE_PACK_CASE(2);
        NEO_RANGE_PACK_CASE(3); NEO_RANGE_PACK_CASE(4); NEO_RANGE_PACK_CASE(5);
        NEO_RANGE_PACK_CASE(6); NEO_RANGE_PACK_CASE(7); NEO_RANGE_PACK_CASE(8);
        NEO_RANGE_PACK_CASE(9); NEO_RANGE_PACK_CASE(10);
#undef NEO_RANGE_PACK_CASE
        default: break; // only called after a validated 0..10 span
    }
}

inline bool range10UnpackBits(unsigned bits, const std::uint8_t* input, std::size_t count,
                              unsigned minimum, std::uint16_t* values) noexcept {
    switch (bits) {
#define NEO_RANGE_UNPACK_CASE(B) case B: return range10UnpackLane<B>(input, count, minimum, values)
        NEO_RANGE_UNPACK_CASE(0); NEO_RANGE_UNPACK_CASE(1); NEO_RANGE_UNPACK_CASE(2);
        NEO_RANGE_UNPACK_CASE(3); NEO_RANGE_UNPACK_CASE(4); NEO_RANGE_UNPACK_CASE(5);
        NEO_RANGE_UNPACK_CASE(6); NEO_RANGE_UNPACK_CASE(7); NEO_RANGE_UNPACK_CASE(8);
        NEO_RANGE_UNPACK_CASE(9); NEO_RANGE_UNPACK_CASE(10);
#undef NEO_RANGE_UNPACK_CASE
        default: return false;
    }
}

inline std::uint16_t range10PackedCode(const std::uint8_t* source, std::size_t index) noexcept {
    const auto* group = source + index / 4u * 5u;
    switch (index & 3u) {
        case 0u: return static_cast<std::uint16_t>(group[0] | ((group[1] & 3u) << 8u));
        case 1u: return static_cast<std::uint16_t>((group[1] >> 2u) | ((group[2] & 15u) << 6u));
        case 2u: return static_cast<std::uint16_t>((group[2] >> 4u) | ((group[3] & 63u) << 4u));
        default: return static_cast<std::uint16_t>((group[3] >> 6u) | (group[4] << 2u));
    }
}

// Returns zero if this representation cannot fit. The caller retains its untouched source and
// falls back to the existing LZ4/raw chunk. Writes are always bounded by the supplied capacity.
inline std::size_t encodeRange10(
    const std::uint8_t* packed, std::size_t samples,
    std::uint8_t* output, std::size_t capacity
) noexcept {
    if (packed == nullptr || output == nullptr || samples == 0u || samples > 262144u) return 0u;
    std::size_t cursor = 0u;
    std::array<std::array<std::uint16_t, 32>, 2> codes{};
    for (std::size_t first = 0u; first < samples; first += kRange10BlockSamples) {
        const auto count = std::min(kRange10BlockSamples, samples - first);
#if defined(__aarch64__) && !defined(NEO_NPV10_FORCE_SCALAR_SPLIT) && \
    !defined(NEO_NPV10_FORCE_LEGACY_RANGE) && defined(__BYTE_ORDER__) && \
    __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
        if (count == kRange10BlockSamples) {
            if (cursor > capacity) return 0u;
            const auto bytes = range10EncodeNeonBlock(packed + first / 4u * 5u,
                output + cursor, capacity - cursor);
            if (bytes == 0u) return 0u;
            cursor += bytes;
            continue;
        }
#endif
        std::uint16_t minima[2] = {1023u, 1023u};
        std::uint16_t maxima[2] = {0u, 0u};
        bool vectorLoaded = false;
#if defined(__aarch64__) && !defined(NEO_NPV10_FORCE_SCALAR_SPLIT) && \
    defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
        if (count == kRange10BlockSamples) {
            alignas(16) std::uint16_t canonical[64];
            if (writeP010FromPackedSimdPrefix(packed + first / 4u * 5u, 64u,
                    reinterpret_cast<std::uint8_t*>(canonical)) == 64u) {
                auto minEven = vdupq_n_u16(1023u), minOdd = minEven;
                auto maxEven = vdupq_n_u16(0u), maxOdd = maxEven;
                for (unsigned group = 0; group < 4u; ++group) {
                    const auto pair = vld2q_u16(canonical + group * 16u);
                    const auto even = vshrq_n_u16(pair.val[0], 6);
                    const auto odd = vshrq_n_u16(pair.val[1], 6);
                    vst1q_u16(codes[0].data() + group * 8u, even);
                    vst1q_u16(codes[1].data() + group * 8u, odd);
                    minEven = vminq_u16(minEven, even); minOdd = vminq_u16(minOdd, odd);
                    maxEven = vmaxq_u16(maxEven, even); maxOdd = vmaxq_u16(maxOdd, odd);
                }
                minima[0] = vminvq_u16(minEven); minima[1] = vminvq_u16(minOdd);
                maxima[0] = vmaxvq_u16(maxEven); maxima[1] = vmaxvq_u16(maxOdd);
                vectorLoaded = true;
            }
        }
#endif
        for (std::size_t i = 0u; !vectorLoaded && i < count; ++i) {
            const auto code = range10PackedCode(packed, first + i);
            const auto lane = i & 1u;
            codes[lane][i / 2u] = code;
            minima[lane] = std::min(minima[lane], code);
            maxima[lane] = std::max(maxima[lane], code);
        }
        for (std::size_t lane = 0u; lane < 2u; ++lane) {
            const auto laneCount = (count + 1u - lane) / 2u;
            const auto minimum = laneCount == 0u ? 0u : minima[lane];
            unsigned bits = 0u;
            for (unsigned span = laneCount == 0u ? 0u : maxima[lane] - minimum;
                 span > 0u; span >>= 1u) ++bits;
            const auto bytes = (laneCount * bits + 7u) / 8u;
            if (cursor > capacity || capacity - cursor < 2u + bytes) return 0u;
            const auto header = minimum | (bits << 10u);
            output[cursor++] = static_cast<std::uint8_t>(header);
            output[cursor++] = static_cast<std::uint8_t>(header >> 8u);
            range10PackBits(bits, codes[lane].data(), laneCount, minimum, output + cursor);
            cursor += bytes;
        }
    }
    return cursor;
}

inline bool decodeRange10(
    const std::uint8_t* input, std::size_t inputBytes, std::size_t samples,
    std::uint8_t* packed, std::size_t packedBytes
) noexcept {
    if (input == nullptr || packed == nullptr || samples == 0u || samples > 262144u ||
        packedBytes != (samples * 10u + 7u) / 8u) return false;
    std::size_t cursor = 0u;
    std::size_t output = 0u;
    std::array<std::array<std::uint16_t, 32>, 2> codes{};
    for (std::size_t first = 0u; first < samples; first += kRange10BlockSamples) {
        const auto count = std::min(kRange10BlockSamples, samples - first);
#if defined(__aarch64__) && !defined(NEO_NPV10_FORCE_SCALAR_SPLIT) && \
    !defined(NEO_NPV10_FORCE_LEGACY_RANGE) && !defined(NEO_NPV10_FORCE_SCALAR_RANGE_DECODE) && \
    defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
        if (count == kRange10BlockSamples) {
            constexpr std::size_t blockBytes = kRange10BlockSamples * 10u / 8u;
            if (cursor > inputBytes || output > packedBytes ||
                blockBytes > packedBytes - output) return false;
            const auto consumed = range10DecodeNeonBlock(input + cursor, inputBytes - cursor,
                packed + output);
            if (consumed == 0u) return false;
            cursor += consumed;
            output += blockBytes;
            continue;
        }
#endif
        for (std::size_t lane = 0u; lane < 2u; ++lane) {
            if (cursor > inputBytes || inputBytes - cursor < 2u) return false;
            const unsigned header = input[cursor] | (input[cursor + 1u] << 8u);
            cursor += 2u;
            const unsigned minimum = header & 1023u;
            const unsigned bits = (header >> 10u) & 15u;
            const auto laneCount = (count + 1u - lane) / 2u;
            if ((header & 0xc000u) != 0u || bits > 10u || (laneCount == 0u && header != 0u)) return false;
            const auto bytes = (laneCount * bits + 7u) / 8u;
            if (cursor > inputBytes || bytes > inputBytes - cursor) return false;
            if (!range10UnpackBits(bits, input + cursor, laneCount, minimum, codes[lane].data())) return false;
            cursor += bytes;
        }
        std::array<std::uint16_t, kRange10BlockSamples> ordered;
        for (std::size_t i = 0u; i < count; ++i) {
            ordered[i] = codes[i & 1u][i / 2u];
        }
        const auto bytes = (count * 10u + 7u) / 8u;
        if (output > packedBytes || bytes > packedBytes - output) return false;
        range10PackLane<10>(ordered.data(), count, 0u, packed + output);
        output += bytes;
    }
    return cursor == inputBytes && output == packedBytes;
}

}  // namespace neoproc::npv10::lossless
