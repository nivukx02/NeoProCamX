#pragma once

#include "npv10_range10_codec.h"

// Candidate only: not an enabled NLC2 flag, application decoder or recording route.
// Each 64-code block has independent even/odd predictors, including interleaved U/V.
// A lane header stores its first original code and residual width. Residual zero is
// mandatory at the first position. Later codes are signed modulo-1024 differences,
// mapped bijectively to 0..1023. No frame/row history, quantizer or rounding is used.
namespace neoproc::npv10::spatial_probe {

inline unsigned residual(unsigned value, unsigned previous) noexcept {
    const unsigned modulo = (value - previous) & 1023u;
    return modulo >= 512u ? 2u * (1024u - modulo) - 1u : 2u * modulo;
}

inline unsigned restore(unsigned code, unsigned previous) noexcept {
    const unsigned delta = (code & 1u) ? 1024u - ((code + 1u) / 2u) : code / 2u;
    return (previous + delta) & 1023u;
}

#if defined(__aarch64__) && !defined(NEO_NPV10_FORCE_SCALAR_SPLIT) && \
    defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
inline unsigned transformLane(lossless::Range10NeonLane& lane) noexcept {
    const unsigned origin = vgetq_lane_u16(lane.values[0], 0);
    auto previous = vdupq_n_u16(static_cast<std::uint16_t>(origin));
    for (auto& values : lane.values) {
        const auto input = values;
        const auto adjacent = vextq_u16(previous, input, 7);
        previous = input;
        const auto difference = vsubq_u16(input, adjacent);
        // Shifting left and arithmetically right sign-extends precisely ten bits.
        const auto signedTen = vshrq_n_s16(vreinterpretq_s16_u16(
            vshlq_n_u16(difference, 6)), 6);
        values = veorq_u16(vshlq_n_u16(vreinterpretq_u16_s16(signedTen), 1),
            vreinterpretq_u16_s16(vshrq_n_s16(signedTen, 15)));
    }
    return origin;
}

inline std::size_t encodeBlock(const std::uint8_t* input, std::uint8_t* output,
                               std::size_t capacity) noexcept {
    lossless::Range10NeonLane lanes[2];
    lossless::range10LoadNeonHalf(input, lanes[0], lanes[1], 0u);
    lossless::range10LoadNeonHalf(input + 40u, lanes[0], lanes[1], 1u);
    unsigned origins[2], widths[2];
    for (unsigned lane = 0u; lane < 2u; ++lane) {
        origins[lane] = transformLane(lanes[lane]);
        const auto& v = lanes[lane].values;
        const unsigned maximum = vmaxvq_u16(vmaxq_u16(vmaxq_u16(v[0], v[1]),
            vmaxq_u16(v[2], v[3])));
        widths[lane] = maximum == 0u ? 0u : 32u - static_cast<unsigned>(__builtin_clz(maximum));
    }
    const auto bytes = 4u + 4u * (widths[0] + widths[1]);
    if (bytes > capacity) return 0u;
    std::size_t cursor = 0u;
    for (unsigned lane = 0u; lane < 2u; ++lane) {
        const auto header = origins[lane] | (widths[lane] << 10u);
        output[cursor++] = static_cast<std::uint8_t>(header);
        output[cursor++] = static_cast<std::uint8_t>(header >> 8u);
        lossless::range10StoreNeonBits(widths[lane], lanes[lane], 0u, output + cursor);
        cursor += 4u * widths[lane];
    }
    return cursor;
}
#endif

inline std::size_t encode(const std::uint8_t* input, std::size_t samples,
                          std::uint8_t* output, std::size_t capacity) noexcept {
    if (input == nullptr || output == nullptr || samples == 0u || samples > 262144u) return 0u;
    std::size_t cursor = 0u;
    for (std::size_t first = 0u; first < samples; first += 64u) {
        const auto count = std::min<std::size_t>(64u, samples - first);
#if defined(__aarch64__) && !defined(NEO_NPV10_FORCE_SCALAR_SPLIT) && \
    defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
        if (count == 64u) {
            if (cursor > capacity) return 0u;
            const auto size = encodeBlock(input + first / 4u * 5u, output + cursor, capacity - cursor);
            if (size == 0u) return 0u;
            cursor += size;
            continue;
        }
#endif
        for (unsigned lane = 0u; lane < 2u; ++lane) {
            const auto laneCount = (count + 1u - lane) / 2u;
            const auto origin = laneCount == 0u ? 0u : lossless::range10PackedCode(input, first + lane);
            unsigned previous = origin, maximum = 0u;
            std::array<std::uint16_t, 32> values{};
            for (std::size_t i = 0u; i < laneCount; ++i) {
                const auto value = lossless::range10PackedCode(input, first + lane + 2u * i);
                values[i] = static_cast<std::uint16_t>(residual(value, previous));
                maximum = std::max(maximum, static_cast<unsigned>(values[i]));
                previous = value;
            }
            unsigned width = 0u;
            for (auto span = maximum; span != 0u; span >>= 1u) ++width;
            const auto bytes = (laneCount * width + 7u) / 8u;
            if (cursor > capacity || capacity - cursor < 2u + bytes) return 0u;
            const auto header = origin | (width << 10u);
            output[cursor++] = static_cast<std::uint8_t>(header);
            output[cursor++] = static_cast<std::uint8_t>(header >> 8u);
            lossless::range10PackBits(width, values.data(), laneCount, 0u, output + cursor);
            cursor += bytes;
        }
    }
    return cursor;
}

// Deliberately independent scalar reconstruction for this device experiment.
inline bool decode(const std::uint8_t* input, std::size_t bytes, std::size_t samples,
                    std::uint8_t* packed, std::size_t packedBytes) noexcept {
    if (input == nullptr || packed == nullptr || samples == 0u || samples > 262144u ||
        packedBytes != (samples * 10u + 7u) / 8u) return false;
    std::size_t cursor = 0u, written = 0u;
    std::uint32_t accumulator = 0u;
    unsigned pending = 0u;
    for (std::size_t first = 0u; first < samples; first += 64u) {
        const auto count = std::min<std::size_t>(64u, samples - first);
        std::array<std::array<std::uint16_t, 32>, 2> lanes{};
        for (unsigned lane = 0u; lane < 2u; ++lane) {
            if (cursor > bytes || bytes - cursor < 2u) return false;
            const unsigned header = input[cursor] | (input[cursor + 1u] << 8u);
            cursor += 2u;
            const auto countInLane = (count + 1u - lane) / 2u;
            const auto width = (header >> 10u) & 15u;
            unsigned previous = header & 1023u;
            const auto required = (countInLane * width + 7u) / 8u;
            if ((header & 0xc000u) != 0u || width > 10u || required > bytes - cursor ||
                (countInLane == 0u && header != 0u) ||
                !lossless::range10UnpackBits(width, input + cursor, countInLane, 0u, lanes[lane].data()))
                return false;
            cursor += required;
            if (countInLane > 0u && lanes[lane][0] != 0u) return false;
            for (std::size_t i = 0u; i < countInLane; ++i) {
                previous = restore(lanes[lane][i], previous);
                lanes[lane][i] = static_cast<std::uint16_t>(previous);
            }
        }
        for (std::size_t i = 0u; i < count; ++i) {
            accumulator |= static_cast<std::uint32_t>(lanes[i & 1u][i / 2u]) << pending;
            pending += 10u;
            while (pending >= 8u) {
                packed[written++] = static_cast<std::uint8_t>(accumulator);
                accumulator >>= 8u;
                pending -= 8u;
            }
        }
    }
    if (pending != 0u) packed[written++] = static_cast<std::uint8_t>(accumulator);
    return cursor == bytes && written == packedBytes;
}

} // namespace neoproc::npv10::spatial_probe
