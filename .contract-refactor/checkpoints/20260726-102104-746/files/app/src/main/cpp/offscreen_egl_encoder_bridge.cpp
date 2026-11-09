#include <jni.h>

#include <android/data_space.h>
#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2ext.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <dlfcn.h>
#include <mutex>
#include <sstream>
#include <string>
#include <unordered_map>
#include <vector>

#ifndef EGL_OPENGL_ES3_BIT_KHR
#define EGL_OPENGL_ES3_BIT_KHR 0x00000040
#endif

#ifndef EGL_RECORDABLE_ANDROID
#define EGL_RECORDABLE_ANDROID 0x3142
#endif

#ifndef EGL_GL_COLORSPACE_KHR
#define EGL_GL_COLORSPACE_KHR 0x309D
#endif

#ifndef EGL_GL_COLORSPACE_SRGB_KHR
#define EGL_GL_COLORSPACE_SRGB_KHR 0x3089
#endif

#ifndef EGL_GL_COLORSPACE_BT2020_HLG_EXT
#define EGL_GL_COLORSPACE_BT2020_HLG_EXT 0x3540
#endif

#ifndef EGL_GL_COLORSPACE_BT2020_PQ_EXT
#define EGL_GL_COLORSPACE_BT2020_PQ_EXT 0x3340
#endif

namespace {

constexpr const char* kTag = "OffscreenAhbEgl";

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, kTag, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, kTag, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, kTag, __VA_ARGS__)

using AHardwareBufferGetIdFn = int (*)(const AHardwareBuffer*, uint64_t*);

struct EglImageCacheEntry {
    EGLImageKHR image = EGL_NO_IMAGE_KHR;
};

// #2 decode-once: uniform locations for the sampler2D main-shader variant (parallel to the external
// program's individual location fields). Kept as a struct because there are two programs to locate.
struct MainProgramLoc {
    GLint texture = -1;
    GLint texel = -1;
    GLint sharpness = -1;
    GLint denoise = -1;
    GLint chromaDenoise = -1;
    GLint gradeMatrix = -1;
    GLint gradeContrast = -1;
    GLint lut = -1;
    GLint lutStrength = -1;
    GLint history = -1;
    GLint temporalBlend = -1;
    GLint historyValid = -1;
    GLint historyPreGrade = -1;
    GLint hdrToPqConvert = -1;
    GLint ndrDeliveryMode = -1;
    GLint ndrPqLut = -1;
    GLint ndrPqLutReady = -1;
    GLint inputPq = -1;             // 1.0 => camera input is PQ (HDR10/HDR10_PLUS); 0.0 => HLG
    GLint inputPrimaries = -1;      // 1 => BT.709, 2 => BT.2020
    GLint inputRange = -1;          // 1 => full, 2 => limited, 3 => extended
    GLint ndrWarmSkinRedTame = -1;  // Proprietary NDR scene-aware warm/red neutral correction.
    GLint encOutFlip = -1;          // MIRRORFIX v5 — per-axis output flip (see kVertexShader)
};

struct OffscreenAhbEglEncoderState {
    std::mutex mutex;
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLConfig config = nullptr;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface encoderSurface = EGL_NO_SURFACE;
    ANativeWindow* encoderWindow = nullptr;
    int surfaceWidth = 0;
    int surfaceHeight = 0;
    EGLint redBits = 0;
    EGLint greenBits = 0;
    EGLint blueBits = 0;
    EGLint alphaBits = 0;
    EGLint recordable = EGL_FALSE;
    EGLint surfaceColorSpace = EGL_NONE;
    int32_t bufferDataSpace = ADATASPACE_UNKNOWN;
    bool bufferDataSpaceApplied = false;
    std::string deliveryModeLabel = "UNKNOWN";
    std::string deliveryColorSpaceLabel = "UNKNOWN";
    bool deliveryCompatible = false;
    std::string sourceFormat = "UNDEFINED";
    int sourceBitDepth = 0;
    std::string sourcePrecision = "UNKNOWN";
    bool sourcePrecisionProven = false;
    GLuint program = 0;
    GLuint vertexBuffer = 0;
    GLuint sourceTexture = 0;
    GLint textureLocation = -1;
    GLint texelLocation = -1;
    GLint sharpnessLocation = -1;
    GLint denoiseLocation = -1;
    GLint chromaDenoiseLocation = -1;
    // Record-grade stage locations + state (all identity/no-op by default).
    GLint gradeMatrixLocation = -1;
    GLint gradeContrastLocation = -1;
    float recordGradeMatrix[9] = {
        1.0f, 0.0f, 0.0f,
        0.0f, 1.0f, 0.0f,
        0.0f, 0.0f, 1.0f
    };
    float recordGradeContrast = 1.0f;
    GLint lutLocation = -1;
    GLint lutStrengthLocation = -1;
    GLint historyLocation = -1;
    GLint temporalBlendLocation = -1;
    GLint historyValidLocation = -1;
    GLint historyPreGradeLocation = -1;
    // NDR true-PQ: 1.0 => convert the final HLG-encoded pixel to genuine ST.2084 PQ before write
    // (set only when the delivery surface is BT.2020_PQ). 0.0 => passthrough (HLG-coherent).
    GLint hdrToPqConvertLocation = -1;
    // NDR10+/NIVUKX delivery mode (0.0 => NDR10+, 1.0 => NIVUKX Vision) for the tone operator.
    GLint ndrDeliveryModeLocation = -1;
    GLint ndrPqLutLocation = -1;
    GLint ndrPqLutReadyLocation = -1;
    // NDR10+/NIVUKX input-transfer decode is selected per submitted Image dataspace.
    GLint inputPqLocation = -1;
    GLint ndrWarmSkinRedTameLocation = -1;
    int lastInputTransfer = 0;   // 0=SDR, 1=PQ, 2=HLG
    int lastInputPrimaries = 0;  // 0=unknown, 1=BT.709, 2=BT.2020
    int lastInputRange = 0;      // 0=unknown, 1=full, 2=limited, 3=extended
    // MIRRORFIX v5 — orientation-correct record un-mirror (uEncOutFlip in the single-pass main program).
    // encOutFlipX/Y are 0.0 (no flip) or 1.0 (flip that texcoord axis). Default flipY=1 un-mirrors the
    // portrait case: the recorded frame displays upright but horizontally mirrored (raw camera buffer
    // sampled without the preview's SurfaceTexture transform), and — because the container applies a 90°
    // rotation — a DISPLAYED-horizontal mirror is removed by flipping the ENCODED-vertical (T) axis.
    // Written under state->mutex by nativeSetEncoderOutputFlip; read on the record thread.
    GLint encOutFlipLocation = -1;
    float encOutFlipX = 0.0f;
    float encOutFlipY = 1.0f;
    GLuint lutTexture = 0;          // real 3D creative LUT (0 => bind default, strength forced 0)
    GLuint defaultLut3d = 0;        // 1x1x1 complete sampler3D so the shader is never incomplete
    GLuint ndrPqLutNdr10 = 0;       // 65^3 RGB16F NDR10+ HLG-domain -> PQ delivery LUT
    GLuint ndrPqLutNivukx = 0;      // 65^3 RGB16F NIVUKX HLG-domain -> PQ delivery LUT
    bool ndrPqLutsReady = false;
    GLuint historyTexture = 0;      // previous rendered output for temporal blend
    GLuint historyWriteTexture = 0; // current rendered output, swapped into history after present
    GLuint historyFbo = 0;          // RGB10_A2 temporal-history render target
    GLuint defaultHistory2d = 0;    // 1x1 complete sampler2D
    int lutSize = 0;
    float lutStrength = 0.0f;
    bool lutDirty = false;
    std::vector<float> pendingLutData;
    int historyWidth = 0;
    int historyHeight = 0;
    bool historyValid = false;
    PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC eglGetNativeClientBufferANDROID = nullptr;
    PFNEGLCREATEIMAGEKHRPROC eglCreateImageKHR = nullptr;
    PFNEGLDESTROYIMAGEKHRPROC eglDestroyImageKHR = nullptr;
    PFNEGLPRESENTATIONTIMEANDROIDPROC eglPresentationTimeANDROID = nullptr;
    PFNGLEGLIMAGETARGETTEXTURE2DOESPROC glEGLImageTargetTexture2DOES = nullptr;
    // #5 (device-gated): reuse EGLImageKHR by stable AHardwareBuffer id instead of creating/destroying
    // one every frame. AHardwareBuffer_fromHardwareBuffer can hand back a fresh native wrapper for the
    // same camera slot, so raw AHardwareBuffer* is only a pre-API-31 fallback. Each cache entry holds ONE
    // extra AHardwareBuffer ref (released on eviction / teardown) so the graphic buffer stays alive while
    // its image is cached.
    bool eglImageCacheEnabled = false;
    AHardwareBufferGetIdFn ahardwareBufferGetId = nullptr;
    bool eglImageCacheStableIds = false;
    std::unordered_map<uint64_t, EglImageCacheEntry> eglImageCache;
    std::vector<uint64_t> eglImageCacheOrder;
    // #2 decode-once (default ON on the measured target): decode the external YUV texture ONCE per frame into
    // an RGB10_A2 texture, then run the grade/denoise/sharpen shader sampling that 10-bit
    // texture (sampler2D) instead of re-decoding the external texture 13x. Only engaged when
    // source-res == surface-res so the 10-bit sample lands on texel centres.
    bool decodeOnceEnabled = false;
    GLuint mainProgram2d = 0;       // main shader variant: sampler2D uTexture (same body as external)
    GLuint decodeProgram = 0;       // passthrough: samplerExternalOES -> RGB10_A2
    GLint decodeTexLoc = -1;        // uTexture location in decodeProgram
    GLuint mainProgramExplicitYuv = 0; // GL_EXT_YUV_target + project-owned matrix/range decode
    GLuint decodeProgramExplicitYuv = 0;
    GLint decodeExplicitYuvTexLoc = -1;
    GLint decodeExplicitYuvPrimariesLoc = -1;
    GLint decodeExplicitYuvRangeLoc = -1;
    MainProgramLoc mainLocExternal;
    MainProgramLoc mainLoc2d;       // uniform locations for mainProgram2d
    MainProgramLoc mainLocExplicitYuv;
    bool explicitYuvDecodeAvailable = false;
    uint64_t explicitYuvDecodeFrames = 0;
    GLuint decodeFbo = 0;
    GLuint decodeTexture = 0;       // RGB10_A2, source-res decode target
    GLuint decodeHistoryTexture = 0; // previous RGB10_A2 decoded source for temporal blend
    int decodeTexWidth = 0;
    int decodeTexHeight = 0;
    bool decodeHistoryValid = false;
    bool codecBackpressurePacing = true;
    uint64_t swapDurationTotalUs = 0;
    uint64_t swapDurationMaxUs = 0;
    uint64_t slowSwapFrames = 0;
    uint64_t submitDurationTotalUs = 0;
    uint64_t submitDurationMaxUs = 0;
    uint64_t slowSubmitFrames = 0;
    uint64_t historyPresentTotalUs = 0;
    uint64_t historyPresentMaxUs = 0;
    uint64_t slowHistoryPresentFrames = 0;
    uint64_t historyPresentErrorChecks = 0;
    uint64_t temporalHistoryFrames = 0;
    uint64_t decodeOnceFrames = 0;
    uint64_t externalPathFrames = 0;
    uint64_t sourceHistoryFrames = 0;
    uint64_t eglImageCacheHits = 0;
    uint64_t eglImageCacheMisses = 0;
    uint64_t eglImageCacheEvictions = 0;
    uint64_t eglImageCacheIdFailures = 0;
    uint64_t submittedFrames = 0;
    uint64_t failedFrames = 0;
    int64_t lastPresentationTimeNs = 0;
    std::string status = "created";
};

struct HardwareBufferSourceProof {
    std::string format;
    int bitDepth;
    std::string precision;
    bool proven;
};

using ANativeWindowSetBuffersDataSpaceFn = int32_t (*)(ANativeWindow*, int32_t);
using ANativeWindowGetBuffersDataSpaceFn = int32_t (*)(ANativeWindow*);

struct NativeWindowDataSpaceApi {
    ANativeWindowSetBuffersDataSpaceFn setBuffersDataSpace = nullptr;
    ANativeWindowGetBuffersDataSpaceFn getBuffersDataSpace = nullptr;
    bool available = false;
};

struct HardwareBufferIdApi {
    AHardwareBufferGetIdFn getId = nullptr;
    bool available = false;
};

OffscreenAhbEglEncoderState* fromHandle(jlong handle) {
    return reinterpret_cast<OffscreenAhbEglEncoderState*>(handle);
}

void setStatus(OffscreenAhbEglEncoderState* state, const std::string& status) {
    if (state != nullptr) {
        state->status = status;
    }
}

bool hasExtension(const char* extensions, const char* extension) {
    if (extensions == nullptr || extension == nullptr || *extension == '\0') return false;
    const size_t extensionLength = std::strlen(extension);
    const char* match = extensions;
    while ((match = std::strstr(match, extension)) != nullptr) {
        const bool startsAtBoundary = match == extensions || match[-1] == ' ';
        const char next = match[extensionLength];
        const bool endsAtBoundary = next == '\0' || next == ' ';
        if (startsAtBoundary && endsAtBoundary) return true;
        match += extensionLength;
    }
    return false;
}

HardwareBufferSourceProof classifySourcePrecision(const AHardwareBuffer_Desc& description) {
    switch (description.format) {
        case AHARDWAREBUFFER_FORMAT_YCbCr_P010:
            return {
                "AHARDWAREBUFFER_FORMAT_YCbCr_P010",
                10,
                "PACKED_10_BIT",
                true
            };
        case AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM:
            return {
                "AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM",
                10,
                "PACKED_10_BIT",
                true
            };
        case AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM:
            return {"AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM", 8, "EIGHT_BIT", false};
        case AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM:
            return {"AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM", 8, "EIGHT_BIT", false};
        case AHARDWAREBUFFER_FORMAT_Y8Cb8Cr8_420:
            return {"AHARDWAREBUFFER_FORMAT_Y8Cb8Cr8_420", 8, "EIGHT_BIT", false};
        default:
            return {
                "AHARDWAREBUFFER_FORMAT_" + std::to_string(description.format),
                0,
                "UNKNOWN",
                false
            };
    }
}

void resetSourceEvidence(OffscreenAhbEglEncoderState* state) {
    if (state == nullptr) return;
    state->sourceFormat = "UNDEFINED";
    state->sourceBitDepth = 0;
    state->sourcePrecision = "UNKNOWN";
    state->sourcePrecisionProven = false;
}

std::string preferredDeliveryMode(JNIEnv* env, jstring value) {
    if (value == nullptr) return "SDR";
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return "SDR";
    std::string mode(chars);
    env->ReleaseStringUTFChars(value, chars);
    if (mode == "HLG" || mode == "PQ" || mode == "SDR") return mode;
    return "SDR";
}

int32_t dataSpaceForDelivery(const std::string& deliveryMode) {
    if (deliveryMode == "HLG") return ADATASPACE_BT2020_HLG;
    if (deliveryMode == "PQ") return ADATASPACE_BT2020_PQ;
    return ADATASPACE_SRGB;
}

std::string dataSpaceLabel(int32_t dataSpace) {
    switch (dataSpace) {
        case ADATASPACE_UNKNOWN:
            return "UNKNOWN";
        case ADATASPACE_SRGB:
            return "SRGB_NONLINEAR";
        case ADATASPACE_SRGB_LINEAR:
            return "EXTENDED_SRGB_LINEAR";
        case ADATASPACE_SCRGB:
            return "EXTENDED_SRGB_NONLINEAR";
        case ADATASPACE_SCRGB_LINEAR:
            return "EXTENDED_SRGB_LINEAR";
        case ADATASPACE_BT2020_HLG:
            return "HDR10_HLG";
        case ADATASPACE_BT2020_PQ:
            return "HDR10_ST2084";
        case ADATASPACE_BT2020:
            return "BT2020_LINEAR";
        default:
            return "ADATASPACE_" + std::to_string(dataSpace);
    }
}

std::string eglColorSpaceLabel(EGLint colorSpace) {
    switch (colorSpace) {
        case EGL_GL_COLORSPACE_SRGB_KHR:
            return "SRGB_NONLINEAR";
        case EGL_GL_COLORSPACE_BT2020_HLG_EXT:
            return "HDR10_HLG";
        case EGL_GL_COLORSPACE_BT2020_PQ_EXT:
            return "HDR10_ST2084";
        case EGL_NONE:
            return "UNDEFINED";
        default:
            return "EGL_COLORSPACE_" + std::to_string(colorSpace);
    }
}

bool dataSpaceMatchesDelivery(const std::string& deliveryMode, int32_t dataSpace) {
    if (deliveryMode == "HLG") return dataSpace == ADATASPACE_BT2020_HLG;
    if (deliveryMode == "PQ") return dataSpace == ADATASPACE_BT2020_PQ;
    return dataSpace == ADATASPACE_SRGB ||
        dataSpace == ADATASPACE_SRGB_LINEAR ||
        dataSpace == ADATASPACE_SCRGB ||
        dataSpace == ADATASPACE_SCRGB_LINEAR;
}

