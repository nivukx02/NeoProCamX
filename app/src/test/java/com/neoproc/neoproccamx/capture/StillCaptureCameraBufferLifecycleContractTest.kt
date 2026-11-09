package com.neoproc.neoproccamx.capture

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StillCaptureCameraBufferLifecycleContractTest {
    private val source by lazy {
        File("src/main/java/com/neoproc/neoproccamx/capture/StillCapture.kt").readText()
    }

    @Test
    fun `JPEG and RAW acquired Images own exact leases until successful close`() {
        val jpeg = source.functionBody("handleJpeg")
        val jpegAcquire = maxOf(
            jpeg.indexOf("reader.acquireNextImage()"),
            jpeg.indexOf("reader.acquireLatestImage()")
        )
        val jpegLease = jpeg.indexOf("cameraBufferTracker.acquire()")
        val jpegImageClose = jpeg.indexOf("image.close()")
        val jpegLeaseClose = jpeg.indexOf("cameraBufferLease.close()")

        assertTrue(jpegAcquire >= 0)
        assertTrue(jpegLease > jpegAcquire)
        assertTrue(jpegImageClose > jpegLease)
        assertTrue(jpegLeaseClose > jpegImageClose)
        assertTrue(jpeg.substring(jpegImageClose, jpegLeaseClose).contains("if (imageClosed)"))

        val rawDrain = source.functionBody("drainRawReader")
        val rawAcquire = rawDrain.indexOf("reader.acquireNextImage()")
        val rawLease = rawDrain.indexOf("cameraBufferTracker.acquire()")
        val rawDispatch = rawDrain.indexOf("processRawImage(image, cameraBufferLease)")
        assertTrue(rawAcquire >= 0)
        assertTrue(rawLease > rawAcquire)
        assertTrue(rawDispatch > rawLease)

        val rawProcess = source.functionBody("processRawImage")
        val rawImageClose = rawProcess.indexOf("image.close()")
        val rawLeaseClose = rawProcess.indexOf("cameraBufferLease.close()")
        assertTrue(rawProcess.contains("finally"))
        assertTrue(rawImageClose >= 0)
        assertTrue(rawLeaseClose > rawImageClose)
        assertTrue(rawProcess.substring(rawImageClose, rawLeaseClose).contains("if (imageClosed)"))
    }

    @Test
    fun `combined release proof spends one timeout budget on ingress then acquired buffers`() {
        val await = source.functionBody("awaitCameraBuffersReleased")
        val start = await.indexOf("val startedNanos = System.nanoTime()")
        val ingress = await.indexOf("imageReaderIngress.awaitIdle(boundedTimeoutMs)")
        val elapsed = await.indexOf("System.nanoTime() - startedNanos")
        val buffers = await.indexOf("cameraBufferTracker.awaitReleasedNanos(remainingNanos)")

        assertTrue(start >= 0)
        assertTrue(ingress > start)
        assertTrue(elapsed > ingress)
        assertTrue(buffers > elapsed)
    }

    @Test
    fun `destroy detaches callbacks and cancels work before bounded release proof`() {
        val destroy = source.functionBody("destroy")
        val seal = destroy.indexOf("imageReaderIngress.seal()")
        val detach = destroy.indexOf("detachCameraCallbacksForTeardown()")
        val cancelTimeout = destroy.indexOf("captureTimeoutGate.invalidate()")
        val cancelControl = destroy.indexOf("captureControlScope.cancel()")
        val cancelProcessing = destroy.indexOf("scope.cancel()")
        val await = destroy.indexOf("awaitCameraBuffersReleased(DESTROY_IMAGE_INGRESS_TIMEOUT_MS)")
        val timeoutReturn = destroy.indexOf("return@synchronized false")
        val finalize = destroy.indexOf("captureResultSynchronizer.close()")

        assertTrue(seal >= 0)
        assertTrue(detach > seal)
        assertTrue(cancelTimeout > detach)
        assertTrue(cancelControl > cancelTimeout)
        assertTrue(cancelProcessing > cancelControl)
        assertTrue(await > cancelProcessing)
        assertTrue(timeoutReturn > await)
        assertTrue(finalize > timeoutReturn)
    }

    @Test
    fun `teardown only detaches callbacks still owned by StillCapture`() {
        val detach = source.functionBody("detachCameraCallbacksForTeardown")

        assertTrue(detach.contains("cameraController.onJpegReady === jpegReadyCallback"))
        assertTrue(detach.contains("cameraController.onRawReady === rawReadyCallback"))
        assertTrue(detach.contains("cameraController.onCaptureResult === captureResultCallback"))
        assertTrue(detach.contains("cameraController.onStillCaptureFailed === stillCaptureFailureCallback"))
        assertTrue(detach.contains("callbackRegistrationSealed = true"))
    }

    private fun String.functionBody(name: String): String {
        val nameIndex = indexOf("fun $name")
        require(nameIndex >= 0) { "Missing function $name" }
        val bodyStart = indexOf('{', startIndex = nameIndex)
        require(bodyStart >= 0) { "Missing body for $name" }
        var depth = 0
        for (index in bodyStart until length) {
            when (this[index]) {
                '{' -> depth += 1
                '}' -> {
                    depth -= 1
                    if (depth == 0) return substring(nameIndex, index + 1)
                }
            }
        }
        error("Unterminated function $name")
    }
}
