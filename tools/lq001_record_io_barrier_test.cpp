// Native concurrency regression of production primitives. Not camera/FPS evidence.
#include "../app/src/main/cpp/npv10_record_io_barrier.h"
#include "../app/src/main/cpp/npv10_inflight_io.h"
#include <atomic>
#include <chrono>
#include <cstdio>
#include <future>
#include <stdexcept>
#include <thread>
#include <vector>

using namespace std::chrono_literals;
using neoproc::npv10::RecordIoBarrier;
using neoproc::npv10::ScopedUnlockedIo;

namespace {
unsigned passed = 0;
void require(bool condition, const char* name) {
    if (!condition) throw std::runtime_error(name);
    ++passed;
    std::printf("PASS %s\n", name);
}

void exclusion(bool writeFirst) {
    RecordIoBarrier barrier;
    auto held = barrier.acquire();
    std::promise<void> entered;
    std::atomic<bool> completed{false};
    auto waiting = std::async(std::launch::async, [&] {
        // Match production: timing begins before waiting for an active flush/record.
        const auto start = std::chrono::steady_clock::now();
        entered.set_value();
        auto owned = barrier.acquire();
        completed.store(true);
        return std::chrono::steady_clock::now() - start;
    });
    entered.get_future().wait();
    const auto blocked = waiting.wait_for(25ms);
    const bool early = completed.load();
    held.unlock();
    const auto elapsed = waiting.get();
    require(blocked == std::future_status::timeout && !early,
        writeFirst ? "force_cannot_overlap_active_record" : "record_cannot_overlap_active_force");
    require(elapsed >= 25ms, "wait_remains_inside_operation_timing");
}

void failedOperationReleasesLock() {
    RecordIoBarrier barrier;
    bool observed = false;
    try {
        auto owned = barrier.acquire();
        throw std::runtime_error("injected filesystem failure");
    } catch (const std::runtime_error&) { observed = true; }
    auto next = std::async(std::launch::async, [&] { return barrier.acquire().owns_lock(); });
    require(observed && next.get(), "error_not_swallowed_and_barrier_released_exactly_once");
}

void shutdownRetainsDescriptor() {
    RecordIoBarrier barrier;
    std::mutex stateMutex;
    std::condition_variable changed;
    std::size_t active = 0;
    std::promise<void> admitted, beginForce;
    auto forceReady = beginForce.get_future();
    auto force = std::async(std::launch::async, [&] {
        std::unique_lock<std::mutex> state(stateMutex);
        {
            ScopedUnlockedIo lease(state, active, changed);
            admitted.set_value();
            auto owned = barrier.acquire();
            forceReady.wait();
        }
        return active;
    });
    admitted.get_future().wait();
    auto close = std::async(std::launch::async, [&] {
        std::unique_lock<std::mutex> state(stateMutex);
        changed.wait(state, [&] { return active == 0u; });
        return active;
    });
    const bool retained = close.wait_for(25ms) == std::future_status::timeout;
    beginForce.set_value();
    require(force.get() == 0u && close.get() == 0u && retained,
        "shutdown_waits_for_force_and_state_lock_does_not_deadlock");
}

void parallelRequests() {
    RecordIoBarrier barrier;
    std::atomic<unsigned> inside{0}, overlap{0}, completed{0};
    std::future<void> workers[4];
    for (auto& worker : workers) worker = std::async(std::launch::async, [&] {
        for (unsigned i = 0; i < 4096; ++i) {
            auto owned = barrier.acquire();
            if (inside.fetch_add(1u) != 0u) overlap.fetch_add(1u);
            completed.fetch_add(1u);
            if (inside.fetch_sub(1u) != 1u) overlap.fetch_add(1u);
        }
    });
    for (auto& worker : workers) worker.get();
    require(overlap == 0u && inside == 0u && completed == 16384u,
        "all_16384_operations_complete_without_overlap_or_extra_queue");
}

bool waitForAdmissions(RecordIoBarrier& barrier, std::size_t count) {
    const auto limit = std::chrono::steady_clock::now() + 2s;
    while (barrier.pendingAdmissions() != count) {
        if (std::chrono::steady_clock::now() >= limit) return false;
        std::this_thread::yield();
    }
    return true;
}

void queuedMaintenanceCannotBeOvertaken() {
    RecordIoBarrier barrier;
    auto initialWrite = barrier.acquire();
    std::vector<unsigned> order;
    auto allocation = std::async(std::launch::async, [&] {
        auto owned = barrier.acquire(); order.push_back(1u);
    });
    const bool allocationQueued = waitForAdmissions(barrier, 1u);
    auto durability = std::async(std::launch::async, [&] {
        auto owned = barrier.acquire(); order.push_back(2u);
    });
    const bool durabilityQueued = waitForAdmissions(barrier, 2u);
    auto olderWrite = std::async(std::launch::async, [&] {
        auto owned = barrier.acquire(); order.push_back(3u);
    });
    const bool writeQueued = waitForAdmissions(barrier, 3u);
    initialWrite.unlock();
    {
        // The same hot owner immediately re-enters: it must not barge ahead
        // of any of the three already queued operations, irrespective of OS
        // wake order or which core next schedules the waiting caller.
        auto nextWrite = barrier.acquire(); order.push_back(4u);
    }
    allocation.get(); durability.get(); olderWrite.get();
    require(allocationQueued && durabilityQueued && writeQueued &&
        order == std::vector<unsigned>({1u, 2u, 3u, 4u}),
        "older_allocation_force_and_write_cannot_be_starved_by_new_write");
    require(barrier.pendingAdmissions() == 0u,
        "fifo_admission_retains_no_waiters_after_completion");
}

void movedLeaseRetiresExactlyOnce() {
    RecordIoBarrier first, second;
    auto firstOwner = first.acquire();
    auto secondOwner = second.acquire();
    RecordIoBarrier::Lease moved(std::move(firstOwner));
    require(!firstOwner.owns_lock() && moved.owns_lock(), "move_transfers_one_io_owner");
    secondOwner = std::move(moved);
    require(!moved.owns_lock() && secondOwner.owns_lock(), "move_assignment_transfers_io_owner");
    {
        auto reclaimed = second.acquire();
        require(reclaimed.owns_lock(), "move_assignment_releases_previous_barrier");
    }
    secondOwner.unlock();
    secondOwner.unlock(); // A moved/retired lease cannot advance a second ticket.
    auto reclaimed = first.acquire();
    require(reclaimed.owns_lock(), "explicit_unlock_and_destruction_retire_ticket_once");
}
}

int main() {
    try {
        exclusion(true);
        exclusion(false);
        failedOperationReleasesLock();
        shutdownRetainsDescriptor();
        parallelRequests();
        queuedMaintenanceCannotBeOvertaken();
        movedLeaseRetiresExactlyOnce();
        std::printf("RECORD_IO_BARRIER_PASS assertions=%u camera_fps=NOT_TESTED\n", passed);
        return 0;
    } catch (const std::exception& failure) {
        std::fprintf(stderr, "RECORD_IO_BARRIER_FAIL %s\n", failure.what());
        return 1;
    }
}
