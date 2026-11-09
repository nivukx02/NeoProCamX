package com.neoproc.neoproccamx.security

import java.security.MessageDigest
import java.util.Base64

data class BoundIntegrityRequest(
    val requestId: String,
    val issuedAtEpochMs: Long,
    val tokenPayload: ByteArray,
    val requestHash: String
)

object PlayIntegrityRequestPolicy {
    private const val MAX_ACTION_LENGTH = 128
    private const val MAX_PAYLOAD_BYTES = 1024 * 1024
    private val validAction = Regex("[A-Za-z0-9._:-]{1,$MAX_ACTION_LENGTH}")
    private val validRequestId = Regex("[A-Za-z0-9_-]{16,128}")

    fun createRequestHash(payload: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(payload)
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(digest)
    }

    fun bindRequest(
        action: String,
        payload: ByteArray,
        requestId: String,
        issuedAtEpochMs: Long
    ): BoundIntegrityRequest {
        require(validAction.matches(action)) { "Invalid integrity action" }
        require(payload.size <= MAX_PAYLOAD_BYTES) { "Integrity payload is too large" }
        require(validRequestId.matches(requestId)) { "Invalid integrity request ID" }
        require(issuedAtEpochMs > 0L) { "Integrity request timestamp must be positive" }

        val payloadDigest = createRequestHash(payload)
        val tokenPayload = buildString {
            append("neoprocamx-integrity-v1\n")
            append(action)
            append('\n')
            append(requestId)
            append('\n')
            append(issuedAtEpochMs)
            append('\n')
            append(payloadDigest)
        }.toByteArray(Charsets.UTF_8)
        return BoundIntegrityRequest(
            requestId = requestId,
            issuedAtEpochMs = issuedAtEpochMs,
            tokenPayload = tokenPayload,
            requestHash = createRequestHash(tokenPayload)
        )
    }
}
