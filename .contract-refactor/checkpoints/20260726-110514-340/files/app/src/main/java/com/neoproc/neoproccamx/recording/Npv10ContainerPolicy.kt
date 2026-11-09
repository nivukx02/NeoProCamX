package com.neoproc.neoproccamx.recording

import java.io.File
import java.util.Locale

enum class Npv10PayloadFormat {
    YUV420P10_LE,
    P010_LE,
    YUV420_8BIT_EXPANDED,
    RAW12_NPV10_PACKED,
    RAW14_NPV10_PACKED;

    val isPlanarYuv420: Boolean
        get() = this == YUV420P10_LE || this == YUV420_8BIT_EXPANDED

    val isRawPacked: Boolean
        get() = this == RAW12_NPV10_PACKED || this == RAW14_NPV10_PACKED

    val rawBitDepth: Int?
        get() = when (this) {
            RAW12_NPV10_PACKED -> 12
            RAW14_NPV10_PACKED -> 14
            else -> null
        }

    companion object {
        fun fromMetadata(value: String?): Npv10PayloadFormat {
            val normalized = value
                ?.trim()
                ?.replace('-', '_')
                ?.uppercase(Locale.US)
                ?: throw IllegalArgumentException("Missing NPV10 payload format metadata")
            return values().firstOrNull { format -> format.name == normalized }
                ?: throw IllegalArgumentException("Unsupported NPV10 payload format: $value")
        }
    }
}

object Npv10ContainerPolicy {
    val MAGIC_BYTES: ByteArray = byteArrayOf(
        'N'.code.toByte(),
        'P'.code.toByte(),
        'V'.code.toByte(),
        '1'.code.toByte(),
        '0'.code.toByte()
    )
    val FRAME_MAGIC_BYTES: ByteArray = byteArrayOf(
        'F'.code.toByte(),
        'R'.code.toByte(),
        'M'.code.toByte(),
        '0'.code.toByte()
    )

    const val LEGACY_VERSION = 1
    const val VERSION = 2
    const val RAW_ACQUISITION_CONTRACT_VERSION = 2
    const val EXTENSION = "npv10"
    const val BIT_DEPTH = 10
    const val HEADER_MIN_BYTES = 29
    private const val MASTER_DIR_NAME = "CamXNPV10"
    private const val LEGACY_MASTER_DIR_NAME = "npv10_masters"

    fun masterDirectory(privateRoot: File): File =
        File(privateRoot, MASTER_DIR_NAME)

    fun legacyMasterDirectory(privateRoot: File): File =
        File(privateRoot, LEGACY_MASTER_DIR_NAME)

    fun buildMasterFileName(config: Npv10SessionConfig): String {
        val mode = sanitize(config.mode)
        val profile = sanitize(config.colorProfile).takeIf { it.isNotEmpty() } ?: "profile"
        val label = sanitizeValidationLabel(config.validationLabel).takeIf { it.isNotEmpty() }
        val labelSegment = label?.let { "_$it" } ?: ""
        return "NPV10_${config.startedAtEpochMs}${labelSegment}_${mode}_${profile}_" +
            "${config.width}x${config.height}_${config.fps}fps.$EXTENSION"
    }

    fun estimatedPayloadBytesPerFrame(width: Int, height: Int): Long {
        val safeWidth = width.coerceAtLeast(1)
        val safeHeight = height.coerceAtLeast(1)
        val chromaWidth = (safeWidth + 1) / 2
        val chromaHeight = (safeHeight + 1) / 2
        val samples = safeWidth.toLong() * safeHeight.toLong() +
            2L * chromaWidth.toLong() * chromaHeight.toLong()
        return samples * Short.SIZE_BYTES
    }

    fun estimatedRawPackedPayloadBytesPerFrame(width: Int, height: Int): Long =
        width.coerceAtLeast(1).toLong() * height.coerceAtLeast(1).toLong() * Short.SIZE_BYTES

    fun estimatedPayloadBytesPerFrame(
        width: Int,
        height: Int,
        payloadFormat: Npv10PayloadFormat
    ): Long =
        if (payloadFormat.isRawPacked) {
            estimatedRawPackedPayloadBytesPerFrame(width, height)
        } else {
            estimatedPayloadBytesPerFrame(width, height)
        }

    fun estimatedStoredBytesPerFrame(
        width: Int,
        height: Int,
        payloadFormat: Npv10PayloadFormat,
        packetCodec: Npv10PacketCodec
    ): Long = Npv10PacketCodecEngine.storedBytes(
        decodedBytes = estimatedPayloadBytesPerFrame(width, height, payloadFormat),
        codec = packetCodec
    )

    private fun sanitize(value: String): String =
        value
            .trim()
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifEmpty { "video" }

    private fun sanitizeValidationLabel(value: String): String =
        value
            .trim()
            .replace(Regex("[^A-Za-z0-9_.-]+"), "_")
            .trim('_', '.', '-')
            .take(120)
}
