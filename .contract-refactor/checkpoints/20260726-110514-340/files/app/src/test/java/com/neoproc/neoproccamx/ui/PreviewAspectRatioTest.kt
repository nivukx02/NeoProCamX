package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class PreviewAspectRatioTest {

    @Test
    fun standardAspectNames_arePreserved() {
        assertEquals("1:1", PreviewAspectRatio.fromName("1:1").label)
        assertEquals("4:3", PreviewAspectRatio.fromName("4:3").label)
        assertEquals("16:9", PreviewAspectRatio.fromName("16:9").label)
        assertEquals("1:1", PreviewAspectRatio.fromLabel("1:1").label)
        assertEquals("4:3", PreviewAspectRatio.fromLabel("4:3").label)
        assertEquals("16:9", PreviewAspectRatio.fromLabel("16:9").label)
    }

    @Test
    fun removedLegacyAspectNames_stillFallbackToAuto() {
        assertEquals(PreviewAspectRatio.AUTO, PreviewAspectRatio.fromName("FULL"))
        assertEquals(PreviewAspectRatio.AUTO, PreviewAspectRatio.fromName("2.39"))
        assertEquals(PreviewAspectRatio.AUTO, PreviewAspectRatio.fromLabel("FULL"))
        assertEquals(PreviewAspectRatio.AUTO, PreviewAspectRatio.fromLabel("2.39"))
    }

    @Test
    fun autoAspectReportsConfiguredSensorRatio() {
        val previous = SensorCropEngine.fullSensorAspectRatio()
        SensorCropEngine.setFullSensorAspectRatio(4f / 3f)
        try {
            assertEquals(4f / 3f, PreviewAspectRatio.AUTO.outputRatio()!!, 0.0001f)
        } finally {
            SensorCropEngine.setFullSensorAspectRatio(previous)
        }
    }

    @Test
    fun autoAspect_resolvesSensorRatioForPortraitBounds() {
        val previous = SensorCropEngine.fullSensorAspectRatio()
        SensorCropEngine.setFullSensorAspectRatio(4f / 3f)
        try {
            val portraitDisplayRatio = PreviewAspectRatio.AUTO.resolve(1080, 2400)
            assertEquals(4f / 3f, portraitDisplayRatio!!, 0.0001f)
        } finally {
            SensorCropEngine.setFullSensorAspectRatio(previous)
        }
    }
}
