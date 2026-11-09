package com.neoproc.neoproccamx.recording

import org.junit.Assert.assertEquals
import org.junit.Test

class Npv10MasterLanePolicyTest {
    @Test fun `sdr packet master routes to sdr develop lane`() {
        assertEquals(
            RecordDevelopMasterKind.SDR,
            Npv10MasterLanePolicy.resolve(mapOf("signalFamily" to "SDR", "hdrMode" to "OFF"))
        )
    }

    @Test fun `ndr packet master routes to ndr develop lane`() {
        assertEquals(
            RecordDevelopMasterKind.NDR,
            Npv10MasterLanePolicy.resolve(mapOf("hdrEnabled" to "true", "hdrMode" to "NDR10+"))
        )
    }

    @Test fun `legacy hdr filename routes to ndr develop lane`() {
        assertEquals(
            RecordDevelopMasterKind.NDR,
            Npv10MasterLanePolicy.resolve(emptyMap(), "NPV10_legacy_ndr10_master.npv10")
        )
    }
}
