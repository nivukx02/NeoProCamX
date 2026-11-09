package com.neoproc.neoproccamx.camera

import android.util.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class CameraAnalysisReaderTargetPolicyTest {

    @Test
    fun prefersExplicitAnalysisTargetOverReadoutOverride() {
        val analysis = Size(1920, 1080)
        val readout = Size(8192, 6144)

        val resolved = CameraAnalysisReaderTargetPolicy.resolveRequestedAnalysisSize(
            analysisTargetSize = analysis,
            recordingReadoutOverrideSize = readout
        )

        assertNotNull(resolved)
        assertEquals(analysis.width, resolved!!.width)
        assertEquals(analysis.height, resolved.height)
    }

    @Test
    fun fallsBackToReadoutOverrideWhenNoAnalysisTargetExists() {
        val readout = Size(4096, 3072)

        val resolved = CameraAnalysisReaderTargetPolicy.resolveRequestedAnalysisSize(
            analysisTargetSize = null,
            recordingReadoutOverrideSize = readout
        )

        assertNotNull(resolved)
        assertEquals(readout.width, resolved!!.width)
        assertEquals(readout.height, resolved.height)
    }
}
