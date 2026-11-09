package com.neoproc.neoproccamx.recording

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the reversible contract of the develop-`.mov` → MediaStore publish copy buffer: default is a large
 * buffer (the byte-identical throughput fix for the multi-GB gallery publish), set() coerces into a safe
 * range, and it can be restored to the JDK-default 8 KiB chunk to reproduce the original slow copy exactly.
 */
class RecordDevelopPublishCopyPolicyTest {

    @After
    fun reset() {
        RecordDevelopPublishCopyPolicy.reset()
    }

    @Test
    fun `default buffer is the large multi-MiB copy chunk`() {
        assertEquals(RecordDevelopPublishCopyPolicy.DEFAULT_COPY_BUFFER_BYTES, RecordDevelopPublishCopyPolicy.bufferBytes)
        assertTrue(
            "default must beat the JDK 8 KiB copyTo buffer",
            RecordDevelopPublishCopyPolicy.bufferBytes > RecordDevelopPublishCopyPolicy.MIN_COPY_BUFFER_BYTES
        )
    }

    @Test
    fun `set clamps below the minimum up to the JDK-default floor`() {
        RecordDevelopPublishCopyPolicy.set(16)
        assertEquals(RecordDevelopPublishCopyPolicy.MIN_COPY_BUFFER_BYTES, RecordDevelopPublishCopyPolicy.bufferBytes)
    }

    @Test
    fun `set clamps above the maximum down to the ceiling`() {
        RecordDevelopPublishCopyPolicy.set(Int.MAX_VALUE)
        assertEquals(RecordDevelopPublishCopyPolicy.MAX_COPY_BUFFER_BYTES, RecordDevelopPublishCopyPolicy.bufferBytes)
    }

    @Test
    fun `set honours an in-range value and reset restores the default`() {
        val inRange = 2 * 1024 * 1024
        RecordDevelopPublishCopyPolicy.set(inRange)
        assertEquals(inRange, RecordDevelopPublishCopyPolicy.bufferBytes)
        RecordDevelopPublishCopyPolicy.reset()
        assertEquals(RecordDevelopPublishCopyPolicy.DEFAULT_COPY_BUFFER_BYTES, RecordDevelopPublishCopyPolicy.bufferBytes)
    }
}
