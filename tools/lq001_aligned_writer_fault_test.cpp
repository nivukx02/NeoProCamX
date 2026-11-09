// Deterministic syscall-fault tests of the production algorithm, NOT camera/FPS evidence.
#include "../app/src/main/cpp/npv10_aligned_record_writer.h"

#include <array>
#include <cstdio>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
void check(bool value, const char* description) {
    if (!value) throw std::runtime_error(description);
}

struct FaultIo {
    static inline std::mutex writeMutex;
    static inline std::vector<std::uint8_t> bytes;
    static inline std::vector<ssize_t> scriptedWrites;
    static inline std::size_t scriptIndex = 0;
    static inline std::int64_t base = 0, physicalSize = 0;
    static inline long page = 4096;
    static inline int openError = 0, statError = 0, readError = 0, syncError = 0;
    static inline int closeError = 0, opens = 0, closes = 0, writes = 0;
    static inline bool wrongInode = false, nonRegular = false, corruptReadback = false;
    static inline bool zeroRead = false;
    static void reset(long selectedPage = 4096, std::int64_t selectedBase = 0) {
        bytes.resize(16u * 1024u * 1024u);
        for (std::size_t i = 0; i < bytes.size(); ++i) {
            bytes[i] = static_cast<std::uint8_t>((i * 31u + i / 97u) & 255u);
        }
        scriptedWrites.clear(); scriptIndex = 0;
        base = selectedBase; physicalSize = base + static_cast<std::int64_t>(bytes.size());
        page = selectedPage;
        openError = statError = readError = syncError = closeError = opens = closes = writes = 0;
        wrongInode = nonRegular = corruptReadback = zeroRead = false;
    }
    static long pageSize() noexcept { return page; }
    static int openDirect(const char*) noexcept {
        ++opens;
        if (openError != 0) { errno = openError; return -1; }
        return 41;
    }
    static int stat(int fd, struct stat64* result) noexcept {
        if (statError != 0) { errno = statError; return -1; }
        result->st_mode = nonRegular ? S_IFIFO : S_IFREG;
        result->st_dev = 7;
        result->st_ino = wrongInode && fd == 41 ? 3 : 2;
        result->st_size = physicalSize;
        return 0;
    }
    static ssize_t read(int fd, void* target, std::size_t count, std::int64_t offset) noexcept {
        if (readError != 0) { errno = readError; readError = 0; return -1; }
        if (zeroRead) return 0;
        if (offset < base || offset > physicalSize ||
            count > static_cast<std::uint64_t>(physicalSize - offset)) { errno = EIO; return -1; }
        std::memcpy(target, bytes.data() + static_cast<std::size_t>(offset - base), count);
        if (corruptReadback && fd == 41 && count > 0) static_cast<std::uint8_t*>(target)[0] ^= 1u;
        return static_cast<ssize_t>(count);
    }
    static ssize_t write(int fd, const void* source, std::size_t count, std::int64_t offset) noexcept {
        // The fault script itself is ordered and race-free even when two disjoint production
        // chunks reach the syscall seam concurrently. This is not a lock in production I/O.
        std::lock_guard<std::mutex> lock(writeMutex);
        ++writes;
        const auto alignment = static_cast<std::size_t>(std::max(4096L, page));
        if (fd != 41 || reinterpret_cast<std::uintptr_t>(source) % alignment != 0u ||
            count % alignment != 0u || offset % static_cast<std::int64_t>(alignment) != 0) {
            errno = EINVAL; return -1;
        }
        const auto result = scriptIndex < scriptedWrites.size()
            ? scriptedWrites[scriptIndex++] : static_cast<ssize_t>(count);
        if (result < 0) { errno = static_cast<int>(-result); return -1; }
        const auto copied = std::min(count, static_cast<std::size_t>(result));
        if (offset < base || offset > physicalSize ||
            copied > static_cast<std::uint64_t>(physicalSize - offset)) { errno = ENOSPC; return -1; }
        std::memcpy(bytes.data() + static_cast<std::size_t>(offset - base), source, copied);
        return result;
    }
    static int sync(int) noexcept {
        if (syncError != 0) { errno = syncError; syncError = 0; return -1; }
        return 0;
    }
    static int close(int) noexcept {
        ++closes;
        if (closeError != 0) { errno = closeError; return -1; }
        return 0;
    }
};

#if defined(NEO_NPV10_TEST_CONCURRENT_WRITER)
using Writer = neoproc::npv10::AlignedRecordWriter<FaultIo, 2u * 1024u * 1024u, true, 2u>;
#elif defined(NEO_NPV10_TEST_PIPELINED_WRITER)
using Writer = neoproc::npv10::AlignedRecordWriter<FaultIo, 2u * 1024u * 1024u, true>;
#else
using Writer = neoproc::npv10::AlignedRecordWriter<FaultIo>;
#endif
constexpr std::int64_t kPrefix = 13654;
std::array<std::uint8_t, 28> header{};
std::vector<std::uint8_t> packet(3u * 1024u * 1024u + 55u, 0x9au);
unsigned passed = 0;

