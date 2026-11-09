#pragma once

#include "npv10_aligned_record_writer.h"
#include <memory>

namespace neoproc::npv10 {

/** Select once before capture. Kernel AIO overlaps disjoint sectors, never uncommitted frames. */
class RealtimeAlignedRecordWriter final {
    using Legacy = AlignedRecordWriter<>;
    using Concurrent = AlignedRecordWriter<AlignedRecordPosixIo, 8u * 1024u * 1024u, false, 1u, true>;
public:
    using Admission = Legacy::Admission;
    static constexpr std::size_t kMinimumPayloadCapacity = Legacy::kCapacity;

    RealtimeAlignedRecordWriter(int fd, const char* path, std::int64_t start,
                                std::int64_t reservation) {
        capabilityErrno_ = KernelAioPosix::sandboxCapability();
        if (capabilityErrno_ == 0) {
            concurrent_ = std::make_unique<Concurrent>(fd, path, start, reservation);
            if (concurrent_->admission() == Concurrent::Admission::Fatal) return;
            if (concurrent_->kernelAioEnabled()) return;
            capabilityErrno_ = concurrent_->kernelAioAdmissionErrno();
            // No frame or AIO request has been accepted on this unsupported route. Preserve the
            // measured 2-MiB legacy writer on other kernels instead of changing its submission size.
            if (!concurrent_->close()) { closeFailure_ = true; return; }
            concurrent_.reset();
        }
        legacy_ = std::make_unique<Legacy>(fd, path, start, reservation);
    }
    RealtimeAlignedRecordWriter(const RealtimeAlignedRecordWriter&) = delete;
    RealtimeAlignedRecordWriter& operator=(const RealtimeAlignedRecordWriter&) = delete;

    Admission admission() const noexcept {
        if (closeFailure_) return Admission::Fatal;
        if (legacy_) return legacy_->admission();
        switch (concurrent_->admission()) {
            case Concurrent::Admission::Active: return Admission::Active;
            case Concurrent::Admission::Unsupported: return Admission::Unsupported;
            case Concurrent::Admission::Fatal: return Admission::Fatal;
        }
        return Admission::Fatal;
    }
    int admissionErrno() const noexcept {
        if (closeFailure_) return concurrent_->failureErrno();
        return legacy_ ? legacy_->admissionErrno() : concurrent_->admissionErrno();
    }
    int failureErrno() const noexcept { return legacy_ ? legacy_->failureErrno() : concurrent_->failureErrno(); }
    bool probeVerified() const noexcept { return legacy_ ? legacy_->probeVerified() : concurrent_->probeVerified(); }
    std::size_t alignment() const noexcept { return legacy_ ? legacy_->alignment() : concurrent_->alignment(); }
    std::size_t stagingBytes() const noexcept { return legacy_ ? Legacy::kCapacity : Concurrent::kCapacity; }
    std::uint64_t writeCalls() const noexcept { return legacy_ ? legacy_->writeCalls() : concurrent_->writeCalls(); }
    std::uint64_t physicalBytes() const noexcept { return legacy_ ? legacy_->physicalBytes() : concurrent_->physicalBytes(); }
    std::uint64_t paddingBytes() const noexcept { return legacy_ ? legacy_->paddingBytes() : concurrent_->paddingBytes(); }
    std::uint64_t failures() const noexcept { return legacy_ ? legacy_->failures() : concurrent_->failures(); }
    bool kernelAioEnabled() const noexcept { return concurrent_ && concurrent_->kernelAioEnabled(); }
    int kernelAioAdmissionErrno() const noexcept { return capabilityErrno_; }
    std::uint64_t kernelAioSubmissions() const noexcept { return concurrent_ ? concurrent_->kernelAioSubmissions() : 0u; }
    std::uint64_t kernelAioCompletions() const noexcept { return concurrent_ ? concurrent_->kernelAioCompletions() : 0u; }
    std::uint64_t kernelAioPartialSubmissions() const noexcept {
        return concurrent_ ? concurrent_->kernelAioPartialSubmissions() : 0u;
    }
    std::uint64_t kernelAioBackpressureEvents() const noexcept {
        return concurrent_ ? concurrent_->kernelAioBackpressureEvents() : 0u;
    }
    bool sourceBuffersRetired() const noexcept {
        return legacy_ ? legacy_->sourceBuffersRetired() : concurrent_->sourceBuffersRetired();
    }
    std::uint64_t ownedRecordWrites() const noexcept {
        return legacy_ ? legacy_->ownedRecordWrites() : concurrent_->ownedRecordWrites();
    }
    std::uint64_t ownedPayloadBytes() const noexcept {
        return legacy_ ? legacy_->ownedPayloadBytes() : concurrent_->ownedPayloadBytes();
    }
    std::uint64_t immutableStreamRecordWrites() const noexcept {
        return concurrent_ ? concurrent_->immutableStreamRecordWrites() : 0u;
    }
    bool alignedEnd(std::int64_t end, std::int64_t* result) const noexcept {
        return legacy_ ? legacy_->alignedEnd(end, result) : concurrent_->alignedEnd(end, result);
    }
    bool writeRecord(const std::uint8_t* header, std::size_t headerBytes,
                     const std::uint8_t* payload, std::size_t payloadBytes,
                     std::int64_t offset, std::int64_t reservation,
                     std::uint8_t* ownedStorage = nullptr, std::size_t ownedStorageBytes = 0u) noexcept {
        if (ownedStorage != nullptr) {
            return legacy_ ? legacy_->writeOwnedRecord(header, headerBytes, payload, payloadBytes,
                offset, reservation, ownedStorage, ownedStorageBytes)
                : concurrent_->writeOwnedRecord(header, headerBytes, payload, payloadBytes,
                    offset, reservation, ownedStorage, ownedStorageBytes,
                    // Actual 12MP recorded-packet ABBA tests at thermal 4 showed lower mean
                    // write time with identical bytes. Refill only packets spanning multiple
                    // existing windows; retain the original route for small records and kernels.
                    payloadBytes > Concurrent::kCapacity);
        }
        return legacy_ ? legacy_->writeRecord(header, headerBytes, payload, payloadBytes, offset, reservation)
            : concurrent_->writeRecord(header, headerBytes, payload, payloadBytes, offset, reservation);
    }
    bool close() noexcept { return legacy_ ? legacy_->close() : concurrent_->close(); }

private:
    std::unique_ptr<Legacy> legacy_;
    std::unique_ptr<Concurrent> concurrent_;
    int capabilityErrno_ = 0;
    bool closeFailure_ = false;
};

} // namespace neoproc::npv10
