package com.neoproc.neoproccamx.ai

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** s7-f05 / CP95 — flag default (ON, activated 2026-07-03) + contracted drop-order constant. */
class PoseHandoffFlagTest {

    @After
    fun reset() = PoseHandoffFlag.set(true)

    @Test
    fun flagDefaultsOn() {
        assertTrue(PoseHandoffFlag.enabled)
        PoseHandoffFlag.set(false)
        assertFalse(PoseHandoffFlag.enabled)
        PoseHandoffFlag.set(true)
        assertTrue(PoseHandoffFlag.enabled)
    }

    @Test
    fun dropOrderPutsPoseAfterDepthAndSegBeforeFace() {
        val order = PoseHandoffFlag.AI_STAGE_DROP_ORDER
        assertEquals(listOf("DEPTH", "SEGMENTATION", "POSE", "FACE"), order)
        assertTrue(order.indexOf("POSE") > order.indexOf("SEGMENTATION"))
        assertTrue(order.indexOf("POSE") > order.indexOf("DEPTH"))
        assertTrue(order.indexOf("POSE") < order.indexOf("FACE"))
    }
}
