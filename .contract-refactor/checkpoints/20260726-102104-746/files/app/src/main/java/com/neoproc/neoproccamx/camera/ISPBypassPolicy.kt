package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.TonemapCurve
import android.os.Build

/**
 * Phase 1b: ISP BYPASS POLICY
 * 
 * Implements strict ISP bypass for high-bitrate quality-preserving capture:
 * 1. Linear 1:1 tonemap curve (no gamma correction)
 * 2. Edge mode OFF (disable sharpening)
 * 3. Noise reduction OFF (preserve all pixels)
 * 4. Hot pixel mode OFF (preserve sensor noise signature)
 * 5. Shading correction OFF (preserve natural falloff)
 * 6. Color aberration correction OFF (1:1 sensor mapping)
 * 7. Distortion correction is policy controlled:
 *    - SENSOR_NATIVE_OFF preserves raw lens geometry when OFF is exposed.
 *    - CORRECTED_ON keeps geometry corrected using the best advertised ON mode.
 * 
 * Result: best-available direct sensor request path. Complete bypass depends on
 * the OFF modes exposed by the device's Camera2 characteristics.
 */
internal class ISPBypassPolicy(
    private val characteristics: CameraCharacteristics,
    distortionCorrectionPreference: IspBypassDistortionCorrectionPreference =
        IspBypassDistortionCorrectionPreference.SENSOR_NATIVE_OFF
) {

    private val requestPlan = ISPBypassRequestPlanPolicy.resolve(
        availability = characteristics.toIspBypassAvailability(),
        distortionCorrectionPreference = distortionCorrectionPreference
    )

    /**
     * Apply total ISP bypass to capture request for RAW/LOG workflows
     * Returns count of parameters modified
     */
    fun applyTotalISPBypass(
        captureRequest: CaptureRequest.Builder,
        includeLinearTonemap: Boolean = true,
        tonemapCurveFactory: (Int) -> TonemapCurve
    ): Int {
        var modified = 0
        
        // Phase 1b: Linear 1:1 tonemap (no gamma correction)
        if (includeLinearTonemap && requestPlan.supports(IspBypassFeature.TONEMAP_CONTRAST_CURVE)) {
            val maxCurvePoints = characteristics.get(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS) ?: 64
            val linearCurve = tonemapCurveFactory(maxCurvePoints)
            captureRequest.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
            captureRequest.set(CaptureRequest.TONEMAP_CURVE, linearCurve)
            modified += 2
        }
        
        // Phase 1b: Disable all ISP processing filters
        if (requestPlan.supports(IspBypassFeature.EDGE_OFF)) {
            captureRequest.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
            modified++
        }
        
        if (requestPlan.supports(IspBypassFeature.NOISE_REDUCTION_OFF)) {
            captureRequest.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
            modified++
        }
        
        if (requestPlan.supports(IspBypassFeature.HOT_PIXEL_OFF)) {
            captureRequest.set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_OFF)
            modified++
        }
        
        if (requestPlan.supports(IspBypassFeature.SHADING_OFF)) {
            captureRequest.set(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_OFF)
            modified++
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            requestPlan.supports(IspBypassFeature.COLOR_ABERRATION_OFF)
        ) {
            captureRequest.set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF)
            modified++
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            requestPlan.distortionCorrectionMode?.let { mode ->
                captureRequest.set(CaptureRequest.DISTORTION_CORRECTION_MODE, mode)
                modified++
            }
        }
        
        return modified
    }
    
    /**
     * Check if device supports required ISP bypass capabilities
     */
    fun supportsISPBypass(): Boolean {
        return requestPlan.complete
    }

    fun describeSupport(): String = requestPlan.summary

    fun userWarning(): String? = requestPlan.userWarning
}

internal enum class IspBypassFeature {
    TONEMAP_CONTRAST_CURVE,
    EDGE_OFF,
    NOISE_REDUCTION_OFF,
    HOT_PIXEL_OFF,
    SHADING_OFF,
    COLOR_ABERRATION_OFF,
    DISTORTION_CORRECTION_OFF,
    DISTORTION_CORRECTION_ON
}

