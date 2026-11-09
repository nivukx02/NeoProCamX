package com.neoproc.neoproccamx.recording

import android.system.Os
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-device regression for the confidentiality/extension failures found in the rejected F2FS
 * pin experiment. The production allocator must retain zero-filled unwritten ranges and preserve
 * existing bytes across the exact rolling slice path. This is not a recording/FPS assertion.
 */
@RunWith(AndroidJUnit4::class)
class Npv10PreallocationSafetyInstrumentedTest {
    @Test fun productionPreparedReservationNeverExposesUnwrittenData() = withFile { file ->
        val requested = RecordingPreallocationPolicy.MIN_RESERVATION_BYTES
        val prepared = Npv10PreparedReservationFactory.create(file,
            RecordingPreallocationPolicy.MIN_SAFETY_RESERVE_BYTES + requested)
        assertTrue("The real prepared reservation must be usable", prepared.isUsable())
        assertEquals(requested, prepared.preallocationResult.reservedBytes)
        RandomAccessFile(file, "r").use { target -> verifyZeroRange(target, 0L, requested) }
    }

    @Test fun productionRollingSlicesPreserveWrittenPrefixAndZeroUnwrittenSuffix() = withFile { file ->
        val slice = Npv10RollingExtentAllocator.LIVE_SLICE_BYTES
        RandomAccessFile(file, "rw").use { target ->
            Os.posix_fallocate(target.fd, 0L, slice)
            val prefix = ByteArray(1024 * 1024) { ((it * 37) xor (it ushr 7)).toByte() }
            target.write(prefix)
            val proof = Npv10RollingExtentAllocator.allocate(
                initialReservedBytes = slice,
                growthBytes = 2L * slice,
                allocateRange = { offset, bytes -> Os.posix_fallocate(target.fd, offset, bytes) },
                sliceLimitBytes = slice
            )
            assertEquals(3L * slice, proof.confirmedEndBytes)
            assertEquals(2, proof.completedSlices)
            target.fd.sync()
            target.seek(0L)
            val actual = ByteArray(prefix.size)
            target.readFully(actual)
            assertArrayEquals(prefix, actual)
            verifyZeroRange(target, prefix.size.toLong(), proof.confirmedEndBytes)
            target.setLength(prefix.size.toLong())
            target.fd.sync()
            assertEquals(prefix.size.toLong(), file.length())
        }
    }

    private fun verifyZeroRange(target: RandomAccessFile, from: Long, to: Long) {
        target.seek(from)
        var remaining = to - from
        val bytes = ByteArray(128 * 1024)
        val zeros = ByteArray(bytes.size)
        while (remaining > 0L) {
            val count = minOf(remaining, bytes.size.toLong()).toInt()
            target.readFully(bytes, 0, count)
            if (count == bytes.size) assertArrayEquals("Unwritten storage must read as zeros", zeros, bytes)
            else for (index in 0 until count) assertEquals(0, bytes[index].toInt())
            remaining -= count
        }
    }

    private fun withFile(action: (File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("qa-preallocation-safety-", ".tmp", context.cacheDir)
        try { action(file) }
        finally { assertTrue("Only the exact owned QA file may be deleted", file.delete()) }
    }
}
