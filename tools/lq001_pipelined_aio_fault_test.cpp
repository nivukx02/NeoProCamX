// Focused tests of the actual double-buffer/AIO implementation. Not camera/FPS evidence.
#define main camxEarlierKernelFaultTestMain
#include "lq001_kernel_aio_fault_test.cpp"
#undef main
#include "../app/src/main/cpp/npv10_aligned_record_writer.h"

namespace {
struct PipelinedRecordIo {
    static inline long page = 4096;
    static inline int closed = 0;
    static long pageSize() noexcept { return page; }
    static int openDirect(const char*) noexcept { return 19; }
    static int stat(int, struct stat64* out) noexcept {
        out->st_mode = S_IFREG; out->st_dev = 7; out->st_ino = 19;
        out->st_size = static_cast<off64_t>(FakeAio::base + FakeAio::storage.size());
        return 0;
    }
    static ssize_t read(int, void* destination, std::size_t size, std::int64_t offset) noexcept {
        const auto position = static_cast<std::uint64_t>(offset) - FakeAio::base;
        if (offset < 0 || position > FakeAio::storage.size() || size > FakeAio::storage.size() - position) {
            errno = ENOSPC; return -1;
        }
        std::memcpy(destination, FakeAio::storage.data() + position, size);
        return static_cast<ssize_t>(size);
    }
    static ssize_t write(int, const void* bytes, std::size_t size, std::int64_t offset) noexcept {
        const auto position = static_cast<std::uint64_t>(offset) - FakeAio::base;
        if (offset < 0 || position > FakeAio::storage.size() || size > FakeAio::storage.size() - position) {
            errno = ENOSPC; return -1;
        }
        std::memcpy(FakeAio::storage.data() + position, bytes, size);
        return static_cast<ssize_t>(size);
    }
    static int sync(int) noexcept { return 0; }
    static int close(int) noexcept {
        if (!FakeAio::pending.empty()) { errno = EBUSY; return -1; }
        ++closed; return 0;
    }
};
using PipelinedWriter = neoproc::npv10::AlignedRecordWriter<PipelinedRecordIo,
    8u * 1024u * 1024u, true, 1u, true, FakeAio>;
static_assert(PipelinedWriter::kResidentStagingBytes == 16u * 1024u * 1024u);

void sequence(unsigned page, std::uint64_t base, unsigned maxSubmit, unsigned maxEvents, bool reverse) {
    PipelinedRecordIo::page = page; PipelinedRecordIo::closed = 0;
    FakeAio::base = base; FakeAio::maxSubmit = maxSubmit;
    FakeAio::maxEvents = maxEvents; FakeAio::reverse = reverse;
    constexpr std::size_t prefix = 13654;
    std::memcpy(FakeAio::storage.data(), source, prefix);
    std::vector<std::uint8_t> expected(source, source + prefix);
    const auto reservation = static_cast<std::int64_t>(base + FakeAio::storage.size());
    {
        PipelinedWriter writer(18, "owned-pipelined-record", static_cast<std::int64_t>(base + prefix), reservation);
        check(writer.admission() == PipelinedWriter::Admission::Active && writer.kernelAioEnabled(), "admission failed");
        std::array<std::uint8_t, 28> header{};
        unsigned frame = 0;
        for (unsigned size : {1u, 3u, 4095u, 4096u, 4097u, 65535u, 13107311u, 7u, 524291u}) {
            header.fill(static_cast<std::uint8_t>(++frame));
            check(writer.writeRecord(header.data(), header.size(), source, size,
                static_cast<std::int64_t>(base + expected.size()), reservation), "record failed");
            expected.insert(expected.end(), header.begin(), header.end());
            expected.insert(expected.end(), source, source + size);
            check(FakeAio::pending.empty(), "record returned while kernel owns a buffer");
            check(std::memcmp(FakeAio::storage.data(), expected.data(), expected.size()) == 0,
                "prefix, payload, header or shared-tail bytes changed");
            check(writer.kernelAioSubmissions() == writer.kernelAioCompletions(), "completion mismatch");
        }
        check(writer.close(), "clean shutdown failed");
    }
    check(PipelinedRecordIo::closed == 1 && FakeAio::closes == 1, "duplicate or unretired close");
}

template<class Inject> void failure(int expected, Inject&& inject) {
    PipelinedRecordIo::page = 4096; PipelinedRecordIo::closed = 0;
    {
        PipelinedWriter writer(18, "owned-failed-record", 0, static_cast<std::int64_t>(FakeAio::storage.size()));
        check(writer.admission() == PipelinedWriter::Admission::Active, "fault test admission failed");
        inject();
        std::array<std::uint8_t, 28> header{};
        check(!writer.writeRecord(header.data(), header.size(), source, 13u * 1024u * 1024u,
            0, static_cast<std::int64_t>(FakeAio::storage.size())), "fault was hidden");
        check(writer.failureErrno() == expected, "first error was replaced");
        check(FakeAio::pending.empty(), "fault returned with live readers");
        const int writes = FakeAio::writes;
        check(!writer.writeRecord(header.data(), header.size(), source, 4096, 0,
            static_cast<std::int64_t>(FakeAio::storage.size())), "failed writer accepted another frame");
        check(writes == FakeAio::writes, "failed writer kept writing");
        (void)writer.close();
    }
    check(PipelinedRecordIo::closed == 1 && FakeAio::closes == 1, "fault cleanup did not retire exactly once");
}
} // namespace

