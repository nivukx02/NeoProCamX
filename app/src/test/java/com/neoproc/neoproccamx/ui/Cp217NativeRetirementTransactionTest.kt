package com.neoproc.neoproccamx.ui

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CP217 regression boundary for native camera/GPU ownership.
 *
 * These tests deliberately combine executable coordination tests with narrow source contracts for
 * Android/JNI owners that cannot be instantiated on the host JVM. They fail closed: a timeout or a
 * rejected detach may retain an owner for a later retry, but must never be treated as destruction.
 */
class Cp217NativeRetirementTransactionTest {
    private val viewModelSource by lazy {
        File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
    }
    private val runtimeSource by lazy {
        File("src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt").readText()
    }
    private val bridgeSource by lazy {
        File("src/main/java/com/neoproc/neoproccamx/rendering/VulkanBridge.kt").readText()
    }
    private val retirementSource by lazy {
        File("src/main/java/com/neoproc/neoproccamx/ui/NativeResourceRetirementCoordinator.kt")
            .readText()
    }

    @Test
    fun `Vulkan retirement proof stays false across every quarantine return`() {
        val proof = runtimeSource
            .substringAfter("internal fun closeWithRetirementProof(): Boolean {")
            .substringBefore("override fun close()")
        val close = runtimeSource.substringAfter("override fun close() {")

        assertTrue(proof.indexOf("close()") < proof.indexOf("return closeCompleted.get()"))

        val previewTransportFailure = close
            .substringAfter("if (!previewWorkerDrained || !previewTransportQuiesced) {")
            .substringBefore("if (!encoderInputReadQuiesced) {")
        val inputReadFailure = close
            .substringAfter("if (!encoderInputReadQuiesced) {")
            .substringBefore("val offscreenBridgeClosed")
        val offscreenBridgeFailure = close
            .substringAfter("if (!offscreenBridgeClosed) {")
            .substringBefore("val previewBridgeClosed")
        val previewBridgeFailure = close
            .substringAfter("if (!previewBridgeClosed) {")
            .substringBefore("runtimeCloseQuarantined = false")
        val nativeRuntimeFailure = close
            .substringAfter("if (!nativeRuntimeClosed) {")
            .substringBefore("synchronized(rawDemosaicCompletionLock)")

        listOf(
            previewTransportFailure,
            inputReadFailure,
            offscreenBridgeFailure,
            previewBridgeFailure,
            nativeRuntimeFailure
        ).forEach { failureBranch ->
            assertTrue(failureBranch.contains("runtimeCloseQuarantined = true"))
            assertTrue(failureBranch.contains("return"))
            assertFalse(failureBranch.contains("closeCompleted.set(true)"))
        }

        val offscreenProof = close.indexOf("offscreenAhbEglEncoderBridge.closeAcknowledged()")
        val previewProof = close.indexOf("sensorLinearNdrPreviewBridge.closeAcknowledged()")
        val surfaceRelease = close.indexOf("boundOffscreenEncoderSurface = null")
        val nativeDestroy = close.indexOf("bridge.closeAcknowledged()", previewProof)
        val nativeOwnershipRetirement = close.indexOf(
            "rawDemosaicCompletionHandoff.retireAfterNativeRetirementProof()",
            nativeDestroy
        )
        val completionProof = close.lastIndexOf("closeCompleted.set(true)")

        assertTrue(offscreenProof >= 0)
        assertTrue(previewProof > offscreenProof)
        assertTrue(nativeDestroy > previewProof)
        // Kotlin Surface references remain owned until the native core acknowledges destruction.
        assertTrue(surfaceRelease > nativeDestroy)
        assertTrue(nativeOwnershipRetirement > nativeDestroy)
        assertTrue(completionProof > nativeOwnershipRetirement)
    }

    @Test
    fun `Vulkan bridge clears its native handle only after destroy acknowledgement`() {
        val closeAcknowledged = bridgeSource
            .substringAfter("internal fun closeAcknowledged(): Boolean {")
            .substringBefore("override fun close()")
        val close = bridgeSource
            .substringAfter("override fun close() {")
            .substringBefore("private external fun nativeCreate()")

        val handleRead = closeAcknowledged.indexOf("val handle = nativeHandle")
        val nativeDestroy = closeAcknowledged.indexOf("nativeDestroy(handle)", handleRead)
        val handleClear = closeAcknowledged.indexOf("nativeHandle = 0L", nativeDestroy)
        val acknowledgedReturn = closeAcknowledged.indexOf("return true", handleClear)

        assertTrue(handleRead >= 0)
        assertTrue(nativeDestroy > handleRead)
        assertTrue(handleClear > nativeDestroy)
        assertTrue(acknowledgedReturn > handleClear)
        assertTrue(closeAcknowledged.contains("catch (failure: Throwable)"))
        assertTrue(closeAcknowledged.contains("return false"))
        assertFalse(
            closeAcknowledged
                .substringAfter("catch (failure: Throwable)")
                .substringBefore("return false")
                .contains("nativeHandle = 0L")
        )
        assertTrue(close.contains("if (!closeAcknowledged())"))

        val runtimeClose = runtimeSource
            .substringAfter("internal fun closeWithRetirementProof(): Boolean {")
            .substringAfter("override fun close() {")
        assertTrue(runtimeClose.contains("bridge.closeAcknowledged()"))
        assertFalse(runtimeClose.contains("runCatching { bridge.close() }"))
    }

