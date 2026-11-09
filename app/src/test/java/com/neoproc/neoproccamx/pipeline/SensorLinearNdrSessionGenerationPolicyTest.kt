package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorLinearNdrSessionGenerationPolicyTest {
    @Test
    fun `only the exact assigned Camera2 generation is current`() {
        assertTrue(SensorLinearNdrSessionGenerationPolicy.isCurrent(42L, 42L))
        assertFalse(SensorLinearNdrSessionGenerationPolicy.isCurrent(41L, 42L))
        assertFalse(SensorLinearNdrSessionGenerationPolicy.isCurrent(43L, 42L))
    }

    @Test
    fun `unassigned frame or current generation fails closed`() {
        assertFalse(SensorLinearNdrSessionGenerationPolicy.isCurrent(-1L, 0L))
        assertFalse(SensorLinearNdrSessionGenerationPolicy.isCurrent(0L, -1L))
        assertFalse(SensorLinearNdrSessionGenerationPolicy.isCurrent(-1L, -1L))
    }
}
