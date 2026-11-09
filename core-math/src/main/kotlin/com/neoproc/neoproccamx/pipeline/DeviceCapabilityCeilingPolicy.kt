package com.neoproc.neoproccamx.pipeline

/**
 * DeviceCapabilityCeilingPolicy — maps a device's coarse hardware class (total RAM + CPU cores) to
 * the highest [HighEndRuntimeTier] it can sustain, which the app injects into
 * [com.neoproc.neoproccamx.core.HighEndQualityPolicy.deviceCapabilityCeiling] once at startup.
 *
 * Pure and Context-free so it is fully unit-testable; the app supplies the Context-derived RAM/core
 * numbers. The ladder mirrors the AI performance tiering (AIPerformanceOptimizer.DeviceCapabilityTier:
 * 8 GB/8-core, 6 GB/6-core, 4 GB/4-core). Both a high RAM AND a high core count are required to climb
 * a rung, so a lopsided device is placed at the lower sustainable tier. Because the policy default is
 * FLAGSHIP (no cap), this only ever RELAXES a genuinely weaker device — it never downscales a capable
 * one ("scale to all mobile, zero quality compromise = native-sensor max per device").
 */
object DeviceCapabilityCeilingPolicy {

    fun resolve(totalMemMb: Long, cpuCores: Int): HighEndRuntimeTier = when {
        totalMemMb >= 8192 && cpuCores >= 8 -> HighEndRuntimeTier.FLAGSHIP // flagship: unchanged (no cap)
        totalMemMb >= 6144 && cpuCores >= 6 -> HighEndRuntimeTier.ULTRA    // high-end: full 4K/10-bit
        totalMemMb >= 4096 && cpuCores >= 4 -> HighEndRuntimeTier.CINEMA   // mid: still 4K-capable
        else -> HighEndRuntimeTier.STANDARD                                 // entry: native-sensor max, GLES
    }
}
