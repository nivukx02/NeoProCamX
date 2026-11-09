package com.neoproc.neoproccamx.ui.controls

import com.neoproc.neoproccamx.quality.OmegaCapabilityId
import com.neoproc.neoproccamx.utils.PreferencesManager

internal enum class SettingsOptionType { ENUM, INT_RANGE, BOOLEAN, TEXT }

/**
 * Describes when a setting's production effect becomes authoritative. The dispatch deadline covers
 * app-owned work only; Camera2 session completion and hardware acknowledgement remain device-timed.
 */
internal enum class SettingsActivationClass {
    IMMEDIATE_STATE,
    CAMERA_REQUEST,
    SESSION_RECONFIGURATION,
    NEXT_CAPTURE,
    NEXT_RECORDING
}

/**
 * Exhaustive production contract for one interactive Settings control.
 *
 * [handlerName] is intentionally stored beside the option metadata so host simulations and JVM
 * contract tests can prove that every visible control reaches a real CameraViewModel entry point.
 * [preferenceKeys] is empty only for deliberately session-scoped controls such as Lens and Flash.
 * [maxDispatchLatencyMs] is a bound for accepting and dispatching the setting, not a claim about a
 * vendor Camera HAL finishing a stream reconfiguration within that time.
 */
internal data class SettingsOptionMeta(
    val id: String,
    val title: String,
    val section: SettingsSectionKey,
    val type: SettingsOptionType,
    val defaultLabel: String,
    val allowedLabels: List<String>,
    val handlerName: String,
    val activationClass: SettingsActivationClass,
    val preferenceKeys: List<String> = emptyList(),
    val intRange: IntRange? = null,
    val maxTextLength: Int? = null,
    val maxDispatchLatencyMs: Int = 1_000,
    val capabilityId: OmegaCapabilityId? = null
)

/**
 * The complete interactive Settings surface. Converter cards are action lists and are deliberately
 * excluded; their convert/export/delete contracts are validated separately by the settings simulation.
 */
internal object SettingsOptionMetaCatalog {
    private val BOOLEAN_LABELS = listOf("true", "false")

