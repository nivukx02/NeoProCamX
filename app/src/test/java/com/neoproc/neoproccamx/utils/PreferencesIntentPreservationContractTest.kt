package com.neoproc.neoproccamx.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PreferencesIntentPreservationContractTest {
    private val source: String
        get() = File("src/main/java/com/neoproc/neoproccamx/utils/PreferencesManager.kt")
            .readText()
            .replace("\r\n", "\n")

    @Test
    fun `HDR default migration starts a fresh install in true Video and preserves user selection`() {
        val migration = source
            .substringAfter("private fun migrateHdrRecordingDefaultIfNeeded()")
            .substringBefore("var videoSize:")

        assertTrue(
            migration.contains(
                "if (getBooleanCompat(KEY_HDR_RECORDING_DEFAULT_MIGRATED, false)) return"
            )
        )
        assertTrue(migration.contains("prefs.contains(KEY_VIDEO_HDR_MODE)"))
        assertTrue(migration.contains("if (!hasStoredHdrSelection)"))
        assertTrue(
            migration.contains(
                "putString(KEY_VIDEO_HDR_MODE, VideoHdrStartupDefaultPolicy.DEFAULT_ACTIVE_MODE)"
            )
        )
        assertTrue(migration.contains("putBoolean(KEY_HDR_RECORDING_DEFAULT_MIGRATED, true)"))
        assertFalse(migration.contains("putString(KEY_VIDEO_HDR_MODE, \"NDR10+\")"))
        assertFalse(migration.contains("remove(KEY_VIDEO_HDR_MODE)"))
        assertFalse(migration.contains("current.equals(\"OFF\""))
    }

    @Test
    fun `fresh active mode is OFF while explicit NDR modes remain byte for byte authoritative`() {
        assertEquals("OFF", VideoHdrStartupDefaultPolicy.resolve(null))
        assertEquals("OFF", VideoHdrStartupDefaultPolicy.resolve("OFF"))
        assertEquals("NDR10+", VideoHdrStartupDefaultPolicy.resolve("NDR10+"))
        assertEquals("NIVUKXVISION", VideoHdrStartupDefaultPolicy.resolve("NIVUKXVISION"))

        val hdrPreference = source
            .substringAfter("var videoHdrMode: String")
            .substringBefore("var preferredNdrProfile: String")
        assertTrue(
            hdrPreference.contains(
                "VideoHdrStartupDefaultPolicy.resolve(getStringCompat(KEY_VIDEO_HDR_MODE, null))"
            )
        )
        assertTrue(hdrPreference.contains("putString(KEY_VIDEO_HDR_MODE, v)"))
    }

    @Test
    fun `Video HDR default does not downgrade ten bit or erase preferred NDR conversion profile`() {
        val tenBitPreference = source
            .substringAfter("var tenBitRecordingEnabled: Boolean")
            .substringBefore("var uncompressedCinemaPipelineEnabled: Boolean")
        assertTrue(tenBitPreference.contains("getBooleanCompat(KEY_TEN_BIT_RECORDING, true)"))

        val migration = source
            .substringAfter("private fun migrateHdrRecordingDefaultIfNeeded()")
            .substringBefore("var videoSize:")
        assertFalse(migration.contains("KEY_PREFERRED_NDR_PROFILE"))

        val preferredProfile = source
            .substringAfter("var preferredNdrProfile: String")
            .substringBefore("var videoDetailProfile: VideoDetailProfile")
        assertTrue(preferredProfile.contains("getStringCompat(KEY_PREFERRED_NDR_PROFILE, videoHdrMode)"))
        assertTrue(preferredProfile.contains("putString(KEY_PREFERRED_NDR_PROFILE, v)"))
    }

    @Test
    fun `initialization preserves persisted LUT LOG and video mode intent`() {
        val enforcement = source
            .substringAfter("private fun enforceHiddenCaptureToolsOff()")
            .substringBefore("Product lock: AI Enhancement")

        assertTrue(enforcement.contains("putBoolean(KEY_UNCOMPRESSED_CINEMA_PIPELINE, true)"))
        assertFalse(enforcement.contains("putBoolean(KEY_LUT_ENABLED"))
        assertFalse(enforcement.contains("putBoolean(KEY_LOG_ENABLED"))
        assertFalse(enforcement.contains("putBoolean(KEY_LOG_RECORDING_ENABLED"))
        assertFalse(enforcement.contains("putString(KEY_VIDEO_MODE"))
        assertTrue(source.contains("enforceHiddenCaptureToolsOff()"))
    }
}
