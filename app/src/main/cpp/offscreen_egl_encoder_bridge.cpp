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
#include <array>
#include <chrono>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <dlfcn.h>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <unordered_map>
#include <vector>

#include "egl_image_residency_policy.h"

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

#ifndef EGL_CONTEXT_PRIORITY_LEVEL_IMG
#define EGL_CONTEXT_PRIORITY_LEVEL_IMG 0x3100
#endif

#ifndef EGL_CONTEXT_PRIORITY_HIGH_IMG
#define EGL_CONTEXT_PRIORITY_HIGH_IMG 0x3101
#endif

#ifndef EGL_CONTEXT_PRIORITY_MEDIUM_IMG
#define EGL_CONTEXT_PRIORITY_MEDIUM_IMG 0x3102
#endif

#ifndef EGL_CONTEXT_PRIORITY_LOW_IMG
#define EGL_CONTEXT_PRIORITY_LOW_IMG 0x3103
#endif

namespace {

constexpr const char* kTag = "OffscreenAhbEgl";

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, kTag, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, kTag, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, kTag, __VA_ARGS__)

using AHardwareBufferGetIdFn = int (*)(const AHardwareBuffer*, uint64_t*);

// RecordGradeBaker's JNI contract is row-major, while OpenGL ES consumes matrix-uniform data in
// column-major order and requires transpose=GL_FALSE. Keep the public/state ABI unchanged and pack
// each upload explicitly; GL_TRUE is invalid for glUniformMatrix*fv in OpenGL ES.
inline void packGradeMatrixColumnMajor(
    const float* rowMajor,
    float* columnMajor
) noexcept {
    for (int column = 0; column < 3; ++column) {
        for (int row = 0; row < 3; ++row) {
            columnMajor[column * 3 + row] = rowMajor[row * 3 + column];
        }
    }
}

struct EglImageCacheEntry {
    EGLImageKHR image = EGL_NO_IMAGE_KHR;
};

constexpr jint kInputReadFenceUnknown = 0;
constexpr jint kInputReadFencePending = 1;
constexpr jint kInputReadFenceSignaled = 2;
constexpr jint kInputReadFenceFailed = 3;
constexpr size_t kInputReadFenceReceiptCapacity = 2;
constexpr jint kPregradedBt2020PqSemantic = 1;
constexpr jint kPregradedBt2020PqAbiVersion = 1;
constexpr jint kPregradedBt2020PqFullRange = 1;
// A zero-time poll is normally intentional. Once both exact RAW transport receipts are occupied,
// however, no third frame can legally enter the encoder and repeated scheduler retries only consume
// CPU/JNI time. Give the FIFO-head fence one tightly bounded chance to signal. This never changes
// ownership, ordering, queue depth, or pixels; it only replaces churn while progress is impossible.
constexpr uint64_t kSaturatedInputReadFencePollWaitNs = 1'000'000u;

struct InputReadFenceReceipt {
    uint64_t id = 0u;
    EGLSyncKHR sync = EGL_NO_SYNC_KHR;
    bool signaled = false;
    uint64_t bufferId = 0u;
    int64_t presentationTimeNs = 0;
};

// #2 decode-once: uniform locations for the sampler2D main-shader variant (parallel to the external
// program's individual location fields). Kept as a struct because there are two programs to locate.
struct MainProgramLoc {
    GLint texture = -1;
    GLint texel = -1;
    GLint sharpness = -1;
    GLint denoise = -1;
    GLint chromaDenoise = -1;
    // Neo ConstantLift uniforms exist only in the GLES 3.1 GatherGrid programs. They carry
    // frame-invariant values that the canonical shader otherwise recalculates for every pixel.
    GLint neoDenoiseMix = -1;
    GLint neoInvRangeVariance = -1;
    GLint neoSharpenAmount = -1;
    GLint neoSharpenCap = -1;
    GLint neoChromaScale = -1;
    GLint niv99RgbCorrection = -1;
    GLint niv99ActiveConfidence = -1;
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
    GLint ndrTargetPeakNits = -1;
    GLint ndrPqLut = -1;
    GLint ndrPqLutReady = -1;
    GLint ndrSensorTonePqLut = -1;
    GLint ndrSensorTonePqLutReady = -1;
    GLint inputTransfer = -1;       // 0=SDR, 1=PQ, 2=HLG, 3=Neo sensor-linear ACEScg
    GLint inputPrimaries = -1;      // 1=BT.709, 2=BT.2020, 3=ACEScg/AP1
    GLint inputRange = -1;          // 1 => full, 2 => limited, 3 => extended
    GLint ndrWarmSkinRedTame = -1;  // Proprietary NDR scene-aware warm/red neutral correction.
    GLint ndrMeasuredRgbScale = -1;
    GLint ndrMeasuredTone = -1;
    GLint ndrMeasuredHue = -1;
    GLint ndrToneProfile = -1;
    GLint ndrCharacterProfile = -1;
    GLint ndrDetailProfile = -1;
    GLint encOutFlip = -1;          // MIRRORFIX v5 — per-axis output flip (see kVertexShader)
};

enum class GpuStage : size_t {
    Decode = 0,
    Grade = 1,
    History = 2
};

constexpr size_t kGpuStageCount = 3;

struct DurationStats {
    uint64_t totalUs = 0;
    uint64_t maxUs = 0;
    uint64_t samples = 0;

    void record(uint64_t durationUs) noexcept {
        totalUs += durationUs;
        maxUs = std::max(maxUs, durationUs);
        ++samples;
    }

    void reset() noexcept {
        totalUs = 0;
        maxUs = 0;
        samples = 0;
    }

    [[nodiscard]] uint64_t averageUs() const noexcept {
        return samples > 0u ? totalUs / samples : 0u;
    }
};

struct GpuStageSample {
    std::array<uint64_t, kGpuStageCount> elapsedUs{};
    std::array<bool, kGpuStageCount> valid{};
    bool disjoint = false;
};

struct OffscreenAhbEglEncoderState {
    std::mutex mutex;
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLConfig config = nullptr;
    EGLContext context = EGL_NO_CONTEXT;
    // Scheduling-only request for the record-grade context. The extension is optional and context
    // creation always retries with the ordinary ES3 attribute list if a vendor rejects the hint.
    // No render target, shader, precision, colour, or frame content is changed by this request.
    bool contextPriorityExtensionAvailable = false;
    bool contextPriorityHighRequested = false;
    bool contextPriorityFallbackUsed = false;
    bool contextPriorityQuerySucceeded = false;
    EGLint contextPriorityGranted = EGL_NONE;
    EGLint contextPriorityRequestError = EGL_SUCCESS;
    EGLSurface encoderSurface = EGL_NO_SURFACE;
    ANativeWindow* encoderWindow = nullptr;
    // Final close can be retried after a driver rejects EGL surface/context retirement. Once the GL
    // objects are deleted they stay deleted, allowing the retry to operate only on the retained EGL
    // ownership objects without requiring a now-surface-less context to become current again.
    bool glResourcesDestroyed = false;
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
    // Dedicated one-draw final-PQ transport. It is deliberately not derived from the generic grade
    // program: no grade, transfer, LUT, denoise, sharpen, temporal, dither, or orientation operation
    // can accidentally become reachable from this program.
    GLuint pregradedPqIdentityProgram = 0;
    GLuint pregradedPqSourceTexture = 0;
    GLint pregradedPqTextureLocation = -1;
    GLuint vertexBuffer = 0;
    GLuint sourceTexture = 0;
    GLint textureLocation = -1;
    GLint texelLocation = -1;
    GLint sharpnessLocation = -1;
    GLint denoiseLocation = -1;
    GLint chromaDenoiseLocation = -1;
    GLint niv99RgbCorrectionLocation = -1;
    GLint niv99ActiveConfidenceLocation = -1;
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
    GLint ndrTargetPeakNitsLocation = -1;
    GLint ndrPqLutLocation = -1;
    GLint ndrPqLutReadyLocation = -1;
    // NDR10+/NIVUKX input-transfer decode is selected per submitted Image dataspace.
    GLint inputTransferLocation = -1;
    GLint ndrWarmSkinRedTameLocation = -1;
    int lastInputTransfer = 0;   // 0=SDR, 1=PQ, 2=HLG, 3=Neo sensor-linear
    int lastInputPrimaries = 0;  // 0=unknown, 1=BT.709, 2=BT.2020, 3=ACEScg/AP1
    int lastInputRange = 0;      // 0=unknown, 1=full, 2=limited, 3=extended
    // Confidence-gated calibration packet, updated only at a mode/calibration boundary.
    float ndrMeasuredRgbScale[4] = {1.0f, 1.0f, 1.0f, 0.0f};
    // highlight rolloff, shadow lift, saturation, active
    float ndrMeasuredTone[4] = {0.0f, 0.0f, 1.0f, 0.0f};
    // skin hue correction degrees, sky hue correction degrees
    float ndrMeasuredHue[2] = {0.0f, 0.0f};
    // Canonical NdrToneOperator packet, pushed with the measured calibration at a mode boundary.
    float ndrToneProfile[4] = {0.180f, 0.859937f, 0.238882f, 0.024780f};
    float ndrCharacterProfile[4] = {0.149718f, 0.167837f, 0.100943f, 0.038361f};
    float ndrDetailProfile[4] = {0.062392f, 0.400000f, -0.060000f, 0.085000f};
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
    // Sensor-linear NDR keeps the profile's color gates analytic, but moves the scalar tone,
    // measured-rolloff and ST.2084 transcendental work into one small cache-resident FP16 table.
    // The table is rebuilt atomically on the encoder GL thread when its exact scalar profile changes.
    GLuint ndrSensorTonePqLut = 0;
    bool ndrSensorTonePqLutReady = false;
    bool ndrSensorTonePqLutKeyValid = false;
    std::array<float, 8> ndrSensorTonePqLutKey{};
    uint64_t ndrSensorTonePqLutBuilds = 0;
    uint64_t ndrSensorTonePqLutFrames = 0;
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
    bool historyFp16 = false;
    bool historyValid = false;
    PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC eglGetNativeClientBufferANDROID = nullptr;
    PFNEGLCREATEIMAGEKHRPROC eglCreateImageKHR = nullptr;
    PFNEGLDESTROYIMAGEKHRPROC eglDestroyImageKHR = nullptr;
    PFNEGLPRESENTATIONTIMEANDROIDPROC eglPresentationTimeANDROID = nullptr;
    PFNEGLCREATESYNCKHRPROC eglCreateSyncKHR = nullptr;
    PFNEGLDESTROYSYNCKHRPROC eglDestroySyncKHR = nullptr;
    PFNEGLCLIENTWAITSYNCKHRPROC eglClientWaitSyncKHR = nullptr;
    PFNGLEGLIMAGETARGETTEXTURE2DOESPROC glEGLImageTargetTexture2DOES = nullptr;
    bool inputReadFenceAvailable = false;
    std::array<InputReadFenceReceipt, kInputReadFenceReceiptCapacity> inputReadFenceReceipts{};
    uint64_t inputReadFenceCreated = 0u;
    uint64_t inputReadFenceSignaled = 0u;
    uint64_t inputReadFencePendingPolls = 0u;
    uint64_t inputReadFenceSaturatedWaits = 0u;
    uint64_t inputReadFenceSaturatedWaitTimeouts = 0u;
    uint64_t inputReadFenceSyncFallbacks = 0u;
    uint64_t inputReadFenceFailures = 0u;
    uint64_t inputReadFenceMaxOutstanding = 0u;
    uint64_t inputReadDecodeBoundaryFrames = 0u;
    uint64_t inputReadFinalBoundaryFrames = 0u;
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
    // Camera2 SDR has exact transfer/delivery invariants and most frames carry no approved NIV99
    // pixel packet. Specializing those proven values lets the driver remove unreachable RAW/NDR/PQ
    // and identity-NIV99 work without changing any active SDR quality equation. Any mismatch falls
    // back to the canonical programs below.
    GLuint sdrInvariantProgramExternal = 0;
    GLuint sdrInvariantProgram2d = 0;
    MainProgramLoc sdrInvariantLocExternal;
    MainProgramLoc sdrInvariantLoc2d;
    // NIV99-active SDR keeps the exact per-frame WB correction uniforms dynamic while compiling
    // the same proven SDR/PQ/history invariants as the identity-NIV99 fast path. This prevents an
    // approved packet from falling back to the substantially larger generic 4K shader.
    GLuint sdrNiv99ProgramExternal = 0;
    GLuint sdrNiv99Program2d = 0;
    MainProgramLoc sdrNiv99LocExternal;
    MainProgramLoc sdrNiv99Loc2d;
    // Full-colour SDR recording normally carries a real 3D LUT. Keep that LUT and its exact
    // trilinear sampling dynamic while specializing the same proven SDR/PQ/history invariants as
    // the zero-LUT programs above. Separate NIV99 variants preserve the approved per-frame WB
    // correction without sending either case through the much larger generic RAW/NDR shader.
    GLuint sdrCreativeLutProgramExternal = 0;
    GLuint sdrCreativeLutProgram2d = 0;
    MainProgramLoc sdrCreativeLutLocExternal;
    MainProgramLoc sdrCreativeLutLoc2d;
    GLuint sdrCreativeLutNiv99ProgramExternal = 0;
    GLuint sdrCreativeLutNiv99Program2d = 0;
    MainProgramLoc sdrCreativeLutNiv99LocExternal;
    MainProgramLoc sdrCreativeLutNiv99Loc2d;
    // Neo GatherGrid: GLES 3.1+ quality-identical sampler2D variants for the full-resolution SDR
    // creative-LUT path. Three component gathers reconstruct the same four cardinal RGB texels that
    // the canonical shader reads with four textureOffset calls. The GLES 3.0 programs above remain
    // compiled and are the mandatory fallback on unsupported or compiler-rejecting devices.
    bool neoGatherGridCapable = false;
    // Frozen when the encoder surface is configured. This permits same-binary device qualification
    // against the canonical textureOffset shader without changing any pixel/quality equation.
    bool neoGatherGridEnabled = false;
    GLuint sdrCreativeLutGatherProgram2d = 0;
    MainProgramLoc sdrCreativeLutGatherLoc2d;
    GLuint sdrCreativeLutNiv99GatherProgram2d = 0;
    MainProgramLoc sdrCreativeLutNiv99GatherLoc2d;
    uint64_t neoGatherGridFrames = 0;
    uint64_t neoGatherGridFallbackFrames = 0;
    uint64_t neoConstantLiftFrames = 0;
    uint64_t niv99IdentityWhiteBalanceElisionFrames = 0;
    // Strict RAW-derived RGBA16F/ACEScg PQ programs. All four are generated from the canonical main
    // fragment source by replacing exact uniform declarations with proven compile-time invariants:
    // transfer=3, PQ conversion=1, delivery=0/1, master peak=5000/10000 nits, sensor tone/PQ LUT
    // ready, and creative LUT strength=0. The sampler2D variants additionally specialize decoded
    // source history as pre-grade. Runtime selection proves every invariant before using a variant.
    // The historical field names hold NDR10 so existing telemetry remains stable; NIVUKX has its
    // own pair. Any missing program or non-exact ingress/delivery contract uses the generic shader.
    GLuint sensorLinearProgramExternal = 0;
    GLuint sensorLinearProgram2d = 0;
    GLuint sensorLinearNivukxProgramExternal = 0;
    GLuint sensorLinearNivukxProgram2d = 0;
    GLuint decodeProgram = 0;       // passthrough: samplerExternalOES -> RGB10_A2
    GLint decodeTexLoc = -1;        // uTexture location in decodeProgram
    GLint decodeSourceTexelLoc = -1;
    GLint decodeMinificationScaleLoc = -1;
    GLint decodePreserveExtendedRangeLoc = -1;
    GLuint mainProgramExplicitYuv = 0; // GL_EXT_YUV_target + project-owned matrix/range decode
    GLuint decodeProgramExplicitYuv = 0;
    GLint decodeExplicitYuvTexLoc = -1;
    GLint decodeExplicitYuvSourceTexelLoc = -1;
    GLint decodeExplicitYuvMinificationScaleLoc = -1;
    GLint decodeExplicitYuvPreserveExtendedRangeLoc = -1;
    GLint decodeExplicitYuvPrimariesLoc = -1;
    GLint decodeExplicitYuvRangeLoc = -1;
    MainProgramLoc mainLocExternal;
    MainProgramLoc mainLoc2d;       // uniform locations for mainProgram2d
    MainProgramLoc sensorLinearLocExternal;
    MainProgramLoc sensorLinearLoc2d;
    MainProgramLoc sensorLinearNivukxLocExternal;
    MainProgramLoc sensorLinearNivukxLoc2d;
    MainProgramLoc mainLocExplicitYuv;
    bool explicitYuvDecodeAvailable = false;
    uint64_t explicitYuvDecodeFrames = 0;
    GLuint decodeFbo = 0;
    GLuint decodeTexture = 0;       // RGB10_A2/FP16 intermediate currently owned by PASS 1
    GLuint decodeHistoryTexture = 0; // immediately-previous decoded intermediate for temporal blend
    // A third equal-format target breaks the steady-state write-after-read hazard of the original
    // two-texture ping-pong. PASS 1 can write this spare while the GPU is still reading current +
    // history for the preceding PASS 2. Rotation still exposes exactly frame N-1 as temporal history;
    // only resource ownership changes, never pixels, precision, cadence, or enhancement strength.
    GLuint decodeSpareTexture = 0;
    bool decodeTripleBufferReady = false;
    int decodeTexWidth = 0;
    int decodeTexHeight = 0;
    bool decodeFp16 = false;
    bool decodeHistoryValid = false;
    bool codecBackpressurePacing = true;
    // Sampled stage timing for project-owned RAW/Neo-scene frames. The queries never overlap and
    // are collected only after the existing input-ownership glFinish, so telemetry adds no GPU wait.
    PFNGLGENQUERIESEXTPROC glGenQueriesEXT = nullptr;
    PFNGLDELETEQUERIESEXTPROC glDeleteQueriesEXT = nullptr;
    PFNGLBEGINQUERYEXTPROC glBeginQueryEXT = nullptr;
    PFNGLENDQUERYEXTPROC glEndQueryEXT = nullptr;
    PFNGLGETQUERYIVEXTPROC glGetQueryivEXT = nullptr;
    PFNGLGETQUERYOBJECTUIVEXTPROC glGetQueryObjectuivEXT = nullptr;
    PFNGLGETQUERYOBJECTUI64VEXTPROC glGetQueryObjectui64vEXT = nullptr;
    std::array<GLuint, kGpuStageCount> gpuStageQueries{};
    std::array<bool, kGpuStageCount> gpuStageQueryPending{};
    std::array<bool, kGpuStageCount> gpuStageQueryDiscard{};
    std::array<DurationStats, kGpuStageCount> gpuStageStats{};
    bool gpuStageTimingAvailable = false;
    // Diagnostic-only sampling is opt-in. Production rendering never begins timer queries, so a
    // vendor timer-query defect cannot perturb frame submission or the first recording frames.
    bool gpuStageTimingSamplingEnabled = false;
    bool gpuStageTimingFaulted = false;
    GLint gpuStageCounterBits = 0;
    uint64_t gpuStageQueryErrors = 0;
    uint64_t gpuStageDisjointBatches = 0;
    uint64_t gpuStageUnavailablePolls = 0;
    DurationStats decodeHostStats;
    DurationStats gradeHostStats;
    DurationStats historyHostStats;
    DurationStats finishStats;
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
    uint64_t decodeTripleBufferedFrames = 0;
    uint64_t decodeOnceFrames = 0;
    uint64_t projectPreviewDownsampleFrames = 0;
    uint64_t externalPathFrames = 0;
    uint64_t sdrInvariantSpecializedFrames = 0;
    uint64_t sdrInvariantSpecializationFallbackFrames = 0;
    uint64_t sdrNiv99SpecializedFrames = 0;
    uint64_t sdrNiv99SpecializationFallbackFrames = 0;
    uint64_t sdrCreativeLutSpecializedFrames = 0;
    uint64_t sdrCreativeLutSpecializationFallbackFrames = 0;
    uint64_t sdrCreativeLutNiv99SpecializedFrames = 0;
    uint64_t sdrCreativeLutNiv99SpecializationFallbackFrames = 0;
    uint64_t sourceHistoryFrames = 0;
    uint64_t sensorLinearSpecializedFrames = 0;
    uint64_t sensorLinearNdr10SpecializedFrames = 0;
    uint64_t sensorLinearNivukxSpecializedFrames = 0;
    uint64_t sensorLinearSpecializationFallbackFrames = 0;
    uint64_t eglImageCacheHits = 0;
    uint64_t eglImageCacheMisses = 0;
    uint64_t eglImageCacheEvictions = 0;
    uint64_t eglImageCacheIdFailures = 0;
    uint64_t recorderPrewarmAttempts = 0;
    uint64_t recorderPrewarmSuccesses = 0;
    uint64_t recorderPrewarmFailures = 0;
    uint64_t recorderPrewarmTotalUs = 0;
    uint64_t recorderPrewarmMaxUs = 0;
    std::array<uint64_t, 2> recorderPrewarmBufferIds{};
    uint64_t cameraP010SdrPrewarmAttempts = 0;
    uint64_t cameraP010SdrPrewarmSuccesses = 0;
    uint64_t cameraP010SdrPrewarmFailures = 0;
    uint64_t cameraP010SdrPrewarmTotalUs = 0;
    uint64_t cameraP010SdrPrewarmMaxUs = 0;
    uint64_t cameraP010SdrPrewarmLastBufferId = 0;
    uint64_t cameraP010SdrPrewarmIdentityShaderDraws = 0;
    uint64_t cameraP010SdrPrewarmNiv99ShaderDraws = 0;
    std::vector<uint64_t> cameraP010SdrPrewarmBufferIds;
    uint64_t codecReadinessAttempts = 0;
    uint64_t codecReadinessSuccesses = 0;
    uint64_t codecReadinessFailures = 0;
    uint64_t codecReadinessTotalUs = 0;
    uint64_t codecReadinessMaxUs = 0;
    int64_t codecReadinessPresentationTimeNs = 0;
    uint64_t presentationTimelineRegressions = 0;
    uint64_t pregradedPqIdentityFrames = 0;
    uint64_t pregradedPqRejectedFrames = 0;
    uint64_t pregradedPqLastProfileGeneration = 0;
    uint64_t pregradedPqLastProfileRevision = 0;
    uint64_t pregradedPqLastGradeFingerprint = 0;
    std::array<uint64_t, kInputReadFenceReceiptCapacity> pregradedPqLastSlotTokens{};
    // The producer proof names one of two persistent Vulkan export slots. Bind each declared slot
    // to the stable GraphicBuffer identity observed on its first accepted submission so a stale or
    // misrouted proof can never authorize a different AHardwareBuffer.
    std::array<uint64_t, kInputReadFenceReceiptCapacity> pregradedPqSlotBufferIds{};
    uint64_t submittedFrames = 0;
    uint64_t failedFrames = 0;
    uint64_t niv99PacketsSeen = 0;
    uint64_t niv99PacketsAccepted = 0;
    uint64_t niv99PacketsRejected = 0;
    uint64_t niv99PixelsApplied = 0;
    uint64_t niv99CameraIdHash = 0;
    int64_t niv99SessionGeneration = -1;
    int64_t niv99FrameNumber = -1;
    int64_t niv99SensorTimestampNs = -1;
    bool niv99HasAcceptedPacket = false;
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

const char* contextPriorityLabel(EGLint priority) noexcept {
    switch (priority) {
        case EGL_CONTEXT_PRIORITY_HIGH_IMG:
            return "HIGH";
        case EGL_CONTEXT_PRIORITY_MEDIUM_IMG:
            return "MEDIUM";
        case EGL_CONTEXT_PRIORITY_LOW_IMG:
            return "LOW";
        default:
            return "UNKNOWN";
    }
}

size_t inputReadFenceOutstandingCount(const OffscreenAhbEglEncoderState* state) noexcept {
    if (state == nullptr) return 0u;
    return static_cast<size_t>(std::count_if(
        state->inputReadFenceReceipts.begin(),
        state->inputReadFenceReceipts.end(),
        [](const InputReadFenceReceipt& receipt) { return receipt.id != 0u; }
    ));
}

InputReadFenceReceipt* findInputReadFenceReceipt(
    OffscreenAhbEglEncoderState* state,
    uint64_t id
) noexcept {
    if (state == nullptr || id == 0u) return nullptr;
    for (InputReadFenceReceipt& receipt : state->inputReadFenceReceipts) {
        if (receipt.id == id) return &receipt;
    }
    return nullptr;
}

InputReadFenceReceipt* findFreeInputReadFenceReceipt(
    OffscreenAhbEglEncoderState* state
) noexcept {
    if (state == nullptr) return nullptr;
    for (InputReadFenceReceipt& receipt : state->inputReadFenceReceipts) {
        if (receipt.id == 0u) return &receipt;
    }
    return nullptr;
}

bool destroyInputReadFenceSync(
    OffscreenAhbEglEncoderState* state,
    InputReadFenceReceipt* receipt
) noexcept {
    if (state == nullptr || receipt == nullptr) return false;
    if (receipt->sync == EGL_NO_SYNC_KHR) return true;
    if (state->display == EGL_NO_DISPLAY || state->eglDestroySyncKHR == nullptr ||
        state->eglDestroySyncKHR(state->display, receipt->sync) != EGL_TRUE) {
        ++state->inputReadFenceFailures;
        const EGLint error = eglGetError();
        std::ostringstream message;
        message << "project-owned input-read fence destruction failed error=0x"
                << std::hex << error;
        setStatus(state, message.str());
        LOGE("%s receipt=%llu", message.str().c_str(),
             static_cast<unsigned long long>(receipt->id));
        return false;
    }
    receipt->sync = EGL_NO_SYNC_KHR;
    return true;
}

jint pollInputReadFenceReceipt(
    OffscreenAhbEglEncoderState* state,
    uint64_t id,
    uint64_t timeoutNs
) {
    InputReadFenceReceipt* receipt = findInputReadFenceReceipt(state, id);
    if (state == nullptr || receipt == nullptr) return kInputReadFenceUnknown;
    if (receipt->signaled) {
        return destroyInputReadFenceSync(state, receipt)
            ? kInputReadFenceSignaled
            : kInputReadFenceFailed;
    }
    if (!state->inputReadFenceAvailable || state->eglClientWaitSyncKHR == nullptr ||
        receipt->sync == EGL_NO_SYNC_KHR || state->display == EGL_NO_DISPLAY) {
        ++state->inputReadFenceFailures;
        return kInputReadFenceFailed;
    }
    const bool saturatedZeroTimeoutPoll =
        timeoutNs == 0u &&
        inputReadFenceOutstandingCount(state) == kInputReadFenceReceiptCapacity;
    const uint64_t effectiveTimeoutNs = saturatedZeroTimeoutPoll
        ? kSaturatedInputReadFencePollWaitNs
        : timeoutNs;
    if (saturatedZeroTimeoutPoll) {
        ++state->inputReadFenceSaturatedWaits;
    }
    const EGLint result = state->eglClientWaitSyncKHR(
        state->display,
        receipt->sync,
        0,
        static_cast<EGLTimeKHR>(effectiveTimeoutNs)
    );
    if (result == EGL_CONDITION_SATISFIED_KHR) {
        receipt->signaled = true;
        ++state->inputReadFenceSignaled;
        return destroyInputReadFenceSync(state, receipt)
            ? kInputReadFenceSignaled
            : kInputReadFenceFailed;
    }
    if (result == EGL_TIMEOUT_EXPIRED_KHR) {
        if (saturatedZeroTimeoutPoll) {
            ++state->inputReadFenceSaturatedWaitTimeouts;
        }
        ++state->inputReadFencePendingPolls;
        return kInputReadFencePending;
    }
    ++state->inputReadFenceFailures;
    return kInputReadFenceFailed;
}

void drainAndClearInputReadFenceReceipts(OffscreenAhbEglEncoderState* state) noexcept {
    if (state == nullptr) return;
    for (InputReadFenceReceipt& receipt : state->inputReadFenceReceipts) {
        if (receipt.id == 0u) continue;
        if (!receipt.signaled && receipt.sync != EGL_NO_SYNC_KHR &&
            state->display != EGL_NO_DISPLAY && state->eglClientWaitSyncKHR != nullptr) {
            const EGLint waitResult = state->eglClientWaitSyncKHR(
                state->display,
                receipt.sync,
                0,
                EGL_FOREVER_KHR
            );
            if (waitResult != EGL_CONDITION_SATISFIED_KHR &&
                eglGetCurrentContext() == state->context) {
                // Teardown-only fail-safe. A live recording never reaches this path; glFinish ensures
                // cached EGLImages cannot outlive an unfinished read if a vendor fence wait faults.
                glFinish();
            }
        }
        if (destroyInputReadFenceSync(state, &receipt)) {
            receipt = InputReadFenceReceipt{};
        }
    }
}

size_t gpuStageIndex(GpuStage stage) noexcept {
    return static_cast<size_t>(stage);
}

bool initializeGpuStageTiming(OffscreenAhbEglEncoderState* state) {
    if (state == nullptr) return false;
    const char* extensions = reinterpret_cast<const char*>(glGetString(GL_EXTENSIONS));
    if (!hasExtension(extensions, "GL_EXT_disjoint_timer_query")) {
        LOGI("GPU stage timing unavailable: GL_EXT_disjoint_timer_query not advertised");
        return false;
    }

    state->glGenQueriesEXT = reinterpret_cast<PFNGLGENQUERIESEXTPROC>(
        eglGetProcAddress("glGenQueriesEXT")
    );
    state->glDeleteQueriesEXT = reinterpret_cast<PFNGLDELETEQUERIESEXTPROC>(
        eglGetProcAddress("glDeleteQueriesEXT")
    );
    state->glBeginQueryEXT = reinterpret_cast<PFNGLBEGINQUERYEXTPROC>(
        eglGetProcAddress("glBeginQueryEXT")
    );
    state->glEndQueryEXT = reinterpret_cast<PFNGLENDQUERYEXTPROC>(
        eglGetProcAddress("glEndQueryEXT")
    );
    state->glGetQueryivEXT = reinterpret_cast<PFNGLGETQUERYIVEXTPROC>(
        eglGetProcAddress("glGetQueryivEXT")
    );
    state->glGetQueryObjectuivEXT = reinterpret_cast<PFNGLGETQUERYOBJECTUIVEXTPROC>(
        eglGetProcAddress("glGetQueryObjectuivEXT")
    );
    state->glGetQueryObjectui64vEXT = reinterpret_cast<PFNGLGETQUERYOBJECTUI64VEXTPROC>(
        eglGetProcAddress("glGetQueryObjectui64vEXT")
    );
    if (state->glGenQueriesEXT == nullptr ||
        state->glDeleteQueriesEXT == nullptr ||
        state->glBeginQueryEXT == nullptr ||
        state->glEndQueryEXT == nullptr ||
        state->glGetQueryivEXT == nullptr ||
        state->glGetQueryObjectuivEXT == nullptr ||
        state->glGetQueryObjectui64vEXT == nullptr) {
        LOGW("GPU stage timing unavailable: disjoint timer entry points incomplete");
        return false;
    }

    // Query the target before allocating names. Some vendor drivers advertise the extension while
    // exposing zero timer bits; treating that as unsupported keeps diagnostics fail-open.
    while (glGetError() != GL_NO_ERROR) {}
    state->glGetQueryivEXT(
        GL_TIME_ELAPSED_EXT,
        GL_QUERY_COUNTER_BITS_EXT,
        &state->gpuStageCounterBits
    );
    const GLenum counterBitsError = glGetError();
    if (counterBitsError != GL_NO_ERROR || state->gpuStageCounterBits <= 0) {
        LOGW(
            "GPU stage timing unavailable: counterBits=%d error=0x%04x",
            state->gpuStageCounterBits,
            counterBitsError
        );
        state->gpuStageCounterBits = 0;
        return false;
    }

    state->glGenQueriesEXT(
        static_cast<GLsizei>(state->gpuStageQueries.size()),
        state->gpuStageQueries.data()
    );
    const GLenum allocationError = glGetError();
    const bool queriesReady = std::all_of(
        state->gpuStageQueries.begin(),
        state->gpuStageQueries.end(),
        [](GLuint query) { return query != 0u; }
    );
    if (allocationError != GL_NO_ERROR || !queriesReady) {
        state->glDeleteQueriesEXT(
            static_cast<GLsizei>(state->gpuStageQueries.size()),
            state->gpuStageQueries.data()
        );
        state->gpuStageQueries.fill(0u);
        LOGW(
            "GPU stage timing unavailable: query allocation failed error=0x%04x",
            allocationError
        );
        return false;
    }
    state->gpuStageTimingAvailable = true;
    LOGI(
        "GPU stage timing ready: GL_EXT_disjoint_timer_query bits=%d opt-in sampled without added waits",
        state->gpuStageCounterBits
    );
    return true;
}

void faultGpuStageTiming(
    OffscreenAhbEglEncoderState* state,
    const char* operation,
    GLenum error
) {
    if (state == nullptr) return;
    ++state->gpuStageQueryErrors;
    state->gpuStageTimingFaulted = true;
    state->gpuStageTimingSamplingEnabled = false;
    for (size_t index = 0; index < kGpuStageCount; ++index) {
        if (state->gpuStageQueryPending[index]) state->gpuStageQueryDiscard[index] = true;
    }
    LOGW(
        "GPU stage timing disabled fail-open operation=%s error=0x%04x; frame submission unchanged",
        operation != nullptr ? operation : "unknown",
        error
    );
}

bool beginGpuStageTiming(OffscreenAhbEglEncoderState* state, GpuStage stage) {
    if (state == nullptr ||
        !state->gpuStageTimingAvailable ||
        !state->gpuStageTimingSamplingEnabled ||
        state->gpuStageTimingFaulted) return false;
    const size_t index = gpuStageIndex(stage);
    if (state->gpuStageQueryPending[index] || state->gpuStageQueries[index] == 0u) return false;
    // Prove the GL error queue is clean before beginning. Without this boundary an earlier import or
    // uniform error could be misattributed to glBeginQueryEXT and return without closing a query that
    // actually began successfully.
    const GLenum priorError = glGetError();
    if (priorError != GL_NO_ERROR) {
        faultGpuStageTiming(state, "pre-glBeginQueryEXT", priorError);
        return false;
    }
    state->glBeginQueryEXT(GL_TIME_ELAPSED_EXT, state->gpuStageQueries[index]);
    const GLenum error = glGetError();
    if (error != GL_NO_ERROR) {
        faultGpuStageTiming(state, "glBeginQueryEXT", error);
        return false;
    }
    return true;
}

void endGpuStageTiming(
    OffscreenAhbEglEncoderState* state,
    GpuStage stage,
    bool queryStarted
) {
    if (state == nullptr || !queryStarted) return;
    // A draw error may already be queued. Always issue glEndQueryEXT first so a successfully-started
    // query can never remain active, then disable diagnostics if either the draw or end call erred.
    const GLenum priorError = glGetError();
    state->glEndQueryEXT(GL_TIME_ELAPSED_EXT);
    const GLenum error = glGetError();
    if (priorError != GL_NO_ERROR) {
        faultGpuStageTiming(state, "pre-glEndQueryEXT", priorError);
        return;
    }
    if (error != GL_NO_ERROR) {
        faultGpuStageTiming(state, "glEndQueryEXT", error);
        return;
    }
    const size_t index = gpuStageIndex(stage);
    state->gpuStageQueryPending[index] = true;
    state->gpuStageQueryDiscard[index] = false;
}

GpuStageSample collectGpuStageTiming(OffscreenAhbEglEncoderState* state) {
    GpuStageSample sample;
    if (state == nullptr ||
        !state->gpuStageTimingAvailable ||
        !state->gpuStageTimingSamplingEnabled ||
        state->gpuStageTimingFaulted) return sample;

    bool hasPending = false;
    for (bool pending : state->gpuStageQueryPending) hasPending = hasPending || pending;
    if (!hasPending) return sample;

    const GLenum priorError = glGetError();
    if (priorError != GL_NO_ERROR) {
        faultGpuStageTiming(state, "pre-collectGpuStageTiming", priorError);
        return sample;
    }

    GLint disjoint = GL_FALSE;
    glGetIntegerv(GL_GPU_DISJOINT_EXT, &disjoint);
    const GLenum disjointError = glGetError();
    if (disjointError != GL_NO_ERROR) {
        faultGpuStageTiming(state, "glGetIntegerv(GL_GPU_DISJOINT_EXT)", disjointError);
        return sample;
    }
    sample.disjoint = disjoint == GL_TRUE;
    if (sample.disjoint) {
        ++state->gpuStageDisjointBatches;
        for (size_t index = 0; index < kGpuStageCount; ++index) {
            if (state->gpuStageQueryPending[index]) state->gpuStageQueryDiscard[index] = true;
        }
    }

    for (size_t index = 0; index < kGpuStageCount; ++index) {
        if (!state->gpuStageQueryPending[index]) continue;
        GLuint available = GL_FALSE;
        state->glGetQueryObjectuivEXT(
            state->gpuStageQueries[index],
            GL_QUERY_RESULT_AVAILABLE_EXT,
            &available
        );
        const GLenum availableError = glGetError();
        if (availableError != GL_NO_ERROR) {
            faultGpuStageTiming(state, "glGetQueryObjectuivEXT", availableError);
            return sample;
        }
        if (available != GL_TRUE) {
            ++state->gpuStageUnavailablePolls;
            continue;
        }
        GLuint64 elapsedNs = 0;
        state->glGetQueryObjectui64vEXT(
            state->gpuStageQueries[index],
            GL_QUERY_RESULT_EXT,
            &elapsedNs
        );
        const GLenum resultError = glGetError();
        if (resultError != GL_NO_ERROR) {
            faultGpuStageTiming(state, "glGetQueryObjectui64vEXT", resultError);
            return sample;
        }
        state->gpuStageQueryPending[index] = false;
        const bool discard = state->gpuStageQueryDiscard[index];
        state->gpuStageQueryDiscard[index] = false;
        if (discard) continue;
        const uint64_t elapsedUs = static_cast<uint64_t>(elapsedNs / 1000u);
        sample.elapsedUs[index] = elapsedUs;
        sample.valid[index] = true;
        state->gpuStageStats[index].record(elapsedUs);
    }
    return sample;
}

HardwareBufferSourceProof classifySourcePrecision(const AHardwareBuffer_Desc& description) {
    switch (description.format) {
        case AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT:
            return {
                "AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT",
                16,
                "FP16",
                true
            };
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
    if (mode == "HLG" || mode == "PQ" || mode == "SDR" ||
        mode == "PROJECT_PQ_TEXTURE") return mode;
    return "SDR";
}

int32_t dataSpaceForDelivery(const std::string& deliveryMode) {
    if (deliveryMode == "HLG") return ADATASPACE_BT2020_HLG;
    if (deliveryMode == "PQ") return ADATASPACE_BT2020_PQ;
    // This is a shader-to-shader transport, not a display layer. Its producer proof carries the
    // ST.2084/BT.2020 contract out of band; UNKNOWN prevents GLConsumer/vendor composition from
    // presentation-transforming the codes before the project's single explicit PQ decode.
    if (deliveryMode == "PROJECT_PQ_TEXTURE") return ADATASPACE_UNKNOWN;
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
    if (deliveryMode == "PROJECT_PQ_TEXTURE") return dataSpace == ADATASPACE_UNKNOWN;
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
constexpr int kSensorNdrTonePqLutSize = 2048;
constexpr float kSensorNdrToneDomain = 256.0f;
constexpr float kNdr10MasterPeakNits = 5000.0f;
constexpr float kNivukxMasterPeakNits = 10000.0f;
constexpr float kSensorNdrPqNitsDomain = kNivukxMasterPeakNits;
// Piecewise-linear rows replace the old fourth-root coordinate shaper. Each lookup now performs
// only a small binary decision tree plus one cache-resident texture sample; no per-pixel sqrt/pow is
// needed to address the scalar acceleration table. Dense logarithmic rows near black are required:
// storing a mapped/source ratio is singular at zero, while a coarse first row visibly lifts black.
// RG therefore stores absolute mapped signal as FP16 high+residual and BA stores absolute nits the
// same way. Segment endpoints are copied bit-for-bit across rows, so filtering is continuous at every
// transition. The 2048-wide layout stays within the GLES 3.0 minimum texture-size contract.
constexpr std::array<float, 12> kSensorNdrToneSegmentBounds{
    0.0f,
    0.00000001f,
    0.0000001f,
    0.000001f,
    0.00001f,
    0.0001f,
    0.001f,
    0.01f,
    0.1f,
    1.0f,
    16.0f,
    kSensorNdrToneDomain
};
constexpr std::array<float, 7> kSensorNdrPqSegmentBounds{
    0.0f, 0.1f, 1.0f, 10.0f, 100.0f, 1000.0f, kSensorNdrPqNitsDomain
};
constexpr int kSensorNdrToneSegmentCount =
    static_cast<int>(kSensorNdrToneSegmentBounds.size()) - 1;
constexpr int kSensorNdrPqSegmentCount =
    static_cast<int>(kSensorNdrPqSegmentBounds.size()) - 1;
constexpr int kSensorNdrTonePqLutRows =
    kSensorNdrToneSegmentCount + kSensorNdrPqSegmentCount;

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

Float3 compressNdrPeakColorVolumeCpp(const Float3& rgbNits, float luminanceNits, float peakNits) {
    const float peak = std::clamp(peakNits, 203.0f, kNivukxMasterPeakNits);
    const float y = std::clamp(luminanceNits, 0.0f, peak);
    Float3 rgb{
        std::max(rgbNits.r, 0.0f),
        std::max(rgbNits.g, 0.0f),
        std::max(rgbNits.b, 0.0f)
    };
    const float hi = std::max(rgb.r, std::max(rgb.g, rgb.b));
    if (hi <= peak || hi <= y + 1e-8f) return rgb;
    const float chromaScale = std::clamp((peak - y) / (hi - y), 0.0f, 1.0f);
    rgb.r = std::clamp(y + (rgb.r - y) * chromaScale, 0.0f, peak);
    rgb.g = std::clamp(y + (rgb.g - y) * chromaScale, 0.0f, peak);
    rgb.b = std::clamp(y + (rgb.b - y) * chromaScale, 0.0f, peak);
    return rgb;
}

Float3 ndrPqLutSample(float r, float g, float b, bool nivukx) {
    constexpr float diffuseScene = 0.264967f;
    const float gamma = nivukx ? 0.88f : 0.92f;
    const float toeW = nivukx ? 0.24f : 0.20f;
    const float toeA = nivukx ? 0.026f : 0.018f;
    const float peak = nivukx ? kNivukxMasterPeakNits : kNdr10MasterPeakNits;
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
    const Float3 peakSafe = compressNdrPeakColorVolumeCpp(
        Float3{t.r * scale, t.g * scale, t.b * scale},
        nits,
        peak
    );
    return {
        pqOetfFromNitsCpp(peakSafe.r),
        pqOetfFromNitsCpp(peakSafe.g),
        pqOetfFromNitsCpp(peakSafe.b)
    };
}

float neoBlackPreservingMidtoneLiftCpp(float signal, float strength) {
    const float x = std::max(signal, 0.0f);
    if (x <= 0.0f || x >= 1.0f) return x;
    const float window = smoothstepCpp(0.025f, 0.20f, x) *
        (1.0f - smoothstepCpp(0.78f, 1.20f, x));
    return x + strength * std::sqrt(x) * (1.0f - x) * window;
}

float neoProfileMidtoneContrastCpp(float signal, float strength) {
    const float x = std::max(signal, 0.0f);
    if (x <= 0.0f || x >= 1.0f) return x;
    const float window = smoothstepCpp(0.055f, 0.24f, x) *
        (1.0f - smoothstepCpp(0.76f, 1.0f, x));
    const float signedContrast = (x - 0.50f) * 4.0f * x * (1.0f - x);
    return std::max(x + strength * signedContrast * window, 0.0f);
}

float neoMeasuredHighlightRolloffCpp(float signal, float bias, bool active) {
    const float x = std::max(signal, 0.0f);
    const float boundedBias = std::clamp(bias, 0.0f, 0.12f);
    if (!active || x <= 1.0f || boundedBias <= 0.0f) return x;
    const float excess = x - 1.0f;
    return 1.0f + excess / (1.0f + 4.0f * boundedBias * excess);
}

// Quantize through IEEE-754 binary16 exactly once on the host. The LUT is uploaded as RGBA16F, so
// splitting important scalar outputs into a half-precision high part plus a half-precision residual
// makes the shader reconstruction materially more accurate without increasing texture fetch count.
uint16_t sensorLutFloatToHalf(float value) {
    uint32_t bits = 0u;
    std::memcpy(&bits, &value, sizeof(bits));
    const uint32_t sign = (bits >> 16u) & 0x8000u;
    int32_t exponent = static_cast<int32_t>((bits >> 23u) & 0xffu) - 127 + 15;
    uint32_t mantissa = bits & 0x7fffffu;
    if (exponent <= 0) {
        if (exponent < -10) return static_cast<uint16_t>(sign);
        mantissa = (mantissa | 0x800000u) >> static_cast<uint32_t>(1 - exponent);
        return static_cast<uint16_t>(sign | ((mantissa + 0x1000u) >> 13u));
    }
    if (exponent >= 31) return static_cast<uint16_t>(sign | 0x7c00u);
    return static_cast<uint16_t>(
        sign |
        (static_cast<uint32_t>(exponent) << 10u) |
        ((mantissa + 0x1000u) >> 13u)
    );
}

float sensorLutHalfToFloat(uint16_t half) {
    const uint32_t sign = static_cast<uint32_t>(half & 0x8000u) << 16u;
    const uint32_t exponent = (half >> 10u) & 0x1fu;
    const uint32_t mantissa = half & 0x3ffu;
    uint32_t bits = 0u;
    if (exponent == 0u) {
        if (mantissa == 0u) {
            bits = sign;
        } else {
            uint32_t normalized = mantissa;
            uint32_t shift = 0u;
            while ((normalized & 0x400u) == 0u) {
                normalized <<= 1u;
                ++shift;
            }
            normalized &= 0x3ffu;
            bits = sign | ((127u - 15u - shift + 1u) << 23u) | (normalized << 13u);
        }
    } else if (exponent == 0x1fu) {
        bits = sign | 0x7f800000u | (mantissa << 13u);
    } else {
        bits = sign | ((exponent + (127u - 15u)) << 23u) | (mantissa << 13u);
    }
    float value = 0.0f;
    std::memcpy(&value, &bits, sizeof(value));
    return value;
}

std::array<float, 2> sensorLutHighResidual(float value) {
    const float high = sensorLutHalfToFloat(sensorLutFloatToHalf(value));
    return {high, value - high};
}

std::array<float, 8> sensorNdrTonePqLutKey(
    const OffscreenAhbEglEncoderState* state,
    float targetPeakNits
) {
    const bool measuredToneActive = state->ndrMeasuredTone[3] >= 0.5f;
    return {
        targetPeakNits,
        state->ndrToneProfile[1],
        state->ndrToneProfile[2],
        state->ndrToneProfile[3],
        state->ndrCharacterProfile[1],
        state->ndrCharacterProfile[2],
        measuredToneActive ? state->ndrMeasuredTone[0] : 0.0f,
        measuredToneActive ? 1.0f : 0.0f
    };
}

std::vector<float> buildSensorNdrTonePqLutData(
    const OffscreenAhbEglEncoderState* state,
    float targetPeakNits
) {
    constexpr int channels = 4;
    std::vector<float> data(
        static_cast<size_t>(kSensorNdrTonePqLutSize) *
            kSensorNdrTonePqLutRows * channels,
        0.0f
    );
    const float denom = static_cast<float>(kSensorNdrTonePqLutSize - 1);
    const float gamma = std::clamp(state->ndrToneProfile[1], 0.10f, 1.0f);
    const float toeWidth = std::clamp(state->ndrToneProfile[2], 0.001f, 0.90f);
    const float toeAmount = std::clamp(state->ndrToneProfile[3], 0.0f, 0.20f);
    const float shadowLift = std::clamp(state->ndrCharacterProfile[1], 0.0f, 0.35f);
    const float midtoneContrast = std::clamp(state->ndrCharacterProfile[2], 0.0f, 0.30f);
    const bool measuredToneActive = state->ndrMeasuredTone[3] >= 0.5f;
    const float measuredRolloff = measuredToneActive
        ? std::clamp(state->ndrMeasuredTone[0], 0.0f, 0.12f)
        : 0.0f;
    const float peak = std::clamp(targetPeakNits, 203.0f, kSensorNdrPqNitsDomain);

    for (int segment = 0; segment < kSensorNdrToneSegmentCount; ++segment) {
        const float low = kSensorNdrToneSegmentBounds[static_cast<size_t>(segment)];
        const float high = kSensorNdrToneSegmentBounds[static_cast<size_t>(segment + 1)];
        for (int i = 0; i < kSensorNdrTonePqLutSize; ++i) {
            const float u = static_cast<float>(i) / denom;
            const float sceneLuma = low + (high - low) * u;
            float mapped = neoSignalShapeCpp(sceneLuma, gamma, toeWidth, toeAmount);
            mapped = neoBlackPreservingMidtoneLiftCpp(mapped, shadowLift);
            mapped = neoProfileMidtoneContrastCpp(mapped, midtoneContrast);
            const float rolled = neoMeasuredHighlightRolloffCpp(
                mapped,
                measuredRolloff,
                measuredToneActive
            );
            const float nits = neoSignalToNitsCpp(rolled, 203.0f, peak);
            const std::array<float, 2> mappedSignal = sensorLutHighResidual(mapped);
            const std::array<float, 2> absoluteNits = sensorLutHighResidual(nits);
            const size_t base =
                (static_cast<size_t>(segment) * kSensorNdrTonePqLutSize +
                 static_cast<size_t>(i)) * channels;
            data[base] = mappedSignal[0];
            data[base + 1u] = mappedSignal[1];
            data[base + 2u] = absoluteNits[0];
            data[base + 3u] = absoluteNits[1];
        }
        if (segment > 0) {
            const size_t previousEnd =
                (static_cast<size_t>(segment - 1) * kSensorNdrTonePqLutSize +
                 static_cast<size_t>(kSensorNdrTonePqLutSize - 1)) * channels;
            const size_t currentStart =
                static_cast<size_t>(segment) * kSensorNdrTonePqLutSize * channels;
            for (int channel = 0; channel < channels; ++channel) {
                data[currentStart + static_cast<size_t>(channel)] =
                    data[previousEnd + static_cast<size_t>(channel)];
            }
        }
    }
    // Exact black is an explicit table invariant, never a copied near-black approximation.
    data[0] = 0.0f;
    data[1] = 0.0f;
    data[2] = 0.0f;
    data[3] = 0.0f;

    for (int segment = 0; segment < kSensorNdrPqSegmentCount; ++segment) {
        const float low = kSensorNdrPqSegmentBounds[static_cast<size_t>(segment)];
        const float high = kSensorNdrPqSegmentBounds[static_cast<size_t>(segment + 1)];
        const int row = kSensorNdrToneSegmentCount + segment;
        for (int i = 0; i < kSensorNdrTonePqLutSize; ++i) {
            const float u = static_cast<float>(i) / denom;
            const float nits = low + (high - low) * u;
            const std::array<float, 2> pq =
                sensorLutHighResidual(pqOetfFromNitsCpp(nits));
            const size_t base =
                (static_cast<size_t>(row) * kSensorNdrTonePqLutSize +
                 static_cast<size_t>(i)) * channels;
            data[base] = pq[0];
            data[base + 1u] = pq[1];
            data[base + 2u] = 0.0f;
            data[base + 3u] = 1.0f;
        }
        if (segment > 0) {
            const size_t previousEnd =
                (static_cast<size_t>(row - 1) * kSensorNdrTonePqLutSize +
                 static_cast<size_t>(kSensorNdrTonePqLutSize - 1)) * channels;
            const size_t currentStart =
                static_cast<size_t>(row) * kSensorNdrTonePqLutSize * channels;
            for (int channel = 0; channel < channels; ++channel) {
                data[currentStart + static_cast<size_t>(channel)] =
                    data[previousEnd + static_cast<size_t>(channel)];
            }
        }
    }
    // ST.2084's analytic zero is below one 10-bit code but nonzero in float. The accelerated path
    // uses an exact-zero endpoint so a mathematically black source remains exactly black.
    const size_t pqBlack =
        static_cast<size_t>(kSensorNdrToneSegmentCount) *
        kSensorNdrTonePqLutSize * channels;
    data[pqBlack] = 0.0f;
    data[pqBlack + 1u] = 0.0f;
    return data;
}

bool ensureSensorNdrTonePqLut(
    OffscreenAhbEglEncoderState* state,
    float targetPeakNits
) {
    if (state == nullptr) return false;
    const std::array<float, 8> key = sensorNdrTonePqLutKey(state, targetPeakNits);
    if (state->ndrSensorTonePqLutReady && state->ndrSensorTonePqLutKeyValid &&
        state->ndrSensorTonePqLutKey == key && state->ndrSensorTonePqLut != 0) {
        return true;
    }

    const std::vector<float> data = buildSensorNdrTonePqLutData(state, targetPeakNits);
    while (glGetError() != GL_NO_ERROR) {}
    if (state->ndrSensorTonePqLut == 0) {
        glGenTextures(1, &state->ndrSensorTonePqLut);
    }
    glBindTexture(GL_TEXTURE_2D, state->ndrSensorTonePqLut);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(
        GL_TEXTURE_2D,
        0,
        GL_RGBA16F,
        kSensorNdrTonePqLutSize,
        kSensorNdrTonePqLutRows,
        0,
        GL_RGBA,
        GL_FLOAT,
        data.data()
    );
    const GLenum error = glGetError();
    glBindTexture(GL_TEXTURE_2D, 0);
    if (error != GL_NO_ERROR) {
        LOGW("sensor-linear NDR tone/PQ LUT upload failed error=0x%04x", error);
        if (state->ndrSensorTonePqLut != 0) {
            glDeleteTextures(1, &state->ndrSensorTonePqLut);
            state->ndrSensorTonePqLut = 0;
        }
        state->ndrSensorTonePqLutReady = false;
        state->ndrSensorTonePqLutKeyValid = false;
        return false;
    }
    state->ndrSensorTonePqLutKey = key;
    state->ndrSensorTonePqLutKeyValid = true;
    state->ndrSensorTonePqLutReady = true;
    ++state->ndrSensorTonePqLutBuilds;
    LOGI(
        "sensor-linear NDR tone/PQ LUT ready size=%dx%d rgba16f segmented=true "
        "highResidual=true peak=%.1f build=%llu",
        kSensorNdrTonePqLutSize,
        kSensorNdrTonePqLutRows,
        targetPeakNits,
        static_cast<unsigned long long>(state->ndrSensorTonePqLutBuilds)
    );
    return true;
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
    l.neoDenoiseMix = glGetUniformLocation(program, "uNeoDenoiseMix");
    l.neoInvRangeVariance = glGetUniformLocation(program, "uNeoInvRangeVariance");
    l.neoSharpenAmount = glGetUniformLocation(program, "uNeoSharpenAmount");
    l.neoSharpenCap = glGetUniformLocation(program, "uNeoSharpenCap");
    l.neoChromaScale = glGetUniformLocation(program, "uNeoChromaScale");
    l.niv99RgbCorrection = glGetUniformLocation(program, "uNiv99RgbCorrection");
    l.niv99ActiveConfidence = glGetUniformLocation(program, "uNiv99ActiveConfidence");
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
    l.ndrTargetPeakNits = glGetUniformLocation(program, "uNdrTargetPeakNits");
    l.ndrPqLut = glGetUniformLocation(program, "uNdrPqLut");
    l.ndrPqLutReady = glGetUniformLocation(program, "uNdrPqLutReady");
    l.ndrSensorTonePqLut = glGetUniformLocation(program, "uNdrSensorTonePqLut");
    l.ndrSensorTonePqLutReady = glGetUniformLocation(program, "uNdrSensorTonePqLutReady");
    l.inputTransfer = glGetUniformLocation(program, "uInputTransfer");
    l.inputPrimaries = glGetUniformLocation(program, "uInputPrimaries");
    l.inputRange = glGetUniformLocation(program, "uInputRange");
    l.ndrWarmSkinRedTame = glGetUniformLocation(program, "uNdrWarmSkinRedTame");
    l.ndrMeasuredRgbScale = glGetUniformLocation(program, "uNdrMeasuredRgbScale");
    l.ndrMeasuredTone = glGetUniformLocation(program, "uNdrMeasuredTone");
    l.ndrMeasuredHue = glGetUniformLocation(program, "uNdrMeasuredHue");
    l.ndrToneProfile = glGetUniformLocation(program, "uNdrToneProfile");
    l.ndrCharacterProfile = glGetUniformLocation(program, "uNdrCharacterProfile");
    l.ndrDetailProfile = glGetUniformLocation(program, "uNdrDetailProfile");
    l.encOutFlip = glGetUniformLocation(program, "uEncOutFlip");
    return l;
}

/**
 * Neo ConstantLift moves values that are uniform for the whole frame out of the 4K fragment loop.
 * The arithmetic mirrors the canonical GLSL expressions exactly; only its execution frequency
 * changes from once per output pixel to once per submitted frame.
 */
struct NeoConstantLiftValues {
    float denoiseMix = 0.0f;
    float inverseRangeVariance = 0.0f;
    float sharpenAmount = 0.0f;
    float sharpenCap = 0.05f;
    float chromaScale = 0.0f;
};

inline NeoConstantLiftValues resolveNeoConstantLift(
    float sharpness,
    float denoise,
    float chromaDenoise
) noexcept {
    NeoConstantLiftValues values;
    values.denoiseMix = std::clamp(denoise, 0.0f, 1.0f);
    const float sigmaR = std::clamp(
        0.010f + 0.045f * values.denoiseMix,
        0.010f,
        0.05f
    );
    values.inverseRangeVariance = 1.0f / (2.0f * sigmaR * sigmaR);
    values.sharpenAmount = std::clamp(sharpness, 0.0f, 1.0f) * 0.85f;
    values.sharpenCap = 0.05f + 0.05f * values.sharpenAmount;
    values.chromaScale = std::clamp(chromaDenoise, 0.0f, 1.0f) * 0.30f;
    return values;
}

inline void uploadNeoConstantLift(
    const MainProgramLoc& locations,
    const NeoConstantLiftValues& values
) noexcept {
    // -1 is a valid no-op uniform location, but checking avoids needless driver calls on the
    // canonical GLES 3.0 fallback and every non-GatherGrid specialization.
    if (locations.neoDenoiseMix >= 0) {
        glUniform1f(locations.neoDenoiseMix, values.denoiseMix);
    }
    if (locations.neoInvRangeVariance >= 0) {
        glUniform1f(locations.neoInvRangeVariance, values.inverseRangeVariance);
    }
    if (locations.neoSharpenAmount >= 0) {
        glUniform1f(locations.neoSharpenAmount, values.sharpenAmount);
    }
    if (locations.neoSharpenCap >= 0) {
        glUniform1f(locations.neoSharpenCap, values.sharpenCap);
    }
    if (locations.neoChromaScale >= 0) {
        glUniform1f(locations.neoChromaScale, values.chromaScale);
    }
}

inline bool neoConstantLiftLocationsReady(const MainProgramLoc& locations) noexcept {
    return locations.neoDenoiseMix >= 0 &&
        locations.neoInvRangeVariance >= 0 &&
        locations.neoSharpenAmount >= 0 &&
        locations.neoSharpenCap >= 0 &&
        locations.neoChromaScale >= 0;
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

// Final-PQ transport has its own deliberately tiny program. The Vulkan producer has already
// performed every image operation, including output orientation and ST.2084 encoding; reaching the
// generic grade program here would apply those operations twice. NEAREST sampling plus identical
// source/destination extents makes each RGB10_A2 texel land on its matching encoder texel. Dither is
// disabled at draw time, so this program performs one code-value-preserving copy and nothing else.
GLuint createPregradedPqIdentityProgram() {
    static constexpr const char* kIdentityVertexShader = R"(#version 300 es
layout(location = 0) in vec2 aPosition;
layout(location = 1) in vec2 aTexCoord;
out highp vec2 vTexCoord;
void main() {
    gl_Position = vec4(aPosition, 0.0, 1.0);
    vTexCoord = aTexCoord;
}
)";
    static constexpr const char* kIdentityFragmentShader = R"(#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform highp samplerExternalOES uTexture;
in highp vec2 vTexCoord;
layout(location = 0) out highp vec4 outColor;
void main() {
    outColor = texture(uTexture, vTexCoord);
}
)";
    return linkVertexFragment(kIdentityVertexShader, kIdentityFragmentShader);
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
        "uniform highp samplerExternalOES uTexture;",
        "uniform highp __samplerExternal2DY2YEXT uTexture;"
    );
    replaceOne(
        source,
        "vec3 neoReadCamera(vec2 uv) { return neoDecodeIngress(texture(uTexture, uv).rgb); }",
        R"(vec3 neoReadCamera(vec2 uv) {
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

/**
 * Derives the normal-SDR hot-path shader from the canonical source using only invariants proved for
 * the current frame. uInputTransfer=0 keeps the Rec.709 luma/grade equations, PQ delivery is off,
 * A zero-LUT variant compiles the canonical LUT no-op branch away. A creative-LUT variant leaves
 * strength and sampling uniform-driven, preserving the exact selected cube while still removing
 * unreachable RAW/NDR/PQ work. For the identity variant, NIV99 confidence zero is exactly the
 * canonical identity branch.
 * The NIV99-active variant leaves confidence and RGB correction uniform-driven while specializing
 * only the same SDR/PQ/history invariants. Pre-grade history is exact on both supported routes:
 * decode-once swaps the previous decoded RGB10_A2 texture, while the external route maintains the
 * same source-history texture whenever temporal blend is non-zero.
 *
 * The sampler2D variant replaces normalized cardinal coordinates with integer texture offsets.
 * Selection is restricted to equal source/destination extents, so those samples address the same
 * four texels and preserve the complete five-tap denoise/sharpen result.
 */
std::string makeSdrInvariantFragment(
    const char* canonicalSource,
    bool sampler2d,
    bool keepNiv99Dynamic = false,
    bool keepCreativeLutDynamic = false
) {
    if (canonicalSource == nullptr) {
        LOGE("SDR invariant shader specialization rejected: null canonical source");
        return {};
    }

    std::string source(canonicalSource);
    const auto replaceExact = [&source](
        const std::string& dynamicToken,
        const std::string& constantToken,
        const char* label
    ) -> bool {
        const size_t first = source.find(dynamicToken);
        if (first == std::string::npos ||
            source.find(dynamicToken, first + dynamicToken.size()) != std::string::npos) {
            LOGE("SDR invariant shader specialization rejected: expected one %s token", label);
            return false;
        }
        source.replace(first, dynamicToken.size(), constantToken);
        return true;
    };

    if (!replaceExact(
            "uniform int uInputTransfer;",
            "const int uInputTransfer = 0;",
            "input-transfer") ||
        !replaceExact(
            "uniform float uHdrToPqConvert;",
            "const float uHdrToPqConvert = 0.0;",
            "PQ-conversion") ||
        !replaceExact(
            "uniform float uHistoryPreGrade;",
            "const float uHistoryPreGrade = 1.0;",
            "pre-grade-history") ||
        !replaceExact(
            "uniform float uNdrPqLutReady;",
            "const float uNdrPqLutReady = 0.0;",
            "NDR-PQ-LUT-readiness") ||
        !replaceExact(
            "uniform float uNdrSensorTonePqLutReady;",
            "const float uNdrSensorTonePqLutReady = 0.0;",
            "sensor-tone-LUT-readiness")) {
        return {};
    }

    if (!keepCreativeLutDynamic && !replaceExact(
            "uniform float uLutStrength;",
            "const float uLutStrength = 0.0;",
            "creative-LUT-bypass")) {
        return {};
    }

    if (!keepNiv99Dynamic && !replaceExact(
            "uniform float uNiv99ActiveConfidence;",
            "const float uNiv99ActiveConfidence = 0.0;",
            "NIV99-confidence")) {
        return {};
    }

    if (!sampler2d) return source;

    if (!replaceExact(
            "#extension GL_OES_EGL_image_external_essl3 : require\n",
            "",
            "external-texture-extension") ||
        !replaceExact(
            "samplerExternalOES uTexture",
            "sampler2D uTexture",
            "source-sampler")) {
        return {};
    }

    static const std::string normalizedCardinals = R"(vec3 neoSampleCameraEast(vec2 uv, vec2 texel) {
    return neoSampleCamera(uv + vec2(texel.x, 0.0));
}
vec3 neoSampleCameraWest(vec2 uv, vec2 texel) {
    return neoSampleCamera(uv - vec2(texel.x, 0.0));
}
vec3 neoSampleCameraNorth(vec2 uv, vec2 texel) {
    return neoSampleCamera(uv + vec2(0.0, texel.y));
}
vec3 neoSampleCameraSouth(vec2 uv, vec2 texel) {
    return neoSampleCamera(uv - vec2(0.0, texel.y));
})";
    static const std::string integerCardinals = R"(vec3 neoSampleCameraEast(vec2 uv, vec2 texel) {
    return textureOffset(uTexture, uv, ivec2(1, 0)).rgb;
}
vec3 neoSampleCameraWest(vec2 uv, vec2 texel) {
    return textureOffset(uTexture, uv, ivec2(-1, 0)).rgb;
}
vec3 neoSampleCameraNorth(vec2 uv, vec2 texel) {
    return textureOffset(uTexture, uv, ivec2(0, 1)).rgb;
}
vec3 neoSampleCameraSouth(vec2 uv, vec2 texel) {
    return textureOffset(uTexture, uv, ivec2(0, -1)).rgb;
})";
    if (!replaceExact(normalizedCardinals, integerCardinals, "cardinal-sampling-block")) {
        return {};
    }
    return source;
}

