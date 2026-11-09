// Read-only, real-recording experiment. Not an app format, enabled codec, or FPS proof.
#include "npv10_lossless_chunk_codec.h"
#include <algorithm>
#include <array>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <stdexcept>
#include <vector>
#include <zlib.h>
#if defined(__aarch64__)
#include <arm_neon.h>
#endif

namespace {
using Bytes = std::vector<std::uint8_t>;
using Clock = std::chrono::steady_clock;
namespace codec = neoproc::npv10::lossless;

void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}
std::uint32_t be32(const std::uint8_t* bytes) {
    return (std::uint32_t(bytes[0]) << 24u) | (std::uint32_t(bytes[1]) << 16u) |
           (std::uint32_t(bytes[2]) << 8u) | bytes[3];
}
std::uint64_t be64(const std::uint8_t* bytes) {
    return (std::uint64_t(be32(bytes)) << 32u) | be32(bytes + 4u);
}
void readExact(void* target, std::size_t size) {
    auto* next = static_cast<std::uint8_t*>(target);
    while (size != 0u) {
        const auto read = std::fread(next, 1u, size, stdin);
        require(read > 0u, "Truncated actual recording; no complete diagnostic result");
        size -= read;
        next += read;
    }
}
unsigned positive(const char* text, unsigned maximum) {
    char* end = nullptr;
    const auto value = std::strtoul(text, &end, 10);
    require(end != text && *end == '\0' && value > 0u && value <= maximum,
            "Invalid bounded diagnostic argument");
    return static_cast<unsigned>(value);
}
std::uint32_t crc(const std::uint8_t* bytes, std::size_t size) {
    require(size <= std::numeric_limits<uInt>::max(), "CRC input exceeds bounded frame size");
    return static_cast<std::uint32_t>(::crc32(0u, bytes, static_cast<uInt>(size)));
}
std::uint16_t word(const Bytes& bytes, std::size_t sample) {
    return static_cast<std::uint16_t>(bytes[2u * sample] | (bytes[2u * sample + 1u] << 8u));
}
void packFour(std::uint8_t* target, const std::uint16_t* values) {
    const std::uint64_t packed = std::uint64_t(values[0]) |
        (std::uint64_t(values[1]) << 10u) | (std::uint64_t(values[2]) << 20u) |
        (std::uint64_t(values[3]) << 30u);
    for (unsigned i = 0; i < 5u; ++i) target[i] = static_cast<std::uint8_t>(packed >> (8u * i));
}

// Exact signed modulo-1024 residuals; no rounding, denoise, sample loss or motion interpolation.
void packActual(const Bytes& current, const Bytes* previous, Bytes& target) {
    const auto count = current.size() / 2u;
    require(count % 8u == 0u, "Actual geometry is not an eight-sample packing boundary");
    std::array<std::uint16_t, 8> values{};
    std::size_t output = 0u;
    for (std::size_t i = 0u; i < count; i += 8u) {
#if defined(__aarch64__)
        const auto input = vld1q_u16(reinterpret_cast<const std::uint16_t*>(current.data()) + i);
        require(vmaxvq_u16(vandq_u16(input, vdupq_n_u16(63u))) == 0u,
                "Actual source contains noncanonical P010 padding");
        auto codes = vshrq_n_u16(input, 6);
        if (previous != nullptr) {
            const auto reference = vshrq_n_u16(vld1q_u16(
                reinterpret_cast<const std::uint16_t*>(previous->data()) + i), 6);
            const auto residual = vandq_u16(vsubq_u16(codes, reference), vdupq_n_u16(1023u));
            const auto signedResidual = vshrq_n_s16(vreinterpretq_s16_u16(vshlq_n_u16(residual, 6)), 6);
            codes = veorq_u16(vshlq_n_u16(vreinterpretq_u16_s16(signedResidual), 1),
                             vreinterpretq_u16_s16(vshrq_n_s16(signedResidual, 15)));
        }
        vst1q_u16(values.data(), codes);
#else
        for (unsigned lane = 0u; lane < 8u; ++lane) {
            const auto input = word(current, i + lane);
            require((input & 63u) == 0u, "Actual source contains noncanonical P010 padding");
            unsigned code = input >> 6u;
            if (previous != nullptr) {
                int difference = static_cast<int>(code) - (word(*previous, i + lane) >> 6u);
                if (difference < -512) difference += 1024;
                if (difference > 511) difference -= 1024;
                code = static_cast<unsigned>(difference < 0 ? -2 * difference - 1 : 2 * difference);
            }
            values[lane] = static_cast<std::uint16_t>(code);
        }
#endif
        packFour(target.data() + output, values.data());
        packFour(target.data() + output + 5u, values.data() + 4u);
        output += 10u;
    }
    require(output == count * 10u / 8u, "Exact packing length mismatch");
}

