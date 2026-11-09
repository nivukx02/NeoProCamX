// Native codec correctness/throughput probe. NOT a camera-FPS or image-quality test.
#include "npv10_lossless_chunk_codec.h"
#include "neo_qa_hypergrid/probes/parallel_range/npv10_parallel_range_encoder.h"
#include "third_party/lz4/lz4.h"

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
using Clock = std::chrono::steady_clock;
namespace codec = neoproc::npv10::lossless;

void require(bool condition, const char* reason) {
    if (!condition) throw std::runtime_error(reason);
}

std::uint32_t crc(const Bytes& bytes) {
    return static_cast<std::uint32_t>(::crc32(
        ::crc32(0L, Z_NULL, 0), bytes.data(), static_cast<uInt>(bytes.size())
    ));
}

Bytes pack(const Bytes& p010) {
    const std::size_t samples = p010.size() / 2u;
    Bytes packed((samples * 10u + 7u) / 8u, 0u);
    std::uint64_t pending = 0u;
    unsigned bits = 0u;
    std::size_t target = 0u;
    for (std::size_t i = 0; i < samples; ++i) {
        const std::uint16_t word = static_cast<std::uint16_t>(
            p010[2u * i] | (p010[2u * i + 1u] << 8u)
        );
        require((word & 63u) == 0u, "Input is not canonical ten-bit P010");
        pending |= static_cast<std::uint64_t>(word >> 6u) << bits;
        bits += 10u;
        while (bits >= 8u) {
            packed[target++] = static_cast<std::uint8_t>(pending);
            pending >>= 8u;
            bits -= 8u;
        }
    }
    if (bits > 0u) packed[target++] = static_cast<std::uint8_t>(pending);
    require(target == packed.size(), "Packing length mismatch");
    return packed;
}

Bytes synthetic(std::size_t samples, unsigned pattern) {
    Bytes p010(samples * 2u);
    std::uint32_t state = 0x51A7943u;
    for (std::size_t i = 0u; i < samples; ++i) {
        state ^= state << 13u;
        state ^= state >> 17u;
        state ^= state << 5u;
        const std::uint16_t code = static_cast<std::uint16_t>(
            pattern == 0u ? 512u : pattern == 1u ? (state & 1023u) :
            pattern == 2u ? 256u + (state & 63u) : ((i / 64u) & 1023u)
        );
        const std::uint16_t word = static_cast<std::uint16_t>(code << 6u);
        p010[2u * i] = static_cast<std::uint8_t>(word);
        p010[2u * i + 1u] = static_cast<std::uint8_t>(word >> 8u);
    }
    return p010;
}

