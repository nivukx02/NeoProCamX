package com.neoproc.neoproccamx.camera

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Test

class CameraSafeHalAccessContractTest {

    @Test
    fun `production camera code does not bypass hidden api or spoof oem packages`() {
        val productionCameraSource = File("src/main/java/com/neoproc/neoproccamx/camera")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .joinToString(separator = "\n") { it.readText() }

        val forbiddenTokens = listOf(
            "HiddenApiBypass",
            "AndroidHiddenApiUnsealer",
            "setHiddenApiExemptions",
            "VMRuntime",
            "ContextImpl",
            "mBasePackageName",
            "mOpPackageName",
            "CameraContextWhitelaster",
            "CameraContextWhitelister",
            "VendorTagInjector",
            "com.android.camera",
            "com.oneplus.camera"
        )

        forbiddenTokens.forEach { token ->
            assertFalse("Forbidden HAL bypass token found: $token", productionCameraSource.contains(token))
        }
    }
}
