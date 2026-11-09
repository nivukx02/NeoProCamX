package com.neoproc.neoproccamx.utils

import android.content.Context
import android.content.SharedPreferences
import android.util.Size
import androidx.core.content.edit
import com.neoproc.neoproccamx.camera.RawCaptureResolutionMode
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
import com.neoproc.neoproccamx.recording.StabilizationMode
import com.neoproc.neoproccamx.recording.VideoBitrateMode
import com.neoproc.neoproccamx.recording.ManualVideoColorSelectionPolicy
import com.neoproc.neoproccamx.ui.PreviewAspectRatio
import java.util.Locale

/**
 * Keeps the active capture-mode default independent from the default-on packed 10-bit transport.
 * A missing selection means ordinary Video, while every explicitly stored HDR/NDR token remains
 * authoritative and can still be selected by settings or RAW-development workflows.
 */
internal object VideoHdrStartupDefaultPolicy {
    const val DEFAULT_ACTIVE_MODE = "OFF"

    fun resolve(storedMode: String?): String = storedMode ?: DEFAULT_ACTIVE_MODE
}

class PreferencesManager(context: Context) {

    companion object {
        const val PREFS_NAME = "neoproccamx_prefs"

        const val KEY_VIDEO_WIDTH        = "video_width"
        const val KEY_VIDEO_HEIGHT       = "video_height"
        const val KEY_VIDEO_RESOLUTION   = "video_resolution"
        const val KEY_VIDEO_FPS          = "video_fps"
        const val KEY_VIDEO_BITRATE      = "video_bitrate"
        const val KEY_VIDEO_BITRATE_MODE = "video_bitrate_mode"
        const val KEY_VIDEO_CODEC        = "video_codec"
        const val KEY_VIDEO_MODE         = "video_mode"
        const val KEY_LAST_UI_MODE      = "last_ui_mode"
        const val KEY_VIDEO_COLOR_SPACE  = "video_color_space"
        const val KEY_VIDEO_COLOR_SPACE_MANUAL = "video_color_space_manual"
        const val KEY_VIDEO_HDR_MODE     = "video_hdr_mode"
        const val KEY_PREFERRED_NDR_PROFILE = "preferred_ndr_profile"
        const val KEY_VIDEO_DETAIL_PROFILE = "video_detail_profile"
        const val KEY_PHOTO_HDR_MODE     = "photo_hdr_mode"
        const val KEY_RAW_VIDEO_COMPRESSION = "raw_video_compression"
        const val KEY_RAW_RESOLUTION_MODE = "raw_resolution_mode"
        const val KEY_PREVIEW_ASPECT     = "preview_aspect"
        const val KEY_AUDIO_SOURCE       = "audio_source"
        const val KEY_JPEG_QUALITY       = "jpeg_quality"
        const val KEY_MAX_PHOTO_RESOLUTION = "max_photo_resolution"
        const val KEY_RAW_ENABLED        = "raw_enabled"
        const val KEY_HDR_ENABLED        = "hdr_enabled"
        const val KEY_FACE_DETECT        = "face_detection"
        const val KEY_EYE_AF             = "eye_autofocus"
        const val KEY_GRID_MODE          = "grid_mode"
        const val KEY_SAFE_AREA_GUIDES   = "safe_area_guides"
        const val KEY_STABILIZATION_MODE = "stabilization"
        const val KEY_OIS                = "ois_enabled"
        const val KEY_OBJECT_DETECTION   = "object_detection"
        const val KEY_AI_ENHANCEMENT     = "ai_enhancement"
        const val KEY_AI_MOTION_COMPENSATION = "ai_motion_compensation"
        const val AI_ENHANCEMENT_SETTINGS_ENABLED = false
        const val AI_ENHANCEMENT_DEFAULT_ENABLED = false
        const val KEY_TEN_BIT_RECORDING  = "ten_bit_recording"
        const val KEY_UNCOMPRESSED_CINEMA_PIPELINE = "uncompressed_cinema_pipeline"
        const val KEY_SDR_CONVERSION_PIPELINE = "sdr_conversion_pipeline"
        const val KEY_NOISE_REDUCTION    = "noise_reduction"
        const val KEY_HIGH_END_PROFILE   = "high_end_profile"
        const val KEY_HIGH_END_VIDEO_COLOR = "high_end_video_color"
        const val KEY_HIGH_END_FOCUS_AI = "high_end_focus_ai"
        const val KEY_HIGH_END_AUDIO_STABILIZATION = "high_end_audio_stabilization"
        const val KEY_HIGH_END_MANUAL_CONTROLS = "high_end_manual_controls"
        const val KEY_COLOR_PROFILE      = "color_profile"
        const val KEY_VIDEO_COLOR_SCIENCE_MANUAL = "video_color_science_manual"
        const val KEY_LUT_ENABLED        = "lut_enabled"
        const val KEY_LUT_PROFILE        = "lut_profile"
        const val KEY_CUSTOM_LUT_PATH    = "custom_lut_path"
        const val KEY_LOG_ENABLED        = "log_enabled"
        const val KEY_LOG_STRENGTH       = "log_strength"
        const val KEY_AF_MODE            = "af_mode"
        const val KEY_SATURATION         = "saturation"
        const val KEY_CONTRAST           = "contrast"
        const val KEY_BRIGHTNESS         = "brightness"
        const val KEY_LAST_ISO           = "last_iso"
        const val KEY_LAST_SHUTTER_NS    = "last_shutter_ns"
        const val KEY_LAST_WB_KELVIN     = "last_wb_kelvin"
        const val KEY_LAST_WB_TINT       = "last_wb_tint"
        const val KEY_LAST_FOCUS_DISTANCE = "last_focus_distance"
        const val KEY_LAST_EV            = "last_ev"
        const val KEY_CLIP_COUNTER       = "clip_counter"
        const val KEY_REEL_NAME          = "reel_name"
        const val KEY_CUSTOM_RECORDING_NAME_ENABLED = "custom_recording_name_enabled"
        const val KEY_CUSTOM_RECORDING_NAME = "custom_recording_name"
        private const val KEY_HDR_RECORDING_DEFAULT_MIGRATED =
            "hdr_recording_default_migrated_v1"
        private const val KEY_OFFSCREEN_EGL_REPROBE_MIGRATED =
            "offscreen_egl_reprobe_after_hdr_bind_fix_v1"
        private const val KEY_FACE_EYE_DEFAULTS_MIGRATED =
            "face_eye_defaults_off_migrated_v1"
        private const val KEY_MANUAL_VIDEO_COLOR_PROVENANCE_MIGRATED =
            "manual_video_color_provenance_migrated_v1"
        private const val KEY_CAPABILITY_AWARE_BITRATE_MIGRATED =
            "capability_aware_bitrate_migrated_v1"
        
        // HDR Tone Map Persistence (HIGH-END UPGRADE)
        const val KEY_HDR_TONEMAP_EXPOSURE_BOOST = "hdr_tonemap_exposure_boost"
        const val KEY_HDR_TONEMAP_WHITE_POINT = "hdr_tonemap_white_point"
        const val KEY_HDR_TONEMAP_SHADOW_LIFT = "hdr_tonemap_shadow_lift"
        const val KEY_HDR_TONEMAP_MIDTONE_CONTRAST = "hdr_tonemap_midtone_contrast"
        const val KEY_HDR_TONEMAP_HIGHLIGHT_DESATURATE = "hdr_tonemap_highlight_desaturate"
        const val KEY_HDR_TONEMAP_BLACK_FLOOR = "hdr_tonemap_black_floor"
        const val KEY_HDR_TONEMAP_SCENE_INTENT = "hdr_tonemap_scene_intent"
        const val KEY_HDR_TONEMAP_USE_HYBRID = "hdr_tonemap_use_hybrid"
        const val KEY_HDR_TONEMAP_GT_WEIGHT = "hdr_tonemap_gt_weight"
        const val KEY_HDR_TONEMAP_AGX_WEIGHT = "hdr_tonemap_agx_weight"
        const val KEY_HDR_TONEMAP_MIDTONE_BOOST = "hdr_tonemap_midtone_boost"
        const val KEY_HDR_TONEMAP_HIGHLIGHT_RECOVERY = "hdr_tonemap_highlight_recovery"
        const val KEY_HDR_TONEMAP_COLOR_PRESERVATION = "hdr_tonemap_color_preservation"
        
        // Video FPS Baseline (24fps enforcement)
        const val KEY_VIDEO_FPS_BASELINE = "video_fps_baseline"
        
        // LOG Color Space Recording Flag
        const val KEY_LOG_RECORDING_ENABLED = "log_recording_enabled"

        // Device-confirmed dead offscreen AHB->EGL 10-bit encoder path (skip re-probing each launch)
        const val KEY_OFFSCREEN_EGL_ENCODER_UNAVAILABLE = "offscreen_egl_encoder_unavailable"
        // Build+OS fingerprint under which the offscreen path was proven a hardware wall; a new app
        // build (EGL fix) or an OS/GPU-driver OTA changes it, forcing an honest re-probe.
        const val KEY_OFFSCREEN_EGL_ENCODER_UNAVAILABLE_FINGERPRINT =
            "offscreen_egl_encoder_unavailable_fingerprint"

        const val DEFAULT_VIDEO_WIDTH       = 3840
        const val DEFAULT_VIDEO_HEIGHT      = 2160
        const val DEFAULT_VIDEO_FPS         = 25
        /** High-quality 4K/25 seed; AUTO/CINEMA_MASTER resolves and capability-clamps per device. */
        const val DEFAULT_VIDEO_BITRATE     = 240_000_000
        private const val LEGACY_FORCED_VIDEO_BITRATE = 1_968_000_000
        const val DEFAULT_JPEG_QUALITY      = 100
        const val DEFAULT_LOG_STRENGTH      = 1.0f
        const val DEFAULT_SATURATION        = 1.0f
        const val DEFAULT_CONTRAST          = 1.0f
        const val DEFAULT_BRIGHTNESS        = 0.0f

        private const val MIN_VIDEO_DIMENSION = 16
        // CINEMA UPGRADE: Restored 8K UHD support for maximum sensor output
        private const val MAX_VIDEO_DIMENSION = 7680
        private const val MIN_VIDEO_FPS = 1
        private const val MAX_VIDEO_FPS = 60
        private const val MIN_VIDEO_BITRATE = 1_000_000
        private const val MAX_VIDEO_BITRATE = 2_000_000_000
        private const val MIN_JPEG_QUALITY = 1
        private const val MAX_JPEG_QUALITY = 100
        private const val MIN_LOG_STRENGTH = 0f
        private const val MAX_LOG_STRENGTH = 2f
        private const val MIN_COLOR_GAIN = 0f
        private const val MAX_COLOR_GAIN = 2f
        private const val MIN_BRIGHTNESS = -1f
        private const val MAX_BRIGHTNESS = 1f
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    init {
        migrateLegacyAutoAppliedHighEndProfileIfNeeded()
        migrateLegacyForcedBitrateIfNeeded()
        migrateManualVideoColorSelectionProvenanceIfNeeded()
        migrateHdrRecordingDefaultIfNeeded()
        migrateOffscreenEglReprobeIfNeeded()
        enforceStrictPackSelectionForHighEndProfile()
        migrateFaceEyeDefaultsOffIfNeeded()
        enforceHiddenCaptureToolsOff()
        enforceHiddenAiEnhancementOff()
    }

    /**
     * Older high-end packs wrote 1.968 Gbit/s as a CUSTOM request before any codec capability probe.
     * That exact rate is also a user-selectable UI value, so persisted state cannot distinguish an
     * auto-generated pack value from an explicit user choice. Preserve the tuple byte-for-byte and
     * let RecordingManager capability-clamp it at session preparation; silently rewriting it to a
     * lower automatic target would destroy user intent.
     */
    private fun migrateLegacyForcedBitrateIfNeeded() {
        if (getBooleanCompat(KEY_CAPABILITY_AWARE_BITRATE_MIGRATED, false)) return
        val ambiguousLegacyCustomSelection =
            getBooleanCompat(KEY_HIGH_END_PROFILE, false) &&
                getIntCompat(KEY_VIDEO_BITRATE, DEFAULT_VIDEO_BITRATE) ==
                    LEGACY_FORCED_VIDEO_BITRATE &&
                VideoBitrateMode.fromName(
                    getStringCompat(KEY_VIDEO_BITRATE_MODE, VideoBitrateMode.AUTO.name)
                ) == VideoBitrateMode.CUSTOM
        prefs.edit {
            if (ambiguousLegacyCustomSelection) {
                // Normalize legacy cross-type values without changing the selected rate or mode.
                putInt(KEY_VIDEO_BITRATE, LEGACY_FORCED_VIDEO_BITRATE)
                putString(KEY_VIDEO_BITRATE_MODE, VideoBitrateMode.CUSTOM.name)
            }
            putBoolean(KEY_CAPABILITY_AWARE_BITRATE_MIGRATED, true)
        }
    }

    private fun migrateManualVideoColorSelectionProvenanceIfNeeded() {
        if (getBooleanCompat(KEY_MANUAL_VIDEO_COLOR_PROVENANCE_MIGRATED, false)) return
        val shouldApprovePair = ManualVideoColorSelectionPolicy.shouldMigrateSavedPreferencePair(
            hasStoredColorSpace = prefs.contains(KEY_VIDEO_COLOR_SPACE),
            hasStoredColorScience = prefs.contains(KEY_COLOR_PROFILE),
            colorSpace = getStringCompat(KEY_VIDEO_COLOR_SPACE, null),
            colorScience = getStringCompat(KEY_COLOR_PROFILE, null),
            colorSpaceAlreadyApproved = getBooleanCompat(KEY_VIDEO_COLOR_SPACE_MANUAL, false),
            colorScienceAlreadyApproved = getBooleanCompat(KEY_VIDEO_COLOR_SCIENCE_MANUAL, false)
        )
        prefs.edit {
            if (shouldApprovePair) {
                putBoolean(KEY_VIDEO_COLOR_SPACE_MANUAL, true)
                putBoolean(KEY_VIDEO_COLOR_SCIENCE_MANUAL, true)
            }
            putBoolean(KEY_MANUAL_VIDEO_COLOR_PROVENANCE_MIGRATED, true)
        }
    }

    /**
     * One-time clear of the persisted "offscreen GPU 10-bit encoder unavailable" flag. That flag was
     * set by the pre-fix native bind failures (sRGB-on-HDR-surface bug). The native colorspace fix
     * makes the GPU 10-bit bind succeed, so re-probe once; if it genuinely fails again it re-persists.
     */
    private fun migrateOffscreenEglReprobeIfNeeded() {
        if (getBooleanCompat(KEY_OFFSCREEN_EGL_REPROBE_MIGRATED, false)) return
        prefs.edit {
            remove(KEY_OFFSCREEN_EGL_ENCODER_UNAVAILABLE)
            putBoolean(KEY_OFFSCREEN_EGL_REPROBE_MIGRATED, true)
        }
    }

    /**
     * One-time initialization for installs that have never stored an HDR selection. The active home
     * mode must agree with the visible Video rail, so a fresh install starts with HDR off. This does
     * not change the independently default-on packed 10-bit recording transport. A persisted value,
     * including explicit OFF, NDR10+, or NIVUKX Vision, is user intent and survives unchanged.
     */
    private fun migrateHdrRecordingDefaultIfNeeded() {
        if (getBooleanCompat(KEY_HDR_RECORDING_DEFAULT_MIGRATED, false)) return
        val hasStoredHdrSelection = prefs.contains(KEY_VIDEO_HDR_MODE)
        prefs.edit {
            if (!hasStoredHdrSelection) {
                putString(KEY_VIDEO_HDR_MODE, VideoHdrStartupDefaultPolicy.DEFAULT_ACTIVE_MODE)
            }
            putBoolean(KEY_HDR_RECORDING_DEFAULT_MIGRATED, true)
        }
    }

    var videoSize: Size
        get() {
            val encoded = getStringCompat(KEY_VIDEO_RESOLUTION, null)
            val parsed = encoded?.split('x')
                ?.takeIf { it.size == 2 }
                ?.mapNotNull { it.toIntOrNull() }
                ?.takeIf { it.size == 2 }
            if (parsed != null) return Size(parsed[0], parsed[1])
            return Size(
                getIntCompat(KEY_VIDEO_WIDTH, DEFAULT_VIDEO_WIDTH),
                getIntCompat(KEY_VIDEO_HEIGHT, DEFAULT_VIDEO_HEIGHT)
            )
        }
        set(v) {
            val safeWidth = v.width.coerceIn(MIN_VIDEO_DIMENSION, MAX_VIDEO_DIMENSION)
            val safeHeight = v.height.coerceIn(MIN_VIDEO_DIMENSION, MAX_VIDEO_DIMENSION)
            prefs.edit {
                putInt(KEY_VIDEO_WIDTH, safeWidth)
                putInt(KEY_VIDEO_HEIGHT, safeHeight)
                putString(KEY_VIDEO_RESOLUTION, "${safeWidth}x${safeHeight}")
            }
        }

    private fun migrateLegacyAutoAppliedHighEndProfileIfNeeded() {
        val hasExplicitHighEndFlag =
            prefs.contains(KEY_HIGH_END_PROFILE) ||
                prefs.contains(KEY_HIGH_END_VIDEO_COLOR) ||
                prefs.contains(KEY_HIGH_END_FOCUS_AI) ||
                prefs.contains(KEY_HIGH_END_AUDIO_STABILIZATION) ||
                prefs.contains(KEY_HIGH_END_MANUAL_CONTROLS)
        if (hasExplicitHighEndFlag) return

        val legacyCinematicGrid =
            getStringCompat(KEY_GRID_MODE, "THIRDS") == "CINEMATIC"
        val legacyFocusPack =
            getBooleanCompat(KEY_FACE_DETECT, false) &&
                getBooleanCompat(KEY_EYE_AF, false) &&
                getBooleanCompat(KEY_AI_ENHANCEMENT, false)
        val legacyCinemaPack =
            getStringCompat(KEY_STABILIZATION_MODE, StabilizationMode.OIS.name) ==
                StabilizationMode.HYBRID.name &&
                getStringCompat(KEY_AUDIO_SOURCE, "CAMCORDER") == "UNPROCESSED" &&
                getBooleanCompat(KEY_MAX_PHOTO_RESOLUTION, false) &&
                getStringCompat(KEY_COLOR_PROFILE, "Natural").equals("Neo Flex Curve", ignoreCase = true) &&
                getBooleanCompat(KEY_LOG_ENABLED, false) &&
                getStringCompat(KEY_VIDEO_MODE, "NORMAL").equals("LOG", ignoreCase = true) &&
                getStringCompat(KEY_VIDEO_COLOR_SPACE, "Rec.709").equals("Rec.2020", ignoreCase = true) &&
                getStringCompat(KEY_PHOTO_HDR_MODE, "OFF").equals("PRO_HDR", ignoreCase = true)
        if (!(legacyCinematicGrid && legacyFocusPack && legacyCinemaPack)) return

        prefs.edit {
            putBoolean(KEY_FACE_DETECT, false)
            putBoolean(KEY_EYE_AF, false)
            putBoolean(KEY_OBJECT_DETECTION, false)
            putBoolean(KEY_AI_ENHANCEMENT, false)
            putString(KEY_GRID_MODE, "THIRDS")
            putString(KEY_STABILIZATION_MODE, StabilizationMode.OIS.name)
            putBoolean(KEY_OIS, true)
            putString(KEY_AUDIO_SOURCE, "CAMCORDER")
            putBoolean(KEY_MAX_PHOTO_RESOLUTION, false)
            putBoolean(KEY_HDR_ENABLED, false)
            putString(KEY_PHOTO_HDR_MODE, "OFF")
            putString(KEY_COLOR_PROFILE, "Natural")
            putBoolean(KEY_LUT_ENABLED, false)
            putString(KEY_LUT_PROFILE, "Neutral Film")
            putBoolean(KEY_LOG_ENABLED, false)
            putString(KEY_VIDEO_MODE, "PRO")
            putString(KEY_VIDEO_COLOR_SPACE, "Rec.709")
            putString(KEY_VIDEO_HDR_MODE, "OFF")
            putString(KEY_RAW_VIDEO_COMPRESSION, "OFF")
        }
    }

    /**
     * Keeps the mandatory packed-10 capture transport enabled. Hidden-tool availability is resolved
     * at runtime; initialization must not erase persisted LUT/LOG selections or rewrite video mode.
     * The legacy function name is retained because release contracts audit this mandatory gate.
     */
    private fun enforceHiddenCaptureToolsOff() {
        prefs.edit {
            // The legacy preference name is retained for migration. The active implementation is the
            // lossless packed-10 custom packet recorder and is mandatory for processed video.
            putBoolean(KEY_UNCOMPRESSED_CINEMA_PIPELINE, true)
        }
    }

    /**
     * Product lock: AI Enhancement remains implemented, but must stay off unless the product switch
     * above is changed in code. This prevents hidden UI, migrations, or high-end packs from restoring
     * the extra live AI workload during the zero-drop recording profile.
     */
    private fun enforceHiddenAiEnhancementOff() {
        if (AI_ENHANCEMENT_SETTINGS_ENABLED) return
        prefs.edit {
            putBoolean(KEY_AI_ENHANCEMENT, false)
        }
    }

    /**
     * Face/eye tracking remain fully implemented and user-toggleable, but older high-end defaults and
     * video metering startup paths silently wrote them ON. Clear that legacy auto-default once so the
     * realtime AI stack runs only after the user explicitly enables it again in Settings.
     */
    private fun migrateFaceEyeDefaultsOffIfNeeded() {
        if (getBooleanCompat(KEY_FACE_EYE_DEFAULTS_MIGRATED, false)) return
        prefs.edit {
            putBoolean(KEY_FACE_DETECT, false)
            putBoolean(KEY_EYE_AF, false)
            putBoolean(KEY_FACE_EYE_DEFAULTS_MIGRATED, true)
        }
    }

    var videoFps: Int
        get() = getIntCompat(KEY_VIDEO_FPS, DEFAULT_VIDEO_FPS).coerceIn(MIN_VIDEO_FPS, MAX_VIDEO_FPS)
        set(v) { prefs.edit { putInt(KEY_VIDEO_FPS, v.coerceIn(MIN_VIDEO_FPS, MAX_VIDEO_FPS)) } }

    var videoBitrate: Int
        get() = getIntCompat(KEY_VIDEO_BITRATE, DEFAULT_VIDEO_BITRATE)
            .coerceIn(MIN_VIDEO_BITRATE, MAX_VIDEO_BITRATE)
        set(v) { prefs.edit { putInt(KEY_VIDEO_BITRATE, v.coerceIn(MIN_VIDEO_BITRATE, MAX_VIDEO_BITRATE)) } }

    var videoBitrateMode: VideoBitrateMode
        get() = VideoBitrateMode.fromName(getStringCompat(KEY_VIDEO_BITRATE_MODE, VideoBitrateMode.AUTO.name))
        set(v) { prefs.edit { putString(KEY_VIDEO_BITRATE_MODE, v.name) } }

    var videoCodec: String
        get() = getStringCompat(KEY_VIDEO_CODEC, "HEVC") ?: "HEVC"
        set(v) { prefs.edit { putString(KEY_VIDEO_CODEC, v) } }

    var videoMode: String
        get() = getStringCompat(KEY_VIDEO_MODE, "PRO") ?: "PRO"
        set(v) { prefs.edit { putString(KEY_VIDEO_MODE, v) } }

    var lastUiMode: String
        get() = getStringCompat(KEY_LAST_UI_MODE, "PHOTO") ?: "PHOTO"
        set(v) { prefs.edit { putString(KEY_LAST_UI_MODE, v) } }

    var videoColorSpace: String
        get() = getStringCompat(KEY_VIDEO_COLOR_SPACE, "Rec.709") ?: "Rec.709"
        set(v) { prefs.edit { putString(KEY_VIDEO_COLOR_SPACE, v) } }

    var videoColorSpaceManuallySelected: Boolean
        get() = getBooleanCompat(KEY_VIDEO_COLOR_SPACE_MANUAL, false)
        set(v) { prefs.edit { putBoolean(KEY_VIDEO_COLOR_SPACE_MANUAL, v) } }

    /**
     * Persist the value and its user-approval marker in one SharedPreferences transaction. Recording
     * admission reads both fields together; writing them separately allowed a record tap between the two
     * edits to reject the selection the settings row already displayed.
     */
    fun setManualVideoColorSpace(value: String) {
        prefs.edit {
            putString(KEY_VIDEO_COLOR_SPACE, value)
            putBoolean(KEY_VIDEO_COLOR_SPACE_MANUAL, true)
        }
    }

    var videoHdrMode: String
        // Active capture defaults to true Video/SDR. Ten-bit recording remains independently
        // default-on, and NDR10+/NIVUKX remain available as explicit settings/conversion choices.
        get() = VideoHdrStartupDefaultPolicy.resolve(getStringCompat(KEY_VIDEO_HDR_MODE, null))
        set(v) { prefs.edit { putString(KEY_VIDEO_HDR_MODE, v) } }

    /**
     * Last explicitly selected project-owned NDR look. Unlike [videoHdrMode], this preference is not
     * cleared while RAW, LOG, Video, or Pro temporarily owns the active camera pipeline. Keeping the
     * user's look separate from the active mode prevents NIVUKX Vision from silently becoming NDR10+
     * when the user returns to the NDR rail entry.
     */
    var preferredNdrProfile: String
        get() = getStringCompat(KEY_PREFERRED_NDR_PROFILE, videoHdrMode) ?: videoHdrMode
        set(v) { prefs.edit { putString(KEY_PREFERRED_NDR_PROFILE, v) } }

    var videoDetailProfile: VideoDetailProfile
        get() = VideoDetailProfile.fromPreference(getStringCompat(KEY_VIDEO_DETAIL_PROFILE, null))
        set(v) { prefs.edit { putString(KEY_VIDEO_DETAIL_PROFILE, v.name) } }

    var photoHdrMode: String
        get() = getStringCompat(KEY_PHOTO_HDR_MODE, "OFF") ?: "OFF"
        set(v) { prefs.edit { putString(KEY_PHOTO_HDR_MODE, v) } }

    var rawVideoCompression: String
        get() = getStringCompat(KEY_RAW_VIDEO_COMPRESSION, "OFF") ?: "OFF"
        set(v) { prefs.edit { putString(KEY_RAW_VIDEO_COMPRESSION, v) } }

    var rawResolutionMode: RawCaptureResolutionMode
        get() = RawCaptureResolutionMode.fromName(
            getStringCompat(KEY_RAW_RESOLUTION_MODE, RawCaptureResolutionMode.DEFAULT.name)
        )
        set(v) { prefs.edit { putString(KEY_RAW_RESOLUTION_MODE, v.name) } }

    var previewAspectRatio: PreviewAspectRatio
        get() {
            val stored = getStringCompat(KEY_PREVIEW_ASPECT, PreviewAspectRatio.FOUR_THREE.name)
            val resolved = PreviewAspectRatio.fromPersistedUserSelection(stored)
            // Normalize hidden/legacy selections once. This also prevents an existing 1:1 value
            // from continuing to crop the preview after the product option has been removed.
            if (!stored.equals(resolved.name, ignoreCase = true)) {
                prefs.edit { putString(KEY_PREVIEW_ASPECT, resolved.name) }
            }
            return resolved
        }
        set(v) { prefs.edit { putString(KEY_PREVIEW_ASPECT, v.name) } }

    var audioSource: String
        get() = getStringCompat(KEY_AUDIO_SOURCE, "CAMCORDER") ?: "CAMCORDER"
        set(v) { prefs.edit { putString(KEY_AUDIO_SOURCE, v) } }

    var jpegQuality: Int
        get() = getIntCompat(KEY_JPEG_QUALITY, DEFAULT_JPEG_QUALITY)
        set(v) { prefs.edit { putInt(KEY_JPEG_QUALITY, v.coerceIn(MIN_JPEG_QUALITY, MAX_JPEG_QUALITY)) } }

    var maxPhotoResolutionEnabled: Boolean
        get() = getBooleanCompat(KEY_MAX_PHOTO_RESOLUTION, false)
        set(v) { prefs.edit { putBoolean(KEY_MAX_PHOTO_RESOLUTION, v) } }

    var rawEnabled: Boolean
        get() = getBooleanCompat(KEY_RAW_ENABLED, false)
        set(v) { prefs.edit { putBoolean(KEY_RAW_ENABLED, v) } }

    var hdrEnabled: Boolean
        get() = getBooleanCompat(KEY_HDR_ENABLED, false)
        set(v) { prefs.edit { putBoolean(KEY_HDR_ENABLED, v) } }

    var faceDetectionEnabled: Boolean
        get() = getBooleanCompat(KEY_FACE_DETECT, false)
        set(v) { prefs.edit { putBoolean(KEY_FACE_DETECT, v) } }

    var eyeAutofocusEnabled: Boolean
        get() = getBooleanCompat(KEY_EYE_AF, false)
        set(v) { prefs.edit { putBoolean(KEY_EYE_AF, v) } }

    var gridMode: String
        get() = getStringCompat(KEY_GRID_MODE, "NONE") ?: "NONE"
        set(v) { prefs.edit { putString(KEY_GRID_MODE, v) } }

    var safeAreaGuidesEnabled: Boolean
        get() = getBooleanCompat(KEY_SAFE_AREA_GUIDES, false)
        set(v) { prefs.edit { putBoolean(KEY_SAFE_AREA_GUIDES, v) } }

    var stabilizationMode: StabilizationMode
        get() = runCatching {
            StabilizationMode.valueOf(
                getStringCompat(KEY_STABILIZATION_MODE, StabilizationMode.OIS.name)
                    ?: StabilizationMode.OIS.name
            )
        }.getOrDefault(
            if (getBooleanCompat(KEY_OIS, true)) StabilizationMode.OIS else StabilizationMode.OFF
        )
        set(v) {
            prefs.edit {
                putString(KEY_STABILIZATION_MODE, v.name)
                putBoolean(KEY_OIS, v == StabilizationMode.OIS || v == StabilizationMode.HYBRID)
            }
        }

    var oisEnabled: Boolean
        get() = stabilizationMode == StabilizationMode.OIS || stabilizationMode == StabilizationMode.HYBRID
        set(v) { stabilizationMode = if (v) StabilizationMode.OIS else StabilizationMode.OFF }

    var aiEnhancementEnabled: Boolean
        get() = AI_ENHANCEMENT_SETTINGS_ENABLED &&
            getBooleanCompat(KEY_AI_ENHANCEMENT, AI_ENHANCEMENT_DEFAULT_ENABLED)
        set(v) {
            prefs.edit {
                putBoolean(KEY_AI_ENHANCEMENT, AI_ENHANCEMENT_SETTINGS_ENABLED && v)
            }
        }

    var aiMotionCompensationEnabled: Boolean
        get() = getBooleanCompat(KEY_AI_MOTION_COMPENSATION, false)
        set(v) { prefs.edit { putBoolean(KEY_AI_MOTION_COMPENSATION, v) } }

    var tenBitRecordingEnabled: Boolean
        get() = getBooleanCompat(KEY_TEN_BIT_RECORDING, true)
        set(v) { prefs.edit { putBoolean(KEY_TEN_BIT_RECORDING, v) } }

    var uncompressedCinemaPipelineEnabled: Boolean
        get() = true
        set(@Suppress("UNUSED_PARAMETER") value) {
            // Strict custom recording cannot be disabled into a MediaCodec fallback.
            prefs.edit { putBoolean(KEY_UNCOMPRESSED_CINEMA_PIPELINE, true) }
        }

    /**
     * ON keeps ordinary processed SDR clips as private masters for manual conversion. OFF sends
     * those clips through the existing playable MediaStore route. RAW, Log and NDR routing do not
     * consult this preference.
     */
    var sdrConversionPipelineEnabled: Boolean
        get() = getBooleanCompat(KEY_SDR_CONVERSION_PIPELINE, false)
        set(v) { prefs.edit { putBoolean(KEY_SDR_CONVERSION_PIPELINE, v) } }

    var noiseReductionEnabled: Boolean
        get() = getBooleanCompat(KEY_NOISE_REDUCTION, true)
        set(v) { prefs.edit { putBoolean(KEY_NOISE_REDUCTION, v) } }

    var highEndProfileEnabled: Boolean
        get() = getBooleanCompat(KEY_HIGH_END_PROFILE, false)
        set(v) { prefs.edit { putBoolean(KEY_HIGH_END_PROFILE, v) } }

    var highEndVideoColorEnabled: Boolean
        get() = getBooleanCompat(KEY_HIGH_END_VIDEO_COLOR, false)
        set(v) { prefs.edit { putBoolean(KEY_HIGH_END_VIDEO_COLOR, v) } }

    var highEndFocusAiEnabled: Boolean
        get() = getBooleanCompat(KEY_HIGH_END_FOCUS_AI, false)
        set(v) { prefs.edit { putBoolean(KEY_HIGH_END_FOCUS_AI, v) } }

    var highEndAudioStabilizationEnabled: Boolean
        get() = getBooleanCompat(KEY_HIGH_END_AUDIO_STABILIZATION, false)
        set(v) { prefs.edit { putBoolean(KEY_HIGH_END_AUDIO_STABILIZATION, v) } }

    var highEndManualControlsEnabled: Boolean
        get() = getBooleanCompat(KEY_HIGH_END_MANUAL_CONTROLS, false)
        set(v) { prefs.edit { putBoolean(KEY_HIGH_END_MANUAL_CONTROLS, v) } }

    var afMode: String
        get() = getStringCompat(KEY_AF_MODE, "CONTINUOUS_PICTURE") ?: "CONTINUOUS_PICTURE"
        set(v) { prefs.edit { putString(KEY_AF_MODE, v) } }

    var colorProfile: String
        get() = getStringCompat(KEY_COLOR_PROFILE, "Natural") ?: "Natural"
        set(v) { prefs.edit { putString(KEY_COLOR_PROFILE, v) } }

    var videoColorScienceManuallySelected: Boolean
        get() = getBooleanCompat(KEY_VIDEO_COLOR_SCIENCE_MANUAL, false)
        set(v) { prefs.edit { putBoolean(KEY_VIDEO_COLOR_SCIENCE_MANUAL, v) } }

    /** See [setManualVideoColorSpace]. */
    fun setManualVideoColorScience(value: String) {
        prefs.edit {
            putString(KEY_COLOR_PROFILE, value)
            putBoolean(KEY_VIDEO_COLOR_SCIENCE_MANUAL, true)
        }
    }

    var lutEnabled: Boolean
        get() = getBooleanCompat(KEY_LUT_ENABLED, false)
        set(v) { prefs.edit { putBoolean(KEY_LUT_ENABLED, v) } }

    var lutProfile: String
        get() = getStringCompat(KEY_LUT_PROFILE, "Neutral Film") ?: "Neutral Film"
        set(v) { prefs.edit { putString(KEY_LUT_PROFILE, v) } }

    var customLutPath: String?
        get() = getStringCompat(KEY_CUSTOM_LUT_PATH, null)?.takeIf { it.isNotBlank() }
        set(v) { prefs.edit { putString(KEY_CUSTOM_LUT_PATH, v) } }

    var logEnabled: Boolean
        get() = getBooleanCompat(KEY_LOG_ENABLED, false)
        set(v) { prefs.edit { putBoolean(KEY_LOG_ENABLED, v) } }

    var logStrength: Float
        get() = getFloatCompat(KEY_LOG_STRENGTH, DEFAULT_LOG_STRENGTH)
        set(v) { prefs.edit { putFloat(KEY_LOG_STRENGTH, v.coerceIn(MIN_LOG_STRENGTH, MAX_LOG_STRENGTH)) } }

    var saturation: Float
        get() = getFloatCompat(KEY_SATURATION, DEFAULT_SATURATION)
        set(v) { prefs.edit { putFloat(KEY_SATURATION, v.coerceIn(MIN_COLOR_GAIN, MAX_COLOR_GAIN)) } }

    var contrast: Float
        get() = getFloatCompat(KEY_CONTRAST, DEFAULT_CONTRAST)
        set(v) { prefs.edit { putFloat(KEY_CONTRAST, v.coerceIn(MIN_COLOR_GAIN, MAX_COLOR_GAIN)) } }

    var brightness: Float
        get() = getFloatCompat(KEY_BRIGHTNESS, DEFAULT_BRIGHTNESS)
        set(v) { prefs.edit { putFloat(KEY_BRIGHTNESS, v.coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS)) } }

