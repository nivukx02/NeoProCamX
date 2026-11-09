package com.neoproc.neoproccamx.rendering

/**
 * Device-qualification selector for the quality-equivalent SDR cardinal-sampling shader.
 *
 * Both paths execute the same denoise, detail, colour, LUT and temporal equations. `true` selects
 * the GLES 3.1 texture-gather specialization when the driver supports it; `false` selects the
 * canonical GLES 3.0 textureOffset implementation. Keeping the selector outside the per-frame path
 * allows repeatable A/B measurements without rebuilding the APK or changing recorded pixels.
 *
 * Default OFF: measured Adreno qualification at 4096x3072/25 showed the canonical path completing
 * the grade about 8.9% faster (10.957 ms versus 12.030 ms) under matching thermal 2->3 runs. The
 * optional gather path remains available for GPUs on which an equivalent controlled test proves it.
 */
object EglGatherGridFlag {

    @Volatile
    var enabled: Boolean = false
        private set

    fun set(value: Boolean) {
        enabled = value
    }
}
