package com.neoproc.neoproccamx.rendering

/** Host-testable mirror of the Python-calibrated NDR SDR-monitor adaptation. */
internal object NdrPqPreviewMonitorTonePolicy {
    const val DIFFUSE_WHITE_NITS = 203f
    /**
     * Project NDR-NIVUKX-PY-CAL-v4 SDR presentation anchor (sRGB 0.80), shared with the CPU fallback and both
     * live GPU monitor paths. This is deliberately presentation-only: the FP16 ACEScg scene master,
     * the canonical NDR tone signal, and the PQ recording master retain their full precision/range.
     */
    const val MONITOR_DIFFUSE_LINEAR = 0.60382734f

    private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun mapLuminance(toneSignal: Float, @Suppress("UNUSED_PARAMETER") masterPeakNits: Float): Float {
        if (!toneSignal.isFinite() || toneSignal <= 0f) return 0f
        val signal = toneSignal.coerceAtLeast(0f)
        if (signal <= 1f) return signal * MONITOR_DIFFUSE_LINEAR
        val headroom = 1f - MONITOR_DIFFUSE_LINEAR
        val shoulderLength = headroom / MONITOR_DIFFUSE_LINEAR
        val excess = signal - 1f
        return (
            MONITOR_DIFFUSE_LINEAR + headroom * excess / (excess + shoulderLength)
            ).coerceIn(0f, 1f)
    }

    /**
     * The compositor receives the canonical unbounded tone signal. SDR adaptation is presentation-only;
     * recording independently maps the same signal to absolute display nits and PQ.
     */
    fun mapToneSignal(toneSignal: Float, masterPeakNits: Float): Float {
        return mapLuminance(toneSignal, masterPeakNits)
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
        val upperCeiling = 0.985f + (0.965f - 0.985f) * smoothstep(0.65f, 0.98f, y)
        val targetHi = maxOf(y, upperCeiling)
        if (hi > targetHi) {
            val factor = ((targetHi - y) / (hi - y).coerceAtLeast(1e-8f)).coerceIn(0f, 1f)
            rgb = FloatArray(3) { index -> y + (rgb[index] - y) * factor }
        }
        return FloatArray(3) { index -> rgb[index].coerceIn(0f, 1f) }
    }
}
