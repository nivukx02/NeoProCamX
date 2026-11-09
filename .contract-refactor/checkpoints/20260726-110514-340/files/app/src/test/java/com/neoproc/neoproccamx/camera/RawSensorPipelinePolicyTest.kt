package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawSensorPipelinePolicyTest {

    @Test
    fun `full sensor raw with complete controls is true original raw`() {
        val plan = RawSensorPipelinePolicy.resolve(
            capability = capability(
                pixelArraySize = CameraStreamSize(8192, 6144),
                activeArraySize = CameraStreamSize(8064, 6048),
                supportsCompleteIspBypassRequest = true
            ),
            selectedRawSize = CameraStreamSize(8064, 6048)
        )

        assertEquals(RawSensorPipelineQuality.TRUE_ORIGINAL_RAW, plan.quality)
        assertEquals(IspBypassLevel.TRUE_RAW_SENSOR, plan.ispBypassLevel)
        assertTrue(plan.truthfulFullSensorReadout)
        assertTrue(plan.truthfulOriginalRaw)
        assertTrue(plan.diagnostics.any { it.contains("true original RAW_SENSOR") })
    }

    @Test
    fun `full sensor raw with incomplete isp bypass is limited isp raw`() {
        val plan = RawSensorPipelinePolicy.resolve(
            capability = capability(
                pixelArraySize = CameraStreamSize(8192, 6144),
                activeArraySize = CameraStreamSize(8064, 6048),
                supportsCompleteIspBypassRequest = false
            ),
            selectedRawSize = CameraStreamSize(8064, 6048)
        )

        assertEquals(RawSensorPipelineQuality.FULL_SENSOR_RAW_LIMITED_ISP, plan.quality)
        assertEquals(IspBypassLevel.REQUEST_KEYS_ONLY, plan.ispBypassLevel)
        assertTrue(plan.truthfulFullSensorReadout)
        assertFalse(plan.truthfulOriginalRaw)
        assertTrue(plan.diagnostics.any { it.contains("ISP bypass") })
    }

    @Test
    fun `raw below sensor coverage is supported raw but never true original`() {
        val plan = RawSensorPipelinePolicy.resolve(
            capability = capability(
                pixelArraySize = CameraStreamSize(8192, 6144),
                activeArraySize = CameraStreamSize(8192, 6144),
                supportsCompleteIspBypassRequest = true
            ),
            selectedRawSize = CameraStreamSize(4096, 3072)
        )

        assertEquals(RawSensorPipelineQuality.SUPPORTED_RAW_LIMITED_READOUT, plan.quality)
        assertEquals(IspBypassLevel.REQUEST_KEYS_ONLY, plan.ispBypassLevel)
        assertFalse(plan.truthfulFullSensorReadout)
        assertFalse(plan.truthfulOriginalRaw)
        assertTrue(plan.diagnostics.any { it.contains("below full sensor") })
    }

    @Test
    fun `raw unavailable returns unavailable without isp bypass`() {
        val plan = RawSensorPipelinePolicy.resolve(
            capability = capability(
                pixelArraySize = CameraStreamSize(4096, 3072),
                activeArraySize = CameraStreamSize(4096, 3072),
                rawOutputSizes = emptyList()
            ),
            selectedRawSize = null
        )

        assertEquals(RawSensorPipelineQuality.UNAVAILABLE, plan.quality)
        assertEquals(IspBypassLevel.NONE, plan.ispBypassLevel)
        assertFalse(plan.truthfulFullSensorReadout)
        assertFalse(plan.truthfulOriginalRaw)
        assertTrue(plan.diagnostics.any { it.contains("RAW_SENSOR unavailable") })
    }

    private fun capability(
        pixelArraySize: CameraStreamSize,
        activeArraySize: CameraStreamSize,
        rawOutputSizes: List<CameraStreamSize> = listOf(activeArraySize),
        supportsManualSensor: Boolean = true,
        supportsTonemapContrastCurve: Boolean = true,
        supportsCompleteIspBypassRequest: Boolean = true
    ): CameraCapabilityInventory =
        CameraCapabilityInventory(
            cameraId = "0",
            pixelArraySize = pixelArraySize,
            activeArraySize = activeArraySize,
            rawOutputSizes = rawOutputSizes,
            yuvOutputSizes = emptyList(),
            jpegOutputSizes = listOf(pixelArraySize),
            encoderOutputSizes = emptyList(),
            supportsRaw = rawOutputSizes.isNotEmpty(),
            supportsTonemapContrastCurve = supportsTonemapContrastCurve,
            supportsManualSensor = supportsManualSensor,
            supportsIspDisableRequestKeys = supportsCompleteIspBypassRequest,
            supportsCompleteIspBypassRequest = supportsCompleteIspBypassRequest
        )
}
