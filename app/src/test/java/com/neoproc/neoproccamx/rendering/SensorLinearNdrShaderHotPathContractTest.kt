package com.neoproc.neoproccamx.rendering

import java.io.File
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorLinearNdrShaderHotPathContractTest {
    private val source = File("src/main/cpp/offscreen_egl_encoder_bridge.cpp").readText()

    @Test
    fun `full resolution sensor path preserves five taps and every quality stage`() {
        val spatial = source
            .substringAfter("// Four explicit cardinal lanes")
            .substringBefore("// Display-referred colour-science look")

        assertTrue(spatial.contains("vec3 cardinal0 = neoSampleCameraEast"))
        assertTrue(spatial.contains("vec3 cardinal1 = neoSampleCameraWest"))
        assertTrue(spatial.contains("vec3 cardinal2 = neoSampleCameraNorth"))
        assertTrue(spatial.contains("vec3 cardinal3 = neoSampleCameraSouth"))
        assertTrue(spatial.contains("vec3 accBil = c"))
        assertTrue(spatial.contains("vec4 bilateralLumaDeltaMetric"))
        assertTrue(spatial.contains("const float gaussianNeighbourWeight = 1.11"))
        assertTrue(spatial.contains("vec3 denoised = mix(c, bilateral, dn)"))
        assertTrue(spatial.contains("vec3 sharpened = denoised + boost"))
        assertTrue(spatial.contains("vec3 spatiallyDenoisedChroma = mix("))

        assertTrue(source.contains("Motion-guarded temporal denoise"))
        assertTrue(source.contains("uniform highp sampler2D uNdrSensorTonePqLut"))
        assertTrue(source.contains("NEO_SENSOR_TONE_PQ_LUT_SIZE = 2048.0"))
        assertTrue(source.contains("NEO_SENSOR_TONE_PQ_LUT_ROWS = 17.0"))
        assertTrue(source.contains("GL_RGBA16F"))
    }

    @Test
    fun `sampler2d uses exact integer cardinal offsets with defensive generic fallback`() {
        val build = source
            .substringAfter("void buildSensorLinearPrograms(")
            .substringBefore("// #2 decode-once")
        val selection = source
            .substringAfter("const int sensorLinear2dSampleWidth")
            .substringBefore("// Retire any result")

        assertTrue(build.contains("textureOffset(uTexture, uv, ivec2(1, 0))"))
        assertTrue(build.contains("textureOffset(uTexture, uv, ivec2(-1, 0))"))
        assertTrue(build.contains("textureOffset(uTexture, uv, ivec2(0, 1))"))
        assertTrue(build.contains("textureOffset(uTexture, uv, ivec2(0, -1))"))
        assertTrue(build.contains("sampler2DCardinals=INTEGER_TEXTURE_OFFSET"))
        assertTrue(selection.contains("sensorLinear2dIntegerOffsetsExact"))
        assertTrue(selection.contains("sensorLinear2dSampleWidth < 10000"))
        assertTrue(selection.contains("sensorLinear2dSampleHeight < 10000"))
        assertTrue(source.contains("? state->sensorLinearProgram2d\n            : state->mainProgram2d"))
    }

    @Test
    fun `bounded detail hot path has sub code value parity and no transcendental call`() {
        val detail = source
            .substringAfter("if (ndrSensorLinearDetail) {")
            .substringBefore("// Motion-guarded temporal denoise")
        assertTrue(detail.contains("neoBoundedDetailLog2("))
        assertTrue(detail.contains("neoBoundedDetailExp2(detailGainStops)"))
        assertFalse(detail.contains("= log2("))
        assertFalse(detail.contains("*= exp2("))

        var maxLogError = 0.0
        var maxExpError = 0.0
        val count = 200_000
        for (index in 0..count) {
            val ratio = (2.0.pow(-0.22 + 0.44 * index.toDouble() / count)).toFloat()
            val expectedLog = (ln(ratio.toDouble()) / ln(2.0)).toFloat()
                .coerceIn(-0.22f, 0.22f)
            maxLogError = maxOf(
                maxLogError,
                abs(boundedDetailLog2(ratio) - expectedLog).toDouble()
            )

            val stops = (-0.20 + 0.40 * index.toDouble() / count).toFloat()
            val expectedExp = 2.0.pow(stops.toDouble()).toFloat()
            maxExpError = maxOf(
                maxExpError,
                abs(boundedDetailExp2(stops) - expectedExp).toDouble()
            )
        }
        assertTrue("log error=$maxLogError", maxLogError < 1.0e-6)
        assertTrue("exp error=$maxExpError", maxExpError < 1.0e-6)
    }

    @Test
    fun `monotonic length rewrite removes one sqrt without changing value`() {
        val calibration = source
            .substringAfter("vec3 northSouthChromaDelta")
            .substringBefore("float calibrationY")
        assertTrue(calibration.contains("float chromaStructure = sqrt(max("))
        assertFalse(calibration.contains("max(\n        length(northChroma"))
    }

    @Test
    fun `shared protected green denominators use one reciprocal`() {
        val profileChroma = source
            .substringAfter("vec3 neoDelProfileChroma(")
            .substringBefore("vec3 neoDelMapRgbHuePreserving(")
        val activeDelivery = source
            .substringAfter("// Keep the proprietary warm/red reconstruction byte-for-byte")
            .substringBefore("t = max(t, vec3(0.0))")
        assertTrue(profileChroma.contains("float inverseSourceGreen = 1.0 / max(source.g, 1e-8)"))
        assertTrue(profileChroma.contains("source.r * inverseSourceGreen"))
        assertTrue(profileChroma.contains("source.b * inverseSourceGreen"))
        assertTrue(activeDelivery.contains("float inverseLinearGreen = 1.0 / max(lin.g, 1e-8)"))
        assertTrue(activeDelivery.contains("lin.r * inverseLinearGreen"))
        assertTrue(activeDelivery.contains("lin.b * inverseLinearGreen"))
    }

    private fun boundedDetailLog2(ratio: Float): Float {
        val x = ratio.coerceIn(0.858565436437754f, 1.16473358646846f) - 1.0f
        var polynomial = -1.0f / 6.0f
        polynomial = 1.0f / 5.0f + x * polynomial
        polynomial = -1.0f / 4.0f + x * polynomial
        polynomial = 1.0f / 3.0f + x * polynomial
        polynomial = -1.0f / 2.0f + x * polynomial
        polynomial = 1.0f + x * polynomial
        return 1.4426950408889634f * x * polynomial
    }

    private fun boundedDetailExp2(stops: Float): Float {
        val z = 0.6931471805599453f * stops.coerceIn(-0.20f, 0.20f)
        return 1.0f + z * (
            1.0f + z * (0.5f + z * (1.0f / 6.0f + z * (1.0f / 24.0f)))
        )
    }
}
