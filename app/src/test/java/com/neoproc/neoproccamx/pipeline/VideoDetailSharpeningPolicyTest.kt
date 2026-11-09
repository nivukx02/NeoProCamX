package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.core.ImaxStrictProfile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoDetailSharpeningPolicyTest {

    @Test
    fun noisyShadowsReduceSharpeningAndRaiseHaloSuppression() {
        val cleanMidtone = plan(
            lumaLevel = 0.48f,
            noiseMetrics = VideoNoiseMetrics(
                chromaNoise = 0.08f,
                lumaNoise = 0.06f,
                temporalInstability = 0.05f,
                frameToFrameRgbVariance = 0.07f
            )
        )
        val noisyShadow = plan(
            lumaLevel = 0.08f,
            noiseMetrics = VideoNoiseMetrics(
                chromaNoise = 0.72f,
                lumaNoise = 0.68f,
                temporalInstability = 0.42f,
                frameToFrameRgbVariance = 0.74f
            )
        )

        assertTrue(
            "noisyShadow=${noisyShadow.sharpeningStrength} clean=${cleanMidtone.sharpeningStrength}",
            noisyShadow.sharpeningStrength < cleanMidtone.sharpeningStrength
        )
        assertTrue("noisy shadow suppression must be active", noisyShadow.noisyShadowSharpeningSuppression >= 0.70f)
        assertTrue("halo suppression must rise in noisy shadows", noisyShadow.haloSuppression > cleanMidtone.haloSuppression)
    }

    @Test
    fun strongEdgesGetMoreDetailThanFlatSkin() {
        val strongEdge = plan(
            edgeDensity = 0.62f,
            perceptualSharpness = 0.74f,
            skinToneConfidence = 0.10f,
            textureConfidence = 0.72f
        )
        val flatSkin = plan(
            edgeDensity = 0.04f,
            perceptualSharpness = 0.18f,
            skinToneConfidence = 0.92f,
            textureConfidence = 0.12f
        )

        assertTrue("edge confidence must be higher", strongEdge.edgeConfidence > flatSkin.edgeConfidence)
        assertTrue("strong edge sharpening=${strongEdge.sharpeningStrength} flat skin=${flatSkin.sharpeningStrength}",
            strongEdge.sharpeningStrength > flatSkin.sharpeningStrength)
        assertTrue("strong edge reconstruction=${strongEdge.detailReconstruction} flat skin=${flatSkin.detailReconstruction}",
            strongEdge.detailReconstruction > flatSkin.detailReconstruction)
        assertTrue("flat skin guard must stay active", flatSkin.skinTextureGuard >= 0.80f)
        assertTrue("flat skin must not get high-confidence reconstruction", flatSkin.detailReconstruction <= 0.20f)
    }

    @Test
    fun strictImaxDetailProfileReachesStrictDetailCeilingsWithoutGlobalSharpening() {
        val plan = plan(
            lumaLevel = 0.46f,
            edgeDensity = 1.0f,
            perceptualSharpness = 1.0f,
            skinToneConfidence = 0.0f,
            textureConfidence = 1.0f,
            noiseMetrics = VideoNoiseMetrics(
                chromaNoise = 0.0f,
                lumaNoise = 0.0f,
                temporalInstability = 0.0f,
                frameToFrameRgbVariance = 0.0f
            )
        )

        assertFalse(plan.globalSharpeningAllowed)
        assertEquals(ImaxStrictProfile.SHARPENING_STRICT_CEILING, plan.sharpeningStrength, 0.000001f)
        assertTrue("microContrast=${plan.microContrast}", plan.microContrast <= 0.74f)
        assertTrue("detailReconstruction=${plan.detailReconstruction}", plan.detailReconstruction <= 0.95f)
        assertTrue("haloSuppression=${plan.haloSuppression}", plan.haloSuppression >= 0.70f)
        assertTrue("moireAliasGuard=${plan.moireAliasGuard}", plan.moireAliasGuard >= 0.72f)
        assertTrue("skinTextureGuard=${plan.skinTextureGuard}", plan.skinTextureGuard >= 0.60f)
    }

    @Test
    fun detailPlanUsesEdgeAwareSharpeningAfterDenoiseWithoutFakeResolution() {
        val upscalePlan = plan(
            selectedWidth = 7680,
            selectedHeight = 4320,
            nativeWidth = 3840,
            nativeHeight = 2160
        )
        val nativePlan = plan(
            selectedWidth = 3840,
            selectedHeight = 2160,
            nativeWidth = 3840,
            nativeHeight = 2160
        )

        assertTrue(upscalePlan.edgeAwareOnly)
        assertTrue(upscalePlan.sharpeningAfterDenoise)
        assertFalse(upscalePlan.globalSharpeningAllowed)
        assertFalse(upscalePlan.pixelDuplicationAllowed)
        assertFalse(upscalePlan.trueResolutionClaim.contains("TRUE_8K"))
        assertFalse(upscalePlan.trueResolutionClaim.contains("TRUE_16K"))
        assertTrue(upscalePlan.trueResolutionClaim.contains("NOT_TRUE_NATIVE"))
        assertTrue(nativePlan.trueResolutionClaim.contains("TRUE_NATIVE_3840x2160"))
    }

    @Test
    fun naturalDetailKeepsCleanEdgeAwareBaselineWithoutSubjectBoost() {
        val natural = plan(
            videoDetailProfile = VideoDetailProfile.NATURAL_DETAIL,
            subjectDetailPlan = SubjectDetailEnhancementPolicy.resolve(
                SubjectDetailEnhancementRequest(
                    profile = VideoDetailProfile.NATURAL_DETAIL,
                    maskAvailable = true,
                    maskTrackingId = 8,
                    maskConfidence = 0.9f,
                    motionScore = 0.1f,
                    noiseFloor = 0.1f,
                    skinToneConfidence = 0.1f
                )
            )
        )

        assertTrue("natural sharpening=${natural.sharpeningStrength}", natural.sharpeningStrength >= 0.18f)
        assertTrue("natural microContrast=${natural.microContrast}", natural.microContrast > 0f)
        assertTrue("natural reconstruction=${natural.detailReconstruction}", natural.detailReconstruction > 0f)
        assertEquals("NATURAL_DETAIL", natural.toMetadata().getValue("selectedDetailProfile"))
        assertEquals("false", natural.toMetadata().getValue("naturalZeroSharpening"))
    }

    @Test
    fun `legacy detail plans do not emit selected detail profile metadata`() {
        val legacy = plan(videoDetailProfile = null)

        assertFalse(legacy.toMetadata().containsKey("selectedDetailProfile"))
    }

    @Test
    fun `extreme detail raises clean stable detail beyond focused and natural profiles`() {
        val natural = plan(videoDetailProfile = VideoDetailProfile.NATURAL_DETAIL)
        val focused = plan(videoDetailProfile = VideoDetailProfile.FOCUSED_DETAIL)
        val extreme = plan(videoDetailProfile = VideoDetailProfile.EXTREME_DETAIL)

        assertTrue(extreme.sharpeningStrength > focused.sharpeningStrength)
        assertTrue(extreme.microContrast > focused.microContrast)
        assertTrue(extreme.detailReconstruction > focused.detailReconstruction)
        assertTrue(focused.sharpeningStrength > natural.sharpeningStrength)
        assertTrue(extreme.microContrast > natural.microContrast)
        assertTrue(extreme.detailReconstruction > natural.detailReconstruction)
        assertFalse(extreme.globalSharpeningAllowed)
        assertFalse(extreme.pixelDuplicationAllowed)
        assertEquals("EXTREME_DETAIL", extreme.toMetadata().getValue("selectedDetailProfile"))
    }

    @Test
    fun `extreme detail backs off in noisy shadows and raises artifact guards`() {
        val cleanExtreme = plan(videoDetailProfile = VideoDetailProfile.EXTREME_DETAIL)
        val noisyExtreme = plan(
            videoDetailProfile = VideoDetailProfile.EXTREME_DETAIL,
            lumaLevel = 0.08f,
            edgeDensity = 0.18f,
            perceptualSharpness = 0.30f,
            textureConfidence = 0.24f,
            noiseMetrics = VideoNoiseMetrics(
                chromaNoise = 0.72f,
                lumaNoise = 0.70f,
                temporalInstability = 0.46f,
                frameToFrameRgbVariance = 0.76f
            )
        )

        assertTrue(noisyExtreme.sharpeningStrength < cleanExtreme.sharpeningStrength)
        assertTrue(noisyExtreme.microContrast < cleanExtreme.microContrast)
        assertTrue(noisyExtreme.haloSuppression >= cleanExtreme.haloSuppression)
        assertTrue(noisyExtreme.moireAliasGuard >= cleanExtreme.moireAliasGuard)
        assertTrue(noisyExtreme.skinTextureGuard >= cleanExtreme.skinTextureGuard)
    }

    @Test
    fun `extreme detail noisy backoff never undercuts edge aware baseline`() {
        val noisyLegacy = plan(
            videoDetailProfile = null,
            lumaLevel = 0.08f,
            edgeDensity = 0.18f,
            perceptualSharpness = 0.30f,
            textureConfidence = 0.24f,
            noiseMetrics = VideoNoiseMetrics(
                chromaNoise = 0.72f,
                lumaNoise = 0.70f,
                temporalInstability = 0.46f,
                frameToFrameRgbVariance = 0.76f
            )
        )
        val noisyExtreme = plan(
            videoDetailProfile = VideoDetailProfile.EXTREME_DETAIL,
            lumaLevel = 0.08f,
            edgeDensity = 0.18f,
            perceptualSharpness = 0.30f,
            textureConfidence = 0.24f,
            noiseMetrics = VideoNoiseMetrics(
                chromaNoise = 0.72f,
                lumaNoise = 0.70f,
                temporalInstability = 0.46f,
                frameToFrameRgbVariance = 0.76f
            )
        )

        assertTrue(noisyExtreme.sharpeningStrength >= noisyLegacy.sharpeningStrength)
        assertTrue(noisyExtreme.microContrast >= noisyLegacy.microContrast)
        assertTrue(noisyExtreme.detailReconstruction >= noisyLegacy.detailReconstruction)
    }

    @Test
    fun strictNormalSdrNaturalDetailKeepsEliteDetailAuthorityWithoutHdrPromotion() {
        val subjectPlan = SubjectDetailEnhancementPolicy.resolve(
            SubjectDetailEnhancementRequest(
                profile = VideoDetailProfile.NATURAL_DETAIL,
                maskAvailable = true,
                maskTrackingId = 9,
                maskConfidence = 0.92f,
                motionScore = 0.04f,
                noiseFloor = 0.05f,
                skinToneConfidence = 0.08f
            )
        )
        val strictNatural = plan(
            videoDetailProfile = VideoDetailProfile.NATURAL_DETAIL,
            subjectDetailPlan = subjectPlan,
            recording = true,
            strictHighEnd = true,
            edgeDensity = 0.56f,
            perceptualSharpness = 0.62f,
            textureConfidence = 0.64f
        )
        val strictLegacy = plan(
            videoDetailProfile = null,
            recording = true,
            strictHighEnd = true,
            edgeDensity = 0.56f,
            perceptualSharpness = 0.62f,
            textureConfidence = 0.64f
        )
        val nonStrictNatural = plan(
            videoDetailProfile = VideoDetailProfile.NATURAL_DETAIL,
            subjectDetailPlan = subjectPlan,
            recording = true,
            strictHighEnd = false,
            edgeDensity = 0.56f,
            perceptualSharpness = 0.62f,
            textureConfidence = 0.64f
        )

        assertEquals(VideoImageSignalFamily.SDR.name, strictNatural.signature.substringBefore("|"))
        assertEquals("NATURAL_DETAIL", strictNatural.toMetadata().getValue("selectedDetailProfile"))
        assertEquals("false", strictNatural.toMetadata().getValue("naturalZeroSharpening"))
        assertFalse(strictNatural.globalSharpeningAllowed)
        assertFalse(strictNatural.pixelDuplicationAllowed)
        assertTrue(
            "strict natural=${strictNatural.sharpeningStrength} legacy=${strictLegacy.sharpeningStrength}",
            strictNatural.sharpeningStrength >= strictLegacy.sharpeningStrength - 0.000001f
        )
        assertTrue(
            "strict natural=${strictNatural.sharpeningStrength} nonStrict=${nonStrictNatural.sharpeningStrength}",
            strictNatural.sharpeningStrength >= nonStrictNatural.sharpeningStrength + 0.20f
        )
        assertTrue(
            "strict reconstruction=${strictNatural.detailReconstruction} nonStrict=${nonStrictNatural.detailReconstruction}",
            strictNatural.detailReconstruction > nonStrictNatural.detailReconstruction
        )
        assertTrue(strictNatural.trueResolutionClaim.contains("TRUE_NATIVE_3840x2160"))
    }

    @Test
    fun strictNormalSdrRecordingCarriesStrictDetailBoost() {
        val recordingStrict = plan(
            recording = true,
            strictHighEnd = true,
            edgeDensity = 0.34f,
            perceptualSharpness = 0.46f
        )
        val recordingNonStrict = plan(
            recording = true,
            strictHighEnd = false,
            edgeDensity = 0.34f,
            perceptualSharpness = 0.46f
        )
        val previewStrict = plan(
            recording = false,
            strictHighEnd = true,
            edgeDensity = 0.34f,
            perceptualSharpness = 0.46f
        )

        assertTrue(
            "strict recording must include the strict high-end detail lift; strict=${recordingStrict.sharpeningStrength} nonStrict=${recordingNonStrict.sharpeningStrength}",
            recordingStrict.sharpeningStrength >= recordingNonStrict.sharpeningStrength + 0.20f
        )
        assertTrue(
            "recording must not be weaker than recording-grade preview; recording=${recordingStrict.sharpeningStrength} preview=${previewStrict.sharpeningStrength}",
            recordingStrict.sharpeningStrength >= previewStrict.sharpeningStrength
        )
    }

    @Test
    fun focusedDetailCarriesMaskGatedGainWithoutChangingResolutionTruth() {
        val subjectPlan = SubjectDetailEnhancementPolicy.resolve(
            SubjectDetailEnhancementRequest(
                profile = VideoDetailProfile.FOCUSED_DETAIL,
                maskAvailable = true,
                maskTrackingId = 11,
                maskConfidence = 0.9f,
                motionScore = 0.1f,
                noiseFloor = 0.1f,
                skinToneConfidence = 0.1f
            )
        )
        val focused = plan(
            selectedWidth = 7680,
            selectedHeight = 4320,
            nativeWidth = 3840,
            nativeHeight = 2160,
            videoDetailProfile = VideoDetailProfile.FOCUSED_DETAIL,
            subjectDetailPlan = subjectPlan
        )

        assertTrue(
            "focused sharpening=${focused.sharpeningStrength} subject=${subjectPlan.additiveDetailGain}",
            focused.sharpeningStrength <= ImaxStrictProfile.SHARPENING_STRICT_CEILING
        )
        assertTrue("focused sharpening must keep a baseline even when mask is additive", focused.sharpeningStrength >= 0.20f)
        assertEquals("true", focused.toMetadata().getValue("subjectDetailApplied"))
        assertTrue(focused.trueResolutionClaim.contains("NOT_TRUE_NATIVE"))
    }

    @Test
    fun camera2FallbackPreservesPreviewFactButDoesNotClaimRecordedSubjectDetail() {
        val focusedMetadata = plan(
            videoDetailProfile = VideoDetailProfile.FOCUSED_DETAIL,
            subjectDetailPlan = SubjectDetailEnhancementPolicy.resolve(
                SubjectDetailEnhancementRequest(
                    profile = VideoDetailProfile.FOCUSED_DETAIL,
                    maskAvailable = true,
                    maskTrackingId = 12,
                    maskConfidence = 0.92f,
                    motionScore = 0.08f,
                    noiseFloor = 0.08f,
                    skinToneConfidence = 0.10f
                )
            )
        ).toMetadata()

        val recorded = VideoDetailSharpeningPolicy.recordingEvidenceMetadata(
            previewMetadata = focusedMetadata,
            recordingGpuFrameImportValidated = false
        )

        assertEquals("true", recorded.getValue("previewSubjectDetailApplied"))
        assertEquals("false", recorded.getValue("recordingSubjectDetailApplied"))
        assertEquals("false", recorded.getValue("subjectDetailApplied"))
        assertEquals("0.000000", recorded.getValue("subjectDetailGain"))
        assertEquals("false", recorded.getValue("recordingGpuFrameImportValidated"))
        assertEquals("CAMERA2_FALLBACK", recorded.getValue("detailProcessingPath"))
    }

    @Test
    fun validatedGpuFramesAllowRecordedSubjectDetailClaim() {
        val recorded = VideoDetailSharpeningPolicy.recordingEvidenceMetadata(
            previewMetadata = mapOf(
                "selectedDetailProfile" to "FOCUSED_DETAIL",
                "subjectDetailApplied" to "true",
                "subjectDetailGain" to "0.330000"
            ),
            recordingGpuFrameImportValidated = true
        )

        assertEquals("true", recorded.getValue("previewSubjectDetailApplied"))
        assertEquals("true", recorded.getValue("recordingSubjectDetailApplied"))
        assertEquals("true", recorded.getValue("recordingGpuFrameImportValidated"))
        assertEquals("VULKAN_MASKED_SUBJECT_DETAIL", recorded.getValue("detailProcessingPath"))
        assertEquals("0.330000", recorded.getValue("subjectDetailGain"))
    }

    @Test
    fun npv10PrivateMasterRecordsRequestedProfileButNeverClaimsDestructiveDetail() {
        val metadata = VideoDetailSharpeningPolicy.npv10MasterEvidenceMetadata(
            previewMetadata = mapOf(
                "selectedDetailProfile" to "FOCUSED_DETAIL",
                "subjectDetailApplied" to "true",
                "subjectDetailGain" to "0.330000"
            )
        )

        assertEquals("FOCUSED_DETAIL", metadata.getValue("selectedDetailProfile"))
        assertEquals("true", metadata.getValue("previewSubjectDetailApplied"))
        assertEquals("false", metadata.getValue("subjectDetailApplied"))
        assertEquals("0.000000", metadata.getValue("subjectDetailGain"))
        assertEquals("NPV10_PRIVATE_MASTER_UNMODIFIED", metadata.getValue("detailProcessingPath"))
        assertEquals("false", metadata.getValue("npv10ExportSubjectMaskStored"))
    }

    private fun plan(
        lumaLevel: Float = 0.42f,
        edgeDensity: Float = 0.30f,
        perceptualSharpness: Float = 0.44f,
        skinToneConfidence: Float = 0.0f,
        textureConfidence: Float = 0.42f,
        selectedWidth: Int = 3840,
        selectedHeight: Int = 2160,
        nativeWidth: Int = 3840,
        nativeHeight: Int = 2160,
        videoDetailProfile: VideoDetailProfile? = null,
        subjectDetailPlan: SubjectDetailEnhancementPlan? = null,
        recording: Boolean = true,
        strictHighEnd: Boolean = true,
        noiseMetrics: VideoNoiseMetrics = VideoNoiseMetrics(
            chromaNoise = 0.16f,
            lumaNoise = 0.14f,
            temporalInstability = 0.10f,
            frameToFrameRgbVariance = 0.12f
        )
    ): VideoDetailSharpeningPlan =
        VideoDetailSharpeningPolicy.resolve(
            VideoDetailSharpeningRequest(
                signalFamily = VideoImageSignalFamily.SDR,
                recording = recording,
                lumaLevel = lumaLevel,
                edgeDensity = edgeDensity,
                perceptualSharpness = perceptualSharpness,
                skinToneConfidence = skinToneConfidence,
                textureConfidence = textureConfidence,
                selectedWidth = selectedWidth,
                selectedHeight = selectedHeight,
                nativeWidth = nativeWidth,
                nativeHeight = nativeHeight,
                noiseMetrics = noiseMetrics,
                strictHighEnd = strictHighEnd,
                videoDetailProfile = videoDetailProfile,
                subjectDetailPlan = subjectDetailPlan
            )
        )
}
