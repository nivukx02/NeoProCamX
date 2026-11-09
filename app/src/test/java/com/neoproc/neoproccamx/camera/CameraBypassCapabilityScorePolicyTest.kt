package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CameraBypassCapabilityScorePolicyTest {

    @Test
    fun `reports limited raw, partial video bypass, and vendor required full sensor when exposed streams are smaller`() {
        val report = CameraBypassCapabilityScorePolicy.resolve(
            capability = capability(
                pixelArraySize = CameraStreamSize(8192, 6144),
                rawOutputSizes = listOf(CameraStreamSize(4096, 3072)),
                yuvOutputSizes = listOf(CameraStreamSize(4096, 3072)),
                encoderOutputSizes = listOf(CameraStreamSize(4096, 3072))
            ),
            requestPlan = partialRequestPlan(),
            vendorScanReport = CameraVendorReadoutScanReport(
                scannedKeyCount = 1,
                candidates = listOf(
                    CameraVendorReadoutCandidate(
                        keyName = "com.vendor.hidden.8kSize",
                        size = CameraStreamSize(8192, 6144),
                        status = CameraVendorReadoutCandidateStatus.GATED_PRIVATE,
                        reason = "vendor reported but HAL stream map did not expose safe output"
                    )
                )
            )
        )

        assertEquals(BypassCapabilityScore.SENSOR_RAW_LIMITED_ISP, report.raw)
        assertEquals(BypassCapabilityScore.PARTIAL_VIDEO_BYPASS, report.video)
        assertEquals(BypassCapabilityScore.VENDOR_REQUIRED, report.fullSensor)
        assertTrue(report.ispOffApplied)
        assertEquals("PARTIAL_VIDEO_ISP_OFF", report.ispOffCapability)
        assertTrue(report.displayLabel.contains("RAW SENSOR RAW LIMITED ISP"))
        assertTrue(report.displayLabel.contains("VIDEO PARTIAL VIDEO ISP-OFF"))
        assertTrue(report.displayLabel.contains("FULL SENSOR VENDOR REQUIRED"))
        assertTrue(report.toLogLine().contains("ispOffApplied=true"))
        assertTrue(report.toLogLine().contains("ispOffCapability=PARTIAL_VIDEO_ISP_OFF"))
        assertTrue(report.toLogLine().contains("videoIspOff=PARTIAL VIDEO ISP-OFF"))
        assertTrue(report.toLogLine().contains("sensorReadout=VENDOR REQUIRED"))
        assertTrue(report.details.any { it.contains("8192x6144 requires vendor HAL") })
    }

    @Test
    fun `reports true original full sensor when exposed raw covers pixel array and bypass is complete`() {
        val report = CameraBypassCapabilityScorePolicy.resolve(
            capability = capability(
                pixelArraySize = CameraStreamSize(8192, 6144),
                rawOutputSizes = listOf(CameraStreamSize(8192, 6144)),
                yuvOutputSizes = listOf(CameraStreamSize(8192, 6144)),
                encoderOutputSizes = listOf(CameraStreamSize(7680, 4320))
            ),
            requestPlan = completeRequestPlan()
        )

        assertEquals(BypassCapabilityScore.TRUE_ORIGINAL_RAW, report.raw)
        assertEquals(BypassCapabilityScore.PARTIAL_VIDEO_BYPASS, report.video)
        assertEquals(BypassCapabilityScore.TRUE_ORIGINAL_RAW, report.fullSensor)
    }

    @Test
    fun `reports vendor required when no raw yuv encoder or isp-off keys are exposed`() {
        val report = CameraBypassCapabilityScorePolicy.resolve(
            capability = capability(
                pixelArraySize = CameraStreamSize(4096, 3072),
                rawOutputSizes = emptyList(),
                yuvOutputSizes = emptyList(),
                encoderOutputSizes = emptyList(),
                supportsRaw = false,
                supportsManualSensor = false,
                supportsIspDisableRequestKeys = false,
                supportsCompleteIspBypassRequest = false
            ),
            requestPlan = emptyRequestPlan()
        )

        assertEquals(BypassCapabilityScore.VENDOR_REQUIRED, report.raw)
        assertEquals(BypassCapabilityScore.VENDOR_REQUIRED, report.video)
        assertEquals(BypassCapabilityScore.VENDOR_REQUIRED, report.fullSensor)
        assertFalse(report.ispOffApplied)
        assertEquals("NONE", report.ispOffCapability)
    }

    @Test
    fun `camera capability log labels isp off instead of generic bypass`() {
        val source = File("src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt").readText()

        assertTrue(source.contains("ISP-off capability score"))
        assertFalse(source.contains("Bypass capability score"))
    }

    private fun capability(
        pixelArraySize: CameraStreamSize,
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
            activeArraySize = pixelArraySize,
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
            supported = IspBypassFeature.values().toSet(),
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

    private fun emptyRequestPlan(): IspBypassRequestPlan =
        requestPlan(
            supported = emptySet(),
            unsupported = listOf("all ISP-off request keys unavailable")
        )

    private fun requestPlan(
        supported: Set<IspBypassFeature>,
        unsupported: List<String>
    ): IspBypassRequestPlan =
        IspBypassRequestPlan(
            supportedFeatures = supported,
            unsupportedReasons = unsupported,
            requiredFeatures = IspBypassFeature.values().toSet(),
            distortionCorrectionPreference = IspBypassDistortionCorrectionPreference.SENSOR_NATIVE_OFF,
            distortionCorrectionMode = null
        )
}