const NativeWindowDataSpaceApi& nativeWindowDataSpaceApi() {
    static const NativeWindowDataSpaceApi api = []() {
        NativeWindowDataSpaceApi resolved{};
        void* libAndroid = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
        if (libAndroid == nullptr) {
            LOGW("libandroid.so dlopen failed for ANativeWindow dataspace API: %s", dlerror());
            return resolved;
        }
        resolved.setBuffersDataSpace =
            reinterpret_cast<ANativeWindowSetBuffersDataSpaceFn>(
                dlsym(libAndroid, "ANativeWindow_setBuffersDataSpace"));
        resolved.getBuffersDataSpace =
            reinterpret_cast<ANativeWindowGetBuffersDataSpaceFn>(
                dlsym(libAndroid, "ANativeWindow_getBuffersDataSpace"));
        resolved.available =
            resolved.setBuffersDataSpace != nullptr &&
            resolved.getBuffersDataSpace != nullptr;
        if (!resolved.available) {
            LOGW("ANativeWindow buffers dataspace API unavailable through libandroid.so");
        }
        return resolved;
    }();
    return api;
}

const HardwareBufferIdApi& hardwareBufferIdApi() {
    static const HardwareBufferIdApi api = []() {
        HardwareBufferIdApi resolved{};
        void* libAndroid = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
        if (libAndroid == nullptr) {
            LOGW("libandroid.so dlopen failed for AHardwareBuffer_getId: %s", dlerror());
            return resolved;
        }
        resolved.getId =
            reinterpret_cast<AHardwareBufferGetIdFn>(dlsym(libAndroid, "AHardwareBuffer_getId"));
        resolved.available = resolved.getId != nullptr;
        if (!resolved.available) {
            LOGW("AHardwareBuffer_getId unavailable; EGL image cache will use pointer fallback");
        }
        return resolved;
    }();
    return api;
}

uint64_t eglImageCacheKeyFor(OffscreenAhbEglEncoderState* state, AHardwareBuffer* hardwareBuffer) {
    if (state != nullptr && state->ahardwareBufferGetId != nullptr) {
        uint64_t id = 0;
        if (state->ahardwareBufferGetId(hardwareBuffer, &id) == 0) {
            return id;
        }
        ++state->eglImageCacheIdFailures;
    }
    return static_cast<uint64_t>(reinterpret_cast<uintptr_t>(hardwareBuffer));
}

constexpr int kNdrPqLutSize = 65;

struct Float3 {
    float r;
    float g;
    float b;
};

float clamp01(float v) {
    return std::clamp(v, 0.0f, 1.0f);
}

float smoothstepCpp(float e0, float e1, float x) {
    if (e0 == e1) return x >= e1 ? 1.0f : 0.0f;
    const float t = clamp01((x - e0) / (e1 - e0));
    return t * t * (3.0f - 2.0f * t);
}

float hlgInverseOetfChannelCpp(float e) {
    constexpr float a = 0.17883277f;
    constexpr float b = 0.28466892f;
    constexpr float cc = 0.55991073f;
    const float lo = (e * e) / 3.0f;
    const float hi = (std::exp((e - cc) / a) + b) / 12.0f;
    return e >= 0.5f ? hi : lo;
}

float pqOetfFromNitsCpp(float nits) {
    const float y = clamp01(nits / 10000.0f);
    const float ym = std::pow(y, 0.1593017578125f);
    return std::pow(
        (0.8359375f + 18.8515625f * ym) / (1.0f + 18.6875f * ym),
        78.84375f
    );
}

float neoPqLumaCpp(const Float3& c) {
    return c.r * 0.2627f + c.g * 0.6780f + c.b * 0.0593f;
}

float neoSignalShapeCpp(float x, float g, float toeW, float toeA) {
    if (x <= 0.0f) return 0.0f;
    if (x <= 1.0f) {
        return std::pow(x, g) + smoothstepCpp(0.0f, toeW, x) * toeA * (1.0f - x);
    }
    return 1.0f + (g - toeA) * (x - 1.0f);
}

float neoSignalToNitsCpp(float signal, float diffuse, float peak) {
    const float s = std::max(signal, 0.0f);
    const float raw = s * diffuse;
    if (raw <= diffuse) return raw;
    const float range = peak - diffuse;
    return std::min(diffuse + range * (1.0f - std::exp(-(raw - diffuse) / range)), peak);
}

Float3 ndrPqLutSample(float r, float g, float b, bool nivukx) {
    constexpr float diffuseScene = 0.264967f;
    const float gamma = nivukx ? 0.88f : 0.92f;
    const float toeW = nivukx ? 0.24f : 0.20f;
    const float toeA = nivukx ? 0.026f : 0.018f;
    const float peak = nivukx ? 1680.0f : 1420.0f;
    const float desatCap = nivukx ? 0.26f : 0.20f;

    Float3 lin{
        hlgInverseOetfChannelCpp(clamp01(r)) / diffuseScene,
        hlgInverseOetfChannelCpp(clamp01(g)) / diffuseScene,
        hlgInverseOetfChannelCpp(clamp01(b)) / diffuseScene
    };
    lin.r = std::max(lin.r, 0.0f);
    lin.g = std::max(lin.g, 0.0f);
    lin.b = std::max(lin.b, 0.0f);

    const float yv = std::max(neoPqLumaCpp(lin), 1e-8f);
    const float mappedY = neoSignalShapeCpp(yv, gamma, toeW, toeA);
    Float3 t{
        lin.r * (mappedY / yv),
        lin.g * (mappedY / yv),
        lin.b * (mappedY / yv)
    };
    const float desat = std::clamp(
        desatCap * smoothstepCpp(0.50f, 1.0f, mappedY),
        0.0f,
        desatCap
    );
    t.r = std::max(t.r + (mappedY - t.r) * desat, 0.0f);
    t.g = std::max(t.g + (mappedY - t.g) * desat, 0.0f);
    t.b = std::max(t.b + (mappedY - t.b) * desat, 0.0f);

    const float sl = std::max(neoPqLumaCpp(t), 1e-8f);
    const float nits = neoSignalToNitsCpp(sl, 203.0f, peak);
    const float scale = nits / sl;
    return {
        pqOetfFromNitsCpp(t.r * scale),
        pqOetfFromNitsCpp(t.g * scale),
        pqOetfFromNitsCpp(t.b * scale)
    };
}

GLuint createNdrPqDeliveryLut(bool nivukx) {
    const size_t texelCount =
        static_cast<size_t>(kNdrPqLutSize) * kNdrPqLutSize * kNdrPqLutSize;
    std::vector<float> data(texelCount * 3u);
    const float denom = static_cast<float>(kNdrPqLutSize - 1);
    for (int z = 0; z < kNdrPqLutSize; ++z) {
        const float b = static_cast<float>(z) / denom;
        for (int y = 0; y < kNdrPqLutSize; ++y) {
            const float g = static_cast<float>(y) / denom;
            for (int x = 0; x < kNdrPqLutSize; ++x) {
                const float r = static_cast<float>(x) / denom;
                const Float3 mapped = ndrPqLutSample(r, g, b, nivukx);
                const size_t i =
                    ((static_cast<size_t>(z) * kNdrPqLutSize + y) * kNdrPqLutSize + x) * 3u;
                data[i] = mapped.r;
                data[i + 1u] = mapped.g;
                data[i + 2u] = mapped.b;
            }
        }
    }

    while (glGetError() != GL_NO_ERROR) {}
    GLuint lutTextureId = 0;
    glGenTextures(1, &lutTextureId);
    glBindTexture(GL_TEXTURE_3D, lutTextureId);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
    glTexImage3D(
        GL_TEXTURE_3D,
        0,
        GL_RGB16F,
        kNdrPqLutSize,
        kNdrPqLutSize,
        kNdrPqLutSize,
        0,
        GL_RGB,
        GL_FLOAT,
        data.data()
    );
    const GLenum error = glGetError();
    glBindTexture(GL_TEXTURE_3D, 0);
    if (error != GL_NO_ERROR) {
        LOGW(
            "NDR PQ delivery LUT upload failed mode=%s error=0x%04x",
            nivukx ? "NIVUKX" : "NDR10+",
            error
        );
        if (lutTextureId != 0) {
            glDeleteTextures(1, &lutTextureId);
        }
        return 0;
    }
    return lutTextureId;
}

GLuint compileShader(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint compiled = GL_FALSE;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (compiled != GL_TRUE) {
        char log[1024]{};
        glGetShaderInfoLog(shader, sizeof(log), nullptr, log);
        LOGE("shader compile failed: %s", log);
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

MainProgramLoc fetchMainLoc(GLuint program) {
    MainProgramLoc l;
    l.texture = glGetUniformLocation(program, "uTexture");
    l.texel = glGetUniformLocation(program, "uTexel");
    l.sharpness = glGetUniformLocation(program, "uSharpness");
    l.denoise = glGetUniformLocation(program, "uDenoise");
    l.chromaDenoise = glGetUniformLocation(program, "uChromaDenoise");
    l.gradeMatrix = glGetUniformLocation(program, "uGradeMatrix");
    l.gradeContrast = glGetUniformLocation(program, "uGradeContrast");
    l.lut = glGetUniformLocation(program, "uLut");
    l.lutStrength = glGetUniformLocation(program, "uLutStrength");
    l.history = glGetUniformLocation(program, "uHistory");
    l.temporalBlend = glGetUniformLocation(program, "uTemporalBlend");
    l.historyValid = glGetUniformLocation(program, "uHistoryValid");
    l.historyPreGrade = glGetUniformLocation(program, "uHistoryPreGrade");
    l.hdrToPqConvert = glGetUniformLocation(program, "uHdrToPqConvert");
    l.ndrDeliveryMode = glGetUniformLocation(program, "uNdrDeliveryMode");
    l.ndrPqLut = glGetUniformLocation(program, "uNdrPqLut");
    l.ndrPqLutReady = glGetUniformLocation(program, "uNdrPqLutReady");
    l.inputPq = glGetUniformLocation(program, "uInputPq");
    l.inputPrimaries = glGetUniformLocation(program, "uInputPrimaries");
    l.inputRange = glGetUniformLocation(program, "uInputRange");
    l.ndrWarmSkinRedTame = glGetUniformLocation(program, "uNdrWarmSkinRedTame");
    l.encOutFlip = glGetUniformLocation(program, "uEncOutFlip");
    return l;
}

// Compile+link a vertex/fragment pair into a program (0 on failure). Used for the decode-once
// programs; the shipped main program keeps its own inline link path below unchanged.
GLuint linkVertexFragment(const char* vsrc, const char* fsrc) {
    GLuint v = compileShader(GL_VERTEX_SHADER, vsrc);
    GLuint f = compileShader(GL_FRAGMENT_SHADER, fsrc);
    if (v == 0 || f == 0) {
        if (v != 0) glDeleteShader(v);
        if (f != 0) glDeleteShader(f);
        return 0;
    }
    GLuint p = glCreateProgram();
    glAttachShader(p, v);
    glAttachShader(p, f);
    glLinkProgram(p);
    glDeleteShader(v);
    glDeleteShader(f);
    GLint linked = GL_FALSE;
    glGetProgramiv(p, GL_LINK_STATUS, &linked);
    if (linked != GL_TRUE) {
        char log[1024]{};
        glGetProgramInfoLog(p, sizeof(log), nullptr, log);
        LOGE("decode-once program link failed: %s", log);
        glDeleteProgram(p);
        return 0;
    }
    return p;
}

void replaceOne(std::string& source, const std::string& from, const std::string& to) {
    const size_t position = source.find(from);
    if (position != std::string::npos) {
        source.replace(position, from.size(), to);
    }
}

/**
 * Converts an ordinary external-texture shader into a GL_EXT_YUV_target shader. The replacement
 * samples raw Y'CbCr and applies the app-owned matrix/range expansion before any NDR tone work.
 * Android dataspace on the target reports BT.2020 + PQ + LIMITED (0x11C60000); leaving this step to
 * samplerExternalOES was the last unverified transform common to preview, recording, and conversion.
 */
std::string makeExplicitYuvFragment(const char* externalSource) {
    std::string source(externalSource);
    replaceOne(
        source,
        "#extension GL_OES_EGL_image_external_essl3 : require\n",
        "#extension GL_EXT_YUV_target : require\n"
    );
    replaceOne(
        source,
        "uniform samplerExternalOES uTexture;",
        "uniform __samplerExternal2DY2YEXT uTexture;"
    );
    replaceOne(
        source,
        "vec3 neoSampleCamera(vec2 uv) { return texture(uTexture, uv).rgb; }",
        R"(vec3 neoSampleCamera(vec2 uv) {
    vec3 yuv = texture(uTexture, uv).rgb;
    float y;
    float cb;
    float cr;
    if (uInputRange == 2) {
        // Exact P010 legal-range expansion: Y 64..940, Cb/Cr 64..960.
        y = (yuv.x - 64.0 / 1023.0) * (1023.0 / 876.0);
        cb = (yuv.y - 512.0 / 1023.0) * (1023.0 / 896.0);
        cr = (yuv.z - 512.0 / 1023.0) * (1023.0 / 896.0);
    } else {
        y = yuv.x;
        cb = yuv.y - 0.5;
        cr = yuv.z - 0.5;
    }
    if (uInputPrimaries == 1) {
        return vec3(
            y + 1.5748 * cr,
            y - 0.187324 * cb - 0.468124 * cr,
            y + 1.8556 * cb
        );
    }
    return vec3(
        y + 1.4746 * cr,
        y - 0.164553 * cb - 0.571353 * cr,
        y + 1.8814 * cb
    );
})"
    );
    return source;
}

// #2 decode-once: build the sampler2D main variant (derived from the SAME external fragment source by
// dropping the OES extension and swapping the sampler type, so the shaders can never drift) and the
// external->RGB10_A2 passthrough decode program. Failures are non-fatal: decode-once simply stays OFF and
// the shipped external path is used.
void buildDecodeOncePrograms(OffscreenAhbEglEncoderState* state, const char* vsrc, const char* fsrcExternal) {
    std::string s2d(fsrcExternal);
    replaceOne(s2d, "#extension GL_OES_EGL_image_external_essl3 : require\n", "");
    replaceOne(s2d, "samplerExternalOES uTexture", "sampler2D uTexture");
    state->mainProgram2d = linkVertexFragment(vsrc, s2d.c_str());
    if (state->mainProgram2d != 0) {
        state->mainLoc2d = fetchMainLoc(state->mainProgram2d);
    }

    static constexpr const char* kDecodeFragment = R"(#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES uTexture;
uniform int uInputPrimaries;
uniform int uInputRange;
in vec2 vTexCoord;
out vec4 outColor;
vec3 neoSampleCamera(vec2 uv) { return texture(uTexture, uv).rgb; }
void main() { outColor = vec4(clamp(neoSampleCamera(vTexCoord), 0.0, 1.0), 1.0); }
)";
    state->decodeProgram = linkVertexFragment(vsrc, kDecodeFragment);
    if (state->decodeProgram != 0) {
        state->decodeTexLoc = glGetUniformLocation(state->decodeProgram, "uTexture");
    }
    if (state->mainProgram2d == 0 || state->decodeProgram == 0) {
        LOGW("decode-once programs unavailable (main2d=%u decode=%u); decode-once stays OFF",
             state->mainProgram2d, state->decodeProgram);
    }

    const char* glExtensions = reinterpret_cast<const char*>(glGetString(GL_EXTENSIONS));
    if (hasExtension(glExtensions, "GL_EXT_YUV_target")) {
        const std::string explicitMain = makeExplicitYuvFragment(fsrcExternal);
        const std::string explicitDecode = makeExplicitYuvFragment(kDecodeFragment);
        state->mainProgramExplicitYuv = linkVertexFragment(vsrc, explicitMain.c_str());
        state->decodeProgramExplicitYuv = linkVertexFragment(vsrc, explicitDecode.c_str());
        if (state->mainProgramExplicitYuv != 0 && state->decodeProgramExplicitYuv != 0) {
            state->mainLocExplicitYuv = fetchMainLoc(state->mainProgramExplicitYuv);
            state->decodeExplicitYuvTexLoc =
                glGetUniformLocation(state->decodeProgramExplicitYuv, "uTexture");
            state->decodeExplicitYuvPrimariesLoc =
                glGetUniformLocation(state->decodeProgramExplicitYuv, "uInputPrimaries");
            state->decodeExplicitYuvRangeLoc =
                glGetUniformLocation(state->decodeProgramExplicitYuv, "uInputRange");
            state->explicitYuvDecodeAvailable = true;
            LOGI("explicit BT.2020 YUV decode available (GL_EXT_YUV_target)");
        } else {
            LOGW(
                "GL_EXT_YUV_target advertised but explicit programs unavailable (main=%u decode=%u)",
                state->mainProgramExplicitYuv,
                state->decodeProgramExplicitYuv
            );
        }
    } else {
        LOGW("GL_EXT_YUV_target unavailable; retaining driver external-texture conversion fallback");
    }
}

GLuint createProgram(OffscreenAhbEglEncoderState* state) {
    static constexpr const char* kVertexShader = R"(#version 300 es
layout(location = 0) in vec2 aPosition;
layout(location = 1) in vec2 aTexCoord;
// MIRRORFIX v5 — per-axis output flip. x>0.5 mirrors the sampled S (encoded-horizontal); y>0.5 mirrors
// the sampled T (encoded-vertical). Default 0 => byte-identical passthrough. This is set ONLY on the
// final encoder-writing programs (single-pass main + decode-once PASS 2), never on the PASS 1 decode
// copy, so the flip survives the decode-once two-pass path (where the shared quad alone would cancel).
uniform vec2 uEncOutFlip;
out vec2 vTexCoord;
void main() {
    gl_Position = vec4(aPosition, 0.0, 1.0);
    vec2 uv = aTexCoord;
    if (uEncOutFlip.x > 0.5) uv.x = 1.0 - uv.x;
    if (uEncOutFlip.y > 0.5) uv.y = 1.0 - uv.y;
    vTexCoord = uv;
}
)";

    static constexpr const char* kFragmentShader = R"(#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES uTexture;
