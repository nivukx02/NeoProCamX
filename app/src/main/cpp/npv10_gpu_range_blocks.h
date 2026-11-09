#pragma once

#include <cstddef>
#include <cstdint>
#include <cstring>

namespace neoproc::npv10::lossless {

/** Integer-only GPU result; canonical source bytes/CRC are still produced independently on CPU. */
struct GpuRangeBlocksView {
    static constexpr std::size_t kWordsPerBlock = 23u;
    const std::uint32_t* words = nullptr;
    std::size_t wordCount = 0u;
    std::uint32_t frameToken = 0u;
    // Nonzero only for the second, integer-only compact dispatch. Each chunk slot has four
    // metadata uints followed by its exact NLC2 range body; zero retains the reference block view.
    std::size_t chunkSlotWords = 0u;

    bool validFor(std::size_t samples) const noexcept {
        if (chunkSlotWords != 0u) {
            constexpr std::size_t chunkSamples = 262144u;
            const auto inSlot = samples < chunkSamples ? samples : chunkSamples;
            const auto chunks = (samples + chunkSamples - 1u) / chunkSamples;
            return words != nullptr && samples > 0u && samples % 64u == 0u &&
                chunkSlotWords == inSlot / 16u * 5u + 4u &&
                chunks <= wordCount / chunkSlotWords && wordCount == chunks * chunkSlotWords;
        }
        return words != nullptr && samples > 0u && samples % 64u == 0u &&
            samples / 64u <= wordCount / kWordsPerBlock &&
            wordCount == samples / 64u * kWordsPerBlock;
    }

    bool copyChunk(std::size_t chunk, std::size_t samples, std::uint8_t* output,
                   std::size_t capacity, std::size_t* copied) const noexcept {
        if (copied == nullptr) return false;
        *copied = 0u;
        if (words == nullptr || output == nullptr || samples == 0u || samples > 262144u ||
            samples % 64u != 0u) return false;
        if (chunkSlotWords != 0u) {
            if (chunkSlotWords < 4u || chunk >= wordCount / chunkSlotWords) return false;
            const auto* header = words + chunk * chunkSlotWords;
            const auto bytes = static_cast<std::size_t>(header[0]);
            if (header[1] != samples || header[2] != (frameToken ^ static_cast<std::uint32_t>(chunk)) ||
                header[3] != 0u || bytes % 4u != 0u || bytes >= samples / 4u * 5u ||
                bytes / 4u > chunkSlotWords - 4u || bytes > capacity) return false;
            if (bytes != 0u) std::memcpy(output, header + 4u, bytes);
            *copied = bytes;
            return true;
        }
        for (std::size_t offset = 0u; offset < samples; offset += 64u) {
            std::size_t bytes = 0u;
            if (!copyBlock(chunk * (262144u / 64u) + offset / 64u,
                    output + *copied, capacity - *copied, &bytes)) return false;
            if (bytes == 0u) { *copied = 0u; return true; }
            *copied += bytes;
        }
        return true;
    }

    // A complete block is at most 84 bytes. Bounds, generation and both lane headers are checked
    // before writing; stale GPU output cannot masquerade as another frame's encoded blocks.
    bool copyBlock(std::size_t block, std::uint8_t* output, std::size_t capacity,
                   std::size_t* copied) const noexcept {
        if (copied == nullptr) return false;
        *copied = 0u;
        if (words == nullptr || output == nullptr || chunkSlotWords != 0u || block >= wordCount / kWordsPerBlock)
            return false;
        const auto* input = words + block * kWordsPerBlock;
        if (input[22] != (frameToken ^ static_cast<std::uint32_t>(block))) return false;
        const unsigned headers[2] = {input[0], input[1]};
        if ((headers[0] & ~0x3fffu) != 0u || (headers[1] & ~0x3fffu) != 0u) return false;
        const unsigned widths[2] = {headers[0] >> 10u, headers[1] >> 10u};
        if (widths[0] > 10u || widths[1] > 10u) return false;
        const auto bytes = 4u + 4u * (widths[0] + widths[1]);
        if (bytes > capacity) return true; // Existing exact LZ4/raw escape, never quantization.
        std::size_t cursor = 0u;
        for (unsigned lane = 0u; lane < 2u; ++lane) {
            output[cursor++] = static_cast<std::uint8_t>(headers[lane]);
            output[cursor++] = static_cast<std::uint8_t>(headers[lane] >> 8u);
#if defined(__BYTE_ORDER__) && __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
            // Android's admitted ABIs store these uints in the exact NLC2 residual byte order.
            // One bounded memcpy avoids millions of individual byte loads from GPU readback.
            const auto residualBytes = static_cast<std::size_t>(widths[lane]) * 4u;
            std::memcpy(output + cursor, input + 2u + lane * 10u, residualBytes);
            cursor += residualBytes;
#else
            for (unsigned word = 0u; word < widths[lane]; ++word) {
                const auto value = input[2u + lane * 10u + word];
                output[cursor++] = static_cast<std::uint8_t>(value);
                output[cursor++] = static_cast<std::uint8_t>(value >> 8u);
                output[cursor++] = static_cast<std::uint8_t>(value >> 16u);
                output[cursor++] = static_cast<std::uint8_t>(value >> 24u);
            }
#endif
        }
        *copied = cursor;
        return true;
    }
};

} // namespace neoproc::npv10::lossless
