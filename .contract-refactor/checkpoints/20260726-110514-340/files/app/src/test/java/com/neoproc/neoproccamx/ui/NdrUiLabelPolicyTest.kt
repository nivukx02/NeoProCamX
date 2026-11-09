package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NdrUiLabelPolicyTest {

    @Test
    fun homeModeRailAndSettingsUseNdrLabels() {
        val stringsXml = locate("app/src/main/res/values/strings.xml", "src/main/res/values/strings.xml")
            .readText()
        val settingsFactory = locate(
            "app/src/main/java/com/neoproc/neoproccamx/ui/controls/SettingsSectionFactory.kt",
            "src/main/java/com/neoproc/neoproccamx/ui/controls/SettingsSectionFactory.kt"
        ).readText()

        assertTrue(stringsXml.contains("""<string name="mode_hdr">NDR</string>"""))
        assertTrue(stringsXml.contains("""<string name="capture_hdr">NDR Capture</string>"""))
        assertTrue(stringsXml.contains("""<string name="msg_hdr_processing">Processing NDR"""))
        assertTrue(settingsFactory.contains("""title = "NDR Mode""""))
        assertFalse(settingsFactory.contains("""title = "HDR Mode""""))
    }

    private fun locate(rootPath: String, modulePath: String): File =
        listOf(File(rootPath), File(modulePath)).firstOrNull { it.isFile }
            ?: File(rootPath)
}
