package com.neoproc.neoproccamx.ui.controls

import com.neoproc.neoproccamx.rendering.RecordDevelopModeFlag
import com.neoproc.neoproccamx.ui.AppMode
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsSectionResolverTest {

    @After
    fun resetRecordDevelopFlag() {
        // The record-develop cards are gated on a process-global flag; never leak an ON state across tests.
        RecordDevelopModeFlag.set(false)
    }

    @Test
    fun `video mode includes converter and video color sections in order`() {
        RecordDevelopModeFlag.set(false)
        val sections = SettingsSectionResolver.resolve(AppMode.VIDEO)

        assertEquals(
            listOf(
                SettingsSectionKey.VIDEO,
                SettingsSectionKey.RAW_CONVERTER,
                SettingsSectionKey.CAMERA,
                SettingsSectionKey.VIDEO_COLOR,
                SettingsSectionKey.FOCUS,
                SettingsSectionKey.SYSTEM
            ),
            sections
        )
        assertFalse(sections.any { it.name == "MONITORING" })
        assertFalse(sections.contains(SettingsSectionKey.NPV10_CONVERTER))
    }

    @Test
    fun `pro mode includes converter and video color sections in order`() {
        RecordDevelopModeFlag.set(false)
        val proSections = SettingsSectionResolver.resolve(AppMode.PRO)

        val expected = listOf(
            SettingsSectionKey.VIDEO,
            SettingsSectionKey.RAW_CONVERTER,
            SettingsSectionKey.CAMERA,
            SettingsSectionKey.VIDEO_COLOR,
            SettingsSectionKey.FOCUS,
            SettingsSectionKey.SYSTEM
        )

        assertEquals(expected, proSections)
        assertFalse(proSections.any { it.name == "MONITORING" })
        assertFalse(proSections.contains(SettingsSectionKey.NPV10_CONVERTER))
    }

    @Test
    fun `record-develop cards are hidden while the develop engine is off`() {
        RecordDevelopModeFlag.set(false)
        val sections = SettingsSectionResolver.resolve(AppMode.VIDEO)

        assertFalse(sections.contains(SettingsSectionKey.RECORD_DEVELOP_SDR))
        assertFalse(sections.contains(SettingsSectionKey.RECORD_DEVELOP_NDR))
    }

    @Test
    fun `record-develop cards appear after RAW converter when the develop engine is on`() {
        RecordDevelopModeFlag.set(true)
        val sections = SettingsSectionResolver.resolve(AppMode.VIDEO)

        assertEquals(
            listOf(
                SettingsSectionKey.VIDEO,
                SettingsSectionKey.RAW_CONVERTER,
                SettingsSectionKey.RECORD_DEVELOP_SDR,
                SettingsSectionKey.RECORD_DEVELOP_NDR,
                SettingsSectionKey.CAMERA,
                SettingsSectionKey.VIDEO_COLOR,
                SettingsSectionKey.FOCUS,
                SettingsSectionKey.SYSTEM
            ),
            sections
        )
    }

    @Test
    fun `record-develop cards stay reachable when engine is off but intermediates exist`() {
        // Anti-stranding: the composer passes showRecordDevelopCards = flag OR clips-exist, so a clip
        // recorded under the engine remains developable/deletable after the flag is turned back off.
        RecordDevelopModeFlag.set(false)
        val sections = SettingsSectionResolver.resolve(AppMode.VIDEO, showRecordDevelopCards = true)

        assertTrue(sections.contains(SettingsSectionKey.RECORD_DEVELOP_SDR))
        assertTrue(sections.contains(SettingsSectionKey.RECORD_DEVELOP_NDR))
    }

    @Test
    fun `record-develop cards stay hidden when engine is off and no intermediates exist`() {
        RecordDevelopModeFlag.set(true) // prove the explicit arg wins over the flag default
        val sections = SettingsSectionResolver.resolve(AppMode.VIDEO, showRecordDevelopCards = false)

        assertFalse(sections.contains(SettingsSectionKey.RECORD_DEVELOP_SDR))
        assertFalse(sections.contains(SettingsSectionKey.RECORD_DEVELOP_NDR))
    }

    @Test
    fun `video settings keeps video color section visible in pro mode`() {
        val proSections = SettingsSectionResolver.resolveForVideoSettings(AppMode.PRO)

        assertTrue(proSections.contains(SettingsSectionKey.VIDEO_COLOR))
    }

    @Test
    fun `full video settings preserves record-develop cards when engine is on`() {
        RecordDevelopModeFlag.set(true)
        val sections = SettingsSectionResolver.resolveForVideoSettings(AppMode.PRO)

        // resolveForVideoSettings returns resolve()'s list verbatim (VIDEO_COLOR is always present, so the
        // strictOrder re-order path is not exercised here); assert only that the develop cards survive the
        // hub resolution rather than being dropped. The card ORDERING is locked by the assertEquals test
        // above ("record-develop cards appear after RAW converter ...").
        assertTrue(sections.contains(SettingsSectionKey.RECORD_DEVELOP_SDR))
        assertTrue(sections.contains(SettingsSectionKey.RECORD_DEVELOP_NDR))
    }
}
