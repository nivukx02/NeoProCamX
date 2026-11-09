package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraZoomRequestPolicyTest {

    @Test
    fun resolve_clampsRequestedZoomToHardwareRange() {
        val result = CameraZoomRequestPolicy.resolve(
            requestedZoom = 12.0f,
            minZoom = 1.0f,
            maxZoom = 8.0f,
            beyondImaxActive = false
        )

        assertEquals(8.0f, result, 0.0001f)
    }

    @Test
    fun resolve_forcesOneTimesZoomWhenBeyondImaxIsActive() {
        val result = CameraZoomRequestPolicy.resolve(
            requestedZoom = 3.5f,
            minZoom = 1.0f,
            maxZoom = 8.0f,
            beyondImaxActive = true
        )

        assertEquals(1.0f, result, 0.0001f)
    }

    @Test
    fun resolve_handlesInvalidHardwareRangeByReturningRequestedZoom() {
        val result = CameraZoomRequestPolicy.resolve(
            requestedZoom = 2.0f,
            minZoom = 4.0f,
            maxZoom = 1.0f,
            beyondImaxActive = false
        )

        assertEquals(2.0f, result, 0.0001f)
    }
}
