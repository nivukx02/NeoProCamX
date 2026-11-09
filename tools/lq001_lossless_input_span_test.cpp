// Paired execution of the saved pre-change codec and current production codec on Android.
// Synthetic vectors prove byte/bounds contracts; recorded P010 proves codec work, not camera FPS.
#include "npv10_lossless_chunk_codec.h"
#include <algorithm>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <limits>
#include <stdexcept>
#include <string>
#include <vector>
#include <zlib.h>

// The baseline translation unit is the unchanged saved production source, compiled with
// -Dneoproc=baseline_neoproc and a renamed JNI entry point. Its result uses the same header.
namespace baseline_neoproc::npv10::lossless {
struct EncodeResult {
    bool successful = false;
    std::int64_t storedBytes = 0;
    std::uint32_t storedCrc32 = 0;
    std::uint32_t packedCrc32 = 0;
    std::int32_t compressedChunks = 0;
    std::int32_t rawChunks = 0;
    std::int64_t borrowedInputBytes = 0;
};
EncodeResult compressPackedTenBitRealtimeRangeLz4InPlace(
    std::uint8_t*, std::int64_t, std::int64_t, std::int64_t, std::uint32_t, int) noexcept;
EncodeResult compressPackedTenBitRealtimeLz4InPlace(
    std::uint8_t*, std::int64_t, std::int64_t, std::int64_t, std::uint32_t, int) noexcept;
}

