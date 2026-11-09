package com.neoproc.neoproccamx.ui.controls

internal object ReferenceAdjustBarPolicy {
    const val lensRingStartAngleDegrees = 205f
    const val lensRingSweepDegrees = 130f

    fun progressFraction(progress: Int, max: Int): Float {
        if (max <= 0) return 0f
        return progress.coerceIn(0, max) / max.toFloat()
    }

    fun tickFractions(tickCount: Int): List<Float> {
        if (tickCount <= 0) return emptyList()
        if (tickCount == 1) return listOf(0.5f)
        return List(tickCount) { index -> index / (tickCount - 1).toFloat() }
    }

    fun lensRingAngleDegrees(progress: Int, max: Int): Float {
        val fraction = progressFraction(progress, max)
        return lensRingStartAngleDegrees + (lensRingSweepDegrees * fraction)
    }

    fun progressForTouchX(
        x: Float,
        width: Int,
        paddingStart: Int,
        paddingEnd: Int,
        max: Int
    ): Int {
        if (width <= paddingStart + paddingEnd || max <= 0) return 0
        val usableWidth = (width - paddingStart - paddingEnd).toFloat()
        val normalized = ((x - paddingStart) / usableWidth).coerceIn(0f, 1f)
        return (normalized * max).toInt().coerceIn(0, max)
    }
}
