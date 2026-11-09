// Executes the application encoder on Android. Not a camera/FPS simulation.
#include "neo_qa_hypergrid/probes/parallel_range/npv10_parallel_range_encoder.h"
#include <atomic>
#include <cstdio>
#include <stdexcept>

namespace {
namespace codec = neoproc::npv10::lossless;
using Bytes = std::vector<std::uint8_t>;
void require(bool value, const char* reason) { if (!value) throw std::runtime_error(reason); }
std::uint32_t crc(const Bytes& bytes) {
    return static_cast<std::uint32_t>(::crc32(0, bytes.data(), static_cast<uInt>(bytes.size())));
}
Bytes makeInput(std::size_t samples, unsigned pattern) {
    Bytes bytes((samples * 10u + 7u) / 8u, 0u);
    std::uint32_t random = 0x8264273u;
    for (std::size_t index = 0; index < samples; ++index) {
        random ^= random << 13u; random ^= random >> 17u; random ^= random << 5u;
        const auto code = pattern == 0 ? 512u : pattern == 1 ? random & 1023u :
            pattern == 2 ? 257u + (random & 31u) :
            static_cast<unsigned>((index / codec::kChunkSamples & 1u) == 0u
                ? (random & 1023u) : 128u + (random & 7u));
        for (unsigned bit = 0; bit < 10u; ++bit) {
            const auto position = index * 10u + bit;
            bytes[position / 8u] |= static_cast<std::uint8_t>(((code >> bit) & 1u) << (position & 7u));
        }
    }
    return bytes;
}

void verify(const Bytes& input, const Bytes& packet, const codec::EncodeResult& result,
            std::size_t samples, std::size_t capacity) {
    require(result.successful && result.storedBytes <= static_cast<std::int64_t>(capacity),
        "Parallel encode failed or exceeded reservation");
    require(std::all_of(packet.begin() + capacity, packet.end(), [](auto b) { return b == 0xa7u; }),
        "Parallel encode wrote beyond its reservation");
    require(result.storedCrc32 == ::crc32(0, packet.data(), static_cast<uInt>(result.storedBytes)),
        "Combined CRC differs from independent full-packet CRC");
    require(result.packedCrc32 == crc(input), "Source CRC changed");
    Bytes decoded(samples * 2u + 16u, 0xa7u);
    require(codec::decodePacketToP010(packet.data(), result.storedBytes, decoded.data(), samples * 2u),
        "Joined packet cannot be decoded with the established decoder");
    for (std::size_t sample = 0; sample < samples; ++sample) {
        unsigned code = 0;
        for (unsigned bit = 0; bit < 10u; ++bit) {
            const auto position = sample * 10u + bit;
            code |= ((input[position / 8u] >> (position & 7u)) & 1u) << bit;
        }
        require(static_cast<unsigned>(decoded[sample * 2u] |
            (decoded[sample * 2u + 1u] << 8u)) == (code << 6u),
            "Parallel encoder changed a ten-bit sample");
    }
    require(std::all_of(decoded.begin() + samples * 2u, decoded.end(), [](auto b) { return b == 0xa7u; }),
        "Decoder overwrote exact capacity");
    require(!codec::decodePacketToP010(packet.data(), result.storedBytes - 1,
        decoded.data(), samples * 2u), "Truncated joined packet accepted");
    Bytes corrupt(packet);
    corrupt[28] ^= 1u;
    require(!codec::decodePacketToP010(corrupt.data(), result.storedBytes,
        decoded.data(), samples * 2u), "Corrupt full-frame source CRC accepted");
}

void shapesAndRepeatedSession() {
    std::size_t count = 0;
    for (const std::size_t samples : {1048576u, 1048577u, 1048579u, 1310793u, 2097152u}) {
        const auto size = (samples * 10u + 7u) / 8u;
        const auto capacity = codec::maximumStoredBytes(size, samples);
        codec::ParallelRangeEncoder encoder;
        require(encoder.initialize(size, samples, capacity), "Valid parallel geometry rejected");
        require(!encoder.initialize(size, samples, capacity), "Duplicate initialization accepted");
        for (unsigned pattern = 0; pattern < 4u; ++pattern) {
            const auto input = makeInput(samples, pattern);
            for (const int effort : {1, 64, 1024}) {
                Bytes packet(static_cast<std::size_t>(capacity) + 32u, 0xa7u);
                std::copy(input.begin(), input.end(), packet.begin());
                codec::EncodeResult result;
                require(encoder.tryEncode(packet.data(), size, samples, capacity, crc(input), effort, result),
                    "Idle helper declined a valid frame");
                verify(input, packet, result, samples, capacity);
                Bytes serial(static_cast<std::size_t>(capacity), 0u);
                std::copy(input.begin(), input.end(), serial.begin());
                const auto baseline = codec::compressPackedTenBitRealtimeRangeLz4InPlace(
                    serial.data(), size, samples, capacity, crc(input), effort);
                require(baseline.successful, "Serial reference failed");
                // These retained shapes have deterministic all-range/all-raw chunks.
                require(result.storedBytes == baseline.storedBytes &&
                    std::equal(serial.begin(), serial.begin() + baseline.storedBytes, packet.begin()),
                    "Deterministic fixture changed established packet bytes");
                ++count;
            }
        }
        encoder.close(); encoder.close();
        Bytes unchanged(32u, 0xa7u);
        codec::EncodeResult result;
        require(!encoder.tryEncode(unchanged.data(), size, samples, capacity, 0u, 1024, result),
            "Closed encoder touched a retired session");
        require(std::all_of(unchanged.begin(), unchanged.end(), [](auto b) { return b == 0xa7u; }),
            "Closed encoder modified input");
    }
    std::printf("PASS parallel_range_shapes_reuse_exact_bytes_crc_independent_samples cases=%zu\n", count);
}

void rejectedInputs() {
    codec::ParallelRangeEncoder encoder;
    for (const auto samples : {-1LL, 0LL, 64LL, 1048575LL, 2147483647LL})
        require(!encoder.initialize(samples, samples, samples), "Invalid geometry accepted");
    constexpr std::size_t samples = 1048576u;
    constexpr std::size_t packed = samples * 10u / 8u;
    const auto capacity = codec::maximumStoredBytes(packed, samples);
    require(encoder.initialize(packed, samples, capacity), "Initialization after rejected input failed");
    Bytes unchanged(128u, 0xa7u);
    codec::EncodeResult result;
    for (const auto effort : {-1, 0, 65538, INT32_MAX})
        require(!encoder.tryEncode(unchanged.data(), packed, samples, capacity, 0u, effort, result),
            "Invalid effort accepted");
    require(!encoder.tryEncode(nullptr, packed, samples, capacity, 0u, 1024, result), "Null input accepted");
    require(!encoder.tryEncode(unchanged.data(), packed, samples - 1u, capacity, 0u, 1024, result),
        "Mismatched geometry accepted");
    require(!encoder.tryEncode(unchanged.data(), packed, samples, capacity - 1, 0u, 1024, result),
        "Short reservation accepted");
    require(std::all_of(unchanged.begin(), unchanged.end(), [](auto b) { return b == 0xa7u; }),
        "Rejected call modified input");
    std::puts("PASS parallel_range_rejection_before_input_mutation_and_idempotent_close");
}

void concurrentCallers() {
    constexpr std::size_t samples = 2097152u;
    const auto input = makeInput(samples, 2u);
    const auto capacity = codec::maximumStoredBytes(input.size(), samples);
    codec::ParallelRangeEncoder encoder;
    require(encoder.initialize(input.size(), samples, capacity), "Concurrent encoder initialization failed");
    unsigned declines = 0;
    for (unsigned iteration = 0; iteration < 8u; ++iteration) {
        Bytes packets[2] = {Bytes(capacity + 32u, 0xa7u), Bytes(capacity + 32u, 0xa7u)};
        codec::EncodeResult results[2]; bool admitted[2]{};
        std::atomic<unsigned> ready{0};
        auto call = [&](unsigned index) {
            std::copy(input.begin(), input.end(), packets[index].begin());
            ready.fetch_add(1);
            while (ready.load() != 2u) std::this_thread::yield();
            admitted[index] = encoder.tryEncode(packets[index].data(), input.size(), samples,
                capacity, crc(input), 1024, results[index]);
        };
        std::thread first(call, 0u); std::thread second(call, 1u);
        first.join(); second.join();
        for (unsigned index = 0; index < 2u; ++index) {
            if (admitted[index]) verify(input, packets[index], results[index], samples, capacity);
            else {
                ++declines;
                require(std::equal(input.begin(), input.end(), packets[index].begin()),
                    "Busy helper modified declined frame");
                results[index] = codec::compressPackedTenBitRealtimeRangeLz4InPlace(
                    packets[index].data(), input.size(), samples, capacity, crc(input), 1024);
                verify(input, packets[index], results[index], samples, capacity);
            }
        }
    }
    require(declines > 0u, "Concurrency test did not exercise helper contention");
    std::printf("PASS parallel_range_concurrent_callers_busy_no_mutation_serial_fallback declines=%u\n", declines);
}
}

int main() {
    try {
        rejectedInputs(); shapesAndRepeatedSession(); concurrentCallers();
        std::puts("PASS parallel_range_native_contract NOT_CAMERA_FPS");
        return 0;
    } catch (const std::exception& error) {
        std::fprintf(stderr, "FAIL parallel_range_native_contract %s\n", error.what());
        return 1;
    }
}
