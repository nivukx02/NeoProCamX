package com.neoproc.neoproccamx.recording

import java.nio.ByteBuffer

internal enum class Npv10PreparedP010CrcPath(val nativeFused: Boolean) {
    NIV99_CAMERA_PLANE_FUSED_NATIVE(true),
    NIV99_CANONICAL_BUFFER_FUSED_NATIVE(true),
    NIV99_DIRECT_MAPPED_FUSED_NATIVE(true),
    CAMERA_PLANE_DIRECT_MAPPED_FUSED_NATIVE(true),
    CAMERA_PLANE_FUSED_NATIVE(true),
    CANONICAL_PACKET_NATIVE(true),
    JAVA_PACKET_FALLBACK(false)
}

/**
 * Bit-exact packed-10 packet prepared while the Camera2 Image is valid.
 *
 * The payload contains either the lossless PACKED_P010_10_LE_V1 bytes or its independently chunked
 * NLC2 representation. [decodedPayloadBytes] retains the canonical P010 byte count used by the
 * capture ledger and quality evidence; no pixel data or precision is discarded beyond the six P010
 * padding bits that are required to be zero.
 */
internal data class Npv10PreparedP010Packet(
    val timestampNs: Long,
    val width: Int,
    val height: Int,
    val packetLease: Npv10NativeFrameBuffer?,
    val decodedPayloadBytes: Int,
    val encodedBytes: Int,
    val crc32: Int,
    val crcPath: Npv10PreparedP010CrcPath,
    val fallbackCrcTimeNs: Long,
    val copyPath: Npv10FramePacker.P010CopyPath,
    val preparationTimeNs: Long,
    val niv99Applied: Boolean,
    val niv99CameraId: String,
    val niv99SessionGeneration: Long,
    val niv99FrameNumber: Long,
    val niv99ProcessingTimeNs: Long,
    val directMappedFrameIndex: Long = -1L
) {
    init {
        require((packetLease != null) != (directMappedFrameIndex >= 0L)) {
            "Prepared P010 packet must own exactly one native packet lease or DMC frame"
        }
    }

    val directMapped: Boolean get() = directMappedFrameIndex >= 0L

    val packet: ByteBuffer
        get() = checkNotNull(packetLease) { "Direct-mapped P010 frames have no packet buffer" }.buffer
}
