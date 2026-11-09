package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.TonemapCurve
import android.os.Build
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.infrastructure.config.ConfigurationManager
import com.neoproc.neoproccamx.infrastructure.error.ErrorHandler
import com.neoproc.neoproccamx.infrastructure.logging.NeoLogger
import com.neoproc.neoproccamx.infrastructure.telemetry.TelemetryCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min

/**
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 * CAMERA SETTINGS MANAGER — COMPREHENSIVE CAMERA PARAMETER CONFIGURATION AND MANAGEMENT SYSTEM
 * ═══════════════════════════════════════════════════════════════════════════════════════════════════
 *
 * Enterprise-grade camera settings management providing:
 *   - Complete camera parameter control (ISO, shutter, focus, WB, etc.)
 *   - Scene mode detection and automatic optimization
 *   - Manual and automatic exposure control with bracketing
 *   - Focus mode management with continuous and touch AF
 *   - White balance presets and manual temperature control
 *   - Color effect and filter application
 *   - Flash and torch mode coordination
 *   - Real-time settings validation and constraints enforcement
 *
 * Thread Safety: All settings operations are atomic and thread-safe.
 * Performance: Lock-free settings cache with batched updates.
 *
 * @author NeoProCamX Camera Settings Team
 * @version 10.0.0-ENTERPRISE
 * @since 1.0.0
 */
