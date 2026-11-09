package com.neoproc.neoproccamx.ui.controls

import com.neoproc.neoproccamx.quality.OmegaCapabilityId

internal enum class SettingsOptionType { ENUM, INT_RANGE, BOOLEAN }

/**
 * s8-f03 (CP-U3) — typed metadata for a settings option: every option declares a stable id, its
 * section, type, default, valid set/range, and (if capability-bearing) the OmegaCapabilityId that
 * gates it. This is the "no untyped/free-string toggle" layer the typing meta-test asserts against.
 */
internal data class SettingsOptionMeta(
    val id: String,
    val section: SettingsSectionKey,
    val type: SettingsOptionType,
    val defaultLabel: String,
    val allowedLabels: List<String>,
    val intRange: IntRange? = null,
    val capabilityId: OmegaCapabilityId? = null
)

internal object SettingsOptionMetaCatalog {
    val all: List<SettingsOptionMeta> = listOf(
        SettingsOptionMeta(
            id = "video.cinemaMode", section = SettingsSectionKey.VIDEO, type = SettingsOptionType.ENUM,
            defaultLabel = "PRO", allowedLabels = listOf("PRO", "RAW"),
            capabilityId = OmegaCapabilityId.RAW_SENSOR_TRUTH
        ),
        SettingsOptionMeta(
            id = "video.hdrMode", section = SettingsSectionKey.VIDEO, type = SettingsOptionType.ENUM,
            defaultLabel = "OFF", allowedLabels = listOf("OFF", "NDR10+", "NIVUKXVISION"),
            capabilityId = OmegaCapabilityId.NDR_NEO_TRANSPORT
        ),
        SettingsOptionMeta(
            id = "video.bitrateMode", section = SettingsSectionKey.VIDEO, type = SettingsOptionType.ENUM,
            defaultLabel = "AUTO", allowedLabels = listOf("AUTO", "CINEMA_MASTER", "CUSTOM"),
            capabilityId = OmegaCapabilityId.MANUAL_BITRATE_TRUTH
        ),
        SettingsOptionMeta(
            id = "video.codec", section = SettingsSectionKey.VIDEO, type = SettingsOptionType.ENUM,
            defaultLabel = "HEVC", allowedLabels = listOf("HEVC", "AVC", "AV1")
        ),
        SettingsOptionMeta(
            id = "camera.stabilization", section = SettingsSectionKey.CAMERA, type = SettingsOptionType.ENUM,
            defaultLabel = "OIS", allowedLabels = listOf("OIS", "EIS", "HYBRID", "OFF")
        ),
        SettingsOptionMeta(
            id = "videoColor.detailProfile", section = SettingsSectionKey.VIDEO_COLOR, type = SettingsOptionType.ENUM,
            defaultLabel = "STANDARD", allowedLabels = listOf("STANDARD", "FOCUSED_DETAIL", "MAX_DETAIL")
        ),
        SettingsOptionMeta(
            id = "focus.eyeAutofocus", section = SettingsSectionKey.FOCUS, type = SettingsOptionType.BOOLEAN,
            defaultLabel = "false", allowedLabels = listOf("true", "false")
        ),
        SettingsOptionMeta(
            id = "system.hapticFeedback", section = SettingsSectionKey.SYSTEM, type = SettingsOptionType.BOOLEAN,
            defaultLabel = "true", allowedLabels = listOf("true", "false")
        )
    )

    fun forSection(key: SettingsSectionKey): List<SettingsOptionMeta> = all.filter { it.section == key }
}
