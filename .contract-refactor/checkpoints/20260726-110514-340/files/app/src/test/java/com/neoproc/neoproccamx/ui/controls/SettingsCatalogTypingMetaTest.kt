package com.neoproc.neoproccamx.ui.controls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * s8-f03 (CP-U3) — every settings option must be exhaustively typed: a stable id, a section, a type,
 * a default within its allowed set/range, and a non-empty allowed set for enums. No untyped/free
 * toggles. Also asserts no section is empty (every section exposes at least one typed option).
 */
class SettingsCatalogTypingMetaTest {

    @Test
    fun everyOptionIsFullyTypedWithDefaultInRange() {
        val all = SettingsOptionMetaCatalog.all
        assertTrue("catalog must not be empty", all.isNotEmpty())
        val seenIds = mutableSetOf<String>()
        all.forEach { m ->
            assertTrue("option id must be non-blank", m.id.isNotBlank())
            assertTrue("duplicate option id ${m.id}", seenIds.add(m.id))
            when (m.type) {
                SettingsOptionType.ENUM -> {
                    assertTrue("${m.id} ENUM needs allowed labels", m.allowedLabels.isNotEmpty())
                    assertTrue("${m.id} default '${m.defaultLabel}' not in allowed", m.allowedLabels.contains(m.defaultLabel))
                }
                SettingsOptionType.BOOLEAN -> {
                    assertEquals("${m.id} BOOLEAN labels", listOf("true", "false"), m.allowedLabels)
                    assertTrue("${m.id} default not boolean", m.allowedLabels.contains(m.defaultLabel))
                }
                SettingsOptionType.INT_RANGE -> {
                    assertNotNull("${m.id} INT_RANGE needs intRange", m.intRange)
                    val d = m.defaultLabel.toIntOrNull()
                    assertNotNull("${m.id} INT_RANGE default must be an int", d)
                    assertTrue("${m.id} default out of range", m.intRange!!.contains(d!!))
                }
            }
        }
    }

    /**
     * Card-based converter sections are clip LISTS (recorded RAW / hidden NPV10 / record-develop intermediates),
     * not typed-option panels: they are built directly by [SettingsSectionFactory] from a MediaStore/private
     * clip scan and expose per-clip action sheets, so they intentionally carry no [SettingsOptionMetaCatalog]
     * options. They are excluded from the "every section has ≥1 option" rule and, conversely, asserted to
     * carry ZERO options so the two lists can never silently drift.
     */
    private val cardBasedSections = setOf(
        SettingsSectionKey.RAW_CONVERTER,
        SettingsSectionKey.NPV10_CONVERTER,
        SettingsSectionKey.RECORD_DEVELOP_SDR,
        SettingsSectionKey.RECORD_DEVELOP_NDR
    )

    @Test
    fun everyOptionBearingSectionHasAtLeastOneTypedOption() {
        SettingsSectionKey.entries
            .filterNot { it in cardBasedSections }
            .forEach { key ->
                assertTrue(
                    "section $key must expose at least one option",
                    SettingsOptionMetaCatalog.forSection(key).isNotEmpty()
                )
            }
    }

    @Test
    fun cardBasedConverterSectionsExposeNoTypedOptions() {
        cardBasedSections.forEach { key ->
            assertTrue(
                "card-based section $key must expose no typed options (it is a clip list, not an option panel)",
                SettingsOptionMetaCatalog.forSection(key).isEmpty()
            )
        }
    }

    @Test
    fun hiddenAiEnhancementIsNotExposedAsSettingsOption() {
        assertTrue(
            "AI Enhancement is product-locked off and must not be exposed in the typed settings catalog.",
            SettingsOptionMetaCatalog.all.none { it.id == "camera.aiEnhancement" }
        )
    }
}
