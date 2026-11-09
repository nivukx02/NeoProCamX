package com.neoproc.neoproccamx.recording

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class RecordingPublicationIoGateTest {
    @Test
    fun captureWaitsForTheAlreadyAdmittedChunkAndBlocksNewChunks() {
        val gate = RecordingPublicationIoGate()
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val workers = Executors.newFixedThreadPool(2)
        var capture: RecordingPublicationIoGate.CaptureLease? = null
        try {
            val first = workers.submit {
                gate.backgroundChunk {
                    entered.countDown()
                    check(finish.await(3, TimeUnit.SECONDS))
                }
            }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            capture = gate.acquireCapture()
            assertFalse(capture.awaitBackgroundIdle(0))
            val secondAttempt = CountDownLatch(1)
            val secondEntered = AtomicBoolean(false)
            val second = workers.submit {
                gate.backgroundChunk(shouldContinue = {
                    secondAttempt.countDown()
                    true
                }) { secondEntered.set(true) }
            }
            assertTrue(secondAttempt.await(3, TimeUnit.SECONDS))
            assertFalse(secondEntered.get())
            finish.countDown()
            first.get(3, TimeUnit.SECONDS)
            assertTrue(capture.awaitBackgroundIdle(1_000))
            assertEquals(0, gate.snapshot().activeBackgroundChunks)
            assertFalse(secondEntered.get())
            capture.close()
            second.get(3, TimeUnit.SECONDS)
            assertTrue(secondEntered.get())
            assertEquals(2L, gate.snapshot().completedBackgroundChunks)
        } finally {
            finish.countDown()
            capture?.close()
            workers.shutdownNow()
        }
    }

    @Test
    fun overlappingCaptureOwnersAndDuplicateCloseCannotResumePublicationEarly() {
        val gate = RecordingPublicationIoGate()
        val first = gate.acquireCapture()
        val second = gate.acquireCapture()
        assertEquals(2, gate.snapshot().captureOwners)
        second.close()
        second.close()
        assertEquals(1, gate.snapshot().captureOwners)
        assertTrue(first.awaitBackgroundIdle(0))
        first.close()
        assertEquals(0, gate.snapshot().captureOwners)
        gate.backgroundChunk { assertEquals(1, gate.snapshot().activeBackgroundChunks) }
    }

    @Test
    fun closingAnOldLeaseCannotReleaseANewerCapture() {
        val gate = RecordingPublicationIoGate()
        val old = gate.acquireCapture()
        old.close()
        gate.acquireCapture().use {
            old.close()
            assertEquals(1, gate.snapshot().captureOwners)
            assertTrue(runCatching { old.awaitBackgroundIdle(0) }.exceptionOrNull() is IllegalStateException)
        }
    }

    @Test
    fun backgroundFailureAlwaysRetiresItsPermit() {
        val gate = RecordingPublicationIoGate()
        val failure = runCatching {
            gate.backgroundChunk<Unit> { throw IOException("injected write failure") }
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(0, gate.snapshot().activeBackgroundChunks)
        gate.acquireCapture().use { assertTrue(it.awaitBackgroundIdle(0)) }
    }

    @Test
    fun cancellationWhilePausedDoesNotWaitForCaptureCompletion() {
        val gate = RecordingPublicationIoGate()
        val capture = gate.acquireCapture()
        val checked = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        val ran = AtomicBoolean(false)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val result = worker.submit<Throwable?> {
                runCatching {
                    gate.backgroundChunk(shouldContinue = {
                        checked.countDown()
                        !cancelled.get()
                    }) { ran.set(true) }
                }.exceptionOrNull()
            }
            assertTrue(checked.await(3, TimeUnit.SECONDS))
            cancelled.set(true)
            assertTrue(result.get(3, TimeUnit.SECONDS) is CancellationException)
            assertFalse(ran.get())
            assertEquals(1, gate.snapshot().captureOwners)
            assertEquals(0, gate.snapshot().activeBackgroundChunks)
        } finally {
            capture.close()
            worker.shutdownNow()
        }
    }

    @Test
    fun copyResumesAtTheExactBytePositionWithUnchangedDigest() {
        val gate = RecordingPublicationIoGate()
        val source = ByteArray(2 * RecordingPublicationIoGate.MAX_BACKGROUND_CHUNK_BYTES + 17) {
            (it * 17 + 31).toByte()
        }
        val firstWrite = CountDownLatch(1)
        val nextChunkAttempt = CountDownLatch(1)
        val lease = AtomicReference<RecordingPublicationIoGate.CaptureLease?>()
        val output = object : ByteArrayOutputStream() {
            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                assertTrue(length <= RecordingPublicationIoGate.MAX_BACKGROUND_CHUNK_BYTES)
                assertEquals(0, gate.snapshot().captureOwners)
                super.write(buffer, offset, length)
                if (lease.get() == null) {
                    lease.set(gate.acquireCapture())
                    firstWrite.countDown()
                }
            }
        }
        val worker = Executors.newSingleThreadExecutor()
        try {
            val task = worker.submit<VerifiedArtifactCopyEvidence> {
                VerifiedArtifactCopy.copyAndMeasure(
                    ByteArrayInputStream(source), output,
                    shouldContinue = {
                        if (lease.get() != null) nextChunkAttempt.countDown()
                        true
                    },
                    backgroundIo = gate
                )
            }
            assertTrue(firstWrite.await(3, TimeUnit.SECONDS))
            assertTrue(nextChunkAttempt.await(3, TimeUnit.SECONDS))
            assertTrue(requireNotNull(lease.get()).awaitBackgroundIdle(1_000))
            assertEquals(RecordingPublicationIoGate.MAX_BACKGROUND_CHUNK_BYTES, output.size())
            assertFalse(task.isDone)
            lease.get()?.close()
            val evidence = task.get(3, TimeUnit.SECONDS)
            assertArrayEquals(source, output.toByteArray())
            VerifiedArtifactCopy.requireExact(
                evidence,
                VerifiedArtifactCopy.measure(ByteArrayInputStream(source), { true }, gate),
                "capture-priority copy"
            )
            assertEquals(0, gate.snapshot().activeBackgroundChunks)
        } finally {
            lease.get()?.close()
            worker.shutdownNow()
        }
    }

    @Test
    fun independentReadBackAlsoPausesDuringCapture() {
        val gate = RecordingPublicationIoGate()
        val capture = gate.acquireCapture()
        val attempted = CountDownLatch(1)
        val read = AtomicBoolean(false)
        val worker = Executors.newSingleThreadExecutor()
        try {
            val task = worker.submit<VerifiedArtifactCopyEvidence> {
                val input = object : ByteArrayInputStream(byteArrayOf(1, 2, 3)) {
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                        read.set(true)
                        assertEquals(0, gate.snapshot().captureOwners)
                        return super.read(buffer, offset, length)
                    }
                }
                VerifiedArtifactCopy.measure(input, { attempted.countDown(); true }, gate)
            }
            assertTrue(attempted.await(3, TimeUnit.SECONDS))
            assertFalse(read.get())
            capture.close()
            assertEquals(3L, task.get(3, TimeUnit.SECONDS).bytes)
        } finally {
            capture.close()
            worker.shutdownNow()
        }
    }

    @Test
    fun timedOutHandoffDoesNotReleaseCaptureOrHideAnInflightWrite() {
        val gate = RecordingPublicationIoGate()
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        var capture: RecordingPublicationIoGate.CaptureLease? = null
        try {
            val task = worker.submit {
                gate.backgroundChunk { entered.countDown(); check(finish.await(3, TimeUnit.SECONDS)) }
            }
            assertTrue(entered.await(3, TimeUnit.SECONDS))
            capture = gate.acquireCapture()
            assertFalse(capture.awaitBackgroundIdle(10))
            assertEquals(1, gate.snapshot().activeBackgroundChunks)
            assertEquals(1, gate.snapshot().captureOwners)
            finish.countDown()
            task.get(3, TimeUnit.SECONDS)
            assertTrue(capture.awaitBackgroundIdle(1_000))
        } finally {
            finish.countDown()
            capture?.close()
            worker.shutdownNow()
        }
    }
}
