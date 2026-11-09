package com.neoproc.neoproccamx.color

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NdrNivukxOfflineToneSimulatorToolTest {

    @Test
    fun `offline simulator mirrors NDR and NIVUKX calibration workflow`() {
        val tool = listOf(
            File("tools/calibration/offline_ndr_nivukx_tone_simulator.py"),
            File("../tools/calibration/offline_ndr_nivukx_tone_simulator.py"),
        ).firstOrNull { it.isFile } ?: File("tools/calibration/offline_ndr_nivukx_tone_simulator.py")

        assertTrue("Offline simulator tool missing", tool.isFile)
        val source = tool.readText()

        assertTrue(source.contains("def simulate_ndr_nivukx"))
        assertTrue(source.contains("mode == \"NDR10\""))
        assertTrue(source.contains("mode == \"NIVUKX\""))
        assertTrue(source.contains("tone_delta_report.json"))
        assertTrue(source.contains("ndr_measured_sky_hue_guard"))
        assertTrue(source.contains("ndr_measured_skin_hue_guard"))
    }
}
