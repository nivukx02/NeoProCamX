#pragma once

#include <algorithm>
#include <cerrno>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <limits>
#include <sys/stat.h>
#include <unistd.h>

// QA-only prototype: must pass byte-for-byte and sustained device I/O tests before app integration.
// No container padding is added: alignment padding lies beyond the current logical tail, is within
// the already reserved extent, and is replaced by the following record or final logical truncation.
class AlignedWriterCandidate final {
public:
    static constexpr std::size_t kCapacity = 2u * 1024u * 1024u;

    AlignedWriterCandidate(int existingFd, const char* path, std::int64_t startOffset) noexcept {
        const long page = ::sysconf(_SC_PAGESIZE);
        if (page <= 0 || startOffset < 0) return;
        alignment_ = std::max<std::size_t>(4096u, static_cast<std::size_t>(page));
        if ((alignment_ & (alignment_ - 1u)) != 0u || kCapacity % alignment_ != 0u) return;
        fd_ = ::open(path, O_RDWR | O_CLOEXEC | O_NOFOLLOW | O_DIRECT);
        if (fd_ < 0) return;
        struct stat original{};
        struct stat direct{};
        if (::fstat(existingFd, &original) != 0 || ::fstat(fd_, &direct) != 0 ||
            !S_ISREG(direct.st_mode) || original.st_dev != direct.st_dev || original.st_ino != direct.st_ino ||
            startOffset > direct.st_size) return;
        void* allocated = nullptr;
        if (::posix_memalign(&allocated, alignment_, kCapacity) != 0) return;
        buffer_ = static_cast<std::uint8_t*>(allocated);
        baseOffset_ = startOffset - startOffset % static_cast<std::int64_t>(alignment_);
        used_ = static_cast<std::size_t>(startOffset - baseOffset_);
        std::size_t loaded = 0u;
        while (loaded < used_) {
            const ssize_t bytes = ::pread64(existingFd, buffer_ + loaded, used_ - loaded,
                static_cast<off64_t>(baseOffset_ + static_cast<std::int64_t>(loaded)));
            if (bytes < 0 && errno == EINTR) continue;
            if (bytes <= 0) return;
            loaded += static_cast<std::size_t>(bytes);
        }
        cursor_ = startOffset;
        ready_ = true;
    }

    ~AlignedWriterCandidate() {
        if (fd_ >= 0) (void)::close(fd_);
        std::free(buffer_);
    }
    AlignedWriterCandidate(const AlignedWriterCandidate&) = delete;
    AlignedWriterCandidate& operator=(const AlignedWriterCandidate&) = delete;

    bool ready() const noexcept { return ready_ && !failed_; }
    std::int64_t cursor() const noexcept { return cursor_; }
    std::uint64_t writeCalls() const noexcept { return writeCalls_; }

    bool append(const std::uint8_t* bytes, std::size_t count, std::int64_t reservationLimit) noexcept {
        if (!ready() || (bytes == nullptr && count != 0u) ||
            count > static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max() - cursor_)) return false;
        const auto end = cursor_ + static_cast<std::int64_t>(count);
        const auto remainder = end % static_cast<std::int64_t>(alignment_);
        const auto padding = remainder == 0 ? 0 : static_cast<std::int64_t>(alignment_) - remainder;
        if (end > reservationLimit || padding > reservationLimit - end) return false;
        while (count != 0u) {
            const auto take = std::min(count, kCapacity - used_);
            std::memcpy(buffer_ + used_, bytes, take);
            bytes += take;
            count -= take;
            used_ += take;
            cursor_ += static_cast<std::int64_t>(take);
            if (used_ == kCapacity) {
                if (!writeAligned(kCapacity)) return false;
                baseOffset_ += static_cast<std::int64_t>(kCapacity);
                used_ = 0u;
            }
        }
        return true;
    }

    // Every accepted frame is fully written before slot retirement: no unpersisted cross-frame tail.
    bool finishRecord() noexcept {
        if (!ready()) return false;
        if (used_ == 0u) return true;
        const auto rounded = ((used_ + alignment_ - 1u) / alignment_) * alignment_;
        std::memset(buffer_ + used_, 0, rounded - used_);
        if (!writeAligned(rounded)) return false;
        const auto fragment = used_ % alignment_;
        const auto complete = used_ - fragment;
        if (fragment != 0u) std::memmove(buffer_, buffer_ + complete, fragment);
        baseOffset_ += static_cast<std::int64_t>(complete);
        used_ = fragment;
        return baseOffset_ + static_cast<std::int64_t>(used_) == cursor_;
    }

private:
    bool writeAligned(std::size_t count) noexcept {
        std::size_t done = 0u;
        while (done < count) {
            const ssize_t bytes = ::pwrite64(fd_, buffer_ + done, count - done,
                static_cast<off64_t>(baseOffset_ + static_cast<std::int64_t>(done)));
            if (bytes < 0 && errno == EINTR) continue;
            ++writeCalls_;
            if (bytes <= 0 || static_cast<std::size_t>(bytes) > count - done ||
                static_cast<std::size_t>(bytes) % alignment_ != 0u) {
                failed_ = true;
                return false;
            }
            done += static_cast<std::size_t>(bytes);
        }
        return true;
    }

    int fd_ = -1;
    std::uint8_t* buffer_ = nullptr;
    std::size_t alignment_ = 0u;
    std::size_t used_ = 0u;
    std::int64_t baseOffset_ = 0;
    std::int64_t cursor_ = 0;
    std::uint64_t writeCalls_ = 0u;
    bool ready_ = false;
    bool failed_ = false;
};
