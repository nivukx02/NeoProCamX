package com.neoproc.neoproccamx.ui

import android.util.Size
import com.neoproc.neoproccamx.rendering.RecordDevelopModeFlag

/**
 * Adaptive record-fps ceiling for the GPU-PROCESSED recording route (OFFSCREEN_AHB_EGL_ENCODER, and by
 * extension any internal-hardware-import GPU-enhance feed).
 *
 * Real-device verification with the full 10-bit/color/detail stack showed that ≥7 MP offscreen P010
 * recording must not advertise 30 fps. The high-end route therefore keeps native resolution, Main10
 * transport, and the full processed look while selecting the 25 fps cinema target before recording starts.
 */
internal object ProcessedGpuRecordFpsPolicy {
    private const val HIGH_RESOLUTION_PIXEL_THRESHOLD = 7_000_000L
    private const val HIGH_RESOLUTION_SUSTAINABLE_FPS = 25

    /**
     * Full-sensor pixel gate for the record-then-develop LIGHT lane. 4096×3072 = 12,582,912 px sits above
     * this; 4K-class 3840×2160 (8,294,400) and 4096×2160 (8,847,360) sit below, so the light-lane lift
     * applies ONLY to full-sensor and never touches the 4K cap.
     */
    private const val FULL_SENSOR_PIXEL_THRESHOLD = 12_000_000L

    /**
     * Sustainable full-sensor record cadence when the record-then-develop LIGHT lane is active: with the
     * heavy live grade stripped (denoise/sharpen/creative/PQ deferred to the offline develop), the single
     * GPU stage clears a frame fast enough to hold 30 fps. Applied only behind [RecordDevelopModeFlag].
     */
    private const val LIGHT_RECORD_FULL_SENSOR_FPS = 30

    /**
     * Conservative GPU-enhance throughput ceiling in pixels/second for this device class. This was a
     * pre-measurement estimate (~28 fps at 4096×3072 ≈ 352 Mpix/s, held ~15% below), and it snaps
     * 4096×3072 down to 24 fps. Kept as the default (flag-OFF) ceiling for byte-identical baseline behavior.
     */
    const val GPU_ENHANCE_PIXELS_PER_SECOND: Long = 300_000_000L

    /** Historical A/B ceiling retained for explicit-ceiling tests below the verified high-res gate. */
    const val GPU_ENHANCE_PIXELS_PER_SECOND_MEASURED: Long = 337_000_000L

    /** Cinema/broadcast frame-rate tiers, highest first — the record target snaps down onto one of these. */
    private val STANDARD_TIERS = intArrayOf(60, 50, 48, 25, 24)

    /**
     * Never cap below this. The cinema floor; 25 fps is the high-resolution target and 24 fps remains
     * available for explicit 24p requests.
     */
    const val MIN_SUSTAINABLE_FPS = 24

    /** The throughput ceiling in effect right now, selected by the reversible [RecordFpsCeilingFlag]. */
    fun activeCeilingPixelsPerSecond(): Long =
        if (RecordFpsCeilingFlag.enabled) GPU_ENHANCE_PIXELS_PER_SECOND_MEASURED
        else GPU_ENHANCE_PIXELS_PER_SECOND

    fun resolve(requestedFps: Int, encoderSize: Size): Int =
        resolve(requestedFps, encoderSize.width, encoderSize.height)

    /** Live entry point: resolves against whichever ceiling [RecordFpsCeilingFlag] currently selects. */
    fun resolve(requestedFps: Int, width: Int, height: Int): Int {
        // Record-then-develop LIGHT lane (reversible, default-OFF): the live elite grade that forced the
        // full-sensor cinema cap is stripped — the heavy stack runs OFFLINE — so full-sensor can hold a
        // higher cadence. Lift ONLY the full-sensor tier, never above the request, never touching 4K.
        // This lives in the LIVE overload only; the pure math overload below is untouched (baseline exact).
        if (RecordDevelopModeFlag.enabled) {
            val pixels = width.coerceAtLeast(0).toLong() * height.coerceAtLeast(0).toLong()
            if (pixels >= FULL_SENSOR_PIXEL_THRESHOLD) {
                // Full-sensor constant-fps (reversible, default ON): pin full-sensor to a rock-solid 25
                // instead of the LIGHT-lane 30 lift so the cadence never wobbles 25↔30 under thermal load.
                // Flag OFF ⇒ the 30 lift is byte-identical (only the LIVE overload is touched; the pure math
                // overload below is unchanged, so its golden tests stay green).
                val fullSensorCap = com.neoproc.neoproccamx.recording.FullSensorConstantFpsPolicy
                    .lightLaneFullSensorFps(LIGHT_RECORD_FULL_SENSOR_FPS)
                return requestedFps.coerceAtLeast(1).coerceAtMost(fullSensorCap)
            }
        }
        return resolve(requestedFps, width, height, activeCeilingPixelsPerSecond())
    }

    /**
     * Pure math over an EXPLICIT throughput ceiling — no flag/device access, deterministically unit-testable.
     * The live [resolve] overloads delegate here with [activeCeilingPixelsPerSecond].
     */
    fun resolve(requestedFps: Int, width: Int, height: Int, pixelsPerSecond: Long): Int {
        val safeRequested = requestedFps.coerceAtLeast(1)
        val pixels = width.coerceAtLeast(0).toLong() * height.coerceAtLeast(0).toLong()
        if (pixels <= 0L) return safeRequested
        if (pixels >= HIGH_RESOLUTION_PIXEL_THRESHOLD &&
            safeRequested > HIGH_RESOLUTION_SUSTAINABLE_FPS
        ) {
            return HIGH_RESOLUTION_SUSTAINABLE_FPS
        }
        val ceiling = pixelsPerSecond.coerceAtLeast(1L)
        val sustainableRawFps = (ceiling / pixels).toInt()
        val tier = STANDARD_TIERS.firstOrNull { it <= safeRequested && it <= sustainableRawFps }
        return tier ?: minOf(safeRequested, MIN_SUSTAINABLE_FPS)
    }
}