uniform vec2 uTexel;
uniform float uSharpness;
uniform float uDenoise;
uniform float uChromaDenoise;
// Record-grade stage (all identity / no-op by default):
//  uGradeMatrix/uGradeContrast : display-referred colour-science look (Color Science)
//  uLut/uLutStrength           : creative 3D LUT, SDR-gated on the host (LUT Preview)
//  uHistory/uTemporalBlend/... : motion-guarded temporal blend (AI Motion Compensation)
uniform mat3 uGradeMatrix;
uniform float uGradeContrast;
uniform highp sampler3D uLut;
uniform float uLutStrength;
uniform sampler2D uHistory;
uniform float uTemporalBlend;
uniform float uHistoryValid;
// 1.0 on the decode-once RGB10_A2 path: uHistory is previous decoded P010 and blends before grade.
// 0.0 on the external fallback: uHistory is previous processed output and blends after grade.
uniform float uHistoryPreGrade;
// NDR true-PQ: 1.0 => convert the final HLG-encoded pixel to genuine ST.2084 PQ (delivery == BT.2020_PQ).
uniform float uHdrToPqConvert;
// NDR10+/NIVUKX delivery mode: 0.0 => NDR10+ (balanced), 1.0 => NIVUKX Vision (premium/brighter).
uniform float uNdrDeliveryMode;
// 65^3 RGB16F cache of the same NDR/NIVUKX HLG-domain -> ST.2084 operator below. Sampling this LUT
// removes the per-pixel pow/exp tone-map hot spot at 4K without changing the delivery contract.
uniform highp sampler3D uNdrPqLut;
uniform float uNdrPqLutReady;
// 1.0 => the camera frame is PQ (ST.2084, HDR10/HDR10_PLUS capture) and MUST be PQ-decoded; 0.0 => HLG.
// The NDR/NIVUKX capture negotiates HDR10_PLUS (PQ) on this device, so decoding it as HLG (the legacy
// path) mis-mapped the transfer -> the confirmed multi-hue "red-blown" cast + crushed highlights.
uniform float uInputPq;
// Per-buffer Android dataspace metadata. The ordinary external path leaves YUV conversion to the
// driver; the GL_EXT_YUV_target variant consumes these in the project-owned decoder.
uniform int uInputPrimaries;
uniform int uInputRange;
// Proprietary NDR/NIVUKX skin/neutral red correction. Zero is an exact no-op.
uniform float uNdrWarmSkinRedTame;
in vec2 vTexCoord;
out vec4 outColor;

float luma(vec3 color) {
    return dot(color, vec3(0.2126, 0.7152, 0.0722));
}

// ARIB STD-B67 (HLG) inverse OETF: signal [0,1] -> normalized scene-linear [0,1].
vec3 hlgInverseOetf(vec3 e) {
    const float a = 0.17883277;
    const float b = 0.28466892;   // 1 - 4a
    const float cc = 0.55991073;  // 0.5 - a*ln(4a)
    vec3 lo = (e * e) / 3.0;
    vec3 hi = (exp((e - cc) / a) + b) / 12.0;
    return mix(lo, hi, step(vec3(0.5), e));
}

// SMPTE ST.2084 (PQ) OETF: display luminance normalized to [0,1] (1.0 == 10000 cd/m^2) -> signal.
float pqOetf(float l) {
    const float m1 = 0.1593017578125;
    const float m2 = 78.84375;
    const float c1 = 0.8359375;
    const float c2 = 18.8515625;
    const float c3 = 18.6875;
    float lp = pow(clamp(l, 0.0, 1.0), m1);
    return pow((c1 + c2 * lp) / (1.0 + c3 * lp), m2);
}

// Convert an HLG-encoded BT.2020 pixel to genuine PQ (ST.2084) via the HLG OOTF at a 1000-nit nominal
// reference (system gamma 1.2). Public BT.2100 math only (no vendor curves). The 1000-nit peak /
// system gamma is the device-tunable knob for the on-device A/B.
vec3 hlgEncodedToPq(vec3 e) {
    vec3 sceneLinear = hlgInverseOetf(e);
    float ys = dot(sceneLinear, vec3(0.2627, 0.6780, 0.0593)); // BT.2020 luminance
    const float peakNits = 1000.0;
    const float systemGamma = 1.2;                              // HLG OOTF gamma at Lw = 1000 nits
    float ootf = (ys > 0.0) ? pow(ys, systemGamma - 1.0) : 0.0;
    vec3 displayLinear = peakNits * ootf * sceneLinear;         // cd/m^2, 0..1000
    vec3 pqInput = displayLinear / 10000.0;                     // normalize to PQ [0,1]
    return vec3(pqOetf(pqInput.r), pqOetf(pqInput.g), pqOetf(pqInput.b));
}

// ── NDR10+/NIVUKX genuine tone operator (GLSL port of NdrNivukxDeliveryEngine / NdrToneOperator) ──
// This is the app's OWN colour science reaching the recorded pixels: a 203-nit diffuse-white anchor,
// a hue-preserving highlight ROLL-OFF (so bright detail is compressed, never hard-clipped to white),
// mode-aware peak nits, and highlight desaturation — replacing the old bare 1000-nit HLG->PQ that had
// no shoulder and no diffuse anchor. Input is the final HLG-encoded BT.2020 R'G'B' pixel; output is PQ.
const float NEO_HLG_DIFFUSE_SCENE = 0.264967; // scene-linear at 75% HLG (BT.2408 HDR reference white)
float neoPqLuma2(vec3 c) { return dot(c, vec3(0.2627, 0.6780, 0.0593)); }
float neoDelSmoothstep(float e0, float e1, float x) {
    if (e0 == e1) return x >= e1 ? 1.0 : 0.0;
    float t = clamp((x - e0) / (e1 - e0), 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}
float neoDelSignalShape(float x, float g, float toeW, float toeA) {
    if (x <= 0.0) return 0.0;
    if (x <= 1.0) return pow(x, g) + neoDelSmoothstep(0.0, toeW, x) * toeA * (1.0 - x);
    return 1.0 + (g - toeA) * (x - 1.0);
}
float neoDelSignalToNits(float signal, float diffuse, float peak) {
    float s = max(signal, 0.0);
    float raw = s * diffuse;
    if (raw <= diffuse) return raw;
    float range = peak - diffuse;
    return min(diffuse + range * (1.0 - exp(-(raw - diffuse) / range)), peak);
}
float neoDelPqFromNits(float nits) {
    float y = clamp(nits / 10000.0, 0.0, 1.0);
    float ym = pow(y, 0.1593017578125);
    return pow((0.8359375 + 18.8515625 * ym) / (1.0 + 18.6875 * ym), 78.84375);
}
// SMPTE ST.2084 (PQ) EOTF: signal [0,1] -> display-linear normalized [0,1] (1.0 == 10000 cd/m^2).
// Inverse of neoDelPqFromNits; used to decode a PQ-encoded CAMERA input frame back to display-linear.
vec3 neoPqEotf(vec3 e) {
    vec3 ep = pow(clamp(e, 0.0, 1.0), vec3(1.0 / 78.84375));
    vec3 num = max(ep - 0.8359375, vec3(0.0));
    vec3 den = 18.8515625 - 18.6875 * ep;
    return pow(num / den, vec3(1.0 / 0.1593017578125));
}
vec3 neoDelMapRgbHuePreserving(vec3 lin, float g, float toeW, float toeA, float desatCap) {
    vec3 s = max(lin, vec3(0.0));
    float yv = max(neoPqLuma2(s), 1e-8);
    float mappedY = neoDelSignalShape(yv, g, toeW, toeA);
    vec3 o = s * (mappedY / yv);
    // Fix C: begin the hue-preserving highlight desaturation a touch earlier (0.50 vs 0.55) so a
    // near-clipping warm highlight (e.g. overexposed skin that would clip the red channel first) rolls
    // toward neutral white sooner instead of skewing red. Uniform across hues, so legitimately saturated
    // bright subjects are only gently affected; the real overexposure cause is fixed capture-side (A/B).
    float desat = clamp(desatCap * neoDelSmoothstep(0.50, 1.0, mappedY), 0.0, desatCap);
    o = mix(o, vec3(mappedY), desat);
    // Match NdrToneOperator/Hdr10PqPreviewCompositor: ratio-gated red-overflow reconstruction.
    // Extreme-red and low-blue orange objects remain outside the gate; corrected pixels retain luma.
    if (uNdrWarmSkinRedTame > 0.0) {
        float oy = max(neoPqLuma2(max(o, vec3(0.0))), 1e-8);
        float rgRatio = s.r / max(s.g, 1e-8);
        float bgRatio = s.b / max(s.g, 1e-8);
        float moderateRed = neoDelSmoothstep(1.18, 1.55, rgRatio) *
            (1.0 - neoDelSmoothstep(2.80, 4.00, rgRatio));
        float bluePresent = neoDelSmoothstep(0.28, 0.55, bgRatio) *
            (1.0 - neoDelSmoothstep(1.08, 1.35, bgRatio));
        float exposureWindow = neoDelSmoothstep(0.04, 0.16, mappedY) *
            (1.0 - neoDelSmoothstep(6.5, 8.0, mappedY));
        float redOverflow = neoDelSmoothstep(1.30, 2.10, rgRatio);
        float weight = clamp(
            uNdrWarmSkinRedTame * moderateRed * bluePresent * exposureWindow *
                (0.45 + 0.55 * redOverflow),
            0.0,
            uNdrWarmSkinRedTame
        );
        vec3 corrected = o;
        corrected.r = mix(corrected.r, min(corrected.r, corrected.g * 1.42), weight);
        corrected.b = mix(corrected.b, max(corrected.b, corrected.g * 0.64), weight * 0.68);
        float correctedY = max(neoPqLuma2(max(corrected, vec3(0.0))), 1e-8);
        o = corrected * (oy / correctedY);
    }
    return max(o, vec3(0.0));
}
// mode > 0.5 => NIVUKX Vision (premium: brighter peak, more midtone lift, more desat); else NDR10+.
vec3 neoDeliverPqFromHlg(vec3 hlg, float mode) {
    float g     = mode > 0.5 ? 0.88   : 0.92;
    float toeW  = mode > 0.5 ? 0.24   : 0.20;
    float toeA  = mode > 0.5 ? 0.026  : 0.018;
    float peak  = mode > 0.5 ? 1680.0 : 1420.0;
    float desat = mode > 0.5 ? 0.26   : 0.20;
    // Decode the input with the transfer the camera ACTUALLY sent, then normalize to engine-linear
    // (1.0 == 203-nit diffuse). PQ: display nits (pqEotf*10000) / 203. HLG (legacy): scene-linear /
    // diffuse-scene anchor. Everything below the decode is transfer-agnostic and stays identical.
    vec3 lin = uInputPq > 0.5
        ? (neoPqEotf(clamp(hlg, 0.0, 1.0)) * (10000.0 / 203.0))
        : (hlgInverseOetf(clamp(hlg, 0.0, 1.0)) / NEO_HLG_DIFFUSE_SCENE);
    vec3 t = neoDelMapRgbHuePreserving(lin, g, toeW, toeA, desat);
    float sl = max(neoPqLuma2(t), 1e-8);
    float nits = neoDelSignalToNits(sl, 203.0, peak);
    float sc = nits / sl;
    return vec3(neoDelPqFromNits(t.r * sc), neoDelPqFromNits(t.g * sc), neoDelPqFromNits(t.b * sc));
}

// Anti-banding dither: cheap hash value-noise driving a triangular-PDF (TPDF) dither applied just
// before the limited-bit quantize. Neutral RGB dither removes banding without adding colored speckle.
float ditherHash(vec2 p) {
    return fract(52.9829189 * fract(0.06711056 * p.x + 0.00583715 * p.y));
}

vec3 neoSampleCamera(vec2 uv) { return texture(uTexture, uv).rgb; }

void main() {
    vec2 tx = max(uTexel, vec2(0.0001));
    vec3 c = neoSampleCamera(vTexCoord);
    if (uHistoryPreGrade > 0.5 && uTemporalBlend > 0.0 && uHistoryValid > 0.5) {
        vec3 histSource = texture(uHistory, vTexCoord).rgb;
        float lcNow = luma(c);
        float lcHist = luma(histSource);
        vec3 chNow = c - vec3(lcNow);
        vec3 chHist = histSource - vec3(lcHist);
        float mdSource = abs(lcNow - lcHist) + 0.6 * length(chNow - chHist);
        float guardSource = 1.0 - smoothstep(0.012, 0.06, mdSource);
        float bSource = clamp(uTemporalBlend, 0.0, 1.0) * guardSource;
        c = mix(c, histSource, bSource);
    }
    float lc = luma(c);

    // Edge-aware spatial denoise (bilateral) + a spatial-only Gaussian reference for the
    // unsharp, both computed from ONE 5-tap neighbourhood (center + 4 axial).
    // The old radius-2/diagonal taps were low-energy but expensive at 4K; their energy is folded into
    // the axial neighbours below so denoise remains active without the thermal-throttling sample wall.
    // This replaces the old flat 3x3 tent low-pass: the range
    // weight keeps full weight on flat sensor noise and ~0 on real edges, so genuine detail
    // survives the denoise instead of being smeared away (the universal-softness root cause).
    vec2 off[4];
    float sw[4];
    off[0] = vec2( tx.x, 0.0);  sw[0] = 1.11;
    off[1] = vec2(-tx.x, 0.0);  sw[1] = 1.11;
    off[2] = vec2( 0.0,  tx.y); sw[2] = 1.11;
    off[3] = vec2( 0.0, -tx.y); sw[3] = 1.11;

    float dn = clamp(uDenoise, 0.0, 1.0);
    // Range sigma is HARD-CLAMPED (<= 0.05) so that even if the host regresses and drives
    // uDenoise toward 1.0, the range window can never widen enough to collapse low-contrast
    // texture (skin pores / foliage / fabric) into flat patches. Defence-in-depth on top of
    // the signalFamily-aware host cap in OffscreenAhbEglEncoderBridge.
    float sigmaR = clamp(0.010 + 0.045 * dn, 0.010, 0.05);
    float invR2 = 1.0 / (2.0 * sigmaR * sigmaR);

    vec3 accBil = c; float wBil = 1.0;   // bilateral: spatial * range
    vec3 accG = c;   float wG = 1.0;     // spatial-only low-pass: the unsharp reference
    for (int i = 0; i < 4; i++) {
        vec3 s = neoSampleCamera(vTexCoord + off[i]);
        float dl = luma(s) - lc;
        // Fast Gaussian-like range weight. The previous exp(-d^2/sigma) form cost one transcendental
        // ops per 4K pixel; this squared reciprocal keeps the same edge rejection shape without the
        // thermal-throttling ALU wall.
        float x = dl * dl * invR2;
        float rwBase = 1.0 / (1.0 + x);
        float rw = rwBase * rwBase;
        float wb = sw[i] * rw;
        accBil += s * wb; wBil += wb;
        accG += s * sw[i]; wG += sw[i];
    }
    vec3 bilateral = accBil / wBil;
    vec3 gauss = accG / wG;

    // Edge-preserving: safe to apply at full (already-capped) requested strength because edges
    // keep ~0 range weight and are not blended toward the mean.
    vec3 denoised = mix(c, bilateral, dn);

    // Halo-controlled unsharp. detail is the high-freq of the DENOISED signal (real structure
    // that survived denoising, NOT raw grain), backed off on strong edges, and symmetrically
    // capped so overshoot cannot ring or re-amplify residual noise.
    vec3 detail = denoised - gauss;
    float amt = clamp(uSharpness, 0.0, 1.0) * 0.85;
    float lcd = abs(luma(detail));
    float haloGuard = 1.0 - smoothstep(0.06, 0.20, lcd);
    float cap = 0.05 + 0.05 * amt;
    vec3 boost = clamp(detail * amt * haloGuard, -cap, cap);
    vec3 sharpened = denoised + boost;

    float y = luma(sharpened);
    vec3 chroma = sharpened - vec3(y);
    // Chroma-noise reduction, FLAT-AREA GATED so it removes shadow colour speckle without globally
    // desaturating detailed / edge regions (colour richness preserved). Ceiling raised 0.12 -> 0.30
    // but only where luma is locally flat (real chroma noise lives there); ~0 on textured / edge pixels.
    float chromaFlat = 1.0 - smoothstep(0.02, 0.10, abs(luma(detail)));
    float chromaGuard = clamp(uChromaDenoise, 0.0, 1.0) * 0.30 * chromaFlat;
    vec3 finalColor = vec3(y) + chroma * (1.0 - chromaGuard);

    // Display-referred colour-science look: identity mat3 + contrast 1.0 => bit-identical.
    vec3 graded = uGradeMatrix * finalColor;
    graded = (graded - vec3(0.5)) * uGradeContrast + vec3(0.5);
    // Creative 3D LUT (host forces strength 0 on HLG/PQ, so HDR transport is never touched).
    if (uLutStrength > 0.0) {
        vec3 lutSample = texture(uLut, clamp(graded, 0.0, 1.0)).rgb;
        graded = mix(graded, lutSample, clamp(uLutStrength, 0.0, 1.0));
    }
    // Motion-guarded temporal denoise. blend==0 or no valid history => identity. The per-pixel motion
    // guard is TIGHTENED (0.012..0.06) and now measures BOTH luma and chroma change, so the blend
    // collapses to ~0 on any moving/coloured-moving pixel: static areas accumulate clean history
    // (strong noise reduction) while moving subjects never ghost or smear (strict no-motion-blur).
    if (uHistoryPreGrade < 0.5 && uTemporalBlend > 0.0 && uHistoryValid > 0.5) {
        vec3 hist = texture(uHistory, vTexCoord).rgb;
        float lg = luma(graded);
        float lh = luma(hist);
        vec3 chG = graded - vec3(lg);
        vec3 chH = hist - vec3(lh);
        // Combined luma + chroma motion measure (coloured motion is guarded too).
        float md = abs(lg - lh) + 0.6 * length(chG - chH);
        float guard = 1.0 - smoothstep(0.012, 0.06, md);
        float b = clamp(uTemporalBlend, 0.0, 1.0) * guard;
        graded = mix(graded, hist, b);
    }
    vec3 finalRgb = clamp(graded, 0.0, 1.0);
    // NDR true-PQ (Fork A): the pipeline above ran in the HLG-encoded domain (denoise/sharpen/grade
    // are tuned there); convert the final pixel to genuine ST.2084 PQ ONLY when the delivery surface
    // is BT.2020_PQ. When 0.0 the pixel stays HLG-coherent. SDR/LOG deliver an SDR surface and never
    // set this, so their pixels are untouched.
    if (uHdrToPqConvert > 0.5) {
        // The uNdrPqLut is baked HLG-indexed on the host, so it is only valid for HLG input. When the
        // camera input is PQ (uInputPq), take the direct compute path (which PQ-decodes correctly).
        finalRgb = clamp(
            (uNdrPqLutReady > 0.5 && uInputPq < 0.5 && uNdrWarmSkinRedTame <= 0.0)
                ? texture(uNdrPqLut, finalRgb).rgb
                : neoDeliverPqFromHlg(finalRgb, uNdrDeliveryMode),
            0.0,
            1.0
        );
    }
    // Neutral TPDF dither at ~1 LSB of the 10-bit output, applied in output-code space (after any
    // HLG->PQ) so quantization error is decorrelated into imperceptible noise instead of hard bands.
    vec2 dpx = gl_FragCoord.xy;
    float dither = ditherHash(dpx) - ditherHash(dpx + vec2(23.17, 61.43));
    finalRgb = clamp(finalRgb + vec3(dither) * (1.0 / 1023.0), 0.0, 1.0);
    outColor = vec4(finalRgb, 1.0);
}
)";

    GLuint vertex = compileShader(GL_VERTEX_SHADER, kVertexShader);
    GLuint fragment = compileShader(GL_FRAGMENT_SHADER, kFragmentShader);
    if (vertex == 0 || fragment == 0) {
        if (vertex != 0) glDeleteShader(vertex);
        if (fragment != 0) glDeleteShader(fragment);
        return 0;
    }

    GLuint program = glCreateProgram();
    glAttachShader(program, vertex);
    glAttachShader(program, fragment);
    glLinkProgram(program);
    glDeleteShader(vertex);
    glDeleteShader(fragment);

    GLint linked = GL_FALSE;
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (linked != GL_TRUE) {
        char log[1024]{};
        glGetProgramInfoLog(program, sizeof(log), nullptr, log);
        LOGE("program link failed: %s", log);
        glDeleteProgram(program);
        return 0;
    }
    // #2 decode-once (default ON on the measured target): build the sampler2D main variant + the external->RGB10_A2 decode
    // program from the SAME sources so they never drift. Non-fatal on failure.
    buildDecodeOncePrograms(state, kVertexShader, kFragmentShader);
    return program;
}

