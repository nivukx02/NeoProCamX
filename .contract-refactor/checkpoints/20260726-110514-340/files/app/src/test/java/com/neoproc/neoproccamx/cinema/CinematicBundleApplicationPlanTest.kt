package com.neoproc.neoproccamx.cinema

import com.neoproc.neoproccamx.camera.HdrDynamicRangeMode
import com.neoproc.neoproccamx.camera.HdrDynamicRangePolicy
import com.neoproc.neoproccamx.color.NeoTenBitHardwareSupport
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.StabilizationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ELITE Rank-2 — goldens for the Cinematic-bundle application plan (the fps→shutter→HDR mapping the VM applies).
 */
class CinematicBundleApplicationPlanTest {

    private fun fullyCapableAvailability() = CinematicBundlePolicy.CinematicBundleAvailability(
        eisAvailable = true,
        bokehAvailable = false,
        hdrSupport = HdrDynamicRangePolicy.Support(
            standardOnly = false,
            hdr10Supported = true,
            hdr10PlusSupported = true
        ),
        tenBitSupport = NeoTenBitHardwareSupport(hdr10Supported = true, hlgSupported = true)
    )

    // Engaged bundle: 24p, 180° shutter = 1/48 s, NDR10+ token, HYBRID stab, LOG.
    @Test
    fun `engaged bundle plans 24p at a true 180-degree shutter`() {
        val decision = CinematicBundlePolicy.resolve(enabled = true, avail = fullyCapableAvailability())
        val plan = CinematicBundleApplicationPlan.from(decision, currentFps = 60)

        assertEquals("engages 24p", 24, plan.fps)
        // 180° at 24 fps = (0.5)/24 s = 20,833,333 ns (1/48 s).
        assertEquals("180-degree shutter at 24p", 20_833_333L, plan.shutterNs)
        assertEquals("HDR token", CinematicBundleApplicationPlan.HDR_TOKEN_NDR10_PLUS, plan.hdrModeToken)
        assertEquals("hybrid stabilization", StabilizationMode.HYBRID, plan.stabilization)
        assertEquals("LOG cinema mode", CinemaVideoMode.LOG, plan.cinemaMode)
        assertTrue("bokeh dropped honestly", plan.droppedMembers.contains("bokeh"))
    }

    // INERT decision (bundle disabled) plans no fps change, SDR token, OFF/PRO — a no-op restore.
    @Test
    fun `inert decision plans a no-op`() {
        val plan = CinematicBundleApplicationPlan.from(
            CinematicBundlePolicy.CinematicBundleDecision.INERT,
            currentFps = 30
        )
        assertNull("no fps override when inert", plan.fps)
        // With no fps override the shutter tracks the current fps (180° at 30 = 1/60 s) but the VM
        // only applies it when engaged; the plan still exposes a valid value, never a crash.
        assertEquals("shutter tracks current fps when inert", 16_666_666L, plan.shutterNs)
        assertEquals("SDR maps to OFF", CinematicBundleApplicationPlan.HDR_TOKEN_OFF, plan.hdrModeToken)
        assertEquals(StabilizationMode.OFF, plan.stabilization)
        assertEquals(CinemaVideoMode.PRO, plan.cinemaMode)
    }

    // Degraded hardware: HDR unsupported → SDR/OFF token + "ndr" dropped, EIS unsupported → OFF + "stabilization".
    @Test
    fun `unsupported hardware degrades honestly`() {
        val avail = CinematicBundlePolicy.CinematicBundleAvailability(
            eisAvailable = false,
            bokehAvailable = false,
            hdrSupport = HdrDynamicRangePolicy.Support.NONE,
            tenBitSupport = NeoTenBitHardwareSupport.none()
        )
        val decision = CinematicBundlePolicy.resolve(enabled = true, avail = avail)
        val plan = CinematicBundleApplicationPlan.from(decision, currentFps = 60)

        assertEquals("HDR unsupported -> OFF", CinematicBundleApplicationPlan.HDR_TOKEN_OFF, plan.hdrModeToken)
        assertEquals("EIS unsupported -> OFF", StabilizationMode.OFF, plan.stabilization)
        assertTrue(plan.droppedMembers.contains("ndr"))
        assertTrue(plan.droppedMembers.contains("stabilization"))
        // Still engages the 24p cadence + shutter even on degraded hardware (cadence is not HW-gated).
        assertEquals(24, plan.fps)
        assertEquals(20_833_333L, plan.shutterNs)
    }
}