    var lastISO: Int
        get() = getIntCompat(KEY_LAST_ISO, -1)
        set(v) { prefs.edit { putInt(KEY_LAST_ISO, v) } }

    var lastShutterNs: Long
        get() = getLongCompat(KEY_LAST_SHUTTER_NS, -1L)
        set(v) { prefs.edit { putLong(KEY_LAST_SHUTTER_NS, v) } }

    var lastWBKelvin: Int
        get() = getIntCompat(KEY_LAST_WB_KELVIN, -1)
        set(v) { prefs.edit { putInt(KEY_LAST_WB_KELVIN, v) } }

    /** Persisted green-magenta WB tint (0 = neutral). Paired with [lastWBKelvin] for Pro-Control WB. */
    var lastWBTint: Int
        get() = getIntCompat(KEY_LAST_WB_TINT, 0)
        set(v) { prefs.edit { putInt(KEY_LAST_WB_TINT, v) } }

    /** Persisted Pro manual-focus distance in diopters; a negative value means continuous AF. */
    var lastFocusDistance: Float
        get() = getFloatCompat(KEY_LAST_FOCUS_DISTANCE, -1f)
        set(v) { prefs.edit { putFloat(KEY_LAST_FOCUS_DISTANCE, v) } }

    /** Persisted Camera2 exposure-compensation index. Zero is the neutral effective value. */
    var lastEV: Int
        get() = getIntCompat(KEY_LAST_EV, 0)
        set(v) { prefs.edit { putInt(KEY_LAST_EV, v) } }

