package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.recording.GpuRecordingState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingStartBackendAdmissionPolicyTest {
    @Test
    fun `lagging stopping projection cannot veto retained restart after backend release`() {
        val coordinator = RecordingRestartAfterStopCoordinator()
        val stopLease = requireNotNull(coordinator.tryBeginStop())
        coordinator.requestStart()
        val replayPermit = (
            coordinator.releaseStop(stopLease) as
                RecordingRestartAfterStopCoordinator.StopReleaseDecision.ReplayReady
            ).permit
        val laggingObserverProjection: GpuRecordingState = GpuRecordingState.Stopping

        assertEquals(
            RecordingRestartAfterStopCoordinator.ReplayClaimDecision.CLAIMED,
            coordinator.claimReplay(replayPermit)
        )
        assertTrue(laggingObserverProjection is GpuRecordingState.Stopping)
        assertTrue(
            RecordingStartBackendAdmissionPolicy.allowsStart(
                authoritativeBackendActive = false
            )
        )
    }

    @Test
    fun `active authoritative backend still rejects recording start`() {
        assertFalse(
            RecordingStartBackendAdmissionPolicy.allowsStart(
                authoritativeBackendActive = true
            )
        )
    }

    @Test
    fun `view model claim uses authoritative backend and never projected controller admission`() {
        val source = File(
            "src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt"
        ).readText()
        val claim = source
            .substringAfter("private fun claimRecordingStartAdmission(): Boolean {")
            .substringBefore("private fun beginClaimedRecordingStart()")

        assertTrue(claim.contains("hasActiveRecordingBackend()"))
        assertTrue(claim.contains("RecordingStartBackendAdmissionPolicy.allowsStart("))
        assertFalse(claim.contains("recordingController.startRecording()"))
        assertFalse(claim.contains("gpuRecordState.value"))
    }
}
