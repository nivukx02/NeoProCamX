package com.neoproc.neoproccamx.pipeline

import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

internal data class AICameraStageResult<T>(
    val value: T,
    val fresh: Boolean
)

internal object AICameraStageResolver {

    fun <T> resolve(
        future: Future<T>?,
        timeoutMs: Long,
        fallback: T,
        fallbackTimestampMs: Long,
        frameTimestampMs: Long,
        maxStaleMs: Long,
        emptyValue: T,
        cancelOnTimeout: Boolean = true,
        strictHighEnd: Boolean = false
    ): AICameraStageResult<T> {
        val timeoutWindowMs = if (strictHighEnd) {
            (timeoutMs + (timeoutMs / 6L).coerceAtLeast(2L)).coerceAtLeast(timeoutMs)
        } else {
            timeoutMs
        }
        val staleWindowMs = if (strictHighEnd) {
            (maxStaleMs + (maxStaleMs / 4L).coerceAtLeast(16L)).coerceAtLeast(maxStaleMs)
        } else {
            maxStaleMs
        }
        val fallbackFresh = frameTimestampMs - fallbackTimestampMs <= staleWindowMs
        if (future == null) {
            return AICameraStageResult(
                value = if (fallbackFresh) fallback else emptyValue,
                fresh = false
            )
        }
        return try {
            AICameraStageResult(future.get(timeoutWindowMs, TimeUnit.MILLISECONDS), fresh = true)
        } catch (_: TimeoutException) {
            if (cancelOnTimeout) {
                future.cancel(true)
            }
            AICameraStageResult(
                value = if (fallbackFresh) fallback else emptyValue,
                fresh = false
            )
        } catch (_: Throwable) {
            if (cancelOnTimeout) {
                future.cancel(true)
            }
            AICameraStageResult(
                value = if (fallbackFresh) fallback else emptyValue,
                fresh = false
            )
        }
    }
}
