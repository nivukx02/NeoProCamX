package com.neoproc.neoproccamx.camera

import kotlin.math.abs

internal enum class Camera2LineageEndpoint {
    PREVIEW,
    ENCODER
}

internal data class Camera2RequestTag(
    val sessionGeneration: Long,
    val requestRevision: Long,
    val targetRoles: Set<String>,
    val pipelineSignature: String
)

internal data class Camera2CaptureFrameEvidence(
    val sessionGeneration: Long,
    val requestRevision: Long,
    val frameNumber: Long,
    val sensorTimestampNs: Long,
    val sensorFrameDurationNs: Long?,
    val exposureTimeNs: Long?,
    val focusDistanceDiopters: Float?,
    val whiteBalanceMode: String?,
    val requestTag: Camera2RequestTag
)

internal data class Camera2EndpointSubmissionEvidence(
    val endpoint: Camera2LineageEndpoint,
    val sessionGeneration: Long,
    val sensorTimestampNs: Long,
    val presentationTimestampNs: Long,
    val frameNumber: Long?,
    val accepted: Boolean
)

internal data class Camera2FrameLineageSnapshot(
    val strictCertificatePassed: Boolean,
    val strictCertificateFailureCodes: List<String>,
    val expectedSessionGeneration: Long,
    val minimumFrames: Int,
    val minimumDurationNs: Long,
    val minimumCaptureImageTimestampMatchRatio: Double,
    val timestampMatchToleranceNs: Long,
    val maximumPreviewEncoderSubmittedDifference: Int,
    val captureResultCount: Int,
    val imageTimestampCount: Int,
    val previewSubmissionCount: Int,
    val encoderSubmissionCount: Int,
    val matchedCaptureImageCount: Int,
    val captureImageTimestampMatchRatio: Double,
    val evidenceWindowDurationNs: Long,
    val staleGenerationCount: Int,
    val nonMonotonicTimestampCount: Int,
    val duplicateEncoderSubmissionCount: Int,
    val previewEndpointOmissionCount: Int,
    val encoderEndpointOmissionCount: Int,
    val endpointOmissionCount: Int,
    val previewEncoderSubmittedDifference: Int,
    val commonEndpointWindowApplied: Boolean,
    val excludedBoundaryCaptureResultCount: Int,
    val captureNonMonotonicTimestampCount: Int,
    val imageNonMonotonicTimestampCount: Int,
    val previewNonMonotonicTimestampCount: Int,
    val encoderNonMonotonicTimestampCount: Int,
    val firstNonMonotonicStream: String?,
    val firstNonMonotonicPreviousTimestampNs: Long?,
    val firstNonMonotonicTimestampNs: Long?,
    val firstPreviewEndpointOmissionTimestampNs: Long?,
    val firstEncoderEndpointOmissionTimestampNs: Long?,
    val firstSensorTimestampNs: Long?,
    val lastSensorTimestampNs: Long?,
    val firstPreviewSensorTimestampNs: Long?,
    val lastPreviewSensorTimestampNs: Long?,
    val firstEncoderSensorTimestampNs: Long?,
    val lastEncoderSensorTimestampNs: Long?
) {
    fun toCompactMetadataMap(): Map<String, String> {
        val evidenceWindowDurationMs = nsToCeilMs(evidenceWindowDurationNs)
        return linkedMapOf(
            "camera2FrameLineageCertified" to strictCertificatePassed.toString(),
            "camera2FrameLineageFrameCount" to captureResultCount.toString(),
            "camera2FrameLineageDurationMs" to evidenceWindowDurationMs.toString(),
            "camera2FrameLineageMatchRatio" to captureImageTimestampMatchRatio.toString(),
            "camera2FrameLineageStaleGenerationCount" to staleGenerationCount.toString(),
            "camera2FrameLineageNonMonotonicTimestampCount" to nonMonotonicTimestampCount.toString(),
            "camera2FrameLineageDuplicateEncoderSubmissionCount" to
                duplicateEncoderSubmissionCount.toString(),
            "camera2FrameLineageEndpointOmissionCount" to endpointOmissionCount.toString(),
            "camera2FrameLineagePreviewEncoderFrameDifference" to
                previewEncoderSubmittedDifference.toString(),
            "camera2FrameLineageCommonEndpointWindowApplied" to
                commonEndpointWindowApplied.toString(),
            "camera2FrameLineageExcludedBoundaryCaptureResultCount" to
                excludedBoundaryCaptureResultCount.toString(),
            "camera2FrameLineageFirstPreviewEndpointOmissionTimestampNs" to
                (firstPreviewEndpointOmissionTimestampNs?.toString() ?: ""),
            "camera2FrameLineageFirstEncoderEndpointOmissionTimestampNs" to
                (firstEncoderEndpointOmissionTimestampNs?.toString() ?: ""),
            "camera2FrameLineageCertificatePresent" to "true",
            "camera2FrameLineageStrictCertificatePassed" to strictCertificatePassed.toString(),
            "camera2FrameLineageEvidenceWindowFrames" to captureResultCount.toString(),
            "camera2FrameLineageEvidenceWindowDurationMs" to evidenceWindowDurationMs.toString(),
            "camera2FrameLineageTimestampMatchRatio" to captureImageTimestampMatchRatio.toString(),
            "camera2FrameLineagePreviewEndpointOmissionCount" to
                previewEndpointOmissionCount.toString(),
            "camera2FrameLineageEncoderEndpointOmissionCount" to
                encoderEndpointOmissionCount.toString(),
            "camera2FrameLineageSubmittedFrameDifference" to
                previewEncoderSubmittedDifference.toString(),
            "camera2FrameLineageFailureCodes" to strictCertificateFailureCodes.joinToString("|"),
            "camera2FrameLineage.strictCertificatePassed" to strictCertificatePassed.toString(),
            "camera2FrameLineage.failureCodes" to strictCertificateFailureCodes.joinToString("|"),
            "camera2FrameLineage.expectedSessionGeneration" to expectedSessionGeneration.toString(),
            "camera2FrameLineage.minimumFrames" to minimumFrames.toString(),
            "camera2FrameLineage.minimumDurationNs" to minimumDurationNs.toString(),
            "camera2FrameLineage.minimumCaptureImageTimestampMatchRatio" to
                minimumCaptureImageTimestampMatchRatio.toString(),
            "camera2FrameLineage.timestampMatchToleranceNs" to
                timestampMatchToleranceNs.toString(),
            "camera2FrameLineage.maximumPreviewEncoderSubmittedDifference" to
                maximumPreviewEncoderSubmittedDifference.toString(),
            "camera2FrameLineage.captureResultCount" to captureResultCount.toString(),
            "camera2FrameLineage.imageTimestampCount" to imageTimestampCount.toString(),
            "camera2FrameLineage.previewSubmissionCount" to previewSubmissionCount.toString(),
            "camera2FrameLineage.encoderSubmissionCount" to encoderSubmissionCount.toString(),
            "camera2FrameLineage.matchedCaptureImageCount" to matchedCaptureImageCount.toString(),
            "camera2FrameLineage.captureImageTimestampMatchRatio" to
                captureImageTimestampMatchRatio.toString(),
            "camera2FrameLineage.evidenceWindowDurationNs" to evidenceWindowDurationNs.toString(),
            "camera2FrameLineage.staleGenerationCount" to staleGenerationCount.toString(),
            "camera2FrameLineage.nonMonotonicTimestampCount" to nonMonotonicTimestampCount.toString(),
            "camera2FrameLineage.duplicateEncoderSubmissionCount" to
                duplicateEncoderSubmissionCount.toString(),
            "camera2FrameLineage.previewEndpointOmissionCount" to
                previewEndpointOmissionCount.toString(),
            "camera2FrameLineage.encoderEndpointOmissionCount" to
                encoderEndpointOmissionCount.toString(),
            "camera2FrameLineage.endpointOmissionCount" to endpointOmissionCount.toString(),
            "camera2FrameLineage.previewEncoderSubmittedDifference" to
                previewEncoderSubmittedDifference.toString(),
            "camera2FrameLineage.commonEndpointWindowApplied" to
                commonEndpointWindowApplied.toString(),
            "camera2FrameLineage.excludedBoundaryCaptureResultCount" to
                excludedBoundaryCaptureResultCount.toString(),
            "camera2FrameLineage.captureNonMonotonicTimestampCount" to
                captureNonMonotonicTimestampCount.toString(),
            "camera2FrameLineage.imageNonMonotonicTimestampCount" to
                imageNonMonotonicTimestampCount.toString(),
            "camera2FrameLineage.previewNonMonotonicTimestampCount" to
                previewNonMonotonicTimestampCount.toString(),
            "camera2FrameLineage.encoderNonMonotonicTimestampCount" to
                encoderNonMonotonicTimestampCount.toString(),
            "camera2FrameLineage.firstNonMonotonicStream" to
                (firstNonMonotonicStream ?: ""),
            "camera2FrameLineage.firstNonMonotonicPreviousTimestampNs" to
                (firstNonMonotonicPreviousTimestampNs?.toString() ?: ""),
            "camera2FrameLineage.firstNonMonotonicTimestampNs" to
                (firstNonMonotonicTimestampNs?.toString() ?: ""),
            "camera2FrameLineage.firstPreviewEndpointOmissionTimestampNs" to
                (firstPreviewEndpointOmissionTimestampNs?.toString() ?: ""),
            "camera2FrameLineage.firstEncoderEndpointOmissionTimestampNs" to
                (firstEncoderEndpointOmissionTimestampNs?.toString() ?: ""),
            "camera2FrameLineage.firstSensorTimestampNs" to (firstSensorTimestampNs?.toString() ?: ""),
            "camera2FrameLineage.lastSensorTimestampNs" to (lastSensorTimestampNs?.toString() ?: ""),
            "camera2FrameLineage.firstPreviewSensorTimestampNs" to
                (firstPreviewSensorTimestampNs?.toString() ?: ""),
            "camera2FrameLineage.lastPreviewSensorTimestampNs" to
                (lastPreviewSensorTimestampNs?.toString() ?: ""),
            "camera2FrameLineage.firstEncoderSensorTimestampNs" to
                (firstEncoderSensorTimestampNs?.toString() ?: ""),
            "camera2FrameLineage.lastEncoderSensorTimestampNs" to
                (lastEncoderSensorTimestampNs?.toString() ?: "")
        )
    }

    private fun nsToCeilMs(durationNs: Long): Long =
        if (durationNs <= 0L) 0L else (durationNs + 999_999L) / 1_000_000L
}

