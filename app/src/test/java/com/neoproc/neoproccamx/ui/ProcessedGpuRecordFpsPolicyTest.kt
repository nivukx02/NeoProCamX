package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProcessedGpuRecordFpsPolicyTest {

    // The live 3-arg resolve() reads the process-global RecordFpsCeilingFlag (production default ON). Force
    // the conservative-baseline state before every test so the baseline assertions are deterministic
    // regardless of order, then restore the production default (ON) afterward so nothing leaks across classes.
    @Before
    fun resetFlagBefore() {
        HighEndQualityPolicy.strictHighEndOnly = false
        RecordFpsCeilingFlag.set(false)
    }

    @After
    fun restoreProductionDefault() {
        RecordFpsCeilingFlag.set(true)
        HighEndQualityPolicy.strictHighEndOnly = true
    }

    @Test
    fun `strict mode preserves exact requested fps at high resolution`() {
        HighEndQualityPolicy.strictHighEndOnly = true
        assertEquals(30, ProcessedGpuRecordFpsPolicy.resolve(30, 3840, 2160))
        assertEquals(60, ProcessedGpuRecordFpsPolicy.resolve(60, 4096, 3072))
    }

    @Test
    fun `full-sensor 4096x3072 caps to the 25fps cinema tier when the ceiling is not lifted`() {
        assertEquals(25, ProcessedGpuRecordFpsPolicy.resolve(requestedFps = 30, width = 4096, height = 3072))
        assertEquals(25, ProcessedGpuRecordFpsPolicy.resolve(requestedFps = 60, width = 4096, height = 3072))
    }

    @Test
    fun `high resolution offscreen p010 caps 4K class video to 25fps`() {
        assertEquals(25, ProcessedGpuRecordFpsPolicy.resolve(requestedFps = 30, width = 3840, height = 2160))
        assertEquals(25, ProcessedGpuRecordFpsPolicy.resolve(requestedFps = 60, width = 3840, height = 2160))
        assertEquals(25, ProcessedGpuRecordFpsPolicy.resolve(requestedFps = 30, width = 3264, height = 2448))
    }

    @Test
    fun `2K and below keep the full high-cadence set`() {
        assertEquals(60, ProcessedGpuRecordFpsPolicy.resolve(requestedFps = 60, width = 2048, height = 1080))
        assertEquals(60, ProcessedGpuRecordFpsPolicy.resolve(requestedFps = 60, width = 1920, height = 1080))
        assertEquals(48, ProcessedGpuRecordFpsPolicy.resolve(requestedFps = 48, width = 1920, height = 1080))
    }

    @Test
    fun `never raises above the requested fps`() {
        assertEquals(24, ProcessedGpuRecordFpsPolicy.resolve(requestedFps = 24, width = 1920, height = 1080))
        assertEquals(25, ProcessedGpuRecordFpsPolicy.resolve(requestedFps = 25, width = 1920, height = 1080))
        assertEquals(25, ProcessedGpuRecordFpsPolicy.resolve(requestedFps = 25, width = 3840, height = 2160))
    }

    @Test
    fun `degenerate sizes fall back to the requested fps`() {
        assertEquals(30, ProcessedGpuRecordFpsPolicy.resolve(requestedFps = 30, width = 0, height = 0))
        assertEquals(30, ProcessedGpuRecordFpsPolicy.resolve(requestedFps = 30, width = -1, height = 2160))
    }

    @Test
    fun `clamps sub-one requests to a valid rate`() {
        assertEquals(1, ProcessedGpuRecordFpsPolicy.resolve(requestedFps = 0, width = 1920, height = 1080))
    }

    // --- Explicit-ceiling (pure) overload: documents both the baseline and the device-measured lift ---

    @Test
    fun `explicit baseline ceiling keeps full-sensor 4096x3072 at 25`() {
        assertEquals(
            25,
            ProcessedGpuRecordFpsPolicy.resolve(
                requestedFps = 30, width = 4096, height = 3072,
                pixelsPerSecond = ProcessedGpuRecordFpsPolicy.GPU_ENHANCE_PIXELS_PER_SECOND
            )
        )
    }

    @Test
    fun `explicit measured ceiling does not override the 25fps high-res offscreen cap`() {
        assertEquals(
            25,
            ProcessedGpuRecordFpsPolicy.resolve(
                requestedFps = 30, width = 4096, height = 3072,
                pixelsPerSecond = ProcessedGpuRecordFpsPolicy.GPU_ENHANCE_PIXELS_PER_SECOND_MEASURED
            )
        )
        assertEquals(
            25,
            ProcessedGpuRecordFpsPolicy.resolve(
                requestedFps = 60, width = 4096, height = 3072,
                pixelsPerSecond = ProcessedGpuRecordFpsPolicy.GPU_ENHANCE_PIXELS_PER_SECOND_MEASURED
            )
        )
    }

    @Test
    fun `measured ceiling leaves 2K tiers unchanged and high-res capped`() {
        val measured = ProcessedGpuRecordFpsPolicy.GPU_ENHANCE_PIXELS_PER_SECOND_MEASURED
        assertEquals(25, ProcessedGpuRecordFpsPolicy.resolve(30, 3840, 2160, measured))
        assertEquals(25, ProcessedGpuRecordFpsPolicy.resolve(30, 4096, 2160, measured))
        assertEquals(60, ProcessedGpuRecordFpsPolicy.resolve(60, 1920, 1080, measured))
    }

    @Test
    fun `raising the ceiling never lowers the resolved fps at any resolution (monotonic no-downgrade)`() {
        val baseline = ProcessedGpuRecordFpsPolicy.GPU_ENHANCE_PIXELS_PER_SECOND
        val measured = ProcessedGpuRecordFpsPolicy.GPU_ENHANCE_PIXELS_PER_SECOND_MEASURED
        val resolutions = listOf(
            1920 to 1080, 2048 to 1080, 3840 to 2160, 4096 to 2160,
            4096 to 2304, 4096 to 2560, 4096 to 3072
        )
        for ((w, h) in resolutions) {
            for (req in intArrayOf(24, 25, 30, 48, 60)) {
                val low = ProcessedGpuRecordFpsPolicy.resolve(req, w, h, baseline)
                val high = ProcessedGpuRecordFpsPolicy.resolve(req, w, h, measured)
                assertTrue(
                    "lift lowered fps at ${w}x$h req=$req: $low -> $high",
                    high >= low
                )
            }
        }
    }

    // --- Live flag wiring: the reversible RecordFpsCeilingFlag selects the active ceiling ---

    @Test
    fun `flag OFF selects the baseline ceiling for the live overload`() {
        RecordFpsCeilingFlag.set(false)
        assertEquals(
            ProcessedGpuRecordFpsPolicy.GPU_ENHANCE_PIXELS_PER_SECOND,
            ProcessedGpuRecordFpsPolicy.activeCeilingPixelsPerSecond()
        )
        assertEquals(25, ProcessedGpuRecordFpsPolicy.resolve(requestedFps = 30, width = 4096, height = 3072))
    }

    @Test
    fun `flag ON selects the measured ceiling without overriding high-res cap`() {
        RecordFpsCeilingFlag.set(true)
        assertEquals(
            ProcessedGpuRecordFpsPolicy.GPU_ENHANCE_PIXELS_PER_SECOND_MEASURED,
            ProcessedGpuRecordFpsPolicy.activeCeilingPixelsPerSecond()
        )
        assertEquals(25, ProcessedGpuRecordFpsPolicy.resolve(requestedFps = 30, width = 4096, height = 3072))
        assertEquals(25, ProcessedGpuRecordFpsPolicy.resolve(requestedFps = 30, width = 3840, height = 2160))
    }
}