// Tear down a partially-initialised EGL context so a FAILED ensureEglReady() retry cannot orphan
// the eglCreateContext() result. On re-entry the guard at the top of ensureEglReady() sees
// program==0 and re-runs, overwriting state->context with a fresh context -- the previous one
// would leak on every retry. Upgrade-only: unbinds and destroys ONLY the half-built context and
// leaves display/config intact for the retry; a fully-built context is destroyState()'s job.
void teardownPartialEglContext(OffscreenAhbEglEncoderState* state) {
    if (state->display != EGL_NO_DISPLAY && state->context != EGL_NO_CONTEXT) {
        eglMakeCurrent(state->display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        eglDestroyContext(state->display, state->context);
    }
    state->context = EGL_NO_CONTEXT;
}

bool ensureEglReady(OffscreenAhbEglEncoderState* state) {
    if (state->display != EGL_NO_DISPLAY && state->context != EGL_NO_CONTEXT && state->program != 0) {
        return true;
    }

    state->display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (state->display == EGL_NO_DISPLAY) {
        setStatus(state, "eglGetDisplay failed");
        return false;
    }
    EGLint major = 0;
    EGLint minor = 0;
    if (eglInitialize(state->display, &major, &minor) != EGL_TRUE) {
        setStatus(state, "eglInitialize failed");
        return false;
    }
    const char* extensions = eglQueryString(state->display, EGL_EXTENSIONS);
    if (!hasExtension(extensions, "EGL_KHR_gl_colorspace")) {
        setStatus(state, "EGL_KHR_gl_colorspace unavailable");
        return false;
    }

    const EGLint configAttribs[] = {
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
        EGL_RED_SIZE, 10,
        EGL_GREEN_SIZE, 10,
        EGL_BLUE_SIZE, 10,
        EGL_ALPHA_SIZE, 2,
        EGL_RECORDABLE_ANDROID, EGL_TRUE,
        EGL_NONE
    };
    // Some drivers honor the EGL_*_SIZE>=10 minimum loosely and hand back an 8-bit config for this
    // request, so the first eglChooseConfig result is NEVER trusted blindly. It is verified for an
    // exact 10/10/10/2 + recordable window/ES3 layout; if it does not match we ENUMERATE every
    // config (eglGetConfigs) and scan for a genuine one. Only if the full enumeration finds none is
    // this a real driver hardware wall — reported honestly with the enumerated count, so the device
    // log finally distinguishes "no 10-bit recordable config" from a loose eglChooseConfig sort.
    auto configIsRecordable10Bit = [state](EGLConfig candidate) -> bool {
        EGLint r = 0, g = 0, b = 0, a = 0, rec = EGL_FALSE, surf = 0, rend = 0;
        if (eglGetConfigAttrib(state->display, candidate, EGL_RED_SIZE, &r) != EGL_TRUE ||
            eglGetConfigAttrib(state->display, candidate, EGL_GREEN_SIZE, &g) != EGL_TRUE ||
            eglGetConfigAttrib(state->display, candidate, EGL_BLUE_SIZE, &b) != EGL_TRUE ||
            eglGetConfigAttrib(state->display, candidate, EGL_ALPHA_SIZE, &a) != EGL_TRUE ||
            eglGetConfigAttrib(state->display, candidate, EGL_RECORDABLE_ANDROID, &rec) != EGL_TRUE ||
            eglGetConfigAttrib(state->display, candidate, EGL_SURFACE_TYPE, &surf) != EGL_TRUE ||
            eglGetConfigAttrib(state->display, candidate, EGL_RENDERABLE_TYPE, &rend) != EGL_TRUE) {
            return false;
        }
        return r == 10 && g == 10 && b == 10 && a == 2 && rec == EGL_TRUE &&
            (surf & EGL_WINDOW_BIT) != 0 && (rend & EGL_OPENGL_ES3_BIT_KHR) != 0;
    };

    EGLConfig chosenConfig = nullptr;
    EGLint configCount = 0;
    EGLConfig preferredConfig = nullptr;
    if (eglChooseConfig(state->display, configAttribs, &preferredConfig, 1, &configCount) == EGL_TRUE &&
        configCount > 0 &&
        configIsRecordable10Bit(preferredConfig)) {
        chosenConfig = preferredConfig;
    } else {
        EGLint totalConfigs = 0;
        if (eglGetConfigs(state->display, nullptr, 0, &totalConfigs) != EGL_TRUE || totalConfigs <= 0) {
            setStatus(state, "eglChooseConfig failed");
            return false;
        }
        std::vector<EGLConfig> allConfigs(static_cast<size_t>(totalConfigs));
        EGLint returnedConfigs = 0;
        if (eglGetConfigs(state->display, allConfigs.data(), totalConfigs, &returnedConfigs) == EGL_TRUE) {
            for (EGLint i = 0; i < returnedConfigs; ++i) {
                if (configIsRecordable10Bit(allConfigs[static_cast<size_t>(i)])) {
                    chosenConfig = allConfigs[static_cast<size_t>(i)];
                    break;
                }
            }
        }
        if (chosenConfig == nullptr) {
            std::ostringstream status;
            status << "recordable RGB10_A2 config unavailable (enumerated "
                << totalConfigs << " configs)";
            setStatus(state, status.str());
            return false;
        }
    }
    state->config = chosenConfig;
    if (eglGetConfigAttrib(state->display, state->config, EGL_RED_SIZE, &state->redBits) != EGL_TRUE ||
        eglGetConfigAttrib(state->display, state->config, EGL_GREEN_SIZE, &state->greenBits) != EGL_TRUE ||
        eglGetConfigAttrib(state->display, state->config, EGL_BLUE_SIZE, &state->blueBits) != EGL_TRUE ||
        eglGetConfigAttrib(state->display, state->config, EGL_ALPHA_SIZE, &state->alphaBits) != EGL_TRUE ||
        eglGetConfigAttrib(
            state->display,
            state->config,
            EGL_RECORDABLE_ANDROID,
            &state->recordable
        ) != EGL_TRUE) {
        setStatus(state, "eglGetConfigAttrib failed");
        return false;
    }
    if (state->redBits != 10 ||
        state->greenBits != 10 ||
        state->blueBits != 10 ||
        state->alphaBits != 2 ||
        state->recordable != EGL_TRUE) {
        std::ostringstream status;
        status << "recordable RGB10_A2 config unavailable actual="
            << state->redBits << "/" << state->greenBits << "/"
            << state->blueBits << "/" << state->alphaBits
            << " recordable=" << state->recordable;
        setStatus(state, status.str());
        return false;
    }

    const EGLint contextAttribs[] = {
        EGL_CONTEXT_CLIENT_VERSION, 3,
        EGL_NONE
    };
    state->context = eglCreateContext(state->display, state->config, EGL_NO_CONTEXT, contextAttribs);
    if (state->context == EGL_NO_CONTEXT) {
        setStatus(state, "eglCreateContext failed");
        return false;
    }
    if (eglMakeCurrent(state->display, EGL_NO_SURFACE, EGL_NO_SURFACE, state->context) != EGL_TRUE) {
        setStatus(state, "eglMakeCurrent init failed");
        teardownPartialEglContext(state);
        return false;
    }

    state->eglGetNativeClientBufferANDROID =
        reinterpret_cast<PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC>(
            eglGetProcAddress("eglGetNativeClientBufferANDROID"));
    state->eglCreateImageKHR =
        reinterpret_cast<PFNEGLCREATEIMAGEKHRPROC>(eglGetProcAddress("eglCreateImageKHR"));
    state->eglDestroyImageKHR =
        reinterpret_cast<PFNEGLDESTROYIMAGEKHRPROC>(eglGetProcAddress("eglDestroyImageKHR"));
    state->eglPresentationTimeANDROID =
        reinterpret_cast<PFNEGLPRESENTATIONTIMEANDROIDPROC>(
            eglGetProcAddress("eglPresentationTimeANDROID"));
    state->glEGLImageTargetTexture2DOES =
        reinterpret_cast<PFNGLEGLIMAGETARGETTEXTURE2DOESPROC>(
            eglGetProcAddress("glEGLImageTargetTexture2DOES"));
    const HardwareBufferIdApi& bufferIdApi = hardwareBufferIdApi();
    state->ahardwareBufferGetId = bufferIdApi.getId;
    state->eglImageCacheStableIds = bufferIdApi.available;
    if (state->eglGetNativeClientBufferANDROID == nullptr ||
        state->eglCreateImageKHR == nullptr ||
        state->eglDestroyImageKHR == nullptr ||
        state->eglPresentationTimeANDROID == nullptr ||
        state->glEGLImageTargetTexture2DOES == nullptr) {
        setStatus(state, "required EGL image/presentation functions unavailable");
        teardownPartialEglContext(state);
        return false;
    }

    state->program = createProgram(state);
    if (state->program == 0) {
        setStatus(state, "shader program creation failed");
        teardownPartialEglContext(state);
        return false;
    }
    state->textureLocation = glGetUniformLocation(state->program, "uTexture");
    state->texelLocation = glGetUniformLocation(state->program, "uTexel");
    state->sharpnessLocation = glGetUniformLocation(state->program, "uSharpness");
    state->denoiseLocation = glGetUniformLocation(state->program, "uDenoise");
    state->chromaDenoiseLocation = glGetUniformLocation(state->program, "uChromaDenoise");
    state->gradeMatrixLocation = glGetUniformLocation(state->program, "uGradeMatrix");
    state->gradeContrastLocation = glGetUniformLocation(state->program, "uGradeContrast");
    state->lutLocation = glGetUniformLocation(state->program, "uLut");
    state->lutStrengthLocation = glGetUniformLocation(state->program, "uLutStrength");
    state->historyLocation = glGetUniformLocation(state->program, "uHistory");
    state->temporalBlendLocation = glGetUniformLocation(state->program, "uTemporalBlend");
    state->historyValidLocation = glGetUniformLocation(state->program, "uHistoryValid");
    state->historyPreGradeLocation = glGetUniformLocation(state->program, "uHistoryPreGrade");
    state->hdrToPqConvertLocation = glGetUniformLocation(state->program, "uHdrToPqConvert");
    state->ndrDeliveryModeLocation = glGetUniformLocation(state->program, "uNdrDeliveryMode");
    state->ndrPqLutLocation = glGetUniformLocation(state->program, "uNdrPqLut");
    state->ndrPqLutReadyLocation = glGetUniformLocation(state->program, "uNdrPqLutReady");
    state->inputPqLocation = glGetUniformLocation(state->program, "uInputPq");
    state->ndrWarmSkinRedTameLocation = glGetUniformLocation(state->program, "uNdrWarmSkinRedTame");
    state->encOutFlipLocation = glGetUniformLocation(state->program, "uEncOutFlip");
    state->mainLocExternal = fetchMainLoc(state->program);

    // Default 1x1 textures keep sampler3D/sampler2D always complete, so the record path can
    // never be black-framed by an incomplete sampler even though their fetches live in
    // dynamic branches that are not taken on the identity (default) path.
    {
        const float lutTexel[3] = {0.5f, 0.5f, 0.5f};
        glGenTextures(1, &state->defaultLut3d);
        glBindTexture(GL_TEXTURE_3D, state->defaultLut3d);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
        glTexImage3D(GL_TEXTURE_3D, 0, GL_RGB16F, 1, 1, 1, 0, GL_RGB, GL_FLOAT, lutTexel);
        glBindTexture(GL_TEXTURE_3D, 0);

        const unsigned char histTexel[4] = {0, 0, 0, 255};
        glGenTextures(1, &state->defaultHistory2d);
        glBindTexture(GL_TEXTURE_2D, state->defaultHistory2d);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, histTexel);
        glBindTexture(GL_TEXTURE_2D, 0);
    }
    state->ndrPqLutNdr10 = createNdrPqDeliveryLut(false);
    state->ndrPqLutNivukx = createNdrPqDeliveryLut(true);
    state->ndrPqLutsReady = state->ndrPqLutNdr10 != 0 && state->ndrPqLutNivukx != 0;
    LOGI(
        "NDR PQ delivery LUTs %s size=%d rgb16f",
        state->ndrPqLutsReady ? "ready" : "fallback",
        kNdrPqLutSize
    );

    // MIRROR FIX v5 — the record path samples the camera AHB directly (no SurfaceTexture transform,
    // unlike the preview which applies getTransformMatrix), so the recorded frame displays UPRIGHT but
    // HORIZONTALLY MIRRORED (back-camera text reads backwards) while the preview stays correct.
    //
    // WHY v4 (a baked S-flip in this quad) DID NOT FIX IT: decode-once is DEFAULT-ON on this target
    // (EglDecodeOnceFlag) and, on the full-sensor path (source-res == surface-res), the SAME quad is
    // applied TWICE — PASS 1 (external->RGB10_A2) and PASS 2 (RGB10_A2->encoder). Any symmetric flip
    // baked into the shared quad therefore CANCELS across the two passes and the output collapses to the
    // raw camera buffer verbatim. So v4's S-flip was inert on the default path and the file stayed mirrored.
    //
    // v5 FIX: this quad is now the IDENTITY map (screen p -> texcoord p, so PASS 1 is a faithful copy).
    // The orientation correction is applied instead through the `uEncOutFlip` vertex uniform, set ONLY on
    // the final encoder-writing programs (single-pass `program` + decode-once PASS 2 `mainProgram2d`) and
    // NEVER on the PASS 1 decode program — so the flip is applied exactly ONCE and survives decode-once.
    // Both the single-pass and decode-once branches now produce the identical net orientation OES(F(p)).
    //
    // AXIS: the container tags the clip with a 90-degree display rotation (ffprobe displaymatrix det=+1,
    // no reflection). Under that rotation a DISPLAYED-horizontal mirror is undone by flipping the
    // ENCODED-vertical (T) axis, so the default is encOutFlipY=1 (portrait un-mirror). Runtime-overridable
    // per axis via debug.record_flip_x / debug.record_flip_y (RecordOutputMirrorFlag) — no rebuild needed;
    // landscape capture wants flipX instead of flipY.
    constexpr GLfloat vertices[] = {
        -1.0f, -1.0f, 0.0f, 0.0f,
         1.0f, -1.0f, 1.0f, 0.0f,
        -1.0f,  1.0f, 0.0f, 1.0f,
         1.0f,  1.0f, 1.0f, 1.0f
    };
    glGenBuffers(1, &state->vertexBuffer);
    glBindBuffer(GL_ARRAY_BUFFER, state->vertexBuffer);
    glBufferData(GL_ARRAY_BUFFER, sizeof(vertices), vertices, GL_STATIC_DRAW);
    glBindBuffer(GL_ARRAY_BUFFER, 0);

    glGenTextures(1, &state->sourceTexture);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, state->sourceTexture);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0);

    eglMakeCurrent(state->display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    setStatus(state, "egl context ready");
    LOGI("EGL context ready version=%d.%d", major, minor);
    return true;
}

