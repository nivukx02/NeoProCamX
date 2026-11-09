package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.color.SdrQualityIntent
import com.neoproc.neoproccamx.core.BeyondImaxEliteProfile
import com.neoproc.neoproccamx.pipeline.SceneRenderProfile
import com.neoproc.neoproccamx.pipeline.SubjectDetailEnhancementPlan
import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
import com.neoproc.neoproccamx.recording.Npv10ExportEnhancementProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuImageEnhancementContractTest {
    private val profile = SceneRenderProfile(
        temporalBlend = 0.42f,
        spatialDenoise = 0.73f,
        chromaDenoise = 0.96f,
        shadowBoost = 0.25f,
        sharpening = 0.31f,
        warmth = 0.02f,
        contrast = 1.01f,
        saturation = 0.99f,
        highlightCompression = 0.35f,
        logStrength = 0.68f,
        ditherStrength = 0f,
        videoHdrFusionStrength = 0.82f
    )

    @Test
    fun packsSceneAndNpv10ProfileIntoStableUniformBlock() {
        val uniforms = GpuImageEnhancementUniforms.fromSceneProfile(
            profile = profile,
            signalFamily = GpuSignalFamily.LOG,
            colorSpaceName = "Rec.2020-N-Log",
            colorScienceName = "N-Log Cinema",
            activeTransport = GpuActiveTransport.BT2020_LOG_SDR,
            exportProfile = Npv10ExportEnhancementProfile.LOG_VIDEO,
            iso = 1600f,
            noiseMetric = 0.48f
        )

        val packed = uniforms.toFloatArray()

        assertEquals(VulkanBridge.ENHANCEMENT_UNIFORM_FLOAT_COUNT, packed.size)
        assertEquals(GpuSignalFamily.LOG.id.toFloat(), packed[0], 0.0001f)
        assertEquals(GpuColorSpace.REC2020.id.toFloat(), packed[1], 0.0001f)
        assertEquals(GpuColorScience.N_LOG_CINEMA.id.toFloat(), packed[2], 0.0001f)
        assertEquals(GpuActiveTransport.BT2020_LOG_SDR.id.toFloat(), packed[3], 0.0001f)
        assertEquals(0.42f, packed[4], 0.0001f)
        assertEquals(0.985f, packed[5], 0.0001f)
        assertEquals(1.0f, packed[6], 0.0001f)
        assertEquals(0.90f, packed[8], 0.0001f)
        assertEquals(Npv10ExportEnhancementProfile.LOG_VIDEO.aliasSafeMaxDetailDelta / 1023f, packed[16], 0.0001f)
        assertTrue(uniforms.signature.startsWith("gpu-v1|signal=LOG|space=REC2020|science=N_LOG_CINEMA"))
    }

    @Test
    fun strictHighEndUniformPacketRaisesWeakNoiseAndDetailLanesForNonSdrSignalFamilies() {
        val weakProfile = profile.copy(
            spatialDenoise = 0.12f,
            chromaDenoise = 0.20f,
            sharpening = 0.05f
        )

        val expectations = mapOf(
            GpuSignalFamily.HDR to listOf(0.992f, 1.0f, 0.94f, 0.74f),
            GpuSignalFamily.LOG to listOf(0.985f, 1.0f, 0.90f, 0.74f),
            GpuSignalFamily.RAW to listOf(0.992f, 1.0f, 0.94f, 0.74f)
        )

        expectations.forEach { (signalFamily, expected) ->
            val uniforms = GpuImageEnhancementUniforms.fromSceneProfile(
                profile = weakProfile,
                signalFamily = signalFamily,
                colorSpaceName = "Rec.2020",
                colorScienceName = when (signalFamily) {
                    GpuSignalFamily.HDR -> "NDR10+"
                    GpuSignalFamily.LOG -> "N-Log Cinema"
                    GpuSignalFamily.RAW -> "RAW"
                    GpuSignalFamily.SDR -> error("SDR uses the explicit adaptive intent tests")
                },
                activeTransport = when (signalFamily) {
                    GpuSignalFamily.HDR -> GpuActiveTransport.BT2100_PQ_MAIN10
                    GpuSignalFamily.LOG -> GpuActiveTransport.BT2020_LOG_SDR
                    GpuSignalFamily.RAW -> GpuActiveTransport.RAW_MONITOR
                    GpuSignalFamily.SDR -> error("SDR uses the explicit adaptive intent tests")
                },
                noiseMetric = 0.05f
            )
            val packed = uniforms.toFloatArray()

            assertEquals("spatial floor for $signalFamily", expected[0], packed[5], 0.0001f)
            assertEquals("chroma floor for $signalFamily", expected[1], packed[6], 0.0001f)
            assertEquals("sharpening floor for $signalFamily", expected[2], packed[8], 0.0001f)
            assertEquals("noise metric floor for $signalFamily", expected[3], packed[26], 0.0001f)
            assertTrue(uniforms.signature.contains("strictGpuFloors=true"))
        }
    }

    @Test
    fun nonSdrDetailGainAuthorityRemainsUnchangedForNdrLogAndRaw() {
        val subjectPlan = focusedSubjectPlan(additiveDetailGain = 10f)
        val cases = listOf(
            Triple(
                GpuSignalFamily.HDR to GpuActiveTransport.BT2100_PQ_MAIN10,
                "NDR10+" to "NDR10+",
                Npv10ExportEnhancementProfile.NDR10_PLUS
            ),
            Triple(
                GpuSignalFamily.LOG to GpuActiveTransport.BT2020_LOG_SDR,
                "Rec.2020-N-Log" to "N-Log Cinema",
                Npv10ExportEnhancementProfile.LOG_VIDEO
            ),
            Triple(
                GpuSignalFamily.RAW to GpuActiveTransport.RAW_MONITOR,
                "Rec.2020" to "RAW",
                Npv10ExportEnhancementProfile.DEFAULT
            )
        )

        cases.forEach { (signalAndTransport, colorNames, exportProfile) ->
            val packed = GpuImageEnhancementUniforms.fromSceneProfile(
                profile = profile,
                signalFamily = signalAndTransport.first,
                colorSpaceName = colorNames.first,
                colorScienceName = colorNames.second,
                activeTransport = signalAndTransport.second,
                exportProfile = exportProfile,
                videoDetailProfile = VideoDetailProfile.FOCUSED_DETAIL,
                subjectDetailPlan = subjectPlan
            ).toFloatArray()

            assertEquals(exportProfile.fineTextureDetailGain.toFloat(), packed[20], 0.0001f)
            assertEquals(exportProfile.mediumDetailGain.toFloat(), packed[21], 0.0001f)
            assertEquals(exportProfile.strongDetailGain.toFloat(), packed[22], 0.0001f)
            assertEquals(1f, packed[42], 0.0001f)
        }
    }

    @Test
    fun normalSdrRealtimeUniformsUseNormalizedFineDetailProfileFromSdrNpv10Export() {
        val uniforms = GpuImageEnhancementUniforms.fromSceneProfile(
            profile = profile.copy(
                logStrength = 0f,
                videoHdrFusionStrength = 0f,
                highlightCompression = 0.16f
            ),
            signalFamily = GpuSignalFamily.SDR,
            colorSpaceName = "Rec.2020",
            colorScienceName = "Natural",
            activeTransport = GpuActiveTransport.BT2020_SDR,
            sdrQualityIntent = SdrQualityIntent.NORMAL
        )
        val packed = uniforms.toFloatArray()
        val sdr = Npv10ExportEnhancementProfile.SDR_VIDEO

        assertEquals(sdr.shadowChromaDeadband / 1023f, packed[15], 0.0001f)
        assertEquals(sdr.fineTextureThreshold / 1023f, packed[17], 0.0001f)
        assertEquals(sdr.mediumEdgeThreshold / 1023f, packed[18], 0.0001f)
        assertEquals(sdr.strongEdgeThreshold / 1023f, packed[19], 0.0001f)
        assertEquals(sdr.fineTextureDetailGain.toFloat().coerceIn(0f, 1f), packed[20], 0.0001f)
        assertEquals(sdr.mediumDetailGain.toFloat().coerceIn(0f, 1f), packed[21], 0.0001f)
        assertEquals(sdr.strongDetailGain.toFloat().coerceIn(0f, 1f), packed[22], 0.0001f)
        assertEquals(sdr.temporalChromaBlend.toFloat(), packed[24], 0.0001f)
    }

    @Test
    fun normalAndProSdrPacketControlLanesAreFiniteAndBounded() {
        val subjectPlan = focusedSubjectPlan(additiveDetailGain = 10f)

        SdrQualityIntent.entries.forEach { intent ->
            val packed = GpuImageEnhancementUniforms.fromSceneProfile(
                profile = profile,
                signalFamily = GpuSignalFamily.SDR,
                colorSpaceName = "Rec.709",
                colorScienceName = "Natural",
                activeTransport = GpuActiveTransport.REC709_SDR,
                noiseMetric = 0.36f,
                localChromaSpikeSmoothing = 0.44f,
                sharpeningFloor = 0.28f,
                sharpeningCeiling = 0.74f,
                skinDetailProtection = 0.82f,
                videoDetailProfile = VideoDetailProfile.FOCUSED_DETAIL,
                subjectDetailPlan = subjectPlan,
                sdrQualityIntent = intent
            ).toFloatArray()

            packed.forEachIndexed { index, value ->
                assertTrue("$intent slot $index must be finite", value.isFinite())
            }
            (4..8).forEach { index -> assertTrue("$intent slot $index", packed[index] in 0f..1f) }
            assertTrue("$intent warmth", packed[9] in -1f..1f)
            assertTrue("$intent contrast", packed[10] in 0f..2f)
            assertTrue("$intent saturation", packed[11] in 0f..2f)
            (12..39).forEach { index -> assertTrue("$intent slot $index", packed[index] in 0f..1f) }
            (41..43).forEach { index -> assertTrue("$intent slot $index", packed[index] in 0f..1f) }
        }
    }

    @Test
    fun adaptiveSdrSharpeningAndSubjectHeadroomStayCoherentAcrossStressMatrix() {
        val stressCases = listOf(
            GpuSdrStressCase(
                name = "extreme-noise",
                profile = profile.copy(
                    temporalBlend = 0.72f,
                    spatialDenoise = 1f,
                    chromaDenoise = 1f,
                    sharpening = 1f
                ),
                noiseMetric = 1f,
                sharpeningFloor = 0.12f,
                sharpeningCeiling = 0.48f,
                skinDetailProtection = 1f
            ),
            GpuSdrStressCase(
                name = "high-texture",
                profile = profile.copy(
                    temporalBlend = 0.08f,
                    spatialDenoise = 0.30f,
                    chromaDenoise = 0.36f,
                    sharpening = 1f
                ),
                noiseMetric = 0.08f,
                sharpeningFloor = 0.50f,
                sharpeningCeiling = 0.90f,
                skinDetailProtection = 0.55f
            ),
            GpuSdrStressCase(
                name = "rapid-motion",
                profile = profile.copy(
                    temporalBlend = 0f,
                    spatialDenoise = 0.45f,
                    chromaDenoise = 0.50f,
                    sharpening = 0.88f
                ),
                noiseMetric = 0.55f,
                sharpeningFloor = 0.32f,
                sharpeningCeiling = 0.60f,
                skinDetailProtection = 0.72f
            ),
            GpuSdrStressCase(
                name = "thermal-throttle",
                profile = profile.copy(
                    temporalBlend = 0.32f,
                    spatialDenoise = 0.82f,
                    chromaDenoise = 0.88f,
                    sharpening = 0.90f
                ),
                noiseMetric = 0.74f,
                sharpeningFloor = 0.24f,
                sharpeningCeiling = 0.52f,
                skinDetailProtection = 0.90f
            ),
            GpuSdrStressCase(
                name = "heavy-load-hostile-input",
                profile = profile.copy(
                    temporalBlend = Float.NaN,
                    spatialDenoise = Float.POSITIVE_INFINITY,
                    chromaDenoise = Float.NEGATIVE_INFINITY,
                    sharpening = Float.POSITIVE_INFINITY
                ),
                noiseMetric = Float.NaN,
                sharpeningFloor = Float.NaN,
                sharpeningCeiling = Float.NEGATIVE_INFINITY,
                skinDetailProtection = Float.POSITIVE_INFINITY
            )
        )
        val requestedSubjectGain = 10f

        SdrQualityIntent.entries.forEach { intent ->
            stressCases.forEach { stress ->
                val packed = GpuImageEnhancementUniforms.fromSceneProfile(
                    profile = stress.profile,
                    signalFamily = GpuSignalFamily.SDR,
                    colorSpaceName = "Rec.709",
                    colorScienceName = "Natural",
                    activeTransport = GpuActiveTransport.REC709_SDR,
                    noiseMetric = stress.noiseMetric,
                    sharpeningFloor = stress.sharpeningFloor,
                    sharpeningCeiling = stress.sharpeningCeiling,
                    skinDetailProtection = stress.skinDetailProtection,
                    videoDetailProfile = VideoDetailProfile.FOCUSED_DETAIL,
                    subjectDetailPlan = focusedSubjectPlan(requestedSubjectGain),
                    sdrQualityIntent = intent
                ).toFloatArray()
                val context = "$intent/${stress.name}"
                val sharpening = packed[8]
                val floor = packed[28]
                val ceiling = packed[29]
                val skinProtection = packed[30]
                val subjectDetailGain = packed[42]
                val expectedHeadroom = (ceiling - sharpening) *
                    (1f - skinProtection * 0.5f)

                packed.forEachIndexed { index, value ->
                    assertTrue("$context slot $index is not finite: $value", value.isFinite())
                }
                assertTrue("$context floor=$floor sharpening=$sharpening", floor <= sharpening)
                assertTrue("$context sharpening=$sharpening ceiling=$ceiling", sharpening <= ceiling)
                assertTrue("$context floor=$floor ceiling=$ceiling", floor <= ceiling)
                assertEquals(
                    "$context subject headroom mismatch",
                    expectedHeadroom,
                    subjectDetailGain,
                    0.0001f
                )
                assertTrue("$context subject gain exceeds headroom", subjectDetailGain <= ceiling - sharpening)
            }
        }
    }

    @Test
    fun normalSdrRealtimeUniformsPreserveSceneAdaptationInsideBoundedEnvelope() {
        val dimScene = profile.copy(
            temporalBlend = 0.02f,
            spatialDenoise = 0.12f,
            chromaDenoise = 0.20f,
            shadowBoost = 0.70f,
            sharpening = 0.05f,
            warmth = -0.44f,
            contrast = 0.62f,
            saturation = 0.60f,
            highlightCompression = 0.02f,
            logStrength = 0.50f,
            videoHdrFusionStrength = 0.70f
        )
        val brightTiltScene = profile.copy(
            temporalBlend = 0.70f,
            spatialDenoise = 0.88f,
            chromaDenoise = 0.45f,
            shadowBoost = 0.02f,
            sharpening = 0.77f,
            warmth = 0.51f,
            contrast = 1.70f,
            saturation = 1.60f,
            highlightCompression = 0.35f,
            logStrength = 0.30f,
            videoHdrFusionStrength = 0.90f
        )

        val dimPacked = GpuImageEnhancementUniforms.fromSceneProfile(
            profile = dimScene,
            signalFamily = GpuSignalFamily.SDR,
            colorSpaceName = "Rec.709",
            colorScienceName = "Natural",
            activeTransport = GpuActiveTransport.REC709_SDR,
            noiseMetric = 0.05f,
            localChromaSpikeSmoothing = 0.05f,
            sharpeningFloor = 0.06f,
            sharpeningCeiling = 0.12f,
            skinDetailProtection = 0.08f,
            sdrQualityIntent = SdrQualityIntent.NORMAL
        ).toFloatArray()
        val brightPacked = GpuImageEnhancementUniforms.fromSceneProfile(
            profile = brightTiltScene,
            signalFamily = GpuSignalFamily.SDR,
            colorSpaceName = "Rec.709",
            colorScienceName = "Natural",
            activeTransport = GpuActiveTransport.REC709_SDR,
            noiseMetric = 0.95f,
            localChromaSpikeSmoothing = 0.95f,
            sharpeningFloor = 0.80f,
            sharpeningCeiling = 0.90f,
            skinDetailProtection = 0.88f,
            sdrQualityIntent = SdrQualityIntent.NORMAL
        ).toFloatArray()

        listOf(4, 5, 6, 7, 8, 9, 10, 11, 12, 26, 27, 28, 29, 30).forEach { index ->
            assertTrue("dim slot $index must be finite", dimPacked[index].isFinite())
            assertTrue("bright slot $index must be finite", brightPacked[index].isFinite())
            assertTrue("dim slot $index must not be sentinel 10", dimPacked[index] < 2f)
            assertTrue("bright slot $index must not be sentinel 10", brightPacked[index] < 2f)
        }
        assertNotEquals(dimPacked[4], brightPacked[4])
        assertNotEquals(dimPacked[5], brightPacked[5])
        assertNotEquals(dimPacked[8], brightPacked[8])
        assertEquals(0f, dimPacked[13], 0.0001f)
        assertEquals(0f, dimPacked[14], 0.0001f)
    }

    @Test
    fun forcedNormalSdrAdaptivePolicyKeepsManualLogNamedScienceOnSdrTransport() {
        val uniforms = GpuImageEnhancementUniforms.fromSceneProfile(
            profile = profile.copy(
                temporalBlend = 0.05f,
                spatialDenoise = 0.06f,
                chromaDenoise = 0.07f,
                shadowBoost = 0.08f,
                sharpening = 0.09f,
                logStrength = 0.88f,
                videoHdrFusionStrength = 0.91f
            ),
            signalFamily = GpuSignalFamily.SDR,
            colorSpaceName = "Neo Wide Gamut",
            colorScienceName = "N-Log Cinema",
            activeTransport = null,
            noiseMetric = 0.03f,
            forceNormalSdrRealtimeLock = true,
            sdrQualityIntent = SdrQualityIntent.NORMAL
        )
        val packed = uniforms.toFloatArray()

        assertEquals(GpuSignalFamily.SDR, uniforms.signalFamily)
        assertEquals(GpuColorScience.N_LOG_CINEMA, uniforms.colorScience)
        assertEquals(GpuActiveTransport.BT2020_SDR, uniforms.activeTransport)
        assertTrue(packed[4] in 0f..1f)
        assertTrue(packed[5] in 0f..1f)
        assertTrue(packed[6] in 0f..1f)
        assertTrue(packed[8] in 0f..0.90f)
        assertEquals(0.0f, packed[13], 0.0001f)
        assertEquals(0.0f, packed[14], 0.0001f)
        assertTrue(packed[26] in 0f..1f)
        assertTrue(uniforms.signature.contains("normalSdrRealtimeLock=true"))
        assertTrue(uniforms.signature.contains("forceNormalSdrRealtimeLock=true"))
        assertTrue(uniforms.signature.contains("sdrQualityIntent=NORMAL"))
    }

    @Test
    fun proSdrPrioritizesTextureWithDistinctHaloAndNoiseBoundedPacket() {
        val normal = GpuImageEnhancementUniforms.fromSceneProfile(
            profile = profile,
            signalFamily = GpuSignalFamily.SDR,
            colorSpaceName = "Neo Wide Gamut",
            colorScienceName = "Natural",
            activeTransport = GpuActiveTransport.BT2020_SDR,
            noiseMetric = 0.28f,
            sdrQualityIntent = SdrQualityIntent.NORMAL
        )
        val pro = GpuImageEnhancementUniforms.fromSceneProfile(
            profile = profile,
            signalFamily = GpuSignalFamily.SDR,
            colorSpaceName = "Neo Wide Gamut",
            colorScienceName = "Natural",
            activeTransport = GpuActiveTransport.BT2020_SDR,
            noiseMetric = 0.28f,
            sdrQualityIntent = SdrQualityIntent.PRO
        )
        val normalPacked = normal.toFloatArray()
        val proPacked = pro.toFloatArray()

        assertTrue(proPacked[8] > normalPacked[8])
        assertTrue(proPacked[8] <= 0.90f)
        assertTrue(proPacked[5] >= 0.30f)
        assertTrue(proPacked[6] >= 0.36f)
        assertNotEquals(normal.signature, pro.signature)
        assertTrue(pro.signature.contains("sdrQualityIntent=PRO"))
        assertEquals(normal.colorSpace, pro.colorSpace)
        assertEquals(normal.colorScience, pro.colorScience)
    }

    @Test
    fun v20ZeroGrainClarityIsAppliedInsideAbiStableUniformPacket() {
        val noisyProfile = profile.copy(
            temporalBlend = 0.44f,
            spatialDenoise = 0.24f,
            chromaDenoise = 0.30f,
            sharpening = 0.92f,
            noiseMetrics = com.neoproc.neoproccamx.pipeline.VideoNoiseMetrics(
                chromaNoise = 0.82f,
                lumaNoise = 0.78f,
                temporalInstability = 0.34f,
                frameToFrameRgbVariance = 0.86f
            )
        )

        val uniforms = GpuImageEnhancementUniforms.fromSceneProfile(
            profile = noisyProfile,
            signalFamily = GpuSignalFamily.SDR,
            colorSpaceName = "Rec.709",
            colorScienceName = "Natural",
            activeTransport = GpuActiveTransport.REC709_SDR,
            noiseMetric = 0.84f,
            localChromaSpikeSmoothing = 0.76f,
            sharpeningFloor = 0.12f,
            sharpeningCeiling = 0.86f,
            skinDetailProtection = 0.72f,
            videoDetailProfile = VideoDetailProfile.NATURAL_DETAIL,
            sdrQualityIntent = SdrQualityIntent.PRO
        )
        val packed = uniforms.toFloatArray()

        assertEquals(VulkanBridge.ENHANCEMENT_UNIFORM_FLOAT_COUNT, packed.size)
        assertTrue(uniforms.v20ZeroGrainClarityActive)
        assertTrue("spatial=${uniforms.spatialDenoise}", uniforms.spatialDenoise >= 0.92f)
        assertTrue("chroma=${uniforms.chromaDenoise}", uniforms.chromaDenoise >= 0.94f)
        assertTrue("sharpen=${uniforms.sharpening}", uniforms.sharpening <= uniforms.sharpeningCeiling)
        assertTrue(uniforms.signature.contains("v20ZeroGrainClarity=true"))
        assertTrue(uniforms.signature.contains("V20_ZERO_GRAIN_CLARITY|active=true"))
    }

    @Test
    fun externalProfileNamesAreMigrationAliasesOnly() {
        val normalized = GpuColorProfileNormalizer.normalize(
            colorSpaceName = "External Wide Gamut",
            colorScienceName = "External Log Alias"
        )

        assertEquals(GpuColorSpace.NEO_WIDE_GAMUT, normalized.colorSpace)
        assertEquals(GpuColorScience.N_LOG_CINEMA, normalized.colorScience)
        assertTrue(normalized.migrationApplied)
        assertFalse(normalized.exportLabel.contains("External", ignoreCase = true))
    }

    @Test
    fun displayP3NaturalMigratesOnlyColorSpaceToNeoWideGamut() {
        val normalized = GpuColorProfileNormalizer.normalize(
            colorSpaceName = "Display P3",
            colorScienceName = "Natural"
        )

        assertEquals(GpuColorSpace.NEO_WIDE_GAMUT, normalized.colorSpace)
        assertEquals(GpuColorScience.NATURAL, normalized.colorScience)
        assertEquals(GpuActiveTransport.BT2020_SDR, normalized.recommendedTransport)
        assertTrue(normalized.migrationApplied)
    }

    @Test
    fun unrelatedWideWordsDoNotMigrateToPrivateLogScience() {
        val normalized = GpuColorProfileNormalizer.normalize(
            colorSpaceName = "redwood daylight Rec.709",
            colorScienceName = "Natural"
        )

        assertEquals(GpuColorSpace.REC709, normalized.colorSpace)
        assertEquals(GpuColorScience.NATURAL, normalized.colorScience)
        assertFalse(normalized.migrationApplied)
    }

    @Test
    fun explicitExternalLogTokensMigrateOnlyLogScience() {
        val normalized = GpuColorProfileNormalizer.normalize(
            colorSpaceName = "Rec.2020",
            colorScienceName = "External Cinema Log"
        )

        assertEquals(GpuColorSpace.REC2020, normalized.colorSpace)
        assertEquals(GpuColorScience.N_LOG_CINEMA, normalized.colorScience)
        assertTrue(normalized.migrationApplied)
    }

    @Test
    fun ndrAndNivukxMapToNeoHdrProfilesWithBt2020PqTransport() {
        val ndr = GpuColorProfileNormalizer.normalize("Rec.2020-NDR10+", "NDR10+")
        val nivukx = GpuColorProfileNormalizer.normalize("Rec.2020-NIVUKX", "NIVUKX Vision")

        assertEquals(GpuColorScience.NDR10_PLUS, ndr.colorScience)
        assertEquals(GpuColorScience.NIVUKX_VISION, nivukx.colorScience)
        assertEquals(GpuActiveTransport.BT2100_PQ_MAIN10, ndr.recommendedTransport)
        assertEquals(GpuActiveTransport.BT2100_PQ_MAIN10, nivukx.recommendedTransport)
    }

    @Test
    fun neoAcesHighEndIsAFirstClassUserSelectionNotAMigrationAlias() {
        val normalized = GpuColorProfileNormalizer.normalize(
            colorSpaceName = "Neo ACES AP1",
            colorScienceName = "Neo ACES High End"
        )

        assertEquals(GpuColorSpace.NEO_ACES_AP1, normalized.colorSpace)
        assertEquals(GpuColorScience.NEO_ACES_HIGH_END, normalized.colorScience)
        assertEquals(GpuActiveTransport.BT2020_SDR, normalized.recommendedTransport)
        assertFalse(normalized.migrationApplied)
        assertEquals("NEO_ACES_AP1:NEO_ACES_HIGH_END", normalized.exportLabel)
    }

    @Test
    fun neoAcesAp0AndAp1RemainDistinctGpuColorSpacesWithSharedHighEndScience() {
        val ap0 = GpuColorProfileNormalizer.normalize(
            colorSpaceName = "ACES AP0",
            colorScienceName = "Neo ACES High End"
        )
        val ap1 = GpuColorProfileNormalizer.normalize(
            colorSpaceName = "ACEScg",
            colorScienceName = "Neo ACES High End"
        )

        assertEquals(GpuColorSpace.NEO_ACES_AP0, ap0.colorSpace)
        assertEquals(GpuColorSpace.NEO_ACES_AP1, ap1.colorSpace)
        assertEquals(GpuColorScience.NEO_ACES_HIGH_END, ap0.colorScience)
        assertEquals(GpuColorScience.NEO_ACES_HIGH_END, ap1.colorScience)
        assertEquals(GpuActiveTransport.BT2020_SDR, ap0.recommendedTransport)
        assertEquals(GpuActiveTransport.BT2020_SDR, ap1.recommendedTransport)
        assertFalse(ap0.migrationApplied)
        assertFalse(ap1.migrationApplied)
        assertNotEquals(ap0.exportLabel, ap1.exportLabel)
    }

    @Test
    fun ndrAndNivukxManualSpaceAliasesResolveToBt2020NeoHdrInsteadOfRec709() {
        val ndr = GpuColorProfileNormalizer.normalize("NDR10+", "NDR10+")
        val nivukx = GpuColorProfileNormalizer.normalize("NIVUKX Vision", "NIVUKX Vision")

        assertEquals(GpuColorSpace.REC2020, ndr.colorSpace)
        assertEquals(GpuColorSpace.REC2020, nivukx.colorSpace)
        assertEquals(GpuColorScience.NDR10_PLUS, ndr.colorScience)
        assertEquals(GpuColorScience.NIVUKX_VISION, nivukx.colorScience)
        assertEquals(GpuActiveTransport.BT2100_PQ_MAIN10, ndr.recommendedTransport)
        assertEquals(GpuActiveTransport.BT2100_PQ_MAIN10, nivukx.recommendedTransport)
    }

    @Test
    fun mapsArchitectureTailFieldsIntoReservedUniformSlots() {
        val uniforms = GpuImageEnhancementUniforms.fromSceneProfile(
            profile = profile,
            signalFamily = GpuSignalFamily.LOG,
            colorSpaceName = "External Wide Gamut",
            colorScienceName = "External Log Alias",
            activeTransport = GpuActiveTransport.BT2020_LOG_SDR,
            exportProfile = Npv10ExportEnhancementProfile.LOG_VIDEO,
            iso = 1600f,
            noiseMetric = 0.48f,
            localChromaSpikeSmoothing = 0.72f,
            sharpeningFloor = 0.18f,
            sharpeningCeiling = 0.56f,
            skinDetailProtection = 0.33f
        )

        val packed = uniforms.toFloatArray()

        assertEquals(VulkanBridge.ENHANCEMENT_UNIFORM_FLOAT_COUNT, packed.size)
        assertEquals(0.72f, packed[27], 0.0001f)
        assertEquals(0.18f, packed[28], 0.0001f)
        assertEquals(0.56f, packed[29], 0.0001f)
        assertEquals(0.33f, packed[30], 0.0001f)
        assertEquals(1f, packed[31], 0.0001f)
    }

    @Test
    fun packsHalSensorNoiseModelForHeteroscedasticGpuDenoise() {
        val uniforms = GpuImageEnhancementUniforms.fromSceneProfile(
            profile = profile,
            signalFamily = GpuSignalFamily.HDR,
            colorSpaceName = "Rec.2020",
            colorScienceName = "NDR10+",
            activeTransport = GpuActiveTransport.BT2100_PQ_MAIN10,
            sensorShotNoiseCoefficient = 0.0065f,
            sensorReadNoiseCoefficient = 0.0012f
        )

        val packed = uniforms.toFloatArray()

        assertEquals(VulkanBridge.ENHANCEMENT_UNIFORM_FLOAT_COUNT, packed.size)
        assertEquals(0.0065f, packed[38], 0.0001f)
        assertEquals(0.0012f, packed[39], 0.0001f)
        assertTrue(uniforms.signature.contains("noiseModel=heteroscedastic"))
    }

    @Test
    fun defaultGpuNoiseCoefficientsAndChecksumMatchBeyondImaxProfile() {
        val uniforms = GpuImageEnhancementUniforms.fromSceneProfile(
            profile = profile,
            signalFamily = GpuSignalFamily.HDR,
            colorSpaceName = "Rec.2020",
            colorScienceName = "NDR10+",
            activeTransport = GpuActiveTransport.BT2100_PQ_MAIN10
        )
        val packed = uniforms.toFloatArray()

        assertEquals(BeyondImaxEliteProfile.SHOT_NOISE_COEFFICIENT, packed[38], 0.0000001f)
        assertEquals(BeyondImaxEliteProfile.READ_NOISE_COEFFICIENT, packed[39], 0.0000001f)
        assertTrue(uniforms.signature.contains("strictProfile=${BeyondImaxEliteProfile.PROFILE_NAME}"))
        assertTrue(uniforms.signature.contains("strictChecksum=${BeyondImaxEliteProfile.CANONICAL_UNIFORM_CHECKSUM}"))
        assertTrue(uniforms.signature.contains("strictProfile=BEYOND_IMAX_ELITE_10_10"))
    }

    @Test
    fun adaptiveSdrRejectsTenXSubjectDetailAndUsesSharpeningSkinBudget() {
        val subjectPlan = focusedSubjectPlan(additiveDetailGain = 10f)
        val uniforms = GpuImageEnhancementUniforms.fromSceneProfile(
            profile = profile,
            signalFamily = GpuSignalFamily.SDR,
            colorSpaceName = "Rec.709",
            colorScienceName = "Natural",
            activeTransport = GpuActiveTransport.REC709_SDR,
            sharpeningCeiling = 0.74f,
            skinDetailProtection = 0.82f,
            videoDetailProfile = VideoDetailProfile.FOCUSED_DETAIL,
            subjectDetailPlan = subjectPlan,
            sdrQualityIntent = SdrQualityIntent.NORMAL
        )
        val packed = uniforms.toFloatArray()
        val expectedBudget = (packed[29] - packed[8]).coerceAtLeast(0f) *
            (1f - packed[30] * 0.5f)

        assertEquals(44, packed.size)
        assertEquals(1f, packed[40], 0.0001f)
        assertEquals(1f, packed[41], 0.0001f)
        assertEquals(expectedBudget, packed[42], 0.0001f)
        assertTrue(packed[42] in 0f..1f)
        assertEquals(0.16f, packed[43], 0.0001f)
        assertTrue(uniforms.signature.contains("detailProfile=FOCUSED_DETAIL"))
        assertFalse(uniforms.signature.contains("subjectDetailGain=10.0000"))
    }

    @Test
    fun packsNaturalDetailAsSofterThanFocusedBaselineWithoutSubjectBoost() {
        val subjectPlan = SubjectDetailEnhancementPlan(
            profile = VideoDetailProfile.NATURAL_DETAIL,
            subjectDetailRequested = false,
            subjectDetailApplied = false,
            subjectMaskPresent = true,
            additiveDetailGain = 0f,
            maskFeather = 0.18f,
            haloGuard = 0.65f,
            textureGuard = 0.60f,
            naturalZeroSharpening = true,
            signature = "natural-mask"
        )
        val packed = GpuImageEnhancementUniforms.fromSceneProfile(
            profile = profile,
            signalFamily = GpuSignalFamily.SDR,
            colorSpaceName = "Rec.709",
            colorScienceName = "Natural",
            videoDetailProfile = VideoDetailProfile.NATURAL_DETAIL,
            subjectDetailPlan = subjectPlan
        ).toFloatArray()

        assertEquals(2f, packed[40], 0.0001f)
        assertEquals(1f, packed[41], 0.0001f)
        assertEquals(0f, packed[42], 0.0001f)
        // F-DETAIL: NATURAL is now a distinct, softer record sharpening than the FOCUSED
        // baseline (0.96 * 0.75 = 0.72) so the three detail profiles genuinely differ on
        // recorded pixels. FOCUSED remains the identity baseline (asserted elsewhere).
        assertEquals(0.72f, packed[8], 0.0001f)
        assertTrue("NATURAL must be softer than the 0.96 FOCUSED baseline", packed[8] < 0.96f)
    }

    @Test
    fun `packs extreme detail into dedicated uniform slot without subject mask requirement`() {
        val uniforms = GpuImageEnhancementUniforms.fromSceneProfile(
            profile = profile,
            signalFamily = GpuSignalFamily.SDR,
            colorSpaceName = "Rec.709",
            colorScienceName = "Natural",
            videoDetailProfile = VideoDetailProfile.EXTREME_DETAIL,
            subjectDetailPlan = null
        )

        val packed = uniforms.toFloatArray()

        assertEquals(3f, packed[40], 0.0001f)
        assertEquals(0f, packed[41], 0.0001f)
        assertEquals(0f, packed[42], 0.0001f)
        assertTrue(uniforms.signature.contains("detailProfile=EXTREME_DETAIL"))
    }

    @Test
    fun signatureCarriesZenithPipelineAndDualGainMicrosecondGuard() {
        val uniforms = GpuImageEnhancementUniforms.fromSceneProfile(
            profile = profile.copy(
                dualGainFusionEnabled = true,
                dualGainMotionMaskEnabled = true,
                dualGainTimestampSkewNs = 500L
            ),
            signalFamily = GpuSignalFamily.HDR,
            colorSpaceName = "Rec.2020",
            colorScienceName = "NDR10+",
            activeTransport = GpuActiveTransport.BT2100_PQ_MAIN10
        )

        assertTrue(uniforms.signature.contains("zenith=ZENITH_COSMIC_PLANCK"))
        assertTrue(uniforms.signature.contains("dualGainGuardNs=1000"))
        assertTrue(uniforms.signature.contains("dualGain=PUBLIC_CAMERA2_DUAL_GAIN"))
        assertTrue(uniforms.signature.contains("vendor=false"))
    }

    @Test
    fun signatureChangesWhenAnyPackedProcessingInputChanges() {
        val baseline = GpuImageEnhancementUniforms.fromSceneProfile(
            profile = profile,
            signalFamily = GpuSignalFamily.LOG,
            colorSpaceName = "Rec.2020-N-Log",
            colorScienceName = "N-Log Cinema",
            activeTransport = GpuActiveTransport.BT2020_LOG_SDR,
            exportProfile = Npv10ExportEnhancementProfile.LOG_VIDEO,
            iso = 1600f,
            noiseMetric = 0.48f
        )
        val contrastChanged = GpuImageEnhancementUniforms.fromSceneProfile(
            profile = profile.copy(contrast = 1.11f),
            signalFamily = GpuSignalFamily.LOG,
            colorSpaceName = "Rec.2020-N-Log",
            colorScienceName = "N-Log Cinema",
            activeTransport = GpuActiveTransport.BT2020_LOG_SDR,
            exportProfile = Npv10ExportEnhancementProfile.LOG_VIDEO,
            iso = 1600f,
            noiseMetric = 0.48f
        )
        val logStrengthChanged = GpuImageEnhancementUniforms.fromSceneProfile(
            profile = profile.copy(logStrength = 0.77f),
            signalFamily = GpuSignalFamily.LOG,
            colorSpaceName = "Rec.2020-N-Log",
            colorScienceName = "N-Log Cinema",
            activeTransport = GpuActiveTransport.BT2020_LOG_SDR,
            exportProfile = Npv10ExportEnhancementProfile.LOG_VIDEO,
            iso = 1600f,
            noiseMetric = 0.48f
        )
        val temporalChromaChanged = GpuImageEnhancementUniforms.fromSceneProfile(
            profile = profile,
            signalFamily = GpuSignalFamily.LOG,
            colorSpaceName = "Rec.2020-N-Log",
            colorScienceName = "N-Log Cinema",
            activeTransport = GpuActiveTransport.BT2020_LOG_SDR,
            exportProfile = Npv10ExportEnhancementProfile.LOG_VIDEO.copy(temporalChromaBlend = 0.87),
            iso = 1600f,
            noiseMetric = 0.48f
        )
        val noiseMetricChanged = GpuImageEnhancementUniforms.fromSceneProfile(
            profile = profile,
            signalFamily = GpuSignalFamily.LOG,
            colorSpaceName = "Rec.2020-N-Log",
            colorScienceName = "N-Log Cinema",
            activeTransport = GpuActiveTransport.BT2020_LOG_SDR,
            exportProfile = Npv10ExportEnhancementProfile.LOG_VIDEO,
            iso = 1600f,
            noiseMetric = 0.61f
        )

        assertNotEquals(baseline.signature, contrastChanged.signature)
        assertNotEquals(baseline.signature, logStrengthChanged.signature)
        assertNotEquals(baseline.signature, temporalChromaChanged.signature)
        assertNotEquals(baseline.signature, noiseMetricChanged.signature)
    }

    @Test
    fun uniformPacketUsesNativeBridgeExpectedSize() {
        val uniforms = GpuImageEnhancementUniforms.fromSceneProfile(
            profile = profile,
            signalFamily = GpuSignalFamily.SDR,
            colorSpaceName = "Rec.709",
            colorScienceName = "Rec.709 Standard",
            activeTransport = GpuActiveTransport.REC709_SDR
        )

        assertEquals(VulkanBridge.ENHANCEMENT_UNIFORM_FLOAT_COUNT, uniforms.toFloatArray().size)
    }

    @Test
    fun signalFamilyResolverKeepsNormalAndProSdrLogLogAndNeoHdrHdr() {
        assertEquals(
            GpuSignalFamily.SDR,
            GpuSignalFamilyResolver.resolve(
                mode = "VIDEO",
                logEnabled = false,
                hdrEnabled = false,
                rawEnabled = false,
                profile = "Rec.709"
            )
        )
        assertEquals(
            GpuSignalFamily.SDR,
            GpuSignalFamilyResolver.resolve(
                mode = "PRO",
                logEnabled = false,
                hdrEnabled = false,
                rawEnabled = false,
                profile = "Neo Wide Gamut"
            )
        )
        assertEquals(
            GpuSignalFamily.LOG,
            GpuSignalFamilyResolver.resolve(
                mode = "LOG",
                logEnabled = true,
                hdrEnabled = false,
                rawEnabled = false,
                profile = "N-Log Cinema"
            )
        )
        assertEquals(
            GpuSignalFamily.LOG,
            GpuSignalFamilyResolver.resolve(
                mode = "VIDEO",
                logEnabled = false,
                hdrEnabled = false,
                rawEnabled = false,
                profile = "log monitor"
            )
        )
        assertEquals(
            GpuSignalFamily.HDR,
            GpuSignalFamilyResolver.resolve(
                mode = "HDR",
                logEnabled = false,
                hdrEnabled = true,
                rawEnabled = false,
                profile = "NDR10+"
            )
        )
        assertEquals(
            GpuSignalFamily.HDR,
            GpuSignalFamilyResolver.resolve(
                mode = "VIDEO",
                logEnabled = false,
                hdrEnabled = false,
                rawEnabled = false,
                profile = "NIVUKX Vision hdr"
            )
        )
        assertEquals(
            GpuSignalFamily.RAW,
            GpuSignalFamilyResolver.resolve(
                mode = "RAW",
                logEnabled = false,
                hdrEnabled = false,
                rawEnabled = true,
                profile = "RAW"
            )
        )
    }

    private fun focusedSubjectPlan(additiveDetailGain: Float) = SubjectDetailEnhancementPlan(
        profile = VideoDetailProfile.FOCUSED_DETAIL,
        subjectDetailRequested = true,
        subjectDetailApplied = true,
        subjectMaskPresent = true,
        additiveDetailGain = additiveDetailGain,
        maskFeather = 0.16f,
        haloGuard = 0.72f,
        textureGuard = 0.68f,
        naturalZeroSharpening = false,
        signature = "focused-mask"
    )

    private data class GpuSdrStressCase(
        val name: String,
        val profile: SceneRenderProfile,
        val noiseMetric: Float,
        val sharpeningFloor: Float,
        val sharpeningCeiling: Float,
        val skinDetailProtection: Float
    )
}
