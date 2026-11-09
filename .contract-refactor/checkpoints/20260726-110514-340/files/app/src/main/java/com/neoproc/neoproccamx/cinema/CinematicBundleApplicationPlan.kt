package com.neoproc.neoproccamx.cinema

import com.neoproc.neoproccamx.camera.HdrDynamicRangeMode
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.StabilizationMode

/**
 * ELITE Rank-2 — pure, deterministic mapping from a resolved [CinematicBundlePolicy.CinematicBundleDecision]
 * to the concrete recording knobs the ViewModel applies, INCLUDING the 180°-shutter exposure the decision
 * itself does not carry.
 *
 * Extracted as a pure value so the fps→shutter→HDR-token→stabilization→cinema-mode mapping is JVM-unit-
 * testable (the ViewModel is not JVM-instantiable). The ViewModel builds a live availability snapshot,
 * calls [CinematicBundlePolicy.resolve], then hands the decision here and applies the fields in the
 * refresh-minimizing order (fps → cinema mode → HDR → stabilization → shutter).
 *
 * The 180° shutter is computed for the RESOLVED target fps (24p when engaged), NOT the pre-change fps —
 * so the reported exposure lands at a true 1/48 s at 24p, satisfying the elite cadence acceptance metric.
 */
data class CinematicBundleApplicationPlan(
    /** Frame-rate override to apply, or null for no fps change. */
    val fps: Int?,
    /** 180°-rule exposure (ns) for the effective fps; 0 when no valid fps is known (skip setExposure). */
    val shutterNs: Long,
    /** Token for `setVideoHdrMode` — "NDR10+" when HDR is engaged, "OFF" for SDR. */
    val hdrModeToken: String,
    /** Stabilization mode to apply. */
    val stabilization: StabilizationMode,
    /** Cinema video mode (LOG when engaged). */
    val cinemaMode: CinemaVideoMode,
    /** Members the hardware could not honor (for an honest "partially available" warning). */
    val droppedMembers: List<String>
) {
    companion object {
        /** The cinematic 180° shutter angle. */
        const val CINEMATIC_SHUTTER_ANGLE_DEGREES: Double = 180.0

        /** HDR-mode tokens understood by `CameraViewModel.setVideoHdrMode`. */
        const val HDR_TOKEN_NDR10_PLUS: String = "NDR10+"
        const val HDR_TOKEN_OFF: String = "OFF"

        /**
         * Build the application plan.
         *
         * @param decision   the resolved bundle decision.
         * @param currentFps the current recording fps, used only to compute the shutter when the decision
         *                   carries no fps override (INERT); the shutter always tracks the effective fps.
         */
        fun from(
            decision: CinematicBundlePolicy.CinematicBundleDecision,
            currentFps: Int
        ): CinematicBundleApplicationPlan {
            val effectiveFps = decision.targetFps ?: currentFps
            val shutter = if (effectiveFps > 0) {
                CinemaFrameRateCatalog.shutterNs(effectiveFps.toDouble(), CINEMATIC_SHUTTER_ANGLE_DEGREES)
            } else {
                0L
            }
            val token = if (decision.ndrMode == HdrDynamicRangeMode.SDR) HDR_TOKEN_OFF else HDR_TOKEN_NDR10_PLUS
            return CinematicBundleApplicationPlan(
                fps = decision.targetFps,
                shutterNs = shutter,
                hdrModeToken = token,
                stabilization = decision.stabilization,
                cinemaMode = decision.logMode,
                droppedMembers = decision.droppedMembers
            )
        }
    }
}
