package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.camera.NormalizedCropRegion
import com.neoproc.neoproccamx.core.HighEndQualityPolicy

internal data class PreviewSurfaceTransformPlan(
    val contentBounds: PreviewTransformMath.RectBounds,
    val sourcePoints: FloatArray,
    val destinationPoints: FloatArray,
    val stabilization: PreviewRenderTuning.StabilizationTransform
)

internal data class PreviewSurfacePresentation(
    val contentBounds: PreviewTransformMath.RectBounds,
    val scaleX: Float,
    val scaleY: Float,
    val translationX: Float,
    val translationY: Float
)

internal object PreviewSurfaceTransformController {
    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    private fun safeBoundsWidth(viewWidth: Float): Int =
        if (strictHighEndEnabled()) {
            kotlin.math.ceil(viewWidth.toDouble()).toInt().coerceAtLeast(1)
        } else {
            viewWidth.toInt().coerceAtLeast(1)
        }

    private fun safeBoundsHeight(viewHeight: Float): Int =
        if (strictHighEndEnabled()) {
            kotlin.math.ceil(viewHeight.toDouble()).toInt().coerceAtLeast(1)
        } else {
            viewHeight.toInt().coerceAtLeast(1)
        }

    private fun resolvedStabilizationStrength(strength: Float): Float =
        strength.coerceIn(0f, 1f)

    fun buildDirectSurfacePresentation(
        viewWidth: Float,
        viewHeight: Float,
        streamWidth: Int,
        streamHeight: Int,
        targetAspect: Float,
        previewFillViewport: Boolean,
        mirrored: Boolean,
        sourceCropRegion: NormalizedCropRegion?,
        stabilizationStrength: Float,
        stabilizationAnchorX: Float,
        stabilizationAnchorY: Float
    ): PreviewSurfacePresentation {
        val contentBounds = PreviewContentBoundsResolver.resolve(
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            targetAspect = targetAspect,
            fillViewport = previewFillViewport
        )
        val stabilization = PreviewRenderTuning.computeStabilizationTransform(
            strength = resolvedStabilizationStrength(stabilizationStrength),
            anchorX = stabilizationAnchorX,
            anchorY = stabilizationAnchorY,
            contentWidth = contentBounds.width,
            contentHeight = contentBounds.height
        )
        val safeViewWidth = viewWidth.coerceAtLeast(1e-4f)
        val safeViewHeight = viewHeight.coerceAtLeast(1e-4f)
        val baseScaleX = contentBounds.width / safeViewWidth
        val baseScaleY = contentBounds.height / safeViewHeight
        return PreviewSurfacePresentation(
            contentBounds = contentBounds,
            scaleX = baseScaleX * stabilization.zoom * if (mirrored) -1f else 1f,
            scaleY = baseScaleY * stabilization.zoom,
            translationX = (if (mirrored) contentBounds.right else contentBounds.left) +
                stabilization.translationX,
            translationY = contentBounds.top + stabilization.translationY
        )
    }

    fun buildPlan(
        viewWidth: Float,
        viewHeight: Float,
        streamWidth: Int,
        streamHeight: Int,
        targetAspect: Float,
        previewFillViewport: Boolean,
        rotationDegrees: Int,
        mirrored: Boolean,
        sourceCropRegion: NormalizedCropRegion?,
        stabilizationStrength: Float,
        stabilizationAnchorX: Float,
        stabilizationAnchorY: Float
    ): PreviewSurfaceTransformPlan {
        val contentBounds = PreviewContentBoundsResolver.resolve(
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            targetAspect = targetAspect,
            fillViewport = previewFillViewport
        )
        val sourceAspect = PreviewAspectResolver.resolveDisplayStreamAspect(
            streamWidth = streamWidth,
            streamHeight = streamHeight,
            boundsWidth = safeBoundsWidth(viewWidth),
            boundsHeight = safeBoundsHeight(viewHeight)
        )
        return PreviewSurfaceTransformPlan(
            contentBounds = contentBounds,
            sourcePoints = PreviewTransformMath.buildSourceCornerMapping(
                viewWidth = viewWidth,
                viewHeight = viewHeight,
                sourceAspect = sourceAspect,
                targetAspect = targetAspect,
                fillViewport = previewFillViewport,
                // The preview request already carries the active Camera2 crop/zoom.
                // Reapplying the sensor crop here double-crops the visible stream.
                sourceCropRegion = null
            ),
            destinationPoints = PreviewTransformMath.buildDestinationCornerMapping(
                contentBounds = contentBounds,
                rotationDegrees = rotationDegrees,
                mirrored = mirrored
            ),
            stabilization = PreviewRenderTuning.computeStabilizationTransform(
                strength = resolvedStabilizationStrength(stabilizationStrength),
                anchorX = stabilizationAnchorX,
                anchorY = stabilizationAnchorY,
                contentWidth = contentBounds.width,
                contentHeight = contentBounds.height
            )
        )
    }
}
