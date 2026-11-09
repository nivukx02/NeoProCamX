package com.neoproc.neoproccamx.cinema

import com.neoproc.neoproccamx.camera.HdrDynamicRangeMode
import com.neoproc.neoproccamx.camera.HdrDynamicRangePolicy
import com.neoproc.neoproccamx.color.NeoTenBitHardwareSupport
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.StabilizationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CinematicBundlePolicyTest {

    private val hdrCapable = HdrDynamicRangePolicy.Support(
        standardOnly = false,
        hlg10Supported = true,
        hdr10Supported = true,
        hdr10PlusSupported = true
    )

    private val tenBitCapable = NeoTenBitHardwareSupport(
        hdr10Supported = true,
        hlgSupported = true
    )

    private fun fullyAvailable(): CinematicBundlePolicy.CinematicBundleAvailability =
        CinematicBundlePolicy.CinematicBundleAvailability(
            eisAvailable = true,
            bokehAvailable = true,
            hdrSupport = hdrCapable,
            tenBitSupport = tenBitCapable
        )

    @Test
    fun `disabled returns the frozen inert snapshot`() {
        val decision = CinematicBundlePolicy.resolve(
            enabled = false,
            avail = fullyAvailable()
        )

        // Frozen golden — must reproduce today's (bundle-off) output bit-for-bit,
        // even when every member is available.
        assertEquals(CinematicBundlePolicy.CinematicBundleDecision.INERT, decision)
        assertEquals(StabilizationMode.OFF, decision.stabilization)
        assertFalse(decision.bokehEngaged)
        assertEquals(HdrDynamicRangeMode.SDR, decision.ndrMode)
        assertEquals(null, decision.targetFps)
        assertEquals(CinemaVideoMode.PRO, decision.logMode)
        assertFalse(decision.tenBitEngaged)
        assertTrue(decision.droppedMembers.isEmpty())
    }

    @Test
    fun `all available engages full bundle with no dropped members`() {
        val decision = CinematicBundlePolicy.resolve(
            enabled = true,
            avail = fullyAvailable()
        )

        assertEquals(StabilizationMode.HYBRID, decision.stabilization)
        assertTrue(decision.bokehEngaged)
        assertEquals(HdrDynamicRangeMode.HDR10_PLUS, decision.ndrMode)
        assertEquals(24, decision.targetFps)
        assertEquals(CinemaVideoMode.LOG, decision.logMode)
        assertTrue(decision.tenBitEngaged)
        assertTrue(decision.droppedMembers.isEmpty())
    }

    @Test
    fun `no eis drops stabilization but keeps the rest of the bundle`() {
        val decision = CinematicBundlePolicy.resolve(
            enabled = true,
            avail = fullyAvailable().copy(eisAvailable = false)
        )

        assertEquals(StabilizationMode.OFF, decision.stabilization)
        assertTrue("stabilization" in decision.droppedMembers)
        // Graceful degradation, not total failure — the other members still engage.
        assertTrue(decision.bokehEngaged)
        assertEquals(HdrDynamicRangeMode.HDR10_PLUS, decision.ndrMode)
        assertTrue(decision.tenBitEngaged)
        assertEquals(24, decision.targetFps)
        assertEquals(CinemaVideoMode.LOG, decision.logMode)
    }

    @Test
    fun `no bokeh drops bokeh but keeps the rest of the bundle`() {
        val decision = CinematicBundlePolicy.resolve(
            enabled = true,
            avail = fullyAvailable().copy(bokehAvailable = false)
        )

        assertFalse(decision.bokehEngaged)
        assertTrue("bokeh" in decision.droppedMembers)
        assertEquals(StabilizationMode.HYBRID, decision.stabilization)
        assertEquals(HdrDynamicRangeMode.HDR10_PLUS, decision.ndrMode)
        assertTrue(decision.tenBitEngaged)
    }

    @Test
    fun `no hdr support falls back to sdr and drops ndr`() {
        val decision = CinematicBundlePolicy.resolve(
            enabled = true,
            avail = fullyAvailable().copy(hdrSupport = HdrDynamicRangePolicy.Support.NONE)
        )

        assertEquals(HdrDynamicRangeMode.SDR, decision.ndrMode)
        assertTrue("ndr" in decision.droppedMembers)
        assertEquals(StabilizationMode.HYBRID, decision.stabilization)
        assertTrue(decision.bokehEngaged)
        assertTrue(decision.tenBitEngaged)
    }

    @Test
    fun `no native ten bit path drops ten bit but keeps the rest`() {
        val decision = CinematicBundlePolicy.resolve(
            enabled = true,
            avail = fullyAvailable().copy(tenBitSupport = NeoTenBitHardwareSupport.none())
        )

        assertFalse(decision.tenBitEngaged)
        assertTrue("10bit" in decision.droppedMembers)
        assertEquals(StabilizationMode.HYBRID, decision.stabilization)
        assertTrue(decision.bokehEngaged)
        assertEquals(HdrDynamicRangeMode.HDR10_PLUS, decision.ndrMode)
    }

    @Test
    fun `nothing available drops every member but still targets the cinematic frame rate and log`() {
        val decision = CinematicBundlePolicy.resolve(
            enabled = true,
            avail = CinematicBundlePolicy.CinematicBundleAvailability(
                eisAvailable = false,
                bokehAvailable = false,
                hdrSupport = HdrDynamicRangePolicy.Support.NONE,
                tenBitSupport = NeoTenBitHardwareSupport.none()
            )
        )

        assertEquals(StabilizationMode.OFF, decision.stabilization)
        assertFalse(decision.bokehEngaged)
        assertEquals(HdrDynamicRangeMode.SDR, decision.ndrMode)
        assertFalse(decision.tenBitEngaged)
        assertTrue("stabilization" in decision.droppedMembers)
        assertTrue("bokeh" in decision.droppedMembers)
        assertTrue("ndr" in decision.droppedMembers)
        assertTrue("10bit" in decision.droppedMembers)
        // Frame rate and LOG intent are still expressed — degradation is per-member.
        assertEquals(24, decision.targetFps)
        assertEquals(CinemaVideoMode.LOG, decision.logMode)
    }

    @Test
    fun `target fps is 24 and 24 point 0 is a cinema standard in the catalog`() {
        val decision = CinematicBundlePolicy.resolve(
            enabled = true,
            avail = fullyAvailable()
        )

        assertEquals(24, decision.targetFps)
        assertEquals(CinematicBundlePolicy.CINEMATIC_TARGET_FPS, decision.targetFps)

        val entry = CinemaFrameRateCatalog.getFrameRate(24.0)
        assertNotNull(entry)
        assertTrue(entry!!.isCinemaStandard)
    }
}
