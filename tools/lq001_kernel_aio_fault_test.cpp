// Native tests of the production completion algorithm. Not physical-camera/FPS evidence.
#include "../app/src/main/cpp/npv10_kernel_aio.h"
#include <cstring>
#include <cstdio>
#include <cstdlib>
#include <stdexcept>
#include <vector>

namespace {
using neoproc::npv10::BoundedKernelAio;
#if defined(NEO_NPV10_TEST_WIDE_AIO_WINDOW)
constexpr std::size_t kTestGroupBytes = 32u * 1024u * 1024u;
constexpr std::size_t kTestSourceBytes = 34u * 1024u * 1024u;
#else
constexpr std::size_t kTestGroupBytes = 8u * 1024u * 1024u;
constexpr std::size_t kTestSourceBytes = 18u * 1024u * 1024u;
#endif
void check(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

struct FakeAio {
    static inline int capability = 0, setupError = 0, submitError = 0, eventError = 0;
    static inline int destroyEintr = 0, closes = 0, opens = 0, writes = 0;
    static inline unsigned maxSubmit = 4, maxEvents = 4;
    static inline unsigned maximumPending = 0, queueCapacity = 4, submitCalls = 0;
    static inline unsigned failSubmitCall = 0, backpressureEvents = 0;
    static inline unsigned completionWaits = 0, maximumWaitMinimum = 0;
    static inline bool earlyCompletionPerWake = false, waitsForUnsubmitted = false;
    static inline bool zeroSubmit = false, tooManySubmitted = false, timeout = false;
    static inline bool shortResult = false, negativeResult = false, badSecondary = false;
    static inline bool badObject = false, duplicate = false, stale = false, wrongSlot = false;
    static inline bool reverse = false, interruptedSubmit = false, interruptedEvents = false;
    static inline std::vector<iocb*> pending;
    static inline std::vector<std::uint8_t> storage;
    static inline std::uint64_t base = 0;
    static void reset() {
        capability = setupError = submitError = eventError = destroyEintr = closes = opens = writes = 0;
        maxSubmit = maxEvents = 4;
        maximumPending = submitCalls = failSubmitCall = backpressureEvents = 0;
        completionWaits = maximumWaitMinimum = 0;
        earlyCompletionPerWake = waitsForUnsubmitted = false;
        queueCapacity = 4;
        zeroSubmit = tooManySubmitted = timeout = shortResult = negativeResult = badSecondary = false;
        badObject = duplicate = stale = wrongSlot = reverse = interruptedSubmit = interruptedEvents = false;
        pending.clear(); storage.assign(kTestSourceBytes + 2u * 1024u * 1024u, 0u); base = 0;
    }
    static int sandboxCapability() noexcept { return capability; }
    static long setup(unsigned depth, aio_context_t* result) noexcept {
        ++opens;
        if (setupError != 0) { errno = setupError; return -1; }
        if (depth != 4 || result == nullptr || *result != 0) { errno = EINVAL; return -1; }
        *result = 91; return 0;
    }
    static long submit(aio_context_t handle, unsigned count, iocb** requests) noexcept {
        if (handle != 91) { errno = EINVAL; return -1; }
        ++submitCalls;
        if (interruptedSubmit) { interruptedSubmit = false; errno = EINTR; return -1; }
        if (failSubmitCall != 0 && submitCalls == failSubmitCall) { errno = ENOSPC; return -1; }
        if (submitError != 0) { errno = submitError; return -1; }
        if (zeroSubmit) return 0;
        if (tooManySubmitted) return count + 1;
        if (pending.size() >= queueCapacity) { ++backpressureEvents; errno = EAGAIN; return -1; }
        const unsigned selected = std::min({count, maxSubmit,
            queueCapacity - static_cast<unsigned>(pending.size())});
        for (unsigned i = 0; i < selected; ++i) pending.push_back(requests[i]);
        maximumPending = std::max(maximumPending, static_cast<unsigned>(pending.size()));
        return selected;
    }
    static long events(aio_context_t handle, unsigned count, io_event* output,
                       unsigned minimum = 1u) noexcept {
        if (handle != 91) { errno = EINVAL; return -1; }
        ++completionWaits;
        maximumWaitMinimum = std::max(maximumWaitMinimum, minimum);
        if (minimum == 0u || minimum > count || minimum > pending.size()) {
            waitsForUnsubmitted = true; errno = EINVAL; return -1;
        }
        if (interruptedEvents) { interruptedEvents = false; errno = EINTR; return -1; }
        if (eventError != 0) { errno = eventError; return -1; }
        if (timeout) return 0;
        const unsigned selected = std::min({count, maxEvents,
            earlyCompletionPerWake ? minimum : static_cast<unsigned>(pending.size()),
            static_cast<unsigned>(pending.size())});
        io_event previous{};
        for (unsigned i = 0; i < selected; ++i) {
            const auto where = reverse ? pending.size() - 1u : 0u;
            const auto* request = pending[where];
            pending.erase(pending.begin() + static_cast<std::ptrdiff_t>(where));
            const auto relative = static_cast<std::uint64_t>(request->aio_offset) - base;
            if (relative <= storage.size() && request->aio_nbytes <= storage.size() - relative)
                std::memcpy(storage.data() + relative,
                    reinterpret_cast<const void*>(static_cast<std::uintptr_t>(request->aio_buf)),
                    static_cast<std::size_t>(request->aio_nbytes));
            else { errno = ENOSPC; return -1; }
            ++writes;
            auto& event = output[i];
            event.data = request->aio_data;
            event.obj = reinterpret_cast<std::uintptr_t>(request);
            event.res = static_cast<std::int64_t>(request->aio_nbytes);
            event.res2 = 0;
            if (shortResult) --event.res;
            if (negativeResult) event.res = -ENOSPC;
            if (badSecondary) event.res2 = EIO;
            if (badObject) ++event.obj;
            if (stale) event.data -= 8u;
            if (wrongSlot) event.data = (event.data & ~7uLL) | 7u;
            if (duplicate && i > 0) event = previous;
            previous = event;
        }
        return selected;
    }
    static long destroy(aio_context_t handle) noexcept {
        if (handle != 91) { errno = EINVAL; return -1; }
        if (destroyEintr > 0) { --destroyEintr; errno = EINTR; return -1; }
        ++closes;
        // Kernel io_destroy retires every reader synchronously, including failed/timed-out groups.
        pending.clear(); return 0;
    }
};

using Lane = BoundedKernelAio<FakeAio, kTestGroupBytes>;
using CoalescedLane = BoundedKernelAio<FakeAio, kTestGroupBytes, 4u, true>;
constexpr std::size_t kBytes = kTestSourceBytes;
std::uint8_t* source = nullptr;
unsigned passed = 0;

template<class Test> void run(const char* name, Test&& test) {
    FakeAio::reset();
    test();
    check(FakeAio::pending.empty(), "kernel still holds an input request");
    ++passed;
    std::printf("PASS %s\n", name);
}

void failedWrite(int expected) {
    Lane lane;
    check(lane.open() == Lane::Admission::Active, "expected active admission");
    check(!lane.writeAll(19, source, 2u * 1024u * 1024u, 0, 4096u), "failure was hidden");
    check(lane.failureErrno() == expected, "wrong first error");
    check(lane.buffersRetired(), "failed operation retained input readers");
    const int writes = FakeAio::writes;
    check(!lane.writeAll(19, source, 4096, 0, 4096), "failed lane accepted another record");
    check(writes == FakeAio::writes, "failed lane performed additional writes");
    check(lane.close(), "retired close failed");
    check(FakeAio::closes == 1, "context not destroyed exactly once");
}
} // namespace

int main() {
    try {
        void* allocation = nullptr;
        check(::posix_memalign(&allocation, 16384u, kBytes) == 0, "allocation failed");
        source = static_cast<std::uint8_t*>(allocation);
        for (std::size_t i = 0; i < kBytes; ++i) source[i] = static_cast<std::uint8_t>(i * 31u + i / 127u);
        run("normal_recording_preserves_measured_min_one_completion_behavior", [] {
            FakeAio::earlyCompletionPerWake = true;
            BoundedKernelAio<FakeAio, kTestGroupBytes> lane;
            check(lane.open() == decltype(lane)::Admission::Active, "normal lane open failed");
            check(lane.writeAll(19, source, kTestGroupBytes, 0, 4096), "normal group failed");
            check(FakeAio::maximumWaitMinimum == 1 && FakeAio::completionWaits == 4,
                "unproven completion batching activated in the normal recorder");
            check(lane.submissions() == lane.completions() && lane.buffersRetired(),
                "normal completion ownership changed");
        });
        run("submitted_group_waits_once_for_all_required_completions", [] {
            FakeAio::earlyCompletionPerWake = true;
            CoalescedLane lane; check(lane.open() == CoalescedLane::Admission::Active, "open failed");
            check(lane.writeAll(19, source, kTestGroupBytes, 0, 4096), "group write failed");
            check(FakeAio::maximumWaitMinimum == 4 && FakeAio::completionWaits == 1,
                "fully submitted group woke for individual completions with no work to refill");
            check(lane.submissions() == 4 && lane.completions() == 4 && lane.buffersRetired(),
                "batched completion lost an accepted reader");
            check(std::memcmp(source, FakeAio::storage.data(), kTestGroupBytes) == 0,
                "batched wait changed bytes");
        });
        run("short_group_waits_only_for_requests_actually_submitted", [] {
            FakeAio::earlyCompletionPerWake = true;
            CoalescedLane lane; check(lane.open() == CoalescedLane::Admission::Active, "open failed");
            check(lane.writeAll(19, source, 12288u, 0, 4096), "short group failed");
            check(FakeAio::maximumWaitMinimum == 3 && FakeAio::completionWaits == 1 &&
                !FakeAio::waitsForUnsubmitted, "tail waited for nonexistent fourth request");
            check(lane.submissions() == 3 && lane.completions() == 3, "short batch mismatch");
        });
        run("interrupted_group_wait_preserves_the_full_completion_minimum", [] {
            FakeAio::earlyCompletionPerWake = FakeAio::interruptedEvents = true;
            CoalescedLane lane; check(lane.open() == CoalescedLane::Admission::Active, "open failed");
            check(lane.writeAll(19, source, kTestGroupBytes, 0, 4096), "interrupted group failed");
            check(FakeAio::completionWaits == 2 && FakeAio::maximumWaitMinimum == 4 &&
                !FakeAio::waitsForUnsubmitted, "interruption fragmented the completion wait");
            check(lane.submissions() == lane.completions() && lane.buffersRetired(),
                "interruption lost reader ownership");
        });
        run("partial_submission_keeps_four_disjoint_requests_in_flight", [] {
            FakeAio::maxSubmit = 1;
            Lane lane; check(lane.open() == Lane::Admission::Active, "open failed");
            check(lane.writeAll(19, source, kTestGroupBytes, 0, 4096), "partial write failed");
            check(FakeAio::maximumPending == 4,
                "partial io_submit serialized independent writes before filling the four-request window");
            check(lane.submissions() == 4 && lane.completions() == 4, "partial batch counts differ");
            check(std::memcmp(source, FakeAio::storage.data(), kTestGroupBytes) == 0,
                "partial batch changed pixels");
        });
        for (unsigned capacity : {1u, 2u, 3u}) {
            for (bool reverse : {false, true}) {
                run("partial_submission_backpressure_reaps_before_retry_without_serializing_or_spinning", [&] {
                    FakeAio::queueCapacity = capacity;
                    FakeAio::maxEvents = 1;
                    FakeAio::reverse = reverse;
                    Lane lane; check(lane.open() == Lane::Admission::Active, "open failed");
                    check(lane.writeAll(19, source, kTestGroupBytes, 0, 16384),
                        "recoverable queue pressure failed");
                    check(FakeAio::maximumPending == capacity && FakeAio::pending.empty(),
                        "request capacity or retirement changed");
                    check(FakeAio::backpressureEvents > 0 && FakeAio::submitCalls <= 7,
                        "queue pressure was not bounded by completed-request progress");
                    check(!FakeAio::waitsForUnsubmitted,
                        "queue pressure waited for a request that was not accepted");
                    check(lane.submissions() == 4 && lane.completions() == 4, "lost or duplicate request");
                    check(std::memcmp(source, FakeAio::storage.data(), kTestGroupBytes) == 0,
                        "pressure recovery changed bytes");
                });
            }
        }
        run("failure_after_partial_acceptance_retires_all_accepted_readers", [] {
            FakeAio::maxSubmit = 1;
            FakeAio::failSubmitCall = 2;
            failedWrite(ENOSPC);
            check(FakeAio::submitCalls == 2 && FakeAio::writes == 0,
                "terminal submission failure must not schedule more work");
        });
        run("EAGAIN_without_inflight_work_is_terminal_not_a_busy_loop", [] {
            FakeAio::submitError = EAGAIN;
            failedWrite(EAGAIN);
            check(FakeAio::submitCalls == 1, "EAGAIN spun without possible completion progress");
        });
        for (unsigned alignment : {4096u, 16384u}) {
            for (unsigned maxSubmit : {1u, 2u, 3u, 4u}) {
                for (unsigned maxEvents : {1u, 2u, 4u}) {
                    for (bool reverse : {false, true}) {
                        run("exact_variable_groups_partial_submission_reordered_completion", [&] {
                            FakeAio::maxSubmit = maxSubmit; FakeAio::maxEvents = maxEvents;
                            FakeAio::reverse = reverse; FakeAio::base = 5uLL * 1024u * 1024u * 1024u;
                            Lane lane; check(lane.open() == Lane::Admission::Active, "open failed");
                            check(lane.writeAll(19, source, kBytes, static_cast<std::int64_t>(FakeAio::base),
                                alignment), "exact write failed");
                            check(std::memcmp(source, FakeAio::storage.data(), kBytes) == 0, "sample changed");
                            check(lane.submissions() == lane.completions(), "unmatched completion count");
                            check(lane.buffersRetired() && lane.close(), "successful close failed");
                            check(FakeAio::closes == 1, "context close duplicated");
                        });
                    }
                }
            }
        }
        run("one_sector_and_uneven_group", [] {
            Lane lane; check(lane.open() == Lane::Admission::Active, "open failed");
            for (unsigned size : {4096u, 12288u, 20480u, 32768u, 2121728u}) {
                check(lane.writeAll(19, source, size, 0, 4096u), "tail write failed");
                check(std::memcmp(source, FakeAio::storage.data(), size) == 0, "tail changed");
            }
        });
        run("EINTR_preserves_exact_once_ownership", [] {
            FakeAio::interruptedSubmit = FakeAio::interruptedEvents = true;
            FakeAio::destroyEintr = 2;
            Lane lane; check(lane.open() == Lane::Admission::Active, "open failed");
            check(lane.writeAll(19, source, 2097152, 0, 4096), "interrupted write failed");
            check(lane.close() && FakeAio::closes == 1, "interrupted close duplicated");
        });
        run("sandbox_denial_pre_capture_only", [] {
            FakeAio::capability = EOPNOTSUPP;
            Lane lane; check(lane.open() == Lane::Admission::Unsupported, "sandbox denial hidden");
            check(FakeAio::opens == 0 && FakeAio::writes == 0, "unsupported syscall attempted");
        });
        for (int error : {ENOSYS, EINVAL, EPERM, EOPNOTSUPP, EAGAIN, ENOMEM}) {
            run("setup_failure_classified_before_capture", [&] {
                FakeAio::setupError = error;
                Lane lane; check(lane.open() == (error == ENOMEM ? Lane::Admission::Fatal :
                    Lane::Admission::Unsupported), "wrong admission");
                check(lane.admissionErrno() == error && FakeAio::writes == 0, "admission error lost");
            });
        }
        run("zero_submission_terminal", [] { FakeAio::zeroSubmit = true; failedWrite(EIO); });
        run("excess_submission_terminal", [] { FakeAio::tooManySubmitted = true; failedWrite(EIO); });
        run("submission_storage_failure_terminal", [] { FakeAio::submitError = ENOSPC; failedWrite(ENOSPC); });
        run("event_error_retires_pending", [] { FakeAio::eventError = EIO; failedWrite(EIO); });
        run("timeout_retires_pending", [] { FakeAio::timeout = true; failedWrite(ETIMEDOUT); });
        run("short_completion_terminal", [] { FakeAio::shortResult = true; failedWrite(EIO); });
        run("negative_completion_terminal", [] { FakeAio::negativeResult = true; failedWrite(ENOSPC); });
        run("secondary_error_terminal", [] { FakeAio::badSecondary = true; failedWrite(EIO); });
        run("wrong_request_object_terminal", [] { FakeAio::badObject = true; failedWrite(EIO); });
        run("duplicate_completion_terminal", [] { FakeAio::duplicate = true; failedWrite(EIO); });
        run("stale_generation_terminal", [] { FakeAio::stale = true; failedWrite(EIO); });
        run("foreign_slot_terminal", [] { FakeAio::wrongSlot = true; failedWrite(EIO); });
        run("invalid_geometry_no_submission", [] {
            Lane lane; check(lane.open() == Lane::Admission::Active, "open failed");
            check(!lane.writeAll(19, source + 1, 4096, 0, 4096), "unaligned pointer accepted");
            check(FakeAio::writes == 0 && lane.buffersRetired(), "invalid geometry touched source");
        });
        std::free(source);
        std::printf("KERNEL_AIO_FAULT_TEST_PASS cases=%u scope=completion_algorithm_not_camera_fps\n", passed);
        return 0;
    } catch (const std::exception& failure) {
        std::free(source);
        std::fprintf(stderr, "KERNEL_AIO_FAULT_TEST_FAILED cases=%u reason=%s\n", passed, failure.what());
        return 1;
    }
}
