package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraModePresetOrderPolicyTest {

    @Test
    fun `video rail starts with video and ends with raw video`() {
        val order = CameraModePresetOrderPolicy.videoRailOrder()

        assertEquals(CameraModePreset.VIDEO, order.first())
        assertEquals(CameraModePreset.RAW_MODE, order.last())
        assertEquals(order.distinct(), order)
    }

    @Test
    fun `default launch preset is video`() {
        assertEquals(CameraModePreset.VIDEO, CameraModePresetOrderPolicy.defaultPreset())
    }
}
