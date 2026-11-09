package com.neoproc.neoproccamx.recording

import java.util.Locale

/**
 * F01 / CP-QV1 — honest throughput-budget + drop-ratio classifier for the P010 record path.
 *
 * Turns a live [RecordingManager.P010ImageIngressSnapshot] (the per-session sensor->encoder ingress
 * telemetry) into buyer-facing throughput truth for the "> ₹10,000" G1 gate:
 *  - the dropped-frame RATIO (G1 wants < 1% at 4K30),
 *  - whether the per-frame CPU copy fits the frame-rate cadence budget (copy-bound vs within-budget),
 *  - WHY a slow copy is slow — a native-lib-load failure using the slow JVM fallback, vs an uncached
 *    native readout, vs outright copy failures,
 *  - an HONEST, human-readable cap label so a copy-bound session is never presented as if it ran at
 *    the requested fps (standing order: never a silent frame drop).
 *
 * This is pure math over an already-captured snapshot — no Android types, no device access — so it is
 * unit-testable against the exact fixtures in RecordingP010ImageIngressTelemetryTest. The actual copy
 * speed-up (making the ~331 ms native sensor readout faster) is DEVICE-GATED work in
 * npv10_raw_native.cpp and is out of scope here: this is the measurement + honest-labeling instrument
 * that G1 acceptance ("proven from frameDropCount + encoder timestamps") requires, nothing more.
 *
 * No-downgrade: emitting these verdicts is purely additive evidence metadata; it changes no pixel and
 * no existing evidence key.
 */
internal object RecordingThroughputBudgetPolicy {
    /** G1 acceptance ceiling: a sustained recording must drop strictly fewer than 1% of frames. */
    const val TARGET_MAX_DROP_RATIO = 0.01

    /**
     * How much of a single frame's cadence interval the average copy may consume before the session
     * can no longer sustain the requested fps. A copy that fills the whole interval already starves
     * the next frame, so the budget is the full interval (1.0).
     */
    const val COPY_BUDGET_INTERVAL_FRACTION = 1.0

    enum class CopyBudgetVerdict {
        /** No copies recorded yet (idle, or a Surface/EGL input path that never copies). */
        NO_COPY_DATA,

        /** Average copy fits inside the frame cadence: the copy is not the throughput bottleneck. */
        WITHIN_BUDGET,

        /** Average copy exceeds the frame cadence: sustained fps is copy-bound. */
        COPY_BOUND
    }

    enum class CopyPathDiagnosis {
        /** No copies recorded yet. */
        NO_COPY_DATA,

        /** Copies failed outright (payload unavailable) — a correctness fault, not merely slowness. */
        COPY_FAILURES_PRESENT,

        /**
         * The native copy library was unavailable, so every copy fell through to the slow
         * direct-ByteBuffer JVM path. The lever is loading libneoproc_vulkan, not the readout.
         */
        NATIVE_UNAVAILABLE_FALLBACK,

        /** The native path was used but the average copy still exceeds cadence — uncached readout. */
        NATIVE_SLOW_READOUT,

        /** The native path was used and stayed within cadence — healthy. */
        NATIVE_HEALTHY
    }

    data class ThroughputBudgetVerdict(
        val requestedFps: Int,
        val deliveredFrames: Long,
        val droppedFrames: Long,
        val dropRatio: Double,
        val dropRatioWithinTarget: Boolean,
        val frameTimeBudgetUs: Long,
        val averageCopyUs: Long,
        val maxCopyUs: Long,
        val copyBudgetVerdict: CopyBudgetVerdict,
        val copyPathDiagnosis: CopyPathDiagnosis,
        val sustainableFps: Int,
        val honestCapLabel: String
    ) {
        /**
         * True only when copies were actually measured within cadence AND drops stay under the G1
         * target. An unmeasured session (NO_COPY_DATA) is honestly NOT a sustain claim.
         */
        val sustainsRequestedFps: Boolean
            get() = copyBudgetVerdict == CopyBudgetVerdict.WITHIN_BUDGET && dropRatioWithinTarget

        fun toEvidenceMetadata(): Map<String, String> = linkedMapOf(
            "byteBufferP010ThroughputBudgetPolicy" to "true",
            "byteBufferP010RequestedFps" to requestedFps.toString(),
            "byteBufferP010DeliveredFrames" to deliveredFrames.toString(),
            "byteBufferP010DroppedFrames" to droppedFrames.toString(),
            "byteBufferP010DropRatio" to dropRatio.evidenceRatio(),
            "byteBufferP010DropRatioWithinTarget" to dropRatioWithinTarget.toString(),
            "byteBufferP010FrameTimeBudgetUs" to frameTimeBudgetUs.toString(),
            "byteBufferP010CopyBudgetVerdict" to copyBudgetVerdict.name,
            "byteBufferP010CopyPathDiagnosis" to copyPathDiagnosis.name,
            "byteBufferP010SustainableFps" to sustainableFps.toString(),
            "byteBufferP010SustainsRequestedFps" to sustainsRequestedFps.toString(),
            "byteBufferP010ThroughputHonestCap" to honestCapLabel
        )
    }

