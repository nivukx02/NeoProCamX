package com.neoproc.neoproccamx.recording

import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingCodecInputColorPolicyTest {

    @Test
    fun `hevc main10 sdr keeps truthful sdr codec input colors`() {
        val output = RecordingColorEncodingConfig(
            colorStandard = MediaFormat.COLOR_STANDARD_BT709,
            colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
            colorRange = MediaFormat.COLOR_RANGE_LIMITED,
            requiresTenBit = false,
            main10Requested = true
        )

        val codecInput = RecordingCodecInputColorPolicy.resolve(
            mime = "video/hevc",
            outputColorEncoding = output
        )

        assertEquals(MediaFormat.COLOR_STANDARD_BT709, codecInput.colorStandard)
        assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, codecInput.colorTransfer)
        assertEquals(output.colorRange, codecInput.colorRange)
        assertEquals(output.requiresTenBit, codecInput.requiresTenBit)
        assertEquals(output.main10Requested, codecInput.main10Requested)
    }

    @Test
    fun `hdr output keeps its original codec input colors`() {
        val output = RecordingColorEncodingConfig(
            colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
            colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084,
            colorRange = MediaFormat.COLOR_RANGE_LIMITED,
            requiresTenBit = true,
            main10Requested = true
        )

        val codecInput = RecordingCodecInputColorPolicy.resolve(
            mime = "video/hevc",
            outputColorEncoding = output
        )

        assertEquals(output, codecInput)
    }

    @Test
    fun `non main10 sdr keeps original codec input colors`() {
        val output = RecordingColorEncodingConfig(
            colorStandard = MediaFormat.COLOR_STANDARD_BT709,
            colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
            colorRange = MediaFormat.COLOR_RANGE_LIMITED,
            requiresTenBit = false,
            main10Requested = false
        )

        val codecInput = RecordingCodecInputColorPolicy.resolve(
            mime = "video/hevc",
            outputColorEncoding = output
        )

        assertEquals(output, codecInput)
    }
}
