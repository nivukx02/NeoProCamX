package com.neoproc.neoproccamx.ui.controls

import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.RawCompressionMode
import com.neoproc.neoproccamx.recording.VideoBitrateMode
import com.neoproc.neoproccamx.recording.VideoCodecType

/**
 * s8-f03 (CP-U3) — pure resolver that rejects illegal cross-section settings combinations BEFORE they
 * are committed, so the camera can never enter an unprovable/contradictory state. It only REJECTS
 * illegal commits; every legal current selection returns [SettingsInvariantResult.Valid] unchanged.
 */
internal data class SettingsCommit(
    val mode: CinemaVideoMode,
    val hdrMode: String,
    val bitrateMode: VideoBitrateMode,
    val rawCompression: RawCompressionMode,
    val fps: Int,
    val codec: VideoCodecType,
    val width: Int = 3840,
    val height: Int = 2160,
    val customBitrateBps: Int = 240_000_000,
    val encodedTenBitRequested: Boolean = mode != CinemaVideoMode.RAW
)

internal sealed interface SettingsInvariantResult {
    object Valid : SettingsInvariantResult
    data class Rejected(val ruleId: String, val message: String) : SettingsInvariantResult
}

/**
 * Monotonic, UI-consumable proof that a settings request was rejected before any capture state was
 * changed. [sequence] makes repeated requests observable even when they violate the same rule.
 */
data class SettingsRejectionNotice(
    val sequence: Long,
    val ruleId: String,
    val source: String,
    val message: String
)

internal object SettingsInvariantResolver {

    /** RAW master transport is the uncompressed packed 12-bit Bayer payload. */
    private val RAW_REQUIRED_COMPRESSION = RawCompressionMode.HIGH_COMPRESSION
    private val SUPPORTED_RECORDING_FPS = setOf(24, 25, 30, 48, 50, 60)
    private const val MAX_VIDEO_DIMENSION = 16_384
    private const val MAX_CUSTOM_BITRATE_BPS = 2_000_000_000

    fun validate(commit: SettingsCommit): SettingsInvariantResult {
        if (commit.fps <= 0) {
            return SettingsInvariantResult.Rejected(
                "FPS_MUST_BE_POSITIVE",
                "Frame rate must be positive"
            )
        }
        if (commit.fps > SUPPORTED_RECORDING_FPS.max()) {
            return SettingsInvariantResult.Rejected(
                "FPS_EXCEEDS_PRODUCT_LIMIT",
                "${commit.fps} FPS exceeds the verified 60 FPS recording contract"
            )
        }
        if (commit.fps !in SUPPORTED_RECORDING_FPS) {
            return SettingsInvariantResult.Rejected(
                "FPS_NOT_A_VERIFIED_CADENCE",
                "${commit.fps} FPS is not a verified recording cadence; choose 24, 25, 30, 48, 50, or 60 FPS"
            )
        }
        if (commit.width <= 0 || commit.height <= 0) {
            return SettingsInvariantResult.Rejected(
                "RESOLUTION_MUST_BE_POSITIVE",
                "Recording resolution must have positive width and height"
            )
        }
        if (commit.width > MAX_VIDEO_DIMENSION || commit.height > MAX_VIDEO_DIMENSION) {
            return SettingsInvariantResult.Rejected(
                "RESOLUTION_EXCEEDS_PRODUCT_LIMIT",
                "${commit.width}x${commit.height} exceeds the verified ${MAX_VIDEO_DIMENSION}px dimension limit"
            )
        }
        if (!RecordingResolutionFpsContract.accepts(commit.width, commit.height, commit.fps)) {
            return SettingsInvariantResult.Rejected(
                "FPS_EXCEEDS_RESOLUTION_LIMIT",
                "${commit.width}x${commit.height} is limited to " +
                    "${RecordingResolutionFpsContract.maxFps(commit.width, commit.height)} FPS"
            )
        }
        if (commit.bitrateMode == VideoBitrateMode.CUSTOM && commit.customBitrateBps <= 0) {
            return SettingsInvariantResult.Rejected(
                "CUSTOM_BITRATE_MUST_BE_POSITIVE",
                "Custom bitrate must be greater than zero"
            )
        }
        if (commit.bitrateMode == VideoBitrateMode.CUSTOM &&
            commit.customBitrateBps > MAX_CUSTOM_BITRATE_BPS
        ) {
            return SettingsInvariantResult.Rejected(
                "CUSTOM_BITRATE_EXCEEDS_PRODUCT_LIMIT",
                "Custom bitrate cannot exceed 2000 Mbps"
            )
        }
        if (commit.mode == CinemaVideoMode.RAW) {
            if (!commit.hdrMode.equals("OFF", ignoreCase = true)) {
                return SettingsInvariantResult.Rejected(
                    "RAW_EXCLUDES_HDR",
                    "RAW capture is sensor-original and cannot also carry an HDR (${commit.hdrMode}) transport"
                )
            }
            if (commit.rawCompression != RAW_REQUIRED_COMPRESSION) {
                return SettingsInvariantResult.Rejected(
                    "RAW_PINS_UNCOMPRESSED_12BIT",
                    "RAW master requires the uncompressed 12-bit payload (${RAW_REQUIRED_COMPRESSION.name}), not ${commit.rawCompression.name}"
                )
            }
        }
        if (commit.mode == CinemaVideoMode.LOG && !commit.hdrMode.equals("OFF", ignoreCase = true)) {
            return SettingsInvariantResult.Rejected(
                "LOG_EXCLUDES_HDR",
                "LOG capture cannot be combined with an XDR display transform"
            )
        }
        if (commit.codec != VideoCodecType.HEVC) {
            return SettingsInvariantResult.Rejected(
                "STRICT_MAIN10_REQUIRES_HEVC",
                "The strict 10-bit recording contract requires HEVC Main10, not ${commit.codec.name}"
            )
        }
        if (commit.mode != CinemaVideoMode.RAW && !commit.encodedTenBitRequested) {
            return SettingsInvariantResult.Rejected(
                "STRICT_PROCESSED_VIDEO_REQUIRES_10_BIT",
                "Video and Pro recording must keep the verified 10-bit pipeline enabled"
            )
        }
        return SettingsInvariantResult.Valid
    }
}
