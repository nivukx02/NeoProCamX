package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorTimestampTimebaseReconcilerTest {
    @Test
    fun `queued boottime image cannot be reclassified by closeness to monotonic clock`() {
        // Reproduces the 2026-09-07 physical Cinema failure. A storage/callback stall made
        // the same sensor frame look closer to MONOTONIC than BOOTTIME. Callback latency
        // is not evidence of a clock change and must never move the recording timestamp.
        val imageTimestampNs = 18_525_219_685_356L
        val clockSeparationNs = 785_252_083L
        val monotonicNowNs = imageTimestampNs + 50_000_000L
        val boottimeNowNs = monotonicNowNs + clockSeparationNs
        val reconciler = SensorTimestampTimebaseReconciler()
        reconciler.reset(SensorTimestampClockDomain.BOOTTIME)
        reconciler.observeReference(imageTimestampNs, monotonicNowNs, boottimeNowNs)

        assertEquals(
            imageTimestampNs,
            reconciler.normalizeToReference(imageTimestampNs, monotonicNowNs, boottimeNowNs)
        )
        assertEquals(0L, reconciler.snapshot().lastAppliedOffsetNs)
    }

    @Test
    fun `both plausible clocks remain ambiguous without frame identity evidence`() {
        assertEquals(
            SensorTimestampClockDomain.SHARED_OR_AMBIGUOUS,
            SensorTimestampClockDomainPolicy.classify(
                timestampNs = 19_000_000_000L,
                monotonicNowNs = 19_050_000_000L,
                boottimeNowNs = 19_835_252_083L
            )
        )
    }

    @Test
    fun `old exact capture result is retained beyond a burst of sixteen frames`() {
        val reconciler = SensorTimestampTimebaseReconciler()
        reconciler.reset(SensorTimestampClockDomain.BOOTTIME)
        val first = 100_000_000_000L
        repeat(80) { index ->
            val timestamp = first + index * 40_000_000L
            reconciler.observeReference(timestamp, timestamp - 780_000_000L, timestamp + 5_000_000L)
        }
        val delayed = first + 40_000_000L
        assertEquals(
            delayed,
            reconciler.normalizeToReference(delayed, first + 2_415_000_000L, first + 3_200_000_000L)
        )
    }

    @Test
    fun `subsecond real clock translation requires observed capture identity`() {
        val reconciler = SensorTimestampTimebaseReconciler()
        reconciler.reset(SensorTimestampClockDomain.BOOTTIME)
        val monotonicNow = 100_000_000_000L
        val bootNow = monotonicNow + 785_252_083L
        val rawImageTimestamp = monotonicNow - 5_000_000L
        val captureTimestamp = bootNow - 5_000_000L
        reconciler.observeReference(captureTimestamp, monotonicNow, bootNow)

        assertEquals(captureTimestamp,
            reconciler.normalizeToReference(rawImageTimestamp, monotonicNow, bootNow))
        assertEquals(1L, reconciler.snapshot().normalizedCount)
    }

    @Test
    fun `unproven delayed frame is not moved to a fabricated reference timestamp`() {
        val reconciler = SensorTimestampTimebaseReconciler()
        reconciler.reset(SensorTimestampClockDomain.BOOTTIME)
        val imageTimestamp = 100_000_000_000L
        assertEquals(imageTimestamp,
            reconciler.normalizeToReference(imageTimestamp, imageTimestamp + 50_000_000L,
                imageTimestamp + 835_252_083L))
        assertEquals(0L, reconciler.snapshot().normalizedCount)
    }

    @Test
    fun `frame arrival jitter does not alter same-domain twenty five fps cadence`() {
        val reconciler = SensorTimestampTimebaseReconciler()
        reconciler.reset(SensorTimestampClockDomain.BOOTTIME)
        val start = 100_000_000_000L
        val separation = 785_252_083L
        var last = start - 40_000_000L
        repeat(10_000) { index ->
            val timestamp = start + index * 40_000_000L
            val delay = ((index * 104_729L) % 2_000L) * 1_000_000L
            val bootNow = timestamp + delay
            val monoNow = bootNow - separation
            reconciler.observeReference(timestamp, monoNow, bootNow)
            val actual = reconciler.normalizeToReference(timestamp, monoNow, bootNow)
            assertEquals("Frame $index changed with callback delay $delay", timestamp, actual)
            assertEquals(40_000_000L, actual - last)
            last = actual
        }
        assertEquals(0L, reconciler.snapshot().normalizedCount)
    }

    @Test
    fun `generation reset discards old capture identities`() {
        val reconciler = SensorTimestampTimebaseReconciler()
        reconciler.reset(SensorTimestampClockDomain.BOOTTIME)
        val monoNow = 100_000_000_000L
        val bootNow = monoNow + 785_252_083L
        reconciler.observeReference(bootNow - 5_000_000L, monoNow, bootNow)
        reconciler.reset(SensorTimestampClockDomain.BOOTTIME)
        assertEquals(monoNow - 5_000_000L,
            reconciler.normalizeToReference(monoNow - 5_000_000L, monoNow, bootNow))
    }

    @Test
    fun `boottime capture reference normalizes monotonic image and snaps exact identity`() {
        val reconciler = SensorTimestampTimebaseReconciler()
        val captureTimestampNs = BOOTTIME_NOW_NS - 5_000_000L
        reconciler.observeReference(captureTimestampNs, MONOTONIC_NOW_NS, BOOTTIME_NOW_NS)

        // The two clock calls are deliberately skewed by 80us. The translated value must snap to
        // the exact Camera2 identity instead of creating a second physical frame in the registry.
        val canonical = reconciler.normalizeToReference(
            timestampNs = MONOTONIC_NOW_NS - 5_000_000L,
            monotonicNowNs = MONOTONIC_NOW_NS,
            boottimeNowNs = BOOTTIME_NOW_NS + 80_000L
        )

        assertEquals(captureTimestampNs, canonical)
        assertEquals(SensorTimestampClockDomain.BOOTTIME, reconciler.snapshot().referenceDomain)
        assertEquals(1L, reconciler.snapshot().referenceSnapCount)
    }

    @Test
    fun `monotonic capture reference normalizes boottime image`() {
        val reconciler = SensorTimestampTimebaseReconciler()
        val captureTimestampNs = MONOTONIC_NOW_NS - 7_000_000L
        reconciler.observeReference(captureTimestampNs, MONOTONIC_NOW_NS, BOOTTIME_NOW_NS)

        val canonical = reconciler.normalizeToReference(
            timestampNs = BOOTTIME_NOW_NS - 7_000_000L,
            monotonicNowNs = MONOTONIC_NOW_NS,
            boottimeNowNs = BOOTTIME_NOW_NS
        )

        assertEquals(captureTimestampNs, canonical)
        assertEquals(1L, reconciler.snapshot().normalizedCount)
    }

    @Test
    fun `same or ambiguous clock remains unchanged`() {
        val reconciler = SensorTimestampTimebaseReconciler()
        val monotonicNowNs = 10_000_000_000L
        val boottimeNowNs = monotonicNowNs + 100_000_000L
        val timestampNs = monotonicNowNs - 2_000_000L

        reconciler.observeReference(timestampNs, monotonicNowNs, boottimeNowNs)

        assertEquals(
            timestampNs,
            reconciler.normalizeToReference(timestampNs, monotonicNowNs, boottimeNowNs)
        )
        assertEquals(
            SensorTimestampClockDomain.SHARED_OR_AMBIGUOUS,
            reconciler.snapshot().referenceDomain
        )
    }

    @Test
    fun `invalid and unresolved timestamps fail open without fabricated translation`() {
        val reconciler = SensorTimestampTimebaseReconciler()

        assertEquals(0L, reconciler.normalizeToReference(0L, MONOTONIC_NOW_NS, BOOTTIME_NOW_NS))
        assertEquals(
            MONOTONIC_NOW_NS,
            reconciler.normalizeToReference(MONOTONIC_NOW_NS, 0L, BOOTTIME_NOW_NS)
        )
        assertEquals(2L, reconciler.snapshot().unresolvedCount)
    }

    @Test
    fun `reference hint handles image arriving before first capture callback`() {
        val reconciler = SensorTimestampTimebaseReconciler()
        reconciler.reset(referenceHint = SensorTimestampClockDomain.BOOTTIME)

        assertEquals(
            BOOTTIME_NOW_NS - 9_000_000L,
            reconciler.normalizeToReference(
                timestampNs = MONOTONIC_NOW_NS - 9_000_000L,
                monotonicNowNs = MONOTONIC_NOW_NS,
                boottimeNowNs = BOOTTIME_NOW_NS
            )
        )
    }

    @Test
    fun `suspend offset is applied without changing frame cadence`() {
        val first = SensorTimestampClockDomainPolicy.translate(
            timestampNs = MONOTONIC_NOW_NS - 40_000_000L,
            source = SensorTimestampClockDomain.MONOTONIC,
            target = SensorTimestampClockDomain.BOOTTIME,
            monotonicNowNs = MONOTONIC_NOW_NS,
            boottimeNowNs = BOOTTIME_NOW_NS
        )
        val second = SensorTimestampClockDomainPolicy.translate(
            timestampNs = MONOTONIC_NOW_NS,
            source = SensorTimestampClockDomain.MONOTONIC,
            target = SensorTimestampClockDomain.BOOTTIME,
            monotonicNowNs = MONOTONIC_NOW_NS,
            boottimeNowNs = BOOTTIME_NOW_NS
        )

        assertEquals(40_000_000L, second - first)
        assertEquals(BOOTTIME_NOW_NS, second)
    }

    @Test
    fun `translation saturates instead of overflowing`() {
        val translated = SensorTimestampClockDomainPolicy.translate(
            timestampNs = Long.MAX_VALUE - 4L,
            source = SensorTimestampClockDomain.MONOTONIC,
            target = SensorTimestampClockDomain.BOOTTIME,
            monotonicNowNs = 1L,
            boottimeNowNs = Long.MAX_VALUE
        )

        assertEquals(Long.MAX_VALUE, translated)
        assertTrue(translated > 0L)
    }

    private companion object {
        const val MONOTONIC_NOW_NS = 2_600_000_000_000L
        const val BOOTTIME_NOW_NS = 5_000_000_000_000L
    }
}
