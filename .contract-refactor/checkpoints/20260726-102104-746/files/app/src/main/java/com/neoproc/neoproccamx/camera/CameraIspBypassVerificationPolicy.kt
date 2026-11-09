package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CaptureRequest

/**
 * CP17 — ISP-bypass read-back verification (HONEST-GATES).
 *
 * The capture path *requests* a sensor-native, ISP-off bytestream: EDGE / NOISE_REDUCTION /
 * HOT_PIXEL / SHADING / COLOR_CORRECTION_ABERRATION off, a linear 1:1 tonemap, and an explicit
 * distortion-correction geometry. Requesting is not proving — a vendor HAL may silently re-enable
 * any of those blocks. This policy closes the loop: given what we *requested* (extracted from the
 * final [CaptureRequest]) and what the device *reported* (extracted from the `TotalCaptureResult`),
 * it emits a per-block [IspBypassBlockVerdict] (HONORED / NOT_HONORED / UNAVAILABLE) and an
 * aggregate [IspBypassHonestyVerdict] (PROVEN / NOT_PROVEN / NOT_APPLICABLE), proves the tonemap
 * curve is the identity ramp, and records the chosen distortion geometry.
 *
 * It is OBSERVE-ONLY — it changes reporting, never the request stream. Both inputs are plain data
 * (nullable Int per block, using the same Camera2 mode constants on request and result), so the
 * whole decision is a pure function with no Android instance dependency and is fully JVM-unit-
 * tested. The thin `CaptureRequest.Builder` / `TotalCaptureResult` extraction lives device-side in
 * [CameraController].
 *
 * Honesty caveat (HAL comments BEYOND_IMAX_RAW / SUPREME_FIDELITY_BYPASS): Camera2 can only prove
 * the *public* ISP blocks. Vendor-private stages remain unprovable, so a HONORED verdict is
 * necessary-but-not-sufficient evidence of true bypass. This policy reports read-back agreement; it
 * never fabricates a "bypass complete".
 */
internal enum class IspBypassBlock(val label: String) {
    EDGE("EDGE"),
    NOISE_REDUCTION("NOISE_REDUCTION"),
    HOT_PIXEL("HOT_PIXEL"),
    SHADING("SHADING"),
    COLOR_ABERRATION("COLOR_ABERRATION"),
    TONEMAP("TONEMAP"),
    DISTORTION_CORRECTION("DISTORTION_CORRECTION")
}

internal enum class IspBypassBlockVerdict {
    /** The device reported exactly the mode we requested. */
    HONORED,

    /** The device reported a different mode — the block was silently re-enabled or overridden. */
    NOT_HONORED,

    /** We requested a mode, but the result carried no value for this block — it cannot be proven. */
    UNAVAILABLE,

    /** No mode was requested for this block — it is not part of the bypass claim. */
    NOT_REQUESTED
}

internal enum class IspBypassHonestyVerdict {
    /** Every requested block read back HONORED (and the linear tonemap curve was proven if asked). */
    PROVEN,

    /** At least one requested block was NOT_HONORED or UNAVAILABLE. */
    NOT_PROVEN,

    /** No ISP-off request was active — bypass was not engaged. */
    NOT_APPLICABLE
}

internal enum class IspBypassGeometry(val label: String) {
    SENSOR_NATIVE("SENSOR_NATIVE"),
    CORRECTED("CORRECTED"),
    UNKNOWN("UNKNOWN")
}

/**
 * The ISP-block modes the final [CaptureRequest] actually asked for. A `null` field means the key
 * was not set on the request (e.g. the device did not advertise the OFF mode so the writer skipped
 * it), which is distinct from "requested but the device reported something else".
 */
internal data class IspBypassRequestedModes(
    val edgeMode: Int?,
    val noiseReductionMode: Int?,
    val hotPixelMode: Int?,
    val shadingMode: Int?,
    val colorAberrationMode: Int?,
    val tonemapMode: Int?,
    val distortionCorrectionMode: Int?,
    /** True when the request also attached a linear 1:1 tonemap curve (identity proof expected). */
    val expectsLinearTonemapCurve: Boolean = false
) {
    /**
     * A request only counts as a *sensor-native bypass* claim when it actually asks the two
     * information-destroying core blocks (edge enhancement + noise reduction) OFF. A processed
     * HIGH_QUALITY request is not a bypass and must never be reported as one.
     */
    val isBypassRequest: Boolean
        get() = edgeMode == CaptureRequest.EDGE_MODE_OFF &&
            noiseReductionMode == CaptureRequest.NOISE_REDUCTION_MODE_OFF
}

