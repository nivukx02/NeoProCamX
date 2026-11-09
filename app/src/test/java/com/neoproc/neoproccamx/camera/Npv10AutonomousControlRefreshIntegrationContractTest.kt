package com.neoproc.neoproccamx.camera

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Npv10AutonomousControlRefreshIntegrationContractTest {
    private val controller by lazy {
        File("src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt")
            .readText()
            .replace("\r\n", "\n")
    }

    @Test
    fun `startup replays autonomous controls before writer and never after stable commit`() {
        val startup = controller
            .substringAfter("internal fun startNpv10VideoCaptureWithProducerLease()")
            .substringBefore("private fun commitNpv10ProducerStartupLease(")
        val retainedReplay = controller
            .substringAfter("private fun replayDeferredNpv10ProducerStartupRefresh(")
            .substringBefore("private fun commitStableNpv10ProducerStartupLease(")
        val stableCommit = controller
            .substringAfter("private fun commitStableNpv10ProducerStartupLease(")
            .substringBefore("private fun cancelNpv10ProducerStartupLease(")

        assertTrue(startup.contains("npv10AutonomousControlRefreshGate.begin(acquired)"))
        assertTrue(retainedReplay.contains("claimReplayWhileRetained(lease)"))
        assertTrue(retainedReplay.contains("restartRepeatingRequestForRecordTargetChange(lease)"))
        assertTrue(stableCommit.contains("releaseAfterCadenceStabilization(lease)"))
        assertTrue(stableCommit.contains("npv10AutonomousControlRefreshGate.retire(lease)"))
        assertTrue(stableCommit.contains("autonomous.replayLatestControls"))
        assertTrue(stableCommit.contains("release.replayDeferredRefresh"))
        assertTrue(stableCommit.contains("NPV10_USER_CONTROL_LATE_BOUNDARY"))
        assertFalse(stableCommit.contains("npv10ProducerStartupLeaseGate.revoke("))
        assertFalse(stableCommit.contains("restartRepeatingRequestForRecordTargetChange"))
    }

    @Test
    fun `automatic exposure feedback uses coalescing path`() {
        val feedback = controller
            .substringAfter("override fun setHdrExposureSceneMetrics(")
            .substringBefore("override fun setSensorLinearNdrExposureSceneMetrics(")

        assertTrue(feedback.contains("updatePreviewFromAutonomousControlFeedback()"))
        assertFalse(feedback.contains("smoothedIndex != lastRequestedSdrExposureCompensationIndex) updatePreview()"))
    }

    @Test
    fun `user control mutations remain on strict request path`() {
        val userMutation = controller
            .substringAfter("private fun mutateControlState(")
            .substringBefore("private fun applyZoomAndAspectCrop(")

        assertTrue(userMutation.contains("updatePreview(immediate = immediate)"))
        assertFalse(userMutation.contains("updatePreviewFromAutonomousControlFeedback"))
    }
}