    @Test
    fun `persistent native failure consumes the exact retry budget and preserves dependents`() {
        val attempts = AtomicInteger(0)
        val retired = NativeResourceRetirementRetryPolicy.retire(maxAttempts = 5) {
            attempts.incrementAndGet()
            false
        }

        assertFalse(retired)
        assertEquals(5, attempts.get())

        val exactOwner = Any()
        var quarantinedOwner: Any? = null
        var quarantineReason: String? = null
        var dependentsRetired = false
        val outcome = CameraDependentRetirementGate.execute(
            quarantineOwner = exactOwner,
            retireCamera = { false },
            retireDependents = { dependentsRetired = true },
            retainQuarantine = { reason, owner ->
                quarantineReason = reason
                quarantinedOwner = owner
            }
        )

        assertFalse(outcome.cameraRetired)
        assertFalse(outcome.dependentsInvoked)
        assertFalse(dependentsRetired)
        assertNull(outcome.cameraFailure)
        assertSame(exactOwner, quarantinedOwner)
        assertTrue(requireNotNull(quarantineReason).contains("dependent resources preserved"))
    }

    @Test
    fun `lifecycle lease retries atomic two bridge detach and stops on failed proof`() {
        val lease = retirementSource
            .substringAfter("internal class CameraNativeResourceRetirementLease(")
        val detachWindow = lease
            .substringAfter("vulkanRuntime.setOffscreenAhbEglSubmissionEnabled(false)")
            .substringBefore("val rawManagerRetired")

        val retry = detachWindow.indexOf("NativeResourceRetirementRetryPolicy.retire")
        val detach = detachWindow.indexOf(
            "vulkanRuntime.detachAllEncoderSurfacesWithProof()",
            retry.coerceAtLeast(0)
        )
        val failedProof = detachWindow.indexOf("if (!encoderDetached)", detach.coerceAtLeast(0))
        val quarantine = detachWindow.indexOf("quarantine(", failedProof.coerceAtLeast(0))
        val returnFromLease = detachWindow.indexOf("return", quarantine.coerceAtLeast(0))

        assertTrue("Lifecycle atomic detach is not using the bounded retry policy", retry >= 0)
        assertTrue(detach > retry)
        assertTrue("Unacknowledged EGL detach does not have a fail-closed branch", failedProof > detach)
        assertTrue(quarantine > failedProof)
        assertTrue("Dependent retirement remains reachable after failed EGL proof", returnFromLease > quarantine)
    }

    @Test
    fun `prearm replacement cannot discard codec ownership before atomic GPU detach proof`() {
        val prearm = viewModelSource
            .substringAfter("private fun prearmRecordingSurfaceNow(refreshSession: Boolean): Boolean {")
            .substringBefore("private fun releasePrearmedRecordingSurface(")
        val release = viewModelSource
            .substringAfter("private fun releasePrearmedRecordingSurface(")
            .substringBefore("fun isRecordingAdmissionReadyForStart()")

        val staleBranch = prearm
            .substringAfter("Pre-armed recording surface stale; rebuilding prepared encoder")
            .substringBefore("} else {")
        val readyProof = prearm.indexOf(
            "detachAllGpuEncoderSurfacesWithProof(\"ready-prearm-replacement\")"
        )
        val readyRecorderDiscard = prearm.indexOf("gpuRecording.discardPreparedSession()", readyProof)

        assertTrue(staleBranch.contains("releasePrearmedRecordingSurface("))
        assertTrue(staleBranch.contains("preparationOwner = true"))
        assertFalse(staleBranch.contains("gpuRecording.discardPreparedSession()"))
        assertTrue(readyProof >= 0)
        assertTrue(readyRecorderDiscard > readyProof)
        assertTrue(prearm.substring(readyProof, readyRecorderDiscard).contains("return false"))

        val releaseProof = release.indexOf(
            "detachAllGpuEncoderSurfacesWithProof(\"prearmed-surface-release\")"
        )
        val releaseOwnerClear = release.indexOf("prearmedRecordingSurface = null", releaseProof)
        val persistentRelease = release.indexOf("gpuRecording.releasePersistentInputSurface()")
        assertTrue(releaseProof >= 0)
        assertTrue(release.substring(releaseProof, releaseOwnerClear).contains("return false"))
        assertTrue(releaseOwnerClear > releaseProof)
        assertTrue(persistentRelease > releaseOwnerClear)
    }