// #5: how many id-keyed EGLImageKHR entries to keep. The strict hardware-import reader is 16-deep;
// keep 2x that pool so the cache covers camera/encoder handoff jitter without per-frame re-import churn.
constexpr size_t kEglImageCacheCapacity = 32;

bool configureRgb10HistoryTexture(GLuint texture, int width, int height) {
    glBindTexture(GL_TEXTURE_2D, texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(
        GL_TEXTURE_2D,
        0,
        GL_RGB10_A2,
        width,
        height,
        0,
        GL_RGBA,
        GL_UNSIGNED_INT_2_10_10_10_REV,
        nullptr
    );
    const GLenum error = glGetError();
    glBindTexture(GL_TEXTURE_2D, 0);
    return error == GL_NO_ERROR;
}

bool ensureTemporalHistoryTarget(OffscreenAhbEglEncoderState* state, int width, int height) {
    if (state == nullptr || width <= 0 || height <= 0) return false;
    if (state->historyTexture == 0) {
        glGenTextures(1, &state->historyTexture);
    }
    if (state->historyWriteTexture == 0) {
        glGenTextures(1, &state->historyWriteTexture);
    }
    if (state->historyFbo == 0) {
        glGenFramebuffers(1, &state->historyFbo);
    }
    if (state->historyWidth != width || state->historyHeight != height) {
        if (!configureRgb10HistoryTexture(state->historyTexture, width, height) ||
            !configureRgb10HistoryTexture(state->historyWriteTexture, width, height)) {
            setStatus(state, "RGB10_A2 temporal history texture allocation failed");
            state->historyValid = false;
            return false;
        }
        state->historyWidth = width;
        state->historyHeight = height;
        state->historyValid = false;
    }
    glBindFramebuffer(GL_FRAMEBUFFER, state->historyFbo);
    glFramebufferTexture2D(
        GL_FRAMEBUFFER,
        GL_COLOR_ATTACHMENT0,
        GL_TEXTURE_2D,
        state->historyWriteTexture,
        0
    );
    const GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    if (status != GL_FRAMEBUFFER_COMPLETE) {
        std::ostringstream message;
        message << "RGB10_A2 temporal history framebuffer incomplete status=0x"
                << std::hex << status;
        setStatus(state, message.str());
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        state->historyValid = false;
        return false;
    }
    return true;
}

// Destroy all cached EGLImageKHR entries. Safe to call with no current context
// (eglDestroyImageKHR is an EGLDisplay-level op). The frame path still balances its own
// AHardwareBuffer acquire/release pair; the cache must not hold an extra AHB ref or it can
// pin the camera/reader pool and turn stable slot reuse into import churn at 4K25.
void clearEglImageCache(OffscreenAhbEglEncoderState* state) {
    if (state == nullptr) return;
    const bool canDestroy =
        state->display != EGL_NO_DISPLAY && state->eglDestroyImageKHR != nullptr;
    for (auto& entry : state->eglImageCache) {
        EglImageCacheEntry& cached = entry.second;
        if (canDestroy && cached.image != EGL_NO_IMAGE_KHR) {
            state->eglDestroyImageKHR(state->display, cached.image);
        }
    }
    state->eglImageCache.clear();
    state->eglImageCacheOrder.clear();
}

void destroySurface(OffscreenAhbEglEncoderState* state) {
    if (state == nullptr) return;
    // #5: the AHB pool is bound to the session; a new surface means a new pool, so drop the cache.
    clearEglImageCache(state);
    if (state->display != EGL_NO_DISPLAY && state->encoderSurface != EGL_NO_SURFACE) {
        eglMakeCurrent(state->display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        eglDestroySurface(state->display, state->encoderSurface);
        state->encoderSurface = EGL_NO_SURFACE;
    }
    if (state->encoderWindow != nullptr) {
        ANativeWindow_release(state->encoderWindow);
        state->encoderWindow = nullptr;
    }
    state->surfaceWidth = 0;
    state->surfaceHeight = 0;
    state->surfaceColorSpace = EGL_NONE;
    state->bufferDataSpace = ADATASPACE_UNKNOWN;
    state->bufferDataSpaceApplied = false;
    state->deliveryCompatible = false;
    state->codecBackpressurePacing = true;
    state->swapDurationTotalUs = 0;
    state->swapDurationMaxUs = 0;
    state->slowSwapFrames = 0;
    state->submitDurationTotalUs = 0;
    state->submitDurationMaxUs = 0;
    state->slowSubmitFrames = 0;
    state->historyPresentTotalUs = 0;
    state->historyPresentMaxUs = 0;
    state->slowHistoryPresentFrames = 0;
    state->historyPresentErrorChecks = 0;
    state->temporalHistoryFrames = 0;
    state->decodeOnceFrames = 0;
    state->externalPathFrames = 0;
    state->sourceHistoryFrames = 0;
    state->explicitYuvDecodeFrames = 0;
    state->eglImageCacheHits = 0;
    state->eglImageCacheMisses = 0;
    state->eglImageCacheEvictions = 0;
    state->eglImageCacheIdFailures = 0;
    state->submittedFrames = 0;
    state->failedFrames = 0;
    state->lastPresentationTimeNs = 0;
    // FIX #13 — reset temporal-history state on surface teardown so a NEW recording starts clean.
    // historyValid is the "has previous frame" flag consumed by the AI Motion Compensation temporal
    // blend (see historyUsable). Without this, a new recording of the SAME resolution would find
    // historyValid==true and the stale historyTexture still holding the LAST frame of the PREVIOUS
    // recording, so its first frame would temporally blend against it (a one-frame ghost). The GL
    // texture object itself is owned/freed by destroyState; here we only invalidate the reference so
    // the first frame of the next recording re-captures a fresh history before any blend can occur.
    state->historyValid = false;
    state->historyWidth = 0;
    state->historyHeight = 0;
    state->decodeHistoryValid = false;
    resetSourceEvidence(state);
}

void destroyState(OffscreenAhbEglEncoderState* state) {
    if (state == nullptr) return;
    if (state->display != EGL_NO_DISPLAY && state->context != EGL_NO_CONTEXT) {
        eglMakeCurrent(state->display, EGL_NO_SURFACE, EGL_NO_SURFACE, state->context);
    }
    if (state->sourceTexture != 0) {
        glDeleteTextures(1, &state->sourceTexture);
        state->sourceTexture = 0;
    }
    if (state->lutTexture != 0) {
        glDeleteTextures(1, &state->lutTexture);
        state->lutTexture = 0;
    }
    if (state->defaultLut3d != 0) {
        glDeleteTextures(1, &state->defaultLut3d);
        state->defaultLut3d = 0;
    }
    if (state->ndrPqLutNdr10 != 0) {
        glDeleteTextures(1, &state->ndrPqLutNdr10);
        state->ndrPqLutNdr10 = 0;
    }
    if (state->ndrPqLutNivukx != 0) {
        glDeleteTextures(1, &state->ndrPqLutNivukx);
        state->ndrPqLutNivukx = 0;
    }
    state->ndrPqLutsReady = false;
    if (state->historyTexture != 0) {
        glDeleteTextures(1, &state->historyTexture);
        state->historyTexture = 0;
    }
    if (state->historyWriteTexture != 0) {
        glDeleteTextures(1, &state->historyWriteTexture);
        state->historyWriteTexture = 0;
    }
    if (state->historyFbo != 0) {
        glDeleteFramebuffers(1, &state->historyFbo);
        state->historyFbo = 0;
    }
    if (state->defaultHistory2d != 0) {
        glDeleteTextures(1, &state->defaultHistory2d);
        state->defaultHistory2d = 0;
    }
    state->historyValid = false;
    state->historyWidth = 0;
    state->historyHeight = 0;
    if (state->vertexBuffer != 0) {
        glDeleteBuffers(1, &state->vertexBuffer);
        state->vertexBuffer = 0;
    }
    if (state->program != 0) {
        glDeleteProgram(state->program);
        state->program = 0;
    }
    // #2 decode-once resources.
    if (state->decodeFbo != 0) {
        glDeleteFramebuffers(1, &state->decodeFbo);
        state->decodeFbo = 0;
    }
    if (state->decodeTexture != 0) {
        glDeleteTextures(1, &state->decodeTexture);
        state->decodeTexture = 0;
    }
    if (state->decodeHistoryTexture != 0) {
        glDeleteTextures(1, &state->decodeHistoryTexture);
        state->decodeHistoryTexture = 0;
    }
    state->decodeTexWidth = 0;
    state->decodeTexHeight = 0;
    state->decodeHistoryValid = false;
    if (state->mainProgram2d != 0) {
        glDeleteProgram(state->mainProgram2d);
        state->mainProgram2d = 0;
    }
    if (state->decodeProgram != 0) {
        glDeleteProgram(state->decodeProgram);
        state->decodeProgram = 0;
    }
    if (state->mainProgramExplicitYuv != 0) {
        glDeleteProgram(state->mainProgramExplicitYuv);
        state->mainProgramExplicitYuv = 0;
    }
    if (state->decodeProgramExplicitYuv != 0) {
        glDeleteProgram(state->decodeProgramExplicitYuv);
        state->decodeProgramExplicitYuv = 0;
    }
    state->explicitYuvDecodeAvailable = false;
    if (state->display != EGL_NO_DISPLAY) {
        eglMakeCurrent(state->display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    }
    destroySurface(state);
    if (state->display != EGL_NO_DISPLAY && state->context != EGL_NO_CONTEXT) {
        eglDestroyContext(state->display, state->context);
        state->context = EGL_NO_CONTEXT;
    }
    if (state->display != EGL_NO_DISPLAY) {
        eglTerminate(state->display);
        state->display = EGL_NO_DISPLAY;
    }
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_neoproc_neoproccamx_rendering_OffscreenAhbEglEncoderBridge_nativeCreate(
    JNIEnv*,
    jobject
) {
    return reinterpret_cast<jlong>(new OffscreenAhbEglEncoderState());
}

extern "C" JNIEXPORT void JNICALL
Java_com_neoproc_neoproccamx_rendering_OffscreenAhbEglEncoderBridge_nativeDestroy(
    JNIEnv*,
    jobject,
    jlong handle
) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return;
    std::lock_guard<std::mutex> lock(state->mutex);
    destroyState(state);
    delete state;
}

// Out-of-band creative-LUT upload (record-grade stage). Called only when the LUT changes, so
// the copy-bound per-frame submit path is untouched. size<=1 or null data disables the LUT
// (strength 0). The actual GL_TEXTURE_3D upload is deferred to the next submit on the render
// thread's current context; here we only stash the packed RGB float data under the mutex.
extern "C" JNIEXPORT void JNICALL
Java_com_neoproc_neoproccamx_rendering_OffscreenAhbEglEncoderBridge_nativeSetCreativeLut(
    JNIEnv* env,
    jobject,
    jlong handle,
    jfloatArray data,
    jint size,
    jfloat strength
) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return;
    std::lock_guard<std::mutex> lock(state->mutex);
    const int lutSize = static_cast<int>(size);
    if (data == nullptr || lutSize <= 1) {
        // Disable: keep any existing texture bound as default-off (effStrength gate uses strength).
        state->lutStrength = 0.0f;
        state->lutSize = 0;
        state->lutDirty = false;
        state->pendingLutData.clear();
        state->pendingLutData.shrink_to_fit();
        return;
    }
    const size_t expected = static_cast<size_t>(lutSize) * lutSize * lutSize * 3;
    const size_t provided = static_cast<size_t>(env->GetArrayLength(data));
    if (provided < expected) {
        state->lutStrength = 0.0f;
        state->lutSize = 0;
        state->lutDirty = false;
        return;
    }
    state->pendingLutData.resize(expected);
    env->GetFloatArrayRegion(data, 0, static_cast<jsize>(expected), state->pendingLutData.data());
    state->lutSize = lutSize;
    state->lutStrength = std::clamp(static_cast<float>(strength), 0.0f, 1.0f);
    state->lutDirty = true;
}

extern "C" JNIEXPORT void JNICALL
Java_com_neoproc_neoproccamx_rendering_OffscreenAhbEglEncoderBridge_nativeSetRecordGrade(
    JNIEnv* env,
    jobject,
    jlong handle,
    jfloatArray matrix,
    jfloat contrast
) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return;
    std::lock_guard<std::mutex> lock(state->mutex);
    if (matrix != nullptr && env->GetArrayLength(matrix) >= 9) {
        env->GetFloatArrayRegion(matrix, 0, 9, state->recordGradeMatrix);
    } else {
        const float identity[9] = {
            1.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 1.0f
        };
        for (int i = 0; i < 9; ++i) state->recordGradeMatrix[i] = identity[i];
    }
    state->recordGradeContrast = std::clamp(static_cast<float>(contrast), 0.1f, 4.0f);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_rendering_OffscreenAhbEglEncoderBridge_nativeSetEncoderSurface(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject surface,
    jint width,
    jint height,
    jstring preferredDeliveryModeString
) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(state->mutex);
    if (!ensureEglReady(state)) return JNI_FALSE;

    destroySurface(state);
    if (surface == nullptr) {
        setStatus(state, "encoder surface cleared");
        return JNI_TRUE;
    }

    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) {
        setStatus(state, "ANativeWindow_fromSurface failed");
        return JNI_FALSE;
    }
    const int safeWidth = std::max(1, static_cast<int>(width));
    const int safeHeight = std::max(1, static_cast<int>(height));
    const std::string requestedDeliveryMode = preferredDeliveryMode(env, preferredDeliveryModeString);
    const int32_t requestedDataSpace = dataSpaceForDelivery(requestedDeliveryMode);
    int32_t verifiedDataSpace = ADATASPACE_UNKNOWN;
    bool verifiedDataSpaceMatches = false;
    const NativeWindowDataSpaceApi& dataSpaceApi = nativeWindowDataSpaceApi();
    auto applyRequestedDataSpace = [&]() {
        verifiedDataSpace = ADATASPACE_UNKNOWN;
        verifiedDataSpaceMatches = false;
        if (window == nullptr) return;
        if (!dataSpaceApi.available) {
            LOGW("ANativeWindow dataspace proof unavailable; DEFAULT EGL colorspace remains unproven");
            return;
        }
        const int32_t setDataSpaceResult =
            dataSpaceApi.setBuffersDataSpace(window, requestedDataSpace);
        if (setDataSpaceResult == 0) {
            const int32_t queriedDataSpace = dataSpaceApi.getBuffersDataSpace(window);
            if (queriedDataSpace >= 0) {
                verifiedDataSpace = queriedDataSpace;
                verifiedDataSpaceMatches =
                    dataSpaceMatchesDelivery(requestedDeliveryMode, verifiedDataSpace);
            }
        } else {
            LOGW(
                "ANativeWindow_setBuffersDataSpace failed delivery=%s dataspace=%s result=%d",
                requestedDeliveryMode.c_str(),
                dataSpaceLabel(requestedDataSpace).c_str(),
                setDataSpaceResult
            );
        }
    };
    applyRequestedDataSpace();

    // Try only colorspaces that match the Kotlin route's delivery contract. SDR/LOG must never be
    // promoted through HLG/PQ just because that surface binds; HDR/NDR must use the matching strict
    // transfer. DEFAULT is accepted only when ANativeWindow reports the matching buffers dataspace;
    // otherwise it is intentionally reported as unproven and lets Kotlin fall back to P010.
    struct ColorSpaceOption {
        EGLint value;
        const char* label;
        const char* deliveryMode;        // canonical evidence token: HLG / PQ / SDR / DEFAULT
        const char* canonicalColorSpace; // canonical evidence token the Kotlin gate recognizes
    };
    std::vector<ColorSpaceOption> colorSpaceOptions;
    if (requestedDeliveryMode == "HLG") {
        colorSpaceOptions.push_back({ EGL_GL_COLORSPACE_BT2020_HLG_EXT, "BT2020_HLG", "HLG", "HDR10_HLG" });
        colorSpaceOptions.push_back({ EGL_NONE, "DEFAULT", "DEFAULT", "DEFAULT" });
        colorSpaceOptions.push_back({ EGL_GL_COLORSPACE_SRGB_KHR, "SRGB", "HLG", "SRGB_NONLINEAR" });
    } else if (requestedDeliveryMode == "PQ") {
        colorSpaceOptions.push_back({ EGL_GL_COLORSPACE_BT2020_PQ_EXT, "BT2020_PQ", "PQ", "HDR10_ST2084" });
        colorSpaceOptions.push_back({ EGL_NONE, "DEFAULT", "DEFAULT", "DEFAULT" });
        colorSpaceOptions.push_back({ EGL_GL_COLORSPACE_SRGB_KHR, "SRGB", "PQ", "SRGB_NONLINEAR" });
    } else {
        colorSpaceOptions.push_back({ EGL_NONE, "DEFAULT", "DEFAULT", "DEFAULT" });
        colorSpaceOptions.push_back({ EGL_GL_COLORSPACE_SRGB_KHR, "SRGB", "SDR", "SRGB_NONLINEAR" });
    }
    EGLSurface eglSurface = EGL_NO_SURFACE;
    EGLint chosenColorSpace = EGL_NONE;
    std::string chosenDeliveryMode = "UNKNOWN";
    std::string chosenColorSpaceLabel = "NONE";
    std::string chosenCanonicalColorSpace = "UNKNOWN";
    for (size_t optionIndex = 0; optionIndex < colorSpaceOptions.size(); ++optionIndex) {
        const auto& option = colorSpaceOptions[optionIndex];
        EGLint attribs[3];
        int attribCount = 0;
        if (option.value != EGL_NONE) {
            attribs[attribCount++] = EGL_GL_COLORSPACE_KHR;
            attribs[attribCount++] = option.value;
        }
        attribs[attribCount] = EGL_NONE;
        eglSurface = eglCreateWindowSurface(state->display, state->config, window, attribs);
        if (eglSurface != EGL_NO_SURFACE) {
            chosenColorSpace = option.value;
            chosenDeliveryMode = option.deliveryMode;
            chosenColorSpaceLabel = option.label;
            chosenCanonicalColorSpace = option.canonicalColorSpace;
            if (option.value == EGL_NONE && verifiedDataSpaceMatches) {
                chosenDeliveryMode = requestedDeliveryMode;
                chosenColorSpaceLabel = std::string(option.label) + "+" + dataSpaceLabel(verifiedDataSpace);
                chosenCanonicalColorSpace = dataSpaceLabel(verifiedDataSpace);
            } else if (option.value == EGL_GL_COLORSPACE_SRGB_KHR &&
                       (requestedDeliveryMode == "HLG" || requestedDeliveryMode == "PQ")) {
                // NO-DOWNGRADE: the SRGB last-resort surface carries an sRGB (SDR) transfer curve.
                // It may be reported for the HLG/PQ route ONLY when the independently-queried
                // ANativeWindow buffers dataspace verifiably matches that transfer (the SAME proof
                // the DEFAULT branch above uses). Otherwise it is genuinely an SDR surface and is
                // labelled truthfully as SDR, so a non-HDR surface is never reported HDR-compatible
                // and Kotlin falls back to the proven P010 HDR path instead of tagging sRGB HLG/PQ.
                if (verifiedDataSpaceMatches) {
                    chosenDeliveryMode = requestedDeliveryMode;
                    chosenColorSpaceLabel = std::string(option.label) + "+" + dataSpaceLabel(verifiedDataSpace);
                    chosenCanonicalColorSpace = dataSpaceLabel(verifiedDataSpace);
                } else {
                    chosenDeliveryMode = "SDR";
                    chosenColorSpaceLabel = std::string(option.label) + "(SDR-unproven-HDR)";
                    chosenCanonicalColorSpace = "SRGB_NONLINEAR";
                }
            }
            LOGI(
                "eglCreateWindowSurface ok colorspace=%s delivery=%s dataspace=%s dataspaceMatches=%s",
                chosenColorSpaceLabel.c_str(),
                chosenDeliveryMode.c_str(),
                dataSpaceLabel(verifiedDataSpace).c_str(),
                verifiedDataSpaceMatches ? "true" : "false"
            );
            break;
        }
        const EGLint error = eglGetError();
        LOGW(
            "eglCreateWindowSurface failed colorspace=%s delivery=%s error=0x%04x; reacquiring native window before retry",
            option.label,
            option.deliveryMode,
            error
        );
        if (optionIndex + 1u < colorSpaceOptions.size()) {
            ANativeWindow_release(window);
            window = ANativeWindow_fromSurface(env, surface);
            if (window == nullptr) {
                setStatus(state, "ANativeWindow_fromSurface retry failed");
                break;
            }
            applyRequestedDataSpace();
        }
    }
    if (eglSurface == EGL_NO_SURFACE) {
        if (window != nullptr) {
            ANativeWindow_release(window);
        }
        setStatus(
            state,
            std::string("eglCreateWindowSurface failed for requested delivery ") + requestedDeliveryMode
        );
        return JNI_FALSE;
    }
    // Surface-level proof: query the colorspace the driver actually assigned to the
    // created window surface instead of trusting the requested attrib. DEFAULT is proven
    // separately through the ANativeWindow buffers dataspace queried above.
    EGLint verifiedColorSpace = chosenColorSpace;
    if (eglQuerySurface(state->display, eglSurface, EGL_GL_COLORSPACE_KHR, &verifiedColorSpace) != EGL_TRUE) {
        LOGW(
            "eglQuerySurface EGL_GL_COLORSPACE_KHR failed; keeping requested colorspace=%s",
            chosenColorSpaceLabel.c_str()
        );
        verifiedColorSpace = chosenColorSpace;
    }
    state->encoderWindow = window;
    state->encoderSurface = eglSurface;
    state->surfaceWidth = safeWidth;
    state->surfaceHeight = safeHeight;
    state->surfaceColorSpace = verifiedColorSpace;
    state->bufferDataSpace = verifiedDataSpace;
    state->bufferDataSpaceApplied = verifiedDataSpaceMatches;
    state->deliveryModeLabel = chosenDeliveryMode;
    state->deliveryColorSpaceLabel = chosenCanonicalColorSpace;
    state->deliveryCompatible =
        state->redBits == 10 &&
        state->greenBits == 10 &&
        state->blueBits == 10 &&
        state->alphaBits == 2 &&
        state->recordable == EGL_TRUE;
    if (!state->deliveryCompatible) {
        destroySurface(state);
        setStatus(state, "offscreen EGL encoder surface proof rejected");
        return JNI_FALSE;
    }
    setStatus(
        state,
        std::string("offscreen EGL RGB10_A2 encoder surface ready delivery=") + chosenDeliveryMode
    );
    LOGI(
        "Encoder EGL surface ready %dx%d rgba=%d/%d/%d/%d colorspace=%s dataspace=%s delivery=%s",
        safeWidth,
        safeHeight,
        state->redBits,
        state->greenBits,
        state->blueBits,
        state->alphaBits,
        chosenColorSpaceLabel.c_str(),
        dataSpaceLabel(state->bufferDataSpace).c_str(),
        chosenDeliveryMode.c_str()
    );
    return JNI_TRUE;
}

