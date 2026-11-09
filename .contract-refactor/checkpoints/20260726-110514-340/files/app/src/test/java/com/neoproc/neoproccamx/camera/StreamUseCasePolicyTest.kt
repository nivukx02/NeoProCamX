package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CameraMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

class StreamUseCasePolicyTest {

    @Test
    fun `hardware import uses preview use case not preview video still`() {
        val useCase = StreamUseCasePolicy.preferredUseCaseForRole(
            StreamUseCasePolicy.StreamRole.HARDWARE_IMPORT
        )

        assertEquals(
            CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_PREVIEW.toLong(),
            useCase
        )
    }

    @Test
    fun `explicit preview video still role keeps combined use case`() {
        val useCase = StreamUseCasePolicy.preferredUseCaseForRole(
            StreamUseCasePolicy.StreamRole.PREVIEW_VIDEO_STILL
        )

        assertEquals(
            CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_PREVIEW_VIDEO_STILL.toLong(),
            useCase
        )
    }
}
