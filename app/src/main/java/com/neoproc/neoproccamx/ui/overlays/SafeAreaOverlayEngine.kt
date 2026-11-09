package com.neoproc.neoproccamx.ui.overlays

import android.graphics.RectF
import com.neoproc.neoproccamx.camera.CinemaAspectRatio
import com.neoproc.neoproccamx.camera.FrameGeometry
import com.neoproc.neoproccamx.camera.NormalizedCropRegion
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import kotlin.math.abs

/**
 * ════════════════════════════════════════════════════════════════════════════════
 * SAFE AREA OVERLAY ENGINE — OPEN GATE COMPOSITION GUIDES
 * ════════════════════════════════════════════════════════════════════════════════
 *
 * Implements professional-grade 'Open Gate' safe area overlays for the camera
 * preview. When recording in the native 4:3 full-MP sensor readout, the engine
 * renders composition guide overlays for commonly-reframed delivery formats:
 *
 *   - 4:3    Full-MP (Open Gate / Native)
 *   - 16:9   (Cinema Widescreen)
 *   - 2.39:1 (Anamorphic Scope)
 *   - 9:16   (Social / Vertical)
 *   - 1:1    (Social Square)
 *
 * All guide rectangles are geometrically aligned with FrameGeometry to ensure
 * zero parallax error (guides correspond exactly to the final cropped output).
 *
 * Thread safety: All methods are pure functions; no mutable state.
 *
 * NOTE: This engine does NOT change the preview or aspect ratio.
 * It only provides overlay geometry for visual composition guidance.
 */

/**
 * A safe area overlay definition.
 */
data class SafeAreaGuide(
    /** Display label for this guide (e.g., "16:9 Cinema") */
    val label: String,

    /** Target aspect ratio (width / height) */
    val aspectRatio: Float,

    /** Guide rectangle in display coordinates (after FrameGeometry alignment) */
    val displayRect: RectF,

    /** Guide rectangle in normalized sensor coordinates (0-1) */
    val normalizedRect: RectF,

    /** Whether this is the active recording format */
    val isActiveFormat: Boolean = false,

    /** Overlay tint color (ARGB) */
    val tintColor: Int = DEFAULT_GUIDE_COLOR,

    /** Overlay stroke width in dp */
    val strokeWidthDp: Float = 1.0f
) {
    companion object {
        const val DEFAULT_GUIDE_COLOR = 0x80FFFFFF.toInt()   // Semi-transparent white
        const val ACTIVE_GUIDE_COLOR = 0xB0FFD700.toInt()    // Gold for active format
        const val SOCIAL_GUIDE_COLOR = 0x80FF69B4.toInt()    // Hot pink for social formats
        const val CINEMA_GUIDE_COLOR = 0x8000CED1.toInt()    // Dark cyan for cinema formats
    }
}

/**
 * Complete set of safe area overlays for the current frame geometry.
 */
data class SafeAreaOverlaySet(
    /** All active safe area guides */
    val guides: List<SafeAreaGuide> = emptyList(),

    /** Whether the overlay system is active */
    val active: Boolean = false,

    /** The open gate (native sensor) aspect ratio */
    val openGateAspectRatio: Float = 4f / 3f,

    /** Current preview display dimensions */
    val displayWidth: Int = 0,
    val displayHeight: Int = 0,

    /** FrameGeometry hash for cache invalidation */
    val geometryHash: Int = 0
)

/**
 * Safe area format preset with target aspect ratio and display metadata.
 */
enum class SafeAreaPreset(
    val ratio: Float,
    val label: String,
    val category: SafeAreaCategory,
    val defaultEnabled: Boolean = true
) {
    OPEN_GATE_4_3(4f / 3f, "4:3 Full-MP", SafeAreaCategory.NATIVE, true),
    CINEMA_16_9(16f / 9f, "16:9", SafeAreaCategory.CINEMA, true),
    CINEMA_2_39_1(2.39f, "2.39:1 Scope", SafeAreaCategory.CINEMA, true),
    SOCIAL_9_16(9f / 16f, "9:16", SafeAreaCategory.SOCIAL, false),
    SOCIAL_1_1(1f, "1:1", SafeAreaCategory.SOCIAL, false),
    CINEMA_1_85_1(1.85f, "1.85:1 Flat", SafeAreaCategory.CINEMA, false),
    CINEMA_2_1(2.0f, "2:1 Univisium", SafeAreaCategory.CINEMA, false);

    val tintColor: Int
        get() = when (category) {
            SafeAreaCategory.NATIVE -> SafeAreaGuide.ACTIVE_GUIDE_COLOR
            SafeAreaCategory.CINEMA -> SafeAreaGuide.CINEMA_GUIDE_COLOR
            SafeAreaCategory.SOCIAL -> SafeAreaGuide.SOCIAL_GUIDE_COLOR
        }
}

