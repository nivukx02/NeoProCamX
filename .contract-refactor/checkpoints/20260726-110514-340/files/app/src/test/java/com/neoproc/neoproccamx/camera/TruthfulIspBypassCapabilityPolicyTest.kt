package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TruthfulIspBypassCapabilityPolicyTest {

    @Test
    fun `raw below full sensor is limited and full sensor requires vendor`() {
        val result = TruthfulIspBypassCapabilityPolicy.resolve(
            capability = capability(
                pixelArraySize = CameraStreamSize(8192, 6144),
                rawOutputSizes = listOf(CameraStreamSize(4096, 3072)),
                yuvOutputSizes = listOf(CameraStreamSize(4096, 3072)),
                encoderOutputSizes = listOf(CameraStreamSize(4096, 3072))
            ),
            requestPlan = completeRequestPlan()
        )

        assertEquals(BypassCapabilityScore.SENSOR_RAW_LIMITED_ISP, result.raw)
        assertEquals(BypassCapabilityScore.PARTIAL_VIDEO_BYPASS, result.video)
        assertEquals(BypassCapabilityScore.VENDOR_REQUIRED, result.fullSensor)
        assertTrue(result.details.any { it.contains("RAW_SENSOR 4096x3072 is below active sensor 8192x6144") })
    }

    @Test
    fun `full sensor raw with complete request plan is true original raw`() {
        val result = TruthfulIspBypassCapabilityPolicy.resolve(
            capability = capability(
                pixelArraySize = CameraStreamSize(8192, 6144),
                rawOutputSizes = listOf(CameraStreamSize(8192, 6144)),
                yuvOutputSizes = listOf(CameraStreamSize(4096, 3072)),
                encoderOutputSizes = listOf(CameraStreamSize(4096, 3072))
            ),
            requestPlan = completeRequestPlan()
        )

        assertEquals(BypassCapabilityScore.TRUE_ORIGINAL_RAW, result.raw)
        assertEquals(BypassCapabilityScore.PARTIAL_VIDEO_BYPASS, result.video)
        assertEquals(BypassCapabilityScore.TRUE_ORIGINAL_RAW, result.fullSensor)
        assertTrue(result.details.any { it.contains("true original RAW_SENSOR path") })
    }

    @Test
    fun `full sensor raw with incomplete request keys is limited not true original`() {
        val result = TruthfulIspBypassCapabilityPolicy.resolve(
            capability = capability(
                pixelArraySize = CameraStreamSize(8192, 6144),
                rawOutputSizes = listOf(CameraStreamSize(8192, 6144)),
                yuvOutputSizes = listOf(CameraStreamSize(8192, 6144)),
                encoderOutputSizes = listOf(CameraStreamSize(4096, 2160))
            ),
            requestPlan = partialRequestPlan()
        )

        assertEquals(BypassCapabilityScore.SENSOR_RAW_LIMITED_ISP, result.raw)
        assertEquals(BypassCapabilityScore.PARTIAL_VIDEO_BYPASS, result.video)
        assertEquals(BypassCapabilityScore.SENSOR_RAW_LIMITED_ISP, result.fullSensor)
        assertTrue(result.details.any { it.contains("RAW_SENSOR covers full sensor but ISP bypass is incomplete") })
    }

    @Test
    fun `full sensor raw without manual sensor control is limited not true original`() {
        val result = TruthfulIspBypassCapabilityPolicy.resolve(
            capability = capability(
                pixelArraySize = CameraStreamSize(8192, 6144),
                rawOutputSizes = listOf(CameraStreamSize(8192, 6144)),
                yuvOutputSizes = listOf(CameraStreamSize(8192, 6144)),
                encoderOutputSizes = listOf(CameraStreamSize(4096, 2160)),
                supportsManualSensor = false
            ),
            requestPlan = completeRequestPlan()
        )

        assertEquals(BypassCapabilityScore.SENSOR_RAW_LIMITED_ISP, result.raw)
        assertEquals(BypassCapabilityScore.SENSOR_RAW_LIMITED_ISP, result.fullSensor)
        assertTrue(result.details.any { it.contains("manual sensor control is unavailable") })
    }

    @Test
    fun `yuv encoder only path is partial video bypass and raw remains vendor required`() {
        val result = TruthfulIspBypassCapabilityPolicy.resolve(
            capability = capability(
                pixelArraySize = CameraStreamSize(4096, 3072),
                rawOutputSizes = emptyList(),
                yuvOutputSizes = listOf(CameraStreamSize(4096, 3072)),
                encoderOutputSizes = listOf(CameraStreamSize(4096, 3072)),
                supportsRaw = false
            ),
            requestPlan = partialRequestPlan()
        )

        assertEquals(BypassCapabilityScore.VENDOR_REQUIRED, result.raw)
        assertEquals(BypassCapabilityScore.PARTIAL_VIDEO_BYPASS, result.video)
        assertEquals(BypassCapabilityScore.PARTIAL_VIDEO_BYPASS, result.fullSensor)
    }

    private fun capability(
        pixelArraySize: CameraStreamSize,
        activeArraySize: CameraStreamSize = pixelArraySize,
        rawOutputSizes: List<CameraStreamSize>,
        yuvOutputSizes: List<CameraStreamSize>,
        encoderOutputSizes: List<CameraStreamSize>,
        supportsRaw: Boolean = rawOutputSizes.isNotEmpty(),
        supportsManualSensor: Boolean = true,
        supportsIspDisableRequestKeys: Boolean = true,
        supportsCompleteIspBypassRequest: Boolean = true
    ): CameraCapabilityInventory =
        CameraCapabilityInventory(
            cameraId = "0",
            pixelArraySize = pixelArraySize,
            activeArraySize = activeArraySize,
            rawOutputSizes = rawOutputSizes,
            yuvOutputSizes = yuvOutputSizes,
            jpegOutputSizes = emptyList(),
            encoderOutputSizes = encoderOutputSizes,
            supportsRaw = supportsRaw,
            supportsTonemapContrastCurve = true,
            supportsManualSensor = supportsManualSensor,
            supportsIspDisableRequestKeys = supportsIspDisableRequestKeys,
            supportsCompleteIspBypassRequest = supportsCompleteIspBypassRequest
        )

    private fun completeRequestPlan(): IspBypassRequestPlan =
        requestPlan(
            supported = setOf(
                IspBypassFeature.TONEMAP_CONTRAST_CURVE,
                IspBypassFeature.EDGE_OFF,
                IspBypassFeature.NOISE_REDUCTION_OFF,
                IspBypassFeature.HOT_PIXEL_OFF,
                IspBypassFeature.SHADING_OFF,
                IspBypassFeature.COLOR_ABERRATION_OFF,
                IspBypassFeature.DISTORTION_CORRECTION_OFF
            ),
            unsupported = emptyList()
        )

    private fun partialRequestPlan(): IspBypassRequestPlan =
        requestPlan(
            supported = setOf(
                IspBypassFeature.EDGE_OFF,
                IspBypassFeature.NOISE_REDUCTION_OFF,
                IspBypassFeature.HOT_PIXEL_OFF
            ),
            unsupported = listOf("DISTORTION_CORRECTION_MODE_OFF unavailable")
        )

    private fun requestPlan(
        supported: Set<IspBypassFeature>,
        unsupported: List<String>
    ): IspBypassRequestPlan =
        IspBypassRequestPlan(
            supportedFeatures = supported,
            unsupportedReasons = unsupported,
            requiredFeatures = setOf(
                IspBypassFeature.TONEMAP_CONTRAST_CURVE,
                IspBypassFeature.EDGE_OFF,
                IspBypassFeature.NOISE_REDUCTION_OFF,
                IspBypassFeature.HOT_PIXEL_OFF,
                IspBypassFeature.SHADING_OFF,
                IspBypassFeature.COLOR_ABERRATION_OFF,
                IspBypassFeature.DISTORTION_CORRECTION_OFF
            ),
            distortionCorrectionPreference = IspBypassDistortionCorrectionPreference.SENSOR_NATIVE_OFF,
            distortionCorrectionMode = null
        )
}
