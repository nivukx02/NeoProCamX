// Real-filesystem comparison using an exact recorded P010 frame. Not a camera-FPS PASS.
#include "npv10_aligned_record_writer.h"
#include "npv10_lossless_chunk_codec.h"
#include "third_party/lz4/lz4.h"
#include <array>
#include <chrono>
#include <cstdio>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>
#include <zlib.h>

namespace {
using Bytes = std::vector<std::uint8_t>;
using Clock = std::chrono::steady_clock;
namespace codec = neoproc::npv10::lossless;
using Existing = neoproc::npv10::AlignedRecordWriter<
    neoproc::npv10::AlignedRecordPosixIo, 8u * 1024u * 1024u, false, 1u, true>;
using WholeRecord = neoproc::npv10::AlignedRecordWriter<
    neoproc::npv10::AlignedRecordPosixIo, 32u * 1024u * 1024u, false, 1u, true,
    neoproc::npv10::KernelAioPosix, 32u * 1024u * 1024u>;

void require(bool condition, const char* reason) {
    if (!condition) throw std::runtime_error(reason);
}

std::uint32_t crc(const Bytes& bytes) {
    return static_cast<std::uint32_t>(::crc32(0u, bytes.data(), static_cast<uInt>(bytes.size())));
}

Bytes recordedPacket(const char* path) {
    std::unique_ptr<FILE, decltype(&std::fclose)> input(std::fopen(path, "rb"), &std::fclose);
    require(input != nullptr, "Cannot open actual recorded P010 sample");
    require(std::fseek(input.get(), 0, SEEK_END) == 0, "Sample seek failed");
    const long size = std::ftell(input.get());
    require(size > 0 && size <= 4096L * 3072L * 3L && size % 2L == 0, "Invalid sample size");
    require(std::fseek(input.get(), 0, SEEK_SET) == 0, "Sample rewind failed");
    Bytes original(static_cast<std::size_t>(size));
    require(std::fread(original.data(), 1u, original.size(), input.get()) == original.size(),
        "Actual sample is truncated");
    const auto samples = original.size() / 2u;
    Bytes packed((samples * 10u + 7u) / 8u, 0u);
    std::uint64_t accumulator = 0;
    unsigned bits = 0;
    std::size_t out = 0;
    for (std::size_t i = 0; i < samples; ++i) {
        const unsigned word = original[2u * i] | (original[2u * i + 1u] << 8u);
        require((word & 63u) == 0u, "Noncanonical P010 sample");
        accumulator |= static_cast<std::uint64_t>(word >> 6u) << bits;
        bits += 10u;
        while (bits >= 8u) {
            packed[out++] = static_cast<std::uint8_t>(accumulator);
            accumulator >>= 8u;
            bits -= 8u;
        }
    }
    if (bits != 0u) packed[out++] = static_cast<std::uint8_t>(accumulator);
    require(out == packed.size(), "Packed size mismatch");
    const auto packedBytes = packed.size();
    const auto packedCrc = crc(packed);
    const auto capacity = codec::maximumStoredBytes(static_cast<std::int64_t>(packedBytes),
        static_cast<std::int64_t>(samples));
    require(capacity > 0, "Codec capacity failed");
    packed.resize(static_cast<std::size_t>(capacity));
    const auto encoded = codec::compressPackedTenBitRealtimeRangeLz4InPlace(packed.data(),
        static_cast<std::int64_t>(packedBytes), static_cast<std::int64_t>(samples), capacity,
        packedCrc, 1024);
    require(encoded.successful, "Actual frame encoding failed");
    packed.resize(static_cast<std::size_t>(encoded.storedBytes));
    Bytes decoded(original.size());
    require(codec::decodePacketToP010(packed.data(), encoded.storedBytes, decoded.data(), size) &&
        decoded == original, "Original recorded codes changed");
    std::printf("ACTUAL_SAMPLE bytes=%zu p010_crc=%u packet_bytes=%zu packet_crc=%u bit_exact=true\n",
        original.size(), crc(original), packed.size(), crc(packed));
    std::fflush(stdout);
    return packed;
}

struct OwnedFile {
    std::array<char, 96> path{};
    int fd = -1;
    OwnedFile() {
        std::snprintf(path.data(), path.size(), "/data/local/tmp/lq001_aio_record_window_XXXXXX");
        fd = ::mkstemp(path.data());
        require(fd >= 0, "Cannot create exact exclusive diagnostic file");
    }
    ~OwnedFile() {
        if (fd >= 0) { (void)::close(fd); (void)::unlink(path.data()); }
    }
};

void readExact(int fd, std::uint8_t* bytes, std::size_t count, std::int64_t offset) {
    while (count != 0u) {
        const auto result = ::pread64(fd, bytes, count, static_cast<off64_t>(offset));
        if (result < 0 && errno == EINTR) continue;
        require(result > 0 && static_cast<std::size_t>(result) <= count, "Record readback failed");
        count -= static_cast<std::size_t>(result);
        bytes += result;
        offset += result;
    }
}

// Read-only experiment: estimate whether another strictly lossless transform could relieve
// the measured flash stalls. It is NOT a supported app container or an enabled codec path.
// Every measured output must reproduce the exact existing NLC2 packet; no synthetic pixels.
void analyzeLosslessOverlay(const Bytes& packet, unsigned iterations) {
    require(packet.size() <= static_cast<std::size_t>(LZ4_MAX_INPUT_SIZE),
        "Actual recorded packet exceeds LZ4's bounded input size");
    const int size = static_cast<int>(packet.size());
    Bytes compressed(static_cast<std::size_t>(LZ4_compressBound(size)));
    Bytes decoded(packet.size());
    LZ4_stream_t state{};
    for (const int acceleration : {1, 4, 16, 64}) {
        double totalMs = 0.0, maximumMs = 0.0;
        int compressedBytes = 0;
        for (unsigned iteration = 0; iteration < iterations; ++iteration) {
            const auto started = Clock::now();
            const int written = LZ4_compress_fast_extState(&state,
                reinterpret_cast<const char*>(packet.data()),
                reinterpret_cast<char*>(compressed.data()), size,
                static_cast<int>(compressed.size()), acceleration);
            const auto elapsed = std::chrono::duration<double, std::milli>(
                Clock::now() - started).count();
            require(written > 0 && written <= static_cast<int>(compressed.size()),
                "Actual packet LZ4 analysis failed");
            require(iteration == 0u || written == compressedBytes,
                "Repeated exact input produced an inconsistent encoded length");
            compressedBytes = written;
            require(LZ4_decompress_safe(reinterpret_cast<const char*>(compressed.data()),
                reinterpret_cast<char*>(decoded.data()), written, size) == size && decoded == packet,
                "Lossless overlay changed actual recorded packet bytes");
            totalMs += elapsed;
            maximumMs = std::max(maximumMs, elapsed);
        }
        std::printf("ACTUAL_LOSSLESS_OVERLAY acceleration=%d iterations=%u packet_bytes=%zu "
            "overlay_bytes=%d saved_percent=%.6f additional_mean_ms=%.6f additional_max_ms=%.6f "
            "bit_exact=true app_enabled=false scope=actual_packet_analysis_not_camera_fps\n",
            acceleration, iterations, packet.size(), compressedBytes,
            100.0 * (1.0 - static_cast<double>(compressedBytes) / size),
            totalMs / iterations, maximumMs);
        std::fflush(stdout);
    }
}

template<class Writer> void benchmark(const Bytes& packet, unsigned frames, const char* name) {
    constexpr std::int64_t prefixBytes = 13654;
    constexpr std::int64_t reservation = 2LL * 1024LL * 1024LL * 1024LL;
    require(prefixBytes + frames * (packet.size() + 28u) + 16384u < reservation,
        "Requested frames exceed the bounded two-GiB diagnostic reservation");
    OwnedFile file;
    require(::posix_fallocate64(file.fd, 0, reservation) == 0, "Physical reservation failed");
    Bytes prefix(static_cast<std::size_t>(prefixBytes), 0x79u);
    require(::pwrite64(file.fd, prefix.data(), prefix.size(), 0) ==
        static_cast<ssize_t>(prefix.size()), "Prefix write failed");
    require(::fsync(file.fd) == 0, "Prefix durability failed");
    Writer writer(file.fd, file.path.data(), prefixBytes, reservation);
    require(writer.admission() == Writer::Admission::Active && writer.probeVerified() &&
        writer.kernelAioEnabled(), "Exact kernel-AIO writer was not admitted");
    std::array<std::uint8_t, 28> header{};
    std::int64_t offset = prefixBytes;
    double totalMs = 0.0, maximumMs = 0.0, syncMs = 0.0;
    unsigned above40 = 0;
    for (unsigned frame = 0; frame < frames; ++frame) {
        header.fill(static_cast<std::uint8_t>(frame));
        const auto started = Clock::now();
        require(writer.writeRecord(header.data(), header.size(), packet.data(), packet.size(),
            offset, reservation), "Record write/completion failed");
        const double ms = std::chrono::duration<double, std::milli>(Clock::now()-started).count();
        totalMs += ms;
        maximumMs = std::max(maximumMs, ms);
        if (ms > 40.0) ++above40;
        offset += static_cast<std::int64_t>(header.size() + packet.size());
        if (frame == 0u || (frame + 1u) % 120u == 0u) {
            const auto syncStarted = Clock::now();
            require(::fdatasync(file.fd) == 0, "Periodic durability failed");
            syncMs += std::chrono::duration<double, std::milli>(Clock::now()-syncStarted).count();
        }
    }
    require(writer.kernelAioSubmissions() == writer.kernelAioCompletions(), "Outstanding kernel reader");
    require(writer.close(), "Kernel reader retirement failed");
    require(::ftruncate64(file.fd, offset) == 0 && ::fsync(file.fd) == 0, "Final durability failed");
    Bytes review(prefix.size());
    readExact(file.fd, review.data(), review.size(), 0);
    require(review == prefix, "Existing prefix was altered");
    review.resize(packet.size() + header.size());
    std::int64_t readOffset = prefixBytes;
    for (unsigned frame = 0; frame < frames; ++frame) {
        readExact(file.fd, review.data(), review.size(), readOffset);
        header.fill(static_cast<std::uint8_t>(frame));
        require(std::memcmp(review.data(), header.data(), header.size()) == 0 &&
            std::memcmp(review.data()+header.size(), packet.data(), packet.size()) == 0,
            "Complete file readback changed frame bytes or ordering");
        readOffset += static_cast<std::int64_t>(review.size());
    }
    require(readOffset == offset, "Readback did not cover the complete original");
    std::printf("WINDOW_RESULT backend=%s frames=%u packet_bytes=%zu mean_ms=%.6f max_ms=%.6f "
        "writes_above_40ms=%u periodic_sync_ms=%.6f aio_submissions=%llu aio_completions=%llu "
        "bit_exact=true full_file_readback=true scope=storage_not_camera_fps\n", name, frames,
        packet.size(), totalMs / frames, maximumMs, above40, syncMs,
        static_cast<unsigned long long>(writer.kernelAioSubmissions()),
        static_cast<unsigned long long>(writer.kernelAioCompletions()));
}
} // namespace

int main(int argc, char** argv) {
    try {
        require(argc == 4, "Expected actual.p010 existing|whole-record|lossless-analysis frame-count");
        const std::string selected = argv[2];
        require(selected == "existing" || selected == "whole-record" || selected == "lossless-analysis",
            "Unknown writer selection");
        const std::string count = argv[3];
        require(!count.empty() && count.find_first_not_of("0123456789") == std::string::npos,
            "Invalid frame count");
        const auto requestedFrames = std::stoul(count);
        require(requestedFrames >= 2u && requestedFrames <= 128u,
            "Frame count outside bounded diagnostic range");
        const unsigned frames = static_cast<unsigned>(requestedFrames);
        const auto packet = recordedPacket(argv[1]);
        if (selected == "existing") benchmark<Existing>(packet, frames, "existing_8mib_group");
        else if (selected == "whole-record") benchmark<WholeRecord>(packet, frames, "whole_record_32mib_group");
        else analyzeLosslessOverlay(packet, frames);
        return 0;
    } catch (const std::exception& failure) {
        std::fprintf(stderr, "WINDOW_TEST_FAILED reason=%s errno=%d\n", failure.what(), errno);
        return 1;
    }
}
