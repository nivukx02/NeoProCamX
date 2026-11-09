package com.neoproc.neoproccamx.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fail-closed source contracts for MediaCodec input-Surface ownership.
 *
 * Camera2, the core Vulkan bridge, and the offscreen EGL bridge are independent native owners.
 * A route label is therefore never proof that a bridge from an earlier route is detached. These
 * contracts deliberately guard the destructive boundaries that release MediaCodec or a Surface.
 */
class RecordingLifecycleOwnershipHardeningContractTest {
    private val viewModel = File(
        "src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt"
    ).readText()
    private val retirement = File(
        "src/main/java/com/neoproc/neoproccamx/ui/NativeResourceRetirementCoordinator.kt"
    ).readText()
    private val runtime = File(
        "src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt"
    ).readText()
    private val recordingManager = File(
        "src/main/java/com/neoproc/neoproccamx/recording/RecordingManager.kt"
    ).readText()

    @Test
    fun `runtime atomically proves both independent encoder bridges absent`() {
        val method = runtime
            .substringAfter("internal fun detachAllEncoderSurfacesWithProof(): Boolean {")
            .substringBefore("fun setOffscreenAhbEglEncoderSurface(")
        val declarationPrefix = runtime
            .substringBefore("internal fun detachAllEncoderSurfacesWithProof(): Boolean {")
            .trimEnd()
        val coreDetach = method.indexOf("detachInternalEncoderSurfaceWithProof()")
        val offscreenDetach = method.indexOf("detachOffscreenAhbEglEncoderSurfaceWithProof()")
        val coreAbsence = method.indexOf("!hasInternalEncoderSurfaceOwnership()")
        val offscreenAbsence = method.indexOf("!hasOffscreenAhbEglEncoderOwnership()")

        assertTrue(declarationPrefix.endsWith("@Synchronized"))
        assertTrue(coreDetach >= 0)
        assertTrue(offscreenDetach > coreDetach)
        assertTrue(coreAbsence > offscreenDetach)
        assertTrue(offscreenAbsence > coreAbsence)
        // Evaluate both detach attempts even when the first one rejects; `&&` between calls would
        // strand the second owner and make retry behavior depend on route history.
        assertTrue(method.contains("val coreDetached ="))
        assertTrue(method.contains("val offscreenDetached ="))
        assertFalse(
            method.contains(
                "detachInternalEncoderSurfaceWithProof() &&\n" +
                    "            detachOffscreenAhbEglEncoderSurfaceWithProof()"
            )
        )
    }

    @Test
    fun `normal stop proves stale ownership from every route before codec EOS`() {
        val stop = viewModel
            .substringAfter("private fun stopRecordingInternal()")
            .substringBefore("private fun shouldUseUncompressedCinemaPipeline()")
        val routeDetach = stop.indexOf("val detachBeforeStopResult =")
        val preflight = stop.indexOf(
            "detachAllGpuEncoderSurfacesWithProof(\"recording-stop-preflight\")"
        )
        val codecStop = stop.indexOf("gpuRecording.stop()")

        assertTrue(routeDetach >= 0)
        assertTrue("Missing route-independent two-bridge proof", preflight > routeDetach)
        assertTrue("Codec stop crossed before both GPU owners were absent", codecStop > preflight)
        assertTrue(
            "Rejected global detach proof must retain the recorder",
            stop.substring(preflight, codecStop).contains("return@launchStopOwner")
        )
        assertFalse(
            "A current route label cannot prove that a stale bridge is detached",
            stop.contains(
                "feedPath != RecordingFeedPath.OFFSCREEN_AHB_EGL_ENCODER &&\n" +
                    "                    feedPath != RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER"
            )
        )
    }

