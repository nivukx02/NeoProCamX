package com.neoproc.neoproccamx.rendering

/**
 * EglDecodeOnceFlag — device-gated (default ON) two-pass decode-once optimisation for the live
 * offscreen-EGL record shader.
 *
 * The record fragment shader samples the external (YUV) camera texture multiple times per pixel for its
 * edge-aware bilateral denoise + unsharp — each sample can trigger external-texture conversion work.
 * With this ON the native path adds a first FBO pass that decodes the external texture ONCE into an
 * RGB10_A2 texture at source resolution; the edge-aware stage then samples that 10-bit texture
 * (sampler2D) with cheap fetches. It is a device-gated A/B path, not a quality feature.
 *
 * Vivo/Qualcomm 4096x3072@25 NDR10+ measurement: the original RGBA16F full-frame pass and history
 * bandwidth were slower than the single-pass external shader, causing strict 25fps failure. The current
 * native implementation uses RGB10_A2 to preserve the 10-bit source/output contract with less bandwidth.
 * With EGLImage caching disabled it recorded 569 Main10 samples against the strict 528-sample gate, so
 * this is the default high-end path for this target rather than an opt-in debug path.
 *
 * BIT-IDENTITY GUARD (native): decode-once only engages when source-res == surface-res, so the RGB10_A2
 * sample lands on texel centres (bilinear does no blending). Scaled modes fall back to the shipped
 * external path. RGB10_A2 holds the 10-bit-normalised [0,1] source values; shader math stays highp fp32.
 *
 * DEFAULT ON for this measured target. NOT a battery optimisation. On-device A/B:
 *   adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *       -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.egl_decode_once true
 * Pushed to the native bridge at setEncoderSurface, so a flip takes effect on the next recording.
 */
object EglDecodeOnceFlag {

    @Volatile
    var enabled: Boolean = true
        private set

    fun set(value: Boolean) {
        enabled = value
    }
}
