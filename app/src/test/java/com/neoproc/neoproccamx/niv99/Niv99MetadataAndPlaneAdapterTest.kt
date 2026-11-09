package com.neoproc.neoproccamx.niv99

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Niv99MetadataAndPlaneAdapterTest {
    @Test
    fun `physical CFA tile maps to semantic RGGB for every Bayer arrangement`() {
        val physical = floatArrayOf(10f, 20f, 30f, 40f)
        val semantic = FloatArray(4)

        assertTrue(Niv99CfaChannelMapper.copyPhysicalTileToSemantic(physical, Niv99CfaPattern.RGGB, semantic))
        assertArrayEquals(floatArrayOf(10f, 20f, 30f, 40f), semantic, 0f)

        assertTrue(Niv99CfaChannelMapper.copyPhysicalTileToSemantic(physical, Niv99CfaPattern.GRBG, semantic))
        assertArrayEquals(floatArrayOf(20f, 10f, 40f, 30f), semantic, 0f)

        assertTrue(Niv99CfaChannelMapper.copyPhysicalTileToSemantic(physical, Niv99CfaPattern.GBRG, semantic))
        assertArrayEquals(floatArrayOf(30f, 10f, 40f, 20f), semantic, 0f)

        assertTrue(Niv99CfaChannelMapper.copyPhysicalTileToSemantic(physical, Niv99CfaPattern.BGGR, semantic))
        assertArrayEquals(floatArrayOf(40f, 20f, 30f, 10f), semantic, 0f)
    }

    @Test
    fun `unsupported CFA never receives an invented channel mapping`() {
        val output = FloatArray(4) { 99f }
        assertFalse(
            Niv99CfaChannelMapper.copyPhysicalTileToSemantic(
                floatArrayOf(1f, 2f, 3f, 4f),
                Niv99CfaPattern.UNKNOWN,
                output
            )
        )
        assertArrayEquals(floatArrayOf(99f, 99f, 99f, 99f), output, 0f)
        assertFalse(
            Niv99CfaChannelMapper.copyPhysicalTileToSemantic(
                floatArrayOf(1f, 2f, 3f, 4f),
                Niv99CfaPattern.MONO,
                output
            )
        )
    }

    @Test
    fun `dynamic semantic black levels override static physical tile without reordering`() {
        val static = staticMetadata(Niv99CfaPattern.BGGR).apply {
            blackLevelPhysicalTile[0] = 11f
            blackLevelPhysicalTile[1] = 22f
            blackLevelPhysicalTile[2] = 33f
            blackLevelPhysicalTile[3] = 44f
        }
        val dynamic = Niv99DynamicSensorMetadata().apply {
            identity.set(Niv99TestFixtures.CAMERA_ID, 1L, 1L, 40_000_000L)
            dynamicBlackLevelAvailable = true
            dynamicBlackLevelRggb[0] = 101f
            dynamicBlackLevelRggb[1] = 102f
            dynamicBlackLevelRggb[2] = 103f
            dynamicBlackLevelRggb[3] = 104f
            dynamicWhiteLevel = 16_000f
            dynamicWhiteLevelAvailable = true
        }
        val output = Niv99SensorCalibration()
        assertEquals(Niv99MetadataStatus.VALID, Niv99CalibrationAssembler.populate(static, dynamic, output))
        assertArrayEquals(floatArrayOf(101f, 102f, 103f, 104f), output.blackLevel, 0f)
        assertEquals(16_000f, output.whiteLevel, 0f)
    }

    @Test
    fun `static physical black tile is reordered for BGGR`() {
        val static = staticMetadata(Niv99CfaPattern.BGGR).apply {
            blackLevelPhysicalTile[0] = 41f
            blackLevelPhysicalTile[1] = 42f
            blackLevelPhysicalTile[2] = 43f
            blackLevelPhysicalTile[3] = 44f
        }
        val output = Niv99SensorCalibration()
        assertEquals(Niv99MetadataStatus.VALID, Niv99CalibrationAssembler.populate(static, null, output))
        assertArrayEquals(floatArrayOf(44f, 42f, 43f, 41f), output.blackLevel, 0f)
    }

    @Test
    fun `required calibration omissions are truthful errors`() {
        val output = Niv99SensorCalibration()
        val static = staticMetadata()
        static.blackLevelAvailable = false
        assertEquals(Niv99MetadataStatus.MISSING_BLACK_LEVEL, Niv99CalibrationAssembler.populate(static, null, output))
        static.blackLevelAvailable = true
        static.whiteLevelAvailable = false
        assertEquals(Niv99MetadataStatus.MISSING_WHITE_LEVEL, Niv99CalibrationAssembler.populate(static, null, output))
        static.whiteLevelAvailable = true
        static.colorTransformPairAvailable = false
        assertEquals(
            Niv99MetadataStatus.MISSING_COLOR_TRANSFORMS,
            Niv99CalibrationAssembler.populate(static, null, output)
        )
        static.colorTransformPairAvailable = true
        static.cfaPattern = Niv99CfaPattern.UNKNOWN
        assertEquals(Niv99MetadataStatus.UNSUPPORTED_CFA, Niv99CalibrationAssembler.populate(static, null, output))
    }

    @Test
    fun `optional forward matrix absence uses explicit inverse color source`() {
        val static = staticMetadata().apply {
            forwardMatrixPairAvailable = false
            calibrationTransformPairAvailable = false
        }
        val calibration = Niv99SensorCalibration()
        assertEquals(Niv99MetadataStatus.VALID, Niv99CalibrationAssembler.populate(static, null, calibration))
        val solution = Niv99CalibrationSolution()
        assertTrue(Niv99DualIlluminantInterpolator().resolve(calibration, 5_000f, solution))
        assertEquals(
            Niv99CalibrationMatrixSource.INVERSE_DUAL_ILLUMINANT_COLOR_MATRIX,
            solution.matrixSource
        )
        assertTrue(solution.calibrationTransformIdentityFallback)
    }

    @Test
    fun `forward matrix presence reports dual illuminant forward source`() {
        val calibration = Niv99SensorCalibration()
        assertEquals(
            Niv99MetadataStatus.VALID,
            Niv99CalibrationAssembler.populate(staticMetadata(), null, calibration)
        )
        val solution = Niv99CalibrationSolution()
        assertTrue(Niv99DualIlluminantInterpolator().resolve(calibration, 5_000f, solution))
        assertEquals(Niv99CalibrationMatrixSource.DUAL_ILLUMINANT_FORWARD_MATRIX, solution.matrixSource)
        assertFalse(solution.calibrationTransformIdentityFallback)
    }

    @Test
    fun `lens shading metadata is copied in canonical cell channel order`() {
        val dynamic = Niv99DynamicSensorMetadata(lensShadingCapacity = 16).apply {
            identity.set(Niv99TestFixtures.CAMERA_ID, 1L, 2L, 80_000_000L)
            lensShadingColumns = 2
            lensShadingRows = 2
            lensShadingAvailable = true
            for (index in 0 until 16) lensShadingRggb[index] = index + 1f
        }
        val output = Niv99SensorCalibration(lensShadingCapacity = 16)
        assertEquals(
            Niv99MetadataStatus.VALID,
            Niv99CalibrationAssembler.populate(staticMetadata(), dynamic, output)
        )
        assertEquals(1f, output.lensShadingGain(0, 0f, 0f), 0f)
        assertEquals(4f, output.lensShadingGain(3, 0f, 0f), 0f)
        assertEquals(13f, output.lensShadingGain(0, 1f, 1f), 0f)
        assertEquals(16f, output.lensShadingGain(3, 1f, 1f), 0f)
    }

    @Test
    fun `invalid dynamic lens map is rejected instead of silently ignored`() {
        val dynamic = Niv99DynamicSensorMetadata(lensShadingCapacity = 4).apply {
            identity.set(Niv99TestFixtures.CAMERA_ID, 1L, 2L, 80_000_000L)
            lensShadingColumns = 2
            lensShadingRows = 2
            lensShadingAvailable = true
        }
        assertEquals(
            Niv99MetadataStatus.LENS_SHADING_CAPACITY_EXCEEDED,
            Niv99CalibrationAssembler.populate(staticMetadata(), dynamic, Niv99SensorCalibration())
        )
    }

    @Test
    fun `normalized Camera2 noise coefficients are not divided by sensor code range`() {
        val calibration = Niv99SensorCalibration()
        assertEquals(
            Niv99MetadataStatus.VALID,
            Niv99CalibrationAssembler.populate(staticMetadata(), null, calibration)
        )
        val dynamic = measuredNoiseMetadata(Niv99NoiseCoefficientDomain.NORMALIZED_SENSOR_SIGNAL)
        val measured = Niv99MeasuredNoiseProfile()
        assertEquals(Niv99MetadataStatus.VALID, Niv99CalibrationAssembler.populateMeasuredNoise(dynamic, measured))
        val context = Niv99TestFixtures.context()
        val snapshot = Niv99NoiseModelSnapshot()
        assertEquals(Niv99ValidationResult.VALID, Niv99NoiseModel().resolve(context, calibration, measured, snapshot))
        assertEquals(0.012f, snapshot.shotCoefficient[0], 0f)
        assertEquals(0.00014f, snapshot.readCoefficient[0], 0f)
        assertEquals(Niv99NoiseCalibrationSource.CAPTURE_RESULT_NOISE_PROFILE, snapshot.calibrationSource)
    }

    @Test
    fun `code value noise coefficients are normalized exactly once`() {
        val static = staticMetadata().apply {
            whiteLevel = 4_095f
            blackLevelPhysicalTile.fill(63f)
        }
        val calibration = Niv99SensorCalibration()
        assertEquals(Niv99MetadataStatus.VALID, Niv99CalibrationAssembler.populate(static, null, calibration))
        val dynamic = measuredNoiseMetadata(Niv99NoiseCoefficientDomain.SENSOR_CODE_VALUE)
        val measured = Niv99MeasuredNoiseProfile()
        assertEquals(Niv99MetadataStatus.VALID, Niv99CalibrationAssembler.populateMeasuredNoise(dynamic, measured))
        val snapshot = Niv99NoiseModelSnapshot()
        assertEquals(
            Niv99ValidationResult.VALID,
            Niv99NoiseModel().resolve(Niv99TestFixtures.context(), calibration, measured, snapshot)
        )
        val range = 4_095f - 63f
        assertEquals(0.012f / range, snapshot.shotCoefficient[0], 1e-9f)
        assertEquals(0.00014f / (range * range), snapshot.readCoefficient[0], 1e-12f)
    }

    @Test
    fun `missing frame profile requires an explicitly sourced fallback model`() {
        val static = staticMetadata().apply {
            fallbackNoiseSource = Niv99NoiseCalibrationSource.UNAVAILABLE
            fallbackShotNoise.fill(0f)
            fallbackReadNoise.fill(0f)
        }
        val calibration = Niv99SensorCalibration()
        assertEquals(Niv99MetadataStatus.VALID, Niv99CalibrationAssembler.populate(static, null, calibration))
        assertEquals(
            Niv99ValidationResult.INVALID_CALIBRATION,
            Niv99NoiseModel().resolve(
                Niv99TestFixtures.context(),
                calibration,
                null,
                Niv99NoiseModelSnapshot()
            )
        )
    }

    @Test
    fun `capture metadata copies one atomic HAL gain matrix pair with provenance`() {
        val capture = Niv99CaptureMetadata().apply {
            identity.set(Niv99TestFixtures.CAMERA_ID, 8L, 99L, 9_900_000L)
            iso = 640
            exposureTimeNs = 9_000_000L
            frameDurationNs = 40_000_000L
            rollingShutterSkewNs = 14_000_000L
            halAwbConverged = true
            halAwbLocked = true
            halGainsAvailable = true
            halRedGain = 1.8f
            halGreenEvenGain = 1.01f
            halGreenOddGain = 0.99f
            halBlueGain = 1.3f
            halMatrixAvailable = true
            Niv99Math.setIdentity3(halColorMatrix)
            halColorMatrix[0] = 0.9f
        }
        val controls = Niv99UserControlMetadata().apply { awbLockRequested = true }
        val context = Niv99FrameContext()
        assertEquals(
            Niv99ValidationResult.VALID,
            capture.applyTo(
                context,
                controls,
                Niv99OperatingMode.VIDEO,
                Niv99SignalDomain.SENSOR_LINEAR_RAW,
                Niv99DetailProfile.NATURAL,
                3_840,
                2_160,
                25,
                5L,
                6L,
                7L
            )
        )
        assertEquals(99L, context.identity.frameNumber)
        assertEquals(9_900_000L, context.identity.sensorTimestampNs)
        assertEquals(640, context.iso)
        assertTrue(context.whiteBalanceIntent.awbLockRequested)
        assertTrue(context.whiteBalanceIntent.awbLockActive)
        assertTrue(context.whiteBalanceIntent.halAwbHeld)
        assertTrue(context.whiteBalanceIntent.halAwbConverged)
        assertTrue(context.whiteBalanceIntent.halGainsAvailable)
        assertEquals(1.8f, context.whiteBalanceIntent.halRedGain, 0f)
        assertEquals(1.3f, context.whiteBalanceIntent.halBlueGain, 0f)
        assertEquals(0.9f, context.whiteBalanceIntent.halColorMatrix[0], 0f)
    }

    @Test
    fun `internal HAL hold is not promoted to operator AWB lock authority`() {
        val capture = Niv99CaptureMetadata().apply {
            identity.set(Niv99TestFixtures.CAMERA_ID, 8L, 100L, 10_000_000L)
            exposureTimeNs = 9_000_000L
            frameDurationNs = 40_000_000L
            halAwbLocked = true
            halGainsAvailable = true
            halRedGain = 1.8f
            halGreenEvenGain = 1f
            halGreenOddGain = 1f
            halBlueGain = 1.3f
        }
        val context = Niv99FrameContext()

        assertEquals(
            Niv99ValidationResult.VALID,
            capture.applyTo(
                context,
                Niv99UserControlMetadata(),
                Niv99OperatingMode.VIDEO,
                Niv99SignalDomain.DISPLAY_RGB,
                Niv99DetailProfile.NATURAL,
                1_920,
                1_080,
                25,
                5L,
                6L,
                7L
            )
        )
        assertTrue(context.whiteBalanceIntent.halAwbHeld)
        assertFalse(context.whiteBalanceIntent.awbLockRequested)
        assertFalse(context.whiteBalanceIntent.awbLockActive)
    }

    @Test
    fun `missing current HAL gains cannot reuse stale numeric payload`() {
        val capture = Niv99CaptureMetadata().apply {
            identity.set(Niv99TestFixtures.CAMERA_ID, 1L, 1L, 40_000_000L)
            exposureTimeNs = 8_000_000L
            frameDurationNs = 40_000_000L
            halGainsAvailable = true
            halRedGain = 1.8f
            halGreenEvenGain = 1.01f
            halGreenOddGain = 0.99f
            halBlueGain = 1.3f
        }
        val context = Niv99FrameContext()
        assertEquals(
            Niv99ValidationResult.VALID,
            capture.applyTo(
                context,
                Niv99UserControlMetadata(),
                Niv99OperatingMode.VIDEO,
                Niv99SignalDomain.SENSOR_LINEAR_RAW,
                Niv99DetailProfile.NATURAL,
                64,
                64,
                25,
                1L,
                1L,
                1L
            )
        )
        assertTrue(context.whiteBalanceIntent.hasValidHalGains())

        capture.identity.set(Niv99TestFixtures.CAMERA_ID, 1L, 2L, 80_000_000L)
        capture.halGainsAvailable = false
        assertEquals(
            Niv99ValidationResult.VALID,
            capture.applyTo(
                context,
                Niv99UserControlMetadata(),
                Niv99OperatingMode.VIDEO,
                Niv99SignalDomain.SENSOR_LINEAR_RAW,
                Niv99DetailProfile.NATURAL,
                64,
                64,
                25,
                1L,
                1L,
                1L
            )
        )
        assertFalse(context.whiteBalanceIntent.halGainsAvailable)
        assertFalse(context.whiteBalanceIntent.hasValidHalGains())
    }

    @Test
    fun `manual capture controls overwrite conflicting HAL authority fields`() {
        val capture = Niv99CaptureMetadata().apply {
            identity.set(Niv99TestFixtures.CAMERA_ID, 1L, 2L, 80_000_000L)
            exposureTimeNs = 1L
            frameDurationNs = 40_000_000L
            halGainsAvailable = true
            halRedGain = 7f
            halGreenEvenGain = 1f
            halGreenOddGain = 1f
            halBlueGain = 0.2f
        }
        val controls = Niv99UserControlMetadata().apply {
            manualKelvin = 4_300
            manualTint = -11
            manualRedGain = 2f
            manualGreenGain = 1f
            manualBlueGain = 1.4f
        }
        val context = Niv99FrameContext()
        assertEquals(
            Niv99ValidationResult.VALID,
            capture.applyTo(
                context,
                controls,
                Niv99OperatingMode.PRO,
                Niv99SignalDomain.SENSOR_LINEAR_RAW,
                Niv99DetailProfile.EXTREME,
                64,
                64,
                25,
                1L,
                1L,
                1L
            )
        )
        val estimate = Niv99IlluminantEstimate().apply {
            identity.copyFrom(context.identity)
            redGain = 0.25f
            greenGain = 1f
            blueGain = 7f
            cctKelvin = 12_000f
            confidence = 1f
        }
        val output = Niv99WhiteBalanceSolution()
        assertEquals(
            Niv99ValidationResult.VALID,
            Niv99WhiteBalanceController().resolve(
                context,
                Niv99TestFixtures.calibration(),
                estimate,
                null,
                output
            )
        )
        assertEquals(Niv99WhiteBalanceAuthority.MANUAL, output.authority)
        assertEquals(2f, output.redGain, 0f)
        assertEquals(1.4f, output.blueGain, 0f)
    }

    @Test
    fun `RAW16 bounded copy decodes little endian with row padding`() {
        val width = 3
        val height = 2
        val rowStride = 8
        val bytes = ByteBuffer.allocate(rowStride * height)
        putLittleEndian16(bytes, 0, 1)
        putLittleEndian16(bytes, 2, 257)
        putLittleEndian16(bytes, 4, 4_095)
        bytes.put(6, 0x55.toByte())
        bytes.put(7, 0x66.toByte())
        putLittleEndian16(bytes, 8, 65_535)
        putLittleEndian16(bytes, 10, 1_024)
        putLittleEndian16(bytes, 12, 32_768)
        val output = FloatArray(width * height)
        assertEquals(
            Niv99PlaneCopyStatus.VALID,
            Niv99Raw16PlaneAdapter.copyUnsignedRaw16(
                bytes,
                0,
                width,
                height,
                rowStride,
                2,
                Niv99PackedEndian.LITTLE_ENDIAN,
                output
            )
        )
        assertArrayEquals(floatArrayOf(1f, 257f, 4_095f, 65_535f, 1_024f, 32_768f), output, 0f)
    }

    @Test
    fun `RAW16 bounded copy decodes big endian and pixel padding`() {
        val bytes = ByteBuffer.allocate(12)
        putBigEndian16(bytes, 0, 0x1234)
        bytes.put(2, 0x7f)
        bytes.put(3, 0x7f)
        putBigEndian16(bytes, 4, 0xabcd)
        putBigEndian16(bytes, 8, 0xffff)
        val output = FloatArray(3)
        assertEquals(
            Niv99PlaneCopyStatus.VALID,
            Niv99Raw16PlaneAdapter.copyUnsignedRaw16(
                bytes,
                0,
                2,
                1,
                8,
                4,
                Niv99PackedEndian.BIG_ENDIAN,
                output,
                outputOffset = 1,
                outputRowStride = 2
            )
        )
        assertArrayEquals(floatArrayOf(0f, 0x1234.toFloat(), 0xabcd.toFloat()), output, 0f)
    }

    @Test
    fun `RAW16 adapter validates all bounds before touching output`() {
        val input = ByteBuffer.allocate(8)
        val sentinel = FloatArray(8) { 99f }
        assertEquals(
            Niv99PlaneCopyStatus.INPUT_TOO_SMALL,
            Niv99Raw16PlaneAdapter.copyUnsignedRaw16(
                input,
                0,
                4,
                2,
                8,
                2,
                Niv99PackedEndian.LITTLE_ENDIAN,
                sentinel
            )
        )
        assertArrayEquals(FloatArray(8) { 99f }, sentinel, 0f)
        assertEquals(
            Niv99PlaneCopyStatus.INVALID_STRIDE,
            Niv99Raw16PlaneAdapter.copyUnsignedRaw16(
                ByteBuffer.allocate(32),
                0,
                4,
                2,
                7,
                2,
                Niv99PackedEndian.LITTLE_ENDIAN,
                sentinel
            )
        )
        assertArrayEquals(FloatArray(8) { 99f }, sentinel, 0f)
    }

    @Test
    fun `P010 descriptor rejects an eight bit intermediate`() {
        val luma = ByteBuffer.allocate(64)
        val chroma = ByteBuffer.allocate(32)
        val descriptor = Niv99P010PlaneDescriptor().set(
            luma = luma,
            chroma = chroma,
            width = 4,
            height = 4,
            lumaRowStrideBytes = 8,
            chromaRowStrideBytes = 8,
            lumaPixelStrideBytes = 2,
            chromaPixelStrideBytes = 2,
            bitDepth = 8
        )
        assertEquals(Niv99PlaneCopyStatus.UNSUPPORTED_PIXEL_LAYOUT, descriptor.validate())
        descriptor.bitDepth = 10
        assertEquals(Niv99PlaneCopyStatus.VALID, descriptor.validate())
    }

    @Test
    fun `reference illuminant map preserves standard DNG anchors`() {
        assertEquals(2_850f, Niv99ReferenceIlluminant.kelvin(3), 0f)
        assertEquals(2_856f, Niv99ReferenceIlluminant.kelvin(17), 0f)
        assertEquals(4_874f, Niv99ReferenceIlluminant.kelvin(18), 0f)
        assertEquals(6_774f, Niv99ReferenceIlluminant.kelvin(19), 0f)
        assertEquals(5_500f, Niv99ReferenceIlluminant.kelvin(20), 0f)
        assertEquals(6_500f, Niv99ReferenceIlluminant.kelvin(21), 0f)
        assertEquals(7_500f, Niv99ReferenceIlluminant.kelvin(22), 0f)
        assertEquals(5_000f, Niv99ReferenceIlluminant.kelvin(23), 0f)
        assertEquals(4_321f, Niv99ReferenceIlluminant.kelvin(999, 4_321f), 0f)
    }

    private fun staticMetadata(
        pattern: Niv99CfaPattern = Niv99CfaPattern.RGGB
    ): Niv99StaticCameraMetadata = Niv99StaticCameraMetadata().apply {
        cameraId = Niv99TestFixtures.CAMERA_ID
        cfaPattern = pattern
        blackLevelPhysicalTile.fill(64f)
        blackLevelAvailable = true
        whiteLevel = 4_095f
        whiteLevelAvailable = true
        referenceIlluminant1Kelvin = 2_856f
        referenceIlluminant2Kelvin = 6_500f
        colorTransformPairAvailable = true
        calibrationTransformPairAvailable = true
        forwardMatrixPairAvailable = true
        Niv99Math.setIdentity3(colorTransform1)
        Niv99Math.setIdentity3(colorTransform2)
        Niv99Math.setIdentity3(calibrationTransform1)
        Niv99Math.setIdentity3(calibrationTransform2)
        Niv99Math.setIdentity3(forwardMatrix1)
        Niv99Math.setIdentity3(forwardMatrix2)
        fallbackShotNoise.fill(0.002f)
        fallbackReadNoise.fill(0.00004f)
        fallbackNoiseSource = Niv99NoiseCalibrationSource.SENSITIVITY_RANGE_ESTIMATE
        referenceIso = 100
    }

    private fun measuredNoiseMetadata(
        domain: Niv99NoiseCoefficientDomain
    ): Niv99DynamicSensorMetadata = Niv99DynamicSensorMetadata().apply {
        noiseProfileAvailable = true
        noiseCoefficientDomain = domain
        noiseShotRggb[0] = 0.012f
        noiseShotRggb[1] = 0.010f
        noiseShotRggb[2] = 0.011f
        noiseShotRggb[3] = 0.014f
        noiseReadRggb[0] = 0.00014f
        noiseReadRggb[1] = 0.00010f
        noiseReadRggb[2] = 0.00011f
        noiseReadRggb[3] = 0.00016f
    }

    private fun putLittleEndian16(buffer: ByteBuffer, index: Int, value: Int) {
        buffer.put(index, (value and 0xff).toByte())
        buffer.put(index + 1, ((value ushr 8) and 0xff).toByte())
    }

    private fun putBigEndian16(buffer: ByteBuffer, index: Int, value: Int) {
        buffer.put(index, ((value ushr 8) and 0xff).toByte())
        buffer.put(index + 1, (value and 0xff).toByte())
    }
}
