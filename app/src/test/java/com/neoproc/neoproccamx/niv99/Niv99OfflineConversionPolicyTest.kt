package com.neoproc.neoproccamx.niv99

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class Niv99OfflineConversionPolicyTest {
    @Test
    fun `every requested derivative is default on full frame ten bit and source preserving`() {
        val plans = listOf(
            Niv99OfflineConversionPolicy.npv10("SDR") to Niv99OfflineConversionRoute.NPV10_SDR,
            Niv99OfflineConversionPolicy.npv10("NDR10+ PQ") to Niv99OfflineConversionRoute.NPV10_NDR,
            Niv99OfflineConversionPolicy.recordDevelop(isNdr = false) to
                Niv99OfflineConversionRoute.RECORD_DEVELOP_SDR,
            Niv99OfflineConversionPolicy.recordDevelop(isNdr = true) to
                Niv99OfflineConversionRoute.RECORD_DEVELOP_NDR,
            Niv99OfflineConversionPolicy.rawToLog() to Niv99OfflineConversionRoute.RAW_TO_LOG,
            Niv99OfflineConversionPolicy.rawToRawDeveloped() to
                Niv99OfflineConversionRoute.RAW_TO_RAW_DEVELOPED,
            Niv99OfflineConversionPolicy.rawToNdr10Plus() to
                Niv99OfflineConversionRoute.RAW_TO_NDR10_PLUS,
            Niv99OfflineConversionPolicy.rawToNivukxVision() to
                Niv99OfflineConversionRoute.RAW_TO_NIVUKX_VISION
        )

        assertEquals(Niv99OfflineConversionRoute.entries.size, plans.size)
        plans.forEach { (plan, expectedRoute) ->
            assertEquals(expectedRoute, plan.route)
            assertTrue(plan.enabled)
            assertTrue(plan.fullFrame)
            assertTrue(plan.preserveDimensions)
            assertTrue(plan.preserveFrameCount)
            assertTrue(plan.preservePresentationTimestamps)
            assertTrue(plan.preserveSource)
            assertTrue(plan.capturedWhiteBalanceAuthoritative)
            if (expectedRoute == Niv99OfflineConversionRoute.RAW_TO_RAW_DEVELOPED) {
                assertFalse(plan.spatialDenoise)
                assertFalse(plan.temporalDenoise)
                assertFalse(plan.detailProtection)
                assertFalse(plan.highlightProtection)
            } else {
                assertTrue(plan.spatialDenoise)
                assertTrue(plan.temporalDenoise)
                assertTrue(plan.detailProtection)
                assertTrue(plan.highlightProtection)
            }
            assertTrue(plan.minimumOutputBitDepth >= 10)
        }
    }

    @Test
    fun `raw delivery names resolve to the exact NIV99 processing route`() {
        assertEquals(
            Niv99OfflineConversionRoute.RAW_TO_LOG,
            Niv99OfflineConversionPolicy.forRawDelivery("NEOLOG").route
        )
        assertEquals(
            Niv99OfflineConversionRoute.RAW_TO_NDR10_PLUS,
            Niv99OfflineConversionPolicy.forRawDelivery("NDR10_PLUS").route
        )
        assertEquals(
            Niv99OfflineConversionRoute.RAW_TO_NIVUKX_VISION,
            Niv99OfflineConversionPolicy.forRawDelivery("NIVUKX_VISION").route
        )
    }

    @Test
    fun `full frame 4K25 and lower resolution 60 preserve cadence exactly`() {
        Niv99OfflineConversionPolicy.rawToNivukxVision().requireFrameContract(
            sourceWidth = 3840,
            sourceHeight = 2160,
            outputWidth = 3840,
            outputHeight = 2160,
            sourceFps = 25,
            outputFps = 25,
            outputBitDepth = 10
        )
        Niv99OfflineConversionPolicy.recordDevelop(isNdr = false).requireFrameContract(
            sourceWidth = 1920,
            sourceHeight = 1080,
            outputWidth = 1920,
            outputHeight = 1080,
            sourceFps = 60,
            outputFps = 60,
            outputBitDepth = 10
        )
    }

    @Test
    fun `conversion contract rejects scaling retiming and eight bit output`() {
        val plan = Niv99OfflineConversionPolicy.rawToLog()
        assertThrows(IllegalArgumentException::class.java) {
            plan.requireFrameContract(3840, 2160, 1920, 1080, 25, 25, 10)
        }
        assertThrows(IllegalArgumentException::class.java) {
            plan.requireFrameContract(3840, 2160, 3840, 2160, 25, 24, 10)
        }
        assertThrows(IllegalArgumentException::class.java) {
            plan.requireFrameContract(3840, 2160, 3840, 2160, 25, 25, 8)
        }
    }

    @Test
    fun `residual WB is allowed only for explicit automatic capture authority`() {
        assertEquals(
            Niv99OfflineWhiteBalanceAuthorityPolicy.Authority.AUTOMATIC_REFINEMENT_ALLOWED,
            Niv99OfflineWhiteBalanceAuthorityPolicy.resolve(
                mapOf(
                    "manualWhiteBalanceActive" to "false",
                    "awbLockActive" to "false",
                    "whiteBalanceAuthority" to "AUTO"
                )
            )
        )
        assertEquals(
            Niv99OfflineWhiteBalanceAuthorityPolicy.Authority.MANUAL_CAPTURE_PRESERVED,
            Niv99OfflineWhiteBalanceAuthorityPolicy.resolve(
                mapOf(
                    "manualWhiteBalanceActive" to "true",
                    "awbLockActive" to "false",
                    "whiteBalanceAuthority" to "MANUAL"
                )
            )
        )
        assertEquals(
            Niv99OfflineWhiteBalanceAuthorityPolicy.Authority.AWB_LOCK_PRESERVED,
            Niv99OfflineWhiteBalanceAuthorityPolicy.resolve(
                mapOf(
                    "manualWhiteBalanceActive" to "false",
                    "awbLockActive" to "true",
                    "whiteBalanceAuthority" to "AWB_LOCK"
                )
            )
        )
        assertEquals(
            Niv99OfflineWhiteBalanceAuthorityPolicy.Authority.UNKNOWN_CAPTURE_PRESERVED,
            Niv99OfflineWhiteBalanceAuthorityPolicy.resolve(emptyMap())
        )
    }
}
