package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.camera.CameraControllerConfig
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Source integration contracts; camera throughput is validated separately on the physical device. */
class EglImageResidencyContractTest {
    private val native = File("src/main/cpp/offscreen_egl_encoder_bridge.cpp").readText()
    private val policy = File("src/main/cpp/egl_image_residency_policy.h").readText()

    @Test
    fun `native cache bound tracks actual strict P010 reader instead of old PRIVATE depth`() {
        val reader = Regex("kStrictP010ReaderCeiling = (\\d+)").find(policy)!!
            .groupValues[1].toInt()
        val slack = Regex("kProducerAndPrewarmSlack = (\\d+)").find(policy)!!
            .groupValues[1].toInt()
        assertEquals(CameraControllerConfig.ReaderDepth.STRICT_MAX_HARDWARE_IMPORT_P010, reader)
        assertEquals(64, reader + slack)
        assertTrue(policy.contains("kStrictP010ReaderCeiling + kProducerAndPrewarmSlack"))
        assertTrue(native.contains("kEglImageCacheCapacity = camx::EglImageResidencyPolicy::kCapacity"))
    }

    @Test
    fun `prewarm live and pregraded imports share one bounded residency policy`() {
        assertEquals(2, Regex("EglImageResidencyPolicy::needsEviction").findAll(native).count())
        assertEquals(1, Regex("EglImageResidencyPolicy::canAdmitWithoutEviction").findAll(native).count())
        assertFalse(native.contains("eglImageCacheOrder.size() > 32"))
        assertFalse(native.contains("eglImageCache.size() < 32"))
    }

    @Test
    fun `import residency never adds a hardware buffer ownership reference or pixel copy`() {
        val entry = native.substringAfter("struct EglImageCacheEntry").substringBefore("};")
        assertTrue(entry.contains("EGLImageKHR image"))
        assertFalse(entry.contains("AHardwareBuffer"))
        assertFalse(policy.contains("AHardwareBuffer_acquire("))
        assertFalse(policy.contains("glTexImage"))
        assertFalse(policy.contains("memcpy("))
        assertTrue(native.contains("eglImageCacheOrder.clear()"))
        assertTrue(native.contains("drainAndClearInputReadFenceReceipts"))
    }

    @Test
    fun `residency measurements precede the long shader description`() {
        val report = native.substringAfter("<< \" submittedFrames=\" << state->submittedFrames")
        val shaderDescription = report.indexOf("<< \" niv99Mode=")
        for (field in listOf("Capacity", "Size", "Hits", "Misses", "Evictions")) {
            val position = report.indexOf("importResidency$field=")
            assertTrue("Missing early residency field $field", position in 0 until shaderDescription)
        }
    }
}