enum class SafeAreaCategory { NATIVE, CINEMA, SOCIAL }

/** The physical preview corner used to keep a rotated guide label convenient for the viewer. */
enum class SafeAreaLabelCorner {
    TOP_LEFT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    TOP_RIGHT
}

/**
 * Fully resolved, allocation-light drawing geometry for the 2.39:1 indication badge.
 * [rotationDegrees] counter-rotates the badge against the physical device orientation.
 */
data class SafeAreaLabelLayout(
    val centerX: Float,
    val centerY: Float,
    val width: Float,
    val height: Float,
    val rotationDegrees: Float,
    val corner: SafeAreaLabelCorner
)

/**
 * The main safe area computation engine.
 * All methods are pure functions — no mutable state.
 */
object SafeAreaOverlayEngine {

    private const val TAG = "SafeAreaOverlay"

    /**
     * Compute the full set of safe area guides for the given frame geometry
     * and preview display dimensions.
     *
     * @param geometry Current FrameGeometry from the camera pipeline
     * @param displayWidth Preview view width in pixels
     * @param displayHeight Preview view height in pixels
     * @param enabledPresets Which safe area presets to render
     * @param activeRecordingRatio The currently-recording aspect ratio (null if not recording)
     * @return Complete SafeAreaOverlaySet ready for rendering
     */
    fun computeOverlays(
        geometry: FrameGeometry,
        displayWidth: Int,
        displayHeight: Int,
        enabledPresets: Set<SafeAreaPreset> = SafeAreaPreset.entries
            .filter { it.defaultEnabled }.toSet(),
        activeRecordingRatio: Float? = null
    ): SafeAreaOverlaySet {
        if (displayWidth <= 0 || displayHeight <= 0) {
            return SafeAreaOverlaySet()
        }

        val openGateRatio = geometry.outputAspectRatio ?: geometry.sensorAspectRatio
        val displayRatio = displayWidth.toFloat() / displayHeight

        // Compute the preview content rect (where the camera image is rendered)
        val previewContentRect = computePreviewContentRect(
            displayWidth, displayHeight, openGateRatio
        )

        val guides = enabledPresets.mapNotNull { preset ->
            computeGuide(
                preset = preset,
                openGateRatio = openGateRatio,
                previewContentRect = previewContentRect,
                geometry = geometry,
                activeRecordingRatio = activeRecordingRatio
            )
        }

        return SafeAreaOverlaySet(
            guides = guides,
            active = guides.isNotEmpty(),
            openGateAspectRatio = openGateRatio,
            displayWidth = displayWidth,
            displayHeight = displayHeight,
            geometryHash = geometry.hashCode()
        )
    }

    /**
     * Compute a single safe area guide for a given preset.
     *
     * The guide rectangle is geometrically aligned with the FrameGeometry
     * to ensure zero parallax error — the guide corresponds exactly to
     * the final cropped output from the open gate source.
     */
    private fun computeGuide(
        preset: SafeAreaPreset,
        openGateRatio: Float,
        previewContentRect: RectF,
        geometry: FrameGeometry,
        activeRecordingRatio: Float?
    ): SafeAreaGuide? {
        val targetRatio = preset.ratio
        val contentWidth = previewContentRect.width()
        val contentHeight = previewContentRect.height()

        if (contentWidth <= 0f || contentHeight <= 0f) return null

        // Skip if the target matches the open gate ratio (no guide needed)
        if (abs(targetRatio - openGateRatio) < 0.02f) return null

        // Compute the normalized crop region for the target aspect ratio
        // within the open gate frame (zero parallax alignment)
        val normalizedCrop = computeZeroParallaxCrop(targetRatio, openGateRatio)

        // Map normalized crop to display coordinates within the preview content rect
        val displayRect = RectF(
            previewContentRect.left + normalizedCrop.left * contentWidth,
            previewContentRect.top + normalizedCrop.top * contentHeight,
            previewContentRect.left + normalizedCrop.right * contentWidth,
            previewContentRect.top + normalizedCrop.bottom * contentHeight
        )

        // Determine if this is the active recording format
        val isActive = activeRecordingRatio != null &&
            abs(activeRecordingRatio - targetRatio) < 0.05f

        // Choose tint color based on category and active state
        val tintColor = if (isActive) SafeAreaGuide.ACTIVE_GUIDE_COLOR else preset.tintColor

        val strokeWidth = when {
            isActive -> 2.0f
            HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly -> 1.25f
            else -> 1.0f
        }

        return SafeAreaGuide(
            label = preset.label,
            aspectRatio = targetRatio,
            displayRect = displayRect,
            normalizedRect = RectF(
                normalizedCrop.left, normalizedCrop.top,
                normalizedCrop.right, normalizedCrop.bottom
            ),
            isActiveFormat = isActive,
            tintColor = tintColor,
            strokeWidthDp = strokeWidth
        )
    }