    var clipCounter: Int
        get() = getIntCompat(KEY_CLIP_COUNTER, 1)
        set(v) { prefs.edit { putInt(KEY_CLIP_COUNTER, v.coerceAtLeast(1)) } }

    var reelName: String
        get() = getStringCompat(KEY_REEL_NAME, "A001") ?: "A001"
        set(v) { prefs.edit { putString(KEY_REEL_NAME, sanitizeReelName(v)) } }

    fun nextClipName(): String {
        val reel = sanitizeReelName(reelName)
        val name = String.format(Locale.US, "%s_C%03d", reel, clipCounter)
        clipCounter++
        return name
    }

    /**
     * When true, the user-typed [customRecordingName] is used as the base file name for the NEXT
     * recording; CameraViewModel auto-resets this to false after a successful save (one-shot). Default
     * OFF, so recordings are named "CamX ddMMyyyy" unless the user opts in.
     */
    var customRecordingNameEnabled: Boolean
        get() = getBooleanCompat(KEY_CUSTOM_RECORDING_NAME_ENABLED, false)
        set(v) { prefs.edit { putBoolean(KEY_CUSTOM_RECORDING_NAME_ENABLED, v) } }

    /**
     * The user-typed one-shot recording name (stored already-sanitized). Empty => the recorder falls
     * back to the default "CamX ddMMyyyy" name.
     */
    var customRecordingName: String
        get() = getStringCompat(KEY_CUSTOM_RECORDING_NAME, "") ?: ""
        set(v) { prefs.edit { putString(KEY_CUSTOM_RECORDING_NAME, v) } }