// #5 strict-record default: toggle per-AHB EGLImage caching. The cache is accepted only when
// AHardwareBuffer_getId is available so every entry is keyed by a stable GraphicBuffer identity; pointer
// fallback remains observable in telemetry but is not used for default-on strict recording.
extern "C" JNIEXPORT void JNICALL
Java_com_neoproc_neoproccamx_rendering_OffscreenAhbEglEncoderBridge_nativeSetEglImageCacheEnabled(
    JNIEnv*,
    jobject,
    jlong handle,
    jboolean enabled
) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return;
    std::lock_guard<std::mutex> lock(state->mutex);
    const bool requestedEnable = enabled == JNI_TRUE;
    const bool enable = requestedEnable && state->eglImageCacheStableIds;
    if (requestedEnable && !state->eglImageCacheStableIds) {
        clearEglImageCache(state);
        setStatus(state, "EGL image cache disabled: stable AHardwareBuffer_getId unavailable");
        LOGW("EGL image cache requested but stable AHardwareBuffer_getId is unavailable");
    }
    if (!enable && state->eglImageCacheEnabled) {
        clearEglImageCache(state);
    }
    state->eglImageCacheEnabled = enable;
}

// #2 device-gated (default ON on the measured target): toggle decode-once. Pushed at setEncoderSurface time.
extern "C" JNIEXPORT void JNICALL
Java_com_neoproc_neoproccamx_rendering_OffscreenAhbEglEncoderBridge_nativeSetDecodeOnceEnabled(
    JNIEnv*,
    jobject,
    jlong handle,
    jboolean enabled
) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return;
    std::lock_guard<std::mutex> lock(state->mutex);
    state->decodeOnceEnabled = enabled == JNI_TRUE;
}

