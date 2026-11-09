#pragma once

#include "npv10_lossless_chunk_codec.h"

namespace neoproc::npv10::lossless {

struct GpuP010Input {
    const std::uint8_t* y = nullptr;
    const std::uint8_t* uv = nullptr;
    std::int64_t yCapacity = 0;
    std::int64_t uvCapacity = 0;
    std::int32_t width = 0;
    std::int32_t height = 0;
    std::int32_t yRowStride = 0;
    std::int32_t uvRowStride = 0;
};

struct GpuRangeTimings {
    std::int64_t uploadNs = 0;
    std::int64_t originalPackAndCrcNs = 0;
    std::int64_t completionWaitNs = 0;
    std::int64_t assemblyNs = 0;
    std::int64_t totalNs = 0;
};

using OriginalP010Packer = bool (*)(const GpuP010Input&, std::uint8_t*, std::int64_t,
                                  std::uint32_t*);

/** Preallocate one bounded GPU workspace. Does not enable or change a recording route. */
bool prepareGpuRangeEncoder(std::int32_t width, std::int32_t height) noexcept;

/**
 * One exclusive dispatch; copied original input, CPU-authoritative packed CRC, complete GPU
 * fence and unchanged NLC2 encoder. Total timing includes the GPU lock, upload and completion.
 * Unsupported admission or any execution fault returns failure, never lower-quality pixels.
 */
EncodeResult encodeP010WithGpuRange(const GpuP010Input& input, std::uint8_t* packet,
    std::int64_t capacity, int acceleration, OriginalP010Packer packOriginal,
    GpuRangeTimings* timings) noexcept;

/** Only after users have joined; does not terminate the display shared with preview. */
void closeGpuRangeEncoder() noexcept;

} // namespace neoproc::npv10::lossless
