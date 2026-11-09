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
 * [GlVisiblePreviewPolicy] is an SDR stage and deliberately hard-bypasses HDR. Genuine PQ WYSIWYG
 * requires a verified 10-bit RGBA1010102 EGL surface tagged BT.2020-PQ. Current device evidence shows
 * that strict surface initializing successfully, so stale device-specific assumptions must not force
 * the historical SDR monitor branch. The project-owned sensor-linear path fails closed: disabling it
 * or failing strict EGL setup must not expose a direct Camera2 frame as an NDR preview:
 *
 *   ENABLE  : adb shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND \
 *               -n com.neoproc.neoproccamx/.ui.DebugCommandReceiver --ez debug.pq_preview true
 *   VERIFY  : ... --ez debug.pq_preview false   (request is rejected; NDR remains project-owned)
 *
 * Mutually exclusive with the native Vulkan visible preview and with the 8-bit
 * [GlVisiblePreviewPolicy] compositor: only one stage may own the preview surface.
 */
internal object Hdr10PqPreviewPolicy {

    private const val FAIL_CLOSED_PROJECT_NDR_PREVIEW = true

    // Default ON: project-owned RAW-derived NDR is the only valid visible NDR authority.
    @Volatile
    var enabled: Boolean = true
        private set

    @Volatile
    private var onChanged: (() -> Unit)? = null

    /**
     * Apply a historical debug-gate request.
     *
     * A false request is deliberately rejected because the only former fallback was a direct
     * Camera2 HLG monitor frame, which is not the project-owned sensor-linear NDR output.
     */
    fun set(value: Boolean) {
        val effective = value || FAIL_CLOSED_PROJECT_NDR_PREVIEW
        val changed = enabled != effective
        enabled = effective
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
