package com.neoproc.neoproccamx.recording

import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickTimeColorAtomPolicyTest {

    @Test
    fun rec709Sdr_mapsToNonHdrNclxTriplet() {
        val config = QuickTimeColorAtomPolicy.fromRecordingColorEncoding(
            RecordingColorEncodingConfig(
                colorStandard = MediaFormat.COLOR_STANDARD_BT709,
                colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                colorRange = MediaFormat.COLOR_RANGE_LIMITED,
                requiresTenBit = false,
                main10Requested = false
            )
        )

        assertEquals(QuickTimeColorAtomPolicy.PRIMARIES_BT709, config.colorPrimaries)
        assertEquals(QuickTimeColorAtomPolicy.TRANSFER_BT709, config.transferCharacteristics)
        assertEquals(QuickTimeColorAtomPolicy.MATRIX_BT709, config.matrixCoefficients)
        assertFalse(config.fullRange)
    }

    @Test
    fun logWideGamut_mapsToBt2020SdrNotHdr() {
        val config = QuickTimeColorAtomPolicy.fromRecordingColorEncoding(
            RecordingColorEncodingConfig(
                colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
                colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                colorRange = MediaFormat.COLOR_RANGE_FULL,
                requiresTenBit = true,
                main10Requested = true
            )
        )

        assertEquals(QuickTimeColorAtomPolicy.PRIMARIES_BT2020, config.colorPrimaries)
        assertEquals(QuickTimeColorAtomPolicy.TRANSFER_BT709, config.transferCharacteristics)
        assertEquals(QuickTimeColorAtomPolicy.MATRIX_BT2020_NCL, config.matrixCoefficients)
        assertFalse(config.transferCharacteristics == QuickTimeColorAtomPolicy.TRANSFER_PQ_ST2084)
        assertFalse(config.transferCharacteristics == QuickTimeColorAtomPolicy.TRANSFER_HLG)
        assertTrue(config.fullRange)
    }

    @Test
    fun hdr10_mapsToBt2020PqNclxTriplet() {
        val config = QuickTimeColorAtomPolicy.fromRecordingColorEncoding(
            RecordingColorEncodingConfig(
                colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
                colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084,
                colorRange = MediaFormat.COLOR_RANGE_LIMITED,
                requiresTenBit = true,
                main10Requested = true
            )
        )

        assertEquals(QuickTimeColorAtomPolicy.PRIMARIES_BT2020, config.colorPrimaries)
        assertEquals(QuickTimeColorAtomPolicy.TRANSFER_PQ_ST2084, config.transferCharacteristics)
        assertEquals(QuickTimeColorAtomPolicy.MATRIX_BT2020_NCL, config.matrixCoefficients)
        assertFalse(config.fullRange)
    }

    @Test
    fun liveNdrMovTrackUsesTheExactEncoderSessionMasteringMetadata() {
        val mastering = HdrStaticMetadataPolicy.MasteringDisplay.BT2020_D65_5000_NITS
        val config = QuickTimeColorAtomPolicy.fromRecordingColorEncoding(
            colorEncoding = RecordingColorEncodingConfig(
                colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
                colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084,
                colorRange = MediaFormat.COLOR_RANGE_LIMITED,
                requiresTenBit = true,
                main10Requested = true
            ),
            sessionMasteringDisplay = mastering
        )

        assertEquals(mastering, config.masteringDisplay)
        assertEquals(5000.0, config.masteringDisplay?.maxLuminanceNits ?: 0.0, 0.0)
        assertEquals(5000, config.masteringDisplay?.maxContentLightLevelNits)
    }

    @Test(expected = IllegalArgumentException::class)
    fun livePqMovTrackFailsClosedWithoutEncoderSessionMasteringMetadata() {
        QuickTimeColorAtomPolicy.fromRecordingColorEncoding(
            colorEncoding = RecordingColorEncodingConfig(
                colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
                colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084,
                colorRange = MediaFormat.COLOR_RANGE_LIMITED,
                requiresTenBit = true,
                main10Requested = true
            ),
            sessionMasteringDisplay = null
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun livePqMovTrackFailsClosedForNonBt2020Primaries() {
        QuickTimeColorAtomPolicy.fromRecordingColorEncoding(
            colorEncoding = RecordingColorEncodingConfig(
                colorStandard = MediaFormat.COLOR_STANDARD_BT709,
                colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084,
                colorRange = MediaFormat.COLOR_RANGE_LIMITED,
                requiresTenBit = true,
                main10Requested = true
            ),
            sessionMasteringDisplay = HdrStaticMetadataPolicy.MasteringDisplay.BT2020_D65_5000_NITS
        )
    }

    @Test
    fun hlg_mapsToBt2020HlgNclxTriplet() {
        val config = QuickTimeColorAtomPolicy.fromRecordingColorEncoding(
            RecordingColorEncodingConfig(
                colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
                colorTransfer = MediaFormat.COLOR_TRANSFER_HLG,
                colorRange = MediaFormat.COLOR_RANGE_LIMITED,
                requiresTenBit = true,
                main10Requested = true
            )
        )

        assertEquals(QuickTimeColorAtomPolicy.PRIMARIES_BT2020, config.colorPrimaries)
        assertEquals(QuickTimeColorAtomPolicy.TRANSFER_HLG, config.transferCharacteristics)
        assertEquals(QuickTimeColorAtomPolicy.MATRIX_BT2020_NCL, config.matrixCoefficients)
        assertFalse(config.fullRange)
    }

    @Test
    fun displayP3Sdr_usesDisplayP3NclxTriplet() {
        val config = QuickTimeColorAtomConfig.displayP3SdrLimited()

        assertEquals(QuickTimeColorAtomPolicy.PRIMARIES_DISPLAY_P3, config.colorPrimaries)
        assertEquals(QuickTimeColorAtomPolicy.TRANSFER_SRGB, config.transferCharacteristics)
        assertEquals(QuickTimeColorAtomPolicy.MATRIX_BT709, config.matrixCoefficients)
        assertFalse(config.fullRange)
    }
}
