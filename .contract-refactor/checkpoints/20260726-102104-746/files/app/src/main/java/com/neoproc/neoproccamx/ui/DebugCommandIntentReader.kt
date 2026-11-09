package com.neoproc.neoproccamx.ui

import android.content.Intent
import android.os.Bundle

internal data class DebugCommandIntentValues(
    val mode: String? = null,
    val preset: String? = null,
    val action: String? = null,
    val durationString: String? = null,
    val durationLong: Long? = null,
    val durationInt: Int? = null,
    val durationText: CharSequence? = null,
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
    val videoFpsLong: Long? = null,
    val videoFpsInt: Int? = null,
    val videoFpsText: CharSequence? = null,
    val videoMode: String? = null,
    val videoDetailProfile: String? = null,
    val rawCompression: String? = null,
    val uncompressedCinemaPipeline: Boolean? = null
)

internal object DebugCommandIntentReader {
    const val EXTRA_DEBUG_MODE = "debug_mode"
    const val EXTRA_DEBUG_PRESET = "debug_preset"
    const val EXTRA_DEBUG_ACTION = "debug_action"
    const val EXTRA_DEBUG_DURATION_MS = "debug_duration_ms"
    const val EXTRA_DEBUG_RAW = "debug_raw"
    const val EXTRA_DEBUG_LOG = "debug_log"
    const val EXTRA_DEBUG_ASPECT = "debug_aspect"
    const val EXTRA_DEBUG_COLOR_PROFILE = "debug_color_profile"
    const val EXTRA_DEBUG_LUT_ENABLED = "debug_lut_enabled"
    const val EXTRA_DEBUG_LUT_PROFILE = "debug_lut_profile"
    const val EXTRA_DEBUG_VALIDATION_LABEL = "debug_validation_label"
    const val EXTRA_DEBUG_VIDEO_COLOR_SPACE = "debug_video_color_space"
    const val EXTRA_DEBUG_VIDEO_HDR_MODE = "debug_video_hdr_mode"
    const val EXTRA_DEBUG_VIDEO_RESOLUTION = "debug_video_resolution"
    const val EXTRA_DEBUG_VIDEO_FPS = "debug_video_fps"
    const val EXTRA_DEBUG_VIDEO_MODE = "debug_video_mode"
    const val EXTRA_DEBUG_VIDEO_DETAIL_PROFILE = "debug_video_detail_profile"
    const val EXTRA_DEBUG_RAW_COMPRESSION = "debug_raw_compression"
    const val EXTRA_DEBUG_UNCOMPRESSED_CINEMA_PIPELINE = "debug_uncompressed_cinema_pipeline"
    const val EXTRA_UNCOMPRESSED_CINEMA_PIPELINE = "uncompressed_cinema_pipeline"

    private val DEBUG_EXTRA_KEYS = setOf(
        EXTRA_DEBUG_MODE,
        EXTRA_DEBUG_PRESET,
        EXTRA_DEBUG_ACTION,
        EXTRA_DEBUG_DURATION_MS,
        EXTRA_DEBUG_RAW,
        EXTRA_DEBUG_LOG,
        EXTRA_DEBUG_ASPECT,
        EXTRA_DEBUG_COLOR_PROFILE,
        EXTRA_DEBUG_LUT_ENABLED,
        EXTRA_DEBUG_LUT_PROFILE,
        EXTRA_DEBUG_VALIDATION_LABEL,
        EXTRA_DEBUG_VIDEO_COLOR_SPACE,
        EXTRA_DEBUG_VIDEO_HDR_MODE,
        EXTRA_DEBUG_VIDEO_RESOLUTION,
        EXTRA_DEBUG_VIDEO_FPS,
        EXTRA_DEBUG_VIDEO_MODE,
        EXTRA_DEBUG_VIDEO_DETAIL_PROFILE,
        EXTRA_DEBUG_RAW_COMPRESSION,
        EXTRA_DEBUG_UNCOMPRESSED_CINEMA_PIPELINE,
        EXTRA_UNCOMPRESSED_CINEMA_PIPELINE
    )

    fun hasDebugExtraKey(keys: Set<String>): Boolean =
        DEBUG_EXTRA_KEYS.any(keys::contains)

    fun hasDebugCommandExtras(intent: Intent): Boolean =
        DEBUG_EXTRA_KEYS.any(intent::hasExtra)

    fun hasDebugActionExtra(intent: Intent): Boolean =
        intent.hasExtra(EXTRA_DEBUG_ACTION)

    fun isDebugCommandAction(action: String?): Boolean =
        action == DebugCommandReceiver.ACTION_DEBUG_COMMAND

    fun isDebugCommandIntent(intent: Intent): Boolean =
        isDebugCommandAction(intent.action) && hasDebugCommandExtras(intent)

    fun readParsedCommand(
        intent: Intent,
        normalizeColorProfile: (String) -> String?,
        normalizeLutProfile: (String) -> String?
    ): ParsedDebugCommand? =
        readParsedCommand(
            values = intent.readDebugCommandValues(),
            normalizeColorProfile = normalizeColorProfile,
            normalizeLutProfile = normalizeLutProfile
        )

    fun readParsedCommand(
        values: DebugCommandIntentValues,
        normalizeColorProfile: (String) -> String? = ::identity,
        normalizeLutProfile: (String) -> String? = ::identity
    ): ParsedDebugCommand? =
        DebugCommandParser.parse(
            readExtras(
                values = values,
                normalizeColorProfile = normalizeColorProfile,
                normalizeLutProfile = normalizeLutProfile
            )
        )

