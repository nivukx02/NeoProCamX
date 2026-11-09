package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameImportRoutingPolicyTest {

    private inline fun <T> withStrictHighEndOnly(
        enabled: Boolean,
        block: () -> T
    ): T {
        val previous = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.strictHighEndOnly = enabled
        return try {
            block()
        } finally {
            HighEndQualityPolicy.strictHighEndOnly = previous
        }
    }

    @Test
    fun `analysis import remains enabled during dedicated hardware transition grace`() {
        val enabled = FrameImportRoutingPolicy.setDedicatedHardwareImportEnabled(
            state = FrameImportRoutingState(),
            enabled = true,
            nowElapsedMs = 1_000L
        )

        assertTrue(
            FrameImportRoutingPolicy.shouldImportAnalysisFrame(
                state = enabled,
                nowElapsedMs = 1_250L
            )
        )
    }

    @Test
    fun `analysis import stops once dedicated hardware path should own preview`() {
        val enabled = FrameImportRoutingPolicy.setDedicatedHardwareImportEnabled(
            state = FrameImportRoutingState(),
            enabled = true,
            nowElapsedMs = 2_000L
        )

        assertFalse(
            FrameImportRoutingPolicy.shouldImportAnalysisFrame(
                state = enabled,
                nowElapsedMs = 2_500L
            )
        )
    }

    @Test
    fun `analysis import resumes if dedicated hardware import stalls`() {
        val enabled = FrameImportRoutingPolicy.setDedicatedHardwareImportEnabled(
            state = FrameImportRoutingState(),
            enabled = true,
            nowElapsedMs = 3_000L
        )
        val withHardwareImport = FrameImportRoutingPolicy.recordHardwareImport(
            state = enabled,
            nowElapsedMs = 3_100L
        )

        assertTrue(
            FrameImportRoutingPolicy.shouldImportAnalysisFrame(
                state = withHardwareImport,
                nowElapsedMs = 3_800L
            )
        )
    }

    @Test
    fun `severe frame load enters emergency skip mode`() {
        withStrictHighEndOnly(enabled = false) {
            val (state, shouldProcess) = FrameImportRoutingPolicy.evaluateFrameLoad(
                state = FrameImportRoutingState(),
                frameProcessTimeMs = 55L,
                frameSequence = 1L
            )

            assertFalse(shouldProcess)
            assertTrue(state.severeLoadMode)
            // OPTIMIZED: MAX_ADAPTIVE_SKIP reduced from 3 to 2 for less aggressive frame skipping
            assertEquals(2, state.adaptiveFrameSkipCount)
            assertTrue(state.sustainedOverloadScore >= 4)
        }
    }

    @Test
    fun `severe load uses strongest stride multiplier`() {
        withStrictHighEndOnly(enabled = false) {
            // With severeLoadMode=true, stride is 5 (regardless of adaptiveFrameSkipCount value)
            val stride = FrameImportRoutingPolicy.getAdaptiveStrideMultiplier(
                FrameImportRoutingState(severeLoadMode = true, adaptiveFrameSkipCount = 2)
            )

            assertEquals(5, stride)
        }
    }

    @Test
    fun `moderate frame cost enters high load mode earlier to protect preview`() {
        val (state, shouldProcess) = FrameImportRoutingPolicy.evaluateFrameLoad(
            state = FrameImportRoutingState(),
            frameProcessTimeMs = 23L,
            frameSequence = 1L
        )

        assertTrue(state.isHighLoadMode)
        assertTrue(shouldProcess)
    }

    @Test
    fun `isolated high load spike does not increase skip without streak`() {
        val (state, shouldProcess) = FrameImportRoutingPolicy.evaluateFrameLoad(
            state = FrameImportRoutingState(
                adaptiveFrameSkipCount = 0,
                consecutiveImportFailures = 2,
                sustainedOverloadScore = 9
            ),
            frameProcessTimeMs = 23L,
            frameSequence = 2L
        )

        assertEquals(0, state.adaptiveFrameSkipCount)
        assertTrue(shouldProcess)
    }

    @Test
    fun `sustained high load streak increases adaptive skip`() {
        withStrictHighEndOnly(enabled = false) {
            val first = FrameImportRoutingPolicy.evaluateFrameLoad(
                state = FrameImportRoutingState(),
                frameProcessTimeMs = 23L,
                frameSequence = 1L
            ).first
            val second = FrameImportRoutingPolicy.evaluateFrameLoad(
                state = first,
                frameProcessTimeMs = 23L,
                frameSequence = 2L
            ).first
            val (third, shouldProcess) = FrameImportRoutingPolicy.evaluateFrameLoad(
                state = second,
                frameProcessTimeMs = 23L,
                frameSequence = 3L
            )

            assertEquals(0, first.adaptiveFrameSkipCount)
            assertEquals(0, second.adaptiveFrameSkipCount)
            assertEquals(1, third.adaptiveFrameSkipCount)
            assertFalse(shouldProcess)
        }
    }

    @Test
    fun `strict high-end still protects realtime preview under severe analysis load`() {
        withStrictHighEndOnly(enabled = true) {
            val (state, shouldProcess) = FrameImportRoutingPolicy.evaluateFrameLoad(
                state = FrameImportRoutingState(),
                frameProcessTimeMs = 55L,
                frameSequence = 1L
            )

            assertFalse(shouldProcess)
            assertTrue(state.severeLoadMode)
            assertEquals(2, state.adaptiveFrameSkipCount)
            assertEquals(5, FrameImportRoutingPolicy.getAdaptiveStrideMultiplier(state))
        }
    }
}
