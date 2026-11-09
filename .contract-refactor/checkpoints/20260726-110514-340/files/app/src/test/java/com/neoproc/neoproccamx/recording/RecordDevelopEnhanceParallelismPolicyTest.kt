package com.neoproc.neoproccamx.recording

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the reversible contract of the offline SDR/NDR develop enhance parallelism gate: DEFAULT ON (the
 * multi-core enhance is a byte-identical, no-downgrade speed win) and flippable OFF at runtime via
 * `debug.record_enhance_parallel` to fall back to the proven sequential enhance.
 */
class RecordDevelopEnhanceParallelismPolicyTest {

    @After
    fun reset() {
        RecordDevelopEnhanceParallelismPolicy.set(true)
    }

    @Test
    fun `default is on so the flagship develop fans the enhance across cores`() {
        assertTrue(RecordDevelopEnhanceParallelismPolicy.enabled)
    }

    @Test
    fun `set toggles the process-global gate both ways`() {
        RecordDevelopEnhanceParallelismPolicy.set(false)
        assertFalse(RecordDevelopEnhanceParallelismPolicy.enabled)
        RecordDevelopEnhanceParallelismPolicy.set(true)
        assertTrue(RecordDevelopEnhanceParallelismPolicy.enabled)
    }
}
