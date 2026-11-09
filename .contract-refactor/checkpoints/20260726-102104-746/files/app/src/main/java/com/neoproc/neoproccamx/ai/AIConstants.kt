package com.neoproc.neoproccamx.ai

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * AIConstants — Neural Imaging OS Master Configuration.
 *
 * Central registry of all AI/ML processing constants for the NeoProCamX
 * neural cinema pipeline. These constants govern:
 *
 *  - TFLite inference parameters (threads, delegates, quantization)
 *  - Neural pipeline timing budgets (latency ceilings per stage)
 *  - Subject tracking thresholds (face, pose, eye)
 *  - Scene detection sensitivity curves
 *  - Super-resolution and denoising quality targets
 *  - Depth estimation and bokeh synthesis parameters
 *  - Film grain and texture reconstruction coefficients
 *  - Color science constants (Rec.709, Rec.2020, PQ, HLG)
 *
 * All constants are compile-time immutable for zero-overhead access
 * on the hot path. Runtime-configurable overrides should go through
 * [AIStrictHighEndConfig] or [NeuralPipelineConfig].
 */
object AIConstants {

    // ═══════════════════════════════════════════════════════════════════════════
    // TFLITE INFERENCE ENGINE
    // ═══════════════════════════════════════════════════════════════════════════

    /** Physical asset directory scanned for neural model payloads. */
    const val MODEL_ASSET_DIRECTORY = "models"

    /** Canonical abstract model aliases used by the neural orchestrator. */
    const val MODEL_ALIAS_DEPTH_ESTIMATION = "depth_estimation"
    const val MODEL_ALIAS_SEGMENTATION = "segmentation"
    const val MODEL_ALIAS_DENOISING = "denoising"
    const val MODEL_ALIAS_OBJECT_DETECTION = "object_detection"
    const val MODEL_ALIAS_FACE_DETECTION = "face_detection"
    const val MODEL_ALIAS_POSE_ESTIMATION = "pose_estimation"
    const val MODEL_ALIAS_SUPER_RESOLUTION = "super_resolution"
    const val MODEL_ALIAS_IMAGE_ENHANCEMENT = "image_enhancement"
    const val MODEL_ALIAS_SCENE_CLASSIFICATION = "scene_classification"
    const val MODEL_ALIAS_HDR_MERGE = "hdr_merge"

    /** Primary packaged assets. Fallback names are resolved in [AINeuralEngine]. */
    const val MODEL_DEPTH_PRIMARY_ASSET = "models/midas_v2_1_small.tflite"
    const val MODEL_SEGMENTATION_PRIMARY_ASSET = "models/selfie_multiclass_256x256.tflite"
    const val MODEL_DENOISE_PRIMARY_ASSET = "models/enhancement_denoise.tflite"
    const val MODEL_OBJECT_DETECTION_PRIMARY_ASSET = "models/efficientdet_lite0.tflite"
    const val MODEL_FACE_LANDMARK_PRIMARY_ASSET = "models/face_landmarker.task"
    const val MODEL_POSE_LANDMARK_PRIMARY_ASSET = "models/pose_landmarker_full.task"

    /** Initialization must not block the camera runtime on missing optional assets. */
    const val ENGINE_INIT_DEGRADED_BUDGET_MS = 15L

    /** Default TFLite CPU thread count. */
    const val TFLITE_DEFAULT_THREAD_COUNT = 4

    /** Maximum TFLite CPU thread count — saturating all big cores. */
    const val TFLITE_MAX_THREAD_COUNT = 8

    /** Minimum TFLite CPU thread count for ultra-low-power mode. */
    const val TFLITE_MIN_THREAD_COUNT = 1

    /** TFLite GPU delegate priority (1 = low, 3 = high). */
    const val TFLITE_GPU_PRIORITY = 3

    /** TFLite NNAPI preference: 0=low-power, 1=fast, 2=sustained. */
    const val TFLITE_NNAPI_PREFERENCE = 1

    /** Maximum model warmup iterations before first frame. */
    const val TFLITE_WARMUP_ITERATIONS = 3

    /** Model input batch size for pipelined inference. */
    const val TFLITE_BATCH_SIZE = 1

    /** Whether to allow FP16 quantization on GPU delegate. */
    const val TFLITE_ALLOW_FP16 = true

    /** Whether to cache GPU compiled programs on disk. */
    const val TFLITE_GPU_CACHE_ENABLED = true

