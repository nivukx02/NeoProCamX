package com.neoproc.neoproccamx.rendering

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Contract for the record-then-develop master switch. Locks the conservative object default (OFF), the
 * product startup default (ON for the converter workflow), the change-guarded [RecordDevelopModeFlag.onChanged]
 * callback, and clean reset semantics.
 */
class RecordDevelopModeFlagTest {

    @Before
    fun reset() {
        RecordDevelopModeFlag.registerOnChanged(null)
        RecordDevelopModeFlag.set(false)
    }

    @After
    fun restore() {
        RecordDevelopModeFlag.registerOnChanged(null)
        RecordDevelopModeFlag.set(false)
    }

    @Test
    fun defaultsOffForNoDowngrade() {
        assertFalse("record-then-develop must default OFF", RecordDevelopModeFlag.enabled)
    }

    @Test
    fun setTogglesEnabled() {
        RecordDevelopModeFlag.set(true)
        assertTrue(RecordDevelopModeFlag.enabled)
        RecordDevelopModeFlag.set(false)
        assertFalse(RecordDevelopModeFlag.enabled)
    }

    @Test
    fun onChangedFiresOnlyOnRealChange() {
        var count = 0
        RecordDevelopModeFlag.registerOnChanged { count++ }
        RecordDevelopModeFlag.set(false) // no change from default -> no callback
        assertEquals(0, count)
        RecordDevelopModeFlag.set(true) // change -> 1
        assertEquals(1, count)
        RecordDevelopModeFlag.set(true) // no change -> still 1
        assertEquals(1, count)
        RecordDevelopModeFlag.set(false) // change -> 2
        assertEquals(2, count)
    }

    @Test
    fun productDefaultsEnableConverterWorkflowAtStartup() {
        val candidates = listOf(
            File("app/src/main/java/com/neoproc/neoproccamx/ui/ProductDefaultsApplier.kt"),
            File("src/main/java/com/neoproc/neoproccamx/ui/ProductDefaultsApplier.kt")
        )
        val source = candidates.first { it.isFile }.readText()
        assertTrue(
            "Startup must enable the record-then-develop lane so SDR/NDR converter masters are created.",
            source.contains("RecordDevelopModeFlag.set(true)")
        )
    }
}
