package com.neoproc.neoproccamx.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StillCaptureOwnershipHardeningContractTest {
    private val controllerSource by lazy {
        File("src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt").readText()
    }
    private val controllerHostSource by lazy {
        File("src/main/java/com/neoproc/neoproccamx/camera/CameraControllerHost.kt").readText()
    }
    private val stillCaptureSource by lazy {
        File("src/main/java/com/neoproc/neoproccamx/capture/StillCapture.kt").readText()
    }

    @Test
    fun `controller operation ownership compares capture phase and epoch`() {
        val helper = controllerSource.functionBodyContainingEither(
            "sameStillOperation",
            "ownsStillOperation"
        )

        assertTrue(helper.contains("captureId"))
        assertTrue(helper.contains("phase"))
        assertTrue(helper.contains("operationEpoch"))
    }

    @Test
    fun `queued and delayed still submissions revalidate exact operation owner`() {
        val prioritized = controllerSource.functionBody("runPrioritizedCameraControl")
        val waitForSession = controllerSource.functionBody("submitStillCaptureWhenSessionStable")
        val maximumResolutionBranch = controllerSource.substring(
            controllerSource.indexOf("override fun captureStill("),
            controllerSource.indexOf("private fun submitStillCaptureWhenSessionStable")
        )
        val retryBranch = controllerSource.substring(
            controllerSource.indexOf("private fun submitStillCaptureOnCameraThread"),
            controllerSource.indexOf("private fun dispatchJpegReady")
        )

        assertTrue(prioritized.hasExactOwnerGuard())
        assertTrue(waitForSession.hasExactOwnerGuard())
        assertTrue(maximumResolutionBranch.hasExactOwnerGuard())
        assertTrue(retryBranch.hasExactOwnerGuard())
    }

    @Test
    fun `serialized HDR submission and delayed continuation retain exact operation owner`() {
        val burst = controllerSource.substring(
            controllerSource.indexOf("override fun captureStillBurst("),
            controllerSource.indexOf("override fun stillOutputAspectRatio")
        )
        val serialized = controllerSource.functionBody("submitSerializedStillCaptureRequests")

        assertTrue(burst.contains("sameStillOperation") || burst.contains("ownsStillOperation"))
        assertTrue(serialized.hasExactOwnerGuard())
        val delayedContinuation = serialized.substringAfter("postDelayed", missingDelimiterValue = "")
        assertTrue(delayedContinuation.contains("submit(index + 1)"))
    }

    @Test
    fun `RAW image close is acknowledged before repeating or session restoration`() {
        assertTrue(controllerHostSource.contains("fun acknowledgeStillRawBufferReleased(token: StillCaptureRequestToken)"))
        val rawProcessing = stillCaptureSource.functionBody("processRawImage")
        val close = rawProcessing.indexOf("image.close()")
        val acknowledge = rawProcessing.indexOf("acknowledgeStillRawBufferReleased")
        val finish = rawProcessing.indexOf("finishRawProcessing")

        assertTrue(close >= 0)
        assertTrue(acknowledge > close)
        assertTrue(finish > acknowledge)

        val rawAcknowledge = controllerSource.functionBody("acknowledgeStillRawBufferReleased")
        assertTrue(rawAcknowledge.hasExactOwnerGuard())
        assertTrue(rawAcknowledge.contains("resumePreviewRepeatingAfterStillCapture"))
        assertTrue(rawAcknowledge.contains("restoreNormalSessionAfterMaximumResolutionStill"))
    }

    @Test
    fun `RAW reader acquires every image in sensor order`() {
        val rawHandler = stillCaptureSource.functionBody("drainRawReader")

        assertTrue(rawHandler.contains("acquireNextImage()"))
        assertFalse(rawHandler.contains("acquireLatestImage()"))
    }

    @Test
    fun `RAW still association rejects idle and superseded backlog without weakening identity`() {
        val rawProcessing = stillCaptureSource.functionBody("processRawImage")

        assertTrue(rawProcessing.contains("sessionCoordinator.expectsRawFrame()"))
        assertTrue(rawProcessing.contains("rawIdentitySynchronizer.awaitExactOrNewer("))
        assertTrue(rawProcessing.contains("ExactOrNewerTimestampResult.Superseded"))
        assertTrue(rawProcessing.contains("ExactOrNewerTimestampResult.TimedOut"))
        assertTrue(rawProcessing.contains("recoverCaptureSessionAfterFailure("))
        assertTrue(rawProcessing.contains("failCapture(failedCaptureId"))
    }

    @Test
    fun `RAW target buffer loss fails the owning still operation`() {
        val callback = controllerSource.substring(
            controllerSource.indexOf("override fun onCaptureBufferLost("),
            controllerSource.indexOf("private fun maybeLogPhysicalTelemetryForShaderProfiles")
        )
        val rawTarget = callback.indexOf("target == rawReader?.surface")
        val failure = callback.indexOf("failStillCaptureRequest")

        assertTrue(rawTarget >= 0)
        assertTrue(failure > rawTarget)
    }

    @Test
    fun `only tagged RAW still results enter DNG result retention`() {
        val callback = stillCaptureSource.functionBody("onCaptureResult")
        val tokenRead = callback.indexOf("result.request.tag as? StillCaptureRequestToken")
        val rawGate = callback.indexOf("token.expectsRaw")
        val resultOffer = callback.indexOf("captureResultSynchronizer.offer")

        assertTrue(tokenRead >= 0)
        assertTrue(rawGate > tokenRead)
        assertTrue(resultOffer > rawGate)
    }

    @Test
    fun `asynchronous RAW drain remains inside reader close ownership barrier`() {
        val rawHandler = stillCaptureSource.functionBody("handleRaw")
        val destroy = stillCaptureSource.functionBody("destroy")

        assertTrue(rawHandler.contains("imageReaderIngress.tryEnter()"))
        assertTrue(rawHandler.contains("drainJob.invokeOnCompletion"))
        assertTrue(rawHandler.contains("ingress.close()"))
        assertTrue(destroy.contains("imageReaderIngress.seal()"))
        val exactBufferProof = destroy.indexOf("awaitCameraBuffersReleased(")
        assertTrue(destroy.indexOf("scope.cancel()") < exactBufferProof)
        assertTrue(exactBufferProof < destroy.indexOf("captureResultSynchronizer.close()"))
        assertTrue(destroy.contains("return@synchronized false"))
    }

    private fun String.hasExactOwnerGuard(): Boolean =
        contains("ownsStillOperation") || contains("sameStillOperation")

    /**
     * Returns a complete top-level function using brace depth, avoiding assertions against unrelated
     * neighboring functions in this intentionally large controller source file.
     */
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

    private fun String.functionBodyContainingEither(first: String, second: String): String =
        when {
            contains("fun $first") -> functionBody(first)
            contains("fun $second") -> functionBody(second)
            else -> error("Missing operation-ownership helper")
        }
}
