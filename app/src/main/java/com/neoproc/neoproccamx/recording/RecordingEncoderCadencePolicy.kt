package com.neoproc.neoproccamx.recording

/**
 * Realtime encoder cadence contract shared by every encoded video mode.
 *
 * Sub-second periodic IDR requests combined with cyclic intra refresh caused the Qualcomm HEVC
 * encoder to emit only the IDR cadence (8.33 fps from a 25 fps input). A one-second GOP retains
 * precise seeking while allowing inter prediction to spend the unchanged bitrate on image quality.
 * Periodic IDR and cyclic intra refresh are mutually redundant here, so only periodic IDR is used.
 */
internal object RecordingEncoderCadencePolicy {
    const val MIN_REALTIME_GOP_SECONDS = 1f
    const val FRAME_DROP_DISABLED = 0
    private const val API_31 = 31

    data class Plan(
        val iFrameIntervalSeconds: Float,
        val intraRefreshPeriodFrames: Int?,
        val allowFrameDrop: Int?
    )

    fun resolve(
        requestedIFrameIntervalSeconds: Float,
        sdkInt: Int,
        surfaceInput: Boolean
    ): Plan {
        val interval = sanitizeIFrameInterval(requestedIFrameIntervalSeconds)
        return Plan(
            iFrameIntervalSeconds = interval,
            intraRefreshPeriodFrames = null,
            allowFrameDrop = if (surfaceInput && sdkInt >= API_31) FRAME_DROP_DISABLED else null
        )
    }

    fun sanitizeIFrameInterval(requestedSeconds: Float): Float = when {
        !requestedSeconds.isFinite() -> MIN_REALTIME_GOP_SECONDS
        requestedSeconds <= 0f -> 0f
        else -> requestedSeconds.coerceAtLeast(MIN_REALTIME_GOP_SECONDS)
    }
}
