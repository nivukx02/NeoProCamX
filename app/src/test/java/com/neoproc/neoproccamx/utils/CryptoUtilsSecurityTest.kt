package com.neoproc.neoproccamx.utils

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class CryptoUtilsSecurityTest {
    @Test
    fun aesGcm_rejectsTruncationAndWrongAssociatedData() {
        val key = CryptoUtils.generateRandomKey()
        val plaintext = "camera-secret".toByteArray()
        val encrypted = CryptoUtils.encryptAesGcm(plaintext, key, "vault:first".toByteArray())

        assertArrayEquals(
            plaintext,
            CryptoUtils.decryptAesGcm(encrypted, key, "vault:first".toByteArray())
        )
        assertThrows(IllegalArgumentException::class.java) {
            CryptoUtils.decryptAesGcm(ByteArray(27), key)
        }
        assertThrows(Exception::class.java) {
            CryptoUtils.decryptAesGcm(encrypted, key, "vault:second".toByteArray())
        }
    }

    @Test
    fun hexDecoder_rejectsOddLengthAndNonHexInput() {
        assertThrows(IllegalArgumentException::class.java) { CryptoUtils.hexToBytes("ABC") }
        assertThrows(IllegalArgumentException::class.java) { CryptoUtils.hexToBytes("ZZ") }
    }

    @Test
    fun productionCryptoSurface_containsNoUnauthenticatedCbcOrPasswordKeyCache() {
        val source = File("src/main/java/com/neoproc/neoproccamx/utils/CryptoUtils.kt").readText()

        assertFalse(source.contains("AES/CBC"))
        assertFalse(source.contains("encryptAesCbc"))
        assertFalse(source.contains("keyCache"))
    }
}
