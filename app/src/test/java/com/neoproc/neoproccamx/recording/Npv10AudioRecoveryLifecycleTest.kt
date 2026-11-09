package com.neoproc.neoproccamx.recording

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ownership-only JVM checks. Actual microphone/AAC recovery is covered on the device. */
class Npv10AudioRecoveryLifecycleTest {
    @Test
    fun inactiveRecoveryHasNoAudioAndDoesNotInventAnAudit() {
        val recorder = Npv10AudioSidecarRecorder()
        assertNull(recorder.stopAndPreserveForRecovery())
        assertFalse(recorder.isActive)
        assertFalse(recorder.isPrepared)
    }

    @Test
    fun terminatedWorkerStillReturnsItsAuditAndRetiresExactlyOnce() {
        val recorder = withRetiredWorker()
        val audit = Npv10AudioSidecarAudit(3L, 0L, 64_000L, 900L, true, true)
        reference<Npv10AudioSidecarAudit>(recorder, "completedAudit").set(audit)
        assertFalse("Worker is exited, but its session still needs retirement", recorder.isActive)
        assertSame(audit, recorder.stopAndPreserveForRecovery())
        assertNull(field(recorder, "worker"))
        assertNull(recorder.stopAndPreserveForRecovery())
    }

    @Test
    fun poisonedVideoCannotTrimAudioToAnEarlierAlignmentTarget() {
        val recorder = withRetiredWorker()
        val target = field(recorder, "terminalAlignmentTargetUs") as AtomicLong
        val tolerance = field(recorder, "terminalAlignmentToleranceUs") as AtomicLong
        target.set(40_000L)
        tolerance.set(20_000L)
        val audit = Npv10AudioSidecarAudit(5L, 0L, 106_665L, 1500L, true, true)
        reference<Npv10AudioSidecarAudit>(recorder, "completedAudit").set(audit)
        assertSame(audit, recorder.stopAndPreserveForRecovery())
        assertEquals(-1L, target.get())
        assertEquals(-1L, tolerance.get())
    }

    @Test
    fun failedAacWorkerIsRetiredAndItsOriginalCauseRemainsVisible() {
        val recorder = withRetiredWorker()
        val cause = IllegalStateException("actual AAC failure")
        reference<Throwable>(recorder, "terminalFailure").set(cause)
        val observed = runCatching { recorder.stopAndPreserveForRecovery() }.exceptionOrNull()
        assertTrue(observed is IllegalStateException)
        assertSame(cause, observed?.cause)
        assertNull(field(recorder, "worker"))
        assertNull(recorder.stopAndPreserveForRecovery())
    }

    @Test
    fun audioCleanupFailureNeverReplacesThePrimaryVideoFailure() {
        val recorder = withRetiredWorker()
        val videoFailure = IllegalStateException("storage backpressure")
        val audioFailure = IllegalStateException("AAC ended without a footer")
        reference<Throwable>(recorder, "terminalFailure").set(audioFailure)
        recorder.preserveAfterVideoFailure(videoFailure)
        assertEquals(1, videoFailure.suppressed.size)
        assertSame(audioFailure, videoFailure.suppressed.single().cause)
        assertNull(field(recorder, "worker"))
        assertNull(field(recorder, "activeMaster"))
        assertFalse(recorder.isPrepared)
    }

    private fun withRetiredWorker(): Npv10AudioSidecarRecorder =
        Npv10AudioSidecarRecorder().also { recorder ->
            val retired = Thread {}.apply { start(); join() }
            Npv10AudioSidecarRecorder::class.java.getDeclaredField("worker")
                .apply { isAccessible = true }.set(recorder, retired)
        }

    private fun field(recorder: Npv10AudioSidecarRecorder, name: String): Any? =
        Npv10AudioSidecarRecorder::class.java.getDeclaredField(name)
            .apply { isAccessible = true }.get(recorder)

    @Suppress("UNCHECKED_CAST")
    private fun <T> reference(recorder: Npv10AudioSidecarRecorder, name: String): AtomicReference<T?> =
        field(recorder, name) as AtomicReference<T?>
}