namespace {
namespace codec = neoproc::npv10::lossless;
using Bytes = std::vector<std::uint8_t>;
using Clock = std::chrono::steady_clock;
std::size_t checks = 0u;
void require(bool okay, const char* message) {
    if (!okay) throw std::runtime_error(message);
    ++checks;
}

std::uint32_t crc(const std::uint8_t* bytes, std::size_t count) {
    return static_cast<std::uint32_t>(::crc32(0u, bytes, static_cast<uInt>(count)));
}

Bytes pack(const Bytes& p010) {
    Bytes packed((p010.size() / 2u * 10u + 7u) / 8u, 0u);
    std::uint32_t accumulator = 0u;
    unsigned bits = 0u;
    std::size_t out = 0u;
    for (std::size_t i = 0u; i < p010.size(); i += 2u) {
        const auto word = static_cast<unsigned>(p010[i] | (p010[i + 1u] << 8u));
        if ((word & 63u) != 0u) throw std::runtime_error("Noncanonical P010 fixture");
        accumulator |= (word >> 6u) << bits;
        bits += 10u;
        while (bits >= 8u) {
            packed[out++] = static_cast<std::uint8_t>(accumulator);
            accumulator >>= 8u;
            bits -= 8u;
        }
    }
    if (bits != 0u) packed[out] = static_cast<std::uint8_t>(accumulator);
    return packed;
}

void boundaryProof() {
    constexpr std::size_t bytes = 327680u;
    constexpr std::size_t header = codec::kChunkHeaderBytes;
    constexpr auto bound = bytes + bytes / 255u + 16u;
    constexpr auto maximum = std::numeric_limits<std::size_t>::max();
    for (const std::size_t pending : {0u, 12u, 327692u}) {
        const auto source = 32u + pending + header + bound;
        require(codec::canBorrowPackedChunkInput(source, bytes, 32u, pending),
                "Exact disjoint worst-case prefix rejected");
        require(!codec::canBorrowPackedChunkInput(source - 1u, bytes, 32u, pending),
                "Short prefix admitted");
        require(!codec::canBorrowPackedChunkInput(source, bytes, source + 1u, pending),
                "Destination after source admitted");
    }
    require(!codec::canBorrowPackedChunkInput(0u, 0u, 0u, 0u), "Empty span admitted");
    require(!codec::canBorrowPackedChunkInput(maximum, maximum, 0u, 0u), "Body overflow admitted");
    require(!codec::canBorrowPackedChunkInput(maximum, 1u, maximum - 1u, 2u),
            "Pending-prefix overflow admitted");
    require(codec::canBorrowPackedChunkInput(maximum, bytes,
        maximum - bound - header - 7u, 7u), "High disjoint offsets rejected");
    std::puts("PASS source-span boundary and overflow proof");
}

Bytes fixture(std::size_t samples, unsigned pattern) {
    Bytes result(samples * 2u);
    std::uint32_t seed = 0x751ac013u;
    for (std::size_t i = 0u; i < samples; ++i) {
        seed ^= seed << 13u; seed ^= seed >> 17u; seed ^= seed << 5u;
        const auto chunk = i / static_cast<std::size_t>(codec::kChunkSamples);
        unsigned code = 0u;
        if (pattern == 1u) code = seed & 1023u;
        else if (pattern == 2u) code = 320u + (seed & 31u);
        else if (pattern == 3u) code = i < samples / 2u ? 256u : seed & 1023u;
        else if (pattern == 4u) code = i < samples / 2u ? seed & 1023u : 256u;
        else if (pattern == 5u) code = chunk % 2u == 0u ? 256u : seed & 1023u;
        else if (pattern == 6u) code = (i & 1u) ? 768u + (seed & 63u) : 64u + (seed & 7u);
        // Both range lanes need ten bits, but their repeated split bytes admit LZ4. This
        // specifically exercises borrowed input after a rejected partial range encoding.
        else if (pattern == 7u) code = static_cast<unsigned>(i % 4u) * 341u;
        const auto word = code << 6u;
        result[i * 2u] = static_cast<std::uint8_t>(word);
        result[i * 2u + 1u] = static_cast<std::uint8_t>(word >> 8u);
    }
    return result;
}

struct Measurement {
    double baselineMs = 0.0;
    double candidateMs = 0.0;
    double baselineMaxMs = 0.0;
    double candidateMaxMs = 0.0;
    double baselineMedianMs = 0.0;
    double candidateMedianMs = 0.0;
    double pairedDeltaMedianMs = 0.0;
    int candidateFasterPairs = 0;
    std::int64_t borrowed = 0;
    std::int64_t stored = 0;
};

Measurement paired(const Bytes& original, int acceleration, int iterations, bool rangePacking = true) {
    const auto encodeBefore = rangePacking
        ? baseline_neoproc::npv10::lossless::compressPackedTenBitRealtimeRangeLz4InPlace
        : baseline_neoproc::npv10::lossless::compressPackedTenBitRealtimeLz4InPlace;
    using Encoder = codec::EncodeResult (*)(std::uint8_t*, std::int64_t,
        std::int64_t, std::int64_t, std::uint32_t, int) noexcept;
    const Encoder encodeAfter = rangePacking
        ? codec::compressPackedTenBitRealtimeRangeLz4InPlace
        : static_cast<Encoder>(codec::compressPackedTenBitRealtimeLz4InPlace);
    const Bytes packed = pack(original);
    const auto samples = static_cast<std::int64_t>(original.size() / 2u);
    const auto capacity = codec::maximumStoredBytes(static_cast<std::int64_t>(packed.size()), samples);
    require(capacity > 0, "Invalid capacity");
    const auto inputCrc = crc(packed.data(), packed.size());
    Bytes prior(static_cast<std::size_t>(capacity) + 34u, 0xa7u);
    Bytes current(prior.size(), 0xa7u);
    Bytes restored(original.size());
    Measurement measured;
    std::vector<double> baselineTimes, candidateTimes, pairedDeltas;
    baselineTimes.reserve(static_cast<std::size_t>(iterations));
    candidateTimes.reserve(static_cast<std::size_t>(iterations));
    pairedDeltas.reserve(static_cast<std::size_t>(iterations));
    for (int round = -2; round < iterations; ++round) {
        // Alternate ordering after two warmups; both paths receive identical fresh source bytes.
        baseline_neoproc::npv10::lossless::EncodeResult oldResult;
        codec::EncodeResult newResult;
        double oldMs = 0.0, newMs = 0.0;
        for (int pass = 0; pass < 2; ++pass) {
            const bool baseline = ((round + 2 + pass) & 1) == 0;
            auto& output = baseline ? prior : current;
            std::copy(packed.begin(), packed.end(), output.begin() + 1u);
            const auto start = Clock::now();
            if (baseline) {
                oldResult = encodeBefore(
                    output.data() + 1u, static_cast<std::int64_t>(packed.size()), samples,
                    capacity, inputCrc, acceleration);
                oldMs = std::chrono::duration<double, std::milli>(Clock::now() - start).count();
            } else {
                newResult = encodeAfter(
                    output.data() + 1u, static_cast<std::int64_t>(packed.size()), samples,
                    capacity, inputCrc, acceleration);
                newMs = std::chrono::duration<double, std::milli>(Clock::now() - start).count();
            }
        }
        require(oldResult.successful && newResult.successful, "Paired encode failed");
        require(oldResult.storedBytes == newResult.storedBytes &&
            oldResult.storedCrc32 == newResult.storedCrc32 &&
            oldResult.packedCrc32 == newResult.packedCrc32 &&
            oldResult.rawChunks == newResult.rawChunks &&
            oldResult.compressedChunks == newResult.compressedChunks,
            "Stored size, CRC or chunk decisions differ");
        require(std::equal(prior.begin() + 1u, prior.begin() + 1u + oldResult.storedBytes,
            current.begin() + 1u), "Encoded packet bytes changed");
        require(current.front() == 0xa7u && std::all_of(current.begin() + capacity + 1u,
            current.end(), [](auto value) { return value == 0xa7u; }), "Output guard changed");
        require(crc(current.data() + 1u, static_cast<std::size_t>(newResult.storedBytes)) ==
            newResult.storedCrc32, "Stored CRC lost coverage");
        require(codec::decodePacketToP010(current.data() + 1u, newResult.storedBytes,
            restored.data(), static_cast<std::int64_t>(restored.size())) && restored == original,
            "Decoded source samples differ");
        if (round >= 0) {
            measured.baselineMs += oldMs;
            measured.candidateMs += newMs;
            measured.baselineMaxMs = std::max(measured.baselineMaxMs, oldMs);
            measured.candidateMaxMs = std::max(measured.candidateMaxMs, newMs);
            baselineTimes.push_back(oldMs);
            candidateTimes.push_back(newMs);
            pairedDeltas.push_back(newMs - oldMs);
            if (newMs < oldMs) ++measured.candidateFasterPairs;
        }
        measured.borrowed = newResult.borrowedInputBytes;
        measured.stored = newResult.storedBytes;
    }
    measured.baselineMs /= iterations;
    measured.candidateMs /= iterations;
    // Keep means/maxima (including every scheduler stall) and additionally report paired
    // medians. A single preemption must not be silently discarded or mistaken for a
    // repeatable algorithm speedup. Neither statistic grants a camera-checkpoint PASS.
    const auto median = [](std::vector<double>& values) {
        std::sort(values.begin(), values.end());
        const auto middle = values.size() / 2u;
        return values.size() % 2u != 0u ? values[middle]
            : (values[middle - 1u] + values[middle]) * 0.5;
    };
    measured.baselineMedianMs = median(baselineTimes);
    measured.candidateMedianMs = median(candidateTimes);
    measured.pairedDeltaMedianMs = median(pairedDeltas);
    return measured;
}

Bytes readRecorded(const char* path) {
    auto* input = std::fopen(path, "rb");
    require(input != nullptr, "Cannot open recorded P010");
    require(std::fseek(input, 0, SEEK_END) == 0, "Cannot size P010");
    const auto size = std::ftell(input);
    require(size > 0 && size <= 128 * 1024 * 1024 && size % 2 == 0, "Invalid P010 extent");
    require(std::fseek(input, 0, SEEK_SET) == 0, "Cannot rewind P010");
    Bytes result(static_cast<std::size_t>(size));
    require(std::fread(result.data(), 1u, result.size(), input) == result.size(), "Truncated P010");
    require(std::fclose(input) == 0, "Cannot close P010");
    return result;
}
}

