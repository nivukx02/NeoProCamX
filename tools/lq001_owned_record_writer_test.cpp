// Uses the actual app writer, not a camera/FPS simulation. Retain its previous fault suite too.
#define main aligned_writer_baseline_fault_main
#include "lq001_aligned_writer_fault_test.cpp"
#undef main

namespace {
#if defined(NEO_NPV10_TEST_IMMUTABLE_OWNED_STREAM)
constexpr bool kStreamImmutableOwnedRecord = true;
#else
constexpr bool kStreamImmutableOwnedRecord = false;
#endif
struct OwnedFaultIo : FaultIo {
    static inline const std::uint8_t* requiredSource = nullptr;
    static inline std::size_t requiredBytes = 0;
    static inline unsigned directSourceWrites = 0;
    static ssize_t write(int fd, const void* source, std::size_t count, std::int64_t offset) noexcept {
        if (requiredSource != nullptr) {
            const auto begin = reinterpret_cast<std::uintptr_t>(requiredSource);
            const auto data = reinterpret_cast<std::uintptr_t>(source);
            if (data < begin || data - begin > requiredBytes || count > requiredBytes - (data - begin)) {
                errno = EFAULT;
                return -1;
            }
            ++directSourceWrites;
        }
        return FaultIo::write(fd, source, count, offset);
    }
};

struct OwnedAio {
    static inline std::vector<iocb*> pending;
    static inline unsigned maxSubmit = 4, maximumPending = 0;
    static inline bool reverse = false, shortEvent = false, invalidEvent = false;
    static inline int eventsError = 0, destroyError = 0;
    static void reset() {
        pending.clear(); maxSubmit = 4; maximumPending = 0;
        reverse = shortEvent = invalidEvent = false;
        eventsError = destroyError = 0;
        OwnedFaultIo::requiredSource = nullptr;
        OwnedFaultIo::requiredBytes = 0;
        OwnedFaultIo::directSourceWrites = 0;
    }
    static int sandboxCapability() noexcept { return 0; }
    static long setup(unsigned count, aio_context_t* context) noexcept {
        if (count != 4 || *context != 0) { errno = EINVAL; return -1; }
        *context = 99;
        return 0;
    }
    static long submit(aio_context_t context, unsigned count, iocb** requests) noexcept {
        if (context != 99 || pending.size() >= 4u) { errno = EAGAIN; return -1; }
        const auto accepted = std::min({count, maxSubmit, 4u - static_cast<unsigned>(pending.size())});
        for (unsigned index = 0; index < accepted; ++index) pending.push_back(requests[index]);
        maximumPending = std::max(maximumPending, static_cast<unsigned>(pending.size()));
        return accepted;
    }
    static long events(aio_context_t context, unsigned count, io_event* results,
                       unsigned minimum = 1u) noexcept {
        if (context != 99 || minimum == 0 || minimum > count || pending.empty()) { errno = EINVAL; return -1; }
        if (eventsError != 0) { errno = eventsError; return -1; }
        const auto index = reverse ? pending.size() - 1u : 0u;
        const auto* request = pending[index];
        pending.erase(pending.begin() + static_cast<std::ptrdiff_t>(index));
        auto& event = results[0];
        event = {};
        event.obj = reinterpret_cast<std::uintptr_t>(request);
        event.data = request->aio_data;
        const auto result = OwnedFaultIo::write(static_cast<int>(request->aio_fildes),
            reinterpret_cast<const void*>(static_cast<std::uintptr_t>(request->aio_buf)),
            static_cast<std::size_t>(request->aio_nbytes), request->aio_offset);
        event.res = result < 0 ? -errno : result;
        if (shortEvent && event.res > 0) --event.res;
        if (invalidEvent) ++event.obj;
        return 1;
    }
    static long destroy(aio_context_t context) noexcept {
        if (context != 99) { errno = EINVAL; return -1; }
        if (destroyError != 0) { errno = destroyError; return -1; }
        pending.clear();
        return 0;
    }
};

using OwnedWriter = neoproc::npv10::AlignedRecordWriter<OwnedFaultIo,
    8u * 1024u * 1024u, false, 1u, true, OwnedAio>;

struct OwnedAllocation {
    std::uint8_t* data = nullptr;
    std::size_t bytes;
    explicit OwnedAllocation(std::size_t size) : bytes(size) {
        void* allocated = nullptr;
        check(::posix_memalign(&allocated, 16384u, size) == 0, "owned allocation failed");
        data = static_cast<std::uint8_t*>(allocated);
        std::memset(data, 0xa5, size);
    }
    ~OwnedAllocation() { std::free(data); }
};

template<class Test> void ownedCase(const char* name, Test&& test) {
    OwnedAio::reset();
    run(name, std::forward<Test>(test));
    check(OwnedAio::pending.empty(), "unretired owned request after test");
}

void ownedSequence(long page, std::int64_t base, bool outOfOrder) {
    FaultIo::reset(page, base);
    OwnedWriter writer(40, "owned", base + kPrefix, FaultIo::physicalSize);
    check(writer.admission() == OwnedWriter::Admission::Active, "AIO admission failed");
    OwnedAllocation allocation(10u * 1024u * 1024u);
    std::vector<std::uint8_t> expected(FaultIo::bytes.begin(), FaultIo::bytes.begin() + kPrefix);
    const std::array<std::size_t, 11> sizes{
        1u, 3u, 4095u, 4096u, 4097u, 16383u, 65535u, 2097124u, 2097207u, 8388609u, 7u};
    std::uint64_t payloadTotal = 0;
    OwnedAio::maxSubmit = 1;
    OwnedAio::reverse = outOfOrder;
    for (auto size : sizes) {
        const auto offset = base + static_cast<std::int64_t>(expected.size());
        const auto head = static_cast<std::size_t>(offset % page) + header.size();
        auto* payload = allocation.data + head;
        for (std::size_t index = 0; index < size; ++index)
            payload[index] = static_cast<std::uint8_t>(index * 71u + index / 23u);
        const std::vector<std::uint8_t> original(payload, payload + size);
        OwnedFaultIo::requiredSource = allocation.data;
        OwnedFaultIo::requiredBytes = allocation.bytes;
        check(writer.writeOwnedRecord(header.data(), header.size(), payload, size,
            offset, FaultIo::physicalSize, allocation.data, allocation.bytes,
            kStreamImmutableOwnedRecord), "owned write failed");
        check(std::equal(original.begin(), original.end(), payload), "encoded packet was changed");
        expected.insert(expected.end(), header.begin(), header.end());
        expected.insert(expected.end(), original.begin(), original.end());
        check(std::equal(expected.begin(), expected.end(), FaultIo::bytes.begin()), "wire bytes differ");
        payloadTotal += size;
        check(writer.sourceBuffersRetired() && OwnedAio::pending.empty(), "early record acknowledgment");
        check(writer.ownedPayloadBytes() == payloadTotal, "payload telemetry differs");
    }
    check(writer.ownedRecordWrites() == sizes.size(), "copy path used unexpectedly");
    check(OwnedFaultIo::directSourceWrites > 0 && OwnedAio::maximumPending == 4u, "bounded direct AIO missing");
    check(writer.kernelAioSubmissions() == writer.kernelAioCompletions(), "completion accounting differs");
    OwnedFaultIo::requiredSource = nullptr;
}
} // namespace

