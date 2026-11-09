package com.neoproc.neoproccamx.ui

import kotlin.math.abs

internal data class CameraDisplayModeSpec(
    val modeId: Int,
    val width: Int,
    val height: Int,
    val refreshRate: Float
)

/**
 * Keeps the physical panel cadence appropriate for the active camera workload.
 *
 * A 25/30 fps camera preview cannot produce additional unique frames on a 120/144 Hz panel. During
 * recording, selecting the native-resolution 60 Hz mode therefore preserves every visible source
 * frame while reducing display/compositor contention and heat available to the full-resolution GPU
 * encoder path. Idle preview retains the prior highest-refresh behavior.
 */
internal object CameraDisplayRefreshPolicy {
    const val RECORDING_TARGET_HZ = 60f
    private const val HIGH_REFRESH_FLOOR_HZ = 119f
    private const val RATE_TOLERANCE_HZ = 0.75f

    fun select(
        modes: List<CameraDisplayModeSpec>,
        currentWidth: Int,
        currentHeight: Int,
        recordingActive: Boolean
    ): CameraDisplayModeSpec? {
        if (modes.isEmpty()) return null
        val nativeResolutionModes = modes.filter {
            it.width == currentWidth && it.height == currentHeight && it.refreshRate > 0f
        }
        val candidates = nativeResolutionModes.ifEmpty { modes.filter { it.refreshRate > 0f } }
        if (candidates.isEmpty()) return null

        if (!recordingActive) {
            return candidates.filter { it.refreshRate >= HIGH_REFRESH_FLOOR_HZ }
                .maxByOrNull { it.refreshRate }
                ?: candidates.maxByOrNull { it.refreshRate }
        }

        return candidates.filter { it.refreshRate <= RECORDING_TARGET_HZ + RATE_TOLERANCE_HZ }
            .maxByOrNull { it.refreshRate }
            ?: candidates.minByOrNull { abs(it.refreshRate - RECORDING_TARGET_HZ) }
    }
}
