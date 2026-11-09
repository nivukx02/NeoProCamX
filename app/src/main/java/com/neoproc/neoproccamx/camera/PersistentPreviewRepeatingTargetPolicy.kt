package com.neoproc.neoproccamx.camera

/**
 * Keeps a configured SDR/HDR monitor pair in an idle repeating request only when Camera2 reports
 * the two dynamic-range profiles can coexist. During recording it targets only the active monitor
 * input: asking the ISP to produce an invisible second preview stream wastes sustained bandwidth
 * and thermal headroom.
 *
 * The visible owner is selected downstream by the persistent preview router. On compatible devices,
 * changing visible ownership mutates Camera2 request targets only while recording. On a constrained
 * device, the one-target retarget is always the only legal operation. Neither case recreates the
 * configured Camera2 session.
 */
internal object PersistentPreviewRepeatingTargetPolicy {
    fun <T : Any> resolve(
        activePreview: T?,
        persistentSdrPreview: T?,
        persistentHdrPreview: T?,
        profilesCanShareRequest: Boolean = true,
        recordingActive: Boolean = false
    ): List<T> {
        if (!recordingActive &&
            profilesCanShareRequest &&
            persistentSdrPreview != null &&
            persistentHdrPreview != null &&
            persistentSdrPreview !== persistentHdrPreview
        ) {
            return listOf(persistentSdrPreview, persistentHdrPreview)
        }
        return activePreview?.let(::listOf).orEmpty()
    }

    /** Whether a logical visible-owner handoff also requires a Camera2 request retarget. */
    fun requiresHandoffRetarget(
        profilesCanShareRequest: Boolean,
        recordingActive: Boolean
    ): Boolean = recordingActive || !profilesCanShareRequest
}

/**
 * Selects the persistent preview outputs that are attached to a Camera2 session.
 *
 * Idle preview keeps both fixed-profile outputs configured so an SDR/HDR handoff can be immediate.
 * A sustained Direct recording already owns a full-resolution hardware-import stream, so it attaches
 * only the visible preview output. The inactive Surface remains alive and is restored by the normal
 * post-record session rebuild; no resolution, frame-rate, bit-depth, bitrate, colour, or photo output
 * is changed.
 */
internal object PersistentPreviewSessionOutputPolicy {
    fun <T : Any> resolve(
        activePreview: T?,
        persistentSdrPreview: T?,
        persistentHdrPreview: T?,
        constrainToVisiblePreview: Boolean
    ): List<T> {
        val validPersistentPair =
            persistentSdrPreview != null &&
                persistentHdrPreview != null &&
                persistentSdrPreview !== persistentHdrPreview
        if (!constrainToVisiblePreview && validPersistentPair) {
            return listOf(persistentSdrPreview, persistentHdrPreview)
        }

        // Active ownership is authoritative. The fallbacks prevent an empty Camera2 graph during
        // the narrow router handoff window without ever attaching two outputs in constrained mode.
        return (activePreview ?: persistentSdrPreview ?: persistentHdrPreview)
            ?.let(::listOf)
            .orEmpty()
    }
}

/**
 * Interprets Camera2's per-profile capture-request constraints for a fixed SDR/PQ preview pair.
 * An empty constraint set means unrestricted; otherwise each profile must explicitly admit the
 * other. Unsupported pairs stay configured for zero-session-churn switching, but only one is
 * targeted by any repeating request at a time.
 */
internal object PersistentPreviewDynamicRangeCoexistencePolicy {
    fun canShareRequest(
        firstProfile: Long,
        firstConstraints: Set<Long>,
        secondProfile: Long,
        secondConstraints: Set<Long>
    ): Boolean {
        if (firstProfile == secondProfile) return true
        val firstAdmitsSecond = firstConstraints.isEmpty() || secondProfile in firstConstraints
        val secondAdmitsFirst = secondConstraints.isEmpty() || firstProfile in secondConstraints
        return firstAdmitsSecond && secondAdmitsFirst
    }
}