    @Test
    fun `prepared recorder destruction sites use the atomic two bridge proof`() {
        val prearm = viewModel
            .substringAfter("private fun prearmRecordingSurfaceNow(refreshSession: Boolean): Boolean {")
            .substringBefore("private fun scheduleRecordingSurfacePrearmRetry()")
        val release = viewModel
            .substringAfter("private fun releasePrearmedRecordingSurface(")
            .substringBefore("fun isRecordingAdmissionReadyForStart()")
        val start = viewModel
            .substringAfter("private fun startRecordingInternal()")
            .substringBefore("private suspend fun awaitCameraRecordingState(")
        val failedStart = viewModel
            .substringAfter("private suspend fun cleanupFailedRecordingStart(")
            .substringBefore("private suspend fun awaitNextVulkanImportStatsOrTimeout(")

        val stalePrearm = prearm
            .substringAfter("Pre-armed recording surface stale; rebuilding prepared encoder")
            .substringBefore("} else {")
        assertTrue(
            "Stale prearm must delegate to the shared proof-gated release transaction",
            stalePrearm.contains("releasePrearmedRecordingSurface(") &&
                stalePrearm.contains("preparationOwner = true")
        )
        assertProofBefore(
            prearm.substringAfter("if (gpuRecordState.value is GpuRecordingState.Ready)"),
            "ready prearm replacement",
            "gpuRecording.discardPreparedSession()"
        )
        assertProofBefore(
            prearm.substringAfter("val persistentSurface = gpuRecording.preparePersistentInputSurface()"),
            "start-owned persistent prearm",
            "gpuRecording.discardPreparedSession()"
        )
        assertProofBefore(
            prearm.substringAfter("if (surface !== persistentSurface)"),
            "rejected persistent prearm",
            "gpuRecording.discardPreparedSession()"
        )
        assertProofBefore(release, "prearmed surface release", "gpuRecording.discardPreparedSession()")
        assertProofBefore(
            start.substringAfter("Clear a stale refused-attempt error"),
            "stale error retry",
            "gpuRecording.discardPreparedSession()"
        )
        assertProofBefore(
            start.substringAfter("suspend fun retryPreviewContinuityAfterInternalHardwareImportFailure("),
            "internal import fallback",
            "gpuRecording.reportExternalErrorAfterOwnershipRetired(message)"
        )
        val strictRejection = start
            .substringAfter("suspend fun rejectOffscreenAhbEglRecordingNoDowngrade(")
            .substringBefore("suspend fun startInternalHardwareImportRecording()")
        assertTrue(strictRejection.contains("cleanupFailedRecordingStart("))
        assertFalse(strictRejection.contains("gpuRecording.discardPreparedSession()"))

        val startupCancellation = start
            .substringAfter("catch (e: CancellationException)")
            .substringBefore("} catch (e: Exception)")
        assertTrue(startupCancellation.contains("cleanupFailedRecordingStart("))
        assertFalse(startupCancellation.contains("gpuRecording.stop()"))
        assertFalse(startupCancellation.contains("gpuRecording.discardPreparedSession()"))
        assertProofBefore(
            failedStart,
            "failed start cleanup",
            "failedRecordingStartRecovery.releaseRecorder(terminalFailureMessage)"
        )
    }

    @Test
    fun `error publication names make destructive ownership transfer explicit`() {
        val retaining = recordingManager
            .substringAfter("fun reportExternalErrorRetainingResources(message: String) {")
            .substringBefore("fun reportExternalErrorAfterOwnershipRetired(message: String) {")
        val destructive = recordingManager
            .substringAfter("fun reportExternalErrorAfterOwnershipRetired(message: String) {")
            .substringBefore("fun reportFatalPipelineFailure(message: String) {")

        assertFalse(retaining.contains("releaseInternal("))
        assertFalse(retaining.contains("cleanupFailedOutputTarget("))
        assertTrue(destructive.contains("releaseInternal()"))
        assertTrue(destructive.contains("cleanupFailedOutputTarget()"))
        assertFalse(Regex("gpuRecording\\.reportExternalError\\s*\\(").containsMatchIn(viewModel))
        assertFalse(Regex("gpuRecording::reportExternalError\\b").containsMatchIn(viewModel))
    }

    @Test
    fun `every direct destructive recorder call remains in the reviewed inventory`() {
        val directCalls = Regex(
            "gpuRecording\\.(stop|discardPreparedSession|releasePersistentInputSurface|" +
                "reportExternalErrorAfterOwnershipRetired)\\s*\\("
        ).findAll(viewModel).map { it.groupValues[1] }.toList()
        val actionReferences = Regex(
            "gpuRecording::(destroy|discardPreparedSession|releasePersistentInputSurface|" +
                "reportExternalErrorAfterOwnershipRetired)\\b"
        ).findAll(viewModel).map { it.groupValues[1] }.toList()

        assertEquals(
            mapOf(
                "discardPreparedSession" to 5,
                "releasePersistentInputSurface" to 2,
                "reportExternalErrorAfterOwnershipRetired" to 2,
                "stop" to 1
            ),
            directCalls.groupingBy { it }.eachCount()
        )
        assertEquals(
            mapOf(
                "reportExternalErrorAfterOwnershipRetired" to 1,
                "discardPreparedSession" to 1,
                "releasePersistentInputSurface" to 1,
                "destroy" to 1
            ),
            actionReferences.groupingBy { it }.eachCount()
        )
    }

