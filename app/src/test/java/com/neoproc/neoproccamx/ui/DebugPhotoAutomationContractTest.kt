package com.neoproc.neoproccamx.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugPhotoAutomationContractTest {
    @Test
    fun `debug photo command uses the real still pipeline and retries transient admission failures`() {
        val candidates = listOf(
            File("app/src/main/java/com/neoproc/neoproccamx/ui/MainActivity.kt"),
            File("src/main/java/com/neoproc/neoproccamx/ui/MainActivity.kt")
        )
        val source = candidates.first { it.isFile }.readText()
        val branch = source
            .substringAfter("DEBUG_ACTION_CAPTURE_PHOTO ->")
            .substringBefore("DEBUG_ACTION_START_RECORDING ->")

        assertTrue(source.contains("DEBUG_ACTION_CAPTURE_PHOTO = \"capture_photo\""))
        assertTrue(branch.contains("shouldRetryDebugActionUntilCameraReady(action)"))
        assertTrue(branch.contains("vm.setDebugValidationLabel(actionValidationLabel)"))
        assertTrue(branch.contains("vm.captureHighQualityPhoto()"))
        assertTrue(branch.contains("capture_photo_waiting_for_still_pipeline"))
    }
}
