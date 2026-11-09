package com.neoproc.neoproccamx.recording

/**
 * s8-f06 / CP-Q8 — the JVM-testable real-feed seam that closes the measurement→gate loop with real
 * decoded frames. A device-side decode step (MediaExtractor → MediaCodec → ImageReader over the
 * just-recorded / exported master, wired separately behind [EliteVisionMeasurementFlag]) produces the
 * already-decoded chart-frame buffers and hands them in as
 * [EliteVisionMeasurementHarness.GoldenFrames]. This provider carries NO Android import, so the full
 * producer → [EliteVisionMeasurementHarness.measure] → [RecordingQualityEvidencePolicy] path can be
 * exercised end-to-end under the pure-JVM unit suite with a deterministic decoded fixture.
 *
 * Honesty contract: [canMeasureAllRequiredCriteria] must be true ONLY when a real chart frame was
 * actually present in the decoded buffers — the slanted edge needed for MTF50 and the ColorChecker
 * patches needed for ΔE2000. A generic decoded scene frame has no chart, so a caller over such frames
 * must construct this provider with `canMeasureAllRequiredCriteria = false`, and
 * [EliteVisionProductionMeasurement] then emits an honest NOT_PROVEN instead of threshold-testing
 * carried-over baseline values.
 */
internal class EliteVisionByteBufferFrameProvider(
    private val frames: EliteVisionMeasurementHarness.GoldenFrames?,
    override val canMeasureAllRequiredCriteria: Boolean,
    override val producerLabel: String = LABEL_DECODED
) : EliteVisionDecodedFrameProvider {

    override fun framesOrNull(): EliteVisionMeasurementHarness.GoldenFrames? = frames

    companion object {
        /** Provenance for frames decoded from a real recorded/exported NPV10/HEVC master. */
        const val LABEL_DECODED = "NPV10_EXPORT_DECODE"
    }
}
