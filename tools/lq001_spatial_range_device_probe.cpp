// Uses actual retained camera samples. This is not a camera-recording or FPS PASS.
#include "npv10_spatial_range_probe.h"
#include <chrono>
#include <cstdio>
#include <memory>
#include <stdexcept>
#include <vector>

namespace {
using Bytes = std::vector<std::uint8_t>;
using Clock = std::chrono::steady_clock;
namespace old = neoproc::npv10::lossless;
namespace spatial = neoproc::npv10::spatial_probe;

void require(bool condition, const char* error) {
    if (!condition) throw std::runtime_error(error);
}

Bytes pack(const std::vector<std::uint16_t>& codes) {
    Bytes bytes((codes.size() * 10u + 7u) / 8u, 0u);
    std::uint32_t accumulator = 0u;
    unsigned pending = 0u;
    std::size_t position = 0u;
    for (const auto code : codes) {
        require(code <= 1023u, "Invalid original ten-bit sample");
        accumulator |= static_cast<std::uint32_t>(code) << pending;
        pending += 10u;
        while (pending >= 8u) {
            bytes[position++] = static_cast<std::uint8_t>(accumulator);
            accumulator >>= 8u;
            pending -= 8u;
        }
    }
    if (pending != 0u) bytes[position] = static_cast<std::uint8_t>(accumulator);
    return bytes;
}

void contracts() {
    for (unsigned previous = 0u; previous < 1024u; ++previous)
        for (unsigned value = 0u; value < 1024u; ++value)
            require(spatial::restore(spatial::residual(value, previous), previous) == value,
                "Modulo predictor is not bijective");
    unsigned cases = 0u;
    for (const auto count : {1u, 2u, 3u, 7u, 31u, 32u, 63u, 64u, 65u, 127u, 4096u, 262144u}) {
        for (unsigned pattern = 0u; pattern < 6u; ++pattern) {
            std::vector<std::uint16_t> codes(count);
            std::uint32_t random = 0x87953ad1u;
            for (unsigned i = 0u; i < count; ++i) {
                random ^= random << 13u; random ^= random >> 17u; random ^= random << 5u;
                codes[i] = static_cast<std::uint16_t>(pattern == 0u ? 0u : pattern == 1u ? 1023u :
                    pattern == 2u ? (i & 1u) * 1023u : pattern == 3u ? (i * 511u) & 1023u :
                    pattern == 4u ? i & 1023u : random & 1023u);
            }
            const auto source = pack(codes);
            Bytes encoded(source.size() + (count + 63u) / 64u * 4u + 32u, 0xa7u);
            const auto bytes = spatial::encode(source.data(), count, encoded.data(), encoded.size() - 16u);
            require(bytes > 0u, "Spatial encoding failed");
            Bytes decoded(source.size() + 16u, 0xa7u);
            require(spatial::decode(encoded.data(), bytes, count, decoded.data(), source.size()) &&
                std::equal(source.begin(), source.end(), decoded.begin()), "Source sample changed");
            require(std::all_of(encoded.begin() + bytes, encoded.end(), [](auto v) { return v == 0xa7u; }) &&
                std::all_of(decoded.begin() + source.size(), decoded.end(), [](auto v) { return v == 0xa7u; }),
                "Guard changed");
            for (std::size_t truncated = 0u; truncated < std::min<std::size_t>(bytes, 128u); ++truncated)
                require(!spatial::decode(encoded.data(), truncated, count, decoded.data(), source.size()),
                    "Truncated packet accepted");
            require(!spatial::decode(encoded.data(), bytes + 1u, count, decoded.data(), source.size()),
                "Extra packet byte accepted");
            encoded[1] |= 0xc0u;
            require(!spatial::decode(encoded.data(), bytes, count, decoded.data(), source.size()),
                "Reserved bits accepted");
            ++cases;
        }
    }
    std::printf("PASS modulo_pairs=1048576 lossless_boundary_cases=%u scope=native_algorithm_not_camera_fps\n", cases);
}

void compare(const char* path) {
    std::unique_ptr<FILE, decltype(&std::fclose)> file(std::fopen(path, "rb"), &std::fclose);
    require(file != nullptr, "Cannot open exact recorded frame");
    require(std::fseek(file.get(), 0, SEEK_END) == 0, "Seek failed");
    const auto length = std::ftell(file.get());
    require(length > 0 && length <= 4096L * 3072L * 3L && length % 2L == 0, "Invalid real sample size");
    require(std::fseek(file.get(), 0, SEEK_SET) == 0, "Rewind failed");
    Bytes frame(static_cast<std::size_t>(length));
    require(std::fread(frame.data(), 1u, frame.size(), file.get()) == frame.size(), "Truncated real sample");
    std::vector<std::uint16_t> codes(frame.size() / 2u);
    for (std::size_t i = 0u; i < codes.size(); ++i) {
        const unsigned word = frame[i * 2u] | (frame[i * 2u + 1u] << 8u);
        require((word & 63u) == 0u, "Noncanonical source P010");
        codes[i] = static_cast<std::uint16_t>(word >> 6u);
    }
    const auto packed = pack(codes);
    constexpr std::size_t chunkSamples = 262144u;
    Bytes encoded(chunkSamples * 2u), decoded(chunkSamples * 2u);
    std::uint64_t stored[2]{};
    double elapsed[2]{};
    for (unsigned round = 0u; round < 12u; ++round) {
        for (unsigned pass = 0u; pass < 2u; ++pass) {
            const unsigned candidate = (round + pass) & 1u;
            const auto started = Clock::now();
            std::uint64_t total = 0u;
            for (std::size_t first = 0u; first < codes.size(); first += chunkSamples) {
                const auto samples = std::min(chunkSamples, codes.size() - first);
                const auto bytes = (samples * 10u + 7u) / 8u;
                const auto* input = packed.data() + first * 10u / 8u;
                const auto result = candidate
                    ? spatial::encode(input, samples, encoded.data(), bytes - 1u)
                    : old::encodeRange10(input, samples, encoded.data(), bytes - 1u);
                total += (result == 0u ? bytes : result) + 12u;
            }
            elapsed[candidate] += std::chrono::duration<double, std::milli>(Clock::now() - started).count();
            stored[candidate] = total + 32u;
        }
    }
    for (std::size_t first = 0u; first < codes.size(); first += chunkSamples) {
        const auto samples = std::min(chunkSamples, codes.size() - first);
        const auto bytes = (samples * 10u + 7u) / 8u;
        const auto* input = packed.data() + first * 10u / 8u;
        const auto encodedBytes = spatial::encode(input, samples, encoded.data(), encoded.size());
        require(encodedBytes > 0u && spatial::decode(encoded.data(), encodedBytes, samples,
            decoded.data(), bytes) && std::memcmp(input, decoded.data(), bytes) == 0,
            "Real source sample changed");
    }
    std::printf("ACTUAL_SPATIAL_RANGE fixture=%s original_range_bytes=%llu spatial_range_bytes=%llu "
        "saved_percent=%.6f original_mean_ms=%.6f spatial_mean_ms=%.6f all_source_codes_identical=true "
        "lz4_fallback_not_benchmarked=true camera_fps=NOT_TESTED\n", path,
        static_cast<unsigned long long>(stored[0]), static_cast<unsigned long long>(stored[1]),
        100.0 * (1.0 - static_cast<double>(stored[1]) / static_cast<double>(stored[0])),
        elapsed[0] / 12.0, elapsed[1] / 12.0);
}
} // namespace

int main(int argc, char** argv) {
    try {
        contracts();
        for (int index = 1; index < argc; ++index) compare(argv[index]);
        return 0;
    } catch (const std::exception& error) {
        std::fprintf(stderr, "SPATIAL_PROBE_FAIL %s\n", error.what());
        return 1;
    }
}
