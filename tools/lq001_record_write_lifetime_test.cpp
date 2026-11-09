#include "../app/src/main/cpp/npv10_record_write_lifetime.h"

#include <array>
#include <cstdio>
#include <cstdlib>
#include <sys/stat.h>
#include <unistd.h>

namespace {
struct FakeIo {
    static inline std::uint64_t hint = 0;
    static inline int getCalls = 0;
    static inline int setCalls = 0;
    static inline int getError = 0;
    static inline int setError = 0;
    static inline int interruptedReads = 0;
    static inline int interruptedWrites = 0;
    static inline bool ignore = false;
    static int get(int, std::uint64_t* out) noexcept {
        ++getCalls;
        if (interruptedReads-- > 0) { errno = EINTR; return -1; }
        if (getError != 0) { errno = getError; return -1; }
        *out = hint;
        return 0;
    }
    static int set(int, const std::uint64_t* value) noexcept {
        ++setCalls;
        if (interruptedWrites-- > 0) { errno = EINTR; return -1; }
        if (setError != 0) { errno = setError; return -1; }
        if (!ignore) hint = *value;
        return 0;
    }
    static void reset() {
        hint = 0; getCalls = 0; setCalls = 0; getError = 0; setError = 0;
        interruptedReads = 0; interruptedWrites = 0; ignore = false;
    }
};
void require(bool good, const char* what) {
    if (!good) { std::fprintf(stderr, "FAIL %s\n", what); std::exit(1); }
}
using Hint = neoproc::npv10::RecordWriteLifetime<FakeIo>;
void contracts() {
    FakeIo::reset(); errno = ENOSPC;
    const Hint active(7);
    require(active.applied && active.attempted && active.error == 0 &&
        active.previous == 0 && active.observed == 4 && FakeIo::getCalls == 2 &&
        FakeIo::setCalls == 1 && errno == ENOSPC, "verified admission/errno preservation");
    for (std::uint64_t previous : {1u, 2u, 3u, 4u, 5u, 99u}) {
        FakeIo::reset(); FakeIo::hint = previous;
        const Hint retained(7);
        require(!retained.attempted && !retained.applied && retained.error == 0 &&
            retained.observed == previous && FakeIo::setCalls == 0, "explicit hint preserved");
    }
    FakeIo::reset();
    const Hint invalid(-1);
    require(invalid.error == EBADF && !invalid.attempted && FakeIo::getCalls == 0,
        "invalid descriptor has no syscall");
    for (int error : {EINVAL, ENOSYS, EOPNOTSUPP, EPERM, EIO}) {
        FakeIo::reset(); FakeIo::getError = error;
        const Hint noRead(7);
        require(!noRead.attempted && !noRead.applied && noRead.error == error &&
            FakeIo::setCalls == 0, "read rejection preserved");
        FakeIo::reset(); FakeIo::setError = error;
        const Hint noWrite(7);
        require(noWrite.attempted && !noWrite.applied && noWrite.error == error &&
            FakeIo::hint == 0, "set rejection preserved");
    }
    FakeIo::reset(); FakeIo::ignore = true;
    const Hint ignored(7);
    require(!ignored.applied && ignored.error == EIO && ignored.observed == 0,
        "ignored hint is not successful admission");
    FakeIo::reset(); FakeIo::interruptedReads = 2; FakeIo::interruptedWrites = 2;
    const Hint interrupted(7);
    require(interrupted.applied && FakeIo::getCalls == 4 && FakeIo::setCalls == 3,
        "interrupted syscall retries");
    std::puts("WRITE_LIFETIME_CONTRACTS_PASS cases=20");
}
} // namespace

int main(int argc, char** argv) {
    contracts();
    if (argc == 2) {
        // mkstemp owns exactly this scratch file; never open or remove an existing recording.
        std::array<char, 256> path{};
        const int length = std::snprintf(path.data(), path.size(), "%s/camx-write-life-XXXXXX", argv[1]);
        require(length > 0 && static_cast<std::size_t>(length) < path.size(), "temporary path bounds");
        const int fd = ::mkstemp(path.data());
        require(fd >= 0, "temporary owned file creation");
        const neoproc::npv10::RecordWriteLifetime<> actual(fd);
        std::printf("PHYSICAL_WRITE_LIFETIME attempted=%d applied=%d previous=%llu observed=%llu errno=%d\n",
            actual.attempted, actual.applied, static_cast<unsigned long long>(actual.previous),
            static_cast<unsigned long long>(actual.observed), actual.error);
        require(::close(fd) == 0, "temporary file close");
        require(::unlink(path.data()) == 0, "temporary owned file removal");
    }
}
