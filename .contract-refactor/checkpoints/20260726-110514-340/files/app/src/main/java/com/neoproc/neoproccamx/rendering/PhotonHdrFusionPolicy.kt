package com.neoproc.neoproccamx.rendering

/**
 * OmniClarity Engine — §1 Photon HDR Fusion Core: capability gating + radiance-ratio parameterization.
 *
 * Decides whether real-acquisition HDR fusion runs and with what parameters. Photon needs hardware
 * that can deliver fast alternating exposures or a dual-conversion-gain readout in video mode; when
 * neither is available it falls back to single-exposure capture (no fusion) per the design's TEIV
 * ladder. The fusion math itself lives in `photon_hdr_fusion.comp`; this object computes no pixels.
 */
internal enum class PhotonFusionMode {
    DUAL_EXPOSURE,
    DUAL_GAIN,
    SINGLE_FALLBACK
}

internal data class PhotonFusionParams(
    val highlightThreshold: Float,
    val clipThreshold: Float,
    val longScale: Float,
    val shortScale: Float,
    val motionCompEnabled: Boolean,
    val maxFlow: Float
) {
    /** Push-constant layout for photon_hdr_fusion.comp's PhotonFusionParams block. */
    fun toPushConstants(): FloatArray = floatArrayOf(
        highlightThreshold,
        clipThreshold,
        longScale,
        shortScale,
        if (motionCompEnabled) 1f else 0f,
        maxFlow
    )
}

internal data class PhotonFusionPlan(
    val mode: PhotonFusionMode,
    val active: Boolean,
    val params: PhotonFusionParams,
    val reason: String
)

internal object PhotonHdrFusionPolicy {
    const val DEFAULT_REQUESTED: Boolean = true
    private const val HIGHLIGHT_THRESHOLD = 0.85f // normalized long-radiance luma where short takes over
    private const val CLIP_THRESHOLD = 0.98f      // normalized luma at which the long exposure clips
    private const val MAX_EXPOSURE_RATIO = 64f

    fun resolve(
        dualExposureSupported: Boolean,
        dualGainSupported: Boolean,
        longExposureNs: Long,
        shortExposureNs: Long,
        motionFieldAvailable: Boolean,
        maxFlow: Float,
        requested: Boolean = DEFAULT_REQUESTED
    ): PhotonFusionPlan {
        if (!requested) {
            return PhotonFusionPlan(
                mode = PhotonFusionMode.SINGLE_FALLBACK,
                active = false,
                params = PhotonFusionParams(HIGHLIGHT_THRESHOLD, CLIP_THRESHOLD, 1f, 1f, false, maxFlow),
                reason = "PHOTON_HDR_DISABLED"
            )
        }

        val mode = when {
            dualExposureSupported -> PhotonFusionMode.DUAL_EXPOSURE
            dualGainSupported -> PhotonFusionMode.DUAL_GAIN
            else -> PhotonFusionMode.SINGLE_FALLBACK
        }

        if (mode == PhotonFusionMode.SINGLE_FALLBACK) {
            return PhotonFusionPlan(
                mode = mode,
                active = false,
                params = PhotonFusionParams(HIGHLIGHT_THRESHOLD, CLIP_THRESHOLD, 1f, 1f, false, maxFlow),
                reason = "SINGLE_EXPOSURE_FALLBACK"
            )
        }

        val ratio = exposureRatio(longExposureNs, shortExposureNs)
        return PhotonFusionPlan(
            mode = mode,
            active = true,
            params = PhotonFusionParams(
                highlightThreshold = HIGHLIGHT_THRESHOLD,
                clipThreshold = CLIP_THRESHOLD,
                longScale = 1f,
                shortScale = ratio,
                motionCompEnabled = motionFieldAvailable,
                maxFlow = maxFlow
            ),
            reason = if (motionFieldAvailable) "MOTION_COMPENSATED_FUSION" else "STATIC_FUSION_NO_FLOW"
        )
    }

    /** Long-to-short exposure (or gain) ratio that scales the short exposure up to common radiance. */
    private fun exposureRatio(longNs: Long, shortNs: Long): Float {
        if (longNs <= 0L || shortNs <= 0L) return 1f
        return (longNs.toFloat() / shortNs.toFloat()).coerceIn(1f, MAX_EXPOSURE_RATIO)
    }
}
