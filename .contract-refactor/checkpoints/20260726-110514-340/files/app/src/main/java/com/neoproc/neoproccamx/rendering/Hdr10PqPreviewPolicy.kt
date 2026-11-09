package com.neoproc.neoproccamx.rendering

/**
 * ════════════════════════════════════════════════════════════════════════════
 *  HDR10 PQ PREVIEW POLICY  (WYSIWYG live preview for the genuine NDR/NIVUKX look)
 * ════════════════════════════════════════════════════════════════════════════
 *
 * Gate for the 10-bit BT.2020-PQ preview compositor ([Hdr10PqPreviewCompositor]).
 * When engaged it shows the SAME nit-accurate NDR10+/NIVUKX PQ look that the
 * record path bakes into the file (neo_p010_export_enhance.comp / the host-golden
 * NdrNivukxDeliveryEngine), so the live preview matches the recording (WYSIWYG)
 * instead of showing the ungraded camera HLG10 image.
 *
 * Why this is a separate gate (not folded into [GlVisiblePreviewPolicy])
 * ──────────────────────────────────────────────────────────────────────────────
 * [GlVisiblePreviewPolicy] is an 8-bit SDR stage and deliberately hard-bypasses HDR
 * (an 8-bit hop would DOWNGRADE HLG/PQ to SDR). Genuine PQ WYSIWYG needs a 10-bit
 * (RGBA1010102) EGL surface tagged BT.2020-PQ. On this device a 10-bit-PQ EGL
 * preview surface can re-arm the vivo 10-bit-EGL / Vulkan-encoder ANR hazards. The default-on
 * compositor therefore uses the proven SDR monitor-output surface while leaving the recorded NDR
 * stream untouched, and remains instantly revertible on-device:
 *
 *   ENABLE  : adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *               -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.pq_preview true
 *   REVERT  : ... --ez debug.pq_preview false   (falls back to the direct HLG10 preview)
 *
 * Mutually exclusive with the native Vulkan visible preview and with the 8-bit
 * [GlVisiblePreviewPolicy] compositor: only one stage may own the preview surface.
 */
internal object Hdr10PqPreviewPolicy {

    // Default ON: the SDR monitor-output path is the device-proven fix for the direct preview's
    // clipped red/wide-gamut appearance. This changes only the display monitor; NDR recording
    // remains on its original 10-bit path.
    @Volatile
    var enabled: Boolean = true
        private set

    @Volatile
    private var onChanged: (() -> Unit)? = null

    /** Flip the gate; notify the listener to re-bind the preview surface if it changed. */
    fun set(value: Boolean) {
        val changed = enabled != value
        enabled = value
        if (changed) onChanged?.invoke()
    }

    /** Registered by the ViewModel to re-evaluate preview binding when the gate flips (ADB A/B). */
    fun registerOnChanged(callback: (() -> Unit)?) {
        onChanged = callback
    }

    /**
     * True when the 10-bit PQ preview compositor should own the preview surface:
     * the gate is on, a genuine NDR10+/NIVUKX PQ HDR mode is active, and neither
     * the native Vulkan preview nor the 8-bit GL compositor is engaged.
     *
     * @param gateEnabled              [enabled] snapshot.
     * @param ndrPqDeliveryActive      an NDR10+/NIVUKX PQ delivery mode is live (Phase 2 transport).
     * @param nativeVulkanPreviewActive the native Vulkan compositor owns the surface — forces bypass.
     * @param glSdrCompositorActive    the 8-bit SDR compositor is engaged (LOG/RAW) — forces bypass.
     */
    fun shouldComposite(
        gateEnabled: Boolean,
        ndrPqDeliveryActive: Boolean,
        nativeVulkanPreviewActive: Boolean,
        glSdrCompositorActive: Boolean
    ): Boolean =
        gateEnabled &&
            ndrPqDeliveryActive &&
            !nativeVulkanPreviewActive &&
            !glSdrCompositorActive
}