/**
 * The ISP-block modes the `TotalCaptureResult` reported. A `null` field means the key was absent
 * from the result (the HAL did not report it), which lands as [IspBypassBlockVerdict.UNAVAILABLE].
 */
internal data class IspBypassReportedModes(
    val edgeMode: Int?,
    val noiseReductionMode: Int?,
    val hotPixelMode: Int?,
    val shadingMode: Int?,
    val colorAberrationMode: Int?,
    val tonemapMode: Int?,
    val distortionCorrectionMode: Int?,
    /** Whether the reported tonemap curve is the identity ramp (null = absent / not evaluated). */
    val tonemapCurveLinear: Boolean? = null
)

internal data class IspBypassBlockResult(
    val block: IspBypassBlock,
    val verdict: IspBypassBlockVerdict,
    val requestedMode: Int?,
    val reportedMode: Int?,
    val detail: String
)

internal data class IspBypassVerificationReport(
    val aggregate: IspBypassHonestyVerdict,
    val blocks: List<IspBypassBlockResult>,
    val linearTonemapProven: Boolean,
    val geometry: IspBypassGeometry,
    val warning: String?
) {
    val proven: Boolean get() = aggregate == IspBypassHonestyVerdict.PROVEN

    fun verdictOf(block: IspBypassBlock): IspBypassBlockVerdict =
        blocks.firstOrNull { it.block == block }?.verdict ?: IspBypassBlockVerdict.NOT_REQUESTED

    val summary: String
        get() = "ispBypass=$aggregate geometry=${geometry.label} linearTonemap=$linearTonemapProven " +
            blocks.joinToString(" ") { "${it.block.label}=${it.verdict}" }

    fun toLogLine(): String = summary
}

internal object CameraIspBypassVerificationPolicy {

    /**
     * Verify requested-vs-honored ISP-block modes. Pure: no Android instance dependency.
     *
     * Aggregate is [IspBypassHonestyVerdict.NOT_APPLICABLE] whenever the request is not a genuine
     * sensor-native bypass (see [IspBypassRequestedModes.isBypassRequest]); otherwise it is
     * [IspBypassHonestyVerdict.PROVEN] only when every requested block read back HONORED and, if a
     * linear tonemap curve was requested, that curve was proven to be the identity ramp.
     */
    fun verify(
        requested: IspBypassRequestedModes,
        reported: IspBypassReportedModes
    ): IspBypassVerificationReport {
        if (!requested.isBypassRequest) {
            return IspBypassVerificationReport(
                aggregate = IspBypassHonestyVerdict.NOT_APPLICABLE,
                blocks = IspBypassBlock.entries.map {
                    IspBypassBlockResult(it, IspBypassBlockVerdict.NOT_REQUESTED, null, null, "bypass not engaged")
                },
                linearTonemapProven = false,
                geometry = geometryOf(requested.distortionCorrectionMode),
                warning = null
            )
        }

        val blocks = listOf(
            evaluate(IspBypassBlock.EDGE, requested.edgeMode, reported.edgeMode),
            evaluate(IspBypassBlock.NOISE_REDUCTION, requested.noiseReductionMode, reported.noiseReductionMode),
            evaluate(IspBypassBlock.HOT_PIXEL, requested.hotPixelMode, reported.hotPixelMode),
            evaluate(IspBypassBlock.SHADING, requested.shadingMode, reported.shadingMode),
            evaluate(IspBypassBlock.COLOR_ABERRATION, requested.colorAberrationMode, reported.colorAberrationMode),
            evaluateTonemap(requested, reported),
            evaluate(IspBypassBlock.DISTORTION_CORRECTION, requested.distortionCorrectionMode, reported.distortionCorrectionMode)
        )

        val requestedBlocks = blocks.filter { it.verdict != IspBypassBlockVerdict.NOT_REQUESTED }
        val tonemapVerdict = blocks.first { it.block == IspBypassBlock.TONEMAP }.verdict
        val linearTonemapProven = !requested.expectsLinearTonemapCurve ||
            (tonemapVerdict == IspBypassBlockVerdict.HONORED && reported.tonemapCurveLinear == true)

        // requestedBlocks always contains EDGE + NOISE_REDUCTION here (isBypassRequest guarantees both
        // are the non-null OFF constants), so it is never empty; NOT_APPLICABLE is only ever produced
        // by the pre-gate early return above, leaving the engaged-bypass aggregate binary.
        val aggregate = if (requestedBlocks.all { it.verdict == IspBypassBlockVerdict.HONORED } && linearTonemapProven) {
            IspBypassHonestyVerdict.PROVEN
        } else {
            IspBypassHonestyVerdict.NOT_PROVEN
        }

        return IspBypassVerificationReport(
            aggregate = aggregate,
            blocks = blocks,
            linearTonemapProven = linearTonemapProven,
            // HONEST-GATES: the recorded geometry reflects what the device REPORTED, not what we asked
            // for. If the HAL silently re-enabled correction (reported FAST/HIGH_QUALITY), this is
            // CORRECTED; if the result carried no distortion value, it is UNKNOWN — never a SENSOR_NATIVE
            // claim the read-back can falsify.
            geometry = geometryOf(reported.distortionCorrectionMode),
            warning = warningFor(aggregate, requestedBlocks, linearTonemapProven)
        )
    }