    // ═══════════════════════════════════════════════════════════════════════════
    // NEURAL PIPELINE TIMING BUDGETS (milliseconds)
    // ═══════════════════════════════════════════════════════════════════════════

    /** Maximum inference latency for real-time preview stages. */
    const val PREVIEW_INFERENCE_BUDGET_MS = 16L  // 60fps target

    /** Maximum latency for face detection per frame. */
    const val FACE_DETECTION_BUDGET_MS = 12L

    /** Maximum latency for scene detection per frame. */
    const val SCENE_DETECTION_BUDGET_MS = 20L

    /** Maximum latency for depth estimation per frame. */
    const val DEPTH_ESTIMATION_BUDGET_MS = 25L

    /** Maximum latency for super-resolution pass. */
    const val SUPER_RESOLUTION_BUDGET_MS = 35L

    /** Maximum latency for neural denoising pass. */
    const val DENOISING_BUDGET_MS = 30L

    /** Maximum latency for segmentation mask generation. */
    const val SEGMENTATION_BUDGET_MS = 20L

    /** Total neural pipeline ceiling (all stages combined). */
    const val TOTAL_PIPELINE_BUDGET_MS = 50L  // 20fps minimum for all AI

    /** Classical fallback work must remain below a single high-refresh frame budget. */
    const val CLASSICAL_FALLBACK_BUDGET_MS = 8.5

    /** Direct tensor bridge ingestion target for a 1080p frame. */
    const val DIRECT_TENSOR_TRANSFER_BUDGET_MS = 1.2

    // ═══════════════════════════════════════════════════════════════════════════
    // HDR PROCESSING
    // ═══════════════════════════════════════════════════════════════════════════

    /** Number of HDR history frames for temporal enhancement. */
    const val ENHANCEMENT_HDR_HISTORY_FRAMES = 8

    /** Maximum HDR bracket count for extreme dynamic range. */
    const val MAX_HDR_BRACKET_COUNT = 12

    /** HDR exposure bracket step (EV). */
    const val HDR_BRACKET_STEP_EV = 1.5f

    /** HDR ghost detection threshold (motion between brackets). */
    const val HDR_GHOST_THRESHOLD = 0.15f

    /** HDR tone map key value for Reinhard operator. */
    const val HDR_TONEMAP_KEY = 0.18f

    /** HDR maximum luminance target for PQ encoding (nits). */
    const val HDR_MAX_LUMINANCE_NITS = 10_000.0

    /** HDR reference white luminance (nits). */
    const val HDR_REFERENCE_WHITE_NITS = 203.0

    // ═══════════════════════════════════════════════════════════════════════════
    // SUPER-RESOLUTION
    // ═══════════════════════════════════════════════════════════════════════════

    /** Maximum super-resolution upscale factor. */
    const val SR_MAX_SCALE_FACTOR = 4

    /** Default super-resolution upscale factor. */
    const val SR_DEFAULT_SCALE_FACTOR = 2

    /** Super-resolution tile size for tiled inference (pixels). */
    const val SR_TILE_SIZE = 256

    /** Super-resolution tile overlap (pixels). */
    const val SR_TILE_OVERLAP = 16

    /** Minimum input resolution for SR activation (pixels, long edge). */
    const val SR_MIN_INPUT_LONG_EDGE = 480

    /** SR model input dimensions (width × height). */
    const val SR_MODEL_INPUT_WIDTH = 256
    const val SR_MODEL_INPUT_HEIGHT = 256

    /** SR quality threshold — skip if source quality is already high. */
    const val SR_QUALITY_SKIP_THRESHOLD = 0.85f

    // ═══════════════════════════════════════════════════════════════════════════
    // NEURAL DENOISING
    // ═══════════════════════════════════════════════════════════════════════════

    /** Denoising model input dimensions. */
    const val DENOISE_MODEL_INPUT_WIDTH = 256
    const val DENOISE_MODEL_INPUT_HEIGHT = 256

    /** Denoising chroma noise weight (higher = more chroma denoise). */
    const val DENOISE_CHROMA_WEIGHT = 1.5f

    /** Denoising luma noise weight (lower = preserve luma detail). */
    const val DENOISE_LUMA_WEIGHT = 0.40f

    /** Denoising temporal stability weight (0–1). */
    const val DENOISE_TEMPORAL_WEIGHT = 0.7f

