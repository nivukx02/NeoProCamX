package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CameraControllerOrientationPublicationContractTest {
    private val source: String = listOf(
        File("src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt"),
        File("app/src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt")
    ).first { it.exists() }.readText()

    @Test
    fun deviceQuarterTurnPublishesFreshFrameGeometryForSecondaryPreviewDomains() {
        val body = source
            .substringAfter("override fun setDeviceRotationDegrees(degrees: Int)")
            .substringBefore("override fun resetPreviewActivityTracking()")

        val normalizeIndex = body.indexOf("OrientationManager.normalizeDegrees(degrees)")
        val assignmentIndex = body.indexOf("deviceRotationDegrees = normalizedDegrees")
        val publicationIndex = body.indexOf("refreshFrameGeometry()")

        assertTrue(normalizeIndex >= 0)
        assertTrue(assignmentIndex > normalizeIndex)
        assertTrue(publicationIndex > assignmentIndex)
    }
}
