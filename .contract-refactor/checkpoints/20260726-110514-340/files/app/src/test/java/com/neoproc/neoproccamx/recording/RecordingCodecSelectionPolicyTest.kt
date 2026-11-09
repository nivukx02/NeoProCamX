package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingCodecSelectionPolicyTest {

    @Test
    fun rank_prefersVendorCodec2HardwareOverLegacyVendorHardware() {
        val ranked = RecordingCodecSelectionPolicy.rank(
            listOf(
                candidate(name = "c2.qti.hevc.encoder", vendor = true),
                candidate(name = "OMX.qcom.video.encoder.hevc", vendor = true)
            )
        )

        assertEquals("c2.qti.hevc.encoder", ranked.first().name)
        assertTrue(ranked.first().createByName)
    }

    @Test
    fun rank_keepsCodec2HardwareWhenItIsTheOnlyHardwareEncoder() {
        val ranked = RecordingCodecSelectionPolicy.rank(
            listOf(
                candidate(name = "c2.android.hevc.encoder", hardwareAccelerated = false, softwareOnly = true),
                candidate(name = "c2.qti.hevc.encoder", vendor = true)
            )
        )

        assertEquals("c2.qti.hevc.encoder", ranked.first().name)
        assertTrue(ranked.first().createByName)
    }

    @Test
    fun rank_rejectsSoftwareBeforeHardwareEvenWhenSoftwareIsLegacyOmx() {
        val ranked = RecordingCodecSelectionPolicy.rank(
            listOf(
                candidate(
                    name = "OMX.google.hevc.encoder",
                    hardwareAccelerated = false,
                    softwareOnly = true,
                    vendor = false
                ),
                candidate(name = "c2.qti.hevc.encoder", vendor = true)
            )
        )

        assertEquals("c2.qti.hevc.encoder", ranked.first().name)
        assertFalse(ranked.first().softwareOnly)
    }

    @Test
    fun rank_placesFormatCompatibleCandidatesFirst() {
        val ranked = RecordingCodecSelectionPolicy.rank(
            listOf(
                candidate(name = "OMX.qcom.video.encoder.hevc", vendor = true, supportsRequestedFormat = false),
                candidate(name = "c2.qti.hevc.encoder", vendor = true, supportsRequestedFormat = true)
            )
        )

        assertEquals("c2.qti.hevc.encoder", ranked.first().name)
        assertTrue(ranked.first().supportsRequestedFormat)
    }

    private fun candidate(
        name: String,
        hardwareAccelerated: Boolean = true,
        softwareOnly: Boolean = false,
        vendor: Boolean = false,
        supportsRequestedFormat: Boolean = true
    ): RecordingCodecSelectionCandidate =
        RecordingCodecSelectionCandidate(
            name = name,
            hardwareAccelerated = hardwareAccelerated,
            softwareOnly = softwareOnly,
            vendor = vendor,
            supportsRequestedFormat = supportsRequestedFormat
        )
}
