package com.neoproc.neoproccamx.camera

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * NDR10+/NIVUKX motion-blur elimination — a CLOSED-LOOP shutter cap that keeps auto-exposure fully
 * adaptive while guaranteeing the exposure time never grows long enough to smear motion.
 *
 * Why a closed loop and not a plain fixed shutter:
 *  - A fixed / pinned exposure time (Camera2 `SENSOR_EXPOSURE_TIME_PRIORITY`) blows out bright scenes:
 *    in daylight the correct exposure is far shorter than any anti-blur cap, so pinning to the cap
 *    would over-expose. That is the OPPOSITE of what the user wants.
 *  - So we only PIN the exposure at the cap when the metered (auto) exposure would otherwise run
 *    LONGER than the cap — i.e. in dim scenes where blur actually happens. Auto-exposure then raises
 *    ISO to keep brightness (the extra noise is handled by the record-path denoiser). In bright
 *    scenes the metered exposure is already under the cap, so we stay in plain AUTO and highlights
 *    are never pushed up.
 *
 * The loop runs off the capture-result feedback (metered exposure time + ISO). Hysteresis on both
 * edges (engage only when clearly over the cap; release only when the pinned frame's ISO has fallen
 * near the sensor floor, i.e. the scene is bright enough that AUTO would pick a sub-cap exposure)
 * prevents flapping between the two states.
 *
 * The cap itself is expressed as a shutter angle: cap = frameDuration / [shutterAngleDivisor].
 * Divisor 2 == 180 degrees (normal cinema), 4 == 90 degrees (crisp / low-blur, the default here), 8
 * == 45 degrees (very crisp, noisier). The default is deliberately below 180 degrees because the
 * user asked to strictly avoid motion blur.
 */
internal object NdrAntiMotionBlurShutterPolicy {

    /** 90-degree shutter (frameDuration / 4): crisp, clearly below the 180-degree cinema blur. */
    const val DEFAULT_SHUTTER_ANGLE_DIVISOR = 4

    /** Engage only once AE's metered exposure exceeds the cap by this margin (anti-flap). */
    private const val ENGAGE_MARGIN = 1.10f

    /**
     * While pinned, release back to plain AUTO once the metered ISO has fallen to within this factor
     * of the sensor floor — the scene is then bright enough that AUTO would choose a sub-cap exposure
     * anyway, so there is no blur to guard against and no reason to hold the higher-ISO pinned frame.
     */
    private const val RELEASE_ISO_MARGIN = 1.35f

    data class Decision(
        /** True → request `SENSOR_EXPOSURE_TIME_PRIORITY` pinned at [pinnedExposureNs]. */
        val engage: Boolean,
        /** The exposure time to pin at when [engage]; also the cap the loop is defending. */
        val pinnedExposureNs: Long,
        /** The computed anti-blur cap (frameDuration / divisor, clamped to the sensor range). */
        val capNs: Long,
        val reason: String
    )

    /** Compute the anti-blur exposure cap for a frame duration, clamped to the sensor exposure range. */
    fun resolveCapNs(
        frameDurationNs: Long,
        exposureRange: CameraLongRange,
        shutterAngleDivisor: Int = DEFAULT_SHUTTER_ANGLE_DIVISOR
    ): Long {
        val lower = minOf(exposureRange.lower, exposureRange.upper).coerceAtLeast(1L)
        val upper = maxOf(exposureRange.lower, exposureRange.upper).coerceAtLeast(lower)
        val divisor = shutterAngleDivisor.coerceAtLeast(1)
        val rawCap = (frameDurationNs.coerceAtLeast(1L).toDouble() / divisor.toDouble()).roundToLong()
        return rawCap.coerceIn(lower, upper)
    }

    /**
     * Full resolve: computes the cap from [frameDurationNs] then decides engage/release. See
     * [resolveWithCap] for the state-machine detail.
     */
    fun resolve(
        requested: Boolean,
        currentlyEngaged: Boolean,
        meteredExposureNs: Long,
        meteredIso: Int,
        isoFloor: Int,
        frameDurationNs: Long,
        exposureRange: CameraLongRange,
        shutterAngleDivisor: Int = DEFAULT_SHUTTER_ANGLE_DIVISOR
    ): Decision {
        val capNs = resolveCapNs(frameDurationNs, exposureRange, shutterAngleDivisor)
        return resolveWithCap(
            requested = requested,
            currentlyEngaged = currentlyEngaged,
            meteredExposureNs = meteredExposureNs,
            meteredIso = meteredIso,
            isoFloor = isoFloor,
            capNs = capNs
        )
    }

    /**
     * State-machine decision against a pre-computed [capNs]. Used both by [resolve] (request build)
     * and by the capture-result feedback loop (which caches [capNs]/[isoFloor] from the last build so
     * it can cheaply decide whether the engage state should flip and a rebuild is warranted).
     */
    fun resolveWithCap(
        requested: Boolean,
        currentlyEngaged: Boolean,
        meteredExposureNs: Long,
        meteredIso: Int,
        isoFloor: Int,
        capNs: Long
    ): Decision {
        if (!requested) {
            return Decision(engage = false, pinnedExposureNs = capNs, capNs = capNs, reason = "NOT_REQUESTED")
        }
        // No trustworthy metered feedback yet → hold the current state so we neither flap nor pin
        // blind at cold start.
        if (meteredExposureNs <= 0L || meteredIso <= 0) {
            return Decision(
                engage = currentlyEngaged,
                pinnedExposureNs = capNs,
                capNs = capNs,
                reason = "HOLD_NO_METERED_DATA"
            )
        }
        val floor = isoFloor.coerceAtLeast(1)
        val engage: Boolean
        val reason: String
        if (currentlyEngaged) {
            // Pinned at cap: metered exposure ~= cap, so decide on ISO. Bright enough (ISO near floor)
            // → release to AUTO; still dim (high ISO) → keep the cap to hold off blur.
            val releaseIsoCeiling = (floor * RELEASE_ISO_MARGIN).roundToInt().coerceAtLeast(floor)
            if (meteredIso <= releaseIsoCeiling) {
                engage = false
                reason = "RELEASE_SCENE_BRIGHT_ISO_NEAR_FLOOR"
            } else {
                engage = true
                reason = "HOLD_PINNED_DIM_SCENE"
            }
        } else {
            // Plain AUTO: engage only if AE's natural exposure runs meaningfully past the cap (dim,
            // blur-prone). Otherwise the scene is bright and AUTO's sub-cap exposure is already crisp.
            val engageThreshold = (capNs * ENGAGE_MARGIN).roundToLong()
            if (meteredExposureNs > engageThreshold) {
                engage = true
                reason = "ENGAGE_METERED_EXPOSURE_OVER_CAP"
            } else {
                engage = false
                reason = "AUTO_METERED_EXPOSURE_UNDER_CAP"
            }
        }
        return Decision(engage = engage, pinnedExposureNs = capNs, capNs = capNs, reason = reason)
    }
}
