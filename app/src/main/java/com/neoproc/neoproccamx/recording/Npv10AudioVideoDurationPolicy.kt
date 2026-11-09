package com.neoproc.neoproccamx.recording

internal data class Npv10AudioVideoDurationAudit(
    val videoFrameCount: Long,
    val videoFps: Int,
    val videoDurationUs: Long,
    val audioDurationUs: Long,
    val absoluteDeltaUs: Long,
    val maximumAllowedDeltaUs: Long,
    val passed: Boolean
)

/** Final publication gate for the independently scheduled NPV10 video and AAC timelines. */
internal object Npv10AudioVideoDurationPolicy {
    // Final clip integrity target: substantially tighter than one 25 fps video frame. This is a
    // publication gate, not merely telemetry, so a visibly drifting sidecar is never presented as
    // a valid synchronized master.
    const val MAXIMUM_ALLOWED_DELTA_US = 20_000L

    fun audit(
        videoFrameCount: Long,
        videoFps: Int,
        audioDurationUs: Long
    ): Npv10AudioVideoDurationAudit {
        require(videoFrameCount > 0L) { "NPV10 A/V audit requires at least one video frame" }
        require(videoFps > 0) { "NPV10 A/V audit requires a positive video frame rate" }
        require(audioDurationUs >= 0L) { "NPV10 A/V audit requires a non-negative audio duration" }
        val videoDurationUs = Npv10ExportCadencePolicy.endPresentationTimeUs(
            frameCount = videoFrameCount,
            fps = videoFps
        )
        val absoluteDeltaUs = if (audioDurationUs >= videoDurationUs) {
            audioDurationUs - videoDurationUs
        } else {
            videoDurationUs - audioDurationUs
        }
        return Npv10AudioVideoDurationAudit(
            videoFrameCount = videoFrameCount,
            videoFps = videoFps,
            videoDurationUs = videoDurationUs,
            audioDurationUs = audioDurationUs,
            absoluteDeltaUs = absoluteDeltaUs,
            maximumAllowedDeltaUs = MAXIMUM_ALLOWED_DELTA_US,
            passed = absoluteDeltaUs <= MAXIMUM_ALLOWED_DELTA_US
        )
    }
}
