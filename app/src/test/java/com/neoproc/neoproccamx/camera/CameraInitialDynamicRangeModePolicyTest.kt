package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraInitialDynamicRangeModePolicyTest {

    @Test
    fun `camera opens in SDR until UI explicitly selects HDR`() {
        assertEquals(HdrDynamicRangeMode.SDR, CameraInitialDynamicRangeModePolicy.defaultMode())
    }
}
