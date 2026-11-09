package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.ui.CameraViewModelConstants.SHUTTER_RULE_EXPOSURE_DENOMINATOR
import com.neoproc.neoproccamx.ui.CameraViewModelConstants.SHUTTER_RULE_TARGET_FPS
import com.neoproc.neoproccamx.ui.CameraViewModelConstants.SHUTTER_RULE_WARNING_TOLERANCE
import java.util.Locale
import kotlin.math.abs

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Cinema Display Utilities
//
// Pure functions for formatting cinema runtime display labels.
// Extracted from CameraViewModel for testability and reuse.
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

object CinemaDisplayUtils {

    /**
     * Formats a megapixel value for display.
     *
     * Example: `megapixelLabel(50.3f)` → `"50.3MP"`
     */
    fun megapixelLabel(megapixels: Float): String =
        String.format(Locale.US, "%.1fMP", megapixels)

    /**
     * Formats the storage label with free space and optional
     * remaining recording time.
     *
     * Example: `formatStorageLabel(32_000_000_000, 42)` → `"29.8 GB free • 42 min"`
     */
    fun formatStorageLabel(freeBytes: Long, remainingMinutes: Int?): String {
        val freeGb = freeBytes / (1024f * 1024f * 1024f)
        return if (remainingMinutes != null) {
            String.format(Locale.US, "%.1f GB free \u2022 %d min", freeGb, remainingMinutes)
        } else {
            String.format(Locale.US, "%.1f GB free", freeGb)
        }
    }

    /**
     * Resolves the 180° shutter rule guidance warning.
     *
     * Returns a human-readable warning string when the current
     * exposure deviates from the ideal 1/48s for 24fps cinematic
     * motion, or `null` if the exposure is within tolerance.
     *
     * @param isVideoMode whether the app is in VIDEO mode
     * @param fps current recording frame rate
     * @param currentExposureNs current sensor exposure time in nanoseconds
     */
    fun resolveShutterRuleGuidance(
        isVideoMode: Boolean,
        fps: Int,
        currentExposureNs: Long
    ): String? {
        if (!isVideoMode) return null
        if (fps != SHUTTER_RULE_TARGET_FPS) return null

        val targetExposureNs = 1_000_000_000L / SHUTTER_RULE_EXPOSURE_DENOMINATOR
        if (currentExposureNs <= 0L) {
            return "180-degree shutter: use ~1/${SHUTTER_RULE_EXPOSURE_DENOMINATOR}s for 24fps cinematic motion."
        }

        val deviation = abs(currentExposureNs - targetExposureNs).toFloat() / targetExposureNs.toFloat()
        return if (deviation > SHUTTER_RULE_WARNING_TOLERANCE) {
            "180-degree shutter: adjust to ~1/${SHUTTER_RULE_EXPOSURE_DENOMINATOR}s for 24fps cinematic motion."
        } else {
            null
        }
    }
}
