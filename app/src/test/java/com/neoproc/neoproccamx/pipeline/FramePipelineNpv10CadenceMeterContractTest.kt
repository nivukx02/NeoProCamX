package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FramePipelineNpv10CadenceMeterContractTest {
    private val source = File(
        "src/main/java/com/neoproc/neoproccamx/pipeline/FramePipelineManager.kt"
    ).readText()

    @Test
    fun `cadence meter runs before optional writer sink ownership`() {
        val body = source
            .substringAfter("private fun processAcquiredImage(")
            .substringBefore("private fun maybePublishNpv10CadenceExposureMetrics(")
        val meterIndex = body.indexOf("maybePublishNpv10CadenceExposureMetrics(")
        val sinkIndex = body.indexOf("if (rawYuvImageSinkSnapshot != null)")

        assertTrue("The pre-writer P010 producer must be metered", meterIndex >= 0)
        assertTrue(
            "Exposure convergence must happen before durable writer-sink attachment",
            sinkIndex >= 0 && meterIndex < sinkIndex
        )
    }
}
