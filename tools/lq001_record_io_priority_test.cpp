// Tests the real scheduling-policy implementation. No camera or FPS simulation.
#include "../app/src/main/cpp/npv10_record_io_priority.h"
#include <cstdio>
#include <initializer_list>
#include <stdexcept>

namespace {
void check(bool condition, const char* reason) {
    if (!condition) throw std::runtime_error(reason);
}
struct PriorityIo {
    static inline int priority = 0, capabilityError = 0, getError = 0, setError = 0;
    static inline int reads = 0, writes = 0, probes = 0;
    static inline bool ignoreSet = false;
    static void reset() {
        priority = capabilityError = getError = setError = reads = writes = probes = 0;
        ignoreSet = false;
    }
    static int capability() { ++probes; return capabilityError; }
    static long getCurrent() {
        ++reads;
        if (getError != 0) { errno = getError; return -1; }
        return priority;
    }
    static long setCurrent(int value) {
        ++writes;
        if (setError != 0) { errno = setError; return -1; }
        if (!ignoreSet) priority = value;
        return 0;
    }
};
using Priority = neoproc::npv10::ScopedRecordIoPriority<PriorityIo>;
unsigned passed = 0;
template<class Test> void run(const char* name, Test&& test) {
    PriorityIo::reset();
    test();
    std::printf("PASS %s\n", name);
    ++passed;
}
} // namespace

int main() {
    try {
        static_assert(Priority::kRequested == (2 << 13));
        run("normal_writer_gets_best_effort_not_realtime_and_restores_once", [] {
            { Priority priority; check(priority.applied(), "hint was not verified");
              check(priority.previous() == 0 && priority.observed() == (2 << 13), "wrong priority");
              check(priority.restore() && priority.restore(), "restoration failed"); }
            check(PriorityIo::priority == 0 && PriorityIo::writes == 2, "restore was not exact once");
        });
        for (int previous : {(2 << 13) | 7, 3 << 13}) {
            run("owned_lower_priority_is_restored_exactly", [previous] {
                PriorityIo::priority = previous;
                { Priority priority; check(priority.applied(), "foreground hint not applied"); }
                check(PriorityIo::priority == previous, "prior scheduling class changed");
            });
        }
        for (int previous : {1 << 13, (1 << 13) | 7, 2 << 13}) {
            run("existing_realtime_or_equal_owner_is_never_downgraded", [previous] {
                PriorityIo::priority = previous;
                { Priority priority; check(!priority.applied() && priority.error() == 0, "existing owner changed"); }
                check(PriorityIo::priority == previous && PriorityIo::writes == 0, "unneeded priority write");
            });
        }
        for (int error : {EOPNOTSUPP, EPERM, EAGAIN, ETIMEDOUT}) {
            run("unsupported_sandbox_is_not_called_in_live_writer", [error] {
                PriorityIo::capabilityError = error;
                { Priority priority; check(priority.error() == error && !priority.applied(), "probe denial hidden"); }
                check(PriorityIo::reads == 0 && PriorityIo::writes == 0, "denied syscall called");
            });
        }
        run("read_failure_performs_no_priority_write", [] {
            PriorityIo::getError = ESRCH;
            { Priority priority; check(priority.error() == ESRCH, "read error lost"); }
            check(PriorityIo::writes == 0, "failed read changed scheduler");
        });
        run("set_failure_is_reported_not_certified", [] {
            PriorityIo::setError = EPERM;
            { Priority priority; check(priority.error() == EPERM && !priority.applied(), "set denial hidden"); }
            check(PriorityIo::priority == 0 && PriorityIo::writes == 1, "denied hint retried");
        });
        run("readback_mismatch_is_not_certified", [] {
            PriorityIo::ignoreSet = true;
            { Priority priority; check(priority.error() == EIO && !priority.applied(), "ignored hint certified"); }
            check(PriorityIo::priority == 0, "mismatch changed priority");
        });
        run("platform_override_is_preserved_during_retirement", [] {
            { Priority priority; check(priority.applied(), "hint missing"); PriorityIo::priority = 3 << 13; }
            check(PriorityIo::priority == (3 << 13) && PriorityIo::writes == 1, "platform override undone");
        });
        run("temporary_restore_failure_remains_visible_and_recoverable", [] {
            Priority priority;
            PriorityIo::setError = EPERM;
            check(!priority.restore() && priority.error() == EPERM, "restore denial hidden");
            PriorityIo::setError = 0;
            check(priority.restore() && PriorityIo::priority == 0, "restore retry lost ownership");
        });
        run("disabled_control_is_observational_only", [] {
            { Priority priority(false); check(!priority.applied(), "disabled hint applied"); }
            check(PriorityIo::probes == 0 && PriorityIo::reads == 0 && PriorityIo::writes == 0,
                "baseline scheduling was touched");
        });
        std::printf("RECORD_IO_PRIORITY_PASS cases=%u scope=scheduling_policy_not_camera_fps\n", passed);
        return 0;
    } catch (const std::exception& failure) {
        std::fprintf(stderr, "RECORD_IO_PRIORITY_FAIL cases=%u reason=%s\n", passed, failure.what());
        return 1;
    }
}
