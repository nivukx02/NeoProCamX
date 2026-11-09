#include "../app/src/main/cpp/npv10_writeback_policy.h"

#include <cstdio>
#include <cstdlib>
#include <limits>

using Policy = neoproc::npv10::IncrementalWritebackPolicy;

static int checks = 0;
static void check(bool result, const char* label) {
    ++checks;
    if (!result) {
        std::fprintf(stderr, "FAIL: %s\n", label);
        std::exit(1);
    }
}

int main() {
    check(!Policy::suppressForRealtime(true, 24LL * 1024 * 1024), "large compressed slots enabled");
    check(Policy::suppressForRealtime(false, 24LL * 1024 * 1024), "legacy large slots preserved");
    check(!Policy::suppressForRealtime(false, Policy::kLegacySuppressionPayloadBytes - 1), "legacy below boundary");
    check(Policy::suppressForRealtime(false, Policy::kLegacySuppressionPayloadBytes), "legacy exact boundary");
    check(Policy::targetBytes(true) == 16LL * 1024 * 1024, "compressed byte wake threshold");
    check(Policy::targetBytes(false) == 64LL * 1024 * 1024, "legacy byte wake threshold");
    check(Policy::boundedEnd(4096, 4096) == 4096, "empty range");
    check(Policy::boundedEnd(4096, 2048) == 4096, "reversed range");
    check(Policy::boundedEnd(-1, 4096) == -1, "negative offset");
    check(Policy::boundedEnd(4096, 4097) == 4097, "one byte range");
    const auto maximum = std::numeric_limits<std::int64_t>::max();
    check(Policy::boundedEnd(maximum - 2, maximum) == maximum, "near maximum offset");
    check(Policy::boundedEnd(0, maximum) == Policy::kMaximumRequestBytes, "maximum backlog bounded");
    constexpr std::int64_t start = 4121;
    constexpr std::int64_t end = start + 93LL * 1024 * 1024 + 11;
    std::int64_t covered = start;
    int requests = 0;
    while (covered < end) {
        const auto next = Policy::boundedEnd(covered, end);
        check(next > covered && next <= end, "monotonic committed-range coverage");
        check(next - covered <= Policy::kMaximumRequestBytes, "every syscall independently bounded");
        covered = next;
        ++requests;
    }
    check(covered == end && requests == 6, "coalesced backlog fully covered without gaps");
    std::printf("PASS %d checks; policy only, not physical FPS or storage throughput proof\n", checks);
    return 0;
}
