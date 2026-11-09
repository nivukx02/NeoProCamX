package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraUiRotationPolicyTest {

    @Test
    fun `locked portrait camera controls counter rotate for landscape holding`() {
        assertEquals(0f, CameraUiRotationPolicy.controlRotationDegrees(0), 0.001f)
        assertEquals(-90f, CameraUiRotationPolicy.controlRotationDegrees(90), 0.001f)
        assertEquals(180f, CameraUiRotationPolicy.controlRotationDegrees(180), 0.001f)
        assertEquals(90f, CameraUiRotationPolicy.controlRotationDegrees(270), 0.001f)
    }
}
