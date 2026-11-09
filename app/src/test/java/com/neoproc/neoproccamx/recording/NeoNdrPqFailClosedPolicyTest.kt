package com.neoproc.neoproccamx.recording

import android.media.MediaFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeoNdrPqFailClosedPolicyTest {

    @Test
    fun legacyDisableRequestCannotChangeProprietaryNdrTransport() {
        NeoNdrTruePqTransportPolicy.set(false)
        assertTrue(NeoNdrTruePqTransportPolicy.enabled)

        // Preserve the historical public property ABI without leaving a direct-write bypass.
        NeoNdrTruePqTransportPolicy.enabled = false
        assertTrue(NeoNdrTruePqTransportPolicy.enabled)
    }

    @Test
    fun everyProprietarySensorLinearProfileResolvesToPqAfterDisableRequest() {
        NeoNdrTruePqTransportPolicy.set(false)

        listOf("NDR10+", "NIVUKXVISION", "APEX_SHARP", "APEX_ZERO", "APEX_CINEMA")
            .forEach { profile ->
                val look = RecordingHdrVideoLookPolicy.resolveLookProfile(
                    mime = "video/hevc",
                    videoHdrMode = profile,
                    videoColorSpace = "Rec.2020",
                    sdkInt = 36
                )

                assertEquals(profile, MediaFormat.COLOR_TRANSFER_ST2084, look.colorEncoding.colorTransfer)
                assertTrue(profile, look.colorEncoding.requiresTenBit)
            }
    }

    @Test
    fun proprietaryPublicationMetadataRemainsPqAfterDisableRequest() {
        NeoNdrTruePqTransportPolicy.set(false)

        val metadata = RecordingMediaStoreLifecyclePolicy.resolveColorMetadata(
            "color=Rec.2020-NDR10+;hdrIntent=NDR10+;" +
                "dynamicRange=NIVUKX Vision (10-bit);bitDepth=10-bit"
        )

        assertEquals(RecordingMediaStoreColorStandard.BT2020, metadata.colorStandard)
        assertEquals(RecordingMediaStoreColorTransfer.ST2084, metadata.colorTransfer)
        assertEquals(Bt2100TransportPolicy.WORKING_SPACE_PQ, metadata.workingSpace)
    }
}
