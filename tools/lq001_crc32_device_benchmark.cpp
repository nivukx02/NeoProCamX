// Physical-device checksum microbenchmark; not a camera-FPS, storage or image-quality test.
#include "neo_qa_hypergrid/probes/npv10_crc32_candidate.h"

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <stdexcept>
#include <vector>

namespace {
namespace candidate = neoproc::npv10::checksum;
using Clock = std::chrono::steady_clock;

void require(bool condition, const char* reason) {
    if (!condition) throw std::runtime_error(reason);
}

std::uint32_t next(std::uint32_t& state) {
    state ^= state << 13u;
    state ^= state >> 17u;
    state ^= state << 5u;
    return state;
}

double measure(const std::vector<std::uint8_t>& bytes, bool accelerated, uLong* result) {
    constexpr int iterations = 16;
    const auto started = Clock::now();
    uLong checksum = 0u;
    for (int i = 0; i < iterations; ++i) {
        checksum = accelerated
            ? candidate::crc32(checksum, bytes.data(), static_cast<uInt>(bytes.size()))
            : ::crc32(checksum, bytes.data(), static_cast<uInt>(bytes.size()));
    }
    *result = checksum;
    return std::chrono::duration<double, std::milli>(Clock::now() - started).count() / iterations;
}
}  // namespace

int main() {
    try {
        std::uint32_t state = 0x9e3779b9u;
        std::vector<std::uint8_t> bytes(23u * 1024u * 1024u + 19u);
        for (auto& byte : bytes) byte = static_cast<std::uint8_t>(next(state));
        unsigned checked = 0u;
        for (uLong seed : {0uL, 1uL, 0xffffffffuL, 0x12345678uL}) {
            require(candidate::crc32(seed, Z_NULL, 0u) == ::crc32(seed, Z_NULL, 0u),
                "Null initialization differs");
            for (unsigned offset = 0u; offset < 16u; ++offset) {
                for (uInt size : {0u, 1u, 2u, 3u, 7u, 8u, 9u, 15u, 16u, 17u, 31u, 32u, 33u,
                                  4095u, 4096u, 4097u, 32767u, 32768u, 32769u, 262143u, 262144u}) {
                    require(candidate::crc32(seed, bytes.data() + offset, size) ==
                            ::crc32(seed, bytes.data() + offset, size), "CRC parity differs");
                    ++checked;
                }
            }
        }
        const auto split = static_cast<uInt>(bytes.size() / 3u);
        const auto prefix = candidate::crc32(0u, bytes.data(), split);
        require(candidate::crc32(prefix, bytes.data() + split,
                    static_cast<uInt>(bytes.size()) - split) ==
                ::crc32(0u, bytes.data(), static_cast<uInt>(bytes.size())),
            "Streaming CRC composition differs");
        double zlibTotal = 0.0;
        double candidateTotal = 0.0;
        for (unsigned round = 0u; round < 4u; ++round) {
            uLong reference = 0u;
            uLong accelerated = 0u;
            // Alternate order to reduce cache/warmup bias; use identical bytes and seeds.
            if ((round & 1u) == 0u) {
                zlibTotal += measure(bytes, false, &reference);
                candidateTotal += measure(bytes, true, &accelerated);
            } else {
                candidateTotal += measure(bytes, true, &accelerated);
                zlibTotal += measure(bytes, false, &reference);
            }
            require(reference == accelerated, "Full-frame rolling CRC differs");
        }
        std::printf("{\"scope\":\"checksum_only_not_camera_fps\",\"hardware_crc32\":%s,"
                    "\"parity_cases\":%u,\"bytes_per_iteration\":%zu,\"zlib_mean_ms\":%.6f,"
                    "\"candidate_mean_ms\":%.6f,\"ratio\":%.6f,\"bit_exact\":true}\n",
            candidate::hardwareAvailable() ? "true" : "false", checked, bytes.size(),
            zlibTotal / 4.0, candidateTotal / 4.0, zlibTotal / candidateTotal);
        return 0;
    } catch (const std::exception& error) {
        std::fprintf(stderr, "CHECKSUM_TEST_FAILED: %s\n", error.what());
        return 1;
    }
}