void benchmark(const Bytes& original, const std::string& label, int iterations, int selectedBackend = -1,
               bool parallelRange = false) {
    const Bytes packed = pack(original);
    const auto samples = static_cast<std::int64_t>(original.size() / 2u);
    const auto capacity = codec::maximumStoredBytes(static_cast<std::int64_t>(packed.size()), samples);
    require(capacity > 0, "Invalid packet capacity");
    const auto packedCrc = crc(packed);
    codec::ParallelRangeEncoder parallelEncoder;
    if (parallelRange) require(parallelEncoder.initialize(packed.size(), samples, capacity),
        "Parallel encoder did not initialize");
    Bytes defaultPacket;
    for (unsigned backend = 0u; backend < 8u; ++backend) {
        if (selectedBackend >= 0 && static_cast<int>(backend) != selectedBackend) continue;
        Bytes packet(static_cast<std::size_t>(capacity) + 64u, 0xA7u);
        Bytes decoded(original.size());
        double encodeMs = 0.0;
        double decodeMs = 0.0;
        double maxEncodeMs = 0.0;
        codec::EncodeResult result;
        for (int iteration = 0; iteration < iterations; ++iteration) {
            std::memcpy(packet.data(), packed.data(), packed.size());
            const auto started = Clock::now();
            if (backend == 0u) {
                result = codec::framePackedTenBitRealtimeInPlace(
                    packet.data(), static_cast<std::int64_t>(packed.size()), samples, capacity, packedCrc
                );
            } else if (backend == 1u) {
                result = codec::compressPackedTenBitInPlace(
                    packet.data(), static_cast<std::int64_t>(packed.size()), samples, capacity, packedCrc
                );
            } else if (backend == 2u) {
                result = codec::compressPackedTenBitRealtimeLz4InPlace(
                    packet.data(), static_cast<std::int64_t>(packed.size()), samples, capacity, packedCrc
                );
            } else if (backend == 7u) {
                if (parallelRange) {
                    require(parallelEncoder.tryEncode(packet.data(), packed.size(), samples,
                        capacity, packedCrc, 1024, result), "Parallel encoder was not admitted");
                } else result = codec::compressPackedTenBitRealtimeRangeLz4InPlace(
                    packet.data(), static_cast<std::int64_t>(packed.size()), samples, capacity,
                    packedCrc, 1024
                );
            } else {
                result = codec::compressPackedTenBitRealtimeLz4InPlace(
                    packet.data(), static_cast<std::int64_t>(packed.size()), samples, capacity,
                    packedCrc, backend == 3u ? 1024 : backend == 4u ? 64 : backend == 5u ? 16 : 1
                );
            }
            const double elapsed = std::chrono::duration<double, std::milli>(Clock::now() - started).count();
            encodeMs += elapsed;
            maxEncodeMs = std::max(maxEncodeMs, elapsed);
            require(result.successful, "Encode failed");
            require(result.storedBytes <= capacity, "Nonexpansion bound exceeded");
            require(std::all_of(packet.begin() + capacity, packet.end(),
                [](std::uint8_t value) { return value == 0xA7u; }), "Packet wrote past guard");
            const auto decodeStarted = Clock::now();
            require(codec::decodePacketToP010(packet.data(), result.storedBytes,
                decoded.data(), static_cast<std::int64_t>(decoded.size())), "Decode/CRC failed");
            decodeMs += std::chrono::duration<double, std::milli>(Clock::now() - decodeStarted).count();
            require(decoded == original, "Decoded sensor codes changed");
            if (backend == 2u) {
                defaultPacket.assign(packet.begin(), packet.begin() + result.storedBytes);
            }
#if !defined(NEO_NPV10_REALTIME_LZ4_ACCELERATION)
            if (backend == 3u) {
                require(defaultPacket.size() == static_cast<std::size_t>(result.storedBytes) &&
                    std::equal(defaultPacket.begin(), defaultPacket.end(), packet.begin()),
                    "Explicit fast effort changed the existing default packet");
            }
#endif
        }
        packet[28] ^= 1u;
        require(!codec::decodePacketToP010(packet.data(), result.storedBytes,
            decoded.data(), static_cast<std::int64_t>(decoded.size())), "Corrupt CRC accepted");
        packet[28] ^= 1u;
        require(!codec::decodePacketToP010(packet.data(), result.storedBytes - 1,
            decoded.data(), static_cast<std::int64_t>(decoded.size())), "Truncated packet accepted");
        const char* name = parallelRange ? "parallel_range64_lz4_escape" :
            backend == 0u ? "raw_nlc2" : backend == 1u ? "legacy_lz4" :
            backend == 2u ? "upstream_lz4" : backend == 3u ? "explicit_fast_1024" :
            backend == 4u ? "storage_pressure_64" : backend == 5u ? "storage_pressure_16" :
            backend == 6u ? "storage_pressure_1" : "range64_lz4_escape";
        std::printf(
            "{\"fixture\":\"%s\",\"backend\":\"%s\",\"iterations\":%d,"
            "\"samples\":%lld,\"packed_bytes\":%zu,\"stored_bytes\":%lld,"
            "\"compressed_chunks\":%d,\"raw_chunks\":%d,\"encode_mean_ms\":%.4f,"
            "\"encode_max_ms\":%.4f,\"decode_mean_ms\":%.4f,\"bit_exact\":true,"
            "\"corruption_rejected\":true,\"truncation_rejected\":true,"
            "\"stored_crc32\":%u,\"source_crc32\":%u}\n",
            label.c_str(), name, iterations, static_cast<long long>(samples), packed.size(),
            static_cast<long long>(result.storedBytes), result.compressedChunks, result.rawChunks,
            encodeMs / iterations, maxEncodeMs, decodeMs / iterations,
            result.storedCrc32, result.packedCrc32
        );
        std::fflush(stdout);
    }
}