    /**
     * Normalized crop fractions [left, top, right, bottom] (each 0..1) for inscribing a guide of
     * [targetRatio] (width/height) centered, maximum-area, inside a preview frame of on-screen aspect
     * [frameAspect] (width/height). This is what anchors the guide to the REAL displayed preview
     * rectangle (UIOverlay.previewRect) rather than a recomputed one, so the box hugs the live image.
     *
     * This is the aspect-inscription primitive. Callers that want the same physical cinema frame in
     * both portrait and landscape should use [orientedScopeCropFractions], which transposes 2.39:1 to
     * 1:2.39 for portrait. Pure — no Android dependency, so it remains directly unit-testable.
     */
    fun scopeCropFractions(targetRatio: Float, frameAspect: Float): FloatArray {
        if (targetRatio <= 0f || frameAspect <= 0f) return floatArrayOf(0f, 0f, 1f, 1f)
        return when {
            // Target wider than frame -> letterbox (bars top/bottom): full width, cropped height.
            targetRatio > frameAspect + 0.001f -> {
                val h = frameAspect / targetRatio
                val m = (1f - h) / 2f
                floatArrayOf(0f, m, 1f, 1f - m)
            }
            // Target taller than frame -> pillarbox (bars left/right): full height, cropped width.
            targetRatio < frameAspect - 0.001f -> {
                val w = targetRatio / frameAspect
                val m = (1f - w) / 2f
                floatArrayOf(m, 0f, 1f - m, 1f)
            }
            else -> floatArrayOf(0f, 0f, 1f, 1f)
        }
    }

    /**
     * Inscribes the same physical scope frame in either display orientation.
     *
     * Landscape preview: horizontal 2.39:1 guide.
     * Portrait preview: vertical 1:2.39 guide, which becomes the identical horizontal scope frame
     * when the portrait-locked camera UI is physically held in landscape.
     */
    fun orientedScopeCropFractions(scopeRatio: Float, frameAspect: Float): FloatArray {
        if (scopeRatio <= 0f || frameAspect <= 0f) return floatArrayOf(0f, 0f, 1f, 1f)
        val orientedRatio = if (frameAspect < 1f) 1f / scopeRatio else scopeRatio
        return scopeCropFractions(orientedRatio, frameAspect)
    }