int main(int argc, char** argv) {
    try {
        boundaryProof();
        if (argc > 1) {
            for (int file = 1; file < argc; ++file) {
                const auto original = readRecorded(argv[file]);
                for (const int acceleration : {1024, 64}) {
                    const auto measured = paired(original, acceleration, 24);
                    require(measured.borrowed > 0, "Real fixture did not exercise source borrowing");
                    std::printf("RECORDED_CODEC_PAIRED fixture=%s effort=%d iterations=24 "
                        "baseline_mean_ms=%.6f candidate_mean_ms=%.6f baseline_max_ms=%.6f "
                        "candidate_max_ms=%.6f baseline_p50_ms=%.6f candidate_p50_ms=%.6f "
                        "paired_delta_p50_ms=%.6f candidate_faster_pairs=%d "
                        "borrowed_input_bytes=%lld stored_bytes=%lld "
                        "all_packet_bytes_identical=true all_p010_samples_identical=true\n",
                        argv[file], acceleration, measured.baselineMs, measured.candidateMs,
                        measured.baselineMaxMs, measured.candidateMaxMs,
                        measured.baselineMedianMs, measured.candidateMedianMs,
                        measured.pairedDeltaMedianMs, measured.candidateFasterPairs,
                        static_cast<long long>(measured.borrowed), static_cast<long long>(measured.stored));
                    std::fflush(stdout);
                }
            }
        } else {
            for (const std::size_t samples : {1u, 2u, 3u, 4u, 63u, 64u, 65u, 262143u,
                    262144u, 262145u, 524287u, 524288u, 524289u, 786433u, 2097153u}) {
                for (unsigned pattern = 0u; pattern < 8u; ++pattern) {
                    for (const int acceleration : {1024, 64}) {
                        for (const bool rangePacking : {true, false}) {
                            const auto measured = paired(fixture(samples, pattern), acceleration, 1, rangePacking);
                            if (samples >= 2097153u && (pattern == 0u || pattern == 7u ||
                                    (rangePacking && (pattern == 2u || pattern == 6u))))
                                require(measured.borrowed > 0, "Disjoint-prefix fast path not exercised");
                        }
                    }
                }
            }
            std::puts("PASS 480 differential NLC2-v1/v2 boundary, overlap, LZ4/range/raw escape and effort cases");
        }
        std::printf("LOSSLESS_INPUT_SPAN_TEST_PASS checks=%zu scope=codec_not_camera_fps\n", checks);
        return 0;
    } catch (const std::exception& error) {
        std::fprintf(stderr, "LOSSLESS_INPUT_SPAN_TEST_FAIL: %s\n", error.what());
        return 1;
    }
}
