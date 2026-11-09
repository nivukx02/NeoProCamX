package com.neoproc.neoproccamx.camera

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.util.Range
import android.util.Size
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.pow
import kotlin.math.ln
import kotlin.math.exp

// ════════════════════════════════════════════════════════════════════════════════
// HIGH-END CAMERA CONTROL MATHEMATICS
// ════════════════════════════════════════════════════════════════════════════════
// cinema-oriented computational utilities for professional camera operations.
// Includes advanced metering, exposure compensation, and precision zooming.
// ════════════════════════════════════════════════════════════════════════════════

internal data class CameraRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val exactCenterX: Float get() = (left + right) * 0.5f
    val exactCenterY: Float get() = (top + bottom) * 0.5f
    val area: Long get() = width.toLong() * height.toLong()
    val aspectRatio: Float get() = width.toFloat() / height.coerceAtLeast(1).toFloat()
    val diagonalLength: Float get() = sqrt((width * width + height * height).toFloat())
}

internal data class CameraSize(
    val width: Int,
    val height: Int
) {
    val aspectRatio: Float get() = width.toFloat() / height.coerceAtLeast(1).toFloat()
    val megapixels: Float get() = (width.toLong() * height.toLong()) / 1_000_000f
}

internal data class CameraIntRange(
    val lower: Int,
    val upper: Int
) {
    val span: Int get() = (upper - lower).coerceAtLeast(0)
    fun contains(value: Int): Boolean = value in lower..upper
}

internal data class CameraLongRange(
    val lower: Long,
    val upper: Long
) {
    val span: Long get() = (upper - lower).coerceAtLeast(0L)
    fun contains(value: Long): Boolean = value in lower..upper
}

/**
 * Exposure computation result with professional-grade metadata.
 */
internal data class ExposureComputation(
    val exposureNs: Long,
    val frameDurationNs: Long,
    val exposureValue: Float,
    val dynamicRangeStops: Float
)

/**
 * Advanced metering weight distribution for professional exposure control.
 */
internal enum class MeteringWeightProfile {
    /** Center-weighted average - classic cinema metering */
    CENTER_WEIGHTED,
    /** Spot metering - precision subject exposure */
    SPOT,
    /** Matrix/Evaluative - intelligent scene analysis */
    MATRIX,
    /** Highlight priority - protect specular highlights */
    HIGHLIGHT_PRIORITY,
    /** Shadow priority - preserve shadow detail */
    SHADOW_PRIORITY
}

internal object CameraControlMath {
    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    // ═══════════════════════════════════════════════════════════════════════════
    // cinema-oriented CONSTANTS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private const val MIN_PREVIEW_EXPOSURE_BUDGET_FPS = 24
    private const val MAX_PREVIEW_EXPOSURE_NS_60_FPS = 16_666_666L
    private const val MAX_PREVIEW_EXPOSURE_NS_120_FPS = 8_333_333L
    
    /** Professional cinema standard - 180° shutter angle equivalent */
    private const val CINEMA_SHUTTER_ANGLE_RATIO = 0.5f
    
    /** ISO normalization base for EV calculations (ISO 100 standard) */
    private const val ISO_BASE = 100
    
    /** Minimum crop region percentage to prevent extreme zoom artifacts */
    private const val MIN_CROP_PERCENTAGE = 0.05f
    
    /** Smooth zoom interpolation factor for cinematic transitions */
    private const val SMOOTH_ZOOM_FACTOR = 0.15f
    
    /** High-precision aspect ratio tolerance */
    private const val ASPECT_TOLERANCE = 0.0001f
    
    /** Focus peaking sensitivity threshold (diopters) */
    private const val FOCUS_PEAKING_THRESHOLD = 0.08f
    
