#include "npv10_inflight_io.h"

#include <atomic>
#include <chrono>
#include <cstdio>
#include <future>
#include <thread>
#include <vector>

using neoproc::npv10::ScopedUnlockedIo;
using namespace std::chrono_literals;

namespace {
int passed = 0;
int failed = 0;
void check(bool ok, const char* label) {
    std::printf("%s %s\n", ok ? "PASS" : "FAIL", label);
    ok ? ++passed : ++failed;
}
}

int main() {
    std::mutex mutex;
    std::condition_variable changed;
    std::size_t active = 0;

    {
        std::unique_lock<std::mutex> lock(mutex);
        bool unlocked = false;
        {
            ScopedUnlockedIo probe(lock, active, changed);
            unlocked = !lock.owns_lock();
        }
        check(unlocked && lock.owns_lock() && active == 0, "exact_once_relock_and_retirement");
    }

    {
        std::unique_lock<std::mutex> lock(mutex);
        bool caught = false;
        try {
            ScopedUnlockedIo probe(lock, active, changed);
            throw std::runtime_error("injected extent error");
        } catch (const std::runtime_error&) { caught = true; }
        check(caught && lock.owns_lock() && active == 0, "exception_cannot_leak_io_ownership");
    }

    {
        std::unique_lock<std::mutex> lock(mutex, std::defer_lock);
        bool caught = false;
        try { ScopedUnlockedIo probe(lock, active, changed); }
        catch (const std::logic_error&) { caught = true; }
        check(caught && active == 0 && !lock.owns_lock(), "unowned_lock_is_rejected");
    }

    {
        std::unique_lock<std::mutex> lock(mutex);
        active = std::numeric_limits<std::size_t>::max();
        bool caught = false;
        try { ScopedUnlockedIo probe(lock, active, changed); }
        catch (const std::overflow_error&) { caught = true; }
        check(caught && lock.owns_lock() && active == std::numeric_limits<std::size_t>::max(),
              "overflow_does_not_wrap_descriptor_ownership");
        active = 0;
    }

    {
        std::promise<void> entered;
        std::promise<void> release;
        auto releaseFuture = release.get_future().share();
        auto probe = std::async(std::launch::async, [&] {
            std::unique_lock<std::mutex> lock(mutex);
            ScopedUnlockedIo io(lock, active, changed);
            entered.set_value();
            releaseFuture.wait();
        });
        entered.get_future().wait();
        auto commit = std::async(std::launch::async, [&] {
            std::lock_guard<std::mutex> lock(mutex);
            return active == 1;
        });
        const bool commitProgress = commit.wait_for(2s) == std::future_status::ready;
        auto shutdown = std::async(std::launch::async, [&] {
            std::unique_lock<std::mutex> lock(mutex);
            changed.wait(lock, [&] { return active == 0; });
        });
        const bool shutdownRetained = shutdown.wait_for(20ms) == std::future_status::timeout;
        release.set_value();
        probe.get();
        const bool commitSawLiveIo = commit.get();
        shutdown.get();
        check(commitProgress && commitSawLiveIo, "writer_can_retire_during_delayed_filesystem_probe");
        check(shutdownRetained && active == 0, "shutdown_waits_for_actual_io_completion");
    }

    {
        std::atomic<int> callbacks{0};
        std::vector<std::thread> workers;
        for (int worker = 0; worker < 4; ++worker) {
            workers.emplace_back([&] {
                for (int iteration = 0; iteration < 500; ++iteration) {
                    std::unique_lock<std::mutex> lock(mutex);
                    ScopedUnlockedIo io(lock, active, changed);
                    callbacks.fetch_add(1, std::memory_order_relaxed);
                    std::this_thread::yield();
                }
            });
        }
        for (auto& worker : workers) worker.join();
        check(active == 0 && callbacks.load() == 2000, "parallel_probe_stress_exact_once_2000_operations");
    }

    std::printf("SUMMARY passed=%d failed=%d\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
