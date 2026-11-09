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
    /** Clip in the SurfaceView's local coordinates, applied before the View transform. */
    val localClipBounds: PreviewTransformMath.RectBounds?,
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
        val sourceAspect = PreviewAspectResolver.resolveDisplayStreamAspect(
            boundsWidth = safeBoundsWidth(viewWidth),
            boundsHeight = safeBoundsHeight(viewHeight),
            streamWidth = streamWidth,
            streamHeight = streamHeight
        )
        val sourcePresentationBounds = coverBounds(
            destination = contentBounds,
            sourceAspect = sourceAspect
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
        val baseScaleX = sourcePresentationBounds.width / safeViewWidth
        val baseScaleY = sourcePresentationBounds.height / safeViewHeight
        val scaleX = baseScaleX * stabilization.zoom * if (mirrored) -1f else 1f
        val scaleY = baseScaleY * stabilization.zoom
        val translationX = (if (mirrored) {
            sourcePresentationBounds.right
        } else {
            sourcePresentationBounds.left
        }) + stabilization.translationX
        val translationY = sourcePresentationBounds.top + stabilization.translationY
        val needsCrop = !previewFillViewport && (
            kotlin.math.abs(sourceAspect - targetAspect) > 0.0001f ||
                stabilization.zoom > 1.0001f ||
                kotlin.math.abs(stabilization.translationX) > 0.0001f ||
                kotlin.math.abs(stabilization.translationY) > 0.0001f
            )
        return PreviewSurfacePresentation(
            contentBounds = contentBounds,
            localClipBounds = if (needsCrop) {
                inverseMappedClipBounds(
                    contentBounds = contentBounds,
                    viewWidth = safeViewWidth,
                    viewHeight = safeViewHeight,
                    scaleX = scaleX,
                    scaleY = scaleY,
                    translationX = translationX,
                    translationY = translationY
                )
            } else {
                null
            },
            scaleX = scaleX,
            scaleY = scaleY,
            translationX = translationX,
            translationY = translationY
        )
    }

    /**
     * Covers the requested display frame with the camera stream without changing pixel aspect.
     * Any excess is removed by [inverseMappedClipBounds], so a 4:3 transport can present 1:1 or
     * 16:9 instantly without resizing or abandoning the Camera2 Surface.
     */
    private fun coverBounds(
        destination: PreviewTransformMath.RectBounds,
        sourceAspect: Float
    ): PreviewTransformMath.RectBounds {
        val safeAspect = sourceAspect.coerceAtLeast(1e-4f)
        val destinationAspect = destination.width / destination.height.coerceAtLeast(1e-4f)
        val width: Float
        val height: Float
        if (safeAspect >= destinationAspect) {
            height = destination.height
            width = height * safeAspect
        } else {
            width = destination.width
            height = width / safeAspect
        }
        return PreviewTransformMath.RectBounds(
            left = destination.centerX - width * 0.5f,
            top = destination.centerY - height * 0.5f,
            right = destination.centerX + width * 0.5f,
            bottom = destination.centerY + height * 0.5f
        )
    }

    private fun inverseMappedClipBounds(
        contentBounds: PreviewTransformMath.RectBounds,
        viewWidth: Float,
        viewHeight: Float,
        scaleX: Float,
        scaleY: Float,
        translationX: Float,
        translationY: Float
    ): PreviewTransformMath.RectBounds {
        val x0 = (contentBounds.left - translationX) / scaleX
        val x1 = (contentBounds.right - translationX) / scaleX
        val y0 = (contentBounds.top - translationY) / scaleY
        val y1 = (contentBounds.bottom - translationY) / scaleY
        return PreviewTransformMath.RectBounds(
            left = minOf(x0, x1).coerceIn(0f, viewWidth),
            top = minOf(y0, y1).coerceIn(0f, viewHeight),
            right = maxOf(x0, x1).coerceIn(0f, viewWidth),
            bottom = maxOf(y0, y1).coerceIn(0f, viewHeight)
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
