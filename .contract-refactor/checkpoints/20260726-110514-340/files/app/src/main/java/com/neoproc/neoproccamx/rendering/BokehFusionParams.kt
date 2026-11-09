package com.neoproc.neoproccamx.rendering

/**
 * s5-f06 / CP-F7 — the JVM mirror of the bokeh_vogel_spiral.comp push-constant ABI (the depth-driven
 * Vogel-spiral computational-bokeh pass). [toPushConstants] packs the exact native `BokehPushConstants`
 * layout: three `int` slots (width, height, sampleCount) bit-reinterpreted into their float slots, then
 * six `float` slots (chromaticAberration, apertureBladeCount, apertureBladeStrength,
 * foregroundGuardStrength, focusDepth, blendStrength). Order/typing is locked field-for-field against
 * the GLSL `BokehUniforms` block by [BokehPushConstantLayoutTest], so a reordered slot or an int/float
 * drift on either side fails the build before it can corrupt the GPU pass ("ABI is sacred").
 *
 * No-downgrade contract: [blendStrength] == 0f is an EXACT passthrough (the shader emits the input
 * color unchanged), which is why [passthroughDefault] — the value the native dispatch uploads while the
 * `bokehBlendEnabled` gate is OFF — produces today's pixels bit-for-bit. The depth-driven bokeh look is
 * only visible once the owner raises blendStrength device-side (DEVICE-GATED).
 *
 * Computes no pixels; it only encodes the parameter contract the GPU pass must satisfy.
 */
internal data class BokehFusionParams(
    /** Output width — a GLSL `int` slot (packed as int-bits). */
    val width: Int,
    /** Output height — a GLSL `int` slot (packed as int-bits). */
    val height: Int,
    /** Vogel-disc sample count — a GLSL `int` slot; the shader clamps it to [8, 64]. */
    val sampleCount: Int,
    /** Per-channel radial chromatic-aberration offset (pixels). */
    val chromaticAberration: Float,
    /** Aperture blade count for the polygonal bokeh shape (>=3 to engage). */
    val apertureBladeCount: Float,
    /** Strength of the aperture-blade modulation (0 == round disc). */
    val apertureBladeStrength: Float,
    /** Foreground occlusion-guard strength (larger == stronger rejection of nearer samples). */
    val foregroundGuardStrength: Float,
    /** Focus-plane depth (normalized inverse depth, matching the depth image) the CoC is built around. */
    val focusDepth: Float,
    /** Blend of the bokeh result over the original input; 0 == exact passthrough (no-downgrade default). */
    val blendStrength: Float
) {
    /**
     * Push-constant layout for bokeh_vogel_spiral.comp's `BokehUniforms` block, mirroring the native
     * `BokehPushConstants` ABI: `int width; int height; int sampleCount;` then six floats. The three int
     * slots are bit-reinterpreted into their float slots (the GPU reads them back as `int`). Order is
     * locked by [BokehPushConstantLayoutTest].
     */
    fun toPushConstants(): FloatArray = floatArrayOf(
        Float.fromBits(width),
        Float.fromBits(height),
        Float.fromBits(sampleCount),
        chromaticAberration,
        apertureBladeCount,
        apertureBladeStrength,
        foregroundGuardStrength,
        focusDepth,
        blendStrength
    )

    companion object {
        /** Sample-count bounds the shader clamps to (`clamp(sampleCount, 8, 64)`). */
        const val MIN_SAMPLES = 8
        const val MAX_SAMPLES = 64

        /**
         * What the native bokeh dispatch uploads while the gate is OFF: blendStrength 0 == exact
         * passthrough. Never a claim of a visible bokeh render — that is device-gated (the s1-f06 rule).
         */
        fun passthroughDefault(width: Int, height: Int, focusDepth: Float = 0.5f): BokehFusionParams =
            BokehFusionParams(
                width = width,
                height = height,
                sampleCount = 16,
                chromaticAberration = 0f,
                apertureBladeCount = 6f,
                apertureBladeStrength = 0f,
                foregroundGuardStrength = 6f,
                focusDepth = focusDepth,
                blendStrength = 0f
            )

        /** Clamp a requested Vogel sample count into the shader's supported range. */
        fun clampSampleCount(requested: Int): Int = requested.coerceIn(MIN_SAMPLES, MAX_SAMPLES)
    }
}
