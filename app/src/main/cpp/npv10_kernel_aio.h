#pragma once

#include <algorithm>
#include <array>
#include <atomic>
#include <cerrno>
#include <cstdint>
#include <limits>
#include <linux/aio_abi.h>
#include <memory>
#include <new>
#include <signal.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

namespace neoproc::npv10 {

/** Real syscall seam. Fault tests instantiate the exact completion algorithm with another seam. */
struct KernelAioPosix {
    static long setup(unsigned depth, aio_context_t* context) noexcept {
        return ::syscall(__NR_io_setup, depth, context);
    }
    static long submit(aio_context_t context, unsigned count, iocb** requests) noexcept {
        return ::syscall(__NR_io_submit, context, count, requests);
    }
    static long events(aio_context_t context, unsigned count, io_event* output,
                       unsigned minimum = 1u) noexcept {
        if (minimum == 0u || minimum > count || output == nullptr) {
            errno = EINVAL;
            return -1;
        }
        timespec timeout{30, 0};
        return ::syscall(__NR_io_getevents, context, minimum, count, output, &timeout);
    }
    static long destroy(aio_context_t context) noexcept {
        return ::syscall(__NR_io_destroy, context);
    }

    static int sandboxCapability() noexcept {
        // A vendor app-seccomp policy may SIGSYS an unsupported syscall instead of returning an
        // errno. Probe once, before capture, in a child which inherits that exact app policy.
        // The child performs only async-signal-safe calls; it never uses JNI, malloc or app locks.
        static const int capability = []() noexcept {
            const pid_t child = ::fork();
            if (child < 0) return errno == 0 ? EAGAIN : errno;
            if (child == 0) {
                (void)::alarm(2u);
                aio_context_t context = 0;
                if (setup(4u, &context) != 0) ::_exit(20);
                iocb* requests[1]{};
                io_event completions[1]{};
                timespec zero{};
                int result = 0;
                if (::syscall(__NR_io_submit, context, 0, requests) < 0) result = 21;
                if (::syscall(__NR_io_getevents, context, 0, 0, completions, &zero) < 0) result = 22;
                if (destroy(context) != 0) result = 23;
                ::_exit(result);
            }
            int status = 0;
            for (unsigned attempt = 0; attempt < 300u; ++attempt) {
                const auto waited = ::waitpid(child, &status, WNOHANG);
                if (waited == child) return WIFEXITED(status) && WEXITSTATUS(status) == 0
                    ? 0 : EOPNOTSUPP;
                if (waited < 0 && errno != EINTR) return errno == 0 ? ECHILD : errno;
                timespec interval{0, 10000000};
                while (::nanosleep(&interval, &interval) != 0 && errno == EINTR) {}
            }
            (void)::kill(child, SIGKILL);
            while (::waitpid(child, &status, 0) < 0 && errno == EINTR) {}
            return ETIMEDOUT;
        }();
        return capability;
    }
};

/**
 * Four disjoint direct-I/O requests inside ONE existing staging range, not a frame backlog.
 * writeAll never returns success until every byte completes. No kernel reader may outlive its
 * staging buffer. Failed/short/mismatched completions are terminal; there is no lossy retry or
 * mid-record switch to another backend. Existing fsync and record-order barriers remain external.
 */
template <class Io = KernelAioPosix,
          std::size_t MaximumGroupBytes = 8u * 1024u * 1024u,
          unsigned RequestDepth = 4u,
          bool CoalesceGroupCompletions = false>
class BoundedKernelAio final {
public:
    enum class Admission { Active, Unsupported, Fatal };
    static_assert(RequestDepth == 4u || RequestDepth == 8u,
        "Only measured four- or eight-request bounded I/O windows are supported");
    static constexpr unsigned kDepth = RequestDepth;
    // Preserve the existing default. A larger, explicitly bounded staging range can be tested
    // without adding requests, retaining frames longer, or acknowledging incomplete writes.
    static_assert(MaximumGroupBytes >= 32768u && MaximumGroupBytes <= 32u * 1024u * 1024u &&
        (MaximumGroupBytes & (MaximumGroupBytes - 1u)) == 0u,
        "Kernel AIO group must be a bounded, power-of-two staging range");
    static constexpr std::size_t kMaximumGroupBytes = MaximumGroupBytes;

