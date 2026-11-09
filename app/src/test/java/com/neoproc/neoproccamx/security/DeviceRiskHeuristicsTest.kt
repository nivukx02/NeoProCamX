package com.neoproc.neoproccamx.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceRiskHeuristicsTest {
    @Test
    fun isProbablyEmulator_detectsCommonEmulatorBuildFingerprint() {
        val snapshot = DeviceBuildSnapshot(
            fingerprint = "generic/sdk/generic:14/UP1A/test-keys",
            model = "Android SDK built for x86",
            manufacturer = "Google",
            product = "sdk_gphone64_x86_64",
            hardware = "ranchu",
            brand = "generic",
            device = "generic"
        )

        assertTrue(DeviceRiskHeuristics.isProbablyEmulator(snapshot))
    }

    @Test
    fun isProbablyEmulator_allowsPhysicalDeviceSignatures() {
        val snapshot = DeviceBuildSnapshot(
            fingerprint = "vivo/PD2407/PD2407:14/UP1A.231005.007/release-keys",
            model = "V2405",
            manufacturer = "vivo",
            product = "PD2407",
            hardware = "mt6897",
            brand = "vivo",
            device = "PD2407"
        )

        assertFalse(DeviceRiskHeuristics.isProbablyEmulator(snapshot))
    }

    @Test
    fun isProbablyRooted_detectsTestKeysAndSuBinarySignals() {
        val rooted = DeviceRiskHeuristics.isProbablyRooted(
            buildTags = "test-keys",
            detectedRootPaths = setOf("/system/xbin/su"),
            magiskDetected = false
        )

        assertTrue(rooted)
    }
}
