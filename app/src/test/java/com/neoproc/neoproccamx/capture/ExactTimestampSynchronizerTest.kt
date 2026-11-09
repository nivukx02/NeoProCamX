package com.neoproc.neoproccamx.capture

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExactTimestampSynchronizerTest {
    @Test
    fun exactTimestamp_isTheOnlyAcceptedIdentity() {
        val synchronizer = ExactTimestampSynchronizer<String>(retainedValueCapacity = 4)
        synchronizer.offer(timestampNs = 1_000L, value = "frame-1000")

        assertNull(synchronizer.pollExact(timestampNs = 999L))
        assertNull(synchronizer.pollExact(timestampNs = 1_001L))
        assertEquals("frame-1000", synchronizer.pollExact(timestampNs = 1_000L))
    }

    @Test
    fun resultDeliveredBeforeImage_isReturnedWithoutPolling() = runBlocking {
        val synchronizer = ExactTimestampSynchronizer<String>(retainedValueCapacity = 4)
        synchronizer.offer(timestampNs = 42L, value = "result")

        assertEquals("result", synchronizer.awaitExact(timestampNs = 42L, timeoutMillis = 100L))
    }

    @Test
    fun imageDeliveredBeforeResult_waitsForTheExactTimestampOnly() = runBlocking {
        val synchronizer = ExactTimestampSynchronizer<String>(retainedValueCapacity = 4)
        val waitingImage = async(start = CoroutineStart.UNDISPATCHED) {
            synchronizer.awaitExact(timestampNs = 42L, timeoutMillis = 1_000L)
        }

        synchronizer.offer(timestampNs = 41L, value = "wrong-result")
        synchronizer.offer(timestampNs = 42L, value = "exact-result")

        assertEquals("exact-result", waitingImage.await())
        assertEquals("wrong-result", synchronizer.pollExact(timestampNs = 41L))
    }

    @Test
    fun boundedRetention_evictsOldestUnmatchedResult() {
        val synchronizer = ExactTimestampSynchronizer<String>(retainedValueCapacity = 2)
        synchronizer.offer(1L, "one")
        synchronizer.offer(2L, "two")
        synchronizer.offer(3L, "three")

        assertNull(synchronizer.pollExact(1L))
        assertEquals("two", synchronizer.pollExact(2L))
        assertEquals("three", synchronizer.pollExact(3L))
    }

    @Test
    fun newerAuthoritativeTimestamp_supersedesOlderWaiterAndRetainsExactValue() = runBlocking {
        val synchronizer = ExactTimestampSynchronizer<String>(retainedValueCapacity = 8)
        val staleImage = async(start = CoroutineStart.UNDISPATCHED) {
            synchronizer.awaitExactOrNewer(timestampNs = 10L, timeoutMillis = 1_000L)
        }

        synchronizer.offer(timestampNs = 20L, value = "still-frame")

        assertEquals(
            ExactOrNewerTimestampResult.Superseded(newerTimestampNs = 20L),
            staleImage.await()
        )
        assertEquals(
            ExactOrNewerTimestampResult.Superseded(newerTimestampNs = 20L),
            synchronizer.awaitExactOrNewer(timestampNs = 19L, timeoutMillis = 1_000L)
        )
        assertEquals(
            ExactOrNewerTimestampResult.Exact("still-frame"),
            synchronizer.awaitExactOrNewer(timestampNs = 20L, timeoutMillis = 1_000L)
        )
    }

    @Test
    fun olderAuthoritativeTimestamp_doesNotSupersedeNewerImageWaiter() = runBlocking {
        val synchronizer = ExactTimestampSynchronizer<String>(retainedValueCapacity = 8)
        val waitingImage = async(start = CoroutineStart.UNDISPATCHED) {
            synchronizer.awaitExactOrNewer(timestampNs = 42L, timeoutMillis = 1_000L)
        }

        synchronizer.offer(timestampNs = 41L, value = "older-result")
        assertFalse(waitingImage.isCompleted)
        synchronizer.offer(timestampNs = 42L, value = "exact-result")

        assertEquals(ExactOrNewerTimestampResult.Exact("exact-result"), waitingImage.await())
        assertEquals("older-result", synchronizer.pollExact(41L))
    }

    @Test
    fun absentExactOrNewerTimestamp_returnsExplicitTimeout() = runBlocking {
        val synchronizer = ExactTimestampSynchronizer<String>(retainedValueCapacity = 4)

        val result = synchronizer.awaitExactOrNewer(timestampNs = 42L, timeoutMillis = 10L)

        assertTrue(result === ExactOrNewerTimestampResult.TimedOut)
    }
}