    // ════════════════════════════════════════════════════════════════════════════════
    // HDR TONE MAP PERSISTENCE - HIGH-END UPGRADE
    // ════════════════════════════════════════════════════════════════════════════════
    
    fun saveHdrToneMapProfile(
        exposureBoost: Float,
        whitePoint: Float,
        shadowLift: Float,
        midtoneContrast: Float,
        highlightDesaturate: Float,
        blackFloor: Float,
        sceneIntent: String,
        useHybrid: Boolean,
        gtWeight: Float,
        agxWeight: Float,
        midtoneBoost: Float,
        highlightRecovery: Float,
        colorPreservation: Float
    ) {
        prefs.edit {
            putFloat(KEY_HDR_TONEMAP_EXPOSURE_BOOST, exposureBoost)
            putFloat(KEY_HDR_TONEMAP_WHITE_POINT, whitePoint)
            putFloat(KEY_HDR_TONEMAP_SHADOW_LIFT, shadowLift)
            putFloat(KEY_HDR_TONEMAP_MIDTONE_CONTRAST, midtoneContrast)
            putFloat(KEY_HDR_TONEMAP_HIGHLIGHT_DESATURATE, highlightDesaturate)
            putFloat(KEY_HDR_TONEMAP_BLACK_FLOOR, blackFloor)
            putString(KEY_HDR_TONEMAP_SCENE_INTENT, sceneIntent)
            putBoolean(KEY_HDR_TONEMAP_USE_HYBRID, useHybrid)
            putFloat(KEY_HDR_TONEMAP_GT_WEIGHT, gtWeight)
            putFloat(KEY_HDR_TONEMAP_AGX_WEIGHT, agxWeight)
            putFloat(KEY_HDR_TONEMAP_MIDTONE_BOOST, midtoneBoost)
            putFloat(KEY_HDR_TONEMAP_HIGHLIGHT_RECOVERY, highlightRecovery)
            putFloat(KEY_HDR_TONEMAP_COLOR_PRESERVATION, colorPreservation)
        }
    }
    
