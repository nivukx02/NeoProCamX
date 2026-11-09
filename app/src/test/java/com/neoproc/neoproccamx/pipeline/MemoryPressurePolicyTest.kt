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

    @Test
    fun `runtime pressure uses measured system pressure`() {
        val pressure = RuntimeMemoryPressureSignalPolicy.pressure(
            stats(availableMb = 1_000, totalMb = 10_000, heapPercent = 30)
        )

        assertEquals(0.90f, pressure, 0.001f)
    }

    @Test
    fun `warning severity establishes a conservative pressure floor`() {
        val pressure = RuntimeMemoryPressureSignalPolicy.pressure(
            stats(
                availableMb = 8_000,
                totalMb = 10_000,
                heapPercent = 20,
                warning = MemoryProfiler.MemoryStats.WarningLevel.CRITICAL
            )
        )

        assertEquals(0.93f, pressure, 0.001f)
    }

    @Test
    fun `runtime memory usage is converted to bytes without decimal units`() {
        assertEquals(
            512L * 1024L * 1024L,
            RuntimeMemoryPressureSignalPolicy.usedBytes(stats(totalUsageMb = 512))
        )
    }

    private fun stats(
        availableMb: Int = 4_000,
        totalMb: Int = 10_000,
        heapPercent: Int = 25,
        totalUsageMb: Int = 400,
        warning: MemoryProfiler.MemoryStats.WarningLevel =
            MemoryProfiler.MemoryStats.WarningLevel.NORMAL
    ) = MemoryProfiler.MemoryStats(
        javaHeapUsageMb = 100,
        totalUsageMb = totalUsageMb,
        utilizationPercent = heapPercent,
        pssKb = totalUsageMb.toLong() * 1024L,
        oomReserveFreeMb = availableMb,
        javaHeapMaxMb = 400,
        javaHeapUtilizationPercent = heapPercent,
        nativePssMb = totalUsageMb,
        systemTotalMb = totalMb,
        systemLowMemory = warning == MemoryProfiler.MemoryStats.WarningLevel.CRITICAL,
        warningLevel = warning
    )
}
