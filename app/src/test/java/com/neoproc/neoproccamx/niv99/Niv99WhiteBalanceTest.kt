package com.neoproc.neoproccamx.niv99

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class Niv99WhiteBalanceTest {
    @Test
    fun `illuminant ensemble neutralizes synthetic sensor response`() {
        val context = Niv99TestFixtures.context()
        val calibration = Niv99TestFixtures.calibration()
        val statistics = statistics(
            Niv99TestFixtures.rawPlane(redResponse = 0.70f, blueResponse = 1.40f),
            context,
            calibration
        )
        val calibrationSolution = Niv99CalibrationSolution()
        assertTrue(Niv99DualIlluminantInterpolator().resolve(calibration, 5_000f, calibrationSolution))
        val estimate = Niv99IlluminantEstimate()
        val result = Niv99IlluminantEstimator(64).estimate(
            context,
            statistics,
            calibrationSolution,
            null,
            estimate
        )
        assertEquals(Niv99ValidationResult.VALID, result)
        assertEquals(1f / 0.70f, estimate.redGain, 0.18f)
        assertEquals(1f / 1.40f, estimate.blueGain, 0.12f)
        assertTrue(estimate.confidence > 0.25f)
        assertTrue(estimate.acceptedCandidateCount >= 2)
    }

    @Test
    fun `single extreme chroma outlier cannot dominate consensus`() {
        val context = Niv99TestFixtures.context()
        val calibration = Niv99TestFixtures.calibration()
        val statistics = statistics(Niv99TestFixtures.rawPlane(), context, calibration)
        statistics.redOverGreen[0] = 8f
        statistics.blueOverGreen[0] = 0.1f
        statistics.meanRed[0] = 0.9f
        statistics.meanBlue[0] = 0.01f
        val calibrationSolution = Niv99CalibrationSolution()
        Niv99DualIlluminantInterpolator().resolve(calibration, 5_000f, calibrationSolution)
        val estimate = Niv99IlluminantEstimate()
        Niv99IlluminantEstimator(64).estimate(
            context,
            statistics,
            calibrationSolution,
            null,
            estimate
        )
        assertTrue(estimate.redResponseOverGreen < 1.3f)
        assertTrue(estimate.blueResponseOverGreen > 0.8f)
    }

    @Test
    fun `learned vote cannot override classical consensus alone`() {
        val context = Niv99TestFixtures.context()
        val calibration = Niv99TestFixtures.calibration()
        val statistics = statistics(Niv99TestFixtures.rawPlane(), context, calibration)
        val solution = Niv99CalibrationSolution()
        Niv99DualIlluminantInterpolator().resolve(calibration, 5_000f, solution)
        val learned = Niv99LearnedIlluminantInput().apply {
            available = true
            modelRevision = 1L
            redResponseOverGreen = 8f
            blueResponseOverGreen = 0.1f
            confidence = 1f
        }
        val estimate = Niv99IlluminantEstimate()
        Niv99IlluminantEstimator(64).estimate(context, statistics, solution, learned, estimate)
        assertTrue(estimate.redResponseOverGreen < 1.5f)
        assertTrue(estimate.blueResponseOverGreen > 0.7f)
    }

    @Test
    fun `mixed light creates rendering-only two cluster field`() {
        val context = Niv99TestFixtures.context(width = 128, height = 64)
        val calibration = Niv99TestFixtures.calibration()
        val statistics = statistics(Niv99TestFixtures.mixedLightPlane(), context, calibration)
        val global = Niv99IlluminantEstimate().apply {
            identity.copyFrom(context.identity)
            redGain = 1f
            blueGain = 1f
            confidence = 0.8f
        }
        val field = Niv99LocalIlluminantField(64)
        val result = Niv99MixedLightEstimator(64).estimate(statistics, global, field)
        assertEquals(Niv99ValidationResult.VALID, result)
        assertTrue(field.renderingOnly)
        assertTrue(field.clusterCount >= 2)
        assertTrue(field.mixedLightConfidence > 0f)
        assertNotEquals(field.redGain[0], field.redGain[field.columns - 1], 0.05f)
    }

    @Test
    fun `manual white balance is absolute authority`() {
        val context = Niv99TestFixtures.context()
        context.whiteBalanceIntent.apply {
            manualKelvin = 3_200
            manualTint = -24
            manualRedGain = 2.1f
            manualGreenGain = 1f
            manualBlueGain = 1.45f
            manualMatrixValid = true
            manualColorMatrix[0] = 0.9f
            manualColorMatrix[4] = 1f
            manualColorMatrix[8] = 1.1f
        }
        val conflictingEstimate = Niv99IlluminantEstimate().apply {
            identity.copyFrom(context.identity)
            redGain = 0.3f
            greenGain = 1f
            blueGain = 4f
            cctKelvin = 10_000f
            confidence = 1f
        }
        val output = Niv99WhiteBalanceSolution()
        val result = Niv99WhiteBalanceController().resolve(
            context,
            Niv99TestFixtures.calibration(),
            conflictingEstimate,
            null,
            output
        )
        assertEquals(Niv99ValidationResult.VALID, result)
        assertEquals(Niv99WhiteBalanceAuthority.MANUAL, output.authority)
        assertEquals(2.1f, output.redGain, 0f)
        assertEquals(1.45f, output.blueGain, 0f)
        assertArrayEquals(context.whiteBalanceIntent.manualColorMatrix, output.colorMatrix, 0f)
    }

    @Test
    fun `incomplete manual payload never falls through to auto`() {
        val context = Niv99TestFixtures.context()
        context.whiteBalanceIntent.apply {
            manualKelvin = 4_200
            manualRedGain = Float.NaN
            manualBlueGain = 0f
        }
        val estimate = Niv99IlluminantEstimate().apply {
            identity.copyFrom(context.identity)
            redGain = 4f
            blueGain = 4f
            confidence = 1f
        }
        val output = Niv99WhiteBalanceSolution()
        Niv99WhiteBalanceController().resolve(
            context,
            Niv99TestFixtures.calibration(),
            estimate,
            null,
            output
        )
        assertEquals(Niv99WhiteBalanceAuthority.MANUAL, output.authority)
        assertEquals(1f, output.redGain, 0f)
        assertEquals(1f, output.blueGain, 0f)
        assertFalse(output.colorMatrixValid)
    }

    @Test
    fun `first frame AWB lock uses same frame HAL state instead of NIV99 estimate`() {
        val context = Niv99TestFixtures.context()
        context.whiteBalanceIntent.apply {
            awbLockRequested = true
            halAwbConverged = true
            halGainsAvailable = true
            halRedGain = 1.65f
            halGreenEvenGain = 1.02f
            halGreenOddGain = 0.98f
            halBlueGain = 1.28f
        }
        val conflictingEstimate = illuminantEstimate(context, redGain = 0.42f, blueGain = 3.1f)
        val output = Niv99WhiteBalanceSolution()
        assertEquals(
            Niv99ValidationResult.VALID,
            Niv99WhiteBalanceController().resolve(
                context,
                Niv99TestFixtures.calibration(),
                conflictingEstimate,
                null,
                output
            )
        )
        assertEquals(Niv99WhiteBalanceAuthority.AWB_LOCK, output.authority)
        assertEquals(1.65f, output.redGain, 0f)
        assertEquals(1f, output.greenGain, 0f)
        assertEquals(1.28f, output.blueGain, 0f)
        assertEquals(context.identity.frameNumber, output.sourceIdentity.frameNumber)
    }

    @Test
    fun `first frame AWB lock without HAL payload never falls through to NIV99 auto`() {
        val context = Niv99TestFixtures.context()
        context.whiteBalanceIntent.apply {
            awbLockRequested = true
        }
        val conflictingEstimate = illuminantEstimate(context, redGain = 0.42f, blueGain = 3.1f)
        val output = Niv99WhiteBalanceSolution()
        Niv99WhiteBalanceController().resolve(
            context,
            Niv99TestFixtures.calibration(),
            conflictingEstimate,
            null,
            output
        )
        assertEquals(Niv99WhiteBalanceAuthority.UNSUPPORTED, output.authority)
        assertEquals(1f, output.redGain, 0f)
        assertEquals(1f, output.greenGain, 0f)
        assertEquals(1f, output.blueGain, 0f)
        assertFalse(output.colorMatrixValid)
    }

    @Test
    fun `confirmed HAL lock transition uses its same frame atomic payload`() {
        val controller = Niv99WhiteBalanceController()
        val firstContext = Niv99TestFixtures.context(frameNumber = 1L)
        val firstOutput = Niv99WhiteBalanceSolution()
        controller.resolve(
            firstContext,
            Niv99TestFixtures.calibration(),
            illuminantEstimate(firstContext, redGain = 1.2f, blueGain = 0.8f),
            null,
            firstOutput
        )

        val lockedContext = Niv99TestFixtures.context(frameNumber = 2L).apply {
            whiteBalanceIntent.awbLockRequested = true
            whiteBalanceIntent.awbLockActive = true
            whiteBalanceIntent.halAwbConverged = true
            whiteBalanceIntent.halGainsAvailable = true
            whiteBalanceIntent.halRedGain = 1.8f
            whiteBalanceIntent.halGreenEvenGain = 1.02f
            whiteBalanceIntent.halGreenOddGain = 0.98f
            whiteBalanceIntent.halBlueGain = 1.3f
            whiteBalanceIntent.halMatrixValid = true
            Niv99Math.setIdentity3(whiteBalanceIntent.halColorMatrix)
            whiteBalanceIntent.halColorMatrix[0] = 0.91f
        }
        val lockedOutput = Niv99WhiteBalanceSolution()
        assertEquals(
            Niv99ValidationResult.VALID,
            controller.resolve(
                lockedContext,
                Niv99TestFixtures.calibration(),
                illuminantEstimate(lockedContext, redGain = 0.4f, blueGain = 3f),
                null,
                lockedOutput
            )
        )
        assertEquals(Niv99WhiteBalanceAuthority.AWB_LOCK, lockedOutput.authority)
        assertEquals(1.8f, lockedOutput.redGain, 0f)
        assertEquals(1f, lockedOutput.greenGain, 0f)
        assertEquals(1.3f, lockedOutput.blueGain, 0f)
        assertEquals(0.91f, lockedOutput.colorMatrix[0], 0f)
        assertEquals(2L, lockedOutput.sourceIdentity.frameNumber)
    }

    @Test
    fun `finite default gains without same frame HAL provenance are unsupported`() {
        val context = Niv99TestFixtures.context()
        val output = Niv99WhiteBalanceSolution()

        assertFalse(context.whiteBalanceIntent.hasValidHalGains())
        assertEquals(
            Niv99ValidationResult.VALID,
            Niv99WhiteBalanceController().resolve(
                context,
                Niv99TestFixtures.calibration(),
                null,
                null,
                output
            )
        )
        assertEquals(Niv99WhiteBalanceAuthority.UNSUPPORTED, output.authority)
        assertFalse(output.colorMatrixValid)
    }

    @Test
    fun `AWB lock holds gains and matrix while frame identity advances`() {
        val state = Niv99WhiteBalanceTemporalState()
        val firstContext = Niv99TestFixtures.context(frameNumber = 1L)
        val first = autoSolution(firstContext, 1.4f, 0.8f)
        val firstOutput = Niv99WhiteBalanceSolution()
        state.stabilize(firstContext, first, firstOutput)

        val lockedContext = Niv99TestFixtures.context(frameNumber = 2L).apply {
            whiteBalanceIntent.awbLockRequested = true
        }
        val conflicting = autoSolution(lockedContext, 0.5f, 2.5f)
        val lockedOutput = Niv99WhiteBalanceSolution()
        state.stabilize(lockedContext, conflicting, lockedOutput)

        assertEquals(Niv99WhiteBalanceAuthority.AWB_LOCK, lockedOutput.authority)
        assertEquals(firstOutput.redGain, lockedOutput.redGain, 0f)
        assertEquals(firstOutput.blueGain, lockedOutput.blueGain, 0f)
        assertArrayEquals(firstOutput.colorMatrix, lockedOutput.colorMatrix, 0f)
        assertEquals(2L, lockedOutput.identity.frameNumber)
        assertEquals(1L, lockedOutput.sourceIdentity.frameNumber)
    }

    @Test
    fun `session generation change immediately resets temporal smoothing`() {
        val state = Niv99WhiteBalanceTemporalState()
        val firstContext = Niv99TestFixtures.context(frameNumber = 1L, sessionGeneration = 1L)
        state.stabilize(firstContext, autoSolution(firstContext, 1.2f, 0.9f), Niv99WhiteBalanceSolution())
        val nextContext = Niv99TestFixtures.context(frameNumber = 1L, sessionGeneration = 2L)
        val nextCandidate = autoSolution(nextContext, 2.2f, 0.5f)
        val output = Niv99WhiteBalanceSolution()
        val reason = state.stabilize(nextContext, nextCandidate, output)
        assertEquals(Niv99ResetReason.SESSION_CHANGED, reason)
        assertEquals(2.2f, output.redGain, 0f)
        assertEquals(0.5f, output.blueGain, 0f)
    }

    @Test
    fun `small chromatic jitter is held inside confidence deadband`() {
        val state = Niv99WhiteBalanceTemporalState()
        val firstContext = Niv99TestFixtures.context(frameNumber = 1L)
        val firstOutput = Niv99WhiteBalanceSolution()
        state.stabilize(firstContext, autoSolution(firstContext, 1.2f, 0.8f), firstOutput)
        val nextContext = Niv99TestFixtures.context(frameNumber = 2L)
        val nextOutput = Niv99WhiteBalanceSolution()
        state.stabilize(nextContext, autoSolution(nextContext, 1.201f, 0.8005f), nextOutput)
        assertEquals(firstOutput.redGain, nextOutput.redGain, 1e-6f)
        assertEquals(firstOutput.blueGain, nextOutput.blueGain, 1e-6f)
    }

    @Test
    fun `large high confidence illuminant change converges without one-frame full jump`() {
        val state = Niv99WhiteBalanceTemporalState()
        val firstContext = Niv99TestFixtures.context(frameNumber = 1L)
        state.stabilize(firstContext, autoSolution(firstContext, 1f, 1f), Niv99WhiteBalanceSolution())
        val nextContext = Niv99TestFixtures.context(frameNumber = 2L)
        val output = Niv99WhiteBalanceSolution()
        state.stabilize(nextContext, autoSolution(nextContext, 2f, 0.5f), output)
        assertTrue(output.redGain > 1f)
        assertTrue(output.redGain < 2f)
        assertTrue(output.blueGain < 1f)
        assertTrue(output.blueGain > 0.5f)
    }

    @Test
    fun `local mixed-light field never changes global manual gains`() {
        val context = Niv99TestFixtures.context()
        context.whiteBalanceIntent.apply {
            manualKelvin = 5_000
            manualRedGain = 1.8f
            manualGreenGain = 1f
            manualBlueGain = 1.2f
        }
        val field = Niv99LocalIlluminantField(16).apply {
            reset(4, 4)
            redGain.fill(6f)
            blueGain.fill(0.2f)
            mixedLightConfidence = 1f
        }
        val output = Niv99WhiteBalanceSolution()
        Niv99WhiteBalanceController().resolve(
            context,
            Niv99TestFixtures.calibration(),
            null,
            field,
            output
        )
        assertEquals(1.8f, output.redGain, 0f)
        assertEquals(1.2f, output.blueGain, 0f)
    }

    private fun statistics(
        plane: Niv99LinearPlane,
        context: Niv99FrameContext,
        calibration: Niv99SensorCalibration
    ): Niv99TileStatistics {
        val output = Niv99TileStatistics(64)
        val noise = Niv99NoiseModelSnapshot()
        Niv99NoiseModel().resolve(context, calibration, null, noise)
        val result = Niv99RawStatistics(tileWidth = 16, tileHeight = 16).compute(
            plane,
            calibration,
            noise,
            output
        )
        check(result == Niv99ValidationResult.VALID)
        return output
    }

    private fun autoSolution(
        context: Niv99FrameContext,
        redGain: Float,
        blueGain: Float
    ): Niv99WhiteBalanceSolution = Niv99WhiteBalanceSolution().apply {
        identity.copyFrom(context.identity)
        sourceIdentity.copyFrom(context.identity)
        authority = Niv99WhiteBalanceAuthority.NIV99_AUTO
        this.redGain = redGain
        greenGain = 1f
        this.blueGain = blueGain
        cctKelvin = 5_000f
        duv = 0f
        confidence = 0.95f
        colorMatrixValid = true
        Niv99Math.setIdentity3(colorMatrix)
        assertTrue(abs(redGain) > 0f)
    }

    private fun illuminantEstimate(
        context: Niv99FrameContext,
        redGain: Float,
        blueGain: Float
    ): Niv99IlluminantEstimate = Niv99IlluminantEstimate().apply {
        identity.copyFrom(context.identity)
        this.redGain = redGain
        greenGain = 1f
        this.blueGain = blueGain
        redResponseOverGreen = 1f / redGain
        blueResponseOverGreen = 1f / blueGain
        cctKelvin = 5_000f
        confidence = 1f
    }
}
