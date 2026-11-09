// Paired physical storage test of the production writer, not a camera recording PASS.
#include "../app/src/main/cpp/npv10_realtime_aligned_writer.h"
#include "neo_qa_hypergrid/probes/rejected_f2fs/npv10_physical_extent_reservation.h"
#include <array>
#include <chrono>
#include <cstdio>
#include <stdexcept>
#include <vector>

namespace {
using Clock = std::chrono::steady_clock;
constexpr std::int64_t kReservation = 2LL * 1024LL * 1024LL * 1024LL;
constexpr std::size_t kPrefixBytes = 13661;
constexpr unsigned kFrames = 96;
struct OwnedFile {
    char path[96] = "/data/local/tmp/camx-physical-writer-XXXXXX";
    int fd = ::mkstemp(path);
    ~OwnedFile() { if (fd >= 0) { (void)::close(fd); (void)::unlink(path); } }
};
void check(bool valid, const char* message) { if (!valid) throw std::runtime_error(message); }
struct Result { double mean = 0, maximum = 0; unsigned misses = 0; };

Result run(bool physical, const std::vector<std::uint8_t>& payload, const char* order) {
    OwnedFile file;
    check(file.fd >= 0, "own temporary file");
    const auto allocationStart = Clock::now();
    if (physical) {
        const auto result = neoproc::npv10::PhysicalExtentReservation<>::allocate(file.fd, 0, kReservation);
        std::printf("ALLOCATION_RESULT error=%d capability_errno=%d release_errno=%d physical=%d\n",
            result.error, result.capabilityError, result.releaseError, result.materialized);
        check(result.error == 0 && result.materialized, "physical allocation not admitted");
    } else check(::posix_fallocate64(file.fd, 0, kReservation) == 0, "legacy allocation failed");
    const double allocationMs = std::chrono::duration<double, std::milli>(Clock::now() - allocationStart).count();
    std::vector<std::uint8_t> prefix(kPrefixBytes, 0x6b);
    check(::pwrite64(file.fd, prefix.data(), prefix.size(), 0) == static_cast<ssize_t>(prefix.size()), "prefix write");
    check(::fsync(file.fd) == 0, "initial durability");
    neoproc::npv10::RealtimeAlignedRecordWriter writer(file.fd, file.path, kPrefixBytes, kReservation);
    check(writer.admission() == decltype(writer)::Admission::Active && writer.kernelAioEnabled(), "writer capability");
    Result result;
    std::int64_t offset = kPrefixBytes;
    std::array<std::uint8_t, 28> header {};
    std::vector<std::uint8_t> readback(payload.size());
    for (unsigned frame = 0; frame < kFrames; ++frame) {
        header.fill(static_cast<std::uint8_t>(frame));
        const auto began = Clock::now();
        check(writer.writeRecord(header.data(), header.size(), payload.data(), payload.size(), offset, kReservation), "record write");
        const double ms = std::chrono::duration<double, std::milli>(Clock::now() - began).count();
        result.mean += ms;
        result.maximum = std::max(result.maximum, ms);
        if (ms > 40.0) ++result.misses;
        offset += static_cast<std::int64_t>(header.size() + payload.size());
        if (frame == 0 || frame == 59) check(::fdatasync(file.fd) == 0, "intermediate durability");
    }
    check(writer.close(), "writer close");
    check(::ftruncate64(file.fd, offset) == 0 && ::fsync(file.fd) == 0, "final durability");
    std::int64_t position = kPrefixBytes;
    for (unsigned frame = 0; frame < kFrames; ++frame) {
        check(::pread64(file.fd, header.data(), header.size(), position) == static_cast<ssize_t>(header.size()), "header read");
        for (auto byte : header) check(byte == static_cast<std::uint8_t>(frame), "header/order mismatch");
        position += header.size();
        std::size_t done = 0;
        while (done < readback.size()) {
            const auto got = ::pread64(file.fd, readback.data() + done, readback.size() - done, position + done);
            if (got < 0 && errno == EINTR) continue;
            check(got > 0, "complete payload read");
            done += static_cast<std::size_t>(got);
        }
        check(payload == readback, "recorded bytes differ");
        position += static_cast<std::int64_t>(payload.size());
    }
    check(position == offset, "logical end mismatch");
    result.mean /= kFrames;
    std::printf("PHYSICAL_WRITER order=%s materialized=%d payload_bytes=%zu frames=%u mean_ms=%.6f "
        "max_ms=%.6f over_40ms=%u allocation_ms=%.6f all_bytes_exact=true\n", order, physical,
        payload.size(), kFrames, result.mean, result.maximum, result.misses, allocationMs);
    std::fflush(stdout);
    return result;
}
} // namespace
int main() {
    try {
        std::vector<std::uint8_t> payload(14u * 1024u * 1024u + 37u);
        std::uint32_t seed = 0x68fd731bu;
        for (auto& byte : payload) { seed ^= seed << 13u; seed ^= seed >> 17u; seed ^= seed << 5u; byte = seed; }
        run(false, payload, "A1");
        run(true, payload, "B1");
        run(true, payload, "B2");
        run(false, payload, "A2");
        std::puts("PAIRED_COMPLETE all_bytes_exact=true scope=physical_storage_only_no_camera_pass");
        return 0;
    } catch (const std::exception& error) {
        std::fprintf(stderr, "PHYSICAL_WRITER_FAIL %s errno=%d\n", error.what(), errno);
        return 1;
    }
}
