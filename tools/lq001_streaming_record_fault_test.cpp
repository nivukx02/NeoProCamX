// Deterministic faults against the exact new native algorithm, never camera/FPS evidence.
#define main camxExistingAioFaultTestMain
#define Lane LegacyLane
#include "lq001_kernel_aio_fault_test.cpp"
#undef Lane
#undef main
#include "../app/src/main/cpp/npv10_aligned_record_writer.h"

namespace {
#if defined(NEO_NPV10_TEST_EIGHT_STREAM_REQUESTS)
struct StreamAio : FakeAio {
    static long setup(unsigned depth, aio_context_t* result) noexcept {
        if (depth != 8u) { errno = EINVAL; return -1; }
        return FakeAio::setup(4u, result);
    }
};
using Lane = neoproc::npv10::BoundedKernelAio<StreamAio, 8u * 1024u * 1024u, 8u>;
#else
using StreamAio = FakeAio;
using Lane = LegacyLane;
#endif
std::uint8_t* staging = nullptr;
constexpr std::size_t kStagingBytes = 8u * 1024u * 1024u;

bool stream(Lane& lane, std::size_t size = kBytes, std::int64_t offset = 0,
            std::size_t alignment = 4096u, bool failRefill = false) {
    return lane.writeStream(19, staging, kStagingBytes, size, offset, alignment,
        [&](std::uint8_t* target, std::size_t position, std::size_t count) noexcept {
            if (failRefill && position >= kStagingBytes) return false;
            if (position > kBytes || count > kBytes - position) return false;
            std::memcpy(target, source + position, count);
            return true;
        });
}

struct RecordIo {
    static inline long page = 4096;
    static inline int closed = 0;
    static long pageSize() noexcept { return page; }
    static int openDirect(const char*) noexcept { return 19; }
    static int stat(int, struct stat64* output) noexcept {
        output->st_mode = S_IFREG; output->st_dev = 7; output->st_ino = 19;
        output->st_size = static_cast<off64_t>(FakeAio::base + FakeAio::storage.size());
        return 0;
    }
    static ssize_t read(int, void* destination, std::size_t size, std::int64_t offset) noexcept {
        if (offset < 0 || static_cast<std::uint64_t>(offset) < FakeAio::base) {
            errno = EINVAL; return -1;
        }
        const auto start = static_cast<std::uint64_t>(offset) - FakeAio::base;
        if (start > FakeAio::storage.size() || size > FakeAio::storage.size() - start) {
            errno = ENOSPC; return -1;
        }
        std::memcpy(destination, FakeAio::storage.data() + start, size);
        return static_cast<ssize_t>(size);
    }
    static ssize_t write(int, const void* bytes, std::size_t size, std::int64_t offset) noexcept {
        if (offset < 0 || static_cast<std::uint64_t>(offset) < FakeAio::base) {
            errno = EINVAL; return -1;
        }
        const auto start = static_cast<std::uint64_t>(offset) - FakeAio::base;
        if (start > FakeAio::storage.size() || size > FakeAio::storage.size() - start) {
            errno = ENOSPC; return -1;
        }
        std::memcpy(FakeAio::storage.data() + start, bytes, size);
        return static_cast<ssize_t>(size);
    }
    static int sync(int) noexcept { return 0; }
    static int close(int) noexcept { ++closed; return 0; }
};
using StreamingWriter = neoproc::npv10::AlignedRecordWriter<RecordIo, kStagingBytes,
    false, 1u, true, StreamAio, kStagingBytes, true, Lane::kDepth>;

void recordSequence(unsigned page, std::uint64_t base, bool reverse, unsigned maxEvents) {
    RecordIo::page = page; RecordIo::closed = 0;
    FakeAio::base = base; FakeAio::reverse = reverse; FakeAio::maxEvents = maxEvents;
    constexpr std::size_t prefix = 13654;
    for (std::size_t i = 0; i < prefix; ++i) FakeAio::storage[i] = source[i];
    std::vector<std::uint8_t> expected(source, source + prefix);
    const auto reservation = static_cast<std::int64_t>(base + FakeAio::storage.size());
    {
        StreamingWriter writer(18, "exact-owned-record", static_cast<std::int64_t>(base + prefix),
            reservation);
        check(writer.admission() == StreamingWriter::Admission::Active && writer.kernelAioEnabled(),
            "record stream was not admitted");
        std::array<std::uint8_t, 28> header{};
        unsigned frame = 0;
        for (unsigned size : {1u, 3u, 4095u, 4096u, 4097u, 65535u, 13107311u, 7u, 524291u}) {
            header.fill(static_cast<std::uint8_t>(++frame));
            check(writer.writeRecord(header.data(), header.size(), source, size,
                static_cast<std::int64_t>(base + expected.size()), reservation), "record stream failed");
            expected.insert(expected.end(), header.begin(), header.end());
            expected.insert(expected.end(), source, source + size);
            check(std::equal(expected.begin(), expected.end(), FakeAio::storage.begin()),
                "stream changed original prefix, packet bytes, or prior tail");
            const auto alignment = static_cast<std::size_t>(page);
            const auto rounded = (expected.size() + alignment - 1u) / alignment * alignment;
            for (auto index = expected.size(); index < rounded; ++index)
                check(FakeAio::storage[index] == 0, "record padding is not zero");
            check(FakeAio::pending.empty(), "record succeeded before every read of its bytes completed");
        }
        check(writer.kernelAioSubmissions() == writer.kernelAioCompletions(), "completion imbalance");
        check(writer.close(), "record stream close failed");
    }
    check(RecordIo::closed == 1, "record descriptor was not closed exactly once");
}

void streamFailure(int expected) {
    Lane lane;
    check(lane.open() == Lane::Admission::Active, "open failed");
    check(!stream(lane), "stream failure was hidden");
    check(lane.failureErrno() == expected, "first stream error changed");
    check(lane.buffersRetired(), "failed stream left kernel readers alive");
    const auto writes = FakeAio::writes;
    check(!stream(lane) && FakeAio::writes == writes, "terminal stream performed another write");
    check(lane.close() && FakeAio::closes == 1, "failed stream did not retire exactly once");
}
} // namespace

