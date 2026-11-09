package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s6-f05 / CP72 read-back parity audit unit tests. Pure JVM (no encoder). Proves the lossless-P010
 * repack verdict, honest NOT_PROVEN on mismatch, and the 8-bit-hop heuristic.
 */
class Npv10ExportReadBackAuditTest {

    /** Build P010 sample bytes (little-endian word16[15:6]=code10[9:0]) from 10-bit codes. */
    private fun p010(vararg codes: Int): ByteArray {
        val out = ByteArray(codes.size * 2)
        codes.forEachIndexed { i, code ->
            val word = (code.coerceIn(0, 1023) shl 6)
            out[i * 2] = (word and 0xFF).toByte()
            out[i * 2 + 1] = ((word ushr 8) and 0xFF).toByte()
        }
        return out
    }

    @Test
    fun identicalP010SamplesAuditProven() {
        val master = p010(300, 511, 1023, 17)
        val verdict = Npv10ExportReadBackAudit.audit(master, master.copyOf())
        assertEquals(Npv10ExportReadBackAudit.STATUS_PROVEN, verdict.status)
        assertTrue(verdict.lossless)
        assertEquals(0, verdict.maxLumaCodeDelta)
        assertEquals(4, verdict.comparedSamples)
        assertFalse(verdict.eightBitHopDetected)
    }

    @Test
    fun sizeMismatchAuditNotProven() {
        val verdict = Npv10ExportReadBackAudit.audit(p010(300, 400), p010(300))
        assertEquals(Npv10ExportReadBackAudit.STATUS_NOT_PROVEN, verdict.status)
        assertEquals(-1, verdict.maxLumaCodeDelta)
        assertFalse(verdict.lossless)
    }

    @Test
    fun nonZeroLumaDeltaAuditNotProven() {
        val verdict = Npv10ExportReadBackAudit.audit(p010(300, 400), p010(300, 405))
        assertEquals(Npv10ExportReadBackAudit.STATUS_NOT_PROVEN, verdict.status)
        assertEquals(5, verdict.maxLumaCodeDelta)
        assertFalse(verdict.lossless)
    }

    @Test
    fun eightBitTruncatedMasterFlagsHop() {
        // All 10-bit codes with zero low-2 bits == an 8-bit source expanded to 10-bit.
        val truncated = p010(300 and 0x3FC, 512, 1020, 64)
        val verdict = Npv10ExportReadBackAudit.audit(truncated, truncated.copyOf())
        assertTrue(verdict.eightBitHopDetected)
    }

    @Test
    fun fullTenBitContentDoesNotFlagHop() {
        val full = p010(301, 511, 1023, 17) // low-2 bits vary -> true 10-bit precision
        val verdict = Npv10ExportReadBackAudit.audit(full, full.copyOf())
        assertFalse(verdict.eightBitHopDetected)
    }

    @Test
    fun toMetadataEmitsHonestKeysWithNoGateFlipPrefix() {
        val meta = Npv10ExportReadBackAudit.toMetadata(
            Npv10ExportReadBackAudit.audit(p010(1, 2), p010(3, 4))
        )
        assertEquals(Npv10ExportReadBackAudit.STATUS_NOT_PROVEN, meta["exportReadBackVerdict"])
        meta.keys.forEach { key ->
            assertFalse(key, key.startsWith("tierOmega", ignoreCase = true))
            assertFalse(key, key.startsWith("beyondImax", ignoreCase = true))
        }
    }
}
