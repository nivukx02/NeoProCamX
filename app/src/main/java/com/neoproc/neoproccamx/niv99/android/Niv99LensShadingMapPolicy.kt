package com.neoproc.neoproccamx.niv99.android

/**
 * Allocation-free validation for Camera2 lens-shading payloads.
 *
 * Camera2 defines the copied gain layout as row-major pixels with four consecutive colour
 * channels (R, G-even, G-odd, B). Keeping the size and value checks in one policy lets the live
 * adapter use [android.hardware.camera2.params.LensShadingMap.copyGainFactors] once per result,
 * instead of crossing the Camera2 API boundary once for every gain value.
 */
internal object Niv99LensShadingMapPolicy {
    const val CHANNEL_COUNT: Int = 4

    fun requiredGainCount(columns: Int, rows: Int, capacity: Int): Int {
        if (columns <= 0 || rows <= 0 || capacity <= 0) return INVALID_COUNT
        val required = columns.toLong() * rows.toLong() * CHANNEL_COUNT.toLong()
        return if (required <= 0L || required > capacity.toLong() || required > Int.MAX_VALUE) {
            INVALID_COUNT
        } else {
            required.toInt()
        }
    }

    fun containsOnlyPhysicalGains(gains: FloatArray, count: Int): Boolean {
        if (count <= 0 || count > gains.size) return false
        for (index in 0 until count) {
            val gain = gains[index]
            if (!gain.isFinite() || gain <= 0f) return false
        }
        return true
    }

    const val INVALID_COUNT: Int = -1
}
