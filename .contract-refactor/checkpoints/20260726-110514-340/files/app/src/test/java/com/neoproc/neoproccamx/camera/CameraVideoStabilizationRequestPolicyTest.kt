package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CaptureRequest
import com.neoproc.neoproccamx.recording.StabilizationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraVideoStabilizationRequestPolicyTest {

    @Test
    fun `hybrid request prefers ois without enabling eis crop when both are supported`() {
        val plan = CameraVideoStabilizationRequestPolicy.resolve(
            requestedMode = StabilizationMode.HYBRID,
            supportsOis = true,
            supportsVideoStabilization = true,
            rawVideoCaptureEnabled = false
        )

        assertEquals(
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON,
            plan.oisMode
        )
        assertEquals(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
            plan.videoStabilizationMode
        )
        assertEquals(StabilizationMode.OIS, plan.resolvedMode)
    }

    @Test
    fun `no stabilization request enables ois and eis simultaneously`() {
        StabilizationMode.entries.forEach { mode ->
            val plan = CameraVideoStabilizationRequestPolicy.resolve(
                requestedMode = mode,
                supportsOis = true,
                supportsVideoStabilization = true,
                rawVideoCaptureEnabled = false
            )

            val bothEnabled =
                plan.oisMode == CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON &&
                    plan.videoStabilizationMode == CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
            assertEquals("mode=$mode", false, bothEnabled)
        }
    }

    @Test
    fun `ois request falls back to eis when the lens has no optical stabilization`() {
        val plan = CameraVideoStabilizationRequestPolicy.resolve(
            requestedMode = StabilizationMode.OIS,
            supportsOis = false,
            supportsVideoStabilization = true,
            rawVideoCaptureEnabled = false
        )

        assertNull(plan.oisMode)
        assertEquals(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON,
            plan.videoStabilizationMode
        )
        assertEquals(StabilizationMode.EIS, plan.resolvedMode)
    }

    @Test
    fun `eis request falls back to ois when video stabilization is unavailable`() {
        val plan = CameraVideoStabilizationRequestPolicy.resolve(
            requestedMode = StabilizationMode.EIS,
            supportsOis = true,
            supportsVideoStabilization = false,
            rawVideoCaptureEnabled = false
        )

        assertEquals(
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON,
            plan.oisMode
        )
        assertNull(plan.videoStabilizationMode)
        assertEquals(StabilizationMode.OIS, plan.resolvedMode)
    }

    @Test
    fun `explicit off disables every supported stabilization path`() {
        val plan = CameraVideoStabilizationRequestPolicy.resolve(
            requestedMode = StabilizationMode.OFF,
            supportsOis = true,
            supportsVideoStabilization = true,
            rawVideoCaptureEnabled = false
        )

        assertEquals(
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF,
            plan.oisMode
        )
        assertEquals(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
            plan.videoStabilizationMode
        )
        assertEquals(StabilizationMode.OFF, plan.resolvedMode)
    }

    @Test
    fun `raw original video disables stabilization even when hybrid was requested`() {
        val plan = CameraVideoStabilizationRequestPolicy.resolve(
            requestedMode = StabilizationMode.HYBRID,
            supportsOis = true,
            supportsVideoStabilization = true,
            rawVideoCaptureEnabled = true
        )

        assertEquals(
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF,
            plan.oisMode
        )
        assertEquals(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
            plan.videoStabilizationMode
        )
        assertEquals(StabilizationMode.OFF, plan.resolvedMode)
    }
}
