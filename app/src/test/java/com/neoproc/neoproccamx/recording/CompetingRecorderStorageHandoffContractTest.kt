package com.neoproc.neoproccamx.recording

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class CompetingRecorderStorageHandoffContractTest {
    private val sourceRoot = listOf(
        File("src/main/java/com/neoproc/neoproccamx"),
        File("app/src/main/java/com/neoproc/neoproccamx")
    ).first { it.isDirectory }
    private val viewModelSource = File(sourceRoot, "ui/CameraViewModel.kt").readText()
    private val rawManagerSource = File(sourceRoot, "pipeline/RawVideoManager.kt").readText()
    private val npv10ManagerSource = File(sourceRoot, "recording/Npv10RecordingManager.kt").readText()

    @Test
    fun `raw start retires only the competing idle npv10 reservation first`() {
        val body = viewModelSource
            .substringAfter("private suspend fun startCustomRawRecordingSession(")
            .substringBefore("private suspend fun awaitStableCameraCadenceBeforeRecording(")
        val handoff = body.indexOf("npv10Recording.retireIdlePhysicalReservationForCompetingPipeline()")
        val writerStart = body.indexOf("rawVideoManager.startCapture(requestedFps)")

        assertTrue(handoff >= 0)
        assertTrue(writerStart > handoff)
    }

    @Test
    fun `npv10 start retires only the competing idle raw reservation first`() {
        val body = viewModelSource
            .substringAfter("private suspend fun startNpv10RecordingSession(")
            .substringBefore("private fun abortNpv10Startup(")
        val handoff = body.indexOf("rawVideoManager.retireIdleCaptureStorageForCompetingPipeline()")
        val writerStart = body.indexOf("npv10Recording.start(config)")

        assertTrue(handoff >= 0)
        assertTrue(writerStart > handoff)
    }

    @Test
    fun `both managers invalidate preparation and await their serial executor`() {
        val rawHandoff = rawManagerSource
            .substringAfter("fun retireIdleCaptureStorageForCompetingPipeline()")
            .substringBefore("private fun prewarmRealtimeWriter(")
        assertTrue(rawHandoff.contains("storagePreparationGeneration.incrementAndGet()"))
        assertTrue(rawHandoff.contains("idleStoragePreparationBlockedByCompetingPipeline = true"))
        assertTrue(rawHandoff.contains("preparedStorage = null"))
        assertTrue(rawHandoff.contains("StorageReservationHandoffBarrier.await("))
        assertTrue(rawHandoff.contains("discardPreparedStorage"))

        val npv10Handoff = npv10ManagerSource
            .substringAfter("fun retireIdlePhysicalReservationForCompetingPipeline()")
            .substringBefore("private fun discardPreparedPhysicalReservation()")
        assertTrue(npv10Handoff.contains("reservationGeneration.incrementAndGet()"))
        assertTrue(npv10Handoff.contains("idleReservationPreparationBlockedByCompetingPipeline = true"))
        assertTrue(npv10Handoff.contains("preparedReservation = null"))
        assertTrue(npv10Handoff.contains("StorageReservationHandoffBarrier.await("))
        assertTrue(npv10Handoff.contains("abandonLeasedReservation"))
    }
}
