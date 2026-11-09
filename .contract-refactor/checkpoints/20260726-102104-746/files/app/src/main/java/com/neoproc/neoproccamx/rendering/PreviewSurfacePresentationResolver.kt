package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.camera.NormalizedCropRegion
import com.neoproc.neoproccamx.core.HighEndQualityPolicy

internal data class PreviewSurfacePresentationState(
    val scaleX: Float,
    val scaleY: Float,
    val translationX: Float,
    val translationY: Float,
    val rotation: Float
)

internal data class PreviewSurfacePresentationInput(
    val viewWidth: Float,
    val viewHeight: Float,
    val streamWidth: Int,
    val streamHeight: Int,
    val targetAspect: Float,
    val previewFillViewport: Boolean,
    val rotationDegrees: Int,
    val mirrored: Boolean,
    val nativeCompositorActive: Boolean,
    val sourceCropRegion: NormalizedCropRegion?,
    val stabilizationStrength: Float,
    val stabilizationAnchorX: Float,
    val stabilizationAnchorY: Float
)

internal object PreviewSurfacePresentationResolver {
    private const val FOUR_THREE_ASPECT = 4f / 3f
    private const val THREE_FOUR_ASPECT = 3f / 4f
    private const val ASPECT_EPSILON = 0.01f
    private const val STRICT_MIN_SCALE = 1e-4f
    private const val STRICT_TRANSLATION_EPSILON = 0.0001f

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    private fun normalizePresentationState(
        state: PreviewSurfacePresentationState
    ): PreviewSurfacePresentationState {
        if (!strictHighEndEnabled()) return state
        return state.copy(
            scaleX = when {
                state.scaleX > 0f -> state.scaleX.coerceAtLeast(STRICT_MIN_SCALE)
                state.scaleX < 0f -> state.scaleX.coerceAtMost(-STRICT_MIN_SCALE)
                else -> state.scaleX
            },
            scaleY = when {
                state.scaleY > 0f -> state.scaleY.coerceAtLeast(STRICT_MIN_SCALE)
                state.scaleY < 0f -> state.scaleY.coerceAtMost(-STRICT_MIN_SCALE)
                else -> state.scaleY
            },
            translationX = if (kotlin.math.abs(state.translationX) < STRICT_TRANSLATION_EPSILON) {
                0f
            } else {
                state.translationX
            },
            translationY = if (kotlin.math.abs(state.translationY) < STRICT_TRANSLATION_EPSILON) {
                0f
            } else {
                state.translationY
            }
        )
    }

    fun resolve(input: PreviewSurfacePresentationInput): PreviewSurfacePresentationState {
        val state = if (input.nativeCompositorActive) {
            buildNativePresentation(input)
        } else {
            buildDirectPresentation(input)
        }
        return normalizePresentationState(state)
    }

    private fun buildNativePresentation(
        input: PreviewSurfacePresentationInput
    ): PreviewSurfacePresentationState {
        if (!input.previewFillViewport && isFourThreeAspect(input.targetAspect)) {
            return buildDirectPresentation(input.copy(mirrored = false))
        }
        // Native Vulkan preview now performs aspect framing in the shader against the
        // physical surface aspect. Keep the SurfaceView layer unscaled; non-uniform
        // SurfaceView transforms are the source of stretched/squeezed live preview on
        // several vendor compositors.
        return PreviewSurfacePresentationState(
            scaleX = 1f,
            scaleY = 1f,
            translationX = 0f,
            translationY = 0f,
            rotation = 0f
        )
    }

    private fun isFourThreeAspect(aspect: Float): Boolean =
        kotlin.math.abs(aspect - FOUR_THREE_ASPECT) <= ASPECT_EPSILON ||
            kotlin.math.abs(aspect - THREE_FOUR_ASPECT) <= ASPECT_EPSILON

    private fun buildDirectPresentation(
        input: PreviewSurfacePresentationInput
    ): PreviewSurfacePresentationState {
        val directPresentation = PreviewSurfaceTransformController.buildDirectSurfacePresentation(
            viewWidth = input.viewWidth,
            viewHeight = input.viewHeight,
            streamWidth = input.streamWidth,
            streamHeight = input.streamHeight,
            targetAspect = input.targetAspect,
            previewFillViewport = input.previewFillViewport,
            mirrored = input.mirrored,
            sourceCropRegion = input.sourceCropRegion,
            stabilizationStrength = input.stabilizationStrength,
            stabilizationAnchorX = input.stabilizationAnchorX,
            stabilizationAnchorY = input.stabilizationAnchorY
        )
        return PreviewSurfacePresentationState(
            scaleX = directPresentation.scaleX,
            scaleY = directPresentation.scaleY,
            translationX = directPresentation.translationX,
            translationY = directPresentation.translationY,
            // Direct preview framing is already resolved in display space.
            // Rotating the SurfaceView again pushes portrait/alternate landscape
            // off-screen on some devices, leaving the preview black.
            rotation = 0f
        )
    }
}