struct Encoded { std::size_t bytes; double elapsedMs; };
Encoded encode(const Bytes& current, const Bytes* previous, Bytes& packet) {
    const auto samples = current.size() / 2u;
    const auto bytes = samples * 10u / 8u;
    const auto started = Clock::now();
    packActual(current, previous, packet);
    const auto encoded = codec::compressPackedTenBitRealtimeRangeLz4InPlace(packet.data(),
        static_cast<std::int64_t>(bytes), static_cast<std::int64_t>(samples),
        static_cast<std::int64_t>(packet.size()), crc(packet.data(), bytes), 1024);
    const double elapsed = std::chrono::duration<double, std::milli>(Clock::now() - started).count();
    require(encoded.successful && encoded.storedBytes > 0, "Actual-frame lossless encoding failed");
    return {static_cast<std::size_t>(encoded.storedBytes), elapsed};
}
void verify(const Bytes& packet, std::size_t bytes, const Bytes& original,
            const Bytes* previous, Bytes& decoded) {
    require(codec::decodePacketToP010(packet.data(), static_cast<std::int64_t>(bytes),
            decoded.data(), static_cast<std::int64_t>(decoded.size())), "Encoded source-CRC/decode failure");
    for (std::size_t i = 0; i < original.size() / 2u; ++i) {
        const unsigned code = word(decoded, i) >> 6u;
        int restored = static_cast<int>(code);
        if (previous != nullptr) {
            const int delta = (code & 1u) ? -static_cast<int>(code / 2u) - 1 : static_cast<int>(code / 2u);
            restored = ((word(*previous, i) >> 6u) + delta + 1024) & 1023;
        }
        require((word(decoded, i) & 63u) == 0u && restored == (word(original, i) >> 6u),
                "Temporal reconstruction changed an actual recorded code value");
    }
}
} // namespace