    Admission open() noexcept {
        if (state_) return reject(EALREADY, Admission::Fatal);
        const int capability = Io::sandboxCapability();
        if (capability != 0) return reject(capability, Admission::Unsupported);
        state_.reset(new (std::nothrow) State());
        if (!state_) return reject(ENOMEM, Admission::Fatal);
        long result;
        do { result = Io::setup(kDepth, &state_->context); } while (result < 0 && errno == EINTR);
        if (result != 0 || state_->context == 0) {
            const int failure = result < 0 ? errno : EIO;
            return reject(failure, failure == ENOSYS || failure == EINVAL || failure == EPERM ||
                failure == EOPNOTSUPP || failure == EAGAIN ? Admission::Unsupported : Admission::Fatal);
        }
        admission_ = Admission::Active;
        return admission_;
    }

    ~BoundedKernelAio() {
        if (!close() && state_ && !buffersRetired()) {
            // A kernel refusing retirement must not read freed iocbs/source storage. The owner
            // also retains its bounded staging allocation in this terminal, unpublished state.
            // Normal cancellation and every supported syscall failure retire synchronously.
            (void)state_.release();
        }
    }
    BoundedKernelAio() = default;
    BoundedKernelAio(const BoundedKernelAio&) = delete;
    BoundedKernelAio& operator=(const BoundedKernelAio&) = delete;

    bool writeAll(int fd, const std::uint8_t* bytes, std::size_t count,
                  std::int64_t offset, std::size_t alignment) noexcept {
        if (admission_ != Admission::Active || !state_ || state_->context == 0 || failureErrno() != 0)
            return false;
        if (fd < 0 || bytes == nullptr || count == 0 || alignment == 0 ||
            (alignment & (alignment - 1u)) != 0 || alignment > kMaximumGroupBytes ||
            reinterpret_cast<std::uintptr_t>(bytes) % alignment != 0 || count % alignment != 0 ||
            offset < 0 || offset % static_cast<std::int64_t>(alignment) != 0 ||
            count > static_cast<std::uint64_t>(INT64_MAX - offset)) return fail(EINVAL);
        while (count != 0) {
            const auto group = std::min(count, kMaximumGroupBytes);
            if (!writeGroup(fd, bytes, group, offset, alignment)) return false;
            bytes += group;
            count -= group;
            offset += static_cast<std::int64_t>(group);
        }
        return true;
    }

