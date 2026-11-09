package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SensorLinearNdrPreviewDetachContractTest {
    @Test
    fun `preview surface state clears only after native detach acknowledgement`() {
        val source = File(
            "src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt"
        ).readText()
        val release = source
            .substringAfter("fun releaseSensorLinearNdrPreviewSurface(): Boolean")
            .substringBefore("private fun syncRawSceneLogTransportState()")
        val nativeCall = release.indexOf("sensorLinearNdrPreviewBridge.setEncoderSurface(null, 0, 0)")
        val guard = release.indexOf("if (!nativeDetached)")
        val surfaceClear = release.indexOf("boundSensorLinearNdrPreviewSurface = null")
        val activeClear = release.indexOf("sensorLinearNdrPreviewActive = false")

        assertTrue(nativeCall >= 0)
        assertTrue(guard > nativeCall)
        assertTrue(surfaceClear > guard)
        assertTrue(activeClear > guard)
        assertTrue(release.substringAfter("if (!nativeDetached)").contains("return@synchronized false"))
        assertFalse(release.substring(0, guard).contains("boundSensorLinearNdrPreviewSurface = null"))
    }
}
