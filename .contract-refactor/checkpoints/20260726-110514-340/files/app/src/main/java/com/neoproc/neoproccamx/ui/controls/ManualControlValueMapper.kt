package com.neoproc.neoproccamx.ui.controls

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.utils.ShutterTable
import kotlin.math.pow

/**
 * ════════════════════════════════════════════════════════════════════════════════
 * MANUAL CONTROL VALUE MAPPER - cinema-oriented CONTROLS
 * ════════════════════════════════════════════════════════════════════════════════
 * 
 * Maps UI slider positions to professional camera control values.
 * All ranges match or exceed professional cinema camera capabilities.
 */
internal object ManualControlValueMapper {
    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    // ═══════════════════════════════════════════════════════════════════════════
    // ISO CONFIGURATION - Extended Dynamic Range
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Extended ISO range matching professional cinema cameras.
     *
     * Standard 1/3-stop sequence from ISO 25 (extended low) to ISO 409600
     * (max extended). 43 discrete steps. Index 0 = 25, index 6 = 100 (native
     * start), index 21 = 3200 (native end), index 42 = 409600.
     */
    val isoValues: IntArray = intArrayOf(
        25, 32, 40,                              // Extended low
        50, 64, 80,                              // Below native
        100, 125, 160,                           // Native start
        200, 250, 320,
        400, 500, 640,
        800, 1000, 1250,
        1600, 2000, 2500,
        3200,                                    // Native end
        4000, 5000, 6400,                        // Extended high
        8000, 10000, 12800,
        16000, 20000, 25600,
        32000, 40000, 51200,
        64000, 80000, 102400,
        128000, 160000, 204800,
        256000, 320000, 409600                   // Max extended
    )
    
    /** ISO values considered "native" (best quality) */
    val nativeIsoRange = 100..3200
    
    /** Default ISO progress position (ISO 400) */
    const val defaultIsoProgress = 12

    // ═══════════════════════════════════════════════════════════════════════════
    // WHITE BALANCE CONFIGURATION - Professional Color Temperature
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Extended white balance range: 1800K to 20000K in 50K steps. The 50K
     * step gives a smooth slider feel while staying coarse enough that the
     * UI doesn't accumulate sub-perceptual delta requests against the
     * Camera2 white-balance pipeline.
     */
    const val whiteBalanceMin = 1800
    const val whiteBalanceMax = 20000
    const val whiteBalanceStep = 50
    const val whiteBalanceProgressMax = (whiteBalanceMax - whiteBalanceMin) / whiteBalanceStep
    
    /** Default white balance (5600K daylight) */
    const val defaultWhiteBalanceProgress = (5600 - whiteBalanceMin) / whiteBalanceStep
    
    /** Tint range: -150 (green) to +150 (magenta) */
    const val tintMin = -150
    const val tintMax = 150
    const val tintProgressMax = tintMax - tintMin
    const val defaultTintProgress = tintProgressMax / 2

    // ═══════════════════════════════════════════════════════════════════════════
    // FOCUS CONFIGURATION - Precision Manual Focus
    // ═══════════════════════════════════════════════════════════════════════════
    
    /** Focus slider precision */
    const val focusProgressMax = 1000  // Fine-grained manual focus slider steps
    
    /** Focus peaking sensitivity levels */
    enum class FocusPeakingSensitivity {
        OFF, LOW, MEDIUM, HIGH, ULTRA
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SHUTTER ANGLE CONFIGURATION - Cinema Standard
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Cinema shutter angles in degrees.
     * 180° is cinema standard, lower = more staccato, higher = more blur.
     */
    val shutterAngles = floatArrayOf(
        11.25f, 22.5f, 45f, 72f, 90f, 120f, 144f, 150f, 
        172.8f, 180f, 200f, 220f, 270f, 324f, 358f, 360f
    )
    
    /** Default shutter angle (180° cinema standard) */
    const val defaultShutterAngleIndex = 9
    
    /** Motion blur descriptors for UI */
    val shutterAngleLabels = arrayOf(
        "11.25° Extreme Staccato", "22.5° Very Staccato", "45° Staccato/Combat",
        "72° Light Blur", "90° Reduced Blur", "120° Slight Reduction",
        "144° Video Standard", "150° Broadcast", "172.8° NTSC Video",
        "180° Cinema Standard", "200° Slight Blur", "220° Enhanced Blur",
        "270° Heavy Blur", "324° Very Heavy Blur", "358° Max Blur", "360° Open Shutter"
    )

    // ═══════════════════════════════════════════════════════════════════════════
    // EXPOSURE COMPENSATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    /** Extended EV range for HDR capture */
    const val evRangeExtended = 6  // -6 to +6 stops
    
    /** Fine EV step (1/3 stop) */
    const val evStepFine = 0.33f

    // ═══════════════════════════════════════════════════════════════════════════
    // TIMING CONFIGURATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    /** Debounce time for manual control changes */
    const val manualControlDebounceMs = 4L
    
    /** Animation duration for smooth value changes */
    const val valueAnimationDurationMs = 150L

