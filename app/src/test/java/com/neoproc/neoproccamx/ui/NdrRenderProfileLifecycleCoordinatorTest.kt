package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.rendering.NdrRenderProfileCommitResult
import com.neoproc.neoproccamx.rendering.NdrRenderProfileIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NdrRenderProfileLifecycleCoordinatorTest {
    @Test
    fun `dispatch requires positive current generation and active sensor-linear mode`() {
        val current: (Long) -> Boolean = { it == 17L }

        assertFalse(NdrRenderProfileLifecycleCoordinator.shouldDispatch(0L, current) { true })
        assertFalse(NdrRenderProfileLifecycleCoordinator.shouldDispatch(16L, current) { true })
        assertFalse(NdrRenderProfileLifecycleCoordinator.shouldDispatch(17L, current) { false })
        assertTrue(NdrRenderProfileLifecycleCoordinator.shouldDispatch(17L, current) { true })
    }

    @Test
    fun `late observer receives sticky ready generation as a scope child`() = runBlocking {
        val generations = MutableStateFlow(31L)
        val delivered = CompletableDeferred<Long>()
        val observer = NdrRenderProfileLifecycleCoordinator.observeReadyGenerations(
            scope = this,
            readyGenerations = generations,
            isGenerationCurrent = { it == 31L },
            isModeActive = { true },
            onReady = { delivered.complete(it) }
        )

        assertEquals(31L, withTimeout(2_000L) { delivered.await() })
        observer.cancel()
    }

    @Test
    fun `ready observer isolates nonfatal predicate and callback failures per emission`() = runBlocking {
        val generations = MutableStateFlow(0L)
        val predicateFailed = CompletableDeferred<Unit>()
        val callbackFailed = CompletableDeferred<Unit>()
        val delivered = CompletableDeferred<Long>()
        val observer = NdrRenderProfileLifecycleCoordinator.observeReadyGenerations(
            scope = this,
            readyGenerations = generations,
            isGenerationCurrent = { generation ->
                if (generation == 1L) {
                    predicateFailed.complete(Unit)
                    error("synthetic predicate failure")
                }
                true
            },
            isModeActive = { true },
            onReady = { generation ->
                if (generation == 2L) {
                    callbackFailed.complete(Unit)
                    error("synthetic callback failure")
                }
                delivered.complete(generation)
            }
        )

        generations.value = 1L
        withTimeout(2_000L) { predicateFailed.await() }
        generations.value = 2L
        withTimeout(2_000L) { callbackFailed.await() }
        generations.value = 3L

        assertEquals(3L, withTimeout(2_000L) { delivered.await() })
        observer.cancelAndJoin()
    }

    @Test
    fun `staged observer isolates nonfatal predicate and callback failures per emission`() = runBlocking {
        val generations = MutableStateFlow(0L)
        val predicateFailed = CompletableDeferred<Unit>()
        val callbackFailed = CompletableDeferred<Unit>()
        val delivered = CompletableDeferred<Long>()
        val observer = NdrRenderProfileLifecycleCoordinator.observeStagedGenerations(
            scope = this,
            stagedGenerations = generations,
            isGenerationCurrent = { generation ->
                if (generation == 1L) {
                    predicateFailed.complete(Unit)
                    error("synthetic predicate failure")
                }
                true
            },
            onStaged = { generation ->
                if (generation == 2L) {
                    callbackFailed.complete(Unit)
                    error("synthetic callback failure")
                }
                delivered.complete(generation)
            }
        )

        generations.value = 1L
        withTimeout(2_000L) { predicateFailed.await() }
        generations.value = 2L
        withTimeout(2_000L) { callbackFailed.await() }
        generations.value = 3L

        assertEquals(3L, withTimeout(2_000L) { delivered.await() })
        observer.cancelAndJoin()
    }

    @Test
    fun `ready observer rethrows cancellation from predicate`() = runBlocking {
        val observer = NdrRenderProfileLifecycleCoordinator.observeReadyGenerations(
            scope = this,
            readyGenerations = MutableStateFlow(7L),
            isGenerationCurrent = { true },
            isModeActive = { throw CancellationException("synthetic cancellation") },
            onReady = { error("must not be called") }
        )
        val completion = CompletableDeferred<Throwable?>()
        observer.invokeOnCompletion { cause -> completion.complete(cause) }

        val cause = withTimeout(2_000L) { completion.await() }
        assertTrue(cause is CancellationException)
        assertTrue(observer.isCancelled)
    }

    @Test
    fun `staged observer rethrows cancellation from callback`() = runBlocking {
        val observer = NdrRenderProfileLifecycleCoordinator.observeStagedGenerations(
            scope = this,
            stagedGenerations = MutableStateFlow(11L),
            isGenerationCurrent = { true },
            onStaged = { throw CancellationException("synthetic cancellation") }
        )
        val completion = CompletableDeferred<Throwable?>()
        observer.invokeOnCompletion { cause -> completion.complete(cause) }

        val cause = withTimeout(2_000L) { completion.await() }
        assertTrue(cause is CancellationException)
        assertTrue(observer.isCancelled)
    }

    @Test
    fun `RAW drain observer delivers only current exact retry identities`() = runBlocking {
        val identities = MutableStateFlow<NdrRenderProfileIdentity?>(null)
        val delivered = CompletableDeferred<NdrRenderProfileIdentity>()
        val observer = NdrRenderProfileLifecycleCoordinator.observeMutationReadyIdentities(
            scope = this,
            readyIdentities = identities,
            isGenerationCurrent = { it == 12L },
            onReady = { delivered.complete(it) }
        )

        identities.value = NdrRenderProfileIdentity(11L, 99L)
        identities.value = NdrRenderProfileIdentity(12L, 4L)

        assertEquals(
            NdrRenderProfileIdentity(12L, 4L),
            withTimeout(2_000L) { delivered.await() }
        )
        observer.cancelAndJoin()
    }

    @Test
    fun `only a committed packet permits frame ingress`() {
        NdrRenderProfileCommitResult.entries.forEach { result ->
            assertEquals(
                result == NdrRenderProfileCommitResult.COMMITTED,
                NdrRenderProfileLifecycleCoordinator.permitsOwnerPublication(result)
            )
        }
    }
}
