package com.neoproc.neoproccamx.recording

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Npv10NativeFrameBufferInstrumentedTest {
    @Test
    fun productionSizedMappingsAreWritableAndExplicitlyReusable() {
        val capacities = intArrayOf(
            23_592_960,
            37_748_736,
            37_748_736,
            50_331_648
        )
        val owners = ArrayList<Npv10NativeFrameBuffer>(capacities.size)
        try {
            capacities.forEachIndexed { index, capacity ->
                val owner = Npv10NativeFrameBuffer.allocate(capacity)
                owners += owner
                val buffer = owner.buffer
                assertTrue(buffer.isDirect)
                assertEquals(capacity, buffer.capacity())
                var offset = 0
                while (offset < capacity) {
                    buffer.put(offset, (index + offset).toByte())
                    offset += PAGE_BYTES
                }
                buffer.put(capacity - 1, index.toByte())
            }
        } finally {
            owners.asReversed().forEach(Npv10NativeFrameBuffer::close)
        }

        val maximumCapacity = requireNotNull(capacities.maxOrNull())
        Npv10NativeFrameBuffer.allocate(maximumCapacity).use { reused ->
            assertEquals(maximumCapacity, reused.buffer.capacity())
            reused.buffer.put(0, 0x5A.toByte())
            reused.buffer.put(reused.buffer.capacity() - 1, 0xA5.toByte())
        }
    }

    private companion object {
        const val PAGE_BYTES = 4096
    }
}
