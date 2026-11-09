package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.camera.CameraReaderCloseRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CameraReaderCloseCoordinatorTest {
    @Test
    fun `production still barrier proves every acquired camera buffer was released`() {
        val source = File(
            "src/main/java/com/neoproc/neoproccamx/ui/CameraReaderCloseCoordinator.kt"
        ).readText()

        assertTrue(source.contains("sealStillCaptureIngress = stillCapture::sealImageReaderIngress"))
        assertTrue(source.contains("awaitStillCaptureIdle = stillCapture::awaitCameraBuffersReleased"))
        assertFalse(source.contains("stillCapture::awaitImageReaderIngressIdle"))
    }

    @Test
    fun `YUV close drains only frame pipeline while its seal is held`() {
        val events = mutableListOf<String>()
        val coordinator = coordinator(
            sealPipeline = { seal("pipeline", events) },
            awaitPipeline = {
                events += "pipeline-wait"
                true
            },
            sealStill = { error("still seal must remain unselected") },
            awaitStill = { error("still wait must remain unselected") },
            awaitRaw = { error("RAW wait must remain unselected") }
        )

        assertTrue(coordinator.closeReaders(YUV_ONLY, 1_000L) { events += "close" })
        assertEquals(listOf("pipeline-seal", "pipeline-wait", "close", "pipeline-unseal"), events)
    }

    @Test
    fun `RAW close proves still and raw owners without blocking unrelated pipeline`() {
        val events = mutableListOf<String>()
        var nowNs = 0L
        val coordinator = CameraReaderCloseCoordinator(
            sealFramePipelineIngress = { error("pipeline seal must remain unselected") },
            awaitFramePipelineIdle = { error("pipeline wait must remain unselected") },
            sealStillCaptureIngress = { seal("still", events) },
            awaitStillCaptureIdle = { timeoutMs ->
                assertEquals(1_000L, timeoutMs)
                events += "still-wait"
                nowNs = 400_000_000L
                true
            },
            awaitRawVideoIdle = { timeoutMs ->
                assertEquals(600L, timeoutMs)
                events += "raw-wait"
                true
            },
            monotonicNowNs = { nowNs }
        )

        assertTrue(coordinator.closeReaders(RAW_ONLY, 1_000L) { events += "close" })
        assertEquals(listOf("still-seal", "still-wait", "raw-wait", "close", "still-unseal"), events)
    }

    @Test
    fun `failed selected owner never reaches destructive close and always releases seals`() {
        val events = mutableListOf<String>()
        val coordinator = coordinator(
            sealPipeline = { seal("pipeline", events) },
            awaitPipeline = {
                events += "pipeline-timeout"
                false
            },
            sealStill = { seal("still", events) },
            awaitStill = { error("later owner must not run after timeout") },
            awaitRaw = { error("later owner must not run after timeout") }
        )

        assertFalse(coordinator.closeReaders(ALL_READERS, 1_000L) { events += "close" })
        assertEquals(
            listOf("pipeline-seal", "still-seal", "pipeline-timeout", "still-unseal", "pipeline-unseal"),
            events
        )
    }

    private fun coordinator(
        sealPipeline: () -> AutoCloseable,
        awaitPipeline: (Long) -> Boolean,
        sealStill: () -> AutoCloseable,
        awaitStill: (Long) -> Boolean,
        awaitRaw: (Long) -> Boolean
    ) = CameraReaderCloseCoordinator(
        sealFramePipelineIngress = sealPipeline,
        awaitFramePipelineIdle = awaitPipeline,
        sealStillCaptureIngress = sealStill,
        awaitStillCaptureIdle = awaitStill,
        awaitRawVideoIdle = awaitRaw
    )

    private fun seal(name: String, events: MutableList<String>): AutoCloseable {
        events += "$name-seal"
        return AutoCloseable { events += "$name-unseal" }
    }

    private companion object {
        val YUV_ONLY = CameraReaderCloseRequest(jpeg = false, raw = false, yuv = true, hardwareImport = false)
        val RAW_ONLY = CameraReaderCloseRequest(jpeg = false, raw = true, yuv = false, hardwareImport = false)
        val ALL_READERS = CameraReaderCloseRequest(jpeg = true, raw = true, yuv = true, hardwareImport = true)
    }
}
