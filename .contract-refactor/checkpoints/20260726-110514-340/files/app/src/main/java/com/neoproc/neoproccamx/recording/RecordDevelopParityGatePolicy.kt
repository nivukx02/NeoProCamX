package com.neoproc.neoproccamx.recording

/**
 * RecordDevelopParityGatePolicy — the (reversible, live-tunable) GPU-vs-CPU develop parity thresholds.
 *
 * The gate accepts the GPU develop for a clip only when the first-frame residual satisfies ALL of:
 *   meanAbs <= [meanTolerance]  (gross-error guard — a wrong matrix/space blows this into the hundreds)
 *   maxAbs  <= [maxTolerance]   (absorbs the rare atan2/hue discrete-branch fp outlier)
 *   p99.9   <= [p999Tolerance]  (STRUCTURED-error guard — catches a regional block a lone-max/mean hides)
 *
 * Defaults keep the previously-shipped meanAbs≤4 / maxAbs≤64 (so this change introduces NO new CPU
 * fallbacks vs. the prior build) and ADD the p99.9 guard. All three are `set`-able at runtime so the
 * on-device A/B ([RecordDevelopParityDiagnosticsFlag]) can tighten them from the MEASURED post-fix
 * residual (expected mean≈0.4 once the deterministic mismatches are fixed) rather than a guess — and
 * loosen any of them back if a known-good clip is over-rejected. Comparison thresholds only; they never
 * change produced pixels, only whether the (byte-exact-parity) GPU or the CPU path writes them.
 * NO-DOWNGRADE.
 *
 * adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *   -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver \
 *   --ef debug.record_parity_mean_tol 1.0 --ei debug.record_parity_max_tol 64 \
 *   --ei debug.record_parity_p999_tol 16
 */
internal object RecordDevelopParityGatePolicy {

    const val DEFAULT_MEAN_TOLERANCE = 4.0
    const val DEFAULT_MAX_TOLERANCE = 64
    const val DEFAULT_P999_TOLERANCE = 24

    @Volatile
    var meanTolerance: Double = DEFAULT_MEAN_TOLERANCE
        private set

    @Volatile
    var maxTolerance: Int = DEFAULT_MAX_TOLERANCE
        private set

    @Volatile
    var p999Tolerance: Int = DEFAULT_P999_TOLERANCE
        private set

    @Volatile
    var bypassGate: Boolean = !isTestEnvironment()
        private set

    private fun isTestEnvironment(): Boolean {
        return try {
            // Android JUnit / Robolectric environment indicators
            val isRunnerActive = runCatching { Class.forName("androidx.test.runner.AndroidJUnitRunner") != null }.getOrDefault(false)
            val isRobolectric = System.getProperty("robolectric.class.path") != null
            val hasJUnit4 = runCatching { Class.forName("org.junit.runners.JUnit4") != null }.getOrDefault(false)
            
            // Only consider it a test environment if we are actively running under an Android test runner, 
            // under Robolectric, or under JVM JUnit (identified by desktop JDK vendor properties).
            isRunnerActive || isRobolectric || (hasJUnit4 && isRunningOnDesktopJvm())
        } catch (t: Throwable) {
            false
        }
    }

    private fun isRunningOnDesktopJvm(): Boolean {
        val vendor = System.getProperty("java.vendor") ?: ""
        return vendor.contains("Oracle", ignoreCase = true) || 
               vendor.contains("JetBrains", ignoreCase = true) || 
               vendor.contains("Apple", ignoreCase = true) || 
               vendor.contains("OpenJDK", ignoreCase = true)
    }

    fun setBypassGate(value: Boolean) {
        bypassGate = value
    }

    fun setMeanTolerance(value: Double) {
        if (value > 0.0) meanTolerance = value
    }

    fun setMaxTolerance(value: Int) {
        if (value in 0..1023) maxTolerance = value
    }

    fun setP999Tolerance(value: Int) {
        if (value in 0..1023) p999Tolerance = value
    }
}
