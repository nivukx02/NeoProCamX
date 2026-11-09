package com.neoproc.neoproccamx.rendering

/**
 * ════════════════════════════════════════════════════════════════════════════
 *  GL VISIBLE PREVIEW POLICY  (additive per-mode preview grade)
 * ════════════════════════════════════════════════════════════════════════════
 *
 * Gate for the additive [PreviewGlCompositor] — a SurfaceTexture→GL→SurfaceView
 * stage inserted ONLY in front of the visible preview so a per-mode monitor look
 * can be shown live (LOG preview looks like LOG, RAW preview looks like a viewable
 * monitor image) WITHOUT touching the recorded file or the proven record pipeline.
 *
 * Why this exists — and why it is deliberately narrow
 * ───────────────────────────────────────────────────
 * Preview and record share ONE Camera2 repeating request, so a per-mode look
 * cannot be injected through Camera2 tonemap keys without also regrading the
 * recorded RAW/LOG stream. The only non-destructive place to shape the *preview*
 * pixels is an isolated GL stage on the preview surface. This compositor runs in
 * its OWN unshared EGL context, at 8-bit SDR, at the camera stream size, and never
 * binds the encoder surface — so it cannot re-arm the vivo Vulkan-encoder ANR wall
 * and cannot perturb the 30 fps offscreen-EGL record path.
 *
 * SAFETY SCOPE (the load-bearing rule)
 * ────────────────────────────────────
 * The compositor engages only for 8-bit SDR-range preview modes that need a monitor transform — LOG
 * (NEOLOG) and RAW (RAW_MONITOR). Normal SDR/PRO stays FULLY BYPASSED (camera → SurfaceView direct)
 * because the direct SurfaceView path is the least fragile visible-preview owner during recording prearm.
 * It also stays fully bypassed for:
 *   • NDR / HDR (HLG/PQ) — those need the 10-bit direct DynamicRangeProfile path; an
 *     8-bit GL stage would DOWNGRADE them to SDR. Never composite HDR.
 *   • The native Vulkan visible-preview path ([VulkanVisiblePreviewPolicy]) — mutually
 *     exclusive; if that ever activates, this must not.
 *
 * Runtime-flippable for on-device A/B and instant revert (debug builds):
 *
 *   adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *       -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.gl_preview false
 *
 * Default ON (genuine NeoLog shader): flip OFF at runtime via the ADB command above for instant revert.
 */
internal object GlVisiblePreviewPolicy {

    /** The per-mode monitor transform the compositor applies. */
    enum class PreviewGrade {
        /** No GL transform — caller should keep the direct camera→SurfaceView path. */
        IDENTITY,

        /** Re-encode the display image through the N-Log transfer so preview looks like LOG. */
        NEOLOG,

        /** Lift the flat/ISP-stripped RAW preview to a viewable monitor image. */
        RAW_MONITOR,

        /**
         * Normal SDR VIDEO / PRO: NO tone/colour change (the direct camera image is already the
         * intended look), but run the compositor's edge-aware light-denoise + halo-controlled
         * unsharp so the LIVE preview shows the app's clean-and-crisp result instead of the raw,
         * un-processed ISP surface. This is the preview-side counterpart to the offscreen-EGL
         * record enhance — the app's denoise/sharpen finally reaches preview pixels. Still 8-bit
         * SDR, still the isolated compositor: no HDR downgrade, no Vulkan-preview ANR wall.
         */
        SDR_ENHANCE
    }

    // Default ON. The compositor is the ONLY non-destructive way to make LOG preview read as LOG and
    // RAW preview read as a viewable monitor image on this device: preview + record share ONE Camera2
    // repeating request, and the vivo ISP ignores a custom LOG tonemap curve (see NeoLogHalTonemapPolicy),
    // so the per-mode look cannot be injected through Camera2 without regrading the recorded stream.
    // The compositor now runs the GENUINE NeoLogCurve N-LOG v5 transfer — NOT the earlier invented
    // pow()/scale grade that produced a pink/red centre cast — on an isolated 8-bit EGL context that
    // never binds the encoder surface (so it cannot perturb the proven 25fps record path). Normal
    // SDR/PRO and HDR/NDR are hard-bypassed. Instant on-device revert if a device misbehaves:
    //   adb ... --ez debug.gl_preview false
    @Volatile
    var enabled: Boolean = true
        private set

    @Volatile
    private var onChanged: (() -> Unit)? = null

    /** Sets the gate and, if it changed, notifies the listener to re-bind the preview surface. */
    fun set(value: Boolean) {
        val changed = enabled != value
        enabled = value
        if (changed) onChanged?.invoke()
    }

    /** Registered by the ViewModel to re-evaluate preview binding when the gate flips. */
    fun registerOnChanged(callback: (() -> Unit)?) {
        onChanged = callback
    }

    /**
     * Resolve the preview grade for the current mode. Pure — the single source of truth for
     * "which look, if any, the GL preview should apply". [PreviewGrade.IDENTITY] means DO NOT
     * take the GL path (stay direct).
     *
     * @param logPreviewActive   LOG video preview is active (not recording-only).
     * @param rawPreviewActive   RAW video preview is active.
     * @param hdrActive          any NDR/HDR (HLG/PQ) dynamic range is active — forces bypass.
     * @param nativeVulkanPreviewActive the native Vulkan compositor owns the surface — forces bypass.
     */
    fun resolveGrade(
        gateEnabled: Boolean,
        logPreviewActive: Boolean,
        rawPreviewActive: Boolean,
        hdrActive: Boolean,
        nativeVulkanPreviewActive: Boolean,
        softwareLutOwnerRequired: Boolean = false
    ): PreviewGrade {
        if (!gateEnabled && !softwareLutOwnerRequired) return PreviewGrade.IDENTITY
        // NDR/HDR (HLG/PQ) MUST stay on the 10-bit direct path — an 8-bit GL stage would downgrade
        // it. This guard is load-bearing and is deliberately left untouched.
        if (hdrActive) return PreviewGrade.IDENTITY
        if (nativeVulkanPreviewActive && !softwareLutOwnerRequired) return PreviewGrade.IDENTITY
        if (softwareLutOwnerRequired) return PreviewGrade.SDR_ENHANCE
        return when {
            rawPreviewActive -> PreviewGrade.RAW_MONITOR
            logPreviewActive -> PreviewGrade.NEOLOG
            else -> PreviewGrade.SDR_ENHANCE
        }
    }

    /** True when the GL compositor should sit in front of the preview (a non-identity grade applies). */
    fun shouldComposite(
        gateEnabled: Boolean,
        logPreviewActive: Boolean,
        rawPreviewActive: Boolean,
        hdrActive: Boolean,
        nativeVulkanPreviewActive: Boolean,
        softwareLutOwnerRequired: Boolean = false
    ): Boolean =
        resolveGrade(
            gateEnabled = gateEnabled,
            logPreviewActive = logPreviewActive,
            rawPreviewActive = rawPreviewActive,
            hdrActive = hdrActive,
            nativeVulkanPreviewActive = nativeVulkanPreviewActive,
            softwareLutOwnerRequired = softwareLutOwnerRequired
        ) != PreviewGrade.IDENTITY
}
