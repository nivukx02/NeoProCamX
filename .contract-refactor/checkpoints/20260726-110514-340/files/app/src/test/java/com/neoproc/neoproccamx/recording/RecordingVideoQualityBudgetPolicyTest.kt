package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingVideoQualityBudgetPolicyTest {

    @Test
    fun strictHighEndQualityValueIsLockedAtOneHundred() = withStrictHighEnd {
        assertEquals(100, RecordingVideoQualityBudgetPolicy.resolveQualityValue())
    }

    @Test
    fun strictHighEndBudgetUsesHevcCeilingAcrossCommonResolutionsAndFrameRates() = withStrictHighEnd {
        val hevcCeiling = RecordingCodecCatalog.maxBitrateMbps(GpuVideoCodec.HEVC) * 1_000_000
        val cases = listOf(
            qualityCase(width = 1280, height = 720, fps = 60),
            qualityCase(width = 1920, height = 1080, fps = 60),
            qualityCase(width = 3840, height = 2160, fps = 30),
            qualityCase(width = 3840, height = 2160, fps = 60),
            qualityCase(width = 7680, height = 4320, fps = 30)
        )

        cases.forEach { testCase ->
            val budget = RecordingVideoQualityBudgetPolicy.resolveTargetBitrateBps(
                request = request(
                    width = testCase.width,
                    height = testCase.height,
                    fps = testCase.fps
                )
            )

            assertEquals("${testCase.width}x${testCase.height}@${testCase.fps}", hevcCeiling, budget)
        }
    }

    @Test
    fun strictHighEndBudgetKeepsAllVideoModesAtFullCodecCeiling() = withStrictHighEnd {
        val hevcCeiling = RecordingCodecCatalog.maxBitrateMbps(GpuVideoCodec.HEVC) * 1_000_000
        val normal = RecordingVideoQualityBudgetPolicy.resolveTargetBitrateBps(
            request = request(width = 1920, height = 1080, fps = 30)
        )
        val proSdr = RecordingVideoQualityBudgetPolicy.resolveTargetBitrateBps(
            request = request(
                width = 1920,
                height = 1080,
                fps = 30,
                tenBit = true,
                colorSpace = "Rec.709"
            )
        )
        val hdr = RecordingVideoQualityBudgetPolicy.resolveTargetBitrateBps(
            request = request(
                width = 1920,
                height = 1080,
                fps = 30,
                hdrMode = "HDR10",
                tenBit = true,
                colorSpace = "Rec.2020"
            )
        )
        val log = RecordingVideoQualityBudgetPolicy.resolveTargetBitrateBps(
            request = request(
                width = 1920,
                height = 1080,
                fps = 30,
                logEnabled = true,
                tenBit = true,
                colorSpace = "Rec.2020"
            )
        )
        val extremeDetail = RecordingVideoQualityBudgetPolicy.resolveTargetBitrateBps(
            request = request(
                width = 3840,
                height = 2160,
                fps = 30,
                videoDetailProfile = VideoDetailProfile.EXTREME_DETAIL
            )
        )

        assertEquals(hevcCeiling, normal)
        assertEquals(hevcCeiling, proSdr)
        assertEquals(hevcCeiling, hdr)
        assertEquals(hevcCeiling, log)
        assertEquals(hevcCeiling, extremeDetail)
    }

    @Test
    fun codecCeilingPreventsImpossibleBitrateRequests() {
        val request = request(
            width = 7680,
            height = 4320,
            fps = 60,
            hdrMode = "HDR10",
            tenBit = true,
            colorSpace = "Rec.2020"
        )

        assertEquals(
            2_000_000_000,
            RecordingVideoQualityBudgetPolicy.resolveTargetBitrateBps(
                request = request.copy(codec = GpuVideoCodec.HEVC)
            )
        )
        assertEquals(
            1_600_000_000,
            RecordingVideoQualityBudgetPolicy.resolveTargetBitrateBps(
                request = request.copy(codec = GpuVideoCodec.AV1)
            )
        )
        assertEquals(
            1_000_000_000,
            RecordingVideoQualityBudgetPolicy.resolveTargetBitrateBps(
                request = request.copy(codec = GpuVideoCodec.AVC)
            )
        )
    }

    @Test
    fun eliteBudgetRaisesLowResolutionPerceptualDetailHeadroomOutsideStrictCeiling() = withQualityFlags(
        enabled = false,
        strict = false
    ) {
        val budget = RecordingVideoQualityBudgetPolicy.resolveTargetBitrateBps(
            request = request(
                width = 1280,
                height = 720,
                fps = 30,
                codec = GpuVideoCodec.HEVC,
                hdrMode = "OFF",
                logEnabled = false,
                tenBit = false,
                colorSpace = "Rec.709"
            )
        )

        assertTrue("budget=$budget", budget >= 90_000_000)
        assertTrue("budget=$budget", budget < RecordingCodecCatalog.maxBitrateMbps(GpuVideoCodec.HEVC) * 1_000_000)
    }

    @Test
    fun extremeDetailRaisesFourKHevcBudgetOutsideStrictCeiling() = withQualityFlags(
        enabled = false,
        strict = false
    ) {
        val codecCeiling = RecordingCodecCatalog.maxBitrateMbps(GpuVideoCodec.HEVC) * 1_000_000
        val unset = RecordingVideoQualityBudgetPolicy.resolveTargetBitrateBps(
            request(
                width = 3840,
                height = 2160,
                fps = 30,
                codec = GpuVideoCodec.HEVC
            )
        )
        val focused = RecordingVideoQualityBudgetPolicy.resolveTargetBitrateBps(
            request(
                width = 3840,
                height = 2160,
                fps = 30,
                codec = GpuVideoCodec.HEVC,
                videoDetailProfile = VideoDetailProfile.FOCUSED_DETAIL
            )
        )
        val extreme = RecordingVideoQualityBudgetPolicy.resolveTargetBitrateBps(
            request(
                width = 3840,
                height = 2160,
                fps = 30,
                codec = GpuVideoCodec.HEVC,
                videoDetailProfile = VideoDetailProfile.EXTREME_DETAIL
            )
        )

        assertTrue("unset=$unset extreme=$extreme", extreme > unset)
        assertTrue("focused=$focused extreme=$extreme", extreme > focused)
        assertTrue("extreme=$extreme ceiling=$codecCeiling", extreme <= codecCeiling)
    }

    @Test
    fun v20SourceQualityPressureRaisesBudgetAndExposesMetadataOutsideStrictCeiling() = withQualityFlags(
        enabled = false,
        strict = false
    ) {
        val baseline = RecordingVideoQualityBudgetPolicy.resolveBudgetPlan(
            request(
                width = 1280,
                height = 720,
                fps = 30,
                codec = GpuVideoCodec.HEVC
            )
        )
        val pressured = RecordingVideoQualityBudgetPolicy.resolveBudgetPlan(
            request(
                width = 1280,
                height = 720,
                fps = 30,
                codec = GpuVideoCodec.HEVC,
                videoDetailProfile = VideoDetailProfile.EXTREME_DETAIL,
                motionRisk = 0.84f,
                noiseRisk = 0.78f,
                antiMotionBlurEnabled = true,
                sourceQualityBudgetMultiplier = 1.18,
                sourceQualityTier = "MOTION_STABLE_LOW_NOISE_DETAIL"
            )
        )

        assertTrue("baseline=$baseline pressured=$pressured", pressured.targetBitrateBps > baseline.targetBitrateBps)
        assertTrue(pressured.qualityValue > baseline.qualityValue)
        assertEquals(
            RecordingVideoQualitySourcePressureTier.MOTION_STABLE_HIGH_NOISE_DETAIL,
            pressured.sourceQualityPressureTier
        )
        assertTrue(pressured.highNoiseBudgetApplied)
        assertTrue(pressured.highDetailBudgetApplied)
        assertTrue(pressured.motionBudgetApplied)
        assertTrue(pressured.antiMotionBlurBudgetApplied)
        assertTrue(pressured.bitratePressureMultiplier > 1.0)
        assertTrue(pressured.sourceQualityPressureFloorBps >= baseline.targetBitrateBps)
        assertEquals("true", pressured.metadata["v20RecordingQualityBudgetPolicy"])
        assertEquals("true", pressured.metadata["v20AntiMotionBlurBudgetApplied"])
        assertEquals("MOTION_STABLE_LOW_NOISE_DETAIL", pressured.metadata["v20SourceQualityRequestTier"])
    }

    @Test
    fun v20StrictHighEndKeepsCodecCeilingWhileReportingPressureMetadata() = withStrictHighEnd {
        val hevcCeiling = RecordingCodecCatalog.maxBitrateMbps(GpuVideoCodec.HEVC) * 1_000_000
        val plan = RecordingVideoQualityBudgetPolicy.resolveBudgetPlan(
            request(
                width = 1920,
                height = 1080,
                fps = 60,
                codec = GpuVideoCodec.HEVC,
                videoDetailProfile = VideoDetailProfile.EXTREME_DETAIL,
                motionRisk = 1f,
                noiseRisk = 1f,
                antiMotionBlurEnabled = true,
                sourceQualityBudgetMultiplier = 1.35,
                sourceQualityTier = "MOTION_STABLE_LOW_NOISE_DETAIL"
            )
        )

        assertEquals(hevcCeiling, plan.targetBitrateBps)
        assertEquals(100, plan.qualityValue)
        assertTrue(plan.bitratePressureMultiplier > 1.0)
        assertTrue(plan.highNoiseBudgetApplied)
        assertTrue(plan.highDetailBudgetApplied)
        assertTrue(plan.motionBudgetApplied)
        assertTrue(plan.antiMotionBlurBudgetApplied)
        assertEquals("100", plan.metadata["v20QualityValue"])
    }

    @Test
    fun v20DefaultRequestDoesNotApplySourceQualityPressure() = withQualityFlags(
        enabled = false,
        strict = false
    ) {
        val plan = RecordingVideoQualityBudgetPolicy.resolveBudgetPlan(
            request(
                width = 1280,
                height = 720,
                fps = 30,
                codec = GpuVideoCodec.HEVC
            )
        )

        assertEquals(88, plan.qualityValue)
        assertEquals(RecordingVideoQualitySourcePressureTier.STANDARD, plan.sourceQualityPressureTier)
        assertEquals(1.0, plan.bitratePressureMultiplier, 0.0)
        assertFalse(plan.highNoiseBudgetApplied)
        assertFalse(plan.highDetailBudgetApplied)
        assertFalse(plan.motionBudgetApplied)
        assertFalse(plan.antiMotionBlurBudgetApplied)
    }

    @Test
    fun eliteBudgetKeepsNeoProCamXHdrAndLogProfilesDistinctOutsideStrictCeiling() = withQualityFlags(
        enabled = false,
        strict = false
    ) {
        val hlg = RecordingVideoQualityBudgetPolicy.resolveTargetBitrateBps(
            request(
                width = 1920,
                height = 1080,
                fps = 30,
                hdrMode = "HLG",
                tenBit = true,
                colorSpace = "Rec.2020"
            )
        )
        val hdr10Plus = RecordingVideoQualityBudgetPolicy.resolveTargetBitrateBps(
            request(
                width = 1920,
                height = 1080,
                fps = 30,
                hdrMode = "HDR10+",
                tenBit = true,
                colorSpace = "Rec.2020"
            )
        )
        val ndr10Plus = RecordingVideoQualityBudgetPolicy.resolveTargetBitrateBps(
            request(
                width = 1920,
                height = 1080,
                fps = 30,
                hdrMode = "NDR10+",
                tenBit = true,
                colorSpace = "Rec.2020-NDR10+"
            )
        )
        val nivukx = RecordingVideoQualityBudgetPolicy.resolveTargetBitrateBps(
            request(
                width = 1920,
                height = 1080,
                fps = 30,
                hdrMode = "NIVUKX Vision",
                tenBit = true,
                colorSpace = "Rec.2020-NIVUKX"
            )
        )
        val nLogBase = RecordingVideoQualityBudgetPolicy.resolveTargetBitrateBps(
            request(
                width = 1920,
                height = 1080,
                fps = 30,
                logEnabled = true,
                logProfile = "N-Log Base",
                tenBit = true,
                colorSpace = "Rec.2020-N-Log"
            )
        )
        val nLogShadow = RecordingVideoQualityBudgetPolicy.resolveTargetBitrateBps(
            request(
                width = 1920,
                height = 1080,
                fps = 30,
                logEnabled = true,
                logProfile = "N-Log Shadow",
                tenBit = true,
                colorSpace = "Rec.2020-N-Log"
            )
        )
        val nLogHighlight = RecordingVideoQualityBudgetPolicy.resolveTargetBitrateBps(
            request(
                width = 1920,
                height = 1080,
                fps = 30,
                logEnabled = true,
                logProfile = "N-Log Highlight",
                tenBit = true,
                colorSpace = "Rec.2020-N-Log"
            )
        )
        val nLogCinema = RecordingVideoQualityBudgetPolicy.resolveTargetBitrateBps(
            request(
                width = 1920,
                height = 1080,
                fps = 30,
                logEnabled = true,
                logProfile = "N-Log Cinema",
                tenBit = true,
                colorSpace = "Rec.2020-N-Log"
            )
        )

        assertEquals(ndr10Plus, hlg)
        assertEquals(ndr10Plus, hdr10Plus)
        assertTrue(nivukx > ndr10Plus)
        assertTrue(nLogShadow > nLogBase)
        assertTrue(nLogHighlight > nLogShadow)
        assertTrue(nLogCinema > nLogHighlight)
    }

    private fun request(
        width: Int,
        height: Int,
        fps: Int,
        codec: GpuVideoCodec = GpuVideoCodec.HEVC,
        hdrMode: String = "OFF",
        logEnabled: Boolean = false,
        logProfile: String = "N-Log Base",
        tenBit: Boolean = false,
        colorSpace: String = "Rec.709",
        videoDetailProfile: VideoDetailProfile? = null,
        motionRisk: Float = 0f,
        noiseRisk: Float = 0f,
        antiMotionBlurEnabled: Boolean = false,
        sourceQualityBudgetMultiplier: Double = 1.0,
        sourceQualityTier: String = "STANDARD"
    ): RecordingVideoQualityBudgetRequest =
        RecordingVideoQualityBudgetRequest(
            width = width,
            height = height,
            fps = fps,
            codec = codec,
            hdrMode = hdrMode,
            logEnabled = logEnabled,
            logProfile = logProfile,
            tenBit = tenBit,
            colorSpace = colorSpace,
            videoDetailProfile = videoDetailProfile,
            motionRisk = motionRisk,
            noiseRisk = noiseRisk,
            antiMotionBlurEnabled = antiMotionBlurEnabled,
            sourceQualityBudgetMultiplier = sourceQualityBudgetMultiplier,
            sourceQualityTier = sourceQualityTier
        )

    private fun qualityCase(
        width: Int,
        height: Int,
        fps: Int
    ): QualityCase = QualityCase(width, height, fps)

    private data class QualityCase(
        val width: Int,
        val height: Int,
        val fps: Int
    )

    private inline fun withStrictHighEnd(block: () -> Unit) {
        withQualityFlags(enabled = true, strict = true, block = block)
    }

    private inline fun withQualityFlags(enabled: Boolean, strict: Boolean, block: () -> Unit) {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.enabled = enabled
        HighEndQualityPolicy.strictHighEndOnly = strict
        try {
            block()
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }
}
