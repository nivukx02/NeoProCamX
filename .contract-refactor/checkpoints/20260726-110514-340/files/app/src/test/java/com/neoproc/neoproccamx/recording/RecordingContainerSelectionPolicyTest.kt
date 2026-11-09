package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingContainerSelectionPolicyTest {

    @Test
    fun movOnlyInvariantHoldsAcrossEveryModePermutation() {
        // s6-f01 MOV-only fail-closed: every CinemaVideoMode x raw x log x hdr permutation resolves
        // a QuickTime MOV container — MP4 is unreachable by construction.
        val hdrModes = listOf("OFF", "HLG", "HDR10", "PQ")
        CinemaVideoMode.values().forEach { mode ->
            listOf(false, true).forEach { raw ->
                listOf(false, true).forEach { log ->
                    hdrModes.forEach { hdr ->
                        val selection = RecordingContainerSelectionPolicy.resolve(
                            cinemaMode = mode,
                            rawVideoEnabled = raw,
                            logVideoEnabled = log,
                            hdrMode = hdr
                        )
                        assertEquals(RecordingContainer.MOV, selection.container)
                        assertEquals("mov", selection.fileExtension)
                        assertEquals("video/quicktime", selection.mediaStoreMime)
                    }
                }
            }
        }
    }

    @Test
    fun recordingContainerEnumStillDeclaresMp4ButResolverNeverYieldsIt() {
        assertTrue(RecordingContainer.values().any { it == RecordingContainer.MP4 })
        val everSelectsMp4 = CinemaVideoMode.values().any { mode ->
            RecordingContainerSelectionPolicy.resolve(mode, true, true, "HDR10").container == RecordingContainer.MP4 ||
                RecordingContainerSelectionPolicy.resolve(mode, false, false, "OFF").container == RecordingContainer.MP4
        }
        assertTrue("MOV-only build must never resolve the MP4 container", !everSelectsMp4)
    }

    @Test
    fun rawMode_usesOriginalQualityPreservingMovLabel() {
        val selection = RecordingContainerSelectionPolicy.resolve(
            cinemaMode = CinemaVideoMode.RAW,
            rawVideoEnabled = true,
            logVideoEnabled = false,
            hdrMode = "OFF"
        )

        assertEquals(RecordingContainer.MOV, selection.container)
        assertEquals("mov", selection.fileExtension)
        assertEquals("video/quicktime", selection.mediaStoreMime)
        assertEquals("Original Quality-Preserving MOV", selection.userVisibleLabel)
    }

    @Test
    fun hdrMode_usesQuickTimeMovWithoutRawClaim() {
        val selection = RecordingContainerSelectionPolicy.resolve(
            cinemaMode = CinemaVideoMode.PRO,
            rawVideoEnabled = false,
            logVideoEnabled = false,
            hdrMode = "HDR10"
        )

        assertEquals(RecordingContainer.MOV, selection.container)
        assertEquals("QuickTime MOV", selection.userVisibleLabel)
    }
}
