package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraImportColorCompensationPolicyTest {

    @Test
    fun returnsNeutralCompensationForUnknownDevices() {
        val compensation = CameraImportColorCompensationPolicy.resolve("UNKNOWN")

        assertEquals(1f, compensation.redScale, 0.0001f)
        assertEquals(1f, compensation.greenScale, 0.0001f)
        assertEquals(1f, compensation.blueScale, 0.0001f)
        assertEquals(0f, compensation.blendStrength, 0.0001f)
    }

    @Test
    fun returnsNeutralCompensationForI2405ToRemoveCast() {
        val compensation = CameraImportColorCompensationPolicy.resolve("I2405")

        assertEquals(1f, compensation.redScale, 0.0001f)
        assertEquals(1f, compensation.greenScale, 0.0001f)
        assertEquals(1f, compensation.blueScale, 0.0001f)
        assertEquals(0f, compensation.blendStrength, 0.0001f)
    }
}
