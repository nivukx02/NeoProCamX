package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class StillCaptureRequestTokenTest {

    @Test
    fun `operation identity includes phase and operation epoch`() {
        val token = token(operationEpoch = 41L)

        assertNotEquals(
            token,
            token.copy(phase = StillCaptureRequestPhase.HDR_RAW_COMPANION)
        )
        assertNotEquals(token, token.copy(operationEpoch = 42L))
    }

    @Test
    fun `negative operation epoch is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            token(operationEpoch = -1L)
        }
    }

    private fun token(operationEpoch: Long) = StillCaptureRequestToken(
        captureId = 1,
        phase = StillCaptureRequestPhase.SINGLE_PRIMARY,
        frameIndex = 0,
        frameCount = 1,
        expectsJpeg = true,
        expectsRaw = false,
        operationEpoch = operationEpoch
    )
}
