package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ════════════════════════════════════════════════════════════════════════════════
// HIGH-END LENS SELECTION POLICY
// ════════════════════════════════════════════════════════════════════════════════
// cinema-oriented lens selection with multi-camera support.
// Optimizes for optical quality, zoom range, and sensor capabilities.
// ════════════════════════════════════════════════════════════════════════════════

/**
 * Lens type classification for professional workflows.
 */
internal enum class LensType {
    /** Ultra-wide angle (typically <18mm equivalent) */
    ULTRA_WIDE,
    /** Wide angle (18-35mm equivalent) */
    WIDE,
    /** Standard/Normal (35-60mm equivalent) */
    STANDARD,
    /** Short telephoto (60-100mm equivalent) */
    SHORT_TELEPHOTO,
    /** Telephoto (>100mm equivalent) */
    TELEPHOTO,
    /** Macro lens for close-up */
    MACRO,
    /** Depth/ToF sensor */
    DEPTH,
    /** Unknown lens type */
    UNKNOWN
}

/**
 * Optical Image Stabilization capability level.
 */
internal enum class OisCapability {
    /** No OIS support */
    NONE,
    /** Basic sensor-shift OIS */
    BASIC,
    /** Advanced multi-axis OIS */
    ADVANCED,
    /** Gimbal-style stabilization */
    GIMBAL
}

internal data class CameraLensCandidate(
    val cameraId: String,
    val level: Int,
    val focalLength: Float,
    val logical: Boolean,
    val zoomLower: Float,
    val zoomUpper: Float,
    // High-end additions
    val aperture: Float = 1.8f,
    val sensorPixels: Long = 0L,
    val hasOis: Boolean = false,
    val hasPhaseDetect: Boolean = false,
    val supportsRaw: Boolean = false,
    val supports10BitHdr: Boolean = false,
    val maxFps: Int = 60,
    val lensType: LensType = LensType.UNKNOWN,
    val openable: Boolean = true
) {
    val hardwareRank: Int
        get() = when (level) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> 5
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> 4
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> 3
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> 2
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> 1
            else -> 0
        }
    
    /** Cinema quality score considering all professional factors */
    val cinemaQualityScore: Float
        get() {
            var score = hardwareRank * 100f
            
            // Optical quality factors
            score += if (hasOis) 50f else 0f
            score += if (hasPhaseDetect) 40f else 0f
            score += if (supportsRaw) 60f else 0f
            score += if (supports10BitHdr) 70f else 0f
            
            // Aperture bonus (wider = better for low light)
            score += (2.8f - aperture.coerceIn(1.0f, 2.8f)) * 30f
            
            // High resolution bonus
            score += (sensorPixels / 1_000_000f).coerceIn(0f, 100f) * 0.5f
            
            // High FPS bonus
            score += (maxFps / 60f).coerceIn(0f, 2f) * 20f
            
            // Zoom versatility
            val zoomRange = zoomUpper / zoomLower.coerceAtLeast(0.1f)
            score += zoomRange.coerceIn(1f, 10f) * 10f
            
            // Logical camera bonus (multi-lens fusion)
            score += if (logical) 80f else 0f
            
            return score
        }
    
    /** Check if this lens is suitable for cinema 4K+ recording */
    val isCinemaCapable: Boolean
        get() = level >= CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL &&
                supportsRaw && maxFps >= 60
    
    /** Equivalent 35mm focal length (approximation) */
    val equivalent35mm: Float
        get() {
            // Assume typical smartphone sensor crop factor of ~5-7x
            val cropFactor = 5.5f
            return focalLength * cropFactor
        }
}

internal object CameraLensSelectionPolicy {
    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    // ═══════════════════════════════════════════════════════════════════════════
    // PROFESSIONAL CONSTANTS
    // ═══════════════════════════════════════════════════════════════════════════
    
    /** Standard reference focal length for 35mm equivalent */
    private const val STANDARD_FOCAL_35MM = 50f
    
