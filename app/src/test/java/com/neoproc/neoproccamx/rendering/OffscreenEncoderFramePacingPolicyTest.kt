package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OffscreenEncoderFramePacingPolicyTest {
    @Test
    fun `phase pacer selects twenty four frames per second from thirty fps camera timestamps`() {
        var state = OffscreenEncoderFramePacingPolicy.reset(targetFps = 24)
        var submitted = 0
        repeat(300) { index ->
            val timestampNs = index * (1_000_000_000L / 30L)
            val decision = OffscreenEncoderFramePacingPolicy.resolve(timestampNs, 24, state)
            state = decision.state
            if (decision.submit) submitted += 1
        }

        assertEquals(240, submitted)
    }

    @Test
    fun `phase pacer keeps every frame when target matches thirty fps camera timestamps`() {
        var state = OffscreenEncoderFramePacingPolicy.reset(targetFps = 30)
        var submitted = 0
        repeat(300) { index ->
            val timestampNs = index * (1_000_000_000L / 30L)
            val decision = OffscreenEncoderFramePacingPolicy.resolve(timestampNs, 30, state)
            state = decision.state
            if (decision.submit) submitted += 1
        }

        assertEquals(300, submitted)
    }

    @Test
    fun `phase pacer keeps native twenty four fps camera timestamps with small jitter`() {
        var state = OffscreenEncoderFramePacingPolicy.reset(targetFps = 24)
        var submitted = 0
        repeat(240) { index ->
            val jitterNs = when (index % 4) {
                1 -> -2_500_000L
                2 -> 1_500_000L
                3 -> -1_000_000L
                else -> 0L
            }
            val timestampNs = index * (1_000_000_000L / 24L) + jitterNs
            val decision = OffscreenEncoderFramePacingPolicy.resolve(timestampNs, 24, state)
            state = decision.state
            if (decision.submit) submitted += 1
        }

        assertEquals(240, submitted)
    }

    @Test
    fun `phase pacer skips frames before the next due presentation time`() {
        var state = OffscreenEncoderFramePacingPolicy.reset(targetFps = 24)
        val first = OffscreenEncoderFramePacingPolicy.resolve(1_000_000_000L, 24, state)
        state = first.state
        val early = OffscreenEncoderFramePacingPolicy.resolve(1_020_000_000L, 24, state)
        val due = OffscreenEncoderFramePacingPolicy.resolve(1_050_000_000L, 24, early.state)

        assertTrue(first.submit)
        assertFalse(early.submit)
        assertTrue(due.submit)
    }

    @Test
    fun `accepted offscreen frames use constant target cadence timestamps`() {
        var state = OffscreenEncoderFramePacingPolicy.reset(targetFps = 25)
        val first = OffscreenEncoderFramePacingPolicy.resolve(1_000_000_000L, 25, state)
        state = first.state
        val second = OffscreenEncoderFramePacingPolicy.resolve(1_092_000_000L, 25, state)

        assertTrue(first.submit)
        assertTrue(second.submit)
        assertEquals(1_000_000_000L, first.submissionPresentationTimeNs)
        assertEquals(1_040_000_000L, second.submissionPresentationTimeNs)
    }

    @Test
    fun `accepted offscreen timestamps remain monotonic through jitter`() {
        var state = OffscreenEncoderFramePacingPolicy.reset(targetFps = 25)
        var previousSubmittedPts = -1L
        repeat(100) { index ->
            val jitterNs = when (index % 5) {
                1 -> -3_000_000L
                2 -> 2_000_000L
                3 -> -1_000_000L
                else -> 0L
            }
            val timestampNs = 2_000_000_000L + index * 40_000_000L + jitterNs
            val decision = OffscreenEncoderFramePacingPolicy.resolve(timestampNs, 25, state)
            state = decision.state
            if (decision.submit) {
                if (previousSubmittedPts > 0L) {
                    assertEquals(previousSubmittedPts + 40_000_000L, decision.submissionPresentationTimeNs)
                }
                previousSubmittedPts = decision.submissionPresentationTimeNs
            }
        }
    }

    @Test
    fun `one to one recorder cadence preserves every jittered sensor master`() {
        var state = OffscreenEncoderFramePacingPolicy.reset(targetFps = 25)
        val sourceDeltasNs = listOf(0L, 45_000_000L, 79_000_000L, 117_000_000L, 152_000_000L)
        val baseNs = 2_000_000_000L
        val submittedPts = sourceDeltasNs.map { deltaNs ->
            OffscreenEncoderFramePacingPolicy.resolveOneToOne(
                presentationTimeNs = baseNs + deltaNs,
                targetFps = 25,
                state = state
            ).also {
                assertTrue(it.submit)
                state = it.state
            }.submissionPresentationTimeNs
        }

        assertEquals(
            listOf(
                baseNs,
                baseNs + 40_000_000L,
                baseNs + 80_000_000L,
                baseNs + 120_000_000L,
                baseNs + 160_000_000L
            ),
            submittedPts
        )
    }

    @Test
    fun `one to one recorder cadence remains positive without a source timestamp`() {
        val decision = OffscreenEncoderFramePacingPolicy.resolveOneToOne(
            presentationTimeNs = 0L,
            targetFps = 25,
            state = OffscreenEncoderFramePacingPolicy.reset(25)
        )

        assertTrue(decision.submit)
        assertEquals(1L, decision.submissionPresentationTimeNs)
    }

    @Test
    fun `delayed first live recorder frame continues one interval after readiness`() {
        val readinessPtsNs = 802_000_000_000_000L
        val state = OffscreenEncoderFramePacingPolicy.continueAfterReadiness(
            readinessPresentationTimeNs = readinessPtsNs,
            targetFps = 25
        )

        val firstLive = OffscreenEncoderFramePacingPolicy.resolveOneToOne(
            presentationTimeNs = readinessPtsNs + 2_000_000_000L,
            targetFps = 25,
            state = state
        )

        assertTrue(firstLive.submit)
        assertTrue(firstLive.readinessHandoffRebased)
        assertEquals(readinessPtsNs + 40_000_000L, firstLive.submissionPresentationTimeNs)
        assertEquals(
            firstLive.submissionPresentationTimeNs,
            firstLive.state.lastSubmittedPresentationTimeNs
        )
        assertFalse(firstLive.state.awaitingFirstLiveFrame)

        val secondLive = OffscreenEncoderFramePacingPolicy.resolveOneToOne(
            presentationTimeNs = readinessPtsNs + 2_041_000_000L,
            targetFps = 25,
            state = firstLive.state
        )
        assertFalse(secondLive.readinessHandoffRebased)
        assertEquals(
            firstLive.submissionPresentationTimeNs + 40_000_000L,
            secondLive.submissionPresentationTimeNs
        )
    }

    @Test
    fun `readiness handoff remains monotonic when source timestamp precedes readiness clock`() {
        val readinessPtsNs = 802_000_000_000_000L
        val state = OffscreenEncoderFramePacingPolicy.continueAfterReadiness(
            readinessPresentationTimeNs = readinessPtsNs,
            targetFps = 25
        )

        val firstLive = OffscreenEncoderFramePacingPolicy.resolveOneToOne(
            presentationTimeNs = readinessPtsNs - 1_000_000_000L,
            targetFps = 25,
            state = state
        )

        assertTrue(firstLive.submit)
        assertTrue(firstLive.readinessHandoffRebased)
        assertTrue(firstLive.submissionPresentationTimeNs > readinessPtsNs)
        assertEquals(readinessPtsNs + 40_000_000L, firstLive.submissionPresentationTimeNs)
    }

    @Test
    fun `readiness handoff excludes session preparation delay from codec timeline`() {
        val readinessPtsNs = 802_000_000_000_000L
        val delaysNs = listOf(0L, 20_000_000L, 40_000_000L, 900_000_000L, 5_000_000_000L)

        delaysNs.forEach { delayNs ->
            var state = OffscreenEncoderFramePacingPolicy.continueAfterReadiness(
                readinessPresentationTimeNs = readinessPtsNs,
                targetFps = 25
            )
            val firstLive = OffscreenEncoderFramePacingPolicy.resolveOneToOne(
                presentationTimeNs = readinessPtsNs + delayNs,
                targetFps = 25,
                state = state
            )
            state = firstLive.state

            assertTrue(firstLive.submit)
            assertTrue(firstLive.readinessHandoffRebased)
            assertEquals(
                readinessPtsNs + 40_000_000L,
                firstLive.submissionPresentationTimeNs
            )

            repeat(125) { index ->
                val next = OffscreenEncoderFramePacingPolicy.resolveOneToOne(
                    presentationTimeNs = readinessPtsNs + delayNs + (index + 1L) * 40_000_000L,
                    targetFps = 25,
                    state = state
                )
                assertTrue(next.submit)
                assertFalse(next.readinessHandoffRebased)
                assertEquals(
                    state.lastSubmittedPresentationTimeNs + 40_000_000L,
                    next.submissionPresentationTimeNs
                )
                state = next.state
            }
        }
    }
}
