package com.neoproc.neoproccamx.camera

import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawPreviewViewableTonemapFlagTest {

    @Before
    fun setUp() {
        RawPreviewViewableTonemapFlag.set(true)
    }

    @After
    fun tearDown() {
        RawPreviewViewableTonemapFlag.registerOnChanged(null)
        RawPreviewViewableTonemapFlag.set(true)
    }

    @Test
    fun `default is on so every RAW camera monitor is SDR viewable`() {
        assertTrue(RawPreviewViewableTonemapFlag.enabled)
    }

    @Test
    fun `set toggles and fires onChanged only on a real change`() {
        var count = 0
        RawPreviewViewableTonemapFlag.registerOnChanged { count++ }
        RawPreviewViewableTonemapFlag.set(true)
        assertEquals(0, count)
        RawPreviewViewableTonemapFlag.set(false)
        assertEquals(1, count)
        RawPreviewViewableTonemapFlag.set(false)
        assertEquals(1, count)
        RawPreviewViewableTonemapFlag.set(true)
        assertEquals(2, count)
    }

    @Test
    fun `substitutes only for the live RAW-video repeating request that would go linear`() {
        // The target case: flag ON, RAW video active, live repeating request, profile would go linear.
        assertTrue(
            RawPreviewViewableTonemapFlag.shouldSubstituteViewableTonemap(
                flagEnabled = true, rawVideoCaptureEnabled = true,
                forStillCapture = false, profileAppliesLinearTonemap = true
            )
        )
        // Never for stills, never when RAW inactive, never when flag off, never when not linear.
        assertFalse(
            RawPreviewViewableTonemapFlag.shouldSubstituteViewableTonemap(
                flagEnabled = true, rawVideoCaptureEnabled = true,
                forStillCapture = true, profileAppliesLinearTonemap = true
            )
        )
        assertFalse(
            RawPreviewViewableTonemapFlag.shouldSubstituteViewableTonemap(
                flagEnabled = true, rawVideoCaptureEnabled = false,
                forStillCapture = false, profileAppliesLinearTonemap = true
            )
        )
        assertFalse(
            RawPreviewViewableTonemapFlag.shouldSubstituteViewableTonemap(
                flagEnabled = false, rawVideoCaptureEnabled = true,
                forStillCapture = false, profileAppliesLinearTonemap = true
            )
        )
        assertFalse(
            RawPreviewViewableTonemapFlag.shouldSubstituteViewableTonemap(
                flagEnabled = true, rawVideoCaptureEnabled = true,
                forStillCapture = false, profileAppliesLinearTonemap = false
            )
        )
    }
}
