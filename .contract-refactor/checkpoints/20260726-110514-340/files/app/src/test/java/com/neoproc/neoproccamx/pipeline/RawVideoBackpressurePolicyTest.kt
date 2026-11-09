package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RawVideoBackpressurePolicyTest {

    @Test
    fun `retains one reader slot so raw frame acquisition does not saturate maxImages`() {
        withStrictHighEndOnly(false) {
            assertFalse(
                RawVideoBackpressurePolicy.shouldRetainAcquiredFrame(
                    readerMaxImages = 3,
                    queuedImages = 2,
                    activeWrites = 0
                )
            )
            assertTrue(
                RawVideoBackpressurePolicy.shouldRetainAcquiredFrame(
                    readerMaxImages = 3,
                    queuedImages = 1,
                    activeWrites = 0
                )
            )
        }
    }

    @Test
    fun `caps queued raw frames below reader budget when writer is active`() {
        withStrictHighEndOnly(false) {
            assertEquals(
                2,
                RawVideoBackpressurePolicy.resolveSafeQueueCapacity(
                    requestedQueueCapacity = 5,
                    readerMaxImages = 3,
                    reservedActiveWrites = 1,
                    strictHighEnd = false
                )
            )
            assertEquals(
                3,
                RawVideoBackpressurePolicy.resolveSafeQueueCapacity(
                    requestedQueueCapacity = 6,
                    readerMaxImages = 5,
                    reservedActiveWrites = 1,
                    strictHighEnd = false
                )
            )
        }
    }

    @Test
    fun `raw recording acquires next image so queued writer buffers stay valid`() {
        val source = File("src/main/java/com/neoproc/neoproccamx/pipeline/RawVideoManager.kt")
            .readText()
        val acquireBlock = source
            .substringAfter("val img = try {")
            .substringBefore("} catch (e: IllegalStateException)")
        val previewBlock = source
            .substringAfter("if (!capturing) {")
            .substringBefore("// Frame rate throttle")

        assertTrue(acquireBlock.contains("if (capturing)"))
        assertTrue(acquireBlock.contains("reader.acquireNextImage()"))
        assertTrue(acquireBlock.contains("reader.acquireLatestImage()"))
        assertTrue(previewBlock.contains("rawPreviewProcessor"))
    }

    @Test
    fun `raw capture starts and stops inside stable recording session window`() {
        val source = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt")
            .readText()
        val rawSetupBlock = source
            .substringAfter("if (rawVideoEnabled) {")
            .substringBefore("val expectedPrearmSignature")
        val stopBody = source
            .substringAfter("freezeCamera2FrameLineageSession()")
            .substringBefore("val path = gpuRecording.stop()")

        assertFalse(rawSetupBlock.contains("rawVideoManager.startCapture()"))
        assertTrue(source.contains("fun startRawCaptureAfterRecordingSessionReady()"))
        assertTrue(source.contains("RAW capture armed after recording session reached stable state"))
        assertTrue(stopBody.contains("stopRawCaptureIfNeeded()"))
        assertTrue(stopBody.indexOf("stopRawCaptureIfNeeded()") < stopBody.indexOf("stopCameraVideoCaptureSafely()"))
        assertTrue(stopBody.indexOf("stopRawCaptureIfNeeded()") < stopBody.indexOf("detachRecordingSurfaceBeforeEncoderStop()"))
    }

    @Test
    fun `recording preview copy runs off the single raw writer thread`() {
        val source = File("src/main/java/com/neoproc/neoproccamx/pipeline/RawVideoManager.kt")
            .readText()
        val writeLoop = source
            .substringAfter("private fun writeLoop()")
            .substringBefore("private fun tapRecordingPreview(image: Image): Boolean")
        val previewTap = source
            .substringAfter("private fun tapRecordingPreview(image: Image): Boolean")
            .substringBefore("private fun writeDng(image: Image)")

        assertTrue(writeLoop.contains("if (!tapRecordingPreview(img)) img.close()"))
        assertFalse(writeLoop.contains("dst.put(src)"))
        assertTrue(previewTap.contains("previewTapExecutor.submit"))
        assertTrue(previewTap.indexOf("copyBuffer.put(src)") > previewTap.indexOf("previewTapExecutor.submit"))
        assertTrue(previewTap.contains("runCatching { image.close() }"))
    }

    private inline fun withStrictHighEndOnly(enabled: Boolean, block: () -> Unit) {
        val previous = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.strictHighEndOnly = enabled
        try {
            block()
        } finally {
            HighEndQualityPolicy.strictHighEndOnly = previous
        }
    }
}
