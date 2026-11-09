package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.ai.SubjectCategory
import com.neoproc.neoproccamx.ai.SubjectMask
import com.neoproc.neoproccamx.camera.HdrDynamicRangeMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNotNull
import org.junit.Test

class NdrRenderProfilePacketSnapshotTest {
    @Test
    fun `revision sequencer is stable for duplicates and advances for native visible changes`() {
        val sequencer = NdrRenderProfileRevisionSequencer()

        val first = sequencer.assign(candidate(5L, geometryMarker = 1f, enhancement = "a"))
        val duplicate = sequencer.assign(candidate(5L, geometryMarker = 1f, enhancement = "a"))
        val geometryChanged = sequencer.assign(
            candidate(5L, geometryMarker = 2f, enhancement = "a")
        )
        val enhancementChanged = sequencer.assign(
            candidate(5L, geometryMarker = 2f, enhancement = "b")
        )
        val nextGeneration = sequencer.assign(
            candidate(6L, geometryMarker = 2f, enhancement = "b")
        )

        assertEquals(1L, first.configRevision)
        assertEquals(first.identity, duplicate.identity)
        assertEquals(2L, geometryChanged.configRevision)
        assertEquals(3L, enhancementChanged.configRevision)
        assertEquals(NdrRenderProfileIdentity(6L, 1L), nextGeneration.identity)
    }

    @Test
    fun `packet snapshot deep copies every render array and subject mask`() {
        val sourceConfig = VulkanRenderConfig(
            previewTransform = floats(16, 10f),
            encoderTransform = floats(16, 20f),
            colorGain = floats(4, 30f),
            previewGrade = floats(16, 40f),
            encoderGrade = floats(16, 50f),
            previewColorScience = floats(VulkanColorScienceUniforms.FLOAT_COUNT, 60f),
            encoderColorScience = floats(VulkanColorScienceUniforms.FLOAT_COUNT, 70f)
        )
        val sourceMask = SubjectMask(
            maskWidth = 3,
            maskHeight = 2,
            alphaMask = byteArrayOf(0, 24, 63, 127, -64, -1),
            trackingId = 81,
            category = SubjectCategory.HUMAN,
            intensity = 0.75f
        )
        val packet = NdrRenderProfilePacket(
            profileGeneration = 47L,
            configRevision = 9L,
            correction = NdrNivukxMeasuredCorrectionPolicy.resolve("NDR10+"),
            enhancementUniforms = ndrUniforms(),
            nativeRenderConfig = VulkanNativeRenderProfileConfig(
                hdrDynamicRangeMode = HdrDynamicRangeMode.HDR10,
                preferTenBitSdrDisplay = true,
                renderConfig = sourceConfig
            ),
            subjectDetailMask = sourceMask
        )
        val expectedArrays = listOf(
            sourceConfig.previewTransform.copyOf(),
            sourceConfig.encoderTransform.copyOf(),
            sourceConfig.colorGain.copyOf(),
            sourceConfig.previewGrade.copyOf(),
            sourceConfig.encoderGrade.copyOf(),
            sourceConfig.previewColorScience.copyOf(),
            sourceConfig.encoderColorScience.copyOf()
        )
        val expectedMask = sourceMask.alphaMask.copyOf()

        val snapshot = packet.immutableSnapshot()
        val snapshotConfig = snapshot.nativeRenderConfig.renderConfig
        assertNotNull(snapshot.subjectDetailMask)
        val snapshotMask = requireNotNull(snapshot.subjectDetailMask)

        assertNotSame(sourceConfig, snapshotConfig)
        assertNotSame(sourceConfig.previewTransform, snapshotConfig.previewTransform)
        assertNotSame(sourceConfig.encoderTransform, snapshotConfig.encoderTransform)
        assertNotSame(sourceConfig.colorGain, snapshotConfig.colorGain)
        assertNotSame(sourceConfig.previewGrade, snapshotConfig.previewGrade)
        assertNotSame(sourceConfig.encoderGrade, snapshotConfig.encoderGrade)
        assertNotSame(sourceConfig.previewColorScience, snapshotConfig.previewColorScience)
        assertNotSame(sourceConfig.encoderColorScience, snapshotConfig.encoderColorScience)
        assertNotSame(sourceMask, snapshotMask)
        assertNotSame(sourceMask.alphaMask, snapshotMask.alphaMask)

        sourceConfig.previewTransform.fill(-1f)
        sourceConfig.encoderTransform.fill(-2f)
        sourceConfig.colorGain.fill(-3f)
        sourceConfig.previewGrade.fill(-4f)
        sourceConfig.encoderGrade.fill(-5f)
        sourceConfig.previewColorScience.fill(-6f)
        sourceConfig.encoderColorScience.fill(-7f)
        sourceMask.alphaMask.fill(42)

        val actualArrays = listOf(
            snapshotConfig.previewTransform,
            snapshotConfig.encoderTransform,
            snapshotConfig.colorGain,
            snapshotConfig.previewGrade,
            snapshotConfig.encoderGrade,
            snapshotConfig.previewColorScience,
            snapshotConfig.encoderColorScience
        )
        expectedArrays.zip(actualArrays).forEach { (expected, actual) ->
            assertArrayEquals(expected, actual, 0f)
        }
        assertArrayEquals(expectedMask, snapshotMask.alphaMask)
        assertEquals(47L, snapshot.profileGeneration)
        assertEquals(9L, snapshot.configRevision)
        assertEquals(NdrRenderProfileIdentity(47L, 9L), snapshot.identity)
        assertEquals("snapshot-ndr", snapshot.enhancementUniforms.signature)
        assertEquals(NdrNivukxMeasuredMode.NDR10_PLUS, snapshot.correction.mode)
    }

