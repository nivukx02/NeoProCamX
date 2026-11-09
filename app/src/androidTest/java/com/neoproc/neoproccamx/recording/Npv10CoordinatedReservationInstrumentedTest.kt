package com.neoproc.neoproccamx.recording

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class Npv10CoordinatedReservationInstrumentedTest {
    @Test(timeout = 30_000L)
    fun exactContiguousPrefixSurvivesConcurrentDurabilityAndRetainsOriginalBytes() = withOwner { file, engine ->
        val expected = prefix()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val syncing = executor.submit { repeat(32) { engine.forceCommittedFrames(0L) } }
            repeat(32) { index ->
                engine.allocatePhysicalRange(INITIAL_BYTES + index * SLICE_BYTES, SLICE_BYTES)
                assertEquals(INITIAL_BYTES + (index + 1L) * SLICE_BYTES, file.length())
            }
            syncing.get(15, TimeUnit.SECONDS)
            engine.forceCommittedFrames(0L)
            RandomAccessFile(file, "r").use { input ->
                val actual = ByteArray(expected.size)
                input.readFully(actual)
                assertArrayEquals(expected, actual)
                assertTrue(Os.fstat(input.fd).st_blocks * 512L >= file.length())
            }
            assertEquals(0L, engine.snapshot().framesCommitted)
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS))
        }
    }

    @Test(timeout = 30_000L)
    fun staleGapOverflowOversizedAndClosedClaimsCannotPublishAllocation() = withOwner { file, engine ->
        val handle = engine.processingHandle()
        assertFalse(Npv10DirectMappedCommitNative.nativeAllocatePhysicalRange(handle, INITIAL_BYTES + 1L, SLICE_BYTES))
        assertFalse(Npv10DirectMappedCommitNative.nativeAllocatePhysicalRange(handle, INITIAL_BYTES, 0L))
        assertFalse(Npv10DirectMappedCommitNative.nativeAllocatePhysicalRange(handle, Long.MAX_VALUE - 1L, SLICE_BYTES))
        assertFalse(Npv10DirectMappedCommitNative.nativeAllocatePhysicalRange(handle, INITIAL_BYTES,
            Npv10RollingExtentAllocator.LIVE_SLICE_BYTES + 1L))
        assertEquals(INITIAL_BYTES, file.length())
        engine.allocatePhysicalRange(INITIAL_BYTES, SLICE_BYTES)
        assertThrows(IOException::class.java) { engine.allocatePhysicalRange(INITIAL_BYTES, SLICE_BYTES) }
        assertEquals(INITIAL_BYTES + SLICE_BYTES, file.length())
        engine.allocatePhysicalRange(INITIAL_BYTES + SLICE_BYTES, SLICE_BYTES)
        val finalLength = file.length()
        engine.close()
        assertFalse(Npv10DirectMappedCommitNative.nativeAllocatePhysicalRange(handle, finalLength, SLICE_BYTES))
        assertEquals(finalLength, file.length())
    }

    private fun withOwner(test: (File, Npv10DirectMappedCommitEngine) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "lq001-coordinated-reservation-${System.nanoTime()}.bin")
        try {
            RandomAccessFile(file, "rw").use { output ->
                Os.posix_fallocate(output.fd, 0L, INITIAL_BYTES)
                output.write(prefix())
            }
            Npv10DirectMappedCommitEngine.open(file = file, dataStartOffset = DATA_START,
                frameHeaderBytes = 28, payloadBytes = 128, slotCount = 2, reservedBytes = INITIAL_BYTES)
                .use { test(file, it) }
        } finally {
            assertTrue("Test-owned scratch could not be removed", !file.exists() || file.delete())
        }
    }

    private fun prefix() = ByteArray(DATA_START.toInt()) { ((it * 31) xor 0x53).toByte() }

    private companion object {
        const val DATA_START = 4096L
        const val INITIAL_BYTES = 64L * 1024L
        const val SLICE_BYTES = 64L * 1024L
    }
}
