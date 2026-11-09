package com.neoproc.neoproccamx.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
