package com.neoproc.neoproccamx.recording

import android.system.Os
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android filesystem evidence. These are storage/cleanup tests, not camera FPS claims. */
@RunWith(AndroidJUnit4::class)
class Npv10RollingExtentInstrumentedTest {
    @Test
    fun tenGiBLogicalExtensionPreservesConcurrentPrefixWritesAndPhysicalCoverage() {
        assertConcurrentPhysicalExtension(live = false)
    }

    @Test
    fun pacedLiveTenGiBExtensionPreservesEveryByteAndConcurrentWriterProgress() {
        assertConcurrentPhysicalExtension(live = true)
    }

    private fun assertConcurrentPhysicalExtension(live: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val growthBytes = RecordingPreallocationPolicy.MOV_ROLLING_EXTENSION_BYTES
        val initialBytes = Npv10RollingExtentAllocator.MAX_SLICE_BYTES
        val space = Os.statvfs(context.cacheDir.path)
        check(Math.multiplyExact(space.f_bavail, space.f_frsize) >
            growthBytes + initialBytes + RecordingPreallocationPolicy.MIN_SAFETY_RESERVE_BYTES) {
            "Insufficient physical-device storage for the explicit 10-GiB allocation test"
        }
        val file = File.createTempFile("lq001-extent-", ".bin", context.cacheDir)
        val writer = Executors.newSingleThreadExecutor()
        val running = AtomicBoolean(true)
        val written = AtomicLong(0L)
        val firstWrite = CountDownLatch(1)
        val marker = ByteArray(64 * 1024) { (it * 37 + 11).toByte() }
        try {
            RandomAccessFile(file, "rw").use { target ->
                Os.posix_fallocate(target.fd, 0L, initialBytes)
                val work = writer.submit {
                    while (running.get()) {
                        var offset = 0
                        while (offset < marker.size) {
                            val count = Os.pwrite(target.fd, marker, offset,
                                marker.size - offset, offset.toLong())
                            check(count > 0) { "Concurrent prefix write made no progress" }
                            offset += count
                        }
                        written.incrementAndGet()
                        firstWrite.countDown()
                        Thread.yield()
                    }
                }
                try {
                    assertTrue(firstWrite.await(10, TimeUnit.SECONDS))
                    val started = System.nanoTime()
                    val writesBeforeGrowth = written.get()
                    var maximumSliceNs = 0L
                    var lastConfirmed = initialBytes
                    val proof = Npv10RollingExtentAllocator.allocate(initialBytes, growthBytes,
                        allocateRange = { offset, length ->
                            val sliceStarted = System.nanoTime()
                            Os.posix_fallocate(target.fd, offset, length)
                            maximumSliceNs = maxOf(maximumSliceNs, System.nanoTime() - sliceStarted)
                        },
                        onSliceConfirmed = { end, length ->
                            assertEquals(lastConfirmed + length, end)
                            assertTrue(Os.fstat(target.fd).st_size >= end)
                            lastConfirmed = end
                        },
                        yieldBetweenSlices = {
                            if (live) Npv10RollingExtentAllocator.paceLiveAllocation()
                            else Thread.yield()
                        },
                        sliceLimitBytes = if (live) Npv10RollingExtentAllocator.LIVE_SLICE_BYTES
                            else Npv10RollingExtentAllocator.MAX_SLICE_BYTES)
                    assertEquals(if (live) 320 else 80, proof.completedSlices)
                    assertEquals(initialBytes + growthBytes, proof.confirmedEndBytes)
                    assertEquals(proof.confirmedEndBytes, lastConfirmed)
                    val stat = Os.fstat(target.fd)
                    assertEquals(proof.confirmedEndBytes, stat.st_size)
                    assertTrue("Extent must be physically allocated, not sparse",
                        Math.multiplyExact(stat.st_blocks, 512L) >= proof.confirmedEndBytes)
                    assertTrue("Concurrent writer must progress during physical extension",
                        written.get() > writesBeforeGrowth)
                    Log.i("LQ001ExtentTest", "tenGiB=true livePacing=$live slices=${proof.completedSlices} " +
                        "elapsedMs=${(System.nanoTime() - started) / 1_000_000.0} " +
                        "maxSliceMs=${maximumSliceNs / 1_000_000.0} concurrentWrites=${written.get()}")
                } finally {
                    running.set(false)
                    work.get(15, TimeUnit.SECONDS)
                }
                target.fd.sync()
                target.seek(0L)
                val readback = ByteArray(marker.size)
                target.readFully(readback)
                assertArrayEquals(marker, readback)
                assertTrue(written.get() > 0L)
            }
        } finally {
            running.set(false)
            writer.shutdownNow()
            assertTrue(writer.awaitTermination(15, TimeUnit.SECONDS))
            assertTrue("Only the run-owned temporary extent is removed", file.delete())
        }
    }

    @Test
    fun allocationFailurePreservesThePhysicalPrefixWithoutClaimingTheRemainingExtent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("lq001-extent-failure-", ".bin", context.cacheDir)
        val initial = 4096L
        val slice = Npv10RollingExtentAllocator.MAX_SLICE_BYTES
        val injected = IOException("injected second-slice allocation failure")
        val marker = ByteArray(initial.toInt()) { (it * 17).toByte() }
        try {
            RandomAccessFile(file, "rw").use { target ->
                Os.posix_fallocate(target.fd, 0L, initial)
                target.write(marker)
                var confirmed = initial
                var calls = 0
                val failure = assertThrows(IOException::class.java) {
                    Npv10RollingExtentAllocator.allocate(initial, 3L * slice,
                        allocateRange = { offset, length ->
                            if (++calls == 2) throw injected
                            Os.posix_fallocate(target.fd, offset, length)
                        },
                        onSliceConfirmed = { end, _ -> confirmed = end })
                }
                assertSame(injected, failure)
                assertEquals(2, calls)
                assertEquals(initial + slice, confirmed)
                assertEquals(confirmed, Os.fstat(target.fd).st_size)
                target.seek(0L)
                val readback = ByteArray(marker.size)
                target.readFully(readback)
                assertArrayEquals(marker, readback)
            }
        } finally {
            assertTrue("Only the run-owned failed extent is removed", file.delete())
        }
    }
}
