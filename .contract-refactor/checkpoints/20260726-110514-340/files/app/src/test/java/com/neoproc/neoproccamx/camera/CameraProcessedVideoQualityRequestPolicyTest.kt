package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CaptureRequest
import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CameraProcessedVideoQualityRequestPolicyTest {

    @Test
    fun `strict processed video requests maximum supported ISP quality stack`() {
        val plan = CameraProcessedVideoQualityRequestPolicy.resolve(
            strictHighEndEnabled = true,
            rawVideoCaptureEnabled = false,
            availability = availability(
                edgeModes = listOf(CaptureRequest.EDGE_MODE_FAST, CaptureRequest.EDGE_MODE_HIGH_QUALITY),
                noiseReductionModes = listOf(
                    CaptureRequest.NOISE_REDUCTION_MODE_FAST,
                    CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
                ),
                hotPixelModes = listOf(CaptureRequest.HOT_PIXEL_MODE_FAST, CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY),
                shadingModes = listOf(CaptureRequest.SHADING_MODE_FAST, CaptureRequest.SHADING_MODE_HIGH_QUALITY),
                tonemapModes = listOf(CaptureRequest.TONEMAP_MODE_FAST, CaptureRequest.TONEMAP_MODE_HIGH_QUALITY),
                colorAberrationModes = listOf(
                    CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_FAST,
                    CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY
                ),
                distortionCorrectionModes = listOf(
                    CaptureRequest.DISTORTION_CORRECTION_MODE_FAST,
                    CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY
                )
            )
        )

        requireNotNull(plan)
        assertEquals(CaptureRequest.EDGE_MODE_HIGH_QUALITY, plan.edgeMode)
        assertEquals(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY, plan.noiseReductionMode)
        assertEquals(CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY, plan.hotPixelMode)
        assertEquals(CaptureRequest.SHADING_MODE_HIGH_QUALITY, plan.shadingMode)
        assertEquals(CaptureRequest.TONEMAP_MODE_HIGH_QUALITY, plan.tonemapMode)
        assertEquals(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY, plan.aberrationMode)
        assertEquals(CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY, plan.distortionCorrectionMode)
    }

    @Test
    fun `strict processed video leaves fast-only modes unset`() {
        val plan = CameraProcessedVideoQualityRequestPolicy.resolve(
            strictHighEndEnabled = true,
            rawVideoCaptureEnabled = false,
            availability = availability(
                edgeModes = listOf(CaptureRequest.EDGE_MODE_FAST),
                noiseReductionModes = listOf(CaptureRequest.NOISE_REDUCTION_MODE_FAST),
                hotPixelModes = listOf(CaptureRequest.HOT_PIXEL_MODE_FAST),
                shadingModes = listOf(CaptureRequest.SHADING_MODE_FAST),
                tonemapModes = listOf(CaptureRequest.TONEMAP_MODE_FAST),
                colorAberrationModes = listOf(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_FAST),
                distortionCorrectionModes = listOf(CaptureRequest.DISTORTION_CORRECTION_MODE_FAST)
            )
        )

        requireNotNull(plan)
        assertNull(plan.edgeMode)
        assertNull(plan.noiseReductionMode)
        assertNull(plan.hotPixelMode)
        assertNull(plan.shadingMode)
        assertNull(plan.tonemapMode)
        assertNull(plan.aberrationMode)
        assertNull(plan.distortionCorrectionMode)
    }

    @Test
    fun `strict processed video assumes high quality when mode metadata is missing`() {
        val plan = CameraProcessedVideoQualityRequestPolicy.resolve(
            strictHighEndEnabled = true,
            rawVideoCaptureEnabled = false,
            availability = availability(
                edgeModes = emptyList(),
                noiseReductionModes = emptyList(),
                hotPixelModes = emptyList(),
                shadingModes = emptyList(),
                tonemapModes = emptyList(),
                colorAberrationModes = emptyList(),
                distortionCorrectionModes = emptyList()
            )
        )

        requireNotNull(plan)
        assertEquals(CaptureRequest.EDGE_MODE_HIGH_QUALITY, plan.edgeMode)
        assertEquals(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY, plan.noiseReductionMode)
        assertEquals(CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY, plan.hotPixelMode)
        assertEquals(CaptureRequest.SHADING_MODE_HIGH_QUALITY, plan.shadingMode)
        assertEquals(CaptureRequest.TONEMAP_MODE_HIGH_QUALITY, plan.tonemapMode)
        assertEquals(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY, plan.aberrationMode)
        assertEquals(CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY, plan.distortionCorrectionMode)
    }

    @Test
    fun `strict processed video does not fall back to off for explicit low quality lists`() {
        val plan = CameraProcessedVideoQualityRequestPolicy.resolve(
            strictHighEndEnabled = true,
            rawVideoCaptureEnabled = false,
            availability = availability(
                edgeModes = listOf(CaptureRequest.EDGE_MODE_OFF),
                noiseReductionModes = listOf(CaptureRequest.NOISE_REDUCTION_MODE_OFF),
                hotPixelModes = listOf(CaptureRequest.HOT_PIXEL_MODE_OFF),
                shadingModes = listOf(CaptureRequest.SHADING_MODE_OFF),
                tonemapModes = emptyList(),
                colorAberrationModes = listOf(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF),
                distortionCorrectionModes = listOf(CaptureRequest.DISTORTION_CORRECTION_MODE_OFF)
            )
        )

        requireNotNull(plan)
        assertNull(plan.edgeMode)
        assertNull(plan.noiseReductionMode)
        assertNull(plan.hotPixelMode)
        assertNull(plan.shadingMode)
        assertEquals(CaptureRequest.TONEMAP_MODE_HIGH_QUALITY, plan.tonemapMode)
        assertNull(plan.aberrationMode)
        assertNull(plan.distortionCorrectionMode)
    }

    @Test
    fun `raw video is not modified by processed video quality policy`() {
        assertNull(
            CameraProcessedVideoQualityRequestPolicy.resolve(
                strictHighEndEnabled = true,
                rawVideoCaptureEnabled = true,
                availability = null
            )
        )
    }

    @Test
    fun `strict cinema video gets final high quality ISP cleanup pass`() {
        val plan = CameraProcessedVideoQualityRequestPolicy.resolve(
            strictHighEndEnabled = true,
            rawVideoCaptureEnabled = false,
            consumerAutoVideo = false,
            availability = null
        )

        requireNotNull(plan)
        assertEquals(CaptureRequest.EDGE_MODE_HIGH_QUALITY, plan.edgeMode)
        assertEquals(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY, plan.noiseReductionMode)
        assertEquals(CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY, plan.hotPixelMode)
        assertEquals(CaptureRequest.SHADING_MODE_HIGH_QUALITY, plan.shadingMode)
        assertEquals(CaptureRequest.TONEMAP_MODE_HIGH_QUALITY, plan.tonemapMode)
        assertEquals(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY, plan.aberrationMode)
        assertEquals(CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY, plan.distortionCorrectionMode)
    }

    @Test
    fun `non strict cinema video leaves the encoded cleanup pass to the hal profile`() {
        assertNull(
            CameraProcessedVideoQualityRequestPolicy.resolve(
                strictHighEndEnabled = false,
                rawVideoCaptureEnabled = false,
                consumerAutoVideo = false,
                availability = null
            )
        )
    }

    @Test
    fun `non raw processed video always requests maximum ISP quality stack`() {
        val plan = CameraProcessedVideoQualityRequestPolicy.resolve(
            strictHighEndEnabled = false,
            rawVideoCaptureEnabled = false,
            availability = null
        )

        requireNotNull(plan)
        assertEquals(CaptureRequest.EDGE_MODE_HIGH_QUALITY, plan.edgeMode)
        assertEquals(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY, plan.noiseReductionMode)
        assertEquals(CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY, plan.hotPixelMode)
        assertEquals(CaptureRequest.SHADING_MODE_HIGH_QUALITY, plan.shadingMode)
        assertEquals(CaptureRequest.TONEMAP_MODE_HIGH_QUALITY, plan.tonemapMode)
        assertEquals(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY, plan.aberrationMode)
        assertEquals(CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY, plan.distortionCorrectionMode)
    }

    @Test
    fun `v20 high motion processed video exposes frame stable short exposure request metadata`() {
        val plan = CameraProcessedVideoQualityRequestPolicy.resolve(
            strictHighEndEnabled = true,
            rawVideoCaptureEnabled = false,
            availability = null,
            requestedFps = 60,
            availableAeFpsRanges = listOf(
                CameraIntRange(30, 60),
                CameraIntRange(60, 60)
            ),
            exposureRange = CameraLongRange(100_000L, 100_000_000L),
            motionRisk = 0.90f,
            noiseRisk = 0.80f,
            videoDetailProfile = VideoDetailProfile.EXTREME_DETAIL
        )

        requireNotNull(plan)
        assertEquals("V20", plan.requestPolicyVersion)
        assertEquals(
            CameraProcessedVideoSourceQualityTier.MOTION_STABLE_LOW_NOISE_DETAIL,
            plan.sourceQualityTier
        )
        assertTrue(plan.antiMotionBlurEnabled)
        assertEquals(60, plan.motionStableFps)
        assertEquals(CameraIntRange(60, 60), plan.motionStableAeTargetFpsRange)
        assertEquals(16_666_666L, plan.motionStableFrameDurationNs)
        assertEquals(4_166_666L, plan.maxExposureTimeNs)
        assertTrue(plan.sourceQualityBudgetMultiplier > 1.40)

        val summary = plan.toEvidenceSummary()
        assertTrue(summary.contains("requestPolicyVersion=V20"))
        assertTrue(summary.contains("sourceQualityTier=MOTION_STABLE_LOW_NOISE_DETAIL"))
        assertTrue(summary.contains("motionStableAeTargetFpsRange=60-60"))
        assertTrue(summary.contains("maxExposureTimeNs=4166666"))
    }

    @Test
    fun `v20 low motion processed video leaves anti blur request metadata unset`() {
        val plan = CameraProcessedVideoQualityRequestPolicy.resolve(
            strictHighEndEnabled = false,
            rawVideoCaptureEnabled = false,
            availability = null,
            requestedFps = 60,
            availableAeFpsRanges = listOf(CameraIntRange(60, 60)),
            exposureRange = CameraLongRange(100_000L, 100_000_000L),
            motionRisk = 0.20f,
            noiseRisk = 0.10f,
            videoDetailProfile = VideoDetailProfile.NATURAL_DETAIL
        )

        requireNotNull(plan)
        assertEquals(CameraProcessedVideoSourceQualityTier.STANDARD, plan.sourceQualityTier)
        assertFalse(plan.antiMotionBlurEnabled)
        assertNull(plan.motionStableFps)
        assertNull(plan.motionStableAeTargetFpsRange)
        assertNull(plan.motionStableFrameDurationNs)
        assertNull(plan.maxExposureTimeNs)
        assertEquals(1.0, plan.sourceQualityBudgetMultiplier, 0.0)
    }

    @Test
    fun `v20 high noise low motion pins full 180 degree shutter to lower iso`() {
        val plan = CameraProcessedVideoQualityRequestPolicy.resolve(
            strictHighEndEnabled = true,
            rawVideoCaptureEnabled = false,
            availability = null,
            requestedFps = 60,
            availableAeFpsRanges = listOf(CameraIntRange(60, 60)),
            exposureRange = CameraLongRange(100_000L, 100_000_000L),
            motionRisk = 0.20f,
            noiseRisk = 0.80f,
            videoDetailProfile = VideoDetailProfile.NATURAL_DETAIL
        )

        requireNotNull(plan)
        // High noise, no motion -> LOW_NOISE_DETAIL, no anti-motion-blur, no fps pin, but a full
        // 180-degree shutter cap (16_666_666 / 2 = 8_333_333 ns) so AE prefers time over gain.
        assertEquals(CameraProcessedVideoSourceQualityTier.LOW_NOISE_DETAIL, plan.sourceQualityTier)
        assertFalse(plan.antiMotionBlurEnabled)
        assertNull(plan.motionStableFps)
        assertNull(plan.motionStableAeTargetFpsRange)
        assertNull(plan.motionStableFrameDurationNs)
        assertEquals(8_333_333L, plan.maxExposureTimeNs)
    }

    @Test
    fun `encoded fallback is wired through real camera request and certificate evidence path`() {
        val sourceRoot = listOf(
            File("src/main/java/com/neoproc/neoproccamx"),
            File("app/src/main/java/com/neoproc/neoproccamx")
        ).first { it.isDirectory }
        val controller = File(sourceRoot, "camera/CameraController.kt").readText()
        val host = File(sourceRoot, "camera/CameraControllerHost.kt").readText()
        val viewModel = File(sourceRoot, "ui/CameraViewModel.kt").readText()

        assertTrue(controller.contains("CameraProcessedVideoQualityRequestPolicy.resolve"))
        assertTrue(controller.contains("CameraExtremeDetailFallbackExposurePolicy.resolve"))
        assertTrue(controller.contains("selectedVideoDetailProfile == VideoDetailProfile.EXTREME_DETAIL"))
        assertTrue(controller.contains("maybeRefreshExtremeDetailFallbackExposure"))
        assertTrue(controller.contains("Extreme Detail fallback exposure metered refresh"))
        assertTrue(controller.contains("processedVideoQualityEvidence"))
        assertTrue(host.contains("fun processedVideoQualityEvidence"))
        assertTrue(host.contains("fun setVideoDetailProfile"))
        assertTrue(host.contains("fun setProcessedVideoSceneQuality"))
        assertTrue(controller.contains("processedVideoSceneRequestTier"))
        assertTrue(controller.contains("previousRequestTier != nextRequestTier"))
        assertTrue(controller.contains("v20AntiMotionBlurApplicationStatus"))
        assertTrue(viewModel.contains("camera.setVideoDetailProfile"))
        assertTrue(viewModel.contains("camera.setProcessedVideoSceneQuality"))
        assertTrue(viewModel.contains("camera.processedVideoQualityEvidence()"))
        assertTrue(viewModel.contains("CAMERA2_ISP_ENCODED_FALLBACK"))
    }

    @Test
    fun `gpu owned encoder feed selects sensor native distortion off only when advertised`() {
        val available = CameraGpuOwnedGeometryPolicy.resolveDistortionCorrectionMode(
            appOwnedGpuProcessingEnabled = true,
            availability = availability(
                edgeModes = emptyList(),
                noiseReductionModes = emptyList(),
                hotPixelModes = emptyList(),
                shadingModes = emptyList(),
                tonemapModes = emptyList(),
                colorAberrationModes = emptyList(),
                distortionCorrectionModes = listOf(
                    CaptureRequest.DISTORTION_CORRECTION_MODE_OFF,
                    CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY
                )
            )
        )
        val unavailable = CameraGpuOwnedGeometryPolicy.resolveDistortionCorrectionMode(
            appOwnedGpuProcessingEnabled = true,
            availability = availability(
                edgeModes = emptyList(),
                noiseReductionModes = emptyList(),
                hotPixelModes = emptyList(),
                shadingModes = emptyList(),
                tonemapModes = emptyList(),
                colorAberrationModes = emptyList(),
                distortionCorrectionModes = listOf(CaptureRequest.DISTORTION_CORRECTION_MODE_FAST)
            )
        )

        assertEquals(CaptureRequest.DISTORTION_CORRECTION_MODE_OFF, available)
        assertNull(unavailable)
        assertFalse(CameraGpuOwnedGeometryPolicy.isDistortionCorrectionOffConfirmed(false, available))
        assertTrue(CameraGpuOwnedGeometryPolicy.isDistortionCorrectionOffConfirmed(true, available))
    }

    @Test
    fun `strict high end gpu owned video does not force sensor native distortion off`() {
        val mode = CameraGpuOwnedGeometryPolicy.resolveDistortionCorrectionMode(
            appOwnedGpuProcessingEnabled = true,
            strictHighEndEnabled = true,
            availability = availability(
                edgeModes = emptyList(),
                noiseReductionModes = emptyList(),
                hotPixelModes = emptyList(),
                shadingModes = emptyList(),
                tonemapModes = emptyList(),
                colorAberrationModes = emptyList(),
                distortionCorrectionModes = listOf(
                    CaptureRequest.DISTORTION_CORRECTION_MODE_OFF,
                    CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY
                )
            )
        )

        assertNull(mode)
        assertFalse(CameraGpuOwnedGeometryPolicy.isDistortionCorrectionOffConfirmed(true, mode))
    }

    @Test
    fun `gpu owned sensor geometry request is wired into camera request evidence`() {
        val sourceRoot = listOf(
            File("src/main/java/com/neoproc/neoproccamx"),
            File("app/src/main/java/com/neoproc/neoproccamx")
        ).first { it.isDirectory }
        val controller = File(sourceRoot, "camera/CameraController.kt").readText()

        assertTrue(controller.contains("CameraGpuOwnedGeometryPolicy.resolveDistortionCorrectionMode"))
        assertTrue(controller.contains("gpuOwnedDistortionCorrectionOff"))
    }

    @Test
    fun `explicit noise reduction off requests noise and hot pixel off under strict`() {
        val plan = CameraProcessedVideoQualityRequestPolicy.resolve(
            strictHighEndEnabled = true,
            rawVideoCaptureEnabled = false,
            availability = availability(
                edgeModes = listOf(CaptureRequest.EDGE_MODE_HIGH_QUALITY),
                noiseReductionModes = listOf(
                    CaptureRequest.NOISE_REDUCTION_MODE_OFF,
                    CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
                ),
                hotPixelModes = listOf(
                    CaptureRequest.HOT_PIXEL_MODE_OFF,
                    CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY
                ),
                shadingModes = listOf(CaptureRequest.SHADING_MODE_HIGH_QUALITY),
                tonemapModes = listOf(CaptureRequest.TONEMAP_MODE_HIGH_QUALITY),
                colorAberrationModes = listOf(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY),
                distortionCorrectionModes = listOf(CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY)
            ),
            noiseReductionEnabled = false
        )

        requireNotNull(plan)
        assertEquals(CaptureRequest.NOISE_REDUCTION_MODE_OFF, plan.noiseReductionMode)
        assertEquals(CaptureRequest.HOT_PIXEL_MODE_OFF, plan.hotPixelMode)
        // Only NR/hot-pixel follow the toggle; the rest of the quality stack is untouched.
        assertEquals(CaptureRequest.EDGE_MODE_HIGH_QUALITY, plan.edgeMode)
        assertEquals(CaptureRequest.SHADING_MODE_HIGH_QUALITY, plan.shadingMode)
    }

    @Test
    fun `noise reduction on keeps high quality under strict (no downgrade default)`() {
        val plan = CameraProcessedVideoQualityRequestPolicy.resolve(
            strictHighEndEnabled = true,
            rawVideoCaptureEnabled = false,
            availability = availability(
                edgeModes = listOf(CaptureRequest.EDGE_MODE_HIGH_QUALITY),
                noiseReductionModes = listOf(
                    CaptureRequest.NOISE_REDUCTION_MODE_OFF,
                    CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
                ),
                hotPixelModes = listOf(
                    CaptureRequest.HOT_PIXEL_MODE_OFF,
                    CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY
                ),
                shadingModes = listOf(CaptureRequest.SHADING_MODE_HIGH_QUALITY),
                tonemapModes = listOf(CaptureRequest.TONEMAP_MODE_HIGH_QUALITY),
                colorAberrationModes = listOf(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY),
                distortionCorrectionModes = listOf(CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY)
            ),
            noiseReductionEnabled = true
        )

        requireNotNull(plan)
        assertEquals(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY, plan.noiseReductionMode)
        assertEquals(CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY, plan.hotPixelMode)
    }

    private fun availability(
        edgeModes: List<Int>,
        noiseReductionModes: List<Int>,
        hotPixelModes: List<Int>,
        shadingModes: List<Int>,
        tonemapModes: List<Int>,
        colorAberrationModes: List<Int>,
        distortionCorrectionModes: List<Int>
    ): IspBypassAvailability =
        IspBypassAvailability(
            tonemapModes = tonemapModes,
            edgeModes = edgeModes,
            noiseReductionModes = noiseReductionModes,
            hotPixelModes = hotPixelModes,
            shadingModes = shadingModes,
            colorAberrationModes = colorAberrationModes,
            distortionCorrectionModes = distortionCorrectionModes
        )
}
