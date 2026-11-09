package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraImageTimestampBasePolicyTest {
    @Test
    fun `every supported modern ImageReader requests sensor identity`() {
        for (sdk in 33..37) {
            assertTrue(CameraImageTimestampBasePolicy.requiresSensorBase(sdk, true))
        }
    }

    @Test
    fun `legacy devices never invoke the newer producer timestamp API`() {
        for (sdk in 21..32) {
            assertFalse(CameraImageTimestampBasePolicy.requiresSensorBase(sdk, true))
        }
    }

    @Test
    fun `display and direct codec surfaces retain platform timing`() {
        for (sdk in 21..37) {
            assertFalse(CameraImageTimestampBasePolicy.requiresSensorBase(sdk, false))
        }
    }
}
