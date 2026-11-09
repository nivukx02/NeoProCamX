package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryPressurePolicyTest {

    @Test
    fun `native camera pss does not create false critical memory`() {
        val level = MemoryPressurePolicy.classify(
            javaHeapUtilizationPercent = 42,
            systemAvailableMb = 2048,
            systemLowMemory = false
        )

        assertEquals(MemoryProfiler.MemoryStats.WarningLevel.NORMAL, level)
    }

    @Test
    fun `java heap exhaustion remains critical even with system ram available`() {
        val level = MemoryPressurePolicy.classify(
            javaHeapUtilizationPercent = 96,
            systemAvailableMb = 4096,
            systemLowMemory = false
        )

        assertEquals(MemoryProfiler.MemoryStats.WarningLevel.CRITICAL, level)
    }

    @Test
    fun `android low memory state remains critical`() {
        val level = MemoryPressurePolicy.classify(
            javaHeapUtilizationPercent = 20,
            systemAvailableMb = 1024,
            systemLowMemory = true
        )

        assertEquals(MemoryProfiler.MemoryStats.WarningLevel.CRITICAL, level)
    }
}
