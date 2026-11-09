package com.neoproc.neoproccamx.niv99

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

/**
 * Deterministic property tests for invariants that must hold over a broad input space. The fixed
 * seeds make every failure exactly reproducible while still covering substantially more states
 * than individual example tests.
 */
class Niv99PropertyAndFuzzTest {
    @Test
    fun `well conditioned matrix inverses round trip over deterministic corpus`() {
        val random = DeterministicRandom(0x4e49563939L)
        val inverse = FloatArray(9)
        val product = FloatArray(9)
        repeat(2_000) { sample ->
            val matrix = FloatArray(9) { random.signedFloat(0.24f) }
            matrix[0] += random.float(1.25f, 2.2f)
            matrix[4] += random.float(1.25f, 2.2f)
            matrix[8] += random.float(1.25f, 2.2f)

            assertTrue("inverse rejected sample $sample", Niv99Math.invert3(matrix, inverse))
            Niv99Math.multiply3(matrix, inverse, product)
            assertIdentity(product, 3e-4f, "matrix inverse sample $sample")
        }
    }

    @Test
    fun `matrix multiplication remains correct when output aliases either input`() {
        val random = DeterministicRandom(0x9911223344L)
        val expected = FloatArray(9)
        repeat(500) { sample ->
            val left = FloatArray(9) { random.signedFloat(2f) }
            val right = FloatArray(9) { random.signedFloat(2f) }
            Niv99Math.multiply3(left, right, expected)

            val aliasedLeft = left.copyOf()
            Niv99Math.multiply3(aliasedLeft, right, aliasedLeft)
            assertArrayEquals("left alias sample $sample", expected, aliasedLeft, 1e-6f)

            val aliasedRight = right.copyOf()
            Niv99Math.multiply3(left, aliasedRight, aliasedRight)
            assertArrayEquals("right alias sample $sample", expected, aliasedRight, 1e-6f)
        }
    }

    @Test
    fun `weighted estimators ignore non finite and non positive evidence`() {
        val values = floatArrayOf(1f, Float.NaN, 7f, Float.POSITIVE_INFINITY, 3f, -8f)
        val weights = floatArrayOf(2f, 100f, -1f, 5f, 2f, 0f)
        val mean = Niv99Math.weightedMean(values, weights, values.size)
        val variance = Niv99Math.weightedVariance(values, weights, values.size, mean)
        assertEquals(2f, mean, 0f)
        assertEquals(1f, variance, 0f)
    }

    @Test
    fun `covariance propagation stays symmetric positive semidefinite`() {
        val random = DeterministicRandom(0x1234abcdL)
        val covariance = FloatArray(9)
        repeat(3_000) { sample ->
            val matrix = FloatArray(9) { random.signedFloat(1.4f) }
            Niv99Math.propagateDiagonalCovariance3(
                covarianceR = random.float(1e-9f, 0.08f),
                covarianceG = random.float(1e-9f, 0.08f),
                covarianceB = random.float(1e-9f, 0.08f),
                redGain = random.float(0.125f, 8f),
                greenGain = random.float(0.125f, 8f),
                blueGain = random.float(0.125f, 8f),
                colorMatrix = matrix,
                output = covariance
            )
            for (value in covariance) assertTrue("finite covariance sample $sample", value.isFinite())
            assertEquals(covariance[1], covariance[3], 2e-5f)
            assertEquals(covariance[2], covariance[6], 2e-5f)
            assertEquals(covariance[5], covariance[7], 2e-5f)
            assertTrue(covariance[0] >= 0f)
            assertTrue(covariance[4] >= 0f)
            assertTrue(covariance[8] >= 0f)

            repeat(4) {
                val x = random.signedFloat(2f)
                val y = random.signedFloat(2f)
                val z = random.signedFloat(2f)
                val quadratic =
                    x * (covariance[0] * x + covariance[1] * y + covariance[2] * z) +
                        y * (covariance[3] * x + covariance[4] * y + covariance[5] * z) +
                        z * (covariance[6] * x + covariance[7] * y + covariance[8] * z)
                assertTrue("PSD sample $sample produced $quadratic", quadratic >= -2e-4f)
            }
        }
    }

