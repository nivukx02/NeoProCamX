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

    @Test
    fun scaledAnalysisChromaSnapshotDoesNotCopyFullSourcePlanes() {
        val source = File(
            "src/main/java/com/neoproc/neoproccamx/ai/Yuv420ImageTools.kt"
        ).readText()
        val block = source
            .substringAfter("fun snapshotScaledChromaOnly(")
            .substringBefore("private fun snapshotP010ChromaOnly(")

        assertTrue(block.contains("targetChromaWidth"))
        assertTrue(block.contains("targetChromaHeight"))
        assertTrue(block.contains("openPlaneSlice("))
        assertFalse(block.contains("copyPlaneBytes("))
        assertFalse(block.contains("snapshotP010("))
    }

    @Test
    fun p010AnalysisPrewarmExecutesTheExactLiveTraversal() {
        val source = File(
            "src/main/java/com/neoproc/neoproccamx/ai/Yuv420ImageTools.kt"
        ).readText()
        val liveBlock = source
            .substringAfter("fun snapshotScaledChromaOnly(")
            .substringBefore("/** Shared verbatim traversal")
        val prewarmBlock = source
            .substringAfter("fun prewarmScaledP010ChromaKernel(")
            .substringBefore("private fun chromaScaleMap(")

        assertTrue(liveBlock.contains("copyScaledP010Chroma("))
        assertTrue(prewarmBlock.contains("ByteBuffer.allocateDirect("))
        assertTrue(prewarmBlock.contains("copyScaledP010Chroma("))
        assertTrue(prewarmBlock.contains("normalizeP010ToEightBit = true"))
    }
}
