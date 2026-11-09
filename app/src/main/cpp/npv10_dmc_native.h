#pragma once

#include <cstdint>

namespace neoproc::npv10 {

namespace lossless { struct EncodeResult; }

struct DirectMappedPayloadView {
    std::uint8_t* data = nullptr;
    std::int64_t capacity = 0;
    bool fusedLosslessPreparation = false;
    int losslessAcceleration = 1024;
};

struct DirectMappedPreparedPayload {
    std::int64_t storedBytes = 0;
    std::uint32_t storedCrc32 = 0;
    std::int32_t compressedChunks = 0;
    std::int32_t rawChunks = 0;
};

/**
 * Claims a pre-faulted staging slot for the fused native pixel processor. A successful claim must be paired
 * exactly once with finishDirectMappedPayloadProcessing(), including on processing failure.
 */
bool acquireDirectMappedPayload(
    std::int64_t handle,
    std::int64_t frameIndex,
    std::int64_t requiredBytes,
    DirectMappedPayloadView* output,
    bool requestFusedLosslessPreparation = false
) noexcept;

/** Publishes the CRC and PREPARED state, or atomically retires the slot on failure. */
bool finishDirectMappedPayloadProcessing(
    std::int64_t handle,
    std::int64_t frameIndex,
    bool successful,
    std::uint32_t packedCrc32,
    DirectMappedPreparedPayload* output
) noexcept;

/**
 * Finish the same PROCESSING lease after direct-plane lossless preparation. The
 * source-release callback must have completed first. On failure the slot is retired,
 * never compressed again and never published. Shares normal commit/close ownership.
 */
bool finishDirectMappedEncodedPayloadProcessing(
    std::int64_t handle, std::int64_t frameIndex, bool sourceReleasedSuccessfully,
    const lossless::EncodeResult& encoded, std::int64_t preparationElapsedNs,
    DirectMappedPreparedPayload* output
) noexcept;

}  // namespace neoproc::npv10
