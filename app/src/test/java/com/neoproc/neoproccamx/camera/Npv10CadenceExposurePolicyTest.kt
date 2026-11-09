package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CaptureRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Npv10CadenceExposurePolicyTest {
    @Test
    fun `automatic private NPV10 1K 30 receives a 25 millisecond exposure budget`() {
        val plan = resolve()

        assertTrue(plan.active)
        assertEquals(25_000_000L, plan.fixedExposureTimeNs)
        assertTrue(plan.forceAntibandingOff)
        assertEquals("NPV10_1K30_SENSOR_CADENCE_GUARD", plan.reason)
    }

    @Test
    fun `active cadence guard disables supported antibanding only for video requests`() {
        val requested = CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ
        val available = intArrayOf(
            CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF,
            requested
        )

        assertEquals(
            CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF,
            Npv10CadenceExposurePolicy.resolveAntibandingMode(
                plan = resolve(),
                requestedMode = requested,
                availableModes = available,
                stillCapture = false
            )
        )
        assertEquals(
            requested,
            Npv10CadenceExposurePolicy.resolveAntibandingMode(
                plan = resolve(),
                requestedMode = requested,
                availableModes = available,
                stillCapture = true
            )
        )
    }

    @Test
    fun `antibanding remains unchanged outside target guard or without OFF support`() {
        val requested = CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ

        assertEquals(
            requested,
            Npv10CadenceExposurePolicy.resolveAntibandingMode(
                plan = resolve(fps = 25),
                requestedMode = requested,
                availableModes = intArrayOf(
                    CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_OFF,
                    requested
                ),
                stillCapture = false
            )
        )
        assertEquals(
            requested,
            Npv10CadenceExposurePolicy.resolveAntibandingMode(
                plan = resolve(),
                requestedMode = requested,
                availableModes = intArrayOf(requested),
                stillCapture = false
            )
        )
    }

    @Test
    fun `portrait-normalized 720 by 1280 remains the same 1K capture`() {
        assertTrue(resolve(width = 720, height = 1280).active)
    }

    @Test
    fun `25 fps and every non-1K size retain their existing exposure policy`() {
        assertFalse(resolve(fps = 25).active)
        assertFalse(resolve(width = 3840, height = 2160).active)
        assertNull(resolve(fps = 25).fixedExposureTimeNs)
        assertFalse(resolve(fps = 25).forceAntibandingOff)
    }

    @Test
    fun `direct encoder and non-SDR routes are untouched`() {
        assertFalse(resolve(privateMaster = false).active)
        assertFalse(resolve(sdr = false).active)
    }

    @Test
    fun `operator ISO or shutter always takes precedence`() {
        assertFalse(resolve(manualIso = 800).active)
        assertFalse(resolve(manualExposureNs = 10_000_000L).active)
    }

    @Test
    fun `unsupported manual sensor fails closed without changing quality settings`() {
        val plan = resolve(supportsManualSensor = false)

        assertFalse(plan.active)
        assertNull(plan.fixedExposureTimeNs)
        assertEquals("MANUAL_SENSOR_UNAVAILABLE", plan.reason)
    }

    private fun resolve(
        enabled: Boolean = true,
        privateMaster: Boolean = true,
        sdr: Boolean = true,
        width: Int = 1280,
        height: Int = 720,
        fps: Int = 30,
        supportsManualSensor: Boolean = true,
        manualIso: Int = -1,
        manualExposureNs: Long = -1L
    ) = Npv10CadenceExposurePolicy.resolve(
        npv10TenBitCaptureEnabled = enabled,
        privateMasterPipeline = privateMaster,
        sdrProcessedTransport = sdr,
        captureWidth = width,
        captureHeight = height,
        targetFps = fps,
        supportsManualSensor = supportsManualSensor,
        manualIso = manualIso,
        manualExposureTimeNs = manualExposureNs
    )
}
