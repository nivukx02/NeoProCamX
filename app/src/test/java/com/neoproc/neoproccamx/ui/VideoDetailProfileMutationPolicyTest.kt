package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VideoDetailProfileMutationPolicyTest {
    @Test
    fun `synchronized profile request is an idempotent no-op`() {
        VideoDetailProfile.entries.forEach { profile ->
            assertEquals(
                VideoDetailProfileMutationPolicy.Decision.NO_OP,
                decide(profile, profile, profile, profile, recording = true)
            )
        }
    }

    @Test
    fun `recording lifecycle rejects every profile mutation and partial resync`() {
        VideoDetailProfile.entries.forEach { current ->
            VideoDetailProfile.entries.filterNot { it == current }.forEach { requested ->
                assertEquals(
                    VideoDetailProfileMutationPolicy.Decision.REJECT_RECORDING_ACTIVE,
                    decide(current, current, current, requested, recording = true)
                )
            }
            assertEquals(
                VideoDetailProfileMutationPolicy.Decision.REJECT_RECORDING_ACTIVE,
                decide(
                    current,
                    persisted = VideoDetailProfile.entries.first { it != current },
                    recorder = current,
                    requested = current,
                    recording = true
                )
            )
        }
    }

    @Test
    fun `idle lifecycle admits profile changes and repairs split state`() {
        assertEquals(
            VideoDetailProfileMutationPolicy.Decision.APPLY,
            decide(
                selected = VideoDetailProfile.NATURAL_DETAIL,
                persisted = VideoDetailProfile.NATURAL_DETAIL,
                recorder = VideoDetailProfile.NATURAL_DETAIL,
                requested = VideoDetailProfile.EXTREME_DETAIL,
                recording = false
            )
        )
        assertEquals(
            VideoDetailProfileMutationPolicy.Decision.APPLY,
            decide(
                selected = VideoDetailProfile.FOCUSED_DETAIL,
                persisted = VideoDetailProfile.NATURAL_DETAIL,
                recorder = VideoDetailProfile.FOCUSED_DETAIL,
                requested = VideoDetailProfile.FOCUSED_DETAIL,
                recording = false
            )
        )
    }

    @Test
    fun `view model orders admission bitrate refresh and prearm and scopes throughput by profile`() {
        val source = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
        val setter = source.substringAfter("fun setVideoDetailProfile(profile: VideoDetailProfile)")
            .substringBefore("fun setMaximumPhotoResolutionEnabled")

        val admission = setter.indexOf("VideoDetailProfileMutationPolicy.resolve")
        val persistentMutation = setter.indexOf("settingsController.setVideoDetailProfile(profile)")
        val bitrateRefresh = setter.indexOf("applyAutomaticVideoBitrateIfNeeded(force = true)")
        val prearm = setter.indexOf("requestRecordingSurfacePrearm(forceSessionRefresh = false)")
        assertTrue(admission >= 0)
        assertTrue("admission must precede persistence", admission < persistentMutation)
        assertTrue("bitrate must refresh before prearm", bitrateRefresh in 1 until prearm)

        val signature = source.substringAfter("private fun currentVulkanRecordingThroughputSignature()")
            .substringBefore("private fun hasValidatedVulkanRecordingEvidenceForCurrentSignature")
        assertTrue(signature.contains("_videoDetailProfile.value.name"))
    }

    private fun decide(
        selected: VideoDetailProfile,
        persisted: VideoDetailProfile,
        recorder: VideoDetailProfile,
        requested: VideoDetailProfile,
        recording: Boolean
    ): VideoDetailProfileMutationPolicy.Decision =
        VideoDetailProfileMutationPolicy.resolve(
            selectedProfile = selected,
            persistedProfile = persisted,
            recorderProfile = recorder,
            requestedProfile = requested,
            recordingLifecycleActive = recording
        )
}