    /** Map a resolved/reported DISTORTION_CORRECTION_MODE to the recorded geometry label. */
    fun geometryOf(distortionCorrectionMode: Int?): IspBypassGeometry = when (distortionCorrectionMode) {
        null -> IspBypassGeometry.UNKNOWN
        CaptureRequest.DISTORTION_CORRECTION_MODE_OFF -> IspBypassGeometry.SENSOR_NATIVE
        else -> IspBypassGeometry.CORRECTED // FAST / HIGH_QUALITY are both corrected geometry
    }

    private fun evaluate(block: IspBypassBlock, requested: Int?, reported: Int?): IspBypassBlockResult {
        val verdict = when {
            requested == null -> IspBypassBlockVerdict.NOT_REQUESTED
            reported == null -> IspBypassBlockVerdict.UNAVAILABLE
            reported == requested -> IspBypassBlockVerdict.HONORED
            else -> IspBypassBlockVerdict.NOT_HONORED
        }
        return IspBypassBlockResult(block, verdict, requested, reported, detailFor(verdict, requested, reported))
    }

    private fun evaluateTonemap(
        requested: IspBypassRequestedModes,
        reported: IspBypassReportedModes
    ): IspBypassBlockResult {
        val base = evaluate(IspBypassBlock.TONEMAP, requested.tonemapMode, reported.tonemapMode)
        // A reported CONTRAST_CURVE tonemap is a no-op only if its curve is the identity ramp; a
        // non-identity reported curve is active tone shaping and must not count as honored bypass even
        // when the mode matches what we requested. Keyed off the *reported* mode/curve (not on whether
        // we asked for a linear curve) so a device that re-enables tone shaping can never read PROVEN.
        if (base.verdict == IspBypassBlockVerdict.HONORED &&
            reported.tonemapMode == CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE &&
            reported.tonemapCurveLinear == false
        ) {
            return base.copy(
                verdict = IspBypassBlockVerdict.NOT_HONORED,
                detail = "tonemap mode honored but reported curve is not the identity ramp"
            )
        }
        return base
    }

    private fun detailFor(verdict: IspBypassBlockVerdict, requested: Int?, reported: Int?): String =
        when (verdict) {
            IspBypassBlockVerdict.HONORED -> "requested=$requested reported=$reported"
            IspBypassBlockVerdict.NOT_HONORED ->
                "requested=$requested but device reported=$reported (silently re-enabled/overridden)"
            IspBypassBlockVerdict.UNAVAILABLE ->
                "requested=$requested but result carried no value (cannot prove)"
            IspBypassBlockVerdict.NOT_REQUESTED -> "no mode requested"
        }

    private fun warningFor(
        aggregate: IspBypassHonestyVerdict,
        requestedBlocks: List<IspBypassBlockResult>,
        linearTonemapProven: Boolean
    ): String? {
        if (aggregate != IspBypassHonestyVerdict.NOT_PROVEN) return null
        val offenders = requestedBlocks
            .filter {
                it.verdict == IspBypassBlockVerdict.NOT_HONORED ||
                    it.verdict == IspBypassBlockVerdict.UNAVAILABLE
            }
            .map { "${it.block.label}=${it.verdict}" }
            .toMutableList()
        if (!linearTonemapProven && offenders.none { it.startsWith(IspBypassBlock.TONEMAP.label) }) {
            offenders += "TONEMAP_CURVE=NOT_LINEAR"
        }
        val detail = if (offenders.isEmpty()) "tonemap curve unverified" else offenders.joinToString(", ")
        return "Sensor-native ISP bypass NOT PROVEN: $detail. " +
            "Device honored only public blocks; bypass claim downgraded."
    }
}