    @Test
    fun `noise variance is monotonic with signal ISO and temperature`() {
        val calibration = Niv99TestFixtures.calibration()
        val model = Niv99NoiseModel()
        val low = Niv99NoiseModelSnapshot()
        val highIso = Niv99NoiseModelSnapshot()
        val hot = Niv99NoiseModelSnapshot()

        val lowContext = Niv99TestFixtures.context().apply {
            iso = 100
            sensorTemperatureCelsius = 20f
        }
        val highContext = Niv99TestFixtures.context(frameNumber = 2L).apply {
            iso = 3_200
            sensorTemperatureCelsius = 20f
        }
        val hotContext = Niv99TestFixtures.context(frameNumber = 3L).apply {
            iso = 3_200
            sensorTemperatureCelsius = 65f
        }
        assertEquals(Niv99ValidationResult.VALID, model.resolve(lowContext, calibration, null, low))
        assertEquals(Niv99ValidationResult.VALID, model.resolve(highContext, calibration, null, highIso))
        assertEquals(Niv99ValidationResult.VALID, model.resolve(hotContext, calibration, null, hot))

        for (channel in 0 until Niv99SensorCalibration.CHANNEL_COUNT) {
            var previous = low.variance(channel, 0f)
            for (step in 1..100) {
                val current = low.variance(channel, step / 100f)
                assertTrue(current >= previous)
                previous = current
            }
            assertTrue(highIso.variance(channel, 0.5f) > low.variance(channel, 0.5f))
            assertTrue(hot.variance(channel, 0f) > highIso.variance(channel, 0f))
        }
    }

