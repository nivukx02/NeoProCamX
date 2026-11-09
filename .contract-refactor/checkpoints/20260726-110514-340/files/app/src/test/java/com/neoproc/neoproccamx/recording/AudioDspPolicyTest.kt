package com.neoproc.neoproccamx.recording

import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDspPolicyTest {

    @Test
    fun disabledProcessorLeavesPcmByteForByteIdentical() {
        val original = ShortArray(2048) { ((it * 37 % 65_536) - 32_768).toShort() }
        val pcm = original.copyOf()
        val level = AudioDspProcessor(AudioDspConfig()).process(pcm, pcm.size)

        assertArrayEquals("no-downgrade: disabled DSP must not modify a single sample", original, pcm)
        // Metering is still produced (read-only).
        assertEquals(AudioDspPolicy.peakDbfs(original, original.size), level.peakDbfs, 1e-4f)
        assertEquals(AudioDspPolicy.rmsDbfs(original, original.size), level.rmsDbfs, 1e-4f)
    }

    @Test
    fun limiterGuaranteesNoSampleReachesDigitalClip() {
        // Sustained full-scale square wave: without a limiter this sits at ±32767 (0 dBFS clip).
        val pcm = ShortArray(4096) { if (it % 2 == 0) 32_767 else -32_768 }
        val config = AudioDspConfig(limiterEnabled = true)
        val level = AudioDspProcessor(config).process(pcm, pcm.size)

        val ceilingSamples = AudioDspPolicy.dbfsToLinear(config.limiterCeilingDbfs) * AudioDspPolicy.FULL_SCALE
        pcm.forEach { s ->
            assertTrue("sample ${s} exceeded the -1 dBFS ceiling", abs(s.toInt()) <= ceilingSamples.roundToInt())
            assertTrue("no sample may sit at digital full scale", abs(s.toInt()) < 32_767)
        }
        // The reported peak must honour the ceiling too.
        assertTrue("peak ${level.peakDbfs} dBFS must stay under 0", level.peakDbfs < 0f)
    }

    @Test
    fun limiterIsTransparentBelowThreshold() {
        val thr = AudioDspPolicy.dbfsToLinear(-6f)
        val ceil = AudioDspPolicy.dbfsToLinear(-1f)
        // Values comfortably below threshold pass through untouched.
        listOf(0f, 0.1f, -0.2f, 0.3f, -0.4f).forEach { x ->
            assertEquals(x, AudioDspPolicy.softKneeLimit(x, thr, ceil), 1e-6f)
        }
    }

    @Test
    fun softKneeLimiterIsMonotoneAndStrictlyBoundedByCeiling() {
        val thr = AudioDspPolicy.dbfsToLinear(-6f)
        val ceil = AudioDspPolicy.dbfsToLinear(-1f)
        var prev = -1f
        var x = 0f
        while (x <= 4f) {
            val y = AudioDspPolicy.softKneeLimit(x, thr, ceil)
            assertTrue("limiter must stay strictly below the ceiling ($y >= $ceil)", y < ceil)
            assertTrue("limiter must be monotone non-decreasing", y >= prev - 1e-6f)
            prev = y
            x += 0.02f
        }
    }

    @Test
    fun windHighPassRejectsDcRumble() {
        val config = AudioDspConfig(windFilterEnabled = true, channels = 1)
        val processor = AudioDspProcessor(config)
        val dc = ShortArray(6000) { 8_000 }
        processor.process(dc, dc.size)
        // A high-pass drives a constant (DC/low rumble) toward zero after its short transient.
        val tail = dc.copyOfRange(dc.size - 200, dc.size)
        tail.forEach { s ->
            assertTrue("DC must be strongly attenuated, got $s", abs(s.toInt()) < 8_000 / 20)
        }
    }

    @Test
    fun windHighPassPassesHighFrequencyContent() {
        val config = AudioDspConfig(windFilterEnabled = true, channels = 1)
        val processor = AudioDspProcessor(config)
        // Near-Nyquist alternating tone at ~-1.4 dBFS; an 80 Hz high-pass passes it near unity.
        val amp = 28_000
        val hf = ShortArray(4000) { if (it % 2 == 0) amp.toShort() else (-amp).toShort() }
        processor.process(hf, hf.size)
        val peak = hf.drop(200).maxOf { abs(it.toInt()) }
        assertTrue("high-frequency content must pass (peak=$peak)", peak > (amp * 0.9).toInt())
    }

    @Test
    fun levelMeterDbfsMathIsCorrect() {
        assertEquals(1f, AudioDspPolicy.dbfsToLinear(0f), 1e-6f)
        assertEquals(0.5f, AudioDspPolicy.dbfsToLinear(-6.0206f), 1e-4f)

        val fullScale = ShortArray(64) { if (it % 2 == 0) 32_767 else -32_767 }
        assertEquals("full scale ~ 0 dBFS", 0f, AudioDspPolicy.peakDbfs(fullScale, fullScale.size), 0.05f)

        val halfScale = ShortArray(64) { if (it % 2 == 0) 16_384 else -16_384 }
        assertEquals("half scale ~ -6 dBFS", -6.02f, AudioDspPolicy.peakDbfs(halfScale, halfScale.size), 0.05f)

        assertEquals(AudioDspPolicy.NEG_INF_DBFS, AudioDspPolicy.peakDbfs(ShortArray(16), 16), 0f)
    }

    @Test
    fun stereoBiquadKeepsPerChannelStateSeparate() {
        // Interleaved stereo: L is DC, R is silent. The filter must not leak L's rumble into R.
        val config = AudioDspConfig(windFilterEnabled = true, channels = 2)
        val pcm = ShortArray(6000) { if (it % 2 == 0) 9_000 else 0 }
        AudioDspProcessor(config).process(pcm, pcm.size)
        // Right channel (odd indices) started silent and must stay silent.
        for (i in 1 until pcm.size step 2) {
            assertEquals("right channel must not receive left-channel filter state", 0, pcm[i].toInt())
        }
    }
}
