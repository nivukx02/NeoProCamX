package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawVideoBitDepthPolicyTest {

    @Test
    fun `12 bit sensor samples pass through without frame stretching`() {
        val samples = intArrayOf(0, 64, 2048, 4095)

        val normalized = RawVideoBitDepthPolicy.normalizeSamplesForBitDepth(
            samples = samples,
            targetBitDepth = 12,
            sourceWhiteLevel = 4095
        )

        assertArrayEquals(samples, normalized)
    }

    @Test
    fun `higher precision sensor samples compand into 12 bit monotonically without clipping`() {
        // CP16: the legacy linear right-shift ([0,1024,2048,4095]) is replaced by an
        // invertible perceptual companding curve. Contract = monotone, endpoints pinned,
        // no clipping, and reported as perceptual-log (not identity) for this reduction.
        val samples = intArrayOf(0, 4095, 8192, 16383)

        val normalized = RawVideoBitDepthPolicy.normalizeSamplesForBitDepth(
            samples = samples,
            targetBitDepth = 12,
            sourceWhiteLevel = 16383
        )

        assertEquals(0, normalized[0])
        assertEquals(4095, normalized[3])
        assertTrue(normalized[0] <= normalized[1])
        assertTrue(normalized[1] <= normalized[2])
        assertTrue(normalized[2] <= normalized[3])
        normalized.forEach { assertTrue("code $it in 0..4095", it in 0..4095) }

        assertEquals(
            RawCompandingCurve.COMPANDING_PERCEPTUAL_LOG,
            RawVideoBitDepthPolicy.compandingTypeForBitDepth(targetBitDepth = 12, sourceWhiteLevel = 16383)
        )
    }

    @Test
    fun `raw storage bit depth preserves native 12 bit contract without widening`() {
        val bitDepth = RawVideoBitDepthPolicy.resolveStorageBitDepth(
            requestedBitDepth = 12,
            sourceWhiteLevel = 4095
        )

        assertEquals(12, bitDepth)
    }

    @Test
    fun `native depth gate keeps the full sensor word for a 14 bit sensor when opted in`() {
        // Default (no-downgrade): 14-bit sensor still floors at the legacy 12-bit container.
        assertEquals(
            12,
            RawVideoBitDepthPolicy.resolveStorageBitDepth(
                requestedBitDepth = 12,
                sourceWhiteLevel = 16383,
                preserveNativeDepth = false
            )
        )
        // Device-gated: native depth is preserved (16-bit container holds 14-bit word).
        assertEquals(
            14,
            RawVideoBitDepthPolicy.resolveStorageBitDepth(
                requestedBitDepth = 12,
                sourceWhiteLevel = 16383,
                preserveNativeDepth = true
            )
        )
    }

    @Test
    fun `lower precision raw is not stretched into fake 12 bit samples`() {
        val samples = intArrayOf(0, 256, 512, 1023)

        val normalized = RawVideoBitDepthPolicy.normalizeSamplesForBitDepth(
            samples = samples,
            targetBitDepth = 12,
            sourceWhiteLevel = 1023
        )

        assertArrayEquals(samples, normalized)
    }

    @Test
    fun `native video depth is preserved only for a wide sensor under strict high-end hardware`() {
        // A >12-bit sensor under strict high-end on capable hardware: preserve native depth.
        assertTrue(
            RawVideoBitDepthPolicy.shouldPreserveNativeVideoDepth(
                strictHighEnd = true,
                highEndHardwareSupported = true,
                sourceWhiteLevel = 16383 // 14-bit
            )
        )
        // A 12-bit sensor never needs preservation (nothing above the legacy floor to keep).
        assertFalse(
            RawVideoBitDepthPolicy.shouldPreserveNativeVideoDepth(
                strictHighEnd = true,
                highEndHardwareSupported = true,
                sourceWhiteLevel = 4095 // 12-bit
            )
        )
        // No-downgrade: a wide sensor stays on the legacy 12-bit floor when strict high-end is off or
        // the hardware is not certified — byte-identical to the legacy path.
        assertFalse(
            RawVideoBitDepthPolicy.shouldPreserveNativeVideoDepth(
                strictHighEnd = false,
                highEndHardwareSupported = true,
                sourceWhiteLevel = 16383
            )
        )
        assertFalse(
            RawVideoBitDepthPolicy.shouldPreserveNativeVideoDepth(
                strictHighEnd = true,
                highEndHardwareSupported = false,
                sourceWhiteLevel = 16383
            )
        )
    }
}
