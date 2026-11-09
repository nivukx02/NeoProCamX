package com.neoproc.neoproccamx.niv99

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class Niv99ShaderAbiTest {
    @Test
    fun `uniform and record sizes remain native compatible`() {
        assertEquals(256, Niv99ShaderAbi.UNIFORM_BYTES)
        assertEquals(64, Niv99ShaderAbi.TILE_CONTROL_BYTES)
        assertEquals(64, Niv99ShaderAbi.TILE_STATISTICS_BYTES)
        assertEquals(80, Niv99ShaderAbi.TELEMETRY_RECORD_BYTES)
        assertEquals(16, Niv99ShaderAbi.DESCRIPTOR_BINDING_COUNT)
    }

    @Test
    fun `upload writes exact frame provenance as split unsigned words`() {
        val fixture = completeFixture()
        assertEquals(Niv99ValidationResult.VALID, fixture.write())
        val uniform = fixture.workspace.uniformBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val cameraHash = Niv99ShaderAbi.cameraIdHash(fixture.context.identity.cameraId)
        assertEquals(cameraHash, readSplitLong(uniform, Niv99ShaderAbi.IDENTITY_LOW_OFFSET))
        assertEquals(
            fixture.context.identity.sessionGeneration,
            readSplitLong(uniform, Niv99ShaderAbi.IDENTITY_LOW_OFFSET + Int.SIZE_BYTES)
        )
        assertEquals(
            fixture.context.identity.frameNumber,
            readSplitLong(uniform, Niv99ShaderAbi.IDENTITY_LOW_OFFSET + 2 * Int.SIZE_BYTES)
        )
        assertEquals(
            fixture.context.identity.sensorTimestampNs,
            readSplitLong(uniform, Niv99ShaderAbi.IDENTITY_LOW_OFFSET + 3 * Int.SIZE_BYTES)
        )
    }

    @Test
    fun `uniform layout carries full precision calibration and matching WB matrix`() {
        val fixture = completeFixture()
        fixture.whiteBalance.redGain = 1.75f
        fixture.whiteBalance.greenGain = 1.02f
        fixture.whiteBalance.blueGain = 2.20f
        for (index in fixture.whiteBalance.colorMatrix.indices) {
            fixture.whiteBalance.colorMatrix[index] = (index + 1) * 0.03125f
        }
        assertEquals(Niv99ValidationResult.VALID, fixture.write())
        val uniform = fixture.workspace.uniformBuffer
        assertEquals(1.75f, uniform.getFloat(Niv99ShaderAbi.WHITE_BALANCE_OFFSET), 0f)
        assertEquals(1.02f, uniform.getFloat(Niv99ShaderAbi.WHITE_BALANCE_OFFSET + 4), 0f)
        assertEquals(1.02f, uniform.getFloat(Niv99ShaderAbi.WHITE_BALANCE_OFFSET + 8), 0f)
        assertEquals(2.20f, uniform.getFloat(Niv99ShaderAbi.WHITE_BALANCE_OFFSET + 12), 0f)
        assertEquals(
            fixture.whiteBalance.colorMatrix[0],
            uniform.getFloat(Niv99ShaderAbi.COLOR_MATRIX_ROW_0_OFFSET),
            0f
        )
        assertEquals(
            fixture.whiteBalance.colorMatrix[4],
            uniform.getFloat(Niv99ShaderAbi.COLOR_MATRIX_ROW_1_OFFSET + 4),
            0f
        )
        assertEquals(
            fixture.whiteBalance.colorMatrix[8],
            uniform.getFloat(Niv99ShaderAbi.COLOR_MATRIX_ROW_2_OFFSET + 8),
            0f
        )
    }

    @Test
    fun `tile control upload preserves denoise detail skin and local illuminant fields`() {
        val fixture = completeFixture()
        fixture.spatial.rawStrength[0] = 0.21f
        fixture.spatial.lumaStrength[0] = 0.32f
        fixture.spatial.chromaStrength[0] = 0.76f
        fixture.spatial.edgeProtection[0] = 0.81f
        fixture.spatial.textureProtection[0] = 0.72f
        fixture.spatial.skinProtection[0] = 0.63f
        fixture.spatial.sharpening[0] = 0.14f
        fixture.spatial.waveletFineThreshold[0] = 0.011f
        fixture.spatial.waveletMediumThreshold[0] = 0.022f
        fixture.spatial.waveletCoarseThreshold[0] = 0.033f
        fixture.localIlluminant.redGain[0] = 1.18f
        fixture.localIlluminant.blueGain[0] = 0.87f
        fixture.localIlluminant.confidence[0] = 0.66f
        assertEquals(Niv99ValidationResult.VALID, fixture.write())
        val controls = fixture.workspace.tileControlBuffer
        assertEquals(0.21f, controls.getFloat(0), 0f)
        assertEquals(0.32f, controls.getFloat(4), 0f)
        assertEquals(0.76f, controls.getFloat(8), 0f)
        assertEquals(fixture.temporal.temporalStrength, controls.getFloat(12), 0f)
        assertEquals(0.81f, controls.getFloat(16), 0f)
        assertEquals(0.72f, controls.getFloat(20), 0f)
        assertEquals(0.63f, controls.getFloat(24), 0f)
        assertEquals(0.14f, controls.getFloat(28), 0f)
        assertEquals(0.011f, controls.getFloat(32), 0f)
        assertEquals(0.022f, controls.getFloat(36), 0f)
        assertEquals(0.033f, controls.getFloat(40), 0f)
        assertEquals(1.18f, controls.getFloat(48), 0f)
        assertEquals(1f, controls.getFloat(52), 0f)
        assertEquals(0.87f, controls.getFloat(56), 0f)
        assertEquals(0.66f, controls.getFloat(60), 0f)
    }

    @Test
    fun `stale noise or WB provenance is rejected before native upload`() {
        val fixture = completeFixture()
        fixture.noise.identity.set(
            fixture.context.identity.cameraId,
            fixture.context.identity.sessionGeneration,
            fixture.context.identity.frameNumber - 1L,
            fixture.context.identity.sensorTimestampNs - 1L
        )
        assertEquals(Niv99ValidationResult.STALE_METADATA, fixture.write())
    }

    @Test
    fun `AWB lock may carry one past source frame on the same timeline`() {
        val fixture = completeFixture()
        val lockedSource = Niv99FrameIdentity().copyFrom(fixture.whiteBalance.sourceIdentity)
        fixture.context.identity.set(Niv99TestFixtures.CAMERA_ID, 1L, 2L, 80_000_000L)
        fixture.noise.identity.copyFrom(fixture.context.identity)
        fixture.temporal.identity.copyFrom(fixture.context.identity)
        fixture.whiteBalance.identity.copyFrom(fixture.context.identity)
        fixture.whiteBalance.sourceIdentity.copyFrom(lockedSource)
        fixture.whiteBalance.authority = Niv99WhiteBalanceAuthority.AWB_LOCK
        assertEquals(Niv99ValidationResult.VALID, fixture.write())
    }

    @Test
    fun `AWB lock rejects cross session and future source provenance`() {
        val fixture = completeFixture()
        fixture.context.identity.set(Niv99TestFixtures.CAMERA_ID, 1L, 2L, 80_000_000L)
        fixture.noise.identity.copyFrom(fixture.context.identity)
        fixture.temporal.identity.copyFrom(fixture.context.identity)
        fixture.whiteBalance.identity.copyFrom(fixture.context.identity)
        fixture.whiteBalance.authority = Niv99WhiteBalanceAuthority.AWB_LOCK

        fixture.whiteBalance.sourceIdentity.set(
            Niv99TestFixtures.CAMERA_ID,
            2L,
            1L,
            40_000_000L
        )
        assertEquals(Niv99ValidationResult.STALE_METADATA, fixture.write())

        fixture.whiteBalance.sourceIdentity.set(
            Niv99TestFixtures.CAMERA_ID,
            1L,
            3L,
            120_000_000L
        )
        assertEquals(Niv99ValidationResult.STALE_METADATA, fixture.write())
    }

    @Test
    fun `upload workspace reuses direct buffers across frames`() {
        val fixture = completeFixture()
        val uniforms = fixture.workspace.uniformBuffer
        val controls = fixture.workspace.tileControlBuffer
        assertEquals(Niv99ValidationResult.VALID, fixture.write())
        assertEquals(Niv99ValidationResult.VALID, fixture.write())
        assertSame(uniforms, fixture.workspace.uniformBuffer)
        assertSame(controls, fixture.workspace.tileControlBuffer)
        assertEquals(Niv99ShaderAbi.UNIFORM_BYTES, uniforms.limit())
        assertEquals(fixture.statistics.count * Niv99ShaderAbi.TILE_CONTROL_BYTES, controls.limit())
    }

    @Test
    fun `dispatch geometry rounds up without truncating edge pixels`() {
        assertEquals(0, Niv99ShaderAbi.dispatchCount(0, 16))
        assertEquals(1, Niv99ShaderAbi.dispatchCount(1, 16))
        assertEquals(1, Niv99ShaderAbi.dispatchCount(16, 16))
        assertEquals(2, Niv99ShaderAbi.dispatchCount(17, 16))
        assertEquals(481, Niv99ShaderAbi.dispatchCount(7_681, 16))
    }

    @Test
    fun `non Bayer calibration cannot enter Bayer shader graph`() {
        val fixture = completeFixture()
        fixture.calibration.cfaPattern = Niv99CfaPattern.MONO
        assertEquals(Niv99ValidationResult.INVALID_CALIBRATION, fixture.write())
    }

    private fun readSplitLong(buffer: ByteBuffer, lowOffset: Int): Long {
        val component = (lowOffset - Niv99ShaderAbi.IDENTITY_LOW_OFFSET) / Int.SIZE_BYTES
        val highOffset = Niv99ShaderAbi.IDENTITY_HIGH_OFFSET + component * Int.SIZE_BYTES
        val low = buffer.getInt(lowOffset).toLong() and 0xffff_ffffL
        val high = buffer.getInt(highOffset).toLong() and 0xffff_ffffL
        return low or (high shl 32)
    }

    private fun completeFixture(): ShaderFixture {
        val context = Niv99TestFixtures.context().apply {
            signalDomain = Niv99SignalDomain.SENSOR_LINEAR_RAW
            meanLinearSignal = 0.18f
        }
        val calibration = Niv99TestFixtures.calibration()
        val noise = Niv99TestFixtures.noiseSnapshot(context)
        val whiteBalance = Niv99WhiteBalanceSolution().apply {
            identity.copyFrom(context.identity)
            sourceIdentity.copyFrom(context.identity)
            authority = Niv99WhiteBalanceAuthority.NIV99_AUTO
            colorMatrixValid = true
            Niv99Math.setIdentity3(colorMatrix)
        }
        val statistics = Niv99TileStatistics(TILE_COUNT).apply { clear(2, 2) }
        val spatial = Niv99SpatialPlan(TILE_COUNT).apply {
            count = TILE_COUNT
            meanSharpening = 0.12f
            for (index in 0 until count) {
                rawStrength[index] = 0.4f
                lumaStrength[index] = 0.3f
                chromaStrength[index] = 0.6f
                waveletFineThreshold[index] = 0.01f
                waveletMediumThreshold[index] = 0.02f
                waveletCoarseThreshold[index] = 0.03f
                edgeProtection[index] = 0.5f
                textureProtection[index] = 0.6f
                skinProtection[index] = 0.2f
                sharpening[index] = 0.1f
            }
        }
        val temporal = Niv99TemporalFusionPlan(4).apply {
            identity.copyFrom(context.identity)
            historyCount = 2
            totalHistoryWeight = 0.8f
            temporalStrength = 0.45f
            chromaFlickerSuppression = 0.55f
        }
        val localIlluminant = Niv99LocalIlluminantField(TILE_COUNT).apply { reset(2, 2) }
        return ShaderFixture(
            context,
            calibration,
            statistics,
            noise,
            whiteBalance,
            spatial,
            temporal,
            localIlluminant,
            Niv99GpuMotionParameters(),
            Niv99GpuUploadWorkspace(TILE_COUNT)
        )
    }

    private data class ShaderFixture(
        val context: Niv99FrameContext,
        val calibration: Niv99SensorCalibration,
        val statistics: Niv99TileStatistics,
        val noise: Niv99NoiseModelSnapshot,
        val whiteBalance: Niv99WhiteBalanceSolution,
        val spatial: Niv99SpatialPlan,
        val temporal: Niv99TemporalFusionPlan,
        val localIlluminant: Niv99LocalIlluminantField,
        val motion: Niv99GpuMotionParameters,
        val workspace: Niv99GpuUploadWorkspace
    ) {
        fun write(): Niv99ValidationResult = workspace.write(
            context,
            calibration,
            statistics,
            noise,
            whiteBalance,
            spatial,
            temporal,
            localIlluminant,
            motion,
            persistentDefectMapEnabled = true
        )
    }

    companion object {
        private const val TILE_COUNT = 4
    }
}
