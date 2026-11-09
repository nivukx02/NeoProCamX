package com.neoproc.neoproccamx.camera

internal data class VideoColorStabilityPlan(
    val aeLockRequested: Boolean,
    val awbLockRequested: Boolean,
    val allowManualWhiteBalance: Boolean,
    val requiresLockableAutoWhiteBalance: Boolean,
    val sceneAdaptiveColorAllowed: Boolean,
    val maxFrameToFrameColorDriftUv: Float,
    val tapFocusMeteringUnlockAllowed: Boolean,
    val evidenceLabel: String
)

internal object VideoColorStabilityPolicy {
    fun resolve(
        videoRecordingActive: Boolean,
        sensorMasterActive: Boolean,
        manualWhiteBalanceSelected: Boolean,
        existingAeAwbLock: Boolean,
        normalSdrVideoPreviewActive: Boolean = false,
        ndrOrLogVideoPreviewActive: Boolean = false,
        autoExposureConvergedOrLocked: Boolean = true,
        autoWhiteBalanceConvergedOrLocked: Boolean = true,
        ndrOrLogModeActive: Boolean,
        tapFocusMeteringUnlockActive: Boolean = false,
        automaticRecordingWhiteBalanceLockEnabled: Boolean = true
    ): VideoColorStabilityPlan {
        val applyManualWhiteBalance = manualWhiteBalanceSelected && !sensorMasterActive
        val focusMeteringUnlockAllowed =
            tapFocusMeteringUnlockActive &&
                !sensorMasterActive
        val autoColorConvergedOrLocked =
            autoExposureConvergedOrLocked && autoWhiteBalanceConvergedOrLocked
        // Tap focus may temporarily re-meter AE during video, but it must not make the white point
        // hunt. Keep the converged AWB state held while focus/AE regions move; RAW sensor masters and
        // explicit manual-WB requests remain untouched.
        val focusWhiteBalanceHold =
            focusMeteringUnlockAllowed &&
                !applyManualWhiteBalance &&
                autoWhiteBalanceConvergedOrLocked &&
                !ndrOrLogModeActive &&
                (videoRecordingActive || normalSdrVideoPreviewActive)
        val normalSdrAutoColorLock =
            !focusMeteringUnlockAllowed &&
            !sensorMasterActive &&
                !applyManualWhiteBalance &&
                !ndrOrLogModeActive &&
                autoColorConvergedOrLocked &&
                (normalSdrVideoPreviewActive ||
                    (videoRecordingActive && automaticRecordingWhiteBalanceLockEnabled))
        val shotStableNormalSdr = normalSdrAutoColorLock ||
            (focusWhiteBalanceHold && !ndrOrLogModeActive)
        // Proprietary NDR/NeoLog consumes timestamp-matched per-frame WB/CCM metadata in its own
        // temporally bounded colour pipeline. Automatically locking Camera2 AWB at convergence makes
        // the provisional white point permanent and produces the visible one-to-two-second handoff.
        // Keep these modes adaptive; explicit user lock/manual WB remain authoritative.
        val ndrLogPreviewAutoColorLock = false
        // AE is auto-locked ONLY on an explicit user AE/AWB lock — NEVER by the automatic SDR
        // stability path. Hard-locking the HAL AE froze exposure permanently: a locked AE reports
        // CONTROL_AE_STATE_LOCKED, which the convergence gate counts as "converged", so the lock could
        // never detect a genuine scene change and never released — panning to a window / walking
        // outdoors left the preview (and recording) stuck at the original exposure. Keeping AE ADAPTIVE
        // (matching the NDR/LOG path above) makes exposure re-meter on scene changes like a real camera;
        // colour stability is still held by the AWB lock below, and a genuine scene change de-converges
        // AE, which drops normalSdrAutoColorLock and releases the AWB lock so white balance re-meters too.
        val lockAutoExposure = !focusMeteringUnlockAllowed && existingAeAwbLock
        val lockAutoWhiteBalance =
            !applyManualWhiteBalance &&
                (
                    existingAeAwbLock ||
                        (videoRecordingActive &&
                            !sensorMasterActive &&
                            !ndrOrLogModeActive &&
                            automaticRecordingWhiteBalanceLockEnabled &&
                            autoColorConvergedOrLocked) ||
                        (normalSdrVideoPreviewActive && normalSdrAutoColorLock) ||
                        ndrLogPreviewAutoColorLock ||
                        focusWhiteBalanceHold
                    )
        return VideoColorStabilityPlan(
            aeLockRequested = lockAutoExposure,
            awbLockRequested = lockAutoWhiteBalance,
            allowManualWhiteBalance = !sensorMasterActive,
            requiresLockableAutoWhiteBalance = shotStableNormalSdr,
            sceneAdaptiveColorAllowed = !shotStableNormalSdr,
            maxFrameToFrameColorDriftUv = if (shotStableNormalSdr) 0.0015f else 0.0060f,
            tapFocusMeteringUnlockAllowed = focusMeteringUnlockAllowed,
            evidenceLabel = when {
                sensorMasterActive && videoRecordingActive ->
                    "RAW_MONITOR_LOCKED_MASTER_UNMODIFIED"
                applyManualWhiteBalance && videoRecordingActive -> "MANUAL_WB_RECORDING"
                applyManualWhiteBalance -> "MANUAL_WB_PREVIEW"
                focusMeteringUnlockAllowed && videoRecordingActive && lockAutoWhiteBalance ->
                    "TAP_FOCUS_RECORDING_AE_UNLOCK_AWB_HELD"
                focusMeteringUnlockAllowed &&
                    (normalSdrVideoPreviewActive || ndrOrLogVideoPreviewActive) &&
                    lockAutoWhiteBalance -> "TAP_FOCUS_VIDEO_PREVIEW_AE_UNLOCK_AWB_HELD"
                tapFocusMeteringUnlockActive && normalSdrVideoPreviewActive && normalSdrAutoColorLock ->
                    if (lockAutoExposure) "NORMAL_SDR_VIDEO_PREVIEW_FOCUS_AE_AWB_LOCKED"
                    else "NORMAL_SDR_VIDEO_PREVIEW_FOCUS_AWB_LOCKED_AE_ADAPTIVE"
                tapFocusMeteringUnlockActive && videoRecordingActive && normalSdrAutoColorLock ->
                    if (lockAutoExposure) "NORMAL_SDR_VIDEO_RECORDING_FOCUS_AE_AWB_LOCKED"
                    else "NORMAL_SDR_VIDEO_RECORDING_FOCUS_AWB_LOCKED_AE_ADAPTIVE"
                focusMeteringUnlockAllowed -> "TAP_FOCUS_METERING_UNLOCK"
                videoRecordingActive && normalSdrAutoColorLock ->
                    if (lockAutoExposure) "NORMAL_SDR_VIDEO_RECORDING_AE_AWB_LOCKED"
                    else "NORMAL_SDR_VIDEO_RECORDING_AWB_LOCKED_AE_ADAPTIVE"
                videoRecordingActive && lockAutoWhiteBalance -> "AUTO_WB_SHOT_LOCKED"
                videoRecordingActive -> "AUTO_WB_RECORDING_ADAPTIVE"
                existingAeAwbLock -> "USER_AE_AWB_LOCK"
                normalSdrVideoPreviewActive && normalSdrAutoColorLock ->
                    if (lockAutoExposure) "NORMAL_SDR_VIDEO_PREVIEW_AE_AWB_LOCKED"
                    else "NORMAL_SDR_VIDEO_PREVIEW_AWB_LOCKED_AE_ADAPTIVE"
                normalSdrVideoPreviewActive ->
                    "NORMAL_SDR_VIDEO_PREVIEW_AE_AWB_ADAPTIVE_UNCONVERGED"
                ndrLogPreviewAutoColorLock -> "NDR_LOG_PREVIEW_AWB_LOCKED"
                ndrOrLogModeActive -> "NDR_LOG_PREVIEW_AE_AWB_ADAPTIVE"
                else -> "AUTO_WB_PREVIEW_ADAPTIVE"
            }
        )
    }

