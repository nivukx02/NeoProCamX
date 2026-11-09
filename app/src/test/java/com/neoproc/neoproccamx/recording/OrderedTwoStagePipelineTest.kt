package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Locks the concurrency contract of [runInOrderTwoStage] — the scheduler behind the offline RAW→LOG
 * single-worker CPU+GPU overlap. Proves strict in-order delivery, genuine two-thread overlap, and
 * exception-safety (no deadlock) on the JVM without any GPU. Every test has a hard timeout so a
 * regression that deadlocks fails fast instead of hanging the suite.
 */
class OrderedTwoStagePipelineTest {

    @Test(timeout = 10_000)
    fun `delivers all items to stage2 in production order`() {
        val n = 25
        val produced = AtomicInteger(0)
        val consumed = ArrayList<Int>()
        runInOrderTwoStage<Int>(
            stage1 = { produced.getAndIncrement().takeIf { it < n } },
            stage2 = { consumed.add(it) }
        )
        assertEquals((0 until n).toList(), consumed)
    }

    @Test(timeout = 10_000)
    fun `empty stream never invokes stage2`() {
        var stage2Calls = 0
        runInOrderTwoStage<Int>(stage1 = { null }, stage2 = { stage2Calls++ })
        assertEquals(0, stage2Calls)
    }

    @Test(timeout = 10_000)
    fun `stage1 and stage2 run on different threads so the lanes genuinely overlap`() {
        var stage1Thread: String? = null
        var stage2Thread: String? = null
        var produced = false
        runInOrderTwoStage<Int>(
            stage1 = {
                if (produced) {
                    null
                } else {
                    produced = true
                    stage1Thread = Thread.currentThread().name
                    1
                }
            },
            stage2 = { stage2Thread = Thread.currentThread().name }
        )
        assertNotEquals(stage1Thread, stage2Thread)
        assertTrue(
            "producer must run on the dedicated develop thread, was $stage1Thread",
            stage1Thread!!.contains("raw-develop")
        )
    }

    @Test(timeout = 10_000)
    fun `stage1 failure propagates after earlier items are consumed`() {
        val consumed = ArrayList<Int>()
        val produced = AtomicInteger(0)
        try {
            runInOrderTwoStage<Int>(
                stage1 = {
                    when (produced.getAndIncrement()) {
                        0 -> 10
                        1 -> 20
                        else -> throw IllegalStateException("stage1-boom")
                    }
                },
                stage2 = { consumed.add(it) }
            )
            fail("expected the stage1 failure to propagate")
        } catch (e: IllegalStateException) {
            assertEquals("stage1-boom", e.message)
        }
        assertEquals(listOf(10, 20), consumed)
    }

    @Test(timeout = 10_000)
    fun `stage2 failure interrupts an infinite producer without deadlock`() {
        val consumed = ArrayList<Int>()
        val produced = AtomicInteger(0)
        try {
            runInOrderTwoStage<Int>(
                // Never returns null: only the consumer's failure + interrupt can stop this.
                stage1 = { produced.getAndIncrement() },
                stage2 = { item ->
                    consumed.add(item)
                    if (item == 1) throw IllegalStateException("stage2-boom")
                }
            )
            fail("expected the stage2 failure to propagate")
        } catch (e: IllegalStateException) {
            assertEquals("stage2-boom", e.message)
        }
        // Item 0 consumed cleanly, item 1 threw; the infinite producer must be interrupted + joined so this
        // returns within the timeout (proves no deadlock on the SynchronousQueue hand-off).
        assertEquals(0, consumed.first())
        assertTrue(consumed.contains(1))
    }
}
