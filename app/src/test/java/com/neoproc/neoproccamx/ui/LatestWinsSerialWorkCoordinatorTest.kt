package com.neoproc.neoproccamx.ui

import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LatestWinsSerialWorkCoordinatorTest {
    private data class Request(val value: Int, val force: Boolean = false)

    @Test
    fun `burst while drain is active keeps only latest request and sticky force`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val firstStarted = CountDownLatch(1)
            val releaseFirst = CountDownLatch(1)
            val allExecuted = CountDownLatch(2)
            val executed = CopyOnWriteArrayList<Request>()
            val coordinator = coordinator(
                schedule = { drain -> executor.execute(drain) },
                execute = { request ->
                    executed += request
                    if (request.value == 1) {
                        firstStarted.countDown()
                        assertTrue(releaseFirst.await(5, TimeUnit.SECONDS))
                    }
                    allExecuted.countDown()
                }
            )

            coordinator.submit(Request(1))
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS))
            coordinator.submit(Request(2, force = true))
            coordinator.submit(Request(3))
            releaseFirst.countDown()

            assertTrue(allExecuted.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(Request(1), Request(3, force = true)), executed)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `awaited request completes only after serialized execution`() = runBlocking {
        val scheduled = ArrayDeque<() -> Unit>()
        var executed = false
        var awaitReturned = false
        val coordinator = coordinator(
            schedule = scheduled::addLast,
            execute = { executed = true }
        )

        val awaiting = async(start = CoroutineStart.UNDISPATCHED) {
            coordinator.submitAndAwait(Request(7))
            awaitReturned = true
        }

        assertFalse(executed)
        assertFalse(awaitReturned)
        scheduled.removeFirst().invoke()
        awaiting.await()

        assertTrue(executed)
        assertTrue(awaitReturned)
    }

    @Test
    fun `request submitted by active execution drains without a lost wakeup`() {
        val scheduled = ArrayDeque<() -> Unit>()
        val executed = mutableListOf<Int>()
        lateinit var coordinator: LatestWinsSerialWorkCoordinator<Request>
        coordinator = coordinator(
            schedule = scheduled::addLast,
            execute = { request ->
                executed += request.value
                if (request.value == 1) coordinator.submit(Request(2))
            }
        )

        coordinator.submit(Request(1))
        scheduled.removeFirst().invoke()

        assertEquals(listOf(1, 2), executed)
        assertTrue(scheduled.isEmpty())
    }

    @Test
    fun `failed awaited request propagates and later work still executes`() = runBlocking {
        val scheduled = ArrayDeque<() -> Unit>()
        val executed = mutableListOf<Int>()
        val failures = mutableListOf<String>()
        val coordinator = coordinator(
            schedule = scheduled::addLast,
            execute = { request ->
                if (request.value == 1) error("expected failure")
                executed += request.value
            },
            onFailure = { failures += requireNotNull(it.message) }
        )
        val failed = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { coordinator.submitAndAwait(Request(1)) }.exceptionOrNull()
        }
        scheduled.removeFirst().invoke()
        assertEquals("expected failure", failed.await()?.message)

        coordinator.submit(Request(2))
        scheduled.removeFirst().invoke()

        assertEquals(listOf(2), executed)
        assertEquals(listOf("expected failure"), failures)
    }

    private fun coordinator(
        schedule: ((() -> Unit) -> Unit),
        execute: (Request) -> Unit,
        onFailure: (Throwable) -> Unit = {}
    ): LatestWinsSerialWorkCoordinator<Request> =
        LatestWinsSerialWorkCoordinator(
            merge = { pending, incoming ->
                incoming.copy(force = pending.force || incoming.force)
            },
            schedule = schedule,
            execute = execute,
            onFailure = onFailure
        )
}
