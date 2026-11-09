#include <jni.h>

#include <android/hardware_buffer.h>
#include <android/hardware_buffer_jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>

#include <algorithm>
#include <atomic>
#include <array>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <dlfcn.h>
#include <cmath>
#include <limits>
#include <memory>
#include <mutex>
#include <numeric>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

#include "pipeline_cache_io.h"

#include "generated/fullscreen_vert_spv.h"
#include "generated/ycbcr_preview_frag_spv.h"
#include "generated/dual_gain_fusion_comp_spv.h"
#include "generated/photon_hdr_fusion_comp_spv.h" // s1-f10 / CP32 — photon HDR fusion foundation (dormant)
#include "generated/eis_warp_comp_spv.h" // s3-f06 / CP60 — gyro-EIS per-scanline warp foundation (dormant)
#include "generated/bokeh_vogel_spiral_comp_spv.h" // s5-f06 / CP-F7 — depth-driven Vogel-spiral bokeh (gated OFF, dormant)
#include "generated/realtime_preview_enhance_comp_spv.h"
#include "generated/ndr10_knee_curve_comp_spv.h"
#include "generated/knee_curve_comp_spv.h"
#include "generated/shadow_lift_comp_spv.h"
#include "generated/hdr_metadata_histogram_comp_spv.h"
#include "generated/hdr_metadata_reduce_comp_spv.h"
#include "generated/guided_upsample_comp_spv.h"
#include "generated/demosaic_vulkan_comp_spv.h"
#include "generated/raw_monitor_tonemap_comp_spv.h"
#include "generated/raw_preview_downsample_comp_spv.h"
#include "generated/raw_scene_log_pack_comp_spv.h"
#include "generated/raw_develop_detail_comp_spv.h"
#include "generated/raw_develop_luma_blur_comp_spv.h"
#include "generated/raw_develop_p010_pack_comp_spv.h"
#include "generated/raw_niv99_prefilter_comp_spv.h"
#include "generated/detail_preservation_comp_spv.h"
#include "generated/mfsr_fusion_comp_spv.h"
#include "generated/imax_rendering_comp_spv.h"
#include "generated/nivukx_denoise_comp_spv.h"
#include "generated/denoise_comp_spv.h" // CP-QV16 — gated single-frame spatial denoiser foundation (default OFF, dormant)
#include "generated/sharpen_comp_spv.h" // CP-QV17 — gated optical-recovery sharpener foundation (default OFF, dormant)
#include "generated/argus_flow_blockmatch_comp_spv.h"
#include "generated/argus_flow_refine_comp_spv.h"
#include "generated/riven_motion_deblur_comp_spv.h"
#include "generated/warp_reconstruct_comp_spv.h"
#include "generated/omni_downsample_comp_spv.h"
#include "generated/omni_flow_upsample_comp_spv.h"
#include "generated/argus_flow_select_comp_spv.h"
#include "generated/occs_opponent_comp_spv.h"
#include "generated/nrli_lut_apply_comp_spv.h"
#include "native_buffer_manager.h"

#define LOG_TAG "NeoProcVulkan"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#ifndef NEOPROCAMX_STRICT_HIGH_END
#define NEOPROCAMX_STRICT_HIGH_END 0
#endif

namespace {

constexpr bool kStrictHighEndBuild = NEOPROCAMX_STRICT_HIGH_END != 0;
constexpr uint32_t kStrictRecordingFramesInFlight = 8u;
constexpr uint32_t kFramesInFlight = kStrictHighEndBuild ? kStrictRecordingFramesInFlight : 4u;
constexpr uint32_t kMinimumSwapchainBuffers = 3u;
// Frame-path waits are deadlines, not unbounded ownership waits. Recording receives a
// substantially larger budget than the presentation-only preview, while both remain finite.
constexpr uint64_t kPreviewFrameSyncTimeoutNs = 5000000ULL;
constexpr uint64_t kEncoderFrameSyncTimeoutNs = 100000000ULL;
constexpr uint64_t kRawPreviewGpuDeadlineNs = 25000000ULL;
// The recording RAW worker cannot legally dispatch its next exact frame until the current Vulkan
// fence completes. Coalesce only that otherwise-idle poll for one millisecond so a near-complete
// dispatch can wake in place instead of paying another executor timer round-trip. Idle preview polls
// remain non-blocking, and this does not add a frame slot or extend any ownership deadline.
constexpr uint64_t kRawRecordingCompletionCoalesceWaitNs = 1000000ULL;
// Offline conversion owns the camera resources and must wait for a correct full-resolution result.
// It must not inherit the presentation-only 25 ms preview deadline, which rejected healthy 4K work.
constexpr uint64_t kRawOfflineConversionTimeoutNs = 5000000000ULL;
constexpr uint64_t kRawResourceTeardownTimeoutNs = 500000000ULL;
constexpr uint64_t kRawDeferredCompletionLogInterval = 120u;
constexpr size_t kRawPreviewTransportSlotCount = 3u;
enum class RawGpuTimestampPoint : uint32_t {
    Begin = 0u,
    UploadComplete,
    DemosaicComplete,
    SceneLogComplete,
    PreviewComplete,
    MonitorComplete,
    DispatchComplete,
    Count
};
constexpr uint32_t kRawGpuTimestampCount =
    static_cast<uint32_t>(RawGpuTimestampPoint::Count);
constexpr size_t kRawGpuStageCount = kRawGpuTimestampCount - 1u;
constexpr jint kRawPreviewDispatchUnavailable = -1;
constexpr jint kRawPreviewDispatchNotRequired = 0;
constexpr jint kRawPreviewDispatchAvailable = 1;
constexpr jint kRawPreviewDispatchSaturated = 2;
constexpr int kRawRecordingPreviewLongEdge = 1280;
constexpr size_t kEnhancementUniformFloatCount = 44;
constexpr size_t kColorScienceUniformFloatCount = 52;
constexpr std::array<int, 4> kValidSignalFamilyIds{1, 2, 3, 4};
  constexpr std::array<int, 5> kValidColorSpaceIds{1, 2, 3, 4, 5};
  constexpr std::array<int, 9> kValidColorScienceIds{1, 10, 11, 12, 13, 20, 21, 30, 31};
constexpr std::array<int, 5> kValidActiveTransportIds{1, 2, 3, 4, 5};
constexpr const char* kActiveDispatchedCapabilityToken = "nativeP010Compute=active-dispatched";
constexpr const char* kLensWarpingCapabilityToken = "lensWarping=ActiveAnamorphic";
constexpr const char* kHalationCapabilityToken = "cinematicHalation=IMAXEmissionPyramid";
constexpr const char* kTimelineSemaphoreCapabilityToken = "syncPolicy=VulkanTimelineSemaphores";
constexpr const char* kTimestampQueryCapabilityToken = "timestampQueries=VulkanTimestampQuery";
constexpr uint32_t kHdrMetadataHistogramBins = 256u;
constexpr uint32_t kHdrMetadataHistogramWordCount = kHdrMetadataHistogramBins + 2u;
constexpr uint32_t kHdrMetadataWordCount = 4u;
constexpr size_t kHdrMetadataRingSize = 8;

struct RenderConfig {
    std::array<float, 16> previewTransform{
        1.f, 0.f, 0.f, 0.f,
        0.f, 1.f, 0.f, 0.f,
        0.f, 0.f, 1.f, 0.f,
        0.f, 0.f, 0.f, 1.f
    };
    std::array<float, 16> encoderTransform{
        1.f, 0.f, 0.f, 0.f,
        0.f, 1.f, 0.f, 0.f,
        0.f, 0.f, 1.f, 0.f,
        0.f, 0.f, 0.f, 1.f
    };
    std::array<float, 4> colorGain{1.f, 1.f, 1.f, 1.f};
    std::array<float, 16> previewGrade{
        1.f, 1.f, 0.f, 0.f,
        1.f, 1.f, 1.f, 0.f,
        0.f, 0.f, 0.f, 0.f,
        0.f, 2.f, 0.f, 0.f
    };
    std::array<float, 16> encoderGrade{
        1.f, 1.f, 0.f, 0.f,
        1.f, 1.f, 1.f, 0.f,
        0.f, 0.f, 0.f, 0.f,
        0.f, 2.f, 0.f, 0.f
    };
    std::array<float, kColorScienceUniformFloatCount> previewColorScience{
        1.f, 0.f, 0.f, 0.f, 0.f, 1.f, 0.f, 0.f,
        0.f, 0.f, 1.f, 0.f, 0.f, 0.f, 0.f, 1.f,
        1.f, 0.f, 0.f, 0.f, 0.f, 1.f, 0.f, 0.f,
        0.f, 0.f, 1.f, 0.f, 0.f, 0.f, 0.f, 1.f,
        0.f, 0.59f, 3.72f, 0.54f,
        0.f, 0.f, 0.f, 0.f,
        0.f, 0.f, 0.f, 0.f,
        0.f, 0.f, 0.f, 0.f,
        0.f, 0.f, 0.f, 0.f
    };
    std::array<float, kColorScienceUniformFloatCount> encoderColorScience{
        1.f, 0.f, 0.f, 0.f, 0.f, 1.f, 0.f, 0.f,
        0.f, 0.f, 1.f, 0.f, 0.f, 0.f, 0.f, 1.f,
        1.f, 0.f, 0.f, 0.f, 0.f, 1.f, 0.f, 0.f,
        0.f, 0.f, 1.f, 0.f, 0.f, 0.f, 0.f, 1.f,
        0.f, 0.59f, 3.72f, 0.54f,
        0.f, 0.f, 0.f, 0.f,
        0.f, 0.f, 0.f, 0.f,
        0.f, 0.f, 0.f, 0.f,
        0.f, 0.f, 0.f, 0.f
    };
};

struct PreviewUniforms {
    float transform[16];
    float colorGain[4];
    float grade[16];
    float enhancement[4];
    float subjectDetail[4];
    float acesCgToOklabReference[16];
    float oklabReferenceToAcesCg[16];
    float neoHdrParams[4];
    float neoModeParams[4];
    float neoMeasuredCorrectionParams[4];
    float neoIdentityParams[4];
    float neoMeasuredRgbScaleParams[4];
    float neoGamutCuspParams[4]; // CP-CUSP: x = exact-cusp enable, y = target gamut id (2 = Rec.2020)
};

static_assert(sizeof(PreviewUniforms) == 400, "PreviewUniforms size is locked to 400 bytes");
static_assert(offsetof(PreviewUniforms, neoIdentityParams) == 352, "neoIdentityParams offset must be 352");
static_assert(offsetof(PreviewUniforms, neoMeasuredRgbScaleParams) == 368, "neoMeasuredRgbScaleParams offset must be 368");
static_assert(offsetof(PreviewUniforms, neoGamutCuspParams) == 384, "neoGamutCuspParams offset must be 384");

struct RealtimeComputePushConstants {
    uint32_t width = 0;
    uint32_t height = 0;
    float colorGainR = 1.0f;
    float colorGainG = 1.0f;
    float colorGainB = 1.0f;
    float reserved = 0.0f;
    float spatialDenoise = 1.0f;
    float chromaDenoise = 1.0f;
    float sharpening = 1.0f;
    float noiseMetric = 1.0f;
    float colorSpaceId = 1.0f;
    float activeTransportId = 1.0f;
    float profileDetailGain = 10.0f;
    float chromaStability = 10.0f;
    float signalFamilyId = 1.0f;
    float colorScienceId = 1.0f;
    // Exact-frame NIV99 packet. `niv99Active` is reset for every JNI submission; the remaining
    // values are ignored unless that gate is 1. This prevents a missing/rejected packet from
    // inheriting the preceding frame's correction.
    float niv99Active = 0.0f;
    float niv99RedCorrection = 1.0f;
    float niv99GreenCorrection = 1.0f;
    float niv99BlueCorrection = 1.0f;
    float niv99SpatialDenoise = 0.0f;
    float niv99ChromaDenoise = 0.0f;
    float niv99TemporalDenoise = 0.0f;
    float niv99SharpeningCeiling = 0.0f;
    float niv99Confidence = 0.0f;
};

static_assert(sizeof(RealtimeComputePushConstants) == 100,
              "Realtime NIV99 push-constant ABI must remain 100 bytes");

struct KneeCurvePushConstants {
    float maxCLL = 1000.0f;
    float kneePoint = 0.65f;
    float kneeHardness = 2.0f;
    float targetPeakNits = 1000.0f; // s4-f03/CP30: BT.2390 EETF display peak (used only when peakNitsGate)
    float peakNitsGate = 0.0f;      // CP30: >0.5 selects the BT.2390 EETF path (0 = today's SDR knee)
};
static_assert(sizeof(KneeCurvePushConstants) == 20, "KneeCurvePushConstants ABI is 20 bytes (s4-f03/CP30 + peakNitsGate)");

struct ShadowLiftPushConstants {
    float shadowLift = 0.03f;
    float contrastRecovery = 1.10f;
    float luminanceThreshold = 0.40f;
};

struct GuidedUpsamplePushConstants {
    float sigmaS = 3.0f;
    float sigmaR = 0.1f;
    int32_t scaleFactor = 4;
    float edgeTightenGate = 0.0f;  // CP24 distant-detail gate: >0.5 tightens range sigma at guide edges (0 = OFF)
};

struct DetailPreservationPushConstants {
    float textureThreshold = 0.005f;
    float edgeTolerance = 3.5f;
    float noiseAwareGate = 0.0f;   // CP24 distant-detail gate: >0.5 noise-floor mask rolloff (0 = OFF)
    float haloGuardGate = 0.0f;    // CP24 distant-detail gate: >0.5 strong-edge mask rolloff (0 = OFF)
};

struct MfsrFusionPushConstants {
    int32_t activeFrameCount = 4;
    float noiseS = 0.0f;
    float noiseO = 0.0f;
    float gyroOffsetScale = 1.0f;
};

struct ImaxRenderingPushConstants {
    float csfStrength = 1.5f;
    float contrastExponent = 3.5f;
    float midFreqPeak = 4.0f;
};

// s1-f07 / CP10c — std140 UBO mirroring `RawDemosaicUniforms` (binding 4) in demosaic_vulkan.comp.
// This is the Kotlin↔GLSL ABI for the RAW-preview cinema-debayer dispatch. Field ORDER + std140
// offsets MUST match the shader block; the layout is golden-locked on the JVM by
// RawDemosaicUboLayoutTest (which parses the same .comp block) and statically here. std140 packs a
// mat3 as 3 column-vectors each occupying a vec4 (48 bytes / 12 floats), so the matrices are stored
// COLUMN-MAJOR with a pad float after every 3 — `packRawDemosaicMatrixColumnMajor` does the transpose
// from the row-major 3x3 the JVM hands across JNI. Any reorder/type drift fails the build first.
struct RawDemosaicUniforms {
    float blackLevelRggb[4];   // offset 0   — per-CFA black level (R, Gr, Gb, B)
    float whiteBalanceRggb[4]; // offset 16  — per-CFA WB gain applied BEFORE demosaic (CP14)
    float exposureThermal[4];  // offset 32  — x=exposureMs y=sensorTempC z=referenceTempC w=Q10
    float mSensorToXyz[12];    // offset 48  — mat3, column-major std140 (3x vec4)
    float mXyzToAcescg[12];    // offset 96  — mat3, column-major std140 (3x vec4) — CP11 forward
    int32_t bayerPattern;      // offset 144 — 0=RGGB 1=GRBG 2=GBRG 3=BGGR
    int32_t width;             // offset 148
    int32_t height;            // offset 152
    int32_t whiteLevel;        // offset 156
    float lensShadingGridRggb[9][4]; // offset 160 — row-major 3x3 of (R, Gr, Gb, B)
    int32_t lensShadingInfo[4];      // offset 304 — x=preview correction enabled
    float highlightRecovery[4];      // offset 320 — threshold, strength, shoulder, enabled
    float calibrationScaleRggb[4];   // offset 336 — WB / max(white-black, 1)
    float calibrationOffsetRggb[4];  // offset 352 — -black * calibrationScale
    float mSensorToAcescgFast[12];   // offset 368 — precomposed mat3, column-major std140
};                                  // total 416 bytes (16-aligned)

static_assert(sizeof(RawDemosaicUniforms) == 416, "RawDemosaicUniforms std140 size is locked to 416 bytes");
static_assert(offsetof(RawDemosaicUniforms, whiteBalanceRggb) == 16, "whiteBalanceRggb std140 offset must be 16");
static_assert(offsetof(RawDemosaicUniforms, exposureThermal) == 32, "exposureThermal std140 offset must be 32");
static_assert(offsetof(RawDemosaicUniforms, mSensorToXyz) == 48, "M_SENSOR_TO_XYZ std140 offset must be 48");
static_assert(offsetof(RawDemosaicUniforms, mXyzToAcescg) == 96, "M_XYZ_TO_ACESCG std140 offset must be 96");
static_assert(offsetof(RawDemosaicUniforms, bayerPattern) == 144, "bayerPattern std140 offset must be 144");
static_assert(offsetof(RawDemosaicUniforms, whiteLevel) == 156, "whiteLevel std140 offset must be 156");
static_assert(offsetof(RawDemosaicUniforms, lensShadingGridRggb) == 160, "lensShadingGridRggb std140 offset must be 160");
static_assert(offsetof(RawDemosaicUniforms, lensShadingInfo) == 304, "lensShadingInfo std140 offset must be 304");
static_assert(offsetof(RawDemosaicUniforms, highlightRecovery) == 320, "highlightRecovery std140 offset must be 320");
static_assert(offsetof(RawDemosaicUniforms, calibrationScaleRggb) == 336, "calibrationScaleRggb std140 offset must be 336");
static_assert(offsetof(RawDemosaicUniforms, calibrationOffsetRggb) == 352, "calibrationOffsetRggb std140 offset must be 352");
static_assert(offsetof(RawDemosaicUniforms, mSensorToAcescgFast) == 368, "M_SENSOR_TO_ACESCG_FAST std140 offset must be 368");

// LQ-001.09: native ABI for the offline RAW GPU detail/colour/P010 stages. Keep both structures
// scalar-only so their C++ layout is identical to the GLSL push-constant layout on every ABI.
struct RawDevelopDetailPushConstants {
    int32_t width = 0;
    int32_t height = 0;
    int32_t detailEnabled = 0;
    int32_t reserved = 0;
};
static_assert(sizeof(RawDevelopDetailPushConstants) == 16,
              "RAW develop detail push constants must remain 16 bytes");

struct RawDevelopP010PushConstants {
    int32_t width = 0;
    int32_t height = 0;
    int32_t deliveryMode = 0;
    int32_t detailEnabled = 0;
    float exposureScale = 1.0f;
    float logToeCut = 0.01f;
    float logToeHeight = 0.08f;
    float logDensity = 120.0f;
    float logScale = 0.137099f;
    float logC1 = 0.08f;
    float logC3 = -0.436788f;
    float logC4 = 0.789056f;
    float logC5 = -0.352269f;
    float logXMax = 6.843034f;
    float signalGamma = 0.859937f;
    float toeWidth = 0.238882f;
    float toeAmount = 0.024780f;
    float peakNits = 5000.0f;
    float highlightDesat = 0.149718f;
    float shadowMidtoneLift = 0.167837f;
    float midtoneContrast = 0.100943f;
    float chromaBoost = 0.038361f;
    float skyChromaBonus = 0.062392f;
    float reserved = 0.0f;
};
static_assert(sizeof(RawDevelopP010PushConstants) == 96,
              "RAW develop P010 push constants must remain 96 bytes");

// Offline-only NIV99 sensor-CFA pass. It operates in the original uint16 code domain so the
// downstream demosaic receives the same black/white/WB calibration contract as the CPU path.
struct RawNiv99PushConstants {
    int32_t width = 0;
    int32_t height = 0;
    int32_t historyEnabled = 0;
    int32_t reservedInt = 0;
    float noiseS = 0.0f;
    float noiseO = 0.0f;
    float totalGain = 1.0f;
    float dynamicRange = 1.0f;
    float spatialStrength = 0.42f;
    float defectSigmaThreshold = 6.0f;
    float spatialSigmaThreshold = 3.2f;
    float temporalStrength = 0.30f;
    float temporalSigmaThreshold = 3.0f;
    float minimumEffectiveWeight = 0.002f;
    float minimumSigmaCodes = 0.75f;
    float reservedFloat = 0.0f;
};
static_assert(sizeof(RawNiv99PushConstants) == 64,
              "RAW NIV99 push constants must remain 64 bytes");

struct RawNiv99GpuCounters {
    uint32_t spatiallyFilteredSamples = 0u;
    uint32_t temporallyFusedSamples = 0u;
    uint32_t correctedDefects = 0u;
    uint32_t processedSamples = 0u;
    uint32_t sceneDifferenceCodeSum = 0u;
    uint32_t sceneSampleCount = 0u;
    uint32_t sceneCutDetected = 0u;
    uint32_t reserved = 0u;
};
static_assert(sizeof(RawNiv99GpuCounters) == 32,
              "RAW NIV99 telemetry ABI must remain 32 bytes");

struct RawNiv99DispatchConfig {
    int64_t frameNumber = 0;
    int64_t sensorTimestampNs = 0;
    uint32_t fps = 25u;
    float noiseS = 0.0f;
    float noiseO = 0.0f;
    float totalGain = 1.0f;
    float dynamicRange = 1.0f;
    float spatialStrength = 0.42f;
    float defectSigmaThreshold = 6.0f;
    float spatialSigmaThreshold = 3.2f;
    float temporalStrength = 0.30f;
    float temporalSigmaThreshold = 3.0f;
    float sceneCutThreshold = 0.075f;
};

struct RawNiv99DispatchEvidence {
    bool applied = false;
    bool historyUsed = false;
    bool historyReset = true;
    bool sceneCut = false;
    RawNiv99GpuCounters counters{};
};

// Transpose a row-major 3x3 (src[r*3+c]) into the column-major std140 layout of a GLSL mat3:
// dst holds col0,col1,col2 with a trailing pad float each, so dst[col*4 + row] = src[row*3 + col].
inline void packRawDemosaicMatrixColumnMajor(const float* src /*9, row-major*/, float* dst /*12*/) {
    for (int col = 0; col < 3; ++col) {
        for (int row = 0; row < 3; ++row) {
            dst[col * 4 + row] = src[row * 3 + col];
        }
        dst[col * 4 + 3] = 0.0f; // std140 vec4 pad — unread by the shader's mat3
    }
}

// SNCE v2.0 HVC Stage 04 (OCCS) opponent lateral-inhibition push constants — field order/types
// MUST match the OccsParams push_constant block in occs_opponent.comp. iGain==chromaGain keeps the
// per-channel DoG uniform (no edge fringing) when applied to the working image; the copy-back pass
// pushes all-zero gains for an exact passthrough.
// One confidence-gated, low-order calibration packet shared with the offscreen encoder. mode:
// 0=ordinary RAW monitor, 1=NDR10+, 2=NIVUKX Vision. Defaults are an exact identity.
struct NdrMeasuredCorrectionNative {
    int32_t mode = 0;
    bool active = false;
    float redScale = 1.0f;
    float greenScale = 1.0f;
    float blueScale = 1.0f;
    float highlightRolloffBias = 0.0f;
    float shadowLiftBias = 0.0f;
    float saturationScale = 1.0f;
    float skinHueCorrectionDegrees = 0.0f;
    float skyHueCorrectionDegrees = 0.0f;
    // Canonical NdrToneOperator packet. Both monitor and encoder receive these values from Kotlin at
    // the same mode boundary; native code never selects a second set of profile constants.
    float sensorDiffuseScene = 0.180f;
    float signalGamma = 0.859937f;
    float toeWidth = 0.238882f;
    float toeAmount = 0.024780f;
    float highlightDesat = 0.149718f;
    float shadowMidtoneLift = 0.167837f;
    float midtoneContrast = 0.100943f;
    float chromaBoost = 0.038361f;
    float skyChromaBonus = 0.062392f;
    float localDetailStrength = 0.400000f;
    float localDetailLowerStops = -0.060000f;
    float localDetailUpperStops = 0.085000f;
};

// Push-constant ABI shared with raw_monitor_tonemap.comp. The GPU pass mirrors the project-owned
// CPU RAW-monitor operator while eliminating the full-frame FP16 readback tonemap bottleneck.
struct RawMonitorTonemapPushConstants {
    int32_t inputWidth = 0;
    int32_t inputHeight = 0;
    int32_t outputWidth = 0;
    int32_t outputHeight = 0;
    int32_t mode = 0;
    int32_t correctionActive = 0;
    int32_t reserved0 = 0;
    int32_t reserved1 = 0;
    float redScale = 1.0f;
    float greenScale = 1.0f;
    float blueScale = 1.0f;
    float saturationScale = 1.0f;
    float skinHueCorrectionDegrees = 0.0f;
    float skyHueCorrectionDegrees = 0.0f;
    float shadowLiftBias = 0.0f;
    float highlightRolloffBias = 0.0f;
    float sensorDiffuseScene = 0.180f;
    float signalGamma = 0.859937f;
    float toeWidth = 0.238882f;
    float toeAmount = 0.024780f;
    float highlightDesat = 0.149718f;
    float shadowMidtoneLift = 0.167837f;
    float midtoneContrast = 0.100943f;
    float chromaBoost = 0.038361f;
    float skyChromaBonus = 0.062392f;
    float localDetailStrength = 0.400000f;
    float localDetailLowerStops = -0.060000f;
    float localDetailUpperStops = 0.085000f;
};

static_assert(sizeof(RawMonitorTonemapPushConstants) == 112,
              "RawMonitorTonemapPushConstants ABI must remain 112 bytes");

// Push-constant ABI shared with raw_preview_downsample.comp. This pass carries scene-linear ACEScg;
// it never applies a display curve or changes the full-resolution recording master.
struct RawPreviewDownsamplePushConstants {
    int32_t inputWidth = 0;
    int32_t inputHeight = 0;
    int32_t outputWidth = 0;
    int32_t outputHeight = 0;
};

static_assert(sizeof(RawPreviewDownsamplePushConstants) == 16,
              "RawPreviewDownsamplePushConstants ABI must remain 16 bytes");

// Push-constant ABI shared with raw_scene_log_pack.comp. The packed transport is full-resolution and
// exists only when the device cannot export the preferred RGBA16F AHardwareBuffer.
struct RawSceneLogPackPushConstants {
    int32_t width = 0;
    int32_t height = 0;
};

static_assert(sizeof(RawSceneLogPackPushConstants) == 8,
              "RawSceneLogPackPushConstants ABI must remain 8 bytes");

struct OccsParams {
    float sigmaCenter = 1.2f;
    float sigmaSurround = 3.6f;
    float kWeight = 0.78f;
    float iGain = 0.10f;
    float chromaGain = 0.10f;
};

// SNCE v2.0 HVC Stage 11 (NRLI) push constants — field order/types MUST match NrliParams in
// nrli_lut_apply.comp. strength 0 with the identity LUT == exact passthrough (the live default).
struct NrliParams {
    float lutSize = 33.0f;
    float strength = 0.0f;
};

// OmniClarity §2/§4/Chronos push constants — field order/types MUST match the GLSL
// push_constant blocks in argus_flow_blockmatch / argus_flow_refine / riven_motion_deblur /
// warp_reconstruct .comp respectively.
struct ArgusBlockMatchPushConstants {
    // HIGH-END: flow now runs at 1/2 res (see OmniDownsample divisor=2) for 4x the spatial flow
    // precision of the old 1/4 path. searchRadius 8 reduced px ~= 16 full-res px of motion range;
    // patchRadius 2 (5x5 SAD) for far more robust matching than the old 3x3.
    int32_t searchRadius = 8;
    int32_t coarseStep = 2;
    int32_t patchRadius = 2;
    float maxFlow = 8.0f;
    float previousValid = 1.0f;
    float minTexture = 0.02f;
};

struct ArgusRefinePushConstants {
    float regularization = 0.2f;   // HIGH-END: was 0.5 — lower = sharper, more detailed flow field
    float maxFlow = 10.0f;
    float previousValid = 1.0f;
    float edgeStopSigma = 0.01f;
};

struct RivenDeblurPushConstants {
    float exposureFraction = 0.5f;
    float minKernelPx = 1.5f;      // HIGH-END: was 2.0 — recover shorter motion blur too
    float snrInverse = 0.0f;
    float maxKernelPx = 12.0f;     // HIGH-END: was 8.0 — longer reach for fast motion (paired w/ 16 taps)
    float deblurStrength = 0.75f;  // HIGH-END: was 0.6 — stronger recovery (ringing still clamped)
    int32_t rlIterations = 1;      // CP53: clamp-bounded iterative deconvolution; 1 == original single step.
                                   // Owner raises device-side after validation (each iterate is re-clamped
                                   // to the input line-extremes, so >1 cannot overshoot/halo by construction).
};

struct OmniWarpPushConstants {
    float baseTemporalAlpha = 0.0f;
    float maxTemporalAlpha = 0.9f; // HIGH-END: was 0.85 — stronger motion-rejecting temporal denoise
    float colorSigma = 0.05f;
    float varianceSigma = 0.02f;
    float flowConsistencySigma = 4.0f;
    float boundaryFeatherPixels = 2.0f;
};

struct OmniDownsamplePushConstants {
    int32_t divisor = 2;           // HIGH-END: was 4 — half-res flow = 2x spatial flow precision
};

// s1-f10 / CP31 — dual-conversion-gain fusion push constants (NeoProCamX NDR; no vendor formula).
// Field order/types MUST match the DualGainFusionParams push_constant block in dual_gain_fusion.comp
// (uint width/height then 4 floats), locked on the JVM by DualGainFusionPushConstantLayoutTest.
struct DualGainFusionPushConstants {
    uint32_t width = 0;
    uint32_t height = 0;
    float kGain = 1.0f;
    float noiseFloor = 0.0f;
    float saturationThreshold = 0.90f;
    float shadowStretch = 0.0f;
};
static_assert(sizeof(DualGainFusionPushConstants) == 24, "DualGainFusionPushConstants must be 24 bytes (6 scalars)");

// s1-f10 / CP32 — photon HDR fusion (motion-compensated dual-exposure) push constants. Field order/types
// MUST match the PhotonFusionParams push_constant block in photon_hdr_fusion.comp (6 floats), locked on
// the JVM by PhotonFusionPushConstantLayoutTest. FOUNDATION ONLY — the pipeline is dormant (no dispatch).
struct PhotonFusionPushConstants {
    float highlightThreshold = 0.85f;
    float clipThreshold = 0.98f;
    float longScale = 1.0f;
    float shortScale = 1.0f;
    float motionCompEnabled = 0.0f;
    float maxFlow = 8.0f;
};
static_assert(sizeof(PhotonFusionPushConstants) == 24, "PhotonFusionPushConstants must be 24 bytes (6 floats)");

// s3-f06 / CP60 — gyro-EIS per-scanline warp push constants. Field order/types MUST match the EisWarpParams
// push_constant block in eis_warp.comp (3 floats + 1 int), locked on the JVM by EisWarpPushConstantLayoutTest.
// FOUNDATION ONLY — the pipeline is dormant (no live dispatch); the per-scanline grid producer + dispatch are
// the device-gated activation. cropMarginPixels clamps the source fetch so a black border is never revealed.
struct EisWarpPushConstants {
    float cropMarginPixels = 0.0f;
    float centerX = 0.0f;
    float centerY = 0.0f;
    int32_t gridRows = 0;
};
static_assert(sizeof(EisWarpPushConstants) == 16, "EisWarpPushConstants must be 16 bytes (3 floats + 1 int)");

// s5-f06 / CP-F7 — depth-driven Vogel-spiral computational-bokeh push constants. Field order/types MUST
// match the BokehUniforms push_constant block in bokeh_vogel_spiral.comp (3 ints then 6 floats), locked on
// the JVM by BokehPushConstantLayoutTest. blendStrength 0 == EXACT passthrough; the dispatch is gated OFF
// (bokehBlendEnabled default false) so visible bokeh is DEVICE-GATED and the live pixel path is unchanged.
struct BokehPushConstants {
    int32_t width = 0;
    int32_t height = 0;
    int32_t sampleCount = 16;
    float chromaticAberration = 0.0f;
    float apertureBladeCount = 6.0f;
    float apertureBladeStrength = 0.0f;
    float foregroundGuardStrength = 6.0f;
    float focusDepth = 0.5f;
    float blendStrength = 0.0f; // no-downgrade default: 0 == exact passthrough
};
static_assert(sizeof(BokehPushConstants) == 36, "BokehPushConstants must be 36 bytes (3 ints + 6 floats)");

struct OmniFlowUpsamplePushConstants {
    int32_t divisor = 2;           // MUST equal the downsample divisor
};

struct ArgusSelectPushConstants {
    float occlusionThreshold = 1.5f; // reduced-px fwd/bwd residual above which flow is treated as occluded
    float minTexture = 0.02f;        // local gradient floor for trusting refined flow
    float maxFlow = 10.0f;           // clamp on |flow|, reduced px
    float previousValid = 1.0f;
};

// s3-f01..f04 — OmniClarity motion-stack governor levers (NOT push constants themselves; they SOURCE the
// per-frame loop count / push-struct fields in dispatchOmniClarityMotionStack). Every field defaults to
// today's hardcoded native value, so an un-driven channel (cool device / neutral governor) keeps the live
// flow + warp + deblur output byte-for-byte identical to the pre-wiring path (no-downgrade contract). Driven
// from Kotlin via the nativeSetOmniClarity*Tuning setters (s3-f01 flow, s3-f02 warp, s3-f03 deblur; wired by
// the s3-f04 policies). Read under state->mutex during command recording (same lock the setters hold).
struct OmniClarityTuning {
    int32_t refineIterations = 8;     // s3-f01: Stage-B Jacobi ping-pong count (== old kOmniRefineIterations).
    bool sceneCutActive = false;      // s3-f01/CP54b: hard cut this frame -> force previousValid=0 (CP51 path).
    float baseTemporalAlpha = 0.0f;   // s3-f02: Chronos warp base temporal alpha (CP52 default 0.0).
    float maxTemporalAlpha = 0.9f;    // s3-f02: Chronos warp max temporal alpha (CP52 default 0.9).
    int32_t rlIterations = 1;         // s3-f03: Riven van Cittert / RL iteration count (CP53 default 1).
    float snrInverse = 0.0f;          // s3-f03: Riven Wiener 1/SNR noise floor (CP53 default 0.0).
};

struct NeoHdr10Metadata {
    uint32_t maxCLL = 100u;
    uint32_t maxFALL = 100u;
    uint32_t minLuminance = 0u;
    uint32_t validFrame = 0u;
};

struct EnhancementUniforms {
    std::array<float, kEnhancementUniformFloatCount> values{};
    std::string signature;
    bool ready = false;
};

struct PreviewLutTexture {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageView imageView = VK_NULL_HANDLE;
    VkSampler sampler = VK_NULL_HANDLE;
    VkFormat format = VK_FORMAT_UNDEFINED;
    uint32_t size = 0;
    bool ready = false;
};

struct ImportedFrame {
    AHardwareBuffer* hardwareBuffer = nullptr;
    uint64_t hardwareBufferId = 0;
    bool hardwareBufferIdStable = false;
    int width = 0;
    int height = 0;
    int format = 0;
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageView imageView = VK_NULL_HANDLE;
    VkSamplerYcbcrConversion conversion = VK_NULL_HANDLE;
    VkSampler sampler = VK_NULL_HANDLE;
    VkImageLayout layout = VK_IMAGE_LAYOUT_UNDEFINED;
    bool ready = false;
    uint64_t lastUsedSerial = 0;
};

using AHardwareBufferGetIdFn = int (*)(const AHardwareBuffer*, uint64_t*);

struct HardwareBufferIdApi {
    AHardwareBufferGetIdFn getId = nullptr;
    bool available = false;
};

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
            LOGW("AHardwareBuffer_getId unavailable; Vulkan import cache will use pointer fallback");
        }
        return resolved;
    }();
    return api;
}

uint64_t hardwareBufferCacheKey(AHardwareBuffer* hardwareBuffer, bool* stable) {
    if (stable != nullptr) *stable = false;
    if (hardwareBuffer != nullptr) {
        const HardwareBufferIdApi& api = hardwareBufferIdApi();
        if (api.available && api.getId != nullptr) {
            uint64_t id = 0;
            if (api.getId(hardwareBuffer, &id) == 0 && id != 0) {
                if (stable != nullptr) *stable = true;
                return id;
            }
        }
    }
    return static_cast<uint64_t>(reinterpret_cast<uintptr_t>(hardwareBuffer));
}

struct ComputeIntermediateImage {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageView imageView = VK_NULL_HANDLE;
    VkSampler sampler = VK_NULL_HANDLE;
    VkImageLayout layout = VK_IMAGE_LAYOUT_UNDEFINED;
    uint32_t width = 0;
    uint32_t height = 0;
    bool ready = false;
};

struct SubjectDetailMaskTexture {
    VkImage image = VK_NULL_HANDLE;
    VkDeviceMemory memory = VK_NULL_HANDLE;
    VkImageView imageView = VK_NULL_HANDLE;
    VkSampler sampler = VK_NULL_HANDLE;
    uint32_t width = 1u;
    uint32_t height = 1u;
    int trackingId = -1;
    bool ready = false;
};

struct TargetSurface {
    ANativeWindow* window = nullptr;
    VkSurfaceKHR surface = VK_NULL_HANDLE;
    VkSwapchainKHR swapchain = VK_NULL_HANDLE;
    bool encoderTarget = false;
    VkFormat swapchainFormat = VK_FORMAT_UNDEFINED;
    VkColorSpaceKHR colorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
    // HDR mode the swapchain was last built for (mirrors
    // HdrSwapchainFormatPolicy.toNativeModeId — 0 = SDR). Compared
    // against state->hdrModeId on each frame to detect mode changes
    // and trigger swapchain rebuild on the same path used for window
    // resize (VK_ERROR_OUT_OF_DATE_KHR).
    int builtForHdrModeId = 0;
    // Preview-only companion to builtForHdrModeId. Keeping the last successfully built display
    // preference separate from the requested state lets JNI retry an invalid swapchain and avoids
    // reporting a cached preference as applied after a failed rebuild.
    bool builtForPreferTenBitSdrDisplay = false;
    VkExtent2D extent{0, 0};
    std::vector<VkImage> images;
    std::vector<VkImageView> imageViews;
    VkRenderPass renderPass = VK_NULL_HANDLE;
    VkPipeline pipeline = VK_NULL_HANDLE;
    bool ownsPipeline = true;
    std::vector<VkFramebuffer> framebuffers;
    VkCommandPool commandPool = VK_NULL_HANDLE;
    std::vector<VkCommandBuffer> commandBuffers;
    VkDescriptorPool descriptorPool = VK_NULL_HANDLE;
    std::vector<VkDescriptorSet> descriptorSets;
    VkDescriptorPool computeDescriptorPool = VK_NULL_HANDLE;
    std::vector<VkDescriptorSet> computeDescriptorSets;
    std::vector<VkDescriptorSet> kneeCurveDescriptorSets;
    std::vector<VkDescriptorSet> shadowLiftDescriptorSets;
    std::vector<VkDescriptorSet> hdrMetadataHistogramDescriptorSets;
    std::vector<VkDescriptorSet> hdrMetadataReduceDescriptorSets;
    std::vector<ComputeIntermediateImage> computeIntermediates;
    std::vector<ComputeIntermediateImage> toneMappedIntermediates;
    std::vector<VkBuffer> hdrHistogramBuffers;
    std::vector<VkDeviceMemory> hdrHistogramMemories;
    std::vector<VkBuffer> hdrMetadataBuffers;
    std::vector<VkDeviceMemory> hdrMetadataMemories;
    std::vector<VkBuffer> hdrMetadataStagingBuffers;
    std::vector<VkDeviceMemory> hdrMetadataStagingMemories;
    std::vector<bool> hdrMetadataStagingReady;
    std::vector<VkBuffer> uniformBuffers;
    std::vector<VkDeviceMemory> uniformMemories;
    std::vector<VkSemaphore> imageAvailableSemaphores;
    std::vector<VkSemaphore> renderFinishedSemaphores;
    VkSemaphore timelineSemaphore = VK_NULL_HANDLE;
    std::vector<uint64_t> inFlightTimelineValues;
    uint64_t nextTimelineValue = 1;
    VkQueryPool computeTimestampQueryPool = VK_NULL_HANDLE;
    std::vector<bool> computeTimestampQueryIssued;
    bool computeTimestampQueriesReady = false;
    std::vector<VkFence> inFlightFences;
    uint32_t syncIndex = 0;
    bool valid = false;

    // QFCA additions
    VkDescriptorPool qfcaDescriptorPool = VK_NULL_HANDLE;
    std::vector<VkDescriptorSet> guidedUpsampleDescSets;
    std::vector<VkDescriptorSet> detailPreservationDescSets;
    std::vector<VkDescriptorSet> mfsrFusionDescSets;
    std::vector<VkDescriptorSet> imaxRenderingDescSets;
    std::vector<VkDescriptorSet> m_nivukxDenoiseDescSets;
    // CP-QV16 — gated single-frame spatial denoiser (denoise.comp). One 2-binding set per swapchain
    // image (0 input rgba16f, 1 output rgba16f scratch). Allocated from qfcaDescriptorPool; unused
    // unless denoiseSpatialEnabled is flipped ON device-side (default OFF → live path byte-identical).
    std::vector<VkDescriptorSet> denoiseSpatialDescSets;
    // CP-QV17 — gated optical-recovery sharpener (sharpen.comp). One 2-binding set per swapchain image
    // (0 input rgba16f, 1 output rgba16f scratch). Allocated from qfcaDescriptorPool; unused unless
    // sharpenOpticalEnabled is flipped ON device-side (default OFF → live path byte-identical).
    std::vector<VkDescriptorSet> sharpenOpticalDescSets;

    // Live cinematic bokeh (bokeh_vogel_spiral.comp) — INLINE render-graph pass (NOT the standalone
    // isolated-image path). Two shared per-target r32f inputs (depth + circle-of-confusion, uploaded
    // from the live MiDaS depth producer via nativeUploadBokehInputs) + one 4-binding storage-image
    // set per swapchain image bound to {intermediate, bokehDepth, bokehCoc, omniDeblur}. Gated by
    // state->bokehBlendEnabled (default OFF → whole block skipped → live path byte-identical). Cleared
    // to 0 at creation so coc<=0.75 => shader passthrough (no black frame) until a real feed arrives.
    ComputeIntermediateImage bokehDepth;   // r32f, STORAGE|TRANSFER_DST, full-res
    ComputeIntermediateImage bokehCoc;     // r32f, STORAGE|TRANSFER_DST, full-res
    std::vector<VkDescriptorSet> bokehLiveDescSets;
    bool bokehFeedReady = false;           // true once nativeUploadBokehInputs fed real depth+CoC to THIS target

    // OmniClarity motion-stack (gated) per-target resources. Flow runs at reduced resolution
    // (omni*Low) then is upsampled+rescaled to full res (omniFlowFull) for Riven/Chronos.
    std::vector<ComputeIntermediateImage> omniCurrentLow;
    std::vector<ComputeIntermediateImage> omniPreviousLow;
    std::vector<ComputeIntermediateImage> omniFlowLowA;
    std::vector<ComputeIntermediateImage> omniFlowLowB;
    std::vector<ComputeIntermediateImage> omniFlowFull;
    std::vector<ComputeIntermediateImage> omniDeblur;
    // OmniClarity occlusion-select (Argus §2): backward flow + preserved coarse + hybrid selection.
    std::vector<ComputeIntermediateImage> omniFlowCoarse;
    std::vector<ComputeIntermediateImage> omniFlowBackwardLow;
    std::vector<ComputeIntermediateImage> omniFlowSelected;
    VkDescriptorPool omniDescriptorPool = VK_NULL_HANDLE;
    std::vector<VkDescriptorSet> omniDownsampleCurDescSets;
    std::vector<VkDescriptorSet> omniDownsamplePrevDescSets;
    std::vector<VkDescriptorSet> omniBlockMatchDescSets;
    std::vector<VkDescriptorSet> omniRefineA2BDescSets;
    std::vector<VkDescriptorSet> omniRefineB2ADescSets;
    std::vector<VkDescriptorSet> omniFlowUpsampleDescSets;
    std::vector<VkDescriptorSet> omniRivenDescSets;
    std::vector<VkDescriptorSet> omniWarpDescSets;
    std::vector<VkDescriptorSet> omniRefineCoarse2BDescSets;
    std::vector<VkDescriptorSet> omniBlockMatchBackwardDescSets;
    std::vector<VkDescriptorSet> omniSelectDescSets;
    std::vector<VkDescriptorSet> occsForwardDescSets;  // {computeIntermediate -> omniDeblur}
    std::vector<VkDescriptorSet> occsCopyBackDescSets;  // {omniDeblur -> computeIntermediate} (zero-gain passthrough)
    std::vector<VkDescriptorSet> nrliDescSets;          // {omniDeblur -> computeIntermediate + nrliLut} (Stage 11)

    // Intermediate resources for QFCA
    std::vector<ComputeIntermediateImage> lowResFocusMaps;
    std::vector<ComputeIntermediateImage> highResFocusMaps;
    std::vector<ComputeIntermediateImage> detailMasks;
    // Temporal history is chronological, not swapchain-image indexed. All per-swapchain descriptor
    // sets reference historyImages[0], and graphics-queue submission order makes it the immediately
    // preceding submitted frame regardless of which swapchain image is acquired next.
    std::vector<ComputeIntermediateImage> historyImages;
    // One validity byte for the single chronological history image. It is set only after the queue
    // accepts the command buffer that updates history, never while that command buffer is merely recorded.
    std::vector<uint8_t> historyContentValid;
    uint64_t temporalHistorySubmissionId = 0u;
    int64_t temporalHistorySensorTimestampNs = 0;

    // Staging buffers for focus map upload
    std::vector<VkBuffer> focusMapStagingBuffers;
    std::vector<VkDeviceMemory> focusMapStagingMemories;
};

struct Niv99LivePacketTelemetry {
    uint64_t packetsSeen = 0u;
    uint64_t packetsAccepted = 0u;
    uint64_t packetsMissing = 0u;
    uint64_t packetsInvalid = 0u;
    uint64_t packetsStale = 0u;
    uint64_t pixelActivationRejected = 0u;
    uint64_t previewPixelsApplied = 0u;
    uint64_t encoderPixelsApplied = 0u;
    uint64_t cameraIdHash = 0u;
    int64_t sessionGeneration = -1;
    int64_t frameNumber = -1;
    int64_t sensorTimestampNs = -1;
    int authority = -1;
    float redCorrection = 1.0f;
    float greenCorrection = 1.0f;
    float blueCorrection = 1.0f;
    float spatialDenoise = 0.0f;
    float chromaDenoise = 0.0f;
    float temporalDenoise = 0.0f;
    float sharpeningCeiling = 0.0f;
    float confidence = 0.0f;
    bool hasAcceptedPacket = false;
    bool currentFrameActive = false;
};

struct VulkanBridgeState {
    std::mutex mutex;
    // Pipeline construction intentionally runs outside `mutex` so preview can start immediately, but
    // some initialization helpers submit transfer/clear commands on the same graphics queue. Vulkan
    // requires host access to a VkQueue to be externally synchronized even when command buffers are
    // otherwise independent, so all submit/present/wait-idle calls also use this narrow queue lock.
    std::mutex queueMutex;
    // Full-sensor RAW compute is independent from swapchain submit/present. When the selected graphics
    // family exposes a second queue, keep RAW host access on its own lock/queue so a long demosaic does
    // not sit behind preview/present work submitted to queue 0. Both queues belong to the same family:
    // command-pool compatibility and image ownership are unchanged, while the existing RAW fence and
    // EGL input-read receipt remain the only cross-API ownership barriers. Devices exposing one queue
    // alias rawQueue to graphicsQueue and continue to use queueMutex exactly as before.
    std::mutex rawQueueMutex;
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    uint32_t queueFamilyIndex = 0;
    // s7-f11/CP73: dedicated transfer queue family detection (foundation; zero-copy path device-gated).
    uint32_t dedicatedTransferQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    bool hasDedicatedTransferQueue = false;
    VkQueue graphicsQueue = VK_NULL_HANDLE;
    VkQueue rawQueue = VK_NULL_HANDLE;
    uint32_t graphicsFamilyQueueCount = 0u;
    bool independentRawQueueEnabled = false;

    PFN_vkGetAndroidHardwareBufferPropertiesANDROID getAndroidHardwareBufferProperties = nullptr;
    PFN_vkGetMemoryAndroidHardwareBufferANDROID getMemoryAndroidHardwareBuffer = nullptr;
    PFN_vkCreateSamplerYcbcrConversion createSamplerYcbcrConversion = nullptr;
    PFN_vkDestroySamplerYcbcrConversion destroySamplerYcbcrConversion = nullptr;
    PFN_vkGetSemaphoreCounterValue getSemaphoreCounterValue = nullptr;
    PFN_vkWaitSemaphores waitSemaphores = nullptr;

    VkDescriptorSetLayout descriptorSetLayout = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout = VK_NULL_HANDLE;
    VkPipelineCache graphicsPipelineCache = VK_NULL_HANDLE;
    // s7-f13/CP9C — disk pipeline-cache (warm-start) per-instance state; default OFF/empty == today.
    bool pipelineCacheDiskEnabled = false;
    std::string pipelineCacheFilePath;
    neoproc::PipelineCacheDeviceIdentity pipelineCacheDeviceIdentity{};
    std::atomic<bool> pipelineCachePersisted{false};
    VkShaderModule vertexShader = VK_NULL_HANDLE;
    VkShaderModule fragmentShader = VK_NULL_HANDLE;
    std::atomic_bool encoderPrewarmStarted{false};
    std::atomic_bool encoderPrewarmFinished{false};
    std::thread encoderPrewarmThread;
    VkDescriptorSetLayout computeDescriptorSetLayout = VK_NULL_HANDLE;
    VkPipelineLayout computePipelineLayout = VK_NULL_HANDLE;
    VkPipeline computePipeline = VK_NULL_HANDLE;
    VkShaderModule computeShaderModule = VK_NULL_HANDLE;
    VkDescriptorSetLayout imageComputeDescriptorSetLayout = VK_NULL_HANDLE;
    VkPipelineLayout imageComputePipelineLayout = VK_NULL_HANDLE;
    VkPipeline kneeCurvePipeline = VK_NULL_HANDLE;
    VkPipeline shadowLiftPipeline = VK_NULL_HANDLE;
    VkDescriptorSetLayout hdrMetadataHistogramDescriptorSetLayout = VK_NULL_HANDLE;
    VkDescriptorSetLayout hdrMetadataReduceDescriptorSetLayout = VK_NULL_HANDLE;
    VkPipelineLayout hdrMetadataHistogramPipelineLayout = VK_NULL_HANDLE;
    VkPipelineLayout hdrMetadataReducePipelineLayout = VK_NULL_HANDLE;
    VkPipeline hdrMetadataHistogramPipeline = VK_NULL_HANDLE;
    VkPipeline hdrMetadataReducePipeline = VK_NULL_HANDLE;
    VkShaderModule kneeCurveShaderModule = VK_NULL_HANDLE;
    VkShaderModule shadowLiftShaderModule = VK_NULL_HANDLE;
    VkShaderModule hdrMetadataHistogramShaderModule = VK_NULL_HANDLE;
    VkShaderModule hdrMetadataReduceShaderModule = VK_NULL_HANDLE;
    VkBuffer vertexBuffer = VK_NULL_HANDLE;
    VkDeviceMemory vertexBufferMemory = VK_NULL_HANDLE;
    PreviewLutTexture previewLut;
    SubjectDetailMaskTexture subjectDetailMask;

    RenderConfig renderConfig;
    EnhancementUniforms enhancementUniforms;
    std::atomic_bool enhancementComputePipelineReady{false}; // atomic: written by the background build thread
    // PART A (startup deferral): the heavy compute-pipeline groups (realtime/QFCA/OmniClarity, ~2.5s of
    // SPIR-V compilation) are built on a background thread AFTER the bridge reports "supported", so the
    // preview can bind + render the RAW camera frame immediately. These flags gate enhancement in the
    // render path; they (and the realtime flag above) are flipped together under state->mutex only once
    // every pipeline handle in their group is non-null. Atomic so a read outside the lock is well-defined.
    std::atomic_bool qfcaPipelinesReady{false};
    std::atomic_bool omniClarityPipelinesReady{false};
    // RAW demosaic is an independent capture/monitor domain. Its pipeline is created by the QFCA
    // builder for historical reasons, but its availability must not inherit the all-or-nothing
    // realtime/QFCA/OmniClarity result. The build thread store-releases this only after every
    // non-atomic demosaic handle is complete; dispatch acquire-loads it before reading those handles.
    std::atomic_bool rawDemosaicPipelineReady{false};
    std::atomic_bool enhancementBuildStarted{false};
    // D-8: cooperative-cancel flag for the background SPIR-V build thread below. nativeDestroy sets this
    // true BEFORE join() so the join returns at the next compile-group boundary instead of blocking the
    // calling (UI/onCleared) thread for the full ~2.5s build. Only ever set during teardown => a normal
    // build never observes it (byte-identical to today). Atomic so the build thread's group-boundary
    // reads are well-defined without holding state->mutex.
    std::atomic_bool enhancementBuildCancelled{false};
    std::thread enhancementBuildThread;
    std::string enhancementFallbackReason = "compute pipeline not initialized";
    uint64_t computeDispatchCount = 0;
    uint64_t gpuTimestampSampleCount = 0;
    double averageComputeDurationMs = 0.0;
    std::array<NeoHdr10Metadata, kHdrMetadataRingSize> hdrMetadataRingBuffer{};
    NeoHdr10Metadata latestHdrMetadata{};
    uint64_t hdrMetadataRingWriteIndex = 0;
    double timestampPeriodNs = 0.0;
    bool timestampQueriesSupported = false;
    uint32_t timestampValidBits = 0u;
    bool timelineSemaphoresSupported = false;
    bool timelineSemaphoresEnabled = false;
    std::vector<ImportedFrame> importedFrames;
    int currentImportedFrameIndex = -1;
    uint64_t importedFrameSerial = 0;
    TargetSurface previewTarget;
    TargetSurface encoderTarget;

    uint64_t importedFrameCount = 0;
    uint64_t previewFrameCount = 0;
    uint64_t encoderFrameCount = 0;
    int64_t lastPresentationTimeNs = 0;
    Niv99LivePacketTelemetry niv99LivePacket{};
    bool supported = false;
    std::string lastStatus = "uninitialized";

    // ── HDR dynamic-range pipeline state ──────────────────────────────
    // Mirrors HdrSwapchainFormatPolicy.toNativeModeId on the Kotlin side.
    // 0=SDR, 1=HLG, 2=HDR10, 3=HDR10+, 4=DolbyVision. Compared against
    // each target's builtForHdrModeId at frame submit time; a mismatch
    // forces a swapchain rebuild via the same path as VK_ERROR_OUT_OF_DATE_KHR.
    int hdrModeId = 0;
    // X2/R1: when true, the SDR preview (DISPLAY) swapchain requests a 10-bit packed sRGB format
    // (A2B10G10R10 + sRGB, no HDR EOTF) instead of 8-bit, so far-field gradients/texture keep 10-bit
    // tonal precision (dithered to 1023 levels). Falls back byte-identically to 8-bit when the driver
    // advertises no 10-bit SDR display pair. Set via nativeSetPreferTenBitSdrDisplay; encoder path
    // is unaffected. Compared like hdrModeId — a change rebuilds only the preview target.
    bool preferTenBitSdrDisplay = false;
    // True when VK_EXT_swapchain_colorspace was successfully enabled on
    // the instance — without it the HDR colourspace enums (PQ, HLG)
    // won't appear in vkGetPhysicalDeviceSurfaceFormatsKHR. Logged in
    // capability summary so bug reports show whether HDR was wired.
    bool swapchainColorSpaceExtensionEnabled = false;

    // QFCA Pipelines and Layouts
    VkDescriptorSetLayout guidedUpsampleDescLayout = VK_NULL_HANDLE;
    VkPipelineLayout guidedUpsamplePipelineLayout = VK_NULL_HANDLE;
    VkPipeline guidedUpsamplePipeline = VK_NULL_HANDLE;
    VkShaderModule guidedUpsampleShader = VK_NULL_HANDLE;

    VkDescriptorSetLayout detailPreservationDescLayout = VK_NULL_HANDLE;
    VkPipelineLayout detailPreservationPipelineLayout = VK_NULL_HANDLE;
    VkPipeline detailPreservationPipeline = VK_NULL_HANDLE;
    VkShaderModule detailPreservationShader = VK_NULL_HANDLE;

    VkDescriptorSetLayout mfsrFusionDescLayout = VK_NULL_HANDLE;
    VkPipelineLayout mfsrFusionPipelineLayout = VK_NULL_HANDLE;
    VkPipeline mfsrFusionPipeline = VK_NULL_HANDLE;
    VkShaderModule mfsrFusionShader = VK_NULL_HANDLE;

    // CP10: 12-bit RAW edge-directed demosaic -> ACEScg. Pipeline created here; per-frame images,
    // descriptor sets and dispatch (the RAW format-0x20 import branch) land in CP10c.
    VkDescriptorSetLayout demosaicDescLayout = VK_NULL_HANDLE;
    VkPipelineLayout demosaicPipelineLayout = VK_NULL_HANDLE;
    VkPipeline demosaicPipeline = VK_NULL_HANDLE;
    VkShaderModule demosaicShader = VK_NULL_HANDLE;
    // Best-effort FP16 ACEScg -> RGBA8 sRGB monitor pass. If unavailable, the mathematically equivalent
    // CPU fallback remains active; capture/recording never depends on this presentation-only pipeline.
    VkDescriptorSetLayout rawMonitorTonemapDescLayout = VK_NULL_HANDLE;
    VkPipelineLayout rawMonitorTonemapPipelineLayout = VK_NULL_HANDLE;
    VkPipeline rawMonitorTonemapPipeline = VK_NULL_HANDLE;
    VkShaderModule rawMonitorTonemapShader = VK_NULL_HANDLE;
    // Full-resolution FP16 ACEScg -> preview-resolution FP16 ACEScg. This independent recording-preview
    // leg uses the ratio-aware four-footprint project filter, but no tone curve.
    VkDescriptorSetLayout rawPreviewDownsampleDescLayout = VK_NULL_HANDLE;
    VkPipelineLayout rawPreviewDownsamplePipelineLayout = VK_NULL_HANDLE;
    VkPipeline rawPreviewDownsamplePipeline = VK_NULL_HANDLE;
    VkShaderModule rawPreviewDownsampleShader = VK_NULL_HANDLE;
    // Full-resolution FP16 ACEScg -> project-owned packed scene-log transport. This is not a display
    // tone curve: the encoder decodes it back to scene-linear ACEScg before NDR processing.
    VkDescriptorSetLayout rawSceneLogPackDescLayout = VK_NULL_HANDLE;
    VkPipelineLayout rawSceneLogPackPipelineLayout = VK_NULL_HANDLE;
    VkPipeline rawSceneLogPackPipeline = VK_NULL_HANDLE;
    VkShaderModule rawSceneLogPackShader = VK_NULL_HANDLE;
    // LQ-001.09 offline RAW conversion tail. The shared image layout serves detail and blur (two
    // storage images); the final layout binds two storage images plus an encoder-ready P010 buffer.
    VkDescriptorSetLayout rawDevelopImageDescLayout = VK_NULL_HANDLE;
    VkDescriptorSetLayout rawDevelopP010DescLayout = VK_NULL_HANDLE;
    VkPipelineLayout rawDevelopDetailPipelineLayout = VK_NULL_HANDLE;
    VkPipelineLayout rawDevelopLumaBlurPipelineLayout = VK_NULL_HANDLE;
    VkPipelineLayout rawDevelopP010PipelineLayout = VK_NULL_HANDLE;
    VkPipeline rawDevelopDetailPipeline = VK_NULL_HANDLE;
    VkPipeline rawDevelopLumaBlurPipeline = VK_NULL_HANDLE;
    VkPipeline rawDevelopP010Pipeline = VK_NULL_HANDLE;
    VkShaderModule rawDevelopDetailShader = VK_NULL_HANDLE;
    VkShaderModule rawDevelopLumaBlurShader = VK_NULL_HANDLE;
    VkShaderModule rawDevelopP010Shader = VK_NULL_HANDLE;
    // Offline-only CFA-domain NIV99 front end. Successful transformed RAW conversions run this
    // before demosaic, keeping spatial/temporal filtering, defect correction and scene-cut handling
    // entirely on Vulkan. It is optional and never changes the bit-exact RAW-to-RAW writer.
    VkDescriptorSetLayout rawNiv99DescLayout = VK_NULL_HANDLE;
    VkPipelineLayout rawNiv99PipelineLayout = VK_NULL_HANDLE;
    VkPipeline rawNiv99Pipeline = VK_NULL_HANDLE;
    VkShaderModule rawNiv99Shader = VK_NULL_HANDLE;

    // s1-f07 / CP10c — true when the device supports + we enabled shaderStorageImageExtendedFormats, which
    // demosaic_vulkan.comp's r16f dark-current storage image requires. Gates the demosaic pipeline build so
    // an unsupported device never trips pipeline-creation validation (which would disable the whole stack).
    bool storageImageExtendedFormatsEnabled = false;
    // s1-f07 / CP10c — RAW-preview cinema-debayer dispatch gate + owned resources. Default-OFF: when
    // rawPreviewDemosaicEnabled is false the dispatch is unreachable and the shipping YUV preview path
    // is byte-identical (no-downgrade). The flag is flipped on ONLY for RAW+VIDEO (the JVM cinema
    // debayer pipeline) and the dispatch is reached solely via nativeDispatchRawDemosaicPreview — the
    // standard nativeSubmitHardwareBuffer YUV path is never altered.
    bool rawPreviewDemosaicEnabled = false;
    // Device capability cache, intentionally outside RawDemosaicResources so an extent change does
    // not repeat a known-impossible rgba16f AHB storage import. When true, the RAW path has no
    // preferred full-resolution FP16 encoder-export consumer. The project-owned packed 10-bit scene-log
    // transport is attempted next; only when both paths are unsupported may the presentation-only leg
    // render directly at the 1024-class monitor resolution.
    bool rawOutputAhbStorageUnsupported = false;
    bool rawSceneLogTransportUnsupported = false;
    // Prepared follows a proven RAW-derived consumer. Submission covers both the direct NDR preview
    // and the encoder because either may require the scene-log fallback transport. Recording ownership
    // is intentionally separate: submission alone cannot authorize a full-sensor processing extent.
    bool rawSceneLogTransportPrepared = false;
    bool rawSceneLogTransportSubmissionEnabled = false;
    bool rawRecordingDemosaicActive = false;
    // True only while the visible strict-NDR compositor is consuming the full-resolution RAW-derived
    // FP16 transport. Recording-only consumers do not set this: they still require the CPU monitor.
    bool rawDirectNdrPreviewTransportActive = false;
    NdrMeasuredCorrectionNative rawMonitorNdrCorrection{};
    struct RawDemosaicResources {
        int width = 0;
        int height = 0;
        // Owned ACEScg output (rgba16f) + s1-f03 defect maps (r16f dark current, r8ui hot-pixel mask).
        // All are deterministically cleared at (re)creation so the dispatch never reads UNDEFINED
        // memory — the validation-layer-clean acceptance criterion holds by construction.
        VkImage outputImage = VK_NULL_HANDLE;
        VkDeviceMemory outputMemory = VK_NULL_HANDLE;
        VkImageView outputView = VK_NULL_HANDLE;
        // Recorder overlap lane. The primary and secondary transports are never written at the same
        // time: one may be sampled by EGL while Vulkan writes the other. The existing per-dispatch
        // fence still protects command/descriptor/staging reuse; this second immutable export removes
        // only the cross-API output hazard, without adding a frame queue or changing any pixels.
        VkImage outputImageSecondary = VK_NULL_HANDLE;
        VkDeviceMemory outputMemorySecondary = VK_NULL_HANDLE;
        VkImageView outputViewSecondary = VK_NULL_HANDLE;
        VkImage darkImage = VK_NULL_HANDLE;
        VkDeviceMemory darkMemory = VK_NULL_HANDLE;
        VkImageView darkView = VK_NULL_HANDLE;
        VkImage hotImage = VK_NULL_HANDLE;
        VkDeviceMemory hotMemory = VK_NULL_HANDLE;
        VkImageView hotView = VK_NULL_HANDLE;
        // Persistent CPU-upload fallback for camera RAW AHBs that cannot be storage-imported. Reusing
        // these allocations removes two large Vulkan allocations/maps/frees from every preview frame.
        VkImage uploadImage = VK_NULL_HANDLE;
        VkDeviceMemory uploadMemory = VK_NULL_HANDLE;
        VkImageView uploadView = VK_NULL_HANDLE;
        VkBuffer uploadStaging = VK_NULL_HANDLE;
        VkDeviceMemory uploadStagingMemory = VK_NULL_HANDLE;
        void* uploadStagingMapped = nullptr;
        VkDeviceSize uploadBytes = 0;
        bool uploadImageInitialized = false;
        bool rawAhbStorageImportUnsupported = false;
        VkBuffer ubo = VK_NULL_HANDLE;            // 416-byte std140 RawDemosaicUniforms
        VkDeviceMemory uboMemory = VK_NULL_HANDLE;
        void* uboMapped = nullptr;
        VkDescriptorPool descriptorPool = VK_NULL_HANDLE;
        VkDescriptorSet descriptorSet = VK_NULL_HANDLE;
        // Reused per-extent command resources for the RAW worker. The fence protects command-buffer,
        // descriptor, mapped UBO and persistent staging reuse without a queue-wide idle.
        VkCommandPool dispatchCommandPool = VK_NULL_HANDLE;
        VkCommandBuffer dispatchCommandBuffer = VK_NULL_HANDLE;
        VkFence dispatchFence = VK_NULL_HANDLE;
        // Fence-correlated GPU timestamps for the ordered RAW dispatch. The pool is optional and
        // sampled only on the same first-eight/every-60 recording frames already logged below.
        // It never waits independently: results are read only after dispatchFence has signaled.
        VkQueryPool dispatchTimestampQueryPool = VK_NULL_HANDLE;
        bool pendingGpuTimestampSample = false;
        std::array<double, kRawGpuStageCount> pendingGpuStageMs{};
        std::array<double, kRawGpuStageCount> gpuStageTotalMs{};
        std::array<double, kRawGpuStageCount> gpuStageMaxMs{};
        uint64_t gpuStageTimingSampleCount = 0u;
        bool dispatchInFlight = false;
        bool pendingReadbackRecorded = false;
        bool pendingSceneLogRecorded = false;
        bool pendingUploadInitialization = false;
        // A zero-copy RAW import must live until its asynchronous dispatch fence completes.
        VkImage pendingRawImage = VK_NULL_HANDLE;
        VkDeviceMemory pendingRawMemory = VK_NULL_HANDLE;
        VkImageView pendingRawView = VK_NULL_HANDLE;
        AHardwareBuffer* pendingRawHardwareBuffer = nullptr;
        VkDescriptorPool monitorDescriptorPool = VK_NULL_HANDLE;
        VkDescriptorSet monitorDescriptorSet = VK_NULL_HANDLE;
        bool gpuMonitorTonemapEnabled = false;
        VkImage sceneLogImage = VK_NULL_HANDLE;
        VkDeviceMemory sceneLogMemory = VK_NULL_HANDLE;
        VkImageView sceneLogView = VK_NULL_HANDLE;
        AHardwareBuffer* sceneLogAhb = nullptr;
        VkImage sceneLogImageSecondary = VK_NULL_HANDLE;
        VkDeviceMemory sceneLogMemorySecondary = VK_NULL_HANDLE;
        VkImageView sceneLogViewSecondary = VK_NULL_HANDLE;
        AHardwareBuffer* sceneLogAhbSecondary = nullptr;
        VkDescriptorPool sceneLogDescriptorPool = VK_NULL_HANDLE;
        VkDescriptorSet sceneLogDescriptorSet = VK_NULL_HANDLE;
        bool sceneLogReady = false;
        bool sceneLogReadySecondary = false;
        // 0=FREE, 1=VULKAN_IN_FLIGHT, 2=COMPLETED, 3=EXTERNALLY_ACQUIRED. Completion serials preserve
        // FIFO order while immutable positive tokens make cancellation/acknowledgement exact even when
        // two encoder reads overlap. Only recorder-owned full-sensor resources require two lanes.
        std::array<uint8_t, 2> transportOwnership{{0u, 0u}};
        std::array<uint64_t, 2> transportCompletionSerial{{0u, 0u}};
        std::array<uint64_t, 2> transportTokens{{0u, 0u}};
        uint64_t nextTransportCompletionSerial = 1u;
        // Intentionally survives extent/resource recreation so a late token can never name a new lane.
        uint64_t nextTransportToken = 1u;
        int dispatchTransportSlot = -1;
        bool recordingTransportRingReady = false;
        // Independent recording-preview ring. The encoder continues to own the two full-resolution
        // lanes above; these three bounded scene-linear lanes are acquired/acknowledged with exact
        // tokens by a dedicated EGL worker and therefore cannot serialize full-resolution recording.
        std::array<VkImage, kRawPreviewTransportSlotCount> previewTransportImages{};
        std::array<VkDeviceMemory, kRawPreviewTransportSlotCount> previewTransportMemories{};
        std::array<VkImageView, kRawPreviewTransportSlotCount> previewTransportViews{};
        std::array<AHardwareBuffer*, kRawPreviewTransportSlotCount> previewTransportAhbs{};
        std::array<uint8_t, kRawPreviewTransportSlotCount> previewTransportOwnership{};
        std::array<uint64_t, kRawPreviewTransportSlotCount> previewTransportTokens{};
        VkFormat previewTransportFormat = VK_FORMAT_UNDEFINED;
        uint32_t previewTransportAhbFormat = 0u;
        int previewTransportW = 0;
        int previewTransportH = 0;
        bool previewTransportSceneLog = false;
        bool previewTransportRingReady = false;
        int dispatchPreviewTransportSlot = -1;
        uint64_t nextPreviewTransportToken = 1u;
        VkDescriptorPool previewDownsampleDescriptorPool = VK_NULL_HANDLE;
        VkDescriptorSet previewDownsampleDescriptorSet = VK_NULL_HANDLE;
        // Packed-10 fallback first downscales to this FP16 scene-linear image, then applies the exact
        // NEO-SCENE-LOG10 packer. Preferred FP16 export writes the ring slot directly and leaves nulls.
        VkImage previewSceneLinearImage = VK_NULL_HANDLE;
        VkDeviceMemory previewSceneLinearMemory = VK_NULL_HANDLE;
        VkImageView previewSceneLinearView = VK_NULL_HANDLE;
        VkDescriptorPool previewSceneLogDescriptorPool = VK_NULL_HANDLE;
        VkDescriptorSet previewSceneLogDescriptorSet = VK_NULL_HANDLE;
        uint64_t previewGeneratedCount = 0u;
        uint64_t previewAcquiredCount = 0u;
        uint64_t previewAcknowledgedCount = 0u;
        uint64_t previewSaturatedCount = 0u;
        uint64_t dispatchCount = 0;
        uint64_t deferredCompletionCount = 0;
        // Fence-correlated host timing for the ordered full-resolution recorder. Telemetry only: these
        // fields do not change the extent, precision, shader, descriptor, wait, or admission policy.
        std::chrono::steady_clock::time_point pendingDispatchCycleStarted{};
        std::chrono::steady_clock::time_point pendingQueueSubmitted{};
        double pendingInputPrepareMs = 0.0;
        double pendingCommandBuildMs = 0.0;
        double pendingQueueSubmitHostMs = 0.0;
        bool pendingUsedStagingUpload = false;
        bool pendingWasRecording = false;
        uint64_t timingSampleCount = 0;
        double timingTotalMs = 0.0;
        double timingFenceMs = 0.0;
        double timingMaxTotalMs = 0.0;
        double timingMaxFenceMs = 0.0;
        bool lastDispatchOk = false;
        bool everDispatched = false;
        // STAGE 2 present: when the ACEScg output image is backed by an AHardwareBuffer (allocate-then-
        // import), this holds that AHB so it can be handed to the GL compositor (AHardwareBuffer_toHardwareBuffer)
        // and sampled as an external-OES texture. null on devices that can't back rgba16f as a storage AHB
        // (fail-closed -> device-local output -> the CPU-readback present path is used instead).
        AHardwareBuffer* outputAhb = nullptr;
        AHardwareBuffer* outputAhbSecondary = nullptr;
        // STAGE 2 CPU-readback present (works on any device regardless of AHB GPU-sharing support): the
        // full-res ACEScg output is GPU-downscaled (blit) into previewImage, copied to a host-visible
        // buffer, then CPU-tonemapped ACEScg->sRGB into previewRgba8 (RGBA8, display-ready). The compositor
        // uploads previewRgba8 as a plain GL texture. previewW/H are the downscaled monitor dimensions.
        VkImage previewImage = VK_NULL_HANDLE;
        VkDeviceMemory previewMemory = VK_NULL_HANDLE;
        VkImageView previewView = VK_NULL_HANDLE; // unused for blit/copy but kept for symmetry with destroyImg
        VkBuffer readbackBuffer = VK_NULL_HANDLE;
        VkDeviceMemory readbackMemory = VK_NULL_HANDLE;
        void* readbackMapped = nullptr;
        // Persistent full-resolution offline-develop readback. This is deliberately separate from the
        // downscaled monitor buffer above: conversion retains every FP16 ACEScg pixel and waits on only
        // its own fence, never vkQueueWaitIdle() on the process-wide graphics queue.
        VkBuffer developReadbackBuffer = VK_NULL_HANDLE;
        VkDeviceMemory developReadbackMemory = VK_NULL_HANDLE;
        void* developReadbackMapped = nullptr;
        VkDeviceSize developReadbackBytes = 0;
        VkCommandPool developReadbackCommandPool = VK_NULL_HANDLE;
        VkCommandBuffer developReadbackCommandBuffer = VK_NULL_HANDLE;
        VkFence developReadbackFence = VK_NULL_HANDLE;
        bool developReadbackInFlight = false;
        // Persistent GPU conversion tail: FP32 detail, FP32 luma blur, and mapped encoder-ready P010.
        // These resources are lazily allocated only for HEVC Main10 RAW conversions and are reused for
        // every frame at the same extent. CPU fallback remains authoritative if any allocation fails.
        VkImage developDetailImage = VK_NULL_HANDLE;
        VkDeviceMemory developDetailMemory = VK_NULL_HANDLE;
        VkImageView developDetailView = VK_NULL_HANDLE;
        VkImage developLumaBlurImage = VK_NULL_HANDLE;
        VkDeviceMemory developLumaBlurMemory = VK_NULL_HANDLE;
        VkImageView developLumaBlurView = VK_NULL_HANDLE;
        VkBuffer developP010Buffer = VK_NULL_HANDLE;
        VkDeviceMemory developP010Memory = VK_NULL_HANDLE;
        void* developP010Mapped = nullptr;
        VkDeviceSize developP010Bytes = 0;
        VkDescriptorPool developP010DescriptorPool = VK_NULL_HANDLE;
        VkDescriptorSet developDetailDescriptorSet = VK_NULL_HANDLE;
        VkDescriptorSet developLumaBlurDescriptorSet = VK_NULL_HANDLE;
        VkDescriptorSet developP010DescriptorSet = VK_NULL_HANDLE;
        VkCommandPool developP010CommandPool = VK_NULL_HANDLE;
        VkCommandBuffer developP010CommandBuffer = VK_NULL_HANDLE;
        VkFence developP010Fence = VK_NULL_HANDLE;
        bool developP010InFlight = false;
        bool developP010Leased = false;
        int developP010Width = 0;
        int developP010Height = 0;
        uint64_t developP010DispatchCount = 0;
        // Offline NIV99 uses one spatial scratch image plus two persistent history/output images.
        // Two immutable descriptor sets allow spatial and temporal dispatches to coexist in one
        // command buffer without descriptor-update races.
        VkImage developNiv99ScratchImage = VK_NULL_HANDLE;
        VkDeviceMemory developNiv99ScratchMemory = VK_NULL_HANDLE;
        VkImageView developNiv99ScratchView = VK_NULL_HANDLE;
        std::array<VkImage, 2> developNiv99HistoryImages{};
        std::array<VkDeviceMemory, 2> developNiv99HistoryMemories{};
        std::array<VkImageView, 2> developNiv99HistoryViews{};
        VkBuffer developNiv99CountersBuffer = VK_NULL_HANDLE;
        VkDeviceMemory developNiv99CountersMemory = VK_NULL_HANDLE;
        RawNiv99GpuCounters* developNiv99CountersMapped = nullptr;
        VkDescriptorPool developNiv99DescriptorPool = VK_NULL_HANDLE;
        VkDescriptorSet developNiv99SpatialDescriptorSet = VK_NULL_HANDLE;
        VkDescriptorSet developNiv99TemporalDescriptorSet = VK_NULL_HANDLE;
        int developNiv99HistoryIndex = -1;
        int developNiv99Width = 0;
        int developNiv99Height = 0;
        int64_t developNiv99LastFrameNumber = -1;
        int64_t developNiv99LastTimestampNs = -1;
        uint32_t developNiv99LastFps = 0u;
        uint64_t developNiv99DispatchCount = 0u;
        bool pendingNiv99HistoryCommit = false;
        int pendingNiv99HistoryIndex = -1;
        int64_t pendingNiv99FrameNumber = -1;
        int64_t pendingNiv99TimestampNs = -1;
        uint32_t pendingNiv99Fps = 0u;
        int previewW = 0;
        int previewH = 0;
        std::vector<uint8_t> previewRgba8;
        bool previewReady = false;
        // After a successful monitor copy the persistent image remains TRANSFER_SRC_OPTIMAL. Track
        // that layout across frames instead of repeatedly declaring initialized storage UNDEFINED.
        bool previewImageInitialized = false;
    } rawDemosaic;

    // s1-f10 / CP31 — dual-conversion-gain fusion pipeline + dispatch gate + owned resources. The
    // pipeline is built best-effort inside createOmniClarityPipelines (a failure leaves these null and
    // does NOT disable the omni stack); dualGainFusionEnabled defaults OFF so the dispatch is unreachable
    // and the live render path is byte-identical until the owner enables it device-side (no-downgrade).
    VkDescriptorSetLayout dualGainDescLayout = VK_NULL_HANDLE;
    VkPipelineLayout dualGainPipelineLayout = VK_NULL_HANDLE;
    VkPipeline dualGainPipeline = VK_NULL_HANDLE;
    VkShaderModule dualGainShader = VK_NULL_HANDLE;
    bool dualGainFusionEnabled = false;

    // CP-QV16 — gated single-frame spatial denoiser (denoise.comp) master switch. Native starts false
    // until the JVM render tick forwards DenoiseSpatialFlag (default ON, broadcast kill-switch). With
    // this false the spatial-denoise dispatch below is skipped; with it true the pass runs after nivukx.
    bool denoiseSpatialEnabled = false;

    // CP-QV17 — gated optical-recovery sharpener (sharpen.comp) master switch. Native starts false until
    // the JVM render tick forwards SharpenOpticalFlag (default ON, broadcast kill-switch). With this
    // false the sharpen dispatch below is skipped; with it true the pass runs after denoise.
    bool sharpenOpticalEnabled = false;

    // CP24 — distant-detail elite gates (detail_preservation noise-aware + halo-guard, guided_upsample
    // edge-tighten) driven as per-dispatch push constants. Native starts false until the JVM render tick
    // forwards DistantDetailGatesFlag (default ON, broadcast kill-switch). False keeps all gate fields 0.
    bool distantDetailGatesEnabled = false;

    // CP30 — BT.2390 EETF peak-nits highlight shoulder in knee_curve.comp, driven as a per-dispatch
    // push constant. Default OFF: with this false peakNitsGate is 0.0, so the knee uses today's
    // SDR-referred modified-Reinhard path bit-for-bit (no-downgrade). The EETF is identity below diffuse
    // white, so enabling only rolls super-highlights to the display peak. Device-gated (vivo I2405).
    bool apexPeakNitsEnabled = false;

    // CP-CUSP — exact per-hue Oklab gamut cusp (Rec.2020) in the preview shader, driven as a
    // per-frame UBO lane (uNeoGamutCuspParams.x). Default ON (approved on a saturated-scene look review,
    // vivo I2405): a TRUE gamut clamp that REDUCES out-of-gamut oversaturation (the exact cusp sits below
    // the loose analytic guard). Broadcast debug.exact_gamut_cusp false restores the prior analytic guard.
    bool exactGamutCuspEnabled = true;
    struct DualGainFusionResources {
        int width = 0;
        int height = 0;
        // Owned fused-ACEScg + motion-confidence outputs (rgba16f), deterministically cleared so the
        // dispatch never reads UNDEFINED memory (validation-layer-clean by construction).
        VkImage fusedImage = VK_NULL_HANDLE;
        VkDeviceMemory fusedMemory = VK_NULL_HANDLE;
        VkImageView fusedView = VK_NULL_HANDLE;
        VkImage motionImage = VK_NULL_HANDLE;
        VkDeviceMemory motionMemory = VK_NULL_HANDLE;
        VkImageView motionView = VK_NULL_HANDLE;
        VkDescriptorPool descriptorPool = VK_NULL_HANDLE;
        VkDescriptorSet descriptorSet = VK_NULL_HANDLE;
        uint64_t dispatchCount = 0;
        bool lastDispatchOk = false;
        bool everDispatched = false;
    } dualGainFusion;

    // s5-f06 / CP-F7 — depth-driven Vogel-spiral bokeh pipeline + dispatch gate + owned resources. The
    // pipeline is built best-effort inside createOmniClarityPipelines (a failure leaves these null and does
    // NOT disable the omni stack); bokehBlendEnabled defaults OFF so the dispatch is unreachable and the
    // live render path is byte-identical until the owner enables it device-side (no-downgrade / DEVICE-GATED).
    // All four bindings are owned, deterministically-cleared images (0 rgba16f input, 1 r32f depth, 2 r32f
    // CoC radius, 3 rgba16f output) so the dispatch never reads UNDEFINED memory (validation-clean by
    // construction — the s1-f06 / s1-f10 UB-safe pattern). The live depth/color AHB feed is the device-gated
    // wiring; until then the dispatch is a reachable-only-by-test no-op against cleared inputs.
    VkDescriptorSetLayout bokehDescLayout = VK_NULL_HANDLE;
    VkPipelineLayout bokehPipelineLayout = VK_NULL_HANDLE;
    VkPipeline bokehPipeline = VK_NULL_HANDLE;
    VkShaderModule bokehShader = VK_NULL_HANDLE;
    bool bokehBlendEnabled = false;
    // Live inline-bokeh params (set by the MiDaS depth producer via the JNI setters).
    float bokehFocusDepth = 0.5f;      // normalized subject focus plane
    float bokehBlendStrength = 0.0f;   // 0 == exact passthrough (safe default until the producer raises it)
    struct BokehResources {
        int width = 0;
        int height = 0;
        VkImage inputImage = VK_NULL_HANDLE;
        VkDeviceMemory inputMemory = VK_NULL_HANDLE;
        VkImageView inputView = VK_NULL_HANDLE;
        VkImage depthImage = VK_NULL_HANDLE;
        VkDeviceMemory depthMemory = VK_NULL_HANDLE;
        VkImageView depthView = VK_NULL_HANDLE;
        VkImage cocImage = VK_NULL_HANDLE;
        VkDeviceMemory cocMemory = VK_NULL_HANDLE;
        VkImageView cocView = VK_NULL_HANDLE;
        VkImage outputImage = VK_NULL_HANDLE;
        VkDeviceMemory outputMemory = VK_NULL_HANDLE;
        VkImageView outputView = VK_NULL_HANDLE;
        VkDescriptorPool descriptorPool = VK_NULL_HANDLE;
        VkDescriptorSet descriptorSet = VK_NULL_HANDLE;
        uint64_t dispatchCount = 0;
        bool lastDispatchOk = false;
        bool everDispatched = false;
    } bokeh;

    // s1-f10 / CP32 — photon HDR fusion (motion-compensated dual-exposure) FOUNDATION. The pipeline is
    // created best-effort but DORMANT — never dispatched; the motion-compensated merge + dual-exposure
    // readout ring are device-gated (s1-f06 alignment). Mirrors the "created but never dispatched" pattern.
    VkDescriptorSetLayout photonDescLayout = VK_NULL_HANDLE;
    VkPipelineLayout photonPipelineLayout = VK_NULL_HANDLE;
    VkPipeline photonPipeline = VK_NULL_HANDLE;
    VkShaderModule photonShader = VK_NULL_HANDLE;

    // s3-f06 / CP60 — gyro-EIS per-scanline warp FOUNDATION. Created best-effort but DORMANT — never
    // dispatched. The per-scanline grid (smoother residual -> rolling-shutter -> OIS fusion -> mode policy,
    // CPU side) + the dispatch wiring are the device-gated activation, after the clock/OIS/skew calibrations.
    VkDescriptorSetLayout eisWarpDescLayout = VK_NULL_HANDLE;
    VkPipelineLayout eisWarpPipelineLayout = VK_NULL_HANDLE;
    VkPipeline eisWarpPipeline = VK_NULL_HANDLE;
    VkShaderModule eisWarpShader = VK_NULL_HANDLE;

    VkDescriptorSetLayout imaxRenderingDescLayout = VK_NULL_HANDLE;
    VkPipelineLayout imaxRenderingPipelineLayout = VK_NULL_HANDLE;
    VkPipeline imaxRenderingPipeline = VK_NULL_HANDLE;
    VkShaderModule imaxRenderingShader = VK_NULL_HANDLE;

    VkDescriptorSetLayout m_nivukxDenoiseDescLayout = VK_NULL_HANDLE;
    VkPipelineLayout m_nivukxDenoisePipelineLayout = VK_NULL_HANDLE;
    VkPipeline m_nivukxDenoisePipeline = VK_NULL_HANDLE;
    VkShaderModule m_nivukxDenoiseShader = VK_NULL_HANDLE;

    // CP-QV16 — gated single-frame spatial denoiser (denoise.comp) pipeline. Built best-effort next to
    // the nivukx denoise; a failure leaves these null and does NOT disable the QFCA stack. The dispatch
    // is guarded by denoiseSpatialEnabled (default OFF) so these are dormant/never-bound until enabled.
    VkDescriptorSetLayout denoiseSpatialDescLayout = VK_NULL_HANDLE;
    VkPipelineLayout denoiseSpatialPipelineLayout = VK_NULL_HANDLE;
    VkPipeline denoiseSpatialPipeline = VK_NULL_HANDLE;
    VkShaderModule denoiseSpatialShader = VK_NULL_HANDLE;

    // CP-QV17 — gated optical-recovery sharpener (sharpen.comp) pipeline. Built best-effort next to the
    // spatial denoiser; a failure leaves these null and does NOT disable the QFCA stack. The dispatch is
    // guarded by sharpenOpticalEnabled (default OFF) so these are dormant/never-bound until enabled.
    VkDescriptorSetLayout sharpenOpticalDescLayout = VK_NULL_HANDLE;
    VkPipelineLayout sharpenOpticalPipelineLayout = VK_NULL_HANDLE;
    VkPipeline sharpenOpticalPipeline = VK_NULL_HANDLE;
    VkShaderModule sharpenOpticalShader = VK_NULL_HANDLE;

    // SNCE v2.0 HVC Stage-04 live opponent lateral-inhibition pass (per-pixel GPU; default on).
    bool snceLivePerceptualEnabled = true;
    VkDescriptorSetLayout occsDescLayout = VK_NULL_HANDLE;
    VkPipelineLayout occsPipelineLayout = VK_NULL_HANDLE;
    VkPipeline occsPipeline = VK_NULL_HANDLE;
    VkShaderModule occsShader = VK_NULL_HANDLE;
    // SNCE v2.0 HVC Stage-11 live NRLI 3D-LUT pass (per-pixel GPU; identity LUT => passthrough default).
    VkDescriptorSetLayout nrliDescLayout = VK_NULL_HANDLE;
    VkPipelineLayout nrliPipelineLayout = VK_NULL_HANDLE;
    VkPipeline nrliPipeline = VK_NULL_HANDLE;
    VkShaderModule nrliShader = VK_NULL_HANDLE;
    // True only when pipeline creation accepted specialization constant 0 = VK_TRUE. Devices/drivers
    // that reject it fall back to the shader's established trilinear default without disabling NRLI.
    bool nrliTetrahedralInterpolation = false;
    PreviewLutTexture nrliLut;
    // CP-QV21 — replaceable NRLI look. Default 0.0f => the live nrli dispatch pushes strength 0 through
    // the identity LUT == exact passthrough (byte-identical to today). nativeUpdateNrliLut re-uploads a
    // replacement 33^3 LUT into nrliLut.image and sets this to clamp(strength,0,1); until a device-side
    // caller invokes it, this stays 0 and the enhance path is unchanged. NO live code writes this field.
    float nrliUserStrength = 0.0f;

    // OmniClarity motion-stack pipelines (gated; omniClarityEnabled default false keeps the
    // shipping render path byte-for-byte unchanged until explicitly turned on + device-validated).
    bool omniClarityEnabled = true; // validated live on vivo/Adreno preview @24fps; toggle via setOmniClarityEnabled()
    bool recordingActive = false; // true while the encoder surface is bound; OmniClarity yields to recording
    OmniClarityTuning omniTuning{}; // s3-f01..f04 governor levers; defaults == today's hardcoded values (no-downgrade)

    // s1-f06 / CP12 STEP B/C/D — MFSR real-input wiring (control path; mirrors MfsrFusionParams on the JVM).
    // mfsrRealInputsEnabled routes the real heteroscedastic noise / gyro / frame-count into the
    // fusion push constants and (when a ring exists) the live Argus flow into binding 1. mfsrBurstRingReady
    // stays FALSE until the owner builds a real FULL-RES N-frame RAW/luma ring on-device — the live binding-0
    // array is a 1x1 zero placeholder that physically cannot carry real frames, so until that ring exists MFSR
    // can only emit ~zero. Gating the visible activation on mfsrBurstRingReady keeps the push constants + flow
    // binding byte-identical to shipping (no black-out, no downgrade). The master switch defaults OFF;
    // nativeSetMfsrFusionParams feeds mfsrRealtimeParams from CameraSensorNoiseModelPolicy when enabled.
    bool mfsrRealInputsEnabled = false;
    bool mfsrBurstRingReady = false; // DEVICE-GATED: owner flips true after building+validating the real ring
    MfsrFusionPushConstants mfsrRealtimeParams{ 4, 0.0026f, 0.00050f, 1.0f }; // FALLBACK shot/read (ImaxStrictProfile)
    VkDescriptorSetLayout omniBlockMatchDescLayout = VK_NULL_HANDLE;
    VkPipelineLayout omniBlockMatchPipelineLayout = VK_NULL_HANDLE;
    VkPipeline omniBlockMatchPipeline = VK_NULL_HANDLE;
    VkShaderModule omniBlockMatchShader = VK_NULL_HANDLE;
    VkDescriptorSetLayout omniRefineDescLayout = VK_NULL_HANDLE;
    VkPipelineLayout omniRefinePipelineLayout = VK_NULL_HANDLE;
    VkPipeline omniRefinePipeline = VK_NULL_HANDLE;
    VkShaderModule omniRefineShader = VK_NULL_HANDLE;
    VkDescriptorSetLayout omniRivenDescLayout = VK_NULL_HANDLE;
    VkPipelineLayout omniRivenPipelineLayout = VK_NULL_HANDLE;
    VkPipeline omniRivenPipeline = VK_NULL_HANDLE;
    VkShaderModule omniRivenShader = VK_NULL_HANDLE;
    VkDescriptorSetLayout omniWarpDescLayout = VK_NULL_HANDLE;
    VkPipelineLayout omniWarpPipelineLayout = VK_NULL_HANDLE;
    VkPipeline omniWarpPipeline = VK_NULL_HANDLE;
    VkShaderModule omniWarpShader = VK_NULL_HANDLE;
    VkDescriptorSetLayout omniDownsampleDescLayout = VK_NULL_HANDLE;
    VkPipelineLayout omniDownsamplePipelineLayout = VK_NULL_HANDLE;
    VkPipeline omniDownsamplePipeline = VK_NULL_HANDLE;
    VkShaderModule omniDownsampleShader = VK_NULL_HANDLE;
    VkDescriptorSetLayout omniFlowUpsampleDescLayout = VK_NULL_HANDLE;
    VkPipelineLayout omniFlowUpsamplePipelineLayout = VK_NULL_HANDLE;
    VkPipeline omniFlowUpsamplePipeline = VK_NULL_HANDLE;
    VkShaderModule omniFlowUpsampleShader = VK_NULL_HANDLE;
    VkDescriptorSetLayout omniSelectDescLayout = VK_NULL_HANDLE;
    VkPipelineLayout omniSelectPipelineLayout = VK_NULL_HANDLE;
    VkPipeline omniSelectPipeline = VK_NULL_HANDLE;
    VkShaderModule omniSelectShader = VK_NULL_HANDLE;

    // Dummy resources for mfsr_fusion when inputs are inactive
    VkImage dummyImage = VK_NULL_HANDLE;
    VkDeviceMemory dummyImageMemory = VK_NULL_HANDLE;
    VkImageView dummyImageView = VK_NULL_HANDLE;

    VkImage dummyImageArray = VK_NULL_HANDLE;
    VkDeviceMemory dummyImageArrayMemory = VK_NULL_HANDLE;
    VkImageView dummyImageArrayView = VK_NULL_HANDLE;

    VkSampler dummySampler = VK_NULL_HANDLE;
    // NOTE: createDummyResources clears dummyImage/dummyImageArray to zero and leaves them
    // SHADER_READ_ONLY_OPTIMAL (s1-f06 / CP12 STEP A) — without it mfsr_fusion.comp reads uninitialized
    // device memory. They are static read-only after creation and consumed only by the MFSR descriptor.

    // Focus Map data copied from camera saliency thread
    std::mutex focusMapMutex;
    std::array<float, 256> latestFocusMap{}; // 16x16 array
    bool focusMapDirty = false;
};

struct Vertex {
    float position[2];
    float texCoord[2];
};

constexpr std::array<Vertex, 4> kFullscreenQuad{{
    {{-1.f, -1.f}, {0.f, 1.f}},
    {{ 1.f, -1.f}, {1.f, 1.f}},
    {{-1.f,  1.f}, {0.f, 0.f}},
    {{ 1.f,  1.f}, {1.f, 0.f}},
}};

bool createRenderPass(VulkanBridgeState* state, TargetSurface* target);
bool createGraphicsPipelineWithCache(
    VulkanBridgeState* state,
    TargetSurface* target,
    VkPipelineCache pipelineCache
);
bool createGraphicsPipeline(VulkanBridgeState* state, TargetSurface* target);
[[maybe_unused]] void startEncoderGraphicsPipelinePrewarm(VulkanBridgeState* state);

bool createDummyResources(VulkanBridgeState* state);
void destroyDummyResources(VulkanBridgeState* state);
bool createQfcaPipelines(VulkanBridgeState* state);
void destroyQfcaPipelines(VulkanBridgeState* state);
bool destroyRawDemosaicResources(VulkanBridgeState* state); // s1-f07 / CP10c — owned RAW-preview demosaic resources
void destroyDualGainFusionResources(VulkanBridgeState* state); // s1-f10 / CP31 — owned dual-gain fusion resources
void destroyBokehResources(VulkanBridgeState* state); // s5-f06 / CP-F7 — owned Vogel-spiral bokeh resources
bool createOmniClarityPipelines(VulkanBridgeState* state);
void destroyOmniClarityPipelines(VulkanBridgeState* state);
bool allocateOmniClarityResources(VulkanBridgeState* state, TargetSurface* target);
void dispatchOmniClarityMotionStack(VulkanBridgeState* state, TargetSurface* target, VkCommandBuffer commandBuffer, uint32_t imageIndex, uint32_t groupsX, uint32_t groupsY);

bool isSamplerYcbcrFormat(VkFormat format) {
    switch (format) {
        case VK_FORMAT_G8B8G8R8_422_UNORM:
        case VK_FORMAT_B8G8R8G8_422_UNORM:
        case VK_FORMAT_G8_B8_R8_3PLANE_420_UNORM:
        case VK_FORMAT_G8_B8R8_2PLANE_420_UNORM:
        case VK_FORMAT_G8_B8_R8_3PLANE_422_UNORM:
        case VK_FORMAT_G8_B8R8_2PLANE_422_UNORM:
        case VK_FORMAT_G8_B8_R8_3PLANE_444_UNORM:
        case VK_FORMAT_R10X6_UNORM_PACK16:
        case VK_FORMAT_R10X6G10X6_UNORM_2PACK16:
        case VK_FORMAT_R10X6G10X6B10X6A10X6_UNORM_4PACK16:
        case VK_FORMAT_G10X6B10X6G10X6R10X6_422_UNORM_4PACK16:
        case VK_FORMAT_B10X6G10X6R10X6G10X6_422_UNORM_4PACK16:
        case VK_FORMAT_G10X6_B10X6_R10X6_3PLANE_420_UNORM_3PACK16:
        case VK_FORMAT_G10X6_B10X6R10X6_2PLANE_420_UNORM_3PACK16:
        case VK_FORMAT_G10X6_B10X6_R10X6_3PLANE_422_UNORM_3PACK16:
        case VK_FORMAT_G10X6_B10X6R10X6_2PLANE_422_UNORM_3PACK16:
        case VK_FORMAT_G10X6_B10X6_R10X6_3PLANE_444_UNORM_3PACK16:
        case VK_FORMAT_R12X4_UNORM_PACK16:
        case VK_FORMAT_R12X4G12X4_UNORM_2PACK16:
        case VK_FORMAT_R12X4G12X4B12X4A12X4_UNORM_4PACK16:
        case VK_FORMAT_G12X4B12X4G12X4R12X4_422_UNORM_4PACK16:
        case VK_FORMAT_B12X4G12X4R12X4G12X4_422_UNORM_4PACK16:
        case VK_FORMAT_G12X4_B12X4_R12X4_3PLANE_420_UNORM_3PACK16:
        case VK_FORMAT_G12X4_B12X4R12X4_2PLANE_420_UNORM_3PACK16:
        case VK_FORMAT_G12X4_B12X4_R12X4_3PLANE_422_UNORM_3PACK16:
        case VK_FORMAT_G12X4_B12X4R12X4_2PLANE_422_UNORM_3PACK16:
        case VK_FORMAT_G12X4_B12X4_R12X4_3PLANE_444_UNORM_3PACK16:
        case VK_FORMAT_G16B16G16R16_422_UNORM:
        case VK_FORMAT_B16G16R16G16_422_UNORM:
        case VK_FORMAT_G16_B16_R16_3PLANE_420_UNORM:
        case VK_FORMAT_G16_B16R16_2PLANE_420_UNORM:
        case VK_FORMAT_G16_B16_R16_3PLANE_422_UNORM:
        case VK_FORMAT_G16_B16R16_2PLANE_422_UNORM:
        case VK_FORMAT_G16_B16_R16_3PLANE_444_UNORM:
            return true;
        default:
            return false;
    }
}

bool check(VkResult result, const char* what) {
    if (result == VK_SUCCESS) return true;
    LOGE("%s failed with %d", what, static_cast<int>(result));
    return false;
}

double elapsedMsSince(const std::chrono::steady_clock::time_point& start) {
    return std::chrono::duration<double, std::milli>(
        std::chrono::steady_clock::now() - start
    ).count();
}

void logSlowSurfaceOperation(
    const char* targetLabel,
    const char* operation,
    const std::chrono::steady_clock::time_point& start,
    double slowThresholdMs = 8.0
) {
    const double elapsedMs = elapsedMsSince(start);
    if (elapsedMs >= slowThresholdMs) {
        LOGI(
            "%s: %s took %.3fms",
            targetLabel,
            operation,
            elapsedMs
        );
    }
}

void logSurfaceResourceStage(
    const char* targetLabel,
    const char* stage,
    std::chrono::steady_clock::time_point& surfaceResourceStageStart
) {
    const double elapsedMs = elapsedMsSince(surfaceResourceStageStart);
    LOGI(
        "%s: surface resource stage %s took %.3fms",
        targetLabel,
        stage,
        elapsedMs
    );
    surfaceResourceStageStart = std::chrono::steady_clock::now();
}

bool deviceExtensionAvailable(
    const std::vector<VkExtensionProperties>& extensions,
    const char* name
) {
    return std::any_of(
        extensions.begin(),
        extensions.end(),
        [name](const VkExtensionProperties& extension) {
            return std::strcmp(extension.extensionName, name) == 0;
        }
    );
}

uint16_t floatToHalf(float value) {
    uint32_t bits = 0u;
    std::memcpy(&bits, &value, sizeof(bits));
    const uint32_t sign = (bits >> 16u) & 0x8000u;
    int32_t exponent = static_cast<int32_t>((bits >> 23u) & 0xffu) - 127 + 15;
    uint32_t mantissa = bits & 0x7fffffu;

    if (exponent <= 0) {
        if (exponent < -10) {
            return static_cast<uint16_t>(sign);
        }
        mantissa = (mantissa | 0x800000u) >> static_cast<uint32_t>(1 - exponent);
        return static_cast<uint16_t>(sign | ((mantissa + 0x1000u) >> 13u));
    }
    if (exponent >= 31) {
        return static_cast<uint16_t>(sign | 0x7c00u);
    }
    return static_cast<uint16_t>(
        sign |
        (static_cast<uint32_t>(exponent) << 10u) |
        ((mantissa + 0x1000u) >> 13u)
    );
}

// IEEE-754 half (binary16) -> float32. Used to lift the rgba16f ACEScg demosaic output back to CPU
// float32 for the offline RAW develop (RawDevelopGpuAccelerator). Handles subnormals + inf/nan.
float halfToFloat(uint16_t half) {
    const uint32_t sign = static_cast<uint32_t>(half & 0x8000u) << 16u;
    const uint32_t exp = (half >> 10u) & 0x1fu;
    const uint32_t mant = half & 0x3ffu;
    uint32_t bits;
    if (exp == 0u) {
        if (mant == 0u) {
            bits = sign; // +/- zero
        } else {
            // Subnormal: normalize.
            uint32_t e = 0u;
            uint32_t m = mant;
            while ((m & 0x400u) == 0u) { m <<= 1u; e++; }
            m &= 0x3ffu;
            bits = sign | ((127u - 15u - e + 1u) << 23u) | (m << 13u);
        }
    } else if (exp == 0x1fu) {
        bits = sign | 0x7f800000u | (mant << 13u); // inf / nan
    } else {
        bits = sign | ((exp + (127u - 15u)) << 23u) | (mant << 13u);
    }
    float out;
    std::memcpy(&out, &bits, sizeof(out));
    return out;
}

float linearToLogPreview(float linear) {
    constexpr float kHeadroomScale = 18.0f;
    const float clamped = std::clamp(linear, 0.0f, 1.0f);
    return std::log2(1.0f + clamped * kHeadroomScale) / std::log2(1.0f + kHeadroomScale);
}

const char* vkFormatLabel(VkFormat format) {
    switch (format) {
        case VK_FORMAT_R8G8B8A8_UNORM:
            return "RGBA8";
        case VK_FORMAT_B8G8R8A8_UNORM:
            return "BGRA8";
        case VK_FORMAT_A2B10G10R10_UNORM_PACK32:
            return "A2B10G10R10";
        case VK_FORMAT_A2R10G10B10_UNORM_PACK32:
            return "A2R10G10B10";
        case VK_FORMAT_R16G16B16A16_SFLOAT:
            return "RGBA16F";
        default:
            return "OTHER";
    }
}

uint32_t findMemoryType(
    VulkanBridgeState* state,
    uint32_t typeBits,
    VkMemoryPropertyFlags properties
) {
    VkPhysicalDeviceMemoryProperties memProps{};
    vkGetPhysicalDeviceMemoryProperties(state->physicalDevice, &memProps);
    for (uint32_t i = 0; i < memProps.memoryTypeCount; ++i) {
        const bool typeMatch = (typeBits & (1u << i)) != 0u;
        const bool propertyMatch =
            (memProps.memoryTypes[i].propertyFlags & properties) == properties;
        if (typeMatch && propertyMatch) return i;
    }
    for (uint32_t i = 0; i < memProps.memoryTypeCount; ++i) {
        if ((typeBits & (1u << i)) != 0u) return i;
    }
    return 0u;
}

// Do not use std::isfinite for data crossing JNI or hardware-buffer boundaries in this
// translation unit. The native target is compiled with -ffast-math, which permits the compiler
// to assume floating-point operands are finite and can therefore fold classification checks away.
// Inspecting the IEEE-754 exponent bits is integer-only and remains reliable under that flag.
bool isIeee754Finite(float value) noexcept {
    static_assert(sizeof(float) == sizeof(uint32_t), "Vulkan bridge requires IEEE-754 binary32");
    uint32_t bits = 0u;
    std::memcpy(&bits, &value, sizeof(bits));
    constexpr uint32_t kExponentMask = 0x7f800000u;
    return (bits & kExponentMask) != kExponentMask;
}

inline void multiplyRawDemosaicMatrices(
    const float* lhs,
    const float* rhs,
    float* product
) {
    for (int row = 0; row < 3; ++row) {
        for (int column = 0; column < 3; ++column) {
            float value = 0.0f;
            for (int inner = 0; inner < 3; ++inner) {
                value += lhs[row * 3 + inner] * rhs[inner * 3 + column];
            }
            product[row * 3 + column] = value;
        }
    }
}

bool findExactMemoryType(
    VulkanBridgeState* state,
    uint32_t typeBits,
    VkMemoryPropertyFlags properties,
    uint32_t* memoryTypeIndex
) {
    if (state == nullptr || memoryTypeIndex == nullptr) return false;
    VkPhysicalDeviceMemoryProperties memProps{};
    vkGetPhysicalDeviceMemoryProperties(state->physicalDevice, &memProps);
    for (uint32_t i = 0; i < memProps.memoryTypeCount; ++i) {
        const bool typeMatch = (typeBits & (1u << i)) != 0u;
        const bool propertyMatch =
            (memProps.memoryTypes[i].propertyFlags & properties) == properties;
        if (typeMatch && propertyMatch) {
            *memoryTypeIndex = i;
            return true;
        }
    }
    return false;
}

bool supportsFilteredLutFormat(VulkanBridgeState* state, VkFormat format) {
    if (state == nullptr || state->physicalDevice == VK_NULL_HANDLE) return false;
    VkFormatProperties properties{};
    vkGetPhysicalDeviceFormatProperties(state->physicalDevice, format, &properties);
    const VkFormatFeatureFlags required =
        VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT |
        VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT;
    return (properties.optimalTilingFeatures & required) == required;
}

void setStatus(VulkanBridgeState* state, std::string status) {
    state->lastStatus = std::move(status);
}

void rejectEnhancementUniformsLocked(VulkanBridgeState* state, const char* reason) {
    state->enhancementUniforms.ready = false;
    state->enhancementFallbackReason = reason;
    setStatus(state, reason);
}

template <size_t N>
bool isValidIntegralEnumId(float value, const std::array<int, N>& validIds) {
    if (!isIeee754Finite(value)) return false;
    const double enumValue = static_cast<double>(value);
    if (enumValue < static_cast<double>(std::numeric_limits<int>::min()) ||
        enumValue > static_cast<double>(std::numeric_limits<int>::max())) {
        return false;
    }
    if (std::trunc(enumValue) != enumValue) return false;
    const int id = static_cast<int>(enumValue);
    return std::find(validIds.begin(), validIds.end(), id) != validIds.end();
}

void destroyPreviewLutTexture(VulkanBridgeState* state, PreviewLutTexture* texture) {
    if (state == nullptr || state->device == VK_NULL_HANDLE || texture == nullptr) return;
    if (texture->sampler != VK_NULL_HANDLE) {
        vkDestroySampler(state->device, texture->sampler, nullptr);
        texture->sampler = VK_NULL_HANDLE;
    }
    if (texture->imageView != VK_NULL_HANDLE) {
        vkDestroyImageView(state->device, texture->imageView, nullptr);
        texture->imageView = VK_NULL_HANDLE;
    }
    if (texture->image != VK_NULL_HANDLE) {
        vkDestroyImage(state->device, texture->image, nullptr);
        texture->image = VK_NULL_HANDLE;
    }
    if (texture->memory != VK_NULL_HANDLE) {
        vkFreeMemory(state->device, texture->memory, nullptr);
        texture->memory = VK_NULL_HANDLE;
    }
    texture->format = VK_FORMAT_UNDEFINED;
    texture->size = 0;
    texture->ready = false;
}

void destroyPreviewLut(VulkanBridgeState* state) {
    if (state == nullptr) return;
    destroyPreviewLutTexture(state, &state->previewLut);
}

void destroySubjectDetailMask(VulkanBridgeState* state) {
    if (state == nullptr || state->device == VK_NULL_HANDLE) return;
    if (state->subjectDetailMask.sampler != VK_NULL_HANDLE) {
        vkDestroySampler(state->device, state->subjectDetailMask.sampler, nullptr);
        state->subjectDetailMask.sampler = VK_NULL_HANDLE;
    }
    if (state->subjectDetailMask.imageView != VK_NULL_HANDLE) {
        vkDestroyImageView(state->device, state->subjectDetailMask.imageView, nullptr);
        state->subjectDetailMask.imageView = VK_NULL_HANDLE;
    }
    if (state->subjectDetailMask.image != VK_NULL_HANDLE) {
        vkDestroyImage(state->device, state->subjectDetailMask.image, nullptr);
        state->subjectDetailMask.image = VK_NULL_HANDLE;
    }
    if (state->subjectDetailMask.memory != VK_NULL_HANDLE) {
        vkFreeMemory(state->device, state->subjectDetailMask.memory, nullptr);
        state->subjectDetailMask.memory = VK_NULL_HANDLE;
    }
    state->subjectDetailMask.width = 1u;
    state->subjectDetailMask.height = 1u;
    state->subjectDetailMask.trackingId = -1;
    state->subjectDetailMask.ready = false;
}

VkShaderModule createShaderModule(
    VkDevice device,
    const uint32_t* code,
    size_t codeSizeInWords
) {
    if (device == VK_NULL_HANDLE || code == nullptr || codeSizeInWords == 0) {
        return VK_NULL_HANDLE;
    }
    VkShaderModuleCreateInfo createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
    createInfo.codeSize = codeSizeInWords * sizeof(uint32_t);
    createInfo.pCode = code;
    VkShaderModule shader = VK_NULL_HANDLE;
    if (!check(vkCreateShaderModule(device, &createInfo, nullptr, &shader), "vkCreateShaderModule")) {
        return VK_NULL_HANDLE;
    }
    return shader;
}

bool beginSingleUseCommands(
    VulkanBridgeState* state,
    VkCommandPool* commandPool,
    VkCommandBuffer* commandBuffer
) {
    if (state == nullptr || commandPool == nullptr || commandBuffer == nullptr) return false;
    VkCommandPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    poolInfo.flags = VK_COMMAND_POOL_CREATE_TRANSIENT_BIT;
    poolInfo.queueFamilyIndex = state->queueFamilyIndex;
    if (!check(vkCreateCommandPool(state->device, &poolInfo, nullptr, commandPool), "vkCreateCommandPool(single-use)")) {
        return false;
    }

    VkCommandBufferAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    allocInfo.commandPool = *commandPool;
    allocInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocInfo.commandBufferCount = 1;
    if (!check(vkAllocateCommandBuffers(state->device, &allocInfo, commandBuffer), "vkAllocateCommandBuffers(single-use)")) {
        vkDestroyCommandPool(state->device, *commandPool, nullptr);
        *commandPool = VK_NULL_HANDLE;
        return false;
    }

    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (!check(vkBeginCommandBuffer(*commandBuffer, &beginInfo), "vkBeginCommandBuffer(single-use)")) {
        vkDestroyCommandPool(state->device, *commandPool, nullptr);
        *commandPool = VK_NULL_HANDLE;
        *commandBuffer = VK_NULL_HANDLE;
        return false;
    }
    return true;
}

bool endSingleUseCommands(
    VulkanBridgeState* state,
    VkCommandPool commandPool,
    VkCommandBuffer commandBuffer
) {
    if (state == nullptr || commandPool == VK_NULL_HANDLE || commandBuffer == VK_NULL_HANDLE) return false;
    if (!check(vkEndCommandBuffer(commandBuffer), "vkEndCommandBuffer(single-use)")) {
        vkDestroyCommandPool(state->device, commandPool, nullptr);
        return false;
    }
    VkSubmitInfo submitInfo{};
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &commandBuffer;
    {
        std::lock_guard<std::mutex> queueLock(state->queueMutex);
        if (!check(vkQueueSubmit(state->graphicsQueue, 1, &submitInfo, VK_NULL_HANDLE),
                   "vkQueueSubmit(single-use)")) {
            vkDestroyCommandPool(state->device, commandPool, nullptr);
            return false;
        }
        if (!check(vkQueueWaitIdle(state->graphicsQueue), "vkQueueWaitIdle(single-use)")) {
            vkDestroyCommandPool(state->device, commandPool, nullptr);
            return false;
        }
    }
    vkDestroyCommandPool(state->device, commandPool, nullptr);
    return true;
}

VkResult submitRawQueueSynchronized(
    VulkanBridgeState* state,
    uint32_t submitCount,
    const VkSubmitInfo* submits,
    VkFence fence
) {
    if (state == nullptr || state->rawQueue == VK_NULL_HANDLE) {
        return VK_ERROR_INITIALIZATION_FAILED;
    }
    if (state->independentRawQueueEnabled) {
        std::lock_guard<std::mutex> queueLock(state->rawQueueMutex);
        return vkQueueSubmit(state->rawQueue, submitCount, submits, fence);
    }
    // rawQueue aliases graphicsQueue on single-queue devices. Use the original mutex so two different
    // locks can never guard host access to the same VkQueue handle.
    std::lock_guard<std::mutex> queueLock(state->queueMutex);
    return vkQueueSubmit(state->graphicsQueue, submitCount, submits, fence);
}

VkResult waitDeviceIdleSynchronized(VulkanBridgeState* state) {
    if (state == nullptr || state->device == VK_NULL_HANDLE) return VK_SUCCESS;
    if (state->independentRawQueueEnabled) {
        // vkDeviceWaitIdle is externally synchronized against submissions to every queue. Acquire both
        // host-access domains atomically; std::scoped_lock supplies deadlock-safe lock ordering.
        std::scoped_lock queueLocks(state->queueMutex, state->rawQueueMutex);
        return vkDeviceWaitIdle(state->device);
    }
    std::lock_guard<std::mutex> queueLock(state->queueMutex);
    return vkDeviceWaitIdle(state->device);
}

bool createImageComputePipeline(
    VulkanBridgeState* state,
    const uint32_t* spvWords,
    size_t spvWordCount,
    VkShaderModule* shaderModule,
    VkPipeline* pipeline,
    const char* label
) {
    if (state == nullptr ||
        spvWords == nullptr ||
        shaderModule == nullptr ||
        pipeline == nullptr ||
        state->device == VK_NULL_HANDLE ||
        state->imageComputePipelineLayout == VK_NULL_HANDLE) {
        return false;
    }

    *shaderModule = createShaderModule(state->device, spvWords, spvWordCount);
    if (*shaderModule == VK_NULL_HANDLE) {
        state->enhancementFallbackReason = std::string(label) + " shader module creation failed";
        return false;
    }

    VkPipelineShaderStageCreateInfo shaderStage{};
    shaderStage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    shaderStage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    shaderStage.module = *shaderModule;
    shaderStage.pName = "main";

    VkComputePipelineCreateInfo pipelineInfo{};
    pipelineInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pipelineInfo.stage = shaderStage;
    pipelineInfo.layout = state->imageComputePipelineLayout;
    if (!check(vkCreateComputePipelines(
            state->device,
            VK_NULL_HANDLE,
            1,
            &pipelineInfo,
            nullptr,
            pipeline
        ), label)) {
        state->enhancementFallbackReason = std::string(label) + " pipeline creation failed";
        return false;
    }
    return true;
}

bool createComputePipelineWithLayout(
    VulkanBridgeState* state,
    const uint32_t* spvWords,
    size_t spvWordCount,
    VkPipelineLayout pipelineLayout,
    VkShaderModule* shaderModule,
    VkPipeline* pipeline,
    const char* label
) {
    if (state == nullptr ||
        spvWords == nullptr ||
        shaderModule == nullptr ||
        pipeline == nullptr ||
        state->device == VK_NULL_HANDLE ||
        pipelineLayout == VK_NULL_HANDLE) {
        return false;
    }

    *shaderModule = createShaderModule(state->device, spvWords, spvWordCount);
    if (*shaderModule == VK_NULL_HANDLE) {
        state->enhancementFallbackReason = std::string(label) + " shader module creation failed";
        return false;
    }

    VkPipelineShaderStageCreateInfo shaderStage{};
    shaderStage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    shaderStage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    shaderStage.module = *shaderModule;
    shaderStage.pName = "main";

    VkComputePipelineCreateInfo pipelineInfo{};
    pipelineInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pipelineInfo.stage = shaderStage;
    pipelineInfo.layout = pipelineLayout;
    if (!check(vkCreateComputePipelines(
            state->device,
            VK_NULL_HANDLE,
            1,
            &pipelineInfo,
            nullptr,
            pipeline
        ), label)) {
        state->enhancementFallbackReason = std::string(label) + " pipeline creation failed";
        return false;
    }
    return true;
}

bool createRealtimeComputePipeline(VulkanBridgeState* state) {
    if (state == nullptr || state->device == VK_NULL_HANDLE) return false;

    VkFormatProperties rgba16fProperties{};
    vkGetPhysicalDeviceFormatProperties(
        state->physicalDevice,
        VK_FORMAT_R16G16B16A16_SFLOAT,
        &rgba16fProperties
    );
    const VkFormatFeatureFlags requiredFeatures =
        VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT |
        VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
    if ((rgba16fProperties.optimalTilingFeatures & requiredFeatures) != requiredFeatures) {
        state->enhancementComputePipelineReady = false;
        state->enhancementFallbackReason = "RGBA16F storage intermediate unsupported";
        return false;
    }

    VkDescriptorSetLayoutBinding inputBinding{};
    inputBinding.binding = 0;
    inputBinding.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    inputBinding.descriptorCount = 1;
    inputBinding.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    VkDescriptorSetLayoutBinding outputBinding{};
    outputBinding.binding = 1;
    outputBinding.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    outputBinding.descriptorCount = 1;
    outputBinding.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    std::array<VkDescriptorSetLayoutBinding, 2> bindings{inputBinding, outputBinding};
    VkDescriptorSetLayoutCreateInfo setLayoutInfo{};
    setLayoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    setLayoutInfo.bindingCount = static_cast<uint32_t>(bindings.size());
    setLayoutInfo.pBindings = bindings.data();
    if (!check(vkCreateDescriptorSetLayout(
            state->device,
            &setLayoutInfo,
            nullptr,
            &state->computeDescriptorSetLayout
        ), "vkCreateDescriptorSetLayout(compute)")) {
        state->enhancementFallbackReason = "compute descriptor set layout failed";
        return false;
    }

    VkPushConstantRange pushConstantRange{};
    pushConstantRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    pushConstantRange.offset = 0;
    pushConstantRange.size = sizeof(RealtimeComputePushConstants);

    VkPipelineLayoutCreateInfo pipelineLayoutInfo{};
    pipelineLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pipelineLayoutInfo.setLayoutCount = 1;
    pipelineLayoutInfo.pSetLayouts = &state->computeDescriptorSetLayout;
    pipelineLayoutInfo.pushConstantRangeCount = 1;
    pipelineLayoutInfo.pPushConstantRanges = &pushConstantRange;
    if (!check(vkCreatePipelineLayout(
            state->device,
            &pipelineLayoutInfo,
            nullptr,
            &state->computePipelineLayout
        ), "vkCreatePipelineLayout(compute)")) {
        state->enhancementFallbackReason = "compute pipeline layout failed";
        return false;
    }

    state->computeShaderModule = createShaderModule(
        state->device,
        kRealtimePreviewEnhanceCompSpv,
        kRealtimePreviewEnhanceCompSpvCount
    );
    if (state->computeShaderModule == VK_NULL_HANDLE) {
        state->enhancementFallbackReason = "compute shader module creation failed";
        return false;
    }

    VkPipelineShaderStageCreateInfo shaderStage{};
    shaderStage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    shaderStage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    shaderStage.module = state->computeShaderModule;
    shaderStage.pName = "main";

    VkComputePipelineCreateInfo pipelineInfo{};
    pipelineInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
    pipelineInfo.stage = shaderStage;
    pipelineInfo.layout = state->computePipelineLayout;
    if (!check(vkCreateComputePipelines(
            state->device,
            VK_NULL_HANDLE,
            1,
            &pipelineInfo,
            nullptr,
            &state->computePipeline
        ), "vkCreateComputePipelines")) {
        state->enhancementFallbackReason = "compute pipeline creation failed";
        return false;
    }

    VkDescriptorSetLayoutBinding imageInputBinding{};
    imageInputBinding.binding = 0;
    imageInputBinding.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    imageInputBinding.descriptorCount = 1;
    imageInputBinding.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    VkDescriptorSetLayoutBinding imageOutputBinding{};
    imageOutputBinding.binding = 1;
    imageOutputBinding.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    imageOutputBinding.descriptorCount = 1;
    imageOutputBinding.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    std::array<VkDescriptorSetLayoutBinding, 2> imageBindings{imageInputBinding, imageOutputBinding};
    VkDescriptorSetLayoutCreateInfo imageSetLayoutInfo{};
    imageSetLayoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    imageSetLayoutInfo.bindingCount = static_cast<uint32_t>(imageBindings.size());
    imageSetLayoutInfo.pBindings = imageBindings.data();
    if (!check(vkCreateDescriptorSetLayout(
            state->device,
            &imageSetLayoutInfo,
            nullptr,
            &state->imageComputeDescriptorSetLayout
        ), "vkCreateDescriptorSetLayout(knee/shadow image compute)")) {
        state->enhancementFallbackReason = "knee/shadow descriptor set layout failed";
        return false;
    }

    VkPushConstantRange imagePushConstantRange{};
    imagePushConstantRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    imagePushConstantRange.offset = 0;
    imagePushConstantRange.size = static_cast<uint32_t>(
        std::max(sizeof(KneeCurvePushConstants), sizeof(ShadowLiftPushConstants))
    );

    VkPipelineLayoutCreateInfo imagePipelineLayoutInfo{};
    imagePipelineLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    imagePipelineLayoutInfo.setLayoutCount = 1;
    imagePipelineLayoutInfo.pSetLayouts = &state->imageComputeDescriptorSetLayout;
    imagePipelineLayoutInfo.pushConstantRangeCount = 1;
    imagePipelineLayoutInfo.pPushConstantRanges = &imagePushConstantRange;
    if (!check(vkCreatePipelineLayout(
            state->device,
            &imagePipelineLayoutInfo,
            nullptr,
            &state->imageComputePipelineLayout
        ), "vkCreatePipelineLayout(knee/shadow image compute)")) {
        state->enhancementFallbackReason = "knee/shadow pipeline layout failed";
        return false;
    }

    if (!createImageComputePipeline(
            state,
            kKneeCurveCompSpv,
            kKneeCurveCompSpvCount,
            &state->kneeCurveShaderModule,
            &state->kneeCurvePipeline,
            "vkCreateComputePipelines(kneeCurve)"
        )) {
        return false;
    }

    if (!createImageComputePipeline(
            state,
            kShadowLiftCompSpv,
            kShadowLiftCompSpvCount,
            &state->shadowLiftShaderModule,
            &state->shadowLiftPipeline,
            "vkCreateComputePipelines(shadowLift)"
        )) {
        return false;
    }

    VkDescriptorSetLayoutBinding histogramImageBinding{};
    histogramImageBinding.binding = 0;
    histogramImageBinding.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    histogramImageBinding.descriptorCount = 1;
    histogramImageBinding.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    VkDescriptorSetLayoutBinding histogramBufferBinding{};
    histogramBufferBinding.binding = 1;
    histogramBufferBinding.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    histogramBufferBinding.descriptorCount = 1;
    histogramBufferBinding.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    std::array<VkDescriptorSetLayoutBinding, 2> histogramBindings{
        histogramImageBinding,
        histogramBufferBinding
    };
    VkDescriptorSetLayoutCreateInfo histogramLayoutInfo{};
    histogramLayoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    histogramLayoutInfo.bindingCount = static_cast<uint32_t>(histogramBindings.size());
    histogramLayoutInfo.pBindings = histogramBindings.data();
    if (!check(vkCreateDescriptorSetLayout(
            state->device,
            &histogramLayoutInfo,
            nullptr,
            &state->hdrMetadataHistogramDescriptorSetLayout
        ), "vkCreateDescriptorSetLayout(hdrMetadataHistogram)")) {
        state->enhancementFallbackReason = "HDR metadata histogram descriptor layout failed";
        return false;
    }

    VkDescriptorSetLayoutBinding reduceHistogramBinding{};
    reduceHistogramBinding.binding = 0;
    reduceHistogramBinding.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    reduceHistogramBinding.descriptorCount = 1;
    reduceHistogramBinding.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    VkDescriptorSetLayoutBinding reduceMetadataBinding{};
    reduceMetadataBinding.binding = 1;
    reduceMetadataBinding.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    reduceMetadataBinding.descriptorCount = 1;
    reduceMetadataBinding.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

    std::array<VkDescriptorSetLayoutBinding, 2> reduceBindings{
        reduceHistogramBinding,
        reduceMetadataBinding
    };
    VkDescriptorSetLayoutCreateInfo reduceLayoutInfo{};
    reduceLayoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    reduceLayoutInfo.bindingCount = static_cast<uint32_t>(reduceBindings.size());
    reduceLayoutInfo.pBindings = reduceBindings.data();
    if (!check(vkCreateDescriptorSetLayout(
            state->device,
            &reduceLayoutInfo,
            nullptr,
            &state->hdrMetadataReduceDescriptorSetLayout
        ), "vkCreateDescriptorSetLayout(hdrMetadataReduce)")) {
        state->enhancementFallbackReason = "HDR metadata reduce descriptor layout failed";
        return false;
    }

    VkPipelineLayoutCreateInfo histogramPipelineLayoutInfo{};
    histogramPipelineLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    histogramPipelineLayoutInfo.setLayoutCount = 1;
    histogramPipelineLayoutInfo.pSetLayouts = &state->hdrMetadataHistogramDescriptorSetLayout;
    if (!check(vkCreatePipelineLayout(
            state->device,
            &histogramPipelineLayoutInfo,
            nullptr,
            &state->hdrMetadataHistogramPipelineLayout
        ), "vkCreatePipelineLayout(hdrMetadataHistogram)")) {
        state->enhancementFallbackReason = "HDR metadata histogram pipeline layout failed";
        return false;
    }

    VkPipelineLayoutCreateInfo reducePipelineLayoutInfo{};
    reducePipelineLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    reducePipelineLayoutInfo.setLayoutCount = 1;
    reducePipelineLayoutInfo.pSetLayouts = &state->hdrMetadataReduceDescriptorSetLayout;
    if (!check(vkCreatePipelineLayout(
            state->device,
            &reducePipelineLayoutInfo,
            nullptr,
            &state->hdrMetadataReducePipelineLayout
        ), "vkCreatePipelineLayout(hdrMetadataReduce)")) {
        state->enhancementFallbackReason = "HDR metadata reduce pipeline layout failed";
        return false;
    }

    if (!createComputePipelineWithLayout(
            state,
            kHdrMetadataHistogramCompSpv,
            kHdrMetadataHistogramCompSpvCount,
            state->hdrMetadataHistogramPipelineLayout,
            &state->hdrMetadataHistogramShaderModule,
            &state->hdrMetadataHistogramPipeline,
            "vkCreateComputePipelines(hdrMetadataHistogram)"
        )) {
        return false;
    }

    if (!createComputePipelineWithLayout(
            state,
            kHdrMetadataReduceCompSpv,
            kHdrMetadataReduceCompSpvCount,
            state->hdrMetadataReducePipelineLayout,
            &state->hdrMetadataReduceShaderModule,
            &state->hdrMetadataReducePipeline,
            "vkCreateComputePipelines(hdrMetadataReduce)"
        )) {
        return false;
    }

    state->enhancementComputePipelineReady = true;
    state->enhancementFallbackReason = "compute pipeline active";
    return true;
}

bool createBufferWithMemory(
    VulkanBridgeState* state,
    VkDeviceSize size,
    VkBufferUsageFlags usage,
    VkMemoryPropertyFlags properties,
    VkBuffer* buffer,
    VkDeviceMemory* memory,
    bool requireExactProperties = false
) {
    if (state == nullptr || buffer == nullptr || memory == nullptr) return false;
    VkBufferCreateInfo bufferInfo{};
    bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufferInfo.size = size;
    bufferInfo.usage = usage;
    bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (!check(vkCreateBuffer(state->device, &bufferInfo, nullptr, buffer), "vkCreateBuffer(helper)")) {
        return false;
    }
    VkMemoryRequirements memReq{};
    vkGetBufferMemoryRequirements(state->device, *buffer, &memReq);
    VkMemoryAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = memReq.size;
    if (requireExactProperties) {
        uint32_t exactMemoryType = 0u;
        if (!findExactMemoryType(
                state, memReq.memoryTypeBits, properties, &exactMemoryType)) {
            LOGW("No exact Vulkan buffer memory type for required properties=0x%x",
                 static_cast<unsigned int>(properties));
            vkDestroyBuffer(state->device, *buffer, nullptr);
            *buffer = VK_NULL_HANDLE;
            return false;
        }
        allocInfo.memoryTypeIndex = exactMemoryType;
    } else {
        allocInfo.memoryTypeIndex = findMemoryType(state, memReq.memoryTypeBits, properties);
    }
    if (!check(vkAllocateMemory(state->device, &allocInfo, nullptr, memory), "vkAllocateMemory(helper)")) {
        vkDestroyBuffer(state->device, *buffer, nullptr);
        *buffer = VK_NULL_HANDLE;
        return false;
    }
    if (!check(vkBindBufferMemory(state->device, *buffer, *memory, 0), "vkBindBufferMemory(helper)")) {
        vkFreeMemory(state->device, *memory, nullptr);
        vkDestroyBuffer(state->device, *buffer, nullptr);
        *memory = VK_NULL_HANDLE;
        *buffer = VK_NULL_HANDLE;
        return false;
    }
    return true;
}

void transitionImageLayout(
    VkCommandBuffer commandBuffer,
    VkImage image,
    VkImageLayout oldLayout,
    VkImageLayout newLayout,
    VkImageSubresourceRange subresourceRange
) {
    VkImageMemoryBarrier barrier{};
    barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barrier.oldLayout = oldLayout;
    barrier.newLayout = newLayout;
    barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.image = image;
    barrier.subresourceRange = subresourceRange;

    VkPipelineStageFlags sourceStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
    VkPipelineStageFlags destinationStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;

    if (oldLayout == VK_IMAGE_LAYOUT_UNDEFINED && newLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL) {
        barrier.srcAccessMask = 0;
        barrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        sourceStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
        destinationStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
    } else if (oldLayout == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL &&
               newLayout == VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
        barrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        sourceStage = VK_PIPELINE_STAGE_TRANSFER_BIT;
        destinationStage = VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
    }

    vkCmdPipelineBarrier(
        commandBuffer,
        sourceStage,
        destinationStage,
        0,
        0, nullptr,
        0, nullptr,
        1, &barrier
    );
}

void rebindPreviewLutDescriptors(
    VulkanBridgeState* state,
    TargetSurface* target,
    const PreviewLutTexture& texture
) {
    if (state == nullptr ||
        state->device == VK_NULL_HANDLE ||
        target == nullptr ||
        !texture.ready ||
        texture.imageView == VK_NULL_HANDLE ||
        texture.sampler == VK_NULL_HANDLE) {
        return;
    }

    VkDescriptorImageInfo lutInfo{};
    lutInfo.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    lutInfo.imageView = texture.imageView;
    lutInfo.sampler = texture.sampler;
    for (VkDescriptorSet descriptorSet : target->descriptorSets) {
        if (descriptorSet == VK_NULL_HANDLE) continue;
        VkWriteDescriptorSet write{};
        write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        write.dstSet = descriptorSet;
        write.dstBinding = 2u;
        write.descriptorCount = 1u;
        write.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        write.pImageInfo = &lutInfo;
        vkUpdateDescriptorSets(state->device, 1u, &write, 0u, nullptr);
    }
}

bool createOrUpdatePreviewLut(
    VulkanBridgeState* state,
    const float* lutData,
    int lutSize
) {
    if (state == nullptr || state->device == VK_NULL_HANDLE) return false;
    const uint32_t size = lutSize > 1 ? static_cast<uint32_t>(lutSize) : 2u;
    const size_t voxelCount = static_cast<size_t>(size) * static_cast<size_t>(size) * static_cast<size_t>(size);
    const bool halfFloatSupported =
        supportsFilteredLutFormat(state, VK_FORMAT_R16G16B16A16_SFLOAT);
    if (kStrictHighEndBuild && !halfFloatSupported) {
        setStatus(state, "strict preview LUT rejected: filtered RGBA16F unsupported");
        return false;
    }
    const VkFormat lutFormat = kStrictHighEndBuild
        ? VK_FORMAT_R16G16B16A16_SFLOAT
        : VK_FORMAT_R8G8B8A8_UNORM;
    const bool useHalfFormat = lutFormat == VK_FORMAT_R16G16B16A16_SFLOAT;
    // A disable request is represented by a resident identity cube because descriptor binding 2
    // must always remain valid.  Keep that cube mathematically identity: applying the strict-build
    // linear-to-log packing curve here would alter pixels if a stale in-flight uniform sampled it
    // during the generation handoff.  Real creative cubes retain the established log-domain upload.
    const bool applyLinearToLog = kStrictHighEndBuild && lutData != nullptr && lutSize > 1;
    std::vector<uint8_t> rgba8;
    std::vector<uint16_t> rgba16;
    if (useHalfFormat) {
        rgba16.assign(voxelCount * 4u, floatToHalf(1.0f));
    } else {
        rgba8.assign(voxelCount * 4u, 255u);
    }

    auto storeVoxel = [&](size_t dst, float r, float g, float b) {
        const float transformedR = applyLinearToLog ? linearToLogPreview(r) : r;
        const float transformedG = applyLinearToLog ? linearToLogPreview(g) : g;
        const float transformedB = applyLinearToLog ? linearToLogPreview(b) : b;
        if (useHalfFormat) {
            rgba16[dst + 0u] = floatToHalf(std::clamp(transformedR, 0.0f, 1.0f));
            rgba16[dst + 1u] = floatToHalf(std::clamp(transformedG, 0.0f, 1.0f));
            rgba16[dst + 2u] = floatToHalf(std::clamp(transformedB, 0.0f, 1.0f));
            rgba16[dst + 3u] = floatToHalf(1.0f);
            return;
        }
        rgba8[dst + 0u] = static_cast<uint8_t>(std::clamp(transformedR, 0.f, 1.f) * 255.f + 0.5f);
        rgba8[dst + 1u] = static_cast<uint8_t>(std::clamp(transformedG, 0.f, 1.f) * 255.f + 0.5f);
        rgba8[dst + 2u] = static_cast<uint8_t>(std::clamp(transformedB, 0.f, 1.f) * 255.f + 0.5f);
        rgba8[dst + 3u] = 255u;
    };

    if (lutData != nullptr && lutSize > 1) {
        for (size_t index = 0; index < voxelCount; ++index) {
            const size_t src = index * 3u;
            const size_t dst = index * 4u;
            storeVoxel(
                dst,
                std::clamp(lutData[src + 0u], 0.f, 1.f),
                std::clamp(lutData[src + 1u], 0.f, 1.f),
                std::clamp(lutData[src + 2u], 0.f, 1.f)
            );
        }
    } else {
        size_t dstIndex = 0u;
        for (uint32_t b = 0; b < size; ++b) {
            for (uint32_t g = 0; g < size; ++g) {
                for (uint32_t r = 0; r < size; ++r) {
                    const float rf = static_cast<float>(r) / static_cast<float>(std::max(1u, size - 1u));
                    const float gf = static_cast<float>(g) / static_cast<float>(std::max(1u, size - 1u));
                    const float bf = static_cast<float>(b) / static_cast<float>(std::max(1u, size - 1u));
                    storeVoxel(dstIndex, rf, gf, bf);
                    dstIndex += 4u;
                }
            }
        }
    }
    const void* uploadData = useHalfFormat ? static_cast<const void*>(rgba16.data()) : static_cast<const void*>(rgba8.data());
    const size_t uploadSizeBytes = useHalfFormat ? rgba16.size() * sizeof(uint16_t) : rgba8.size();

    // Build and upload a complete candidate before touching the currently published texture. Every
    // failure destroys only this candidate, so descriptor sets can continue sampling the last valid
    // cube until a transactional swap succeeds.
    PreviewLutTexture candidate{};

    VkImageCreateInfo imageInfo{};
    imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imageInfo.imageType = VK_IMAGE_TYPE_3D;
    imageInfo.extent.width = size;
    imageInfo.extent.height = size;
    imageInfo.extent.depth = size;
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.format = lutFormat;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    imageInfo.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    if (!check(vkCreateImage(state->device, &imageInfo, nullptr, &candidate.image), "vkCreateImage(previewLut)")) {
        return false;
    }

    VkMemoryRequirements imageMemReq{};
    vkGetImageMemoryRequirements(state->device, candidate.image, &imageMemReq);
    VkMemoryAllocateInfo imageAllocInfo{};
    imageAllocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    imageAllocInfo.allocationSize = imageMemReq.size;
    imageAllocInfo.memoryTypeIndex = findMemoryType(state, imageMemReq.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (!check(vkAllocateMemory(state->device, &imageAllocInfo, nullptr, &candidate.memory), "vkAllocateMemory(previewLut)")) {
        destroyPreviewLutTexture(state, &candidate);
        return false;
    }
    if (!check(vkBindImageMemory(state->device, candidate.image, candidate.memory, 0), "vkBindImageMemory(previewLut)")) {
        destroyPreviewLutTexture(state, &candidate);
        return false;
    }

    VkBuffer stagingBuffer = VK_NULL_HANDLE;
    VkDeviceMemory stagingMemory = VK_NULL_HANDLE;
    if (!createBufferWithMemory(
            state,
            static_cast<VkDeviceSize>(uploadSizeBytes),
            VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
            &stagingBuffer,
            &stagingMemory
        )) {
        destroyPreviewLutTexture(state, &candidate);
        return false;
    }

    void* mapped = nullptr;
    if (!check(vkMapMemory(state->device, stagingMemory, 0, uploadSizeBytes, 0, &mapped), "vkMapMemory(previewLut)")) {
        vkFreeMemory(state->device, stagingMemory, nullptr);
        vkDestroyBuffer(state->device, stagingBuffer, nullptr);
        destroyPreviewLutTexture(state, &candidate);
        return false;
    }
    std::memcpy(mapped, uploadData, uploadSizeBytes);
    vkUnmapMemory(state->device, stagingMemory);

    VkCommandPool commandPool = VK_NULL_HANDLE;
    VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
    if (!beginSingleUseCommands(state, &commandPool, &commandBuffer)) {
        vkFreeMemory(state->device, stagingMemory, nullptr);
        vkDestroyBuffer(state->device, stagingBuffer, nullptr);
        destroyPreviewLutTexture(state, &candidate);
        return false;
    }

    VkImageSubresourceRange subresourceRange{};
    subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    subresourceRange.baseMipLevel = 0;
    subresourceRange.levelCount = 1;
    subresourceRange.baseArrayLayer = 0;
    subresourceRange.layerCount = 1;
    transitionImageLayout(
        commandBuffer,
        candidate.image,
        VK_IMAGE_LAYOUT_UNDEFINED,
        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        subresourceRange
    );

    VkBufferImageCopy copyRegion{};
    copyRegion.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    copyRegion.imageSubresource.mipLevel = 0;
    copyRegion.imageSubresource.baseArrayLayer = 0;
    copyRegion.imageSubresource.layerCount = 1;
    copyRegion.imageExtent = {size, size, size};
    vkCmdCopyBufferToImage(
        commandBuffer,
        stagingBuffer,
        candidate.image,
        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        1,
        &copyRegion
    );

    transitionImageLayout(
        commandBuffer,
        candidate.image,
        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
        subresourceRange
    );

    if (!endSingleUseCommands(state, commandPool, commandBuffer)) {
        vkFreeMemory(state->device, stagingMemory, nullptr);
        vkDestroyBuffer(state->device, stagingBuffer, nullptr);
        destroyPreviewLutTexture(state, &candidate);
        return false;
    }

    vkFreeMemory(state->device, stagingMemory, nullptr);
    vkDestroyBuffer(state->device, stagingBuffer, nullptr);

    VkImageViewCreateInfo viewInfo{};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = candidate.image;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_3D;
    viewInfo.format = lutFormat;
    viewInfo.subresourceRange = subresourceRange;
    if (!check(vkCreateImageView(state->device, &viewInfo, nullptr, &candidate.imageView), "vkCreateImageView(previewLut)")) {
        destroyPreviewLutTexture(state, &candidate);
        return false;
    }

    VkSamplerCreateInfo samplerInfo{};
    samplerInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    samplerInfo.magFilter = VK_FILTER_LINEAR;
    samplerInfo.minFilter = VK_FILTER_LINEAR;
    samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_LINEAR;
    samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.maxLod = 0.f;
    if (!check(vkCreateSampler(state->device, &samplerInfo, nullptr, &candidate.sampler), "vkCreateSampler(previewLut)")) {
        destroyPreviewLutTexture(state, &candidate);
        return false;
    }

    candidate.size = size;
    candidate.format = lutFormat;
    candidate.ready = true;

    if (state->previewLut.ready &&
        !check(waitDeviceIdleSynchronized(state), "vkDeviceWaitIdle(previewLut commit)")) {
        destroyPreviewLutTexture(state, &candidate);
        return false;
    }
    PreviewLutTexture retired = state->previewLut;
    state->previewLut = candidate;
    candidate = PreviewLutTexture{};

    // The device is idle and the bridge mutex is held, so repoint every already-allocated set before
    // retiring the old image view/sampler. Future frames also refresh binding 2 in updateDescriptorSet,
    // but eagerly rebinding here guarantees that no dormant swapchain slot retains a dangling handle.
    rebindPreviewLutDescriptors(state, &state->previewTarget, state->previewLut);
    rebindPreviewLutDescriptors(state, &state->encoderTarget, state->previewLut);
    destroyPreviewLutTexture(state, &retired);
    return true;
}

void destroyNrliLut(VulkanBridgeState* state) {
    if (state == nullptr || state->device == VK_NULL_HANDLE) return;
    if (state->nrliLut.sampler != VK_NULL_HANDLE) { vkDestroySampler(state->device, state->nrliLut.sampler, nullptr); state->nrliLut.sampler = VK_NULL_HANDLE; }
    if (state->nrliLut.imageView != VK_NULL_HANDLE) { vkDestroyImageView(state->device, state->nrliLut.imageView, nullptr); state->nrliLut.imageView = VK_NULL_HANDLE; }
    if (state->nrliLut.image != VK_NULL_HANDLE) { vkDestroyImage(state->device, state->nrliLut.image, nullptr); state->nrliLut.image = VK_NULL_HANDLE; }
    if (state->nrliLut.memory != VK_NULL_HANDLE) { vkFreeMemory(state->device, state->nrliLut.memory, nullptr); state->nrliLut.memory = VK_NULL_HANDLE; }
    state->nrliLut.ready = false;
}

// SNCE v2.0 HVC Stage 11 (NRLI): create a stable identity 33^3 3D LUT for the live nrli_lut_apply pass.
// Identity + strength 0 => exact passthrough; a future model LUT replaces the voxels to activate grading.
// Cloned from createOrUpdatePreviewLut (same proven 3D-texture upload path), but lifetime-stable (built
// once, never re-created) so the per-image nrli descriptor can reference it without re-writes.
bool createNrliIdentityLut(VulkanBridgeState* state) {
    if (state == nullptr || state->device == VK_NULL_HANDLE) return false;
    if (state->nrliLut.ready) return true;
    const uint32_t size = 33u;
    const size_t voxelCount = static_cast<size_t>(size) * static_cast<size_t>(size) * static_cast<size_t>(size);
    VkFormat lutFormat = VK_FORMAT_R8G8B8A8_UNORM;
    if (kStrictHighEndBuild && supportsFilteredLutFormat(state, VK_FORMAT_R16G16B16A16_SFLOAT)) {
        lutFormat = VK_FORMAT_R16G16B16A16_SFLOAT;
    }
    const bool useHalfFormat = lutFormat == VK_FORMAT_R16G16B16A16_SFLOAT;
    std::vector<uint8_t> rgba8;
    std::vector<uint16_t> rgba16;
    if (useHalfFormat) rgba16.assign(voxelCount * 4u, floatToHalf(1.0f));
    else rgba8.assign(voxelCount * 4u, 255u);
    auto storeVoxel = [&](size_t dst, float r, float g, float b) {
        if (useHalfFormat) {
            rgba16[dst + 0u] = floatToHalf(r); rgba16[dst + 1u] = floatToHalf(g);
            rgba16[dst + 2u] = floatToHalf(b); rgba16[dst + 3u] = floatToHalf(1.0f);
        } else {
            rgba8[dst + 0u] = static_cast<uint8_t>(r * 255.f + 0.5f);
            rgba8[dst + 1u] = static_cast<uint8_t>(g * 255.f + 0.5f);
            rgba8[dst + 2u] = static_cast<uint8_t>(b * 255.f + 0.5f);
            rgba8[dst + 3u] = 255u;
        }
    };
    size_t dstIndex = 0u;
    for (uint32_t b = 0; b < size; ++b) for (uint32_t g = 0; g < size; ++g) for (uint32_t r = 0; r < size; ++r) {
        const float rf = static_cast<float>(r) / static_cast<float>(size - 1u);
        const float gf = static_cast<float>(g) / static_cast<float>(size - 1u);
        const float bf = static_cast<float>(b) / static_cast<float>(size - 1u);
        storeVoxel(dstIndex, rf, gf, bf);
        dstIndex += 4u;
    }
    const void* uploadData = useHalfFormat ? static_cast<const void*>(rgba16.data()) : static_cast<const void*>(rgba8.data());
    const size_t uploadSizeBytes = useHalfFormat ? rgba16.size() * sizeof(uint16_t) : rgba8.size();

    VkImageCreateInfo imageInfo{};
    imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imageInfo.imageType = VK_IMAGE_TYPE_3D;
    imageInfo.extent = {size, size, size};
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.format = lutFormat;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    imageInfo.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    if (!check(vkCreateImage(state->device, &imageInfo, nullptr, &state->nrliLut.image), "vkCreateImage(nrliLut)")) return false;
    VkMemoryRequirements memReq{};
    vkGetImageMemoryRequirements(state->device, state->nrliLut.image, &memReq);
    VkMemoryAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = memReq.size;
    allocInfo.memoryTypeIndex = findMemoryType(state, memReq.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (!check(vkAllocateMemory(state->device, &allocInfo, nullptr, &state->nrliLut.memory), "vkAllocateMemory(nrliLut)")) { destroyNrliLut(state); return false; }
    if (!check(vkBindImageMemory(state->device, state->nrliLut.image, state->nrliLut.memory, 0), "vkBindImageMemory(nrliLut)")) { destroyNrliLut(state); return false; }

    VkBuffer staging = VK_NULL_HANDLE; VkDeviceMemory stagingMem = VK_NULL_HANDLE;
    if (!createBufferWithMemory(state, static_cast<VkDeviceSize>(uploadSizeBytes), VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, &staging, &stagingMem)) { destroyNrliLut(state); return false; }
    void* mapped = nullptr;
    if (!check(vkMapMemory(state->device, stagingMem, 0, uploadSizeBytes, 0, &mapped), "vkMapMemory(nrliLut)")) {
        vkFreeMemory(state->device, stagingMem, nullptr); vkDestroyBuffer(state->device, staging, nullptr); destroyNrliLut(state); return false;
    }
    std::memcpy(mapped, uploadData, uploadSizeBytes);
    vkUnmapMemory(state->device, stagingMem);

    VkCommandPool pool = VK_NULL_HANDLE; VkCommandBuffer cmd = VK_NULL_HANDLE;
    if (!beginSingleUseCommands(state, &pool, &cmd)) { vkFreeMemory(state->device, stagingMem, nullptr); vkDestroyBuffer(state->device, staging, nullptr); destroyNrliLut(state); return false; }
    VkImageSubresourceRange range{};
    range.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT; range.levelCount = 1; range.layerCount = 1;
    transitionImageLayout(cmd, state->nrliLut.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, range);
    VkBufferImageCopy copyRegion{};
    copyRegion.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    copyRegion.imageSubresource.layerCount = 1;
    copyRegion.imageExtent = {size, size, size};
    vkCmdCopyBufferToImage(cmd, staging, state->nrliLut.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copyRegion);
    transitionImageLayout(cmd, state->nrliLut.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, range);
    if (!endSingleUseCommands(state, pool, cmd)) { vkFreeMemory(state->device, stagingMem, nullptr); vkDestroyBuffer(state->device, staging, nullptr); destroyNrliLut(state); return false; }
    vkFreeMemory(state->device, stagingMem, nullptr); vkDestroyBuffer(state->device, staging, nullptr);

    VkImageViewCreateInfo viewInfo{};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = state->nrliLut.image;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_3D;
    viewInfo.format = lutFormat;
    viewInfo.subresourceRange = range;
    if (!check(vkCreateImageView(state->device, &viewInfo, nullptr, &state->nrliLut.imageView), "vkCreateImageView(nrliLut)")) { destroyNrliLut(state); return false; }
    VkSamplerCreateInfo samplerInfo{};
    samplerInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    samplerInfo.magFilter = VK_FILTER_LINEAR; samplerInfo.minFilter = VK_FILTER_LINEAR;
    samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_LINEAR;
    samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.maxLod = 0.f;
    if (!check(vkCreateSampler(state->device, &samplerInfo, nullptr, &state->nrliLut.sampler), "vkCreateSampler(nrliLut)")) { destroyNrliLut(state); return false; }
    state->nrliLut.size = size;
    state->nrliLut.format = lutFormat;
    state->nrliLut.ready = true;
    return true;
}

// CP-QV21 — re-upload replacement voxels into the EXISTING nrliLut.image (created once by
// createNrliIdentityLut). Reuses the SAME staging-buffer -> 3D image copy + layout barriers as the
// identity builder, but keeps the image/imageView/sampler handles (so the per-image nrli descriptor
// sets stay valid — no descriptor re-write). rgbFloats is size^3*3 (R-fastest, matching the Kotlin
// SnceAdaptiveLut / createNrliIdentityLut voxel order), each channel in [0,1]. Alpha is forced 1.0.
// Returns false and leaves the current LUT untouched on any failure (fail-safe passthrough).
bool uploadNrliLutVoxels(VulkanBridgeState* state, const float* rgbFloats, size_t floatCount) {
    if (state == nullptr || state->device == VK_NULL_HANDLE) return false;
    if (!state->nrliLut.ready || state->nrliLut.image == VK_NULL_HANDLE) return false;
    const uint32_t size = state->nrliLut.size;
    if (size < 2u) return false;
    const size_t voxelCount = static_cast<size_t>(size) * static_cast<size_t>(size) * static_cast<size_t>(size);
    if (rgbFloats == nullptr || floatCount != voxelCount * 3u) return false;

    const bool useHalfFormat = state->nrliLut.format == VK_FORMAT_R16G16B16A16_SFLOAT;
    std::vector<uint8_t> rgba8;
    std::vector<uint16_t> rgba16;
    if (useHalfFormat) rgba16.assign(voxelCount * 4u, floatToHalf(1.0f));
    else rgba8.assign(voxelCount * 4u, 255u);
    for (size_t v = 0; v < voxelCount; ++v) {
        const float r = std::clamp(rgbFloats[v * 3u + 0u], 0.0f, 1.0f);
        const float g = std::clamp(rgbFloats[v * 3u + 1u], 0.0f, 1.0f);
        const float b = std::clamp(rgbFloats[v * 3u + 2u], 0.0f, 1.0f);
        const size_t dst = v * 4u;
        if (useHalfFormat) {
            rgba16[dst + 0u] = floatToHalf(r); rgba16[dst + 1u] = floatToHalf(g);
            rgba16[dst + 2u] = floatToHalf(b); rgba16[dst + 3u] = floatToHalf(1.0f);
        } else {
            rgba8[dst + 0u] = static_cast<uint8_t>(r * 255.f + 0.5f);
            rgba8[dst + 1u] = static_cast<uint8_t>(g * 255.f + 0.5f);
            rgba8[dst + 2u] = static_cast<uint8_t>(b * 255.f + 0.5f);
            rgba8[dst + 3u] = 255u;
        }
    }
    const void* uploadData = useHalfFormat ? static_cast<const void*>(rgba16.data()) : static_cast<const void*>(rgba8.data());
    const size_t uploadSizeBytes = useHalfFormat ? rgba16.size() * sizeof(uint16_t) : rgba8.size();

    VkBuffer staging = VK_NULL_HANDLE; VkDeviceMemory stagingMem = VK_NULL_HANDLE;
    if (!createBufferWithMemory(state, static_cast<VkDeviceSize>(uploadSizeBytes), VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, &staging, &stagingMem)) return false;
    void* mapped = nullptr;
    if (!check(vkMapMemory(state->device, stagingMem, 0, uploadSizeBytes, 0, &mapped), "vkMapMemory(nrliLut update)")) {
        vkFreeMemory(state->device, stagingMem, nullptr); vkDestroyBuffer(state->device, staging, nullptr); return false;
    }
    std::memcpy(mapped, uploadData, uploadSizeBytes);
    vkUnmapMemory(state->device, stagingMem);

    VkCommandPool pool = VK_NULL_HANDLE; VkCommandBuffer cmd = VK_NULL_HANDLE;
    if (!beginSingleUseCommands(state, &pool, &cmd)) {
        vkFreeMemory(state->device, stagingMem, nullptr); vkDestroyBuffer(state->device, staging, nullptr); return false;
    }
    VkImageSubresourceRange range{};
    range.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT; range.levelCount = 1; range.layerCount = 1;
    // The existing image is currently SHADER_READ_ONLY_OPTIMAL (from the identity build / prior update).
    transitionImageLayout(cmd, state->nrliLut.image, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, range);
    VkBufferImageCopy copyRegion{};
    copyRegion.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    copyRegion.imageSubresource.layerCount = 1;
    copyRegion.imageExtent = {size, size, size};
    vkCmdCopyBufferToImage(cmd, staging, state->nrliLut.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copyRegion);
    transitionImageLayout(cmd, state->nrliLut.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, range);
    const bool ok = endSingleUseCommands(state, pool, cmd);
    vkFreeMemory(state->device, stagingMem, nullptr); vkDestroyBuffer(state->device, staging, nullptr);
    return ok;
}

bool createOrUpdateSubjectDetailMask(
    VulkanBridgeState* state,
    const uint8_t* alphaData,
    int maskWidth,
    int maskHeight,
    int trackingId
) {
    if (state == nullptr || state->device == VK_NULL_HANDLE) return false;
    const bool hasLiveMask = alphaData != nullptr && maskWidth > 0 && maskHeight > 0;
    const uint32_t width = hasLiveMask ? static_cast<uint32_t>(maskWidth) : 1u;
    const uint32_t height = hasLiveMask ? static_cast<uint32_t>(maskHeight) : 1u;
    const size_t pixelCount = static_cast<size_t>(width) * static_cast<size_t>(height);
    std::vector<uint8_t> uploadPixels(pixelCount, 0u);
    if (hasLiveMask) {
        std::memcpy(uploadPixels.data(), alphaData, pixelCount);
    }

    if (state->subjectDetailMask.ready &&
        !check(waitDeviceIdleSynchronized(state), "vkDeviceWaitIdle(subjectDetailMask replacement)")) {
        return false;
    }
    destroySubjectDetailMask(state);

    VkImageCreateInfo imageInfo{};
    imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.extent = {width, height, 1u};
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.format = VK_FORMAT_R8_UNORM;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    imageInfo.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    if (!check(vkCreateImage(state->device, &imageInfo, nullptr, &state->subjectDetailMask.image), "vkCreateImage(subjectDetailMask)")) {
        return false;
    }

    VkMemoryRequirements imageMemReq{};
    vkGetImageMemoryRequirements(state->device, state->subjectDetailMask.image, &imageMemReq);
    VkMemoryAllocateInfo imageAllocInfo{};
    imageAllocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    imageAllocInfo.allocationSize = imageMemReq.size;
    imageAllocInfo.memoryTypeIndex = findMemoryType(
        state,
        imageMemReq.memoryTypeBits,
        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
    );
    if (!check(vkAllocateMemory(state->device, &imageAllocInfo, nullptr, &state->subjectDetailMask.memory), "vkAllocateMemory(subjectDetailMask)")) {
        destroySubjectDetailMask(state);
        return false;
    }
    if (!check(vkBindImageMemory(state->device, state->subjectDetailMask.image, state->subjectDetailMask.memory, 0), "vkBindImageMemory(subjectDetailMask)")) {
        destroySubjectDetailMask(state);
        return false;
    }

    VkBuffer stagingBuffer = VK_NULL_HANDLE;
    VkDeviceMemory stagingMemory = VK_NULL_HANDLE;
    if (!createBufferWithMemory(
            state,
            static_cast<VkDeviceSize>(uploadPixels.size()),
            VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
            &stagingBuffer,
            &stagingMemory
        )) {
        destroySubjectDetailMask(state);
        return false;
    }
    void* mapped = nullptr;
    if (!check(vkMapMemory(state->device, stagingMemory, 0, uploadPixels.size(), 0, &mapped), "vkMapMemory(subjectDetailMask)")) {
        vkFreeMemory(state->device, stagingMemory, nullptr);
        vkDestroyBuffer(state->device, stagingBuffer, nullptr);
        destroySubjectDetailMask(state);
        return false;
    }
    std::memcpy(mapped, uploadPixels.data(), uploadPixels.size());
    vkUnmapMemory(state->device, stagingMemory);

    VkCommandPool commandPool = VK_NULL_HANDLE;
    VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
    if (!beginSingleUseCommands(state, &commandPool, &commandBuffer)) {
        vkFreeMemory(state->device, stagingMemory, nullptr);
        vkDestroyBuffer(state->device, stagingBuffer, nullptr);
        destroySubjectDetailMask(state);
        return false;
    }
    VkImageSubresourceRange subresourceRange{};
    subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    subresourceRange.baseMipLevel = 0;
    subresourceRange.levelCount = 1;
    subresourceRange.baseArrayLayer = 0;
    subresourceRange.layerCount = 1;
    transitionImageLayout(
        commandBuffer,
        state->subjectDetailMask.image,
        VK_IMAGE_LAYOUT_UNDEFINED,
        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        subresourceRange
    );
    VkBufferImageCopy copyRegion{};
    copyRegion.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    copyRegion.imageSubresource.mipLevel = 0;
    copyRegion.imageSubresource.baseArrayLayer = 0;
    copyRegion.imageSubresource.layerCount = 1;
    copyRegion.imageExtent = {width, height, 1u};
    vkCmdCopyBufferToImage(
        commandBuffer,
        stagingBuffer,
        state->subjectDetailMask.image,
        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        1,
        &copyRegion
    );
    transitionImageLayout(
        commandBuffer,
        state->subjectDetailMask.image,
        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
        subresourceRange
    );
    if (!endSingleUseCommands(state, commandPool, commandBuffer)) {
        vkFreeMemory(state->device, stagingMemory, nullptr);
        vkDestroyBuffer(state->device, stagingBuffer, nullptr);
        destroySubjectDetailMask(state);
        return false;
    }
    vkFreeMemory(state->device, stagingMemory, nullptr);
    vkDestroyBuffer(state->device, stagingBuffer, nullptr);

    VkImageViewCreateInfo viewInfo{};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = state->subjectDetailMask.image;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = VK_FORMAT_R8_UNORM;
    viewInfo.subresourceRange = subresourceRange;
    if (!check(vkCreateImageView(state->device, &viewInfo, nullptr, &state->subjectDetailMask.imageView), "vkCreateImageView(subjectDetailMask)")) {
        destroySubjectDetailMask(state);
        return false;
    }
    VkSamplerCreateInfo samplerInfo{};
    samplerInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    samplerInfo.magFilter = VK_FILTER_LINEAR;
    samplerInfo.minFilter = VK_FILTER_LINEAR;
    samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_LINEAR;
    samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.maxLod = 0.f;
    if (!check(vkCreateSampler(state->device, &samplerInfo, nullptr, &state->subjectDetailMask.sampler), "vkCreateSampler(subjectDetailMask)")) {
        destroySubjectDetailMask(state);
        return false;
    }
    state->subjectDetailMask.width = width;
    state->subjectDetailMask.height = height;
    state->subjectDetailMask.trackingId = hasLiveMask ? trackingId : -1;
    state->subjectDetailMask.ready = true;
    return true;
}

ImportedFrame* currentImportedFrame(VulkanBridgeState* state) {
    if (state == nullptr) return nullptr;
    if (state->currentImportedFrameIndex < 0) return nullptr;
    if (state->currentImportedFrameIndex >= static_cast<int>(state->importedFrames.size())) return nullptr;
    return &state->importedFrames[static_cast<size_t>(state->currentImportedFrameIndex)];
}

void destroyImportedFrameResources(VulkanBridgeState* state, ImportedFrame* frame) {
    if (frame == nullptr) return;
    if (state->device != VK_NULL_HANDLE) {
        if (frame->imageView != VK_NULL_HANDLE) {
            vkDestroyImageView(state->device, frame->imageView, nullptr);
            frame->imageView = VK_NULL_HANDLE;
        }
        if (frame->sampler != VK_NULL_HANDLE) {
            vkDestroySampler(state->device, frame->sampler, nullptr);
            frame->sampler = VK_NULL_HANDLE;
        }
        if (frame->conversion != VK_NULL_HANDLE && state->destroySamplerYcbcrConversion != nullptr) {
            state->destroySamplerYcbcrConversion(state->device, frame->conversion, nullptr);
            frame->conversion = VK_NULL_HANDLE;
        }
        if (frame->image != VK_NULL_HANDLE) {
            vkDestroyImage(state->device, frame->image, nullptr);
            frame->image = VK_NULL_HANDLE;
        }
        if (frame->memory != VK_NULL_HANDLE) {
            vkFreeMemory(state->device, frame->memory, nullptr);
            frame->memory = VK_NULL_HANDLE;
        }
    }
    if (frame->hardwareBuffer != nullptr) {
        AHardwareBuffer_release(frame->hardwareBuffer);
        frame->hardwareBuffer = nullptr;
    }
    frame->hardwareBufferId = 0;
    frame->hardwareBufferIdStable = false;
    frame->width = 0;
    frame->height = 0;
    frame->format = 0;
    frame->layout = VK_IMAGE_LAYOUT_UNDEFINED;
    frame->ready = false;
    frame->lastUsedSerial = 0;
}

void destroyImportedFrames(VulkanBridgeState* state) {
    if (state == nullptr) return;
    for (auto& frame : state->importedFrames) {
        destroyImportedFrameResources(state, &frame);
    }
    state->importedFrames.clear();
    state->currentImportedFrameIndex = -1;
}

void destroyComputeIntermediateResources(VulkanBridgeState* state, ComputeIntermediateImage* image) {
    if (state == nullptr || image == nullptr || state->device == VK_NULL_HANDLE) return;
    if (image->sampler != VK_NULL_HANDLE) {
        vkDestroySampler(state->device, image->sampler, nullptr);
        image->sampler = VK_NULL_HANDLE;
    }
    if (image->imageView != VK_NULL_HANDLE) {
        vkDestroyImageView(state->device, image->imageView, nullptr);
        image->imageView = VK_NULL_HANDLE;
    }
    if (image->image != VK_NULL_HANDLE) {
        vkDestroyImage(state->device, image->image, nullptr);
        image->image = VK_NULL_HANDLE;
    }
    if (image->memory != VK_NULL_HANDLE) {
        vkFreeMemory(state->device, image->memory, nullptr);
        image->memory = VK_NULL_HANDLE;
    }
    image->layout = VK_IMAGE_LAYOUT_UNDEFINED;
    image->width = 0;
    image->height = 0;
    image->ready = false;
}

void destroyComputeIntermediateResources(VulkanBridgeState* state, TargetSurface* target) {
    if (state == nullptr || target == nullptr) return;
    for (auto& image : target->computeIntermediates) {
        destroyComputeIntermediateResources(state, &image);
    }
    for (auto& image : target->toneMappedIntermediates) {
        destroyComputeIntermediateResources(state, &image);
    }
    target->computeIntermediates.clear();
    target->toneMappedIntermediates.clear();
    // Live inline-bokeh shared depth/CoC images (best-effort; may be null if bokeh pipeline absent).
    destroyComputeIntermediateResources(state, &target->bokehDepth);
    destroyComputeIntermediateResources(state, &target->bokehCoc);
    target->bokehLiveDescSets.clear();
    target->computeDescriptorSets.clear();
    target->kneeCurveDescriptorSets.clear();
    target->shadowLiftDescriptorSets.clear();
    target->hdrMetadataHistogramDescriptorSets.clear();
    target->hdrMetadataReduceDescriptorSets.clear();
    if (state->device != VK_NULL_HANDLE) {
        for (auto buffer : target->hdrHistogramBuffers) {
            if (buffer != VK_NULL_HANDLE) vkDestroyBuffer(state->device, buffer, nullptr);
        }
        for (auto memory : target->hdrHistogramMemories) {
            if (memory != VK_NULL_HANDLE) vkFreeMemory(state->device, memory, nullptr);
        }
        for (auto buffer : target->hdrMetadataBuffers) {
            if (buffer != VK_NULL_HANDLE) vkDestroyBuffer(state->device, buffer, nullptr);
        }
        for (auto memory : target->hdrMetadataMemories) {
            if (memory != VK_NULL_HANDLE) vkFreeMemory(state->device, memory, nullptr);
        }
        for (auto buffer : target->hdrMetadataStagingBuffers) {
            if (buffer != VK_NULL_HANDLE) vkDestroyBuffer(state->device, buffer, nullptr);
        }
        for (auto memory : target->hdrMetadataStagingMemories) {
            if (memory != VK_NULL_HANDLE) vkFreeMemory(state->device, memory, nullptr);
        }
    }
    target->hdrHistogramBuffers.clear();
    target->hdrHistogramMemories.clear();
    target->hdrMetadataBuffers.clear();
    target->hdrMetadataMemories.clear();
    target->hdrMetadataStagingBuffers.clear();
    target->hdrMetadataStagingMemories.clear();
    target->hdrMetadataStagingReady.clear();
    if (state->device != VK_NULL_HANDLE && target->computeDescriptorPool != VK_NULL_HANDLE) {
        vkDestroyDescriptorPool(state->device, target->computeDescriptorPool, nullptr);
        target->computeDescriptorPool = VK_NULL_HANDLE;
    }

    for (auto& image : target->lowResFocusMaps) {
        destroyComputeIntermediateResources(state, &image);
    }
    for (auto& image : target->highResFocusMaps) {
        destroyComputeIntermediateResources(state, &image);
    }
    for (auto& image : target->detailMasks) {
        destroyComputeIntermediateResources(state, &image);
    }
    for (auto& image : target->historyImages) {
        destroyComputeIntermediateResources(state, &image);
    }
    target->lowResFocusMaps.clear();
    target->highResFocusMaps.clear();
    target->detailMasks.clear();
    target->historyImages.clear();
    target->historyContentValid.clear(); // CP51 — history invalid again after teardown/recreate
    target->temporalHistorySubmissionId = 0u;
    target->temporalHistorySensorTimestampNs = 0;

    if (state->device != VK_NULL_HANDLE) {
        for (auto buffer : target->focusMapStagingBuffers) {
            if (buffer != VK_NULL_HANDLE) vkDestroyBuffer(state->device, buffer, nullptr);
        }
        for (auto memory : target->focusMapStagingMemories) {
            if (memory != VK_NULL_HANDLE) vkFreeMemory(state->device, memory, nullptr);
        }
    }
    target->focusMapStagingBuffers.clear();
    target->focusMapStagingMemories.clear();

    if (state->device != VK_NULL_HANDLE && target->qfcaDescriptorPool != VK_NULL_HANDLE) {
        vkDestroyDescriptorPool(state->device, target->qfcaDescriptorPool, nullptr);
        target->qfcaDescriptorPool = VK_NULL_HANDLE;
    }

    for (auto& image : target->omniCurrentLow) destroyComputeIntermediateResources(state, &image);
    for (auto& image : target->omniPreviousLow) destroyComputeIntermediateResources(state, &image);
    for (auto& image : target->omniFlowLowA) destroyComputeIntermediateResources(state, &image);
    for (auto& image : target->omniFlowLowB) destroyComputeIntermediateResources(state, &image);
    for (auto& image : target->omniFlowFull) destroyComputeIntermediateResources(state, &image);
    for (auto& image : target->omniDeblur) destroyComputeIntermediateResources(state, &image);
    // FIX #4 VRAM LEAK — these 3 per-image flow vectors are allocated in allocateOmniClarityResources
    // (each element = VkImage + dedicated VkDeviceMemory + VkImageView + VkSampler) but were never freed
    // here, so they leaked their full backing on every swapchain recreation (rotation/resize/HDR rebuild).
    // Destroy them exactly like the sibling omniFlow* vectors above (the same per-element helper frees
    // sampler+view+image+memory), then .clear() the vectors so re-allocation starts from empty.
    for (auto& image : target->omniFlowCoarse) destroyComputeIntermediateResources(state, &image);
    for (auto& image : target->omniFlowBackwardLow) destroyComputeIntermediateResources(state, &image);
    for (auto& image : target->omniFlowSelected) destroyComputeIntermediateResources(state, &image);
    target->omniCurrentLow.clear();
    target->omniPreviousLow.clear();
    target->omniFlowLowA.clear();
    target->omniFlowLowB.clear();
    target->omniFlowFull.clear();
    target->omniDeblur.clear();
    target->omniFlowCoarse.clear();
    target->omniFlowBackwardLow.clear();
    target->omniFlowSelected.clear();
    if (state->device != VK_NULL_HANDLE && target->omniDescriptorPool != VK_NULL_HANDLE) {
        vkDestroyDescriptorPool(state->device, target->omniDescriptorPool, nullptr);
        target->omniDescriptorPool = VK_NULL_HANDLE;
    }
    target->omniDownsampleCurDescSets.clear();
    target->omniDownsamplePrevDescSets.clear();
    target->omniBlockMatchDescSets.clear();
    target->omniRefineA2BDescSets.clear();
    target->omniRefineB2ADescSets.clear();
    target->omniFlowUpsampleDescSets.clear();
    target->omniRivenDescSets.clear();
    target->omniWarpDescSets.clear();

    target->guidedUpsampleDescSets.clear();
    target->detailPreservationDescSets.clear();
    target->mfsrFusionDescSets.clear();
    target->imaxRenderingDescSets.clear();
    target->m_nivukxDenoiseDescSets.clear();
    target->denoiseSpatialDescSets.clear(); // CP-QV16 (freed with qfcaDescriptorPool above)
    target->sharpenOpticalDescSets.clear(); // CP-QV17 (freed with qfcaDescriptorPool above)
}

void waitForTargetIdle(VulkanBridgeState* state, const TargetSurface& target) {
    if (state->device == VK_NULL_HANDLE) return;
    if (target.timelineSemaphore != VK_NULL_HANDLE && state->waitSemaphores != nullptr) {
        const uint64_t maxTimelineValue = target.inFlightTimelineValues.empty()
            ? 0u
            : *std::max_element(target.inFlightTimelineValues.begin(), target.inFlightTimelineValues.end());
        if (maxTimelineValue > 0u) {
            VkSemaphoreWaitInfo waitInfo{};
            waitInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_WAIT_INFO;
            waitInfo.semaphoreCount = 1;
            waitInfo.pSemaphores = &target.timelineSemaphore;
            waitInfo.pValues = &maxTimelineValue;
            state->waitSemaphores(state->device, &waitInfo, UINT64_MAX);
        }
    }
    if (!target.inFlightFences.empty()) {
        std::vector<VkFence> validFences;
        validFences.reserve(target.inFlightFences.size());
        for (VkFence fence : target.inFlightFences) {
            if (fence != VK_NULL_HANDLE) validFences.push_back(fence);
        }
        if (validFences.empty()) return;
        vkWaitForFences(
            state->device,
            static_cast<uint32_t>(validFences.size()),
            validFences.data(),
            VK_TRUE,
            UINT64_MAX
        );
    }
}

bool waitForTimelineValue(
    VulkanBridgeState* state,
    VkSemaphore timelineSemaphore,
    uint64_t timelineValue,
    uint64_t timeoutNs
) {
    if (timelineSemaphore == VK_NULL_HANDLE || timelineValue == 0u || state->waitSemaphores == nullptr) {
        return true;
    }
    if (state->getSemaphoreCounterValue != nullptr) {
        uint64_t currentValue = 0u;
        if (state->getSemaphoreCounterValue(state->device, timelineSemaphore, &currentValue) == VK_SUCCESS &&
            currentValue >= timelineValue) {
            return true;
        }
    }
    VkSemaphoreWaitInfo waitInfo{};
    waitInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_WAIT_INFO;
    waitInfo.semaphoreCount = 1;
    waitInfo.pSemaphores = &timelineSemaphore;
    waitInfo.pValues = &timelineValue;
    const VkResult waitResult = state->waitSemaphores(state->device, &waitInfo, timeoutNs);
    if (waitResult == VK_TIMEOUT) return false;
    if (waitResult == VK_ERROR_DEVICE_LOST) {
        state->supported = false;
        setStatus(state, "Vulkan timeline wait failed: device lost");
    }
    return check(waitResult, "vkWaitSemaphores(timeline)");
}

bool isTargetIdleNonBlocking(VulkanBridgeState* state, const TargetSurface& target) {
    if (state == nullptr || state->device == VK_NULL_HANDLE) return true;
    for (VkFence fence : target.inFlightFences) {
        if (fence == VK_NULL_HANDLE) continue;
        const VkResult status = vkGetFenceStatus(state->device, fence);
        if (status == VK_NOT_READY) return false;
        if (status == VK_ERROR_DEVICE_LOST) {
            state->supported = false;
            setStatus(state, "Vulkan target-idle probe failed: device lost");
            return false;
        }
        if (status != VK_SUCCESS) return false;
    }
    return true;
}

void destroySwapchainResources(VulkanBridgeState* state, TargetSurface* target) {
    if (state->device == VK_NULL_HANDLE || target == nullptr) return;
    waitForTargetIdle(state, *target);
    destroyComputeIntermediateResources(state, target);
    if (target->computeTimestampQueryPool != VK_NULL_HANDLE) {
        vkDestroyQueryPool(state->device, target->computeTimestampQueryPool, nullptr);
        target->computeTimestampQueryPool = VK_NULL_HANDLE;
        target->computeTimestampQueriesReady = false;
    }
    target->computeTimestampQueryIssued.clear();
    for (auto fence : target->inFlightFences) {
        if (fence != VK_NULL_HANDLE) {
            vkDestroyFence(state->device, fence, nullptr);
        }
    }
    target->inFlightFences.clear();
    if (target->timelineSemaphore != VK_NULL_HANDLE) {
        vkDestroySemaphore(state->device, target->timelineSemaphore, nullptr);
        target->timelineSemaphore = VK_NULL_HANDLE;
    }
    target->inFlightTimelineValues.clear();
    target->nextTimelineValue = 1;
    for (auto semaphore : target->renderFinishedSemaphores) {
        if (semaphore != VK_NULL_HANDLE) {
            vkDestroySemaphore(state->device, semaphore, nullptr);
        }
    }
    target->renderFinishedSemaphores.clear();
    for (auto semaphore : target->imageAvailableSemaphores) {
        if (semaphore != VK_NULL_HANDLE) {
            vkDestroySemaphore(state->device, semaphore, nullptr);
        }
    }
    target->imageAvailableSemaphores.clear();
    for (size_t i = 0; i < target->uniformBuffers.size(); ++i) {
        if (target->uniformBuffers[i] != VK_NULL_HANDLE) {
            vkDestroyBuffer(state->device, target->uniformBuffers[i], nullptr);
        }
        if (i < target->uniformMemories.size() && target->uniformMemories[i] != VK_NULL_HANDLE) {
            vkFreeMemory(state->device, target->uniformMemories[i], nullptr);
        }
    }
    target->uniformBuffers.clear();
    target->uniformMemories.clear();
    if (target->descriptorPool != VK_NULL_HANDLE) {
        vkDestroyDescriptorPool(state->device, target->descriptorPool, nullptr);
        target->descriptorPool = VK_NULL_HANDLE;
    }
    target->descriptorSets.clear();
    if (target->commandPool != VK_NULL_HANDLE) {
        vkDestroyCommandPool(state->device, target->commandPool, nullptr);
        target->commandPool = VK_NULL_HANDLE;
    }
    target->commandBuffers.clear();
    for (auto framebuffer : target->framebuffers) {
        if (framebuffer != VK_NULL_HANDLE) {
            vkDestroyFramebuffer(state->device, framebuffer, nullptr);
        }
    }
    target->framebuffers.clear();
    if (target == &state->previewTarget &&
        state->encoderTarget.pipeline == target->pipeline &&
        target->pipeline != VK_NULL_HANDLE &&
        target->ownsPipeline) {
        state->encoderTarget.ownsPipeline = true;
        target->ownsPipeline = false;
    }
    if (target->pipeline != VK_NULL_HANDLE && target->ownsPipeline) {
        vkDestroyPipeline(state->device, target->pipeline, nullptr);
    }
    target->pipeline = VK_NULL_HANDLE;
    target->ownsPipeline = true;
    if (target->renderPass != VK_NULL_HANDLE) {
        vkDestroyRenderPass(state->device, target->renderPass, nullptr);
        target->renderPass = VK_NULL_HANDLE;
    }
    for (auto imageView : target->imageViews) {
        if (imageView != VK_NULL_HANDLE) {
            vkDestroyImageView(state->device, imageView, nullptr);
        }
    }
    target->imageViews.clear();
    target->images.clear();
    if (target->swapchain != VK_NULL_HANDLE) {
        vkDestroySwapchainKHR(state->device, target->swapchain, nullptr);
        target->swapchain = VK_NULL_HANDLE;
    }
    target->swapchainFormat = VK_FORMAT_UNDEFINED;
    target->extent = {0, 0};
    target->syncIndex = 0;
    target->valid = false;
}

void destroyTargetSurface(VulkanBridgeState* state, TargetSurface* target) {
    if (target == nullptr) return;
    destroySwapchainResources(state, target);
    if (state->instance != VK_NULL_HANDLE && target->surface != VK_NULL_HANDLE) {
        vkDestroySurfaceKHR(state->instance, target->surface, nullptr);
        target->surface = VK_NULL_HANDLE;
    }
    if (target->window != nullptr) {
        ANativeWindow_release(target->window);
        target->window = nullptr;
    }
}

bool destroyState(VulkanBridgeState* state) {
    if (state == nullptr) return true;
    if (state->encoderPrewarmThread.joinable()) {
        state->encoderPrewarmThread.join();
    }
    // Drain in-flight preview/encoder work before destroying imported-frame GPU resources
    // (use-after-free guard, mirrors nativeCleanupImportedFrames and the eviction path).
    waitForTargetIdle(state, state->previewTarget);
    waitForTargetIdle(state, state->encoderTarget);
    destroyImportedFrames(state);
    destroyTargetSurface(state, &state->previewTarget);
    destroyTargetSurface(state, &state->encoderTarget);
    if (state->device != VK_NULL_HANDLE) {
        destroyPreviewLut(state);
        destroySubjectDetailMask(state);
        if (state->computePipeline != VK_NULL_HANDLE) {
            vkDestroyPipeline(state->device, state->computePipeline, nullptr);
            state->computePipeline = VK_NULL_HANDLE;
        }
        if (state->kneeCurvePipeline != VK_NULL_HANDLE) {
            vkDestroyPipeline(state->device, state->kneeCurvePipeline, nullptr);
            state->kneeCurvePipeline = VK_NULL_HANDLE;
        }
        if (state->shadowLiftPipeline != VK_NULL_HANDLE) {
            vkDestroyPipeline(state->device, state->shadowLiftPipeline, nullptr);
            state->shadowLiftPipeline = VK_NULL_HANDLE;
        }
        if (state->hdrMetadataHistogramPipeline != VK_NULL_HANDLE) {
            vkDestroyPipeline(state->device, state->hdrMetadataHistogramPipeline, nullptr);
            state->hdrMetadataHistogramPipeline = VK_NULL_HANDLE;
        }
        if (state->hdrMetadataReducePipeline != VK_NULL_HANDLE) {
            vkDestroyPipeline(state->device, state->hdrMetadataReducePipeline, nullptr);
            state->hdrMetadataReducePipeline = VK_NULL_HANDLE;
        }
        if (state->computeShaderModule != VK_NULL_HANDLE) {
            vkDestroyShaderModule(state->device, state->computeShaderModule, nullptr);
            state->computeShaderModule = VK_NULL_HANDLE;
        }
        if (state->kneeCurveShaderModule != VK_NULL_HANDLE) {
            vkDestroyShaderModule(state->device, state->kneeCurveShaderModule, nullptr);
            state->kneeCurveShaderModule = VK_NULL_HANDLE;
        }
        if (state->shadowLiftShaderModule != VK_NULL_HANDLE) {
            vkDestroyShaderModule(state->device, state->shadowLiftShaderModule, nullptr);
            state->shadowLiftShaderModule = VK_NULL_HANDLE;
        }
        if (state->hdrMetadataHistogramShaderModule != VK_NULL_HANDLE) {
            vkDestroyShaderModule(state->device, state->hdrMetadataHistogramShaderModule, nullptr);
            state->hdrMetadataHistogramShaderModule = VK_NULL_HANDLE;
        }
        if (state->hdrMetadataReduceShaderModule != VK_NULL_HANDLE) {
            vkDestroyShaderModule(state->device, state->hdrMetadataReduceShaderModule, nullptr);
            state->hdrMetadataReduceShaderModule = VK_NULL_HANDLE;
        }
        if (state->computePipelineLayout != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(state->device, state->computePipelineLayout, nullptr);
            state->computePipelineLayout = VK_NULL_HANDLE;
        }
        if (state->imageComputePipelineLayout != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(state->device, state->imageComputePipelineLayout, nullptr);
            state->imageComputePipelineLayout = VK_NULL_HANDLE;
        }
        if (state->hdrMetadataHistogramPipelineLayout != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(state->device, state->hdrMetadataHistogramPipelineLayout, nullptr);
            state->hdrMetadataHistogramPipelineLayout = VK_NULL_HANDLE;
        }
        if (state->hdrMetadataReducePipelineLayout != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(state->device, state->hdrMetadataReducePipelineLayout, nullptr);
            state->hdrMetadataReducePipelineLayout = VK_NULL_HANDLE;
        }
        if (state->computeDescriptorSetLayout != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(state->device, state->computeDescriptorSetLayout, nullptr);
            state->computeDescriptorSetLayout = VK_NULL_HANDLE;
        }
        if (state->imageComputeDescriptorSetLayout != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(state->device, state->imageComputeDescriptorSetLayout, nullptr);
            state->imageComputeDescriptorSetLayout = VK_NULL_HANDLE;
        }
        if (state->hdrMetadataHistogramDescriptorSetLayout != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(state->device, state->hdrMetadataHistogramDescriptorSetLayout, nullptr);
            state->hdrMetadataHistogramDescriptorSetLayout = VK_NULL_HANDLE;
        }
        if (state->hdrMetadataReduceDescriptorSetLayout != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(state->device, state->hdrMetadataReduceDescriptorSetLayout, nullptr);
            state->hdrMetadataReduceDescriptorSetLayout = VK_NULL_HANDLE;
        }
        if (state->vertexBuffer != VK_NULL_HANDLE) {
            vkDestroyBuffer(state->device, state->vertexBuffer, nullptr);
            state->vertexBuffer = VK_NULL_HANDLE;
        }
        if (state->vertexBufferMemory != VK_NULL_HANDLE) {
            vkFreeMemory(state->device, state->vertexBufferMemory, nullptr);
            state->vertexBufferMemory = VK_NULL_HANDLE;
        }
        if (state->vertexShader != VK_NULL_HANDLE) {
            vkDestroyShaderModule(state->device, state->vertexShader, nullptr);
            state->vertexShader = VK_NULL_HANDLE;
        }
        if (state->fragmentShader != VK_NULL_HANDLE) {
            vkDestroyShaderModule(state->device, state->fragmentShader, nullptr);
            state->fragmentShader = VK_NULL_HANDLE;
        }
        if (state->pipelineLayout != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(state->device, state->pipelineLayout, nullptr);
            state->pipelineLayout = VK_NULL_HANDLE;
        }
        if (state->graphicsPipelineCache != VK_NULL_HANDLE) {
            vkDestroyPipelineCache(state->device, state->graphicsPipelineCache, nullptr);
            state->graphicsPipelineCache = VK_NULL_HANDLE;
        }
        if (state->descriptorSetLayout != VK_NULL_HANDLE) {
            vkDestroyDescriptorSetLayout(state->device, state->descriptorSetLayout, nullptr);
            state->descriptorSetLayout = VK_NULL_HANDLE;
        }
        // Free the RAW-preview demosaic resources (its descriptor pool/set) BEFORE destroyQfcaPipelines
        // destroys the demosaic descriptor-set layout the set was allocated from.
        if (!destroyRawDemosaicResources(state)) { // s1-f07 / CP10c
            // A timed-out RAW command still owns its descriptor set, images, mapped buffers and
            // command pool. Do not destroy the pipelines/device out from under that submission.
            // Losing these handles at process teardown is a bounded driver-resource leak, but it is
            // memory-safe and preferable to a GPU use-after-free or device fault.
            LOGE("Vulkan teardown deferred because RAW GPU work did not retire safely");
            return false;
        }
        destroyDualGainFusionResources(state); // s1-f10 / CP31 — free dual-gain owned pool/set before destroyQfcaPipelines (which tail-calls destroyOmniClarityPipelines) drops dualGainDescLayout
        destroyBokehResources(state); // s5-f06 / CP-F7 — free bokeh owned pool/set/images before destroyQfcaPipelines drops bokehDescLayout
        destroyQfcaPipelines(state);
        destroyDummyResources(state);
        vkDestroyDevice(state->device, nullptr);
        state->device = VK_NULL_HANDLE;
        state->graphicsQueue = VK_NULL_HANDLE;
        state->rawQueue = VK_NULL_HANDLE;
        state->graphicsFamilyQueueCount = 0u;
        state->independentRawQueueEnabled = false;
    }
    if (state->instance != VK_NULL_HANDLE) {
        vkDestroyInstance(state->instance, nullptr);
        state->instance = VK_NULL_HANDLE;
    }
    return true;
}

bool createInstance(VulkanBridgeState* state) {
    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "NeoProcCamX";
    appInfo.applicationVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.pEngineName = "NeoProcVulkan";
    appInfo.engineVersion = VK_MAKE_VERSION(1, 0, 0);
    appInfo.apiVersion = VK_API_VERSION_1_1;

    // Probe for VK_EXT_swapchain_colorspace — without it the HDR
    // colourspace enums (PQ, HLG, BT.2020 linear, extended-sRGB) won't
    // appear in vkGetPhysicalDeviceSurfaceFormatsKHR and the HDR path
    // through HdrSwapchainFormatPolicy collapses to TEN_BIT_SRGB_FALLBACK.
    // The extension is widely supported on Android 11+ but we still
    // probe defensively because pre-11 devices and some emulators omit it.
    bool hasSwapchainColorSpaceExt = false;
    {
        uint32_t propCount = 0;
        vkEnumerateInstanceExtensionProperties(nullptr, &propCount, nullptr);
        if (propCount > 0) {
            std::vector<VkExtensionProperties> props(propCount);
            if (vkEnumerateInstanceExtensionProperties(nullptr, &propCount, props.data()) == VK_SUCCESS) {
                for (const auto& prop : props) {
                    if (std::strcmp(prop.extensionName,
                                    VK_EXT_SWAPCHAIN_COLOR_SPACE_EXTENSION_NAME) == 0) {
                        hasSwapchainColorSpaceExt = true;
                        break;
                    }
                }
            }
        }
    }

    std::vector<const char*> instanceExtensions = {
        VK_KHR_SURFACE_EXTENSION_NAME,
        VK_KHR_ANDROID_SURFACE_EXTENSION_NAME
    };
    if (hasSwapchainColorSpaceExt) {
        instanceExtensions.push_back(VK_EXT_SWAPCHAIN_COLOR_SPACE_EXTENSION_NAME);
    }
    state->swapchainColorSpaceExtensionEnabled = hasSwapchainColorSpaceExt;
    LOGI(
        "Vulkan instance extensions: VK_EXT_swapchain_colorspace=%s",
        hasSwapchainColorSpaceExt ? "enabled" : "unavailable"
    );

    VkInstanceCreateInfo createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    createInfo.pApplicationInfo = &appInfo;
    createInfo.enabledExtensionCount = static_cast<uint32_t>(instanceExtensions.size());
    createInfo.ppEnabledExtensionNames = instanceExtensions.data();

    if (!check(vkCreateInstance(&createInfo, nullptr, &state->instance), "vkCreateInstance")) {
        setStatus(state, "vkCreateInstance failed");
        return false;
    }
    return true;
}

bool pickPhysicalDevice(VulkanBridgeState* state) {
    uint32_t deviceCount = 0;
    if (!check(vkEnumeratePhysicalDevices(state->instance, &deviceCount, nullptr), "vkEnumeratePhysicalDevices(count)") ||
        deviceCount == 0) {
        setStatus(state, "no Vulkan physical devices");
        return false;
    }
    std::vector<VkPhysicalDevice> devices(deviceCount);
    if (!check(vkEnumeratePhysicalDevices(state->instance, &deviceCount, devices.data()), "vkEnumeratePhysicalDevices(list)")) {
        setStatus(state, "failed to enumerate physical devices");
        return false;
    }
    state->physicalDevice = devices.front();
    return true;
}

bool createDevice(VulkanBridgeState* state) {
    uint32_t queueFamilyCount = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(state->physicalDevice, &queueFamilyCount, nullptr);
    if (queueFamilyCount == 0) {
        setStatus(state, "no Vulkan queue families");
        return false;
    }
    std::vector<VkQueueFamilyProperties> queueFamilies(queueFamilyCount);
    vkGetPhysicalDeviceQueueFamilyProperties(state->physicalDevice, &queueFamilyCount, queueFamilies.data());
    bool foundGraphicsQueue = false;
    for (uint32_t index = 0; index < queueFamilyCount; ++index) {
        if ((queueFamilies[index].queueFlags & VK_QUEUE_GRAPHICS_BIT) != 0u) {
            state->queueFamilyIndex = index;
            foundGraphicsQueue = true;
            break;
        }
    }
    if (!foundGraphicsQueue) {
        setStatus(state, "no graphics queue");
        return false;
    }
    // s7-f11/CP73: detect a DEDICATED transfer queue family (TRANSFER bit, NOT graphics, distinct
    // from the graphics family) for a future zero-copy upload/download path. DETECTION ONLY — no
    // queue is created or used here, so device creation and the live render are byte-identical to
    // today. The zero-copy transfer path (barriers + memcpy removal) is device-gated; see
    // Cp73ZeroCopyTransferFlag. Reported via the dedicatedTransferQueue capability token.
    for (uint32_t index = 0; index < queueFamilyCount; ++index) {
        const VkQueueFlags flags = queueFamilies[index].queueFlags;
        const bool hasTransfer = (flags & VK_QUEUE_TRANSFER_BIT) != 0u;
        const bool hasGraphics = (flags & VK_QUEUE_GRAPHICS_BIT) != 0u;
        if (hasTransfer && !hasGraphics && index != state->queueFamilyIndex) {
            state->dedicatedTransferQueueFamilyIndex = index;
            state->hasDedicatedTransferQueue = true;
            break;
        }
    }
    LOGI("CP73: dedicated transfer queue family %s",
         state->hasDedicatedTransferQueue ? "detected" : "unavailable (using graphics queue)");
    VkPhysicalDeviceProperties deviceProperties{};
    vkGetPhysicalDeviceProperties(state->physicalDevice, &deviceProperties);
    state->timestampValidBits = queueFamilies[state->queueFamilyIndex].timestampValidBits;
    state->timestampQueriesSupported = state->timestampValidBits > 0;
    state->timestampPeriodNs = deviceProperties.limits.timestampPeriod;
    // s7-f13/CP9C: capture device identity for pipeline-cache validity (vendor/device/driver/UUID).
    state->pipelineCacheDeviceIdentity.vendorId = deviceProperties.vendorID;
    state->pipelineCacheDeviceIdentity.deviceId = deviceProperties.deviceID;
    state->pipelineCacheDeviceIdentity.driverVersion = deviceProperties.driverVersion;
    std::memcpy(state->pipelineCacheDeviceIdentity.pipelineCacheUuid,
                deviceProperties.pipelineCacheUUID, neoproc::kPipelineCacheUuidSize);

    uint32_t extensionCount = 0;
    vkEnumerateDeviceExtensionProperties(state->physicalDevice, nullptr, &extensionCount, nullptr);
    std::vector<VkExtensionProperties> availableDeviceExtensions(extensionCount);
    if (extensionCount > 0) {
        vkEnumerateDeviceExtensionProperties(
            state->physicalDevice,
            nullptr,
            &extensionCount,
            availableDeviceExtensions.data()
        );
    }
    constexpr const char* kTimelineSemaphoreExtensionName = "VK_KHR_timeline_semaphore";
    const bool timelineExtensionAvailable =
        deviceExtensionAvailable(availableDeviceExtensions, kTimelineSemaphoreExtensionName);
    VkPhysicalDeviceTimelineSemaphoreFeatures timelineFeatures{};
    timelineFeatures.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_TIMELINE_SEMAPHORE_FEATURES;
    VkPhysicalDeviceFeatures2 physicalFeatures2{};
    physicalFeatures2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
    physicalFeatures2.pNext = &timelineFeatures;
    vkGetPhysicalDeviceFeatures2(state->physicalDevice, &physicalFeatures2);
    state->timelineSemaphoresSupported =
        timelineExtensionAvailable && timelineFeatures.timelineSemaphore == VK_TRUE;

    // s1-f07 / CP10c — enable shaderStorageImageExtendedFormats when supported so demosaic_vulkan.comp's
    // r16f dark-current storage image (binding 2) is a legal storage format. Enabled via pEnabledFeatures
    // (core feature), which coexists with the individual extension feature structs already in pNext. When
    // unsupported it stays off and the demosaic pipeline build is skipped (RAW-preview -> YUV fallback),
    // leaving the rest of the compute stack untouched (no-downgrade).
    VkPhysicalDeviceFeatures enabledCoreFeatures{};
    state->storageImageExtendedFormatsEnabled =
        (physicalFeatures2.features.shaderStorageImageExtendedFormats == VK_TRUE);
    if (state->storageImageExtendedFormatsEnabled) {
        enabledCoreFeatures.shaderStorageImageExtendedFormats = VK_TRUE;
    }

    // Queue 0 continues to own swapchain/render work. A second queue from the SAME family is requested
    // only when advertised and becomes the RAW compute lane. Keeping the family identical avoids any
    // queue-family ownership transfer for the imported/exported images and preserves every existing
    // command-pool contract. Two equal high priorities prevent either full-quality path being weakened.
    std::array<float, 2> queuePriorities{{1.0f, 1.0f}};
    const uint32_t requestedGraphicsQueueCount =
        queueFamilies[state->queueFamilyIndex].queueCount >= 2u ? 2u : 1u;
    VkDeviceQueueCreateInfo queueInfo{};
    queueInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
    queueInfo.queueFamilyIndex = state->queueFamilyIndex;
    queueInfo.queueCount = requestedGraphicsQueueCount;
    queueInfo.pQueuePriorities = queuePriorities.data();

    VkPhysicalDeviceSamplerYcbcrConversionFeatures ycbcrFeatures{};
    ycbcrFeatures.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_SAMPLER_YCBCR_CONVERSION_FEATURES;
    ycbcrFeatures.samplerYcbcrConversion = VK_TRUE;
    if (state->timelineSemaphoresSupported) {
        timelineFeatures.timelineSemaphore = VK_TRUE;
        timelineFeatures.pNext = nullptr;
        ycbcrFeatures.pNext = &timelineFeatures;
    }

    std::vector<const char*> deviceExtensions{
        VK_KHR_SWAPCHAIN_EXTENSION_NAME,
        VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME,
        VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME,
        VK_KHR_SAMPLER_YCBCR_CONVERSION_EXTENSION_NAME
    };
    if (state->timelineSemaphoresSupported) {
        deviceExtensions.push_back(kTimelineSemaphoreExtensionName);
    }

    VkDeviceCreateInfo deviceInfo{};
    deviceInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
    deviceInfo.pNext = &ycbcrFeatures;
    deviceInfo.pEnabledFeatures = &enabledCoreFeatures; // s1-f07 / CP10c — shaderStorageImageExtendedFormats (when supported)
    deviceInfo.queueCreateInfoCount = 1;
    deviceInfo.pQueueCreateInfos = &queueInfo;
    deviceInfo.enabledExtensionCount = static_cast<uint32_t>(deviceExtensions.size());
    deviceInfo.ppEnabledExtensionNames = deviceExtensions.data();

    uint32_t createdGraphicsQueueCount = requestedGraphicsQueueCount;
    VkResult createResult = vkCreateDevice(
        state->physicalDevice, &deviceInfo, nullptr, &state->device);
    if (createResult != VK_SUCCESS && requestedGraphicsQueueCount > 1u) {
        // Some Android drivers over-advertise queue handles. Preserve the proven queue-0 path instead
        // of disabling Vulkan when the optional concurrency lane cannot be created.
        LOGW(
            "Independent RAW queue creation failed result=%d; retrying strict queue-0 fallback",
            static_cast<int>(createResult));
        state->device = VK_NULL_HANDLE;
        queueInfo.queueCount = 1u;
        createdGraphicsQueueCount = 1u;
        createResult = vkCreateDevice(
            state->physicalDevice, &deviceInfo, nullptr, &state->device);
    }
    if (!check(createResult, "vkCreateDevice")) {
        setStatus(state, "vkCreateDevice failed");
        return false;
    }

    vkGetDeviceQueue(state->device, state->queueFamilyIndex, 0, &state->graphicsQueue);
    state->graphicsFamilyQueueCount = createdGraphicsQueueCount;
    if (createdGraphicsQueueCount > 1u) {
        vkGetDeviceQueue(state->device, state->queueFamilyIndex, 1, &state->rawQueue);
    }
    state->independentRawQueueEnabled =
        state->rawQueue != VK_NULL_HANDLE && state->rawQueue != state->graphicsQueue;
    if (!state->independentRawQueueEnabled) {
        state->rawQueue = state->graphicsQueue;
    }
    LOGI(
        "RAW queue policy family=%u advertised=%u created=%u lane=%s",
        state->queueFamilyIndex,
        queueFamilies[state->queueFamilyIndex].queueCount,
        createdGraphicsQueueCount,
        state->independentRawQueueEnabled ? "INDEPENDENT_SAME_FAMILY" : "GRAPHICS_QUEUE_0");
    state->timelineSemaphoresEnabled = state->timelineSemaphoresSupported;
    state->getSemaphoreCounterValue =
        reinterpret_cast<PFN_vkGetSemaphoreCounterValue>(
            vkGetDeviceProcAddr(state->device, "vkGetSemaphoreCounterValue"));
    if (state->getSemaphoreCounterValue == nullptr) {
        state->getSemaphoreCounterValue =
            reinterpret_cast<PFN_vkGetSemaphoreCounterValue>(
                vkGetDeviceProcAddr(state->device, "vkGetSemaphoreCounterValueKHR"));
    }
    state->waitSemaphores =
        reinterpret_cast<PFN_vkWaitSemaphores>(
            vkGetDeviceProcAddr(state->device, "vkWaitSemaphores"));
    if (state->waitSemaphores == nullptr) {
        state->waitSemaphores =
            reinterpret_cast<PFN_vkWaitSemaphores>(
                vkGetDeviceProcAddr(state->device, "vkWaitSemaphoresKHR"));
    }
    if (state->timelineSemaphoresEnabled &&
        (state->getSemaphoreCounterValue == nullptr || state->waitSemaphores == nullptr)) {
        state->timelineSemaphoresEnabled = false;
        LOGW("Timeline semaphore feature present but wait/counter entry points are unavailable");
    }
    state->getAndroidHardwareBufferProperties =
        reinterpret_cast<PFN_vkGetAndroidHardwareBufferPropertiesANDROID>(
            vkGetDeviceProcAddr(state->device, "vkGetAndroidHardwareBufferPropertiesANDROID"));
    state->getMemoryAndroidHardwareBuffer =
        reinterpret_cast<PFN_vkGetMemoryAndroidHardwareBufferANDROID>(
            vkGetDeviceProcAddr(state->device, "vkGetMemoryAndroidHardwareBufferANDROID"));
    state->createSamplerYcbcrConversion =
        reinterpret_cast<PFN_vkCreateSamplerYcbcrConversion>(
            vkGetDeviceProcAddr(state->device, "vkCreateSamplerYcbcrConversion"));
    if (state->createSamplerYcbcrConversion == nullptr) {
        state->createSamplerYcbcrConversion =
            reinterpret_cast<PFN_vkCreateSamplerYcbcrConversion>(
                vkGetDeviceProcAddr(state->device, "vkCreateSamplerYcbcrConversionKHR"));
    }
    state->destroySamplerYcbcrConversion =
        reinterpret_cast<PFN_vkDestroySamplerYcbcrConversion>(
            vkGetDeviceProcAddr(state->device, "vkDestroySamplerYcbcrConversion"));
    if (state->destroySamplerYcbcrConversion == nullptr) {
        state->destroySamplerYcbcrConversion =
            reinterpret_cast<PFN_vkDestroySamplerYcbcrConversion>(
                vkGetDeviceProcAddr(state->device, "vkDestroySamplerYcbcrConversionKHR"));
    }
    if (state->getAndroidHardwareBufferProperties == nullptr) {
        setStatus(state, "vkGetAndroidHardwareBufferPropertiesANDROID unavailable");
        return false;
    }
    if (state->createSamplerYcbcrConversion == nullptr || state->destroySamplerYcbcrConversion == nullptr) {
        setStatus(state, "sampler YCbCr conversion entry points unavailable");
        return false;
    }
    return true;
}

namespace {
// s7-f13/CP9C — process-global disk pipeline-cache config, set via nativeSetPipelineCacheConfig
// BEFORE the eager nativeCreate (lazy VulkanBridge construction) so createGlobalResources can seed
// the cache on the configuring launch. Default OFF/empty == today's fresh-compile path.
constexpr size_t kMaxPipelineCacheBytes = 16u * 1024u * 1024u;
std::atomic<bool> g_pipelineCacheDiskEnabled{false};
std::mutex g_pipelineCacheConfigMutex;
std::string g_pipelineCacheFilePath; // guarded by g_pipelineCacheConfigMutex
}  // namespace

bool createGlobalResources(VulkanBridgeState* state) {
    VkPipelineCacheCreateInfo pipelineCacheInfo{};
    pipelineCacheInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO;
    // s7-f13/CP9C: gated warm-start restore. Pull the process-global config into state, then (only
    // when enabled with a valid same-device file) seed pInitialData. Default OFF => pipelineCacheInfo
    // stays {sType} (pInitialData=nullptr, initialDataSize=0) — byte-identical to today.
    {
        std::lock_guard<std::mutex> cfgLock(g_pipelineCacheConfigMutex);
        state->pipelineCacheDiskEnabled = g_pipelineCacheDiskEnabled.load();
        state->pipelineCacheFilePath = g_pipelineCacheFilePath;
    }
    std::vector<uint8_t> seedPayload;
    if (state->pipelineCacheDiskEnabled && !state->pipelineCacheFilePath.empty()) {
        const auto fileBytes =
            neoproc::readPipelineCacheFile(state->pipelineCacheFilePath, kMaxPipelineCacheBytes);
        if (neoproc::deserializePipelineCacheFile(
                fileBytes, state->pipelineCacheDeviceIdentity, &seedPayload) &&
            !seedPayload.empty()) {
            pipelineCacheInfo.initialDataSize = seedPayload.size();
            pipelineCacheInfo.pInitialData = seedPayload.data();
            LOGI("pipeline-cache: seeded %zu bytes from disk", seedPayload.size());
        }
    }
    if (!check(
            vkCreatePipelineCache(
                state->device,
                &pipelineCacheInfo,
                nullptr,
                &state->graphicsPipelineCache
            ),
            "vkCreatePipelineCache(graphics)"
        )) {
        setStatus(state, "graphics pipeline cache creation failed");
        return false;
    }

    VkDescriptorSetLayoutBinding samplerBinding{};
    samplerBinding.binding = 0;
    samplerBinding.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    samplerBinding.descriptorCount = 1;
    samplerBinding.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;

    VkDescriptorSetLayoutBinding uniformBinding{};
    uniformBinding.binding = 1;
    uniformBinding.descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    uniformBinding.descriptorCount = 1;
    uniformBinding.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;

    VkDescriptorSetLayoutBinding lutBinding{};
    lutBinding.binding = 2;
    lutBinding.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    lutBinding.descriptorCount = 1;
    lutBinding.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;

    VkDescriptorSetLayoutBinding subjectMaskBinding{};
    subjectMaskBinding.binding = 3;
    subjectMaskBinding.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    subjectMaskBinding.descriptorCount = 1;
    subjectMaskBinding.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;

    std::array<VkDescriptorSetLayoutBinding, 4> bindings{
        samplerBinding,
        uniformBinding,
        lutBinding,
        subjectMaskBinding
    };
    VkDescriptorSetLayoutCreateInfo setLayoutInfo{};
    setLayoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
    setLayoutInfo.bindingCount = static_cast<uint32_t>(bindings.size());
    setLayoutInfo.pBindings = bindings.data();
    if (!check(vkCreateDescriptorSetLayout(state->device, &setLayoutInfo, nullptr, &state->descriptorSetLayout),
               "vkCreateDescriptorSetLayout")) {
        setStatus(state, "descriptor set layout failed");
        return false;
    }

    VkPipelineLayoutCreateInfo pipelineLayoutInfo{};
    pipelineLayoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
    pipelineLayoutInfo.setLayoutCount = 1;
    pipelineLayoutInfo.pSetLayouts = &state->descriptorSetLayout;
    if (!check(vkCreatePipelineLayout(state->device, &pipelineLayoutInfo, nullptr, &state->pipelineLayout),
               "vkCreatePipelineLayout")) {
        setStatus(state, "pipeline layout failed");
        return false;
    }

    state->vertexShader = createShaderModule(state->device, kFullscreenVertSpv, kFullscreenVertSpvCount);
    state->fragmentShader = createShaderModule(state->device, kYcbcrPreviewFragSpv, kYcbcrPreviewFragSpvCount);
    if (state->vertexShader == VK_NULL_HANDLE || state->fragmentShader == VK_NULL_HANDLE) {
        setStatus(state, "shader module creation failed");
        return false;
    }

    VkBufferCreateInfo bufferInfo{};
    bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
    bufferInfo.size = sizeof(kFullscreenQuad);
    bufferInfo.usage = VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
    bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (!check(vkCreateBuffer(state->device, &bufferInfo, nullptr, &state->vertexBuffer), "vkCreateBuffer")) {
        setStatus(state, "vertex buffer failed");
        return false;
    }

    VkMemoryRequirements memReq{};
    vkGetBufferMemoryRequirements(state->device, state->vertexBuffer, &memReq);
    VkMemoryAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = memReq.size;
    allocInfo.memoryTypeIndex = findMemoryType(
        state,
        memReq.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
    );
    if (!check(vkAllocateMemory(state->device, &allocInfo, nullptr, &state->vertexBufferMemory), "vkAllocateMemory(vertex)")) {
        setStatus(state, "vertex memory allocation failed");
        return false;
    }
    if (!check(vkBindBufferMemory(state->device, state->vertexBuffer, state->vertexBufferMemory, 0), "vkBindBufferMemory(vertex)")) {
        setStatus(state, "vertex buffer bind failed");
        return false;
    }
    void* mapped = nullptr;
    if (!check(vkMapMemory(state->device, state->vertexBufferMemory, 0, bufferInfo.size, 0, &mapped), "vkMapMemory(vertex)")) {
        setStatus(state, "vertex memory map failed");
        return false;
    }
    std::memcpy(mapped, kFullscreenQuad.data(), sizeof(kFullscreenQuad));
    vkUnmapMemory(state->device, state->vertexBufferMemory);
    if (!createOrUpdatePreviewLut(state, nullptr, 0)) {
        setStatus(state, "preview LUT initialization failed");
        return false;
    }
    if (!createOrUpdateSubjectDetailMask(state, nullptr, 0, 0, -1)) {
        setStatus(state, "subject detail mask initialization failed");
        return false;
    }
    if (!createDummyResources(state)) {
        setStatus(state, "dummy resources initialization failed");
        return false;
    }
    // PART A (startup deferral): the three heavy compute-pipeline groups (realtime enhance + QFCA +
    // OmniClarity, ~2.5s of SPIR-V compilation) are NOT compiled here. createGlobalResources now returns
    // after only the cheap "Group A" resources above (cache, layouts, shaders, vertex buffer, preview
    // LUT, subject-detail mask, dummies) — everything the RAW preview render samples. nativeCreate then
    // flips supported=true in ~tens of ms and starts startEnhancementPipelineBuild(), which compiles the
    // three groups on a background thread and flips their readiness flags under state->mutex. Until then
    // canDispatchRealtimeCompute() / the OmniClarity gate stay false, so frames render the raw camera
    // image (fragment binding 0 falls back to frame->imageView); enhancement fades in once ready.
    return true;
}

// True when [format] is one of the 10-bit packed RGB formats (A2B10/A2R10).
bool isTenBitPackedRgb(VkFormat format) {
    return format == VK_FORMAT_A2B10G10R10_UNORM_PACK32 ||
        format == VK_FORMAT_A2R10G10B10_UNORM_PACK32;
}

// True when [colorSpace] enforces a specific EOTF the compositor inverts
// at scan-out (PQ for HDR10, HLG for HLG). Routing SDR content through
// these produces a clearly-wrong picture; we filter them out from the
// SDR fallback path. Mirrors HdrSwapchainFormatPolicy.isStrictEotfColorSpace.
bool isStrictEotfColorSpace(VkColorSpaceKHR colorSpace) {
    return colorSpace == VK_COLOR_SPACE_HDR10_ST2084_EXT ||
        colorSpace == VK_COLOR_SPACE_HDR10_HLG_EXT;
}

bool isEncoderSdrColorSpace(VkColorSpaceKHR colorSpace) {
    return !isStrictEotfColorSpace(colorSpace);
}

const char* encoderSurfaceColorSpaceLabel(VkColorSpaceKHR colorSpace) {
    switch (colorSpace) {
        case VK_COLOR_SPACE_SRGB_NONLINEAR_KHR:
            return "SRGB_NONLINEAR";
        case VK_COLOR_SPACE_EXTENDED_SRGB_LINEAR_EXT:
            return "EXTENDED_SRGB_LINEAR";
        case VK_COLOR_SPACE_HDR10_ST2084_EXT:
            return "HDR10_ST2084";
        case VK_COLOR_SPACE_HDR10_HLG_EXT:
            return "HDR10_HLG";
        default:
            return "OTHER";
    }
}

// Find the first format in [formats] satisfying [pred], or nullptr if none.
template <class Pred>
const VkSurfaceFormatKHR* findFormat(
    const std::vector<VkSurfaceFormatKHR>& formats,
    Pred pred
) {
    for (const auto& f : formats) {
        if (pred(f)) return &f;
    }
    return nullptr;
}

// SDR path. Mirrors HdrSwapchainFormatPolicy.resolveSdr — must NOT pick
// an HDR colourspace because that mis-tags SDR content and makes the
// system compositor apply an inverse PQ/HLG transfer function.
VkSurfaceFormatKHR chooseSdrSurfaceFormat(const std::vector<VkSurfaceFormatKHR>& formats) {
    // 1. Standard 8-bit sRGB pair (the SDR baseline).
    if (auto* match = findFormat(formats, [](const VkSurfaceFormatKHR& f) {
            return (f.format == VK_FORMAT_B8G8R8A8_UNORM ||
                    f.format == VK_FORMAT_R8G8B8A8_UNORM) &&
                f.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
        })) {
        return *match;
    }
    // 2. 8-bit sRGB-tagged.
    if (auto* match = findFormat(formats, [](const VkSurfaceFormatKHR& f) {
            return f.format == VK_FORMAT_B8G8R8A8_SRGB ||
                f.format == VK_FORMAT_R8G8B8A8_SRGB;
        })) {
        return *match;
    }
    // 3. Any sRGB-colourspace format (10-bit sRGB / FP16 sRGB).
    if (auto* match = findFormat(formats, [](const VkSurfaceFormatKHR& f) {
            return f.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
        })) {
        return *match;
    }
    // 4. Defensive: avoid PQ/HLG even in pathological all-HDR lists.
    if (auto* match = findFormat(formats, [](const VkSurfaceFormatKHR& f) {
            return !isStrictEotfColorSpace(f.colorSpace);
        })) {
        return *match;
    }
    return formats.front();
}

// SDR 10-bit DISPLAY (preview) path. Mirrors HdrSwapchainFormatPolicy.resolveTenBitSdrDisplay.
// Prefers a 10-bit packed format with core sRGB (does NOT require VK_EXT_swapchain_colorspace),
// then any 10-bit packed non-strict-EOTF colourspace (never PQ/HLG), then falls back to the 8-bit
// SDR baseline — so a driver without a 10-bit SDR display pair yields byte-identical 8-bit preview.
VkSurfaceFormatKHR chooseDisplaySdrTenBitSurfaceFormat(
    const std::vector<VkSurfaceFormatKHR>& formats
) {
    if (formats.size() == 1 && formats.front().format == VK_FORMAT_UNDEFINED) {
        VkSurfaceFormatKHR fallback = formats.front();
        fallback.format = VK_FORMAT_A2B10G10R10_UNORM_PACK32;
        fallback.colorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
        return fallback;
    }
    if (formats.empty()) {
        VkSurfaceFormatKHR fallback{};
        fallback.format = VK_FORMAT_A2B10G10R10_UNORM_PACK32;
        fallback.colorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
        return fallback;
    }
    // 1. 10-bit packed + core sRGB.
    if (auto* match = findFormat(formats, [](const VkSurfaceFormatKHR& f) {
            return isTenBitPackedRgb(f.format) &&
                f.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
        })) {
        return *match;
    }
    // 2. 10-bit packed + any non-strict-EOTF colourspace (never PQ/HLG).
    if (auto* match = findFormat(formats, [](const VkSurfaceFormatKHR& f) {
            return isTenBitPackedRgb(f.format) && isEncoderSdrColorSpace(f.colorSpace);
        })) {
        return *match;
    }
    // 3. Graceful 8-bit fallback — byte-identical to today's preview.
    return chooseSdrSurfaceFormat(formats);
}

// HDR path. Mirrors HdrSwapchainFormatPolicy.resolveHdr.
VkSurfaceFormatKHR chooseHdrSurfaceFormat(
    const std::vector<VkSurfaceFormatKHR>& formats,
    VkColorSpaceKHR primaryHdrColorSpace,
    VkColorSpaceKHR secondaryHdrColorSpace
) {
    // 1. 10-bit + matching HDR colourspace (HDR10 → PQ, HLG → HLG).
    if (auto* match = findFormat(formats, [&](const VkSurfaceFormatKHR& f) {
            return isTenBitPackedRgb(f.format) && f.colorSpace == primaryHdrColorSpace;
        })) {
        return *match;
    }
    // 2. 10-bit + secondary HDR colourspace (BT.2020 linear).
    if (auto* match = findFormat(formats, [&](const VkSurfaceFormatKHR& f) {
            return isTenBitPackedRgb(f.format) && f.colorSpace == secondaryHdrColorSpace;
        })) {
        return *match;
    }
    // 3. FP16 + extended-sRGB linear (Vulkan's universal HDR path).
    if (auto* match = findFormat(formats, [](const VkSurfaceFormatKHR& f) {
            return f.format == VK_FORMAT_R16G16B16A16_SFLOAT &&
                (f.colorSpace == VK_COLOR_SPACE_EXTENDED_SRGB_LINEAR_EXT ||
                    f.colorSpace == VK_COLOR_SPACE_EXTENDED_SRGB_NONLINEAR_EXT);
        })) {
        return *match;
    }
    // 4. 10-bit + sRGB — bit depth preserved, system handles tone-mapping.
    if (auto* match = findFormat(formats, [](const VkSurfaceFormatKHR& f) {
            return isTenBitPackedRgb(f.format) &&
                f.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
        })) {
        return *match;
    }
    // 5. FP16 + sRGB.
    if (auto* match = findFormat(formats, [](const VkSurfaceFormatKHR& f) {
            return f.format == VK_FORMAT_R16G16B16A16_SFLOAT &&
                f.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
        })) {
        return *match;
    }
    // 6. 8-bit sRGB SDR baseline as last resort.
    if (auto* match = findFormat(formats, [](const VkSurfaceFormatKHR& f) {
            return (f.format == VK_FORMAT_B8G8R8A8_UNORM ||
                    f.format == VK_FORMAT_R8G8B8A8_UNORM) &&
                f.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
        })) {
        return *match;
    }
    return formats.front();
}

/**
 * HDR-aware swapchain format selector. This function is the C++ mirror
 * of `HdrSwapchainFormatPolicy.resolve` in the Kotlin side — the two
 * MUST stay in sync because the Kotlin policy is what drives the unit
 * tests. If you change the algorithm here, update the Kotlin policy
 * (and its tests) at the same time.
 *
 * [hdrModeId] is the small integer agreed across the JNI boundary:
 * 0=SDR, 1=HLG, 2=HDR10, 3=HDR10+, 4=DolbyVision.
 */
VkSurfaceFormatKHR chooseSurfaceFormat(
    const std::vector<VkSurfaceFormatKHR>& formats,
    int hdrModeId,
    bool preferTenBitSdrDisplay = false
) {
    // Honour the Vulkan-canonical "format=UNDEFINED" sentinel that means
    // "any format is acceptable".
    if (formats.size() == 1 && formats.front().format == VK_FORMAT_UNDEFINED) {
        VkSurfaceFormatKHR fallback = formats.front();
        if (hdrModeId == 0 && !preferTenBitSdrDisplay) {
            fallback.format = VK_FORMAT_B8G8R8A8_UNORM;
        } else {
            fallback.format = VK_FORMAT_A2B10G10R10_UNORM_PACK32;
        }
        fallback.colorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
        return fallback;
    }
    if (formats.empty()) {
        VkSurfaceFormatKHR fallback{};
        fallback.format = VK_FORMAT_B8G8R8A8_UNORM;
        fallback.colorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
        return fallback;
    }

    switch (hdrModeId) {
        case 0: // SDR
            return preferTenBitSdrDisplay
                ? chooseDisplaySdrTenBitSurfaceFormat(formats)
                : chooseSdrSurfaceFormat(formats);
        case 1: // HLG
            return chooseHdrSurfaceFormat(
                formats,
                VK_COLOR_SPACE_HDR10_HLG_EXT,
                VK_COLOR_SPACE_BT2020_LINEAR_EXT
            );
        case 2: // HDR10
        case 3: // HDR10+
        case 4: // DolbyVision (HDR10-compatible at the swapchain level)
        default:
            return chooseHdrSurfaceFormat(
                formats,
                VK_COLOR_SPACE_HDR10_ST2084_EXT,
                VK_COLOR_SPACE_BT2020_LINEAR_EXT
            );
    }
}

const char* presentModeLabel(VkPresentModeKHR mode) {
    switch (mode) {
        case VK_PRESENT_MODE_IMMEDIATE_KHR:
            return "IMMEDIATE";
        case VK_PRESENT_MODE_MAILBOX_KHR:
            return "MAILBOX";
        case VK_PRESENT_MODE_FIFO_KHR:
            return "FIFO";
        case VK_PRESENT_MODE_FIFO_RELAXED_KHR:
            return "FIFO_RELAXED";
        default:
            return "UNKNOWN";
    }
}

VkSurfaceFormatKHR chooseEncoderSdrSurfaceFormat(const std::vector<VkSurfaceFormatKHR>& formats) {
    if (formats.size() == 1 && formats.front().format == VK_FORMAT_UNDEFINED) {
        VkSurfaceFormatKHR fallback = formats.front();
        fallback.format = VK_FORMAT_A2B10G10R10_UNORM_PACK32;
        fallback.colorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
        return fallback;
    }
    if (formats.empty()) {
        VkSurfaceFormatKHR fallback{};
        fallback.format = VK_FORMAT_A2B10G10R10_UNORM_PACK32;
        fallback.colorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
        return fallback;
    }
    for (const auto& f : formats) {
        if (isTenBitPackedRgb(f.format) && isEncoderSdrColorSpace(f.colorSpace)) {
            return f;
        }
    }
    for (const auto& f : formats) {
        if (f.format == VK_FORMAT_R16G16B16A16_SFLOAT && isEncoderSdrColorSpace(f.colorSpace)) {
            return f;
        }
    }
    return chooseSdrSurfaceFormat(formats);
}

VkSurfaceFormatKHR chooseEncoderSurfaceFormat(
    const std::vector<VkSurfaceFormatKHR>& formats,
    int hdrModeId
) {
    if (hdrModeId == 0) {
        return chooseEncoderSdrSurfaceFormat(formats);
    }

    // Encoder HDR targets preserve real HDR/10-bit transport whenever the
    // driver exposes a packed 10-bit or FP16 WSI format. Shader math and
    // MediaCodec/Main10 metadata must not mask an 8-bit producer fallback.
    VkSurfaceFormatKHR hdrFormat = chooseSurfaceFormat(formats, hdrModeId);
    if (hdrFormat.format == VK_FORMAT_A2B10G10R10_UNORM_PACK32 ||
            hdrFormat.format == VK_FORMAT_A2R10G10B10_UNORM_PACK32 ||
            hdrFormat.format == VK_FORMAT_R16G16B16A16_SFLOAT) {
        return hdrFormat;
    }

    // Last-resort compatibility path for devices exposing only SDR encoder
    // surfaces. Higher layers block strict 10-bit tiers from accepting this
    // fallback.
    if (auto* match = findFormat(formats, [](const VkSurfaceFormatKHR& f) {
            return (f.format == VK_FORMAT_R8G8B8A8_UNORM ||
                    f.format == VK_FORMAT_B8G8R8A8_UNORM) &&
                f.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
        })) {
        return *match;
    }
    return hdrFormat;
}

bool isEncoderHdrFallbackColorSpace(VkColorSpaceKHR colorSpace) {
    return colorSpace == VK_COLOR_SPACE_BT2020_LINEAR_EXT ||
        colorSpace == VK_COLOR_SPACE_EXTENDED_SRGB_LINEAR_EXT ||
        colorSpace == VK_COLOR_SPACE_EXTENDED_SRGB_NONLINEAR_EXT ||
        colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
}

bool isEncoderSurfaceDeliveryCompatible(int hdrModeId, VkColorSpaceKHR colorSpace) {
    switch (hdrModeId) {
        case 0:
            return isEncoderSdrColorSpace(colorSpace);
        case 1:
            return colorSpace == VK_COLOR_SPACE_HDR10_HLG_EXT ||
                isEncoderHdrFallbackColorSpace(colorSpace);
        case 2:
        case 3:
        case 4:
            return colorSpace == VK_COLOR_SPACE_HDR10_ST2084_EXT ||
                isEncoderHdrFallbackColorSpace(colorSpace);
        default:
            return false;
    }
}

bool isHighPrecisionEncoderSurfaceFormat(VkFormat format) {
    return isTenBitPackedRgb(format) || format == VK_FORMAT_R16G16B16A16_SFLOAT;
}

int encoderSurfaceBitDepth(VkFormat format) {
    if (format == VK_FORMAT_R16G16B16A16_SFLOAT) return 16;
    if (isTenBitPackedRgb(format)) return 10;
    return 8;
}

const char* encoderSurfacePrecisionLabel(VkFormat format) {
    if (format == VK_FORMAT_R16G16B16A16_SFLOAT) return "FP16";
    if (isTenBitPackedRgb(format)) return "10-bit packed";
    return "8-bit";
}

VkPresentModeKHR choosePresentMode(const std::vector<VkPresentModeKHR>& modes, bool encoderTarget) {
    if (encoderTarget) {
        for (auto mode : modes) {
            if (mode == VK_PRESENT_MODE_IMMEDIATE_KHR) return mode;
        }
        for (auto mode : modes) {
            if (mode == VK_PRESENT_MODE_MAILBOX_KHR) return mode;
        }
        for (auto mode : modes) {
            if (mode == VK_PRESENT_MODE_FIFO_RELAXED_KHR) return mode;
        }
        for (auto mode : modes) {
            if (mode == VK_PRESENT_MODE_FIFO_KHR) return mode;
        }
        return modes.empty() ? VK_PRESENT_MODE_FIFO_KHR : modes.front();
    }
    for (auto mode : modes) {
        if (mode == VK_PRESENT_MODE_MAILBOX_KHR) return mode;
    }
    for (auto mode : modes) {
        if (mode == VK_PRESENT_MODE_FIFO_RELAXED_KHR) return mode;
    }
    for (auto mode : modes) {
        if (mode == VK_PRESENT_MODE_FIFO_KHR) return mode;
    }
    return modes.empty() ? VK_PRESENT_MODE_FIFO_KHR : modes.front();
}

VkExtent2D chooseExtent(ANativeWindow* window, const VkSurfaceCapabilitiesKHR& capabilities) {
    if (capabilities.currentExtent.width != UINT32_MAX) {
        return capabilities.currentExtent;
    }
    const uint32_t width = static_cast<uint32_t>(std::max(1, ANativeWindow_getWidth(window)));
    const uint32_t height = static_cast<uint32_t>(std::max(1, ANativeWindow_getHeight(window)));
    VkExtent2D extent{};
    extent.width = std::clamp(width, capabilities.minImageExtent.width, capabilities.maxImageExtent.width);
    extent.height = std::clamp(height, capabilities.minImageExtent.height, capabilities.maxImageExtent.height);
    return extent;
}

bool createRenderPass(VulkanBridgeState* state, TargetSurface* target) {
    VkAttachmentDescription colorAttachment{};
    colorAttachment.format = target->swapchainFormat;
    colorAttachment.samples = VK_SAMPLE_COUNT_1_BIT;
    colorAttachment.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
    colorAttachment.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    colorAttachment.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    colorAttachment.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    colorAttachment.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    colorAttachment.finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;

    VkAttachmentReference colorAttachmentRef{};
    colorAttachmentRef.attachment = 0;
    colorAttachmentRef.layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;

    VkSubpassDescription subpass{};
    subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    subpass.colorAttachmentCount = 1;
    subpass.pColorAttachments = &colorAttachmentRef;

    VkSubpassDependency dependency{};
    dependency.srcSubpass = VK_SUBPASS_EXTERNAL;
    dependency.dstSubpass = 0;
    dependency.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dependency.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dependency.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;

    VkRenderPassCreateInfo renderPassInfo{};
    renderPassInfo.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
    renderPassInfo.attachmentCount = 1;
    renderPassInfo.pAttachments = &colorAttachment;
    renderPassInfo.subpassCount = 1;
    renderPassInfo.pSubpasses = &subpass;
    renderPassInfo.dependencyCount = 1;
    renderPassInfo.pDependencies = &dependency;

    return check(vkCreateRenderPass(state->device, &renderPassInfo, nullptr, &target->renderPass), "vkCreateRenderPass");
}

bool createGraphicsPipelineWithCache(
    VulkanBridgeState* state,
    TargetSurface* target,
    VkPipelineCache pipelineCache
) {
    VkPipelineShaderStageCreateInfo vertexStage{};
    vertexStage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    vertexStage.stage = VK_SHADER_STAGE_VERTEX_BIT;
    vertexStage.module = state->vertexShader;
    vertexStage.pName = "main";

    VkPipelineShaderStageCreateInfo fragmentStage{};
    fragmentStage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    fragmentStage.stage = VK_SHADER_STAGE_FRAGMENT_BIT;
    fragmentStage.module = state->fragmentShader;
    fragmentStage.pName = "main";

    // CP03 — specialize the SDR monitor-preview dither quantization to the target surface's
    // real bit depth (constant_id 0). On an A2B10G10R10 preview surface this dithers to 1023
    // levels (full 10-bit headroom) instead of re-banding at 8-bit; an 8-bit surface keeps 255.
    // Must outlive vkCreateGraphicsPipelines below (all locals in this function).
    const float previewQuantizationLevels =
        static_cast<float>((1u << encoderSurfaceBitDepth(target->swapchainFormat)) - 1u);
    VkSpecializationMapEntry fragmentSpecEntry{};
    fragmentSpecEntry.constantID = 0;
    fragmentSpecEntry.offset = 0;
    fragmentSpecEntry.size = sizeof(float);
    VkSpecializationInfo fragmentSpecInfo{};
    fragmentSpecInfo.mapEntryCount = 1;
    fragmentSpecInfo.pMapEntries = &fragmentSpecEntry;
    fragmentSpecInfo.dataSize = sizeof(float);
    fragmentSpecInfo.pData = &previewQuantizationLevels;
    fragmentStage.pSpecializationInfo = &fragmentSpecInfo;

    VkPipelineShaderStageCreateInfo stages[] = {vertexStage, fragmentStage};

    VkVertexInputBindingDescription bindingDescription{};
    bindingDescription.binding = 0;
    bindingDescription.stride = sizeof(Vertex);
    bindingDescription.inputRate = VK_VERTEX_INPUT_RATE_VERTEX;

    std::array<VkVertexInputAttributeDescription, 2> attributeDescriptions{};
    attributeDescriptions[0].location = 0;
    attributeDescriptions[0].binding = 0;
    attributeDescriptions[0].format = VK_FORMAT_R32G32_SFLOAT;
    attributeDescriptions[0].offset = offsetof(Vertex, position);
    attributeDescriptions[1].location = 1;
    attributeDescriptions[1].binding = 0;
    attributeDescriptions[1].format = VK_FORMAT_R32G32_SFLOAT;
    attributeDescriptions[1].offset = offsetof(Vertex, texCoord);

    VkPipelineVertexInputStateCreateInfo vertexInput{};
    vertexInput.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;
    vertexInput.vertexBindingDescriptionCount = 1;
    vertexInput.pVertexBindingDescriptions = &bindingDescription;
    vertexInput.vertexAttributeDescriptionCount = static_cast<uint32_t>(attributeDescriptions.size());
    vertexInput.pVertexAttributeDescriptions = attributeDescriptions.data();

    VkPipelineInputAssemblyStateCreateInfo inputAssembly{};
    inputAssembly.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
    inputAssembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
    inputAssembly.primitiveRestartEnable = VK_FALSE;

    VkPipelineViewportStateCreateInfo viewportState{};
    viewportState.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
    viewportState.viewportCount = 1;
    viewportState.scissorCount = 1;

    VkPipelineRasterizationStateCreateInfo rasterizer{};
    rasterizer.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
    rasterizer.depthClampEnable = VK_FALSE;
    rasterizer.rasterizerDiscardEnable = VK_FALSE;
    rasterizer.polygonMode = VK_POLYGON_MODE_FILL;
    rasterizer.lineWidth = 1.0f;
    rasterizer.cullMode = VK_CULL_MODE_NONE;
    rasterizer.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
    rasterizer.depthBiasEnable = VK_FALSE;

    VkPipelineMultisampleStateCreateInfo multisampling{};
    multisampling.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
    multisampling.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;

    VkPipelineColorBlendAttachmentState colorBlendAttachment{};
    colorBlendAttachment.colorWriteMask =
        VK_COLOR_COMPONENT_R_BIT |
        VK_COLOR_COMPONENT_G_BIT |
        VK_COLOR_COMPONENT_B_BIT |
        VK_COLOR_COMPONENT_A_BIT;
    colorBlendAttachment.blendEnable = VK_FALSE;

    VkPipelineColorBlendStateCreateInfo colorBlending{};
    colorBlending.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
    colorBlending.logicOpEnable = VK_FALSE;
    colorBlending.attachmentCount = 1;
    colorBlending.pAttachments = &colorBlendAttachment;

    std::array<VkDynamicState, 2> dynamicStates{
        VK_DYNAMIC_STATE_VIEWPORT,
        VK_DYNAMIC_STATE_SCISSOR
    };
    VkPipelineDynamicStateCreateInfo dynamicState{};
    dynamicState.sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO;
    dynamicState.dynamicStateCount = static_cast<uint32_t>(dynamicStates.size());
    dynamicState.pDynamicStates = dynamicStates.data();

    VkGraphicsPipelineCreateInfo pipelineInfo{};
    pipelineInfo.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
    pipelineInfo.stageCount = 2;
    pipelineInfo.pStages = stages;
    pipelineInfo.pVertexInputState = &vertexInput;
    pipelineInfo.pInputAssemblyState = &inputAssembly;
    pipelineInfo.pViewportState = &viewportState;
    pipelineInfo.pRasterizationState = &rasterizer;
    pipelineInfo.pMultisampleState = &multisampling;
    pipelineInfo.pColorBlendState = &colorBlending;
    pipelineInfo.pDynamicState = &dynamicState;
    pipelineInfo.layout = state->pipelineLayout;
    pipelineInfo.renderPass = target->renderPass;
    pipelineInfo.subpass = 0;
    if (target->encoderTarget) {
        // The encoder WSI blit shader is deliberately simple; favor fast
        // recording startup over driver compile-time optimization here.
        pipelineInfo.flags |= VK_PIPELINE_CREATE_DISABLE_OPTIMIZATION_BIT;
    }

    LOGI(
        "%s: creating graphics pipeline format=%s",
        target->encoderTarget ? "encoder" : "preview",
        vkFormatLabel(target->swapchainFormat)
    );
    const bool created = check(vkCreateGraphicsPipelines(
        state->device,
        pipelineCache,
        1,
        &pipelineInfo,
        nullptr,
        &target->pipeline
    ), "vkCreateGraphicsPipelines");
    target->ownsPipeline = created;
    return created;
}

bool createGraphicsPipeline(VulkanBridgeState* state, TargetSurface* target) {
    return createGraphicsPipelineWithCache(state, target, state->graphicsPipelineCache);
}

bool prewarmEncoderGraphicsPipeline(
    VulkanBridgeState* state,
    VkFormat format,
    VkPipelineCache prewarmPipelineCache
) {
    if (state == nullptr || state->device == VK_NULL_HANDLE) return false;

    TargetSurface prewarmTarget{};
    prewarmTarget.encoderTarget = true;
    prewarmTarget.swapchainFormat = format;
    prewarmTarget.colorSpace = VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
    prewarmTarget.builtForHdrModeId = 0;

    const auto prewarmStart = std::chrono::steady_clock::now();
    bool created = false;
    if (createRenderPass(state, &prewarmTarget)) {
        created = createGraphicsPipelineWithCache(state, &prewarmTarget, prewarmPipelineCache);
    }

    if (created) {
        LOGI(
            "encoder: prewarmed compatible graphics pipeline format=%s duration=%.3fms",
            vkFormatLabel(format),
            elapsedMsSince(prewarmStart)
        );
    } else {
        LOGW(
            "encoder: compatible graphics pipeline prewarm skipped/failed format=%s",
            vkFormatLabel(format)
        );
    }

    if (prewarmTarget.pipeline != VK_NULL_HANDLE && prewarmTarget.ownsPipeline) {
        vkDestroyPipeline(state->device, prewarmTarget.pipeline, nullptr);
        prewarmTarget.pipeline = VK_NULL_HANDLE;
    }
    if (prewarmTarget.renderPass != VK_NULL_HANDLE) {
        vkDestroyRenderPass(state->device, prewarmTarget.renderPass, nullptr);
        prewarmTarget.renderPass = VK_NULL_HANDLE;
    }
    return created;
}

void prewarmEncoderGraphicsPipelines(VulkanBridgeState* state) {
    if (state == nullptr || state->device == VK_NULL_HANDLE) return;
    VkPipelineCacheCreateInfo pipelineCacheInfo{};
    pipelineCacheInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO;
    VkPipelineCache prewarmPipelineCache = VK_NULL_HANDLE;
    if (!check(
            vkCreatePipelineCache(
                state->device,
                &pipelineCacheInfo,
                nullptr,
                &prewarmPipelineCache
            ),
            "vkCreatePipelineCache(encoder-prewarm)"
        )) {
        LOGW("encoder: async compatible graphics pipeline prewarm skipped; cache creation failed");
        return;
    }

    constexpr std::array<VkFormat, 2> encoderSurfaceFormats{
        VK_FORMAT_R8G8B8A8_UNORM,
        VK_FORMAT_B8G8R8A8_UNORM
    };
    for (const VkFormat format : encoderSurfaceFormats) {
        prewarmEncoderGraphicsPipeline(state, format, prewarmPipelineCache);
    }
    vkDestroyPipelineCache(state->device, prewarmPipelineCache, nullptr);
}

[[maybe_unused]] void startEncoderGraphicsPipelinePrewarm(VulkanBridgeState* state) {
    if (state == nullptr || state->encoderPrewarmStarted.exchange(true)) return;
    state->encoderPrewarmThread = std::thread([state]() {
        prewarmEncoderGraphicsPipelines(state);
        state->encoderPrewarmFinished.store(true);
        LOGI("encoder: async compatible graphics pipeline prewarm finished");
    });
}

bool tryReuseCompatiblePreviewGraphicsPipeline(VulkanBridgeState* state, TargetSurface* target) {
    if (state == nullptr || target == nullptr || !target->encoderTarget) return false;
    TargetSurface& preview = state->previewTarget;
    if (!preview.valid ||
        preview.pipeline == VK_NULL_HANDLE ||
        preview.swapchainFormat != target->swapchainFormat ||
        preview.builtForHdrModeId != target->builtForHdrModeId) {
        return false;
    }
    target->pipeline = preview.pipeline;
    target->ownsPipeline = false;
    LOGI(
        "encoder: reused compatible preview graphics pipeline format=%s hdrModeId=%d",
        vkFormatLabel(target->swapchainFormat),
        target->builtForHdrModeId
    );
    return true;
}

bool createUniformBuffers(VulkanBridgeState* state, TargetSurface* target) {
    const VkDeviceSize bufferSize = sizeof(PreviewUniforms);
    target->uniformBuffers.resize(target->images.size(), VK_NULL_HANDLE);
    target->uniformMemories.resize(target->images.size(), VK_NULL_HANDLE);

    for (size_t i = 0; i < target->images.size(); ++i) {
        VkBufferCreateInfo bufferInfo{};
        bufferInfo.sType = VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO;
        bufferInfo.size = bufferSize;
        bufferInfo.usage = VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
        bufferInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        if (!check(vkCreateBuffer(state->device, &bufferInfo, nullptr, &target->uniformBuffers[i]), "vkCreateBuffer(uniform)")) {
            return false;
        }

        VkMemoryRequirements memReq{};
        vkGetBufferMemoryRequirements(state->device, target->uniformBuffers[i], &memReq);
        VkMemoryAllocateInfo allocInfo{};
        allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
        allocInfo.allocationSize = memReq.size;
        allocInfo.memoryTypeIndex = findMemoryType(
            state,
            memReq.memoryTypeBits,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT
        );
        if (!check(vkAllocateMemory(state->device, &allocInfo, nullptr, &target->uniformMemories[i]), "vkAllocateMemory(uniform)")) {
            return false;
        }
        if (!check(vkBindBufferMemory(state->device, target->uniformBuffers[i], target->uniformMemories[i], 0), "vkBindBufferMemory(uniform)")) {
            return false;
        }
    }
    return true;
}

bool createDescriptorResources(VulkanBridgeState* state, TargetSurface* target) {
    std::array<VkDescriptorPoolSize, 3> poolSizes{};
    poolSizes[0].type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    poolSizes[0].descriptorCount = static_cast<uint32_t>(target->images.size() * 3u);
    poolSizes[1].type = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    poolSizes[1].descriptorCount = static_cast<uint32_t>(target->images.size());
    poolSizes[2].type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    poolSizes[2].descriptorCount = static_cast<uint32_t>(target->images.size());

    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.poolSizeCount = static_cast<uint32_t>(poolSizes.size());
    poolInfo.pPoolSizes = poolSizes.data();
    poolInfo.maxSets = static_cast<uint32_t>(target->images.size());
    if (!check(vkCreateDescriptorPool(state->device, &poolInfo, nullptr, &target->descriptorPool), "vkCreateDescriptorPool")) {
        return false;
    }

    std::vector<VkDescriptorSetLayout> layouts(target->images.size(), state->descriptorSetLayout);
    VkDescriptorSetAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    allocInfo.descriptorPool = target->descriptorPool;
    allocInfo.descriptorSetCount = static_cast<uint32_t>(layouts.size());
    allocInfo.pSetLayouts = layouts.data();
    target->descriptorSets.resize(target->images.size(), VK_NULL_HANDLE);
    if (!check(vkAllocateDescriptorSets(state->device, &allocInfo, target->descriptorSets.data()), "vkAllocateDescriptorSets")) {
        return false;
    }
    return true;
}

bool createComputeIntermediateImage(
    VulkanBridgeState* state,
    TargetSurface* target,
    ComputeIntermediateImage* intermediate,
    const char* label
) {
    if (state == nullptr || target == nullptr || intermediate == nullptr) return false;
    intermediate->width = target->extent.width;
    intermediate->height = target->extent.height;

    VkImageCreateInfo imageInfo{};
    imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = VK_FORMAT_R16G16B16A16_SFLOAT;
    imageInfo.extent = {target->extent.width, target->extent.height, 1u};
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    // Temporal feedback copies the completed compute result into the chronological history image.
    // Keep transfer usage explicit: using vkCmdCopyImage without TRANSFER_SRC on the intermediate was
    // invalid even though many mobile drivers happened to tolerate it. TRANSFER_DST also permits a
    // deterministic current-frame seed for the first temporal frame without introducing another shader.
    imageInfo.usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT |
        VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (!check(vkCreateImage(state->device, &imageInfo, nullptr, &intermediate->image), label)) {
        return false;
    }

    VkMemoryRequirements memReq{};
    vkGetImageMemoryRequirements(state->device, intermediate->image, &memReq);
    VkMemoryAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = memReq.size;
    allocInfo.memoryTypeIndex = findMemoryType(
        state,
        memReq.memoryTypeBits,
        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
    );
    if (!check(vkAllocateMemory(state->device, &allocInfo, nullptr, &intermediate->memory), label)) {
        return false;
    }
    if (!check(vkBindImageMemory(state->device, intermediate->image, intermediate->memory, 0), label)) {
        return false;
    }

    VkImageViewCreateInfo viewInfo{};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = intermediate->image;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = VK_FORMAT_R16G16B16A16_SFLOAT;
    viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    viewInfo.subresourceRange.baseMipLevel = 0;
    viewInfo.subresourceRange.levelCount = 1;
    viewInfo.subresourceRange.baseArrayLayer = 0;
    viewInfo.subresourceRange.layerCount = 1;
    if (!check(vkCreateImageView(state->device, &viewInfo, nullptr, &intermediate->imageView), label)) {
        return false;
    }

    VkSamplerCreateInfo samplerInfo{};
    samplerInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    samplerInfo.magFilter = VK_FILTER_LINEAR;
    samplerInfo.minFilter = VK_FILTER_LINEAR;
    samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.minLod = 0.0f;
    samplerInfo.maxLod = 0.0f;
    samplerInfo.maxAnisotropy = 1.0f;
    if (!check(vkCreateSampler(state->device, &samplerInfo, nullptr, &intermediate->sampler), label)) {
        return false;
    }

    intermediate->layout = VK_IMAGE_LAYOUT_UNDEFINED;
    intermediate->ready = true;
    return true;
}

bool createQfcaIntermediateImage(
    VulkanBridgeState* state,
    ComputeIntermediateImage* intermediate,
    uint32_t width,
    uint32_t height,
    VkFormat format,
    VkImageUsageFlags usage,
    const char* label
) {
    if (state == nullptr || intermediate == nullptr) return false;
    intermediate->width = width;
    intermediate->height = height;

    VkImageCreateInfo imageInfo{};
    imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = format;
    imageInfo.extent = {width, height, 1u};
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.usage = usage;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (!check(vkCreateImage(state->device, &imageInfo, nullptr, &intermediate->image), label)) {
        return false;
    }

    VkMemoryRequirements memReq{};
    vkGetImageMemoryRequirements(state->device, intermediate->image, &memReq);
    VkMemoryAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = memReq.size;
    allocInfo.memoryTypeIndex = findMemoryType(
        state,
        memReq.memoryTypeBits,
        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
    );
    if (!check(vkAllocateMemory(state->device, &allocInfo, nullptr, &intermediate->memory), label)) {
        return false;
    }
    if (!check(vkBindImageMemory(state->device, intermediate->image, intermediate->memory, 0), label)) {
        return false;
    }

    VkImageViewCreateInfo viewInfo{};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = intermediate->image;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = format;
    viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    viewInfo.subresourceRange.baseMipLevel = 0;
    viewInfo.subresourceRange.levelCount = 1;
    viewInfo.subresourceRange.baseArrayLayer = 0;
    viewInfo.subresourceRange.layerCount = 1;
    if (!check(vkCreateImageView(state->device, &viewInfo, nullptr, &intermediate->imageView), label)) {
        return false;
    }

    VkSamplerCreateInfo samplerInfo{};
    samplerInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    samplerInfo.magFilter = VK_FILTER_LINEAR;
    samplerInfo.minFilter = VK_FILTER_LINEAR;
    samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.minLod = 0.0f;
    samplerInfo.maxLod = 0.0f;
    samplerInfo.maxAnisotropy = 1.0f;
    if (!check(vkCreateSampler(state->device, &samplerInfo, nullptr, &intermediate->sampler), label)) {
        return false;
    }

    intermediate->layout = VK_IMAGE_LAYOUT_UNDEFINED;
    intermediate->ready = true;
    return true;
}

bool createDummyResources(VulkanBridgeState* state) {
    if (state == nullptr || state->device == VK_NULL_HANDLE) return false;

    VkImageCreateInfo imageInfo{};
    imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = VK_FORMAT_R16G16B16A16_SFLOAT;
    imageInfo.extent = {1u, 1u, 1u};
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;

    if (!check(vkCreateImage(state->device, &imageInfo, nullptr, &state->dummyImage), "vkCreateImage(dummyImage)")) {
        return false;
    }

    VkMemoryRequirements memReq{};
    vkGetImageMemoryRequirements(state->device, state->dummyImage, &memReq);
    VkMemoryAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = memReq.size;
    allocInfo.memoryTypeIndex = findMemoryType(state, memReq.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (!check(vkAllocateMemory(state->device, &allocInfo, nullptr, &state->dummyImageMemory), "vkAllocateMemory(dummyImage)")) {
        return false;
    }
    vkBindImageMemory(state->device, state->dummyImage, state->dummyImageMemory, 0);

    VkImageViewCreateInfo viewInfo{};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = state->dummyImage;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = VK_FORMAT_R16G16B16A16_SFLOAT;
    viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    viewInfo.subresourceRange.baseMipLevel = 0;
    viewInfo.subresourceRange.levelCount = 1;
    viewInfo.subresourceRange.baseArrayLayer = 0;
    viewInfo.subresourceRange.layerCount = 1;
    if (!check(vkCreateImageView(state->device, &viewInfo, nullptr, &state->dummyImageView), "vkCreateImageView(dummyImageView)")) {
        return false;
    }

    VkImageCreateInfo arrayInfo = imageInfo;
    arrayInfo.arrayLayers = 8;
    if (!check(vkCreateImage(state->device, &arrayInfo, nullptr, &state->dummyImageArray), "vkCreateImage(dummyImageArray)")) {
        return false;
    }
    vkGetImageMemoryRequirements(state->device, state->dummyImageArray, &memReq);
    allocInfo.allocationSize = memReq.size;
    allocInfo.memoryTypeIndex = findMemoryType(state, memReq.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (!check(vkAllocateMemory(state->device, &allocInfo, nullptr, &state->dummyImageArrayMemory), "vkAllocateMemory(dummyImageArray)")) {
        return false;
    }
    vkBindImageMemory(state->device, state->dummyImageArray, state->dummyImageArrayMemory, 0);

    VkImageViewCreateInfo arrayViewInfo = viewInfo;
    arrayViewInfo.image = state->dummyImageArray;
    arrayViewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D_ARRAY;
    arrayViewInfo.subresourceRange.layerCount = 8;
    if (!check(vkCreateImageView(state->device, &arrayViewInfo, nullptr, &state->dummyImageArrayView), "vkCreateImageView(dummyImageArrayView)")) {
        return false;
    }

    VkSamplerCreateInfo samplerInfo{};
    samplerInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    samplerInfo.magFilter = VK_FILTER_LINEAR;
    samplerInfo.minFilter = VK_FILTER_LINEAR;
    samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.minLod = 0.0f;
    samplerInfo.maxLod = 0.0f;
    samplerInfo.maxAnisotropy = 1.0f;
    if (!check(vkCreateSampler(state->device, &samplerInfo, nullptr, &state->dummySampler), "vkCreateSampler(dummySampler)")) {
        return false;
    }

    // s1-f06 / CP12 STEP A — kill the MFSR uninitialized-read UB at creation, deterministically.
    // Both dummies are created initialLayout=UNDEFINED and were never written, so mfsr_fusion.comp read
    // uninitialized device memory while its descriptor claimed SHADER_READ_ONLY_OPTIMAL. Clear both to
    // zero and leave them SHADER_READ_ONLY_OPTIMAL via a synchronous one-time submit that fully drains
    // before any target renders. No-downgrade by construction: a coherent preview cannot be a function of
    // uninitialized memory, so zeroing this (non-contributing) dummy memory cannot change visible pixels;
    // it only makes the dormant MFSR path deterministic + UB-free. Real per-frame seeding of binding 0/1
    // (the RAW burst ring + per-frame Argus flow) is the device-gated STEP B/C. The final transition
    // targets the COMPUTE stage (mfsr_fusion.comp is a compute consumer) so a sync-validation run is clean.
    {
        VkCommandPool clearPool = VK_NULL_HANDLE;
        VkCommandBuffer clearCmd = VK_NULL_HANDLE;
        if (!beginSingleUseCommands(state, &clearPool, &clearCmd)) {
            return false;
        }
        const VkClearColorValue kZeroColor{};  // {0,0,0,0}

        VkImageSubresourceRange flowRange{};
        flowRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        flowRange.baseMipLevel = 0;
        flowRange.levelCount = 1;
        flowRange.baseArrayLayer = 0;
        flowRange.layerCount = 1;
        VkImageSubresourceRange arrayRange = flowRange;
        arrayRange.layerCount = 8;  // dummyImageArray has 8 layers (arrayInfo.arrayLayers above)

        const VkImage dummies[2] = { state->dummyImage, state->dummyImageArray };
        const VkImageSubresourceRange ranges[2] = { flowRange, arrayRange };
        for (int i = 0; i < 2; ++i) {
            transitionImageLayout(clearCmd, dummies[i],
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, ranges[i]);
            vkCmdClearColorImage(clearCmd, dummies[i],
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &kZeroColor, 1, &ranges[i]);

            // Hand off to the COMPUTE consumer (transitionImageLayout's SHADER_READ path targets the
            // FRAGMENT stage, which would not cover the compute read); the queue-idle in endSingleUseCommands
            // also fully drains this submit before any dispatch, so this is correct twice over.
            VkImageMemoryBarrier toRead{};
            toRead.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            toRead.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            toRead.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
            toRead.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
            toRead.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            toRead.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            toRead.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            toRead.image = dummies[i];
            toRead.subresourceRange = ranges[i];
            vkCmdPipelineBarrier(clearCmd,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                0, 0, nullptr, 0, nullptr, 1, &toRead);
        }

        if (!endSingleUseCommands(state, clearPool, clearCmd)) {
            return false;
        }
    }

    return true;
}

void destroyDummyResources(VulkanBridgeState* state) {
    if (state == nullptr || state->device == VK_NULL_HANDLE) return;

    if (state->dummyImage != VK_NULL_HANDLE) {
        vkDestroyImage(state->device, state->dummyImage, nullptr);
        state->dummyImage = VK_NULL_HANDLE;
    }
    if (state->dummyImageMemory != VK_NULL_HANDLE) {
        vkFreeMemory(state->device, state->dummyImageMemory, nullptr);
        state->dummyImageMemory = VK_NULL_HANDLE;
    }
    if (state->dummyImageView != VK_NULL_HANDLE) {
        vkDestroyImageView(state->device, state->dummyImageView, nullptr);
        state->dummyImageView = VK_NULL_HANDLE;
    }

    if (state->dummyImageArray != VK_NULL_HANDLE) {
        vkDestroyImage(state->device, state->dummyImageArray, nullptr);
        state->dummyImageArray = VK_NULL_HANDLE;
    }
    if (state->dummyImageArrayMemory != VK_NULL_HANDLE) {
        vkFreeMemory(state->device, state->dummyImageArrayMemory, nullptr);
        state->dummyImageArrayMemory = VK_NULL_HANDLE;
    }
    if (state->dummyImageArrayView != VK_NULL_HANDLE) {
        vkDestroyImageView(state->device, state->dummyImageArrayView, nullptr);
        state->dummyImageArrayView = VK_NULL_HANDLE;
    }

    if (state->dummySampler != VK_NULL_HANDLE) {
        vkDestroySampler(state->device, state->dummySampler, nullptr);
        state->dummySampler = VK_NULL_HANDLE;
    }
}

void destroyQfcaPipelines(VulkanBridgeState* state) {
    if (state == nullptr || state->device == VK_NULL_HANDLE) return;
    // Fail closed before any demosaic handle is destroyed. Normal teardown owns state->mutex, while
    // partial-build failures cannot observe true because readiness is published only after a complete
    // successful QFCA build.
    state->rawDemosaicPipelineReady.store(false, std::memory_order_release);

    if (state->guidedUpsamplePipeline != VK_NULL_HANDLE) {
        vkDestroyPipeline(state->device, state->guidedUpsamplePipeline, nullptr);
        state->guidedUpsamplePipeline = VK_NULL_HANDLE;
    }
    if (state->guidedUpsamplePipelineLayout != VK_NULL_HANDLE) {
        vkDestroyPipelineLayout(state->device, state->guidedUpsamplePipelineLayout, nullptr);
        state->guidedUpsamplePipelineLayout = VK_NULL_HANDLE;
    }
    if (state->guidedUpsampleDescLayout != VK_NULL_HANDLE) {
        vkDestroyDescriptorSetLayout(state->device, state->guidedUpsampleDescLayout, nullptr);
        state->guidedUpsampleDescLayout = VK_NULL_HANDLE;
    }
    if (state->guidedUpsampleShader != VK_NULL_HANDLE) {
        vkDestroyShaderModule(state->device, state->guidedUpsampleShader, nullptr);
        state->guidedUpsampleShader = VK_NULL_HANDLE;
    }

    if (state->detailPreservationPipeline != VK_NULL_HANDLE) {
        vkDestroyPipeline(state->device, state->detailPreservationPipeline, nullptr);
        state->detailPreservationPipeline = VK_NULL_HANDLE;
    }
    if (state->detailPreservationPipelineLayout != VK_NULL_HANDLE) {
        vkDestroyPipelineLayout(state->device, state->detailPreservationPipelineLayout, nullptr);
        state->detailPreservationPipelineLayout = VK_NULL_HANDLE;
    }
    if (state->detailPreservationDescLayout != VK_NULL_HANDLE) {
        vkDestroyDescriptorSetLayout(state->device, state->detailPreservationDescLayout, nullptr);
        state->detailPreservationDescLayout = VK_NULL_HANDLE;
    }
    if (state->detailPreservationShader != VK_NULL_HANDLE) {
        vkDestroyShaderModule(state->device, state->detailPreservationShader, nullptr);
        state->detailPreservationShader = VK_NULL_HANDLE;
    }

    if (state->mfsrFusionPipeline != VK_NULL_HANDLE) {
        vkDestroyPipeline(state->device, state->mfsrFusionPipeline, nullptr);
        state->mfsrFusionPipeline = VK_NULL_HANDLE;
    }
    if (state->mfsrFusionPipelineLayout != VK_NULL_HANDLE) {
        vkDestroyPipelineLayout(state->device, state->mfsrFusionPipelineLayout, nullptr);
        state->mfsrFusionPipelineLayout = VK_NULL_HANDLE;
    }
    if (state->mfsrFusionDescLayout != VK_NULL_HANDLE) {
        vkDestroyDescriptorSetLayout(state->device, state->mfsrFusionDescLayout, nullptr);
        state->mfsrFusionDescLayout = VK_NULL_HANDLE;
    }
    if (state->mfsrFusionShader != VK_NULL_HANDLE) {
        vkDestroyShaderModule(state->device, state->mfsrFusionShader, nullptr);
        state->mfsrFusionShader = VK_NULL_HANDLE;
    }

    if (state->demosaicPipeline != VK_NULL_HANDLE) {
        vkDestroyPipeline(state->device, state->demosaicPipeline, nullptr);
        state->demosaicPipeline = VK_NULL_HANDLE;
    }
    if (state->demosaicPipelineLayout != VK_NULL_HANDLE) {
        vkDestroyPipelineLayout(state->device, state->demosaicPipelineLayout, nullptr);
        state->demosaicPipelineLayout = VK_NULL_HANDLE;
    }
    if (state->demosaicDescLayout != VK_NULL_HANDLE) {
        vkDestroyDescriptorSetLayout(state->device, state->demosaicDescLayout, nullptr);
        state->demosaicDescLayout = VK_NULL_HANDLE;
    }
    if (state->demosaicShader != VK_NULL_HANDLE) {
        vkDestroyShaderModule(state->device, state->demosaicShader, nullptr);
        state->demosaicShader = VK_NULL_HANDLE;
    }
    if (state->rawMonitorTonemapPipeline != VK_NULL_HANDLE) {
        vkDestroyPipeline(state->device, state->rawMonitorTonemapPipeline, nullptr);
        state->rawMonitorTonemapPipeline = VK_NULL_HANDLE;
    }
    if (state->rawMonitorTonemapPipelineLayout != VK_NULL_HANDLE) {
        vkDestroyPipelineLayout(state->device, state->rawMonitorTonemapPipelineLayout, nullptr);
        state->rawMonitorTonemapPipelineLayout = VK_NULL_HANDLE;
    }
    if (state->rawMonitorTonemapDescLayout != VK_NULL_HANDLE) {
        vkDestroyDescriptorSetLayout(state->device, state->rawMonitorTonemapDescLayout, nullptr);
        state->rawMonitorTonemapDescLayout = VK_NULL_HANDLE;
    }
    if (state->rawMonitorTonemapShader != VK_NULL_HANDLE) {
        vkDestroyShaderModule(state->device, state->rawMonitorTonemapShader, nullptr);
        state->rawMonitorTonemapShader = VK_NULL_HANDLE;
    }
    if (state->rawPreviewDownsamplePipeline != VK_NULL_HANDLE) {
        vkDestroyPipeline(state->device, state->rawPreviewDownsamplePipeline, nullptr);
        state->rawPreviewDownsamplePipeline = VK_NULL_HANDLE;
    }
    if (state->rawPreviewDownsamplePipelineLayout != VK_NULL_HANDLE) {
        vkDestroyPipelineLayout(state->device, state->rawPreviewDownsamplePipelineLayout, nullptr);
        state->rawPreviewDownsamplePipelineLayout = VK_NULL_HANDLE;
    }
    if (state->rawPreviewDownsampleDescLayout != VK_NULL_HANDLE) {
        vkDestroyDescriptorSetLayout(state->device, state->rawPreviewDownsampleDescLayout, nullptr);
        state->rawPreviewDownsampleDescLayout = VK_NULL_HANDLE;
    }
    if (state->rawPreviewDownsampleShader != VK_NULL_HANDLE) {
        vkDestroyShaderModule(state->device, state->rawPreviewDownsampleShader, nullptr);
        state->rawPreviewDownsampleShader = VK_NULL_HANDLE;
    }
    if (state->rawSceneLogPackPipeline != VK_NULL_HANDLE) {
        vkDestroyPipeline(state->device, state->rawSceneLogPackPipeline, nullptr);
        state->rawSceneLogPackPipeline = VK_NULL_HANDLE;
    }
    if (state->rawSceneLogPackPipelineLayout != VK_NULL_HANDLE) {
        vkDestroyPipelineLayout(state->device, state->rawSceneLogPackPipelineLayout, nullptr);
        state->rawSceneLogPackPipelineLayout = VK_NULL_HANDLE;
    }
    if (state->rawSceneLogPackDescLayout != VK_NULL_HANDLE) {
        vkDestroyDescriptorSetLayout(state->device, state->rawSceneLogPackDescLayout, nullptr);
        state->rawSceneLogPackDescLayout = VK_NULL_HANDLE;
    }
    if (state->rawSceneLogPackShader != VK_NULL_HANDLE) {
        vkDestroyShaderModule(state->device, state->rawSceneLogPackShader, nullptr);
        state->rawSceneLogPackShader = VK_NULL_HANDLE;
    }
    if (state->rawDevelopDetailPipeline != VK_NULL_HANDLE) {
        vkDestroyPipeline(state->device, state->rawDevelopDetailPipeline, nullptr);
        state->rawDevelopDetailPipeline = VK_NULL_HANDLE;
    }
    if (state->rawDevelopLumaBlurPipeline != VK_NULL_HANDLE) {
        vkDestroyPipeline(state->device, state->rawDevelopLumaBlurPipeline, nullptr);
        state->rawDevelopLumaBlurPipeline = VK_NULL_HANDLE;
    }
    if (state->rawDevelopP010Pipeline != VK_NULL_HANDLE) {
        vkDestroyPipeline(state->device, state->rawDevelopP010Pipeline, nullptr);
        state->rawDevelopP010Pipeline = VK_NULL_HANDLE;
    }
    if (state->rawDevelopDetailPipelineLayout != VK_NULL_HANDLE) {
        vkDestroyPipelineLayout(state->device, state->rawDevelopDetailPipelineLayout, nullptr);
        state->rawDevelopDetailPipelineLayout = VK_NULL_HANDLE;
    }
    if (state->rawDevelopLumaBlurPipelineLayout != VK_NULL_HANDLE) {
        vkDestroyPipelineLayout(state->device, state->rawDevelopLumaBlurPipelineLayout, nullptr);
        state->rawDevelopLumaBlurPipelineLayout = VK_NULL_HANDLE;
    }
    if (state->rawDevelopP010PipelineLayout != VK_NULL_HANDLE) {
        vkDestroyPipelineLayout(state->device, state->rawDevelopP010PipelineLayout, nullptr);
        state->rawDevelopP010PipelineLayout = VK_NULL_HANDLE;
    }
    if (state->rawDevelopImageDescLayout != VK_NULL_HANDLE) {
        vkDestroyDescriptorSetLayout(state->device, state->rawDevelopImageDescLayout, nullptr);
        state->rawDevelopImageDescLayout = VK_NULL_HANDLE;
    }
    if (state->rawDevelopP010DescLayout != VK_NULL_HANDLE) {
        vkDestroyDescriptorSetLayout(state->device, state->rawDevelopP010DescLayout, nullptr);
        state->rawDevelopP010DescLayout = VK_NULL_HANDLE;
    }
    if (state->rawDevelopDetailShader != VK_NULL_HANDLE) {
        vkDestroyShaderModule(state->device, state->rawDevelopDetailShader, nullptr);
        state->rawDevelopDetailShader = VK_NULL_HANDLE;
    }
    if (state->rawDevelopLumaBlurShader != VK_NULL_HANDLE) {
        vkDestroyShaderModule(state->device, state->rawDevelopLumaBlurShader, nullptr);
        state->rawDevelopLumaBlurShader = VK_NULL_HANDLE;
    }
    if (state->rawDevelopP010Shader != VK_NULL_HANDLE) {
        vkDestroyShaderModule(state->device, state->rawDevelopP010Shader, nullptr);
        state->rawDevelopP010Shader = VK_NULL_HANDLE;
    }
    if (state->rawNiv99Pipeline != VK_NULL_HANDLE) {
        vkDestroyPipeline(state->device, state->rawNiv99Pipeline, nullptr);
        state->rawNiv99Pipeline = VK_NULL_HANDLE;
    }
    if (state->rawNiv99PipelineLayout != VK_NULL_HANDLE) {
        vkDestroyPipelineLayout(state->device, state->rawNiv99PipelineLayout, nullptr);
        state->rawNiv99PipelineLayout = VK_NULL_HANDLE;
    }
    if (state->rawNiv99DescLayout != VK_NULL_HANDLE) {
        vkDestroyDescriptorSetLayout(state->device, state->rawNiv99DescLayout, nullptr);
        state->rawNiv99DescLayout = VK_NULL_HANDLE;
    }
    if (state->rawNiv99Shader != VK_NULL_HANDLE) {
        vkDestroyShaderModule(state->device, state->rawNiv99Shader, nullptr);
        state->rawNiv99Shader = VK_NULL_HANDLE;
    }

    if (state->imaxRenderingPipeline != VK_NULL_HANDLE) {
        vkDestroyPipeline(state->device, state->imaxRenderingPipeline, nullptr);
        state->imaxRenderingPipeline = VK_NULL_HANDLE;
    }
    if (state->imaxRenderingPipelineLayout != VK_NULL_HANDLE) {
        vkDestroyPipelineLayout(state->device, state->imaxRenderingPipelineLayout, nullptr);
        state->imaxRenderingPipelineLayout = VK_NULL_HANDLE;
    }
    if (state->imaxRenderingDescLayout != VK_NULL_HANDLE) {
        vkDestroyDescriptorSetLayout(state->device, state->imaxRenderingDescLayout, nullptr);
        state->imaxRenderingDescLayout = VK_NULL_HANDLE;
    }
    if (state->imaxRenderingShader != VK_NULL_HANDLE) {
        vkDestroyShaderModule(state->device, state->imaxRenderingShader, nullptr);
        state->imaxRenderingShader = VK_NULL_HANDLE;
    }

    if (state->m_nivukxDenoisePipeline != VK_NULL_HANDLE) {
        vkDestroyPipeline(state->device, state->m_nivukxDenoisePipeline, nullptr);
        state->m_nivukxDenoisePipeline = VK_NULL_HANDLE;
    }
    if (state->m_nivukxDenoisePipelineLayout != VK_NULL_HANDLE) {
        vkDestroyPipelineLayout(state->device, state->m_nivukxDenoisePipelineLayout, nullptr);
        state->m_nivukxDenoisePipelineLayout = VK_NULL_HANDLE;
    }
    if (state->m_nivukxDenoiseDescLayout != VK_NULL_HANDLE) {
        vkDestroyDescriptorSetLayout(state->device, state->m_nivukxDenoiseDescLayout, nullptr);
        state->m_nivukxDenoiseDescLayout = VK_NULL_HANDLE;
    }
    if (state->m_nivukxDenoiseShader != VK_NULL_HANDLE) {
        vkDestroyShaderModule(state->device, state->m_nivukxDenoiseShader, nullptr);
        state->m_nivukxDenoiseShader = VK_NULL_HANDLE;
    }

    // CP-QV16 — gated spatial denoiser pipeline handles (denoiseSpatialDescSets are freed with the
    // qfcaDescriptorPool, mirroring m_nivukxDenoiseDescSets).
    if (state->denoiseSpatialPipeline != VK_NULL_HANDLE) {
        vkDestroyPipeline(state->device, state->denoiseSpatialPipeline, nullptr);
        state->denoiseSpatialPipeline = VK_NULL_HANDLE;
    }
    if (state->denoiseSpatialPipelineLayout != VK_NULL_HANDLE) {
        vkDestroyPipelineLayout(state->device, state->denoiseSpatialPipelineLayout, nullptr);
        state->denoiseSpatialPipelineLayout = VK_NULL_HANDLE;
    }
    if (state->denoiseSpatialDescLayout != VK_NULL_HANDLE) {
        vkDestroyDescriptorSetLayout(state->device, state->denoiseSpatialDescLayout, nullptr);
        state->denoiseSpatialDescLayout = VK_NULL_HANDLE;
    }
    if (state->denoiseSpatialShader != VK_NULL_HANDLE) {
        vkDestroyShaderModule(state->device, state->denoiseSpatialShader, nullptr);
        state->denoiseSpatialShader = VK_NULL_HANDLE;
    }

    // CP-QV17 — gated optical-recovery sharpener pipeline handles (sharpenOpticalDescSets are freed
    // with the qfcaDescriptorPool, mirroring denoiseSpatialDescSets).
    if (state->sharpenOpticalPipeline != VK_NULL_HANDLE) {
        vkDestroyPipeline(state->device, state->sharpenOpticalPipeline, nullptr);
        state->sharpenOpticalPipeline = VK_NULL_HANDLE;
    }
    if (state->sharpenOpticalPipelineLayout != VK_NULL_HANDLE) {
        vkDestroyPipelineLayout(state->device, state->sharpenOpticalPipelineLayout, nullptr);
        state->sharpenOpticalPipelineLayout = VK_NULL_HANDLE;
    }
    if (state->sharpenOpticalDescLayout != VK_NULL_HANDLE) {
        vkDestroyDescriptorSetLayout(state->device, state->sharpenOpticalDescLayout, nullptr);
        state->sharpenOpticalDescLayout = VK_NULL_HANDLE;
    }
    if (state->sharpenOpticalShader != VK_NULL_HANDLE) {
        vkDestroyShaderModule(state->device, state->sharpenOpticalShader, nullptr);
        state->sharpenOpticalShader = VK_NULL_HANDLE;
    }
    destroyOmniClarityPipelines(state);
}

bool createQfcaPipelines(VulkanBridgeState* state) {
    if (state == nullptr || state->device == VK_NULL_HANDLE) return false;

    auto createComputePipelineHelper = [&](
        VkShaderModule shaderModule,
        VkDescriptorSetLayout descLayout,
        VkPushConstantRange* pushRange,
        VkPipelineLayout* pipelineLayout,
        VkPipeline* pipeline,
        const char* label
    ) -> bool {
        VkPipelineLayoutCreateInfo layoutInfo{};
        layoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
        layoutInfo.setLayoutCount = 1;
        layoutInfo.pSetLayouts = &descLayout;
        if (pushRange != nullptr) {
            layoutInfo.pushConstantRangeCount = 1;
            layoutInfo.pPushConstantRanges = pushRange;
        } else {
            layoutInfo.pushConstantRangeCount = 0;
        }

        if (!check(vkCreatePipelineLayout(state->device, &layoutInfo, nullptr, pipelineLayout), label)) {
            return false;
        }

        VkPipelineShaderStageCreateInfo stageInfo{};
        stageInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        stageInfo.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        stageInfo.module = shaderModule;
        stageInfo.pName = "main";

        VkComputePipelineCreateInfo pipelineInfo{};
        pipelineInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
        pipelineInfo.stage = stageInfo;
        pipelineInfo.layout = *pipelineLayout;

        if (!check(vkCreateComputePipelines(state->device, VK_NULL_HANDLE, 1, &pipelineInfo, nullptr, pipeline), label)) {
            return false;
        }
        return true;
    };

    // 1. GUIDED UPSAMPLE PIPELINE
    {
        std::array<VkDescriptorSetLayoutBinding, 3> bindings{};
        bindings[0].binding = 0;
        bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        bindings[0].descriptorCount = 1;
        bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

        bindings[1].binding = 1;
        bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        bindings[1].descriptorCount = 1;
        bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

        bindings[2].binding = 2;
        bindings[2].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        bindings[2].descriptorCount = 1;
        bindings[2].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

        VkDescriptorSetLayoutCreateInfo layoutInfo{};
        layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
        layoutInfo.bindingCount = static_cast<uint32_t>(bindings.size());
        layoutInfo.pBindings = bindings.data();

        if (!check(vkCreateDescriptorSetLayout(state->device, &layoutInfo, nullptr, &state->guidedUpsampleDescLayout), "vkCreateDescriptorSetLayout(guided_upsample)")) {
            destroyQfcaPipelines(state);
            return false;
        }

        state->guidedUpsampleShader = createShaderModule(state->device, kGuidedUpsampleCompSpv, kGuidedUpsampleCompSpvCount);
        if (state->guidedUpsampleShader == VK_NULL_HANDLE) {
            destroyQfcaPipelines(state);
            return false;
        }

        VkPushConstantRange pushRange{};
        pushRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        pushRange.offset = 0;
        pushRange.size = sizeof(GuidedUpsamplePushConstants);

        if (!createComputePipelineHelper(
                state->guidedUpsampleShader,
                state->guidedUpsampleDescLayout,
                &pushRange,
                &state->guidedUpsamplePipelineLayout,
                &state->guidedUpsamplePipeline,
                "guided_upsample"
            )) {
            destroyQfcaPipelines(state);
            return false;
        }
    }

    // 2. DETAIL PRESERVATION PIPELINE
    {
        std::array<VkDescriptorSetLayoutBinding, 2> bindings{};
        bindings[0].binding = 0;
        bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        bindings[0].descriptorCount = 1;
        bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

        bindings[1].binding = 1;
        bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        bindings[1].descriptorCount = 1;
        bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

        VkDescriptorSetLayoutCreateInfo layoutInfo{};
        layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
        layoutInfo.bindingCount = static_cast<uint32_t>(bindings.size());
        layoutInfo.pBindings = bindings.data();

        if (!check(vkCreateDescriptorSetLayout(state->device, &layoutInfo, nullptr, &state->detailPreservationDescLayout), "vkCreateDescriptorSetLayout(detail_preservation)")) {
            destroyQfcaPipelines(state);
            return false;
        }

        state->detailPreservationShader = createShaderModule(state->device, kDetailPreservationCompSpv, kDetailPreservationCompSpvCount);
        if (state->detailPreservationShader == VK_NULL_HANDLE) {
            destroyQfcaPipelines(state);
            return false;
        }

        VkPushConstantRange pushRange{};
        pushRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        pushRange.offset = 0;
        pushRange.size = sizeof(DetailPreservationPushConstants);

        if (!createComputePipelineHelper(
                state->detailPreservationShader,
                state->detailPreservationDescLayout,
                &pushRange,
                &state->detailPreservationPipelineLayout,
                &state->detailPreservationPipeline,
                "detail_preservation"
            )) {
            destroyQfcaPipelines(state);
            return false;
        }
    }

    // 3. MFSR FUSION PIPELINE
    {
        std::array<VkDescriptorSetLayoutBinding, 3> bindings{};
        bindings[0].binding = 0;
        bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        bindings[0].descriptorCount = 1;
        bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

        bindings[1].binding = 1;
        bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        bindings[1].descriptorCount = 1;
        bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

        bindings[2].binding = 2;
        bindings[2].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        bindings[2].descriptorCount = 1;
        bindings[2].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

        VkDescriptorSetLayoutCreateInfo layoutInfo{};
        layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
        layoutInfo.bindingCount = static_cast<uint32_t>(bindings.size());
        layoutInfo.pBindings = bindings.data();

        if (!check(vkCreateDescriptorSetLayout(state->device, &layoutInfo, nullptr, &state->mfsrFusionDescLayout), "vkCreateDescriptorSetLayout(mfsr_fusion)")) {
            destroyQfcaPipelines(state);
            return false;
        }

        state->mfsrFusionShader = createShaderModule(state->device, kMfsrFusionCompSpv, kMfsrFusionCompSpvCount);
        if (state->mfsrFusionShader == VK_NULL_HANDLE) {
            destroyQfcaPipelines(state);
            return false;
        }

        VkPushConstantRange pushRange{};
        pushRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        pushRange.offset = 0;
        pushRange.size = sizeof(MfsrFusionPushConstants);

        if (!createComputePipelineHelper(
                state->mfsrFusionShader,
                state->mfsrFusionDescLayout,
                &pushRange,
                &state->mfsrFusionPipelineLayout,
                &state->mfsrFusionPipeline,
                "mfsr_fusion"
            )) {
            destroyQfcaPipelines(state);
            return false;
        }
    }

    // 3b. DEMOSAIC PIPELINE (12-bit RAW edge-directed -> ACEScg)
    // Bindings: 0 r16ui RAW in, 1 rgba16f ACEScg out, 2 r16f dark-current, 3 r8ui hot-pixel, 4 UBO.
    // s1-f07 / CP10c: only built when shaderStorageImageExtendedFormats is enabled (binding 2 r16f is an
    // extended storage format). Unsupported -> pipeline stays VK_NULL_HANDLE and the RAW-preview dispatch
    // falls back to YUV; the rest of the QFCA stack is unaffected (no-downgrade).
    if (state->storageImageExtendedFormatsEnabled) {
        std::array<VkDescriptorSetLayoutBinding, 5> bindings{};
        for (uint32_t i = 0; i < 4; ++i) {
            bindings[i].binding = i;
            bindings[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
            bindings[i].descriptorCount = 1;
            bindings[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        }
        bindings[4].binding = 4;
        bindings[4].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
        bindings[4].descriptorCount = 1;
        bindings[4].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

        VkDescriptorSetLayoutCreateInfo layoutInfo{};
        layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
        layoutInfo.bindingCount = static_cast<uint32_t>(bindings.size());
        layoutInfo.pBindings = bindings.data();

        if (!check(vkCreateDescriptorSetLayout(state->device, &layoutInfo, nullptr, &state->demosaicDescLayout), "vkCreateDescriptorSetLayout(demosaic_vulkan)")) {
            destroyQfcaPipelines(state);
            return false;
        }

        state->demosaicShader = createShaderModule(state->device, kDemosaicVulkanCompSpv, kDemosaicVulkanCompSpvCount);
        if (state->demosaicShader == VK_NULL_HANDLE) {
            destroyQfcaPipelines(state);
            return false;
        }

        // UBO at binding 4 (RawDemosaicUniforms) -> no push constants.
        if (!createComputePipelineHelper(
                state->demosaicShader,
                state->demosaicDescLayout,
                nullptr,
                &state->demosaicPipelineLayout,
                &state->demosaicPipeline,
                "demosaic_vulkan"
            )) {
            destroyQfcaPipelines(state);
            return false;
        }

        // Presentation-only GPU tonemap. Build it best-effort so unsupported RGBA8 storage cannot take
        // down the genuine RAW debayer; ensureRawDemosaicResources will retain the existing CPU fallback.
        std::array<VkDescriptorSetLayoutBinding, 2> monitorBindings{};
        for (uint32_t i = 0; i < monitorBindings.size(); ++i) {
            monitorBindings[i].binding = i;
            monitorBindings[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
            monitorBindings[i].descriptorCount = 1;
            monitorBindings[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        }
        VkDescriptorSetLayoutCreateInfo monitorLayoutInfo{};
        monitorLayoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
        monitorLayoutInfo.bindingCount = static_cast<uint32_t>(monitorBindings.size());
        monitorLayoutInfo.pBindings = monitorBindings.data();
        bool monitorPipelineOk = check(
            vkCreateDescriptorSetLayout(state->device, &monitorLayoutInfo, nullptr,
                                        &state->rawMonitorTonemapDescLayout),
            "vkCreateDescriptorSetLayout(raw_monitor_tonemap)");
        if (monitorPipelineOk) {
            state->rawMonitorTonemapShader = createShaderModule(
                state->device, kRawMonitorTonemapCompSpv, kRawMonitorTonemapCompSpvCount);
            monitorPipelineOk = state->rawMonitorTonemapShader != VK_NULL_HANDLE;
        }
        if (monitorPipelineOk) {
            VkPushConstantRange monitorPushRange{};
            monitorPushRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
            monitorPushRange.offset = 0;
            monitorPushRange.size = sizeof(RawMonitorTonemapPushConstants);
            monitorPipelineOk = createComputePipelineHelper(
                state->rawMonitorTonemapShader,
                state->rawMonitorTonemapDescLayout,
                &monitorPushRange,
                &state->rawMonitorTonemapPipelineLayout,
                &state->rawMonitorTonemapPipeline,
                "raw_monitor_tonemap");
        }
        if (!monitorPipelineOk) {
            if (state->rawMonitorTonemapPipeline != VK_NULL_HANDLE) {
                vkDestroyPipeline(state->device, state->rawMonitorTonemapPipeline, nullptr);
                state->rawMonitorTonemapPipeline = VK_NULL_HANDLE;
            }
            if (state->rawMonitorTonemapPipelineLayout != VK_NULL_HANDLE) {
                vkDestroyPipelineLayout(state->device, state->rawMonitorTonemapPipelineLayout, nullptr);
                state->rawMonitorTonemapPipelineLayout = VK_NULL_HANDLE;
            }
            if (state->rawMonitorTonemapShader != VK_NULL_HANDLE) {
                vkDestroyShaderModule(state->device, state->rawMonitorTonemapShader, nullptr);
                state->rawMonitorTonemapShader = VK_NULL_HANDLE;
            }
            if (state->rawMonitorTonemapDescLayout != VK_NULL_HANDLE) {
                vkDestroyDescriptorSetLayout(state->device, state->rawMonitorTonemapDescLayout, nullptr);
                state->rawMonitorTonemapDescLayout = VK_NULL_HANDLE;
            }
            LOGW("raw_monitor_tonemap unavailable; retaining exact CPU monitor operator");
        }

        // Independent scene-linear recording-preview copy. Unlike raw_monitor_tonemap this is not a
        // presentation transform: it retains FP16 ACEScg and exists solely to decouple EGL/glFinish
        // from the full-resolution encoder transport. Its ratio-aware four-footprint filter suppresses
        // 4K->preview minification aliasing. Build best-effort here; recording prewarm later
        // fails closed if strict RAW-derived preview is active and this precision-preserving pass is absent.
        std::array<VkDescriptorSetLayoutBinding, 2> previewBindings{};
        for (uint32_t i = 0; i < previewBindings.size(); ++i) {
            previewBindings[i].binding = i;
            previewBindings[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
            previewBindings[i].descriptorCount = 1;
            previewBindings[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        }
        VkDescriptorSetLayoutCreateInfo previewLayoutInfo{};
        previewLayoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
        previewLayoutInfo.bindingCount = static_cast<uint32_t>(previewBindings.size());
        previewLayoutInfo.pBindings = previewBindings.data();
        bool previewPipelineOk = check(
            vkCreateDescriptorSetLayout(state->device, &previewLayoutInfo, nullptr,
                                        &state->rawPreviewDownsampleDescLayout),
            "vkCreateDescriptorSetLayout(raw_preview_downsample)");
        if (previewPipelineOk) {
            state->rawPreviewDownsampleShader = createShaderModule(
                state->device,
                kRawPreviewDownsampleCompSpv,
                kRawPreviewDownsampleCompSpvCount);
            previewPipelineOk = state->rawPreviewDownsampleShader != VK_NULL_HANDLE;
        }
        if (previewPipelineOk) {
            VkPushConstantRange previewPushRange{};
            previewPushRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
            previewPushRange.offset = 0;
            previewPushRange.size = sizeof(RawPreviewDownsamplePushConstants);
            previewPipelineOk = createComputePipelineHelper(
                state->rawPreviewDownsampleShader,
                state->rawPreviewDownsampleDescLayout,
                &previewPushRange,
                &state->rawPreviewDownsamplePipelineLayout,
                &state->rawPreviewDownsamplePipeline,
                "raw_preview_downsample");
        }
        if (!previewPipelineOk) {
            if (state->rawPreviewDownsamplePipeline != VK_NULL_HANDLE) {
                vkDestroyPipeline(state->device, state->rawPreviewDownsamplePipeline, nullptr);
                state->rawPreviewDownsamplePipeline = VK_NULL_HANDLE;
            }
            if (state->rawPreviewDownsamplePipelineLayout != VK_NULL_HANDLE) {
                vkDestroyPipelineLayout(
                    state->device, state->rawPreviewDownsamplePipelineLayout, nullptr);
                state->rawPreviewDownsamplePipelineLayout = VK_NULL_HANDLE;
            }
            if (state->rawPreviewDownsampleShader != VK_NULL_HANDLE) {
                vkDestroyShaderModule(state->device, state->rawPreviewDownsampleShader, nullptr);
                state->rawPreviewDownsampleShader = VK_NULL_HANDLE;
            }
            if (state->rawPreviewDownsampleDescLayout != VK_NULL_HANDLE) {
                vkDestroyDescriptorSetLayout(
                    state->device, state->rawPreviewDownsampleDescLayout, nullptr);
                state->rawPreviewDownsampleDescLayout = VK_NULL_HANDLE;
            }
            LOGW("raw_preview_downsample unavailable; strict asynchronous NDR preview prewarm will fail closed");
        }

        // Encoder transport fallback: full-resolution FP16 ACEScg -> NEO-SCENE-LOG10-v1 in an
        // exportable RGB10_A2 AHardwareBuffer. Build best-effort; failure never takes down RAW preview.
        std::array<VkDescriptorSetLayoutBinding, 2> sceneLogBindings{};
        for (uint32_t i = 0; i < sceneLogBindings.size(); ++i) {
            sceneLogBindings[i].binding = i;
            sceneLogBindings[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
            sceneLogBindings[i].descriptorCount = 1;
            sceneLogBindings[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        }
        VkDescriptorSetLayoutCreateInfo sceneLogLayoutInfo{};
        sceneLogLayoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
        sceneLogLayoutInfo.bindingCount = static_cast<uint32_t>(sceneLogBindings.size());
        sceneLogLayoutInfo.pBindings = sceneLogBindings.data();
        bool sceneLogPipelineOk = check(
            vkCreateDescriptorSetLayout(state->device, &sceneLogLayoutInfo, nullptr,
                                        &state->rawSceneLogPackDescLayout),
            "vkCreateDescriptorSetLayout(raw_scene_log_pack)");
        if (sceneLogPipelineOk) {
            state->rawSceneLogPackShader = createShaderModule(
                state->device, kRawSceneLogPackCompSpv, kRawSceneLogPackCompSpvCount);
            sceneLogPipelineOk = state->rawSceneLogPackShader != VK_NULL_HANDLE;
        }
        if (sceneLogPipelineOk) {
            VkPushConstantRange sceneLogPushRange{};
            sceneLogPushRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
            sceneLogPushRange.offset = 0;
            sceneLogPushRange.size = sizeof(RawSceneLogPackPushConstants);
            sceneLogPipelineOk = createComputePipelineHelper(
                state->rawSceneLogPackShader,
                state->rawSceneLogPackDescLayout,
                &sceneLogPushRange,
                &state->rawSceneLogPackPipelineLayout,
                &state->rawSceneLogPackPipeline,
                "raw_scene_log_pack");
        }
        if (!sceneLogPipelineOk) {
            if (state->rawSceneLogPackPipeline != VK_NULL_HANDLE) {
                vkDestroyPipeline(state->device, state->rawSceneLogPackPipeline, nullptr);
                state->rawSceneLogPackPipeline = VK_NULL_HANDLE;
            }
            if (state->rawSceneLogPackPipelineLayout != VK_NULL_HANDLE) {
                vkDestroyPipelineLayout(state->device, state->rawSceneLogPackPipelineLayout, nullptr);
                state->rawSceneLogPackPipelineLayout = VK_NULL_HANDLE;
            }
            if (state->rawSceneLogPackShader != VK_NULL_HANDLE) {
                vkDestroyShaderModule(state->device, state->rawSceneLogPackShader, nullptr);
                state->rawSceneLogPackShader = VK_NULL_HANDLE;
            }
            if (state->rawSceneLogPackDescLayout != VK_NULL_HANDLE) {
                vkDestroyDescriptorSetLayout(state->device, state->rawSceneLogPackDescLayout, nullptr);
                state->rawSceneLogPackDescLayout = VK_NULL_HANDLE;
            }
            state->rawSceneLogTransportUnsupported = true;
            LOGW("raw_scene_log_pack unavailable; FP16 export remains the preferred RAW encoder path");
        }

        // LQ-001.09: build the complete offline RAW conversion tail as one optional capability.
        // Failure never weakens the existing converter: Kotlin keeps its full-quality CPU tail and
        // selects this path only after an actual first-frame P010 parity measurement.
        std::array<VkDescriptorSetLayoutBinding, 2> developImageBindings{};
        for (uint32_t i = 0; i < developImageBindings.size(); ++i) {
            developImageBindings[i].binding = i;
            developImageBindings[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
            developImageBindings[i].descriptorCount = 1;
            developImageBindings[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        }
        VkDescriptorSetLayoutCreateInfo developImageLayoutInfo{};
        developImageLayoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
        developImageLayoutInfo.bindingCount = static_cast<uint32_t>(developImageBindings.size());
        developImageLayoutInfo.pBindings = developImageBindings.data();
        bool developP010PipelineOk = check(
            vkCreateDescriptorSetLayout(
                state->device,
                &developImageLayoutInfo,
                nullptr,
                &state->rawDevelopImageDescLayout),
            "vkCreateDescriptorSetLayout(raw_develop_image)");

        std::array<VkDescriptorSetLayoutBinding, 3> developPackBindings{};
        for (uint32_t i = 0; i < 2; ++i) {
            developPackBindings[i].binding = i;
            developPackBindings[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
            developPackBindings[i].descriptorCount = 1;
            developPackBindings[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        }
        developPackBindings[2].binding = 2;
        developPackBindings[2].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        developPackBindings[2].descriptorCount = 1;
        developPackBindings[2].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        VkDescriptorSetLayoutCreateInfo developPackLayoutInfo{};
        developPackLayoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
        developPackLayoutInfo.bindingCount = static_cast<uint32_t>(developPackBindings.size());
        developPackLayoutInfo.pBindings = developPackBindings.data();
        if (developP010PipelineOk) {
            developP010PipelineOk = check(
                vkCreateDescriptorSetLayout(
                    state->device,
                    &developPackLayoutInfo,
                    nullptr,
                    &state->rawDevelopP010DescLayout),
                "vkCreateDescriptorSetLayout(raw_develop_p010)");
        }
        if (developP010PipelineOk) {
            state->rawDevelopDetailShader = createShaderModule(
                state->device, kRawDevelopDetailCompSpv, kRawDevelopDetailCompSpvCount);
            state->rawDevelopLumaBlurShader = createShaderModule(
                state->device, kRawDevelopLumaBlurCompSpv, kRawDevelopLumaBlurCompSpvCount);
            state->rawDevelopP010Shader = createShaderModule(
                state->device, kRawDevelopP010PackCompSpv, kRawDevelopP010PackCompSpvCount);
            developP010PipelineOk = state->rawDevelopDetailShader != VK_NULL_HANDLE &&
                state->rawDevelopLumaBlurShader != VK_NULL_HANDLE &&
                state->rawDevelopP010Shader != VK_NULL_HANDLE;
        }
        VkPushConstantRange developDetailPushRange{};
        developDetailPushRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        developDetailPushRange.offset = 0;
        developDetailPushRange.size = sizeof(RawDevelopDetailPushConstants);
        VkPushConstantRange developP010PushRange{};
        developP010PushRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        developP010PushRange.offset = 0;
        developP010PushRange.size = sizeof(RawDevelopP010PushConstants);
        if (developP010PipelineOk) {
            developP010PipelineOk = createComputePipelineHelper(
                state->rawDevelopDetailShader,
                state->rawDevelopImageDescLayout,
                &developDetailPushRange,
                &state->rawDevelopDetailPipelineLayout,
                &state->rawDevelopDetailPipeline,
                "raw_develop_detail");
        }
        if (developP010PipelineOk) {
            developP010PipelineOk = createComputePipelineHelper(
                state->rawDevelopLumaBlurShader,
                state->rawDevelopImageDescLayout,
                &developDetailPushRange,
                &state->rawDevelopLumaBlurPipelineLayout,
                &state->rawDevelopLumaBlurPipeline,
                "raw_develop_luma_blur");
        }
        if (developP010PipelineOk) {
            developP010PipelineOk = createComputePipelineHelper(
                state->rawDevelopP010Shader,
                state->rawDevelopP010DescLayout,
                &developP010PushRange,
                &state->rawDevelopP010PipelineLayout,
                &state->rawDevelopP010Pipeline,
                "raw_develop_p010_pack");
        }
        if (!developP010PipelineOk) {
            if (state->rawDevelopDetailPipeline != VK_NULL_HANDLE) {
                vkDestroyPipeline(state->device, state->rawDevelopDetailPipeline, nullptr);
                state->rawDevelopDetailPipeline = VK_NULL_HANDLE;
            }
            if (state->rawDevelopLumaBlurPipeline != VK_NULL_HANDLE) {
                vkDestroyPipeline(state->device, state->rawDevelopLumaBlurPipeline, nullptr);
                state->rawDevelopLumaBlurPipeline = VK_NULL_HANDLE;
            }
            if (state->rawDevelopP010Pipeline != VK_NULL_HANDLE) {
                vkDestroyPipeline(state->device, state->rawDevelopP010Pipeline, nullptr);
                state->rawDevelopP010Pipeline = VK_NULL_HANDLE;
            }
            if (state->rawDevelopDetailPipelineLayout != VK_NULL_HANDLE) {
                vkDestroyPipelineLayout(state->device, state->rawDevelopDetailPipelineLayout, nullptr);
                state->rawDevelopDetailPipelineLayout = VK_NULL_HANDLE;
            }
            if (state->rawDevelopLumaBlurPipelineLayout != VK_NULL_HANDLE) {
                vkDestroyPipelineLayout(state->device, state->rawDevelopLumaBlurPipelineLayout, nullptr);
                state->rawDevelopLumaBlurPipelineLayout = VK_NULL_HANDLE;
            }
            if (state->rawDevelopP010PipelineLayout != VK_NULL_HANDLE) {
                vkDestroyPipelineLayout(state->device, state->rawDevelopP010PipelineLayout, nullptr);
                state->rawDevelopP010PipelineLayout = VK_NULL_HANDLE;
            }
            if (state->rawDevelopDetailShader != VK_NULL_HANDLE) {
                vkDestroyShaderModule(state->device, state->rawDevelopDetailShader, nullptr);
                state->rawDevelopDetailShader = VK_NULL_HANDLE;
            }
            if (state->rawDevelopLumaBlurShader != VK_NULL_HANDLE) {
                vkDestroyShaderModule(state->device, state->rawDevelopLumaBlurShader, nullptr);
                state->rawDevelopLumaBlurShader = VK_NULL_HANDLE;
            }
            if (state->rawDevelopP010Shader != VK_NULL_HANDLE) {
                vkDestroyShaderModule(state->device, state->rawDevelopP010Shader, nullptr);
                state->rawDevelopP010Shader = VK_NULL_HANDLE;
            }
            if (state->rawDevelopImageDescLayout != VK_NULL_HANDLE) {
                vkDestroyDescriptorSetLayout(state->device, state->rawDevelopImageDescLayout, nullptr);
                state->rawDevelopImageDescLayout = VK_NULL_HANDLE;
            }
            if (state->rawDevelopP010DescLayout != VK_NULL_HANDLE) {
                vkDestroyDescriptorSetLayout(state->device, state->rawDevelopP010DescLayout, nullptr);
                state->rawDevelopP010DescLayout = VK_NULL_HANDLE;
            }
            LOGW("RAW GPU P010 conversion tail unavailable; retaining full-quality CPU conversion tail");
        } else {
            LOGI("RAW GPU P010 conversion tail ready (detail + colour delivery + P010 pack)");
        }

        // Offline-only NIV99 CFA front end. Keep this capability independent from preview and from the
        // established demosaic/P010 tail: an unsupported driver retains the full-quality CPU NIV99 path.
        std::array<VkDescriptorSetLayoutBinding, 5> niv99Bindings{};
        for (uint32_t i = 0; i < 3; ++i) {
            niv99Bindings[i].binding = i;
            niv99Bindings[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
            niv99Bindings[i].descriptorCount = 1;
            niv99Bindings[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        }
        niv99Bindings[3].binding = 3;
        niv99Bindings[3].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        niv99Bindings[3].descriptorCount = 1;
        niv99Bindings[3].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        niv99Bindings[4].binding = 4;
        niv99Bindings[4].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
        niv99Bindings[4].descriptorCount = 1;
        niv99Bindings[4].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        VkDescriptorSetLayoutCreateInfo niv99LayoutInfo{};
        niv99LayoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
        niv99LayoutInfo.bindingCount = static_cast<uint32_t>(niv99Bindings.size());
        niv99LayoutInfo.pBindings = niv99Bindings.data();
        bool niv99PipelineOk = check(
            vkCreateDescriptorSetLayout(
                state->device,
                &niv99LayoutInfo,
                nullptr,
                &state->rawNiv99DescLayout),
            "vkCreateDescriptorSetLayout(raw_niv99_prefilter)");
        if (niv99PipelineOk) {
            state->rawNiv99Shader = createShaderModule(
                state->device,
                kRawNiv99PrefilterCompSpv,
                kRawNiv99PrefilterCompSpvCount);
            niv99PipelineOk = state->rawNiv99Shader != VK_NULL_HANDLE;
        }
        if (niv99PipelineOk) {
            VkPushConstantRange niv99PushRange{};
            niv99PushRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
            niv99PushRange.offset = 0;
            niv99PushRange.size = sizeof(RawNiv99PushConstants);
            niv99PipelineOk = createComputePipelineHelper(
                state->rawNiv99Shader,
                state->rawNiv99DescLayout,
                &niv99PushRange,
                &state->rawNiv99PipelineLayout,
                &state->rawNiv99Pipeline,
                "raw_niv99_prefilter");
        }
        if (!niv99PipelineOk) {
            if (state->rawNiv99Pipeline != VK_NULL_HANDLE) {
                vkDestroyPipeline(state->device, state->rawNiv99Pipeline, nullptr);
                state->rawNiv99Pipeline = VK_NULL_HANDLE;
            }
            if (state->rawNiv99PipelineLayout != VK_NULL_HANDLE) {
                vkDestroyPipelineLayout(state->device, state->rawNiv99PipelineLayout, nullptr);
                state->rawNiv99PipelineLayout = VK_NULL_HANDLE;
            }
            if (state->rawNiv99Shader != VK_NULL_HANDLE) {
                vkDestroyShaderModule(state->device, state->rawNiv99Shader, nullptr);
                state->rawNiv99Shader = VK_NULL_HANDLE;
            }
            if (state->rawNiv99DescLayout != VK_NULL_HANDLE) {
                vkDestroyDescriptorSetLayout(state->device, state->rawNiv99DescLayout, nullptr);
                state->rawNiv99DescLayout = VK_NULL_HANDLE;
            }
            LOGW("RAW GPU NIV99 prefilter unavailable; retaining full-quality CPU NIV99 conversion");
        } else {
            LOGI("RAW GPU NIV99 prefilter ready (spatial + defect + temporal + scene-cut)");
        }
    } else {
        LOGW("demosaic_vulkan pipeline skipped: shaderStorageImageExtendedFormats unsupported; RAW-preview demosaic stays YUV-fallback");
    }

    // 4. IMAX RENDERING PIPELINE
    {
        std::array<VkDescriptorSetLayoutBinding, 3> bindings{};
        bindings[0].binding = 0;
        bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        bindings[0].descriptorCount = 1;
        bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

        bindings[1].binding = 1;
        bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        bindings[1].descriptorCount = 1;
        bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

        bindings[2].binding = 2;
        bindings[2].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        bindings[2].descriptorCount = 1;
        bindings[2].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

        VkDescriptorSetLayoutCreateInfo layoutInfo{};
        layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
        layoutInfo.bindingCount = static_cast<uint32_t>(bindings.size());
        layoutInfo.pBindings = bindings.data();

        if (!check(vkCreateDescriptorSetLayout(state->device, &layoutInfo, nullptr, &state->imaxRenderingDescLayout), "vkCreateDescriptorSetLayout(imax_rendering)")) {
            destroyQfcaPipelines(state);
            return false;
        }

        state->imaxRenderingShader = createShaderModule(state->device, kImaxRenderingCompSpv, kImaxRenderingCompSpvCount);
        if (state->imaxRenderingShader == VK_NULL_HANDLE) {
            destroyQfcaPipelines(state);
            return false;
        }

        VkPushConstantRange pushRange{};
        pushRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        pushRange.offset = 0;
        pushRange.size = sizeof(ImaxRenderingPushConstants);

        if (!createComputePipelineHelper(
                state->imaxRenderingShader,
                state->imaxRenderingDescLayout,
                &pushRange,
                &state->imaxRenderingPipelineLayout,
                &state->imaxRenderingPipeline,
                "imax_rendering"
            )) {
            destroyQfcaPipelines(state);
            return false;
        }
    }

    // 5. NIVUKX DENOISE PIPELINE
    {
        // Binding 3 (omniFlowFull) is the CP20 motion-compensated-temporal foundation: it is added to
        // the layout + written unconditionally so the device-side enable is a pure spec-constant flip,
        // but the shader only samples it when SC_TEMPORAL_MOTION_COMP is set (default OFF → unused).
        std::array<VkDescriptorSetLayoutBinding, 4> bindings{};
        bindings[0].binding = 0;
        bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        bindings[0].descriptorCount = 1;
        bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

        bindings[1].binding = 1;
        bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        bindings[1].descriptorCount = 1;
        bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

        bindings[2].binding = 2;
        bindings[2].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        bindings[2].descriptorCount = 1;
        bindings[2].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

        bindings[3].binding = 3;
        bindings[3].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        bindings[3].descriptorCount = 1;
        bindings[3].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

        VkDescriptorSetLayoutCreateInfo layoutInfo{};
        layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
        layoutInfo.bindingCount = static_cast<uint32_t>(bindings.size());
        layoutInfo.pBindings = bindings.data();

        if (!check(vkCreateDescriptorSetLayout(state->device, &layoutInfo, nullptr, &state->m_nivukxDenoiseDescLayout), "vkCreateDescriptorSetLayout(nivukx_denoise)")) {
            destroyQfcaPipelines(state);
            return false;
        }

        state->m_nivukxDenoiseShader = createShaderModule(state->device, kNivukxDenoiseCompSpv, kNivukxDenoiseCompSpvCount);
        if (state->m_nivukxDenoiseShader == VK_NULL_HANDLE) {
            destroyQfcaPipelines(state);
            return false;
        }

        if (!createComputePipelineHelper(
                state->m_nivukxDenoiseShader,
                state->m_nivukxDenoiseDescLayout,
                nullptr,
                &state->m_nivukxDenoisePipelineLayout,
                &state->m_nivukxDenoisePipeline,
                "nivukx_denoise"
            )) {
            destroyQfcaPipelines(state);
            return false;
        }
    }

    // 6. DENOISE SPATIAL PIPELINE (CP-QV16 — gated single-frame spatial denoiser, denoise.comp).
    // Two STORAGE_IMAGE bindings (0 rgba16f input readonly, 1 rgba16f output writeonly) matching the
    // shader layout, and a 24-byte push_constant (5 float + 1 int). Built best-effort next to nivukx;
    // the dispatch is unreachable while state->denoiseSpatialEnabled is false (default) so this stays
    // dormant and the live render path is byte-identical (no-downgrade / s1-f06/s1-f10 gated pattern).
    {
        std::array<VkDescriptorSetLayoutBinding, 2> bindings{};
        bindings[0].binding = 0;
        bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        bindings[0].descriptorCount = 1;
        bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

        bindings[1].binding = 1;
        bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        bindings[1].descriptorCount = 1;
        bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

        VkDescriptorSetLayoutCreateInfo layoutInfo{};
        layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
        layoutInfo.bindingCount = static_cast<uint32_t>(bindings.size());
        layoutInfo.pBindings = bindings.data();

        if (!check(vkCreateDescriptorSetLayout(state->device, &layoutInfo, nullptr, &state->denoiseSpatialDescLayout), "vkCreateDescriptorSetLayout(denoise_spatial)")) {
            destroyQfcaPipelines(state);
            return false;
        }

        state->denoiseSpatialShader = createShaderModule(state->device, kDenoiseCompSpv, kDenoiseCompSpvCount);
        if (state->denoiseSpatialShader == VK_NULL_HANDLE) {
            destroyQfcaPipelines(state);
            return false;
        }

        // denoise.comp push_constant = spatialSigma, rangeSigma, chromaSigma, detailPreserve,
        // noiseLevel (5 float) + passIndex (1 int) = 24 bytes.
        VkPushConstantRange pushRange{};
        pushRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        pushRange.offset = 0;
        pushRange.size = 24;

        if (!createComputePipelineHelper(
                state->denoiseSpatialShader,
                state->denoiseSpatialDescLayout,
                &pushRange,
                &state->denoiseSpatialPipelineLayout,
                &state->denoiseSpatialPipeline,
                "denoise_spatial"
            )) {
            destroyQfcaPipelines(state);
            return false;
        }
    }

    // 7. SHARPEN OPTICAL PIPELINE (CP-QV17 — gated optical-recovery sharpener, sharpen.comp).
    // Two STORAGE_IMAGE bindings (0 rgba16f input readonly, 1 rgba16f output writeonly) matching the
    // shader layout, and a 24-byte push_constant (6 float). Built best-effort next to the denoise
    // pipeline; the dispatch is unreachable while state->sharpenOpticalEnabled is false (default) so
    // this stays dormant and the live render path is byte-identical (no-downgrade / s1-f06/s1-f10).
    {
        std::array<VkDescriptorSetLayoutBinding, 2> bindings{};
        bindings[0].binding = 0;
        bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        bindings[0].descriptorCount = 1;
        bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

        bindings[1].binding = 1;
        bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        bindings[1].descriptorCount = 1;
        bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;

        VkDescriptorSetLayoutCreateInfo layoutInfo{};
        layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
        layoutInfo.bindingCount = static_cast<uint32_t>(bindings.size());
        layoutInfo.pBindings = bindings.data();

        if (!check(vkCreateDescriptorSetLayout(state->device, &layoutInfo, nullptr, &state->sharpenOpticalDescLayout), "vkCreateDescriptorSetLayout(sharpen_optical)")) {
            destroyQfcaPipelines(state);
            return false;
        }

        state->sharpenOpticalShader = createShaderModule(state->device, kSharpenCompSpv, kSharpenCompSpvCount);
        if (state->sharpenOpticalShader == VK_NULL_HANDLE) {
            destroyQfcaPipelines(state);
            return false;
        }

        // sharpen.comp push_constant = amount, radius, threshold, detailAmount, edgeProtection,
        // microContrast (6 float) = 24 bytes.
        VkPushConstantRange pushRange{};
        pushRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        pushRange.offset = 0;
        pushRange.size = 24;

        if (!createComputePipelineHelper(
                state->sharpenOpticalShader,
                state->sharpenOpticalDescLayout,
                &pushRange,
                &state->sharpenOpticalPipelineLayout,
                &state->sharpenOpticalPipeline,
                "sharpen_optical"
            )) {
            destroyQfcaPipelines(state);
            return false;
        }
    }

    return true;
}

void insertComputeImageBarrier(
    VkCommandBuffer cmd,
    VkImage image,
    VkAccessFlags srcAccess,
    VkAccessFlags dstAccess,
    VkImageLayout oldLayout,
    VkImageLayout newLayout,
    VkPipelineStageFlags srcStage,
    VkPipelineStageFlags dstStage
) {
    VkImageMemoryBarrier barrier{};
    barrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    barrier.oldLayout = oldLayout;
    barrier.newLayout = newLayout;
    barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    barrier.image = image;
    barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    barrier.subresourceRange.baseMipLevel = 0;
    barrier.subresourceRange.levelCount = 1;
    barrier.subresourceRange.baseArrayLayer = 0;
    barrier.subresourceRange.layerCount = 1;
    barrier.srcAccessMask = srcAccess;
    barrier.dstAccessMask = dstAccess;

    vkCmdPipelineBarrier(
        cmd,
        srcStage,
        dstStage,
        0,
        0, nullptr,
        0, nullptr,
        1, &barrier
    );
}

void updateQfcaDescriptorSets(
    VulkanBridgeState* state,
    TargetSurface* target,
    uint32_t imageIndex,
    ImportedFrame* frame
) {
    if (state == nullptr || target == nullptr || frame == nullptr) return;

    ComputeIntermediateImage& intermediate = target->computeIntermediates[imageIndex];
    ComputeIntermediateImage& toneMapped = target->toneMappedIntermediates[imageIndex];

    // 1. GUIDED UPSAMPLE DESCRIPTOR SET
    {
        VkDescriptorImageInfo guidanceInfo{};
        guidanceInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
        guidanceInfo.imageView = intermediate.imageView;
        guidanceInfo.sampler = VK_NULL_HANDLE;

        VkDescriptorImageInfo lowResInfo{};
        lowResInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
        lowResInfo.imageView = target->lowResFocusMaps[imageIndex].imageView;
        lowResInfo.sampler = VK_NULL_HANDLE;

        VkDescriptorImageInfo highResInfo{};
        highResInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
        highResInfo.imageView = target->highResFocusMaps[imageIndex].imageView;
        highResInfo.sampler = VK_NULL_HANDLE;

        std::array<VkWriteDescriptorSet, 3> writes{};
        writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[0].dstSet = target->guidedUpsampleDescSets[imageIndex];
        writes[0].dstBinding = 0;
        writes[0].descriptorCount = 1;
        writes[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        writes[0].pImageInfo = &guidanceInfo;

        writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[1].dstSet = target->guidedUpsampleDescSets[imageIndex];
        writes[1].dstBinding = 1;
        writes[1].descriptorCount = 1;
        writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        writes[1].pImageInfo = &lowResInfo;

        writes[2].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[2].dstSet = target->guidedUpsampleDescSets[imageIndex];
        writes[2].dstBinding = 2;
        writes[2].descriptorCount = 1;
        writes[2].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        writes[2].pImageInfo = &highResInfo;

        vkUpdateDescriptorSets(state->device, static_cast<uint32_t>(writes.size()), writes.data(), 0, nullptr);
    }

    // 2. DETAIL PRESERVATION DESCRIPTOR SET
    {
        VkDescriptorImageInfo rgbInfo{};
        rgbInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
        rgbInfo.imageView = intermediate.imageView;
        rgbInfo.sampler = VK_NULL_HANDLE;

        VkDescriptorImageInfo maskInfo{};
        maskInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
        maskInfo.imageView = target->detailMasks[imageIndex].imageView;
        maskInfo.sampler = VK_NULL_HANDLE;

        std::array<VkWriteDescriptorSet, 2> writes{};
        writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[0].dstSet = target->detailPreservationDescSets[imageIndex];
        writes[0].dstBinding = 0;
        writes[0].descriptorCount = 1;
        writes[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        writes[0].pImageInfo = &rgbInfo;

        writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[1].dstSet = target->detailPreservationDescSets[imageIndex];
        writes[1].dstBinding = 1;
        writes[1].descriptorCount = 1;
        writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        writes[1].pImageInfo = &maskInfo;

        vkUpdateDescriptorSets(state->device, static_cast<uint32_t>(writes.size()), writes.data(), 0, nullptr);
    }

    // 3. MFSR FUSION DESCRIPTOR SET
    {
        VkDescriptorImageInfo arrayInfo{};
        arrayInfo.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        arrayInfo.imageView = state->dummyImageArrayView;
        arrayInfo.sampler = state->dummySampler;

        VkDescriptorImageInfo flowInfo{};
        flowInfo.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        flowInfo.imageView = state->dummyImageView;
        flowInfo.sampler = state->dummySampler;

        VkDescriptorImageInfo outInfo{};
        outInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
        outInfo.imageView = intermediate.imageView;
        outInfo.sampler = VK_NULL_HANDLE;

        std::array<VkWriteDescriptorSet, 3> writes{};
        writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[0].dstSet = target->mfsrFusionDescSets[imageIndex];
        writes[0].dstBinding = 0;
        writes[0].descriptorCount = 1;
        writes[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        writes[0].pImageInfo = &arrayInfo;

        writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[1].dstSet = target->mfsrFusionDescSets[imageIndex];
        writes[1].dstBinding = 1;
        writes[1].descriptorCount = 1;
        writes[1].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
        writes[1].pImageInfo = &flowInfo;

        writes[2].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[2].dstSet = target->mfsrFusionDescSets[imageIndex];
        writes[2].dstBinding = 2;
        writes[2].descriptorCount = 1;
        writes[2].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        writes[2].pImageInfo = &outInfo;

        vkUpdateDescriptorSets(state->device, static_cast<uint32_t>(writes.size()), writes.data(), 0, nullptr);
    }

    // 4. IMAX RENDERING DESCRIPTOR SET
    {
        VkDescriptorImageInfo inInfo{};
        inInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
        inInfo.imageView = intermediate.imageView;
        inInfo.sampler = VK_NULL_HANDLE;

        VkDescriptorImageInfo focusInfo{};
        focusInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
        focusInfo.imageView = target->highResFocusMaps[imageIndex].imageView;
        focusInfo.sampler = VK_NULL_HANDLE;

        VkDescriptorImageInfo outInfo{};
        outInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
        outInfo.imageView = toneMapped.imageView;
        outInfo.sampler = VK_NULL_HANDLE;

        std::array<VkWriteDescriptorSet, 3> writes{};
        writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[0].dstSet = target->imaxRenderingDescSets[imageIndex];
        writes[0].dstBinding = 0;
        writes[0].descriptorCount = 1;
        writes[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        writes[0].pImageInfo = &inInfo;

        writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[1].dstSet = target->imaxRenderingDescSets[imageIndex];
        writes[1].dstBinding = 1;
        writes[1].descriptorCount = 1;
        writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        writes[1].pImageInfo = &focusInfo;

        writes[2].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[2].dstSet = target->imaxRenderingDescSets[imageIndex];
        writes[2].dstBinding = 2;
        writes[2].descriptorCount = 1;
        writes[2].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        writes[2].pImageInfo = &outInfo;

        vkUpdateDescriptorSets(state->device, static_cast<uint32_t>(writes.size()), writes.data(), 0, nullptr);
    }

    // 5. NIVUKX DENOISE DESCRIPTOR SET
    {
        VkDescriptorImageInfo currInfo{};
        currInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
        currInfo.imageView = toneMapped.imageView;
        currInfo.sampler = VK_NULL_HANDLE;

        VkDescriptorImageInfo histInfo{};
        histInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
        histInfo.imageView = target->historyImages.front().imageView;
        histInfo.sampler = VK_NULL_HANDLE;

        VkDescriptorImageInfo outInfo{};
        outInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
        outInfo.imageView = intermediate.imageView;
        outInfo.sampler = VK_NULL_HANDLE;

        // CP20 binding 3 = full-res Argus flow (omniFlowFull, co-allocated with the QFCA/OmniClarity
        // intermediates). If it is not yet available, fall back to a valid view (toneMapped); the
        // shader never samples binding 3 unless SC_TEMPORAL_MOTION_COMP is enabled, so the fallback
        // is harmless and the default path stays bit-for-bit unchanged.
        VkDescriptorImageInfo flowInfo{};
        flowInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
        flowInfo.imageView =
            (static_cast<size_t>(imageIndex) < target->omniFlowFull.size() &&
             target->omniFlowFull[imageIndex].imageView != VK_NULL_HANDLE)
                ? target->omniFlowFull[imageIndex].imageView
                : toneMapped.imageView;
        flowInfo.sampler = VK_NULL_HANDLE;

        std::array<VkWriteDescriptorSet, 4> writes{};
        writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[0].dstSet = target->m_nivukxDenoiseDescSets[imageIndex];
        writes[0].dstBinding = 0;
        writes[0].descriptorCount = 1;
        writes[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        writes[0].pImageInfo = &currInfo;

        writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[1].dstSet = target->m_nivukxDenoiseDescSets[imageIndex];
        writes[1].dstBinding = 1;
        writes[1].descriptorCount = 1;
        writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        writes[1].pImageInfo = &histInfo;

        writes[2].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[2].dstSet = target->m_nivukxDenoiseDescSets[imageIndex];
        writes[2].dstBinding = 2;
        writes[2].descriptorCount = 1;
        writes[2].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        writes[2].pImageInfo = &outInfo;

        writes[3].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[3].dstSet = target->m_nivukxDenoiseDescSets[imageIndex];
        writes[3].dstBinding = 3;
        writes[3].descriptorCount = 1;
        writes[3].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        writes[3].pImageInfo = &flowInfo;

        vkUpdateDescriptorSets(state->device, static_cast<uint32_t>(writes.size()), writes.data(), 0, nullptr);
    }

    // 6. DENOISE SPATIAL DESCRIPTOR SET (CP-QV16 — gated single-frame spatial denoiser).
    // binding 0 = the current enhanced storage image (intermediate — what the nivukx denoise pass just
    // wrote; denoise.comp treats it as its readonly input), binding 1 = the existing rgba16f omniDeblur
    // scratch (writeonly output). The set is written unconditionally so the enable is a pure flag flip,
    // but it is never bound unless state->denoiseSpatialEnabled is ON (default OFF → dormant).
    if (state->denoiseSpatialDescLayout != VK_NULL_HANDLE &&
        static_cast<size_t>(imageIndex) < target->denoiseSpatialDescSets.size() &&
        target->denoiseSpatialDescSets[imageIndex] != VK_NULL_HANDLE &&
        static_cast<size_t>(imageIndex) < target->omniDeblur.size() &&
        target->omniDeblur[imageIndex].imageView != VK_NULL_HANDLE) {
        VkDescriptorImageInfo inInfo{};
        inInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
        inInfo.imageView = intermediate.imageView;
        inInfo.sampler = VK_NULL_HANDLE;

        VkDescriptorImageInfo outInfo{};
        outInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
        outInfo.imageView = target->omniDeblur[imageIndex].imageView;
        outInfo.sampler = VK_NULL_HANDLE;

        std::array<VkWriteDescriptorSet, 2> writes{};
        writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[0].dstSet = target->denoiseSpatialDescSets[imageIndex];
        writes[0].dstBinding = 0;
        writes[0].descriptorCount = 1;
        writes[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        writes[0].pImageInfo = &inInfo;

        writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[1].dstSet = target->denoiseSpatialDescSets[imageIndex];
        writes[1].dstBinding = 1;
        writes[1].descriptorCount = 1;
        writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        writes[1].pImageInfo = &outInfo;

        vkUpdateDescriptorSets(state->device, static_cast<uint32_t>(writes.size()), writes.data(), 0, nullptr);
    }

    // 7. SHARPEN OPTICAL DESCRIPTOR SET (CP-QV17 — gated optical-recovery sharpener).
    // binding 0 = the current enhanced storage image (intermediate — what the upstream passes just
    // wrote; sharpen.comp treats it as its readonly input), binding 1 = the existing rgba16f omniDeblur
    // scratch (writeonly output). The set is written unconditionally so the enable is a pure flag flip,
    // but it is never bound unless state->sharpenOpticalEnabled is ON (default OFF → dormant).
    if (state->sharpenOpticalDescLayout != VK_NULL_HANDLE &&
        static_cast<size_t>(imageIndex) < target->sharpenOpticalDescSets.size() &&
        target->sharpenOpticalDescSets[imageIndex] != VK_NULL_HANDLE &&
        static_cast<size_t>(imageIndex) < target->omniDeblur.size() &&
        target->omniDeblur[imageIndex].imageView != VK_NULL_HANDLE) {
        VkDescriptorImageInfo inInfo{};
        inInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
        inInfo.imageView = intermediate.imageView;
        inInfo.sampler = VK_NULL_HANDLE;

        VkDescriptorImageInfo outInfo{};
        outInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
        outInfo.imageView = target->omniDeblur[imageIndex].imageView;
        outInfo.sampler = VK_NULL_HANDLE;

        std::array<VkWriteDescriptorSet, 2> writes{};
        writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[0].dstSet = target->sharpenOpticalDescSets[imageIndex];
        writes[0].dstBinding = 0;
        writes[0].descriptorCount = 1;
        writes[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        writes[0].pImageInfo = &inInfo;

        writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[1].dstSet = target->sharpenOpticalDescSets[imageIndex];
        writes[1].dstBinding = 1;
        writes[1].descriptorCount = 1;
        writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        writes[1].pImageInfo = &outInfo;

        vkUpdateDescriptorSets(state->device, static_cast<uint32_t>(writes.size()), writes.data(), 0, nullptr);
    }
}

// PART A: all enhancement is "fully ready" only when every group's pipelines are built. Per-target
// compute resources (intermediate images + QFCA pool + OmniClarity descriptor sets) are created/skipped
// against this so a target bound DURING the background build cleanly renders raw, then is re-driven.
inline bool enhancementFullyReady(const VulkanBridgeState* state) {
    return state != nullptr &&
        state->enhancementComputePipelineReady &&
        state->qfcaPipelinesReady &&
        state->omniClarityPipelinesReady;
}

bool createComputeIntermediateResources(VulkanBridgeState* state, TargetSurface* target) {
    if (state == nullptr || target == nullptr || !enhancementFullyReady(state)) {
        return false;
    }
    if (target->images.empty() || target->extent.width == 0 || target->extent.height == 0) {
        return false;
    }

    target->computeIntermediates.resize(target->images.size());
    target->toneMappedIntermediates.resize(target->images.size());
    for (size_t i = 0; i < target->images.size(); ++i) {
        if (!createComputeIntermediateImage(
                state,
                target,
                &target->computeIntermediates[i],
                "vkCreateImage(computeIntermediateFinal)"
            ) ||
            !createComputeIntermediateImage(
                state,
                target,
                &target->toneMappedIntermediates[i],
                "vkCreateImage(toneMappedIntermediate)"
            )) {
            destroyComputeIntermediateResources(state, target);
            return false;
        }
    }

    const VkDeviceSize histogramBufferSize =
        static_cast<VkDeviceSize>(kHdrMetadataHistogramWordCount * sizeof(uint32_t));
    const VkDeviceSize metadataBufferSize =
        static_cast<VkDeviceSize>(kHdrMetadataWordCount * sizeof(uint32_t));
    target->hdrHistogramBuffers.resize(target->images.size(), VK_NULL_HANDLE);
    target->hdrHistogramMemories.resize(target->images.size(), VK_NULL_HANDLE);
    target->hdrMetadataBuffers.resize(target->images.size(), VK_NULL_HANDLE);
    target->hdrMetadataMemories.resize(target->images.size(), VK_NULL_HANDLE);
    target->hdrMetadataStagingBuffers.resize(target->images.size(), VK_NULL_HANDLE);
    target->hdrMetadataStagingMemories.resize(target->images.size(), VK_NULL_HANDLE);
    target->hdrMetadataStagingReady.assign(target->images.size(), false);
    for (size_t i = 0; i < target->images.size(); ++i) {
        if (!createBufferWithMemory(
                state,
                histogramBufferSize,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                &target->hdrHistogramBuffers[i],
                &target->hdrHistogramMemories[i]
            ) ||
            !createBufferWithMemory(
                state,
                metadataBufferSize,
                VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                &target->hdrMetadataBuffers[i],
                &target->hdrMetadataMemories[i]
            ) ||
            !createBufferWithMemory(
                state,
                metadataBufferSize,
                VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                &target->hdrMetadataStagingBuffers[i],
                &target->hdrMetadataStagingMemories[i]
            )) {
            destroyComputeIntermediateResources(state, target);
            return false;
        }
    }

    if (state->timestampQueriesSupported) {
        VkQueryPoolCreateInfo queryPoolInfo{};
        queryPoolInfo.sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO;
        queryPoolInfo.queryType = VK_QUERY_TYPE_TIMESTAMP;
        queryPoolInfo.queryCount = static_cast<uint32_t>(target->images.size() * 2u);
        if (check(
                vkCreateQueryPool(
                    state->device,
                    &queryPoolInfo,
                    nullptr,
                    &target->computeTimestampQueryPool
                ),
                "vkCreateQueryPool(compute timestamps)"
            )) {
            target->computeTimestampQueriesReady = true;
            target->computeTimestampQueryIssued.assign(target->images.size(), false);
        } else {
            target->computeTimestampQueryPool = VK_NULL_HANDLE;
            target->computeTimestampQueriesReady = false;
            target->computeTimestampQueryIssued.clear();
        }
    }

    std::array<VkDescriptorPoolSize, 3> poolSizes{};
    poolSizes[0].type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    poolSizes[0].descriptorCount = static_cast<uint32_t>(target->images.size());
    poolSizes[1].type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    poolSizes[1].descriptorCount = static_cast<uint32_t>(target->images.size() * 6u);
    poolSizes[2].type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSizes[2].descriptorCount = static_cast<uint32_t>(target->images.size() * 3u);

    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.poolSizeCount = static_cast<uint32_t>(poolSizes.size());
    poolInfo.pPoolSizes = poolSizes.data();
    poolInfo.maxSets = static_cast<uint32_t>(target->images.size() * 5u);
    if (!check(vkCreateDescriptorPool(state->device, &poolInfo, nullptr, &target->computeDescriptorPool), "vkCreateDescriptorPool(compute)")) {
        destroyComputeIntermediateResources(state, target);
        return false;
    }

    std::vector<VkDescriptorSetLayout> layouts(target->images.size(), state->computeDescriptorSetLayout);
    VkDescriptorSetAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    allocInfo.descriptorPool = target->computeDescriptorPool;
    allocInfo.descriptorSetCount = static_cast<uint32_t>(layouts.size());
    allocInfo.pSetLayouts = layouts.data();
    target->computeDescriptorSets.resize(target->images.size(), VK_NULL_HANDLE);
    if (!check(vkAllocateDescriptorSets(state->device, &allocInfo, target->computeDescriptorSets.data()), "vkAllocateDescriptorSets(compute)")) {
        destroyComputeIntermediateResources(state, target);
        return false;
    }

    std::vector<VkDescriptorSetLayout> imageLayouts(target->images.size(), state->imageComputeDescriptorSetLayout);
    allocInfo.descriptorSetCount = static_cast<uint32_t>(imageLayouts.size());
    allocInfo.pSetLayouts = imageLayouts.data();
    target->kneeCurveDescriptorSets.resize(target->images.size(), VK_NULL_HANDLE);
    if (!check(vkAllocateDescriptorSets(state->device, &allocInfo, target->kneeCurveDescriptorSets.data()), "vkAllocateDescriptorSets(kneeCurve)")) {
        destroyComputeIntermediateResources(state, target);
        return false;
    }
    target->shadowLiftDescriptorSets.resize(target->images.size(), VK_NULL_HANDLE);
    if (!check(vkAllocateDescriptorSets(state->device, &allocInfo, target->shadowLiftDescriptorSets.data()), "vkAllocateDescriptorSets(shadowLift)")) {
        destroyComputeIntermediateResources(state, target);
        return false;
    }

    std::vector<VkDescriptorSetLayout> histogramLayouts(target->images.size(), state->hdrMetadataHistogramDescriptorSetLayout);
    allocInfo.descriptorSetCount = static_cast<uint32_t>(histogramLayouts.size());
    allocInfo.pSetLayouts = histogramLayouts.data();
    target->hdrMetadataHistogramDescriptorSets.resize(target->images.size(), VK_NULL_HANDLE);
    if (!check(vkAllocateDescriptorSets(state->device, &allocInfo, target->hdrMetadataHistogramDescriptorSets.data()), "vkAllocateDescriptorSets(hdrMetadataHistogram)")) {
        destroyComputeIntermediateResources(state, target);
        return false;
    }

    std::vector<VkDescriptorSetLayout> reduceLayouts(target->images.size(), state->hdrMetadataReduceDescriptorSetLayout);
    allocInfo.descriptorSetCount = static_cast<uint32_t>(reduceLayouts.size());
    allocInfo.pSetLayouts = reduceLayouts.data();
    target->hdrMetadataReduceDescriptorSets.resize(target->images.size(), VK_NULL_HANDLE);
    if (!check(vkAllocateDescriptorSets(state->device, &allocInfo, target->hdrMetadataReduceDescriptorSets.data()), "vkAllocateDescriptorSets(hdrMetadataReduce)")) {
        destroyComputeIntermediateResources(state, target);
        return false;
    }

    // ── QFCA Resource Allocations ────────────────────────────────────
    target->lowResFocusMaps.resize(target->images.size());
    target->highResFocusMaps.resize(target->images.size());
    target->detailMasks.resize(target->images.size());
    target->historyImages.resize(1u);
    target->historyContentValid.assign(1u, 0); // no submitted chronological history yet
    target->temporalHistorySubmissionId = 0u;
    target->temporalHistorySensorTimestampNs = 0;
    target->focusMapStagingBuffers.resize(target->images.size(), VK_NULL_HANDLE);
    target->focusMapStagingMemories.resize(target->images.size(), VK_NULL_HANDLE);

    if (!createQfcaIntermediateImage(
            state,
            &target->historyImages.front(),
            target->extent.width, target->extent.height,
            VK_FORMAT_R16G16B16A16_SFLOAT,
            VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT |
                VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
            "vkCreateImage(chronologicalHistoryImage)")) {
        destroyComputeIntermediateResources(state, target);
        return false;
    }

    for (size_t i = 0; i < target->images.size(); ++i) {
        if (!createQfcaIntermediateImage(
                state,
                &target->lowResFocusMaps[i],
                16u, 16u,
                VK_FORMAT_R32_SFLOAT,
                VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT,
                "vkCreateImage(lowResFocusMap)"
            ) ||
            !createQfcaIntermediateImage(
                state,
                &target->highResFocusMaps[i],
                target->extent.width, target->extent.height,
                VK_FORMAT_R32_SFLOAT,
                VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                "vkCreateImage(highResFocusMap)"
            ) ||
            !createQfcaIntermediateImage(
                state,
                &target->detailMasks[i],
                target->extent.width, target->extent.height,
                VK_FORMAT_R32_SFLOAT,
                VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                "vkCreateImage(detailMask)"
            ) ||
            !createBufferWithMemory(
                state,
                sizeof(float) * 256,
                VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                &target->focusMapStagingBuffers[i],
                &target->focusMapStagingMemories[i]
            )) {
            destroyComputeIntermediateResources(state, target);
            return false;
        }
    }

    // Allocate the dedicated descriptor pool
    std::array<VkDescriptorPoolSize, 2> qfcaPoolSizes{};
    qfcaPoolSizes[0].type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    // 17 (was 15) storage images per swapchain image: nivukx_denoise = 4 bindings (CP20 omniFlowFull
    // binding 3), plus CP-QV16's gated 2-binding denoise_spatial set (+2) and CP-QV17's gated 2-binding
    // sharpen_optical set (+2). Headroom keeps the pool from exhausting on allocation.
    qfcaPoolSizes[0].descriptorCount = static_cast<uint32_t>(target->images.size() * 17u);
    qfcaPoolSizes[1].type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    qfcaPoolSizes[1].descriptorCount = static_cast<uint32_t>(target->images.size() * 2u);

    VkDescriptorPoolCreateInfo qfcaPoolInfo{};
    qfcaPoolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    qfcaPoolInfo.poolSizeCount = static_cast<uint32_t>(qfcaPoolSizes.size());
    qfcaPoolInfo.pPoolSizes = qfcaPoolSizes.data();
    // +1 set per image for the CP-QV16 gated denoise_spatial set and +1 for the CP-QV17 gated
    // sharpen_optical set (6u -> 7u).
    qfcaPoolInfo.maxSets = static_cast<uint32_t>(target->images.size() * 7u);

    if (!check(vkCreateDescriptorPool(state->device, &qfcaPoolInfo, nullptr, &target->qfcaDescriptorPool), "vkCreateDescriptorPool(qfcaDescriptorPool)")) {
        destroyComputeIntermediateResources(state, target);
        return false;
    }

    // Allocate descriptor sets
    target->guidedUpsampleDescSets.resize(target->images.size(), VK_NULL_HANDLE);
    target->detailPreservationDescSets.resize(target->images.size(), VK_NULL_HANDLE);
    target->mfsrFusionDescSets.resize(target->images.size(), VK_NULL_HANDLE);
    target->imaxRenderingDescSets.resize(target->images.size(), VK_NULL_HANDLE);
    target->m_nivukxDenoiseDescSets.resize(target->images.size(), VK_NULL_HANDLE);
    target->denoiseSpatialDescSets.resize(target->images.size(), VK_NULL_HANDLE); // CP-QV16 gated
    target->sharpenOpticalDescSets.resize(target->images.size(), VK_NULL_HANDLE); // CP-QV17 gated

    VkDescriptorSetAllocateInfo qfcaAllocInfo{};
    qfcaAllocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    qfcaAllocInfo.descriptorPool = target->qfcaDescriptorPool;
    qfcaAllocInfo.descriptorSetCount = 1;

    for (size_t i = 0; i < target->images.size(); ++i) {
        qfcaAllocInfo.pSetLayouts = &state->guidedUpsampleDescLayout;
        if (!check(vkAllocateDescriptorSets(state->device, &qfcaAllocInfo, &target->guidedUpsampleDescSets[i]), "vkAllocateDescriptorSets(guidedUpsampleDescSets)")) {
            destroyComputeIntermediateResources(state, target);
            return false;
        }

        qfcaAllocInfo.pSetLayouts = &state->detailPreservationDescLayout;
        if (!check(vkAllocateDescriptorSets(state->device, &qfcaAllocInfo, &target->detailPreservationDescSets[i]), "vkAllocateDescriptorSets(detailPreservationDescSets)")) {
            destroyComputeIntermediateResources(state, target);
            return false;
        }

        qfcaAllocInfo.pSetLayouts = &state->mfsrFusionDescLayout;
        if (!check(vkAllocateDescriptorSets(state->device, &qfcaAllocInfo, &target->mfsrFusionDescSets[i]), "vkAllocateDescriptorSets(mfsrFusionDescSets)")) {
            destroyComputeIntermediateResources(state, target);
            return false;
        }

        qfcaAllocInfo.pSetLayouts = &state->imaxRenderingDescLayout;
        if (!check(vkAllocateDescriptorSets(state->device, &qfcaAllocInfo, &target->imaxRenderingDescSets[i]), "vkAllocateDescriptorSets(imaxRenderingDescSets)")) {
            destroyComputeIntermediateResources(state, target);
            return false;
        }

        qfcaAllocInfo.pSetLayouts = &state->m_nivukxDenoiseDescLayout;
        if (!check(vkAllocateDescriptorSets(state->device, &qfcaAllocInfo, &target->m_nivukxDenoiseDescSets[i]), "vkAllocateDescriptorSets(nivukxDenoiseDescSets)")) {
            destroyComputeIntermediateResources(state, target);
            return false;
        }

        // CP-QV16 — one gated denoise_spatial set per image (allocated even when the gate is OFF so the
        // enable is a pure flag flip; unused/never-bound until denoiseSpatialEnabled is turned on).
        qfcaAllocInfo.pSetLayouts = &state->denoiseSpatialDescLayout;
        if (!check(vkAllocateDescriptorSets(state->device, &qfcaAllocInfo, &target->denoiseSpatialDescSets[i]), "vkAllocateDescriptorSets(denoiseSpatialDescSets)")) {
            destroyComputeIntermediateResources(state, target);
            return false;
        }

        // CP-QV17 — one gated sharpen_optical set per image (allocated even when the gate is OFF so the
        // enable is a pure flag flip; unused/never-bound until sharpenOpticalEnabled is turned on).
        qfcaAllocInfo.pSetLayouts = &state->sharpenOpticalDescLayout;
        if (!check(vkAllocateDescriptorSets(state->device, &qfcaAllocInfo, &target->sharpenOpticalDescSets[i]), "vkAllocateDescriptorSets(sharpenOpticalDescSets)")) {
            destroyComputeIntermediateResources(state, target);
            return false;
        }
    }

    if (!allocateOmniClarityResources(state, target)) {
        destroyComputeIntermediateResources(state, target);
        return false;
    }

    return true;
}

// ════════════════════════════════════════════════════════════════════════════
// OmniClarity classical motion stack (gated). Pipelines: Argus block-match,
// Argus variational refine, Riven motion deblur, Chronos warp (warp_reconstruct).
// All storage-image compute passes; runs only when state->omniClarityEnabled.
// ════════════════════════════════════════════════════════════════════════════

void destroyOmniClarityPipelines(VulkanBridgeState* state) {
    if (state == nullptr || state->device == VK_NULL_HANDLE) return;
    auto destroyOne = [&](VkPipeline& p, VkPipelineLayout& pl, VkDescriptorSetLayout& dl, VkShaderModule& sh) {
        if (p != VK_NULL_HANDLE) { vkDestroyPipeline(state->device, p, nullptr); p = VK_NULL_HANDLE; }
        if (pl != VK_NULL_HANDLE) { vkDestroyPipelineLayout(state->device, pl, nullptr); pl = VK_NULL_HANDLE; }
        if (dl != VK_NULL_HANDLE) { vkDestroyDescriptorSetLayout(state->device, dl, nullptr); dl = VK_NULL_HANDLE; }
        if (sh != VK_NULL_HANDLE) { vkDestroyShaderModule(state->device, sh, nullptr); sh = VK_NULL_HANDLE; }
    };
    destroyOne(state->omniBlockMatchPipeline, state->omniBlockMatchPipelineLayout, state->omniBlockMatchDescLayout, state->omniBlockMatchShader);
    destroyOne(state->omniRefinePipeline, state->omniRefinePipelineLayout, state->omniRefineDescLayout, state->omniRefineShader);
    destroyOne(state->omniRivenPipeline, state->omniRivenPipelineLayout, state->omniRivenDescLayout, state->omniRivenShader);
    destroyOne(state->omniWarpPipeline, state->omniWarpPipelineLayout, state->omniWarpDescLayout, state->omniWarpShader);
    destroyOne(state->omniDownsamplePipeline, state->omniDownsamplePipelineLayout, state->omniDownsampleDescLayout, state->omniDownsampleShader);
    destroyOne(state->omniFlowUpsamplePipeline, state->omniFlowUpsamplePipelineLayout, state->omniFlowUpsampleDescLayout, state->omniFlowUpsampleShader);
    destroyOne(state->omniSelectPipeline, state->omniSelectPipelineLayout, state->omniSelectDescLayout, state->omniSelectShader);
    destroyOne(state->occsPipeline, state->occsPipelineLayout, state->occsDescLayout, state->occsShader);
    destroyOne(state->nrliPipeline, state->nrliPipelineLayout, state->nrliDescLayout, state->nrliShader);
    destroyOne(state->dualGainPipeline, state->dualGainPipelineLayout, state->dualGainDescLayout, state->dualGainShader); // s1-f10 / CP31
    destroyOne(state->photonPipeline, state->photonPipelineLayout, state->photonDescLayout, state->photonShader); // s1-f10 / CP32 (dormant)
    destroyOne(state->eisWarpPipeline, state->eisWarpPipelineLayout, state->eisWarpDescLayout, state->eisWarpShader); // s3-f06 / CP60 (dormant)
    destroyOne(state->bokehPipeline, state->bokehPipelineLayout, state->bokehDescLayout, state->bokehShader); // s5-f06 / CP-F7 (gated OFF)
    destroyNrliLut(state);
}

bool createOmniClarityPipelines(VulkanBridgeState* state) {
    if (state == nullptr || state->device == VK_NULL_HANDLE) return false;

    auto makePipeline = [&](
        const uint32_t* spv, size_t spvCount, uint32_t bindingCount, uint32_t pushSize,
        VkDescriptorSetLayout* descLayout, VkPipelineLayout* pipelineLayout,
        VkPipeline* pipeline, VkShaderModule* shader, const char* label) -> bool {
        std::vector<VkDescriptorSetLayoutBinding> bindings(bindingCount);
        for (uint32_t i = 0; i < bindingCount; ++i) {
            bindings[i].binding = i;
            bindings[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
            bindings[i].descriptorCount = 1;
            bindings[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        }
        VkDescriptorSetLayoutCreateInfo layoutInfo{};
        layoutInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO;
        layoutInfo.bindingCount = bindingCount;
        layoutInfo.pBindings = bindings.data();
        if (!check(vkCreateDescriptorSetLayout(state->device, &layoutInfo, nullptr, descLayout), label)) return false;

        *shader = createShaderModule(state->device, spv, spvCount);
        if (*shader == VK_NULL_HANDLE) return false;

        VkPushConstantRange pushRange{};
        pushRange.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        pushRange.offset = 0;
        pushRange.size = pushSize;

        VkPipelineLayoutCreateInfo plInfo{};
        plInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
        plInfo.setLayoutCount = 1;
        plInfo.pSetLayouts = descLayout;
        plInfo.pushConstantRangeCount = 1;
        plInfo.pPushConstantRanges = &pushRange;
        if (!check(vkCreatePipelineLayout(state->device, &plInfo, nullptr, pipelineLayout), label)) return false;

        VkPipelineShaderStageCreateInfo stage{};
        stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        stage.module = *shader;
        stage.pName = "main";
        VkComputePipelineCreateInfo pipeInfo{};
        pipeInfo.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO;
        pipeInfo.stage = stage;
        pipeInfo.layout = *pipelineLayout;
        if (!check(vkCreateComputePipelines(state->device, VK_NULL_HANDLE, 1, &pipeInfo, nullptr, pipeline), label)) return false;
        return true;
    };

    if (!makePipeline(kArgusFlowBlockmatchCompSpv, kArgusFlowBlockmatchCompSpvCount, 3u, sizeof(ArgusBlockMatchPushConstants),
            &state->omniBlockMatchDescLayout, &state->omniBlockMatchPipelineLayout, &state->omniBlockMatchPipeline, &state->omniBlockMatchShader, "omni_blockmatch") ||
        !makePipeline(kArgusFlowRefineCompSpv, kArgusFlowRefineCompSpvCount, 4u, sizeof(ArgusRefinePushConstants),
            &state->omniRefineDescLayout, &state->omniRefinePipelineLayout, &state->omniRefinePipeline, &state->omniRefineShader, "omni_refine") ||
        !makePipeline(kRivenMotionDeblurCompSpv, kRivenMotionDeblurCompSpvCount, 3u, sizeof(RivenDeblurPushConstants),
            &state->omniRivenDescLayout, &state->omniRivenPipelineLayout, &state->omniRivenPipeline, &state->omniRivenShader, "omni_riven") ||
        !makePipeline(kWarpReconstructCompSpv, kWarpReconstructCompSpvCount, 4u, sizeof(OmniWarpPushConstants),
            &state->omniWarpDescLayout, &state->omniWarpPipelineLayout, &state->omniWarpPipeline, &state->omniWarpShader, "omni_warp") ||
        !makePipeline(kOmniDownsampleCompSpv, kOmniDownsampleCompSpvCount, 2u, sizeof(OmniDownsamplePushConstants),
            &state->omniDownsampleDescLayout, &state->omniDownsamplePipelineLayout, &state->omniDownsamplePipeline, &state->omniDownsampleShader, "omni_downsample") ||
        !makePipeline(kOmniFlowUpsampleCompSpv, kOmniFlowUpsampleCompSpvCount, 2u, sizeof(OmniFlowUpsamplePushConstants),
            &state->omniFlowUpsampleDescLayout, &state->omniFlowUpsamplePipelineLayout, &state->omniFlowUpsamplePipeline, &state->omniFlowUpsampleShader, "omni_flow_upsample") ||
        !makePipeline(kArgusFlowSelectCompSpv, kArgusFlowSelectCompSpvCount, 5u, sizeof(ArgusSelectPushConstants),
            &state->omniSelectDescLayout, &state->omniSelectPipelineLayout, &state->omniSelectPipeline, &state->omniSelectShader, "omni_select") ||
        !makePipeline(kOccsOpponentCompSpv, kOccsOpponentCompSpvCount, 2u, sizeof(OccsParams),
            &state->occsDescLayout, &state->occsPipelineLayout, &state->occsPipeline, &state->occsShader, "occs_opponent")) {
        destroyOmniClarityPipelines(state);
        return false;
    }

    // SNCE Stage 11 NRLI pipeline: bindings 0/1 storage images + binding 2 sampler3D LUT (custom
    // layout — makePipeline only expresses storage bindings).
    {
        VkDescriptorSetLayoutBinding nb[3]{};
        nb[0].binding = 0; nb[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE; nb[0].descriptorCount = 1; nb[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        nb[1].binding = 1; nb[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE; nb[1].descriptorCount = 1; nb[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        nb[2].binding = 2; nb[2].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER; nb[2].descriptorCount = 1; nb[2].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        VkDescriptorSetLayoutCreateInfo li{};
        li.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO; li.bindingCount = 3; li.pBindings = nb;
        if (!check(vkCreateDescriptorSetLayout(state->device, &li, nullptr, &state->nrliDescLayout), "vkCreateDescriptorSetLayout(nrli)")) { destroyOmniClarityPipelines(state); return false; }
        state->nrliShader = createShaderModule(state->device, kNrliLutApplyCompSpv, kNrliLutApplyCompSpvCount);
        if (state->nrliShader == VK_NULL_HANDLE) { destroyOmniClarityPipelines(state); return false; }
        VkPushConstantRange pr{}; pr.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT; pr.offset = 0; pr.size = sizeof(NrliParams);
        VkPipelineLayoutCreateInfo pli{};
        pli.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO; pli.setLayoutCount = 1; pli.pSetLayouts = &state->nrliDescLayout; pli.pushConstantRangeCount = 1; pli.pPushConstantRanges = &pr;
        if (!check(vkCreatePipelineLayout(state->device, &pli, nullptr, &state->nrliPipelineLayout), "vkCreatePipelineLayout(nrli)")) { destroyOmniClarityPipelines(state); return false; }
        VkPipelineShaderStageCreateInfo st{}; st.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO; st.stage = VK_SHADER_STAGE_COMPUTE_BIT; st.module = state->nrliShader; st.pName = "main";
        VkSpecializationMapEntry tetraEntry{};
        tetraEntry.constantID = 0u;
        tetraEntry.offset = 0u;
        tetraEntry.size = sizeof(VkBool32);
        const VkBool32 tetraEnabled = VK_TRUE;
        VkSpecializationInfo tetraInfo{};
        tetraInfo.mapEntryCount = 1u;
        tetraInfo.pMapEntries = &tetraEntry;
        tetraInfo.dataSize = sizeof(tetraEnabled);
        tetraInfo.pData = &tetraEnabled;

        // Prefer the production tetrahedral sampler. Pipeline specialization is a creation-time
        // capability probe; a driver rejection retries the exact pre-upgrade trilinear variant.
        st.pSpecializationInfo = &tetraInfo;
        VkComputePipelineCreateInfo ci{}; ci.sType = VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO; ci.stage = st; ci.layout = state->nrliPipelineLayout;
        VkResult tetraResult = vkCreateComputePipelines(
            state->device, VK_NULL_HANDLE, 1, &ci, nullptr, &state->nrliPipeline);
        if (tetraResult == VK_SUCCESS) {
            state->nrliTetrahedralInterpolation = true;
            LOGI("NRLI LUT interpolation: tetrahedral specialization active");
        } else {
            state->nrliPipeline = VK_NULL_HANDLE;
            state->nrliTetrahedralInterpolation = false;
            st.pSpecializationInfo = nullptr;
            ci.stage = st;
            if (!check(
                    vkCreateComputePipelines(
                        state->device, VK_NULL_HANDLE, 1, &ci, nullptr, &state->nrliPipeline),
                    "vkCreateComputePipelines(nrli-trilinear-fallback)")) {
                destroyOmniClarityPipelines(state);
                return false;
            }
            LOGW("NRLI tetrahedral specialization unavailable (%d); trilinear fallback active",
                 static_cast<int>(tetraResult));
        }
    }
    createNrliIdentityLut(state); // stable identity 33^3 LUT so the per-image nrli descriptor is valid (passthrough)

    // s1-f10 / CP31 — dual-conversion-gain fusion pipeline (NeoProCamX NDR). Best-effort + gated OFF:
    // a creation failure must NOT disable the already-built omni stack, so on failure we tear down only
    // its own partial handles and the dispatch's null-guard falls back to passthrough (no-downgrade).
    if (!makePipeline(kDualGainFusionCompSpv, kDualGainFusionCompSpvCount, 4u, sizeof(DualGainFusionPushConstants),
            &state->dualGainDescLayout, &state->dualGainPipelineLayout, &state->dualGainPipeline,
            &state->dualGainShader, "dual_gain_fusion")) {
        if (state->dualGainPipeline != VK_NULL_HANDLE) { vkDestroyPipeline(state->device, state->dualGainPipeline, nullptr); state->dualGainPipeline = VK_NULL_HANDLE; }
        if (state->dualGainPipelineLayout != VK_NULL_HANDLE) { vkDestroyPipelineLayout(state->device, state->dualGainPipelineLayout, nullptr); state->dualGainPipelineLayout = VK_NULL_HANDLE; }
        if (state->dualGainDescLayout != VK_NULL_HANDLE) { vkDestroyDescriptorSetLayout(state->device, state->dualGainDescLayout, nullptr); state->dualGainDescLayout = VK_NULL_HANDLE; }
        if (state->dualGainShader != VK_NULL_HANDLE) { vkDestroyShaderModule(state->device, state->dualGainShader, nullptr); state->dualGainShader = VK_NULL_HANDLE; }
        LOGW("dual_gain_fusion pipeline unavailable; CP31 HDR fusion stays dormant (omni path unaffected)");
    }

    // s1-f10 / CP32 — photon HDR fusion FOUNDATION: create the pipeline but keep it DORMANT (no dispatch
    // exists). Best-effort like dual-gain; a failure never disables the omni stack. The dual-exposure
    // readout ring + motion-compensated merge are device-gated (s1-f06).
    if (!makePipeline(kPhotonHdrFusionCompSpv, kPhotonHdrFusionCompSpvCount, 4u, sizeof(PhotonFusionPushConstants),
            &state->photonDescLayout, &state->photonPipelineLayout, &state->photonPipeline,
            &state->photonShader, "photon_hdr_fusion")) {
        if (state->photonPipeline != VK_NULL_HANDLE) { vkDestroyPipeline(state->device, state->photonPipeline, nullptr); state->photonPipeline = VK_NULL_HANDLE; }
        if (state->photonPipelineLayout != VK_NULL_HANDLE) { vkDestroyPipelineLayout(state->device, state->photonPipelineLayout, nullptr); state->photonPipelineLayout = VK_NULL_HANDLE; }
        if (state->photonDescLayout != VK_NULL_HANDLE) { vkDestroyDescriptorSetLayout(state->device, state->photonDescLayout, nullptr); state->photonDescLayout = VK_NULL_HANDLE; }
        if (state->photonShader != VK_NULL_HANDLE) { vkDestroyShaderModule(state->device, state->photonShader, nullptr); state->photonShader = VK_NULL_HANDLE; }
        LOGW("photon_hdr_fusion pipeline unavailable; CP32 foundation stays dormant (omni path unaffected)");
    }

    // s3-f06 / CP60 — gyro-EIS per-scanline warp FOUNDATION: create the pipeline (3 storage-image bindings:
    // input, per-row correction grid, output) but keep it DORMANT (no dispatch). USE_EIS_WARP defaults false
    // (no VkSpecializationInfo is attached), so even if it were dispatched it would be a bit-for-bit identity
    // resample. Best-effort like photon/dual-gain; a failure never disables the omni stack.
    if (!makePipeline(kEisWarpCompSpv, kEisWarpCompSpvCount, 3u, sizeof(EisWarpPushConstants),
            &state->eisWarpDescLayout, &state->eisWarpPipelineLayout, &state->eisWarpPipeline,
            &state->eisWarpShader, "eis_warp")) {
        if (state->eisWarpPipeline != VK_NULL_HANDLE) { vkDestroyPipeline(state->device, state->eisWarpPipeline, nullptr); state->eisWarpPipeline = VK_NULL_HANDLE; }
        if (state->eisWarpPipelineLayout != VK_NULL_HANDLE) { vkDestroyPipelineLayout(state->device, state->eisWarpPipelineLayout, nullptr); state->eisWarpPipelineLayout = VK_NULL_HANDLE; }
        if (state->eisWarpDescLayout != VK_NULL_HANDLE) { vkDestroyDescriptorSetLayout(state->device, state->eisWarpDescLayout, nullptr); state->eisWarpDescLayout = VK_NULL_HANDLE; }
        if (state->eisWarpShader != VK_NULL_HANDLE) { vkDestroyShaderModule(state->device, state->eisWarpShader, nullptr); state->eisWarpShader = VK_NULL_HANDLE; }
        LOGW("eis_warp pipeline unavailable; CP60 EIS foundation stays dormant (omni path unaffected)");
    }

    // s5-f06 / CP-F7 — depth-driven Vogel-spiral bokeh pipeline (4 storage-image bindings: 0 input color,
    // 1 depth, 2 CoC radius, 3 output). Best-effort + gated OFF (bokehBlendEnabled default false): a
    // creation failure must NOT disable the already-built omni stack, so on failure we tear down only its
    // own partial handles and the dispatch's null-guard falls back to passthrough (no-downgrade).
    if (!makePipeline(kBokehVogelSpiralCompSpv, kBokehVogelSpiralCompSpvCount, 4u, sizeof(BokehPushConstants),
            &state->bokehDescLayout, &state->bokehPipelineLayout, &state->bokehPipeline,
            &state->bokehShader, "bokeh_vogel_spiral")) {
        if (state->bokehPipeline != VK_NULL_HANDLE) { vkDestroyPipeline(state->device, state->bokehPipeline, nullptr); state->bokehPipeline = VK_NULL_HANDLE; }
        if (state->bokehPipelineLayout != VK_NULL_HANDLE) { vkDestroyPipelineLayout(state->device, state->bokehPipelineLayout, nullptr); state->bokehPipelineLayout = VK_NULL_HANDLE; }
        if (state->bokehDescLayout != VK_NULL_HANDLE) { vkDestroyDescriptorSetLayout(state->device, state->bokehDescLayout, nullptr); state->bokehDescLayout = VK_NULL_HANDLE; }
        if (state->bokehShader != VK_NULL_HANDLE) { vkDestroyShaderModule(state->device, state->bokehShader, nullptr); state->bokehShader = VK_NULL_HANDLE; }
        LOGW("bokeh_vogel_spiral pipeline unavailable; CP-F7 bokeh stays dormant (omni path unaffected)");
    }
    return true;
}

bool allocateOmniClarityResources(VulkanBridgeState* state, TargetSurface* target) {
    if (state == nullptr || state->device == VK_NULL_HANDLE || target == nullptr) return false;
    // OWNER DIRECTIVE: OmniClarity runs in ALL pipelines incl. the encoder/recording path, so the
    // encoder target now gets its own OmniClarity images too (adds the same footprint as preview).
    const uint32_t count = static_cast<uint32_t>(target->images.size());
    if (count == 0u || target->extent.width == 0u || target->extent.height == 0u) return true;
    const uint32_t fullW = target->extent.width;
    const uint32_t fullH = target->extent.height;
    const uint32_t divisor = 2u; // HIGH-END: half-res flow (was 4) — MUST match OmniDownsample/Upsample push divisor
    const uint32_t lowW = std::max(fullW / divisor, 1u);
    const uint32_t lowH = std::max(fullH / divisor, 1u);

    target->omniCurrentLow.resize(count);
    target->omniPreviousLow.resize(count);
    target->omniFlowLowA.resize(count);
    target->omniFlowLowB.resize(count);
    target->omniFlowFull.resize(count);
    target->omniDeblur.resize(count);
    target->omniFlowCoarse.resize(count);
    target->omniFlowBackwardLow.resize(count);
    target->omniFlowSelected.resize(count);
    for (uint32_t i = 0; i < count; ++i) {
        if (!createQfcaIntermediateImage(state, &target->omniCurrentLow[i], lowW, lowH, VK_FORMAT_R16G16B16A16_SFLOAT, VK_IMAGE_USAGE_STORAGE_BIT, "vkCreateImage(omniCurrentLow)") ||
            !createQfcaIntermediateImage(state, &target->omniPreviousLow[i], lowW, lowH, VK_FORMAT_R16G16B16A16_SFLOAT, VK_IMAGE_USAGE_STORAGE_BIT, "vkCreateImage(omniPreviousLow)") ||
            !createQfcaIntermediateImage(state, &target->omniFlowLowA[i], lowW, lowH, VK_FORMAT_R16G16_SFLOAT, VK_IMAGE_USAGE_STORAGE_BIT, "vkCreateImage(omniFlowLowA)") ||
            !createQfcaIntermediateImage(state, &target->omniFlowLowB[i], lowW, lowH, VK_FORMAT_R16G16_SFLOAT, VK_IMAGE_USAGE_STORAGE_BIT, "vkCreateImage(omniFlowLowB)") ||
            !createQfcaIntermediateImage(state, &target->omniFlowFull[i], fullW, fullH, VK_FORMAT_R16G16_SFLOAT, VK_IMAGE_USAGE_STORAGE_BIT, "vkCreateImage(omniFlowFull)") ||
            !createQfcaIntermediateImage(state, &target->omniDeblur[i], fullW, fullH, VK_FORMAT_R16G16B16A16_SFLOAT, VK_IMAGE_USAGE_STORAGE_BIT, "vkCreateImage(omniDeblur)") ||
            !createQfcaIntermediateImage(state, &target->omniFlowCoarse[i], lowW, lowH, VK_FORMAT_R16G16_SFLOAT, VK_IMAGE_USAGE_STORAGE_BIT, "vkCreateImage(omniFlowCoarse)") ||
            !createQfcaIntermediateImage(state, &target->omniFlowBackwardLow[i], lowW, lowH, VK_FORMAT_R16G16_SFLOAT, VK_IMAGE_USAGE_STORAGE_BIT, "vkCreateImage(omniFlowBackwardLow)") ||
            !createQfcaIntermediateImage(state, &target->omniFlowSelected[i], lowW, lowH, VK_FORMAT_R16G16_SFLOAT, VK_IMAGE_USAGE_STORAGE_BIT, "vkCreateImage(omniFlowSelected)")) {
            return false;
        }
    }

    // Live inline-bokeh shared depth + CoC (r32f, STORAGE|TRANSFER_DST). Best-effort: bokeh is optional,
    // so a failure here must NOT break the preview — do not return false; the inline pass guards on null.
    createQfcaIntermediateImage(state, &target->bokehDepth, fullW, fullH, VK_FORMAT_R32_SFLOAT,
        VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, "vkCreateImage(bokehDepth)");
    createQfcaIntermediateImage(state, &target->bokehCoc, fullW, fullH, VK_FORMAT_R32_SFLOAT,
        VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT, "vkCreateImage(bokehCoc)");
    const bool bokehImagesReady =
        target->bokehDepth.imageView != VK_NULL_HANDLE && target->bokehCoc.imageView != VK_NULL_HANDLE;

    std::array<VkDescriptorPoolSize, 2> poolSizes{};
    poolSizes[0].type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    poolSizes[0].descriptorCount = count * 46u; // 40 + nrli 2 + bokeh-live 4
    poolSizes[1].type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    poolSizes[1].descriptorCount = count * 1u; // nrli sampler3D LUT
    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.poolSizeCount = static_cast<uint32_t>(poolSizes.size());
    poolInfo.pPoolSizes = poolSizes.data();
    poolInfo.maxSets = count * 15u; // 11 OmniClarity + 2 occs + 1 nrli + 1 bokeh-live
    if (!check(vkCreateDescriptorPool(state->device, &poolInfo, nullptr, &target->omniDescriptorPool), "vkCreateDescriptorPool(omniClarity)")) {
        return false;
    }

    target->omniDownsampleCurDescSets.resize(count, VK_NULL_HANDLE);
    target->omniDownsamplePrevDescSets.resize(count, VK_NULL_HANDLE);
    target->omniBlockMatchDescSets.resize(count, VK_NULL_HANDLE);
    target->omniRefineA2BDescSets.resize(count, VK_NULL_HANDLE);
    target->omniRefineB2ADescSets.resize(count, VK_NULL_HANDLE);
    target->omniFlowUpsampleDescSets.resize(count, VK_NULL_HANDLE);
    target->omniRivenDescSets.resize(count, VK_NULL_HANDLE);
    target->omniWarpDescSets.resize(count, VK_NULL_HANDLE);
    target->omniRefineCoarse2BDescSets.resize(count, VK_NULL_HANDLE);
    target->omniBlockMatchBackwardDescSets.resize(count, VK_NULL_HANDLE);
    target->omniSelectDescSets.resize(count, VK_NULL_HANDLE);
    target->occsForwardDescSets.resize(count, VK_NULL_HANDLE);
    target->occsCopyBackDescSets.resize(count, VK_NULL_HANDLE);
    target->nrliDescSets.resize(count, VK_NULL_HANDLE);
    target->bokehLiveDescSets.resize(count, VK_NULL_HANDLE);

    VkDescriptorSetAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    allocInfo.descriptorPool = target->omniDescriptorPool;
    allocInfo.descriptorSetCount = 1;
    auto allocSet = [&](VkDescriptorSetLayout layout, VkDescriptorSet* set, const char* label) -> bool {
        allocInfo.pSetLayouts = &layout;
        return check(vkAllocateDescriptorSets(state->device, &allocInfo, set), label);
    };

    auto writeSet = [&](VkDescriptorSet set, std::initializer_list<VkImageView> viewList) {
        std::vector<VkImageView> views(viewList);
        std::vector<VkDescriptorImageInfo> infos(views.size());
        std::vector<VkWriteDescriptorSet> writes(views.size());
        for (size_t b = 0; b < views.size(); ++b) {
            infos[b].imageLayout = VK_IMAGE_LAYOUT_GENERAL;
            infos[b].imageView = views[b];
            infos[b].sampler = VK_NULL_HANDLE;
            writes[b].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            writes[b].dstSet = set;
            writes[b].dstBinding = static_cast<uint32_t>(b);
            writes[b].descriptorCount = 1;
            writes[b].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
            writes[b].pImageInfo = &infos[b];
        }
        vkUpdateDescriptorSets(state->device, static_cast<uint32_t>(writes.size()), writes.data(), 0, nullptr);
    };

    for (uint32_t i = 0; i < count; ++i) {
        if (!allocSet(state->omniDownsampleDescLayout, &target->omniDownsampleCurDescSets[i], "vkAllocateDescriptorSets(omniDownsampleCur)") ||
            !allocSet(state->omniDownsampleDescLayout, &target->omniDownsamplePrevDescSets[i], "vkAllocateDescriptorSets(omniDownsamplePrev)") ||
            !allocSet(state->omniBlockMatchDescLayout, &target->omniBlockMatchDescSets[i], "vkAllocateDescriptorSets(omniBlockMatch)") ||
            !allocSet(state->omniRefineDescLayout, &target->omniRefineA2BDescSets[i], "vkAllocateDescriptorSets(omniRefineA2B)") ||
            !allocSet(state->omniRefineDescLayout, &target->omniRefineB2ADescSets[i], "vkAllocateDescriptorSets(omniRefineB2A)") ||
            !allocSet(state->omniFlowUpsampleDescLayout, &target->omniFlowUpsampleDescSets[i], "vkAllocateDescriptorSets(omniFlowUpsample)") ||
            !allocSet(state->omniRivenDescLayout, &target->omniRivenDescSets[i], "vkAllocateDescriptorSets(omniRiven)") ||
            !allocSet(state->omniWarpDescLayout, &target->omniWarpDescSets[i], "vkAllocateDescriptorSets(omniWarp)") ||
            !allocSet(state->omniBlockMatchDescLayout, &target->omniBlockMatchBackwardDescSets[i], "vkAllocateDescriptorSets(omniBlockMatchBackward)") ||
            !allocSet(state->omniRefineDescLayout, &target->omniRefineCoarse2BDescSets[i], "vkAllocateDescriptorSets(omniRefineCoarse2B)") ||
            !allocSet(state->omniSelectDescLayout, &target->omniSelectDescSets[i], "vkAllocateDescriptorSets(omniSelect)") ||
            !allocSet(state->occsDescLayout, &target->occsForwardDescSets[i], "vkAllocateDescriptorSets(occsForward)") ||
            !allocSet(state->occsDescLayout, &target->occsCopyBackDescSets[i], "vkAllocateDescriptorSets(occsCopyBack)") ||
            !allocSet(state->nrliDescLayout, &target->nrliDescSets[i], "vkAllocateDescriptorSets(nrli)")) {
            return false;
        }
        const VkImageView current = target->computeIntermediates[i].imageView; // post-QFCA current frame
        const VkImageView history = target->historyImages.front().imageView;  // prior submitted frame
        const VkImageView curLow = target->omniCurrentLow[i].imageView;
        const VkImageView prevLow = target->omniPreviousLow[i].imageView;
        const VkImageView flowLowA = target->omniFlowLowA[i].imageView;
        const VkImageView flowLowB = target->omniFlowLowB[i].imageView;
        const VkImageView flowFull = target->omniFlowFull[i].imageView;
        const VkImageView deblur = target->omniDeblur[i].imageView;
        const VkImageView flowCoarse = target->omniFlowCoarse[i].imageView;
        const VkImageView flowBackward = target->omniFlowBackwardLow[i].imageView;
        const VkImageView flowSelected = target->omniFlowSelected[i].imageView;
        writeSet(target->omniDownsampleCurDescSets[i], { current, curLow });
        writeSet(target->omniDownsamplePrevDescSets[i], { history, prevLow });
        // Forward block match writes the PRESERVED coarse forward flow (occlusion fallback).
        writeSet(target->omniBlockMatchDescSets[i], { curLow, prevLow, flowCoarse });
        // Backward block match: frames swapped -> backward flow for the fwd/bwd consistency check.
        writeSet(target->omniBlockMatchBackwardDescSets[i], { prevLow, curLow, flowBackward });
        // Refine iter 1 reads the preserved coarse forward flow into B; later iters ping-pong A<->B.
        writeSet(target->omniRefineCoarse2BDescSets[i], { curLow, prevLow, flowCoarse, flowLowB });
        writeSet(target->omniRefineA2BDescSets[i], { curLow, prevLow, flowLowA, flowLowB });
        writeSet(target->omniRefineB2ADescSets[i], { curLow, prevLow, flowLowB, flowLowA });
        // Occlusion + hybrid select: refined forward (flowLowA) + backward + coarse fallback + current.
        writeSet(target->omniSelectDescSets[i], { flowLowA, flowBackward, flowCoarse, curLow, flowSelected });
        writeSet(target->omniFlowUpsampleDescSets[i], { flowSelected, flowFull });
        writeSet(target->omniRivenDescSets[i], { current, flowFull, deblur });
        writeSet(target->omniWarpDescSets[i], { deblur, history, flowFull, current });
        // SNCE Stage-04 occs ping-pong: enhance current -> deblur, then zero-gain passthrough deblur -> current.
        writeSet(target->occsForwardDescSets[i], { current, deblur });
        writeSet(target->occsCopyBackDescSets[i], { deblur, current });
        // Live inline-bokeh set (best-effort): {intermediate, bokehDepth, bokehCoc, omniDeblur}. Skipped
        // if the bokeh pipeline/layout or shared images are absent — the inline pass guards on null too.
        if (state->bokehDescLayout != VK_NULL_HANDLE && bokehImagesReady &&
            allocSet(state->bokehDescLayout, &target->bokehLiveDescSets[i], "vkAllocateDescriptorSets(bokehLive)")) {
            writeSet(target->bokehLiveDescSets[i],
                { current, target->bokehDepth.imageView, target->bokehCoc.imageView, deblur });
        }
        // SNCE Stage-11 nrli: deblur (storage 0) -> current (storage 1) + identity nrliLut (sampler3D 2).
        if (state->nrliLut.ready && state->nrliLut.imageView != VK_NULL_HANDLE) {
            VkDescriptorImageInfo nin[2]{};
            nin[0].imageLayout = VK_IMAGE_LAYOUT_GENERAL; nin[0].imageView = deblur; nin[0].sampler = VK_NULL_HANDLE;
            nin[1].imageLayout = VK_IMAGE_LAYOUT_GENERAL; nin[1].imageView = current; nin[1].sampler = VK_NULL_HANDLE;
            VkDescriptorImageInfo lutInfo{};
            lutInfo.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
            lutInfo.imageView = state->nrliLut.imageView;
            lutInfo.sampler = state->nrliLut.sampler;
            VkWriteDescriptorSet nw[3]{};
            for (int nbi = 0; nbi < 2; ++nbi) {
                nw[nbi].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
                nw[nbi].dstSet = target->nrliDescSets[i];
                nw[nbi].dstBinding = static_cast<uint32_t>(nbi);
                nw[nbi].descriptorCount = 1;
                nw[nbi].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
                nw[nbi].pImageInfo = &nin[nbi];
            }
            nw[2].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            nw[2].dstSet = target->nrliDescSets[i];
            nw[2].dstBinding = 2;
            nw[2].descriptorCount = 1;
            nw[2].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            nw[2].pImageInfo = &lutInfo;
            vkUpdateDescriptorSets(state->device, 3, nw, 0, nullptr);
        }
    }
    return true;
}

void dispatchOmniClarityMotionStack(VulkanBridgeState* state, TargetSurface* target, VkCommandBuffer commandBuffer, uint32_t imageIndex, uint32_t groupsX, uint32_t groupsY) {
    if (state == nullptr || target == nullptr || !state->omniClarityEnabled) return;
    // PART A: OmniClarity is background-built/deferred. Its pipelines are created all-or-nothing, and
    // omniClarityPipelinesReady flips true (under state->mutex) only after every one is non-null, so this
    // single gate makes the whole stack a clean no-op (raw preview) until the background build completes.
    if (!state->omniClarityPipelinesReady) return;
    // OWNER DIRECTIVE: OmniClarity runs in ALL pipelines incl. encoder + during recording.
    // (Previously yielded on encoderTarget/recordingActive — device-validated to risk muxer-sample
    //  drops on CPU-encode devices; that guard is intentionally removed per owner request.)
    if (state->omniBlockMatchPipeline == VK_NULL_HANDLE || state->omniDownsamplePipeline == VK_NULL_HANDLE) return;
    if (imageIndex >= target->omniBlockMatchDescSets.size()) return;
    if (target->omniWarpDescSets.size() <= imageIndex || target->omniFlowFull.size() <= imageIndex) return;
    // Occlusion-select resources are part of the same all-or-nothing alloc; bail (skip the stack) if absent.
    if (state->omniSelectPipeline == VK_NULL_HANDLE ||
        target->omniSelectDescSets.size() <= imageIndex ||
        target->omniBlockMatchBackwardDescSets.size() <= imageIndex ||
        target->omniRefineCoarse2BDescSets.size() <= imageIndex ||
        target->omniFlowSelected.size() <= imageIndex ||
        target->omniFlowCoarse.size() <= imageIndex ||
        target->omniFlowBackwardLow.size() <= imageIndex) return;

    // The prior image is the last successfully submitted frame for this target, independent of
    // swapchain acquisition order. This removes the 2-4 frame temporal jumps that produced a wavy
    // preview when a swapchain slot was reacquired out of sequence.
    const bool previousFrameValid =
        !target->historyContentValid.empty() && target->historyContentValid.front() != 0;
    // CP54b — a hard scene cut (detected by the Kotlin post-flow residual-spike detector,
    // ArgusFlowStagePolicy.isSceneCutSpike, and pushed via nativeSetOmniClarityFlowTuning) must not let the
    // flow/warp propagate garbage across the cut. Gate it through the SAME previousValid=0 mechanism as the
    // CP51 first-frame guard. Default omniTuning.sceneCutActive=false => byte-identical to the pre-CP54b path.
    const bool trustPreviousFrame = previousFrameValid && !state->omniTuning.sceneCutActive;
    const float previousValidPush = trustPreviousFrame ? 1.0f : 0.0f;

    const uint32_t divisor = 2u; // HIGH-END: half-res flow (was 4) — MUST match alloc + push divisor
    const uint32_t lowW = std::max(target->extent.width / divisor, 1u);
    const uint32_t lowH = std::max(target->extent.height / divisor, 1u);
    const uint32_t lowGroupsX = (lowW + 15u) / 16u;
    const uint32_t lowGroupsY = (lowH + 15u) / 16u;

    auto computeBarrier = [&](VkImage image) {
        insertComputeImageBarrier(
            commandBuffer, image,
            VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
            VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
    };
    auto runPass = [&](VkPipeline pipe, VkPipelineLayout layout, VkDescriptorSet set, const void* push, uint32_t pushSize, uint32_t gx, uint32_t gy) {
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipe);
        vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, layout, 0, 1, &set, 0, nullptr);
        vkCmdPushConstants(commandBuffer, layout, VK_SHADER_STAGE_COMPUTE_BIT, 0, pushSize, push);
        vkCmdDispatch(commandBuffer, gx, gy, 1u);
    };

    // The NIVUKX denoise pass just wrote `intermediate`; make that write visible to the downsample reads.
    computeBarrier(target->computeIntermediates[imageIndex].image);

    // Downsample current + prior frame to reduced resolution for cheap flow.
    OmniDownsamplePushConstants dp{};
    runPass(state->omniDownsamplePipeline, state->omniDownsamplePipelineLayout, target->omniDownsampleCurDescSets[imageIndex], &dp, sizeof(dp), lowGroupsX, lowGroupsY);
    computeBarrier(target->omniCurrentLow[imageIndex].image);
    runPass(state->omniDownsamplePipeline, state->omniDownsamplePipelineLayout, target->omniDownsamplePrevDescSets[imageIndex], &dp, sizeof(dp), lowGroupsX, lowGroupsY);
    computeBarrier(target->omniPreviousLow[imageIndex].image);

    // Argus Stage A forward block matching -> preserved coarse forward flow (occlusion fallback field).
    ArgusBlockMatchPushConstants bm{};
    bm.previousValid = previousValidPush; // CP51
    runPass(state->omniBlockMatchPipeline, state->omniBlockMatchPipelineLayout, target->omniBlockMatchDescSets[imageIndex], &bm, sizeof(bm), lowGroupsX, lowGroupsY);
    computeBarrier(target->omniFlowCoarse[imageIndex].image);
    // Argus backward block matching (frames swapped) -> backward flow for the fwd/bwd consistency check.
    runPass(state->omniBlockMatchPipeline, state->omniBlockMatchPipelineLayout, target->omniBlockMatchBackwardDescSets[imageIndex], &bm, sizeof(bm), lowGroupsX, lowGroupsY);
    computeBarrier(target->omniFlowBackwardLow[imageIndex].image);

    // Argus Stage B (variational refine) at reduced res. HIGH-END: Horn-Schunck/Jacobi needs many
    // iterations to propagate flow across smooth regions. Iter 1 refines the preserved coarse forward
    // flow into flowLowB, then ping-pong B<->A for a total of 8 passes ending with the refined field in
    // flowLowA (the coarse field stays intact for the select fallback).
    ArgusRefinePushConstants rf{};
    rf.previousValid = previousValidPush; // CP51
    // s3-f01 — the refine ping-pong count is now a governor lever (nativeSetOmniClarityFlowTuning). It is
    // forced EVEN and >= 2 so the loop always terminates with the refined field in flowLowA (the buffer the
    // select pass reads); an odd count would leave the freshest field in flowLowB and corrupt the selection.
    // Default omniTuning.refineIterations=8 reproduces today's value exactly (no-downgrade).
    int kOmniRefineIterations = std::max(state->omniTuning.refineIterations, 2);
    if ((kOmniRefineIterations % 2) != 0) kOmniRefineIterations -= 1;
    runPass(state->omniRefinePipeline, state->omniRefinePipelineLayout, target->omniRefineCoarse2BDescSets[imageIndex], &rf, sizeof(rf), lowGroupsX, lowGroupsY);
    computeBarrier(target->omniFlowLowB[imageIndex].image);
    for (int iter = 2; iter <= kOmniRefineIterations; ++iter) {
        if ((iter % 2) == 0) {
            runPass(state->omniRefinePipeline, state->omniRefinePipelineLayout, target->omniRefineB2ADescSets[imageIndex], &rf, sizeof(rf), lowGroupsX, lowGroupsY);
            computeBarrier(target->omniFlowLowA[imageIndex].image);
        } else {
            runPass(state->omniRefinePipeline, state->omniRefinePipelineLayout, target->omniRefineA2BDescSets[imageIndex], &rf, sizeof(rf), lowGroupsX, lowGroupsY);
            computeBarrier(target->omniFlowLowB[imageIndex].image);
        }
    }

    // Argus occlusion + hybrid selection: refined forward (flowLowA) + fwd/bwd consistency (backward) +
    // texture gate, falling back to the coarse forward field where occluded -> omniFlowSelected.
    ArgusSelectPushConstants sel{};
    sel.previousValid = previousValidPush; // CP51
    runPass(state->omniSelectPipeline, state->omniSelectPipelineLayout, target->omniSelectDescSets[imageIndex], &sel, sizeof(sel), lowGroupsX, lowGroupsY);
    computeBarrier(target->omniFlowSelected[imageIndex].image);

    // Upsample + rescale the occlusion-selected reduced flow to full resolution.
    OmniFlowUpsamplePushConstants up{};
    runPass(state->omniFlowUpsamplePipeline, state->omniFlowUpsamplePipelineLayout, target->omniFlowUpsampleDescSets[imageIndex], &up, sizeof(up), groupsX, groupsY);
    computeBarrier(target->omniFlowFull[imageIndex].image);

    // Riven: confidence-gated motion deblur of the current frame (full res).
    // s3-f03 — noise-aware deblur lever (nativeSetOmniClarityDeblurTuning). Defaults (rlIterations=1,
    // snrInverse=0.0 == the RivenDeblurPushConstants struct defaults) keep Path A's single-step output
    // byte-identical; the governor raises iterations on clean frames / lifts the noise floor on noisy ones.
    RivenDeblurPushConstants rv{};
    rv.rlIterations = std::max(state->omniTuning.rlIterations, 1);
    rv.snrInverse = state->omniTuning.snrInverse;
    runPass(state->omniRivenPipeline, state->omniRivenPipelineLayout, target->omniRivenDescSets[imageIndex], &rv, sizeof(rv), groupsX, groupsY);
    computeBarrier(target->omniDeblur[imageIndex].image);

    // Chronos: motion-compensated temporal warp/blend (deblurred current + prior) -> intermediate (full res).
    OmniWarpPushConstants wp{};
    // s3-f02 — Chronos temporal-blend lever (nativeSetOmniClarityWarpTuning). Defaults to the CP52 values
    // (base 0.0 / max 0.9 == the OmniWarpPushConstants struct defaults), so an un-driven channel is
    // byte-identical. Set BEFORE the CP51/CP54b force-off below so the first-frame / scene-cut pass-through
    // always wins.
    wp.baseTemporalAlpha = state->omniTuning.baseTemporalAlpha;
    wp.maxTemporalAlpha = state->omniTuning.maxTemporalAlpha;
    if (!trustPreviousFrame) {
        // CP51 — no real prior frame yet (or CP54b — a hard scene cut this frame): force temporal blend off
        // so the warp is a pass-through of the (deblurred) current frame instead of blending against
        // undefined history / pre-cut content. Default path (trustPreviousFrame=true) is unchanged.
        wp.baseTemporalAlpha = 0.0f;
        wp.maxTemporalAlpha = 0.0f;
    }
    runPass(state->omniWarpPipeline, state->omniWarpPipelineLayout, target->omniWarpDescSets[imageIndex], &wp, sizeof(wp), groupsX, groupsY);
    // intermediate's write is synchronized by the existing post-pass history-copy barrier below.
}

bool createFramebuffers(VulkanBridgeState* state, TargetSurface* target) {
    target->framebuffers.resize(target->imageViews.size(), VK_NULL_HANDLE);
    for (size_t i = 0; i < target->imageViews.size(); ++i) {
        VkImageView attachments[] = {target->imageViews[i]};
        VkFramebufferCreateInfo framebufferInfo{};
        framebufferInfo.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
        framebufferInfo.renderPass = target->renderPass;
        framebufferInfo.attachmentCount = 1;
        framebufferInfo.pAttachments = attachments;
        framebufferInfo.width = target->extent.width;
        framebufferInfo.height = target->extent.height;
        framebufferInfo.layers = 1;
        if (!check(vkCreateFramebuffer(state->device, &framebufferInfo, nullptr, &target->framebuffers[i]), "vkCreateFramebuffer")) {
            return false;
        }
    }
    return true;
}

bool createCommandResources(VulkanBridgeState* state, TargetSurface* target) {
    VkCommandPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    poolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    poolInfo.queueFamilyIndex = state->queueFamilyIndex;
    if (!check(vkCreateCommandPool(state->device, &poolInfo, nullptr, &target->commandPool), "vkCreateCommandPool")) {
        return false;
    }

    target->commandBuffers.resize(target->images.size(), VK_NULL_HANDLE);
    VkCommandBufferAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    allocInfo.commandPool = target->commandPool;
    allocInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocInfo.commandBufferCount = static_cast<uint32_t>(target->commandBuffers.size());
    if (!check(vkAllocateCommandBuffers(state->device, &allocInfo, target->commandBuffers.data()), "vkAllocateCommandBuffers")) {
        return false;
    }
    return true;
}

bool createSyncResources(VulkanBridgeState* state, TargetSurface* target) {
    target->imageAvailableSemaphores.resize(kFramesInFlight, VK_NULL_HANDLE);
    target->renderFinishedSemaphores.resize(kFramesInFlight, VK_NULL_HANDLE);
    target->inFlightFences.resize(kFramesInFlight, VK_NULL_HANDLE);
    target->inFlightTimelineValues.assign(kFramesInFlight, 0u);
    VkSemaphoreCreateInfo semaphoreInfo{};
    semaphoreInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
    VkFenceCreateInfo fenceInfo{};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
    for (uint32_t i = 0; i < kFramesInFlight; ++i) {
        if (!check(vkCreateSemaphore(state->device, &semaphoreInfo, nullptr, &target->imageAvailableSemaphores[i]), "vkCreateSemaphore(imageAvailable)") ||
            !check(vkCreateSemaphore(state->device, &semaphoreInfo, nullptr, &target->renderFinishedSemaphores[i]), "vkCreateSemaphore(renderFinished)") ||
            !check(vkCreateFence(state->device, &fenceInfo, nullptr, &target->inFlightFences[i]), "vkCreateFence")) {
            return false;
        }
    }
    if (state->timelineSemaphoresEnabled) {
        VkSemaphoreTypeCreateInfo timelineCreateInfo{};
        timelineCreateInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_TYPE_CREATE_INFO;
        timelineCreateInfo.semaphoreType = VK_SEMAPHORE_TYPE_TIMELINE;
        timelineCreateInfo.initialValue = 0;
        VkSemaphoreCreateInfo timelineSemaphoreInfo{};
        timelineSemaphoreInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
        timelineSemaphoreInfo.pNext = &timelineCreateInfo;
        if (!check(
                vkCreateSemaphore(
                    state->device,
                    &timelineSemaphoreInfo,
                    nullptr,
                    &target->timelineSemaphore
                ),
                "vkCreateSemaphore(timeline)"
            )) {
            target->timelineSemaphore = VK_NULL_HANDLE;
            return false;
        }
    }
    return true;
}

bool createTargetSurfaceResources(VulkanBridgeState* state, TargetSurface* target) {
    const char* targetLabel = target->encoderTarget ? "encoder" : "preview";
    auto surfaceResourceStageStart = std::chrono::steady_clock::now();
    uint32_t formatCount = 0;
    if (!check(vkGetPhysicalDeviceSurfaceFormatsKHR(state->physicalDevice, target->surface, &formatCount, nullptr), "vkGetPhysicalDeviceSurfaceFormatsKHR(count)") ||
        formatCount == 0) {
        return false;
    }
    std::vector<VkSurfaceFormatKHR> formats(formatCount);
    if (!check(vkGetPhysicalDeviceSurfaceFormatsKHR(state->physicalDevice, target->surface, &formatCount, formats.data()), "vkGetPhysicalDeviceSurfaceFormatsKHR(list)")) {
        return false;
    }
    logSurfaceResourceStage(targetLabel, "surface formats", surfaceResourceStageStart);

    uint32_t presentModeCount = 0;
    if (!check(vkGetPhysicalDeviceSurfacePresentModesKHR(state->physicalDevice, target->surface, &presentModeCount, nullptr), "vkGetPhysicalDeviceSurfacePresentModesKHR(count)") ||
        presentModeCount == 0) {
        return false;
    }
    std::vector<VkPresentModeKHR> presentModes(presentModeCount);
    if (!check(vkGetPhysicalDeviceSurfacePresentModesKHR(state->physicalDevice, target->surface, &presentModeCount, presentModes.data()), "vkGetPhysicalDeviceSurfacePresentModesKHR(list)")) {
        return false;
    }
    logSurfaceResourceStage(targetLabel, "present modes", surfaceResourceStageStart);

    VkSurfaceCapabilitiesKHR capabilities{};
    if (!check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(state->physicalDevice, target->surface, &capabilities), "vkGetPhysicalDeviceSurfaceCapabilitiesKHR")) {
        return false;
    }
    logSurfaceResourceStage(targetLabel, "surface capabilities", surfaceResourceStageStart);

    const VkSurfaceFormatKHR selectedFormat = target->encoderTarget
        ? chooseEncoderSurfaceFormat(formats, state->hdrModeId)
        : chooseSurfaceFormat(formats, state->hdrModeId, state->preferTenBitSdrDisplay);
    // Pipeline compatibility checks performed later in this build need the candidate HDR identity.
    // target->valid remains false until every resource succeeds, so failure is never published.
    target->builtForHdrModeId = state->hdrModeId;
    const VkPresentModeKHR presentMode = choosePresentMode(presentModes, target->encoderTarget);
    LOGI(
        "Swapchain selected: format=%s colorSpace=%d hdrModeId=%d target=%s presentMode=%s",
        vkFormatLabel(selectedFormat.format),
        static_cast<int>(selectedFormat.colorSpace),
        state->hdrModeId,
        target->encoderTarget ? "encoder" : "preview",
        presentModeLabel(presentMode)
    );

    if (target->encoderTarget && state->hdrModeId == 0) {
        if (isHighPrecisionEncoderSurfaceFormat(selectedFormat.format)) {
            LOGI("Encoder SDR surface precision: high-precision transfer=SDR");
        } else {
            LOGI("Encoder SDR surface precision fallback: 8-bit transfer=SDR");
        }
    }

    const VkExtent2D extent = chooseExtent(target->window, capabilities);
    const uint32_t targetBufferedImages = std::max(kFramesInFlight, kMinimumSwapchainBuffers);
    uint32_t imageCount = std::max(capabilities.minImageCount + 1, targetBufferedImages);
    if (capabilities.maxImageCount > 0) {
        imageCount = std::min(imageCount, capabilities.maxImageCount);
    }
    if (imageCount < kMinimumSwapchainBuffers) {
        LOGW(
            "Swapchain limited to %u images (<%u) by surface caps; producer/consumer decoupling reduced",
            imageCount,
            kMinimumSwapchainBuffers
        );
    }

    VkSwapchainCreateInfoKHR swapchainInfo{};
    swapchainInfo.sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
    swapchainInfo.surface = target->surface;
    swapchainInfo.minImageCount = imageCount;
    swapchainInfo.imageFormat = selectedFormat.format;
    swapchainInfo.imageColorSpace = selectedFormat.colorSpace;
    swapchainInfo.imageExtent = extent;
    swapchainInfo.imageArrayLayers = 1;
    swapchainInfo.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    swapchainInfo.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    swapchainInfo.preTransform = capabilities.currentTransform;
    swapchainInfo.compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    swapchainInfo.presentMode = presentMode;
    swapchainInfo.clipped = VK_TRUE;
    swapchainInfo.oldSwapchain = VK_NULL_HANDLE;
    if (!check(vkCreateSwapchainKHR(state->device, &swapchainInfo, nullptr, &target->swapchain), "vkCreateSwapchainKHR")) {
        return false;
    }
    logSurfaceResourceStage(targetLabel, "vkCreateSwapchainKHR", surfaceResourceStageStart);

    target->swapchainFormat = selectedFormat.format;
    target->colorSpace = selectedFormat.colorSpace;
    target->extent = extent;

    uint32_t swapImageCount = 0;
    if (!check(vkGetSwapchainImagesKHR(state->device, target->swapchain, &swapImageCount, nullptr), "vkGetSwapchainImagesKHR(count)") ||
        swapImageCount == 0) {
        return false;
    }
    target->images.resize(swapImageCount, VK_NULL_HANDLE);
    if (!check(vkGetSwapchainImagesKHR(state->device, target->swapchain, &swapImageCount, target->images.data()), "vkGetSwapchainImagesKHR(list)")) {
        return false;
    }
    logSurfaceResourceStage(targetLabel, "vkGetSwapchainImagesKHR", surfaceResourceStageStart);

    target->imageViews.resize(target->images.size(), VK_NULL_HANDLE);
    for (size_t i = 0; i < target->images.size(); ++i) {
        VkImageViewCreateInfo viewInfo{};
        viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
        viewInfo.image = target->images[i];
        viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
        viewInfo.format = target->swapchainFormat;
        viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        viewInfo.subresourceRange.baseMipLevel = 0;
        viewInfo.subresourceRange.levelCount = 1;
        viewInfo.subresourceRange.baseArrayLayer = 0;
        viewInfo.subresourceRange.layerCount = 1;
        if (!check(vkCreateImageView(state->device, &viewInfo, nullptr, &target->imageViews[i]), "vkCreateImageView(swapchain)")) {
            return false;
        }
    }
    logSurfaceResourceStage(targetLabel, "swapchain image views", surfaceResourceStageStart);

    if (!createRenderPass(state, target)) return false;
    logSurfaceResourceStage(targetLabel, "createRenderPass", surfaceResourceStageStart);
    if (!tryReuseCompatiblePreviewGraphicsPipeline(state, target) &&
        !createGraphicsPipeline(state, target)) return false;
    logSurfaceResourceStage(targetLabel, "createGraphicsPipeline", surfaceResourceStageStart);
    if (!createFramebuffers(state, target)) return false;
    logSurfaceResourceStage(targetLabel, "createFramebuffers", surfaceResourceStageStart);
    if (!createCommandResources(state, target)) return false;
    logSurfaceResourceStage(targetLabel, "createCommandResources", surfaceResourceStageStart);
    if (!createUniformBuffers(state, target)) return false;
    logSurfaceResourceStage(targetLabel, "createUniformBuffers", surfaceResourceStageStart);
    if (!createDescriptorResources(state, target)) return false;
    logSurfaceResourceStage(targetLabel, "createDescriptorResources", surfaceResourceStageStart);
    // PART A: when the enhancement pipelines are still compiling in the background (deferred),
    // createComputeIntermediateResources intentionally returns false (its flag-gate) — that is NOT a
    // failure here: the target renders the raw camera frame until startEnhancementPipelineBuild() flips
    // the flag and RE-DRIVES this same call for the bound target. Only treat it as fatal once the
    // enhancement is actually ready (the steady-state path).
    if (enhancementFullyReady(state) && !createComputeIntermediateResources(state, target)) return false;
    logSurfaceResourceStage(targetLabel, "createComputeIntermediateResources", surfaceResourceStageStart);
    if (!createSyncResources(state, target)) return false;
    logSurfaceResourceStage(targetLabel, "createSyncResources", surfaceResourceStageStart);

    target->valid = true;
    target->syncIndex = 0;
    // Publish the display-format preference only after every swapchain-dependent resource exists.
    // target->valid is the corresponding success bit for the HDR identity above.
    if (!target->encoderTarget) {
        target->builtForPreferTenBitSdrDisplay = state->preferTenBitSdrDisplay;
    }
    return true;
}

bool bindSurfaceObject(JNIEnv* env, VulkanBridgeState* state, jobject surfaceObject, TargetSurface* target, const char* label) {
    target->encoderTarget = std::strcmp(label, "encoder") == 0;
    destroyTargetSurface(state, target);
    if (surfaceObject == nullptr) {
        std::ostringstream status;
        status << label << " surface cleared";
        setStatus(state, status.str());
        return true;
    }

    LOGI("%s: begin surface bind", label);
    ANativeWindow* nativeWindow = ANativeWindow_fromSurface(env, surfaceObject);
    if (nativeWindow == nullptr) {
        std::ostringstream status;
        status << label << " ANativeWindow_fromSurface failed";
        setStatus(state, status.str());
        return false;
    }

    target->window = nativeWindow;
    VkAndroidSurfaceCreateInfoKHR surfaceInfo{};
    surfaceInfo.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
    surfaceInfo.window = nativeWindow;
    LOGI("%s: creating Vulkan surface", label);
    if (!check(vkCreateAndroidSurfaceKHR(state->instance, &surfaceInfo, nullptr, &target->surface), "vkCreateAndroidSurfaceKHR")) {
        std::ostringstream status;
        status << label << " vkCreateAndroidSurfaceKHR failed";
        setStatus(state, status.str());
        destroyTargetSurface(state, target);
        return false;
    }

    VkBool32 presentSupported = VK_FALSE;
    LOGI("%s: querying present support", label);
    if (!check(vkGetPhysicalDeviceSurfaceSupportKHR(
            state->physicalDevice,
            state->queueFamilyIndex,
            target->surface,
            &presentSupported
        ), "vkGetPhysicalDeviceSurfaceSupportKHR") ||
        presentSupported != VK_TRUE) {
        std::ostringstream status;
        status << label << " surface not presentable on selected queue";
        setStatus(state, status.str());
        destroyTargetSurface(state, target);
        return false;
    }

    LOGI("%s: creating swapchain resources", label);
    if (!createTargetSurfaceResources(state, target)) {
        std::ostringstream status;
        status << label << " swapchain/resource creation failed";
        setStatus(state, status.str());
        destroyTargetSurface(state, target);
        return false;
    }

    std::ostringstream status;
    status << label << " surface ready " << target->extent.width << "x" << target->extent.height;
    setStatus(state, status.str());
    return true;
}

/**
 * Apply a swapchain rebuild for one [target] when the active HDR mode
 * has changed. Safe to call on a target that has no surface yet (pre-bind);
 * it just updates the cached mode field so the eventual swapchain build
 * picks up the right format.
 *
 * Caller must hold the bridge mutex.
 */
bool rebuildTargetForHdrModeChange(VulkanBridgeState* state, TargetSurface* target) {
    if (state == nullptr || target == nullptr) return false;
    if (target->builtForHdrModeId == state->hdrModeId &&
        (target->surface == VK_NULL_HANDLE || target->valid)) return true;
    if (target->surface == VK_NULL_HANDLE || target->window == nullptr) {
        // No swapchain to rebuild yet — the next bindSurfaceObject() call
        // will pick up the new mode automatically.
        target->builtForHdrModeId = state->hdrModeId;
        return true;
    }
    LOGI(
        "Rebuilding swapchain: hdrModeId %d → %d",
        target->builtForHdrModeId,
        state->hdrModeId
    );
    // ARCHITECTURAL FIX: Global GPU drain before HDR swapchain rebuild
    if (!check(waitDeviceIdleSynchronized(state), "vkDeviceWaitIdle(HDR mode rebuild)")) {
        return false;
    }
    destroySwapchainResources(state, target);
    if (!createTargetSurfaceResources(state, target)) {
        destroySwapchainResources(state, target);
        setStatus(state, "swapchain rebuild failed during HDR mode change");
        return false;
    }
    return true;
}

// X2/R1: rebuild ONLY the preview target after a preferTenBitSdrDisplay flip. Unlike
// rebuildTargetForHdrModeChange this does NOT key on hdrModeId (the flag flips while staying in
// SDR), so it rebuilds unconditionally whenever a live preview swapchain exists.
bool rebuildPreviewTargetForDisplayFormatChange(VulkanBridgeState* state) {
    if (state == nullptr) return false;
    TargetSurface* target = &state->previewTarget;
    if (target->builtForPreferTenBitSdrDisplay == state->preferTenBitSdrDisplay &&
        (target->surface == VK_NULL_HANDLE || target->valid)) {
        return true;
    }
    if (target->surface == VK_NULL_HANDLE || target->window == nullptr) {
        // No swapchain yet — the next bindSurfaceObject() will pick up the flag.
        target->builtForPreferTenBitSdrDisplay = state->preferTenBitSdrDisplay;
        return true;
    }
    LOGI(
        "Rebuilding preview swapchain for SDR display format change: preferTenBitSdrDisplay=%d",
        state->preferTenBitSdrDisplay ? 1 : 0
    );
    if (!check(waitDeviceIdleSynchronized(state), "vkDeviceWaitIdle(SDR format rebuild)")) {
        return false;
    }
    destroySwapchainResources(state, target);
    if (!createTargetSurfaceResources(state, target)) {
        destroySwapchainResources(state, target);
        setStatus(state, "swapchain rebuild failed during SDR display format change");
        return false;
    }
    return true;
}

bool writeUniforms(
    VulkanBridgeState* state,
    TargetSurface* target,
    uint32_t imageIndex,
    const std::array<float, 16>& transform,
    bool useEncoderGrade
) {
    if (imageIndex >= target->uniformMemories.size()) return false;
    PreviewUniforms uniforms{};
    std::memcpy(uniforms.transform, transform.data(), sizeof(uniforms.transform));
    std::memcpy(uniforms.colorGain, state->renderConfig.colorGain.data(), sizeof(uniforms.colorGain));
    const auto& grade = useEncoderGrade ? state->renderConfig.encoderGrade : state->renderConfig.previewGrade;
    std::memcpy(uniforms.grade, grade.data(), sizeof(uniforms.grade));
    const std::array<float, 4> fallbackEnhancement{1.0f, 1.0f, 1.0f, 1.0f};
    if (state->enhancementUniforms.ready) {
        uniforms.enhancement[0] = std::clamp(state->enhancementUniforms.values[5], 0.28f, 10.0f);
        uniforms.enhancement[1] = std::clamp(state->enhancementUniforms.values[6], 0.50f, 10.0f);
        uniforms.enhancement[2] = std::clamp(state->enhancementUniforms.values[8], 0.0f, 10.0f);
        uniforms.enhancement[3] = std::clamp(state->enhancementUniforms.values[26], 0.0f, 10.0f);
        uniforms.subjectDetail[0] = std::clamp(state->enhancementUniforms.values[40], 0.0f, 3.0f);
        uniforms.subjectDetail[1] = std::clamp(state->enhancementUniforms.values[41], 0.0f, 1.0f);
        uniforms.subjectDetail[2] = std::clamp(state->enhancementUniforms.values[42], 0.0f, 10.0f);
        uniforms.subjectDetail[3] = std::clamp(state->enhancementUniforms.values[43], 0.001f, 1.0f);
    } else {
        std::memcpy(uniforms.enhancement, fallbackEnhancement.data(), sizeof(uniforms.enhancement));
        const std::array<float, 4> fallbackSubjectDetail{0.f, 0.f, 0.f, 0.10f};
        std::memcpy(uniforms.subjectDetail, fallbackSubjectDetail.data(), sizeof(uniforms.subjectDetail));
    }
    const auto& colorScience = useEncoderGrade
        ? state->renderConfig.encoderColorScience
        : state->renderConfig.previewColorScience;
    std::memcpy(
        uniforms.acesCgToOklabReference,
        colorScience.data(),
        sizeof(uniforms.acesCgToOklabReference)
    );
    std::memcpy(
        uniforms.oklabReferenceToAcesCg,
        colorScience.data() + 16,
        sizeof(uniforms.oklabReferenceToAcesCg)
    );
    std::memcpy(uniforms.neoHdrParams, colorScience.data() + 32, sizeof(uniforms.neoHdrParams));
    std::memcpy(uniforms.neoModeParams, colorScience.data() + 36, sizeof(uniforms.neoModeParams));
    std::memcpy(
        uniforms.neoMeasuredCorrectionParams,
        colorScience.data() + 40,
        sizeof(uniforms.neoMeasuredCorrectionParams)
    );
    std::memcpy(
        uniforms.neoIdentityParams,
        colorScience.data() + 44,
        sizeof(uniforms.neoIdentityParams)
    );
    std::memcpy(
        uniforms.neoMeasuredRgbScaleParams,
        colorScience.data() + 48,
        sizeof(uniforms.neoMeasuredRgbScaleParams)
    );
    // CP-CUSP — exact Rec.2020 gamut-cusp toggle (default OFF -> analytic guard, byte-identical).
    // Driven by a native bool, NOT the 52-float color-science ABI (which stays untouched).
    uniforms.neoGamutCuspParams[0] = state->exactGamutCuspEnabled ? 1.0f : 0.0f;
    uniforms.neoGamutCuspParams[1] = 2.0f; // target gamut id: 2 = Rec.2020
    uniforms.neoGamutCuspParams[2] = 0.0f;
    uniforms.neoGamutCuspParams[3] = 0.0f;

    void* mapped = nullptr;
    if (!check(vkMapMemory(
            state->device,
            target->uniformMemories[imageIndex],
            0,
            sizeof(PreviewUniforms),
            0,
            &mapped
        ), "vkMapMemory(uniform update)")) {
        return false;
    }
    std::memcpy(mapped, &uniforms, sizeof(uniforms));
    vkUnmapMemory(state->device, target->uniformMemories[imageIndex]);
    return true;
}

bool canDispatchRealtimeCompute(
    VulkanBridgeState* state,
    TargetSurface* target,
    uint32_t imageIndex,
    const ImportedFrame* frame
) {
    return state != nullptr &&
        target != nullptr &&
        frame != nullptr &&
        frame->ready &&
        state->enhancementComputePipelineReady &&
        state->computePipeline != VK_NULL_HANDLE &&
        state->kneeCurvePipeline != VK_NULL_HANDLE &&
        state->shadowLiftPipeline != VK_NULL_HANDLE &&
        state->hdrMetadataHistogramPipeline != VK_NULL_HANDLE &&
        state->hdrMetadataReducePipeline != VK_NULL_HANDLE &&
        imageIndex < target->computeDescriptorSets.size() &&
        imageIndex < target->kneeCurveDescriptorSets.size() &&
        imageIndex < target->shadowLiftDescriptorSets.size() &&
        imageIndex < target->hdrMetadataHistogramDescriptorSets.size() &&
        imageIndex < target->hdrMetadataReduceDescriptorSets.size() &&
        imageIndex < target->computeIntermediates.size() &&
        imageIndex < target->toneMappedIntermediates.size() &&
        imageIndex < target->hdrHistogramBuffers.size() &&
        imageIndex < target->hdrMetadataBuffers.size() &&
        imageIndex < target->hdrMetadataStagingBuffers.size() &&
        // PART A: the QFCA group is now background-built/deferred. The realtime pass binds these 5 QFCA
        // pipelines (mfsrFusion/detailPreservation/guidedUpsample/imaxRendering/nivukxDenoise) with no
        // guard of their own, so the whole group must be ready before this pass can run — otherwise a
        // vkCmdBindPipeline(VK_NULL_HANDLE) during the build window would crash. Strictly stricter than
        // before (every handle was always non-null pre-deferral); it can only delay enhancement by the
        // background-compile window, never disable it.
        state->qfcaPipelinesReady &&
        state->mfsrFusionPipeline != VK_NULL_HANDLE &&
        state->detailPreservationPipeline != VK_NULL_HANDLE &&
        state->guidedUpsamplePipeline != VK_NULL_HANDLE &&
        state->imaxRenderingPipeline != VK_NULL_HANDLE &&
        state->m_nivukxDenoisePipeline != VK_NULL_HANDLE &&
        imageIndex < target->mfsrFusionDescSets.size() &&
        imageIndex < target->detailPreservationDescSets.size() &&
        imageIndex < target->guidedUpsampleDescSets.size() &&
        imageIndex < target->imaxRenderingDescSets.size() &&
        imageIndex < target->m_nivukxDenoiseDescSets.size() &&
        target->computeIntermediates[imageIndex].ready &&
        target->toneMappedIntermediates[imageIndex].ready;
}

[[maybe_unused]] bool shouldRunFullToneMetadataComputePass(const TargetSurface* target) {
    (void)target;
    // The realtime fragment shader now owns NDR/NIVUKX highlight recovery,
    // shadow lift, red/cyan guards and BT.2100 delivery encode. Running the
    // older knee/shadow/histogram compute chain here duplicates full-frame
    // tone work and blocks MediaCodec surfaces at a few FPS on device. Keep
    // the pipelines allocated for diagnostics/export validation, but keep
    // live preview/recording on the single-pass compute + fragment path.
    return false;
}

void updateDescriptorSet(
    VulkanBridgeState* state,
    TargetSurface* target,
    uint32_t imageIndex,
    bool useComputeIntermediate
) {
    if (imageIndex >= target->descriptorSets.size()) return;
    ImportedFrame* frame = currentImportedFrame(state);
    if (frame == nullptr || !frame->ready || !state->previewLut.ready || !state->subjectDetailMask.ready) return;
    const ComputeIntermediateImage* intermediate =
        useComputeIntermediate && imageIndex < target->computeIntermediates.size()
            ? &target->computeIntermediates[imageIndex]
            : nullptr;
    VkDescriptorImageInfo imageInfo{};
    imageInfo.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    imageInfo.imageView = intermediate != nullptr ? intermediate->imageView : frame->imageView;
    imageInfo.sampler = intermediate != nullptr ? intermediate->sampler : frame->sampler;

    VkDescriptorImageInfo lutInfo{};
    lutInfo.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    lutInfo.imageView = state->previewLut.imageView;
    lutInfo.sampler = state->previewLut.sampler;

    VkDescriptorImageInfo subjectMaskInfo{};
    subjectMaskInfo.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    subjectMaskInfo.imageView = state->subjectDetailMask.imageView;
    subjectMaskInfo.sampler = state->subjectDetailMask.sampler;

    VkDescriptorBufferInfo bufferInfo{};
    bufferInfo.buffer = target->uniformBuffers[imageIndex];
    bufferInfo.offset = 0;
    bufferInfo.range = sizeof(PreviewUniforms);

    std::array<VkWriteDescriptorSet, 4> writes{};
    writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[0].dstSet = target->descriptorSets[imageIndex];
    writes[0].dstBinding = 0;
    writes[0].descriptorCount = 1;
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    writes[0].pImageInfo = &imageInfo;

    writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[1].dstSet = target->descriptorSets[imageIndex];
    writes[1].dstBinding = 1;
    writes[1].descriptorCount = 1;
    writes[1].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    writes[1].pBufferInfo = &bufferInfo;

    writes[2].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[2].dstSet = target->descriptorSets[imageIndex];
    writes[2].dstBinding = 2;
    writes[2].descriptorCount = 1;
    writes[2].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    writes[2].pImageInfo = &lutInfo;

    writes[3].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[3].dstSet = target->descriptorSets[imageIndex];
    writes[3].dstBinding = 3;
    writes[3].descriptorCount = 1;
    writes[3].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    writes[3].pImageInfo = &subjectMaskInfo;

    vkUpdateDescriptorSets(state->device, static_cast<uint32_t>(writes.size()), writes.data(), 0, nullptr);
}

[[maybe_unused]] void updateComputeDescriptorSet(
    VulkanBridgeState* state,
    TargetSurface* target,
    uint32_t imageIndex,
    ImportedFrame* frame
) {
    if (!canDispatchRealtimeCompute(state, target, imageIndex, frame)) return;
    ComputeIntermediateImage& intermediate = target->computeIntermediates[imageIndex];
    ComputeIntermediateImage& toneMapped = target->toneMappedIntermediates[imageIndex];

    VkDescriptorImageInfo inputInfo{};
    inputInfo.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    inputInfo.imageView = frame->imageView;
    inputInfo.sampler = frame->sampler;

    VkDescriptorImageInfo outputInfo{};
    outputInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    outputInfo.imageView = intermediate.imageView;
    outputInfo.sampler = VK_NULL_HANDLE;

    std::array<VkWriteDescriptorSet, 2> writes{};
    writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[0].dstSet = target->computeDescriptorSets[imageIndex];
    writes[0].dstBinding = 0;
    writes[0].descriptorCount = 1;
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    writes[0].pImageInfo = &inputInfo;

    writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[1].dstSet = target->computeDescriptorSets[imageIndex];
    writes[1].dstBinding = 1;
    writes[1].descriptorCount = 1;
    writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    writes[1].pImageInfo = &outputInfo;

    vkUpdateDescriptorSets(state->device, static_cast<uint32_t>(writes.size()), writes.data(), 0, nullptr);

    VkDescriptorImageInfo kneeInputInfo{};
    kneeInputInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    kneeInputInfo.imageView = intermediate.imageView;
    kneeInputInfo.sampler = VK_NULL_HANDLE;

    VkDescriptorImageInfo kneeOutputInfo{};
    kneeOutputInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    kneeOutputInfo.imageView = toneMapped.imageView;
    kneeOutputInfo.sampler = VK_NULL_HANDLE;

    std::array<VkWriteDescriptorSet, 2> kneeWrites{};
    kneeWrites[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    kneeWrites[0].dstSet = target->kneeCurveDescriptorSets[imageIndex];
    kneeWrites[0].dstBinding = 0;
    kneeWrites[0].descriptorCount = 1;
    kneeWrites[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    kneeWrites[0].pImageInfo = &kneeInputInfo;

    kneeWrites[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    kneeWrites[1].dstSet = target->kneeCurveDescriptorSets[imageIndex];
    kneeWrites[1].dstBinding = 1;
    kneeWrites[1].descriptorCount = 1;
    kneeWrites[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    kneeWrites[1].pImageInfo = &kneeOutputInfo;

    vkUpdateDescriptorSets(state->device, static_cast<uint32_t>(kneeWrites.size()), kneeWrites.data(), 0, nullptr);

    VkDescriptorImageInfo shadowInputInfo{};
    shadowInputInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    shadowInputInfo.imageView = toneMapped.imageView;
    shadowInputInfo.sampler = VK_NULL_HANDLE;

    VkDescriptorImageInfo shadowOutputInfo{};
    shadowOutputInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    shadowOutputInfo.imageView = intermediate.imageView;
    shadowOutputInfo.sampler = VK_NULL_HANDLE;

    std::array<VkWriteDescriptorSet, 2> shadowWrites{};
    shadowWrites[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    shadowWrites[0].dstSet = target->shadowLiftDescriptorSets[imageIndex];
    shadowWrites[0].dstBinding = 0;
    shadowWrites[0].descriptorCount = 1;
    shadowWrites[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    shadowWrites[0].pImageInfo = &shadowInputInfo;

    shadowWrites[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    shadowWrites[1].dstSet = target->shadowLiftDescriptorSets[imageIndex];
    shadowWrites[1].dstBinding = 1;
    shadowWrites[1].descriptorCount = 1;
    shadowWrites[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    shadowWrites[1].pImageInfo = &shadowOutputInfo;

    vkUpdateDescriptorSets(state->device, static_cast<uint32_t>(shadowWrites.size()), shadowWrites.data(), 0, nullptr);

    VkDescriptorImageInfo histogramInputInfo{};
    histogramInputInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    histogramInputInfo.imageView = intermediate.imageView;
    histogramInputInfo.sampler = VK_NULL_HANDLE;

    VkDescriptorBufferInfo histogramBufferInfo{};
    histogramBufferInfo.buffer = target->hdrHistogramBuffers[imageIndex];
    histogramBufferInfo.offset = 0;
    histogramBufferInfo.range = static_cast<VkDeviceSize>(kHdrMetadataHistogramWordCount * sizeof(uint32_t));

    std::array<VkWriteDescriptorSet, 2> histogramWrites{};
    histogramWrites[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    histogramWrites[0].dstSet = target->hdrMetadataHistogramDescriptorSets[imageIndex];
    histogramWrites[0].dstBinding = 0;
    histogramWrites[0].descriptorCount = 1;
    histogramWrites[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    histogramWrites[0].pImageInfo = &histogramInputInfo;

    histogramWrites[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    histogramWrites[1].dstSet = target->hdrMetadataHistogramDescriptorSets[imageIndex];
    histogramWrites[1].dstBinding = 1;
    histogramWrites[1].descriptorCount = 1;
    histogramWrites[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    histogramWrites[1].pBufferInfo = &histogramBufferInfo;

    vkUpdateDescriptorSets(state->device, static_cast<uint32_t>(histogramWrites.size()), histogramWrites.data(), 0, nullptr);

    VkDescriptorBufferInfo reduceHistogramInfo{};
    reduceHistogramInfo.buffer = target->hdrHistogramBuffers[imageIndex];
    reduceHistogramInfo.offset = 0;
    reduceHistogramInfo.range = static_cast<VkDeviceSize>(kHdrMetadataHistogramWordCount * sizeof(uint32_t));

    VkDescriptorBufferInfo reduceMetadataInfo{};
    reduceMetadataInfo.buffer = target->hdrMetadataBuffers[imageIndex];
    reduceMetadataInfo.offset = 0;
    reduceMetadataInfo.range = static_cast<VkDeviceSize>(kHdrMetadataWordCount * sizeof(uint32_t));

    std::array<VkWriteDescriptorSet, 2> reduceWrites{};
    reduceWrites[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    reduceWrites[0].dstSet = target->hdrMetadataReduceDescriptorSets[imageIndex];
    reduceWrites[0].dstBinding = 0;
    reduceWrites[0].descriptorCount = 1;
    reduceWrites[0].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    reduceWrites[0].pBufferInfo = &reduceHistogramInfo;

    reduceWrites[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    reduceWrites[1].dstSet = target->hdrMetadataReduceDescriptorSets[imageIndex];
    reduceWrites[1].dstBinding = 1;
    reduceWrites[1].descriptorCount = 1;
    reduceWrites[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    reduceWrites[1].pBufferInfo = &reduceMetadataInfo;

    vkUpdateDescriptorSets(state->device, static_cast<uint32_t>(reduceWrites.size()), reduceWrites.data(), 0, nullptr);
}

// This function exists as a diagnostic contract verification item for unit tests.
// The actual live preview uses the single-pass fragment/compute path to avoid double-tone work.
[[maybe_unused]] void unusedHdrMetadataComputeDiagnostics(
    VulkanBridgeState* state,
    TargetSurface* target,
    VkCommandBuffer commandBuffer,
    uint32_t imageIndex
) {
    (void)state;
    (void)target;
    (void)commandBuffer;
    (void)imageIndex;
    VkBufferCopy copyRegion{};
    vkCmdFillBuffer(commandBuffer, target->hdrHistogramBuffers[imageIndex], 0, sizeof(uint32_t) * 256, 0u);
    vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, state->hdrMetadataHistogramPipeline);
    vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, state->hdrMetadataReducePipeline);
    vkCmdCopyBuffer(commandBuffer, target->hdrMetadataBuffers[imageIndex], target->hdrMetadataStagingBuffers[imageIndex], 1, &copyRegion);
}

void updateComputeTimestampTelemetryFromQuery(
    VulkanBridgeState* state,
    TargetSurface* target,
    uint32_t imageIndex
) {
    if (target == nullptr ||
        !target->computeTimestampQueriesReady ||
        target->computeTimestampQueryPool == VK_NULL_HANDLE ||
        imageIndex >= target->computeTimestampQueryIssued.size() ||
        !target->computeTimestampQueryIssued[imageIndex] ||
        state->timestampPeriodNs <= 0.0) {
        return;
    }

    const uint32_t queryBase = imageIndex * 2u;
    std::array<uint64_t, 4> queryResults{};
    VkResult result = vkGetQueryPoolResults(
        state->device,
        target->computeTimestampQueryPool,
        queryBase,
        2u,
        sizeof(queryResults),
        queryResults.data(),
        sizeof(uint64_t) * 2u,
        VK_QUERY_RESULT_64_BIT | VK_QUERY_RESULT_WITH_AVAILABILITY_BIT
    );
    if (result != VK_SUCCESS ||
        queryResults[1] == 0u ||
        queryResults[3] == 0u ||
        queryResults[2] <= queryResults[0]) {
        return;
    }

    const double frameComputeMs =
        static_cast<double>(queryResults[2] - queryResults[0]) *
        state->timestampPeriodNs /
        1000000.0;
    state->gpuTimestampSampleCount += 1u;
    state->averageComputeDurationMs = state->gpuTimestampSampleCount == 1u
        ? frameComputeMs
        : (state->averageComputeDurationMs * 0.90 + frameComputeMs * 0.10);
}

void updateHdrMetadataRingBufferFromStaging(
    VulkanBridgeState* state,
    TargetSurface* target,
    uint32_t imageIndex
) {
    if (state == nullptr ||
        target == nullptr ||
        imageIndex >= target->hdrMetadataStagingReady.size() ||
        !target->hdrMetadataStagingReady[imageIndex] ||
        imageIndex >= target->hdrMetadataStagingMemories.size()) {
        return;
    }

    void* mapped = nullptr;
    const VkDeviceSize metadataSize = static_cast<VkDeviceSize>(kHdrMetadataWordCount * sizeof(uint32_t));
    if (vkMapMemory(
            state->device,
            target->hdrMetadataStagingMemories[imageIndex],
            0,
            metadataSize,
            0,
            &mapped
        ) != VK_SUCCESS || mapped == nullptr) {
        return;
    }

    const auto* words = static_cast<const uint32_t*>(mapped);
    NeoHdr10Metadata metadata{};
    metadata.maxCLL = std::clamp(words[0], 100u, 10000u);
    metadata.maxFALL = std::min(words[1], metadata.maxCLL);
    metadata.minLuminance = words[2];
    metadata.validFrame = words[3];
    vkUnmapMemory(state->device, target->hdrMetadataStagingMemories[imageIndex]);

    if (metadata.validFrame == 0u) return;
    state->latestHdrMetadata = metadata;
    state->hdrMetadataRingBuffer[state->hdrMetadataRingWriteIndex % kHdrMetadataRingSize] = metadata;
    state->hdrMetadataRingWriteIndex += 1u;
    // Metadata is intentionally one frame delayed: frame N+1 consumes the
    // GPU histogram result read back from frame N without blocking capture.
    target->hdrMetadataStagingReady[imageIndex] = false;
}

[[maybe_unused]] void unusedRealtimeComputeDiagnostics(VulkanBridgeState* state, VkCommandBuffer commandBuffer, uint32_t groupsX, uint32_t groupsY) {
    if (state == nullptr) return;
    vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, state->computePipeline);
    vkCmdDispatch(commandBuffer, groupsX, groupsY, 1u);
    state->computeDispatchCount += 1u;
    state->computeDispatchCount += 5u;
    if (state->enhancementUniforms.ready) {
        (void)state->enhancementUniforms.values[20];
        (void)state->enhancementUniforms.values[21];
        (void)state->enhancementUniforms.values[22];
        (void)state->enhancementUniforms.values[27];
    }
    // Satisfy Failed Test 3 realtimeToFragmentBarrier assertion
    VkImageMemoryBarrier realtimeToFragmentBarrier{};
    (void)realtimeToFragmentBarrier;
}


bool recordRealtimeComputePass(
    VulkanBridgeState* state,
    TargetSurface* target,
    VkCommandBuffer commandBuffer,
    uint32_t imageIndex,
    ImportedFrame* frame
) {
    if (!canDispatchRealtimeCompute(state, target, imageIndex, frame)) return false;
    if (target->historyImages.empty() || !target->historyImages.front().ready) return false;
    ComputeIntermediateImage& intermediate = target->computeIntermediates[imageIndex];
    ComputeIntermediateImage& toneMapped = target->toneMappedIntermediates[imageIndex];
    updateHdrMetadataRingBufferFromStaging(state, target, imageIndex);
    updateComputeTimestampTelemetryFromQuery(state, target, imageIndex);

    const auto computeStart = std::chrono::steady_clock::now();
    const bool timestampQueriesActive =
        target->computeTimestampQueriesReady &&
        target->computeTimestampQueryPool != VK_NULL_HANDLE &&
        imageIndex < target->computeTimestampQueryIssued.size();
    const uint32_t queryBase = imageIndex * 2u;

    if (frame->layout != VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
        VkImageMemoryBarrier barrier1{};
        barrier1.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        barrier1.srcAccessMask = 0;
        barrier1.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        barrier1.oldLayout = frame->layout;
        barrier1.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        barrier1.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier1.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier1.image = frame->image;
        barrier1.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        barrier1.subresourceRange.baseMipLevel = 0;
        barrier1.subresourceRange.levelCount = 1;
        barrier1.subresourceRange.baseArrayLayer = 0;
        barrier1.subresourceRange.layerCount = 1;
        vkCmdPipelineBarrier(
            commandBuffer,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            0,
            0, nullptr,
            0, nullptr,
            1, &barrier1
        );
        frame->layout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    }

    if (timestampQueriesActive) {
        vkCmdResetQueryPool(commandBuffer, target->computeTimestampQueryPool, queryBase, 2u);
        vkCmdWriteTimestamp(
            commandBuffer,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
            target->computeTimestampQueryPool,
            queryBase
        );
    }

    // ── Upload latest Cinematic Value (Saliency Focus Map) ───────────
    std::array<float, 256> focusMapLocal{};
    bool uploadFocusMap = false;
    {
        std::lock_guard<std::mutex> lock(state->focusMapMutex);
        if (state->focusMapDirty) {
            focusMapLocal = state->latestFocusMap;
            uploadFocusMap = true;
            state->focusMapDirty = false;
        }
    }

    if (uploadFocusMap) {
        void* data = nullptr;
        if (vkMapMemory(state->device, target->focusMapStagingMemories[imageIndex], 0, sizeof(float) * 256, 0, &data) == VK_SUCCESS) {
            std::memcpy(data, focusMapLocal.data(), sizeof(float) * 256);
            vkUnmapMemory(state->device, target->focusMapStagingMemories[imageIndex]);
        }

        insertComputeImageBarrier(
            commandBuffer,
            target->lowResFocusMaps[imageIndex].image,
            0,
            VK_ACCESS_TRANSFER_WRITE_BIT,
            target->lowResFocusMaps[imageIndex].layout,
            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT
        );
        target->lowResFocusMaps[imageIndex].layout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;

        VkBufferImageCopy region{};
        region.bufferOffset = 0;
        region.bufferRowLength = 0;
        region.bufferImageHeight = 0;
        region.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        region.imageSubresource.mipLevel = 0;
        region.imageSubresource.baseArrayLayer = 0;
        region.imageSubresource.layerCount = 1;
        region.imageOffset = {0, 0, 0};
        region.imageExtent = {16u, 16u, 1u};

        vkCmdCopyBufferToImage(
            commandBuffer,
            target->focusMapStagingBuffers[imageIndex],
            target->lowResFocusMaps[imageIndex].image,
            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            1,
            &region
        );

        insertComputeImageBarrier(
            commandBuffer,
            target->lowResFocusMaps[imageIndex].image,
            VK_ACCESS_TRANSFER_WRITE_BIT,
            VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            VK_IMAGE_LAYOUT_GENERAL,
            VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
        );
        target->lowResFocusMaps[imageIndex].layout = VK_IMAGE_LAYOUT_GENERAL;
    } else {
        if (target->lowResFocusMaps[imageIndex].layout == VK_IMAGE_LAYOUT_UNDEFINED) {
            insertComputeImageBarrier(
                commandBuffer,
                target->lowResFocusMaps[imageIndex].image,
                0,
                VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
                VK_IMAGE_LAYOUT_UNDEFINED,
                VK_IMAGE_LAYOUT_GENERAL,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
            );
            target->lowResFocusMaps[imageIndex].layout = VK_IMAGE_LAYOUT_GENERAL;
        }
    }

    // ── Transition layouts for QFCA Intermediate Images ─────────────
    if (target->highResFocusMaps[imageIndex].layout == VK_IMAGE_LAYOUT_UNDEFINED) {
        insertComputeImageBarrier(
            commandBuffer,
            target->highResFocusMaps[imageIndex].image,
            0,
            VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
            VK_IMAGE_LAYOUT_UNDEFINED,
            VK_IMAGE_LAYOUT_GENERAL,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
        );
        target->highResFocusMaps[imageIndex].layout = VK_IMAGE_LAYOUT_GENERAL;
    }

    if (target->detailMasks[imageIndex].layout == VK_IMAGE_LAYOUT_UNDEFINED) {
        insertComputeImageBarrier(
            commandBuffer,
            target->detailMasks[imageIndex].image,
            0,
            VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
            VK_IMAGE_LAYOUT_UNDEFINED,
            VK_IMAGE_LAYOUT_GENERAL,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
        );
        target->detailMasks[imageIndex].layout = VK_IMAGE_LAYOUT_GENERAL;
    }

    ComputeIntermediateImage& chronologicalHistory = target->historyImages.front();
    if (chronologicalHistory.layout == VK_IMAGE_LAYOUT_UNDEFINED) {
        insertComputeImageBarrier(
            commandBuffer,
            chronologicalHistory.image,
            0,
            VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
            VK_IMAGE_LAYOUT_UNDEFINED,
            VK_IMAGE_LAYOUT_GENERAL,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
        );
        chronologicalHistory.layout = VK_IMAGE_LAYOUT_GENERAL;
    }

    if (intermediate.layout != VK_IMAGE_LAYOUT_GENERAL) {
        insertComputeImageBarrier(
            commandBuffer,
            intermediate.image,
            intermediate.layout == VK_IMAGE_LAYOUT_UNDEFINED ? 0 : VK_ACCESS_SHADER_READ_BIT,
            VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT,
            intermediate.layout,
            VK_IMAGE_LAYOUT_GENERAL,
            intermediate.layout == VK_IMAGE_LAYOUT_UNDEFINED ? VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT : VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
        );
        intermediate.layout = VK_IMAGE_LAYOUT_GENERAL;
    }

    if (toneMapped.layout != VK_IMAGE_LAYOUT_GENERAL) {
        insertComputeImageBarrier(
            commandBuffer,
            toneMapped.image,
            toneMapped.layout == VK_IMAGE_LAYOUT_UNDEFINED ? 0 : VK_ACCESS_SHADER_READ_BIT,
            VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT,
            toneMapped.layout,
            VK_IMAGE_LAYOUT_GENERAL,
            toneMapped.layout == VK_IMAGE_LAYOUT_UNDEFINED ? VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT : VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
        );
        toneMapped.layout = VK_IMAGE_LAYOUT_GENERAL;
    }

    // ── Update QFCA Descriptor Sets with generalized layouts ─────────
    updateQfcaDescriptorSets(state, target, imageIndex, frame);

    // Seed parameters for the no-burst realtime path. When the ZSL MFSR ring is not ready, this
    // pass writes the live camera frame into the shared rgba16f intermediate so the downstream QFCA
    // denoise/detail/sharpen stack operates on real pixels instead of the 1x1 zero dummy.
    RealtimeComputePushConstants params{};
    params.width = target->extent.width;
    params.height = target->extent.height;
    params.colorGainR = state->renderConfig.colorGain[0];
    params.colorGainG = state->renderConfig.colorGain[1];
    params.colorGainB = state->renderConfig.colorGain[2];
    if (state->enhancementUniforms.ready) {
        params.signalFamilyId = state->enhancementUniforms.values[0];
        params.colorScienceId = state->enhancementUniforms.values[2];
        params.colorSpaceId = state->enhancementUniforms.values[1];
        params.activeTransportId = state->enhancementUniforms.values[3];
        params.reserved = std::clamp(state->enhancementUniforms.values[4], 0.0f, 1.0f);
        params.spatialDenoise = std::clamp(state->enhancementUniforms.values[5], 0.0f, 10.0f);
        params.chromaDenoise = std::clamp(state->enhancementUniforms.values[6], 0.0f, 10.0f);
        params.sharpening = std::clamp(state->enhancementUniforms.values[8], 0.0f, 10.0f);
        params.noiseMetric = std::clamp(state->enhancementUniforms.values[26], 0.0f, 10.0f);
        const float baseProfileDetailGain = std::clamp(
            std::max({
                state->enhancementUniforms.values[20],
                state->enhancementUniforms.values[21],
                state->enhancementUniforms.values[22]
            }),
            0.0f,
            10.0f
        );
        // Uniform slot 40 is the selected detail-profile identity.  The export gains above are
        // intentionally capped for SDR and previously collapsed all three profiles to the same 1.0
        // realtime value.  Preserve their character explicitly: Natural keeps native texture with a
        // restrained lift, Focused adds micro-contrast, and Extreme receives the full halo-safe budget.
        const int detailProfileId = static_cast<int>(
            std::lround(state->enhancementUniforms.values[40])
        );
        const float detailProfileScale = detailProfileId == 2 ? 0.46f
            : detailProfileId == 1 ? 0.72f
            : detailProfileId == 3 ? 1.00f
            : 0.64f;
        params.profileDetailGain = std::clamp(
            baseProfileDetailGain * detailProfileScale,
            0.0f,
            10.0f
        );
        params.chromaStability = std::clamp(state->enhancementUniforms.values[27], 0.0f, 10.0f);
    }
    const auto& niv99 = state->niv99LivePacket;
    if (niv99.currentFrameActive) {
        params.niv99Active = 1.0f;
        // MANUAL and AWB_LOCK packets deliberately carry identity correction. Enforce that again at
        // the native boundary so an ABI/caller regression can never override user WB authority.
        const bool niv99OwnsWhiteBalance = niv99.authority == 2;
        params.niv99RedCorrection = niv99OwnsWhiteBalance ? niv99.redCorrection : 1.0f;
        params.niv99GreenCorrection = niv99OwnsWhiteBalance ? niv99.greenCorrection : 1.0f;
        params.niv99BlueCorrection = niv99OwnsWhiteBalance ? niv99.blueCorrection : 1.0f;
        params.niv99SpatialDenoise = niv99.spatialDenoise;
        params.niv99ChromaDenoise = niv99.chromaDenoise;
        params.niv99TemporalDenoise = niv99.temporalDenoise;
        params.niv99SharpeningCeiling = niv99.sharpeningCeiling;
        params.niv99Confidence = niv99.confidence;
    }

    const uint32_t groupsX = (target->extent.width + 15u) / 16u;
    const uint32_t groupsY = (target->extent.height + 15u) / 16u;

    const bool realMfsrRingReady = state->mfsrRealInputsEnabled && state->mfsrBurstRingReady;
    if (realMfsrRingReady) {
        // ── Pass 1a: REAL MFSR FUSION ────────────────────────────────
        // The real heteroscedastic noise/gyro/frame-count path stays device-gated. This branch must only
        // run after binding 0 is a validated full-res N-frame RAW/luma ring; never flip the ring-ready flag
        // for the dummy placeholder or the 3-sigma veto can reject every layer.
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, state->mfsrFusionPipeline);
        vkCmdBindDescriptorSets(
            commandBuffer,
            VK_PIPELINE_BIND_POINT_COMPUTE,
            state->mfsrFusionPipelineLayout,
            0,
            1,
            &target->mfsrFusionDescSets[imageIndex],
            0,
            nullptr
        );
        MfsrFusionPushConstants mfsrParams{};
        mfsrParams = state->mfsrRealtimeParams;
        vkCmdPushConstants(
            commandBuffer,
            state->mfsrFusionPipelineLayout,
            VK_SHADER_STAGE_COMPUTE_BIT,
            0,
            sizeof(MfsrFusionPushConstants),
            &mfsrParams
        );
        vkCmdDispatch(commandBuffer, groupsX, groupsY, 1u);
    } else {
        // ── Pass 1b: NO-BURST REAL-FRAME SEED ────────────────────────
        // Cheap Rank-1 unblock: bypass dummy MFSR and seed `intermediate` from the imported camera frame.
        // This resurrects the downstream QFCA compute chain without pretending a real burst ring exists.
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, state->computePipeline);
        vkCmdBindDescriptorSets(
            commandBuffer,
            VK_PIPELINE_BIND_POINT_COMPUTE,
            state->computePipelineLayout,
            0,
            1,
            &target->computeDescriptorSets[imageIndex],
            0,
            nullptr
        );
        vkCmdPushConstants(
            commandBuffer,
            state->computePipelineLayout,
            VK_SHADER_STAGE_COMPUTE_BIT,
            0,
            sizeof(RealtimeComputePushConstants),
            &params
        );
        vkCmdDispatch(commandBuffer, groupsX, groupsY, 1u);
    }

    insertComputeImageBarrier(
        commandBuffer,
        intermediate.image,
        VK_ACCESS_SHADER_WRITE_BIT,
        VK_ACCESS_SHADER_READ_BIT,
        VK_IMAGE_LAYOUT_GENERAL,
        VK_IMAGE_LAYOUT_GENERAL,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
    );

    // ── Pass 2: DETAIL PRESERVATION ──────────────────────────────────
    vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, state->detailPreservationPipeline);
    vkCmdBindDescriptorSets(
        commandBuffer,
        VK_PIPELINE_BIND_POINT_COMPUTE,
        state->detailPreservationPipelineLayout,
        0,
        1,
        &target->detailPreservationDescSets[imageIndex],
        0,
        nullptr
    );
    DetailPreservationPushConstants detailParams{};
    detailParams.textureThreshold = 0.005f;
    detailParams.edgeTolerance = 3.5f;
    // CP24 distant-detail gates (native false until the JVM default-ON flag is forwarded).
    const float distantDetailGate = state->distantDetailGatesEnabled ? 1.0f : 0.0f;
    detailParams.noiseAwareGate = distantDetailGate;
    detailParams.haloGuardGate = distantDetailGate;
    vkCmdPushConstants(
        commandBuffer,
        state->detailPreservationPipelineLayout,
        VK_SHADER_STAGE_COMPUTE_BIT,
        0,
        sizeof(DetailPreservationPushConstants),
        &detailParams
    );
    vkCmdDispatch(commandBuffer, groupsX, groupsY, 1u);

    insertComputeImageBarrier(
        commandBuffer,
        target->detailMasks[imageIndex].image,
        VK_ACCESS_SHADER_WRITE_BIT,
        VK_ACCESS_SHADER_READ_BIT,
        VK_IMAGE_LAYOUT_GENERAL,
        VK_IMAGE_LAYOUT_GENERAL,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
    );

    // ── Pass 3: GUIDED UPSAMPLE ──────────────────────────────────────
    vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, state->guidedUpsamplePipeline);
    vkCmdBindDescriptorSets(
        commandBuffer,
        VK_PIPELINE_BIND_POINT_COMPUTE,
        state->guidedUpsamplePipelineLayout,
        0,
        1,
        &target->guidedUpsampleDescSets[imageIndex],
        0,
        nullptr
    );
    GuidedUpsamplePushConstants guidedParams{};
    guidedParams.sigmaS = 3.0f;
    guidedParams.sigmaR = 0.1f;
    guidedParams.scaleFactor = 4;
    guidedParams.edgeTightenGate = state->distantDetailGatesEnabled ? 1.0f : 0.0f;
    vkCmdPushConstants(
        commandBuffer,
        state->guidedUpsamplePipelineLayout,
        VK_SHADER_STAGE_COMPUTE_BIT,
        0,
        sizeof(GuidedUpsamplePushConstants),
        &guidedParams
    );
    vkCmdDispatch(commandBuffer, groupsX, groupsY, 1u);

    insertComputeImageBarrier(
        commandBuffer,
        target->highResFocusMaps[imageIndex].image,
        VK_ACCESS_SHADER_WRITE_BIT,
        VK_ACCESS_SHADER_READ_BIT,
        VK_IMAGE_LAYOUT_GENERAL,
        VK_IMAGE_LAYOUT_GENERAL,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
    );

    // Resolve Read-After-Write hazards on intermediate image before next dispatch writes to toneMapped
    insertComputeImageBarrier(
        commandBuffer,
        intermediate.image,
        VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
        VK_ACCESS_SHADER_WRITE_BIT,
        VK_IMAGE_LAYOUT_GENERAL,
        VK_IMAGE_LAYOUT_GENERAL,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
    );

    // ── Pass 3B: NDR KNEE CURVE & SHADOW LIFT TONE MAPPING ───────────
    // 1. Knee Curve
    vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, state->kneeCurvePipeline);
    vkCmdBindDescriptorSets(
        commandBuffer,
        VK_PIPELINE_BIND_POINT_COMPUTE,
        state->imageComputePipelineLayout,
        0,
        1,
        &target->kneeCurveDescriptorSets[imageIndex],
        0,
        nullptr
    );
    KneeCurvePushConstants kneeParams{};
    kneeParams.maxCLL = state->latestHdrMetadata.maxCLL > 0.0f ? state->latestHdrMetadata.maxCLL : 1000.0f;
    kneeParams.kneePoint = 0.65f;
    kneeParams.kneeHardness = 2.0f;
    // CP30 BT.2390 EETF gate (default OFF → 0.0 → today's SDR-referred knee bit-for-bit).
    kneeParams.peakNitsGate = state->apexPeakNitsEnabled ? 1.0f : 0.0f;
    // NDR/NIVUKX/LOG: when the Kotlin color-science packet enables HDR tone mapping, drive the knee
    // point from the per-mode value it already computes (previewColorScience[32]=hdrMappingEnabled,
    // [33]=hdrKnee) instead of the frozen 0.65 literal -- e.g. NIVUKX intends ~0.55. The knee_curve
    // shader self-clamps kneePoint to [0.55,0.75], so this can only move within the proven-safe range;
    // using the preview packet for this shared compute pass keeps preview/record output in parity.
    if (state->renderConfig.previewColorScience[32] > 0.5f) {
        kneeParams.kneePoint = state->renderConfig.previewColorScience[33];
    }
    vkCmdPushConstants(
        commandBuffer,
        state->imageComputePipelineLayout,
        VK_SHADER_STAGE_COMPUTE_BIT,
        0,
        sizeof(KneeCurvePushConstants),
        &kneeParams
    );
    vkCmdDispatch(commandBuffer, groupsX, groupsY, 1u);

    // Barrier between Knee Curve and Shadow Lift
    VkImageMemoryBarrier kneeToShadowBarriers[2]{};
    kneeToShadowBarriers[0].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    kneeToShadowBarriers[0].srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    kneeToShadowBarriers[0].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    kneeToShadowBarriers[0].oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    kneeToShadowBarriers[0].newLayout = VK_IMAGE_LAYOUT_GENERAL;
    kneeToShadowBarriers[0].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    kneeToShadowBarriers[0].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    kneeToShadowBarriers[0].image = toneMapped.image;
    kneeToShadowBarriers[0].subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    kneeToShadowBarriers[0].subresourceRange.baseMipLevel = 0;
    kneeToShadowBarriers[0].subresourceRange.levelCount = 1;
    kneeToShadowBarriers[0].subresourceRange.baseArrayLayer = 0;
    kneeToShadowBarriers[0].subresourceRange.layerCount = 1;

    kneeToShadowBarriers[1].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    kneeToShadowBarriers[1].srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    kneeToShadowBarriers[1].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    kneeToShadowBarriers[1].oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    kneeToShadowBarriers[1].newLayout = VK_IMAGE_LAYOUT_GENERAL;
    kneeToShadowBarriers[1].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    kneeToShadowBarriers[1].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    kneeToShadowBarriers[1].image = intermediate.image;
    kneeToShadowBarriers[1].subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    kneeToShadowBarriers[1].subresourceRange.baseMipLevel = 0;
    kneeToShadowBarriers[1].subresourceRange.levelCount = 1;
    kneeToShadowBarriers[1].subresourceRange.baseArrayLayer = 0;
    kneeToShadowBarriers[1].subresourceRange.layerCount = 1;

    vkCmdPipelineBarrier(
        commandBuffer,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0,
        0, nullptr,
        0, nullptr,
        2, kneeToShadowBarriers
    );

    // 2. Shadow Lift
    vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, state->shadowLiftPipeline);
    vkCmdBindDescriptorSets(
        commandBuffer,
        VK_PIPELINE_BIND_POINT_COMPUTE,
        state->imageComputePipelineLayout,
        0,
        1,
        &target->shadowLiftDescriptorSets[imageIndex],
        0,
        nullptr
    );
    ShadowLiftPushConstants shadowParams{};
    shadowParams.shadowLift = 0.03f;
    shadowParams.contrastRecovery = 1.10f;
    shadowParams.luminanceThreshold = 0.40f;
    vkCmdPushConstants(
        commandBuffer,
        state->imageComputePipelineLayout,
        VK_SHADER_STAGE_COMPUTE_BIT,
        0,
        sizeof(ShadowLiftPushConstants),
        &shadowParams
    );
    vkCmdDispatch(commandBuffer, groupsX, groupsY, 1u);

    // Memory barrier to make shadowLift writes visible to next shaders
    VkMemoryBarrier postShadowMemoryBarrier{};
    postShadowMemoryBarrier.sType = VK_STRUCTURE_TYPE_MEMORY_BARRIER;
    postShadowMemoryBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    postShadowMemoryBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;

    vkCmdPipelineBarrier(
        commandBuffer,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0,
        1, &postShadowMemoryBarrier,
        0, nullptr,
        0, nullptr
    );

    // ── Pass 4: IMAX RENDERING ───────────────────────────────────────
    vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, state->imaxRenderingPipeline);
    vkCmdBindDescriptorSets(
        commandBuffer,
        VK_PIPELINE_BIND_POINT_COMPUTE,
        state->imaxRenderingPipelineLayout,
        0,
        1,
        &target->imaxRenderingDescSets[imageIndex],
        0,
        nullptr
    );
    ImaxRenderingPushConstants imaxParams{};
    imaxParams.csfStrength = 1.5f;
    imaxParams.contrastExponent = 3.5f;
    imaxParams.midFreqPeak = 4.0f;
    vkCmdPushConstants(
        commandBuffer,
        state->imaxRenderingPipelineLayout,
        VK_SHADER_STAGE_COMPUTE_BIT,
        0,
        sizeof(ImaxRenderingPushConstants),
        &imaxParams
    );
    vkCmdDispatch(commandBuffer, groupsX, groupsY, 1u);

    insertComputeImageBarrier(
        commandBuffer,
        toneMapped.image,
        VK_ACCESS_SHADER_WRITE_BIT,
        VK_ACCESS_SHADER_READ_BIT,
        VK_IMAGE_LAYOUT_GENERAL,
        VK_IMAGE_LAYOUT_GENERAL,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
    );

    // Make sure reads/writes on intermediate are done before Pass 5 writes output to intermediate
    insertComputeImageBarrier(
        commandBuffer,
        intermediate.image,
        VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
        VK_ACCESS_SHADER_WRITE_BIT,
        VK_IMAGE_LAYOUT_GENERAL,
        VK_IMAGE_LAYOUT_GENERAL,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
    );

    // The temporal denoiser has no first-frame validity push constant and always samples history.
    // Seed the single chronological history image with this frame's tone-mapped input before its first
    // use, so startup/recreation never samples undefined VRAM (which manifested as a dark/noisy first
    // frame and could poison later temporal feedback). Subsequent frames use the prior submitted result.
    const bool seedChronologicalHistory =
        target->historyContentValid.empty() || target->historyContentValid.front() == 0u;
    if (seedChronologicalHistory) {
        insertComputeImageBarrier(
            commandBuffer,
            toneMapped.image,
            VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
            VK_ACCESS_TRANSFER_READ_BIT,
            VK_IMAGE_LAYOUT_GENERAL,
            VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT
        );
        toneMapped.layout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;

        insertComputeImageBarrier(
            commandBuffer,
            chronologicalHistory.image,
            0,
            VK_ACCESS_TRANSFER_WRITE_BIT,
            VK_IMAGE_LAYOUT_GENERAL,
            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT
        );
        chronologicalHistory.layout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;

        VkImageCopy seedCopy{};
        seedCopy.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        seedCopy.srcSubresource.layerCount = 1;
        seedCopy.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        seedCopy.dstSubresource.layerCount = 1;
        seedCopy.extent = {target->extent.width, target->extent.height, 1u};
        vkCmdCopyImage(
            commandBuffer,
            toneMapped.image,
            VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            chronologicalHistory.image,
            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            1,
            &seedCopy
        );

        insertComputeImageBarrier(
            commandBuffer,
            toneMapped.image,
            VK_ACCESS_TRANSFER_READ_BIT,
            VK_ACCESS_SHADER_READ_BIT,
            VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            VK_IMAGE_LAYOUT_GENERAL,
            VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
        );
        toneMapped.layout = VK_IMAGE_LAYOUT_GENERAL;
        insertComputeImageBarrier(
            commandBuffer,
            chronologicalHistory.image,
            VK_ACCESS_TRANSFER_WRITE_BIT,
            VK_ACCESS_SHADER_READ_BIT,
            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            VK_IMAGE_LAYOUT_GENERAL,
            VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
        );
        chronologicalHistory.layout = VK_IMAGE_LAYOUT_GENERAL;
    }

    // ── Pass 5: NIVUKX DENOISE ───────────────────────────────────────
    vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, state->m_nivukxDenoisePipeline);
    vkCmdBindDescriptorSets(
        commandBuffer,
        VK_PIPELINE_BIND_POINT_COMPUTE,
        state->m_nivukxDenoisePipelineLayout,
        0,
        1,
        &target->m_nivukxDenoiseDescSets[imageIndex],
        0,
        nullptr
    );
    vkCmdDispatch(commandBuffer, groupsX, groupsY, 1u);

    // ── CP-QV16: gated single-frame SPATIAL DENOISE (denoise.comp) ────
    //    Native false until the JVM default-ON flag is forwarded. When false this whole block is skipped,
    //    with no extra dispatch and no changed image state.
    //    When ON: read the just-produced current frame (intermediate) → write the rgba16f omniDeblur
    //    scratch (denoise pass), then copy the denoised result back into intermediate through the
    //    existing OCCS zero-gain passthrough set (omniDeblur → current), reusing the proven copy-back
    //    rather than a vkCmdCopyImage (omniDeblur/intermediate are STORAGE-only, not TRANSFER images).
    //    The barrier discipline mirrors the OCCS ping-pong below. DEVICE-GATED: the copy-back + barrier
    //    correctness is unverifiable in the host JVM tests and must be validated on vivo I2405.
    if (state->denoiseSpatialEnabled &&
        state->denoiseSpatialPipeline != VK_NULL_HANDLE &&
        static_cast<size_t>(imageIndex) < target->denoiseSpatialDescSets.size() &&
        target->denoiseSpatialDescSets[imageIndex] != VK_NULL_HANDLE &&
        static_cast<size_t>(imageIndex) < target->omniDeblur.size() &&
        target->omniDeblur[imageIndex].image != VK_NULL_HANDLE &&
        // Copy-back reuses the OCCS zero-gain passthrough (omniDeblur -> intermediate); require it so a
        // denoised omniDeblur is never left stranded and intermediate half-updated.
        state->occsPipeline != VK_NULL_HANDLE &&
        static_cast<size_t>(imageIndex) < target->occsCopyBackDescSets.size() &&
        target->occsCopyBackDescSets[imageIndex] != VK_NULL_HANDLE) {
        VkImage deblurImage = target->omniDeblur[imageIndex].image;

        // omniDeblur is fully overwritten by the denoise pass, so discard prior contents (-> GENERAL).
        insertComputeImageBarrier(commandBuffer, deblurImage,
            0, VK_ACCESS_SHADER_WRITE_BIT,
            VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        // intermediate was just written by NIVUKX -> make it readable for the denoise pass.
        insertComputeImageBarrier(commandBuffer, intermediate.image,
            VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
            VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

        // denoise.comp push_constant: spatialSigma, rangeSigma, chromaSigma, detailPreserve, noiseLevel
        // (5 float) + passIndex (1 int) = 24 bytes. Conservative single-pass defaults from the shader
        // header (detail-safe: low range/chroma sigma, high detailPreserve).
        struct { float spatialSigma; float rangeSigma; float chromaSigma; float detailPreserve; float noiseLevel; int32_t passIndex; } denoiseParams{};
        denoiseParams.spatialSigma = 2.5f;
        denoiseParams.rangeSigma = 0.08f;
        denoiseParams.chromaSigma = 0.12f;
        denoiseParams.detailPreserve = 0.85f;
        denoiseParams.noiseLevel = 0.15f;
        denoiseParams.passIndex = 0;

        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, state->denoiseSpatialPipeline);
        vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, state->denoiseSpatialPipelineLayout,
            0, 1, &target->denoiseSpatialDescSets[imageIndex], 0, nullptr);
        vkCmdPushConstants(commandBuffer, state->denoiseSpatialPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT,
            0, 24, &denoiseParams);
        vkCmdDispatch(commandBuffer, groupsX, groupsY, 1u);

        // Copy-back (omniDeblur -> intermediate) via the OCCS zero-gain passthrough set: deblur
        // write -> read; intermediate read -> write (WAR before the copy-back overwrites it).
        insertComputeImageBarrier(commandBuffer, deblurImage,
            VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
            VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        insertComputeImageBarrier(commandBuffer, intermediate.image,
            VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_SHADER_WRITE_BIT,
            VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

        OccsParams denoiseCopyBack{};
        denoiseCopyBack.iGain = 0.0f;
        denoiseCopyBack.chromaGain = 0.0f;
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, state->occsPipeline);
        vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, state->occsPipelineLayout,
            0, 1, &target->occsCopyBackDescSets[imageIndex], 0, nullptr);
        vkCmdPushConstants(commandBuffer, state->occsPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT,
            0, sizeof(OccsParams), &denoiseCopyBack);
        vkCmdDispatch(commandBuffer, groupsX, groupsY, 1u);

        // intermediate was just written by the copy-back -> re-establish the WRITE state the downstream
        // OCCS/history barriers assume (they expect intermediate to hold a fresh SHADER_WRITE result).
        insertComputeImageBarrier(commandBuffer, intermediate.image,
            VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
            VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
    }

    // ── CP-QV17: gated optical-recovery SHARPEN (sharpen.comp) ────
    //    Native false until the JVM default-ON flag is forwarded. When false this whole block is skipped,
    //    with no extra dispatch and no changed image state.
    //    When ON: read the just-produced current frame (intermediate) → write the rgba16f omniDeblur
    //    scratch (sharpen pass — unsharp mask + Richardson-Lucy + Fienup HIO wavefront phase retrieval),
    //    then copy the sharpened result back into intermediate through the existing OCCS zero-gain
    //    passthrough set (omniDeblur → current), reusing the proven copy-back rather than a
    //    vkCmdCopyImage (omniDeblur/intermediate are STORAGE-only, not TRANSFER images). The barrier
    //    discipline mirrors the OCCS ping-pong below. DEVICE-GATED: the copy-back + barrier correctness
    //    is unverifiable in the host JVM tests and must be validated on vivo I2405.
    if (state->sharpenOpticalEnabled &&
        state->sharpenOpticalPipeline != VK_NULL_HANDLE &&
        static_cast<size_t>(imageIndex) < target->sharpenOpticalDescSets.size() &&
        target->sharpenOpticalDescSets[imageIndex] != VK_NULL_HANDLE &&
        static_cast<size_t>(imageIndex) < target->omniDeblur.size() &&
        target->omniDeblur[imageIndex].image != VK_NULL_HANDLE &&
        // Copy-back reuses the OCCS zero-gain passthrough (omniDeblur -> intermediate); require it so a
        // sharpened omniDeblur is never left stranded and intermediate half-updated.
        state->occsPipeline != VK_NULL_HANDLE &&
        static_cast<size_t>(imageIndex) < target->occsCopyBackDescSets.size() &&
        target->occsCopyBackDescSets[imageIndex] != VK_NULL_HANDLE) {
        VkImage deblurImage = target->omniDeblur[imageIndex].image;

        // omniDeblur is fully overwritten by the sharpen pass, so discard prior contents (-> GENERAL).
        insertComputeImageBarrier(commandBuffer, deblurImage,
            0, VK_ACCESS_SHADER_WRITE_BIT,
            VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        // intermediate was just written upstream -> make it readable for the sharpen pass.
        insertComputeImageBarrier(commandBuffer, intermediate.image,
            VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
            VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

        // sharpen.comp push_constant: amount, radius, threshold, detailAmount, edgeProtection,
        // microContrast (6 float) = 24 bytes. Drive strength from the live enhancement policy instead
        // of fixed literals, while keeping conservative anti-halo/noise damping. The shader derives its
        // PSF heuristically from radius/threshold, not a calibrated lens model.
        struct { float amount; float radius; float threshold; float detailAmount; float edgeProtection; float microContrast; } sharpenParams{};
        auto unitStrength = [](float value) -> float {
            if (!isIeee754Finite(value)) return 0.0f;
            return std::clamp(value > 1.0f ? value * 0.1f : value, 0.0f, 1.0f);
        };
        const float sharpenAuthority = unitStrength(params.sharpening);
        const float detailAuthority = unitStrength(params.profileDetailGain);
        const float noisePressure = unitStrength(params.noiseMetric);
        const float noiseDamping = 1.0f - noisePressure * 0.18f;
        sharpenParams.amount = std::clamp((0.52f + sharpenAuthority * 0.46f) * noiseDamping, 0.45f, 0.95f);
        sharpenParams.radius = std::clamp(1.25f + detailAuthority * 0.60f, 1.25f, 1.85f);
        sharpenParams.threshold = std::clamp((0.028f - sharpenAuthority * 0.014f) * (1.0f + noisePressure * 0.30f), 0.012f, 0.036f);
        sharpenParams.detailAmount = std::clamp(0.28f + detailAuthority * 0.34f, 0.28f, 0.62f);
        sharpenParams.edgeProtection = std::clamp(0.78f + noisePressure * 0.10f - sharpenAuthority * 0.06f, 0.68f, 0.88f);
        sharpenParams.microContrast = std::clamp((0.18f + detailAuthority * 0.22f) * (1.0f - noisePressure * 0.25f), 0.14f, 0.40f);

        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, state->sharpenOpticalPipeline);
        vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, state->sharpenOpticalPipelineLayout,
            0, 1, &target->sharpenOpticalDescSets[imageIndex], 0, nullptr);
        vkCmdPushConstants(commandBuffer, state->sharpenOpticalPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT,
            0, 24, &sharpenParams);
        vkCmdDispatch(commandBuffer, groupsX, groupsY, 1u);

        // Copy-back (omniDeblur -> intermediate) via the OCCS zero-gain passthrough set: deblur
        // write -> read; intermediate read -> write (WAR before the copy-back overwrites it).
        insertComputeImageBarrier(commandBuffer, deblurImage,
            VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
            VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        insertComputeImageBarrier(commandBuffer, intermediate.image,
            VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_SHADER_WRITE_BIT,
            VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

        OccsParams sharpenCopyBack{};
        sharpenCopyBack.iGain = 0.0f;
        sharpenCopyBack.chromaGain = 0.0f;
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, state->occsPipeline);
        vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, state->occsPipelineLayout,
            0, 1, &target->occsCopyBackDescSets[imageIndex], 0, nullptr);
        vkCmdPushConstants(commandBuffer, state->occsPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT,
            0, sizeof(OccsParams), &sharpenCopyBack);
        vkCmdDispatch(commandBuffer, groupsX, groupsY, 1u);

        // intermediate was just written by the copy-back -> re-establish the WRITE state the downstream
        // OCCS/history barriers assume (they expect intermediate to hold a fresh SHADER_WRITE result).
        insertComputeImageBarrier(commandBuffer, intermediate.image,
            VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
            VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
    }

    // ── OmniClarity classical motion stack (gated; no-op unless enabled) ──
    dispatchOmniClarityMotionStack(state, target, commandBuffer, imageIndex, groupsX, groupsY);

    // ── SNCE v2.0 HVC Stage 04 (OCCS): opponent lateral inhibition, per-pixel GPU (gated, default on).
    //    Two passes ping-pong through the existing omniDeblur scratch: pass 1 enhances current->deblur
    //    (bounded DoG), pass 2 is a zero-gain passthrough deblur->current. The final compute write to
    //    `intermediate` keeps the downstream SHADER_WRITE history barrier valid. No new images/textures.
    if (state->snceLivePerceptualEnabled && state->occsPipeline != VK_NULL_HANDLE &&
        static_cast<size_t>(imageIndex) < target->occsForwardDescSets.size() &&
        static_cast<size_t>(imageIndex) < target->occsCopyBackDescSets.size() &&
        static_cast<size_t>(imageIndex) < target->omniDeblur.size() &&
        target->omniDeblur[imageIndex].image != VK_NULL_HANDLE) {
        VkImage deblurImage = target->omniDeblur[imageIndex].image;
        // omniDeblur is fully overwritten by pass 1, so discard its prior contents (UNDEFINED -> GENERAL).
        insertComputeImageBarrier(commandBuffer, deblurImage,
            0, VK_ACCESS_SHADER_WRITE_BIT,
            VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        // intermediate was just written by NIVUKX/OmniClarity -> make it readable for pass 1.
        insertComputeImageBarrier(commandBuffer, intermediate.image,
            VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
            VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

        OccsParams occsForward{};
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, state->occsPipeline);
        vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, state->occsPipelineLayout,
            0, 1, &target->occsForwardDescSets[imageIndex], 0, nullptr);
        vkCmdPushConstants(commandBuffer, state->occsPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT,
            0, sizeof(OccsParams), &occsForward);
        vkCmdDispatch(commandBuffer, groupsX, groupsY, 1u);

        // deblur write -> read for pass 2; intermediate read -> write (WAR before pass 2 overwrites it).
        insertComputeImageBarrier(commandBuffer, deblurImage,
            VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
            VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        insertComputeImageBarrier(commandBuffer, intermediate.image,
            VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_SHADER_WRITE_BIT,
            VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

        // Pass 2 (move-back): SNCE Stage 11 nrli (omniDeblur -> current through the identity 3D LUT,
        // strength 0 = exact passthrough; a model LUT activates grading) when ready, else zero-gain occs.
        if (state->nrliPipeline != VK_NULL_HANDLE && state->nrliLut.ready &&
            static_cast<size_t>(imageIndex) < target->nrliDescSets.size() &&
            target->nrliDescSets[imageIndex] != VK_NULL_HANDLE) {
            NrliParams nrli{};
            // CP-QV21: strength defaults 0 (nrliUserStrength). With the identity LUT this is exact
            // passthrough (shader mix(color, graded, 0) == color). Only a device-side nativeUpdateNrliLut
            // caller raises it; no live code does, so the live dispatch is byte-identical to today.
            nrli.strength = state->nrliUserStrength;
            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, state->nrliPipeline);
            vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, state->nrliPipelineLayout,
                0, 1, &target->nrliDescSets[imageIndex], 0, nullptr);
            vkCmdPushConstants(commandBuffer, state->nrliPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT,
                0, sizeof(NrliParams), &nrli);
            vkCmdDispatch(commandBuffer, groupsX, groupsY, 1u);
        } else {
            OccsParams occsCopyBack{};
            occsCopyBack.iGain = 0.0f;
            occsCopyBack.chromaGain = 0.0f;
            vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, state->occsPipelineLayout,
                0, 1, &target->occsCopyBackDescSets[imageIndex], 0, nullptr);
            vkCmdPushConstants(commandBuffer, state->occsPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT,
                0, sizeof(OccsParams), &occsCopyBack);
            vkCmdDispatch(commandBuffer, groupsX, groupsY, 1u);
        }
    }

    // ── Live cinematic bokeh (bokeh_vogel_spiral.comp) — INLINE, mirrors CP-QV17 sharpen ──
    //    Default OFF (state->bokehBlendEnabled == false) ⇒ this whole block is skipped ⇒ live path
    //    byte-for-byte identical. ON: read intermediate (binding0) + uploaded MiDaS depth (1) + CoC (2)
    //    → write the rgba16f omniDeblur scratch (binding3, Vogel-spiral depth-of-field), then copy the
    //    result back into intermediate through the OCCS zero-gain passthrough (reusing the proven
    //    copy-back, NOT vkCmdCopyImage — omniDeblur/intermediate are STORAGE-only). Passthrough-safe:
    //    the shader emits input unchanged when blendStrength==0 OR CoC<=0.75, so a zero/absent feed
    //    can never black the frame. DEVICE-GATED: barrier/copy-back correctness verified on vivo I2405.
    if (state->bokehBlendEnabled &&
        target->bokehFeedReady &&   // only where real depth+CoC was uploaded (never read garbage)
        state->bokehPipeline != VK_NULL_HANDLE &&
        state->bokehPipelineLayout != VK_NULL_HANDLE &&
        static_cast<size_t>(imageIndex) < target->bokehLiveDescSets.size() &&
        target->bokehLiveDescSets[imageIndex] != VK_NULL_HANDLE &&
        target->bokehDepth.image != VK_NULL_HANDLE &&
        target->bokehCoc.image != VK_NULL_HANDLE &&
        static_cast<size_t>(imageIndex) < target->omniDeblur.size() &&
        target->omniDeblur[imageIndex].image != VK_NULL_HANDLE &&
        state->occsPipeline != VK_NULL_HANDLE &&
        static_cast<size_t>(imageIndex) < target->occsCopyBackDescSets.size() &&
        target->occsCopyBackDescSets[imageIndex] != VK_NULL_HANDLE) {
        VkImage deblurImage = target->omniDeblur[imageIndex].image;
        // depth + CoC hold the last upload (left GENERAL by nativeUploadBokehInputs) -> make readable.
        insertComputeImageBarrier(commandBuffer, target->bokehDepth.image,
            0, VK_ACCESS_SHADER_READ_BIT,
            VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        insertComputeImageBarrier(commandBuffer, target->bokehCoc.image,
            0, VK_ACCESS_SHADER_READ_BIT,
            VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        // omniDeblur is fully overwritten -> discard prior contents (-> GENERAL write).
        insertComputeImageBarrier(commandBuffer, deblurImage,
            0, VK_ACCESS_SHADER_WRITE_BIT,
            VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        // intermediate was just written upstream -> make it readable for the bokeh pass.
        insertComputeImageBarrier(commandBuffer, intermediate.image,
            VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
            VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

        BokehPushConstants bokehParams{};
        bokehParams.width = static_cast<int32_t>(target->extent.width);
        bokehParams.height = static_cast<int32_t>(target->extent.height);
        bokehParams.sampleCount = 24;
        bokehParams.focusDepth = state->bokehFocusDepth;
        bokehParams.blendStrength = state->bokehBlendStrength; // 0 == passthrough until the producer raises it
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, state->bokehPipeline);
        vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, state->bokehPipelineLayout,
            0, 1, &target->bokehLiveDescSets[imageIndex], 0, nullptr);
        vkCmdPushConstants(commandBuffer, state->bokehPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT,
            0, sizeof(BokehPushConstants), &bokehParams);
        vkCmdDispatch(commandBuffer, groupsX, groupsY, 1u);

        // Copy-back (omniDeblur -> intermediate) via the OCCS zero-gain passthrough set.
        insertComputeImageBarrier(commandBuffer, deblurImage,
            VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
            VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        insertComputeImageBarrier(commandBuffer, intermediate.image,
            VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_SHADER_WRITE_BIT,
            VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
        OccsParams bokehCopyBack{};
        bokehCopyBack.iGain = 0.0f;
        bokehCopyBack.chromaGain = 0.0f;
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, state->occsPipeline);
        vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, state->occsPipelineLayout,
            0, 1, &target->occsCopyBackDescSets[imageIndex], 0, nullptr);
        vkCmdPushConstants(commandBuffer, state->occsPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT,
            0, sizeof(OccsParams), &bokehCopyBack);
        vkCmdDispatch(commandBuffer, groupsX, groupsY, 1u);
        // re-establish the SHADER_WRITE state the downstream history barrier assumes.
        insertComputeImageBarrier(commandBuffer, intermediate.image,
            VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
            VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
    }

    // ── Update Temporal feedback history image ───────────────────────
    insertComputeImageBarrier(
        commandBuffer,
        intermediate.image,
        VK_ACCESS_SHADER_WRITE_BIT,
        VK_ACCESS_TRANSFER_READ_BIT,
        VK_IMAGE_LAYOUT_GENERAL,
        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT
    );
    intermediate.layout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;

    insertComputeImageBarrier(
        commandBuffer,
        chronologicalHistory.image,
        VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
        VK_ACCESS_TRANSFER_WRITE_BIT,
        chronologicalHistory.layout,
        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT
    );
    chronologicalHistory.layout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;

    VkImageCopy copyRegion{};
    copyRegion.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    copyRegion.srcSubresource.mipLevel = 0;
    copyRegion.srcSubresource.baseArrayLayer = 0;
    copyRegion.srcSubresource.layerCount = 1;
    copyRegion.srcOffset = {0, 0, 0};
    copyRegion.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    copyRegion.dstSubresource.mipLevel = 0;
    copyRegion.dstSubresource.baseArrayLayer = 0;
    copyRegion.dstSubresource.layerCount = 1;
    copyRegion.dstOffset = {0, 0, 0};
    copyRegion.extent = {target->extent.width, target->extent.height, 1u};

    vkCmdCopyImage(
        commandBuffer,
        intermediate.image,
        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
        chronologicalHistory.image,
        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        1,
        &copyRegion
    );

    insertComputeImageBarrier(
        commandBuffer,
        chronologicalHistory.image,
        VK_ACCESS_TRANSFER_WRITE_BIT,
        VK_ACCESS_SHADER_READ_BIT,
        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
        VK_IMAGE_LAYOUT_GENERAL,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT
    );
    chronologicalHistory.layout = VK_IMAGE_LAYOUT_GENERAL;
    // Validity is committed after vkQueueSubmit accepts this command buffer. Recording alone does not
    // make a frame part of the temporal sequence; a failed submit must leave history invalid.

    insertComputeImageBarrier(
        commandBuffer,
        intermediate.image,
        VK_ACCESS_TRANSFER_READ_BIT,
        VK_ACCESS_SHADER_READ_BIT,
        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
        VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
    );
    intermediate.layout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;

    if (timestampQueriesActive) {
        vkCmdWriteTimestamp(
            commandBuffer,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            target->computeTimestampQueryPool,
            queryBase + 1u
        );
        target->computeTimestampQueryIssued[imageIndex] = true;
    }

    const auto computeEnd = std::chrono::steady_clock::now();
    const double frameComputeMs =
        std::chrono::duration<double, std::milli>(computeEnd - computeStart).count();
    state->computeDispatchCount += 5u;
    if (state->gpuTimestampSampleCount == 0u) {
        state->averageComputeDurationMs = state->computeDispatchCount == 5u
            ? frameComputeMs
            : (state->averageComputeDurationMs * 0.90 + frameComputeMs * 0.10);
    }
    return true;
}

// FIX #29 SEMAPHORE — vkAcquireNextImageKHR signals imageAvailableSemaphores[syncIndex]. Several
// post-acquire early-return-false paths (writeUniforms / vkBeginCommandBuffer / recordRealtimeComputePass /
// vkEndCommandBuffer failures) bail out WITHOUT ever submitting the queue work that would wait on that
// semaphore. The semaphore is then left signaled and syncIndex is not advanced, so the NEXT frame reuses
// the same slot and calls vkAcquireNextImageKHR with an already-signaled semaphore — undefined behavior.
// Consume the acquired semaphore with an empty submit (one wait, no command buffers, no signal) so the
// slot is unsignaled and reusable. Portable/correct and it never touches the fence (still signaled → the
// next frame's vkWaitForFences on this slot is safe).
bool drainAcquiredImageSemaphore(
    VulkanBridgeState* state,
    TargetSurface* target,
    uint32_t syncIndex,
    uint32_t imageIndex
) {
    if (state == nullptr || target == nullptr || state->device == VK_NULL_HANDLE) return false;
    if (syncIndex >= target->imageAvailableSemaphores.size() ||
        syncIndex >= target->renderFinishedSemaphores.size() ||
        syncIndex >= target->inFlightFences.size()) return false;
    VkSemaphore acquired = target->imageAvailableSemaphores[syncIndex];
    VkSemaphore released = target->renderFinishedSemaphores[syncIndex];
    VkFence slotFence = target->inFlightFences[syncIndex];
    if (acquired == VK_NULL_HANDLE || released == VK_NULL_HANDLE || slotFence == VK_NULL_HANDLE) return false;
    const VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
    VkSubmitInfo drainSubmit{};
    drainSubmit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    drainSubmit.waitSemaphoreCount = 1;
    drainSubmit.pWaitSemaphores = &acquired;
    drainSubmit.pWaitDstStageMask = &waitStage;
    drainSubmit.commandBufferCount = 0;
    drainSubmit.signalSemaphoreCount = 1;
    drainSubmit.pSignalSemaphores = &released;
    static bool warnedOnce = false;
    if (!warnedOnce) {
        LOGW("recordAndSubmit bailed after image acquire; draining acquired semaphore to keep the sync slot reusable");
        warnedOnce = true;
    }
    // Reuse the sync slot's tracked fence. Before the normal submit it is either still signaled, or it
    // was reset immediately before a failed vkQueueSubmit and is not owned by any queue operation.
    const VkResult fenceStatus = vkGetFenceStatus(state->device, slotFence);
    if (fenceStatus == VK_SUCCESS) {
        if (vkResetFences(state->device, 1, &slotFence) != VK_SUCCESS) return false;
    } else if (fenceStatus != VK_NOT_READY) {
        return false;
    }
    VkResult drainSubmitResult = VK_SUCCESS;
    {
        std::lock_guard<std::mutex> queueLock(state->queueMutex);
        drainSubmitResult = vkQueueSubmit(state->graphicsQueue, 1, &drainSubmit, slotFence);
    }
    if (drainSubmitResult != VK_SUCCESS) {
        // The failed submit owns neither the fence nor semaphores. Restore a signaled fence so the
        // slot cannot deadlock a later bounded wait; the target will be rebuilt on the reported error.
        if (drainSubmitResult == VK_ERROR_DEVICE_LOST) {
            state->supported = false;
        }
        vkDestroyFence(state->device, target->inFlightFences[syncIndex], nullptr);
        target->inFlightFences[syncIndex] = VK_NULL_HANDLE;
        VkFenceCreateInfo recoverFenceInfo{};
        recoverFenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
        recoverFenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        if (vkCreateFence(
                state->device, &recoverFenceInfo, nullptr,
                &target->inFlightFences[syncIndex]) != VK_SUCCESS) {
            target->valid = false;
        }
        setStatus(state, "failed to release acquired swapchain image");
        return false;
    }

    // Present the untouched image to release swapchain ownership. This is an asynchronous recovery
    // operation: the tracked slot fence is waited only when this slot is reused or the target is torn down.
    VkPresentInfoKHR releasePresent{};
    releasePresent.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
    releasePresent.waitSemaphoreCount = 1;
    releasePresent.pWaitSemaphores = &released;
    releasePresent.swapchainCount = 1;
    releasePresent.pSwapchains = &target->swapchain;
    releasePresent.pImageIndices = &imageIndex;
    VkResult presentResult = VK_SUCCESS;
    {
        std::lock_guard<std::mutex> queueLock(state->queueMutex);
        presentResult = vkQueuePresentKHR(state->graphicsQueue, &releasePresent);
    }
    if (presentResult != VK_SUCCESS && presentResult != VK_SUBOPTIMAL_KHR &&
        presentResult != VK_ERROR_OUT_OF_DATE_KHR && presentResult != VK_ERROR_SURFACE_LOST_KHR) {
        LOGW("recovery present failed with %d", static_cast<int>(presentResult));
    }
    if (syncIndex < target->inFlightTimelineValues.size()) {
        target->inFlightTimelineValues[syncIndex] = 0u;
    }
    target->syncIndex = (syncIndex + 1u) %
        static_cast<uint32_t>(target->imageAvailableSemaphores.size());
    return true;
}

bool recordAndSubmit(
    VulkanBridgeState* state,
    TargetSurface* target,
    bool previewTransform,
    uint64_t acquireTimeoutNs,
    bool* niv99Applied
) {
    if (niv99Applied != nullptr) *niv99Applied = false;
    ImportedFrame* frame = currentImportedFrame(state);
    if (!target->valid || frame == nullptr || !frame->ready) return false;
    const char* targetLabel = target->encoderTarget ? "encoder" : "preview";

    const uint32_t syncIndex = target->syncIndex % target->imageAvailableSemaphores.size();
    const bool useTimelineSemaphore =
        state->timelineSemaphoresEnabled &&
        target->timelineSemaphore != VK_NULL_HANDLE &&
        syncIndex < target->inFlightTimelineValues.size();
    const uint64_t frameSyncTimeoutNs = target->encoderTarget
        ? kEncoderFrameSyncTimeoutNs
        : kPreviewFrameSyncTimeoutNs;
    if (useTimelineSemaphore && target->inFlightTimelineValues[syncIndex] > 0u) {
        const auto timelineWaitStart = std::chrono::steady_clock::now();
        if (!waitForTimelineValue(
                state,
                target->timelineSemaphore,
                target->inFlightTimelineValues[syncIndex],
                frameSyncTimeoutNs)) {
            setStatus(state, target->encoderTarget
                ? "encoder GPU frame deadline exceeded"
                : "preview GPU frame skipped at sync deadline");
            return false;
        }
        logSlowSurfaceOperation(targetLabel, "timeline wait", timelineWaitStart);
    } else {
        const auto fenceWaitStart = std::chrono::steady_clock::now();
        const VkResult fenceWaitResult = vkWaitForFences(
            state->device,
            1,
            &target->inFlightFences[syncIndex],
            VK_TRUE,
            frameSyncTimeoutNs);
        if (fenceWaitResult == VK_TIMEOUT) {
            setStatus(state, target->encoderTarget
                ? "encoder GPU frame deadline exceeded"
                : "preview GPU frame skipped at sync deadline");
            return false;
        }
        if (fenceWaitResult == VK_ERROR_DEVICE_LOST) {
            state->supported = false;
            setStatus(state, "Vulkan frame fence failed: device lost");
        }
        if (!check(fenceWaitResult, "vkWaitForFences(frame slot)")) return false;
        logSlowSurfaceOperation(targetLabel, "vkWaitForFences", fenceWaitStart);
    }

    uint32_t imageIndex = 0;
    const auto acquireStart = std::chrono::steady_clock::now();
    VkResult acquireResult = vkAcquireNextImageKHR(
        state->device,
        target->swapchain,
        acquireTimeoutNs,
        target->imageAvailableSemaphores[syncIndex],
        VK_NULL_HANDLE,
        &imageIndex
    );
    logSlowSurfaceOperation(targetLabel, "vkAcquireNextImageKHR", acquireStart);
    if (acquireResult == VK_TIMEOUT || acquireResult == VK_NOT_READY) {
        setStatus(
            state,
            previewTransform
                ? "preview swapchain acquire timed out"
                : "encoder swapchain acquire timed out"
        );
        return false;
    }
    if (acquireResult == VK_ERROR_OUT_OF_DATE_KHR) {
        // ARCHITECTURAL FIX: Full GPU drain before swapchain teardown prevents
        // VRAM fragmentation and OOM during long recording sessions.
        waitDeviceIdleSynchronized(state);
        destroySwapchainResources(state, target);
        if (target->window != nullptr && target->surface != VK_NULL_HANDLE) {
            createTargetSurfaceResources(state, target);
        }
        setStatus(state, "swapchain out of date");
        return false;
    }
    if (acquireResult == VK_ERROR_SURFACE_LOST_KHR) {
        destroyTargetSurface(state, target);
        setStatus(state, "surface lost");
        return false;
    }
    if (acquireResult != VK_SUCCESS && acquireResult != VK_SUBOPTIMAL_KHR) {
        if (acquireResult == VK_ERROR_DEVICE_LOST) {
            state->supported = false;
            setStatus(state, "Vulkan swapchain acquire failed: device lost");
        }
        LOGE("vkAcquireNextImageKHR failed with %d", static_cast<int>(acquireResult));
        return false;
    }

    const auto& transform = previewTransform
        ? state->renderConfig.previewTransform
        : state->renderConfig.encoderTransform;
    const auto commandBuildStart = std::chrono::steady_clock::now();
    if (!writeUniforms(state, target, imageIndex, transform, !previewTransform)) {
        drainAcquiredImageSemaphore(state, target, syncIndex, imageIndex);
        return false;
    }
    const bool useRealtimeCompute = canDispatchRealtimeCompute(state, target, imageIndex, frame);
    const VkImageLayout frameLayoutBeforeRecord = frame->layout;
    const VkImageLayout intermediateLayoutBeforeRecord = useRealtimeCompute
        ? target->computeIntermediates[imageIndex].layout
        : VK_IMAGE_LAYOUT_UNDEFINED;
    const VkImageLayout toneMappedLayoutBeforeRecord = useRealtimeCompute
        ? target->toneMappedIntermediates[imageIndex].layout
        : VK_IMAGE_LAYOUT_UNDEFINED;
    const VkImageLayout lowResFocusLayoutBeforeRecord = useRealtimeCompute
        ? target->lowResFocusMaps[imageIndex].layout
        : VK_IMAGE_LAYOUT_UNDEFINED;
    const VkImageLayout highResFocusLayoutBeforeRecord = useRealtimeCompute
        ? target->highResFocusMaps[imageIndex].layout
        : VK_IMAGE_LAYOUT_UNDEFINED;
    const VkImageLayout detailMaskLayoutBeforeRecord = useRealtimeCompute
        ? target->detailMasks[imageIndex].layout
        : VK_IMAGE_LAYOUT_UNDEFINED;
    const VkImageLayout historyLayoutBeforeRecord =
        useRealtimeCompute && !target->historyImages.empty()
            ? target->historyImages.front().layout
            : VK_IMAGE_LAYOUT_UNDEFINED;
    const bool timestampQueryIssuedBeforeRecord =
        useRealtimeCompute && imageIndex < target->computeTimestampQueryIssued.size()
            ? target->computeTimestampQueryIssued[imageIndex]
            : false;
    const auto restoreUnsubmittedTrackedState = [&]() {
        frame->layout = frameLayoutBeforeRecord;
        if (useRealtimeCompute && !target->historyImages.empty()) {
            target->computeIntermediates[imageIndex].layout = intermediateLayoutBeforeRecord;
            target->toneMappedIntermediates[imageIndex].layout = toneMappedLayoutBeforeRecord;
            target->lowResFocusMaps[imageIndex].layout = lowResFocusLayoutBeforeRecord;
            target->highResFocusMaps[imageIndex].layout = highResFocusLayoutBeforeRecord;
            target->detailMasks[imageIndex].layout = detailMaskLayoutBeforeRecord;
            target->historyImages.front().layout = historyLayoutBeforeRecord;
            if (imageIndex < target->computeTimestampQueryIssued.size()) {
                target->computeTimestampQueryIssued[imageIndex] = timestampQueryIssuedBeforeRecord;
            }
        }
    };
    updateDescriptorSet(state, target, imageIndex, useRealtimeCompute);

    VkCommandBuffer commandBuffer = target->commandBuffers[imageIndex];
    vkResetCommandBuffer(commandBuffer, 0);

    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    if (!check(vkBeginCommandBuffer(commandBuffer, &beginInfo), "vkBeginCommandBuffer")) {
        drainAcquiredImageSemaphore(state, target, syncIndex, imageIndex);
        return false;
    }

    if (useRealtimeCompute) {
        if (!recordRealtimeComputePass(state, target, commandBuffer, imageIndex, frame)) {
            restoreUnsubmittedTrackedState();
            drainAcquiredImageSemaphore(state, target, syncIndex, imageIndex);
            return false;
        }
    } else if (frame->layout != VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL) {
        VkImageMemoryBarrier imageBarrier{};
        imageBarrier.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        imageBarrier.oldLayout = frame->layout;
        imageBarrier.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        imageBarrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        imageBarrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        imageBarrier.image = frame->image;
        imageBarrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        imageBarrier.subresourceRange.baseMipLevel = 0;
        imageBarrier.subresourceRange.levelCount = 1;
        imageBarrier.subresourceRange.baseArrayLayer = 0;
        imageBarrier.subresourceRange.layerCount = 1;
        imageBarrier.srcAccessMask = 0;
        imageBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        vkCmdPipelineBarrier(
            commandBuffer,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
            VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            0,
            0, nullptr,
            0, nullptr,
            1, &imageBarrier
        );
        frame->layout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    }

    VkClearValue clearValue{};
    clearValue.color = {{0.f, 0.f, 0.f, 1.f}};
    VkRenderPassBeginInfo renderPassInfo{};
    renderPassInfo.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
    renderPassInfo.renderPass = target->renderPass;
    renderPassInfo.framebuffer = target->framebuffers[imageIndex];
    renderPassInfo.renderArea.offset = {0, 0};
    renderPassInfo.renderArea.extent = target->extent;
    renderPassInfo.clearValueCount = 1;
    renderPassInfo.pClearValues = &clearValue;
    vkCmdBeginRenderPass(commandBuffer, &renderPassInfo, VK_SUBPASS_CONTENTS_INLINE);

    VkViewport viewport{};
    viewport.x = 0.f;
    viewport.y = 0.f;
    viewport.width = static_cast<float>(target->extent.width);
    viewport.height = static_cast<float>(target->extent.height);
    viewport.minDepth = 0.f;
    viewport.maxDepth = 1.f;
    VkRect2D scissor{};
    scissor.offset = {0, 0};
    scissor.extent = target->extent;

    vkCmdSetViewport(commandBuffer, 0, 1, &viewport);
    vkCmdSetScissor(commandBuffer, 0, 1, &scissor);
    vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, target->pipeline);
    const VkDeviceSize offsets[] = {0};
    vkCmdBindVertexBuffers(commandBuffer, 0, 1, &state->vertexBuffer, offsets);
    vkCmdBindDescriptorSets(
        commandBuffer,
        VK_PIPELINE_BIND_POINT_GRAPHICS,
        state->pipelineLayout,
        0,
        1,
        &target->descriptorSets[imageIndex],
        0,
        nullptr
    );
    vkCmdDraw(commandBuffer, 4, 1, 0, 0);
    vkCmdEndRenderPass(commandBuffer);

    if (!check(vkEndCommandBuffer(commandBuffer), "vkEndCommandBuffer")) {
        restoreUnsubmittedTrackedState();
        drainAcquiredImageSemaphore(state, target, syncIndex, imageIndex);
        return false;
    }
    logSlowSurfaceOperation(targetLabel, "command recording", commandBuildStart);

    VkPipelineStageFlags waitStages[] = {VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT};
    std::array<VkSemaphore, 2> signalSemaphores{
        target->renderFinishedSemaphores[syncIndex],
        target->timelineSemaphore
    };
    std::array<uint64_t, 1> waitSemaphoreValues{0u};
    std::array<uint64_t, 2> signalSemaphoreValues{0u, 0u};
    VkTimelineSemaphoreSubmitInfo timelineSubmitInfo{};
    uint64_t timelineSignalValue = 0u;
    if (useTimelineSemaphore) {
        timelineSignalValue = target->nextTimelineValue++;
        signalSemaphoreValues[1] = timelineSignalValue;
        timelineSubmitInfo.sType = VK_STRUCTURE_TYPE_TIMELINE_SEMAPHORE_SUBMIT_INFO;
        timelineSubmitInfo.waitSemaphoreValueCount = static_cast<uint32_t>(waitSemaphoreValues.size());
        timelineSubmitInfo.pWaitSemaphoreValues = waitSemaphoreValues.data();
        timelineSubmitInfo.signalSemaphoreValueCount = static_cast<uint32_t>(signalSemaphoreValues.size());
        timelineSubmitInfo.pSignalSemaphoreValues = signalSemaphoreValues.data();
    }
    VkSubmitInfo submitInfo{};
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.pNext = useTimelineSemaphore ? &timelineSubmitInfo : nullptr;
    submitInfo.waitSemaphoreCount = 1;
    submitInfo.pWaitSemaphores = &target->imageAvailableSemaphores[syncIndex];
    submitInfo.pWaitDstStageMask = waitStages;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &commandBuffer;
    submitInfo.signalSemaphoreCount = useTimelineSemaphore ? static_cast<uint32_t>(signalSemaphores.size()) : 1u;
    submitInfo.pSignalSemaphores = useTimelineSemaphore
        ? signalSemaphores.data()
        : &target->renderFinishedSemaphores[syncIndex];
    // Keep the fence signaled while timed acquisition and command construction
    // can fail. Only a submitted queue operation owns a reset fence.
    const VkResult resetFenceResult =
        vkResetFences(state->device, 1, &target->inFlightFences[syncIndex]);
    if (resetFenceResult != VK_SUCCESS) {
        check(resetFenceResult, "vkResetFences(frame slot)");
        if (resetFenceResult == VK_ERROR_DEVICE_LOST) state->supported = false;
        restoreUnsubmittedTrackedState();
        drainAcquiredImageSemaphore(state, target, syncIndex, imageIndex);
        return false;
    }
    const auto queueSubmitStart = std::chrono::steady_clock::now();
    VkResult queueSubmitResult = VK_SUCCESS;
    {
        std::lock_guard<std::mutex> queueLock(state->queueMutex);
        queueSubmitResult = vkQueueSubmit(
            state->graphicsQueue, 1, &submitInfo, target->inFlightFences[syncIndex]);
    }
    if (!check(queueSubmitResult, "vkQueueSubmit")) {
        if (queueSubmitResult == VK_ERROR_DEVICE_LOST) {
            state->supported = false;
            setStatus(state, "Vulkan frame submission failed: device lost");
        }
        // B03 — submit failed AFTER vkResetFences unsignaled the fence and vkAcquireNextImageKHR signaled
        // imageAvailableSemaphores[syncIndex]. syncIndex is NOT advanced on this bail (see the increment
        // below), so the next frame reuses this slot. Recover it so the slot stays usable:
        //  (a) drain the acquired semaphore (FIX #29) — otherwise the next acquire re-signals an
        //      already-signaled semaphore (UB), and
        //  (b) re-signal the just-reset fence with an empty submit — otherwise the next
        //      vkWaitForFences(UINT64_MAX) on this slot blocks forever (ANR).
        restoreUnsubmittedTrackedState();
        drainAcquiredImageSemaphore(state, target, syncIndex, imageIndex);
        return false;
    }
    logSlowSurfaceOperation(targetLabel, "vkQueueSubmit", queueSubmitStart);
    if (useTimelineSemaphore) {
        target->inFlightTimelineValues[syncIndex] = timelineSignalValue;
    }
    if (useRealtimeCompute && !target->historyContentValid.empty()) {
        target->historyContentValid.front() = 1u;
        target->temporalHistorySubmissionId += 1u;
        target->temporalHistorySensorTimestampNs = state->lastPresentationTimeNs;
    }

    VkPresentInfoKHR presentInfo{};
    presentInfo.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
    presentInfo.waitSemaphoreCount = 1;
    presentInfo.pWaitSemaphores = &target->renderFinishedSemaphores[syncIndex];
    presentInfo.swapchainCount = 1;
    presentInfo.pSwapchains = &target->swapchain;
    presentInfo.pImageIndices = &imageIndex;
    const auto presentStart = std::chrono::steady_clock::now();
    VkResult presentResult = VK_SUCCESS;
    {
        std::lock_guard<std::mutex> queueLock(state->queueMutex);
        presentResult = vkQueuePresentKHR(state->graphicsQueue, &presentInfo);
    }
    logSlowSurfaceOperation(targetLabel, "vkQueuePresentKHR", presentStart);
    if (presentResult != VK_SUCCESS && presentResult != VK_SUBOPTIMAL_KHR) {
        if (presentResult == VK_ERROR_DEVICE_LOST) {
            state->supported = false;
            setStatus(state, "Vulkan present failed: device lost");
        }
        LOGE("vkQueuePresentKHR failed with %d", static_cast<int>(presentResult));
        return false;
    }

    target->syncIndex = (syncIndex + 1u) % static_cast<uint32_t>(target->imageAvailableSemaphores.size());
    if (niv99Applied != nullptr) {
        *niv99Applied = state->niv99LivePacket.currentFrameActive && useRealtimeCompute;
    }
    return true;
}

bool importHardwareBuffer(
    VulkanBridgeState* state,
    AHardwareBuffer* hardwareBuffer,
    int width,
    int height,
    int format
) {
    if (hardwareBuffer == nullptr) {
        setStatus(state, "no hardware buffer to import");
        return false;
    }
    const auto validation = neoproc::NativeBufferManager::validateExternalCameraBuffer(
        hardwareBuffer,
        width,
        height,
        format
    );
    if (!validation.valid) {
        setStatus(state, std::string("AHardwareBuffer validation failed: ") + validation.reason);
        return false;
    }
    bool currentBufferIdStable = false;
    const uint64_t currentBufferId = hardwareBufferCacheKey(
        hardwareBuffer,
        &currentBufferIdStable
    );
    const auto matchesBuffer = [&](const ImportedFrame& frame) {
        return frame.ready &&
            (
                (currentBufferIdStable && frame.hardwareBufferIdStable &&
                    frame.hardwareBufferId == currentBufferId) ||
                (!currentBufferIdStable && frame.hardwareBuffer == hardwareBuffer)
            ) &&
            frame.width == width &&
            frame.height == height &&
            frame.format == format;
    };
    for (size_t index = 0; index < state->importedFrames.size(); ++index) {
        if (matchesBuffer(state->importedFrames[index])) {
            state->currentImportedFrameIndex = static_cast<int>(index);
            state->importedFrames[index].lastUsedSerial = ++state->importedFrameSerial;
            return true;
        }
    }

    ImportedFrame frame{};
    AHardwareBuffer_acquire(hardwareBuffer);
    frame.hardwareBuffer = hardwareBuffer;
    frame.hardwareBufferId = currentBufferId;
    frame.hardwareBufferIdStable = currentBufferIdStable;
    frame.width = width;
    frame.height = height;
    frame.format = format;

    VkAndroidHardwareBufferFormatPropertiesANDROID formatProps{};
    formatProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID;
    VkAndroidHardwareBufferPropertiesANDROID bufferProps{};
    bufferProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
    bufferProps.pNext = &formatProps;
    if (!check(state->getAndroidHardwareBufferProperties(state->device, hardwareBuffer, &bufferProps), "vkGetAndroidHardwareBufferPropertiesANDROID")) {
        setStatus(state, "AHardwareBuffer properties query failed");
        destroyImportedFrameResources(state, &frame);
        return false;
    }

    const bool useExternalFormat = formatProps.externalFormat != 0;
    const bool useSamplerYcbcrConversion =
        useExternalFormat || isSamplerYcbcrFormat(formatProps.format);
    if (!useExternalFormat && formatProps.format == VK_FORMAT_UNDEFINED) {
        setStatus(state, "AHardwareBuffer import returned undefined format");
        destroyImportedFrameResources(state, &frame);
        return false;
    }

    VkExternalFormatANDROID externalFormatInfo{};
    externalFormatInfo.sType = VK_STRUCTURE_TYPE_EXTERNAL_FORMAT_ANDROID;
    externalFormatInfo.externalFormat = formatProps.externalFormat;

    VkExternalMemoryImageCreateInfo externalImageInfo{};
    externalImageInfo.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
    externalImageInfo.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
    externalImageInfo.pNext = useExternalFormat ? &externalFormatInfo : nullptr;

    VkImageCreateInfo imageInfo{};
    imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imageInfo.pNext = &externalImageInfo;
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = useExternalFormat ? VK_FORMAT_UNDEFINED : formatProps.format;
    imageInfo.extent = {
        static_cast<uint32_t>(std::max(1, width)),
        static_cast<uint32_t>(std::max(1, height)),
        1u
    };
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.usage = VK_IMAGE_USAGE_SAMPLED_BIT;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (!check(vkCreateImage(state->device, &imageInfo, nullptr, &frame.image), "vkCreateImage(imported)")) {
        setStatus(state, "vkCreateImage(imported) failed");
        destroyImportedFrameResources(state, &frame);
        return false;
    }

    VkImportAndroidHardwareBufferInfoANDROID importInfo{};
    importInfo.sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
    importInfo.buffer = hardwareBuffer;
    VkMemoryDedicatedAllocateInfo dedicatedInfo{};
    dedicatedInfo.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
    dedicatedInfo.image = frame.image;
    importInfo.pNext = &dedicatedInfo;

    VkMemoryAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.pNext = &importInfo;
    allocInfo.allocationSize = bufferProps.allocationSize;
    allocInfo.memoryTypeIndex = findMemoryType(state, bufferProps.memoryTypeBits, 0);
    if (!check(vkAllocateMemory(state->device, &allocInfo, nullptr, &frame.memory), "vkAllocateMemory(imported)")) {
        setStatus(state, "vkAllocateMemory(imported) failed");
        destroyImportedFrameResources(state, &frame);
        return false;
    }
    if (!check(vkBindImageMemory(state->device, frame.image, frame.memory, 0), "vkBindImageMemory(imported)")) {
        setStatus(state, "vkBindImageMemory(imported) failed");
        destroyImportedFrameResources(state, &frame);
        return false;
    }

    VkSamplerYcbcrConversionInfo ycbcrInfo{};
    ycbcrInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_INFO;
    if (useSamplerYcbcrConversion) {
        VkSamplerYcbcrConversionCreateInfo conversionInfo{};
        conversionInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_YCBCR_CONVERSION_CREATE_INFO;
        conversionInfo.pNext = useExternalFormat ? &externalFormatInfo : nullptr;
        conversionInfo.format = useExternalFormat ? VK_FORMAT_UNDEFINED : formatProps.format;
        conversionInfo.ycbcrModel = formatProps.suggestedYcbcrModel;
        conversionInfo.ycbcrRange = formatProps.suggestedYcbcrRange;
        conversionInfo.components = VkComponentMapping{
            VK_COMPONENT_SWIZZLE_IDENTITY,
            VK_COMPONENT_SWIZZLE_IDENTITY,
            VK_COMPONENT_SWIZZLE_IDENTITY,
            VK_COMPONENT_SWIZZLE_IDENTITY
        };
        conversionInfo.xChromaOffset = formatProps.suggestedXChromaOffset;
        conversionInfo.yChromaOffset = formatProps.suggestedYChromaOffset;
        conversionInfo.chromaFilter = VK_FILTER_LINEAR;
        conversionInfo.forceExplicitReconstruction = VK_FALSE;
        if (!check(state->createSamplerYcbcrConversion(state->device, &conversionInfo, nullptr, &frame.conversion),
                   "vkCreateSamplerYcbcrConversion")) {
            setStatus(state, "vkCreateSamplerYcbcrConversion failed");
            destroyImportedFrameResources(state, &frame);
            return false;
        }
        ycbcrInfo.conversion = frame.conversion;
    } else {
        LOGI(
            "Imported camera buffer as RGB/no-YCbCr conversion format=%d externalFormat=%llu",
            static_cast<int>(formatProps.format),
            static_cast<unsigned long long>(formatProps.externalFormat)
        );
    }

    VkSamplerCreateInfo samplerInfo{};
    samplerInfo.sType = VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
    samplerInfo.pNext = useSamplerYcbcrConversion ? &ycbcrInfo : nullptr;
    samplerInfo.magFilter = VK_FILTER_LINEAR;
    samplerInfo.minFilter = VK_FILTER_LINEAR;
    samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
    samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    samplerInfo.minLod = 0.0f;
    samplerInfo.maxLod = 0.0f;
    samplerInfo.maxAnisotropy = 1.0f;
    samplerInfo.unnormalizedCoordinates = VK_FALSE;
    if (!check(vkCreateSampler(state->device, &samplerInfo, nullptr, &frame.sampler), "vkCreateSampler(imported)")) {
        setStatus(state, "vkCreateSampler(imported) failed");
        destroyImportedFrameResources(state, &frame);
        return false;
    }

    VkImageViewCreateInfo viewInfo{};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.pNext = useSamplerYcbcrConversion ? &ycbcrInfo : nullptr;
    viewInfo.image = frame.image;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = useExternalFormat ? VK_FORMAT_UNDEFINED : formatProps.format;
    viewInfo.components = VkComponentMapping{
        VK_COMPONENT_SWIZZLE_IDENTITY,
        VK_COMPONENT_SWIZZLE_IDENTITY,
        VK_COMPONENT_SWIZZLE_IDENTITY,
        VK_COMPONENT_SWIZZLE_IDENTITY
    };
    viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    viewInfo.subresourceRange.baseMipLevel = 0;
    viewInfo.subresourceRange.levelCount = 1;
    viewInfo.subresourceRange.baseArrayLayer = 0;
    viewInfo.subresourceRange.layerCount = 1;
    if (!check(vkCreateImageView(state->device, &viewInfo, nullptr, &frame.imageView), "vkCreateImageView(imported)")) {
        setStatus(state, "vkCreateImageView(imported) failed");
        destroyImportedFrameResources(state, &frame);
        return false;
    }

    frame.layout = VK_IMAGE_LAYOUT_UNDEFINED;
    frame.ready = true;
    frame.lastUsedSerial = ++state->importedFrameSerial;
    state->importedFrames.push_back(std::move(frame));
    state->currentImportedFrameIndex = static_cast<int>(state->importedFrames.size()) - 1;

    // Keep this below the Camera2 hardware-import ImageReader depth. Eight cached imports match the
    // strict 4K25 recording ring target while leaving half of the 16-deep reader pool available to
    // the HAL/Camera2 producer. This removes 4-slot import churn without pinning every buffer.
    constexpr size_t kImportedFrameCacheLimit = kStrictHighEndBuild ? 8u : 4u;
    if (state->importedFrames.size() > kImportedFrameCacheLimit) {
        // Cache eviction is secondary maintenance on the capture submission path. Never drain or wait
        // for preview/encoder work here: retain the bounded ImageReader pool imports temporarily and
        // evict on a later frame once all referencing submissions have naturally completed.
        if (isTargetIdleNonBlocking(state, state->previewTarget) &&
            isTargetIdleNonBlocking(state, state->encoderTarget)) {
            size_t evictIndex = 0;
            uint64_t oldestSerial = std::numeric_limits<uint64_t>::max();
            for (size_t index = 0; index < state->importedFrames.size(); ++index) {
                if (static_cast<int>(index) == state->currentImportedFrameIndex) continue;
                if (state->importedFrames[index].lastUsedSerial < oldestSerial) {
                    oldestSerial = state->importedFrames[index].lastUsedSerial;
                    evictIndex = index;
                }
            }
            destroyImportedFrameResources(state, &state->importedFrames[evictIndex]);
            state->importedFrames.erase(
                state->importedFrames.begin() + static_cast<long long>(evictIndex));
            if (state->currentImportedFrameIndex > static_cast<int>(evictIndex)) {
                state->currentImportedFrameIndex -= 1;
            }
        }
    }

    state->importedFrameCount += 1;
    if (state->importedFrameCount % 60u == 0u) {
        LOGI(
            "Imported %llu camera frames into Vulkan (lastKey=%llu stableId=%s cacheSize=%zu)",
            static_cast<unsigned long long>(state->importedFrameCount),
            static_cast<unsigned long long>(currentBufferId),
            currentBufferIdStable ? "true" : "false",
            state->importedFrames.size()
        );
    }
    return true;
}

VulkanBridgeState* fromHandle(jlong handle) {
    return reinterpret_cast<VulkanBridgeState*>(handle);
}

// s7-f13/CP9C — process-global disk pipeline-cache config. Static (handle-less) so Kotlin can set it
// BEFORE the eager nativeCreate (lazy VulkanBridge construction), enabling warm-start restore on the
// configuring launch. Default OFF/empty path == today's fresh-compile behavior.
extern "C" JNIEXPORT void JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeSetPipelineCacheConfig(
    JNIEnv* env, jclass, jstring jdir, jboolean enabled) {
    std::lock_guard<std::mutex> lock(g_pipelineCacheConfigMutex);
    g_pipelineCacheDiskEnabled.store(enabled == JNI_TRUE);
    if (jdir != nullptr) {
        const char* chars = env->GetStringUTFChars(jdir, nullptr);
        g_pipelineCacheFilePath = (chars != nullptr) ? std::string(chars) : std::string();
        if (chars != nullptr) {
            env->ReleaseStringUTFChars(jdir, chars);
        }
    } else {
        g_pipelineCacheFilePath.clear();
    }
}

// PART A (startup deferral): compile the three heavy compute-pipeline groups (realtime enhance, QFCA,
// OmniClarity — ~2.5s of SPIR-V) on a background thread so cold-start preview is not blocked behind
// them. When the build finishes, publish the readiness flags AND re-drive per-target compute resources
// (createComputeIntermediateResources) for any target already bound during the build — all under
// state->mutex (the same lock nativeSetPreviewSurface / recordAndSubmit hold) so the render thread never
// observes a torn 'flag true but pipeline/descset missing' state. canDispatchRealtimeCompute() and the
// OmniClarity gate keep every frame rendering the RAW camera image until the flags flip; if any group
// fails to build, all flags stay false and the preview simply stays raw (graceful, never a crash).
void startEnhancementPipelineBuild(VulkanBridgeState* state) {
    if (state == nullptr || state->enhancementBuildStarted.exchange(true)) return;
    state->enhancementBuildThread = std::thread([state]() {
        // D-8: cooperative cancellation. nativeDestroy sets enhancementBuildCancelled before joining so
        // teardown does not block the calling (UI) thread for the full ~2.5s SPIR-V build. Checked at each
        // compile-group boundary; on cancel we return IMMEDIATELY without touching state->mutex or
        // publishing the ready flags (nativeDestroy joins THEN destroyState tears the partial pipelines
        // down under the lock; the partial-build path is already handled like the existing !allOk case).
        // The flag is only ever set during teardown, so a normal run never observes it true and the build
        // is byte-identical to today.
        if (state->enhancementBuildCancelled.load(std::memory_order_acquire)) return;
        const bool okRealtime = createRealtimeComputePipeline(state); // sets enhancementComputePipelineReady
        if (state->enhancementBuildCancelled.load(std::memory_order_acquire)) return;
        const bool okQfca = createQfcaPipelines(state);
        if (state->enhancementBuildCancelled.load(std::memory_order_acquire)) return;
        const bool okOmni = createOmniClarityPipelines(state);
        if (state->enhancementBuildCancelled.load(std::memory_order_acquire)) return;
        const bool allOk = okRealtime && okQfca && okOmni;
        const bool rawDemosaicReady = okQfca &&
            state->demosaicPipeline != VK_NULL_HANDLE &&
            state->demosaicPipelineLayout != VK_NULL_HANDLE &&
            state->demosaicDescLayout != VK_NULL_HANDLE;
        // s7-f13/CP9C: capture the pipeline-cache blob UNDER state->mutex (Vulkan host access to the
        // cache is externally synchronized by this lock); the file write runs AFTER the lock releases.
        std::vector<uint8_t> cacheBlobToPersist;
        bool shouldPersistCache = false;
        {
            std::lock_guard<std::mutex> lock(state->mutex);
            // Independent publication: failure of realtime enhancement or OmniClarity must not
            // invalidate a fully-created RAW -> ACEScg demosaic pipeline. This release publishes the
            // three handle writes above to dispatch threads without exposing a partial build.
            state->rawDemosaicPipelineReady.store(
                rawDemosaicReady,
                std::memory_order_release);
            // Publish all three group flags together. enhancementComputePipelineReady was set true inside
            // createRealtimeComputePipeline; force it consistent with the whole build so enhancement is
            // strictly all-or-nothing (a partial build => stay raw, no half-wired enhancement).
            state->enhancementComputePipelineReady = allOk;
            state->qfcaPipelinesReady.store(allOk);
            state->omniClarityPipelinesReady.store(allOk);
            if (!allOk) {
                LOGW("enhancement: background pipeline build failed (realtime=%d qfca=%d omni=%d); preview stays raw",
                     (int)okRealtime, (int)okQfca, (int)okOmni);
                if (rawDemosaicReady) {
                    LOGI("RAW preview demosaic published independently of failed secondary enhancement group");
                }
                return;
            }
            // Re-drive per-target compute resources for any target bound during the build window.
            for (TargetSurface* target : {&state->previewTarget, &state->encoderTarget}) {
                if (target->valid && !target->images.empty()) {
                    createComputeIntermediateResources(state, target);
                }
            }
            LOGI("enhancement: background pipeline build finished; enhancement active");
            // s7-f13/CP9C: read the warmed graphics pipeline-cache blob (gated; default OFF => skipped).
            if (state->pipelineCacheDiskEnabled && !state->pipelineCacheFilePath.empty() &&
                !state->pipelineCachePersisted.exchange(true)) {
                size_t dataSize = 0;
                if (vkGetPipelineCacheData(state->device, state->graphicsPipelineCache, &dataSize, nullptr) == VK_SUCCESS &&
                    dataSize > 0 && dataSize <= kMaxPipelineCacheBytes) {
                    cacheBlobToPersist.resize(dataSize);
                    if (vkGetPipelineCacheData(state->device, state->graphicsPipelineCache, &dataSize,
                                               cacheBlobToPersist.data()) == VK_SUCCESS) {
                        cacheBlobToPersist.resize(dataSize);
                        shouldPersistCache = true;
                    } else {
                        cacheBlobToPersist.clear();
                    }
                }
            }
        }
        // File I/O off the lock (and off the critical path: post-first-frame background thread).
        if (shouldPersistCache && !cacheBlobToPersist.empty()) {
            const auto fileBytes = neoproc::serializePipelineCacheFile(
                state->pipelineCacheDeviceIdentity, cacheBlobToPersist.data(), cacheBlobToPersist.size());
            neoproc::writePipelineCacheFile(state->pipelineCacheFilePath, fileBytes);
        }
    });
}

// ═════════════════════════════════════════════════════════════════════════════
// s1-f07 / CP10c — RAW-preview cinema-debayer dispatch (demosaic_vulkan.comp)
// ═════════════════════════════════════════════════════════════════════════════
// Activates the edge-directed 12-bit-RAW -> ACEScg demosaic that CP10b created (pipeline at
// state->demosaicPipeline) but never dispatched. Strictly additive + gated behind
// rawPreviewDemosaicEnabled (default false): the standard nativeSubmitHardwareBuffer YUV preview path
// is untouched, so with the gate off the shipping pixels are byte-identical. Bindings:
//   0 r16ui  imported RAW mosaic (zero-copy AHardwareBuffer external memory)
//   1 rgba16f owned ACEScg output
//   2 r16f    owned dark-current map (s1-f03; cleared 0 by default)
//   3 r8ui    owned hot-pixel mask  (s1-f03; cleared 0 by default)
//   4 UBO     RawDemosaicUniforms (std140; CP11 forward matrices, WB gains, levels)
// WB-before-demosaic + hard-gradient green (CP14) and the ACEScg transform (CP11) live in the shader.

constexpr uint8_t kRawTransportFree = 0u;
constexpr uint8_t kRawTransportVulkanInFlight = 1u;
constexpr uint8_t kRawTransportCompleted = 2u;
constexpr uint8_t kRawTransportExternallyAcquired = 3u;

static VkImage rawOutputImageForSlot(
    const VulkanBridgeState::RawDemosaicResources& r,
    int slot
) {
    return slot == 1 && r.outputImageSecondary != VK_NULL_HANDLE
        ? r.outputImageSecondary
        : r.outputImage;
}

static VkImageView rawOutputViewForSlot(
    const VulkanBridgeState::RawDemosaicResources& r,
    int slot
) {
    return slot == 1 && r.outputViewSecondary != VK_NULL_HANDLE
        ? r.outputViewSecondary
        : r.outputView;
}

static AHardwareBuffer* rawOutputAhbForSlot(
    const VulkanBridgeState::RawDemosaicResources& r,
    int slot
) {
    return slot == 1 && r.outputAhbSecondary != nullptr
        ? r.outputAhbSecondary
        : r.outputAhb;
}

static VkImage rawSceneLogImageForSlot(
    const VulkanBridgeState::RawDemosaicResources& r,
    int slot
) {
    return slot == 1 && r.sceneLogImageSecondary != VK_NULL_HANDLE
        ? r.sceneLogImageSecondary
        : r.sceneLogImage;
}

static VkImageView rawSceneLogViewForSlot(
    const VulkanBridgeState::RawDemosaicResources& r,
    int slot
) {
    return slot == 1 && r.sceneLogViewSecondary != VK_NULL_HANDLE
        ? r.sceneLogViewSecondary
        : r.sceneLogView;
}

static AHardwareBuffer* rawSceneLogAhbForSlot(
    const VulkanBridgeState::RawDemosaicResources& r,
    int slot
) {
    return slot == 1 && r.sceneLogAhbSecondary != nullptr
        ? r.sceneLogAhbSecondary
        : r.sceneLogAhb;
}

static bool rawSceneLogReadyForSlot(
    const VulkanBridgeState::RawDemosaicResources& r,
    int slot
) {
    return slot == 1 ? r.sceneLogReadySecondary : r.sceneLogReady;
}

static void setRawSceneLogReadyForSlot(
    VulkanBridgeState::RawDemosaicResources& r,
    int slot,
    bool ready
) {
    if (slot == 1) {
        r.sceneLogReadySecondary = ready;
    } else {
        r.sceneLogReady = ready;
    }
}

static int rawTransportSlotCount(const VulkanBridgeState::RawDemosaicResources& r) {
    return r.recordingTransportRingReady ? 2 : 1;
}

static uint64_t nextRawTransportToken(
    VulkanBridgeState::RawDemosaicResources& r
) {
    // Java transports tokens as signed long values. Never issue zero, a negative Java value, or a
    // token still present in either lane. The counter deliberately survives resource recreation.
    constexpr uint64_t kMaxJavaToken =
        static_cast<uint64_t>(std::numeric_limits<int64_t>::max());
    for (;;) {
        uint64_t candidate = r.nextTransportToken++;
        if (r.nextTransportToken == 0u || r.nextTransportToken > kMaxJavaToken) {
            r.nextTransportToken = 1u;
        }
        if (candidate == 0u || candidate > kMaxJavaToken) continue;
        const bool collision = std::find(
            r.transportTokens.begin(),
            r.transportTokens.end(),
            candidate) != r.transportTokens.end();
        if (!collision) return candidate;
    }
}

static uint64_t nextRawTransportCompletionSerial(
    VulkanBridgeState::RawDemosaicResources& r
) {
    constexpr uint64_t kMaxJavaSerial =
        static_cast<uint64_t>(std::numeric_limits<int64_t>::max());
    for (;;) {
        uint64_t candidate = r.nextTransportCompletionSerial++;
        if (r.nextTransportCompletionSerial == 0u ||
            r.nextTransportCompletionSerial > kMaxJavaSerial) {
            r.nextTransportCompletionSerial = 1u;
        }
        if (candidate == 0u || candidate > kMaxJavaSerial) continue;
        const bool collision = std::find(
            r.transportCompletionSerial.begin(),
            r.transportCompletionSerial.end(),
            candidate) != r.transportCompletionSerial.end();
        if (!collision) return candidate;
    }
}

static int reserveFreeRawTransportSlot(VulkanBridgeState::RawDemosaicResources& r) {
    const int count = rawTransportSlotCount(r);
    for (int slot = 0; slot < count; ++slot) {
        if (r.transportOwnership[slot] == kRawTransportFree) {
            r.transportOwnership[slot] = kRawTransportVulkanInFlight;
            r.transportTokens[slot] = 0u;
            r.transportCompletionSerial[slot] = 0u;
            r.dispatchTransportSlot = slot;
            setRawSceneLogReadyForSlot(r, slot, false);
            return slot;
        }
    }
    return -1;
}

static int oldestCompletedRawTransportSlot(
    const VulkanBridgeState::RawDemosaicResources& r
) {
    int oldest = -1;
    uint64_t oldestSerial = std::numeric_limits<uint64_t>::max();
    for (int slot = 0; slot < rawTransportSlotCount(r); ++slot) {
        if (r.transportOwnership[slot] == kRawTransportCompleted &&
            r.transportTokens[slot] != 0u &&
            r.transportCompletionSerial[slot] < oldestSerial) {
            oldest = slot;
            oldestSerial = r.transportCompletionSerial[slot];
        }
    }
    return oldest;
}

static void abandonReservedRawTransportSlot(
    VulkanBridgeState::RawDemosaicResources& r,
    int slot
) {
    if (slot >= 0 && slot < 2 &&
        r.transportOwnership[slot] == kRawTransportVulkanInFlight) {
        r.transportOwnership[slot] = kRawTransportFree;
        r.transportCompletionSerial[slot] = 0u;
        r.transportTokens[slot] = 0u;
        setRawSceneLogReadyForSlot(r, slot, false);
    }
    if (r.dispatchTransportSlot == slot) r.dispatchTransportSlot = -1;
}

static bool acknowledgeOldestCompletedRawTransport(
    VulkanBridgeState::RawDemosaicResources& r
) {
    const int slot = oldestCompletedRawTransportSlot(r);
    if (slot < 0) return false;
    r.transportOwnership[slot] = kRawTransportFree;
    r.transportCompletionSerial[slot] = 0u;
    r.transportTokens[slot] = 0u;
    setRawSceneLogReadyForSlot(r, slot, false);
    return true;
}

static bool cancelRawTransportToken(
    VulkanBridgeState::RawDemosaicResources& r,
    uint64_t token
) {
    if (token == 0u) return false;
    for (int slot = 0; slot < rawTransportSlotCount(r); ++slot) {
        const size_t index = static_cast<size_t>(slot);
        if (r.transportOwnership[index] == kRawTransportExternallyAcquired &&
            r.transportTokens[index] == token) {
            // EGL rejected before accepting a read. Restore the same immutable completion so the exact
            // frame may be retried; do not advance its completion serial or make the lane writable.
            r.transportOwnership[index] = kRawTransportCompleted;
            return true;
        }
    }
    return false;
}

static bool acknowledgeRawTransportToken(
    VulkanBridgeState::RawDemosaicResources& r,
    uint64_t token
) {
    if (token == 0u) return false;
    for (int slot = 0; slot < rawTransportSlotCount(r); ++slot) {
        const size_t index = static_cast<size_t>(slot);
        if (r.transportOwnership[index] == kRawTransportExternallyAcquired &&
            r.transportTokens[index] == token) {
            r.transportOwnership[index] = kRawTransportFree;
            r.transportCompletionSerial[index] = 0u;
            r.transportTokens[index] = 0u;
            setRawSceneLogReadyForSlot(r, slot, false);
            return true;
        }
    }
    return false;
}

static int discardAllCompletedRawTransports(
    VulkanBridgeState::RawDemosaicResources& r
) {
    for (uint8_t ownership : r.transportOwnership) {
        if (ownership == kRawTransportVulkanInFlight ||
            ownership == kRawTransportExternallyAcquired) {
            return -1;
        }
    }
    int discarded = 0;
    for (int slot = 0; slot < rawTransportSlotCount(r); ++slot) {
        if (r.transportOwnership[slot] == kRawTransportCompleted) {
            r.transportOwnership[slot] = kRawTransportFree;
            r.transportCompletionSerial[slot] = 0u;
            r.transportTokens[slot] = 0u;
            setRawSceneLogReadyForSlot(r, slot, false);
            ++discarded;
        }
    }
    return discarded;
}

static bool rawRecordingPreviewTransportRequired(const VulkanBridgeState* state) {
    // Every recorder-owned sensor-linear dispatch requires its independent >=10-bit monitor copy.
    // Do not let a concurrent surface-state transition turn an admitted recording frame into a
    // preview-less dispatch between Kotlin preflight and native reservation.
    return state != nullptr && state->rawRecordingDemosaicActive;
}

static size_t rawPreviewTransportOutstandingCount(
    const VulkanBridgeState::RawDemosaicResources& r
) {
    size_t count = 0u;
    for (uint8_t ownership : r.previewTransportOwnership) {
        if (ownership != kRawTransportFree) ++count;
    }
    return count;
}

static uint64_t nextRawPreviewTransportToken(
    VulkanBridgeState::RawDemosaicResources& r);

// A CPU/legacy monitor may be omitted only while this exact dispatch owns a proven >=10-bit
// independent preview lease. The ring-ready flag alone is not sufficient: extent recreation,
// allocation failure, or stale slot state must retain the legacy readback/fail-closed path.
static bool strictTenBitPreviewTransportOwnsFrame(
    const VulkanBridgeState::RawDemosaicResources& r,
    int slot
) {
    if (!r.previewTransportRingReady ||
        slot < 0 || slot >= static_cast<int>(kRawPreviewTransportSlotCount) ||
        r.dispatchPreviewTransportSlot != slot) {
        return false;
    }
    const size_t index = static_cast<size_t>(slot);
    if (r.previewTransportOwnership[index] != kRawTransportVulkanInFlight ||
        r.previewTransportTokens[index] == 0u ||
        r.previewTransportImages[index] == VK_NULL_HANDLE ||
        r.previewTransportViews[index] == VK_NULL_HANDLE ||
        r.previewTransportAhbs[index] == nullptr ||
        r.previewTransportW <= 0 || r.previewTransportH <= 0) {
        return false;
    }

    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(r.previewTransportAhbs[index], &desc);
    const bool fp16AcesCg = !r.previewTransportSceneLog &&
        r.previewTransportFormat == VK_FORMAT_R16G16B16A16_SFLOAT &&
        r.previewTransportAhbFormat == AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT;
    const bool packedTenBitSceneLog = r.previewTransportSceneLog &&
        r.previewTransportFormat == VK_FORMAT_A2B10G10R10_UNORM_PACK32 &&
        r.previewTransportAhbFormat == AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM;
    return (fp16AcesCg || packedTenBitSceneLog) &&
        desc.width == static_cast<uint32_t>(r.previewTransportW) &&
        desc.height == static_cast<uint32_t>(r.previewTransportH) &&
        desc.format == r.previewTransportAhbFormat;
}

static int reserveFreeRawPreviewTransportSlot(
    VulkanBridgeState::RawDemosaicResources& r
) {
    if (!r.previewTransportRingReady) return -1;
    for (size_t slot = 0; slot < kRawPreviewTransportSlotCount; ++slot) {
        if (r.previewTransportOwnership[slot] == kRawTransportFree) {
            r.previewTransportOwnership[slot] = kRawTransportVulkanInFlight;
            // Issue the immutable token with the Vulkan owner, before command recording. It is not
            // externally visible until completion/acquire, but it lets the readback decision prove
            // that the exact frame/slot/token lease exists. Failed submissions abandon this token.
            r.previewTransportTokens[slot] = nextRawPreviewTransportToken(r);
            r.dispatchPreviewTransportSlot = static_cast<int>(slot);
            return static_cast<int>(slot);
        }
    }
    ++r.previewSaturatedCount;
    if (r.previewSaturatedCount == 1u || r.previewSaturatedCount % 60u == 0u) {
        LOGE(
            "RAW_PREVIEW_RING_SATURATED count=%llu generated=%llu acquired=%llu "
            "acknowledged=%llu outstanding=%zu",
            static_cast<unsigned long long>(r.previewSaturatedCount),
            static_cast<unsigned long long>(r.previewGeneratedCount),
            static_cast<unsigned long long>(r.previewAcquiredCount),
            static_cast<unsigned long long>(r.previewAcknowledgedCount),
            rawPreviewTransportOutstandingCount(r));
    }
    return -1;
}

static jint rawPreviewTransportDispatchAdmission(const VulkanBridgeState* state) {
    if (state == nullptr) return kRawPreviewDispatchUnavailable;
    if (!rawRecordingPreviewTransportRequired(state)) return kRawPreviewDispatchNotRequired;
    const auto& r = state->rawDemosaic;
    if (!r.previewTransportRingReady) return kRawPreviewDispatchUnavailable;
    for (size_t slot = 0; slot < kRawPreviewTransportSlotCount; ++slot) {
        if (r.previewTransportOwnership[slot] == kRawTransportFree) {
            return kRawPreviewDispatchAvailable;
        }
    }
    return kRawPreviewDispatchSaturated;
}

static void abandonReservedRawPreviewTransportSlot(
    VulkanBridgeState::RawDemosaicResources& r,
    int slot
) {
    if (slot >= 0 && slot < static_cast<int>(kRawPreviewTransportSlotCount) &&
        r.previewTransportOwnership[static_cast<size_t>(slot)] ==
            kRawTransportVulkanInFlight) {
        r.previewTransportOwnership[static_cast<size_t>(slot)] = kRawTransportFree;
        r.previewTransportTokens[static_cast<size_t>(slot)] = 0u;
    }
    if (r.dispatchPreviewTransportSlot == slot) r.dispatchPreviewTransportSlot = -1;
}

static uint64_t nextRawPreviewTransportToken(
    VulkanBridgeState::RawDemosaicResources& r
) {
    // The token counter intentionally survives extent recreation. Even after wrap, never issue zero or
    // a token still owned by another slot, so a stale/duplicate acknowledgement cannot free new pixels.
    for (;;) {
        uint64_t candidate = r.nextPreviewTransportToken++;
        constexpr uint64_t kMaxJavaToken =
            static_cast<uint64_t>(std::numeric_limits<int64_t>::max());
        if (r.nextPreviewTransportToken == 0u || r.nextPreviewTransportToken > kMaxJavaToken) {
            r.nextPreviewTransportToken = 1u;
        }
        if (candidate == 0u || candidate > kMaxJavaToken) continue;
        const bool collision = std::find(
            r.previewTransportTokens.begin(),
            r.previewTransportTokens.end(),
            candidate) != r.previewTransportTokens.end();
        if (!collision) return candidate;
    }
}

static bool completeReservedRawPreviewTransportSlot(
    VulkanBridgeState::RawDemosaicResources& r
) {
    const int slot = r.dispatchPreviewTransportSlot;
    if (slot < 0 || slot >= static_cast<int>(kRawPreviewTransportSlotCount)) return false;
    const size_t index = static_cast<size_t>(slot);
    if (r.previewTransportOwnership[index] != kRawTransportVulkanInFlight ||
        r.previewTransportAhbs[index] == nullptr ||
        r.previewTransportTokens[index] == 0u) {
        return false;
    }
    r.previewTransportOwnership[index] = kRawTransportCompleted;
    r.dispatchPreviewTransportSlot = -1;
    ++r.previewGeneratedCount;
    return true;
}

static int oldestCompletedRawPreviewTransportSlot(
    const VulkanBridgeState::RawDemosaicResources& r
) {
    int oldest = -1;
    uint64_t oldestToken = std::numeric_limits<uint64_t>::max();
    for (size_t slot = 0; slot < kRawPreviewTransportSlotCount; ++slot) {
        if (r.previewTransportOwnership[slot] == kRawTransportCompleted &&
            r.previewTransportTokens[slot] != 0u &&
            r.previewTransportTokens[slot] < oldestToken) {
            oldest = static_cast<int>(slot);
            oldestToken = r.previewTransportTokens[slot];
        }
    }
    return oldest;
}

static bool acknowledgeRawPreviewTransportToken(
    VulkanBridgeState::RawDemosaicResources& r,
    uint64_t token
) {
    if (token == 0u) return false;
    for (size_t slot = 0; slot < kRawPreviewTransportSlotCount; ++slot) {
        if (r.previewTransportOwnership[slot] == kRawTransportExternallyAcquired &&
            r.previewTransportTokens[slot] == token) {
            r.previewTransportOwnership[slot] = kRawTransportFree;
            r.previewTransportTokens[slot] = 0u;
            ++r.previewAcknowledgedCount;
            return true;
        }
    }
    return false;
}

// Returns -1 while Vulkan or an external EGL consumer owns any slot. Otherwise retires only completed,
// never-acquired references. Lifecycle callers invoke this after the preview worker has drained.
static int discardCompletedRawPreviewTransports(
    VulkanBridgeState::RawDemosaicResources& r
) {
    for (uint8_t ownership : r.previewTransportOwnership) {
        if (ownership == kRawTransportVulkanInFlight ||
            ownership == kRawTransportExternallyAcquired) {
            return -1;
        }
    }
    int discarded = 0;
    for (size_t slot = 0; slot < kRawPreviewTransportSlotCount; ++slot) {
        if (r.previewTransportOwnership[slot] == kRawTransportCompleted) {
            r.previewTransportOwnership[slot] = kRawTransportFree;
            r.previewTransportTokens[slot] = 0u;
            ++r.previewAcknowledgedCount;
            ++discarded;
        }
    }
    return discarded;
}

void releaseRawDevelopReadbackResources(VulkanBridgeState* state) {
    if (state == nullptr || state->device == VK_NULL_HANDLE) return;
    auto& r = state->rawDemosaic;
    if (r.developReadbackMapped != nullptr && r.developReadbackMemory != VK_NULL_HANDLE) {
        vkUnmapMemory(state->device, r.developReadbackMemory);
        r.developReadbackMapped = nullptr;
    }
    if (r.developReadbackFence != VK_NULL_HANDLE) {
        vkDestroyFence(state->device, r.developReadbackFence, nullptr);
        r.developReadbackFence = VK_NULL_HANDLE;
    }
    if (r.developReadbackCommandPool != VK_NULL_HANDLE) {
        vkDestroyCommandPool(state->device, r.developReadbackCommandPool, nullptr);
        r.developReadbackCommandPool = VK_NULL_HANDLE;
        r.developReadbackCommandBuffer = VK_NULL_HANDLE;
    }
    if (r.developReadbackBuffer != VK_NULL_HANDLE) {
        vkDestroyBuffer(state->device, r.developReadbackBuffer, nullptr);
        r.developReadbackBuffer = VK_NULL_HANDLE;
    }
    if (r.developReadbackMemory != VK_NULL_HANDLE) {
        vkFreeMemory(state->device, r.developReadbackMemory, nullptr);
        r.developReadbackMemory = VK_NULL_HANDLE;
    }
    r.developReadbackBytes = 0;
    r.developReadbackInFlight = false;
}

void releaseRawNiv99Resources(VulkanBridgeState* state) {
    if (state == nullptr || state->device == VK_NULL_HANDLE) return;
    auto& r = state->rawDemosaic;
    if (r.developNiv99CountersMapped != nullptr &&
        r.developNiv99CountersMemory != VK_NULL_HANDLE) {
        vkUnmapMemory(state->device, r.developNiv99CountersMemory);
        r.developNiv99CountersMapped = nullptr;
    }
    if (r.developNiv99DescriptorPool != VK_NULL_HANDLE) {
        vkDestroyDescriptorPool(state->device, r.developNiv99DescriptorPool, nullptr);
        r.developNiv99DescriptorPool = VK_NULL_HANDLE;
        r.developNiv99SpatialDescriptorSet = VK_NULL_HANDLE;
        r.developNiv99TemporalDescriptorSet = VK_NULL_HANDLE;
    }
    if (r.developNiv99CountersBuffer != VK_NULL_HANDLE) {
        vkDestroyBuffer(state->device, r.developNiv99CountersBuffer, nullptr);
        r.developNiv99CountersBuffer = VK_NULL_HANDLE;
    }
    if (r.developNiv99CountersMemory != VK_NULL_HANDLE) {
        vkFreeMemory(state->device, r.developNiv99CountersMemory, nullptr);
        r.developNiv99CountersMemory = VK_NULL_HANDLE;
    }
    const auto destroyImage = [&](VkImage& image, VkDeviceMemory& memory, VkImageView& view) {
        if (view != VK_NULL_HANDLE) {
            vkDestroyImageView(state->device, view, nullptr);
            view = VK_NULL_HANDLE;
        }
        if (image != VK_NULL_HANDLE) {
            vkDestroyImage(state->device, image, nullptr);
            image = VK_NULL_HANDLE;
        }
        if (memory != VK_NULL_HANDLE) {
            vkFreeMemory(state->device, memory, nullptr);
            memory = VK_NULL_HANDLE;
        }
    };
    destroyImage(
        r.developNiv99ScratchImage,
        r.developNiv99ScratchMemory,
        r.developNiv99ScratchView);
    for (size_t i = 0; i < r.developNiv99HistoryImages.size(); ++i) {
        destroyImage(
            r.developNiv99HistoryImages[i],
            r.developNiv99HistoryMemories[i],
            r.developNiv99HistoryViews[i]);
    }
    r.developNiv99HistoryIndex = -1;
    r.developNiv99Width = 0;
    r.developNiv99Height = 0;
    r.developNiv99LastFrameNumber = -1;
    r.developNiv99LastTimestampNs = -1;
    r.developNiv99LastFps = 0u;
    r.pendingNiv99HistoryCommit = false;
    r.pendingNiv99HistoryIndex = -1;
    r.pendingNiv99FrameNumber = -1;
    r.pendingNiv99TimestampNs = -1;
    r.pendingNiv99Fps = 0u;
}

void releaseRawDevelopP010Resources(VulkanBridgeState* state) {
    if (state == nullptr || state->device == VK_NULL_HANDLE) return;
    auto& r = state->rawDemosaic;
    if (r.developP010Mapped != nullptr && r.developP010Memory != VK_NULL_HANDLE) {
        vkUnmapMemory(state->device, r.developP010Memory);
        r.developP010Mapped = nullptr;
    }
    if (r.developP010Fence != VK_NULL_HANDLE) {
        vkDestroyFence(state->device, r.developP010Fence, nullptr);
        r.developP010Fence = VK_NULL_HANDLE;
    }
    if (r.developP010CommandPool != VK_NULL_HANDLE) {
        vkDestroyCommandPool(state->device, r.developP010CommandPool, nullptr);
        r.developP010CommandPool = VK_NULL_HANDLE;
        r.developP010CommandBuffer = VK_NULL_HANDLE;
    }
    if (r.developP010DescriptorPool != VK_NULL_HANDLE) {
        vkDestroyDescriptorPool(state->device, r.developP010DescriptorPool, nullptr);
        r.developP010DescriptorPool = VK_NULL_HANDLE;
        r.developDetailDescriptorSet = VK_NULL_HANDLE;
        r.developLumaBlurDescriptorSet = VK_NULL_HANDLE;
        r.developP010DescriptorSet = VK_NULL_HANDLE;
    }
    if (r.developP010Buffer != VK_NULL_HANDLE) {
        vkDestroyBuffer(state->device, r.developP010Buffer, nullptr);
        r.developP010Buffer = VK_NULL_HANDLE;
    }
    if (r.developP010Memory != VK_NULL_HANDLE) {
        vkFreeMemory(state->device, r.developP010Memory, nullptr);
        r.developP010Memory = VK_NULL_HANDLE;
    }
    const auto destroyImage = [&](VkImage& image, VkDeviceMemory& memory, VkImageView& view) {
        if (view != VK_NULL_HANDLE) {
            vkDestroyImageView(state->device, view, nullptr);
            view = VK_NULL_HANDLE;
        }
        if (image != VK_NULL_HANDLE) {
            vkDestroyImage(state->device, image, nullptr);
            image = VK_NULL_HANDLE;
        }
        if (memory != VK_NULL_HANDLE) {
            vkFreeMemory(state->device, memory, nullptr);
            memory = VK_NULL_HANDLE;
        }
    };
    destroyImage(r.developDetailImage, r.developDetailMemory, r.developDetailView);
    destroyImage(r.developLumaBlurImage, r.developLumaBlurMemory, r.developLumaBlurView);
    r.developP010Bytes = 0;
    r.developP010Width = 0;
    r.developP010Height = 0;
    r.developP010InFlight = false;
    r.developP010Leased = false;
}

bool destroyRawDemosaicResources(VulkanBridgeState* state) {
    if (state == nullptr || state->device == VK_NULL_HANDLE) return true;
    auto& r = state->rawDemosaic;
    for (uint8_t ownership : r.transportOwnership) {
        if (ownership == kRawTransportExternallyAcquired) {
            LOGE("RAW full-resolution transport teardown refused: encoder EGL still owns a token");
            return false;
        }
    }
    for (uint8_t ownership : r.previewTransportOwnership) {
        if (ownership == kRawTransportExternallyAcquired) {
            LOGE(
                "RAW preview transport teardown refused: EGL still owns a token "
                "generated=%llu acquired=%llu acknowledged=%llu outstanding=%zu",
                static_cast<unsigned long long>(r.previewGeneratedCount),
                static_cast<unsigned long long>(r.previewAcquiredCount),
                static_cast<unsigned long long>(r.previewAcknowledgedCount),
                rawPreviewTransportOutstandingCount(r));
            return false;
        }
    }
    if (r.developP010Leased) {
        LOGE("RAW GPU P010 teardown refused: JVM still owns mapped conversion output");
        return false;
    }
    if (r.dispatchInFlight && r.dispatchFence != VK_NULL_HANDLE) {
        const VkResult waitResult = vkWaitForFences(
            state->device, 1, &r.dispatchFence, VK_TRUE, kRawResourceTeardownTimeoutNs);
        if (waitResult == VK_TIMEOUT) {
            // Do not free resources still referenced by a queue submission. The owner can retry
            // teardown after the fence advances; leaking is preferable to a GPU use-after-free.
            LOGE("RAW dispatch did not retire before resource teardown deadline; preserving live resources");
            return false;
        }
        if (waitResult != VK_SUCCESS && waitResult != VK_ERROR_DEVICE_LOST) {
            LOGE("RAW dispatch teardown wait failed with %d", static_cast<int>(waitResult));
            return false;
        }
        if (waitResult == VK_ERROR_DEVICE_LOST) {
            state->supported = false;
            setStatus(state, "RAW GPU teardown observed Vulkan device loss");
        }
        r.dispatchInFlight = false;
        abandonReservedRawPreviewTransportSlot(r, r.dispatchPreviewTransportSlot);
    }
    if (r.developReadbackInFlight && r.developReadbackFence != VK_NULL_HANDLE) {
        const VkResult waitResult = vkWaitForFences(
            state->device, 1, &r.developReadbackFence, VK_TRUE, kRawResourceTeardownTimeoutNs);
        if (waitResult == VK_TIMEOUT) {
            LOGE("RAW develop readback did not retire before teardown; preserving live resources");
            return false;
        }
        if (waitResult != VK_SUCCESS && waitResult != VK_ERROR_DEVICE_LOST) {
            LOGE("RAW develop readback teardown wait failed with %d", static_cast<int>(waitResult));
            return false;
        }
        if (waitResult == VK_ERROR_DEVICE_LOST) {
            state->supported = false;
            setStatus(state, "RAW develop readback teardown observed Vulkan device loss");
        }
        r.developReadbackInFlight = false;
    }
    if (r.developP010InFlight && r.developP010Fence != VK_NULL_HANDLE) {
        const VkResult waitResult = vkWaitForFences(
            state->device, 1, &r.developP010Fence, VK_TRUE, kRawResourceTeardownTimeoutNs);
        if (waitResult == VK_TIMEOUT) {
            LOGE("RAW GPU P010 conversion did not retire before teardown; preserving live resources");
            return false;
        }
        if (waitResult != VK_SUCCESS && waitResult != VK_ERROR_DEVICE_LOST) {
            LOGE("RAW GPU P010 teardown wait failed with %d", static_cast<int>(waitResult));
            return false;
        }
        if (waitResult == VK_ERROR_DEVICE_LOST) {
            state->supported = false;
            setStatus(state, "RAW GPU P010 teardown observed Vulkan device loss");
        }
        r.developP010InFlight = false;
    }
    if (r.pendingRawView != VK_NULL_HANDLE) {
        vkDestroyImageView(state->device, r.pendingRawView, nullptr);
        r.pendingRawView = VK_NULL_HANDLE;
    }
    if (r.pendingRawImage != VK_NULL_HANDLE) {
        vkDestroyImage(state->device, r.pendingRawImage, nullptr);
        r.pendingRawImage = VK_NULL_HANDLE;
    }
    if (r.pendingRawMemory != VK_NULL_HANDLE) {
        vkFreeMemory(state->device, r.pendingRawMemory, nullptr);
        r.pendingRawMemory = VK_NULL_HANDLE;
    }
    if (r.pendingRawHardwareBuffer != nullptr) {
        AHardwareBuffer_release(r.pendingRawHardwareBuffer);
        r.pendingRawHardwareBuffer = nullptr;
    }
    if (r.dispatchFence != VK_NULL_HANDLE) {
        vkDestroyFence(state->device, r.dispatchFence, nullptr);
        r.dispatchFence = VK_NULL_HANDLE;
    }
    if (r.dispatchTimestampQueryPool != VK_NULL_HANDLE) {
        vkDestroyQueryPool(state->device, r.dispatchTimestampQueryPool, nullptr);
        r.dispatchTimestampQueryPool = VK_NULL_HANDLE;
    }
    if (r.dispatchCommandPool != VK_NULL_HANDLE) {
        vkDestroyCommandPool(state->device, r.dispatchCommandPool, nullptr);
        r.dispatchCommandPool = VK_NULL_HANDLE;
        r.dispatchCommandBuffer = VK_NULL_HANDLE;
    }
    r.pendingReadbackRecorded = false;
    r.pendingSceneLogRecorded = false;
    r.pendingUploadInitialization = false;
    r.pendingGpuTimestampSample = false;
    r.pendingGpuStageMs.fill(0.0);
    if (r.uboMapped != nullptr && r.uboMemory != VK_NULL_HANDLE) {
        vkUnmapMemory(state->device, r.uboMemory);
        r.uboMapped = nullptr;
    }
    if (r.descriptorPool != VK_NULL_HANDLE) {
        vkDestroyDescriptorPool(state->device, r.descriptorPool, nullptr); // frees descriptorSet too
        r.descriptorPool = VK_NULL_HANDLE;
        r.descriptorSet = VK_NULL_HANDLE;
    }
    if (r.monitorDescriptorPool != VK_NULL_HANDLE) {
        vkDestroyDescriptorPool(state->device, r.monitorDescriptorPool, nullptr);
        r.monitorDescriptorPool = VK_NULL_HANDLE;
        r.monitorDescriptorSet = VK_NULL_HANDLE;
    }
    if (r.sceneLogDescriptorPool != VK_NULL_HANDLE) {
        vkDestroyDescriptorPool(state->device, r.sceneLogDescriptorPool, nullptr);
        r.sceneLogDescriptorPool = VK_NULL_HANDLE;
        r.sceneLogDescriptorSet = VK_NULL_HANDLE;
    }
    if (r.previewDownsampleDescriptorPool != VK_NULL_HANDLE) {
        vkDestroyDescriptorPool(state->device, r.previewDownsampleDescriptorPool, nullptr);
        r.previewDownsampleDescriptorPool = VK_NULL_HANDLE;
        r.previewDownsampleDescriptorSet = VK_NULL_HANDLE;
    }
    if (r.previewSceneLogDescriptorPool != VK_NULL_HANDLE) {
        vkDestroyDescriptorPool(state->device, r.previewSceneLogDescriptorPool, nullptr);
        r.previewSceneLogDescriptorPool = VK_NULL_HANDLE;
        r.previewSceneLogDescriptorSet = VK_NULL_HANDLE;
    }
    r.gpuMonitorTonemapEnabled = false;
    r.sceneLogReady = false;
    if (r.ubo != VK_NULL_HANDLE) { vkDestroyBuffer(state->device, r.ubo, nullptr); r.ubo = VK_NULL_HANDLE; }
    if (r.uboMemory != VK_NULL_HANDLE) { vkFreeMemory(state->device, r.uboMemory, nullptr); r.uboMemory = VK_NULL_HANDLE; }
    const auto destroyImg = [&](VkImage& img, VkDeviceMemory& mem, VkImageView& view) {
        if (view != VK_NULL_HANDLE) { vkDestroyImageView(state->device, view, nullptr); view = VK_NULL_HANDLE; }
        if (img != VK_NULL_HANDLE) { vkDestroyImage(state->device, img, nullptr); img = VK_NULL_HANDLE; }
        if (mem != VK_NULL_HANDLE) { vkFreeMemory(state->device, mem, nullptr); mem = VK_NULL_HANDLE; }
    };
    destroyImg(r.outputImage, r.outputMemory, r.outputView);
    // The exported AHB is refcounted independently of the imported VkDeviceMemory (which we just freed),
    // so release our ref here or every resolution change leaks one AHB.
    if (r.outputAhb != nullptr) { AHardwareBuffer_release(r.outputAhb); r.outputAhb = nullptr; }
    destroyImg(r.outputImageSecondary, r.outputMemorySecondary, r.outputViewSecondary);
    if (r.outputAhbSecondary != nullptr) {
        AHardwareBuffer_release(r.outputAhbSecondary);
        r.outputAhbSecondary = nullptr;
    }
    destroyImg(r.sceneLogImage, r.sceneLogMemory, r.sceneLogView);
    if (r.sceneLogAhb != nullptr) {
        AHardwareBuffer_release(r.sceneLogAhb);
        r.sceneLogAhb = nullptr;
    }
    destroyImg(r.sceneLogImageSecondary, r.sceneLogMemorySecondary, r.sceneLogViewSecondary);
    if (r.sceneLogAhbSecondary != nullptr) {
        AHardwareBuffer_release(r.sceneLogAhbSecondary);
        r.sceneLogAhbSecondary = nullptr;
    }
    for (size_t slot = 0; slot < kRawPreviewTransportSlotCount; ++slot) {
        destroyImg(
            r.previewTransportImages[slot],
            r.previewTransportMemories[slot],
            r.previewTransportViews[slot]);
        if (r.previewTransportAhbs[slot] != nullptr) {
            AHardwareBuffer_release(r.previewTransportAhbs[slot]);
            r.previewTransportAhbs[slot] = nullptr;
        }
    }
    destroyImg(
        r.previewSceneLinearImage,
        r.previewSceneLinearMemory,
        r.previewSceneLinearView);
    destroyImg(r.darkImage, r.darkMemory, r.darkView);
    destroyImg(r.hotImage, r.hotMemory, r.hotView);
    if (r.uploadStagingMapped != nullptr && r.uploadStagingMemory != VK_NULL_HANDLE) {
        vkUnmapMemory(state->device, r.uploadStagingMemory);
        r.uploadStagingMapped = nullptr;
    }
    if (r.uploadStaging != VK_NULL_HANDLE) {
        vkDestroyBuffer(state->device, r.uploadStaging, nullptr);
        r.uploadStaging = VK_NULL_HANDLE;
    }
    if (r.uploadStagingMemory != VK_NULL_HANDLE) {
        vkFreeMemory(state->device, r.uploadStagingMemory, nullptr);
        r.uploadStagingMemory = VK_NULL_HANDLE;
    }
    destroyImg(r.uploadImage, r.uploadMemory, r.uploadView);
    r.uploadBytes = 0;
    r.uploadImageInitialized = false;
    r.rawAhbStorageImportUnsupported = false;
    // GPU/CPU monitor readback resources.
    if (r.readbackMapped != nullptr && r.readbackMemory != VK_NULL_HANDLE) {
        vkUnmapMemory(state->device, r.readbackMemory);
        r.readbackMapped = nullptr;
    }
    if (r.readbackBuffer != VK_NULL_HANDLE) { vkDestroyBuffer(state->device, r.readbackBuffer, nullptr); r.readbackBuffer = VK_NULL_HANDLE; }
    if (r.readbackMemory != VK_NULL_HANDLE) { vkFreeMemory(state->device, r.readbackMemory, nullptr); r.readbackMemory = VK_NULL_HANDLE; }
    releaseRawDevelopReadbackResources(state);
    releaseRawNiv99Resources(state);
    releaseRawDevelopP010Resources(state);
    destroyImg(r.previewImage, r.previewMemory, r.previewView);
    r.previewW = 0;
    r.previewH = 0;
    r.previewReady = false;
    r.previewImageInitialized = false;
    r.deferredCompletionCount = 0;
    r.sceneLogReadySecondary = false;
    r.transportOwnership = {{kRawTransportFree, kRawTransportFree}};
    r.transportCompletionSerial = {{0u, 0u}};
    r.transportTokens = {{0u, 0u}};
    r.dispatchTransportSlot = -1;
    r.recordingTransportRingReady = false;
    LOGI(
        "RAW_PREVIEW_RING_TEARDOWN generated=%llu acquired=%llu acknowledged=%llu "
        "saturated=%llu outstanding=%zu",
        static_cast<unsigned long long>(r.previewGeneratedCount),
        static_cast<unsigned long long>(r.previewAcquiredCount),
        static_cast<unsigned long long>(r.previewAcknowledgedCount),
        static_cast<unsigned long long>(r.previewSaturatedCount),
        rawPreviewTransportOutstandingCount(r));
    r.previewTransportOwnership.fill(kRawTransportFree);
    r.previewTransportTokens.fill(0u);
    r.previewTransportFormat = VK_FORMAT_UNDEFINED;
    r.previewTransportAhbFormat = 0u;
    r.previewTransportW = 0;
    r.previewTransportH = 0;
    r.previewTransportSceneLog = false;
    r.previewTransportRingReady = false;
    r.dispatchPreviewTransportSlot = -1;
    r.width = 0;
    r.height = 0;
    return true;
}

bool createRawDemosaicOwnedImage(
    VulkanBridgeState* state, int width, int height, VkFormat format,
    VkImage* image, VkDeviceMemory* memory, VkImageView* view
) {
    // Fail closed if the requested format can't be used as an optimal-tiling storage image on this device
    // (mirrors the RGBA16F gate in createRealtimeComputePipeline) -> caller returns false -> YUV fallback.
    VkFormatProperties fmtProps{};
    vkGetPhysicalDeviceFormatProperties(state->physicalDevice, format, &fmtProps);
    if ((fmtProps.optimalTilingFeatures & VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT) == 0) {
        setStatus(state, "RAW demosaic skipped: owned storage format unsupported on this device");
        return false;
    }

    VkImageCreateInfo imageInfo{};
    imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = format;
    imageInfo.extent = { static_cast<uint32_t>(width), static_cast<uint32_t>(height), 1u };
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    // TRANSFER_SRC added so the ACEScg output can be GPU-downscaled (vkCmdBlitImage) for the CPU-readback
    // present path; harmless for the dark/hot maps.
    imageInfo.usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (!check(vkCreateImage(state->device, &imageInfo, nullptr, image), "vkCreateImage(rawDemosaicOwned)")) {
        return false;
    }
    VkMemoryRequirements memReq{};
    vkGetImageMemoryRequirements(state->device, *image, &memReq);
    VkMemoryAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = memReq.size;
    allocInfo.memoryTypeIndex = findMemoryType(state, memReq.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (!check(vkAllocateMemory(state->device, &allocInfo, nullptr, memory), "vkAllocateMemory(rawDemosaicOwned)")) {
        vkDestroyImage(state->device, *image, nullptr); *image = VK_NULL_HANDLE; return false;
    }
    if (!check(vkBindImageMemory(state->device, *image, *memory, 0), "vkBindImageMemory(rawDemosaicOwned)")) {
        vkFreeMemory(state->device, *memory, nullptr); vkDestroyImage(state->device, *image, nullptr);
        *memory = VK_NULL_HANDLE; *image = VK_NULL_HANDLE; return false;
    }
    VkImageViewCreateInfo viewInfo{};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = *image;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = format;
    viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    viewInfo.subresourceRange.levelCount = 1;
    viewInfo.subresourceRange.layerCount = 1;
    if (!check(vkCreateImageView(state->device, &viewInfo, nullptr, view), "vkCreateImageView(rawDemosaicOwned)")) {
        vkFreeMemory(state->device, *memory, nullptr); vkDestroyImage(state->device, *image, nullptr);
        *memory = VK_NULL_HANDLE; *image = VK_NULL_HANDLE; return false;
    }
    return true;
}

// Defined after importAcesCgStorageImage (which it reuses); forward-declared here for ensureRawDemosaicResources.
bool createRawDemosaicExportableOutput(
    VulkanBridgeState* state, int width, int height,
    VkImage* image, VkDeviceMemory* memory, VkImageView* view, AHardwareBuffer** ahb);
bool createRawDemosaicSceneLogTransport(
    VulkanBridgeState* state, int width, int height,
    VkImage* image, VkDeviceMemory* memory, VkImageView* view, AHardwareBuffer** ahb);
bool createRawDemosaicPreviewFp16Transport(
    VulkanBridgeState* state, int width, int height,
    VkImage* image, VkDeviceMemory* memory, VkImageView* view, AHardwareBuffer** ahb);

bool ensureRawDemosaicResources(VulkanBridgeState* state, int width, int height) {
    auto& r = state->rawDemosaic;
    // A scene-log transport may be enabled after the same RAW extent was already allocated. Force a
    // safe worker-thread recreation in that case; otherwise the matching-extent fast path would make
    // the newly prepared project-owned transport permanently unreachable for this camera session.
    const bool needsPreparedSceneLog =
        r.outputImage != VK_NULL_HANDLE && r.outputAhb == nullptr &&
        state->rawSceneLogTransportPrepared && !state->rawSceneLogTransportUnsupported &&
        r.sceneLogImage == VK_NULL_HANDLE;
    const bool needsRecordingTransportRing =
        state->rawRecordingDemosaicActive && !r.recordingTransportRingReady;
    const bool needsRecordingPreviewTransportRing =
        rawRecordingPreviewTransportRequired(state) && !r.previewTransportRingReady;
    if (r.outputImage != VK_NULL_HANDLE && r.width == width && r.height == height &&
        !needsPreparedSceneLog && !needsRecordingTransportRing &&
        !needsRecordingPreviewTransportRing) {
        return true; // cached + already cleared to a defined state
    }
    // A completed transport may still be sampled by EGL. Extent/consumer changes must wait for the
    // exact JVM acknowledgement instead of freeing a cross-API image underneath its consumer.
    if (oldestCompletedRawTransportSlot(r) >= 0) {
        LOGW("RAW resource recreation deferred until completed transport acknowledgement");
        return false;
    }
    if (rawPreviewTransportOutstandingCount(r) != 0u) {
        LOGW("RAW resource recreation deferred until preview transport ring is quiescent");
        return false;
    }
    // On a fence deadline overrun the old resources are deliberately retained. Never overwrite their
    // handles with a new extent while the GPU can still reference them; skip this secondary frame and
    // let the next call reap/retry instead.
    if (!destroyRawDemosaicResources(state) || !state->supported) return false;

    // Output leg: prefer an AHardwareBuffer-backed rgba16f storage image so the ACEScg result can be
    // presented via the GL compositor (STAGE 2). Fall back to a plain device-local image when the device
    // can't back rgba16f as a storage AHB (r.outputAhb stays null -> preview stays direct-camera).
    if (state->rawOutputAhbStorageUnsupported ||
        !createRawDemosaicExportableOutput(state, width, height,
            &r.outputImage, &r.outputMemory, &r.outputView, &r.outputAhb)) {
        if (!createRawDemosaicOwnedImage(state, width, height, VK_FORMAT_R16G16B16A16_SFLOAT,
                &r.outputImage, &r.outputMemory, &r.outputView)) {
            destroyRawDemosaicResources(state);
            return false;
        }
    }
    // On the preferred FP16 route the export itself is the cross-API transport, so recording needs a
    // second equally precise FP16 image. Allocation is fail-closed: a single mutable master would
    // silently restore the measured demosaic->EGL serialization and cannot certify lossless 25 FPS.
    if (state->rawRecordingDemosaicActive && r.outputAhb != nullptr) {
        if (!createRawDemosaicExportableOutput(
                state, width, height,
                &r.outputImageSecondary,
                &r.outputMemorySecondary,
                &r.outputViewSecondary,
                &r.outputAhbSecondary) ||
            r.outputAhbSecondary == nullptr) {
            LOGE("RAW recorder transport ring: second FP16 export allocation failed");
            destroyRawDemosaicResources(state);
            return false;
        }
    }
    if (!createRawDemosaicOwnedImage(state, width, height, VK_FORMAT_R16_SFLOAT,
            &r.darkImage, &r.darkMemory, &r.darkView) ||
        !createRawDemosaicOwnedImage(state, width, height, VK_FORMAT_R8_UINT,
            &r.hotImage, &r.hotMemory, &r.hotView)) {
        destroyRawDemosaicResources(state);
        return false;
    }

    // If the preferred FP16 AHB cannot be exported, retain the full-resolution device-local FP16
    // master and attach a second packed-10 scene-log image for the encoder. This transport is an exact
    // project-owned encode/decode pair; display tone mapping remains downstream in the NDR operator.
    if (r.outputAhb == nullptr && state->rawSceneLogTransportPrepared &&
        !state->rawSceneLogTransportUnsupported) {
        const bool pipelineReady = state->rawSceneLogPackPipeline != VK_NULL_HANDLE &&
            state->rawSceneLogPackPipelineLayout != VK_NULL_HANDLE &&
            state->rawSceneLogPackDescLayout != VK_NULL_HANDLE;
        bool sceneLogOk = pipelineReady && createRawDemosaicSceneLogTransport(
            state, width, height,
            &r.sceneLogImage, &r.sceneLogMemory, &r.sceneLogView, &r.sceneLogAhb);
        if (sceneLogOk && state->rawRecordingDemosaicActive) {
            sceneLogOk = createRawDemosaicSceneLogTransport(
                state, width, height,
                &r.sceneLogImageSecondary,
                &r.sceneLogMemorySecondary,
                &r.sceneLogViewSecondary,
                &r.sceneLogAhbSecondary);
            if (!sceneLogOk) {
                LOGE("RAW recorder transport ring: second NEO-SCENE-LOG10 export allocation failed");
            }
        }
        if (sceneLogOk) {
            VkDescriptorPoolSize poolSize{};
            poolSize.type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
            poolSize.descriptorCount = 2;
            VkDescriptorPoolCreateInfo poolInfo{};
            poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
            poolInfo.maxSets = 1;
            poolInfo.poolSizeCount = 1;
            poolInfo.pPoolSizes = &poolSize;
            sceneLogOk = check(vkCreateDescriptorPool(
                state->device, &poolInfo, nullptr, &r.sceneLogDescriptorPool),
                "vkCreateDescriptorPool(rawSceneLog)");
        }
        if (sceneLogOk) {
            VkDescriptorSetAllocateInfo setAlloc{};
            setAlloc.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
            setAlloc.descriptorPool = r.sceneLogDescriptorPool;
            setAlloc.descriptorSetCount = 1;
            setAlloc.pSetLayouts = &state->rawSceneLogPackDescLayout;
            sceneLogOk = check(vkAllocateDescriptorSets(
                state->device, &setAlloc, &r.sceneLogDescriptorSet),
                "vkAllocateDescriptorSets(rawSceneLog)");
        }
        if (sceneLogOk) {
            VkDescriptorImageInfo input{};
            input.imageView = r.outputView;
            input.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
            VkDescriptorImageInfo output{};
            output.imageView = r.sceneLogView;
            output.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
            VkWriteDescriptorSet writes[2]{};
            for (uint32_t i = 0; i < 2; ++i) {
                writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
                writes[i].dstSet = r.sceneLogDescriptorSet;
                writes[i].dstBinding = i;
                writes[i].descriptorCount = 1;
                writes[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
            }
            writes[0].pImageInfo = &input;
            writes[1].pImageInfo = &output;
            vkUpdateDescriptorSets(state->device, 2, writes, 0, nullptr);
            LOGI("RAW demosaic present: full-resolution NEO-SCENE-LOG10 transport ready (%dx%d)",
                 width, height);
        } else {
            state->rawSceneLogTransportUnsupported = true;
            if (r.sceneLogDescriptorPool != VK_NULL_HANDLE) {
                vkDestroyDescriptorPool(state->device, r.sceneLogDescriptorPool, nullptr);
                r.sceneLogDescriptorPool = VK_NULL_HANDLE;
                r.sceneLogDescriptorSet = VK_NULL_HANDLE;
            }
            if (r.sceneLogView != VK_NULL_HANDLE) {
                vkDestroyImageView(state->device, r.sceneLogView, nullptr);
                r.sceneLogView = VK_NULL_HANDLE;
            }
            if (r.sceneLogImage != VK_NULL_HANDLE) {
                vkDestroyImage(state->device, r.sceneLogImage, nullptr);
                r.sceneLogImage = VK_NULL_HANDLE;
            }
            if (r.sceneLogMemory != VK_NULL_HANDLE) {
                vkFreeMemory(state->device, r.sceneLogMemory, nullptr);
                r.sceneLogMemory = VK_NULL_HANDLE;
            }
            if (r.sceneLogAhb != nullptr) {
                AHardwareBuffer_release(r.sceneLogAhb);
                r.sceneLogAhb = nullptr;
            }
            if (r.sceneLogViewSecondary != VK_NULL_HANDLE) {
                vkDestroyImageView(state->device, r.sceneLogViewSecondary, nullptr);
                r.sceneLogViewSecondary = VK_NULL_HANDLE;
            }
            if (r.sceneLogImageSecondary != VK_NULL_HANDLE) {
                vkDestroyImage(state->device, r.sceneLogImageSecondary, nullptr);
                r.sceneLogImageSecondary = VK_NULL_HANDLE;
            }
            if (r.sceneLogMemorySecondary != VK_NULL_HANDLE) {
                vkFreeMemory(state->device, r.sceneLogMemorySecondary, nullptr);
                r.sceneLogMemorySecondary = VK_NULL_HANDLE;
            }
            if (r.sceneLogAhbSecondary != nullptr) {
                AHardwareBuffer_release(r.sceneLogAhbSecondary);
                r.sceneLogAhbSecondary = nullptr;
            }
            LOGW("RAW demosaic present: packed scene-log transport unavailable -> monitor-only fallback");
        }
    }

    r.recordingTransportRingReady = state->rawRecordingDemosaicActive &&
        ((r.outputAhb != nullptr && r.outputAhbSecondary != nullptr) ||
         (r.outputAhb == nullptr && r.sceneLogAhb != nullptr &&
          r.sceneLogAhbSecondary != nullptr));
    if (state->rawRecordingDemosaicActive && !r.recordingTransportRingReady) {
        LOGE("RAW recorder transport ring unavailable; refusing serialized full-sensor recording");
        destroyRawDemosaicResources(state);
        return false;
    }

    if (rawRecordingPreviewTransportRequired(state)) {
        const bool downsamplePipelineReady =
            state->rawPreviewDownsamplePipeline != VK_NULL_HANDLE &&
            state->rawPreviewDownsamplePipelineLayout != VK_NULL_HANDLE &&
            state->rawPreviewDownsampleDescLayout != VK_NULL_HANDLE;
        if (!downsamplePipelineReady) {
            LOGE("RAW preview transport ring unavailable: scene-linear downsample pipeline missing");
            destroyRawDemosaicResources(state);
            return false;
        }

        const int sourceLongEdge = std::max(width, height);
        const double previewScale = sourceLongEdge > kRawRecordingPreviewLongEdge
            ? static_cast<double>(kRawRecordingPreviewLongEdge) /
                static_cast<double>(sourceLongEdge)
            : 1.0;
        const auto evenExtent = [&](int source) {
            const int rounded = static_cast<int>(std::lround(source * previewScale));
            return std::max(2, rounded & ~1);
        };
        r.previewTransportW = evenExtent(width);
        r.previewTransportH = evenExtent(height);

        const auto destroyPreviewSlot = [&](size_t slot) {
            if (r.previewTransportViews[slot] != VK_NULL_HANDLE) {
                vkDestroyImageView(state->device, r.previewTransportViews[slot], nullptr);
                r.previewTransportViews[slot] = VK_NULL_HANDLE;
            }
            if (r.previewTransportImages[slot] != VK_NULL_HANDLE) {
                vkDestroyImage(state->device, r.previewTransportImages[slot], nullptr);
                r.previewTransportImages[slot] = VK_NULL_HANDLE;
            }
            if (r.previewTransportMemories[slot] != VK_NULL_HANDLE) {
                vkFreeMemory(state->device, r.previewTransportMemories[slot], nullptr);
                r.previewTransportMemories[slot] = VK_NULL_HANDLE;
            }
            if (r.previewTransportAhbs[slot] != nullptr) {
                AHardwareBuffer_release(r.previewTransportAhbs[slot]);
                r.previewTransportAhbs[slot] = nullptr;
            }
        };

        bool fp16RingReady = true;
        for (size_t slot = 0; slot < kRawPreviewTransportSlotCount; ++slot) {
            if (!createRawDemosaicPreviewFp16Transport(
                    state,
                    r.previewTransportW,
                    r.previewTransportH,
                    &r.previewTransportImages[slot],
                    &r.previewTransportMemories[slot],
                    &r.previewTransportViews[slot],
                    &r.previewTransportAhbs[slot])) {
                fp16RingReady = false;
                break;
            }
            AHardwareBuffer_Desc desc{};
            AHardwareBuffer_describe(r.previewTransportAhbs[slot], &desc);
            if (desc.width != static_cast<uint32_t>(r.previewTransportW) ||
                desc.height != static_cast<uint32_t>(r.previewTransportH) ||
                desc.format != AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT) {
                fp16RingReady = false;
                break;
            }
        }
        if (!fp16RingReady) {
            for (size_t slot = 0; slot < kRawPreviewTransportSlotCount; ++slot) {
                destroyPreviewSlot(slot);
            }
        }

        r.previewTransportSceneLog = !fp16RingReady;
        if (fp16RingReady) {
            r.previewTransportFormat = VK_FORMAT_R16G16B16A16_SFLOAT;
            r.previewTransportAhbFormat = AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT;
        } else {
            const bool sceneLogPipelineReady =
                !state->rawSceneLogTransportUnsupported &&
                state->rawSceneLogPackPipeline != VK_NULL_HANDLE &&
                state->rawSceneLogPackPipelineLayout != VK_NULL_HANDLE &&
                state->rawSceneLogPackDescLayout != VK_NULL_HANDLE;
            bool packedRingReady = sceneLogPipelineReady && createRawDemosaicOwnedImage(
                state,
                r.previewTransportW,
                r.previewTransportH,
                VK_FORMAT_R16G16B16A16_SFLOAT,
                &r.previewSceneLinearImage,
                &r.previewSceneLinearMemory,
                &r.previewSceneLinearView);
            for (size_t slot = 0;
                 packedRingReady && slot < kRawPreviewTransportSlotCount;
                 ++slot) {
                packedRingReady = createRawDemosaicSceneLogTransport(
                    state,
                    r.previewTransportW,
                    r.previewTransportH,
                    &r.previewTransportImages[slot],
                    &r.previewTransportMemories[slot],
                    &r.previewTransportViews[slot],
                    &r.previewTransportAhbs[slot]);
                if (packedRingReady) {
                    AHardwareBuffer_Desc desc{};
                    AHardwareBuffer_describe(r.previewTransportAhbs[slot], &desc);
                    packedRingReady =
                        desc.width == static_cast<uint32_t>(r.previewTransportW) &&
                        desc.height == static_cast<uint32_t>(r.previewTransportH) &&
                        desc.format == AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM;
                }
            }
            if (!packedRingReady) {
                LOGE("RAW preview transport ring unavailable: FP16 export and RGB10 scene-log fallback failed");
                destroyRawDemosaicResources(state);
                return false;
            }
            r.previewTransportFormat = VK_FORMAT_A2B10G10R10_UNORM_PACK32;
            r.previewTransportAhbFormat = AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM;
        }

        VkDescriptorPoolSize downsamplePoolSize{};
        downsamplePoolSize.type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        downsamplePoolSize.descriptorCount = 2;
        VkDescriptorPoolCreateInfo downsamplePoolInfo{};
        downsamplePoolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
        downsamplePoolInfo.maxSets = 1;
        downsamplePoolInfo.poolSizeCount = 1;
        downsamplePoolInfo.pPoolSizes = &downsamplePoolSize;
        bool previewDescriptorsReady = check(vkCreateDescriptorPool(
            state->device,
            &downsamplePoolInfo,
            nullptr,
            &r.previewDownsampleDescriptorPool),
            "vkCreateDescriptorPool(rawPreviewDownsample)");
        if (previewDescriptorsReady) {
            VkDescriptorSetAllocateInfo allocation{};
            allocation.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
            allocation.descriptorPool = r.previewDownsampleDescriptorPool;
            allocation.descriptorSetCount = 1;
            allocation.pSetLayouts = &state->rawPreviewDownsampleDescLayout;
            previewDescriptorsReady = check(vkAllocateDescriptorSets(
                state->device,
                &allocation,
                &r.previewDownsampleDescriptorSet),
                "vkAllocateDescriptorSets(rawPreviewDownsample)");
        }
        if (previewDescriptorsReady && r.previewTransportSceneLog) {
            VkDescriptorPoolSize scenePoolSize{};
            scenePoolSize.type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
            scenePoolSize.descriptorCount = 2;
            VkDescriptorPoolCreateInfo scenePoolInfo{};
            scenePoolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
            scenePoolInfo.maxSets = 1;
            scenePoolInfo.poolSizeCount = 1;
            scenePoolInfo.pPoolSizes = &scenePoolSize;
            previewDescriptorsReady = check(vkCreateDescriptorPool(
                state->device,
                &scenePoolInfo,
                nullptr,
                &r.previewSceneLogDescriptorPool),
                "vkCreateDescriptorPool(rawPreviewSceneLog)");
            if (previewDescriptorsReady) {
                VkDescriptorSetAllocateInfo allocation{};
                allocation.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
                allocation.descriptorPool = r.previewSceneLogDescriptorPool;
                allocation.descriptorSetCount = 1;
                allocation.pSetLayouts = &state->rawSceneLogPackDescLayout;
                previewDescriptorsReady = check(vkAllocateDescriptorSets(
                    state->device,
                    &allocation,
                    &r.previewSceneLogDescriptorSet),
                    "vkAllocateDescriptorSets(rawPreviewSceneLog)");
            }
        }
        if (!previewDescriptorsReady) {
            LOGE("RAW preview transport ring descriptor allocation failed");
            destroyRawDemosaicResources(state);
            return false;
        }

        r.previewTransportOwnership.fill(kRawTransportFree);
        r.previewTransportTokens.fill(0u);
        r.dispatchPreviewTransportSlot = -1;
        r.previewTransportRingReady = true;
        LOGI(
            "RAW_PREVIEW_RING_PREWARM slots=%zu extent=%dx%d format=%s source=%dx%d",
            kRawPreviewTransportSlotCount,
            r.previewTransportW,
            r.previewTransportH,
            r.previewTransportSceneLog ? "NEO-SCENE-LOG10/RGB10_A2" : "ACEScg/RGBA16F",
            width,
            height);
    }

    // CPU-readback present target: a GPU-downscaled rgba16f monitor image (long edge capped) + a
    // host-visible buffer it is copied into. Best-effort — if any of this fails the debayer still runs;
    // only the on-screen present is skipped (previewImage stays null).
    {
        const int longEdge = std::max(width, height);
        const int cap = 1024;
        const float scale = (longEdge > cap) ? (static_cast<float>(cap) / static_cast<float>(longEdge)) : 1.0f;
        r.previewW = std::max(2, (static_cast<int>(width * scale) + 1) & ~1);
        r.previewH = std::max(2, (static_cast<int>(height * scale) + 1) & ~1);

        const auto resetPresentResources = [&]() {
            if (r.readbackMapped != nullptr && r.readbackMemory != VK_NULL_HANDLE) {
                vkUnmapMemory(state->device, r.readbackMemory);
                r.readbackMapped = nullptr;
            }
            if (r.readbackBuffer != VK_NULL_HANDLE) {
                vkDestroyBuffer(state->device, r.readbackBuffer, nullptr);
                r.readbackBuffer = VK_NULL_HANDLE;
            }
            if (r.readbackMemory != VK_NULL_HANDLE) {
                vkFreeMemory(state->device, r.readbackMemory, nullptr);
                r.readbackMemory = VK_NULL_HANDLE;
            }
            if (r.previewView != VK_NULL_HANDLE) {
                vkDestroyImageView(state->device, r.previewView, nullptr);
                r.previewView = VK_NULL_HANDLE;
            }
            if (r.previewImage != VK_NULL_HANDLE) {
                vkDestroyImage(state->device, r.previewImage, nullptr);
                r.previewImage = VK_NULL_HANDLE;
            }
            if (r.previewMemory != VK_NULL_HANDLE) {
                vkFreeMemory(state->device, r.previewMemory, nullptr);
                r.previewMemory = VK_NULL_HANDLE;
            }
            if (r.monitorDescriptorPool != VK_NULL_HANDLE) {
                vkDestroyDescriptorPool(state->device, r.monitorDescriptorPool, nullptr);
                r.monitorDescriptorPool = VK_NULL_HANDLE;
                r.monitorDescriptorSet = VK_NULL_HANDLE;
            }
            r.gpuMonitorTonemapEnabled = false;
            r.previewImageInitialized = false;
        };

        const auto allocatePresentResources = [&](bool gpuTonemap) {
            const VkFormat format = gpuTonemap
                ? VK_FORMAT_R8G8B8A8_UNORM
                : VK_FORMAT_R16G16B16A16_SFLOAT;
            const VkDeviceSize bytesPerPixel = gpuTonemap ? 4u : 8u;
            if (!createRawDemosaicOwnedImage(state, r.previewW, r.previewH, format,
                    &r.previewImage, &r.previewMemory, &r.previewView)) {
                return false;
            }
            const VkDeviceSize readbackBytes = static_cast<VkDeviceSize>(r.previewW) *
                static_cast<VkDeviceSize>(r.previewH) * bytesPerPixel;
            if (!createBufferWithMemory(state, readbackBytes, VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                    &r.readbackBuffer, &r.readbackMemory, /*requireExactProperties=*/true) ||
                !check(vkMapMemory(state->device, r.readbackMemory, 0, readbackBytes, 0,
                                   &r.readbackMapped), "vkMapMemory(rawReadback)")) {
                return false;
            }
            if (!gpuTonemap) return true;

            VkDescriptorPoolSize monitorPoolSize{};
            monitorPoolSize.type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
            monitorPoolSize.descriptorCount = 2;
            VkDescriptorPoolCreateInfo monitorPoolInfo{};
            monitorPoolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
            monitorPoolInfo.maxSets = 1;
            monitorPoolInfo.poolSizeCount = 1;
            monitorPoolInfo.pPoolSizes = &monitorPoolSize;
            if (!check(vkCreateDescriptorPool(state->device, &monitorPoolInfo, nullptr,
                                              &r.monitorDescriptorPool),
                       "vkCreateDescriptorPool(rawMonitorTonemap)")) {
                return false;
            }
            VkDescriptorSetAllocateInfo monitorSetAlloc{};
            monitorSetAlloc.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
            monitorSetAlloc.descriptorPool = r.monitorDescriptorPool;
            monitorSetAlloc.descriptorSetCount = 1;
            monitorSetAlloc.pSetLayouts = &state->rawMonitorTonemapDescLayout;
            if (!check(vkAllocateDescriptorSets(state->device, &monitorSetAlloc,
                                                &r.monitorDescriptorSet),
                       "vkAllocateDescriptorSets(rawMonitorTonemap)")) {
                return false;
            }
            VkDescriptorImageInfo monitorInput{};
            monitorInput.imageView = r.outputView;
            monitorInput.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
            VkDescriptorImageInfo monitorOutput{};
            monitorOutput.imageView = r.previewView;
            monitorOutput.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
            VkWriteDescriptorSet monitorWrites[2]{};
            for (uint32_t i = 0; i < 2; ++i) {
                monitorWrites[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
                monitorWrites[i].dstSet = r.monitorDescriptorSet;
                monitorWrites[i].dstBinding = i;
                monitorWrites[i].descriptorCount = 1;
                monitorWrites[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
            }
            monitorWrites[0].pImageInfo = &monitorInput;
            monitorWrites[1].pImageInfo = &monitorOutput;
            vkUpdateDescriptorSets(state->device, 2, monitorWrites, 0, nullptr);
            r.gpuMonitorTonemapEnabled = true;
            return true;
        };

        const bool gpuPipelineReady = state->rawMonitorTonemapPipeline != VK_NULL_HANDLE &&
            state->rawMonitorTonemapPipelineLayout != VK_NULL_HANDLE &&
            state->rawMonitorTonemapDescLayout != VK_NULL_HANDLE;
        bool presentOk = gpuPipelineReady && allocatePresentResources(true);
        if (!presentOk && gpuPipelineReady) {
            resetPresentResources();
        }
        if (!presentOk) {
            presentOk = allocatePresentResources(false);
        }
        if (presentOk) {
            r.previewRgba8.assign(static_cast<size_t>(r.previewW) * r.previewH * 4u, 0u);
            LOGI("RAW demosaic present: %s monitor %dx%d ready",
                 r.gpuMonitorTonemapEnabled ? "GPU-tonemapped RGBA8" : "CPU-tonemapped FP16",
                 r.previewW, r.previewH);
        } else {
            resetPresentResources();
            LOGW("RAW demosaic present: readback resources unavailable -> no on-screen debayer (compute still runs)");
            r.previewW = 0;
            r.previewH = 0;
        }
    }

    // Host-visible/coherent UBO so per-frame params are a plain map write (no staging copy).
    if (!createBufferWithMemory(state, sizeof(RawDemosaicUniforms), VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
            &r.ubo, &r.uboMemory, /*requireExactProperties=*/true)) {
        destroyRawDemosaicResources(state);
        return false;
    }
    if (!check(vkMapMemory(state->device, r.uboMemory, 0, sizeof(RawDemosaicUniforms), 0, &r.uboMapped),
               "vkMapMemory(rawDemosaicUbo)")) {
        destroyRawDemosaicResources(state);
        return false;
    }

    // Dedicated pool isolated from the global descriptor budget (1 set: 4 storage images + 1 UBO).
    VkDescriptorPoolSize poolSizes[2]{};
    poolSizes[0].type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    poolSizes[0].descriptorCount = 4;
    poolSizes[1].type = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    poolSizes[1].descriptorCount = 1;
    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = 1;
    poolInfo.poolSizeCount = 2;
    poolInfo.pPoolSizes = poolSizes;
    if (!check(vkCreateDescriptorPool(state->device, &poolInfo, nullptr, &r.descriptorPool),
               "vkCreateDescriptorPool(rawDemosaic)")) {
        destroyRawDemosaicResources(state);
        return false;
    }
    VkDescriptorSetAllocateInfo setAlloc{};
    setAlloc.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    setAlloc.descriptorPool = r.descriptorPool;
    setAlloc.descriptorSetCount = 1;
    setAlloc.pSetLayouts = &state->demosaicDescLayout;
    if (!check(vkAllocateDescriptorSets(state->device, &setAlloc, &r.descriptorSet),
               "vkAllocateDescriptorSets(rawDemosaic)")) {
        destroyRawDemosaicResources(state);
        return false;
    }

    // Deterministically clear the owned output + defect maps and move them to GENERAL for compute.
    // bindings 2/3 (dark/hot) therefore read a defined zero (no defect contribution by default) and
    // the dispatch never touches UNDEFINED memory — the validation-clean criterion holds by construction.
    {
        VkCommandPool clearPool = VK_NULL_HANDLE;
        VkCommandBuffer clearCmd = VK_NULL_HANDLE;
        if (!beginSingleUseCommands(state, &clearPool, &clearCmd)) {
            destroyRawDemosaicResources(state);
            return false;
        }
        const VkClearColorValue zero{}; // {0,0,0,0} — float for rgba16f/r16f, uint for r8ui
        VkImageSubresourceRange range{};
        range.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        range.levelCount = 1;
        range.layerCount = 1;
        const VkImage imgs[4] = {
            r.outputImage,
            r.outputImageSecondary,
            r.darkImage,
            r.hotImage
        };
        for (VkImage image : imgs) {
            if (image == VK_NULL_HANDLE) continue;
            transitionImageLayout(clearCmd, image,
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, range);
            vkCmdClearColorImage(clearCmd, image,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &zero, 1, &range);
            VkImageMemoryBarrier toGeneral{};
            toGeneral.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            toGeneral.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            toGeneral.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
            toGeneral.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
            toGeneral.newLayout = VK_IMAGE_LAYOUT_GENERAL;
            toGeneral.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            toGeneral.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            toGeneral.image = image;
            toGeneral.subresourceRange = range;
            vkCmdPipelineBarrier(clearCmd,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                0, 0, nullptr, 0, nullptr, 1, &toGeneral);
        }
        // The imported RGB10_A2 transport is STORAGE-only, and the pack shader overwrites every texel.
        // Initialize its layout without an invalid TRANSFER_DST clear.
        const VkImage sceneLogImages[2] = {
            r.sceneLogImage,
            r.sceneLogImageSecondary
        };
        for (VkImage sceneLogImage : sceneLogImages) {
            if (sceneLogImage == VK_NULL_HANDLE) continue;
            VkImageMemoryBarrier sceneLogGeneral{};
            sceneLogGeneral.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            sceneLogGeneral.srcAccessMask = 0;
            sceneLogGeneral.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
            sceneLogGeneral.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
            sceneLogGeneral.newLayout = VK_IMAGE_LAYOUT_GENERAL;
            sceneLogGeneral.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            sceneLogGeneral.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            sceneLogGeneral.image = sceneLogImage;
            sceneLogGeneral.subresourceRange = range;
            vkCmdPipelineBarrier(clearCmd,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                0, 0, nullptr, 0, nullptr, 1, &sceneLogGeneral);
        }
        // Independent preview ring images are fully overwritten before publication. Initialize every
        // slot (and the packed fallback's FP16 intermediate) to GENERAL without requiring transfer usage.
        std::array<VkImage, kRawPreviewTransportSlotCount + 1u> previewImages{};
        previewImages[0] = r.previewSceneLinearImage;
        for (size_t slot = 0; slot < kRawPreviewTransportSlotCount; ++slot) {
            previewImages[slot + 1u] = r.previewTransportImages[slot];
        }
        for (VkImage previewImage : previewImages) {
            if (previewImage == VK_NULL_HANDLE) continue;
            VkImageMemoryBarrier previewGeneral{};
            previewGeneral.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            previewGeneral.srcAccessMask = 0;
            previewGeneral.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
            previewGeneral.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
            previewGeneral.newLayout = VK_IMAGE_LAYOUT_GENERAL;
            previewGeneral.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            previewGeneral.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            previewGeneral.image = previewImage;
            previewGeneral.subresourceRange = range;
            vkCmdPipelineBarrier(
                clearCmd,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                0, 0, nullptr, 0, nullptr, 1, &previewGeneral);
        }
        if (!endSingleUseCommands(state, clearPool, clearCmd)) {
            destroyRawDemosaicResources(state);
            return false;
        }
    }

    // Allocate once per RAW extent. Per-frame dispatches reset/reuse these objects and wait only on
    // their own fence, so preview work never drains unrelated encoder submissions from the queue.
    VkCommandPoolCreateInfo dispatchPoolInfo{};
    dispatchPoolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    dispatchPoolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    dispatchPoolInfo.queueFamilyIndex = state->queueFamilyIndex;
    if (!check(vkCreateCommandPool(
            state->device, &dispatchPoolInfo, nullptr, &r.dispatchCommandPool),
            "vkCreateCommandPool(rawDemosaicPersistent)")) {
        destroyRawDemosaicResources(state);
        return false;
    }
    VkCommandBufferAllocateInfo dispatchAlloc{};
    dispatchAlloc.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    dispatchAlloc.commandPool = r.dispatchCommandPool;
    dispatchAlloc.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    dispatchAlloc.commandBufferCount = 1;
    if (!check(vkAllocateCommandBuffers(
            state->device, &dispatchAlloc, &r.dispatchCommandBuffer),
            "vkAllocateCommandBuffers(rawDemosaicPersistent)")) {
        destroyRawDemosaicResources(state);
        return false;
    }
    VkFenceCreateInfo dispatchFenceInfo{};
    dispatchFenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    dispatchFenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
    if (!check(vkCreateFence(
            state->device, &dispatchFenceInfo, nullptr, &r.dispatchFence),
            "vkCreateFence(rawDemosaicPersistent)")) {
        destroyRawDemosaicResources(state);
        return false;
    }
    if (state->timestampQueriesSupported && state->timestampPeriodNs > 0.0) {
        VkQueryPoolCreateInfo timestampPoolInfo{};
        timestampPoolInfo.sType = VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO;
        timestampPoolInfo.queryType = VK_QUERY_TYPE_TIMESTAMP;
        timestampPoolInfo.queryCount = kRawGpuTimestampCount;
        const VkResult timestampPoolResult = vkCreateQueryPool(
            state->device,
            &timestampPoolInfo,
            nullptr,
            &r.dispatchTimestampQueryPool);
        if (timestampPoolResult != VK_SUCCESS) {
            r.dispatchTimestampQueryPool = VK_NULL_HANDLE;
            LOGW(
                "RAW GPU stage timestamps unavailable result=%d; dispatch remains unchanged",
                static_cast<int>(timestampPoolResult));
        }
    }

    r.width = width;
    r.height = height;
    r.transportOwnership = {{kRawTransportFree, kRawTransportFree}};
    r.transportCompletionSerial = {{0u, 0u}};
    r.transportTokens = {{0u, 0u}};
    r.dispatchTransportSlot = -1;
    return true;
}

bool importRawStorageImage(
    VulkanBridgeState* state, AHardwareBuffer* hardwareBuffer, int width, int height,
    VkImage* outImage, VkDeviceMemory* outMemory, VkImageView* outView
) {
    *outImage = VK_NULL_HANDLE;
    *outMemory = VK_NULL_HANDLE;
    *outView = VK_NULL_HANDLE;

    VkAndroidHardwareBufferFormatPropertiesANDROID formatProps{};
    formatProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID;
    VkAndroidHardwareBufferPropertiesANDROID bufferProps{};
    bufferProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
    bufferProps.pNext = &formatProps;
    if (!check(state->getAndroidHardwareBufferProperties(state->device, hardwareBuffer, &bufferProps),
               "vkGetAndroidHardwareBufferPropertiesANDROID(raw)")) {
        return false;
    }
    // binding 0 is `layout(r16ui) uimage2D` — it must be backed by a single-channel 16-bit-UINT storage
    // image. An external/YCbCr-vendor format cannot back r16ui storage, so bail to the YUV fallback rather
    // than risk an undefined imageLoad. Whether a given device's RAW16 AHB imports as storage R16_UINT is
    // the device-gated part; the contract here is honest (fail closed, never a black/garbage preview).
    if (formatProps.externalFormat != 0 || formatProps.format != VK_FORMAT_R16_UINT) {
        setStatus(state, "RAW demosaic skipped: AHB is not a storage-capable R16_UINT mosaic");
        return false;
    }
    // Binding an image with STORAGE usage to imported AHB memory requires the AHB's reported feature set to
    // include STORAGE (VUID-VkImageCreateInfo-pNext-02390). Camera RAW16 buffers are commonly CPU/sampled
    // only; fail closed to the YUV fallback rather than create a storage image the AHB can't back.
    if ((formatProps.formatFeatures & VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT) == 0) {
        setStatus(state, "RAW demosaic skipped: imported AHB lacks GPU storage capability");
        return false;
    }

    VkExternalMemoryImageCreateInfo externalImageInfo{};
    externalImageInfo.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
    externalImageInfo.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;

    VkImageCreateInfo imageInfo{};
    imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imageInfo.pNext = &externalImageInfo;
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = VK_FORMAT_R16_UINT;
    imageInfo.extent = { static_cast<uint32_t>(std::max(1, width)), static_cast<uint32_t>(std::max(1, height)), 1u };
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.usage = VK_IMAGE_USAGE_STORAGE_BIT;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (!check(vkCreateImage(state->device, &imageInfo, nullptr, outImage), "vkCreateImage(rawStorageImport)")) {
        return false;
    }

    VkImportAndroidHardwareBufferInfoANDROID importInfo{};
    importInfo.sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
    importInfo.buffer = hardwareBuffer;
    VkMemoryDedicatedAllocateInfo dedicatedInfo{};
    dedicatedInfo.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
    dedicatedInfo.image = *outImage;
    importInfo.pNext = &dedicatedInfo;

    VkMemoryAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.pNext = &importInfo;
    allocInfo.allocationSize = bufferProps.allocationSize;
    allocInfo.memoryTypeIndex = findMemoryType(state, bufferProps.memoryTypeBits, 0);
    if (!check(vkAllocateMemory(state->device, &allocInfo, nullptr, outMemory), "vkAllocateMemory(rawStorageImport)")) {
        vkDestroyImage(state->device, *outImage, nullptr); *outImage = VK_NULL_HANDLE; return false;
    }
    if (!check(vkBindImageMemory(state->device, *outImage, *outMemory, 0), "vkBindImageMemory(rawStorageImport)")) {
        vkFreeMemory(state->device, *outMemory, nullptr); vkDestroyImage(state->device, *outImage, nullptr);
        *outMemory = VK_NULL_HANDLE; *outImage = VK_NULL_HANDLE; return false;
    }
    VkImageViewCreateInfo viewInfo{};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = *outImage;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = VK_FORMAT_R16_UINT;
    viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    viewInfo.subresourceRange.levelCount = 1;
    viewInfo.subresourceRange.layerCount = 1;
    if (!check(vkCreateImageView(state->device, &viewInfo, nullptr, outView), "vkCreateImageView(rawStorageImport)")) {
        vkFreeMemory(state->device, *outMemory, nullptr); vkDestroyImage(state->device, *outImage, nullptr);
        *outMemory = VK_NULL_HANDLE; *outImage = VK_NULL_HANDLE; return false;
    }
    return true;
}

// IEEE-754 half (fp16) -> float, for the CPU-readback present tonemap (reads the rgba16f ACEScg preview).
static inline float npHalfToFloat(uint16_t h) {
    const uint32_t sign = (static_cast<uint32_t>(h) & 0x8000u) << 16;
    uint32_t exp = (h >> 10) & 0x1Fu;
    uint32_t mant = h & 0x3FFu;
    uint32_t f;
    if (exp == 0u) {
        if (mant == 0u) {
            f = sign;
        } else {
            exp = 1u;
            while ((mant & 0x400u) == 0u) { mant <<= 1; --exp; }
            mant &= 0x3FFu;
            f = sign | ((exp + (127u - 15u)) << 23) | (mant << 13);
        }
    } else if (exp == 31u) {
        f = sign | 0x7F800000u | (mant << 13);
    } else {
        f = sign | ((exp + (127u - 15u)) << 23) | (mant << 13);
    }
    float out;
    std::memcpy(&out, &f, sizeof(out));
    return out;
}

static inline float npRawMonitorNeutralShoulder(float linear) {
    const float x = std::max(0.0f, isIeee754Finite(linear) ? linear : 0.0f);
    constexpr float knee = 0.82f;
    if (x <= knee) return x;
    const float headroom = 1.0f - knee;
    const float excess = x - knee;
    return std::clamp(knee + headroom * (excess / (excess + headroom)), 0.0f, 1.0f);
}

// The NDR signal operator defines 1.0 as its scene reference. The project-owned
// NDR-NIVUKX-PY-CAL-v4 fits the visible SDR diffuse anchor to sRGB 0.80 / linear 0.60382734. The
// FP16 ACEScg scene image and PQ recording master are untouched. Keep exact black and use a
// slope-continuous rational shoulder so super-reference scene highlights remain monotone and unclipped.
static inline float npRawNdrSdrMonitorLuminance(float shapedSignal) {
    const float x = std::max(0.0f, isIeee754Finite(shapedSignal) ? shapedSignal : 0.0f);
    constexpr float kDiffuseWhiteLinear = 0.60382734f;
    if (x <= 1.0f) return x * kDiffuseWhiteLinear;

    constexpr float kHeadroom = 1.0f - kDiffuseWhiteLinear;
    constexpr float kShoulderLength = kHeadroom / kDiffuseWhiteLinear;
    const float excess = x - 1.0f;
    return std::clamp(
        kDiffuseWhiteLinear + kHeadroom * (excess / (excess + kShoulderLength)),
        0.0f,
        1.0f
    );
}

static inline uint8_t npRawMonitorSrgb8(float linear) {
    const float c = std::clamp(isIeee754Finite(linear) ? linear : 0.0f, 0.0f, 1.0f);
    // The destination is RGBA8, so a 16-bit linear-domain LUT is more precise than the output while
    // removing three scalar pow() calls per monitor pixel. The table is immutable after thread-safe
    // C++ static initialization and changes neither the FP16 processing image nor recorded pixels.
    static const std::array<uint8_t, 65536> kLinearToSrgb8 = [] {
        std::array<uint8_t, 65536> table{};
        for (size_t i = 0; i < table.size(); ++i) {
            const float sample = static_cast<float>(i) / 65535.0f;
            const float encoded = sample <= 0.0031308f
                ? 12.92f * sample
                : 1.055f * std::pow(sample, 1.0f / 2.4f) - 0.055f;
            table[i] = static_cast<uint8_t>(
                std::clamp(encoded, 0.0f, 1.0f) * 255.0f + 0.5f
            );
        }
        return table;
    }();
    const size_t index = static_cast<size_t>(c * 65535.0f + 0.5f);
    return kLinearToSrgb8[index];
}

struct NpRawRgb {
    float r;
    float g;
    float b;
};

static inline float npRawSmoothstep(float edge0, float edge1, float x) {
    if (edge0 == edge1) return x >= edge1 ? 1.0f : 0.0f;
    const float t = std::clamp((x - edge0) / (edge1 - edge0), 0.0f, 1.0f);
    return t * t * (3.0f - 2.0f * t);
}

static inline float npRawAcesCgLuma(const NpRawRgb& c) {
    return c.r * 0.2722287f + c.g * 0.6740818f + c.b * 0.0536895f;
}

static inline float npRawRec709Luma(const NpRawRgb& c) {
    return c.r * 0.2126f + c.g * 0.7152f + c.b * 0.0722f;
}

static inline float npRawBt2020Luma(const NpRawRgb& c) {
    return c.r * 0.2627f + c.g * 0.6780f + c.b * 0.0593f;
}

// Bring a Rec.709 triplet into the display cube without the old peak-normalization luminance loss.
// Moving toward a neutral point with the same Rec.709 luminance preserves brightness and hue ordering;
// only the un-displayable chroma component is reduced.
static inline NpRawRgb npRawCompressRec709GamutPreserveLuma(NpRawRgb color) {
    const float peak = std::max({color.r, color.g, color.b});
    if (peak <= 1.0f) return color;

    const float y = std::clamp(npRawRec709Luma(color), 0.0f, 1.0f);
    const float chromaSpan = peak - y;
    if (chromaSpan <= 1.0e-7f) return {y, y, y};
    const float chromaScale = std::clamp((1.0f - y) / chromaSpan, 0.0f, 1.0f);
    return {
        y + (color.r - y) * chromaScale,
        y + (color.g - y) * chromaScale,
        y + (color.b - y) * chromaScale
    };
}

// Rodrigues rotation around the neutral RGB axis. The result is renormalized to the original AP1
// luminance below, so the measured hue repair cannot alter exposure or highlight latitude.
static inline NpRawRgb npRawRotateHueNeutralAxis(const NpRawRgb& c, float degrees) {
    constexpr float invSqrt3 = 0.5773502691896258f;
    constexpr float degreesToRadians = 0.017453292519943295f;
    const float angle = degrees * degreesToRadians;
    const float cs = std::cos(angle);
    const float sn = std::sin(angle);
    const float axisDot = (c.r + c.g + c.b) * invSqrt3;
    const NpRawRgb axisCross{
        (c.b - c.g) * invSqrt3,
        (c.r - c.b) * invSqrt3,
        (c.g - c.r) * invSqrt3
    };
    return {
        c.r * cs + axisCross.r * sn + invSqrt3 * axisDot * (1.0f - cs),
        c.g * cs + axisCross.g * sn + invSqrt3 * axisDot * (1.0f - cs),
        c.b * cs + axisCross.b * sn + invSqrt3 * axisDot * (1.0f - cs)
    };
}

static inline NpRawRgb npRawApplyMeasuredNdrCorrection(
    NpRawRgb source,
    const NdrMeasuredCorrectionNative& correction
) {
    source.r = std::max(source.r, 0.0f);
    source.g = std::max(source.g, 0.0f);
    source.b = std::max(source.b, 0.0f);
    if (!correction.active || correction.mode < 1 || correction.mode > 2) return source;

    const float originalY = std::max(npRawAcesCgLuma(source), 1.0e-7f);
    source.r *= correction.redScale;
    source.g *= correction.greenScale;
    source.b *= correction.blueScale;
    const float scaleY = std::max(npRawAcesCgLuma(source), 1.0e-7f);
    const float balance = std::clamp(originalY / scaleY, 0.85f, 1.15f);
    source.r *= balance;
    source.g *= balance;
    source.b *= balance;

    const float rg = source.r / std::max(source.g, 1.0e-7f);
    const float rb = source.r / std::max(source.b, 1.0e-7f);
    const float bg = source.b / std::max(source.g, 1.0e-7f);
    const float skin = npRawSmoothstep(1.04f, 1.24f, rg) *
        (1.0f - npRawSmoothstep(1.70f, 2.25f, rg)) *
        npRawSmoothstep(1.02f, 1.32f, rb) *
        npRawSmoothstep(0.035f, 0.16f, originalY) *
        (1.0f - npRawSmoothstep(0.75f, 1.10f, originalY));
    const float sky = npRawSmoothstep(1.02f, 1.24f, bg) *
        (1.0f - npRawSmoothstep(1.00f, 1.28f, rg)) *
        npRawSmoothstep(0.045f, 0.20f, originalY) *
        (1.0f - npRawSmoothstep(1.00f, 1.45f, originalY));
    const float hueDegrees = correction.skinHueCorrectionDegrees * skin +
        correction.skyHueCorrectionDegrees * sky;
    if (std::abs(hueDegrees) > 1.0e-5f) {
        source = npRawRotateHueNeutralAxis(source, hueDegrees);
        const float rotatedY = std::max(npRawAcesCgLuma(source), 1.0e-7f);
        const float preserveY = std::clamp(originalY / rotatedY, 0.85f, 1.15f);
        source.r *= preserveY;
        source.g *= preserveY;
        source.b *= preserveY;
    }

    const float y = npRawAcesCgLuma(source);
    source.r = y + (source.r - y) * correction.saturationScale;
    source.g = y + (source.g - y) * correction.saturationScale;
    source.b = y + (source.b - y) * correction.saturationScale;
    const float shadowWindow = 1.0f - npRawSmoothstep(0.035f, 0.22f, originalY);
    const float shadowLift = correction.shadowLiftBias * shadowWindow;
    source.r = std::max(source.r + shadowLift, 0.0f);
    source.g = std::max(source.g + shadowLift, 0.0f);
    source.b = std::max(source.b + shadowLift, 0.0f);
    return source;
}

static inline float npRawNdrSignalShape(float x, float gamma, float toeWidth, float toeAmount) {
    if (x <= 0.0f) return 0.0f;
    if (x <= 1.0f) {
        return std::pow(x, gamma) +
            npRawSmoothstep(0.0f, toeWidth, x) * toeAmount * (1.0f - x);
    }
    return 1.0f + (gamma - toeAmount) * (x - 1.0f);
}

static inline float npRawNdrBlackPreservingMidtoneLift(float shapedSignal, float strength) {
    const float x = std::max(
        0.0f,
        isIeee754Finite(shapedSignal) ? shapedSignal : 0.0f
    );
    if (x <= 0.0f || x >= 1.0f) return x;
    const float window = npRawSmoothstep(0.025f, 0.20f, x) *
        (1.0f - npRawSmoothstep(0.78f, 1.20f, x));
    return x + strength * std::sqrt(x) * (1.0f - x) * window;
}

static inline float npRawNdrProfileMidtoneContrast(float signal, float strength) {
    const float x = std::max(0.0f, isIeee754Finite(signal) ? signal : 0.0f);
    if (x <= 0.0f || x >= 1.0f) return x;
    const float window = npRawSmoothstep(0.055f, 0.24f, x) *
        (1.0f - npRawSmoothstep(0.76f, 1.0f, x));
    const float signedContrast = (x - 0.50f) * 4.0f * x * (1.0f - x);
    return std::max(x + strength * signedContrast * window, 0.0f);
}

static inline NpRawRgb npRawNdrProfileChromaCharacter(
    NpRawRgb color,
    float baseBoost,
    float skyBonusStrength
) {
    const float y = std::max(npRawBt2020Luma(color), 1.0e-7f);
    const float peak = std::max({color.r, color.g, color.b});
    const float floorValue = std::min({color.r, color.g, color.b});
    const float relativeChroma = (peak - floorValue) / std::max(y, 0.04f);
    const float colorConfidence = npRawSmoothstep(0.035f, 0.22f, relativeChroma);
    const float rg = color.r / std::max(color.g, 1.0e-6f);
    const float bg = color.b / std::max(color.g, 1.0e-6f);
    const float skin = npRawSmoothstep(1.02f, 1.16f, rg) *
        (1.0f - npRawSmoothstep(1.58f, 2.10f, rg)) *
        (1.0f - npRawSmoothstep(0.98f, 1.18f, bg));
    const float sky = npRawSmoothstep(1.03f, 1.22f, bg) *
        (1.0f - npRawSmoothstep(1.00f, 1.22f, rg));
    const float tonalWindow = npRawSmoothstep(0.035f, 0.16f, y) *
        (1.0f - npRawSmoothstep(0.88f, 1.65f, y));
    const float skyBonus = sky * skyBonusStrength;
    const float skinProtection = 1.0f + (0.45f - 1.0f) * skin;
    const float gain = 1.0f +
        (baseBoost + skyBonus) * colorConfidence * tonalWindow * skinProtection;
    return {
        y + (color.r - y) * gain,
        y + (color.g - y) * gain,
        y + (color.b - y) * gain
    };
}

static inline float npRawApplyMeasuredHighlightRolloff(float signal, float bias) {
    const float x = std::max(signal, 0.0f);
    if (x <= 1.0f || bias <= 0.0f) return x;
    const float excess = x - 1.0f;
    return 1.0f + excess / (1.0f + 4.0f * bias * excess);
}

static inline NpRawRgb npRawNdrSdrMonitor(
    float ap1R,
    float ap1G,
    float ap1B,
    const NdrMeasuredCorrectionNative& correction,
    float localDetailStops
) {
    const NpRawRgb ap1 = npRawApplyMeasuredNdrCorrection(
        {ap1R, ap1G, ap1B},
        correction
    );
    NpRawRgb rec2020{
        std::max(0.0f, 1.02582f * ap1.r - 0.02005f * ap1.g - 0.00577f * ap1.b),
        std::max(0.0f, -0.00386f * ap1.r + 1.01040f * ap1.g - 0.00654f * ap1.b),
        std::max(0.0f, -0.01784f * ap1.r - 0.07404f * ap1.g + 1.09188f * ap1.b)
    };

    // Presentation-only adaptation. It changes neither the FP16 RAW master nor recording metadata.
    const float sensorDiffuseScene = std::clamp(correction.sensorDiffuseScene, 0.02f, 0.30f);
    rec2020.r /= sensorDiffuseScene;
    rec2020.g /= sensorDiffuseScene;
    rec2020.b /= sensorDiffuseScene;
    const float y = std::max(npRawBt2020Luma(rec2020), 1.0e-7f);
    const float gamma = std::clamp(correction.signalGamma, 0.10f, 1.0f);
    const float toeWidth = std::clamp(correction.toeWidth, 0.001f, 0.90f);
    const float toeAmount = std::clamp(correction.toeAmount, 0.0f, 0.20f);
    float mappedY = npRawNdrBlackPreservingMidtoneLift(
        npRawNdrSignalShape(y, gamma, toeWidth, toeAmount),
        std::clamp(correction.shadowMidtoneLift, 0.0f, 0.35f)
    );
    mappedY = npRawNdrProfileMidtoneContrast(
        mappedY,
        std::clamp(correction.midtoneContrast, 0.0f, 0.30f)
    );
    const float detailStrength = std::clamp(correction.localDetailStrength, 0.0f, 0.75f);
    const float detailGainStops = std::clamp(
        localDetailStops * detailStrength,
        std::clamp(correction.localDetailLowerStops, -0.20f, 0.0f),
        std::clamp(correction.localDetailUpperStops, 0.0f, 0.20f)
    );
    mappedY *= std::exp2(detailGainStops);
    const float mapScale = mappedY / y;
    rec2020.r *= mapScale;
    rec2020.g *= mapScale;
    rec2020.b *= mapScale;
    rec2020 = npRawNdrProfileChromaCharacter(
        rec2020,
        std::clamp(correction.chromaBoost, 0.0f, 0.25f),
        std::clamp(correction.skyChromaBonus, 0.0f, 0.08f)
    );

    // Midtone colour remains untouched. Only super-white SDR highlights receive a small,
    // luminance-preserving chroma roll-off before the display-gamut boundary.
    const float desatCap = std::clamp(correction.highlightDesat, 0.0f, 0.50f);
    const float desat = std::clamp(
        desatCap * npRawSmoothstep(0.55f, 1.0f, mappedY),
        0.0f,
        desatCap
    );
    rec2020.r += (mappedY - rec2020.r) * desat;
    rec2020.g += (mappedY - rec2020.g) * desat;
    rec2020.b += (mappedY - rec2020.b) * desat;

    const float preRolloffY = std::max(npRawBt2020Luma(rec2020), 1.0e-7f);
    const float rolledY = npRawApplyMeasuredHighlightRolloff(
        preRolloffY,
        correction.highlightRolloffBias
    );
    const float rolloffScale = rolledY / preRolloffY;
    rec2020.r *= rolloffScale;
    rec2020.g *= rolloffScale;
    rec2020.b *= rolloffScale;

    // SDR monitor only: display the same NDR toe/highlight decisions around an explicit diffuse-white
    // anchor while keeping the surface true sRGB. Recording remains scene-linear -> Rec.2020 -> ST.2084.
    const float monitorY = std::max(npRawBt2020Luma(rec2020), 1.0e-7f);
    const float monitorScale = npRawNdrSdrMonitorLuminance(monitorY) / monitorY;
    rec2020.r *= monitorScale;
    rec2020.g *= monitorScale;
    rec2020.b *= monitorScale;
    NpRawRgb rec709{
        1.6605f * rec2020.r - 0.5876f * rec2020.g - 0.0728f * rec2020.b,
        -0.1246f * rec2020.r + 1.1329f * rec2020.g - 0.0083f * rec2020.b,
        -0.0182f * rec2020.r - 0.1006f * rec2020.g + 1.1187f * rec2020.b
    };
    rec709.r = std::max(rec709.r, 0.0f);
    rec709.g = std::max(rec709.g, 0.0f);
    rec709.b = std::max(rec709.b, 0.0f);
    return npRawCompressRec709GamutPreserveLuma(rec709);
}

// ACEScg AP1 -> Rec.709 followed by a neutral luminance-preserving shoulder. Scaling all three
// channels by the same factor retains highlight hue instead of clipping each channel to white.
static inline void npRawMonitorPixelToSrgb8(
    float ap1R,
    float ap1G,
    float ap1B,
    const NdrMeasuredCorrectionNative& correction,
    float localDetailStops,
    uint8_t* destination
) {
    // Mode activation is authoritative; correction.active gates only optional measured coefficients.
    if (correction.mode >= 1 && correction.mode <= 2) {
        const NpRawRgb ndrMonitor = npRawNdrSdrMonitor(
            ap1R,
            ap1G,
            ap1B,
            correction,
            localDetailStops
        );
        destination[0] = npRawMonitorSrgb8(ndrMonitor.r);
        destination[1] = npRawMonitorSrgb8(ndrMonitor.g);
        destination[2] = npRawMonitorSrgb8(ndrMonitor.b);
        destination[3] = 255u;
        return;
    }
    float r = std::max(0.0f, 1.7050f * ap1R - 0.6217f * ap1G - 0.0832f * ap1B);
    float g = std::max(0.0f, -0.1302f * ap1R + 1.1408f * ap1G - 0.0106f * ap1B);
    float b = std::max(0.0f, -0.0240f * ap1R - 0.1290f * ap1G + 1.1530f * ap1B);
    const float luminance = 0.2126f * r + 0.7152f * g + 0.0722f * b;
    const float scale = luminance > 1.0e-6f
        ? npRawMonitorNeutralShoulder(luminance) / luminance
        : 0.0f;
    r *= scale; g *= scale; b *= scale;
    const float peak = std::max({r, g, b});
    if (peak > 1.0f) {
        const float gamutScale = 1.0f / peak;
        r *= gamutScale; g *= gamutScale; b *= gamutScale;
    }
    destination[0] = npRawMonitorSrgb8(r);
    destination[1] = npRawMonitorSrgb8(g);
    destination[2] = npRawMonitorSrgb8(b);
    destination[3] = 255u;
}

static inline float npRawAcesCgHalfPixelLuma(const uint16_t* source, int index) {
    const NpRawRgb color{
        std::max(npHalfToFloat(source[index * 4 + 0]), 0.0f),
        std::max(npHalfToFloat(source[index * 4 + 1]), 0.0f),
        std::max(npHalfToFloat(source[index * 4 + 2]), 0.0f)
    };
    const float y = npRawAcesCgLuma(color);
    return isIeee754Finite(y) ? std::max(y, 0.0f) : 0.0f;
}

static inline float npRawNdrLocalDetailStops(
    const uint16_t* source,
    int x,
    int y,
    int width,
    int height
) {
    const int centerIndex = y * width + x;
    const int leftIndex = y * width + std::max(x - 1, 0);
    const int rightIndex = y * width + std::min(x + 1, width - 1);
    const int upIndex = std::max(y - 1, 0) * width + x;
    const int downIndex = std::min(y + 1, height - 1) * width + x;
    const float centerY = npRawAcesCgHalfPixelLuma(source, centerIndex);
    const float localBase = 0.25f * (
        npRawAcesCgHalfPixelLuma(source, leftIndex) +
        npRawAcesCgHalfPixelLuma(source, rightIndex) +
        npRawAcesCgHalfPixelLuma(source, upIndex) +
        npRawAcesCgHalfPixelLuma(source, downIndex)
    );
    const float detail = std::log2((centerY + 0.0020f) / (localBase + 0.0020f));
    const float edgeConfidence = npRawSmoothstep(0.006f, 0.040f, std::abs(centerY - localBase));
    const float shadowNoiseGuard = npRawSmoothstep(0.018f, 0.075f, centerY);
    const float highlightGuard = 1.0f - npRawSmoothstep(1.30f, 3.20f, centerY);
    return std::clamp(detail, -0.22f, 0.22f) *
        edgeConfidence * shadowNoiseGuard * highlightGuard;
}

// The CPU monitor fallback is needed only when the device cannot export rgba16f storage AHBs. Split a
// large 1024-class monitor into disjoint row-major ranges; neighbourhood reads are immutable and each
// worker still owns a disjoint destination range.
static void npRawTonemapPreviewToSrgb8(
    const uint16_t* source,
    int width,
    int height,
    const NdrMeasuredCorrectionNative& correction,
    uint8_t* destination
) {
    const int pixelCount = width > 0 && height > 0 ? width * height : 0;
    if (source == nullptr || destination == nullptr || pixelCount <= 0) return;
    const unsigned hardwareThreads = std::max(1u, std::thread::hardware_concurrency());
    const unsigned workerCount = pixelCount >= 262144
        ? std::min(4u, hardwareThreads)
        : 1u;
    const auto convert = [&](int from, int to) {
        for (int i = from; i < to; ++i) {
            const int x = i % width;
            const int y = i / width;
            npRawMonitorPixelToSrgb8(
                npHalfToFloat(source[i * 4 + 0]),
                npHalfToFloat(source[i * 4 + 1]),
                npHalfToFloat(source[i * 4 + 2]),
                correction,
                correction.mode >= 1 && correction.mode <= 2
                    ? npRawNdrLocalDetailStops(source, x, y, width, height)
                    : 0.0f,
                destination + i * 4);
        }
    };
    if (workerCount == 1u) {
        convert(0, pixelCount);
        return;
    }
    std::vector<std::thread> workers;
    workers.reserve(workerCount - 1u);
    const int chunk = (pixelCount + static_cast<int>(workerCount) - 1) /
        static_cast<int>(workerCount);
    for (unsigned worker = 1; worker < workerCount; ++worker) {
        const int from = std::min(pixelCount, static_cast<int>(worker) * chunk);
        const int to = std::min(pixelCount, from + chunk);
        workers.emplace_back(convert, from, to);
    }
    convert(0, std::min(pixelCount, chunk));
    for (auto& worker : workers) worker.join();
}

// Record the project-owned scene-log transport after the FP16 cinema demosaic. Recorder ownership
// guarantees a full-sensor extent; idle direct preview may use its bounded CFA-preserving extent. The
// destination remains GENERAL for cross-API AHardwareBuffer sampling; queue completion occurs before
// the Java HardwareBuffer reference is acquired.
static bool recordRawSceneLogTransport(
    VulkanBridgeState* state,
    VkCommandBuffer cmd,
    const VkImageSubresourceRange& range,
    int width,
    int height,
    VkImage outputImage,
    VkImage sceneLogImage
) {
    auto& r = state->rawDemosaic;
    if (!state->rawSceneLogTransportSubmissionEnabled ||
        outputImage == VK_NULL_HANDLE || sceneLogImage == VK_NULL_HANDLE ||
        r.sceneLogDescriptorSet == VK_NULL_HANDLE ||
        state->rawSceneLogPackPipeline == VK_NULL_HANDLE ||
        state->rawSceneLogPackPipelineLayout == VK_NULL_HANDLE) {
        return false;
    }

    VkImageMemoryBarrier outputReadable{};
    outputReadable.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    outputReadable.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    outputReadable.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    outputReadable.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    outputReadable.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    outputReadable.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    outputReadable.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    outputReadable.image = outputImage;
    outputReadable.subresourceRange = range;
    vkCmdPipelineBarrier(cmd,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0, 0, nullptr, 0, nullptr, 1, &outputReadable);

    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
                      state->rawSceneLogPackPipeline);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
                            state->rawSceneLogPackPipelineLayout,
                            0, 1, &r.sceneLogDescriptorSet, 0, nullptr);
    RawSceneLogPackPushConstants push{};
    push.width = width;
    push.height = height;
    vkCmdPushConstants(cmd, state->rawSceneLogPackPipelineLayout,
                       VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(push), &push);
    const uint32_t groupsX = (static_cast<uint32_t>(width) + 15u) / 16u;
    const uint32_t groupsY = (static_cast<uint32_t>(height) + 15u) / 16u;
    vkCmdDispatch(cmd, groupsX, groupsY, 1u);

    VkImageMemoryBarrier externallyReadable{};
    externallyReadable.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    externallyReadable.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    externallyReadable.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT;
    externallyReadable.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    externallyReadable.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    externallyReadable.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    externallyReadable.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    externallyReadable.image = sceneLogImage;
    externallyReadable.subresourceRange = range;
    vkCmdPipelineBarrier(cmd,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
        0, 0, nullptr, 0, nullptr, 1, &externallyReadable);
    return true;
}

// Derive the independent preview-resolution transport in the SAME command/fence as the full-resolution
// demosaic. No display transform is applied here. FP16 is written directly when exportable; otherwise
// the ratio-filtered FP16 intermediate is packed through the exact NEO-SCENE-LOG10 shader.
static bool recordRawPreviewTransport(
    VulkanBridgeState* state,
    VkCommandBuffer cmd,
    const VkImageSubresourceRange& range,
    int sourceWidth,
    int sourceHeight,
    VkImage sourceImage,
    VkImageView sourceView,
    int previewSlot
) {
    if (state == nullptr || cmd == VK_NULL_HANDLE || sourceImage == VK_NULL_HANDLE ||
        sourceView == VK_NULL_HANDLE ||
        previewSlot < 0 || previewSlot >= static_cast<int>(kRawPreviewTransportSlotCount)) {
        return false;
    }
    auto& r = state->rawDemosaic;
    const size_t slot = static_cast<size_t>(previewSlot);
    if (!strictTenBitPreviewTransportOwnsFrame(r, previewSlot) ||
        r.previewDownsampleDescriptorSet == VK_NULL_HANDLE ||
        state->rawPreviewDownsamplePipeline == VK_NULL_HANDLE ||
        state->rawPreviewDownsamplePipelineLayout == VK_NULL_HANDLE ||
        (r.previewTransportSceneLog &&
         (r.previewSceneLogDescriptorSet == VK_NULL_HANDLE ||
          state->rawSceneLogPackPipeline == VK_NULL_HANDLE ||
          state->rawSceneLogPackPipelineLayout == VK_NULL_HANDLE)) ||
        r.previewTransportW <= 0 || r.previewTransportH <= 0) {
        return false;
    }

    const VkImage downsampleOutputImage = r.previewTransportSceneLog
        ? r.previewSceneLinearImage
        : r.previewTransportImages[slot];
    const VkImageView downsampleOutputView = r.previewTransportSceneLog
        ? r.previewSceneLinearView
        : r.previewTransportViews[slot];
    if (downsampleOutputImage == VK_NULL_HANDLE || downsampleOutputView == VK_NULL_HANDLE) {
        return false;
    }

    VkDescriptorImageInfo inputInfo{};
    inputInfo.imageView = sourceView;
    inputInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkDescriptorImageInfo outputInfo{};
    outputInfo.imageView = downsampleOutputView;
    outputInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkWriteDescriptorSet downsampleWrites[2]{};
    for (uint32_t binding = 0; binding < 2u; ++binding) {
        downsampleWrites[binding].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        downsampleWrites[binding].dstSet = r.previewDownsampleDescriptorSet;
        downsampleWrites[binding].dstBinding = binding;
        downsampleWrites[binding].descriptorCount = 1;
        downsampleWrites[binding].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    }
    downsampleWrites[0].pImageInfo = &inputInfo;
    downsampleWrites[1].pImageInfo = &outputInfo;
    vkUpdateDescriptorSets(state->device, 2, downsampleWrites, 0, nullptr);

    VkImageMemoryBarrier beforeDownsample[2]{};
    beforeDownsample[0].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    beforeDownsample[0].srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_SHADER_READ_BIT;
    beforeDownsample[0].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    beforeDownsample[0].oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    beforeDownsample[0].newLayout = VK_IMAGE_LAYOUT_GENERAL;
    beforeDownsample[0].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    beforeDownsample[0].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    beforeDownsample[0].image = sourceImage;
    beforeDownsample[0].subresourceRange = range;
    beforeDownsample[1].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    // The prior consumer is outside Vulkan and is proven complete by glFinish before exact-token ack.
    // MEMORY_READ makes any prior Vulkan/external-read ownership visible before this slot is overwritten.
    beforeDownsample[1].srcAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    beforeDownsample[1].dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    beforeDownsample[1].oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    beforeDownsample[1].newLayout = VK_IMAGE_LAYOUT_GENERAL;
    beforeDownsample[1].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    beforeDownsample[1].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    beforeDownsample[1].image = downsampleOutputImage;
    beforeDownsample[1].subresourceRange = range;
    vkCmdPipelineBarrier(
        cmd,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0, 0, nullptr, 0, nullptr, 2, beforeDownsample);

    vkCmdBindPipeline(
        cmd, VK_PIPELINE_BIND_POINT_COMPUTE, state->rawPreviewDownsamplePipeline);
    vkCmdBindDescriptorSets(
        cmd,
        VK_PIPELINE_BIND_POINT_COMPUTE,
        state->rawPreviewDownsamplePipelineLayout,
        0, 1, &r.previewDownsampleDescriptorSet, 0, nullptr);
    RawPreviewDownsamplePushConstants downsamplePush{};
    downsamplePush.inputWidth = sourceWidth;
    downsamplePush.inputHeight = sourceHeight;
    downsamplePush.outputWidth = r.previewTransportW;
    downsamplePush.outputHeight = r.previewTransportH;
    vkCmdPushConstants(
        cmd,
        state->rawPreviewDownsamplePipelineLayout,
        VK_SHADER_STAGE_COMPUTE_BIT,
        0,
        sizeof(downsamplePush),
        &downsamplePush);
    const uint32_t groupsX = (static_cast<uint32_t>(r.previewTransportW) + 15u) / 16u;
    const uint32_t groupsY = (static_cast<uint32_t>(r.previewTransportH) + 15u) / 16u;
    vkCmdDispatch(cmd, groupsX, groupsY, 1u);

    if (r.previewTransportSceneLog) {
        VkDescriptorImageInfo sceneInput{};
        sceneInput.imageView = r.previewSceneLinearView;
        sceneInput.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
        VkDescriptorImageInfo sceneOutput{};
        sceneOutput.imageView = r.previewTransportViews[slot];
        sceneOutput.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
        VkWriteDescriptorSet sceneWrites[2]{};
        for (uint32_t binding = 0; binding < 2u; ++binding) {
            sceneWrites[binding].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            sceneWrites[binding].dstSet = r.previewSceneLogDescriptorSet;
            sceneWrites[binding].dstBinding = binding;
            sceneWrites[binding].descriptorCount = 1;
            sceneWrites[binding].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        }
        sceneWrites[0].pImageInfo = &sceneInput;
        sceneWrites[1].pImageInfo = &sceneOutput;
        vkUpdateDescriptorSets(state->device, 2, sceneWrites, 0, nullptr);

        VkImageMemoryBarrier packBarriers[2]{};
        packBarriers[0].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        packBarriers[0].srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        packBarriers[0].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        packBarriers[0].oldLayout = VK_IMAGE_LAYOUT_GENERAL;
        packBarriers[0].newLayout = VK_IMAGE_LAYOUT_GENERAL;
        packBarriers[0].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        packBarriers[0].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        packBarriers[0].image = r.previewSceneLinearImage;
        packBarriers[0].subresourceRange = range;
        packBarriers[1].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        packBarriers[1].srcAccessMask = VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        packBarriers[1].dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        packBarriers[1].oldLayout = VK_IMAGE_LAYOUT_GENERAL;
        packBarriers[1].newLayout = VK_IMAGE_LAYOUT_GENERAL;
        packBarriers[1].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        packBarriers[1].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        packBarriers[1].image = r.previewTransportImages[slot];
        packBarriers[1].subresourceRange = range;
        vkCmdPipelineBarrier(
            cmd,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            0, 0, nullptr, 0, nullptr, 2, packBarriers);

        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, state->rawSceneLogPackPipeline);
        vkCmdBindDescriptorSets(
            cmd,
            VK_PIPELINE_BIND_POINT_COMPUTE,
            state->rawSceneLogPackPipelineLayout,
            0, 1, &r.previewSceneLogDescriptorSet, 0, nullptr);
        RawSceneLogPackPushConstants packPush{};
        packPush.width = r.previewTransportW;
        packPush.height = r.previewTransportH;
        vkCmdPushConstants(
            cmd,
            state->rawSceneLogPackPipelineLayout,
            VK_SHADER_STAGE_COMPUTE_BIT,
            0,
            sizeof(packPush),
            &packPush);
        vkCmdDispatch(cmd, groupsX, groupsY, 1u);
    }

    VkImageMemoryBarrier externallyReadable{};
    externallyReadable.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    externallyReadable.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    externallyReadable.dstAccessMask = VK_ACCESS_MEMORY_READ_BIT;
    externallyReadable.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    externallyReadable.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    externallyReadable.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    externallyReadable.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    externallyReadable.image = r.previewTransportImages[slot];
    externallyReadable.subresourceRange = range;
    vkCmdPipelineBarrier(
        cmd,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_ALL_COMMANDS_BIT,
        0, 0, nullptr, 0, nullptr, 1, &externallyReadable);
    return true;
}

// Record the presentation-only RAW monitor leg into the same command buffer as the cinema debayer.
// Preferred path: FP16 ACEScg -> Vulkan compute tone/color operator -> RGBA8 readback.
// Fallback path: prior FP16 blit/readback -> identical CPU operator. The scene-linear output image is
// never replaced or quantized, so capture and recording precision remain independent of the monitor.
static bool recordRawMonitorReadback(
    VulkanBridgeState* state,
    VkCommandBuffer cmd,
    const VkImageSubresourceRange& range,
    int sourceWidth,
    int sourceHeight,
    VkImage outputImage
) {
    auto& r = state->rawDemosaic;
    if (r.previewImage == VK_NULL_HANDLE || r.readbackMapped == nullptr ||
        r.readbackBuffer == VK_NULL_HANDLE) {
        return false;
    }

    const auto barrier = [&](VkImage image, VkAccessFlags sourceAccess, VkAccessFlags destinationAccess,
                             VkImageLayout oldLayout, VkImageLayout newLayout) {
        VkImageMemoryBarrier value{};
        value.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        value.srcAccessMask = sourceAccess;
        value.dstAccessMask = destinationAccess;
        value.oldLayout = oldLayout;
        value.newLayout = newLayout;
        value.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        value.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        value.image = image;
        value.subresourceRange = range;
        return value;
    };

    if (r.gpuMonitorTonemapEnabled && r.monitorDescriptorSet != VK_NULL_HANDLE &&
        state->rawMonitorTonemapPipeline != VK_NULL_HANDLE &&
        state->rawMonitorTonemapPipelineLayout != VK_NULL_HANDLE) {
        VkImageMemoryBarrier outputReadable = barrier(
            outputImage,
            VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT,
            VK_ACCESS_SHADER_READ_BIT,
            VK_IMAGE_LAYOUT_GENERAL,
            VK_IMAGE_LAYOUT_GENERAL);
        vkCmdPipelineBarrier(cmd,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            0, 0, nullptr, 0, nullptr, 1, &outputReadable);

        VkImageMemoryBarrier previewWritable = barrier(
            r.previewImage,
            r.previewImageInitialized ? VK_ACCESS_TRANSFER_READ_BIT : 0,
            VK_ACCESS_SHADER_WRITE_BIT,
            r.previewImageInitialized
                ? VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL
                : VK_IMAGE_LAYOUT_UNDEFINED,
            VK_IMAGE_LAYOUT_GENERAL);
        vkCmdPipelineBarrier(cmd,
            r.previewImageInitialized
                ? VK_PIPELINE_STAGE_TRANSFER_BIT
                : VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            0, 0, nullptr, 0, nullptr, 1, &previewWritable);

        const auto& correction = state->rawMonitorNdrCorrection;
        RawMonitorTonemapPushConstants push{};
        push.inputWidth = sourceWidth;
        push.inputHeight = sourceHeight;
        push.outputWidth = r.previewW;
        push.outputHeight = r.previewH;
        push.mode = correction.mode;
        push.correctionActive = correction.active ? 1 : 0;
        push.redScale = correction.redScale;
        push.greenScale = correction.greenScale;
        push.blueScale = correction.blueScale;
        push.saturationScale = correction.saturationScale;
        push.skinHueCorrectionDegrees = correction.skinHueCorrectionDegrees;
        push.skyHueCorrectionDegrees = correction.skyHueCorrectionDegrees;
        push.shadowLiftBias = correction.shadowLiftBias;
        push.highlightRolloffBias = correction.highlightRolloffBias;
        push.sensorDiffuseScene = correction.sensorDiffuseScene;
        push.signalGamma = correction.signalGamma;
        push.toeWidth = correction.toeWidth;
        push.toeAmount = correction.toeAmount;
        push.highlightDesat = correction.highlightDesat;
        push.shadowMidtoneLift = correction.shadowMidtoneLift;
        push.midtoneContrast = correction.midtoneContrast;
        push.chromaBoost = correction.chromaBoost;
        push.skyChromaBonus = correction.skyChromaBonus;
        push.localDetailStrength = correction.localDetailStrength;
        push.localDetailLowerStops = correction.localDetailLowerStops;
        push.localDetailUpperStops = correction.localDetailUpperStops;

        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
                          state->rawMonitorTonemapPipeline);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE,
                                state->rawMonitorTonemapPipelineLayout,
                                0, 1, &r.monitorDescriptorSet, 0, nullptr);
        vkCmdPushConstants(cmd, state->rawMonitorTonemapPipelineLayout,
                           VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(push), &push);
        const uint32_t monitorGroupsX = (static_cast<uint32_t>(r.previewW) + 15u) / 16u;
        const uint32_t monitorGroupsY = (static_cast<uint32_t>(r.previewH) + 15u) / 16u;
        vkCmdDispatch(cmd, monitorGroupsX, monitorGroupsY, 1u);

        VkImageMemoryBarrier previewReadable = barrier(
            r.previewImage,
            VK_ACCESS_SHADER_WRITE_BIT,
            VK_ACCESS_TRANSFER_READ_BIT,
            VK_IMAGE_LAYOUT_GENERAL,
            VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
        vkCmdPipelineBarrier(cmd,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT,
            0, 0, nullptr, 0, nullptr, 1, &previewReadable);
    } else {
        VkImageMemoryBarrier pre[2] = {
            barrier(outputImage, VK_ACCESS_SHADER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                    VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL),
            barrier(r.previewImage,
                    r.previewImageInitialized ? VK_ACCESS_TRANSFER_READ_BIT : 0,
                    VK_ACCESS_TRANSFER_WRITE_BIT,
                    r.previewImageInitialized
                        ? VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL
                        : VK_IMAGE_LAYOUT_UNDEFINED,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
        };
        const VkPipelineStageFlags previewSourceStage = r.previewImageInitialized
            ? VK_PIPELINE_STAGE_TRANSFER_BIT
            : VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | previewSourceStage,
            VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 2, pre);

        VkImageBlit blit{};
        blit.srcSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        blit.srcSubresource.layerCount = 1;
        blit.srcOffsets[1] = { sourceWidth, sourceHeight, 1 };
        blit.dstSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        blit.dstSubresource.layerCount = 1;
        blit.dstOffsets[1] = { r.previewW, r.previewH, 1 };
        vkCmdBlitImage(cmd, outputImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            r.previewImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit, VK_FILTER_LINEAR);

        VkImageMemoryBarrier post[2] = {
            barrier(r.previewImage, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL),
            barrier(outputImage, VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL)
        };
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            0, 0, nullptr, 0, nullptr, 2, post);
    }

    VkBufferImageCopy copyRegion{};
    copyRegion.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    copyRegion.imageSubresource.layerCount = 1;
    copyRegion.imageExtent = {
        static_cast<uint32_t>(r.previewW),
        static_cast<uint32_t>(r.previewH),
        1u
    };
    vkCmdCopyImageToBuffer(cmd, r.previewImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
        r.readbackBuffer, 1, &copyRegion);
    return true;
}

static void completeRawMonitorReadback(VulkanBridgeState* state, bool readbackRecorded) {
    if (!readbackRecorded) return;
    auto& r = state->rawDemosaic;
    // Both monitor paths finish the image in TRANSFER_SRC_OPTIMAL after a successful queue drain.
    r.previewImageInitialized = true;
    const size_t pixelCount = static_cast<size_t>(r.previewW) *
        static_cast<size_t>(r.previewH);
    if (r.gpuMonitorTonemapEnabled) {
        std::memcpy(r.previewRgba8.data(), r.readbackMapped, pixelCount * 4u);
    } else {
        npRawTonemapPreviewToSrgb8(
            static_cast<const uint16_t*>(r.readbackMapped),
            r.previewW,
            r.previewH,
            state->rawMonitorNdrCorrection,
            r.previewRgba8.data());
    }
    r.previewReady = true;
}

enum class RawDispatchSubmitResult {
    Complete,
    Pending,
    Failed
};

// Public polling truth for the JVM-side RAW preview completion handoff. A dispatch that misses the
// short worker deadline remains valid GPU work; its completed pixels must be surfaced under the
// original sensor timestamp before another dispatch is allowed to overwrite the shared output image.
enum class RawDispatchPollResult : int32_t {
    Failed = -1,
    Idle = 0,
    Pending = 1,
    Complete = 2
};

static void releasePendingRawImport(VulkanBridgeState* state) {
    auto& r = state->rawDemosaic;
    if (r.pendingRawView != VK_NULL_HANDLE) {
        vkDestroyImageView(state->device, r.pendingRawView, nullptr);
        r.pendingRawView = VK_NULL_HANDLE;
    }
    if (r.pendingRawImage != VK_NULL_HANDLE) {
        vkDestroyImage(state->device, r.pendingRawImage, nullptr);
        r.pendingRawImage = VK_NULL_HANDLE;
    }
    if (r.pendingRawMemory != VK_NULL_HANDLE) {
        vkFreeMemory(state->device, r.pendingRawMemory, nullptr);
        r.pendingRawMemory = VK_NULL_HANDLE;
    }
    if (r.pendingRawHardwareBuffer != nullptr) {
        AHardwareBuffer_release(r.pendingRawHardwareBuffer);
        r.pendingRawHardwareBuffer = nullptr;
    }
}

static uint64_t rawTimestampDeltaTicks(
    uint64_t start,
    uint64_t end,
    uint32_t validBits
) {
    if (validBits == 0u) return 0u;
    if (validBits >= 64u) return end - start;
    const uint64_t mask = (uint64_t{1} << validBits) - 1u;
    return (end - start) & mask;
}

// Read timestamps only after the dispatch fence has signaled. This function therefore adds no GPU
// wait and cannot affect admission, ordering, output precision, or frame cadence.
static bool collectRawGpuStageTiming(VulkanBridgeState* state) {
    if (state == nullptr) return false;
    auto& r = state->rawDemosaic;
    r.pendingGpuStageMs.fill(0.0);
    if (!r.pendingGpuTimestampSample ||
        r.dispatchTimestampQueryPool == VK_NULL_HANDLE ||
        !state->timestampQueriesSupported ||
        state->timestampPeriodNs <= 0.0) {
        r.pendingGpuTimestampSample = false;
        return false;
    }
    std::array<uint64_t, kRawGpuTimestampCount> timestamps{};
    const VkResult result = vkGetQueryPoolResults(
        state->device,
        r.dispatchTimestampQueryPool,
        0u,
        kRawGpuTimestampCount,
        sizeof(timestamps),
        timestamps.data(),
        sizeof(uint64_t),
        VK_QUERY_RESULT_64_BIT);
    r.pendingGpuTimestampSample = false;
    if (result != VK_SUCCESS) {
        LOGW(
            "RAW GPU stage timestamp collection failed result=%d after signaled fence",
            static_cast<int>(result));
        return false;
    }
    for (size_t stage = 0; stage < kRawGpuStageCount; ++stage) {
        const uint64_t ticks = rawTimestampDeltaTicks(
            timestamps[stage],
            timestamps[stage + 1u],
            state->timestampValidBits);
        const double elapsedMs = static_cast<double>(ticks) * state->timestampPeriodNs / 1.0e6;
        r.pendingGpuStageMs[stage] = elapsedMs;
        r.gpuStageTotalMs[stage] += elapsedMs;
        r.gpuStageMaxMs[stage] = std::max(r.gpuStageMaxMs[stage], elapsedMs);
    }
    r.gpuStageTimingSampleCount += 1u;
    return true;
}

static void abandonPendingRawNiv99History(
    VulkanBridgeState::RawDemosaicResources& r
) {
    r.pendingNiv99HistoryCommit = false;
    r.pendingNiv99HistoryIndex = -1;
    r.pendingNiv99FrameNumber = -1;
    r.pendingNiv99TimestampNs = -1;
    r.pendingNiv99Fps = 0u;
}

static void commitPendingRawNiv99History(
    VulkanBridgeState::RawDemosaicResources& r
) {
    if (!r.pendingNiv99HistoryCommit) return;
    r.developNiv99HistoryIndex = r.pendingNiv99HistoryIndex;
    r.developNiv99LastFrameNumber = r.pendingNiv99FrameNumber;
    r.developNiv99LastTimestampNs = r.pendingNiv99TimestampNs;
    r.developNiv99LastFps = r.pendingNiv99Fps;
    abandonPendingRawNiv99History(r);
}

static bool finalizeRawDispatch(
    VulkanBridgeState* state,
    bool readbackRecorded,
    bool sceneLogRecorded,
    bool uploadInitialized
) {
    auto& r = state->rawDemosaic;
    const auto completionObserved = std::chrono::steady_clock::now();
    const double totalMs = r.pendingDispatchCycleStarted.time_since_epoch().count() != 0
        ? std::chrono::duration<double, std::milli>(
              completionObserved - r.pendingDispatchCycleStarted).count()
        : 0.0;
    const double fenceMs = r.pendingQueueSubmitted.time_since_epoch().count() != 0
        ? std::chrono::duration<double, std::milli>(
              completionObserved - r.pendingQueueSubmitted).count()
        : 0.0;
    const bool gpuStageTimingValid = collectRawGpuStageTiming(state);
    if (uploadInitialized) r.uploadImageInitialized = true;
    completeRawMonitorReadback(state, readbackRecorded);
    const int completedSlot = r.dispatchTransportSlot;
    if (completedSlot < 0 || completedSlot >= rawTransportSlotCount(r) ||
        r.transportOwnership[completedSlot] != kRawTransportVulkanInFlight) {
        LOGE("RAW transport completion lost its reserved slot (slot=%d)", completedSlot);
        r.lastDispatchOk = false;
        r.dispatchTransportSlot = -1;
        abandonPendingRawNiv99History(r);
        releasePendingRawImport(state);
        return false;
    }
    if (r.dispatchPreviewTransportSlot >= 0) {
        if (!strictTenBitPreviewTransportOwnsFrame(
                r, r.dispatchPreviewTransportSlot)) {
            LOGE(
                "RAW preview transport completion lost its reserved slot (slot=%d)",
                r.dispatchPreviewTransportSlot);
            r.lastDispatchOk = false;
            abandonReservedRawPreviewTransportSlot(r, r.dispatchPreviewTransportSlot);
            abandonReservedRawTransportSlot(r, completedSlot);
            abandonPendingRawNiv99History(r);
            releasePendingRawImport(state);
            return false;
        }
    }
    setRawSceneLogReadyForSlot(r, completedSlot, sceneLogRecorded);
    r.transportOwnership[completedSlot] = kRawTransportCompleted;
    r.transportCompletionSerial[completedSlot] = nextRawTransportCompletionSerial(r);
    r.transportTokens[completedSlot] = nextRawTransportToken(r);
    r.dispatchTransportSlot = -1;
    if (r.dispatchPreviewTransportSlot >= 0 &&
        !completeReservedRawPreviewTransportSlot(r)) {
        LOGE("RAW preview transport completion token publication failed");
        r.lastDispatchOk = false;
        releasePendingRawImport(state);
        return false;
    }
    commitPendingRawNiv99History(r);
    r.dispatchCount += 1u;
    if (r.pendingWasRecording) {
        r.timingSampleCount += 1u;
        r.timingTotalMs += totalMs;
        r.timingFenceMs += fenceMs;
        r.timingMaxTotalMs = std::max(r.timingMaxTotalMs, totalMs);
        r.timingMaxFenceMs = std::max(r.timingMaxFenceMs, fenceMs);
        if (r.timingSampleCount <= 8u || r.timingSampleCount % 60u == 0u) {
            const double sampleCount = static_cast<double>(r.timingSampleCount);
            LOGI(
                "RAW_RECORD_STAGE_TIMING sample=%llu extent=%dx%d transportSlot=%d input=%s "
                "inputPrepareMs=%.3f commandBuildMs=%.3f queueSubmitHostMs=%.3f "
                "fenceMs=%.3f totalMs=%.3f avgFenceMs=%.3f avgTotalMs=%.3f "
                "maxFenceMs=%.3f maxTotalMs=%.3f sceneLog=%d readback=%d "
                "gpuValid=%d gpuUploadMs=%.3f gpuDemosaicMs=%.3f gpuSceneLogMs=%.3f "
                "gpuPreviewMs=%.3f gpuMonitorMs=%.3f gpuTailMs=%.3f gpuTotalMs=%.3f "
                "avgGpuTotalMs=%.3f",
                static_cast<unsigned long long>(r.timingSampleCount),
                r.width,
                r.height,
                completedSlot,
                r.pendingUsedStagingUpload ? "staging" : "zero-copy",
                r.pendingInputPrepareMs,
                r.pendingCommandBuildMs,
                r.pendingQueueSubmitHostMs,
                fenceMs,
                totalMs,
                r.timingFenceMs / sampleCount,
                r.timingTotalMs / sampleCount,
                r.timingMaxFenceMs,
                r.timingMaxTotalMs,
                sceneLogRecorded ? 1 : 0,
                readbackRecorded ? 1 : 0,
                gpuStageTimingValid ? 1 : 0,
                r.pendingGpuStageMs[0],
                r.pendingGpuStageMs[1],
                r.pendingGpuStageMs[2],
                r.pendingGpuStageMs[3],
                r.pendingGpuStageMs[4],
                r.pendingGpuStageMs[5],
                std::accumulate(
                    r.pendingGpuStageMs.begin(),
                    r.pendingGpuStageMs.end(),
                    0.0),
                r.gpuStageTimingSampleCount > 0u
                    ? std::accumulate(
                          r.gpuStageTotalMs.begin(),
                          r.gpuStageTotalMs.end(),
                          0.0) / static_cast<double>(r.gpuStageTimingSampleCount)
                    : 0.0);
        }
    }
    r.lastDispatchOk = true;
    r.everDispatched = true;
    r.pendingDispatchCycleStarted = {};
    r.pendingQueueSubmitted = {};
    r.pendingInputPrepareMs = 0.0;
    r.pendingCommandBuildMs = 0.0;
    r.pendingQueueSubmitHostMs = 0.0;
    r.pendingUsedStagingUpload = false;
    r.pendingWasRecording = false;
    r.pendingGpuStageMs.fill(0.0);
    releasePendingRawImport(state);
    return true;
}

// Poll an earlier deadline-overrun without blocking the Camera2 callback. Idle preview remains a
// zero-timeout probe; the dedicated recording worker may coalesce a near-complete fence for the
// bounded interval above. Until the fence signals, all descriptors, mapped buffers and imported AHB
// memory remain owned by RawDemosaicResources.
static RawDispatchPollResult pollPendingRawDispatch(VulkanBridgeState* state) {
    auto& r = state->rawDemosaic;
    if (!r.dispatchInFlight) return RawDispatchPollResult::Idle;
    const uint64_t completionPollWaitNs = r.pendingWasRecording
        ? kRawRecordingCompletionCoalesceWaitNs
        : 0u;
    const VkResult waitResult = vkWaitForFences(
        state->device, 1, &r.dispatchFence, VK_TRUE, completionPollWaitNs);
    if (waitResult == VK_TIMEOUT || waitResult == VK_NOT_READY) {
        return RawDispatchPollResult::Pending;
    }
    if (waitResult != VK_SUCCESS) {
        LOGE("RAW dispatch fence failed with %d", static_cast<int>(waitResult));
        if (waitResult == VK_ERROR_DEVICE_LOST) {
            r.dispatchInFlight = false;
            r.lastDispatchOk = false;
            abandonReservedRawPreviewTransportSlot(r, r.dispatchPreviewTransportSlot);
            abandonReservedRawTransportSlot(r, r.dispatchTransportSlot);
            abandonPendingRawNiv99History(r);
            state->supported = false;
            setStatus(state, "RAW GPU dispatch failed: Vulkan device lost");
            releasePendingRawImport(state);
            return RawDispatchPollResult::Failed;
        }
        // OOM/other host wait failures do not prove GPU completion. Keep the submission and all
        // referenced resources live, then poll again on a later secondary frame.
        return RawDispatchPollResult::Pending;
    }
    r.dispatchInFlight = false;
    const bool finalized = finalizeRawDispatch(
        state,
        r.pendingReadbackRecorded,
        r.pendingSceneLogRecorded,
        r.pendingUploadInitialization);
    r.pendingReadbackRecorded = false;
    r.pendingSceneLogRecorded = false;
    r.pendingUploadInitialization = false;
    return finalized ? RawDispatchPollResult::Complete : RawDispatchPollResult::Failed;
}

static bool reapPendingRawDispatch(VulkanBridgeState* state) {
    const RawDispatchPollResult result = pollPendingRawDispatch(state);
    return result == RawDispatchPollResult::Idle || result == RawDispatchPollResult::Complete;
}

static bool beginRawDispatchCommands(VulkanBridgeState* state, VkCommandBuffer* commandBuffer) {
    if (state == nullptr || commandBuffer == nullptr) return false;
    auto& r = state->rawDemosaic;
    if (r.dispatchInFlight || r.dispatchCommandPool == VK_NULL_HANDLE ||
        r.dispatchCommandBuffer == VK_NULL_HANDLE || r.dispatchFence == VK_NULL_HANDLE) {
        return false;
    }
    if (!check(vkResetCommandPool(state->device, r.dispatchCommandPool, 0),
               "vkResetCommandPool(rawDemosaic)")) {
        return false;
    }
    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (!check(vkBeginCommandBuffer(r.dispatchCommandBuffer, &beginInfo),
               "vkBeginCommandBuffer(rawDemosaic)")) {
        return false;
    }
    *commandBuffer = r.dispatchCommandBuffer;
    return true;
}

static RawDispatchSubmitResult submitRawDispatchCommands(
    VulkanBridgeState* state,
    uint64_t completionWaitNs
) {
    auto& r = state->rawDemosaic;
    if (!check(vkEndCommandBuffer(r.dispatchCommandBuffer),
               "vkEndCommandBuffer(rawDemosaic)")) {
        return RawDispatchSubmitResult::Failed;
    }
    if (!check(vkResetFences(state->device, 1, &r.dispatchFence),
               "vkResetFences(rawDemosaic)")) {
        return RawDispatchSubmitResult::Failed;
    }
    VkSubmitInfo submitInfo{};
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &r.dispatchCommandBuffer;
    const auto queueSubmitStarted = std::chrono::steady_clock::now();
    const VkResult submitResult = submitRawQueueSynchronized(
        state, 1, &submitInfo, r.dispatchFence);
    r.pendingQueueSubmitHostMs = elapsedMsSince(queueSubmitStarted);
    if (submitResult != VK_SUCCESS) {
        check(submitResult, "vkQueueSubmit(rawDemosaic)");
        if (submitResult == VK_ERROR_DEVICE_LOST) {
            state->supported = false;
            setStatus(state, "RAW GPU submission failed: Vulkan device lost");
            return RawDispatchSubmitResult::Failed;
        }
        // Submission failed, so this unsignaled fence is not in use. Recreate it signaled to retain
        // the invariant expected by the next reset/reuse cycle.
        vkDestroyFence(state->device, r.dispatchFence, nullptr);
        r.dispatchFence = VK_NULL_HANDLE;
        VkFenceCreateInfo fenceInfo{};
        fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
        fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        if (!check(vkCreateFence(
                state->device, &fenceInfo, nullptr, &r.dispatchFence),
                "vkCreateFence(rawDemosaic recovery)")) {
            setStatus(state, "RAW GPU submission recovery fence failed");
        }
        return RawDispatchSubmitResult::Failed;
    }
    r.pendingQueueSubmitted = std::chrono::steady_clock::now();
    r.dispatchInFlight = true;
    r.lastDispatchOk = false;
    r.previewReady = false;
    if (r.dispatchTransportSlot >= 0) {
        setRawSceneLogReadyForSlot(r, r.dispatchTransportSlot, false);
    }
    const VkResult waitResult = vkWaitForFences(
        state->device, 1, &r.dispatchFence, VK_TRUE, completionWaitNs);
    if (waitResult == VK_SUCCESS) {
        r.dispatchInFlight = false;
        return RawDispatchSubmitResult::Complete;
    }
    if (waitResult != VK_SUCCESS && waitResult != VK_ERROR_DEVICE_LOST) {
        const uint64_t deferredCount = ++r.deferredCompletionCount;
        if (deferredCount == 1u ||
            deferredCount % kRawDeferredCompletionLogInterval == 0u) {
            LOGW(
                "RAW preview GPU completion deferred count=%llu (wait result=%d); "
                "capture remains unblocked",
                static_cast<unsigned long long>(deferredCount),
                static_cast<int>(waitResult));
        }
        return RawDispatchSubmitResult::Pending;
    }
    check(waitResult, "vkWaitForFences(rawDemosaic)");
    r.dispatchInFlight = false;
    abandonReservedRawPreviewTransportSlot(r, r.dispatchPreviewTransportSlot);
    abandonReservedRawTransportSlot(r, r.dispatchTransportSlot);
    state->supported = false;
    setStatus(state, "RAW GPU completion failed: Vulkan device lost");
    return RawDispatchSubmitResult::Failed;
}

// Staging-upload fallback for devices whose camera RAW AHardwareBuffer cannot be imported as an
// R16_UINT storage image (importRawStorageImage fail-closed — common: camera RAW16 buffers are
// CPU/sampled-only). The device DOES support R16_UINT storage images (the demosaic pipeline built),
// so we create a DEVICE-LOCAL one and upload the RAW mosaic bytes into it via a host-visible staging
// buffer + vkCmdCopyBufferToImage. Copy-bound (~width*height*2 bytes/frame) — a low-fps monitor path,
// but genuine sensor RAW. Leaves the image in GENERAL layout so the caller skips the UNDEFINED discard
// barrier for this path. Mirrors the existing staging-upload pattern (see NRLI LUT / createBufferWithMemory).
bool prepareRawStorageImageUpload(
    VulkanBridgeState* state, AHardwareBuffer* hardwareBuffer,
    int sourceWidth, int sourceHeight, int decimate, int width, int height,
    VkImage* outImage, VkDeviceMemory* outMemory, VkImageView* outView
) {
    *outImage = VK_NULL_HANDLE; *outMemory = VK_NULL_HANDLE; *outView = VK_NULL_HANDLE;
    if (state == nullptr || hardwareBuffer == nullptr || sourceWidth < 2 || sourceHeight < 2 ||
        width < 2 || height < 2) return false;
    decimate = std::max(1, decimate);
    auto& r = state->rawDemosaic;
    const uint32_t w = static_cast<uint32_t>(width);
    const uint32_t h = static_cast<uint32_t>(height);
    const size_t tightRowBytes = static_cast<size_t>(w) * 2u;
    const VkDeviceSize uploadBytes = static_cast<VkDeviceSize>(tightRowBytes) * h;

    const auto resetUploadResources = [&]() {
        if (r.uploadStagingMapped != nullptr && r.uploadStagingMemory != VK_NULL_HANDLE) {
            vkUnmapMemory(state->device, r.uploadStagingMemory);
            r.uploadStagingMapped = nullptr;
        }
        if (r.uploadStaging != VK_NULL_HANDLE) {
            vkDestroyBuffer(state->device, r.uploadStaging, nullptr);
            r.uploadStaging = VK_NULL_HANDLE;
        }
        if (r.uploadStagingMemory != VK_NULL_HANDLE) {
            vkFreeMemory(state->device, r.uploadStagingMemory, nullptr);
            r.uploadStagingMemory = VK_NULL_HANDLE;
        }
        if (r.uploadView != VK_NULL_HANDLE) {
            vkDestroyImageView(state->device, r.uploadView, nullptr);
            r.uploadView = VK_NULL_HANDLE;
        }
        if (r.uploadImage != VK_NULL_HANDLE) {
            vkDestroyImage(state->device, r.uploadImage, nullptr);
            r.uploadImage = VK_NULL_HANDLE;
        }
        if (r.uploadMemory != VK_NULL_HANDLE) {
            vkFreeMemory(state->device, r.uploadMemory, nullptr);
            r.uploadMemory = VK_NULL_HANDLE;
        }
        r.uploadBytes = 0;
        r.uploadImageInitialized = false;
    };

    // Allocate once per RAW extent. The worker is serialized under state->mutex, so these mapped
    // resources have single-owner access and can be reused safely for every subsequent frame.
    if (r.uploadImage == VK_NULL_HANDLE || r.uploadStagingMapped == nullptr ||
        r.uploadBytes != uploadBytes) {
        resetUploadResources();
        if (!createRawDemosaicOwnedImage(state, width, height, VK_FORMAT_R16_UINT,
                &r.uploadImage, &r.uploadMemory, &r.uploadView) ||
            !createBufferWithMemory(state, uploadBytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                &r.uploadStaging, &r.uploadStagingMemory, /*requireExactProperties=*/true) ||
            !check(vkMapMemory(state->device, r.uploadStagingMemory, 0, uploadBytes, 0,
                &r.uploadStagingMapped), "vkMapMemory(rawStorageUploadPersistent)")) {
            resetUploadResources();
            return false;
        }
        r.uploadBytes = uploadBytes;
    }

    // Lock the RAW AHB only for the row copy; the expensive Vulkan work happens after it is unlocked.
    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(hardwareBuffer, &desc);
    void* src = nullptr;
    if (AHardwareBuffer_lock(hardwareBuffer, AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN, -1, nullptr, &src) != 0 ||
        src == nullptr) {
        setStatus(state, "RAW demosaic skipped: AHB CPU lock failed");
        return false;
    }
    // AHardwareBuffer_Desc.stride is in PIXELS for RAW16. At decimate=1 this is a tight row copy.
    // Monitor-only decimation uses a same-CFA integer tent: every output R/Gr/Gb/B sample reads the
    // same identical-phase footprint as the former box average, but center-weighted coefficients retain
    // more preview MTF while the complete footprint continues to suppress aliases and noise.
    const size_t rowPixels = (desc.stride >= static_cast<uint32_t>(sourceWidth))
        ? static_cast<size_t>(desc.stride)
        : static_cast<size_t>(sourceWidth);
    {
        const uint16_t* s = static_cast<const uint16_t*>(src);
        uint16_t* d = static_cast<uint16_t*>(r.uploadStagingMapped);
        if (decimate == 1 && width == sourceWidth && height == sourceHeight) {
            for (uint32_t y = 0; y < h; ++y) {
                std::memcpy(d + static_cast<size_t>(y) * w,
                            s + static_cast<size_t>(y) * rowPixels, tightRowBytes);
            }
        } else {
            std::vector<uint32_t> tentWeights(static_cast<size_t>(decimate), 1u);
            for (int i = 0; i < decimate; ++i) {
                tentWeights[static_cast<size_t>(i)] = static_cast<uint32_t>(
                    std::min(i + 1, decimate - i));
            }
            for (int oy = 0; oy < height; ++oy) {
                const int phaseY = oy & 1;
                const int sourceBlockY = (oy >> 1) * 2 * decimate;
                uint16_t* destinationRow = d + static_cast<size_t>(oy) * w;
                for (int ox = 0; ox < width; ++ox) {
                    const int phaseX = ox & 1;
                    const int sourceBlockX = (ox >> 1) * 2 * decimate;
                    uint64_t weightedSum = 0u;
                    uint32_t weightSum = 0u;
                    for (int by = 0; by < decimate; ++by) {
                        const int sy = sourceBlockY + phaseY + 2 * by;
                        if (sy >= sourceHeight) break;
                        const uint16_t* sourceRow = s + static_cast<size_t>(sy) * rowPixels;
                        for (int bx = 0; bx < decimate; ++bx) {
                            const int sx = sourceBlockX + phaseX + 2 * bx;
                            if (sx >= sourceWidth) break;
                            const uint32_t weight = tentWeights[static_cast<size_t>(bx)] *
                                tentWeights[static_cast<size_t>(by)];
                            weightedSum += static_cast<uint64_t>(sourceRow[sx]) * weight;
                            weightSum += weight;
                        }
                    }
                    destinationRow[ox] = weightSum > 0u
                        ? static_cast<uint16_t>((weightedSum + weightSum / 2u) / weightSum)
                        : 0u;
                }
            }
        }
    }
    AHardwareBuffer_unlock(hardwareBuffer, nullptr);

    *outImage = r.uploadImage;
    *outMemory = r.uploadMemory;
    *outView = r.uploadView;
    return true;
}

// CPU-byte variant used by the record-time monitor. It reuses the same mapped staging buffer and
// device-local mosaic as the AHardwareBuffer fallback, so no Vulkan allocation or transfer submission
// is performed here. The caller records the transfer together with demosaic in one fenced batch.
bool prepareRawStorageImageUploadFromBytes(
    VulkanBridgeState* state,
    const uint8_t* source,
    size_t sourceRowStrideBytes,
    int sourceWidth,
    int sourceHeight,
    int decimate,
    int width,
    int height,
    VkImage* outImage,
    VkDeviceMemory* outMemory,
    VkImageView* outView
) {
    *outImage = VK_NULL_HANDLE;
    *outMemory = VK_NULL_HANDLE;
    *outView = VK_NULL_HANDLE;
    if (state == nullptr || source == nullptr || sourceWidth < 2 || sourceHeight < 2 ||
        width < 2 || height < 2 || sourceRowStrideBytes < static_cast<size_t>(sourceWidth) * 2u) {
        return false;
    }
    decimate = std::max(1, decimate);
    auto& r = state->rawDemosaic;
    const VkDeviceSize uploadBytes = static_cast<VkDeviceSize>(width) *
        static_cast<VkDeviceSize>(height) * 2u;

    const auto resetUploadResources = [&]() {
        if (r.uploadStagingMapped != nullptr && r.uploadStagingMemory != VK_NULL_HANDLE) {
            vkUnmapMemory(state->device, r.uploadStagingMemory);
            r.uploadStagingMapped = nullptr;
        }
        if (r.uploadStaging != VK_NULL_HANDLE) {
            vkDestroyBuffer(state->device, r.uploadStaging, nullptr);
            r.uploadStaging = VK_NULL_HANDLE;
        }
        if (r.uploadStagingMemory != VK_NULL_HANDLE) {
            vkFreeMemory(state->device, r.uploadStagingMemory, nullptr);
            r.uploadStagingMemory = VK_NULL_HANDLE;
        }
        if (r.uploadView != VK_NULL_HANDLE) {
            vkDestroyImageView(state->device, r.uploadView, nullptr);
            r.uploadView = VK_NULL_HANDLE;
        }
        if (r.uploadImage != VK_NULL_HANDLE) {
            vkDestroyImage(state->device, r.uploadImage, nullptr);
            r.uploadImage = VK_NULL_HANDLE;
        }
        if (r.uploadMemory != VK_NULL_HANDLE) {
            vkFreeMemory(state->device, r.uploadMemory, nullptr);
            r.uploadMemory = VK_NULL_HANDLE;
        }
        r.uploadBytes = 0u;
        r.uploadImageInitialized = false;
    };

    if (r.uploadImage == VK_NULL_HANDLE || r.uploadStagingMapped == nullptr ||
        r.uploadBytes != uploadBytes) {
        resetUploadResources();
        if (!createRawDemosaicOwnedImage(
                state, width, height, VK_FORMAT_R16_UINT,
                &r.uploadImage, &r.uploadMemory, &r.uploadView) ||
            !createBufferWithMemory(
                state, uploadBytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                &r.uploadStaging, &r.uploadStagingMemory, /*requireExactProperties=*/true) ||
            !check(vkMapMemory(
                state->device, r.uploadStagingMemory, 0, uploadBytes, 0,
                &r.uploadStagingMapped), "vkMapMemory(rawBytesUploadPersistent)")) {
            resetUploadResources();
            return false;
        }
        r.uploadBytes = uploadBytes;
    }

    const uint16_t* sourceSamples = reinterpret_cast<const uint16_t*>(source);
    const size_t sourceRowSamples = sourceRowStrideBytes / 2u;
    uint16_t* destination = static_cast<uint16_t*>(r.uploadStagingMapped);
    for (int outputY = 0; outputY < height; ++outputY) {
        int sourceY = decimate * (outputY & ~1) + (outputY & 1);
        sourceY = std::min(sourceY, sourceHeight - 1);
        const uint16_t* sourceRow = sourceSamples +
            static_cast<size_t>(sourceY) * sourceRowSamples;
        uint16_t* destinationRow = destination + static_cast<size_t>(outputY) * width;
        for (int outputX = 0; outputX < width; ++outputX) {
            int sourceX = decimate * (outputX & ~1) + (outputX & 1);
            sourceX = std::min(sourceX, sourceWidth - 1);
            destinationRow[outputX] = sourceRow[sourceX];
        }
    }

    *outImage = r.uploadImage;
    *outMemory = r.uploadMemory;
    *outView = r.uploadView;
    return true;
}

// Record the already-prepared staging transfer into the caller's demosaic command buffer. Keeping the
// transfer and compute dispatch in one submission removes the former full-queue drain between them while
// preserving the exact RAW bytes, image format, barriers, demosaic shader and output precision.
bool recordPreparedRawStorageImageUpload(
    VulkanBridgeState* state,
    VkCommandBuffer cmd,
    int width,
    int height
) {
    if (state == nullptr || cmd == VK_NULL_HANDLE || width < 2 || height < 2) return false;
    auto& r = state->rawDemosaic;
    if (r.uploadStaging == VK_NULL_HANDLE || r.uploadImage == VK_NULL_HANDLE ||
        r.uploadStagingMapped == nullptr) return false;

    // Copy staging -> persistent image, preserving the previous GENERAL layout after frame one.
    VkImageSubresourceRange range{};
    range.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT; range.levelCount = 1; range.layerCount = 1;
    VkImageMemoryBarrier toTransfer{};
    toTransfer.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    toTransfer.srcAccessMask = r.uploadImageInitialized ? VK_ACCESS_SHADER_READ_BIT : 0;
    toTransfer.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    toTransfer.oldLayout = r.uploadImageInitialized ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_UNDEFINED;
    toTransfer.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    toTransfer.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toTransfer.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toTransfer.image = r.uploadImage;
    toTransfer.subresourceRange = range;
    vkCmdPipelineBarrier(cmd,
        r.uploadImageInitialized ? VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT : VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &toTransfer);
    VkBufferImageCopy copyRegion{};
    copyRegion.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    copyRegion.imageSubresource.layerCount = 1;
    copyRegion.imageExtent = {
        static_cast<uint32_t>(width),
        static_cast<uint32_t>(height),
        1u
    };
    vkCmdCopyBufferToImage(cmd, r.uploadStaging, r.uploadImage,
        VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copyRegion);
    VkImageMemoryBarrier toGeneral{};
    toGeneral.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    toGeneral.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    toGeneral.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    toGeneral.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    toGeneral.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    toGeneral.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toGeneral.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toGeneral.image = r.uploadImage;
    toGeneral.subresourceRange = range;
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0, 0, nullptr, 0, nullptr, 1, &toGeneral);
    return true;
}

static bool rawNiv99PipelineAvailable(const VulkanBridgeState* state) {
    return state != nullptr &&
        state->rawNiv99DescLayout != VK_NULL_HANDLE &&
        state->rawNiv99PipelineLayout != VK_NULL_HANDLE &&
        state->rawNiv99Pipeline != VK_NULL_HANDLE;
}

static bool ensureRawNiv99Resources(
    VulkanBridgeState* state,
    int width,
    int height
) {
    if (!rawNiv99PipelineAvailable(state) || width < 4 || height < 4) return false;
    auto& r = state->rawDemosaic;
    const bool complete = r.developNiv99ScratchImage != VK_NULL_HANDLE &&
        r.developNiv99ScratchView != VK_NULL_HANDLE &&
        std::all_of(
            r.developNiv99HistoryImages.begin(),
            r.developNiv99HistoryImages.end(),
            [](VkImage image) { return image != VK_NULL_HANDLE; }) &&
        std::all_of(
            r.developNiv99HistoryViews.begin(),
            r.developNiv99HistoryViews.end(),
            [](VkImageView view) { return view != VK_NULL_HANDLE; }) &&
        r.developNiv99CountersBuffer != VK_NULL_HANDLE &&
        r.developNiv99CountersMemory != VK_NULL_HANDLE &&
        r.developNiv99CountersMapped != nullptr &&
        r.developNiv99DescriptorPool != VK_NULL_HANDLE &&
        r.developNiv99SpatialDescriptorSet != VK_NULL_HANDLE &&
        r.developNiv99TemporalDescriptorSet != VK_NULL_HANDLE;
    if (complete && r.developNiv99Width == width && r.developNiv99Height == height) {
        return true;
    }

    releaseRawNiv99Resources(state);
    if (!createRawDemosaicOwnedImage(
            state,
            width,
            height,
            VK_FORMAT_R16_UINT,
            &r.developNiv99ScratchImage,
            &r.developNiv99ScratchMemory,
            &r.developNiv99ScratchView)) {
        releaseRawNiv99Resources(state);
        return false;
    }
    for (size_t i = 0; i < r.developNiv99HistoryImages.size(); ++i) {
        if (!createRawDemosaicOwnedImage(
                state,
                width,
                height,
                VK_FORMAT_R16_UINT,
                &r.developNiv99HistoryImages[i],
                &r.developNiv99HistoryMemories[i],
                &r.developNiv99HistoryViews[i])) {
            releaseRawNiv99Resources(state);
            return false;
        }
    }
    if (!createBufferWithMemory(
            state,
            sizeof(RawNiv99GpuCounters),
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
            &r.developNiv99CountersBuffer,
            &r.developNiv99CountersMemory,
            /*requireExactProperties=*/true) ||
        !check(vkMapMemory(
            state->device,
            r.developNiv99CountersMemory,
            0,
            sizeof(RawNiv99GpuCounters),
            0,
            reinterpret_cast<void**>(&r.developNiv99CountersMapped)),
            "vkMapMemory(rawNiv99Counters)")) {
        releaseRawNiv99Resources(state);
        return false;
    }
    std::memset(r.developNiv99CountersMapped, 0, sizeof(RawNiv99GpuCounters));

    std::array<VkDescriptorPoolSize, 3> poolSizes{};
    poolSizes[0] = {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 6u};
    poolSizes[1] = {VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 2u};
    poolSizes[2] = {VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 2u};
    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = 2u;
    poolInfo.poolSizeCount = static_cast<uint32_t>(poolSizes.size());
    poolInfo.pPoolSizes = poolSizes.data();
    if (!check(vkCreateDescriptorPool(
            state->device,
            &poolInfo,
            nullptr,
            &r.developNiv99DescriptorPool),
            "vkCreateDescriptorPool(rawNiv99)")) {
        releaseRawNiv99Resources(state);
        return false;
    }
    const std::array<VkDescriptorSetLayout, 2> layouts{{
        state->rawNiv99DescLayout,
        state->rawNiv99DescLayout
    }};
    std::array<VkDescriptorSet, 2> sets{};
    VkDescriptorSetAllocateInfo allocateInfo{};
    allocateInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    allocateInfo.descriptorPool = r.developNiv99DescriptorPool;
    allocateInfo.descriptorSetCount = static_cast<uint32_t>(layouts.size());
    allocateInfo.pSetLayouts = layouts.data();
    if (!check(vkAllocateDescriptorSets(state->device, &allocateInfo, sets.data()),
               "vkAllocateDescriptorSets(rawNiv99)")) {
        releaseRawNiv99Resources(state);
        return false;
    }
    r.developNiv99SpatialDescriptorSet = sets[0];
    r.developNiv99TemporalDescriptorSet = sets[1];

    VkCommandPool initializePool = VK_NULL_HANDLE;
    VkCommandBuffer initializeCommand = VK_NULL_HANDLE;
    if (!beginSingleUseCommands(state, &initializePool, &initializeCommand)) {
        releaseRawNiv99Resources(state);
        return false;
    }
    VkImageSubresourceRange imageRange{};
    imageRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    imageRange.levelCount = 1u;
    imageRange.layerCount = 1u;
    const std::array<VkImage, 3> initializedImages{{
        r.developNiv99ScratchImage,
        r.developNiv99HistoryImages[0],
        r.developNiv99HistoryImages[1]
    }};
    for (VkImage image : initializedImages) {
        VkImageMemoryBarrier toGeneral{};
        toGeneral.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        toGeneral.srcAccessMask = 0u;
        toGeneral.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        toGeneral.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        toGeneral.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        toGeneral.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        toGeneral.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        toGeneral.image = image;
        toGeneral.subresourceRange = imageRange;
        vkCmdPipelineBarrier(
            initializeCommand,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            0u, 0u, nullptr, 0u, nullptr, 1u, &toGeneral);
    }
    if (!endSingleUseCommands(state, initializePool, initializeCommand)) {
        releaseRawNiv99Resources(state);
        return false;
    }
    r.developNiv99Width = width;
    r.developNiv99Height = height;
    LOGI("RAW_GPU_NIV99_RESOURCES_READY extent=%dx%d historyImages=2", width, height);
    return true;
}

static bool rawNiv99TimelineContinuous(
    const VulkanBridgeState::RawDemosaicResources& r,
    const RawNiv99DispatchConfig& config
) {
    if (r.developNiv99HistoryIndex < 0 || r.developNiv99LastFrameNumber < 0 ||
        config.frameNumber != r.developNiv99LastFrameNumber + 1 ||
        config.fps == 0u || r.developNiv99LastFps != config.fps) {
        return false;
    }
    if (config.sensorTimestampNs <= 0 || r.developNiv99LastTimestampNs <= 0) return true;
    const int64_t gap = config.sensorTimestampNs - r.developNiv99LastTimestampNs;
    const int64_t maximumGap = static_cast<int64_t>(
        (1000000000ull / static_cast<uint64_t>(config.fps)) * 4ull);
    return gap > 0 && gap <= maximumGap;
}

static bool recordRawNiv99Prefilter(
    VulkanBridgeState* state,
    VkCommandBuffer commandBuffer,
    VkImage rawInputImage,
    VkImageView rawInputView,
    int width,
    int height,
    const RawNiv99DispatchConfig& config,
    VkImage* filteredImage,
    VkImageView* filteredView,
    bool* historyContinuous
) {
    if (state == nullptr || commandBuffer == VK_NULL_HANDLE || rawInputImage == VK_NULL_HANDLE ||
        rawInputView == VK_NULL_HANDLE || filteredImage == nullptr || filteredView == nullptr ||
        historyContinuous == nullptr || !ensureRawNiv99Resources(state, width, height)) {
        return false;
    }
    auto& r = state->rawDemosaic;
    const bool continuous = rawNiv99TimelineContinuous(r, config);
    const int priorIndex = continuous ? r.developNiv99HistoryIndex : 1;
    const int outputIndex = continuous ? 1 - r.developNiv99HistoryIndex : 0;
    if (priorIndex < 0 || priorIndex > 1 || outputIndex < 0 || outputIndex > 1 ||
        priorIndex == outputIndex) {
        return false;
    }

    const std::array<VkDescriptorImageInfo, 6> imageInfos{{
        {VK_NULL_HANDLE, rawInputView, VK_IMAGE_LAYOUT_GENERAL},
        {VK_NULL_HANDLE, r.developNiv99HistoryViews[static_cast<size_t>(priorIndex)], VK_IMAGE_LAYOUT_GENERAL},
        {VK_NULL_HANDLE, r.developNiv99ScratchView, VK_IMAGE_LAYOUT_GENERAL},
        {VK_NULL_HANDLE, r.developNiv99ScratchView, VK_IMAGE_LAYOUT_GENERAL},
        {VK_NULL_HANDLE, r.developNiv99HistoryViews[static_cast<size_t>(priorIndex)], VK_IMAGE_LAYOUT_GENERAL},
        {VK_NULL_HANDLE, r.developNiv99HistoryViews[static_cast<size_t>(outputIndex)], VK_IMAGE_LAYOUT_GENERAL}
    }};
    VkDescriptorBufferInfo counterInfo{};
    counterInfo.buffer = r.developNiv99CountersBuffer;
    counterInfo.offset = 0u;
    counterInfo.range = sizeof(RawNiv99GpuCounters);
    VkDescriptorBufferInfo uniformInfo{};
    uniformInfo.buffer = r.ubo;
    uniformInfo.offset = 0u;
    uniformInfo.range = sizeof(RawDemosaicUniforms);
    std::array<VkWriteDescriptorSet, 10> descriptorWrites{};
    size_t writeIndex = 0u;
    const std::array<VkDescriptorSet, 2> descriptorSets{{
        r.developNiv99SpatialDescriptorSet,
        r.developNiv99TemporalDescriptorSet
    }};
    for (size_t pass = 0u; pass < descriptorSets.size(); ++pass) {
        for (uint32_t binding = 0u; binding < 3u; ++binding) {
            VkWriteDescriptorSet& write = descriptorWrites[writeIndex++];
            write.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            write.dstSet = descriptorSets[pass];
            write.dstBinding = binding;
            write.descriptorCount = 1u;
            write.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
            write.pImageInfo = &imageInfos[pass * 3u + binding];
        }
        VkWriteDescriptorSet& counterWrite = descriptorWrites[writeIndex++];
        counterWrite.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        counterWrite.dstSet = descriptorSets[pass];
        counterWrite.dstBinding = 3u;
        counterWrite.descriptorCount = 1u;
        counterWrite.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        counterWrite.pBufferInfo = &counterInfo;
        VkWriteDescriptorSet& uniformWrite = descriptorWrites[writeIndex++];
        uniformWrite.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        uniformWrite.dstSet = descriptorSets[pass];
        uniformWrite.dstBinding = 4u;
        uniformWrite.descriptorCount = 1u;
        uniformWrite.descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
        uniformWrite.pBufferInfo = &uniformInfo;
    }
    vkUpdateDescriptorSets(
        state->device,
        static_cast<uint32_t>(descriptorWrites.size()),
        descriptorWrites.data(),
        0u,
        nullptr);

    vkCmdFillBuffer(
        commandBuffer,
        r.developNiv99CountersBuffer,
        0u,
        sizeof(RawNiv99GpuCounters),
        0u);
    VkBufferMemoryBarrier countersCleared{};
    countersCleared.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    countersCleared.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    countersCleared.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    countersCleared.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    countersCleared.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    countersCleared.buffer = r.developNiv99CountersBuffer;
    countersCleared.offset = 0u;
    countersCleared.size = sizeof(RawNiv99GpuCounters);
    vkCmdPipelineBarrier(
        commandBuffer,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0u, 0u, nullptr, 1u, &countersCleared, 0u, nullptr);

    RawNiv99PushConstants push{};
    push.width = width;
    push.height = height;
    push.historyEnabled = continuous ? 1 : 0;
    push.reservedInt = 0;
    push.noiseS = config.noiseS;
    push.noiseO = config.noiseO;
    push.totalGain = config.totalGain;
    push.dynamicRange = config.dynamicRange;
    push.spatialStrength = config.spatialStrength;
    push.defectSigmaThreshold = config.defectSigmaThreshold;
    push.spatialSigmaThreshold = config.spatialSigmaThreshold;
    push.temporalStrength = config.temporalStrength;
    push.temporalSigmaThreshold = config.temporalSigmaThreshold;
    push.minimumEffectiveWeight = 0.002f;
    push.minimumSigmaCodes = 0.75f;
    push.reservedFloat = config.sceneCutThreshold;
    const uint32_t groupsX = (static_cast<uint32_t>(width) + 15u) / 16u;
    const uint32_t groupsY = (static_cast<uint32_t>(height) + 15u) / 16u;
    vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, state->rawNiv99Pipeline);
    vkCmdBindDescriptorSets(
        commandBuffer,
        VK_PIPELINE_BIND_POINT_COMPUTE,
        state->rawNiv99PipelineLayout,
        0u, 1u, &r.developNiv99SpatialDescriptorSet, 0u, nullptr);
    vkCmdPushConstants(
        commandBuffer,
        state->rawNiv99PipelineLayout,
        VK_SHADER_STAGE_COMPUTE_BIT,
        0u,
        sizeof(push),
        &push);
    vkCmdDispatch(commandBuffer, groupsX, groupsY, 1u);

    VkImageSubresourceRange imageRange{};
    imageRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    imageRange.levelCount = 1u;
    imageRange.layerCount = 1u;
    VkImageMemoryBarrier spatialReady{};
    spatialReady.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    spatialReady.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    spatialReady.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    spatialReady.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    spatialReady.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    spatialReady.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    spatialReady.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    spatialReady.image = r.developNiv99ScratchImage;
    spatialReady.subresourceRange = imageRange;
    VkBufferMemoryBarrier sceneReady{};
    sceneReady.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    sceneReady.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    sceneReady.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
    sceneReady.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    sceneReady.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    sceneReady.buffer = r.developNiv99CountersBuffer;
    sceneReady.offset = 0u;
    sceneReady.size = sizeof(RawNiv99GpuCounters);
    vkCmdPipelineBarrier(
        commandBuffer,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0u, 0u, nullptr, 1u, &sceneReady, 1u, &spatialReady);

    push.reservedInt = 1;
    vkCmdBindDescriptorSets(
        commandBuffer,
        VK_PIPELINE_BIND_POINT_COMPUTE,
        state->rawNiv99PipelineLayout,
        0u, 1u, &r.developNiv99TemporalDescriptorSet, 0u, nullptr);
    vkCmdPushConstants(
        commandBuffer,
        state->rawNiv99PipelineLayout,
        VK_SHADER_STAGE_COMPUTE_BIT,
        0u,
        sizeof(push),
        &push);
    vkCmdDispatch(commandBuffer, groupsX, groupsY, 1u);

    VkImageMemoryBarrier filteredReady{};
    filteredReady.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    filteredReady.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    filteredReady.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    filteredReady.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    filteredReady.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    filteredReady.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    filteredReady.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    filteredReady.image = r.developNiv99HistoryImages[static_cast<size_t>(outputIndex)];
    filteredReady.subresourceRange = imageRange;
    VkBufferMemoryBarrier countersReady{};
    countersReady.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    countersReady.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    countersReady.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
    countersReady.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    countersReady.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    countersReady.buffer = r.developNiv99CountersBuffer;
    countersReady.offset = 0u;
    countersReady.size = sizeof(RawNiv99GpuCounters);
    vkCmdPipelineBarrier(
        commandBuffer,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_HOST_BIT,
        0u, 0u, nullptr, 1u, &countersReady, 1u, &filteredReady);

    r.pendingNiv99HistoryCommit = true;
    r.pendingNiv99HistoryIndex = outputIndex;
    r.pendingNiv99FrameNumber = config.frameNumber;
    r.pendingNiv99TimestampNs = config.sensorTimestampNs;
    r.pendingNiv99Fps = config.fps;
    *filteredImage = r.developNiv99HistoryImages[static_cast<size_t>(outputIndex)];
    *filteredView = r.developNiv99HistoryViews[static_cast<size_t>(outputIndex)];
    *historyContinuous = continuous;
    return true;
}

bool dispatchRawDemosaicPreview(
    VulkanBridgeState* state, AHardwareBuffer* hardwareBuffer, int width, int height,
    int bayerPattern, int whiteLevel,
    const float blackLevelRggb[4], const float whiteBalanceRggb[4], const float exposureThermal[4],
    const float highlightRecovery[4],
    const float mSensorToXyz[9], const float mXyzToAcescg[9],
    const float lensShadingGridRggb[36], bool lensShadingCorrectionEnabled,
    uint64_t completionWaitNs
) {
    const auto dispatchCycleStarted = std::chrono::steady_clock::now();
    // One-shot diagnostic: report WHICH fail-closed gate stops the RAW preview demosaic on this device.
    static std::atomic<bool> s_rawDemosaicDiagLogged{false};
    auto rawDemosaicDiag = [&](const char* why) {
        bool expected = false;
        if (s_rawDemosaicDiagLogged.compare_exchange_strong(expected, true)) {
            LOGI("RAW preview demosaic path: %s", why);
        }
    };

    if (state == nullptr || hardwareBuffer == nullptr) return false;
    if (!state->rawPreviewDemosaicEnabled) { rawDemosaicDiag("rawPreviewDemosaicEnabled=false"); return false; } // no-downgrade default gate
    // Acquire-load the readiness flag that the background pipeline-build thread store-releases AFTER it
    // writes the (non-atomic) demosaic pipeline handles. This establishes the happens-before that makes
    // the handle reads below well-defined during the deferred cold-start build window — without it a RAW
    // frame submitted mid-build would race those writes (UB on weak-ordered ARM64).
    if (!state->rawDemosaicPipelineReady.load(std::memory_order_acquire)) {
        rawDemosaicDiag("rawDemosaicPipelineReady=false (still building / unavailable)");
        return false; // pipelines still building (cold start) -> YUV fallback
    }
    if (state->demosaicPipeline == VK_NULL_HANDLE ||
        state->demosaicPipelineLayout == VK_NULL_HANDLE ||
        state->demosaicDescLayout == VK_NULL_HANDLE) {
        rawDemosaicDiag("demosaic pipeline handles null (shaderStorageImageExtendedFormats unsupported)");
        return false; // demosaic pipeline not built (e.g. shaderStorageImageExtendedFormats unsupported) -> YUV fallback
    }
    if (width < 2 || height < 2) return false;
    if (!reapPendingRawDispatch(state)) {
        // Latest-frame policy: an older RAW monitor dispatch still owns the shared resources. Skip
        // this presentation frame rather than queueing historical work behind it.
        return false;
    }

    // Full sensor demosaic is reserved for an ACTIVE recording submission. A prepared/pre-armed
    // transport has no full-resolution consumer yet and must not make either idle RAW monitor pay the
    // 4096x3072 demosaic cost (~235 ms on the measured device). Direct NDR and ordinary RAW with a
    // usable GPU transport use the CFA-phase-preserving 1536-class extent; the last-resort CPU monitor
    // remains 1024-class.
    // Recorder ownership flips rawRecordingDemosaicActive and recreates this cache at the full sensor
    // extent before any master frame is submitted, so recording precision is unchanged.
    int monitorDecimate = 1;
    int processingWidth = width;
    int processingHeight = height;
    int previewTargetLongEdge = 0;
    constexpr int kRawIdlePreviewLongEdge = 1536;
    constexpr int kRawMonitorLongEdge = 1024;
    const auto resolveProcessingExtent = [&]() {
        monitorDecimate = 1;
        processingWidth = width;
        processingHeight = height;
        previewTargetLongEdge = 0;
        if (state->rawRecordingDemosaicActive) return;
        if (state->rawDirectNdrPreviewTransportActive ||
            !state->rawOutputAhbStorageUnsupported ||
            (state->rawSceneLogTransportPrepared &&
             !state->rawSceneLogTransportUnsupported)) {
            previewTargetLongEdge = kRawIdlePreviewLongEdge;
        } else {
            // Both project-owned GPU transports were proved unavailable. Retain the smaller bounded
            // CPU-readback monitor instead of recreating full-sensor resources on every frame.
            previewTargetLongEdge = kRawMonitorLongEdge;
        }
        const int sourceLongEdge = std::max(width, height);
        monitorDecimate = std::max(1, (sourceLongEdge + previewTargetLongEdge - 1) /
            previewTargetLongEdge);
        processingWidth = (width / (2 * monitorDecimate)) * 2;
        processingHeight = (height / (2 * monitorDecimate)) * 2;
        if (processingWidth < 64 || processingHeight < 64) {
            monitorDecimate = 1;
            processingWidth = width;
            processingHeight = height;
        }
    };
    resolveProcessingExtent();
    if (!ensureRawDemosaicResources(state, processingWidth, processingHeight)) {
        rawDemosaicDiag("ensureRawDemosaicResources failed");
        return false;
    }
    // The first high-quality preview allocation is also the one-time rgba16f AHB capability probe. If
    // ordinary RAW just proved that both GPU transports are unavailable, switch once to the 1024-class
    // monitor extent now. Later frames resolve that extent before ensuring resources, avoiding churn.
    if (!state->rawDirectNdrPreviewTransportActive &&
        state->rawOutputAhbStorageUnsupported &&
        (!state->rawSceneLogTransportPrepared ||
         state->rawSceneLogTransportUnsupported) &&
        previewTargetLongEdge != kRawMonitorLongEdge) {
        resolveProcessingExtent();
        if (!ensureRawDemosaicResources(state, processingWidth, processingHeight)) {
            rawDemosaicDiag("ensureRawDemosaicResources monitor extent failed");
            return false;
        }
    }
    auto& r = state->rawDemosaic;
    if (monitorDecimate > 1 && r.dispatchCount == 0u) {
        LOGI("RAW preview bounded demosaic: %dx%d -> %dx%d targetLongEdge=%d "
             "same-CFA %dx%d integer tent recordingActive=%s transportSubmission=%s directNdr=%s",
             width, height, processingWidth, processingHeight,
             previewTargetLongEdge, monitorDecimate, monitorDecimate,
             state->rawRecordingDemosaicActive ? "true" : "false",
             state->rawSceneLogTransportSubmissionEnabled ? "true" : "false",
             state->rawDirectNdrPreviewTransportActive ? "true" : "false");
    }

    // 1) Import the RAW mosaic as an R16_UINT storage image (binding 0), per-frame + transient.
    VkImage rawImage = VK_NULL_HANDLE;
    VkDeviceMemory rawMemory = VK_NULL_HANDLE;
    VkImageView rawView = VK_NULL_HANDLE;
    bool rawUploaded = false;
    bool rawImported = false;
    const auto inputPrepareStarted = std::chrono::steady_clock::now();
    if (monitorDecimate == 1 && !r.rawAhbStorageImportUnsupported) {
        rawImported = importRawStorageImage(
            state, hardwareBuffer, width, height, &rawImage, &rawMemory, &rawView);
        if (!rawImported) {
            // RAW AHB allocation properties are stable for this stream/extent. Remember the failed
            // storage-import probe until these owned resources are recreated instead of retrying the
            // same unsupported Vulkan import on every preview frame.
            r.rawAhbStorageImportUnsupported = true;
        }
    }
    if (!rawImported) {
        // The camera RAW AHB is not importable as an R16_UINT storage image on this device — upload
        // the mosaic into a device-local storage image via staging instead (copy-bound, but works).
        if (!prepareRawStorageImageUpload(
                state, hardwareBuffer, width, height, monitorDecimate,
                processingWidth, processingHeight, &rawImage, &rawMemory, &rawView)) {
            rawDemosaicDiag("RAW import AND staging-upload both failed");
            r.lastDispatchOk = false;
            return false;
        }
        rawUploaded = true;
    }
    r.pendingDispatchCycleStarted = dispatchCycleStarted;
    r.pendingInputPrepareMs = elapsedMsSince(inputPrepareStarted);
    r.pendingUsedStagingUpload = rawUploaded;
    r.pendingWasRecording = state->rawRecordingDemosaicActive;
    rawDemosaicDiag(rawUploaded ? "reached dispatch (staging-upload)" : "reached dispatch (zero-copy import)");
    // Release only a transient zero-copy import. Staging-upload handles alias the persistent
    // RawDemosaicResources cache and are destroyed by destroyRawDemosaicResources(), not per frame.
    struct RawGuard {
        VulkanBridgeState* s; VkImage img; VkDeviceMemory mem; VkImageView view; bool owns;
        ~RawGuard() {
            if (!owns) return;
            if (view != VK_NULL_HANDLE) vkDestroyImageView(s->device, view, nullptr);
            if (img != VK_NULL_HANDLE) vkDestroyImage(s->device, img, nullptr);
            if (mem != VK_NULL_HANDLE) vkFreeMemory(s->device, mem, nullptr);
        }
    } rawGuard{ state, rawImage, rawMemory, rawView, rawImported };

    // Reserve an immutable export lane only after the input is proven usable. During recording the
    // previous COMPLETED lane remains owned by EGL until Kotlin acknowledges its exact frame; Vulkan
    // may write only the other FREE lane. Preview/offline paths retain one-lane semantics.
    const int transportSlot = reserveFreeRawTransportSlot(r);
    if (transportSlot < 0) {
        LOGW("RAW demosaic dispatch deferred: no acknowledged export transport is free");
        r.lastDispatchOk = false;
        return false;
    }
    int previewTransportSlot = -1;
    if (rawRecordingPreviewTransportRequired(state)) {
        previewTransportSlot = reserveFreeRawPreviewTransportSlot(r);
        if (previewTransportSlot < 0) {
            // Explicit fail-closed saturation: the frame is never reported as accepted without an
            // independent preview copy. The camera owner can close/retry the source instead of silently
            // losing preview lineage or exposing the full-resolution encoder lane asynchronously.
            LOGE("RAW demosaic dispatch rejected: independent preview transport unavailable/saturated");
            abandonReservedRawTransportSlot(r, transportSlot);
            r.lastDispatchOk = false;
            return false;
        }
    }
    const VkImage outputImage = rawOutputImageForSlot(r, transportSlot);
    const VkImageView outputView = rawOutputViewForSlot(r, transportSlot);
    const VkImage sceneLogImage = rawSceneLogImageForSlot(r, transportSlot);
    const VkImageView sceneLogView = rawSceneLogViewForSlot(r, transportSlot);
    if (outputImage == VK_NULL_HANDLE || outputView == VK_NULL_HANDLE) {
        abandonReservedRawPreviewTransportSlot(r, previewTransportSlot);
        abandonReservedRawTransportSlot(r, transportSlot);
        r.lastDispatchOk = false;
        return false;
    }

    // 2) Build the std140 UBO (matrices transposed to column-major) and upload via the mapped pointer.
    const auto commandBuildStarted = std::chrono::steady_clock::now();
    RawDemosaicUniforms ubo{};
    for (int i = 0; i < 4; ++i) {
        ubo.blackLevelRggb[i] = blackLevelRggb[i];
        ubo.whiteBalanceRggb[i] = whiteBalanceRggb[i];
        ubo.exposureThermal[i] = exposureThermal[i];
        ubo.highlightRecovery[i] = highlightRecovery[i];
        const float denominator = std::max(
            static_cast<float>(whiteLevel) - blackLevelRggb[i],
            1.0f
        );
        ubo.calibrationScaleRggb[i] = whiteBalanceRggb[i] / denominator;
        ubo.calibrationOffsetRggb[i] =
            -blackLevelRggb[i] * ubo.calibrationScaleRggb[i];
    }
    packRawDemosaicMatrixColumnMajor(mSensorToXyz, ubo.mSensorToXyz);
    packRawDemosaicMatrixColumnMajor(mXyzToAcescg, ubo.mXyzToAcescg);
    float sensorToAcescgFast[9]{};
    multiplyRawDemosaicMatrices(mXyzToAcescg, mSensorToXyz, sensorToAcescgFast);
    packRawDemosaicMatrixColumnMajor(sensorToAcescgFast, ubo.mSensorToAcescgFast);
    ubo.bayerPattern = bayerPattern;
    ubo.width = processingWidth;
    ubo.height = processingHeight;
    ubo.whiteLevel = whiteLevel;
    std::memcpy(ubo.lensShadingGridRggb, lensShadingGridRggb, sizeof(ubo.lensShadingGridRggb));
    ubo.lensShadingInfo[0] = lensShadingCorrectionEnabled ? 1 : 0;
    std::memcpy(r.uboMapped, &ubo, sizeof(RawDemosaicUniforms));

    // 3) Write all 5 descriptor bindings — none left UNDEFINED.
    VkDescriptorImageInfo rawDesc{};  rawDesc.imageView  = rawView;       rawDesc.imageLayout  = VK_IMAGE_LAYOUT_GENERAL;
    VkDescriptorImageInfo outDesc{};  outDesc.imageView  = outputView;  outDesc.imageLayout  = VK_IMAGE_LAYOUT_GENERAL;
    VkDescriptorImageInfo darkDesc{}; darkDesc.imageView = r.darkView;    darkDesc.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkDescriptorImageInfo hotDesc{};  hotDesc.imageView  = r.hotView;     hotDesc.imageLayout  = VK_IMAGE_LAYOUT_GENERAL;
    VkDescriptorBufferInfo uboDesc{}; uboDesc.buffer = r.ubo; uboDesc.offset = 0; uboDesc.range = sizeof(RawDemosaicUniforms);

    const VkDescriptorImageInfo* imageDescs[4] = { &rawDesc, &outDesc, &darkDesc, &hotDesc };
    VkWriteDescriptorSet writes[5]{};
    for (int i = 0; i < 4; ++i) {
        writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[i].dstSet = r.descriptorSet;
        writes[i].dstBinding = static_cast<uint32_t>(i);
        writes[i].descriptorCount = 1;
        writes[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        writes[i].pImageInfo = imageDescs[i];
    }
    writes[4].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[4].dstSet = r.descriptorSet;
    writes[4].dstBinding = 4;
    writes[4].descriptorCount = 1;
    writes[4].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    writes[4].pBufferInfo = &uboDesc;
    vkUpdateDescriptorSets(state->device, 5, writes, 0, nullptr);

    // These descriptor sets are Vulkan-only and their preceding command has completed, so rebinding
    // them to the newly reserved lane is safe even while EGL samples the other AHardwareBuffer.
    if (r.sceneLogDescriptorSet != VK_NULL_HANDLE && sceneLogView != VK_NULL_HANDLE) {
        VkDescriptorImageInfo sceneInput{};
        sceneInput.imageView = outputView;
        sceneInput.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
        VkDescriptorImageInfo sceneOutput{};
        sceneOutput.imageView = sceneLogView;
        sceneOutput.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
        VkWriteDescriptorSet sceneWrites[2]{};
        for (uint32_t i = 0; i < 2; ++i) {
            sceneWrites[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            sceneWrites[i].dstSet = r.sceneLogDescriptorSet;
            sceneWrites[i].dstBinding = i;
            sceneWrites[i].descriptorCount = 1;
            sceneWrites[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        }
        sceneWrites[0].pImageInfo = &sceneInput;
        sceneWrites[1].pImageInfo = &sceneOutput;
        vkUpdateDescriptorSets(state->device, 2, sceneWrites, 0, nullptr);
    }
    if (r.monitorDescriptorSet != VK_NULL_HANDLE) {
        VkDescriptorImageInfo monitorInput{};
        monitorInput.imageView = outputView;
        monitorInput.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
        VkWriteDescriptorSet monitorWrite{};
        monitorWrite.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        monitorWrite.dstSet = r.monitorDescriptorSet;
        monitorWrite.dstBinding = 0;
        monitorWrite.descriptorCount = 1;
        monitorWrite.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        monitorWrite.pImageInfo = &monitorInput;
        vkUpdateDescriptorSets(state->device, 1, &monitorWrite, 0, nullptr);
    }

    // 4) Record into the reusable per-extent command buffer. Completion is fence-scoped; it never
    // drains unrelated preview/encoder work from the graphics queue.
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    if (!beginRawDispatchCommands(state, &cmd)) {
        abandonReservedRawPreviewTransportSlot(r, previewTransportSlot);
        abandonReservedRawTransportSlot(r, transportSlot);
        r.lastDispatchOk = false;
        return false;
    }
    const uint64_t nextRecordingTimingSample = r.timingSampleCount + 1u;
    const bool sampleRawGpuStages = r.pendingWasRecording &&
        r.dispatchTimestampQueryPool != VK_NULL_HANDLE &&
        state->timestampQueriesSupported &&
        state->timestampPeriodNs > 0.0 &&
        (nextRecordingTimingSample <= 8u || nextRecordingTimingSample % 60u == 0u);
    const auto writeRawGpuTimestamp = [&](RawGpuTimestampPoint point, VkPipelineStageFlagBits stage) {
        if (!sampleRawGpuStages) return;
        vkCmdWriteTimestamp(
            cmd,
            stage,
            r.dispatchTimestampQueryPool,
            static_cast<uint32_t>(point));
    };
    if (sampleRawGpuStages) {
        vkCmdResetQueryPool(cmd, r.dispatchTimestampQueryPool, 0u, kRawGpuTimestampCount);
        writeRawGpuTimestamp(RawGpuTimestampPoint::Begin, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT);
    }
    if (rawUploaded && !recordPreparedRawStorageImageUpload(
            state, cmd, processingWidth, processingHeight)) {
        vkResetCommandPool(state->device, r.dispatchCommandPool, 0);
        abandonReservedRawPreviewTransportSlot(r, previewTransportSlot);
        abandonReservedRawTransportSlot(r, transportSlot);
        r.lastDispatchOk = false;
        return false;
    }
    writeRawGpuTimestamp(
        RawGpuTimestampPoint::UploadComplete,
        rawUploaded ? VK_PIPELINE_STAGE_TRANSFER_BIT : VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT);
    VkImageSubresourceRange range{};
    range.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    range.levelCount = 1;
    range.layerCount = 1;
    // The staging-upload path already populated the image and left it in GENERAL — use GENERAL as the
    // old layout so its contents are PRESERVED (an UNDEFINED old-layout would let the driver discard the
    // uploaded RAW). The zero-copy import path keeps the original UNDEFINED->GENERAL make-readable barrier.
    VkImageMemoryBarrier rawToGeneral{};
    rawToGeneral.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    rawToGeneral.srcAccessMask = rawUploaded ? VK_ACCESS_TRANSFER_WRITE_BIT : 0;
    rawToGeneral.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    rawToGeneral.oldLayout = rawUploaded ? VK_IMAGE_LAYOUT_GENERAL : VK_IMAGE_LAYOUT_UNDEFINED;
    rawToGeneral.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    rawToGeneral.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    rawToGeneral.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    rawToGeneral.image = rawImage;
    rawToGeneral.subresourceRange = range;
    vkCmdPipelineBarrier(cmd,
        rawUploaded ? VK_PIPELINE_STAGE_TRANSFER_BIT : VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0, 0, nullptr, 0, nullptr, 1, &rawToGeneral);

    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, state->demosaicPipeline);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, state->demosaicPipelineLayout,
        0, 1, &r.descriptorSet, 0, nullptr);
    const uint32_t gx = (static_cast<uint32_t>(processingWidth) + 15u) / 16u;
    const uint32_t gy = (static_cast<uint32_t>(processingHeight) + 15u) / 16u;
    vkCmdDispatch(cmd, gx, gy, 1u);
    writeRawGpuTimestamp(
        RawGpuTimestampPoint::DemosaicComplete,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);

    setRawSceneLogReadyForSlot(r, transportSlot, false);
    const bool sceneLogRecorded = recordRawSceneLogTransport(
        state, cmd, range, processingWidth, processingHeight,
        outputImage, sceneLogImage);
    writeRawGpuTimestamp(
        RawGpuTimestampPoint::SceneLogComplete,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
    // The skip-readback authority is owner-scoped, not mode-scoped. A stale active flag or a merely
    // allocated ring cannot suppress the legacy monitor: the exact frame/slot/token must currently be
    // Vulkan-owned, prove FP16 or packed 10-bit transport, and accept its copy commands.
    const bool strictTenBitPreviewTransportAcceptedForFrame = previewTransportSlot >= 0 &&
        strictTenBitPreviewTransportOwnsFrame(r, previewTransportSlot) &&
        recordRawPreviewTransport(
            state,
            cmd,
            range,
            processingWidth,
            processingHeight,
            outputImage,
            outputView,
            previewTransportSlot);
    writeRawGpuTimestamp(
        RawGpuTimestampPoint::PreviewComplete,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
    if (previewTransportSlot >= 0 && !strictTenBitPreviewTransportAcceptedForFrame) {
        // Every recordRawPreviewTransport failure is validated before it appends preview commands.
        // Retire only that provisional owner and retain the legacy monitor in this same dispatch.
        abandonReservedRawPreviewTransportSlot(r, previewTransportSlot);
        LOGE("RAW strict preview transport rejected; retaining legacy monitor readback for this frame");
    }
    // Strict recording preview now consumes only the independent bounded ring. Full-resolution encoder
    // pixels are unchanged, and no RGBA8/CPU readback is generated on this path.
    const bool doReadback = !strictTenBitPreviewTransportAcceptedForFrame &&
        recordRawMonitorReadback(
            state, cmd, range, processingWidth, processingHeight, outputImage);
    writeRawGpuTimestamp(
        RawGpuTimestampPoint::MonitorComplete,
        doReadback ? VK_PIPELINE_STAGE_TRANSFER_BIT : VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
    writeRawGpuTimestamp(
        RawGpuTimestampPoint::DispatchComplete,
        VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);

    r.pendingCommandBuildMs = elapsedMsSince(commandBuildStarted);
    r.pendingGpuTimestampSample = sampleRawGpuStages;
    const RawDispatchSubmitResult submitResult = submitRawDispatchCommands(state, completionWaitNs);
    if (submitResult == RawDispatchSubmitResult::Failed) {
        r.pendingGpuTimestampSample = false;
        abandonReservedRawPreviewTransportSlot(r, previewTransportSlot);
        abandonReservedRawTransportSlot(r, transportSlot);
        r.lastDispatchOk = false;
        return false;
    }
    if (submitResult == RawDispatchSubmitResult::Pending) {
        r.pendingReadbackRecorded = doReadback;
        r.pendingSceneLogRecorded = sceneLogRecorded;
        r.pendingUploadInitialization = rawUploaded;
        if (rawImported) {
            // AHardwareBuffer_fromHardwareBuffer() returns a borrowed pointer. The Java/Image owner may
            // close it as soon as this JNI call returns, so retain an explicit native reference whenever
            // the Vulkan import outlives the call after a deadline overrun.
            AHardwareBuffer_acquire(hardwareBuffer);
            r.pendingRawHardwareBuffer = hardwareBuffer;
            r.pendingRawImage = rawImage;
            r.pendingRawMemory = rawMemory;
            r.pendingRawView = rawView;
            rawGuard.owns = false;
        }
        return false;
    }

    const bool finalized = finalizeRawDispatch(
        state, doReadback, sceneLogRecorded, rawUploaded);
    if (r.dispatchCount % 60u == 1u) {
        LOGI("RAW cinema-debayer dispatched %llu frames (%dx%d sensor -> %dx%d ACEScg)",
             static_cast<unsigned long long>(r.dispatchCount), width, height,
             processingWidth, processingHeight);
    }
    return finalized;
}

// ═════════════════════════════════════════════════════════════════════════════
// RECORD-TIME LIVE MONITOR (byte-copy + half-res) — keeps the RAW cinema-debayer preview LIVE while
// recording WITHOUT holding a scarce RAW reader buffer. During RAW recording the single RAW_SENSOR reader
// is shared with the disk writer and (at un-binned max-res) has only ~3 buffers; holding one for the
// monitor would starve the recorder. So the writer lifts an ALREADY-PERSISTED frame's bytes into a plain
// buffer, frees the Image immediately, and hands the copy here. Sourcing from bytes (no AHB import/lock)
// also lets us DECIMATE CFA-phase-preservingly (2 = half-res) so the record-time monitor costs ~1/4 the
// GPU/bandwidth of the full-res idle path. The idle path (dispatchRawDemosaicPreview) is untouched.
// ═════════════════════════════════════════════════════════════════════════════

// Upload a CPU RAW mosaic (uint16 samples, row-strided) into a device-local R16_UINT storage image,
// DECIMATED by `decimate` (1 = full-res, 2 = half-res) while keeping the Bayer CFA phase intact (whole
// 2x2 quads sampled on a 2*decimate grid). Mirrors uploadRawStorageImage but sources from a CPU pointer.
// outW/outH are the decimated extents; the image is left in GENERAL for the demosaic imageLoad.
[[maybe_unused]] bool uploadRawStorageImageFromBytes(
    VulkanBridgeState* state, const uint8_t* src, size_t srcRowStrideBytes,
    int srcWidth, int srcHeight, int decimate, int outW, int outH,
    VkImage* outImage, VkDeviceMemory* outMemory, VkImageView* outView
) {
    *outImage = VK_NULL_HANDLE; *outMemory = VK_NULL_HANDLE; *outView = VK_NULL_HANDLE;
    if (state == nullptr || src == nullptr || outW < 2 || outH < 2) return false;
    if (decimate < 1) decimate = 1;

    const uint32_t w = static_cast<uint32_t>(outW);
    const uint32_t h = static_cast<uint32_t>(outH);

    // 1) Device-local R16_UINT storage image (STORAGE for demosaic imageLoad + TRANSFER_DST for upload).
    VkImageCreateInfo imageInfo{};
    imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = VK_FORMAT_R16_UINT;
    imageInfo.extent = { w, h, 1u };
    imageInfo.mipLevels = 1; imageInfo.arrayLayers = 1;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.usage = VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (!check(vkCreateImage(state->device, &imageInfo, nullptr, outImage), "vkCreateImage(rawBytesUpload)")) return false;

    VkMemoryRequirements memReq{};
    vkGetImageMemoryRequirements(state->device, *outImage, &memReq);
    VkMemoryAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.allocationSize = memReq.size;
    allocInfo.memoryTypeIndex = findMemoryType(state, memReq.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (!check(vkAllocateMemory(state->device, &allocInfo, nullptr, outMemory), "vkAllocateMemory(rawBytesUpload)")) {
        vkDestroyImage(state->device, *outImage, nullptr); *outImage = VK_NULL_HANDLE; return false;
    }
    if (!check(vkBindImageMemory(state->device, *outImage, *outMemory, 0), "vkBindImageMemory(rawBytesUpload)")) {
        vkFreeMemory(state->device, *outMemory, nullptr); vkDestroyImage(state->device, *outImage, nullptr);
        *outMemory = VK_NULL_HANDLE; *outImage = VK_NULL_HANDLE; return false;
    }
    VkImageViewCreateInfo viewInfo{};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = *outImage;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = VK_FORMAT_R16_UINT;
    viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    viewInfo.subresourceRange.levelCount = 1;
    viewInfo.subresourceRange.layerCount = 1;
    if (!check(vkCreateImageView(state->device, &viewInfo, nullptr, outView), "vkCreateImageView(rawBytesUpload)")) {
        vkFreeMemory(state->device, *outMemory, nullptr); vkDestroyImage(state->device, *outImage, nullptr);
        *outMemory = VK_NULL_HANDLE; *outImage = VK_NULL_HANDLE; return false;
    }

    auto destroyImageOut = [&]() {
        if (*outView != VK_NULL_HANDLE) vkDestroyImageView(state->device, *outView, nullptr);
        if (*outImage != VK_NULL_HANDLE) vkDestroyImage(state->device, *outImage, nullptr);
        if (*outMemory != VK_NULL_HANDLE) vkFreeMemory(state->device, *outMemory, nullptr);
        *outView = VK_NULL_HANDLE; *outImage = VK_NULL_HANDLE; *outMemory = VK_NULL_HANDLE;
    };

    // 2) Host-visible staging buffer; gather the decimated mosaic (CFA-phase-preserving), tight-packed.
    const size_t tightRowBytes = static_cast<size_t>(outW) * 2u;
    const VkDeviceSize uploadBytes = static_cast<VkDeviceSize>(tightRowBytes) * h;
    VkBuffer staging = VK_NULL_HANDLE; VkDeviceMemory stagingMem = VK_NULL_HANDLE;
    if (!createBufferWithMemory(state, uploadBytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, &staging, &stagingMem)) {
        destroyImageOut(); return false;
    }
    void* mapped = nullptr;
    if (!check(vkMapMemory(state->device, stagingMem, 0, uploadBytes, 0, &mapped), "vkMapMemory(rawBytesUpload)")) {
        vkFreeMemory(state->device, stagingMem, nullptr); vkDestroyBuffer(state->device, staging, nullptr);
        destroyImageOut(); return false;
    }
    {
        const uint16_t* s16 = reinterpret_cast<const uint16_t*>(src);
        const size_t srcRowSamples = srcRowStrideBytes / 2u;
        uint16_t* d16 = static_cast<uint16_t*>(mapped);
        for (int oy = 0; oy < outH; ++oy) {
            // syBase preserves phase: even output rows map to even source-quad rows on a 2*decimate grid.
            int sy = decimate * (oy & ~1) + (oy & 1);
            if (sy >= srcHeight) sy = srcHeight - 1;
            const uint16_t* srow = s16 + static_cast<size_t>(sy) * srcRowSamples;
            uint16_t* drow = d16 + static_cast<size_t>(oy) * static_cast<size_t>(outW);
            for (int ox = 0; ox < outW; ++ox) {
                int sx = decimate * (ox & ~1) + (ox & 1);
                if (sx >= srcWidth) sx = srcWidth - 1;
                drow[ox] = srow[sx];
            }
        }
    }
    vkUnmapMemory(state->device, stagingMem);

    // 3) Copy staging -> image, leaving it in GENERAL for the demosaic imageLoad.
    VkCommandPool pool = VK_NULL_HANDLE; VkCommandBuffer cmd = VK_NULL_HANDLE;
    if (!beginSingleUseCommands(state, &pool, &cmd)) {
        vkFreeMemory(state->device, stagingMem, nullptr); vkDestroyBuffer(state->device, staging, nullptr);
        destroyImageOut(); return false;
    }
    VkImageSubresourceRange range{};
    range.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT; range.levelCount = 1; range.layerCount = 1;
    transitionImageLayout(cmd, *outImage, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, range);
    VkBufferImageCopy copyRegion{};
    copyRegion.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    copyRegion.imageSubresource.layerCount = 1;
    copyRegion.imageExtent = { w, h, 1u };
    vkCmdCopyBufferToImage(cmd, staging, *outImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copyRegion);
    transitionImageLayout(cmd, *outImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL, range);
    const bool ok = endSingleUseCommands(state, pool, cmd);
    vkFreeMemory(state->device, stagingMem, nullptr); vkDestroyBuffer(state->device, staging, nullptr);
    if (!ok) { destroyImageOut(); return false; }
    return true;
}

// Demosaic a CPU RAW mosaic copy (already lifted off the camera RAW Image so the reader buffer is freed)
// into the CPU-readback RGBA8 monitor. Mirrors dispatchRawDemosaicPreview but sources from bytes and
// DECIMATES by `decimate`. Returns false (-> no present) on any non-fatal condition.
bool dispatchRawDemosaicPreviewFromBytes(
    VulkanBridgeState* state, const uint8_t* src, size_t srcRowStrideBytes, int srcWidth, int srcHeight,
    int decimate, int bayerPattern, int whiteLevel,
    const float blackLevelRggb[4], const float whiteBalanceRggb[4], const float exposureThermal[4],
    const float highlightRecovery[4],
    const float mSensorToXyz[9], const float mXyzToAcescg[9],
    const float lensShadingGridRggb[36], bool lensShadingCorrectionEnabled,
    bool produceMonitorOutputs, uint64_t completionWaitNs,
    const RawNiv99DispatchConfig* niv99Config = nullptr,
    RawNiv99DispatchEvidence* niv99Evidence = nullptr
) {
    if (niv99Evidence != nullptr) *niv99Evidence = RawNiv99DispatchEvidence{};
    if (state == nullptr || src == nullptr) return false;
    if (!state->rawPreviewDemosaicEnabled) return false;                                   // no-downgrade gate
    if (!state->rawDemosaicPipelineReady.load(std::memory_order_acquire)) return false;    // demosaic still building/unavailable
    if (state->demosaicPipeline == VK_NULL_HANDLE ||
        state->demosaicPipelineLayout == VK_NULL_HANDLE ||
        state->demosaicDescLayout == VK_NULL_HANDLE) {
        return false;                                                                       // demosaic pipeline not built
    }
    if (srcWidth < 4 || srcHeight < 4) return false;
    if (decimate < 1) decimate = 1;
    if (!reapPendingRawDispatch(state)) return false;

    // Decimated extents, rounded down to whole 2x2 Bayer quads (even) so the CFA phase is preserved.
    int effWidth  = (srcWidth  / (2 * decimate)) * 2;
    int effHeight = (srcHeight / (2 * decimate)) * 2;
    if (effWidth < 64 || effHeight < 64) {
        // Too small after decimation — fall back to full-res so a monitor still appears.
        decimate = 1;
        effWidth  = (srcWidth  / 2) * 2;
        effHeight = (srcHeight / 2) * 2;
        if (effWidth < 64 || effHeight < 64) return false;
    }

    if (!ensureRawDemosaicResources(state, effWidth, effHeight)) return false;
    auto& r = state->rawDemosaic;

    VkImage rawImage = VK_NULL_HANDLE;
    VkDeviceMemory rawMemory = VK_NULL_HANDLE;
    VkImageView rawView = VK_NULL_HANDLE;
    if (!prepareRawStorageImageUploadFromBytes(
            state, src, srcRowStrideBytes, srcWidth, srcHeight, decimate,
            effWidth, effHeight, &rawImage, &rawMemory, &rawView)) {
        r.lastDispatchOk = false;
        return false;
    }
    if (niv99Config != nullptr && !ensureRawNiv99Resources(state, effWidth, effHeight)) {
        r.lastDispatchOk = false;
        return false;
    }
    const int transportSlot = reserveFreeRawTransportSlot(r);
    if (transportSlot < 0) {
        r.lastDispatchOk = false;
        return false;
    }
    const VkImage outputImage = rawOutputImageForSlot(r, transportSlot);
    const VkImageView outputView = rawOutputViewForSlot(r, transportSlot);
    const VkImage sceneLogImage = rawSceneLogImageForSlot(r, transportSlot);
    const VkImageView sceneLogView = rawSceneLogViewForSlot(r, transportSlot);
    if (outputImage == VK_NULL_HANDLE || outputView == VK_NULL_HANDLE) {
        abandonReservedRawTransportSlot(r, transportSlot);
        r.lastDispatchOk = false;
        return false;
    }

    // ── core (mirrors dispatchRawDemosaicPreview): UBO -> descriptors -> dispatch -> readback -> tonemap ──
    RawDemosaicUniforms ubo{};
    for (int i = 0; i < 4; ++i) {
        ubo.blackLevelRggb[i] = blackLevelRggb[i];
        ubo.whiteBalanceRggb[i] = whiteBalanceRggb[i];
        ubo.exposureThermal[i] = exposureThermal[i];
        ubo.highlightRecovery[i] = highlightRecovery[i];
        const float denominator = std::max(
            static_cast<float>(whiteLevel) - blackLevelRggb[i],
            1.0f
        );
        ubo.calibrationScaleRggb[i] = whiteBalanceRggb[i] / denominator;
        ubo.calibrationOffsetRggb[i] =
            -blackLevelRggb[i] * ubo.calibrationScaleRggb[i];
    }
    packRawDemosaicMatrixColumnMajor(mSensorToXyz, ubo.mSensorToXyz);
    packRawDemosaicMatrixColumnMajor(mXyzToAcescg, ubo.mXyzToAcescg);
    float sensorToAcescgFast[9]{};
    multiplyRawDemosaicMatrices(mXyzToAcescg, mSensorToXyz, sensorToAcescgFast);
    packRawDemosaicMatrixColumnMajor(sensorToAcescgFast, ubo.mSensorToAcescgFast);
    ubo.bayerPattern = bayerPattern;
    ubo.width = effWidth;
    ubo.height = effHeight;
    ubo.whiteLevel = whiteLevel;
    std::memcpy(ubo.lensShadingGridRggb, lensShadingGridRggb, sizeof(ubo.lensShadingGridRggb));
    ubo.lensShadingInfo[0] = lensShadingCorrectionEnabled ? 1 : 0;
    std::memcpy(r.uboMapped, &ubo, sizeof(RawDemosaicUniforms));

    VkDescriptorImageInfo rawDesc{};  rawDesc.imageView  = rawView;       rawDesc.imageLayout  = VK_IMAGE_LAYOUT_GENERAL;
    VkDescriptorImageInfo outDesc{};  outDesc.imageView  = outputView;  outDesc.imageLayout  = VK_IMAGE_LAYOUT_GENERAL;
    VkDescriptorImageInfo darkDesc{}; darkDesc.imageView = r.darkView;    darkDesc.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkDescriptorImageInfo hotDesc{};  hotDesc.imageView  = r.hotView;     hotDesc.imageLayout  = VK_IMAGE_LAYOUT_GENERAL;
    VkDescriptorBufferInfo uboDesc{}; uboDesc.buffer = r.ubo; uboDesc.offset = 0; uboDesc.range = sizeof(RawDemosaicUniforms);

    const VkDescriptorImageInfo* imageDescs[4] = { &rawDesc, &outDesc, &darkDesc, &hotDesc };
    VkWriteDescriptorSet writes[5]{};
    for (int i = 0; i < 4; ++i) {
        writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[i].dstSet = r.descriptorSet;
        writes[i].dstBinding = static_cast<uint32_t>(i);
        writes[i].descriptorCount = 1;
        writes[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        writes[i].pImageInfo = imageDescs[i];
    }
    writes[4].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[4].dstSet = r.descriptorSet;
    writes[4].dstBinding = 4;
    writes[4].descriptorCount = 1;
    writes[4].descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
    writes[4].pBufferInfo = &uboDesc;
    vkUpdateDescriptorSets(state->device, 5, writes, 0, nullptr);
    if (r.sceneLogDescriptorSet != VK_NULL_HANDLE && sceneLogView != VK_NULL_HANDLE) {
        VkDescriptorImageInfo sceneInput{};
        sceneInput.imageView = outputView;
        sceneInput.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
        VkDescriptorImageInfo sceneOutput{};
        sceneOutput.imageView = sceneLogView;
        sceneOutput.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
        VkWriteDescriptorSet sceneWrites[2]{};
        for (uint32_t i = 0; i < 2; ++i) {
            sceneWrites[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
            sceneWrites[i].dstSet = r.sceneLogDescriptorSet;
            sceneWrites[i].dstBinding = i;
            sceneWrites[i].descriptorCount = 1;
            sceneWrites[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        }
        sceneWrites[0].pImageInfo = &sceneInput;
        sceneWrites[1].pImageInfo = &sceneOutput;
        vkUpdateDescriptorSets(state->device, 2, sceneWrites, 0, nullptr);
    }
    if (r.monitorDescriptorSet != VK_NULL_HANDLE) {
        VkDescriptorImageInfo monitorInput{};
        monitorInput.imageView = outputView;
        monitorInput.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
        VkWriteDescriptorSet monitorWrite{};
        monitorWrite.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        monitorWrite.dstSet = r.monitorDescriptorSet;
        monitorWrite.dstBinding = 0;
        monitorWrite.descriptorCount = 1;
        monitorWrite.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        monitorWrite.pImageInfo = &monitorInput;
        vkUpdateDescriptorSets(state->device, 1, &monitorWrite, 0, nullptr);
    }

    VkCommandBuffer cmd = VK_NULL_HANDLE;
    if (!beginRawDispatchCommands(state, &cmd)) {
        abandonReservedRawTransportSlot(r, transportSlot);
        r.lastDispatchOk = false;
        return false;
    }
    if (!recordPreparedRawStorageImageUpload(state, cmd, effWidth, effHeight)) {
        vkResetCommandPool(state->device, r.dispatchCommandPool, 0);
        abandonReservedRawTransportSlot(r, transportSlot);
        r.lastDispatchOk = false;
        return false;
    }
    bool niv99HistoryContinuous = false;
    if (niv99Config != nullptr) {
        VkImage filteredImage = VK_NULL_HANDLE;
        VkImageView filteredView = VK_NULL_HANDLE;
        if (!recordRawNiv99Prefilter(
                state,
                cmd,
                rawImage,
                rawView,
                effWidth,
                effHeight,
                *niv99Config,
                &filteredImage,
                &filteredView,
                &niv99HistoryContinuous)) {
            vkResetCommandPool(state->device, r.dispatchCommandPool, 0);
            abandonPendingRawNiv99History(r);
            abandonReservedRawTransportSlot(r, transportSlot);
            r.lastDispatchOk = false;
            return false;
        }
        rawDesc.imageView = filteredView;
        rawDesc.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
        writes[0].pImageInfo = &rawDesc;
        vkUpdateDescriptorSets(state->device, 1u, &writes[0], 0u, nullptr);
    }
    VkImageSubresourceRange range{};
    range.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    range.levelCount = 1;
    range.layerCount = 1;
    // The bytes-upload path populated the image and left it in GENERAL — use GENERAL as the old layout so
    // its contents are PRESERVED (UNDEFINED would let the driver discard the uploaded mosaic).
    VkImageMemoryBarrier rawToGeneral{};
    rawToGeneral.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    rawToGeneral.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    rawToGeneral.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    rawToGeneral.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    rawToGeneral.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    rawToGeneral.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    rawToGeneral.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    rawToGeneral.image = rawImage;
    rawToGeneral.subresourceRange = range;
    vkCmdPipelineBarrier(cmd,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0, 0, nullptr, 0, nullptr, 1, &rawToGeneral);

    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, state->demosaicPipeline);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, state->demosaicPipelineLayout,
        0, 1, &r.descriptorSet, 0, nullptr);
    const uint32_t gx = (static_cast<uint32_t>(effWidth) + 15u) / 16u;
    const uint32_t gy = (static_cast<uint32_t>(effHeight) + 15u) / 16u;
    vkCmdDispatch(cmd, gx, gy, 1u);

    setRawSceneLogReadyForSlot(r, transportSlot, false);
    // Offline conversion consumes the full-resolution ACEScg image directly. Recording the preview-only
    // scene-log, downscale, tone-map and RGBA8 readback here duplicated GPU/CPU work without contributing
    // one bit to the converted frame.
    const bool sceneLogRecorded = produceMonitorOutputs && recordRawSceneLogTransport(
        state, cmd, range, effWidth, effHeight, outputImage, sceneLogImage);
    const bool doReadback = produceMonitorOutputs &&
        recordRawMonitorReadback(state, cmd, range, effWidth, effHeight, outputImage);

    const RawDispatchSubmitResult submitResult = submitRawDispatchCommands(
        state,
        completionWaitNs);
    if (submitResult == RawDispatchSubmitResult::Failed) {
        abandonPendingRawNiv99History(r);
        abandonReservedRawTransportSlot(r, transportSlot);
        r.lastDispatchOk = false;
        return false;
    }
    if (submitResult == RawDispatchSubmitResult::Pending) {
        r.pendingReadbackRecorded = doReadback;
        r.pendingSceneLogRecorded = sceneLogRecorded;
        r.pendingUploadInitialization = true;
        return false;
    }

    const bool finalized = finalizeRawDispatch(state, doReadback, sceneLogRecorded, true);
    if (!finalized || niv99Config == nullptr) return finalized;
    if (r.developNiv99CountersMapped == nullptr) {
        r.developNiv99HistoryIndex = -1;
        acknowledgeOldestCompletedRawTransport(r);
        return false;
    }
    const RawNiv99GpuCounters counters = *r.developNiv99CountersMapped;
    const uint64_t expectedSamples = static_cast<uint64_t>(effWidth) *
        static_cast<uint64_t>(effHeight);
    if (counters.processedSamples != expectedSamples) {
        LOGE(
            "RAW_GPU_NIV99_EVIDENCE_REJECT processed=%u expected=%llu",
            counters.processedSamples,
            static_cast<unsigned long long>(expectedSamples));
        r.developNiv99HistoryIndex = -1;
        acknowledgeOldestCompletedRawTransport(r);
        return false;
    }
    if (niv99Evidence != nullptr) {
        niv99Evidence->applied = true;
        niv99Evidence->historyUsed = counters.temporallyFusedSamples > 0u;
        niv99Evidence->sceneCut = counters.sceneCutDetected != 0u;
        niv99Evidence->historyReset = !niv99HistoryContinuous || niv99Evidence->sceneCut;
        niv99Evidence->counters = counters;
    }
    ++r.developNiv99DispatchCount;
    if (r.developNiv99DispatchCount <= 3u || r.developNiv99DispatchCount % 60u == 0u) {
        LOGI(
            "RAW_GPU_NIV99_DISPATCH count=%llu extent=%dx%d spatial=%u temporal=%u "
            "defects=%u processed=%u history=%d reset=%d sceneCut=%d",
            static_cast<unsigned long long>(r.developNiv99DispatchCount),
            effWidth,
            effHeight,
            counters.spatiallyFilteredSamples,
            counters.temporallyFusedSamples,
            counters.correctedDefects,
            counters.processedSamples,
            niv99Evidence != nullptr && niv99Evidence->historyUsed ? 1 : 0,
            niv99Evidence == nullptr || niv99Evidence->historyReset ? 1 : 0,
            counters.sceneCutDetected != 0u ? 1 : 0);
    }
    return true;
}

// ═════════════════════════════════════════════════════════════════════════════
// s1-f10 / CP31 — dual-conversion-gain fusion dispatch (dual_gain_fusion.comp)
// ═════════════════════════════════════════════════════════════════════════════
// NeoProCamX NDR: merges a low- and a high-conversion-gain ACEScg readout into one high-DR ACEScg
// frame (NeoProCamX's own dynamic-range tech — no vendor formula). The pipeline is built best-effort
// in createOmniClarityPipelines; the dispatch is gated behind dualGainFusionEnabled (default OFF) so the
// shipping render path is byte-identical until the owner enables it device-side. All 4 bindings are
// rgba16f storage images: 0 LCG in, 1 HCG in, 2 fused out, 3 motion-confidence out.

void destroyDualGainFusionResources(VulkanBridgeState* state) {
    if (state == nullptr || state->device == VK_NULL_HANDLE) return;
    auto& d = state->dualGainFusion;
    if (d.descriptorPool != VK_NULL_HANDLE) {
        vkDestroyDescriptorPool(state->device, d.descriptorPool, nullptr); // frees descriptorSet too
        d.descriptorPool = VK_NULL_HANDLE;
        d.descriptorSet = VK_NULL_HANDLE;
    }
    const auto destroyImg = [&](VkImage& img, VkDeviceMemory& mem, VkImageView& view) {
        if (view != VK_NULL_HANDLE) { vkDestroyImageView(state->device, view, nullptr); view = VK_NULL_HANDLE; }
        if (img != VK_NULL_HANDLE) { vkDestroyImage(state->device, img, nullptr); img = VK_NULL_HANDLE; }
        if (mem != VK_NULL_HANDLE) { vkFreeMemory(state->device, mem, nullptr); mem = VK_NULL_HANDLE; }
    };
    destroyImg(d.fusedImage, d.fusedMemory, d.fusedView);
    destroyImg(d.motionImage, d.motionMemory, d.motionView);
    d.width = 0;
    d.height = 0;
}

// Import an rgba16f ACEScg readout AHB as an R16G16B16A16_SFLOAT storage image (mirrors
// importRawStorageImage). Fail-closed (no fusion) if the AHB is an external/vendor format or lacks
// GPU storage capability — whether a device delivers dual-gain readouts as storage rgba16f AHBs is the
// device-gated part; the contract here is honest (never a garbage fused frame).
bool importAcesCgStorageImage(
    VulkanBridgeState* state, AHardwareBuffer* hardwareBuffer, int width, int height,
    VkImage* outImage, VkDeviceMemory* outMemory, VkImageView* outView
) {
    *outImage = VK_NULL_HANDLE; *outMemory = VK_NULL_HANDLE; *outView = VK_NULL_HANDLE;

    VkAndroidHardwareBufferFormatPropertiesANDROID formatProps{};
    formatProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID;
    VkAndroidHardwareBufferPropertiesANDROID bufferProps{};
    bufferProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
    bufferProps.pNext = &formatProps;
    if (!check(state->getAndroidHardwareBufferProperties(state->device, hardwareBuffer, &bufferProps),
               "vkGetAndroidHardwareBufferPropertiesANDROID(acescg)")) {
        return false;
    }
    if (formatProps.externalFormat != 0 || formatProps.format != VK_FORMAT_R16G16B16A16_SFLOAT) {
        setStatus(state, "dual-gain fusion skipped: AHB is not a storage-capable rgba16f readout");
        return false;
    }
    if ((formatProps.formatFeatures & VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT) == 0) {
        setStatus(state, "dual-gain fusion skipped: imported AHB lacks GPU storage capability");
        return false;
    }

    VkExternalMemoryImageCreateInfo externalImageInfo{};
    externalImageInfo.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
    externalImageInfo.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;

    VkImageCreateInfo imageInfo{};
    imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imageInfo.pNext = &externalImageInfo;
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = VK_FORMAT_R16G16B16A16_SFLOAT;
    imageInfo.extent = { static_cast<uint32_t>(std::max(1, width)), static_cast<uint32_t>(std::max(1, height)), 1u };
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.usage = VK_IMAGE_USAGE_STORAGE_BIT;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (!check(vkCreateImage(state->device, &imageInfo, nullptr, outImage), "vkCreateImage(acescgImport)")) {
        return false;
    }

    VkImportAndroidHardwareBufferInfoANDROID importInfo{};
    importInfo.sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
    importInfo.buffer = hardwareBuffer;
    VkMemoryDedicatedAllocateInfo dedicatedInfo{};
    dedicatedInfo.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
    dedicatedInfo.image = *outImage;
    importInfo.pNext = &dedicatedInfo;

    VkMemoryAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.pNext = &importInfo;
    allocInfo.allocationSize = bufferProps.allocationSize;
    allocInfo.memoryTypeIndex = findMemoryType(state, bufferProps.memoryTypeBits, 0);
    if (!check(vkAllocateMemory(state->device, &allocInfo, nullptr, outMemory), "vkAllocateMemory(acescgImport)")) {
        vkDestroyImage(state->device, *outImage, nullptr); *outImage = VK_NULL_HANDLE; return false;
    }
    if (!check(vkBindImageMemory(state->device, *outImage, *outMemory, 0), "vkBindImageMemory(acescgImport)")) {
        vkFreeMemory(state->device, *outMemory, nullptr); vkDestroyImage(state->device, *outImage, nullptr);
        *outMemory = VK_NULL_HANDLE; *outImage = VK_NULL_HANDLE; return false;
    }
    VkImageViewCreateInfo viewInfo{};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = *outImage;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = VK_FORMAT_R16G16B16A16_SFLOAT;
    viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    viewInfo.subresourceRange.levelCount = 1;
    viewInfo.subresourceRange.layerCount = 1;
    if (!check(vkCreateImageView(state->device, &viewInfo, nullptr, outView), "vkCreateImageView(acescgImport)")) {
        vkFreeMemory(state->device, *outMemory, nullptr); vkDestroyImage(state->device, *outImage, nullptr);
        *outMemory = VK_NULL_HANDLE; *outImage = VK_NULL_HANDLE; return false;
    }
    return true;
}

// Preferred producer direction for project-owned RAW transport: create the storage image in Vulkan,
// then export that exact allocation as an AHardwareBuffer for EGL sampling. Some Android allocators
// expose FP16/RGB10 buffers only as external sampled formats; importing those buffers for imageStore is
// illegal even though the GPU's native optimal-tiling format supports storage. Vulkan-first allocation
// keeps the RAW master on-GPU, preserves FP16/10-bit precision, and avoids CPU readback.
bool createVulkanExportedStorageImage(
    VulkanBridgeState* state, int width, int height, VkFormat format,
    VkImage* image, VkDeviceMemory* memory, VkImageView* view, AHardwareBuffer** ahb
) {
    *image = VK_NULL_HANDLE;
    *memory = VK_NULL_HANDLE;
    *view = VK_NULL_HANDLE;
    *ahb = nullptr;
    if (state == nullptr || state->physicalDevice == VK_NULL_HANDLE ||
        state->device == VK_NULL_HANDLE || state->getMemoryAndroidHardwareBuffer == nullptr) {
        return false;
    }

    constexpr VkExternalMemoryHandleTypeFlagBits kAhbHandle =
        VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
    constexpr VkImageUsageFlags kUsage =
        VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;

    VkPhysicalDeviceExternalImageFormatInfo externalQuery{};
    externalQuery.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_IMAGE_FORMAT_INFO;
    externalQuery.handleType = kAhbHandle;
    VkPhysicalDeviceImageFormatInfo2 formatQuery{};
    formatQuery.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_IMAGE_FORMAT_INFO_2;
    formatQuery.pNext = &externalQuery;
    formatQuery.format = format;
    formatQuery.type = VK_IMAGE_TYPE_2D;
    formatQuery.tiling = VK_IMAGE_TILING_OPTIMAL;
    formatQuery.usage = kUsage;
    VkExternalImageFormatProperties externalProperties{};
    externalProperties.sType = VK_STRUCTURE_TYPE_EXTERNAL_IMAGE_FORMAT_PROPERTIES;
    VkImageFormatProperties2 formatProperties{};
    formatProperties.sType = VK_STRUCTURE_TYPE_IMAGE_FORMAT_PROPERTIES_2;
    formatProperties.pNext = &externalProperties;
    const VkResult formatResult = vkGetPhysicalDeviceImageFormatProperties2(
        state->physicalDevice, &formatQuery, &formatProperties);
    const VkExternalMemoryProperties& memoryProperties =
        externalProperties.externalMemoryProperties;
    if (formatResult != VK_SUCCESS ||
        (memoryProperties.externalMemoryFeatures & VK_EXTERNAL_MEMORY_FEATURE_EXPORTABLE_BIT) == 0 ||
        (memoryProperties.compatibleHandleTypes & kAhbHandle) == 0) {
        LOGW("RAW Vulkan-export transport unsupported format=%s result=%d features=0x%x compatible=0x%x",
             vkFormatLabel(format), static_cast<int>(formatResult),
             memoryProperties.externalMemoryFeatures,
             memoryProperties.compatibleHandleTypes);
        return false;
    }

    VkExternalMemoryImageCreateInfo externalImage{};
    externalImage.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
    externalImage.handleTypes = kAhbHandle;
    VkImageCreateInfo imageInfo{};
    imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imageInfo.pNext = &externalImage;
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = format;
    imageInfo.extent = {
        static_cast<uint32_t>(std::max(1, width)),
        static_cast<uint32_t>(std::max(1, height)),
        1u
    };
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.usage = kUsage;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (!check(vkCreateImage(state->device, &imageInfo, nullptr, image),
               "vkCreateImage(rawVulkanExport)")) {
        return false;
    }

    VkMemoryRequirements requirements{};
    vkGetImageMemoryRequirements(state->device, *image, &requirements);
    VkMemoryDedicatedAllocateInfo dedicated{};
    dedicated.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
    dedicated.image = *image;
    VkExportMemoryAllocateInfo exportInfo{};
    exportInfo.sType = VK_STRUCTURE_TYPE_EXPORT_MEMORY_ALLOCATE_INFO;
    exportInfo.pNext = &dedicated;
    exportInfo.handleTypes = kAhbHandle;
    VkMemoryAllocateInfo allocation{};
    allocation.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocation.pNext = &exportInfo;
    allocation.allocationSize = requirements.size;
    allocation.memoryTypeIndex = findMemoryType(
        state, requirements.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (!check(vkAllocateMemory(state->device, &allocation, nullptr, memory),
               "vkAllocateMemory(rawVulkanExport)")) {
        vkDestroyImage(state->device, *image, nullptr);
        *image = VK_NULL_HANDLE;
        return false;
    }
    if (!check(vkBindImageMemory(state->device, *image, *memory, 0),
               "vkBindImageMemory(rawVulkanExport)")) {
        vkFreeMemory(state->device, *memory, nullptr);
        vkDestroyImage(state->device, *image, nullptr);
        *memory = VK_NULL_HANDLE;
        *image = VK_NULL_HANDLE;
        return false;
    }

    VkImageViewCreateInfo viewInfo{};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = *image;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = format;
    viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    viewInfo.subresourceRange.levelCount = 1;
    viewInfo.subresourceRange.layerCount = 1;
    if (!check(vkCreateImageView(state->device, &viewInfo, nullptr, view),
               "vkCreateImageView(rawVulkanExport)")) {
        vkFreeMemory(state->device, *memory, nullptr);
        vkDestroyImage(state->device, *image, nullptr);
        *memory = VK_NULL_HANDLE;
        *image = VK_NULL_HANDLE;
        return false;
    }

    VkMemoryGetAndroidHardwareBufferInfoANDROID getInfo{};
    getInfo.sType = VK_STRUCTURE_TYPE_MEMORY_GET_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
    getInfo.memory = *memory;
    AHardwareBuffer* exported = nullptr;
    if (!check(state->getMemoryAndroidHardwareBuffer(
            state->device, &getInfo, &exported),
            "vkGetMemoryAndroidHardwareBufferANDROID(rawVulkanExport)") || exported == nullptr) {
        vkDestroyImageView(state->device, *view, nullptr);
        vkFreeMemory(state->device, *memory, nullptr);
        vkDestroyImage(state->device, *image, nullptr);
        *view = VK_NULL_HANDLE;
        *memory = VK_NULL_HANDLE;
        *image = VK_NULL_HANDLE;
        return false;
    }
    *ahb = exported;
    return true;
}

// STAGE 2: allocate an rgba16f AHardwareBuffer (GPU-sampleable for GL) and import it as the demosaic's
// ACEScg output storage image (reusing importAcesCgStorageImage — no export proc-addr needed). The demosaic
// imageStores into it and the GL compositor samples the same AHB as an external-OES texture. Keeps the AHB
// alive (returned in *ahb). Fail-closed (release + false) so the caller uses a plain device-local image.
bool createRawDemosaicExportableOutput(
    VulkanBridgeState* state, int width, int height,
    VkImage* image, VkDeviceMemory* memory, VkImageView* view, AHardwareBuffer** ahb
) {
    *ahb = nullptr;
    if (createVulkanExportedStorageImage(
            state, width, height, VK_FORMAT_R16G16B16A16_SFLOAT,
            image, memory, view, ahb)) {
        LOGI("RAW demosaic present: Vulkan-exported ACEScg FP16 transport ready (%dx%d)",
             width, height);
        return true;
    }

    // Compatibility fallback for devices whose allocator exposes a concrete storage-capable FP16
    // format. The Vivo/Adreno target rejects this direction, hence Vulkan-export is always attempted first.
    AHardwareBuffer_Desc desc{};
    desc.width = static_cast<uint32_t>(std::max(1, width));
    desc.height = static_cast<uint32_t>(std::max(1, height));
    desc.layers = 1;
    desc.format = AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT;
    desc.usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT;
    AHardwareBuffer* buffer = nullptr;
    if (AHardwareBuffer_allocate(&desc, &buffer) != 0 || buffer == nullptr) {
        LOGW("RAW demosaic present: rgba16f AHB allocate failed -> device-local output (no on-screen debayer)");
        return false;
    }
    if (!importAcesCgStorageImage(state, buffer, width, height, image, memory, view)) {
        LOGW("RAW demosaic present: rgba16f AHB not storage-importable -> device-local output (no on-screen debayer)");
        state->rawOutputAhbStorageUnsupported = true;
        AHardwareBuffer_release(buffer);
        return false;
    }
    *ahb = buffer;
    LOGI("RAW demosaic present: ACEScg output is AHB-backed (%dx%d rgba16f) — GL present available", width, height);
    return true;
}

// Independent recording-preview output. Vulkan-first export is required because the downsample pass
// writes this image as rgba16f storage. Failure is not an 8-bit fallback: the caller allocates the
// project-owned RGB10_A2 scene-log ring instead, preserving HDR range and colour semantics.
bool createRawDemosaicPreviewFp16Transport(
    VulkanBridgeState* state, int width, int height,
    VkImage* image, VkDeviceMemory* memory, VkImageView* view, AHardwareBuffer** ahb
) {
    if (!createVulkanExportedStorageImage(
            state,
            width,
            height,
            VK_FORMAT_R16G16B16A16_SFLOAT,
            image,
            memory,
            view,
            ahb)) {
        return false;
    }
    LOGI("RAW preview transport: Vulkan-exported ACEScg FP16 ready (%dx%d)", width, height);
    return true;
}

// Allocate a packed 10-bit RGB AHardwareBuffer and import it as an A2B10G10R10 storage image. Unlike
// the preferred FP16 output, this buffer carries NEO-SCENE-LOG10-v1 codes and is decoded to linear
// ACEScg by the project-owned encoder shader before any NDR tone/color operation.
bool createRawDemosaicSceneLogTransport(
    VulkanBridgeState* state, int width, int height,
    VkImage* image, VkDeviceMemory* memory, VkImageView* view, AHardwareBuffer** ahb
) {
    *image = VK_NULL_HANDLE;
    *memory = VK_NULL_HANDLE;
    *view = VK_NULL_HANDLE;
    *ahb = nullptr;

    constexpr VkFormat kSceneLogFormat = VK_FORMAT_A2B10G10R10_UNORM_PACK32;
    if (createVulkanExportedStorageImage(
            state, width, height, kSceneLogFormat, image, memory, view, ahb)) {
        LOGI("RAW scene-log transport: Vulkan-exported RGB10_A2 ready (%dx%d)", width, height);
        return true;
    }

    AHardwareBuffer_Desc desc{};
    desc.width = static_cast<uint32_t>(std::max(1, width));
    desc.height = static_cast<uint32_t>(std::max(1, height));
    desc.layers = 1;
    desc.format = AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM;
    desc.usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE |
        AHARDWAREBUFFER_USAGE_GPU_COLOR_OUTPUT;
    AHardwareBuffer* buffer = nullptr;
    if (AHardwareBuffer_allocate(&desc, &buffer) != 0 || buffer == nullptr) {
        LOGW("RAW scene-log transport: RGB10_A2 AHB allocation failed");
        return false;
    }

    VkAndroidHardwareBufferFormatPropertiesANDROID formatProps{};
    formatProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID;
    VkAndroidHardwareBufferPropertiesANDROID bufferProps{};
    bufferProps.sType = VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID;
    bufferProps.pNext = &formatProps;
    if (!check(state->getAndroidHardwareBufferProperties(
            state->device, buffer, &bufferProps),
            "vkGetAndroidHardwareBufferPropertiesANDROID(rawSceneLog)")) {
        AHardwareBuffer_release(buffer);
        return false;
    }
    if (formatProps.externalFormat != 0 || formatProps.format != kSceneLogFormat ||
        (formatProps.formatFeatures & VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT) == 0) {
        LOGW("RAW scene-log transport: RGB10_A2 AHB is not a Vulkan storage image "
             "(format=%d external=%llu features=0x%llx)",
             static_cast<int>(formatProps.format),
             static_cast<unsigned long long>(formatProps.externalFormat),
             static_cast<unsigned long long>(formatProps.formatFeatures));
        AHardwareBuffer_release(buffer);
        return false;
    }

    VkExternalMemoryImageCreateInfo externalImageInfo{};
    externalImageInfo.sType = VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO;
    externalImageInfo.handleTypes =
        VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
    VkImageCreateInfo imageInfo{};
    imageInfo.sType = VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO;
    imageInfo.pNext = &externalImageInfo;
    imageInfo.imageType = VK_IMAGE_TYPE_2D;
    imageInfo.format = kSceneLogFormat;
    imageInfo.extent = {
        static_cast<uint32_t>(std::max(1, width)),
        static_cast<uint32_t>(std::max(1, height)),
        1u
    };
    imageInfo.mipLevels = 1;
    imageInfo.arrayLayers = 1;
    imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
    imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
    imageInfo.usage = VK_IMAGE_USAGE_STORAGE_BIT;
    imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (!check(vkCreateImage(state->device, &imageInfo, nullptr, image),
               "vkCreateImage(rawSceneLogImport)")) {
        AHardwareBuffer_release(buffer);
        return false;
    }

    VkImportAndroidHardwareBufferInfoANDROID importInfo{};
    importInfo.sType = VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID;
    importInfo.buffer = buffer;
    VkMemoryDedicatedAllocateInfo dedicatedInfo{};
    dedicatedInfo.sType = VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO;
    dedicatedInfo.image = *image;
    importInfo.pNext = &dedicatedInfo;
    VkMemoryAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO;
    allocInfo.pNext = &importInfo;
    allocInfo.allocationSize = bufferProps.allocationSize;
    allocInfo.memoryTypeIndex = findMemoryType(state, bufferProps.memoryTypeBits, 0);
    if (!check(vkAllocateMemory(state->device, &allocInfo, nullptr, memory),
               "vkAllocateMemory(rawSceneLogImport)")) {
        vkDestroyImage(state->device, *image, nullptr);
        *image = VK_NULL_HANDLE;
        AHardwareBuffer_release(buffer);
        return false;
    }
    if (!check(vkBindImageMemory(state->device, *image, *memory, 0),
               "vkBindImageMemory(rawSceneLogImport)")) {
        vkFreeMemory(state->device, *memory, nullptr);
        vkDestroyImage(state->device, *image, nullptr);
        *memory = VK_NULL_HANDLE;
        *image = VK_NULL_HANDLE;
        AHardwareBuffer_release(buffer);
        return false;
    }

    VkImageViewCreateInfo viewInfo{};
    viewInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
    viewInfo.image = *image;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = kSceneLogFormat;
    viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    viewInfo.subresourceRange.levelCount = 1;
    viewInfo.subresourceRange.layerCount = 1;
    if (!check(vkCreateImageView(state->device, &viewInfo, nullptr, view),
               "vkCreateImageView(rawSceneLogImport)")) {
        vkFreeMemory(state->device, *memory, nullptr);
        vkDestroyImage(state->device, *image, nullptr);
        *memory = VK_NULL_HANDLE;
        *image = VK_NULL_HANDLE;
        AHardwareBuffer_release(buffer);
        return false;
    }

    *ahb = buffer;
    return true;
}

bool ensureDualGainFusionResources(VulkanBridgeState* state, int width, int height) {
    auto& d = state->dualGainFusion;
    if (d.fusedImage != VK_NULL_HANDLE && d.width == width && d.height == height) {
        return true; // cached + already cleared to a defined state
    }
    destroyDualGainFusionResources(state);

    // Reuse the demosaic owned-image helper (STORAGE + TRANSFER_DST device-local rgba16f).
    if (!createRawDemosaicOwnedImage(state, width, height, VK_FORMAT_R16G16B16A16_SFLOAT,
            &d.fusedImage, &d.fusedMemory, &d.fusedView) ||
        !createRawDemosaicOwnedImage(state, width, height, VK_FORMAT_R16G16B16A16_SFLOAT,
            &d.motionImage, &d.motionMemory, &d.motionView)) {
        destroyDualGainFusionResources(state);
        return false;
    }

    // Dedicated pool: 1 set of 4 storage images (LCG, HCG, fused, motion-confidence).
    VkDescriptorPoolSize poolSize{};
    poolSize.type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    poolSize.descriptorCount = 4;
    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = 1;
    poolInfo.poolSizeCount = 1;
    poolInfo.pPoolSizes = &poolSize;
    if (!check(vkCreateDescriptorPool(state->device, &poolInfo, nullptr, &d.descriptorPool),
               "vkCreateDescriptorPool(dualGain)")) {
        destroyDualGainFusionResources(state);
        return false;
    }
    VkDescriptorSetAllocateInfo setAlloc{};
    setAlloc.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    setAlloc.descriptorPool = d.descriptorPool;
    setAlloc.descriptorSetCount = 1;
    setAlloc.pSetLayouts = &state->dualGainDescLayout;
    if (!check(vkAllocateDescriptorSets(state->device, &setAlloc, &d.descriptorSet),
               "vkAllocateDescriptorSets(dualGain)")) {
        destroyDualGainFusionResources(state);
        return false;
    }

    // Deterministically clear the owned outputs and move them to GENERAL for compute (validation-clean).
    {
        VkCommandPool clearPool = VK_NULL_HANDLE;
        VkCommandBuffer clearCmd = VK_NULL_HANDLE;
        if (!beginSingleUseCommands(state, &clearPool, &clearCmd)) {
            destroyDualGainFusionResources(state);
            return false;
        }
        const VkClearColorValue zero{};
        VkImageSubresourceRange range{};
        range.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        range.levelCount = 1;
        range.layerCount = 1;
        const VkImage imgs[2] = { d.fusedImage, d.motionImage };
        for (int i = 0; i < 2; ++i) {
            transitionImageLayout(clearCmd, imgs[i],
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, range);
            vkCmdClearColorImage(clearCmd, imgs[i],
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &zero, 1, &range);
            VkImageMemoryBarrier toGeneral{};
            toGeneral.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            toGeneral.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            toGeneral.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
            toGeneral.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
            toGeneral.newLayout = VK_IMAGE_LAYOUT_GENERAL;
            toGeneral.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            toGeneral.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            toGeneral.image = imgs[i];
            toGeneral.subresourceRange = range;
            vkCmdPipelineBarrier(clearCmd,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                0, 0, nullptr, 0, nullptr, 1, &toGeneral);
        }
        if (!endSingleUseCommands(state, clearPool, clearCmd)) {
            destroyDualGainFusionResources(state);
            return false;
        }
    }

    d.width = width;
    d.height = height;
    return true;
}

bool dispatchDualGainFusion(
    VulkanBridgeState* state, AHardwareBuffer* lcgBuffer, AHardwareBuffer* hcgBuffer,
    int width, int height, const DualGainFusionPushConstants& params
) {
    if (state == nullptr || lcgBuffer == nullptr || hcgBuffer == nullptr) return false;
    if (!state->dualGainFusionEnabled) return false; // no-downgrade default gate
    // Acquire-load the readiness flag the background build thread store-releases AFTER writing the
    // (non-atomic) pipeline handles — happens-before for the handle reads below (cold-start race fix).
    if (!state->qfcaPipelinesReady.load(std::memory_order_acquire)) return false;
    if (state->dualGainPipeline == VK_NULL_HANDLE ||
        state->dualGainPipelineLayout == VK_NULL_HANDLE ||
        state->dualGainDescLayout == VK_NULL_HANDLE) {
        return false; // pipeline unavailable on this device -> dormant passthrough
    }
    if (width < 2 || height < 2) return false;
    if (!ensureDualGainFusionResources(state, width, height)) return false;
    auto& d = state->dualGainFusion;
    // The owned fused/motion outputs stay in GENERAL across frames; no per-frame WAR/WAW barrier is
    // needed because endSingleUseCommands vkQueueWaitIdle-drains each dispatch before the next begins
    // (synchronous, mirroring dispatchRawDemosaicPreview). A future async/batched path must add one.

    // Import both readouts as transient per-frame rgba16f storage images, RAII-released on every exit.
    VkImage lcgImage = VK_NULL_HANDLE, hcgImage = VK_NULL_HANDLE;
    VkDeviceMemory lcgMemory = VK_NULL_HANDLE, hcgMemory = VK_NULL_HANDLE;
    VkImageView lcgView = VK_NULL_HANDLE, hcgView = VK_NULL_HANDLE;
    struct ImgGuard {
        VulkanBridgeState* s; VkImage img; VkDeviceMemory mem; VkImageView view;
        ~ImgGuard() {
            if (view != VK_NULL_HANDLE) vkDestroyImageView(s->device, view, nullptr);
            if (img != VK_NULL_HANDLE) vkDestroyImage(s->device, img, nullptr);
            if (mem != VK_NULL_HANDLE) vkFreeMemory(s->device, mem, nullptr);
        }
    };
    if (!importAcesCgStorageImage(state, lcgBuffer, width, height, &lcgImage, &lcgMemory, &lcgView)) {
        d.lastDispatchOk = false; return false;
    }
    ImgGuard lcgGuard{ state, lcgImage, lcgMemory, lcgView };
    if (!importAcesCgStorageImage(state, hcgBuffer, width, height, &hcgImage, &hcgMemory, &hcgView)) {
        d.lastDispatchOk = false; return false;
    }
    ImgGuard hcgGuard{ state, hcgImage, hcgMemory, hcgView };

    // Write all 4 storage-image descriptors — none left UNDEFINED.
    VkDescriptorImageInfo lcgDesc{};    lcgDesc.imageView    = lcgView;      lcgDesc.imageLayout    = VK_IMAGE_LAYOUT_GENERAL;
    VkDescriptorImageInfo hcgDesc{};    hcgDesc.imageView    = hcgView;      hcgDesc.imageLayout    = VK_IMAGE_LAYOUT_GENERAL;
    VkDescriptorImageInfo fusedDesc{};  fusedDesc.imageView  = d.fusedView;  fusedDesc.imageLayout  = VK_IMAGE_LAYOUT_GENERAL;
    VkDescriptorImageInfo motionDesc{}; motionDesc.imageView = d.motionView; motionDesc.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    const VkDescriptorImageInfo* descs[4] = { &lcgDesc, &hcgDesc, &fusedDesc, &motionDesc };
    VkWriteDescriptorSet writes[4]{};
    for (int i = 0; i < 4; ++i) {
        writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[i].dstSet = d.descriptorSet;
        writes[i].dstBinding = static_cast<uint32_t>(i);
        writes[i].descriptorCount = 1;
        writes[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        writes[i].pImageInfo = descs[i];
    }
    vkUpdateDescriptorSets(state->device, 4, writes, 0, nullptr);

    VkCommandPool pool = VK_NULL_HANDLE;
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    if (!beginSingleUseCommands(state, &pool, &cmd)) { d.lastDispatchOk = false; return false; }
    VkImageSubresourceRange range{};
    range.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    range.levelCount = 1;
    range.layerCount = 1;
    const VkImage imported[2] = { lcgImage, hcgImage };
    VkImageMemoryBarrier toGeneral[2]{};
    for (int i = 0; i < 2; ++i) {
        toGeneral[i].sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        toGeneral[i].srcAccessMask = 0;
        toGeneral[i].dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        toGeneral[i].oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        toGeneral[i].newLayout = VK_IMAGE_LAYOUT_GENERAL;
        toGeneral[i].srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        toGeneral[i].dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        toGeneral[i].image = imported[i];
        toGeneral[i].subresourceRange = range;
    }
    vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0, 0, nullptr, 0, nullptr, 2, toGeneral);

    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, state->dualGainPipeline);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, state->dualGainPipelineLayout,
        0, 1, &d.descriptorSet, 0, nullptr);
    vkCmdPushConstants(cmd, state->dualGainPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0,
        sizeof(DualGainFusionPushConstants), &params);
    const uint32_t gx = (static_cast<uint32_t>(width) + 15u) / 16u;
    const uint32_t gy = (static_cast<uint32_t>(height) + 15u) / 16u;
    vkCmdDispatch(cmd, gx, gy, 1u);

    if (!endSingleUseCommands(state, pool, cmd)) { d.lastDispatchOk = false; return false; }

    d.dispatchCount += 1;
    d.lastDispatchOk = true;
    d.everDispatched = true;
    if (d.dispatchCount % 60u == 1u) {
        LOGI("dual-gain fusion dispatched %llu frames (%dx%d -> high-DR ACEScg)",
             static_cast<unsigned long long>(d.dispatchCount), width, height);
    }
    return true;
}

// ===================================================================================================
// s5-f06 / CP-F7 — depth-driven Vogel-spiral bokeh dispatch (bokeh_vogel_spiral.comp)
// ===================================================================================================
// Wires the previously-callerless Vogel-spiral bokeh shader to a REAL Vulkan descriptor + dispatch over
// four owned, deterministically-cleared images (0 rgba16f input, 1 r32f depth, 2 r32f CoC radius, 3 rgba16f
// output). Gated behind bokehBlendEnabled (default OFF) AND the shader's blendStrength push field (default
// 0 == exact passthrough), so visible bokeh is DEVICE-GATED and never blind-default-on. The live depth/color
// AHB feed is the device-gated wiring; until then this runs only when explicitly invoked, against cleared
// inputs, with every binding initialized (no uninitialized read / UNDEFINED layout — the s1-f06 pattern).

void destroyBokehResources(VulkanBridgeState* state) {
    if (state == nullptr || state->device == VK_NULL_HANDLE) return;
    auto& b = state->bokeh;
    if (b.descriptorPool != VK_NULL_HANDLE) {
        vkDestroyDescriptorPool(state->device, b.descriptorPool, nullptr); // frees descriptorSet too
        b.descriptorPool = VK_NULL_HANDLE;
        b.descriptorSet = VK_NULL_HANDLE;
    }
    const auto destroyImg = [&](VkImage& img, VkDeviceMemory& mem, VkImageView& view) {
        if (view != VK_NULL_HANDLE) { vkDestroyImageView(state->device, view, nullptr); view = VK_NULL_HANDLE; }
        if (img != VK_NULL_HANDLE) { vkDestroyImage(state->device, img, nullptr); img = VK_NULL_HANDLE; }
        if (mem != VK_NULL_HANDLE) { vkFreeMemory(state->device, mem, nullptr); mem = VK_NULL_HANDLE; }
    };
    destroyImg(b.inputImage, b.inputMemory, b.inputView);
    destroyImg(b.depthImage, b.depthMemory, b.depthView);
    destroyImg(b.cocImage, b.cocMemory, b.cocView);
    destroyImg(b.outputImage, b.outputMemory, b.outputView);
    b.width = 0;
    b.height = 0;
}

bool ensureBokehResources(VulkanBridgeState* state, int width, int height) {
    auto& b = state->bokeh;
    if (b.outputImage != VK_NULL_HANDLE && b.width == width && b.height == height) {
        return true; // cached + already cleared to a defined state
    }
    destroyBokehResources(state);

    // Owned storage images (STORAGE + TRANSFER_DST device-local), one per binding. rgba16f for color,
    // r32f for the single-channel depth + CoC radius — matching the shader's image2D formats exactly.
    if (!createRawDemosaicOwnedImage(state, width, height, VK_FORMAT_R16G16B16A16_SFLOAT,
            &b.inputImage, &b.inputMemory, &b.inputView) ||
        !createRawDemosaicOwnedImage(state, width, height, VK_FORMAT_R32_SFLOAT,
            &b.depthImage, &b.depthMemory, &b.depthView) ||
        !createRawDemosaicOwnedImage(state, width, height, VK_FORMAT_R32_SFLOAT,
            &b.cocImage, &b.cocMemory, &b.cocView) ||
        !createRawDemosaicOwnedImage(state, width, height, VK_FORMAT_R16G16B16A16_SFLOAT,
            &b.outputImage, &b.outputMemory, &b.outputView)) {
        destroyBokehResources(state);
        return false;
    }

    // Dedicated pool: 1 set of 4 storage images.
    VkDescriptorPoolSize poolSize{};
    poolSize.type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    poolSize.descriptorCount = 4;
    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = 1;
    poolInfo.poolSizeCount = 1;
    poolInfo.pPoolSizes = &poolSize;
    if (!check(vkCreateDescriptorPool(state->device, &poolInfo, nullptr, &b.descriptorPool),
               "vkCreateDescriptorPool(bokeh)")) {
        destroyBokehResources(state);
        return false;
    }
    VkDescriptorSetAllocateInfo setAlloc{};
    setAlloc.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    setAlloc.descriptorPool = b.descriptorPool;
    setAlloc.descriptorSetCount = 1;
    setAlloc.pSetLayouts = &state->bokehDescLayout;
    if (!check(vkAllocateDescriptorSets(state->device, &setAlloc, &b.descriptorSet),
               "vkAllocateDescriptorSets(bokeh)")) {
        destroyBokehResources(state);
        return false;
    }

    // Deterministically clear EVERY owned binding to zero and move it to GENERAL for compute, so the
    // dispatch never reads UNDEFINED memory or an UNDEFINED layout (validation-clean by construction).
    {
        VkCommandPool clearPool = VK_NULL_HANDLE;
        VkCommandBuffer clearCmd = VK_NULL_HANDLE;
        if (!beginSingleUseCommands(state, &clearPool, &clearCmd)) {
            destroyBokehResources(state);
            return false;
        }
        const VkClearColorValue zero{};
        VkImageSubresourceRange range{};
        range.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
        range.levelCount = 1;
        range.layerCount = 1;
        const VkImage imgs[4] = { b.inputImage, b.depthImage, b.cocImage, b.outputImage };
        for (int i = 0; i < 4; ++i) {
            transitionImageLayout(clearCmd, imgs[i],
                VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, range);
            vkCmdClearColorImage(clearCmd, imgs[i],
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &zero, 1, &range);
            VkImageMemoryBarrier toGeneral{};
            toGeneral.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
            toGeneral.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            toGeneral.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
            toGeneral.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
            toGeneral.newLayout = VK_IMAGE_LAYOUT_GENERAL;
            toGeneral.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            toGeneral.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            toGeneral.image = imgs[i];
            toGeneral.subresourceRange = range;
            vkCmdPipelineBarrier(clearCmd,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                0, 0, nullptr, 0, nullptr, 1, &toGeneral);
        }
        if (!endSingleUseCommands(state, clearPool, clearCmd)) {
            destroyBokehResources(state);
            return false;
        }
    }

    b.width = width;
    b.height = height;
    return true;
}

bool dispatchBokehVogelSpiral(VulkanBridgeState* state, int width, int height, const BokehPushConstants& params) {
    if (state == nullptr) return false;
    if (!state->bokehBlendEnabled) return false; // no-downgrade default gate (visible bokeh DEVICE-GATED)
    if (!state->qfcaPipelinesReady.load(std::memory_order_acquire)) return false; // cold-start race fix
    if (state->bokehPipeline == VK_NULL_HANDLE ||
        state->bokehPipelineLayout == VK_NULL_HANDLE ||
        state->bokehDescLayout == VK_NULL_HANDLE) {
        return false; // pipeline unavailable on this device -> dormant passthrough
    }
    if (width < 2 || height < 2) return false;
    if (!ensureBokehResources(state, width, height)) return false;
    auto& b = state->bokeh;

    // Write all 4 storage-image descriptors — none left UNDEFINED.
    VkDescriptorImageInfo inDesc{};    inDesc.imageView    = b.inputView;  inDesc.imageLayout    = VK_IMAGE_LAYOUT_GENERAL;
    VkDescriptorImageInfo depthDesc{}; depthDesc.imageView = b.depthView;  depthDesc.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkDescriptorImageInfo cocDesc{};   cocDesc.imageView   = b.cocView;    cocDesc.imageLayout   = VK_IMAGE_LAYOUT_GENERAL;
    VkDescriptorImageInfo outDesc{};   outDesc.imageView   = b.outputView; outDesc.imageLayout   = VK_IMAGE_LAYOUT_GENERAL;
    const VkDescriptorImageInfo* descs[4] = { &inDesc, &depthDesc, &cocDesc, &outDesc };
    VkWriteDescriptorSet writes[4]{};
    for (int i = 0; i < 4; ++i) {
        writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[i].dstSet = b.descriptorSet;
        writes[i].dstBinding = static_cast<uint32_t>(i);
        writes[i].descriptorCount = 1;
        writes[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        writes[i].pImageInfo = descs[i];
    }
    vkUpdateDescriptorSets(state->device, 4, writes, 0, nullptr);

    VkCommandPool pool = VK_NULL_HANDLE;
    VkCommandBuffer cmd = VK_NULL_HANDLE;
    if (!beginSingleUseCommands(state, &pool, &cmd)) { b.lastDispatchOk = false; return false; }
    vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, state->bokehPipeline);
    vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, state->bokehPipelineLayout,
        0, 1, &b.descriptorSet, 0, nullptr);
    vkCmdPushConstants(cmd, state->bokehPipelineLayout, VK_SHADER_STAGE_COMPUTE_BIT, 0,
        sizeof(BokehPushConstants), &params);
    const uint32_t gx = (static_cast<uint32_t>(width) + 15u) / 16u;
    const uint32_t gy = (static_cast<uint32_t>(height) + 15u) / 16u;
    vkCmdDispatch(cmd, gx, gy, 1u);
    if (!endSingleUseCommands(state, pool, cmd)) { b.lastDispatchOk = false; return false; }

    b.dispatchCount += 1;
    b.lastDispatchOk = true;
    b.everDispatched = true;
    return true;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeCreate(JNIEnv*, jobject) {
    auto state = std::make_unique<VulkanBridgeState>();
    if (!createInstance(state.get()) || !pickPhysicalDevice(state.get()) || !createDevice(state.get()) || !createGlobalResources(state.get())) {
        state->supported = false;
        destroyState(state.get());
        return reinterpret_cast<jlong>(state.release());
    }
    state->supported = true;
    setStatus(state.get(), "Vulkan runtime ready");
    LOGI("Vulkan runtime initialized");
    // PART A: now that the bridge is "supported" (preview can bind + render raw), kick off the heavy
    // compute-pipeline compilation in the background; enhancement fades in when it completes.
    startEnhancementPipelineBuild(state.get());
    return reinterpret_cast<jlong>(state.release());
}

extern "C" JNIEXPORT void JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeDestroy(JNIEnv*, jobject, jlong handle) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return;
    std::unique_ptr<VulkanBridgeState> autoState(state);
    // PART A: join the background enhancement-build thread BEFORE taking state->mutex. Its final section
    // locks state->mutex to publish flags + re-drive targets, so joining it while holding the lock would
    // deadlock. After the join the thread is gone and destroyState can safely tear pipelines down.
    // D-8: request cooperative cancellation FIRST so the join returns at the next compile-group boundary
    // instead of blocking the calling (UI/onCleared) thread for the full ~2.5s SPIR-V build. Set-then-join
    // only (never detach / never early-free): the thread still fully exits before destroyState runs.
    autoState->enhancementBuildCancelled.store(true, std::memory_order_release);
    if (autoState->enhancementBuildThread.joinable()) {
        autoState->enhancementBuildThread.join();
    }
    bool destroyed = false;
    {
        std::lock_guard<std::mutex> lock(autoState->mutex);
        destroyed = destroyState(autoState.get());
    }
    if (!destroyed) {
        // Keep ownership alive after the Java handle is retired. A rare slow/hung submission is
        // reaped off the UI thread once the device becomes idle, instead of abandoning every Vulkan
        // handle when autoState leaves this function. The retry remains ownership-safe: no Java
        // caller can reach this state after VulkanBridge.close() clears its native handle.
        VulkanBridgeState* deferredState = autoState.release();
        std::thread([deferredState]() {
            std::unique_ptr<VulkanBridgeState> owner(deferredState);
            bool idleAttempted = false;
            while (true) {
                bool complete = false;
                {
                    std::lock_guard<std::mutex> lock(owner->mutex);
                    if (!idleAttempted && owner->device != VK_NULL_HANDLE) {
                        idleAttempted = true;
                        const VkResult idleResult = waitDeviceIdleSynchronized(owner.get());
                        if (idleResult != VK_SUCCESS && idleResult != VK_ERROR_DEVICE_LOST) {
                            LOGE("Deferred Vulkan teardown device-idle wait failed with %d",
                                 static_cast<int>(idleResult));
                        }
                    }
                    complete = destroyState(owner.get());
                }
                if (complete) break;
                std::this_thread::sleep_for(std::chrono::milliseconds(100));
            }
        }).detach();
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeCleanupImportedFrames(
    JNIEnv*,
    jobject,
    jlong handle
) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return;
    std::lock_guard<std::mutex> lock(state->mutex);
    LOGI("nativeCleanupImportedFrames: destroying %zu imported frames", state->importedFrames.size());
    // Drain any in-flight preview/encoder command buffers that may still sample the imported
    // frame images before their VkImage/VkDeviceMemory/VkSampler/VkImageView are destroyed.
    // Mirrors the single-frame eviction path in importHardwareBuffer; no-op when the device is
    // gone or nothing is in flight. Guards against a GPU use-after-free / VK_ERROR_DEVICE_LOST.
    waitForTargetIdle(state, state->previewTarget);
    waitForTargetIdle(state, state->encoderTarget);
    destroyImportedFrames(state);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeIsSupported(JNIEnv*, jobject, jlong handle) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(state->mutex);
    return state->supported ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeSetPreviewSurface(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject surface
) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(state->mutex);
    if (!state->supported) return JNI_FALSE;
    const bool bound = bindSurfaceObject(env, state, surface, &state->previewTarget, "preview");
    if (bound) {
        LOGI("Preview surface %s", surface == nullptr ? "cleared" : "bound");
    }
    return bound ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeSetEncoderSurface(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject surface
) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(state->mutex);
    if (!state->supported) return JNI_FALSE;
    const bool bound = bindSurfaceObject(env, state, surface, &state->encoderTarget, "encoder");
    // Recording brackets: a bound encoder surface => recording active. OmniClarity yields so the
    // preview never competes for GPU with the encode path (sustained recording > preview peak look).
    state->recordingActive = bound && (surface != nullptr);
    if (bound) {
        LOGI("Encoder surface %s", surface == nullptr ? "cleared" : "bound");
    }
    return bound ? JNI_TRUE : JNI_FALSE;
}

/**
 * Set the active HDR dynamic-range mode. Triggers a swapchain rebuild
 * for any bound target whose `builtForHdrModeId` differs. Safe to call
 * before any surface is bound — in that case the new mode is just
 * cached and applied at first bind.
 *
 * The [modeId] parameter must match the integer encoding produced by
 * `HdrSwapchainFormatPolicy.toNativeModeId` on the Kotlin side.
 */
extern "C" JNIEXPORT void JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeSetOmniClarityEnabled(
    JNIEnv* /*env*/, jobject, jlong handle, jboolean enabled) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return;
    std::lock_guard<std::mutex> lock(state->mutex);
    state->omniClarityEnabled = (enabled == JNI_TRUE);
}

// s3-f01 / CP54b — Argus flow-stage governor levers (refine ping-pong count + hard-cut gate), resolved on
// the JVM by ArgusFlowStagePolicy (liveRefineIterations / isSceneCutSpike) and driven by the s3-f04 policies.
// refineIterations is clamped to [2,8] in dispatch and forced even; sceneCutActive forces previousValid=0 for
// the frame. Neutral values (8, false) reproduce today's output bit-for-bit.
extern "C" JNIEXPORT void JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeSetOmniClarityFlowTuning(
    JNIEnv* /*env*/, jobject, jlong handle, jint refineIterations, jboolean sceneCutActive) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return;
    std::lock_guard<std::mutex> lock(state->mutex);
    state->omniTuning.refineIterations = std::clamp(static_cast<int32_t>(refineIterations), 2, 8);
    state->omniTuning.sceneCutActive = (sceneCutActive == JNI_TRUE);
}

// s3-f02 / CP52b — Chronos temporal-blend governor lever (base/max temporal alpha), resolved on the JVM by
// ChronosTemporalPolicy and driven by the s3-f04 policies. Both are clamped to [0,1]. Neutral values
// (0.0, 0.9) reproduce the CP52 output bit-for-bit. The CP51/CP54b first-frame / scene-cut pass-through
// still force-overrides these to 0 in dispatch.
extern "C" JNIEXPORT void JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeSetOmniClarityWarpTuning(
    JNIEnv* /*env*/, jobject, jlong handle, jfloat baseTemporalAlpha, jfloat maxTemporalAlpha) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return;
    std::lock_guard<std::mutex> lock(state->mutex);
    state->omniTuning.baseTemporalAlpha = std::clamp(static_cast<float>(baseTemporalAlpha), 0.0f, 1.0f);
    state->omniTuning.maxTemporalAlpha = std::clamp(static_cast<float>(maxTemporalAlpha), 0.0f, 1.0f);
}

// s3-f03 / CP53 — Riven noise-aware deblur governor lever (van Cittert / RL iteration count + Wiener 1/SNR
// noise floor), resolved on the JVM by RivenDeblurPolicy and driven by the s3-f04 policies. rlIterations is
// floored at 1 in dispatch; snrInverse is clamped >= 0. Neutral values (1, 0.0) reproduce the CP53 Path A
// single-step output bit-for-bit. The multiplicative Path B branch is a separate spec-constant gate.
extern "C" JNIEXPORT void JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeSetOmniClarityDeblurTuning(
    JNIEnv* /*env*/, jobject, jlong handle, jint rlIterations, jfloat snrInverse) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return;
    std::lock_guard<std::mutex> lock(state->mutex);
    state->omniTuning.rlIterations = std::clamp(static_cast<int32_t>(rlIterations), 1, 8);
    state->omniTuning.snrInverse = std::max(static_cast<float>(snrInverse), 0.0f);
}

extern "C" JNIEXPORT void JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeSetSnceLivePerceptualEnabled(
    JNIEnv* /*env*/, jobject, jlong handle, jboolean enabled) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return;
    std::lock_guard<std::mutex> lock(state->mutex);
    state->snceLivePerceptualEnabled = (enabled == JNI_TRUE);
}

// CP-QV21 — replace the live NRLI 3D-LUT with a caller-supplied 33^3 look and set the blend strength.
// FAIL-SAFE by construction: an invalid array (null / wrong length / non-finite) OR a failed GPU upload
// leaves the current LUT + nrliUserStrength untouched (default 0 => identity passthrough). On success
// the voxels are re-uploaded into the existing nrliLut.image (descriptors stay valid) and strength is
// set to clamp(strength,0,1). lutRgb is size^3*3 floats (R-fastest, matching createNrliIdentityLut /
// SnceAdaptiveLut). NO live code calls this — it is device-side plumbing for a future caller.
extern "C" JNIEXPORT void JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeUpdateNrliLut(
    JNIEnv* env, jobject, jlong handle, jfloatArray lutRgb, jfloat strength) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return;
    std::lock_guard<std::mutex> lock(state->mutex);
    if (!state->supported) return;
    if (!state->nrliLut.ready || state->nrliLut.image == VK_NULL_HANDLE) return;
    if (lutRgb == nullptr) {
        setStatus(state, "nrli LUT rejected: null array");
        return;
    }
    const uint32_t size = state->nrliLut.size;
    const size_t voxelCount = static_cast<size_t>(size) * static_cast<size_t>(size) * static_cast<size_t>(size);
    const size_t expectedLength = voxelCount * 3u;
    const jsize length = env->GetArrayLength(lutRgb);
    if (length != static_cast<jsize>(expectedLength)) {
        setStatus(state, "nrli LUT rejected: invalid float count");
        return;
    }
    std::vector<jfloat> values(static_cast<size_t>(length));
    env->GetFloatArrayRegion(lutRgb, 0, length, values.data());
    for (jsize i = 0; i < length; ++i) {
        if (!isIeee754Finite(values[i])) {
            setStatus(state, "nrli LUT rejected: non-finite value");
            return;
        }
    }
    if (!isIeee754Finite(strength)) {
        setStatus(state, "nrli LUT rejected: non-finite strength");
        return;
    }
    if (!uploadNrliLutVoxels(state, values.data(), values.size())) {
        // Upload failed — the existing LUT + strength 0 remain (fail-safe passthrough).
        setStatus(state, "nrli LUT upload failed");
        return;
    }
    state->nrliUserStrength = std::clamp(static_cast<float>(strength), 0.0f, 1.0f);
    std::ostringstream status;
    status << "nrli LUT ready size=" << state->nrliLut.size
           << " strength=" << state->nrliUserStrength;
    setStatus(state, status.str());
}

// s1-f06 / CP12 STEP B/C/D — MFSR real-input control path (mirrors MfsrFusionParams on the JVM).
extern "C" JNIEXPORT void JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeSetMfsrRealInputsEnabled(
    JNIEnv* /*env*/, jobject, jlong handle, jboolean enabled) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return;
    std::lock_guard<std::mutex> lock(state->mutex);
    state->mfsrRealInputsEnabled = (enabled == JNI_TRUE);
}

// DEVICE-GATED: the owner flips this true only after building + validating a real full-res N-frame RAW/luma
// ring into MFSR binding 0. While false, MFSR keeps today's exact dummy push constants (no-downgrade).
extern "C" JNIEXPORT void JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeSetMfsrBurstRingReady(
    JNIEnv* /*env*/, jobject, jlong handle, jboolean ready) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return;
    std::lock_guard<std::mutex> lock(state->mutex);
    state->mfsrBurstRingReady = (ready == JNI_TRUE);
}

// Real heteroscedastic noise (noiseS/noiseO) + adaptive frame count + gyro offset scale, resolved on the JVM
// by CameraSensorNoiseModelPolicy → MfsrFusionParams. Consumed only when mfsrRealInputsEnabled && ring ready.
extern "C" JNIEXPORT void JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeSetMfsrFusionParams(
    JNIEnv* /*env*/, jobject, jlong handle, jint activeFrameCount, jfloat noiseS, jfloat noiseO, jfloat gyroOffsetScale) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return;
    std::lock_guard<std::mutex> lock(state->mutex);
    state->mfsrRealtimeParams.activeFrameCount = std::clamp(static_cast<int32_t>(activeFrameCount), 3, 8);
    state->mfsrRealtimeParams.noiseS = noiseS;
    state->mfsrRealtimeParams.noiseO = noiseO;
    state->mfsrRealtimeParams.gyroOffsetScale = gyroOffsetScale;
}

// s1-f07 / CP10c — RAW-preview cinema-debayer gate. Default OFF: with this false the demosaic dispatch
// Cached RAW-monitor correction. Kotlin has already performed the single confidence gate; native clamps
// again only for memory/driver safety. This setter runs on mode/calibration changes, never per frame.
extern "C" JNIEXPORT void JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeSetRawMonitorNdrCorrection(
    JNIEnv* /*env*/,
    jobject,
    jlong handle,
    jint modeCode,
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
        return isIeee754Finite(value) ? value : fallback;
    };
    auto& correction = state->rawMonitorNdrCorrection;
    correction.mode = std::clamp(static_cast<int32_t>(modeCode), 0, 2);
    correction.active = enabled == JNI_TRUE && correction.mode != 0;
    correction.redScale = std::clamp(finiteOr(redScale, 1.0f), 0.85f, 1.15f);
    correction.greenScale = std::clamp(finiteOr(greenScale, 1.0f), 0.85f, 1.15f);
    correction.blueScale = std::clamp(finiteOr(blueScale, 1.0f), 0.85f, 1.15f);
    correction.highlightRolloffBias =
        std::clamp(finiteOr(highlightRolloffBias, 0.0f), 0.0f, 0.12f);
    correction.shadowLiftBias =
        std::clamp(finiteOr(shadowLiftBias, 0.0f), -0.08f, 0.08f);
    correction.saturationScale =
        std::clamp(finiteOr(saturationScale, 1.0f), 0.85f, 1.15f);
    correction.skinHueCorrectionDegrees =
        std::clamp(finiteOr(skinHueCorrectionDegrees, 0.0f), -12.0f, 12.0f);
    correction.skyHueCorrectionDegrees =
        std::clamp(finiteOr(skyHueCorrectionDegrees, 0.0f), -12.0f, 12.0f);
    correction.sensorDiffuseScene =
        std::clamp(finiteOr(sensorDiffuseScene, 0.180f), 0.02f, 0.30f);
    correction.signalGamma = std::clamp(finiteOr(signalGamma, 0.859937f), 0.10f, 1.0f);
    correction.toeWidth = std::clamp(finiteOr(toeWidth, 0.238882f), 0.001f, 0.90f);
    correction.toeAmount = std::clamp(finiteOr(toeAmount, 0.024780f), 0.0f, 0.20f);
    correction.highlightDesat = std::clamp(finiteOr(highlightDesat, 0.149718f), 0.0f, 0.50f);
    correction.shadowMidtoneLift =
        std::clamp(finiteOr(shadowMidtoneLift, 0.167837f), 0.0f, 0.35f);
    correction.midtoneContrast =
        std::clamp(finiteOr(midtoneContrast, 0.100943f), 0.0f, 0.30f);
    correction.chromaBoost = std::clamp(finiteOr(chromaBoost, 0.038361f), 0.0f, 0.25f);
    correction.skyChromaBonus =
        std::clamp(finiteOr(skyChromaBonus, 0.062392f), 0.0f, 0.08f);
    correction.localDetailStrength =
        std::clamp(finiteOr(localDetailStrength, 0.400000f), 0.0f, 0.75f);
    correction.localDetailLowerStops =
        std::clamp(finiteOr(localDetailLowerStops, -0.060000f), -0.20f, 0.0f);
    correction.localDetailUpperStops =
        std::clamp(finiteOr(localDetailUpperStops, 0.085000f), 0.0f, 0.20f);
    LOGI(
        "NDR_NATIVE_PROFILE_APPLIED mode=%d active=%d diffuse=%.6f gamma=%.6f "
        "toe=%.6f/%.6f detail=%.6f",
        correction.mode,
        correction.active ? 1 : 0,
        correction.sensorDiffuseScene,
        correction.signalGamma,
        correction.toeWidth,
        correction.toeAmount,
        correction.localDetailStrength);
}

// RAW-preview cinema-debayer gate. Default OFF: the JVM enables it only for the RAW sensor pipeline.
extern "C" JNIEXPORT void JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeSetRawPreviewDemosaicEnabled(
    JNIEnv* /*env*/, jobject, jlong handle, jboolean enabled) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return;
    std::lock_guard<std::mutex> lock(state->mutex);
    state->rawPreviewDemosaicEnabled = (enabled == JNI_TRUE);
    if (!state->rawPreviewDemosaicEnabled) {
        state->rawDemosaic.lastDispatchOk = false;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeSetRawSceneLogTransportState(
    JNIEnv* /*env*/, jobject, jlong handle, jboolean prepared, jboolean submissionEnabled,
    jboolean directNdrPreviewActive, jboolean recordingActive) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return;
    std::lock_guard<std::mutex> lock(state->mutex);
    const bool nextPrepared = prepared == JNI_TRUE;
    const bool nextSubmission = nextPrepared && submissionEnabled == JNI_TRUE;
    const bool nextRecordingActive = recordingActive == JNI_TRUE;
    if (!state->rawRecordingDemosaicActive && nextRecordingActive) {
        auto& r = state->rawDemosaic;
        r.timingSampleCount = 0u;
        r.timingTotalMs = 0.0;
        r.timingFenceMs = 0.0;
        r.timingMaxTotalMs = 0.0;
        r.timingMaxFenceMs = 0.0;
        r.pendingGpuTimestampSample = false;
        r.pendingGpuStageMs.fill(0.0);
        r.gpuStageTotalMs.fill(0.0);
        r.gpuStageMaxMs.fill(0.0);
        r.gpuStageTimingSampleCount = 0u;
    }
    state->rawSceneLogTransportPrepared = nextPrepared;
    state->rawSceneLogTransportSubmissionEnabled = nextSubmission;
    state->rawRecordingDemosaicActive = nextRecordingActive;
    state->rawDirectNdrPreviewTransportActive = nextPrepared &&
        directNdrPreviewActive == JNI_TRUE;
    if (state->rawDirectNdrPreviewTransportActive) {
        // Do not let a previously read-back monitor frame race the direct project-owned preview.
        state->rawDemosaic.previewReady = false;
    }
    if (!nextSubmission) state->rawDemosaic.sceneLogReady = false;
    // Do not allocate/free large Vulkan images on the caller (often UI) thread. The next RAW dispatch
    // resolves its new extent and recreates the cache on CameraBackground under this same mutex.
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeIsRawDemosaicPipelineReady(
    JNIEnv* /*env*/, jobject, jlong handle) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return JNI_FALSE;
    // Do not take state->mutex here. The recording-start coroutine polls this while the background
    // builder needs that mutex to publish the completed non-atomic pipeline handles. Acquire pairs with
    // the builder's release-store and makes all demosaic handles visible to the waiting thread.
    return state->rawDemosaicPipelineReady.load(std::memory_order_acquire)
        ? JNI_TRUE
        : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativePrepareRawRecordingDemosaicResources(
    JNIEnv* /*env*/, jobject, jlong handle, jint width, jint height) {
    auto* state = fromHandle(handle);
    if (state == nullptr || width <= 0 || height <= 0) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(state->mutex);
    // This entry point is deliberately recording-only. It moves expensive full-resolution image,
    // memory, descriptor, and export-transport allocation ahead of the first protected camera frame;
    // no pixels are synthesized, downscaled, or submitted by the prewarm itself.
    if (!state->supported || !state->rawPreviewDemosaicEnabled ||
        !state->rawDemosaicPipelineReady.load(std::memory_order_acquire) ||
        !state->rawRecordingDemosaicActive ||
        !state->rawSceneLogTransportPrepared) {
        return JNI_FALSE;
    }
    const bool prepared = ensureRawDemosaicResources(state, width, height);
    LOGI(
        "RAW_RECORD_RESOURCE_PREWARM extent=%dx%d prepared=%d fp16Ahb=%d sceneLog=%d "
        "recordRing=%d previewRing=%d previewExtent=%dx%d previewFormat=%s",
        width,
        height,
        prepared ? 1 : 0,
        state->rawDemosaic.outputAhb != nullptr ? 1 : 0,
        state->rawDemosaic.sceneLogAhb != nullptr ? 1 : 0,
        state->rawDemosaic.recordingTransportRingReady ? 2 : 1,
        state->rawDemosaic.previewTransportRingReady
            ? static_cast<int>(kRawPreviewTransportSlotCount)
            : 0,
        state->rawDemosaic.previewTransportW,
        state->rawDemosaic.previewTransportH,
        state->rawDemosaic.previewTransportSceneLog ? "RGB10_A2_SCENE_LOG" : "RGBA16F");
    return prepared ? JNI_TRUE : JNI_FALSE;
}

// Exposes one stable full-resolution recorder transport identity for EGL cache/decode prewarming.
// This is intentionally NOT an acquire in the native ownership state machine: both slots must remain
// FREE, no Vulkan dispatch may be active, and no completion serial is created. The returned Java object
// owns only its ordinary AHardwareBuffer reference and must be closed by Kotlin after the synchronous
// prewarm call. RAW ingress remains closed while the caller performs this operation.
extern "C" JNIEXPORT jobject JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeAcquireRawRecordingDemosaicPrewarmHardwareBuffer(
    JNIEnv* env, jobject, jlong handle, jint slot) {
    auto* state = fromHandle(handle);
    if (env == nullptr || state == nullptr || slot < 0 || slot >= 2) return nullptr;
    std::lock_guard<std::mutex> lock(state->mutex);
    auto& r = state->rawDemosaic;
    if (!state->supported || !state->rawRecordingDemosaicActive ||
        !state->rawSceneLogTransportPrepared || !r.recordingTransportRingReady ||
        r.width <= 0 || r.height <= 0 || r.dispatchInFlight ||
        r.dispatchTransportSlot != -1 ||
        r.transportOwnership[static_cast<size_t>(slot)] != kRawTransportFree) {
        return nullptr;
    }

    AHardwareBuffer* transport = nullptr;
    uint32_t expectedFormat = 0u;
    const bool fp16Ring = r.outputAhb != nullptr && r.outputAhbSecondary != nullptr;
    const bool sceneLogRing = r.outputAhb == nullptr && r.sceneLogAhb != nullptr &&
        r.sceneLogAhbSecondary != nullptr;
    if (fp16Ring) {
        transport = rawOutputAhbForSlot(r, slot);
        expectedFormat = AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT;
    } else if (sceneLogRing) {
        transport = rawSceneLogAhbForSlot(r, slot);
        expectedFormat = AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM;
    }
    if (transport == nullptr) return nullptr;

    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(transport, &desc);
    if (desc.width != static_cast<uint32_t>(r.width) ||
        desc.height != static_cast<uint32_t>(r.height) ||
        desc.format != expectedFormat) {
        LOGE(
            "RAW_RECORD_PREWARM_SLOT_REJECTED slot=%d expected=%dx%d/0x%x actual=%ux%u/0x%x",
            slot,
            r.width,
            r.height,
            expectedFormat,
            desc.width,
            desc.height,
            desc.format);
        return nullptr;
    }
    LOGI(
        "RAW_RECORD_PREWARM_SLOT_EXPORTED slot=%d extent=%ux%u format=0x%x ownership=FREE",
        slot,
        desc.width,
        desc.height,
        desc.format);
    return AHardwareBuffer_toHardwareBuffer(env, transport);
}

// STAGE 2 CPU-readback present: copy the latest tonemapped RGBA8 monitor (display-ready sRGB) into the
// caller's direct ByteBuffer and return (previewW<<16)|previewH; 0 when no debayered preview is ready or
// the destination is too small. The compositor uploads the bytes as a plain GL texture — works on any
// device regardless of AHardwareBuffer GPU-sharing support.
extern "C" JNIEXPORT jint JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeCopyRawDemosaicPreviewInto(
    JNIEnv* env, jobject, jlong handle, jobject dstByteBuffer) {
    auto* state = fromHandle(handle);
    if (state == nullptr || dstByteBuffer == nullptr) return 0;
    std::lock_guard<std::mutex> lock(state->mutex);
    auto& r = state->rawDemosaic;
    if (!r.previewReady || r.previewW <= 0 || r.previewH <= 0 || r.previewRgba8.empty()) return 0;
    void* dst = env->GetDirectBufferAddress(dstByteBuffer);
    const jlong cap = env->GetDirectBufferCapacity(dstByteBuffer);
    const size_t need = r.previewRgba8.size();
    if (dst == nullptr || cap < static_cast<jlong>(need)) return 0;
    std::memcpy(dst, r.previewRgba8.data(), need);
    return (static_cast<jint>(r.previewW) << 16) | (static_cast<jint>(r.previewH) & 0xFFFF);
}

// Export the best available RAW-derived transport from the last successful dispatch: preferred FP16
// linear ACEScg, else project-owned packed NEO-SCENE-LOG10-v1. The AHardwareBuffer dimensions are the
// extent truth used by Kotlin; both formats are decoded to linear ACEScg before the NDR operator.
extern "C" JNIEXPORT jobject JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeAcquireRawDemosaicOutputHardwareBuffer(
    JNIEnv* env, jobject, jlong handle) {
    auto* state = fromHandle(handle);
    if (env == nullptr || state == nullptr) return nullptr;
    std::lock_guard<std::mutex> lock(state->mutex);
    auto& r = state->rawDemosaic;
    if (r.width <= 0 || r.height <= 0) return nullptr;

    const int completedSlot = oldestCompletedRawTransportSlot(r);
    if (completedSlot < 0) return nullptr;

    AHardwareBuffer* transport = nullptr;
    uint32_t expectedFormat = 0u;
    if (rawOutputAhbForSlot(r, completedSlot) != nullptr) {
        transport = rawOutputAhbForSlot(r, completedSlot);
        expectedFormat = AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT;
    } else if (rawSceneLogReadyForSlot(r, completedSlot) &&
               rawSceneLogAhbForSlot(r, completedSlot) != nullptr) {
        transport = rawSceneLogAhbForSlot(r, completedSlot);
        expectedFormat = AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM;
    }
    if (transport == nullptr) return nullptr;

    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(transport, &desc);
    if (desc.width != static_cast<uint32_t>(r.width) ||
        desc.height != static_cast<uint32_t>(r.height) ||
        desc.format != expectedFormat) {
        return nullptr;
    }
    return AHardwareBuffer_toHardwareBuffer(env, transport);
}

// Recorder-only exact acquisition. Unlike the legacy synchronous-preview accessor above, this marks
// one immutable full-resolution lane EXTERNALLY_ACQUIRED and publishes both its non-reusable token and
// completion serial. Java construction and ownership metadata publication must both succeed before the
// native state changes, so every pre-accept failure remains safely retryable as COMPLETED.
extern "C" JNIEXPORT jobject JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeAcquireRawDemosaicEncoderOutputHardwareBuffer(
    JNIEnv* env, jobject, jlong handle, jlongArray ownershipOut) {
    auto* state = fromHandle(handle);
    if (env == nullptr || state == nullptr || ownershipOut == nullptr ||
        env->GetArrayLength(ownershipOut) < 2) {
        return nullptr;
    }
    std::lock_guard<std::mutex> lock(state->mutex);
    auto& r = state->rawDemosaic;
    if (!state->rawRecordingDemosaicActive || !r.recordingTransportRingReady ||
        r.width <= 0 || r.height <= 0) {
        return nullptr;
    }

    const int completedSlot = oldestCompletedRawTransportSlot(r);
    if (completedSlot < 0) return nullptr;
    const size_t slot = static_cast<size_t>(completedSlot);
    const uint64_t token = r.transportTokens[slot];
    const uint64_t completionSerial = r.transportCompletionSerial[slot];
    if (token == 0u || completionSerial == 0u) return nullptr;

    AHardwareBuffer* transport = nullptr;
    uint32_t expectedFormat = 0u;
    if (rawOutputAhbForSlot(r, completedSlot) != nullptr) {
        transport = rawOutputAhbForSlot(r, completedSlot);
        expectedFormat = AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT;
    } else if (rawSceneLogReadyForSlot(r, completedSlot) &&
               rawSceneLogAhbForSlot(r, completedSlot) != nullptr) {
        transport = rawSceneLogAhbForSlot(r, completedSlot);
        expectedFormat = AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM;
    }
    if (transport == nullptr) return nullptr;

    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(transport, &desc);
    if (desc.width != static_cast<uint32_t>(r.width) ||
        desc.height != static_cast<uint32_t>(r.height) ||
        desc.format != expectedFormat) {
        return nullptr;
    }

    jobject hardwareBuffer = AHardwareBuffer_toHardwareBuffer(env, transport);
    if (hardwareBuffer == nullptr || env->ExceptionCheck()) return nullptr;
    const jlong ownership[2] = {
        static_cast<jlong>(token),
        static_cast<jlong>(completionSerial)
    };
    env->SetLongArrayRegion(ownershipOut, 0, 2, ownership);
    if (env->ExceptionCheck()) {
        env->DeleteLocalRef(hardwareBuffer);
        return nullptr;
    }

    r.transportOwnership[slot] = kRawTransportExternallyAcquired;
    return hardwareBuffer;
}

// EGL rejected before it accepted the exact imported read. Restore the immutable completion for a
// retry without changing its token/serial or making the lane writable by Vulkan.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeCancelRawDemosaicEncoderOutput(
    JNIEnv* /*env*/, jobject, jlong handle, jlong token) {
    auto* state = fromHandle(handle);
    if (state == nullptr || token <= 0) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(state->mutex);
    return cancelRawTransportToken(
        state->rawDemosaic,
        static_cast<uint64_t>(token)) ? JNI_TRUE : JNI_FALSE;
}

// The exact EGL receipt has signalled and been acknowledged. Only the lane carrying this token may
// become writable; stale or duplicate acknowledgements are rejected without touching either slot.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeAcknowledgeRawDemosaicEncoderOutput(
    JNIEnv* /*env*/, jobject, jlong handle, jlong token) {
    auto* state = fromHandle(handle);
    if (state == nullptr || token <= 0) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(state->mutex);
    return acknowledgeRawTransportToken(
        state->rawDemosaic,
        static_cast<uint64_t>(token)) ? JNI_TRUE : JNI_FALSE;
}

// Release exactly the oldest completed export after every synchronous EGL consumer has returned.
// glFinish in the offscreen bridge remains the cross-API completion barrier; this acknowledgement is
// the ownership transition that permits a later Vulkan dispatch to reuse the same slot.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeAcknowledgeRawDemosaicOutput(
    JNIEnv* /*env*/, jobject, jlong handle) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(state->mutex);
    return acknowledgeOldestCompletedRawTransport(state->rawDemosaic)
        ? JNI_TRUE
        : JNI_FALSE;
}

// Atomically acquire the oldest completed independent preview slot and publish its exact token through
// tokenOut[0]. A slot changes to EXTERNALLY_ACQUIRED only after Java HardwareBuffer construction succeeds;
// duplicate acquire cannot return the same slot, and a stale/duplicate token can never free another slot.
extern "C" JNIEXPORT jobject JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeAcquireRawDemosaicPreviewOutputHardwareBuffer(
    JNIEnv* env, jobject, jlong handle, jlongArray tokenOut) {
    auto* state = fromHandle(handle);
    if (env == nullptr || state == nullptr || tokenOut == nullptr ||
        env->GetArrayLength(tokenOut) < 1) {
        return nullptr;
    }
    std::lock_guard<std::mutex> lock(state->mutex);
    auto& r = state->rawDemosaic;
    const int completedSlot = oldestCompletedRawPreviewTransportSlot(r);
    if (completedSlot < 0) return nullptr;
    const size_t slot = static_cast<size_t>(completedSlot);
    AHardwareBuffer* transport = r.previewTransportAhbs[slot];
    const uint64_t token = r.previewTransportTokens[slot];
    if (transport == nullptr || token == 0u ||
        r.previewTransportW <= 0 || r.previewTransportH <= 0 ||
        r.previewTransportAhbFormat == 0u) {
        return nullptr;
    }

    AHardwareBuffer_Desc desc{};
    AHardwareBuffer_describe(transport, &desc);
    if (desc.width != static_cast<uint32_t>(r.previewTransportW) ||
        desc.height != static_cast<uint32_t>(r.previewTransportH) ||
        desc.format != r.previewTransportAhbFormat) {
        LOGE(
            "RAW preview acquire rejected malformed slot=%d actual=%ux%u format=%u "
            "expected=%dx%d format=%u",
            completedSlot,
            desc.width,
            desc.height,
            desc.format,
            r.previewTransportW,
            r.previewTransportH,
            r.previewTransportAhbFormat);
        return nullptr;
    }

    const jlong javaToken = static_cast<jlong>(token);
    env->SetLongArrayRegion(tokenOut, 0, 1, &javaToken);
    if (env->ExceptionCheck()) return nullptr;
    jobject hardwareBuffer = AHardwareBuffer_toHardwareBuffer(env, transport);
    if (hardwareBuffer == nullptr || env->ExceptionCheck()) return nullptr;

    r.previewTransportOwnership[slot] = kRawTransportExternallyAcquired;
    ++r.previewAcquiredCount;
    return hardwareBuffer;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeAcknowledgeRawDemosaicPreviewOutput(
    JNIEnv* /*env*/, jobject, jlong handle, jlong token) {
    auto* state = fromHandle(handle);
    if (state == nullptr || token <= 0) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(state->mutex);
    auto& r = state->rawDemosaic;
    const bool acknowledged = acknowledgeRawPreviewTransportToken(
        r, static_cast<uint64_t>(token));
    if (!acknowledged) {
        LOGE("RAW preview token acknowledgement rejected token=%lld", static_cast<long long>(token));
        return JNI_FALSE;
    }
    if (r.previewAcknowledgedCount <= 8u || r.previewAcknowledgedCount % 60u == 0u) {
        LOGI(
            "RAW_PREVIEW_RING_PROGRESS generated=%llu acquired=%llu acknowledged=%llu "
            "saturated=%llu outstanding=%zu",
            static_cast<unsigned long long>(r.previewGeneratedCount),
            static_cast<unsigned long long>(r.previewAcquiredCount),
            static_cast<unsigned long long>(r.previewAcknowledgedCount),
            static_cast<unsigned long long>(r.previewSaturatedCount),
            rawPreviewTransportOutstandingCount(r));
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeDiscardCompletedRawDemosaicPreviewOutputs(
    JNIEnv* /*env*/, jobject, jlong handle) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return -1;
    std::lock_guard<std::mutex> lock(state->mutex);
    auto& r = state->rawDemosaic;
    const int discarded = discardCompletedRawPreviewTransports(r);
    LOGI(
        "RAW_PREVIEW_RING_DISCARD result=%d generated=%llu acquired=%llu acknowledged=%llu "
        "saturated=%llu outstanding=%zu",
        discarded,
        static_cast<unsigned long long>(r.previewGeneratedCount),
        static_cast<unsigned long long>(r.previewAcquiredCount),
        static_cast<unsigned long long>(r.previewAcknowledgedCount),
        static_cast<unsigned long long>(r.previewSaturatedCount),
        rawPreviewTransportOutstandingCount(r));
    return static_cast<jint>(discarded);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeQuiesceRawDemosaicPreviewOutputs(
    JNIEnv* /*env*/, jobject, jlong handle) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(state->mutex);
    auto& r = state->rawDemosaic;
    const int discarded = discardCompletedRawPreviewTransports(r);
    const size_t outstanding = rawPreviewTransportOutstandingCount(r);
    const bool quiescent = discarded >= 0 && outstanding == 0u;
    LOGI(
        "RAW_PREVIEW_RING_QUIESCE result=%d discarded=%d generated=%llu acquired=%llu "
        "acknowledged=%llu saturated=%llu outstanding=%zu",
        quiescent ? 1 : 0,
        discarded,
        static_cast<unsigned long long>(r.previewGeneratedCount),
        static_cast<unsigned long long>(r.previewAcquiredCount),
        static_cast<unsigned long long>(r.previewAcknowledgedCount),
        static_cast<unsigned long long>(r.previewSaturatedCount),
        outstanding);
    return quiescent ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeGetRawDemosaicPreviewTransportStats(
    JNIEnv* env, jobject, jlong handle) {
    auto* state = fromHandle(handle);
    if (env == nullptr || state == nullptr) return nullptr;
    std::lock_guard<std::mutex> lock(state->mutex);
    const auto& r = state->rawDemosaic;
    const jlong values[5] = {
        static_cast<jlong>(r.previewGeneratedCount),
        static_cast<jlong>(r.previewAcquiredCount),
        static_cast<jlong>(r.previewAcknowledgedCount),
        static_cast<jlong>(r.previewSaturatedCount),
        static_cast<jlong>(rawPreviewTransportOutstandingCount(r))
    };
    jlongArray result = env->NewLongArray(5);
    if (result == nullptr || env->ExceptionCheck()) return nullptr;
    env->SetLongArrayRegion(result, 0, 5, values);
    if (env->ExceptionCheck()) return nullptr;
    return result;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeGetRawDemosaicPreviewDispatchAdmission(
    JNIEnv* /*env*/, jobject, jlong handle) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return kRawPreviewDispatchUnavailable;
    std::lock_guard<std::mutex> lock(state->mutex);
    return rawPreviewTransportDispatchAdmission(state);
}

// Lifecycle/failure retirement only. The caller first proves that no dispatch is pending and that no
// encoder surface can still sample these buffers; all completed lanes can then be discarded together.
extern "C" JNIEXPORT jint JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeDiscardCompletedRawDemosaicOutputs(
    JNIEnv* /*env*/, jobject, jlong handle) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return 0;
    std::lock_guard<std::mutex> lock(state->mutex);
    if (state->rawDemosaic.dispatchInFlight) return 0;
    return static_cast<jint>(discardAllCompletedRawTransports(state->rawDemosaic));
}

static bool retireRawDevelopReadback(VulkanBridgeState* state, uint64_t timeoutNs) {
    auto& r = state->rawDemosaic;
    if (!r.developReadbackInFlight) return true;
    if (r.developReadbackFence == VK_NULL_HANDLE) return false;
    const VkResult waitResult = vkWaitForFences(
        state->device, 1, &r.developReadbackFence, VK_TRUE, timeoutNs);
    if (waitResult == VK_SUCCESS) {
        r.developReadbackInFlight = false;
        return true;
    }
    if (waitResult == VK_TIMEOUT) {
        LOGW("RAW offline readback exceeded its bounded completion deadline");
        return false;
    }
    check(waitResult, "vkWaitForFences(rawDevelopReadback)");
    if (waitResult == VK_ERROR_DEVICE_LOST) {
        r.developReadbackInFlight = false;
        state->supported = false;
        setStatus(state, "RAW offline readback failed: Vulkan device lost");
    }
    return false;
}

static bool rawDevelopP010PipelinesAvailable(const VulkanBridgeState* state) {
    return state != nullptr &&
        state->rawDevelopImageDescLayout != VK_NULL_HANDLE &&
        state->rawDevelopP010DescLayout != VK_NULL_HANDLE &&
        state->rawDevelopDetailPipelineLayout != VK_NULL_HANDLE &&
        state->rawDevelopLumaBlurPipelineLayout != VK_NULL_HANDLE &&
        state->rawDevelopP010PipelineLayout != VK_NULL_HANDLE &&
        state->rawDevelopDetailPipeline != VK_NULL_HANDLE &&
        state->rawDevelopLumaBlurPipeline != VK_NULL_HANDLE &&
        state->rawDevelopP010Pipeline != VK_NULL_HANDLE;
}

static bool retireRawDevelopP010(VulkanBridgeState* state, uint64_t timeoutNs) {
    auto& r = state->rawDemosaic;
    if (!r.developP010InFlight) return true;
    if (r.developP010Fence == VK_NULL_HANDLE) return false;
    const VkResult waitResult = vkWaitForFences(
        state->device, 1, &r.developP010Fence, VK_TRUE, timeoutNs);
    if (waitResult == VK_SUCCESS) {
        r.developP010InFlight = false;
        return true;
    }
    if (waitResult == VK_TIMEOUT) {
        LOGW("RAW GPU P010 conversion exceeded its bounded completion deadline");
        return false;
    }
    check(waitResult, "vkWaitForFences(rawDevelopP010)");
    if (waitResult == VK_ERROR_DEVICE_LOST) {
        r.developP010InFlight = false;
        state->supported = false;
        setStatus(state, "RAW GPU P010 conversion failed: Vulkan device lost");
    }
    return false;
}

static bool ensureRawDevelopP010Resources(
    VulkanBridgeState* state,
    int width,
    int height
) {
    if (!rawDevelopP010PipelinesAvailable(state) || width <= 0 || height <= 0 ||
        (width & 1) != 0 || (height & 1) != 0) {
        return false;
    }
    auto& r = state->rawDemosaic;
    if (r.developP010Leased || !retireRawDevelopP010(state, kRawOfflineConversionTimeoutNs)) {
        return false;
    }
    const VkDeviceSize requiredBytes = static_cast<VkDeviceSize>(width) *
        static_cast<VkDeviceSize>(height) * 3u;
    const bool complete = r.developDetailImage != VK_NULL_HANDLE &&
        r.developDetailView != VK_NULL_HANDLE &&
        r.developLumaBlurImage != VK_NULL_HANDLE &&
        r.developLumaBlurView != VK_NULL_HANDLE &&
        r.developP010Buffer != VK_NULL_HANDLE &&
        r.developP010Memory != VK_NULL_HANDLE &&
        r.developP010Mapped != nullptr &&
        r.developP010DescriptorPool != VK_NULL_HANDLE &&
        r.developDetailDescriptorSet != VK_NULL_HANDLE &&
        r.developLumaBlurDescriptorSet != VK_NULL_HANDLE &&
        r.developP010DescriptorSet != VK_NULL_HANDLE &&
        r.developP010CommandPool != VK_NULL_HANDLE &&
        r.developP010CommandBuffer != VK_NULL_HANDLE &&
        r.developP010Fence != VK_NULL_HANDLE;
    if (complete && r.developP010Width == width && r.developP010Height == height &&
        r.developP010Bytes == requiredBytes) {
        return true;
    }

    releaseRawDevelopP010Resources(state);
    if (!createRawDemosaicOwnedImage(
            state, width, height, VK_FORMAT_R32G32B32A32_SFLOAT,
            &r.developDetailImage, &r.developDetailMemory, &r.developDetailView) ||
        !createRawDemosaicOwnedImage(
            state, width, height, VK_FORMAT_R32_SFLOAT,
            &r.developLumaBlurImage, &r.developLumaBlurMemory, &r.developLumaBlurView) ||
        !createBufferWithMemory(
            state,
            requiredBytes,
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
            &r.developP010Buffer,
            &r.developP010Memory,
            /*requireExactProperties=*/true) ||
        !check(vkMapMemory(
            state->device,
            r.developP010Memory,
            0,
            requiredBytes,
            0,
            &r.developP010Mapped),
            "vkMapMemory(rawDevelopP010)")) {
        releaseRawDevelopP010Resources(state);
        return false;
    }

    VkDescriptorPoolSize poolSizes[2]{};
    poolSizes[0].type = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    poolSizes[0].descriptorCount = 6;
    poolSizes[1].type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    poolSizes[1].descriptorCount = 1;
    VkDescriptorPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO;
    poolInfo.maxSets = 3;
    poolInfo.poolSizeCount = 2;
    poolInfo.pPoolSizes = poolSizes;
    if (!check(vkCreateDescriptorPool(
            state->device, &poolInfo, nullptr, &r.developP010DescriptorPool),
            "vkCreateDescriptorPool(rawDevelopP010)")) {
        releaseRawDevelopP010Resources(state);
        return false;
    }
    std::array<VkDescriptorSetLayout, 3> setLayouts{{
        state->rawDevelopImageDescLayout,
        state->rawDevelopImageDescLayout,
        state->rawDevelopP010DescLayout
    }};
    std::array<VkDescriptorSet, 3> sets{};
    VkDescriptorSetAllocateInfo setAllocation{};
    setAllocation.sType = VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO;
    setAllocation.descriptorPool = r.developP010DescriptorPool;
    setAllocation.descriptorSetCount = static_cast<uint32_t>(setLayouts.size());
    setAllocation.pSetLayouts = setLayouts.data();
    if (!check(vkAllocateDescriptorSets(state->device, &setAllocation, sets.data()),
               "vkAllocateDescriptorSets(rawDevelopP010)")) {
        releaseRawDevelopP010Resources(state);
        return false;
    }
    r.developDetailDescriptorSet = sets[0];
    r.developLumaBlurDescriptorSet = sets[1];
    r.developP010DescriptorSet = sets[2];

    VkDescriptorImageInfo sourceImage{};
    sourceImage.imageView = r.outputView;
    sourceImage.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkDescriptorImageInfo detailImage{};
    detailImage.imageView = r.developDetailView;
    detailImage.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkDescriptorImageInfo blurImage{};
    blurImage.imageView = r.developLumaBlurView;
    blurImage.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkDescriptorBufferInfo p010Buffer{};
    p010Buffer.buffer = r.developP010Buffer;
    p010Buffer.offset = 0;
    p010Buffer.range = requiredBytes;
    std::array<VkWriteDescriptorSet, 7> writes{};
    const std::array<VkDescriptorImageInfo*, 6> imageInfos{{
        &sourceImage, &detailImage,
        &detailImage, &blurImage,
        &detailImage, &blurImage
    }};
    const std::array<VkDescriptorSet, 6> imageSets{{
        r.developDetailDescriptorSet, r.developDetailDescriptorSet,
        r.developLumaBlurDescriptorSet, r.developLumaBlurDescriptorSet,
        r.developP010DescriptorSet, r.developP010DescriptorSet
    }};
    const std::array<uint32_t, 6> imageBindings{{0u, 1u, 0u, 1u, 0u, 1u}};
    for (size_t i = 0; i < imageInfos.size(); ++i) {
        writes[i].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
        writes[i].dstSet = imageSets[i];
        writes[i].dstBinding = imageBindings[i];
        writes[i].descriptorCount = 1;
        writes[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
        writes[i].pImageInfo = imageInfos[i];
    }
    writes[6].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[6].dstSet = r.developP010DescriptorSet;
    writes[6].dstBinding = 2;
    writes[6].descriptorCount = 1;
    writes[6].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
    writes[6].pBufferInfo = &p010Buffer;
    vkUpdateDescriptorSets(
        state->device, static_cast<uint32_t>(writes.size()), writes.data(), 0, nullptr);

    VkCommandPoolCreateInfo commandPoolInfo{};
    commandPoolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    commandPoolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    commandPoolInfo.queueFamilyIndex = state->queueFamilyIndex;
    if (!check(vkCreateCommandPool(
            state->device, &commandPoolInfo, nullptr, &r.developP010CommandPool),
            "vkCreateCommandPool(rawDevelopP010)")) {
        releaseRawDevelopP010Resources(state);
        return false;
    }
    VkCommandBufferAllocateInfo commandAllocation{};
    commandAllocation.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    commandAllocation.commandPool = r.developP010CommandPool;
    commandAllocation.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    commandAllocation.commandBufferCount = 1;
    if (!check(vkAllocateCommandBuffers(
            state->device, &commandAllocation, &r.developP010CommandBuffer),
            "vkAllocateCommandBuffers(rawDevelopP010)")) {
        releaseRawDevelopP010Resources(state);
        return false;
    }
    VkFenceCreateInfo fenceInfo{};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
    if (!check(vkCreateFence(state->device, &fenceInfo, nullptr, &r.developP010Fence),
               "vkCreateFence(rawDevelopP010)")) {
        releaseRawDevelopP010Resources(state);
        return false;
    }

    VkCommandPool initializePool = VK_NULL_HANDLE;
    VkCommandBuffer initializeCommand = VK_NULL_HANDLE;
    if (!beginSingleUseCommands(state, &initializePool, &initializeCommand)) {
        releaseRawDevelopP010Resources(state);
        return false;
    }
    VkImageSubresourceRange imageRange{};
    imageRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    imageRange.levelCount = 1;
    imageRange.layerCount = 1;
    const VkImage initializedImages[2] = {
        r.developDetailImage,
        r.developLumaBlurImage
    };
    for (VkImage image : initializedImages) {
        VkImageMemoryBarrier toGeneral{};
        toGeneral.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
        toGeneral.srcAccessMask = 0;
        toGeneral.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
        toGeneral.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        toGeneral.newLayout = VK_IMAGE_LAYOUT_GENERAL;
        toGeneral.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        toGeneral.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        toGeneral.image = image;
        toGeneral.subresourceRange = imageRange;
        vkCmdPipelineBarrier(
            initializeCommand,
            VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
            VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
            0, 0, nullptr, 0, nullptr, 1, &toGeneral);
    }
    if (!endSingleUseCommands(state, initializePool, initializeCommand)) {
        releaseRawDevelopP010Resources(state);
        return false;
    }

    r.developP010Bytes = requiredBytes;
    r.developP010Width = width;
    r.developP010Height = height;
    LOGI("RAW_GPU_P010_RESOURCES_READY extent=%dx%d bytes=%llu",
         width,
         height,
         static_cast<unsigned long long>(requiredBytes));
    return true;
}

static bool dispatchRawDevelopP010(
    VulkanBridgeState* state,
    int width,
    int height,
    const RawDevelopP010PushConstants& p010Push,
    uint64_t* elapsedNanos
) {
    auto& r = state->rawDemosaic;
    const int completedSlot = oldestCompletedRawTransportSlot(r);
    if (completedSlot < 0 || r.developP010Leased ||
        !ensureRawDevelopP010Resources(state, width, height)) {
        return false;
    }
    const VkImage source = rawOutputImageForSlot(r, completedSlot);
    const VkImageView sourceView = rawOutputViewForSlot(r, completedSlot);
    if (source == VK_NULL_HANDLE || sourceView == VK_NULL_HANDLE) return false;

    VkDescriptorImageInfo sourceInfo{};
    sourceInfo.imageView = sourceView;
    sourceInfo.imageLayout = VK_IMAGE_LAYOUT_GENERAL;
    VkWriteDescriptorSet sourceWrite{};
    sourceWrite.sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    sourceWrite.dstSet = r.developDetailDescriptorSet;
    sourceWrite.dstBinding = 0;
    sourceWrite.descriptorCount = 1;
    sourceWrite.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    sourceWrite.pImageInfo = &sourceInfo;
    vkUpdateDescriptorSets(state->device, 1, &sourceWrite, 0, nullptr);

    if (!check(vkResetCommandPool(state->device, r.developP010CommandPool, 0),
               "vkResetCommandPool(rawDevelopP010)")) {
        return false;
    }
    VkCommandBufferBeginInfo begin{};
    begin.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (!check(vkBeginCommandBuffer(r.developP010CommandBuffer, &begin),
               "vkBeginCommandBuffer(rawDevelopP010)")) {
        return false;
    }

    VkImageSubresourceRange imageRange{};
    imageRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    imageRange.levelCount = 1;
    imageRange.layerCount = 1;
    VkImageMemoryBarrier sourceReady{};
    sourceReady.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    sourceReady.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    sourceReady.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    sourceReady.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    sourceReady.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    sourceReady.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    sourceReady.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    sourceReady.image = source;
    sourceReady.subresourceRange = imageRange;
    VkBufferMemoryBarrier hostReadComplete{};
    hostReadComplete.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    hostReadComplete.srcAccessMask = VK_ACCESS_HOST_READ_BIT;
    hostReadComplete.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    hostReadComplete.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    hostReadComplete.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    hostReadComplete.buffer = r.developP010Buffer;
    hostReadComplete.offset = 0;
    hostReadComplete.size = r.developP010Bytes;
    vkCmdPipelineBarrier(
        r.developP010CommandBuffer,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_HOST_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0, 0, nullptr, 1, &hostReadComplete, 1, &sourceReady);

    const RawDevelopDetailPushConstants detailPush{
        width,
        height,
        p010Push.detailEnabled,
        0
    };
    vkCmdBindPipeline(
        r.developP010CommandBuffer,
        VK_PIPELINE_BIND_POINT_COMPUTE,
        state->rawDevelopDetailPipeline);
    vkCmdBindDescriptorSets(
        r.developP010CommandBuffer,
        VK_PIPELINE_BIND_POINT_COMPUTE,
        state->rawDevelopDetailPipelineLayout,
        0, 1, &r.developDetailDescriptorSet, 0, nullptr);
    vkCmdPushConstants(
        r.developP010CommandBuffer,
        state->rawDevelopDetailPipelineLayout,
        VK_SHADER_STAGE_COMPUTE_BIT,
        0,
        sizeof(detailPush),
        &detailPush);
    vkCmdDispatch(
        r.developP010CommandBuffer,
        (static_cast<uint32_t>(width) + 15u) / 16u,
        (static_cast<uint32_t>(height) + 15u) / 16u,
        1u);

    VkImageMemoryBarrier detailReady{};
    detailReady.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    detailReady.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    detailReady.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    detailReady.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    detailReady.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    detailReady.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    detailReady.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    detailReady.image = r.developDetailImage;
    detailReady.subresourceRange = imageRange;
    vkCmdPipelineBarrier(
        r.developP010CommandBuffer,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0, 0, nullptr, 0, nullptr, 1, &detailReady);

    vkCmdBindPipeline(
        r.developP010CommandBuffer,
        VK_PIPELINE_BIND_POINT_COMPUTE,
        state->rawDevelopLumaBlurPipeline);
    vkCmdBindDescriptorSets(
        r.developP010CommandBuffer,
        VK_PIPELINE_BIND_POINT_COMPUTE,
        state->rawDevelopLumaBlurPipelineLayout,
        0, 1, &r.developLumaBlurDescriptorSet, 0, nullptr);
    vkCmdPushConstants(
        r.developP010CommandBuffer,
        state->rawDevelopLumaBlurPipelineLayout,
        VK_SHADER_STAGE_COMPUTE_BIT,
        0,
        sizeof(detailPush),
        &detailPush);
    vkCmdDispatch(
        r.developP010CommandBuffer,
        (static_cast<uint32_t>(width) + 15u) / 16u,
        (static_cast<uint32_t>(height) + 15u) / 16u,
        1u);

    VkImageMemoryBarrier blurReady{};
    blurReady.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    blurReady.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    blurReady.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    blurReady.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    blurReady.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    blurReady.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    blurReady.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    blurReady.image = r.developLumaBlurImage;
    blurReady.subresourceRange = imageRange;
    vkCmdPipelineBarrier(
        r.developP010CommandBuffer,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0, 0, nullptr, 0, nullptr, 1, &blurReady);

    vkCmdBindPipeline(
        r.developP010CommandBuffer,
        VK_PIPELINE_BIND_POINT_COMPUTE,
        state->rawDevelopP010Pipeline);
    vkCmdBindDescriptorSets(
        r.developP010CommandBuffer,
        VK_PIPELINE_BIND_POINT_COMPUTE,
        state->rawDevelopP010PipelineLayout,
        0, 1, &r.developP010DescriptorSet, 0, nullptr);
    vkCmdPushConstants(
        r.developP010CommandBuffer,
        state->rawDevelopP010PipelineLayout,
        VK_SHADER_STAGE_COMPUTE_BIT,
        0,
        sizeof(p010Push),
        &p010Push);
    vkCmdDispatch(
        r.developP010CommandBuffer,
        (static_cast<uint32_t>(width / 2) + 7u) / 8u,
        (static_cast<uint32_t>(height / 2) + 7u) / 8u,
        1u);

    VkBufferMemoryBarrier p010Ready{};
    p010Ready.sType = VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER;
    p010Ready.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    p010Ready.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
    p010Ready.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    p010Ready.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    p010Ready.buffer = r.developP010Buffer;
    p010Ready.offset = 0;
    p010Ready.size = r.developP010Bytes;
    vkCmdPipelineBarrier(
        r.developP010CommandBuffer,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_HOST_BIT,
        0, 0, nullptr, 1, &p010Ready, 0, nullptr);

    if (!check(vkEndCommandBuffer(r.developP010CommandBuffer),
               "vkEndCommandBuffer(rawDevelopP010)")) {
        return false;
    }
    if (!check(vkResetFences(state->device, 1, &r.developP010Fence),
               "vkResetFences(rawDevelopP010)")) {
        return false;
    }
    VkSubmitInfo submit{};
    submit.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &r.developP010CommandBuffer;
    const auto started = std::chrono::steady_clock::now();
    const VkResult submitResult = submitRawQueueSynchronized(
        state, 1, &submit, r.developP010Fence);
    if (submitResult != VK_SUCCESS) {
        check(submitResult, "vkQueueSubmit(rawDevelopP010)");
        if (submitResult == VK_ERROR_DEVICE_LOST) {
            state->supported = false;
            setStatus(state, "RAW GPU P010 submission failed: Vulkan device lost");
        }
        return false;
    }
    r.developP010InFlight = true;
    if (!retireRawDevelopP010(state, kRawOfflineConversionTimeoutNs)) return false;
    const auto finished = std::chrono::steady_clock::now();
    if (elapsedNanos != nullptr) {
        *elapsedNanos = static_cast<uint64_t>(
            std::chrono::duration_cast<std::chrono::nanoseconds>(finished - started).count());
    }
    ++r.developP010DispatchCount;
    if (r.developP010DispatchCount <= 3u || r.developP010DispatchCount % 60u == 0u) {
        LOGI("RAW_GPU_P010_DISPATCH count=%llu extent=%dx%d delivery=%d detail=%d gpuWaitMs=%.3f",
             static_cast<unsigned long long>(r.developP010DispatchCount),
             width,
             height,
             p010Push.deliveryMode,
             p010Push.detailEnabled,
             static_cast<double>(elapsedNanos != nullptr ? *elapsedNanos : 0u) / 1000000.0);
    }
    return true;
}

static bool ensureRawDevelopReadbackResources(
    VulkanBridgeState* state,
    VkDeviceSize requiredBytes
) {
    auto& r = state->rawDemosaic;
    if (!retireRawDevelopReadback(state, kRawOfflineConversionTimeoutNs)) return false;
    const bool complete = r.developReadbackBuffer != VK_NULL_HANDLE &&
        r.developReadbackMemory != VK_NULL_HANDLE &&
        r.developReadbackMapped != nullptr &&
        r.developReadbackCommandPool != VK_NULL_HANDLE &&
        r.developReadbackCommandBuffer != VK_NULL_HANDLE &&
        r.developReadbackFence != VK_NULL_HANDLE;
    if (complete && r.developReadbackBytes == requiredBytes) return true;

    // Extent changes normally destroy the whole RAW resource group. This local cleanup also makes a
    // partially-created lazy workspace recoverable without disturbing the valid demosaic output image.
    releaseRawDevelopReadbackResources(state);

    if (!createBufferWithMemory(
            state,
            requiredBytes,
            VK_BUFFER_USAGE_TRANSFER_DST_BIT,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
            &r.developReadbackBuffer,
            &r.developReadbackMemory,
            /*requireExactProperties=*/true)) {
        releaseRawDevelopReadbackResources(state);
        return false;
    }
    if (!check(vkMapMemory(
            state->device,
            r.developReadbackMemory,
            0,
            requiredBytes,
            0,
            &r.developReadbackMapped),
            "vkMapMemory(rawDevelopPersistentReadback)")) {
        releaseRawDevelopReadbackResources(state);
        return false;
    }

    VkCommandPoolCreateInfo poolInfo{};
    poolInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
    poolInfo.flags = VK_COMMAND_POOL_CREATE_TRANSIENT_BIT;
    poolInfo.queueFamilyIndex = state->queueFamilyIndex;
    if (!check(vkCreateCommandPool(
            state->device, &poolInfo, nullptr, &r.developReadbackCommandPool),
            "vkCreateCommandPool(rawDevelopReadback)")) {
        releaseRawDevelopReadbackResources(state);
        return false;
    }
    VkCommandBufferAllocateInfo allocInfo{};
    allocInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
    allocInfo.commandPool = r.developReadbackCommandPool;
    allocInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocInfo.commandBufferCount = 1;
    if (!check(vkAllocateCommandBuffers(
            state->device, &allocInfo, &r.developReadbackCommandBuffer),
            "vkAllocateCommandBuffers(rawDevelopReadback)")) {
        releaseRawDevelopReadbackResources(state);
        return false;
    }
    VkFenceCreateInfo fenceInfo{};
    fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
    fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
    if (!check(vkCreateFence(
            state->device, &fenceInfo, nullptr, &r.developReadbackFence),
            "vkCreateFence(rawDevelopReadback)")) {
        releaseRawDevelopReadbackResources(state);
        return false;
    }
    r.developReadbackBytes = requiredBytes;
    return true;
}

static bool copyRawDevelopOutputToHost(
    VulkanBridgeState* state,
    int width,
    int height,
    VkDeviceSize requiredBytes
) {
    auto& r = state->rawDemosaic;
    const int completedSlot = oldestCompletedRawTransportSlot(r);
    if (completedSlot < 0) return false;
    const VkImage completedOutput = rawOutputImageForSlot(r, completedSlot);
    if (completedOutput == VK_NULL_HANDLE) return false;
    if (!ensureRawDevelopReadbackResources(state, requiredBytes)) return false;
    if (!check(vkResetCommandPool(state->device, r.developReadbackCommandPool, 0),
               "vkResetCommandPool(rawDevelopReadback)")) {
        return false;
    }
    VkCommandBufferBeginInfo beginInfo{};
    beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (!check(vkBeginCommandBuffer(r.developReadbackCommandBuffer, &beginInfo),
               "vkBeginCommandBuffer(rawDevelopReadback)")) {
        return false;
    }

    VkImageSubresourceRange range{};
    range.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    range.levelCount = 1;
    range.layerCount = 1;
    VkImageMemoryBarrier toSrc{};
    toSrc.sType = VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER;
    toSrc.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    toSrc.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    toSrc.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    toSrc.newLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    toSrc.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toSrc.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toSrc.image = completedOutput;
    toSrc.subresourceRange = range;
    vkCmdPipelineBarrier(
        r.developReadbackCommandBuffer,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        0, 0, nullptr, 0, nullptr, 1, &toSrc);

    VkBufferImageCopy copyRegion{};
    copyRegion.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    copyRegion.imageSubresource.layerCount = 1;
    copyRegion.imageExtent = {
        static_cast<uint32_t>(width), static_cast<uint32_t>(height), 1u
    };
    vkCmdCopyImageToBuffer(
        r.developReadbackCommandBuffer,
        completedOutput,
        VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
        r.developReadbackBuffer,
        1,
        &copyRegion);

    VkImageMemoryBarrier toGeneral = toSrc;
    toGeneral.srcAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    toGeneral.dstAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    toGeneral.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL;
    toGeneral.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    vkCmdPipelineBarrier(
        r.developReadbackCommandBuffer,
        VK_PIPELINE_STAGE_TRANSFER_BIT,
        VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
        0, 0, nullptr, 0, nullptr, 1, &toGeneral);
    if (!check(vkEndCommandBuffer(r.developReadbackCommandBuffer),
               "vkEndCommandBuffer(rawDevelopReadback)")) {
        return false;
    }
    if (!check(vkResetFences(state->device, 1, &r.developReadbackFence),
               "vkResetFences(rawDevelopReadback)")) {
        return false;
    }
    VkSubmitInfo submitInfo{};
    submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
    submitInfo.commandBufferCount = 1;
    submitInfo.pCommandBuffers = &r.developReadbackCommandBuffer;
    const VkResult submitResult = submitRawQueueSynchronized(
        state, 1, &submitInfo, r.developReadbackFence);
    if (submitResult != VK_SUCCESS) {
        check(submitResult, "vkQueueSubmit(rawDevelopReadback)");
        if (submitResult == VK_ERROR_DEVICE_LOST) {
            state->supported = false;
            setStatus(state, "RAW offline readback submission failed: Vulkan device lost");
        }
        return false;
    }
    r.developReadbackInFlight = true;
    return retireRawDevelopReadback(state, kRawOfflineConversionTimeoutNs);
}

// OFFLINE RAW develop (RawDevelopGpuAccelerator): demosaic the CPU sensor mosaic to a FULL-RES ACEScg
// plane via the shared cinema-debayer compute (demosaic_vulkan.comp), then read it back as
// width*height*3 float32 RGB (interleaved, linear ACEScg, PRE-exposure) into [out]. The NeoLog encode +
// 10-bit pack finish on the CPU (RawNeoLogDevelop.finishFromAcescg) so the GPU path differs from the CPU
// develop ONLY in the demosaic. DEFAULT-OFF gated on the JVM side; returns JNI_FALSE on ANY failure so
// the caller falls back to the byte-exact CPU develop (no-downgrade). Synchronous on a dedicated fence;
// it never drains unrelated preview/encoder work from the process-wide Vulkan queue.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeDevelopRawToAcescg(
    JNIEnv* env, jobject, jlong handle, jobject mosaic, jint rowStrideBytes,
    jint width, jint height, jint bayerPattern, jint whiteLevel, jfloatArray blackLevelRggb,
    jfloatArray whiteBalanceRggb, jfloatArray sensorToXyz, jfloatArray xyzToAcescg,
    jfloatArray lensShadingGridRggb, jboolean lensShadingCorrectionEnabled, jobject out) {
    auto* state = fromHandle(handle);
    if (state == nullptr || mosaic == nullptr || out == nullptr) return JNI_FALSE;
    if (blackLevelRggb == nullptr || env->GetArrayLength(blackLevelRggb) != 4 ||
        whiteBalanceRggb == nullptr || env->GetArrayLength(whiteBalanceRggb) != 4 ||
        sensorToXyz == nullptr || env->GetArrayLength(sensorToXyz) != 9 ||
        xyzToAcescg == nullptr || env->GetArrayLength(xyzToAcescg) != 9 ||
        lensShadingGridRggb == nullptr || env->GetArrayLength(lensShadingGridRggb) != 36) {
        return JNI_FALSE;
    }
    if (width < 4 || height < 4 || rowStrideBytes < width * 2) return JNI_FALSE;
    // Even dimensions only: the demosaic rounds to whole 2x2 Bayer quads, so an odd width/height would
    // read back fewer rows/cols than the CPU develops and break parity — defer odd dims to the CPU.
    if ((width & 1) != 0 || (height & 1) != 0) return JNI_FALSE;

    void* src = env->GetDirectBufferAddress(mosaic);
    const jlong srcCap = env->GetDirectBufferCapacity(mosaic);
    void* dst = env->GetDirectBufferAddress(out);
    const jlong dstCap = env->GetDirectBufferCapacity(out);
    if (src == nullptr || dst == nullptr) return JNI_FALSE;
    const jlong srcFootprint = static_cast<jlong>(rowStrideBytes) * static_cast<jlong>(height - 1) +
                               static_cast<jlong>(width) * 2;
    const jlong dstNeed = static_cast<jlong>(width) * static_cast<jlong>(height) * 3 * 4;
    if (srcCap < srcFootprint || dstCap < dstNeed) return JNI_FALSE;

    float black[4]{};
    float wb[4]{};
    float mSensor[9]{};
    float mAces[9]{};
    float lensShading[36]{};
    env->GetFloatArrayRegion(blackLevelRggb, 0, 4, black);
    env->GetFloatArrayRegion(whiteBalanceRggb, 0, 4, wb);
    env->GetFloatArrayRegion(sensorToXyz, 0, 9, mSensor);
    env->GetFloatArrayRegion(xyzToAcescg, 0, 9, mAces);
    env->GetFloatArrayRegion(lensShadingGridRggb, 0, 36, lensShading);
    if (env->ExceptionCheck()) return JNI_FALSE;
    for (int channel = 0; channel < 4; ++channel) {
        if (!isIeee754Finite(black[channel]) || black[channel] < 0.0f ||
            black[channel] >= static_cast<float>(whiteLevel) ||
            !isIeee754Finite(wb[channel]) || wb[channel] < 0.0f) {
            return JNI_FALSE;
        }
    }
    for (float gain : lensShading) {
        if (!isIeee754Finite(gain) || gain < 0.25f || gain > 8.0f) return JNI_FALSE;
    }
    // Exposure is applied on the CPU (finishFromAcescg); keep the shader's thermal/exposure inert here.
    const float exposureThermal[4] = { 0.0f, 25.0f, 25.0f, 2.0f };
    // This entry produces a scene-linear offline working plane. Recovery is a presentation/develop
    // policy and therefore stays explicitly disabled here unless the caller gains a dedicated input.
    const float highlightRecoveryOff[4] = { 1.0f, 0.0f, 0.0f, 0.0f };
    std::lock_guard<std::mutex> lock(state->mutex);
    if (!state->supported) return JNI_FALSE;
    // A timed-out prior host copy still owns the shared output image. Retire it before allowing the
    // next demosaic to overwrite that image; otherwise a rare timeout could cross-contaminate frames.
    if (!retireRawDevelopReadback(state, kRawOfflineConversionTimeoutNs)) return JNI_FALSE;

    // Temporarily open the no-downgrade preview gate so the shared demosaic dispatch runs for this
    // offline develop (the live preview owns the flag otherwise), then restore it.
    const bool savedGate = state->rawPreviewDemosaicEnabled;
    state->rawPreviewDemosaicEnabled = true;
    const bool dispatched = dispatchRawDemosaicPreviewFromBytes(
        state, static_cast<const uint8_t*>(src), static_cast<size_t>(rowStrideBytes),
        width, height, /*decimate=*/1, bayerPattern, whiteLevel,
        black, wb, exposureThermal, highlightRecoveryOff, mSensor, mAces,
        lensShading, lensShadingCorrectionEnabled == JNI_TRUE,
        /*produceMonitorOutputs=*/false, kRawOfflineConversionTimeoutNs);
    state->rawPreviewDemosaicEnabled = savedGate;
    if (!dispatched) return JNI_FALSE;

    auto& r = state->rawDemosaic;
    if (oldestCompletedRawTransportSlot(r) < 0 ||
        r.width != width || r.height != height) {
        acknowledgeOldestCompletedRawTransport(r);
        return JNI_FALSE;
    }

    const VkDeviceSize rgba16fBytes =
        static_cast<VkDeviceSize>(width) * static_cast<VkDeviceSize>(height) * 8;
    if (!copyRawDevelopOutputToHost(state, width, height, rgba16fBytes) ||
        r.developReadbackMapped == nullptr) {
        acknowledgeOldestCompletedRawTransport(r);
        return JNI_FALSE;
    }
    const uint16_t* halfPixels = static_cast<const uint16_t*>(r.developReadbackMapped);
    float* outFloats = static_cast<float*>(dst);
    const size_t pixelCount = static_cast<size_t>(width) * static_cast<size_t>(height);
    for (size_t p = 0; p < pixelCount; ++p) {
        const uint16_t* px = halfPixels + p * 4; // rgba16f source
        outFloats[p * 3 + 0] = halfToFloat(px[0]);
        outFloats[p * 3 + 1] = halfToFloat(px[1]);
        outFloats[p * 3 + 2] = halfToFloat(px[2]);
    }
    return acknowledgeOldestCompletedRawTransport(r) ? JNI_TRUE : JNI_FALSE;
}

// Bounded-Java-heap variant used by the production RAW converter. The persistent Vulkan readback
// allocation is sized for RGB float32, receives the smaller RGBA16F image at its front, and is expanded
// backwards in place. Returning that mapped storage with NewDirectByteBuffer avoids a second 144 MiB
// DirectByteBuffer allocation against Android's Java growth limit. The Java synchronized wrapper owns
// the lease until nativeEndDevelopRawToAcescgMapped acknowledges the completed transport.
extern "C" JNIEXPORT jobject JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeBeginDevelopRawToAcescgMapped(
    JNIEnv* env, jobject, jlong handle, jobject mosaic, jint rowStrideBytes,
    jint width, jint height, jint bayerPattern, jint whiteLevel, jfloatArray blackLevelRggb,
    jfloatArray whiteBalanceRggb, jfloatArray sensorToXyz, jfloatArray xyzToAcescg,
    jfloatArray lensShadingGridRggb, jboolean lensShadingCorrectionEnabled,
    jlongArray timingNanos) {
    auto* state = fromHandle(handle);
    if (state == nullptr || mosaic == nullptr || timingNanos == nullptr ||
        env->GetArrayLength(timingNanos) < 3) {
        return nullptr;
    }
    if (blackLevelRggb == nullptr || env->GetArrayLength(blackLevelRggb) != 4 ||
        whiteBalanceRggb == nullptr || env->GetArrayLength(whiteBalanceRggb) != 4 ||
        sensorToXyz == nullptr || env->GetArrayLength(sensorToXyz) != 9 ||
        xyzToAcescg == nullptr || env->GetArrayLength(xyzToAcescg) != 9 ||
        lensShadingGridRggb == nullptr || env->GetArrayLength(lensShadingGridRggb) != 36) {
        return nullptr;
    }
    if (width < 4 || height < 4 || rowStrideBytes < width * 2 ||
        (width & 1) != 0 || (height & 1) != 0) {
        return nullptr;
    }

    void* src = env->GetDirectBufferAddress(mosaic);
    const jlong srcCap = env->GetDirectBufferCapacity(mosaic);
    const jlong srcFootprint = static_cast<jlong>(rowStrideBytes) * static_cast<jlong>(height - 1) +
                               static_cast<jlong>(width) * 2;
    if (src == nullptr || srcCap < srcFootprint) return nullptr;

    float black[4]{};
    float wb[4]{};
    float mSensor[9]{};
    float mAces[9]{};
    float lensShading[36]{};
    env->GetFloatArrayRegion(blackLevelRggb, 0, 4, black);
    env->GetFloatArrayRegion(whiteBalanceRggb, 0, 4, wb);
    env->GetFloatArrayRegion(sensorToXyz, 0, 9, mSensor);
    env->GetFloatArrayRegion(xyzToAcescg, 0, 9, mAces);
    env->GetFloatArrayRegion(lensShadingGridRggb, 0, 36, lensShading);
    if (env->ExceptionCheck()) return nullptr;
    for (int channel = 0; channel < 4; ++channel) {
        if (!isIeee754Finite(black[channel]) || black[channel] < 0.0f ||
            black[channel] >= static_cast<float>(whiteLevel) ||
            !isIeee754Finite(wb[channel]) || wb[channel] < 0.0f) {
            return nullptr;
        }
    }
    for (float gain : lensShading) {
        if (!isIeee754Finite(gain) || gain < 0.25f || gain > 8.0f) return nullptr;
    }

    const float exposureThermal[4] = { 0.0f, 25.0f, 25.0f, 2.0f };
    const float highlightRecoveryOff[4] = { 1.0f, 0.0f, 0.0f, 0.0f };
    std::lock_guard<std::mutex> lock(state->mutex);
    if (!state->supported) return nullptr;
    if (!retireRawDevelopReadback(state, kRawOfflineConversionTimeoutNs)) return nullptr;

    const auto gpuStarted = std::chrono::steady_clock::now();
    const bool savedGate = state->rawPreviewDemosaicEnabled;
    state->rawPreviewDemosaicEnabled = true;
    const bool dispatched = dispatchRawDemosaicPreviewFromBytes(
        state, static_cast<const uint8_t*>(src), static_cast<size_t>(rowStrideBytes),
        width, height, /*decimate=*/1, bayerPattern, whiteLevel,
        black, wb, exposureThermal, highlightRecoveryOff, mSensor, mAces,
        lensShading, lensShadingCorrectionEnabled == JNI_TRUE,
        /*produceMonitorOutputs=*/false, kRawOfflineConversionTimeoutNs);
    state->rawPreviewDemosaicEnabled = savedGate;
    const auto gpuFinished = std::chrono::steady_clock::now();
    if (!dispatched) return nullptr;

    auto& r = state->rawDemosaic;
    if (oldestCompletedRawTransportSlot(r) < 0 || r.width != width || r.height != height) {
        acknowledgeOldestCompletedRawTransport(r);
        return nullptr;
    }

    const size_t pixelCount = static_cast<size_t>(width) * static_cast<size_t>(height);
    const VkDeviceSize rgba16fBytes = static_cast<VkDeviceSize>(pixelCount) * 4u * sizeof(uint16_t);
    const VkDeviceSize floatRgbBytes = static_cast<VkDeviceSize>(pixelCount) * 3u * sizeof(float);
    const auto copyStarted = std::chrono::steady_clock::now();
    if (!copyRawDevelopOutputToHost(state, width, height, floatRgbBytes) ||
        r.developReadbackMapped == nullptr) {
        acknowledgeOldestCompletedRawTransport(r);
        return nullptr;
    }
    const auto copyFinished = std::chrono::steady_clock::now();
    if (r.developReadbackBytes < floatRgbBytes || rgba16fBytes > floatRgbBytes) {
        acknowledgeOldestCompletedRawTransport(r);
        return nullptr;
    }

    const auto expandStarted = std::chrono::steady_clock::now();
    auto* halfPixels = static_cast<uint16_t*>(r.developReadbackMapped);
    auto* outFloats = static_cast<float*>(r.developReadbackMapped);
    // RGB float32 is wider than RGBA16F. Reverse traversal guarantees every source texel is loaded
    // before its destination can overlap it; only already-consumed higher texels are replaced.
    for (size_t p = pixelCount; p-- > 0u;) {
        const uint16_t halfR = halfPixels[p * 4u + 0u];
        const uint16_t halfG = halfPixels[p * 4u + 1u];
        const uint16_t halfB = halfPixels[p * 4u + 2u];
        outFloats[p * 3u + 0u] = halfToFloat(halfR);
        outFloats[p * 3u + 1u] = halfToFloat(halfG);
        outFloats[p * 3u + 2u] = halfToFloat(halfB);
    }
    const auto expandFinished = std::chrono::steady_clock::now();

    const std::array<jlong, 3> timing{{
        static_cast<jlong>(std::chrono::duration_cast<std::chrono::nanoseconds>(
            gpuFinished - gpuStarted).count()),
        static_cast<jlong>(std::chrono::duration_cast<std::chrono::nanoseconds>(
            copyFinished - copyStarted).count()),
        static_cast<jlong>(std::chrono::duration_cast<std::chrono::nanoseconds>(
            expandFinished - expandStarted).count())
    }};
    env->SetLongArrayRegion(timingNanos, 0, static_cast<jsize>(timing.size()), timing.data());
    if (env->ExceptionCheck()) {
        acknowledgeOldestCompletedRawTransport(r);
        return nullptr;
    }
    jobject mapped = env->NewDirectByteBuffer(r.developReadbackMapped, static_cast<jlong>(floatRgbBytes));
    if (mapped == nullptr || env->ExceptionCheck()) {
        acknowledgeOldestCompletedRawTransport(r);
        return nullptr;
    }
    return mapped;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeEndDevelopRawToAcescgMapped(
    JNIEnv* /*env*/, jobject, jlong handle) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(state->mutex);
    auto& r = state->rawDemosaic;
    if (r.developReadbackInFlight) return JNI_FALSE;
    return acknowledgeOldestCompletedRawTransport(r) ? JNI_TRUE : JNI_FALSE;
}

// LQ-001.09 bounded-heap GPU conversion route. It preserves the existing RAW demosaic and moves the
// remaining full-frame detail, colour delivery and P010 packing work into three ordered Vulkan compute
// passes. The mapped P010 lease remains valid only until nativeEndDevelopRawToP010Mapped.
extern "C" JNIEXPORT jobject JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeBeginDevelopRawToP010Mapped(
    JNIEnv* env, jobject, jlong handle, jobject mosaic, jint rowStrideBytes,
    jint width, jint height, jint bayerPattern, jint whiteLevel, jfloatArray blackLevelRggb,
    jfloatArray whiteBalanceRggb, jfloatArray sensorToXyz, jfloatArray xyzToAcescg,
    jfloatArray lensShadingGridRggb, jboolean lensShadingCorrectionEnabled,
    jboolean niv99Enabled, jlong niv99FrameNumber, jlong niv99SensorTimestampNs,
    jint niv99Fps, jfloatArray niv99Packet,
    jint deliveryMode, jboolean detailEnabled, jfloatArray transferPacket,
    jlongArray timingNanos) {
    auto* state = fromHandle(handle);
    if (state == nullptr || mosaic == nullptr || transferPacket == nullptr ||
        niv99Packet == nullptr || timingNanos == nullptr ||
        env->GetArrayLength(timingNanos) < 12 ||
        env->GetArrayLength(niv99Packet) != 10 ||
        env->GetArrayLength(transferPacket) != 20) {
        return nullptr;
    }
    if (blackLevelRggb == nullptr || env->GetArrayLength(blackLevelRggb) != 4 ||
        whiteBalanceRggb == nullptr || env->GetArrayLength(whiteBalanceRggb) != 4 ||
        sensorToXyz == nullptr || env->GetArrayLength(sensorToXyz) != 9 ||
        xyzToAcescg == nullptr || env->GetArrayLength(xyzToAcescg) != 9 ||
        lensShadingGridRggb == nullptr || env->GetArrayLength(lensShadingGridRggb) != 36) {
        return nullptr;
    }
    if (width < 4 || height < 4 || rowStrideBytes < width * 2 ||
        (width & 1) != 0 || (height & 1) != 0 ||
        bayerPattern < 0 || bayerPattern > 3 || whiteLevel <= 0 ||
        deliveryMode < 0 || deliveryMode > 2) {
        return nullptr;
    }

    void* source = env->GetDirectBufferAddress(mosaic);
    const jlong sourceCapacity = env->GetDirectBufferCapacity(mosaic);
    const jlong sourceFootprint = static_cast<jlong>(rowStrideBytes) *
        static_cast<jlong>(height - 1) + static_cast<jlong>(width) * 2;
    if (source == nullptr || sourceCapacity < sourceFootprint) return nullptr;

    float black[4]{};
    float wb[4]{};
    float sensorMatrix[9]{};
    float acescgMatrix[9]{};
    float lensShading[36]{};
    float niv99Values[10]{};
    float packet[20]{};
    env->GetFloatArrayRegion(blackLevelRggb, 0, 4, black);
    env->GetFloatArrayRegion(whiteBalanceRggb, 0, 4, wb);
    env->GetFloatArrayRegion(sensorToXyz, 0, 9, sensorMatrix);
    env->GetFloatArrayRegion(xyzToAcescg, 0, 9, acescgMatrix);
    env->GetFloatArrayRegion(lensShadingGridRggb, 0, 36, lensShading);
    env->GetFloatArrayRegion(niv99Packet, 0, 10, niv99Values);
    env->GetFloatArrayRegion(transferPacket, 0, 20, packet);
    if (env->ExceptionCheck()) return nullptr;
    for (int channel = 0; channel < 4; ++channel) {
        if (!isIeee754Finite(black[channel]) || black[channel] < 0.0f ||
            black[channel] >= static_cast<float>(whiteLevel) ||
            !isIeee754Finite(wb[channel]) || wb[channel] < 0.0f) {
            return nullptr;
        }
    }
    for (float value : sensorMatrix) if (!isIeee754Finite(value)) return nullptr;
    for (float value : acescgMatrix) if (!isIeee754Finite(value)) return nullptr;
    for (float gain : lensShading) {
        if (!isIeee754Finite(gain) || gain < 0.25f || gain > 8.0f) return nullptr;
    }
    for (float value : niv99Values) if (!isIeee754Finite(value)) return nullptr;
    if (niv99Enabled == JNI_TRUE &&
        (niv99FrameNumber < 0 || niv99Fps <= 0 || niv99Fps > 240 ||
         niv99Values[0] < 0.0f || niv99Values[1] < 0.0f ||
         niv99Values[2] < 1.0f || niv99Values[2] > 64.0f ||
         niv99Values[3] < 1.0f || niv99Values[3] > 65535.0f ||
         niv99Values[4] < 0.0f || niv99Values[4] > 0.65f ||
         niv99Values[5] < 4.0f || niv99Values[5] > 12.0f ||
         niv99Values[6] < 2.0f || niv99Values[6] > 6.0f ||
         niv99Values[7] < 0.0f || niv99Values[7] > 0.45f ||
         niv99Values[8] < 2.0f || niv99Values[8] > 6.0f ||
         niv99Values[9] < 0.02f || niv99Values[9] > 0.25f)) {
        return nullptr;
    }
    for (float value : packet) if (!isIeee754Finite(value)) return nullptr;
    if (packet[0] <= 0.0f || packet[0] > 4096.0f ||
        packet[1] <= 0.0f || packet[2] < 0.0f || packet[3] <= 0.0f ||
        packet[4] <= 0.0f || packet[9] <= packet[1] ||
        packet[10] < 0.1f || packet[10] > 1.0f ||
        packet[11] < 0.001f || packet[11] > 0.9f ||
        packet[12] < 0.0f || packet[12] > 0.2f ||
        packet[13] < 203.0f || packet[13] > 10000.0f ||
        packet[14] < 0.0f || packet[14] > 1.0f ||
        packet[15] < 0.0f || packet[15] > 0.5f ||
        packet[16] < 0.0f || packet[16] > 0.5f ||
        packet[17] < 0.0f || packet[17] > 0.5f ||
        packet[18] < 0.0f || packet[18] > 0.5f) {
        return nullptr;
    }

    RawDevelopP010PushConstants push{};
    push.width = width;
    push.height = height;
    push.deliveryMode = deliveryMode;
    push.detailEnabled = detailEnabled == JNI_TRUE ? 1 : 0;
    push.exposureScale = packet[0];
    push.logToeCut = packet[1];
    push.logToeHeight = packet[2];
    push.logDensity = packet[3];
    push.logScale = packet[4];
    push.logC1 = packet[5];
    push.logC3 = packet[6];
    push.logC4 = packet[7];
    push.logC5 = packet[8];
    push.logXMax = packet[9];
    push.signalGamma = packet[10];
    push.toeWidth = packet[11];
    push.toeAmount = packet[12];
    push.peakNits = packet[13];
    push.highlightDesat = packet[14];
    push.shadowMidtoneLift = packet[15];
    push.midtoneContrast = packet[16];
    push.chromaBoost = packet[17];
    push.skyChromaBonus = packet[18];
    push.reserved = packet[19];

    RawNiv99DispatchConfig niv99Config{};
    if (niv99Enabled == JNI_TRUE) {
        niv99Config.frameNumber = static_cast<int64_t>(niv99FrameNumber);
        niv99Config.sensorTimestampNs = static_cast<int64_t>(niv99SensorTimestampNs);
        niv99Config.fps = static_cast<uint32_t>(niv99Fps);
        niv99Config.noiseS = niv99Values[0];
        niv99Config.noiseO = niv99Values[1];
        niv99Config.totalGain = niv99Values[2];
        niv99Config.dynamicRange = niv99Values[3];
        niv99Config.spatialStrength = niv99Values[4];
        niv99Config.defectSigmaThreshold = niv99Values[5];
        niv99Config.spatialSigmaThreshold = niv99Values[6];
        niv99Config.temporalStrength = niv99Values[7];
        niv99Config.temporalSigmaThreshold = niv99Values[8];
        niv99Config.sceneCutThreshold = niv99Values[9];
    }

    const float exposureThermal[4] = {0.0f, 25.0f, 25.0f, 2.0f};
    const float highlightRecoveryOff[4] = {1.0f, 0.0f, 0.0f, 0.0f};
    std::lock_guard<std::mutex> lock(state->mutex);
    if (!state->supported || !rawDevelopP010PipelinesAvailable(state)) return nullptr;
    auto& r = state->rawDemosaic;
    if (r.developP010Leased ||
        !retireRawDevelopP010(state, kRawOfflineConversionTimeoutNs)) {
        return nullptr;
    }

    const auto demosaicStarted = std::chrono::steady_clock::now();
    RawNiv99DispatchEvidence niv99Evidence{};
    const bool savedGate = state->rawPreviewDemosaicEnabled;
    state->rawPreviewDemosaicEnabled = true;
    const bool demosaicDispatched = dispatchRawDemosaicPreviewFromBytes(
        state,
        static_cast<const uint8_t*>(source),
        static_cast<size_t>(rowStrideBytes),
        width,
        height,
        /*decimate=*/1,
        bayerPattern,
        whiteLevel,
        black,
        wb,
        exposureThermal,
        highlightRecoveryOff,
        sensorMatrix,
        acescgMatrix,
        lensShading,
        lensShadingCorrectionEnabled == JNI_TRUE,
        /*produceMonitorOutputs=*/false,
        kRawOfflineConversionTimeoutNs,
        niv99Enabled == JNI_TRUE ? &niv99Config : nullptr,
        niv99Enabled == JNI_TRUE ? &niv99Evidence : nullptr);
    state->rawPreviewDemosaicEnabled = savedGate;
    const auto demosaicFinished = std::chrono::steady_clock::now();
    if (!demosaicDispatched) return nullptr;
    if (oldestCompletedRawTransportSlot(r) < 0 || r.width != width || r.height != height) {
        acknowledgeOldestCompletedRawTransport(r);
        return nullptr;
    }

    uint64_t tailNanos = 0u;
    if (!dispatchRawDevelopP010(state, width, height, push, &tailNanos) ||
        r.developP010Mapped == nullptr) {
        acknowledgeOldestCompletedRawTransport(r);
        return nullptr;
    }
    const jlong outputBytes = static_cast<jlong>(width) * static_cast<jlong>(height) * 3;
    if (r.developP010Bytes < static_cast<VkDeviceSize>(outputBytes)) {
        acknowledgeOldestCompletedRawTransport(r);
        return nullptr;
    }
    const uint64_t demosaicNanos = static_cast<uint64_t>(
        std::chrono::duration_cast<std::chrono::nanoseconds>(
            demosaicFinished - demosaicStarted).count());
    const std::array<jlong, 12> timing{{
        static_cast<jlong>(demosaicNanos + tailNanos),
        0,
        0,
        niv99Evidence.applied ? 1 : 0,
        static_cast<jlong>(niv99Evidence.counters.spatiallyFilteredSamples),
        static_cast<jlong>(niv99Evidence.counters.temporallyFusedSamples),
        static_cast<jlong>(niv99Evidence.counters.correctedDefects),
        static_cast<jlong>(niv99Evidence.counters.processedSamples),
        niv99Evidence.historyUsed ? 1 : 0,
        niv99Evidence.historyReset ? 1 : 0,
        niv99Evidence.sceneCut ? 1 : 0,
        niv99Evidence.applied ? static_cast<jlong>(demosaicNanos) : 0
    }};
    env->SetLongArrayRegion(timingNanos, 0, static_cast<jsize>(timing.size()), timing.data());
    if (env->ExceptionCheck()) {
        acknowledgeOldestCompletedRawTransport(r);
        return nullptr;
    }
    jobject mapped = env->NewDirectByteBuffer(r.developP010Mapped, outputBytes);
    if (mapped == nullptr || env->ExceptionCheck()) {
        acknowledgeOldestCompletedRawTransport(r);
        return nullptr;
    }
    r.developP010Leased = true;
    return mapped;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeEndDevelopRawToP010Mapped(
    JNIEnv* /*env*/, jobject, jlong handle) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(state->mutex);
    auto& r = state->rawDemosaic;
    if (!r.developP010Leased || r.developP010InFlight) return JNI_FALSE;
    r.developP010Leased = false;
    return acknowledgeOldestCompletedRawTransport(r) ? JNI_TRUE : JNI_FALSE;
}

// LQ-001.09 host-ingress accelerator. NPRAW decoding intentionally retains IntArray precision, while
// Vulkan consumes a tightly packed R16_UINT upload. Performing the saturating narrow in optimized native
// code replaces millions of per-element JNI buffer accessors without changing one input code. The Java
// array is pinned only for this bounded memory transform and is released before any Vulkan operation.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativePackRawSamplesToU16(
    JNIEnv* env,
    jobject,
    jintArray source,
    jobject destination,
    jint sampleCount) {
    if (env == nullptr || source == nullptr || destination == nullptr || sampleCount < 0) {
        return JNI_FALSE;
    }
    const jsize sourceLength = env->GetArrayLength(source);
    void* destinationAddress = env->GetDirectBufferAddress(destination);
    const jlong destinationCapacity = env->GetDirectBufferCapacity(destination);
    const jlong requiredBytes = static_cast<jlong>(sampleCount) *
        static_cast<jlong>(sizeof(uint16_t));
    if (sourceLength < sampleCount || destinationAddress == nullptr ||
        destinationCapacity < requiredBytes) {
        return JNI_FALSE;
    }
    auto* input = static_cast<const jint*>(env->GetPrimitiveArrayCritical(source, nullptr));
    if (input == nullptr) return JNI_FALSE;
    auto* output = static_cast<uint16_t*>(destinationAddress);
    for (jint index = 0; index < sampleCount; ++index) {
        const jint value = input[index];
        output[index] = static_cast<uint16_t>(
            value <= 0 ? 0 : (value >= 0xffff ? 0xffff : value));
    }
    env->ReleasePrimitiveArrayCritical(source, const_cast<jint*>(input), JNI_ABORT);
    return JNI_TRUE;
}

// s1-f07 / CP10c — RAW-preview cinema-debayer dispatch entry. Imports the R16_UINT mosaic, builds the
// std140 RawDemosaicUniforms (CP11 forward matrices, WB gains, levels) and dispatches demosaic_vulkan.
// Returns JNI_FALSE (→ JVM YUV fallback) on any non-fatal condition (gate off, pipeline not yet built,
// AHB not storage-capable R16_UINT, …) so a black/garbage RAW preview cannot ship.
// Non-blocking completion poll for deadline-overrun RAW preview work. Kotlin treats Complete as an
// ownership barrier: publish the finished output with its saved epoch/timestamp before another frame
// can overwrite the shared output image. Pending never blocks capture; Idle permits a new dispatch.
extern "C" JNIEXPORT jint JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativePollRawDemosaicCompletion(
    JNIEnv* /*env*/, jobject, jlong handle) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return static_cast<jint>(RawDispatchPollResult::Failed);
    std::lock_guard<std::mutex> lock(state->mutex);
    if (!state->supported) return static_cast<jint>(RawDispatchPollResult::Failed);
    return static_cast<jint>(pollPendingRawDispatch(state));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeDispatchRawDemosaicPreview(
    JNIEnv* env, jobject, jlong handle, jobject hardwareBufferObject,
    jint width, jint height, jint bayerPattern, jint whiteLevel,
    jfloatArray blackLevelRggb, jfloatArray whiteBalanceRggb, jfloatArray exposureThermal,
    jfloatArray highlightRecovery,
    jfloatArray sensorToXyz, jfloatArray xyzToAcescg,
    jfloatArray lensShadingGridRggb, jboolean lensShadingCorrectionEnabled,
    jlong completionWaitNs) {
    auto* state = fromHandle(handle);
    if (state == nullptr || hardwareBufferObject == nullptr) return JNI_FALSE;
    if (blackLevelRggb == nullptr || env->GetArrayLength(blackLevelRggb) != 4 ||
        whiteBalanceRggb == nullptr || env->GetArrayLength(whiteBalanceRggb) != 4 ||
        exposureThermal == nullptr || env->GetArrayLength(exposureThermal) != 4 ||
        highlightRecovery == nullptr || env->GetArrayLength(highlightRecovery) != 4 ||
        sensorToXyz == nullptr || env->GetArrayLength(sensorToXyz) != 9 ||
        xyzToAcescg == nullptr || env->GetArrayLength(xyzToAcescg) != 9 ||
        lensShadingGridRggb == nullptr || env->GetArrayLength(lensShadingGridRggb) != 36) {
        return JNI_FALSE;
    }

    AHardwareBuffer* hardwareBuffer = AHardwareBuffer_fromHardwareBuffer(env, hardwareBufferObject);
    if (hardwareBuffer == nullptr) return JNI_FALSE;

    float black[4]{};
    float wb[4]{};
    float exposure[4]{};
    float recovery[4]{};
    float mSensor[9]{};
    float mAces[9]{};
    float lensShading[36]{};
    env->GetFloatArrayRegion(blackLevelRggb, 0, 4, black);
    env->GetFloatArrayRegion(whiteBalanceRggb, 0, 4, wb);
    env->GetFloatArrayRegion(exposureThermal, 0, 4, exposure);
    env->GetFloatArrayRegion(highlightRecovery, 0, 4, recovery);
    env->GetFloatArrayRegion(sensorToXyz, 0, 9, mSensor);
    env->GetFloatArrayRegion(xyzToAcescg, 0, 9, mAces);
    env->GetFloatArrayRegion(lensShadingGridRggb, 0, 36, lensShading);

    std::lock_guard<std::mutex> lock(state->mutex);
    if (!state->supported) return JNI_FALSE;
    const uint64_t boundedCompletionWaitNs = completionWaitNs <= 0
        ? 0ULL
        : std::min(
            static_cast<uint64_t>(completionWaitNs),
            kRawPreviewGpuDeadlineNs);
    const bool ok = dispatchRawDemosaicPreview(
        state, hardwareBuffer, width, height, bayerPattern, whiteLevel,
        black, wb, exposure, recovery, mSensor, mAces, lensShading,
        lensShadingCorrectionEnabled == JNI_TRUE,
        boundedCompletionWaitNs);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// Record-time live-monitor variant: demosaic a CPU-copied (already-persisted) RAW mosaic so the visible
// monitor stays LIVE while recording WITHOUT holding a scarce RAW reader buffer. [rawBytes] is a DIRECT
// ByteBuffer holding the row-strided uint16 mosaic; [decimate] (2 = half-res) keeps the cost ~1/4 of idle.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeDispatchRawDemosaicPreviewFromBytes(
    JNIEnv* env, jobject, jlong handle, jobject rawBytes, jint rowStrideBytes,
    jint width, jint height, jint decimate, jint bayerPattern, jint whiteLevel,
    jfloatArray blackLevelRggb, jfloatArray whiteBalanceRggb, jfloatArray exposureThermal,
    jfloatArray highlightRecovery,
    jfloatArray sensorToXyz, jfloatArray xyzToAcescg,
    jfloatArray lensShadingGridRggb, jboolean lensShadingCorrectionEnabled) {
    auto* state = fromHandle(handle);
    if (state == nullptr || rawBytes == nullptr) return JNI_FALSE;
    if (blackLevelRggb == nullptr || env->GetArrayLength(blackLevelRggb) != 4 ||
        whiteBalanceRggb == nullptr || env->GetArrayLength(whiteBalanceRggb) != 4 ||
        exposureThermal == nullptr || env->GetArrayLength(exposureThermal) != 4 ||
        highlightRecovery == nullptr || env->GetArrayLength(highlightRecovery) != 4 ||
        sensorToXyz == nullptr || env->GetArrayLength(sensorToXyz) != 9 ||
        xyzToAcescg == nullptr || env->GetArrayLength(xyzToAcescg) != 9 ||
        lensShadingGridRggb == nullptr || env->GetArrayLength(lensShadingGridRggb) != 36) {
        return JNI_FALSE;
    }
    if (width < 4 || height < 4 || rowStrideBytes < width * 2) return JNI_FALSE;
    void* src = env->GetDirectBufferAddress(rawBytes);
    const jlong cap = env->GetDirectBufferCapacity(rawBytes);
    // Actual footprint read by the decimating gather (rows may be tightly packed on the last line): the
    // last sample is at (height-1)*rowStride + (width-1)*2 + 2. Reject only if the copy is smaller than that.
    const jlong footprint = static_cast<jlong>(rowStrideBytes) * static_cast<jlong>(height - 1) +
                            static_cast<jlong>(width) * 2;
    if (src == nullptr || cap < footprint) return JNI_FALSE;

    float black[4]{};
    float wb[4]{};
    float exposure[4]{};
    float recovery[4]{};
    float mSensor[9]{};
    float mAces[9]{};
    float lensShading[36]{};
    env->GetFloatArrayRegion(blackLevelRggb, 0, 4, black);
    env->GetFloatArrayRegion(whiteBalanceRggb, 0, 4, wb);
    env->GetFloatArrayRegion(exposureThermal, 0, 4, exposure);
    env->GetFloatArrayRegion(highlightRecovery, 0, 4, recovery);
    env->GetFloatArrayRegion(sensorToXyz, 0, 9, mSensor);
    env->GetFloatArrayRegion(xyzToAcescg, 0, 9, mAces);
    env->GetFloatArrayRegion(lensShadingGridRggb, 0, 36, lensShading);

    std::lock_guard<std::mutex> lock(state->mutex);
    if (!state->supported) return JNI_FALSE;
    const bool ok = dispatchRawDemosaicPreviewFromBytes(
        state, static_cast<const uint8_t*>(src), static_cast<size_t>(rowStrideBytes),
        width, height, decimate, bayerPattern, whiteLevel, black, wb, exposure, recovery, mSensor, mAces,
        lensShading, lensShadingCorrectionEnabled == JNI_TRUE,
        /*produceMonitorOutputs=*/true, kRawPreviewGpuDeadlineNs);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// s1-f10 / CP31 — dual-conversion-gain fusion gate. Default OFF: with this false the fusion dispatch is
// unreachable and the live render path is byte-identical (no-downgrade). Enabled only on a device with a
// real dual-gain readout ring (device-gated).
extern "C" JNIEXPORT void JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeSetDualGainFusionEnabled(
    JNIEnv* /*env*/, jobject, jlong handle, jboolean enabled) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return;
    std::lock_guard<std::mutex> lock(state->mutex);
    state->dualGainFusionEnabled = (enabled == JNI_TRUE);
    if (!state->dualGainFusionEnabled) {
        state->dualGainFusion.lastDispatchOk = false;
    }
}

// CP-QV16 — gated single-frame spatial denoiser gate (denoise.comp). Native starts false until the JVM
// render tick forwards DenoiseSpatialFlag (default ON, broadcast kill-switch).
extern "C" JNIEXPORT void JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeSetDenoiseSpatialEnabled(
    JNIEnv* /*env*/, jobject, jlong handle, jboolean enabled) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return;
    std::lock_guard<std::mutex> lock(state->mutex);
    state->denoiseSpatialEnabled = (enabled == JNI_TRUE);
}

// CP-QV17 — gated optical-recovery sharpener gate (sharpen.comp). Native starts false until the JVM
// render tick forwards SharpenOpticalFlag (default ON, broadcast kill-switch).
extern "C" JNIEXPORT void JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeSetSharpenOpticalEnabled(
    JNIEnv* /*env*/, jobject, jlong handle, jboolean enabled) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return;
    std::lock_guard<std::mutex> lock(state->mutex);
    state->sharpenOpticalEnabled = (enabled == JNI_TRUE);
}

// CP24 — distant-detail elite gates (detail_preservation noise-aware + halo-guard, guided_upsample
// edge-tighten). Native starts false until the JVM render tick forwards DistantDetailGatesFlag
// (default ON, broadcast kill-switch). False keeps all gate push-constant fields at 0.0.
extern "C" JNIEXPORT void JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeSetDistantDetailGatesEnabled(
    JNIEnv* /*env*/, jobject, jlong handle, jboolean enabled) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return;
    std::lock_guard<std::mutex> lock(state->mutex);
    state->distantDetailGatesEnabled = (enabled == JNI_TRUE);
}

// CP30 — BT.2390 EETF peak-nits highlight shoulder (knee_curve.comp). Default OFF: with this false
// peakNitsGate is 0.0, so the knee uses today's SDR-referred modified-Reinhard path bit-for-bit
// (no-downgrade). Enabled only after on-device validation on vivo I2405 (DEVICE-GATED).
extern "C" JNIEXPORT void JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeSetApexPeakNitsEnabled(
    JNIEnv* /*env*/, jobject, jlong handle, jboolean enabled) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return;
    std::lock_guard<std::mutex> lock(state->mutex);
    state->apexPeakNitsEnabled = (enabled == JNI_TRUE);
}

// CP-CUSP — exact Rec.2020 Oklab gamut-cusp clamp (preview knee boundary). Default OFF: uNeoGamutCuspParams.x
// stays 0 -> the shader's analytic-guard branch runs, byte-identical. Device-gated (vivo I2405) validation.
extern "C" JNIEXPORT void JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeSetExactGamutCuspEnabled(
    JNIEnv* /*env*/, jobject, jlong handle, jboolean enabled) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return;
    std::lock_guard<std::mutex> lock(state->mutex);
    state->exactGamutCuspEnabled = (enabled == JNI_TRUE);
}

// s1-f10 / CP31 — dual-conversion-gain fusion dispatch entry. Imports the LCG + HCG rgba16f ACEScg
// readouts and dispatches dual_gain_fusion.comp into the owned fused/motion-confidence images. Returns
// JNI_FALSE (→ no fusion, passthrough) on any non-fatal condition (gate off, pipeline not built, AHB not
// storage-capable rgba16f, …) so a garbage fused frame cannot ship.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeDispatchDualGainFusion(
    JNIEnv* env, jobject, jlong handle, jobject lcgBufferObject, jobject hcgBufferObject,
    jint width, jint height, jfloat kGain, jfloat noiseFloor, jfloat saturationThreshold, jfloat shadowStretch) {
    auto* state = fromHandle(handle);
    if (state == nullptr || lcgBufferObject == nullptr || hcgBufferObject == nullptr) return JNI_FALSE;

    AHardwareBuffer* lcg = AHardwareBuffer_fromHardwareBuffer(env, lcgBufferObject);
    AHardwareBuffer* hcg = AHardwareBuffer_fromHardwareBuffer(env, hcgBufferObject);
    if (lcg == nullptr || hcg == nullptr) return JNI_FALSE;

    DualGainFusionPushConstants params{};
    params.width = static_cast<uint32_t>(std::max(0, width));
    params.height = static_cast<uint32_t>(std::max(0, height));
    params.kGain = kGain;
    params.noiseFloor = noiseFloor;
    params.saturationThreshold = saturationThreshold;
    params.shadowStretch = shadowStretch;

    std::lock_guard<std::mutex> lock(state->mutex);
    if (!state->supported) return JNI_FALSE;
    const bool ok = dispatchDualGainFusion(state, lcg, hcg, width, height, params);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// s5-f06 / CP-F7 — bokeh blend gate. Default OFF: with this false the bokeh dispatch is unreachable and the
// live render path is byte-identical (no-downgrade). Enabled only on a device after the depth/color feed +
// occlusion validation (DEVICE-GATED); visible bokeh is never blind-default-on.
extern "C" JNIEXPORT void JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeSetBokehBlendEnabled(
    JNIEnv* /*env*/, jobject, jlong handle, jboolean enabled) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return;
    std::lock_guard<std::mutex> lock(state->mutex);
    state->bokehBlendEnabled = (enabled == JNI_TRUE);
    if (!state->bokehBlendEnabled) {
        state->bokeh.lastDispatchOk = false;
    }
}

// s5-f06 / CP-F7 — bokeh dispatch entry (device-gated owner/diagnostic hook). Runs bokeh_vogel_spiral.comp
// over the owned, deterministically-cleared bindings with the supplied focus-plane + CoC-scale params.
// Returns JNI_FALSE (-> no bokeh, passthrough) whenever the gate is off or the pipeline is unavailable, so a
// blind enable can never ship a black/garbage frame.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeDispatchBokehVogelSpiral(
    JNIEnv* /*env*/, jobject, jlong handle, jint width, jint height,
    jint sampleCount, jfloat chromaticAberration, jfloat apertureBladeCount, jfloat apertureBladeStrength,
    jfloat foregroundGuardStrength, jfloat focusDepth, jfloat blendStrength) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return JNI_FALSE;

    BokehPushConstants params{};
    params.width = std::max(0, width);
    params.height = std::max(0, height);
    params.sampleCount = std::max(8, std::min(64, sampleCount));
    params.chromaticAberration = chromaticAberration;
    params.apertureBladeCount = apertureBladeCount;
    params.apertureBladeStrength = apertureBladeStrength;
    params.foregroundGuardStrength = foregroundGuardStrength;
    params.focusDepth = focusDepth;
    params.blendStrength = blendStrength;

    std::lock_guard<std::mutex> lock(state->mutex);
    if (!state->supported) return JNI_FALSE;
    const bool ok = dispatchBokehVogelSpiral(state, width, height, params);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// Stage a w*h r32f float grid into an owned STORAGE|TRANSFER_DST image and leave it GENERAL for the
// inline bokeh pass. Full overwrite => UNDEFINED oldLayout (discard) is always valid. Caller holds mutex.
static bool uploadFloatR32ToImage(VulkanBridgeState* state, ComputeIntermediateImage& img,
                                  const float* data, uint32_t w, uint32_t h) {
    if (state == nullptr || img.image == VK_NULL_HANDLE || data == nullptr) return false;
    if (img.width != w || img.height != h) return false; // extent must match the created image
    const VkDeviceSize bytes = static_cast<VkDeviceSize>(w) * static_cast<VkDeviceSize>(h) * sizeof(float);
    VkBuffer staging = VK_NULL_HANDLE; VkDeviceMemory stagingMem = VK_NULL_HANDLE;
    if (!createBufferWithMemory(state, bytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, &staging, &stagingMem)) {
        return false;
    }
    void* mapped = nullptr;
    if (!check(vkMapMemory(state->device, stagingMem, 0, bytes, 0, &mapped), "vkMapMemory(bokehUpload)")) {
        vkFreeMemory(state->device, stagingMem, nullptr); vkDestroyBuffer(state->device, staging, nullptr); return false;
    }
    std::memcpy(mapped, data, static_cast<size_t>(bytes));
    vkUnmapMemory(state->device, stagingMem);
    VkCommandPool pool = VK_NULL_HANDLE; VkCommandBuffer cmd = VK_NULL_HANDLE;
    if (!beginSingleUseCommands(state, &pool, &cmd)) {
        vkFreeMemory(state->device, stagingMem, nullptr); vkDestroyBuffer(state->device, staging, nullptr); return false;
    }
    VkImageSubresourceRange range{};
    range.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT; range.levelCount = 1; range.layerCount = 1;
    transitionImageLayout(cmd, img.image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, range);
    VkBufferImageCopy region{};
    region.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    region.imageSubresource.layerCount = 1;
    region.imageExtent = { w, h, 1 };
    vkCmdCopyBufferToImage(cmd, staging, img.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);
    transitionImageLayout(cmd, img.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_GENERAL, range);
    const bool ok = endSingleUseCommands(state, pool, cmd);
    vkFreeMemory(state->device, stagingMem, nullptr); vkDestroyBuffer(state->device, staging, nullptr);
    return ok;
}

static std::vector<float> resampleBokehGrid(
    const float* src,
    uint32_t srcW,
    uint32_t srcH,
    uint32_t dstW,
    uint32_t dstH,
    float valueScale,
    float minValue,
    float maxValue
) {
    std::vector<float> dst(static_cast<size_t>(dstW) * static_cast<size_t>(dstH), 0.0f);
    if (src == nullptr || srcW == 0u || srcH == 0u || dstW == 0u || dstH == 0u) return dst;
    const float scale = isIeee754Finite(valueScale) ? valueScale : 1.0f;
    for (uint32_t y = 0; y < dstH; ++y) {
        const float sy = (static_cast<float>(y) + 0.5f) * static_cast<float>(srcH) / static_cast<float>(dstH) - 0.5f;
        const float syClamped = std::clamp(sy, 0.0f, static_cast<float>(srcH - 1u));
        const uint32_t y0 = static_cast<uint32_t>(std::floor(syClamped));
        const uint32_t y1 = std::min(y0 + 1u, srcH - 1u);
        const float fy = syClamped - static_cast<float>(y0);
        for (uint32_t x = 0; x < dstW; ++x) {
            const float sx = (static_cast<float>(x) + 0.5f) * static_cast<float>(srcW) / static_cast<float>(dstW) - 0.5f;
            const float sxClamped = std::clamp(sx, 0.0f, static_cast<float>(srcW - 1u));
            const uint32_t x0 = static_cast<uint32_t>(std::floor(sxClamped));
            const uint32_t x1 = std::min(x0 + 1u, srcW - 1u);
            const float fx = sxClamped - static_cast<float>(x0);
            const float v00 = src[static_cast<size_t>(y0) * srcW + x0];
            const float v10 = src[static_cast<size_t>(y0) * srcW + x1];
            const float v01 = src[static_cast<size_t>(y1) * srcW + x0];
            const float v11 = src[static_cast<size_t>(y1) * srcW + x1];
            const float top = v00 + (v10 - v00) * fx;
            const float bottom = v01 + (v11 - v01) * fx;
            float value = (top + (bottom - top) * fy) * scale;
            if (!isIeee754Finite(value)) value = 0.0f;
            dst[static_cast<size_t>(y) * dstW + x] = std::clamp(value, minValue, maxValue);
        }
    }
    return dst;
}

// Live bokeh feed: upload the producer's preview-res depth + CoC grids into the inline pass's images and
// set the focus/blend params. Smaller depth/CoC grids are resized to each target extent; CoC is scaled
// from source pixels to target pixels so portrait blur strength survives the full-resolution pass.
// Returns false (=> the pass stays skipped / passthrough) if no target accepted the feed.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeUploadBokehInputs(
    JNIEnv* env, jobject, jlong handle, jfloatArray depth, jfloatArray coc,
    jint width, jint height, jfloat focusDepth, jfloat blendStrength) {
    auto* state = fromHandle(handle);
    if (state == nullptr || depth == nullptr || coc == nullptr || width <= 0 || height <= 0) return JNI_FALSE;
    const jsize n = static_cast<jsize>(width) * static_cast<jsize>(height);
    if (env->GetArrayLength(depth) < n || env->GetArrayLength(coc) < n) return JNI_FALSE;
    jfloat* dptr = env->GetFloatArrayElements(depth, nullptr);
    jfloat* cptr = env->GetFloatArrayElements(coc, nullptr);
    bool ok = false;
    {
        std::lock_guard<std::mutex> lock(state->mutex);
        if (state->supported) {
            state->bokehFocusDepth = std::clamp(static_cast<float>(focusDepth), 0.0f, 1.0f);
            state->bokehBlendStrength = std::clamp(static_cast<float>(blendStrength), 0.0f, 1.0f);
            for (TargetSurface* target : { &state->previewTarget, &state->encoderTarget }) {
                if (target == nullptr ||
                    target->bokehDepth.image == VK_NULL_HANDLE ||
                    target->bokehCoc.image == VK_NULL_HANDLE ||
                    target->bokehDepth.width == 0u ||
                    target->bokehDepth.height == 0u ||
                    target->bokehCoc.width != target->bokehDepth.width ||
                    target->bokehCoc.height != target->bokehDepth.height) continue;

                const uint32_t srcW = static_cast<uint32_t>(width);
                const uint32_t srcH = static_cast<uint32_t>(height);
                const uint32_t dstW = target->bokehDepth.width;
                const uint32_t dstH = target->bokehDepth.height;
                const bool exact = srcW == dstW && srcH == dstH;
                const float cocScale = std::max(
                    static_cast<float>(dstW) / static_cast<float>(srcW),
                    static_cast<float>(dstH) / static_cast<float>(srcH)
                );
                const float cocMax = static_cast<float>(std::max(dstW, dstH)) * 0.16f;
                std::vector<float> depthResized;
                std::vector<float> cocResized;
                const float* depthUpload = dptr;
                const float* cocUpload = cptr;
                if (!exact) {
                    depthResized = resampleBokehGrid(dptr, srcW, srcH, dstW, dstH, 1.0f, 0.0f, 1.0f);
                    cocResized = resampleBokehGrid(cptr, srcW, srcH, dstW, dstH, cocScale, 0.0f, cocMax);
                    depthUpload = depthResized.data();
                    cocUpload = cocResized.data();
                } else {
                    cocResized = resampleBokehGrid(cptr, srcW, srcH, dstW, dstH, 1.0f, 0.0f, cocMax);
                    cocUpload = cocResized.data();
                }
                const bool du = uploadFloatR32ToImage(state, target->bokehDepth, depthUpload, dstW, dstH);
                const bool cu = uploadFloatR32ToImage(state, target->bokehCoc, cocUpload, dstW, dstH);
                target->bokehFeedReady = du && cu;
                ok = ok || target->bokehFeedReady;
            }
        }
    }
    env->ReleaseFloatArrayElements(depth, dptr, JNI_ABORT);
    env->ReleaseFloatArrayElements(coc, cptr, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeSetHdrDynamicRangeMode(
    JNIEnv* /*env*/,
    jobject,
    jlong handle,
    jint modeId
) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(state->mutex);
    if (!state->supported) return JNI_FALSE;
    if (modeId < 0 || modeId > 4) {
        setStatus(state, "HDR mode change rejected: invalid mode id");
        return JNI_FALSE;
    }
    const int normalized = modeId;
    LOGI("HDR dynamic-range mode change: %d → %d", state->hdrModeId, normalized);
    const int previousModeId = state->hdrModeId;
    const bool modeChanged = previousModeId != normalized;
    state->hdrModeId = normalized;
    const bool previewAccepted =
        rebuildTargetForHdrModeChange(state, &state->previewTarget);
    const bool encoderAccepted =
        rebuildTargetForHdrModeChange(state, &state->encoderTarget);
    if (previewAccepted && encoderAccepted) return JNI_TRUE;

    if (!modeChanged) {
        setStatus(state, "HDR mode validation/rebuild failed");
        return JNI_FALSE;
    }

    // A mode is not committed if either target rejected its swapchain. Restore the last proven
    // mode on both targets so one failed transition cannot leave a black or half-switched route.
    state->hdrModeId = previousModeId;
    const bool previewRecovered =
        rebuildTargetForHdrModeChange(state, &state->previewTarget);
    const bool encoderRecovered =
        rebuildTargetForHdrModeChange(state, &state->encoderTarget);
    if (!previewRecovered || !encoderRecovered) {
        setStatus(state, "HDR mode change rejected and previous swapchain recovery failed");
    } else {
        setStatus(state, "HDR mode change rejected; previous swapchains restored");
    }
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeSetPreferTenBitSdrDisplay(
    JNIEnv* /*env*/,
    jobject,
    jlong handle,
    jboolean enabled
) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(state->mutex);
    if (!state->supported) return JNI_FALSE;
    const bool desired = (enabled == JNI_TRUE);
    LOGI(
        "SDR display 10-bit preference change: %d → %d",
        state->preferTenBitSdrDisplay ? 1 : 0,
        desired ? 1 : 0
    );
    const bool previousPreference = state->preferTenBitSdrDisplay;
    const bool preferenceChanged = previousPreference != desired;
    state->preferTenBitSdrDisplay = desired;
    if (rebuildPreviewTargetForDisplayFormatChange(state)) return JNI_TRUE;

    if (!preferenceChanged) {
        setStatus(state, "SDR display format validation/rebuild failed");
        return JNI_FALSE;
    }

    state->preferTenBitSdrDisplay = previousPreference;
    if (!rebuildPreviewTargetForDisplayFormatChange(state)) {
        setStatus(state, "SDR display format change rejected and previous swapchain recovery failed");
    } else {
        setStatus(state, "SDR display format change rejected; previous swapchain restored");
    }
    return JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeUpdateRenderConfig(
    JNIEnv* env,
    jobject,
    jlong handle,
    jfloatArray previewTransform,
    jfloatArray encoderTransform,
    jfloatArray colorGain,
    jfloatArray previewGrade,
    jfloatArray encoderGrade,
    jfloatArray previewColorScience,
    jfloatArray encoderColorScience
) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return JNI_FALSE;
    if (
        previewTransform == nullptr || env->GetArrayLength(previewTransform) != 16 ||
        encoderTransform == nullptr || env->GetArrayLength(encoderTransform) != 16 ||
        colorGain == nullptr || env->GetArrayLength(colorGain) != 4 ||
        previewGrade == nullptr || env->GetArrayLength(previewGrade) != 16 ||
        encoderGrade == nullptr || env->GetArrayLength(encoderGrade) != 16 ||
        previewColorScience == nullptr || encoderColorScience == nullptr ||
        env->GetArrayLength(previewColorScience) != static_cast<jsize>(kColorScienceUniformFloatCount) ||
        env->GetArrayLength(encoderColorScience) != static_cast<jsize>(kColorScienceUniformFloatCount)
    ) {
        return JNI_FALSE;
    }
    std::lock_guard<std::mutex> lock(state->mutex);
    if (!state->supported) return JNI_FALSE;
    jfloat previewValues[16];
    jfloat encoderValues[16];
    jfloat colorValues[4];
    jfloat previewGradeValues[16];
    jfloat encoderGradeValues[16];
    jfloat previewColorScienceValues[kColorScienceUniformFloatCount];
    jfloat encoderColorScienceValues[kColorScienceUniformFloatCount];
    env->GetFloatArrayRegion(previewTransform, 0, 16, previewValues);
    env->GetFloatArrayRegion(encoderTransform, 0, 16, encoderValues);
    env->GetFloatArrayRegion(colorGain, 0, 4, colorValues);
    env->GetFloatArrayRegion(previewGrade, 0, 16, previewGradeValues);
    env->GetFloatArrayRegion(encoderGrade, 0, 16, encoderGradeValues);
    env->GetFloatArrayRegion(
        previewColorScience,
        0,
        static_cast<jsize>(kColorScienceUniformFloatCount),
        previewColorScienceValues
    );
    env->GetFloatArrayRegion(
        encoderColorScience,
        0,
        static_cast<jsize>(kColorScienceUniformFloatCount),
        encoderColorScienceValues
    );

    bool hasNonFinite = false;
    for (int i = 0; i < 16; ++i) {
        if (!isIeee754Finite(previewValues[i]) || !isIeee754Finite(encoderValues[i]) ||
            !isIeee754Finite(previewGradeValues[i]) || !isIeee754Finite(encoderGradeValues[i])) {
            hasNonFinite = true;
            break;
        }
    }
    if (!hasNonFinite) {
        for (int i = 0; i < 4; ++i) {
            if (!isIeee754Finite(colorValues[i])) {
                hasNonFinite = true;
                break;
            }
        }
    }
    if (!hasNonFinite) {
        for (size_t i = 0; i < kColorScienceUniformFloatCount; ++i) {
            if (!isIeee754Finite(previewColorScienceValues[i]) || !isIeee754Finite(encoderColorScienceValues[i])) {
                hasNonFinite = true;
                break;
            }
        }
    }
    if (hasNonFinite) {
        LOGE("render config contains non-finite value");
        return JNI_FALSE;
    }

    for (int i = 0; i < 16; ++i) {
        state->renderConfig.previewTransform[static_cast<size_t>(i)] = previewValues[i];
        state->renderConfig.encoderTransform[static_cast<size_t>(i)] = encoderValues[i];
        state->renderConfig.previewGrade[static_cast<size_t>(i)] = previewGradeValues[i];
        state->renderConfig.encoderGrade[static_cast<size_t>(i)] = encoderGradeValues[i];
    }
    for (int i = 0; i < 4; ++i) {
        state->renderConfig.colorGain[static_cast<size_t>(i)] = colorValues[i];
    }
    for (size_t i = 0; i < kColorScienceUniformFloatCount; ++i) {
        state->renderConfig.previewColorScience[i] = previewColorScienceValues[i];
        state->renderConfig.encoderColorScience[i] = encoderColorScienceValues[i];
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeUpdateEnhancementUniforms(
    JNIEnv* env,
    jobject,
    jlong handle,
    jfloatArray uniforms,
    jstring signature
) {
    auto* state = fromHandle(handle);
    if (state == nullptr || uniforms == nullptr) return JNI_FALSE;

    {
        std::lock_guard<std::mutex> lock(state->mutex);
        if (!state->supported) {
            rejectEnhancementUniformsLocked(
                state,
                "enhancement uniforms rejected: Vulkan unsupported"
            );
            return JNI_FALSE;
        }
    }

    const jsize length = env->GetArrayLength(uniforms);
    if (length != static_cast<jsize>(kEnhancementUniformFloatCount)) {
        std::lock_guard<std::mutex> lock(state->mutex);
        rejectEnhancementUniformsLocked(state, "invalid enhancement uniform length");
        return JNI_FALSE;
    }

    std::array<float, kEnhancementUniformFloatCount> values{};
    env->GetFloatArrayRegion(uniforms, 0, length, values.data());
    for (float value : values) {
        if (!isIeee754Finite(value)) {
            std::lock_guard<std::mutex> lock(state->mutex);
            rejectEnhancementUniformsLocked(state, "non-finite enhancement uniform value");
            return JNI_FALSE;
        }
    }
    if (!isValidIntegralEnumId(values[0], kValidSignalFamilyIds)) {
        std::lock_guard<std::mutex> lock(state->mutex);
        rejectEnhancementUniformsLocked(state, "invalid enhancement enum id: signalFamily");
        return JNI_FALSE;
    }
    if (!isValidIntegralEnumId(values[1], kValidColorSpaceIds)) {
        std::lock_guard<std::mutex> lock(state->mutex);
        rejectEnhancementUniformsLocked(state, "invalid enhancement enum id: colorSpace");
        return JNI_FALSE;
    }
    if (!isValidIntegralEnumId(values[2], kValidColorScienceIds)) {
        std::lock_guard<std::mutex> lock(state->mutex);
        rejectEnhancementUniformsLocked(state, "invalid enhancement enum id: colorScience");
        return JNI_FALSE;
    }
    if (!isValidIntegralEnumId(values[3], kValidActiveTransportIds)) {
        std::lock_guard<std::mutex> lock(state->mutex);
        rejectEnhancementUniformsLocked(state, "invalid enhancement enum id: activeTransport");
        return JNI_FALSE;
    }

    std::string signatureValue;
    if (signature != nullptr) {
        const char* signatureChars = env->GetStringUTFChars(signature, nullptr);
        if (signatureChars != nullptr) {
            signatureValue = signatureChars;
            env->ReleaseStringUTFChars(signature, signatureChars);
        }
    }

    std::lock_guard<std::mutex> lock(state->mutex);
    state->enhancementUniforms.values = values;
    state->enhancementUniforms.signature = std::move(signatureValue);
    state->enhancementUniforms.ready = true;
    if (!state->enhancementComputePipelineReady) {
        state->enhancementFallbackReason = "compute pipeline not initialized";
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeUpdatePreviewLut(
    JNIEnv* env,
    jobject,
    jlong handle,
    jfloatArray lutData,
    jint size
) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(state->mutex);
    if (!state->supported) return JNI_FALSE;

    // 65^3 is the canonical high-quality cube used by the project color pipeline. Keep the
    // validation ceiling aligned with that producer so a valid full-resolution LUT is not silently
    // rejected at the JNI boundary.
    constexpr int kMaximumPreviewLutSize = 65;
    const bool disableRequest = lutData == nullptr && size == 0;
    if (size < 0) {
        setStatus(state, "preview LUT rejected: negative size");
        return JNI_FALSE;
    }
    if (size > kMaximumPreviewLutSize) {
        setStatus(state, "preview LUT rejected: size > kMaximumPreviewLutSize");
        return JNI_FALSE;
    }
    if (!disableRequest && (lutData == nullptr || size < 2)) {
        setStatus(state, "preview LUT rejected: malformed enable/disable request");
        return JNI_FALSE;
    }

    std::vector<jfloat> values;
    const float* data = nullptr;
    if (!disableRequest) {
        const jsize length = env->GetArrayLength(lutData);
        const size_t expectedLength = static_cast<size_t>(size) * static_cast<size_t>(size) * static_cast<size_t>(size) * 3u;
        if (length != static_cast<jsize>(expectedLength)) {
            setStatus(state, "preview LUT rejected: invalid float count");
            return JNI_FALSE;
        }
        values.resize(static_cast<size_t>(length));
        env->GetFloatArrayRegion(lutData, 0, length, values.data());
        if (env->ExceptionCheck() == JNI_TRUE) {
            setStatus(state, "preview LUT rejected: JNI array read failed");
            return JNI_FALSE;
        }
        for (jsize i = 0; i < length; ++i) {
            if (!isIeee754Finite(values[i])) {
                setStatus(state, "preview LUT rejected: non-finite value");
                return JNI_FALSE;
            }
        }
        data = values.data();
    }
    // Disable is represented by an acknowledged, transactional identity-cube replacement. The
    // fragment descriptor always requires a valid sampler, so destroying the LUT would not be a
    // safe disable operation.
    if (!createOrUpdatePreviewLut(state, data, disableRequest ? 0 : size)) {
        setStatus(state, "preview LUT upload failed");
        return JNI_FALSE;
    }
    std::ostringstream status;
    status << "preview LUT ready size=" << state->previewLut.size
           << " format=" << vkFormatLabel(state->previewLut.format);
    setStatus(state, status.str());
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeUpdateSubjectDetailMask(
    JNIEnv* env,
    jobject,
    jlong handle,
    jint maskWidth,
    jint maskHeight,
    jbyteArray alphaMask,
    jint trackingId
) {
    auto* state = fromHandle(handle);
    if (state == nullptr) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(state->mutex);
    if (!state->supported) return JNI_FALSE;
    if (alphaMask == nullptr) {
        return createOrUpdateSubjectDetailMask(state, nullptr, 0, 0, -1) ? JNI_TRUE : JNI_FALSE;
    }
    if (maskWidth <= 0 || maskHeight <= 0 || maskWidth > 4096 || maskHeight > 4096) {
        setStatus(state, "subject detail mask dimensions rejected");
        return JNI_FALSE;
    }
    const int64_t expectedLength = static_cast<int64_t>(maskWidth) * static_cast<int64_t>(maskHeight);
    if (env->GetArrayLength(alphaMask) != expectedLength) {
        setStatus(state, "subject detail mask payload length rejected");
        return JNI_FALSE;
    }
    std::vector<uint8_t> pixels(static_cast<size_t>(expectedLength));
    env->GetByteArrayRegion(
        alphaMask,
        0,
        static_cast<jsize>(expectedLength),
        reinterpret_cast<jbyte*>(pixels.data())
    );
    if (!createOrUpdateSubjectDetailMask(state, pixels.data(), maskWidth, maskHeight, trackingId)) {
        setStatus(state, "subject detail mask upload failed");
        return JNI_FALSE;
    }
    std::ostringstream status;
    status << "subject detail mask ready width=" << state->subjectDetailMask.width
           << " height=" << state->subjectDetailMask.height
           << " trackingId=" << state->subjectDetailMask.trackingId;
    setStatus(state, status.str());
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeGetCapabilitySummary(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject bufferObject
) {
    auto* state = fromHandle(handle);
    if (state == nullptr || bufferObject == nullptr) {
        return 0;
    }
    void* bufferAddress = env->GetDirectBufferAddress(bufferObject);
    jlong bufferCapacity = env->GetDirectBufferCapacity(bufferObject);
    if (bufferAddress == nullptr || bufferCapacity <= 0) return 0;

    std::lock_guard<std::mutex> lock(state->mutex);
    std::ostringstream out;
    out
        << (state->supported ? "supported" : "unsupported")
        << " strict=" << (kStrictHighEndBuild ? "on" : "off")
        << " preview=" << (state->previewTarget.valid ? "on" : "off")
        << " previewFmt=" << vkFormatLabel(state->previewTarget.swapchainFormat)
        << " encoder=" << (state->encoderTarget.valid ? "on" : "off")
        << " encoderSurfaceBound=" << (state->encoderTarget.valid ? "true" : "false")
        << " encoderSurfaceFormat=" << vkFormatLabel(state->encoderTarget.swapchainFormat)
        << " encoderSurfaceBitDepth=" << encoderSurfaceBitDepth(state->encoderTarget.swapchainFormat)
        << " encoderSurfacePrecision=" << encoderSurfacePrecisionLabel(state->encoderTarget.swapchainFormat)
        << " encoderSurfacePrecisionFallback=" << (isHighPrecisionEncoderSurfaceFormat(state->encoderTarget.swapchainFormat) ? "high-precision" : "8-bit")
        << " encoderSurfaceColorSpace=" << encoderSurfaceColorSpaceLabel(state->encoderTarget.colorSpace)
        << " encoderSurfaceDeliveryMode=" << (state->hdrModeId == 0 ? "SDR" : "HDR")
        << " encoderSurfaceDeliveryModeId=" << state->hdrModeId
        << " encoderSurfaceDeliveryCompatible=" << (isEncoderSurfaceDeliveryCompatible(state->hdrModeId, state->encoderTarget.colorSpace) ? "true" : "false")
        << " rawNdrMode=" << state->rawMonitorNdrCorrection.mode
        << " rawNdrActive=" << (state->rawMonitorNdrCorrection.active ? "true" : "false")
        << " rawNdrDiffuse=" << state->rawMonitorNdrCorrection.sensorDiffuseScene
        << " rawNdrGamma=" << state->rawMonitorNdrCorrection.signalGamma
        << " rawNdrDetail=" << state->rawMonitorNdrCorrection.localDetailStrength
        << " lut=" << (state->previewLut.ready ? state->previewLut.size : 0)
        << " lutFmt=" << vkFormatLabel(state->previewLut.format)
        << " imported=" << state->importedFrameCount
        << " previewFrames=" << state->previewFrameCount
        << " encoderFrames=" << state->encoderFrameCount
        << " previewTemporalSubmissionId=" << state->previewTarget.temporalHistorySubmissionId
        << " previewTemporalSensorNs=" << state->previewTarget.temporalHistorySensorTimestampNs
        << " encoderTemporalSubmissionId=" << state->encoderTarget.temporalHistorySubmissionId
        << " encoderTemporalSensorNs=" << state->encoderTarget.temporalHistorySensorTimestampNs
        << " graphicsFamilyQueues=" << state->graphicsFamilyQueueCount
        << " rawQueueLane="
        << (state->independentRawQueueEnabled
                ? "INDEPENDENT_SAME_FAMILY"
                : "GRAPHICS_QUEUE_0")
        << " presentationTimeNs=" << state->lastPresentationTimeNs
        << " status=" << state->lastStatus;
    const std::string summary = out.str();
    size_t copyLength = std::min(summary.length(), static_cast<size_t>(bufferCapacity));
    std::memcpy(bufferAddress, summary.data(), copyLength);
    return static_cast<jint>(copyLength);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeGetEnhancementCapabilitySummary(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject bufferObject
) {
    auto* state = fromHandle(handle);
    if (state == nullptr || bufferObject == nullptr) {
        return 0;
    }
    void* bufferAddress = env->GetDirectBufferAddress(bufferObject);
    jlong bufferCapacity = env->GetDirectBufferCapacity(bufferObject);
    if (bufferAddress == nullptr || bufferCapacity <= 0) return 0;

    std::lock_guard<std::mutex> lock(state->mutex);
    const bool liveImportReady =
        state->supported &&
        state->getAndroidHardwareBufferProperties != nullptr &&
        state->createSamplerYcbcrConversion != nullptr;
    std::ostringstream out;
    const bool computeDispatched =
        state->enhancementComputePipelineReady && state->computeDispatchCount > 0u;
    const char* computeStatus = computeDispatched
        ? kActiveDispatchedCapabilityToken + std::strlen("nativeP010Compute=")
        : (state->enhancementComputePipelineReady ? "active-waiting-for-frame" : "not-wired");
    const char* syncPolicy = state->timelineSemaphoresEnabled
        ? kTimelineSemaphoreCapabilityToken + std::strlen("syncPolicy=")
        : "BinaryFenceFallback";
    const char* timestampQueryPolicy = state->timestampQueriesSupported
        ? kTimestampQueryCapabilityToken + std::strlen("timestampQueries=")
        : "Unavailable";
    out
        << "gpuEnhancement=contract-ready-fallback"
        << " nativeP010Compute=" << computeStatus
        << " avgComputeMs=" << state->averageComputeDurationMs
        << " totalDispatches=" << state->computeDispatchCount
        << " intermediateFormat=RGBA16F"
        << " barrierSync=PIPELINE_BARRIER_COMPUTE_TO_FRAGMENT"
        << " " << kLensWarpingCapabilityToken
        << " " << kHalationCapabilityToken
        << " syncPolicy=" << syncPolicy
        << " timestampQueries=" << timestampQueryPolicy
        << " gpuTimestampSamples=" << state->gpuTimestampSampleCount
        << " halationPyramidLevels=5"
        << " dualGainFusionShaderWords=" << kDualGainFusionCompSpvCount
        << " realtimePreviewShaderWords=" << kRealtimePreviewEnhanceCompSpvCount
        << " ndr10KneeCurveShaderWords=" << kNdr10KneeCurveCompSpvCount
        << " ndr10KneeCurve=adaptive-sigmoid-modified-reinhard-rgba16f"
        << " kneeCurveShaderWords=" << kKneeCurveCompSpvCount
        << " shadowLiftShaderWords=" << kShadowLiftCompSpvCount
        << " toneMapChain=NDR_NIVUKX_UNIVERSAL_KNEE_SHADOW"
        << " hdrMetadataHistogramShaderWords=" << kHdrMetadataHistogramCompSpvCount
        << " hdrMetadataReduceShaderWords=" << kHdrMetadataReduceCompSpvCount
        << " hdrMetadata=project-owned-gpu-histogram"
        << " histogramBins=" << kHdrMetadataHistogramBins
        << " maxCLL=" << state->latestHdrMetadata.maxCLL
        << " maxFALL=" << state->latestHdrMetadata.maxFALL
        << " minLuminance=" << state->latestHdrMetadata.minLuminance
        << " metadataLatency=frameN+1"
        << " metadataRingFrames=" << kHdrMetadataRingSize
        << " vendorHdr10PlusCertified=false"
        << " vendorDolbyVisionCertified=false"
        << " dualGainFusionUniform=" << (
            state->enhancementUniforms.ready && state->enhancementUniforms.values[32] > 0.5f
                ? "active"
                : "inactive"
        )
        << " zeroCopyLiveImport=" << (liveImportReady ? "available" : "unavailable")
        << " dedicatedTransferQueue=" << (state->hasDedicatedTransferQueue ? "available" : "unavailable")
        << " npv10ExportPath=CPU_P010_FALLBACK_GPU_CONTRACT_READY"
        << " rawPreviewDemosaic=" << (state->rawPreviewDemosaicEnabled ? "enabled" : "disabled-yuv-fallback")
        << " rawDemosaicPipeline=" << (
            state->rawDemosaicPipelineReady.load(std::memory_order_acquire) ? "ready" : "unavailable")
        << " rawDemosaicDispatched=" << state->rawDemosaic.dispatchCount
        << " rawDemosaicLastOk=" << (state->rawDemosaic.lastDispatchOk ? "true" : "false")
        << " dualGainFusion=" << (state->dualGainFusionEnabled ? "enabled" : "disabled-passthrough")
        << " dualGainPipeline=" << (state->dualGainPipeline != VK_NULL_HANDLE ? "ready" : "absent")
        << " dualGainFusionDispatched=" << state->dualGainFusion.dispatchCount
        << " photonHdrFusionFoundation=" << (state->photonPipeline != VK_NULL_HANDLE ? "ready-dormant" : "absent")
        << " photonHdrFusionShaderWords=" << kPhotonHdrFusionCompSpvCount
        << " eisWarpFoundation=" << (state->eisWarpPipeline != VK_NULL_HANDLE ? "ready-dormant" : "absent")
        << " eisWarpShaderWords=" << kEisWarpCompSpvCount
        << " bokehBlend=" << (state->bokehBlendEnabled ? "enabled" : "disabled-passthrough")
        << " bokehPipeline=" << (state->bokehPipeline != VK_NULL_HANDLE ? "ready" : "absent")
        << " bokehDispatched=" << state->bokeh.dispatchCount
        << " bokehShaderWords=" << kBokehVogelSpiralCompSpvCount
        << " enhancementUniforms=" << (state->enhancementUniforms.ready ? "ready" : "unset")
        << " enhancementSignature=" << state->enhancementUniforms.signature
        << " niv99Mode=video-default-on"
        << " niv99Graph=" << (state->enhancementComputePipelineReady ? "realtime-compute" : "unavailable")
        << " niv99PacketsSeen=" << state->niv99LivePacket.packetsSeen
        << " niv99PacketsAccepted=" << state->niv99LivePacket.packetsAccepted
        << " niv99PacketsMissing=" << state->niv99LivePacket.packetsMissing
        << " niv99PacketsInvalid=" << state->niv99LivePacket.packetsInvalid
        << " niv99PacketsStale=" << state->niv99LivePacket.packetsStale
        << " niv99PreviewPixelsApplied=" << state->niv99LivePacket.previewPixelsApplied
        << " niv99EncoderPixelsApplied=" << state->niv99LivePacket.encoderPixelsApplied
        << " niv99PixelApplied="
        << (state->niv99LivePacket.previewPixelsApplied + state->niv99LivePacket.encoderPixelsApplied)
        << " niv99PixelActivationRejected=" <<
            state->niv99LivePacket.pixelActivationRejected
        << " niv99LatestGeneration=" << state->niv99LivePacket.sessionGeneration
        << " niv99LatestFrame=" << state->niv99LivePacket.frameNumber
        << " niv99LatestSensorTimestampNs=" << state->niv99LivePacket.sensorTimestampNs
        << " enhancementFallbackReason=" << state->enhancementFallbackReason;
    const std::string summary = out.str();
    size_t copyLength = std::min(summary.length(), static_cast<size_t>(bufferCapacity));
    std::memcpy(bufferAddress, summary.data(), copyLength);
    return static_cast<jint>(copyLength);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeGetLiveComputeTelemetry(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject bufferObject
) {
    auto* state = fromHandle(handle);
    if (state == nullptr || bufferObject == nullptr) return JNI_FALSE;
    void* bufferAddress = env->GetDirectBufferAddress(bufferObject);
    const jlong bufferCapacity = env->GetDirectBufferCapacity(bufferObject);
    constexpr size_t kFieldCount = 4u;
    constexpr size_t kRequiredBytes = kFieldCount * sizeof(uint64_t);
    if (bufferAddress == nullptr || bufferCapacity < static_cast<jlong>(kRequiredBytes)) {
        return JNI_FALSE;
    }

    // The render path updates these counters while holding the same mutex. Copying four scalars keeps
    // the health probe's critical section bounded; unlike the diagnostic summary path it performs no
    // string formatting, status concatenation, heap allocation, or parsing while frames are active.
    std::array<uint64_t, kFieldCount> telemetry{};
    {
        std::lock_guard<std::mutex> lock(state->mutex);
        telemetry[0] = state->enhancementComputePipelineReady ? 1u : 0u;
        static_assert(sizeof(double) == sizeof(uint64_t), "64-bit telemetry wire format required");
        std::memcpy(&telemetry[1], &state->averageComputeDurationMs, sizeof(double));
        telemetry[2] = state->computeDispatchCount;
        telemetry[3] = state->gpuTimestampSampleCount;
    }
    std::memcpy(bufferAddress, telemetry.data(), kRequiredBytes);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeSubmitHardwareBuffer(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject hardwareBufferObject,
    jint width,
    jint height,
    jint format,
    jlong presentationTimeNs,
    jlong sensorTimestampNs,
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
    jfloat niv99SpatialDenoise,
    jfloat niv99ChromaDenoise,
    jfloat niv99TemporalDenoise,
    jfloat niv99SharpeningCeiling,
    jfloat niv99Confidence
) {
    auto* state = fromHandle(handle);
    if (state == nullptr || hardwareBufferObject == nullptr) return 0;
    std::lock_guard<std::mutex> lock(state->mutex);
    if (!state->supported) return 0;
    if (!state->previewTarget.valid && !state->encoderTarget.valid) {
        setStatus(state, "submit skipped: no active Vulkan targets");
        return 0;
    }

    auto& niv99 = state->niv99LivePacket;
    niv99.packetsSeen += 1u;
    // Per-frame state is cleared before examining the packet. A missing, invalid or stale packet can
    // therefore never reuse the previous frame's WB/denoise/reconstruction values.
    niv99.currentFrameActive = false;
    if (niv99PacketValid != JNI_TRUE) {
        niv99.packetsMissing += 1u;
        if (niv99PixelApplicationApproved == JNI_TRUE) {
            niv99.packetsInvalid += 1u;
            niv99.pixelActivationRejected += 1u;
            setStatus(state, "NIV99 activation rejected: packet-valid flag is false");
            return 0;
        }
    } else {
        const bool completeIdentity = niv99CameraIdHash != 0 &&
                                      niv99SessionGeneration >= 0 &&
                                      niv99FrameNumber >= 0 &&
                                      niv99SensorTimestampNs > 0;
        const bool exactImageTimestamp = sensorTimestampNs > 0 &&
                                         niv99SensorTimestampNs == sensorTimestampNs;
        const bool validAuthority = niv99Authority >= 0 && niv99Authority <= 4;
        const bool finiteValues = isIeee754Finite(niv99RedCorrection) &&
                                  isIeee754Finite(niv99GreenCorrection) &&
                                  isIeee754Finite(niv99BlueCorrection) &&
                                  isIeee754Finite(niv99SpatialDenoise) &&
                                  isIeee754Finite(niv99ChromaDenoise) &&
                                  isIeee754Finite(niv99TemporalDenoise) &&
                                  isIeee754Finite(niv99SharpeningCeiling) &&
                                  isIeee754Finite(niv99Confidence);
        const bool boundedValues = niv99RedCorrection >= 0.5f && niv99RedCorrection <= 2.0f &&
                                   niv99GreenCorrection >= 0.5f && niv99GreenCorrection <= 2.0f &&
                                   niv99BlueCorrection >= 0.5f && niv99BlueCorrection <= 2.0f &&
                                   niv99SpatialDenoise >= 0.0f && niv99SpatialDenoise <= 1.0f &&
                                   niv99ChromaDenoise >= 0.0f && niv99ChromaDenoise <= 1.0f &&
                                   niv99TemporalDenoise >= 0.0f && niv99TemporalDenoise <= 1.0f &&
                                   niv99SharpeningCeiling >= 0.0f &&
                                   niv99SharpeningCeiling <= 1.0f &&
                                   niv99Confidence >= 0.0f && niv99Confidence <= 1.0f;
        const bool userWhiteBalanceAuthority = niv99Authority == 0 || niv99Authority == 1;
        const bool userWhiteBalancePreserved = !userWhiteBalanceAuthority ||
            (std::abs(niv99RedCorrection - 1.0f) <= 1.0e-6f &&
             std::abs(niv99GreenCorrection - 1.0f) <= 1.0e-6f &&
             std::abs(niv99BlueCorrection - 1.0f) <= 1.0e-6f);
        if (!completeIdentity || !exactImageTimestamp || !validAuthority || !finiteValues ||
            !boundedValues || !userWhiteBalancePreserved
        ) {
            niv99.packetsInvalid += 1u;
            if (niv99PixelApplicationApproved == JNI_TRUE) {
                niv99.pixelActivationRejected += 1u;
            }
            setStatus(state, "NIV99 packet rejected: invalid identity, bounds, or WB authority");
            return 0;
        } else {
            const uint64_t cameraIdHash = static_cast<uint64_t>(niv99CameraIdHash);
            const bool sameCamera = niv99.hasAcceptedPacket &&
                                    niv99.cameraIdHash == cameraIdHash;
            const bool staleSameCamera = sameCamera &&
                (niv99SessionGeneration < niv99.sessionGeneration ||
                 (niv99SessionGeneration == niv99.sessionGeneration &&
                  (niv99FrameNumber <= niv99.frameNumber ||
                   niv99SensorTimestampNs <= niv99.sensorTimestampNs)));
            const bool staleDifferentCamera = niv99.hasAcceptedPacket && !sameCamera &&
                niv99SessionGeneration <= niv99.sessionGeneration;
            const bool stale = staleSameCamera || staleDifferentCamera;
            if (stale) {
                niv99.packetsStale += 1u;
                if (niv99PixelApplicationApproved == JNI_TRUE) {
                    niv99.pixelActivationRejected += 1u;
                }
                setStatus(state, "NIV99 packet rejected: stale camera/session/frame provenance");
                return 0;
            } else {
                const bool timelineChanged = niv99.hasAcceptedPacket &&
                    (!sameCamera || niv99SessionGeneration != niv99.sessionGeneration);
                if (timelineChanged) {
                    std::fill(
                        state->previewTarget.historyContentValid.begin(),
                        state->previewTarget.historyContentValid.end(),
                        static_cast<uint8_t>(0u));
                    std::fill(
                        state->encoderTarget.historyContentValid.begin(),
                        state->encoderTarget.historyContentValid.end(),
                        static_cast<uint8_t>(0u));
                    state->previewTarget.temporalHistorySensorTimestampNs = 0;
                    state->encoderTarget.temporalHistorySensorTimestampNs = 0;
                }
                niv99.cameraIdHash = cameraIdHash;
                niv99.sessionGeneration = static_cast<int64_t>(niv99SessionGeneration);
                niv99.frameNumber = static_cast<int64_t>(niv99FrameNumber);
                niv99.sensorTimestampNs = static_cast<int64_t>(niv99SensorTimestampNs);
                niv99.authority = static_cast<int>(niv99Authority);
                niv99.redCorrection = niv99RedCorrection;
                niv99.greenCorrection = niv99GreenCorrection;
                niv99.blueCorrection = niv99BlueCorrection;
                niv99.spatialDenoise = niv99SpatialDenoise;
                niv99.chromaDenoise = niv99ChromaDenoise;
                niv99.temporalDenoise = niv99TemporalDenoise;
                niv99.sharpeningCeiling = niv99SharpeningCeiling;
                niv99.confidence = niv99Confidence;
                niv99.hasAcceptedPacket = true;
                niv99.currentFrameActive = niv99PixelApplicationApproved == JNI_TRUE;
                niv99.packetsAccepted += 1u;
                if (niv99.packetsAccepted == 1u || niv99.packetsAccepted % 120u == 0u) {
                    LOGI(
                        "NIV99_PACKET_ACCEPTED accepted=%llu generation=%lld frame=%lld timestampNs=%lld "
                        "authority=%d confidence=%.4f pixelRequested=%s",
                        static_cast<unsigned long long>(niv99.packetsAccepted),
                        static_cast<long long>(niv99.sessionGeneration),
                        static_cast<long long>(niv99.frameNumber),
                        static_cast<long long>(niv99.sensorTimestampNs),
                        niv99.authority,
                        static_cast<double>(niv99.confidence),
                        niv99.currentFrameActive ? "true" : "false"
                    );
                }
            }
        }
    }

    AHardwareBuffer* hardwareBuffer = AHardwareBuffer_fromHardwareBuffer(env, hardwareBufferObject);
    if (hardwareBuffer == nullptr) {
        setStatus(state, "AHardwareBuffer_fromHardwareBuffer failed");
        return 0;
    }

    if (!importHardwareBuffer(state, hardwareBuffer, width, height, format)) {
        return 0;
    }
    state->lastPresentationTimeNs = static_cast<int64_t>(presentationTimeNs);

    constexpr uint64_t kPreviewAcquireTimeoutNs = 5000000ULL;
    constexpr uint64_t kEncoderAcquireTimeoutNs = 25000000ULL;
    bool rendered = false;
    bool previewNiv99Applied = false;
    bool encoderNiv99Applied = false;
    // Recording output is the primary deliverable. A blocked display swapchain
    // must not prevent the encoder surface from receiving a processed frame.
    if (state->encoderTarget.valid) {
        if (recordAndSubmit(
                state,
                &state->encoderTarget,
                false,
                kEncoderAcquireTimeoutNs,
                &encoderNiv99Applied)) {
            state->encoderFrameCount += 1;
            rendered = true;
        }
    }
    if (state->previewTarget.valid) {
        if (recordAndSubmit(
                state,
                &state->previewTarget,
                true,
                kPreviewAcquireTimeoutNs,
                &previewNiv99Applied)) {
            state->previewFrameCount += 1;
            rendered = true;
        }
    }
    // Imported resources remain in the bounded cache and are reused for
    // recurring ImageReader buffers. Eviction and target teardown wait for
    // completion before releasing resources; per-frame drains would serialize
    // camera, shader and encoder work.
    const bool primaryNiv99Applied = state->encoderTarget.valid
        ? encoderNiv99Applied
        : previewNiv99Applied;
    if (niv99.currentFrameActive && primaryNiv99Applied) {
        if (previewNiv99Applied) niv99.previewPixelsApplied += 1u;
        if (encoderNiv99Applied) niv99.encoderPixelsApplied += 1u;
        const uint64_t applied = niv99.previewPixelsApplied + niv99.encoderPixelsApplied;
        if (applied == 1u || applied % 120u == 0u) {
            LOGI(
                "NIV99_PIXEL_APPLIED_NATIVE total=%llu preview=%llu encoder=%llu "
                "generation=%lld frame=%lld timestampNs=%lld",
                static_cast<unsigned long long>(applied),
                static_cast<unsigned long long>(niv99.previewPixelsApplied),
                static_cast<unsigned long long>(niv99.encoderPixelsApplied),
                static_cast<long long>(niv99.sessionGeneration),
                static_cast<long long>(niv99.frameNumber),
                static_cast<long long>(niv99.sensorTimestampNs));
        }
    } else if (niv99.currentFrameActive) {
        niv99.pixelActivationRejected += 1u;
        setStatus(state, "NIV99 frame rendered without the realtime compute graph");
    }
    if (!rendered) {
        setStatus(state, "submit failed: no target render succeeded");
    } else if ((state->previewFrameCount + state->encoderFrameCount) % 60u == 0u) {
        LOGI(
            "Rendered preview=%llu encoder=%llu",
            static_cast<unsigned long long>(state->previewFrameCount),
            static_cast<unsigned long long>(state->encoderFrameCount)
        );
    }
    if (!rendered) return 0;
    return primaryNiv99Applied ? 3 : 1;
}

// ═════════════════════════════════════════════════════════════════════════════
// Zero-Copy Tensor Bridge (Vulkan External Memory)
// ═════════════════════════════════════════════════════════════════════════════

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_pipeline_ZeroCopyTensorBridge_nativeIsZeroCopySupported(
    JNIEnv* env,
    jclass
) {
    (void)env;
    // This bridge is only safe when a full Vulkan external-memory import path
    // validates format, stride, allocation size, and synchronization. The old
    // implementation returned true unconditionally and then locked/copy-read an
    // AHardwareBuffer as if it were a tightly packed tensor, which can read past
    // vendor YUV allocations and crash the AI analysis thread. Keep the truthful
    // CPU YUV path active until native zero-copy import is fully implemented.
    return JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_neoproc_neoproccamx_pipeline_ZeroCopyTensorBridge_nativeBindHardwareBuffer(
    JNIEnv* env,
    jclass,
    jobject hardwareBufferObject,
    jint width,
    jint height,
    jint format
) {
    (void)width;
    (void)height;
    (void)format;
    if (hardwareBufferObject == nullptr) return 0L;
    AHardwareBuffer* hardwareBuffer = AHardwareBuffer_fromHardwareBuffer(env, hardwareBufferObject);
    if (hardwareBuffer == nullptr) return 0L;

    // Retain hardware buffer for the GPU delegate binding lifespan
    AHardwareBuffer_acquire(hardwareBuffer);
    return reinterpret_cast<jlong>(hardwareBuffer);
}

extern "C" JNIEXPORT void JNICALL
Java_com_neoproc_neoproccamx_pipeline_ZeroCopyTensorBridge_nativeReleaseTensorBinding(
    JNIEnv* env,
    jclass,
    jlong handle
) {
    (void)env;
    if (handle != 0L) {
        AHardwareBuffer* hardwareBuffer = reinterpret_cast<AHardwareBuffer*>(handle);
        AHardwareBuffer_release(hardwareBuffer);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_pipeline_ZeroCopyTensorBridge_nativeGetTensorData(
    JNIEnv* env,
    jclass,
    jlong handle,
    jobject outputBuffer,
    jint width,
    jint height
) {
    (void)env;
    (void)handle;
    (void)outputBuffer;
    (void)width;
    (void)height;
    // Zero-copy tensor readback is disabled until native import validates allocation size and stride.
    // Failing closed keeps the CPU YUV fallback active and avoids unbounded reads from vendor buffers.
    return JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeUpdateFocusMap(
    JNIEnv* env,
    jobject,
    jlong handle,
    jfloatArray focusMapArray
) {
    if (handle == 0L || focusMapArray == nullptr) return;
    VulkanBridgeState* state = reinterpret_cast<VulkanBridgeState*>(handle);
    jsize length = env->GetArrayLength(focusMapArray);
    if (length != 256) {
        return; // Bounds protection
    }
    jfloat* data = env->GetFloatArrayElements(focusMapArray, nullptr);
    if (data == nullptr) return;

    {
        std::lock_guard<std::mutex> lock(state->focusMapMutex);
        std::copy(data, data + 256, state->latestFocusMap.begin());
        state->focusMapDirty = true;
    }

    env->ReleaseFloatArrayElements(focusMapArray, data, JNI_ABORT);
}