    /**
     * Stream ONE record through the bounded request set and fixed staging allocation. A completed
     * slot can be refilled while other slots are still in flight; unlike writeAll's independent
     * groups, a slow final request does not leave three slots idle between chunks of a record.
     * Fill runs synchronously and must provide the exact bytes of [position, position + size).
     * No success, staging reuse, record boundary, or durability barrier precedes full completion.
     */
    template<bool ImmutableSource = false, class Fill>
    bool writeStream(int fd, std::uint8_t* staging, std::size_t stagingBytes,
                     std::size_t count, std::int64_t offset, std::size_t alignment,
                     Fill&& fill) noexcept {
        if (admission_ != Admission::Active || !state_ || state_->context == 0 || failureErrno() != 0)
            return false;
        if (fd < 0 || staging == nullptr || count == 0 || alignment == 0 ||
            (alignment & (alignment - 1u)) != 0 || stagingBytes != kMaximumGroupBytes ||
            stagingBytes % kDepth != 0 || stagingBytes / kDepth < alignment ||
            (stagingBytes / kDepth) % alignment != 0 ||
            reinterpret_cast<std::uintptr_t>(staging) % alignment != 0 || count % alignment != 0 ||
            offset < 0 || offset % static_cast<std::int64_t>(alignment) != 0 ||
            count > static_cast<std::uint64_t>(INT64_MAX - offset)) return fail(EINVAL);
        auto& state = *state_;
        if (state.pending != 0 || state.uncertain) return fail(EBUSY);
        const std::size_t chunkCapacity = stagingBytes / kDepth;
        std::size_t produced = 0;
        std::size_t persisted = 0;
        unsigned active = 0;
        while (produced < count || state.pending != 0) {
            unsigned preparedCount = 0;
            std::array<unsigned, kDepth> preparedSlots{};
            for (unsigned slot = 0; slot < kDepth && produced < count; ++slot) {
                if ((active & (1u << slot)) != 0u) continue;
                if (state.generation == (UINT64_MAX >> 3u)) return fail(EOVERFLOW);
                const auto bytes = std::min(chunkCapacity, count - produced);
                // An owned immutable record already contains its final aligned bytes. Refill
                // request descriptors directly from that record instead of copying each chunk
                // into a second staging range. The same four slots and 8-MiB in-flight ceiling
                // apply; not one slot is reused until its exact kernel completion is verified.
                auto* destination = staging + (ImmutableSource ? produced : slot * chunkCapacity);
                if constexpr (!ImmutableSource) {
                    if (!fill(destination, produced, bytes)) return fail(EIO);
                }
                auto& request = state.requests[slot];
                request = {};
                request.aio_data = (++state.generation << 3u) | slot;
                request.aio_lio_opcode = IOCB_CMD_PWRITE;
                request.aio_fildes = static_cast<std::uint32_t>(fd);
                request.aio_buf = reinterpret_cast<std::uintptr_t>(destination);
                request.aio_nbytes = bytes;
                request.aio_offset = offset + static_cast<std::int64_t>(produced);
                state.addresses[preparedCount] = &request;
                preparedSlots[preparedCount++] = slot;
                produced += bytes;
            }
            unsigned first = 0;
            while (first < preparedCount) {
                const unsigned requested = preparedCount - first;
                long submitted;
                do { submitted = Io::submit(state.context, requested,
                    state.addresses.data() + first); } while (submitted < 0 && errno == EINTR);
                if (submitted < 0 && errno == EAGAIN && state.pending != 0u) {
                    // A short submit owns only its accepted prefix. A temporarily full kernel
                    // queue is recoverable by validating completion of that accepted work, not
                    // by failing the recording or spinning on the same submission. Retain ALL
                    // prepared descriptors and their immutable bytes until the remaining prefix
                    // is submitted; the outer refill loop cannot overwrite those waiting slots.
                    submissionBackpressureEvents_.fetch_add(1u, std::memory_order_relaxed);
                    if (!reapStream(active, persisted)) return false;
                    continue;
                }
                if (submitted <= 0 || submitted > static_cast<long>(requested)) {
                    state.uncertain = submitted > 0;
                    return fail(submitted < 0 ? errno : EIO);
                }
                if (submitted < static_cast<long>(requested))
                    partialSubmissions_.fetch_add(1u, std::memory_order_relaxed);
                for (unsigned index = first; index < first + static_cast<unsigned>(submitted); ++index)
                    active |= 1u << preparedSlots[index];
                state.pending += static_cast<unsigned>(submitted);
                submissions_.fetch_add(static_cast<std::uint64_t>(submitted), std::memory_order_relaxed);
                first += static_cast<unsigned>(submitted);
            }
            if (!reapStream(active, persisted)) return false;
        }
        if (active != 0 || persisted != count) return fail(EIO);
        return true;
    }

    /**
     * Stream ONE complete immutable owned record without inter-group drain bubbles or a payload
     * copy. The caller retains the whole source through this call AND any failed retirement.
     * This changes neither the maximum pending requests/bytes nor record/durability boundaries.
     * Kept opt-in until a physical same-input comparison validates the device's storage behavior.
     */
    bool writeImmutableStream(int fd, const std::uint8_t* bytes, std::size_t count,
                              std::int64_t offset, std::size_t alignment) noexcept {
        return writeStream<true>(fd, const_cast<std::uint8_t*>(bytes), kMaximumGroupBytes,
            count, offset, alignment,
            [](std::uint8_t*, std::size_t, std::size_t) noexcept { return true; });
    }

    bool close() noexcept {
        if (!state_ || state_->context == 0) return true;
        long result;
        do { result = Io::destroy(state_->context); } while (result < 0 && errno == EINTR);
        if (result == 0 || (result < 0 && errno == EINVAL)) {
            // EINVAL proves this previously owned context no longer exists. Keep it a terminal
            // error, but do not retain buffers for a nonexistent kernel context.
            state_->context = 0;
            state_->pending = 0;
            state_->uncertain = false;
            if (result < 0) { latch(EINVAL); return false; }
            return true;
        }
        latch(result < 0 ? errno : EIO);
        return false;
    }

