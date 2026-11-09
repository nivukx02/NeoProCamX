package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture

class AICameraStageResolverTest {

    @Test
    fun resolve_returnsFreshFutureResultWhenAvailable() {
        val result = AICameraStageResolver.resolve(
            future = CompletableFuture.completedFuture("fresh"),
            timeoutMs = 5L,
            fallback = "fallback",
            fallbackTimestampMs = 0L,
            frameTimestampMs = 100L,
            maxStaleMs = 20L,
            emptyValue = "empty"
        )

        assertEquals("fresh", result.value)
        assertTrue(result.fresh)
    }

    @Test
    fun resolve_usesFreshFallbackWhenStageIsMissing() {
        val result = AICameraStageResolver.resolve(
            future = null,
            timeoutMs = 5L,
            fallback = "fallback",
            fallbackTimestampMs = 95L,
            frameTimestampMs = 100L,
            maxStaleMs = 20L,
            emptyValue = "empty"
        )

        assertEquals("fallback", result.value)
        assertFalse(result.fresh)
    }

    @Test
    fun resolve_usesEmptyValueWhenFallbackIsTooStale() {
        val result = AICameraStageResolver.resolve(
            future = null,
            timeoutMs = 5L,
            fallback = "fallback",
            fallbackTimestampMs = 1L,
            frameTimestampMs = 100L,
            maxStaleMs = 20L,
            emptyValue = "empty"
        )

        assertEquals("empty", result.value)
        assertFalse(result.fresh)
    }
}
