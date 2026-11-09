package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Npv10ReservationLifecycleContractTest {

    private val sourceRoot = listOf(
        File("src/main/java/com/neoproc/neoproccamx"),
        File("app/src/main/java/com/neoproc/neoproccamx")
    ).first { it.isDirectory }
    private val viewModelSource = File(sourceRoot, "ui/CameraViewModel.kt").readText()
    private val managerSource = File(sourceRoot, "recording/Npv10RecordingManager.kt").readText()

    @Test
    fun transientModeDetachRetainsPhysicalReservationWhileDetachingCameraOutput() {
        val prearmBody = viewModelSource
            .substringAfter("private fun requestRecordingSurfacePrearm(")
            .substringBefore("private fun requestNpv10CapturePrearm(")

        assertFalse(prearmBody.contains("discardPreparedPhysicalReservation"))
        assertTrue(prearmBody.contains("npv10CapturePrearmedSignature = null"))
        assertTrue(prearmBody.contains("camera.setNpv10TenBitCaptureEnabled(false)"))
        assertTrue(prearmBody.contains("reservation=retained"))
    }

    @Test
    fun reservationPreparationIsIdempotentAcrossRepeatedModeEntries() {
        val prepareBody = managerSource
            .substringAfter("fun preparePhysicalReservationAsync() {")
            .substringBefore("private fun discardPreparedPhysicalReservation()")

        assertTrue(prepareBody.contains("if (ready.isUsable()) return"))
        assertTrue(prepareBody.contains("if (reservationFuture != null) return"))
        assertEquals(1, prepareBody.windowed("Npv10PreparedReservationFactory.create".length)
            .count { it == "Npv10PreparedReservationFactory.create" })
    }

    @Test
    fun onlyManagerReleaseOwnsPreparedReservationDiscard() {
        assertFalse(managerSource.contains("discardPreparedPhysicalReservationAsync"))
        assertEquals(2, managerSource.windowed("discardPreparedPhysicalReservation()".length)
            .count { it == "discardPreparedPhysicalReservation()" })

        val releaseBody = managerSource
            .substringAfter("fun release() {")
            .substringBefore("private companion object")
        assertTrue(releaseBody.contains("discardPreparedPhysicalReservation()"))
        assertTrue(releaseBody.contains("reservationExecutor.shutdownNow()"))
    }
}
