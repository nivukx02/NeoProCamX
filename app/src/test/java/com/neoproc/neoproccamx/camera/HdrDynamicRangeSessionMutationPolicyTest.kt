package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HdrDynamicRangeSessionMutationPolicyTest {

    @Test
    fun `governor-only transition retargets request without rebuilding outputs`() {
        val result = HdrDynamicRangeSessionMutationPolicy.resolve(
            modeChanged = false,
            governorChanged = true,
            analysisChanged = false
        )

        assertFalse(result.rebuildSession)
        assertFalse(result.rebuildReaders)
        assertTrue(result.retargetRepeatingRequest)
    }

    @Test
    fun `analysis membership change rebuilds reader and session`() {
        val result = HdrDynamicRangeSessionMutationPolicy.resolve(
            modeChanged = false,
            governorChanged = true,
            analysisChanged = true
        )

        assertTrue(result.rebuildSession)
        assertTrue(result.rebuildReaders)
        assertFalse(result.retargetRepeatingRequest)
    }

    @Test
    fun `dynamic range profile change rebuilds session without reader churn`() {
        val result = HdrDynamicRangeSessionMutationPolicy.resolve(
            modeChanged = true,
            governorChanged = false,
            analysisChanged = false
        )

        assertTrue(result.rebuildSession)
        assertFalse(result.rebuildReaders)
        assertFalse(result.retargetRepeatingRequest)
    }
}
