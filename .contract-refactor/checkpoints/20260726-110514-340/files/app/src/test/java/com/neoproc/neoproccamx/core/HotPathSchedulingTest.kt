package com.neoproc.neoproccamx.core

import android.os.Process
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HotPathSchedulingTest {

    @After
    fun restoreDefault() {
        // Singleton — leave it in the shipped default so ordering can't leak into other tests.
        HotPathScheduling.registerOnChanged(null)
        HotPathScheduling.set(true)
    }

    @Test
    fun `ships enabled by default`() {
        assertTrue(
            "Hot-path priority elevation must ship ON (pure scheduling, no downgrade; battery is a non-goal)",
            HotPathScheduling.enabled
        )
    }

    @Test
    fun `urgent tier maps to URGENT_DISPLAY when enabled and DEFAULT when disabled`() {
        HotPathScheduling.set(true)
        assertEquals(Process.THREAD_PRIORITY_URGENT_DISPLAY, HotPathScheduling.urgentDisplayPriority())
        HotPathScheduling.set(false)
        assertEquals(Process.THREAD_PRIORITY_DEFAULT, HotPathScheduling.urgentDisplayPriority())
    }

    @Test
    fun `analysis tier maps to DISPLAY when enabled and DEFAULT when disabled`() {
        HotPathScheduling.set(true)
        assertEquals(Process.THREAD_PRIORITY_DISPLAY, HotPathScheduling.displayPriority())
        HotPathScheduling.set(false)
        assertEquals(Process.THREAD_PRIORITY_DEFAULT, HotPathScheduling.displayPriority())
    }

    @Test
    fun `urgent tier sits above analysis tier (lower nice = higher priority)`() {
        HotPathScheduling.set(true)
        assertTrue(
            "URGENT must be a lower nice value than DISPLAY so it schedules ahead of it",
            HotPathScheduling.urgentDisplayPriority() < HotPathScheduling.displayPriority()
        )
    }

    @Test
    fun `onChanged fires only on an actual change`() {
        HotPathScheduling.set(true)
        var fires = 0
        HotPathScheduling.registerOnChanged { fires++ }
        HotPathScheduling.set(true) // no change
        assertEquals(0, fires)
        HotPathScheduling.set(false) // change
        assertEquals(1, fires)
        HotPathScheduling.set(false) // no change
        assertEquals(1, fires)
    }

    @Test
    fun `apply methods never throw off-device`() {
        // Process.setThreadPriority is unavailable under plain JUnit; the helper must swallow it.
        HotPathScheduling.set(true)
        HotPathScheduling.applyUrgentDisplay()
        HotPathScheduling.applyDisplay()
        HotPathScheduling.set(false)
        HotPathScheduling.applyUrgentDisplay() // no-op when disabled
        assertFalse(HotPathScheduling.enabled)
    }
}
