package com.neoproc.neoproccamx.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingStopLifecycleOwnershipContractTest {
    private val viewModel =
        File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
    private val lifecycle =
        File("src/main/java/com/neoproc/neoproccamx/ui/RecordingStopRestartLifecycle.kt").readText()
    private val camera =
        File("src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt").readText()
    private val pipeline =
        File("src/main/java/com/neoproc/neoproccamx/pipeline/FramePipelineManager.kt").readText()
    private val manager =
        File("src/main/java/com/neoproc/neoproccamx/recording/Npv10RecordingManager.kt").readText()
    private val startupIngress =
        File("src/main/java/com/neoproc/neoproccamx/ui/Npv10RecordingStartupIngress.kt").readText()

    @Test
    fun `every backend stop installs cancellation safe ownership before io dispatch`() {
        val owner = lifecycle
            .substringAfter("fun launchStopOwner(")
            .substringBefore("fun cancelForTeardown()")
        val releaseOwner = lifecycle
            .substringAfter("private fun releaseStopOwner(stopLease:")
            .substringBefore("private fun scheduleRetainedRestart(")
        val stop = viewModel
            .substringAfter("private fun stopRecordingInternal()")
            .substringBefore("private fun shouldUseUncompressedCinemaPipeline()")

        val launch = owner.indexOf("scope.launch(start = CoroutineStart.UNDISPATCHED)")
        val tryBlock = owner.indexOf("try {", launch)
        val io = owner.indexOf("withContext(Dispatchers.IO)", tryBlock)
        val finallyBlock = owner.indexOf("} finally {", io)
        val delegatedRelease = owner.indexOf("releaseStopOwner(stopLease)", finallyBlock)
        val release = releaseOwner.indexOf("stopInProgress.set(false)")

        assertTrue(launch >= 0)
        assertTrue(tryBlock > launch)
        assertTrue(io > tryBlock)
        assertTrue(finallyBlock > io)
        assertTrue(delegatedRelease > finallyBlock)
        assertTrue(release >= 0)
        assertEquals(3, Regex("recordingStopRestartLifecycle\\.launchStopOwner \\{").findAll(stop).count())
        assertFalse(stop.contains("viewModelScope.launch(Dispatchers.IO)"))
    }

    @Test
    fun `npv10 exact marker proof precedes seal detach finalization and worker drain`() {
        val stop = viewModel
            .substringAfter("private suspend fun stopNpv10RecordingSession()")
            .substringBefore("private suspend fun restorePreviewAfterNpv10CaptureStop()")

        val cameraFence = stop.indexOf("camera.stopNpv10P010CaptureAtExactBoundary(")
        val markerProof = stop.indexOf("pipeline.proveRawYuvImageStopBoundary(", cameraFence)
        val seal = stop.indexOf("pipeline.sealImageIngressForReaderClose()", markerProof)
        val ingressIdle = stop.indexOf("pipeline.awaitIdleBeforeReaderClose(", seal)
        val imageDetach = stop.indexOf("pipeline.setRawYuvImageSink(null)", ingressIdle)
        val frameDetach = stop.indexOf("pipeline.setRawYuvFrameSink(null)", imageDetach)
        val finalization = stop.indexOf("npv10Recording.beginFinalization()", frameDetach)
        val acceptedDrain = stop.indexOf("npv10Recording.awaitAcceptedFrameDrain()", finalization)
        val restore = stop.indexOf("restorePreviewAfterNpv10CaptureStop()", acceptedDrain)

        assertTrue(cameraFence >= 0)
        assertTrue(markerProof > cameraFence)
        assertTrue(seal > markerProof)
        assertTrue(ingressIdle > seal)
        assertTrue(imageDetach > ingressIdle)
        assertTrue(frameDetach > imageDetach)
        assertTrue(finalization > frameDetach)
        assertTrue(acceptedDrain > finalization)
        assertTrue(restore > acceptedDrain)
        assertFalse(stop.substring(0, seal).contains("pipeline.setRawYuvImageSink(null)"))
        assertFalse(stop.contains("camera.stopVideoCapture()"))
    }

    @Test
    fun `all boundary failures poison publication before cleanup and saved notification stays audited`() {
        val stop = viewModel
            .substringAfter("private suspend fun stopNpv10RecordingSession()")
            .substringBefore("private suspend fun restorePreviewAfterNpv10CaptureStop()")
        val controllerStop = camera
            .substringAfter("internal fun stopNpv10P010CaptureAtExactBoundary(")
            .substringBefore("private fun awaitP010AnalysisIngressFence(")

        assertTrue(stop.contains("poisonRecorder = { message ->"))
        assertTrue(stop.contains("npv10Recording.poisonStopBoundaryFailure("))
        assertTrue(controllerStop.indexOf("poison(cameraResult.reason)") <
            controllerStop.indexOf("sealDetachAndBeginFinalization("))
        assertTrue(stop.contains("output != null && npv10Recording.lastStopPassedDurabilityAudit()"))
        assertTrue(stop.indexOf("lastStopPassedDurabilityAudit()") <
            stop.indexOf("notifyRecordingSaved(output.absolutePath)"))

        val poison = manager
            .substringAfter("fun poisonStopBoundaryFailure(")
            .substringBefore("@Synchronized\n    fun pause()")
        assertTrue(poison.contains("!sessionGate.poison()"))
        assertTrue(poison.contains("sessionGate.recordPoisonFailure(failure)"))
        assertTrue(manager.contains("if (terminalError != null) add(\"terminalError=${'$'}{terminalError.message}\")"))
        assertTrue(manager.contains("val verifiedMarker = if (prePublicationFailures.isEmpty()"))
    }

    @Test
    fun `tail proof uses exact FIFO and timestamp ownership without quiet guesses`() {
        val proof = pipeline
            .substringAfter("fun proveRawYuvImageStopBoundary(")
            .substringBefore("private fun processAcquiredImage(")

        assertTrue(proof.contains("drainRawYuvImageTail(reader)"))
        assertTrue(proof.contains("rawYuvImageAcceptance.await("))
        assertTrue(proof.contains("rawYuvImageAcceptance.contains("))
        val imagePath = pipeline
            .substringAfter("private fun processAcquiredImage(")
            .substringBefore("// Adaptive load management")
        assertTrue(imagePath.indexOf("if (rawYuvImageSupported)") <
            imagePath.indexOf("rawYuvImageAcceptance.record("))
        assertTrue(imagePath.indexOf("if (ownershipTransferred)") <
            imagePath.indexOf("rawYuvImageAcceptance.record("))
        assertTrue(imagePath.contains("rawYuvImageSinkDeclinedCounter.incrementAndGet()"))
        assertTrue(imagePath.contains("rawYuvImageUnsupportedCounter.incrementAndGet()"))
        assertFalse(pipeline.contains("P010TailDrainQuietPolicy"))
        assertFalse(proof.contains("sleep("))
        assertFalse(proof.contains("delay("))
        assertFalse(proof.contains("consecutive"))
    }

    @Test
    fun `npv10 timeout and post-first-frame cancellation use startup abort fence`() {
        val start = viewModel
            .substringAfter("private suspend fun startNpv10RecordingSession(")
            .substringBefore("private fun abortNpv10Startup(")
        val timeout = start
            .substringAfter("if (!awaitNpv10FirstWrittenFrame(")
            .substringBefore("currentCoroutineContext().ensureActive()")
        val catchBlock = start.substringAfter("} catch (t: Throwable) {")

        val producerMayBeActive = start.indexOf("npv10Camera2ProducerMayBeActive = true")
        val producerStart = start.indexOf(
            "camera.startNpv10VideoCaptureWithProducerLease()",
            producerMayBeActive
        )
        val producerProof = start.indexOf("awaitNpv10P010ProducerReady(", producerStart)
        val writerStart = start.indexOf("npv10Recording.start(config)", producerProof)
        val writerStarted = start.indexOf("npv10WriterStarted = true", writerStart)
        val lineageStarted = start.indexOf("beginCamera2FrameLineageSession(", writerStarted)
        val sinkAttached = start.indexOf("npv10ImageSinkMayBeAttached = true", lineageStarted)
        val firstFrame = start.indexOf("awaitNpv10FirstWrittenFrame(", sinkAttached)
        val producerLeaseRelease =
            start.indexOf("producerStartupLease.commitAfterCadenceStabilization()", firstFrame)
        val cancellationPoint = start.indexOf("currentCoroutineContext().ensureActive()", firstFrame)
        val catchAbort = start.indexOf("abortNpv10Startup(", cancellationPoint)

        assertTrue(producerMayBeActive >= 0)
        assertTrue(producerStart > producerMayBeActive)
        assertTrue(producerProof > producerStart)
        assertTrue(writerStart > producerProof)
        assertTrue(writerStarted > writerStart)
        assertTrue(lineageStarted > writerStarted)
        assertTrue(sinkAttached > lineageStarted)
        assertTrue(firstFrame > sinkAttached)
        assertTrue(producerLeaseRelease > firstFrame)
        assertTrue(cancellationPoint > firstFrame)
        assertTrue(producerLeaseRelease > cancellationPoint)
        assertTrue(catchAbort > cancellationPoint)
        assertTrue(timeout.contains("abortNpv10Startup("))
        assertTrue(timeout.contains("camera2ProducerMayBeActive = npv10Camera2ProducerMayBeActive"))
        assertFalse(timeout.contains("pipeline.setRawYuvImageSink(null)"))
        assertFalse(timeout.contains("npv10Recording.stop()"))
        assertFalse(timeout.contains("camera.stopVideoCapture()"))
        assertTrue(catchBlock.contains("abortNpv10Startup("))
        assertFalse(catchBlock.contains("pipeline.setRawYuvImageSink(null)"))
        assertFalse(catchBlock.contains("camera.stopVideoCapture()"))

        val durableProof = viewModel
            .substringAfter("private suspend fun awaitNpv10FirstWrittenFrame(")
            .substringBefore("private suspend fun stopNpv10RecordingSession()")
        assertTrue(durableProof.contains("npv10Recording.firstDurableFrameResult("))
        assertTrue(durableProof.contains("startupAuthority.producerProof.firstFrameSensorTimestampNs"))
        assertTrue(durableProof.contains("startupIngress.firstSinkTimestampNs()"))
        assertFalse(durableProof.contains("npv10Recording.writtenFrameCount()"))
        assertTrue(start.contains("startupLeaseValid = { !producerStartupLease.isRevoked }"))
        assertTrue(start.contains("producerStartupLease.commitAfterCadenceStabilization()"))
        assertTrue(startupIngress.contains("if (startupLease.isRevoked)"))
    }

    @Test
    fun `startup abort fences a live producer then drains before writer stop`() {
        val abort = viewModel
            .substringAfter("private fun abortNpv10Startup(")
            .substringBefore("private suspend fun awaitNpv10P010SessionReady(")
        val cleanup = abort
            .substringAfter("fun sealDetachAndBeginFinalization(")
            .substringBefore("val exactCaptureStop = camera.stopNpv10P010CaptureAtExactBoundary(")
        val producerBranch = abort
            .substringAfter("if (camera2ProducerMayBeActive && imageSinkMayBeAttached) {")
            .substringBefore("} else {")

        assertTrue(abort.indexOf("npv10Recording.poisonStopBoundaryFailure(poisonReason)") <
            abort.indexOf("if (camera2ProducerMayBeActive && imageSinkMayBeAttached)"))
        assertTrue(producerBranch.contains("camera.stopNpv10P010CaptureAtExactBoundary("))
        assertTrue(producerBranch.contains("pipeline.proveRawYuvImageStopBoundary("))
        assertTrue(producerBranch.contains(
            "establishProducerBoundary = npv10Recording::acknowledgeExactProducerBoundary"
        ))
        assertTrue(producerBranch.contains("sealDetachAndBeginFinalization = ::sealDetachAndBeginFinalization"))
        assertTrue(cleanup.indexOf("pipeline.sealImageIngressForReaderClose()") <
            cleanup.indexOf("pipeline.awaitIdleBeforeReaderClose("))
        assertTrue(cleanup.indexOf("pipeline.awaitIdleBeforeReaderClose(") <
            cleanup.indexOf("pipeline.setRawYuvImageSink(null)"))
        assertTrue(cleanup.indexOf("pipeline.setRawYuvFrameSink(null)") <
            cleanup.lastIndexOf("npv10Recording.beginFinalization()"))
        assertTrue(abort.indexOf("npv10Recording.awaitAcceptedFrameDrain()") <
            abort.indexOf("npv10Recording.stop()"))
        assertTrue(abort.contains("if (!exactCaptureStop)"))
        assertTrue(abort.contains("exact P010 producer-stop boundary was not proven"))
        assertFalse(producerBranch.contains("camera.stopVideoCapture()"))
        assertFalse(abort.contains("notifyRecordingSaved("))
    }

    @Test
    fun `startup abort before sink attachment uses ordinary producer stop without exact fence`() {
        val abort = viewModel
            .substringAfter("private fun abortNpv10Startup(")
            .substringBefore("private suspend fun awaitNpv10P010SessionReady(")
        val earlyBranch = abort
            .substringAfter("} else {")
            .substringBefore("if (recorderActive && !npv10Recording.awaitAcceptedFrameDrain())")

        assertTrue(earlyBranch.contains("if (camera2ProducerMayBeActive)"))
        assertTrue(earlyBranch.contains("camera.stopVideoCapture()"))
        assertTrue(earlyBranch.contains("sealDetachAndBeginFinalization("))
        assertFalse(earlyBranch.contains("stopNpv10P010CaptureAtExactBoundary("))
        assertTrue(abort.contains("writerStarted || npv10Recording.hasActiveSession()"))
    }

    @Test
    fun `lifecycle teardown poisons before callback sink and recorder retirement`() {
        val onCleared = viewModel
            .substringAfter("override fun onCleared()")
            .substringBefore("private fun buildVulkanPreviewTransform(")

        val poison = onCleared.indexOf("npv10Recording.poisonUnprovenProducerBoundaryFailure(")
        val callbackDetach = onCleared.indexOf("camera.onYuvFrame = null", poison)
        val imageSinkDetach = onCleared.indexOf("pipeline.setRawYuvImageSink(null)", callbackDetach)
        val recorderRelease = onCleared.indexOf("npv10Recording.release()", imageSinkDetach)

        assertTrue(poison >= 0)
        assertTrue(callbackDetach > poison)
        assertTrue(imageSinkDetach > callbackDetach)
        assertTrue(recorderRelease > imageSinkDetach)
    }

    @Test
    fun `manager direct stop and publication both gate on irreversible poison`() {
        val poisonMethod = manager
            .substringAfter("fun poisonStopBoundaryFailure(")
            .substringBefore("fun pause(): Boolean")
        val exactAcknowledgement = manager
            .substringAfter("internal fun acknowledgeExactProducerBoundary(markerTimestampNs: Long): Boolean")
            .substringBefore("fun poisonUnprovenProducerBoundaryFailure(")
        val stop = manager
            .substringAfter("fun stop(): File?")
            .substringBefore("fun resetCancelledStartError()")
        val release = manager
            .substringAfter("fun release()")
            .substringBefore("private companion object")

        val missingBoundary = stop.indexOf("if (!finalizationInProgress)")
        val poison = stop.indexOf("poisonStopBoundaryFailure(", missingBoundary)
        val finalization = stop.indexOf("beginFinalization()", poison)
        val boundarySeal = stop.indexOf("sessionProducerBoundaryGate?.sealForPublication()", finalization)
        val writerRelease = stop.indexOf("writer = null", boundarySeal)
        val poisonSnapshot = stop.indexOf(
            "val commitPoison = sessionProducerBoundaryGate?.poisonFailure",
            finalization
        )
        val publicationFailure = stop.indexOf("add(\"p010CommitPoison=", poisonSnapshot)
        val boundaryFailure = stop.indexOf("add(\"producerBoundary=UNPROVEN_OR_POISONED\")", poisonSnapshot)
        val publication = stop.indexOf("Npv10VerifiedMasterPublication.publish(", publicationFailure)

        assertTrue(missingBoundary >= 0)
        assertTrue(poison > missingBoundary)
        assertTrue(finalization > poison)
        assertTrue(boundarySeal > finalization)
        assertTrue(writerRelease > boundarySeal)
        assertTrue(poisonSnapshot > finalization)
        assertTrue(publicationFailure > poisonSnapshot)
        assertTrue(boundaryFailure > poisonSnapshot)
        assertTrue(publication > publicationFailure)
        assertTrue(release.contains("runCatching { stop() }"))
        assertTrue(poisonMethod.contains("!sessionGate.poison()"))
        assertTrue(poisonMethod.contains("sessionGate.recordPoisonFailure(failure)"))
        assertTrue(poisonMethod.contains("!sessionGate.poisonIfOpen()"))
        val admissionClose = exactAcknowledgement.indexOf("paused = true")
        val packingDrain = exactAcknowledgement.indexOf("awaitP010PackingDrain()", admissionClose)
        val writerDrain = exactAcknowledgement.indexOf("writerExecutor.submit<Long?>", packingDrain)
        val writerCadence = exactAcknowledgement.indexOf(
            "activeWriter.closeTerminalCadenceSegment(markerTimestampNs)",
            writerDrain
        )
        val ledgerCadence = exactAcknowledgement.indexOf(
            "captureLedger.closeTerminalCadenceSegment(markerTimestampNs)",
            writerCadence
        )
        val establish = exactAcknowledgement.indexOf("sessionGate.establish()", ledgerCadence)
        assertTrue(admissionClose >= 0)
        assertTrue(packingDrain > admissionClose)
        assertTrue(writerDrain > packingDrain)
        assertTrue(writerCadence > writerDrain)
        assertTrue(ledgerCadence > writerCadence)
        assertTrue(establish > ledgerCadence)
        assertTrue(exactAcknowledgement.contains("sessionGate.establish()"))
        assertFalse(exactAcknowledgement.contains("State.POISONED"))
        val imageAdmission = manager
            .substringAfter("internal fun enqueueP010ImageWithAdmission(image: Image): Npv10P010ImageAdmission")
            .substringBefore("private fun publishPreparedP010Commit(")
        assertTrue(imageAdmission.contains("State.ESTABLISHED"))
        assertTrue(imageAdmission.contains("poisonStopBoundaryFailure("))
        assertTrue(manager.contains(
            "val sessionProducerBoundaryGate = Npv10ProducerBoundaryGate().also { it.open() }"
        ))
        val startupRollback = manager
            .substringAfter("fun rollbackFailedStart()")
            .substringBefore("try {")
        assertTrue(startupRollback.contains("producerBoundaryGateRef.compareAndSet(gate, null)"))
        assertTrue(startupRollback.contains("gate.close()"))
        val stopFinally = stop.indexOf("} finally {", publication)
        val retirementCall = stop.indexOf("retireStoppedSessionOwnership(", stopFinally)
        val retirement = stop
            .substringAfter("private fun retireStoppedSessionOwnership(")
            .substringBefore("private fun recordStopStageTimings(")
        assertTrue(stopFinally > publication)
        assertTrue(retirementCall > stopFinally)
        assertTrue(
            retirement.contains("currentProducerBoundary !== expectedProducerBoundaryGate")
        )
        assertTrue(
            retirement.contains(
                "producerBoundaryGateRef.compareAndSet(expectedProducerBoundaryGate, null)"
            )
        )
        val beginFinalization = manager
            .substringAfter("fun beginFinalization(): Boolean")
            .substringBefore("fun awaitAcceptedFrameDrain(): Boolean")
        assertFalse(beginFinalization.contains("producerBoundaryGate.establish()"))
        assertFalse(Regex("@Synchronized\\s+fun poisonStopBoundaryFailure").containsMatchIn(manager))
        assertFalse(Regex("@Synchronized\\s+fun poisonUnprovenProducerBoundaryFailure").containsMatchIn(manager))
    }
}
