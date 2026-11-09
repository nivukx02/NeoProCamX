package com.neoproc.neoproccamx.camera

import com.neoproc.neoproccamx.recording.CinemaVideoMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCapabilityContractTest {

    @Test
    fun `raw plan uses largest raw stream as true isp bypass`() {
        val plan = CameraStreamPlanResolver.resolve(
            request = CameraStreamPlanRequest(cinemaMode = CinemaVideoMode.RAW),
            capability = baseCapability(
                pixelArraySize = CameraStreamSize(8160, 6144),
                rawOutputSizes = listOf(CameraStreamSize(8160, 6144), CameraStreamSize(4080, 3072)),
                yuvOutputSizes = listOf(CameraStreamSize(3840, 2160)),
                encoderOutputSizes = listOf(CameraStreamSize(3840, 2160))
            )
        )

        assertEquals(CameraReadoutTier.FULL_SENSOR_RAW, plan.readoutTier)
        assertEquals(IspBypassLevel.TRUE_RAW_SENSOR, plan.ispBypassLevel)
        assertSizeEquals(8160, 6144, plan.rawSize)
        assertSizeEquals(8160, 6144, plan.primaryReadoutSize)
        assertTrue(plan.truthfulFullSensorReadout)
        assertTrue(plan.fallbackChain.first().contains("RAW_SENSOR 8160x6144"))
    }

    @Test
    fun `raw request falls back to high resolution yuv without claiming true isp bypass`() {
        val plan = CameraStreamPlanResolver.resolve(
            request = CameraStreamPlanRequest(cinemaMode = CinemaVideoMode.RAW),
            capability = baseCapability(
                pixelArraySize = CameraStreamSize(8160, 6144),
                rawOutputSizes = emptyList(),
                yuvOutputSizes = listOf(CameraStreamSize(7680, 4320), CameraStreamSize(3840, 2160)),
                encoderOutputSizes = listOf(CameraStreamSize(3840, 2160))
            )
        )

        assertEquals(CameraReadoutTier.HIGH_RES_YUV, plan.readoutTier)
        assertEquals(IspBypassLevel.BEST_EFFORT_YUV, plan.ispBypassLevel)
        assertSizeEquals(7680, 4320, plan.yuvAnalysisSize)
        assertSizeEquals(7680, 4320, plan.primaryReadoutSize)
        assertFalse(plan.truthfulFullSensorReadout)
        assertTrue(plan.diagnostics.any { it.contains("RAW_SENSOR unavailable") })
    }

    @Test
    fun `encoder only plan is stable fallback and never marked full sensor`() {
        val plan = CameraStreamPlanResolver.resolve(
            request = CameraStreamPlanRequest(cinemaMode = CinemaVideoMode.PRO),
            capability = baseCapability(
                pixelArraySize = CameraStreamSize(8160, 6144),
                rawOutputSizes = emptyList(),
                yuvOutputSizes = emptyList(),
                encoderOutputSizes = listOf(CameraStreamSize(3840, 2160), CameraStreamSize(1920, 1080))
            )
        )

        assertEquals(CameraReadoutTier.ENCODER_ONLY, plan.readoutTier)
        assertEquals(IspBypassLevel.NONE, plan.ispBypassLevel)
        assertSizeEquals(3840, 2160, plan.encoderSize)
        assertSizeEquals(3840, 2160, plan.primaryReadoutSize)
        assertFalse(plan.truthfulFullSensorReadout)
        assertTrue(plan.fallbackChain.last().contains("ENCODER 3840x2160"))
    }

    @Test
    fun `pro mode records encoder while using maximum yuv readout sidecar`() {
        val plan = CameraStreamPlanResolver.resolve(
            request = CameraStreamPlanRequest(
                cinemaMode = CinemaVideoMode.PRO,
                preferredEncoderSize = CameraStreamSize(3840, 2160),
                requestedReadoutSize = CameraStreamSize(4096, 3072)
            ),
            capability = baseCapability(
                pixelArraySize = CameraStreamSize(4096, 3072),
                rawOutputSizes = listOf(CameraStreamSize(4096, 3072)),
                yuvOutputSizes = listOf(CameraStreamSize(4096, 3072), CameraStreamSize(3840, 2160)),
                encoderOutputSizes = listOf(CameraStreamSize(3840, 2160))
            )
        )

        assertEquals(CameraReadoutTier.ENCODER_WITH_ANALYSIS, plan.readoutTier)
        assertEquals(IspBypassLevel.BEST_EFFORT_YUV, plan.ispBypassLevel)
        assertSizeEquals(3840, 2160, plan.encoderSize)
        assertSizeEquals(4096, 3072, plan.yuvAnalysisSize)
        assertSizeEquals(4096, 3072, plan.primaryReadoutSize)
        assertTrue(plan.truthfulFullSensorReadout)
        assertTrue(plan.fallbackChain.first().contains("YUV_420_888_READOUT 4096x3072"))
    }

    @Test
    fun `log mode records encoder while using largest exposed yuv readout sidecar`() {
        val plan = CameraStreamPlanResolver.resolve(
            request = CameraStreamPlanRequest(cinemaMode = CinemaVideoMode.LOG),
            capability = baseCapability(
                pixelArraySize = CameraStreamSize(8160, 6144),
                rawOutputSizes = listOf(CameraStreamSize(8160, 6144)),
                yuvOutputSizes = listOf(CameraStreamSize(4096, 3072), CameraStreamSize(3840, 2160)),
                encoderOutputSizes = listOf(CameraStreamSize(3840, 2160))
            )
        )

        assertEquals(CameraReadoutTier.ENCODER_WITH_ANALYSIS, plan.readoutTier)
        assertEquals(IspBypassLevel.BEST_EFFORT_YUV, plan.ispBypassLevel)
        assertSizeEquals(3840, 2160, plan.encoderSize)
        assertSizeEquals(4096, 3072, plan.yuvAnalysisSize)
        assertSizeEquals(4096, 3072, plan.primaryReadoutSize)
        assertFalse(plan.truthfulFullSensorReadout)
    }

    @Test
    fun `raw request warns when exposed raw path is below requested full sensor`() {
        val plan = CameraStreamPlanResolver.resolve(
            request = CameraStreamPlanRequest(
                cinemaMode = CinemaVideoMode.RAW,
                requestedReadoutSize = CameraStreamSize(8192, 6144)
            ),
            capability = baseCapability(
                pixelArraySize = CameraStreamSize(8192, 6144),
                rawOutputSizes = listOf(CameraStreamSize(4096, 3072)),
                yuvOutputSizes = listOf(CameraStreamSize(4096, 3072)),
                encoderOutputSizes = listOf(CameraStreamSize(4096, 2160))
            )
        )

        assertEquals(
            "8K full sensor requested, but camera/encoder exposed 4096x3072. Using stable RAW_SENSOR path.",
            plan.fallbackWarning
        )
        assertTrue(plan.diagnostics.contains(plan.fallbackWarning))
    }

    @Test
    fun `raw below full sensor coverage never claims true raw sensor bypass`() {
        val plan = CameraStreamPlanResolver.resolve(
            request = CameraStreamPlanRequest(
                cinemaMode = CinemaVideoMode.RAW,
                requestedReadoutSize = CameraStreamSize(8192, 6144)
            ),
            capability = baseCapability(
                pixelArraySize = CameraStreamSize(8192, 6144),
                rawOutputSizes = listOf(CameraStreamSize(4096, 3072)),
                yuvOutputSizes = listOf(CameraStreamSize(4096, 3072)),
                encoderOutputSizes = listOf(CameraStreamSize(4096, 2160))
            )
        )

        assertEquals(CameraReadoutTier.SUPPORTED_RAW, plan.readoutTier)
        assertEquals(IspBypassLevel.REQUEST_KEYS_ONLY, plan.ispBypassLevel)
        assertFalse(plan.truthfulFullSensorReadout)
        assertTrue(plan.diagnostics.any { it.contains("below full sensor") })
    }

    @Test
    fun `raw request does not warn when exposed raw path covers requested full sensor`() {
        val plan = CameraStreamPlanResolver.resolve(
            request = CameraStreamPlanRequest(
                cinemaMode = CinemaVideoMode.RAW,
                requestedReadoutSize = CameraStreamSize(8192, 6144)
            ),
            capability = baseCapability(
                pixelArraySize = CameraStreamSize(8192, 6144),
                rawOutputSizes = listOf(CameraStreamSize(8192, 6144)),
                yuvOutputSizes = listOf(CameraStreamSize(4096, 3072)),
                encoderOutputSizes = listOf(CameraStreamSize(4096, 2160))
            )
        )

        assertEquals(null, plan.fallbackWarning)
    }

    @Test
    fun `raw plan treats active array coverage as truthful full sensor readout`() {
        val plan = CameraStreamPlanResolver.resolve(
            request = CameraStreamPlanRequest(cinemaMode = CinemaVideoMode.RAW),
            capability = baseCapability(
                pixelArraySize = CameraStreamSize(8192, 6144),
                activeArraySize = CameraStreamSize(8064, 6048),
                rawOutputSizes = listOf(CameraStreamSize(8064, 6048)),
                yuvOutputSizes = listOf(CameraStreamSize(3840, 2160)),
                encoderOutputSizes = listOf(CameraStreamSize(3840, 2160))
            )
        )

        assertEquals(CameraReadoutTier.FULL_SENSOR_RAW, plan.readoutTier)
        assertTrue(plan.truthfulFullSensorReadout)
        assertTrue(plan.diagnostics.any { it.contains("active sensor") })
    }

    private fun baseCapability(
        pixelArraySize: CameraStreamSize,
        activeArraySize: CameraStreamSize = pixelArraySize,
        rawOutputSizes: List<CameraStreamSize>,
        yuvOutputSizes: List<CameraStreamSize>,
        encoderOutputSizes: List<CameraStreamSize>
    ): CameraCapabilityInventory =
        CameraCapabilityInventory(
            cameraId = "0",
            pixelArraySize = pixelArraySize,
            activeArraySize = activeArraySize,
            rawOutputSizes = rawOutputSizes,
            yuvOutputSizes = yuvOutputSizes,
            jpegOutputSizes = listOf(pixelArraySize),
            encoderOutputSizes = encoderOutputSizes,
            supportsRaw = rawOutputSizes.isNotEmpty(),
            supportsTonemapContrastCurve = true,
            supportsManualSensor = true,
            supportsIspDisableRequestKeys = true
        )

    private fun assertSizeEquals(expectedWidth: Int, expectedHeight: Int, actual: CameraStreamSize?) {
        assertEquals(expectedWidth, actual?.width)
        assertEquals(expectedHeight, actual?.height)
    }
}
