// Independent container/timestamp/stored-CRC audit of an actual phone recording.
// The production decoder additionally verifies the original ten-bit sample CRC of EVERY packet.
// This is not a camera simulator or a calibrated image-quality test.
#include "npv10_lossless_chunk_codec.h"

#include <zlib.h>
#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <limits>
#include <stdexcept>
#include <vector>

namespace {
using Bytes = std::vector<std::uint8_t>;

void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

std::uint32_t be32(const std::uint8_t* bytes) {
    return (std::uint32_t(bytes[0]) << 24u) | (std::uint32_t(bytes[1]) << 16u) |
           (std::uint32_t(bytes[2]) << 8u) | std::uint32_t(bytes[3]);
}

std::uint64_t be64(const std::uint8_t* bytes) {
    return (std::uint64_t(be32(bytes)) << 32u) | be32(bytes + 4);
}

void readExactly(void* target, std::size_t count) {
    auto* next = static_cast<std::uint8_t*>(target);
    while (count != 0u) {
        const auto read = std::fread(next, 1u, count, stdin);
        require(read != 0u, "Truncated recording or input read failure");
        next += read;
        count -= read;
    }
}

std::uint32_t positive(const char* text) {
    char* end = nullptr;
    const auto value = std::strtoul(text, &end, 10);
    require(end != text && *end == '\0' && value > 0u && value <= 8192u,
            "Invalid requested geometry/FPS");
    return static_cast<std::uint32_t>(value);
}

void selfTest() {
    const std::uint8_t value[] = {0x00, 0x00, 0x00, 0x01, 0x12, 0x34, 0x56, 0x78};
    require(be32(value + 4) == 0x12345678u, "BE32 parser self-test failed");
    require(be64(value) == 0x112345678ull, "BE64 parser self-test failed");
    const auto* check = reinterpret_cast<const Bytef*>("123456789");
    require(crc32(0u, check, 9u) == 0xcbf43926u, "Stored CRC self-test failed");
    std::puts("AUDITOR_SELF_TEST_PASS; not camera-FPS evidence");
}
}  // namespace

