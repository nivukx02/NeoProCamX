package com.neoproc.neoproccamx.recording

import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingSessionHdrMetadataTest {

    @Test
    fun ndrSnapshotRetainsExactFiveThousandNitEncoderMetadata() {
        val owner = RecordingSessionHdrMetadata()
        val formatResult = encoderFormatResult(pqEncoding, masteringPeakNits = 5_000.0)

        val snapshot = owner.retain(formatResult, pqEncoding)

        assertSame(formatResult.masteringDisplay, snapshot.quickTimeColorConfig.masteringDisplay)
        assertEquals(
            5_000.0,
            snapshot.quickTimeColorConfig.masteringDisplay?.maxLuminanceNits ?: 0.0,
            0.0
        )
        assertEquals(QuickTimeColorAtomPolicy.TRANSFER_PQ_ST2084, snapshot.quickTimeColorConfig.transferCharacteristics)
        assertEquals(QuickTimeColorAtomPolicy.PRIMARIES_BT2020, snapshot.quickTimeColorConfig.colorPrimaries)
        assertTrue(owner.isCurrent(snapshot))
    }

    @Test
    fun nivukxSnapshotRetainsExactTenThousandNitEncoderMetadata() {
        val owner = RecordingSessionHdrMetadata()
        val formatResult = encoderFormatResult(pqEncoding, masteringPeakNits = 10_000.0)

        val snapshot = owner.retain(formatResult, pqEncoding)

        assertSame(formatResult.masteringDisplay, snapshot.quickTimeColorConfig.masteringDisplay)
        assertEquals(
            10_000.0,
            snapshot.quickTimeColorConfig.masteringDisplay?.maxLuminanceNits ?: 0.0,
            0.0
        )
        assertEquals(pqEncoding, snapshot.colorEncoding)
    }

    @Test
    fun inFlightTrackSnapshotRemainsImmutableAfterOwnerClear() {
        val owner = RecordingSessionHdrMetadata()
        val retained = owner.retain(
            encoderFormatResult(pqEncoding, masteringPeakNits = 5_000.0),
            pqEncoding
        )
        val inFlightTrackSnapshot = owner.requireSnapshotForTrack()

        owner.clear(retained)

        assertFalse(owner.isCurrent(inFlightTrackSnapshot))
        assertNull(owner.currentSnapshotOrNull())
        assertEquals(
            5_000.0,
            inFlightTrackSnapshot.quickTimeColorConfig.masteringDisplay?.maxLuminanceNits ?: 0.0,
            0.0
        )
        assertThrows(IllegalStateException::class.java) {
            owner.requireSnapshotForTrack()
        }
    }

    @Test
    fun staleTeardownCannotClearReplacementSession() {
        val owner = RecordingSessionHdrMetadata()
        val retired = owner.retain(
            encoderFormatResult(pqEncoding, masteringPeakNits = 5_000.0),
            pqEncoding
        )
        val replacement = owner.retain(
            encoderFormatResult(pqEncoding, masteringPeakNits = 10_000.0),
            pqEncoding
        )

        owner.clear(retired)

        assertSame(replacement, owner.requireSnapshotForTrack())
        assertEquals(
            10_000.0,
            replacement.quickTimeColorConfig.masteringDisplay?.maxLuminanceNits ?: 0.0,
            0.0
        )
    }

    @Test
    fun missingPqMetadataFailsClosedAndClearsPreviousOwner() {
        val owner = RecordingSessionHdrMetadata()
        owner.retain(
            encoderFormatResult(pqEncoding, masteringPeakNits = 5_000.0),
            pqEncoding
        )
        val malformed = encoderFormatResult(pqEncoding, masteringPeakNits = 10_000.0).copy(
            staticMetadataAttached = false,
            masteringDisplay = null
        )

        assertThrows(IllegalArgumentException::class.java) {
            owner.retain(malformed, pqEncoding)
        }

        assertNull(owner.currentSnapshotOrNull())
    }

    @Test
    fun sdrSessionPublishesNoHdrMasteringMetadata() {
        val owner = RecordingSessionHdrMetadata()
        val formatResult = encoderFormatResult(sdrEncoding, masteringPeakNits = null)

        val snapshot = owner.retain(formatResult, sdrEncoding)

        assertEquals(QuickTimeColorAtomPolicy.TRANSFER_BT709, snapshot.quickTimeColorConfig.transferCharacteristics)
        assertNull(snapshot.quickTimeColorConfig.masteringDisplay)
    }

    private fun encoderFormatResult(
        colorEncoding: RecordingColorEncodingConfig,
        masteringPeakNits: Double?
    ): RecordingEncoderFormatResult {
        val result = RecordingEncoderFormatPolicy.buildVideoFormat(
            RecordingEncoderFormatRequest(
                mime = "video/hevc",
                width = 3840,
                height = 2160,
                fps = 25,
                bitrate = 300_000_000,
                iFrameIntervalSeconds = 1f,
                colorEncoding = colorEncoding,
                bitrateMode = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                encoderComplexity = 10,
                operatingRate = 25,
                profile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                outputColorEncoding = colorEncoding,
                hdrSceneMetadata = HdrStaticMetadataPolicy.SceneMetadata.DEFAULT.copy(
                    masteringPeakNits = masteringPeakNits,
                    maxCllNits = masteringPeakNits,
                    maxFallNits = masteringPeakNits?.div(4.0)
                )
            )
        )
        // Host JVM Android stubs may report SDK 0 inside applyToFormat even though the explicit request
        // models API 34. Mark the fake final codec result attached only for this PQ session fixture.
        return if (colorEncoding.colorTransfer == MediaFormat.COLOR_TRANSFER_ST2084) {
            result.copy(staticMetadataAttached = true)
        } else {
            result
        }
    }

    private companion object {
        val pqEncoding = RecordingColorEncodingConfig(
            colorStandard = MediaFormat.COLOR_STANDARD_BT2020,
            colorTransfer = MediaFormat.COLOR_TRANSFER_ST2084,
            colorRange = MediaFormat.COLOR_RANGE_LIMITED,
            requiresTenBit = true,
            main10Requested = true
        )

        val sdrEncoding = RecordingColorEncodingConfig(
            colorStandard = MediaFormat.COLOR_STANDARD_BT709,
            colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
            colorRange = MediaFormat.COLOR_RANGE_LIMITED,
            requiresTenBit = false,
            main10Requested = false
        )
    }
}
