package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.color.NdrToneOperator
import com.neoproc.neoproccamx.recording.RecordDevelopP010ColorIngress.OutputTransfer
import com.neoproc.neoproccamx.recording.VideoDevelopColorSidecar.DevelopColorSpace
import com.neoproc.neoproccamx.recording.VideoDevelopColorSidecar.DevelopColorTransfer
import com.neoproc.neoproccamx.snce.SnceIlluminantEstimator
import com.neoproc.neoproccamx.snce.SnceNeuralEnhancement
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordDevelopNdrVulkanAbiContractTest {

    @Test
    fun ndrPacketCarriesCanonicalCpuToneAndColourParameters() {
        val canonical = NdrToneOperator.ndr10Plus()
        val candidate = NativeRecordDevelopGpuAccelerator.buildParamsOrNull(
                RecordDevelopGpuContext(
                    format = RecordDevelopDecodedFormat(width = 16, height = 8, fps = 30),
                    target = DevelopTarget.NDR_PQ_BT2020,
                    decodeSpace = DevelopColorSpace.REC2020,
                    decodeTransfer = DevelopColorTransfer.HLG,
                    outputTransfer = OutputTransfer.PQ_ST2084,
                    snceAppearanceStrength = 1f,
                    filmPrintStrength = 0f,
                    warmSkinRedTameStrength = NdrWarmSkinRedTameFlag.effectiveStrength.toFloat(),
                    acesToneMap = false,
                    semanticClass = SnceNeuralEnhancement.SemanticClass.OTHER,
                    clampOutput = true,
                    ndrGenuineHdr = true,
                    diffuseWhiteNits = 100f, // Must not override the canonical NDR 203-nit anchor.
                    widenToRec2020 = false,
                    workingRgbToXyz = SnceIlluminantEstimator.REC2020_TO_XYZ.copyOf(),
                    sceneLuminanceLA = VideoDevelopColorPipeline.DEFAULT_SCENE_LA
                )
            )
        assertNotNull(candidate)
        val packet = requireNotNull(candidate)

        assertEquals(NativeRecordDevelopGpuAccelerator.PARAM_FLOAT_COUNT, packet.size)
        assertEquals(64, NativeRecordDevelopGpuAccelerator.FILM_LUT_OFFSET)
        assertEquals(canonical.diffuseWhiteNits.toFloat(), packet[5], FLOAT_EPSILON)
        assertEquals(canonical.signalGamma.toFloat(), packet[NativeRecordDevelopGpuAccelerator.PARAM_NDR_SIGNAL_GAMMA], FLOAT_EPSILON)
        assertEquals(canonical.toeWidth.toFloat(), packet[NativeRecordDevelopGpuAccelerator.PARAM_NDR_TOE_WIDTH], FLOAT_EPSILON)
        assertEquals(canonical.toeAmount.toFloat(), packet[NativeRecordDevelopGpuAccelerator.PARAM_NDR_TOE_AMOUNT], FLOAT_EPSILON)
        assertEquals(canonical.peakNits.toFloat(), packet[NativeRecordDevelopGpuAccelerator.PARAM_NDR_PEAK_NITS], FLOAT_EPSILON)
        assertEquals(canonical.highlightDesat.toFloat(), packet[NativeRecordDevelopGpuAccelerator.PARAM_NDR_HIGHLIGHT_DESAT], FLOAT_EPSILON)
        assertEquals(canonical.shadowMidtoneLift.toFloat(), packet[NativeRecordDevelopGpuAccelerator.PARAM_NDR_SHADOW_MIDTONE_LIFT], FLOAT_EPSILON)
        assertEquals(canonical.midtoneContrast.toFloat(), packet[NativeRecordDevelopGpuAccelerator.PARAM_NDR_MIDTONE_CONTRAST], FLOAT_EPSILON)
        assertEquals(canonical.chromaBoost.toFloat(), packet[NativeRecordDevelopGpuAccelerator.PARAM_NDR_CHROMA_BOOST], FLOAT_EPSILON)
        assertEquals(canonical.skyChromaBonus.toFloat(), packet[NativeRecordDevelopGpuAccelerator.PARAM_NDR_SKY_CHROMA_BONUS], FLOAT_EPSILON)
        assertEquals(
            NdrWarmSkinRedTameFlag.effectiveStrength.toFloat(),
            packet[NativeRecordDevelopGpuAccelerator.PARAM_NDR_WARM_SKIN_RED_TAME],
            FLOAT_EPSILON
        )
        assertEquals(0f, packet[NativeRecordDevelopGpuAccelerator.PARAM_SDR_WARM_SKIN_RED_TAME], FLOAT_EPSILON)
        assertTrue(packet.sliceArray(54 until NativeRecordDevelopGpuAccelerator.FILM_LUT_OFFSET).all { it == 0f })
    }

    @Test
    fun sdrPacketCarriesResolvedWarmSkinStrengthWithoutEnablingNdrCorrection() {
        val resolvedStrength = 0.375f
        val packet = requireNotNull(
            NativeRecordDevelopGpuAccelerator.buildParamsOrNull(
                RecordDevelopGpuContext(
                    format = RecordDevelopDecodedFormat(width = 16, height = 8, fps = 30),
                    target = DevelopTarget.SDR_REC709,
                    decodeSpace = DevelopColorSpace.REC709,
                    decodeTransfer = DevelopColorTransfer.SDR,
                    outputTransfer = OutputTransfer.SDR_REC709_OETF,
                    snceAppearanceStrength = 0.65f,
                    filmPrintStrength = 1f,
                    warmSkinRedTameStrength = resolvedStrength,
                    acesToneMap = false,
                    semanticClass = SnceNeuralEnhancement.SemanticClass.OTHER,
                    clampOutput = true,
                    ndrGenuineHdr = false,
                    diffuseWhiteNits = 203f,
                    widenToRec2020 = false,
                    workingRgbToXyz = SnceIlluminantEstimator.REC709_TO_XYZ.copyOf(),
                    sceneLuminanceLA = VideoDevelopColorPipeline.DEFAULT_SCENE_LA
                )
            )
        )

        assertEquals(
            resolvedStrength,
            packet[NativeRecordDevelopGpuAccelerator.PARAM_SDR_WARM_SKIN_RED_TAME],
            FLOAT_EPSILON
        )
        assertEquals(0f, packet[NativeRecordDevelopGpuAccelerator.PARAM_NDR_WARM_SKIN_RED_TAME], FLOAT_EPSILON)
    }

    @Test
    fun kotlinNativeAndShaderUseOne64FloatScalarAbiAndNoLegacyNdrCurve() {
        val native = File("src/main/cpp/npv10_export_native.cpp").readText()
        val shader = File("src/main/cpp/shaders/vulkan/record_develop_p010_to_rgb10.comp").readText()

        assertTrue(native.contains("kRecordDevelopParamFloatCount = 64u + 33u * 33u * 33u * 3u"))
        assertTrue(shader.contains("const int FILM_LUT_OFFSET = 64;"))
        assertTrue(shader.contains("PARAM_NDR_WARM_SKIN_RED_TAME"))
        assertTrue(shader.contains("PARAM_SDR_WARM_SKIN_RED_TAME"))
        assertTrue(shader.contains("tameWarmSkinRedSdr"))
        val mainFilmStage = shader.indexOf("rgb = applyFilmPrint(rgb, p[38]);")
        val mainSdrCorrection = shader.indexOf("rgb = tameWarmSkinRedSdr(rgb, sdrWarmSkinRedTame);")
        val mainOutputClamp = shader.indexOf("if (clampOutput) rgb = clamp(rgb, vec3(0.0), vec3(1.0));")
        assertTrue(mainFilmStage >= 0)
        assertTrue(mainSdrCorrection > mainFilmStage)
        assertTrue(mainOutputClamp > mainSdrCorrection)
        assertTrue(shader.contains("neoBlackPreservingMidtoneLift"))
        assertTrue(shader.contains("neoProfileMidtoneContrast"))
        assertTrue(shader.contains("neoCompressToPeakColorVolume"))
        assertTrue(!shader.contains("const float g = 0.92"))
        assertTrue(!shader.contains("const float peak = 1420.0"))
    }

    private companion object {
        const val FLOAT_EPSILON = 1.0e-6f
    }
}
