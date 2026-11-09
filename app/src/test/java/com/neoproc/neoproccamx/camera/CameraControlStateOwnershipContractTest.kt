package com.neoproc.neoproccamx.camera

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraControlStateOwnershipContractTest {
    @Test
    fun `control state is persisted before any camera-thread request dispatch`() {
        val source = loadCameraControllerSource()
        val body = source
            .substringAfter("private fun mutateControlState(")
            .substringBefore("private fun applyZoomAndAspectCrop(")

        assertFalse(
            "Durable control mutations must not be lost when CameraBackground is stopped",
            body.contains("runOnCameraThread {")
        )
        assertTrue(body.indexOf("stateMutation()") < body.indexOf("updatePreview(immediate = immediate)"))
        assertTrue(body.indexOf("paramsFlow.value = paramsMutation") < body.indexOf("updatePreview(immediate = immediate)"))
    }

    private fun loadCameraControllerSource(): String {
        val candidates = listOf(
            File("src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt"),
            File("app/src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt")
        )
        return candidates.firstOrNull(File::isFile)?.readText()
            ?: error("Unable to locate CameraController.kt")
    }
}
