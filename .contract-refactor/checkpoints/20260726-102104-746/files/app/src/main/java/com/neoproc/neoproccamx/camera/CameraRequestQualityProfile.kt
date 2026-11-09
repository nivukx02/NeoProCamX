package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CaptureRequest
import android.os.Build
import com.neoproc.neoproccamx.core.HighEndQualityPolicy

// ════════════════════════════════════════════════════════════════════════════════
// PROFESSIONAL cinema-oriented QUALITY PROFILE
// ════════════════════════════════════════════════════════════════════════════════
// Maximum quality processing pipeline with support for multiple quality tiers,
// cinema-specific modes, and intelligent adaptive quality selection.
// ════════════════════════════════════════════════════════════════════════════════

/**
 * Quality tier for different use cases.
 */
enum class QualityTier {
    /** Internal unified NeoProCamX physics-inspired ISP profile; not a physical-limit claim. */
    OMNIPOTENT_CINEMA_ENGINE,
    /** Internal experimental NeoProCamX stress profile; not a physical-limit claim. */
    COSMIC_SINGULARITY,
    /** Internal experimental top-tier NeoProCamX profile; not a physics or certification claim. */
    PLANCK_ZENITH,
    /** Internal top-tier NeoProCamX cinema profile; not an external certification claim. */
    IMAX_CINEMA,
    /** Maximum quality - no compromises (stills, cinema) */
    CINEMA,
    /** High quality with slight performance concessions */
    PROFESSIONAL,
    /** Balanced quality and performance (general video) */
    BALANCED,
    /** Performance-optimized (high-speed, slow-mo) */
    PERFORMANCE,
    /** Minimal processing (RAW workflows) */
    MINIMAL
}

/**
 * Noise reduction strategy for different scenarios.
 */
enum class NoiseReductionStrategy {
    /** Maximum NR regardless of motion */
    AGGRESSIVE,
    /** Smart temporal NR (best for video) */
    TEMPORAL,
    /** Spatial-only NR (preserves motion detail) */
    SPATIAL,
    /** Minimal NR (RAW-like processing) */
    MINIMAL,
    /** Completely disabled */
    OFF
}

/**
 * Sharpening strategy.
 */
enum class SharpeningStrategy {
    /** Maximum hardware sharpening */
    MAXIMUM,
    /** Standard high quality */
    HIGH_QUALITY,
    /** Balanced sharpening */
    MODERATE,
    /** Minimal for post-processing workflows */
    MINIMAL,
    /** No hardware sharpening */
    OFF
}

/**
 * Extended quality profile with all processing parameters.
 */
internal data class ExtendedQualityProfile(
    val tier: QualityTier,
    val noiseReductionStrategy: NoiseReductionStrategy,
    val sharpeningStrategy: SharpeningStrategy,
    val enableHotPixelCorrection: Boolean,
    val enableLensShadingCorrection: Boolean,
    val enableDistortionCorrection: Boolean,
    val enableAberrationCorrection: Boolean,
    val enableFaceDetection: Boolean,
    val useHighQualityTonemap: Boolean
)