    fun evaluate(
        snapshot: RecordingManager.P010ImageIngressSnapshot,
        requestedFps: Int
    ): ThroughputBudgetVerdict {
        val fps = requestedFps.coerceAtLeast(1)
        val delivered = snapshot.callbackFrames.coerceAtLeast(0L)
        val dropped = (
            snapshot.admissionRejectedFrames +
                snapshot.processingDroppedFrames +
                snapshot.copyFailures
            ).coerceAtLeast(0L)
        val dropRatio = if (delivered > 0L) {
            (dropped.toDouble() / delivered.toDouble()).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        val frameTimeBudgetUs = 1_000_000L / fps
        val averageCopyUs = snapshot.averageCopyUs.coerceAtLeast(0L)
        val totalCopies =
            snapshot.nativeDirectCopies + snapshot.directBufferCopies + snapshot.copyFailures

        val copyBudgetVerdict = when {
            totalCopies <= 0L || averageCopyUs <= 0L -> CopyBudgetVerdict.NO_COPY_DATA
            averageCopyUs.toDouble() > frameTimeBudgetUs.toDouble() * COPY_BUDGET_INTERVAL_FRACTION ->
                CopyBudgetVerdict.COPY_BOUND
            else -> CopyBudgetVerdict.WITHIN_BUDGET
        }

        val copyPathDiagnosis = when {
            totalCopies <= 0L -> CopyPathDiagnosis.NO_COPY_DATA
            snapshot.copyFailures > 0L &&
                snapshot.copyFailures >= snapshot.nativeDirectCopies + snapshot.directBufferCopies ->
                CopyPathDiagnosis.COPY_FAILURES_PRESENT
            snapshot.nativeDirectCopies <= 0L && snapshot.directBufferCopies > 0L ->
                CopyPathDiagnosis.NATIVE_UNAVAILABLE_FALLBACK
            copyBudgetVerdict == CopyBudgetVerdict.COPY_BOUND ->
                CopyPathDiagnosis.NATIVE_SLOW_READOUT
            else -> CopyPathDiagnosis.NATIVE_HEALTHY
        }

        // Honest sustainable rate the measured copy can actually feed. Never claims more than the
        // requested fps (a fast copy just means we are not copy-limited), never below 1.
        val sustainableFps = if (averageCopyUs > 0L) {
            (1_000_000L / averageCopyUs).toInt().coerceIn(1, fps)
        } else {
            fps
        }

        return ThroughputBudgetVerdict(
            requestedFps = fps,
            deliveredFrames = delivered,
            droppedFrames = dropped,
            dropRatio = dropRatio,
            dropRatioWithinTarget = dropRatio < TARGET_MAX_DROP_RATIO,
            frameTimeBudgetUs = frameTimeBudgetUs,
            averageCopyUs = averageCopyUs,
            maxCopyUs = snapshot.maxCopyUs.coerceAtLeast(0L),
            copyBudgetVerdict = copyBudgetVerdict,
            copyPathDiagnosis = copyPathDiagnosis,
            sustainableFps = sustainableFps,
            honestCapLabel = buildHonestCapLabel(
                fps = fps,
                copyBudgetVerdict = copyBudgetVerdict,
                copyPathDiagnosis = copyPathDiagnosis,
                averageCopyUs = averageCopyUs,
                sustainableFps = sustainableFps
            )
        )
    }

    private fun buildHonestCapLabel(
        fps: Int,
        copyBudgetVerdict: CopyBudgetVerdict,
        copyPathDiagnosis: CopyPathDiagnosis,
        averageCopyUs: Long,
        sustainableFps: Int
    ): String = when (copyBudgetVerdict) {
        CopyBudgetVerdict.NO_COPY_DATA -> "Throughput not yet measured"
        CopyBudgetVerdict.WITHIN_BUDGET -> "Sustained ${fps}fps within copy budget"
        CopyBudgetVerdict.COPY_BOUND -> {
            val reason = when (copyPathDiagnosis) {
                CopyPathDiagnosis.NATIVE_UNAVAILABLE_FALLBACK ->
                    "native copy unavailable, using slow JVM fallback"
                CopyPathDiagnosis.COPY_FAILURES_PRESENT -> "P010 copy failures"
                else -> "copy-bound sensor readout"
            }
            val avgMs = String.format(Locale.US, "%.0f", averageCopyUs / 1_000.0)
            "Capped to ~${sustainableFps}fps (requested ${fps}fps): $reason at ${avgMs}ms/frame"
        }
    }
}

private fun Double.evidenceRatio(): String = String.format(Locale.US, "%.5f", this)