    /** Wide angle threshold */
    private const val WIDE_FOCAL_35MM = 35f
    
    /** Ultra-wide threshold */
    private const val ULTRA_WIDE_FOCAL_35MM = 18f
    
    /** Telephoto threshold */
    private const val TELEPHOTO_FOCAL_35MM = 85f
    private const val FULL_FRAME_WIDTH_MM = 36f
    private const val FULL_FRAME_HEIGHT_MM = 24f
    private val FULL_FRAME_DIAGONAL_MM =
        sqrt(
            FULL_FRAME_WIDTH_MM * FULL_FRAME_WIDTH_MM +
                FULL_FRAME_HEIGHT_MM * FULL_FRAME_HEIGHT_MM
        )
    private const val CINE_TARGET_SENSOR_PIXELS = 35_000_000L
    private const val HIGH_SENSOR_POOL_TOLERANCE = 0.92f

    // ═══════════════════════════════════════════════════════════════════════════
    // PRIMARY SELECTION LOGIC
    // ═══════════════════════════════════════════════════════════════════════════

    fun selectPreferredCandidate(
        requestedFacing: Int,
        candidates: List<CameraLensCandidate>
    ): CameraLensCandidate? {
        if (candidates.isEmpty()) return null
        val openableCandidates = candidates.filter { it.openable }.ifEmpty { candidates }
        return if (requestedFacing == CameraCharacteristics.LENS_FACING_BACK) {
            selectPreferredBackCandidate(openableCandidates)
        } else {
            selectPreferredFrontCandidate(openableCandidates)
        }
    }

    /**
     * s5-f10 / CP-F11 — task-aware routing in front of [selectPreferredCandidate].
     *
     * NO-DOWNGRADE: when [gateEnabled] is false OR [hint] is [LensSelectionTaskHint.None], this returns
     * EXACTLY [selectPreferredCandidate](requestedFacing, candidates) — today's opened lens, bit-for-bit.
     * Only when the owner enables the gate device-side and supplies a task hint does it delegate to one
     * of the otherwise-dormant specialized selectors. The dormant selectors do not themselves filter on
     * [CameraLensCandidate.openable]; this router applies the same openable pre-filter that
     * [selectPreferredCandidate] uses so a task hint can never open a hidden / secondary sensor.
     *
     * Pure function: same inputs → same pick. Zero per-frame cost — invoked only at camera-open / mode-switch.
     */
    fun selectForTask(
        requestedFacing: Int,
        candidates: List<CameraLensCandidate>,
        hint: LensSelectionTaskHint,
        gateEnabled: Boolean
    ): CameraLensCandidate? {
        if (!gateEnabled || hint is LensSelectionTaskHint.None) {
            return selectPreferredCandidate(requestedFacing, candidates)
        }
        if (candidates.isEmpty()) return null
        val openableCandidates = candidates.filter { it.openable }.ifEmpty { candidates }
        return when (hint) {
            is LensSelectionTaskHint.None ->
                selectPreferredCandidate(requestedFacing, candidates)
            is LensSelectionTaskHint.LowLight ->
                selectLowLightCandidate(openableCandidates)
            is LensSelectionTaskHint.Video ->
                selectVideoCandidate(openableCandidates, requireHighFps = hint.requireHighFps)
            is LensSelectionTaskHint.ByLensType ->
                selectCandidateForLensType(
                    openableCandidates,
                    preferredType = hint.lensType,
                    requireCinemaCapable = hint.requireCinemaCapable
                )
        }
    }

