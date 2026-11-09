package com.neoproc.neoproccamx.cinema

import com.neoproc.neoproccamx.camera.HdrDynamicRangeMode
import com.neoproc.neoproccamx.camera.HdrDynamicRangePolicy
import com.neoproc.neoproccamx.color.NeoTenBitHardwareSupport
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.StabilizationMode

/**
 * CinematicBundlePolicy — one-tap "Cinematic" bundle composition.
 *
 * Pure, deterministic, availability-aware resolver that turns a single
 * user intent ("engage the cinematic look") into the concrete set of
 * recording knobs — stabilization, bokeh, dynamic range, frame rate,
 * LOG transfer and 10-bit path — while gracefully degrading each member
 * that the current hardware cannot honor.
 *
 * NO-DOWNGRADE contract
 * ─────────────────────
 * When [resolve] is called with `enabled = false`, it returns the frozen
 * [CinematicBundleDecision.INERT] value: every member OFF, SDR, no fps
 * override and an empty dropped list. This reproduces today's behavior
 * bit-for-bit — the bundle only ever changes state when explicitly
 * engaged.
 *
 * Honest gates
 * ────────────
 * A member is engaged only when the supplied [CinematicBundleAvailability]
 * says the hardware supports it. Any member that cannot be honored is left
 * in its safe OFF/SDR state and its name is appended to
 * [CinematicBundleDecision.droppedMembers] so the caller can surface an
 * accurate "partially available" report — never a fabricated full engage.
 *
 * This object owns no Android dependencies and performs no I/O; the
 * availability snapshot is computed elsewhere (from live
 * CameraCharacteristics) and handed in.
 */
object CinematicBundlePolicy {

    /** Target cinematic frame rate — the universal 24 fps look. */
    const val CINEMATIC_TARGET_FPS: Int = 24

    /**
     * Hardware/pipeline availability snapshot the resolver composes against.
     * All types are the app's real capability types so the caller can pass
     * live detections directly with no lossy translation.
     *
     * @param eisAvailable   true when electronic stabilization can be engaged.
     * @param bokehAvailable true when a cinematic bokeh (portrait) engine is available.
     * @param hdrSupport     Camera2 dynamic-range capability snapshot.
     * @param tenBitSupport  native 10-bit transport capability snapshot.
     */
    data class CinematicBundleAvailability(
        val eisAvailable: Boolean,
        val bokehAvailable: Boolean,
        val hdrSupport: HdrDynamicRangePolicy.Support,
        val tenBitSupport: NeoTenBitHardwareSupport
    )

    /**
     * Fully-resolved bundle decision.
     *
     * @param stabilization  stabilization mode to apply.
     * @param bokehEngaged   whether the cinematic bokeh engine is engaged.
     * @param ndrMode        dynamic-range mode to request.
     * @param targetFps      frame rate override, or null for no override.
     * @param logMode        cinema video mode (LOG when engaged).
     * @param tenBitEngaged  whether the native 10-bit path is engaged.
     * @param droppedMembers names of members the hardware could not honor.
     */
    data class CinematicBundleDecision(
        val stabilization: StabilizationMode,
        val bokehEngaged: Boolean,
        val ndrMode: HdrDynamicRangeMode,
        val targetFps: Int?,
        val logMode: CinemaVideoMode,
        val tenBitEngaged: Boolean,
        val droppedMembers: List<String>
    ) {
        companion object {
            /**
             * The frozen "bundle off" decision. Every member is in its safe
             * default state: no stabilization, no bokeh, SDR, no fps override,
             * PRO (non-LOG) mode, no 10-bit, nothing dropped. Returned verbatim
             * by [resolve] when the bundle is disabled so today's output is
             * reproduced bit-for-bit.
             */
            val INERT: CinematicBundleDecision = CinematicBundleDecision(
                stabilization = StabilizationMode.OFF,
                bokehEngaged = false,
                ndrMode = HdrDynamicRangeMode.SDR,
                targetFps = null,
                logMode = CinemaVideoMode.PRO,
                tenBitEngaged = false,
                droppedMembers = emptyList()
            )
        }
    }

    /**
     * Compose the cinematic bundle.
     *
     * @param enabled false → returns [CinematicBundleDecision.INERT] unchanged.
     * @param avail   hardware/pipeline availability snapshot.
     * @return the resolved decision. When enabled, each unavailable member is
     *         left OFF/SDR/false and named in
     *         [CinematicBundleDecision.droppedMembers].
     */
    fun resolve(
        enabled: Boolean,
        avail: CinematicBundleAvailability
    ): CinematicBundleDecision {
        if (!enabled) return CinematicBundleDecision.INERT

        val dropped = mutableListOf<String>()

        val stabilization = if (avail.eisAvailable) {
            StabilizationMode.HYBRID
        } else {
            dropped.add("stabilization")
            StabilizationMode.OFF
        }

        val bokehEngaged = avail.bokehAvailable
        if (!bokehEngaged) dropped.add("bokeh")

        val ndrMode = if (avail.hdrSupport.hdr10Supported) {
            HdrDynamicRangeMode.HDR10
        } else {
            dropped.add("ndr")
            HdrDynamicRangeMode.SDR
        }

        val tenBitEngaged = avail.tenBitSupport.hasNativeTenBitPath()
        if (!tenBitEngaged) dropped.add("10bit")

        return CinematicBundleDecision(
            stabilization = stabilization,
            bokehEngaged = bokehEngaged,
            ndrMode = ndrMode,
            targetFps = CINEMATIC_TARGET_FPS,
            logMode = CinemaVideoMode.LOG,
            tenBitEngaged = tenBitEngaged,
            droppedMembers = dropped.toList()
        )
    }
}
