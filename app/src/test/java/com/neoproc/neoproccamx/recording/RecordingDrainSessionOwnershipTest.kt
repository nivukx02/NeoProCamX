package com.neoproc.neoproccamx.recording

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingDrainSessionOwnershipTest {
    @Test
    fun rapidStopStartCannotOpenUntilEveryOldProducerHasActuallyExited() {
        val owner = RecordingDrainSessionOwnership()
        val first = owner.open()
        assertTrue(first.attachVideoDrain {})
        assertTrue(first.attachAudioDrain {})
        assertTrue(first.attachTicker {})

        assertSame(first, owner.retire(first))
        assertFails<IllegalStateException> { owner.open() }

        first.completeVideoDrain()
        first.completeAudioDrain()
        first.completeTicker()
        assertTrue(first.awaitTermination(0L).complete)

        val replacement = owner.open()
        assertTrue(replacement.generation > first.generation)
        assertTrue(owner.isCurrent(replacement))
    }

    @Test
    fun lateOutputFormatAndBufferFromRetiredSessionFailClosed() {
        val owner = RecordingDrainSessionOwnership()
        val first = owner.open()
        first.bindMuxerQueueGeneration(7L)
        assertTrue(first.publishVideoTrack(0))
        owner.retire(first)

        assertFalse(first.acceptsProducerOutput())
        assertFalse(first.publishVideoTrack(4))
        assertFalse(first.publishAudioTrack(1))
        assertFalse(first.ownsMuxerQueueGeneration(7L))

        val replacement = owner.open()
        replacement.bindMuxerQueueGeneration(8L)
        assertTrue(replacement.publishVideoTrack(0))
        assertEquals(0, replacement.videoTrackIndex())
        assertTrue(replacement.ownsMuxerQueueGeneration(8L))
        assertFalse(first.ownsMuxerQueueGeneration(8L))
    }

    @Test
    fun staleVideoAndAudioDrainCancellationIsPerSessionAndIdempotent() {
        val owner = RecordingDrainSessionOwnership()
        val first = owner.open()
        val videoCancels = AtomicInteger(0)
        val audioCancels = AtomicInteger(0)
        assertTrue(first.attachVideoDrain { videoCancels.incrementAndGet() })
        assertTrue(first.attachAudioDrain { audioCancels.incrementAndGet() })

        assertSame(first, owner.retire(first))
        assertNull(owner.retire(first))
        first.retire()
        assertEquals(1, videoCancels.get())
        assertEquals(1, audioCancels.get())

        first.completeVideoDrain()
        first.completeAudioDrain()
        val replacement = owner.open()
        assertTrue(owner.isCurrent(replacement))
        assertFalse(owner.isCurrent(first))
    }

    @Test
    fun staleCompletionCannotPublishTracksOrMutateReplacementAudioState() {
        val owner = RecordingDrainSessionOwnership()
        val first = owner.open()
        first.audioCodecTerminalShutdown.set(true)
        owner.retire(first)

        val replacement = owner.open()
        assertFalse(replacement.audioCodecTerminalShutdown.get())
        assertFalse(first.publishVideoTrack(9))
        assertTrue(replacement.publishVideoTrack(2))
        first.completeVideoDrain()
        first.completeAudioDrain()
        first.completeTicker()

        assertEquals(2, replacement.videoTrackIndex())
        assertTrue(owner.isCurrent(replacement))
        assertFalse(replacement.audioCodecTerminalShutdown.get())
    }

    @Test
    fun releaseWithoutAttachedJobsIsImmediatelyCompleteAndRepeatSafe() {
        val owner = RecordingDrainSessionOwnership()
        val session = owner.open()

        assertSame(session, owner.retireCurrent())
        assertNull(owner.retireCurrent())
        assertTrue(session.awaitTermination(0L).complete)

        val replacement = owner.open()
        assertTrue(owner.isCurrent(replacement))
    }

    private inline fun <reified T : Throwable> assertFails(block: () -> Unit) {
        try {
            block()
        } catch (throwable: Throwable) {
            assertTrue(
                "Expected ${T::class.java.name}, got ${throwable::class.java.name}",
                throwable is T
            )
            return
        }
        throw AssertionError("Expected ${T::class.java.name}")
    }
}
