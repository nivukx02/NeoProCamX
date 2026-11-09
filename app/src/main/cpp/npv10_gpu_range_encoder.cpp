#include "npv10_gpu_range_encoder.h"
#include "npv10_gpu_range_blocks.h"
#include "npv10_gpu_range_shader.h"
#include "npv10_gpu_range_compact_shader.h"

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl31.h>
#include <android/log.h>
#include <android/trace.h>
#include <algorithm>
#include <chrono>
#include <cstring>
#include <limits>
#include <mutex>

namespace neoproc::npv10::lossless {
namespace {
using Clock = std::chrono::steady_clock;
std::int64_t elapsed(Clock::time_point start) noexcept {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(Clock::now() - start).count();
}
constexpr std::int64_t kMaximumSamples = 4096LL * 3072LL * 3LL / 2LL;

bool shape(std::int32_t width, std::int32_t height, std::int64_t* samples) noexcept {
    if (width <= 0 || height <= 0 || (width & 63) != 0 || (height & 1) != 0) return false;
    const auto luma = std::int64_t{width} * height;
    if (luma > kMaximumSamples / 3 * 2) return false;
    *samples = luma + luma / 2;
    return true;
}

class CurrentContext final {
public:
    CurrentContext(EGLDisplay display, EGLSurface surface, EGLContext context, bool* poisoned) noexcept
        : display_(display), previousDisplay_(eglGetCurrentDisplay()),
          previousContext_(eglGetCurrentContext()),
          previousDraw_(eglGetCurrentSurface(EGL_DRAW)),
          previousRead_(eglGetCurrentSurface(EGL_READ)), previousApi_(eglQueryAPI()), poisoned_(poisoned) {
        active_ = eglBindAPI(EGL_OPENGL_ES_API) == EGL_TRUE &&
            eglMakeCurrent(display, surface, surface, context) == EGL_TRUE;
    }
    ~CurrentContext() { (void)restore(); }
    bool restore() noexcept {
        if (restored_) return restoreSucceeded_;
        restored_ = true;
        bool restored = true;
        if (active_) {
            if (previousContext_ == EGL_NO_CONTEXT) {
                restored = eglMakeCurrent(display_, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT) == EGL_TRUE;
            } else {
                restored = eglBindAPI(previousApi_) == EGL_TRUE &&
                    eglMakeCurrent(previousDisplay_, previousDraw_, previousRead_, previousContext_) == EGL_TRUE;
            }
        }
        if (eglBindAPI(previousApi_) != EGL_TRUE) restored = false;
        active_ = false;
        restoreSucceeded_ = restored;
        if (!restored) {
            if (poisoned_ != nullptr) *poisoned_ = true;
            __android_log_print(ANDROID_LOG_ERROR, "Npv10GpuRange",
                "Caller EGL restoration failed error=0x%x; encoder retired", eglGetError());
        }
        return restored;
    }
    bool active() const noexcept { return active_; }
private:
    EGLDisplay display_, previousDisplay_;
    EGLContext previousContext_;
    EGLSurface previousDraw_, previousRead_;
    EGLenum previousApi_;
    bool active_ = false;
    bool restored_ = false, restoreSucceeded_ = false;
    bool* poisoned_ = nullptr;
};

class GpuRangeEncoder final {
public:
    bool prepare(std::int32_t width, std::int32_t height) noexcept {
        std::lock_guard<std::mutex> lock(mutex_);
        std::int64_t samples = 0;
        if (!shape(width, height, &samples) || poisoned_) return false;
        if (!createContext()) return false;
        CurrentContext current(display_, surface_, context_, &poisoned_);
        if (!current.active()) return false;
        if (program_ == 0u && !createProgram()) return false;
        const auto inputBytes = std::max(samples * 2, compactBytesFor(samples));
        const auto outputBytes = samples / 64 * GpuRangeBlocksView::kWordsPerBlock * 4;
        GLint64 maximumBlockBytes = 0;
        glGetInteger64v(GL_MAX_SHADER_STORAGE_BLOCK_SIZE, &maximumBlockBytes);
        if (inputBytes > maximumBlockBytes || static_cast<std::int64_t>(outputBytes) > maximumBlockBytes)
            return false;
        if (buffers_[0] == 0u) glGenBuffers(2, buffers_);
        if (buffers_[0] == 0u || buffers_[1] == 0u) return false;
        if (samples > capacitySamples_) {
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffers_[0]);
            glBufferData(GL_SHADER_STORAGE_BUFFER, static_cast<GLsizeiptr>(inputBytes), nullptr, GL_DYNAMIC_COPY);
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffers_[1]);
            glBufferData(GL_SHADER_STORAGE_BUFFER, static_cast<GLsizeiptr>(outputBytes), nullptr, GL_DYNAMIC_COPY);
            if (glGetError() != GL_NO_ERROR) { poisoned_ = true; return false; }
            capacitySamples_ = samples;
        }
        __android_log_print(ANDROID_LOG_INFO, "Npv10GpuRange",
            "INTEGER_GPU_RANGE_READY samples=%lld workspaceBytes=%lld renderer=%s liveRoute=NOT_ENABLED",
            static_cast<long long>(capacitySamples_), static_cast<long long>(inputBytes + outputBytes),
            reinterpret_cast<const char*>(glGetString(GL_RENDERER)));
        const bool healthy = glGetError() == GL_NO_ERROR;
        return current.restore() && healthy;
    }

