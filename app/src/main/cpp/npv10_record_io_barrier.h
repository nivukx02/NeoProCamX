#pragma once

#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <mutex>
#include <stdexcept>
#include <utility>

namespace neoproc::npv10 {

/**
 * Serializes a master's complete record writes, periodic fdatasync and bounded live fallocate,
 * not packing. A second descriptor does not isolate those operations on the same inode.
 * O_DIRECT requests must all retire before the record owner releases this lock. The existing
 * write timer starts BEFORE acquisition, so a delayed flush cannot disappear from write latency.
 * No extra frame queue, deferred acknowledgement, or changed durability interval is introduced.
 */
class RecordIoBarrier final {
public:
    class Lease final {
    public:
        Lease() noexcept = default;
        ~Lease() { unlock(); }
        Lease(const Lease&) = delete;
        Lease& operator=(const Lease&) = delete;
        Lease(Lease&& other) noexcept : owner_(std::exchange(other.owner_, nullptr)) {}
        Lease& operator=(Lease&& other) noexcept {
            if (this != &other) {
                unlock();
                owner_ = std::exchange(other.owner_, nullptr);
            }
            return *this;
        }
        bool owns_lock() const noexcept { return owner_ != nullptr; }
        void unlock() noexcept {
            if (auto* owner = std::exchange(owner_, nullptr)) owner->release();
        }
    private:
        friend class RecordIoBarrier;
        explicit Lease(RecordIoBarrier* owner) noexcept : owner_(owner) {}
        RecordIoBarrier* owner_ = nullptr;
    };

    Lease acquire() {
        std::unique_lock<std::mutex> state(mutex_);
        if (poisoned_) throw std::runtime_error("NPV10 record I/O admission is poisoned");
        // A plain mutex permits the hot writer to reacquire ahead of older
        // fallocate/fdatasync waiters. That can drain a reservation's lead even
        // when each allocation syscall is short. Ticket admission bounds each
        // caller's wait to operations already ahead of it, not future frames.
        // Unsigned wrap is defined; the bounded set of live I/O callers cannot
        // issue an entire uint64_t ticket space before its oldest one retires.
        const auto ticket = issued_++;
        ++waiting_;
        try {
            changed_.wait(state, [this, ticket] { return poisoned_ || ticket == serving_; });
        } catch (...) {
            // A failed wait must not strand a missing ticket and deadlock close.
            // Fail subsequent admissions closed; never continue file I/O after
            // losing synchronization ownership or swallow the original error.
            --waiting_;
            poisoned_ = true;
            changed_.notify_all();
            throw;
        }
        --waiting_;
        if (poisoned_) throw std::runtime_error("NPV10 record I/O admission is poisoned");
        return Lease(this);
    }

    // An observation only: callers cannot reorder or cancel an admitted ticket.
    // Used by deterministic concurrency regression to prove all older callers
    // are waiting before the original writer attempts to enter again.
    std::size_t pendingAdmissions() {
        std::lock_guard<std::mutex> state(mutex_);
        return waiting_;
    }
private:
    void release() noexcept {
        {
            std::lock_guard<std::mutex> state(mutex_);
            ++serving_;
        }
        // There are only the existing writer, reservation and durability
        // callers. Wake them to let the exact next ticket proceed; no spin,
        // new worker, frame buffer, write acknowledgement or durability change.
        changed_.notify_all();
    }
    std::mutex mutex_;
    std::condition_variable changed_;
    std::uint64_t issued_ = 0;
    std::uint64_t serving_ = 0;
    std::size_t waiting_ = 0;
    bool poisoned_ = false;
};

} // namespace neoproc::npv10
