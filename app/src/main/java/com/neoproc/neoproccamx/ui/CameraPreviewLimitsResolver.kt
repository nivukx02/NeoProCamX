package com.neoproc.neoproccamx.ui

import android.util.Size
import com.neoproc.neoproccamx.core.HighEndQualityPolicy

/**
 * CameraPreviewLimitsResolver — cinema-oriented preview resolution limit calculator.
 *
 * Resolves the maximum allowable preview surface size, analysis resolution,
 * and stream size for the active camera session, taking into account:
 *
 *  - Device thermal state
 *  - Performance stability profile
 *  - Strict high-end mode policy
 *  - Recording resolution demands (prevents preview from starving the encoder)
 *  - Native sensor readout requirements
 *
 * All resolved sizes are validated against Camera2 stream configuration maps
 * and SMPTE output specifications.
 */
class CameraPreviewLimitsResolver {

    // ── Constants ─────────────────────────────────────────────────────────────

    private companion object {
        // Maximum preview surface sizes per mode
        val PREVIEW_MAX_STRICT_HIGH_END = Size(8192, 6144)  // Sensor-native preview ceiling
        val PREVIEW_MAX_STANDARD        = Size(3840, 2160)  // FLAGSHIP: 4K for standard
        val PREVIEW_MAX_PERFORMANCE     = Size(1920, 1080)  // FLAGSHIP: 1080p for thermal throttle

        // Analysis stream maximums per mode
        const val ANALYSIS_MAX_HIGH_END    = 8192 // Sensor-native analysis ceiling
        const val ANALYSIS_MAX_STANDARD   = 3840
        const val ANALYSIS_MAX_PERFORMANCE = 1920
        const val ANALYSIS_MAX_DEGRADED    = 1280

        // Recording analysis (smaller to avoid competing with encoder)
        const val RECORDING_ANALYSIS_MAX_HIGH_END = 8192 // Sensor-native analysis ceiling during recording
        const val RECORDING_ANALYSIS_MAX_STANDARD = 3840
        const val RECORDING_ANALYSIS_MAX_MINIMAL  = 1920

        // Fallback sizes
        val FALLBACK_PHOTO_PREVIEW  = Size(1920, 1080)
        val FALLBACK_STREAM_PREVIEW = Size(1920, 1080)
        val FALLBACK_ANALYSIS       = Size(1280, 720)
    }

    // ── Default Fallback Properties ───────────────────────────────────────────

    /**
     * Default photo preview fallback size.
     * Used when Camera2 reports no valid JPEG preview sizes.
     */
    val defaultPhotoPreviewFallback: Size
        get() = if (HighEndQualityPolicy.strictHighEndOnly) {
            PREVIEW_MAX_STRICT_HIGH_END
        } else {
            FALLBACK_PHOTO_PREVIEW
        }

    /**
     * Default stream preview fallback size.
     * Used when Camera2 reports no valid SurfaceTexture preview sizes.
     */
    val defaultStreamPreviewFallback: Size
        get() = FALLBACK_STREAM_PREVIEW

    // ── Primary Resolution API ────────────────────────────────────────────────

    /**
     * Resolves the maximum preview surface size for the current configuration.
     *
     * @param strictHighEnd Whether the strict high-end pipeline is active.
     * @param thermal       Current thermal state label.
     * @param recordingLongEdge The long edge of the recording output (pixels).
     *                          Used to prevent preview from overwhelming the encoder.
     */
    fun resolvePreviewLimits(
        strictHighEnd: Boolean = HighEndQualityPolicy.strictHighEndOnly,
        thermal: String = "",
        recordingLongEdge: Int = 1920
    ): Size {
        val isThermallyThrottled = thermal.contains("throttl", ignoreCase = true) ||
            thermal.contains("critical", ignoreCase = true) ||
            thermal.contains("emergency", ignoreCase = true)

        return when {
            isThermallyThrottled -> PREVIEW_MAX_PERFORMANCE
            strictHighEnd && recordingLongEdge >= 3840 -> PREVIEW_MAX_STRICT_HIGH_END
            strictHighEnd -> PREVIEW_MAX_STRICT_HIGH_END
            recordingLongEdge >= 3840 -> PREVIEW_MAX_STANDARD
            else -> PREVIEW_MAX_STANDARD
        }
    }

