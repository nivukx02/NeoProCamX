package com.neoproc.neoproccamx.ui.overlays

import android.graphics.RectF
import kotlin.math.max
import java.util.Locale

internal data class OverlayLine(
    val startX: Float,
    val startY: Float,
    val endX: Float,
    val endY: Float
)

internal data class OverlayRectBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

internal object OverlayDiagnosticsGeometry {
    fun gridLines(
        mode: GridMode,
        rect: RectF
    ): List<OverlayLine> =
        gridLines(mode, rect.left, rect.top, rect.right, rect.bottom)

    fun gridLines(
        mode: GridMode,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ): List<OverlayLine> =
        when (mode) {
            GridMode.NONE -> emptyList()
            GridMode.THIRDS -> {
                val width = right - left
                val height = bottom - top
                val thirdW = width / 3f
                val thirdH = height / 3f
                listOf(
                    OverlayLine(left + thirdW, top, left + thirdW, bottom),
                    OverlayLine(left + thirdW * 2f, top, left + thirdW * 2f, bottom),
                    OverlayLine(left, top + thirdH, right, top + thirdH),
                    OverlayLine(left, top + thirdH * 2f, right, top + thirdH * 2f)
                )
            }

            GridMode.CENTER -> listOf(
                OverlayLine((left + right) / 2f, top, (left + right) / 2f, bottom),
                OverlayLine(left, (top + bottom) / 2f, right, (top + bottom) / 2f)
            )
        }

    fun mapNormalizedRect(
        src: RectF,
        container: RectF
    ): RectF {
        val mapped = mapNormalizedRect(
            srcLeft = src.left,
            srcTop = src.top,
            srcRight = src.right,
            srcBottom = src.bottom,
            containerLeft = container.left,
            containerTop = container.top,
            containerRight = container.right,
            containerBottom = container.bottom
        )
        return RectF(mapped.left, mapped.top, mapped.right, mapped.bottom)
    }

    fun diagnosticMaskCorners(
        container: RectF,
        rotationDegrees: Int,
        mirrored: Boolean
    ): FloatArray =
        diagnosticMaskCorners(
            left = container.left,
            top = container.top,
            right = container.right,
            bottom = container.bottom,
            rotationDegrees = rotationDegrees,
            mirrored = mirrored
        )

    fun diagnosticMaskCorners(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        rotationDegrees: Int,
        mirrored: Boolean
    ): FloatArray {
        val containerWidth = right - left
        val containerHeight = bottom - top
        val corners = floatArrayOf(
            0f, 0f,
            1f, 0f,
            1f, 1f,
            0f, 1f
        )
        val result = FloatArray(corners.size)
        for (index in corners.indices step 2) {
            val x = corners[index]
            val y = corners[index + 1]
            val rotated = rotateNormalizedPoint(x, y, rotationDegrees)
            val finalX = if (mirrored) 1f - rotated.first else rotated.first
            result[index] = left + finalX * containerWidth
            result[index + 1] = top + rotated.second * containerHeight
        }
        return result
    }

    fun mapNormalizedRect(
        srcLeft: Float,
        srcTop: Float,
        srcRight: Float,
        srcBottom: Float,
        containerLeft: Float,
        containerTop: Float,
        containerRight: Float,
        containerBottom: Float
    ): OverlayRectBounds {
        val containerWidth = containerRight - containerLeft
        val containerHeight = containerBottom - containerTop
        return OverlayRectBounds(
            left = containerLeft + srcLeft.coerceIn(0f, 1f) * containerWidth,
            top = containerTop + srcTop.coerceIn(0f, 1f) * containerHeight,
            right = containerLeft + srcRight.coerceIn(0f, 1f) * containerWidth,
            bottom = containerTop + srcBottom.coerceIn(0f, 1f) * containerHeight
        )
    }

    fun formatDuration(durationMs: Long): String {
        val totalSeconds = max(0L, durationMs / 1000L)
        val seconds = totalSeconds % 60
        val minutes = (totalSeconds / 60) % 60
        val hours = totalSeconds / 3600
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    private fun rotateNormalizedPoint(
        x: Float,
        y: Float,
        rotationDegrees: Int
    ): Pair<Float, Float> {
        return when (((rotationDegrees % 360) + 360) % 360) {
            90 -> 1f - y to x
            180 -> 1f - x to 1f - y
            270 -> y to 1f - x
            else -> x to y
        }
    }
}
