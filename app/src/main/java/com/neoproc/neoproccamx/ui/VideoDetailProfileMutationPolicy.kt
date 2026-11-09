package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.pipeline.VideoDetailProfile

/**
 * Atomic admission policy for detail-profile changes.
 *
 * A profile selects Camera2 request tuning, the GPU uniform packet and the encoder quality budget.
 * Changing only part of that contract during an active recording can mix two profiles in one clip
 * or reuse throughput evidence measured for a different workload. Keep the current profile intact
 * until every recording/start/stop/finalization owner has released the session.
 */
internal object VideoDetailProfileMutationPolicy {
    enum class Decision {
        NO_OP,
        APPLY,
        REJECT_RECORDING_ACTIVE
    }

    fun resolve(
        selectedProfile: VideoDetailProfile,
        persistedProfile: VideoDetailProfile,
        recorderProfile: VideoDetailProfile,
        requestedProfile: VideoDetailProfile,
        recordingLifecycleActive: Boolean
    ): Decision {
        val alreadyAppliedEverywhere = selectedProfile == requestedProfile &&
            persistedProfile == requestedProfile &&
            recorderProfile == requestedProfile
        if (alreadyAppliedEverywhere) return Decision.NO_OP
        if (recordingLifecycleActive) return Decision.REJECT_RECORDING_ACTIVE
        return Decision.APPLY
    }
}

/** Immutable diagnostic snapshot used by production telemetry and physical-device QA. */
internal data class VideoDetailProfileRuntimeEvidence(
    val selectedProfile: VideoDetailProfile,
    val persistedProfile: VideoDetailProfile,
    val recorderProfile: VideoDetailProfile,
    val gpuProfileId: Float?,
    val spatialDenoise: Float?,
    val chromaDenoise: Float?,
    val sharpening: Float?,
    val subjectDetailGain: Float?,
    val enhancementSignature: String,
    val throughputSignature: String,
    val requestedBitrateBps: Int,
    val appliedBitrateBps: Int,
    val cameraRequestEvidence: Map<String, String>
)