    fun readExtras(
        values: DebugCommandIntentValues,
        normalizeColorProfile: (String) -> String? = ::identity,
        normalizeLutProfile: (String) -> String? = ::identity
    ): DebugCommandExtras =
        DebugCommandExtras(
            mode = values.mode,
            preset = values.preset,
            action = values.action,
            durationMs = durationMsText(values),
            raw = values.raw,
            log = values.log,
            aspect = values.aspect,
            colorProfile = values.colorProfile?.let(normalizeColorProfile),
            lutEnabled = values.lutEnabled,
            lutProfile = values.lutProfile?.let(normalizeLutProfile),
            validationLabel = values.validationLabel,
            videoColorSpace = values.videoColorSpace,
            videoHdrMode = values.videoHdrMode,
            videoResolution = values.videoResolution,
            videoFps = videoFpsText(values),
            videoMode = values.videoMode,
            videoDetailProfile = values.videoDetailProfile,
            rawCompression = values.rawCompression,
            uncompressedCinemaPipeline = values.uncompressedCinemaPipeline
        )

    private fun Intent.readDebugCommandValues(): DebugCommandIntentValues {
        val bundle = extras
        fun value(key: String): Any? = bundle?.rawValueOrNull(key)
        val durationValue = value(EXTRA_DEBUG_DURATION_MS)
        val videoFpsValue = value(EXTRA_DEBUG_VIDEO_FPS)
        return DebugCommandIntentValues(
            mode = coerceStringExtraValue(value(EXTRA_DEBUG_MODE)),
            preset = coerceStringExtraValue(value(EXTRA_DEBUG_PRESET)),
            action = coerceStringExtraValue(value(EXTRA_DEBUG_ACTION)),
            durationString = coerceStringExtraValue(durationValue),
            durationLong = coerceLongExtraValue(durationValue),
            durationInt = coerceIntExtraValue(durationValue),
            durationText = coerceCharSequenceExtraValue(durationValue),
            raw = coerceBooleanExtraValue(value(EXTRA_DEBUG_RAW)),
            log = coerceBooleanExtraValue(value(EXTRA_DEBUG_LOG)),
            aspect = coerceStringExtraValue(value(EXTRA_DEBUG_ASPECT)),
            colorProfile = coerceStringExtraValue(value(EXTRA_DEBUG_COLOR_PROFILE)),
            lutEnabled = coerceBooleanExtraValue(value(EXTRA_DEBUG_LUT_ENABLED)),
            lutProfile = coerceStringExtraValue(value(EXTRA_DEBUG_LUT_PROFILE)),
            validationLabel = coerceStringExtraValue(value(EXTRA_DEBUG_VALIDATION_LABEL)),
            videoColorSpace = coerceStringExtraValue(value(EXTRA_DEBUG_VIDEO_COLOR_SPACE)),
            videoHdrMode = coerceStringExtraValue(value(EXTRA_DEBUG_VIDEO_HDR_MODE)),
            videoResolution = coerceStringExtraValue(value(EXTRA_DEBUG_VIDEO_RESOLUTION)),
            videoFps = coerceStringExtraValue(videoFpsValue),
            videoFpsLong = coerceLongExtraValue(videoFpsValue),
            videoFpsInt = coerceIntExtraValue(videoFpsValue),
            videoFpsText = coerceCharSequenceExtraValue(videoFpsValue),
            videoMode = coerceStringExtraValue(value(EXTRA_DEBUG_VIDEO_MODE)),
            videoDetailProfile = coerceStringExtraValue(value(EXTRA_DEBUG_VIDEO_DETAIL_PROFILE)),
            rawCompression = coerceStringExtraValue(value(EXTRA_DEBUG_RAW_COMPRESSION)),
            uncompressedCinemaPipeline = coerceBooleanExtraValue(
                value(EXTRA_DEBUG_UNCOMPRESSED_CINEMA_PIPELINE)
                    ?: value(EXTRA_UNCOMPRESSED_CINEMA_PIPELINE)
            )
        )
    }

    private fun durationMsText(values: DebugCommandIntentValues): String? =
        values.durationString
            ?: values.durationLong?.toString()
            ?: values.durationInt?.toString()
            ?: values.durationText?.toString()

    private fun videoFpsText(values: DebugCommandIntentValues): String? =
        values.videoFps
            ?: values.videoFpsLong?.toString()
            ?: values.videoFpsInt?.toString()
            ?: values.videoFpsText?.toString()

    private fun identity(value: String): String = value

    internal fun coerceLongExtraValue(value: Any?): Long? =
        when (value) {
            is Long -> value
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            is CharSequence -> value.toString().toLongOrNull()
            else -> null
        }

    private fun coerceIntExtraValue(value: Any?): Int? =
        when (value) {
            is Int -> value
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            is CharSequence -> value.toString().toIntOrNull()
            else -> null
        }

    private fun coerceBooleanExtraValue(value: Any?): Boolean? =
        when (value) {
            is Boolean -> value
            is String -> value.toBooleanStrictOrNull()
            is CharSequence -> value.toString().toBooleanStrictOrNull()
            else -> null
        }

    private fun coerceCharSequenceExtraValue(value: Any?): CharSequence? =
        value as? CharSequence

    private fun coerceStringExtraValue(value: Any?): String? =
        when (value) {
            is String -> value
            is CharSequence -> value.toString()
            else -> null
        }

    @Suppress("DEPRECATION")
    private fun Bundle.rawValueOrNull(key: String): Any? =
        if (containsKey(key)) get(key) else null
}
