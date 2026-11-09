package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoRecordingContractPolicyTest {

    @Test
    fun `requested fps never exceeds 60`() {
        assertEquals(60, VideoRecordingContractPolicy.sanitizeRequestedFps(120))
        assertEquals(60, VideoRecordingContractPolicy.sanitizeRequestedFps(240))
        assertEquals(60, VideoRecordingContractPolicy.sanitizeRequestedFps(61))
        assertEquals(48, VideoRecordingContractPolicy.sanitizeRequestedFps(48))
    }

    @Test
    fun `encoded sdr pro log and hdr default to 10 bit while raw uses its own raw path`() {
        assertTrue(
            VideoRecordingContractPolicy.resolveTenBitRecordingRequested(
                cinemaMode = CinemaVideoMode.fromName("NORMAL"),
                normalizedHdrMode = "OFF"
            )
        )
        assertTrue(
            VideoRecordingContractPolicy.resolveTenBitRecordingRequested(
                cinemaMode = CinemaVideoMode.PRO,
                normalizedHdrMode = "OFF"
            )
        )
        assertTrue(
            VideoRecordingContractPolicy.resolveTenBitRecordingRequested(
                cinemaMode = CinemaVideoMode.LOG,
                normalizedHdrMode = "OFF"
            )
        )
        assertTrue(
            VideoRecordingContractPolicy.resolveTenBitRecordingRequested(
                cinemaMode = CinemaVideoMode.PRO,
                normalizedHdrMode = "HDR10"
            )
        )
        assertFalse(
            VideoRecordingContractPolicy.resolveTenBitRecordingRequested(
                cinemaMode = CinemaVideoMode.RAW,
                normalizedHdrMode = "OFF"
            )
        )
    }

    @Test
    fun `raw video forces uncompressed 12 bit raw master`() {
        for (mode in RawCompressionMode.entries) {
            assertEquals(
                "RAW video must not drift into legacy or compressed aliases for ${mode.name}",
                RawCompressionMode.HIGH_COMPRESSION,
                VideoRecordingContractPolicy.resolveRawCompressionMode(mode)
            )
        }
    }

    @Test
    fun `raw video master stays 12 bit even in strict high end mode`() {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.enabled = true
        HighEndQualityPolicy.strictHighEndOnly = true
        try {
            assertEquals(12, RawCompressionMode.getBitDepth(RawCompressionMode.HIGH_COMPRESSION))
            assertEquals(
                RawCompressionMode.HIGH_COMPRESSION,
                RawCompressionMode.fromName("not_a_real_mode")
            )
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }
}
