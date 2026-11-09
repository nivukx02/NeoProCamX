package com.neoproc.neoproccamx.camera

import com.neoproc.neoproccamx.ai.NLogMath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class TonemapCurveFactoryTest {

    @Test
    fun previewAndRecordingLogCurves_useSameCalibratedNLogMath() {
        val preview = TonemapCurveFactory.previewLogCurvePoints()
        val recording = TonemapCurveFactory.recordingLogCurvePoints()

        assertArrayEquals(preview, recording, 0f)
        assertEquals(
            NLogMath.GRAY_18_CODE,
            TonemapCurveFactory.interpolateY(preview, NLogMath.SCENE_GRAY_18),
            0.002f
        )
    }
}
