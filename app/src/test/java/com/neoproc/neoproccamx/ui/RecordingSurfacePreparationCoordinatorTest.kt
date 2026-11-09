package com.neoproc.neoproccamx.ui

import java.io.File
import java.util.concurrent.CancellationException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingSurfacePreparationCoordinatorTest {

    @Test
    fun `teardown seal permanently rejects new prearm transaction bodies`() {
        val coordinator = RecordingSurfacePreparationCoordinator()
        val calls = AtomicInteger(0)

        assertEquals(
            41,
            runBlocking {
                coordinator.runPrearmTransaction {
                    calls.incrementAndGet()
                    41
                }
            }
        )

        val seal = coordinator.sealForTeardown()
        assertEquals(
            RecordingSurfacePreparationCoordinator.TeardownQuiescence.PROVEN,
            seal.awaitQuiescence(timeoutMillis = 0L)
        )
        assertThrows(CancellationException::class.java) {
            runBlocking {
                coordinator.runPrearmTransaction {
                    calls.incrementAndGet()
                }
            }
        }
        assertEquals(1, calls.get())
    }

    @Test
    fun `retirement proof waits for transaction active before teardown seal`() {
        val coordinator = RecordingSurfacePreparationCoordinator()
        val executor = Executors.newFixedThreadPool(2)
        val transactionEntered = CountDownLatch(1)
        val allowTransactionExit = CountDownLatch(1)
        try {
            val transaction = executor.submit {
                runBlocking {
                    coordinator.runPrearmTransaction {
                        transactionEntered.countDown()
                        assertTrue(allowTransactionExit.await(2, TimeUnit.SECONDS))
                    }
                }
            }
            assertTrue(transactionEntered.await(2, TimeUnit.SECONDS))

            val seal = coordinator.sealForTeardown()
            val proof = executor.submit<RecordingSurfacePreparationCoordinator.TeardownQuiescence> {
                seal.awaitQuiescence(timeoutMillis = 2_000L)
            }
            Thread.sleep(50L)
            assertFalse("Retirement was proven while prearm still ran", proof.isDone)

            allowTransactionExit.countDown()
            transaction.get(2, TimeUnit.SECONDS)
            assertEquals(
                RecordingSurfacePreparationCoordinator.TeardownQuiescence.PROVEN,
                proof.get(2, TimeUnit.SECONDS)
            )
        } finally {
            allowTransactionExit.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `retirement proof times out fail closed and can be retried`() {
        val coordinator = RecordingSurfacePreparationCoordinator()
        val executor = Executors.newSingleThreadExecutor()
        val transactionEntered = CountDownLatch(1)
        val allowTransactionExit = CountDownLatch(1)
        try {
            val transaction = executor.submit {
                runBlocking {
                    coordinator.runPrearmTransaction {
                        transactionEntered.countDown()
                        assertTrue(allowTransactionExit.await(2, TimeUnit.SECONDS))
                    }
                }
            }
            assertTrue(transactionEntered.await(2, TimeUnit.SECONDS))
            val seal = coordinator.sealForTeardown()

            assertEquals(
                RecordingSurfacePreparationCoordinator.TeardownQuiescence.TIMED_OUT,
                seal.awaitQuiescence(timeoutMillis = 10L)
            )
            allowTransactionExit.countDown()
            transaction.get(2, TimeUnit.SECONDS)
            assertEquals(
                RecordingSurfacePreparationCoordinator.TeardownQuiescence.PROVEN,
                seal.awaitQuiescence(timeoutMillis = 100L)
            )
        } finally {
            allowTransactionExit.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `transaction queued behind active owner cannot begin after teardown seal`() {
        val coordinator = RecordingSurfacePreparationCoordinator()
        val executor = Executors.newFixedThreadPool(2)
        val activeEntered = CountDownLatch(1)
        val allowActiveExit = CountDownLatch(1)
        val queuedBodyCalls = AtomicInteger(0)
        try {
            val active = executor.submit {
                runBlocking {
                    coordinator.runPrearmTransaction {
                        activeEntered.countDown()
                        assertTrue(allowActiveExit.await(2, TimeUnit.SECONDS))
                    }
                }
            }
            assertTrue(activeEntered.await(2, TimeUnit.SECONDS))
            val queued = executor.submit {
                assertThrows(CancellationException::class.java) {
                    runBlocking {
                        coordinator.runPrearmTransaction {
                            queuedBodyCalls.incrementAndGet()
                        }
                    }
                }
            }

            val seal = coordinator.sealForTeardown()
            allowActiveExit.countDown()
            active.get(2, TimeUnit.SECONDS)
            queued.get(2, TimeUnit.SECONDS)

            assertEquals(0, queuedBodyCalls.get())
            assertEquals(
                RecordingSurfacePreparationCoordinator.TeardownQuiescence.PROVEN,
                seal.awaitQuiescence(timeoutMillis = 100L)
            )
        } finally {
            allowActiveExit.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `recording start waits until the complete prearm cleanup transaction exits`() {
        val coordinator = RecordingSurfacePreparationCoordinator()
        val executor = Executors.newFixedThreadPool(2)
        val prearmEntered = CountDownLatch(1)
        val allowPrearmCleanup = CountDownLatch(1)
        val startPassedBarrier = CountDownLatch(1)
        val events = CopyOnWriteArrayList<String>()
        try {
            val prearm = executor.submit {
                runBlocking {
                    coordinator.runPrearmTransaction {
                        events += "prearm-prepare"
                        prearmEntered.countDown()
                        assertTrue(allowPrearmCleanup.await(2, TimeUnit.SECONDS))
                        events += "prearm-cleanup"
                    }
                }
            }
            assertTrue(prearmEntered.await(2, TimeUnit.SECONDS))

            val recordingStart = executor.submit {
                runBlocking { coordinator.awaitPrearmCompletion() }
                events += "recording-start-prepare"
                startPassedBarrier.countDown()
            }

            assertFalse(
                "Recording start crossed the barrier while prearm still owned cleanup",
                startPassedBarrier.await(100, TimeUnit.MILLISECONDS)
            )
            allowPrearmCleanup.countDown()
            prearm.get(2, TimeUnit.SECONDS)
            recordingStart.get(2, TimeUnit.SECONDS)

            assertEquals(
                listOf("prearm-prepare", "prearm-cleanup", "recording-start-prepare"),
                events.toList()
            )
        } finally {
            allowPrearmCleanup.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `published recording start and racing prearm never overlap encoder ownership`() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(500) {
                val coordinator = RecordingSurfacePreparationCoordinator()
                val handoff = RecordingStartupHandoff()
                val ready = CountDownLatch(2)
                val go = CountDownLatch(1)
                val activePrearmMutation = AtomicInteger(0)
                val startOwnsEncoder = AtomicBoolean(false)
                val ownershipViolations = AtomicInteger(0)

                val prearm = executor.submit {
                    ready.countDown()
                    assertTrue(go.await(2, TimeUnit.SECONDS))
                    runBlocking {
                        coordinator.runPrearmTransaction {
                            // Mirrors prearmRecordingSurfaceNow(): the ownership check is inside the
                            // transaction, after recording start has published through tryBeginStart().
                            if (!handoff.isStarting) {
                                if (startOwnsEncoder.get()) ownershipViolations.incrementAndGet()
                                activePrearmMutation.incrementAndGet()
                                Thread.yield()
                                if (startOwnsEncoder.get()) ownershipViolations.incrementAndGet()
                                // This is the destructive prearm cleanup boundary. It must finish
                                // before start can publish ownership of its prepared encoder surface.
                                if (startOwnsEncoder.get()) ownershipViolations.incrementAndGet()
                                activePrearmMutation.decrementAndGet()
                            }
                        }
                    }
                }
                val recordingStart = executor.submit {
                    ready.countDown()
                    assertTrue(go.await(2, TimeUnit.SECONDS))
                    assertTrue(handoff.tryBeginStart())
                    runBlocking { coordinator.awaitPrearmCompletion() }
                    if (activePrearmMutation.get() != 0) ownershipViolations.incrementAndGet()
                    startOwnsEncoder.set(true)
                    Thread.yield()
                    if (activePrearmMutation.get() != 0) ownershipViolations.incrementAndGet()
                    startOwnsEncoder.set(false)
                    handoff.completeStart(backendActive = false) { false }
                }

                assertTrue(ready.await(2, TimeUnit.SECONDS))
                go.countDown()
                prearm.get(2, TimeUnit.SECONDS)
                recordingStart.get(2, TimeUnit.SECONDS)
                assertEquals(0, ownershipViolations.get())
                assertEquals(0, activePrearmMutation.get())
                assertFalse(startOwnsEncoder.get())
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `camera view model serializes full prearm and crosses barrier before reuse or prepare`() {
        val source = File(
            "src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt"
        ).readText()

        val prearmRequest = source
            .substringAfter("private fun requestRecordingSurfacePrearm(")
            .substringBefore("private fun requestNpv10CapturePrearm(")
        val transactionIndex = prearmRequest.indexOf(
            "recordingSurfacePreparationCoordinator.runPrearmTransaction {"
        )
        val prearmCallIndex = prearmRequest.indexOf(
            "prearmRecordingSurfaceNow(refreshSession = refreshSession)",
            transactionIndex
        )
        assertTrue(transactionIndex >= 0)
        assertTrue(prearmCallIndex > transactionIndex)
        assertEquals(
            "Every prearm invocation must enter the serialization transaction",
            1,
            Regex("prearmRecordingSurfaceNow\\(refreshSession = refreshSession\\)")
                .findAll(source)
                .count()
        )

        val prearmBody = source
            .substringAfter("private fun prearmRecordingSurfaceNow(refreshSession: Boolean): Boolean {")
            .substringBefore("private fun scheduleRecordingSurfacePrearmRetry()")
        val ownershipGuardIndex = prearmBody.indexOf(
            "if (!shouldKeepRecordingSurfacePrearmed()) return false"
        )
        val firstPrepareIndex = prearmBody.indexOf("gpuRecording.prepare()")
        val destructiveCleanupIndex = prearmBody.indexOf("gpuRecording.discardPreparedSession()")
        assertTrue(ownershipGuardIndex >= 0)
        assertTrue(firstPrepareIndex > ownershipGuardIndex)
        assertTrue(destructiveCleanupIndex > ownershipGuardIndex)

        val lifecycle = File(
            "src/main/java/com/neoproc/neoproccamx/ui/RecordingStopRestartLifecycle.kt"
        ).readText()
        val startAdmission = lifecycle
            .substringAfter("fun requestStart() {")
            .substringBefore("fun registerStartupCancellationAction")
        assertTrue(startAdmission.indexOf("startupHandoff.tryBeginStart(") >= 0)
        assertTrue(startAdmission.indexOf("beginStartWithPublicationIsolation()") >
            startAdmission.indexOf("startupHandoff.tryBeginStart("))
        val isolatedStart = lifecycle
            .substringAfter("private fun beginStartWithPublicationIsolation() {")
            .substringBefore("private fun releasePublicationCaptureLease()")
        assertTrue(isolatedStart.indexOf("RecordingPublicationIoGate.process.acquireCapture()") >= 0)
        assertTrue(isolatedStart.indexOf("beginClaimedStart()") >
            isolatedStart.indexOf("publicationCaptureLease.compareAndSet(null, lease)"))

        val startBody = source
            .substringAfter("private fun startRecordingInternal() {")
            .substringBefore("private suspend fun awaitCameraRecordingState(")
        val gateIndex = startBody.indexOf("startExecutionGate.await()")
        val publicationBarrierIndex = startBody.indexOf("awaitPublicationIdleBeforeStart()", gateIndex)
        val barrierIndex = startBody.indexOf(
            "recordingSurfacePreparationCoordinator.awaitPrearmCompletion()",
            gateIndex
        )
        val staleSessionCleanupIndex = startBody.indexOf(
            "gpuRecording.discardPreparedSession()",
            gateIndex
        )
        val reuseEvaluationIndex = startBody.indexOf(
            "val expectedPrearmSignature = currentRecordingPrearmSignature(recordingParityPlan)",
            gateIndex
        )
        val startPrepareIndex = startBody.indexOf("gpuRecording.prepare()", reuseEvaluationIndex)
        assertTrue(gateIndex >= 0)
        assertTrue(publicationBarrierIndex > gateIndex)
        assertTrue(barrierIndex > publicationBarrierIndex)
        assertTrue(barrierIndex > gateIndex)
        assertTrue(staleSessionCleanupIndex > barrierIndex)
        assertTrue(reuseEvaluationIndex > barrierIndex)
        assertTrue(startPrepareIndex > reuseEvaluationIndex)

        val skipReason = source
            .substringAfter("private fun idleRecordingSurfacePrearmSkipReason(): String?")
            .substringBefore("private fun shouldKeepRecordingSurfacePrearmed()")
        assertTrue(
            skipReason.contains(
                "if (recordingStopRestartLifecycle.isStarting) return \"recording-starting\""
            )
        )

        val releaseBody = source
            .substringAfter("private fun releasePrearmedRecordingSurface(")
            .substringBefore("fun isRecordingAdmissionReadyForStart()")
        val ownerGateIndex = releaseBody.indexOf(
            "if (recordingStopRestartLifecycle.isStarting && !preparationOwner)"
        )
        val surfaceMutationIndex = releaseBody.indexOf("prearmedRecordingSurface = null")
        val preparedSessionReleaseIndex = releaseBody.indexOf("gpuRecording.discardPreparedSession()")
        val persistentSurfaceReleaseIndex = releaseBody.indexOf(
            "gpuRecording.releasePersistentInputSurface()"
        )
        assertTrue(releaseBody.contains("preparationOwner: Boolean = false"))
        assertTrue(ownerGateIndex >= 0)
        assertTrue(surfaceMutationIndex > ownerGateIndex)
        assertTrue(preparedSessionReleaseIndex > ownerGateIndex)
        assertTrue(persistentSurfaceReleaseIndex > ownerGateIndex)
        assertTrue(
            "Coordinator-owned prearm cleanup must be authorized explicitly",
            prearmBody.contains(
                "releasePrearmedRecordingSurface(\n" +
                    "                        refreshSession = false,\n" +
                    "                        preparationOwner = true"
            )
        )

        val startPrearmRetirement = startBody
            .substringAfter(
                "val expectedPrearmSignature = currentRecordingPrearmSignature(recordingParityPlan)"
            )
            .substringBefore("val prepareStartElapsedMs = SystemClock.elapsedRealtime()")
        assertEquals(
            "Every start-owned prearm retirement must pass the owner gate",
            Regex("releasePrearmedRecordingSurface\\(")
                .findAll(startPrearmRetirement)
                .count(),
            Regex("preparationOwner = true")
                .findAll(startPrearmRetirement)
                .count()
        )
        assertTrue(startPrearmRetirement.contains("preparationOwner = true"))
    }

    @Test
    fun `prearm and recording start hold one immutable feed path per transaction`() {
        val source = File(
            "src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt"
        ).readText()

        val prearmBody = source
            .substringAfter("private fun prearmRecordingSurfaceNow(refreshSession: Boolean): Boolean {")
            .substringBefore("private fun scheduleRecordingSurfacePrearmRetry()")
        assertTrue(prearmBody.contains("val prearmFeedPathLease ="))
        assertTrue(prearmBody.contains("actualFeedPathOverride = prearmFeedPathLease"))
        assertTrue(
            prearmBody.contains(
                "prepareGpuRecordingForPrearmedVideoSession(prearmFeedPathLease)"
            )
        )
        assertTrue(prearmBody.contains("val parityPlan = prearmParityPlan"))

        val preparationBody = source
            .substringAfter(
                "private fun prepareGpuRecordingForPrearmedVideoSession(\n" +
                    "        feedPathLease: RecordingFeedPath"
            )
            .substringBefore("private fun currentRecordingPrearmSignature(")
        assertEquals(
            2,
            Regex("actualFeedPathOverride = feedPathLease")
                .findAll(preparationBody)
                .count()
        )

        val startBody = source
            .substringAfter("private fun startRecordingInternal() {")
            .substringBefore("private suspend fun awaitCameraRecordingState(")
        assertTrue(startBody.contains("val recordingFeedPathLease = initialParityPlan.feedPath"))
        assertEquals(
            2,
            Regex("actualFeedPathOverride = recordingFeedPathLease")
                .findAll(startBody)
                .count()
        )
        assertEquals(
            2,
            Regex("prearmedRecordingFeedPath == recordingParityPlan.feedPath")
                .findAll(startBody)
                .count()
        )
    }

    @Test
    fun `debug readiness cannot observe a partially bound prearm surface`() {
        val source = File(
            "src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt"
        ).readText()
        val readiness = source
            .substringAfter("fun isRecordingSurfacePrearmedForDebugStart(): Boolean {")
            .substringBefore("private fun isCurrentPrearmReusableForPlan(")

        val inFlightGate = readiness.indexOf("if (recordingSurfacePrearmInFlight.get()) return false")
        val planRead = readiness.indexOf("val parityPlan = activeRecordingPipelineParityPlan()")
        assertTrue(inFlightGate >= 0)
        assertTrue(planRead > inFlightGate)
    }
}
