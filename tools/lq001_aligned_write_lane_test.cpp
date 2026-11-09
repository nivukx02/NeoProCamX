// Native ownership/failure regressions. These are not camera-FPS or image-quality evidence.
#include "../app/src/main/cpp/npv10_aligned_write_lane.h"

#include <atomic>
#include <chrono>
#include <cstdio>
#include <future>
#include <stdexcept>
#include <vector>

namespace {
using Lane = neoproc::npv10::AlignedWriteLane;
using namespace std::chrono_literals;

void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

struct GatedWriter {
    std::mutex mutex;
    std::condition_variable changed;
    bool released = false;
    bool fail = false;
    unsigned entered = 0;
    std::vector<unsigned> order;
    static bool write(void* context, const std::uint8_t* bytes, std::size_t size,
                      std::int64_t offset) noexcept {
        auto& self = *static_cast<GatedWriter*>(context);
        std::unique_lock<std::mutex> lock(self.mutex);
        ++self.entered;
        self.changed.notify_all();
        self.changed.wait(lock, [&self] { return self.released; });
        if (self.fail) { errno = EIO; return false; }
        for (std::size_t index = 0; index < size; ++index) {
            if (bytes[index] != static_cast<std::uint8_t>(offset / 4096)) {
                errno = EILSEQ;
                return false;
            }
        }
        self.order.push_back(static_cast<unsigned>(offset));
        return true;
    }
    void waitForEntry() {
        std::unique_lock<std::mutex> lock(mutex);
        require(changed.wait_for(lock, 2s, [this] { return entered != 0; }),
            "I/O worker did not enter its submitted task");
    }
    void release() {
        std::lock_guard<std::mutex> lock(mutex);
        released = true;
        changed.notify_all();
    }
};

void overlappingPreparationAndOrderedDrain() {
    std::array<std::uint8_t, 4096> first{}, second{};
    first.fill(1);
    GatedWriter writer;
    Lane lane;
    require(lane.start(first.data(), second.data(), first.size(), &writer, GatedWriter::write),
        "Initial lane admission failed");
    require(lane.submit(first.data(), first.size(), 4096), "First submit failed");
    writer.waitForEntry();
    auto* next = lane.acquire();
    require(next == second.data(), "Producer acquired a buffer still owned by I/O");
    std::fill_n(next, second.size(), std::uint8_t{2});
    require(lane.submit(next, second.size(), 8192), "Second bounded submit failed");
    auto third = std::async(std::launch::async, [&lane] { return lane.acquire(); });
    const bool bounded = third.wait_for(20ms) == std::future_status::timeout;
    writer.release();
    require(third.get() != nullptr, "Retired buffer did not become available");
    require(bounded, "The two-buffer ownership ceiling was exceeded");
    require(lane.drain() && lane.stop(), "Complete ordered writes did not drain");
    require(writer.order == std::vector<unsigned>{4096, 8192}, "Record chunks reordered/corrupted");
}

void terminalFailureDiscardsQueuedChunksAndWakesProducer() {
    std::array<std::uint8_t, 4096> first{}, second{};
    GatedWriter writer;
    writer.fail = true;
    Lane lane;
    require(lane.start(first.data(), second.data(), first.size(), &writer, GatedWriter::write),
        "Initial failure-case admission failed");
    require(lane.submit(first.data(), first.size(), 4096), "Failure-case first submit failed");
    writer.waitForEntry();
    require(lane.submit(lane.acquire(), second.size(), 8192), "Failure-case second submit failed");
    auto waiter = std::async(std::launch::async, [&lane] { return lane.acquire(); });
    writer.release();
    require(waiter.get() == nullptr, "Fatal I/O error returned a usable pixel buffer");
    require(!lane.drain() && !lane.stop() && lane.failureErrno() == EIO,
        "Worker failure was hidden during drain/close");
    require(writer.entered == 1 && writer.order.empty(), "I/O continued after terminal failure");
}

void stopJoinsBeforeBufferAndDescriptorRetirement() {
    std::array<std::uint8_t, 4096> first{}, second{};
    first.fill(3);
    GatedWriter writer;
    Lane lane;
    require(lane.start(first.data(), second.data(), first.size(), &writer, GatedWriter::write),
        "Stop-case admission failed");
    require(lane.submit(first.data(), first.size(), 12288), "Stop-case submit failed");
    writer.waitForEntry();
    auto stopped = std::async(std::launch::async, [&lane] { return lane.stop(); });
    const bool waited = stopped.wait_for(20ms) == std::future_status::timeout;
    writer.release();
    require(stopped.get() && waited, "Close returned before outstanding I/O completed");
    require(writer.order == std::vector<unsigned>{12288}, "Close lost its pending write");
    require(!lane.submit(first.data(), first.size(), 12288), "Submission accepted after close");
}
} // namespace

int main() {
    try {
        for (unsigned iteration = 0; iteration < 32; ++iteration) {
            overlappingPreparationAndOrderedDrain();
            terminalFailureDiscardsQueuedChunksAndWakesProducer();
            stopJoinsBeforeBufferAndDescriptorRetirement();
        }
        std::printf("{\"scope\":\"native_chunk_ownership_not_camera_fps\",\"passed\":96,\"failed\":0}\n");
        return 0;
    } catch (const std::exception& error) {
        std::fprintf(stderr, "ALIGNED_WRITE_LANE_TEST_FAILED: %s\n", error.what());
        return 1;
    }
}