int main() {
    try {
        void* sourceAllocation = nullptr;
        void* stagingAllocation = nullptr;
        check(::posix_memalign(&sourceAllocation, 16384u, kBytes) == 0, "source allocation");
        source = static_cast<std::uint8_t*>(sourceAllocation);
        check(::posix_memalign(&stagingAllocation, 16384u, kStagingBytes) == 0, "staging allocation");
        staging = static_cast<std::uint8_t*>(stagingAllocation);
        for (std::size_t i = 0; i < kBytes; ++i) source[i] = static_cast<std::uint8_t>(i * 31u + i / 127u);
        std::vector<unsigned> submissions{1u, 2u, 3u, 4u};
        std::vector<unsigned> completions{1u, 2u, 4u};
        if constexpr (Lane::kDepth == 8u) { submissions.push_back(8u); completions.push_back(8u); }
        for (unsigned alignment : {4096u, 16384u})
            for (unsigned maxSubmit : submissions)
                for (unsigned maxEvents : completions)
                    for (bool reverse : {false, true})
                        run("stream_partial_submit_reordered_completion_exact_bytes", [&] {
                            FakeAio::maxSubmit = maxSubmit; FakeAio::maxEvents = maxEvents;
                            FakeAio::reverse = reverse; FakeAio::base = 5uLL * 1024u * 1024u * 1024u;
                            Lane lane; check(lane.open() == Lane::Admission::Active, "open");
                            check(stream(lane, kBytes, static_cast<std::int64_t>(FakeAio::base), alignment),
                                "stream rejected exact input");
                            check(std::memcmp(source, FakeAio::storage.data(), kBytes) == 0, "byte changed");
                            check(lane.submissions() == lane.completions(), "unfinished completion");
                        });
        run("stream_refills_idle_slots_before_slow_slots_finish", [] {
            FakeAio::maxEvents = 1; FakeAio::reverse = true;
            Lane lane; check(lane.open() == Lane::Admission::Active, "open");
            bool replenishedBeforeBarrier = false;
            check(lane.writeStream(19, staging, kStagingBytes, kBytes, 0, 4096,
                [&](std::uint8_t* target, std::size_t position, std::size_t count) noexcept {
                    if (position >= kStagingBytes && FakeAio::pending.size() == Lane::kDepth - 1u)
                        replenishedBeforeBarrier = true;
                    std::memcpy(target, source + position, count); return true;
                }), "refill stream failed");
            check(replenishedBeforeBarrier, "old whole-group completion barrier remains");
        });
        run("stream_generator_failure_retires_earlier_kernel_readers", [] {
            FakeAio::maxEvents = 1;
            Lane lane; check(lane.open() == Lane::Admission::Active, "open");
            check(!stream(lane, kBytes, 0, 4096, true), "generator failure hidden");
            check(lane.failureErrno() == EIO && lane.buffersRetired(), "generator failure ownership leak");
        });
        run("stream_EINTR_keeps_exact_ownership", [] {
            FakeAio::interruptedSubmit = FakeAio::interruptedEvents = true; FakeAio::destroyEintr = 2;
            Lane lane; check(lane.open() == Lane::Admission::Active, "open");
            check(stream(lane) && lane.close() && FakeAio::closes == 1, "interrupted stream failed");
        });
        run("stream_zero_submit_terminal", [] { FakeAio::zeroSubmit = true; streamFailure(EIO); });
        run("stream_excess_submit_terminal", [] { FakeAio::tooManySubmitted = true; streamFailure(EIO); });
        run("stream_storage_error_terminal", [] { FakeAio::submitError = ENOSPC; streamFailure(ENOSPC); });
        run("stream_events_error_terminal", [] { FakeAio::eventError = EIO; streamFailure(EIO); });
        run("stream_timeout_terminal", [] { FakeAio::timeout = true; streamFailure(ETIMEDOUT); });
        run("stream_short_completion_terminal", [] { FakeAio::shortResult = true; streamFailure(EIO); });
        run("stream_negative_completion_terminal", [] { FakeAio::negativeResult = true; streamFailure(ENOSPC); });
        run("stream_secondary_error_terminal", [] { FakeAio::badSecondary = true; streamFailure(EIO); });
        run("stream_wrong_object_terminal", [] { FakeAio::badObject = true; streamFailure(EIO); });
        run("stream_duplicate_completion_terminal", [] { FakeAio::duplicate = true; streamFailure(EIO); });
        run("stream_stale_generation_terminal", [] { FakeAio::stale = true; streamFailure(EIO); });
        run("stream_foreign_slot_terminal", [] { FakeAio::wrongSlot = true; streamFailure(EIO); });
        run("stream_invalid_staging_rejected_before_IO", [] {
            Lane lane; check(lane.open() == Lane::Admission::Active, "open");
            check(!lane.writeStream(19, staging + 1, kStagingBytes, 4096, 0, 4096,
                [](std::uint8_t*, std::size_t, std::size_t) noexcept { return true; }),
                "unaligned staging accepted");
            check(FakeAio::writes == 0 && lane.buffersRetired(), "invalid staging touched file");
        });
        for (unsigned page : {4096u, 16384u})
            for (std::uint64_t base : {0uLL, 5uLL * 1024u * 1024u * 1024u})
                for (bool reverse : {false, true})
                    for (unsigned maxEvents : {1u, 4u})
                        run("record_prefix_tail_padding_64bit_offsets_and_refills", [&] {
                            recordSequence(page, base, reverse, maxEvents);
                        });
        std::free(source); std::free(staging);
        std::printf("STREAMING_RECORD_FAULT_TEST_PASS cases=%u depth=%u scope=algorithm_not_camera_fps\n",
            passed, Lane::kDepth);
        return 0;
    } catch (const std::exception& failure) {
        std::free(source); std::free(staging);
        std::fprintf(stderr, "STREAMING_RECORD_FAULT_TEST_FAILED cases=%u reason=%s\n", passed, failure.what());
        return 1;
    }
}
