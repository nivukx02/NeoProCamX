package com.neoproc.neoproccamx.security

data class IntegrityBackendRequest(
    val action: String,
    val requestId: String,
    val issuedAtEpochMs: Long,
    val requestHash: String,
    val integrityToken: String,
    val appVersion: String,
    val securitySignals: Set<String>,
    val installationSecretId: String? = null
)

data class IntegrityBackendResponse(
    val accepted: Boolean,
    val appRecognitionVerdict: String? = null,
    val deviceRecognitionVerdicts: Set<String> = emptySet(),
    val licensingVerdict: String? = null,
    val requestHashMatched: Boolean = false,
    val message: String? = null
)

interface IntegrityBackendVerifier {
    suspend fun verify(request: IntegrityBackendRequest): IntegrityBackendResponse
}
