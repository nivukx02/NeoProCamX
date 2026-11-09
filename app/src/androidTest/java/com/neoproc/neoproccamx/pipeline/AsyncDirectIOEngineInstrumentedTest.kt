package com.neoproc.neoproccamx.pipeline

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AsyncDirectIOEngineInstrumentedTest {
    @Test
    fun borrowedSegmentedPacketWritesInOrderAndCompletesExactlyOnce() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val output = File(context.cacheDir, "borrowed-npraw-${System.nanoTime()}.bin")
        val engine = AsyncDirectIOEngine(queueCapacity = 2, maxBorrowedPayloadWrites = 2)
        val completionCount = AtomicInteger(0)
        val completionResult = AtomicReference<Boolean>()
        val completion = CountDownLatch(1)
        val header = byteArrayOf(0x4e, 0x50, 0x52, 0x41, 0x57, 0x02)
        val payloadBytes = ByteArray(4096) { index -> ((index * 29) and 0xff).toByte() }
        val payload = ByteBuffer.allocateDirect(payloadBytes.size).apply {
            put(payloadBytes)
            flip()
        }

        try {
            engine.open(output.absolutePath, availableStorageBytes = 0L)
            assertTrue(
                engine.enqueueBorrowedSegmentedWrite(header, payload) { success ->
                    completionResult.set(success)
                    completionCount.incrementAndGet()
                    completion.countDown()
                }
            )
            assertTrue("Segmented storage completion timed out", completion.await(5, TimeUnit.SECONDS))
            assertEquals(true, completionResult.get())
            assertEquals(1, completionCount.get())
            assertEquals(0, engine.borrowedPayloadWriteCount)
        } finally {
            engine.close()
        }

        val expected = header + payloadBytes
        assertArrayEquals(expected, output.readBytes())
        output.delete()
    }
}
