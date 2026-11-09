#pragma once

#include <array>
#include <cerrno>
#include <condition_variable>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <mutex>
#include <thread>
#if defined(__linux__)
#include <pthread.h>
#endif

namespace neoproc::npv10 {

/**
 * Two caller-owned aligned staging buffers, bounded I/O workers, no queued pixel copies.
 * The producer may fill one buffer while the worker reads the other. A submitted buffer cannot
 * be acquired again until its complete write has returned. drain() is mandatory at each record
 * boundary; this is chunk overlap, not an additional frame queue or deferred durability policy.
 */
template<std::size_t ConcurrentWrites = 1u>
class BoundedAlignedWriteLane final {
public:
    static_assert(ConcurrentWrites == 1u || ConcurrentWrites == 2u,
        "Only one or two disjoint positional writes may be outstanding");
    using Write = bool (*)(void*, const std::uint8_t*, std::size_t, std::int64_t) noexcept;
    using WorkerSetup = void (*)() noexcept;
    enum class State { Free, Producer, Queued, Writing };

    BoundedAlignedWriteLane() = default;
    ~BoundedAlignedWriteLane() { (void)stop(); }
    BoundedAlignedWriteLane(const BoundedAlignedWriteLane&) = delete;
    BoundedAlignedWriteLane& operator=(const BoundedAlignedWriteLane&) = delete;

    bool start(std::uint8_t* first, std::uint8_t* second, std::size_t capacity,
               void* context, Write write, WorkerSetup workerSetup = nullptr) noexcept {
        std::unique_lock<std::mutex> admissionLock(mutex_);
        if (first == nullptr || second == nullptr || first == second || capacity == 0u ||
            context == nullptr || write == nullptr || started_ || stopping_ || failure_ != 0)
            return reject(EINVAL);
        buffers_ = {first, second};
        states_ = {State::Producer, State::Free};
        capacity_ = capacity;
        context_ = context;
        write_ = write;
        workerSetup_ = workerSetup;
        try {
            for (auto& worker : workers_) worker = std::thread([this] { work(); });
            started_ = true;
            return true;
        } catch (...) {
            // If the second thread could not start, wake and join the first before its buffers
            // can be released. A partial worker set must never become a silent fallback.
            (void)reject(EAGAIN);
            admissionLock.unlock();
            (void)stop();
            return false;
        }
    }

    bool submit(std::uint8_t* buffer, std::size_t bytes, std::int64_t offset) noexcept {
        std::lock_guard<std::mutex> lock(mutex_);
        const auto slot = indexOf(buffer);
        if (!started_ || stopping_ || failure_ != 0) return false;
        if (slot >= buffers_.size() || states_[slot] != State::Producer || bytes == 0u ||
            bytes > capacity_ || offset < 0 ||
            bytes > static_cast<std::uint64_t>(std::numeric_limits<std::int64_t>::max() - offset) ||
            queued_ >= queue_.size()) return reject(EINVAL);
        const auto end = offset + static_cast<std::int64_t>(bytes);
        for (std::size_t index = 0u; index < states_.size(); ++index) {
            if (states_[index] != State::Queued && states_[index] != State::Writing) continue;
            const auto& pending = ownedRanges_[index];
            const auto pendingEnd = pending.offset + static_cast<std::int64_t>(pending.bytes);
            if (offset < pendingEnd && pending.offset < end) return reject(EINVAL);
        }
        states_[slot] = State::Queued;
        ownedRanges_[slot] = Request{slot, bytes, offset};
        queue_[tail_] = ownedRanges_[slot];
        tail_ = (tail_ + 1u) % queue_.size();
        ++queued_;
        changed_.notify_all();
        return true;
    }

    std::uint8_t* acquire() noexcept {
        std::unique_lock<std::mutex> lock(mutex_);
        changed_.wait(lock, [this] {
            return !started_ || stopping_ || failure_ != 0 ||
                states_[0] == State::Free || states_[1] == State::Free;
        });
        if (!started_ || stopping_ || failure_ != 0) return nullptr;
        for (std::size_t index = 0; index < states_.size(); ++index) {
            if (states_[index] == State::Free) {
                states_[index] = State::Producer;
                return buffers_[index];
            }
        }
        (void)reject(EIO);
        return nullptr;
    }