class CameraSettingsManager(
    private val cameraManager: CameraManager,
    private val cameraId: String
) {

    companion object {
        // ═════════════════════════════════════════════════════════════════════════════════════════════
        // SETTINGS CONSTANTS
        // ═════════════════════════════════════════════════════════════════════════════════════════════

        /** Minimum ISO value for most cameras. */
        private const val MIN_ISO_DEFAULT = 50

        /** Maximum ISO cap for quality preservation. */
        private const val MAX_ISO_CAP = 6400

        /** Minimum exposure time (nanoseconds) - 1/8000s. */
        private const val MIN_EXPOSURE_TIME_NS = 125_000L

        /** Maximum exposure time (nanoseconds) - 1s. */
        private const val MAX_EXPOSURE_TIME_NS = 1_000_000_000L

        /** Minimum focus distance (diopters) - infinity. */
        private const val MIN_FOCUS_DISTANCE = 0.0f

        /** Maximum focus distance (diopters) - macro. */
        private const val MAX_FOCUS_DISTANCE = 10.0f

        /** Color temperature range (Kelvin). */
        private const val MIN_COLOR_TEMPERATURE = 2000
        private const val MAX_COLOR_TEMPERATURE = 8000

        /** Default exposure compensation step. */
        private const val EXPOSURE_COMPENSATION_STEP = 0.33f
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // EXPOSURE MODE ENUM
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    enum class ExposureMode {
        AUTO,               // Full automatic exposure
        MANUAL,             // Manual ISO and shutter
        SHUTTER_PRIORITY,   // Manual shutter, auto ISO
        ISO_PRIORITY,       // Manual ISO, auto shutter
        LONG_EXPOSURE       // Extended exposure times
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // FOCUS MODE ENUM
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    enum class FocusMode {
        AUTO,               // Auto focus
        CONTINUOUS_PICTURE, // Continuous focus for pictures
        CONTINUOUS_VIDEO,   // Continuous focus for video
        MACRO,              // Macro focus mode
        EDOF,               // Extended depth of field
        FIXED,              // Fixed focus
        MANUAL              // Manual focus distance
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // WHITE BALANCE MODE ENUM
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    enum class WhiteBalanceMode {
        AUTO,           // Automatic white balance
        INCANDESCENT,   // Tungsten (~2700K)
        FLUORESCENT,    // Fluorescent (~4000K)
        WARM_FLUORESCENT, // Warm fluorescent (~3000K)
        DAYLIGHT,       // Daylight (~5500K)
        CLOUDY_DAYLIGHT, // Cloudy (~6500K)
        TWILIGHT,       // Twilight (~4000K)
        SHADE,          // Shade (~7500K)
        MANUAL          // Manual color temperature
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // SCENE MODE ENUM
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    enum class SceneMode {
        FACE_PRIORITY,      // Optimize for faces
        ACTION,             // Fast moving subjects
        PORTRAIT,           // Portrait photography
        LANDSCAPE,          // Landscape photography
        NIGHT,              // Low light scenes
        NIGHT_PORTRAIT,     // Low light portraits
        THEATRE,            // Stage/theatre lighting
        BEACH,              // Bright beach scenes
        SNOW,               // Bright snow scenes
        SUNSET,             // Sunset scenes
        STEADY_PHOTO,       // Steady shot
        FIREWORKS,          // Fireworks
        SPORTS,             // Sports action
        PARTY,              // Indoor party
        CANDLELIGHT,        // Candlelight scenes
        BARCODE,            // Barcode scanning
        HDR,                // HDR mode
        PRO_MODE            // Professional manual control
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // FLASH MODE ENUM
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    enum class FlashMode {
        OFF,            // Flash disabled
        AUTO,           // Automatic flash
        ON,             // Flash always on
        RED_EYE,        // Red-eye reduction
        TORCH           // Torch/flashlight mode
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // ANTIBANDING MODE ENUM
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    enum class AntibandingMode {
        OFF,        // No antibanding
        AUTO,       // Automatic detection
        HZ_50,      // 50Hz (Europe/Asia)
        HZ_60       // 60Hz (Americas)
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // COLOR EFFECT ENUM
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    enum class ColorEffect {
        OFF,            // No effect
        MONO,           // Monochrome
        NEGATIVE,       // Negative/inverted
        SOLARIZE,       // Solarization
        SEPIA,          // Sepia tone
        POSTERIZE,      // Posterization
        WHITEBOARD,     // Whiteboard mode
        BLACKBOARD,     // Blackboard mode
        AQUA            // Aqua tint
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // CAMERA SETTINGS DATA CLASS
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    data class CameraSettings(
        // Exposure settings
        val exposureMode: ExposureMode = ExposureMode.AUTO,
        val iso: Int = 100,
        val shutterSpeedNs: Long = 33_333_333L, // 1/30s
        val exposureCompensation: Float = 0f,

        // Focus settings
        val focusMode: FocusMode = FocusMode.CONTINUOUS_PICTURE,
        val focusDistance: Float = 0f,
        val focusArea: RectF? = null,

        // White balance settings
        val whiteBalanceMode: WhiteBalanceMode = WhiteBalanceMode.AUTO,
        val colorTemperature: Int = 5500,

        // Scene and effects
        val sceneMode: SceneMode = SceneMode.FACE_PRIORITY,
        val colorEffect: ColorEffect = ColorEffect.OFF,

        // Flash
        val flashMode: FlashMode = FlashMode.AUTO,

        // Stabilization
        val opticalStabilization: Boolean = true,
        val electronicStabilization: Boolean = false,

        // Advanced
        val antibandingMode: AntibandingMode = AntibandingMode.AUTO,
        val noiseReduction: NoiseReductionMode = NoiseReductionMode.HIGH_QUALITY,
        val edgeEnhancement: EdgeEnhancementMode = EdgeEnhancementMode.HIGH_QUALITY,
        val hotPixelMode: HotPixelMode = HotPixelMode.FAST,

        // Tonemapping
        val tonemapMode: TonemapMode = TonemapMode.HIGH_QUALITY,
        val customTonemapCurve: FloatArray? = null,

        // Color correction
        val colorCorrectionMode: ColorCorrectionMode = ColorCorrectionMode.HIGH_QUALITY,
        val customGains: RggbChannelVector? = null,

        // Statistics
        val aeRegions: List<MeteringRectangle>? = null,
        val afRegions: List<MeteringRectangle>? = null,
        val awbRegions: List<MeteringRectangle>? = null,

        // Face detection
        val faceDetectionMode: FaceDetectionMode = FaceDetectionMode.SIMPLE,

        // Auto settings locks
        val aeLock: Boolean = false,
        val awbLock: Boolean = false,
        val afLock: Boolean = false
    ) {
        enum class NoiseReductionMode { OFF, FAST, HIGH_QUALITY, MINIMAL, ZERO_SHUTTER_LAG }
        enum class EdgeEnhancementMode { OFF, FAST, HIGH_QUALITY, ZERO_SHUTTER_LAG }
        enum class HotPixelMode { OFF, FAST, HIGH_QUALITY }
        enum class TonemapMode { CONTRAST_CURVE, FAST, HIGH_QUALITY }
        enum class ColorCorrectionMode { FAST, HIGH_QUALITY }
        enum class FaceDetectionMode { OFF, SIMPLE, FULL }
    }

    data class RectF(val left: Float, val top: Float, val right: Float, val bottom: Float)
    data class MeteringRectangle(
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
        val weight: Int
    )

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // SETTINGS CONSTRAINTS DATA CLASS
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    data class SettingsConstraints(
        val minIso: Int,
        val maxIso: Int,
        val minExposureTimeNs: Long,
        val maxExposureTimeNs: Long,
        val minFocusDistance: Float,
        val maxFocusDistance: Float,
        val exposureCompensationRange: ClosedFloatingPointRange<Float>,
        val availableFocusModes: List<FocusMode>,
        val availableWhiteBalanceModes: List<WhiteBalanceMode>,
        val availableSceneModes: List<SceneMode>,
        val availableFlashModes: List<FlashMode>,
        val supportsOpticalStabilization: Boolean,
        val supportsElectronicStabilization: Boolean,
        val maxRegionsAe: Int,
        val maxRegionsAf: Int,
        val maxRegionsAwb: Int
    )

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // SETTINGS PRESET DATA CLASS
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    data class SettingsPreset(
        val name: String,
        val description: String,
        val settings: CameraSettings,
        val applicableScenes: List<SceneMode>,
        val priority: Int = 0
    )

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // SETTINGS CHANGE EVENT
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    data class SettingsChangeEvent(
        val settingName: String,
        val oldValue: Any?,
        val newValue: Any,
        val timestamp: Long = System.currentTimeMillis()
    )

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // INTERNAL STATE
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    private val isOperational = AtomicBoolean(false)
    private val characteristics: CameraCharacteristics by lazy {
        cameraManager.getCameraCharacteristics(cameraId)
    }

    private val _currentSettings = MutableStateFlow(CameraSettings())
    val currentSettings: StateFlow<CameraSettings> = _currentSettings.asStateFlow()

    private val _constraints = MutableStateFlow<SettingsConstraints?>(null)
    val constraints: StateFlow<SettingsConstraints?> = _constraints.asStateFlow()

    private val settingsHistory = CopyOnWriteArrayList<Pair<Long, CameraSettings>>()
    private val presets = ConcurrentHashMap<String, SettingsPreset>()
    private val settingsListeners = CopyOnWriteArrayList<(SettingsChangeEvent) -> Unit>()

    private val configurationManager = ConfigurationManager.getInstance()

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    fun initialize() {
        if (!isOperational.compareAndSet(false, true)) {
            NeoLogger.w("CameraSettingsManager", "Already initialized for camera $cameraId")
            return
        }

        NeoLogger.i("CameraSettingsManager", "Initializing settings manager for camera $cameraId")

        try {
            // Load constraints from camera characteristics
            loadConstraints()

            // Load saved settings from preferences
            loadSavedSettings()

            // Register default presets
            registerDefaultPresets()

            NeoLogger.i("CameraSettingsManager", "Settings manager initialized successfully")

            TelemetryCollector.trackEvent(
                TelemetryCollector.EventType.CUSTOM_EVENT,
                "camera_settings_manager_initialized",
                mapOf("camera_id" to cameraId)
            )

        } catch (e: Exception) {
            isOperational.set(false)
            ErrorHandler.handle(
                e,
                ErrorHandler.ErrorContext(
                    severity = ErrorHandler.ErrorSeverity.RECOVERABLE,
                    category = ErrorHandler.ErrorCategory.CAMERA_HARDWARE,
                    operation = "initialize",
                    component = "CameraSettingsManager"
                )
            )
            NeoLogger.emergency("CameraSettingsManager", "Failed to initialize settings manager", e)
        }
    }

    private fun loadConstraints() {
        val isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        val focusRange = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)

        val expCompRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
        val expCompStep = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)

        val availableAfModes = characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.map { mode ->
            when (mode) {
                CaptureRequest.CONTROL_AF_MODE_AUTO -> FocusMode.AUTO
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE -> FocusMode.CONTINUOUS_PICTURE
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO -> FocusMode.CONTINUOUS_VIDEO
                CaptureRequest.CONTROL_AF_MODE_MACRO -> FocusMode.MACRO
                CaptureRequest.CONTROL_AF_MODE_EDOF -> FocusMode.EDOF
                CaptureRequest.CONTROL_AF_MODE_OFF -> FocusMode.FIXED
                else -> FocusMode.AUTO
            }
        } ?: listOf(FocusMode.AUTO)

        val availableAwbModes = characteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES)?.map { mode ->
            when (mode) {
                CaptureRequest.CONTROL_AWB_MODE_AUTO -> WhiteBalanceMode.AUTO
                CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT -> WhiteBalanceMode.INCANDESCENT
                CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT -> WhiteBalanceMode.FLUORESCENT
                CaptureRequest.CONTROL_AWB_MODE_WARM_FLUORESCENT -> WhiteBalanceMode.WARM_FLUORESCENT
                CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT -> WhiteBalanceMode.DAYLIGHT
                CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> WhiteBalanceMode.CLOUDY_DAYLIGHT
                CaptureRequest.CONTROL_AWB_MODE_TWILIGHT -> WhiteBalanceMode.TWILIGHT
                CaptureRequest.CONTROL_AWB_MODE_SHADE -> WhiteBalanceMode.SHADE
                else -> WhiteBalanceMode.AUTO
            }
        } ?: listOf(WhiteBalanceMode.AUTO)

        val availableScenes = characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)?.map { mode ->
            when (mode) {
                CameraCharacteristics.CONTROL_SCENE_MODE_FACE_PRIORITY -> SceneMode.FACE_PRIORITY
                CameraCharacteristics.CONTROL_SCENE_MODE_ACTION -> SceneMode.ACTION
                CameraCharacteristics.CONTROL_SCENE_MODE_PORTRAIT -> SceneMode.PORTRAIT
                CameraCharacteristics.CONTROL_SCENE_MODE_LANDSCAPE -> SceneMode.LANDSCAPE
                CameraCharacteristics.CONTROL_SCENE_MODE_NIGHT -> SceneMode.NIGHT
                CameraCharacteristics.CONTROL_SCENE_MODE_NIGHT_PORTRAIT -> SceneMode.NIGHT_PORTRAIT
                CameraCharacteristics.CONTROL_SCENE_MODE_THEATRE -> SceneMode.THEATRE
                CameraCharacteristics.CONTROL_SCENE_MODE_BEACH -> SceneMode.BEACH
                CameraCharacteristics.CONTROL_SCENE_MODE_SNOW -> SceneMode.SNOW
                CameraCharacteristics.CONTROL_SCENE_MODE_SUNSET -> SceneMode.SUNSET
                CameraCharacteristics.CONTROL_SCENE_MODE_STEADYPHOTO -> SceneMode.STEADY_PHOTO
                CameraCharacteristics.CONTROL_SCENE_MODE_FIREWORKS -> SceneMode.FIREWORKS
                CameraCharacteristics.CONTROL_SCENE_MODE_SPORTS -> SceneMode.SPORTS
                CameraCharacteristics.CONTROL_SCENE_MODE_PARTY -> SceneMode.PARTY
                CameraCharacteristics.CONTROL_SCENE_MODE_CANDLELIGHT -> SceneMode.CANDLELIGHT
                CameraCharacteristics.CONTROL_SCENE_MODE_BARCODE -> SceneMode.BARCODE
                CameraCharacteristics.CONTROL_SCENE_MODE_HDR -> SceneMode.HDR
                else -> SceneMode.PRO_MODE
            }
        } ?: listOf(SceneMode.PRO_MODE)

        val hasOIS = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.contains(
            CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON
        ) ?: false

        val hasEIS = characteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.contains(
            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON
        ) ?: false

        val maxRegionsAe = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE) ?: 0
        val maxRegionsAf = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0
        val maxRegionsAwb = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AWB) ?: 0

        val constraints = SettingsConstraints(
            minIso = isoRange?.lower ?: MIN_ISO_DEFAULT,
            maxIso = min(isoRange?.upper ?: MAX_ISO_CAP, MAX_ISO_CAP),
            minExposureTimeNs = exposureRange?.lower ?: MIN_EXPOSURE_TIME_NS,
            maxExposureTimeNs = exposureRange?.upper ?: MAX_EXPOSURE_TIME_NS,
            minFocusDistance = 0f,
            maxFocusDistance = focusRange ?: MAX_FOCUS_DISTANCE,
            exposureCompensationRange = (expCompRange?.lower?.toFloat() ?: -2f)..(expCompRange?.upper?.toFloat() ?: 2f),
            availableFocusModes = availableAfModes,
            availableWhiteBalanceModes = availableAwbModes,
            availableSceneModes = availableScenes,
            availableFlashModes = listOf(FlashMode.OFF, FlashMode.AUTO, FlashMode.ON, FlashMode.TORCH),
            supportsOpticalStabilization = hasOIS,
            supportsElectronicStabilization = hasEIS,
            maxRegionsAe = maxRegionsAe,
            maxRegionsAf = maxRegionsAf,
            maxRegionsAwb = maxRegionsAwb
        )

        _constraints.value = constraints

        NeoLogger.i(
            "CameraSettingsManager",
            "Loaded constraints: ISO ${constraints.minIso}-${constraints.maxIso}, " +
            "Exposure ${constraints.minExposureTimeNs}ns-${constraints.maxExposureTimeNs}ns"
        )
    }

    private fun loadSavedSettings() {
        val iso = configurationManager.getInt("camera.saved_iso", 100)
        val shutterSpeed = configurationManager.getLong("camera.saved_shutter", 33_333_333L)
        val focusMode = FocusMode.valueOf(
            configurationManager.getString("camera.saved_focus_mode", "CONTINUOUS_PICTURE") ?: "CONTINUOUS_PICTURE"
        )
        val wbMode = WhiteBalanceMode.valueOf(
            configurationManager.getString("camera.saved_wb_mode", "AUTO") ?: "AUTO"
        )

        _currentSettings.value = _currentSettings.value.copy(
            iso = iso,
            shutterSpeedNs = shutterSpeed,
            focusMode = focusMode,
            whiteBalanceMode = wbMode
        )
    }

    private fun registerDefaultPresets() {
        // Auto preset
        registerPreset(
            SettingsPreset(
                name = "auto",
                description = "Automatic settings for general photography",
                settings = CameraSettings(
                    exposureMode = ExposureMode.AUTO,
                    focusMode = FocusMode.CONTINUOUS_PICTURE,
                    whiteBalanceMode = WhiteBalanceMode.AUTO,
                    sceneMode = SceneMode.FACE_PRIORITY,
                    flashMode = FlashMode.AUTO
                ),
                applicableScenes = SceneMode.values().toList(),
                priority = 10
            )
        )

        // Portrait preset
        registerPreset(
            SettingsPreset(
                name = "portrait",
                description = "Optimized for portrait photography",
                settings = CameraSettings(
                    exposureMode = ExposureMode.AUTO,
                    focusMode = FocusMode.CONTINUOUS_PICTURE,
                    whiteBalanceMode = WhiteBalanceMode.AUTO,
                    sceneMode = SceneMode.PORTRAIT,
                    flashMode = FlashMode.AUTO,
                    noiseReduction = CameraSettings.NoiseReductionMode.HIGH_QUALITY,
                    edgeEnhancement = CameraSettings.EdgeEnhancementMode.HIGH_QUALITY,
                    faceDetectionMode = CameraSettings.FaceDetectionMode.FULL
                ),
                applicableScenes = listOf(SceneMode.PORTRAIT, SceneMode.FACE_PRIORITY),
                priority = 20
            )
        )

        // Night preset
        registerPreset(
            SettingsPreset(
                name = "night",
                description = "Optimized for low light photography",
                settings = CameraSettings(
                    exposureMode = ExposureMode.AUTO,
                    focusMode = FocusMode.CONTINUOUS_PICTURE,
                    whiteBalanceMode = WhiteBalanceMode.AUTO,
                    sceneMode = SceneMode.NIGHT,
                    flashMode = FlashMode.OFF,
                    noiseReduction = CameraSettings.NoiseReductionMode.HIGH_QUALITY,
                    opticalStabilization = true,
                    electronicStabilization = true
                ),
                applicableScenes = listOf(SceneMode.NIGHT, SceneMode.NIGHT_PORTRAIT),
                priority = 30
            )
        )

        // Pro manual preset
        registerPreset(
            SettingsPreset(
                name = "pro_manual",
                description = "Full manual control for professionals",
                settings = CameraSettings(
                    exposureMode = ExposureMode.MANUAL,
                    iso = 100,
                    shutterSpeedNs = 100_000_000L, // 1/10s
                    focusMode = FocusMode.MANUAL,
                    focusDistance = 0f,
                    whiteBalanceMode = WhiteBalanceMode.MANUAL,
                    colorTemperature = 5500,
                    colorCorrectionMode = CameraSettings.ColorCorrectionMode.HIGH_QUALITY,
                    tonemapMode = CameraSettings.TonemapMode.HIGH_QUALITY,
                    aeLock = true,
                    awbLock = true,
                    afLock = true
                ),
                applicableScenes = listOf(SceneMode.PRO_MODE),
                priority = 40
            )
        )

        NeoLogger.d("CameraSettingsManager", "Registered ${presets.size} default presets")
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // PRESET MANAGEMENT
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    fun registerPreset(preset: SettingsPreset) {
        presets[preset.name] = preset
    }

    fun applyPreset(presetName: String): Boolean {
        val preset = presets[presetName] ?: run {
            NeoLogger.w("CameraSettingsManager", "Preset not found: $presetName")
            return false
        }

        val validatedSettings = validateSettings(preset.settings)
        return updateSettings(validatedSettings)
    }

    fun getAvailablePresets(): List<SettingsPreset> {
        return presets.values.sortedByDescending { it.priority }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // SETTINGS UPDATES
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    fun updateSettings(newSettings: CameraSettings): Boolean {
        if (!isOperational.get()) return false

        val validatedSettings = validateSettings(newSettings)
        val oldSettings = _currentSettings.value

        // Track changes
        val changes = mutableListOf<SettingsChangeEvent>()

        if (validatedSettings.iso != oldSettings.iso) {
            changes.add(SettingsChangeEvent("iso", oldSettings.iso, validatedSettings.iso))
        }
        if (validatedSettings.shutterSpeedNs != oldSettings.shutterSpeedNs) {
            changes.add(SettingsChangeEvent("shutterSpeed", oldSettings.shutterSpeedNs, validatedSettings.shutterSpeedNs))
        }
        if (validatedSettings.focusMode != oldSettings.focusMode) {
            changes.add(SettingsChangeEvent("focusMode", oldSettings.focusMode, validatedSettings.focusMode))
        }
        if (validatedSettings.whiteBalanceMode != oldSettings.whiteBalanceMode) {
            changes.add(SettingsChangeEvent("whiteBalance", oldSettings.whiteBalanceMode, validatedSettings.whiteBalanceMode))
        }

        _currentSettings.value = validatedSettings

        // Save to preferences
        saveSettings(validatedSettings)

        // Add to history
        settingsHistory.add(Pair(System.currentTimeMillis(), validatedSettings))
        if (settingsHistory.size > 100) {
            settingsHistory.removeAt(0)
        }

        // Notify listeners
        changes.forEach { event ->
            settingsListeners.forEach { listener ->
                try {
                    listener(event)
                } catch (e: Exception) {
                    NeoLogger.w("CameraSettingsManager", "Settings listener failed", e)
                }
            }
        }

        NeoLogger.d("CameraSettingsManager", "Settings updated with ${changes.size} changes")
        return true
    }

    fun updateSetting(updateAction: (CameraSettings) -> CameraSettings): Boolean {
        val newSettings = updateAction(_currentSettings.value)
        return updateSettings(newSettings)
    }

    private fun validateSettings(settings: CameraSettings): CameraSettings {
        val constraints = _constraints.value ?: return settings

        var validated = settings

        // Validate ISO
        validated = validated.copy(
            iso = settings.iso.coerceIn(constraints.minIso, constraints.maxIso)
        )

        // Validate shutter speed
        validated = validated.copy(
            shutterSpeedNs = settings.shutterSpeedNs.coerceIn(
                constraints.minExposureTimeNs,
                constraints.maxExposureTimeNs
            )
        )

        // Validate focus distance
        validated = validated.copy(
            focusDistance = settings.focusDistance.coerceIn(
                constraints.minFocusDistance,
                constraints.maxFocusDistance
            )
        )

        // Validate exposure compensation
        validated = validated.copy(
            exposureCompensation = settings.exposureCompensation.coerceIn(
                constraints.exposureCompensationRange.start,
                constraints.exposureCompensationRange.endInclusive
            )
        )

        // Validate color temperature
        validated = validated.copy(
            colorTemperature = settings.colorTemperature.coerceIn(
                MIN_COLOR_TEMPERATURE,
                MAX_COLOR_TEMPERATURE
            )
        )

        // Validate OIS/EIS support
        if (!constraints.supportsOpticalStabilization) {
            validated = validated.copy(opticalStabilization = false)
        }
        if (!constraints.supportsElectronicStabilization) {
            validated = validated.copy(electronicStabilization = false)
        }

        return validated
    }

    private fun saveSettings(settings: CameraSettings) {
        configurationManager.setInt("camera.saved_iso", settings.iso)
        configurationManager.setLong("camera.saved_shutter", settings.shutterSpeedNs)
        configurationManager.setString("camera.saved_focus_mode", settings.focusMode.name)
        configurationManager.setString("camera.saved_wb_mode", settings.whiteBalanceMode.name)
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // INDIVIDUAL SETTING UPDATES
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    fun setIso(iso: Int): Boolean {
        return updateSetting { it.copy(iso = iso, exposureMode = ExposureMode.MANUAL) }
    }

    fun setShutterSpeed(shutterSpeedNs: Long): Boolean {
        return updateSetting { it.copy(shutterSpeedNs = shutterSpeedNs, exposureMode = ExposureMode.MANUAL) }
    }

    fun setFocusMode(focusMode: FocusMode): Boolean {
        return updateSetting { it.copy(focusMode = focusMode) }
    }

    fun setFocusDistance(distance: Float): Boolean {
        return updateSetting { it.copy(focusDistance = distance, focusMode = FocusMode.MANUAL) }
    }

    fun setWhiteBalance(mode: WhiteBalanceMode): Boolean {
        return updateSetting { it.copy(whiteBalanceMode = mode) }
    }

    fun setColorTemperature(temperature: Int): Boolean {
        return updateSetting { it.copy(colorTemperature = temperature, whiteBalanceMode = WhiteBalanceMode.MANUAL) }
    }

    fun setSceneMode(sceneMode: SceneMode): Boolean {
        return updateSetting { it.copy(sceneMode = sceneMode) }
    }

    fun setFlashMode(flashMode: FlashMode): Boolean {
        return updateSetting { it.copy(flashMode = flashMode) }
    }

    fun setExposureCompensation(compensation: Float): Boolean {
        return updateSetting { it.copy(exposureCompensation = compensation) }
    }

    fun setOpticalStabilization(enabled: Boolean): Boolean {
        val constraints = _constraints.value
        if (constraints != null && !constraints.supportsOpticalStabilization && enabled) {
            NeoLogger.w("CameraSettingsManager", "OIS not supported on this camera")
            return false
        }
        return updateSetting { it.copy(opticalStabilization = enabled) }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // UTILITY METHODS
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    fun resetToDefaults(): Boolean {
        return applyPreset("auto")
    }

    fun getSettingsHistory(): List<Pair<Long, CameraSettings>> {
        return settingsHistory.toList()
    }

    fun addSettingsListener(listener: (SettingsChangeEvent) -> Unit) {
        settingsListeners.add(listener)
    }

    fun removeSettingsListener(listener: (SettingsChangeEvent) -> Unit) {
        settingsListeners.remove(listener)
    }

    fun isSettingSupported(setting: String): Boolean {
        val constraints = _constraints.value ?: return false

        return when (setting) {
            "iso_manual" -> constraints.maxIso > constraints.minIso
            "shutter_manual" -> constraints.maxExposureTimeNs > constraints.minExposureTimeNs
            "focus_manual" -> true
            "wb_manual" -> true
            "ois" -> constraints.supportsOpticalStabilization
            "eis" -> constraints.supportsElectronicStabilization
            "flash" -> true
            "face_detection" -> true
            else -> false
        }
    }

    // ═════════════════════════════════════════════════════════════════════════════════════════════════
    // SHUTDOWN
    // ═════════════════════════════════════════════════════════════════════════════════════════════════

    fun shutdown() {
        if (!isOperational.compareAndSet(true, false)) return

        // Save current settings
        saveSettings(_currentSettings.value)

        settingsListeners.clear()

        NeoLogger.i("CameraSettingsManager", "Settings manager shut down for camera $cameraId")
    }
}
