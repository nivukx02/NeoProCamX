package com.neoproc.neoproccamx.ui.controls

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

internal object ManualControlLabelFormatter {
    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    fun isoLabel(iso: Int): String {
        if (!strictHighEndEnabled()) return "ISO $iso"
        return if (iso in 100..3200) {
            "ISO $iso • NATIVE"
        } else {
            "ISO $iso"
        }
    }

    fun shutterLabel(exposureNs: Long): String {
        if (exposureNs <= 0L) return "Auto"
        val seconds = exposureNs / 1_000_000_000.0
        return if (seconds >= 1.0) {
            String.format(Locale.US, "%.2fs", seconds)
        } else if (seconds >= 0.05) {
            String.format(Locale.US, "1/%.0f", 1.0 / seconds)
        } else {
            "1/${(1.0 / seconds).roundToInt().coerceAtLeast(1)}"
        }
    }

    fun whiteBalanceLabel(kelvin: Int): String = "${kelvin}K"

    fun focusLabel(distanceDiopters: Float): String = if (distanceDiopters <= 0.01f) {
        "INF"
    } else {
        val meters = (1f / distanceDiopters).coerceAtMost(999f)
        if (meters < 1f) {
            String.format(Locale.US, "%.0fcm", meters * 100f)
        } else {
            String.format(Locale.US, "%.2fm", meters)
        }
    }

    fun evLabel(ev: Int): String = evStopsLabel(ev.toFloat())

    fun evIndexLabel(index: Int, stepStops: Float): String =
        evStopsLabel(index * stepStops.coerceAtLeast(0.01f))

    private fun evStopsLabel(stops: Float): String {
        val normalized = if (abs(stops) < 0.05f) 0f else stops
        val nearestWhole = normalized.roundToInt()
        val value = if (abs(normalized - nearestWhole) < 0.05f) {
            nearestWhole.toString()
        } else {
            String.format(Locale.US, "%.1f", normalized)
        }
        val prefix = if (normalized > 0f) "+" else ""
        return "$prefix$value EV"
    }

    fun metricProfileLabel(colorProfile: String): String =
        colorProfile.takeIf { it.isNotBlank() } ?: if (strictHighEndEnabled()) "Cinema Neutral" else "Neutral Film"

    fun metricHistogramLabel(
        showHistogram: Boolean,
        showWaveform: Boolean,
        showZebra: Boolean
    ): String = when {
        showHistogram -> "Luma"
        showWaveform -> "Wave"
        showZebra -> "Zebra"
        else -> "Luma"
    }

    fun metricFocusLabel(
        peakingEnabled: Boolean,
        faceDetectionEnabled: Boolean,
        eyeAutofocusEnabled: Boolean
    ): String = when {
        peakingEnabled -> "Peaking"
        faceDetectionEnabled -> "Face"
        eyeAutofocusEnabled -> "Eye AF"
        else -> "Focus"
    }
}