    /** Minimum ISO threshold to activate neural denoising. */
    const val DENOISE_ISO_ACTIVATION_THRESHOLD = 400

    /** Maximum denoise strength (0–1). */
    const val DENOISE_MAX_STRENGTH = 1.0f

    // ═══════════════════════════════════════════════════════════════════════════
    // FILM GRAIN SYNTHESIS
    // ═══════════════════════════════════════════════════════════════════════════

    /** Film grain frequency (higher = finer grain). */
    const val GRAIN_FREQUENCY = 2.4f

    /** Film grain intensity scale (0–1). */
    const val GRAIN_INTENSITY_SCALE = 0.08f

    /** Film grain response curve — maps ISO to grain strength. */
    const val GRAIN_ISO_BASE = 400

    /** Film grain chroma noise component strength. */
    const val GRAIN_CHROMA_STRENGTH = 0.03f

    /** Film grain luma noise component strength. */
    const val GRAIN_LUMA_STRENGTH = 0.06f

    /** Film grain temporal variation (frame-to-frame change). */
    const val GRAIN_TEMPORAL_VARIATION = 0.12f

    /** Whether to use Halation (light bloom around highlights). */
    const val GRAIN_HALATION_ENABLED = true

    /** Halation radius in pixels (at 1080p, scaled proportionally). */
    const val GRAIN_HALATION_RADIUS = 8

    /** Halation intensity (0–1). */
    const val GRAIN_HALATION_INTENSITY = 0.04f

    // ═══════════════════════════════════════════════════════════════════════════
    // DEPTH ESTIMATION & BOKEH
    // ═══════════════════════════════════════════════════════════════════════════

    /** Depth model input dimensions. */
    const val DEPTH_MODEL_INPUT_WIDTH = 256
    const val DEPTH_MODEL_INPUT_HEIGHT = 256

    /** Depth map output dimensions. */
    const val DEPTH_MAP_OUTPUT_WIDTH = 256
    const val DEPTH_MAP_OUTPUT_HEIGHT = 256

    /** Maximum virtual aperture for bokeh synthesis (f-stop). */
    const val BOKEH_MAX_APERTURE = 1.2f

    /** Default virtual aperture (f-stop). */
    const val BOKEH_DEFAULT_APERTURE = 2.0f

    /** Bokeh disc kernel size at maximum blur. */
    const val BOKEH_MAX_KERNEL_SIZE = 31

    /** Bokeh disc minimum kernel size. */
    const val BOKEH_MIN_KERNEL_SIZE = 3

    /** Bokeh circle-of-confusion scale factor. */
    const val BOKEH_COC_SCALE = 0.012f

    /** Bokeh edge-aware refinement iterations. */
    const val BOKEH_EDGE_REFINEMENT_ITERATIONS = 3

    /** Bokeh cat-eye effect strength at image corners (0–1). */
    const val BOKEH_CAT_EYE_STRENGTH = 0.15f

    /** Bokeh specular highlight boost (for "orbs"). */
    const val BOKEH_SPECULAR_BOOST = 1.8f

    // ═══════════════════════════════════════════════════════════════════════════
    // SUBJECT TRACKING
    // ═══════════════════════════════════════════════════════════════════════════

    /** Maximum tracking age before subject is considered lost (ms). */
    const val TRACKING_MAX_AGE_MS = 2000L

    /** Tracking velocity damping factor for smooth predictions. */
    const val TRACKING_VELOCITY_DAMPING = 0.85f

    /** Minimum IoU for track-to-detection matching. */
    const val TRACKING_MIN_IOU = 0.3f

    /** Maximum concurrent tracked subjects. */
    const val TRACKING_MAX_CONCURRENT = 32

    /** Focus transition speed — diopters per second for rack focus. */
    const val FOCUS_TRANSITION_SPEED_DIOPTERS_PER_SEC = 8.0f

    /** Focus transition damping for smooth rack focus. */
    const val FOCUS_TRANSITION_DAMPING = 0.92f

    /** Eye-AF confidence threshold for near-eye focus lock. */
    const val EYE_AF_CONFIDENCE_THRESHOLD = 0.8f

    /** Minimum eye separation in normalized coords for reliable eye-AF. */
    const val EYE_AF_MIN_SEPARATION = 0.05f

