package com.neoproc.neoproccamx.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraThermalStatusEvidenceTest {
    @Test fun `every platform level and exact elapsed timestamp remain observable`() {
        for (status in 0..6) {
            val line = CameraThermalStatusCoordinator.telemetryLine(status, 98765432101234L, "CALLBACK")
            assertTrue(line.contains("platformStatus=$status "))
            assertTrue(line.contains("elapsedRealtimeNs=98765432101234 "))
            assertTrue(line.contains("source=CALLBACK valid=true "))
            assertTrue(line.contains("temperatureC=NOT_EXPOSED_BY_ANDROID_STATUS_API"))
        }
    }

    @Test fun `initial snapshot cannot masquerade as a thermal callback`() {
        val line = CameraThermalStatusCoordinator.telemetryLine(4, 1234L, "INITIAL_SNAPSHOT")
        assertTrue(line.contains("source=INITIAL_SNAPSHOT"))
        assertFalse(line.contains("source=CALLBACK"))
    }

    @Test fun `invalid vendor status or timestamp is logged without inventing a valid level`() {
        for ((status, timestamp) in listOf(-1 to 100L, 7 to 100L, 4 to -1L)) {
            val line = CameraThermalStatusCoordinator.telemetryLine(status, timestamp, "CALLBACK")
            assertTrue(line.contains("platformStatus=$status "))
            assertTrue(line.contains("valid=false"))
        }
    }

    @Test fun `thermal evidence is event driven and independent of verbose camera tag`() {
        val source = File("src/main/java/com/neoproc/neoproccamx/ui/CameraThermalStatusCoordinator.kt").readText()
        val callback = source.substringAfter("PowerManager.OnThermalStatusChangedListener { status ->")
            .substringBefore("manager.addThermalStatusListener")
        assertTrue(callback.contains("Log.i(EVIDENCE_TAG, telemetryLine(status, SystemClock.elapsedRealtimeNanos(), \"CALLBACK\"))"))
        assertTrue(callback.contains("onStatus(CameraThermalStatusPolicy.resolve(status))"))
        assertTrue(source.contains("private const val EVIDENCE_TAG = \"CameraThermalStatus\""))
        assertFalse(source.contains("Thread.sleep"))
        assertFalse(source.contains("overrideThermal"))
    }
}