int main() {
    if (aligned_writer_baseline_fault_main() != 0) return 1;
    try {
        ownedCase("owned_4KiB_variable_records_complete_byte_identity", [] { ownedSequence(4096, 0, false); });
        ownedCase("owned_16KiB_reverse_partial_completion_byte_identity", [] { ownedSequence(16384, 0, true); });
        ownedCase("owned_64bit_file_offsets", [] { ownedSequence(4096, 3LL * 1024 * 1024 * 1024, true); });
        for (std::size_t residue : {0u, 1u, 2u, 27u, 28u, 29u, 2047u, 4067u, 4068u, 4069u, 4095u}) {
            ownedCase("owned_header_crosses_sector_at_every_boundary", [residue] {
                const auto offset = static_cast<std::int64_t>(4096u + residue);
                OwnedWriter writer(40, "owned", offset, FaultIo::physicalSize);
                OwnedAllocation allocation(32768u);
                auto* payload = allocation.data + residue + header.size();
                std::memcpy(payload, packet.data(), 4097u);
                const auto old = FaultIo::bytes;
                check(writer.writeOwnedRecord(header.data(), header.size(), payload, 4097u, offset,
                    FaultIo::physicalSize, allocation.data, allocation.bytes,
                    kStreamImmutableOwnedRecord), "sector-crossing record failed");
                check(writer.ownedRecordWrites() == 1u, "unexpected staging");
                check(std::equal(old.begin(), old.begin() + offset, FaultIo::bytes.begin()), "prefix corruption");
                check(std::equal(packet.begin(), packet.begin() + 4097,
                    FaultIo::bytes.begin() + offset + header.size()), "payload corruption");
            });
        }
        ownedCase("speculative_offset_uses_existing_staging_before_io", [] {
            OwnedWriter writer(40, "owned", kPrefix, FaultIo::physicalSize);
            OwnedAllocation allocation(32768u);
            auto* payload = allocation.data + 1u;
            std::memcpy(payload, packet.data(), 4097u);
            check(writer.writeOwnedRecord(header.data(), header.size(), payload, 4097u, kPrefix,
                FaultIo::physicalSize, allocation.data, allocation.bytes,
                kStreamImmutableOwnedRecord), "safe staging failed");
            check(writer.ownedRecordWrites() == 0u, "misaligned pointer was used directly");
            check(std::equal(packet.begin(), packet.begin() + 4097,
                FaultIo::bytes.begin() + kPrefix + header.size()), "fallback changed payload");
        });
        for (int failure : {EIO, ENOSPC, EINVAL}) {
            ownedCase("owned_runtime_failure_retires_and_never_falls_back", [failure] {
                OwnedWriter writer(40, "owned", kPrefix, FaultIo::physicalSize);
                OwnedAllocation allocation(32768u);
                auto* payload = allocation.data + kPrefix % 4096 + header.size();
                FaultIo::scriptedWrites = {-failure};
                check(!writer.writeOwnedRecord(header.data(), header.size(), payload, 8192u, kPrefix,
                    FaultIo::physicalSize, allocation.data, allocation.bytes,
                    kStreamImmutableOwnedRecord), "I/O failure hidden");
                check(writer.failureErrno() == failure && writer.ownedRecordWrites() == 0u &&
                    writer.sourceBuffersRetired(), "failed write escaped ownership barrier");
                const auto calls = FaultIo::writes;
                check(!writer.writeOwnedRecord(header.data(), header.size(), payload, 8192u, kPrefix,
                    FaultIo::physicalSize, allocation.data, allocation.bytes,
                    kStreamImmutableOwnedRecord), "failed writer reused");
                check(FaultIo::writes == calls, "failed source retried through another route");
            });
        }
        for (bool invalid : {false, true}) {
            ownedCase("owned_bad_completion_is_terminal_and_retired", [invalid] {
                OwnedWriter writer(40, "owned", kPrefix, FaultIo::physicalSize);
                OwnedAllocation allocation(32768u);
                auto* payload = allocation.data + kPrefix % 4096 + header.size();
                OwnedAio::shortEvent = !invalid; OwnedAio::invalidEvent = invalid;
                check(!writer.writeOwnedRecord(header.data(), header.size(), payload, 8192u, kPrefix,
                    FaultIo::physicalSize, allocation.data, allocation.bytes,
                    kStreamImmutableOwnedRecord), "bad completion admitted");
                check(writer.sourceBuffersRetired() && writer.ownedRecordWrites() == 0u, "early ownership return");
            });
        }
        ownedCase("unretirable_kernel_context_keeps_source_lifetime_explicit", [] {
            OwnedWriter writer(40, "owned", kPrefix, FaultIo::physicalSize);
            OwnedAllocation allocation(32768u);
            auto* payload = allocation.data + kPrefix % 4096 + header.size();
            OwnedAio::eventsError = EIO; OwnedAio::destroyError = EPERM;
            check(!writer.writeOwnedRecord(header.data(), header.size(), payload, 8192u, kPrefix,
                FaultIo::physicalSize, allocation.data, allocation.bytes,
                kStreamImmutableOwnedRecord), "kernel failure admitted");
            check(!writer.sourceBuffersRetired() && !OwnedAio::pending.empty(), "live source reported free");
            check(!writer.close() && !writer.sourceBuffersRetired(), "failed destroy claimed retirement");
            OwnedAio::destroyError = 0;
            check(writer.close() && writer.sourceBuffersRetired(), "kernel could not be retired before free");
        });
        for (unsigned scenario = 0; scenario < 5u; ++scenario) {
            ownedCase("owned_bounds_reservation_order_and_inode_fail_before_mutation", [scenario] {
                OwnedWriter writer(40, "owned", kPrefix, FaultIo::physicalSize);
                OwnedAllocation allocation(32768u);
                auto* payload = allocation.data + kPrefix % 4096 + header.size();
                const auto before = std::vector<std::uint8_t>(allocation.data, allocation.data + allocation.bytes);
                const auto calls = FaultIo::writes;
                if (scenario == 3u) FaultIo::physicalSize = kPrefix;
                if (scenario == 4u) FaultIo::wrongInode = true;
                check(!writer.writeOwnedRecord(header.data(), header.size(), payload, 8192u,
                    kPrefix + (scenario == 2u ? 1 : 0), scenario == 1u ? kPrefix + 8192 : FaultIo::physicalSize,
                    allocation.data, scenario == 0u ? 10u : allocation.bytes,
                    kStreamImmutableOwnedRecord), "invalid record admitted");
                check(FaultIo::writes == calls && std::equal(before.begin(), before.end(), allocation.data),
                    "invalid request mutated source or file");
            });
        }
        std::printf("{\"scope\":\"actual_app_owned_writer_native_contract_not_camera_fps\",\"passed\":%u,\"failed\":0}\n", passed);
        return 0;
    } catch (const std::exception& error) {
        std::fprintf(stderr, "OWNED_WRITER_FAILED after %u: %s errno=%d\n", passed, error.what(), errno);
        return 1;
    }
}
