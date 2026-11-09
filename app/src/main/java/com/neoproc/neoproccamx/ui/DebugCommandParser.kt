package com.neoproc.neoproccamx.ui

import android.util.Size
import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.RawCompressionMode
import com.neoproc.neoproccamx.recording.VideoRecordingContractPolicy
import java.util.Locale

internal data class DebugCommandExtras(
    val mode: String? = null,
    val preset: String? = null,
    val action: String? = null,
    val durationMs: String? = null,
    val raw: Boolean? = null,
    val log: Boolean? = null,
    val aspect: String? = null,
    val colorProfile: String? = null,
    val lutEnabled: Boolean? = null,
    val lutProfile: String? = null,
    val validationLabel: String? = null,
    val videoColorSpace: String? = null,
    val videoHdrMode: String? = null,
    val videoResolution: String? = null,
    val videoFps: String? = null,
    val videoMode: String? = null,
    val videoDetailProfile: String? = null,
    val rawCompression: String? = null,
    val uncompressedCinemaPipeline: Boolean? = null,
    val sdrConversionPipeline: Boolean? = null
)

internal data class ParsedDebugCommand(
    val mode: AppMode?,
    val preset: CameraModePreset?,
    val action: String?,
    val durationMs: Long,
    val raw: Boolean?,
    val log: Boolean?,
    val aspect: PreviewAspectRatio?,
    val colorProfile: String?,
    val lutEnabled: Boolean?,
    val lutProfile: String?,
    val validationLabel: String?,
    val videoColorSpace: String?,
    val videoHdrMode: String?,
    val videoResolution: Size?,
    val videoFps: Int?,
    val videoMode: CinemaVideoMode?,
    val videoDetailProfile: VideoDetailProfile?,
    val rawCompression: RawCompressionMode?,
    val uncompressedCinemaPipeline: Boolean?,
    val sdrConversionPipeline: Boolean?
)

internal object DebugCommandParser {
    const val DEFAULT_DEBUG_DURATION_MS = 2500L
    private const val MIN_DEBUG_DURATION_MS = 400L
    private const val MAX_DEBUG_DURATION_MS = 120_000L

    fun parse(extras: DebugCommandExtras): ParsedDebugCommand? {
        if (extras == DebugCommandExtras()) return null

        var mode = parseMode(extras.mode)
        val preset = parsePreset(extras.preset)
        val action = extras.action?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() }
        val durationMs = parseDurationMs(extras.durationMs)
        var raw = extras.raw
        var log = extras.log
        val aspect = parseAspect(extras.aspect)
        var colorProfile = extras.colorProfile
        val lutEnabled = extras.lutEnabled
        val lutProfile = extras.lutProfile
        val validationLabel = extras.validationLabel?.trim()?.takeIf { it.isNotEmpty() }
        var videoColorSpace = extras.videoColorSpace?.trim()?.takeIf { it.isNotEmpty() }
        var videoHdrMode = extras.videoHdrMode?.trim()?.takeIf { it.isNotEmpty() }
        val videoResolution = parseSize(extras.videoResolution)
        val videoFps = parseVideoFps(extras.videoFps)
        var videoMode = extras.videoMode?.let(CinemaVideoMode::fromName)
        val videoDetailProfile = parseVideoDetailProfile(extras.videoDetailProfile)
        val rawCompression = extras.rawCompression?.let(RawCompressionMode::fromName)

