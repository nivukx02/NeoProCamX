// Debug-only, actual-app-UID storage isolation using CRC-verified real camera packets.
// It is deliberately not a camera, compression, audio, or checkpoint performance test.
#include "../../main/cpp/npv10_realtime_aligned_writer.h"
#include <jni.h>
#include <android/log.h>
#include <array>
#include <chrono>
#include <cstdio>
#include <sstream>
#include <stdexcept>
#include <string>
#include <sys/resource.h>
#include <sys/statvfs.h>
#include <time.h>
#include <vector>

namespace {
using Clock = std::chrono::steady_clock;
constexpr std::int64_t kPeriodNs = 40'000'000;
constexpr std::int64_t kPrefix = 13654;
constexpr std::int64_t kFreeFloor = 2LL * 1024LL * 1024LL * 1024LL;
constexpr std::size_t kHeaderBytes = 28;

// Identical production file, request depth, staging, bytes and barriers; only the
// completion-wakeup policy differs. Counters describe real syscalls, not simulated I/O.
struct ReplayCompletionObservations {
    static inline std::uint64_t waits = 0, fullGroupWaits = 0, returnedEvents = 0;
    static void reset() noexcept { waits = fullGroupWaits = returnedEvents = 0; }
};
template<bool Batched>
struct ReplayCompletionAio : neoproc::npv10::KernelAioPosix {
    static long events(aio_context_t context, unsigned count, io_event* output,
                       unsigned minimum = 1u) noexcept {
        ++ReplayCompletionObservations::waits;
        const auto requested = Batched ? minimum : 1u;
        if (requested > 1u) ++ReplayCompletionObservations::fullGroupWaits;
        const auto result = KernelAioPosix::events(context, count, output, requested);
        if (result > 0) ReplayCompletionObservations::returnedEvents +=
            static_cast<std::uint64_t>(result);
        return result;
    }
};

void check(bool value, const char* message) {
    if (!value) throw std::runtime_error(std::string(message) + " errno=" + std::to_string(errno));
}

struct Packet { const std::uint8_t* data; std::size_t size; };
struct OwnedReplay {
    std::vector<char> path;
    int fd = -1;
    explicit OwnedReplay(const std::string& directory) {
        const auto name = directory + "/recorded-writer-XXXXXX";
        path.assign(name.begin(), name.end());
        path.push_back('\0');
        fd = ::mkstemp(path.data());
        check(fd >= 0, "create private replay file");
    }
    ~OwnedReplay() {
        if (fd >= 0) {
            (void)::close(fd);
            (void)::unlink(path.data()); // only the exact mkstemp file owned by this invocation
        }
    }
};

void readExact(int fd, std::uint8_t* bytes, std::size_t count, std::int64_t offset) {
    while (count != 0u) {
        const auto got = ::pread64(fd, bytes, count, static_cast<off64_t>(offset));
        if (got < 0 && errno == EINTR) continue;
        check(got > 0 && static_cast<std::size_t>(got) <= count, "complete readback");
        bytes += got;
        count -= static_cast<std::size_t>(got);
        offset += got;
    }
}

void frameHeader(std::array<std::uint8_t, kHeaderBytes>& header, unsigned frame) {
    for (std::size_t index = 0; index < header.size(); ++index)
        header[index] = static_cast<std::uint8_t>((frame >> (8u * (index % 4u))) ^ index);
}

template<class Writer>
std::string replay(const std::string& directory, const std::vector<Packet>& packets, unsigned frames,
                   const char* backend) {
    check(frames >= 25u && frames <= 7500u && !packets.empty() && packets.size() <= 32u,
        "bounded real-data replay arguments");
    std::int64_t logicalEnd = kPrefix;
    std::size_t largest = 0;
    for (unsigned index = 0; index < frames; ++index) {
        const auto size = packets[index % packets.size()].size;
        check(size > 0 && size <= 32u * 1024u * 1024u, "real packet capacity");
        logicalEnd += static_cast<std::int64_t>(kHeaderBytes + size);
        largest = std::max(largest, size);
    }
    const std::int64_t reservation = (logicalEnd + 16383) / 16384 * 16384;
    struct statvfs space{};
    check(::statvfs(directory.c_str(), &space) == 0, "private-filesystem capacity");
    const auto available = static_cast<std::uint64_t>(space.f_bavail) * space.f_frsize;
    check(available >= static_cast<std::uint64_t>(reservation + kFreeFloor),
        "insufficient storage: no shortened or overwritten replay accepted");
    OwnedReplay file(directory);
    const int allocated = ::posix_fallocate64(file.fd, 0, reservation);
    if (allocated != 0) errno = allocated;
    check(allocated == 0, "physical reservation before measurement");
    std::vector<std::uint8_t> prefix(static_cast<std::size_t>(kPrefix), 0x79u);
    check(::pwrite64(file.fd, prefix.data(), prefix.size(), 0) == static_cast<ssize_t>(prefix.size()),
        "prefix write");
    check(::fsync(file.fd) == 0, "prefix durability");
    Writer writer(file.fd, file.path.data(), kPrefix, reservation);
    check(writer.admission() == decltype(writer)::Admission::Active && writer.probeVerified() &&
        writer.kernelAioEnabled(), "production aligned writer admission");
    ReplayCompletionObservations::reset();
    (void)::setpriority(PRIO_PROCESS, 0, -4); // same best-effort priority as the production writer
    std::vector<double> elapsed;
    elapsed.reserve(frames);
    std::int64_t offset = kPrefix;
    double maximum = 0.0, total = 0.0, syncMaximum = 0.0;
    unsigned above40 = 0, lateFrames = 0;
    timespec origin{};
    check(::clock_gettime(CLOCK_MONOTONIC, &origin) == 0, "monotonic pacing origin");
    const auto originNs = static_cast<std::int64_t>(origin.tv_sec) * 1'000'000'000LL + origin.tv_nsec;
    for (unsigned frame = 0; frame < frames; ++frame) {
        const auto deadline = originNs + static_cast<std::int64_t>(frame) * kPeriodNs;
        const timespec target{static_cast<time_t>(deadline / 1'000'000'000LL),
            static_cast<long>(deadline % 1'000'000'000LL)};
        int slept;
        do { slept = ::clock_nanosleep(CLOCK_MONOTONIC, TIMER_ABSTIME, &target, nullptr); }
        while (slept == EINTR);
        check(slept == 0, "absolute frame pacing");
        const auto& packet = packets[frame % packets.size()];
        std::array<std::uint8_t, kHeaderBytes> header{};
        frameHeader(header, frame);
        const auto started = Clock::now();
        check(writer.writeRecord(header.data(), header.size(), packet.data, packet.size,
            offset, reservation), "production record write");
        const auto ms = std::chrono::duration<double, std::milli>(Clock::now() - started).count();
        elapsed.push_back(ms);
        total += ms;
        maximum = std::max(maximum, ms);
        if (ms > 40.0) ++above40;
        timespec now{};
        check(::clock_gettime(CLOCK_MONOTONIC, &now) == 0, "completion timestamp");
        const auto completedNs = static_cast<std::int64_t>(now.tv_sec) * 1'000'000'000LL + now.tv_nsec;
        if (completedNs > deadline + kPeriodNs) ++lateFrames;
        offset += static_cast<std::int64_t>(header.size() + packet.size);
        if (frame == 0u || (frame + 1u) % 120u == 0u) {
            const auto syncStart = Clock::now();
            check(::fdatasync(file.fd) == 0, "periodic durability");
            syncMaximum = std::max(syncMaximum,
                std::chrono::duration<double, std::milli>(Clock::now() - syncStart).count());
        }
        if ((frame + 1u) % 250u == 0u)
            __android_log_print(ANDROID_LOG_INFO, "Npv10WriterReplay",
                "REAL_PACKET_REPLAY_PROGRESS frames=%u max_ms=%.6f above_40ms=%u uid=%u",
                frame + 1u, maximum, above40, static_cast<unsigned>(::getuid()));
    }
    check(offset == logicalEnd && writer.kernelAioSubmissions() == writer.kernelAioCompletions(),
        "all submitted bytes completed");
    check(writer.close() && ::ftruncate64(file.fd, logicalEnd) == 0 && ::fsync(file.fd) == 0,
        "final retirement and durability");
    std::vector<std::uint8_t> review(std::max(prefix.size(), largest + kHeaderBytes));
    readExact(file.fd, review.data(), prefix.size(), 0);
    check(std::memcmp(review.data(), prefix.data(), prefix.size()) == 0, "prefix equality");
    offset = kPrefix;
    for (unsigned frame = 0; frame < frames; ++frame) {
        const auto& packet = packets[frame % packets.size()];
        std::array<std::uint8_t, kHeaderBytes> expected{};
        frameHeader(expected, frame);
        const auto bytes = kHeaderBytes + packet.size;
        readExact(file.fd, review.data(), bytes, offset);
        check(std::memcmp(review.data(), expected.data(), kHeaderBytes) == 0 &&
            std::memcmp(review.data() + kHeaderBytes, packet.data, packet.size) == 0,
            "complete file byte equality and frame ordering");
        offset += static_cast<std::int64_t>(bytes);
    }
    check(offset == logicalEnd, "readback covers every persisted byte");
    std::sort(elapsed.begin(), elapsed.end());
    std::ostringstream report;
    report.precision(9);
    report << "REPLAY_RESULT context=actual_app_uid uid=" << ::getuid()
        << " backend=" << backend << " frames=" << frames << " bytes=" << logicalEnd
        << " mean_ms=" << total / frames << " max_ms=" << maximum
        << " p99_ms=" << elapsed[(elapsed.size() - 1u) * 99u / 100u]
        << " writes_above_40ms=" << above40 << " paced_deadline_misses=" << lateFrames
        << " aio_partial_submissions=" << writer.kernelAioPartialSubmissions()
        << " aio_backpressure_events=" << writer.kernelAioBackpressureEvents()
        << " observed_completion_waits=" << ReplayCompletionObservations::waits
        << " observed_full_group_waits=" << ReplayCompletionObservations::fullGroupWaits
        << " observed_returned_events=" << ReplayCompletionObservations::returnedEvents
        << " max_sync_ms=" << syncMaximum << " all_bytes_exact=true full_file_readback=true"
        << " camera_frames=NOT_TESTED audio=NOT_TESTED source_packets=REAL_RECORDED"
        << " checkpoint_pass=false";
    return report.str();
}
} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10RecordedWriterReplayInstrumentedTest_nativeReplay(
    JNIEnv* env, jclass, jstring directory, jobjectArray suppliedPackets, jint frames, jint depth) {
    if (directory == nullptr || suppliedPackets == nullptr)
        return env->NewStringUTF("REPLAY_ERROR missing real input");
    const char* supplied = env->GetStringUTFChars(directory, nullptr);
    if (supplied == nullptr) return nullptr;
    std::string path;
    try { path = supplied; }
    catch (...) {
        env->ReleaseStringUTFChars(directory, supplied);
        return env->NewStringUTF("REPLAY_ERROR path allocation");
    }
    env->ReleaseStringUTFChars(directory, supplied);
    try {
        const auto count = env->GetArrayLength(suppliedPackets);
        check(count > 0 && count <= 32 && frames > 0, "packet array bound");
        std::vector<Packet> packets;
        packets.reserve(static_cast<std::size_t>(count));
        for (jsize index = 0; index < count; ++index) {
            jobject buffer = env->GetObjectArrayElement(suppliedPackets, index);
            check(buffer != nullptr && !env->ExceptionCheck(), "real packet reference");
            const auto* bytes = static_cast<std::uint8_t*>(env->GetDirectBufferAddress(buffer));
            const auto capacity = env->GetDirectBufferCapacity(buffer);
            env->DeleteLocalRef(buffer);
            check(bytes != nullptr && capacity > 0 && capacity <= 32LL * 1024LL * 1024LL,
                "exact bounded direct packet buffer");
            packets.push_back({bytes, static_cast<std::size_t>(capacity)});
        }
        using Proposed = neoproc::npv10::AlignedRecordWriter<neoproc::npv10::AlignedRecordPosixIo,
            8u * 1024u * 1024u, false, 1u, true, neoproc::npv10::KernelAioPosix,
            8u * 1024u * 1024u, true>;
        using ProposedEight = neoproc::npv10::AlignedRecordWriter<neoproc::npv10::AlignedRecordPosixIo,
            8u * 1024u * 1024u, false, 1u, true, neoproc::npv10::KernelAioPosix,
            8u * 1024u * 1024u, true, 8u>;
        using Pipelined = neoproc::npv10::AlignedRecordWriter<neoproc::npv10::AlignedRecordPosixIo,
            8u * 1024u * 1024u, true, 1u, true>;
        using SingleCompletion = neoproc::npv10::AlignedRecordWriter<neoproc::npv10::AlignedRecordPosixIo,
            8u * 1024u * 1024u, false, 1u, true, ReplayCompletionAio<false>>;
        using BatchedCompletion = neoproc::npv10::AlignedRecordWriter<neoproc::npv10::AlignedRecordPosixIo,
            8u * 1024u * 1024u, false, 1u, true, ReplayCompletionAio<true>,
            8u * 1024u * 1024u, false, 4u, true>;
        check(depth == 0 || depth == 4 || depth == 8 || depth == 16 || depth == 32 || depth == 64,
            "invalid measured writer");
        const auto result = depth == 32
            ? replay<SingleCompletion>(path, packets, static_cast<unsigned>(frames), "single_completion_baseline")
            : depth == 64
            ? replay<BatchedCompletion>(path, packets, static_cast<unsigned>(frames), "batched_completion_candidate")
            : depth == 16
            ? replay<Pipelined>(path, packets, static_cast<unsigned>(frames), "pipelined_copy_aio_candidate")
            : depth == 8
            ? replay<ProposedEight>(path, packets, static_cast<unsigned>(frames), "bounded_stream8_candidate")
            : depth == 4
            ? replay<Proposed>(path, packets, static_cast<unsigned>(frames), "bounded_stream_candidate")
            : replay<neoproc::npv10::RealtimeAlignedRecordWriter>(path, packets,
                static_cast<unsigned>(frames), "production");
        return env->NewStringUTF(result.c_str());
    } catch (const std::exception& failure) {
        const auto result = std::string("REPLAY_ERROR ") + failure.what();
        return env->NewStringUTF(result.c_str());
    }
}
