// Real Android syscall/storage diagnostic. It is NOT a recording-FPS or app-compatibility PASS.
// No application backend is changed by this probe. Only its own mkstemp files are written/deleted.
#include <algorithm>
#include <array>
#include <cerrno>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <fcntl.h>
#include <linux/aio_abi.h>
#include <stdexcept>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

namespace {
constexpr std::size_t kAlignment = 16384u;
constexpr std::size_t kChunkBytes = 2u * 1024u * 1024u;
constexpr std::size_t kFileBytes = 512u * 1024u * 1024u;
constexpr unsigned kDepth = 4u;
using Clock = std::chrono::steady_clock;

void check(bool valid, const char* message) {
    if (!valid) throw std::runtime_error(message);
}

struct Buffer {
    void* data = nullptr;
    explicit Buffer(std::size_t bytes) {
        const int result = ::posix_memalign(&data, kAlignment, bytes);
        check(result == 0 && data != nullptr, "aligned allocation failed");
    }
    ~Buffer() { std::free(data); }
    Buffer(const Buffer&) = delete;
    Buffer& operator=(const Buffer&) = delete;
};

struct OwnedFile {
    char name[512]{};
    int fd = -1;
    explicit OwnedFile(const char* directory = "/data/local/tmp") {
        check(directory != nullptr && directory[0] == '/', "absolute QA directory required");
        const int size = std::snprintf(name, sizeof(name), "%s/camx-lq001-native-aio-XXXXXX", directory);
        check(size > 0 && static_cast<std::size_t>(size) < sizeof(name), "QA path too long");
        fd = ::mkstemp(name);
        check(fd >= 0, "exclusive QA file creation failed");
    }
    void prepare() {
        check(::posix_fallocate64(fd, 0, kFileBytes) == 0, "physical reservation failed");
        check(::fsync(fd) == 0, "initial durability failed");
        const int flags = ::fcntl(fd, F_GETFL);
        check(flags >= 0 && ::fcntl(fd, F_SETFL, flags | O_DIRECT) == 0,
            "own-descriptor direct I/O unavailable");
    }
    ~OwnedFile() {
        if (fd >= 0) {
            (void)::close(fd);
            (void)::unlink(name);
        }
    }
    OwnedFile(const OwnedFile&) = delete;
    OwnedFile& operator=(const OwnedFile&) = delete;
};

struct NativeContext {
    aio_context_t handle = 0;
    bool available = false;
    NativeContext() {
        const long result = ::syscall(__NR_io_setup, kDepth, &handle);
        available = result == 0;
        std::printf("NATIVE_AIO_CAPABILITY syscall_result=%ld errno=%d uid=%u "
            "context=sdk_adb_shell app_seccomp_compatibility=NOT_PROVEN\n",
            result, result == 0 ? 0 : errno, static_cast<unsigned>(::getuid()));
        std::fflush(stdout);
    }
    ~NativeContext() {
        // io_destroy retires outstanding kernel readers before any source buffer/file is released.
        if (available) (void)::syscall(__NR_io_destroy, handle);
    }
    NativeContext(const NativeContext&) = delete;
    NativeContext& operator=(const NativeContext&) = delete;
};

void writeAll(int fd, const std::uint8_t* source, std::size_t count, std::int64_t offset) {
    while (count != 0u) {
        const auto result = ::pwrite64(fd, source, count, offset);
        if (result < 0 && errno == EINTR) continue;
        check(result > 0 && static_cast<std::size_t>(result) <= count,
            "synchronous direct write failed");
        // A non-aligned short completion cannot be retried as an unaligned direct-I/O request.
        check(static_cast<std::size_t>(result) == count || result % kAlignment == 0,
            "unaligned synchronous short write");
        source += result;
        count -= static_cast<std::size_t>(result);
        offset += result;
    }
}

void asynchronousGroup(NativeContext& context, int fd, const std::uint8_t* source,
                       std::int64_t offset) {
    std::array<iocb, kDepth> requests{};
    std::array<iocb*, kDepth> addresses{};
    for (unsigned i = 0; i < kDepth; ++i) {
        auto& request = requests[i];
        request.aio_data = i;
        request.aio_lio_opcode = IOCB_CMD_PWRITE;
        request.aio_fildes = fd;
        request.aio_buf = reinterpret_cast<std::uintptr_t>(source);
        request.aio_nbytes = kChunkBytes;
        request.aio_offset = offset + static_cast<std::int64_t>(i) * kChunkBytes;
        addresses[i] = &request;
    }
    unsigned first = 0;
    while (first < kDepth) {
        long submitted;
        do {
            submitted = ::syscall(__NR_io_submit, context.handle, kDepth - first,
                addresses.data() + first);
        } while (submitted < 0 && errno == EINTR);
        check(submitted > 0 && submitted <= static_cast<long>(kDepth - first),
            "native AIO submission failed or made no progress");
        unsigned pending = static_cast<unsigned>(submitted);
        unsigned seen = 0;
        while (pending != 0u) {
            std::array<io_event, kDepth> events{};
            timespec deadline{30, 0};
            const long completed = ::syscall(__NR_io_getevents, context.handle, 1u, pending,
                events.data(), &deadline);
            if (completed < 0 && errno == EINTR) continue;
            check(completed > 0 && completed <= static_cast<long>(pending),
                "native AIO completion failed or timed out");
            for (long event = 0; event < completed; ++event) {
                const auto& value = events[event];
                check(value.data >= first && value.data < first + static_cast<unsigned>(submitted),
                    "completion belongs to a different request");
                const auto index = static_cast<unsigned>(value.data);
                check((seen & (1u << index)) == 0u &&
                    value.obj == reinterpret_cast<std::uintptr_t>(&requests[index]),
                    "duplicate or mismatched completion");
                check(value.res == kChunkBytes && value.res2 == 0,
                    "native AIO short or failed write");
                seen |= 1u << index;
            }
            pending -= static_cast<unsigned>(completed);
        }
        first += static_cast<unsigned>(submitted);
    }
}

double measure(OwnedFile& file, NativeContext& context, const std::uint8_t* source,
               bool asynchronous, unsigned round) {
    const auto started = Clock::now();
    for (std::size_t offset = 0; offset < kFileBytes; offset += kDepth * kChunkBytes) {
        if (asynchronous) asynchronousGroup(context, file.fd, source, offset);
        else for (unsigned i = 0; i < kDepth; ++i)
            writeAll(file.fd, source, kChunkBytes, offset + i * kChunkBytes);
    }
    const auto writesFinished = Clock::now();
    check(::fdatasync(file.fd) == 0, "final data durability failed");
    const double writeMs = std::chrono::duration<double, std::milli>(writesFinished - started).count();
    const double totalMs = std::chrono::duration<double, std::milli>(Clock::now() - started).count();
    std::printf("ACTUAL_STORAGE round=%u backend=%s bytes=%zu write_ms=%.3f "
        "write_and_sync_ms=%.3f bytes_per_second=%.0f scope=not_camera_fps\n",
        round, asynchronous ? "kernel_aio_depth4" : "sync_direct_depth1", kFileBytes,
        writeMs, totalMs, kFileBytes * 1000.0 / totalMs);
    std::fflush(stdout);
    return totalMs;
}

void verify(const OwnedFile& file, const std::uint8_t* source, Buffer& readback) {
    for (std::size_t offset = 0; offset < kFileBytes; offset += kChunkBytes) {
        ssize_t bytes;
        do { bytes = ::pread64(file.fd, readback.data, kChunkBytes, offset); }
        while (bytes < 0 && errno == EINTR);
        check(bytes == kChunkBytes && std::memcmp(source, readback.data, kChunkBytes) == 0,
            "complete physical-file readback differs");
    }
    struct stat64 status{};
    check(::fstat64(file.fd, &status) == 0 && status.st_size == kFileBytes,
        "physical-file extent differs");
}
}  // namespace

