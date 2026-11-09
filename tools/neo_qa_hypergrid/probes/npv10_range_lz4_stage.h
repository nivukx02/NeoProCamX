#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <limits>
#include "third_party/lz4/lz4.h"

namespace neoproc::npv10::lossless {

/**
 * Optional, bounded second-stage compression of an already exact range block.
 * Retained experiment, NOT enabled in the recording format: actual camera
 * frames did not show sufficient storage savings. It never changes a residual. Zero
 * means retain the original range block, including when the bounded destination
 * cannot achieve the required byte saving. No allocation or retained frame.
 */
inline std::size_t compressExactRangeLz4(
    const std::uint8_t* range, std::size_t rangeBytes,
    std::uint8_t* destination, std::size_t capacity,
    LZ4_stream_t* state, int acceleration = 64
) noexcept {
    constexpr std::size_t kMaximumRangeBytes = 327680u;
    constexpr std::size_t kLengthBytes = 4u;
    if (range == nullptr || destination == nullptr || state == nullptr ||
        rangeBytes < 64u || rangeBytes > kMaximumRangeBytes || capacity <= kLengthBytes ||
        acceleration < 1 || acceleration > 65537) return 0u;
    // At least 1/16 of the original bytes must disappear, including the length
    // prefix. Incompressible sensor noise is kept bit-for-bit in its old form.
    const auto saving = (rangeBytes + 15u) / 16u;
    const auto bound = std::min(capacity, rangeBytes - saving) - kLengthBytes;
    const auto compressed = LZ4_compress_fast_extState(state,
        reinterpret_cast<const char*>(range),
        reinterpret_cast<char*>(destination + kLengthBytes),
        static_cast<int>(rangeBytes), static_cast<int>(bound), acceleration);
    if (compressed <= 0 || static_cast<std::size_t>(compressed) > bound) return 0u;
    destination[0] = static_cast<std::uint8_t>(rangeBytes >> 24u);
    destination[1] = static_cast<std::uint8_t>(rangeBytes >> 16u);
    destination[2] = static_cast<std::uint8_t>(rangeBytes >> 8u);
    destination[3] = static_cast<std::uint8_t>(rangeBytes);
    return kLengthBytes + static_cast<std::size_t>(compressed);
}

} // namespace neoproc::npv10::lossless
