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
        PreviewGradeWhileRecordingFlag.set(false)
    }

    @Test
    fun `default is off so recording uses the zero drop light preview path`() {
        assertFalse(PreviewGradeWhileRecordingFlag.enabled)
    }

    @Test
    fun `set toggles and fires onChanged only on a real change`() {
        var count = 0
        PreviewGradeWhileRecordingFlag.registerOnChanged { count++ }
        PreviewGradeWhileRecordingFlag.set(false)
        assertEquals(0, count)
        PreviewGradeWhileRecordingFlag.set(true)
        assertTrue(PreviewGradeWhileRecordingFlag.enabled)
        assertEquals(1, count)
        PreviewGradeWhileRecordingFlag.set(true)
        assertEquals(1, count)
        PreviewGradeWhileRecordingFlag.set(false)
        assertFalse(PreviewGradeWhileRecordingFlag.enabled)
        assertEquals(2, count)
    }
}
