// Debug-only JNI probe: shell AIO support is NOT proof of application seccomp/filesystem support.
// No recording backend uses this library. It only touches its own mkstemp files in the supplied
// instrumentation-owned cache directory. Every successful write is read back in full.
#include <jni.h>
#include <signal.h>
#include <sys/wait.h>
#include <sstream>
#include <string>
#include <thread>
#include "../../main/cpp/npv10_record_io_priority.h"

#define main camxShellStorageProbeMain
#include "../../../../tools/lq001_native_aio_device_probe.cpp"
#undef main

extern "C" JNIEXPORT jint JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10KernelAioInstrumentedTest_nativeProbeCapabilities(
    JNIEnv*, jclass) {
    // Only async-signal-safe operations are performed in the child of this multithreaded app.
    // A denied syscall can SIGSYS the child without terminating the instrumentation/app process.
    const pid_t child = ::fork();
    if (child < 0) return 2000 + errno;
    if (child == 0) {
        (void)::alarm(2u);
        aio_context_t handle = 0;
        if (::syscall(__NR_io_setup, kDepth, &handle) != 0) ::_exit(20);
        iocb* requests[1]{};
        io_event events[1]{};
        timespec zero{};
        int result = 0;
        // Even zero-request calls execute the app's syscall filter. No file/buffer is submitted.
        if (::syscall(__NR_io_submit, handle, 0, requests) < 0) result = 21;
        if (::syscall(__NR_io_getevents, handle, 0, 0, events, &zero) < 0) result = 22;
        if (::syscall(__NR_io_destroy, handle) != 0) result = 23;
        ::_exit(result);
    }
    int status = 0;
    for (unsigned attempt = 0; attempt < 300u; ++attempt) {
        const auto waited = ::waitpid(child, &status, WNOHANG);
        if (waited == child) {
            if (WIFEXITED(status)) return WEXITSTATUS(status);
            if (WIFSIGNALED(status)) return 1000 + WTERMSIG(status);
            return 3001;
        }
        if (waited < 0 && errno != EINTR) return 2000 + errno;
        timespec interval{0, 10000000};
        while (::nanosleep(&interval, &interval) != 0 && errno == EINTR) {}
    }
    (void)::kill(child, SIGKILL);
    while (::waitpid(child, &status, 0) < 0 && errno == EINTR) {}
    return 3002;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10KernelAioInstrumentedTest_nativeCompareStorage(
    JNIEnv* env, jclass, jstring directory) {
    if (directory == nullptr) return env->NewStringUTF("FAIL null cache directory");
    const char* supplied = env->GetStringUTFChars(directory, nullptr);
    if (supplied == nullptr) return nullptr;
    std::string path;
    try { path = supplied; }
    catch (...) {
        env->ReleaseStringUTFChars(directory, supplied);
        return env->NewStringUTF("FAIL cache path allocation");
    }
    env->ReleaseStringUTFChars(directory, supplied);
    try {
        Buffer source(kChunkBytes), readback(kChunkBytes);
        OwnedFile baseline(path.c_str()), candidate(path.c_str());
        NativeContext context;
        check(context.available, "kernel AIO unavailable in actual application context");
        baseline.prepare();
        candidate.prepare();
        auto* bytes = static_cast<std::uint8_t*>(source.data);
        std::uint32_t state = 0x37fa520du;
        for (std::size_t i = 0; i < kChunkBytes; ++i) {
            state ^= state << 13u; state ^= state >> 17u; state ^= state << 5u;
            bytes[i] = static_cast<std::uint8_t>(state);
        }
        std::ostringstream report;
        report << "PASS context=actual_application uid=" << ::getuid()
            << " bytes_each=" << kFileBytes << " depth=" << kDepth;
        for (unsigned round = 0; round < 4u; ++round) {
            double synchronousMs = 0.0;
            double asynchronousMs = 0.0;
            if ((round & 1u) == 0u) {
                synchronousMs = measure(baseline, context, bytes, false, round);
                asynchronousMs = measure(candidate, context, bytes, true, round);
            } else {
                asynchronousMs = measure(candidate, context, bytes, true, round);
                synchronousMs = measure(baseline, context, bytes, false, round);
            }
            verify(baseline, bytes, readback);
            verify(candidate, bytes, readback);
            report << " round" << round << "_sync_ms=" << synchronousMs
                << " round" << round << "_aio_ms=" << asynchronousMs;
        }
        report << " all_bytes_exact=true durability_checked=true five_minute_fps=NOT_TESTED"
            << " production_backend=UNCHANGED";
        return env->NewStringUTF(report.str().c_str());
    } catch (const std::exception& failure) {
        const auto message = std::string("FAIL ") + failure.what();
        return env->NewStringUTF(message.c_str());
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_neoproc_neoproccamx_recording_Npv10RecordIoPriorityInstrumentedTest_nativeCheckOwnedWriterPriority(
    JNIEnv* env, jclass) {
    using Io = neoproc::npv10::RecordIoPriorityPosix;
    using Priority = neoproc::npv10::ScopedRecordIoPriority<>;
    const int capability = Io::capability();
    if (capability != 0) {
        const auto result = std::string("UNAVAILABLE context=actual_app_uid capability_errno=") +
            std::to_string(capability) + " live_writer_syscalls=false";
        return env->NewStringUTF(result.c_str());
    }
    const long callerBefore = Io::getCurrent();
    std::string result;
    try {
        std::thread writer([&result] {
            try {
            const long before = Io::getCurrent();
            Priority priority;
            const bool applied = priority.applied();
            const long during = Io::getCurrent();
            const int error = priority.error();
            const bool restored = priority.restore();
            const long after = Io::getCurrent();
            const bool correct = before >= 0 && error == 0 && restored && after == before &&
                (applied ? during == Priority::kRequested : during == before);
            std::ostringstream report;
            report << (correct ? "PASS" : "FAIL") << " context=actual_app_uid uid=" << ::getuid()
                << " before=" << before << " during=" << during << " after=" << after
                << " applied=" << (applied ? "true" : "false") << " errno=" << error
                << " restored=" << (restored ? "true" : "false")
                << " target=current_writer_thread quality_changed=false camera_fps=NOT_TESTED";
            result = report.str();
            } catch (...) {
                // Do not let report allocation failure terminate the test/app process.
                result.clear();
            }
        });
        writer.join();
        if (callerBefore < 0 || Io::getCurrent() != callerBefore)
            return env->NewStringUTF("FAIL caller_thread_priority_changed");
    } catch (...) {
        return env->NewStringUTF("FAIL owned_writer_thread_setup");
    }
    return env->NewStringUTF(result.empty() ? "FAIL writer_priority_report" : result.c_str());
}
