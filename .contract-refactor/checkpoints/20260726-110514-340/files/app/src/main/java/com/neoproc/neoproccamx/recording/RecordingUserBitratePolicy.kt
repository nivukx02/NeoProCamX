package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.core.HighEndQualityPolicy

internal object RecordingUserBitratePolicy {
    fun resolveRequestedBitrate(
        requestedBitrate: Int,
        minimumBitrateFloor: Int,
        qualityBudgetFloor: Int = minimumBitrateFloor,
        encoderCapabilityUpperBound: Int?,
        cinemaMasteringEnabled: Boolean = false,
        manualSelectionEnabled: Boolean = false
    ): Int = resolveRequestedBitrate(
        requestedBitrate = requestedBitrate,
        minimumBitrateFloor = minimumBitrateFloor,
        qualityBudgetFloor = qualityBudgetFloor,
        encoderCapabilityUpperBound = encoderCapabilityUpperBound,
        width = 1920,
        height = 1080,
        fps = 25,
        cinemaMasteringEnabled = cinemaMasteringEnabled,
        manualSelectionEnabled = manualSelectionEnabled
    )

    fun resolveRequestedBitrate(
        requestedBitrate: Int,
        minimumBitrateFloor: Int,
        qualityBudgetFloor: Int = minimumBitrateFloor,
        encoderCapabilityUpperBound: Int?,
        width: Int,
        height: Int,
        fps: Int = 25,
        cinemaMasteringEnabled: Boolean = false,
        manualSelectionEnabled: Boolean = false
    ): Int {
        val pixels = width.toLong() * height.toLong()
        val strictMode = HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly
        val strictHighEndCeiling = HighEndQualityPolicy.minimumBitrateBps(
            width = width,
            height = height,
            fps = fps
        )
        val legacyResolutionCeiling = if (cinemaMasteringEnabled) {
            when {
                pixels >= 7680L * 4320L -> 180_000_000
                pixels >= 3840L * 2160L -> 100_000_000
                else -> 45_000_000
            }
        } else {
            when {
                pixels >= 7680L * 4320L -> 150_000_000
                pixels >= 3840L * 2160L -> 80_000_000
                else -> 45_000_000
            }
        }
        val automaticCeiling = if (strictMode) {
            maxOf(legacyResolutionCeiling, strictHighEndCeiling)
        } else {
            legacyResolutionCeiling
        }
        val upperLimit = if (manualSelectionEnabled) {
            encoderCapabilityUpperBound ?: automaticCeiling
        } else if (strictMode) {
            encoderCapabilityUpperBound ?: automaticCeiling
        } else {
            encoderCapabilityUpperBound?.coerceAtMost(automaticCeiling)
                ?: automaticCeiling
        }
        val effectiveFloor = if (manualSelectionEnabled) {
            minimumBitrateFloor
        } else if (strictMode) {
            maxOf(minimumBitrateFloor, qualityBudgetFloor, strictHighEndCeiling)
        } else {
            maxOf(minimumBitrateFloor, qualityBudgetFloor)
        }
        if (effectiveFloor > upperLimit) return upperLimit
        return requestedBitrate.coerceIn(effectiveFloor, upperLimit)
    }
}
