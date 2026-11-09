#pragma once

#include <condition_variable>
#include <cstddef>
#include <limits>
#include <mutex>
#include <stdexcept>

namespace neoproc::npv10 {

/**
 * Keeps an external I/O operation alive without holding the frame-state mutex.
 *
 * The active count and its completion notification are always protected by that same mutex.
 * The descriptor owner must wait for activeOperations == 0 before closing it. This lets the
 * ordered writer retire frames even when a filesystem metadata query is delayed by allocation,
 * writeback, or thermal scheduling. No frame buffer, reservation, or quality limit is enlarged.
 */
class ScopedUnlockedIo final {
public:
    ScopedUnlockedIo(std::unique_lock<std::mutex>& stateLock,
                     std::size_t& activeOperations,
                     std::condition_variable& stateChanged)
        : stateLock_(stateLock), activeOperations_(activeOperations), stateChanged_(stateChanged) {
        if (!stateLock_.owns_lock()) {
            throw std::logic_error("NPV10 external I/O requires the state lock");
        }
        if (activeOperations_ == std::numeric_limits<std::size_t>::max()) {
            throw std::overflow_error("NPV10 external I/O ownership overflow");
        }
        ++activeOperations_;
        stateLock_.unlock();
    }

    ~ScopedUnlockedIo() {
        stateLock_.lock();
        --activeOperations_;
        stateChanged_.notify_all();
    }

    ScopedUnlockedIo(const ScopedUnlockedIo&) = delete;
    ScopedUnlockedIo& operator=(const ScopedUnlockedIo&) = delete;

private:
    std::unique_lock<std::mutex>& stateLock_;
    std::size_t& activeOperations_;
    std::condition_variable& stateChanged_;
};

}  // namespace neoproc::npv10
