#pragma once

#include <cerrno>
#include <cstdint>
#include <fcntl.h>

namespace neoproc::npv10 {

/** File-local placement advice. It never changes device policy, bytes, or durability. */
struct RecordWriteLifetimePosix final {
    static int get(int descriptor, std::uint64_t* value) noexcept {
#if defined(F_GET_RW_HINT)
        return ::fcntl(descriptor, F_GET_RW_HINT, value);
#else
        (void)descriptor; (void)value;
        errno = ENOTSUP;
        return -1;
#endif
    }
    static int set(int descriptor, const std::uint64_t* value) noexcept {
#if defined(F_SET_RW_HINT)
        return ::fcntl(descriptor, F_SET_RW_HINT, value);
#else
        (void)descriptor; (void)value;
        errno = ENOTSUP;
        return -1;
#endif
    }
};

/**
 * A private Cinema master is an append-only, long-lived file. Give supporting filesystems that
 * information before admitting its writer so they can separate it from short-lived app traffic.
 * This is NOT a latency guarantee: the kernel may accept advice without changing placement.
 * Preserve an existing explicit inode hint and verify every successful request by reading it back.
 * An unsupported hint never changes the selected writer or masks a later write/fsync failure.
 * The 64-bit argument is required by the kernel on all three application ABIs.
 */
template<class Io = RecordWriteLifetimePosix>
struct RecordWriteLifetime final {
    static constexpr std::uint64_t kUnspecified = 0u;
    static constexpr std::uint64_t kLong = 4u;
    std::uint64_t previous = kUnspecified;
    std::uint64_t observed = kUnspecified;
    int error = 0;
    bool attempted = false;
    bool applied = false;

    explicit RecordWriteLifetime(int descriptor) noexcept {
        const int savedErrno = errno;
        if (descriptor < 0) {
            error = EBADF;
        } else {
            int result;
            do { result = Io::get(descriptor, &previous); } while (result < 0 && errno == EINTR);
            if (result < 0) {
                error = errno;
            } else {
                observed = previous;
                if (previous == kUnspecified) {
                    attempted = true;
                    const auto requested = kLong;
                    do { result = Io::set(descriptor, &requested); } while (result < 0 && errno == EINTR);
                    if (result < 0) {
                        error = errno;
                    } else {
                        do { result = Io::get(descriptor, &observed); } while (result < 0 && errno == EINTR);
                        if (result < 0) error = errno;
                        else if (observed != requested) error = EIO;
                        else applied = true;
                    }
                }
            }
        }
        errno = savedErrno;
    }
};

} // namespace neoproc::npv10