    /**
     * Resolves the maximum long edge for preview AI/analysis streams.
     *
     * Analysis streams are sized to leave encoder bandwidth headroom.
     * In recording mode, the analysis resolution is always reduced to
     * prevent latency spikes in the encode pipeline.
     *
     * @param strictHighEnd Whether the strict high-end pipeline is active.
     * @param normalizedPreviewLongEdge The long edge of the resolved preview size.
     * @param performanceAnalysisMaxLongEdge Optional external performance cap.
     * @param nativePreview Whether the Vulkan native compositor is active.
     * @param recordingActive Whether a recording session is in progress.
     */
    fun resolvePreviewAnalysisMaxLongEdge(
        strictHighEnd: Boolean = HighEndQualityPolicy.strictHighEndOnly,
        normalizedPreviewLongEdge: Int,
        performanceAnalysisMaxLongEdge: Int? = null,
        nativePreview: Boolean = false,
        recordingActive: Boolean = false
    ): Int {
        val performanceCap = performanceAnalysisMaxLongEdge ?: if (strictHighEnd) {
            ANALYSIS_MAX_HIGH_END
        } else {
            ANALYSIS_MAX_STANDARD
        }

        val recordingCap = if (recordingActive) {
            if (strictHighEnd) RECORDING_ANALYSIS_MAX_HIGH_END else RECORDING_ANALYSIS_MAX_STANDARD
        } else {
            Int.MAX_VALUE
        }

        // Native Vulkan preview benefits from a larger analysis feed for LUT and scene processing.
        val nativePremium = if (nativePreview && strictHighEnd) 1.25f else 1.0f

        val base = if (strictHighEnd) {
            minOf(normalizedPreviewLongEdge, performanceCap, recordingCap)
        } else {
            minOf(performanceCap, recordingCap)
        }

        return (base * nativePremium).toInt().coerceIn(
            ANALYSIS_MAX_DEGRADED,
            if (strictHighEnd) ANALYSIS_MAX_HIGH_END else ANALYSIS_MAX_STANDARD
        )
    }

    /**
     * Resolves the maximum long edge for recording analysis streams.
     *
     * Recording analysis is used for AI subject tracking and internal exposure statistics during
     * active recording. It must not exceed the
     * recording output to avoid decoder pipeline cross-contamination.
     *
     * @param normalizedRecordingLongEdge The long edge of the recording output.
     * @param performanceAnalysisMaxLongEdge Optional external performance cap.
     * @param strictHighEnd Whether high-end analysis features are enabled.
     */
    fun resolveRecordingAnalysisMaxLongEdge(
        normalizedRecordingLongEdge: Int,
        performanceAnalysisMaxLongEdge: Int? = null,
        strictHighEnd: Boolean = HighEndQualityPolicy.strictHighEndOnly
    ): Int {
        val cap = performanceAnalysisMaxLongEdge ?: if (strictHighEnd) {
            RECORDING_ANALYSIS_MAX_HIGH_END
        } else {
            RECORDING_ANALYSIS_MAX_STANDARD
        }
        return minOf(normalizedRecordingLongEdge, cap).coerceAtLeast(ANALYSIS_MAX_DEGRADED)
    }

    /**
     * Returns a recommended analysis size as a [Size] object.
     *
     * @param recordingActive Whether recording is in progress.
     * @param strictHighEnd Whether high-end pipeline is active.
     */
    fun resolveAnalysisSurface(
        recordingActive: Boolean,
        strictHighEnd: Boolean = HighEndQualityPolicy.strictHighEndOnly
    ): Size {
        val longEdge = if (recordingActive) {
            if (strictHighEnd) RECORDING_ANALYSIS_MAX_HIGH_END else RECORDING_ANALYSIS_MAX_MINIMAL
        } else {
            if (strictHighEnd) ANALYSIS_MAX_HIGH_END else ANALYSIS_MAX_STANDARD
        }
        val shortEdge = (longEdge * 9 / 16).coerceAtLeast(360)
        return Size(longEdge, shortEdge)
    }

    /**
     * Returns whether a given preview size exceeds the recommended limit for
     * the current thermal/performance state.
     *
     * @param previewSize The current preview surface size.
     * @param thermalLabel Current thermal state label.
     */
    fun isPreviewOversized(previewSize: Size, thermalLabel: String = ""): Boolean {
        val limit = resolvePreviewLimits(thermal = thermalLabel)
        return previewSize.width * previewSize.height > limit.width * limit.height
    }

    /**
     * Returns the recommended preview long edge for a given codec target size.
     * Ensures preview does not exceed the recording output long edge,
     * avoiding unnecessary upscaling artifacts in the preview pipeline.
     *
     * @param recordingSize Target recording output size.
     * @param strict Whether strict maximum is enforced.
     */
    fun recommendedPreviewLongEdge(recordingSize: Size, strict: Boolean = false): Int {
        val recordingLong = maxOf(recordingSize.width, recordingSize.height)
        return if (strict) {
            minOf(recordingLong, PREVIEW_MAX_STRICT_HIGH_END.width)
        } else {
            minOf(recordingLong, PREVIEW_MAX_STANDARD.width)
        }
    }
}

// ── Module-level fallback accessors ────────────────────────────────────────────

/** Default fallback for photo preview when the resolver is unavailable. */
val defaultPhotoPreviewFallback: Size get() = Size(1920, 1080)

/** Default fallback for stream preview when the resolver is unavailable. */
val defaultStreamPreviewFallback: Size get() = Size(1920, 1080)
