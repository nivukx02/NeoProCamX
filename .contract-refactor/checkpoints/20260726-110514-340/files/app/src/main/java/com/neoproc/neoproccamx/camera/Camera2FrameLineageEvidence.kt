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
    val firstSensorTimestampNs: Long?,
    val lastSensorTimestampNs: Long?
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
            "camera2FrameLineage.firstSensorTimestampNs" to (firstSensorTimestampNs?.toString() ?: ""),
            "camera2FrameLineage.lastSensorTimestampNs" to (lastSensorTimestampNs?.toString() ?: "")
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
        val captureTimestamps = currentCaptureResults
            .map { it.sensorTimestampNs }
            .distinct()
        val imageTimestampSet = currentImageTimestamps.toSet()
        val previewTimestampSet = currentPreviewSubmissions
            .map { it.sensorTimestampNs }
            .toSet()
        val encoderTimestampSet = currentEncoderSubmissions
            .map { it.sensorTimestampNs }
            .toSet()
        val matchedCaptureImageCount = captureTimestamps.count { it in imageTimestampSet }
        val captureImageMatchRatio = if (captureTimestamps.isEmpty()) {
            0.0
        } else {
            matchedCaptureImageCount.toDouble() / captureTimestamps.size.toDouble()
        }
        var previewEndpointOmissionCount = 0
        var encoderEndpointOmissionCount = 0
        captureTimestamps.forEach { timestamp ->
            if (timestamp !in previewTimestampSet) previewEndpointOmissionCount++
            if (timestamp !in encoderTimestampSet) encoderEndpointOmissionCount++
        }
        val endpointOmissionCount = previewEndpointOmissionCount + encoderEndpointOmissionCount
        val staleGenerationCount = captureResults.count { !it.isCurrentGeneration() } +
            imageTimestamps.count { it.sessionGeneration != expectedSessionGeneration } +
            endpointSubmissions.count { it.sessionGeneration != expectedSessionGeneration }
        val nonMonotonicTimestampCount = countNonMonotonic(currentCaptureResults.map { it.sensorTimestampNs }) +
            countNonMonotonic(currentImageTimestamps) +
            countNonMonotonic(currentPreviewSubmissions.map { it.sensorTimestampNs }) +
            countNonMonotonic(currentEncoderSubmissions.map { it.sensorTimestampNs })
        val duplicateEncoderSubmissionCount = currentEncoderSubmissions.size -
            currentEncoderSubmissions.map { it.sensorTimestampNs }.distinct().size
        val previewEncoderSubmittedDifference = abs(previewTimestampSet.size - encoderTimestampSet.size)
        val evidenceWindow = computeEvidenceWindowDurationNs(currentCaptureResults)
        val firstTimestamp = currentCaptureResults.minOfOrNull { it.sensorTimestampNs }
        val lastTimestamp = currentCaptureResults.maxOfOrNull { it.sensorTimestampNs }
        val failureCodes = buildFailureCodes(
            captureFrameCount = captureTimestamps.size,
            imageTimestampCount = currentImageTimestamps.size,
            previewSubmissionCount = currentPreviewSubmissions.size,
            encoderSubmissionCount = currentEncoderSubmissions.size,
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
            maximumPreviewEncoderSubmittedDifference = maximumPreviewEncoderSubmittedDifference,
            captureResultCount = currentCaptureResults.size,
            imageTimestampCount = currentImageTimestamps.size,
            previewSubmissionCount = currentPreviewSubmissions.size,
            encoderSubmissionCount = currentEncoderSubmissions.size,
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
            firstSensorTimestampNs = firstTimestamp,
            lastSensorTimestampNs = lastTimestamp
        )
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

    private fun countNonMonotonic(timestamps: List<Long>): Int {
        var count = 0
        var previous: Long? = null
        for (timestamp in timestamps) {
            val previousTimestamp = previous
            if (previousTimestamp != null && timestamp <= previousTimestamp) {
                count++
            }
            previous = timestamp
        }
        return count
    }

    private data class ImageTimestampEvidence(
        val sessionGeneration: Long,
        val sensorTimestampNs: Long
    )

    companion object {
        const val STRICT_MINIMUM_FRAMES = 60
        const val STRICT_MINIMUM_DURATION_NS = 2_000_000_000L
        const val STRICT_MINIMUM_CAPTURE_IMAGE_TIMESTAMP_MATCH_RATIO = 0.995
        const val STRICT_MAXIMUM_PREVIEW_ENCODER_SUBMITTED_DIFFERENCE = 1

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
