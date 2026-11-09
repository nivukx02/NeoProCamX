package com.neoproc.neoproccamx.camera

/**
 * Policy for determining when to rebuild camera sessions
 */
class CameraSessionRebuildPolicy {

    /** Minimum time between session rebuilds (ms) */
    var minRebuildIntervalMs: Long = 5000

    /** Conditions that trigger session rebuild */
    var rebuildOnConfigChange: Boolean = true
    var rebuildOnThermalThrottle: Boolean = true
    var rebuildOnMemoryPressure: Boolean = true
    var rebuildOnFrameDrop: Boolean = true

    /**
     * Determines if session should be rebuilt
     */
    fun shouldRebuildSession(
        lastRebuildTimeMs: Long,
        configChanged: Boolean,
        thermalThrottling: Boolean,
        memoryPressure: Boolean,
        frameDropRate: Float
    ): Boolean {
        val timeSinceLastRebuild = System.currentTimeMillis() - lastRebuildTimeMs

        return timeSinceLastRebuild >= minRebuildIntervalMs && (
            (rebuildOnConfigChange && configChanged) ||
            (rebuildOnThermalThrottle && thermalThrottling) ||
            (rebuildOnMemoryPressure && memoryPressure) ||
            (rebuildOnFrameDrop && frameDropRate > 0.1f)
        )
    }
}
