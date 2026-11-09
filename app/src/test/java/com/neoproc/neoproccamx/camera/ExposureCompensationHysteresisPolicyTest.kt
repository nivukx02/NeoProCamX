package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class ExposureCompensationHysteresisPolicyTest {
    @Test
    fun `highlight protection crosses attack threshold without half step chatter`() {
        assertEquals(
            0,
            resolve(currentIndex = 0, biasStops = -0.54f)
        )
        assertEquals(
            -1,
            resolve(currentIndex = 0, biasStops = -0.56f)
        )
    }

    @Test
    fun `highlight protection release uses wider hysteresis`() {
        assertEquals(
            -1,
            resolve(currentIndex = -1, biasStops = -0.31f)
        )
        assertEquals(
            0,
            resolve(currentIndex = -1, biasStops = -0.29f)
        )
    }

    @Test
    fun `large scene change advances directly to nearest valid index`() {
        assertEquals(
            -3,
            resolve(currentIndex = 0, biasStops = -2.6f)
        )
        assertEquals(
            3,
            resolve(currentIndex = -2, biasStops = 2.6f)
        )
    }

    @Test
    fun `sensor feedback loop bounds each request while keeping faster highlight attack`() {
        assertEquals(
            2,
            ExposureCompensationHysteresisPolicy.resolveIndex(
                currentIndex = 5,
                baseEvIndex = 0,
                biasStops = -1f,
                evStepStops = 0.1f,
                evLowerIndex = -12,
                evUpperIndex = 12,
                maxDecreaseSteps = 3,
                maxIncreaseSteps = 2
            )
        )
        assertEquals(
            -3,
            ExposureCompensationHysteresisPolicy.resolveIndex(
                currentIndex = -5,
                baseEvIndex = 0,
                biasStops = 1f,
                evStepStops = 0.1f,
                evLowerIndex = -12,
                evUpperIndex = 12,
                maxDecreaseSteps = 3,
                maxIncreaseSteps = 2
            )
        )
    }

    @Test
    fun `first request rounds normally and clamps to camera range`() {
        assertEquals(
            -4,
            ExposureCompensationHysteresisPolicy.resolveIndex(
                currentIndex = null,
                baseEvIndex = 0,
                biasStops = -20f,
                evStepStops = 1f,
                evLowerIndex = -4,
                evUpperIndex = 4
            )
        )
        assertEquals(
            4,
            ExposureCompensationHysteresisPolicy.resolveIndex(
                currentIndex = null,
                baseEvIndex = 0,
                biasStops = 20f,
                evStepStops = 1f,
                evLowerIndex = 4,
                evUpperIndex = -4
            )
        )
    }

    @Test
    fun `invalid meter input safely returns clamped user ev`() {
        assertEquals(
            4,
            ExposureCompensationHysteresisPolicy.resolveIndex(
                currentIndex = -2,
                baseEvIndex = 9,
                biasStops = Float.NaN,
                evStepStops = 0f,
                evLowerIndex = -4,
                evUpperIndex = 4
            )
        )
    }

    private fun resolve(currentIndex: Int?, biasStops: Float): Int =
        ExposureCompensationHysteresisPolicy.resolveIndex(
            currentIndex = currentIndex,
            baseEvIndex = 0,
            biasStops = biasStops,
            evStepStops = 1f,
            evLowerIndex = -4,
            evUpperIndex = 4
        )
}
