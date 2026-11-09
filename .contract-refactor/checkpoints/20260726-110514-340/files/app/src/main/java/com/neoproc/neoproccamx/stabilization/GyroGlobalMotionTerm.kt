package com.neoproc.neoproccamx.stabilization

import com.neoproc.neoproccamx.camera.CameraMotionTelemetry

/**
 * s1-f04 → s1-f06 hand-off — the global-motion alignment term MFSR consumes per frame.
 *
 * Multi-frame super-resolution aligns each burst frame to a reference frame before fusing. Per-tile
 * optical flow (s3-f01) resolves LOCAL motion; the gyro supplies the GLOBAL camera motion between the
 * reference frame and frame f, removing the large rigid component so flow only has to recover the
 * residual. That global term is the inter-frame camera rotation mapped through the same pinhole model
 * the EIS warp uses, i.e. a frame-centre pixel translation (+ in-plane roll) — the exact shape MFSR's
 * per-frame alignment expects.
 *
 * Both frame instants are the mid-exposure timestamps (already reconciled to the gyro clock by
 * [GyroExposureWindowReconciler]); mid-exposure keeps the term symmetric with the integration the
 * smoother/warp use. Pure, deterministic, JVM-tested, dormant — it produces a value, it changes no pixel.
 */
object GyroGlobalMotionTerm {

    /** The per-frame global-motion alignment MFSR adds to its per-tile flow. */
    data class GlobalMotion(
        /** Inter-frame camera rotation: reference-frame orientation⁻¹ · this-frame orientation. */
        val rotation: Quaternion,
        /** Pinhole pixel translation (+roll) of the frame centre that [rotation] induces. */
        val pixel: GyroEisWarp.EisCorrection,
    )

    /**
     * Global motion of the frame at [frameMidNs] relative to the reference frame at [refMidNs], over the
     * (oldest-first, GYRO-domain) [samples], at pinhole [focalLengthPx]. Handles frame-before/after-ref.
     * Zero camera motion ⇒ identity rotation ⇒ zero pixel term.
     */
    fun between(
        samples: List<CameraMotionTelemetry>,
        refMidNs: Long,
        frameMidNs: Long,
        focalLengthPx: Float,
    ): GlobalMotion {
        val rotation = when {
            frameMidNs > refMidNs -> GyroOrientationIntegrator.rotationBetween(samples, refMidNs, frameMidNs)
            frameMidNs < refMidNs -> GyroOrientationIntegrator.rotationBetween(samples, frameMidNs, refMidNs).conjugate()
            else -> Quaternion.IDENTITY
        }
        return GlobalMotion(rotation, GyroEisWarp.correctionFor(rotation, focalLengthPx))
    }

    /**
     * As [between], but subtract the OIS-absorbed pixel shift the lens already applied for this frame so
     * the global term is not double-counted against an OIS-stabilized capture. Roll is OIS-independent.
     * (OIS sign/scale relative to the EIS axes is DEVICE-GATED calibration; the subtraction is what is
     * locked here — see [OisEisFusion].)
     */
    fun betweenOisFused(
        samples: List<CameraMotionTelemetry>,
        refMidNs: Long,
        frameMidNs: Long,
        focalLengthPx: Float,
        oisShiftXPixels: Float,
        oisShiftYPixels: Float,
    ): GlobalMotion {
        val raw = between(samples, refMidNs, frameMidNs, focalLengthPx)
        return raw.copy(pixel = OisEisFusion.fuse(raw.pixel, oisShiftXPixels, oisShiftYPixels))
    }
}
