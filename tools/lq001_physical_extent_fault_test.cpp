// Fault-injection of the exact production reservation state machine; not camera evidence.
#include "neo_qa_hypergrid/probes/rejected_f2fs/npv10_physical_extent_reservation.h"
#include <cstdio>
#include <stdexcept>
#include <vector>

namespace {
unsigned assertions = 0;
void check(bool value, const char* message) {
    ++assertions;
    if (!value) throw std::runtime_error(message);
}
struct FaultIo {
    static inline bool f2fs = true, regular = true;
    static inline unsigned long fileFlags = 0;
    static inline int statError = 0, fsError = 0, flagsErrorAt = 0, flagReads = 0;
    static inline int pinError = 0, releaseError = 0, allocationError = 0;
    static inline bool ignorePin = false, ignoreRelease = false, allocationInterrupted = false;
    static inline unsigned calls = 0;
    static inline std::vector<std::uint32_t> pins;
    static inline std::vector<std::pair<std::int64_t, std::int64_t>> allocations;
    static void reset() {
        f2fs = regular = true; fileFlags = 0; statError = fsError = flagsErrorAt = flagReads = 0;
        pinError = releaseError = allocationError = 0;
        ignorePin = ignoreRelease = allocationInterrupted = false;
        calls = 0; pins.clear(); allocations.clear();
    }
    static int fail(int error) { errno = error; return -1; }
    static int stat(int, struct stat64* output) {
        ++calls;
        if (statError) return fail(statError);
        output->st_mode = regular ? S_IFREG | 0600 : S_IFDIR | 0700;
        return 0;
    }
    static int filesystem(int, struct statfs* output) {
        ++calls;
        if (fsError) return fail(fsError);
        output->f_type = f2fs ? 0xf2f52010UL : 0xef53;
        return 0;
    }
    static int flags(int, unsigned long* output) {
        ++calls;
        if (++flagReads == flagsErrorAt) return fail(EACCES);
        *output = fileFlags;
        return 0;
    }
    static int pin(int, std::uint32_t value) {
        ++calls; pins.push_back(value);
        if (value) {
            if (pinError) return fail(pinError);
            if (!ignorePin) fileFlags |= FS_NOCOW_FL;
        } else {
            if (releaseError) return fail(releaseError);
            if (!ignoreRelease) fileFlags &= ~FS_NOCOW_FL;
        }
        return 0;
    }
    static int allocate(int, std::int64_t offset, std::int64_t count) {
        ++calls; allocations.emplace_back(offset, count);
        if (allocationInterrupted) { allocationInterrupted = false; return EINTR; }
        return allocationError;
    }
};
using Reservation = neoproc::npv10::PhysicalExtentReservation<FaultIo>;
auto allocate() { return Reservation::allocate(3, 64LL * 1024 * 1024, 32LL * 1024 * 1024); }

void test() {
    FaultIo::reset();
    auto result = allocate();
    check(result.error == 0 && result.materialized, "physical success");
    check(FaultIo::pins == std::vector<std::uint32_t>({1, 0}), "exact owned pin lifecycle");
    check(FaultIo::fileFlags == 0 && FaultIo::allocations.size() == 1, "restore and single allocation");
    check(FaultIo::allocations[0].first == 64LL * 1024 * 1024 &&
        FaultIo::allocations[0].second == 32LL * 1024 * 1024, "range unchanged");

    for (int error : {ENOTTY, EOPNOTSUPP, ENOSYS, EINVAL, EPERM, EACCES, EAGAIN}) {
        FaultIo::reset(); FaultIo::pinError = error;
        result = allocate();
        check(result.error == 0 && !result.materialized && result.capabilityError == error, "truthful unsupported fallback");
        check(FaultIo::pins.size() == 1 && FaultIo::allocations.size() == 1, "never release unowned pin");
    }
    for (int error : {EIO, ENOSPC, EDQUOT, EROFS}) {
        FaultIo::reset(); FaultIo::pinError = error;
        result = allocate();
        check(result.error == error && FaultIo::allocations.empty(), "fatal pin error must not fall back");
        FaultIo::reset(); FaultIo::allocationError = error;
        result = allocate();
        check(result.error == error && !result.materialized && FaultIo::pins.size() == 2 &&
            FaultIo::fileFlags == 0 && FaultIo::allocations.size() == 1, "allocation failure restores pin and never retries");
        FaultIo::reset(); FaultIo::releaseError = error;
        result = allocate();
        check(result.error == error && result.releaseError == error && !result.materialized,
            "release failure must poison success");
    }
    FaultIo::reset(); FaultIo::fileFlags = FS_NOCOW_FL | FS_NOATIME_FL;
    result = allocate();
    check(result.materialized && FaultIo::pins.empty() &&
        FaultIo::fileFlags == (FS_NOCOW_FL | FS_NOATIME_FL), "existing pin belongs to its owner");
    FaultIo::reset(); FaultIo::fileFlags = FS_COMPR_FL | FS_NOATIME_FL;
    result = allocate();
    check(!result.materialized && result.error == 0 && FaultIo::pins.empty() &&
        FaultIo::fileFlags == (FS_COMPR_FL | FS_NOATIME_FL), "filesystem compression never disabled");
    FaultIo::reset(); FaultIo::fileFlags = FS_NOATIME_FL;
    result = allocate();
    check(result.materialized && FaultIo::fileFlags == FS_NOATIME_FL, "unrelated flags preserved");
    FaultIo::reset(); FaultIo::f2fs = false;
    result = allocate();
    check(!result.materialized && result.error == 0 && FaultIo::pins.empty() &&
        FaultIo::flagReads == 0 && FaultIo::allocations.size() == 1, "other filesystems preserve legacy behavior");
    for (int failureAt : {1, 2, 3}) {
        FaultIo::reset(); FaultIo::flagsErrorAt = failureAt;
        result = allocate();
        if (failureAt == 1) check(result.error == 0 && !result.materialized &&
            FaultIo::pins.empty(), "unsupported read-flags before mutation");
        else check(result.error == EACCES && !result.materialized && FaultIo::pins.size() == 2 &&
            FaultIo::fileFlags == 0, "verification failure never skips restoration");
    }
    FaultIo::reset(); FaultIo::ignorePin = true;
    result = allocate();
    check(result.error == EIO && FaultIo::allocations.empty() && FaultIo::pins.size() == 2,
        "successful ioctl without enabled pin is not proof");
    FaultIo::reset(); FaultIo::ignoreRelease = true;
    result = allocate();
    check(result.error == EIO && result.releaseError == EIO && !result.materialized,
        "successful ioctl without release is fatal");
    FaultIo::reset(); FaultIo::allocationInterrupted = true;
    result = allocate();
    check(result.materialized && FaultIo::allocations.size() == 2 && FaultIo::pins.size() == 2,
        "EINTR retains pin ownership until final completion");
    FaultIo::reset(); FaultIo::allocationError = ENOSPC; FaultIo::releaseError = EIO;
    result = allocate();
    check(result.error == ENOSPC && result.releaseError == EIO, "preserve both primary and cleanup errors");
    for (int stage : {0, 1, 2}) {
        FaultIo::reset();
        if (stage == 0) FaultIo::regular = false;
        if (stage == 1) FaultIo::statError = EBADF;
        if (stage == 2) FaultIo::fsError = EIO;
        result = allocate();
        check(result.error != 0 && FaultIo::pins.empty() && FaultIo::allocations.empty(), "invalid targets not mutated");
    }
    FaultIo::reset();
    check(Reservation::allocate(-1, 0, 1).error == EINVAL, "negative descriptor");
    check(Reservation::allocate(3, -1, 1).error == EINVAL, "negative offset");
    check(Reservation::allocate(3, 0, 0).error == EINVAL, "empty range");
    check(Reservation::allocate(3, INT64_MAX, 1).error == EINVAL, "overflow rejected");
    check(FaultIo::calls == 0, "invalid range rejected before I/O");
}
} // namespace
int main() {
    try { test(); std::printf("PHYSICAL_EXTENT_FAULT_PASS assertions=%u\n", assertions); return 0; }
    catch (const std::exception& error) { std::fprintf(stderr, "PHYSICAL_EXTENT_FAULT_FAIL %s\n", error.what()); return 1; }
}
