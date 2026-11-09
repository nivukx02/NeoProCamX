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
    val codec: VideoCodecType
)

internal sealed interface SettingsInvariantResult {
    object Valid : SettingsInvariantResult
    data class Rejected(val ruleId: String, val message: String) : SettingsInvariantResult
}

internal object SettingsInvariantResolver {

    /** RAW master transport is the uncompressed packed 12-bit Bayer payload. */
    private val RAW_REQUIRED_COMPRESSION = RawCompressionMode.HIGH_COMPRESSION

    fun validate(commit: SettingsCommit): SettingsInvariantResult {
        if (commit.fps <= 0) {
            return SettingsInvariantResult.Rejected("FPS_MUST_BE_POSITIVE", "Frame rate must be positive")
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
        return SettingsInvariantResult.Valid
    }
}
