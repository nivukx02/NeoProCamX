// Execute on the Android target. Byte-contract/safety tests, not camera cadence evidence.
#include "npv10_range10_codec.h"
#include <algorithm>
#include <cstdio>
#include <stdexcept>
#include <vector>
#include <sys/mman.h>
#include <unistd.h>

namespace {
using Bytes = std::vector<std::uint8_t>;
namespace codec = neoproc::npv10::lossless;
void check(bool okay, const char* reason) { if (!okay) throw std::runtime_error(reason); }

Bytes independentPack(const std::vector<unsigned>& words) {
    Bytes output((words.size() * 10u + 7u) / 8u, 0u);
    for (std::size_t i = 0u; i < words.size(); ++i) {
        for (unsigned bit = 0u; bit < 10u; ++bit) {
            const auto position = i * 10u + bit;
            output[position / 8u] |= static_cast<std::uint8_t>(((words[i] >> bit) & 1u) << (position & 7u));
        }
    }
    return output;
}

Bytes independentRange(const std::vector<unsigned>& words) {
    Bytes output;
    for (std::size_t first = 0; first < words.size(); first += 64u) {
        const auto count = std::min<std::size_t>(64u, words.size() - first);
        for (std::size_t lane = 0; lane < 2u; ++lane) {
            unsigned low = 1023u, high = 0u;
            std::size_t size = 0;
            for (std::size_t i = lane; i < count; i += 2u) {
                low = std::min(low, words[first + i]);
                high = std::max(high, words[first + i]);
                ++size;
            }
            if (size == 0) low = high = 0;
            unsigned bits = 0;
            while ((high - low) >> bits) ++bits;
            output.push_back(static_cast<std::uint8_t>(low));
            output.push_back(static_cast<std::uint8_t>((low | (bits << 10u)) >> 8u));
            const auto base = output.size();
            output.resize(base + (size * bits + 7u) / 8u, 0);
            std::size_t residual = 0;
            for (std::size_t i = lane; i < count; i += 2u, ++residual) {
                for (unsigned bit = 0; bit < bits; ++bit) {
                    const auto position = residual * bits + bit;
                    output[base + position / 8u] |= static_cast<std::uint8_t>(
                        (((words[first + i] - low) >> bit) & 1u) << (position % 8u));
                }
            }
        }
    }
    return output;
}

void roundTrip(std::size_t count, unsigned bits) {
    const unsigned mask = (1u << bits) - 1u;
    std::vector<unsigned> words(count);
    for (std::size_t i = 0; i < count; ++i) words[i] = (static_cast<unsigned>(i * 17u) & mask);
    if (count >= 4u) { words[0] = 0; words[1] = 0; words[2] = mask; words[3] = mask; }
    const Bytes packed = independentPack(words);
    const auto capacity = packed.size() + ((count + 63u) / 64u) * 6u;
    Bytes encoded(capacity + 64u, 0xa7u);
    const auto bytes = codec::encodeRange10(packed.data(), count, encoded.data(), capacity);
    check(bytes > 0 && bytes <= capacity, "Range encode failed");
    const auto reference = independentRange(words);
    check(bytes == reference.size() && std::equal(reference.begin(), reference.end(), encoded.begin()),
          "Range bytes differ from independent wire-format reference");
    check(std::all_of(encoded.begin() + capacity, encoded.end(),
        [](std::uint8_t v) { return v == 0xa7u; }), "Encode overwrote guard");
    Bytes decoded(packed.size() + 64u, 0xa7u);
    check(codec::decodeRange10(encoded.data(), bytes, count, decoded.data(), packed.size()), "Range decode failed");
    check(std::equal(packed.begin(), packed.end(), decoded.begin()), "Source code changed");
    check(std::all_of(decoded.begin() + packed.size(), decoded.end(),
        [](std::uint8_t v) { return v == 0xa7u; }), "Decode overwrote guard");
    for (std::size_t cut = 0; cut < bytes; ++cut) {
        check(!codec::decodeRange10(encoded.data(), cut, count, decoded.data(), packed.size()), "Truncation accepted");
    }
    std::printf("PASS range_bits=%u samples=%zu bytes=%zu all_truncations_rejected\n", bits, count, bytes);
}

void allLaneWidthsAndOrigins() {
    std::size_t cases = 0;
    std::uint32_t seed = 0x961fc537u;
    for (unsigned evenBits = 0; evenBits <= 10; ++evenBits) {
        for (unsigned oddBits = 0; oddBits <= 10; ++oddBits) {
            for (unsigned pattern = 0; pattern < 5; ++pattern) {
                for (const auto count : {64u, 65u, 127u, 128u, 129u, 1024u}) {
                    std::vector<unsigned> words(count);
                    for (std::size_t i = 0; i < count; ++i) {
                        const auto bits = (i & 1u) ? oddBits : evenBits;
                        const unsigned mask = (1u << bits) - 1u;
                        seed ^= seed << 13u; seed ^= seed >> 17u; seed ^= seed << 5u;
                        const unsigned origin = pattern == 0 ? 0u : pattern == 1 ? 1023u - mask :
                            (1023u - mask) / 2u;
                        const unsigned code = pattern == 3 ? ((i % 64u) < 2u ? 0u : mask) : seed & mask;
                        words[i] = origin + code;
                    }
                    const auto packed = independentPack(words);
                    const auto reference = independentRange(words);
                    Bytes output(reference.size() + 17u, 0xa7u);
                    const auto bytes = codec::encodeRange10(packed.data(), count,
                        output.data() + 1u, reference.size());
                    check(bytes == reference.size() && std::equal(reference.begin(), reference.end(),
                        output.begin() + 1u), "SIMD mixed lanes/origins/un-aligned wire bytes differ");
                    check(output.front() == 0xa7u && std::all_of(output.begin() + bytes + 1u,
                        output.end(), [](auto v) { return v == 0xa7u; }), "SIMD output guard changed");
                    Bytes decoded(packed.size());
                    check(codec::decodeRange10(output.data() + 1u, bytes, count,
                        decoded.data(), decoded.size()) && decoded == packed, "Mixed-lane source changed");
                    check(codec::encodeRange10(packed.data(), count, output.data() + 1u,
                        reference.size() - 1u) == 0u, "Short output capacity accepted");
                    ++cases;
                }
            }
        }
    }
    std::printf("PASS independent_mixed_lane_widths_origins_unaligned_capacity cases=%zu\n", cases);
}

void malformedHeaders() {
    Bytes output(3, 0);
    for (const auto& source : {Bytes{255, 7, 1, 0, 0}, Bytes{0, 4, 128, 0, 0},
                              Bytes{0, 64, 0, 0}, Bytes{0, 44, 0, 0}}) {
        check(!codec::decodeRange10(source.data(), source.size(), 2, output.data(), output.size()),
              "Overflow/padding/reserved/width header accepted");
    }
    const Bytes emptyLane{0, 0, 1, 0};
    check(!codec::decodeRange10(emptyLane.data(), emptyLane.size(), 1, output.data(), 2), "Nonempty absent lane accepted");
    std::puts("PASS invalid_headers_overflow_padding_empty_lane");
}

void malformedCompleteBlockOrigins() {
    for (unsigned bits = 1u; bits <= 10u; ++bits) {
        const unsigned mask = (1u << bits) - 1u;
        std::vector<unsigned> words(64u, 1023u - mask);
        words[62] = words[63] = 1023u;
        const auto reference = independentRange(words);
        for (unsigned lane = 0u; lane < 2u; ++lane) {
            Bytes corrupt = reference;
            const auto offset = lane * (2u + 4u * bits);
            const unsigned header = (1024u - mask) | (bits << 10u);
            corrupt[offset] = static_cast<std::uint8_t>(header);
            corrupt[offset + 1u] = static_cast<std::uint8_t>(header >> 8u);
            Bytes output(80u + 16u, 0xa7u);
            check(!codec::decodeRange10(corrupt.data(), corrupt.size(), words.size(),
                output.data() + 1u, 80u), "Complete SIMD block accepted sensor-code overflow");
            check(output.front() == 0xa7u && std::all_of(output.begin() + 81u,
                output.end(), [](auto value) { return value == 0xa7u; }),
                "Rejected SIMD packet wrote beyond its destination");
        }
    }
    std::puts("PASS full_block_corrupt_origins_both_lanes_all_widths cases=20");
}

void protectedCompleteBlockDestinations() {
    const auto page = static_cast<std::size_t>(::sysconf(_SC_PAGESIZE));
    check(page >= 4096u, "Invalid page geometry");
    auto* memory = static_cast<std::uint8_t*>(::mmap(nullptr, page * 2u,
        PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0));
    check(memory != MAP_FAILED, "Decode mmap failed");
    check(::mprotect(memory + page, page, PROT_NONE) == 0, "Decode mprotect failed");
    for (unsigned evenBits = 0u; evenBits <= 10u; ++evenBits) {
        for (unsigned oddBits = 0u; oddBits <= 10u; ++oddBits) {
            std::vector<unsigned> words(64u);
            for (std::size_t i = 0u; i < words.size(); ++i) {
                const unsigned mask = (1u << ((i & 1u) ? oddBits : evenBits)) - 1u;
                words[i] = 1023u - mask + (static_cast<unsigned>(i * 7u) & mask);
            }
            const auto expected = independentPack(words);
            const auto reference = independentRange(words);
            auto* output = memory + page - expected.size();
            check(codec::decodeRange10(reference.data(), reference.size(), words.size(),
                output, expected.size()), "Guarded complete-block decode failed");
            check(std::equal(expected.begin(), expected.end(), output),
                "Guarded complete-block codes changed");
            auto* source = memory + page - reference.size();
            std::copy(reference.begin(), reference.end(), source);
            Bytes decoded(expected.size());
            check(codec::decodeRange10(source, reference.size(), words.size(), decoded.data(),
                decoded.size()) && decoded == expected, "Guarded nonzero complete lane overread");
        }
    }
    check(::munmap(memory, page * 2u) == 0, "Decode munmap failed");
    std::puts("PASS protected_complete_blocks_all_lane_widths cases=121");
}

void protectedPageTail() {
    const auto page = static_cast<std::size_t>(::sysconf(_SC_PAGESIZE));
    check(page >= 4096u, "Invalid page geometry");
    auto* memory = static_cast<std::uint8_t*>(::mmap(nullptr, page * 2u,
        PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0));
    check(memory != MAP_FAILED, "mmap failed");
    check(::mprotect(memory + page, page, PROT_NONE) == 0, "mprotect failed");
    for (std::size_t count = 1; count <= 65; ++count) {
        const auto bytes = (count * 10u + 7u) / 8u;
        auto* packed = memory + page - bytes;
        std::fill_n(packed, bytes, 0);
        Bytes encoded(bytes + 12u);
        const auto result = codec::encodeRange10(packed, count, encoded.data(), encoded.size());
        check(result > 0, "Guarded exact-tail encode failed");
        const Bytes saved(encoded.begin(), encoded.begin() + result);
        auto* guarded = memory + page - result;
        std::copy(saved.begin(), saved.end(), guarded);
        Bytes decoded(bytes);
        check(codec::decodeRange10(guarded, result, count, decoded.data(), decoded.size()), "Guarded decode failed");
        check(std::all_of(decoded.begin(), decoded.end(), [](auto v) { return v == 0; }), "Guarded code changed");
    }
    check(::munmap(memory, page * 2u) == 0, "munmap failed");
    std::puts("PASS protected_page_tails_1_through_65");
}

void protectedCompleteBlockEncoders() {
    const auto page = static_cast<std::size_t>(::sysconf(_SC_PAGESIZE));
    check(page >= 4096u, "Invalid encoder page geometry");
    auto* memory = static_cast<std::uint8_t*>(::mmap(nullptr, page * 2u,
        PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0));
    check(memory != MAP_FAILED, "Encoder mmap failed");
    check(::mprotect(memory + page, page, PROT_NONE) == 0, "Encoder mprotect failed");
    std::size_t cases = 0u;
    for (unsigned evenBits = 0u; evenBits <= 10u; ++evenBits) {
        for (unsigned oddBits = 0u; oddBits <= 10u; ++oddBits) {
            for (unsigned originKind = 0u; originKind < 3u; ++originKind) {
                std::vector<unsigned> words(64u);
                for (std::size_t i = 0u; i < words.size(); ++i) {
                    const unsigned bits = (i & 1u) ? oddBits : evenBits;
                    const unsigned mask = (1u << bits) - 1u;
                    const unsigned origin = originKind == 0u ? 0u :
                        originKind == 1u ? 1023u - mask : (1023u - mask) / 2u;
                    const unsigned residual = i < 2u ? 0u : i < 4u ? mask :
                        static_cast<unsigned>(i * 29u) & mask;
                    words[i] = origin + residual;
                }
                const auto packed = independentPack(words);
                const auto reference = independentRange(words);
                auto* output = memory + page - reference.size();
                std::fill_n(memory, page, 0xa7u);
                check(codec::encodeRange10(packed.data(), words.size(), output,
                    reference.size()) == reference.size(), "Guarded encoder rejected exact capacity");
                check(std::equal(reference.begin(), reference.end(), output),
                    "Guarded encoder changed the independent range bytes");
                check(*(output - 1u) == 0xa7u, "Guarded encoder wrote before output");
                Bytes decoded(packed.size());
                check(codec::decodeRange10(output, reference.size(), words.size(),
                    decoded.data(), decoded.size()) && decoded == packed,
                    "Guarded encoder changed decoded sensor codes");
                check(codec::encodeRange10(packed.data(), words.size(), output + 1u,
                    reference.size() - 1u) == 0u, "Guarded encoder accepted short capacity");
                ++cases;
            }
        }
    }
    check(::munmap(memory, page * 2u) == 0, "Encoder munmap failed");
    std::printf("PASS protected_encode_all_widths_origins_exact_capacity cases=%zu\n", cases);
}

#if defined(__aarch64__) && !defined(NEO_NPV10_FORCE_SCALAR_SPLIT) && \
    !defined(NEO_NPV10_FORCE_LEGACY_RANGE) && defined(__BYTE_ORDER__) && \
    __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
void wordPairLoadEveryCodeAndPosition() {
    std::size_t cases = 0u;
    for (unsigned position = 0u; position < 16u; ++position) {
        for (unsigned code = 0u; code < 1024u; ++code) {
            std::vector<unsigned> words(16u, 1023u);
            words[position] = code;
            const auto packed = independentPack(words);
            for (unsigned offset = 0u; offset < 16u; ++offset) {
                Bytes unaligned(packed.size() + offset);
                std::copy(packed.begin(), packed.end(), unaligned.begin() + offset);
                codec::Range10NeonLane even{}, odd{};
                const unsigned quarter = (position + offset) & 3u;
                codec::range10LoadNeonWordPairs(unaligned.data() + offset, even, odd, quarter);
                alignas(16) std::uint16_t evenCodes[8], oddCodes[8];
                vst1q_u16(evenCodes, even.values[quarter]);
                vst1q_u16(oddCodes, odd.values[quarter]);
                for (unsigned lane = 0u; lane < 8u; ++lane) {
                    check(evenCodes[lane] == words[lane * 2u] &&
                        oddCodes[lane] == words[lane * 2u + 1u],
                        "Word-pair loader changed, swapped or truncated a ten-bit code");
                }
                ++cases;
            }
        }
    }
    const auto page = static_cast<std::size_t>(::sysconf(_SC_PAGESIZE));
    check(page >= 4096u, "Invalid word-pair protected-page geometry");
    auto* memory = static_cast<std::uint8_t*>(::mmap(nullptr, page * 2u,
        PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0));
    check(memory != MAP_FAILED, "Word-pair mmap failed");
    check(::mprotect(memory + page, page, PROT_NONE) == 0, "Word-pair mprotect failed");
    for (unsigned bit = 0u; bit < 160u; ++bit) {
        auto* packed = memory + page - 20u;
        std::fill_n(packed, 20u, 0u);
        packed[bit / 8u] = static_cast<std::uint8_t>(1u << (bit & 7u));
        codec::Range10NeonLane even{}, odd{};
        codec::range10LoadNeonWordPairs(packed, even, odd, 3u);
        alignas(16) std::uint16_t evenCodes[8], oddCodes[8];
        vst1q_u16(evenCodes, even.values[3]);
        vst1q_u16(oddCodes, odd.values[3]);
        for (unsigned sample = 0u; sample < 16u; ++sample) {
            const auto actual = (sample & 1u) ? oddCodes[sample / 2u] : evenCodes[sample / 2u];
            check(actual == (sample == bit / 10u ? (1u << (bit % 10u)) : 0u),
                "Protected word-pair loader moved a source bit");
        }
    }
    check(::munmap(memory, page * 2u) == 0, "Word-pair munmap failed");
    std::printf("PASS word_pair_all_codes_positions_alignments cases=%zu protected_source_bits=160\n", cases);
}

template<unsigned Bits>
void wholeNarrowLaneReference(std::size_t& cases) {
    constexpr unsigned mask = (1u << Bits) - 1u;
    for (const unsigned origin : {0u, (1023u - mask) / 2u, 1023u - mask}) {
        for (unsigned pattern = 0u; pattern < 16u; ++pattern) {
            alignas(16) std::uint16_t values[32];
            Bytes reference(4u * Bits, 0u);
            for (unsigned sample = 0u; sample < 32u; ++sample) {
                const auto residual = sample < 2u ? sample * mask :
                    (sample * 37u + pattern * 19u) & mask;
                values[sample] = static_cast<std::uint16_t>(origin + residual);
                for (unsigned bit = 0u; bit < Bits; ++bit) {
                    const auto position = sample * Bits + bit;
                    reference[position / 8u] |= static_cast<std::uint8_t>(
                        ((residual >> bit) & 1u) << (position & 7u));
                }
            }
            codec::Range10NeonLane lane;
            for (unsigned group = 0u; group < 4u; ++group)
                lane.values[group] = vld1q_u16(values + group * 8u);
            for (unsigned offset = 1u; offset <= 16u; ++offset) {
                Bytes output(65u, 0xa7u);
                codec::range10StoreNeonWholeNarrowLane<Bits>(lane,
                    vdupq_n_u16(static_cast<std::uint16_t>(origin)), output.data() + offset);
                check(std::equal(reference.begin(), reference.end(), output.begin() + offset),
                    "Whole SIMD lane differs from independent residual bits");
                check(std::all_of(output.begin(), output.begin() + offset,
                    [](auto value) { return value == 0xa7u; }) &&
                    std::all_of(output.begin() + offset + reference.size(), output.end(),
                    [](auto value) { return value == 0xa7u; }),
                    "Whole SIMD lane exceeded its exact unaligned output");
                ++cases;
            }
        }
    }
}

void allWholeNarrowLanes() {
    std::size_t cases = 0u;
    wholeNarrowLaneReference<1u>(cases); wholeNarrowLaneReference<2u>(cases);
    wholeNarrowLaneReference<3u>(cases); wholeNarrowLaneReference<4u>(cases);
    wholeNarrowLaneReference<5u>(cases); wholeNarrowLaneReference<6u>(cases);
    wholeNarrowLaneReference<7u>(cases); wholeNarrowLaneReference<8u>(cases);
    std::printf("PASS whole_narrow_lane_independent_bits_all_alignments cases=%zu\n", cases);
}

template<unsigned Bits>
void wholeSmallLaneWalkingBits(std::size_t& cases) {
    static_assert(Bits >= 1u && Bits <= 7u);
    constexpr unsigned mask = (1u << Bits) - 1u;
    // Periodic residual patterns alone can miss swapped equal-valued lanes. Walk each
    // individual source bit through all 32 positions, including every vector boundary.
    for (const unsigned origin : {0u, 1023u - mask}) {
        for (unsigned sample = 0u; sample < 32u; ++sample) {
            for (unsigned bit = 0u; bit < Bits; ++bit) {
                alignas(16) std::uint16_t values[32];
                std::fill(std::begin(values), std::end(values),
                    static_cast<std::uint16_t>(origin));
                values[sample] = static_cast<std::uint16_t>(origin + (1u << bit));
                Bytes reference(Bits * 4u, 0u);
                const unsigned position = sample * Bits + bit;
                reference[position / 8u] = static_cast<std::uint8_t>(1u << (position & 7u));
                codec::Range10NeonLane lane;
                for (unsigned group = 0u; group < 4u; ++group)
                    lane.values[group] = vld1q_u16(values + group * 8u);
                for (unsigned offset = 1u; offset <= 16u; ++offset) {
                    Bytes output(65u, 0xa7u);
                    codec::range10StoreNeonWholeNarrowLane<Bits>(lane,
                        vdupq_n_u16(static_cast<std::uint16_t>(origin)), output.data() + offset);
                    check(std::equal(reference.begin(), reference.end(), output.begin() + offset),
                        "Small SIMD lane moved, duplicated or lost a source bit");
                    check(std::all_of(output.begin(), output.begin() + offset,
                        [](auto value) { return value == 0xa7u; }) &&
                        std::all_of(output.begin() + offset + reference.size(), output.end(),
                        [](auto value) { return value == 0xa7u; }),
                        "Small SIMD lane exceeded its exact walking-bit output");
                    ++cases;
                }
            }
        }
    }
}

void allWholeSmallLaneWalkingBits() {
    std::size_t cases = 0u;
    wholeSmallLaneWalkingBits<1u>(cases);
    wholeSmallLaneWalkingBits<2u>(cases);
    wholeSmallLaneWalkingBits<3u>(cases);
    wholeSmallLaneWalkingBits<4u>(cases);
    wholeSmallLaneWalkingBits<5u>(cases);
    wholeSmallLaneWalkingBits<6u>(cases);
    wholeSmallLaneWalkingBits<7u>(cases);
    std::printf("PASS small_lane_each_source_bit_all_alignments cases=%zu\n", cases);
}

template<unsigned Bits>
void compactLaneAdjacentPairs(std::size_t& cases) {
    constexpr unsigned mask = (1u << Bits) - 1u;
    // Shift/OR without field selection corrupts overlapping low bits. Exhaust
    // every pair, placing it across each of the 8/16/32/64-bit merge boundaries.
    for (unsigned left = 0u; left <= mask; ++left) {
        for (unsigned right = 0u; right <= mask; ++right) {
            for (const unsigned boundary : {1u, 3u, 7u, 15u, 23u, 31u}) {
                alignas(16) std::uint16_t values[32];
                const auto origin = static_cast<std::uint16_t>(1023u - mask);
                std::fill(std::begin(values), std::end(values), origin);
                values[boundary - 1u] += left;
                values[boundary] += right;
                Bytes reference(4u * Bits, 0u);
                for (unsigned sample = 0u; sample < 32u; ++sample) {
                    for (unsigned bit = 0u; bit < Bits; ++bit) {
                        const unsigned position = sample * Bits + bit;
                        reference[position / 8u] |= static_cast<std::uint8_t>(
                            (((values[sample] - origin) >> bit) & 1u) << (position & 7u));
                    }
                }
                codec::Range10NeonLane lane;
                for (unsigned group = 0u; group < 4u; ++group)
                    lane.values[group] = vld1q_u16(values + group * 8u);
                Bytes output(4u * Bits + 2u, 0xa7u);
                codec::range10StoreNeonWholeNarrowLane<Bits>(lane,
                    vdupq_n_u16(origin), output.data() + 1u);
                check(std::equal(reference.begin(), reference.end(), output.begin() + 1u),
                    "Compact SIMD pair lost or mixed adjacent source fields");
                check(output.front() == 0xa7u && output.back() == 0xa7u,
                    "Compact SIMD pair exceeded exact output span");
                ++cases;
            }
        }
    }
}

void allCompactLaneAdjacentPairs() {
    std::size_t cases = 0u;
    compactLaneAdjacentPairs<3u>(cases);
    compactLaneAdjacentPairs<5u>(cases);
    compactLaneAdjacentPairs<6u>(cases);
    compactLaneAdjacentPairs<7u>(cases);
    std::printf("PASS compact_lane_all_adjacent_pairs_and_merge_boundaries cases=%zu\n", cases);
}
#endif
}

