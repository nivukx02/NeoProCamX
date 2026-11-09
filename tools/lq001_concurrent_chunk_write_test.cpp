// Executed as native code on Android. Ownership/failure evidence, never camera-FPS evidence.
#include "../app/src/main/cpp/npv10_aligned_write_lane.h"
#include <algorithm>
#include <chrono>
#include <cstdio>
#include <future>
#include <stdexcept>
#include <vector>

namespace {
using Lane = neoproc::npv10::ConcurrentAlignedWriteLane;
using namespace std::chrono_literals;
void require(bool value, const char* reason) {
    if (!value) throw std::runtime_error(reason);
}

struct TwoWrites {
    std::mutex mutex;
    std::condition_variable changed;
    std::array<bool, 2> entered{}, released{}, finished{};
    bool failFirst = false;
    std::array<std::uint8_t, 8192> bytes{};
    static bool write(void* context, const std::uint8_t* source, std::size_t count,
                      std::int64_t offset) noexcept {
        auto& self = *static_cast<TwoWrites*>(context);
        const auto index = offset == 0 ? 0u : 1u;
        std::unique_lock<std::mutex> lock(self.mutex);
        self.entered[index] = true;
        self.changed.notify_all();
        self.changed.wait(lock, [&] { return self.released[index]; });
        const bool valid = count == 4096u && offset == static_cast<std::int64_t>(index * 4096u) &&
            std::all_of(source, source + count, [index](auto value) { return value == index + 1u; });
        const bool fail = !valid || (index == 0u && self.failFirst);
        if (!fail) std::copy_n(source, count, self.bytes.begin() + index * 4096u);
        self.finished[index] = true;
        self.changed.notify_all();
        if (fail) errno = EIO;
        return !fail;
    }
    void awaitBoth() {
        std::unique_lock<std::mutex> lock(mutex);
        const bool okay = changed.wait_for(lock, 2s, [&] { return entered[0] && entered[1]; });
        if (!okay) { released.fill(true); changed.notify_all(); }
        require(okay, "Two disjoint writes did not overlap on separate I/O workers");
    }
    void release(unsigned index) {
        std::lock_guard<std::mutex> lock(mutex);
        released[index] = true;
        changed.notify_all();
    }
    void releaseBoth() {
        std::lock_guard<std::mutex> lock(mutex);
        released.fill(true);
        changed.notify_all();
    }
    void awaitFinished(unsigned index) {
        std::unique_lock<std::mutex> lock(mutex);
        const bool okay = changed.wait_for(lock, 2s, [&] { return finished[index]; });
        if (!okay) { released.fill(true); changed.notify_all(); }
        require(okay, "Released positional write did not finish");
    }
};

struct ReleaseOnExit {
    TwoWrites& io;
    ~ReleaseOnExit() { io.releaseBoth(); }
};

void disjointOutOfOrderCompletionIsBitExactAndBounded() {
    std::array<std::uint8_t, 4096> first{}, second{};
    first.fill(1); second.fill(2);
    TwoWrites io;
    Lane lane;
    ReleaseOnExit release{io};
    require(lane.start(first.data(), second.data(), first.size(), &io, TwoWrites::write), "Start failed");
    require(lane.submit(first.data(), first.size(), 0), "First disjoint submit failed");
    require(lane.acquire() == second.data(), "Incorrect second-buffer ownership");
    require(lane.submit(second.data(), second.size(), 4096), "Second disjoint submit failed");
    io.awaitBoth();
    auto third = std::async(std::launch::async, [&] { return lane.acquire(); });
    const bool bounded = third.wait_for(20ms) == std::future_status::timeout;
    auto drained = std::async(std::launch::async, [&] { return lane.drain(); });
    io.release(1); // Later file range deliberately completes first.
    io.awaitFinished(1);
    const bool stillPending = drained.wait_for(20ms) == std::future_status::timeout;
    io.release(0);
    auto* retired = third.get();
    require(drained.get() && lane.stop(), "Disjoint writes failed to drain");
    require(bounded && stillPending, "Record completed or staging reused before I/O retirement");
    require(retired == first.data() || retired == second.data(), "Unexpected third allocation");
    require(std::all_of(io.bytes.begin(), io.bytes.begin() + 4096, [](auto b) { return b == 1; }) &&
        std::all_of(io.bytes.begin() + 4096, io.bytes.end(), [](auto b) { return b == 2; }),
        "Out-of-order disjoint completion changed bytes");
}

void fatalWriteStillJoinsItsConcurrentReader() {
    std::array<std::uint8_t, 4096> first{}, second{};
    first.fill(1); second.fill(2);
    TwoWrites io;
    io.failFirst = true;
    Lane lane;
    ReleaseOnExit release{io};
    require(lane.start(first.data(), second.data(), first.size(), &io, TwoWrites::write), "Start failed");
    require(lane.submit(first.data(), first.size(), 0), "First submit failed");
    require(lane.submit(lane.acquire(), second.size(), 4096), "Second submit failed");
    io.awaitBoth();
    io.release(0);
    io.awaitFinished(0);
    auto producer = std::async(std::launch::async, [&] { return lane.acquire(); });
    require(producer.get() == nullptr, "I/O failure returned a reusable producer buffer");
    auto stopped = std::async(std::launch::async, [&] { return lane.stop(); });
    const bool joined = stopped.wait_for(20ms) == std::future_status::timeout;
    io.release(1);
    require(!stopped.get() && joined && lane.failureErrno() == EIO,
        "Fatal close hid an error or released storage before both writes retired");
    require(!lane.submit(first.data(), first.size(), 0), "Failure allowed more I/O");
}

void overlappingPhysicalRangesAreRejected() {
    std::array<std::uint8_t, 4096> first{}, second{};
    first.fill(1); second.fill(2);
    TwoWrites io;
    Lane lane;
    ReleaseOnExit release{io};
    require(lane.start(first.data(), second.data(), first.size(), &io, TwoWrites::write), "Start failed");
    require(lane.submit(first.data(), first.size(), 0), "First submit failed");
    require(!lane.submit(lane.acquire(), second.size(), 2048), "Overlapping direct writes accepted");
    io.releaseBoth();
    require(!lane.stop() && lane.failureErrno() == EINVAL, "Overlap failure was hidden");
}

void overflowAndRestartRemainTerminal() {
    std::array<std::uint8_t, 4096> first{}, second{};
    TwoWrites io;
    Lane lane;
    require(lane.start(first.data(), second.data(), first.size(), &io, TwoWrites::write), "Start failed");
    require(!lane.submit(first.data(), first.size(), INT64_MAX - 1), "Overflow accepted");
    require(!lane.stop() && lane.failureErrno() == EINVAL, "Overflow failure hidden");
    require(!lane.start(first.data(), second.data(), first.size(), &io, TwoWrites::write),
        "Closed lane restarted with potentially stale buffer ownership");
}
} // namespace

int main() {
    try {
        for (unsigned iteration = 0u; iteration < 32u; ++iteration) {
            disjointOutOfOrderCompletionIsBitExactAndBounded();
            fatalWriteStillJoinsItsConcurrentReader();
            overlappingPhysicalRangesAreRejected();
            overflowAndRestartRemainTerminal();
        }
        std::puts("{\"scope\":\"native_disjoint_chunk_ownership_not_camera_fps\",\"passed\":128,\"failed\":0}");
        return 0;
    } catch (const std::exception& error) {
        std::fprintf(stderr, "CONCURRENT_CHUNK_TEST_FAILED: %s\n", error.what());
        return 1;
    }
}
