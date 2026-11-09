package com.neoproc.neoproccamx.niv99

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Niv99TemporalAndMotionTest {
    @Test
    fun `forward backward consistent flow is admitted`() {
        val field = Niv99MotionField(16).apply {
            configure(4, 4)
            for (index in 0 until count) {
                forwardX[index] = 0.25f
                forwardY[index] = -0.1f
                backwardX[index] = -0.25f
                backwardY[index] = 0.1f
                estimatorConfidence[index] = 0.95f
            }
        }
        val output = Niv99MotionSummary()
        val result = Niv99MotionConfidence(16).evaluate(
            field,
            null,
            rollingShutterSkewNs = 10_000_000L,
            frameDurationNs = 40_000_000L,
            output
        )
        assertEquals(Niv99ValidationResult.VALID, result)
        assertTrue(output.meanConsistency > 0.95f)
        assertTrue(output.admittedFraction > 0.8f)
        assertTrue(output.globalConfidence > 0.6f)
    }

    @Test
    fun `inconsistent reverse flow is rejected as ghost risk`() {
        val field = Niv99MotionField(16).apply {
            configure(4, 4)
            for (index in 0 until count) {
                forwardX[index] = 2f
                backwardX[index] = 2f
                estimatorConfidence[index] = 1f
            }
        }
        val output = Niv99MotionSummary()
        Niv99MotionConfidence(16).evaluate(field, null, 0L, 40_000_000L, output)
        assertTrue(output.meanConsistency < 0.2f)
        assertTrue(output.occludedFraction > 0.8f)
        assertTrue(output.admittedFraction < 0.2f)
    }

    @Test
    fun `rolling shutter gyro correction improves consistency`() {
        val field = Niv99MotionField(16).apply {
            configure(4, 4)
            for (index in 0 until count) {
                val row = index / columns
                val normalizedY = row.toFloat() / (rows - 1)
                val rollingTime = (normalizedY - 0.5f) * 0.02f
                val rollingFlow = 1f * 100f * rollingTime
                forwardX[index] = rollingFlow + 0.2f
                backwardX[index] = -rollingFlow - 0.2f
                estimatorConfidence[index] = 1f
            }
        }
        val gyro = Niv99GyroMotion().apply {
            angularVelocityY = 1f
            pixelsPerRadianX = 100f
            valid = true
        }
        val output = Niv99MotionSummary()
        Niv99MotionConfidence(16).evaluate(field, gyro, 20_000_000L, 40_000_000L, output)
        assertTrue(output.meanConsistency > 0.9f)
    }

    @Test
    fun `scene cut policy resets on joint luma chroma discontinuity`() {
        assertTrue(Niv99SceneDiscontinuityPolicy.isSceneCut(0.6f, 0.3f, 0.8f, 1f, false))
        assertFalse(Niv99SceneDiscontinuityPolicy.isSceneCut(0.1f, 0.02f, 0.9f, 1f, false))
        assertTrue(Niv99SceneDiscontinuityPolicy.isSceneCut(0f, 0f, 1f, 1f, true))
    }

    @Test
    fun `temporal history is bounded and timestamps remain ordered`() {
        val policy = Niv99TemporalDenoisePolicy(historyCapacity = 4)
        val output = Niv99TemporalFusionPlan(4)
        for (frame in 1L..12L) {
            val context = Niv99TestFixtures.context(frameNumber = frame)
            val result = policy.resolve(
                context,
                wb(context),
                Niv99TestFixtures.noiseSnapshot(context),
                stableMotion(),
                residualConfidence = 1f,
                output
            )
            assertEquals(Niv99ValidationResult.VALID, result)
            assertTrue(output.historyCount <= 4)
            for (index in 1 until output.historyCount) {
                assertTrue(output.historyTimestampNs[index] < output.historyTimestampNs[index - 1])
            }
        }
    }

    @Test
    fun `session change clears temporal history before fusion`() {
        val policy = Niv99TemporalDenoisePolicy(4)
        val output = Niv99TemporalFusionPlan(4)
        val first = Niv99TestFixtures.context(frameNumber = 1L, sessionGeneration = 1L)
        policy.resolve(first, wb(first), Niv99TestFixtures.noiseSnapshot(first), stableMotion(), 1f, output)
        val second = Niv99TestFixtures.context(frameNumber = 1L, sessionGeneration = 2L)
        policy.resolve(second, wb(second), Niv99TestFixtures.noiseSnapshot(second), stableMotion(), 1f, output)
        assertEquals(Niv99ResetReason.SESSION_CHANGED, output.resetReason)
        assertEquals(0, output.historyCount)
    }

    @Test
    fun `resolution FPS mode profile and surface boundaries clear history`() {
        val mutations = arrayOf<(Niv99FrameContext) -> Unit>(
            { it.width += 16 },
            { it.targetFps = 30 },
            { it.operatingMode = Niv99OperatingMode.PRO },
            { it.colorProfileId += 1L },
            { it.surfaceGeneration += 1L },
            { it.codecGeneration += 1L }
        )
        for (mutation in mutations) {
            val policy = Niv99TemporalDenoisePolicy(4)
            val output = Niv99TemporalFusionPlan(4)
            val first = Niv99TestFixtures.context(frameNumber = 1L)
            policy.resolve(first, wb(first), Niv99TestFixtures.noiseSnapshot(first), stableMotion(), 1f, output)
            val second = Niv99TestFixtures.context(frameNumber = 2L)
            mutation(second)
            policy.resolve(second, wb(second), Niv99TestFixtures.noiseSnapshot(second), stableMotion(), 1f, output)
            assertTrue(output.resetReason != Niv99ResetReason.NONE)
            assertEquals(0, output.historyCount)
        }
    }

    @Test
    fun `reordered metadata is reset and never fused`() {
        val policy = Niv99TemporalDenoisePolicy(4)
        val output = Niv99TemporalFusionPlan(4)
        val first = Niv99TestFixtures.context(frameNumber = 5L, timestampNs = 200_000_000L)
        policy.resolve(first, wb(first), Niv99TestFixtures.noiseSnapshot(first), stableMotion(), 1f, output)
        val reordered = Niv99TestFixtures.context(frameNumber = 4L, timestampNs = 160_000_000L)
        policy.resolve(
            reordered,
            wb(reordered),
            Niv99TestFixtures.noiseSnapshot(reordered),
            stableMotion(),
            1f,
            output
        )
        assertEquals(Niv99ResetReason.TIMESTAMP_DISCONTINUITY, output.resetReason)
        assertEquals(0, output.historyCount)
    }

    @Test
    fun `high motion suppresses temporal fusion`() {
        val policy = Niv99TemporalDenoisePolicy(4)
        val output = Niv99TemporalFusionPlan(4)
        val first = Niv99TestFixtures.context(frameNumber = 1L)
        policy.resolve(first, wb(first), Niv99TestFixtures.noiseSnapshot(first), stableMotion(), 1f, output)
        val second = Niv99TestFixtures.context(frameNumber = 2L)
        val highMotion = Niv99MotionSummary().apply {
            meanMagnitude = 8f
            p95Magnitude = 12f
            meanConsistency = 0.1f
            occludedFraction = 0.8f
            admittedFraction = 0.05f
            globalConfidence = 0.05f
        }
        policy.resolve(second, wb(second), Niv99TestFixtures.noiseSnapshot(second), highMotion, 0.1f, output)
        assertTrue(output.temporalStrength < 0.05f)
    }

    @Test
    fun `offline conversion may use more history but stays bounded`() {
        val policy = Niv99TemporalDenoisePolicy(8)
        val output = Niv99TemporalFusionPlan(8)
        for (frame in 1L..10L) {
            val context = Niv99TestFixtures.context(
                frameNumber = frame,
                mode = Niv99OperatingMode.CONVERSION_RAW_TO_LOG
            )
            policy.resolve(context, wb(context), Niv99TestFixtures.noiseSnapshot(context), stableMotion(), 1f, output)
        }
        assertTrue(output.deterministicOffline)
        assertTrue(output.historyCount <= 8)
        assertTrue(output.historyCount >= 3)
    }

    private fun stableMotion(): Niv99MotionSummary = Niv99MotionSummary().apply {
        meanMagnitude = 0.1f
        p95Magnitude = 0.2f
        meanConsistency = 0.98f
        occludedFraction = 0f
        admittedFraction = 1f
        globalConfidence = 0.98f
    }

    private fun wb(context: Niv99FrameContext): Niv99WhiteBalanceSolution =
        Niv99WhiteBalanceSolution().apply {
            identity.copyFrom(context.identity)
            sourceIdentity.copyFrom(context.identity)
            authority = Niv99WhiteBalanceAuthority.NIV99_AUTO
            redGain = 1.3f
            greenGain = 1f
            blueGain = 0.8f
            confidence = 0.9f
            colorMatrixValid = true
            Niv99Math.setIdentity3(colorMatrix)
        }
}

