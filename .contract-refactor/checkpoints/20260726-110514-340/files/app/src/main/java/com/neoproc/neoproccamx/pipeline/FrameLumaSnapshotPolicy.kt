package com.neoproc.neoproccamx.pipeline

internal data class LumaSnapshotDimensions(
    val width: Int,
    val height: Int
)

internal object FrameLumaSnapshotPolicy {
    fun resolveDimensions(
        sourceWidth: Int,
        sourceHeight: Int,
        targetLongEdge: Int
    ): LumaSnapshotDimensions {
        val width = sourceWidth.coerceAtLeast(1)
        val height = sourceHeight.coerceAtLeast(1)
        val longEdge = maxOf(width, height)
        val boundedTarget = targetLongEdge.coerceAtLeast(1)
        if (longEdge <= boundedTarget) {
            return LumaSnapshotDimensions(width, height)
        }
        val scale = boundedTarget.toFloat() / longEdge.toFloat()
        return LumaSnapshotDimensions(
            width = (width * scale).toInt().coerceAtLeast(1),
            height = (height * scale).toInt().coerceAtLeast(1)
        )
    }
}