template <class Test> void run(const char* name, Test&& test) {
    FaultIo::reset();
    test();
    check(FaultIo::closes == (FaultIo::opens != 0 && FaultIo::openError == 0 ? 1 : 0),
        "Descriptor not released exactly once");
    ++passed;
    std::printf("PASS %s\n", name);
}

bool record(Writer& writer, std::int64_t offset = kPrefix,
            std::int64_t limit = 16LL * 1024LL * 1024LL) {
    return writer.writeRecord(header.data(), header.size(), packet.data(), packet.size(), offset, limit);
}

void assertFaultIsTerminal(int expectedErrno, std::vector<ssize_t> faults) {
    Writer writer(40, "owned", kPrefix, FaultIo::physicalSize);
    check(writer.admission() == Writer::Admission::Active, "Admission must succeed first");
    FaultIo::scriptedWrites = std::move(faults);
    check(!record(writer), "Write failure was hidden");
    const int observedCalls = FaultIo::writes;
    check(!record(writer), "Failed writer reused");
    check(FaultIo::writes == observedCalls, "Failed writer performed more I/O");
    check(writer.failureErrno() == expectedErrno && writer.failures() == 1, "Wrong terminal error");
}

void byteExactSequence(long page, std::int64_t base) {
    FaultIo::reset(page, base);
    Writer writer(40, "owned", base + kPrefix, FaultIo::physicalSize);
    check(writer.admission() == Writer::Admission::Active && writer.probeVerified(), "Probe failed");
    std::vector<std::uint8_t> expected(FaultIo::bytes.begin(), FaultIo::bytes.begin() + kPrefix);
    for (const std::size_t size : {1u, 3u, 4095u, 4096u, 4097u, 65535u, 2097124u, 2097207u, 7u}) {
        const auto offset = base + static_cast<std::int64_t>(expected.size());
        check(writer.writeRecord(header.data(), header.size(), packet.data(), size,
                offset, FaultIo::physicalSize), "Sequential write failed");
        expected.insert(expected.end(), header.begin(), header.end());
        expected.insert(expected.end(), packet.begin(), packet.begin() + size);
        check(std::equal(expected.begin(), expected.end(), FaultIo::bytes.begin()),
            "Original prefix or accepted record was changed");
        std::int64_t rounded = 0;
        check(writer.alignedEnd(base + static_cast<std::int64_t>(expected.size()), &rounded), "Alignment failed");
        for (auto i = expected.size(); i < static_cast<std::size_t>(rounded - base); ++i) {
            check(FaultIo::bytes[i] == 0, "Tail padding not deterministic");
        }
        check(writer.failures() == 0, "Successful sequence reports an error");
    }
    check(writer.writeCalls() > 0 && writer.physicalBytes() >= expected.size() - kPrefix,
        "Physical-byte telemetry incomplete");
    check(writer.paddingBytes() > 0, "Padding telemetry missing");
}
}  // namespace

