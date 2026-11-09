package com.neoproc.neoproccamx.rendering

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OffscreenTemporalHistoryResetContractTest {

    private fun source(pathFromApp: String): String {
        val candidates = listOf(File(pathFromApp), File("app/$pathFromApp"))
        return candidates.first { it.isFile }.readText()
    }

    @Test
    fun `epoch history reset is synchronized and non destructive`() {
        val kotlinSource = source(
            "src/main/java/com/neoproc/neoproccamx/rendering/OffscreenAhbEglEncoderBridge.kt"
        )
        val nativeSource = source("src/main/cpp/offscreen_egl_encoder_bridge.cpp")
        val kotlinReset = kotlinSource
            .substringAfter("internal fun resetTemporalHistory(): Boolean")
            .substringBefore("fun setNdrNivukxMeasuredCorrection(")
        val nativeReset = nativeSource
            .substringAfter("OffscreenAhbEglEncoderBridge_nativeResetTemporalHistory(")
            .substringBefore("OffscreenAhbEglEncoderBridge_nativeSetEncoderSurface(")

        assertTrue(kotlinSource.contains("private external fun nativeResetTemporalHistory(handle: Long): Boolean"))
        assertTrue(kotlinReset.contains("if (handle == 0L) return false"))
        assertTrue(kotlinReset.contains("return nativeResetTemporalHistory(handle)"))

        assertTrue(nativeReset.contains("std::lock_guard<std::mutex> lock(state->mutex)"))
        assertTrue(nativeReset.contains("state->historyValid = false"))
        assertTrue(nativeReset.contains("state->decodeHistoryValid = false"))
        assertTrue(nativeReset.contains("return JNI_TRUE"))

        assertFalse(nativeReset.contains("destroySurface("))
        assertFalse(nativeReset.contains("glDeleteTextures"))
        assertFalse(nativeReset.contains("state->encoderSurface = EGL_NO_SURFACE"))
        assertFalse(nativeReset.contains("state->recordGrade"))
        assertFalse(nativeReset.contains("state->ndrMeasured"))
    }

    @Test
    fun `sensor linear preview resets history before reusing persistent surface`() {
        val runtimeSource = source(
            "src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt"
        )
        val prepare = runtimeSource
            .substringAfter("fun prepareSensorLinearNdrPreviewSurface(")
            .substringBefore("fun submitLatestSensorLinearNdrPreviewFrame(")

        val resetIndex = prepare.indexOf("sensorLinearNdrPreviewBridge.resetTemporalHistory()")
        val reuseIndex = prepare.indexOf("boundSensorLinearNdrPreviewSurface === surface")
        assertTrue("epoch reset must be wired into the sensor-linear preview domain", resetIndex >= 0)
        assertTrue("history must reset before the persistent-surface early return", resetIndex < reuseIndex)
        assertTrue("a failed reset must retain the outgoing visible owner", prepare.contains("return false"))
    }
}
