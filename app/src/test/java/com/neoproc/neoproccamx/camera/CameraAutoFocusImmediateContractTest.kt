package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CameraAutoFocusImmediateContractTest {
    @Test
    fun explicitAutoFocusRestorationSubmitsImmediately() {
        val source = File("src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt").readText()
        val body = source
            .substringAfter("override fun setAutoFocus() {")
            .substringBefore("override fun setEV")
        assertTrue(body.contains("mutateControlState(immediate = true"))
    }
}