    @Test
    fun `raw statistics remain finite and bounded for all Bayer arrangements`() {
        val random = DeterministicRandom(0x55aa77ccL)
        val patterns = arrayOf(
            Niv99CfaPattern.RGGB,
            Niv99CfaPattern.GRBG,
            Niv99CfaPattern.GBRG,
            Niv99CfaPattern.BGGR
        )
        repeat(400) { sample ->
            val width = random.int(8, 113)
            val height = random.int(8, 97)
            val stride = width + random.int(0, 9)
            val pattern = patterns[random.int(0, patterns.size)]
            val black = random.float(16f, 256f)
            val white = random.float(1_024f, 16_383f)
            val redResponse = random.float(0.35f, 1.8f)
            val greenResponse = random.float(0.45f, 1.6f)
            val blueResponse = random.float(0.35f, 1.8f)
            val samples = FloatArray(stride * height)
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val response = when (cfaChannel(pattern, x, y)) {
                        Niv99SensorCalibration.CHANNEL_RED -> redResponse
                        Niv99SensorCalibration.CHANNEL_BLUE -> blueResponse
                        else -> greenResponse
                    }
                    val scene = random.float(0.025f, 0.72f)
                    val code = black + scene * response * (white - black)
                    samples[y * stride + x] = code.coerceIn(black, white)
                }
            }
            val calibration = Niv99TestFixtures.calibration().apply {
                cfaPattern = pattern
                blackLevel.fill(black)
                whiteLevel = white
                markUpdated()
            }
            val context = Niv99TestFixtures.context(
                frameNumber = sample + 1L,
                width = width,
                height = height
            )
            val noise = Niv99NoiseModelSnapshot()
            assertEquals(Niv99ValidationResult.VALID, Niv99NoiseModel().resolve(context, calibration, null, noise))
            val plane = Niv99LinearPlane().set(
                samples = samples,
                width = width,
                height = height,
                rowStride = stride,
                whiteLevel = white,
                cfaPattern = pattern
            )
            val output = Niv99TileStatistics(64)
            assertEquals(
                "statistics sample $sample",
                Niv99ValidationResult.VALID,
                Niv99RawStatistics().compute(plane, calibration, noise, output)
            )
            assertEquals(((width + 31) / 32) * ((height + 31) / 32), output.count)
            assertTrue(output.frameMeanSignal.isFinite())
            assertTrue(output.frameMeanSignal in 0f..1.25f)
            assertTrue(output.frameClippingFraction in 0f..1f)
            for (index in 0 until output.count) assertFiniteStatistics(output, index, sample)
        }
    }

    @Test
    fun `statistics never read row padding as image samples`() {
        val width = 65
        val height = 35
        val stride = 96
        val padded = FloatArray(stride * height) { Float.NaN }
        val compact = FloatArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val value = 0.1f + x * 0.002f + y * 0.001f
                padded[y * stride + x] = value
                compact[y * width + x] = value
            }
        }
        val calibration = Niv99TestFixtures.calibration()
        val noise = Niv99TestFixtures.noiseSnapshot()
        val paddedOutput = Niv99TileStatistics(16)
        val compactOutput = Niv99TileStatistics(16)
        val statistics = Niv99RawStatistics()
        assertEquals(
            Niv99ValidationResult.VALID,
            statistics.compute(
                Niv99LinearPlane().set(padded, width, height, stride, cfaPattern = Niv99CfaPattern.RGGB),
                calibration,
                noise,
                paddedOutput
            )
        )
        assertEquals(
            Niv99ValidationResult.VALID,
            statistics.compute(
                Niv99LinearPlane().set(compact, width, height, width, cfaPattern = Niv99CfaPattern.RGGB),
                calibration,
                noise,
                compactOutput
            )
        )
        assertEquals(compactOutput.count, paddedOutput.count)
        for (index in 0 until compactOutput.count) {
            assertEquals(compactOutput.meanRed[index], paddedOutput.meanRed[index], 0f)
            assertEquals(compactOutput.meanGreen[index], paddedOutput.meanGreen[index], 0f)
            assertEquals(compactOutput.meanBlue[index], paddedOutput.meanBlue[index], 0f)
        }
    }

    @Test
    fun `manual white balance authority survives randomized conflicting estimates`() {
        val random = DeterministicRandom(0x0badcafeL)
        val controller = Niv99WhiteBalanceController()
        val calibration = Niv99TestFixtures.calibration()
        val output = Niv99WhiteBalanceSolution()
        repeat(1_000) { sample ->
            val context = Niv99TestFixtures.context(
                frameNumber = sample + 1L,
                timestampNs = (sample + 1L) * 40_000_000L
            )
            val manualRed = random.float(0.125f, 8f)
            val manualGreen = random.float(0.125f, 8f)
            val manualBlue = random.float(0.125f, 8f)
            context.whiteBalanceIntent.apply {
                manualKelvin = random.int(1_500, 25_001)
                manualTint = random.int(-150, 151)
                manualRedGain = manualRed
                manualGreenGain = manualGreen
                manualBlueGain = manualBlue
                awbLockRequested = random.boolean()
                awbLockActive = random.boolean()
                halGainsAvailable = true
                halRedGain = random.float(0.125f, 8f)
                halGreenEvenGain = random.float(0.125f, 8f)
                halGreenOddGain = random.float(0.125f, 8f)
                halBlueGain = random.float(0.125f, 8f)
            }
            val estimate = Niv99IlluminantEstimate().apply {
                identity.copyFrom(context.identity)
                redGain = random.float(0.125f, 8f)
                greenGain = random.float(0.125f, 8f)
                blueGain = random.float(0.125f, 8f)
                cctKelvin = random.float(1_500f, 25_000f)
                confidence = 1f
            }
            assertEquals(
                Niv99ValidationResult.VALID,
                controller.resolve(context, calibration, estimate, null, output)
            )
            assertEquals(Niv99WhiteBalanceAuthority.MANUAL, output.authority)
            assertEquals(manualRed, output.redGain, 0f)
            assertEquals(manualGreen, output.greenGain, 0f)
            assertEquals(manualBlue, output.blueGain, 0f)
            assertTrue(output.hasFinitePayload())
        }
    }

    @Test
    fun `AWB lock holds one atomic source identity and matrix`() {
        val controller = Niv99WhiteBalanceController()
        val calibration = Niv99TestFixtures.calibration()
        val output = Niv99WhiteBalanceSolution()
        val lockedGains = FloatArray(3)
        val lockedMatrix = FloatArray(9)
        var sourceFrame = -1L
        for (frame in 1L..120L) {
            val context = Niv99TestFixtures.context(frameNumber = frame).apply {
                whiteBalanceIntent.awbLockRequested = frame >= 20L && frame <= 100L
            }
            val estimate = Niv99IlluminantEstimate().apply {
                identity.copyFrom(context.identity)
                redGain = 1f + frame * 0.004f
                greenGain = 1f
                blueGain = 1.8f - frame * 0.003f
                cctKelvin = 3_000f + frame * 25f
                confidence = 0.95f
            }
            assertEquals(
                Niv99ValidationResult.VALID,
                controller.resolve(context, calibration, estimate, null, output)
            )
            if (frame == 20L) {
                lockedGains[0] = output.redGain
                lockedGains[1] = output.greenGain
                lockedGains[2] = output.blueGain
                output.colorMatrix.copyInto(lockedMatrix)
                sourceFrame = output.sourceIdentity.frameNumber
            }
            if (frame in 20L..100L) {
                assertEquals(Niv99WhiteBalanceAuthority.AWB_LOCK, output.authority)
                assertEquals(lockedGains[0], output.redGain, 0f)
                assertEquals(lockedGains[1], output.greenGain, 0f)
                assertEquals(lockedGains[2], output.blueGain, 0f)
                assertArrayEquals(lockedMatrix, output.colorMatrix, 0f)
                assertEquals(sourceFrame, output.sourceIdentity.frameNumber)
                assertEquals(frame, output.identity.frameNumber)
            }
        }
        assertEquals(Niv99WhiteBalanceAuthority.NIV99_AUTO, output.authority)
        assertEquals(120L, output.sourceIdentity.frameNumber)
    }

    @Test
    fun `timeline fuzzer never permits history through a reset boundary`() {
        val random = DeterministicRandom(0x77889911L)
        repeat(250) { iteration ->
            val baseline = Niv99TestFixtures.context(frameNumber = 10L, timestampNs = 400_000_000L)
            val signature = Niv99TimelineSignature().apply { update(baseline) }
            val next = Niv99TestFixtures.context(frameNumber = 11L, timestampNs = 440_000_000L)
            val expected = when (random.int(0, 11)) {
                0 -> Niv99ResetReason.CAMERA_CHANGED.also {
                    next.identity.set("camera-$iteration", 1L, 11L, 440_000_000L)
                }
                1 -> Niv99ResetReason.SESSION_CHANGED.also {
                    next.identity.set(Niv99TestFixtures.CAMERA_ID, 2L, 11L, 440_000_000L)
                }
                2 -> Niv99ResetReason.MODE_CHANGED.also { next.operatingMode = Niv99OperatingMode.PRO }
                3 -> Niv99ResetReason.DIMENSIONS_CHANGED.also { next.width += 2 }
                4 -> Niv99ResetReason.FPS_CHANGED.also { next.targetFps = 30 }
                5 -> Niv99ResetReason.COLOR_PROFILE_CHANGED.also { next.colorProfileId += 1L }
                6 -> Niv99ResetReason.SURFACE_CHANGED.also { next.surfaceGeneration += 1L }
                7 -> Niv99ResetReason.CODEC_CHANGED.also { next.codecGeneration += 1L }
                8 -> Niv99ResetReason.SCENE_CUT.also { next.sceneCutHint = true }
                9 -> Niv99ResetReason.FLASH_TRANSITION.also { next.flashFired = true }
                else -> Niv99ResetReason.TIMESTAMP_DISCONTINUITY.also {
                    next.identity.set(Niv99TestFixtures.CAMERA_ID, 1L, 10L, 400_000_000L)
                }
            }
            assertEquals("timeline iteration $iteration", expected, signature.resetReason(next))
        }
    }

    @Test
    fun `engine output is deterministic for identical isolated sequences`() {
        val first = runEngineSequence()
        val second = runEngineSequence()
        assertEquals(first.size, second.size)
        for (index in first.indices) {
            val a = first[index]
            val b = second[index]
            assertEquals(a.whiteBalance.authority, b.whiteBalance.authority)
            assertEquals(a.whiteBalance.redGain, b.whiteBalance.redGain, 0f)
            assertEquals(a.whiteBalance.greenGain, b.whiteBalance.greenGain, 0f)
            assertEquals(a.whiteBalance.blueGain, b.whiteBalance.blueGain, 0f)
            assertArrayEquals(a.whiteBalance.colorMatrix, b.whiteBalance.colorMatrix, 0f)
            assertEquals(a.denoise.rawSpatialStrength, b.denoise.rawSpatialStrength, 0f)
            assertEquals(a.denoise.temporalStrength, b.denoise.temporalStrength, 0f)
            assertEquals(a.denoise.temporalHistoryFrames, b.denoise.temporalHistoryFrames)
            assertEquals(a.denoise.resetReason, b.denoise.resetReason)
        }
    }

    @Test
    fun `invalid metadata is rejected without publishing an accepted packet`() {
        val mutations: Array<(Niv99FrameContext) -> Unit> = arrayOf(
            { it.identity.set("", 1L, 1L, 40_000_000L) },
            { it.identity.set(Niv99TestFixtures.CAMERA_ID, -1L, 1L, 40_000_000L) },
            { it.identity.set(Niv99TestFixtures.CAMERA_ID, 1L, -1L, 40_000_000L) },
            { it.identity.set(Niv99TestFixtures.CAMERA_ID, 1L, 1L, 0L) },
            { it.width = 0 },
            { it.height = -1 },
            { it.targetFps = 0 },
            { it.targetFps = 481 },
            { it.iso = 0 },
            { it.exposureTimeNs = -1L },
            { it.motionMagnitude = Float.NaN },
            { it.opticalFlowConfidence = Float.POSITIVE_INFINITY },
            { it.thermalPressure = Float.NEGATIVE_INFINITY }
        )
        for ((index, mutate) in mutations.withIndex()) {
            val engine = Niv99Engine(maximumTiles = 64)
            val input = engineInput(frame = 1L)
            mutate(input.context)
            val output = Niv99StatePacket()
            val result = engine.process(input, Niv99TestFixtures.capabilityDecision(), output)
            assertFalse("mutation $index", output.accepted)
            assertTrue("mutation $index returned VALID", result != Niv99ValidationResult.VALID)
        }
    }

    private fun runEngineSequence(): List<Niv99StatePacket> {
        val engine = Niv99Engine(maximumTiles = 64)
        val packets = ArrayList<Niv99StatePacket>(32)
        for (frame in 1L..32L) {
            val input = engineInput(frame)
            input.context.motionMagnitude = (frame % 7L) / 20f
            input.context.opticalFlowConfidence = 1f - (frame % 5L) / 10f
            input.context.sceneCutHint = frame == 17L
            val packet = Niv99StatePacket()
            assertEquals(
                Niv99ValidationResult.VALID,
                engine.process(input, Niv99TestFixtures.capabilityDecision(), packet)
            )
            assertTrue(packet.accepted)
            assertTrue(packet.hasCoherentIdentity())
            packets += packet
        }
        return packets
    }

    private fun engineInput(frame: Long): Niv99FrameInput {
        val context = Niv99TestFixtures.context(frameNumber = frame)
        val raw = Niv99TestFixtures.rawPlane(
            redResponse = 0.72f + (frame % 4L) * 0.015f,
            blueResponse = 1.34f - (frame % 3L) * 0.012f,
            noiseAmplitude = 0.006f,
            seed = frame.toInt()
        )
        return Niv99FrameInput(64).apply {
            this.context.identity.copyFrom(context.identity)
            this.context.operatingMode = context.operatingMode
            this.context.signalDomain = context.signalDomain
            this.context.detailProfile = context.detailProfile
            this.context.width = context.width
            this.context.height = context.height
            this.context.targetFps = context.targetFps
            this.context.colorProfileId = context.colorProfileId
            this.context.surfaceGeneration = context.surfaceGeneration
            this.context.codecGeneration = context.codecGeneration
            this.context.iso = context.iso
            this.context.exposureTimeNs = context.exposureTimeNs
            this.context.frameDurationNs = context.frameDurationNs
            this.context.rollingShutterSkewNs = context.rollingShutterSkewNs
            this.context.motionMagnitude = context.motionMagnitude
            this.context.opticalFlowConfidence = context.opticalFlowConfidence
            plane.set(
                raw.samples,
                raw.width,
                raw.height,
                raw.rowStride,
                raw.offset,
                raw.whiteLevel,
                raw.cfaPattern
            )
            calibration = Niv99TestFixtures.calibration()
            semantics = Niv99SemanticTileMap(64).apply { clear(4) }
        }
    }

    private fun assertIdentity(matrix: FloatArray, tolerance: Float, message: String) {
        for (row in 0 until 3) {
            for (column in 0 until 3) {
                val expected = if (row == column) 1f else 0f
                assertEquals("$message [$row,$column]", expected, matrix[row * 3 + column], tolerance)
            }
        }
    }

    private fun assertFiniteStatistics(statistics: Niv99TileStatistics, index: Int, sample: Int) {
        val values = floatArrayOf(
            statistics.centerX[index],
            statistics.centerY[index],
            statistics.meanRed[index],
            statistics.meanGreen[index],
            statistics.meanBlue[index],
            statistics.varianceRed[index],
            statistics.varianceGreen[index],
            statistics.varianceBlue[index],
            statistics.gradientRed[index],
            statistics.gradientGreen[index],
            statistics.gradientBlue[index],
            statistics.saturationFraction[index],
            statistics.shadowFraction[index],
            statistics.clippingRisk[index],
            statistics.signalToNoise[index],
            statistics.reliability[index],
            statistics.neutralConfidence[index],
            statistics.redOverGreen[index],
            statistics.blueOverGreen[index]
        )
        for ((field, value) in values.withIndex()) {
            assertTrue("sample=$sample tile=$index field=$field value=$value", value.isFinite())
        }
        assertTrue(statistics.saturationFraction[index] in 0f..1f)
        assertTrue(statistics.shadowFraction[index] in 0f..1f)
        assertTrue(statistics.reliability[index] in 0f..1f)
        assertTrue(statistics.neutralConfidence[index] in 0f..1f)
        assertTrue(statistics.varianceRed[index] >= 0f)
        assertTrue(statistics.varianceGreen[index] >= 0f)
        assertTrue(statistics.varianceBlue[index] >= 0f)
    }

    private fun Niv99WhiteBalanceSolution.hasFinitePayload(): Boolean =
        redGain.isFinite() && greenGain.isFinite() && blueGain.isFinite() &&
            cctKelvin.isFinite() && duv.isFinite() && confidence.isFinite() &&
            colorMatrix.all(Float::isFinite)

    private fun cfaChannel(pattern: Niv99CfaPattern, x: Int, y: Int): Int {
        val evenX = x and 1 == 0
        val evenY = y and 1 == 0
        return when (pattern) {
            Niv99CfaPattern.RGGB -> when {
                evenY && evenX -> Niv99SensorCalibration.CHANNEL_RED
                evenY -> Niv99SensorCalibration.CHANNEL_GREEN_EVEN
                evenX -> Niv99SensorCalibration.CHANNEL_GREEN_ODD
                else -> Niv99SensorCalibration.CHANNEL_BLUE
            }
            Niv99CfaPattern.GRBG -> when {
                evenY && evenX -> Niv99SensorCalibration.CHANNEL_GREEN_EVEN
                evenY -> Niv99SensorCalibration.CHANNEL_RED
                evenX -> Niv99SensorCalibration.CHANNEL_BLUE
                else -> Niv99SensorCalibration.CHANNEL_GREEN_ODD
            }
            Niv99CfaPattern.GBRG -> when {
                evenY && evenX -> Niv99SensorCalibration.CHANNEL_GREEN_EVEN
                evenY -> Niv99SensorCalibration.CHANNEL_BLUE
                evenX -> Niv99SensorCalibration.CHANNEL_RED
                else -> Niv99SensorCalibration.CHANNEL_GREEN_ODD
            }
            Niv99CfaPattern.BGGR -> when {
                evenY && evenX -> Niv99SensorCalibration.CHANNEL_BLUE
                evenY -> Niv99SensorCalibration.CHANNEL_GREEN_EVEN
                evenX -> Niv99SensorCalibration.CHANNEL_GREEN_ODD
                else -> Niv99SensorCalibration.CHANNEL_RED
            }
            Niv99CfaPattern.MONO -> Niv99SensorCalibration.CHANNEL_GREEN_EVEN
            Niv99CfaPattern.UNKNOWN -> -1
        }
    }

    private class DeterministicRandom(seed: Long) {
        private var state = seed

        fun float(minimum: Float, maximum: Float): Float {
            require(maximum >= minimum)
            return minimum + (maximum - minimum) * unitFloat()
        }

        fun signedFloat(magnitude: Float): Float = float(-abs(magnitude), abs(magnitude))

        fun int(minimumInclusive: Int, maximumExclusive: Int): Int {
            require(maximumExclusive > minimumInclusive)
            val range = maximumExclusive - minimumInclusive
            return minimumInclusive + (nextPositiveLong() % range).toInt()
        }

        fun boolean(): Boolean = nextPositiveLong() and 1L == 0L

        private fun unitFloat(): Float =
            ((nextPositiveLong() ushr 16) and 0x00ff_ffffL).toFloat() / 0x0100_0000L.toFloat()

        private fun nextPositiveLong(): Long {
            state = state * 6_364_136_223_846_793_005L + 1_442_695_040_888_963_407L
            return state and Long.MAX_VALUE
        }
    }
}
