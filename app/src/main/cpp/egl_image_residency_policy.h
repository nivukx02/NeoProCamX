#pragma once

#include <cstddef>

namespace camx {

// This is an import-handle cache, NOT a frame queue. CameraController's strict P010 reader can
// own 55 buffers. A 32-entry cache churns when that existing pool expands under backpressure:
// a cyclic working set larger than the cache reimports nearly every frame. Reserve bounded
// identity coverage for the reader plus producer/prewarm turnover, without acquiring additional
// AHardwareBuffer references, allocating image planes, or increasing the accepted frame ceiling.
struct EglImageResidencyPolicy final {
    static constexpr std::size_t kStrictP010ReaderCeiling = 55;
    static constexpr std::size_t kProducerAndPrewarmSlack = 9;
    static constexpr std::size_t kCapacity =
        kStrictP010ReaderCeiling + kProducerAndPrewarmSlack;

    static constexpr bool needsEviction(std::size_t residentEntries) noexcept {
        return residentEntries > kCapacity;
    }

    static constexpr bool canAdmitWithoutEviction(std::size_t residentEntries) noexcept {
        return residentEntries < kCapacity;
    }
};

static_assert(EglImageResidencyPolicy::kCapacity == 64, "Keep native import residency bounded");

}  // namespace camx
