package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RawPreviewColorMetadataStabilizerTest {
    // Synthetic, invertible forward matrix whose neutral sensor vector maps to XYZ D50.
    private val neutralD50 = floatArrayOf(
        0.96422f, 0f, 0f,
        0f, 1f, 0f,
        0f, 0f, 0.82521f
    )

    @Test
    fun `invalid first packet is skipped instead of presenting corrupt colour`() {
        val resolution = RawPreviewColorMetadataStabilizer().resolve(
            whiteBalanceRggb = null,
            sensorToXyz = neutralD50
        )

        assertFalse(resolution.shouldPresent)
        assertNull(resolution.metadata)
        assertEquals(RawPreviewColorMetadataSource.SKIPPED_INVALID_FIRST_FRAME, resolution.source)
        assertEquals(
            RawPreviewColorMetadataRejectionReason.MISSING_WHITE_BALANCE,
            resolution.rejection?.reason
        )
    }

    @Test
    fun `missing packet holds the last proven presentation metadata`() {
        val stabilizer = RawPreviewColorMetadataStabilizer()
        val gains = floatArrayOf(2f, 1f, 1f, 1.55f)
        val accepted = stabilizer.resolve(gains, neutralD50)
        val held = stabilizer.resolve(null, null)

        assertEquals(RawPreviewColorMetadataSource.LIVE, accepted.source)
        assertNull(accepted.rejection)
        assertEquals(RawPreviewColorMetadataSource.HELD_LAST_VALID, held.source)
        assertEquals(
            RawPreviewColorMetadataRejectionReason.MISSING_WHITE_BALANCE,
            held.rejection?.reason
        )
        val heldMetadata = held.metadata!!
        assertArrayEquals(gains, heldMetadata.whiteBalanceRggb, 0f)
        assertArrayEquals(neutralD50, heldMetadata.sensorToXyz, 0f)
    }

    @Test
    fun `valid illuminant motion is smoothed without freezing colour adaptation`() {
        val stabilizer = RawPreviewColorMetadataStabilizer(gainAlpha = 0.25f, matrixAlpha = 0.25f)
        stabilizer.resolve(floatArrayOf(2f, 1f, 1f, 1.5f), neutralD50)
        // Change the basis while preserving each row sum, so neutral still maps exactly to D50.
        val nextMatrix = floatArrayOf(
            0.9256512f, 0.0385688f, 0f,
            0.04f, 0.96f, 0f,
            0f, 0.0330084f, 0.7922016f
        )
        val result = stabilizer.resolve(floatArrayOf(2.2f, 1f, 1f, 1.4f), nextMatrix)
        val stable = result.metadata!!

        assertEquals(RawPreviewColorMetadataSource.SMOOTHED, result.source)
        assertTrue(stable.whiteBalanceRggb[0] > 2f)
        assertTrue(stable.whiteBalanceRggb[0] < 2.2f)
        assertTrue(stable.sensorToXyz[0] < neutralD50[0])
        assertTrue(stable.sensorToXyz[0] > nextMatrix[0])
    }

    @Test
    fun `one frame gain or matrix outlier cannot create red green or grayscale flash`() {
        val stabilizer = RawPreviewColorMetadataStabilizer()
        val gains = floatArrayOf(2f, 1f, 1f, 1.5f)
        stabilizer.resolve(gains, neutralD50)

        val gainOutlier = stabilizer.resolve(floatArrayOf(8f, 1f, 1f, 0.4f), neutralD50)
        val matrixOutlier = stabilizer.resolve(
            gains,
            floatArrayOf(2f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0.25f)
        )

        assertEquals(RawPreviewColorMetadataSource.HELD_LAST_VALID, gainOutlier.source)
        assertEquals(
            RawPreviewColorMetadataRejectionReason.TEMPORAL_OUTLIER,
            gainOutlier.rejection?.reason
        )
        assertArrayEquals(gains, gainOutlier.metadata!!.whiteBalanceRggb, 0f)
        assertEquals(RawPreviewColorMetadataSource.HELD_LAST_VALID, matrixOutlier.source)
        assertEquals(
            RawPreviewColorMetadataRejectionReason.NEUTRAL_AXIS_REJECTED,
            matrixOutlier.rejection?.reason
        )
        assertArrayEquals(neutralD50, matrixOutlier.metadata!!.sensorToXyz, 0f)
    }

    @Test
    fun `sustained illuminant jump is confirmed then adapted without a one frame flash`() {
        val stabilizer = RawPreviewColorMetadataStabilizer(gainAlpha = 0.25f)
        val baseline = floatArrayOf(2f, 1f, 1f, 1.5f)
        val changed = floatArrayOf(4f, 1f, 1f, 0.8f)
        stabilizer.resolve(baseline, neutralD50)

        val first = stabilizer.resolve(changed, neutralD50)
        val second = stabilizer.resolve(changed.copyOf(), neutralD50.copyOf())
        val third = stabilizer.resolve(changed.copyOf(), neutralD50.copyOf())

        assertEquals(RawPreviewColorMetadataSource.HELD_LAST_VALID, first.source)
        assertArrayEquals(baseline, first.metadata!!.whiteBalanceRggb, 0f)
        assertEquals(RawPreviewColorMetadataSource.SMOOTHED, second.source)
        val secondMetadata = second.metadata!!
        assertTrue(secondMetadata.whiteBalanceRggb[0] > baseline[0])
        assertTrue(secondMetadata.whiteBalanceRggb[0] < changed[0])
        assertEquals(RawPreviewColorMetadataSource.SMOOTHED, third.source)
        assertTrue(third.metadata!!.whiteBalanceRggb[0] > secondMetadata.whiteBalanceRggb[0])
    }

    @Test
    fun `invalid metadata interrupts outlier confirmation so evidence must be consecutive`() {
        val stabilizer = RawPreviewColorMetadataStabilizer(gainAlpha = 0.25f)
        val baseline = floatArrayOf(2f, 1f, 1f, 1.5f)
        val changed = floatArrayOf(4f, 1f, 1f, 0.8f)
        stabilizer.resolve(baseline, neutralD50)

        val firstJump = stabilizer.resolve(changed, neutralD50)
        val interrupted = stabilizer.resolve(null, neutralD50)
        val firstAfterInterruption = stabilizer.resolve(changed.copyOf(), neutralD50.copyOf())
        val secondAfterInterruption = stabilizer.resolve(changed.copyOf(), neutralD50.copyOf())

        assertEquals(RawPreviewColorMetadataSource.HELD_LAST_VALID, firstJump.source)
        assertEquals(RawPreviewColorMetadataSource.HELD_LAST_VALID, interrupted.source)
        assertEquals(
            RawPreviewColorMetadataRejectionReason.MISSING_WHITE_BALANCE,
            interrupted.rejection?.reason
        )
        assertEquals(RawPreviewColorMetadataSource.HELD_LAST_VALID, firstAfterInterruption.source)
        assertArrayEquals(baseline, firstAfterInterruption.metadata!!.whiteBalanceRggb, 0f)
        assertEquals(RawPreviewColorMetadataSource.SMOOTHED, secondAfterInterruption.source)
        val adapted = requireNotNull(secondAfterInterruption.metadata)
        assertTrue(adapted.whiteBalanceRggb[0] > baseline[0])
        assertTrue(adapted.whiteBalanceRggb[0] < changed[0])
    }

    @Test
    fun `camera stream identity change cannot reuse stale colour metadata`() {
        val stabilizer = RawPreviewColorMetadataStabilizer()
        stabilizer.resolve(floatArrayOf(2f, 1f, 1f, 1.5f), neutralD50, streamIdentity = "0:raw")
        val result = stabilizer.resolve(null, neutralD50, streamIdentity = "1:raw")

        assertFalse(result.shouldPresent)
        assertEquals(RawPreviewColorMetadataSource.SKIPPED_INVALID_FIRST_FRAME, result.source)
    }

    @Test
    fun `neutral axis rejection exposes status spread determinant and exact matrix`() {
        val chromatic = floatArrayOf(
            2f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 0.25f
        )

        val result = RawPreviewColorMetadataStabilizer().resolve(
            whiteBalanceRggb = floatArrayOf(2f, 1f, 1f, 1.5f),
            sensorToXyz = chromatic
        )

        assertEquals(
            RawPreviewColorMetadataRejectionReason.NEUTRAL_AXIS_REJECTED,
            result.rejection?.reason
        )
        val detail = result.rejection?.diagnosticSummary(
            whiteBalanceRggb = floatArrayOf(2f, 1f, 1f, 1.5f),
            sensorToXyz = chromatic
        ).orEmpty()
        assertTrue(detail.contains("neutralStatus=CHROMATIC"))
        assertTrue(detail.contains("spread="))
        assertTrue(detail.contains("determinant="))
        assertTrue(detail.contains("matrix=[2.0,0.0,0.0"))
    }

    @Test
    fun `diagnostic rate limiter logs first and periodic summary with suppression count`() {
        val limiter = RawPreviewColorDiagnosticRateLimiter(minimumIntervalMs = 5_000L)

        val first = limiter.evaluate("stream|NEUTRAL_AXIS_REJECTED", 10_000L)
        val suppressedOne = limiter.evaluate("stream|NEUTRAL_AXIS_REJECTED", 10_001L)
        val suppressedTwo = limiter.evaluate("stream|NEUTRAL_AXIS_REJECTED", 14_999L)
        val periodic = limiter.evaluate("stream|NEUTRAL_AXIS_REJECTED", 15_000L)

        assertTrue(first.shouldLog)
        assertEquals(0L, first.suppressedSinceLastLog)
        assertFalse(suppressedOne.shouldLog)
        assertFalse(suppressedTwo.shouldLog)
        assertTrue(periodic.shouldLog)
        assertEquals(2L, periodic.suppressedSinceLastLog)
    }

    @Test
    fun `diagnostic rate limiter reset makes next epoch immediately observable`() {
        val limiter = RawPreviewColorDiagnosticRateLimiter(minimumIntervalMs = 5_000L)
        assertTrue(limiter.evaluate("epoch=1|MISSING_WHITE_BALANCE", 1_000L).shouldLog)
        assertFalse(limiter.evaluate("epoch=1|MISSING_WHITE_BALANCE", 1_001L).shouldLog)

        limiter.reset()

        assertTrue(limiter.evaluate("epoch=2|MISSING_WHITE_BALANCE", 1_002L).shouldLog)
    }
}
