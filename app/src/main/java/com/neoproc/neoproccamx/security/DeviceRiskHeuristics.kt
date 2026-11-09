package com.neoproc.neoproccamx.security

import java.util.Locale

data class DeviceBuildSnapshot(
    val fingerprint: String,
    val model: String,
    val manufacturer: String,
    val product: String,
    val hardware: String,
    val brand: String,
    val device: String
)

object DeviceRiskHeuristics {
    fun isProbablyEmulator(snapshot: DeviceBuildSnapshot): Boolean {
        val fingerprint = snapshot.fingerprint.lowercase(Locale.US)
        val model = snapshot.model.lowercase(Locale.US)
        val manufacturer = snapshot.manufacturer.lowercase(Locale.US)
        val product = snapshot.product.lowercase(Locale.US)
        val hardware = snapshot.hardware.lowercase(Locale.US)
        val brand = snapshot.brand.lowercase(Locale.US)
        val device = snapshot.device.lowercase(Locale.US)
        return fingerprint.startsWith("generic") ||
            fingerprint.contains("emulator") ||
            fingerprint.contains("test-keys") && hardware.contains("ranchu") ||
            model.contains("emulator") ||
            model.contains("android sdk built for") ||
            manufacturer.contains("genymotion") ||
            product.contains("sdk") ||
            product.contains("emulator") ||
            hardware.contains("goldfish") ||
            hardware.contains("ranchu") ||
            (brand.startsWith("generic") && device.startsWith("generic"))
    }

    fun isProbablyRooted(
        buildTags: String?,
        detectedRootPaths: Set<String>,
        magiskDetected: Boolean
    ): Boolean {
        val testKeys = buildTags?.contains("test-keys", ignoreCase = true) == true
        return testKeys || detectedRootPaths.isNotEmpty() || magiskDetected
    }
}
