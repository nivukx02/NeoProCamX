// Standalone native packet-safety regression. This is not camera-FPS evidence.
#include "npv10_lossless_chunk_codec.h"

#include <algorithm>
#include <cstdio>
#include <cstring>
#include <limits>
#include <stdexcept>
#include <string>
#include <vector>
#include <sys/mman.h>
#include <unistd.h>
#include <zlib.h>

namespace {
namespace codec = neoproc::npv10::lossless;

void require(bool value, const char* message) {
    if (!value) throw std::runtime_error(message);
}

void putBe32(std::uint8_t* output, std::uint32_t value) {
    output[0] = static_cast<std::uint8_t>(value >> 24u);
    output[1] = static_cast<std::uint8_t>(value >> 16u);
    output[2] = static_cast<std::uint8_t>(value >> 8u);
    output[3] = static_cast<std::uint8_t>(value);
}

void rejectedBeforePayloadRead(std::uint32_t flags, std::uint32_t storedBytes) {
    constexpr std::size_t samples = 256u;
    constexpr std::size_t packedBytes = samples * 10u / 8u;
    constexpr std::size_t headerBytes = 44u;
    const long rawPageSize = ::sysconf(_SC_PAGESIZE);
    require(rawPageSize >= static_cast<long>(headerBytes), "Invalid page size");
    const auto pageBytes = static_cast<std::size_t>(rawPageSize);
    auto* pages = static_cast<std::uint8_t*>(::mmap(
        nullptr, pageBytes * 2u, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0));
    require(pages != MAP_FAILED, "mmap failed");
    require(::mprotect(pages + pageBytes, pageBytes, PROT_NONE) == 0, "mprotect failed");
    auto* packet = pages + pageBytes - headerBytes;
    std::memcpy(packet, "NLC2", 4u);
    putBe32(packet + 4u, flags == 2u ? 2u : 1u);
    putBe32(packet + 8u, packedBytes);
    putBe32(packet + 12u, samples * 2u);
    putBe32(packet + 16u, samples);
    putBe32(packet + 20u, static_cast<std::uint32_t>(codec::kChunkSamples));
    putBe32(packet + 24u, 1u);
    putBe32(packet + 28u, 0u);
    putBe32(packet + 32u, flags);
    putBe32(packet + 36u, samples);
    putBe32(packet + 40u, storedBytes);
    std::vector<std::uint8_t> decoded(samples * 2u, 0xA7u);
    const bool accepted = codec::decodePacketToP010(packet, headerBytes, decoded.data(), decoded.size());
    require(::munmap(pages, pageBytes * 2u) == 0, "munmap failed");
    require(!accepted, "Truncated payload accepted");
    require(std::all_of(decoded.begin(), decoded.end(),
        [](std::uint8_t value) { return value == 0xA7u; }), "Rejected header modified output");
    std::printf("PASS truncated_guard_page flags=%u stored=%u\n", flags, storedBytes);
}

void validPacketAndEveryTruncation() {
    constexpr std::int64_t samples = 256;
    constexpr std::int64_t packedBytes = samples * 10 / 8;
    const auto capacity = codec::maximumStoredBytes(packedBytes, samples);
    require(capacity > 0, "Invalid packet size");
    std::vector<std::uint8_t> packet(static_cast<std::size_t>(capacity), 0u);
    const auto crc = static_cast<std::uint32_t>(::crc32(
        ::crc32(0L, Z_NULL, 0), packet.data(), static_cast<uInt>(packedBytes)));
    const auto result = codec::framePackedTenBitRealtimeInPlace(
        packet.data(), packedBytes, samples, capacity, crc);
    require(result.successful, "Valid packet generation failed");
    std::vector<std::uint8_t> output(samples * 2, 0xA7u);
    require(codec::decodePacketToP010(packet.data(), result.storedBytes, output.data(), output.size()),
            "Valid legacy raw packet rejected");
    require(std::all_of(output.begin(), output.end(),
        [](std::uint8_t value) { return value == 0u; }), "Valid codes changed");
    for (std::int64_t length = 0; length < result.storedBytes; ++length) {
        require(!codec::decodePacketToP010(packet.data(), length, output.data(), output.size()),
                "Truncated packet accepted");
    }
    std::printf("PASS valid_legacy_raw_and_every_truncation count=%lld\n",
                static_cast<long long>(result.storedBytes));
}
}  // namespace

int main(int argc, char** argv) {
    try {
        std::puts("SCOPE native_packet_bounds_not_camera_fps");
        std::fflush(stdout);
        if (argc == 2 && std::strcmp(argv[1], "--probe-raw") == 0) {
            rejectedBeforePayloadRead(0u, 320u);
        } else if (argc == 2 && std::strcmp(argv[1], "--probe-lz4") == 0) {
            rejectedBeforePayloadRead(1u, 0x7fffffffu);
        } else {
            for (const auto flags : {0u, 1u, 2u}) {
                for (const auto extent : {44u, 45u, 320u, 0x7fffffffu, 0xffffffffu}) {
                    rejectedBeforePayloadRead(flags, extent);
                }
            }
            validPacketAndEveryTruncation();
        }
        std::puts("PASS native_packet_bounds");
        return 0;
    } catch (const std::exception& failure) {
        std::fprintf(stderr, "FAIL %s\n", failure.what());
        return 1;
    }
}
