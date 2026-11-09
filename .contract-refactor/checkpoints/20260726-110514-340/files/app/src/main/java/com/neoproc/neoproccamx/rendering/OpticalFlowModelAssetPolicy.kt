package com.neoproc.neoproccamx.rendering

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

data class OpticalFlowModelAssetValidation(
    val valid: Boolean,
    val reason: String,
    val assetPath: String,
    val sizeBytes: Long,
    val sha256: String,
    val tfliteFlatbuffer: Boolean
)

object OpticalFlowModelAssetPolicy {
    const val DEFAULT_ASSET_PATH = "models/optical_flow_int8.tflite"
    private const val EXPECTED_FILE_NAME = "optical_flow_int8.tflite"
    private const val MIN_REAL_MODEL_BYTES = 256 * 1024L
    private val TFLITE_IDENTIFIER = byteArrayOf('T'.code.toByte(), 'F'.code.toByte(), 'L'.code.toByte(), '3'.code.toByte())

    fun validateFile(file: File, assetPath: String = DEFAULT_ASSET_PATH): OpticalFlowModelAssetValidation {
        if (!file.isFile) {
            return invalid(assetPath, "model missing")
        }
        return file.inputStream().use { input ->
            validateStream(
                assetPath = assetPath,
                fileName = file.name,
                input = input
            )
        }
    }

    fun validateStream(
        assetPath: String,
        fileName: String,
        input: InputStream
    ): OpticalFlowModelAssetValidation {
        val digest = MessageDigest.getInstance("SHA-256")
        val header = ByteArray(8)
        var headerOffset = 0
        var size = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            if (headerOffset < header.size) {
                val copy = minOf(read, header.size - headerOffset)
                buffer.copyInto(header, destinationOffset = headerOffset, startIndex = 0, endIndex = copy)
                headerOffset += copy
            }
            digest.update(buffer, 0, read)
            size += read
        }

        if (fileName != EXPECTED_FILE_NAME) {
            return invalid(assetPath, "unexpected file name", size, digest)
        }
        if (!fileName.endsWith(".tflite", ignoreCase = true)) {
            return invalid(assetPath, "not a tflite model", size, digest)
        }
        if (size < MIN_REAL_MODEL_BYTES) {
            return invalid(assetPath, "model too small", size, digest)
        }
        if (!hasTfliteIdentifier(header)) {
            return invalid(assetPath, "not a TFLite FlatBuffer", size, digest)
        }
        return OpticalFlowModelAssetValidation(
            valid = true,
            reason = "valid TFLite container; tensor/runtime validation still required before activation",
            assetPath = assetPath,
            sizeBytes = size,
            sha256 = digest.digest().toHex(),
            tfliteFlatbuffer = true
        )
    }

    private fun hasTfliteIdentifier(header: ByteArray): Boolean =
        header.size >= 8 &&
            header[4] == TFLITE_IDENTIFIER[0] &&
            header[5] == TFLITE_IDENTIFIER[1] &&
            header[6] == TFLITE_IDENTIFIER[2] &&
            header[7] == TFLITE_IDENTIFIER[3]

    private fun invalid(
        assetPath: String,
        reason: String,
        sizeBytes: Long = 0L,
        digest: MessageDigest? = null
    ): OpticalFlowModelAssetValidation =
        OpticalFlowModelAssetValidation(
            valid = false,
            reason = reason,
            assetPath = assetPath,
            sizeBytes = sizeBytes,
            sha256 = digest?.digest()?.toHex().orEmpty(),
            tfliteFlatbuffer = false
        )

    private fun ByteArray.toHex(): String =
        joinToString(separator = "") { byte -> "%02x".format(byte) }
}
