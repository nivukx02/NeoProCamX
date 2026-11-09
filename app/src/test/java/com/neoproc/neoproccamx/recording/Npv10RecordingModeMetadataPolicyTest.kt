package com.neoproc.neoproccamx.recording

import com.neoproc.neoproccamx.color.SdrQualityIntent
import org.junit.Assert.assertEquals
import org.junit.Test

class Npv10RecordingModeMetadataPolicyTest {
    @Test
    fun resolve_preservesNormalAndProSdrDevelopIntent() {
        assertEquals(
            "NORMAL_SDR",
            Npv10RecordingModeMetadataPolicy.resolve("OFF", false, SdrQualityIntent.NORMAL)
        )
        assertEquals(
            "PRO_SDR",
            Npv10RecordingModeMetadataPolicy.resolve("OFF", false, SdrQualityIntent.PRO)
        )
    }

    @Test
    fun resolve_preservesNdrAndLogSignalIdentity() {
        assertEquals(
            "NDR10+",
            Npv10RecordingModeMetadataPolicy.resolve("HDR10", false, SdrQualityIntent.NORMAL)
        )
        assertEquals(
            "LOG",
            Npv10RecordingModeMetadataPolicy.resolve("OFF", true, SdrQualityIntent.PRO)
        )
    }
}
