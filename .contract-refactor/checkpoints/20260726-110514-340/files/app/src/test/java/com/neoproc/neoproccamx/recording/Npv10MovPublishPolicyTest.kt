package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class Npv10MovPublishPolicyTest {

    @Test
    fun convertedMovPublishesToMoviesAlbumWithQuickTimeMime() {
        val plan = Npv10MovPublishPolicy.resolve(File("NPV10_123_pro_3840x2160_60fps.npv10"))

        assertEquals("video/quicktime", plan.mimeType)
        assertEquals("Movies/CamXNPV10", plan.relativePath)
        assertTrue(plan.displayName.endsWith(".mov"))
    }

    @Test
    fun galleryEligibilityAllowsOnlyConvertedMovNeverPrivateNpv10Master() {
        val master = File("NPV10_123_pro_3840x2160_60fps.npv10")
        val converted = File("NPV10_123_pro_3840x2160_60fps.mov")

        val masterDecision = Npv10MovPublishPolicy.galleryEligibility(master)
        val convertedDecision = Npv10MovPublishPolicy.galleryEligibility(converted)

        assertEquals(false, masterDecision.publishable)
        assertEquals(true, convertedDecision.publishable)
        assertTrue(masterDecision.reason.contains("private"))
        assertEquals("video/quicktime", Npv10MovPublishPolicy.resolveConvertedMov(converted).mimeType)
    }
}
