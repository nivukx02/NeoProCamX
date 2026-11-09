package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CinemaCamMtpPublicationPolicyTest {

    @Test
    fun npv10PublicationKeepsExactMasterAndAudioCompanionNames() {
        val master = "NPV10_123_video_raw_3840x2160_30fps.npv10"
        val artifacts = CinemaCamMtpPublicationPolicy.npv10Artifacts(
            masterName = master,
            masterBytes = 8_000L,
            audioName = "$master.audio.npv10a",
            audioBytes = 400L,
            audioRequired = true
        )

        assertEquals("Documents/CamX/Conversion", CinemaCamMtpPublicationPolicy.RELATIVE_PATH)
        assertEquals(listOf(master, "$master.audio.npv10a"), artifacts.map { it.displayName })
        assertTrue(artifacts.all { it.bytes > 0L })
    }

    @Test(expected = IllegalArgumentException::class)
    fun npv10PublicationFailsClosedWhenRequiredAudioIsMissing() {
        CinemaCamMtpPublicationPolicy.npv10Artifacts(
            masterName = "NPV10_123_video_raw_3840x2160_30fps.npv10",
            masterBytes = 8_000L,
            audioName = null,
            audioBytes = 0L,
            audioRequired = true
        )
    }

    @Test
    fun developPublicationRequiresExactDevcolorSibling() {
        val master = "DEVNDR_123_video_ndr_3840x2160_30fps.mov"
        val artifacts = CinemaCamMtpPublicationPolicy.developArtifacts(
            masterName = master,
            masterBytes = 16_000L,
            sidecarName = "$master.devcolor",
            sidecarBytes = 256L
        )

        assertEquals(2, artifacts.size)
        assertEquals(master, artifacts[0].displayName)
        assertEquals("$master.devcolor", artifacts[1].displayName)
        assertEquals(
            CinemaCamMtpPublicationPolicy.MASTER_MIME_TYPE,
            artifacts[0].mimeType
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun directGalleryVideoCannotEnterConversionPublication() {
        CinemaCamMtpPublicationPolicy.developArtifacts(
            masterName = "CAMX_saved_gallery.mov",
            masterBytes = 16_000L,
            sidecarName = "CAMX_saved_gallery.mov.devcolor",
            sidecarBytes = 256L
        )
    }
}