    /**
     * Select candidate for specific lens type requirement.
     * Used for professional multi-camera setups.
     */
    fun selectCandidateForLensType(
        candidates: List<CameraLensCandidate>,
        preferredType: LensType,
        requireCinemaCapable: Boolean = false
    ): CameraLensCandidate? {
        val filtered = if (requireCinemaCapable) {
            candidates.filter { it.isCinemaCapable }
        } else {
            candidates
        }
        val strictFiltered = if (strictHighEndEnabled()) {
            filtered.filter {
                it.supportsRaw || it.supports10BitHdr || it.hardwareRank >= 4
            }.ifEmpty { filtered }
        } else {
            filtered
        }
        
        // First try exact match
        val exactMatch = strictFiltered.filter { it.lensType == preferredType }
            .maxByOrNull { it.cinemaQualityScore }
        if (exactMatch != null) return exactMatch
        
        // Fall back to closest focal length
        val targetFocal = when (preferredType) {
            LensType.ULTRA_WIDE -> ULTRA_WIDE_FOCAL_35MM / 5.5f
            LensType.WIDE -> WIDE_FOCAL_35MM / 5.5f
            LensType.STANDARD -> STANDARD_FOCAL_35MM / 5.5f
            LensType.SHORT_TELEPHOTO -> 70f / 5.5f
            LensType.TELEPHOTO -> TELEPHOTO_FOCAL_35MM / 5.5f
            else -> STANDARD_FOCAL_35MM / 5.5f
        }
        
        return strictFiltered.minByOrNull { abs(it.focalLength - targetFocal) }
    }

    /**
     * Get all available lenses sorted by focal length for lens switching UI.
     */
    fun getSortedLensesByFocalLength(
        candidates: List<CameraLensCandidate>,
        facing: Int
    ): List<CameraLensCandidate> {
        return candidates
            .filter { it.logical || it.zoomLower <= 1.2f } // Exclude secondary physical sensors
            .sortedBy { it.focalLength }
    }

    /**
     * Find best lens for low-light shooting.
     * Prioritizes aperture, OIS, and sensor size.
     */
    fun selectLowLightCandidate(
        candidates: List<CameraLensCandidate>
    ): CameraLensCandidate? {
        if (strictHighEndEnabled()) {
            return candidates.maxByOrNull { candidate ->
                var score = 0f
                score += (1f / candidate.aperture.coerceAtLeast(0.1f)) * 2.2f
                score += if (candidate.hasOis) 1.8f else 0f
                score += if (candidate.supportsRaw) 1.4f else 0f
                score += if (candidate.supports10BitHdr) 1.2f else 0f
                score += candidate.sensorPixels / 12_000_000f
                score += candidate.hardwareRank * 0.8f
                score
            }
        }
        return candidates.maxWithOrNull(
            compareBy<CameraLensCandidate>(
                { 1f / it.aperture.coerceAtLeast(0.1f) },
                { if (it.hasOis) 1 else 0 },
                { it.sensorPixels },
                { it.hardwareRank }
            )
        )
    }