internal enum class IspBypassDistortionCorrectionPreference {
    SENSOR_NATIVE_OFF,
    CORRECTED_ON
}

internal data class IspBypassAvailability(
    val tonemapModes: List<Int>,
    val edgeModes: List<Int>,
    val noiseReductionModes: List<Int>,
    val hotPixelModes: List<Int>,
    val shadingModes: List<Int>,
    val colorAberrationModes: List<Int>,
    val distortionCorrectionModes: List<Int>
)

internal data class IspBypassRequestPlan(
    val supportedFeatures: Set<IspBypassFeature>,
    val unsupportedReasons: List<String>,
    val requiredFeatures: Set<IspBypassFeature>,
    val distortionCorrectionPreference: IspBypassDistortionCorrectionPreference,
    val distortionCorrectionMode: Int?
) {
    val supportedFeatureCount: Int = supportedFeatures.size
    val totalFeatureCount: Int = requiredFeatures.size
    val complete: Boolean = unsupportedReasons.isEmpty()
    val userWarning: String? = CameraPipelineDiagnostics.ispBypassWarning(unsupportedReasons)
    val summary: String = "geometry=$distortionCorrectionPreference complete=$complete supported=$supportedFeatureCount/$totalFeatureCount " +
        "features=${supportedFeatures.joinToString()} " +
        "unsupported=${unsupportedReasons.joinToString("; ")}"

    fun supports(feature: IspBypassFeature): Boolean = supportedFeatures.contains(feature)
}

internal object ISPBypassRequestPlanPolicy {
    fun resolve(
        availability: IspBypassAvailability,
        distortionCorrectionPreference: IspBypassDistortionCorrectionPreference =
            IspBypassDistortionCorrectionPreference.SENSOR_NATIVE_OFF
    ): IspBypassRequestPlan {
        val supported = linkedSetOf<IspBypassFeature>()
        val unsupported = mutableListOf<String>()
        val required = linkedSetOf(
            IspBypassFeature.TONEMAP_CONTRAST_CURVE,
            IspBypassFeature.EDGE_OFF,
            IspBypassFeature.NOISE_REDUCTION_OFF,
            IspBypassFeature.HOT_PIXEL_OFF,
            IspBypassFeature.SHADING_OFF,
            IspBypassFeature.COLOR_ABERRATION_OFF
        )

        availability.requireMode(
            modes = availability.tonemapModes,
            expected = CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE,
            feature = IspBypassFeature.TONEMAP_CONTRAST_CURVE,
            label = "TONEMAP_MODE_CONTRAST_CURVE",
            supported = supported,
            unsupported = unsupported
        )
        availability.requireMode(
            modes = availability.edgeModes,
            expected = CaptureRequest.EDGE_MODE_OFF,
            feature = IspBypassFeature.EDGE_OFF,
            label = "EDGE_MODE_OFF",
            supported = supported,
            unsupported = unsupported
        )
        availability.requireMode(
            modes = availability.noiseReductionModes,
            expected = CaptureRequest.NOISE_REDUCTION_MODE_OFF,
            feature = IspBypassFeature.NOISE_REDUCTION_OFF,
            label = "NOISE_REDUCTION_MODE_OFF",
            supported = supported,
            unsupported = unsupported
        )
        availability.requireMode(
            modes = availability.hotPixelModes,
            expected = CaptureRequest.HOT_PIXEL_MODE_OFF,
            feature = IspBypassFeature.HOT_PIXEL_OFF,
            label = "HOT_PIXEL_MODE_OFF",
            supported = supported,
            unsupported = unsupported
        )
        availability.requireMode(
            modes = availability.shadingModes,
            expected = CaptureRequest.SHADING_MODE_OFF,
            feature = IspBypassFeature.SHADING_OFF,
            label = "SHADING_MODE_OFF",
            supported = supported,
            unsupported = unsupported
        )
        availability.requireMode(
            modes = availability.colorAberrationModes,
            expected = CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF,
            feature = IspBypassFeature.COLOR_ABERRATION_OFF,
            label = "COLOR_CORRECTION_ABERRATION_MODE_OFF",
            supported = supported,
            unsupported = unsupported
        )
        val distortionCorrectionMode = resolveDistortionCorrectionMode(
            availability = availability,
            preference = distortionCorrectionPreference,
            required = required,
            supported = supported,
            unsupported = unsupported
        )

        return IspBypassRequestPlan(
            supportedFeatures = supported,
            unsupportedReasons = unsupported,
            requiredFeatures = required,
            distortionCorrectionPreference = distortionCorrectionPreference,
            distortionCorrectionMode = distortionCorrectionMode
        )
    }

