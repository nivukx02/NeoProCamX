package com.neoproc.neoproccamx.pipeline

data class FrameQualityMode(
    val label: String,
    val scaleFactor: Float,
    val maxFrameRate: Int,
    val skipThreshold: Float
) {
    fun scaledWidth(baseWidth: Int): Int = (baseWidth * scaleFactor).toInt().coerceAtLeast(480)
    fun scaledHeight(baseHeight: Int): Int = (baseHeight * scaleFactor).toInt().coerceAtLeast(270)
}

object FrameQualityModes {
    val ULTRA = FrameQualityMode("Ultra", 1.0f, 60, 0.2f)
    val HIGH = FrameQualityMode("High", 0.9f, 60, 0.3f)
    val BALANCED = FrameQualityMode("Balanced", 0.75f, 30, 0.4f)
    val PERFORMANCE = FrameQualityMode("Performance", 0.5f, 30, 0.5f)
    val POWER_SAVER = FrameQualityMode("Power Saver", 0.333f, 24, 0.6f)
}

class AdaptiveFrameQualityManager {
    private var currentMode: FrameQualityMode = FrameQualityModes.BALANCED
    private var processingTimeMs = 0L
    private val smoothingAlpha = 0.2f

    data class AdaptationSignal(
        val stageLagMs: Long,
        val cpuUtilization: Float,
        val memoryUtilization: Float,
        val droppedFrames: Int
    )

    fun updateAdaptation(signal: AdaptationSignal): FrameQualityMode {
        val overloadFactor = calculateOverloadFactor(signal)
        
        val newMode = when {
            overloadFactor > 0.8f -> FrameQualityModes.POWER_SAVER
            overloadFactor > 0.65f -> FrameQualityModes.PERFORMANCE
            overloadFactor > 0.5f -> FrameQualityModes.BALANCED
            overloadFactor > 0.25f -> FrameQualityModes.HIGH
            else -> FrameQualityModes.ULTRA
        }
        
        if (newMode.label != currentMode.label) {
            currentMode = newMode
        }
        return currentMode
    }

    private fun calculateOverloadFactor(signal: AdaptationSignal): Float {
        val stageLagFactor = (signal.stageLagMs.toFloat() / 50f).coerceIn(0f, 1f)
        val cpuFactor = signal.cpuUtilization.coerceIn(0f, 1f)
        val memoryFactor = signal.memoryUtilization.coerceIn(0f, 1f)
        val droppedFramesFactor = (signal.droppedFrames.toFloat() / 10f).coerceIn(0f, 1f)
        
        return (stageLagFactor * 0.4f + cpuFactor * 0.3f + memoryFactor * 0.2f + droppedFramesFactor * 0.1f)
    }

    fun getCurrentMode(): FrameQualityMode = currentMode

    fun shouldSkipFrame(droppedFrameCount: Int): Boolean =
        droppedFrameCount.toFloat() / 100f > currentMode.skipThreshold
}
