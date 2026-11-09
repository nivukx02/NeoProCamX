package com.neoproc.neoproccamx.recording

import android.graphics.ImageFormat
import com.neoproc.neoproccamx.camera.Npv10P010ReaderTransportPolicy
import com.neoproc.neoproccamx.pipeline.RawYuvPixelAuthorityTelemetry
import com.neoproc.neoproccamx.rendering.NdrInputTransportPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Npv10PixelColorMetadataPolicyTest {
    @Test
    fun imageDataspaceAuthorityIsExplicitlyGatedToAndroid13AndNewer() {
        assertFalse(Npv10PixelColorMetadataPolicy.supportsAuthoritativeImageDataSpace(32))
        assertTrue(Npv10PixelColorMetadataPolicy.supportsAuthoritativeImageDataSpace(33))
        assertTrue(Npv10PixelColorMetadataPolicy.supportsAuthoritativeImageDataSpace(35))
    }

    @Test
    fun exactCurrentSessionBt709ImageDataspaceProducesCompleteAuthority() {
        val plan = resolve(evidence(dataSpace = NdrInputTransportPolicy.DATASPACE_BT709))

        assertTrue(plan.verified)
        assertEquals("BT.709", plan.transferEncoding)
        assertEquals("BT.709", plan.primaries)
        assertEquals("BT.709", plan.matrix)
        assertEquals("LIMITED", plan.range)
        assertEquals("IMAGE_DATASPACE", plan.authority)
        assertEquals("VERIFIED", plan.status)
        assertTrue(plan.sourceIdentityVerified)
        assertTrue(plan.recordingEligible)
        assertEquals("CAMERA2_EXACT_TIMESTAMP_JOIN", plan.sourceAuthority)
        assertEquals("true", plan.toMetadata()["pixelColorApplied"])
        assertEquals("NONE", plan.toMetadata()["pixelColorFallbackPolicy"])
    }

    @Test
    fun exactCurrentSessionWithUnknownHalDataspacePreservesP010WithoutInventingColour() {
        val plan = resolve(evidence(dataSpace = NdrInputTransportPolicy.DATASPACE_UNKNOWN))

        assertFalse(plan.verified)
        assertTrue(plan.sourceIdentityVerified)
        assertTrue(plan.recordingEligible)
        assertEquals("CAMERA2_EXACT_TIMESTAMP_JOIN", plan.sourceAuthority)
        assertEquals("UNVERIFIED", plan.authority)
        assertEquals("SOURCE_VERIFIED_COLOR_UNSPECIFIED", plan.status)
        assertEquals("UNKNOWN_IMAGE_DATASPACE", plan.reason)
        assertEquals("", plan.transferEncoding)
        assertEquals("", plan.primaries)
        assertEquals("", plan.matrix)
        assertEquals("", plan.range)
        assertEquals("false", plan.toMetadata()["pixelColorApplied"])
        assertEquals("true", plan.toMetadata()["pixelSourceIdentityVerified"])
        assertEquals("true", plan.toMetadata()["pixelSourceRecordingEligible"])
        assertEquals("true", plan.toMetadata()["pixelColorFallbackRequired"])
        assertEquals("LIMITED_BT709_FAIL_CLOSED", plan.toMetadata()["pixelColorFallbackPolicy"])
    }

    @Test
    fun exactBt2020SdrDataspaceCanRepresentWideGamutSdrWithoutRequestedIntent() {
        val bt2020SdrLimited = 0x10C60000
        val plan = resolve(evidence(dataSpace = bt2020SdrLimited))

        assertTrue(plan.verified)
        assertEquals("BT.709", plan.transferEncoding)
        assertEquals("BT.2020", plan.primaries)
        assertEquals("BT.2020-NCL", plan.matrix)
        assertEquals("LIMITED", plan.range)
    }

    @Test
    fun explicitLimitedRangeP010HdrDataspacesRemainAuthoritative() {
        val pq = resolve(
            evidence(dataSpace = Npv10P010ReaderTransportPolicy.DATA_SPACE_BT2020_PQ_LIMITED)
        )
        val hlg = resolve(
            evidence(dataSpace = Npv10P010ReaderTransportPolicy.DATA_SPACE_BT2020_HLG_LIMITED)
        )

        assertTrue(pq.verified)
        assertEquals(Bt2100TransportPolicy.TRANSFER_PQ, pq.transferEncoding)
        assertEquals("BT.2020", pq.primaries)
        assertEquals("LIMITED", pq.range)
        assertTrue(hlg.verified)
        assertEquals(Bt2100TransportPolicy.TRANSFER_HLG, hlg.transferEncoding)
        assertEquals("BT.2020", hlg.primaries)
        assertEquals("LIMITED", hlg.range)
    }

    @Test
    fun staleSessionEvidenceFailsClosed() {
        val plan = resolve(
            evidence(dataSpace = NdrInputTransportPolicy.DATASPACE_BT709)
                .copy(sessionGeneration = EXPECTED_SESSION - 1L)
        )

        assertFalse(plan.verified)
        assertFalse(plan.sourceIdentityVerified)
        assertFalse(plan.recordingEligible)
        assertEquals("STALE_SESSION_GENERATION", plan.reason)
        assertEquals("false", plan.toMetadata()["pixelTransferApplied"])
        assertEquals("UNVERIFIED", plan.authority)
    }

    @Test
    fun preArmPixelEvidenceCannotDescribeRecordingPixels() {
        val plan = resolve(
            evidence(dataSpace = NdrInputTransportPolicy.DATASPACE_BT709)
                .copy(sensorTimestampNs = RECORDING_BOUNDARY_NS - 1L)
        )

        assertFalse(plan.verified)
        assertEquals("PIXEL_EVIDENCE_PRECEDES_RECORDING_PRODUCER", plan.reason)
    }

    @Test
    fun exactFirstRecordingTimestampAndLaterFramesAreAuthoritative() {
        val exact = resolve(
            evidence(dataSpace = NdrInputTransportPolicy.DATASPACE_BT709)
                .copy(sensorTimestampNs = RECORDING_BOUNDARY_NS)
        )
        val later = resolve(
            evidence(dataSpace = NdrInputTransportPolicy.DATASPACE_BT709)
                .copy(sensorTimestampNs = RECORDING_BOUNDARY_NS + 40_000_000L)
        )

        assertTrue(exact.verified)
        assertTrue(later.verified)
    }

    @Test
    fun preApiDataspaceEvidenceFailsClosed() {
        val plan = resolve(
            evidence(dataSpace = NdrInputTransportPolicy.DATASPACE_BT709)
                .copy(dataSpaceApiAvailable = false)
        )

        assertFalse(plan.verified)
        assertTrue(plan.sourceIdentityVerified)
        assertFalse(plan.recordingEligible)
        assertEquals("IMAGE_DATASPACE_API_UNAVAILABLE", plan.reason)
    }

    @Test
    fun unknownRangeNeverBecomesAuthoritative() {
        val bt709WithoutRange = 0x00C10000
        val plan = resolve(evidence(dataSpace = bt709WithoutRange))

        assertFalse(plan.verified)
        assertTrue(plan.sourceIdentityVerified)
        assertFalse(plan.recordingEligible)
        assertEquals("UNSUPPORTED_OR_UNKNOWN_RANGE", plan.reason)
    }

    @Test
    fun fullRangeSourceIsRejectedUntilEveryP010ExportPathHasAProvenRangeTransform() {
        val bt709FullRange = 0x08C10000
        val plan = resolve(evidence(dataSpace = bt709FullRange))

        assertFalse(plan.verified)
        assertTrue(plan.sourceIdentityVerified)
        assertFalse(plan.recordingEligible)
        assertEquals("FULL_RANGE_P010_RECORDING_UNSUPPORTED", plan.reason)
    }

    @Test
    fun mismatchedSourceIdentityFailsClosed() {
        val wrongSize = resolve(
            evidence(dataSpace = NdrInputTransportPolicy.DATASPACE_BT709).copy(width = 1920)
        )
        val wrongFormat = resolve(
            evidence(dataSpace = NdrInputTransportPolicy.DATASPACE_BT709)
                .copy(imageFormat = ImageFormat.YUV_420_888)
        )

        assertEquals("SOURCE_SIZE_MISMATCH", wrongSize.reason)
        assertEquals("SOURCE_FORMAT_MISMATCH", wrongFormat.reason)
        assertFalse(wrongSize.sourceIdentityVerified)
        assertFalse(wrongFormat.sourceIdentityVerified)
        assertFalse(wrongSize.recordingEligible)
        assertFalse(wrongFormat.recordingEligible)
    }

    private fun resolve(evidence: RawYuvPixelAuthorityTelemetry): Npv10PixelColorMetadataPlan =
        Npv10PixelColorMetadataPolicy.resolve(
            evidence = evidence,
            expectedSessionGeneration = EXPECTED_SESSION,
            expectedWidth = 4096,
            expectedHeight = 2160,
            expectedImageFormat = ImageFormat.YCBCR_P010,
            minimumSensorTimestampNs = RECORDING_BOUNDARY_NS
        )

    private fun evidence(dataSpace: Int) = RawYuvPixelAuthorityTelemetry(
        exactCaptureIdentity = true,
        sessionGeneration = EXPECTED_SESSION,
        sensorTimestampNs = 987_654_321L,
        width = 4096,
        height = 2160,
        imageFormat = ImageFormat.YCBCR_P010,
        dataSpace = dataSpace,
        dataSpaceApiAvailable = true
    )

    private companion object {
        const val EXPECTED_SESSION = 17L
        const val RECORDING_BOUNDARY_NS = 900_000_000L
    }
}
