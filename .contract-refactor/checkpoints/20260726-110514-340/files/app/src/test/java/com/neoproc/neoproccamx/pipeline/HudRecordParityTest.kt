package com.neoproc.neoproccamx.pipeline

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import org.junit.Assert.assertArrayEquals
import org.junit.Test

/**
 * s8-f01 — non-destructive + no-downgrade lock for the live zebra HUD producer. The overlay must
 * never mutate the source luma the encoder reads (overlay-on == overlay-off recorded bytes), and
 * routing buildZebraMask through HudIreScale must be byte-identical to the legacy threshold formula
 * while USE_LEGAL_RANGE_IRE is off. HighEndQualityPolicy strict is pinned false (it is a mutable
 * cross-module global that subtracts 4 from the zebra threshold) so the lock is deterministic.
 */
class HudRecordParityTest {

    private inline fun <T> withStrict(enabled: Boolean, block: () -> T): T {
        val prevEnabled = HighEndQualityPolicy.enabled
        val prevStrict = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.enabled = enabled
        HighEndQualityPolicy.strictHighEndOnly = enabled
        try {
            return block()
        } finally {
            HighEndQualityPolicy.enabled = prevEnabled
            HighEndQualityPolicy.strictHighEndOnly = prevStrict
        }
    }

    private fun rampLuma(width: Int, height: Int): ByteArray =
        ByteArray(width * height) { i -> ((i * 255) / (width * height - 1)).toByte() }

    @Test
    fun buildZebraMaskDoesNotMutateSourceLuma() {
        withStrict(enabled = false) {
            val width = 8
            val height = 8
            val luma = rampLuma(width, height)
            val before = luma.copyOf()
            FrameAnalysisMath.buildZebraMask(width, height, luma, maxMaskLongEdge = 320, zebraThreshold = 0.9f)
            assertArrayEquals("zebra overlay must not mutate the source luma", before, luma)
        }
    }

    @Test
    fun flagOffZebraAlphaIsByteIdenticalToLegacyFormula() {
        withStrict(enabled = false) {
            val width = 8
            val height = 8
            val threshold01 = 0.9f
            val luma = rampLuma(width, height)
            val mask = FrameAnalysisMath.buildZebraMask(width, height, luma, maxMaskLongEdge = 320, zebraThreshold = threshold01)

            // Recompute the legacy threshold formula (strict off => no -4) and rebuild the expected alpha.
            val legacyThreshold = ((threshold01 * 255f).toInt()).coerceIn(0, 255)
            val step = maxOf(1, maxOf(width, height) / 320)
            val maskW = maxOf(2, width / step)
            val maskH = maxOf(2, height / step)
            val expected = ByteArray(maskW * maskH)
            for (y in 0 until maskH) {
                for (x in 0 until maskW) {
                    val value = luma[(y * step) * width + (x * step)].toInt() and 0xFF
                    expected[y * maskW + x] = if (value >= legacyThreshold) 0x7F.toByte() else 0x00.toByte()
                }
            }
            assertArrayEquals("flag-off zebra alpha must match the legacy formula byte-for-byte", expected, mask.alphaMask)
        }
    }
}
