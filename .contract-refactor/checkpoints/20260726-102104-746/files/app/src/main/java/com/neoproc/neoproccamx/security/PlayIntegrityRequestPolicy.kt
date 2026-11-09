package com.neoproc.neoproccamx.security

import java.security.MessageDigest
import java.util.Base64

object PlayIntegrityRequestPolicy {
    fun createRequestHash(payload: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(payload)
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(digest)
    }
}