    // ═══════════════════════════════════════════════════════════════════════════
    // VALUE MAPPING FUNCTIONS
    // ═══════════════════════════════════════════════════════════════════════════

    fun isoValueForProgress(progress: Int): Int =
        isoValues[progress.coerceIn(0, isoValues.lastIndex)]
    
    fun progressForIsoValue(iso: Int): Int =
        isoValues.indexOfFirst { it >= iso }.takeIf { it >= 0 } ?: isoValues.lastIndex
    
    fun isNativeIso(iso: Int): Boolean = iso in nativeIsoRange

    fun shutterEntryForProgress(progress: Int): ShutterTable.Entry =
        ShutterTable.entries[progress.coerceIn(0, ShutterTable.entries.lastIndex)]

    fun whiteBalanceKelvinForProgress(progress: Int): Int =
        whiteBalanceMin + progress.coerceIn(0, whiteBalanceProgressMax) * whiteBalanceStep
    
    fun progressForWhiteBalanceKelvin(kelvin: Int): Int =
        ((kelvin - whiteBalanceMin) / whiteBalanceStep).coerceIn(0, whiteBalanceProgressMax)
    
    fun tintValueForProgress(progress: Int): Int =
        tintMin + progress.coerceIn(0, tintProgressMax)
    
    fun progressForTintValue(tint: Int): Int =
        (tint - tintMin).coerceIn(0, tintProgressMax)

    /**
     * Convert focus slider progress to diopter distance.
     * Uses eased curve for finer control at close distances.
     */
    fun focusDistanceForProgress(progress: Int, minFocusDistance: Float): Float {
        val normalized = progress.coerceIn(0, focusProgressMax) / focusProgressMax.toFloat()
        // Quadratic easing for finer control at macro distances
        val eased = 1f - normalized.toDouble().pow(2.2).toFloat()
        return minFocusDistance.coerceAtLeast(0f) * eased.coerceIn(0f, 1f)
    }
    
    fun progressForFocusDistance(distance: Float, minFocusDistance: Float): Int {
        if (minFocusDistance <= 0f) return 0
        val ratio = (distance / minFocusDistance).coerceIn(0f, 1f)
        val normalized = 1f - ratio
        val uneased = normalized.toDouble().pow(1.0 / 2.2).toFloat()
        return (uneased * focusProgressMax).toInt().coerceIn(0, focusProgressMax)
    }

    /**
     * Convert shutter angle to exposure time in nanoseconds.
     * 
     * @param angleDegrees Shutter angle in degrees
     * @param fps Current frame rate
     * @return Exposure time in nanoseconds
     */
    fun shutterAngleToExposureNs(angleDegrees: Float, fps: Float): Long {
        val exposureFraction = angleDegrees / 360f
        val frameTimeNs = (1_000_000_000L / fps).toLong()
        return (frameTimeNs * exposureFraction).toLong()
    }
    
    /**
     * Convert exposure time to equivalent shutter angle.
     */
    fun exposureNsToShutterAngle(exposureNs: Long, fps: Float): Float {
        val frameTimeNs = 1_000_000_000L / fps
        return (exposureNs.toFloat() / frameTimeNs) * 360f
    }

    fun evValueForProgress(progress: Int, lower: Int, upper: Int): Int =
        (progress + lower).coerceIn(lower, upper)

    fun evProgressForValue(value: Int, lower: Int, upper: Int): Int {
        val clamped = value.coerceIn(lower, upper)
        return (clamped - lower).coerceIn(0, (upper - lower).coerceAtLeast(1))
    }
    
    /**
     * Get descriptive label for ISO value.
     */
    fun isoLabel(iso: Int): String {
        if (strictHighEndEnabled()) {
            return when {
                iso < 100 -> "ISO $iso (Extended Low)"
                iso <= 3200 -> "ISO $iso (Native Cinema)"
                iso <= 25600 -> "ISO $iso (Extended High)"
                else -> "ISO $iso (Ultra High)"
            }
        }
        return when {
            iso < 100 -> "ISO $iso (Extended Low)"
            iso <= 3200 -> "ISO $iso (Native)"
            iso <= 25600 -> "ISO $iso (Extended High)"
            else -> "ISO $iso (Ultra High)"
        }
    }
    
    /**
     * Get descriptive label for white balance.
     */
    fun whiteBalanceLabel(kelvin: Int): String {
        if (strictHighEndEnabled() && kelvin in 5200..6000) {
            return "${kelvin}K (Daylight Reference)"
        }
        return when {
            kelvin < 2500 -> "${kelvin}K (Warm/Candlelight)"
            kelvin < 3500 -> "${kelvin}K (Tungsten)"
            kelvin < 4500 -> "${kelvin}K (Fluorescent)"
            kelvin < 5500 -> "${kelvin}K (Daylight)"
            kelvin < 6500 -> "${kelvin}K (Overcast)"
            kelvin < 8000 -> "${kelvin}K (Shade)"
            else -> "${kelvin}K (Blue Sky)"
        }
    }
}
