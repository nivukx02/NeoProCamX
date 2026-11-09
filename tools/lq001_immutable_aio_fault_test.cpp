// Faults against the app's exact immutable kernel-AIO path; never camera/FPS evidence.
#define main existingKernelAioFaultMain
#include "lq001_kernel_aio_fault_test.cpp"
#undef main
#include <sys/mman.h>

namespace {
struct ObservedAio : FakeAio {
    static inline bool refilledBeforeDrain = false;
    static inline bool invalidSource = false;
    static long submit(aio_context_t handle, unsigned count, iocb** requests) noexcept {
        for (unsigned i = 0; i < count; ++i) {
            const auto pointer = static_cast<std::uintptr_t>(requests[i]->aio_buf);
            const auto begin = reinterpret_cast<std::uintptr_t>(source);
            if (pointer < begin || pointer - begin > kBytes ||
                requests[i]->aio_nbytes > kBytes - (pointer - begin) ||
                requests[i]->aio_nbytes > kTestGroupBytes / 4u) invalidSource = true;
            if (pointer >= begin + kTestGroupBytes && !pending.empty()) refilledBeforeDrain = true;
        }
        return FakeAio::submit(handle, count, requests);
    }
};
using ImmutableLane = BoundedKernelAio<ObservedAio>;

bool writeImmutable(ImmutableLane& lane, std::size_t count = kBytes,
                    std::int64_t offset = 0, std::size_t alignment = 4096u) {
    return lane.writeImmutableStream(19, source, count, offset, alignment);
}

void expectFailure(int expected) {
    ImmutableLane lane;
    check(lane.open() == ImmutableLane::Admission::Active, "open failed");
    check(!writeImmutable(lane), "immutable failure was hidden");
    check(lane.failureErrno() == expected && lane.buffersRetired(), "failure ownership changed");
    const auto writes = FakeAio::writes;
    check(!writeImmutable(lane) && FakeAio::writes == writes, "terminal lane wrote again");
    check(lane.close() && FakeAio::closes == 1, "reader retirement not exact once");
}
} // namespace

