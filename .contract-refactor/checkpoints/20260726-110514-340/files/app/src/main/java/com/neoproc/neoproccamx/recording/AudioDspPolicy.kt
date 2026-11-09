package com.neoproc.neoproccamx.recording

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * s9-f06 / CP-QV6 — pro-audio DSP for the record path: a brick-wall-safe soft-knee limiter, an
 * optional wind/rumble high-pass, and always-on level metering, over the live 48 kHz stereo 16-bit
 * PCM stream (see RecordingManager AUDIO_SAMPLE_RATE / CHANNEL_IN_STEREO / ENCODING_PCM_16BIT).
 *
 * Buyers judge audio hard (G6): the limiter guarantees no sample ever reaches 0 dBFS clip, the
 * high-pass tames handling/wind rumble, and the meter surfaces levels. Pure, deterministic math with
 * no Android deps, so it is fully host-testable.
 *
 * NO-DOWNGRADE: [AudioDspConfig] defaults both processing stages OFF, and with both off
 * [AudioDspProcessor.process] does not touch a single PCM sample (it only measures) — the recorded
 * audio is byte-for-byte today until a settings toggle enables a stage. Metering is read-only.
 */
object AudioDspPolicy {
    const val SAMPLE_RATE_HZ = 48_000
    const val CHANNELS = 2

    /** 16-bit PCM full scale. In/out use the same scale and clamp to [Short.MIN, Short.MAX]. */
    const val FULL_SCALE = 32_768f

    const val DEFAULT_WIND_HPF_HZ = 80f
    const val DEFAULT_HPF_Q = 0.707f
    const val DEFAULT_LIMITER_THRESHOLD_DBFS = -6f
    /** A true-peak ceiling strictly below 0 dBFS: the limiter output can never reach digital clip. */
    const val DEFAULT_LIMITER_CEILING_DBFS = -1f

    fun dbfsToLinear(db: Float): Float = Math.pow(10.0, db / 20.0).toFloat()

    fun linearToDbfs(linear: Float): Float =
        if (linear <= 1e-9f) NEG_INF_DBFS else (20.0 * log10(linear.toDouble())).toFloat()

    const val NEG_INF_DBFS = -120f

    /**
     * Symmetric C1 soft-knee limiter on a normalized sample in roughly [-1, 1].
     *
     * Below [thresholdLinear] it is the identity (transparent). Above, it smoothly compresses so the
     * magnitude asymptotes to — but NEVER reaches — [ceilingLinear] via
     * `out = T + (C - T)·tanh((|x| - T)/(C - T))`, which is continuous and unit-slope at the knee (no
     * kink) and strictly bounded by C. With C < 1 the output can never hit digital full scale.
     */
    fun softKneeLimit(x: Float, thresholdLinear: Float, ceilingLinear: Float): Float {
        val a = abs(x)
        if (a <= thresholdLinear) return x
        val range = (ceilingLinear - thresholdLinear).coerceAtLeast(1e-6f)
        val compressed = thresholdLinear + range * tanh((a - thresholdLinear) / range)
        return sign(x) * compressed
    }

    /** Transposed-direct-form-II biquad coefficients (a0 normalized to 1). */
    data class BiquadCoeffs(val b0: Float, val b1: Float, val b2: Float, val a1: Float, val a2: Float)

    /** RBJ audio-EQ-cookbook high-pass at [cutoffHz] (wind/rumble reduction). */
    fun highPassCoeffs(cutoffHz: Float, sampleRate: Int, q: Float): BiquadCoeffs {
        val w0 = 2.0 * Math.PI * cutoffHz.coerceIn(1f, sampleRate / 2f - 1f) / sampleRate
        val cosw0 = cos(w0)
        val sinw0 = sin(w0)
        val alpha = sinw0 / (2.0 * q.coerceAtLeast(1e-3f))
        val a0 = 1.0 + alpha
        val b0 = (1.0 + cosw0) / 2.0
        val b1 = -(1.0 + cosw0)
        val b2 = (1.0 + cosw0) / 2.0
        val a1 = -2.0 * cosw0
        val a2 = 1.0 - alpha
        return BiquadCoeffs(
            b0 = (b0 / a0).toFloat(),
            b1 = (b1 / a0).toFloat(),
            b2 = (b2 / a0).toFloat(),
            a1 = (a1 / a0).toFloat(),
            a2 = (a2 / a0).toFloat(),
        )
    }

    /** Peak level (dBFS) of interleaved 16-bit PCM. */
    fun peakDbfs(pcm: ShortArray, sampleCount: Int): Float {
        var peak = 0
        val n = sampleCount.coerceAtMost(pcm.size)
        for (i in 0 until n) {
            val a = abs(pcm[i].toInt())
            if (a > peak) peak = a
        }
        return linearToDbfs(peak / FULL_SCALE)
    }

