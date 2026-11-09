package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FramePipelineSchedulingContractTest {
    private val source = File(
        "src/main/java/com/neoproc/neoproccamx/pipeline/FramePipelineManager.kt"
    ).readText()

    @Test
    fun `ai ingress cannot retain the scene processing gate`() {
        val processBlock = source
            .substringAfter("private fun processAcquiredImage(")
            .substringBefore("fun enqueueHardware(")
        val aiDispatch = processBlock.indexOf("aiIngressExecutor.execute")
        val sceneGateRelease = processBlock.indexOf("processing.set(false)")

        assertTrue(source.contains("Executors.newSingleThreadExecutor(namedThreadFactory(\"FramePipeline-AIIngress\"))"))
        assertTrue(source.contains("private val aiIngressInFlight = AtomicBoolean(false)"))
        assertTrue(aiDispatch >= 0)
        assertTrue(sceneGateRelease > aiDispatch)
        assertTrue(processBlock.contains("if (!aiIngressScheduled) closeImageOnce()"))
        assertTrue(processBlock.contains("releaseImage = closeImageOnce"))
        assertTrue(source.contains("backgroundAiIngress"))
        assertTrue(source.contains("THREAD_PRIORITY_BACKGROUND"))
    }
}
