package com.neoproc.neoproccamx.rendering

/**
 * OffscreenEncoderBootProbeSkipFlag — reversible gate (default ON) that skips the boot-time "Decisive
 * real-surface" 10-bit encoder diagnostic in [VulkanRuntimeManager.initialize] when the offscreen
 * AHB->EGL HEVC Main10 HLG encoder has ALREADY been proven unbindable on THIS exact build+OS fingerprint.
 *
 * The probe creates a throwaway MediaCodec HEVC Main10 HLG encoder (createByCodecName + configure +
 * createInputSurface), binds the GPU to that real encoder input surface, logs the outcome, then restores
 * `setEncoderSurface(null)` and releases the codec. It feeds NO routing: the recording feed-path selector
 * consults `offscreenEncoderProvenUnavailable` (recalled from PreferencesManager BEFORE initialize()),
 * never the probe log. On a device whose prior session already persisted a genuine GPU hardware wall for
 * this fingerprint, re-running that codec+EGL churn on every boot is wasted startup cost.
 *
 * [shouldSkipBootProbe] returns true ONLY when the flag is enabled AND the wall is already proven for the
 * current fingerprint. A new app build, an OS/GPU-driver OTA, or a first launch lapses the fingerprint
 * recall (PreferencesManager scopes it to VERSION_CODE:Build.FINGERPRINT), so the field is false and the
 * probe runs exactly once to re-discover the path honestly.
 *
 * NO-DOWNGRADE: the probe never touched recorded pixels/fps/bit-depth/routing — it only wrote an info log
 * and always restored the unbound surface — so skipping it on an already-proven-dead device removes
 * redundant boot work with no quality/stability regression. With the flag OFF the probe runs on every
 * boot exactly as today (byte-identical). Read once inside initialize() (takes effect next process start):
 *   adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *       -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.offscreen_encoder_boot_probe_skip false
 */
object OffscreenEncoderBootProbeSkipFlag {

    @Volatile
    var enabled: Boolean = true
        private set

    fun set(value: Boolean) {
        enabled = value
    }

    /** Pure decision helper (unit-testable): skip the boot probe only when enabled AND already proven dead. */
    fun shouldSkipBootProbe(provenUnavailable: Boolean): Boolean = enabled && provenUnavailable
}