    EncodeResult encode(const GpuP010Input& input, std::uint8_t* packet, std::int64_t capacity,
                        int acceleration, OriginalP010Packer packOriginal, GpuRangeTimings* timings) noexcept {
        const auto start = Clock::now();
        struct CompleteTiming {
            Clock::time_point start; GpuRangeTimings* target;
            ~CompleteTiming() { if (target != nullptr) target->totalNs = elapsed(start); }
        } complete{start, timings};
        if (timings != nullptr) *timings = {};
        std::int64_t samples = 0;
        if (!shape(input.width, input.height, &samples) || input.y == nullptr || input.uv == nullptr ||
                packet == nullptr || packOriginal == nullptr || acceleration < 1 || acceleration > 65537)
            return {};
        const auto rowBytes = std::int64_t{input.width} * 2;
        if (input.yRowStride < rowBytes || input.uvRowStride < rowBytes ||
                input.yCapacity < std::int64_t{input.height - 1} * input.yRowStride + rowBytes ||
                input.uvCapacity < std::int64_t{input.height / 2 - 1} * input.uvRowStride + rowBytes ||
                capacity < maximumStoredBytes(samples / 4 * 5, samples)) return {};
        // The original CPU packer and GPU upload both need the input until dispatch preparation
        // finishes. Do not accept aliasing that could overwrite the original before its CPU CRC.
        const auto disjoint = [](const void* a, std::int64_t aBytes, const void* b, std::int64_t bBytes) {
            const auto first = reinterpret_cast<std::uintptr_t>(a);
            const auto second = reinterpret_cast<std::uintptr_t>(b);
            return aBytes > 0 && bBytes > 0 &&
                static_cast<std::uint64_t>(aBytes) <= std::numeric_limits<std::uintptr_t>::max() - first &&
                static_cast<std::uint64_t>(bBytes) <= std::numeric_limits<std::uintptr_t>::max() - second &&
                (first <= second ? static_cast<std::uint64_t>(aBytes) <= second - first
                                 : static_cast<std::uint64_t>(bBytes) <= first - second);
        };
        if (!disjoint(input.y, input.yCapacity, packet, capacity) ||
                !disjoint(input.uv, input.uvCapacity, packet, capacity)) return {};
        std::lock_guard<std::mutex> lock(mutex_);
        if (poisoned_ || samples > capacitySamples_ || program_ == 0u) return {};
        CurrentContext current(display_, surface_, context_, &poisoned_);
        if (!current.active()) { poisoned_ = true; return {}; }
        const auto inputBytes = static_cast<GLsizeiptr>(samples * 2);
        const auto outputWords = static_cast<std::size_t>(compactBytesFor(samples) / 4);
        const auto outputBytes = static_cast<GLsizeiptr>(outputWords * sizeof(std::uint32_t));
        const auto uploadStarted = Clock::now();
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffers_[0]);
        // Previous dispatch has been fully fenced before this workspace becomes reusable.
        auto* mappedInput = static_cast<std::uint8_t*>(glMapBufferRange(GL_SHADER_STORAGE_BUFFER,
            0, inputBytes, GL_MAP_WRITE_BIT | GL_MAP_UNSYNCHRONIZED_BIT));
        if (mappedInput == nullptr) { poisoned_ = true; return {}; }
        std::size_t cursor = 0u;
        for (std::int32_t row = 0; row < input.height; ++row) {
            std::memcpy(mappedInput + cursor, input.y + std::int64_t{row} * input.yRowStride,
                static_cast<std::size_t>(rowBytes));
            cursor += static_cast<std::size_t>(rowBytes);
        }
        for (std::int32_t row = 0; row < input.height / 2; ++row) {
            std::memcpy(mappedInput + cursor, input.uv + std::int64_t{row} * input.uvRowStride,
                static_cast<std::size_t>(rowBytes));
            cursor += static_cast<std::size_t>(rowBytes);
        }
        if (glUnmapBuffer(GL_SHADER_STORAGE_BUFFER) != GL_TRUE) { poisoned_ = true; return {}; }
        if (timings != nullptr) timings->uploadNs = elapsed(uploadStarted);
        glUseProgram(program_);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0u, buffers_[0]);
        glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 1u, buffers_[1]);
        ++frameToken_; // Bounded serial workspace; no in-flight dispatch can survive into a new token.
        glUniform1ui(blockCountLocation_, static_cast<GLuint>(samples / 64));
        glUniform1ui(frameTokenLocation_, frameToken_);
        ATrace_beginSection("NPV10 integer GPU range dispatch");
        const auto groups = static_cast<GLuint>((samples / 64 + 63) / 64);
        glDispatchCompute(groups, 1u, 1u);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
        // Range generation no longer reads the original upload after this boundary. Compact
        // into that same buffer while CPU packing reads the separate caller-owned originals.
        glUseProgram(compactProgram_);
        glUniform1ui(compactSampleCountLocation_, static_cast<GLuint>(samples));
        glUniform1ui(compactFrameTokenLocation_, frameToken_);
        glDispatchCompute(static_cast<GLuint>((samples + kChunkSamples - 1) / kChunkSamples), 1u, 1u);
        glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_BUFFER_UPDATE_BARRIER_BIT);
        GLsync fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0u);
        glFlush();
        ATrace_endSection();
        if (fence == nullptr || glGetError() != GL_NO_ERROR) {
            if (fence != nullptr) glDeleteSync(fence);
            poisoned_ = true;
            return {};
        }
        // CPU canonical packing/checksum overlaps GPU range work. GPU never supplies its own
        // source-of-truth CRC; a wrong residual fails the unchanged full-source CRC audit.
        std::uint32_t sourceCrc = 0u;
        const auto packStarted = Clock::now();
        ATrace_beginSection("NPV10 GPU original CPU pack and CRC");
        const bool packed = packOriginal(input, packet, capacity, &sourceCrc);
        ATrace_endSection();
        if (timings != nullptr) timings->originalPackAndCrcNs = elapsed(packStarted);
        const auto waitStarted = Clock::now();
        bool completed = false;
        ATrace_beginSection("NPV10 GPU complete fence wait");
        for (;;) {
            const auto status = glClientWaitSync(fence, GL_SYNC_FLUSH_COMMANDS_BIT, 2'000'000u);
            if (status == GL_ALREADY_SIGNALED || status == GL_CONDITION_SATISFIED) { completed = true; break; }
            if (status == GL_WAIT_FAILED || elapsed(waitStarted) >= 1'000'000'000LL) break;
        }
        ATrace_endSection();
        glDeleteSync(fence);
        if (timings != nullptr) timings->completionWaitNs = elapsed(waitStarted);
        if (!completed || !packed || glGetError() != GL_NO_ERROR) { poisoned_ = true; return {}; }
        glBindBuffer(GL_SHADER_STORAGE_BUFFER, buffers_[0]);
        const auto* mappedOutput = static_cast<const std::uint32_t*>(glMapBufferRange(
            GL_SHADER_STORAGE_BUFFER, 0, outputBytes, GL_MAP_READ_BIT));
        if (mappedOutput == nullptr) { poisoned_ = true; return {}; }
        const auto assemblyStarted = Clock::now();
        ATrace_beginSection("NPV10 GPU exact NLC2 packet assembly");
        const auto slotWords = static_cast<std::size_t>(std::min<std::int64_t>(samples, kChunkSamples) / 16 * 5 + 4);
        const GpuRangeBlocksView blocks{mappedOutput, outputWords, frameToken_, slotWords};
        auto result = compressPackedTenBitGpuRangeLz4InPlace(packet, samples / 4 * 5, samples,
            capacity, sourceCrc, acceleration, blocks);
        const bool unmapped = glUnmapBuffer(GL_SHADER_STORAGE_BUFFER) == GL_TRUE;
        ATrace_endSection();
        if (timings != nullptr) timings->assemblyNs = elapsed(assemblyStarted);
        if (!unmapped || glGetError() != GL_NO_ERROR || !result.successful) {
            poisoned_ = true;
            return {};
        }
        if (!current.restore()) return {};
        return result;
    }

    void close() noexcept {
        std::lock_guard<std::mutex> lock(mutex_);
        if (context_ != EGL_NO_CONTEXT) {
            {
                CurrentContext current(display_, surface_, context_, &poisoned_);
                if (current.active()) {
                    // Retire any exceptional timed-out dispatch before releasing its GPU buffers.
                    glFinish();
                    if (program_ != 0u) glDeleteProgram(program_);
                    if (compactProgram_ != 0u) glDeleteProgram(compactProgram_);
                    glDeleteBuffers(2, buffers_);
                }
            }
            (void)eglDestroyContext(display_, context_);
        }
        if (surface_ != EGL_NO_SURFACE) (void)eglDestroySurface(display_, surface_);
        // EGL_DEFAULT_DISPLAY is shared with live preview: never eglTerminate it here.
        context_ = EGL_NO_CONTEXT; surface_ = EGL_NO_SURFACE; display_ = EGL_NO_DISPLAY;
        program_ = compactProgram_ = 0u; buffers_[0] = buffers_[1] = 0u; capacitySamples_ = 0; poisoned_ = false;
    }

