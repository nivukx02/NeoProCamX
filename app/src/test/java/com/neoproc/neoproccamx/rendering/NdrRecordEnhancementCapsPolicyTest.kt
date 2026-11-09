package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NdrRecordEnhancementCapsPolicyTest {

    @Test
    fun `SDR caps remain fixed regardless of evidence`() {
        val low = NdrRecordEnhancementCapsPolicy.resolve(
            GpuSignalFamily.SDR, iso = 100f, noiseMetric = 0f
        )
        val high = NdrRecordEnhancementCapsPolicy.resolve(
            GpuSignalFamily.SDR, iso = 6400f, noiseMetric = 1f, noiseMetricConfidence = 1f
        )
        assertEquals(0.45f, low.denoiseCap, 1e-6f)
        assertEquals(0.45f, low.sharpenCap, 1e-6f)
        assertEquals(low, high)
    }

    @Test
    fun `clean low ISO HDR keeps texture-preserving caps`() {
        val caps = NdrRecordEnhancementCapsPolicy.resolve(
            GpuSignalFamily.HDR,
            iso = 140f,
            noiseMetric = 0.4078f,
            noiseMetricConfidence = 0f,
            sensorShotNoiseCoefficient = 0.0044f,
            sensorReadNoiseCoefficient = 0.0009f,
            sensorNoiseModelConfidence = 1f
        )
        assertEquals(0.35f, caps.denoiseCap, 1e-6f)
        assertEquals(0.45f, caps.sharpenCap, 1e-6f)
    }

    @Test
    fun `scene contrast without noise provenance cannot soften NDR`() {
        val caps = NdrRecordEnhancementCapsPolicy.resolve(
            GpuSignalFamily.HDR,
            iso = 100f,
            noiseMetric = 1f,
            noiseMetricConfidence = 0f
        )
        assertEquals(0.35f, caps.denoiseCap, 1e-6f)
        assertEquals(0.45f, caps.sharpenCap, 1e-6f)
    }

    @Test
    fun `high sensor ISO still raises denoise and lowers sharpening`() {
        val caps = NdrRecordEnhancementCapsPolicy.resolve(
            GpuSignalFamily.HDR, iso = 6400f, noiseMetric = 0f
        )
        assertEquals(0.72f, caps.denoiseCap, 1e-6f)
        assertEquals(0.30f, caps.sharpenCap, 1e-6f)
    }

    @Test
    fun `only a per-frame sensor noise model may add model severity`() {
        val untrusted = NdrRecordEnhancementCapsPolicy.noiseSeverity(
            iso = 100f,
            noiseMetric = 0f,
            sensorShotNoiseCoefficient = 0.08f,
            sensorReadNoiseCoefficient = 0.02f,
            sensorNoiseModelConfidence = 0f
        )
        val trusted = NdrRecordEnhancementCapsPolicy.noiseSeverity(
            iso = 100f,
            noiseMetric = 0f,
            sensorShotNoiseCoefficient = 0.08f,
            sensorReadNoiseCoefficient = 0.02f,
            sensorNoiseModelConfidence = 1f
        )
        assertEquals(0f, untrusted, 1e-6f)
        assertEquals(1f, trusted, 1e-6f)
    }

    @Test
    fun `trusted offline noise measurement remains adaptive`() {
        val clean = NdrRecordEnhancementCapsPolicy.resolve(
            GpuSignalFamily.HDR,
            iso = 0f,
            noiseMetric = 0f,
            noiseMetricConfidence = 1f
        )
        val noisy = NdrRecordEnhancementCapsPolicy.resolve(
            GpuSignalFamily.HDR,
            iso = 0f,
            noiseMetric = 0.60f,
            noiseMetricConfidence = 1f
        )
        assertEquals(0.35f, clean.denoiseCap, 1e-6f)
        assertEquals(0.45f, clean.sharpenCap, 1e-6f)
        assertEquals(0.72f, noisy.denoiseCap, 1e-6f)
        assertEquals(0.30f, noisy.sharpenCap, 1e-6f)
    }

    @Test
    fun `ISO remains authoritative when other evidence is absent`() {
        assertEquals(
            0f,
            NdrRecordEnhancementCapsPolicy.noiseSeverity(iso = 800f, noiseMetric = 1f),
            1e-6f
        )
        assertEquals(
            0.5f,
            NdrRecordEnhancementCapsPolicy.noiseSeverity(iso = 3600f, noiseMetric = 0f),
            1e-6f
        )
        assertEquals(
            1f,
            NdrRecordEnhancementCapsPolicy.noiseSeverity(iso = 6400f, noiseMetric = 0f),
            1e-6f
        )
    }

    @Test
    fun `invalid evidence fails clean rather than producing NaN caps`() {
        val caps = NdrRecordEnhancementCapsPolicy.resolve(
            GpuSignalFamily.HDR,
            iso = Float.NaN,
            noiseMetric = Float.POSITIVE_INFINITY,
            noiseMetricConfidence = Float.NaN,
            sensorShotNoiseCoefficient = Float.NaN,
            sensorReadNoiseCoefficient = -1f,
            sensorNoiseModelConfidence = 1f
        )
        assertTrue(caps.denoiseCap.isFinite())
        assertTrue(caps.sharpenCap.isFinite())
        assertEquals(0.35f, caps.denoiseCap, 1e-6f)
        assertEquals(0.45f, caps.sharpenCap, 1e-6f)
    }
}