void readExactly(void* output, std::size_t bytes) {
    require(std::fread(output, 1u, bytes, stdin) == bytes, "Truncated input fixture");
}

// Uses actual decoded camera samples only when explicitly requested. This does not measure camera
// cadence. Every proposed byte permutation is independently reversed before it can inform a fix.
void benchmarkRecordedBitplanes(const Bytes& original, const std::string& label) {
    constexpr std::size_t chunkSamples = 262144u;
    const std::size_t samples = original.size() / 2u;
    Bytes split(chunkSamples * 5u / 4u);
    Bytes compressed(static_cast<std::size_t>(LZ4_compressBound(static_cast<int>(split.size()))));
    Bytes restored(split.size());
    for (const unsigned shift : {0u, 2u}) {
        for (const int effort : {1024, 64, 16, 1}) {
            std::size_t bytes = codec::kFrameHeaderBytes;
            double encodeMs = 0.0;
            double maxMs = 0.0;
            for (unsigned repeat = 0; repeat < 3u; ++repeat) {
                bytes = codec::kFrameHeaderBytes;
                const auto started = Clock::now();
                for (std::size_t first = 0; first < samples; first += chunkSamples) {
                    const std::size_t count = std::min(chunkSamples, samples - first);
                    const std::size_t extent = (count * 10u + 7u) / 8u;
                    std::fill(split.begin() + count, split.begin() + extent, 0u);
                    for (std::size_t i = 0; i < count; ++i) {
                        const std::size_t index = (first + i) * 2u;
                        const auto code = static_cast<std::uint16_t>(
                            (original[index] | (original[index + 1u] << 8u)) >> 6u);
                        split[i] = static_cast<std::uint8_t>(code >> shift);
                        const auto pair = shift == 0u ? code >> 8u : code & 3u;
                        split[count + i / 4u] |= static_cast<std::uint8_t>(pair << ((i & 3u) * 2u));
                    }
                    const int stored = LZ4_compress_fast(
                        reinterpret_cast<const char*>(split.data()),
                        reinterpret_cast<char*>(compressed.data()), static_cast<int>(extent),
                        static_cast<int>(compressed.size()), effort);
                    require(stored > 0, "Recorded bitplane LZ4 failed");
                    bytes += codec::kChunkHeaderBytes + std::min(extent, static_cast<std::size_t>(stored));
                    require(LZ4_decompress_safe(reinterpret_cast<const char*>(compressed.data()),
                        reinterpret_cast<char*>(restored.data()), stored, static_cast<int>(extent)) ==
                        static_cast<int>(extent), "Recorded bitplane decode failed");
                    for (std::size_t i = 0; i < count; ++i) {
                        const auto pair = (restored[count + i / 4u] >> ((i & 3u) * 2u)) & 3u;
                        const unsigned code = shift == 0u ? restored[i] | (pair << 8u) :
                            (restored[i] << 2u) | pair;
                        const std::size_t index = (first + i) * 2u;
                        require((code << 6u) == static_cast<unsigned>(original[index] |
                            (original[index + 1u] << 8u)), "Recorded sample changed");
                    }
                }
                const double ms = std::chrono::duration<double, std::milli>(Clock::now() - started).count();
                encodeMs += ms;
                maxMs = std::max(maxMs, ms);
            }
            std::printf("RECORDED_BITPLANE fixture=%s shift=%u effort=%d bytes=%zu "
                "encode_decode_verify_mean_ms=%.3f max_ms=%.3f bit_exact=true\n",
                label.c_str(), shift, effort, bytes, encodeMs / 3.0, maxMs);
            std::fflush(stdout);
        }
    }
    for (const std::size_t block : {32u, 64u, 128u, 256u}) {
        for (const std::size_t lanes : {1u, 2u}) {
            std::size_t bytes = codec::kFrameHeaderBytes;
            for (std::size_t first = 0; first < samples; first += block) {
                const std::size_t count = std::min(block, samples - first);
                bytes += lanes * 3u;
                for (std::size_t lane = 0; lane < lanes; ++lane) {
                    unsigned minimum = 1023u, maximum = 0u;
                    std::size_t laneCount = 0;
                    for (std::size_t i = lane; i < count; i += lanes) {
                        const std::size_t index = (first + i) * 2u;
                        const unsigned code = (original[index] | (original[index + 1u] << 8u)) >> 6u;
                        minimum = std::min(minimum, code);
                        maximum = std::max(maximum, code);
                        ++laneCount;
                    }
                    unsigned bits = 0;
                    for (unsigned span = maximum - minimum; span > 0; span >>= 1u) ++bits;
                    bytes += (laneCount * bits + 7u) / 8u;
                }
            }
            std::printf("RECORDED_RANGE_SIZE_ESTIMATE fixture=%s block=%zu lanes=%zu "
                "bytes=%zu scope=actual_sample_range_not_codec_or_fps_proof\n",
                label.c_str(), block, lanes, bytes);
        }
    }
}

