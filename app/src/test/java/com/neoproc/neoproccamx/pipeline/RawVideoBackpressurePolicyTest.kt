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
                1,
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
    fun `three slot strict reader retains one HAL slot plus queue and writer`() {
        assertEquals(
            1,
            RawVideoBackpressurePolicy.resolveSafeQueueCapacity(
                requestedQueueCapacity = 4,
                readerMaxImages = 3,
                reservedActiveWrites = 1,
                strictHighEnd = true
            )
        )
    }

    @Test
    fun `rejects two slot reader that cannot preserve queue writer and HAL headroom`() {
        val failure = runCatching {
            RawVideoBackpressurePolicy.resolveSafeQueueCapacity(
                requestedQueueCapacity = 4,
                readerMaxImages = 2,
                reservedActiveWrites = 1,
                strictHighEnd = true
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        assertTrue(failure?.message?.contains("cannot preserve one HAL slot") == true)
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
        val resultCallback = source
            .substringAfter("fun onCaptureResult(")
            .substringBefore("fun buildRawBufferDescriptor(image: Image)")

        assertTrue(acquireBlock.contains("sensorLinearNdrRecordingLifecycleLock.withLock"))
        assertTrue(acquireBlock.contains("rawWriterAdmissionLock.withLock"))
        assertTrue(acquireBlock.contains("rawCaptureIngress = snapshotRawCaptureIngressLocked("))
        assertTrue(acquireBlock.contains("reader.acquireNextImage()"))
        assertFalse(acquireBlock.contains("reader.acquireLatestImage()"))
        assertTrue(previewBlock.contains("pairOrHoldIdleRawPreview(img)"))
        assertFalse(previewBlock.contains("rawPreviewProcessor(img"))
        assertFalse(previewBlock.contains("buildRawBufferDescriptor(img"))
        assertTrue(source.contains("Thread(runnable, \"RawPreviewCompute\")"))
        assertTrue(source.contains("idleRawPreviewPending.getAndSet(frame)"))
        assertTrue(source.contains("MAX_UNMATCHED_IDLE_RAW_IMAGES = 3"))
        assertTrue(source.contains("unmatchedIdleRawImages.put(timestamp, image)"))
        assertFalse(acquireBlock.contains("awaitExactCaptureResult("))
        assertFalse(previewBlock.contains("awaitExactCaptureResult("))
        assertFalse(resultCallback.contains("awaitExactCaptureResult("))
        assertTrue(source.contains("private fun awaitExactCaptureResult("))
        assertFalse(
            source.substringAfter("private fun processIdleRawPreview(frame: IdleRawPreviewFrame)")
                .substringBefore("private fun publishSensorLinearExposureMetrics")
                .contains("latestResult")
        )
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
            .substringAfter("private fun writeLoop(session: RawWriterSessionToken)")
            .substringBefore("private fun tapRecordingPreview(")
        val previewTap = source
            .substringAfter("private fun tapRecordingPreview(")
            .substringBefore("private fun writeDng(image: Image, session: RawWriterSessionToken)")

        assertTrue(writeLoop.contains("val imageLease = RawFrameImageLease(img)"))
        assertTrue(writeLoop.contains("tapRecordingPreview(img, session, imageLease)"))
        assertTrue(writeLoop.contains("imageLease.release()"))
        assertFalse(writeLoop.contains("dst.put(src)"))
        assertTrue(previewTap.contains("previewTapExecutor.submit"))
        assertTrue(previewTap.indexOf("copyBuffer.put(src)") > previewTap.indexOf("previewTapExecutor.submit"))
        assertTrue(previewTap.contains("imageLease.retain()"))
        assertTrue(previewTap.contains("imageLease.release()"))
    }

    @Test
    fun `strict raw ingress never waits inside the image callback`() {
        val source = File("src/main/java/com/neoproc/neoproccamx/pipeline/RawVideoManager.kt")
            .readText()
        val ingress = source
            .substringAfter("fun onRawFrameAvailable(reader: ImageReader)")
            .substringBefore("private fun pairOrHoldIdleRawPreview")
        val strictAdmission = source
            .substringAfter("private fun offerRawFrameIfCurrent(")
            .substringBefore("private fun replaceOldestRawFrameIfCurrent(")
        val atomicOffer = source
            .substringAfter("private fun offerRawFrameAndRegisterResultLocked(")
            .substringBefore("private fun retireRawCaptureResultJoin(")

        assertTrue(ingress.contains("offerRawFrameIfCurrent(captureToken, img)"))
        assertTrue(strictAdmission.contains("rawWriterAdmissionLock.withLock"))
        assertTrue(strictAdmission.contains("offerRawFrameAndRegisterResultLocked(token, image)"))
        assertTrue(strictAdmission.contains("rawCaptureIngressIsCurrentLocked(token)"))
        assertTrue(atomicOffer.contains("resultLock.withLock"))
        assertTrue(atomicOffer.contains("token.session.queue.offer(image)"))
        assertFalse(strictAdmission.contains("TimeUnit"))
        assertFalse(strictAdmission.contains("offer(image,"))
        assertFalse(atomicOffer.contains("TimeUnit"))
        assertFalse(atomicOffer.contains("offer(image,"))
        assertTrue(ingress.contains("failStrictRawMaster"))
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
