package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ManualVideoColorSelectionPolicyTest {

    @Test
    fun `legacy non-default saved pair can recover missing provenance`() {
        assertTrue(
            ManualVideoColorSelectionPolicy.shouldMigrateSavedPreferencePair(
                hasStoredColorSpace = true,
                hasStoredColorScience = true,
                colorSpace = "Rec.2020-NDR10+",
                colorScience = "NDR10+",
                colorSpaceAlreadyApproved = false,
                colorScienceAlreadyApproved = false
            )
        )
    }

    @Test
    fun `untouched factory defaults are never silently approved`() {
        assertFalse(
            ManualVideoColorSelectionPolicy.shouldMigrateSavedPreferencePair(
                hasStoredColorSpace = true,
                hasStoredColorScience = true,
                colorSpace = "Rec.709",
                colorScience = "Natural",
                colorSpaceAlreadyApproved = false,
                colorScienceAlreadyApproved = false
            )
        )
    }

    @Test
    fun rejectsRecordingWhenColorSpaceWasNotManuallySelected() {
        val plan = ManualVideoColorSelectionPolicy.resolve(
            ManualVideoColorSelectionRequest(
                colorSpace = "Rec.2020",
                colorScience = "N-Log Cinema",
                colorSpaceSelectedByUser = false,
                colorScienceSelectedByUser = true
            )
        )

        assertFalse(plan.readyForRecording)
        assertTrue(plan.failureReasons.any { it.contains("color space", ignoreCase = true) })
    }

    @Test
    fun rejectsRecordingWhenColorScienceWasNotManuallySelected() {
        val plan = ManualVideoColorSelectionPolicy.resolve(
            ManualVideoColorSelectionRequest(
                colorSpace = "Rec.2020",
                colorScience = "N-Log Cinema",
                colorSpaceSelectedByUser = true,
                colorScienceSelectedByUser = false
            )
        )

        assertFalse(plan.readyForRecording)
        assertTrue(plan.failureReasons.any { it.contains("color science", ignoreCase = true) })
    }

    @Test
    fun preservesManualNeoColorSpaceAndColorScienceInMetadata() {
        val plan = ManualVideoColorSelectionPolicy.resolve(
            ManualVideoColorSelectionRequest(
                colorSpace = "Neo Wide Gamut",
                colorScience = "N-Log Highlight",
                colorSpaceSelectedByUser = true,
                colorScienceSelectedByUser = true
            )
        )
        val metadata = plan.toMetadata()

        assertTrue(plan.readyForRecording)
        assertEquals("Neo Wide Gamut", plan.selectedColorSpace)
        assertEquals("N-Log Highlight", plan.selectedColorScience)
        assertEquals("USER_MANUAL", metadata.getValue("colorSelectionSource"))
        assertEquals("true", metadata.getValue("manualColorSelectionRequired"))
        assertEquals("Neo Wide Gamut", metadata.getValue("selectedColorSpace"))
        assertEquals("N-Log Highlight", metadata.getValue("selectedColorScience"))
    }

    @Test
    fun allowsSavedPreviousUserPreferenceWithoutSilentlyDefaulting() {
        val plan = ManualVideoColorSelectionPolicy.resolve(
            ManualVideoColorSelectionRequest(
                colorSpace = "Rec.2020-NDR10+",
                colorScience = "NDR10+",
                colorSpaceSelectedByUser = false,
                colorScienceSelectedByUser = false,
                savedColorSpacePreference = true,
                savedColorSciencePreference = true
            )
        )
        val metadata = plan.toMetadata()

        assertTrue(plan.readyForRecording)
        assertEquals("SAVED_USER_PREFERENCE", plan.colorSelectionSource)
        assertEquals("Rec.2020-NDR10+", metadata.getValue("selectedColorSpace"))
        assertEquals("NDR10+", metadata.getValue("selectedColorScience"))
        assertEquals("true", metadata.getValue("manualColorSelectionReady"))
    }

    @Test
    fun rejectsDefaultValuesWhenNeitherExplicitNorSavedPreferenceExists() {
        val plan = ManualVideoColorSelectionPolicy.resolve(
            ManualVideoColorSelectionRequest(
                colorSpace = "Rec.709",
                colorScience = "Natural",
                colorSpaceSelectedByUser = false,
                colorScienceSelectedByUser = false,
                savedColorSpacePreference = false,
                savedColorSciencePreference = false
            )
        )

        assertFalse(plan.readyForRecording)
        assertEquals("UNSELECTED", plan.colorSelectionSource)
        assertTrue(plan.failureReasons.any { it.contains("color space", ignoreCase = true) })
        assertTrue(plan.failureReasons.any { it.contains("color science", ignoreCase = true) })
    }

    @Test
    fun normalizesExternalWideGamutColorSpacesToNeoWideGamutForRecordingMetadata() {
        assertEquals("Neo Wide Gamut", ManualVideoColorSelectionPolicy.normalizeColorSpace("Display P3"))
        assertEquals("Neo Wide Gamut", ManualVideoColorSelectionPolicy.normalizeColorSpace("External Wide Gamut"))
        assertEquals(
            "Neo Wide Gamut",
            ManualVideoColorSelectionPolicy.normalizeColorSpace("External Cinema Gamut")
        )
        assertEquals(
            "Neo Wide Gamut",
            ManualVideoColorSelectionPolicy.normalizeColorSpace("Wide Gamut RGB")
        )
    }

    @Test
    fun normalizesLegacySdrModeLabelsToRec709ManualSelection() {
        assertEquals("Rec.709", ManualVideoColorSelectionPolicy.normalizeColorSpace("SDR"))
        assertEquals("Rec.709", ManualVideoColorSelectionPolicy.normalizeColorSpace("Normal SDR"))
        assertEquals("Rec.709", ManualVideoColorSelectionPolicy.normalizeColorSpace("Pro SDR"))
        assertEquals("Rec.709 Standard", ManualVideoColorSelectionPolicy.normalizeColorScience("SDR"))
    }

    @Test
    fun normalizesLegacyLogColorSpaceLabelsToProjectNLogSelection() {
        assertEquals("Rec.2020-N-Log", ManualVideoColorSelectionPolicy.normalizeColorSpace("Rec.2020-LOG"))
        assertEquals("Rec.2020-N-Log", ManualVideoColorSelectionPolicy.normalizeColorSpace("LOG"))
    }

    @Test
    fun normalizesProjectRawLabelsToRawSensorSelection() {
        assertEquals("RAW", ManualVideoColorSelectionPolicy.normalizeColorSpace("N-RAW"))
        assertEquals("RAW Sensor Original", ManualVideoColorSelectionPolicy.normalizeColorScience("N-RAW"))
    }

    @Test
    fun rejectsUnsupportedSavedColorSpaceInsteadOfFallingBackToRec709() {
        val plan = ManualVideoColorSelectionPolicy.resolve(
            ManualVideoColorSelectionRequest(
                colorSpace = "Experimental Vendor XYZ",
                colorScience = "Natural",
                colorSpaceSelectedByUser = false,
                colorScienceSelectedByUser = false,
                savedColorSpacePreference = true,
                savedColorSciencePreference = true
            )
        )

        assertFalse(plan.readyForRecording)
        assertEquals("UNSELECTED", plan.colorSelectionSource)
        assertTrue(plan.failureReasons.any { it.contains("not a supported", ignoreCase = true) })
        assertEquals("Experimental Vendor XYZ", plan.selectedColorSpace)
    }

    @Test
    fun normalizesAcesAliasesToProjectOwnedNeoAcesSelections() {
        assertEquals("Neo ACES AP1", ManualVideoColorSelectionPolicy.normalizeColorSpace("ACES"))
        assertEquals("Neo ACES AP1", ManualVideoColorSelectionPolicy.normalizeColorSpace("ACEScg"))
        assertEquals("Neo ACES AP0", ManualVideoColorSelectionPolicy.normalizeColorSpace("ACES AP0"))
        assertEquals("Neo ACES High End", ManualVideoColorSelectionPolicy.normalizeColorScience("ACEScg"))
        assertEquals("Neo ACES High End", ManualVideoColorSelectionPolicy.normalizeColorScience("Neo ACES High End"))
    }

    @Test
    fun canonicalManualColorMetadataPreservesAcesAp0Ap1NeoWideAndNeoHdrIdentity() {
        val ap0 = ManualVideoColorSelectionPolicy.resolve(
            ManualVideoColorSelectionRequest(
                colorSpace = "ACES AP0",
                colorScience = "Neo ACES High End",
                colorSpaceSelectedByUser = true,
                colorScienceSelectedByUser = true
            )
        ).toMetadata()
        val ap1 = ManualVideoColorSelectionPolicy.resolve(
            ManualVideoColorSelectionRequest(
                colorSpace = "ACEScg",
                colorScience = "ACES AP1",
                colorSpaceSelectedByUser = true,
                colorScienceSelectedByUser = true
            )
        ).toMetadata()
        val neoWide = ManualVideoColorSelectionPolicy.resolve(
            ManualVideoColorSelectionRequest(
                colorSpace = "Display P3",
                colorScience = "Natural",
                colorSpaceSelectedByUser = true,
                colorScienceSelectedByUser = true
            )
        ).toMetadata()

        assertEquals("Neo ACES AP0", ap0.getValue("selectedColorSpace"))
        assertEquals("ACES AP0", ap0.getValue("selectedColorPrimaries"))
        assertEquals("ACES2065-1", ap0.getValue("selectedColorWorkingGamut"))
        assertEquals("D60", ap0.getValue("selectedColorWhitePoint"))
        assertEquals("BT.2020", ap0.getValue("selectedContainerPrimaries"))
        assertEquals("Neo ACES AP1", ap1.getValue("selectedColorSpace"))
        assertEquals("ACES AP1", ap1.getValue("selectedColorPrimaries"))
        assertEquals("ACEScg", ap1.getValue("selectedColorWorkingGamut"))
        assertEquals("Neo Wide Gamut", neoWide.getValue("selectedColorSpace"))
        assertEquals("Neo WG2", neoWide.getValue("selectedColorPrimaries"))
        assertEquals("D55", neoWide.getValue("selectedColorWhitePoint"))
    }

    @Test
    fun ndrAndNivukxCanBeUsedAsManualColorSpaceAliasesWithoutVendorSubstitution() {
        assertEquals("Rec.2020-NDR10+", ManualVideoColorSelectionPolicy.normalizeColorSpace("NDR10+"))
        assertEquals("Rec.2020-NIVUKX", ManualVideoColorSelectionPolicy.normalizeColorSpace("NIVUKX Vision"))

        val ndr = ManualVideoColorSelectionPolicy.resolveColorProfile("NDR10+", "NDR10+")
        val nivukx = ManualVideoColorSelectionPolicy.resolveColorProfile("NIVUKX Vision", "NIVUKX Vision")

        assertEquals("NDR10+", ndr.neoProfile)
        assertEquals("NIVUKX Vision", nivukx.neoProfile)
        assertFalse(ndr.vendorManaged)
        assertFalse(nivukx.vendorManaged)
        assertEquals("BT.2100_PQ_MAIN10", ndr.recommendedTransport)
        assertEquals("BT.2100_PQ_MAIN10", nivukx.recommendedTransport)
    }

    @Test
    fun normalizesApexAliasesToProjectOwnedNivukxApexSelections() {
        assertEquals(
            "Rec.2020-NIVUKX-APEX",
            ManualVideoColorSelectionPolicy.normalizeColorSpace("Rec.2020 APEX")
        )
        assertEquals(
            "NIVUKX APEX SHARP",
            ManualVideoColorSelectionPolicy.normalizeColorScience("APEX Sharp")
        )
        assertEquals(
            "NIVUKX APEX ZERO",
            ManualVideoColorSelectionPolicy.normalizeColorScience("NIVUKX APEX Zero")
        )
        assertEquals(
            "NIVUKX APEX CINEMA",
            ManualVideoColorSelectionPolicy.normalizeColorScience("APEX Cinema")
        )
    }

    @Test
    fun lq017ColorScienceAliasesResolveToCanonicalRuntimeIdentities() {
        assertEquals(
            "Rec.709 Standard",
            ManualVideoColorSelectionPolicy.normalizeColorScience("Rec.709")
        )
        assertEquals(
            "NeoGamut APEX",
            ManualVideoColorSelectionPolicy.normalizeColorScience("NeoGamut APEX")
        )
        assertEquals(
            "NDR10+",
            ManualVideoColorSelectionPolicy.normalizeColorScience("NDR10+ Native")
        )
        assertEquals(
            "NIVUKX Vision",
            ManualVideoColorSelectionPolicy.normalizeColorScience("NIVUKX Vision")
        )
    }
}
