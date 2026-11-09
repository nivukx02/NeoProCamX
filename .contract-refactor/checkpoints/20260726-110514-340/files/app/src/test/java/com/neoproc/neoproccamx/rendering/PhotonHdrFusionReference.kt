package com.neoproc.neoproccamx.rendering

/**
 * CP32 / s1-f10 — host-side faithful reference of photon_hdr_fusion.comp's radiance blend (NeoProCamX's
 * own motion-compensated dual-exposure fusion). Ports the deterministic static-blend path line-for-line
 * (motionCompEnabled = 0): both exposures are scaled to common scene radiance and blended by a
 * confidence ramp on the long exposure's luma, so highlights come from the (unclipped) short exposure
 * and the cleaner long exposure dominates elsewhere. The motion-compensated warp (Argus flow) is the
 * device-gated part and is not modelled here. Keep in lockstep with photon_hdr_fusion.comp.
 */
object PhotonHdrFusionReference {

    class Params(
        val highlightThreshold: Float,
        val clipThreshold: Float,
        val longScale: Float,
        val shortScale: Float
    )

    private fun lumaOf(c: FloatArray): Float = c[0] * 0.2126f + c[1] * 0.7152f + c[2] * 0.0722f

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** Confidence that the long exposure is clipping at this pixel (→ 1 means hand over to the short read). */
    fun longClipConfidence(longRgb: FloatArray, p: Params): Float {
        val longScaled = floatArrayOf(longRgb[0] * p.longScale, longRgb[1] * p.longScale, longRgb[2] * p.longScale)
        return smoothstep(p.highlightThreshold, p.clipThreshold, lumaOf(longScaled))
    }

    /** Static radiance blend: fused = mix(long_scaled, short_scaled, confidence_long). */
    fun fuseStatic(longRgb: FloatArray, shortRgb: FloatArray, p: Params): FloatArray {
        val c = longClipConfidence(longRgb, p)
        val longScaled = floatArrayOf(longRgb[0] * p.longScale, longRgb[1] * p.longScale, longRgb[2] * p.longScale)
        val shortScaled = floatArrayOf(shortRgb[0] * p.shortScale, shortRgb[1] * p.shortScale, shortRgb[2] * p.shortScale)
        return floatArrayOf(
            longScaled[0] * (1f - c) + shortScaled[0] * c,
            longScaled[1] * (1f - c) + shortScaled[1] * c,
            longScaled[2] * (1f - c) + shortScaled[2] * c
        )
    }
}
