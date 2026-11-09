package com.neoproc.neoproccamx.camera

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import kotlin.math.abs
import kotlin.math.roundToInt
import java.util.Locale

// ════════════════════════════════════════════════════════════════════════════════
// HIGH-END CAPTURE RESULT DATA
// ════════════════════════════════════════════════════════════════════════════════
// Professional-grade capture metadata with cinema exposure analysis,
// focus quality metrics, and professional HUD formatting.
// ════════════════════════════════════════════════════════════════════════════════

/**
 * Exposure metering mode for professional analysis.
 */
enum class MeteringMode {
    MATRIX,
    CENTER_WEIGHTED,
    SPOT,
    HIGHLIGHT_PRIORITY,
    SHADOW_PRIORITY
}

/**
 * Focus quality assessment.
 */
enum class FocusQuality {
    EXCELLENT,
    GOOD,
    ACCEPTABLE,
    SOFT,
    OUT_OF_FOCUS,
    UNKNOWN
}

/**
 * Exposure assessment relative to scene.
 */
enum class ExposureAssessment {
    OPTIMAL,
    SLIGHTLY_OVER,
    OVER_EXPOSED,
    SLIGHTLY_UNDER,
    UNDER_EXPOSED,
    EXTREME
}

/**
 * Display-ready snapshot of the active Camera2 capture result.
 *
 * Values are preformatted for HUD rendering so overlays do not need to
 * duplicate camera formatting logic. Extended with professional metadata
 * for cinema-oriented monitoring.
 */
