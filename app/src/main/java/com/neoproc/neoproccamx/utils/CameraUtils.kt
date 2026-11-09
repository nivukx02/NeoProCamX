package com.neoproc.neoproccamx.utils

import android.util.Size
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

object CameraUtils {
    private const val NS_PER_SECOND = 1_000_000_000.0
    private const val DEFAULT_ASPECT = 16f / 9f
    private const val STANDARD_ASPECT_PRIORITY_EPSILON = 0.02f
    private const val STRICT_ASPECT_PRIORITY_EPSILON = 0.01f

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    private fun sizeArea(size: Size): Long =
        size.width.coerceAtLeast(1).toLong() * size.height.coerceAtLeast(1).toLong()

    private fun aspectDelta(size: Size, targetAspect: Float): Float {
        val width = size.width.coerceAtLeast(1).toFloat()
        val height = size.height.coerceAtLeast(1).toFloat()
        return abs((width / height) - targetAspect)
    }

    fun chooseBestSize(
        choices: List<Size>,
        maxWidth: Int,
        maxHeight: Int,
        preferredAspect: Float = DEFAULT_ASPECT
    ): Size? {
        if (choices.isEmpty()) return null

        val safeMaxWidth = maxWidth.coerceAtLeast(1)
        val safeMaxHeight = maxHeight.coerceAtLeast(1)
        val targetAspect = preferredAspect.takeIf { it.isFinite() && it > 0f } ?: DEFAULT_ASPECT
        val validChoices = choices
        val fitting = validChoices.filter {
            it.width.coerceAtLeast(1) <= safeMaxWidth && it.height.coerceAtLeast(1) <= safeMaxHeight
        }
        val candidates = if (fitting.isNotEmpty()) fitting else validChoices
        val strict = strictHighEndEnabled()
        val aspectPriorityEpsilon = if (strict) {
            STRICT_ASPECT_PRIORITY_EPSILON
        } else {
            STANDARD_ASPECT_PRIORITY_EPSILON
        }

        val bestAspectError = candidates.minOfOrNull { aspectDelta(it, targetAspect) } ?: Float.MAX_VALUE
        val aspectPinnedCandidates = candidates.filter {
            aspectDelta(it, targetAspect) <= bestAspectError + aspectPriorityEpsilon
        }
        if (strict && aspectPinnedCandidates.isNotEmpty()) {
            return aspectPinnedCandidates.maxByOrNull(::sizeArea)
        }

        return candidates.minWithOrNull(
            compareBy<Size> {
                aspectDelta(it, targetAspect)
            }.thenByDescending {
                sizeArea(it)
            }
        )
    }

    fun formatExposureTime(ns: Long): String {
        if (ns <= 0) return "Auto"
        val sec = ns / NS_PER_SECOND
        return if (sec >= 1.0) {
            val precision = if (sec >= 10.0) 0 else 1
            String.format(Locale.US, "%.${precision}fs", sec)
        } else {
            val denominator = (NS_PER_SECOND / ns.toDouble()).roundToInt().coerceAtLeast(1)
            if (denominator < 2) {
                String.format(Locale.US, "%.2fs", sec)
            } else {
                "1/$denominator"
            }
        }
    }

    fun formatISO(iso: Int): String = if (iso > 0) "ISO $iso" else "Auto"

    fun formatKelvin(kelvin: Int): String = if (kelvin > 0) "${kelvin}K" else "Auto"

    fun focusDistanceToMeters(diopters: Float): Float {
        return if (diopters.isFinite() && diopters > 0f) 1f / diopters else Float.POSITIVE_INFINITY
    }

    fun formatFocusDistance(diopters: Float): String {
        val meters = focusDistanceToMeters(diopters)
        return if (meters.isInfinite()) {
            "∞"
        } else if (meters >= 100f) {
            String.format(Locale.US, "%.0fm", meters)
        } else if (meters >= 10f) {
            String.format(Locale.US, "%.1fm", meters)
        } else if (meters >= 1f) {
            String.format(Locale.US, "%.2fm", meters)
        } else {
            "${(meters * 100f).roundToInt()}cm"
        }
    }

    fun formatDuration(ms: Long): String {
        val totalSec = ms.coerceAtLeast(0L) / 1000L
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%02d:%02d", m, s)
        }
    }
}
