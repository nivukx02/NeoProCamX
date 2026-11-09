package com.neoproc.neoproccamx.pipeline

/** Identifies only this app's private, unpublished NPRAW stream reservations. */
internal object RawPendingReservationPolicy {
    const val STREAM_DISPLAY_NAME = "stream.npraw"
    private val PHYSICAL_PENDING_STREAM_NAME = Regex("^\\.pending-[0-9]+-stream\\.npraw$")

    fun isOwnedPendingStream(
        displayName: String?,
        relativePath: String?,
        isPending: Boolean
    ): Boolean {
        val appStreamName = displayName == STREAM_DISPLAY_NAME ||
            displayName?.matches(PHYSICAL_PENDING_STREAM_NAME) == true
        if (!isPending || !appStreamName) return false
        val segments = relativePath
            ?.replace('\\', '/')
            ?.trim('/')
            ?.split('/')
            ?: return false
        return segments.size == 3 &&
            segments[0] in setOf("Documents", "DCIM") &&
            segments[1] == "CamX" &&
            segments[2].startsWith("NEO_RADIANCE_RAW_")
    }
}
