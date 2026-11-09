package com.neoproc.neoproccamx.ui

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestRestoreOwnershipTest {
    @Test
    fun `user mutation rejects an older asynchronous restore`() {
        val ownership = LatestRestoreOwnership()
        var hdrMode = "OFF"
        val staleRestore = ownership.begin()

        ownership.invalidate()
        hdrMode = "NDR10+"

        val committed = ownership.commitIfCurrent(staleRestore) {
            hdrMode = "OFF"
        }

        assertFalse(committed)
        assertEquals("NDR10+", hdrMode)
    }

    @Test
    fun `newest restore lease can commit`() {
        val ownership = LatestRestoreOwnership()
        var hdrMode = "OFF"
        ownership.begin()
        val newestRestore = ownership.begin()

        val committed = ownership.commitIfCurrent(newestRestore) {
            hdrMode = "NDR10+"
        }

        assertTrue(committed)
        assertEquals("NDR10+", hdrMode)
    }

    @Test
    fun `generation rejects an aba restore even when the visible value matches again`() {
        val ownership = LatestRestoreOwnership()
        var hdrMode = "OFF"
        val staleRestore = ownership.begin()

        ownership.invalidate()
        hdrMode = "NDR10+"
        ownership.invalidate()
        hdrMode = "OFF"

        assertFalse(ownership.commitIfCurrent(staleRestore) { hdrMode = "NDR10+" })
        assertEquals("OFF", hdrMode)
    }

    @Test
    fun `invalidation that wins before commit deterministically preserves user intent`() {
        val ownership = LatestRestoreOwnership()
        val restoreMayCommit = CountDownLatch(1)
        val restoreFinished = CountDownLatch(1)
        val staleRestore = ownership.begin()
        val hdrMode = AtomicReference("OFF")

        val worker = Thread {
            restoreMayCommit.await(5, TimeUnit.SECONDS)
            ownership.commitIfCurrent(staleRestore) { hdrMode.set("OFF") }
            restoreFinished.countDown()
        }
        worker.start()

        ownership.invalidate()
        hdrMode.set("NDR10+")
        restoreMayCommit.countDown()

        assertTrue(restoreFinished.await(5, TimeUnit.SECONDS))
        worker.join(5_000L)
        assertFalse(worker.isAlive)
        assertEquals("NDR10+", hdrMode.get())
    }
}
