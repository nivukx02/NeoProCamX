package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract for [RawCaptureResolutionModePolicy]: default favours MAXIMUM_SENSOR, degrades smartly,
 * and honours the forced-vs-soft-block distinction chosen by the user.
 */
class RawCaptureResolutionModePolicyTest {

    private val active = CameraStreamSize(4080, 3072)
    private val maxRes = CameraStreamSize(8160, 6144)

    private val capAvailable = RawSensorMaxResCapability(
        maxResAvailable = true, maxResRawSize = maxRes, activeArrayRawSize = active,
        areaGainRatio = 4.0, diagnostics = emptyList()
    )
    private val capUnavailable = RawSensorMaxResCapability(
        maxResAvailable = false, maxResRawSize = null, activeArrayRawSize = active,
        areaGainRatio = 1.0, diagnostics = emptyList()
    )

    private val viable = RawMaxResViability(true, hardBlocked = false, reason = "ok")
    private val softBlock = RawMaxResViability(false, hardBlocked = false, reason = "hot")
    private val hardBlock = RawMaxResViability(false, hardBlocked = true, reason = "critical")

    @Test
    fun autoWithMaxResAndViableEngagesBurst() {
        val r = RawCaptureResolutionModePolicy.resolve(RawCaptureResolutionMode.AUTO, capAvailable, viable)
        assertEquals(RawCaptureTier.MAXIMUM_SENSOR, r.tier)
        assertEquals(maxRes, r.rawSize)
        assertTrue(r.engageMaxResPixelMode)
        assertTrue(r.burstCapture)
        assertFalse(r.fellBack)
    }

    @Test
    fun autoFallsBackWhenMaxResUnavailable() {
        val r = RawCaptureResolutionModePolicy.resolve(RawCaptureResolutionMode.AUTO, capUnavailable, viable)
        assertEquals(RawCaptureTier.FULL_ACTIVE_ARRAY, r.tier)
        assertEquals(active, r.rawSize)
        assertFalse(r.engageMaxResPixelMode)
        assertFalse(r.burstCapture)
        assertTrue(r.fellBack)
    }

    @Test
    fun autoFallsBackOnSoftBlock() {
        val r = RawCaptureResolutionModePolicy.resolve(RawCaptureResolutionMode.AUTO, capAvailable, softBlock)
        assertEquals(RawCaptureTier.FULL_ACTIVE_ARRAY, r.tier)
        assertTrue(r.fellBack)
    }

    @Test
    fun forcedMaxOverridesSoftBlock() {
        val r = RawCaptureResolutionModePolicy.resolve(RawCaptureResolutionMode.MAXIMUM_SENSOR, capAvailable, softBlock)
        assertEquals(RawCaptureTier.MAXIMUM_SENSOR, r.tier)
        assertTrue(r.engageMaxResPixelMode)
        assertFalse(r.fellBack)
    }

    @Test
    fun forcedMaxStillFallsBackOnHardBlock() {
        val r = RawCaptureResolutionModePolicy.resolve(RawCaptureResolutionMode.MAXIMUM_SENSOR, capAvailable, hardBlock)
        assertEquals(RawCaptureTier.FULL_ACTIVE_ARRAY, r.tier)
        assertTrue(r.fellBack)
    }

    @Test
    fun forcedMaxFallsBackWhenNotExposed() {
        val r = RawCaptureResolutionModePolicy.resolve(RawCaptureResolutionMode.MAXIMUM_SENSOR, capUnavailable, viable)
        assertEquals(RawCaptureTier.FULL_ACTIVE_ARRAY, r.tier)
        assertTrue(r.fellBack)
    }

    @Test
    fun forcedActiveNeverEngagesMaxRes() {
        val r = RawCaptureResolutionModePolicy.resolve(RawCaptureResolutionMode.FULL_ACTIVE_ARRAY, capAvailable, viable)
        assertEquals(RawCaptureTier.FULL_ACTIVE_ARRAY, r.tier)
        assertEquals(active, r.rawSize)
        assertFalse(r.engageMaxResPixelMode)
        assertFalse(r.fellBack)
    }
}