int main() {
    try {
        void* memory = nullptr;
        check(::posix_memalign(&memory, 16384, kBytes) == 0, "input allocation failed");
        source = static_cast<std::uint8_t*>(memory);
        for (std::size_t i = 0; i < kBytes; ++i) source[i] = static_cast<std::uint8_t>(i * 31u + i / 127u);
        for (unsigned page : {4096u, 16384u})
            for (std::uint64_t base : {0uLL, 5uLL * 1024u * 1024u * 1024u})
                for (unsigned submitted : {1u, 4u})
                    for (unsigned events : {1u, 4u})
                        for (bool reverse : {false, true})
                            run("pipelined_exact_records_and_full_retirement", [&] { sequence(page, base, submitted, events, reverse); });
        run("pipelined_ENOSPC", [] { failure(ENOSPC, [] { FakeAio::submitError = ENOSPC; }); });
        run("pipelined_completion_error", [] { failure(EIO, [] { FakeAio::eventError = EIO; }); });
        run("pipelined_timeout", [] { failure(ETIMEDOUT, [] { FakeAio::timeout = true; }); });
        run("pipelined_short_completion", [] { failure(EIO, [] { FakeAio::shortResult = true; }); });
        run("pipelined_negative_completion", [] { failure(ENOSPC, [] { FakeAio::negativeResult = true; }); });
        run("pipelined_secondary_error", [] { failure(EIO, [] { FakeAio::badSecondary = true; }); });
        run("pipelined_stale_generation", [] { failure(EIO, [] { FakeAio::stale = true; }); });
        run("pipelined_wrong_object", [] { failure(EIO, [] { FakeAio::badObject = true; }); });
        run("pipelined_duplicate_completion", [] { failure(EIO, [] { FakeAio::duplicate = true; }); });
        run("pipelined_zero_submit", [] { failure(EIO, [] { FakeAio::zeroSubmit = true; }); });
        run("pipelined_excess_submit", [] { failure(EIO, [] { FakeAio::tooManySubmitted = true; }); });
        run("pipelined_EINTR", [] {
            FakeAio::interruptedSubmit = FakeAio::interruptedEvents = true; FakeAio::destroyEintr = 2;
            sequence(4096, 0, 1, 1, true);
        });
        std::free(source);
        std::printf("PIPELINED_AIO_FAULT_TEST_PASS cases=%u scope=algorithm_not_camera_fps\n", passed);
        return 0;
    } catch (const std::exception& error) {
        std::free(source);
        std::fprintf(stderr, "PIPELINED_AIO_FAULT_TEST_FAILED cases=%u reason=%s\n", passed, error.what());
        return 1;
    }
}
