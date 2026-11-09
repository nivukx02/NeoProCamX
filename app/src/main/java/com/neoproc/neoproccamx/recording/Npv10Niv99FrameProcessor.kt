package com.neoproc.neoproccamx.recording

import android.media.Image
import java.nio.ByteBuffer
import java.util.Locale

internal enum class Npv10Niv99ColorMatrix(val nativeCode: Int) {
    BT709(0),
    BT2020_NCL(1)
}

internal enum class Npv10Niv99Transfer(val nativeCode: Int) {
    BT709(0),
    HLG(1),
    PQ(2),
    SRGB(3)
}

internal data class Npv10Niv99ColorEncoding(
    val matrix: Npv10Niv99ColorMatrix,
    val transfer: Npv10Niv99Transfer,
    val limitedRange: Boolean = true
)

internal enum class Npv10Niv99ProcessingFailure {
    NONE,
    PROCESSOR_UNAVAILABLE,
    METADATA_NOT_FOUND,
    STALE_FRAME_IDENTITY,
    PIXEL_APPLICATION_NOT_APPROVED,
    TEMPORAL_ALIGNMENT_UNPROVEN,
    NATIVE_PROCESSING_FAILED,
    COMPLETION_REJECTED
}

/** Reused worker-owned result; processing never allocates a result object for each frame. */
internal class Npv10Niv99FrameProcessingResult {
    var failure = Npv10Niv99ProcessingFailure.NONE
    var bytesPacked = 0
    var crc32 = 0
    var cameraId = ""
    var sessionGeneration = -1L
    var frameNumber = -1L
    var sensorTimestampNs = -1L
    var processingTimeNs = 0L
    var whiteBalanceEffectMagnitude = 0f
    var denoiseEffectMagnitude = 0f
    var reconstructionEffectMagnitude = 0f

    fun clear(): Npv10Niv99FrameProcessingResult {
        failure = Npv10Niv99ProcessingFailure.NONE
        bytesPacked = 0
        crc32 = 0
        cameraId = ""
        sessionGeneration = -1L
        frameNumber = -1L
        sensorTimestampNs = -1L
        processingTimeNs = 0L
        whiteBalanceEffectMagnitude = 0f
        denoiseEffectMagnitude = 0f
        reconstructionEffectMagnitude = 0f
        return this
    }
}

/**
 * Mandatory production boundary between an Image-independent canonical P010 copy, exact Camera2
 * metadata, and NPV10 persistence. The caller closes the Camera2 Image before entering this API.
 * A false result means the caller must reject the frame; untreated P010 is never an implicit
 * fallback for a session that requires NIV99.
 */
internal interface Npv10Niv99FrameProcessor {
    fun isAvailable(): Boolean

    /**
     * Attempts an exact-metadata, zero-copy Camera2 Image -> DMC submission without waiting. A
     * false result with [Npv10Niv99ProcessingFailure.METADATA_NOT_FOUND] leaves the mapped slot
     * untouched so the caller can close the Image through the canonical deferred-join route.
     */
    fun processImageAndPackDirectMappedIfTuningReady(
        image: Image,
        sensorTimestampNs: Long,
        encoding: Npv10Niv99ColorEncoding,
        directMappedCommit: Npv10DirectMappedCommitEngine,
        frameIndex: Long,
        output: Npv10Niv99FrameProcessingResult
    ): Boolean

    fun processCanonicalAndPack(
        canonicalP010: ByteBuffer,
        width: Int,
        height: Int,
        sensorTimestampNs: Long,
        encoding: Npv10Niv99ColorEncoding,
        target: ByteBuffer,
        output: Npv10Niv99FrameProcessingResult
    ): Boolean

    fun processCanonicalAndPackDirectMapped(
        canonicalP010: ByteBuffer,
        width: Int,
        height: Int,
        sensorTimestampNs: Long,
        encoding: Npv10Niv99ColorEncoding,
        directMappedCommit: Npv10DirectMappedCommitEngine,
        frameIndex: Long,
        output: Npv10Niv99FrameProcessingResult
    ): Boolean
}

internal object Npv10Niv99ColorEncodingPolicy {
    fun resolve(config: Npv10SessionConfig): Npv10Niv99ColorEncoding? {
        val metadata = config.featureMetadata
        val fallback = metadata["pixelColorFallbackPolicy"]
            .orEmpty()
            .equals("LIMITED_BT709_FAIL_CLOSED", ignoreCase = true)
        val range = metadata["pixelColorRange"].orEmpty().trim()
        if (range.isNotEmpty() && !range.equals("LIMITED", ignoreCase = true)) return null
        if (range.isEmpty() && !fallback) return null

        val matrix = when (metadata["pixelColorMatrix"].orEmpty().trim().uppercase(Locale.US)) {
            "BT.709", "BT709" -> Npv10Niv99ColorMatrix.BT709
            "BT.2020-NCL", "BT2020-NCL", "BT.2020", "BT2020" ->
                Npv10Niv99ColorMatrix.BT2020_NCL
            "" -> if (fallback) Npv10Niv99ColorMatrix.BT709 else return null
            else -> return null
        }
        val transferText = metadata["pixelTransferEncoding"]
            .orEmpty()
            .ifBlank { metadata["transfer"].orEmpty() }
            .trim()
            .uppercase(Locale.US)
        val transfer = when {
            transferText == "BT.709" || transferText == "BT709" ||
                transferText == "SDR" -> Npv10Niv99Transfer.BT709
            transferText.contains("HLG") -> Npv10Niv99Transfer.HLG
            transferText.contains("PQ") || transferText.contains("ST2084") ||
                transferText.contains("ST.2084") -> Npv10Niv99Transfer.PQ
            transferText == "SRGB" || transferText == "IEC61966-2-1" ||
                transferText == "IEC 61966-2-1" -> Npv10Niv99Transfer.SRGB
            transferText.isEmpty() && fallback -> Npv10Niv99Transfer.BT709
            else -> return null
        }
        return Npv10Niv99ColorEncoding(matrix, transfer)
    }
}
