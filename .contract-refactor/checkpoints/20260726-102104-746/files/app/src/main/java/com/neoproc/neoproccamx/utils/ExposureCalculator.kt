package com.neoproc.neoproccamx.utils

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import java.util.Locale
import kotlin.math.*

/**
 * ExposureCalculator — Photographic exposure math utilities.
 *
 * Provides:
 *  - EV (Exposure Value) computation
 *  - ISO ↔ shutter speed equivalent exposure
 *  - Sunny 16 rule
 *  - ND filter stop calculation
 *  - 180° shutter rule for video
 *  - Depth of Field (DoF) estimation
 *  - Hyperfocal distance
 */
object ExposureCalculator {
    private const val DEFAULT_APERTURE = 2.8f
    private const val DEFAULT_FPS = 24
    private const val NS_PER_SECOND = 1_000_000_000f
    private const val DEFAULT_COC = 0.030f

    // Standard circle of confusion values (mm)
    private val CIRCLE_OF_CONFUSION = mapOf(
        "Full Frame"  to 0.030f,
        "APS-C"       to 0.020f,
        "Micro 4/3"   to 0.015f,
        "1-inch"      to 0.011f,
        "1/1.7\""     to 0.008f
    )
    private val STANDARD_ND_VALUES = intArrayOf(1, 2, 4, 8, 16, 32, 64, 128, 256, 512, 1000)

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    // ─── EV Calculation ───────────────────────────────────────────────────────

    /**
     * Compute EV from aperture f-stop, shutter speed, and ISO.
     * EV = log2(N² / t) - log2(S/100)
     */
    fun computeEV(aperture: Float, shutterSec: Float, iso: Int = 100): Float {
        if (
            !aperture.isFinite() || aperture <= 0f ||
            !shutterSec.isFinite() || shutterSec <= 0f ||
            iso <= 0
        ) {
            return 0f
        }
        val evBase = log2((aperture * aperture) / shutterSec)
        val isoAdj = log2(iso.toFloat() / 100f)
        return evBase - isoAdj
    }

    /**
     * Compute EV from Camera2 nanoseconds exposure + ISO.
     */
    fun computeEVFromCamera2(exposureNs: Long, iso: Int, aperture: Float = DEFAULT_APERTURE): Float {
        if (exposureNs <= 0 || iso <= 0) return 0f
        val shutterSec = exposureNs / NS_PER_SECOND
        return computeEV(aperture, shutterSec, iso)
    }

    // ─── 180° Shutter Rule ────────────────────────────────────────────────────

    /**
     * Returns the correct shutter speed in nanoseconds for 180° shutter angle at given FPS.
     */
    fun shutterFor180Rule(fps: Int): Long {
        val safeFps = fps.coerceAtLeast(1)
        val shutterSec = 1.0 / (safeFps * 2.0)
        return (shutterSec * NS_PER_SECOND.toDouble()).toLong().coerceAtLeast(1L)
    }

    /**
     * Returns the shutter for any arbitrary shutter angle (e.g. 270°).
     */
    fun shutterForAngle(fps: Int, angleDegrees: Float): Long {
        val safeFps = fps.coerceAtLeast(1)
        val safeAngle = angleDegrees.takeIf { it.isFinite() }?.coerceIn(1f, 360f) ?: 180f
        val shutterSec = safeAngle / (360f * safeFps)
        return (shutterSec * NS_PER_SECOND).toLong().coerceAtLeast(1L)
    }

    // ─── ND Filter ────────────────────────────────────────────────────────────

    /**
     * Calculate how many stops an ND filter reduces exposure.
     * ND 2 = 1 stop, ND 4 = 2 stops, ND 64 = 6 stops, ND 1000 = ~10 stops.
     */
    fun ndFilterStops(ndValue: Int): Float {
        if (ndValue <= 1) return 0f
        return log2(ndValue.toFloat())
    }

    /**
     * Given current shutter speed and desired stops-down, compute new shutter (with ND).
     */
    fun applyNDFilter(currentShutterNs: Long, ndStops: Float): Long {
        if (currentShutterNs <= 0L) return 1L
        if (!ndStops.isFinite()) return currentShutterNs
        val factor = 2f.pow(ndStops.coerceAtLeast(0f))
        return (currentShutterNs * factor).toLong().coerceAtLeast(1L)
    }

    /**
     * Recommend ND filter value to achieve 180° rule given current lighting.
     * Returns the ND value (e.g., 8 for ND8).
     */
    fun recommendND(fps: Int, currentShutterNs: Long): Int {
        val targetNs = shutterFor180Rule(if (fps > 0) fps else DEFAULT_FPS)
        val safeCurrentNs = currentShutterNs.coerceAtLeast(1L)
        if (safeCurrentNs >= targetNs) return 1 // Already at or slower than target shutter.
        val stopsNeeded = log2(targetNs.toFloat() / safeCurrentNs.toFloat()).coerceAtLeast(0f)
        val ndValue = 2f.pow(stopsNeeded).roundToInt().coerceAtLeast(1)
        val nearest = STANDARD_ND_VALUES.minByOrNull { abs(it - ndValue) } ?: ndValue
        if (!strictHighEndEnabled()) return nearest
        val qualityFloor = STANDARD_ND_VALUES.firstOrNull { it >= ndValue } ?: nearest
        return max(nearest, qualityFloor)
    }

