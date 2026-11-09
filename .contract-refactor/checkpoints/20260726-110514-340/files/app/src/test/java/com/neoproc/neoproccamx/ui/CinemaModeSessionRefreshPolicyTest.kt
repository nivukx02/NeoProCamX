package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.recording.CinemaVideoMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CinemaModeSessionRefreshPolicyTest {

    @Test
    fun `entering raw forces reader and session rebuild`() {
        val decision = CinemaModeSessionRefreshPolicy.resolve(
            previousMode = CinemaVideoMode.PRO,
            currentMode = CinemaVideoMode.RAW,
            videoModeActive = true,
            hdrMode = "OFF",
            captureActive = false
        )

        assertTrue(decision.shouldRefresh)
        assertTrue(decision.rebuildReaders)
        assertTrue(decision.forceRebuild)
    }

    @Test
    fun `leaving raw forces reader and session rebuild`() {
        val decision = CinemaModeSessionRefreshPolicy.resolve(
            previousMode = CinemaVideoMode.RAW,
            currentMode = CinemaVideoMode.LOG,
            videoModeActive = true,
            hdrMode = "OFF",
            captureActive = false
        )

        assertTrue(decision.shouldRefresh)
        assertTrue(decision.rebuildReaders)
        assertTrue(decision.forceRebuild)
    }

    @Test
    fun `pro log and hdr mode transitions force session rebuild without reader churn`() {
        val logDecision = CinemaModeSessionRefreshPolicy.resolve(
            previousMode = CinemaVideoMode.PRO,
            currentMode = CinemaVideoMode.LOG,
            videoModeActive = true,
            hdrMode = "OFF",
            captureActive = false
        )
        val hdrDecision = CinemaModeSessionRefreshPolicy.resolve(
            previousMode = CinemaVideoMode.PRO,
            currentMode = CinemaVideoMode.PRO,
            videoModeActive = true,
            hdrMode = "HDR10",
            captureActive = false
        )

        assertTrue(logDecision.shouldRefresh)
        assertFalse(logDecision.rebuildReaders)
        assertTrue(logDecision.forceRebuild)
        assertTrue(hdrDecision.shouldRefresh)
        assertFalse(hdrDecision.rebuildReaders)
        assertTrue(hdrDecision.forceRebuild)
    }

    @Test
    fun `same cinema mode refreshes when effective pipeline signature changes`() {
        val decision = CinemaModeSessionRefreshPolicy.resolve(
            previousMode = CinemaVideoMode.PRO,
            currentMode = CinemaVideoMode.PRO,
            videoModeActive = true,
            hdrMode = "OFF",
            captureActive = false,
            previousPipelineSignature = "true|PRO|HDR_PQ|HDR_10BIT|BT2100_PQ|HDR10|Rec.2020-PQ|HDR10|true",
            currentPipelineSignature = "true|PRO|PRO_SDR|SDR|REC709_NEUTRAL|OFF|Rec.709|SDR|false"
        )

        assertTrue(decision.shouldRefresh)
        assertFalse(decision.rebuildReaders)
        assertTrue(decision.forceRebuild)
    }

    @Test
    fun `active capture skips mode switch refresh`() {
        val decision = CinemaModeSessionRefreshPolicy.resolve(
            previousMode = CinemaVideoMode.PRO,
            currentMode = CinemaVideoMode.RAW,
            videoModeActive = true,
            hdrMode = "OFF",
            captureActive = true
        )

        assertFalse(decision.shouldRefresh)
        assertFalse(decision.rebuildReaders)
        assertFalse(decision.forceRebuild)
    }
}
