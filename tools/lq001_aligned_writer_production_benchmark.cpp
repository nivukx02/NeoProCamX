// Physical-device storage probe, not a camera-frame-rate or image-quality result.
#include "../app/src/main/cpp/npv10_aligned_record_writer.h"
#include <memory>
#if defined(NEO_NPV10_TEST_CONCURRENT_WRITER)
using ProductionWriter = neoproc::npv10::AlignedRecordWriter<
    neoproc::npv10::AlignedRecordPosixIo, 2u * 1024u * 1024u, true, 2u>;
#elif defined(NEO_NPV10_TEST_PIPELINED_WRITER)
using ProductionWriter = neoproc::npv10::AlignedRecordWriter<
    neoproc::npv10::AlignedRecordPosixIo, 2u * 1024u * 1024u, true>;
#else
using ProductionWriter = neoproc::npv10::AlignedRecordWriter<>;
#endif

#include <array>
#include <chrono>
#include <cstdio>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
using Clock = std::chrono::steady_clock;
constexpr std::size_t kPrefixBytes = 13654u;
constexpr std::size_t kPayloadBytes = 14u * 1024u * 1024u + 37u;
constexpr unsigned kFrames = 128u;
constexpr std::int64_t kReservation = 2LL * 1024LL * 1024LL * 1024LL;

void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

struct TemporaryFile {
    std::array<char, 80> path{};
    int fd = -1;
    TemporaryFile() {
        std::snprintf(path.data(), path.size(), "/data/local/tmp/neo_lq001_io_probe_XXXXXX");
        fd = ::mkstemp(path.data());
        require(fd >= 0, "Cannot create exclusive temporary test file");
    }
    ~TemporaryFile() {
        if (fd >= 0) {
            (void)::close(fd);
            (void)::unlink(path.data());  // Only the fresh mkstemp file owned by this instance.
        }
    }
};

void writeAll(int fd, const std::uint8_t* bytes, std::size_t count, std::int64_t offset) {
    while (count != 0u) {
        const auto request = std::min(count, ProductionWriter::kCapacity);
        const auto written = ::pwrite64(fd, bytes, request, static_cast<off64_t>(offset));
        if (written < 0 && errno == EINTR) continue;
        require(written > 0, "Buffered write failed");
        bytes += written;
        count -= static_cast<std::size_t>(written);
        offset += written;
    }
}

struct Result { double meanMs = 0; double maxMs = 0; double syncMs = 0; std::int64_t end = 0; };

Result benchmark(TemporaryFile& file, const std::vector<std::uint8_t>& payload, bool aligned) {
    require(::posix_fallocate64(file.fd, 0, kReservation) == 0, "Physical reservation failed");
    std::vector<std::uint8_t> prefix(kPrefixBytes, 0x79u);
    writeAll(file.fd, prefix.data(), prefix.size(), 0);
    require(::fsync(file.fd) == 0, "Initial metadata durability failed");
    std::unique_ptr<ProductionWriter> writer;
    if (aligned) {
        writer = std::make_unique<ProductionWriter>(file.fd, file.path.data(), kPrefixBytes, kReservation);
        require(writer->admission() == ProductionWriter::Admission::Active && writer->probeVerified(),
            "Production aligned I/O admission failed");
    }
    std::array<std::uint8_t, 28> header{};
    Result result;
    result.end = kPrefixBytes;
    for (unsigned frame = 0u; frame < kFrames; ++frame) {
        header.fill(static_cast<std::uint8_t>(frame));
        const auto start = Clock::now();
        if (aligned) {
            require(writer->writeRecord(header.data(), header.size(), payload.data(), payload.size(),
                result.end, kReservation), "Production aligned record failed");
        } else {
            writeAll(file.fd, header.data(), header.size(), result.end);
            writeAll(file.fd, payload.data(), payload.size(), result.end + header.size());
        }
        result.end += static_cast<std::int64_t>(header.size() + payload.size());
        const auto ms = std::chrono::duration<double, std::milli>(Clock::now() - start).count();
        result.meanMs += ms;
        result.maxMs = std::max(result.maxMs, ms);
        if (frame == 0u || (frame + 1u) % 120u == 0u) {
            const auto syncStart = Clock::now();
            require(::fdatasync(file.fd) == 0, "Recovery checkpoint durability failed");
            result.syncMs += std::chrono::duration<double, std::milli>(Clock::now() - syncStart).count();
        }
    }
    if (writer) require(writer->close(), "Production direct descriptor close failed");
    const auto closeStart = Clock::now();
    require(::ftruncate64(file.fd, result.end) == 0 && ::fsync(file.fd) == 0, "Final durability failed");
    result.syncMs += std::chrono::duration<double, std::milli>(Clock::now() - closeStart).count();
    result.meanMs /= kFrames;
    std::printf("{\"backend\":\"%s\",\"frames\":%u,\"bytes\":%lld,\"write_mean_ms\":%.6f,"
                "\"write_max_ms\":%.6f,\"checkpoint_and_final_sync_ms\":%.6f}\n",
        aligned ? "aligned_production" : "buffered_reference", kFrames,
        static_cast<long long>(result.end), result.meanMs, result.maxMs, result.syncMs);
    std::fflush(stdout);
    return result;
}

void equalFiles(const TemporaryFile& left, const TemporaryFile& right, std::int64_t size) {
    std::vector<std::uint8_t> a(1024u * 1024u);
    std::vector<std::uint8_t> b(a.size());
    for (std::int64_t offset = 0; offset < size;) {
        const auto count = static_cast<std::size_t>(std::min<std::int64_t>(size - offset, a.size()));
        require(::pread64(left.fd, a.data(), count, offset) == static_cast<ssize_t>(count), "Reference read failed");
        require(::pread64(right.fd, b.data(), count, offset) == static_cast<ssize_t>(count), "Candidate read failed");
        require(std::memcmp(a.data(), b.data(), count) == 0, "Byte-exact equality failed");
        offset += static_cast<std::int64_t>(count);
    }
}
}  // namespace

int main() {
    try {
        std::vector<std::uint8_t> payload(kPayloadBytes);
        std::uint32_t state = 0x873214abu;
        for (auto& byte : payload) {
            state ^= state << 13u; state ^= state >> 17u; state ^= state << 5u;
            byte = static_cast<std::uint8_t>(state);
        }
        TemporaryFile reference;
        TemporaryFile candidate;
        const auto buffered = benchmark(reference, payload, false);
        const auto aligned = benchmark(candidate, payload, true);
        require(buffered.end == aligned.end, "Final logical sizes differ");
        equalFiles(reference, candidate, buffered.end);
        std::printf("{\"scope\":\"production_storage_microbenchmark_not_camera_fps\",\"bit_exact\":true,"
                    "\"write_speed_ratio\":%.6f,\"installed_application_changed\":false}\n",
            buffered.meanMs / aligned.meanMs);
        return 0;
    } catch (const std::exception& error) {
        std::fprintf(stderr, "ALIGNED_WRITER_TEST_FAILED: %s (errno=%d)\n", error.what(), errno);
        return 1;
    }
}