internal data class CameraRequestQualityProfile(
    val noiseReductionMode: Int,
    val hotPixelMode: Int,
    val edgeMode: Int,
    val tonemapMode: Int,
    val shadingMode: Int,
    val distortionCorrectionMode: Int?,
    val aberrationMode: Int?,
    val faceDetectMode: Int,
    
    // Extended parameters
    val qualityTier: QualityTier = QualityTier.PROFESSIONAL,
    val noiseReductionStrategy: NoiseReductionStrategy = NoiseReductionStrategy.TEMPORAL,
    val sharpeningStrategy: SharpeningStrategy = SharpeningStrategy.HIGH_QUALITY
) {
    fun apply(builder: CaptureRequest.Builder) {
        builder[CaptureRequest.NOISE_REDUCTION_MODE] = noiseReductionMode
        builder[CaptureRequest.HOT_PIXEL_MODE] = hotPixelMode
        builder[CaptureRequest.EDGE_MODE] = edgeMode
        builder[CaptureRequest.TONEMAP_MODE] = tonemapMode
        builder[CaptureRequest.SHADING_MODE] = shadingMode
        builder[CaptureRequest.DISTORTION_CORRECTION_MODE] = distortionCorrectionMode
        builder[CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE] = aberrationMode
        builder[CaptureRequest.STATISTICS_FACE_DETECT_MODE] = faceDetectMode
    }
    
    /**
     * Apply only noise-related settings (for real-time adjustment).
     */
    fun applyNoiseSettings(builder: CaptureRequest.Builder) {
        builder[CaptureRequest.NOISE_REDUCTION_MODE] = noiseReductionMode
        builder[CaptureRequest.HOT_PIXEL_MODE] = hotPixelMode
    }
    
    /**
     * Apply only sharpness-related settings.
     */
    fun applySharpnessSettings(builder: CaptureRequest.Builder) {
        builder[CaptureRequest.EDGE_MODE] = edgeMode
    }
    
    /**
     * Check if this profile uses any high-quality modes.
     */
    val usesHighQualityModes: Boolean
        get() = noiseReductionMode == CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY ||
                edgeMode == CaptureRequest.EDGE_MODE_HIGH_QUALITY ||
                shadingMode == CaptureRequest.SHADING_MODE_HIGH_QUALITY
    
    /**
     * Estimated processing overhead (0.0 = minimal, 1.0 = maximum).
     */
    val estimatedOverhead: Float
        get() {
            var overhead = 0f
            if (noiseReductionMode == CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY) overhead += 0.25f
            if (edgeMode == CaptureRequest.EDGE_MODE_HIGH_QUALITY) overhead += 0.15f
            if (shadingMode == CaptureRequest.SHADING_MODE_HIGH_QUALITY) overhead += 0.1f
            if (tonemapMode == CaptureRequest.TONEMAP_MODE_HIGH_QUALITY) overhead += 0.15f
            if (distortionCorrectionMode == CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY) overhead += 0.1f
            if (aberrationMode == CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY) overhead += 0.05f
            return overhead.coerceAtMost(1f)
        }
}

internal object CameraRequestQualityProfiles {