    /**
     * Resolves a gyro-aware badge position inside the guide. The badge always appears at the
     * viewer's visual top-left and remains upright for 0/90/180/270-degree device orientations.
     * Its dimensions are reduced only if required to remain completely inside a very small guide.
     */
    fun resolveScopeLabelLayout(
        guideLeft: Float,
        guideTop: Float,
        guideRight: Float,
        guideBottom: Float,
        deviceRotationDegrees: Int,
        desiredWidth: Float,
        desiredHeight: Float,
        edgeInset: Float
    ): SafeAreaLabelLayout? {
        val guideWidth = (guideRight - guideLeft).coerceAtLeast(0f)
        val guideHeight = (guideBottom - guideTop).coerceAtLeast(0f)
        if (guideWidth <= 0f || guideHeight <= 0f || desiredWidth <= 0f || desiredHeight <= 0f) {
            return null
        }

        val rotation = ((deviceRotationDegrees % 360) + 360) % 360
        val corner = when (rotation) {
            90 -> SafeAreaLabelCorner.BOTTOM_LEFT
            180 -> SafeAreaLabelCorner.BOTTOM_RIGHT
            270 -> SafeAreaLabelCorner.TOP_RIGHT
            else -> SafeAreaLabelCorner.TOP_LEFT
        }
        val textRotation = when (rotation) {
            90 -> -90f
            180 -> 180f
            270 -> 90f
            else -> 0f
        }

        val inset = edgeInset.coerceAtLeast(0f)
        val availableWidth = (guideWidth - inset * 2f).coerceAtLeast(0f)
        val availableHeight = (guideHeight - inset * 2f).coerceAtLeast(0f)
        val quarterTurn = rotation == 90 || rotation == 270
        val rotatedDesiredWidth = if (quarterTurn) desiredHeight else desiredWidth
        val rotatedDesiredHeight = if (quarterTurn) desiredWidth else desiredHeight
        if (availableWidth <= 0f || availableHeight <= 0f) return null
        val scale = minOf(
            1f,
            availableWidth / rotatedDesiredWidth,
            availableHeight / rotatedDesiredHeight
        ).coerceAtLeast(0f)
        val badgeWidth = desiredWidth * scale
        val badgeHeight = desiredHeight * scale

        val centerX: Float
        val centerY: Float
        when (corner) {
            SafeAreaLabelCorner.TOP_LEFT -> {
                centerX = guideLeft + inset + badgeWidth / 2f
                centerY = guideTop + inset + badgeHeight / 2f
            }
            SafeAreaLabelCorner.BOTTOM_LEFT -> {
                centerX = guideLeft + inset + badgeHeight / 2f
                centerY = guideBottom - inset - badgeWidth / 2f
            }
            SafeAreaLabelCorner.BOTTOM_RIGHT -> {
                centerX = guideRight - inset - badgeWidth / 2f
                centerY = guideBottom - inset - badgeHeight / 2f
            }
            SafeAreaLabelCorner.TOP_RIGHT -> {
                centerX = guideRight - inset - badgeHeight / 2f
                centerY = guideTop + inset + badgeWidth / 2f
            }
        }
        return SafeAreaLabelLayout(
            centerX = centerX,
            centerY = centerY,
            width = badgeWidth,
            height = badgeHeight,
            rotationDegrees = textRotation,
            corner = corner
        )
    }

    /**
     * Compute the zero-parallax-error crop region for a target aspect ratio
     * inscribed within the open gate frame.
     *
     * The crop is always centered and preserves the maximum area of the
     * target ratio within the source frame.
     *
     * For portrait targets (e.g., 9:16) inscribed in landscape sources
     * (e.g., 4:3), the guide shows a centered vertical strip.
     */
    private fun computeZeroParallaxCrop(
        targetRatio: Float,
        sourceRatio: Float
    ): RectF {
        return when {
            // Target is wider than source → letterbox (horizontal bars top/bottom)
            targetRatio > sourceRatio + 0.01f -> {
                val cropHeight = sourceRatio / targetRatio
                val margin = (1f - cropHeight) / 2f
                RectF(0f, margin, 1f, 1f - margin)
            }
            // Target is taller than source → pillarbox (vertical bars left/right)
            targetRatio < sourceRatio - 0.01f -> {
                val cropWidth = targetRatio / sourceRatio
                val margin = (1f - cropWidth) / 2f
                RectF(margin, 0f, 1f - margin, 1f)
            }
            // Same ratio
            else -> RectF(0f, 0f, 1f, 1f)
        }
    }

    /**
     * Compute the preview content rectangle within the display viewport.
     * This matches the area where the camera preview image is actually rendered.
     */
    private fun computePreviewContentRect(
        displayWidth: Int,
        displayHeight: Int,
        contentRatio: Float
    ): RectF {
        val displayRatio = displayWidth.toFloat() / displayHeight

        return when {
            contentRatio > displayRatio + 0.01f -> {
                // Content is wider — letterbox (center vertically)
                val contentHeight = displayWidth / contentRatio
                val offsetY = (displayHeight - contentHeight) / 2f
                RectF(0f, offsetY, displayWidth.toFloat(), offsetY + contentHeight)
            }
            contentRatio < displayRatio - 0.01f -> {
                // Content is taller — pillarbox (center horizontally)
                val contentWidth = displayHeight * contentRatio
                val offsetX = (displayWidth - contentWidth) / 2f
                RectF(offsetX, 0f, offsetX + contentWidth, displayHeight.toFloat())
            }
            else -> {
                // Exact fit
                RectF(0f, 0f, displayWidth.toFloat(), displayHeight.toFloat())
            }
        }
    }

    /**
     * Utility: Compute the guide label for professional HUD display.
     */
    fun formatGuideLabel(guide: SafeAreaGuide): String {
        return buildString {
            append(guide.label)
            if (guide.isActiveFormat) append(" ●")
            val widthPct = ((guide.normalizedRect.width()) * 100).toInt()
            val heightPct = ((guide.normalizedRect.height()) * 100).toInt()
            append(" (${widthPct}%×${heightPct}%)")
        }
    }
}
