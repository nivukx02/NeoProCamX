package com.neoproc.neoproccamx.recording

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Npv10ProducerBoundaryGateTest {
    @Test
    fun `normal exact finalization cannot be downgraded by lifecycle teardown`() {
        val gate = Npv10ProducerBoundaryGate()
        gate.open()

        assertTrue(gate.establish())
        assertFalse(gate.poisonIfOpen())
        assertTrue(gate.sealForPublication())
        assertEquals(
            Npv10ProducerBoundaryGate.State.SEALED_ESTABLISHED,
            gate.state
        )
    }

    @Test
    fun `lifecycle poison before exact finalization remains irreversible`() {
        val gate = Npv10ProducerBoundaryGate()
        gate.open()

        assertTrue(gate.poisonIfOpen())
        assertFalse(gate.establish())
        assertFalse(gate.sealForPublication())
        assertEquals(Npv10ProducerBoundaryGate.State.SEALED_POISONED, gate.state)
    }

    @Test
    fun `exact cleanup and lifecycle teardown have one atomic winner`() {
        val gate = Npv10ProducerBoundaryGate()
        gate.open()
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val established = AtomicBoolean(false)
        val poisoned = AtomicBoolean(false)

        Thread {
            start.await()
            established.set(gate.establish())
            done.countDown()
        }.start()
        Thread {
            start.await()
            poisoned.set(gate.poisonIfOpen())
            done.countDown()
        }.start()
        start.countDown()

        assertTrue(done.await(5L, TimeUnit.SECONDS))
        assertTrue(established.get() xor poisoned.get())
        assertEquals(established.get(), gate.sealForPublication())
    }

    @Test
    fun `unexpected open producer seals fail closed`() {
        val gate = Npv10ProducerBoundaryGate()
        gate.open()

        assertFalse(gate.sealForPublication())
        assertEquals(Npv10ProducerBoundaryGate.State.SEALED_POISONED, gate.state)
    }

    @Test
    fun `explicit poison and publication seal have one atomic winner`() {
        val gate = Npv10ProducerBoundaryGate()
        gate.open()
        assertTrue(gate.establish())
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val poisoned = AtomicBoolean(false)
        val publicationSafe = AtomicBoolean(false)

        Thread {
            start.await()
            poisoned.set(gate.poison())
            done.countDown()
        }.start()
        Thread {
            start.await()
            publicationSafe.set(gate.sealForPublication())
            done.countDown()
        }.start()
        start.countDown()

        assertTrue(done.await(5L, TimeUnit.SECONDS))
        assertTrue(poisoned.get() xor publicationSafe.get())
        assertEquals(publicationSafe.get(), gate.sealForPublication())
    }

    @Test
    fun `sealed old generation cannot poison a newly opened session gate`() {
        val gateRef = AtomicReference<Npv10ProducerBoundaryGate?>()
        val oldGate = Npv10ProducerBoundaryGate().also { it.open() }
        assertTrue(gateRef.compareAndSet(null, oldGate))
        assertTrue(oldGate.establish())
        assertTrue(oldGate.sealForPublication())
        assertTrue(gateRef.compareAndSet(oldGate, null))

        val newGate = Npv10ProducerBoundaryGate().also { it.open() }
        assertTrue(gateRef.compareAndSet(null, newGate))
        assertFalse(oldGate.poison())
        assertEquals(Npv10ProducerBoundaryGate.State.OPEN, newGate.state)
    }
}
