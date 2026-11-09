package com.neoproc.neoproccamx.camera

/**
 * Hysteresis + hold for the AE/AWB "converged" signals that gate the SDR-preview auto-lock in
 * [VideoColorStabilityPolicy].
 *
 * The lock used to react to the *instantaneous* per-frame `CONTROL_AE_STATE`/`CONTROL_AWB_STATE`:
 * the moment a reframe (or a continuous-AF re-scan) pushed either out of CONVERGED, the policy
 * collapsed the lock in a single step — releasing AE into a free re-meter (the brief over-exposed
 * "washout") and the white point into a re-converge (the skin-color shift). This class damps that
 * transition:
 *
 *  - **Debounce to lock:** require [stabilizeFrames] consecutive converged frames before declaring
 *    stable, so a flicker into CONVERGED doesn't latch prematurely.
 *  - **Hold through a blip:** once stable, stay stable through a *brief* de-convergence — up to
 *    [holdFrames] frames, or [afScanHoldFrames] while AF is actively scanning (the common reframe
 *    trigger). Only a *sustained* de-convergence (a genuine scene change) releases the lock, after
 *    which AE/AWB adapt as before. This trades a small adaptation delay for the elimination of the
 *    transient washout/skin-shift on quick reframes and AF micro-scans.
 *
 * Defaults are conservative starting points (frame-counted; ~30fps → holdFrames 8 ≈ 0.27s,
 * afScanHoldFrames 20 ≈ 0.67s) and are meant to be tuned on-device. With (stabilizeFrames=1,
 * holdFrames=0, afScanHoldFrames=0) this is an exact identity passthrough of the raw flags.
 *
 * Drive [update] once per capture result. Mutations are synchronized because capture-result ingress
 * is isolated from the Camera2 control looper; volatile outputs keep request construction lock-free.
 */
internal class MeteringConvergenceHysteresis(
    private val stabilizeFrames: Int = DEFAULT_STABILIZE_FRAMES,
    private val holdFrames: Int = DEFAULT_HOLD_FRAMES,
    private val afScanHoldFrames: Int = DEFAULT_AF_SCAN_HOLD_FRAMES
) {

    /** Debounced "auto-exposure is stable enough to lock" signal. */
    @Volatile var aeStable: Boolean = false
        private set

    /** Debounced "auto-white-balance is stable enough to lock" signal. */
    @Volatile var awbStable: Boolean = false
        private set

    private var aeConvergedRun = 0
    private var aeDeconvergedRun = 0
    private var awbConvergedRun = 0
    private var awbDeconvergedRun = 0

    @Synchronized
    fun reset() {
        aeStable = false
        awbStable = false
        aeConvergedRun = 0
        aeDeconvergedRun = 0
        awbConvergedRun = 0
        awbDeconvergedRun = 0
    }

    /**
     * Advances the hysteresis by one capture result.
     *
     * @return true if [aeStable] or [awbStable] changed this frame (the caller should rebuild the
     *   capture request so the new lock decision is applied promptly).
     */
    @Synchronized
    fun update(aeConvergedRaw: Boolean, awbConvergedRaw: Boolean, afScanActive: Boolean): Boolean {
        val beforeAe = aeStable
        val beforeAwb = awbStable
        val holdLimit = if (afScanActive) afScanHoldFrames else holdFrames

        if (aeConvergedRaw) {
            aeConvergedRun++
            aeDeconvergedRun = 0
            if (!aeStable && aeConvergedRun >= stabilizeFrames) aeStable = true
        } else {
            aeDeconvergedRun++
            aeConvergedRun = 0
            if (aeStable && aeDeconvergedRun > holdLimit) aeStable = false
        }

        if (awbConvergedRaw) {
            awbConvergedRun++
            awbDeconvergedRun = 0
            if (!awbStable && awbConvergedRun >= stabilizeFrames) awbStable = true
        } else {
            awbDeconvergedRun++
            awbConvergedRun = 0
            if (awbStable && awbDeconvergedRun > holdLimit) awbStable = false
        }

        return aeStable != beforeAe || awbStable != beforeAwb
    }

    companion object {
        const val DEFAULT_STABILIZE_FRAMES = 2
        const val DEFAULT_HOLD_FRAMES = 8
        const val DEFAULT_AF_SCAN_HOLD_FRAMES = 20
    }
}