std::uint32_t readBe32() {
    std::uint8_t value[4];
    readExactly(value, sizeof(value));
    return (static_cast<std::uint32_t>(value[0]) << 24u) |
        (static_cast<std::uint32_t>(value[1]) << 16u) |
        (static_cast<std::uint32_t>(value[2]) << 8u) | value[3];
}

void recordedFrame(bool bitplaneProbe = false) {
    char magic[5];
    readExactly(magic, sizeof(magic));
    require(std::memcmp(magic, "NPV10", 5u) == 0, "Not NPV10");
    const auto version = readBe32();
    require(version == 1u || version == 2u, "Unknown NPV10 version");
    const auto width = readBe32();
    const auto height = readBe32();
    const auto fps = readBe32();
    require(readBe32() == 10u, "Not ten bit");
    require(width > 0u && width <= 8192u && height > 0u && height <= 8192u, "Invalid dimensions");
    const auto metadataSize = readBe32();
    require(metadataSize < 1024u * 1024u, "Invalid metadata length");
    Bytes metadata(metadataSize);
    readExactly(metadata.data(), metadata.size());
    readExactly(magic, 4u);
    require(std::memcmp(magic, "FRM0", 4u) == 0, "Missing first frame");
    (void)readBe32();
    (void)readBe32();
    require(readBe32() == width && readBe32() == height, "Frame dimensions differ");
    const auto storedSize = readBe32();
    const auto storedCrc = readBe32();
    require(storedSize > 0u && storedSize <= 128u * 1024u * 1024u, "Invalid frame length");
    Bytes stored(storedSize);
    readExactly(stored.data(), stored.size());
    require(crc(stored) == storedCrc, "Stored source-frame CRC failed");
    Bytes decoded(static_cast<std::size_t>(width) * height * 3u);
    require(codec::decodePacketToP010(stored.data(), stored.size(), decoded.data(), decoded.size()),
        "Recorded NLC2 frame failed decode");
    const auto label = "recorded_" + std::to_string(width) + "x" + std::to_string(height) +
        "_" + std::to_string(fps) + "fps_first_frame";
    if (bitplaneProbe) benchmarkRecordedBitplanes(decoded, label);
    else benchmark(decoded, label, 8);
    // The caller supplies a bounded prefix; consume its remainder so a successful first-frame
    // audit does not cause the read-only producer to fail with a broken pipe.
    std::uint8_t tail[64u * 1024u];
    while (std::fread(tail, 1u, sizeof(tail), stdin) > 0u) {}
}
}  // namespace

