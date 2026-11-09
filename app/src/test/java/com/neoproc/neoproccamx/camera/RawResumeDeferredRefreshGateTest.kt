package com.neoproc.neoproccamx.camera

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Test

class RawResumeDeferredRefreshGateTest {
    private class Owner(val name: String)

    @Test
    fun `publication before owner retirement is drained exactly once by terminal path`() {
        val owner = Owner("B")
        val active = AtomicReference<Owner?>(owner)
        val restarts = AtomicInteger(0)
        val gate = RawResumeDeferredRefreshGate(active::get) { restarts.incrementAndGet() }

        gate.defer(owner)
        assertEquals(0, restarts.get())

        active.compareAndSet(owner, null)
        gate.drain()
        gate.drain()
        assertEquals(1, restarts.get())
    }

    @Test
    fun `publication after owner retirement self drains without another terminal callback`() {
        val retiredOwner = Owner("B")
        val active = AtomicReference<Owner?>(null)
        val restarts = AtomicInteger(0)
        val gate = RawResumeDeferredRefreshGate(active::get) { restarts.incrementAndGet() }

        gate.defer(retiredOwner)
        gate.drain()
        assertEquals(1, restarts.get())
    }

    @Test
    fun `retirement drain between publication and revalidation cannot lose wakeup`() {
        val owner = Owner("B")
        val active = AtomicReference<Owner?>(owner)
        val restarts = AtomicInteger(0)
        lateinit var gate: RawResumeDeferredRefreshGate<Owner>
        var retireDuringRevalidation = true
        gate = RawResumeDeferredRefreshGate(
            activeOwner = {
                if (retireDuringRevalidation) {
                    retireDuringRevalidation = false
                    active.compareAndSet(owner, null)
                    gate.drain()
                }
                active.get()
            },
            restart = { restarts.incrementAndGet() }
        )

        gate.defer(owner)
        assertEquals(1, restarts.get())
    }

    @Test
    fun `stale A restart re-defers to B and B retirement survives a C handoff`() {
        val retiredA = Owner("A")
        val ownerB = Owner("B")
        val ownerC = Owner("C")
        val active = AtomicReference<Owner?>(ownerB)
        val restartAttempts = AtomicInteger(0)
        lateinit var gate: RawResumeDeferredRefreshGate<Owner>
        gate = RawResumeDeferredRefreshGate(
            activeOwner = active::get,
            restart = {
                restartAttempts.incrementAndGet()
                active.get()?.let(gate::defer)
            }
        )

        // A's delayed restart is handed to B. B then retires into C; its drain is handed to C.
        gate.defer(retiredA)
        assertEquals(1, restartAttempts.get())
        active.compareAndSet(ownerB, ownerC)
        gate.drain()
        assertEquals(2, restartAttempts.get())

        active.compareAndSet(ownerC, null)
        gate.drain()
        gate.drain()
        assertEquals(3, restartAttempts.get())
    }

    @Test
    fun `failed detach discards deferred replay`() {
        val owner = Owner("P010-stop")
        val active = AtomicReference<Owner?>(owner)
        val restarts = AtomicInteger(0)
        val gate = RawResumeDeferredRefreshGate(active::get) { restarts.incrementAndGet() }

        gate.defer(owner)
        active.compareAndSet(owner, null)
        gate.discard()
        gate.drain()

        assertEquals(0, restarts.get())
    }
}
