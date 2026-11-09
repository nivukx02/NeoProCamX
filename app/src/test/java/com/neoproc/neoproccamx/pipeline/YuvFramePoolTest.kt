package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertTrue
import org.junit.Test

class YuvFramePoolTest {

    @Test
    fun `pool can allocate high resolution yuv buffers without relying on default frame size`() {
        val pool = YuvFramePool(
            maxPoolSize = 2,
            frameWidth = 640,
            frameHeight = 480
        )
        val requiredBytes = 4096 * 2160 * 3 / 2

        val buffer = pool.acquire(requiredBytes)

        assertTrue(buffer.capacity() >= requiredBytes)
    }

    @Test
    fun `oversized transient yuv buffers are not retained in small realtime pool`() {
        val pool = YuvFramePool(
            maxPoolSize = 2,
            frameWidth = 640,
            frameHeight = 480
        )
        val oversized = pool.acquire(4096 * 2160 * 3 / 2)

        pool.release(oversized)

        assertTrue(pool.snapshot().pooledBuffers == 0)
    }
}