    private fun resolveDistortionCorrectionMode(
        availability: IspBypassAvailability,
        preference: IspBypassDistortionCorrectionPreference,
        required: MutableSet<IspBypassFeature>,
        supported: MutableSet<IspBypassFeature>,
        unsupported: MutableList<String>
    ): Int? = when (preference) {
        IspBypassDistortionCorrectionPreference.SENSOR_NATIVE_OFF -> {
            required += IspBypassFeature.DISTORTION_CORRECTION_OFF
            if (availability.distortionCorrectionModes.contains(CaptureRequest.DISTORTION_CORRECTION_MODE_OFF)) {
                supported += IspBypassFeature.DISTORTION_CORRECTION_OFF
                CaptureRequest.DISTORTION_CORRECTION_MODE_OFF
            } else {
                unsupported += "DISTORTION_CORRECTION_MODE_OFF unavailable"
                null
            }
        }
        IspBypassDistortionCorrectionPreference.CORRECTED_ON -> {
            required += IspBypassFeature.DISTORTION_CORRECTION_ON
            val onMode = DistortionCorrectionModePolicy.bestOnMode(availability.distortionCorrectionModes)
            if (onMode != null) {
                supported += IspBypassFeature.DISTORTION_CORRECTION_ON
            } else {
                unsupported += "DISTORTION_CORRECTION_MODE_ON unavailable"
            }
            onMode
        }
    }

    private fun IspBypassAvailability.requireMode(
        modes: List<Int>,
        expected: Int,
        feature: IspBypassFeature,
        label: String,
        supported: MutableSet<IspBypassFeature>,
        unsupported: MutableList<String>
    ) {
        if (modes.contains(expected)) {
            supported += feature
        } else {
            unsupported += "$label unavailable"
        }
    }
}

internal object DistortionCorrectionModePolicy {
    fun bestOnMode(availableModes: List<Int>): Int? = when {
        availableModes.contains(CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY) ->
            CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY
        availableModes.contains(CaptureRequest.DISTORTION_CORRECTION_MODE_FAST) ->
            CaptureRequest.DISTORTION_CORRECTION_MODE_FAST
        else -> null
    }
}

internal fun CameraCharacteristics.toIspBypassAvailability(): IspBypassAvailability {
    val colorAberrationModes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES)?.toList().orEmpty()
    } else {
        listOf(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF)
    }
    val distortionCorrectionModes = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)?.toList().orEmpty()
    } else {
        listOf(CaptureRequest.DISTORTION_CORRECTION_MODE_OFF)
    }

    return IspBypassAvailability(
        tonemapModes = get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES)?.toList().orEmpty(),
        edgeModes = get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES)?.toList().orEmpty(),
        noiseReductionModes = get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES)?.toList().orEmpty(),
        hotPixelModes = get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES)?.toList().orEmpty(),
        shadingModes = get(CameraCharacteristics.SHADING_AVAILABLE_MODES)?.toList().orEmpty(),
        colorAberrationModes = colorAberrationModes,
        distortionCorrectionModes = distortionCorrectionModes
    )
}