int main(int argc, char** argv) {
    try {
        if (argc == 2 && std::strcmp(argv[1], "--self-test") == 0) {
            selfTest();
            return 0;
        }
        require(argc == 5, "Usage: <actual NPV10 on stdin> audit width height fps minimum_seconds");
        const auto width = positive(argv[1]);
        const auto height = positive(argv[2]);
        const auto fps = positive(argv[3]);
        require((width & 1u) == 0u && (height & 1u) == 0u && fps <= 120u,
                "Expected even P010 geometry and a valid selected FPS");
        char* end = nullptr;
        const double minimumSeconds = std::strtod(argv[4], &end);
        require(end != argv[4] && *end == '\0' && std::isfinite(minimumSeconds) &&
                    minimumSeconds >= 0.0 && minimumSeconds <= 7200.0,
                "Invalid minimum recording duration");
        std::setvbuf(stdin, nullptr, _IOFBF, 4u * 1024u * 1024u);
        const auto began = std::chrono::steady_clock::now();
        std::array<std::uint8_t, 29> header{};
        readExactly(header.data(), header.size());
        require(std::memcmp(header.data(), "NPV10", 5u) == 0, "Not an NPV10 master");
        const auto containerVersion = be32(header.data() + 5);
        require(containerVersion == 1u || containerVersion == 2u, "Unsupported container version");
        require(be32(header.data() + 9) == width && be32(header.data() + 13) == height &&
                    be32(header.data() + 17) == fps && be32(header.data() + 21) == 10u,
                "Selected geometry/FPS/bit-depth mismatch");
        const auto metadataBytes = be32(header.data() + 25);
        require(metadataBytes > 0u && metadataBytes <= 1024u * 1024u, "Invalid metadata size");
        Bytes metadata(metadataBytes);
        readExactly(metadata.data(), metadata.size());

        const auto decodedBytes = static_cast<std::uint64_t>(width) * height * 3u;
        Bytes decoded(static_cast<std::size_t>(decodedBytes));
        Bytes stored;
        std::uint64_t frames = 0, firstTimestamp = 0, lastTimestamp = 0;
        std::uint64_t minimumInterval = std::numeric_limits<std::uint64_t>::max();
        std::uint64_t maximumInterval = 0, longGaps = 0, nonMonotonic = 0;
        std::uint64_t storedBytes = 0, containerBytes = header.size() + metadata.size();
        std::uint64_t version1Frames = 0, version2Frames = 0;
        const auto maximumStored = neoproc::npv10::lossless::maximumStoredBytes(
            static_cast<std::int64_t>(decodedBytes * 5u / 8u),
            static_cast<std::int64_t>(decodedBytes / 2u));

        while (true) {
            std::array<std::uint8_t, 28> frame{};
            const auto count = std::fread(frame.data(), 1u, 4u, stdin);
            if (count == 0u) {
                require(std::feof(stdin) != 0 && std::ferror(stdin) == 0, "Recording read failed");
                break;
            }
            require(count == 4u && std::memcmp(frame.data(), "FRM0", 4u) == 0,
                    "Truncated or invalid frame marker");
            readExactly(frame.data() + 4, frame.size() - 4u);
            const auto timestamp = be64(frame.data() + 4);
            require(timestamp > 0u && timestamp <= std::uint64_t(std::numeric_limits<std::int64_t>::max()),
                    "Invalid sensor timestamp");
            require(be32(frame.data() + 12) == width && be32(frame.data() + 16) == height,
                    "Frame geometry changed");
            const auto bytes = be32(frame.data() + 20);
            const auto expectedCrc = be32(frame.data() + 24);
            require(bytes >= 32u && maximumStored > 0 && bytes <= std::uint64_t(maximumStored),
                    "Frame storage exceeds the unchanged lossless payload bound");
            stored.resize(bytes);
            readExactly(stored.data(), stored.size());
            require(crc32(0u, stored.data(), static_cast<uInt>(stored.size())) == expectedCrc,
                    "Stored frame CRC mismatch");
            require(std::memcmp(stored.data(), "NLC2", 4u) == 0, "Frame is not chunked lossless P010");
            const auto packetVersion = be32(stored.data() + 4);
            require(packetVersion == 1u || packetVersion == 2u, "Unsupported lossless packet version");
            require(neoproc::npv10::lossless::decodePacketToP010(
                        stored.data(), bytes, decoded.data(), static_cast<std::int64_t>(decodedBytes)),
                    "Decoded original ten-bit sample CRC, chunk boundary or padding validation failed");
            for (std::size_t sample = 0; sample < decoded.size(); sample += 2u) {
                require((decoded[sample] & 0x3fu) == 0u, "Decoded P010 sample has nonzero padding bits");
            }
            if (frames == 0u) firstTimestamp = timestamp;
            else if (timestamp <= lastTimestamp) ++nonMonotonic;
            else {
                const auto interval = timestamp - lastTimestamp;
                minimumInterval = std::min(minimumInterval, interval);
                maximumInterval = std::max(maximumInterval, interval);
                if (static_cast<long double>(interval) * fps > 1500000000.0L) ++longGaps;
            }
            lastTimestamp = timestamp;
            ++frames;
            if (packetVersion == 2u) ++version2Frames;
            else ++version1Frames;
            storedBytes += bytes;
            containerBytes += frame.size() + bytes;
            if (frames % 500u == 0u) {
                std::fprintf(stderr, "AUDIT_PROGRESS actualFrames=%llu allStoredAndSourceCrcValid=true\n",
                             static_cast<unsigned long long>(frames));
            }
        }
        require(frames >= 2u && lastTimestamp > firstTimestamp, "Insufficient actual recorded frames");
        const double spanSeconds = static_cast<double>(lastTimestamp - firstTimestamp) / 1.0e9;
        const double actualFps = static_cast<double>(frames - 1u) / spanSeconds;
        const double duration = spanSeconds + 1.0 / fps;
        const double elapsed = std::chrono::duration<double>(std::chrono::steady_clock::now() - began).count();
        const bool cadence = nonMonotonic == 0u && longGaps == 0u &&
            std::abs(actualFps - fps) <= fps * 0.002 && duration >= minimumSeconds;
        std::printf(
            "NPV10_REAL_FILE_AUDIT status=%s width=%u height=%u requestedFps=%u bitDepth=10 "
            "frames=%llu durationSeconds=%.9f actualSensorFps=%.9f "
            "firstTimestampNs=%llu lastTimestampNs=%llu minIntervalNs=%llu maxIntervalNs=%llu "
            "gapsAbove1p5FramePeriods=%llu nonMonotonic=%llu "
            "storedCrcFailures=0 decodedSourceCrcFailures=0 allP010PaddingValid=true "
            "version1Frames=%llu version2Frames=%llu storedBytes=%llu containerBytes=%llu "
            "minimumSeconds=%.3f fpsTolerance=0.2percent auditWallSeconds=%.3f "
            "physicalQualityAndAudioSync=NOT_ASSESSED\n",
            cadence ? "PASS" : "FAIL", width, height, fps,
            static_cast<unsigned long long>(frames), duration, actualFps,
            static_cast<unsigned long long>(firstTimestamp), static_cast<unsigned long long>(lastTimestamp),
            static_cast<unsigned long long>(minimumInterval), static_cast<unsigned long long>(maximumInterval),
            static_cast<unsigned long long>(longGaps), static_cast<unsigned long long>(nonMonotonic),
            static_cast<unsigned long long>(version1Frames), static_cast<unsigned long long>(version2Frames),
            static_cast<unsigned long long>(storedBytes), static_cast<unsigned long long>(containerBytes),
            minimumSeconds, elapsed);
        return cadence ? 0 : 2;
    } catch (const std::exception& error) {
        std::fprintf(stderr, "NPV10_REAL_FILE_AUDIT FAIL: %s\n", error.what());
        return 1;
    }
}
