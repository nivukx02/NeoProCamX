package com.neoproc.neoproccamx.ui.controls

import com.neoproc.neoproccamx.camera.CameraLongRange
import com.neoproc.neoproccamx.camera.CameraRect
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal enum class CinemaManualMeteringMode {
    MATRIX,
    CENTER_WEIGHTED,
    SPOT
}

internal data class CinemaWhiteBalanceRange(
    val lower: Int,
    val upper: Int,
    val step: Int = 50
)

internal data class CinemaShutterAngleEntry(
    val angleDegrees: Float,
    val exposureNs: Long,
    val label: String
)

internal object CinemaManualControlPolicy {
    private const val NS_PER_SECOND = 1_000_000_000.0
    private const val DEFAULT_WB_LOWER_K = 2_000
    private const val DEFAULT_WB_UPPER_K = 12_000
    private const val WB_STEP_K = 50

    private val cinemaShutterAngles = floatArrayOf(
        1f,
        4f,
        8.6f,
        11.25f,
        22.5f,
        45f,
        72f,
        90f,
        105f,
        120f,
        135f,
        144f,
        172.8f,
        180f,
        216f,
        240f,
        270f,
        288f,
        324f,
        360f
    )

    fun shutterAngleEntries(
        fps: Int,
        exposureRange: CameraLongRange
    ): List<CinemaShutterAngleEntry> {
        val safeFps = fps.coerceAtLeast(1)
        val lower = exposureRange.lower.coerceAtMost(exposureRange.upper)
        val upper = exposureRange.upper.coerceAtLeast(exposureRange.lower)
        val entries = cinemaShutterAngles.asSequence().mapNotNull { angle ->
            val exposureNs = shutterAngleToExposureNs(angle, safeFps)
            if (exposureNs !in lower..upper) {
                null
            } else {
                CinemaShutterAngleEntry(
                    angleDegrees = angle,
                    exposureNs = exposureNs,
                    label = shutterAngleLabel(angle, exposureNs)
                )
            }
        }.toList()
        return entries.ifEmpty {
            val fallbackNs = lower.coerceAtLeast(1L)
            listOf(
                CinemaShutterAngleEntry(
                    angleDegrees = exposureNsToShutterAngle(fallbackNs, safeFps),
                    exposureNs = fallbackNs,
                    label = shutterAngleLabel(exposureNsToShutterAngle(fallbackNs, safeFps), fallbackNs)
                )
            )
        }
    }

    fun shutterEntryForProgress(
        progress: Int,
        entries: List<CinemaShutterAngleEntry>
    ): CinemaShutterAngleEntry =
        entries[progress.coerceIn(0, entries.lastIndex.coerceAtLeast(0))]

    fun progressForExposureNs(
        exposureNs: Long,
        entries: List<CinemaShutterAngleEntry>
    ): Int {
        if (entries.isEmpty()) return 0
        return entries.indices.minByOrNull { index ->
            val candidate = entries[index].exposureNs
            if (candidate >= exposureNs) candidate - exposureNs else exposureNs - candidate
        } ?: 0
    }

    fun shutterAngleToExposureNs(angleDegrees: Float, fps: Int): Long {
        val safeAngle = angleDegrees.coerceIn(1f, 360f)
        val safeFps = fps.coerceAtLeast(1)
        return (NS_PER_SECOND / (safeFps * 360.0 / safeAngle)).roundToLong().coerceAtLeast(1L)
    }

    fun exposureNsToShutterAngle(exposureNs: Long, fps: Int): Float {
        if (exposureNs <= 0L || fps <= 0) return 0f
        return ((exposureNs.toDouble() / NS_PER_SECOND) * fps * 360.0)
            .toFloat()
            .coerceIn(1f, 360f)
    }

    fun whiteBalanceRange(
        hardwareLower: Int? = null,
        hardwareUpper: Int? = null
    ): CinemaWhiteBalanceRange {
        val lower = (hardwareLower ?: DEFAULT_WB_LOWER_K).coerceAtLeast(1)
        val upper = (hardwareUpper ?: DEFAULT_WB_UPPER_K).coerceAtLeast(lower)
        return CinemaWhiteBalanceRange(lower, upper, WB_STEP_K)
    }

    fun whiteBalanceProgressMax(range: CinemaWhiteBalanceRange): Int =
        ceil((range.upper - range.lower).coerceAtLeast(0) / range.step.coerceAtLeast(1).toDouble())
            .toInt()
            .coerceAtLeast(1)

    fun whiteBalanceKelvinForProgress(
        progress: Int,
        range: CinemaWhiteBalanceRange
    ): Int {
        val maxProgress = whiteBalanceProgressMax(range)
        if (progress <= 0) return range.lower
        if (progress >= maxProgress) return range.upper
        return (range.lower + progress * range.step).coerceIn(range.lower, range.upper)
    }

    fun progressForWhiteBalanceKelvin(
        kelvin: Int,
        range: CinemaWhiteBalanceRange
    ): Int {
        val maxProgress = whiteBalanceProgressMax(range)
        if (kelvin <= range.lower) return 0
        if (kelvin >= range.upper) return maxProgress
        return ((kelvin - range.lower).toFloat() / range.step.coerceAtLeast(1).toFloat())
            .roundToInt()
            .coerceIn(0, maxProgress)
    }

    fun meteringRegion(
        activeArray: CameraRect,
        mode: CinemaManualMeteringMode
    ): CameraRect? = when (mode) {
        CinemaManualMeteringMode.MATRIX -> null
        CinemaManualMeteringMode.CENTER_WEIGHTED -> centeredRegion(activeArray, 0.50f)
        CinemaManualMeteringMode.SPOT -> centeredRegion(activeArray, 0.12f)
    }

    private fun centeredRegion(activeArray: CameraRect, windowRatio: Float): CameraRect {
        val safeWidth = activeArray.width.coerceAtLeast(1)
        val safeHeight = activeArray.height.coerceAtLeast(1)
        val halfWidth = (safeWidth * windowRatio * 0.5f).roundToInt().coerceAtLeast(1)
        val halfHeight = (safeHeight * windowRatio * 0.5f).roundToInt().coerceAtLeast(1)
        val centerX = activeArray.left + safeWidth / 2
        val centerY = activeArray.top + safeHeight / 2
        val left = (centerX - halfWidth).coerceIn(activeArray.left, activeArray.right - 1)
        val top = (centerY - halfHeight).coerceIn(activeArray.top, activeArray.bottom - 1)
        val right = (centerX + halfWidth).coerceIn(left + 1, activeArray.right)
        val bottom = (centerY + halfHeight).coerceIn(top + 1, activeArray.bottom)
        return CameraRect(left, top, right, bottom)
    }

    private fun shutterAngleLabel(angleDegrees: Float, exposureNs: Long): String {
        val angle = if (angleDegrees % 1f == 0f) {
            angleDegrees.roundToInt().toString()
        } else {
            String.format(Locale.US, "%.1f", angleDegrees)
        }
        return "$angle° ${ManualControlLabelFormatter.shutterLabel(exposureNs)}"
    }
}
