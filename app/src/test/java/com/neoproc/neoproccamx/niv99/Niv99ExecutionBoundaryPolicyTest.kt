package com.neoproc.neoproccamx.niv99

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Niv99ExecutionBoundaryPolicyTest {
    @Test
    fun `live capture paths are isolated from NIV99`() {
        assertFalse(Niv99ExecutionBoundaryPolicy.LIVE_CAPTURE_METADATA_ENABLED)
        assertFalse(Niv99ExecutionBoundaryPolicy.LIVE_ANALYSIS_ENABLED)
        assertFalse(Niv99ExecutionBoundaryPolicy.allowsLivePixelProcessing(recordingPath = false))
        assertFalse(Niv99ExecutionBoundaryPolicy.allowsLivePixelProcessing(recordingPath = true))
        assertFalse(Niv99ExecutionBoundaryPolicy.ANY_LIVE_PROCESSING_ENABLED)
    }

    @Test
    fun `offline conversion remains enabled at full fidelity`() {
        assertTrue(Niv99ExecutionBoundaryPolicy.OFFLINE_CONVERSION_ENABLED)
        listOf(
            Niv99OfflineConversionPolicy.npv10Sdr(),
            Niv99OfflineConversionPolicy.npv10("NDR10+ PQ"),
            Niv99OfflineConversionPolicy.recordDevelop(isNdr = false),
            Niv99OfflineConversionPolicy.recordDevelop(isNdr = true),
            Niv99OfflineConversionPolicy.rawToLog(),
            Niv99OfflineConversionPolicy.rawToRawDeveloped(),
            Niv99OfflineConversionPolicy.rawToNdr10Plus(),
            Niv99OfflineConversionPolicy.rawToNivukxVision()
        ).forEach { plan ->
            assertTrue("offline route ${plan.route} must remain enabled", plan.enabled)
            assertTrue("offline route ${plan.route} must remain full frame", plan.fullFrame)
            assertTrue("offline route ${plan.route} must preserve dimensions", plan.preserveDimensions)
            assertTrue("offline route ${plan.route} must preserve frame count", plan.preserveFrameCount)
            assertTrue("offline route ${plan.route} must preserve PTS", plan.preservePresentationTimestamps)
            assertTrue("offline route ${plan.route} must preserve source", plan.preserveSource)
            assertTrue("offline route ${plan.route} must remain at least 10-bit", plan.minimumOutputBitDepth >= 10)
        }
    }
}
