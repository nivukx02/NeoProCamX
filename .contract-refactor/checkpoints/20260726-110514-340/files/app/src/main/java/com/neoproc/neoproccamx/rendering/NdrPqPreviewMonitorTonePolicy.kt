package com.neoproc.neoproccamx.rendering

import kotlin.math.exp

/** Host-testable mirror of the NDR PQ compositor's SDR-monitor highlight shoulder. */
internal object NdrPqPreviewMonitorTonePolicy {
    const val DIFFUSE_WHITE_NITS = 203f
    const val KNEE = 0.75f
    const val SHOULDER_STRENGTH = 4f

    fun mapLuminance(engineLinearLuma: Float, masterPeakNits: Float): Float {
        if (!engineLinearLuma.isFinite() || engineLinearLuma <= 0f) return 0f
        val luma = engineLinearLuma.coerceAtLeast(0f)
        if (luma <= KNEE) return luma
        val safePeak = masterPeakNits.takeIf(Float::isFinite)?.coerceAtLeast(DIFFUSE_WHITE_NITS)
            ?: DIFFUSE_WHITE_NITS
        val hdrSpan = (safePeak / DIFFUSE_WHITE_NITS - KNEE).coerceAtLeast(0.25f)
        return (
            KNEE +
                (1f - KNEE) *
                (1f - exp((-SHOULDER_STRENGTH * (luma - KNEE) / hdrSpan).toDouble()).toFloat())
            ).coerceIn(0f, 1f)
    }

    /** Host mirror of the shader's radial lower/upper BT.709 gamut compression. */
    fun compressUnitGamut(r: Float, g: Float, b: Float): FloatArray {
        var rgb = floatArrayOf(r, g, b)
        fun luma(): Float =
            (rgb[0] * 0.2126f + rgb[1] * 0.7152f + rgb[2] * 0.0722f).coerceAtLeast(0f)

        var y = luma()
        val lo = rgb.minOrNull() ?: 0f
        if (lo < 0f) {
            val factor = (y / (y - lo)).coerceIn(0f, 1f)
            rgb = FloatArray(3) { index -> y + (rgb[index] - y) * factor }
        }
        y = luma().coerceIn(0f, 1f)
        val hi = rgb.maxOrNull() ?: 0f
        if (hi > 1f) {
            val factor = ((1f - y) / (hi - y).coerceAtLeast(1e-8f)).coerceIn(0f, 1f)
            rgb = FloatArray(3) { index -> y + (rgb[index] - y) * factor }
        }
        return FloatArray(3) { index -> rgb[index].coerceIn(0f, 1f) }
    }
}
