package com.neoproc.neoproccamx.niv99

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Niv99PreviewShaderTuningTest {
    @Test
    fun `automatic tuning is accepted only for its exact sensor timestamp`() {
        val source = approvedTuning(Niv99WhiteBalanceAuthority.NIV99_AUTO).apply {
            rgbCorrection[0] = 1.06f
            rgbCorrection[1] = 1.00f
            rgbCorrection[2] = 0.94f
            spatialDenoise = 0.42f
            chromaDenoise = 0.71f
            sharpeningCeiling = 0.58f
            confidence = 0.81f
        }
        val shader = Niv99PreviewShaderTuning()

        assertFalse(shader.update(source, SENSOR_TIMESTAMP_NS + 1L))
        assertEquals(0f, shader.active, 0f)
        assertTrue(shader.update(source, SENSOR_TIMESTAMP_NS))
        assertEquals(1f, shader.active, 0f)
        assertEquals(1.06f, shader.redCorrection, 0f)
        assertEquals(0.94f, shader.blueCorrection, 0f)
        assertEquals(0.42f, shader.spatialDenoise, 0f)
        assertEquals(0.71f, shader.chromaDenoise, 0f)
        assertEquals(0.58f, shader.sharpeningCeiling, 0f)
        assertEquals(0.81f, shader.confidence, 0f)
    }

    @Test
    fun `manual and locked white balance publish identity while retaining denoise`() {
        for (authority in listOf(
            Niv99WhiteBalanceAuthority.MANUAL,
            Niv99WhiteBalanceAuthority.AWB_LOCK
        )) {
            val source = approvedTuning(authority).apply {
                rgbCorrection[0] = 1.08f
                rgbCorrection[1] = 0.96f
                rgbCorrection[2] = 0.92f
                spatialDenoise = 0.38f
                chromaDenoise = 0.66f
                sharpeningCeiling = 0.51f
                confidence = 0.9f
            }
            val shader = Niv99PreviewShaderTuning()

            assertTrue(shader.update(source, SENSOR_TIMESTAMP_NS))
            assertEquals(1f, shader.redCorrection, 0f)
            assertEquals(1f, shader.greenCorrection, 0f)
            assertEquals(1f, shader.blueCorrection, 0f)
            assertEquals(0.38f, shader.spatialDenoise, 0f)
            assertEquals(0.66f, shader.chromaDenoise, 0f)
        }
    }

    @Test
    fun `invalid or unapproved packets clear every previous shader value`() {
        val shader = Niv99PreviewShaderTuning()
        assertTrue(shader.update(approvedTuning(Niv99WhiteBalanceAuthority.NIV99_AUTO), SENSOR_TIMESTAMP_NS))

        val unapproved = Niv99LiveFrameTuning().apply {
            identity.set(CAMERA_ID, GENERATION, FRAME_NUMBER + 1L, SENSOR_TIMESTAMP_NS + 40_000_000L)
            valid = true
            confidence = 1f
        }
        assertFalse(shader.update(unapproved, unapproved.identity.sensorTimestampNs))
        assertEquals(0f, shader.active, 0f)
        assertEquals(1f, shader.redCorrection, 0f)
        assertEquals(1f, shader.greenCorrection, 0f)
        assertEquals(1f, shader.blueCorrection, 0f)
        assertEquals(0f, shader.spatialDenoise, 0f)
        assertEquals(0f, shader.chromaDenoise, 0f)
        assertEquals(0f, shader.sharpeningCeiling, 0f)
    }

    @Test
    fun `recent packet requires matching continuity and a bounded older timestamp`() {
        val expected = continuity()
        val source = approvedTuning(Niv99WhiteBalanceAuthority.NIV99_AUTO).apply {
            previewContinuity.copyFrom(expected)
            rgbCorrection[0] = 1.04f
            confidence = 0.8f
        }
        val shader = Niv99PreviewShaderTuning()
        val targetTimestampNs = SENSOR_TIMESTAMP_NS + 40_000_000L

        assertTrue(shader.updateRecent(source, targetTimestampNs, 80_000_000L, expected))
        assertEquals(1f, shader.active, 0f)
        assertEquals(1.04f, shader.redCorrection, 0f)

        val changed = continuity(detail = Niv99DetailProfile.EXTREME)
        assertFalse(shader.updateRecent(source, targetTimestampNs, 80_000_000L, changed))
        assertEquals(0f, shader.active, 0f)
        assertFalse(shader.updateRecent(
            source,
            SENSOR_TIMESTAMP_NS + 80_000_001L,
            80_000_000L,
            expected
        ))
    }

    @Test
    fun `peak safe reference never creates clipping over bounded RGB lattice`() {
        val output = FloatArray(3)
        val corrections = arrayOf(
            floatArrayOf(0.92f, 1f, 1.08f),
            floatArrayOf(1.08f, 0.97f, 0.92f),
            floatArrayOf(1f, 1f, 1f)
        )
        val amounts = floatArrayOf(0f, 0.35f, 0.81f, 1f)
        for (ri in 0..10) for (gi in 0..10) for (bi in 0..10) {
            val red = ri / 10f
            val green = gi / 10f
            val blue = bi / 10f
            val sourcePeak = maxOf(red, green, blue)
            for (correction in corrections) for (amount in amounts) {
                assertTrue(
                    Niv99PreviewColorReference.applyPeakSafeWhiteBalance(
                        red = red,
                        green = green,
                        blue = blue,
                        redCorrection = correction[0],
                        greenCorrection = correction[1],
                        blueCorrection = correction[2],
                        amount = amount,
                        output = output
                    )
                )
                assertTrue(output[0] >= 0f && output[1] >= 0f && output[2] >= 0f)
                assertTrue(output[0] <= sourcePeak + EPSILON)
                assertTrue(output[1] <= sourcePeak + EPSILON)
                assertTrue(output[2] <= sourcePeak + EPSILON)
            }
        }
    }

    private fun approvedTuning(authority: Niv99WhiteBalanceAuthority): Niv99LiveFrameTuning =
        Niv99LiveFrameTuning().apply {
            identity.set(CAMERA_ID, GENERATION, FRAME_NUMBER, SENSOR_TIMESTAMP_NS)
            valid = true
            this.authority = authority
            confidence = 0.75f
            check(approvePixelApplication())
        }

    private fun continuity(
        detail: Niv99DetailProfile = Niv99DetailProfile.NATURAL
    ): Niv99PreviewTuningContinuity = Niv99PreviewTuningContinuity().apply {
        check(set(
            cameraId = CAMERA_ID,
            sessionGeneration = GENERATION,
            mode = Niv99OperatingMode.VIDEO,
            domain = Niv99SignalDomain.P010_YUV,
            detailProfile = detail,
            width = 3_840,
            height = 2_160,
            targetFps = 25,
            colorProfileId = 11L,
            surfaceGeneration = 7L,
            codecGeneration = GENERATION,
            manualKelvin = 0,
            manualTint = 0,
            awbLockRequested = false
        ))
    }

    private companion object {
        const val CAMERA_ID = "0"
        const val GENERATION = 17L
        const val FRAME_NUMBER = 41L
        const val SENSOR_TIMESTAMP_NS = 1_640_000_000L
        const val EPSILON = 1.0e-6f
    }
}