/**
 * Neo GatherGrid is an exact GLES 3.1+ specialization of an already-specialized sampler2D SDR
 * fragment. At a texel-centre coordinate, textureGatherOffsets component lanes address the base
 * footprint corners (0,+1), (+1,+1), (+1,0), and (0,0). The compensating constant offsets below
 * therefore reconstruct east, west, north, and south respectively. Three component gathers replace
 * four RGBA textureOffset instructions while returning the identical four RGB texels, including
 * CLAMP_TO_EDGE behavior at image boundaries. No denoise, detail, LUT, temporal, colour, precision,
 * or output equation is changed.
 */
std::string makeNeoGatherGridFragment(const std::string& sampler2dSource) {
    if (sampler2dSource.empty()) return {};

    std::string source(sampler2dSource);
    const auto replaceExact = [&source](
        const std::string& dynamicToken,
        const std::string& replacement,
        const char* label
    ) -> bool {
        const size_t first = source.find(dynamicToken);
        if (first == std::string::npos ||
            source.find(dynamicToken, first + dynamicToken.size()) != std::string::npos) {
            LOGE("Neo GatherGrid specialization rejected: expected one %s token", label);
            return false;
        }
        source.replace(first, dynamicToken.size(), replacement);
        return true;
    };

    static const std::string shaderVersion = "#version 300 es";
    static const std::string gatherShaderVersion =
        "#version 310 es\n#extension GL_EXT_gpu_shader5 : require";
    static const std::string integerCardinalReaders = R"(vec3 neoSampleCameraEast(vec2 uv, vec2 texel) {
    return textureOffset(uTexture, uv, ivec2(1, 0)).rgb;
}
vec3 neoSampleCameraWest(vec2 uv, vec2 texel) {
    return textureOffset(uTexture, uv, ivec2(-1, 0)).rgb;
}
vec3 neoSampleCameraNorth(vec2 uv, vec2 texel) {
    return textureOffset(uTexture, uv, ivec2(0, 1)).rgb;
}
vec3 neoSampleCameraSouth(vec2 uv, vec2 texel) {
    return textureOffset(uTexture, uv, ivec2(0, -1)).rgb;
})";
    static const std::string gatheredCardinalReader = R"(const ivec2 NEO_CARDINAL_GATHER_OFFSETS[4] = ivec2[4](
    ivec2( 1, -1), // result.x: base (0,+1) + offset = east  (+1, 0)
    ivec2(-2, -1), // result.y: base (+1,+1) + offset = west  (-1, 0)
    ivec2(-1,  1), // result.z: base (+1, 0) + offset = north ( 0,+1)
    ivec2( 0, -1)  // result.w: base (0, 0) + offset = south ( 0,-1)
);
void neoGatherCameraCardinals(
    vec2 uv,
    out vec3 east,
    out vec3 west,
    out vec3 north,
    out vec3 south
) {
    highp vec4 gatheredR = textureGatherOffsets(
        uTexture, uv, NEO_CARDINAL_GATHER_OFFSETS, 0
    );
    highp vec4 gatheredG = textureGatherOffsets(
        uTexture, uv, NEO_CARDINAL_GATHER_OFFSETS, 1
    );
    highp vec4 gatheredB = textureGatherOffsets(
        uTexture, uv, NEO_CARDINAL_GATHER_OFFSETS, 2
    );
    east = niv99ApplyWhiteBalance(neoDecodeIngress(vec3(gatheredR.x, gatheredG.x, gatheredB.x)));
    west = niv99ApplyWhiteBalance(neoDecodeIngress(vec3(gatheredR.y, gatheredG.y, gatheredB.y)));
    north = niv99ApplyWhiteBalance(neoDecodeIngress(vec3(gatheredR.z, gatheredG.z, gatheredB.z)));
    south = niv99ApplyWhiteBalance(neoDecodeIngress(vec3(gatheredR.w, gatheredG.w, gatheredB.w)));
})";
    static const std::string fourCardinalReads = R"(    vec3 cardinal0 = neoSampleCameraEast(vTexCoord, tx);
    vec3 cardinal1 = neoSampleCameraWest(vTexCoord, tx);
    vec3 cardinal2 = neoSampleCameraNorth(vTexCoord, tx);
    vec3 cardinal3 = neoSampleCameraSouth(vTexCoord, tx);)";
    static const std::string oneGatherGridRead = R"(    vec3 cardinal0;
    vec3 cardinal1;
    vec3 cardinal2;
    vec3 cardinal3;
    neoGatherCameraCardinals(
        vTexCoord,
        cardinal0,
        cardinal1,
        cardinal2,
        cardinal3
    );)";
    static const std::string canonicalTuningUniforms = R"(uniform float uSharpness;
uniform float uDenoise;
uniform float uChromaDenoise;)";
    static const std::string constantLiftTuningUniforms = R"(uniform float uSharpness;
uniform float uDenoise;
uniform float uChromaDenoise;
// Neo ConstantLift: exact frame constants, evaluated once on the CPU instead of per fragment.
uniform float uNeoDenoiseMix;
uniform float uNeoInvRangeVariance;
uniform float uNeoSharpenAmount;
uniform float uNeoSharpenCap;
uniform float uNeoChromaScale;)";
    static const std::string canonicalCardinalChroma = R"(    vec3 cardinalChroma[4];
    cardinalChroma[0] = cardinal0 - vec3(cardinalLuma.x);
    cardinalChroma[1] = cardinal1 - vec3(cardinalLuma.y);
    cardinalChroma[2] = cardinal2 - vec3(cardinalLuma.z);
    cardinalChroma[3] = cardinal3 - vec3(cardinalLuma.w);)";
    static const std::string explicitCardinalChroma = R"(    vec3 cardinalChroma0 = cardinal0 - vec3(cardinalLuma.x);
    vec3 cardinalChroma1 = cardinal1 - vec3(cardinalLuma.y);
    vec3 cardinalChroma2 = cardinal2 - vec3(cardinalLuma.z);
    vec3 cardinalChroma3 = cardinal3 - vec3(cardinalLuma.w);)";
    static const std::string canonicalCardinalAliases = R"(    vec3 northChroma = cardinalChroma[0];
    vec3 southChroma = cardinalChroma[1];
    vec3 eastChroma = cardinalChroma[2];
    vec3 westChroma = cardinalChroma[3];)";
    static const std::string explicitCardinalAliases = R"(    vec3 northChroma = cardinalChroma0;
    vec3 southChroma = cardinalChroma1;
    vec3 eastChroma = cardinalChroma2;
    vec3 westChroma = cardinalChroma3;)";
    static const std::string conditionalCreativeLut = R"(    if (uLutStrength > 0.0) {
        vec3 lutSample = texture(uLut, clamp(graded, 0.0, 1.0)).rgb;
        graded = mix(graded, lutSample, clamp(uLutStrength, 0.0, 1.0));
    })";
    static const std::string provenCreativeLut = R"(    vec3 lutSample = texture(uLut, clamp(graded, 0.0, 1.0)).rgb;
    graded = mix(graded, lutSample, clamp(uLutStrength, 0.0, 1.0));)";

    if (!replaceExact(shaderVersion, gatherShaderVersion, "GLSL-version") ||
        !replaceExact(integerCardinalReaders, gatheredCardinalReader, "cardinal-reader-block") ||
        !replaceExact(fourCardinalReads, oneGatherGridRead, "cardinal-call-block") ||
        !replaceExact(
            canonicalTuningUniforms,
            constantLiftTuningUniforms,
            "constant-lift-uniforms") ||
        !replaceExact(
            "    float dn = clamp(uDenoise, 0.0, 1.0);",
            "    float dn = uNeoDenoiseMix;",
            "constant-lift-denoise") ||
        !replaceExact(
            "    float sigmaR = clamp(0.010 + 0.045 * dn, 0.010, 0.05);\n"
            "    float invR2 = 1.0 / (2.0 * sigmaR * sigmaR);",
            "    float invR2 = uNeoInvRangeVariance;",
            "constant-lift-range-variance") ||
        !replaceExact(
            canonicalCardinalChroma,
            explicitCardinalChroma,
            "constant-lift-cardinal-chroma") ||
        !replaceExact(
            canonicalCardinalAliases,
            explicitCardinalAliases,
            "constant-lift-cardinal-aliases") ||
        !replaceExact(
            "    float amt = clamp(uSharpness, 0.0, 1.0) * 0.85;",
            "    float amt = uNeoSharpenAmount;",
            "constant-lift-sharpen-amount") ||
        !replaceExact(
            "    float cap = 0.05 + 0.05 * amt;",
            "    float cap = uNeoSharpenCap;",
            "constant-lift-sharpen-cap") ||
        !replaceExact(
            "    float chromaGuard = clamp(uChromaDenoise, 0.0, 1.0) * 0.30 * chromaFlat;",
            "    float chromaGuard = uNeoChromaScale * chromaFlat;",
            "constant-lift-chroma-scale") ||
        !replaceExact(
            conditionalCreativeLut,
            provenCreativeLut,
            "positive-creative-LUT")) {
        return {};
    }
    return source;
}

bool supportsNeoGatherGrid() {
    GLint major = 0;
    GLint minor = 0;
    while (glGetError() != GL_NO_ERROR) {}
    glGetIntegerv(GL_MAJOR_VERSION, &major);
    glGetIntegerv(GL_MINOR_VERSION, &minor);
    if (glGetError() != GL_NO_ERROR) return false;
    const char* extensions = reinterpret_cast<const char*>(glGetString(GL_EXTENSIONS));
    const bool shaderModelSupported = major > 3 || (major == 3 && minor >= 1);
    // Qualcomm exposes textureGatherOffsets through EXT_gpu_shader5 even on its GLES 3.2 compiler;
    // require the advertised extension instead of generating a shader the driver must reject.
    return shaderModelSupported && hasExtension(extensions, "GL_EXT_gpu_shader5");
}