    fun loadHdrToneMapProfile(): Map<String, Any>? {
        val sceneIntent = getStringCompat(KEY_HDR_TONEMAP_SCENE_INTENT, null)
        if (sceneIntent == null) return null
        
        return mapOf(
            "exposureBoost" to getFloatCompat(KEY_HDR_TONEMAP_EXPOSURE_BOOST, 1.12f),
            "whitePoint" to getFloatCompat(KEY_HDR_TONEMAP_WHITE_POINT, 6.0f),
            "shadowLift" to getFloatCompat(KEY_HDR_TONEMAP_SHADOW_LIFT, 0.014f),
            "midtoneContrast" to getFloatCompat(KEY_HDR_TONEMAP_MIDTONE_CONTRAST, 0.05f),
            "highlightDesaturate" to getFloatCompat(KEY_HDR_TONEMAP_HIGHLIGHT_DESATURATE, 0.06f),
            "blackFloor" to getFloatCompat(KEY_HDR_TONEMAP_BLACK_FLOOR, 0.005f),
            "sceneIntent" to sceneIntent,
            "useHybrid" to getBooleanCompat(KEY_HDR_TONEMAP_USE_HYBRID, true),
            "gtWeight" to getFloatCompat(KEY_HDR_TONEMAP_GT_WEIGHT, 0.38f),
            "agxWeight" to getFloatCompat(KEY_HDR_TONEMAP_AGX_WEIGHT, 0.15f),
            "midtoneBoost" to getFloatCompat(KEY_HDR_TONEMAP_MIDTONE_BOOST, 0.08f),
            "highlightRecovery" to getFloatCompat(KEY_HDR_TONEMAP_HIGHLIGHT_RECOVERY, 0.18f),
            "colorPreservation" to getFloatCompat(KEY_HDR_TONEMAP_COLOR_PRESERVATION, 0.85f)
        )
    }
    
