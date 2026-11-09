package com.neoproc.neoproccamx.rendering

import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/** Host-golden definition of the reversible RAW-derived RGB10_A2 fallback transport. */
internal object NeoSceneLog10 {
    const val VERSION: String = "NEO-SCENE-LOG10-v1"
    const val SLOPE: Float = 32f
    const val MAX_LINEAR: Float = 256f
    const val CODE_MAX: Int = 1023
    val denominator: Float = log2(1f + SLOPE * MAX_LINEAR)

    fun encode(linearAcesCg: Float): Float =
        (log2(1f + SLOPE * linearAcesCg.coerceIn(0f, MAX_LINEAR)) / denominator)
            .coerceIn(0f, 1f)

    fun decode(code: Float): Float =
        ((2f.pow(code.coerceIn(0f, 1f) * denominator) - 1f) / SLOPE)
            .coerceIn(0f, MAX_LINEAR)

    fun quantize(linearAcesCg: Float): Int =
        (encode(linearAcesCg) * CODE_MAX).roundToInt().coerceIn(0, CODE_MAX)

    fun decodeCode(code: Int): Float = decode(code.coerceIn(0, CODE_MAX).toFloat() / CODE_MAX)
}
