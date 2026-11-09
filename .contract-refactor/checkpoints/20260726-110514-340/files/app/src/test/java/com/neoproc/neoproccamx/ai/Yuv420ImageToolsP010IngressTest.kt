package com.neoproc.neoproccamx.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Yuv420ImageToolsP010IngressTest {
    @Test
    fun p010ArgbIngressSamplesOnlyTheBoundedTargetWithoutFullFrameSnapshot() {
        val source = File(
            "src/main/java/com/neoproc/neoproccamx/ai/Yuv420ImageTools.kt"
        ).readText()
        val fillBlock = source
            .substringAfter("fun fillArgbPixels(")
            .substringBefore("fun fillModelInput(")

        assertTrue(fillBlock.contains("fillArgbPixelsP010("))
        assertFalse(fillBlock.contains("snapshotP010(image)"))
    }

    @Test
    fun p010DirectSamplerReadsOnlyRequestedScaledPixels() {
        val source = File(
            "src/main/java/com/neoproc/neoproccamx/ai/Yuv420ImageTools.kt"
        ).readText()
        val p010Block = source
            .substringAfter("private fun fillArgbPixelsP010(")
            .substringBefore("fun fillModelInput(")

        assertTrue(p010Block.contains("fillArgbPixels("))
        assertTrue(p010Block.contains("readP010SampleAs8("))
        assertFalse(p010Block.contains("ByteArray("))
        assertFalse(p010Block.contains("copyP010Plane("))
    }
}