    bool drain() noexcept {
        std::unique_lock<std::mutex> lock(mutex_);
        changed_.wait(lock, [this] { return queued_ == 0u && activeWrites_ == 0u; });
        return failure_ == 0;
    }

    // The sole producer calls stop, including on errors. Join precedes descriptor/buffer release.
    bool stop() noexcept {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            stopping_ = true;
            changed_.notify_all();
        }
        for (auto& worker : workers_) if (worker.joinable()) worker.join();
        std::lock_guard<std::mutex> lock(mutex_);
        started_ = false;
        return failure_ == 0;
    }

    int failureErrno() const noexcept {
        std::lock_guard<std::mutex> lock(mutex_);
        return failure_;
    }

private:
    struct Request { std::size_t slot = 0u; std::size_t bytes = 0u; std::int64_t offset = 0; };

    std::size_t indexOf(const std::uint8_t* buffer) const noexcept {
        return buffer == buffers_[0] ? 0u : buffer == buffers_[1] ? 1u : buffers_.size();
    }
    // Caller holds mutex_, or is still in single-threaded initial admission.
    bool reject(int error) noexcept {
        if (failure_ == 0) failure_ = error == 0 ? EIO : error;
        changed_.notify_all();
        return false;
    }
    void work() noexcept {
#if defined(__linux__)
        (void)::pthread_setname_np(::pthread_self(), "NPV10-IO");
#endif
        if (workerSetup_ != nullptr) workerSetup_();
        std::unique_lock<std::mutex> lock(mutex_);
        for (;;) {
            changed_.wait(lock, [this] { return stopping_ || failure_ != 0 || queued_ != 0u; });
            if (failure_ != 0) {
                while (queued_ != 0u) {
                    states_[queue_[head_].slot] = State::Free;
                    head_ = (head_ + 1u) % queue_.size();
                    --queued_;
                }
                changed_.notify_all();
                return;
            }
            if (queued_ == 0u) {
                if (stopping_) return;
                continue;
            }
            const Request request = queue_[head_];
            head_ = (head_ + 1u) % queue_.size();
            --queued_;
            if (states_[request.slot] != State::Queued) {
                (void)reject(EIO);
                continue;
            }
            states_[request.slot] = State::Writing;
            ++activeWrites_;
            const auto* buffer = buffers_[request.slot];
            lock.unlock();
            const bool successful = write_(context_, buffer, request.bytes, request.offset);
            const int error = successful ? 0 : (errno == 0 ? EIO : errno);
            lock.lock();
            --activeWrites_;
            states_[request.slot] = State::Free;
            if (!successful) (void)reject(error);
            changed_.notify_all();
        }
    }

    std::array<std::uint8_t*, 2> buffers_{};
    std::array<State, 2> states_{State::Free, State::Free};
    std::array<Request, 2> queue_{};
    std::array<Request, 2> ownedRanges_{};
    std::size_t capacity_ = 0u, head_ = 0u, tail_ = 0u, queued_ = 0u;
    void* context_ = nullptr;
    Write write_ = nullptr;
    WorkerSetup workerSetup_ = nullptr;
    mutable std::mutex mutex_;
    std::condition_variable changed_;
    std::array<std::thread, ConcurrentWrites> workers_;
    std::size_t activeWrites_ = 0u;
    bool started_ = false, stopping_ = false;
    int failure_ = 0;
};

// The original FIFO lane remains the default. Two-lane completion may reorder only disjoint
// chunks within one record: the owning writer must drain before committing or starting the next
// record's shared aligned tail. Both lanes are joined before any descriptor or buffer retirement.
using AlignedWriteLane = BoundedAlignedWriteLane<1u>;
using ConcurrentAlignedWriteLane = BoundedAlignedWriteLane<2u>;

} // namespace neoproc::npv10
