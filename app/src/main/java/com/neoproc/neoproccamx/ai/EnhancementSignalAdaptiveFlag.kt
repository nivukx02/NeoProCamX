package com.neoproc.neoproccamx.ai

/**
 * s7-f09 / CP99 — process-global gate for signal-adaptive enhancement (noise-driven sharpen
 * back-off + explicit unsharp overshoot clamp).
 *
 * DEFAULT ON (user-directed activation 2026-07-04, device-verified; enabling only makes sharpening
 * MORE conservative — noise-driven back-off + overshoot clamp — so it reduces halos, never adds them).
 * While [enabled] is false, [EnhancementEngine] applies the existing sharpen /
 * micro-contrast strengths and the existing halo-attenuation verbatim — bit-for-bit today's output.
 * The denoise-path HONESTY metadata is always populated regardless of this flag (it changes no
 * pixels). When flipped on (device-gated), sharpen/micro-contrast scale down with the measured noise
 * floor and the unsharp overshoot is hard-clamped.
 */
internal object EnhancementSignalAdaptiveFlag {
    @Volatile
    var enabled: Boolean = true
        private set

    fun set(value: Boolean) {
        enabled = value
    }
}
