package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.recording.EliteVisionMeasurementFlag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s6-f08 — locks the Elite-Vision measurement debug-broadcast wiring to the key documented on
 * [EliteVisionMeasurementFlag]. The receiver forwards this extra to the process-global flag, so the
 * enable command advertised in the flag's own docstring is now real rather than dead documentation.
 * The flag round-trip is restored in a finally so this pure-JVM test never leaks the process-global
 * gate into sibling tests.
 */
class EliteVisionMeasurementDebugCommandTest {

    @Test
    fun extraKeyMatchesDocumentedBroadcastCommand() {
        assertEquals("debug.elite_vision_measure", DebugCommandReceiver.EXTRA_ELITE_VISION_MEASURE)
    }

    @Test
    fun flagRoundTripsSoTheDocumentedEnableActuallyToggles() {
        val original = EliteVisionMeasurementFlag.enabled
        try {
            EliteVisionMeasurementFlag.set(false)
            assertFalse(EliteVisionMeasurementFlag.enabled)
            EliteVisionMeasurementFlag.set(true)
            assertTrue(EliteVisionMeasurementFlag.enabled)
        } finally {
            EliteVisionMeasurementFlag.set(original)
        }
    }
}
