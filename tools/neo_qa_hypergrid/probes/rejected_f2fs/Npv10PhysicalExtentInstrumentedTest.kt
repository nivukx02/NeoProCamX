// Two physical failures retained unchanged in meaning; experiment excluded from production.
package com.neoproc.neoproccamx.recording

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Executes the production JNI under the actual app UID; no camera/FPS pass is inferred. */
@RunWith(AndroidJUnit4::class)
class Npv10PhysicalExtentInstrumentedTest {
    @Test fun initialAndRollingExtentsPreserveEveryByteAndZeroUnwrittenData() = withFile { file, target ->
        val first = Npv10PhysicalExtentAllocator.allocate(target.fd, 0L, SLICE)
        Log.i(TAG, "APP_PHYSICAL_EXTENT initial=$first appUid=${android.os.Process.myUid()}")
        assertTrue("F2FS materialization not admitted by this app/device: $first", first.materialized)
        assertEquals(SLICE, file.length())
        verifyZeroRange(target, 0L, SLICE)
        val prefix = ByteArray(1024 * 1024) { ((it * 37) xor (it ushr 7)).toByte() }
        target.seek(0L)
        target.write(prefix)
        val growth = Npv10PhysicalExtentAllocator.allocate(target.fd, SLICE, SLICE)
        assertTrue(growth.materialized)
        target.fd.sync()
        assertEquals(2L * SLICE, file.length())
        target.seek(0L)
        val actual = ByteArray(prefix.size)
        target.readFully(actual)
        assertArrayEquals(prefix, actual)
        verifyZeroRange(target, prefix.size.toLong(), 2L * SLICE)
        // Native does not own/close the Java descriptor, and normal final truncation still works.
        target.setLength(prefix.size.toLong())
        target.fd.sync()
        assertEquals(prefix.size.toLong(), file.length())
    }

    @Test fun concurrentReservationCallsOnDuplicatedDescriptorDoNotReleaseAnotherOwner() = withFile { file, target ->
        val workers = Executors.newFixedThreadPool(2)
        try {
            val results = listOf(0L, SLICE).map { offset ->
                workers.submit<Npv10PhysicalExtentAllocator.Result> {
                    Npv10PhysicalExtentAllocator.allocate(target.fd, offset, SLICE)
                }
            }
            results.forEach { assertTrue(it.get(60L, TimeUnit.SECONDS).materialized) }
            assertEquals(2L * SLICE, file.length())
            verifyZeroRange(target, 0L, 2L * SLICE)
        } finally {
            workers.shutdown()
            assertTrue(workers.awaitTermination(60L, TimeUnit.SECONDS))
        }
    }

    @Test fun invalidBoundsDoNotMutateTheFile() = withFile { file, target ->
        assertThrows(IllegalArgumentException::class.java) { Npv10PhysicalExtentAllocator.allocate(target.fd, -1L, 1L) }
        assertThrows(IllegalArgumentException::class.java) { Npv10PhysicalExtentAllocator.allocate(target.fd, 0L, 0L) }
        assertThrows(ArithmeticException::class.java) { Npv10PhysicalExtentAllocator.allocate(target.fd, Long.MAX_VALUE, 1L) }
        assertEquals(0L, file.length())
    }

    private fun verifyZeroRange(target: RandomAccessFile, from: Long, to: Long) {
        target.seek(from)
        var remaining = to - from
        val bytes = ByteArray(128 * 1024)
        while (remaining > 0L) {
            val count = minOf(remaining, bytes.size.toLong()).toInt()
            target.readFully(bytes, 0, count)
            for (index in 0 until count) assertEquals("Unwritten allocation exposed nonzero data", 0, bytes[index].toInt())
            remaining -= count
        }
    }

    private fun withFile(action: (File, RandomAccessFile) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("qa-physical-extent-", ".tmp", context.cacheDir)
        try { RandomAccessFile(file, "rw").use { action(file, it) } }
        finally { assertTrue("Only this exact QA temporary file is removed", file.delete()) }
    }

    companion object {
        private const val SLICE = 32L * 1024L * 1024L
        private const val TAG = "Npv10PhysicalExtentQA"
    }
}
