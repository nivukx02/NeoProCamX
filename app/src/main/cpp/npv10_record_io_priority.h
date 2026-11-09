#pragma once

#include <cerrno>
#include <cstdint>
#include <signal.h>
#include <sys/syscall.h>
#include <sys/wait.h>
#include <time.h>
#include <unistd.h>

namespace neoproc::npv10 {

struct RecordIoPriorityPosix {
    // Linux best-effort class, level zero. Never request the privileged realtime class.
    static constexpr int kBestEffort = 2 << 13;
    static long getCurrent() noexcept { return ::syscall(__NR_ioprio_get, 1, 0); }
    static long setCurrent(int priority) noexcept {
        // IOPRIO_WHO_PROCESS / zero addresses only the calling thread, not the app or another UID.
        return ::syscall(__NR_ioprio_set, 1, 0, priority);
    }

    static int capability() noexcept {
        // Vendor app seccomp can kill on a denied syscall instead of returning ENOSYS. Admit once
        // in an isolated child before touching the live writer; only async-signal-safe calls run
        // there. Probe availability is advisory and cannot bypass any data/durability failure.
        static const int result = []() noexcept {
            const pid_t child = ::fork();
            if (child < 0) return errno == 0 ? EAGAIN : errno;
            if (child == 0) {
                (void)::alarm(2u);
                if (getCurrent() < 0 || setCurrent(kBestEffort) != 0)
                    ::_exit(errno > 0 && errno < 256 ? errno : EIO);
                ::_exit(getCurrent() == kBestEffort ? 0 : EIO);
            }
            int status = 0;
            for (unsigned attempt = 0; attempt < 300u; ++attempt) {
                const auto waited = ::waitpid(child, &status, WNOHANG);
                if (waited == child) return WIFEXITED(status) ? WEXITSTATUS(status) : EOPNOTSUPP;
                if (waited < 0 && errno != EINTR) return errno == 0 ? ECHILD : errno;
                timespec interval{0, 10'000'000};
                while (::nanosleep(&interval, &interval) != 0 && errno == EINTR) {}
            }
            (void)::kill(child, SIGKILL);
            while (::waitpid(child, &status, 0) < 0 && errno == EINTR) {}
            return ETIMEDOUT;
        }();
        return result;
    }
};

/**
 * One thread-owned scheduling hint for the ordered recording writer, not a quality governor.
 * CPU nice does not set a thread's explicit storage-I/O priority. Keep camera writes in the
 * best-effort foreground class without realtime priority, new buffers, thermal overrides,
 * changed frame admission, or early write acknowledgements. Unsupported/denied hints are
 * reported and retain the existing backend; every actual I/O failure remains fatal there.
 */
template<class Io = RecordIoPriorityPosix>
class ScopedRecordIoPriority final {
public:
    static constexpr int kRequested = 2 << 13;

    explicit ScopedRecordIoPriority(bool enabled = true) noexcept {
        if (!enabled) return;
        error_ = Io::capability();
        if (error_ != 0) return;
        const auto prior = Io::getCurrent();
        if (prior < 0) { error_ = currentError(); return; }
        prior_ = static_cast<int>(prior);
        observed_ = prior_;
        // Preserve an existing realtime owner or the already selected best-effort priority.
        if ((prior_ >> 13) == 1 || prior_ == kRequested) return;
        if (Io::setCurrent(kRequested) != 0) { error_ = currentError(); return; }
        changed_ = true;
        const auto observed = Io::getCurrent();
        if (observed < 0) { error_ = currentError(); return; }
        observed_ = static_cast<int>(observed);
        if (observed_ != kRequested) error_ = EIO;
    }

    ~ScopedRecordIoPriority() { (void)restore(); }
    ScopedRecordIoPriority(const ScopedRecordIoPriority&) = delete;
    ScopedRecordIoPriority& operator=(const ScopedRecordIoPriority&) = delete;

    bool applied() const noexcept { return changed_ && observed_ == kRequested && error_ == 0; }
    int previous() const noexcept { return prior_; }
    int observed() const noexcept { return observed_; }
    int error() const noexcept { return error_; }

    bool restore() noexcept {
        if (!changed_) return true;
        const auto current = Io::getCurrent();
        if (current < 0) { error_ = currentError(); return false; }
        if (current != kRequested) {
            // A platform scheduler can supersede this hint. Never undo its newer decision.
            changed_ = false;
            return true;
        }
        if (Io::setCurrent(prior_) != 0) { error_ = currentError(); return false; }
        changed_ = false;
        return true;
    }

private:
    static int currentError() noexcept { return errno == 0 ? EIO : errno; }
    int prior_ = -1;
    int observed_ = -1;
    int error_ = 0;
    bool changed_ = false;
};

} // namespace neoproc::npv10
