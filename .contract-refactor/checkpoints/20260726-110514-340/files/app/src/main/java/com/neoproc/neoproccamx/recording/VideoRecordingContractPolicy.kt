package com.neoproc.neoproccamx.recording

internal object VideoRecordingContractPolicy {
    const val MAX_VIDEO_RECORD_FPS = 60

    fun sanitizeRequestedFps(requestedFps: Int): Int =
        requestedFps.coerceAtMost(MAX_VIDEO_RECORD_FPS)

    fun resolveRequestedCodec(requestedCodec: VideoCodecType): VideoCodecType =
        VideoCodecType.HEVC

    fun resolveTenBitRecordingRequested(
        cinemaMode: CinemaVideoMode,
        normalizedHdrMode: String
    ): Boolean {
        val hdrMode = RecordingHdrVideoLookPolicy.normalizeVideoHdrMode(normalizedHdrMode)
        return when {
            cinemaMode == CinemaVideoMode.RAW -> false
            hdrMode != "OFF" -> true
            else -> true
        }
    }

    // UI / preference facing: normalizes the RAW compression selector to its canonical value. The
    // MotionCam-style compressed default is applied only at the capture boundary
    // (RawVideoContractPolicy.resolveVideoCaptureMode, RawVideoManager record start), not here.
    fun resolveRawCompressionMode(requestedMode: RawCompressionMode): RawCompressionMode =
        RawVideoContractPolicy.canonicalUiCompressionMode(requestedMode)
}