    fun clearHdrToneMapProfile() {
        prefs.edit {
            remove(KEY_HDR_TONEMAP_EXPOSURE_BOOST)
            remove(KEY_HDR_TONEMAP_WHITE_POINT)
            remove(KEY_HDR_TONEMAP_SHADOW_LIFT)
            remove(KEY_HDR_TONEMAP_MIDTONE_CONTRAST)
            remove(KEY_HDR_TONEMAP_HIGHLIGHT_DESATURATE)
            remove(KEY_HDR_TONEMAP_BLACK_FLOOR)
            remove(KEY_HDR_TONEMAP_SCENE_INTENT)
            remove(KEY_HDR_TONEMAP_USE_HYBRID)
            remove(KEY_HDR_TONEMAP_GT_WEIGHT)
            remove(KEY_HDR_TONEMAP_AGX_WEIGHT)
            remove(KEY_HDR_TONEMAP_MIDTONE_BOOST)
            remove(KEY_HDR_TONEMAP_HIGHLIGHT_RECOVERY)
            remove(KEY_HDR_TONEMAP_COLOR_PRESERVATION)
        }
    }
    
    var logRecordingEnabled: Boolean
        get() = getBooleanCompat(KEY_LOG_RECORDING_ENABLED, false)
        set(v) { prefs.edit { putBoolean(KEY_LOG_RECORDING_ENABLED, v) } }