        when (preset) {
            CameraModePreset.VIDEO -> {
                // A public Video-rail preset always owns the VIDEO capture surface. An explicit
                // legacy `debug_mode=PRO` must not move a Pro-video request into the still-photo
                // AppMode.PRO pipeline after the preset has been applied.
                mode = AppMode.VIDEO
                log = log ?: false
                raw = raw ?: false
                videoHdrMode = videoHdrMode ?: "OFF"
                videoColorSpace = videoColorSpace ?: "Rec.709"
                videoMode = videoMode ?: CinemaVideoMode.PRO
                colorProfile = colorProfile ?: "Natural"
            }
            CameraModePreset.PRO -> {
                mode = AppMode.VIDEO
                log = log ?: false
                raw = raw ?: false
                videoHdrMode = videoHdrMode ?: "OFF"
                videoColorSpace = videoColorSpace ?: "Rec.709"
                videoMode = videoMode ?: CinemaVideoMode.PRO
                colorProfile = colorProfile ?: "Natural"
            }
            CameraModePreset.HDR -> {
                mode = AppMode.VIDEO
                log = log ?: false
                raw = raw ?: false
                videoHdrMode = videoHdrMode ?: "HDR10"
                videoColorSpace = videoColorSpace ?: "Rec.2020"
                videoMode = videoMode ?: CinemaVideoMode.PRO
                colorProfile = colorProfile ?: "Natural"
            }
            CameraModePreset.LOG_VIDEO -> {
                mode = mode ?: AppMode.VIDEO
                log = log ?: true
                raw = raw ?: false
                videoHdrMode = videoHdrMode ?: "OFF"
                videoColorSpace = videoColorSpace ?: "Rec.2020"
                videoMode = videoMode ?: CinemaVideoMode.LOG
                colorProfile = colorProfile ?: "N-Log"
            }
            CameraModePreset.RAW_MODE -> {
                mode = AppMode.VIDEO
                raw = raw ?: true
                log = log ?: false
                videoHdrMode = videoHdrMode ?: "OFF"
                videoColorSpace = videoColorSpace ?: "Rec.2020"
                videoMode = videoMode ?: CinemaVideoMode.RAW
                colorProfile = colorProfile ?: "Natural"
            }
            null -> Unit
        }

        return ParsedDebugCommand(
            mode = mode,
            preset = preset,
            action = action,
            durationMs = durationMs,
            raw = raw,
            log = log,
            aspect = aspect,
            colorProfile = colorProfile,
            lutEnabled = lutEnabled,
            lutProfile = lutProfile,
            validationLabel = validationLabel,
            videoColorSpace = videoColorSpace,
            videoHdrMode = videoHdrMode,
            videoResolution = videoResolution,
            videoFps = videoFps,
            videoMode = videoMode,
            videoDetailProfile = videoDetailProfile,
            rawCompression = rawCompression,
            uncompressedCinemaPipeline = extras.uncompressedCinemaPipeline,
            sdrConversionPipeline = extras.sdrConversionPipeline
        )
    }

    private fun parseDurationMs(value: String?): Long =
        (value?.toLongOrNull() ?: DEFAULT_DEBUG_DURATION_MS)
            .coerceIn(MIN_DEBUG_DURATION_MS, MAX_DEBUG_DURATION_MS)

    private fun parseMode(value: String?): AppMode? =
        when (value?.trim()?.uppercase(Locale.US)) {
            "VIDEO" -> AppMode.VIDEO
            "PRO" -> AppMode.PRO
            else -> null
        }

    private fun parseAspect(value: String?): PreviewAspectRatio? =
        when (value?.trim()?.uppercase(Locale.US)) {
            "AUTO", "FULL", "2.39", "CINEMATIC" -> PreviewAspectRatio.AUTO
            "1:1", "ONE_ONE", "SQUARE" -> PreviewAspectRatio.SQUARE
            "4:3", "FOUR_THREE" -> PreviewAspectRatio.FOUR_THREE
            "16:9", "SIXTEEN_NINE" -> PreviewAspectRatio.SIXTEEN_NINE
            else -> null
        }

    private fun parsePreset(value: String?): CameraModePreset? =
        when (value?.trim()?.uppercase(Locale.US)) {
            "VIDEO", "NORMAL", "PHOTO" -> CameraModePreset.VIDEO
            "PRO" -> CameraModePreset.PRO
            "HDR" -> CameraModePreset.HDR
            "LOG_VIDEO", "LOGVIDEO", "LOG" -> CameraModePreset.LOG_VIDEO
            "RAW", "RAW_MODE", "RAWMODE" -> CameraModePreset.RAW_MODE
            else -> null
        }

    private fun parseSize(value: String?): Size? {
        val parts = value?.trim()?.lowercase(Locale.US)?.split("x") ?: return null
        if (parts.size != 2) return null
        val width = parts[0].trim().toIntOrNull() ?: return null
        val height = parts[1].trim().toIntOrNull() ?: return null
        if (width <= 0 || height <= 0) return null
        return Size(width, height)
    }

    private fun parseVideoFps(value: String?): Int? {
        val parsed = value?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        return VideoRecordingContractPolicy.sanitizeRequestedFps(parsed).coerceAtLeast(1)
    }

    private fun parseVideoDetailProfile(value: String?): VideoDetailProfile? =
        value
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let(VideoDetailProfile::fromPreference)
}
