// Exact retained camera samples, not a camera/FPS or thermal qualification.
#include "npv10_lossless_chunk_codec.h"
#include "neo_qa_hypergrid/probes/npv10_range_lz4_stage.h"
#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <stdexcept>
#include <string>
#include <vector>
#include <zlib.h>

namespace {
using Bytes = std::vector<std::uint8_t>;
namespace codec = neoproc::npv10::lossless;
std::uint32_t be32(const std::uint8_t* p) {
    return (std::uint32_t(p[0]) << 24u) | (std::uint32_t(p[1]) << 16u) |
        (std::uint32_t(p[2]) << 8u) | p[3];
}
void require(bool condition, const char* reason) {
    if (!condition) throw std::runtime_error(reason);
}
Bytes read(const char* path) {
    FILE* input = std::fopen(path, "rb");
    require(input != nullptr, "Cannot open exact recorded frame");
    require(std::fseek(input, 0, SEEK_END) == 0, "Cannot seek source");
    const auto bytes = std::ftell(input);
    require(bytes > 0 && bytes <= 4096L * 3072L * 3L && bytes % 2L == 0,
        "Unexpected P010 sample extent");
    std::rewind(input);
    Bytes result(static_cast<std::size_t>(bytes));
    const auto loaded = std::fread(result.data(), 1u, result.size(), input);
    const auto closed = std::fclose(input);
    require(loaded == result.size() && closed == 0, "Truncated source sample");
    return result;
}
Bytes pack(const Bytes& p010) {
    Bytes packed((p010.size() / 2u * 10u + 7u) / 8u, 0u);
    std::uint32_t accumulator = 0;
    unsigned pending = 0;
    std::size_t output = 0;
    for (std::size_t i = 0; i < p010.size(); i += 2u) {
        const unsigned word = p010[i] | (unsigned(p010[i + 1u]) << 8u);
        require((word & 63u) == 0u, "Noncanonical P010 source");
        accumulator |= (word >> 6u) << pending;
        pending += 10u;
        while (pending >= 8u) {
            packed[output++] = static_cast<std::uint8_t>(accumulator);
            accumulator >>= 8u;
            pending -= 8u;
        }
    }
    if (pending != 0u) packed[output] = static_cast<std::uint8_t>(accumulator);
    return packed;
}
}

int main(int argc, char** argv) {
    try {
        require(argc > 1, "Provide SHA-verified recorded P010 samples");
        for (int file = 1; file < argc; ++file) {
            const auto source = read(argv[file]);
            const auto packed = pack(source);
            const auto capacity = codec::maximumStoredBytes(packed.size(), source.size() / 2u);
            require(capacity > 0, "Invalid packet capacity");
            Bytes packet(static_cast<std::size_t>(capacity)), output(327680u), restored(327680u);
            std::copy(packed.begin(), packed.end(), packet.begin());
            const auto encoded = codec::compressPackedTenBitRealtimeRangeLz4InPlace(packet.data(),
                packed.size(), source.size() / 2u, capacity,
                static_cast<std::uint32_t>(::crc32(0, packed.data(), packed.size())), 1024);
            require(encoded.successful, "Baseline encode failed");
            Bytes decoded(source.size());
            require(codec::decodePacketToP010(packet.data(), encoded.storedBytes,
                decoded.data(), decoded.size()) && decoded == source, "Baseline changed a source bit");
            for (const int acceleration : {16, 64, 256}) {
                std::size_t selectedBytes = 0u, selectedChunks = 0u, ranges = 0u;
                double totalMs = 0.0, maximumMs = 0.0;
                LZ4_stream_t state{};
                for (int round = 0; round < 12; ++round) {
                    std::size_t cursor = codec::kFrameHeaderBytes;
                    selectedBytes = codec::kFrameHeaderBytes;
                    selectedChunks = ranges = 0u;
                    double roundMs = 0.0;
                    while (cursor < static_cast<std::size_t>(encoded.storedBytes)) {
                        require(cursor + codec::kChunkHeaderBytes <= static_cast<std::size_t>(encoded.storedBytes),
                            "Truncated baseline chunk header");
                        const auto flags = be32(packet.data() + cursor);
                        const auto bytes = be32(packet.data() + cursor + 8u);
                        cursor += codec::kChunkHeaderBytes;
                        require(bytes > 0 && bytes <= static_cast<std::size_t>(encoded.storedBytes) - cursor,
                            "Invalid baseline chunk body");
                        std::size_t candidate = 0u;
                        if (flags == 2u) {
                            ++ranges;
                            const auto start = std::chrono::steady_clock::now();
                            candidate = codec::compressExactRangeLz4(packet.data() + cursor, bytes,
                                output.data(), output.size(), &state, acceleration);
                            roundMs += std::chrono::duration<double, std::milli>(
                                std::chrono::steady_clock::now() - start).count();
                            if (candidate != 0u) {
                                require(be32(output.data()) == bytes, "Second-stage length changed");
                                require(LZ4_decompress_safe(reinterpret_cast<const char*>(output.data() + 4u),
                                    reinterpret_cast<char*>(restored.data()), candidate - 4u, bytes) == int(bytes) &&
                                    std::memcmp(restored.data(), packet.data() + cursor, bytes) == 0,
                                    "Second stage changed range headers or residuals");
                                ++selectedChunks;
                            }
                        }
                        selectedBytes += codec::kChunkHeaderBytes + (candidate == 0u ? bytes : candidate);
                        cursor += bytes;
                    }
                    totalMs += roundMs;
                    maximumMs = std::max(maximumMs, roundMs);
                }
                std::printf("REAL_RANGE_LZ4 sample=%s acceleration=%d original_bytes=%lld selected_bytes=%zu "
                    "saved_percent=%.6f selected_chunks=%zu range_chunks=%zu added_mean_ms=%.6f "
                    "added_max_ms=%.6f exact_range_bytes=true exact_source_codes=true camera_fps=NOT_TESTED\n",
                    argv[file], acceleration, static_cast<long long>(encoded.storedBytes), selectedBytes,
                    100.0 * (double(encoded.storedBytes) - double(selectedBytes)) / double(encoded.storedBytes),
                    selectedChunks, ranges, totalMs / 12.0, maximumMs);
            }
        }
        return 0;
    } catch (const std::exception& error) {
        std::fprintf(stderr, "FAIL exact_range_lz4_probe: %s\n", error.what());
        return 1;
    }
}
