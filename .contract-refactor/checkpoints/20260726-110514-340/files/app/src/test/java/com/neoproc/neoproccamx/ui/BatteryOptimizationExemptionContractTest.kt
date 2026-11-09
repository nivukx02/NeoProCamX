package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BatteryOptimizationExemptionContractTest {

    private fun read(path: String): String =
        File(path).readText().replace("\r\n", "\n")

    @Test
    fun manifestDoesNotRequestRestrictedBatteryOptimizationExemptionPermission() {
        val manifest = read("src/main/AndroidManifest.xml")

        // Compliant: Should NOT contain Play-restricted permission
        assertFalse(
            manifest.contains("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS")
        )
    }

    @Test
    fun batteryOptimizationExemptionRequesterIsPlayCompliant() {
        val requester = read("src/main/java/com/neoproc/neoproccamx/ui/BatteryOptimizationExemptionRequester.kt")

        assertTrue(requester.contains("fun requestIfNeeded(activity: Activity)"))
        assertTrue(requester.contains("if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return"))
        assertTrue(requester.contains("getSystemService(PowerManager::class.java)"))
        assertTrue(requester.contains("powerManager.isIgnoringBatteryOptimizations(activity.packageName)"))
        
        // Compliant: Must use ignore settings intent instead of the restricted request intent
        assertTrue(requester.contains("Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)"))
    }
}