    // ─── Equivalent Exposure ──────────────────────────────────────────────────

    /**
     * Given current ISO + shutter, compute equivalent exposure at a different ISO.
     */
    fun equivalentShutter(
        currentIso: Int, currentShutterNs: Long, targetIso: Int
    ): Long {
        if (currentIso <= 0 || targetIso <= 0 || currentShutterNs <= 0) return currentShutterNs
        return ((currentShutterNs.toDouble() * currentIso.toDouble()) / targetIso.toDouble())
            .toLong()
            .coerceAtLeast(1L)
    }

    // ─── Depth of Field ───────────────────────────────────────────────────────

    data class DofResult(
        val nearLimitM: Float,
        val farLimitM: Float,
        val totalDofM: Float,
        val hyperfocalM: Float,
        val isInfinite: Boolean
    )

    /**
     * Compute depth of field.
     *
     * @param aperture      f-stop (e.g., 2.8)
     * @param focalLengthMm focal length in mm
     * @param subjectDistM  subject distance in metres
     * @param sensorFormat  one of: "Full Frame", "APS-C", "Micro 4/3", "1-inch"
     */
    fun computeDof(
        aperture: Float,
        focalLengthMm: Float,
        subjectDistM: Float,
        sensorFormat: String = "Full Frame"
    ): DofResult {
        if (
            !aperture.isFinite() || aperture <= 0f ||
            !focalLengthMm.isFinite() || focalLengthMm <= 0f ||
            !subjectDistM.isFinite() || subjectDistM <= 0f
        ) {
            return DofResult(
                nearLimitM = 0f,
                farLimitM = Float.POSITIVE_INFINITY,
                totalDofM = Float.POSITIVE_INFINITY,
                hyperfocalM = 0f,
                isInfinite = true
            )
        }

        val coc = resolveCircleOfConfusion(sensorFormat)
        val fl = focalLengthMm / 1000f    // convert to metres
        val dist = subjectDistM

        val hyperfocal = (fl * fl) / (aperture * coc / 1000f)

        val nearDenom = hyperfocal + (dist - fl)
        val nearLimit = if (nearDenom <= 0f) dist else (hyperfocal * dist) / nearDenom
        val farDenom  = hyperfocal - (dist - fl)

        val isInfinite = farDenom <= 0f
        val farLimit = if (isInfinite) Float.POSITIVE_INFINITY
                       else (hyperfocal * dist) / farDenom

        val totalDof = if (isInfinite) Float.POSITIVE_INFINITY
                       else farLimit - nearLimit

        return DofResult(
            nearLimitM = nearLimit.coerceAtLeast(0f),
            farLimitM = farLimit,
            totalDofM = totalDof,
            hyperfocalM = hyperfocal,
            isInfinite = isInfinite
        )
    }

    // ─── Exposure Triangle Display ────────────────────────────────────────────

    /**
     * Format an exposure triangle summary string.
     */
    fun formatExposureTriangle(iso: Int, shutterNs: Long, aperture: Float = DEFAULT_APERTURE): String {
        val ss = CameraUtils.formatExposureTime(shutterNs)
        val ev = computeEVFromCamera2(shutterNs, iso, aperture)
        return "ISO $iso  •  $ss  •  f/${"%.1f".format(Locale.US, aperture)}  •  EV ${"%.1f".format(Locale.US, ev)}"
    }

    /**
     * Return a human-readable lighting condition name for a given EV.
     */
    fun lightingConditionName(ev: Float): String = when {
        ev < 0  -> "Extreme Night"
        ev < 3  -> "Night / Candlelight"
        ev < 6  -> "Indoor Low Light"
        ev < 9  -> "Indoor Mixed Light"
        ev < 11 -> "Overcast Outdoor"
        ev < 13 -> "Bright Overcast"
        ev < 15 -> "Bright Sunlight"
        ev < 17 -> "Direct Sunlight"
        else    -> "Bright Snow / Beach"
    }

    private fun resolveCircleOfConfusion(sensorFormat: String): Float {
        val normalized = sensorFormat.trim()
        if (normalized.isEmpty()) {
            return if (strictHighEndEnabled()) DEFAULT_COC * 0.85f else DEFAULT_COC
        }
        return CIRCLE_OF_CONFUSION.entries
            .firstOrNull { entry -> entry.key.equals(normalized, ignoreCase = true) }
            ?.value
            ?: if (strictHighEndEnabled()) DEFAULT_COC * 0.85f else DEFAULT_COC
    }
}
