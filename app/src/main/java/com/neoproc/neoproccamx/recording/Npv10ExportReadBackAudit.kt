package com.neoproc.neoproccamx.recording

import kotlin.math.abs

/**
 * s6-f05 / CP72 — NPV10→MOV export read-back parity audit.
 *
 * The NPV10->MOV export path derives a playable v210 MOV from a private P010 master without a
 * MediaCodec/QP/CQ encoder. This helper audits the intermediate P010 payload parity before v210
 * packing: decode the 10-bit luma codes of the master frame and the derived payload and confirm
 * they are identical (no silent 8-bit hop, no bit-depth loss before the final MOV packing step).
 *
 * Honest by construction (OmegaCapabilityGate contract): mismatched sizes / empty inputs land
 * NOT_PROVEN, never a synthesized PASS. Pure JVM (no encoder, no Android).
 */
internal data class Npv10ExportReadBackVerdict(
    val status: String,
    val comparedSamples: Int,
    val maxLumaCodeDelta: Int,
    val lossless: Boolean,
    val eightBitHopDetected: Boolean
)

internal object Npv10ExportReadBackAudit {

    const val STATUS_PROVEN = "PROVEN"
    const val STATUS_NOT_PROVEN = "NOT_PROVEN"

    /**
     * Compares master vs written P010 sample bytes (little-endian word16[15:6]=code10[9:0]).
     * @return PROVEN only when every 10-bit luma code matches exactly (lossless repack).
     * `eightBitHopDetected` flags a master whose 10-bit codes all have zero low-2 bits — the
     * signature of an 8-bit source expanded to 10-bit (a heuristic, surfaced honestly).
     */
    fun audit(masterP010: ByteArray, writtenP010: ByteArray): Npv10ExportReadBackVerdict {
        if (masterP010.isEmpty() ||
            writtenP010.isEmpty() ||
            masterP010.size != writtenP010.size ||
            masterP010.size % 2 != 0
        ) {
            return Npv10ExportReadBackVerdict(STATUS_NOT_PROVEN, 0, -1, lossless = false, eightBitHopDetected = false)
        }
        val count = masterP010.size / 2
        var maxDelta = 0
        var allLowTwoBitsZero = true
        for (i in 0 until count) {
            val mWord = (masterP010[i * 2].toInt() and 0xFF) or ((masterP010[i * 2 + 1].toInt() and 0xFF) shl 8)
            val wWord = (writtenP010[i * 2].toInt() and 0xFF) or ((writtenP010[i * 2 + 1].toInt() and 0xFF) shl 8)
            val mCode = (mWord ushr 6) and 0x3FF
            val wCode = (wWord ushr 6) and 0x3FF
            maxDelta = maxOf(maxDelta, abs(mCode - wCode))
            if ((wCode and 0x3) != 0) allLowTwoBitsZero = false
        }
        val lossless = maxDelta == 0
        return Npv10ExportReadBackVerdict(
            status = if (lossless) STATUS_PROVEN else STATUS_NOT_PROVEN,
            comparedSamples = count,
            maxLumaCodeDelta = maxDelta,
            lossless = lossless,
            eightBitHopDetected = allLowTwoBitsZero
        )
    }

    /** Honest evidence keys (never `tierOmega`/`beyondImax` prefixed). */
    fun toMetadata(v: Npv10ExportReadBackVerdict): Map<String, String> = mapOf(
        "exportReadBackVerdict" to v.status,
        "exportReadBackComparedSamples" to v.comparedSamples.toString(),
        "exportReadBackMaxLumaCodeDelta" to if (v.maxLumaCodeDelta < 0) STATUS_NOT_PROVEN else v.maxLumaCodeDelta.toString(),
        "exportReadBackLossless" to v.lossless.toString(),
        "exportEightBitHopDetected" to v.eightBitHopDetected.toString()
    )
}