int main(int argc, char** argv) {
    try {
        for (const auto effort : {-1, 0, 65538, 2147483647}) {
            Bytes untouched(128, 0xA7u);
            const auto result = codec::compressPackedTenBitRealtimeLz4InPlace(
                untouched.data(), 5, 4, 128, 0, effort);
            require(!result.successful, "Invalid acceleration accepted");
            require(std::all_of(untouched.begin(), untouched.end(),
                [](std::uint8_t value) { return value == 0xA7u; }),
                "Invalid acceleration modified input");
        }
        std::printf("{\"probe\":\"codec_only_not_camera_fps\",\"lz4_version\":\"%s\"}\n", LZ4_versionString());
#if defined(__aarch64__) && !defined(NEO_NPV10_FORCE_SCALAR_SPLIT)
        std::puts("{\"split_backend\":\"aarch64_simd\"}");
#else
        std::puts("{\"split_backend\":\"scalar\"}");
#endif
#if defined(NEO_NPV10_REALTIME_LZ4_ACCELERATION)
        std::printf("{\"realtime_lz4_acceleration\":%d}\n", NEO_NPV10_REALTIME_LZ4_ACCELERATION);
#else
        std::puts("{\"realtime_lz4_acceleration\":1024}");
#endif
        if (argc == 3 && (std::strcmp(argv[1], "--recorded-p010-range-only") == 0 ||
                         std::strcmp(argv[1], "--recorded-p010-range-parallel") == 0 ||
                         std::strcmp(argv[1], "--recorded-p010-codec-grid") == 0)) {
            // A measured failed recording can exercise every existing bit-exact backend without
            // synthesizing sensor input or changing the installed application's selected codec.
            const bool compareCodecs = std::strcmp(argv[1], "--recorded-p010-codec-grid") == 0;
            auto* input = std::fopen(argv[2], "rb");
            require(input != nullptr, "Recorded P010 sample cannot be opened");
            require(std::fseek(input, 0, SEEK_END) == 0, "Cannot size recorded P010 sample");
            const auto length = std::ftell(input);
            require(length > 0 && length <= 128 * 1024 * 1024 && length % 2 == 0,
                "Recorded P010 sample length invalid");
            require(std::fseek(input, 0, SEEK_SET) == 0, "Cannot rewind recorded P010 sample");
            Bytes original(static_cast<std::size_t>(length));
            require(std::fread(original.data(), 1, original.size(), input) == original.size(),
                "Truncated recorded P010 sample");
            require(std::fclose(input) == 0, "Recorded sample close failed");
            benchmark(original, argv[2], compareCodecs ? 4 : 24, compareCodecs ? -1 : 7,
                std::strcmp(argv[1], "--recorded-p010-range-parallel") == 0);
        } else if (argc == 2 && std::strcmp(argv[1], "--npv10-stdin") == 0) {
            recordedFrame();
        } else if (argc == 2 && std::strcmp(argv[1], "--npv10-bitplane-probe") == 0) {
            recordedFrame(true);
        } else {
            for (std::size_t count : {1u, 2u, 3u, 4u, 31u, 32u, 33u, 63u, 64u, 65u,
                                      262143u, 262144u, 262145u, 524289u}) {
                for (unsigned pattern = 0u; pattern < 4u; ++pattern) {
                    benchmark(synthetic(count, pattern), "boundary_" + std::to_string(count) +
                        "_pattern_" + std::to_string(pattern), 1);
                }
            }
            for (const auto& size : {std::pair{3264u, 2448u}, std::pair{4096u, 3072u}, std::pair{3840u, 2160u}}) {
                for (unsigned pattern = 0u; pattern < 4u; ++pattern) {
                    benchmark(synthetic(static_cast<std::size_t>(size.first) * size.second * 3u / 2u, pattern),
                        std::to_string(size.first) + "x" + std::to_string(size.second) +
                        "_pattern_" + std::to_string(pattern), 3);
                }
            }
        }
        std::puts("{\"result\":\"PASS\",\"scope\":\"native_lossless_codec_only\"}");
        return 0;
    } catch (const std::exception& error) {
        std::fprintf(stderr, "CODEC_TEST_FAILED: %s\n", error.what());
        return 1;
    }
}