    @Test
    fun `view model seals and drains prearm ownership before lifecycle retirement`() {
        val onCleared = viewModel.substringAfter("override fun onCleared() {")
        val seal = onCleared.indexOf(
            "recordingSurfacePreparationCoordinator.sealForTeardown()"
        )
        val superCall = onCleared.indexOf("super.onCleared()")
        val lease = onCleared.indexOf("CameraNativeResourceRetirementLease(")

        assertTrue("Prearm admission is not synchronously sealed", seal >= 0)
        assertTrue("ViewModelScope cancellation can race ahead of the ownership seal", seal < superCall)
        assertTrue("The exact teardown seal is not transferred to retirement", lease > seal)
        assertTrue(onCleared.substring(seal, lease + 200).contains("recordingPrearmTeardownSeal"))

        val retire = retirement
            .substringAfter("internal class CameraNativeResourceRetirementLease(")
            .substringAfter("fun retire() {")
        val await = retire.indexOf("awaitQuiescence(")
        val proof = retire.indexOf(
            "RecordingSurfacePreparationCoordinator.TeardownQuiescence.PROVEN",
            await.coerceAtLeast(0)
        )
        val firstEncoderDetach = retire.indexOf("detachAllEncoderSurfacesWithProof()")

        assertTrue("Retirement never waits for the in-flight prearm transaction", await >= 0)
        assertTrue("Prearm quiescence result is not checked", proof > await)
        assertTrue("GPU detach can race an in-flight prearm", firstEncoderDetach > proof)
        assertTrue(
            "Unproven prearm quiescence must quarantine and stop retirement",
            retire.substring(proof, firstEncoderDetach).contains("return")
        )
    }

    @Test
    fun `lifecycle closes Camera2 before recorder and closes Vulkan last`() {
        val retire = retirement
            .substringAfter("internal class CameraNativeResourceRetirementLease(")
            .substringAfter("fun retire() {")
        val still = retire.indexOf("retireWithProof(\"still capture\", destroyStillCapture)")
        val cameraGate = retire.indexOf("CameraDependentRetirementGate.execute(")
        val cameraProof = retire.indexOf("retireCamera = destroyCamera", cameraGate)
        val dependentBlock = retire
            .substringAfter("retireDependents = {")
            .substringBefore("}\n        )")
        val recorder = dependentBlock.indexOf("retireSafely(\"GPU recorder\", destroyGpuRecorder)")
        val pipeline = dependentBlock.indexOf("retireSafely(\"frame pipeline\", destroyPipeline)")
        val aiAf = dependentBlock.indexOf("retireSafely(\"AI autofocus\", destroyAiAf)")
        val aiPipeline = dependentBlock.indexOf("retireSafely(\"AI camera pipeline\", closeAiPipeline)")
        val vulkan = dependentBlock.indexOf("vulkanRuntime::closeWithRetirementProof")

        assertTrue(still >= 0)
        assertTrue(cameraGate > still)
        assertTrue(cameraProof > cameraGate)
        assertTrue("Recorder destruction must be a post-Camera2 dependent", recorder >= 0)
        assertTrue(pipeline > recorder)
        assertTrue(aiAf > pipeline)
        assertTrue(aiPipeline > aiAf)
        assertTrue("Vulkan must be the final native runtime owner retired", vulkan > aiPipeline)
        assertFalse(
            "GPU recorder must not be destroyed before Camera2 retirement proof",
            retire.substring(0, cameraGate).contains("destroyGpuRecorder")
        )
    }

    private fun assertProofBefore(body: String, boundary: String, destructiveMarker: String) {
        val proof = body.indexOf("detachAllGpuEncoderSurfacesWithProof(")
        val destruction = body.indexOf(destructiveMarker, proof.coerceAtLeast(0))

        assertTrue("Missing atomic two-bridge proof for $boundary", proof >= 0)
        assertTrue("$destructiveMarker crossed before $boundary proof", destruction > proof)
        assertTrue(
            "Rejected $boundary proof must fail closed before destruction",
            body.substring(proof, destruction).contains("return")
        )
    }
}