void buildSdrInvariantPrograms(
    OffscreenAhbEglEncoderState* state,
    const char* vertexSource,
    const char* canonicalExternalFragment
) {
    const std::string external = makeSdrInvariantFragment(canonicalExternalFragment, false);
    const std::string sampler2d = makeSdrInvariantFragment(canonicalExternalFragment, true);
    const std::string niv99External =
        makeSdrInvariantFragment(canonicalExternalFragment, false, true);
    const std::string niv99Sampler2d =
        makeSdrInvariantFragment(canonicalExternalFragment, true, true);
    const std::string creativeLutExternal =
        makeSdrInvariantFragment(canonicalExternalFragment, false, false, true);
    const std::string creativeLutSampler2d =
        makeSdrInvariantFragment(canonicalExternalFragment, true, false, true);
    const std::string creativeLutNiv99External =
        makeSdrInvariantFragment(canonicalExternalFragment, false, true, true);
    const std::string creativeLutNiv99Sampler2d =
        makeSdrInvariantFragment(canonicalExternalFragment, true, true, true);
    if (!external.empty()) {
        state->sdrInvariantProgramExternal = linkVertexFragment(vertexSource, external.c_str());
        if (state->sdrInvariantProgramExternal != 0) {
            state->sdrInvariantLocExternal = fetchMainLoc(state->sdrInvariantProgramExternal);
        }
    }
    if (!sampler2d.empty()) {
        state->sdrInvariantProgram2d = linkVertexFragment(vertexSource, sampler2d.c_str());
        if (state->sdrInvariantProgram2d != 0) {
            state->sdrInvariantLoc2d = fetchMainLoc(state->sdrInvariantProgram2d);
        }
    }
    if (!niv99External.empty()) {
        state->sdrNiv99ProgramExternal = linkVertexFragment(vertexSource, niv99External.c_str());
        if (state->sdrNiv99ProgramExternal != 0) {
            state->sdrNiv99LocExternal = fetchMainLoc(state->sdrNiv99ProgramExternal);
        }
    }
    if (!niv99Sampler2d.empty()) {
        state->sdrNiv99Program2d = linkVertexFragment(vertexSource, niv99Sampler2d.c_str());
        if (state->sdrNiv99Program2d != 0) {
            state->sdrNiv99Loc2d = fetchMainLoc(state->sdrNiv99Program2d);
        }
    }
    if (!creativeLutExternal.empty()) {
        state->sdrCreativeLutProgramExternal =
            linkVertexFragment(vertexSource, creativeLutExternal.c_str());
        if (state->sdrCreativeLutProgramExternal != 0) {
            state->sdrCreativeLutLocExternal = fetchMainLoc(state->sdrCreativeLutProgramExternal);
        }
    }
    if (!creativeLutSampler2d.empty()) {
        state->sdrCreativeLutProgram2d =
            linkVertexFragment(vertexSource, creativeLutSampler2d.c_str());
        if (state->sdrCreativeLutProgram2d != 0) {
            state->sdrCreativeLutLoc2d = fetchMainLoc(state->sdrCreativeLutProgram2d);
        }
    }
    if (!creativeLutNiv99External.empty()) {
        state->sdrCreativeLutNiv99ProgramExternal =
            linkVertexFragment(vertexSource, creativeLutNiv99External.c_str());
        if (state->sdrCreativeLutNiv99ProgramExternal != 0) {
            state->sdrCreativeLutNiv99LocExternal =
                fetchMainLoc(state->sdrCreativeLutNiv99ProgramExternal);
        }
    }
    if (!creativeLutNiv99Sampler2d.empty()) {
        state->sdrCreativeLutNiv99Program2d =
            linkVertexFragment(vertexSource, creativeLutNiv99Sampler2d.c_str());
        if (state->sdrCreativeLutNiv99Program2d != 0) {
            state->sdrCreativeLutNiv99Loc2d = fetchMainLoc(state->sdrCreativeLutNiv99Program2d);
        }
    }

    state->neoGatherGridCapable = supportsNeoGatherGrid();
    if (state->neoGatherGridCapable) {
        std::string gatherVertex(vertexSource);
        replaceOne(gatherVertex, "#version 300 es", "#version 310 es");
        const std::string creativeLutGather =
            makeNeoGatherGridFragment(creativeLutSampler2d);
        const std::string creativeLutNiv99Gather =
            makeNeoGatherGridFragment(creativeLutNiv99Sampler2d);
        if (!creativeLutGather.empty()) {
            state->sdrCreativeLutGatherProgram2d =
                linkVertexFragment(gatherVertex.c_str(), creativeLutGather.c_str());
            if (state->sdrCreativeLutGatherProgram2d != 0) {
                state->sdrCreativeLutGatherLoc2d =
                    fetchMainLoc(state->sdrCreativeLutGatherProgram2d);
                if (!neoConstantLiftLocationsReady(state->sdrCreativeLutGatherLoc2d)) {
                    LOGE("Neo ConstantLift identity uniform contract incomplete; using GLES30 fallback");
                    glDeleteProgram(state->sdrCreativeLutGatherProgram2d);
                    state->sdrCreativeLutGatherProgram2d = 0;
                    state->sdrCreativeLutGatherLoc2d = MainProgramLoc{};
                }
            }
        }
        if (!creativeLutNiv99Gather.empty()) {
            state->sdrCreativeLutNiv99GatherProgram2d =
                linkVertexFragment(gatherVertex.c_str(), creativeLutNiv99Gather.c_str());
            if (state->sdrCreativeLutNiv99GatherProgram2d != 0) {
                state->sdrCreativeLutNiv99GatherLoc2d =
                    fetchMainLoc(state->sdrCreativeLutNiv99GatherProgram2d);
                if (!neoConstantLiftLocationsReady(state->sdrCreativeLutNiv99GatherLoc2d)) {
                    LOGE("Neo ConstantLift NIV99 uniform contract incomplete; using GLES30 fallback");
                    glDeleteProgram(state->sdrCreativeLutNiv99GatherProgram2d);
                    state->sdrCreativeLutNiv99GatherProgram2d = 0;
                    state->sdrCreativeLutNiv99GatherLoc2d = MainProgramLoc{};
                }
            }
        }
    }

    if (state->sdrInvariantProgramExternal == 0 || state->sdrInvariantProgram2d == 0) {
        LOGW(
            "SDR invariant specialization incomplete external=%u sampler2d=%u; "
            "canonical fallback retained",
            state->sdrInvariantProgramExternal,
            state->sdrInvariantProgram2d
        );
    } else {
        LOGI(
            "SDR invariant specialization ready external+sampler2D transfer=const-0 "
            "pqConvert=const-0 niv99Confidence=const-0 lutStrength=const-0 "
            "history=const-pre-grade "
            "sampler2DCardinals=INTEGER_TEXTURE_OFFSET qualityEquations=CANONICAL"
        );
    }
    if (state->sdrNiv99ProgramExternal == 0 || state->sdrNiv99Program2d == 0) {
        LOGW(
            "SDR NIV99 specialization incomplete external=%u sampler2d=%u; "
            "canonical fallback retained",
            state->sdrNiv99ProgramExternal,
            state->sdrNiv99Program2d
        );
    } else {
        LOGI(
            "SDR NIV99 specialization ready external+sampler2D transfer=const-0 "
            "pqConvert=const-0 niv99Correction=dynamic lutStrength=const-0 "
            "history=const-pre-grade "
            "sampler2DCardinals=INTEGER_TEXTURE_OFFSET qualityEquations=CANONICAL"
        );
    }
    if (state->sdrCreativeLutProgramExternal == 0 ||
        state->sdrCreativeLutProgram2d == 0 ||
        state->sdrCreativeLutNiv99ProgramExternal == 0 ||
        state->sdrCreativeLutNiv99Program2d == 0) {
        LOGW(
            "SDR creative-LUT specialization incomplete identityExternal=%u identity2d=%u "
            "niv99External=%u niv992d=%u; canonical fallback retained",
            state->sdrCreativeLutProgramExternal,
            state->sdrCreativeLutProgram2d,
            state->sdrCreativeLutNiv99ProgramExternal,
            state->sdrCreativeLutNiv99Program2d
        );
    } else {
        LOGI(
            "SDR creative-LUT specialization ready identity+NIV99 external+sampler2D "
            "transfer=const-0 pqConvert=const-0 lutStrength=dynamic "
            "lutSampling=canonical history=const-pre-grade qualityEquations=CANONICAL"
        );
    }
    if (!state->neoGatherGridCapable) {
        LOGI(
            "Neo GatherGrid unavailable: GLES 3.1 + GL_EXT_gpu_shader5 required; "
            "canonical sampler2D fallback retained"
        );
    } else if (state->sdrCreativeLutGatherProgram2d == 0 ||
               state->sdrCreativeLutNiv99GatherProgram2d == 0) {
        LOGW(
            "Neo GatherGrid compile incomplete identity=%u niv99=%u; canonical sampler2D fallback retained",
            state->sdrCreativeLutGatherProgram2d,
            state->sdrCreativeLutNiv99GatherProgram2d
        );
    } else {
        LOGI(
            "Neo GatherGrid ready GLES31 sampler2D creative-LUT identity+NIV99 "
            "cardinalFetches=3xRGB_GATHER exactTexels=EAST_WEST_NORTH_SOUTH "
            "constantLift=FRAME_UNIFORMS creativeLutBranch=PROVEN_ACTIVE "
            "qualityEquations=CANONICAL fallback=GLES30"
        );
    }
}

/**
 * Derive a strict RAW/ACEScg -> PQ delivery variant from the one canonical main shader. Exact
 * declarations change only where runtime admission proves the same value: RAW transfer, PQ delivery,
 * profile/peak, a successfully uploaded sensor tone/PQ LUT, and creative-LUT bypass for PQ output.
 * Every function body, five-tap sample, temporal stage, measured correction, profile equation, and
 * out-of-domain analytic fallback remains byte-identical to the canonical source.
 * Requiring one and only one occurrence of each declaration makes source drift fail closed.
 */
std::string makeSensorLinearFragment(const char* canonicalSource, int ndrDeliveryMode) {
    static const std::string dynamicDeclaration = "uniform int uInputTransfer;";
    static const std::string sensorLinearDeclaration = "const int uInputTransfer = 3;";
    static const std::string dynamicPqDeclaration = "uniform float uHdrToPqConvert;";
    static const std::string sensorPqDeclaration = "const float uHdrToPqConvert = 1.0;";
    static const std::string dynamicDeliveryDeclaration = "uniform float uNdrDeliveryMode;";
    static const std::string dynamicPeakDeclaration = "uniform float uNdrTargetPeakNits;";
    static const std::string dynamicSensorToneLutReadyDeclaration =
        "uniform float uNdrSensorTonePqLutReady;";
    static const std::string sensorToneLutReadyDeclaration =
        "const float uNdrSensorTonePqLutReady = 1.0;";
    static const std::string dynamicCreativeLutStrengthDeclaration =
        "uniform float uLutStrength;";
    static const std::string creativeLutDisabledDeclaration =
        "const float uLutStrength = 0.0;";
    const std::string sensorDeliveryDeclaration = ndrDeliveryMode == 0
        ? "const float uNdrDeliveryMode = 0.0;"
        : "const float uNdrDeliveryMode = 1.0;";
    const std::string sensorPeakDeclaration = ndrDeliveryMode == 0
        ? "const float uNdrTargetPeakNits = 5000.0;"
        : "const float uNdrTargetPeakNits = 10000.0;";
    if (canonicalSource == nullptr || (ndrDeliveryMode != 0 && ndrDeliveryMode != 1)) {
        LOGE("sensor-linear PQ shader specialization rejected: invalid canonical source or mode=%d",
             ndrDeliveryMode);
        return {};
    }

    std::string source(canonicalSource);
    const size_t first = source.find(dynamicDeclaration);
    if (first == std::string::npos ||
        source.find(dynamicDeclaration, first + dynamicDeclaration.size()) != std::string::npos) {
        LOGE("sensor-linear PQ shader specialization rejected: expected one input-transfer declaration");
        return {};
    }
    source.replace(first, dynamicDeclaration.size(), sensorLinearDeclaration);

    const auto replaceExactDeclaration = [&source](
        const std::string& dynamicToken,
        const std::string& constantToken,
        const char* label
    ) -> bool {
        const size_t first = source.find(dynamicToken);
        if (first == std::string::npos ||
            source.find(dynamicToken, first + dynamicToken.size()) != std::string::npos) {
            LOGE("sensor-linear PQ shader specialization rejected: expected one %s declaration",
                 label);
            return false;
        }
        source.replace(first, dynamicToken.size(), constantToken);
        return true;
    };

    if (!replaceExactDeclaration(
            dynamicPqDeclaration,
            sensorPqDeclaration,
            "PQ-conversion") ||
        !replaceExactDeclaration(
            dynamicDeliveryDeclaration,
            sensorDeliveryDeclaration,
            "delivery-mode") ||
        !replaceExactDeclaration(
            dynamicPeakDeclaration,
            sensorPeakDeclaration,
            "target-peak") ||
        !replaceExactDeclaration(
            dynamicSensorToneLutReadyDeclaration,
            sensorToneLutReadyDeclaration,
            "sensor-tone-LUT-readiness") ||
        !replaceExactDeclaration(
            dynamicCreativeLutStrengthDeclaration,
            creativeLutDisabledDeclaration,
            "creative-LUT-strength")) {
        return {};
    }
    return source;
}

