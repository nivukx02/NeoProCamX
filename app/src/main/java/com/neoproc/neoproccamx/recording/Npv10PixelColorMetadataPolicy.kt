package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.pipeline.RawYuvPixelAuthorityTelemetry
import com.neoproc.neoproccamx.rendering.NdrInputPrimaries
import com.neoproc.neoproccamx.rendering.NdrInputRange
import com.neoproc.neoproccamx.rendering.NdrInputTransfer
import com.neoproc.neoproccamx.rendering.NdrInputTransportPolicy

/**
 * Immutable, producer-owned colour evidence for one NPV10 P010 source.
 *
 * Requested looks and colour-space selections are deliberately excluded. Only a timestamp-joined
 * Image from the exact current Camera2 session, source size and format may describe stored pixels.
 * Android DataSpace proves transfer, standard/primaries and range; the YCbCr matrix is then derived
 * from that enumerated standard. A missing HAL dataspace never becomes invented colour metadata:
 * exact-session P010 remains recordable with an explicit unverified packet and playback decodes it
 * conservatively as limited-range BT.709. Contradictory known metadata remains rejected.
 */
internal data class Npv10PixelColorMetadataPlan(
    val verified: Boolean,
    val sourceIdentityVerified: Boolean,
    val recordingEligible: Boolean,
    val sourceAuthority: String,
    val transferEncoding: String,
    val primaries: String,
    val matrix: String,
    val range: String,
    val authority: String,
    val status: String,
    val reason: String,
    val dataSpace: Int,
    val sessionGeneration: Long,
    val sensorTimestampNs: Long
) {
    fun toMetadata(): Map<String, String> = linkedMapOf(
        "pixelSourceIdentityVerified" to sourceIdentityVerified.toString(),
        "pixelSourceRecordingEligible" to recordingEligible.toString(),
        "pixelSourceAuthority" to sourceAuthority,
        "pixelTransferEncoding" to transferEncoding,
        "pixelTransferApplied" to verified.toString(),
        "pixelTransferAuthority" to authority,
        "pixelColorPrimaries" to primaries,
        "pixelColorMatrix" to matrix,
        "pixelColorRange" to range,
        "pixelColorApplied" to verified.toString(),
        "pixelColorAuthority" to authority,
        "pixelColorAuthorityStatus" to status,
        "pixelColorAuthorityReason" to reason,
        "pixelColorFallbackRequired" to (!verified && recordingEligible).toString(),
        "pixelColorFallbackPolicy" to when {
            verified -> "NONE"
            recordingEligible -> "LIMITED_BT709_FAIL_CLOSED"
            else -> "RECORDING_REJECTED"
        },
        "pixelMatrixDerivation" to if (verified) {
            "ITU_YCBCR_MATRIX_FROM_IMAGE_DATASPACE_STANDARD"
        } else {
            "NONE"
        },
        "pixelDataSpace" to dataSpace.toString(),
        "pixelSourceSessionGeneration" to sessionGeneration.toString(),
        "pixelSourceSensorTimestampNs" to sensorTimestampNs.toString()
    )
}

internal object Npv10PixelColorMetadataPolicy {
    private const val VERIFIED_AUTHORITY = "IMAGE_DATASPACE"
    private const val UNVERIFIED_AUTHORITY = "UNVERIFIED"
    private const val VERIFIED_SOURCE_AUTHORITY = "CAMERA2_EXACT_TIMESTAMP_JOIN"
    private const val UNVERIFIED_SOURCE_AUTHORITY = "UNVERIFIED"
    private const val IMAGE_DATASPACE_API_LEVEL = 33

    fun supportsAuthoritativeImageDataSpace(apiLevel: Int): Boolean =
        apiLevel >= IMAGE_DATASPACE_API_LEVEL

