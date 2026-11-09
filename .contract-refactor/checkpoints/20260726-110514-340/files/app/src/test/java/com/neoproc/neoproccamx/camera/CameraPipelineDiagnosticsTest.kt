package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraPipelineDiagnosticsTest {

    @Test
    fun `readout fallback warning names requested and actual sizes`() {
        val warning = CameraPipelineDiagnostics.readoutFallbackWarning(
            requested = CameraStreamSize(8192, 6144),
            actual = CameraStreamSize(4096, 3072),
            requestedLabel = "8K full sensor",
            actualLabel = "stable RAW_SENSOR"
        )

        assertEquals(
            "8K full sensor requested, but camera/encoder exposed 4096x3072. Using stable RAW_SENSOR path.",
            warning
        )
    }

    @Test
    fun `readout fallback warning is absent when actual covers requested area`() {
        val warning = CameraPipelineDiagnostics.readoutFallbackWarning(
            requested = CameraStreamSize(4096, 3072),
            actual = CameraStreamSize(8192, 6144),
            requestedLabel = "RAW full sensor",
            actualLabel = "RAW_SENSOR"
        )

        assertNull(warning)
    }

    @Test
    fun `isp warning lists unsupported keys`() {
        val warning = CameraPipelineDiagnostics.ispBypassWarning(
            unsupportedReasons = listOf("DISTORTION_CORRECTION_MODE_OFF unavailable")
        )

        assertEquals(
            "Requested ISP processing disablement unavailable: DISTORTION_CORRECTION_MODE_OFF unavailable.",
            warning
        )
    }

    @Test
    fun `pro fallback warning includes fallback reason without experimental wording`() {
        val warning = CameraPipelineDiagnostics.experimentalFallbackWarning(
            reason = "Camera session rejected hardware import surface"
        )

        assertEquals(
            "Pro capture path adapted to this device — continued on the stable pipeline: Camera session rejected hardware import surface.",
            warning
        )
    }
}
