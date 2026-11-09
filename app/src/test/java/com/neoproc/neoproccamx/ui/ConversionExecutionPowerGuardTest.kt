package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class ConversionExecutionPowerGuardTest {
    @Test
    fun `lease covers complete successful conversion and closes exactly once`() {
        val held = AtomicBoolean(false)
        val closes = AtomicInteger(0)
        val guard = ConversionExecutionPowerGuard {
            check(held.compareAndSet(false, true))
            object : ConversionExecutionLease {
                override val isHeld: Boolean get() = held.get()
                override fun close() {
                    if (held.compareAndSet(true, false)) closes.incrementAndGet()
                }
            }
        }

        val result = guard.run {
            assertTrue(held.get())
            "complete"
        }

        assertEquals("complete", result)
        assertFalse(held.get())
        assertEquals(1, closes.get())
    }

    @Test
    fun `lease is released when conversion fails`() {
        val held = AtomicBoolean(false)
        val closes = AtomicInteger(0)
        val guard = ConversionExecutionPowerGuard {
            held.set(true)
            object : ConversionExecutionLease {
                override val isHeld: Boolean get() = held.get()
                override fun close() {
                    held.set(false)
                    closes.incrementAndGet()
                }
            }
        }

        val failure = runCatching {
            guard.run<Unit> {
                assertTrue(held.get())
                error("conversion failed")
            }
        }.exceptionOrNull()

        assertEquals("conversion failed", failure?.message)
        assertFalse(held.get())
        assertEquals(1, closes.get())
    }

    @Test
    fun `failed lease acquisition prevents unprotected conversion`() {
        var workStarted = false
        val guard = ConversionExecutionPowerGuard {
            throw IllegalStateException("wake lock unavailable")
        }

        val failure = runCatching {
            guard.run { workStarted = true }
        }.exceptionOrNull()

        assertEquals("wake lock unavailable", failure?.message)
        assertFalse(workStarted)
    }

    @Test
    fun `lost execution protection rejects a misleading successful return`() {
        val held = AtomicBoolean(true)
        val guard = ConversionExecutionPowerGuard {
            object : ConversionExecutionLease {
                override val isHeld: Boolean get() = held.get()
                override fun close() = Unit
            }
        }

        val failure = runCatching {
            guard.run {
                held.set(false)
                "must-not-escape"
            }
        }.exceptionOrNull()

        assertEquals("Media-conversion execution protection was lost", failure?.message)
    }
}
