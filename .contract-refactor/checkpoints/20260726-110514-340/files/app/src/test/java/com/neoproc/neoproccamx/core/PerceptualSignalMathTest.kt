package com.neoproc.neoproccamx.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PerceptualSignalMathTest {

    @Test
    fun uniformLumaFrame_reportsBaseNoiseFloor() {
        val luma = ByteArray(64 * 64) { 128.toByte() }

        val sample = PerceptualSignalMath.analyzeLuma(luma, width = 64, height = 64)

        assertEquals(0f, sample.normalizedNoiseFloor, 0.0001f)
        assertEquals(0f, sample.flatRegionStdDev, 0.0001f)
        assertEquals(100f, sample.realIsoNoiseFloor, 0.0001f)
    }

    @Test
    fun noisyFlatFrame_reportsHigherNoiseAndIsoFloorThanUniformFrame() {
        val clean = ByteArray(64 * 64) { 128.toByte() }
        val noisy = ByteArray(64 * 64) { index ->
            val perturbation = ((index * 7) % 11) - 5
            (128 + perturbation).toByte()
        }

        val cleanSample = PerceptualSignalMath.analyzeLuma(clean, width = 64, height = 64)
        val noisySample = PerceptualSignalMath.analyzeLuma(noisy, width = 64, height = 64)

        assertTrue(noisySample.flatRegionStdDev > cleanSample.flatRegionStdDev)
        assertTrue(noisySample.normalizedNoiseFloor > cleanSample.normalizedNoiseFloor)
        assertTrue(noisySample.realIsoNoiseFloor > cleanSample.realIsoNoiseFloor)
    }

    @Test
    fun dynamicBlueNoiseTemporalDitherFloor_scalesWithMotionNoiseAndHdr() {
        val cleanSignal = PerceptualSignalSample(
            normalizedNoiseFloor = 0.05f,
            flatRegionStdDev = 1.8f,
            flatRegionCoverage = 0.20f,
            perceptualSharpness = 0.55f,
            laplacianEnergy = 14f,
            realIsoNoiseFloor = 140f
        )
        val noisySignal = cleanSignal.copy(
            normalizedNoiseFloor = 0.82f,
            flatRegionStdDev = 8.7f,
            flatRegionCoverage = 0.78f,
            realIsoNoiseFloor = 1480f
        )

        val baseline = PerceptualSignalMath.dynamicBlueNoiseTemporalDitherFloor(
            baseFloor = 0.006f,
            signal = cleanSignal,
            motionScore = 0.05f,
            hdrActive = false
        )
        val aggressive = PerceptualSignalMath.dynamicBlueNoiseTemporalDitherFloor(
            baseFloor = 0.006f,
            signal = noisySignal,
            motionScore = 0.55f,
            hdrActive = true
        )

        assertTrue(aggressive > baseline)
        assertTrue(aggressive <= 0.018f)
    }
}