int main() {
    try {
        // Lifetime order is deliberate: the context is destroyed before its files or buffers.
        Buffer source(kChunkBytes), readback(kChunkBytes);
        OwnedFile baseline, candidate;
        NativeContext context;
        if (!context.available) return 2;
        baseline.prepare();
        candidate.prepare();
        auto* bytes = static_cast<std::uint8_t*>(source.data);
        std::uint32_t random = 0x37fa520du;
        for (std::size_t i = 0; i < kChunkBytes; ++i) {
            random ^= random << 13u; random ^= random >> 17u; random ^= random << 5u;
            bytes[i] = static_cast<std::uint8_t>(random);
        }
        for (unsigned round = 0; round < 4u; ++round) {
            if ((round & 1u) == 0u) {
                (void)measure(baseline, context, bytes, false, round);
                (void)measure(candidate, context, bytes, true, round);
            } else {
                (void)measure(candidate, context, bytes, true, round);
                (void)measure(baseline, context, bytes, false, round);
            }
            verify(baseline, bytes, readback);
            verify(candidate, bytes, readback);
            std::printf("COMPLETE_READBACK_PASS round=%u bytes_each=%zu\n", round, kFileBytes);
            std::fflush(stdout);
        }
        std::puts("NATIVE_AIO_PROBE_COMPLETE app_seccomp_compatibility=NOT_PROVEN "
            "five_minute_recording=NOT_TESTED production_backend=UNCHANGED");
        return 0;
    } catch (const std::exception& failure) {
        std::fprintf(stderr, "NATIVE_AIO_PROBE_FAILED errno=%d reason=%s\n", errno, failure.what());
        return 1;
    }
}