    bool buffersRetired() const noexcept {
        return !state_ || state_->context == 0 || (state_->pending == 0 && !state_->uncertain);
    }
    int admissionErrno() const noexcept { return admissionErrno_; }
    int failureErrno() const noexcept { return failure_.load(std::memory_order_relaxed); }
    std::uint64_t submissions() const noexcept { return submissions_.load(std::memory_order_relaxed); }
    std::uint64_t completions() const noexcept { return completions_.load(std::memory_order_relaxed); }
    std::uint64_t partialSubmissions() const noexcept {
        return partialSubmissions_.load(std::memory_order_relaxed);
    }
    std::uint64_t submissionBackpressureEvents() const noexcept {
        return submissionBackpressureEvents_.load(std::memory_order_relaxed);
    }

private:
    struct State {
        aio_context_t context = 0;
        std::array<iocb, kDepth> requests{};
        std::array<iocb*, kDepth> addresses{};
        unsigned pending = 0;
        bool uncertain = false;
        std::uint64_t generation = 0;
    };

    // Exactly the same completion proof for ordinary streaming and queue-pressure recovery.
    // Every retry must retire accepted work first. Errors remain terminal and io_destroy must
    // retire all other readers before caller-owned packet or staging storage can be released.
    bool reapStream(unsigned& active, std::size_t& persisted) noexcept {
        auto& state = *state_;
        if (state.pending == 0 || state.pending > kDepth) return fail(EIO);
        std::array<io_event, kDepth> events{};
        long completed;
        do { completed = Io::events(state.context, state.pending, events.data()); }
        while (completed < 0 && errno == EINTR);
        if (completed <= 0 || completed > static_cast<long>(state.pending)) {
            state.uncertain = true;
            return fail(completed < 0 ? errno : ETIMEDOUT);
        }
        for (long index = 0; index < completed; ++index) {
            const auto& event = events[static_cast<std::size_t>(index)];
            const auto slot = static_cast<unsigned>(event.data & 7u);
            if (slot >= kDepth || (active & (1u << slot)) == 0u ||
                event.data != state.requests[slot].aio_data ||
                event.obj != reinterpret_cast<std::uintptr_t>(&state.requests[slot])) {
                state.uncertain = true;
                return fail(EIO);
            }
            const auto expected = state.requests[slot].aio_nbytes;
            if (event.res != static_cast<std::int64_t>(expected) || event.res2 != 0) {
                const int error = event.res < 0 && event.res >= -4095
                    ? static_cast<int>(-event.res) : EIO;
                return fail(error);
            }
            active &= ~(1u << slot);
            --state.pending;
            persisted += static_cast<std::size_t>(expected);
            completions_.fetch_add(1u, std::memory_order_relaxed);
        }
        return true;
    }

    Admission reject(int failure, Admission decision) noexcept {
        admissionErrno_ = failure == 0 ? EIO : failure;
        admission_ = decision;
        if (!close()) admission_ = Admission::Fatal;
        return admission_;
    }
    void latch(int error) noexcept {
        int expected = 0;
        (void)failure_.compare_exchange_strong(expected, error == 0 ? EIO : error,
            std::memory_order_relaxed);
        errno = failureErrno();
    }
    bool fail(int error) noexcept {
        latch(error);
        (void)close(); // io_destroy retires accepted requests before source ownership can return.
        errno = failureErrno();
        return false;
    }

