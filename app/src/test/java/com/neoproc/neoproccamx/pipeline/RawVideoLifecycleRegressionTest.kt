package com.neoproc.neoproccamx.pipeline

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawVideoLifecycleRegressionTest {
    private fun managerSource(): String {
        val relative = "src/main/java/com/neoproc/neoproccamx/pipeline/RawVideoManager.kt"
        return listOf(File(relative), File("app/$relative")).first { it.isFile }.readText()
    }

    @Test
    fun `NDR start refuses reader capacity that cannot preserve the bounded stall`() {
        val start = managerSource()
            .substringAfter("internal fun startSensorLinearNdrRecordingIngress(")
            .substringBefore("internal fun requestSensorLinearNdrRecordingIngressTeardown()")

        val capacityPlan = start.indexOf("RawSensorReaderDepthPolicy.minimumBoundedStallDepth")
        val capacityGate = start.indexOf("readerMaxImages < boundedStallMinimumDepth")
        val readinessLease = start.indexOf("sensorLinearNdrRecordingStartupGate.consume(")

        assertTrue(capacityPlan >= 0)
        assertTrue(capacityGate > capacityPlan)
        assertTrue(readinessLease > capacityGate)
        assertTrue(start.contains("refusing a clip that could only survive"))
        assertTrue(start.contains("dropping or reordering frames"))
    }

    @Test
    fun `final exposure publication serializes against lifecycle invalidation`() {
        val lifecycleLock = ReentrantLock()
        val current = AtomicBoolean(true)
        val publicationEntered = CountDownLatch(1)
        val releasePublication = CountDownLatch(1)
        val invalidationAttempted = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val publication = executor.submit<Boolean> {
                SensorLinearExposureLifecyclePublisher.publishIfCurrent(
                    lifecycleLock = lifecycleLock,
                    isCurrent = current::get
                ) {
                    publicationEntered.countDown()
                    assertTrue(releasePublication.await(2, TimeUnit.SECONDS))
                }
            }
            assertTrue(publicationEntered.await(2, TimeUnit.SECONDS))

            val invalidation = executor.submit {
                invalidationAttempted.countDown()
                lifecycleLock.withLock { current.set(false) }
            }
            assertTrue(invalidationAttempted.await(2, TimeUnit.SECONDS))
            assertFalse("invalidation must wait for the protected publication", invalidation.isDone)

            releasePublication.countDown()
            assertTrue(publication.get(2, TimeUnit.SECONDS))
            invalidation.get(2, TimeUnit.SECONDS)
            assertFalse(current.get())
        } finally {
            releasePublication.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `retired exposure generation is rejected without publication`() {
        val published = AtomicBoolean(false)
        val accepted = SensorLinearExposureLifecyclePublisher.publishIfCurrent(
            lifecycleLock = ReentrantLock(),
            isCurrent = { false },
            publish = { published.set(true) }
        )
        assertFalse(accepted)
        assertFalse(published.get())
    }

    @Test
    fun `RAW failure pause resume and destroy retain serialized ownership contracts`() {
        val manager = managerSource()
        val startFailure = manager
            .substringAfter("fun startCapture(selectedFps: Int) = rawCaptureLifecycleLock.withLock")
            .substringBefore("private fun startCaptureLocked()")
        val startBody = manager
            .substringAfter("private fun startCaptureLocked(selectedFps: Int)")
            .substringBefore("fun stopCapture(): String?")
        val pause = manager.substringAfter("fun pauseCapture()").substringBefore("fun resumeCapture()")
        val resume = manager.substringAfter("fun resumeCapture()").substringBefore("fun writtenFrameCount()")
        val destroy = manager.substringAfter("private fun destroyLocked(): Boolean")
            .substringBefore("private fun awaitExecutorTerminationForDestroy(")

        assertFalse(startBody.contains("captureLifecycleActive.set(false)"))
        assertTrue(startFailure.indexOf("quiesceRawCaptureWorkers()") <
            startFailure.lastIndexOf("captureLifecycleActive.set(false)"))

        assertTrue(pause.contains("sensorLinearNdrRecordingLifecycleLock.withLock"))
        assertTrue(pause.contains("invalidateRawOriginalExposureAnalyticsLocked()"))
        assertTrue(resume.contains("sensorLinearNdrRecordingLifecycleLock.withLock"))
        assertTrue(resume.indexOf("invalidateRawOriginalExposureAnalyticsLocked()") <
            resume.indexOf("isPaused.set(false)"))

        assertTrue(manager.contains("private val destroyFinalized = AtomicBoolean(false)"))
        assertTrue(destroy.contains("if (destroyFinalized.get()) return true"))
        assertTrue(destroy.indexOf("awaitIdleRawPreviewWorkerIdleForTeardown") <
            destroy.indexOf("destroyFinalized.set(true)"))
        assertTrue(destroy.indexOf("awaitExecutorTerminationForDestroy") <
            destroy.indexOf("destroyFinalized.set(true)"))
    }

    @Test
    fun `delayed NDR worker must retire before the final executor barrier`() {
        val manager = managerSource()
        val bounded = manager
            .substringAfter("private fun awaitSensorLinearNdrRecordingWorkerIdle(timeoutMs: Long): Boolean")
            .substringBefore("private fun awaitSensorLinearNdrRecordingWorkerUnscheduledUntil(")
        val mandatory = manager
            .substringAfter("private fun awaitSensorLinearNdrRecordingWorkerIdleForTeardown(")
            .substringBefore("internal fun isSensorLinearNdrRecordingWorkerQuiescentForTeardown()")
        val worker = manager
            .substringAfter("private fun drainSensorLinearNdrRecordingFrames()")
            .substringBefore("private fun processSensorLinearNdrRecordingFrame(")

        assertTrue(
            bounded.indexOf("awaitSensorLinearNdrRecordingWorkerUnscheduledUntil(deadlineNs)") <
                bounded.indexOf("sensorLinearNdrRecordingExecutor.submit { Unit }")
        )
        assertTrue(bounded.contains("isSensorLinearNdrRecordingWorkerQuiescentForTeardown()"))
        assertTrue(mandatory.contains("sensorLinearNdrRecordingWorkerUnscheduled.await()"))
        assertTrue(mandatory.contains("if (interrupted) Thread.currentThread().interrupt()"))
        assertTrue(worker.contains("markSensorLinearNdrRecordingWorkerUnscheduled()"))
    }

    @Test
    fun `idle RAW publication submission and completion reject capture ownership`() {
        val manager = managerSource()
        val process = manager
            .substringAfter("private fun processIdleRawPreview(frame: IdleRawPreviewFrame)")
            .substringBefore("private fun maybeReserveRawOriginalExposureMeter(")
        val exposure = manager
            .substringAfter("private fun publishSensorLinearExposureMetrics(")
            .substringBefore("fun onCaptureResult(")
        val completion = manager
            .substringAfter("internal fun presentIdleRawPreviewCompletionIfCurrent(")
            .substringBefore("internal fun startSensorLinearNdrRecordingIngress(")

        assertTrue(process.split("sensorLinearNdrRecordingLifecycleLock.withLock").size >= 3)
        assertTrue(process.contains("isIdleRawPreviewFrameCurrentLocked(frame)"))
        assertTrue(process.contains("IdleRawPreviewSubmissionIdentity("))
        assertTrue(process.contains("rawPreviewProcessor?.invoke(image, monitorDescriptor, submissionIdentity)"))
        assertTrue(exposure.indexOf("isIdleRawPreviewFrameCurrentLocked(frame)") <
            exposure.indexOf("camera.setSensorLinearNdrExposureSceneMetrics("))
        assertTrue(completion.contains("isIdleRawPreviewSubmissionCurrentLocked(identity)"))
        assertTrue(completion.indexOf("isIdleRawPreviewSubmissionCurrentLocked(identity)") <
            completion.indexOf("present()"))
    }

    @Test
    fun `writer timeout retains exact future queue and storage ownership for retry`() {
        val manager = managerSource()
        val quiesce = manager
            .substringAfter("private fun quiesceWriteLoop(): Boolean")
            .substringBefore("private fun awaitWriteLoopExit(")
        val stop = manager
            .substringAfter("private fun stopCaptureLocked()")
            .substringBefore("fun pauseCapture()")
        val start = manager
            .substringAfter("private fun startCaptureLocked()")
            .substringBefore("fun stopCapture()")

        assertTrue(quiesce.contains("val handle = writeLoopHandle"))
        assertTrue(quiesce.contains("drainAndCloseWriteQueue(handle.session.queue)"))
        assertTrue(quiesce.contains("return false"))
        assertTrue(quiesce.indexOf("if (writeLoopHandle === handle) writeLoopHandle = null") >
            quiesce.lastIndexOf("awaitWriteLoopExit(handle.future"))
        assertTrue(stop.indexOf("closeRawWriterAdmission()") <
            stop.indexOf("quiesceRawCaptureWorkers()"))
        val writerQuiescence = stop.indexOf("quiesceRawCaptureWorkers()")
        val completedStreamSnapshot = stop.indexOf("val completedRawStream = directIOEngine")
        val completedStreamClose = stop.indexOf("completedRawStream?.close()")
        assertTrue(writerQuiescence >= 0)
        assertTrue(completedStreamSnapshot > writerQuiescence)
        assertTrue(completedStreamClose > completedStreamSnapshot)
        assertTrue(start.indexOf("quiesceRawCaptureWorkers()") <
            start.indexOf("writeQueue = ArrayBlockingQueue("))
        val taskCreate = start.indexOf("val writerTask = FutureTask<Unit>")
        val handlePublish = start.indexOf("writeLoopHandle = writerHandle", taskCreate)
        val taskExecute = start.indexOf("writeExecutor.execute(writerTask)", handlePublish)
        assertTrue(taskCreate >= 0)
        assertTrue(handlePublish > taskCreate)
        assertTrue(taskExecute > handlePublish)
    }

    @Test
    fun `Camera2 offers and preview taps are exact session scoped`() {
        val manager = managerSource()
        val ingress = manager
            .substringAfter("fun onRawFrameAvailable(reader: ImageReader)")
            .substringBefore("private fun pairOrHoldSensorLinearNdrRecording(")
        val admission = manager
            .substringAfter("private fun rawCaptureIngressIsCurrentLocked(")
            .substringBefore("private fun admitRawFrameTimestamp(")
        val tap = manager
            .substringAfter("private fun tapRecordingPreview(")
            .substringBefore("private fun writeDng(image: Image, session: RawWriterSessionToken)")
        val tapBarrier = manager
            .substringAfter("private fun awaitPreviewTapIdle(")
            .substringBefore("private fun drainAndCloseWriteQueue(")
        val callbackFailure = manager
            .substringAfter("private fun failStrictRawMasterIfCurrent(")
            .substringBefore("private fun markRawDropIfCurrent(")

        assertTrue(ingress.contains("rawCaptureIngress = snapshotRawCaptureIngressLocked("))
        assertTrue(ingress.contains("offerRawFrameIfCurrent(captureToken, img)"))
        assertFalse(ingress.contains("writeQueue.offer(img)"))
        assertTrue(ingress.contains("failStrictRawMasterIfCurrent("))
        assertFalse(ingress.contains("failStrictRawMaster(\""))
        assertTrue(admission.contains("token.session.generation == rawWriterSessionGeneration"))
        assertTrue(admission.contains("token.admissionGeneration == rawWriterAdmissionGeneration"))
        assertTrue(admission.contains("token.session.queue === writeQueue"))
        assertTrue(tap.contains("session.queue.size"))
        assertTrue(tap.contains("invokeRecordingPreviewProcessorIfCurrent("))
        assertTrue(tapBarrier.contains("previewTapExecutor.submit { Unit }"))
        assertTrue(tapBarrier.contains("previewTapBusy.get()"))
        assertTrue(callbackFailure.indexOf("sensorLinearNdrRecordingLifecycleLock.withLock") <
            callbackFailure.indexOf("rawWriterAdmissionLock.withLock"))
        assertTrue(callbackFailure.contains("rawCaptureIngressIsCurrentLocked(token)"))
    }

    @Test
    fun `split phase submission keeps Camera2 ingress live and stop cannot overtake publication`() {
        val manager = managerSource()
        val process = manager
            .substringAfter("private fun processSensorLinearNdrRecordingFrame(")
            .substringBefore("private fun sensorLinearNdrRetryDelayOrFailure(")
        val worker = manager
            .substringAfter("private fun drainSensorLinearNdrRecordingFrames()")
            .substringBefore("private fun processSensorLinearNdrRecordingFrame(")
        val accepted = worker
            .substringAfter("SensorLinearNdrRecordingSubmissionStatus.ACCEPTED ->")
            .substringBefore("SensorLinearNdrRecordingSubmissionStatus.RETRY_PENDING ->")
        val retry = worker
            .substringAfter("SensorLinearNdrRecordingSubmissionStatus.RETRY_PENDING ->")
            .substringBefore("SensorLinearNdrRecordingSubmissionStatus.FAILED ->")
        val failure = manager
            .substringAfter("private fun failSensorLinearNdrRecording(")
            .substringBefore("private fun finishSensorLinearNdrRecordingIngress(")
        val finish = manager
            .substringAfter("private fun finishSensorLinearNdrRecordingIngress(")
            .substringBefore("private fun takeAllSensorLinearNdrRecordingImages(")

        val leasePublication = process.indexOf(
            "sensorLinearNdrRecordingProcessorInFlight.compareAndSet(null, frame)"
        )
        val conservativeTail = process.indexOf(
            "sensorLinearNdrRecordingNativePending.set(true)",
            leasePublication
        )
        val firstLifecycleSectionEnd = process.indexOf(
            "val submission = try {",
            conservativeTail
        )
        val nativeCall = process.indexOf("processor(", firstLifecycleSectionEnd)
        val finalLifecycleSection = process.indexOf(
            "val published = sensorLinearNdrRecordingLifecycleLock.withLock",
            nativeCall
        )
        val exactPublication = process.indexOf("when (submission.status)", finalLifecycleSection)
        val leaseRelease = process.indexOf(
            "sensorLinearNdrRecordingProcessorInFlight.set(null)",
            exactPublication
        )
        assertTrue(leasePublication >= 0)
        assertTrue(conservativeTail > leasePublication)
        assertTrue(firstLifecycleSectionEnd > conservativeTail)
        assertTrue(nativeCall > conservativeTail)
        assertTrue(finalLifecycleSection > nativeCall)
        assertTrue(exactPublication > finalLifecycleSection)
        assertTrue(leaseRelease > exactPublication)
        assertFalse(
            process.substring(firstLifecycleSectionEnd, nativeCall)
                .contains("sensorLinearNdrRecordingLifecycleLock.withLock")
        )
        assertFalse(accepted.contains("sensorLinearNdrRecordingNativePending.set("))
        assertFalse(retry.contains("sensorLinearNdrRecordingRetryFrame.set("))
        assertTrue(failure.contains("sensorLinearNdrRecordingDiscardRequested.set(true)"))
        assertTrue(failure.contains("takeAllSensorLinearNdrRecordingImages()"))
        assertFalse(failure.contains("finishSensorLinearNdrRecordingIngress("))
        assertTrue(finish.contains("sensorLinearNdrRecordingAccepting.get()"))
        assertTrue(finish.contains("sensorLinearNdrRecordingNativePending.get()"))
        assertTrue(finish.contains("sensorLinearNdrRecordingProcessorInFlight.get() != null"))
        assertTrue(finish.contains("sensorLinearNdrRecordingOwnedFrames.get() != 0"))

        val lifecycleLock = ReentrantLock()
        val processorEntered = CountDownLatch(1)
        val releaseProcessor = CountDownLatch(1)
        val ingressCompleted = CountDownLatch(1)
        val nativePending = AtomicBoolean(false)
        val inFlight = java.util.concurrent.atomic.AtomicReference<Any?>(null)
        val lifecycleActive = AtomicBoolean(true)
        val frame = Any()
        val executor = Executors.newFixedThreadPool(3)
        try {
            val submission = executor.submit {
                lifecycleLock.withLock {
                    assertTrue(inFlight.compareAndSet(null, frame))
                    nativePending.set(true)
                }
                processorEntered.countDown()
                assertTrue(releaseProcessor.await(2, TimeUnit.SECONDS))
                lifecycleLock.withLock {
                    nativePending.set(true)
                    assertTrue(inFlight.compareAndSet(frame, null))
                }
            }
            assertTrue(processorEntered.await(2, TimeUnit.SECONDS))
            val ingress = executor.submit {
                lifecycleLock.withLock { ingressCompleted.countDown() }
            }
            assertTrue(
                "Camera2 ingress must not wait for the slow processor call",
                ingressCompleted.await(500, TimeUnit.MILLISECONDS)
            )
            ingress.get(2, TimeUnit.SECONDS)

            val stop = executor.submit<Boolean> {
                lifecycleLock.withLock {
                    if (inFlight.get() != null || nativePending.get()) {
                        false
                    } else {
                        lifecycleActive.set(false)
                        true
                    }
                }
            }
            assertFalse("in-flight/native ownership forbids lifecycle finish", stop.get(2, TimeUnit.SECONDS))
            assertTrue(lifecycleActive.get())
            releaseProcessor.countDown()
            submission.get(2, TimeUnit.SECONDS)
            assertTrue(inFlight.get() == null)
            assertTrue(nativePending.get())
            assertTrue(lifecycleActive.get())
        } finally {
            releaseProcessor.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `queued NDR frame reaches ring aware processor before standalone completion polling`() {
        val manager = managerSource()
        val worker = manager
            .substringAfter("private fun drainSensorLinearNdrRecordingFrames()")
            .substringBefore("private fun processSensorLinearNdrRecordingFrame(")
        val process = manager
            .substringAfter("private fun processSensorLinearNdrRecordingFrame(")
            .substringBefore("private fun sensorLinearNdrRetryDelayOrFailure(")

        val standalonePollGate = worker.indexOf(
            "if (sensorLinearNdrRecordingNativePending.get() &&"
        )
        val retryAbsenceGate = worker.indexOf(
            "sensorLinearNdrRecordingRetryFrame.get() == null",
            standalonePollGate
        )
        val queueEmptyGate = worker.indexOf(
            "sensorLinearNdrRecordingQueue.isEmpty()",
            retryAbsenceGate
        )
        val standalonePoll = worker.indexOf("completionPoller.invoke()", queueEmptyGate)
        val processorCall = worker.indexOf("processSensorLinearNdrRecordingFrame(frame)")

        assertTrue(standalonePollGate >= 0)
        assertTrue(retryAbsenceGate > standalonePollGate)
        assertTrue(queueEmptyGate > retryAbsenceGate)
        assertTrue(standalonePoll > queueEmptyGate)
        assertTrue(processorCall > standalonePoll)
        val beforeProcessor = process.substringBefore("val submission = try")
        val acceptedBranch = process
            .substringAfter("SensorLinearNdrRecordingSubmissionStatus.ACCEPTED ->")
            .substringBefore("SensorLinearNdrRecordingSubmissionStatus.RETRY_PENDING ->")
        val retryBranch = process
            .substringAfter("SensorLinearNdrRecordingSubmissionStatus.RETRY_PENDING ->")
            .substringBefore("SensorLinearNdrRecordingSubmissionStatus.FAILED ->")
        assertFalse(
            "processor entry must not reset a retained profile/preview retry cause",
            beforeProcessor.contains("transitionSensorLinearNdrRetryWindow(")
        )
        assertTrue(
            "a newly accepted native owner needs an independent deadline",
            acceptedBranch.contains("restartSensorLinearNdrRetryWindow(")
        )
        assertTrue(
            "same-cause RETRY_PENDING attempts must preserve their original deadline",
            retryBranch.contains("transitionSensorLinearNdrRetryWindow(")
        )
    }

    @Test
    fun `old RAW callback cannot charge a restarted NDR generation`() {
        val manager = managerSource()
        val ingress = manager
            .substringAfter("fun onRawFrameAvailable(reader: ImageReader)")
            .substringBefore("private fun pairOrHoldSensorLinearNdrRecording(")
        val ndrAdmission = manager
            .substringAfter("private fun snapshotSensorLinearNdrRecordingIngressLocked(")
            .substringBefore("private fun nextRawWriterGeneration(")
        val resultIngress = manager
            .substringAfter("fun onCaptureResult(")
            .substringBefore("fun buildRawBufferDescriptor(image: Image)")
        val pair = manager
            .substringAfter("private fun pairOrHoldSensorLinearNdrRecording(")
            .substringBefore("private fun flushSensorLinearNdrReadyFramesLocked()")
        val release = manager
            .substringAfter("private fun releaseSensorLinearNdrImage(")
            .substringBefore("private fun pairOrHoldIdleRawPreview(")

        val rawEntrySnapshot = ingress.indexOf("callbackObservedRawAdmissionBefore =")
        val ndrEntrySnapshot = ingress.indexOf("callbackObservedNdrAdmissionBefore =")
        val stableIdentityGate = ingress.indexOf(
            "callbackObservedRawAdmissionBefore === callbackObservedRawAdmissionAfter"
        )
        val snapshotLock = ingress.indexOf("sensorLinearNdrRecordingLifecycleLock.withLock")
        assertTrue(rawEntrySnapshot >= 0)
        assertTrue(ndrEntrySnapshot > rawEntrySnapshot)
        assertTrue(stableIdentityGate > ndrEntrySnapshot)
        assertTrue(snapshotLock > stableIdentityGate)
        val publishedIdentityGate = ndrAdmission.indexOf(
            "sensorLinearNdrRecordingIngressAdmission.get() !== callbackObservedAdmission"
        )
        val generationGateAtAdmission = ndrAdmission.indexOf(
            "callbackObservedAdmission.generation != sensorLinearNdrRecordingGeneration.get()"
        )
        assertTrue(publishedIdentityGate >= 0)
        assertTrue(generationGateAtAdmission > publishedIdentityGate)

        val resultEntrySnapshot = resultIngress.indexOf("callbackObservedNdrAdmission =")
        val resultLifecycleLock = resultIngress.indexOf(
            "sensorLinearNdrRecordingLifecycleLock.withLock"
        )
        val resultIdentityGate = resultIngress.indexOf(
            "publishedAdmission === callbackObservedNdrAdmission",
            resultLifecycleLock
        )
        val resultJoin = resultIngress.indexOf("resultLock.withLock", resultIdentityGate)
        assertTrue(resultEntrySnapshot >= 0)
        assertTrue(resultLifecycleLock > resultEntrySnapshot)
        assertTrue(resultIdentityGate > resultLifecycleLock)
        assertTrue(resultJoin > resultIdentityGate)
        val ownershipReservation = ingress.indexOf(
            "SensorLinearNdrRecordingIngressPolicy.tryReserveOwnedFrame("
        )
        val orderedAcquire = ingress.indexOf("reader.acquireNextImage()", ownershipReservation)
        assertTrue(ownershipReservation > snapshotLock)
        assertTrue(orderedAcquire > ownershipReservation)
        val lifecycleLock = pair.indexOf("sensorLinearNdrRecordingLifecycleLock.withLock")
        val generationGate = pair.indexOf(
            "generation != sensorLinearNdrRecordingGeneration.get()",
            lifecycleLock
        )
        val ownershipProof = pair.indexOf(
            "owned = sensorLinearNdrRecordingOwnedFrames.get()",
            generationGate
        )
        val joinLock = pair.indexOf("resultLock.withLock", ownershipProof)
        assertTrue(lifecycleLock >= 0)
        assertTrue(generationGate > lifecycleLock)
        assertTrue(ownershipProof > generationGate)
        assertTrue(joinLock > ownershipProof)
        assertFalse(pair.contains("sensorLinearNdrRecordingOwnedFrames.incrementAndGet()"))
        assertTrue(release.contains(
            "if (ingressGeneration != sensorLinearNdrRecordingGeneration.get()) return"
        ))

        var publishedAdmission: Any? = Any()
        var owned = 0
        val oldCallbackAdmission = publishedAdmission
        publishedAdmission = null
        publishedAdmission = Any()
        if (oldCallbackAdmission === publishedAdmission) owned += 1
        assertTrue("old callback must not charge new generation", owned == 0)

        val callbackBeforeOpen: Any? = null
        publishedAdmission = Any()
        assertFalse("pre-open callback must not become admitted", callbackBeforeOpen === publishedAdmission)
    }

    @Test
    fun `RAW result lineage completes pause joins but rejects restart and retired timestamps`() {
        val manager = managerSource()
        val resultIngress = manager
            .substringAfter("fun onCaptureResult(")
            .substringBefore("fun buildRawBufferDescriptor(image: Image)")
        val acceptedResultRegistration = manager
            .substringAfter("private fun registerAcceptedRawFrameResultLocked(")
            .substringBefore("private fun offerRawFrameAndRegisterResultLocked(")
        val finalization = manager
            .substringAfter("private fun finalizeRawCaptureResultIngress()")
            .substringBefore("private fun snapshotRawCaptureIngressLocked(")

        val rawEntrySnapshot = resultIngress.indexOf(
            "callbackObservedRawResultIdentityBefore ="
        )
        val lifecycleLock = resultIngress.indexOf(
            "sensorLinearNdrRecordingLifecycleLock.withLock"
        )
        val writerLock = resultIngress.indexOf("rawWriterAdmissionLock.withLock", lifecycleLock)
        val resultLock = resultIngress.indexOf("resultLock.withLock rawResultJoin@", writerLock)
        assertTrue(rawEntrySnapshot >= 0)
        assertTrue(lifecycleLock > rawEntrySnapshot)
        assertTrue(writerLock > lifecycleLock)
        assertTrue(resultLock > writerLock)
        assertTrue(resultIngress.contains("pendingRawIdentity.session.generation =="))
        assertTrue(resultIngress.contains(
            "callbackObservedRawResultIdentity.session.generation"
        ))
        assertTrue(resultIngress.contains("pendingRawIdentity.session.queue ==="))
        assertTrue(resultIngress.contains("callbackObservedRawResultIdentity.session.queue"))
        assertTrue(resultIngress.contains("timestamp >"))
        assertTrue(resultIngress.contains(
            "callbackObservedRawResultIdentity.retiredSensorTimestampFloorNs"
        ))
        assertTrue(resultIngress.contains("timestamp >= firstTimestampNs"))
        assertTrue(resultIngress.contains(
            "publishedRawResultIdentity === pendingRawIdentity"
        ))
        assertTrue(acceptedResultRegistration.contains(
            "rawCaptureFirstAcceptedSensorTimestampNs.compareAndSet(0L, timestamp)"
        ))
        assertTrue(acceptedResultRegistration.contains(
            "recentResultsByTimestamp.entries.removeAll { it.key < timestamp }"
        ))
        assertTrue(finalization.contains("rawRetiredSensorTimestampFloorNs = retiredFloorNs"))

        val writerSessionA = Any()
        val writerSessionB = Any()
        val pendingWriterSession = writerSessionA
        val callbackAfterResumeWriterSession = writerSessionA
        val callbackAfterRestartWriterSession = writerSessionB
        assertTrue(pendingWriterSession === callbackAfterResumeWriterSession)
        assertFalse(pendingWriterSession === callbackAfterRestartWriterSession)
        val retiredTimestamp = 1_000L
        val firstRestartedTimestamp = 2_000L
        assertTrue(retiredTimestamp < firstRestartedTimestamp)

        fun currentGenerationAccepts(
            timestampNs: Long,
            retiredFloorNs: Long,
            firstAcceptedTimestampNs: Long
        ): Boolean = timestampNs > retiredFloorNs &&
            (firstAcceptedTimestampNs == 0L || timestampNs >= firstAcceptedTimestampNs)

        assertFalse(currentGenerationAccepts(999L, retiredTimestamp, 0L))
        assertFalse(currentGenerationAccepts(retiredTimestamp, retiredTimestamp, 0L))
        // Before the first new Image, a newer queued result may enter the bounded cache. The first
        // accepted Image establishes the stronger barrier and purges/rejects every older timestamp.
        assertTrue(currentGenerationAccepts(1_500L, retiredTimestamp, 0L))
        assertFalse(currentGenerationAccepts(1_500L, retiredTimestamp, firstRestartedTimestamp))
        assertTrue(currentGenerationAccepts(
            firstRestartedTimestamp,
            retiredTimestamp,
            firstRestartedTimestamp
        ))
    }

    @Test
    fun `authoritative RAW metadata is exact while monitor fallback remains non consuming`() {
        val manager = managerSource()
        val exactResolver = manager
            .substringAfter("private fun resolveExactCaptureResult(imageTimestamp: Long)")
            .substringBefore("private fun awaitExactCaptureResult(")
        val exactWait = manager
            .substringAfter("private fun awaitExactCaptureResult(")
            .substringBefore("private fun resolveMonitorCaptureResult(imageTimestamp: Long)")
        val monitorResolver = manager
            .substringAfter("private fun resolveMonitorCaptureResult(imageTimestamp: Long)")
            .substringBefore("private fun stopDrainTimeoutMs()")

        assertTrue(exactResolver.contains("recentResultsByTimestamp[imageTimestamp]"))
        assertTrue(exactResolver.contains("exact.sessionGeneration != owner.cameraSessionGeneration"))
        assertTrue(exactResolver.contains("pendingRawCaptureResultsByTimestamp.remove(imageTimestamp)"))
        assertFalse(exactResolver.contains("minByOrNull"))
        assertFalse(exactResolver.contains("latestResult"))
        assertTrue(exactWait.contains("exactCaptureResultWaiter.await("))
        assertTrue(exactWait.contains("currentOwner.session !== expectedSession"))
        assertTrue(exactWait.contains("currentOwner.session.queue !== expectedSession.queue"))
        assertTrue(exactWait.contains("currentOwner.cameraSessionGeneration != camera.sessionGeneration.get()"))
        assertTrue(exactWait.contains("currentOwner !== owner"))
        assertTrue(exactWait.contains("exact.sessionGeneration != owner.cameraSessionGeneration"))
        assertTrue(exactWait.contains("exact.rawWriterSession !== expectedSession"))
        assertFalse(exactWait.contains("isCapturing.get()"))
        assertFalse(exactWait.contains("minByOrNull"))
        assertFalse(exactWait.contains("latestResult"))

        val nearestLookup = monitorResolver.indexOf("recentResultsByTimestamp.minByOrNull")
        val nearestReturn = monitorResolver.indexOf("return nearest.value.result", nearestLookup)
        assertTrue(nearestLookup >= 0)
        assertTrue(nearestReturn > nearestLookup)
        val nearestBranch = monitorResolver.substring(nearestLookup, nearestReturn)
        assertFalse(nearestBranch.contains("pendingRawCaptureResultsByTimestamp.remove"))
        assertFalse(nearestBranch.contains("recentResultsByTimestamp.remove"))

        val pendingOwnership = linkedMapOf(190L to "image-190", 200L to "image-200")
        val results = linkedMapOf(200L to "result-200")
        fun resolveAuthoritative(imageTimestamp: Long): String? {
            if (imageTimestamp !in pendingOwnership) return null
            val exact = results[imageTimestamp] ?: return null
            results.remove(imageTimestamp)
            pendingOwnership.remove(imageTimestamp)
            return exact
        }

        assertNull(resolveAuthoritative(190L))
        assertTrue("adjacent metadata must retain requesting Image ownership", 190L in pendingOwnership)
        assertTrue("adjacent metadata must retain its own Image ownership", 200L in pendingOwnership)
        assertTrue("adjacent metadata must not be consumed", 200L in results)

        assertEquals("result-200", resolveAuthoritative(200L))
        assertFalse("exact result consumption must retire its exact pending entry", 200L in pendingOwnership)
        assertFalse("exact result consumption must remove its exact cached result", 200L in results)
        assertTrue("exact consumption must not retire a different Image", 190L in pendingOwnership)
        assertNull(resolveAuthoritative(190L))
        assertTrue(190L in pendingOwnership)

        data class SimOwner(val session: Any, val cameraGeneration: Long)
        val expectedSession = Any()
        fun acceptsBeforeFirstProbe(
            owner: SimOwner,
            currentCameraGeneration: Long,
            exactResultGeneration: Long
        ): Boolean = owner.session === expectedSession &&
            owner.cameraGeneration == currentCameraGeneration &&
            exactResultGeneration == owner.cameraGeneration

        assertFalse(
            "an already-replaced owner must fail before the waiter adopts it",
            acceptsBeforeFirstProbe(SimOwner(Any(), 7L), 7L, 7L)
        )
        assertFalse(
            "an exact timestamp from an advanced Camera2 session must fail closed",
            acceptsBeforeFirstProbe(SimOwner(expectedSession, 6L), 7L, 6L)
        )
        assertTrue(acceptsBeforeFirstProbe(SimOwner(expectedSession, 7L), 7L, 7L))
    }

    @Test
    fun `DNG and NPRAW sidecar fail closed and sidecar retries until durable success`() {
        val manager = managerSource()
        val dng = manager
            .substringAfter("private fun writeDng(image: Image, session: RawWriterSessionToken)")
            .substringBefore("private fun configureCinemaDng")
        val compressed = manager
            .substringAfter("private fun writeCompressedFrame(")
            .substringBefore("private fun onCompressedFramePersisted(")
        val close = manager
            .substringAfter("private fun closeRawWriterAdmission()")
            .substringBefore("private fun finalizeRawCaptureResultIngress()")
        val finalization = manager
            .substringAfter("private fun finalizeRawCaptureResultIngress()")
            .substringBefore("private fun snapshotRawCaptureIngressLocked(")

        assertTrue(dng.contains("awaitExactCaptureResult(image.timestamp, session)"))
        assertFalse(dng.contains("resolveMonitorCaptureResult"))
        assertFalse(dng.contains("latestResult"))

        val sidecarGuard = compressed.indexOf("if (!developSidecarWritten.get())")
        val exactJoin = compressed.indexOf("awaitExactCaptureResult(image.timestamp, session)", sidecarGuard)
        val durableWrite = compressed.indexOf("writeNprawFrame(", exactJoin)
        val completionPublish = compressed.indexOf("developSidecarWritten.set(true)", durableWrite)
        assertTrue(sidecarGuard >= 0)
        assertTrue(exactJoin > sidecarGuard)
        assertTrue(durableWrite > exactJoin)
        assertTrue(completionPublish > durableWrite)
        assertFalse(compressed.contains("developSidecarWritten.compareAndSet(false, true)"))
        assertTrue(compressed.contains("retrying on the next frame"))
        assertTrue(compressed.contains("onFailure"))
        assertTrue(compressed.contains("activeCaptureFps.get().takeIf { it in 1..240 }"))
        assertFalse(compressed.contains("camera.params.value.fps.coerceIn(1, 240)"))
        assertFalse(compressed.contains("1_000_000_000L / minFrameIntervalNs"))

        assertTrue(close.contains("exactCaptureResultWaiter.signalChanged()"))
        assertTrue(finalization.contains("exactCaptureResultWaiter.signalChangedLocked()"))
    }

    @Test
    fun `idle callback cannot collapse ordered frames across recording start`() {
        val manager = managerSource()
        val ingress = manager
            .substringAfter("fun onRawFrameAvailable(reader: ImageReader)")
            .substringBefore("private fun pairOrHoldSensorLinearNdrRecording(")
        val acquisition = ingress
            .substringAfter("val img = try {")
            .substringBefore("} catch (e: IllegalStateException)")

        val lifecycleLock = acquisition.indexOf(
            "sensorLinearNdrRecordingLifecycleLock.withLock"
        )
        val writerLock = acquisition.indexOf("rawWriterAdmissionLock.withLock", lifecycleLock)
        val captureSnapshot = acquisition.indexOf(
            "rawCaptureIngress = snapshotRawCaptureIngressLocked(",
            writerLock
        )
        val acquireNext = acquisition.indexOf("reader.acquireNextImage()", captureSnapshot)
        assertTrue(lifecycleLock >= 0)
        assertTrue(writerLock > lifecycleLock)
        assertTrue(captureSnapshot > writerLock)
        assertTrue(acquireNext > captureSnapshot)
        assertFalse(acquisition.contains("reader.acquireLatestImage()"))

        val idleAdmission = ingress
            .substringAfter("if (!capturing) {")
            .substringBefore("// Pause/stop may close admission")
        val finalLifecycleLock = idleAdmission.indexOf(
            "sensorLinearNdrRecordingLifecycleLock.withLock"
        )
        val activeNdrGate = idleAdmission.indexOf(
            "sensorLinearNdrRecordingLifecycleActive.get()",
            finalLifecycleLock
        )
        val idleJoin = idleAdmission.indexOf("pairOrHoldIdleRawPreview(img)", activeNdrGate)
        assertTrue(finalLifecycleLock >= 0)
        assertTrue(activeNdrGate > finalLifecycleLock)
        assertTrue(idleJoin > activeNdrGate)

        val lock = ReentrantLock()
        val acquisitionEntered = CountDownLatch(1)
        val releaseAcquisition = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val callback = executor.submit<String> {
                lock.withLock {
                    acquisitionEntered.countDown()
                    assertTrue(releaseAcquisition.await(2, TimeUnit.SECONDS))
                    "NEXT"
                }
            }
            assertTrue(acquisitionEntered.await(2, TimeUnit.SECONDS))
            val recordingStart = executor.submit {
                lock.withLock { Unit }
            }
            assertFalse("recording start must not overtake RAW acquisition", recordingStart.isDone)
            releaseAcquisition.countDown()
            assertTrue(callback.get(2, TimeUnit.SECONDS) == "NEXT")
            recordingStart.get(2, TimeUnit.SECONDS)
        } finally {
            releaseAcquisition.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `Camera2 session advance rejects NDR and idle RAW final admission`() {
        val manager = managerSource()
        val resultIngress = manager
            .substringAfter("fun onCaptureResult(")
            .substringBefore("fun buildRawBufferDescriptor(image: Image)")
        val ndrProcess = manager
            .substringAfter("private fun processSensorLinearNdrRecordingFrame(")
            .substringBefore("private fun sensorLinearNdrRetryDelayOrFailure(")
        val ndrImageIngress = manager
            .substringAfter("private fun pairOrHoldSensorLinearNdrRecording(")
            .substringBefore("private fun flushSensorLinearNdrReadyFramesLocked()")
        val idleCurrent = manager
            .substringAfter("private fun isIdleRawPreviewSubmissionCurrentLocked(")
            .substringBefore("private fun maybeReserveRawOriginalExposureMeter(")
        val idleJoin = manager
            .substringAfter("private fun pairOrHoldIdleRawPreview(image: Image)")
            .substringBefore("private fun clearIdleRawPreviewFrames()")

        assertTrue(resultIngress.contains("sensorLinearNdrRecordingLifecycleLock.withLock"))
        assertTrue(resultIngress.contains(
            "val currentSessionGeneration = camera.sessionGeneration.get()"
        ))
        assertTrue(resultIngress.contains(
            "expectedSessionGeneration != currentSessionGeneration"
        ))
        assertTrue(resultIngress.contains(
            "Camera2 session advanced during ordered NDR result ingress"
        ))
        assertTrue(resultIngress.contains("if (sessionGeneration != expectedSessionGeneration)"))
        assertTrue(resultIngress.contains("DISCARDED_CALLBACK_SESSION_MISMATCH"))
        assertTrue(ndrImageIngress.contains("activeSessionMismatch"))
        assertTrue(ndrImageIngress.contains(
            "Camera2 session advanced during ordered NDR ingress"
        ))
        assertTrue(ndrImageIngress.contains("failSensorLinearNdrRecording("))
        assertTrue(idleCurrent.contains(
            "identity.sessionGeneration != camera.sessionGeneration.get()"
        ))
        assertTrue(idleJoin.contains(
            "presentation.sessionGeneration != currentSessionGeneration"
        ))
        val finalSessionGate = ndrProcess.indexOf(
            "expectedSessionGeneration != currentSessionGeneration"
        )
        val nativeCall = ndrProcess.indexOf("processor(", finalSessionGate)
        assertTrue(finalSessionGate >= 0)
        assertTrue(nativeCall > finalSessionGate)
        assertTrue(ndrProcess.substring(finalSessionGate, nativeCall).contains(
            "SensorLinearNdrRecordingSubmissionStatus.FAILED"
        ))
    }

    @Test
    fun `destroy closes admission but keeps NDR tail retirement executable`() {
        val manager = managerSource()
        val schedule = manager
            .substringAfter("private fun scheduleSensorLinearNdrRecordingWorker(")
            .substringBefore("private fun drainSensorLinearNdrRecordingFrames()")
        val worker = manager
            .substringAfter("private fun drainSensorLinearNdrRecordingFrames()")
            .substringBefore("private fun processSensorLinearNdrRecordingFrame(")
        val destroy = manager
            .substringAfter("private fun destroyLocked(): Boolean")
            .substringBefore("private fun awaitExecutorTerminationForDestroy(")

        assertFalse(schedule.contains("destroyRequested.get()"))
        assertFalse(schedule.contains("destroyed.get()"))
        assertFalse(worker.contains("!destroyed.get()"))
        val request = destroy.indexOf("destroyRequested.set(true)")
        val stop = destroy.indexOf("stopSensorLinearNdrRecordingIngress(", request)
        val quiescent = destroy.indexOf(
            "isSensorLinearNdrRecordingWorkerQuiescentForTeardown()",
            stop
        )
        val destroyed = destroy.indexOf("destroyed.set(true)", quiescent)
        val executorShutdown = destroy.indexOf(
            "sensorLinearNdrRecordingExecutor.shutdownNow()",
            destroyed
        )
        assertTrue(request >= 0)
        assertTrue(stop > request)
        assertTrue(quiescent > stop)
        assertTrue(destroyed > quiescent)
        assertTrue(executorShutdown > destroyed)
    }
}
