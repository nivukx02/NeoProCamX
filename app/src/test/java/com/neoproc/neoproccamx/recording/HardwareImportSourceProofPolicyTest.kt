package com.neoproc.neoproccamx.recording

import android.graphics.ImageFormat
import com.neoproc.neoproccamx.camera.CameraRepeatingReadinessSnapshot
import com.neoproc.neoproccamx.pipeline.HardwareImportSourceIngressTelemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HardwareImportSourceProofPolicyTest {
    @Test
    fun `same clock domain preserves exact sensor timestamp ordering`() {
        val repeating = repeating(
            firstFrameSensorTimestampNs = 10_000_000_000L,
            firstFrameReceivedAtElapsedRealtimeNs = 10_100_000_000L
        )
        val evaluation = HardwareImportSourceProofPolicy.evaluate(
            ingress = ingress(
                sourceImageCount = 101L,
                sensorTimestampNs = 10_040_000_000L,
                receivedAtElapsedRealtimeNs = 10_150_000_000L
            ),
            baselineSourceImageCount = 100L,
            expectedSessionGeneration = SESSION_GENERATION,
            repeating = repeating,
            existingFirstFrameBoundary = null
        )

        assertTrue(evaluation.timestampDomainsCompatible == true)
        assertEquals(
            HardwareImportSourceProofMethod.SENSOR_TIMESTAMP_ORDER,
            evaluation.proofMethod
        )
    }

    @Test
    fun `cross clock domain requires ingress after first capture callback snapshot`() {
        val repeating = vivoCrossClockRepeating()
        val atProof = HardwareImportSourceProofPolicy.evaluate(
            ingress = vivoIngress(sourceImageCount = 1_286L),
            baselineSourceImageCount = 1_245L,
            expectedSessionGeneration = SESSION_GENERATION,
            repeating = repeating,
            existingFirstFrameBoundary = null
        )

        assertFalse(atProof.timestampDomainsCompatible == true)
        assertNull(atProof.proofMethod)
        assertEquals(1_286L, atProof.firstFrameBoundary?.sourceImageCountAtFirstFrameProof)

        val afterProof = HardwareImportSourceProofPolicy.evaluate(
            ingress = vivoIngress(
                sourceImageCount = 1_287L,
                sensorTimestampNs = 1_446_221_993_914_959L,
                receivedAtElapsedRealtimeNs = 1_448_618_408_741_507L
            ),
            baselineSourceImageCount = 1_245L,
            expectedSessionGeneration = SESSION_GENERATION,
            repeating = repeating,
            existingFirstFrameBoundary = atProof.firstFrameBoundary
        )

        assertEquals(
            HardwareImportSourceProofMethod.POST_CAPTURE_CALLBACK_INGRESS,
            afterProof.proofMethod
        )
    }

    @Test
    fun `stale session cannot produce source proof`() {
        val evaluation = HardwareImportSourceProofPolicy.evaluate(
            ingress = ingress(
                sourceImageCount = 101L,
                sessionGeneration = SESSION_GENERATION - 1L,
                sensorTimestampNs = 10_040_000_000L,
                receivedAtElapsedRealtimeNs = 10_150_000_000L
            ),
            baselineSourceImageCount = 100L,
            expectedSessionGeneration = SESSION_GENERATION,
            repeating = repeating(
                firstFrameSensorTimestampNs = 10_000_000_000L,
                firstFrameReceivedAtElapsedRealtimeNs = 10_100_000_000L
            ),
            existingFirstFrameBoundary = null
        )

        assertNotNull(evaluation.firstFrameBoundary)
        assertNull(evaluation.proofMethod)
    }

    @Test
    fun `replacement repeating sequence cannot inherit prior callback boundary`() {
        val firstRepeating = vivoCrossClockRepeating(publicationGeneration = 15L, sequenceId = 16)
        val firstEvaluation = HardwareImportSourceProofPolicy.evaluate(
            ingress = vivoIngress(sourceImageCount = 1_286L),
            baselineSourceImageCount = 1_245L,
            expectedSessionGeneration = SESSION_GENERATION,
            repeating = firstRepeating,
            existingFirstFrameBoundary = null
        )
        val replacement = vivoCrossClockRepeating(
            publicationGeneration = 16L,
            sequenceId = 17,
            firstFrameSensorTimestampNs = 1_448_617_604_541_911L,
            firstFrameReceivedAtElapsedRealtimeNs = 1_448_617_650_000_000L
        )
        val replacementAtProof = HardwareImportSourceProofPolicy.evaluate(
            ingress = vivoIngress(
                sourceImageCount = 1_287L,
                receivedAtElapsedRealtimeNs = 1_448_618_408_741_507L
            ),
            baselineSourceImageCount = 1_245L,
            expectedSessionGeneration = SESSION_GENERATION,
            repeating = replacement,
            existingFirstFrameBoundary = firstEvaluation.firstFrameBoundary
        )

        assertEquals(16L, replacementAtProof.firstFrameBoundary?.publicationGeneration)
        assertEquals(1_287L, replacementAtProof.firstFrameBoundary?.sourceImageCountAtFirstFrameProof)
        assertNull(replacementAtProof.proofMethod)

        val replacementAfterProof = HardwareImportSourceProofPolicy.evaluate(
            ingress = vivoIngress(
                sourceImageCount = 1_288L,
                receivedAtElapsedRealtimeNs = 1_448_618_448_741_507L
            ),
            baselineSourceImageCount = 1_245L,
            expectedSessionGeneration = SESSION_GENERATION,
            repeating = replacement,
            existingFirstFrameBoundary = replacementAtProof.firstFrameBoundary
        )
        assertEquals(
            HardwareImportSourceProofMethod.POST_CAPTURE_CALLBACK_INGRESS,
            replacementAfterProof.proofMethod
        )
    }

    @Test
    fun `cross clock source is not accepted without post proof count advancement`() {
        val repeating = vivoCrossClockRepeating()
        val atProof = HardwareImportSourceProofPolicy.evaluate(
            ingress = vivoIngress(sourceImageCount = 1_286L),
            baselineSourceImageCount = 1_245L,
            expectedSessionGeneration = SESSION_GENERATION,
            repeating = repeating,
            existingFirstFrameBoundary = null
        )
        val laterReadOfSameIngress = HardwareImportSourceProofPolicy.evaluate(
            ingress = vivoIngress(
                sourceImageCount = 1_286L,
                receivedAtElapsedRealtimeNs = 1_448_618_408_741_507L
            ),
            baselineSourceImageCount = 1_245L,
            expectedSessionGeneration = SESSION_GENERATION,
            repeating = repeating,
            existingFirstFrameBoundary = atProof.firstFrameBoundary
        )

        assertNull(laterReadOfSameIngress.proofMethod)
    }

    private fun repeating(
        publicationGeneration: Long = 15L,
        sequenceId: Int = 16,
        firstFrameSensorTimestampNs: Long,
        firstFrameReceivedAtElapsedRealtimeNs: Long
    ) = CameraRepeatingReadinessSnapshot(
        publicationGeneration = publicationGeneration,
        sessionGeneration = SESSION_GENERATION,
        sequenceId = sequenceId,
        firstFrameSensorTimestampNs = firstFrameSensorTimestampNs,
        firstFrameReceivedAtElapsedRealtimeNs = firstFrameReceivedAtElapsedRealtimeNs
    )

    private fun vivoCrossClockRepeating(
        publicationGeneration: Long = 15L,
        sequenceId: Int = 16,
        firstFrameSensorTimestampNs: Long = 1_448_617_564_541_911L,
        firstFrameReceivedAtElapsedRealtimeNs: Long = 1_448_617_610_000_000L
    ) = repeating(
        publicationGeneration = publicationGeneration,
        sequenceId = sequenceId,
        firstFrameSensorTimestampNs = firstFrameSensorTimestampNs,
        firstFrameReceivedAtElapsedRealtimeNs = firstFrameReceivedAtElapsedRealtimeNs
    )

    private fun vivoIngress(
        sourceImageCount: Long,
        sensorTimestampNs: Long = 1_446_221_953_914_959L,
        receivedAtElapsedRealtimeNs: Long = 1_448_618_368_741_507L
    ) = ingress(
        sourceImageCount = sourceImageCount,
        sensorTimestampNs = sensorTimestampNs,
        receivedAtElapsedRealtimeNs = receivedAtElapsedRealtimeNs
    )

    private fun ingress(
        sourceImageCount: Long,
        sessionGeneration: Long = SESSION_GENERATION,
        sensorTimestampNs: Long,
        receivedAtElapsedRealtimeNs: Long
    ) = HardwareImportSourceIngressTelemetry(
        sourceImageCount = sourceImageCount,
        sessionGeneration = sessionGeneration,
        sensorTimestampNs = sensorTimestampNs,
        receivedAtElapsedRealtimeNs = receivedAtElapsedRealtimeNs,
        width = 4096,
        height = 3072,
        imageFormat = ImageFormat.YCBCR_P010
    )

    private companion object {
        const val SESSION_GENERATION = 3L
    }
}
