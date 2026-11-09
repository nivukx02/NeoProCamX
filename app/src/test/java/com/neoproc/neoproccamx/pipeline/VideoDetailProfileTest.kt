package com.neoproc.neoproccamx.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoDetailProfileTest {
    @Test
    fun `missing stored detail profile defaults to focused detail`() {
        assertEquals(VideoDetailProfile.FOCUSED_DETAIL, VideoDetailProfile.fromPreference(null))
        assertEquals(VideoDetailProfile.FOCUSED_DETAIL, VideoDetailProfile.fromPreference(""))
    }

    @Test
    fun `stored detail profile accepts stable keys and visible labels`() {
        assertEquals(
            VideoDetailProfile.FOCUSED_DETAIL,
            VideoDetailProfile.fromPreference("FOCUSED_DETAIL")
        )
        assertEquals(
            VideoDetailProfile.FOCUSED_DETAIL,
            VideoDetailProfile.fromPreference("Focused Detail")
        )
        assertEquals(
            VideoDetailProfile.NATURAL_DETAIL,
            VideoDetailProfile.fromPreference("natural_detail")
        )
        assertEquals(
            VideoDetailProfile.NATURAL_DETAIL,
            VideoDetailProfile.fromPreference("Natural Detail")
        )
    }

    @Test
    fun `fromPreference resolves extreme detail enum and display names`() {
        assertEquals(VideoDetailProfile.EXTREME_DETAIL, VideoDetailProfile.fromPreference("EXTREME_DETAIL"))
        assertEquals(VideoDetailProfile.EXTREME_DETAIL, VideoDetailProfile.fromPreference("Extreme Detail"))
    }
}
