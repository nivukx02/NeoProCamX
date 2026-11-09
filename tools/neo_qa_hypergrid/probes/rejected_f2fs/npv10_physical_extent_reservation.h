// REJECTED: actual app tests exposed nonzero unwritten data and EFBIG on later pinning.
// Retained for reproduction only. Never include this allocator in a production target.
#pragma once

#include <cerrno>
#include <cstdint>
#include <fcntl.h>
#include <limits>
#include <linux/f2fs.h>
#include <linux/fs.h>
#include <mutex>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/vfs.h>
#include <unistd.h>

namespace neoproc::npv10 {

struct PhysicalExtentPosixIo {
    static int stat(int fd, struct stat64* output) noexcept { return ::fstat64(fd, output); }
    static int filesystem(int fd, struct statfs* output) noexcept { return ::fstatfs(fd, output); }
    static int flags(int fd, unsigned long* output) noexcept { return ::ioctl(fd, FS_IOC_GETFLAGS, output); }
    static int pin(int fd, std::uint32_t value) noexcept { return ::ioctl(fd, F2FS_IOC_SET_PIN_FILE, &value); }
    static int allocate(int fd, std::int64_t offset, std::int64_t count) noexcept {
        return ::posix_fallocate64(fd, static_cast<off64_t>(offset), static_cast<off64_t>(count));
    }
};

struct PhysicalExtentReservationResult final {
    int error = 0;
    int capabilityError = 0;
    int releaseError = 0;
    bool materialized = false;
};

/**
 * Reserve capacity AND materialize F2FS block mappings ahead of direct recording writes.
 *
 * Ordinary F2FS fallocate uses PRE_AIO reservations (NEW_ADDR), which can still require block
 * allocation in the recording's direct-I/O path. F2FS fallocate on a pinned inode uses PRE_DIO
 * physical section allocation. The pin belongs ONLY to this allocation operation and is restored
 * before return, so no persistent pin or GC exemption is left on a completed recording.
 *
 * Unsupported filesystems/app policies keep the existing posix_fallocate path. After acquiring a
 * pin, allocation/verification/release failure is fatal: never silently retry through another path.
 * No file flags are overwritten wholesale, no compressed-file policy is changed, and existing
 * pins are not ours to release. Callers still own capacity limits, pacing, durability and errors.
 */
template<class Io = PhysicalExtentPosixIo>
class PhysicalExtentReservation final {
public:
    static PhysicalExtentReservationResult allocate(int fd, std::int64_t offset,
                                                     std::int64_t count) noexcept {
        // A pin is inode-wide, not descriptor-local. Serialize our own reservations so two
        // duplicated descriptors cannot release one another's temporary pin during allocation.
        // The recording writer never takes this mutex.
        std::lock_guard<std::mutex> guard(reservationMutex_);
        PhysicalExtentReservationResult result;
        if (fd < 0 || offset < 0 || count <= 0 ||
            count > std::numeric_limits<std::int64_t>::max() - offset) {
            result.error = EINVAL;
            return result;
        }
        struct stat64 file {};
        if (Io::stat(fd, &file) != 0) {
            result.error = errorNumber();
            return result;
        }
        if (!S_ISREG(file.st_mode)) {
            result.error = EINVAL;
            return result;
        }
        struct statfs filesystem {};
        if (Io::filesystem(fd, &filesystem) != 0) {
            result.error = errorNumber();
            return result;
        }
        bool ownsPin = false;
        bool pinned = false;
        if (static_cast<unsigned long>(filesystem.f_type) == 0xf2f52010UL) {
            unsigned long before = 0;
            if (Io::flags(fd, &before) != 0) {
                result.capabilityError = errorNumber();
                if (!unsupported(result.capabilityError)) {
                    result.error = result.capabilityError;
                    return result;
                }
            } else if ((before & FS_COMPR_FL) != 0) {
                // SET_PIN_FILE may disable filesystem compression. Never mutate that policy.
                result.capabilityError = EOPNOTSUPP;
            } else if ((before & FS_NOCOW_FL) != 0) {
                pinned = true;
            } else {
                int changed;
                do { changed = Io::pin(fd, 1u); } while (changed < 0 && errno == EINTR);
                if (changed < 0) {
                    result.capabilityError = errorNumber();
                    if (!unsupported(result.capabilityError)) {
                        result.error = result.capabilityError;
                        return result;
                    }
                } else {
                    ownsPin = true;
                    unsigned long after = 0;
                    if (Io::flags(fd, &after) != 0) result.error = errorNumber();
                    else if ((after & FS_NOCOW_FL) == 0) result.error = EIO;
                    else pinned = true;
                }
            }
        } else {
            result.capabilityError = EOPNOTSUPP;
        }
        if (result.error == 0) {
            do { result.error = Io::allocate(fd, offset, count); } while (result.error == EINTR);
            // POSIX fallocate returns an errno value, not -1/errno.
            if (result.error < 0) result.error = EIO;
            result.materialized = pinned && result.error == 0;
        }
        if (ownsPin) {
            int released;
            do { released = Io::pin(fd, 0u); } while (released < 0 && errno == EINTR);
            if (released < 0) result.releaseError = errorNumber();
            else {
                unsigned long after = 0;
                if (Io::flags(fd, &after) != 0) result.releaseError = errorNumber();
                else if ((after & FS_NOCOW_FL) != 0) result.releaseError = EIO;
            }
            if (result.error == 0) result.error = result.releaseError;
        }
        // Do not advertise a usable/materialized prefix when restoration or allocation failed.
        if (result.error != 0) result.materialized = false;
        return result;
    }

private:
    static inline std::mutex reservationMutex_;
    static int errorNumber() noexcept { return errno > 0 ? errno : EIO; }
    static bool unsupported(int error) noexcept {
        return error == ENOTTY || error == EOPNOTSUPP || error == ENOSYS ||
            error == EINVAL || error == EPERM || error == EACCES || error == EAGAIN;
    }
};

} // namespace neoproc::npv10
