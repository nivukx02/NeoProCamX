package com.neoproc.neoproccamx.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionAccessibilityContractTest {
    private fun source(path: String): String = File(path).readText().replace("\r\n", "\n")

    @Test
    fun primaryIconAndShutterTargetsMeetAccessibilityContract() {
        val dimensions = source("src/main/res/values/dimens.xml")
        val layout = source("src/main/res/layout/activity_main.xml")
        val activity = source("src/main/java/com/neoproc/neoproccamx/ui/MainActivity.kt")
        val shutter = layout.substringAfter("android:id=\"@+id/btnShutter\"").substringBefore(">")

        assertTrue(dimensions.contains("<dimen name=\"icon_touch_target\">48dp</dimen>"))
        assertTrue(shutter.contains("android:contentDescription=\"@string/cd_shutter_button\""))
        assertTrue(shutter.contains("android:clickable=\"true\""))
        assertTrue(shutter.contains("android:focusable=\"true\""))
        assertTrue(activity.contains("if (recording) R.string.rec_stop else R.string.rec_start"))
    }

    @Test
    fun permissionRecoveryHasLoopGuardAndSystemSettingsPath() {
        val activity = source("src/main/java/com/neoproc/neoproccamx/ui/MainActivity.kt")

        assertTrue(activity.contains("cameraPermissionRequestInFlight"))
        assertTrue(activity.contains("cameraPermissionRequestAttempted"))
        assertTrue(activity.contains("CameraPermissionPolicy.shouldLaunchRequest("))
        assertTrue(activity.contains("Settings.ACTION_APPLICATION_DETAILS_SETTINGS"))
        assertTrue(activity.contains("shouldShowRequestPermissionRationale("))
    }
}
