package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DebugClipTimingOwnerPolicyTest {
    @Test
    fun `npraw session never delegates timing to inactive gpu recorder`() {
        assertEquals(
            DebugClipTimingOwner.NPRAW,
            DebugClipTimingOwnerPolicy.resolve(rawActive = true, npv10Active = false)
        )
    }

    @Test
    fun `npv10 session never delegates timing to inactive gpu recorder`() {
        assertEquals(
            DebugClipTimingOwner.NPV10,
            DebugClipTimingOwnerPolicy.resolve(rawActive = false, npv10Active = true)
        )
    }

    @Test
    fun `processed recording delegates timing to gpu recorder`() {
        assertEquals(
            DebugClipTimingOwner.GPU_ENCODER,
            DebugClipTimingOwnerPolicy.resolve(rawActive = false, npv10Active = false)
        )
    }

    @Test
    fun `npraw ownership wins impossible dual-active safety state`() {
        assertEquals(
            DebugClipTimingOwner.NPRAW,
            DebugClipTimingOwnerPolicy.resolve(rawActive = true, npv10Active = true)
        )
    }
}