    bool writeGroup(int fd, const std::uint8_t* bytes, std::size_t count,
                    std::int64_t offset, std::size_t alignment) noexcept {
        auto& state = *state_;
        if (state.pending != 0 || state.generation == (UINT64_MAX >> 3u)) return fail(EOVERFLOW);
        ++state.generation;
        const auto desired = (count + kDepth - 1u) / kDepth;
        const auto chunk = ((desired + alignment - 1u) / alignment) * alignment;
        unsigned requestCount = 0;
        for (std::size_t position = 0; position < count; position += chunk) {
            auto& request = state.requests[requestCount];
            request = {};
            request.aio_data = (state.generation << 3u) | requestCount;
            request.aio_lio_opcode = IOCB_CMD_PWRITE;
            request.aio_fildes = static_cast<std::uint32_t>(fd);
            request.aio_buf = reinterpret_cast<std::uintptr_t>(bytes + position);
            request.aio_nbytes = std::min(chunk, count - position);
            request.aio_offset = offset + static_cast<std::int64_t>(position);
            state.addresses[requestCount] = &request;
            ++requestCount;
        }
        unsigned first = 0;
        unsigned seen = 0;
        while (first < requestCount || state.pending != 0) {
            if (first < requestCount) {
                const unsigned requested = requestCount - first;
                long submitted;
                do { submitted = Io::submit(state.context, requested,
                    state.addresses.data() + first); } while (submitted < 0 && errno == EINTR);
                if (submitted > 0 && submitted <= static_cast<long>(requested)) {
                    // A short io_submit accepts a prefix, not a completed batch. Fill the remaining
                    // disjoint slots before waiting; draining that prefix here serialized a four-way
                    // write into up to four independent storage waits. Requests and source bytes stay
                    // immutable until ALL accepted completions have been checked below.
                    if (submitted < static_cast<long>(requested))
                        partialSubmissions_.fetch_add(1u, std::memory_order_relaxed);
                    first += static_cast<unsigned>(submitted);
                    state.pending += static_cast<unsigned>(submitted);
                    submissions_.fetch_add(static_cast<std::uint64_t>(submitted), std::memory_order_relaxed);
                    continue;
                }
                // A temporarily full kernel queue can make progress only by retiring an accepted
                // request. Reap before retrying; never spin, add buffers, duplicate a submission, or
                // treat EAGAIN with no in-flight work as success. Other failures still retire the
                // entire context before returning ownership to the recorder.
                if (!(submitted < 0 && errno == EAGAIN && state.pending != 0)) {
                    state.uncertain = submitted > 0;
                    return fail(submitted < 0 ? errno : EIO);
                }
                submissionBackpressureEvents_.fetch_add(1u, std::memory_order_relaxed);
            }
            {
                std::array<io_event, kDepth> events{};
                long completed;
                // A fully submitted group cannot reuse ANY part of its staging range until all
                // readers finish. Waking the writer at each early completion does no useful work
                // and can repeatedly put a hot recording thread back through the scheduler. Ask
                // for the whole accepted remainder in that case. Under submission backpressure we
                // still wait for just one, so capacity can be refilled without waiting for requests
                // the kernel has not accepted. Streaming slots keep their separate min-one path.
                // Signals/timeouts may return a short positive batch: validate every completion and
                // then wait for the remaining accepted readers, never acknowledge them early.
                // Retained for controlled native comparison only. Physical ABBA measurements on
                // the target did not improve latency, so normal recording keeps min-one waits.
                const unsigned minimum = CoalesceGroupCompletions && first == requestCount
                    ? state.pending : 1u;
                do { completed = Io::events(state.context, state.pending, events.data(), minimum); }
                while (completed < 0 && errno == EINTR);
                if (completed <= 0 || completed > static_cast<long>(state.pending)) {
                    state.uncertain = true;
                    return fail(completed < 0 ? errno : ETIMEDOUT);
                }
                for (long index = 0; index < completed; ++index) {
                    const auto& event = events[static_cast<std::size_t>(index)];
                    const auto slot = static_cast<unsigned>(event.data & 7u);
                    if ((event.data >> 3u) != state.generation || slot >= first ||
                        (seen & (1u << slot)) != 0 ||
                        event.obj != reinterpret_cast<std::uintptr_t>(&state.requests[slot])) {
                        state.uncertain = true;
                        return fail(EIO);
                    }
                    const auto expected = state.requests[slot].aio_nbytes;
                    if (event.res != static_cast<std::int64_t>(expected) || event.res2 != 0) {
                        const int error = event.res < 0 && event.res >= -4095
                            ? static_cast<int>(-event.res) : EIO;
                        return fail(error);
                    }
                    seen |= 1u << slot;
                    completions_.fetch_add(1u, std::memory_order_relaxed);
                }
                state.pending -= static_cast<unsigned>(completed);
            }
        }
        if (seen != (1u << requestCount) - 1u) return fail(EIO);
        return true;
    }

    std::unique_ptr<State> state_;
    Admission admission_ = Admission::Unsupported;
    int admissionErrno_ = 0;
    std::atomic<int> failure_{0};
    std::atomic<std::uint64_t> submissions_{0};
    std::atomic<std::uint64_t> completions_{0};
    std::atomic<std::uint64_t> partialSubmissions_{0};
    std::atomic<std::uint64_t> submissionBackpressureEvents_{0};
};

} // namespace neoproc::npv10
