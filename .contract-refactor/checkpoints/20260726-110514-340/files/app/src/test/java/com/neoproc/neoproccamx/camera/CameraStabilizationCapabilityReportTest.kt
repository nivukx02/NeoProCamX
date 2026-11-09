package com.neoproc.neoproccamx.camera

import com.neoproc.neoproccamx.recording.StabilizationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraStabilizationCapabilityReportTest {

    @Test
    fun `describe reports both capabilities and readable diagnostic`() {
        val r = CameraStabilizationCapabilityReport.describe(
            oisModes = intArrayOf(0, 1),
            videoStabModes = intArrayOf(0, 1, 2)
        )
        assertTrue(r.supportsOis)
        assertTrue(r.supportsVideoStabilization)
        assertTrue(r.diagnostic.contains("ois=[OFF,ON]"))
        assertTrue(r.diagnostic.contains("videoStab=[OFF,ON,PREVIEW]"))
        assertTrue(r.diagnostic.contains("ois=true"))
        assertTrue(r.diagnostic.contains("eis=true"))
    }

    @Test
    fun `describe with no advertised modes reports unsupported`() {
        val r = CameraStabilizationCapabilityReport.describe(intArrayOf(), intArrayOf())
        assertFalse(r.supportsOis)
        assertFalse(r.supportsVideoStabilization)
        assertTrue(r.diagnostic.contains("ois=[none]"))
        assertTrue(r.diagnostic.contains("videoStab=[none]"))
    }

    @Test
    fun `off never warns`() {
        assertFalse(
            CameraStabilizationCapabilityReport.requiredHardwareMissing(
                StabilizationMode.OFF, supportsOis = false, supportsVideoStabilization = false
            )
        )
    }

    @Test
    fun `ois requested warns only when ois absent`() {
        assertFalse(CameraStabilizationCapabilityReport.requiredHardwareMissing(StabilizationMode.OIS, supportsOis = true, supportsVideoStabilization = false))
        assertTrue(CameraStabilizationCapabilityReport.requiredHardwareMissing(StabilizationMode.OIS, supportsOis = false, supportsVideoStabilization = true))
    }

    @Test
    fun `eis requested warns only when video stabilization absent`() {
        assertFalse(CameraStabilizationCapabilityReport.requiredHardwareMissing(StabilizationMode.EIS, supportsOis = false, supportsVideoStabilization = true))
        assertTrue(CameraStabilizationCapabilityReport.requiredHardwareMissing(StabilizationMode.EIS, supportsOis = true, supportsVideoStabilization = false))
    }

    @Test
    fun `hybrid does not warn when both caps present but warns when either missing`() {
        assertFalse(CameraStabilizationCapabilityReport.requiredHardwareMissing(StabilizationMode.HYBRID, supportsOis = true, supportsVideoStabilization = true))
        assertTrue(CameraStabilizationCapabilityReport.requiredHardwareMissing(StabilizationMode.HYBRID, supportsOis = true, supportsVideoStabilization = false))
        assertTrue(CameraStabilizationCapabilityReport.requiredHardwareMissing(StabilizationMode.HYBRID, supportsOis = false, supportsVideoStabilization = true))
    }

    @Test
    fun `unknown mode ints render as hex tokens`() {
        val r = CameraStabilizationCapabilityReport.describe(intArrayOf(9), intArrayOf(7))
        assertTrue(r.diagnostic.contains("0x9"))
        assertTrue(r.diagnostic.contains("0x7"))
        assertEquals(false, r.supportsOis)
    }
}
