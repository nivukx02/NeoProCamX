package com.neoproc.neoproccamx.recording

import android.media.MediaCodecInfo
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingRealtimeBitrateContractPolicyTest {
    @Test
    fun directGalleryPrefersCbrAndRetainsVbrOnlyAsCompatibilityFallback() {
        assertEquals(
            listOf(
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR,
                MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
            ),
            RecordingRealtimeBitrateContractPolicy.preferredDirectGalleryModes(
                sdkInt = Build.VERSION_CODES.UPSIDE_DOWN_CAKE
            )
        )
    }

    @Test
    fun strictBitrateTargetRejectsIndependentQuantizerController() {
        assertFalse(
            RecordingRealtimeBitrateContractPolicy.permitsIndependentQuantizerWindow(
                strictBitrateTarget = true
            )
        )
    }

    @Test
    fun nonStrictMasteringRouteCanRetainItsQuantizerPolicy() {
        assertTrue(
            RecordingRealtimeBitrateContractPolicy.permitsIndependentQuantizerWindow(
                strictBitrateTarget = false
            )
        )
    }
}