    /** RMS level (dBFS) of interleaved 16-bit PCM. */
    fun rmsDbfs(pcm: ShortArray, sampleCount: Int): Float {
        val n = sampleCount.coerceAtMost(pcm.size)
        if (n <= 0) return NEG_INF_DBFS
        var sumSq = 0.0
        for (i in 0 until n) {
            val v = pcm[i] / FULL_SCALE
            sumSq += (v * v).toDouble()
        }
        return linearToDbfs(sqrt(sumSq / n).toFloat())
    }
}

/** Buyer-facing audio levels for one processed block. */
data class AudioLevel(val peakDbfs: Float, val rmsDbfs: Float)

data class AudioDspConfig(
    /** No-downgrade default: wind high-pass OFF. */
    val windFilterEnabled: Boolean = false,
    /** No-downgrade default: limiter OFF. */
    val limiterEnabled: Boolean = false,
    val windHpfHz: Float = AudioDspPolicy.DEFAULT_WIND_HPF_HZ,
    val hpfQ: Float = AudioDspPolicy.DEFAULT_HPF_Q,
    val limiterThresholdDbfs: Float = AudioDspPolicy.DEFAULT_LIMITER_THRESHOLD_DBFS,
    val limiterCeilingDbfs: Float = AudioDspPolicy.DEFAULT_LIMITER_CEILING_DBFS,
    val channels: Int = AudioDspPolicy.CHANNELS,
    val sampleRate: Int = AudioDspPolicy.SAMPLE_RATE_HZ,
) {
    val active: Boolean get() = windFilterEnabled || limiterEnabled
}

/**
 * Stateful per-session audio processor. Holds one biquad state per channel (so a filter's memory is
 * continuous across capture blocks). Intended for single-threaded use on the audio capture thread.
 */
class AudioDspProcessor(private val config: AudioDspConfig) {
    private val coeffs: AudioDspPolicy.BiquadCoeffs? =
        if (config.windFilterEnabled) {
            AudioDspPolicy.highPassCoeffs(config.windHpfHz, config.sampleRate, config.hpfQ)
        } else {
            null
        }
    private val thresholdLinear = AudioDspPolicy.dbfsToLinear(config.limiterThresholdDbfs)
    private val ceilingLinear = AudioDspPolicy.dbfsToLinear(config.limiterCeilingDbfs)

    // Transposed-direct-form-II state: [z1, z2] per channel.
    private val z1 = FloatArray(config.channels)
    private val z2 = FloatArray(config.channels)

    fun reset() {
        z1.fill(0f)
        z2.fill(0f)
    }

    /**
     * Filter + limit [pcm] in place (interleaved) and return the block's level. When the config is
     * inactive NO sample is modified — only measured — so the audio stays byte-for-byte today.
     *
     * @param sampleCount number of valid interleaved samples in [pcm] (frames × channels).
     */
    fun process(pcm: ShortArray, sampleCount: Int): AudioLevel {
        val n = sampleCount.coerceAtMost(pcm.size)
        if (!config.active) {
            return AudioLevel(AudioDspPolicy.peakDbfs(pcm, n), AudioDspPolicy.rmsDbfs(pcm, n))
        }
        val ch = config.channels.coerceAtLeast(1)
        var peak = 0
        var sumSq = 0.0
        val c = coeffs
        for (i in 0 until n) {
            val channel = i % ch
            var x = pcm[i] / AudioDspPolicy.FULL_SCALE
            if (c != null) x = biquad(x, channel, c)
            if (config.limiterEnabled) {
                x = AudioDspPolicy.softKneeLimit(x, thresholdLinear, ceilingLinear)
            }
            val out = (x * AudioDspPolicy.FULL_SCALE).roundToInt().coerceIn(-32_768, 32_767)
            pcm[i] = out.toShort()
            val a = abs(out)
            if (a > peak) peak = a
            val vn = out / AudioDspPolicy.FULL_SCALE
            sumSq += (vn * vn).toDouble()
        }
        val rms = if (n > 0) sqrt(sumSq / n).toFloat() else 0f
        return AudioLevel(
            AudioDspPolicy.linearToDbfs(peak / AudioDspPolicy.FULL_SCALE),
            AudioDspPolicy.linearToDbfs(rms),
        )
    }

    private fun biquad(x: Float, channel: Int, c: AudioDspPolicy.BiquadCoeffs): Float {
        val y = c.b0 * x + z1[channel]
        z1[channel] = c.b1 * x - c.a1 * y + z2[channel]
        z2[channel] = c.b2 * x - c.a2 * y
        return y
    }
}
