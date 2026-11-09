package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.recording.GpuRecordingState
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoRenderProfileQualityHintPolicyTest {

    @Test
    fun `video preview uses recording grade render quality before recording starts`() {
        assertTrue(
            VideoRenderProfileQualityHintPolicy.useRecordingGradeRenderProfile(
                appMode = AppMode.VIDEO,
                recordingState = GpuRecordingState.Idle
            )
        )
    }

    @Test
    fun `active recording always uses recording grade render quality`() {
        assertTrue(
            VideoRenderProfileQualityHintPolicy.useRecordingGradeRenderProfile(
                appMode = AppMode.PRO,
                recordingState = GpuRecordingState.Recording
            )
        )
    }

    @Test
    fun `pro sdr idle preview also uses recording grade render quality`() {
        assertTrue(
            VideoRenderProfileQualityHintPolicy.useRecordingGradeRenderProfile(
                appMode = AppMode.PRO,
                recordingState = GpuRecordingState.Idle
            )
        )
    }

    @Test
    fun `pro is a video surface for normal pro sdr parity`() {
        assertTrue(VideoRenderProfileQualityHintPolicy.isVideoSurface(AppMode.PRO))
    }
}