void buildSensorLinearPrograms(
    OffscreenAhbEglEncoderState* state,
    const char* vertexSource,
    const char* canonicalExternalFragment
) {
    const std::string sensorExternal = makeSensorLinearFragment(canonicalExternalFragment, 0);
    if (sensorExternal.empty()) return;

    state->sensorLinearProgramExternal =
        linkVertexFragment(vertexSource, sensorExternal.c_str());
    if (state->sensorLinearProgramExternal != 0) {
        state->sensorLinearLocExternal = fetchMainLoc(state->sensorLinearProgramExternal);
    }

    const auto makeSensorLinear2dFragment = [](const std::string& sensorExternalSource) {
        std::string sensor2d(sensorExternalSource);
        replaceOne(sensor2d, "#extension GL_OES_EGL_image_external_essl3 : require\n", "");
        replaceOne(sensor2d, "samplerExternalOES uTexture", "sampler2D uTexture");
        static const std::string dynamicHistoryPreGrade = "uniform float uHistoryPreGrade;";
        static const std::string decodedHistoryPreGrade =
            "const float uHistoryPreGrade = 1.0;";
        const size_t historyDeclaration = sensor2d.find(dynamicHistoryPreGrade);
        if (historyDeclaration == std::string::npos ||
            sensor2d.find(
                dynamicHistoryPreGrade,
                historyDeclaration + dynamicHistoryPreGrade.size()
            ) != std::string::npos) {
            LOGE("sensor-linear sampler2D specialization rejected: expected one history declaration");
            return std::string{};
        }
        sensor2d.replace(
            historyDeclaration,
            dynamicHistoryPreGrade.size(),
            decodedHistoryPreGrade
        );
        // sampler2D supports a native integer texel offset. For the full-resolution decode-once path
        // it is exactly uv + offset / dimensions, but avoids four coordinate constructions. Runtime
        // selection below retains the canonical fallback wherever that identity is not exact.
        replaceOne(
            sensor2d,
            R"(vec3 neoSampleCameraEast(vec2 uv, vec2 texel) {
    return neoSampleCamera(uv + vec2(texel.x, 0.0));
}
vec3 neoSampleCameraWest(vec2 uv, vec2 texel) {
    return neoSampleCamera(uv - vec2(texel.x, 0.0));
}
vec3 neoSampleCameraNorth(vec2 uv, vec2 texel) {
    return neoSampleCamera(uv + vec2(0.0, texel.y));
}
vec3 neoSampleCameraSouth(vec2 uv, vec2 texel) {
    return neoSampleCamera(uv - vec2(0.0, texel.y));
})",
        R"(vec3 neoSampleCameraEast(vec2 uv, vec2 texel) {
    return neoDecodeIngress(textureOffset(uTexture, uv, ivec2(1, 0)).rgb);
}
vec3 neoSampleCameraWest(vec2 uv, vec2 texel) {
    return neoDecodeIngress(textureOffset(uTexture, uv, ivec2(-1, 0)).rgb);
}
vec3 neoSampleCameraNorth(vec2 uv, vec2 texel) {
    return neoDecodeIngress(textureOffset(uTexture, uv, ivec2(0, 1)).rgb);
}
vec3 neoSampleCameraSouth(vec2 uv, vec2 texel) {
    return neoDecodeIngress(textureOffset(uTexture, uv, ivec2(0, -1)).rgb);
})"
        );
        return sensor2d;
    };
    const std::string sensor2d = makeSensorLinear2dFragment(sensorExternal);
    if (!sensor2d.empty()) {
        state->sensorLinearProgram2d = linkVertexFragment(vertexSource, sensor2d.c_str());
        if (state->sensorLinearProgram2d != 0) {
            state->sensorLinearLoc2d = fetchMainLoc(state->sensorLinearProgram2d);
        }
    }

    const std::string sensorNivukxExternal =
        makeSensorLinearFragment(canonicalExternalFragment, 1);
    if (!sensorNivukxExternal.empty()) {
        state->sensorLinearNivukxProgramExternal =
            linkVertexFragment(vertexSource, sensorNivukxExternal.c_str());
        if (state->sensorLinearNivukxProgramExternal != 0) {
            state->sensorLinearNivukxLocExternal =
                fetchMainLoc(state->sensorLinearNivukxProgramExternal);
        }
        const std::string sensorNivukx2d =
            makeSensorLinear2dFragment(sensorNivukxExternal);
        if (!sensorNivukx2d.empty()) {
            state->sensorLinearNivukxProgram2d =
                linkVertexFragment(vertexSource, sensorNivukx2d.c_str());
            if (state->sensorLinearNivukxProgram2d != 0) {
                state->sensorLinearNivukxLoc2d =
                    fetchMainLoc(state->sensorLinearNivukxProgram2d);
            }
        }
    }

    if (state->sensorLinearProgramExternal == 0 ||
        state->sensorLinearProgram2d == 0 ||
        state->sensorLinearNivukxProgramExternal == 0 ||
        state->sensorLinearNivukxProgram2d == 0) {
        LOGW(
            "sensor-linear PQ specialization incomplete ndr10External=%u ndr10Sampler2d=%u "
            "nivukxExternal=%u nivukxSampler2d=%u; missing variants retain generic fallback",
            state->sensorLinearProgramExternal,
            state->sensorLinearProgram2d,
            state->sensorLinearNivukxProgramExternal,
            state->sensorLinearNivukxProgram2d
        );
    } else {
        LOGI(
            "sensor-linear PQ specialization ready NDR10+NIVUKX external+sampler2D "
            "transfer=const-3 pqConvert=const-1 delivery=const-0/1 masterNits=const-5000/10000 "
            "sampler2DCardinals=INTEGER_TEXTURE_OFFSET sensorToneLut=CONST_READY "
            "creativeLut=CONST_OFF sampler2DHistory=CONST_PRE_GRADE "
            "outOfDomainAnalyticFallback=retained canonicalGenericFallback=retained"
        );
    }
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
uniform highp samplerExternalOES uTexture;
uniform int uInputPrimaries;
uniform int uInputRange;
uniform vec2 uSourceTexel;
uniform vec2 uMinificationScale;
uniform int uPreserveExtendedRange;
in vec2 vTexCoord;
out vec4 outColor;
vec3 neoDecodeIngress(vec3 encoded) { return encoded; }
vec3 neoReadCamera(vec2 uv) { return neoDecodeIngress(texture(uTexture, uv).rgb); }
vec3 neoSampleCamera(vec2 uv) {
    // A target pixel covers uMinificationScale source pixels. Four bilinear quadrant samples form
    // a compact ratio-derived footprint filter. At 1:1 the offset is exactly zero and this branch
    // is identical to the original center fetch.
    vec2 downsampleScale = vec2(
        uMinificationScale.x > 1.0 ? uMinificationScale.x : 0.0,
        uMinificationScale.y > 1.0 ? uMinificationScale.y : 0.0
    );
    vec2 footprintOffset = downsampleScale * uSourceTexel * 0.25;
    if (max(footprintOffset.x, footprintOffset.y) <= 0.0) return neoReadCamera(uv);
    return 0.25 * (
        neoReadCamera(uv + vec2(-footprintOffset.x, -footprintOffset.y)) +
        neoReadCamera(uv + vec2( footprintOffset.x, -footprintOffset.y)) +
        neoReadCamera(uv + vec2(-footprintOffset.x,  footprintOffset.y)) +
        neoReadCamera(uv + vec2( footprintOffset.x,  footprintOffset.y))
    );
}
void main() {
    vec3 sampled = neoSampleCamera(vTexCoord);
    // Sensor-linear RGBA16F carries legitimate highlight energy above 1.0. Preserve it for the
    // downstream NDR shoulder; bounded P010/Neo-scene code transports retain their original clamp.
    outColor = vec4(
        uPreserveExtendedRange != 0 ? max(sampled, vec3(0.0)) : clamp(sampled, 0.0, 1.0),
        1.0
    );
}
)";
    state->decodeProgram = linkVertexFragment(vsrc, kDecodeFragment);
    if (state->decodeProgram != 0) {
        state->decodeTexLoc = glGetUniformLocation(state->decodeProgram, "uTexture");
        state->decodeSourceTexelLoc =
            glGetUniformLocation(state->decodeProgram, "uSourceTexel");
        state->decodeMinificationScaleLoc =
            glGetUniformLocation(state->decodeProgram, "uMinificationScale");
        state->decodePreserveExtendedRangeLoc =
            glGetUniformLocation(state->decodeProgram, "uPreserveExtendedRange");
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
            state->decodeExplicitYuvSourceTexelLoc =
                glGetUniformLocation(state->decodeProgramExplicitYuv, "uSourceTexel");
            state->decodeExplicitYuvMinificationScaleLoc =
                glGetUniformLocation(state->decodeProgramExplicitYuv, "uMinificationScale");
            state->decodeExplicitYuvPreserveExtendedRangeLoc =
                glGetUniformLocation(state->decodeProgramExplicitYuv, "uPreserveExtendedRange");
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
uniform highp samplerExternalOES uTexture;
uniform vec2 uTexel;
uniform float uSharpness;
uniform float uDenoise;
uniform float uChromaDenoise;
// Exact-frame NIV99 correction. Confidence is zero for every absent/unapproved packet.
uniform vec3 uNiv99RgbCorrection;
uniform float uNiv99ActiveConfidence;
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
// Project-owned content-master peak; strict NDR10/NIVUKX programs specialize this to 5000/10000.
uniform float uNdrTargetPeakNits;
// 65^3 RGB16F cache of the same NDR/NIVUKX HLG-domain -> ST.2084 operator below. Sampling this LUT
// removes the per-pixel pow/exp tone-map hot spot at 4K without changing the delivery contract.
uniform highp sampler3D uNdrPqLut;
uniform float uNdrPqLutReady;
// Cache-resident scalar acceleration for sensor-linear/Neo-scene NDR. Six piecewise-linear rows
// store profile tone/rolloff/nits ratios and six rows store ST.2084. Nits/PQ use FP16 high+residual
// reconstruction. Color classification, measured hue, chroma character, detail and peak-volume
// compression remain analytic and unchanged.
uniform highp sampler2D uNdrSensorTonePqLut;
uniform float uNdrSensorTonePqLutReady;
// Input-transfer truth. Proprietary NDR admits code 3 (RAW-derived linear ACEScg FP16) or code 4
// (project-owned NEO-SCENE-LOG10 packed transport). HLG/PQ are never promoted to NDR ingress.
uniform int uInputTransfer;
// Per-buffer Android dataspace metadata. The ordinary external path leaves YUV conversion to the
// driver; the GL_EXT_YUV_target variant consumes these in the project-owned decoder.
uniform int uInputPrimaries;
uniform int uInputRange;
// Proprietary NDR/NIVUKX skin/neutral red correction. Zero is an exact no-op.
uniform float uNdrWarmSkinRedTame;
// The same confidence-gated low-order calibration packet used by the RAW SDR monitor.
// RGB scale .w and tone .w are the active gates; identity values are exact no-ops.
uniform vec4 uNdrMeasuredRgbScale;
uniform vec4 uNdrMeasuredTone;
uniform vec2 uNdrMeasuredHue;
uniform vec4 uNdrToneProfile;       // sensorDiffuseScene, gamma, toeWidth, toeAmount
uniform vec4 uNdrCharacterProfile;  // highlightDesat, shadowMidtoneLift, midtoneContrast, chromaBoost
uniform vec4 uNdrDetailProfile;     // skyChromaBonus, detailStrength, detailLower, detailUpper
in vec2 vTexCoord;
out vec4 outColor;

const float NEO_SCENE_LOG_SLOPE = 32.0;
const float NEO_SCENE_LOG_DENOMINATOR = 13.000176099; // log2(1 + 32 * 256)
vec3 neoDecodeIngress(vec3 encoded) {
    if (uInputTransfer != 4) return encoded;
    return max(
        (exp2(clamp(encoded, 0.0, 1.0) * NEO_SCENE_LOG_DENOMINATOR) - vec3(1.0)) /
            NEO_SCENE_LOG_SLOPE,
        vec3(0.0)
    );
}

float luma(vec3 color) {
    return dot(
        color,
        (uInputTransfer == 3 || uInputTransfer == 4)
            ? vec3(0.2722287, 0.6740818, 0.0536895) // ACEScg/AP1 luminance
            : vec3(0.2126, 0.7152, 0.0722)
    );
}

vec3 niv99ApplyWhiteBalance(vec3 inputColor) {
    vec3 source = max(inputColor, vec3(0.0));
    float confidence = clamp(uNiv99ActiveConfidence, 0.0, 1.0);
    if (confidence <= 0.0) return source;
    vec3 gain = mix(
        vec3(1.0),
        clamp(uNiv99RgbCorrection, vec3(0.92), vec3(1.08)),
        confidence
    );
    vec3 corrected = source * gain;
    // Encoded Camera2 video has a unit legal ceiling. Preserve source highlight headroom so a WB
    // correction cannot create clipping. Sensor-linear project-owned inputs are not NIV99-eligible.
    float sourcePeak = max(source.r, max(source.g, source.b));
    float correctedPeak = max(corrected.r, max(corrected.g, corrected.b));
    if (uInputTransfer != 3 && uInputTransfer != 4 && sourcePeak <= 1.0001 && correctedPeak > 1.0) {
        float highlightGate = smoothstep(0.88, 1.0, sourcePeak);
        corrected *= mix(1.0, 1.0 / correctedPeak, highlightGate);
    }
    return max(corrected, vec3(0.0));
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
vec3 neoCompressPeakColorVolume(vec3 rgbNits, float luminanceNits, float peakNits) {
    float peak = clamp(peakNits, 203.0, 10000.0);
    float y = clamp(luminanceNits, 0.0, peak);
    vec3 rgb = max(rgbNits, vec3(0.0));
    float hi = max(max(rgb.r, rgb.g), rgb.b);
    if (hi <= peak || hi <= y + 1e-8) return rgb;
    float chromaScale = clamp((peak - y) / (hi - y), 0.0, 1.0);
    return clamp(vec3(y) + (rgb - vec3(y)) * chromaScale, 0.0, peak);
}
// SMPTE ST.2084 (PQ) EOTF: signal [0,1] -> display-linear normalized [0,1] (1.0 == 10000 cd/m^2).
// Inverse of neoDelPqFromNits; used to decode a PQ-encoded CAMERA input frame back to display-linear.
vec3 neoPqEotf(vec3 e) {
    vec3 ep = pow(clamp(e, 0.0, 1.0), vec3(1.0 / 78.84375));
    vec3 num = max(ep - 0.8359375, vec3(0.0));
    vec3 den = 18.8515625 - 18.6875 * ep;
    return pow(num / den, vec3(1.0 / 0.1593017578125));
}
float neoDelBlackPreservingMidtoneLift(float signal, float strength) {
    float x = max(signal, 0.0);
    if (x <= 0.0 || x >= 1.0) return x;
    float window = neoDelSmoothstep(0.025, 0.20, x) *
        (1.0 - neoDelSmoothstep(0.78, 1.20, x));
    return x + strength * sqrt(x) * (1.0 - x) * window;
}
float neoDelProfileMidtoneContrast(float signal, float strength) {
    float x = max(signal, 0.0);
    if (x <= 0.0 || x >= 1.0) return x;
    float window = neoDelSmoothstep(0.055, 0.24, x) *
        (1.0 - neoDelSmoothstep(0.76, 1.0, x));
    float signedContrast = (x - 0.50) * 4.0 * x * (1.0 - x);
    return max(x + strength * signedContrast * window, 0.0);
}
vec3 neoDelProfileChroma(
    vec3 color,
    vec3 source,
    float mappedY,
    float baseBoost,
    float skyBonusStrength
) {
    float peakValue = max(color.r, max(color.g, color.b));
    float floorValue = min(color.r, min(color.g, color.b));
    float relativeChroma = (peakValue - floorValue) / max(mappedY, 0.04);
    float colorConfidence = neoDelSmoothstep(0.035, 0.22, relativeChroma);
    // The two ratios have the same protected denominator. One explicit reciprocal preserves the
    // equation while avoiding a second full-rate divide in every sensor-linear output pixel.
    float inverseSourceGreen = 1.0 / max(source.g, 1e-8);
    float rg = source.r * inverseSourceGreen;
    float bg = source.b * inverseSourceGreen;
    float skin = neoDelSmoothstep(1.02, 1.16, rg) *
        (1.0 - neoDelSmoothstep(1.58, 2.10, rg)) *
        (1.0 - neoDelSmoothstep(0.98, 1.18, bg));
    float sky = neoDelSmoothstep(1.03, 1.22, bg) *
        (1.0 - neoDelSmoothstep(1.00, 1.22, rg));
    float tonalWindow = neoDelSmoothstep(0.035, 0.16, mappedY) *
        (1.0 - neoDelSmoothstep(0.88, 1.65, mappedY));
    float skinProtection = mix(1.0, 0.45, skin);
    float gain = 1.0 + (baseBoost + skyBonusStrength * sky) *
        colorConfidence * tonalWindow * skinProtection;
    return vec3(mappedY) + (color - vec3(mappedY)) * gain;
}
vec3 neoDelMapRgbHuePreserving(
    vec3 lin,
    float g,
    float toeW,
    float toeA,
    float desatCap,
    float shadowMidtoneLift,
    float midtoneContrast,
    float chromaBoost,
    float skyChromaBonus
) {
    vec3 s = max(lin, vec3(0.0));
    float yv = max(neoPqLuma2(s), 1e-8);
    float mappedY = neoDelSignalShape(yv, g, toeW, toeA);
    mappedY = neoDelBlackPreservingMidtoneLift(mappedY, shadowMidtoneLift);
    mappedY = neoDelProfileMidtoneContrast(mappedY, midtoneContrast);
    vec3 o = s * (mappedY / yv);
    o = neoDelProfileChroma(o, s, mappedY, chromaBoost, skyChromaBonus);
    float desat = clamp(desatCap * neoDelSmoothstep(0.55, 1.0, mappedY), 0.0, desatCap);
    o = mix(o, vec3(mappedY), desat);
    // Match NdrToneOperator/Hdr10PqPreviewCompositor: ratio-gated red-overflow reconstruction.
    // Extreme-red and low-blue orange objects remain outside the gate; corrected pixels retain luma.
    if (uNdrWarmSkinRedTame > 0.0) {
        float oy = max(neoPqLuma2(max(o, vec3(0.0))), 1e-8);
        float inverseSourceGreen = 1.0 / max(s.g, 1e-8);
        float rgRatio = s.r * inverseSourceGreen;
        float bgRatio = s.b * inverseSourceGreen;
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

float neoAcesCgLuma(vec3 c) {
    return dot(c, vec3(0.2722287, 0.6740818, 0.0536895));
}

vec3 neoRotateHueNeutralAxis(vec3 color, float degrees) {
    // normalize(vec3(1)) is invariant. Spell out the correctly-rounded constant so even conservative
    // mobile compilers cannot leave an inverse-square-root in the per-pixel calibration path.
    const vec3 axis = vec3(0.5773502691896258);
    float angle = radians(degrees);
    // |angle| is confidence-gated to <=12 degrees. Fifth/fourth-order minimax-equivalent Taylor
    // evaluation is below 1e-7 over that interval and avoids per-pixel transcendental sin/cos.
    float angle2 = angle * angle;
    float cs = 1.0 - 0.5 * angle2 + (angle2 * angle2) * (1.0 / 24.0);
    float sn = angle * (1.0 - angle2 * (1.0 / 6.0) +
        (angle2 * angle2) * (1.0 / 120.0));
    return color * cs + cross(axis, color) * sn + axis * dot(axis, color) * (1.0 - cs);
}

// Low-order measured calibration only. It preserves AP1 luminance through channel balance/hue repair;
// the tone operator below remains the sole owner of exposure and output transfer.
vec3 neoApplyNdrMeasuredCorrection(vec3 inputColor) {
    vec3 source = max(inputColor, vec3(0.0));
    // `active` is reserved by the Adreno GLSL ES compiler. Keeping a reserved identifier here
    // prevents the complete FP16 NDR encoder program from compiling, not just this branch.
    float correctionActive = step(0.5, min(uNdrMeasuredRgbScale.w, uNdrMeasuredTone.w));
    if (correctionActive <= 0.0) return source;

    float originalY = max(neoAcesCgLuma(source), 1e-7);
    source *= clamp(uNdrMeasuredRgbScale.xyz, vec3(0.85), vec3(1.15));
    source *= clamp(originalY / max(neoAcesCgLuma(source), 1e-7), 0.85, 1.15);

    float inverseSourceGreen = 1.0 / max(source.g, 1e-7);
    float rg = source.r * inverseSourceGreen;
    float rb = source.r / max(source.b, 1e-7);
    float bg = source.b * inverseSourceGreen;
    float skin = smoothstep(1.04, 1.24, rg) *
        (1.0 - smoothstep(1.70, 2.25, rg)) *
        smoothstep(1.02, 1.32, rb) *
        smoothstep(0.035, 0.16, originalY) *
        (1.0 - smoothstep(0.75, 1.10, originalY));
    float sky = smoothstep(1.02, 1.24, bg) *
        (1.0 - smoothstep(1.00, 1.28, rg)) *
        smoothstep(0.045, 0.20, originalY) *
        (1.0 - smoothstep(1.00, 1.45, originalY));
    float hueDegrees = uNdrMeasuredHue.x * skin + uNdrMeasuredHue.y * sky;
    if (abs(hueDegrees) > 1e-5) {
        source = neoRotateHueNeutralAxis(source, hueDegrees);
        source *= clamp(originalY / max(neoAcesCgLuma(source), 1e-7), 0.85, 1.15);
    }

    float y = neoAcesCgLuma(source);
    source = vec3(y) + (source - vec3(y)) * clamp(uNdrMeasuredTone.z, 0.85, 1.15);
    float shadowWindow = 1.0 - smoothstep(0.035, 0.22, originalY);
    source += vec3(clamp(uNdrMeasuredTone.y, -0.08, 0.08) * shadowWindow);
    return max(source, vec3(0.0));
}

float neoApplyMeasuredHighlightRolloff(float signal) {
    float x = max(signal, 0.0);
    float bias = clamp(uNdrMeasuredTone.x, 0.0, 0.12);
    if (x <= 1.0 || bias <= 0.0 || uNdrMeasuredTone.w < 0.5) return x;
    float excess = x - 1.0;
    return 1.0 + excess / (1.0 + 4.0 * bias * excess);
}

// ACEScg/AP1 scene-linear -> Rec.2020 scene-linear. Coefficients are the project-owned public-primary
// matrix and preserve the neutral axis; no display transfer or vendor tone curve is involved.
vec3 neoAcesCgToRec2020(vec3 aces) {
    return vec3(
        dot(aces, vec3( 1.02582, -0.02005, -0.00577)),
        dot(aces, vec3(-0.00386,  1.01040, -0.00654)),
        dot(aces, vec3(-0.01784, -0.07404,  1.09188))
    );
}

const float NEO_SENSOR_TONE_PQ_LUT_SIZE = 2048.0;
const float NEO_SENSOR_TONE_PQ_LUT_ROWS = 17.0;
// RGBA16F high/residual packing cannot represent every non-zero analytic result arbitrarily close
// to black for all legal tone-profile extrema. Keep that tiny interval on the canonical equations
// instead of allowing a LUT underflow to crush shadow chroma or luminance to zero.
const float NEO_SENSOR_TONE_LUT_MIN_SIGNAL = 0.000001;
const float NEO_SENSOR_TONE_DOMAIN = 256.0;
const float NEO_SENSOR_PQ_NITS_DOMAIN = 10000.0;
vec3 neoSensorToneSegment(float value) {
    float x = clamp(value, 0.0, NEO_SENSOR_TONE_DOMAIN);
    // (row, low, reciprocal span): the balanced tree needs at most four comparisons, and the
    // precomputed reciprocal removes a dynamic divide from every tone lookup.
    if (x <= 0.00001) {
        if (x <= 0.0000001) {
            if (x <= 0.00000001) return vec3(0.0, 0.0, 100000000.0);
            return vec3(1.0, 0.00000001, 11111111.11111111);
        }
        if (x <= 0.000001) return vec3(2.0, 0.0000001, 1111111.11111111);
        return vec3(3.0, 0.000001, 111111.11111111);
    }
    if (x <= 0.01) {
        if (x <= 0.0001) return vec3(4.0, 0.00001, 11111.11111111);
        if (x <= 0.001) return vec3(5.0, 0.0001, 1111.11111111);
        return vec3(6.0, 0.001, 111.11111111);
    }
    if (x <= 1.0) {
        if (x <= 0.1) return vec3(7.0, 0.01, 11.11111111);
        return vec3(8.0, 0.1, 1.11111111);
    }
    if (x <= 16.0) return vec3(9.0, 1.0, 0.0666666667);
    return vec3(10.0, 16.0, 0.00416666667);
}
vec3 neoSensorPqSegment(float value) {
    float x = clamp(value, 0.0, NEO_SENSOR_PQ_NITS_DOMAIN);
    if (x <= 10.0) {
        if (x <= 0.1) return vec3(0.0, 0.0, 10.0);
        if (x <= 1.0) return vec3(1.0, 0.1, 1.11111111);
        return vec3(2.0, 1.0, 0.111111111);
    }
    if (x <= 1000.0) {
        if (x <= 100.0) return vec3(3.0, 10.0, 0.0111111111);
        return vec3(4.0, 100.0, 0.00111111111);
    }
    return vec3(5.0, 1000.0, 0.000333333333);
}
vec2 neoSensorSegmentedLutCoord(float value, vec3 segment, float rowOffset) {
    float normalized = clamp((value - segment.y) * segment.z, 0.0, 1.0);
    float u = (normalized * (NEO_SENSOR_TONE_PQ_LUT_SIZE - 1.0) + 0.5) /
        NEO_SENSOR_TONE_PQ_LUT_SIZE;
    float v = (rowOffset + segment.x + 0.5) / NEO_SENSOR_TONE_PQ_LUT_ROWS;
    return vec2(u, v);
}
vec4 neoSampleSensorTone(float sceneLuma) {
    vec3 segment = neoSensorToneSegment(sceneLuma);
    return texture(
        uNdrSensorTonePqLut,
        neoSensorSegmentedLutCoord(sceneLuma, segment, 0.0)
    );
}
float neoSampleSensorPq(float nits) {
    vec3 segment = neoSensorPqSegment(nits);
    vec4 encoded = texture(
        uNdrSensorTonePqLut,
        neoSensorSegmentedLutCoord(nits, segment, 11.0)
    );
    return encoded.r + encoded.g;
}

// mode > 0.5 => NIVUKX Vision; else NDR10+. PQ is encoded only after the proprietary tone operator.
vec3 neoDeliverPqFromInput(vec3 source, float mode) {
    float g = clamp(uNdrToneProfile.y, 0.10, 1.0);
    float toeW = clamp(uNdrToneProfile.z, 0.001, 0.90);
    float toeA = clamp(uNdrToneProfile.w, 0.0, 0.20);
    float baselinePeak = mode > 0.5 ? 10000.0 : 5000.0;
    float peak = clamp(max(uNdrTargetPeakNits, baselinePeak), baselinePeak, 10000.0);
    float desat = clamp(uNdrCharacterProfile.x, 0.0, 0.50);
    // Normalize to engine-linear (1.0 == 203-nit diffuse). The sensor-linear branch deliberately
    // retains values above 1.0 until the highlight shoulder; it is never pre-clamped.
    bool sensorLinearInput = uInputTransfer == 3 || uInputTransfer == 4;
    vec3 lin;
    if (sensorLinearInput) {
        vec3 calibratedAcesCg = neoApplyNdrMeasuredCorrection(source);
        float sensorDiffuseScene = clamp(uNdrToneProfile.x, 0.02, 0.30);
        lin = max(neoAcesCgToRec2020(calibratedAcesCg), vec3(0.0)) / sensorDiffuseScene;
    } else if (uInputTransfer == 1) {
        lin = neoPqEotf(clamp(source, 0.0, 1.0)) * (10000.0 / 203.0);
    } else {
        lin = hlgInverseOetf(clamp(source, 0.0, 1.0)) / NEO_HLG_DIFFUSE_SCENE;
    }
    float sourceY = max(neoPqLuma2(lin), 1e-8);
    bool useSensorTonePqLut = sensorLinearInput &&
        uNdrSensorTonePqLutReady > 0.5 &&
        sourceY >= NEO_SENSOR_TONE_LUT_MIN_SIGNAL &&
        sourceY <= NEO_SENSOR_TONE_DOMAIN;
    vec4 sensorTone = useSensorTonePqLut
        ? neoSampleSensorTone(sourceY)
        : vec4(0.0);
    float mappedY = useSensorTonePqLut
        ? sensorTone.r + sensorTone.g
        : neoDelProfileMidtoneContrast(
            neoDelBlackPreservingMidtoneLift(
                neoDelSignalShape(sourceY, g, toeW, toeA),
                clamp(uNdrCharacterProfile.y, 0.0, 0.35)
            ),
            clamp(uNdrCharacterProfile.z, 0.0, 0.30)
        );
    vec3 t = lin * (mappedY / sourceY);
    t = neoDelProfileChroma(
        t,
        lin,
        mappedY,
        clamp(uNdrCharacterProfile.w, 0.0, 0.25),
        clamp(uNdrDetailProfile.x, 0.0, 0.08)
    );
    float highlightDesat = clamp(
        desat * neoDelSmoothstep(0.55, 1.0, mappedY),
        0.0,
        desat
    );
    t = mix(t, vec3(mappedY), highlightDesat);
    // Chroma reconstruction is luma-preserving only before gamut-boundary clipping. Saturated
    // sensor colours can legitimately extrapolate one channel below zero; the warm/red leg clamps
    // that channel while measuring/restoring luma, so the tone-LUT's pre-chroma absolute-nits value
    // is no longer authoritative for that pixel. Remember the event before any internal max(t, 0)
    // can hide it. The pixel still uses the same full-quality analytic nits equation below; only the
    // safe, unclipped majority reuses the cached scalar result.
    bool sensorColorPathClipped = useSensorTonePqLut &&
        any(lessThan(t, vec3(0.0)));
    // Keep the proprietary warm/red reconstruction byte-for-byte in the analytic color leg.
    if (uNdrWarmSkinRedTame > 0.0) {
        float originalToneY = max(neoPqLuma2(max(t, vec3(0.0))), 1e-8);
        float inverseLinearGreen = 1.0 / max(lin.g, 1e-8);
        float rgRatio = lin.r * inverseLinearGreen;
        float bgRatio = lin.b * inverseLinearGreen;
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
        vec3 corrected = t;
        corrected.r = mix(corrected.r, min(corrected.r, corrected.g * 1.42), weight);
        corrected.b = mix(corrected.b, max(corrected.b, corrected.g * 0.64), weight * 0.68);
        float correctedY = max(neoPqLuma2(max(corrected, vec3(0.0))), 1e-8);
        t = corrected * (originalToneY / correctedY);
        sensorColorPathClipped = sensorColorPathClipped ||
            (useSensorTonePqLut && any(lessThan(t, vec3(0.0))));
    }
    t = max(t, vec3(0.0));
    float sl;
    if (sensorLinearInput) {
        float preRolloffY = max(neoPqLuma2(t), 1e-8);
        // Rolloff is a cheap rational equation. Keeping it analytic preserves the exact profile
        // after chroma operations and leaves all four LUT channels available for singularity-free
        // absolute mapped-signal and absolute-nits high/residual pairs.
        float rolledY = neoApplyMeasuredHighlightRolloff(preRolloffY);
        t *= rolledY / preRolloffY;
        // Luma is linear: after the scalar rolloff the exact intended luminance is rolledY. Reusing
        // it avoids recomputing a second RGB dot product and also prevents finite-precision drift in
        // the subsequent nits normalization.
        sl = rolledY;
    } else {
        sl = max(neoPqLuma2(t), 1e-8);
    }
    bool useCachedSensorNits = useSensorTonePqLut && !sensorColorPathClipped;
    float nits = useCachedSensorNits
        ? sensorTone.b + sensorTone.a
        : neoDelSignalToNits(sl, 203.0, peak);
    float sc = nits / sl;
    vec3 peakSafe = neoCompressPeakColorVolume(t * sc, nits, peak);
    return useSensorTonePqLut
        ? vec3(
            neoSampleSensorPq(peakSafe.r),
            neoSampleSensorPq(peakSafe.g),
            neoSampleSensorPq(peakSafe.b)
        )
        : vec3(
            neoDelPqFromNits(peakSafe.r),
            neoDelPqFromNits(peakSafe.g),
            neoDelPqFromNits(peakSafe.b)
        );
}

// Anti-banding dither: cheap hash value-noise driving a triangular-PDF (TPDF) dither applied just
// before the limited-bit quantize. Neutral RGB dither removes banding without adding colored speckle.
float ditherHash(vec2 p) {
    return fract(52.9829189 * fract(0.06711056 * p.x + 0.00583715 * p.y));
}

// The NDR detail operator immediately clamps log2 to +/-0.22 stops and exp2 receives at most
// +/-0.20 stops from uNdrDetailProfile. Evaluate those bounded domains directly: the sixth-order
// log series is <6.2e-7 stops from log2 and the fourth-order exponential is <5.2e-7 in linear gain
// under IEEE-754 float evaluation. Both are far below one 10-bit PQ code value, while removing two
// transcendental instructions from every full-resolution sensor-linear NDR pixel.
float neoBoundedDetailLog2(float ratio) {
    float x = clamp(ratio, 0.858565436437754, 1.16473358646846) - 1.0;
    float polynomial = -0.166666666666667;
    polynomial = 0.2 + x * polynomial;
    polynomial = -0.25 + x * polynomial;
    polynomial = 0.333333333333333 + x * polynomial;
    polynomial = -0.5 + x * polynomial;
    polynomial = 1.0 + x * polynomial;
    return 1.4426950408889634 * x * polynomial;
}
float neoBoundedDetailExp2(float stops) {
    float z = 0.6931471805599453 * clamp(stops, -0.20, 0.20);
    return 1.0 + z * (
        1.0 + z * (0.5 + z * (0.166666666666667 + z * 0.041666666666667))
    );
}

vec3 neoReadCamera(vec2 uv) {
    return niv99ApplyWhiteBalance(neoDecodeIngress(texture(uTexture, uv).rgb));
}
vec3 neoSampleCamera(vec2 uv) { return neoReadCamera(uv); }
vec3 neoSampleCameraEast(vec2 uv, vec2 texel) {
    return neoSampleCamera(uv + vec2(texel.x, 0.0));
}
vec3 neoSampleCameraWest(vec2 uv, vec2 texel) {
    return neoSampleCamera(uv - vec2(texel.x, 0.0));
}
vec3 neoSampleCameraNorth(vec2 uv, vec2 texel) {
    return neoSampleCamera(uv + vec2(0.0, texel.y));
}
vec3 neoSampleCameraSouth(vec2 uv, vec2 texel) {
    return neoSampleCamera(uv - vec2(0.0, texel.y));
}

void main() {
    vec2 tx = max(uTexel, vec2(0.0001));
    vec3 c = neoSampleCamera(vTexCoord);
    // Generic cleanup thresholds are authored in the canonical diffuse-normalized signal domain.
    // RAW ingress still carries scene-linear values, where project diffuse white is normally 0.18.
    // Normalize comparison metrics only; never scale pixels or proprietary NDR/NIVUKX/NeoLog math.
    float calibrationSignalScale = (uInputTransfer == 3 || uInputTransfer == 4)
        ? 1.0 / clamp(uNdrToneProfile.x, 0.02, 0.30)
        : 1.0;
    if (uHistoryPreGrade > 0.5 && uTemporalBlend > 0.0 && uHistoryValid > 0.5) {
        vec3 histSource = niv99ApplyWhiteBalance(
            neoDecodeIngress(texture(uHistory, vTexCoord).rgb)
        );
        float lcNow = luma(c);
        float lcHist = luma(histSource);
        vec3 chNow = c - vec3(lcNow);
        vec3 chHist = histSource - vec3(lcHist);
        float sourceMotionMetric =
            (abs(lcNow - lcHist) + 0.6 * length(chNow - chHist)) *
            calibrationSignalScale;
        float guardSource = 1.0 - smoothstep(0.012, 0.06, sourceMotionMetric);
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
    float dn = clamp(uDenoise, 0.0, 1.0);
    // Range sigma is HARD-CLAMPED (<= 0.05) so that even if the host regresses and drives
    // uDenoise toward 1.0, the range window can never widen enough to collapse low-contrast
    // texture (skin pores / foliage / fabric) into flat patches. Defence-in-depth on top of
    // the signalFamily-aware host cap in OffscreenAhbEglEncoderBridge.
    float sigmaR = clamp(0.010 + 0.045 * dn, 0.010, 0.05);
    float invR2 = 1.0 / (2.0 * sigmaR * sigmaR);

    // Four explicit cardinal lanes let the compiler keep weights in one vec4 and let the derived
    // sampler2D program use native integer texture offsets. The external-texture variant retains the
    // exact normalized-coordinate equation through neoSampleCameraOffset.
    vec3 cardinal0 = neoSampleCameraEast(vTexCoord, tx);
    vec3 cardinal1 = neoSampleCameraWest(vTexCoord, tx);
    vec3 cardinal2 = neoSampleCameraNorth(vTexCoord, tx);
    vec3 cardinal3 = neoSampleCameraSouth(vTexCoord, tx);
    vec4 cardinalLuma = vec4(
        luma(cardinal0),
        luma(cardinal1),
        luma(cardinal2),
        luma(cardinal3)
    );
    vec4 dl = cardinalLuma - vec4(lc);
    // Four-lane form of the canonical scalar equation:
    // float bilateralLumaDeltaMetric = dl * calibrationSignalScale
    vec4 bilateralLumaDeltaMetric = dl * calibrationSignalScale;
    vec4 bilateralX = bilateralLumaDeltaMetric * bilateralLumaDeltaMetric * invR2;
    vec4 bilateralReciprocal = 1.0 / (vec4(1.0) + bilateralX);
    vec4 bilateralWeight = 1.11 * bilateralReciprocal * bilateralReciprocal;
    vec3 accBil = c;
    accBil += cardinal0 * bilateralWeight.x;
    accBil += cardinal1 * bilateralWeight.y;
    accBil += cardinal2 * bilateralWeight.z;
    accBil += cardinal3 * bilateralWeight.w;
    float wBil = 1.0 + dot(bilateralWeight, vec4(1.0));

    // Equal spatial weights collapse to one vector multiply and a compile-time reciprocal. This is
    // the same 5-tap kernel: (center + 1.11 * sum(cardinals)) / (1 + 4 * 1.11).
    const float gaussianNeighbourWeight = 1.11;
    const float inverseGaussianWeight = 1.0 / 5.44;
    vec3 gauss = (
        c + gaussianNeighbourWeight * (cardinal0 + cardinal1 + cardinal2 + cardinal3)
    ) * inverseGaussianWeight;

    vec3 cardinalChroma[4];
    cardinalChroma[0] = cardinal0 - vec3(cardinalLuma.x);
    cardinalChroma[1] = cardinal1 - vec3(cardinalLuma.y);
    cardinalChroma[2] = cardinal2 - vec3(cardinalLuma.z);
    cardinalChroma[3] = cardinal3 - vec3(cardinalLuma.w);
    vec3 bilateral = accBil / wBil;

    // Edge-preserving: safe to apply at full (already-capped) requested strength because edges
    // keep ~0 range weight and are not blended toward the mean.
    vec3 denoised = mix(c, bilateral, dn);

    // Halo-controlled unsharp. detail is the high-freq of the DENOISED signal (real structure
    // that survived denoising, NOT raw grain), backed off on strong edges, and symmetrically
    // capped so overshoot cannot ring or re-amplify residual noise.
    vec3 detail = denoised - gauss;
    float amt = clamp(uSharpness, 0.0, 1.0) * 0.85;
    float lcd = abs(luma(detail));
    float detailLumaMetric = lcd * calibrationSignalScale;
    float haloGuard = 1.0 - smoothstep(0.06, 0.20, detailLumaMetric);
    float cap = 0.05 + 0.05 * amt;
    vec3 boost = clamp(detail * amt * haloGuard, -cap, cap);
    vec3 sharpened = denoised + boost;

    float y = luma(sharpened);
    vec3 chroma = sharpened - vec3(y);
    // Sensor-black calibration reuses the already-computed spatial reference, so it adds no texture
    // fetches and no serial stage. It preserves the sharpened center luma exactly, changing ONLY
    // near-black chroma speckle and a spatially-coherent sub-noise-floor sensor offset. Recoverable
    // saturated colour and luma structure are protected; therefore this applies uniformly to
    // SDR/Pro/LOG/NDR processed delivery and every detail profile without crushing shadow detail or
    // lowering saturation outside the sensor uncertainty floor.
    float gy = luma(gauss);
    vec3 consensusChroma = gauss - vec3(gy);
    vec3 northChroma = cardinalChroma[0];
    vec3 southChroma = cardinalChroma[1];
    vec3 eastChroma = cardinalChroma[2];
    vec3 westChroma = cardinalChroma[3];
    vec3 neighbourConsensusChroma =
        (northChroma + southChroma + eastChroma + westChroma) * 0.25;
    float localStructure = max(abs(lc - gy), lcd);
    vec3 northSouthChromaDelta = northChroma - southChroma;
    vec3 eastWestChromaDelta = eastChroma - westChroma;
    // sqrt is monotonic for non-negative inputs, so max(length(a), length(b)) is exactly
    // sqrt(max(dot(a,a), dot(b,b))). This removes one square-root per output pixel.
    float chromaStructure = sqrt(max(
        dot(northSouthChromaDelta, northSouthChromaDelta),
        dot(eastWestChromaDelta, eastWestChromaDelta)
    ));
    float calibrationY = y * calibrationSignalScale;
    float calibrationLocalStructure = localStructure * calibrationSignalScale;
    float calibrationChromaStructure = chromaStructure * calibrationSignalScale;
    float edgeProtect = max(
        smoothstep(0.006, 0.045, calibrationLocalStructure),
        smoothstep(0.018, 0.100, calibrationChromaStructure)
    );
    vec3 calibratedChroma = chroma;
    float coherentColorProtect = 0.0;
    float outlierConfidence = 0.0;
    // smoothstep is exactly zero/one outside its edge interval. Make those zero-weight regions
    // explicit so ordinary midtones/highlights do not execute three square roots and the complete
    // shadow-calibration graph. Guarded pixels execute the canonical equations unchanged.
    if (calibrationY < 0.090 && edgeProtect < 1.0) {
        float calibrationConsensusMagnitude =
            length(consensusChroma) * calibrationSignalScale;
        float calibrationDisagreement =
            length(chroma - consensusChroma) * calibrationSignalScale;
        float deepShadow = 1.0 - smoothstep(0.018, 0.090, calibrationY);
        coherentColorProtect =
            smoothstep(0.018, 0.085, calibrationConsensusMagnitude);
        outlierConfidence = smoothstep(0.0008, 0.018, calibrationDisagreement);
        float shadowCalibrationBlend = clamp(
            deepShadow * (1.0 - edgeProtect) * mix(0.42, 0.96, outlierConfidence) *
                (1.0 - coherentColorProtect * 0.82),
            0.0,
            0.96
        );
        calibratedChroma = mix(chroma, consensusChroma, shadowCalibrationBlend);
    }
    // Visible neutral-surface outlier suppression uses the taps already resident in this pass; it
    // adds no texture fetches or pipeline stage. Neighbour-only consensus removes isolated coloured
    // P010 speckles, while luma/chroma edge guards and the neutral-consensus gate retain real colour.
    if (calibrationY > 0.075 && edgeProtect < 1.0) {
        float calibrationNeighbourConsensusMagnitude =
            length(neighbourConsensusChroma) * calibrationSignalScale;
        if (calibrationNeighbourConsensusMagnitude < 0.090) {
            float calibrationSurfaceDisagreement =
                length(chroma - neighbourConsensusChroma) * calibrationSignalScale;
            float surfaceNeutral =
                1.0 - smoothstep(0.020, 0.090, calibrationNeighbourConsensusMagnitude);
            float visibleSurface = smoothstep(0.075, 0.240, calibrationY);
            float surfaceOutlierConfidence = smoothstep(
                0.0020,
                0.0280,
                calibrationSurfaceDisagreement
            );
            float surfaceOutlierBlend = clamp(
                visibleSurface * (1.0 - edgeProtect) * surfaceNeutral *
                    surfaceOutlierConfidence * 0.92,
                0.0,
                0.92
            );
            calibratedChroma = mix(
                calibratedChroma,
                neighbourConsensusChroma,
                surfaceOutlierBlend
            );
        }
    }
    if (calibrationY < 0.065 && edgeProtect < 1.0) {
        float blackUncertainty = 1.0 - smoothstep(0.012, 0.065, calibrationY);
        float calibrationChromaMagnitude = length(calibratedChroma) * calibrationSignalScale;
        float tinyChroma =
            1.0 - smoothstep(0.012, 0.070, calibrationChromaMagnitude);
        float neutralize = clamp(
            blackUncertainty * tinyChroma * (1.0 - edgeProtect) *
                (1.0 - coherentColorProtect) * mix(0.96, 1.0, outlierConfidence),
            0.0,
            0.995
        );
        calibratedChroma *= 1.0 - neutralize;
    }

    // General chroma-noise reduction remains flat-area gated. Denoise toward the LOCAL four-neighbour
    // chroma consensus instead of multiplying every flat region toward neutral grey. The former
    // attenuation destroyed coherent NDR colour after the RAW handoff (especially with the high-ISO
    // chroma strength near 1.0); a local consensus removes isolated magenta/green sensor noise while a
    // genuinely coloured surface has the same chroma in its neighbours and therefore keeps its full
    // magnitude. Luma is reconstructed from the untouched center value below.
    float chromaFlat = 1.0 - smoothstep(0.02, 0.10, detailLumaMetric);
    float chromaGuard = clamp(uChromaDenoise, 0.0, 1.0) * 0.30 * chromaFlat;
    vec3 spatiallyDenoisedChroma = mix(
        calibratedChroma,
        neighbourConsensusChroma,
        chromaGuard
    );
    vec3 finalColor = vec3(y) + spatiallyDenoisedChroma;

    // Display-referred colour-science look: identity mat3 + contrast 1.0 => bit-identical.
    vec3 graded = uGradeMatrix * finalColor;
    float gradePivot = (uInputTransfer == 3 || uInputTransfer == 4)
        ? clamp(uNdrToneProfile.x, 0.02, 0.30)
        : 0.5;
    graded = (graded - vec3(gradePivot)) * uGradeContrast + vec3(gradePivot);
    // Creative 3D LUT (host forces strength 0 on HLG/PQ, so HDR transport is never touched).
    if (uLutStrength > 0.0) {
        vec3 lutSample = texture(uLut, clamp(graded, 0.0, 1.0)).rgb;
        graded = mix(graded, lutSample, clamp(uLutStrength, 0.0, 1.0));
    }
    // The four axial samples used by the denoise/unsharp stage are already resident. Reuse their
    // spatial mean for the calibrated NDR/NIVUKX detail character, so distant texture gains bounded
    // local contrast with zero additional texture fetches and no sequential pipeline stage.
    bool ndrSensorLinearDetail = uHdrToPqConvert > 0.5 &&
        (uInputTransfer == 3 || uInputTransfer == 4);
    if (ndrSensorLinearDetail) {
        float centerSceneY = max(neoAcesCgLuma(max(graded, vec3(0.0))), 0.0);
        float localSceneY = max(neoAcesCgLuma(max(gauss, vec3(0.0))), 0.0);
        float localDetailStops = neoBoundedDetailLog2(
            (centerSceneY + 0.0020) / (localSceneY + 0.0020)
        );
        float edgeConfidence = smoothstep(0.006, 0.040, abs(centerSceneY - localSceneY));
        float shadowNoiseGuard = smoothstep(0.018, 0.075, centerSceneY);
        float highlightGuard = 1.0 - smoothstep(1.30, 3.20, centerSceneY);
        localDetailStops = localDetailStops *
            edgeConfidence * shadowNoiseGuard * highlightGuard;
        float detailStrength = clamp(uNdrDetailProfile.y, 0.0, 0.75);
        float detailGainStops = clamp(
            localDetailStops * detailStrength,
            clamp(uNdrDetailProfile.z, -0.20, 0.0),
            clamp(uNdrDetailProfile.w, 0.0, 0.20)
        );
        graded *= neoBoundedDetailExp2(detailGainStops);
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
        float temporalMotionMetric =
            (abs(lg - lh) + 0.6 * length(chG - chH)) * calibrationSignalScale;
        float guard = 1.0 - smoothstep(0.012, 0.06, temporalMotionMetric);
        float b = clamp(uTemporalBlend, 0.0, 1.0) * guard;
        graded = mix(graded, hist, b);
    }
    // Preserve scene-linear highlight latitude until the NDR shoulder. Encoded compatibility inputs
    // remain bounded to their legal signal domain.
    vec3 finalRgb = (uInputTransfer == 3 || uInputTransfer == 4)
        ? max(graded, vec3(0.0))
        : clamp(graded, 0.0, 1.0);
    // Encode ST.2084 only at final delivery. SDR/LOG surfaces never set this branch.
    if (uHdrToPqConvert > 0.5) {
        // The cached LUT is HLG-indexed and is therefore forbidden for sensor-linear/PQ input.
        finalRgb = clamp(
            (uNdrPqLutReady > 0.5 && uInputTransfer == 2 && uNdrWarmSkinRedTame <= 0.0)
                ? texture(uNdrPqLut, finalRgb).rgb
                : neoDeliverPqFromInput(finalRgb, uNdrDeliveryMode),
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
    // Normal SDR fast path: compile-time specialize only exact, frame-proven no-op branches. This
    // keeps all active quality math canonical while removing the branch/register cost that pushed
    // the measured 4096x3072 grade beyond its 40 ms frame budget.
    buildSdrInvariantPrograms(state, kVertexShader, kFragmentShader);
    // Strict RAW/ACEScg PQ variants: same source/equations with exact transfer, PQ-conversion, and
    // NDR10/NIVUKX delivery invariants. Compilation is non-fatal; any unavailable or ineligible
    // variant falls back to the canonical generic main shader at submission time.
    buildSensorLinearPrograms(state, kVertexShader, kFragmentShader);
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
    state->eglCreateSyncKHR = reinterpret_cast<PFNEGLCREATESYNCKHRPROC>(
        eglGetProcAddress("eglCreateSyncKHR")
    );
    state->eglDestroySyncKHR = reinterpret_cast<PFNEGLDESTROYSYNCKHRPROC>(
        eglGetProcAddress("eglDestroySyncKHR")
    );
    state->eglClientWaitSyncKHR = reinterpret_cast<PFNEGLCLIENTWAITSYNCKHRPROC>(
        eglGetProcAddress("eglClientWaitSyncKHR")
    );
    state->inputReadFenceAvailable =
        hasExtension(extensions, "EGL_KHR_fence_sync") &&
        state->eglCreateSyncKHR != nullptr &&
        state->eglDestroySyncKHR != nullptr &&
        state->eglClientWaitSyncKHR != nullptr;
    if (!state->inputReadFenceAvailable) {
        LOGW("EGL input-read fence receipts unavailable; recorder retains synchronous safety barrier");
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
    state->contextPriorityExtensionAvailable =
        hasExtension(extensions, "EGL_IMG_context_priority");
    state->contextPriorityHighRequested = state->contextPriorityExtensionAvailable;
    state->contextPriorityFallbackUsed = false;
    state->contextPriorityQuerySucceeded = false;
    state->contextPriorityGranted = EGL_NONE;
    state->contextPriorityRequestError = EGL_SUCCESS;
    if (state->contextPriorityHighRequested) {
        const EGLint highPriorityContextAttribs[] = {
            EGL_CONTEXT_CLIENT_VERSION, 3,
            EGL_CONTEXT_PRIORITY_LEVEL_IMG, EGL_CONTEXT_PRIORITY_HIGH_IMG,
            EGL_NONE
        };
        state->context = eglCreateContext(
            state->display,
            state->config,
            EGL_NO_CONTEXT,
            highPriorityContextAttribs
        );
        if (state->context == EGL_NO_CONTEXT) {
            state->contextPriorityRequestError = eglGetError();
            state->contextPriorityFallbackUsed = true;
            LOGW(
                "EGL high-priority context request rejected error=0x%04x; retrying ordinary ES3 context",
                state->contextPriorityRequestError
            );
        }
    }
    if (state->context == EGL_NO_CONTEXT) {
        state->context = eglCreateContext(
            state->display,
            state->config,
            EGL_NO_CONTEXT,
            contextAttribs
        );
    }
    if (state->context == EGL_NO_CONTEXT) {
        setStatus(state, "eglCreateContext failed");
        return false;
    }
    if (state->contextPriorityExtensionAvailable) {
        EGLint grantedPriority = EGL_NONE;
        if (eglQueryContext(
                state->display,
                state->context,
                EGL_CONTEXT_PRIORITY_LEVEL_IMG,
                &grantedPriority
            ) == EGL_TRUE) {
            state->contextPriorityQuerySucceeded = true;
            state->contextPriorityGranted = grantedPriority;
            LOGI(
                "EGL record context priority requested=HIGH granted=%s fallback=%s",
                contextPriorityLabel(grantedPriority),
                state->contextPriorityFallbackUsed ? "true" : "false"
            );
        } else {
            const EGLint queryError = eglGetError();
            LOGW(
                "EGL context priority query failed error=0x%04x fallback=%s",
                queryError,
                state->contextPriorityFallbackUsed ? "true" : "false"
            );
        }
    } else {
        LOGI("EGL_IMG_context_priority unavailable; using ordinary ES3 context");
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
    state->niv99RgbCorrectionLocation =
        glGetUniformLocation(state->program, "uNiv99RgbCorrection");
    state->niv99ActiveConfidenceLocation =
        glGetUniformLocation(state->program, "uNiv99ActiveConfidence");
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
    state->ndrTargetPeakNitsLocation = glGetUniformLocation(state->program, "uNdrTargetPeakNits");
    state->ndrPqLutLocation = glGetUniformLocation(state->program, "uNdrPqLut");
    state->ndrPqLutReadyLocation = glGetUniformLocation(state->program, "uNdrPqLutReady");
    state->inputTransferLocation = glGetUniformLocation(state->program, "uInputTransfer");
    state->ndrWarmSkinRedTameLocation = glGetUniformLocation(state->program, "uNdrWarmSkinRedTame");
    state->encOutFlipLocation = glGetUniformLocation(state->program, "uEncOutFlip");
    state->mainLocExternal = fetchMainLoc(state->program);
    state->pregradedPqIdentityProgram = createPregradedPqIdentityProgram();
    if (state->pregradedPqIdentityProgram != 0) {
        state->pregradedPqTextureLocation =
            glGetUniformLocation(state->pregradedPqIdentityProgram, "uTexture");
        if (state->pregradedPqTextureLocation < 0) {
            LOGE("pregraded BT.2020/PQ identity program missing uTexture");
            glDeleteProgram(state->pregradedPqIdentityProgram);
            state->pregradedPqIdentityProgram = 0;
        }
    } else {
        LOGW("pregraded BT.2020/PQ identity program unavailable; strict path will reject");
    }
    (void)initializeGpuStageTiming(state);

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

    if (state->pregradedPqIdentityProgram != 0) {
        while (glGetError() != GL_NO_ERROR) {
            // Attribute any following allocation error to the dedicated identity texture.
        }
        glGenTextures(1, &state->pregradedPqSourceTexture);
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, state->pregradedPqSourceTexture);
        // Exact-size identity presentation must never interpolate neighbouring 10-bit code values.
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0);
        const GLenum identityTextureError = glGetError();
        if (state->pregradedPqSourceTexture == 0 || identityTextureError != GL_NO_ERROR) {
            LOGE(
                "pregraded BT.2020/PQ identity texture creation failed error=0x%04x",
                identityTextureError
            );
            if (state->pregradedPqSourceTexture != 0) {
                glDeleteTextures(1, &state->pregradedPqSourceTexture);
                state->pregradedPqSourceTexture = 0;
            }
            glDeleteProgram(state->pregradedPqIdentityProgram);
            state->pregradedPqIdentityProgram = 0;
            state->pregradedPqTextureLocation = -1;
        } else {
            LOGI("pregraded BT.2020/PQ one-draw identity path ready");
        }
    }

    eglMakeCurrent(state->display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    setStatus(state, "egl context ready");
    LOGI("EGL context ready version=%d.%d", major, minor);
    return true;
}

// Match the existing strict 55-buffer P010 reader, not the older 16-buffer PRIVATE reader.
// This only retains bounded EGL import handles; reader depth, GPU fences and pixels are unchanged.
constexpr size_t kEglImageCacheCapacity = camx::EglImageResidencyPolicy::kCapacity;

bool configureHistoryTexture(GLuint texture, int width, int height, bool fp16) {
    glBindTexture(GL_TEXTURE_2D, texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(
        GL_TEXTURE_2D,
        0,
        fp16 ? GL_RGBA16F : GL_RGB10_A2,
        width,
        height,
        0,
        GL_RGBA,
        fp16 ? GL_HALF_FLOAT : GL_UNSIGNED_INT_2_10_10_10_REV,
        nullptr
    );
    const GLenum error = glGetError();
    glBindTexture(GL_TEXTURE_2D, 0);
    return error == GL_NO_ERROR;
}

bool ensureTemporalHistoryTarget(
    OffscreenAhbEglEncoderState* state,
    int width,
    int height,
    bool fp16
) {
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
    if (state->historyWidth != width || state->historyHeight != height || state->historyFp16 != fp16) {
        if (!configureHistoryTexture(state->historyTexture, width, height, fp16) ||
            !configureHistoryTexture(state->historyWriteTexture, width, height, fp16)) {
            setStatus(state, fp16
                ? "RGBA16F temporal history texture allocation failed"
                : "RGB10_A2 temporal history texture allocation failed");
            state->historyValid = false;
            return false;
        }
        state->historyWidth = width;
        state->historyHeight = height;
        state->historyFp16 = fp16;
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
        message << (fp16 ? "RGBA16F" : "RGB10_A2")
                << " temporal history framebuffer incomplete status=0x"
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

// Every submit owns the EGL binding only for the duration of its JNI call. This prevents a codec
// surface from remaining current on the recording worker while lifecycle teardown runs on another
// thread. The destructor covers every failure return; the successful path calls unbind() explicitly
// so an unbind failure can be reported to Kotlin instead of being silently accepted.
class ScopedSubmitEglUnbind final {
public:
    explicit ScopedSubmitEglUnbind(OffscreenAhbEglEncoderState* state) : state_(state) {}

    ScopedSubmitEglUnbind(const ScopedSubmitEglUnbind&) = delete;
    ScopedSubmitEglUnbind& operator=(const ScopedSubmitEglUnbind&) = delete;

    ~ScopedSubmitEglUnbind() {
        if (!unbound_ && !unbind()) {
            LOGE(
                "eglMakeCurrent submit cleanup failed error=0x%04x",
                static_cast<unsigned int>(lastError_)
            );
        }
    }

    bool unbind() {
        if (unbound_) return true;
        if (state_ == nullptr || state_->display == EGL_NO_DISPLAY) {
            unbound_ = true;
            return true;
        }
        const EGLContext currentContext = eglGetCurrentContext();
        if (currentContext == EGL_NO_CONTEXT || currentContext != state_->context) {
            // The submit path did not make this bridge's context current on this thread. Never
            // disturb an unrelated EGL owner merely because it shares EGL_DEFAULT_DISPLAY.
            unbound_ = true;
            return true;
        }
        if (eglMakeCurrent(
                state_->display,
                EGL_NO_SURFACE,
                EGL_NO_SURFACE,
                EGL_NO_CONTEXT
            ) != EGL_TRUE) {
            lastError_ = eglGetError();
            return false;
        }
        unbound_ = true;
        lastError_ = EGL_SUCCESS;
        return true;
    }

    EGLint lastError() const { return lastError_; }

private:
    OffscreenAhbEglEncoderState* state_ = nullptr;
    bool unbound_ = false;
    EGLint lastError_ = EGL_SUCCESS;
};

// Transactional surface retirement. EGLSurface, ANativeWindow, the session cache, and all bound
// diagnostics remain intact until both the thread-local unbind and eglDestroySurface are proven.
// Callers can therefore quarantine and retry a failed detach without losing the only ownership
// evidence for a still-live codec surface.
bool destroySurface(OffscreenAhbEglEncoderState* state) {
    if (state == nullptr) return true;
    const size_t outstandingInputReads = inputReadFenceOutstandingCount(state);
    if (outstandingInputReads != 0u) {
        std::ostringstream message;
        message << "encoder surface retains " << outstandingInputReads
                << " unacknowledged input-read fence receipt(s)";
        setStatus(state, message.str());
        LOGE("%s", message.str().c_str());
        return false;
    }
    const bool hasSurface = state->encoderSurface != EGL_NO_SURFACE;
    if (state->display != EGL_NO_DISPLAY) {
        const EGLContext currentContext = eglGetCurrentContext();
        if (currentContext == state->context) {
            if (eglMakeCurrent(
                    state->display,
                    EGL_NO_SURFACE,
                    EGL_NO_SURFACE,
                    EGL_NO_CONTEXT
                ) != EGL_TRUE) {
                const EGLint error = eglGetError();
                std::ostringstream message;
                message << "encoder surface unbind failed error=0x" << std::hex << error;
                setStatus(state, message.str());
                LOGE("%s", message.str().c_str());
                return false;
            }
        }
    } else if (hasSurface) {
        setStatus(state, "encoder surface destroy failed: EGL display unavailable");
        LOGE("encoder surface destroy failed: EGL display unavailable");
        return false;
    }
    if (hasSurface && eglDestroySurface(state->display, state->encoderSurface) != EGL_TRUE) {
        const EGLint error = eglGetError();
        std::ostringstream message;
        message << "encoder surface destroy failed error=0x" << std::hex << error;
        setStatus(state, message.str());
        LOGE("%s", message.str().c_str());
        return false;
    }

    // #5: the AHB pool is bound to the retired session. Clear it only after EGL has released the
    // surface; a failed retirement must retain the complete session for a safe retry.
    clearEglImageCache(state);
    state->encoderSurface = EGL_NO_SURFACE;
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
    // A completed-but-not-yet-readable query belongs to the retired surface epoch. Keep it pending
    // until AVAILABLE is true (query-name reuse before completion is unsafe), but force its eventual
    // result to be discarded before the next session can record timing statistics.
    for (size_t index = 0; index < kGpuStageCount; ++index) {
        if (state->gpuStageQueryPending[index]) state->gpuStageQueryDiscard[index] = true;
    }
    for (DurationStats& stats : state->gpuStageStats) stats.reset();
    state->gpuStageDisjointBatches = 0;
    state->gpuStageUnavailablePolls = 0;
    state->decodeHostStats.reset();
    state->gradeHostStats.reset();
    state->historyHostStats.reset();
    state->finishStats.reset();
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
    state->decodeTripleBufferedFrames = 0;
    state->decodeOnceFrames = 0;
    state->externalPathFrames = 0;
    state->sdrInvariantSpecializedFrames = 0;
    state->sdrInvariantSpecializationFallbackFrames = 0;
    state->sdrNiv99SpecializedFrames = 0;
    state->sdrNiv99SpecializationFallbackFrames = 0;
    state->sdrCreativeLutSpecializedFrames = 0;
    state->sdrCreativeLutSpecializationFallbackFrames = 0;
    state->sdrCreativeLutNiv99SpecializedFrames = 0;
    state->sdrCreativeLutNiv99SpecializationFallbackFrames = 0;
    state->neoGatherGridFrames = 0;
    state->neoGatherGridFallbackFrames = 0;
    state->neoConstantLiftFrames = 0;
    state->niv99IdentityWhiteBalanceElisionFrames = 0;
    state->sourceHistoryFrames = 0;
    state->sensorLinearSpecializedFrames = 0;
    state->sensorLinearNdr10SpecializedFrames = 0;
    state->sensorLinearNivukxSpecializedFrames = 0;
    state->sensorLinearSpecializationFallbackFrames = 0;
    state->explicitYuvDecodeFrames = 0;
    state->eglImageCacheHits = 0;
    state->eglImageCacheMisses = 0;
    state->eglImageCacheEvictions = 0;
    state->eglImageCacheIdFailures = 0;
    state->recorderPrewarmAttempts = 0;
    state->recorderPrewarmSuccesses = 0;
    state->recorderPrewarmFailures = 0;
    state->recorderPrewarmTotalUs = 0;
    state->recorderPrewarmMaxUs = 0;
    state->recorderPrewarmBufferIds.fill(0u);
    state->cameraP010SdrPrewarmAttempts = 0;
    state->cameraP010SdrPrewarmSuccesses = 0;
    state->cameraP010SdrPrewarmFailures = 0;
    state->cameraP010SdrPrewarmTotalUs = 0;
    state->cameraP010SdrPrewarmMaxUs = 0;
    state->cameraP010SdrPrewarmLastBufferId = 0;
    state->cameraP010SdrPrewarmIdentityShaderDraws = 0;
    state->cameraP010SdrPrewarmNiv99ShaderDraws = 0;
    state->cameraP010SdrPrewarmBufferIds.clear();
    state->codecReadinessAttempts = 0;
    state->codecReadinessSuccesses = 0;
    state->codecReadinessFailures = 0;
    state->codecReadinessTotalUs = 0;
    state->codecReadinessMaxUs = 0;
    state->codecReadinessPresentationTimeNs = 0;
    state->presentationTimelineRegressions = 0u;
    state->inputReadFenceCreated = 0u;
    state->inputReadFenceSignaled = 0u;
    state->inputReadFencePendingPolls = 0u;
    state->inputReadFenceSaturatedWaits = 0u;
    state->inputReadFenceSaturatedWaitTimeouts = 0u;
    state->inputReadFenceSyncFallbacks = 0u;
    state->inputReadFenceFailures = 0u;
    state->inputReadFenceMaxOutstanding = 0u;
    state->inputReadDecodeBoundaryFrames = 0u;
    state->inputReadFinalBoundaryFrames = 0u;
    state->pregradedPqIdentityFrames = 0u;
    state->pregradedPqRejectedFrames = 0u;
    state->pregradedPqLastProfileGeneration = 0u;
    state->pregradedPqLastProfileRevision = 0u;
    state->pregradedPqLastGradeFingerprint = 0u;
    state->pregradedPqLastSlotTokens.fill(0u);
    state->pregradedPqSlotBufferIds.fill(0u);
    state->submittedFrames = 0;
    state->failedFrames = 0;
    state->niv99PacketsSeen = 0;
    state->niv99PacketsAccepted = 0;
    state->niv99PacketsRejected = 0;
    state->niv99PixelsApplied = 0;
    state->niv99CameraIdHash = 0;
    state->niv99SessionGeneration = -1;
    state->niv99FrameNumber = -1;
    state->niv99SensorTimestampNs = -1;
    state->niv99HasAcceptedPacket = false;
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
    state->historyFp16 = false;
    state->decodeHistoryValid = false;
    resetSourceEvidence(state);
    return true;
}

bool destroyState(OffscreenAhbEglEncoderState* state) {
    if (state == nullptr)
        return true;
    const bool ownsGlObjects =
        state->program != 0 || state->pregradedPqIdentityProgram != 0 ||
        state->mainProgram2d != 0 || state->decodeProgram != 0 ||
        state->mainProgramExplicitYuv != 0 || state->decodeProgramExplicitYuv != 0 ||
        state->sensorLinearProgramExternal != 0 || state->sensorLinearProgram2d != 0 ||
        state->sensorLinearNivukxProgramExternal != 0 || state->sensorLinearNivukxProgram2d != 0 ||
        state->vertexBuffer != 0 || state->sourceTexture != 0 ||
        state->pregradedPqSourceTexture != 0 || state->lutTexture != 0 ||
        state->defaultLut3d != 0 || state->ndrPqLutNdr10 != 0 || state->ndrPqLutNivukx != 0 ||
        state->ndrSensorTonePqLut != 0 || state->historyTexture != 0 ||
        state->historyWriteTexture != 0 || state->historyFbo != 0 || state->defaultHistory2d != 0 ||
        state->decodeFbo != 0 || state->decodeTexture != 0 || state->decodeHistoryTexture != 0 ||
        state->decodeSpareTexture != 0 ||
        std::any_of(state->gpuStageQueries.begin(), state->gpuStageQueries.end(),
                    [](GLuint query) { return query != 0u; });
    if (!state->glResourcesDestroyed && state->context == EGL_NO_CONTEXT) {
        if (ownsGlObjects) {
            setStatus(state, "final close cannot retire live GL objects without "
                             "their EGL context");
            LOGE("final close cannot retire live GL objects without their EGL "
                 "context");
            return false;
        }
        // A failed pre-context capability probe may legitimately leave only an
        // initialized display. There are no GL objects to delete; continue to
        // transactional surface/display retirement.
        state->glResourcesDestroyed = true;
    }
    if (!state->glResourcesDestroyed && state->display != EGL_NO_DISPLAY &&
        state->context != EGL_NO_CONTEXT) {
        const EGLSurface teardownSurface =
            state->encoderSurface != EGL_NO_SURFACE ? state->encoderSurface : EGL_NO_SURFACE;
        if (eglMakeCurrent(state->display, teardownSurface, teardownSurface, state->context) !=
            EGL_TRUE) {
            const EGLint error = eglGetError();
            std::ostringstream message;
            message << "final close could not acquire EGL context error=0x" << std::hex << error;
            setStatus(state, message.str());
            LOGE("%s", message.str().c_str());
            return false;
        }
    } else if (!state->glResourcesDestroyed && state->display == EGL_NO_DISPLAY &&
               state->context != EGL_NO_CONTEXT) {
        setStatus(state, "final close found an EGL context without its display owner");
        LOGE("final close found an EGL context without its display owner");
        return false;
    }
    // Full bridge destruction is the final quarantine boundary. Drain every GL
    // read before cached EGLImages, textures, context, or the Vulkan-owned AHB
    // identities can be destroyed.
    drainAndClearInputReadFenceReceipts(state);
    const size_t outstandingInputReads = inputReadFenceOutstandingCount(state);
    if (outstandingInputReads != 0u) {
        std::ostringstream message;
        message << "final close retains " << outstandingInputReads
                << " input-read fence receipt(s) for retry";
        setStatus(state, message.str());
        LOGE("%s", message.str().c_str());
        return false;
    }
    if (!state->glResourcesDestroyed) {
        if (state->glDeleteQueriesEXT != nullptr &&
            std::any_of(state->gpuStageQueries.begin(), state->gpuStageQueries.end(),
                        [](GLuint query) { return query != 0u; })) {
            state->glDeleteQueriesEXT(static_cast<GLsizei>(state->gpuStageQueries.size()),
                                      state->gpuStageQueries.data());
            state->gpuStageQueries.fill(0u);
            state->gpuStageQueryPending.fill(false);
            state->gpuStageQueryDiscard.fill(false);
        }
        state->gpuStageTimingAvailable = false;
        if (state->sourceTexture != 0) {
            glDeleteTextures(1, &state->sourceTexture);
            state->sourceTexture = 0;
        }
        if (state->pregradedPqSourceTexture != 0) {
            glDeleteTextures(1, &state->pregradedPqSourceTexture);
            state->pregradedPqSourceTexture = 0;
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
        if (state->ndrSensorTonePqLut != 0) {
            glDeleteTextures(1, &state->ndrSensorTonePqLut);
            state->ndrSensorTonePqLut = 0;
        }
        state->ndrSensorTonePqLutReady = false;
        state->ndrSensorTonePqLutKeyValid = false;
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
        state->historyFp16 = false;
        if (state->vertexBuffer != 0) {
            glDeleteBuffers(1, &state->vertexBuffer);
            state->vertexBuffer = 0;
        }
        if (state->program != 0) {
            glDeleteProgram(state->program);
            state->program = 0;
        }
        if (state->pregradedPqIdentityProgram != 0) {
            glDeleteProgram(state->pregradedPqIdentityProgram);
            state->pregradedPqIdentityProgram = 0;
        }
        state->pregradedPqTextureLocation = -1;
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
        if (state->decodeSpareTexture != 0) {
            glDeleteTextures(1, &state->decodeSpareTexture);
            state->decodeSpareTexture = 0;
        }
        state->decodeTexWidth = 0;
        state->decodeTexHeight = 0;
        state->decodeFp16 = false;
        state->decodeHistoryValid = false;
        state->decodeTripleBufferReady = false;
        if (state->mainProgram2d != 0) {
            glDeleteProgram(state->mainProgram2d);
            state->mainProgram2d = 0;
        }
        if (state->sdrInvariantProgramExternal != 0) {
            glDeleteProgram(state->sdrInvariantProgramExternal);
            state->sdrInvariantProgramExternal = 0;
        }
        if (state->sdrInvariantProgram2d != 0) {
            glDeleteProgram(state->sdrInvariantProgram2d);
            state->sdrInvariantProgram2d = 0;
        }
        if (state->sdrNiv99ProgramExternal != 0) {
            glDeleteProgram(state->sdrNiv99ProgramExternal);
            state->sdrNiv99ProgramExternal = 0;
        }
        if (state->sdrNiv99Program2d != 0) {
            glDeleteProgram(state->sdrNiv99Program2d);
            state->sdrNiv99Program2d = 0;
        }
        if (state->sdrCreativeLutProgramExternal != 0) {
            glDeleteProgram(state->sdrCreativeLutProgramExternal);
            state->sdrCreativeLutProgramExternal = 0;
        }
        if (state->sdrCreativeLutProgram2d != 0) {
            glDeleteProgram(state->sdrCreativeLutProgram2d);
            state->sdrCreativeLutProgram2d = 0;
        }
        if (state->sdrCreativeLutNiv99ProgramExternal != 0) {
            glDeleteProgram(state->sdrCreativeLutNiv99ProgramExternal);
            state->sdrCreativeLutNiv99ProgramExternal = 0;
        }
        if (state->sdrCreativeLutNiv99Program2d != 0) {
            glDeleteProgram(state->sdrCreativeLutNiv99Program2d);
            state->sdrCreativeLutNiv99Program2d = 0;
        }
        if (state->sdrCreativeLutGatherProgram2d != 0) {
            glDeleteProgram(state->sdrCreativeLutGatherProgram2d);
            state->sdrCreativeLutGatherProgram2d = 0;
        }
        if (state->sdrCreativeLutNiv99GatherProgram2d != 0) {
            glDeleteProgram(state->sdrCreativeLutNiv99GatherProgram2d);
            state->sdrCreativeLutNiv99GatherProgram2d = 0;
        }
        if (state->sensorLinearProgramExternal != 0) {
            glDeleteProgram(state->sensorLinearProgramExternal);
            state->sensorLinearProgramExternal = 0;
        }
        if (state->sensorLinearProgram2d != 0) {
            glDeleteProgram(state->sensorLinearProgram2d);
            state->sensorLinearProgram2d = 0;
        }
        if (state->sensorLinearNivukxProgramExternal != 0) {
            glDeleteProgram(state->sensorLinearNivukxProgramExternal);
            state->sensorLinearNivukxProgramExternal = 0;
        }
        if (state->sensorLinearNivukxProgram2d != 0) {
            glDeleteProgram(state->sensorLinearNivukxProgram2d);
            state->sensorLinearNivukxProgram2d = 0;
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
        state->glResourcesDestroyed = true;
    }
    if (!destroySurface(state)) {
        LOGE("Offscreen encoder surface remained live; final close ownership "
             "quarantined");
        return false;
    }
    if (state->display != EGL_NO_DISPLAY && state->context != EGL_NO_CONTEXT) {
        if (eglDestroyContext(state->display, state->context) != EGL_TRUE) {
            const EGLint error = eglGetError();
            std::ostringstream message;
            message << "final close EGL context destroy failed error=0x" << std::hex << error;
            setStatus(state, message.str());
            LOGE("%s", message.str().c_str());
            return false;
        }
        state->context = EGL_NO_CONTEXT;
    } else if (state->context != EGL_NO_CONTEXT) {
        setStatus(state, "final close cannot destroy EGL context without its display");
        LOGE("final close cannot destroy EGL context without its display");
        return false;
    }
    if (state->display != EGL_NO_DISPLAY) {
        if (eglTerminate(state->display) != EGL_TRUE) {
            const EGLint error = eglGetError();
            std::ostringstream message;
            message << "final close EGL display terminate failed error=0x" << std::hex << error;
            setStatus(state, message.str());
            LOGE("%s", message.str().c_str());
            return false;
        }
        state->display = EGL_NO_DISPLAY;
    }
    return true;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_neoproc_neoproccamx_rendering_OffscreenAhbEglEncoderBridge_nativeCreate(
    JNIEnv*,
    jobject
) {
    return reinterpret_cast<jlong>(new OffscreenAhbEglEncoderState());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_rendering_OffscreenAhbEglEncoderBridge_nativeDestroy(
    JNIEnv*,
    jobject,
    jlong handle
) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return JNI_TRUE;
    bool destroyed = false;
    {
        // Destruction is serialized with every JNI entry point that uses this state. The
        // lock must be released while the state (and therefore its mutex) is still alive.
        std::lock_guard<std::mutex> lock(state->mutex);
        destroyed = destroyState(state);
    }
    if (destroyed) {
        delete state;
        return JNI_TRUE;
    }
    // Java has already closed ordinary operation admission, but retains this exact handle solely
    // for a later destroy retry. Never delete the mutex/state or abandon its ANativeWindow here.
    LOGE("Native offscreen bridge final close rejected; exact owner retained for retry");
    return JNI_FALSE;
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

extern "C" JNIEXPORT void JNICALL
Java_com_neoproc_neoproccamx_rendering_OffscreenAhbEglEncoderBridge_nativeSetNdrMeasuredCorrection(
    JNIEnv* /*env*/,
    jobject,
    jlong handle,
    jboolean enabled,
    jfloat redScale,
    jfloat greenScale,
    jfloat blueScale,
    jfloat highlightRolloffBias,
    jfloat shadowLiftBias,
    jfloat saturationScale,
    jfloat skinHueCorrectionDegrees,
    jfloat skyHueCorrectionDegrees,
    jfloat sensorDiffuseScene,
    jfloat signalGamma,
    jfloat toeWidth,
    jfloat toeAmount,
    jfloat highlightDesat,
    jfloat shadowMidtoneLift,
    jfloat midtoneContrast,
    jfloat chromaBoost,
    jfloat skyChromaBonus,
    jfloat localDetailStrength,
    jfloat localDetailLowerStops,
    jfloat localDetailUpperStops
) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return;
    std::lock_guard<std::mutex> lock(state->mutex);
    auto finiteOr = [](float value, float fallback) {
        return std::isfinite(value) ? value : fallback;
    };
    const float active = enabled == JNI_TRUE ? 1.0f : 0.0f;
    state->ndrMeasuredRgbScale[0] =
        std::clamp(finiteOr(redScale, 1.0f), 0.85f, 1.15f);
    state->ndrMeasuredRgbScale[1] =
        std::clamp(finiteOr(greenScale, 1.0f), 0.85f, 1.15f);
    state->ndrMeasuredRgbScale[2] =
        std::clamp(finiteOr(blueScale, 1.0f), 0.85f, 1.15f);
    state->ndrMeasuredRgbScale[3] = active;
    state->ndrMeasuredTone[0] =
        std::clamp(finiteOr(highlightRolloffBias, 0.0f), 0.0f, 0.12f);
    state->ndrMeasuredTone[1] =
        std::clamp(finiteOr(shadowLiftBias, 0.0f), -0.08f, 0.08f);
    state->ndrMeasuredTone[2] =
        std::clamp(finiteOr(saturationScale, 1.0f), 0.85f, 1.15f);
    state->ndrMeasuredTone[3] = active;
    state->ndrMeasuredHue[0] =
        std::clamp(finiteOr(skinHueCorrectionDegrees, 0.0f), -12.0f, 12.0f);
    state->ndrMeasuredHue[1] =
        std::clamp(finiteOr(skyHueCorrectionDegrees, 0.0f), -12.0f, 12.0f);
    state->ndrToneProfile[0] =
        std::clamp(finiteOr(sensorDiffuseScene, 0.180f), 0.02f, 0.30f);
    state->ndrToneProfile[1] =
        std::clamp(finiteOr(signalGamma, 0.859937f), 0.10f, 1.0f);
    state->ndrToneProfile[2] =
        std::clamp(finiteOr(toeWidth, 0.238882f), 0.001f, 0.90f);
    state->ndrToneProfile[3] =
        std::clamp(finiteOr(toeAmount, 0.024780f), 0.0f, 0.20f);
    state->ndrCharacterProfile[0] =
        std::clamp(finiteOr(highlightDesat, 0.149718f), 0.0f, 0.50f);
    state->ndrCharacterProfile[1] =
        std::clamp(finiteOr(shadowMidtoneLift, 0.167837f), 0.0f, 0.35f);
    state->ndrCharacterProfile[2] =
        std::clamp(finiteOr(midtoneContrast, 0.100943f), 0.0f, 0.30f);
    state->ndrCharacterProfile[3] =
        std::clamp(finiteOr(chromaBoost, 0.038361f), 0.0f, 0.25f);
    state->ndrDetailProfile[0] =
        std::clamp(finiteOr(skyChromaBonus, 0.062392f), 0.0f, 0.08f);
    state->ndrDetailProfile[1] =
        std::clamp(finiteOr(localDetailStrength, 0.400000f), 0.0f, 0.75f);
    state->ndrDetailProfile[2] =
        std::clamp(finiteOr(localDetailLowerStops, -0.060000f), -0.20f, 0.0f);
    state->ndrDetailProfile[3] =
        std::clamp(finiteOr(localDetailUpperStops, 0.085000f), 0.0f, 0.20f);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_rendering_OffscreenAhbEglEncoderBridge_nativeResetTemporalHistory(
    JNIEnv*,
    jobject,
    jlong handle
) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(state->mutex);

    // Presentation epochs are independent even when they reuse the same EGL surface and dimensions.
    // Invalidate both history variants without destroying their reusable textures or touching any
    // surface/profile/grade state. The next submitted frame therefore seeds fresh history and cannot
    // blend against a frame retained by the preceding epoch.
    state->historyValid = false;
    state->decodeHistoryValid = false;
    return JNI_TRUE;
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

    if (!destroySurface(state)) {
        // Keep the previous EGLSurface/ANativeWindow and its complete evidence state. Kotlin must
        // quarantine that still-owned surface instead of assuming a replacement or detach occurred.
        return JNI_FALSE;
    }
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
    } else if (requestedDeliveryMode == "PROJECT_PQ_TEXTURE") {
        // Internal RGB10_A2 transport: numeric PQ code values, no EGL/platform presentation tag.
        // The downstream SurfaceTexture frame is admitted only with the matching project proof.
        colorSpaceOptions.push_back({
            EGL_NONE,
            "UNTAGGED_PROJECT_PQ_TEXTURE",
            "PROJECT_PQ_TEXTURE",
            "PROJECT_PQ_TEXTURE"
        });
    } else {
        colorSpaceOptions.push_back({ EGL_NONE, "DEFAULT", "DEFAULT", "DEFAULT" });
        colorSpaceOptions.push_back({ EGL_GL_COLORSPACE_SRGB_KHR, "SRGB", "SDR", "SRGB_NONLINEAR" });
    }
    EGLSurface eglSurface = EGL_NO_SURFACE;
    EGLint chosenColorSpace = EGL_NONE;
    EGLint lastCreateSurfaceError = EGL_SUCCESS;
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
        lastCreateSurfaceError = error;
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
        std::ostringstream status;
        status << "eglCreateWindowSurface failed for requested delivery "
               << requestedDeliveryMode
               << " error=0x" << std::hex << lastCreateSurfaceError
               << (lastCreateSurfaceError == EGL_BAD_ALLOC
                       ? " classification=transient_ownership_or_pressure"
                       : " classification=surface_contract_rejection");
        setStatus(state, status.str());
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
    const bool projectTextureContractProven =
        requestedDeliveryMode != "PROJECT_PQ_TEXTURE" ||
        (chosenDeliveryMode == "PROJECT_PQ_TEXTURE" &&
         verifiedDataSpace == ADATASPACE_UNKNOWN && verifiedDataSpaceMatches);
    state->deliveryCompatible =
        state->redBits == 10 &&
        state->greenBits == 10 &&
        state->blueBits == 10 &&
        state->alphaBits == 2 &&
        state->recordable == EGL_TRUE &&
        projectTextureContractProven;
    if (!state->deliveryCompatible) {
        if (!destroySurface(state)) {
            // destroySurface retains the rejected candidate and its exact EGL failure status so
            // Kotlin can quarantine/retry it without losing either ownership or root-cause proof.
            return JNI_FALSE;
        }
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

// Device-qualification selector between quality-equivalent cardinal sampling implementations.
// The Kotlin bridge invokes this only while configuring a new encoder surface, never mid-frame.
extern "C" JNIEXPORT void JNICALL
Java_com_neoproc_neoproccamx_rendering_OffscreenAhbEglEncoderBridge_nativeSetGatherGridEnabled(
    JNIEnv*,
    jobject,
    jlong handle,
    jboolean enabled
) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return;
    std::lock_guard<std::mutex> lock(state->mutex);
    state->neoGatherGridEnabled = enabled == JNI_TRUE;
    LOGI(
        "Neo GatherGrid selection enabled=%s capable=%s identityReady=%s niv99Ready=%s",
        state->neoGatherGridEnabled ? "true" : "false",
        state->neoGatherGridCapable ? "true" : "false",
        state->sdrCreativeLutGatherProgram2d != 0 ? "true" : "false",
        state->sdrCreativeLutNiv99GatherProgram2d != 0 ? "true" : "false"
    );
}

// Diagnostic-only GPU timing toggle. Default OFF in Kotlin: production frames never execute timer
// queries. Any capability/call fault permanently disables sampling for this bridge while leaving
// rendering, synchronization, quality, and encoder submission untouched.
extern "C" JNIEXPORT void JNICALL
Java_com_neoproc_neoproccamx_rendering_OffscreenAhbEglEncoderBridge_nativeSetGpuStageTimingEnabled(
    JNIEnv*,
    jobject,
    jlong handle,
    jboolean enabled
) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return;
    std::lock_guard<std::mutex> lock(state->mutex);
    const bool requested = enabled == JNI_TRUE;
    state->gpuStageTimingSamplingEnabled =
        requested && state->gpuStageTimingAvailable && !state->gpuStageTimingFaulted;
    if (!state->gpuStageTimingSamplingEnabled) {
        for (size_t index = 0; index < kGpuStageCount; ++index) {
            if (state->gpuStageQueryPending[index]) state->gpuStageQueryDiscard[index] = true;
        }
    }
    LOGI(
        "GPU stage timing sampling requested=%s enabled=%s available=%s faulted=%s",
        requested ? "true" : "false",
        state->gpuStageTimingSamplingEnabled ? "true" : "false",
        state->gpuStageTimingAvailable ? "true" : "false",
        state->gpuStageTimingFaulted ? "true" : "false"
    );
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

// Pre-ingress recorder warmup for the two persistent Vulkan export identities. This deliberately
// stops after the exact external-OES -> decode-intermediate pass used by the live two-pass recorder:
// there is no default-framebuffer draw, presentation timestamp, eglSwapBuffers, encoder submission,
// temporal-history publication, or regular submitted/failed-frame counter mutation. glFinish is a
// start-boundary ownership barrier and also forces the vendor driver to pay lazy import/sample costs
// before Camera2 RAW ImageReader admission opens.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_rendering_OffscreenAhbEglEncoderBridge_nativePrewarmProjectOwnedRawTransport(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject hardwareBufferObject,
    jint width,
    jint height,
    jint format,
    jint slot
) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(state->mutex);
    ScopedSubmitEglUnbind submitEglUnbind(state);
    const auto started = std::chrono::steady_clock::now();
    ++state->recorderPrewarmAttempts;
    const auto elapsedUs = [&]() -> uint64_t {
        return static_cast<uint64_t>(
            std::chrono::duration_cast<std::chrono::microseconds>(
                std::chrono::steady_clock::now() - started
            ).count());
    };
    bool insertedCacheEntry = false;
    uint64_t insertedCacheBufferId = 0u;
    bool decodeStateMutated = false;
    const auto rollback = [&]() {
        if (insertedCacheEntry) {
            const auto inserted = state->eglImageCache.find(insertedCacheBufferId);
            if (inserted != state->eglImageCache.end()) {
                if (inserted->second.image != EGL_NO_IMAGE_KHR &&
                    state->eglDestroyImageKHR != nullptr && state->display != EGL_NO_DISPLAY) {
                    state->eglDestroyImageKHR(state->display, inserted->second.image);
                }
                state->eglImageCache.erase(inserted);
            }
            state->eglImageCacheOrder.erase(
                std::remove(
                    state->eglImageCacheOrder.begin(),
                    state->eglImageCacheOrder.end(),
                    insertedCacheBufferId
                ),
                state->eglImageCacheOrder.end()
            );
            insertedCacheEntry = false;
        }
        if (decodeStateMutated) {
            if (eglGetCurrentContext() == state->context) {
                glBindFramebuffer(GL_FRAMEBUFFER, 0);
                glBindTexture(GL_TEXTURE_2D, 0);
                glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0);
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
                if (state->decodeSpareTexture != 0) {
                    glDeleteTextures(1, &state->decodeSpareTexture);
                    state->decodeSpareTexture = 0;
                }
                while (glGetError() != GL_NO_ERROR) {
                    // A failed warmup is not allowed to contaminate the first live submission.
                }
            }
            state->decodeTexWidth = 0;
            state->decodeTexHeight = 0;
            state->decodeFp16 = false;
            state->decodeHistoryValid = false;
            state->decodeTripleBufferReady = false;
            decodeStateMutated = false;
        }
    };
    const auto fail = [&](const std::string& reason) -> jboolean {
        rollback();
        const uint64_t durationUs = elapsedUs();
        ++state->recorderPrewarmFailures;
        state->recorderPrewarmTotalUs += durationUs;
        state->recorderPrewarmMaxUs = std::max(state->recorderPrewarmMaxUs, durationUs);
        setStatus(state, std::string("recorder AHB/EGL prewarm failed: ") + reason);
        LOGW(
            "RAW_RECORD_AHB_EGL_PREWARM slot=%d success=false reason=%s elapsedUs=%llu",
            slot,
            reason.c_str(),
            static_cast<unsigned long long>(durationUs));
        return JNI_FALSE;
    };

    if (env == nullptr || hardwareBufferObject == nullptr || width <= 0 || height <= 0 ||
        slot < 0 || slot >= 2) {
        return fail("invalid arguments");
    }
    if (!ensureEglReady(state) || state->encoderSurface == EGL_NO_SURFACE ||
        !state->deliveryCompatible) {
        return fail("encoder EGL surface unavailable");
    }
    // Stable identity caching and decode-once are required for this optimization. Other devices keep
    // the existing per-frame path unchanged; Kotlin treats this false result as a compatibility no-op.
    if (!state->eglImageCacheEnabled || !state->eglImageCacheStableIds ||
        state->ahardwareBufferGetId == nullptr || !state->decodeOnceEnabled ||
        state->decodeProgram == 0 || state->mainProgram2d == 0) {
        return fail("stable cache/decode-once unavailable");
    }
    if (state->surfaceWidth != width || state->surfaceHeight != height) {
        return fail("source and encoder extents differ");
    }

    AHardwareBuffer* hardwareBuffer =
        AHardwareBuffer_fromHardwareBuffer(env, hardwareBufferObject);
    if (hardwareBuffer == nullptr) return fail("AHardwareBuffer_fromHardwareBuffer failed");
    AHardwareBuffer_acquire(hardwareBuffer);
    std::unique_ptr<AHardwareBuffer, decltype(&AHardwareBuffer_release)> scopedHardwareBuffer(
        hardwareBuffer,
        &AHardwareBuffer_release);

    AHardwareBuffer_Desc description{};
    AHardwareBuffer_describe(hardwareBuffer, &description);
    const bool sourceIsFp16 =
        description.format == AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT;
    const bool sourceIsSceneLog10 =
        description.format == AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM;
    if ((!sourceIsFp16 && !sourceIsSceneLog10) ||
        description.format != static_cast<uint32_t>(format) ||
        description.width != static_cast<uint32_t>(width) ||
        description.height != static_cast<uint32_t>(height)) {
        return fail("project-owned transport format/extent mismatch");
    }

    if (eglMakeCurrent(
            state->display,
            state->encoderSurface,
            state->encoderSurface,
            state->context
        ) != EGL_TRUE) {
        return fail("eglMakeCurrent prewarm failed");
    }
    while (glGetError() != GL_NO_ERROR) {
        // Clear setup-era errors so the prewarm draw has an attributable failure signal.
    }

    EGLClientBuffer clientBuffer = state->eglGetNativeClientBufferANDROID(hardwareBuffer);
    if (clientBuffer == nullptr) return fail("eglGetNativeClientBufferANDROID failed");
    uint64_t bufferId = 0u;
    if (state->ahardwareBufferGetId(hardwareBuffer, &bufferId) != 0 || bufferId == 0u) {
        ++state->eglImageCacheIdFailures;
        return fail("stable AHardwareBuffer id unavailable");
    }

    EGLImageKHR eglImage = EGL_NO_IMAGE_KHR;
    const auto cached = state->eglImageCache.find(bufferId);
    if (cached != state->eglImageCache.end()) {
        eglImage = cached->second.image;
        ++state->eglImageCacheHits;
    } else {
        ++state->eglImageCacheMisses;
        const EGLint imageAttrs[] = {
            EGL_IMAGE_PRESERVED_KHR, EGL_TRUE,
            EGL_NONE
        };
        eglImage = state->eglCreateImageKHR(
            state->display,
            EGL_NO_CONTEXT,
            EGL_NATIVE_BUFFER_ANDROID,
            clientBuffer,
            imageAttrs);
        if (eglImage == EGL_NO_IMAGE_KHR) return fail("eglCreateImageKHR failed");
        state->eglImageCache.emplace(bufferId, EglImageCacheEntry{eglImage});
        state->eglImageCacheOrder.push_back(bufferId);
        insertedCacheEntry = true;
        insertedCacheBufferId = bufferId;
    }

    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, state->sourceTexture);
    state->glEGLImageTargetTexture2DOES(GL_TEXTURE_EXTERNAL_OES, eglImage);

    decodeStateMutated = true;
    if (state->decodeTexture == 0) glGenTextures(1, &state->decodeTexture);
    if (state->decodeHistoryTexture == 0) glGenTextures(1, &state->decodeHistoryTexture);
    if (state->decodeSpareTexture == 0) glGenTextures(1, &state->decodeSpareTexture);
    if (state->decodeTexture == 0 || state->decodeHistoryTexture == 0) {
        return fail("decode texture allocation failed");
    }
    if (state->decodeTexWidth != width || state->decodeTexHeight != height ||
        state->decodeFp16 != sourceIsFp16) {
        const bool decodeReady = configureHistoryTexture(
            state->decodeTexture,
            width,
            height,
            sourceIsFp16);
        const bool historyReady = configureHistoryTexture(
            state->decodeHistoryTexture,
            width,
            height,
            sourceIsFp16);
        const bool spareReady = state->decodeSpareTexture != 0 && configureHistoryTexture(
            state->decodeSpareTexture,
            width,
            height,
            sourceIsFp16);
        if (!decodeReady || !historyReady) {
            state->decodeTexWidth = 0;
            state->decodeTexHeight = 0;
            state->decodeHistoryValid = false;
            return fail(sourceIsFp16
                ? "RGBA16F decode intermediate allocation failed"
                : "RGB10_A2 decode intermediate allocation failed");
        }
        state->decodeTexWidth = width;
        state->decodeTexHeight = height;
        state->decodeFp16 = sourceIsFp16;
        state->decodeTripleBufferReady = spareReady;
        if (!spareReady && state->decodeSpareTexture != 0) {
            glDeleteTextures(1, &state->decodeSpareTexture);
            state->decodeSpareTexture = 0;
            while (glGetError() != GL_NO_ERROR) {
                // Optional scheduling resource failed; retain the proven two-texture quality path.
            }
        }
    }
    if (state->decodeFbo == 0) glGenFramebuffers(1, &state->decodeFbo);
    if (state->decodeFbo == 0) return fail("decode framebuffer allocation failed");

    glBindFramebuffer(GL_FRAMEBUFFER, state->decodeFbo);
    glFramebufferTexture2D(
        GL_FRAMEBUFFER,
        GL_COLOR_ATTACHMENT0,
        GL_TEXTURE_2D,
        state->decodeTexture,
        0);
    const GLenum framebufferStatus = glCheckFramebufferStatus(GL_FRAMEBUFFER);
    if (framebufferStatus != GL_FRAMEBUFFER_COMPLETE) {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        state->decodeHistoryValid = false;
        std::ostringstream message;
        message << "decode framebuffer incomplete status=0x" << std::hex << framebufferStatus;
        return fail(message.str());
    }

    glViewport(0, 0, width, height);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_BLEND);
    glUseProgram(state->decodeProgram);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, state->sourceTexture);
    glUniform1i(state->decodeTexLoc, 0);
    glUniform2f(
        state->decodeSourceTexelLoc,
        1.0f / static_cast<float>(width),
        1.0f / static_cast<float>(height));
    glUniform2f(state->decodeMinificationScaleLoc, 1.0f, 1.0f);
    glUniform1i(state->decodePreserveExtendedRangeLoc, sourceIsFp16 ? 1 : 0);
    glBindBuffer(GL_ARRAY_BUFFER, state->vertexBuffer);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(
        0,
        2,
        GL_FLOAT,
        GL_FALSE,
        4 * sizeof(GLfloat),
        reinterpret_cast<void*>(0));
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(
        1,
        2,
        GL_FLOAT,
        GL_FALSE,
        4 * sizeof(GLfloat),
        reinterpret_cast<void*>(2 * sizeof(GLfloat)));
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glDisableVertexAttribArray(0);
    glDisableVertexAttribArray(1);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);

    // Force both the lazy external-image sample and the full-resolution intermediate allocation to
    // finish now. The sampled data is never presented and cannot enter temporal history.
    glFinish();
    state->decodeHistoryValid = false;
    const GLenum prewarmError = glGetError();
    if (prewarmError != GL_NO_ERROR) {
        std::ostringstream message;
        message << "decode prewarm GL error=0x" << std::hex << prewarmError;
        return fail(message.str());
    }
    if (!submitEglUnbind.unbind()) {
        std::ostringstream message;
        message << "eglMakeCurrent prewarm cleanup failed error=0x"
                << std::hex << submitEglUnbind.lastError();
        return fail(message.str());
    }

    // Cache eviction is committed only after the complete warmup transaction succeeds. A failed
    // prewarm therefore cannot evict a previously proven identity or leave a half-valid new entry.
    while (camx::EglImageResidencyPolicy::needsEviction(state->eglImageCacheOrder.size())) {
        const uint64_t oldest = state->eglImageCacheOrder.front();
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
    insertedCacheEntry = false;
    decodeStateMutated = false;

    const uint64_t durationUs = elapsedUs();
    ++state->recorderPrewarmSuccesses;
    state->recorderPrewarmTotalUs += durationUs;
    state->recorderPrewarmMaxUs = std::max(state->recorderPrewarmMaxUs, durationUs);
    state->recorderPrewarmBufferIds[static_cast<size_t>(slot)] = bufferId;
    setStatus(state, "recorder AHB/EGL transport prewarmed");
    LOGI(
        "RAW_RECORD_AHB_EGL_PREWARM slot=%d success=true id=%llu extent=%dx%d format=0x%x "
        "decode=%s cacheSize=%zu elapsedUs=%llu swap=false presentation=false",
        slot,
        static_cast<unsigned long long>(bufferId),
        width,
        height,
        description.format,
        sourceIsFp16 ? "RGBA16F" : "RGB10_A2",
        state->eglImageCache.size(),
        static_cast<unsigned long long>(durationUs));
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_rendering_OffscreenAhbEglEncoderBridge_nativeSubmitEncoderReadinessFrame(
    JNIEnv*,
    jobject,
    jlong handle,
    jlong presentationTimeNs
) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(state->mutex);
    ++state->codecReadinessAttempts;
    const auto start = std::chrono::steady_clock::now();
    const auto elapsedUs = [&]() -> uint64_t {
        return static_cast<uint64_t>(
            std::chrono::duration_cast<std::chrono::microseconds>(
                std::chrono::steady_clock::now() - start
            ).count());
    };
    const auto fail = [&](const char* reason) -> jboolean {
        const uint64_t durationUs = elapsedUs();
        ++state->codecReadinessFailures;
        state->codecReadinessTotalUs += durationUs;
        state->codecReadinessMaxUs = std::max(state->codecReadinessMaxUs, durationUs);
        setStatus(state, reason);
        LOGE(
            "CODEC_SURFACE_READINESS success=false reason=%s elapsedUs=%llu",
            reason,
            static_cast<unsigned long long>(durationUs));
        return JNI_FALSE;
    };

    if (!ensureEglReady(state) || state->encoderSurface == EGL_NO_SURFACE) {
        return fail("codec readiness encoder surface unavailable");
    }
    if (presentationTimeNs <= 0L) {
        return fail("codec readiness presentation timestamp is invalid");
    }
    ScopedSubmitEglUnbind submitEglUnbind(state);
    if (eglMakeCurrent(
            state->display,
            state->encoderSurface,
            state->encoderSurface,
            state->context
        ) != EGL_TRUE) {
        return fail("eglMakeCurrent codec readiness failed");
    }
    while (glGetError() != GL_NO_ERROR) {
        // Attribute any following error to this readiness transaction.
    }
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glViewport(0, 0, state->surfaceWidth, state->surfaceHeight);
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_BLEND);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    // RGB code zero is black for every configured SDR/PQ offscreen transfer. This transport-only
    // frame contains no live sensor pixels and never enters temporal or clip-frame accounting.
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);
    const GLenum clearError = glGetError();
    if (clearError != GL_NO_ERROR) {
        return fail("codec readiness clear failed");
    }
    if (state->eglPresentationTimeANDROID != nullptr) {
        state->eglPresentationTimeANDROID(
            state->display,
            state->encoderSurface,
            static_cast<EGLnsecsANDROID>(presentationTimeNs)
        );
    }
    if (eglSwapBuffers(state->display, state->encoderSurface) != EGL_TRUE) {
        return fail("codec readiness swap failed");
    }
    if (!submitEglUnbind.unbind()) {
        return fail("codec readiness EGL cleanup failed");
    }

    const uint64_t durationUs = elapsedUs();
    ++state->codecReadinessSuccesses;
    state->codecReadinessTotalUs += durationUs;
    state->codecReadinessMaxUs = std::max(state->codecReadinessMaxUs, durationUs);
    state->codecReadinessPresentationTimeNs = static_cast<int64_t>(presentationTimeNs);
    state->lastPresentationTimeNs = static_cast<int64_t>(presentationTimeNs);
    setStatus(state, "codec input surface readiness frame submitted");
    LOGI(
        "CODEC_SURFACE_READINESS success=true elapsedUs=%llu ptsNs=%lld "
        "swap=true liveRaw=false clipFrame=false",
        static_cast<unsigned long long>(durationUs),
        static_cast<long long>(presentationTimeNs));
    return JNI_TRUE;
}

// Strict terminal consumer for a Vulkan-produced, already graded BT.2020/ST.2084 RGB10_A2 frame.
// This ABI intentionally shares no implementation branch with nativeSubmitHardwareBuffer: any
// semantic, ownership, precision, surface, extent, timestamp, GL, or fence mismatch rejects the
// frame instead of reaching the generic grade shader.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_rendering_OffscreenAhbEglEncoderBridge_nativeSubmitPregradedBt2020Pq(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject hardwareBufferObject,
    jint width,
    jint height,
    jlong presentationTimeNs,
    jint semantic,
    jint abiVersion,
    jlong profileGeneration,
    jlong profileRevision,
    jint slotIndex,
    jlong slotToken,
    jlong effectiveGradeFingerprint,
    jint rgbRange,
    jlong inputReadReceipt
) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(state->mutex);
    ScopedSubmitEglUnbind submitEglUnbind(state);
    const auto submitStart = std::chrono::steady_clock::now();
    const auto reject = [&](const std::string& reason) -> jboolean {
        ++state->pregradedPqRejectedFrames;
        ++state->failedFrames;
        setStatus(state, std::string("pregraded BT.2020/PQ rejected: ") + reason);
        LOGE("PREGRADED_PQ_IDENTITY_REJECT reason=%s", reason.c_str());
        return JNI_FALSE;
    };

    if (env == nullptr || hardwareBufferObject == nullptr || width <= 0 || height <= 0 ||
        presentationTimeNs <= 0 || semantic != kPregradedBt2020PqSemantic ||
        abiVersion != kPregradedBt2020PqAbiVersion || profileGeneration <= 0 ||
        profileRevision <= 0 || slotIndex < 0 ||
        slotIndex >= static_cast<jint>(kInputReadFenceReceiptCapacity) || slotToken == 0 ||
        effectiveGradeFingerprint == 0 || rgbRange != kPregradedBt2020PqFullRange ||
        inputReadReceipt <= 0) {
        return reject("invalid immutable semantic/ABI/profile/slot/range/receipt proof");
    }
    if (!ensureEglReady(state) || state->encoderSurface == EGL_NO_SURFACE) {
        return reject("encoder EGL surface unavailable");
    }
    if (state->pregradedPqIdentityProgram == 0 || state->pregradedPqSourceTexture == 0 ||
        state->pregradedPqTextureLocation < 0) {
        return reject("dedicated identity program unavailable");
    }
    const bool exactPqSurface =
        state->deliveryCompatible && state->deliveryModeLabel == "PQ" &&
        state->deliveryColorSpaceLabel == "HDR10_ST2084" &&
        state->redBits == 10 && state->greenBits == 10 && state->blueBits == 10 &&
        state->alphaBits == 2 && state->recordable == EGL_TRUE &&
        (state->surfaceColorSpace == EGL_GL_COLORSPACE_BT2020_PQ_EXT ||
         (state->bufferDataSpaceApplied && state->bufferDataSpace == ADATASPACE_BT2020_PQ));
    if (!exactPqSurface) {
        return reject("destination is not a proven recordable RGB10_A2 BT.2020/PQ surface");
    }
    if (state->surfaceWidth != width || state->surfaceHeight != height) {
        return reject("source proof and encoder surface extents differ");
    }
    if (!state->inputReadFenceAvailable || state->eglCreateSyncKHR == nullptr ||
        state->eglDestroySyncKHR == nullptr || state->eglClientWaitSyncKHR == nullptr) {
        return reject("exact EGL input-read fence unavailable");
    }
    if (findInputReadFenceReceipt(state, static_cast<uint64_t>(inputReadReceipt)) != nullptr) {
        ++state->inputReadFenceFailures;
        return reject("duplicate input-read fence receipt");
    }
    InputReadFenceReceipt* inputReadReceiptSlot = findFreeInputReadFenceReceipt(state);
    if (inputReadReceiptSlot == nullptr) {
        ++state->inputReadFenceFailures;
        return reject("input-read fence receipt capacity exhausted");
    }
    if (state->lastPresentationTimeNs > 0 &&
        presentationTimeNs <= state->lastPresentationTimeNs) {
        ++state->presentationTimelineRegressions;
        return reject("non-monotonic presentation timestamp");
    }

    const uint64_t generation = static_cast<uint64_t>(profileGeneration);
    const uint64_t revision = static_cast<uint64_t>(profileRevision);
    const uint64_t token = static_cast<uint64_t>(slotToken);
    const uint64_t fingerprint = static_cast<uint64_t>(effectiveGradeFingerprint);
    const size_t slot = static_cast<size_t>(slotIndex);
    const bool staleProfile =
        generation < state->pregradedPqLastProfileGeneration ||
        (generation == state->pregradedPqLastProfileGeneration &&
         revision < state->pregradedPqLastProfileRevision);
    if (staleProfile) {
        return reject("stale profile generation/revision proof");
    }
    if (generation == state->pregradedPqLastProfileGeneration &&
        revision == state->pregradedPqLastProfileRevision &&
        state->pregradedPqLastGradeFingerprint != 0u &&
        fingerprint != state->pregradedPqLastGradeFingerprint) {
        return reject("effective-grade fingerprint changed within one profile revision");
    }
    if (state->pregradedPqLastSlotTokens[slot] == token) {
        return reject("duplicate Vulkan slot token");
    }

    AHardwareBuffer* hardwareBuffer =
        AHardwareBuffer_fromHardwareBuffer(env, hardwareBufferObject);
    if (hardwareBuffer == nullptr) {
        return reject("AHardwareBuffer_fromHardwareBuffer failed");
    }
    AHardwareBuffer_acquire(hardwareBuffer);
    std::unique_ptr<AHardwareBuffer, decltype(&AHardwareBuffer_release)> scopedHardwareBuffer(
        hardwareBuffer,
        &AHardwareBuffer_release
    );
    AHardwareBuffer_Desc description{};
    AHardwareBuffer_describe(hardwareBuffer, &description);
    const bool exactSource =
        description.format == AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM &&
        description.width == static_cast<uint32_t>(width) &&
        description.height == static_cast<uint32_t>(height) && description.layers == 1u &&
        (description.usage & AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE) != 0u;
    if (!exactSource) {
        return reject("source is not exact single-layer GPU-sampled RGB10_A2 at proof extent");
    }
    if (state->ahardwareBufferGetId == nullptr) {
        return reject("stable AHardwareBuffer identity unavailable");
    }
    uint64_t bufferId = 0u;
    if (state->ahardwareBufferGetId(hardwareBuffer, &bufferId) != 0 || bufferId == 0u) {
        ++state->eglImageCacheIdFailures;
        return reject("stable AHardwareBuffer identity query failed");
    }
    if (state->pregradedPqSlotBufferIds[slot] != 0u &&
        state->pregradedPqSlotBufferIds[slot] != bufferId) {
        return reject("declared Vulkan slot changed GraphicBuffer identity");
    }

    if (eglMakeCurrent(
            state->display,
            state->encoderSurface,
            state->encoderSurface,
            state->context
        ) != EGL_TRUE) {
        return reject("eglMakeCurrent identity submit failed");
    }
    while (glGetError() != GL_NO_ERROR) {
        // Clear prior setup errors so every following failure belongs to this exact submission.
    }

    EGLClientBuffer clientBuffer = state->eglGetNativeClientBufferANDROID(hardwareBuffer);
    if (clientBuffer == nullptr) {
        return reject("eglGetNativeClientBufferANDROID failed");
    }
    const EGLint imageAttrs[] = {
        EGL_IMAGE_PRESERVED_KHR, EGL_TRUE,
        EGL_NONE
    };
    EGLImageKHR eglImage = EGL_NO_IMAGE_KHR;
    bool eglImageCached = false;
    if (state->eglImageCacheEnabled) {
        const auto cached = state->eglImageCache.find(bufferId);
        if (cached != state->eglImageCache.end() && cached->second.image != EGL_NO_IMAGE_KHR) {
            eglImage = cached->second.image;
            eglImageCached = true;
            ++state->eglImageCacheHits;
        }
    }
    if (eglImage == EGL_NO_IMAGE_KHR) {
        if (state->eglImageCacheEnabled) ++state->eglImageCacheMisses;
        eglImage = state->eglCreateImageKHR(
            state->display,
            EGL_NO_CONTEXT,
            EGL_NATIVE_BUFFER_ANDROID,
            clientBuffer,
            imageAttrs
        );
        if (eglImage == EGL_NO_IMAGE_KHR) {
            return reject("eglCreateImageKHR identity import failed");
        }
        // Never evict an image that may still be named by an outstanding read receipt. If the
        // shared cache is full, this frame simply owns a short-lived import; pixel processing and
        // ownership guarantees remain identical.
        if (state->eglImageCacheEnabled &&
            camx::EglImageResidencyPolicy::canAdmitWithoutEviction(state->eglImageCache.size())) {
            state->eglImageCache.emplace(bufferId, EglImageCacheEntry{eglImage});
            state->eglImageCacheOrder.push_back(bufferId);
            eglImageCached = true;
        }
    }
    const auto destroyTransientImage = [&]() {
        if (!eglImageCached && eglImage != EGL_NO_IMAGE_KHR) {
            if (state->eglDestroyImageKHR(state->display, eglImage) != EGL_TRUE) {
                LOGE(
                    "pregraded PQ transient EGLImage destroy failed bufferId=%llu error=0x%04x",
                    static_cast<unsigned long long>(bufferId),
                    static_cast<unsigned int>(eglGetError())
                );
            }
            eglImage = EGL_NO_IMAGE_KHR;
        }
    };

    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, state->pregradedPqSourceTexture);
    state->glEGLImageTargetTexture2DOES(GL_TEXTURE_EXTERNAL_OES, eglImage);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glViewport(0, 0, width, height);
    glDisable(GL_BLEND);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_STENCIL_TEST);
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_DITHER);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glUseProgram(state->pregradedPqIdentityProgram);
    glUniform1i(state->pregradedPqTextureLocation, 0);
    glBindBuffer(GL_ARRAY_BUFFER, state->vertexBuffer);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), nullptr);
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(
        1,
        2,
        GL_FLOAT,
        GL_FALSE,
        4 * sizeof(GLfloat),
        reinterpret_cast<const void*>(2 * sizeof(GLfloat))
    );
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    glDisableVertexAttribArray(0);
    glDisableVertexAttribArray(1);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0);
    const GLenum drawError = glGetError();
    if (drawError != GL_NO_ERROR) {
        // A driver may have accepted the draw before reporting a later validation fault. Complete
        // every possible source read before the producer is allowed to reuse this slot.
        glFinish();
        destroyTransientImage();
        std::ostringstream reason;
        reason << "identity draw failed error=0x" << std::hex << drawError;
        return reject(reason.str());
    }

    const EGLint syncAttrs[] = {EGL_NONE};
    EGLSyncKHR inputReadSync = state->eglCreateSyncKHR(
        state->display,
        EGL_SYNC_FENCE_KHR,
        syncAttrs
    );
    if (inputReadSync == EGL_NO_SYNC_KHR) {
        ++state->inputReadFenceFailures;
        // The draw has consumed the shared Vulkan slot but no receipt can represent it. Complete the
        // read synchronously before returning failure; never fabricate a receipt or fall back to the
        // generic renderer.
        glFinish();
        destroyTransientImage();
        return reject("exact input-read fence creation failed");
    }
    glFlush();
    ++state->inputReadFenceCreated;
    ++state->inputReadFinalBoundaryFrames;
    const auto retireUncommittedRead = [&]() {
        if (inputReadSync == EGL_NO_SYNC_KHR) return;
        const EGLint waitResult = state->eglClientWaitSyncKHR(
            state->display,
            inputReadSync,
            0,
            EGL_FOREVER_KHR
        );
        if (waitResult != EGL_CONDITION_SATISFIED_KHR) {
            glFinish();
        }
        if (state->eglDestroySyncKHR(state->display, inputReadSync) != EGL_TRUE) {
            ++state->inputReadFenceFailures;
            LOGE(
                "pregraded PQ uncommitted input-read fence destroy failed error=0x%04x",
                static_cast<unsigned int>(eglGetError())
            );
        }
        inputReadSync = EGL_NO_SYNC_KHR;
    };

    if (state->eglPresentationTimeANDROID(
            state->display,
            state->encoderSurface,
            static_cast<EGLnsecsANDROID>(presentationTimeNs)
        ) != EGL_TRUE) {
        retireUncommittedRead();
        destroyTransientImage();
        return reject("eglPresentationTimeANDROID identity submit failed");
    }
    const auto swapStart = std::chrono::steady_clock::now();
    const EGLBoolean swapped = eglSwapBuffers(state->display, state->encoderSurface);
    const uint64_t swapDurationUs = static_cast<uint64_t>(
        std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::steady_clock::now() - swapStart
        ).count()
    );
    if (swapped != EGL_TRUE) {
        retireUncommittedRead();
        destroyTransientImage();
        return reject("eglSwapBuffers identity submit failed");
    }

    // Swap is irreversible. Publish all ownership/proof state atomically under the bridge mutex
    // before reporting success so neither this Vulkan slot nor this exact proof can be reused.
    destroyTransientImage();
    inputReadReceiptSlot->id = static_cast<uint64_t>(inputReadReceipt);
    inputReadReceiptSlot->sync = inputReadSync;
    inputReadReceiptSlot->signaled = false;
    inputReadReceiptSlot->bufferId = bufferId;
    inputReadReceiptSlot->presentationTimeNs = static_cast<int64_t>(presentationTimeNs);
    inputReadSync = EGL_NO_SYNC_KHR;
    state->inputReadFenceMaxOutstanding = std::max<uint64_t>(
        state->inputReadFenceMaxOutstanding,
        static_cast<uint64_t>(inputReadFenceOutstandingCount(state))
    );
    state->pregradedPqLastProfileGeneration = generation;
    state->pregradedPqLastProfileRevision = revision;
    state->pregradedPqLastGradeFingerprint = fingerprint;
    state->pregradedPqLastSlotTokens[slot] = token;
    if (state->pregradedPqSlotBufferIds[slot] == 0u) {
        state->pregradedPqSlotBufferIds[slot] = bufferId;
    }
    state->sourceFormat = "AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM";
    state->sourceBitDepth = 10;
    state->sourcePrecision = "PACKED_10_BIT_PREGRADED_BT2020_PQ";
    state->sourcePrecisionProven = true;
    state->lastInputTransfer = 1;
    state->lastInputPrimaries = 2;
    state->lastInputRange = kPregradedBt2020PqFullRange;
    state->lastPresentationTimeNs = static_cast<int64_t>(presentationTimeNs);
    ++state->pregradedPqIdentityFrames;
    ++state->submittedFrames;
    state->swapDurationTotalUs += swapDurationUs;
    state->swapDurationMaxUs = std::max(state->swapDurationMaxUs, swapDurationUs);
    if (swapDurationUs > 45000u) ++state->slowSwapFrames;
    const uint64_t submitDurationUs = static_cast<uint64_t>(
        std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::steady_clock::now() - submitStart
        ).count()
    );
    state->submitDurationTotalUs += submitDurationUs;
    state->submitDurationMaxUs = std::max(state->submitDurationMaxUs, submitDurationUs);
    if (submitDurationUs > 40000u) ++state->slowSubmitFrames;
    setStatus(state, "pregraded BT.2020/PQ identity frame submitted");
    if (state->pregradedPqIdentityFrames <= 3u ||
        state->pregradedPqIdentityFrames % 25u == 0u) {
        LOGI(
            "PREGRADED_PQ_IDENTITY_COMMITTED frame=%llu generation=%llu revision=%llu "
            "slot=%d token=%llu fingerprint=%llu receipt=%lld bufferId=%llu ptsNs=%lld "
            "swapUs=%llu submitUs=%llu outstanding=%zu",
            static_cast<unsigned long long>(state->pregradedPqIdentityFrames),
            static_cast<unsigned long long>(generation),
            static_cast<unsigned long long>(revision),
            slotIndex,
            static_cast<unsigned long long>(token),
            static_cast<unsigned long long>(fingerprint),
            static_cast<long long>(inputReadReceipt),
            static_cast<unsigned long long>(bufferId),
            static_cast<long long>(presentationTimeNs),
            static_cast<unsigned long long>(swapDurationUs),
            static_cast<unsigned long long>(submitDurationUs),
            inputReadFenceOutstandingCount(state)
        );
    }
    if (!submitEglUnbind.unbind()) {
        ++state->inputReadFenceFailures;
        std::ostringstream message;
        message << "eglMakeCurrent identity-submit cleanup failed after irreversible swap error=0x"
                << std::hex << submitEglUnbind.lastError();
        setStatus(state, message.str());
        LOGE("%s", message.str().c_str());
        // The frame and exact receipt are already committed. Returning false would invite a
        // duplicate retry, so preserve one-time submission semantics and report success.
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_rendering_OffscreenAhbEglEncoderBridge_nativeSubmitHardwareBuffer(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject hardwareBufferObject,
    jint width,
    jint height,
    jint format,
    jlong presentationTimeNs,
    jlong sensorTimestampNs,
    jfloat sharpness,
    jfloat denoise,
    jfloat chromaDenoise,
    jfloat temporalBlend,
    jint ndrDeliveryMode,
    jfloat ndrTargetPeakNits,
    jfloat ndrWarmSkinRedTame,
    jboolean preserveInputPqMaster,
    jint inputTransfer,
    jint inputPrimaries,
    jint inputRange,
    jlong inputReadReceipt,
    jboolean niv99PacketValid,
    jboolean niv99PixelApplicationApproved,
    jlong niv99CameraIdHash,
    jlong niv99SessionGeneration,
    jlong niv99FrameNumber,
    jlong niv99SensorTimestampNs,
    jint niv99Authority,
    jfloat niv99RedCorrection,
    jfloat niv99GreenCorrection,
    jfloat niv99BlueCorrection,
    jfloat niv99Confidence,
    jboolean prewarmOnly
) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return JNI_FALSE;
    const bool cameraP010SdrPrewarm = prewarmOnly == JNI_TRUE;
    const auto prewarmStarted = std::chrono::steady_clock::now();
    const bool exactNdrDeliveryMode = ndrDeliveryMode == 0 || ndrDeliveryMode == 1;
    const float ndrDeliveryModeValue = (ndrDeliveryMode == 1) ? 1.0f : 0.0f;
    const float ndrBaselinePeakNits = ndrDeliveryMode == 1
        ? kNivukxMasterPeakNits
        : kNdr10MasterPeakNits;
    const float requestedNdrTargetPeakNits = std::clamp(
        static_cast<float>(ndrTargetPeakNits),
        203.0f,
        kNivukxMasterPeakNits
    );
    // Valid proprietary profiles have fixed mastering contracts. Do not silently retain an old
    // caller baseline (1420/1680) or advertise a peak different from the PQ pixels generated here.
    const float ndrTargetPeakNitsValue = exactNdrDeliveryMode
        ? ndrBaselinePeakNits
        : requestedNdrTargetPeakNits;
    const float ndrWarmSkinRedTameValue = std::clamp(ndrWarmSkinRedTame, 0.0f, 1.0f);
    const bool inputIsPq = inputTransfer == 1;
    const bool inputIsSensorLinear = inputTransfer == 3;
    const bool inputIsNeoSceneLog10 = inputTransfer == 4;
    const bool projectOwnedInput = inputIsSensorLinear || inputIsNeoSceneLog10;
    const bool inputReadFenceRequested = inputReadReceipt > 0;
    std::lock_guard<std::mutex> lock(state->mutex);
    bool prewarmFailureRecorded = false;
    const auto recordFrameFailure = [&]() {
        if (!cameraP010SdrPrewarm) {
            ++state->failedFrames;
            return;
        }
        if (prewarmFailureRecorded) return;
        prewarmFailureRecorded = true;
        const uint64_t durationUs = static_cast<uint64_t>(
            std::chrono::duration_cast<std::chrono::microseconds>(
                std::chrono::steady_clock::now() - prewarmStarted
            ).count());
        ++state->cameraP010SdrPrewarmFailures;
        state->cameraP010SdrPrewarmTotalUs += durationUs;
        state->cameraP010SdrPrewarmMaxUs = std::max(
            state->cameraP010SdrPrewarmMaxUs,
            durationUs);
    };
    if (cameraP010SdrPrewarm) {
        ++state->cameraP010SdrPrewarmAttempts;
        // A zero-strength creative LUT may use the exact SDR specialization. When a valid LUT is
        // active, however, that specialization is intentionally ineligible because it compiles
        // uLutStrength to zero. Prewarm must then exercise the canonical sampler2D program so its
        // pending 3D texture is uploaded and the exact live-recording colour path is warmed without
        // bypassing the selected look.
        const bool cameraP010SdrPrewarmHasExactGradeProgram =
            state->mainProgram2d != 0 &&
            (state->lutStrength != 0.0f || state->sdrInvariantProgram2d != 0);
        const bool exactPrewarmContract =
            hardwareBufferObject != nullptr &&
            width > 0 && height > 0 &&
            format == AHARDWAREBUFFER_FORMAT_YCbCr_P010 &&
            presentationTimeNs == 0 &&
            inputTransfer == 0 &&
            !projectOwnedInput &&
            !inputReadFenceRequested &&
            preserveInputPqMaster != JNI_TRUE &&
            niv99PacketValid != JNI_TRUE &&
            niv99PixelApplicationApproved != JNI_TRUE &&
            state->deliveryModeLabel == "SDR" &&
            state->eglImageCacheEnabled &&
            state->eglImageCacheStableIds &&
            state->ahardwareBufferGetId != nullptr &&
            state->decodeOnceEnabled &&
            state->decodeProgram != 0 &&
            cameraP010SdrPrewarmHasExactGradeProgram;
        if (!exactPrewarmContract) {
            recordFrameFailure();
            setStatus(state, "Camera2 P010 SDR prewarm contract unavailable");
            return JNI_FALSE;
        }
        state->historyValid = false;
        state->decodeHistoryValid = false;
    } else {
        state->niv99PacketsSeen += 1u;
    }
    bool niv99ActiveForFrame = false;
    float niv99ActiveConfidenceValue = 0.0f;
    std::array<float, 3> niv99RgbCorrectionValue{1.0f, 1.0f, 1.0f};
    if (niv99PacketValid != JNI_TRUE) {
        if (niv99PixelApplicationApproved == JNI_TRUE) {
            state->niv99PacketsRejected += 1u;
            setStatus(state, "NIV99 encoder activation rejected: packet-valid flag is false");
            return JNI_FALSE;
        }
    } else {
        const bool completeIdentity = niv99CameraIdHash != 0 &&
            niv99SessionGeneration >= 0 && niv99FrameNumber >= 0 &&
            niv99SensorTimestampNs > 0;
        const bool exactImageTimestamp = sensorTimestampNs > 0 &&
            niv99SensorTimestampNs == sensorTimestampNs;
        const bool validAuthority = niv99Authority >= 0 && niv99Authority <= 4;
        const bool finiteValues = std::isfinite(niv99RedCorrection) &&
            std::isfinite(niv99GreenCorrection) && std::isfinite(niv99BlueCorrection) &&
            std::isfinite(niv99Confidence);
        const bool boundedValues = niv99RedCorrection >= 0.5f && niv99RedCorrection <= 2.0f &&
            niv99GreenCorrection >= 0.5f && niv99GreenCorrection <= 2.0f &&
            niv99BlueCorrection >= 0.5f && niv99BlueCorrection <= 2.0f &&
            niv99Confidence >= 0.0f && niv99Confidence <= 1.0f;
        const bool userWhiteBalanceAuthority = niv99Authority == 0 || niv99Authority == 1;
        const bool userWhiteBalancePreserved = !userWhiteBalanceAuthority ||
            (std::abs(niv99RedCorrection - 1.0f) <= 1.0e-6f &&
             std::abs(niv99GreenCorrection - 1.0f) <= 1.0e-6f &&
             std::abs(niv99BlueCorrection - 1.0f) <= 1.0e-6f);
        const uint64_t cameraIdHash = static_cast<uint64_t>(niv99CameraIdHash);
        const bool sameCamera = state->niv99HasAcceptedPacket &&
            state->niv99CameraIdHash == cameraIdHash;
        const bool staleSameCamera = sameCamera &&
            (niv99SessionGeneration < state->niv99SessionGeneration ||
             (niv99SessionGeneration == state->niv99SessionGeneration &&
              (niv99FrameNumber <= state->niv99FrameNumber ||
               niv99SensorTimestampNs <= state->niv99SensorTimestampNs)));
        const bool staleDifferentCamera = state->niv99HasAcceptedPacket && !sameCamera &&
            niv99SessionGeneration <= state->niv99SessionGeneration;
        if (!completeIdentity || !exactImageTimestamp || !validAuthority || !finiteValues ||
            !boundedValues || !userWhiteBalancePreserved || staleSameCamera || staleDifferentCamera) {
            state->niv99PacketsRejected += 1u;
            setStatus(state, "NIV99 encoder packet rejected: invalid or stale provenance");
            return JNI_FALSE;
        }
        const bool niv99TimelineChanged = state->niv99HasAcceptedPacket &&
            (!sameCamera || niv99SessionGeneration != state->niv99SessionGeneration);
        if (niv99TimelineChanged) {
            // NIV99 metadata and temporal pixels must belong to the same camera/session timeline.
            // Invalidate both history variants before this frame selects either the single-pass
            // processed-history path or the decode-once pre-grade path. The texture allocations are
            // retained, so a camera/session transition remains allocation-free on the hot path.
            state->historyValid = false;
            state->decodeHistoryValid = false;
            LOGI(
                "NIV99_HISTORY_RESET previousCameraHash=%llu previousGeneration=%lld "
                "cameraHash=%llu generation=%lld frame=%lld timestampNs=%lld",
                static_cast<unsigned long long>(state->niv99CameraIdHash),
                static_cast<long long>(state->niv99SessionGeneration),
                static_cast<unsigned long long>(cameraIdHash),
                static_cast<long long>(niv99SessionGeneration),
                static_cast<long long>(niv99FrameNumber),
                static_cast<long long>(niv99SensorTimestampNs));
        }
        state->niv99CameraIdHash = cameraIdHash;
        state->niv99SessionGeneration = static_cast<int64_t>(niv99SessionGeneration);
        state->niv99FrameNumber = static_cast<int64_t>(niv99FrameNumber);
        state->niv99SensorTimestampNs = static_cast<int64_t>(niv99SensorTimestampNs);
        state->niv99HasAcceptedPacket = true;
        state->niv99PacketsAccepted += 1u;
        niv99ActiveForFrame = niv99PixelApplicationApproved == JNI_TRUE;
        niv99ActiveConfidenceValue = niv99ActiveForFrame
            ? std::clamp(static_cast<float>(niv99Confidence), 0.0f, 1.0f)
            : 0.0f;
        if (niv99ActiveForFrame && niv99Authority == 2) {
            niv99RgbCorrectionValue = {
                std::clamp(static_cast<float>(niv99RedCorrection), 0.92f, 1.08f),
                std::clamp(static_cast<float>(niv99GreenCorrection), 0.92f, 1.08f),
                std::clamp(static_cast<float>(niv99BlueCorrection), 0.92f, 1.08f)
            };
        }
    }
    ScopedSubmitEglUnbind submitEglUnbind(state);
    if (hardwareBufferObject == nullptr) return JNI_FALSE;
    InputReadFenceReceipt* inputReadReceiptSlot = nullptr;
    if (inputReadFenceRequested) {
        if (!projectOwnedInput ||
            findInputReadFenceReceipt(state, static_cast<uint64_t>(inputReadReceipt)) != nullptr) {
            ++state->inputReadFenceFailures;
            setStatus(state, "invalid or duplicate project-owned input-read fence receipt");
            return JNI_FALSE;
        }
        inputReadReceiptSlot = findFreeInputReadFenceReceipt(state);
        if (inputReadReceiptSlot == nullptr) {
            ++state->inputReadFenceFailures;
            setStatus(state, "project-owned input-read fence receipt capacity exhausted");
            return JNI_FALSE;
        }
    }
    state->lastInputTransfer = inputTransfer;
    state->lastInputPrimaries = inputPrimaries;
    state->lastInputRange = inputRange;
    const bool pqCodeDelivery =
        state->deliveryModeLabel == "PQ" ||
        state->deliveryModeLabel == "PROJECT_PQ_TEXTURE";
    const bool flatPqMasterPassthrough =
        preserveInputPqMaster == JNI_TRUE &&
        inputIsPq &&
        pqCodeDelivery;
    const float applyNdrPqDeliveryToneMap =
        pqCodeDelivery && !flatPqMasterPassthrough ? 1.0f : 0.0f;
    const auto submitStart = std::chrono::steady_clock::now();
    if (!ensureEglReady(state) || state->encoderSurface == EGL_NO_SURFACE) {
        recordFrameFailure();
        return JNI_FALSE;
    }
    if (presentationTimeNs > 0L && state->lastPresentationTimeNs > 0L &&
        presentationTimeNs <= state->lastPresentationTimeNs) {
        recordFrameFailure();
        ++state->presentationTimelineRegressions;
        std::ostringstream message;
        message << "non-monotonic encoder presentation timestamp current="
                << static_cast<long long>(presentationTimeNs)
                << " previous=" << static_cast<long long>(state->lastPresentationTimeNs);
        setStatus(state, message.str());
        LOGE("%s", message.str().c_str());
        return JNI_FALSE;
    }
    if (state->submittedFrames == 0u && flatPqMasterPassthrough) {
        LOGI(
            "NDR flat master: PQ input codes preserved on PQ encoder surface; offline delivery tone-map deferred"
        );
    }
    AHardwareBuffer* hardwareBuffer = AHardwareBuffer_fromHardwareBuffer(env, hardwareBufferObject);
    if (hardwareBuffer == nullptr) {
        recordFrameFailure();
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
    if (cameraP010SdrPrewarm &&
        (description.format != AHARDWAREBUFFER_FORMAT_YCbCr_P010 ||
         description.format != static_cast<uint32_t>(format) ||
         description.width != static_cast<uint32_t>(width) ||
         description.height != static_cast<uint32_t>(height) ||
         state->surfaceWidth != width || state->surfaceHeight != height)) {
        AHardwareBuffer_release(hardwareBuffer);
        recordFrameFailure();
        setStatus(state, "Camera2 P010 SDR prewarm source format/extent mismatch");
        return JNI_FALSE;
    }
    const HardwareBufferSourceProof sourceProof = classifySourcePrecision(description);
    state->sourceFormat = sourceProof.format;
    state->sourceBitDepth = sourceProof.bitDepth;
    state->sourcePrecision = sourceProof.precision;
    state->sourcePrecisionProven = sourceProof.proven;
    const bool sourceIsFp16 =
        description.format == AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT;
    const bool sourceIsRgb10A2 =
        description.format == AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM;
    if (inputIsSensorLinear) {
        const bool strictLinearContract =
            sourceIsFp16 && inputPrimaries == 3 && inputRange == 3;
        if (!strictLinearContract) {
            AHardwareBuffer_release(hardwareBuffer);
            recordFrameFailure();
            setStatus(state, "sensor-linear NDR ingress rejected: require RGBA16F ACEScg extended-range");
            return JNI_FALSE;
        }
    } else if (inputIsNeoSceneLog10) {
        const bool strictSceneLogContract =
            sourceIsRgb10A2 && inputPrimaries == 3 && inputRange == 1;
        if (!strictSceneLogContract) {
            AHardwareBuffer_release(hardwareBuffer);
            recordFrameFailure();
            setStatus(state,
                "Neo Scene Log10 ingress rejected: require RGB10_A2 ACEScg full-range contract");
            return JNI_FALSE;
        }
    } else if (sourceIsFp16) {
        AHardwareBuffer_release(hardwareBuffer);
        recordFrameFailure();
        setStatus(state, "RGBA16F source rejected without sensor-linear transfer contract");
        return JNI_FALSE;
    }
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
            recordFrameFailure();
            setStatus(
                state,
                "offscreen source precision rejected format=" + sourceProof.format
            );
            return JNI_FALSE;
        }
    }
    if (!cameraP010SdrPrewarm && state->submittedFrames == 0u) {
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
        recordFrameFailure();
        setStatus(state, "eglMakeCurrent submit failed");
        return JNI_FALSE;
    }
    if (cameraP010SdrPrewarm) {
        while (glGetError() != GL_NO_ERROR) {
            // Attribute the final warm-up error only to work issued for this exact Camera2 frame.
        }
    }
    const bool sensorNdrTonePqLutReadyForFrame =
        applyNdrPqDeliveryToneMap > 0.5f &&
        (inputIsSensorLinear || inputIsNeoSceneLog10) &&
        ensureSensorNdrTonePqLut(state, ndrTargetPeakNitsValue);
    EGLClientBuffer clientBuffer = state->eglGetNativeClientBufferANDROID(hardwareBuffer);
    if (clientBuffer == nullptr) {
        AHardwareBuffer_release(hardwareBuffer);
        recordFrameFailure();
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
            recordFrameFailure();
            setStatus(state, "eglCreateImageKHR failed");
            return JNI_FALSE;
        }
        if (state->eglImageCacheEnabled) {
            state->eglImageCache.emplace(
                eglImageCacheKey,
                EglImageCacheEntry{eglImage}
            );
            state->eglImageCacheOrder.push_back(eglImageCacheKey);
            while (camx::EglImageResidencyPolicy::needsEviction(state->eglImageCacheOrder.size())) {
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
    const NeoConstantLiftValues neoConstantLift = resolveNeoConstantLift(
        static_cast<float>(sharpness),
        static_cast<float>(denoise),
        static_cast<float>(chromaDenoise)
    );
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
    const GLint activeDecodeSourceTexelLocation = useExplicitYuv
        ? state->decodeExplicitYuvSourceTexelLoc
        : state->decodeSourceTexelLoc;
    const GLint activeDecodeMinificationScaleLocation = useExplicitYuv
        ? state->decodeExplicitYuvMinificationScaleLoc
        : state->decodeMinificationScaleLoc;
    const GLint activeDecodePreserveExtendedRangeLocation = useExplicitYuv
        ? state->decodeExplicitYuvPreserveExtendedRangeLoc
        : state->decodePreserveExtendedRangeLoc;
    if (!cameraP010SdrPrewarm && state->submittedFrames == 0u) {
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
    // Preview-only anti-alias route. The RAW/Neo-scene project surface is visibly smaller than its
    // full-resolution ACEScg source on current devices (4096x3072 -> 1280x960). Ordinary GL_LINEAR
    // samples less than that 3.2x pixel footprint and aliases repeating detail into wavy texture.
    // Downsample into a target-size FP16 intermediate first, then run the unchanged canonical NDR/PQ
    // pass. MediaCodec SDR/HLG/PQ delivery and same-size/full-resolution masters never enter this path.
    const bool useProjectPreviewDownsample =
        state->deliveryModeLabel == "PROJECT_PQ_TEXTURE" &&
        (inputIsSensorLinear || inputIsNeoSceneLog10) &&
        (sourceWidth > targetWidth || sourceHeight > targetHeight) &&
        state->mainProgram2d != 0 && activeDecodeProgram != 0;
    const bool useDecodeIntermediate = useDecodeOnce || useProjectPreviewDownsample;
    const int sensorLinear2dSampleWidth = useProjectPreviewDownsample ? targetWidth : sourceWidth;
    const int sensorLinear2dSampleHeight = useProjectPreviewDownsample ? targetHeight : sourceHeight;
    const bool sensorLinear2dIntegerOffsetsExact =
        sensorLinear2dSampleWidth > 0 && sensorLinear2dSampleWidth < 10000 &&
        sensorLinear2dSampleHeight > 0 && sensorLinear2dSampleHeight < 10000;
    // Every substituted value is checked against this exact frame. Zero NIV99 confidence is the
    // canonical pixel-identity branch, not a feature disable: an approved non-zero packet
    // immediately selects the generic shader. The pre-grade-history constant is valid because the
    // two-pass path owns decoded history and the direct path owns external source history whenever
    // temporal blend is active.
    const bool sdrPreGradeHistoryProven =
        requestedTemporalBlend <= 0.0f || useDecodeIntermediate || activeDecodeProgram != 0;
    // The SDR variants compile the creative-LUT branch to its exact identity result. Selection is
    // therefore legal only when the runtime strength is exactly zero. A pending or active custom
    // LUT has a positive strength and uses the separate dynamic-LUT specialization below, including
    // its unchanged upload and canonical trilinear sampling path; no creative look is bypassed.
    const bool sdrCreativeLutBypassProven = state->lutStrength == 0.0f;
    const bool sdrCreativeLutActive = state->lutStrength > 0.0f;
    // A valid NIV99 packet can carry denoise/reconstruction strength while its RGB correction is
    // mathematically identity. On bounded Camera2 SDR/RGB10_A2 input, setting the WB confidence to
    // the canonical zero branch in that exact case produces the same pixel and removes six redundant
    // per-pixel WB evaluations (center, four cardinals, history). Non-identity RGB correction always
    // retains the dynamic NIV99 program.
    const bool niv99RgbCorrectionIdentity =
        niv99RgbCorrectionValue[0] == 1.0f &&
        niv99RgbCorrectionValue[1] == 1.0f &&
        niv99RgbCorrectionValue[2] == 1.0f;
    const bool niv99WhiteBalanceIdentityProven =
        niv99ActiveConfidenceValue == 0.0f || niv99RgbCorrectionIdentity;
    const bool niv99WhiteBalanceArithmeticRequired =
        niv99ActiveForFrame && niv99ActiveConfidenceValue > 0.0f &&
        !niv99RgbCorrectionIdentity;
    const bool sdrInvariantSpecializationEligible =
        state->deliveryModeLabel == "SDR" &&
        inputTransfer == 0 &&
        applyNdrPqDeliveryToneMap == 0.0f &&
        niv99WhiteBalanceIdentityProven &&
        sdrCreativeLutBypassProven &&
        sdrPreGradeHistoryProven;
    const bool useSdrInvariantSpecialized2d =
        sdrInvariantSpecializationEligible &&
        useDecodeIntermediate &&
        sourceWidth == targetWidth && sourceHeight == targetHeight &&
        sensorLinear2dIntegerOffsetsExact &&
        state->sdrInvariantProgram2d != 0;
    const bool useSdrInvariantSpecializedExternal =
        sdrInvariantSpecializationEligible &&
        !useDecodeIntermediate &&
        state->sdrInvariantProgramExternal != 0;
    const bool useSdrInvariantSpecializedMain =
        useSdrInvariantSpecialized2d || useSdrInvariantSpecializedExternal;
    const bool sdrNiv99SpecializationEligible =
        state->deliveryModeLabel == "SDR" &&
        inputTransfer == 0 &&
        applyNdrPqDeliveryToneMap == 0.0f &&
        niv99WhiteBalanceArithmeticRequired &&
        sdrCreativeLutBypassProven &&
        sdrPreGradeHistoryProven;
    const bool useSdrNiv99Specialized2d =
        sdrNiv99SpecializationEligible &&
        useDecodeIntermediate &&
        sourceWidth == targetWidth && sourceHeight == targetHeight &&
        sensorLinear2dIntegerOffsetsExact &&
        state->sdrNiv99Program2d != 0;
    const bool useSdrNiv99SpecializedExternal =
        sdrNiv99SpecializationEligible &&
        !useDecodeIntermediate &&
        state->sdrNiv99ProgramExternal != 0;
    const bool useSdrNiv99SpecializedMain =
        useSdrNiv99Specialized2d || useSdrNiv99SpecializedExternal;
    // The LUT-capable variants retain uLutStrength and the canonical texture3D sampling equation.
    // They specialize only frame-proven SDR/PQ/history values, so full colour remains pixel-equivalent
    // while the 4K hot path no longer executes the unreachable RAW/NDR branches.
    const bool sdrCreativeLutSpecializationEligible =
        state->deliveryModeLabel == "SDR" &&
        inputTransfer == 0 &&
        applyNdrPqDeliveryToneMap == 0.0f &&
        niv99WhiteBalanceIdentityProven &&
        sdrCreativeLutActive &&
        sdrPreGradeHistoryProven;
    const bool useSdrCreativeLutSpecialized2d =
        sdrCreativeLutSpecializationEligible &&
        useDecodeIntermediate &&
        sourceWidth == targetWidth && sourceHeight == targetHeight &&
        sensorLinear2dIntegerOffsetsExact &&
        state->sdrCreativeLutProgram2d != 0;
    const bool useSdrCreativeLutSpecializedExternal =
        sdrCreativeLutSpecializationEligible &&
        !useDecodeIntermediate &&
        state->sdrCreativeLutProgramExternal != 0;
    const bool useSdrCreativeLutSpecializedMain =
        useSdrCreativeLutSpecialized2d || useSdrCreativeLutSpecializedExternal;
    const bool useSdrCreativeLutGatherGrid2d =
        state->neoGatherGridEnabled &&
        useSdrCreativeLutSpecialized2d &&
        state->sdrCreativeLutGatherProgram2d != 0;
    const bool sdrCreativeLutNiv99SpecializationEligible =
        state->deliveryModeLabel == "SDR" &&
        inputTransfer == 0 &&
        applyNdrPqDeliveryToneMap == 0.0f &&
        niv99WhiteBalanceArithmeticRequired &&
        sdrCreativeLutActive &&
        sdrPreGradeHistoryProven;
    const bool useSdrCreativeLutNiv99Specialized2d =
        sdrCreativeLutNiv99SpecializationEligible &&
        useDecodeIntermediate &&
        sourceWidth == targetWidth && sourceHeight == targetHeight &&
        sensorLinear2dIntegerOffsetsExact &&
        state->sdrCreativeLutNiv99Program2d != 0;
    const bool useSdrCreativeLutNiv99SpecializedExternal =
        sdrCreativeLutNiv99SpecializationEligible &&
        !useDecodeIntermediate &&
        state->sdrCreativeLutNiv99ProgramExternal != 0;
    const bool useSdrCreativeLutNiv99SpecializedMain =
        useSdrCreativeLutNiv99Specialized2d || useSdrCreativeLutNiv99SpecializedExternal;
    const bool useSdrCreativeLutNiv99GatherGrid2d =
        state->neoGatherGridEnabled &&
        useSdrCreativeLutNiv99Specialized2d &&
        state->sdrCreativeLutNiv99GatherProgram2d != 0;
    const bool cameraP010SdrPrewarmUsesCanonicalDynamicLutProgram =
        cameraP010SdrPrewarm &&
        !sdrCreativeLutBypassProven &&
        useDecodeOnce &&
        !useSdrCreativeLutSpecialized2d &&
        state->mainProgram2d != 0;
    const bool cameraP010SdrPrewarmIdentityGradeProgramReady =
        useSdrInvariantSpecialized2d ||
        useSdrCreativeLutSpecialized2d ||
        cameraP010SdrPrewarmUsesCanonicalDynamicLutProgram;
    // The first approved NIV99 packet may arrive only after the encoder gate opens. Qualcomm's
    // deferred shader finalization can otherwise charge the complete creative-LUT+NIV99 variant to
    // that live frame and block the Camera2 ImageReader long enough to lose source timestamps. Pick
    // the exact program live recording will use for a non-zero NIV99 correction. A missing
    // specialization intentionally selects the canonical program because that is the live fallback;
    // no look, equation, precision, resolution, or frame-rate contract is changed.
    const bool cameraP010SdrPrewarmUsesCreativeLut =
        cameraP010SdrPrewarm && sdrCreativeLutActive;
    const bool cameraP010SdrPrewarmUsesSpecializedNiv99Program =
        cameraP010SdrPrewarm &&
        (cameraP010SdrPrewarmUsesCreativeLut
            ? (state->sdrCreativeLutNiv99GatherProgram2d != 0 ||
               state->sdrCreativeLutNiv99Program2d != 0)
            : state->sdrNiv99Program2d != 0);
    const GLuint cameraP010SdrPrewarmNiv99Program2d =
        cameraP010SdrPrewarmUsesSpecializedNiv99Program
            ? (cameraP010SdrPrewarmUsesCreativeLut
                ? (state->sdrCreativeLutNiv99GatherProgram2d != 0
                    ? state->sdrCreativeLutNiv99GatherProgram2d
                    : state->sdrCreativeLutNiv99Program2d)
                : state->sdrNiv99Program2d)
            : state->mainProgram2d;
    const MainProgramLoc* cameraP010SdrPrewarmNiv99Loc2d =
        cameraP010SdrPrewarmUsesSpecializedNiv99Program
            ? (cameraP010SdrPrewarmUsesCreativeLut
                ? (state->sdrCreativeLutNiv99GatherProgram2d != 0
                    ? &state->sdrCreativeLutNiv99GatherLoc2d
                    : &state->sdrCreativeLutNiv99Loc2d)
                : &state->sdrNiv99Loc2d)
            : &state->mainLoc2d;
    const bool cameraP010SdrPrewarmGradeProgramReady =
        cameraP010SdrPrewarmIdentityGradeProgramReady &&
        cameraP010SdrPrewarmNiv99Program2d != 0;
    if (cameraP010SdrPrewarm && (!useDecodeOnce || !cameraP010SdrPrewarmGradeProgramReady)) {
        AHardwareBuffer_release(hardwareBuffer);
        recordFrameFailure();
        setStatus(state, "Camera2 P010 SDR prewarm exact live grade shader unavailable");
        return JNI_FALSE;
    }
    // Compile-time PQ/profile invariants are selected only when runtime evidence proves every one
    // of them exactly. This guard intentionally repeats the strict ingress identity established
    // above; a future caller/source change therefore fails closed to the canonical generic shader.
    const bool sensorLinearPqSpecializationEligible =
        inputIsSensorLinear &&
        sourceIsFp16 &&
        inputPrimaries == 3 &&
        inputRange == 3 &&
        applyNdrPqDeliveryToneMap == 1.0f &&
        sensorNdrTonePqLutReadyForFrame &&
        exactNdrDeliveryMode &&
        ndrTargetPeakNitsValue == ndrBaselinePeakNits &&
        (ndrDeliveryMode == 0 || ndrDeliveryMode == 1);
    const bool useSensorLinearSpecialized2d =
        inputIsSensorLinear && state->sensorLinearProgram2d != 0 &&
        sensorLinearPqSpecializationEligible && ndrDeliveryMode == 0 &&
        requestedTemporalBlend > 0.0f &&
        sensorLinear2dIntegerOffsetsExact;
    const bool useSensorLinearSpecializedExternal =
        inputIsSensorLinear && state->sensorLinearProgramExternal != 0 &&
        sensorLinearPqSpecializationEligible && ndrDeliveryMode == 0;
    const bool useSensorLinearNivukxSpecialized2d =
        inputIsSensorLinear && state->sensorLinearNivukxProgram2d != 0 &&
        sensorLinearPqSpecializationEligible && ndrDeliveryMode == 1 &&
        requestedTemporalBlend > 0.0f &&
        sensorLinear2dIntegerOffsetsExact;
    const bool useSensorLinearNivukxSpecializedExternal =
        inputIsSensorLinear && state->sensorLinearNivukxProgramExternal != 0 &&
        sensorLinearPqSpecializationEligible && ndrDeliveryMode == 1;
    const bool useSensorLinearPqSpecialized2d =
        useSensorLinearSpecialized2d || useSensorLinearNivukxSpecialized2d;
    const bool useSensorLinearPqSpecializedExternal =
        useSensorLinearSpecializedExternal || useSensorLinearNivukxSpecializedExternal;
    const bool useSensorLinearSpecializedMain = useDecodeIntermediate
        ? useSensorLinearPqSpecialized2d
        : useSensorLinearPqSpecializedExternal;
    // Retire any result that was not immediately available on the prior frame. This is a pure poll;
    // GL_QUERY_RESULT is read only after AVAILABLE reports true, so it cannot add a driver wait.
    // A timer result usually becomes available one submission later on tile GPUs. Preserve that
    // non-blocking result for this diagnostic log instead of discarding it at the start of the frame.
    GpuStageSample gpuStageSample = collectGpuStageTiming(state);
    const bool sampleGpuStages =
        state->gpuStageTimingAvailable &&
        state->gpuStageTimingSamplingEnabled &&
        !state->gpuStageTimingFaulted &&
        (state->submittedFrames < 12u || ((state->submittedFrames + 1u) % 60u) == 0u);
    uint64_t decodeHostUs = 0;
    uint64_t gradeHostUs = 0;
    uint64_t historyHostUs = 0;
    bool decodeGpuQueryStarted = false;
    bool gradeGpuQueryStarted = false;
    bool historyGpuQueryStarted = false;
    uint64_t finishDurationUs = 0;
    EGLSyncKHR inputReadSync = EGL_NO_SYNC_KHR;
    bool inputReadCompletedSynchronously = false;
    bool inputReadBoundaryEstablished = false;
    const char* inputReadBoundaryLabel = "UNRESOLVED";

    // A project-owned RAW/scene-log AHardwareBuffer may be reused as soon as the last GL command
    // that samples the external EGLImage has completed. On the two-pass path that command is PASS 1:
    // PASS 2 and temporal history read only the private decode texture. Keeping the fence at the end
    // of the complete grade unnecessarily serializes Vulkan ownership with independent GL work and
    // can defer retirement by one ingress poll. Direct external paths still resolve at the final
    // boundary because their grade/history shaders continue sampling the EGLImage.
    const auto establishProjectOwnedInputReadBoundary = [&](const char* boundaryLabel) {
        if (!projectOwnedInput || inputReadBoundaryEstablished) return;
        if (inputReadFenceRequested && state->inputReadFenceAvailable) {
            const EGLint syncAttrs[] = {EGL_NONE};
            inputReadSync = state->eglCreateSyncKHR(
                state->display,
                EGL_SYNC_FENCE_KHR,
                syncAttrs
            );
            if (inputReadSync != EGL_NO_SYNC_KHR) {
                glFlush();
                ++state->inputReadFenceCreated;
            } else {
                ++state->inputReadFenceSyncFallbacks;
                LOGW(
                    "RAW_INPUT_READ_FENCE_CREATE_FALLBACK receipt=%lld boundary=%s error=0x%04x",
                    static_cast<long long>(inputReadReceipt),
                    boundaryLabel,
                    static_cast<unsigned int>(eglGetError())
                );
            }
        }
        if (!inputReadFenceRequested || inputReadSync == EGL_NO_SYNC_KHR) {
            const auto finishStart = std::chrono::steady_clock::now();
            glFinish();
            finishDurationUs += static_cast<uint64_t>(
                std::chrono::duration_cast<std::chrono::microseconds>(
                    std::chrono::steady_clock::now() - finishStart
                ).count()
            );
            inputReadCompletedSynchronously = inputReadFenceRequested;
        }
        inputReadBoundaryEstablished = true;
        inputReadBoundaryLabel = boundaryLabel;
        if (useDecodeIntermediate) {
            ++state->inputReadDecodeBoundaryFrames;
        } else {
            ++state->inputReadFinalBoundaryFrames;
        }
    };
    if (!cameraP010SdrPrewarm && state->submittedFrames == 0u && inputIsSensorLinear) {
        LOGI(
            "sensor-linear main shader selected specialized=%s sampler=%s transfer=3",
            useSensorLinearSpecializedMain ? "true" : "false",
            useDecodeIntermediate ? "sampler2D" : "samplerExternalOES"
        );
    }
    if (!cameraP010SdrPrewarm && state->submittedFrames == 0u &&
        state->deliveryModeLabel == "SDR") {
        LOGI(
            "SDR main shader selected specialized=%s sampler=%s transfer=%d "
            "niv99Confidence=%.6f qualityEquations=CANONICAL",
            useSdrInvariantSpecializedMain ? "true" : "false",
            useDecodeIntermediate ? "sampler2D" : "samplerExternalOES",
            inputTransfer,
            niv99ActiveConfidenceValue
        );
    }
    const int decodeWidth = useProjectPreviewDownsample ? targetWidth : sourceWidth;
    const int decodeHeight = useProjectPreviewDownsample ? targetHeight : sourceHeight;
    const bool decodeIntermediateFp16 =
        inputIsSensorLinear || (useProjectPreviewDownsample && inputIsNeoSceneLog10);
    const bool useDecodeTextureHistory =
        useDecodeIntermediate && requestedTemporalBlend > 0.0f;
    // For the external single-pass path, keep temporal denoise active without rendering the full
    // processed frame into an intermediate texture and blitting it to the encoder surface. The shader
    // already supports pre-grade history for decode-once; here that history is a 10-bit RGB10_A2 copy
    // of the previous P010 source center sample. The current frame still runs the full external
    // denoise/sharpen/PQ shader directly into the proven RGB10_A2 encoder surface.
    const bool useExternalSourceHistory =
        !useDecodeIntermediate && requestedTemporalBlend > 0.0f && activeDecodeProgram != 0;
    const bool renderToTemporalHistory =
        requestedTemporalBlend > 0.0f && !useDecodeTextureHistory && !useExternalSourceHistory;
    const bool needsHistoryTarget = renderToTemporalHistory || useExternalSourceHistory;
    if (needsHistoryTarget &&
        !ensureTemporalHistoryTarget(state, targetWidth, targetHeight, inputIsSensorLinear)) {
        if (!state->eglImageCacheEnabled) {
            state->eglDestroyImageKHR(state->display, eglImage);
        }
        AHardwareBuffer_release(hardwareBuffer);
        recordFrameFailure();
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
    if (useDecodeIntermediate) {
        if (!cameraP010SdrPrewarm && state->submittedFrames == 0u) {
            if (useProjectPreviewDownsample) {
                LOGI(
                    "project preview anti-alias downsample ACTIVE (src=%dx%d -> surface=%dx%d) "
                    "two-pass RGBA16F path; canonical NDR/PQ pass unchanged",
                    sourceWidth,
                    sourceHeight,
                    targetWidth,
                    targetHeight
                );
            } else {
                LOGI(
                    "decode-once ACTIVE (src=%dx%d == surface) two-pass %s record path",
                    sourceWidth,
                    sourceHeight,
                    decodeIntermediateFp16 ? "RGBA16F" : "RGB10_A2"
                );
            }
            if (useDecodeTextureHistory) {
                if (decodeIntermediateFp16) {
                    LOGI("RGBA16F decode-history temporal blend ACTIVE (copy-free texture swap)");
                } else {
                    LOGI("RGB10_A2 decode-history temporal blend ACTIVE (copy-free texture swap)");
                }
            }
        }
        // Same-size P010 decode-once remains RGB10_A2. Project-owned RAW/Neo-scene preview
        // minification uses a target-size RGBA16F intermediate so scene-linear range and precision
        // survive the low-pass pass before the unchanged canonical NDR/PQ shader.
        if (state->decodeTexture == 0) glGenTextures(1, &state->decodeTexture);
        if (state->decodeHistoryTexture == 0) glGenTextures(1, &state->decodeHistoryTexture);
        const bool decodeSpareWasMissing = state->decodeSpareTexture == 0;
        if (decodeSpareWasMissing) glGenTextures(1, &state->decodeSpareTexture);
        bool decodeTargetReconfigured = false;
        if (state->decodeTexWidth != decodeWidth || state->decodeTexHeight != decodeHeight ||
            state->decodeFp16 != decodeIntermediateFp16) {
            const bool decodeTextureReady = configureHistoryTexture(
                state->decodeTexture,
                decodeWidth,
                decodeHeight,
                decodeIntermediateFp16
            );
            const bool decodeHistoryReady = configureHistoryTexture(
                state->decodeHistoryTexture,
                decodeWidth,
                decodeHeight,
                decodeIntermediateFp16
            );
            const bool decodeSpareReady = state->decodeSpareTexture != 0 && configureHistoryTexture(
                state->decodeSpareTexture,
                decodeWidth,
                decodeHeight,
                decodeIntermediateFp16
            );
            if (!decodeTextureReady || !decodeHistoryReady) {
                if (!state->eglImageCacheEnabled) {
                    state->eglDestroyImageKHR(state->display, eglImage);
                }
                AHardwareBuffer_release(hardwareBuffer);
                recordFrameFailure();
                setStatus(
                    state,
                    decodeIntermediateFp16
                        ? "RGBA16F decode intermediate allocation failed"
                        : "RGB10_A2 decode intermediate allocation failed"
                );
                return JNI_FALSE;
            }
            state->decodeTexWidth = decodeWidth;
            state->decodeTexHeight = decodeHeight;
            state->decodeFp16 = decodeIntermediateFp16;
            state->decodeHistoryValid = false;
            state->decodeTripleBufferReady = decodeSpareReady;
            if (!decodeSpareReady && state->decodeSpareTexture != 0) {
                glDeleteTextures(1, &state->decodeSpareTexture);
                state->decodeSpareTexture = 0;
                while (glGetError() != GL_NO_ERROR) {
                    // Optional scheduling resource failed; retain the proven two-texture path.
                }
            }
            decodeTargetReconfigured = true;
        } else if (decodeSpareWasMissing && state->decodeSpareTexture != 0) {
            state->decodeTripleBufferReady = configureHistoryTexture(
                state->decodeSpareTexture,
                decodeWidth,
                decodeHeight,
                decodeIntermediateFp16
            );
            if (!state->decodeTripleBufferReady) {
                glDeleteTextures(1, &state->decodeSpareTexture);
                state->decodeSpareTexture = 0;
                while (glGetError() != GL_NO_ERROR) {
                    // Optional scheduling resource failed; retain the proven two-texture path.
                }
            }
        }
        if (state->decodeFbo == 0) {
            glGenFramebuffers(1, &state->decodeFbo);
            decodeTargetReconfigured = true;
        }

        // PASS 1 — decode once, applying a ratio-derived low-pass footprint only for the
        // project-preview minification route.
        const auto decodeHostStart = std::chrono::steady_clock::now();
        glBindFramebuffer(GL_FRAMEBUFFER, state->decodeFbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D,
                               state->decodeTexture, 0);
        if (decodeTargetReconfigured) {
            const GLenum decodeFramebufferStatus = glCheckFramebufferStatus(GL_FRAMEBUFFER);
            if (decodeFramebufferStatus != GL_FRAMEBUFFER_COMPLETE) {
                if (!state->eglImageCacheEnabled) {
                    state->eglDestroyImageKHR(state->display, eglImage);
                }
                AHardwareBuffer_release(hardwareBuffer);
                recordFrameFailure();
                std::ostringstream message;
                message << (decodeIntermediateFp16 ? "RGBA16F" : "RGB10_A2")
                        << " decode framebuffer incomplete status=0x"
                        << std::hex << decodeFramebufferStatus;
                state->decodeTexWidth = 0;
                state->decodeTexHeight = 0;
                state->decodeHistoryValid = false;
                setStatus(state, message.str());
                glBindFramebuffer(GL_FRAMEBUFFER, 0);
                return JNI_FALSE;
            }
        }
        glViewport(0, 0, decodeWidth, decodeHeight);
        glUseProgram(activeDecodeProgram);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, state->sourceTexture);
        glUniform1i(activeDecodeTextureLocation, 0);
        glUniform2f(
            activeDecodeSourceTexelLocation,
            1.0f / static_cast<float>(sourceWidth),
            1.0f / static_cast<float>(sourceHeight)
        );
        glUniform2f(
            activeDecodeMinificationScaleLocation,
            useProjectPreviewDownsample
                ? static_cast<float>(sourceWidth) / static_cast<float>(decodeWidth)
                : 1.0f,
            useProjectPreviewDownsample
                ? static_cast<float>(sourceHeight) / static_cast<float>(decodeHeight)
                : 1.0f
        );
        glUniform1i(activeDecodePreserveExtendedRangeLocation, inputIsSensorLinear ? 1 : 0);
        if (useExplicitYuv) {
            glUniform1i(state->decodeExplicitYuvPrimariesLoc, inputPrimaries);
            glUniform1i(state->decodeExplicitYuvRangeLoc, inputRange);
        }
        glBindBuffer(GL_ARRAY_BUFFER, state->vertexBuffer);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), reinterpret_cast<void*>(0));
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), reinterpret_cast<void*>(2 * sizeof(GLfloat)));
        if (sampleGpuStages) {
            decodeGpuQueryStarted = beginGpuStageTiming(state, GpuStage::Decode);
        }
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        endGpuStageTiming(state, GpuStage::Decode, decodeGpuQueryStarted);
        decodeHostUs = static_cast<uint64_t>(
            std::chrono::duration_cast<std::chrono::microseconds>(
                std::chrono::steady_clock::now() - decodeHostStart
            ).count()
        );
        establishProjectOwnedInputReadBoundary("DECODE_INTERMEDIATE");

        // PASS 2 — full grade/denoise/sharpen/NDR shader sampling the decode intermediate
        // (sampler2D) into the destination surface. Its color math and uniform values are unchanged.
        const auto gradeHostStart = std::chrono::steady_clock::now();
        if (cameraP010SdrPrewarm) {
            // Run the live SDR program at the recording extent, but retain its output in the private
            // RGB10 scratch texture. The MediaCodec default framebuffer is never touched.
            glBindFramebuffer(GL_FRAMEBUFFER, state->decodeFbo);
            glFramebufferTexture2D(
                GL_FRAMEBUFFER,
                GL_COLOR_ATTACHMENT0,
                GL_TEXTURE_2D,
                state->decodeHistoryTexture,
                0);
            const GLenum scratchStatus = glCheckFramebufferStatus(GL_FRAMEBUFFER);
            if (scratchStatus != GL_FRAMEBUFFER_COMPLETE) {
                glBindFramebuffer(GL_FRAMEBUFFER, 0);
                AHardwareBuffer_release(hardwareBuffer);
                recordFrameFailure();
                std::ostringstream message;
                message << "Camera2 P010 SDR prewarm grade framebuffer incomplete status=0x"
                        << std::hex << scratchStatus;
                setStatus(state, message.str());
                return JNI_FALSE;
            }
        } else {
            glBindFramebuffer(GL_FRAMEBUFFER, renderToTemporalHistory ? state->historyFbo : 0);
        }
        glViewport(0, 0, targetWidth, targetHeight);
        const GLuint sensorLinearNdr10OrGenericProgram2d = useSensorLinearSpecialized2d
            ? state->sensorLinearProgram2d
            : state->mainProgram2d;
        const MainProgramLoc& sensorLinearNdr10OrGenericLoc2d = useSensorLinearSpecialized2d
            ? state->sensorLinearLoc2d
            : state->mainLoc2d;
        const GLuint sensorLinearOrGenericProgram2d = useSensorLinearNivukxSpecialized2d
            ? state->sensorLinearNivukxProgram2d
            : sensorLinearNdr10OrGenericProgram2d;
        const MainProgramLoc& sensorLinearOrGenericLoc2d = useSensorLinearNivukxSpecialized2d
            ? state->sensorLinearNivukxLoc2d
            : sensorLinearNdr10OrGenericLoc2d;
        const GLuint sdrInvariantOrGenericProgram2d = useSdrInvariantSpecialized2d
            ? state->sdrInvariantProgram2d
            : sensorLinearOrGenericProgram2d;
        const MainProgramLoc& sdrInvariantOrGenericLoc2d = useSdrInvariantSpecialized2d
            ? state->sdrInvariantLoc2d
            : sensorLinearOrGenericLoc2d;
        const GLuint sdrNiv99OrGenericProgram2d = useSdrNiv99Specialized2d
            ? state->sdrNiv99Program2d
            : sdrInvariantOrGenericProgram2d;
        const MainProgramLoc& sdrNiv99OrGenericLoc2d = useSdrNiv99Specialized2d
            ? state->sdrNiv99Loc2d
            : sdrInvariantOrGenericLoc2d;
        const GLuint sdrCreativeLutOrGenericProgram2d = useSdrCreativeLutSpecialized2d
            ? (useSdrCreativeLutGatherGrid2d
                ? state->sdrCreativeLutGatherProgram2d
                : state->sdrCreativeLutProgram2d)
            : sdrNiv99OrGenericProgram2d;
        const MainProgramLoc& sdrCreativeLutOrGenericLoc2d = useSdrCreativeLutSpecialized2d
            ? (useSdrCreativeLutGatherGrid2d
                ? state->sdrCreativeLutGatherLoc2d
                : state->sdrCreativeLutLoc2d)
            : sdrNiv99OrGenericLoc2d;
        const GLuint activeMainProgram2d = useSdrCreativeLutNiv99Specialized2d
            ? (useSdrCreativeLutNiv99GatherGrid2d
                ? state->sdrCreativeLutNiv99GatherProgram2d
                : state->sdrCreativeLutNiv99Program2d)
            : sdrCreativeLutOrGenericProgram2d;
        const MainProgramLoc& activeMainLoc2d = useSdrCreativeLutNiv99Specialized2d
            ? (useSdrCreativeLutNiv99GatherGrid2d
                ? state->sdrCreativeLutNiv99GatherLoc2d
                : state->sdrCreativeLutNiv99Loc2d)
            : sdrCreativeLutOrGenericLoc2d;
        glUseProgram(activeMainProgram2d);

        float gradeMat2[9];
        packGradeMatrixColumnMajor(state->recordGradeMatrix, gradeMat2);
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
        glUniform1i(activeMainLoc2d.texture, 0);
        glUniform2f(activeMainLoc2d.texel, 1.0f / static_cast<float>(decodeWidth),
                    1.0f / static_cast<float>(decodeHeight));
        glUniform1f(activeMainLoc2d.sharpness, std::clamp(static_cast<float>(sharpness), 0.0f, 1.0f));
        glUniform1f(activeMainLoc2d.denoise, std::clamp(static_cast<float>(denoise), 0.0f, 1.0f));
        glUniform1f(activeMainLoc2d.chromaDenoise, std::clamp(static_cast<float>(chromaDenoise), 0.0f, 1.0f));
        uploadNeoConstantLift(activeMainLoc2d, neoConstantLift);
        glUniform3f(
            activeMainLoc2d.niv99RgbCorrection,
            niv99RgbCorrectionValue[0],
            niv99RgbCorrectionValue[1],
            niv99RgbCorrectionValue[2]);
        glUniform1f(activeMainLoc2d.niv99ActiveConfidence, niv99ActiveConfidenceValue);
        glUniformMatrix3fv(activeMainLoc2d.gradeMatrix, 1, GL_FALSE, gradeMat2);
        glUniform1f(activeMainLoc2d.gradeContrast, gradeContrastF2);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_3D, state->lutTexture != 0 ? state->lutTexture : state->defaultLut3d);
        glUniform1i(activeMainLoc2d.lut, 1);
        const float effLutStrength2 =
            (state->deliveryModeLabel == "SDR" && state->lutTexture != 0)
                ? std::clamp(state->lutStrength, 0.0f, 1.0f)
                : 0.0f;
        glUniform1f(activeMainLoc2d.lutStrength, effLutStrength2);
        const bool historyUsable2 = useDecodeTextureHistory &&
            state->decodeHistoryValid &&
            state->decodeHistoryTexture != 0 &&
            state->decodeTexWidth == decodeWidth &&
            state->decodeTexHeight == decodeHeight;
        glActiveTexture(GL_TEXTURE2);
        glBindTexture(
            GL_TEXTURE_2D,
            historyUsable2 ? state->decodeHistoryTexture : state->defaultHistory2d
        );
        glUniform1i(activeMainLoc2d.history, 2);
        glUniform1f(activeMainLoc2d.temporalBlend, tb2);
        glUniform1f(activeMainLoc2d.historyValid, historyUsable2 ? 1.0f : 0.0f);
        glUniform1f(activeMainLoc2d.historyPreGrade, useDecodeTextureHistory ? 1.0f : 0.0f);
        glUniform1f(activeMainLoc2d.hdrToPqConvert, applyNdrPqDeliveryToneMap);
        glUniform1f(activeMainLoc2d.ndrDeliveryMode, ndrDeliveryModeValue);
        glUniform1f(activeMainLoc2d.ndrTargetPeakNits, ndrTargetPeakNitsValue);
        const GLuint ndrPqLut2 =
            ndrDeliveryModeValue > 0.5f ? state->ndrPqLutNivukx : state->ndrPqLutNdr10;
        const bool ndrPqLutReady2 =
            state->deliveryModeLabel == "PQ" &&
            inputTransfer == 2 &&
            ndrPqLut2 != 0 &&
            std::abs(ndrTargetPeakNitsValue - ndrBaselinePeakNits) < 0.5f;
        glActiveTexture(GL_TEXTURE3);
        glBindTexture(
            GL_TEXTURE_3D,
            ndrPqLutReady2 ? ndrPqLut2 : state->defaultLut3d
        );
        glUniform1i(activeMainLoc2d.ndrPqLut, 3);
        glUniform1f(activeMainLoc2d.ndrPqLutReady, ndrPqLutReady2 ? 1.0f : 0.0f);
        glActiveTexture(GL_TEXTURE4);
        glBindTexture(
            GL_TEXTURE_2D,
            sensorNdrTonePqLutReadyForFrame
                ? state->ndrSensorTonePqLut
                : state->defaultHistory2d
        );
        glUniform1i(activeMainLoc2d.ndrSensorTonePqLut, 4);
        glUniform1f(
            activeMainLoc2d.ndrSensorTonePqLutReady,
            sensorNdrTonePqLutReadyForFrame ? 1.0f : 0.0f
        );
        glUniform1i(activeMainLoc2d.inputTransfer, inputTransfer);
        glUniform1f(activeMainLoc2d.ndrWarmSkinRedTame, ndrWarmSkinRedTameValue);
        glUniform4fv(activeMainLoc2d.ndrMeasuredRgbScale, 1, state->ndrMeasuredRgbScale);
        glUniform4fv(activeMainLoc2d.ndrMeasuredTone, 1, state->ndrMeasuredTone);
        glUniform2fv(activeMainLoc2d.ndrMeasuredHue, 1, state->ndrMeasuredHue);
        glUniform4fv(activeMainLoc2d.ndrToneProfile, 1, state->ndrToneProfile);
        glUniform4fv(activeMainLoc2d.ndrCharacterProfile, 1, state->ndrCharacterProfile);
        glUniform4fv(activeMainLoc2d.ndrDetailProfile, 1, state->ndrDetailProfile);
        // MIRRORFIX v5 — apply the output flip once on PASS 2 (the encoder-writing pass). PASS 1
        // (decodeProgram) deliberately leaves uEncOutFlip at its 0 default so the decode copy stays
        // identity and the flip is applied exactly once across the two-pass decode-once path.
        glUniform2f(activeMainLoc2d.encOutFlip, state->encOutFlipX, state->encOutFlipY);
        glActiveTexture(GL_TEXTURE0);
        glBindBuffer(GL_ARRAY_BUFFER, state->vertexBuffer);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), reinterpret_cast<void*>(0));
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), reinterpret_cast<void*>(2 * sizeof(GLfloat)));
        if (sampleGpuStages) {
            gradeGpuQueryStarted = beginGpuStageTiming(state, GpuStage::Grade);
        }
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        endGpuStageTiming(state, GpuStage::Grade, gradeGpuQueryStarted);
        if (cameraP010SdrPrewarm) {
            // PRIVATE_NIV99_SHADER_PREWARM_BEGIN
            // Draw the exact NIV99 program selected by a live approved packet while all output is
            // still attached to the private RGB10 scratch texture. Identity RGB multipliers with
            // non-zero confidence execute the NIV99 branch without changing the warmed pixels. This
            // forces deferred driver compilation/linking before the encoder gate opens and prevents
            // the first live NIV99 frame from paying a multi-frame shader activation stall.
            const MainProgramLoc& niv99PrewarmLoc = *cameraP010SdrPrewarmNiv99Loc2d;
            glUseProgram(cameraP010SdrPrewarmNiv99Program2d);
            glUniform1i(niv99PrewarmLoc.texture, 0);
            glUniform2f(
                niv99PrewarmLoc.texel,
                1.0f / static_cast<float>(decodeWidth),
                1.0f / static_cast<float>(decodeHeight));
            glUniform1f(
                niv99PrewarmLoc.sharpness,
                std::clamp(static_cast<float>(sharpness), 0.0f, 1.0f));
            glUniform1f(
                niv99PrewarmLoc.denoise,
                std::clamp(static_cast<float>(denoise), 0.0f, 1.0f));
            glUniform1f(
                niv99PrewarmLoc.chromaDenoise,
                std::clamp(static_cast<float>(chromaDenoise), 0.0f, 1.0f));
            uploadNeoConstantLift(niv99PrewarmLoc, neoConstantLift);
            glUniform3f(niv99PrewarmLoc.niv99RgbCorrection, 1.0f, 1.0f, 1.0f);
            glUniform1f(niv99PrewarmLoc.niv99ActiveConfidence, 1.0f);
            glUniformMatrix3fv(niv99PrewarmLoc.gradeMatrix, 1, GL_FALSE, gradeMat2);
            glUniform1f(niv99PrewarmLoc.gradeContrast, gradeContrastF2);
            glUniform1i(niv99PrewarmLoc.lut, 1);
            glUniform1f(niv99PrewarmLoc.lutStrength, effLutStrength2);
            glUniform1i(niv99PrewarmLoc.history, 2);
            glUniform1f(niv99PrewarmLoc.temporalBlend, tb2);
            glUniform1f(niv99PrewarmLoc.historyValid, historyUsable2 ? 1.0f : 0.0f);
            glUniform1f(
                niv99PrewarmLoc.historyPreGrade,
                useDecodeTextureHistory ? 1.0f : 0.0f);
            glUniform1f(niv99PrewarmLoc.hdrToPqConvert, applyNdrPqDeliveryToneMap);
            glUniform1f(niv99PrewarmLoc.ndrDeliveryMode, ndrDeliveryModeValue);
            glUniform1f(niv99PrewarmLoc.ndrTargetPeakNits, ndrTargetPeakNitsValue);
            glUniform1i(niv99PrewarmLoc.ndrPqLut, 3);
            glUniform1f(niv99PrewarmLoc.ndrPqLutReady, ndrPqLutReady2 ? 1.0f : 0.0f);
            glUniform1i(niv99PrewarmLoc.ndrSensorTonePqLut, 4);
            glUniform1f(
                niv99PrewarmLoc.ndrSensorTonePqLutReady,
                sensorNdrTonePqLutReadyForFrame ? 1.0f : 0.0f);
            glUniform1i(niv99PrewarmLoc.inputTransfer, inputTransfer);
            glUniform1f(niv99PrewarmLoc.ndrWarmSkinRedTame, ndrWarmSkinRedTameValue);
            glUniform4fv(
                niv99PrewarmLoc.ndrMeasuredRgbScale, 1, state->ndrMeasuredRgbScale);
            glUniform4fv(niv99PrewarmLoc.ndrMeasuredTone, 1, state->ndrMeasuredTone);
            glUniform2fv(niv99PrewarmLoc.ndrMeasuredHue, 1, state->ndrMeasuredHue);
            glUniform4fv(niv99PrewarmLoc.ndrToneProfile, 1, state->ndrToneProfile);
            glUniform4fv(
                niv99PrewarmLoc.ndrCharacterProfile, 1, state->ndrCharacterProfile);
            glUniform4fv(niv99PrewarmLoc.ndrDetailProfile, 1, state->ndrDetailProfile);
            glUniform2f(
                niv99PrewarmLoc.encOutFlip, state->encOutFlipX, state->encOutFlipY);
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
            // PRIVATE_NIV99_SHADER_PREWARM_END
        }
        glDisableVertexAttribArray(0);
        glDisableVertexAttribArray(1);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindTexture(GL_TEXTURE_2D, 0);
        gradeHostUs = static_cast<uint64_t>(
            std::chrono::duration_cast<std::chrono::microseconds>(
                std::chrono::steady_clock::now() - gradeHostStart
            ).count()
        );
        if (useDecodeTextureHistory && !cameraP010SdrPrewarm) {
            if (state->decodeTripleBufferReady && state->decodeSpareTexture != 0) {
                const GLuint completedDecodeTexture = state->decodeTexture;
                state->decodeTexture = state->decodeSpareTexture;
                state->decodeSpareTexture = state->decodeHistoryTexture;
                state->decodeHistoryTexture = completedDecodeTexture;
                ++state->decodeTripleBufferedFrames;
            } else {
                std::swap(state->decodeTexture, state->decodeHistoryTexture);
            }
            state->decodeHistoryValid = true;
            ++state->temporalHistoryFrames;
        }
    } else {
    if (!cameraP010SdrPrewarm && state->submittedFrames == 0u) {
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
    const auto gradeHostStart = std::chrono::steady_clock::now();
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
    const GLuint sensorLinearNdr10OrGenericProgram = useSensorLinearSpecializedExternal
        ? state->sensorLinearProgramExternal
        : (useExplicitYuv ? state->mainProgramExplicitYuv : state->program);
    const MainProgramLoc& sensorLinearNdr10OrGenericLoc = useSensorLinearSpecializedExternal
        ? state->sensorLinearLocExternal
        : (useExplicitYuv ? state->mainLocExplicitYuv : state->mainLocExternal);
    const GLuint sensorLinearOrGenericProgram = useSensorLinearNivukxSpecializedExternal
        ? state->sensorLinearNivukxProgramExternal
        : sensorLinearNdr10OrGenericProgram;
    const MainProgramLoc& sensorLinearOrGenericLoc = useSensorLinearNivukxSpecializedExternal
        ? state->sensorLinearNivukxLocExternal
        : sensorLinearNdr10OrGenericLoc;
    const GLuint sdrInvariantOrGenericProgram = useSdrInvariantSpecializedExternal
        ? state->sdrInvariantProgramExternal
        : sensorLinearOrGenericProgram;
    const MainProgramLoc& sdrInvariantOrGenericLoc = useSdrInvariantSpecializedExternal
        ? state->sdrInvariantLocExternal
        : sensorLinearOrGenericLoc;
    const GLuint sdrNiv99OrGenericProgram = useSdrNiv99SpecializedExternal
        ? state->sdrNiv99ProgramExternal
        : sdrInvariantOrGenericProgram;
    const MainProgramLoc& sdrNiv99OrGenericLoc = useSdrNiv99SpecializedExternal
        ? state->sdrNiv99LocExternal
        : sdrInvariantOrGenericLoc;
    const GLuint sdrCreativeLutOrGenericProgram = useSdrCreativeLutSpecializedExternal
        ? state->sdrCreativeLutProgramExternal
        : sdrNiv99OrGenericProgram;
    const MainProgramLoc& sdrCreativeLutOrGenericLoc = useSdrCreativeLutSpecializedExternal
        ? state->sdrCreativeLutLocExternal
        : sdrNiv99OrGenericLoc;
    const GLuint activeMainProgram = useSdrCreativeLutNiv99SpecializedExternal
        ? state->sdrCreativeLutNiv99ProgramExternal
        : sdrCreativeLutOrGenericProgram;
    const MainProgramLoc& activeMainLoc = useSdrCreativeLutNiv99SpecializedExternal
        ? state->sdrCreativeLutNiv99LocExternal
        : sdrCreativeLutOrGenericLoc;
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
    glUniform3f(
        activeMainLoc.niv99RgbCorrection,
        niv99RgbCorrectionValue[0],
        niv99RgbCorrectionValue[1],
        niv99RgbCorrectionValue[2]);
    glUniform1f(activeMainLoc.niv99ActiveConfidence, niv99ActiveConfidenceValue);

    // ── Record-grade stage (identity/OFF by default) ────────────────────────────────────
    // Grade state remains row-major; pack column-major because GLES requires transpose=GL_FALSE.
    float gradeMat[9];
    packGradeMatrixColumnMajor(state->recordGradeMatrix, gradeMat);
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

    glUniformMatrix3fv(activeMainLoc.gradeMatrix, 1, GL_FALSE, gradeMat);
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
    glUniform1f(activeMainLoc.ndrTargetPeakNits, ndrTargetPeakNitsValue);
    const GLuint ndrPqLut =
        ndrDeliveryModeValue > 0.5f ? state->ndrPqLutNivukx : state->ndrPqLutNdr10;
    const bool ndrPqLutReady =
        state->deliveryModeLabel == "PQ" &&
        inputTransfer == 2 &&
        ndrPqLut != 0 &&
        std::abs(ndrTargetPeakNitsValue - ndrBaselinePeakNits) < 0.5f;
    glActiveTexture(GL_TEXTURE3);
    glBindTexture(
        GL_TEXTURE_3D,
        ndrPqLutReady ? ndrPqLut : state->defaultLut3d
    );
    glUniform1i(activeMainLoc.ndrPqLut, 3);
    glUniform1f(activeMainLoc.ndrPqLutReady, ndrPqLutReady ? 1.0f : 0.0f);
    glActiveTexture(GL_TEXTURE4);
    glBindTexture(
        GL_TEXTURE_2D,
        sensorNdrTonePqLutReadyForFrame
            ? state->ndrSensorTonePqLut
            : state->defaultHistory2d
    );
    glUniform1i(activeMainLoc.ndrSensorTonePqLut, 4);
    glUniform1f(
        activeMainLoc.ndrSensorTonePqLutReady,
        sensorNdrTonePqLutReadyForFrame ? 1.0f : 0.0f
    );
    glUniform1i(activeMainLoc.inputTransfer, inputTransfer);
    glUniform1f(activeMainLoc.ndrWarmSkinRedTame, ndrWarmSkinRedTameValue);
    glUniform4fv(activeMainLoc.ndrMeasuredRgbScale, 1, state->ndrMeasuredRgbScale);
    glUniform4fv(activeMainLoc.ndrMeasuredTone, 1, state->ndrMeasuredTone);
    glUniform2fv(activeMainLoc.ndrMeasuredHue, 1, state->ndrMeasuredHue);
    glUniform4fv(activeMainLoc.ndrToneProfile, 1, state->ndrToneProfile);
    glUniform4fv(activeMainLoc.ndrCharacterProfile, 1, state->ndrCharacterProfile);
    glUniform4fv(activeMainLoc.ndrDetailProfile, 1, state->ndrDetailProfile);
    glActiveTexture(GL_TEXTURE0);

    glBindBuffer(GL_ARRAY_BUFFER, state->vertexBuffer);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), reinterpret_cast<void*>(0));
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), reinterpret_cast<void*>(2 * sizeof(GLfloat)));
    if (sampleGpuStages) {
        gradeGpuQueryStarted = beginGpuStageTiming(state, GpuStage::Grade);
    }
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
    endGpuStageTiming(state, GpuStage::Grade, gradeGpuQueryStarted);
    glDisableVertexAttribArray(0);
    glDisableVertexAttribArray(1);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, 0);
    gradeHostUs = static_cast<uint64_t>(
        std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::steady_clock::now() - gradeHostStart
        ).count()
    );
    }  // end external-path else (decode intermediate handled above)

    // CAMERA_P010_SDR_PREWARM_NO_PRESENT_BEGIN
    if (cameraP010SdrPrewarm) {
        // PASS 2 targeted decodeHistoryTexture above. Complete every external-image read before the
        // Java Image is closed, then deliberately invalidate both temporal histories: warm-up pixels
        // may compile/cache resources, but can never influence frame zero of the real clip.
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glFinish();
        state->historyValid = false;
        state->decodeHistoryValid = false;
        const GLenum prewarmError = glGetError();
        if (prewarmError != GL_NO_ERROR) {
            AHardwareBuffer_release(hardwareBuffer);
            recordFrameFailure();
            std::ostringstream message;
            message << "Camera2 P010 SDR full-grade prewarm GL error=0x"
                    << std::hex << prewarmError;
            setStatus(state, message.str());
            return JNI_FALSE;
        }
        if (!submitEglUnbind.unbind()) {
            AHardwareBuffer_release(hardwareBuffer);
            recordFrameFailure();
            std::ostringstream message;
            message << "Camera2 P010 SDR prewarm cleanup failed error=0x"
                    << std::hex << submitEglUnbind.lastError();
            setStatus(state, message.str());
            return JNI_FALSE;
        }
        AHardwareBuffer_release(hardwareBuffer);

        const uint64_t durationUs = static_cast<uint64_t>(
            std::chrono::duration_cast<std::chrono::microseconds>(
                std::chrono::steady_clock::now() - prewarmStarted
            ).count());
        ++state->cameraP010SdrPrewarmSuccesses;
        ++state->cameraP010SdrPrewarmIdentityShaderDraws;
        ++state->cameraP010SdrPrewarmNiv99ShaderDraws;
        state->cameraP010SdrPrewarmTotalUs += durationUs;
        state->cameraP010SdrPrewarmMaxUs = std::max(
            state->cameraP010SdrPrewarmMaxUs,
            durationUs);
        state->cameraP010SdrPrewarmLastBufferId = eglImageCacheKey;
        if (std::find(
                state->cameraP010SdrPrewarmBufferIds.begin(),
                state->cameraP010SdrPrewarmBufferIds.end(),
                eglImageCacheKey
            ) == state->cameraP010SdrPrewarmBufferIds.end()) {
            state->cameraP010SdrPrewarmBufferIds.push_back(eglImageCacheKey);
        }
        const char* prewarmGradeProgram = useSdrCreativeLutSpecialized2d
            ? "sdr-specialized-dynamic-lut"
            : (cameraP010SdrPrewarmUsesCanonicalDynamicLutProgram
                ? "canonical-dynamic-lut-fallback"
                : "sdr-invariant-zero-lut");
        const char* prewarmNiv99GradeProgram = cameraP010SdrPrewarmUsesSpecializedNiv99Program
            ? (cameraP010SdrPrewarmUsesCreativeLut
                ? "sdr-specialized-dynamic-lut-niv99"
                : "sdr-specialized-zero-lut-niv99")
            : "canonical-dynamic-niv99-fallback";
        setStatus(
            state,
            std::string("Camera2 P010 SDR full-grade prewarmed program=") +
                prewarmGradeProgram + "+" + prewarmNiv99GradeProgram);
        LOGI(
            "CAMERA_P010_SDR_PREWARM success=true frame=%llu distinctBuffers=%zu id=%llu "
            "extent=%dx%d decodeHostUs=%llu gradeHostUs=%llu elapsedUs=%llu "
            "identityGradeProgram=%s niv99GradeProgram=%s "
            "identityShaderDraw=true niv99ShaderDraw=true creativeLutStrength=%.6f "
            "swap=false presentation=false historyPublished=false",
            static_cast<unsigned long long>(state->cameraP010SdrPrewarmSuccesses),
            state->cameraP010SdrPrewarmBufferIds.size(),
            static_cast<unsigned long long>(eglImageCacheKey),
            width,
            height,
            static_cast<unsigned long long>(decodeHostUs),
            static_cast<unsigned long long>(gradeHostUs),
            static_cast<unsigned long long>(durationUs),
            prewarmGradeProgram,
            prewarmNiv99GradeProgram,
            static_cast<double>(state->lutStrength));
        return JNI_TRUE;
    }
    // CAMERA_P010_SDR_PREWARM_NO_PRESENT_END

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
        glUniform2f(
            activeDecodeSourceTexelLocation,
            1.0f / static_cast<float>(sourceWidth),
            1.0f / static_cast<float>(sourceHeight)
        );
        glUniform2f(activeDecodeMinificationScaleLocation, 1.0f, 1.0f);
        glUniform1i(activeDecodePreserveExtendedRangeLocation, inputIsSensorLinear ? 1 : 0);
        if (useExplicitYuv) {
            glUniform1i(state->decodeExplicitYuvPrimariesLoc, inputPrimaries);
            glUniform1i(state->decodeExplicitYuvRangeLoc, inputRange);
        }
        glBindBuffer(GL_ARRAY_BUFFER, state->vertexBuffer);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), reinterpret_cast<void*>(0));
        glEnableVertexAttribArray(1);
        glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(GLfloat), reinterpret_cast<void*>(2 * sizeof(GLfloat)));
        if (sampleGpuStages) {
            historyGpuQueryStarted = beginGpuStageTiming(state, GpuStage::History);
        }
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        endGpuStageTiming(state, GpuStage::History, historyGpuQueryStarted);
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
        historyHostUs = historyPresentUs;
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
        if (sampleGpuStages) {
            historyGpuQueryStarted = beginGpuStageTiming(state, GpuStage::History);
        }
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
        endGpuStageTiming(state, GpuStage::History, historyGpuQueryStarted);
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
            if (inputIsSensorLinear || inputIsNeoSceneLog10) {
                // The blit error is reported after project-owned input sampling has already been
                // queued. Kotlin may retire a failed preview/record submission immediately, so prove
                // that every GL read is complete before the shared Vulkan transport slot can be
                // acknowledged and reused.
                glFinish();
                if (inputReadSync != EGL_NO_SYNC_KHR) {
                    if (state->eglDestroySyncKHR == nullptr ||
                        state->eglDestroySyncKHR(state->display, inputReadSync) != EGL_TRUE) {
                        ++state->inputReadFenceFailures;
                        LOGE(
                            "Uncommitted input-read fence destruction failed after history error "
                            "receipt=%lld boundary=%s error=0x%04x",
                            static_cast<long long>(inputReadReceipt),
                            inputReadBoundaryLabel,
                            static_cast<unsigned int>(eglGetError())
                        );
                    }
                    inputReadSync = EGL_NO_SYNC_KHR;
                }
            }
            if (!state->eglImageCacheEnabled) {
                state->eglDestroyImageKHR(state->display, eglImage);
            }
            AHardwareBuffer_release(hardwareBuffer);
            recordFrameFailure();
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
        historyHostUs = historyPresentUs;
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

    if (projectOwnedInput) {
        establishProjectOwnedInputReadBoundary("FINAL_EXTERNAL_READ");
        const GpuStageSample headGpuStageSample = gpuStageSample;
        gpuStageSample = collectGpuStageTiming(state);
        gpuStageSample.disjoint = gpuStageSample.disjoint || headGpuStageSample.disjoint;
        for (size_t stage = 0; stage < kGpuStageCount; ++stage) {
            if (gpuStageSample.valid[stage] || !headGpuStageSample.valid[stage]) continue;
            gpuStageSample.elapsedUs[stage] = headGpuStageSample.elapsedUs[stage];
            gpuStageSample.valid[stage] = true;
        }
    }
    const auto swapStart = std::chrono::steady_clock::now();
    const EGLBoolean swapped = eglSwapBuffers(state->display, state->encoderSurface);
    const auto swapDurationUs = static_cast<uint64_t>(
        std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::steady_clock::now() - swapStart
        ).count()
    );
    if (swapped != EGL_TRUE) {
        if (inputReadSync != EGL_NO_SYNC_KHR) {
            const EGLint waitResult = state->eglClientWaitSyncKHR(
                state->display,
                inputReadSync,
                0,
                EGL_FOREVER_KHR
            );
            if (waitResult != EGL_CONDITION_SATISFIED_KHR) glFinish();
            if (state->eglDestroySyncKHR(state->display, inputReadSync) != EGL_TRUE) {
                ++state->inputReadFenceFailures;
                LOGE(
                    "Uncommitted input-read fence destruction failed after swap error "
                    "receipt=%lld boundary=%s error=0x%04x",
                    static_cast<long long>(inputReadReceipt),
                    inputReadBoundaryLabel,
                    static_cast<unsigned int>(eglGetError())
                );
            }
            inputReadSync = EGL_NO_SYNC_KHR;
        }
        // No receipt is committed on a failed swap. Retire the exact GL read first, then release the
        // imported source so Kotlin can safely return the project-owned RAW lane immediately.
        if (!state->eglImageCacheEnabled) {
            state->eglDestroyImageKHR(state->display, eglImage);
        }
        AHardwareBuffer_release(hardwareBuffer);
        recordFrameFailure();
        setStatus(state, "eglSwapBuffers failed");
        return JNI_FALSE;
    }
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
    if (inputReadFenceRequested) {
        // Commit the receipt only after the irreversible MediaCodec swap succeeds. A synchronous
        // compatibility fallback is represented as an already-signaled receipt and follows the same
        // exact acknowledgement protocol in Kotlin.
        inputReadReceiptSlot->id = static_cast<uint64_t>(inputReadReceipt);
        inputReadReceiptSlot->sync = inputReadSync;
        inputReadReceiptSlot->signaled = inputReadCompletedSynchronously;
        inputReadReceiptSlot->bufferId = eglImageCacheKey;
        inputReadReceiptSlot->presentationTimeNs = static_cast<int64_t>(presentationTimeNs);
        if (inputReadCompletedSynchronously) ++state->inputReadFenceSignaled;
        state->inputReadFenceMaxOutstanding = std::max<uint64_t>(
            state->inputReadFenceMaxOutstanding,
            static_cast<uint64_t>(inputReadFenceOutstandingCount(state))
        );
        // Keep enough exact-receipt evidence for startup and cadence checkpoints without making
        // every 4K frame synchronously format and enqueue a logd record on the recorder thread.
        // Failures and synchronous fallbacks remain unsampled through their dedicated warning paths.
        const uint64_t committedFrame = state->submittedFrames + 1u;
        if (committedFrame <= 3u || committedFrame % 25u == 0u) {
            LOGI(
                "RAW_INPUT_READ_FENCE_COMMITTED frame=%llu receipt=%lld bufferId=%llu ptsNs=%lld "
                "state=%s boundary=%s outstanding=%zu",
                static_cast<unsigned long long>(committedFrame),
                static_cast<long long>(inputReadReceipt),
                static_cast<unsigned long long>(eglImageCacheKey),
                static_cast<long long>(presentationTimeNs),
                inputReadCompletedSynchronously ? "SIGNALED_SYNC_FALLBACK" : "PENDING",
                inputReadBoundaryLabel,
                inputReadFenceOutstandingCount(state)
            );
        }
    }
    ++state->submittedFrames;
    const bool neoGatherGridUsed =
        useSdrCreativeLutGatherGrid2d || useSdrCreativeLutNiv99GatherGrid2d;
    const bool neoGatherGridEligible =
        useDecodeIntermediate && sourceWidth == targetWidth && sourceHeight == targetHeight &&
        sensorLinear2dIntegerOffsetsExact && sdrCreativeLutActive &&
        (sdrCreativeLutSpecializationEligible ||
         sdrCreativeLutNiv99SpecializationEligible);
    if (neoGatherGridUsed) {
        ++state->neoGatherGridFrames;
        ++state->neoConstantLiftFrames;
        if (state->neoGatherGridFrames == 1u) {
            LOGI(
                "NEO_GATHER_GRID_ACTIVE GLES31 exactCardinals=true textureInstructions=3xRGB_GATHER "
                "canonicalCardinalInstructions=4xRGBA_OFFSET qualityReduction=false"
            );
            LOGI(
                "NEO_CONSTANT_LIFT_ACTIVE denoiseMix=%.6f invRangeVariance=%.6f "
                "sharpenAmount=%.6f sharpenCap=%.6f chromaScale=%.6f "
                "creativeLutBranch=PROVEN_ACTIVE qualityEquations=CANONICAL",
                neoConstantLift.denoiseMix,
                neoConstantLift.inverseRangeVariance,
                neoConstantLift.sharpenAmount,
                neoConstantLift.sharpenCap,
                neoConstantLift.chromaScale
            );
        }
    } else if (neoGatherGridEligible) {
        ++state->neoGatherGridFallbackFrames;
    }
    if (niv99ActiveForFrame && niv99RgbCorrectionIdentity &&
        (useSdrInvariantSpecializedMain || useSdrCreativeLutSpecializedMain)) {
        ++state->niv99IdentityWhiteBalanceElisionFrames;
        if (state->niv99IdentityWhiteBalanceElisionFrames == 1u) {
            LOGI(
                "NIV99_IDENTITY_WB_ELISION_ACTIVE rgbCorrection=1,1,1 "
                "denoiseReconstructionRetained=true qualityReduction=false"
            );
        }
    }
    if (niv99ActiveForFrame) {
        ++state->niv99PixelsApplied;
        if (state->niv99PixelsApplied == 1u || state->niv99PixelsApplied % 120u == 0u) {
            LOGI(
                "NIV99_PIXEL_APPLIED_ENCODER total=%llu generation=%lld frame=%lld "
                "timestampNs=%lld confidence=%.4f",
                static_cast<unsigned long long>(state->niv99PixelsApplied),
                static_cast<long long>(state->niv99SessionGeneration),
                static_cast<long long>(state->niv99FrameNumber),
                static_cast<long long>(state->niv99SensorTimestampNs),
                static_cast<double>(niv99ActiveConfidenceValue));
        }
    }
    if (useSdrNiv99SpecializedMain) {
        ++state->sdrNiv99SpecializedFrames;
        if (state->sdrNiv99SpecializedFrames == 1u) {
            LOGI(
                "SDR NIV99 specialized shader frame committed sampler=%s "
                "transfer=const-0 pqConvert=const-0 niv99Correction=dynamic "
                "lutStrength=const-0 "
                "qualityEquations=CANONICAL",
                useDecodeIntermediate ? "sampler2D" : "samplerExternalOES"
            );
        }
    } else if (sdrNiv99SpecializationEligible) {
        ++state->sdrNiv99SpecializationFallbackFrames;
    }
    if (useSdrInvariantSpecializedMain) {
        ++state->sdrInvariantSpecializedFrames;
        if (state->sdrInvariantSpecializedFrames == 1u) {
            LOGI(
                "SDR invariant specialized shader frame committed sampler=%s "
                "transfer=const-0 pqConvert=const-0 niv99Confidence=const-0 "
                "lutStrength=const-0 "
                "qualityEquations=CANONICAL",
                useDecodeIntermediate ? "sampler2D" : "samplerExternalOES"
            );
        }
    } else if (sdrInvariantSpecializationEligible) {
        ++state->sdrInvariantSpecializationFallbackFrames;
    }
    if (useSdrCreativeLutNiv99SpecializedMain) {
        ++state->sdrCreativeLutNiv99SpecializedFrames;
        if (state->sdrCreativeLutNiv99SpecializedFrames == 1u) {
            LOGI(
                "SDR creative-LUT NIV99 specialized shader frame committed sampler=%s "
                "transfer=const-0 pqConvert=const-0 niv99Correction=dynamic "
                "lutStrength=dynamic lutSampling=canonical qualityEquations=CANONICAL",
                useDecodeIntermediate ? "sampler2D" : "samplerExternalOES"
            );
        }
    } else if (sdrCreativeLutNiv99SpecializationEligible) {
        ++state->sdrCreativeLutNiv99SpecializationFallbackFrames;
    }
    if (useSdrCreativeLutSpecializedMain) {
        ++state->sdrCreativeLutSpecializedFrames;
        if (state->sdrCreativeLutSpecializedFrames == 1u) {
            LOGI(
                "SDR creative-LUT specialized shader frame committed sampler=%s "
                "transfer=const-0 pqConvert=const-0 niv99Confidence=const-0 "
                "lutStrength=dynamic lutSampling=canonical qualityEquations=CANONICAL",
                useDecodeIntermediate ? "sampler2D" : "samplerExternalOES"
            );
        }
    } else if (sdrCreativeLutSpecializationEligible) {
        ++state->sdrCreativeLutSpecializationFallbackFrames;
    }
    if (useSensorLinearSpecializedMain) {
        ++state->sensorLinearSpecializedFrames;
        uint64_t& profileFrames = ndrDeliveryMode == 1
            ? state->sensorLinearNivukxSpecializedFrames
            : state->sensorLinearNdr10SpecializedFrames;
        ++profileFrames;
        if (profileFrames == 1u) {
            LOGI(
                "sensor-linear specialized shader frame committed profile=%s sampler=%s "
                "transfer=const-3 pqConvert=const-1 delivery=const-%d masterNits=%.0f",
                ndrDeliveryMode == 1 ? "NIVUKX" : "NDR10",
                useDecodeIntermediate ? "sampler2D" : "samplerExternalOES",
                ndrDeliveryMode,
                ndrBaselinePeakNits
            );
        }
    } else if (sensorLinearPqSpecializationEligible) {
        ++state->sensorLinearSpecializationFallbackFrames;
    }
    if (sensorNdrTonePqLutReadyForFrame) {
        ++state->ndrSensorTonePqLutFrames;
    }
    if (useProjectPreviewDownsample) {
        ++state->projectPreviewDownsampleFrames;
    } else if (useDecodeOnce) {
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
    if (decodeHostUs > 0u) state->decodeHostStats.record(decodeHostUs);
    if (gradeHostUs > 0u) state->gradeHostStats.record(gradeHostUs);
    if (historyHostUs > 0u) state->historyHostStats.record(historyHostUs);
    if (finishDurationUs > 0u) state->finishStats.record(finishDurationUs);
    if (swapDurationUs > 45000u) {
        ++state->slowSwapFrames;
    }
    if (submitDurationUs > 40000u) {
        ++state->slowSubmitFrames;
    }
    if (sampleGpuStages) {
        const size_t decodeIndex = gpuStageIndex(GpuStage::Decode);
        const size_t gradeIndex = gpuStageIndex(GpuStage::Grade);
        const size_t historyIndex = gpuStageIndex(GpuStage::History);
        LOGI(
            "NDR_EGL_STAGE_TIMING frame=%llu delivery=%s surface=%dx%d source=%dx%d "
            "path=%s specialized=%s specializedProfile=%s "
            "hostDecodeUs=%llu hostGradeUs=%llu hostHistoryUs=%llu finishUs=%llu swapUs=%llu "
            "gpuDecodeUs=%llu gpuDecodeValid=%s gpuGradeUs=%llu gpuGradeValid=%s "
            "gpuHistoryUs=%llu gpuHistoryValid=%s disjoint=%s totalSubmitUs=%llu",
            static_cast<unsigned long long>(state->submittedFrames),
            state->deliveryModeLabel.c_str(),
            targetWidth,
            targetHeight,
            sourceWidth,
            sourceHeight,
            useDecodeIntermediate ? "two-pass" : "single-pass",
            useSensorLinearSpecializedMain ? "true" : "false",
            useSensorLinearSpecializedMain
                ? (ndrDeliveryMode == 1 ? "NIVUKX" : "NDR10")
                : "GENERIC",
            static_cast<unsigned long long>(decodeHostUs),
            static_cast<unsigned long long>(gradeHostUs),
            static_cast<unsigned long long>(historyHostUs),
            static_cast<unsigned long long>(finishDurationUs),
            static_cast<unsigned long long>(swapDurationUs),
            static_cast<unsigned long long>(gpuStageSample.elapsedUs[decodeIndex]),
            gpuStageSample.valid[decodeIndex] ? "true" : "false",
            static_cast<unsigned long long>(gpuStageSample.elapsedUs[gradeIndex]),
            gpuStageSample.valid[gradeIndex] ? "true" : "false",
            static_cast<unsigned long long>(gpuStageSample.elapsedUs[historyIndex]),
            gpuStageSample.valid[historyIndex] ? "true" : "false",
            gpuStageSample.disjoint ? "true" : "false",
            static_cast<unsigned long long>(submitDurationUs)
        );
    }
    // Preserve low-overhead production telemetry while making startup backlog diagnosable. The
    // first eight frames cover codec/EGL warmup; steady-state remains sampled only once per 60.
    if (state->submittedFrames <= 8u || (state->submittedFrames % 60u) == 0u) {
        const uint64_t avgSwapUs =
            state->submittedFrames > 0u ? state->swapDurationTotalUs / state->submittedFrames : 0u;
        const uint64_t avgSubmitUs =
            state->submittedFrames > 0u ? state->submitDurationTotalUs / state->submittedFrames : 0u;
        const uint64_t avgHistoryPresentUs =
            state->temporalHistoryFrames > 0u
                ? state->historyPresentTotalUs / state->temporalHistoryFrames
                : 0u;
        LOGI(
            "Submitted offscreen EGL encoder frames=%llu decodeOnceFrames=%llu decodeTripleBufferedFrames=%llu projectPreviewDownsampleFrames=%llu externalPathFrames=%llu sdrInvariantFrames=%llu sdrInvariantFallbackFrames=%llu sdrNiv99Frames=%llu sdrNiv99FallbackFrames=%llu sdrCreativeLutFrames=%llu sdrCreativeLutFallbackFrames=%llu sdrCreativeLutNiv99Frames=%llu sdrCreativeLutNiv99FallbackFrames=%llu explicitYuvFrames=%llu sourceHistoryFrames=%llu sensorPqNdr10Frames=%llu sensorPqNivukxFrames=%llu sensorPqFallbackFrames=%llu avgSwapUs=%llu maxSwapUs=%llu slowSwaps=%llu avgSubmitUs=%llu maxSubmitUs=%llu slowSubmits=%llu temporalHistoryFrames=%llu avgHistoryPresentUs=%llu maxHistoryPresentUs=%llu slowHistoryPresents=%llu historyPresentErrorChecks=%llu ndrPqLut=%s sensorTonePqLut=%s sensorTonePqFrames=%llu sensorTonePqBuilds=%llu eglImageCache=%s cacheKey=%s cacheSize=%zu cacheHits=%llu cacheMisses=%llu cacheEvictions=%llu cacheIdFailures=%llu codecBackpressurePacing=%s",
            static_cast<unsigned long long>(state->submittedFrames),
            static_cast<unsigned long long>(state->decodeOnceFrames),
            static_cast<unsigned long long>(state->decodeTripleBufferedFrames),
            static_cast<unsigned long long>(state->projectPreviewDownsampleFrames),
            static_cast<unsigned long long>(state->externalPathFrames),
            static_cast<unsigned long long>(state->sdrInvariantSpecializedFrames),
            static_cast<unsigned long long>(state->sdrInvariantSpecializationFallbackFrames),
            static_cast<unsigned long long>(state->sdrNiv99SpecializedFrames),
            static_cast<unsigned long long>(state->sdrNiv99SpecializationFallbackFrames),
            static_cast<unsigned long long>(state->sdrCreativeLutSpecializedFrames),
            static_cast<unsigned long long>(state->sdrCreativeLutSpecializationFallbackFrames),
            static_cast<unsigned long long>(state->sdrCreativeLutNiv99SpecializedFrames),
            static_cast<unsigned long long>(state->sdrCreativeLutNiv99SpecializationFallbackFrames),
            static_cast<unsigned long long>(state->explicitYuvDecodeFrames),
            static_cast<unsigned long long>(state->sourceHistoryFrames),
            static_cast<unsigned long long>(state->sensorLinearNdr10SpecializedFrames),
            static_cast<unsigned long long>(state->sensorLinearNivukxSpecializedFrames),
            static_cast<unsigned long long>(state->sensorLinearSpecializationFallbackFrames),
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
            state->ndrSensorTonePqLutReady ? "ready" : "fallback",
            static_cast<unsigned long long>(state->ndrSensorTonePqLutFrames),
            static_cast<unsigned long long>(state->ndrSensorTonePqLutBuilds),
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
    if (!submitEglUnbind.unbind()) {
        std::ostringstream message;
        message << "eglMakeCurrent submit cleanup failed error=0x"
                << std::hex << submitEglUnbind.lastError();
        setStatus(state, message.str());
        if (inputReadFenceRequested) {
            // eglSwapBuffers already accepted this exact encoder frame and its receipt is committed.
            // Returning failure would permit a duplicate retry, so latch diagnostics and let the
            // receipt drive safe one-time RAW ownership retirement.
            ++state->inputReadFenceFailures;
            LOGE("%s; irreversible project-owned submission retained", message.str().c_str());
            return JNI_TRUE;
        }
        recordFrameFailure();
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_neoproc_neoproccamx_rendering_OffscreenAhbEglEncoderBridge_nativeGetLastCameraP010SdrPrewarmBufferId(
    JNIEnv*,
    jobject,
    jlong handle
) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return 0L;
    std::lock_guard<std::mutex> lock(state->mutex);
    return static_cast<jlong>(state->cameraP010SdrPrewarmLastBufferId);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_neoproc_neoproccamx_rendering_OffscreenAhbEglEncoderBridge_nativePollProjectOwnedInputReadFence(
    JNIEnv*,
    jobject,
    jlong handle,
    jlong receipt,
    jlong timeoutNs
) {
    auto* state = fromHandle(handle);
    if (state == nullptr || receipt <= 0 || timeoutNs < 0) return kInputReadFenceFailed;
    std::lock_guard<std::mutex> lock(state->mutex);
    return pollInputReadFenceReceipt(
        state,
        static_cast<uint64_t>(receipt),
        static_cast<uint64_t>(timeoutNs)
    );
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_rendering_OffscreenAhbEglEncoderBridge_nativeAcknowledgeProjectOwnedInputReadFence(
    JNIEnv*,
    jobject,
    jlong handle,
    jlong receipt
) {
    auto* state = fromHandle(handle);
    if (state == nullptr || receipt <= 0) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(state->mutex);
    InputReadFenceReceipt* record = findInputReadFenceReceipt(
        state,
        static_cast<uint64_t>(receipt)
    );
    if (record == nullptr || !record->signaled || record->sync != EGL_NO_SYNC_KHR) {
        return JNI_FALSE;
    }
    const uint64_t bufferId = record->bufferId;
    const int64_t presentationTimeNs = record->presentationTimeNs;
    *record = InputReadFenceReceipt{};
    const uint64_t acknowledgedReceipt = static_cast<uint64_t>(receipt);
    if (acknowledgedReceipt <= 3u || acknowledgedReceipt % 25u == 0u) {
        LOGI(
            "RAW_INPUT_READ_FENCE_ACKNOWLEDGED receipt=%lld bufferId=%llu ptsNs=%lld outstanding=%zu",
            static_cast<long long>(receipt),
            static_cast<unsigned long long>(bufferId),
            static_cast<long long>(presentationTimeNs),
            inputReadFenceOutstandingCount(state)
        );
    }
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
        << " offscreenEglContextPriorityExtension="
        << (state->contextPriorityExtensionAvailable ? "true" : "false")
        << " offscreenEglContextPriorityRequested="
        << (state->contextPriorityHighRequested ? "HIGH" : "NONE")
        << " offscreenEglContextPriorityGranted="
        << (state->contextPriorityQuerySucceeded
            ? contextPriorityLabel(state->contextPriorityGranted)
            : "UNKNOWN")
        << " offscreenEglContextPriorityFallback="
        << (state->contextPriorityFallbackUsed ? "true" : "false")
        << " offscreenEglContextPriorityRequestError="
        << state->contextPriorityRequestError
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
        // Keep residency counters near the start: persistent Android log entries can truncate the
        // long shader description before the legacy cache fields at the end of this report.
        << " importResidencyCapacity=" << kEglImageCacheCapacity
        << " importResidencySize=" << state->eglImageCache.size()
        << " importResidencyHits=" << state->eglImageCacheHits
        << " importResidencyMisses=" << state->eglImageCacheMisses
        << " importResidencyEvictions=" << state->eglImageCacheEvictions
        << " niv99Mode=video-default-on"
        << " niv99Graph=offscreen-egl-grade"
        << " niv99PacketsSeen=" << state->niv99PacketsSeen
        << " niv99PacketsAccepted=" << state->niv99PacketsAccepted
        << " niv99PacketsRejected=" << state->niv99PacketsRejected
        << " niv99PixelApplied=" << state->niv99PixelsApplied
        << " niv99LatestGeneration=" << state->niv99SessionGeneration
        << " niv99LatestFrame=" << state->niv99FrameNumber
        << " niv99LatestSensorTimestampNs=" << state->niv99SensorTimestampNs
        << " decodeOnceEnabled=" << (state->decodeOnceEnabled ? "true" : "false")
        << " decodeOnceFrames=" << state->decodeOnceFrames
        << " decodeTripleBufferReady=" << (state->decodeTripleBufferReady ? "true" : "false")
        << " decodeTripleBufferedFrames=" << state->decodeTripleBufferedFrames
        << " externalPathFrames=" << state->externalPathFrames
        << " sdrInvariantSpecializedExternalReady="
        << (state->sdrInvariantProgramExternal != 0 ? "true" : "false")
        << " sdrInvariantSpecialized2dReady="
        << (state->sdrInvariantProgram2d != 0 ? "true" : "false")
        << " sdrInvariantSpecializedFrames=" << state->sdrInvariantSpecializedFrames
        << " sdrInvariantSpecializationFallbackFrames="
        << state->sdrInvariantSpecializationFallbackFrames
        << " sdrNiv99SpecializedExternalReady="
        << (state->sdrNiv99ProgramExternal != 0 ? "true" : "false")
        << " sdrNiv99Specialized2dReady="
        << (state->sdrNiv99Program2d != 0 ? "true" : "false")
        << " sdrNiv99SpecializedFrames=" << state->sdrNiv99SpecializedFrames
        << " sdrNiv99SpecializationFallbackFrames="
        << state->sdrNiv99SpecializationFallbackFrames
        << " sdrCreativeLutSpecializedExternalReady="
        << (state->sdrCreativeLutProgramExternal != 0 ? "true" : "false")
        << " sdrCreativeLutSpecialized2dReady="
        << (state->sdrCreativeLutProgram2d != 0 ? "true" : "false")
        << " sdrCreativeLutSpecializedFrames=" << state->sdrCreativeLutSpecializedFrames
        << " sdrCreativeLutSpecializationFallbackFrames="
        << state->sdrCreativeLutSpecializationFallbackFrames
        << " sdrCreativeLutNiv99SpecializedExternalReady="
        << (state->sdrCreativeLutNiv99ProgramExternal != 0 ? "true" : "false")
        << " sdrCreativeLutNiv99Specialized2dReady="
        << (state->sdrCreativeLutNiv99Program2d != 0 ? "true" : "false")
        << " sdrCreativeLutNiv99SpecializedFrames="
        << state->sdrCreativeLutNiv99SpecializedFrames
        << " sdrCreativeLutNiv99SpecializationFallbackFrames="
        << state->sdrCreativeLutNiv99SpecializationFallbackFrames
        << " neoGatherGridCapable=" << (state->neoGatherGridCapable ? "true" : "false")
        << " neoGatherGridEnabled=" << (state->neoGatherGridEnabled ? "true" : "false")
        << " neoGatherGridIdentityReady="
        << (state->sdrCreativeLutGatherProgram2d != 0 ? "true" : "false")
        << " neoGatherGridNiv99Ready="
        << (state->sdrCreativeLutNiv99GatherProgram2d != 0 ? "true" : "false")
        << " neoGatherGridFrames=" << state->neoGatherGridFrames
        << " neoGatherGridFallbackFrames=" << state->neoGatherGridFallbackFrames
        << " neoConstantLiftFrames=" << state->neoConstantLiftFrames
        << " niv99IdentityWhiteBalanceElisionFrames="
        << state->niv99IdentityWhiteBalanceElisionFrames
        << " sensorLinearSpecializedExternalReady="
        << (state->sensorLinearProgramExternal != 0 ? "true" : "false")
        << " sensorLinearSpecialized2dReady="
        << (state->sensorLinearProgram2d != 0 ? "true" : "false")
        << " sensorLinearNivukxSpecializedExternalReady="
        << (state->sensorLinearNivukxProgramExternal != 0 ? "true" : "false")
        << " sensorLinearNivukxSpecialized2dReady="
        << (state->sensorLinearNivukxProgram2d != 0 ? "true" : "false")
        << " sensorLinearSpecializedFrames=" << state->sensorLinearSpecializedFrames
        << " sensorLinearNdr10SpecializedFrames="
        << state->sensorLinearNdr10SpecializedFrames
        << " sensorLinearNivukxSpecializedFrames="
        << state->sensorLinearNivukxSpecializedFrames
        << " sensorLinearSpecializationFallbackFrames="
        << state->sensorLinearSpecializationFallbackFrames
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
        << " avgFinishUs=" << state->finishStats.averageUs()
        << " maxFinishUs=" << state->finishStats.maxUs
        << " avgDecodeHostUs=" << state->decodeHostStats.averageUs()
        << " maxDecodeHostUs=" << state->decodeHostStats.maxUs
        << " avgGradeHostUs=" << state->gradeHostStats.averageUs()
        << " maxGradeHostUs=" << state->gradeHostStats.maxUs
        << " avgHistoryHostUs=" << state->historyHostStats.averageUs()
        << " maxHistoryHostUs=" << state->historyHostStats.maxUs
        << " gpuStageTimingAvailable="
        << (state->gpuStageTimingAvailable ? "true" : "false")
        << " gpuStageTimingSamplingEnabled="
        << (state->gpuStageTimingSamplingEnabled ? "true" : "false")
        << " gpuStageTimingFaulted="
        << (state->gpuStageTimingFaulted ? "true" : "false")
        << " gpuStageCounterBits=" << state->gpuStageCounterBits
        << " gpuStageQueryErrors=" << state->gpuStageQueryErrors
        << " avgGpuDecodeUs="
        << state->gpuStageStats[gpuStageIndex(GpuStage::Decode)].averageUs()
        << " maxGpuDecodeUs="
        << state->gpuStageStats[gpuStageIndex(GpuStage::Decode)].maxUs
        << " avgGpuGradeUs="
        << state->gpuStageStats[gpuStageIndex(GpuStage::Grade)].averageUs()
        << " maxGpuGradeUs="
        << state->gpuStageStats[gpuStageIndex(GpuStage::Grade)].maxUs
        << " avgGpuHistoryUs="
        << state->gpuStageStats[gpuStageIndex(GpuStage::History)].averageUs()
        << " maxGpuHistoryUs="
        << state->gpuStageStats[gpuStageIndex(GpuStage::History)].maxUs
        << " gpuStageDisjointBatches=" << state->gpuStageDisjointBatches
        << " gpuStageUnavailablePolls=" << state->gpuStageUnavailablePolls
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
        << " ndrSensorTonePqLutReady="
        << (state->ndrSensorTonePqLutReady ? "true" : "false")
        << " ndrSensorTonePqLutSize=" << kSensorNdrTonePqLutSize
        << " ndrSensorTonePqLutRows=" << kSensorNdrTonePqLutRows
        << " ndrSensorTonePqLutMapping=PIECEWISE_LINEAR_ABSOLUTE_HIGH_RESIDUAL"
        << " ndr10MasterPeakNits=" << kNdr10MasterPeakNits
        << " nivukxMasterPeakNits=" << kNivukxMasterPeakNits
        << " ndrSensorTonePqLutFrames=" << state->ndrSensorTonePqLutFrames
        << " ndrSensorTonePqLutBuilds=" << state->ndrSensorTonePqLutBuilds
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
        << " recorderPrewarmAttempts=" << state->recorderPrewarmAttempts
        << " recorderPrewarmSuccesses=" << state->recorderPrewarmSuccesses
        << " recorderPrewarmFailures=" << state->recorderPrewarmFailures
        << " recorderPrewarmAvgUs="
        << (state->recorderPrewarmAttempts > 0u
            ? state->recorderPrewarmTotalUs / state->recorderPrewarmAttempts
            : 0u)
        << " recorderPrewarmMaxUs=" << state->recorderPrewarmMaxUs
        << " recorderPrewarmBufferId0=" << state->recorderPrewarmBufferIds[0]
        << " recorderPrewarmBufferId1=" << state->recorderPrewarmBufferIds[1]
        << " cameraP010SdrPrewarmAttempts=" << state->cameraP010SdrPrewarmAttempts
        << " cameraP010SdrPrewarmSuccesses=" << state->cameraP010SdrPrewarmSuccesses
        << " cameraP010SdrPrewarmFailures=" << state->cameraP010SdrPrewarmFailures
        << " cameraP010SdrPrewarmDistinctBuffers="
        << state->cameraP010SdrPrewarmBufferIds.size()
        << " cameraP010SdrPrewarmAvgUs="
        << (state->cameraP010SdrPrewarmAttempts > 0u
            ? state->cameraP010SdrPrewarmTotalUs / state->cameraP010SdrPrewarmAttempts
            : 0u)
        << " cameraP010SdrPrewarmMaxUs=" << state->cameraP010SdrPrewarmMaxUs
        << " cameraP010SdrPrewarmLastBufferId=" << state->cameraP010SdrPrewarmLastBufferId
        << " cameraP010SdrPrewarmIdentityShaderDraws="
        << state->cameraP010SdrPrewarmIdentityShaderDraws
        << " cameraP010SdrPrewarmNiv99ShaderDraws="
        << state->cameraP010SdrPrewarmNiv99ShaderDraws
        << " codecReadinessAttempts=" << state->codecReadinessAttempts
        << " codecReadinessSuccesses=" << state->codecReadinessSuccesses
        << " codecReadinessFailures=" << state->codecReadinessFailures
        << " codecReadinessAvgUs="
        << (state->codecReadinessAttempts > 0u
            ? state->codecReadinessTotalUs / state->codecReadinessAttempts
            : 0u)
        << " codecReadinessMaxUs=" << state->codecReadinessMaxUs
        << " codecReadinessPresentationTimeNs=" << state->codecReadinessPresentationTimeNs
        << " presentationTimelineRegressions=" << state->presentationTimelineRegressions
        << " inputReadFenceAvailable=" << (state->inputReadFenceAvailable ? "true" : "false")
        << " inputReadFenceOutstanding=" << inputReadFenceOutstandingCount(state)
        << " inputReadFenceCreated=" << state->inputReadFenceCreated
        << " inputReadFenceSignaled=" << state->inputReadFenceSignaled
        << " inputReadFencePendingPolls=" << state->inputReadFencePendingPolls
        << " inputReadFenceSaturatedWaitNs=" << kSaturatedInputReadFencePollWaitNs
        << " inputReadFenceSaturatedWaits=" << state->inputReadFenceSaturatedWaits
        << " inputReadFenceSaturatedWaitTimeouts="
        << state->inputReadFenceSaturatedWaitTimeouts
        << " inputReadFenceSyncFallbacks=" << state->inputReadFenceSyncFallbacks
        << " inputReadFenceFailures=" << state->inputReadFenceFailures
        << " inputReadFenceMaxOutstanding=" << state->inputReadFenceMaxOutstanding
        << " inputReadDecodeBoundaryFrames=" << state->inputReadDecodeBoundaryFrames
        << " inputReadFinalBoundaryFrames=" << state->inputReadFinalBoundaryFrames
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
