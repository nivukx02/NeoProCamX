package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Camera2FrameLineageAccumulatorTest {

    @Test
    fun `strict certificate passes with complete per frame lineage`() {
        val accumulator = validAccumulator()

        val snapshot = accumulator.validateStrictCertificate()

        assertTrue(snapshot.strictCertificatePassed)
        assertEquals(60, snapshot.captureResultCount)
        assertEquals(60, snapshot.imageTimestampCount)
        assertEquals(60, snapshot.previewSubmissionCount)
        assertEquals(60, snapshot.encoderSubmissionCount)
        assertEquals(60, snapshot.matchedCaptureImageCount)
        assertEquals(1.0, snapshot.captureImageTimestampMatchRatio, 0.0)
        assertEquals(0, snapshot.staleGenerationCount)
        assertEquals(0, snapshot.nonMonotonicTimestampCount)
        assertEquals(0, snapshot.duplicateEncoderSubmissionCount)
        assertEquals(0, snapshot.endpointOmissionCount)
        assertTrue(
            snapshot.toCompactMetadataMap().keys.all { it.startsWith("camera2FrameLineage") }
        )
        assertEquals(
            "true",
            snapshot.toCompactMetadataMap()["camera2FrameLineageCertified"]
        )
        assertEquals(
            "true",
            snapshot.toCompactMetadataMap()["camera2FrameLineageCertificatePresent"]
        )
        assertEquals(
            "60",
            snapshot.toCompactMetadataMap()["camera2FrameLineageFrameCount"]
        )
        assertEquals(
            "2100",
            snapshot.toCompactMetadataMap()["camera2FrameLineageDurationMs"]
        )
        assertEquals(
            "1.0",
            snapshot.toCompactMetadataMap()["camera2FrameLineageMatchRatio"]
        )
        assertEquals(
            "0",
            snapshot.toCompactMetadataMap()["camera2FrameLineageEndpointOmissionCount"]
        )
        assertEquals(
            "true",
            snapshot.toCompactMetadataMap()["camera2FrameLineage.strictCertificatePassed"]
        )
    }

    @Test
    fun `strict certificate fails when required evidence is missing`() {
        val accumulator = Camera2FrameLineageAccumulator(expectedSessionGeneration = SESSION_GENERATION)
        repeat(60) { index ->
            accumulator.recordCaptureResult(captureEvidence(index))
        }

        val snapshot = accumulator.validateStrictCertificate()

        assertFalse(snapshot.strictCertificatePassed)
        assertTrue(
            snapshot.strictCertificateFailureCodes.contains(
                Camera2FrameLineageAccumulator.FAILURE_IMAGE_TIMESTAMP_MISSING
            )
        )
        assertTrue(
            snapshot.strictCertificateFailureCodes.contains(
                Camera2FrameLineageAccumulator.FAILURE_PREVIEW_SUBMISSION_MISSING
            )
        )
        assertTrue(
            snapshot.strictCertificateFailureCodes.contains(
                Camera2FrameLineageAccumulator.FAILURE_ENCODER_SUBMISSION_MISSING
            )
        )
        assertTrue(
            snapshot.strictCertificateFailureCodes.contains(
                Camera2FrameLineageAccumulator.FAILURE_ENDPOINT_OMISSION
            )
        )
    }

    @Test
    fun `strict certificate fails when stale generation evidence is present`() {
        val accumulator = validAccumulator()
        accumulator.recordCaptureResult(
            captureEvidence(
                index = 60,
                sessionGeneration = SESSION_GENERATION + 1L
            )
        )

        val snapshot = accumulator.validateStrictCertificate()

        assertFalse(snapshot.strictCertificatePassed)
        assertEquals(1, snapshot.staleGenerationCount)
        assertTrue(
            snapshot.strictCertificateFailureCodes.contains(
                Camera2FrameLineageAccumulator.FAILURE_STALE_GENERATION
            )
        )
    }

    @Test
    fun `strict certificate fails when sensor timestamps are non monotonic`() {
        val accumulator = validAccumulator()
        accumulator.recordCaptureResult(
            captureEvidence(
                index = 61,
                sensorTimestampNs = sensorTimestampForIndex(10)
            )
        )

        val snapshot = accumulator.validateStrictCertificate()

        assertFalse(snapshot.strictCertificatePassed)
        assertEquals(1, snapshot.nonMonotonicTimestampCount)
        assertTrue(
            snapshot.strictCertificateFailureCodes.contains(
                Camera2FrameLineageAccumulator.FAILURE_NON_MONOTONIC_SENSOR_TIMESTAMP
            )
        )
    }

    @Test
    fun `strict certificate fails when encoder submits a duplicate frame`() {
        val accumulator = validAccumulator()
        val duplicateTimestamp = sensorTimestampForIndex(12)
        accumulator.recordEncoderSubmission(
            sensorTimestampNs = duplicateTimestamp,
            presentationTimestampNs = duplicateTimestamp + ENCODER_OFFSET_NS + 500_000L,
            frameNumber = 12L
        )

        val snapshot = accumulator.validateStrictCertificate()

        assertFalse(snapshot.strictCertificatePassed)
        assertEquals(1, snapshot.duplicateEncoderSubmissionCount)
        assertTrue(
            snapshot.strictCertificateFailureCodes.contains(
                Camera2FrameLineageAccumulator.FAILURE_DUPLICATE_ENCODER_SUBMISSION
            )
        )
    }

    @Test
    fun `strict certificate fails when a preview or encoder endpoint is omitted`() {
        val accumulator = validAccumulator(skipPreviewIndex = 20)

        val snapshot = accumulator.validateStrictCertificate()

        assertFalse(snapshot.strictCertificatePassed)
        assertEquals(1, snapshot.endpointOmissionCount)
        assertEquals(1, snapshot.previewEndpointOmissionCount)
        assertEquals(0, snapshot.encoderEndpointOmissionCount)
        assertEquals(1, snapshot.previewEncoderSubmittedDifference)
        assertTrue(
            snapshot.strictCertificateFailureCodes.contains(
                Camera2FrameLineageAccumulator.FAILURE_ENDPOINT_OMISSION
            )
        )
    }

    private fun validAccumulator(
        skipPreviewIndex: Int? = null,
        skipEncoderIndex: Int? = null
    ): Camera2FrameLineageAccumulator {
        val accumulator = Camera2FrameLineageAccumulator(expectedSessionGeneration = SESSION_GENERATION)
        repeat(60) { index ->
            val timestamp = sensorTimestampForIndex(index)
            accumulator.recordCaptureResult(captureEvidence(index, sensorTimestampNs = timestamp))
            accumulator.recordImageTimestamp(timestamp)
            if (index != skipPreviewIndex) {
                accumulator.recordPreviewSubmission(
                    sensorTimestampNs = timestamp,
                    presentationTimestampNs = timestamp + PREVIEW_OFFSET_NS,
                    frameNumber = index.toLong()
                )
            }
            if (index != skipEncoderIndex) {
                accumulator.recordEncoderSubmission(
                    sensorTimestampNs = timestamp,
                    presentationTimestampNs = timestamp + ENCODER_OFFSET_NS,
                    frameNumber = index.toLong()
                )
            }
        }
        return accumulator
    }

    private fun captureEvidence(
        index: Int,
        sessionGeneration: Long = SESSION_GENERATION,
        sensorTimestampNs: Long = sensorTimestampForIndex(index)
    ): Camera2CaptureFrameEvidence =
        Camera2CaptureFrameEvidence(
            sessionGeneration = sessionGeneration,
            requestRevision = REQUEST_REVISION,
            frameNumber = index.toLong(),
            sensorTimestampNs = sensorTimestampNs,
            sensorFrameDurationNs = FRAME_DURATION_NS,
            exposureTimeNs = FRAME_DURATION_NS / 2L,
            focusDistanceDiopters = 0.5f,
            whiteBalanceMode = "AUTO",
            requestTag = Camera2RequestTag(
                sessionGeneration = sessionGeneration,
                requestRevision = REQUEST_REVISION,
                targetRoles = setOf("preview", "encoder"),
                pipelineSignature = "NDR10X_V19_LINEAGE_TEST"
            )
        )

    private fun sensorTimestampForIndex(index: Int): Long =
        BASE_SENSOR_TIMESTAMP_NS + FRAME_DURATION_NS * index.toLong()

    private companion object {
        const val SESSION_GENERATION = 7L
        const val REQUEST_REVISION = 3L
        const val BASE_SENSOR_TIMESTAMP_NS = 1_000_000_000L
        const val FRAME_DURATION_NS = 35_000_000L
        const val PREVIEW_OFFSET_NS = 1_000_000L
        const val ENCODER_OFFSET_NS = 2_000_000L
    }
}
