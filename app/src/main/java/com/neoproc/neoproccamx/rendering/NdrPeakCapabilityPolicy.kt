package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.color.NdrNivukxDeliveryEngine

/**
 * Project-owned NDR peak policy. Content mastering headroom and monitor brightness are deliberately
 * separate: a display capability never causes the encoder to claim unsupported pixels, and a
 * high-nit master is never sent directly to a lower-nit preview panel.
 */
object NdrPeakCapabilityPolicy {
    const val MAX_NDR_PEAK_NITS = 10_000f
    const val DIFFUSE_WHITE_NITS = 203f

    data class Plan(
        val contentPeakNits: Float,
        val monitorPeakNits: Float,
        val expandedContentPeak: Boolean
    )

    fun resolve(
        mode: NdrNivukxDeliveryEngine.Mode,
        displayMaxLuminanceNits: Float?,
        @Suppress("UNUSED_PARAMETER") requestedContentPeakNits: Float? = null,
        @Suppress("UNUSED_PARAMETER") encoderPeakCapabilityNits: Float? = null
    ): Plan {
        val baseline = NdrNivukxDeliveryEngine.targetPeakNits(mode).toFloat()
        // Mastering luminance describes the encoded PQ pixels, never the monitor or an optional
        // capability hint. Main10/ST.2084 itself spans 0..10000 nits, so both project targets are
        // exact and fail closed instead of silently producing a differently-labelled master.
        val contentPeak = baseline
        val monitorPeak = displayMaxLuminanceNits
            ?.takeIf { it.isFinite() && it > 0f }
            ?.coerceIn(DIFFUSE_WHITE_NITS, MAX_NDR_PEAK_NITS)
            ?.coerceAtMost(contentPeak)
            ?: minOf(1_000f, contentPeak)
        return Plan(
            contentPeakNits = contentPeak,
            monitorPeakNits = monitorPeak,
            expandedContentPeak = false
        )
    }

    fun modeForCode(modeCode: Int): NdrNivukxDeliveryEngine.Mode =
        if (modeCode == Hdr10PqPreviewCompositor.MODE_NIVUKX_VISION) {
            NdrNivukxDeliveryEngine.Mode.NIVUKX_VISION
        } else {
            NdrNivukxDeliveryEngine.Mode.NDR10_PLUS
        }
}
