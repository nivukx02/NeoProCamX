package com.neoproc.neoproccamx.rendering

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewGradeWhileRecordingFlagTest {

    @After
    fun tearDown() {
        PreviewGradeWhileRecordingFlag.registerOnChanged(null)
        PreviewGradeWhileRecordingFlag.set(true)
    }

    @Test
    fun `default is on so recording preserves an existing parity preview`() {
        assertTrue(PreviewGradeWhileRecordingFlag.enabled)
    }

    @Test
    fun `set toggles and fires onChanged only on a real change`() {
        var count = 0
        PreviewGradeWhileRecordingFlag.registerOnChanged { count++ }
        PreviewGradeWhileRecordingFlag.set(true)
        assertEquals(0, count)
        PreviewGradeWhileRecordingFlag.set(false)
        assertFalse(PreviewGradeWhileRecordingFlag.enabled)
        assertEquals(1, count)
        PreviewGradeWhileRecordingFlag.set(false)
        assertEquals(1, count)
        PreviewGradeWhileRecordingFlag.set(true)
        assertTrue(PreviewGradeWhileRecordingFlag.enabled)
        assertEquals(2, count)
    }
}
