package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.camera.FrameGeometry
import com.neoproc.neoproccamx.camera.NormalizedCropRegion
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
// Vulkan Preview Transform Engine
//
// Pure-math computation of the 4×4 preview transform matrix.
// Handles rotation, mirror, center-crop fill, and aspect-ratio
// compensation for the Vulkan compositor.
//
// Extracted from CameraViewModel (lines 5332-5438) as Sprint 2
// of the decomposition. Zero side-effects, fully unit-testable.
// ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

object VulkanPreviewTransformEngine {
    private const val FOUR_THREE_LANDSCAPE_ASPECT = 4f / 3f
    private const val FOUR_THREE_PORTRAIT_ASPECT = 3f / 4f
    private const val ASPECT_EPSILON = 0.01f

    private fun isFourThreeAspect(aspect: Float): Boolean =
        abs(aspect - FOUR_THREE_LANDSCAPE_ASPECT) <= ASPECT_EPSILON ||
            abs(aspect - FOUR_THREE_PORTRAIT_ASPECT) <= ASPECT_EPSILON

    /**
     * Builds the 4×4 column-major transform matrix for the Vulkan
     * preview compositor.
     *
     * The live preview texture already arrives with the active Camera2
     * crop/zoom applied, so we use [NormalizedCropRegion.FULL] to avoid
     * double-cropping.
     *
     * @param geometry       current frame geometry (rotation, aspect, mirror)
     * @param displayAspect  aspect ratio of the preview surface (width/height)
     * @param fallbackContentAspect  fallback content aspect if geometry is null
     */
    fun buildTransformMatrix(
        geometry: FrameGeometry?,
        displayAspect: Float,
        fallbackContentAspect: Float?,
        resolvedDisplayContentAspect: Float? = null
    ): FloatArray {
        val rotation = geometry?.previewRotationDegrees ?: 0
        val mirror = geometry?.previewMirrored == true

        // Prefer the display-resolved content aspect when provided; this is already
        // orientation-aware and should not be re-derived from sensor-space geometry.
        val resolvedAspect = resolvedDisplayContentAspect?.takeIf { it.isFinite() && it > 0f }
        val contentAspectBase =
            resolvedAspect
                ?: geometry?.outputAspectRatio
                ?: geometry?.sensorAspectRatio
                ?: fallbackContentAspect
                ?: 1f

        // For 90/270 rotations, invert only when aspect came from sensor-space sources.
        val usesResolvedAspect = resolvedAspect != null
        val orientedContentAspect =
            if ((rotation % 180) != 0 && !usesResolvedAspect) {
                1f / contentAspectBase.coerceAtLeast(1e-4f)
            } else {
                contentAspectBase
            }
        val effectiveDisplayAspect =
            if (usesResolvedAspect && isFourThreeAspect(orientedContentAspect)) {
                orientedContentAspect
            } else {
                displayAspect
            }

        // Texture-coordinate framing. Without a resolved display content aspect we keep the
        // legacy center-crop behavior. With one, the shader owns fit/framing so the SurfaceView
        // layer can stay 1:1 and avoid device-specific non-uniform layer stretch.
        val fitScaleX: Float
        val fitScaleY: Float
        if (usesResolvedAspect) {
            if (effectiveDisplayAspect > orientedContentAspect) {
                fitScaleX = effectiveDisplayAspect / orientedContentAspect.coerceAtLeast(1e-4f)
                fitScaleY = 1f
            } else {
                fitScaleX = 1f
                fitScaleY = orientedContentAspect / effectiveDisplayAspect.coerceAtLeast(1e-4f)
            }
        } else if (effectiveDisplayAspect > orientedContentAspect) {
            fitScaleX = 1f
            fitScaleY = orientedContentAspect / effectiveDisplayAspect.coerceAtLeast(1e-4f)
        } else {
            fitScaleX = effectiveDisplayAspect / orientedContentAspect.coerceAtLeast(1e-4f)
            fitScaleY = 1f
        }

        val radians = (-rotation.toFloat() * PI / 180.0).toFloat()
        val cosTheta = cos(radians)
        val sinTheta = sin(radians)

        // Build 2D affine transform analytically:
        // center translate -> crop scale -> rotation -> optional mirror -> translate back.
        val aBase = cosTheta * fitScaleX
        val bBase = -sinTheta * fitScaleY
        val c = sinTheta * fitScaleX
        val d = cosTheta * fitScaleY
        val a = if (mirror) -aBase else aBase
        val b = if (mirror) -bBase else bBase
        val tx = 0.5f - (a + b) * 0.5f
        val ty = 0.5f - (c + d) * 0.5f

        return floatArrayOf(
            a, c, 0f, 0f,       // Column 1
            b, d, 0f, 0f,       // Column 2
            0f,   0f,   1f, 0f, // Column 3
            tx, ty, 0f, 1f      // Column 4
        )
    }
}
