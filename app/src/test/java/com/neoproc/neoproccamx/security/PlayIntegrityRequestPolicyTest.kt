package com.neoproc.neoproccamx.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PlayIntegrityRequestPolicyTest {
    @Test
    fun createRequestHash_matchesExpectedBase64UrlSha256() {
        val hash = PlayIntegrityRequestPolicy.createRequestHash("camera:start".toByteArray())

        assertEquals("8PIp-y8IZQBitZy_IKmkk1IdWyQ067ntYl5vfAChmGA", hash)
    }

    @Test
    fun createRequestHash_usesUrlSafeEncodingWithoutPadding() {
        val hash = PlayIntegrityRequestPolicy.createRequestHash("camera:start".toByteArray())

        assertFalse(hash.contains('='))
        assertFalse(hash.contains('+'))
        assertFalse(hash.contains('/'))
    }

    @Test
    fun bindRequest_bindsActionNonceTimestampAndPayloadAgainstReplay() {
        val first = PlayIntegrityRequestPolicy.bindRequest(
            action = "camera:start",
            payload = "same-payload".toByteArray(),
            requestId = "request_1234567890abcdef",
            issuedAtEpochMs = 1_700_000_000_000L
        )
        val second = PlayIntegrityRequestPolicy.bindRequest(
            action = "camera:start",
            payload = "same-payload".toByteArray(),
            requestId = "request_fedcba0987654321",
            issuedAtEpochMs = 1_700_000_000_001L
        )

        assertNotEquals(first.requestHash, second.requestHash)
        assertEquals(first.requestHash, PlayIntegrityRequestPolicy.createRequestHash(first.tokenPayload))
    }

    @Test
    fun bindRequest_rejectsAmbiguousActionsAndUnboundedPayloads() {
        assertThrows(IllegalArgumentException::class.java) {
            PlayIntegrityRequestPolicy.bindRequest(
                action = "camera\nstart",
                payload = byteArrayOf(),
                requestId = "request_1234567890abcdef",
                issuedAtEpochMs = 1L
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            PlayIntegrityRequestPolicy.bindRequest(
                action = "camera:start",
                payload = ByteArray(1024 * 1024 + 1),
                requestId = "request_1234567890abcdef",
                issuedAtEpochMs = 1L
            )
        }
    }
}
