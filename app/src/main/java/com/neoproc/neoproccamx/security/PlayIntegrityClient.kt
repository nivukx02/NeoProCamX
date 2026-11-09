package com.neoproc.neoproccamx.security

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.StandardIntegrityManager
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val preparationMutex = Mutex()

    suspend fun warmUp(): Boolean {
        if (cloudProjectNumber <= 0L) return false
        if (tokenProvider != null) return true
        preparationMutex.withLock {
            if (tokenProvider == null) {
                tokenProvider = standardIntegrityManager.prepareIntegrityToken(
                    StandardIntegrityManager.PrepareIntegrityTokenRequest.builder()
                        .setCloudProjectNumber(cloudProjectNumber)
                        .build()
                ).await()
            }
        }
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
        return token.token().also { require(it.isNotBlank()) { "Play Integrity returned an empty token" } }
    }
}

private suspend fun <T> Task<T>.await(): T =
    suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { value ->
            if (continuation.isActive) continuation.resume(value)
        }
        addOnFailureListener { error ->
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }
