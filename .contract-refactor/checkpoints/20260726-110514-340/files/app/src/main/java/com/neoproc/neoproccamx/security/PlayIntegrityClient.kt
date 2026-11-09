package com.neoproc.neoproccamx.security

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PlayIntegrityClient(
    context: Context,
    private val cloudProjectNumber: Long
) {
    private val standardIntegrityManager =
        IntegrityManagerFactory.createStandard(context.applicationContext)

    @Volatile
    private var tokenProvider: StandardIntegrityManager.StandardIntegrityTokenProvider? = null

    suspend fun warmUp(): Boolean {
        if (cloudProjectNumber <= 0L) return false
        if (tokenProvider != null) return true
        tokenProvider = standardIntegrityManager.prepareIntegrityToken(
            StandardIntegrityManager.PrepareIntegrityTokenRequest.builder()
                .setCloudProjectNumber(cloudProjectNumber)
                .build()
        ).await()
        return true
    }

    suspend fun requestIntegrityToken(payload: ByteArray): String {
        val provider = tokenProvider ?: run {
            warmUp()
            tokenProvider
        } ?: error("Play Integrity token provider is unavailable")
        val token = provider.request(
            StandardIntegrityManager.StandardIntegrityTokenRequest.builder()
                .setRequestHash(PlayIntegrityRequestPolicy.createRequestHash(payload))
                .build()
        ).await()
        return token.token()
    }
}

private suspend fun <T> Task<T>.await(): T =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { continuation.resume(it) }
        addOnFailureListener { continuation.resumeWithException(it) }
    }