private:
    static std::int64_t compactBytesFor(std::int64_t samples) noexcept {
        const auto slots = (samples + kChunkSamples - 1) / kChunkSamples;
        const auto slotBytes = std::min<std::int64_t>(samples, kChunkSamples) / 4 * 5 + 16;
        return slots * slotBytes;
    }
    bool createContext() noexcept {
        if (context_ != EGL_NO_CONTEXT) return true;
        display_ = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        if (display_ == EGL_NO_DISPLAY || eglInitialize(display_, nullptr, nullptr) != EGL_TRUE) return false;
        const EGLint attributes[] = {EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE,
            EGL_OPENGL_ES3_BIT_KHR, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_NONE};
        EGLConfig config{}; EGLint count = 0;
        if (eglChooseConfig(display_, attributes, &config, 1, &count) != EGL_TRUE || count != 1) return false;
        const EGLint contextAttributes[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
        const auto previousApi = eglQueryAPI();
        if (eglBindAPI(EGL_OPENGL_ES_API) != EGL_TRUE) return false;
        context_ = eglCreateContext(display_, config, EGL_NO_CONTEXT, contextAttributes);
        (void)eglBindAPI(previousApi);
        if (context_ == EGL_NO_CONTEXT) return false;
        const EGLint surfaceAttributes[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
        surface_ = eglCreatePbufferSurface(display_, config, surfaceAttributes);
        if (surface_ == EGL_NO_SURFACE) {
            (void)eglDestroyContext(display_, context_); context_ = EGL_NO_CONTEXT; return false;
        }
        return true;
    }
    bool createProgram() noexcept {
        GLint major = 0, minor = 0, bindings = 0, invocations = 0, sharedBytes = 0, groupWidth = 0;
        glGetIntegerv(GL_MAJOR_VERSION, &major); glGetIntegerv(GL_MINOR_VERSION, &minor);
        glGetIntegerv(GL_MAX_SHADER_STORAGE_BUFFER_BINDINGS, &bindings);
        glGetIntegerv(GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS, &invocations);
        glGetIntegerv(GL_MAX_COMPUTE_SHARED_MEMORY_SIZE, &sharedBytes);
        glGetIntegeri_v(GL_MAX_COMPUTE_WORK_GROUP_SIZE, 0u, &groupWidth);
        if (major < 3 || (major == 3 && minor < 1) || bindings < 2 || invocations < 128 ||
            sharedBytes < (4224 + 129 + 128 + 1) * 4 || groupWidth < 128 || glGetError() != GL_NO_ERROR) return false;
        program_ = linkComputeProgram(kP010RangeComputeShader);
        compactProgram_ = linkComputeProgram(kP010RangeCompactShader);
        if (program_ == 0u || compactProgram_ == 0u) {
            if (program_ != 0u) glDeleteProgram(program_);
            if (compactProgram_ != 0u) glDeleteProgram(compactProgram_);
            program_ = compactProgram_ = 0u;
            return false;
        }
        blockCountLocation_ = glGetUniformLocation(program_, "blockCount");
        frameTokenLocation_ = glGetUniformLocation(program_, "frameToken");
        compactSampleCountLocation_ = glGetUniformLocation(compactProgram_, "sampleCount");
        compactFrameTokenLocation_ = glGetUniformLocation(compactProgram_, "frameToken");
        if (blockCountLocation_ < 0 || frameTokenLocation_ < 0 || compactSampleCountLocation_ < 0 ||
                compactFrameTokenLocation_ < 0 || glGetError() != GL_NO_ERROR) {
            glDeleteProgram(program_); glDeleteProgram(compactProgram_);
            program_ = compactProgram_ = 0u; return false;
        }
        return true;
    }

    static GLuint linkComputeProgram(const char* source) noexcept {
        const auto shader = glCreateShader(GL_COMPUTE_SHADER);
        if (shader == 0u) return false;
        glShaderSource(shader, 1, &source, nullptr); glCompileShader(shader);
        GLint compiled = 0; glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
        if (compiled != GL_TRUE) {
            char detail[1024]{}; glGetShaderInfoLog(shader, sizeof(detail), nullptr, detail);
            __android_log_print(ANDROID_LOG_ERROR, "Npv10GpuRange", "Shader admission failed: %s", detail);
            glDeleteShader(shader); return false;
        }
        const auto program = glCreateProgram();
        if (program == 0u) { glDeleteShader(shader); return 0u; }
        glAttachShader(program, shader); glLinkProgram(program); glDeleteShader(shader);
        GLint linked = 0; glGetProgramiv(program, GL_LINK_STATUS, &linked);
        if (linked != GL_TRUE) { glDeleteProgram(program); return 0u; }
        return program;
    }
    std::mutex mutex_;
    EGLDisplay display_ = EGL_NO_DISPLAY;
    EGLContext context_ = EGL_NO_CONTEXT;
    EGLSurface surface_ = EGL_NO_SURFACE;
    GLuint program_ = 0u, compactProgram_ = 0u, buffers_[2]{};
    GLint blockCountLocation_ = -1, frameTokenLocation_ = -1;
    GLint compactSampleCountLocation_ = -1, compactFrameTokenLocation_ = -1;
    std::int64_t capacitySamples_ = 0;
    std::uint32_t frameToken_ = 0u;
    bool poisoned_ = false;
};

GpuRangeEncoder& engine() { static GpuRangeEncoder instance; return instance; }
} // namespace

bool prepareGpuRangeEncoder(std::int32_t width, std::int32_t height) noexcept {
    return engine().prepare(width, height);
}
EncodeResult encodeP010WithGpuRange(const GpuP010Input& input, std::uint8_t* packet,
    std::int64_t capacity, int acceleration, OriginalP010Packer packOriginal,
    GpuRangeTimings* timings) noexcept {
    return engine().encode(input, packet, capacity, acceleration, packOriginal, timings);
}
void closeGpuRangeEncoder() noexcept { engine().close(); }
} // namespace neoproc::npv10::lossless