    /**
     * Find best lens for video recording.
     * Prioritizes OIS, high FPS, and RAW support.
     */
    fun selectVideoCandidate(
        candidates: List<CameraLensCandidate>,
        requireHighFps: Boolean = false
    ): CameraLensCandidate? {
        val filtered = if (requireHighFps || strictHighEndEnabled()) {
            candidates.filter { it.maxFps >= 60 }
        } else {
            candidates
        }
        val strictFiltered = if (strictHighEndEnabled()) {
            filtered.filter {
                it.supports10BitHdr || it.supportsRaw || it.hasOis
            }.ifEmpty { filtered }
        } else {
            filtered
        }
        
        return strictFiltered.maxWithOrNull(
            compareBy<CameraLensCandidate>(
                // Cinema capability first
                { if (it.isCinemaCapable) 1 else 0 },
                // Then OIS for stable footage
                { if (it.hasOis) 1 else 0 },
                // Then 10-bit HDR
                { if (it.supports10BitHdr) 1 else 0 },
                // Then zoom range
                { it.zoomUpper / it.zoomLower.coerceAtLeast(0.1f) },
                // Then hardware level
                { it.hardwareRank }
            )
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FRONT CAMERA SELECTION
    // ═══════════════════════════════════════════════════════════════════════════

    private fun selectPreferredFrontCandidate(
        candidates: List<CameraLensCandidate>
    ): CameraLensCandidate =
        candidates.maxWithOrNull(
            compareBy<CameraLensCandidate>(
                { it.logical || it.zoomUpper > 1f },
                { it.cinemaQualityScore },
                { it.zoomUpper / it.zoomLower.coerceAtLeast(0.1f) }
            )
        ) ?: candidates.first()

    // ═══════════════════════════════════════════════════════════════════════════
    // BACK CAMERA SELECTION - CINEMA OPTIMIZED
    // ═══════════════════════════════════════════════════════════════════════════

    private fun selectPreferredBackCandidate(
        candidates: List<CameraLensCandidate>
    ): CameraLensCandidate {
        selectHighSensorBackCandidate(candidates)?.let { return it }

        // Priority 1: Logical multi-camera with wide zoom range
        val logicalPool = candidates.filter { it.logical || it.zoomLower < 1f || it.zoomUpper > 2f }
        if (logicalPool.isNotEmpty()) {
            return logicalPool.maxWithOrNull(
                compareBy<CameraLensCandidate>(
                    // Cinema quality score includes all professional factors
                    { it.cinemaQualityScore },
                    { it.zoomUpper / it.zoomLower.coerceAtLeast(0.1f) },
                    { 1f / it.zoomLower.coerceAtLeast(0.1f) },
                    { it.hardwareRank }
                )
            ) ?: logicalPool.first()
        }

        // Priority 2: Standard lens with highest quality
        val focalCandidates = candidates.filter { it.focalLength.isFinite() && it.focalLength != Float.MAX_VALUE }
        if (focalCandidates.isEmpty()) {
            return candidates.maxByOrNull { it.cinemaQualityScore } ?: candidates.first()
        }

        // Select lens closest to standard (50mm equivalent) with best quality
        val sortedFocals = focalCandidates.map { it.focalLength }.sorted()
        val medianFocal = sortedFocals[sortedFocals.size / 2]
        
        return focalCandidates.minWithOrNull(
            compareBy<CameraLensCandidate>(
                { abs(it.focalLength - medianFocal) },
                { -it.cinemaQualityScore }
            )
        ) ?: focalCandidates.first()
    }

    private fun selectHighSensorBackCandidate(
        candidates: List<CameraLensCandidate>
    ): CameraLensCandidate? {
        val maxSensorPixels = candidates.maxOfOrNull { it.sensorPixels } ?: 0L
        if (maxSensorPixels <= 0L) return null
        val sensorFloor =
            if (maxSensorPixels >= CINE_TARGET_SENSOR_PIXELS) {
                CINE_TARGET_SENSOR_PIXELS
            } else {
                (maxSensorPixels * HIGH_SENSOR_POOL_TOLERANCE).toLong()
            }
        val highSensorPool = candidates.filter { it.sensorPixels >= sensorFloor }
        if (highSensorPool.isEmpty()) return null
        return highSensorPool.maxWithOrNull(
            compareBy<CameraLensCandidate>(
                { if (it.lensType == LensType.DEPTH || it.lensType == LensType.MACRO) 0 else 1 },
                { it.highEndCaptureReadinessScore() },
                { if (it.logical) 1 else 0 },
                { if (it.hasOis) 1 else 0 },
                { it.cinemaQualityScore },
                { it.sensorPixels },
                { it.hardwareRank },
                { it.zoomUpper / it.zoomLower.coerceAtLeast(0.1f) }
            )
        )
    }

    private fun CameraLensCandidate.highEndCaptureReadinessScore(): Int {
        var score = hardwareRank * 4
        if (supportsRaw) score += 5
        if (supports10BitHdr) score += 5
        if (hasOis) score += 3
        if (hasPhaseDetect) score += 2
        if (maxFps >= 60) score += 2
        if (maxFps >= 120) score += 1
        return score
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LENS TYPE CLASSIFICATION
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Classify lens type based on focal length.
     */
    fun classifyLensType(focalLengthMm: Float, cropFactor: Float = 5.5f): LensType =
        classifyLensTypeByEquivalent(focalLengthMm * cropFactor)

    fun classifyLensTypeByEquivalent(equivalent35mm: Float?): LensType {
        val equivalent = equivalent35mm ?: return LensType.UNKNOWN
        return when {
            equivalent < ULTRA_WIDE_FOCAL_35MM -> LensType.ULTRA_WIDE
            equivalent < WIDE_FOCAL_35MM -> LensType.WIDE
            equivalent < 60f -> LensType.STANDARD
            equivalent < TELEPHOTO_FOCAL_35MM -> LensType.SHORT_TELEPHOTO
            else -> LensType.TELEPHOTO
        }
    }

    fun equivalentFocalLength35mm(
        focalLengthMm: Float?,
        sensorWidthMm: Float?,
        sensorHeightMm: Float?
    ): Float? {
        val focal = focalLengthMm?.takeIf { it.isFinite() && it > 0f } ?: return null
        val width = sensorWidthMm?.takeIf { it.isFinite() && it > 0f } ?: return null
        val height = sensorHeightMm?.takeIf { it.isFinite() && it > 0f } ?: return null
        val sensorDiagonal = sqrt(width * width + height * height)
        if (!sensorDiagonal.isFinite() || sensorDiagonal <= 0f) return null
        return focal * (FULL_FRAME_DIAGONAL_MM / sensorDiagonal)
    }

    fun labelForEquivalentFocalLength(equivalent35mm: Float?): String {
        val typeLabel = when (classifyLensTypeByEquivalent(equivalent35mm)) {
            LensType.ULTRA_WIDE -> "Ultra-Wide"
            LensType.WIDE -> "Wide"
            LensType.STANDARD -> "Main"
            LensType.SHORT_TELEPHOTO -> "Short Telephoto"
            LensType.TELEPHOTO -> "Telephoto"
            LensType.MACRO -> "Macro"
            LensType.DEPTH -> "Depth"
            LensType.UNKNOWN -> "Camera"
        }
        val focalLabel = equivalent35mm
            ?.takeIf { it.isFinite() && it > 0f }
            ?.roundToInt()
            ?.let { "${it}mm " }
            .orEmpty()
        return "$focalLabel$typeLabel"
    }

    /**
     * Get human-readable lens description for UI.
     */
    fun getLensDescription(candidate: CameraLensCandidate): String {
        val typeLabel = when (candidate.lensType) {
            LensType.ULTRA_WIDE -> "Ultra Wide"
            LensType.WIDE -> "Wide"
            LensType.STANDARD -> "Main"
            LensType.SHORT_TELEPHOTO -> "Portrait"
            LensType.TELEPHOTO -> "Telephoto"
            LensType.MACRO -> "Macro"
            LensType.DEPTH -> "Depth"
            LensType.UNKNOWN -> "Camera"
        }
        
        val zoomLabel = if (candidate.zoomLower < 1f) {
            "${candidate.zoomLower}x-${candidate.zoomUpper}x"
        } else if (candidate.zoomUpper > 1f) {
            "${candidate.zoomUpper}x"
        } else {
            "1x"
        }
        
        return "$typeLabel $zoomLabel"
    }

    /**
     * Calculate recommended zoom steps for smooth transitions.
     */
    fun getRecommendedZoomSteps(
        minZoom: Float,
        maxZoom: Float,
        physicalLenses: List<Float> = listOf(0.5f, 1f, 2f, 3f, 5f)
    ): List<Float> {
        val baseSteps = physicalLenses.filter { it in minZoom..maxZoom }
            .ifEmpty { listOf(1f) }
        if (!strictHighEndEnabled()) return baseSteps
        val expanded = buildSet {
            addAll(baseSteps)
            for (index in 0 until baseSteps.lastIndex) {
                val a = baseSteps[index]
                val b = baseSteps[index + 1]
                val midpoint = ((a + b) * 0.5f).coerceIn(minZoom, maxZoom)
                add(midpoint)
            }
        }
        return expanded.toList().sorted()
    }
}
