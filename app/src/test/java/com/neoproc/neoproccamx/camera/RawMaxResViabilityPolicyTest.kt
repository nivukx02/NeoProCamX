package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for [RawMaxResViabilityPolicy]: distinguishes hard blocks (physically impossible — both
 * AUTO and a forced request must fall back) from soft blocks (risky — only AUTO falls back).
 */
class RawMaxResViabilityPolicyTest {

    private val frame = 100L * 1024 * 1024 // 100 MB max-res RAW frame
    private val ample = 8L * 1024 * 1024 * 1024 // 8 GB

    private fun resolve(
        free: Long = ample,
        mem: Long = ample,
        thermal: RawThermalLevel = RawThermalLevel.OK
    ) = RawMaxResViabilityPolicy.resolve(
        estPerFrameBytes = frame,
        freeStorageBytes = free,
        availableMemBytes = mem,
        thermal = thermal
    )

    @Test
    fun viableWhenEverythingAmple() {
        val v = resolve()
        assertTrue(v.viable)
        assertFalse(v.hardBlocked)
    }

    @Test
    fun hardBlockWhenMemoryCannotHoldOneFrame() {
        val v = resolve(mem = frame - 1)
        assertFalse(v.viable)
        assertTrue(v.hardBlocked)
    }

    @Test
    fun hardBlockWhenStorageCannotHoldOneFrame() {
        val v = resolve(free = frame - 1)
        assertFalse(v.viable)
        assertTrue(v.hardBlocked)
    }

    @Test
    fun hardBlockWhenThermalCritical() {
        val v = resolve(thermal = RawThermalLevel.CRITICAL)
        assertFalse(v.viable)
        assertTrue(v.hardBlocked)
    }

    @Test
    fun softBlockWhenMemoryBelowReaderQueueButAboveOneFrame() {
        // Enough for one frame, but not for the reader depth (4 frames).
        val v = resolve(mem = frame * 3)
        assertFalse(v.viable)
        assertFalse(v.hardBlocked)
    }

    @Test
    fun softBlockWhenStorageBelowBurstHeadroomButAboveOneFrame() {
        // Enough for one frame, but not for the 8-frame burst headroom.
        val v = resolve(free = frame * 4)
        assertFalse(v.viable)
        assertFalse(v.hardBlocked)
    }

    @Test
    fun softBlockWhenHot() {
        val v = resolve(thermal = RawThermalLevel.HOT)
        assertFalse(v.viable)
        assertFalse(v.hardBlocked)
    }

    @Test
    fun warmIsStillViable() {
        assertTrue(resolve(thermal = RawThermalLevel.WARM).viable)
    }

    @Test
    fun unknownStorageDoesNotBlock() {
        // A failed StatFs probe returns 0 — treat as unknown, not "no space".
        val v = resolve(free = 0L)
        assertTrue(v.viable)
        assertFalse(v.hardBlocked)
    }

    @Test
    fun unknownMemoryDoesNotBlock() {
        val v = resolve(mem = 0L)
        assertTrue(v.viable)
        assertFalse(v.hardBlocked)
    }

    @Test
    fun thermalLabelMapping() {
        // Critical family → hard-blocking CRITICAL.
        assertEquals(RawThermalLevel.CRITICAL, RawMaxResViabilityPolicy.thermalLevelFromLabel("Critical"))
        assertEquals(RawThermalLevel.CRITICAL, RawMaxResViabilityPolicy.thermalLevelFromLabel("Emergency"))
        assertEquals(RawThermalLevel.CRITICAL, RawMaxResViabilityPolicy.thermalLevelFromLabel("Shutdown"))
        // Severe throttling is a soft block (AUTO falls back; forced may override).
        assertEquals(RawThermalLevel.HOT, RawMaxResViabilityPolicy.thermalLevelFromLabel("Severe"))
        assertEquals(RawThermalLevel.HOT, RawMaxResViabilityPolicy.thermalLevelFromLabel("throttling"))
        // Moderate/Light are still viable.
        assertEquals(RawThermalLevel.WARM, RawMaxResViabilityPolicy.thermalLevelFromLabel("Moderate"))
        assertEquals(RawThermalLevel.WARM, RawMaxResViabilityPolicy.thermalLevelFromLabel("Light"))
        assertEquals(RawThermalLevel.OK, RawMaxResViabilityPolicy.thermalLevelFromLabel("Nominal"))
        assertEquals(RawThermalLevel.OK, RawMaxResViabilityPolicy.thermalLevelFromLabel("Unknown"))
        assertEquals(RawThermalLevel.OK, RawMaxResViabilityPolicy.thermalLevelFromLabel(null))
    }
}
