#include "../app/src/main/cpp/egl_image_residency_policy.h"

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <deque>
#include <limits>
#include <unordered_set>

namespace {
using Policy = camx::EglImageResidencyPolicy;

void require(bool condition, const char* description) {
    if (!condition) {
        std::fprintf(stderr, "FAIL: %s\n", description);
        std::exit(1);
    }
}

struct ImportReplay {
    std::unordered_set<std::uint64_t> entries;
    std::deque<std::uint64_t> order;
    std::uint64_t hits = 0;
    std::uint64_t imports = 0;
    std::uint64_t evictions = 0;
    std::size_t maximumResident = 0;

    void submit(std::uint64_t id) {
        if (entries.find(id) != entries.end()) {
            ++hits;
            return;
        }
        entries.insert(id);
        order.push_back(id);
        ++imports;
        while (Policy::needsEviction(order.size())) {
            require(entries.erase(order.front()) == 1, "exactly one eviction owns each import");
            order.pop_front();
            ++evictions;
        }
        maximumResident = std::max(maximumResident, entries.size());
        require(entries.size() == order.size(), "index and import owner order agree");
        require(entries.size() <= Policy::kCapacity, "cache remains bounded");
    }
};
}  // namespace

int main() {
    static_assert(Policy::kCapacity >= Policy::kStrictP010ReaderCeiling);
    require(!Policy::needsEviction(0), "empty cache does not evict");
    require(!Policy::needsEviction(64), "exact capacity does not evict");
    require(Policy::needsEviction(65), "over-capacity evicts");
    require(Policy::needsEviction(std::numeric_limits<std::size_t>::max()), "size arithmetic cannot wrap");
    require(Policy::canAdmitWithoutEviction(63), "identity path admits last free entry");
    require(!Policy::canAdmitWithoutEviction(64), "identity path cannot evict pending receipts");

    for (const std::size_t pool : {std::size_t{8}, std::size_t{16}, std::size_t{32},
                                  std::size_t{55}, std::size_t{64}}) {
        ImportReplay replay;
        for (std::size_t frame = 0; frame < 15'000; ++frame) {
            replay.submit(1 + frame % pool);
        }
        require(replay.imports == pool, "each stable buffer is imported once after warmup");
        require(replay.hits == 15'000 - pool, "every subsequent pool reuse is a cache hit");
        require(replay.evictions == 0, "supported pool never cyclically thrashes");
        require(replay.maximumResident == pool, "cache does not allocate its unused bound");
        std::printf("PASS stable_pool=%zu frames=15000 imports=%llu evictions=%llu\n", pool,
                    static_cast<unsigned long long>(replay.imports),
                    static_cast<unsigned long long>(replay.evictions));
    }

    ImportReplay churn;
    for (std::uint64_t frame = 1; frame <= 100'000; ++frame) churn.submit(frame);
    require(churn.entries.size() == 64, "unbounded producer IDs cannot grow memory");
    require(churn.evictions == 100'000 - 64, "retired IDs release exactly once");
    churn.entries.clear();
    churn.order.clear();
    require(churn.entries.empty() && churn.order.empty(), "teardown retires all handles");
    std::puts("PASS bounded_churn=100000 teardown=empty; policy evidence only, no physical FPS claim");
    return 0;
}
