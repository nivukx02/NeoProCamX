package com.neoproc.neoproccamx.ui

import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePreviewLutSnapshotTest {

    @Test
    fun `disabled and enabled identities are internally coherent`() {
        val disabled = NativePreviewLutSnapshot(renderProfileGeneration = 7L)
        val enabled = NativePreviewLutSnapshot(
            enabled = true,
            size = 33,
            contentHash = 0x51A7,
            renderProfileGeneration = 8L
        )

        assertFalse(disabled.enabled)
        assertEquals(0, disabled.size)
        assertEquals(0, disabled.contentHash)
        assertEquals(7L, disabled.renderProfileGeneration)
        assertTrue(enabled.enabled)
        assertEquals(33, enabled.size)
        assertEquals(0x51A7, enabled.contentHash)
        assertEquals(8L, enabled.renderProfileGeneration)
    }

    @Test
    fun `invalid torn lut identities fail closed`() {
        assertInvalid {
            NativePreviewLutSnapshot(enabled = false, size = 33, contentHash = 0)
        }
        assertInvalid {
            NativePreviewLutSnapshot(enabled = true, size = 1, contentHash = 123)
        }
        assertInvalid {
            NativePreviewLutSnapshot(enabled = false, size = 0, contentHash = 123)
        }
        assertInvalid {
            NativePreviewLutSnapshot(enabled = false, size = -1, contentHash = 0)
        }
    }

    @Test
    fun `atomic publication exposes complete old or new lut identity`() {
        val old = NativePreviewLutSnapshot(renderProfileGeneration = 20L)
        val next = NativePreviewLutSnapshot(
            enabled = true,
            size = 65,
            contentHash = 0x6E30,
            renderProfileGeneration = 21L
        )
        val authority = AtomicReference(old)

        assertEquals(old, authority.get())
        assertTrue(authority.compareAndSet(old, next))
        val observed = authority.get()
        assertEquals(next, observed)
        assertTrue(observed.enabled == (observed.size > 1))
        assertTrue(observed.enabled || observed.contentHash == 0)
    }

    private inline fun assertInvalid(block: () -> Unit) {
        var rejected = false
        try {
            block()
        } catch (_: IllegalArgumentException) {
            rejected = true
        }
        assertTrue("invalid LUT identity must be rejected", rejected)
    }
}