// NDR10+/NIVUKX input-transfer decode (default ON): true => decode the camera frame as PQ (ST.2084),
// matching the HDR10_PLUS capture. Pushed at setEncoderSurface so a flag change takes effect on the
// next recording (matches decode-once / EGL-cache). Reversible via debug.ndr_pq_decode.
// MIRRORFIX v5 — set the per-axis record output flip. flipX mirrors encoded-horizontal (S); flipY mirrors
// encoded-vertical (T). Applied once on the final encoder-writing pass (survives decode-once). Pushed at
// setEncoderSurface so a flag change takes effect on the next recording (matches decode-once/EGL-cache).
extern "C" JNIEXPORT void JNICALL
Java_com_neoproc_neoproccamx_rendering_OffscreenAhbEglEncoderBridge_nativeSetEncoderOutputFlip(
    JNIEnv*,
    jobject,
    jlong handle,
    jboolean flipX,
    jboolean flipY
) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return;
    std::lock_guard<std::mutex> lock(state->mutex);
    state->encOutFlipX = (flipX == JNI_TRUE) ? 1.0f : 0.0f;
    state->encOutFlipY = (flipY == JNI_TRUE) ? 1.0f : 0.0f;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_rendering_OffscreenAhbEglEncoderBridge_nativeSubmitHardwareBuffer(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject hardwareBufferObject,
    jint width,
    jint height,
    jint,
    jlong presentationTimeNs,
    jfloat sharpness,
    jfloat denoise,
    jfloat chromaDenoise,
    jfloat temporalBlend,
    jint ndrDeliveryMode,
    jfloat ndrWarmSkinRedTame,
    jboolean preserveInputPqMaster,
    jint inputTransfer,
    jint inputPrimaries,
    jint inputRange
) {
    auto* state = fromHandle(handle);
    if (state == nullptr || hardwareBufferObject == nullptr) return JNI_FALSE;
    const float ndrDeliveryModeValue = (ndrDeliveryMode == 1) ? 1.0f : 0.0f;
    const float ndrWarmSkinRedTameValue = std::clamp(ndrWarmSkinRedTame, 0.0f, 1.0f);
    const bool inputIsPq = inputTransfer == 1;
    std::lock_guard<std::mutex> lock(state->mutex);
    state->lastInputTransfer = inputTransfer;
    state->lastInputPrimaries = inputPrimaries;
    state->lastInputRange = inputRange;
    const bool flatPqMasterPassthrough =
        preserveInputPqMaster == JNI_TRUE &&
        inputIsPq &&
        state->deliveryModeLabel == "PQ";
    const float applyNdrPqDeliveryToneMap =
        state->deliveryModeLabel == "PQ" && !flatPqMasterPassthrough ? 1.0f : 0.0f;
    const auto submitStart = std::chrono::steady_clock::now();
    if (!ensureEglReady(state) || state->encoderSurface == EGL_NO_SURFACE) {
        if (state != nullptr) ++state->failedFrames;
        return JNI_FALSE;
    }
    if (state->submittedFrames == 0u && flatPqMasterPassthrough) {
        LOGI(
            "NDR flat master: PQ input codes preserved on PQ encoder surface; offline delivery tone-map deferred"
        );
    }
    AHardwareBuffer* hardwareBuffer = AHardwareBuffer_fromHardwareBuffer(env, hardwareBufferObject);
    if (hardwareBuffer == nullptr) {
        ++state->failedFrames;
        setStatus(state, "AHardwareBuffer_fromHardwareBuffer failed");
        return JNI_FALSE;
    }
    // AHardwareBuffer_fromHardwareBuffer() returns a NON-owning pointer (it does not
    // acquire a reference — ownership stays with the Java HardwareBuffer). Every return
    // path below calls AHardwareBuffer_release() exactly once, so we must acquire here to
    // keep the GraphicBuffer refcount balanced. Without this the native RefBase strong
    // count underflows on the first submitted frame and the process aborts with
    // "decStrong() called too many times" from MediaCodec's shared BufferQueue machinery.
    // Mirrors native_buffer_bridge.cpp / vulkan_bridge.cpp which acquire before releasing.
    AHardwareBuffer_acquire(hardwareBuffer);
    AHardwareBuffer_Desc description{};
    AHardwareBuffer_describe(hardwareBuffer, &description);
    const HardwareBufferSourceProof sourceProof = classifySourcePrecision(description);
    state->sourceFormat = sourceProof.format;
    state->sourceBitDepth = sourceProof.bitDepth;
    state->sourcePrecision = sourceProof.precision;
    state->sourcePrecisionProven = sourceProof.proven;
    if (!sourceProof.proven) {
        // The vivo/Qualcomm camera delivers an opaque, implementation-defined vendor pixel
        // format (e.g. 0x7FA30C09) whose bit depth cannot be read from the enum, so
        // classifySourcePrecision() returns {bitDepth=0, "UNKNOWN", proven=false}. When the
        // DESTINATION encoder surface is a proven 10-bit EGL_RGB10_A2 HDR surface, the buffer
        // is sampled through samplerExternalOES — which decodes ANY opaque vendor format at
        // full native precision on the GPU — and rendered into that 10-bit destination. The
        // delivered precision is therefore provably >= the 10-bit output, so we trust it
        // rather than dropping every frame (which otherwise empties the whole recording and
        // silently degrades the fast zero-copy path to the copy-bound P010 fallback).
        //
        // Strictly no-downgrade: the guard fires ONLY for a truly-opaque source
        // (bitDepth==0 && precision=="UNKNOWN", i.e. the classifier's `default` branch). The opaque
        // vendor AHB is decoded by samplerExternalOES at full native precision on the GPU, and that
        // import is TRANSFER-FUNCTION-AGNOSTIC: the vivo/Qualcomm camera delivers the SAME importable
        // vendor buffer for SDR, LOG, HLG and PQ alike — the transfer curve only changes how the
        // destination interprets the sampled pixels, never whether the source buffer can be sampled.
        // So we accept the opaque vendor source for ALL delivery modes provided the DESTINATION is a
        // proven 10-bit EGL_RGB10_A2 surface. Known 8-bit formats classify with bitDepth==8 and never
        // enter here, so a real 8-bit source is never promoted to 10-bit, and destProven10Bit
        // guarantees the encoded output is genuinely 10-bit (no-downgrade for every mode).
        const bool destProven10Bit =
            state->deliveryCompatible &&
            state->redBits == 10 && state->greenBits == 10 &&
            state->blueBits == 10 && state->alphaBits == 2;
        const bool opaqueVendorSource =
            sourceProof.bitDepth == 0 && sourceProof.precision == "UNKNOWN";
        // FIX #9 — dropped the former `&& hdrDelivery` gate: because the opaque vendor AHB import is
        // transfer-function-agnostic, SDR/LOG opaque-vendor frames now take the same fast offscreen
        // GPU path as HDR instead of being dropped to the copy-bound P010 fallback (~3fps). HDR
        // behavior is unchanged (it already satisfied this condition); the only added coverage is
        // SDR/LOG opaque-vendor sources rendered into a proven 10-bit destination.
        if (destProven10Bit && opaqueVendorSource) {
            state->sourcePrecision = "TRUSTED_EXTERNAL_10_BIT";
            state->sourceBitDepth = 10;
            state->sourcePrecisionProven = true;
        } else {
            AHardwareBuffer_release(hardwareBuffer);
            ++state->failedFrames;
            setStatus(
                state,
                "offscreen source precision rejected format=" + sourceProof.format
            );
            return JNI_FALSE;
        }
    }
    if (state->submittedFrames == 0u) {
        LOGI(
            "First offscreen source accepted format=%s bitDepth=%d precision=%s",
            state->sourceFormat.c_str(),
            state->sourceBitDepth,
            state->sourcePrecision.c_str()
        );
        // Provable running-build marker: grep logcat for "MIRRORFIX".
        LOGI("MIRRORFIX v5 active: identity quad + uEncOutFlip(x=%.0f,y=%.0f) applied once (survives decode-once)",
             state->encOutFlipX, state->encOutFlipY);
    }
    if (eglMakeCurrent(state->display, state->encoderSurface, state->encoderSurface, state->context) != EGL_TRUE) {
        AHardwareBuffer_release(hardwareBuffer);
        ++state->failedFrames;
        setStatus(state, "eglMakeCurrent submit failed");
        return JNI_FALSE;
    }
    EGLClientBuffer clientBuffer = state->eglGetNativeClientBufferANDROID(hardwareBuffer);
    if (clientBuffer == nullptr) {
        AHardwareBuffer_release(hardwareBuffer);
        ++state->failedFrames;
        setStatus(state, "eglGetNativeClientBufferANDROID failed");
        return JNI_FALSE;
    }

    const EGLint imageAttrs[] = {
        EGL_IMAGE_PRESERVED_KHR, EGL_TRUE,
        EGL_NONE
    };
    // #5: reuse the cached image for this AHB slot when enabled; otherwise create per-frame (shipped
    // default). When caching, the cache owns the image (destroyed on eviction / surface teardown), so
    // the end-of-frame destroy below only fires when caching is OFF.
    EGLImageKHR eglImage = EGL_NO_IMAGE_KHR;
    const uint64_t eglImageCacheKey = eglImageCacheKeyFor(state, hardwareBuffer);
    if (state->eglImageCacheEnabled) {
        auto cached = state->eglImageCache.find(eglImageCacheKey);
        if (cached != state->eglImageCache.end()) {
            eglImage = cached->second.image;
            ++state->eglImageCacheHits;
        }
    }
    if (eglImage == EGL_NO_IMAGE_KHR) {
        if (state->eglImageCacheEnabled) {
            ++state->eglImageCacheMisses;
        }
        eglImage = state->eglCreateImageKHR(
            state->display,
            EGL_NO_CONTEXT,
            EGL_NATIVE_BUFFER_ANDROID,
            clientBuffer,
            imageAttrs
        );
        if (eglImage == EGL_NO_IMAGE_KHR) {
            AHardwareBuffer_release(hardwareBuffer);
            ++state->failedFrames;
            setStatus(state, "eglCreateImageKHR failed");
            return JNI_FALSE;
        }
        if (state->eglImageCacheEnabled) {
            state->eglImageCache.emplace(
                eglImageCacheKey,
                EglImageCacheEntry{eglImage}
            );
            state->eglImageCacheOrder.push_back(eglImageCacheKey);
            while (state->eglImageCacheOrder.size() > kEglImageCacheCapacity) {
                uint64_t oldest = state->eglImageCacheOrder.front();
                state->eglImageCacheOrder.erase(state->eglImageCacheOrder.begin());
                auto oldIt = state->eglImageCache.find(oldest);
                if (oldIt != state->eglImageCache.end()) {
                    if (oldIt->second.image != EGL_NO_IMAGE_KHR) {
                        state->eglDestroyImageKHR(state->display, oldIt->second.image);
                    }
                    state->eglImageCache.erase(oldIt);
                }
                ++state->eglImageCacheEvictions;
            }
        }
    }

    glActiveTexture(GL_TEXTURE0);
    if (state->sourceTexture == 0) {
        glGenTextures(1, &state->sourceTexture);
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, state->sourceTexture);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    }
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, state->sourceTexture);
    state->glEGLImageTargetTexture2DOES(GL_TEXTURE_EXTERNAL_OES, eglImage);

    const int sourceWidth = std::max(1, static_cast<int>(width));
    const int sourceHeight = std::max(1, static_cast<int>(height));
    const int targetWidth = std::max(1, state->surfaceWidth);
    const int targetHeight = std::max(1, state->surfaceHeight);
    const float requestedTemporalBlend =
        std::clamp(static_cast<float>(temporalBlend), 0.0f, 1.0f);
    // NDR's per-buffer truth is BT.2020/PQ with a declared YUV range. When GL_EXT_YUV_target is
    // available, bypass the vendor's implicit external-texture conversion and apply the fixed
    // BT.2020-NCL matrix/range expansion in our shader. Unknown metadata stays on the proven fallback.
    const bool useExplicitYuv =
        inputIsPq &&
        inputPrimaries == 2 &&
        (inputRange == 1 || inputRange == 2) &&
        state->explicitYuvDecodeAvailable &&
        state->mainProgramExplicitYuv != 0 &&
        state->decodeProgramExplicitYuv != 0;
    const GLuint activeDecodeProgram = useExplicitYuv
        ? state->decodeProgramExplicitYuv
        : state->decodeProgram;
    const GLint activeDecodeTextureLocation = useExplicitYuv
        ? state->decodeExplicitYuvTexLoc
        : state->decodeTexLoc;
    if (state->submittedFrames == 0u) {
        LOGI(
            "NDR input decode explicitYuv=%s transfer=%d primaries=%d range=%d",
            useExplicitYuv ? "true" : "false",
            inputTransfer,
            inputPrimaries,
            inputRange
        );
    }
    // #2 decode-once: only when enabled, both programs linked, and source-res == surface-res (so the
    // RGB10_A2 sample lands on texel centres -> same 10-bit source values as the external texture).
    const bool useDecodeOnce =
        state->decodeOnceEnabled && state->mainProgram2d != 0 && activeDecodeProgram != 0 &&
        sourceWidth == targetWidth && sourceHeight == targetHeight;
    const bool useDecodeTextureHistory = useDecodeOnce && requestedTemporalBlend > 0.0f;
    // For the external single-pass path, keep temporal denoise active without rendering the full
    // processed frame into an intermediate texture and blitting it to the encoder surface. The shader
    // already supports pre-grade history for decode-once; here that history is a 10-bit RGB10_A2 copy
    // of the previous P010 source center sample. The current frame still runs the full external
    // denoise/sharpen/PQ shader directly into the proven RGB10_A2 encoder surface.
    const bool useExternalSourceHistory =
        !useDecodeOnce && requestedTemporalBlend > 0.0f && activeDecodeProgram != 0;
    const bool renderToTemporalHistory =
        requestedTemporalBlend > 0.0f && !useDecodeTextureHistory && !useExternalSourceHistory;
    const bool needsRgb10HistoryTarget = renderToTemporalHistory || useExternalSourceHistory;
    if (needsRgb10HistoryTarget && !ensureTemporalHistoryTarget(state, targetWidth, targetHeight)) {
        if (!state->eglImageCacheEnabled) {
            state->eglDestroyImageKHR(state->display, eglImage);
        }
        AHardwareBuffer_release(hardwareBuffer);
        ++state->failedFrames;
        return JNI_FALSE;
    }
    if (renderToTemporalHistory) {
        // Reattach every frame after the texture-name swap below; FBO attachments keep the GL object,
        // not the C++ variable, so binding once would feed back into the previous history texture.
        glBindFramebuffer(GL_FRAMEBUFFER, state->historyFbo);
        glFramebufferTexture2D(
            GL_FRAMEBUFFER,
            GL_COLOR_ATTACHMENT0,
            GL_TEXTURE_2D,
            state->historyWriteTexture,
            0
        );
    } else {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }
    glViewport(0, 0, targetWidth, targetHeight);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_BLEND);
    if (useDecodeOnce) {
        if (state->submittedFrames == 0u) {
            LOGI("decode-once ACTIVE (src=%dx%d == surface) two-pass RGB10_A2 record path", sourceWidth, sourceHeight);
            if (useDecodeTextureHistory) {
                LOGI("RGB10_A2 decode-history temporal blend ACTIVE (copy-free texture swap)");
            }
        }
        // Ensure the RGB10_A2 decode target at source resolution. P010 is a 10-bit source and the
        // encoder surface is RGB10_A2, so this preserves every recorded code value while avoiding the
        // RGBA16F bandwidth that made the original two-pass path slower than the external path.
        if (state->decodeTexture == 0) glGenTextures(1, &state->decodeTexture);
        if (state->decodeHistoryTexture == 0) glGenTextures(1, &state->decodeHistoryTexture);
        if (state->decodeTexWidth != sourceWidth || state->decodeTexHeight != sourceHeight) {
            auto configureDecodeTexture = [sourceWidth, sourceHeight](GLuint texture) {
                glActiveTexture(GL_TEXTURE0);
                glBindTexture(GL_TEXTURE_2D, texture);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
                glTexImage2D(
                    GL_TEXTURE_2D,
                    0,
                    GL_RGB10_A2,
                    sourceWidth,
                    sourceHeight,
                    0,
                    GL_RGBA,
                    GL_UNSIGNED_INT_2_10_10_10_REV,
                    nullptr
                );
            };
            configureDecodeTexture(state->decodeTexture);
            configureDecodeTexture(state->decodeHistoryTexture);
            glBindTexture(GL_TEXTURE_2D, 0);
            state->decodeTexWidth = sourceWidth;
            state->decodeTexHeight = sourceHeight;
            state->decodeHistoryValid = false;
        }
        if (state->decodeFbo == 0) glGenFramebuffers(1, &state->decodeFbo);

        // PASS 1 — decode the external YUV texture ONCE into the RGB10_A2 target.
        glBindFramebuffer(GL_FRAMEBUFFER, state->decodeFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                               state->decodeTexture, 0);
        glViewport(0, 0, sourceWidth, sourceHeight);
        glUseProgram(activeDecodeProgram);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, state->sourceTexture);
        glUniform1i(activeDecodeTextureLocation, 0);
        if (useExplicitYuv) {
            glUniform1i(state->decodeExplicitYuvPrimariesLoc, inputPrimaries);
            glUniform1i(state->decodeExplicitYuvRangeLoc, inputRange);
        }
        glBindBuffer(GL_ARRAY_BUFFER, state->vertexBuffer);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), reinterpret_cast<void*>(0));
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), reinterpret_cast<void*>(2 * sizeof(GLfloat)));
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

        // PASS 2 — full grade/denoise/sharpen shader sampling the RGB10_A2 decode (sampler2D) into the
        // encoder surface (default framebuffer). Same uniform values as the external path.
        glBindFramebuffer(GL_FRAMEBUFFER, renderToTemporalHistory ? state->historyFbo : 0);
        glViewport(0, 0, targetWidth, targetHeight);
        glUseProgram(state->mainProgram2d);

        float gradeMat2[9];
        for (int i = 0; i < 9; ++i) gradeMat2[i] = state->recordGradeMatrix[i];
        const float gradeContrastF2 = state->recordGradeContrast;
        const float tb2 = requestedTemporalBlend;

        // Lazy 3D-LUT upload (idempotent; same guard as the external path).
        if (state->lutDirty && state->lutSize > 1 &&
            state->pendingLutData.size() >=
                static_cast<size_t>(state->lutSize) * state->lutSize * state->lutSize * 3) {
            if (state->lutTexture == 0) glGenTextures(1, &state->lutTexture);
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_3D, state->lutTexture);
            glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
            glTexImage3D(GL_TEXTURE_3D, 0, GL_RGB16F, state->lutSize, state->lutSize, state->lutSize,
                         0, GL_RGB, GL_FLOAT, state->pendingLutData.data());
            glBindTexture(GL_TEXTURE_3D, 0);
            state->lutDirty = false;
            state->pendingLutData.clear();
            state->pendingLutData.shrink_to_fit();
        }

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, state->decodeTexture);
        glUniform1i(state->mainLoc2d.texture, 0);
        glUniform2f(state->mainLoc2d.texel, 1.0f / static_cast<float>(sourceWidth),
                    1.0f / static_cast<float>(sourceHeight));
        glUniform1f(state->mainLoc2d.sharpness, std::clamp(static_cast<float>(sharpness), 0.0f, 1.0f));
        glUniform1f(state->mainLoc2d.denoise, std::clamp(static_cast<float>(denoise), 0.0f, 1.0f));
        glUniform1f(state->mainLoc2d.chromaDenoise, std::clamp(static_cast<float>(chromaDenoise), 0.0f, 1.0f));
        glUniformMatrix3fv(state->mainLoc2d.gradeMatrix, 1, GL_TRUE, gradeMat2);
        glUniform1f(state->mainLoc2d.gradeContrast, gradeContrastF2);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_3D, state->lutTexture != 0 ? state->lutTexture : state->defaultLut3d);
        glUniform1i(state->mainLoc2d.lut, 1);
        const float effLutStrength2 =
            (state->deliveryModeLabel == "SDR" && state->lutTexture != 0)
                ? std::clamp(state->lutStrength, 0.0f, 1.0f)
                : 0.0f;
        glUniform1f(state->mainLoc2d.lutStrength, effLutStrength2);
        const bool historyUsable2 = useDecodeTextureHistory &&
            state->decodeHistoryValid &&
            state->decodeHistoryTexture != 0 &&
            state->decodeTexWidth == sourceWidth &&
            state->decodeTexHeight == sourceHeight;
        glActiveTexture(GL_TEXTURE2);
        glBindTexture(
            GL_TEXTURE_2D,
            historyUsable2 ? state->decodeHistoryTexture : state->defaultHistory2d
        );
        glUniform1i(state->mainLoc2d.history, 2);
        glUniform1f(state->mainLoc2d.temporalBlend, tb2);
        glUniform1f(state->mainLoc2d.historyValid, historyUsable2 ? 1.0f : 0.0f);
        glUniform1f(state->mainLoc2d.historyPreGrade, useDecodeTextureHistory ? 1.0f : 0.0f);
        glUniform1f(state->mainLoc2d.hdrToPqConvert, applyNdrPqDeliveryToneMap);
        glUniform1f(state->mainLoc2d.ndrDeliveryMode, ndrDeliveryModeValue);
        const GLuint ndrPqLut2 =
            ndrDeliveryModeValue > 0.5f ? state->ndrPqLutNivukx : state->ndrPqLutNdr10;
        const bool ndrPqLutReady2 = state->deliveryModeLabel == "PQ" && ndrPqLut2 != 0;
        glActiveTexture(GL_TEXTURE3);
        glBindTexture(
            GL_TEXTURE_3D,
            ndrPqLutReady2 ? ndrPqLut2 : state->defaultLut3d
        );
        glUniform1i(state->mainLoc2d.ndrPqLut, 3);
        glUniform1f(state->mainLoc2d.ndrPqLutReady, ndrPqLutReady2 ? 1.0f : 0.0f);
        glUniform1f(state->mainLoc2d.inputPq, inputIsPq ? 1.0f : 0.0f);
        glUniform1f(state->mainLoc2d.ndrWarmSkinRedTame, ndrWarmSkinRedTameValue);
        // MIRRORFIX v5 — apply the output flip once on PASS 2 (the encoder-writing pass). PASS 1
        // (decodeProgram) deliberately leaves uEncOutFlip at its 0 default so the decode copy stays
        // identity and the flip is applied exactly once across the two-pass decode-once path.
        glUniform2f(state->mainLoc2d.encOutFlip, state->encOutFlipX, state->encOutFlipY);
        glActiveTexture(GL_TEXTURE0);
        glBindBuffer(GL_ARRAY_BUFFER, state->vertexBuffer);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), reinterpret_cast<void*>(0));
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), reinterpret_cast<void*>(2 * sizeof(GLfloat)));
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glDisableVertexAttribArray(0);
        glDisableVertexAttribArray(1);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindTexture(GL_TEXTURE_2D, 0);
        if (useDecodeTextureHistory) {
            std::swap(state->decodeTexture, state->decodeHistoryTexture);
            state->decodeHistoryValid = true;
            ++state->temporalHistoryFrames;
        }
    } else {
    if (state->submittedFrames == 0u) {
        LOGI(
            "single-pass external record path ACTIVE (decodeOnceEnabled=%s sourceHistory=%s explicitYuv=%s src=%dx%d surface=%dx%d)",
            state->decodeOnceEnabled ? "true" : "false",
            useExternalSourceHistory ? "true" : "false",
            useExplicitYuv ? "true" : "false",
            sourceWidth,
            sourceHeight,
            targetWidth,
            targetHeight
        );
    }
    if (renderToTemporalHistory) {
        // Reattach every frame after the texture-name swap below; FBO attachments keep the GL object,
        // not the C++ variable, so binding once would feed back into the previous history texture.
        glBindFramebuffer(GL_FRAMEBUFFER, state->historyFbo);
        glFramebufferTexture2D(
            GL_FRAMEBUFFER,
            GL_COLOR_ATTACHMENT0,
            GL_TEXTURE_2D,
            state->historyWriteTexture,
            0
        );
    } else {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
    }
    glViewport(0, 0, targetWidth, targetHeight);
    const GLuint activeMainProgram = useExplicitYuv
        ? state->mainProgramExplicitYuv
        : state->program;
    const MainProgramLoc& activeMainLoc = useExplicitYuv
        ? state->mainLocExplicitYuv
        : state->mainLocExternal;
    glUseProgram(activeMainProgram);
    glUniform1i(activeMainLoc.texture, 0);
    if (useExplicitYuv) {
        glUniform1i(activeMainLoc.inputPrimaries, inputPrimaries);
        glUniform1i(activeMainLoc.inputRange, inputRange);
    }
    // MIRRORFIX v5 — apply the output flip once on this final encoder-writing program.
    glUniform2f(activeMainLoc.encOutFlip, state->encOutFlipX, state->encOutFlipY);
    glUniform2f(activeMainLoc.texel, 1.0f / static_cast<float>(sourceWidth), 1.0f / static_cast<float>(sourceHeight));
    glUniform1f(activeMainLoc.sharpness, std::clamp(static_cast<float>(sharpness), 0.0f, 1.0f));
    glUniform1f(activeMainLoc.denoise, std::clamp(static_cast<float>(denoise), 0.0f, 1.0f));
    glUniform1f(activeMainLoc.chromaDenoise, std::clamp(static_cast<float>(chromaDenoise), 0.0f, 1.0f));

    // ── Record-grade stage (identity/OFF by default) ────────────────────────────────────
    // Grade matrix is row-major (transpose=GL_TRUE); identity default => bit-identical output.
    float gradeMat[9];
    for (int i = 0; i < 9; ++i) gradeMat[i] = state->recordGradeMatrix[i];
    const float gradeContrastF = state->recordGradeContrast;
    const float tb = requestedTemporalBlend;

    // Lazy 3D-LUT upload (once on change) on the live encoder GL context.
    if (state->lutDirty && state->lutSize > 1 &&
        state->pendingLutData.size() >=
            static_cast<size_t>(state->lutSize) * state->lutSize * state->lutSize * 3) {
        if (state->lutTexture == 0) {
            glGenTextures(1, &state->lutTexture);
        }
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_3D, state->lutTexture);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_3D, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
        glTexImage3D(GL_TEXTURE_3D, 0, GL_RGB16F, state->lutSize, state->lutSize, state->lutSize,
                     0, GL_RGB, GL_FLOAT, state->pendingLutData.data());
        glBindTexture(GL_TEXTURE_3D, 0);
        state->lutDirty = false;
        state->pendingLutData.clear();
        state->pendingLutData.shrink_to_fit();
    }

    glUniformMatrix3fv(activeMainLoc.gradeMatrix, 1, GL_TRUE, gradeMat);
    glUniform1f(activeMainLoc.gradeContrast, gradeContrastF);

    // Creative 3D LUT on GL_TEXTURE1. SDR-gated: on HLG/PQ the effective strength is forced 0,
    // so the proven HDR transport is never colour-shifted.
    glActiveTexture(GL_TEXTURE1);
    glBindTexture(GL_TEXTURE_3D, state->lutTexture != 0 ? state->lutTexture : state->defaultLut3d);
    glUniform1i(activeMainLoc.lut, 1);
    const float effLutStrength =
        (state->deliveryModeLabel == "SDR" && state->lutTexture != 0)
            ? std::clamp(state->lutStrength, 0.0f, 1.0f)
            : 0.0f;
    glUniform1f(activeMainLoc.lutStrength, effLutStrength);

    // Temporal history on GL_TEXTURE2. Only marked valid when blend>0 and a same-size history
    // frame exists; otherwise the shader path is a pure identity (no ghosting on the default). On the
    // source-history path, uHistoryPreGrade=1 and the texture is previous decoded P010 stored in a
    // 10-bit RGB10_A2 texture; on the processed-history fallback it remains previous processed output.
    const bool historyUsable = tb > 0.0f && state->historyValid && state->historyTexture != 0 &&
        state->historyWidth == targetWidth && state->historyHeight == targetHeight;
    glActiveTexture(GL_TEXTURE2);
    glBindTexture(GL_TEXTURE_2D, state->historyTexture != 0 ? state->historyTexture : state->defaultHistory2d);
    glUniform1i(activeMainLoc.history, 2);
    glUniform1f(activeMainLoc.temporalBlend, tb);
    glUniform1f(activeMainLoc.historyValid, historyUsable ? 1.0f : 0.0f);
    glUniform1f(activeMainLoc.historyPreGrade, useExternalSourceHistory ? 1.0f : 0.0f);
    // NDR true-PQ (Fork A): convert the final HLG-encoded pixels to genuine ST.2084 PQ only when the
    // delivery surface is BT.2020_PQ. deliveryModeLabel is the canonical "HLG" / "PQ" / "SDR" token.
    glUniform1f(activeMainLoc.hdrToPqConvert, applyNdrPqDeliveryToneMap);
    glUniform1f(activeMainLoc.ndrDeliveryMode, ndrDeliveryModeValue);
    const GLuint ndrPqLut =
        ndrDeliveryModeValue > 0.5f ? state->ndrPqLutNivukx : state->ndrPqLutNdr10;
    const bool ndrPqLutReady = state->deliveryModeLabel == "PQ" && ndrPqLut != 0;
    glActiveTexture(GL_TEXTURE3);
    glBindTexture(
        GL_TEXTURE_3D,
        ndrPqLutReady ? ndrPqLut : state->defaultLut3d
    );
    glUniform1i(activeMainLoc.ndrPqLut, 3);
    glUniform1f(activeMainLoc.ndrPqLutReady, ndrPqLutReady ? 1.0f : 0.0f);
    glUniform1f(activeMainLoc.inputPq, inputIsPq ? 1.0f : 0.0f);
    glUniform1f(activeMainLoc.ndrWarmSkinRedTame, ndrWarmSkinRedTameValue);
    glActiveTexture(GL_TEXTURE0);

    glBindBuffer(GL_ARRAY_BUFFER, state->vertexBuffer);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), reinterpret_cast<void*>(0));
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), reinterpret_cast<void*>(2 * sizeof(GLfloat)));
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glDisableVertexAttribArray(0);
    glDisableVertexAttribArray(1);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0);
    }  // end external-path else (decode-once handled above)

    // Preferred external temporal path: the recorded frame has already been drawn directly to the
    // encoder surface. Update a 10-bit source-history texture for the next frame with a one-sample
    // external decode pass, avoiding the full processed-frame FBO->encoder blit that was the 4K25 wall.
    if (useExternalSourceHistory) {
        const auto historyPresentStart = std::chrono::steady_clock::now();
        glBindFramebuffer(GL_FRAMEBUFFER, state->historyFbo);
        glFramebufferTexture2D(
            GL_FRAMEBUFFER,
            GL_COLOR_ATTACHMENT0,
            GL_TEXTURE_2D,
            state->historyWriteTexture,
            0
        );
        glViewport(0, 0, targetWidth, targetHeight);
        glUseProgram(activeDecodeProgram);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, state->sourceTexture);
        glUniform1i(activeDecodeTextureLocation, 0);
        if (useExplicitYuv) {
            glUniform1i(state->decodeExplicitYuvPrimariesLoc, inputPrimaries);
            glUniform1i(state->decodeExplicitYuvRangeLoc, inputRange);
        }
        glBindBuffer(GL_ARRAY_BUFFER, state->vertexBuffer);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), reinterpret_cast<void*>(0));
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), reinterpret_cast<void*>(2 * sizeof(GLfloat)));
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glDisableVertexAttribArray(0);
        glDisableVertexAttribArray(1);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);

        std::swap(state->historyTexture, state->historyWriteTexture);
        state->historyValid = true;
        ++state->temporalHistoryFrames;
        ++state->sourceHistoryFrames;
        const uint64_t historyPresentUs = static_cast<uint64_t>(
            std::chrono::duration_cast<std::chrono::microseconds>(
                std::chrono::steady_clock::now() - historyPresentStart
            ).count()
        );
        state->historyPresentTotalUs += historyPresentUs;
        state->historyPresentMaxUs = std::max(state->historyPresentMaxUs, historyPresentUs);
        if (historyPresentUs > 12000u) {
            ++state->slowHistoryPresentFrames;
        }
    } else if (renderToTemporalHistory) {
        // Fallback processed-history path: maintain history by rendering into RGB10_A2, presenting it
        // to the encoder, then swapping the written texture into the next-frame history slot.
        const auto historyPresentStart = std::chrono::steady_clock::now();
        glBindFramebuffer(GL_READ_FRAMEBUFFER, state->historyFbo);
        glReadBuffer(GL_COLOR_ATTACHMENT0);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
        glBlitFramebuffer(
            0,
            0,
            targetWidth,
            targetHeight,
            0,
            0,
            targetWidth,
            targetHeight,
            GL_COLOR_BUFFER_BIT,
            GL_NEAREST
        );
        // glGetError() after a full-resolution FBO->encoder-surface blit can serialize tile GPUs.
        // The blit is deterministic after the RGB10_A2 target has been allocated/proven, so validate
        // the first few frames and then poll periodically instead of forcing a per-frame driver sync.
        const bool checkBlitError =
            state->temporalHistoryFrames < 3u ||
            ((state->temporalHistoryFrames + 1u) % 60u) == 0u;
        const GLenum blitError = checkBlitError ? glGetError() : GL_NO_ERROR;
        if (checkBlitError) {
            ++state->historyPresentErrorChecks;
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        if (blitError != GL_NO_ERROR) {
            if (!state->eglImageCacheEnabled) {
                state->eglDestroyImageKHR(state->display, eglImage);
            }
            AHardwareBuffer_release(hardwareBuffer);
            ++state->failedFrames;
            std::ostringstream message;
            message << "RGB10_A2 temporal history present failed error=0x"
                    << std::hex << blitError;
            setStatus(state, message.str());
            return JNI_FALSE;
        }
        std::swap(state->historyTexture, state->historyWriteTexture);
        state->historyValid = true;
        ++state->temporalHistoryFrames;
        const uint64_t historyPresentUs = static_cast<uint64_t>(
            std::chrono::duration_cast<std::chrono::microseconds>(
                std::chrono::steady_clock::now() - historyPresentStart
            ).count()
        );
        state->historyPresentTotalUs += historyPresentUs;
        state->historyPresentMaxUs = std::max(state->historyPresentMaxUs, historyPresentUs);
        if (historyPresentUs > 12000u) {
            ++state->slowHistoryPresentFrames;
        }
    }

    if (presentationTimeNs > 0L) {
        state->eglPresentationTimeANDROID(
            state->display,
            state->encoderSurface,
            static_cast<EGLnsecsANDROID>(presentationTimeNs)
        );
        state->lastPresentationTimeNs = static_cast<int64_t>(presentationTimeNs);
    }

    const auto swapStart = std::chrono::steady_clock::now();
    const EGLBoolean swapped = eglSwapBuffers(state->display, state->encoderSurface);
    const auto swapDurationUs = static_cast<uint64_t>(
        std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::steady_clock::now() - swapStart
        ).count()
    );
    // #5: when caching is ON the cache owns the image (destroyed on eviction / surface teardown); only
    // destroy per-frame in the shipped non-cached path.
    if (!state->eglImageCacheEnabled) {
        state->eglDestroyImageKHR(state->display, eglImage);
    }
    AHardwareBuffer_release(hardwareBuffer);
    const auto submitDurationUs = static_cast<uint64_t>(
        std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::steady_clock::now() - submitStart
        ).count()
    );

    if (swapped != EGL_TRUE) {
        ++state->failedFrames;
        setStatus(state, "eglSwapBuffers failed");
        return JNI_FALSE;
    }
    ++state->submittedFrames;
    if (useDecodeOnce) {
        ++state->decodeOnceFrames;
    } else {
        ++state->externalPathFrames;
    }
    if (useExplicitYuv) {
        ++state->explicitYuvDecodeFrames;
    }
    state->swapDurationTotalUs += swapDurationUs;
    state->swapDurationMaxUs = std::max(state->swapDurationMaxUs, swapDurationUs);
    state->submitDurationTotalUs += submitDurationUs;
    state->submitDurationMaxUs = std::max(state->submitDurationMaxUs, submitDurationUs);
    if (swapDurationUs > 45000u) {
        ++state->slowSwapFrames;
    }
    if (submitDurationUs > 40000u) {
        ++state->slowSubmitFrames;
    }
    if ((state->submittedFrames % 60u) == 0u) {
        const uint64_t avgSwapUs =
            state->submittedFrames > 0u ? state->swapDurationTotalUs / state->submittedFrames : 0u;
        const uint64_t avgSubmitUs =
            state->submittedFrames > 0u ? state->submitDurationTotalUs / state->submittedFrames : 0u;
        const uint64_t avgHistoryPresentUs =
            state->temporalHistoryFrames > 0u
                ? state->historyPresentTotalUs / state->temporalHistoryFrames
                : 0u;
        LOGI(
            "Submitted offscreen EGL encoder frames=%llu decodeOnceFrames=%llu externalPathFrames=%llu explicitYuvFrames=%llu sourceHistoryFrames=%llu avgSwapUs=%llu maxSwapUs=%llu slowSwaps=%llu avgSubmitUs=%llu maxSubmitUs=%llu slowSubmits=%llu temporalHistoryFrames=%llu avgHistoryPresentUs=%llu maxHistoryPresentUs=%llu slowHistoryPresents=%llu historyPresentErrorChecks=%llu ndrPqLut=%s eglImageCache=%s cacheKey=%s cacheSize=%zu cacheHits=%llu cacheMisses=%llu cacheEvictions=%llu cacheIdFailures=%llu codecBackpressurePacing=%s",
            static_cast<unsigned long long>(state->submittedFrames),
            static_cast<unsigned long long>(state->decodeOnceFrames),
            static_cast<unsigned long long>(state->externalPathFrames),
            static_cast<unsigned long long>(state->explicitYuvDecodeFrames),
            static_cast<unsigned long long>(state->sourceHistoryFrames),
            static_cast<unsigned long long>(avgSwapUs),
            static_cast<unsigned long long>(state->swapDurationMaxUs),
            static_cast<unsigned long long>(state->slowSwapFrames),
            static_cast<unsigned long long>(avgSubmitUs),
            static_cast<unsigned long long>(state->submitDurationMaxUs),
            static_cast<unsigned long long>(state->slowSubmitFrames),
            static_cast<unsigned long long>(state->temporalHistoryFrames),
            static_cast<unsigned long long>(avgHistoryPresentUs),
            static_cast<unsigned long long>(state->historyPresentMaxUs),
            static_cast<unsigned long long>(state->slowHistoryPresentFrames),
            static_cast<unsigned long long>(state->historyPresentErrorChecks),
            state->ndrPqLutsReady ? "ready" : "fallback",
            state->eglImageCacheEnabled ? "true" : "false",
            state->eglImageCacheStableIds ? "AHARDWAREBUFFER_ID" : "POINTER_FALLBACK",
            state->eglImageCache.size(),
            static_cast<unsigned long long>(state->eglImageCacheHits),
            static_cast<unsigned long long>(state->eglImageCacheMisses),
            static_cast<unsigned long long>(state->eglImageCacheEvictions),
            static_cast<unsigned long long>(state->eglImageCacheIdFailures),
            state->codecBackpressurePacing ? "true" : "false"
        );
    }
    setStatus(state, "offscreen EGL frame submitted");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_neoproc_neoproccamx_rendering_OffscreenAhbEglEncoderBridge_nativeGetSummary(
    JNIEnv* env,
    jobject,
    jlong handle
) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return env->NewStringUTF("offscreenAhbEglEncoder=released");
    std::lock_guard<std::mutex> lock(state->mutex);
    std::ostringstream out;
    out << "offscreenAhbEglEncoder="
        << (state->encoderSurface != EGL_NO_SURFACE ? "ready" : "idle")
        << " offscreenEglRedBits=" << state->redBits
        << " offscreenEglGreenBits=" << state->greenBits
        << " offscreenEglBlueBits=" << state->blueBits
        << " offscreenEglAlphaBits=" << state->alphaBits
        << " offscreenEglRecordable=" << (state->recordable == EGL_TRUE ? "true" : "false")
        << " offscreenEglColorSpace=" << eglColorSpaceLabel(state->surfaceColorSpace)
        << " offscreenEglDataSpace=" << dataSpaceLabel(state->bufferDataSpace)
        << " offscreenEglDataSpaceApplied="
        << (state->bufferDataSpaceApplied ? "true" : "false")
        << " offscreenEglDeliveryCompatible="
        << (state->deliveryCompatible ? "true" : "false")
        << " encoderSourceFormat=" << state->sourceFormat
        << " encoderSourceBitDepth=" << state->sourceBitDepth
        << " encoderSourcePrecision=" << state->sourcePrecision
        << " encoderSourcePrecisionProven="
        << (state->sourcePrecisionProven ? "true" : "false");
    if (state->encoderSurface != EGL_NO_SURFACE && state->deliveryCompatible) {
        out << " encoderSurfaceBound=true"
            << " encoderSurfaceFormat=EGL_RGB10_A2"
            << " encoderSurfaceBitDepth=" << state->redBits
            << " encoderSurfacePrecision=PACKED_10_BIT"
            << " encoderSurfacePrecisionFallback=false"
            << " encoderSurfaceColorSpace=" << state->deliveryColorSpaceLabel
            << " encoderSurfaceDeliveryMode=" << state->deliveryModeLabel
            << " encoderSurfaceDeliveryCompatible=true";
    }
    out
        << " submittedFrames=" << state->submittedFrames
        << " decodeOnceEnabled=" << (state->decodeOnceEnabled ? "true" : "false")
        << " decodeOnceFrames=" << state->decodeOnceFrames
        << " externalPathFrames=" << state->externalPathFrames
        << " explicitYuvDecodeAvailable=" << (state->explicitYuvDecodeAvailable ? "true" : "false")
        << " explicitYuvDecodeFrames=" << state->explicitYuvDecodeFrames
        << " sourceHistoryFrames=" << state->sourceHistoryFrames
        << " failedFrames=" << state->failedFrames
        << " codecBackpressurePacing="
        << (state->codecBackpressurePacing ? "true" : "false")
        << " avgSwapUs="
        << (state->submittedFrames > 0u ? state->swapDurationTotalUs / state->submittedFrames : 0u)
        << " maxSwapUs=" << state->swapDurationMaxUs
        << " slowSwapFrames=" << state->slowSwapFrames
        << " avgSubmitUs="
        << (state->submittedFrames > 0u ? state->submitDurationTotalUs / state->submittedFrames : 0u)
        << " maxSubmitUs=" << state->submitDurationMaxUs
        << " slowSubmitFrames=" << state->slowSubmitFrames
        << " temporalHistoryFrames=" << state->temporalHistoryFrames
        << " avgHistoryPresentUs="
        << (state->temporalHistoryFrames > 0u
            ? state->historyPresentTotalUs / state->temporalHistoryFrames
            : 0u)
        << " maxHistoryPresentUs=" << state->historyPresentMaxUs
        << " slowHistoryPresentFrames=" << state->slowHistoryPresentFrames
        << " historyPresentErrorChecks=" << state->historyPresentErrorChecks
        << " ndrPqLutReady=" << (state->ndrPqLutsReady ? "true" : "false")
        << " ndrPqLutSize=" << kNdrPqLutSize
        << " inputTransfer=" << state->lastInputTransfer
        << " inputPrimaries=" << state->lastInputPrimaries
        << " inputRange=" << state->lastInputRange
        << " eglImageCacheEnabled=" << (state->eglImageCacheEnabled ? "true" : "false")
        << " eglImageCacheKey="
        << (state->eglImageCacheStableIds ? "AHARDWAREBUFFER_ID" : "POINTER_FALLBACK")
        << " eglImageCacheSize=" << state->eglImageCache.size()
        << " eglImageCacheHits=" << state->eglImageCacheHits
        << " eglImageCacheMisses=" << state->eglImageCacheMisses
        << " eglImageCacheEvictions=" << state->eglImageCacheEvictions
        << " eglImageCacheIdFailures=" << state->eglImageCacheIdFailures
        << " presentationTimeNs=" << state->lastPresentationTimeNs
        << " path=AHardwareBuffer_fromHardwareBuffer>EGLImageKHR>samplerExternalOES>"
        << (state->decodeOnceFrames > 0u
            ? "rgb10a2_decode_history"
            : (state->sourceHistoryFrames > 0u ? "single_pass_external_source_history" : "single_pass_external"))
        << "_or_RGB10_A2_history_pingpong>eglPresentationTimeANDROID>eglSwapBuffers"
        << " status=" << state->status;
    const std::string summary = out.str();
    return env->NewStringUTF(summary.c_str());
}