    fun resolve(
        evidence: RawYuvPixelAuthorityTelemetry,
        expectedSessionGeneration: Long,
        expectedWidth: Int,
        expectedHeight: Int,
        expectedImageFormat: Int,
        minimumSensorTimestampNs: Long
    ): Npv10PixelColorMetadataPlan {
        val structuralFailureReason = when {
            !evidence.exactCaptureIdentity -> "NO_EXACT_CAMERA2_IDENTITY"
            evidence.sessionGeneration != expectedSessionGeneration -> "STALE_SESSION_GENERATION"
            evidence.sensorTimestampNs <= 0L -> "INVALID_SENSOR_TIMESTAMP"
            minimumSensorTimestampNs <= 0L -> "INVALID_RECORDING_PRODUCER_TIMESTAMP"
            evidence.sensorTimestampNs < minimumSensorTimestampNs ->
                "PIXEL_EVIDENCE_PRECEDES_RECORDING_PRODUCER"
            evidence.width != expectedWidth || evidence.height != expectedHeight -> "SOURCE_SIZE_MISMATCH"
            evidence.imageFormat != expectedImageFormat -> "SOURCE_FORMAT_MISMATCH"
            else -> null
        }
        if (structuralFailureReason != null) {
            return structuralFailure(evidence, structuralFailureReason)
        }

        if (!evidence.dataSpaceApiAvailable) {
            return sourceBoundColorFailure(
                evidence = evidence,
                reason = "IMAGE_DATASPACE_API_UNAVAILABLE",
                recordingEligible = false
            )
        }
        if (evidence.dataSpace == NdrInputTransportPolicy.DATASPACE_UNKNOWN) {
            // Some otherwise valid Camera2 P010 producers omit Image.dataSpace. Exact timestamp,
            // session-generation, geometry and format evidence still proves which pixels are being
            // stored, but it cannot prove their transfer/primaries/range. Preserve all ten-bit samples,
            // declare colour unverified, and require playback/export to use its conservative decode.
            return sourceBoundColorFailure(
                evidence = evidence,
                reason = "UNKNOWN_IMAGE_DATASPACE",
                recordingEligible = true
            )
        }

        val transport = NdrInputTransportPolicy.resolve(
            dataSpace = evidence.dataSpace,
            appliedProfile = null,
            preferPqWhenUnknown = false
        )
        if (!transport.dataSpaceKnown) {
            return sourceBoundColorFailure(evidence, "UNSUPPORTED_IMAGE_DATASPACE")
        }

        val transfer = when (transport.transfer) {
            NdrInputTransfer.SDR -> "BT.709"
            NdrInputTransfer.HLG -> Bt2100TransportPolicy.TRANSFER_HLG
            NdrInputTransfer.PQ -> Bt2100TransportPolicy.TRANSFER_PQ
            else -> return sourceBoundColorFailure(evidence, "NON_YUV_TRANSFER")
        }
        val primaries = when (transport.primaries) {
            NdrInputPrimaries.BT709 -> "BT.709"
            NdrInputPrimaries.BT2020 -> "BT.2020"
            else -> return sourceBoundColorFailure(evidence, "UNKNOWN_COLOR_STANDARD")
        }
        val matrix = when (transport.primaries) {
            NdrInputPrimaries.BT709 -> "BT.709"
            NdrInputPrimaries.BT2020 -> "BT.2020-NCL"
        }
        val range = when (transport.range) {
            NdrInputRange.FULL -> "FULL"
            NdrInputRange.LIMITED -> "LIMITED"
            else -> return sourceBoundColorFailure(evidence, "UNSUPPORTED_OR_UNKNOWN_RANGE")
        }
        if (range == "FULL") {
            return sourceBoundColorFailure(evidence, "FULL_RANGE_P010_RECORDING_UNSUPPORTED")
        }
        val internallyConsistent = when (transport.transfer) {
            NdrInputTransfer.PQ,
            NdrInputTransfer.HLG -> transport.primaries == NdrInputPrimaries.BT2020
            NdrInputTransfer.SDR -> transport.primaries == NdrInputPrimaries.BT709 ||
                transport.primaries == NdrInputPrimaries.BT2020
        }
        if (!internallyConsistent) {
            return sourceBoundColorFailure(evidence, "TRANSFER_STANDARD_CONFLICT")
        }

        return Npv10PixelColorMetadataPlan(
            verified = true,
            sourceIdentityVerified = true,
            recordingEligible = true,
            sourceAuthority = VERIFIED_SOURCE_AUTHORITY,
            transferEncoding = transfer,
            primaries = primaries,
            matrix = matrix,
            range = range,
            authority = VERIFIED_AUTHORITY,
            status = "VERIFIED",
            reason = "EXACT_CURRENT_SESSION_IMAGE_DATASPACE",
            dataSpace = evidence.dataSpace,
            sessionGeneration = evidence.sessionGeneration,
            sensorTimestampNs = evidence.sensorTimestampNs
        )
    }

    private fun sourceBoundColorFailure(
        evidence: RawYuvPixelAuthorityTelemetry,
        reason: String,
        recordingEligible: Boolean = false
    ): Npv10PixelColorMetadataPlan = Npv10PixelColorMetadataPlan(
        verified = false,
        sourceIdentityVerified = true,
        recordingEligible = recordingEligible,
        sourceAuthority = VERIFIED_SOURCE_AUTHORITY,
        transferEncoding = "",
        primaries = "",
        matrix = "",
        range = "",
        authority = UNVERIFIED_AUTHORITY,
        status = if (recordingEligible) {
            "SOURCE_VERIFIED_COLOR_UNSPECIFIED"
        } else {
            "SOURCE_VERIFIED_COLOR_REJECTED"
        },
        reason = reason,
        dataSpace = evidence.dataSpace,
        sessionGeneration = evidence.sessionGeneration,
        sensorTimestampNs = evidence.sensorTimestampNs
    )

    private fun structuralFailure(
        evidence: RawYuvPixelAuthorityTelemetry,
        reason: String
    ): Npv10PixelColorMetadataPlan = Npv10PixelColorMetadataPlan(
        verified = false,
        sourceIdentityVerified = false,
        recordingEligible = false,
        sourceAuthority = UNVERIFIED_SOURCE_AUTHORITY,
        transferEncoding = "",
        primaries = "",
        matrix = "",
        range = "",
        authority = UNVERIFIED_AUTHORITY,
        status = "FAIL_CLOSED",
        reason = reason,
        dataSpace = evidence.dataSpace,
        sessionGeneration = evidence.sessionGeneration,
        sensorTimestampNs = evidence.sensorTimestampNs
    )
}
