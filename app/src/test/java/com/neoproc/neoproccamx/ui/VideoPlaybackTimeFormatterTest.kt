package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoPlaybackTimeFormatterTest {

    @Test
    fun `elapsed time matches minimal player clock format`() {
        assertEquals("00:00", VideoPlaybackTimeFormatter.elapsed(0L))
        assertEquals("02:07", VideoPlaybackTimeFormatter.elapsed(127_999L))
        assertEquals("1:02:20", VideoPlaybackTimeFormatter.elapsed(3_740_000L))
    }

    @Test
    fun `remaining time is negative and never underflows`() {
        assertEquals("-00:04", VideoPlaybackTimeFormatter.remaining(3_740_000L, 3_744_000L))
        assertEquals("-00:00", VideoPlaybackTimeFormatter.remaining(10_000L, 9_000L))
        assertEquals("-00:00", VideoPlaybackTimeFormatter.remaining(0L, 0L))
    }

    @Test
    fun `double tap seek is clamped to the real timeline`() {
        assertEquals(20_000L, VideoPlaybackSeekPolicy.relativeTarget(10_000L, 60_000L, 10_000L))
        assertEquals(0L, VideoPlaybackSeekPolicy.relativeTarget(4_000L, 60_000L, -10_000L))
        assertEquals(60_000L, VideoPlaybackSeekPolicy.relativeTarget(57_000L, 60_000L, 10_000L))
    }
}
