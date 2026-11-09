package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.camera.NormalizedCropRegion
import com.neoproc.neoproccamx.core.HighEndQualityPolicy

internal data class PreviewFramingRequest(
    val viewWidth: Int,
    val viewHeight: Int,
    val streamWidth: Int,
    val streamHeight: Int,
    val displayContentAspectRatioOverride: Float?,
    val aspectRatioOverride: Float?,
    val previewFillViewport: Boolean,
    val rotationDegrees: Int,
    val mirrored: Boolean,
    val nativeCompositorActive: Boolean,
    val sourceCropRegion: NormalizedCropRegion?,
    val stabilizationStrength: Float,
    val stabilizationAnchorX: Float,
    val stabilizationAnchorY: Float
)

internal data class PreviewFramingResolution(
    val targetAspect: Float,
    val presentation: PreviewSurfacePresentationState
)

internal object PreviewFramingResolver {
    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    private fun resolvedStabilizationStrength(strength: Float): Float {
        val clamped = strength.coerceIn(0f, 1f)
        return if (strictHighEndEnabled() && clamped < 0.01f) 0f else clamped
    }

    fun resolve(request: PreviewFramingRequest): PreviewFramingResolution {
        val targetAspect = resolveTargetAspect(request)
        val presentation = PreviewSurfacePresentationResolver.resolve(
            PreviewSurfacePresentationInput(
                viewWidth = request.viewWidth.toFloat(),
                viewHeight = request.viewHeight.toFloat(),
                streamWidth = request.streamWidth,
                streamHeight = request.streamHeight,
                targetAspect = targetAspect,
                previewFillViewport = request.previewFillViewport,
                rotationDegrees = request.rotationDegrees,
                mirrored = request.mirrored,
                nativeCompositorActive = request.nativeCompositorActive,
                sourceCropRegion = request.sourceCropRegion,
                stabilizationStrength = resolvedStabilizationStrength(request.stabilizationStrength),
                stabilizationAnchorX = request.stabilizationAnchorX,
                stabilizationAnchorY = request.stabilizationAnchorY
            )
        )
        return PreviewFramingResolution(
            targetAspect = targetAspect,
            presentation = presentation
        )
    }

    fun resolveTargetAspect(request: PreviewFramingRequest): Float {
        return PreviewAspectResolver.resolveDisplayAspect(
            boundsWidth = request.viewWidth,
            boundsHeight = request.viewHeight,
            streamWidth = request.streamWidth,
            streamHeight = request.streamHeight,
            displayContentAspectRatioOverride = request.displayContentAspectRatioOverride,
            aspectRatioOverride = request.aspectRatioOverride
        )
    }
}
