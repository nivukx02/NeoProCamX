package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class Niv99ConversionNativeRoutingTest {
    @Test
    fun `QuickTime color metadata maps exactly to native NIV99 encodings`() {
        assertEquals(
            Npv10Niv99ColorEncoding(
                matrix = Npv10Niv99ColorMatrix.BT709,
                transfer = Npv10Niv99Transfer.BT709
            ),
            QuickTimeColorAtomConfig.rec709SdrLimited().toNiv99ColorEncoding()
        )
        assertEquals(
            Npv10Niv99ColorEncoding(
                matrix = Npv10Niv99ColorMatrix.BT709,
                transfer = Npv10Niv99Transfer.SRGB
            ),
            QuickTimeColorAtomConfig.displayP3SdrLimited().toNiv99ColorEncoding()
        )
        assertEquals(
            Npv10Niv99ColorEncoding(
                matrix = Npv10Niv99ColorMatrix.BT2020_NCL,
                transfer = Npv10Niv99Transfer.PQ
            ),
            QuickTimeColorAtomConfig(
                colorPrimaries = QuickTimeColorAtomPolicy.PRIMARIES_BT2020,
                transferCharacteristics = QuickTimeColorAtomPolicy.TRANSFER_PQ_ST2084,
                matrixCoefficients = QuickTimeColorAtomPolicy.MATRIX_BT2020_NCL,
                fullRange = false
            ).toNiv99ColorEncoding()
        )
    }

    @Test
    fun `full-range source remains explicit and cannot enter limited-only native kernel`() {
        val encoding = QuickTimeColorAtomConfig.rec709SdrLimited()
            .copy(fullRange = true)
            .toNiv99ColorEncoding()

        assertFalse(encoding.limitedRange)
    }

    @Test
    fun `unsupported P010 matrix fails closed`() {
        assertThrows(IllegalStateException::class.java) {
            QuickTimeColorAtomConfig.rec709SdrLimited()
                .copy(matrixCoefficients = QuickTimeColorAtomPolicy.MATRIX_RGB_IDENTITY)
                .toNiv99ColorEncoding()
        }
    }

    @Test
    fun `native SIMD evidence distinguishes complete mixed and CPU routes`() {
        assertEquals(
            "NIV99_NATIVE_SIMD_P010_DIRECT",
            Npv10ExportProcessingEvidence.fromNativeSimdFrameCounts(
                signature = "s",
                nativeSimdFrames = 12L,
                cpuFrames = 0L,
                lastFallbackReason = "NONE"
            ).processingPath
        )
        assertEquals(
            "NIV99_NATIVE_SIMD_P010_DIRECT_CPU_FALLBACK",
            Npv10ExportProcessingEvidence.fromNativeSimdFrameCounts(
                signature = "s",
                nativeSimdFrames = 4L,
                cpuFrames = 8L,
                lastFallbackReason = "NATIVE_FAILURE"
            ).processingPath
        )
        assertEquals(
            "CPU_P010_FALLBACK",
            Npv10ExportProcessingEvidence.fromNativeSimdFrameCounts(
                signature = "s",
                nativeSimdFrames = 0L,
                cpuFrames = 12L,
                lastFallbackReason = "UNAVAILABLE"
            ).processingPath
        )
    }

    @Test
    fun `negative native SIMD evidence is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            Npv10ExportProcessingEvidence.fromNativeSimdFrameCounts(
                signature = "s",
                nativeSimdFrames = -1L,
                cpuFrames = 1L,
                lastFallbackReason = "INVALID"
            )
        }
    }
}
