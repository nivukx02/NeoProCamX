package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class RecordDevelopCodecQualityPolicyTest {
    @Test
    fun fourKPlanUsesEliteBitrateQualityAndMaximumSupportedComplexity() {
        val plan = RecordDevelopEncoderQualityPolicy.resolve(
            width = 3840,
            height = 2160,
            fps = 30,
            bitrateLower = 10_000_000,
            bitrateUpper = 500_000_000,
            complexityLower = 0,
            complexityUpper = 10
        )

        assertEquals(335_923_200, plan.targetBitrate)
        assertEquals(100, plan.quality)
        assertEquals(10, plan.complexity)
        assertEquals(18, plan.maximumQp)
    }

    @Test
    fun bitrateIsBoundedByRealCodecCapabilityWithoutWeakeningQualityIntent() {
        val plan = RecordDevelopEncoderQualityPolicy.resolve(
            width = 4096,
            height = 2160,
            fps = 60,
            bitrateLower = 20_000_000,
            bitrateUpper = 240_000_000
        )

        assertEquals(240_000_000, plan.targetBitrate)
        assertEquals(240_000_000, plan.maximumBitrate)
        assertEquals(100, plan.quality)
    }

    @Test
    fun decoderSelectionRejectsAnySilentEightBitOrNonP010Fallback() {
        val candidates = listOf(
            RecordDevelopDecoderCandidate("hardware-8bit", true, true, false, false, true),
            RecordDevelopDecoderCandidate("software-main10", false, false, true, true, true),
            RecordDevelopDecoderCandidate("hardware-main10", true, true, true, true, true)
        )

        assertEquals("hardware-main10", RecordDevelopDecoderQualityPolicy.select(candidates)?.name)
        assertNull(
            RecordDevelopDecoderQualityPolicy.select(
                candidates.filter { !it.supportsP010 }
            )
        )
    }

    @Test
    fun decoderOutputRequiresExactP010GeometryAndStrictPts() {
        RecordDevelopDecoderQualityPolicy.requireOutput(
            expectedWidth = 3840,
            expectedHeight = 2160,
            actualWidth = 3840,
            actualHeight = 2160,
            p010 = true,
            strideBytes = 7680,
            sliceHeight = 2160
        )
        RecordDevelopDecoderQualityPolicy.requireMonotonicPts(33_333L, 66_666L)

        assertThrows(IllegalArgumentException::class.java) {
            RecordDevelopDecoderQualityPolicy.requireMonotonicPts(66_666L, 66_666L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            RecordDevelopDecoderQualityPolicy.requireOutput(
                expectedWidth = 3840,
                expectedHeight = 2160,
                actualWidth = 3840,
                actualHeight = 2160,
                p010 = false,
                strideBytes = 7680,
                sliceHeight = 2160
            )
        }
    }
}
