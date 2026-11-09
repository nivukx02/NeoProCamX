package com.neoproc.neoproccamx.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StillCaptureCapturePolicyTest {

    @Test
    fun singleCaptureRawMode_usesSoftwareFallbackWhenNativeRawIsUnavailable() {
        val mode = StillCaptureCapturePolicy.resolveSingleCaptureRawMode(
            rawRequested = true,
            supportsRaw = false
        )

        assertEquals(StillCaptureCapturePolicy.SingleCaptureRawMode.PROCESSED_SOURCE_FALLBACK, mode)
    }

    @Test
    fun singleCaptureRawMode_usesNativeRawCompanionWhenSupported() {
        val mode = StillCaptureCapturePolicy.resolveSingleCaptureRawMode(
            rawRequested = true,
            supportsRaw = true
        )

        assertEquals(StillCaptureCapturePolicy.SingleCaptureRawMode.NATIVE_RAW_COMPANION, mode)
    }

    @Test
    fun hdrRawCompanionPolicies_splitNativeAndSoftwareFallbackPaths() {
        assertTrue(
            StillCaptureCapturePolicy.shouldCaptureRawCompanion(
                rawRequested = true,
                supportsRaw = true
            )
        )
        assertFalse(
            StillCaptureCapturePolicy.shouldCaptureRawCompanion(
                rawRequested = true,
                supportsRaw = false
            )
        )
        assertTrue(
            StillCaptureCapturePolicy.shouldCreateProcessedSourceCompanion(
                rawRequested = true,
                supportsRaw = false
            )
        )
        assertFalse(
            StillCaptureCapturePolicy.shouldCreateProcessedSourceCompanion(
                rawRequested = false,
                supportsRaw = false
            )
        )
    }

    @Test
    fun resolveHdrBracketEvs_prefersRecommendedBracketWhenPresent() {
        val bracket = StillCaptureCapturePolicy.resolveHdrBracketEvs(
            recommendedBracket = listOf(1, -2, 0, -2),
            hdrMode = "SMART_HDR"
        )

        assertEquals(listOf(0, 1, -2), bracket)
    }

    @Test
    fun resolveHdrBracketEvs_fallsBackToUltraPreset() {
        val bracket = StillCaptureCapturePolicy.resolveHdrBracketEvs(
            recommendedBracket = null,
            hdrMode = "ULTRA_HDR"
        )

        assertEquals(listOf(0, -1, 1, -2, 2, -3, 3, -4, 4), bracket)
    }

    @Test
    fun resolveHdrBracketEvs_balancesRecommendationWhenOneSideIsMissing() {
        val bracket = StillCaptureCapturePolicy.resolveHdrBracketEvs(
            recommendedBracket = listOf(2, 1),
            hdrMode = "SMART_HDR"
        )

        assertEquals(listOf(0, -1, 1, -2), bracket)
    }

    @Test
    fun resolveHdrBracketEvs_limitsSmartHdrToFourFramesForLowerCaptureLatency() {
        val bracket = StillCaptureCapturePolicy.resolveHdrBracketEvs(
            recommendedBracket = null,
            hdrMode = "SMART_HDR"
        )

        assertEquals(4, bracket.size)
    }
}