    @Test
    fun `failed start and normal stop require acknowledged EGL detach before owner release`() {
        val failedStart = viewModelSource
            .substringAfter("private suspend fun cleanupFailedRecordingStart(")
            .substringBefore("private suspend fun awaitNextVulkanImportStatsOrTimeout(")
        val normalStop = viewModelSource
            .substringAfter("private fun stopRecordingInternal()")
            .substringBefore("private fun shouldUseUncompressedCinemaPipeline()")

        assertProofPrecedesRelease(
            body = failedStart,
            proofReason = "failed-start-cleanup",
            releaseMarker = "failedRecordingStartRecovery.releaseRecorder(terminalFailureMessage)"
        )
        assertFalse(
            failedStart.contains("vulkanRuntime.setOffscreenAhbEglEncoderSurface(null, 0, 0)")
        )

        val offscreenStopRoute = normalStop.indexOf(
            "detachInternalRecordingFeedBeforeEncoderStop("
        )
        val strictRouteArgument = normalStop.indexOf(
            "deferHardwareSourceRebuildUntilAfterEncoderStop = true",
            offscreenStopRoute.coerceAtLeast(0)
        )
        val globalDetach = normalStop.indexOf(
            "detachAllGpuEncoderSurfacesWithProof(\"recording-stop-preflight\")",
            strictRouteArgument.coerceAtLeast(0)
        )
        val failClosedReturn = normalStop.indexOf("return@launchStopOwner", globalDetach)
        val release = normalStop.indexOf(
            "sensorLinearNdrRecordingLifecycle.releaseDemosaicOwnership()",
            failClosedReturn.coerceAtLeast(0)
        )

        assertTrue("Normal stop does not route through the strict internal-feed detach", offscreenStopRoute >= 0)
        assertTrue("Normal stop does not request the recording-stop detach route", strictRouteArgument > offscreenStopRoute)
        assertTrue("Route-independent normal-stop detach is missing", globalDetach > strictRouteArgument)
        assertTrue("Rejected normal-stop detach does not fail closed", failClosedReturn > globalDetach)
        assertTrue("Demosaic ownership can be released before detach rejection returns", release > failClosedReturn)
        assertFalse(
            normalStop.contains("vulkanRuntime.setOffscreenAhbEglEncoderSurface(null, 0, 0)")
        )

        val detachHelper = viewModelSource
            .substringAfter("private fun detachAllGpuEncoderSurfacesWithProof(reason: String): Boolean")
            .substringBefore("private fun prearmOffscreenHardwareImportSource(")
        assertTrue(detachHelper.contains("vulkanRuntime.detachAllEncoderSurfacesWithProof()"))
        assertTrue(detachHelper.contains("codec and Surface ownership were retained"))
    }

    @Test
    fun `startup cancellation publishes stop owner then retires failed prearm before completion`() {
        val events = mutableListOf<String>()
        val recovery = FailedRecordingStartRecoveryCoordinator(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            mainDispatcher = Dispatchers.Unconfined,
            actions = FailedRecordingStartRecoveryActions(
                backendFailureMessage = { error("Cancellation must not read a backend error") },
                publishTransitionFailure = { error("Cancellation must not publish an error") },
                stopForegroundProtection = { events += "foreground-stop" },
                reportRecorderFailure = { error("Cancellation must not report recorder failure") },
                discardPreparedSession = { events += "prepared-discard" },
                releasePersistentInputSurface = { events += "persistent-release" },
                refreshCameraSession = {},
                restorePreviewBinding = {},
                syncCameraPreviewLook = {},
                releaseCinemaAutoLock = {},
                reconcileRawPreview = {},
                syncPreviewFrameRate = {},
                syncPreviewPerformance = {},
                reportCleanupError = { failure, operation, _ ->
                    throw AssertionError("Unexpected cleanup failure in $operation", failure)
                }
            )
        )
        val handoff = RecordingStartupHandoff()

        assertTrue(handoff.tryBeginStart())
        handoff.registerCancellationAction {
            events += "cancel-startup"
            events += "egl-detach-proof"
            val failure = recovery.terminalize("cancelled", reportError = false)
            assertNull(failure)
            recovery.releaseRecorder(failure)
            events += "cleanup-complete"
        }

        val stop = handoff.requestStopDuringStart { events += "stop-owner" }
        val completion = handoff.completeStart(backendActive = false) {
            error("Cancelled startup must not reserve a backend stop")
        }
        events += "startup-complete"

        assertTrue(stop.handledDuringStartup)
        assertTrue(stop.newlyRequested)
        assertTrue(completion.stopWasRequested)
        assertFalse(completion.backendStopReserved)
        assertEquals(
            listOf(
                "stop-owner",
                "cancel-startup",
                "egl-detach-proof",
                "foreground-stop",
                "prepared-discard",
                "persistent-release",
                "cleanup-complete",
                "startup-complete"
            ),
            events
        )
    }

    private fun assertProofPrecedesRelease(
        body: String,
        proofReason: String,
        releaseMarker: String
    ) {
        val proofMarker =
            "detachAllGpuEncoderSurfacesWithProof(\"$proofReason\")"
        val proof = body.indexOf(proofMarker)
        val release = body.indexOf(releaseMarker, proof.coerceAtLeast(0))

        assertTrue("Missing bounded EGL detach proof for $proofReason", proof >= 0)
        assertTrue("$releaseMarker crossed before $proofReason proof", release > proof)
        assertTrue(
            "Rejected $proofReason detach must fail closed before ownership release",
            body.substring(proof, release).contains("return")
        )
    }
}
