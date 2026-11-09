package com.neoproc.neoproccamx.recording

/**
 * RecordingSourceResolutionPlan — Codec-level recording resolution plan.
 *
 * Specifies the resolved width, height, and frame rate for the encoder
 * output surface. This plan is produced by [RecordingSourceResolutionPolicy]
 * and consumed by [RecordingManager] when configuring the MediaCodec encoder.
 *
 * Unlike the UI-layer `RecordingSourceResolutionPlan` (which tracks sensor
 * megapixels and cine-source metadata), this recording-layer plan is a
 * simple, validated output specification.
 *
 * All dimensions are pre-validated:
 *  - Width and height are encoder-alignment-compliant (mod 2/16)
 *  - FPS is within the hardware encoder's supported range
 *  - Values are in landscape orientation (width ≥ height)
 */
data class RecordingSourceResolutionPlan(
    /** Encoder output width in pixels (landscape). */
    val width: Int,

    /** Encoder output height in pixels (landscape). */
    val height: Int,

    /** Target frame rate in frames per second. */
    val fps: Int,

    /** Whether the encoder size was an exact match from the hardware catalog. */
    val encoderExactMatch: Boolean = false,

    /** Whether the camera hardware supports this exact output size. */
    val cameraExactMatch: Boolean = false,

    /** Codec MIME type string (e.g. "video/hevc", "video/avc"). */
    val codecMime: String = "video/hevc",

    /** Target bitrate in bits per second. */
    val targetBitrate: Int = 0,

    /** Resolution tier label for telemetry (e.g. "4K_UHD", "1080P"). */
    val tierLabel: String = ""
) {
    // ── Derived Properties ────────────────────────────────────────────────────

    /** Total pixel count. */
    val pixelCount: Long get() = width.toLong() * height.toLong()

    /** Megapixels. */
    val megapixels: Float get() = pixelCount / 1_000_000f

    /** Aspect ratio (width / height). */
    val aspectRatio: Float
        get() = if (height > 0) width.toFloat() / height.toFloat() else 1.777f

    /** Whether this is a 4K or higher resolution. */
    val is4KOrHigher: Boolean get() = maxOf(width, height) >= 3840

    /** Whether this is a DCI-resolution output (4096 wide). */
    val isDci: Boolean get() = width == 4096 || width == 2048

    /** Long edge of the resolution. */
    val longEdge: Int get() = maxOf(width, height)

    /** Short edge of the resolution. */
    val shortEdge: Int get() = minOf(width, height)

    /** Whether both encoder and camera reported exact support. */
    val isFullySupported: Boolean get() = encoderExactMatch && cameraExactMatch

    /** Human-readable resolution label (e.g. "3840×2160@30fps"). */
    val label: String
        get() = "${width}×${height}@${fps}fps" +
            (if (tierLabel.isNotEmpty()) " [$tierLabel]" else "") +
            (if (!isFullySupported) " (approximate)" else "")

    /** Diagnostic summary for logging. */
    val diagnosticSummary: String
        get() = "RecordingPlan: ${width}×${height}@${fps}fps " +
            "encoder=${if (encoderExactMatch) "exact" else "approx"} " +
            "camera=${if (cameraExactMatch) "exact" else "approx"} " +
            "codec=$codecMime " +
            (if (targetBitrate > 0) "bitrate=${targetBitrate / 1_000_000}Mbps " else "") +
            "tier=$tierLabel"
}