package com.neoproc.neoproccamx.camera

import com.neoproc.neoproccamx.pipeline.SynchronousImageIngressGate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ReaderBindingTest {

    @Test
    fun `post-seal callback performs zero acquisition or dispatch`() {
        val bindings = ReaderBindingRegistry<Any>()
        val ingress = SynchronousImageIngressGate {}
        val admission = ReaderCallbackAdmission(bindings, ingress)
        val reader = Any()
        val binding = bindings.bind(ReaderKind.HARDWARE_IMPORT, reader)
        val acquisitionCalls = AtomicInteger(0)
        val seal = ingress.seal()

        try {
            assertFalse(admission.dispatch(binding, reader) { acquisitionCalls.incrementAndGet() })
            assertEquals(0, acquisitionCalls.get())
            assertFalse(ingress.hasInFlight())
        } finally {
            seal.close()
        }
    }

    @Test
    fun `old reader binding is rejected after replacement for every reader kind`() {
        ReaderKind.entries.forEach { kind ->
            val bindings = ReaderBindingRegistry<Any>()
            val ingress = SynchronousImageIngressGate {}
            val admission = ReaderCallbackAdmission(bindings, ingress)
            val oldReader = Any()
            val newReader = Any()
            val oldBinding = bindings.bind(kind, oldReader)
            val newBinding = bindings.bind(kind, newReader)
            val dispatchCalls = AtomicInteger(0)

            assertTrue("$kind old binding was not retired", oldBinding.retired)
            assertFalse(admission.dispatch(oldBinding, oldReader) { dispatchCalls.incrementAndGet() })
            assertFalse(admission.dispatch(oldBinding, newReader) { dispatchCalls.incrementAndGet() })
            assertTrue(admission.dispatch(newBinding, newReader) { dispatchCalls.incrementAndGet() })
            assertEquals("$kind dispatched a stale reader callback", 1, dispatchCalls.get())
        }
    }

    @Test
    fun `same reader rebound to a new epoch rejects queued old callback`() {
        val bindings = ReaderBindingRegistry<Any>()
        val ingress = SynchronousImageIngressGate {}
        val admission = ReaderCallbackAdmission(bindings, ingress)
        val reader = Any()
        val first = bindings.bind(ReaderKind.YUV, reader)
        val second = bindings.bind(ReaderKind.YUV, reader)
        val dispatchCalls = AtomicInteger(0)

        assertNotEquals(first.epoch, second.epoch)
        assertTrue(first.retired)
        assertSame(second, bindings.current(ReaderKind.YUV))
        assertFalse(admission.dispatch(first, reader) { dispatchCalls.incrementAndGet() })
        assertTrue(admission.dispatch(second, reader) { dispatchCalls.incrementAndGet() })
        assertEquals(1, dispatchCalls.get())
    }

    @Test
    fun `retirement is monotonic and cannot clear a newer epoch`() {
        val bindings = ReaderBindingRegistry<Any>()
        val reader = Any()
        val first = bindings.bind(ReaderKind.RAW, reader)
        val second = bindings.bind(ReaderKind.RAW, reader)

        bindings.retire(first)

        assertTrue(first.retired)
        assertFalse(second.retired)
        assertSame(second, bindings.current(ReaderKind.RAW))
        assertTrue(bindings.accepts(second, reader))
    }
}
