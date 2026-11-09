package com.neoproc.neoproccamx.ui

import java.util.Locale

internal data class CameraSaveWatermarkStyle(
    val text: String,
    val surfaceColor: Int,
    val strokeColor: Int,
    val textColor: Int,
    val cornerRadiusDp: Float,
    val visibleDurationMs: Long
)

internal object CameraSaveWatermarkStylePolicy {
    fun resolve(message: String): CameraSaveWatermarkStyle =
        CameraSaveWatermarkStyle(
            text = message.trim().ifEmpty { "Video saved" }.uppercase(Locale.US),
            surfaceColor = 0xF2050505.toInt(),
            strokeColor = 0x33FFFFFF,
            textColor = 0xFFB46CFF.toInt(),
            cornerRadiusDp = 22f,
            visibleDurationMs = 1300L
        )
}
