package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.color.NormalSdrVideoQualityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Npv10ExportEnhancementPolicyTest {

    @Test
    fun `strict cinema and log exports suppress visible dithering`() {
        assertTrue(!Npv10ExportEnhancementPolicy.isDitheringRequiredFor("NPV10"))
        assertTrue(!Npv10ExportEnhancementPolicy.isDitheringRequiredFor("NDR10+"))
        assertTrue(!Npv10ExportEnhancementPolicy.isDitheringRequiredFor("NIVUKX Vision"))
        assertTrue(!Npv10ExportEnhancementPolicy.isDitheringRequiredFor("N-Log Cinema"))
        assertTrue(!Npv10ExportEnhancementPolicy.isDitheringRequiredFor("NEOLOG_PRO"))
        assertTrue(Npv10ExportEnhancementPolicy.isDitheringRequiredFor("NORMAL_SDR"))
        assertTrue(Npv10ExportEnhancementPolicy.isDitheringRequiredFor("PRO_SDR"))
    }

    @Test
    fun `normal and pro sdr profiles use bounded distinct export authority`() {
        val normal = sdrProfileFor("NORMAL")
        val pro = sdrProfileFor("PRO")

        assertNotEquals(normal, pro)
        assertEquals(40, normal.aliasSafeMaxDetailDelta)
        assertEquals(40, pro.aliasSafeMaxDetailDelta)
        assertEquals(40, normal.cumulativeDetailBudget)
        assertEquals(40, pro.cumulativeDetailBudget)
        assertTrue(normal.fineTextureDetailGain in 1.0..2.20)
        assertTrue(normal.mediumDetailGain in 1.0..2.20)
        assertTrue(normal.strongDetailGain in 1.0..2.20)
        assertTrue(pro.fineTextureDetailGain >= normal.fineTextureDetailGain)
        assertTrue(pro.mediumDetailGain >= normal.mediumDetailGain)
        assertTrue(pro.strongDetailGain >= normal.strongDetailGain)
        assertTrue(normal.temporalLumaBlend <= 0.72)
        assertTrue(normal.temporalChromaBlend <= 0.72)
        assertTrue(pro.temporalLumaBlend <= 0.72)
        assertTrue(pro.temporalChromaBlend <= 0.72)
        assertTrue(normal.ditherAmplitude in 0.0..0.50)
        assertTrue(pro.ditherAmplitude in 0.0..0.50)
        assertTrue(normal.ditherAmplitude > 0.0)
        assertTrue(pro.ditherAmplitude > 0.0)
    }

    @Test
    fun `ndr nivukx and log profile identities remain unchanged`() {
        val ndr = Npv10ExportEnhancementProfile.NDR10_PLUS
        val nivukx = Npv10ExportEnhancementProfile.NIVUKX_VISION
        assertEquals(220, ndr.aliasSafeMaxDetailDelta)
        assertEquals(220, nivukx.aliasSafeMaxDetailDelta)
        assertEquals(0.0, ndr.ditherAmplitude, 0.0)
        assertEquals(0.0, nivukx.ditherAmplitude, 0.0)
        assertEquals(1.0, ndr.temporalLumaBlend, 0.0)
        assertEquals(1.0, ndr.temporalChromaBlend, 0.0)
        assertEquals(1.0, nivukx.temporalLumaBlend, 0.0)
        assertEquals(1.0, nivukx.temporalChromaBlend, 0.0)
        assertTrue(ndr.strongDetailGain > Npv10ExportEnhancementProfile.DEFAULT.strongDetailGain)
        assertTrue(nivukx.fineTextureDetailGain > ndr.fineTextureDetailGain)
        assertTrue(nivukx.fineTextureThreshold < ndr.fineTextureThreshold)
        listOf(
            Npv10ExportEnhancementProfile.DEFAULT,
            Npv10ExportEnhancementProfile.LOG_VIDEO
        ).forEach { profile ->
            assertTrue(
                "aliasSafeMaxDetailDelta=${profile.aliasSafeMaxDetailDelta}",
                profile.aliasSafeMaxDetailDelta <= 220
            )
            assertTrue("strongDetailGain=${profile.strongDetailGain}", profile.strongDetailGain <= 2.20)
            assertEquals(0.0, profile.ditherAmplitude, 0.0)
            assertEquals(1.0, profile.temporalLumaBlend, 0.0)
            assertEquals(1.0, profile.temporalChromaBlend, 0.0)
        }
    }

    @Test
    fun `strict singularity export profiles add bounded offline detail lift over realtime path`() {
        assertEquals(1.92, Npv10ExportEnhancementProfile.DEFAULT.strongDetailGain, 0.000001)
        assertEquals(2.08, Npv10ExportEnhancementProfile.NDR10_PLUS.strongDetailGain, 0.000001)
        assertEquals(
            NormalSdrVideoQualityPolicy.EXPORT_STRONG_DETAIL_GAIN,
            Npv10ExportEnhancementProfile.SDR_VIDEO.strongDetailGain,
            0.000001
        )
        assertEquals(2.06, Npv10ExportEnhancementProfile.NIVUKX_VISION.strongDetailGain, 0.000001)
        assertEquals(2.00, Npv10ExportEnhancementProfile.LOG_VIDEO.strongDetailGain, 0.000001)
    }

    @Test
    fun `sdr video npv10 export carries aggressive clean detail and chroma stability profile`() {
        val profile = Npv10ExportEnhancementProfile.SDR_VIDEO

        assertEquals(NormalSdrVideoQualityPolicy.EXPORT_FINE_TEXTURE_DETAIL_GAIN, profile.fineTextureDetailGain, 0.000001)
        assertEquals(NormalSdrVideoQualityPolicy.EXPORT_MEDIUM_DETAIL_GAIN, profile.mediumDetailGain, 0.000001)
        assertEquals(NormalSdrVideoQualityPolicy.EXPORT_STRONG_DETAIL_GAIN, profile.strongDetailGain, 0.000001)
        assertEquals(3, profile.fineTextureThreshold)
        assertEquals(12, profile.mediumEdgeThreshold)
        assertEquals(58, profile.strongEdgeThreshold)
        assertEquals(NormalSdrVideoQualityPolicy.EXPORT_TEMPORAL_LUMA_BLEND, profile.temporalLumaBlend, 0.000001)
        assertEquals(NormalSdrVideoQualityPolicy.EXPORT_TEMPORAL_CHROMA_BLEND, profile.temporalChromaBlend, 0.000001)
        assertEquals(NormalSdrVideoQualityPolicy.EXPORT_DITHER_AMPLITUDE, profile.ditherAmplitude, 0.000001)
        assertEquals(NormalSdrVideoQualityPolicy.EXPORT_CUMULATIVE_DETAIL_BUDGET, profile.cumulativeDetailBudget)
        assertEquals(1.0, profile.normalSdrCalibrationStrength, 0.000001)
    }

    @Test
    fun `extreme sdr export remains bounded and keeps sub jnd dither`() {
        val base = Npv10ExportEnhancementProfile.SDR_VIDEO
        val extreme = base.withExtremeDetailAuthority()

        assertTrue("fineTextureDetailGain=${extreme.fineTextureDetailGain}", extreme.fineTextureDetailGain > base.fineTextureDetailGain)
        assertTrue("mediumDetailGain=${extreme.mediumDetailGain}", extreme.mediumDetailGain > base.mediumDetailGain)
        assertTrue("strongDetailGain=${extreme.strongDetailGain}", extreme.strongDetailGain > base.strongDetailGain)
        assertTrue("fineTextureThreshold=${extreme.fineTextureThreshold}", extreme.fineTextureThreshold <= base.fineTextureThreshold)
        assertTrue("mediumEdgeThreshold=${extreme.mediumEdgeThreshold}", extreme.mediumEdgeThreshold <= base.mediumEdgeThreshold)
        assertTrue("chromaSuppression=${extreme.chromaSuppression}", extreme.chromaSuppression <= base.chromaSuppression)
        assertTrue(extreme.strongDetailGain <= 2.40)
        assertEquals(40, extreme.cumulativeDetailBudget)
        assertEquals(base.ditherAmplitude, extreme.ditherAmplitude, 0.0)
    }

    @Test
    fun `extreme sdr derived profile keeps normal sdr authority with tighter alias cap`() {
        val source = java.io.File("src/main/java/com/neoproc/neoproccamx/recording/Npv10MovConverter.kt").readText()
        val strictNormalSdrBody = source.substringAfter("private fun isStrictNormalSdrExportProfile(")
            .substringBefore("private fun isStrictVisibleCleanExportProfile(")
        val base = Npv10ExportEnhancementProfile.SDR_VIDEO
        val extreme = base.withExtremeDetailAuthority()

        assertEquals(base.normalSdrCalibrationStrength, extreme.normalSdrCalibrationStrength, 0.0)
        assertEquals(40, extreme.aliasSafeMaxDetailDelta)
        assertEquals(40, extreme.cumulativeDetailBudget)
        assertTrue(strictNormalSdrBody.contains("profile.normalSdrCalibrationStrength"))
        assertTrue(strictNormalSdrBody.contains("profile.fineTextureDetailGain"))
        assertTrue(strictNormalSdrBody.contains("profile.mediumDetailGain"))
        assertTrue(strictNormalSdrBody.contains("profile.strongDetailGain"))
        assertTrue(!strictNormalSdrBody.contains("aliasSafeMaxDetailDelta"))
    }

    @Test
    fun `sdr npv10 export chroma calibration uses normal sdr v4 policy scales`() {
        val source = java.io.File("src/main/java/com/neoproc/neoproccamx/recording/Npv10MovConverter.kt").readText()
        val calibrationBody = source.substringAfter("private fun calibrateNormalSdrU(")
            .substringBefore("private fun blendScale(")

        assertEquals(1.25, NormalSdrVideoQualityPolicy.EXPORT_CHROMA_U_POSITIVE_SCALE, 0.0)
        assertEquals(0.750, NormalSdrVideoQualityPolicy.EXPORT_CHROMA_V_POSITIVE_SCALE, 0.0)
        assertTrue(calibrationBody.contains("NormalSdrVideoQualityPolicy.EXPORT_CHROMA_U_POSITIVE_SCALE"))
        assertTrue(calibrationBody.contains("NormalSdrVideoQualityPolicy.EXPORT_CHROMA_V_POSITIVE_SCALE"))
        assertTrue(calibrationBody.contains("strength * chromaCorrectionGate(delta)"))
        assertTrue(source.contains("private fun chromaCorrectionGate(delta: Int): Double"))
    }

    @Test
    fun `npv10 export chroma spike cleanup uses strict visible clean authority`() {
        val source = java.io.File("src/main/java/com/neoproc/neoproccamx/recording/Npv10MovConverter.kt").readText()
        val chromaSpikeBody = source.substringAfter("private fun smoothLocalChromaSpikes(")
            .substringBefore("private fun chromaNeighborAverage(")

        assertTrue(source.contains("private fun isStrictVisibleCleanExportProfile("))
        assertTrue(chromaSpikeBody.contains("val strictVisibleCleanExport = isStrictVisibleCleanExportProfile(profile)"))
        assertTrue(chromaSpikeBody.contains("smoothChromaComponent(original, payload, pairOffset, neighborU, strictVisibleCleanExport)"))
        assertTrue(chromaSpikeBody.contains("strictVisibleCleanExport"))
    }

    @Test
    fun `sdr active transport wins over manual log named profile for npv10 enhancement`() {
        val profile = Npv10ExportEnhancementProfile.fromColorTagPlan(
            Npv10MovColorTagPlan(
                colorConfig = QuickTimeColorAtomConfig(
                    colorPrimaries = QuickTimeColorAtomPolicy.PRIMARIES_BT2020,
                    transferCharacteristics = QuickTimeColorAtomPolicy.TRANSFER_BT709,
                    matrixCoefficients = QuickTimeColorAtomPolicy.MATRIX_BT2020_NCL,
                    fullRange = false
                ),
                selectedProfile = "N-Log Cinema",
                selectedColorSpace = "Neo Wide Gamut",
                activeTransport = "BT2020_SDR"
            )
        )

        assertEquals(Npv10ExportEnhancementProfile.SDR_VIDEO, profile)
    }

    @Test
    fun `npv10 color tag plan marks ndr pq as carrier only with no vendor metadata`() {
        val plan = Npv10MovColorTagPlan(
            colorConfig = QuickTimeColorAtomConfig(
                colorPrimaries = QuickTimeColorAtomPolicy.PRIMARIES_BT2020,
                transferCharacteristics = QuickTimeColorAtomPolicy.TRANSFER_PQ_ST2084,
                matrixCoefficients = QuickTimeColorAtomPolicy.MATRIX_BT2020_NCL,
                fullRange = false
            ),
            selectedProfile = "NDR10+",
            selectedColorSpace = "Rec.2020-NDR10+",
            activeTransport = Bt2100TransportPolicy.TRANSPORT_PQ_MAIN10
        )

        assertEquals("NEO_NDR_PROPRIETARY", plan.proprietaryProfileAuthority)
        assertEquals(true, plan.bt2100TransportCarrierOnly)
        assertEquals("BT.2100_PQ_ANDROID_CARRIER_ONLY", plan.bt2100CarrierMode)
        assertEquals(false, plan.vendorDynamicRangeProfileUsed)
        assertEquals(false, plan.vendorDynamicMetadataWritten)
        assertEquals(true, plan.dolbyVisionOrHdr10PlusSubstitutionBlocked)
        assertEquals(true, plan.npv10PrivateMasterUnmodified)
    }

    @Test
    fun `sdr video export preserves fine texture above default profile`() {
        val sdr = Npv10ExportEnhancementProfile.SDR_VIDEO
        val default = Npv10ExportEnhancementProfile.DEFAULT

        assertTrue("fineTextureDetailGain=${sdr.fineTextureDetailGain}", sdr.fineTextureDetailGain > default.fineTextureDetailGain)
        assertTrue("mediumDetailGain=${sdr.mediumDetailGain}", sdr.mediumDetailGain > default.mediumDetailGain)
        assertTrue("strongDetailGain=${sdr.strongDetailGain}", sdr.strongDetailGain > default.strongDetailGain)
        assertTrue("temporalChromaBlend=${sdr.temporalChromaBlend}", sdr.temporalChromaBlend <= 0.72)
    }

    @Test
    fun `sdr npv10 export runs second stage luma micro texture recovery after edge lift`() {
        val source = java.io.File("src/main/java/com/neoproc/neoproccamx/recording/Npv10MovConverter.kt").readText()
        val enhanceBody = source.substringAfter("fun enhanceP010(")
            .substringBefore("fun measureP010NoiseMetrics(")
        val microBody = source.substringAfter("private fun recoverApexMicroTextureLuma(")
            .substringBefore("private fun applyBlueNoiseDither(")

        assertTrue(source.contains("private fun recoverApexMicroTextureLuma("))
        assertTrue(enhanceBody.contains("liftEdgeAwareLumaDetail(output, width, height, profile)"))
        assertTrue(enhanceBody.contains("recoverApexMicroTextureLuma(output, width, height, profile)"))
        assertTrue(enhanceBody.indexOf("liftEdgeAwareLumaDetail(output, width, height, profile)") <
            enhanceBody.indexOf("recoverApexMicroTextureLuma(output, width, height, profile)"))
        assertTrue(enhanceBody.indexOf("recoverApexMicroTextureLuma(output, width, height, profile)") <
            enhanceBody.indexOf("applyBlueNoiseDither(output, width, height, profile)"))
        assertTrue(microBody.contains("val microLowPass ="))
        assertTrue(microBody.contains("val microTexture = center - microLowPass"))
        assertTrue(microBody.contains("val strictNormalSdrExport = isStrictNormalSdrExportProfile(profile)"))
        assertTrue(microBody.contains("val microGain = if (strictNormalSdrExport) detailAuthority else 2.0 + 8.0 * detailAuthority"))
        assertTrue(microBody.contains("writeP010Sample(payload, offset, recovered)"))
    }

    @Test
    fun `sdr npv10 export micro texture pass uses bounded profile authority`() {
        val source = java.io.File("src/main/java/com/neoproc/neoproccamx/recording/Npv10MovConverter.kt").readText()
        val microBody = source.substringAfter("private fun recoverApexMicroTextureLuma(")
            .substringBefore("private fun applyBlueNoiseDither(")

        assertTrue(source.contains("private fun isStrictNormalSdrExportProfile("))
        assertTrue(microBody.contains("val strictNormalSdrExport = isStrictNormalSdrExportProfile(profile)"))
        assertTrue(microBody.contains("val detailAuthority = if (strictNormalSdrExport)"))
        assertTrue(microBody.contains("profile.fineTextureDetailGain"))
        assertTrue(microBody.contains("val microGain = if (strictNormalSdrExport) detailAuthority else 2.0 + 8.0 * detailAuthority"))
        assertTrue(microBody.contains("val microLimit = if (strictNormalSdrExport)"))
        assertTrue(microBody.contains("localRange * detailAuthority"))
    }

    @Test
    fun `sdr npv10 export runs v5 local contrast after micro texture before dither`() {
        val source = java.io.File("src/main/java/com/neoproc/neoproccamx/recording/Npv10MovConverter.kt").readText()
        val enhanceBody = source.substringAfter("fun enhanceP010(")
            .substringBefore("fun measureP010NoiseMetrics(")
        val contrastBody = source.substringAfter("private fun applyApexLocalContrastLuma(")
            .substringBefore("private fun applyBlueNoiseDither(")

        assertTrue(source.contains("private fun applyApexLocalContrastLuma("))
        assertTrue(enhanceBody.contains("recoverApexMicroTextureLuma(output, width, height, profile)"))
        assertTrue(enhanceBody.contains("applyApexLocalContrastLuma(output, width, height, profile)"))
        assertTrue(enhanceBody.indexOf("recoverApexMicroTextureLuma(output, width, height, profile)") <
            enhanceBody.indexOf("applyApexLocalContrastLuma(output, width, height, profile)"))
        assertTrue(enhanceBody.indexOf("applyApexLocalContrastLuma(output, width, height, profile)") <
            enhanceBody.indexOf("applyBlueNoiseDither(output, width, height, profile)"))
        assertTrue(contrastBody.contains("val localContrast = center - localMean"))
        assertTrue(contrastBody.contains("val contrastGain = if (strictNormalSdrExport) profile.strongDetailGain else"))
        assertTrue(contrastBody.contains("writeP010Sample(payload, offset, contrasted)"))
    }

    @Test
    fun `sdr npv10 export runs v6 shadow noise floor after local contrast before dither`() {
        val source = java.io.File("src/main/java/com/neoproc/neoproccamx/recording/Npv10MovConverter.kt").readText()
        val enhanceBody = source.substringAfter("fun enhanceP010(")
            .substringBefore("fun measureP010NoiseMetrics(")
        val shadowBody = source.substringAfter("private fun applyApexShadowNoiseFloorLuma(")
            .substringBefore("private fun applyApexHighlightTextureLuma(")

        assertTrue(source.contains("private fun applyApexShadowNoiseFloorLuma("))
        assertTrue(source.contains("private fun isStrictVisibleCleanExportProfile("))
        assertTrue(enhanceBody.contains("applyApexLocalContrastLuma(output, width, height, profile)"))
        assertTrue(enhanceBody.contains("applyApexShadowNoiseFloorLuma(output, width, height, profile)"))
        assertTrue(enhanceBody.indexOf("applyApexLocalContrastLuma(output, width, height, profile)") <
            enhanceBody.indexOf("applyApexShadowNoiseFloorLuma(output, width, height, profile)"))
        assertTrue(enhanceBody.indexOf("applyApexShadowNoiseFloorLuma(output, width, height, profile)") <
            enhanceBody.indexOf("applyBlueNoiseDither(output, width, height, profile)"))
        assertTrue(shadowBody.contains("val legacyShadowNoiseGate = (1.0 - smoothStep(96.0, 360.0, center))"))
        assertTrue(shadowBody.contains("val strictVisibleCleanExport = isStrictVisibleCleanExportProfile(profile)"))
        assertTrue(shadowBody.contains("val strictTexturelessGrainGate = (1.0 - smoothStep(128.0, 560.0, center))"))
        assertTrue(shadowBody.contains("val strictShadowNoiseGate = max("))
        assertTrue(shadowBody.contains("val shadowNoiseGate = if (strictVisibleCleanExport)"))
        assertTrue(shadowBody.contains("val noiseFloorWeight = if (strictVisibleCleanExport) 1.0 else"))
        assertTrue(shadowBody.contains("writeP010Sample(payload, offset, stabilized)"))
    }

    @Test
    fun `sdr npv10 export runs v7 highlight texture after shadow floor before dither`() {
        val source = java.io.File("src/main/java/com/neoproc/neoproccamx/recording/Npv10MovConverter.kt").readText()
        val enhanceBody = source.substringAfter("fun enhanceP010(")
            .substringBefore("fun measureP010NoiseMetrics(")
        val highlightBody = source.substringAfter("private fun applyApexHighlightTextureLuma(")
            .substringBefore("private fun applyApexEdgeCoherenceLuma(")

        assertTrue(source.contains("private fun applyApexHighlightTextureLuma("))
        assertTrue(enhanceBody.contains("applyApexShadowNoiseFloorLuma(output, width, height, profile)"))
        assertTrue(enhanceBody.contains("applyApexHighlightTextureLuma(output, width, height, profile)"))
        assertTrue(enhanceBody.indexOf("applyApexShadowNoiseFloorLuma(output, width, height, profile)") <
            enhanceBody.indexOf("applyApexHighlightTextureLuma(output, width, height, profile)"))
        assertTrue(enhanceBody.indexOf("applyApexHighlightTextureLuma(output, width, height, profile)") <
            enhanceBody.indexOf("applyBlueNoiseDither(output, width, height, profile)"))
        assertTrue(highlightBody.contains("val highlightTextureGate = smoothStep(600.0, 880.0, center)"))
        assertTrue(highlightBody.contains("val highlightGain = if (strictNormalSdrExport) profile.mediumDetailGain else"))
        assertTrue(highlightBody.contains("val headroomGuard = if (highlightDetail > 0.0)"))
        assertTrue(highlightBody.contains("writeP010Sample(payload, offset, highlighted)"))
    }

    @Test
    fun `sdr npv10 export runs v8 edge coherence after highlight texture before dither`() {
        val source = java.io.File("src/main/java/com/neoproc/neoproccamx/recording/Npv10MovConverter.kt").readText()
        val enhanceBody = source.substringAfter("fun enhanceP010(")
            .substringBefore("fun measureP010NoiseMetrics(")
        val coherenceBody = source.substringAfter("private fun applyApexEdgeCoherenceLuma(")
            .substringBefore("private fun applyApexTonalDensityLuma(")

        assertTrue(source.contains("private fun applyApexEdgeCoherenceLuma("))
        assertTrue(enhanceBody.contains("applyApexHighlightTextureLuma(output, width, height, profile)"))
        assertTrue(enhanceBody.contains("applyApexEdgeCoherenceLuma(output, width, height, profile)"))
        assertTrue(enhanceBody.indexOf("applyApexHighlightTextureLuma(output, width, height, profile)") <
            enhanceBody.indexOf("applyApexEdgeCoherenceLuma(output, width, height, profile)"))
        assertTrue(enhanceBody.indexOf("applyApexEdgeCoherenceLuma(output, width, height, profile)") <
            enhanceBody.indexOf("applyBlueNoiseDither(output, width, height, profile)"))
        assertTrue(coherenceBody.contains("val edgeCoherenceGate = smoothStep(18.0, 96.0, kotlin.math.abs(gx) + kotlin.math.abs(gy))"))
        assertTrue(coherenceBody.contains("val edgeGain = if (strictNormalSdrExport) profile.strongDetailGain else"))
        assertTrue(coherenceBody.contains("val haloSafe = candidate.coerceIn("))
        assertTrue(coherenceBody.contains("writeP010Sample(payload, offset, haloSafe.toInt().coerceIn(0, 1023))"))
    }

    @Test
    fun `sdr npv10 export runs v9 tonal density after edge coherence before dither`() {
        val source = java.io.File("src/main/java/com/neoproc/neoproccamx/recording/Npv10MovConverter.kt").readText()
        val enhanceBody = source.substringAfter("fun enhanceP010(")
            .substringBefore("fun measureP010NoiseMetrics(")
        val densityBody = source.substringAfter("private fun applyApexTonalDensityLuma(")
            .substringBefore("private fun applyBlueNoiseDither(")

        assertTrue(source.contains("private fun applyApexTonalDensityLuma("))
        assertTrue(enhanceBody.contains("applyApexEdgeCoherenceLuma(output, width, height, profile)"))
        assertTrue(enhanceBody.contains("applyApexTonalDensityLuma(output, width, height, profile)"))
        assertTrue(enhanceBody.indexOf("applyApexEdgeCoherenceLuma(output, width, height, profile)") <
            enhanceBody.indexOf("applyApexTonalDensityLuma(output, width, height, profile)"))
        assertTrue(enhanceBody.indexOf("applyApexTonalDensityLuma(output, width, height, profile)") <
            enhanceBody.indexOf("applyBlueNoiseDither(output, width, height, profile)"))
        assertTrue(densityBody.contains("val flatMidtoneGate = (1.0 - smoothStep(96.0, 260.0, localRange))"))
        assertTrue(densityBody.contains("val densityGain = if (strictNormalSdrExport) profile.mediumDetailGain else"))
        assertTrue(densityBody.contains("val densityDelta = normalized * (1.0 - kotlin.math.abs(normalized)) * densityGain"))
        assertTrue(densityBody.contains("val isolatedDetailGuard = 1.0 - smoothStep(6.0, 18.0, isolatedBrightDetail)"))
        assertTrue(densityBody.contains("val positiveDensityGuard = if (densityDelta > 0.0) isolatedDetailGuard else 1.0"))
        assertTrue(densityBody.contains("writeP010Sample(payload, offset, densitySafe.toInt().coerceIn(0, 1023))"))
    }

    @Test
    fun `sdr npv10 export runs v10 long distance detail after tonal density before dither`() {
        val source = java.io.File("src/main/java/com/neoproc/neoproccamx/recording/Npv10MovConverter.kt").readText()
        val enhanceBody = source.substringAfter("fun enhanceP010(")
            .substringBefore("fun measureP010NoiseMetrics(")
        val longDistanceBody = source.substringAfter("private fun applyApexLongDistanceDetailLuma(")
            .substringBefore("private fun applyBlueNoiseDither(")

        assertTrue(source.contains("private fun applyApexLongDistanceDetailLuma("))
        assertTrue(enhanceBody.contains("applyApexTonalDensityLuma(output, width, height, profile)"))
        assertTrue(enhanceBody.contains("applyApexLongDistanceDetailLuma(output, width, height, profile)"))
        assertTrue(enhanceBody.indexOf("applyApexTonalDensityLuma(output, width, height, profile)") <
            enhanceBody.indexOf("applyApexLongDistanceDetailLuma(output, width, height, profile)"))
        assertTrue(enhanceBody.indexOf("applyApexLongDistanceDetailLuma(output, width, height, profile)") <
            enhanceBody.indexOf("applyBlueNoiseDither(output, width, height, profile)"))
        assertTrue(longDistanceBody.contains("val nearMean = (center * 4.0 + axial1 * 1.0 + diagonal1 * 0.75) / 11.0"))
        assertTrue(longDistanceBody.contains("val farMean = (center * 4.0 + axial1 * 1.0 + diagonal1 * 0.66 + axial2 * 0.50) / 12.64"))
        assertTrue(longDistanceBody.contains("val hasWideRing = row >= 3 && column >= 3"))
        assertTrue(longDistanceBody.contains("val wideMean = if (hasWideRing)"))
        assertTrue(longDistanceBody.contains("val longDistanceStructure = blendDouble("))
        assertTrue(longDistanceBody.contains("val longDistanceGain = if (strictNormalSdrExport) profile.fineTextureDetailGain else"))
        assertTrue(longDistanceBody.contains(").coerceIn(0.34 + wideAgreement * 0.24, 1.0)"))
        assertTrue(longDistanceBody.contains("writeP010Sample(payload, offset, safeDetail.toInt().coerceIn(0, 1023))"))
    }

    @Test
    fun `export enhancement applies deterministic blue noise dither without changing frame size`() {
        val width = 8
        val height = 8
        val payload = ByteArray(width * height * 2 + ((width + 1) / 2) * ((height + 1) / 2) * 4)
        var offset = 0
        while (offset + 1 < payload.size) {
            writeP010(payload, offset, 512)
            offset += 2
        }
        val profile = Npv10ExportEnhancementProfile.DEFAULT.copy(
            temporalLumaBlend = 0.0,
            temporalChromaBlend = 0.0,
            ditherAmplitude = 1.0
        )

        val enhanced = Npv10ExportEnhancementPolicy.enhanceP010(
            payload = payload,
            width = width,
            height = height,
            profile = profile
        )

        assertEquals(payload.size, enhanced.size)
        assertNotEquals(payload.toList(), enhanced.toList())
        val lumaDeltas = (0 until width * height).map { sampleIndex ->
            readP010(enhanced, sampleIndex * 2) - readP010(payload, sampleIndex * 2)
        }
        assertTrue("blue-noise dither must include negative quantization pushes", lumaDeltas.any { it < 0 })
        assertTrue("blue-noise dither must include positive quantization pushes", lumaDeltas.any { it > 0 })
        assertTrue("blue-noise dither must stay within one P010 code value", lumaDeltas.all { it in -1..1 })
        assertEquals((0 until 64).toList(), Npv10ExportEnhancementPolicy.BLUE_NOISE_8X8.sorted())
        assertEquals(
            enhanced.toList(),
            Npv10ExportEnhancementPolicy.enhanceP010(
                payload = payload,
                width = width,
                height = height,
                profile = profile
            ).toList()
        )
    }

    @Test
    fun `normal sdr npv10 export applies crore corpus chroma calibration without changing luma`() {
        val width = 8
        val height = 8
        val payload = ByteArray(width * height * 2 + ((width + 1) / 2) * ((height + 1) / 2) * 4)
        var offset = 0
        while (offset < width * height * 2) {
            writeP010(payload, offset, 512)
            offset += 2
        }
        while (offset + 3 < payload.size) {
            writeP010(payload, offset, 620)
            writeP010(payload, offset + 2, 650)
            offset += 4
        }
        val profile = Npv10ExportEnhancementProfile.DEFAULT.copy(
            chromaSuppression = 1.0,
            shadowChromaDeadband = 0,
            fineTextureDetailGain = 0.0,
            mediumDetailGain = 0.0,
            strongDetailGain = 0.0,
            temporalLumaBlend = 0.0,
            temporalChromaBlend = 0.0,
            ditherAmplitude = 0.0,
            normalSdrCalibrationStrength = 1.0
        )

        val enhanced = Npv10ExportEnhancementPolicy.enhanceP010(
            payload = payload,
            width = width,
            height = height,
            profile = profile
        )

        assertEquals(512, readP010(enhanced, 0))
        val firstChroma = width * height * 2
        assertTrue("blue chroma should be lifted for the calibrated normal SDR export", readP010(enhanced, firstChroma) > 620)
        assertTrue("red chroma should be reduced for the calibrated normal SDR export", readP010(enhanced, firstChroma + 2) < 650)
    }

    private fun readP010(payload: ByteArray, offset: Int): Int {
        val lo = payload[offset].toInt() and 0xFF
        val hi = payload[offset + 1].toInt() and 0xFF
        return ((hi shl 8) or lo) ushr 6
    }

    private fun writeP010(payload: ByteArray, offset: Int, sample: Int) {
        val value = sample.coerceIn(0, 1023) shl 6
        payload[offset] = (value and 0xFF).toByte()
        payload[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun sdrProfileFor(mode: String): Npv10ExportEnhancementProfile =
        Npv10ExportEnhancementProfile.fromColorTagPlan(
            Npv10MovColorTagPlan(
                colorConfig = QuickTimeColorAtomConfig.rec709SdrLimited(),
                selectedProfile = mode,
                selectedColorSpace = "Rec.709",
                activeTransport = "REC709_SDR"
            )
        )
}
