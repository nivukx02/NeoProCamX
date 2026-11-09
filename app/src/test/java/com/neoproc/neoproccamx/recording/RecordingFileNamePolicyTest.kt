package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

class RecordingFileNamePolicyTest {

    private fun date(iso: String) =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(iso)!!

    @Test
    fun `default name is CamX space ddMMyyyy`() {
        // 15 July 2026 -> "15072026" (dd MM yyyy, NOT minutes)
        assertEquals("15072026", RecordingFileNamePolicy.dateLabel(date("2026-07-15")))
        assertEquals("CamX 15072026", RecordingFileNamePolicy.defaultBaseName(date("2026-07-15")))
        assertEquals("CamX 01012026", RecordingFileNamePolicy.defaultBaseName(date("2026-01-01")))
    }

    @Test
    fun `default name pads single-digit day and month`() {
        assertEquals("CamX 05032026", RecordingFileNamePolicy.defaultBaseName(date("2026-03-05")))
    }

    @Test
    fun `resolveBaseName falls back to default when custom is null or blank`() {
        val label = "15072026"
        assertEquals("CamX 15072026", RecordingFileNamePolicy.resolveBaseName(null, label))
        assertEquals("CamX 15072026", RecordingFileNamePolicy.resolveBaseName("", label))
        assertEquals("CamX 15072026", RecordingFileNamePolicy.resolveBaseName("   ", label))
    }

    @Test
    fun `resolveBaseName uses sanitized custom name when present`() {
        assertEquals("Beach Sunset", RecordingFileNamePolicy.resolveBaseName("Beach Sunset", "15072026"))
    }

    @Test
    fun `sanitize keeps letters digits spaces dot underscore hyphen`() {
        assertEquals("My_Clip-2.take1", RecordingFileNamePolicy.sanitizeCustomName("My_Clip-2.take1"))
    }

    @Test
    fun `sanitize replaces illegal runs with single underscore`() {
        assertEquals("A_B", RecordingFileNamePolicy.sanitizeCustomName("A/\\:*?B"))
        assertEquals("shot_01", RecordingFileNamePolicy.sanitizeCustomName("shot@#\$01"))
    }

    @Test
    fun `sanitize collapses whitespace and trims edges`() {
        assertEquals("Beach Sunset", RecordingFileNamePolicy.sanitizeCustomName("  Beach    Sunset  "))
    }

    @Test
    fun `sanitize strips leading and trailing separators`() {
        assertEquals("clip", RecordingFileNamePolicy.sanitizeCustomName("__clip__"))
        assertEquals("clip", RecordingFileNamePolicy.sanitizeCustomName("--clip.."))
        assertEquals("clip", RecordingFileNamePolicy.sanitizeCustomName(" .clip. "))
    }

    @Test
    fun `sanitize returns empty when nothing usable remains`() {
        assertEquals("", RecordingFileNamePolicy.sanitizeCustomName("____"))
        assertEquals("", RecordingFileNamePolicy.sanitizeCustomName("///"))
        assertEquals("", RecordingFileNamePolicy.sanitizeCustomName(null))
    }

    @Test
    fun `sanitize caps length and does not leave a trailing separator after truncation`() {
        val long = "a".repeat(200)
        val result = RecordingFileNamePolicy.sanitizeCustomName(long)
        assertEquals(RecordingFileNamePolicy.MAX_CUSTOM_NAME_LENGTH, result.length)

        // Truncation must not leave a dangling separator at the boundary.
        val boundarySeparator = "a".repeat(RecordingFileNamePolicy.MAX_CUSTOM_NAME_LENGTH - 1) + "_extra"
        val trimmed = RecordingFileNamePolicy.sanitizeCustomName(boundarySeparator)
        assertTrue(trimmed.length <= RecordingFileNamePolicy.MAX_CUSTOM_NAME_LENGTH)
        assertTrue("must not end with a separator", trimmed.last().isLetterOrDigit())
    }

    @Test
    fun `all-illegal custom name resolves to the default`() {
        assertEquals("CamX 15072026", RecordingFileNamePolicy.resolveBaseName("///", "15072026"))
    }
}
