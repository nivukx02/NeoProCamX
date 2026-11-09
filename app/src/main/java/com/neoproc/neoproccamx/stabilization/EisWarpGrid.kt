package com.neoproc.neoproccamx.stabilization

import com.neoproc.neoproccamx.camera.CameraMotionTelemetry

/**
 * s3-f06 / CP60-64 — the CPU producer for the gyro-EIS per-scanline warp grid that `eis_warp.comp` consumes.
 *
 * Chains the (CP60-64-locked, JVM-tested) stabilization stack into one per-output-row [GyroEisWarp.EisCorrection]
 * grid: the mode-selected global frame correction (from the [OrientationTrajectorySmoother] residual via
 * [StabilizationModePolicy]) + the [RollingShutterGrid] per-row refinement, OIS-fused ([OisEisFusion]) and
 * crop-margin-clamped ([GyroEisWarp.clampToCropMargin]) per row so the shader can never reveal a black border.
 *
 * NO-DOWNGRADE: with EIS OFF the grid is all-zero (an identity warp). The grid + the [EisWarpParams] push
 * block are produced here on the CPU and would be uploaded to the (dormant) `eis_warp` pipeline only once the
 * owner enables EIS device-side after the clock / OIS / skew calibrations — they reach no pixel until then.
 */
data class EisWarpParams(
    val cropMarginPixels: Float,
    val centerX: Float,
    val centerY: Float,
    val gridRows: Int,
) {
    /**
     * Push-constant layout for `eis_warp.comp`'s EisWarpParams block: 3 floats + 1 int. The int slot carries
     * int-bits (floatBitsToInt convention), matching the mixed int/float push-constant convention the native
     * side uploads as one contiguous block. Locked by EisWarpPushConstantLayoutTest.
     */
    fun toPushConstants(): FloatArray = floatArrayOf(
        cropMarginPixels,
        centerX,
        centerY,
        Float.fromBits(gridRows),
    )
}

data class EisWarpGrid(
    /** One [GyroEisWarp.EisCorrection] per output row (size == [EisWarpParams.gridRows]). */
    val perRow: List<GyroEisWarp.EisCorrection>,
    val params: EisWarpParams,
) {
    val rows: Int get() = perRow.size

    /** True when every row is zero — an identity warp (the EIS-OFF / no-downgrade state). */
    val isIdentity: Boolean
        get() = perRow.all { it.dxPixels == 0f && it.dyPixels == 0f && it.rollRadians == 0f }

    /**
     * Pack the per-row corrections into the rgba16f-style grid image (height == rows, width 1): each row is
     * `(dx, dy, roll, 1)`. This is the buffer the shader's `uRowGrid` binding samples per scanline.
     */
    fun packGridRgba(): FloatArray {
        val out = FloatArray(perRow.size * 4)
        perRow.forEachIndexed { i, c ->
            out[i * 4] = c.dxPixels
            out[i * 4 + 1] = c.dyPixels
            out[i * 4 + 2] = c.rollRadians
            out[i * 4 + 3] = 1f
        }
        return out
    }
}

object EisWarpGridProducer {

    /**
     * Build the per-scanline EIS warp grid for one frame. With [mode] == OFF (or no rows) the grid is the
     * identity (all-zero) grid, so the warp is a bit-for-bit passthrough — the no-downgrade default.
     *
     * @param smoothedFrame the latest [OrientationTrajectorySmoother.SmoothedFrame] (raw + smoothed + residual).
     * @param samples the GYRO-domain ring view (oldest first) for the rolling-shutter per-row refinement.
     */
    fun produce(
        mode: StabilizationModePolicy.StabilizationMode,
        smoothedFrame: OrientationTrajectorySmoother.SmoothedFrame,
        lockedReference: Quaternion,
        focalLengthPx: Float,
        cropMarginPixels: Float,
        samples: List<CameraMotionTelemetry>,
        frameStartNs: Long,
        rowReadoutNs: Long,
        rows: Int,
        referenceRow: Int,
        oisShiftXPixels: Float,
        oisShiftYPixels: Float,
        centerX: Float,
        centerY: Float,
    ): EisWarpGrid {
        val safeRows = rows.coerceAtLeast(0)
        val params = EisWarpParams(cropMarginPixels, centerX, centerY, safeRows)
        if (mode == StabilizationModePolicy.StabilizationMode.OFF || safeRows == 0) {
            return EisWarpGrid(List(safeRows) { GyroEisWarp.EisCorrection(0f, 0f, 0f) }, params)
        }
        // Mode-selected global frame correction (already crop-clamped by the mode policy)...
        val global = StabilizationModePolicy.correction(mode, smoothedFrame, lockedReference, focalLengthPx, cropMarginPixels)
        // ...plus the per-row rolling-shutter refinement against each row's readout-time orientation.
        val rsRows = RollingShutterGrid.perRowCorrections(samples, frameStartNs, rowReadoutNs, safeRows, referenceRow, focalLengthPx)
        val perRow = rsRows.map { rs ->
            val combined = GyroEisWarp.EisCorrection(
                dxPixels = global.dxPixels + rs.dxPixels,
                dyPixels = global.dyPixels + rs.dyPixels,
                rollRadians = global.rollRadians + rs.rollRadians,
            )
            val fused = OisEisFusion.fuse(combined, oisShiftXPixels, oisShiftYPixels)
            GyroEisWarp.clampToCropMargin(fused, cropMarginPixels)
        }
        return EisWarpGrid(perRow, params)
    }
}