    fun minimalDistortionCorrectionMode(androidApiLevel: Int = Build.VERSION.SDK_INT): Int? =
        if (androidApiLevel >= Build.VERSION_CODES.P) {
            CaptureRequest.DISTORTION_CORRECTION_MODE_OFF
        } else {
            null
        }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CORE PROFILE CREATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Creates a professional-grade quality profile optimized for maximum sharpness
     * and effective noise reduction.
     * 
     * Sharpness-critical stages stay high quality in all modes, while preview-only
     * request stages remain real-time safe unless we are doing a still capture.
     */
    fun create(
        noiseReductionEnabled: Boolean,
        forStillCapture: Boolean,
        faceDetectionEnabled: Boolean,
        preferredFaceDetectMode: Int
    ): CameraRequestQualityProfile {
        if (strictHighEndActive()) {
            return createCinemaProfile(faceDetectionEnabled, preferredFaceDetectMode)
        }
        
        // Hot pixel correction - always high quality when NR is enabled
        val hotPixelMode = when {
            !noiseReductionEnabled -> CaptureRequest.HOT_PIXEL_MODE_OFF
            // Use HIGH_QUALITY for both photo AND video for best results
            else -> CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY
        }
        
        val tier = if (forStillCapture) QualityTier.CINEMA else QualityTier.PROFESSIONAL
        val nrStrategy = if (noiseReductionEnabled) {
            if (forStillCapture) NoiseReductionStrategy.AGGRESSIVE else NoiseReductionStrategy.TEMPORAL
        } else {
            NoiseReductionStrategy.OFF
        }
        val realTimeVideo = !forStillCapture
        
        return CameraRequestQualityProfile(
            // ═══════════════════════════════════════════════════════════════════
            // NOISE REDUCTION - Critical for clean output
            // ═══════════════════════════════════════════════════════════════════
            noiseReductionMode = when {
                !noiseReductionEnabled -> CaptureRequest.NOISE_REDUCTION_MODE_OFF
                // Always use HIGH_QUALITY - the hardware NR is crucial for clean images
                // The slight performance cost is worth the quality improvement
                else -> CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
            },
            
            hotPixelMode = hotPixelMode,
            
            // ═══════════════════════════════════════════════════════════════════
            // EDGE MODE (SHARPENING) - KEY FIX FOR SOFT FOCUS APPEARANCE
            // ═══════════════════════════════════════════════════════════════════
            edgeMode = CaptureRequest.EDGE_MODE_HIGH_QUALITY,
            
            // ═══════════════════════════════════════════════════════════════════
            // ELITE CINEMA: ALL ISP stages HIGH_QUALITY for BOTH stills AND video.
            // Cinema cameras never use FAST processing paths.
            // ═══════════════════════════════════════════════════════════════════
            tonemapMode = CaptureRequest.TONEMAP_MODE_HIGH_QUALITY,
            shadingMode = CaptureRequest.SHADING_MODE_HIGH_QUALITY,
            distortionCorrectionMode = CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY,
            
            // ═══════════════════════════════════════════════════════════════════
            // CHROMATIC ABERRATION CORRECTION - Color fringing
            // ═══════════════════════════════════════════════════════════════════
            aberrationMode = CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY,
            
            // ═══════════════════════════════════════════════════════════════════
            // FACE DETECTION
            // ═══════════════════════════════════════════════════════════════════
            faceDetectMode = if (faceDetectionEnabled) {
                preferredFaceDetectMode
            } else {
                CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF
            },
            
            qualityTier = QualityTier.CINEMA,
            noiseReductionStrategy = nrStrategy,
            sharpeningStrategy = SharpeningStrategy.MAXIMUM
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // TIER-BASED PROFILES
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Create profile for specific quality tier.
     */
    fun createForTier(
        tier: QualityTier,
        faceDetectionEnabled: Boolean = false,
        preferredFaceDetectMode: Int = CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF
    ): CameraRequestQualityProfile {
        return when (tier) {
            QualityTier.OMNIPOTENT_CINEMA_ENGINE -> createCinemaProfile(faceDetectionEnabled, preferredFaceDetectMode).copy(
                qualityTier = QualityTier.OMNIPOTENT_CINEMA_ENGINE
            )
            QualityTier.COSMIC_SINGULARITY -> createCinemaProfile(faceDetectionEnabled, preferredFaceDetectMode).copy(
                qualityTier = QualityTier.COSMIC_SINGULARITY
            )
            QualityTier.PLANCK_ZENITH -> createCinemaProfile(faceDetectionEnabled, preferredFaceDetectMode).copy(
                qualityTier = QualityTier.PLANCK_ZENITH
            )
            QualityTier.IMAX_CINEMA -> createCinemaProfile(faceDetectionEnabled, preferredFaceDetectMode).copy(
                qualityTier = QualityTier.IMAX_CINEMA
            )
            QualityTier.CINEMA -> createCinemaProfile(faceDetectionEnabled, preferredFaceDetectMode)
            QualityTier.PROFESSIONAL -> create(
                noiseReductionEnabled = true,
                forStillCapture = false,
                faceDetectionEnabled = faceDetectionEnabled,
                preferredFaceDetectMode = preferredFaceDetectMode
            )
            QualityTier.BALANCED -> createBalancedProfile(faceDetectionEnabled, preferredFaceDetectMode)
            QualityTier.PERFORMANCE -> createFastProfile(
                noiseReductionEnabled = true,
                faceDetectionEnabled = faceDetectionEnabled,
                preferredFaceDetectMode = preferredFaceDetectMode
            )
            QualityTier.MINIMAL -> createMinimalProfile(faceDetectionEnabled, preferredFaceDetectMode)
        }
    }
    
    /**
     * cinema-oriented profile with maximum quality settings.
     */
    fun createCinemaProfile(
        faceDetectionEnabled: Boolean = false,
        preferredFaceDetectMode: Int = CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF
    ): CameraRequestQualityProfile {
        return CameraRequestQualityProfile(
            noiseReductionMode = CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY,
            hotPixelMode = CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY,
            edgeMode = CaptureRequest.EDGE_MODE_HIGH_QUALITY,
            tonemapMode = CaptureRequest.TONEMAP_MODE_HIGH_QUALITY,
            shadingMode = CaptureRequest.SHADING_MODE_HIGH_QUALITY,
            distortionCorrectionMode = CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY,
            aberrationMode = CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY,
            faceDetectMode = if (faceDetectionEnabled) preferredFaceDetectMode 
                            else CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF,
            qualityTier = QualityTier.CINEMA,
            noiseReductionStrategy = NoiseReductionStrategy.AGGRESSIVE,
            sharpeningStrategy = SharpeningStrategy.MAXIMUM
        )
    }
    
    /**
     * Balanced profile for general use.
     * Keeps denoise and edge quality high while reducing tone/shading cost for smoother video.
     */
    fun createBalancedProfile(
        faceDetectionEnabled: Boolean = true,
        preferredFaceDetectMode: Int = CaptureRequest.STATISTICS_FACE_DETECT_MODE_SIMPLE
    ): CameraRequestQualityProfile {
        if (strictHighEndActive()) {
            return createCinemaProfile(faceDetectionEnabled, preferredFaceDetectMode)
        }

        return CameraRequestQualityProfile(
            noiseReductionMode = CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY,
            hotPixelMode = CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY,
            edgeMode = CaptureRequest.EDGE_MODE_HIGH_QUALITY,
            tonemapMode = CaptureRequest.TONEMAP_MODE_HIGH_QUALITY,
            shadingMode = CaptureRequest.SHADING_MODE_HIGH_QUALITY,
            distortionCorrectionMode = CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY,
            aberrationMode = CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY,
            faceDetectMode = if (faceDetectionEnabled) preferredFaceDetectMode 
                            else CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF,
            qualityTier = QualityTier.CINEMA,
            noiseReductionStrategy = NoiseReductionStrategy.TEMPORAL,
            sharpeningStrategy = SharpeningStrategy.MAXIMUM
        )
    }
    
    /**
     * Creates a performance-optimized profile for high frame rate scenarios.
     * HIGH-END UPGRADE: Noise reduction and sharpening remain HIGH_QUALITY for quality retention
     */
    fun createFastProfile(
        noiseReductionEnabled: Boolean,
        faceDetectionEnabled: Boolean,
        preferredFaceDetectMode: Int
    ): CameraRequestQualityProfile {
        if (strictHighEndActive()) {
            return createCinemaProfile(faceDetectionEnabled, preferredFaceDetectMode)
        }

        return CameraRequestQualityProfile(
            noiseReductionMode = if (noiseReductionEnabled) {
                CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
            } else {
                CaptureRequest.NOISE_REDUCTION_MODE_OFF
            },
            hotPixelMode = if (noiseReductionEnabled) {
                CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY
            } else {
                CaptureRequest.HOT_PIXEL_MODE_OFF
            },
            edgeMode = CaptureRequest.EDGE_MODE_HIGH_QUALITY,
            tonemapMode = CaptureRequest.TONEMAP_MODE_HIGH_QUALITY,
            shadingMode = CaptureRequest.SHADING_MODE_HIGH_QUALITY,
            distortionCorrectionMode = CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY,
            aberrationMode = CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY,
            faceDetectMode = if (faceDetectionEnabled) {
                preferredFaceDetectMode
            } else {
                CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF
            },
            qualityTier = QualityTier.CINEMA,
            noiseReductionStrategy = if (noiseReductionEnabled) NoiseReductionStrategy.TEMPORAL else NoiseReductionStrategy.OFF,
            sharpeningStrategy = SharpeningStrategy.MAXIMUM
        )
    }
    
    /**
     * Minimal processing profile for RAW workflows.
     */
    fun createMinimalProfile(
        faceDetectionEnabled: Boolean = false,
        preferredFaceDetectMode: Int = CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF
    ): CameraRequestQualityProfile {
        return CameraRequestQualityProfile(
            noiseReductionMode = CaptureRequest.NOISE_REDUCTION_MODE_OFF,
            hotPixelMode = CaptureRequest.HOT_PIXEL_MODE_OFF,
            edgeMode = CaptureRequest.EDGE_MODE_OFF,
            tonemapMode = CaptureRequest.TONEMAP_MODE_FAST,
            shadingMode = CaptureRequest.SHADING_MODE_OFF,
            distortionCorrectionMode = CaptureRequest.DISTORTION_CORRECTION_MODE_OFF,
            aberrationMode = CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF,
            faceDetectMode = if (faceDetectionEnabled) preferredFaceDetectMode 
                            else CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF,
            qualityTier = QualityTier.MINIMAL,
            noiseReductionStrategy = NoiseReductionStrategy.OFF,
            sharpeningStrategy = SharpeningStrategy.OFF
        )
    }
    
    /**
     * High-speed/slow-motion profile optimized for maximum frame rate.
     */
    fun createHighSpeedProfile(): CameraRequestQualityProfile {
        if (strictHighEndActive()) {
            return createCinemaProfile()
        }

        return CameraRequestQualityProfile(
            noiseReductionMode = CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY,
            hotPixelMode = CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY,
            edgeMode = CaptureRequest.EDGE_MODE_HIGH_QUALITY,
            tonemapMode = CaptureRequest.TONEMAP_MODE_HIGH_QUALITY,
            shadingMode = CaptureRequest.SHADING_MODE_HIGH_QUALITY,
            distortionCorrectionMode = CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY,
            aberrationMode = CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY,
            faceDetectMode = CaptureRequest.STATISTICS_FACE_DETECT_MODE_OFF,
            qualityTier = QualityTier.CINEMA,
            noiseReductionStrategy = NoiseReductionStrategy.TEMPORAL,
            sharpeningStrategy = SharpeningStrategy.MAXIMUM
        )
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // ADAPTIVE PROFILE SELECTION
    // ═══════════════════════════════════════════════════════════════════════════
    
    /**
     * Select optimal profile based on current conditions.
     */
    fun selectAdaptiveProfile(
        targetFps: Int,
        currentIso: Int,
        isRecording: Boolean,
        thermalFactor: Float,
        faceDetectionEnabled: Boolean,
        preferredFaceDetectMode: Int
    ): CameraRequestQualityProfile {
        if (HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly) {
            // FLAGSHIP: Never degrade tier in strict mode. ALWAYS use CINEMA
            return createCinemaProfile(faceDetectionEnabled, preferredFaceDetectMode)
        }

        // ELITE CINEMA: Determine tier — never degrade below PROFESSIONAL
        val tier = when {
            targetFps >= 120 -> QualityTier.CINEMA  // Cinema cameras don't degrade at high FPS
            thermalFactor < 0.7f -> QualityTier.PROFESSIONAL  // Never go below PROFESSIONAL
            currentIso > 3200 -> QualityTier.CINEMA  // High ISO needs maximum NR quality
            isRecording -> QualityTier.CINEMA  // Always cinema-grade when recording
            else -> QualityTier.CINEMA  // Elite cinema: always top tier
        }
        
        return createForTier(tier, faceDetectionEnabled, preferredFaceDetectMode)
    }

    private fun strictHighEndActive(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly
    
    /**
     * Create profile optimized for specific ISO level.
     */
    fun createForIso(
        iso: Int,
        forStillCapture: Boolean,
        faceDetectionEnabled: Boolean,
        preferredFaceDetectMode: Int
    ): CameraRequestQualityProfile {
        val nrStrategy = when {
            iso < 400 -> NoiseReductionStrategy.MINIMAL
            iso < 1600 -> NoiseReductionStrategy.SPATIAL
            iso < 6400 -> NoiseReductionStrategy.TEMPORAL
            else -> NoiseReductionStrategy.AGGRESSIVE
        }
        
        val baseProfile = create(
            noiseReductionEnabled = true,
            forStillCapture = forStillCapture,
            faceDetectionEnabled = faceDetectionEnabled,
            preferredFaceDetectMode = preferredFaceDetectMode
        )
        
        return baseProfile.copy(noiseReductionStrategy = nrStrategy)
    }
}
