package com.neoproc.neoproccamx.capture

/**
 * ELITE Centerpiece foundation — the RAW **16-bit-code → scene-linear [0,1]** input contract.
 *
 * The offline multi-frame stills stack (the real iPhone mechanism) fuses N aligned RAW frames. Its 3σ ghost
 * veto and heteroscedastic noise variance (`MfsrFusionParams.ghostSigmaThreshold`,
 * `HdrImagingMath.sensorNoiseCoefficientsForIso`) both assume every frame is expressed in ONE canonical
 * scene-linear signal domain — but today nothing produces it (MFSR is fed a 1×1 zero dummy). This is the
 * first brick everything downstream stands on: black-level subtract, white-level normalize, CFA channel
 * classification, and per-channel white-balance gain, in exactly the domain the veto/variance expect.
 *
 * SAFETY: this is pure host math. It consumes nothing device-side, touches no shader/dispatch/flag, and —
 * critically — does NOT flip `mfsrBurstRingReady`. Flipping the ring on before a real full-res RAW
 * `sampler2DArray` exists drives the 3σ veto to reject every non-reference sample against the zero dummy →
 * MFSR emits black. That activation is the honest weeks-to-months remainder; this brick can never black out.
 */
object RawLinearFrameMath {

    /** Bayer colour-filter-array phase, named by the top-left 2×2 in row-major order. */
    enum class CfaPattern { RGGB, BGGR, GRBG, GBRG }

    /** The four Bayer site kinds (two greens distinguished by their row for green-imbalance correction). */
    enum class BayerChannel { R, GR, GB, B }

    /**
     * Normalize a raw sensor code to scene-linear [0,1]:
     * `((code - black) / (white - black))` clamped. Guards `white <= black` (returns 0, never NaN/∞).
     */
    fun normalizeRawCode(code: Int, blackLevel: Int, whiteLevel: Int): Float {
        if (whiteLevel <= blackLevel) return 0f
        val n = (code - blackLevel).toFloat() / (whiteLevel - blackLevel).toFloat()
        return n.coerceIn(0f, 1f)
    }

    /** Which Bayer channel the pixel at (x,y) carries, from the CFA's repeating 2×2 phase. */
    fun channelAt(x: Int, y: Int, pattern: CfaPattern): BayerChannel {
        val topRow = (y and 1) == 0
        val leftCol = (x and 1) == 0
        return when (pattern) {
            CfaPattern.RGGB -> if (topRow) (if (leftCol) BayerChannel.R else BayerChannel.GR)
                               else (if (leftCol) BayerChannel.GB else BayerChannel.B)
            CfaPattern.BGGR -> if (topRow) (if (leftCol) BayerChannel.B else BayerChannel.GB)
                               else (if (leftCol) BayerChannel.GR else BayerChannel.R)
            CfaPattern.GRBG -> if (topRow) (if (leftCol) BayerChannel.GR else BayerChannel.R)
                               else (if (leftCol) BayerChannel.B else BayerChannel.GB)
            CfaPattern.GBRG -> if (topRow) (if (leftCol) BayerChannel.GB else BayerChannel.B)
                               else (if (leftCol) BayerChannel.R else BayerChannel.GR)
        }
    }

    /** The primary colour a Bayer site contributes to (both greens → green). */
    fun colorOf(channel: BayerChannel): Char = when (channel) {
        BayerChannel.R -> 'R'
        BayerChannel.GR, BayerChannel.GB -> 'G'
        BayerChannel.B -> 'B'
    }

    /**
     * Apply per-channel white-balance gain to a normalized [0,1] linear value, clamped back to [0,1].
     * Greens share [gGain]; reds use [rGain], blues [bGain] — the as-shot neutral from the DNG WB tags.
     */
    fun applyWhiteBalanceGain(
        linear: Float,
        channel: BayerChannel,
        rGain: Float,
        gGain: Float,
        bGain: Float
    ): Float {
        val gain = when (channel) {
            BayerChannel.R -> rGain
            BayerChannel.GR, BayerChannel.GB -> gGain
            BayerChannel.B -> bGain
        }
        return (linear * gain).coerceIn(0f, 1f)
    }

    /**
     * Full contract for one raw site: normalize its code, classify its channel, apply WB gain — the exact
     * scene-linear value the multi-frame veto/variance must compare across frames.
     */
    fun toSceneLinear(
        code: Int,
        x: Int,
        y: Int,
        pattern: CfaPattern,
        blackLevel: Int,
        whiteLevel: Int,
        rGain: Float,
        gGain: Float,
        bGain: Float
    ): Float {
        val ch = channelAt(x, y, pattern)
        return applyWhiteBalanceGain(normalizeRawCode(code, blackLevel, whiteLevel), ch, rGain, gGain, bGain)
    }
}
