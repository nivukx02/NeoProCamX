package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CameraViewModelStabilizationParityContractTest {
    private val source: String by lazy {
        File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
    }

    @Test
    fun `ai subject tracking cannot apply a preview only stabilization transform`() {
        val initialization = source
            .substringAfter("init {")
            .substringBefore("// PREVIEW SURFACE WIRING")

        assertTrue(initialization.contains("mergedAIFocus"))
        assertFalse(initialization.contains("setStabilizationStrength("))
        assertFalse(initialization.contains("setStabilizationAnchor("))
    }

    @Test
    fun `stabilization mode keeps presentation neutral and delegates to camera2`() {
        val block = source
            .substringAfter("fun setStabilizationMode(mode: StabilizationMode) {")
            .substringBefore("val stabilizationMode: StabilizationMode")

        assertTrue(block.contains("camera.setStabilizationMode(mode)"))
        assertTrue(block.contains("previewViewRef?.setStabilizationStrength(0f)"))
        assertTrue(block.contains("previewViewRef?.setStabilizationAnchor(0.5f, 0.5f)"))
        assertEquals(1, Regex("setStabilizationStrength\\(").findAll(source).count())
        assertEquals(1, Regex("setStabilizationAnchor\\(").findAll(source).count())
    }
}