    /** Sticky recall that this device cannot back the offscreen AHB->EGL 10-bit encoder, so the
     *  recording feed-path selector skips it from the first recording instead of re-probing (and
     *  showing the fallback warning) on every launch. SCOPED to a build+OS fingerprint: only honored
     *  when the CURRENT fingerprint matches the one the wall was proven under, so a new app build
     *  (e.g. an EGL fix) or an OS/GPU-driver OTA re-probes the path instead of trusting a stale
     *  failure forever. */
    fun isOffscreenEglEncoderProvenUnavailable(fingerprint: String): Boolean =
        getBooleanCompat(KEY_OFFSCREEN_EGL_ENCODER_UNAVAILABLE, false) &&
            getStringCompat(KEY_OFFSCREEN_EGL_ENCODER_UNAVAILABLE_FINGERPRINT, "") == fingerprint

    /** Persist the offscreen path as a proven hardware wall, scoped to the current build+OS
     *  fingerprint. Call ONLY for a genuine driver wall (no 10-bit recordable EGL config / surface
     *  refused) — never for a transient/token miss, which must stay re-probable. */
    fun setOffscreenEglEncoderProvenUnavailable(fingerprint: String) {
        prefs.edit {
            putBoolean(KEY_OFFSCREEN_EGL_ENCODER_UNAVAILABLE, true)
            putString(KEY_OFFSCREEN_EGL_ENCODER_UNAVAILABLE_FINGERPRINT, fingerprint)
        }
    }
    
    var videoFpsBaseline: Int
        get() = getIntCompat(KEY_VIDEO_FPS_BASELINE, 24)
        set(v) { prefs.edit { putInt(KEY_VIDEO_FPS_BASELINE, v.coerceIn(24, 240)) } }