    /** ISO below which the per-frame drift budget is used unscaled (bright/nominal light). */
    const val LOW_LIGHT_ISO_FLOOR = 800

    /** ISO at/above which the drift budget is scaled by [LOW_LIGHT_MIN_BUDGET_SCALE] (deep low light). */
    const val LOW_LIGHT_ISO_CEIL = 6400

    /**
     * Tightest low-light budget scale. In deep low light the HAL's auto-white-balance estimate is
     * noisy (photon-starved), so the per-frame u'v' budget is tightened toward this floor to suppress
     * chroma hunting — a genuine (slow) illuminant change is still tracked, just more calmly.
     */
    const val LOW_LIGHT_MIN_BUDGET_SCALE = 0.5f

    /**
     * P2.9: lux/ISO-adaptive scale for [VideoColorStabilityPlan.maxFrameToFrameColorDriftUv]. Returns
     * `1.0` at or below [LOW_LIGHT_ISO_FLOOR] — so nominal-light behaviour is byte-for-byte today's —
     * and ramps linearly down to [LOW_LIGHT_MIN_BUDGET_SCALE] at [LOW_LIGHT_ISO_CEIL] and above. An
     * unknown ISO (`<= 0`) also returns `1.0` (no change), so callers without a metered ISO are safe.
     */
    fun lowLightBudgetScale(iso: Int): Float {
        if (iso <= LOW_LIGHT_ISO_FLOOR) return 1f
        val span = (LOW_LIGHT_ISO_CEIL - LOW_LIGHT_ISO_FLOOR).toFloat()
        val t = ((iso - LOW_LIGHT_ISO_FLOOR).toFloat() / span).coerceIn(0f, 1f)
        return 1f - (1f - LOW_LIGHT_MIN_BUDGET_SCALE) * t
    }
}
