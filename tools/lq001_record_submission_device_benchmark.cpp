// Executes the production aligned writer on Android. Storage timings are NOT camera-FPS proof.
#include "../app/src/main/cpp/npv10_aligned_record_writer.h"

#include <array>
#include <chrono>
#include <cstdio>
#include <stdexcept>
#include <vector>

namespace {
using Clock = std::chrono::steady_clock;
using PosixIo = neoproc::npv10::AlignedRecordPosixIo;
constexpr std::int64_t kReservation = 2LL * 1024LL * 1024LL * 1024LL;
constexpr std::size_t kPrefixBytes = 13661u;
constexpr unsigned kFrames = 64u;
#if defined(NEO_NPV10_TEST_KERNEL_AIO)
constexpr bool kCandidateKernelAio = true;
#if defined(NEO_NPV10_TEST_AIO_8MB)
constexpr std::size_t kCandidateCapacity = 8u * 1024u * 1024u;
#else
constexpr std::size_t kCandidateCapacity = 2u * 1024u * 1024u;
#endif
#else
constexpr bool kCandidateKernelAio = false;
constexpr std::size_t kCandidateCapacity = 32u * 1024u * 1024u;
#endif

void require(bool condition, const char* reason) {
    if (!condition) throw std::runtime_error(reason);
}

struct OwnedFile {
    char path[96] = "/data/local/tmp/camx-lq001-submission-XXXXXX";
    int fd = ::mkstemp(path);
    ~OwnedFile() {
        if (fd >= 0) { (void)::close(fd); (void)::unlink(path); }
    }
};

struct Result { double meanMs = 0; double maxMs = 0; std::int64_t end = 0; };

template <std::size_t Capacity, bool KernelAio = false>
Result measure(OwnedFile& file, const std::vector<std::uint8_t>& payload, const char* order) {
    using Writer = neoproc::npv10::AlignedRecordWriter<PosixIo, Capacity, false, 1u, KernelAio>;
    require(file.fd >= 0, "Owned temporary-file creation failed");
    require(::posix_fallocate64(file.fd, 0, kReservation) == 0, "Physical reservation failed");
    const std::vector<std::uint8_t> prefix(kPrefixBytes, 0x6bu);
    require(::pwrite64(file.fd, prefix.data(), prefix.size(), 0) ==
        static_cast<ssize_t>(prefix.size()), "Prefix write failed");
    require(::fsync(file.fd) == 0, "Initial fsync failed");
    Result result;
    result.end = kPrefixBytes;
    {
        Writer writer(file.fd, file.path, kPrefixBytes, kReservation);
        require(writer.admission() == Writer::Admission::Active && writer.probeVerified(),
            "Direct-I/O admission or byte-readback probe failed");
        if constexpr (KernelAio) require(writer.kernelAioEnabled(), "Candidate kernel AIO was not admitted");
        std::array<std::uint8_t, 28> header{};
        for (unsigned frame = 0; frame < kFrames; ++frame) {
            header.fill(static_cast<std::uint8_t>(frame));
            const auto started = Clock::now();
            require(writer.writeRecord(header.data(), header.size(), payload.data(), payload.size(),
                result.end, kReservation), "Record write failed");
            const auto ms = std::chrono::duration<double, std::milli>(Clock::now() - started).count();
            result.meanMs += ms;
            result.maxMs = std::max(result.maxMs, ms);
            result.end += static_cast<std::int64_t>(header.size() + payload.size());
            if (frame == 0 || (frame + 1) % 30u == 0) {
                require(::fdatasync(file.fd) == 0, "Intermediate durability failed");
            }
        }
        require(writer.close(), "Direct descriptor close failed");
        result.meanMs /= kFrames;
        std::printf("SUBMISSION_STORAGE_TIMING order=%s capacity_bytes=%zu payload_bytes=%zu "
            "frames=%u mean_ms=%.6f max_ms=%.6f syscalls=%llu physical_bytes=%llu "
            "kernel_aio=%d aio_submitted=%llu aio_completed=%llu\n",
            order, Capacity, payload.size(), kFrames, result.meanMs, result.maxMs,
            static_cast<unsigned long long>(writer.writeCalls()),
            static_cast<unsigned long long>(writer.physicalBytes()), writer.kernelAioEnabled(),
            static_cast<unsigned long long>(writer.kernelAioSubmissions()),
            static_cast<unsigned long long>(writer.kernelAioCompletions()));
        std::fflush(stdout);
    }
    require(::ftruncate64(file.fd, result.end) == 0 && ::fsync(file.fd) == 0,
        "Final truncation/durability failed");
    return result;
}

void verify(const OwnedFile& file, const std::vector<std::uint8_t>& payload, std::int64_t end) {
    std::vector<std::uint8_t> readback(payload.size());
    std::array<std::uint8_t, 28> header{};
    std::vector<std::uint8_t> prefix(kPrefixBytes);
    require(::pread64(file.fd, prefix.data(), prefix.size(), 0) ==
        static_cast<ssize_t>(prefix.size()), "Prefix read failed");
    require(std::all_of(prefix.begin(), prefix.end(), [](auto b) { return b == 0x6bu; }),
        "Prefix bytes changed");
    std::int64_t offset = kPrefixBytes;
    for (unsigned frame = 0; frame < kFrames; ++frame) {
        require(::pread64(file.fd, header.data(), header.size(), offset) ==
            static_cast<ssize_t>(header.size()), "Header read failed");
        require(std::all_of(header.begin(), header.end(), [frame](auto b) {
            return b == static_cast<std::uint8_t>(frame);
        }), "Header/order changed");
        offset += header.size();
        require(::pread64(file.fd, readback.data(), readback.size(), offset) ==
            static_cast<ssize_t>(readback.size()), "Payload read failed");
        require(readback == payload, "Recorded packet bytes changed");
        offset += payload.size();
    }
    struct stat64 status{};
    require(::fstat64(file.fd, &status) == 0 && offset == end && status.st_size == end,
        "Logical tail or final length changed");
}

void compare(std::size_t payloadBytes, bool candidateFirst) {
    std::vector<std::uint8_t> payload(payloadBytes);
    std::uint32_t state = 0x49af7841u;
    for (auto& byte : payload) {
        state ^= state << 13u; state ^= state >> 17u; state ^= state << 5u;
        byte = static_cast<std::uint8_t>(state);
    }
    OwnedFile baseline;
    OwnedFile candidate;
    Result a, b;
    if (candidateFirst) {
        b = measure<kCandidateCapacity, kCandidateKernelAio>(candidate, payload, "candidate-first");
        a = measure<2u * 1024u * 1024u>(baseline, payload, "baseline-second");
    } else {
        a = measure<2u * 1024u * 1024u>(baseline, payload, "baseline-first");
        b = measure<kCandidateCapacity, kCandidateKernelAio>(candidate, payload, "candidate-second");
    }
    verify(baseline, payload, a.end);
    verify(candidate, payload, b.end);
    require(a.end == b.end, "Compared lengths differ");
    std::printf("SUBMISSION_BYTE_EXACT_PASS payload_bytes=%zu candidate_first=%d "
        "baseline_over_candidate=%.6f scope=physical_storage_not_camera_fps\n",
        payloadBytes, candidateFirst, a.meanMs / b.meanMs);
    std::fflush(stdout);
}
}  // namespace

int main() {
    try {
        compare(14u * 1024u * 1024u + 37u, false);
        compare(14u * 1024u * 1024u + 37u, true);
        compare(22u * 1024u * 1024u + 512u * 1024u + 43u, false);
        compare(22u * 1024u * 1024u + 512u * 1024u + 43u, true);
        return 0;
    } catch (const std::exception& failure) {
        std::fprintf(stderr, "SUBMISSION_DEVICE_FAIL %s errno=%d\n", failure.what(), errno);
        return 1;
    }
}
