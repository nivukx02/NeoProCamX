package com.neoproc.neoproccamx.rendering

internal data class VulkanColorScienceUniforms(
    val acesCgToOklabReference: FloatArray,
    val oklabReferenceToAcesCg: FloatArray,
    val hdrParams: FloatArray,
    val modeParams: FloatArray,
    val measuredCorrectionParams: FloatArray,
    val identityParams: FloatArray,
    val measuredRgbScaleParams: FloatArray
) {
    init {
        require(acesCgToOklabReference.size == 16) { "ACEScg reference transform must be mat4" }
        require(oklabReferenceToAcesCg.size == 16) { "Oklab reference transform must be mat4" }
        require(hdrParams.size == 4) { "HDR params must contain 4 floats" }
        require(modeParams.size == 4) { "mode params must contain 4 floats" }
        require(measuredCorrectionParams.size == 4) { "measured correction params must contain 4 floats" }
        require(identityParams.size == 4) { "identity params must contain 4 floats" }
        require(measuredRgbScaleParams.size == 4) { "measured RGB scale params must contain 4 floats" }
        require(pack().all { it.isFinite() }) { "Neo color-science uniforms contain non-finite values" }
    }

    fun pack(): FloatArray =
        acesCgToOklabReference +
            oklabReferenceToAcesCg +
            hdrParams +
            modeParams +
            measuredCorrectionParams +
            identityParams +
            measuredRgbScaleParams

    companion object {
        const val FLOAT_COUNT = 52
        private val IDENTITY_4 = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )

        fun disabled(): VulkanColorScienceUniforms =
            VulkanColorScienceUniforms(
                acesCgToOklabReference = IDENTITY_4.copyOf(),
                oklabReferenceToAcesCg = IDENTITY_4.copyOf(),
                hdrParams = floatArrayOf(0f, 0.59f, 3.72f, 0.54f),
                modeParams = floatArrayOf(0f, 0f, 0f, 0f),
                measuredCorrectionParams = floatArrayOf(0f, 0f, 0f, 0f),
                identityParams = floatArrayOf(
                    GpuColorSpace.REC709.id.toFloat(),
                    GpuColorScience.NATURAL.id.toFloat(),
                    GpuActiveTransport.REC709_SDR.id.toFloat(),
                    0f
                ),
                measuredRgbScaleParams = floatArrayOf(1f, 1f, 1f, 0f)
            )
    }
}
