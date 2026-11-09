#pragma once

#include <cstddef>
#include <cstdint>

namespace neoproc::npv10::lossless {

struct GpuRangeBlocksView;

constexpr std::int32_t kChunkSamples = 262144;
constexpr std::int32_t kFrameHeaderBytes = 32;
// flags/u32, sampleCount/u32, storedBytes/u32. A 32-bit count is required because one full
// production chunk contains 262,144 samples and cannot be represented by the previous uint16.
constexpr std::int32_t kChunkHeaderBytes = 12;

struct EncodeResult {
    bool successful = false;
    std::int64_t storedBytes = 0;
    std::uint32_t storedCrc32 = 0;
    std::uint32_t packedCrc32 = 0;
    std::int32_t compressedChunks = 0;
    std::int32_t rawChunks = 0;
    // Bytes read directly from the exclusively owned input slot instead of copied to scratch.
    // Diagnostic only: this does not change the stored packet or its integrity fields.
    std::int64_t borrowedInputBytes = 0;
};

/**
 * Proves that publishing the pending record and a worst-case LZ4 body cannot touch the
 * current source chunk. Subtractions avoid wrapping offsets on either 32- or 64-bit ABIs.
 * The LZ4 expansion allowance is needed even when an expanded result will be discarded.
 */
constexpr bool canBorrowPackedChunkInput(
    std::size_t sourceOffset, std::size_t sourceBytes,
    std::size_t outputOffset, std::size_t pendingBytes
) noexcept {
    if (sourceBytes == 0u || outputOffset > sourceOffset ||
        pendingBytes > sourceOffset - outputOffset) return false;
    const auto prefix = sourceOffset - outputOffset - pendingBytes;
    if (prefix < static_cast<std::size_t>(kChunkHeaderBytes)) return false;
    const auto bodyCapacity = prefix - static_cast<std::size_t>(kChunkHeaderBytes);
    if (sourceBytes > bodyCapacity) return false;
    return sourceBytes / 255u + 16u <= bodyCapacity - sourceBytes;
}

std::int64_t maximumStoredBytes(
    std::int64_t packedBytes,
    std::int64_t sampleCount
) noexcept;

/**
 * Reversibly transforms and compresses a packed-P010 frame in its existing guarded DMC slot.
 * The slot must provide [maximumStoredBytes] capacity. No precision, sample, or metadata is lost.
 */
EncodeResult compressPackedTenBitInPlace(
    std::uint8_t* packet,
    std::int64_t packedBytes,
    std::int64_t sampleCount,
    std::int64_t capacity,
    std::uint32_t packedCrc32,
    bool upstreamLz4 = false
) noexcept;

/** Bounded upstream LZ4 search with the same bit-exact NLC2 raw-chunk escape path. */
EncodeResult compressPackedTenBitRealtimeLz4InPlace(
    std::uint8_t* packet,
    std::int64_t packedBytes,
    std::int64_t sampleCount,
    std::int64_t capacity,
    std::uint32_t packedCrc32
) noexcept;

/** Explicit bounded LZ4 effort; identical samples, format, CRC and raw-chunk escape path. */
EncodeResult compressPackedTenBitRealtimeLz4InPlace(
    std::uint8_t* packet,
    std::int64_t packedBytes,
    std::int64_t sampleCount,
    std::int64_t capacity,
    std::uint32_t packedCrc32,
    int acceleration
) noexcept;

/** NLC2 v2: exact 64-sample range packing, with the existing LZ4/raw escape route. */
EncodeResult compressPackedTenBitRealtimeRangeLz4InPlace(
    std::uint8_t* packet,
    std::int64_t packedBytes,
    std::int64_t sampleCount,
    std::int64_t capacity,
    std::uint32_t packedCrc32,
    int acceleration
) noexcept;

/** Uses independently GPU-produced range blocks with the ORIGINAL CPU-packed source CRC. */
EncodeResult compressPackedTenBitGpuRangeLz4InPlace(
    std::uint8_t* packet, std::int64_t packedBytes, std::int64_t sampleCount,
    std::int64_t capacity, std::uint32_t packedCrc32, int acceleration,
    const GpuRangeBlocksView& gpuBlocks
) noexcept;

/**
 * Fused canonical Camera2 P010 -> existing NLC2 v2 packet. Standard two-plane,
 * 64-sample-aligned rows only; other layouts retain the established packer.
 * Uses the existing bounded one-chunk workspace, never a second full frame.
 * Source ownership MUST remain valid until this call returns. The caller must
 * then release the camera image before publishing the prepared packet.
 */
EncodeResult compressP010PlanesRealtimeRangeLz4(
    const std::uint8_t* y, std::int64_t yCapacity,
    const std::uint8_t* uv, std::int64_t uvCapacity,
    std::int32_t width, std::int32_t height,
    std::int32_t yRowStride, std::int32_t uvRowStride,
    std::uint8_t* packet, std::int64_t capacity, int acceleration
) noexcept;

/** Reuses the already bounded native capture helper pool; dispatcher must join ALL users. */
using LosslessChunkDispatcher = bool (*)(void*, void (*)(void*) noexcept) noexcept;

bool prepareP010LosslessChunkWorkspace() noexcept;

/** Exact NLC2 chunks in disjoint ranges of the SAME output slot, compacted after the join. */
EncodeResult compressP010PlanesParallelRangeLz4(
    const std::uint8_t* y, std::int64_t yCapacity,
    const std::uint8_t* uv, std::int64_t uvCapacity,
    std::int32_t width, std::int32_t height,
    std::int32_t yRowStride, std::int32_t uvRowStride,
    std::uint8_t* packet, std::int64_t capacity, int acceleration,
    LosslessChunkDispatcher dispatch
) noexcept;

/**
 * Frames an already packed ten-bit payload as raw NLC2 chunks in place.
 *
 * This is the deterministic realtime recording route. It walks the source backwards, inserts the
 * NLC2 headers without allocating or transforming pixels, and never performs entropy search on a
 * capture worker. The representation remains bit-exact and is decoded by the same NLC2 decoder;
 * adaptive compression remains available to offline conversion through
 * [compressPackedTenBitInPlace].
 */
EncodeResult framePackedTenBitRealtimeInPlace(
    std::uint8_t* packet,
    std::int64_t packedBytes,
    std::int64_t sampleCount,
    std::int64_t capacity,
    std::uint32_t packedCrc32
) noexcept;

/** Decodes an NLC2 packet directly to canonical little-endian P010 and verifies its source CRC. */
bool decodePacketToP010(
    const std::uint8_t* packet,
    std::int64_t packetBytes,
    std::uint8_t* p010,
    std::int64_t p010Bytes
) noexcept;

}  // namespace neoproc::npv10::lossless