    // ═══════════════════════════════════════════════════════════════════════════
    // SCENE DETECTION
    // ═══════════════════════════════════════════════════════════════════════════

    /** Scene change hysteresis frames (prevent flickering). */
    const val SCENE_CHANGE_HYSTERESIS_FRAMES = 5

    /** Scene stability window for confident classification. */
    const val SCENE_STABILITY_WINDOW = 8

    /** Minimum confidence to trigger scene-adaptive overrides. */
    const val SCENE_OVERRIDE_MIN_CONFIDENCE = 0.7f

    /** Golden Hour color temperature range (Kelvin). */
    const val GOLDEN_HOUR_TEMP_MIN = 3000
    const val GOLDEN_HOUR_TEMP_MAX = 4000

    /** Blue Hour color temperature maximum (Kelvin). */
    const val BLUE_HOUR_TEMP_MAX = 7500

    // ═══════════════════════════════════════════════════════════════════════════
    // COLOR SCIENCE
    // ═══════════════════════════════════════════════════════════════════════════

    /** Rec.709 luminance coefficients. */
    const val REC709_R_WEIGHT = 0.2126f
    const val REC709_G_WEIGHT = 0.7152f
    const val REC709_B_WEIGHT = 0.0722f

    /** Rec.2020 luminance coefficients. */
    const val REC2020_R_WEIGHT = 0.2627f
    const val REC2020_G_WEIGHT = 0.6780f
    const val REC2020_B_WEIGHT = 0.0593f

    /** sRGB gamma encoding exponent. */
    const val SRGB_GAMMA = 2.2f

    /** sRGB inverse gamma decoding exponent. */
    const val SRGB_INVERSE_GAMMA = 1.0f / 2.2f

    /** PQ (ST.2084) constants. */
    const val PQ_M1 = 2610.0 / 16384.0
    const val PQ_M2 = 2523.0 / 32.0
    const val PQ_C1 = 3424.0 / 4096.0
    const val PQ_C2 = 2413.0 / 128.0
    const val PQ_C3 = 2392.0 / 128.0

    // ═══════════════════════════════════════════════════════════════════════════
    // PERFORMANCE & MEMORY
    // ═══════════════════════════════════════════════════════════════════════════

    /** Maximum GPU memory allocation for AI models (MB). */
    const val MAX_GPU_MEMORY_MB = 512

    /** Maximum CPU memory allocation for AI buffers (MB). */
    const val MAX_CPU_MEMORY_MB = 256

    /** Ring buffer capacity for frame pipeline. */
    const val FRAME_RING_BUFFER_CAPACITY = 4

    /** Maximum pending inference requests. */
    const val MAX_PENDING_INFERENCES = 2

    /** Inference timeout before frame drop (ms). */
    const val INFERENCE_TIMEOUT_MS = 100L

    /** Whether to use double-buffered inference for latency hiding. */
    const val DOUBLE_BUFFERED_INFERENCE = true

    // ═══════════════════════════════════════════════════════════════════════════
    // UTILITY FUNCTIONS
    // ═══════════════════════════════════════════════════════════════════════════

    /** Converts ISO to film grain strength using a logarithmic response. */
    fun isoToGrainStrength(iso: Int): Float {
        if (iso <= GRAIN_ISO_BASE) return 0f
        val logRatio = ln(iso.toFloat() / GRAIN_ISO_BASE) / ln(2f)
        return (logRatio * GRAIN_INTENSITY_SCALE).coerceIn(0f, DENOISE_MAX_STRENGTH)
    }

    /** Converts focal length and aperture to circle-of-confusion diameter. */
    fun circleOfConfusion(
        focalLengthMm: Float,
        aperture: Float,
        subjectDistanceM: Float,
        pointDistanceM: Float
    ): Float {
        val s = subjectDistanceM.coerceAtLeast(0.001f)
        val d = pointDistanceM.coerceAtLeast(0.001f)
        val f = focalLengthMm / 1000f  // to meters
        val coc = (f * f * kotlin.math.abs(d - s)) / (aperture * s * d)
        return coc * BOKEH_COC_SCALE
    }

    /** Returns the recommended TFLite thread count for the current device. */
    fun recommendedThreadCount(): Int {
        val cpuCount = Runtime.getRuntime().availableProcessors()
        return cpuCount.coerceIn(TFLITE_MIN_THREAD_COUNT, TFLITE_MAX_THREAD_COUNT)
    }
}
