// Physical-filesystem diagnosis only. No camera or recording PASS is inferred.
#include <array>
#include <cerrno>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <linux/f2fs.h>
#include <linux/fiemap.h>
#include <linux/fs.h>
#include <stdexcept>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <sys/vfs.h>
#include <unistd.h>
#include <vector>

namespace {
constexpr std::int64_t kReservation = 64LL * 1024LL * 1024LL;
using Clock = std::chrono::steady_clock;

void require(bool condition, const char* reason) {
    if (!condition) throw std::runtime_error(reason);
}

class OwnedFile final {
public:
    char path[96] = "camx-physical-extents-probe-XXXXXX";
    int fd = ::mkstemp(path);
    bool pinOwned = false;
    ~OwnedFile() {
        if (fd >= 0) {
            if (pinOwned) {
                std::uint32_t disable = 0;
                (void)::ioctl(fd, F2FS_IOC_SET_PIN_FILE, &disable);
            }
            (void)::close(fd);
            (void)::unlink(path); // Only this object's fresh, exclusive temporary file.
        }
    }
};

void snapshot(OwnedFile& file, const char* stage, bool pinRequested) {
    struct stat64 info {};
    require(::fstat64(file.fd, &info) == 0, "fstat64 failed");
    unsigned long flags = 0;
    errno = 0;
    const int flagsResult = ::ioctl(file.fd, FS_IOC_GETFLAGS, &flags);
    const int flagsError = errno;
    // uint64_t storage supplies the alignment required by the FIEMAP ABI. Use only this
    // explicit 64-MiB owned range, never query other files or expose physical addresses.
    std::vector<std::uint64_t> mapStorage((sizeof(fiemap) + 128 * sizeof(fiemap_extent) + 7) / 8);
    auto* map = reinterpret_cast<fiemap*>(mapStorage.data());
    map->fm_length = kReservation;
    map->fm_extent_count = 128;
    errno = 0;
    const int mapResult = ::ioctl(file.fd, FS_IOC_FIEMAP, map);
    const int mapError = errno;
    std::uint64_t mappedBytes = 0;
    std::uint64_t deferredBytes = 0;
    std::uint32_t combinedFlags = 0;
    if (mapResult == 0) {
        require(map->fm_mapped_extents <= 128, "FIEMAP count exceeded its buffer");
        for (unsigned i = 0; i < map->fm_mapped_extents; ++i) {
            const auto& extent = map->fm_extents[i];
            combinedFlags |= extent.fe_flags;
            if ((extent.fe_flags & (FIEMAP_EXTENT_DELALLOC | FIEMAP_EXTENT_UNKNOWN)) != 0 ||
                extent.fe_physical == 0) deferredBytes += extent.fe_length;
            else mappedBytes += extent.fe_length;
        }
    }
    std::printf("EXTENT_SNAPSHOT stage=%s pin_requested=%d bytes=%lld allocated_512_blocks=%lld "
        "getflags_result=%d getflags_errno=%d nocow=%d fiemap_result=%d fiemap_errno=%d "
        "extents=%u physical_bytes=%llu deferred_bytes=%llu extent_flags=%u\n",
        stage, pinRequested, static_cast<long long>(info.st_size),
        static_cast<long long>(info.st_blocks), flagsResult, flagsError,
        (flags & FS_NOCOW_FL) != 0, mapResult, mapError, map->fm_mapped_extents,
        static_cast<unsigned long long>(mappedBytes),
        static_cast<unsigned long long>(deferredBytes), combinedFlags);
    std::fflush(stdout);
}

void probe(bool pinRequested) {
    OwnedFile file;
    require(file.fd >= 0, "exclusive probe creation failed");
    struct statfs filesystem {};
    require(::fstatfs(file.fd, &filesystem) == 0, "filesystem identity unavailable");
    require(static_cast<unsigned long>(filesystem.f_type) == 0xf2f52010UL, "target is not F2FS");
    if (pinRequested) {
        std::uint32_t enable = 1;
        errno = 0;
        const int result = ::ioctl(file.fd, F2FS_IOC_SET_PIN_FILE, &enable);
        const int error = errno;
        file.pinOwned = result >= 0;
        std::printf("PIN_CAPABILITY uid=%u result=%d errno=%d\n",
            static_cast<unsigned>(::getuid()), result, error);
        std::fflush(stdout);
        if (result < 0) return; // No permission escalation or fallback labelled as success.
    }
    const auto began = Clock::now();
    const int allocated = ::posix_fallocate64(file.fd, 0, kReservation);
    std::printf("RESERVATION pin_requested=%d result=%d elapsed_ms=%.6f\n", pinRequested,
        allocated, std::chrono::duration<double, std::milli>(Clock::now() - began).count());
    require(allocated == 0, "physical reservation syscall failed");
    snapshot(file, "allocated", pinRequested);
    require(::fdatasync(file.fd) == 0, "reservation durability failed");
    snapshot(file, "synced", pinRequested);
    if (file.pinOwned) {
        std::uint32_t disable = 0;
        require(::ioctl(file.fd, F2FS_IOC_SET_PIN_FILE, &disable) >= 0, "owned pin release failed");
        file.pinOwned = false;
        snapshot(file, "released", pinRequested);
    }
}
} // namespace

int main() {
    try {
        probe(false);
        probe(true);
        std::puts("DIAGNOSIS_COMPLETE scope=owned_temporary_files_not_camera_fps");
        return 0;
    } catch (const std::exception& error) {
        std::fprintf(stderr, "EXTENT_PROBE_FAILED %s errno=%d\n", error.what(), errno);
        return 1;
    }
}
