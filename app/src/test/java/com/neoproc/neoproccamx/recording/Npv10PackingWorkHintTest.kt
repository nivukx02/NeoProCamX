package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class Npv10PackingWorkHintTest {
    private class Backend : Npv10PackingWorkHint.Backend {
        val targets = mutableListOf<Long>()
        val durations = mutableListOf<Long>()
        var closes = 0
        var reject: String? = null
        override fun updateTarget(durationNs: Long) {
            if (reject == "target") error("target rejected")
            targets += durationNs
        }
        override fun reportActual(durationNs: Long) {
            if (reject == "report") error("report rejected")
            durations += durationNs
        }
        override fun close() {
            closes++
            if (reject == "close") error("close rejected")
        }
    }

    @Test fun budgetUsesSelectedFpsWithoutChangingIt() {
        assertEquals(20_000_000L, Npv10PackingWorkHint.targetDurationNs(25))
        assertEquals(16_666_666L, Npv10PackingWorkHint.targetDurationNs(30))
        assertEquals(8_333_333L, Npv10PackingWorkHint.targetDurationNs(60))
        assertEquals(500_000_000L, Npv10PackingWorkHint.targetDurationNs(1))
        assertEquals(500_000L, Npv10PackingWorkHint.targetDurationNs(1_000))
    }

    @Test fun invalidFpsIsRejected() {
        for (fps in listOf(Int.MIN_VALUE, -1, 0, 1_001, Int.MAX_VALUE)) {
            expectFailure<IllegalArgumentException> { Npv10PackingWorkHint.targetDurationNs(fps) }
        }
    }

    @Test fun unchangedFpsDoesNotMutateSessionEveryFrame() {
        val backend = Backend()
        Npv10PackingWorkHint(backend).use { hint ->
            repeat(100) { hint.prepareFrame(25); hint.reportActual(21_000_000L + it) }
            assertTrue(backend.targets.isEmpty())
            assertEquals(100, backend.durations.size)
            assertEquals(100L, hint.reportsAccepted)
        }
        assertEquals(1, backend.closes)
    }

    @Test fun workReportsAreMeasuredValuesIncludingOverBudgetDurations() {
        val backend = Backend()
        Npv10PackingWorkHint(backend).use { hint ->
            listOf(1L, 53_598_542L, 144_858_698L, Long.MAX_VALUE).forEach(hint::reportActual)
        }
        assertEquals(listOf(1L, 53_598_542L, 144_858_698L, Long.MAX_VALUE), backend.durations)
    }

    @Test fun fpsChangeUpdatesExactlyOnceAndCanReturnToPreviousFps() {
        val backend = Backend()
        Npv10PackingWorkHint(backend).use { hint ->
            listOf(25, 30, 30, 60, 60, 25).forEach(hint::prepareFrame)
        }
        assertEquals(listOf(16_666_666L, 8_333_333L, 20_000_000L), backend.targets)
    }

    @Test fun absentAdvisoryBackendDoesNotAffectCaptureWork() {
        Npv10PackingWorkHint(null).use { hint ->
            hint.prepareFrame(30)
            hint.reportActual(53_598_542L)
            assertFalse(hint.active)
            assertFalse(hint.backendFailed)
            assertEquals(0L, hint.reportsAccepted)
        }
    }

    @Test fun reportRejectionClosesOnceAndRemainsObservable() {
        val backend = Backend().apply { reject = "report" }
        val failures = mutableListOf<String>()
        Npv10PackingWorkHint(backend) { operation, _ -> failures += operation }.use { hint ->
            repeat(10) { hint.reportActual(53_598_542L) }
            assertTrue(hint.backendFailed)
            assertFalse(hint.active)
            assertEquals(0L, hint.reportsAccepted)
        }
        assertEquals(listOf("report-work"), failures)
        assertEquals(1, backend.closes)
    }

    @Test fun targetRejectionRetiresSessionWithoutFurtherReports() {
        val backend = Backend().apply { reject = "target" }
        val failures = mutableListOf<String>()
        Npv10PackingWorkHint(backend) { operation, _ -> failures += operation }.use { hint ->
            hint.prepareFrame(30)
            hint.reportActual(53_598_542L)
            assertTrue(hint.backendFailed)
            assertTrue(backend.durations.isEmpty())
        }
        assertEquals(listOf("update-target"), failures)
        assertEquals(1, backend.closes)
    }

    @Test fun closeIsExactlyOnceEvenWhenVendorCloseThrows() {
        val backend = Backend().apply { reject = "close" }
        val failures = mutableListOf<String>()
        val hint = Npv10PackingWorkHint(backend) { operation, _ -> failures += operation }
        hint.close()
        hint.close()
        assertTrue(hint.backendFailed)
        assertFalse(hint.active)
        assertEquals(1, backend.closes)
        assertEquals(listOf("close"), failures)
    }

    @Test fun sharedVendorExceptionCannotEscapeOptionalSessionRetirement() {
        for (rejectTarget in listOf(false, true)) {
            val vendorFailure = IllegalStateException("reused vendor failure")
            var closes = 0
            val observed = mutableListOf<Pair<String, RuntimeException>>()
            val backend = object : Npv10PackingWorkHint.Backend {
                override fun updateTarget(durationNs: Long) { throw vendorFailure }
                override fun reportActual(durationNs: Long) { throw vendorFailure }
                override fun close() { closes++; throw vendorFailure }
            }
            Npv10PackingWorkHint(backend) { operation, failure ->
                observed += operation to failure
            }.use { hint ->
                if (rejectTarget) hint.prepareFrame(30) else hint.reportActual(20_000_000L)
                // Later captured frames still execute; the rejected optional backend stays retired.
                hint.prepareFrame(25)
                hint.reportActual(40_000_001L)
                assertFalse(hint.active)
                assertTrue(hint.backendFailed)
                assertEquals(0L, hint.reportsAccepted)
            }
            assertEquals(1, closes)
            assertEquals(1, observed.size)
            assertEquals(if (rejectTarget) "update-target" else "report-work", observed.single().first)
            assertSame(vendorFailure, observed.single().second)
            assertTrue(vendorFailure.suppressed.isEmpty())
        }
    }

    @Test fun distinctRetirementFailureRemainsAttachedToOriginalVendorFailure() {
        val reportFailure = IllegalStateException("report rejected")
        val closeFailure = IllegalStateException("close rejected")
        var closes = 0
        var observed: RuntimeException? = null
        val backend = object : Npv10PackingWorkHint.Backend {
            override fun updateTarget(durationNs: Long) = Unit
            override fun reportActual(durationNs: Long) { throw reportFailure }
            override fun close() { closes++; throw closeFailure }
        }
        Npv10PackingWorkHint(backend) { _, failure -> observed = failure }.use { hint ->
            hint.reportActual(53_598_542L)
            hint.reportActual(20_000_000L)
            assertFalse(hint.active)
        }
        assertEquals(1, closes)
        assertSame(reportFailure, observed)
        assertEquals(1, reportFailure.suppressed.size)
        assertSame(closeFailure, reportFailure.suppressed.single())
    }

    @Test fun workAfterCloseIsRejected() {
        val hint = Npv10PackingWorkHint(Backend())
        hint.close()
        expectFailure<IllegalStateException> { hint.prepareFrame(25) }
        expectFailure<IllegalStateException> { hint.reportActual(1L) }
    }

    @Test fun zeroOrNegativeDurationCannotInventValidTelemetry() {
        Npv10PackingWorkHint(Backend()).use { hint ->
            for (duration in listOf(0L, -1L, Long.MIN_VALUE)) {
                expectFailure<IllegalArgumentException> { hint.reportActual(duration) }
            }
            assertEquals(0L, hint.reportsAccepted)
        }
    }

    @Test fun anotherThreadCannotChangeReportOrCloseTheOwnerSession() {
        val backend = Backend()
        Npv10PackingWorkHint(backend).use { hint ->
            val failure = AtomicReference<Throwable>()
            val other = Thread {
                try {
                    expectFailure<IllegalStateException> { hint.prepareFrame(30) }
                    expectFailure<IllegalStateException> { hint.reportActual(1L) }
                    expectFailure<IllegalStateException> { hint.close() }
                } catch (problem: Throwable) { failure.set(problem) }
            }
            other.start()
            other.join(5_000)
            assertFalse(other.isAlive)
            assertSame(null, failure.get())
            assertTrue(hint.active)
        }
        assertEquals(1, backend.closes)
    }

    private inline fun <reified T : Throwable> expectFailure(action: () -> Unit) {
        try {
            action()
        } catch (failure: Throwable) {
            if (failure is T) return
            throw failure
        }
        throw AssertionError("Expected ${T::class.java.simpleName}")
    }
}
