package com.neoproc.neoproccamx.snce

/**
 * SNCE v2.0 HVC — Stage 04 (OCCS): opponent colour channel simulation.
 *
 * Converts working RGB to the IPT opponent representation (I = achromatic,
 * P = red-green, T = blue-yellow; directive 04.1, transform in [SnceColorPrimitives])
 * and applies retinal lateral inhibition as a Difference-of-Gaussians on the chromatic
 * channels (directive 04.2 / Part II 2.2.2). The CPU path here is the reference for the
 * GLSL twin (occs_opponent.comp) and is used by the orchestrator on a downsampled proxy.
 *
 * Faithful to the directive's unbalanced DoG (k_weight = 0.78): chromatic edges are
 * sharpened strongly while the achromatic channel gets only a mild boost, exactly as the
 * directive's shader specifies. Mesopic rod injection lifts the I channel in very low light.
 */
object SnceOpponentChannels {

    class DoGParams(
        val sigmaCenter: Float = 1.2f,
        val sigmaSurround: Float = 3.6f,
        val kWeight: Float = 0.78f,
        val iGain: Float = 0.15f,
        val chromaGain: Float = 0.85f
    )

    /** Convert an interleaved working-RGB thumbnail to an interleaved IPT field. */
    fun toOpponentField(
        thumb: SnceThumbnail,
        rgbToXyz: FloatArray = SnceIlluminantEstimator.REC709_TO_XYZ
    ): FloatArray {
        val n = thumb.pixelCount()
        val out = FloatArray(n * 3)
        for (i in 0 until n) {
            val rgb = floatArrayOf(thumb.rgb[i * 3], thumb.rgb[i * 3 + 1], thumb.rgb[i * 3 + 2])
            val ipt = SnceColorPrimitives.xyzToIpt(SnceColorPrimitives.mulV(rgbToXyz, rgb))
            out[i * 3] = ipt[0]; out[i * 3 + 1] = ipt[1]; out[i * 3 + 2] = ipt[2]
        }
        return out
    }

    /** Lateral inhibition (DoG) on an interleaved IPT field. */
    fun lateralInhibition(
        ipt: FloatArray,
        width: Int,
        height: Int,
        params: DoGParams = DoGParams()
    ): FloatArray {
        val n = width * height
        val planeI = FloatArray(n) { ipt[it * 3] }
        val planeP = FloatArray(n) { ipt[it * 3 + 1] }
        val planeT = FloatArray(n) { ipt[it * 3 + 2] }

        val out = FloatArray(n * 3)
        applyDoG(planeI, width, height, params, params.iGain, out, 0)
        applyDoG(planeP, width, height, params, params.chromaGain, out, 1)
        applyDoG(planeT, width, height, params, params.chromaGain, out, 2)
        return out
    }

    private fun applyDoG(
        plane: FloatArray,
        width: Int,
        height: Int,
        params: DoGParams,
        gain: Float,
        out: FloatArray,
        channel: Int
    ) {
        val center = SnceV4ColorConstancy.gaussianBlur(plane, width, height, params.sigmaCenter)
        val surround = SnceV4ColorConstancy.gaussianBlur(plane, width, height, params.sigmaSurround)
        for (i in plane.indices) {
            val dog = center[i] - params.kWeight * surround[i]
            out[i * 3 + channel] = plane[i] + gain * dog
        }
    }

    /**
     * Mesopic rod injection (directive 04.3): below ~3 cd/m^2 the rod (scotopic) luminance
     * is mixed into the I channel. [scotopicLuma] is a per-pixel scotopic estimate (e.g. a
     * blue-weighted luma); when null a luma-derived proxy is used.
     */
    fun rodInject(
        ipt: FloatArray,
        sceneLuminanceCdm2: Float,
        scotopicLuma: FloatArray? = null
    ): FloatArray {
        if (sceneLuminanceCdm2 >= 3.0f) return ipt
        val wRod = mesopicWeight(sceneLuminanceCdm2)
        val out = ipt.copyOf()
        val n = ipt.size / 3
        for (i in 0 until n) {
            val rod = scotopicLuma?.get(i) ?: ipt[i * 3]
            out[i * 3] = ipt[i * 3] * (1f - wRod) + rod * wRod
        }
        return out
    }

    /** Smooth scotopic blend weight: 0 at 3 cd/m^2, rising toward 1 in deep scotopic light. */
    fun mesopicWeight(sceneLuminanceCdm2: Float): Float {
        val lo = 0.001f
        val hi = 3.0f
        val t = ((hi - sceneLuminanceCdm2) / (hi - lo)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t) // smoothstep
    }

    /**
     * s2-f07 / CP46 — mesopic chroma-gain scale for OCCS. Rod-dominated (dim) vision has poor chroma
     * acuity, so the chromatic lateral-inhibition gain must DROP in dim scenes to avoid amplifying
     * chroma noise. Returns a scale in [floor, 1] that rises monotonically with scene luminance:
     * 1.0 (full gain = today) at/above photopic, falling toward [floor] in deep scotopic. A consumer
     * that passes a photopic luminance gets 1.0 ⇒ today's behavior is unchanged.
     */
    fun mesopicChromaGainScale(sceneLuminanceCdm2: Float, floor: Float = 0.25f): Float {
        val photopic = 10f
        val t = (sceneLuminanceCdm2.coerceAtLeast(0f) / photopic).coerceIn(0f, 1f)
        val smooth = t * t * (3f - 2f * t)
        return (floor + (1f - floor) * smooth).coerceIn(floor, 1f)
    }
}