    private fun floats(size: Int, base: Float): FloatArray =
        FloatArray(size) { index -> base + index / 100f }

    private fun candidate(
        generation: Long,
        geometryMarker: Float,
        enhancement: String
    ): NdrRenderProfilePacket {
        val previewTransform = VulkanRenderConfig().previewTransform.copyOf()
        previewTransform[0] = geometryMarker
        return NdrRenderProfilePacket(
            profileGeneration = generation,
            configRevision = NdrRenderProfileRevisionSequencer.UNASSIGNED_REVISION,
            correction = NdrNivukxMeasuredCorrectionPolicy.resolve("NDR10+"),
            enhancementUniforms = ndrUniforms().copy(signature = enhancement),
            nativeRenderConfig = VulkanNativeRenderProfileConfig(
                hdrDynamicRangeMode = HdrDynamicRangeMode.HDR10,
                preferTenBitSdrDisplay = true,
                renderConfig = VulkanRenderConfig(previewTransform = previewTransform)
            ),
            subjectDetailMask = null
        )
    }

    private fun ndrUniforms(): GpuImageEnhancementUniforms = GpuImageEnhancementUniforms(
        signalFamily = GpuSignalFamily.HDR,
        colorSpace = GpuColorSpace.REC2020,
        colorScience = GpuColorScience.NDR10_PLUS,
        activeTransport = GpuActiveTransport.BT2100_PQ_MAIN10,
        temporalBlend = 0.20f,
        spatialDenoise = 0.30f,
        chromaDenoise = 0.40f,
        shadowBoost = 0.10f,
        sharpening = 0.50f,
        warmth = 0f,
        contrast = 1f,
        saturation = 1f,
        highlightCompression = 0.20f,
        logStrength = 0f,
        videoHdrFusionStrength = 0.60f,
        shadowChromaDeadband = 0.01f,
        aliasSafeMaxDetailDelta = 0.02f,
        fineTextureThreshold = 0.03f,
        mediumEdgeThreshold = 0.04f,
        strongEdgeThreshold = 0.05f,
        fineTextureDetailGain = 0.20f,
        mediumDetailGain = 0.30f,
        strongDetailGain = 0.40f,
        temporalLumaBlend = 0.30f,
        temporalChromaBlend = 0.20f,
        iso = 400f,
        noiseMetric = 0.10f,
        localChromaSpikeSmoothing = 0.20f,
        sharpeningFloor = 0.10f,
        sharpeningCeiling = 0.80f,
        skinDetailProtection = 0.60f,
        migrationApplied = false,
        dualGainFusionEnabled = true,
        dualGainMotionMaskEnabled = true,
        dualGainKGain = 4f,
        dualGainNoiseFloor = 0.01f,
        dualGainSaturationThreshold = 0.90f,
        dualGainTimestampSkew = 0f,
        sensorShotNoiseCoefficient = 0.003f,
        sensorReadNoiseCoefficient = 0.001f,
        detailProfileId = 2f,
        subjectMaskAvailable = 1f,
        subjectDetailGain = 0.50f,
        subjectMaskFeather = 0.20f,
        signature = "snapshot-ndr"
    )
}
