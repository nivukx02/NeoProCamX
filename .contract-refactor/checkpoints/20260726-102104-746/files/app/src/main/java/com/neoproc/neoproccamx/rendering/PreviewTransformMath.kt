package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.camera.NormalizedCropRegion
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import com.neoproc.neoproccamx.ui.SensorCropEngine
import com.neoproc.neoproccamx.utils.OrientationManager

internal object PreviewTransformMath {
    private const val MIN_EDGE = 1e-4f
    private const val STRICT_MIN_EDGE = 1e-3f
    private const val MIN_ASPECT = 1e-4f
    private const val STRICT_MIN_ASPECT = 0.2f
    private const val STRICT_MAX_ASPECT = 5f

    private fun strictHighEndEnabled(): Boolean =
        HighEndQualityPolicy.enabled && HighEndQualityPolicy.strictHighEndOnly

    private fun safeEdge(edge: Float): Float =
        edge.coerceAtLeast(if (strictHighEndEnabled()) STRICT_MIN_EDGE else MIN_EDGE)

    private fun safeAspect(aspect: Float): Float =
        if (strictHighEndEnabled()) {
            aspect.coerceIn(STRICT_MIN_ASPECT, STRICT_MAX_ASPECT)
        } else {
            aspect.coerceAtLeast(MIN_ASPECT)
        }

    internal data class RectBounds(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float
    ) {
        val width: Float
            get() = right - left

        val height: Float
            get() = bottom - top

        val centerX: Float
            get() = (left + right) * 0.5f

        val centerY: Float
            get() = (top + bottom) * 0.5f
    }

    fun fitBounds(viewWidth: Float, viewHeight: Float, targetAspect: Float): RectBounds {
        val safeWidth = safeEdge(viewWidth)
        val safeHeight = safeEdge(viewHeight)
        val safeTargetAspect = safeAspect(targetAspect)
        val boundsAspect = safeWidth / safeHeight
        return if (safeTargetAspect > boundsAspect) {
            val contentHeight = safeWidth / safeTargetAspect
            val top = (safeHeight - contentHeight) * 0.5f
            RectBounds(0f, top, safeWidth, top + contentHeight)
        } else {
            val contentWidth = safeHeight * safeTargetAspect
            val left = (safeWidth - contentWidth) * 0.5f
            RectBounds(left, 0f, left + contentWidth, safeHeight)
        }
    }

    fun fillBounds(viewWidth: Float, viewHeight: Float, targetAspect: Float): RectBounds {
        val safeWidth = safeEdge(viewWidth)
        val safeHeight = safeEdge(viewHeight)
        val safeTargetAspect = safeAspect(targetAspect)
        val boundsAspect = safeWidth / safeHeight
        return if (safeTargetAspect > boundsAspect) {
            val contentWidth = safeHeight * safeTargetAspect
            val left = (safeWidth - contentWidth) * 0.5f
            RectBounds(left, 0f, left + contentWidth, safeHeight)
        } else {
            val contentHeight = safeWidth / safeTargetAspect
            val top = (safeHeight - contentHeight) * 0.5f
            RectBounds(0f, top, safeWidth, top + contentHeight)
        }
    }

    fun buildDestinationCornerMapping(
        contentBounds: RectBounds,
        rotationDegrees: Int,
        mirrored: Boolean
    ): FloatArray {
        val topLeft = floatArrayOf(contentBounds.left, contentBounds.top)
        val topRight = floatArrayOf(contentBounds.right, contentBounds.top)
        val bottomLeft = floatArrayOf(contentBounds.left, contentBounds.bottom)
        val bottomRight = floatArrayOf(contentBounds.right, contentBounds.bottom)

        val mapped = when (OrientationManager.normalizeDegrees(rotationDegrees)) {
            0 -> arrayOf(topLeft, topRight, bottomLeft, bottomRight)
            90 -> arrayOf(bottomLeft, topLeft, bottomRight, topRight)
            180 -> arrayOf(bottomRight, bottomLeft, topRight, topLeft)
            270 -> arrayOf(topRight, bottomRight, topLeft, bottomLeft)
            else -> arrayOf(topLeft, topRight, bottomLeft, bottomRight)
        }

        if (mirrored) {
            for (corner in mapped) {
                corner[0] = contentBounds.left + contentBounds.right - corner[0]
            }
        }

        return floatArrayOf(
            mapped[0][0], mapped[0][1],
            mapped[1][0], mapped[1][1],
            mapped[2][0], mapped[2][1],
            mapped[3][0], mapped[3][1]
        )
    }

    fun buildSourceCornerMapping(
        viewWidth: Float,
        viewHeight: Float,
        sourceAspect: Float,
        targetAspect: Float,
        fillViewport: Boolean,
        sourceCropRegion: NormalizedCropRegion? = null
    ): FloatArray {
        val safeViewWidth = safeEdge(viewWidth)
        val safeViewHeight = safeEdge(viewHeight)
        val safeSourceAspect = safeAspect(sourceAspect)
        val safeTargetAspect = safeAspect(targetAspect)
        
        val sourceBounds = if (fillViewport) {
            RectBounds(0f, 0f, safeViewWidth, safeViewHeight)
        } else {
            fitBounds(safeViewWidth, safeViewHeight, safeSourceAspect)
        }
        
        val crop = sourceCropRegion?.toNormalizedCrop()
            ?: SensorCropEngine.centerCropDisplaySpace(
                sourceRatio = safeSourceAspect,
                targetRatio = safeTargetAspect
            )
        
        // Map the crop region into the resolved source bounds
        val sourceLeft = (sourceBounds.left + sourceBounds.width * crop.left).coerceIn(0f, safeViewWidth)
        val sourceTop = (sourceBounds.top + sourceBounds.height * crop.top).coerceIn(0f, safeViewHeight)
        val sourceRight = (sourceBounds.left + sourceBounds.width * crop.right).coerceIn(sourceLeft, safeViewWidth)
        val sourceBottom = (sourceBounds.top + sourceBounds.height * crop.bottom).coerceIn(sourceTop, safeViewHeight)

        return floatArrayOf(
            sourceLeft, sourceTop,
            sourceRight, sourceTop,
            sourceLeft, sourceBottom,
            sourceRight, sourceBottom
        )
    }

    private fun NormalizedCropRegion.toNormalizedCrop(): SensorCropEngine.NormalizedCrop {
        val safeLeft = left.coerceIn(0f, 1f)
        val safeTop = top.coerceIn(0f, 1f)
        val safeRight = right.coerceIn(safeLeft, 1f)
        val safeBottom = bottom.coerceIn(safeTop, 1f)
        return SensorCropEngine.NormalizedCrop(
            left = safeLeft,
            top = safeTop,
            right = safeRight,
            bottom = safeBottom
        )
    }
}