    // ═══════════════════════════════════════════════════════════════════════════
    // METERING REGION COMPUTATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    fun clampMeteringRect(region: Rect, bounds: Rect): Rect {
        return clampMeteringRect(region.toCameraRect(), bounds.toCameraRect()).toRect()
    }

    fun clampMeteringRect(region: CameraRect, bounds: CameraRect): CameraRect {
        // A degenerate/empty bounds (right<=left or bottom<=top) would make coerceIn(min,max) throw
        // an empty-range exception; collapse to the bounds instead of crashing.
        if (bounds.right <= bounds.left || bounds.bottom <= bounds.top) return bounds
        val left = region.left.coerceIn(bounds.left, bounds.right - 1)
        val top = region.top.coerceIn(bounds.top, bounds.bottom - 1)
        val right = region.right.coerceIn(left + 1, bounds.right)
        val bottom = region.bottom.coerceIn(top + 1, bounds.bottom)
        return CameraRect(left, top, right, bottom)
    }

    /**
     * Build a weighted metering region with professional falloff characteristics.
     * Uses Gaussian weighting for cinema-oriented exposure transitions.
     */
    fun buildWeightedMeteringRegion(
        centerX: Float,
        centerY: Float,
        bounds: CameraRect,
        radiusFraction: Float = 0.15f,
        profile: MeteringWeightProfile = MeteringWeightProfile.CENTER_WEIGHTED
    ): CameraRect {
        val effectiveRadius = when (profile) {
            MeteringWeightProfile.SPOT -> radiusFraction * 0.5f
            MeteringWeightProfile.HIGHLIGHT_PRIORITY -> radiusFraction * 0.8f
            MeteringWeightProfile.SHADOW_PRIORITY -> radiusFraction * 1.2f
            MeteringWeightProfile.MATRIX -> radiusFraction * 1.5f
            else -> radiusFraction
        }
        
        val halfWidth = (bounds.width * effectiveRadius).toInt().coerceAtLeast(1)
        val halfHeight = (bounds.height * effectiveRadius).toInt().coerceAtLeast(1)
        
        val pixelCenterX = (centerX * bounds.width).toInt().coerceIn(0, bounds.width - 1)
        val pixelCenterY = (centerY * bounds.height).toInt().coerceIn(0, bounds.height - 1)
        
        val left = (pixelCenterX - halfWidth).coerceIn(bounds.left, bounds.right - 1)
        val top = (pixelCenterY - halfHeight).coerceIn(bounds.top, bounds.bottom - 1)
        val right = (pixelCenterX + halfWidth).coerceIn(left + 1, bounds.right)
        val bottom = (pixelCenterY + halfHeight).coerceIn(top + 1, bounds.bottom)
        
        return CameraRect(left, top, right, bottom)
    }

    fun shouldRefreshExposureRegion(
        supportsManualSensor: Boolean,
        manualIso: Int,
        manualExposureNs: Long,
        currentRegion: Rect?,
        lastUpdateElapsedMs: Long,
        nowElapsedMs: Long,
        candidate: Rect
    ): Boolean = shouldRefreshExposureRegion(
        supportsManualSensor = supportsManualSensor,
        manualIso = manualIso,
        manualExposureNs = manualExposureNs,
        currentRegion = currentRegion?.toCameraRect(),
        lastUpdateElapsedMs = lastUpdateElapsedMs,
        nowElapsedMs = nowElapsedMs,
        candidate = candidate.toCameraRect()
    )

    fun shouldRefreshExposureRegion(
        supportsManualSensor: Boolean,
        manualIso: Int,
        manualExposureNs: Long,
        currentRegion: CameraRect?,
        lastUpdateElapsedMs: Long,
        nowElapsedMs: Long,
        candidate: CameraRect
    ): Boolean {
        if (supportsManualSensor && (manualIso > 0 || manualExposureNs > 0L)) return false
        val current = currentRegion ?: return true
        val centerDeltaX = abs(candidate.exactCenterX - current.exactCenterX)
        val centerDeltaY = abs(candidate.exactCenterY - current.exactCenterY)
        val widthDelta = abs(candidate.width - current.width)
        val heightDelta = abs(candidate.height - current.height)
        
        // cinema-oriented hysteresis - smoother transitions
        val movedMeaningfully =
            centerDeltaX > current.width * 0.15f ||
                centerDeltaY > current.height * 0.15f ||
                widthDelta > current.width * 0.15f ||
                heightDelta > current.height * 0.15f
        
        // Professional update interval - 800ms for stability
        return movedMeaningfully || (nowElapsedMs - lastUpdateElapsedMs) >= 800L
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // COORDINATE TRANSFORMATION - CINEMA PRECISION
    // ═══════════════════════════════════════════════════════════════════════════

    fun mapDisplayPointToSensor(
        normX: Float,
        normY: Float,
        lensFacing: Int,
        displayRotationDegrees: Int,
        normalizedCropRegion: NormalizedCropRegion?
    ): Pair<Float, Float> {
        var x = normX.coerceIn(0f, 1f)
        var y = normY.coerceIn(0f, 1f)
        if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            x = 1f - x
        }
        val (rotatedX, rotatedY) = when (((displayRotationDegrees % 360) + 360) % 360) {
            90 -> y to (1f - x)
            180 -> (1f - x) to (1f - y)
            270 -> (1f - y) to x
            else -> x to y
        }
        val crop = normalizedCropRegion ?: return rotatedX to rotatedY
        val cropWidth = (crop.right - crop.left).coerceAtLeast(ASPECT_TOLERANCE)
        val cropHeight = (crop.bottom - crop.top).coerceAtLeast(ASPECT_TOLERANCE)
        return (
            crop.left + rotatedX * cropWidth
            ).coerceIn(0f, 1f) to (
            crop.top + rotatedY * cropHeight
            ).coerceIn(0f, 1f)
    }

    /**
     * Inverse mapping from sensor coordinates to display coordinates.
     * Essential for AR overlays and subject tracking visualization.
     */
    fun mapSensorPointToDisplay(
        sensorX: Float,
        sensorY: Float,
        lensFacing: Int,
        displayRotationDegrees: Int,
        normalizedCropRegion: NormalizedCropRegion?
    ): Pair<Float, Float> {
        val crop = normalizedCropRegion ?: NormalizedCropRegion.FULL
        val cropWidth = (crop.right - crop.left).coerceAtLeast(ASPECT_TOLERANCE)
        val cropHeight = (crop.bottom - crop.top).coerceAtLeast(ASPECT_TOLERANCE)
        
        // Uncrop
        var x = ((sensorX - crop.left) / cropWidth).coerceIn(0f, 1f)
        var y = ((sensorY - crop.top) / cropHeight).coerceIn(0f, 1f)
        
        // Inverse rotation
        val (unrotatedX, unrotatedY) = when (((displayRotationDegrees % 360) + 360) % 360) {
            90 -> (1f - y) to x
            180 -> (1f - x) to (1f - y)
            270 -> y to (1f - x)
            else -> x to y
        }
        
        // Mirror compensation for front camera
        return if (lensFacing == CameraCharacteristics.LENS_FACING_FRONT) {
            (1f - unrotatedX) to unrotatedY
        } else {
            unrotatedX to unrotatedY
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ZOOM & CROP - cinema-oriented PRECISION
    // ═══════════════════════════════════════════════════════════════════════════

    fun sensorOutputAspectFromPreviewViewport(displayContentAspectRatio: Float?): Float? {
        val safeAspect = displayContentAspectRatio?.takeIf { it.isFinite() && it > 0f } ?: return null
        return if (safeAspect >= 1f) safeAspect else 1f / safeAspect
    }

    fun resolveCaptureRequestAspectRatio(
        sensorAspectRatio: Float,
        targetOutputAspectRatio: Float?,
        includeAspectCrop: Boolean
    ): Float {
        val sensorAspect = sensorAspectRatio.takeIf { it.isFinite() && it > 0f } ?: 1f
        val targetAspect = targetOutputAspectRatio?.takeIf { it.isFinite() && it > 0f }
        return targetAspect?.takeIf { includeAspectCrop } ?: sensorAspect
    }

    fun buildAspectAndZoomCropRegion(
        sensorRect: Rect,
        targetAspectRatio: Float,
        zoom: Float,
        maxZoom: Float
    ): Rect = buildAspectAndZoomCropRegion(
        sensorRect = sensorRect.toCameraRect(),
        targetAspectRatio = targetAspectRatio,
        zoom = zoom,
        maxZoom = maxZoom
    ).toRect()

    fun buildAspectAndZoomCropRegion(
        sensorRect: CameraRect,
        targetAspectRatio: Float,
        zoom: Float,
        maxZoom: Float
    ): CameraRect {
        val sensorWidth = sensorRect.width.toFloat().coerceAtLeast(1f)
        val sensorHeight = sensorRect.height.toFloat().coerceAtLeast(1f)
        val sensorAspect = sensorWidth / sensorHeight
        
        // HIGH-END FIX: Allow portrait ratios ( < 1.0). 
        // Do not force >= 1.0 here, as it breaks vertical cinema modes.
        val normalizedTargetAspect = targetAspectRatio

        var cropWidth = sensorWidth
        var cropHeight = sensorHeight
        var cropLeft = sensorRect.left.toFloat()
        var cropTop = sensorRect.top.toFloat()

        if (abs(normalizedTargetAspect - sensorAspect) > ASPECT_TOLERANCE) {
            if (normalizedTargetAspect > sensorAspect) {
                // Target is wider than sensor (e.g. 2.35:1 on 4:3 sensor)
                cropHeight = (sensorWidth / normalizedTargetAspect).coerceAtMost(sensorHeight)
                cropTop = sensorRect.top + (sensorHeight - cropHeight) * 0.5f
            } else {
                // Target is narrower than sensor (e.g. 9:16 on 4:3 sensor)
                cropWidth = (sensorHeight * normalizedTargetAspect).coerceAtMost(sensorWidth)
                cropLeft = sensorRect.left + (sensorWidth - cropWidth) * 0.5f
            }
        }

        val clampedZoom = zoom.coerceIn(1f, maxZoom)
        if (clampedZoom > 1f) {
            val zoomedWidth = (cropWidth / clampedZoom).coerceAtLeast(1f)
            val zoomedHeight = (cropHeight / clampedZoom).coerceAtLeast(1f)
            
            // Ensure minimum crop percentage for quality
            val minWidth = sensorWidth * MIN_CROP_PERCENTAGE
            val minHeight = sensorHeight * MIN_CROP_PERCENTAGE
            val finalWidth = zoomedWidth.coerceAtLeast(minWidth)
            val finalHeight = zoomedHeight.coerceAtLeast(minHeight)
            
            cropLeft += (cropWidth - finalWidth) * 0.5f
            cropTop += (cropHeight - finalHeight) * 0.5f
            cropWidth = finalWidth
            cropHeight = finalHeight
        }

        val left = cropLeft.toInt().coerceIn(sensorRect.left, sensorRect.right - 1)
        val top = cropTop.toInt().coerceIn(sensorRect.top, sensorRect.bottom - 1)
        val right = (cropLeft + cropWidth).toInt().coerceIn(left + 1, sensorRect.right)
        val bottom = (cropTop + cropHeight).toInt().coerceIn(top + 1, sensorRect.bottom)
        return CameraRect(left, top, right, bottom)
    }

    /**
     * Compute smooth zoom transition for cinematic zoom effects.
     * Uses exponential interpolation for natural zoom feel.
     */
    fun computeSmoothZoomStep(
        currentZoom: Float,
        targetZoom: Float,
        minZoom: Float,
        maxZoom: Float,
        smoothFactor: Float = SMOOTH_ZOOM_FACTOR
    ): Float {
        val delta = targetZoom - currentZoom
        if (abs(delta) < 0.001f) return targetZoom
        
        // Exponential easing for cinema-feel zoom
        val effectiveSmoothFactor = if (strictHighEndEnabled()) {
            (smoothFactor * 0.82f + abs(delta) * 0.02f).coerceIn(0.08f, 0.28f)
        } else {
            smoothFactor
        }
        val step = delta * effectiveSmoothFactor
        val nextZoom = currentZoom + step
        return nextZoom.coerceIn(minZoom, maxZoom)
    }

    /**
     * Calculate effective field of view change for zoom level.
     * Returns horizontal FOV multiplier.
     */
    fun calculateFovMultiplier(zoomRatio: Float): Float {
        return (1f / zoomRatio.coerceAtLeast(1f)).coerceIn(
            if (strictHighEndEnabled()) 0.08f else 0f,
            1f
        )
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // EXPOSURE MATHEMATICS - PROFESSIONAL GRADE
    // ═══════════════════════════════════════════════════════════════════════════

    fun previewSafeExposure(
        requestedExposureNs: Long,
        previewFps: Int,
        exposureRange: Range<Long>,
        maxShutterAngleDegrees: Float = 180f
    ): Long = previewSafeExposure(
        requestedExposureNs = requestedExposureNs,
        previewFps = previewFps,
        exposureLowerNs = exposureRange.lower,
        exposureUpperNs = exposureRange.upper,
        maxShutterAngleDegrees = maxShutterAngleDegrees
    )

    fun previewSafeExposure(
        requestedExposureNs: Long,
        previewFps: Int,
        exposureLowerNs: Long,
        exposureUpperNs: Long,
        maxShutterAngleDegrees: Float = 180f
    ): Long {
        val sanitizedPreviewFps = previewFps.coerceAtLeast(MIN_PREVIEW_EXPOSURE_BUDGET_FPS)
        val frameBudgetNs = (1_000_000_000L / sanitizedPreviewFps).coerceAtLeast(exposureLowerNs)
        
        val shutterRatio = (maxShutterAngleDegrees.coerceIn(1f, 360f).toDouble() / 360.0)
            .takeIf { it.isFinite() }
            ?: CINEMA_SHUTTER_ANGLE_RATIO.toDouble()
        val cinemaExposureCap = (frameBudgetNs.toDouble() * shutterRatio).toLong()
        val exposureCapNs = when {
            sanitizedPreviewFps >= 120 -> min(cinemaExposureCap, MAX_PREVIEW_EXPOSURE_NS_120_FPS)
            sanitizedPreviewFps >= 60 -> min(cinemaExposureCap, MAX_PREVIEW_EXPOSURE_NS_60_FPS)
            else -> cinemaExposureCap
        }
        // The cinema shutter cap (frameBudget * shutterRatio) can fall below the device exposure
        // floor (e.g. a high exposure floor at low fps, or a small shutter angle). Clamp the upper
        // bound up to the floor so coerceIn never sees max < min (which throws). Behaviour is
        // otherwise unchanged: when the cap is >= the floor, coerceAtLeast(lower) is a no-op.
        val safeUpperNs = exposureCapNs.coerceAtMost(exposureUpperNs).coerceAtLeast(exposureLowerNs)
        return requestedExposureNs.coerceIn(exposureLowerNs, safeUpperNs)
    }

    fun previewSafeFrameDuration(
        appliedExposureNs: Long,
        previewFps: Int,
        exposureRange: Range<Long>
    ): Long = previewSafeFrameDuration(
        appliedExposureNs = appliedExposureNs,
        previewFps = previewFps,
        exposureLowerNs = exposureRange.lower,
        exposureUpperNs = exposureRange.upper
    )

    fun previewSafeFrameDuration(
        appliedExposureNs: Long,
        previewFps: Int,
        exposureLowerNs: Long,
        exposureUpperNs: Long
    ): Long {
        val sanitizedPreviewFps = previewFps.coerceAtLeast(MIN_PREVIEW_EXPOSURE_BUDGET_FPS)
        val frameBudgetNs = (1_000_000_000L / sanitizedPreviewFps).coerceAtLeast(exposureLowerNs)
        return max(appliedExposureNs, frameBudgetNs).coerceAtMost(exposureUpperNs)
    }

    /**
     * Compute professional exposure value (EV) from ISO and shutter speed.
     * EV = log2(N² / t) where N is aperture (assumed f/1.8) and t is exposure time.
     */
    fun computeExposureValue(
        iso: Int,
        exposureNs: Long,
        aperture: Float = 1.8f
    ): Float {
        if (iso <= 0 || exposureNs <= 0L) return 0f
        val exposureSeconds = exposureNs / 1_000_000_000.0
        val ev100 = ln(aperture * aperture / exposureSeconds) / ln(2.0)
        val isoCompensation = ln(iso.toDouble() / ISO_BASE) / ln(2.0)
        return (ev100 - isoCompensation).toFloat()
    }

    /**
     * Compute ISO and exposure time for target EV.
     * Returns optimized values preferring lower ISO for better quality.
     */
    fun computeExposureForEv(
        targetEv: Float,
        isoRange: Range<Int>,
        exposureRange: Range<Long>,
        aperture: Float = 1.8f,
        preferLowIso: Boolean = true
    ): ExposureComputation {
        val targetEv100 = targetEv.toDouble()
        val strictHighEnd = strictHighEndEnabled()
        
        // Start with base ISO and compute required exposure
        var iso = if (preferLowIso || strictHighEnd) {
            isoRange.lower
        } else {
            (isoRange.lower + isoRange.upper) / 2
        }
        var exposureSeconds = (aperture * aperture) / 2.0.pow(targetEv100 + ln(iso.toDouble() / ISO_BASE) / ln(2.0))
        var exposureNs = (exposureSeconds * 1_000_000_000.0).toLong()
        
        // Adjust ISO if exposure is out of range
        if (exposureNs > exposureRange.upper) {
            val requiredIsoBoost = exposureNs.toDouble() / exposureRange.upper
            iso = (iso * requiredIsoBoost).toInt().coerceIn(isoRange.lower, isoRange.upper)
            exposureNs = exposureRange.upper
        } else if (exposureNs < exposureRange.lower) {
            val requiredIsoReduction = exposureRange.lower.toDouble() / exposureNs
            iso = (iso / requiredIsoReduction).toInt().coerceIn(isoRange.lower, isoRange.upper)
            exposureNs = exposureRange.lower
        }
        
        exposureNs = exposureNs.coerceIn(exposureRange.lower, exposureRange.upper)
        
        // Compute actual EV achieved
        val actualEv = computeExposureValue(iso, exposureNs, aperture)
        
        // Estimate dynamic range (simplified - based on ISO)
        val dynamicRangeStops = (14f - (ln(iso.toDouble() / ISO_BASE) / ln(2.0)).toFloat() *
            if (strictHighEnd) 0.45f else 0.5f).coerceIn(8f, 14.5f)
        
        return ExposureComputation(
            exposureNs = exposureNs,
            frameDurationNs = exposureNs,
            exposureValue = actualEv,
            dynamicRangeStops = dynamicRangeStops
        )
    }

    /**
     * Calculate HDR bracket exposures for multi-frame capture.
     * Returns list of EV compensation values centered around base exposure.
     */
    fun computeHdrBracketEvStops(
        frameCount: Int,
        evSpacing: Float = 2f,
        biasTowardsShadows: Boolean = true
    ): List<Float> {
        if (frameCount <= 1) return listOf(0f)
        val strictHighEnd = strictHighEndEnabled()
        
        val halfRange = (frameCount - 1) / 2f
        val biasOffset = if (biasTowardsShadows) evSpacing * 0.25f else 0f
        
        val baseStops = (0 until frameCount).map { i ->
            ((i - halfRange) * evSpacing - biasOffset)
        }
        if (!strictHighEnd) return baseStops
        return (baseStops + listOf(baseStops.last() + evSpacing * 0.5f))
            .distinct()
            .sorted()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FPS RANGE RESOLUTION - CINEMA OPTIMIZED
    // ═══════════════════════════════════════════════════════════════════════════

    fun resolveFpsRange(targetFps: Int, fpsList: Array<Range<Int>>): Range<Int>? =
        resolveFpsRange(targetFps, fpsList.map { CameraIntRange(it.lower, it.upper) })?.toRange()

    fun resolveFpsRange(targetFps: Int, fpsList: List<CameraIntRange>): CameraIntRange? {
        val clampedTarget = targetFps.coerceAtLeast(MIN_PREVIEW_EXPOSURE_BUDGET_FPS)
        return fpsList.minWithOrNull(
            compareBy<CameraIntRange> {
                when {
                    // Perfect match - locked FPS (best for cinema)
                    it.upper == clampedTarget && it.lower == clampedTarget -> 0
                    // Variable range containing target
                    it.upper >= clampedTarget && it.lower <= clampedTarget -> 1
                    // Range can reach target
                    it.upper >= clampedTarget -> 2
                    else -> 3
                }
            }.thenBy { abs(it.upper - clampedTarget) }
                .thenBy { abs(it.lower - clampedTarget.coerceAtMost(it.upper)) }
                .thenByDescending { it.upper }
                // Prefer narrower ranges for more consistent frame timing
                .thenBy { it.span }
        )
    }

    /**
     * Find closest cinema-standard frame rate.
     * Cinema standards exposed by the app: 24, 25, 48, 50, 60, 96, 120.
     */
    fun snapToCinemaFps(targetFps: Int): Int {
        val cinemaFps = if (strictHighEndEnabled()) {
            intArrayOf(24, 25, 48, 50, 60, 96, 120)
        } else {
            intArrayOf(24, 25, 48, 50, 60, 120)
        }
        return cinemaFps.minByOrNull { abs(it - targetFps) } ?: targetFps
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SIZE SELECTION - PROFESSIONAL RESOLUTION HANDLING
    // ═══════════════════════════════════════════════════════════════════════════

    fun chooseLargestSizeWithinBounds(sizes: List<Size>, maxWidth: Int, maxHeight: Int): Size? =
        chooseLargestSizeWithinBounds(
            sizes = sizes.map { it.toCameraSize() },
            maxWidth = maxWidth,
            maxHeight = maxHeight
        )?.toSize()

    fun chooseLargestSizeWithinBounds(
        sizes: List<CameraSize>,
        maxWidth: Int,
        maxHeight: Int
    ): CameraSize? =
        sizes.filter { it.width <= maxWidth && it.height <= maxHeight }
            .maxByOrNull { it.pixelArea() }
            ?: sizes.maxByOrNull { it.pixelArea() }

    fun chooseSizeForAspect(
        sizes: List<Size>,
        targetAspect: Float?,
        maxWidth: Int,
        maxHeight: Int
    ): Size? = chooseSizeForAspect(
        sizes = sizes.map { it.toCameraSize() },
        targetAspect = targetAspect,
        maxWidth = maxWidth,
        maxHeight = maxHeight
    )?.toSize()

    fun chooseSizeForAspect(
        sizes: List<CameraSize>,
        targetAspect: Float?,
        maxWidth: Int,
        maxHeight: Int
    ): CameraSize? {
        val aspect = targetAspect ?: return null
        return sizes
            .filter { it.width <= maxWidth && it.height <= maxHeight }
            .minWithOrNull(
                compareBy<CameraSize> { abs((it.width.toFloat() / it.height) - aspect) }
                    .thenByDescending { it.pixelArea() }
            )
            ?: sizes.minWithOrNull(
                compareBy<CameraSize> { abs((it.width.toFloat() / it.height) - aspect) }
                    .thenByDescending { it.pixelArea() }
            )
    }

    /**
     * Select optimal capture resolution for target megapixel count.
     * Used for ultra-high resolution sensor binning decisions.
     */
    fun chooseSizeForMegapixels(
        sizes: List<Size>,
        targetMegapixels: Float,
        aspectRatio: Float? = null
    ): Size? {
        val cameraSizes = sizes.map { it.toCameraSize() }
        val filtered = if (aspectRatio != null) {
            cameraSizes.filter { abs(it.aspectRatio - aspectRatio) < ASPECT_TOLERANCE }
        } else {
            cameraSizes
        }

        return if (strictHighEndEnabled()) {
            filtered.minWithOrNull(
                compareBy<CameraSize> { abs(it.megapixels - targetMegapixels) }
                    .thenByDescending { it.pixelArea() }
            )?.toSize()
        } else {
            filtered.minByOrNull { abs(it.megapixels - targetMegapixels) }?.toSize()
        }
            ?: sizes.minByOrNull { abs(it.width.toLong() * it.height / 1_000_000f - targetMegapixels) }
    }

    // 6K/8K removed from the product; intentionally not exposing helpers for it.

    /**
     * Check if resolution supports cinema 4K (4096x2160 DCI standard).
     */
    fun supportsCinema4K(sizes: List<Size>): Boolean {
        return sizes.any { it.width >= 4096 && it.height >= 2160 }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FOCUS MATHEMATICS - PRECISION OPTICS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Convert focus distance (diopters) to approximate physical distance (meters).
     * Distance = 1 / diopters (where diopters > 0)
     */
    fun dioptersToDis(diopters: Float): Float {
        if (diopters <= 0f) return Float.POSITIVE_INFINITY // Infinity focus
        return 1f / diopters
    }

    /**
     * Convert physical distance (meters) to diopters.
     * Diopters = 1 / distance
     */
    fun distanceToDiopters(meters: Float): Float {
        if (meters <= 0f || meters.isInfinite()) return 0f // Infinity focus
        return 1f / meters
    }

    /**
     * Calculate hyperfocal distance for depth of field.
     * H = f² / (N × c) where f=focal length, N=aperture, c=circle of confusion
     */
    fun calculateHyperfocalDistance(
        focalLengthMm: Float,
        aperture: Float,
        sensorSizeMm: Float = 6.17f, // Common smartphone sensor
        circleOfConfusionFactor: Float = 0.001f
    ): Float {
        val cocFactor = if (strictHighEndEnabled()) {
            (circleOfConfusionFactor * 0.9f).coerceAtLeast(0.0005f)
        } else {
            circleOfConfusionFactor
        }
        val circleOfConfusion = sensorSizeMm * cocFactor
        return (focalLengthMm * focalLengthMm) / (aperture * circleOfConfusion * 1000f)
    }

    /**
     * Check if two focus distances are perceptually similar (within focus peaking threshold).
     */
    fun isFocusDistanceSimilar(
        diopters1: Float,
        diopters2: Float,
        threshold: Float = FOCUS_PEAKING_THRESHOLD
    ): Boolean {
        return abs(diopters1 - diopters2) < threshold
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PRIVATE UTILITIES
    // ═══════════════════════════════════════════════════════════════════════════

    private fun Rect.toCameraRect(): CameraRect = CameraRect(left, top, right, bottom)

    private fun CameraRect.toRect(): Rect = Rect(left, top, right, bottom)

    private fun Size.toCameraSize(): CameraSize = CameraSize(width, height)

    private fun CameraSize.pixelArea(): Long = width.toLong() * height.toLong()

    private fun CameraSize.toSize(): Size = Size(width, height)

    private fun CameraIntRange.toRange(): Range<Int> = Range(lower, upper)
}