    val all: List<SettingsOptionMeta> = listOf(
        SettingsOptionMeta(
            id = "camera.previewAspect",
            title = "Preview Aspect",
            section = SettingsSectionKey.CAMERA,
            type = SettingsOptionType.ENUM,
            defaultLabel = "FOUR_THREE",
            allowedLabels = listOf("FOUR_THREE", "SIXTEEN_NINE"),
            handlerName = "setPreviewAspectRatio",
            activationClass = SettingsActivationClass.IMMEDIATE_STATE,
            preferenceKeys = listOf(PreferencesManager.KEY_PREVIEW_ASPECT)
        ),
        SettingsOptionMeta(
            id = "camera.photoQuality",
            title = "Photo Quality",
            section = SettingsSectionKey.CAMERA,
            type = SettingsOptionType.BOOLEAN,
            defaultLabel = "false",
            allowedLabels = BOOLEAN_LABELS,
            handlerName = "setMaximumPhotoResolutionEnabled",
            activationClass = SettingsActivationClass.NEXT_CAPTURE,
            preferenceKeys = listOf(PreferencesManager.KEY_MAX_PHOTO_RESOLUTION)
        ),
        SettingsOptionMeta(
            id = "camera.lens",
            title = "Lens",
            section = SettingsSectionKey.CAMERA,
            type = SettingsOptionType.ENUM,
            defaultLabel = "BACK",
            allowedLabels = listOf("BACK", "FRONT"),
            handlerName = "switchFacing",
            activationClass = SettingsActivationClass.SESSION_RECONFIGURATION
        ),
        SettingsOptionMeta(
            id = "camera.flash",
            title = "Flash",
            section = SettingsSectionKey.CAMERA,
            type = SettingsOptionType.ENUM,
            defaultLabel = "OFF",
            allowedLabels = listOf("OFF", "AUTO", "ON", "TORCH"),
            handlerName = "setFlash",
            activationClass = SettingsActivationClass.CAMERA_REQUEST
        ),
        SettingsOptionMeta(
            id = "camera.aiMotionCompensation",
            title = "AI Motion Compensation",
            section = SettingsSectionKey.CAMERA,
            type = SettingsOptionType.BOOLEAN,
            defaultLabel = "false",
            allowedLabels = BOOLEAN_LABELS,
            handlerName = "setAiMotionCompensationEnabled",
            activationClass = SettingsActivationClass.IMMEDIATE_STATE,
            preferenceKeys = listOf(PreferencesManager.KEY_AI_MOTION_COMPENSATION)
        ),
        SettingsOptionMeta(
            id = "camera.noiseReduction",
            title = "Noise Reduction",
            section = SettingsSectionKey.CAMERA,
            type = SettingsOptionType.BOOLEAN,
            defaultLabel = "true",
            allowedLabels = BOOLEAN_LABELS,
            handlerName = "setNoiseReductionEnabled",
            activationClass = SettingsActivationClass.CAMERA_REQUEST,
            preferenceKeys = listOf(PreferencesManager.KEY_NOISE_REDUCTION)
        ),
        SettingsOptionMeta(
            id = "video.resolution",
            title = "Resolution",
            section = SettingsSectionKey.VIDEO,
            type = SettingsOptionType.ENUM,
            defaultLabel = "4K",
            // Launch exposes only the two physically qualified 4K recording geometries.
            allowedLabels = listOf("4K", "4K 4:3"),
            handlerName = "setVideoResolution",
            activationClass = SettingsActivationClass.SESSION_RECONFIGURATION,
            preferenceKeys = listOf(PreferencesManager.KEY_VIDEO_RESOLUTION)
        ),
        SettingsOptionMeta(
            id = "video.frameRate",
            title = "Frame Rate",
            section = SettingsSectionKey.VIDEO,
            type = SettingsOptionType.ENUM,
            defaultLabel = "25",
            // Launch contract: 4K resolves to 25 only; lower tiers may expose 25/30 dynamically.
            allowedLabels = listOf("25", "30"),
            handlerName = "setFPS",
            activationClass = SettingsActivationClass.SESSION_RECONFIGURATION,
            preferenceKeys = listOf(PreferencesManager.KEY_VIDEO_FPS)
        ),
        SettingsOptionMeta(
            id = "video.codec",
            title = "Codec",
            section = SettingsSectionKey.VIDEO,
            type = SettingsOptionType.ENUM,
            defaultLabel = "HEVC",
            allowedLabels = listOf("HEVC"),
            handlerName = "setVideoCodec",
            activationClass = SettingsActivationClass.SESSION_RECONFIGURATION,
            preferenceKeys = listOf(PreferencesManager.KEY_VIDEO_CODEC)
        ),
        SettingsOptionMeta(
            id = "video.sdrConversionPipeline",
            title = "Cinema Cam",
            section = SettingsSectionKey.VIDEO,
            type = SettingsOptionType.BOOLEAN,
            defaultLabel = "false",
            allowedLabels = BOOLEAN_LABELS,
            handlerName = "setSdrConversionPipelineEnabled",
            activationClass = SettingsActivationClass.NEXT_RECORDING,
            preferenceKeys = listOf(PreferencesManager.KEY_SDR_CONVERSION_PIPELINE)
        ),
        SettingsOptionMeta(
            id = "video.hdrMode",
            title = "XDR Mode",
            section = SettingsSectionKey.VIDEO,
            type = SettingsOptionType.ENUM,
            defaultLabel = "NDR10+",
            allowedLabels = listOf("OFF", "NDR10+", "NIVUKXVISION"),
            handlerName = "setVideoHdrMode",
            activationClass = SettingsActivationClass.SESSION_RECONFIGURATION,
            preferenceKeys = listOf(PreferencesManager.KEY_VIDEO_HDR_MODE),
            capabilityId = OmegaCapabilityId.NDR_NEO_TRANSPORT
        ),
        SettingsOptionMeta(
            id = "video.bitrateMode",
            title = "Bitrate Mode",
            section = SettingsSectionKey.VIDEO,
            type = SettingsOptionType.ENUM,
            defaultLabel = "AUTO",
            allowedLabels = listOf("AUTO", "CINEMA_MASTER", "CUSTOM"),
            handlerName = "setVideoBitrateMode",
            activationClass = SettingsActivationClass.NEXT_RECORDING,
            preferenceKeys = listOf(PreferencesManager.KEY_VIDEO_BITRATE_MODE),
            capabilityId = OmegaCapabilityId.MANUAL_BITRATE_TRUTH
        ),
        SettingsOptionMeta(
            id = "video.manualBitrate",
            title = "Manual Bitrate",
            section = SettingsSectionKey.VIDEO,
            type = SettingsOptionType.INT_RANGE,
            defaultLabel = "240",
            allowedLabels = SettingsOptionCatalog.bitrateStepLabels,
            handlerName = "setVideoBitrate",
            activationClass = SettingsActivationClass.NEXT_RECORDING,
            preferenceKeys = listOf(
                PreferencesManager.KEY_VIDEO_BITRATE,
                PreferencesManager.KEY_VIDEO_BITRATE_MODE
            ),
            intRange = 20..2_000,
            capabilityId = OmegaCapabilityId.MANUAL_BITRATE_TRUTH
        ),
        SettingsOptionMeta(
            id = "video.rawEnabled",
            title = "Video RAW",
            section = SettingsSectionKey.VIDEO,
            type = SettingsOptionType.BOOLEAN,
            defaultLabel = "false",
            allowedLabels = BOOLEAN_LABELS,
            handlerName = "setRawEnabled",
            activationClass = SettingsActivationClass.SESSION_RECONFIGURATION,
            preferenceKeys = listOf(
                PreferencesManager.KEY_RAW_ENABLED,
                PreferencesManager.KEY_VIDEO_MODE
            ),
            capabilityId = OmegaCapabilityId.RAW_SENSOR_TRUTH
        ),
        SettingsOptionMeta(
            id = "video.cinemaMode",
            title = "Video Format Engine",
            section = SettingsSectionKey.VIDEO,
            type = SettingsOptionType.ENUM,
            defaultLabel = "PRO",
            allowedLabels = listOf("PRO", "RAW"),
            handlerName = "setCinemaVideoMode",
            activationClass = SettingsActivationClass.SESSION_RECONFIGURATION,
            preferenceKeys = listOf(PreferencesManager.KEY_VIDEO_MODE),
            capabilityId = OmegaCapabilityId.RAW_SENSOR_TRUTH
        ),
        SettingsOptionMeta(
            id = "video.rawResolution",
            title = "RAW Resolution",
            section = SettingsSectionKey.VIDEO,
            type = SettingsOptionType.ENUM,
            defaultLabel = "AUTO",
            allowedLabels = listOf("AUTO", "MAXIMUM_SENSOR", "FULL_ACTIVE_ARRAY"),
            handlerName = "setRawResolutionMode",
            activationClass = SettingsActivationClass.NEXT_RECORDING,
            preferenceKeys = listOf(PreferencesManager.KEY_RAW_RESOLUTION_MODE),
            capabilityId = OmegaCapabilityId.RAW_SENSOR_TRUTH
        ),
        SettingsOptionMeta(
            id = "video.rawCompression",
            title = "RAW Compression",
            section = SettingsSectionKey.VIDEO,
            type = SettingsOptionType.ENUM,
            defaultLabel = "HIGH_COMPRESSION",
            allowedLabels = listOf("HIGH_COMPRESSION"),
            handlerName = "setRawVideoCompressionMode",
            activationClass = SettingsActivationClass.NEXT_RECORDING,
            preferenceKeys = listOf(PreferencesManager.KEY_RAW_VIDEO_COMPRESSION),
            capabilityId = OmegaCapabilityId.RAW_SENSOR_TRUTH
        ),
        SettingsOptionMeta(
            id = "videoColor.colorSpace",
            title = "Color Space",
            section = SettingsSectionKey.VIDEO_COLOR,
            type = SettingsOptionType.ENUM,
            defaultLabel = "Rec.709",
            allowedLabels = listOf(
                "Rec.709",
                "Rec.2020",
                "Rec.2020-N-Log",
                "Rec.2020-NDR10+",
                "Rec.2020-NIVUKX",
                "Neo Wide Gamut",
                "NeoGamut APEX",
                "Neo ACES AP1",
                "Neo ACES AP0"
            ),
            handlerName = "setVideoColorSpace",
            activationClass = SettingsActivationClass.SESSION_RECONFIGURATION,
            preferenceKeys = listOf(
                PreferencesManager.KEY_VIDEO_COLOR_SPACE,
                PreferencesManager.KEY_VIDEO_COLOR_SPACE_MANUAL
            )
        ),
        SettingsOptionMeta(
            id = "videoColor.colorScience",
            title = "Color Science",
            section = SettingsSectionKey.VIDEO_COLOR,
            type = SettingsOptionType.ENUM,
            defaultLabel = "Natural",
            allowedLabels = listOf(
                "Natural",
                "Rec.709",
                "Rec.2020",
                "Neo ACES High End",
                "Neo Wide Gamut",
                "NeoGamut APEX",
                "NDR10+",
                "NIVUKX Vision",
                "N-Log Base",
                "N-Log Shadow",
                "N-Log Highlight",
                "N-Log Cinema"
            ),
            handlerName = "setColorProfile",
            activationClass = SettingsActivationClass.IMMEDIATE_STATE,
            preferenceKeys = listOf(
                PreferencesManager.KEY_COLOR_PROFILE,
                PreferencesManager.KEY_VIDEO_COLOR_SCIENCE_MANUAL
            )
        ),
        SettingsOptionMeta(
            id = "videoColor.detailProfile",
            title = "Detail Profile",
            section = SettingsSectionKey.VIDEO_COLOR,
            type = SettingsOptionType.ENUM,
            defaultLabel = "FOCUSED_DETAIL",
            allowedLabels = listOf("FOCUSED_DETAIL", "NATURAL_DETAIL", "EXTREME_DETAIL"),
            handlerName = "setVideoDetailProfile",
            activationClass = SettingsActivationClass.CAMERA_REQUEST,
            preferenceKeys = listOf(PreferencesManager.KEY_VIDEO_DETAIL_PROFILE)
        ),
        SettingsOptionMeta(
            id = "focus.eyeAutofocus",
            title = "Eye Autofocus",
            section = SettingsSectionKey.FOCUS,
            type = SettingsOptionType.BOOLEAN,
            defaultLabel = "false",
            allowedLabels = BOOLEAN_LABELS,
            handlerName = "setEyeAutofocusEnabled",
            activationClass = SettingsActivationClass.CAMERA_REQUEST,
            preferenceKeys = listOf(PreferencesManager.KEY_EYE_AF)
        ),
        SettingsOptionMeta(
            id = "system.gridMode",
            title = "Grid Type",
            section = SettingsSectionKey.SYSTEM,
            type = SettingsOptionType.ENUM,
            defaultLabel = "NONE",
            allowedLabels = listOf("NONE", "THIRDS", "CENTER"),
            handlerName = "setGridMode",
            activationClass = SettingsActivationClass.IMMEDIATE_STATE,
            preferenceKeys = listOf(PreferencesManager.KEY_GRID_MODE)
        ),
        SettingsOptionMeta(
            id = "system.framingGuides",
            title = "Framing Guides",
            section = SettingsSectionKey.SYSTEM,
            type = SettingsOptionType.BOOLEAN,
            defaultLabel = "false",
            allowedLabels = BOOLEAN_LABELS,
            handlerName = "setSafeAreaGuidesEnabled",
            activationClass = SettingsActivationClass.IMMEDIATE_STATE,
            preferenceKeys = listOf(PreferencesManager.KEY_SAFE_AREA_GUIDES)
        ),
        SettingsOptionMeta(
            id = "system.stabilization",
            title = "Stabilization",
            section = SettingsSectionKey.SYSTEM,
            type = SettingsOptionType.ENUM,
            defaultLabel = "OIS",
            allowedLabels = listOf("OIS", "EIS", "HYBRID", "OFF"),
            handlerName = "setStabilizationMode",
            activationClass = SettingsActivationClass.CAMERA_REQUEST,
            preferenceKeys = listOf(PreferencesManager.KEY_STABILIZATION_MODE)
        ),
        SettingsOptionMeta(
            id = "system.audio",
            title = "Audio",
            section = SettingsSectionKey.SYSTEM,
            type = SettingsOptionType.ENUM,
            defaultLabel = "CAMCORDER",
            allowedLabels = listOf("CAMCORDER", "BUILTIN_MIC", "UNPROCESSED", "NONE"),
            handlerName = "setAudioSourceIndex",
            activationClass = SettingsActivationClass.NEXT_RECORDING,
            preferenceKeys = listOf(PreferencesManager.KEY_AUDIO_SOURCE)
        ),
        SettingsOptionMeta(
            id = "system.customRecordingNameEnabled",
            title = "Custom Recording Name",
            section = SettingsSectionKey.SYSTEM,
            type = SettingsOptionType.BOOLEAN,
            defaultLabel = "false",
            allowedLabels = BOOLEAN_LABELS,
            handlerName = "setCustomRecordingNameEnabled",
            activationClass = SettingsActivationClass.NEXT_RECORDING,
            preferenceKeys = listOf(PreferencesManager.KEY_CUSTOM_RECORDING_NAME_ENABLED)
        ),
        SettingsOptionMeta(
            id = "system.customRecordingName",
            title = "Recording Name",
            section = SettingsSectionKey.SYSTEM,
            type = SettingsOptionType.TEXT,
            defaultLabel = "",
            allowedLabels = emptyList(),
            handlerName = "setCustomRecordingName",
            activationClass = SettingsActivationClass.NEXT_RECORDING,
            preferenceKeys = listOf(
                PreferencesManager.KEY_CUSTOM_RECORDING_NAME,
                PreferencesManager.KEY_CUSTOM_RECORDING_NAME_ENABLED
            ),
            maxTextLength = 64
        )
    )

    fun forSection(key: SettingsSectionKey): List<SettingsOptionMeta> = all.filter { it.section == key }
}
