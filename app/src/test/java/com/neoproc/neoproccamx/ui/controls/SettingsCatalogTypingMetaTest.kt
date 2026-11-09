package com.neoproc.neoproccamx.ui.controls

import com.neoproc.neoproccamx.ui.PreviewAspectRatio
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
                    val range = requireNotNull(m.intRange)
                    val d = m.defaultLabel.toIntOrNull()
                    assertNotNull("${m.id} INT_RANGE default must be an int", d)
                    assertTrue("${m.id} default out of range", range.contains(requireNotNull(d)))
                    m.allowedLabels.forEach { label ->
                        val value = label.toIntOrNull()
                        assertNotNull("${m.id} discrete value must be an int", value)
                        assertTrue(
                            "${m.id} discrete value out of range",
                            range.contains(requireNotNull(value))
                        )
                    }
                }
                SettingsOptionType.TEXT -> {
                    assertTrue("${m.id} TEXT does not use enum labels", m.allowedLabels.isEmpty())
                    assertNotNull("${m.id} TEXT needs maxTextLength", m.maxTextLength)
                    val maxLength = requireNotNull(m.maxTextLength)
                    assertTrue("${m.id} TEXT max length must be positive", maxLength > 0)
                    assertTrue("${m.id} TEXT default is too long", m.defaultLabel.length <= maxLength)
                }
            }
            assertTrue("${m.id} title must not be blank", m.title.isNotBlank())
            assertTrue("${m.id} handler must not be blank", m.handlerName.isNotBlank())
            assertTrue("${m.id} dispatch deadline must be within one second", m.maxDispatchLatencyMs in 1..1_000)
        }
    }

    @Test
    fun catalogExactlyMatchesTheInteractiveSettingsSurface() {
        val expectedIds = setOf(
            "camera.previewAspect",
            "camera.photoQuality",
            "camera.lens",
            "camera.flash",
            "camera.aiMotionCompensation",
            "camera.noiseReduction",
            "video.resolution",
            "video.frameRate",
            "video.codec",
            "video.sdrConversionPipeline",
            "video.hdrMode",
            "video.bitrateMode",
            "video.manualBitrate",
            "video.rawEnabled",
            "video.cinemaMode",
            "video.rawResolution",
            "video.rawCompression",
            "videoColor.colorSpace",
            "videoColor.colorScience",
            "videoColor.detailProfile",
            "focus.eyeAutofocus",
            "system.gridMode",
            "system.framingGuides",
            "system.stabilization",
            "system.audio",
            "system.customRecordingNameEnabled",
            "system.customRecordingName"
        )

        assertEquals(expectedIds, SettingsOptionMetaCatalog.all.map { it.id }.toSet())
        assertEquals(expectedIds.size, SettingsOptionMetaCatalog.all.size)
        assertTrue(SettingsOptionMetaCatalog.all.none { it.id == "system.hapticFeedback" })
    }

    @Test
    fun frameRateMetadataExposesOnlyLaunchApprovedCadences() {
        val frameRate = SettingsOptionMetaCatalog.all.single { it.id == "video.frameRate" }

        assertEquals(listOf("25", "30"), frameRate.allowedLabels)
    }

    @Test
    fun everyPersistentSettingDeclaresItsPreferenceAndSessionOnlyControlsAreExplicit() {
        val sessionOnly = setOf("camera.lens", "camera.flash")
        SettingsOptionMetaCatalog.all.forEach { option ->
            if (option.id in sessionOnly) {
                assertTrue("${option.id} must remain session scoped", option.preferenceKeys.isEmpty())
            } else {
                assertTrue("${option.id} must declare persistence", option.preferenceKeys.isNotEmpty())
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

    @Test
    fun previewAspectAndManualBitrateMetadataMatchTheirProductionCatalogs() {
        val preview = SettingsOptionMetaCatalog.all.single { it.id == "camera.previewAspect" }
        val manualBitrate = SettingsOptionMetaCatalog.all.single { it.id == "video.manualBitrate" }

        val userVisibleAspects = listOf(
            PreviewAspectRatio.FOUR_THREE,
            PreviewAspectRatio.SIXTEEN_NINE
        )
        assertEquals("FOUR_THREE", preview.defaultLabel)
        assertEquals(userVisibleAspects.map { it.name }, preview.allowedLabels)
        assertEquals(userVisibleAspects, SettingsOptionCatalog.previewAspectChoices.map { it.value })
        assertEquals(SettingsActivationClass.IMMEDIATE_STATE, preview.activationClass)

        assertEquals(SettingsOptionCatalog.bitrateStepLabels, manualBitrate.allowedLabels)
        assertEquals("240", manualBitrate.defaultLabel)
        assertTrue(SettingsOptionCatalog.bitrateStepsMbps.contains(240))
    }
}
