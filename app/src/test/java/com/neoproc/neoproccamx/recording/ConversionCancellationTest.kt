package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CancellationException

class ConversionCancellationTest {
    @Test fun cancellationIsMonotonicAndCarriesTheFirstReason() {
        val signal = ConversionCancellationSignal()
        assertTrue(signal.shouldContinue())
        assertTrue(signal.cancel("user requested stop"))
        assertFalse(signal.cancel("later reason"))
        assertFalse(signal.shouldContinue())

        val failure = runCatching { signal.throwIfCancellationRequested() }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertTrue(failure?.message.orEmpty().contains("user requested stop"))
    }

    @Test fun contextInstallsAndRestoresNestedSignals() {
        val outer = ConversionCancellationSignal()
        val inner = ConversionCancellationSignal()
        ConversionCancellationContext.runWith(outer) {
            assertSame(outer, ConversionCancellationContext.currentSignal())
            ConversionCancellationContext.runWith(inner) {
                assertSame(inner, ConversionCancellationContext.currentSignal())
            }
            assertSame(outer, ConversionCancellationContext.currentSignal())
        }
    }

    @Test fun contextRejectsWorkCancelledBeforeStart() {
        val signal = ConversionCancellationSignal().also { it.cancel() }
        var ran = false
        val failure = runCatching {
            ConversionCancellationContext.runWith(signal) { ran = true }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertFalse(ran)
    }
}