data class CaptureResultData(
    val isoDisplay: String,
    val shutterSpeedFraction: String,
    val focusDistance: Float,
    val afState: Int,
    
    // Professional metadata
    val isoValue: Int = 0,
    val exposureTimeNs: Long = 0L,
    val apertureValue: Float = 0f,
    val focalLength: Float = 0f,
    val exposureCompensation: Float = 0f,
    val whiteBalance: Int = 0,
    val colorTemperature: Int = 0,
    val aeState: Int = 0,
    val awbState: Int = 0,
    val lensState: Int = 0,
    val flashState: Int = 0,
    val sensorSensitivity: Int = 0,
    val frameNumber: Long = 0L,
    val timestampNs: Long = 0L,
    
    // Cinema-specific metadata
    val shutterAngleDegrees: Float = 180f,
    val tStopEquivalent: Float = 0f,
    val exposureIndexEi: Int = 0,
    val colorMatrix: FloatArray? = null,
    val dynamicRangeStops: Float = 0f,
    
    // Focus metadata
    val focusDistanceMeters: Float = 0f,
    val depthOfFieldNear: Float = 0f,
    val depthOfFieldFar: Float = 0f,
    val hyperfocalDistance: Float = 0f,
    val focusQuality: FocusQuality = FocusQuality.UNKNOWN,
    
    // Exposure analysis
    val exposureValue: Float = 0f,
    val luminanceAverage: Float = 0f,
    val luminancePeak: Float = 0f,
    val highlightClipping: Float = 0f,
    val shadowClipping: Float = 0f,
    val exposureAssessment: ExposureAssessment = ExposureAssessment.OPTIMAL,
    
    // Scene analysis
    val facesDetected: Int = 0,
    val sceneMode: Int = 0,
    val flashRequired: Boolean = false
) {
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PROFESSIONAL FORMATTING
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Format ISO with cinema-style EI display.
     */
    val isoDisplayCinema: String
        get() = if (exposureIndexEi > 0) "EI $exposureIndexEi" else isoDisplay

    /**
     * Format shutter as angle for cinema display (e.g., "180°").
     */
    val shutterAngleDisplay: String
        get() = "${shutterAngleDegrees.roundToInt()}°"

    /**
     * Format aperture as T-stop equivalent.
     */
    val tStopDisplay: String
        get() = if (tStopEquivalent > 0) "T${formatAperture(tStopEquivalent)}" 
                else if (apertureValue > 0) "f/${formatAperture(apertureValue)}" 
                else "--"

    /**
     * Format focal length with mm suffix.
     */
    val focalLengthDisplay: String
        get() = if (focalLength > 0) "${focalLength.roundToInt()}mm" else "--"

    /**
     * Format focus distance in meters/feet.
     */
    val focusDistanceDisplay: String
        get() = when {
            focusDistanceMeters <= 0f -> "∞"
            focusDistanceMeters < 1f -> "${(focusDistanceMeters * 100).roundToInt()}cm"
            focusDistanceMeters < 10f -> String.format(Locale.US, "%.1fm", focusDistanceMeters)
            else -> String.format(Locale.US, "%.0fm", focusDistanceMeters)
        }

    /**
     * Format focus distance in imperial units.
     */
    val focusDistanceImperial: String
        get() {
            if (focusDistanceMeters <= 0f) return "∞"
            val feet = focusDistanceMeters * 3.28084f
            val inches = (feet % 1f) * 12f
            return when {
                feet < 1f -> "${inches.roundToInt()}\""
                feet < 10f -> "${feet.toInt()}'${inches.roundToInt()}\""
                else -> "${feet.roundToInt()}'"
            }
        }

    /**
     * Format exposure value for display.
     */
    val evDisplay: String
        get() = when {
            exposureValue == 0f -> "EV --"
            exposureValue > 0 -> "EV +${String.format(Locale.US, "%.1f", exposureValue)}"
            else -> "EV ${String.format(Locale.US, "%.1f", exposureValue)}"
        }

    /**
     * Format color temperature in Kelvin.
     */
    val colorTempDisplay: String
        get() = if (colorTemperature > 0) "${colorTemperature}K" else "--"

    /**
     * Cinema-style combined exposure display.
     */
    val cinemaExposureDisplay: String
        get() = buildString {
            append(isoDisplayCinema)
            append(" ")
            append(shutterAngleDisplay)
            append(" ")
            append(tStopDisplay)
        }

    /**
     * Depth of field summary.
     */
    val dofDisplay: String
        get() = when {
            depthOfFieldNear <= 0f || depthOfFieldFar <= 0f -> "--"
            depthOfFieldFar >= 1000f -> "${formatDistance(depthOfFieldNear)} - ∞"
            else -> "${formatDistance(depthOfFieldNear)} - ${formatDistance(depthOfFieldFar)}"
        }

    // ═══════════════════════════════════════════════════════════════════════════
    // EXPOSURE ANALYSIS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Calculate if exposure is within acceptable range.
     */
    val isExposureOptimal: Boolean
        get() = exposureAssessment == ExposureAssessment.OPTIMAL ||
                exposureAssessment == ExposureAssessment.SLIGHTLY_OVER ||
                exposureAssessment == ExposureAssessment.SLIGHTLY_UNDER

    /**
     * Calculate suggested EV compensation adjustment.
     */
    val suggestedEvAdjustment: Float
        get() = when (exposureAssessment) {
            ExposureAssessment.OVER_EXPOSED -> -1.0f
            ExposureAssessment.SLIGHTLY_OVER -> -0.3f
            ExposureAssessment.SLIGHTLY_UNDER -> 0.3f
            ExposureAssessment.UNDER_EXPOSED -> 1.0f
            ExposureAssessment.EXTREME -> if (luminanceAverage > 0.5f) -2.0f else 2.0f
            ExposureAssessment.OPTIMAL -> 0f
        }

    /**
     * Calculate histogram-based exposure quality (0-1).
     */
    val exposureQuality: Float
        get() {
            val clippingPenalty = (highlightClipping + shadowClipping).coerceAtMost(1f)
            val centeredBonus = 1f - abs(luminanceAverage - 0.18f) * 2f // 18% gray target
            return ((1f - clippingPenalty) * 0.6f + centeredBonus.coerceIn(0f, 1f) * 0.4f)
        }

    // ═══════════════════════════════════════════════════════════════════════════
    // FOCUS ANALYSIS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Check if focus is critically sharp.
     */
    val isFocusSharp: Boolean
        get() = focusQuality == FocusQuality.EXCELLENT || focusQuality == FocusQuality.GOOD

    /**
     * Check if focus is acceptable for capture.
     */
    val isFocusAcceptable: Boolean
        get() = focusQuality != FocusQuality.OUT_OF_FOCUS && focusQuality != FocusQuality.UNKNOWN

    /**
     * Calculate focus confidence (0-1).
     */
    val focusConfidence: Float
        get() = when (focusQuality) {
            FocusQuality.EXCELLENT -> 1.0f
            FocusQuality.GOOD -> 0.85f
            FocusQuality.ACCEPTABLE -> 0.7f
            FocusQuality.SOFT -> 0.4f
            FocusQuality.OUT_OF_FOCUS -> 0.1f
            FocusQuality.UNKNOWN -> 0f
        }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private fun formatAperture(value: Float): String {
        return if (value % 1f < 0.05f || value % 1f > 0.95f) {
            value.roundToInt().toString()
        } else {
            String.format(Locale.US, "%.1f", value)
        }
    }

    private fun formatDistance(meters: Float): String {
        return when {
            meters < 1f -> "${(meters * 100).roundToInt()}cm"
            meters < 10f -> String.format(Locale.US, "%.1fm", meters)
            else -> String.format(Locale.US, "%.0fm", meters)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as CaptureResultData

        if (isoDisplay != other.isoDisplay) return false
        if (shutterSpeedFraction != other.shutterSpeedFraction) return false
        if (focusDistance != other.focusDistance) return false
        if (afState != other.afState) return false
        if (frameNumber != other.frameNumber) return false
        if (timestampNs != other.timestampNs) return false

        return true
    }

    override fun hashCode(): Int {
        var result = isoDisplay.hashCode()
        result = 31 * result + shutterSpeedFraction.hashCode()
        result = 31 * result + focusDistance.hashCode()
        result = 31 * result + afState
        result = 31 * result + frameNumber.hashCode()
        return result
    }

    companion object {
        val EMPTY = CaptureResultData(
            isoDisplay = "ISO --",
            shutterSpeedFraction = "--",
            focusDistance = 0f,
            afState = 0
        )

        fun from(result: LiveResult?): CaptureResultData {
            result ?: return EMPTY
            return CaptureResultData(
                isoDisplay = result.isoLabel,
                shutterSpeedFraction = result.shutterLabel,
                focusDistance = result.focusDist,
                afState = result.afState
            )
        }

        /**
         * Create from Camera2 capture result with full professional metadata.
         */
        fun fromCapture(
            iso: Int,
            exposureTimeNs: Long,
            aperture: Float,
            focalLength: Float,
            focusDistance: Float,
            afState: Int,
            aeState: Int,
            awbState: Int,
            frameNumber: Long,
            timestampNs: Long,
            targetFps: Int = 30
        ): CaptureResultData {
            val isoLabel = "ISO $iso"
            val shutterLabel = formatShutterSpeed(exposureTimeNs)
            val shutterAngle = calculateShutterAngle(exposureTimeNs, targetFps)
            val ev = calculateExposureValue(iso, exposureTimeNs, aperture)
            val tStop = aperture * if (strictHighEndEnabled()) 1.03f else 1.05f
            
            return CaptureResultData(
                isoDisplay = isoLabel,
                shutterSpeedFraction = shutterLabel,
                focusDistance = focusDistance,
                afState = afState,
                isoValue = iso,
                exposureTimeNs = exposureTimeNs,
                apertureValue = aperture,
                focalLength = focalLength,
                aeState = aeState,
                awbState = awbState,
                frameNumber = frameNumber,
                timestampNs = timestampNs,
                shutterAngleDegrees = shutterAngle,
                tStopEquivalent = tStop,
                exposureValue = ev,
                focusDistanceMeters = if (focusDistance > 0) 1f / focusDistance else Float.MAX_VALUE
            )
        }

        /**
         * Format exposure time as fraction for display.
         */
        fun formatShutterSpeed(exposureTimeNs: Long): String {
            if (exposureTimeNs <= 0) return "--"
            val seconds = exposureTimeNs / 1_000_000_000.0
            return when {
                seconds >= 1.0 -> "${seconds.roundToInt()}s"
                seconds >= 0.5 -> String.format(Locale.US, "%.1fs", seconds)
                else -> {
                    val denominator = (1.0 / seconds).roundToInt()
                    "1/$denominator"
                }
            }
        }

        /**
         * Calculate shutter angle from exposure time and frame rate.
         */
        fun calculateShutterAngle(exposureTimeNs: Long, targetFps: Int): Float {
            if (exposureTimeNs <= 0 || targetFps <= 0) return 180f
            val frameTimeNs = 1_000_000_000L / targetFps
            return (exposureTimeNs.toFloat() / frameTimeNs * 360f).coerceIn(0f, 360f)
        }

        /**
         * Calculate exposure value (EV) from exposure triangle.
         */
        fun calculateExposureValue(iso: Int, exposureTimeNs: Long, aperture: Float): Float {
            if (iso <= 0 || exposureTimeNs <= 0 || aperture <= 0) return 0f
            val seconds = exposureTimeNs / 1_000_000_000.0
            // EV = log2(N²/t) adjusted for ISO
            val ev100 = kotlin.math.log2((aperture * aperture) / seconds.toFloat())
            return ev100 - kotlin.math.log2(iso / 100f)
        }

        /**
         * Assess exposure quality from histogram metrics.
         */
        fun assessExposure(
            luminanceAverage: Float,
            highlightClipping: Float,
            shadowClipping: Float
        ): ExposureAssessment {
            val strictHighEnd = strictHighEndEnabled()
            val extremeClipThreshold = if (strictHighEnd) 0.08f else 0.10f
            val clippingThreshold = if (strictHighEnd) 0.015f else 0.02f
            val slightlyOverLuma = if (strictHighEnd) 0.56f else 0.6f
            val slightlyUnderLuma = if (strictHighEnd) 0.14f else 0.12f
            return when {
                highlightClipping > extremeClipThreshold || shadowClipping > extremeClipThreshold -> ExposureAssessment.EXTREME
                highlightClipping > clippingThreshold -> ExposureAssessment.OVER_EXPOSED
                shadowClipping > clippingThreshold -> ExposureAssessment.UNDER_EXPOSED
                luminanceAverage > slightlyOverLuma -> ExposureAssessment.SLIGHTLY_OVER
                luminanceAverage < slightlyUnderLuma -> ExposureAssessment.SLIGHTLY_UNDER
                else -> ExposureAssessment.OPTIMAL
            }
        }

        /**
         * Assess focus quality from AF state and sharpness metric.
         */
        fun assessFocusQuality(
            afState: Int,
            sharpnessMetric: Float = 0f
        ): FocusQuality {
            // AF states from CaptureResult.CONTROL_AF_STATE_*
            val afFocused = afState == 4 || afState == 5 // FOCUSED_LOCKED or PASSIVE_FOCUSED
            val afNotFocused = afState == 2 || afState == 6 // NOT_FOCUSED_LOCKED or PASSIVE_UNFOCUSED
            val strictHighEnd = strictHighEndEnabled()
            val excellentThreshold = if (strictHighEnd) 0.84f else 0.8f
            val goodThreshold = if (strictHighEnd) 0.58f else 0.5f
            val softThreshold = if (strictHighEnd) 0.36f else 0.3f
            
            return when {
                afNotFocused -> FocusQuality.OUT_OF_FOCUS
                afFocused && sharpnessMetric > excellentThreshold -> FocusQuality.EXCELLENT
                afFocused && sharpnessMetric > goodThreshold -> FocusQuality.GOOD
                afFocused -> FocusQuality.ACCEPTABLE
                sharpnessMetric > softThreshold -> FocusQuality.SOFT
                sharpnessMetric > 0f -> FocusQuality.OUT_OF_FOCUS
                else -> FocusQuality.UNKNOWN
            }
        }

        private fun strictHighEndEnabled(): Boolean =
            HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly
    }
}
