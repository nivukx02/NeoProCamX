package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.camera.DualGainHdrFrameMetadata
import com.neoproc.neoproccamx.camera.DualGainHdrFusionPolicy
import com.neoproc.neoproccamx.camera.DualGainHdrFusionRequest
import com.neoproc.neoproccamx.rendering.GpuActiveTransport
import com.neoproc.neoproccamx.rendering.GpuImageEnhancementUniforms
import com.neoproc.neoproccamx.rendering.GpuSignalFamily
import com.neoproc.neoproccamx.rendering.VulkanBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DualGainFusionRenderIntegrationTest {
    private val engine = SceneRenderProfileEngine()

    @Test
    fun publicDualGainPlanReachesSceneProfileWithoutExternalHdrOrVendorClaim() {
        val plan = publicDualGainPlan()

        val profile = engine.build(
            scene = scene(),
            recommendation = recommendation(),
            logEnabled = false,
            hdrEnabled = false,
            colorProfile = "Rec.2020",
            hdrMode = "OFF",
            recording = true,
            previewFps = 30f,
            dualGainFusionPlan = plan
        )

        assertTrue(profile.dualGainFusionEnabled)
        assertTrue(profile.dualGainMotionMaskEnabled)
        assertEquals(4.0f, profile.dualGainKGain, 0.0001f)
        assertEquals(0.018f, profile.dualGainNoiseFloor, 0.0001f)
        assertEquals(0.94f, profile.dualGainSaturationThreshold, 0.0001f)
        assertTrue(profile.dualGainFusionSignature.contains("PUBLIC_CAMERA2_DUAL_GAIN"))
        assertTrue(profile.dualGainFusionSignature.contains("ACESCG_LINEAR"))
        listOf("Dolby", "HDR10+", "Sony", "OmniVision", "ARRI", "RED").forEach { forbidden ->
            assertFalse(
                "Forbidden external/vendor label leaked: $forbidden",
                profile.dualGainFusionSignature.contains(forbidden, ignoreCase = true)
            )
        }
    }

    @Test
    fun publicDualGainPlanReachesGpuUniformPacketForPreviewRecordingAndNpv10Parity() {
        val profile = engine.build(
            scene = scene(),
            recommendation = recommendation(),
            logEnabled = false,
            hdrEnabled = false,
            colorProfile = "Rec.2020",
            hdrMode = "OFF",
            recording = true,
            previewFps = 30f,
            dualGainFusionPlan = publicDualGainPlan()
        )

        val uniforms = GpuImageEnhancementUniforms.fromSceneProfile(
            profile = profile,
            signalFamily = GpuSignalFamily.SDR,
            colorSpaceName = "Rec.2020",
            colorScienceName = "Natural",
            activeTransport = GpuActiveTransport.BT2020_SDR
        )
        val packed = uniforms.toFloatArray()

        assertEquals(VulkanBridge.ENHANCEMENT_UNIFORM_FLOAT_COUNT, packed.size)
        assertEquals(1f, packed[32], 0.0001f)
        assertEquals(1f, packed[33], 0.0001f)
        assertEquals(4.0f / 64f, packed[34], 0.0001f)
        assertEquals(0.018f / 0.20f, packed[35], 0.0001f)
        assertEquals((0.94f - 0.72f) / (0.995f - 0.72f), packed[36], 0.0001f)
        assertEquals(500f / DualGainHdrFusionPolicy.MAX_TIMESTAMP_SKEW_NS.toFloat(), packed[37], 0.0001f)
        assertTrue(uniforms.signature.contains("dualGain=PUBLIC_CAMERA2_DUAL_GAIN"))
        assertTrue(uniforms.signature.contains("externalProfile=false"))
    }

    private fun publicDualGainPlan() =
        DualGainHdrFusionPolicy.resolve(
            DualGainHdrFusionRequest(
                enabled = true,
                publicSynchronizedDualGainAvailable = true,
                privateExtensionRequested = false,
                lcgFrame = DualGainHdrFrameMetadata(
                    timestampNs = 10_000_000L,
                    width = 3840,
                    height = 2160,
                    format = "P010",
                    exposureTimeNs = 4_000_000L,
                    analogGain = 1.0f,
                    hardwareBufferBacked = true
                ),
                hcgFrame = DualGainHdrFrameMetadata(
                    timestampNs = 10_000_500L,
                    width = 3840,
                    height = 2160,
                    format = "P010",
                    exposureTimeNs = 16_000_000L,
                    analogGain = 4.0f,
                    hardwareBufferBacked = true
                ),
                kGain = 4.0f,
                noiseFloor = 0.018f,
                saturationThreshold = 0.94f,
                existingHdrMergeFallbackAvailable = true
            )
        )

    private fun scene() = SceneAnalysis(
        averageLuma = 0.42f,
        estimatedLux = 88f,
        motionScore = 0.04f,
        edgeDensity = 0.20f,
        dynamicRangeScore = 0.72f,
        shadowRatio = 0.18f,
        highlightRatio = 0.12f,
        timestamp = 0L,
        normalizedNoiseFloor = 0.08f,
        flatRegionStdDev = 2.0f,
        realIsoNoiseFloor = 320f,
        perceptualSharpness = 0.70f
    )

    private fun recommendation() = CaptureRecommendation(
        scene = scene(),
        isoCeiling = 640,
        targetExposureNs = 16_666_667L,
        stackFrameCount = 1,
        temporalNoiseReduction = true,
        hdrBracketEvs = emptyList(),
        sharpeningBoost = 0.08f,
        localToneMapStrength = 0.42f,
        shadowDenoiseStrength = 0.32f,
        spatialDenoiseFloor = 0.28f,
        detailPreservationStrength = 0.82f,
        skyProtectionStrength = 0.36f
    )
}