int main() {
    try {
        run("4KiB_variable_records_and_original_prefix", [] { byteExactSequence(4096, 0); });
        run("16KiB_variable_records_and_original_prefix", [] { byteExactSequence(16384, 0); });
        run("offsets_beyond_32bit_range", [] { byteExactSequence(4096, 3LL * 1024 * 1024 * 1024); });
        run("unsupported_open_permits_pre_capture_fallback", [] {
            FaultIo::openError = EOPNOTSUPP;
            Writer writer(40, "owned", kPrefix, FaultIo::physicalSize);
            check(writer.admission() == Writer::Admission::Unsupported && FaultIo::writes == 0,
                "Unsupported open incorrectly admitted");
        });
        run("unsupported_probe_permits_pre_capture_fallback", [] {
            FaultIo::scriptedWrites = {-EINVAL};
            Writer writer(40, "owned", kPrefix, FaultIo::physicalSize);
            check(writer.admission() == Writer::Admission::Unsupported && writer.admissionErrno() == EINVAL,
                "Unsupported alignment not distinguished");
        });
        for (const int openFailure : {EACCES, ELOOP, EIO, EMFILE}) {
            const auto name = std::string("fatal_open_") + std::to_string(openFailure);
            run(name.c_str(), [openFailure] {
                FaultIo::openError = openFailure;
                Writer writer(40, "owned", kPrefix, FaultIo::physicalSize);
                check(writer.admission() == Writer::Admission::Fatal, "Fatal open weakened to fallback");
            });
        }
        run("wrong_inode_rejected_before_mutation", [] {
            FaultIo::wrongInode = true;
            Writer writer(40, "owned", kPrefix, FaultIo::physicalSize);
            check(writer.admission() == Writer::Admission::Fatal && FaultIo::writes == 0,
                "Wrong inode mutated");
        });
        run("nonregular_file_rejected", [] {
            FaultIo::nonRegular = true;
            Writer writer(40, "owned", kPrefix, FaultIo::physicalSize);
            check(writer.admission() == Writer::Admission::Fatal && FaultIo::writes == 0, "Nonregular file admitted");
        });
        run("probe_readback_corruption_is_fatal", [] {
            FaultIo::corruptReadback = true;
            Writer writer(40, "owned", kPrefix, FaultIo::physicalSize);
            check(writer.admission() == Writer::Admission::Fatal, "Corrupted probe admitted");
        });
        run("partial_probe_is_fatal", [] {
            FaultIo::scriptedWrites = {512};
            Writer writer(40, "owned", kPrefix, FaultIo::physicalSize);
            check(writer.admission() == Writer::Admission::Fatal, "Partial probe fell back");
        });
        run("failed_prefix_sync_is_fatal", [] {
            FaultIo::syncError = EIO;
            Writer writer(40, "owned", kPrefix, FaultIo::physicalSize);
            check(writer.admission() == Writer::Admission::Fatal && FaultIo::writes == 0, "Sync failure hidden");
        });
        run("zero_progress_read_is_fatal", [] {
            FaultIo::zeroRead = true;
            Writer writer(40, "owned", kPrefix, FaultIo::physicalSize);
            check(writer.admission() == Writer::Admission::Fatal, "Empty probe read admitted");
        });
        run("interrupted_read_and_sync_retry", [] {
            FaultIo::readError = EINTR; FaultIo::syncError = EINTR;
            Writer writer(40, "owned", kPrefix, FaultIo::physicalSize);
            check(writer.admission() == Writer::Admission::Active && record(writer), "EINTR did not retry");
        });
        run("aligned_partial_write_and_EINTR_complete", [] {
            Writer writer(40, "owned", kPrefix, FaultIo::physicalSize);
            FaultIo::scriptedWrites = {-EINTR, 4096};
            check(record(writer), "Valid partial aligned write not completed");
            check(std::equal(packet.begin(), packet.end(), FaultIo::bytes.begin() + kPrefix + header.size()),
                "Partial-write payload corrupt");
        });
        run("runtime_EIO_is_terminal", [] { assertFaultIsTerminal(EIO, {-EIO}); });
        run("runtime_unsupported_never_falls_back", [] { assertFaultIsTerminal(EINVAL, {-EINVAL}); });
        run("zero_progress_write_is_terminal", [] { assertFaultIsTerminal(EIO, {0}); });
        run("partial_then_failed_write_is_terminal", [] { assertFaultIsTerminal(EIO, {4096, -EIO}); });
        run("unaligned_short_write_is_terminal", [] { assertFaultIsTerminal(EIO, {17}); });
        run("oversized_syscall_result_is_terminal", [] {
            assertFaultIsTerminal(EIO, {static_cast<ssize_t>(Writer::kCapacity + 4096)});
        });
        run("reservation_must_cover_padding", [] {
            Writer writer(40, "owned", kPrefix, FaultIo::physicalSize);
            const auto before = FaultIo::writes;
            check(!record(writer, kPrefix, kPrefix + static_cast<std::int64_t>(header.size() + packet.size())),
                "Unreserved padding written");
            check(FaultIo::writes == before && writer.failureErrno() == ENOSPC, "Reservation guard mutated file");
        });
        run("external_truncation_rejected", [] {
            Writer writer(40, "owned", kPrefix, FaultIo::physicalSize);
            FaultIo::physicalSize = kPrefix;
            check(!record(writer) && writer.failureErrno() == ENOSPC, "External shrink not detected");
        });
        run("out_of_order_record_is_terminal", [] {
            Writer writer(40, "owned", kPrefix, FaultIo::physicalSize);
            check(!record(writer, kPrefix + 1) && writer.failureErrno() == EINVAL, "Offset drift accepted");
        });
        run("rounding_overflow_rejected", [] {
            Writer writer(40, "owned", kPrefix, FaultIo::physicalSize);
            std::int64_t result = 0;
            check(!writer.alignedEnd(INT64_MAX, &result), "64-bit alignment overflow accepted");
        });
        run("close_is_exact_once_even_on_EINTR", [] {
            Writer writer(40, "owned", kPrefix, FaultIo::physicalSize);
            FaultIo::closeError = EINTR;
            check(!writer.close() && writer.close(), "Close retry ownership broken");
            check(!record(writer), "Closed writer used");
        });
        std::printf("{\"scope\":\"production_native_algorithm_fault_tests_not_camera\",\"passed\":%u,\"failed\":0}\n", passed);
        return 0;
    } catch (const std::exception& error) {
        std::fprintf(stderr, "ALIGNED_FAULT_TEST_FAILED after %u: %s (errno=%d)\n", passed, error.what(), errno);
        return 1;
    }
}
