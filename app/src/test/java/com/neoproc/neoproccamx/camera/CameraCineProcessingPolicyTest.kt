package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CaptureRequest
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraCineProcessingPolicyTest {

    @Test
    fun requestsHighQualityProcessingForCineVideoModes() {
        val resolved = CameraCineProcessingPolicy.resolve(
            cinemaFidelityRequest = true,
            rawVideoCaptureEnabled = false,
            noiseReductionEnabled = true
        )

        requireNotNull(resolved)
        assertEquals(CaptureRequest.EDGE_MODE_HIGH_QUALITY, resolved.edgeMode)
        assertEquals(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY, resolved.noiseReductionMode)
        assertEquals(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY, resolved.aberrationMode)
    }

    @Test
    fun consumerAutoVideoRequestsDeviceIspCleanup() {
        val resolved = CameraCineProcessingPolicy.resolve(
            cinemaFidelityRequest = false,
            rawVideoCaptureEnabled = false,
            noiseReductionEnabled = true,
            isConsumerMode = true
        )

        requireNotNull(resolved)
        assertEquals(CaptureRequest.EDGE_MODE_HIGH_QUALITY, resolved.edgeMode)
        assertEquals(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY, resolved.noiseReductionMode)
        assertEquals(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY, resolved.aberrationMode)
    }

    @Test
    fun keepsRawVideoTruthful() {
        val resolved = CameraCineProcessingPolicy.resolve(
            cinemaFidelityRequest = true,
            rawVideoCaptureEnabled = true,
            noiseReductionEnabled = true
        )

        requireNotNull(resolved)
        assertEquals(CaptureRequest.EDGE_MODE_OFF, resolved.edgeMode)
        assertEquals(CaptureRequest.NOISE_REDUCTION_MODE_OFF, resolved.noiseReductionMode)
        assertEquals(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF, resolved.aberrationMode)
    }

    @Test
    fun skipsHighQualityIspCorrectionsForEveryRawSensorRequest() {
        assertFalse(
            CameraRequestIspCorrectionPolicy.shouldApplyHighQualityCorrections(
                cinemaFidelityRequest = true,
                rawVideoCaptureEnabled = true
            )
        )
        assertTrue(
            CameraRequestIspCorrectionPolicy.shouldApplyHighQualityCorrections(
                cinemaFidelityRequest = true,
                rawVideoCaptureEnabled = false
            )
        )
        assertFalse(
            CameraRequestIspCorrectionPolicy.shouldApplyHighQualityCorrections(
                cinemaFidelityRequest = false,
                rawVideoCaptureEnabled = true
            )
        )
    }

    @Test
    fun returnsNullWhenCineOverridesDisabled() {
        val resolved = CameraCineProcessingPolicy.resolve(
            cinemaFidelityRequest = false,
            rawVideoCaptureEnabled = false,
            noiseReductionEnabled = true
        )

        assertNull(resolved)
    }

    @Test
    fun baseProcessedVideoModeKeepsHighQualityEdgeAndNoiseReduction() {
        val resolved = CameraBaseProcessingPolicy.resolve(
            rawVideoCaptureEnabled = false,
            noiseReductionEnabled = true
        )

        assertEquals(CaptureRequest.EDGE_MODE_HIGH_QUALITY, resolved.edgeMode)
        assertEquals(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY, resolved.noiseReductionMode)
    }

    @Test
    fun baseProcessedVideoWithNoiseDisabledKeepsEdgeButDisablesNoiseReductionWhenStrictModeIsOff() {
        withHighEnd(enabled = false, strict = false) {
            val resolved = CameraBaseProcessingPolicy.resolve(
                rawVideoCaptureEnabled = false,
                noiseReductionEnabled = false
            )

            assertEquals(CaptureRequest.EDGE_MODE_HIGH_QUALITY, resolved.edgeMode)
            assertEquals(CaptureRequest.NOISE_REDUCTION_MODE_OFF, resolved.noiseReductionMode)
        }
    }

    @Test
    fun strictHighEndHonorsExplicitNoiseReductionOffWithoutReducingOtherCorrections() {
        withHighEnd(enabled = true, strict = true) {
            val base = CameraBaseProcessingPolicy.resolve(
                rawVideoCaptureEnabled = false,
                noiseReductionEnabled = false
            )
            val cine = CameraCineProcessingPolicy.resolve(
                cinemaFidelityRequest = true,
                rawVideoCaptureEnabled = false,
                noiseReductionEnabled = false
            )

            assertEquals(CaptureRequest.NOISE_REDUCTION_MODE_OFF, base.noiseReductionMode)
            requireNotNull(cine)
            assertEquals(CaptureRequest.EDGE_MODE_HIGH_QUALITY, cine.edgeMode)
            assertEquals(CaptureRequest.NOISE_REDUCTION_MODE_OFF, cine.noiseReductionMode)
            assertEquals(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY, cine.aberrationMode)
        }
    }

    @Test
    fun consumerModeHonorsExplicitNoiseReductionOff() {
        val resolved = CameraCineProcessingPolicy.resolve(
            cinemaFidelityRequest = false,
            rawVideoCaptureEnabled = false,
            noiseReductionEnabled = false,
            isConsumerMode = true
        )

        requireNotNull(resolved)
        assertEquals(CaptureRequest.EDGE_MODE_HIGH_QUALITY, resolved.edgeMode)
        assertEquals(CaptureRequest.NOISE_REDUCTION_MODE_OFF, resolved.noiseReductionMode)
        assertEquals(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY, resolved.aberrationMode)
    }

    @Test
    fun baseRawVideoModeDoesNotRequestProcessedSharpeningOrNoiseReduction() {
        val resolved = CameraBaseProcessingPolicy.resolve(
            rawVideoCaptureEnabled = true,
            noiseReductionEnabled = true
        )

        assertEquals(CaptureRequest.EDGE_MODE_OFF, resolved.edgeMode)
        assertEquals(CaptureRequest.NOISE_REDUCTION_MODE_OFF, resolved.noiseReductionMode)
    }

    private inline fun withHighEnd(
        enabled: Boolean,
        strict: Boolean,
        block: () -> Unit
    ) {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.enabled = enabled
        HighEndQualityPolicy.strictHighEndOnly = strict
        try {
            block()
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }
}
