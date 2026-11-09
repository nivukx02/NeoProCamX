// Run on the real Android CPU. This validates codec safety/speed, NOT camera FPS.
#include "npv10_lossless_chunk_codec.h"
#include "npv10_p010_range_fusion.h"
#include <algorithm>
#include <chrono>
#include <cstdio>
#include <limits>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>
#include <sys/mman.h>
#include <unistd.h>
#include <zlib.h>

namespace {
namespace codec = neoproc::npv10::lossless;
using Bytes = std::vector<std::uint8_t>;
void check(bool value, const char* message) { if (!value) throw std::runtime_error(message); }
Bytes packReference(const Bytes& p010) {
    Bytes packed(p010.size() / 8u * 5u, 0u);
    for (std::size_t i = 0; i < p010.size() / 2u; ++i) {
        const auto word = unsigned(p010[i * 2u]) | (unsigned(p010[i * 2u + 1u]) << 8u);
        check((word & 63u) == 0u, "Invalid reference P010 padding");
        for (unsigned bit = 0; bit < 10u; ++bit) {
            const auto position = i * 10u + bit;
            packed[position / 8u] |= std::uint8_t(((word >> (bit + 6u)) & 1u) << (position & 7u));
        }
    }
    return packed;
}
Bytes source(unsigned samples, unsigned evenBits, unsigned oddBits, unsigned origin) {
    Bytes bytes(samples * 2u);
    std::uint32_t seed = 0x32482051u;
    for (unsigned i = 0; i < samples; ++i) {
        seed ^= seed << 13u; seed ^= seed >> 17u; seed ^= seed << 5u;
        const auto bits = (i & 1u) ? oddBits : evenBits;
        const auto mask = (1u << bits) - 1u;
        const auto low = (1023u - mask) * origin / 2u;
        const auto code = low + ((i % 64u) < 2u ? 0u : (i % 64u) < 4u ? mask : seed & mask);
        const auto word = code << 6u;
        bytes[i * 2u] = std::uint8_t(word);
        bytes[i * 2u + 1u] = std::uint8_t(word >> 8u);
    }
    return bytes;
}
void blockContracts() {
    std::size_t cases = 0;
    for (unsigned a = 0; a <= 10; ++a) for (unsigned b = 0; b <= 10; ++b) {
        for (unsigned origin = 0; origin <= 2; ++origin) {
            auto p010 = source(64u, a, b, origin);
            const auto canonical = packReference(p010);
            Bytes expected(84u);
            const auto bytes = codec::encodeRange10(canonical.data(), 64u, expected.data(), expected.size());
            check(bytes > 0u, "Reference range failed");
            for (const auto cap : {0u, unsigned(bytes - 1u), unsigned(bytes), 84u}) {
                Bytes packed(82u, 0xa7u), range(86u, 0xa7u), unaligned(129u, 0);
                std::copy(p010.begin(), p010.end(), unaligned.begin() + 1u);
                std::size_t written = 999u;
                check(codec::packAndEncodeP010RangeBlock(unaligned.data() + 1u, packed.data() + 1u,
                    range.data() + 1u, cap, &written), "Fused block rejected canonical source");
                check(written == (cap < bytes ? 0u : bytes), "Range capacity not respected");
                check(std::equal(canonical.begin(), canonical.end(), packed.begin() + 1u), "Packed CRC bytes changed");
                if (written) check(std::equal(expected.begin(), expected.begin() + bytes, range.begin() + 1u),
                    "Range wire bytes changed");
                check(packed.front() == 0xa7u && packed.back() == 0xa7u && range.front() == 0xa7u &&
                    std::all_of(range.begin() + cap + 1u, range.end(), [](auto v) { return v == 0xa7u; }),
                    "Block wrote beyond guards");
                ++cases;
            }
        }
    }
    for (unsigned word = 0; word < 64; ++word) for (unsigned bit = 0; bit < 6; ++bit) {
        auto p010 = source(64u, 6u, 5u, 1u);
        p010[word * 2u] |= std::uint8_t(1u << bit);
        Bytes packed(80u), encoded(84u);
        std::size_t bytes = 0;
        check(!codec::packAndEncodeP010RangeBlock(p010.data(), packed.data(), encoded.data(),
            encoded.size(), &bytes), "Noncanonical P010 bit was discarded");
        ++cases;
    }
    std::printf("PASS fused_block_contracts=%zu mixed_widths_origins_unaligned_capacity_padding\n", cases);
}
void blockArgumentContracts() {
    const auto input = source(64u, 5u, 7u, 1u);
    const auto canonical = packReference(input);
    Bytes packed(80u, 0xa7u), range(84u, 0xa7u);
    std::size_t written = 999u;
    check(!codec::packAndEncodeP010RangeBlock(nullptr, packed.data(), range.data(),
        range.size(), &written), "Null block source accepted");
    check(!codec::packAndEncodeP010RangeBlock(input.data(), nullptr, range.data(),
        range.size(), &written), "Null packed output accepted");
    check(!codec::packAndEncodeP010RangeBlock(input.data(), packed.data(), range.data(),
        range.size(), nullptr), "Null block extent accepted");
    check(!codec::packAndEncodeP010RangeBlock(input.data(), packed.data(), nullptr,
        range.size(), &written), "Null nonempty range output accepted");
    check(std::all_of(packed.begin(), packed.end(), [](auto value) { return value == 0xa7u; }) &&
        std::all_of(range.begin(), range.end(), [](auto value) { return value == 0xa7u; }),
        "Rejected block arguments changed output");
    check(codec::packAndEncodeP010RangeBlock(input.data(), packed.data(), nullptr, 0u, &written) &&
        written == 0u && packed == canonical, "Packed-only null range contract changed");
    std::puts("PASS fused_block_argument_contracts_null_rejection_no_writes_packed_only");
}
struct Guarded final {
    std::uint8_t* mapping;
    std::size_t page;
    explicit Guarded() : page(static_cast<std::size_t>(::sysconf(_SC_PAGESIZE))) {
        mapping = static_cast<std::uint8_t*>(::mmap(nullptr, page * 2u, PROT_READ | PROT_WRITE,
            MAP_PRIVATE | MAP_ANONYMOUS, -1, 0));
        check(mapping != MAP_FAILED, "mmap guard failed");
        check(::mprotect(mapping + page, page, PROT_NONE) == 0, "mprotect guard failed");
    }
    ~Guarded() { ::munmap(mapping, page * 2u); }
    std::uint8_t* tail(std::size_t bytes) { check(bytes <= page, "Guard span too large"); return mapping + page - bytes; }
};
void guardPages() {
    Guarded input, packed, range;
    auto p010 = source(64u, 5u, 7u, 1u);
    std::copy(p010.begin(), p010.end(), input.tail(128u));
    std::size_t bytes = 0;
    check(codec::packAndEncodeP010RangeBlock(input.tail(128u), packed.tail(80u), range.tail(52u),
        52u, &bytes) && bytes == 52u, "Guarded block failed");
    Guarded y, uv, packet;
    auto full = source(192u, 5u, 7u, 1u);
    std::copy(full.begin(), full.begin() + 256u, y.tail(256u));
    std::copy(full.begin() + 256u, full.end(), uv.tail(128u));
    const auto capacity = codec::maximumStoredBytes(240, 192);
    const auto result = codec::compressP010PlanesRealtimeRangeLz4(y.tail(256u), 256, uv.tail(128u),
        128, 64, 2, 128, 128, packet.tail(capacity), capacity, 1024);
    Bytes decoded(full.size());
    check(result.successful && codec::decodePacketToP010(packet.tail(capacity), result.storedBytes,
        decoded.data(), decoded.size()) && decoded == full, "Guarded frame failed");
    std::puts("PASS fused_guard_pages_exact_input_output_no_overread");
}
void frameContract(unsigned width, unsigned height, unsigned padding, unsigned bits,
                   unsigned chromaPadding = std::numeric_limits<unsigned>::max()) {
    const auto samples = width * height * 3u / 2u;
    auto original = source(samples, bits, bits, 0u);
    const auto packed = packReference(original);
    const auto crc = static_cast<std::uint32_t>(::crc32(0L, packed.data(), packed.size()));
    const auto capacity = codec::maximumStoredBytes(packed.size(), samples);
    const auto stride = width * 2u + padding;
    const auto uvStride = width * 2u + (chromaPadding == std::numeric_limits<unsigned>::max()
        ? padding : chromaPadding);
    Bytes y(stride * (height - 1u) + width * 2u, 0xa7u);
    Bytes uv(uvStride * (height / 2u - 1u) + width * 2u, 0xa7u);
    for (unsigned row = 0; row < height; ++row)
        std::copy_n(original.data() + row * width * 2u, width * 2u, y.data() + row * stride);
    for (unsigned row = 0; row < height / 2u; ++row)
        std::copy_n(original.data() + (height + row) * width * 2u, width * 2u, uv.data() + row * uvStride);
    Bytes packet(capacity + 16u, 0xa7u), baseline(capacity, 0u), decoded(original.size());
    const auto encoded = codec::compressP010PlanesRealtimeRangeLz4(y.data(), y.size(), uv.data(), uv.size(),
        width, height, stride, uvStride, packet.data() + 1u, capacity, 1024);
    check(encoded.successful && encoded.packedCrc32 == crc, "Frame failed / canonical CRC changed");
    check(encoded.storedCrc32 == ::crc32(0L, packet.data() + 1u, encoded.storedBytes), "Stored CRC mismatch");
    check(codec::decodePacketToP010(packet.data() + 1u, encoded.storedBytes, decoded.data(), decoded.size()) &&
        decoded == original, "Round trip changed original P010 words");
    check(packet.front() == 0xa7u && std::all_of(packet.begin() + capacity + 1u, packet.end(),
        [](auto v) { return v == 0xa7u; }), "Packet capacity guard overwritten");
    std::copy(packed.begin(), packed.end(), baseline.begin());
    const auto old = codec::compressPackedTenBitRealtimeRangeLz4InPlace(baseline.data(), packed.size(),
        samples, capacity, crc, 1024);
    check(old.successful, "Original compressor failed");
    if (bits < 9u) check(encoded.storedBytes == old.storedBytes &&
        std::equal(baseline.begin(), baseline.begin() + old.storedBytes, packet.begin() + 1u),
        "Range packet differs from established encoder");
    check(!codec::decodePacketToP010(packet.data() + 1u, encoded.storedBytes - 1,
        decoded.data(), decoded.size()), "Truncated packet accepted");
    packet[29] ^= 1u; // Original source CRC remains mandatory even if outer CRC is not supplied.
    check(!codec::decodePacketToP010(packet.data() + 1u, encoded.storedBytes,
        decoded.data(), decoded.size()), "Corrupt source CRC accepted");
    for (const auto index : {std::size_t{0u}, y.size() - 2u}) {
        y[index] |= 1u;
        check(!codec::compressP010PlanesRealtimeRangeLz4(y.data(), y.size(), uv.data(), uv.size(),
            width, height, stride, uvStride, packet.data() + 1u, capacity, 1024).successful,
            "Invalid first/last luma padding accepted");
        y[index] &= 0xc0u;
    }
    uv[uv.size() - 2u] |= 1u;
    check(!codec::compressP010PlanesRealtimeRangeLz4(y.data(), y.size(), uv.data(), uv.size(),
        width, height, stride, uvStride, packet.data() + 1u, capacity, 1024).successful,
        "Invalid last chroma padding accepted");
    std::printf("PASS fused_frame width=%u height=%u padding=%u uv_stride=%u bits=%u stored=%lld raw_chunks=%d\n",
        width, height, padding, uvStride, bits, static_cast<long long>(encoded.storedBytes), encoded.rawChunks);
}
void invalidSpans() {
    Bytes y(256u), uv(128u), target(284u, 0xa7u);
    const auto run = [&](const std::uint8_t* yp, std::int64_t yc, const std::uint8_t* up, std::int64_t uc,
                         int width, int height, int ys, int us, std::uint8_t* out, std::int64_t cap, int effort) {
        return codec::compressP010PlanesRealtimeRangeLz4(yp, yc, up, uc, width, height, ys, us, out, cap, effort).successful;
    };
    check(!run(nullptr, 256, uv.data(), 128, 64, 2, 128, 128, target.data(), 284, 1024), "Null Y admitted");
    check(!run(y.data(), 256, nullptr, 128, 64, 2, 128, 128, target.data(), 284, 1024), "Null UV admitted");
    check(!run(y.data(), 255, uv.data(), 128, 64, 2, 128, 128, target.data(), 284, 1024), "Truncated Y admitted");
    check(!run(y.data(), 256, uv.data(), 127, 64, 2, 128, 128, target.data(), 284, 1024), "Truncated UV admitted");
    for (int width : {-64, 0, 4, 63, 65, std::numeric_limits<int>::max() - 63})
        check(!run(y.data(), 256, uv.data(), 128, width, 2, 128, 128, target.data(), 284, 1024), "Invalid width admitted");
    for (int height : {-2, 0, 1, 3, std::numeric_limits<int>::max() - 1})
        check(!run(y.data(), 256, uv.data(), 128, 64, height, 128, 128, target.data(), 284, 1024), "Invalid height admitted");
    check(!run(y.data(), 256, uv.data(), 128, 64, 2, 127, 128, target.data(), 284, 1024), "Short stride admitted");
    check(!run(y.data(), 256, uv.data(), 128, 64, 2, 128, 127, target.data(), 284, 1024), "Short UV stride admitted");
    for (const auto cap : {-1, 0, 283})
        check(!run(y.data(), 256, uv.data(), 128, 64, 2, 128, 128, target.data(), cap, 1024), "Short capacity admitted");
    for (const auto effort : {0, -1, 65538})
        check(!run(y.data(), 256, uv.data(), 128, 64, 2, 128, 128, target.data(), 284, effort), "Invalid LZ4 effort admitted");
    check(!run(y.data(), 256, uv.data(), 128, 64, 2, 128, 128, y.data(), 284, 1024), "Y output alias admitted");
    check(!run(y.data(), 256, uv.data(), 128, 64, 2, 128, 128, uv.data(), 284, 1024), "UV output alias admitted");
    check(std::all_of(target.begin(), target.end(), [](auto v) { return v == 0xa7u; }), "Invalid input wrote output");
    std::puts("PASS fused_invalid_spans_dimensions_overflow_alias_acceleration");
}
Bytes read(const char* path) {
    FILE* file = std::fopen(path, "rb");
    check(file != nullptr, "Cannot open recorded frame");
    check(std::fseek(file, 0, SEEK_END) == 0, "Frame seek failed");
    const auto size = std::ftell(file);
    check(size == 4096L * 3072L * 3L, "Expected exact 12MP recorded P010 frame");
    std::rewind(file);
    Bytes data(size);
    const auto bytes = std::fread(data.data(), 1, data.size(), file);
    const auto close = std::fclose(file);
    check(bytes == data.size() && close == 0, "Frame read failed");
    return data;
}
void benchmark(const char* path) {
    const auto p010 = read(path);
    const auto packed = packReference(p010);
    const auto count = p010.size() / 2u;
    const auto capacity = codec::maximumStoredBytes(packed.size(), count);
    const auto crc = static_cast<std::uint32_t>(::crc32(0L, packed.data(), packed.size()));
    Bytes buffer(capacity), reference(capacity), decoded(p010.size());
    std::copy(packed.begin(), packed.end(), reference.begin());
    const auto old = codec::compressPackedTenBitRealtimeRangeLz4InPlace(reference.data(), packed.size(),
        count, capacity, crc, 1024);
    check(old.successful, "Baseline failed");
    for (unsigned pair = 0; pair < 4u; ++pair) for (unsigned order = 0; order < 2u; ++order) {
        const bool fused = ((pair + order) & 1u) != 0;
        double sum = 0, maximum = 0;
        codec::EncodeResult result{};
        for (unsigned iteration = 0; iteration < 24u; ++iteration) {
            if (!fused) std::copy(packed.begin(), packed.end(), buffer.begin());
            const auto start = std::chrono::steady_clock::now();
            result = fused ? codec::compressP010PlanesRealtimeRangeLz4(p010.data(), 4096LL * 3072LL * 2LL,
                p010.data() + 4096u * 3072u * 2u, 4096LL * 3072LL, 4096, 3072, 8192, 8192,
                buffer.data(), capacity, 1024) : codec::compressPackedTenBitRealtimeRangeLz4InPlace(
                buffer.data(), packed.size(), count, capacity, crc, 1024);
            const double ms = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
            check(result.successful && result.packedCrc32 == crc, "Benchmark failed or changed CRC");
            sum += ms;
            maximum = std::max(maximum, ms);
        }
        check(codec::decodePacketToP010(buffer.data(), result.storedBytes, decoded.data(), decoded.size()) &&
            decoded == p010, "Benchmark changed exact recorded frame");
        const bool samePacket = result.storedBytes == old.storedBytes && std::equal(reference.begin(),
            reference.begin() + old.storedBytes, buffer.begin());
        std::printf("REAL_P010_FUSION sample=%s pair=%u mode=%s iterations=24 mean_ms=%.6f max_ms=%.6f "
            "stored_bytes=%lld bit_exact=true identical_packet=%s camera_fps=NOT_TESTED\n", path, pair,
            fused ? "fused_pack_crc_range" : "baseline_range_only_EXCLUDES_PACKING", sum / 24.0, maximum,
            static_cast<long long>(result.storedBytes), samePacket ? "true" : "false");
        std::fflush(stdout);
    }
}
}
int main(int argc, char** argv) {
    try {
        if (argc > 1) {
            for (int i = 1; i < argc; ++i) benchmark(argv[i]);
        } else {
            blockContracts(); blockArgumentContracts(); guardPages(); invalidSpans();
            for (unsigned width : {64u, 192u, 4096u}) for (unsigned height : {2u, 6u, 66u})
                for (unsigned padding : {0u, 14u}) for (unsigned bits : {0u, 5u, 8u, 10u})
                    frameContract(width, height, padding, bits);
            // Deliberately cross a row in a chunk and the plane boundary in a
            // later chunk; luma/chroma pitch must never be assumed identical.
            frameContract(192u, 1822u, 14u, 5u, 30u);
            frameContract(320u, 1100u, 2u, 10u, 0u);
            frameContract(128u, 2050u, 0u, 8u, 18u);
            frameContract(4096u, 130u, 30u, 5u, 14u);
            std::puts("PASS fused_p010_native_contracts camera_fps=NOT_TESTED");
        }
        return 0;
    } catch (const std::exception& error) {
        std::fprintf(stderr, "FAIL fused_p010: %s\n", error.what());
        return 1;
    }
}
