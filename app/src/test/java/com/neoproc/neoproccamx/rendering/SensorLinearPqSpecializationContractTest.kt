package com.neoproc.neoproccamx.rendering

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorLinearPqSpecializationContractTest {
    private val source = File("src/main/cpp/offscreen_egl_encoder_bridge.cpp").readText()
    private val kotlinSource = File(
        "src/main/java/com/neoproc/neoproccamx/rendering/OffscreenAhbEglEncoderBridge.kt"
    ).readText()

    @Test
    fun `NDR10 and NIVUKX variants replace only runtime proven canonical declarations`() {
        val specialization = source
            .substringAfter("std::string makeSensorLinearFragment(")
            .substringBefore("void buildSensorLinearPrograms(")
        val build = source
            .substringAfter("void buildSensorLinearPrograms(")
            .substringBefore("// #2 decode-once")

        assertTrue(source.contains("uniform int uInputTransfer;"))
        assertTrue(source.contains("uniform float uHdrToPqConvert;"))
        assertTrue(source.contains("uniform float uNdrDeliveryMode;"))
        assertTrue(source.contains("uniform float uNdrSensorTonePqLutReady;"))
        assertTrue(source.contains("uniform float uLutStrength;"))
        assertTrue(specialization.contains("const int uInputTransfer = 3;"))
        assertTrue(specialization.contains("const float uHdrToPqConvert = 1.0;"))
        assertTrue(specialization.contains("const float uNdrDeliveryMode = 0.0;"))
        assertTrue(specialization.contains("const float uNdrDeliveryMode = 1.0;"))
        assertTrue(specialization.contains("const float uNdrTargetPeakNits = 5000.0;"))
        assertTrue(specialization.contains("const float uNdrTargetPeakNits = 10000.0;"))
        assertTrue(specialization.contains("const float uNdrSensorTonePqLutReady = 1.0;"))
        assertTrue(specialization.contains("const float uLutStrength = 0.0;"))
        assertTrue(build.contains("const float uHistoryPreGrade = 1.0;"))
        assertTrue(specialization.contains("source.find(dynamicToken, first + dynamicToken.size())"))
        assertTrue(specialization.contains("source.replace(first, dynamicToken.size(), constantToken)"))
        assertTrue(specialization.contains("ndrDeliveryMode != 0 && ndrDeliveryMode != 1"))

        assertTrue(build.contains("makeSensorLinearFragment(canonicalExternalFragment, 0)"))
        assertTrue(build.contains("makeSensorLinearFragment(canonicalExternalFragment, 1)"))
        assertTrue(build.contains("state->sensorLinearNivukxProgramExternal"))
        assertTrue(build.contains("state->sensorLinearNivukxProgram2d"))
        assertTrue(build.contains("outOfDomainAnalyticFallback=retained"))
        assertTrue(build.contains("canonicalGenericFallback=retained"))

        // One canonical shader remains the only equation authority.
        assertTrue(source.split("vec3 neoDeliverPqFromInput(").size - 1 == 1)
        assertTrue(source.split("vec3 neoApplyNdrMeasuredCorrection(").size - 1 == 1)
    }

    @Test
    fun `Kotlin and native mastering contracts are exact and mode specific`() {
        assertTrue(kotlinSource.contains("NDR10_MASTER_PEAK_NITS = 5_000f"))
        assertTrue(kotlinSource.contains("NIVUKX_MASTER_PEAK_NITS = 10_000f"))
        assertTrue(kotlinSource.contains("coerceIn(ndrBaselinePeak, ndrBaselinePeak)"))
        assertFalse(kotlinSource.contains("1_420f"))
        assertFalse(kotlinSource.contains("1_680f"))

        assertTrue(source.contains("kNdr10MasterPeakNits = 5000.0f"))
        assertTrue(source.contains("kNivukxMasterPeakNits = 10000.0f"))
        assertTrue(source.contains("NEO_SENSOR_PQ_NITS_DOMAIN = 10000.0"))
        assertTrue(source.contains("mode > 0.5 ? 10000.0 : 5000.0"))
        assertFalse(source.contains("clamp(peakNits, 203.0, 4000.0)"))
    }

    @Test
    fun `runtime selection requires exact strict sensor linear PQ evidence`() {
        val selection = source
            .substringAfter("const bool sensorLinearPqSpecializationEligible =")
            .substringBefore("// Retire any result")
        val twoPassChoice = source
            .substringAfter("const GLuint sensorLinearNdr10OrGenericProgram2d")
            .substringBefore("glUseProgram(activeMainProgram2d)")
        val directChoice = source
            .substringAfter("const GLuint sensorLinearNdr10OrGenericProgram =")
            .substringBefore("glUseProgram(activeMainProgram)")

        assertTrue(selection.contains("inputIsSensorLinear"))
        assertTrue(selection.contains("sourceIsFp16"))
        assertTrue(selection.contains("inputPrimaries == 3"))
        assertTrue(selection.contains("inputRange == 3"))
        assertTrue(selection.contains("applyNdrPqDeliveryToneMap == 1.0f"))
        assertTrue(selection.contains("sensorNdrTonePqLutReadyForFrame"))
        assertTrue(selection.contains("ndrDeliveryMode == 0 || ndrDeliveryMode == 1"))
        assertTrue(selection.contains("ndrDeliveryMode == 0"))
        assertTrue(selection.contains("ndrDeliveryMode == 1"))
        assertTrue(selection.contains("requestedTemporalBlend > 0.0f"))
        assertFalse(selection.contains("inputIsNeoSceneLog10"))

        assertTrue(twoPassChoice.contains(": state->mainProgram2d"))
        assertTrue(twoPassChoice.contains("state->sensorLinearNivukxProgram2d"))
        assertTrue(directChoice.contains(": (useExplicitYuv ? state->mainProgramExplicitYuv : state->program)"))
        assertTrue(directChoice.contains("state->sensorLinearNivukxProgramExternal"))
    }

    @Test
    fun `specialized lifecycle and per profile telemetry are complete`() {
        val teardown = source
            .substringAfter("bool destroyState(OffscreenAhbEglEncoderState* state)")
            .substringBefore("extern \"C\" JNIEXPORT jlong JNICALL")
        assertTrue(teardown.contains("glDeleteProgram(state->sensorLinearProgramExternal)"))
        assertTrue(teardown.contains("glDeleteProgram(state->sensorLinearProgram2d)"))
        assertTrue(teardown.contains("glDeleteProgram(state->sensorLinearNivukxProgramExternal)"))
        assertTrue(teardown.contains("glDeleteProgram(state->sensorLinearNivukxProgram2d)"))

        assertTrue(source.contains("sensorLinearNdr10SpecializedFrames="))
        assertTrue(source.contains("sensorLinearNivukxSpecializedFrames="))
        assertTrue(source.contains("sensorLinearSpecializationFallbackFrames="))
        assertTrue(source.contains("specializedProfile=%s"))
    }

    @Test
    fun `specialization retains full quality and both analytic fallback layers`() {
        assertTrue(source.contains("vec3 cardinal0 = neoSampleCameraEast"))
        assertTrue(source.contains("vec3 cardinal1 = neoSampleCameraWest"))
        assertTrue(source.contains("vec3 cardinal2 = neoSampleCameraNorth"))
        assertTrue(source.contains("vec3 cardinal3 = neoSampleCameraSouth"))
        assertTrue(source.contains("Motion-guarded temporal denoise"))
        assertTrue(source.contains("uniform highp sampler2D uNdrSensorTonePqLut"))
        assertTrue(source.contains("NEO_SENSOR_TONE_PQ_LUT_SIZE = 2048.0"))
        assertTrue(source.contains("NEO_SENSOR_TONE_PQ_LUT_ROWS = 17.0"))
        assertTrue(source.contains("NEO_SENSOR_TONE_LUT_MIN_SIGNAL = 0.000001"))
        assertTrue(source.contains("uNdrSensorTonePqLutReady > 0.5"))
        assertTrue(source.contains("sourceY >= NEO_SENSOR_TONE_LUT_MIN_SIGNAL"))
        assertTrue(source.contains("sourceY <= NEO_SENSOR_TONE_DOMAIN"))
        assertTrue(source.contains("bool sensorColorPathClipped = useSensorTonePqLut"))
        assertTrue(source.contains("any(lessThan(t, vec3(0.0)))"))
        assertTrue(source.contains("bool useCachedSensorNits = useSensorTonePqLut && !sensorColorPathClipped"))
        assertTrue(source.contains("float nits = useCachedSensorNits"))
        assertTrue(source.contains("const float uNdrSensorTonePqLutReady = 1.0;"))
        assertTrue(source.contains("sensorNdrTonePqLutReadyForFrame &&"))
        assertTrue(source.contains(": state->mainProgram2d"))
        assertTrue(source.contains(": (useExplicitYuv ? state->mainProgramExplicitYuv : state->program)"))
    }
}
