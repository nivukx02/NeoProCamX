package com.neoproc.neoproccamx.recording

import android.graphics.ImageFormat
import com.neoproc.neoproccamx.camera.Npv10TenBitProducerProof
import com.neoproc.neoproccamx.pipeline.RawYuvPixelAuthorityTelemetry
import com.neoproc.neoproccamx.rendering.NdrInputTransportPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Npv10RecordingStartupAuthorityCoordinatorTest {
    @Test
    fun `pre-arm evidence waits until the exact first recording timestamp`() = runBlocking {
        var pixelReads = 0
        val result = await(
            pixelEvidence = {
                pixelReads += 1
                evidence(if (pixelReads == 1) FIRST_RECORDING_NS - 1L else FIRST_RECORDING_NS)
            }
        )

        assertTrue(result.accepted)
        assertEquals(2, pixelReads)
        assertEquals(FIRST_RECORDING_NS, result.authority?.pixelEvidence?.sensorTimestampNs)
        assertEquals("true", result.authority?.toMetadata()?.get("pixelEvidenceAtOrAfterRecordingProducer"))
    }

    @Test
    fun `later frame from the same producer is accepted`() = runBlocking {
        val result = await(pixelEvidence = { evidence(FIRST_RECORDING_NS + 40_000_000L) })

        assertTrue(result.accepted)
        assertEquals(FIRST_RECORDING_NS + 40_000_000L, result.authority?.pixelEvidence?.sensorTimestampNs)
    }

    @Test
    fun `stale session evidence is rejected until bounded timeout`() = runBlocking {
        val result = await(
            pixelEvidence = { evidence(FIRST_RECORDING_NS).copy(sessionGeneration = SESSION - 1L) }
        )

        assertFalse(result.accepted)
        assertTrue(result.timedOut)
        assertTrue(result.failureReason.endsWith("STALE_SESSION_GENERATION"))
    }

    @Test
    fun `same session control request replacement rebases to the newer producer`() = runBlocking {
        var producerReads = 0
        val replacement = proof().copy(
            publicationGeneration = 10L,
            sequenceId = 42,
            firstFrameNumber = 400L,
            firstFrameSensorTimestampNs = FIRST_RECORDING_NS + FRAME_INTERVAL_NS
        )
        val result = await(
            producerProof = {
                producerReads += 1
                when (producerReads) {
                    1 -> proof()
                    2 -> null
                    else -> replacement
                }
            },
            pixelEvidence = {
                evidence(
                    if (producerReads < 3) FIRST_RECORDING_NS - 1L
                    else replacement.firstFrameSensorTimestampNs
                )
            }
        )

        assertTrue(result.accepted)
        assertFalse(result.timedOut)
        assertEquals(replacement, result.authority?.producerProof)
        assertEquals(replacement.firstFrameSensorTimestampNs, result.authority?.pixelEvidence?.sensorTimestampNs)
    }

    @Test
    fun `producer mutation during pixel snapshot rebases and revalidates the newer boundary`() = runBlocking {
        var currentProof = proof()
        var pixelReads = 0
        val replacement = proof().copy(
            publicationGeneration = 10L,
            sequenceId = 42,
            firstFrameNumber = 400L,
            firstFrameSensorTimestampNs = FIRST_RECORDING_NS + FRAME_INTERVAL_NS
        )
        val result = await(
            producerProof = { currentProof },
            pixelEvidence = {
                pixelReads += 1
                if (pixelReads == 1) {
                    evidence(FIRST_RECORDING_NS).also { currentProof = replacement }
                } else {
                    evidence(replacement.firstFrameSensorTimestampNs)
                }
            }
        )

        assertTrue(result.accepted)
        assertFalse(result.timedOut)
        assertEquals(2, pixelReads)
        assertEquals(replacement, result.authority?.producerProof)
    }

    @Test
    fun `session replacement during pixel proof remains fail closed`() = runBlocking {
        var currentProof = proof()
        val result = await(
            producerProof = { currentProof },
            pixelEvidence = {
                evidence(FIRST_RECORDING_NS).also {
                    currentProof = currentProof.copy(
                        publicationGeneration = currentProof.publicationGeneration + 1L,
                        sessionGeneration = currentProof.sessionGeneration + 1L,
                        sequenceId = currentProof.sequenceId + 1
                    )
                }
            }
        )

        assertFalse(result.accepted)
        assertFalse(result.timedOut)
        assertEquals("RECORDING_PRODUCER_SESSION_CHANGED", result.failureReason)
    }

    @Test
    fun `producer publication regression remains fail closed`() = runBlocking {
        var producerReads = 0
        val result = await(
            producerProof = {
                producerReads += 1
                if (producerReads == 1) proof() else proof().copy(publicationGeneration = 8L)
            },
            pixelEvidence = { evidence(FIRST_RECORDING_NS - 1L) }
        )

        assertFalse(result.accepted)
        assertFalse(result.timedOut)
        assertEquals("RECORDING_PRODUCER_IDENTITY_REGRESSED", result.failureReason)
    }

    @Test
    fun `producer loss after proof stays bounded and never accepts stale pixels`() = runBlocking {
        var producerReads = 0
        val result = await(
            producerProof = {
                producerReads += 1
                if (producerReads == 1) proof() else null
            },
            pixelEvidence = { evidence(FIRST_RECORDING_NS - 1L) }
        )

        assertFalse(result.accepted)
        assertTrue(result.timedOut)
        assertEquals(
            "RECORDING_PRODUCER_STABILITY_TIMEOUT:SAME_SESSION_PRODUCER_REPLACEMENT_PROOF_PENDING",
            result.failureReason
        )
    }

    @Test
    fun `missing producer proof times out without manufacturing authority`() = runBlocking {
        val result = await(
            producerProof = { null },
            pixelEvidence = { error("Pixel evidence must not be read before producer proof") }
        )

        assertFalse(result.accepted)
        assertTrue(result.timedOut)
        assertEquals("RECORDING_PRODUCER_PROOF_TIMEOUT", result.failureReason)
        assertEquals(null, result.authority)
    }

    @Test
    fun `accepted packet retains exact Camera2 producer and colour authority`() = runBlocking {
        val result = await(pixelEvidence = { evidence(FIRST_RECORDING_NS) })

        val authority = assertNotNull(result.authority).let { result.authority!! }
        assertEquals(SESSION, authority.producerProof.sessionGeneration)
        assertEquals(321L, authority.producerProof.firstFrameNumber)
        assertEquals("IMAGE_DATASPACE", authority.pixelColorMetadata.authority)
        assertTrue(authority.pixelColorMetadata.verified)
    }

    @Test
    fun `unknown HAL dataspace accepts exact P010 source without manufacturing colour authority`() = runBlocking {
        val result = await(
            pixelEvidence = {
                evidence(FIRST_RECORDING_NS).copy(
                    dataSpace = NdrInputTransportPolicy.DATASPACE_UNKNOWN
                )
            }
        )

        val authority = assertNotNull(result.authority).let { result.authority!! }
        assertTrue(result.accepted)
        assertFalse(result.timedOut)
        assertTrue(authority.pixelColorMetadata.sourceIdentityVerified)
        assertTrue(authority.pixelColorMetadata.recordingEligible)
        assertFalse(authority.pixelColorMetadata.verified)
        assertEquals("UNVERIFIED", authority.pixelColorMetadata.authority)
        assertEquals("UNKNOWN_IMAGE_DATASPACE", authority.pixelColorMetadata.reason)
        assertEquals(
            "LIMITED_BT709_FAIL_CLOSED",
            authority.toMetadata()["pixelColorFallbackPolicy"]
        )
    }

    @Test
    fun `startup lease revocation during pixel proof fails closed`() = runBlocking {
        var leaseValid = true
        val result = await(
            startupLeaseValid = { leaseValid },
            pixelEvidence = {
                evidence(FIRST_RECORDING_NS).also { leaseValid = false }
            }
        )

        assertFalse(result.accepted)
        assertFalse(result.timedOut)
        assertEquals("RECORDING_PRODUCER_STARTUP_LEASE_REVOKED", result.failureReason)
    }

    private suspend fun await(
        producerProof: () -> Npv10TenBitProducerProof? = { proof() },
        startupLeaseValid: () -> Boolean = { true },
        pixelEvidence: () -> RawYuvPixelAuthorityTelemetry
    ): Npv10RecordingStartupAuthorityResult {
        var nowMs = 0L
        return Npv10RecordingStartupAuthorityCoordinator.await(
            expectedWidth = WIDTH,
            expectedHeight = HEIGHT,
            expectedImageFormat = ImageFormat.YCBCR_P010,
            timeoutMs = 100L,
            producerProof = producerProof,
            pixelEvidence = pixelEvidence,
            startupLeaseValid = startupLeaseValid,
            elapsedRealtimeMs = { nowMs },
            pause = { nowMs += it },
            pollIntervalMs = 25L
        )
    }

    private fun proof() = Npv10TenBitProducerProof(
        publicationGeneration = 9L,
        sessionGeneration = SESSION,
        sequenceId = 7,
        firstFrameNumber = 321L,
        firstFrameSensorTimestampNs = FIRST_RECORDING_NS
    )

    private fun evidence(timestampNs: Long) = RawYuvPixelAuthorityTelemetry(
        exactCaptureIdentity = true,
        sessionGeneration = SESSION,
        sensorTimestampNs = timestampNs,
        width = WIDTH,
        height = HEIGHT,
        imageFormat = ImageFormat.YCBCR_P010,
        dataSpace = NdrInputTransportPolicy.DATASPACE_BT709,
        dataSpaceApiAvailable = true
    )

    private companion object {
        const val WIDTH = 4096
        const val HEIGHT = 2160
        const val SESSION = 17L
        const val FIRST_RECORDING_NS = 1_000_000_000L
        const val FRAME_INTERVAL_NS = 40_000_000L
    }
}
