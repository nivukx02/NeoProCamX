package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Soak-style proof (pure policy, no real I/O) for the spec's "no dropped frames within the
 * queue budget, honest backpressure beyond it" requirement.
 *
 * These tests are written to FAIL on a real regression — they assert the exact budget
 * boundary (so a `<`→`<=` flip on the admission test is caught), exercise the additive
 * open-image formula (queued + activeWrites), and assert that an over-budget frame is both
 * refused AND attributed to a concrete, non-UNKNOWN drop reason (never a silent loss).
 */
class RawVideoBackpressureSoakTest {

    private inline fun withStrictHighEndOnly(enabled: Boolean, block: () -> Unit) {
        val previous = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.strictHighEndOnly = enabled
        try {
            block()
        } finally {
            HighEndQualityPolicy.strictHighEndOnly = previous
        }
    }

    @Test
    fun `admission is exact at the budget boundary`() {
        withStrictHighEndOnly(false) {
            val readerMaxImages = 8
            val budget = RawVideoBackpressurePolicy.maxRetainedOpenImages(readerMaxImages)
            // Strictly below budget -> retained (no drop). Catches an off-by-one shrink.
            assertTrue(
                "queued=budget-1 must be retained",
                RawVideoBackpressurePolicy.shouldRetainAcquiredFrame(readerMaxImages, budget - 1, 0)
            )
            // At/above budget -> refused (honest drop). Catches a `<`→`<=` regression.
            assertFalse(
                "queued=budget must be refused",
                RawVideoBackpressurePolicy.shouldRetainAcquiredFrame(readerMaxImages, budget, 0)
            )
            assertFalse(
                "queued=budget+1 must be refused",
                RawVideoBackpressurePolicy.shouldRetainAcquiredFrame(readerMaxImages, budget + 1, 0)
            )
        }
    }

    @Test
    fun `open image accounting includes active writes`() {
        withStrictHighEndOnly(false) {
            val readerMaxImages = 8
            val budget = RawVideoBackpressurePolicy.maxRetainedOpenImages(readerMaxImages)
            // queued + activeWrites == budget must drop — locks the additive currentOpen formula.
            assertFalse(
                RawVideoBackpressurePolicy.shouldRetainAcquiredFrame(
                    readerMaxImages = readerMaxImages,
                    queuedImages = budget - 2,
                    activeWrites = 2
                )
            )
            // One slot of headroom with a write in flight is still admitted.
            assertTrue(
                RawVideoBackpressurePolicy.shouldRetainAcquiredFrame(
                    readerMaxImages = readerMaxImages,
                    queuedImages = budget - 3,
                    activeWrites = 2
                )
            )
        }
    }

    @Test
    fun `sustained run drops zero within budget and accounts every over-budget frame`() {
        withStrictHighEndOnly(false) {
            val readerMaxImages = 12
            val budget = RawVideoBackpressurePolicy.maxRetainedOpenImages(readerMaxImages)
            var dropsWithinBudget = 0
            var accountedDrops = 0
            // Walk the depth across and beyond the budget repeatedly.
            repeat(6000) { frame ->
                val queued = frame % (budget + 3)
                val retained = RawVideoBackpressurePolicy.shouldRetainAcquiredFrame(readerMaxImages, queued, 0)
                if (queued < budget) {
                    if (!retained) dropsWithinBudget++
                } else {
                    assertFalse("over-budget frame must be refused", retained)
                    val reason = RawVideoBackpressurePolicy.resolveDropReason(readerMaxImages, queued, 0, false)
                    assertTrue(
                        "an over-budget drop is attributed, not silent",
                        reason != RawVideoBackpressurePolicy.FrameDropReason.UNKNOWN
                    )
                    accountedDrops++
                }
            }
            assertEquals("no frame dropped while within budget", 0, dropsWithinBudget)
            assertTrue("over-budget frames were exercised and accounted", accountedDrops > 0)
        }
    }
}