int main() {
    try {
        void* allocation = nullptr;
        check(::posix_memalign(&allocation, 16384u, kBytes) == 0, "source allocation failed");
        source = static_cast<std::uint8_t*>(allocation);
        for (std::size_t i = 0; i < kBytes; ++i) source[i] = static_cast<std::uint8_t>(i * 31u + i / 127u);
        check(::mprotect(source, kBytes, PROT_READ) == 0, "read-only source protection failed");
        for (unsigned alignment : {4096u, 16384u})
            for (unsigned submit : {1u, 2u, 3u, 4u})
                for (unsigned events : {1u, 2u, 4u})
                    for (bool reverse : {false, true})
                        run("immutable_partial_reordered_readonly_exact_bytes", [&] {
                            FakeAio::maxSubmit = submit; FakeAio::maxEvents = events;
                            FakeAio::reverse = reverse; FakeAio::base = 5uLL * 1024u * 1024u * 1024u;
                            ObservedAio::invalidSource = false;
                            ImmutableLane lane;
                            check(lane.open() == ImmutableLane::Admission::Active, "immutable open");
                            check(writeImmutable(lane, kBytes, static_cast<std::int64_t>(FakeAio::base), alignment),
                                "immutable exact write failed");
                            check(!ObservedAio::invalidSource && FakeAio::maximumPending <= 4,
                                "immutable source bounds or request ceiling changed");
                            check(std::memcmp(source, FakeAio::storage.data(), kBytes) == 0,
                                "immutable packet bytes changed");
                            check(lane.submissions() == lane.completions() && lane.buffersRetired(),
                                "success before complete retirement");
                        });
        run("immutable_refills_without_whole_group_barrier", [] {
            FakeAio::maxEvents = 1; FakeAio::reverse = true;
            ObservedAio::refilledBeforeDrain = false;
            ImmutableLane lane; check(lane.open() == ImmutableLane::Admission::Active, "open");
            check(writeImmutable(lane) && ObservedAio::refilledBeforeDrain,
                "whole-group drain bubble remains");
        });
        for (unsigned capacity : {1u, 2u, 3u})
            for (unsigned submit : {1u, 4u})
                for (unsigned events : {1u, 4u})
                    for (bool reverse : {false, true})
                        run("immutable_queue_pressure_retires_before_retry", [&] {
                            FakeAio::queueCapacity = capacity;
                            FakeAio::maxSubmit = submit;
                            FakeAio::maxEvents = events;
                            FakeAio::reverse = reverse;
                            ObservedAio::invalidSource = false;
                            ImmutableLane lane;
                            check(lane.open() == ImmutableLane::Admission::Active, "pressure open");
                            check(writeImmutable(lane), "temporary queue pressure killed the record");
                            check(!ObservedAio::invalidSource && FakeAio::maximumPending <= capacity,
                                "pressure exceeded source or kernel request capacity");
                            check(FakeAio::backpressureEvents > 0 &&
                                lane.submissionBackpressureEvents() == FakeAio::backpressureEvents &&
                                FakeAio::submitCalls <= 2u * (kBytes / (kTestGroupBytes / 4u)) + 4u,
                                "pressure retry did not require bounded completion progress");
                            check(lane.submissions() == lane.completions() && lane.buffersRetired() &&
                                !FakeAio::waitsForUnsubmitted, "pressure lost a request ownership boundary");
                            check(std::memcmp(source, FakeAio::storage.data(), kBytes) == 0,
                                "pressure recovery changed immutable packet bytes");
                        });
        for (unsigned capacity : {1u, 2u, 3u})
            for (bool reverse : {false, true})
                run("copy_stream_queue_pressure_keeps_unsubmitted_staging_immutable", [&] {
                    void* buffer = nullptr;
                    check(::posix_memalign(&buffer, 16384u, kTestGroupBytes) == 0, "staging allocation");
                    std::unique_ptr<void, decltype(&std::free)> staging(buffer, &std::free);
                    FakeAio::queueCapacity = capacity;
                    FakeAio::maxEvents = 1;
                    FakeAio::reverse = reverse;
                    Lane lane; check(lane.open() == Lane::Admission::Active, "copy stream open");
                    check(lane.writeStream(19, static_cast<std::uint8_t*>(staging.get()),
                        kTestGroupBytes, kBytes, 0, 16384u,
                        [](std::uint8_t* destination, std::size_t offset, std::size_t bytes) {
                            std::memcpy(destination, source + offset, bytes); return true;
                        }), "copy stream failed under recoverable queue pressure");
                    check(std::memcmp(source, FakeAio::storage.data(), kBytes) == 0 &&
                        lane.submissions() == lane.completions() && lane.buffersRetired(),
                        "an unsubmitted staging slot was overwritten or acknowledged early");
                });
        run("immutable_queue_pressure_without_pending_work_is_terminal", [] {
            FakeAio::queueCapacity = 0;
            expectFailure(EAGAIN);
            check(FakeAio::completionWaits == 0 && FakeAio::submitCalls == 1,
                "empty queue pressure spun or waited for unsubmitted work");
        });
        run("immutable_pressure_completion_error_is_not_retried", [] {
            FakeAio::queueCapacity = 1; FakeAio::eventError = EIO;
            expectFailure(EIO);
            check(FakeAio::submitCalls == 2 && FakeAio::completionWaits == 1,
                "failed pressure completion was retried");
        });
        run("immutable_pressure_short_completion_remains_terminal", [] {
            FakeAio::queueCapacity = 1; FakeAio::shortResult = true;
            expectFailure(EIO);
        });
        run("immutable_later_submission_failure_after_pressure_retires_all_readers", [] {
            FakeAio::queueCapacity = 2; FakeAio::maxEvents = 1; FakeAio::failSubmitCall = 5;
            expectFailure(ENOSPC);
            check(FakeAio::backpressureEvents > 0, "test never reached queue pressure");
        });
        for (std::size_t count : {4096u, 16384u, 2u*1024u*1024u, 8u*1024u*1024u, 8u*1024u*1024u+4096u})
            run("immutable_small_tail_and_window_boundary", [&] {
                ImmutableLane lane; check(lane.open() == ImmutableLane::Admission::Active, "open");
                check(writeImmutable(lane, count) && lane.buffersRetired(), "tail rejected");
                check(std::memcmp(source, FakeAio::storage.data(), count) == 0, "tail mismatch");
            });
        run("immutable_interruptions", [] {
            FakeAio::interruptedSubmit = FakeAio::interruptedEvents = true; FakeAio::destroyEintr = 2;
            ImmutableLane lane; check(lane.open() == ImmutableLane::Admission::Active, "open");
            check(writeImmutable(lane) && lane.close() && FakeAio::closes == 1, "interruption ownership");
        });
        run("immutable_zero_submit", [] { FakeAio::zeroSubmit=true; expectFailure(EIO); });
        run("immutable_excess_submit", [] { FakeAio::tooManySubmitted=true; expectFailure(EIO); });
        run("immutable_storage_failure", [] { FakeAio::submitError=ENOSPC; expectFailure(ENOSPC); });
        run("immutable_refill_failure_retires_readers", [] {
            FakeAio::maxEvents=1; FakeAio::failSubmitCall=2; expectFailure(ENOSPC);
        });
        run("immutable_event_failure", [] { FakeAio::eventError=EIO; expectFailure(EIO); });
        run("immutable_timeout", [] { FakeAio::timeout=true; expectFailure(ETIMEDOUT); });
        run("immutable_short_completion", [] { FakeAio::shortResult=true; expectFailure(EIO); });
        run("immutable_negative_completion", [] { FakeAio::negativeResult=true; expectFailure(ENOSPC); });
        run("immutable_secondary_failure", [] { FakeAio::badSecondary=true; expectFailure(EIO); });
        run("immutable_foreign_object", [] { FakeAio::badObject=true; expectFailure(EIO); });
        run("immutable_duplicate_completion", [] { FakeAio::duplicate=true; expectFailure(EIO); });
        run("immutable_stale_generation", [] { FakeAio::stale=true; expectFailure(EIO); });
        run("immutable_foreign_slot", [] { FakeAio::wrongSlot=true; expectFailure(EIO); });
        run("immutable_unaligned_input_rejected_before_io", [] {
            ImmutableLane lane; check(lane.open() == ImmutableLane::Admission::Active, "open");
            check(!lane.writeImmutableStream(19, source+1, 4096, 0, 4096) && FakeAio::writes==0 &&
                lane.buffersRetired(), "invalid pointer reached IO");
        });
        run("immutable_offset_overflow_rejected_before_io", [] {
            ImmutableLane lane; check(lane.open() == ImmutableLane::Admission::Active, "open");
            check(!writeImmutable(lane, 8192, INT64_MAX-4095, 4096) && FakeAio::writes==0 &&
                lane.buffersRetired(), "offset overflow reached IO");
        });
        check(::mprotect(source, kBytes, PROT_READ|PROT_WRITE) == 0, "source protection restore");
        std::free(source); source=nullptr;
        std::printf("IMMUTABLE_AIO_FAULT_TEST_PASS cases=%u scope=algorithm_not_camera_fps\n", passed);
        return 0;
    } catch (const std::exception& failure) {
        if (source) { (void)::mprotect(source,kBytes,PROT_READ|PROT_WRITE); std::free(source); }
        std::fprintf(stderr,"IMMUTABLE_AIO_FAULT_TEST_FAILED cases=%u reason=%s\n",passed,failure.what());
        return 1;
    }
}
