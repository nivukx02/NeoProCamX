package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraUiStateStructuralDiffPolicyTest {
    @Test
    fun floatArrayComparatorIgnoresTinyTelemetryJitter() {
        assertFalse(
            CameraUiStateDiffPolicy.floatArrayChanged(
                floatArrayOf(0.1f, 0.2f, 0.3f),
                floatArrayOf(0.10001f, 0.19999f, 0.30002f)
            )
        )
        assertTrue(
            CameraUiStateDiffPolicy.floatArrayChanged(
                floatArrayOf(0.1f, 0.2f, 0.3f),
                floatArrayOf(0.1f, 0.2f, 0.31f)
            )
        )
    }

    @Test
    fun intArrayComparatorAvoidsRedrawingUnchangedTelemetryBins() {
        assertFalse(
            CameraUiStateDiffPolicy.intArrayChanged(
                intArrayOf(1, 4, 9),
                intArrayOf(1, 4, 9)
            )
        )
        assertTrue(
            CameraUiStateDiffPolicy.intArrayChanged(
                intArrayOf(1, 4, 9),
                intArrayOf(1, 5, 9)
            )
        )
    }

    @Test
    fun structuralComparatorsRejectOnlyActualChanges() {
        assertFalse(CameraUiStateDiffPolicy.listChanged(listOf("face", "eye"), listOf("face", "eye")))
        assertTrue(CameraUiStateDiffPolicy.listChanged(listOf("face"), listOf("face", "eye")))
        assertFalse(CameraUiStateDiffPolicy.objectChanged("NDR10+", "NDR10+"))
        assertTrue(CameraUiStateDiffPolicy.objectChanged("NDR10+", "NIVUKX"))
    }
}
