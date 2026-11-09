package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Locks the ISO/noise-adaptive record caps ([NdrRecordEnhancementCapsPolicy]): SDR uses the fixed
 * cinema-balanced cap (0.45/0.45), clean HDR keeps the previous gentle caps, noisy HDR raises denoise +
 * lowers sharpen, and the scene-contrast-prone metric term is bounded to <=0.5 severity (M4 stability).
 */
class NdrRecordEnhancementCapsPolicyTest {

    @Test
    fun `SDR caps are the fixed cinema-balanced value regardless of ISO`() {
        val low = NdrRecordEnhancementCapsPolicy.resolve(GpuSignalFamily.SDR, iso = 100f, noiseMetric = 0f)
        val high = NdrRecordEnhancementCapsPolicy.resolve(GpuSignalFamily.SDR, iso = 6400f, noiseMetric = 1f)
        // Cinema-balanced fixed cap (lowered from 0.50/0.60 to stop over-sharpen crunch / waxy denoise).
        assertEquals(0.45f, low.denoiseCap, 1e-6f)
        assertEquals(0.45f, low.sharpenCap, 1e-6f)
        assertEquals(low, high)
    }

    @Test
    fun `clean HDR keeps the previous gentle caps (no downgrade)`() {
        val caps = NdrRecordEnhancementCapsPolicy.resolve(GpuSignalFamily.HDR, iso = 100f, noiseMetric = 0f)
        assertEquals(0.35f, caps.denoiseCap, 1e-6f)
        assertEquals(0.45f, caps.sharpenCap, 1e-6f)
    }

    @Test
    fun `noisy HDR raises denoise ceiling and lowers sharpen ceiling`() {
        val caps = NdrRecordEnhancementCapsPolicy.resolve(GpuSignalFamily.HDR, iso = 6400f, noiseMetric = 0f)
        assertTrue("denoise should rise above clean 0.35", caps.denoiseCap > 0.35f)
        assertTrue("sharpen should fall below clean 0.45", caps.sharpenCap < 0.45f)
        assertEquals(0.72f, caps.denoiseCap, 1e-6f)
        assertEquals(0.30f, caps.sharpenCap, 1e-6f)
    }

    @Test
    fun `noise metric drives severity at low ISO but is bounded to 0_5 (M4)`() {
        // M4: the scene-contrast-prone global metric can no longer push severity past 0.5, so even a maxed
        // metric at low ISO lands at mid-severity (denoise/sharpen half-way to the noisy endpoints) instead
        // of the full 0.72/0.30 — this stops detailed high-contrast scenes going waxy + soft.
        val caps = NdrRecordEnhancementCapsPolicy.resolve(GpuSignalFamily.HDR, iso = 100f, noiseMetric = 1f)
        assertEquals(0.535f, caps.denoiseCap, 1e-4f)
        assertEquals(0.375f, caps.sharpenCap, 1e-4f)
    }

    @Test
    fun `severity is the stronger of ISO ramp and noise metric`() {
        assertEquals(0f, NdrRecordEnhancementCapsPolicy.noiseSeverity(iso = 800f, noiseMetric = 0f), 1e-6f)
        assertEquals(1f, NdrRecordEnhancementCapsPolicy.noiseSeverity(iso = 6400f, noiseMetric = 0f), 1e-6f)
        // Midpoint ISO 3600 -> (3600-800)/(6400-800)=0.5; metric 0.2 -> 0.2/0.6=0.33; ISO term wins -> 0.5.
        assertEquals(0.5f, NdrRecordEnhancementCapsPolicy.noiseSeverity(iso = 3600f, noiseMetric = 0.2f), 1e-3f)
    }

    @Test
    fun `offline metric severity is bounded so scene contrast cannot force max denoise (M4)`() {
        // Offline convert passes iso=0, so the (contrast-prone) global luma metric is the only driver. It
        // must saturate at severity 0.5 no matter how "noisy" a high-contrast frame measures, so the offline
        // NDR denoise can never exceed the mid cap and detailed scenes stay crisp/stable.
        val contrasty = NdrRecordEnhancementCapsPolicy.resolve(GpuSignalFamily.HDR, iso = 0f, noiseMetric = 1f)
        assertEquals(0.535f, contrasty.denoiseCap, 1e-4f)
        assertEquals(0.375f, contrasty.sharpenCap, 1e-4f)
        assertEquals(0.5f, NdrRecordEnhancementCapsPolicy.noiseSeverity(iso = 0f, noiseMetric = 5f), 1e-6f)
    }
}
