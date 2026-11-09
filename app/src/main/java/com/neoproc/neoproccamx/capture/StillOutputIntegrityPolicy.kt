package com.neoproc.neoproccamx.capture

import java.io.InputStream
import java.security.MessageDigest

internal data class StillOutputIntegrityEvidence(
    val byteCount: Long,
    val sha256: String,
    val signatureValid: Boolean
) {
    val passed: Boolean
        get() = byteCount > 0L && sha256.length == 64 && signatureValid
}

/** Streaming integrity check used before and after a still-output publication transaction. */
internal object StillOutputIntegrityPolicy {
    fun inspect(input: InputStream, extension: String): StillOutputIntegrityEvidence {
        val digest = MessageDigest.getInstance("SHA-256")
        val prefix = ByteArray(4)
        var prefixSize = 0
        var previousByte = -1
        var lastByte = -1
        var byteCount = 0L
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)

        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            digest.update(buffer, 0, read)
            var index = 0
            while (prefixSize < prefix.size && index < read) {
                prefix[prefixSize++] = buffer[index++]
            }
            if (read == 1) {
                previousByte = lastByte
                lastByte = buffer[0].toInt() and 0xff
            } else {
                previousByte = buffer[read - 2].toInt() and 0xff
                lastByte = buffer[read - 1].toInt() and 0xff
            }
            byteCount += read.toLong()
        }

        val signatureValid = when (extension.trim().lowercase()) {
            "jpg", "jpeg" ->
                prefixSize >= 2 &&
                    (prefix[0].toInt() and 0xff) == 0xff &&
                    (prefix[1].toInt() and 0xff) == 0xd8 &&
                    previousByte == 0xff && lastByte == 0xd9
            "dng" ->
                prefixSize == 4 &&
                    (((prefix[0].toInt() and 0xff) == 0x49 &&
                        (prefix[1].toInt() and 0xff) == 0x49 &&
                        (prefix[2].toInt() and 0xff) == 0x2a &&
                        (prefix[3].toInt() and 0xff) == 0x00) ||
                        ((prefix[0].toInt() and 0xff) == 0x4d &&
                            (prefix[1].toInt() and 0xff) == 0x4d &&
                            (prefix[2].toInt() and 0xff) == 0x00 &&
                            (prefix[3].toInt() and 0xff) == 0x2a))
            "zip" ->
                prefixSize == 4 &&
                    (prefix[0].toInt() and 0xff) == 0x50 &&
                    (prefix[1].toInt() and 0xff) == 0x4b &&
                    (prefix[2].toInt() and 0xff) in setOf(0x03, 0x05, 0x07) &&
                    (prefix[3].toInt() and 0xff) in setOf(0x04, 0x06, 0x08)
            else -> byteCount > 0L
        }
        return StillOutputIntegrityEvidence(
            byteCount = byteCount,
            sha256 = digest.digest().joinToString(separator = "") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            },
            signatureValid = signatureValid
        )
    }

    fun requireMatching(
        beforePublication: StillOutputIntegrityEvidence,
        afterPublication: StillOutputIntegrityEvidence
    ) {
        require(beforePublication.passed) { "Still output failed pre-publication integrity validation" }
        require(afterPublication.passed) { "Still output failed post-publication integrity validation" }
        require(beforePublication.byteCount == afterPublication.byteCount) {
            "Still output byte count changed during publication"
        }
        require(beforePublication.sha256 == afterPublication.sha256) {
            "Still output content changed during publication"
        }
    }
}
