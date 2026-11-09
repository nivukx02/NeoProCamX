package com.neoproc.neoproccamx.recording

internal object RawVideoContractPolicy {
    const val UNCOMPRESSED_RAW_BIT_DEPTH = 12
    val UNCOMPRESSED_12_BIT_MODE: RawCompressionMode = RawCompressionMode.HIGH_COMPRESSION

    /**
     * Realtime RAW-video default: direct sensor-code-exact Bayer with no prediction, quantization,
     * Deflate, or MediaCodec. Camera2 supplies a RAW16 transport word; sensors whose declared white
     * level fits 12 bits use a packed-12 storage container, while wider sensors retain RAW16 storage.
     * Every sensor code is retained bit-exact while avoiding unused transport padding. Runtime video
     * capture is locked to this mode: diagnostic overrides cannot downgrade the stored master.
     */
    val DEFAULT_VIDEO_CAPTURE_MODE: RawCompressionMode = RawCompressionMode.LOSSLESS_REALTIME

    // On-device A/B override (debug command). null => use DEFAULT_VIDEO_CAPTURE_MODE.
    @Volatile
    var captureModeOverride: RawCompressionMode? = null
        private set

    /**
     * RAW video CAPTURE boundary (applied at [com.neoproc.neoproccamx.pipeline.RawVideoManager] record
     * start). The UI selector normalizes every RAW request to the canonical uncompressed master (see
     * [canonicalUiCompressionMode]), so [requestedMode] is informational; capture applies the
     * strict realtime-lossless [DEFAULT_VIDEO_CAPTURE_MODE]. Debug preferences cannot change this
     * recording boundary.
     */
    fun resolveVideoCaptureMode(
        @Suppress("UNUSED_PARAMETER") requestedMode: RawCompressionMode
    ): RawCompressionMode = DEFAULT_VIDEO_CAPTURE_MODE

    /**
     * UI / preference canonicalization. The RAW compression selector is single-valued today, so any
     * stored value normalizes to the canonical [UNCOMPRESSED_12_BIT_MODE] for display and preference
     * round-trip. Kept byte-identical to the legacy behavior: the MotionCam-style compressed default is
     * applied ONLY at the capture boundary ([resolveVideoCaptureMode]) and never leaks into the settings
     * UI. The recorded RAW is self-describing (per-frame bit depth in the NPRAW header + the develop.meta
     * sidecar), so the offline converter uses the true storage depth regardless of this display label.
     */
    fun canonicalUiCompressionMode(
        @Suppress("UNUSED_PARAMETER") requestedMode: RawCompressionMode
    ): RawCompressionMode = UNCOMPRESSED_12_BIT_MODE

    /** Retains the requested diagnostic value for telemetry; strict capture intentionally ignores it. */
    fun applyCaptureModeOverride(name: String?) {
        val normalized = name?.trim()?.uppercase().orEmpty()
        captureModeOverride = if (normalized.isEmpty() || normalized == "DEFAULT") {
            null
        } else {
            RawCompressionMode.fromName(normalized)
        }
    }

    fun isUncompressedTwelveBitRaw(mode: RawCompressionMode): Boolean =
        mode == UNCOMPRESSED_12_BIT_MODE &&
            RawCompressionMode.getBitDepth(mode) == UNCOMPRESSED_RAW_BIT_DEPTH
}
