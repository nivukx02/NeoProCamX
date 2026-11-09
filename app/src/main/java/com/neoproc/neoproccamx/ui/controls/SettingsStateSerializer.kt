package com.neoproc.neoproccamx.ui.controls

import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.RawCompressionMode
import com.neoproc.neoproccamx.recording.StabilizationMode
import com.neoproc.neoproccamx.recording.VideoBitrateMode
import com.neoproc.neoproccamx.recording.VideoCodecType
import com.neoproc.neoproccamx.ui.PreviewAspectRatio
import com.neoproc.neoproccamx.ui.overlays.GridMode

/**
 * s8-f03 (CP-U3) — typed settings state + a pure String round-trip serializer (no Android
 * SharedPreferences, so it is JVM-testable and cannot mutate live state). Persisting then restoring
 * yields the identical typed state; unknown enum tokens are rejected with a typed exception rather
 * than silently coerced.
 */
internal data class SettingsState(
    val mode: CinemaVideoMode,
    val hdrMode: String,
    val bitrateMode: VideoBitrateMode,
    val bitrateMbps: Int,
    val rawCompression: RawCompressionMode,
    val fps: Int,
    val codec: VideoCodecType,
    val stabilization: StabilizationMode,
    val grid: GridMode,
    val previewAspect: PreviewAspectRatio
)

internal object SettingsStateSerializer {

    fun serialize(state: SettingsState): String = listOf(
        "mode" to state.mode.name,
        "hdr" to state.hdrMode,
        "bitrateMode" to state.bitrateMode.name,
        "bitrateMbps" to state.bitrateMbps.toString(),
        "rawCompression" to state.rawCompression.name,
        "fps" to state.fps.toString(),
        "codec" to state.codec.name,
        "stabilization" to state.stabilization.name,
        "grid" to state.grid.name,
        "previewAspect" to state.previewAspect.name
    ).joinToString(separator = ";") { (k, v) -> "$k=$v" }

    fun deserialize(text: String): SettingsState {
        val map = text.split(";")
            .filter { it.isNotBlank() }
            .associate { entry ->
                val idx = entry.indexOf('=')
                require(idx > 0) { "Malformed settings entry: '$entry'" }
                entry.substring(0, idx) to entry.substring(idx + 1)
            }
        fun req(key: String): String =
            map[key] ?: throw IllegalArgumentException("Missing settings key: $key")
        return SettingsState(
            mode = enumValueOf<CinemaVideoMode>(req("mode")),
            hdrMode = req("hdr"),
            bitrateMode = enumValueOf<VideoBitrateMode>(req("bitrateMode")),
            bitrateMbps = req("bitrateMbps").toInt(),
            rawCompression = enumValueOf<RawCompressionMode>(req("rawCompression")),
            fps = req("fps").toInt(),
            codec = enumValueOf<VideoCodecType>(req("codec")),
            stabilization = enumValueOf<StabilizationMode>(req("stabilization")),
            grid = enumValueOf<GridMode>(req("grid")),
            previewAspect = enumValueOf<PreviewAspectRatio>(req("previewAspect"))
        )
    }
}