internal class Camera2FrameLineageAccumulator(
    private val expectedSessionGeneration: Long,
    private val minimumFrames: Int = STRICT_MINIMUM_FRAMES,
    private val minimumDurationNs: Long = STRICT_MINIMUM_DURATION_NS,
    private val minimumCaptureImageTimestampMatchRatio: Double =
        STRICT_MINIMUM_CAPTURE_IMAGE_TIMESTAMP_MATCH_RATIO,
    private val maximumPreviewEncoderSubmittedDifference: Int =
        STRICT_MAXIMUM_PREVIEW_ENCODER_SUBMITTED_DIFFERENCE
) {
    private val captureResults = mutableListOf<Camera2CaptureFrameEvidence>()
    private val imageTimestamps = mutableListOf<ImageTimestampEvidence>()
    private val endpointSubmissions = mutableListOf<Camera2EndpointSubmissionEvidence>()

    fun recordCaptureResult(evidence: Camera2CaptureFrameEvidence) {
        captureResults += evidence
    }

    fun recordImageTimestamp(
        sensorTimestampNs: Long,
        sessionGeneration: Long = expectedSessionGeneration
    ) {
        // Preview presentation and encoder acceptance are deliberately reported by separate
        // asynchronous owners. On devices whose Image timestamps use MONOTONIC while Camera2
        // results use BOOTTIME, translating those two callbacks can differ by a few microseconds.
        // They are still observations of one physical sensor frame. Coalesce them using the same
        // strict clock-domain tolerance used by the one-to-one certificate join; adjacent camera
        // frames (40 ms at 25 fps) remain far outside this window and can never be merged.
        if (imageTimestamps.any {
                it.sessionGeneration == sessionGeneration &&
                    timestampsWithinTolerance(
                        firstTimestampNs = it.sensorTimestampNs,
                        secondTimestampNs = sensorTimestampNs,
                        toleranceNs = TIMESTAMP_MATCH_TOLERANCE_NS
                    )
            }
        ) {
            return
        }
        imageTimestamps += ImageTimestampEvidence(
            sessionGeneration = sessionGeneration,
            sensorTimestampNs = sensorTimestampNs
        )
    }

    fun recordPreviewSubmission(
        sensorTimestampNs: Long,
        presentationTimestampNs: Long,
        frameNumber: Long? = null,
        sessionGeneration: Long = expectedSessionGeneration,
        accepted: Boolean = true
    ) {
        if (endpointSubmissions.any {
                it.endpoint == Camera2LineageEndpoint.PREVIEW &&
                    it.sessionGeneration == sessionGeneration &&
                    it.sensorTimestampNs == sensorTimestampNs &&
                    it.accepted == accepted
            }
        ) {
            return
        }
        recordEndpointSubmission(
            Camera2EndpointSubmissionEvidence(
                endpoint = Camera2LineageEndpoint.PREVIEW,
                sessionGeneration = sessionGeneration,
                sensorTimestampNs = sensorTimestampNs,
                presentationTimestampNs = presentationTimestampNs,
                frameNumber = frameNumber,
                accepted = accepted
            )
        )
    }

    fun recordEncoderSubmission(
        sensorTimestampNs: Long,
        presentationTimestampNs: Long,
        frameNumber: Long? = null,
        sessionGeneration: Long = expectedSessionGeneration,
        accepted: Boolean = true
    ) {
        recordEndpointSubmission(
            Camera2EndpointSubmissionEvidence(
                endpoint = Camera2LineageEndpoint.ENCODER,
                sessionGeneration = sessionGeneration,
                sensorTimestampNs = sensorTimestampNs,
                presentationTimestampNs = presentationTimestampNs,
                frameNumber = frameNumber,
                accepted = accepted
            )
        )
    }

    fun recordEndpointSubmission(evidence: Camera2EndpointSubmissionEvidence) {
        endpointSubmissions += evidence
    }

    fun validateStrictCertificate(): Camera2FrameLineageSnapshot {
        val currentCaptureResults = captureResults.filter { it.isCurrentGeneration() }
        val currentImageTimestamps = imageTimestamps
            .filter { it.sessionGeneration == expectedSessionGeneration }
            .map { it.sensorTimestampNs }
        val currentPreviewSubmissions = endpointSubmissions
            .filter {
                it.endpoint == Camera2LineageEndpoint.PREVIEW &&
                    it.sessionGeneration == expectedSessionGeneration &&
                    it.accepted
            }
        val currentEncoderSubmissions = endpointSubmissions
            .filter {
                it.endpoint == Camera2LineageEndpoint.ENCODER &&
                    it.sessionGeneration == expectedSessionGeneration &&
                    it.accepted
            }
        // Camera2 capture results can lead the first compositor submission and trail the last accepted
        // writer packet. Those boundary-only frames never belonged to the common preview/recording
        // observation interval and must not be called endpoint omissions. Restrict certification to the
        // inclusive timestamp overlap of both endpoints; a missing frame inside that interval still fails.
        val commonEndpointWindow = commonEndpointTimestampWindow(
            previewSubmissions = currentPreviewSubmissions,
            encoderSubmissions = currentEncoderSubmissions
        )
        val evidenceCaptureResults = currentCaptureResults.within(commonEndpointWindow)
        val evidenceImageTimestamps = currentImageTimestamps.within(commonEndpointWindow)
        val evidencePreviewSubmissions = currentPreviewSubmissions.within(commonEndpointWindow)
        val evidenceEncoderSubmissions = currentEncoderSubmissions.within(commonEndpointWindow)
        val captureTimestamps = evidenceCaptureResults
            .map { it.sensorTimestampNs }
            .distinct()
        val imageTimestampSet = evidenceImageTimestamps.distinct()
        val previewTimestampSet = evidencePreviewSubmissions
            .map { it.sensorTimestampNs }
            .distinct()
        val encoderTimestampSet = evidenceEncoderSubmissions
            .map { it.sensorTimestampNs }
            .distinct()
        val matchedCaptureImageCount = countTimestampMatches(
            captureTimestamps,
            imageTimestampSet,
            TIMESTAMP_MATCH_TOLERANCE_NS
        )
        val captureImageMatchRatio = if (captureTimestamps.isEmpty()) {
            0.0
        } else {
            matchedCaptureImageCount.toDouble() / captureTimestamps.size.toDouble()
        }
        val previewEndpointOmissionCount = captureTimestamps.size - countTimestampMatches(
            captureTimestamps,
            previewTimestampSet,
            TIMESTAMP_MATCH_TOLERANCE_NS
        )
        val encoderEndpointOmissionCount = captureTimestamps.size - countTimestampMatches(
            captureTimestamps,
            encoderTimestampSet,
            TIMESTAMP_MATCH_TOLERANCE_NS
        )
        val endpointOmissionCount = previewEndpointOmissionCount + encoderEndpointOmissionCount
        val staleGenerationCount = captureResults.count { !it.isCurrentGeneration() } +
            imageTimestamps.count { it.sessionGeneration != expectedSessionGeneration } +
            endpointSubmissions.count { it.sessionGeneration != expectedSessionGeneration }
        val monotonicityByStream = listOf(
            "CAPTURE_RESULT" to analyzeMonotonicity(
                evidenceCaptureResults.map { it.sensorTimestampNs }
            ),
            "IMAGE" to analyzeMonotonicity(evidenceImageTimestamps),
            "PREVIEW" to analyzeMonotonicity(
                evidencePreviewSubmissions.map { it.sensorTimestampNs }
            ),
            "ENCODER" to analyzeMonotonicity(
                evidenceEncoderSubmissions.map { it.sensorTimestampNs }
            )
        )
        val captureMonotonicity = monotonicityByStream[0].second
        val imageMonotonicity = monotonicityByStream[1].second
        val previewMonotonicity = monotonicityByStream[2].second
        val encoderMonotonicity = monotonicityByStream[3].second
        val nonMonotonicTimestampCount = monotonicityByStream.sumOf { it.second.count }
        val firstNonMonotonic = monotonicityByStream.firstOrNull { it.second.count > 0 }
        val firstPreviewEndpointOmissionTimestampNs = firstUnmatchedTimestamp(
            expected = captureTimestamps,
            observed = previewTimestampSet,
            toleranceNs = TIMESTAMP_MATCH_TOLERANCE_NS
        )
        val firstEncoderEndpointOmissionTimestampNs = firstUnmatchedTimestamp(
            expected = captureTimestamps,
            observed = encoderTimestampSet,
            toleranceNs = TIMESTAMP_MATCH_TOLERANCE_NS
        )
        val duplicateEncoderSubmissionCount = countTimestampDuplicates(
            evidenceEncoderSubmissions.map { it.sensorTimestampNs },
            TIMESTAMP_MATCH_TOLERANCE_NS
        )
        val previewEncoderSubmittedDifference = abs(previewTimestampSet.size - encoderTimestampSet.size)
        val evidenceWindow = computeEvidenceWindowDurationNs(evidenceCaptureResults)
        val firstTimestamp = evidenceCaptureResults.minOfOrNull { it.sensorTimestampNs }
        val lastTimestamp = evidenceCaptureResults.maxOfOrNull { it.sensorTimestampNs }
        val excludedBoundaryCaptureResultCount =
            (currentCaptureResults.size - evidenceCaptureResults.size).coerceAtLeast(0)
        val failureCodes = buildFailureCodes(
            captureFrameCount = captureTimestamps.size,
            imageTimestampCount = evidenceImageTimestamps.size,
            previewSubmissionCount = evidencePreviewSubmissions.size,
            encoderSubmissionCount = evidenceEncoderSubmissions.size,
            matchedCaptureImageCount = matchedCaptureImageCount,
            captureImageMatchRatio = captureImageMatchRatio,
            evidenceWindowDurationNs = evidenceWindow,
            staleGenerationCount = staleGenerationCount,
            nonMonotonicTimestampCount = nonMonotonicTimestampCount,
            duplicateEncoderSubmissionCount = duplicateEncoderSubmissionCount,
            endpointOmissionCount = endpointOmissionCount,
            previewEncoderSubmittedDifference = previewEncoderSubmittedDifference
        )
        return Camera2FrameLineageSnapshot(
            strictCertificatePassed = failureCodes.isEmpty(),
            strictCertificateFailureCodes = failureCodes,
            expectedSessionGeneration = expectedSessionGeneration,
            minimumFrames = minimumFrames,
            minimumDurationNs = minimumDurationNs,
            minimumCaptureImageTimestampMatchRatio = minimumCaptureImageTimestampMatchRatio,
            timestampMatchToleranceNs = TIMESTAMP_MATCH_TOLERANCE_NS,
            maximumPreviewEncoderSubmittedDifference = maximumPreviewEncoderSubmittedDifference,
            captureResultCount = evidenceCaptureResults.size,
            imageTimestampCount = evidenceImageTimestamps.size,
            previewSubmissionCount = evidencePreviewSubmissions.size,
            encoderSubmissionCount = evidenceEncoderSubmissions.size,
            matchedCaptureImageCount = matchedCaptureImageCount,
            captureImageTimestampMatchRatio = captureImageMatchRatio,
            evidenceWindowDurationNs = evidenceWindow,
            staleGenerationCount = staleGenerationCount,
            nonMonotonicTimestampCount = nonMonotonicTimestampCount,
            duplicateEncoderSubmissionCount = duplicateEncoderSubmissionCount,
            previewEndpointOmissionCount = previewEndpointOmissionCount,
            encoderEndpointOmissionCount = encoderEndpointOmissionCount,
            endpointOmissionCount = endpointOmissionCount,
            previewEncoderSubmittedDifference = previewEncoderSubmittedDifference,
            commonEndpointWindowApplied = commonEndpointWindow != null,
            excludedBoundaryCaptureResultCount = excludedBoundaryCaptureResultCount,
            captureNonMonotonicTimestampCount = captureMonotonicity.count,
            imageNonMonotonicTimestampCount = imageMonotonicity.count,
            previewNonMonotonicTimestampCount = previewMonotonicity.count,
            encoderNonMonotonicTimestampCount = encoderMonotonicity.count,
            firstNonMonotonicStream = firstNonMonotonic?.first,
            firstNonMonotonicPreviousTimestampNs =
                firstNonMonotonic?.second?.firstPreviousTimestampNs,
            firstNonMonotonicTimestampNs = firstNonMonotonic?.second?.firstTimestampNs,
            firstPreviewEndpointOmissionTimestampNs = firstPreviewEndpointOmissionTimestampNs,
            firstEncoderEndpointOmissionTimestampNs = firstEncoderEndpointOmissionTimestampNs,
            firstSensorTimestampNs = firstTimestamp,
            lastSensorTimestampNs = lastTimestamp,
            firstPreviewSensorTimestampNs = evidencePreviewSubmissions
                .minOfOrNull { it.sensorTimestampNs },
            lastPreviewSensorTimestampNs = evidencePreviewSubmissions
                .maxOfOrNull { it.sensorTimestampNs },
            firstEncoderSensorTimestampNs = evidenceEncoderSubmissions
                .minOfOrNull { it.sensorTimestampNs },
            lastEncoderSensorTimestampNs = evidenceEncoderSubmissions
                .maxOfOrNull { it.sensorTimestampNs }
        )
    }

    private fun commonEndpointTimestampWindow(
        previewSubmissions: List<Camera2EndpointSubmissionEvidence>,
        encoderSubmissions: List<Camera2EndpointSubmissionEvidence>
    ): SensorTimestampWindow? {
        if (previewSubmissions.isEmpty() || encoderSubmissions.isEmpty()) return null
        val firstTimestampNs = maxOf(
            previewSubmissions.minOf { it.sensorTimestampNs },
            encoderSubmissions.minOf { it.sensorTimestampNs }
        )
        val lastTimestampNs = minOf(
            previewSubmissions.maxOf { it.sensorTimestampNs },
            encoderSubmissions.maxOf { it.sensorTimestampNs }
        )
        return if (firstTimestampNs <= lastTimestampNs) {
            SensorTimestampWindow(firstTimestampNs, lastTimestampNs)
        } else {
            null
        }
    }

    private fun List<Camera2CaptureFrameEvidence>.within(
        window: SensorTimestampWindow?
    ): List<Camera2CaptureFrameEvidence> = if (window == null) {
        this
    } else {
        filter { window.contains(it.sensorTimestampNs, TIMESTAMP_MATCH_TOLERANCE_NS) }
    }

    @JvmName("imageTimestampsWithin")
    private fun List<Long>.within(window: SensorTimestampWindow?): List<Long> =
        if (window == null) this else filter { window.contains(it, TIMESTAMP_MATCH_TOLERANCE_NS) }

    @JvmName("endpointSubmissionsWithin")
    private fun List<Camera2EndpointSubmissionEvidence>.within(
        window: SensorTimestampWindow?
    ): List<Camera2EndpointSubmissionEvidence> =
        if (window == null) {
            this
        } else {
            // Both endpoints can represent the same boundary frame with a sub-millisecond
            // BOOTTIME/MONOTONIC translation difference. Apply the declared join tolerance to
            // endpoints as well as Camera2/Image timestamps so the common window cannot discard
            // one side of a valid boundary pair. A genuine missing frame remains ~40 ms away and
            // continues to fail the exact one-to-one endpoint check below.
            filter { window.contains(it.sensorTimestampNs, TIMESTAMP_MATCH_TOLERANCE_NS) }
        }

    private data class SensorTimestampWindow(
        val firstTimestampNs: Long,
        val lastTimestampNs: Long
    ) {
        fun contains(timestampNs: Long, toleranceNs: Long = 0L): Boolean {
            val first = (firstTimestampNs - toleranceNs).coerceAtLeast(0L)
            val last = if (lastTimestampNs > Long.MAX_VALUE - toleranceNs) {
                Long.MAX_VALUE
            } else {
                lastTimestampNs + toleranceNs
            }
            return timestampNs in first..last
        }
    }

    private fun buildFailureCodes(
        captureFrameCount: Int,
        imageTimestampCount: Int,
        previewSubmissionCount: Int,
        encoderSubmissionCount: Int,
        matchedCaptureImageCount: Int,
        captureImageMatchRatio: Double,
        evidenceWindowDurationNs: Long,
        staleGenerationCount: Int,
        nonMonotonicTimestampCount: Int,
        duplicateEncoderSubmissionCount: Int,
        endpointOmissionCount: Int,
        previewEncoderSubmittedDifference: Int
    ): List<String> {
        val failures = mutableListOf<String>()
        if (captureFrameCount <= 0) failures += FAILURE_CAPTURE_RESULT_MISSING
        if (imageTimestampCount <= 0) failures += FAILURE_IMAGE_TIMESTAMP_MISSING
        if (previewSubmissionCount <= 0) failures += FAILURE_PREVIEW_SUBMISSION_MISSING
        if (encoderSubmissionCount <= 0) failures += FAILURE_ENCODER_SUBMISSION_MISSING
        if (captureFrameCount < minimumFrames) failures += FAILURE_MINIMUM_FRAMES_NOT_MET
        if (evidenceWindowDurationNs < minimumDurationNs) failures += FAILURE_MINIMUM_DURATION_NOT_MET
        if (matchedCaptureImageCount < captureFrameCount ||
            captureImageMatchRatio < minimumCaptureImageTimestampMatchRatio
        ) {
            failures += FAILURE_CAPTURE_IMAGE_TIMESTAMP_MATCH_RATIO_LOW
        }
        if (staleGenerationCount > 0) failures += FAILURE_STALE_GENERATION
        if (nonMonotonicTimestampCount > 0) failures += FAILURE_NON_MONOTONIC_SENSOR_TIMESTAMP
        if (duplicateEncoderSubmissionCount > 0) failures += FAILURE_DUPLICATE_ENCODER_SUBMISSION
        if (endpointOmissionCount > 0) failures += FAILURE_ENDPOINT_OMISSION
        if (previewEncoderSubmittedDifference > maximumPreviewEncoderSubmittedDifference) {
            failures += FAILURE_PREVIEW_ENCODER_SUBMISSION_DIFFERENCE
        }
        return failures.distinct()
    }

    private fun Camera2CaptureFrameEvidence.isCurrentGeneration(): Boolean =
        sessionGeneration == expectedSessionGeneration &&
            requestTag.sessionGeneration == expectedSessionGeneration

    private fun computeEvidenceWindowDurationNs(
        currentCaptureResults: List<Camera2CaptureFrameEvidence>
    ): Long {
        if (currentCaptureResults.isEmpty()) return 0L
        val sorted = currentCaptureResults.sortedBy { it.sensorTimestampNs }
        val firstTimestamp = sorted.first().sensorTimestampNs
        val last = sorted.last()
        val terminalFrameDurationNs = last.sensorFrameDurationNs
            ?: estimateTerminalFrameDurationNs(sorted)
            ?: 0L
        return (last.sensorTimestampNs - firstTimestamp).coerceAtLeast(0L) +
            terminalFrameDurationNs.coerceAtLeast(0L)
    }

    private fun estimateTerminalFrameDurationNs(
        sortedCaptureResults: List<Camera2CaptureFrameEvidence>
    ): Long? {
        if (sortedCaptureResults.size < 2) return null
        val lastTimestamp = sortedCaptureResults[sortedCaptureResults.lastIndex].sensorTimestampNs
        val previousTimestamp = sortedCaptureResults[sortedCaptureResults.lastIndex - 1].sensorTimestampNs
        return (lastTimestamp - previousTimestamp).takeIf { it > 0L }
    }

    private fun analyzeMonotonicity(timestamps: List<Long>): TimestampMonotonicity {
        var count = 0
        var firstPreviousTimestampNs: Long? = null
        var firstTimestampNs: Long? = null
        var previous: Long? = null
        for (timestamp in timestamps) {
            val previousTimestamp = previous
            if (previousTimestamp != null && timestamp <= previousTimestamp) {
                count++
                if (firstTimestampNs == null) {
                    firstPreviousTimestampNs = previousTimestamp
                    firstTimestampNs = timestamp
                }
            }
            previous = timestamp
        }
        return TimestampMonotonicity(
            count = count,
            firstPreviousTimestampNs = firstPreviousTimestampNs,
            firstTimestampNs = firstTimestampNs
        )
    }

    /** One-to-one monotonic join; adjacent 25 fps frames are far outside the 2 ms clock tolerance. */
    private fun countTimestampMatches(
        expected: List<Long>,
        observed: List<Long>,
        toleranceNs: Long
    ): Int {
        val expectedSorted = expected.distinct().sorted()
        val observedSorted = observed.distinct().sorted()
        var expectedIndex = 0
        var observedIndex = 0
        var matches = 0
        while (expectedIndex < expectedSorted.size && observedIndex < observedSorted.size) {
            val expectedTimestampNs = expectedSorted[expectedIndex]
            val observedTimestampNs = observedSorted[observedIndex]
            val deltaNs = observedTimestampNs - expectedTimestampNs
            when {
                deltaNs < -toleranceNs -> observedIndex++
                deltaNs > toleranceNs -> expectedIndex++
                else -> {
                    matches++
                    expectedIndex++
                    observedIndex++
                }
            }
        }
        return matches
    }

    /** Returns the first expected physical frame without a one-to-one observed endpoint match. */
    private fun firstUnmatchedTimestamp(
        expected: List<Long>,
        observed: List<Long>,
        toleranceNs: Long
    ): Long? {
        val expectedSorted = expected.distinct().sorted()
        val observedSorted = observed.distinct().sorted()
        var observedIndex = 0
        for (expectedTimestampNs in expectedSorted) {
            while (observedIndex < observedSorted.size &&
                observedSorted[observedIndex] < expectedTimestampNs - toleranceNs
            ) {
                observedIndex++
            }
            if (observedIndex >= observedSorted.size ||
                observedSorted[observedIndex] > expectedTimestampNs + toleranceNs
            ) {
                return expectedTimestampNs
            }
            observedIndex++
        }
        return null
    }

    private fun countTimestampDuplicates(timestamps: List<Long>, toleranceNs: Long): Int {
        if (timestamps.size < 2) return 0
        val sorted = timestamps.sorted()
        var duplicates = 0
        var previous = sorted.first()
        for (index in 1 until sorted.size) {
            val timestampNs = sorted[index]
            if (timestampNs - previous <= toleranceNs) duplicates++
            previous = timestampNs
        }
        return duplicates
    }

    private fun timestampsWithinTolerance(
        firstTimestampNs: Long,
        secondTimestampNs: Long,
        toleranceNs: Long
    ): Boolean {
        if (firstTimestampNs < 0L || secondTimestampNs < 0L || toleranceNs < 0L) return false
        val larger = maxOf(firstTimestampNs, secondTimestampNs)
        val smaller = minOf(firstTimestampNs, secondTimestampNs)
        return larger - smaller <= toleranceNs
    }

    private data class ImageTimestampEvidence(
        val sessionGeneration: Long,
        val sensorTimestampNs: Long
    )

    private data class TimestampMonotonicity(
        val count: Int = 0,
        val firstPreviousTimestampNs: Long? = null,
        val firstTimestampNs: Long? = null
    )

    companion object {
        const val STRICT_MINIMUM_FRAMES = 60
        const val STRICT_MINIMUM_DURATION_NS = 2_000_000_000L
        const val STRICT_MINIMUM_CAPTURE_IMAGE_TIMESTAMP_MATCH_RATIO = 0.995
        const val STRICT_MAXIMUM_PREVIEW_ENCODER_SUBMITTED_DIFFERENCE = 1
        const val TIMESTAMP_MATCH_TOLERANCE_NS = 2_000_000L

        const val FAILURE_CAPTURE_RESULT_MISSING = "CAPTURE_RESULT_MISSING"
        const val FAILURE_IMAGE_TIMESTAMP_MISSING = "IMAGE_TIMESTAMP_MISSING"
        const val FAILURE_PREVIEW_SUBMISSION_MISSING = "PREVIEW_SUBMISSION_MISSING"
        const val FAILURE_ENCODER_SUBMISSION_MISSING = "ENCODER_SUBMISSION_MISSING"
        const val FAILURE_MINIMUM_FRAMES_NOT_MET = "MINIMUM_FRAMES_NOT_MET"
        const val FAILURE_MINIMUM_DURATION_NOT_MET = "MINIMUM_DURATION_NOT_MET"
        const val FAILURE_CAPTURE_IMAGE_TIMESTAMP_MATCH_RATIO_LOW =
            "CAPTURE_IMAGE_TIMESTAMP_MATCH_RATIO_LOW"
        const val FAILURE_STALE_GENERATION = "STALE_GENERATION"
        const val FAILURE_NON_MONOTONIC_SENSOR_TIMESTAMP = "NON_MONOTONIC_SENSOR_TIMESTAMP"
        const val FAILURE_DUPLICATE_ENCODER_SUBMISSION = "DUPLICATE_ENCODER_SUBMISSION"
        const val FAILURE_ENDPOINT_OMISSION = "ENDPOINT_OMISSION"
        const val FAILURE_PREVIEW_ENCODER_SUBMISSION_DIFFERENCE =
            "PREVIEW_ENCODER_SUBMISSION_DIFFERENCE"
    }
}