int main() {
    try {
        for (const auto count : {1u, 2u, 3u, 4u, 7u, 8u, 15u, 16u, 31u, 32u, 33u, 63u, 64u, 65u, 129u})
            for (unsigned bits = 0u; bits <= 10u; ++bits) roundTrip(count, bits);
        malformedHeaders();
        malformedCompleteBlockOrigins();
        protectedCompleteBlockDestinations();
        protectedPageTail();
        allLaneWidthsAndOrigins();
        protectedCompleteBlockEncoders();
#if defined(__aarch64__) && !defined(NEO_NPV10_FORCE_SCALAR_SPLIT) && \
    !defined(NEO_NPV10_FORCE_LEGACY_RANGE) && defined(__BYTE_ORDER__) && \
    __BYTE_ORDER__ == __ORDER_LITTLE_ENDIAN__
        allWholeNarrowLanes();
        allWholeSmallLaneWalkingBits();
        wordPairLoadEveryCodeAndPosition();
        allCompactLaneAdjacentPairs();
#endif
        std::puts("PASS range10_native_contract_and_guards NOT_CAMERA_FPS");
        return 0;
    } catch (const std::exception& error) {
        std::fprintf(stderr, "FAIL range10_native_contract: %s\n", error.what());
        return 1;
    }
}
