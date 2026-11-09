#include "../app/src/main/cpp/npv10_split10_simd.h"

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <sys/mman.h>
#include <unistd.h>
#include <vector>

namespace {
using Bytes = std::vector<std::uint8_t>;
std::size_t checks = 0;

void check(bool condition, const char* message) {
    ++checks;
    if (!condition) {
        std::fprintf(stderr, "FAIL: %s\n", message);
        std::exit(1);
    }
}

void verify(std::size_t samples, unsigned pattern, bool pageGuard) {
    const std::size_t bytes = (samples * 10 + 7) / 8;
    Bytes packed(bytes, 0);
    Bytes expected(bytes, 0xa7);
    Bytes output(bytes + 32, 0xa7);
#if defined(__aarch64__) && !defined(NEO_NPV10_FORCE_SCALAR_SPLIT)
    const auto prefix = samples / 32 * 32;
#else
    const std::size_t prefix = 0;
#endif
    if (prefix > 0) std::fill(expected.begin() + samples, expected.begin() + samples + prefix / 4, 0);
    std::uint32_t random = 0x903f5271u;
    for (std::size_t sample = 0; sample < samples; ++sample) {
        random ^= random << 13;
        random ^= random >> 17;
        random ^= random << 5;
        const auto value = static_cast<std::uint16_t>(pattern == 0 ? 0 : pattern == 1 ? 1023 :
            pattern == 2 ? (sample & 1023) : random & 1023);
        // Independent bit-at-a-time encoder avoids sharing the SIMD implementation's shuffles.
        for (unsigned bit = 0; bit < 10; ++bit) {
            const auto index = sample * 10 + bit;
            packed[index / 8] |= static_cast<std::uint8_t>(((value >> bit) & 1u) << (index % 8));
        }
        if (sample < prefix) {
            expected[sample] = static_cast<std::uint8_t>(value);
            expected[samples + sample / 4] |= static_cast<std::uint8_t>((value >> 8) << (sample % 4 * 2));
        }
    }
    void* mapping = MAP_FAILED;
    std::size_t mappingBytes = 0;
    const std::uint8_t* source = packed.data();
    if (pageGuard) {
        const auto page = static_cast<std::size_t>(sysconf(_SC_PAGESIZE));
        const auto pages = (bytes + page - 1) / page;
        mappingBytes = (pages + 1) * page;
        mapping = mmap(nullptr, mappingBytes, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        check(mapping != MAP_FAILED, "guarded allocation");
        auto* guard = static_cast<std::uint8_t*>(mapping) + pages * page;
        check(mprotect(guard, page, PROT_NONE) == 0, "source end guard page");
        auto* start = guard - bytes;
        std::memcpy(start, packed.data(), bytes);
        source = start;
    }
    const auto processed = neoproc::npv10::lossless::splitPackedTenBitSimdPrefix(source, samples, output.data());
    check(processed == prefix, "prefix length");
    check(std::equal(expected.begin(), expected.end(), output.begin()), "exact samples and untouched scalar tail");
    check(std::all_of(output.begin() + bytes, output.end(), [](auto value) { return value == 0xa7; }), "destination guard");
    if (mapping != MAP_FAILED) check(munmap(mapping, mappingBytes) == 0, "release source guard");
}
}  // namespace

int main() {
    for (std::size_t samples = 0; samples < 258; ++samples) {
        for (unsigned pattern = 0; pattern < 4; ++pattern) verify(samples, pattern, false);
    }
    for (std::size_t samples : {31u, 32u, 33u, 63u, 64u, 65u, 255u, 256u, 257u, 262143u, 262144u, 262145u}) {
        for (unsigned pattern = 0; pattern < 4; ++pattern) verify(samples, pattern, true);
    }
    std::printf("PASS %zu SIMD permutation checks; includes protected-page chunk boundaries\n", checks);
    return 0;
}
