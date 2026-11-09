package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CaptureRequest

/**
 * Source-quality request for the uncompressed NDR P010 master.
 *
 * Heavy ISP noise reduction permanently removes texture before the offline converter can inspect it.
 * Keep high-quality demosaic/edge reconstruction and hot-pixel correction, but request the least
 * destructive advertised noise-reduction mode. The offline converter then performs measured,
 * edge-aware denoise with access to adjacent frames.
 */
internal object NdrMasterCaptureQualityPolicy {
    data class Plan(
        val edgeMode: Int,
        val noiseReductionMode: Int,
        val hotPixelMode: Int
    )

    fun resolve(active: Boolean, availability: IspBypassAvailability?): Plan? {
        if (!active) return null
        val edge = choose(
            availability?.edgeModes,
            CaptureRequest.EDGE_MODE_HIGH_QUALITY,
            CaptureRequest.EDGE_MODE_FAST,
            CaptureRequest.EDGE_MODE_OFF
        ) ?: return null
        val noise = choose(
            availability?.noiseReductionModes,
            CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL,
            CaptureRequest.NOISE_REDUCTION_MODE_FAST,
            CaptureRequest.NOISE_REDUCTION_MODE_OFF,
            CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
        ) ?: return null
        val hotPixel = choose(
            availability?.hotPixelModes,
            CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY,
            CaptureRequest.HOT_PIXEL_MODE_FAST,
            CaptureRequest.HOT_PIXEL_MODE_OFF
        ) ?: return null
        return Plan(edge, noise, hotPixel)
    }

    private fun choose(availableModes: List<Int>?, vararg preference: Int): Int? {
        if (availableModes.isNullOrEmpty()) return preference.firstOrNull()
        return preference.firstOrNull { it in availableModes }
    }
}
