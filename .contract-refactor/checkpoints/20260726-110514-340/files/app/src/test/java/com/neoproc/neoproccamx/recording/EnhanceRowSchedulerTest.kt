package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Locks the NO-DOWNGRADE / byte-identical contract of the offline SDR/NDR develop enhance parallelism:
 * fanning the ~12 heavy full-frame kernel passes across CPU cores via [EnhanceRowScheduler] must produce
 * the EXACT same output bytes as the original single-threaded [EnhanceRowScheduler.SEQUENTIAL] path — the
 * whole feature is a pure scheduling win, never a look change. Also verifies the scheduler's row partition
 * covers every row exactly once and that its reused scratch buffer is byte-equivalent to `copyOf()`.
 */
class EnhanceRowSchedulerTest {

    @Test
    fun `forEachRow visits every row exactly once across the range`() {
        val executor = Executors.newFixedThreadPool(4)
        try {
            val scheduler = EnhanceRowScheduler.parallel(executor, 4)
            val visits = IntArray(200)
            scheduler.forEachRow(0, 200) { row -> synchronized(visits) { visits[row]++ } }
            for (row in 0 until 200) {
                assertEquals("row $row visited exactly once", 1, visits[row])
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `forEachRow honours a non-zero start row and never runs rows outside the range`() {
        val executor = Executors.newFixedThreadPool(3)
        try {
            val scheduler = EnhanceRowScheduler.parallel(executor, 3)
            val visited = java.util.Collections.synchronizedSet(HashSet<Int>())
            scheduler.forEachRow(5, 137) { row -> visited.add(row) }
            assertEquals((5 until 137).toSet(), visited)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `sequential scheduler runs the body in strict ascending row order`() {
        val order = ArrayList<Int>()
        EnhanceRowScheduler.SEQUENTIAL.forEachRow(3, 20) { row -> order.add(row) }
        assertEquals((3 until 20).toList(), order)
    }

    @Test
    fun `snapshot reproduces copyOf contents`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val scheduler = EnhanceRowScheduler.parallel(executor, 4)
            val payload = ByteArray(1024) { (it * 31 + 7).toByte() }
            assertArrayEquals(payload.copyOf(), scheduler.snapshot(payload))
            // Reuse (scratch) must still match after a second, differently-sized-safe snapshot.
            val payload2 = ByteArray(1024) { (it * 13 + 1).toByte() }
            assertArrayEquals(payload2.copyOf(), scheduler.snapshot(payload2))
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `forEachStrided visits every strided offset exactly once and matches the sequential set`() {
        val base = 4
        val step = 6
        val endExclusive = base + step * 5000 // count 5000 ≥ MIN_PARALLEL_ITEMS ⇒ actually fans out
        val expected = (base until endExclusive step step).toSet()

        val executor = Executors.newFixedThreadPool(4)
        try {
            val parallel = java.util.Collections.synchronizedList(ArrayList<Int>())
            EnhanceRowScheduler.parallel(executor, 4)
                .forEachStrided(base, endExclusive, step) { offset -> parallel.add(offset) }
            // Every offset the sequential loop would touch, touched exactly once — no gap, no overlap.
            assertEquals(expected.size, parallel.size)
            assertEquals(expected, parallel.toSet())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `forEachStrided is byte-identical to the sequential loop for a read-pair write-pair body`() {
        // Mirrors the chroma-nudge / SDR-calibration shape: stride over U/V pairs, read+write both samples.
        val size = 4 * 3000 // pair count 3000 ≥ MIN_PARALLEL_ITEMS
        val source = ByteArray(size) { ((it * 37 + 11) and 0xFF).toByte() }
        val step = 4
        val endExclusive = size - 2 // reproduces `while (offset + 2 < size)`

        val transform: (ByteArray, Int) -> Unit = { buf, offset ->
            val u = buf[offset].toInt() and 0xFF
            val v = buf[offset + 2].toInt() and 0xFF
            buf[offset] = ((u * 3 + 1) and 0xFF).toByte()
            buf[offset + 2] = ((v * 5 + 2) and 0xFF).toByte()
        }

        val sequentialBuf = source.copyOf()
        EnhanceRowScheduler.SEQUENTIAL.forEachStrided(0, endExclusive, step) { offset ->
            transform(sequentialBuf, offset)
        }

        val executor = Executors.newFixedThreadPool(4)
        try {
            val parallelBuf = source.copyOf()
            EnhanceRowScheduler.parallel(executor, 4).forEachStrided(0, endExclusive, step) { offset ->
                transform(parallelBuf, offset)
            }
            assertArrayEquals("strided parallel must equal sequential byte-for-byte", sequentialBuf, parallelBuf)
            assertNotEquals(source.toList(), sequentialBuf.toList())
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `forEachStrided below the item threshold stays sequential and identical`() {
        // count = 10 < MIN_PARALLEL_ITEMS ⇒ the parallel scheduler must run the exact sequential loop.
        val order = ArrayList<Int>()
        val executor = Executors.newFixedThreadPool(4)
        try {
            EnhanceRowScheduler.parallel(executor, 4).forEachStrided(2, 2 + 3 * 10, 3) { offset ->
                order.add(offset)
            }
            assertEquals((2 until 2 + 3 * 10 step 3).toList(), order)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `parallel enhance output is byte-identical to sequential for the strict SDR profile`() {
        assertParallelMatchesSequential(Npv10ExportEnhancementProfile.SDR_VIDEO)
    }

    @Test
    fun `parallel enhance with a non-identity SNCE nudge is byte-identical to sequential`() {
        // The default-profile enhance test leaves saturation/warmth at identity, so applySnceChromaNudge
        // early-returns and its parallel strided path is never exercised. Drive it here with a real nudge.
        val width = 80
        val height = 140
        val payload = syntheticP010(width, height, seed = 5)
        val previous = syntheticP010(width, height, seed = 6)
        val profile = Npv10ExportEnhancementProfile.SDR_VIDEO

        val sequential = Npv10ExportEnhancementPolicy.enhanceP010(
            payload = payload.copyOf(),
            width = width,
            height = height,
            previousP010Payload = previous,
            profile = profile,
            saturation = 1.4f,
            warmth = 0.12f
        )

        val executor = Executors.newFixedThreadPool(4)
        try {
            val parallel = Npv10ExportEnhancementPolicy.enhanceP010(
                payload = payload.copyOf(),
                width = width,
                height = height,
                previousP010Payload = previous,
                profile = profile,
                saturation = 1.4f,
                warmth = 0.12f,
                rowScheduler = EnhanceRowScheduler.parallel(executor, 4)
            )
            assertArrayEquals("nudge parallel enhance must equal sequential byte-for-byte", sequential, parallel)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `parallel enhance output is byte-identical to sequential for the default profile`() {
        assertParallelMatchesSequential(
            Npv10ExportEnhancementProfile.DEFAULT.copy(
                chromaSuppression = 0.4,
                shadowChromaDeadband = 24,
                fineTextureDetailGain = 6.0,
                mediumDetailGain = 6.0,
                strongDetailGain = 6.0,
                temporalLumaBlend = 0.4,
                temporalChromaBlend = 0.4,
                ditherAmplitude = 1.0,
                normalSdrCalibrationStrength = 0.6
            )
        )
    }

    /**
     * Runs [Npv10ExportEnhancementPolicy.enhanceP010] with the shared [EnhanceRowScheduler.SEQUENTIAL] path
     * and with a per-clip parallel scheduler, on a frame big enough (≥64 luma AND chroma rows) that both the
     * luma and chroma passes cross the parallel threshold, and asserts the two outputs are byte-for-byte
     * equal. Also runs the parallel path twice to prove the reused scratch buffer stays byte-identical across
     * consecutive frames (as it is used across a clip). The frame carries real structure so the edge/detail/
     * denoise passes actually do work (guarded by a not-a-no-op assertion).
     */
    private fun assertParallelMatchesSequential(profile: Npv10ExportEnhancementProfile) {
        val width = 80
        val height = 140 // chromaHeight = 70 ≥ 64 ⇒ chroma passes also fan out
        val payload = syntheticP010(width, height, seed = 1)
        val previous = syntheticP010(width, height, seed = 2)

        val sequential = Npv10ExportEnhancementPolicy.enhanceP010(
            payload = payload.copyOf(),
            width = width,
            height = height,
            previousP010Payload = previous,
            profile = profile
        )

        val executor = Executors.newFixedThreadPool(4)
        try {
            val scheduler = EnhanceRowScheduler.parallel(executor, 4)
            val parallelFirst = Npv10ExportEnhancementPolicy.enhanceP010(
                payload = payload.copyOf(),
                width = width,
                height = height,
                previousP010Payload = previous,
                profile = profile,
                rowScheduler = scheduler
            )
            val parallelSecond = Npv10ExportEnhancementPolicy.enhanceP010(
                payload = payload.copyOf(),
                width = width,
                height = height,
                previousP010Payload = previous,
                profile = profile,
                rowScheduler = scheduler
            )
            assertArrayEquals("parallel enhance must equal sequential byte-for-byte", sequential, parallelFirst)
            assertArrayEquals("scratch reuse across frames must stay byte-identical", sequential, parallelSecond)
            // Sanity: the passes actually transformed the frame (otherwise the equality is vacuous).
            assertNotEquals(payload.toList(), sequential.toList())
        } finally {
            executor.shutdownNow()
        }
    }

    /** A deterministic P010 frame (10-bit-in-16 luma plane + interleaved 4:2:0 chroma) with real structure. */
    private fun syntheticP010(width: Int, height: Int, seed: Int): ByteArray {
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val payload = ByteArray(width * height * 2 + chromaWidth * chromaHeight * 4)
        var offset = 0
        val counter = AtomicInteger(seed * 101)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val ripple = ((x * 13 + y * 29 + counter.getAndIncrement() * 7) % 1024 + 1024) % 1024
                writeP010(payload, offset, ripple)
                offset += 2
            }
        }
        for (cy in 0 until chromaHeight) {
            for (cx in 0 until chromaWidth) {
                val u = (450 + ((cx * 17 + cy * 11 + seed) % 160))
                val v = (600 - ((cx * 7 + cy * 23 + seed) % 160))
                writeP010(payload, offset, u)
                writeP010(payload, offset + 2, v)
                offset += 4
            }
        }
        return payload
    }

    private fun writeP010(payload: ByteArray, offset: Int, sample: Int) {
        val value = sample.coerceIn(0, 1023) shl 6
        payload[offset] = (value and 0xFF).toByte()
        payload[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }
}
