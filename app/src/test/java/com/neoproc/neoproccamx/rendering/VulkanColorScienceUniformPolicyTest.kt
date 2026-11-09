package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VulkanColorScienceUniformPolicyTest {
    @Test
    fun `disabled Neo color payload is finite and leaves the shader branch off`() {
        val uniforms = VulkanColorScienceUniforms.disabled()
        val packed = uniforms.pack()

        assertEquals(VulkanColorScienceUniforms.FLOAT_COUNT, packed.size)
        assertTrue(packed.all { it.isFinite() })
        assertEquals(0f, uniforms.modeParams[0], 0f)
        assertEquals(0f, uniforms.hdrParams[0], 0f)
        assertEquals(0f, uniforms.measuredCorrectionParams[0], 0f)
    }

    @Test
    fun `NDR and NIVUKX measured color corrections are packed for shader delivery`() {
        val ndr = VulkanColorScienceUniformPolicy.resolve(
            enabled = true,
            hdrMappingEnabled = true,
            logDeliveryEnabled = false,
            nivukxVision = false,
            hdrKnee = 0.59f,
            hdrShoulder = 3.72f,
            volumeCompression = 0.54f,
            measuredCorrection = NdrNivukxMeasuredCorrectionPolicy.resolve("NDR10+")
        )
        val nivukx = VulkanColorScienceUniformPolicy.resolve(
            enabled = true,
            hdrMappingEnabled = true,
            logDeliveryEnabled = false,
            nivukxVision = true,
            hdrKnee = 0.55f,
            hdrShoulder = 4.12f,
            volumeCompression = 0.64f,
            measuredCorrection = NdrNivukxMeasuredCorrectionPolicy.resolve("NIVUKXVISION")
        )

        assertEquals(1f, ndr.measuredCorrectionParams[0], 0f)
        assertEquals(6.999f, ndr.measuredCorrectionParams[2], 0.000001f)
        assertEquals(0.034662f, ndr.measuredCorrectionParams[3], 0.000001f)
        assertEquals(1f, nivukx.measuredCorrectionParams[0], 0f)
        assertEquals(2.493f, nivukx.measuredCorrectionParams[1], 0.000001f)
        assertEquals(8.000f, nivukx.measuredCorrectionParams[2], 0.000001f)
        assertEquals(VulkanColorScienceUniforms.FLOAT_COUNT, ndr.pack().size)
    }

    @Test
    fun `NDR payload enables perceptual mapping without enabling log encoding`() {
        val uniforms = VulkanColorScienceUniformPolicy.resolve(
            enabled = true,
            hdrMappingEnabled = true,
            logDeliveryEnabled = false,
            nivukxVision = false,
            hdrKnee = 0.59f,
            hdrShoulder = 3.72f,
            volumeCompression = 0.54f
        )

        assertEquals(1f, uniforms.modeParams[0], 0f)
        assertEquals(0f, uniforms.modeParams[1], 0f)
        assertEquals(0f, uniforms.modeParams[2], 0f)
        assertEquals(1f, uniforms.hdrParams[0], 0f)
        assertEquals(0.59f, uniforms.hdrParams[1], 0f)
        assertEquals(VulkanColorScienceUniforms.FLOAT_COUNT, uniforms.pack().size)
    }

    @Test
    fun `normalized nivukx mode is authoritative over stale natural preference`() {
        val uniforms = VulkanColorScienceUniformPolicy.resolve(
            enabled = true,
            hdrMappingEnabled = true,
            logDeliveryEnabled = false,
            nivukxVision = true,
            hdrKnee = 0.55f,
            hdrShoulder = 4.12f,
            volumeCompression = 0.64f,
            colorSpaceName = "Rec.709",
            colorScienceName = "Natural",
            normalizedHdrMode = "NIVUKXVISION"
        )

        assertEquals(GpuColorSpace.REC2020.id.toFloat(), uniforms.identityParams[0], 0f)
        assertEquals(GpuColorScience.NIVUKX_VISION.id.toFloat(), uniforms.identityParams[1], 0f)
        assertEquals(GpuActiveTransport.BT2100_PQ_MAIN10.id.toFloat(), uniforms.identityParams[2], 0f)
    }

    @Test
    fun `NLogs and NIVUKX signals use independent shader feature flags`() {
        val uniforms = VulkanColorScienceUniformPolicy.resolve(
            enabled = true,
            hdrMappingEnabled = true,
            logDeliveryEnabled = true,
            nivukxVision = true,
            hdrKnee = 0.55f,
            hdrShoulder = 4.12f,
            volumeCompression = 0.64f
        )

        assertEquals(1f, uniforms.modeParams[0], 0f)
        assertEquals(1f, uniforms.modeParams[1], 0f)
        assertEquals(1f, uniforms.modeParams[2], 0f)
        assertEquals(0.64f, uniforms.hdrParams[3], 0f)
    }

    @Test
    fun `log profile identity does not force log encoding in preview monitor packet`() {
        val uniforms = VulkanColorScienceUniformPolicy.resolve(
            enabled = true,
            hdrMappingEnabled = false,
            logDeliveryEnabled = false,
            nivukxVision = false,
            hdrKnee = 0.59f,
            hdrShoulder = 3.72f,
            volumeCompression = 0.54f,
            colorSpaceName = "Rec.2020",
            colorScienceName = "N-Log Cinema"
        )

        assertEquals(GpuColorScience.N_LOG_CINEMA.id.toFloat(), uniforms.identityParams[1], 0f)
        assertEquals(0f, uniforms.modeParams[1], 0f)
    }

    @Test
    fun `Neo ACES High End sets the selectable high end shader lane`() {
        val uniforms = VulkanColorScienceUniformPolicy.resolve(
            enabled = true,
            hdrMappingEnabled = false,
            logDeliveryEnabled = false,
            nivukxVision = false,
            hdrKnee = 0.59f,
            hdrShoulder = 3.72f,
            volumeCompression = 0.54f,
            colorSpaceName = "Neo ACES AP1",
            colorScienceName = "Neo ACES High End"
        )

        assertEquals(1f, uniforms.modeParams[0], 0f)
        assertEquals(0f, uniforms.modeParams[1], 0f)
        assertEquals(0f, uniforms.modeParams[2], 0f)
        assertEquals(3f, uniforms.modeParams[3], 0f)
    }

    @Test
    fun `Neo Wide AP1 and AP0 carry distinct shader mode lanes`() {
        val wide = VulkanColorScienceUniformPolicy.resolve(
            enabled = true,
            hdrMappingEnabled = false,
            logDeliveryEnabled = false,
            nivukxVision = false,
            hdrKnee = 0.59f,
            hdrShoulder = 3.72f,
            volumeCompression = 0.54f,
            colorSpaceName = "Neo Wide Gamut",
            colorScienceName = "Natural"
        )
        val ap1 = VulkanColorScienceUniformPolicy.resolve(
            enabled = true,
            hdrMappingEnabled = false,
            logDeliveryEnabled = false,
            nivukxVision = false,
            hdrKnee = 0.59f,
            hdrShoulder = 3.72f,
            volumeCompression = 0.54f,
            colorSpaceName = "Neo ACES AP1",
            colorScienceName = "Neo ACES High End"
        )
        val ap0 = VulkanColorScienceUniformPolicy.resolve(
            enabled = true,
            hdrMappingEnabled = false,
            logDeliveryEnabled = false,
            nivukxVision = false,
            hdrKnee = 0.59f,
            hdrShoulder = 3.72f,
            volumeCompression = 0.54f,
            colorSpaceName = "Neo ACES AP0",
            colorScienceName = "Neo ACES High End"
        )

        assertEquals(VulkanColorScienceUniforms.FLOAT_COUNT, wide.pack().size)
        assertEquals(VulkanColorScienceUniforms.FLOAT_COUNT, ap1.pack().size)
        assertEquals(VulkanColorScienceUniforms.FLOAT_COUNT, ap0.pack().size)
        assertEquals(2f, wide.modeParams[3], 0f)
        assertEquals(3f, ap1.modeParams[3], 0f)
        assertEquals(4f, ap0.modeParams[3], 0f)
    }

    @Test
    fun `color science packet carries explicit identity lanes after correction params`() {
        val ap0 = VulkanColorScienceUniformPolicy.resolve(
            enabled = true,
            hdrMappingEnabled = false,
            logDeliveryEnabled = false,
            nivukxVision = false,
            hdrKnee = 0.59f,
            hdrShoulder = 3.72f,
            volumeCompression = 0.54f,
            colorSpaceName = "Neo ACES AP0",
            colorScienceName = "Neo ACES High End"
        )
        val nivukx = VulkanColorScienceUniformPolicy.resolve(
            enabled = true,
            hdrMappingEnabled = true,
            logDeliveryEnabled = false,
            nivukxVision = true,
            hdrKnee = 0.55f,
            hdrShoulder = 4.12f,
            volumeCompression = 0.64f,
            colorSpaceName = "Rec.2020-NIVUKX",
            colorScienceName = "NIVUKX Vision"
        )

        assertEquals(52, VulkanColorScienceUniforms.FLOAT_COUNT)
        assertEquals(GpuColorSpace.NEO_ACES_AP0.id.toFloat(), ap0.identityParams[0], 0f)
        assertEquals(GpuColorScience.NEO_ACES_HIGH_END.id.toFloat(), ap0.identityParams[1], 0f)
        assertEquals(GpuActiveTransport.BT2020_SDR.id.toFloat(), ap0.identityParams[2], 0f)
        assertEquals(4f, ap0.identityParams[3], 0f)
        assertEquals(GpuColorSpace.REC2020.id.toFloat(), nivukx.identityParams[0], 0f)
        assertEquals(GpuColorScience.NIVUKX_VISION.id.toFloat(), nivukx.identityParams[1], 0f)
        assertEquals(GpuActiveTransport.BT2100_PQ_MAIN10.id.toFloat(), nivukx.identityParams[2], 0f)
        assertTrue(ap0.pack().sliceArray(44 until 48).contentEquals(ap0.identityParams))
        assertEquals(0.974587f, nivukx.measuredRgbScaleParams[0], 0.000001f)
        assertEquals(0.991713f, nivukx.measuredRgbScaleParams[1], 0.000001f)
        assertEquals(0.991713f, nivukx.measuredRgbScaleParams[2], 0.000001f)
        assertTrue(nivukx.pack().sliceArray(48 until 52).contentEquals(nivukx.measuredRgbScaleParams))
    }

    @Test
    fun `pipeline v1 token keeps legacy NeoGamut shader mode while APEX is default`() {
        val apex = VulkanColorScienceUniformPolicy.resolve(
            enabled = true,
            hdrMappingEnabled = false,
            logDeliveryEnabled = false,
            nivukxVision = false,
            hdrKnee = 0.59f,
            hdrShoulder = 3.72f,
            volumeCompression = 0.54f,
            colorSpaceName = "Neo Wide Gamut",
            colorScienceName = "NeoGamut APEX"
        )
        val legacy = VulkanColorScienceUniformPolicy.resolve(
            enabled = true,
            hdrMappingEnabled = false,
            logDeliveryEnabled = false,
            nivukxVision = false,
            hdrKnee = 0.59f,
            hdrShoulder = 3.72f,
            volumeCompression = 0.54f,
            colorSpaceName = "Neo Wide Gamut pipeline=v1",
            colorScienceName = "Neo Wide Gamut pipeline=v1"
        )

        assertEquals(2f, apex.modeParams[3], 0f)
        assertEquals(0f, legacy.modeParams[3], 0f)
        assertEquals(VulkanColorScienceUniforms.FLOAT_COUNT, apex.pack().size)
        assertEquals(VulkanColorScienceUniforms.FLOAT_COUNT, legacy.pack().size)
    }

    @Test
    fun `render config retains independent preview and encoder color science payloads`() {
        val preview = VulkanColorScienceUniforms.disabled().pack()
        val encoder = VulkanColorScienceUniformPolicy.resolve(
            enabled = true,
            hdrMappingEnabled = false,
            logDeliveryEnabled = true,
            nivukxVision = false,
            hdrKnee = 0.59f,
            hdrShoulder = 3.72f,
            volumeCompression = 0.54f
        ).pack()
        val config = VulkanRenderConfig(
            previewColorScience = preview,
            encoderColorScience = encoder
        )

        assertTrue(config.previewColorScience.contentEquals(preview))
        assertTrue(config.encoderColorScience.contentEquals(encoder))
        assertTrue(config != config.copy(encoderColorScience = preview))
    }
}
