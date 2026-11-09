package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HdrImagingMathTest {

    @Test
    fun recoverRadiance_linearResponseMatchesGroundTruth() {
        val samples = listOf(
            HdrImagingMath.ExposureSample(intensity = 0.10, exposureSeconds = 0.01),
            HdrImagingMath.ExposureSample(intensity = 0.20, exposureSeconds = 0.02),
            HdrImagingMath.ExposureSample(intensity = 0.40, exposureSeconds = 0.04)
        )
        val radiance = HdrImagingMath.recoverRadiance(samples) { it }

        assertEquals(10.0, radiance, 1e-6)
    }

    @Test
    fun pqRoundTrip_preservesLuminanceWithinSmallError() {
        val luminanceNits = 350.0
        val encoded = HdrImagingMath.pqEncodeFromNits(luminanceNits)
        val decoded = HdrImagingMath.pqDecodeToNits(encoded)

        assertEquals(luminanceNits, decoded, 0.5)
    }

    @Test
    fun bt2100HlgOetf_matchesReferenceAnchors() {
        assertEquals(0.0, HdrImagingMath.bt2100HlgOetf(0.0), 1e-8)
        assertEquals(0.5, HdrImagingMath.bt2100HlgOetf(1.0 / 12.0), 1e-6)
        assertEquals(1.0, HdrImagingMath.bt2100HlgOetf(1.0), 1e-6)
    }

    @Test
    fun bt2100HlgInverseOetf_matchesReferenceAnchors() {
        assertEquals(0.0, HdrImagingMath.bt2100HlgInverseOetf(0.0), 1e-8)
        assertEquals(1.0 / 12.0, HdrImagingMath.bt2100HlgInverseOetf(0.5), 1e-6)
        assertEquals(1.0, HdrImagingMath.bt2100HlgInverseOetf(1.0), 1e-6)
    }

    @Test
    fun bt2100LumaPreservingMap_compressesHighlightsWithoutRedAmplification() {
        val mapped = HdrImagingMath.toneMapBt2100LumaPreservingRgb(
            r = 2.80,
            g = 0.96,
            b = 0.62,
            sourcePeakNits = 4_000.0,
            targetPeakNits = 1_000.0,
            saturationPreservation = 0.92
        )

        assertTrue(mapped.r in 0.0..1.0)
        assertTrue(mapped.g in 0.0..1.0)
        assertTrue(mapped.b in 0.0..1.0)
        assertTrue(mapped.r >= mapped.g)
        assertTrue(mapped.g >= mapped.b)
        assertTrue(
            "Red/green ratio must not increase during HDR highlight mapping",
            mapped.r / mapped.g <= 2.80 / 0.96
        )
    }

    @Test
    fun ultraHdrVersionProfiles_areDistinctAndOrderedByCapability() {
        val hlg = HdrImagingMath.resolveUltraHdrVersionProfile(HdrImagingMath.HdrDeliveryVersion.HLG)
        val hdr10 = HdrImagingMath.resolveUltraHdrVersionProfile(HdrImagingMath.HdrDeliveryVersion.HDR10)
        val hdr10Plus = HdrImagingMath.resolveUltraHdrVersionProfile(HdrImagingMath.HdrDeliveryVersion.HDR10_PLUS)
        val dolby = HdrImagingMath.resolveUltraHdrVersionProfile(HdrImagingMath.HdrDeliveryVersion.DOLBY_VISION)
        val ndr10Plus = HdrImagingMath.resolveUltraHdrVersionProfile(HdrImagingMath.HdrDeliveryVersion.NDR10_PLUS)
        val nivukx = HdrImagingMath.resolveUltraHdrVersionProfile(HdrImagingMath.HdrDeliveryVersion.NIVUKX_VISION)

        assertEquals("HLG", hlg.transferName)
        assertEquals("PQ", hdr10.transferName)
        assertEquals("NeoPQ", ndr10Plus.transferName)
        assertEquals("NeoVisionPQ", nivukx.transferName)
        assertTrue(hdr10Plus.dynamicMetadata)
        assertTrue(dolby.dynamicMetadata)
        assertTrue(ndr10Plus.dynamicMetadata)
        assertTrue(nivukx.dynamicMetadata)
        assertTrue(hlg.targetPeakNits < hdr10.targetPeakNits)
        assertTrue(hdr10.targetPeakNits <= hdr10Plus.targetPeakNits)
        assertTrue(hdr10Plus.targetPeakNits <= ndr10Plus.targetPeakNits)
        assertTrue(ndr10Plus.targetPeakNits <= nivukx.targetPeakNits)
        assertTrue(ndr10Plus.targetPeakNits > hdr10Plus.targetPeakNits)
        assertTrue(nivukx.targetPeakNits > dolby.targetPeakNits)
        assertTrue(hdr10Plus.saturationPreservation <= hdr10.saturationPreservation)
        assertTrue(dolby.colorVolumeCompression >= hdr10Plus.colorVolumeCompression)
        assertTrue(ndr10Plus.colorVolumeCompression >= hdr10Plus.colorVolumeCompression)
        assertTrue(nivukx.colorVolumeCompression >= ndr10Plus.colorVolumeCompression)
        assertEquals(10, ndr10Plus.encoderBitDepth)
        assertEquals(10, nivukx.encoderBitDepth)
    }

    @Test
    fun neoHdrProfiles_neverExceedPqTransportPeak() {
        listOf(
            HdrImagingMath.HdrDeliveryVersion.NDR10_PLUS,
            HdrImagingMath.HdrDeliveryVersion.NIVUKX_VISION
        ).forEach { version ->
            val profile = HdrImagingMath.resolveUltraHdrVersionProfile(
                version = version,
                averageLuma = 0.10,
                highlightRatio = 1.0,
                dynamicRangeScore = 1.0
            )

            assertTrue("version=$version sourcePeak=${profile.sourcePeakNits}", profile.sourcePeakNits <= 10_000.0)
        }
    }

    @Test
    fun neoHdrPerceptualMapping_keepsNeutralsNeutralWhileProfilesRemainDistinct() {
        val ndr = HdrImagingMath.toneMapUltraHdrVersionRgb(
            r = 0.42,
            g = 0.42,
            b = 0.42,
            version = HdrImagingMath.HdrDeliveryVersion.NDR10_PLUS
        )
        val vision = HdrImagingMath.toneMapUltraHdrVersionRgb(
            r = 0.42,
            g = 0.42,
            b = 0.42,
            version = HdrImagingMath.HdrDeliveryVersion.NIVUKX_VISION
        )

        listOf(ndr, vision).forEach { mapped ->
            assertEquals(mapped.r, mapped.g, 0.000001)
            assertEquals(mapped.g, mapped.b, 0.000001)
        }
        assertTrue(kotlin.math.abs(ndr.r - vision.r) > 0.000001)
    }

    @Test
    fun ultraHdrVersionRgbMapping_keepsAllVersionsInGamutWithDifferentShoulders() {
        val hdr10 = HdrImagingMath.toneMapUltraHdrVersionRgb(
            r = 3.4,
            g = 1.28,
            b = 0.72,
            version = HdrImagingMath.HdrDeliveryVersion.HDR10
        )
        val hdr10Plus = HdrImagingMath.toneMapUltraHdrVersionRgb(
            r = 3.4,
            g = 1.28,
            b = 0.72,
            version = HdrImagingMath.HdrDeliveryVersion.HDR10_PLUS
        )
        val dolby = HdrImagingMath.toneMapUltraHdrVersionRgb(
            r = 3.4,
            g = 1.28,
            b = 0.72,
            version = HdrImagingMath.HdrDeliveryVersion.DOLBY_VISION
        )
        val ndr10Plus = HdrImagingMath.toneMapUltraHdrVersionRgb(
            r = 3.4,
            g = 1.28,
            b = 0.72,
            version = HdrImagingMath.HdrDeliveryVersion.NDR10_PLUS
        )
        val nivukx = HdrImagingMath.toneMapUltraHdrVersionRgb(
            r = 3.4,
            g = 1.28,
            b = 0.72,
            version = HdrImagingMath.HdrDeliveryVersion.NIVUKX_VISION
        )

        listOf(hdr10, hdr10Plus, dolby, ndr10Plus, nivukx).forEach { mapped ->
            assertTrue(mapped.r in 0.0..1.0)
            assertTrue(mapped.g in 0.0..1.0)
            assertTrue(mapped.b in 0.0..1.0)
            assertTrue(mapped.r >= mapped.g)
            assertTrue(mapped.g >= mapped.b)
        }
        assertTrue(
            "Dynamic HDR versions should not collapse to the exact static HDR10 mapping",
            kotlin.math.abs(HdrImagingMath.bt2020Luminance(hdr10.r, hdr10.g, hdr10.b) -
                HdrImagingMath.bt2020Luminance(hdr10Plus.r, hdr10Plus.g, hdr10Plus.b)) > 0.002
        )
        assertTrue(dolby.r - dolby.b <= hdr10.r - hdr10.b)
        assertTrue(ndr10Plus.r - ndr10Plus.b <= hdr10Plus.r - hdr10Plus.b)
        assertTrue(nivukx.r - nivukx.b <= ndr10Plus.r - ndr10Plus.b)
    }

    @Test
    fun ultraHdrVersionSignalEncoding_usesHlgOnlyForHlgAndPqForOtherHdrVersions() {
        val hlg = HdrImagingMath.encodeUltraHdrVersionSignal(0.18, HdrImagingMath.HdrDeliveryVersion.HLG)
        val hdr10 = HdrImagingMath.encodeUltraHdrVersionSignal(0.18, HdrImagingMath.HdrDeliveryVersion.HDR10)
        val hdr10Plus = HdrImagingMath.encodeUltraHdrVersionSignal(0.18, HdrImagingMath.HdrDeliveryVersion.HDR10_PLUS)
        val ndr10Plus = HdrImagingMath.encodeUltraHdrVersionSignal(0.18, HdrImagingMath.HdrDeliveryVersion.NDR10_PLUS)
        val nivukx = HdrImagingMath.encodeUltraHdrVersionSignal(0.18, HdrImagingMath.HdrDeliveryVersion.NIVUKX_VISION)

        assertEquals(HdrImagingMath.bt2100HlgOetf(0.18), hlg, 1e-8)
        assertTrue(hdr10 != hlg)
        assertTrue(hdr10Plus > hdr10)
        assertTrue(ndr10Plus > hdr10Plus)
        assertTrue(nivukx > ndr10Plus)
    }

    @Test
    fun neoHdrDynamicMetadataExtractsFramePercentilesAndPacksItutT35Payload() {
        val histogram = IntArray(256)
        histogram[10] = 10
        histogram[80] = 40
        histogram[220] = 30
        histogram[252] = 20

        val metadata = HdrImagingMath.deriveNeoHdrDynamicMetadata(
            histogram = histogram,
            frameIndex = 7L,
            version = HdrImagingMath.HdrDeliveryVersion.NIVUKX_VISION
        )
        val payload = HdrImagingMath.buildNeoHdrItutT35Payload(metadata)
        val body = payload.copyOfRange(5, payload.size)

        assertEquals(7L, metadata.frameIndex)
        assertEquals(10_000.0, metadata.masteringPeakNits, 0.001)
        assertTrue(metadata.p10Nits <= metadata.p50Nits)
        assertTrue(metadata.p50Nits <= metadata.p90Nits)
        assertTrue(metadata.p90Nits <= metadata.p99Nits)
        assertTrue(metadata.kneeNits >= metadata.p50Nits + 1.0)
        assertTrue(metadata.kneeNits <= metadata.masteringPeakNits)
        assertTrue(metadata.shoulderStrength in 1.05..4.12)
        assertTrue(metadata.toeStrength in 0.7..1.6)

        assertEquals(37, payload.size)
        assertEquals(0xB5, payload[0].toInt() and 0xFF)
        assertEquals(0x00, payload[1].toInt() and 0xFF)
        assertEquals(0x3C, payload[2].toInt() and 0xFF)
        assertEquals(0x00, payload[3].toInt() and 0xFF)
        assertEquals(0x01, payload[4].toInt() and 0xFF)
        assertEquals(32, body.size)
        assertEquals(10_000, readUInt16(body, 0))
        assertEquals(metadata.targetPeakNits.toInt(), readUInt16(body, 2))
        assertTrue((body[12].toInt() and 0xFF) in 54..64)
    }

    @Test
    fun nivukxQuantumMappingUsesPqExpansionAndHighlightGuardedDither() {
        val shadow = HdrImagingMath.nivukxQuantumPqSignal(100.0)
        val highlight = HdrImagingMath.nivukxQuantumPqSignal(2_000.0)
        val shadowDither = HdrImagingMath.quantumJndBlueNoiseAmplitude(0.18)
        val highlightDither = HdrImagingMath.quantumJndBlueNoiseAmplitude(0.95)

        assertTrue(shadow in 0.0..1.0)
        assertTrue(highlight in 0.0..1.0)
        assertTrue(highlight > shadow)
        assertTrue(shadowDither > 0.0)
        assertTrue(highlightDither < shadowDither)
    }

    @Test
    fun ultraHdrStaticMetadata_ordersSceneAdaptiveLightLevelsByDeliveryVersion() {
        val lowContrastHdr10 = HdrImagingMath.estimateUltraHdrStaticMetadata(
            version = HdrImagingMath.HdrDeliveryVersion.HDR10,
            averageLuma = 0.42,
            highlightRatio = 0.04,
            dynamicRangeScore = 0.38
        )
        val hdr10 = HdrImagingMath.estimateUltraHdrStaticMetadata(
            version = HdrImagingMath.HdrDeliveryVersion.HDR10,
            averageLuma = 0.40,
            highlightRatio = 0.18,
            dynamicRangeScore = 0.86
        )
        val hdr10Plus = HdrImagingMath.estimateUltraHdrStaticMetadata(
            version = HdrImagingMath.HdrDeliveryVersion.HDR10_PLUS,
            averageLuma = 0.40,
            highlightRatio = 0.18,
            dynamicRangeScore = 0.86
        )
        val dolby = HdrImagingMath.estimateUltraHdrStaticMetadata(
            version = HdrImagingMath.HdrDeliveryVersion.DOLBY_VISION,
            averageLuma = 0.40,
            highlightRatio = 0.18,
            dynamicRangeScore = 0.86
        )
        val ndr10Plus = HdrImagingMath.estimateUltraHdrStaticMetadata(
            version = HdrImagingMath.HdrDeliveryVersion.NDR10_PLUS,
            averageLuma = 0.40,
            highlightRatio = 0.18,
            dynamicRangeScore = 0.86
        )
        val nivukx = HdrImagingMath.estimateUltraHdrStaticMetadata(
            version = HdrImagingMath.HdrDeliveryVersion.NIVUKX_VISION,
            averageLuma = 0.40,
            highlightRatio = 0.18,
            dynamicRangeScore = 0.86
        )

        assertTrue(hdr10.maxCllNits > lowContrastHdr10.maxCllNits)
        assertTrue(hdr10.masteringPeakNits >= lowContrastHdr10.masteringPeakNits)
        assertTrue(hdr10Plus.maxCllNits >= hdr10.maxCllNits)
        assertTrue(dolby.maxCllNits >= hdr10Plus.maxCllNits)
        assertTrue(ndr10Plus.maxCllNits >= hdr10Plus.maxCllNits)
        assertTrue(nivukx.maxCllNits >= ndr10Plus.maxCllNits)
        assertEquals(5_000.0, ndr10Plus.masteringPeakNits, 0.0)
        assertEquals(10_000.0, nivukx.masteringPeakNits, 0.0)
        listOf(lowContrastHdr10, hdr10, hdr10Plus, dolby, ndr10Plus, nivukx).forEach { metadata ->
            assertTrue(metadata.maxFallNits <= metadata.maxCllNits)
            assertTrue(metadata.maxCllNits <= metadata.masteringPeakNits)
            assertTrue(metadata.masteringPeakNits in 1_000.0..10_000.0)
        }
    }

    @Test
    fun ultraHdrSkinToneMapping_preventsWarmSkinRedRatioFromGrowingAcrossVersions() {
        val sourceR = 1.18
        val sourceG = 0.78
        val sourceB = 0.55
        val sourceRedGreenRatio = sourceR / sourceG

        listOf(
            HdrImagingMath.HdrDeliveryVersion.HLG,
            HdrImagingMath.HdrDeliveryVersion.HDR10,
            HdrImagingMath.HdrDeliveryVersion.HDR10_PLUS,
            HdrImagingMath.HdrDeliveryVersion.DOLBY_VISION,
            HdrImagingMath.HdrDeliveryVersion.NDR10_PLUS,
            HdrImagingMath.HdrDeliveryVersion.NIVUKX_VISION
        ).forEach { version ->
            val mapped = HdrImagingMath.toneMapUltraHdrVersionRgb(
                r = sourceR,
                g = sourceG,
                b = sourceB,
                version = version,
                averageLuma = 0.36,
                highlightRatio = 0.16,
                dynamicRangeScore = 0.82
            )
            val mappedRedGreenRatio = mapped.r / mapped.g.coerceAtLeast(1e-6)
            assertTrue("version=$version mapped=$mapped", mapped.r in 0.0..1.0)
            assertTrue("version=$version mapped=$mapped", mapped.g in 0.0..1.0)
            assertTrue("version=$version mapped=$mapped", mapped.b in 0.0..1.0)
            assertTrue(
                "version=$version red/green grew from $sourceRedGreenRatio to $mappedRedGreenRatio",
                mappedRedGreenRatio <= sourceRedGreenRatio
            )
            assertTrue(
                "version=$version mapped=$mapped should keep warm skin away from neon red",
                mapped.r <= mapped.g * 1.55
            )
        }
    }

    @Test
    fun ndrAndNivukxDistantSkinMappingCapsRedDominance() {
        val sourceR = 0.82
        val sourceG = 0.36
        val sourceB = 0.27

        listOf(
            HdrImagingMath.HdrDeliveryVersion.NDR10_PLUS,
            HdrImagingMath.HdrDeliveryVersion.NIVUKX_VISION
        ).forEach { version ->
            val mapped = HdrImagingMath.toneMapUltraHdrVersionRgb(
                r = sourceR,
                g = sourceG,
                b = sourceB,
                version = version,
                averageLuma = 0.22,
                highlightRatio = 0.18,
                dynamicRangeScore = 0.88
            )

            assertTrue(
                "version=$version mapped=$mapped",
                mapped.r <= mapped.g * 1.42
            )
            assertTrue(
                "version=$version mapped=$mapped",
                mapped.b >= mapped.g * 0.70
            )
        }
    }

    @Test
    fun ndrAndNivukxShadowMappingGuardsAgainstCyanBias() {
        val sourceR = 0.028
        val sourceG = 0.092
        val sourceB = 0.126
        val sourceGreenRedRatio = sourceG / sourceR
        val sourceBlueRedRatio = sourceB / sourceR

        listOf(
            HdrImagingMath.HdrDeliveryVersion.NDR10_PLUS,
            HdrImagingMath.HdrDeliveryVersion.NIVUKX_VISION
        ).forEach { version ->
            val mapped = HdrImagingMath.toneMapUltraHdrVersionRgb(
                r = sourceR,
                g = sourceG,
                b = sourceB,
                version = version,
                averageLuma = 0.16,
                highlightRatio = 0.09,
                dynamicRangeScore = 0.84
            )

            assertTrue(
                "version=$version mapped=$mapped",
                mapped.g / mapped.r.coerceAtLeast(1e-6) < sourceGreenRedRatio
            )
            assertTrue(
                "version=$version mapped=$mapped",
                mapped.b / mapped.r.coerceAtLeast(1e-6) < sourceBlueRedRatio
            )
            assertTrue(
                "version=$version mapped=$mapped",
                mapped.r >= maxOf(mapped.g, mapped.b) * 0.45
            )
        }
    }

    @Test
    fun quantization_respectsBitDepthRanges() {
        val q10 = HdrImagingMath.quantize(1.0, 10)
        val q12 = HdrImagingMath.quantize(1.0, 12)

        assertEquals(1023, q10)
        assertEquals(4095, q12)
    }

    @Test
    fun bt2020AcesCgAndOklabTransformsRoundTripWithSmallError() {
        val aces = HdrImagingMath.bt2020ToAcesCgLinear(0.25, 0.42, 0.78)
        val bt2020 = HdrImagingMath.acesCgLinearToBt2020(aces.r, aces.g, aces.b)
        val oklab = HdrImagingMath.linearAcesCgToOklab(aces.r, aces.g, aces.b)
        val acesRoundTrip = HdrImagingMath.oklabToLinearAcesCg(oklab.l, oklab.a, oklab.b)

        assertEquals(0.25, bt2020.r, 0.00001)
        assertEquals(0.42, bt2020.g, 0.00001)
        assertEquals(0.78, bt2020.b, 0.00001)
        assertEquals(aces.r, acesRoundTrip.r, 0.00001)
        assertEquals(aces.g, acesRoundTrip.g, 0.00001)
        assertEquals(aces.b, acesRoundTrip.b, 0.00001)
    }

    @Test
    fun jndDitherAndIsoNoiseHelpersClampForEncoderBackpressure() {
        val normalDither = HdrImagingMath.jndBlueNoiseDitherAmplitude(
            normalizedLuma = 0.42,
            noiseMetric = 0.45,
            abrBackpressureScale = 1.0
        )
        val abrDither = HdrImagingMath.jndBlueNoiseDitherAmplitude(
            normalizedLuma = 0.42,
            noiseMetric = 0.45,
            abrBackpressureScale = 0.60
        )
        val iso100 = HdrImagingMath.sensorNoiseCoefficientsForIso(100.0)
        val iso800 = HdrImagingMath.sensorNoiseCoefficientsForIso(800.0)

        assertTrue(normalDither > abrDither)
        assertTrue(normalDither in 0.0..1.0)
        assertTrue(iso800.shotNoiseCoefficient > iso100.shotNoiseCoefficient)
        assertTrue(iso800.readNoiseCoefficient > iso100.readNoiseCoefficient)
    }

    @Test
    fun histogramPercentile_returnsMonotonicPercentiles() {
        val histogram = IntArray(256) { index -> if (index < 128) 1 else 4 }
        val p50 = HdrImagingMath.histogramPercentile(histogram, 0.50)
        val p95 = HdrImagingMath.histogramPercentile(histogram, 0.95)

        assertTrue(p95 >= p50)
    }

    @Test
    fun hdr10PlusToneMap_changesWithDynamicMetadata() {
        val input = 1200.0
        val conservative = HdrImagingMath.Hdr10PlusDynamicMetadata(
            frameIndex = 1L,
            p01Nits = 1.0,
            p50Nits = 140.0,
            p95Nits = 700.0,
            p99Nits = 900.0,
            kneeNits = 500.0,
            shoulderStrength = 1.0,
            toeStrength = 1.0
        )
        val aggressive = conservative.copy(
            frameIndex = 2L,
            kneeNits = 350.0,
            shoulderStrength = 2.2
        )

        val out1 = HdrImagingMath.toneMapHdr10PlusAdaptive(input, conservative, displayPeakNits = 1000.0)
        val out2 = HdrImagingMath.toneMapHdr10PlusAdaptive(input, aggressive, displayPeakNits = 1000.0)

        assertTrue(kotlin.math.abs(out1 - out2) > 1.0)
    }

    @Test
    fun dolbyDualLayerReconstruction_combinesBaseAndEnhancement() {
        val reconstructed = HdrImagingMath.dolbyVisionReconstructSignal(
            baseLayerSignal = 0.55,
            enhancementLayerSignal = 0.10,
            gain = 0.8
        )

        assertEquals(0.63, reconstructed, 1e-6)
    }

    @Test
    fun processRawToHdrEncoded_outputsExpectedBitDepthByFormat() {
        val input = HdrImagingMath.PipelineInput(
            r = listOf(
                HdrImagingMath.RawSensorSample(0.08, 0.010),
                HdrImagingMath.RawSensorSample(0.16, 0.020)
            ),
            g = listOf(
                HdrImagingMath.RawSensorSample(0.08, 0.010),
                HdrImagingMath.RawSensorSample(0.16, 0.020)
            ),
            b = listOf(
                HdrImagingMath.RawSensorSample(0.08, 0.010),
                HdrImagingMath.RawSensorSample(0.16, 0.020)
            )
        )

        val hdr10 = HdrImagingMath.processRawToHdrEncoded(
            input = input,
            format = HdrImagingMath.OutputFormat.HDR10,
            displayPeakNits = 1000.0
        )
        val dv = HdrImagingMath.processRawToHdrEncoded(
            input = input,
            format = HdrImagingMath.OutputFormat.DOLBY_VISION,
            displayPeakNits = 1000.0
        )
        val ndr10Plus = HdrImagingMath.processRawToHdrEncoded(
            input = input,
            format = HdrImagingMath.OutputFormat.NDR10_PLUS,
            displayPeakNits = 1000.0
        )
        val nivukx = HdrImagingMath.processRawToHdrEncoded(
            input = input,
            format = HdrImagingMath.OutputFormat.NIVUKX_VISION,
            displayPeakNits = 1000.0
        )

        assertEquals(10, hdr10.bitDepth)
        assertEquals(10, dv.bitDepth)
        assertEquals(10, ndr10Plus.bitDepth)
        assertEquals(10, nivukx.bitDepth)
        assertTrue(ndr10Plus.luminanceNits >= hdr10.luminanceNits)
        assertTrue(nivukx.luminanceNits >= ndr10Plus.luminanceNits)
    }

    @Test
    fun naturalNdrToneMap_rollsOffOverexposedSkyWithoutFlatWhiteClip() {
        val mapped = HdrImagingMath.toneMapNaturalDynamicRangeRgb(
            r = 4.8,
            g = 5.1,
            b = 5.6,
            version = HdrImagingMath.HdrDeliveryVersion.NDR10_PLUS,
            averageLuma = 0.48,
            highlightRatio = 0.82,
            dynamicRangeScore = 0.94
        )

        val maxChannel = maxOf(mapped.r, mapped.g, mapped.b)
        val minChannel = minOf(mapped.r, mapped.g, mapped.b)

        assertTrue("sky highlight must not hard clip to pure white", maxChannel < 0.985)
        assertTrue("sky highlight must keep visible tonal separation", maxChannel - minChannel > 0.010)
        assertTrue("sky highlight should stay bright after compression", HdrImagingMath.bt2020Luminance(mapped.r, mapped.g, mapped.b) > 0.55)
    }

    @Test
    fun naturalNdrToneMap_reducesCyanGreenHighlightShift() {
        val inputCoolOverRed = maxOf(3.4, 3.8) / 1.10
        val mapped = HdrImagingMath.toneMapNaturalDynamicRangeRgb(
            r = 1.10,
            g = 3.40,
            b = 3.80,
            version = HdrImagingMath.HdrDeliveryVersion.NIVUKX_VISION,
            averageLuma = 0.58,
            highlightRatio = 0.88,
            dynamicRangeScore = 0.96
        )
        val outputCoolOverRed = maxOf(mapped.g, mapped.b) / mapped.r.coerceAtLeast(1e-6)

        assertTrue(outputCoolOverRed < inputCoolOverRed)
        assertTrue("cyan/green highlight dominance must be bounded", outputCoolOverRed <= 2.15)
        assertTrue(maxOf(mapped.r, mapped.g, mapped.b) < 0.985)
    }

    @Test
    fun naturalNdrToneMap_preservesShadowDetailWithoutFlatteningBlack() {
        val inputY = HdrImagingMath.bt2020Luminance(0.018, 0.024, 0.020)
        val mapped = HdrImagingMath.toneMapNaturalDynamicRangeRgb(
            r = 0.018,
            g = 0.024,
            b = 0.020,
            version = HdrImagingMath.HdrDeliveryVersion.NDR10_PLUS,
            averageLuma = 0.22,
            highlightRatio = 0.28,
            dynamicRangeScore = 0.82
        )
        val outputY = HdrImagingMath.bt2020Luminance(mapped.r, mapped.g, mapped.b)

        assertTrue("shadow detail should lift above captured black floor", outputY > inputY)
        assertTrue("shadow lift must remain cinematic, not gray", outputY < 0.10)
        assertTrue(minOf(mapped.r, mapped.g, mapped.b) > 0.010)
    }

    @Test
    fun naturalNdrToneMap_kneeIsC1ContinuousAndNeverExpandsHighlights() {
        // Anti-regression for the knee contrast-kick: the old tanh shoulder had
        // slope == kappa (~2.1) at the knee, so output exceeded input just above
        // it (highlight expansion + Mach band). On a neutral sweep above the toe,
        // the luminance transfer must be monotone and never steeper than ~unit
        // slope (no kink, no expansion).
        for (version in listOf(
            HdrImagingMath.HdrDeliveryVersion.NDR10_PLUS,
            HdrImagingMath.HdrDeliveryVersion.NIVUKX_VISION
        )) {
            var prevIn = 0.35
            var prevOut = neutralNdrLuma(prevIn, version)
            var v = 0.352
            while (v <= 3.0) {
                val out = neutralNdrLuma(v, version)
                assertTrue("non-monotone neutral tone at v=$v ($version)", out >= prevOut - 1e-9)
                val slope = (out - prevOut) / (v - prevIn)
                assertTrue("highlight expansion / knee kink at v=$v ($version): slope=$slope", slope <= 1.05)
                prevIn = v
                prevOut = out
                v += 0.002
            }
        }
    }

    private fun neutralNdrLuma(v: Double, version: HdrImagingMath.HdrDeliveryVersion): Double {
        val mapped = HdrImagingMath.toneMapNaturalDynamicRangeRgb(
            r = v, g = v, b = v, version = version
        )
        return HdrImagingMath.bt2020Luminance(mapped.r, mapped.g, mapped.b)
    }

    @Test
    fun naturalNdrRawChannelNormalizationPreventsRedOverflowBeforeToneMap() {
        val normalized = HdrImagingMath.normalizeRawBayerChannelsForNdr(
            r = 1.92,
            g = 0.74,
            b = 0.52,
            calibration = HdrImagingMath.NdrRawRgbCalibration(
                redResponse = 1.82,
                greenResponse = 1.00,
                blueResponse = 0.92
            )
        )

        assertTrue("red must be response-normalized before merge: $normalized", normalized.r <= normalized.g * 1.45)
        assertTrue("blue texture should not be crushed by normalization: $normalized", normalized.b >= normalized.g * 0.58)
        assertTrue("normalization keeps output finite", normalized.r.isFinite() && normalized.g.isFinite() && normalized.b.isFinite())
    }

    @Test
    fun processRawToNdrEncodedDoesNotTurnSkinPureRedUnderHighAnalogGain() {
        val input = HdrImagingMath.PipelineInput(
            r = listOf(
                HdrImagingMath.RawSensorSample(normalizedSignal = 0.98, exposureSeconds = 0.006, analogGain = 16.0, blackLevel = 0.006),
                HdrImagingMath.RawSensorSample(normalizedSignal = 0.74, exposureSeconds = 0.020, analogGain = 10.0, blackLevel = 0.006)
            ),
            g = listOf(
                HdrImagingMath.RawSensorSample(normalizedSignal = 0.58, exposureSeconds = 0.006, analogGain = 16.0, blackLevel = 0.006),
                HdrImagingMath.RawSensorSample(normalizedSignal = 0.52, exposureSeconds = 0.020, analogGain = 10.0, blackLevel = 0.006)
            ),
            b = listOf(
                HdrImagingMath.RawSensorSample(normalizedSignal = 0.39, exposureSeconds = 0.006, analogGain = 16.0, blackLevel = 0.006),
                HdrImagingMath.RawSensorSample(normalizedSignal = 0.38, exposureSeconds = 0.020, analogGain = 10.0, blackLevel = 0.006)
            )
        )

        listOf(
            HdrImagingMath.OutputFormat.NDR10_PLUS,
            HdrImagingMath.OutputFormat.NIVUKX_VISION
        ).forEach { format ->
            val encoded = HdrImagingMath.processRawToHdrEncoded(
                input = input,
                format = format,
                displayPeakNits = 1_000.0
            )
            val redOverGreenCode = encoded.codeR.toDouble() / encoded.codeG.coerceAtLeast(1).toDouble()

            assertTrue("format=$format must not encode pure red skin: $encoded", redOverGreenCode <= 1.12)
            assertTrue("format=$format must preserve nonzero blue facial texture: $encoded", encoded.codeB >= encoded.codeG * 0.82)
            assertTrue("format=$format must stay below hard PQ clip: $encoded", encoded.codeR < 1018)
        }
    }

    private fun readUInt16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 8) or (bytes[offset + 1].toInt() and 0xFF)
}
