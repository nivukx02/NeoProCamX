package com.neoproc.neoproccamx.ui

import com.neoproc.neoproccamx.pipeline.HudIreScale
import com.neoproc.neoproccamx.recording.AudioLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s9-f11 / CP-QV11 — locks the additive pro-formatter extensions: SMPTE timecode (ported from
 * ProInfoOverlay), the audio peak classifier + segment meter, and the false-colour band label
 * (delegating to HudIreScale). Pure JUnit4, no Android runtime.
 */
class CameraPreviewHudFormatterProTest {

    // ── Timecode ────────────────────────────────────────────────────────────────

    @Test
    fun timecodeZeroIsAllZeros() {
        assertEquals("00:00:00:00", CameraPreviewHudFormatter.formatTimecode(0L, 24))
    }

    @Test
    fun timecodeOneHourOneMinuteOneSecondAt24Fps() {
        // 3661000 ms = 1h 1m 1s, no sub-second remainder -> frame 00.
        assertEquals("01:01:01:00", CameraPreviewHudFormatter.formatTimecode(3_661_000L, 24))
    }

    @Test
    fun timecodeSubSecondAt60FpsClampsToFrame59() {
        // 999 ms remainder * 60 / 1000 = 59 -> highest valid frame index (fps - 1).
        assertEquals("00:00:00:59", CameraPreviewHudFormatter.formatTimecode(999L, 60))
    }

    @Test
    fun timecodeFpsZeroIsCoercedAndDoesNotCrash() {
        // safeFps = 1, frames clamped to [0, 0]; sub-second data cannot escape frame 00.
        assertEquals("00:00:00:00", CameraPreviewHudFormatter.formatTimecode(500L, 0))
    }

    @Test
    fun timecodeDropFrameUsesSemicolonSeparatorSameNumbers() {
        assertEquals("00:00:00;00", CameraPreviewHudFormatter.formatTimecode(0L, 30, dropFrame = true))
    }

    // ── Audio meter classification ────────────────────────────────────────────────

    @Test
    fun audioMeterClipAtCeiling() {
        // -1 dBFS is the limiter ceiling -> CLIP.
        assertEquals("CLIP", CameraPreviewHudFormatter.formatAudioMeter(AudioLevel(-1f, -3f)))
    }

    @Test
    fun audioMeterWarnAtThreshold() {
        // -6 dBFS is the limiter threshold -> WARN.
        assertEquals("WARN", CameraPreviewHudFormatter.formatAudioMeter(AudioLevel(-6f, -12f)))
    }

    @Test
    fun audioMeterNominalBelowThreshold() {
        assertEquals("NOM", CameraPreviewHudFormatter.formatAudioMeter(AudioLevel(-20f, -30f)))
    }

    // ── Audio meter segments ──────────────────────────────────────────────────────

    @Test
    fun audioMeterSegmentsMonotoneAndClamped() {
        val segments = 20
        var prev = -1
        var db = -80f
        while (db <= 5f) {
            val lit = CameraPreviewHudFormatter.audioMeterSegments(db, segments)
            assertTrue("clamped low at $db", lit >= 0)
            assertTrue("clamped high at $db", lit <= segments)
            assertTrue("monotone at $db", lit >= prev)
            prev = lit
            db += 1f
        }
        // Floor fills none; the ceiling (-1 dBFS) and above fill all segments.
        assertEquals(0, CameraPreviewHudFormatter.audioMeterSegments(-60f, segments))
        assertEquals(segments, CameraPreviewHudFormatter.audioMeterSegments(-1f, segments))
        assertEquals(segments, CameraPreviewHudFormatter.audioMeterSegments(0f, segments))
    }

    @Test
    fun audioMeterSegmentsGuardsNonPositiveCountAndNonFinite() {
        assertEquals(0, CameraPreviewHudFormatter.audioMeterSegments(-3f, 0))
        assertEquals(0, CameraPreviewHudFormatter.audioMeterSegments(Float.NaN, 20))
        assertEquals(0, CameraPreviewHudFormatter.audioMeterSegments(Float.NEGATIVE_INFINITY, 20))
    }

    // ── False-colour band label ────────────────────────────────────────────────────

    @Test
    fun falseColorBandLabelsMatchHudIreScaleAtLowMidAndClipIre() {
        assertEquals(
            HudIreScale.FalseColorBand.PURPLE.name,
            CameraPreviewHudFormatter.falseColorBandLabel(1f)
        )
        assertEquals(
            HudIreScale.FalseColorBand.PINK_SKIN.name,
            CameraPreviewHudFormatter.falseColorBandLabel(54f)
        )
        assertEquals(
            HudIreScale.FalseColorBand.RED_CLIP.name,
            CameraPreviewHudFormatter.falseColorBandLabel(100f)
        )
    }

    @Test
    fun falseColorBandLabelGuardsNegativeAndNonFiniteIre() {
        // Negative / non-finite IRE is coerced to 0 -> lowest (PURPLE) band, never throws.
        assertEquals(
            HudIreScale.FalseColorBand.PURPLE.name,
            CameraPreviewHudFormatter.falseColorBandLabel(-5f)
        )
        assertEquals(
            HudIreScale.FalseColorBand.PURPLE.name,
            CameraPreviewHudFormatter.falseColorBandLabel(Float.NaN)
        )
    }
}