int main(int argc, char** argv) {
    try {
        require(argc == 4, "Usage: actual NPV10 on stdin | probe width height prefix_frames(2..128)");
        const unsigned width = positive(argv[1], 4096u), height = positive(argv[2], 3072u);
        const unsigned frames = positive(argv[3], 128u);
        require(frames >= 2u && (width & 1u) == 0u && (height & 1u) == 0u,
                "Expected complete even P010 geometry and at least two real frames");
        std::setvbuf(stdin, nullptr, _IOFBF, 2u * 1024u * 1024u);
        std::array<std::uint8_t, 29> header{};
        readExact(header.data(), header.size());
        require(std::memcmp(header.data(), "NPV10", 5u) == 0 &&
                (be32(header.data() + 5u) == 1u || be32(header.data() + 5u) == 2u) &&
                be32(header.data() + 9u) == width && be32(header.data() + 13u) == height &&
                be32(header.data() + 17u) == 25u && be32(header.data() + 21u) == 10u,
                "Actual source geometry/25-FPS/10-bit header mismatch");
        const auto metadataBytes = be32(header.data() + 25u);
        require(metadataBytes > 0u && metadataBytes <= 1024u * 1024u, "Invalid metadata extent");
        Bytes metadata(metadataBytes);
        readExact(metadata.data(), metadata.size());
        const std::size_t decodedBytes = std::size_t(width) * height * 3u;
        const auto samples = decodedBytes / 2u;
        const auto capacity = codec::maximumStoredBytes(static_cast<std::int64_t>(samples * 10u / 8u),
                                                       static_cast<std::int64_t>(samples));
        require(capacity > 0, "Invalid bounded codec capacity");
        Bytes current(decodedBytes), previous(decodedBytes), decoded(decodedBytes);
        Bytes original(static_cast<std::size_t>(capacity)), baseline(original.size()), residual(original.size());
        std::uint64_t originalTotal = 0, baselineTotal = 0, selectedTotal = 0, previousTs = 0;
        double baselineMs = 0.0, residualMs = 0.0, residualMaxMs = 0.0;
        unsigned selectedResidual = 0, compared = 0, byteIdenticalBaseline = 0;
        for (unsigned index = 0; index < frames; ++index) {
            std::array<std::uint8_t, 28> frame{};
            readExact(frame.data(), frame.size());
            require(std::memcmp(frame.data(), "FRM0", 4u) == 0, "Unexpected actual frame marker");
            const auto timestamp = be64(frame.data() + 4u);
            const auto bytes = be32(frame.data() + 20u);
            require(timestamp > previousTs && be32(frame.data() + 12u) == width &&
                    be32(frame.data() + 16u) == height && bytes > 0u && bytes <= original.size(),
                    "Invalid actual source frame identity/extent/timestamp");
            previousTs = timestamp;
            readExact(original.data(), bytes);
            require(crc(original.data(), bytes) == be32(frame.data() + 24u), "Actual stored CRC mismatch");
            require(codec::decodePacketToP010(original.data(), bytes, current.data(), decodedBytes),
                    "Actual source decode/original-code CRC failure");
            const auto base = encode(current, nullptr, baseline);
            verify(baseline, base.bytes, current, nullptr, decoded);
            baselineMs += base.elapsedMs;
            baselineTotal += base.bytes;
            originalTotal += bytes;
            selectedTotal += bytes; // Independent keyframe every 25 source frames; never downsampled.
            if (base.bytes == bytes && std::memcmp(original.data(), baseline.data(), bytes) == 0)
                ++byteIdenticalBaseline;
            if (index % 25u != 0u) {
                const auto delta = encode(current, &previous, residual);
                verify(residual, delta.bytes, current, &previous, decoded);
                residualMs += delta.elapsedMs;
                residualMaxMs = std::max(residualMaxMs, delta.elapsedMs);
                ++compared;
                // Account for an explicit reference index + reference CRC in a hypothetical format.
                if (delta.bytes + 12u < bytes) {
                    selectedTotal -= bytes - delta.bytes - 12u;
                    ++selectedResidual;
                }
            }
            current.swap(previous);
            if ((index + 1u) % 16u == 0u) {
                std::printf("REAL_TEMPORAL_PREFIX frames=%u original_bytes=%llu selected_bytes=%llu exact_codes=true\n",
                    index + 1u, static_cast<unsigned long long>(originalTotal),
                    static_cast<unsigned long long>(selectedTotal));
                std::fflush(stdout);
            }
        }
        std::printf("REAL_TEMPORAL_RESULT frames=%u width=%u height=%u original_bytes=%llu "
            "baseline_bytes=%llu baseline_byte_identical_frames=%u selected_bytes=%llu "
            "selected_residual_frames=%u saved_percent=%.6f baseline_mean_encode_ms=%.6f "
            "residual_mean_encode_ms=%.6f residual_max_encode_ms=%.6f exact_codes=true "
            "source_written=false app_enabled=false scope=actual_prefix_not_camera_fps\n",
            frames, width, height, static_cast<unsigned long long>(originalTotal),
            static_cast<unsigned long long>(baselineTotal), byteIdenticalBaseline,
            static_cast<unsigned long long>(selectedTotal), selectedResidual,
            100.0 * (1.0 - static_cast<double>(selectedTotal) / originalTotal),
            baselineMs / frames, residualMs / compared, residualMaxMs);
        return 0;
    } catch (const std::exception& error) {
        std::fprintf(stderr, "REAL_TEMPORAL_PROBE_FAILED: %s\n", error.what());
        return 1;
    }
}