    /**
     * Enables strict high-end profile packs and quality-focused defaults.
     */
    fun enableStrictHighEndProfileDefaults() {
        val strictBitrate = DEFAULT_VIDEO_BITRATE
        val strictFps = DEFAULT_VIDEO_FPS
        prefs.edit {
            putBoolean(KEY_HIGH_END_PROFILE, true)
            putBoolean(KEY_HIGH_END_VIDEO_COLOR, true)
            putBoolean(KEY_HIGH_END_FOCUS_AI, false)
            putBoolean(KEY_HIGH_END_AUDIO_STABILIZATION, true)
            putBoolean(KEY_HIGH_END_MANUAL_CONTROLS, true)
            putBoolean(KEY_FACE_DETECT, false)
            putBoolean(KEY_EYE_AF, false)
            putBoolean(KEY_AI_ENHANCEMENT, AI_ENHANCEMENT_SETTINGS_ENABLED)
            putInt(KEY_VIDEO_FPS, strictFps)
            putInt(KEY_VIDEO_BITRATE, strictBitrate)
            putString(KEY_VIDEO_BITRATE_MODE, VideoBitrateMode.CINEMA_MASTER.name)
            putInt(KEY_JPEG_QUALITY, DEFAULT_JPEG_QUALITY)
            putBoolean(KEY_TEN_BIT_RECORDING, true)
            putBoolean(KEY_NOISE_REDUCTION, true)
            putString(KEY_STABILIZATION_MODE, StabilizationMode.HYBRID.name)
            putString(KEY_AUDIO_SOURCE, "UNPROCESSED")
        }
    }

    fun resetToDefaults() { prefs.edit { clear() } }

    private fun enforceStrictPackSelectionForHighEndProfile() {
        if (!getBooleanCompat(KEY_HIGH_END_PROFILE, false)) return
        prefs.edit {
            if (!prefs.contains(KEY_HIGH_END_VIDEO_COLOR)) {
                putBoolean(KEY_HIGH_END_VIDEO_COLOR, true)
            }
            putBoolean(KEY_HIGH_END_FOCUS_AI, false)
            if (!prefs.contains(KEY_HIGH_END_AUDIO_STABILIZATION)) {
                putBoolean(KEY_HIGH_END_AUDIO_STABILIZATION, true)
            }
            if (!prefs.contains(KEY_HIGH_END_MANUAL_CONTROLS)) {
                putBoolean(KEY_HIGH_END_MANUAL_CONTROLS, true)
            }
            if (strictHighEndEnabled()) {
                if (!prefs.contains(KEY_VIDEO_FPS)) {
                    putInt(KEY_VIDEO_FPS, getIntCompat(KEY_VIDEO_FPS, DEFAULT_VIDEO_FPS).coerceIn(MIN_VIDEO_FPS, MAX_VIDEO_FPS))
                }
                if (!prefs.contains(KEY_VIDEO_BITRATE)) {
                    putInt(
                        KEY_VIDEO_BITRATE,
                        DEFAULT_VIDEO_BITRATE
                    )
                }
                if (!prefs.contains(KEY_VIDEO_BITRATE_MODE)) {
                    putString(KEY_VIDEO_BITRATE_MODE, VideoBitrateMode.CINEMA_MASTER.name)
                }
                if (!prefs.contains(KEY_TEN_BIT_RECORDING)) putBoolean(KEY_TEN_BIT_RECORDING, true)
                if (!prefs.contains(KEY_NOISE_REDUCTION)) putBoolean(KEY_NOISE_REDUCTION, true)
            }
        }
    }

    private fun sanitizeReelName(value: String): String {
        val compact = value.trim().uppercase(Locale.US)
        if (compact.isEmpty()) return "A001"
        val safe = buildString(compact.length) {
            compact.forEach { ch ->
                if (ch.isLetterOrDigit()) append(ch)
            }
        }
        return safe.take(8).ifBlank { "A001" }
    }

    private fun getStringCompat(key: String, defaultValue: String?): String? {
        if (PreferencesTypedFastPathFlag.enabled) {
            try {
                return prefs.getString(key, defaultValue)
            } catch (_: ClassCastException) {
                // Stored under a non-String type; fall through to the coercing map lookup.
            }
        }
        return when (val value = prefs.all[key]) {
            null -> defaultValue
            is String -> value
            else -> value.toString()
        }
    }

    private fun getIntCompat(key: String, defaultValue: Int): Int {
        if (PreferencesTypedFastPathFlag.enabled) {
            try {
                return prefs.getInt(key, defaultValue)
            } catch (_: ClassCastException) {
                // Stored under a non-Int type; fall through to the coercing map lookup.
            }
        }
        return when (val value = prefs.all[key]) {
            null -> defaultValue
            is Int -> value
            is Long -> value.toInt()
            is Float -> value.toInt()
            is Double -> value.toInt()
            is String -> value.toIntOrNull() ?: defaultValue
            is Boolean -> if (value) 1 else 0
            else -> defaultValue
        }
    }

    private fun getLongCompat(key: String, defaultValue: Long): Long {
        if (PreferencesTypedFastPathFlag.enabled) {
            try {
                return prefs.getLong(key, defaultValue)
            } catch (_: ClassCastException) {
                // Stored under a non-Long type; fall through to the coercing map lookup.
            }
        }
        return when (val value = prefs.all[key]) {
            null -> defaultValue
            is Long -> value
            is Int -> value.toLong()
            is Float -> value.toLong()
            is Double -> value.toLong()
            is String -> value.toLongOrNull() ?: defaultValue
            else -> defaultValue
        }
    }

    private fun getFloatCompat(key: String, defaultValue: Float): Float {
        if (PreferencesTypedFastPathFlag.enabled) {
            try {
                return prefs.getFloat(key, defaultValue)
            } catch (_: ClassCastException) {
                // Stored under a non-Float type; fall through to the coercing map lookup.
            }
        }
        return when (val value = prefs.all[key]) {
            null -> defaultValue
            is Float -> value
            is Double -> value.toFloat()
            is Int -> value.toFloat()
            is Long -> value.toFloat()
            is String -> value.toFloatOrNull() ?: defaultValue
            else -> defaultValue
        }
    }

    private fun getBooleanCompat(key: String, defaultValue: Boolean): Boolean {
        if (PreferencesTypedFastPathFlag.enabled) {
            try {
                return prefs.getBoolean(key, defaultValue)
            } catch (_: ClassCastException) {
                // Stored under a non-Boolean type; fall through to the coercing map lookup.
            }
        }
        return when (val value = prefs.all[key]) {
            null -> defaultValue
            is Boolean -> value
            is String -> value.equals("true", ignoreCase = true)
            is Int -> value != 0
            is Long -> value != 0L
            else -> defaultValue
        }
    }
}
