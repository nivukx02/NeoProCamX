package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraMotionPolicyTest {

    @Test
    fun pressMotion_isShortAndLayoutStable() {
        val motion = CameraMotionPolicy.pressMotion()

        assertEquals(0.96f, motion.pressedScale, 0.001f)
        assertTrue(motion.durationMs in 70L..120L)
    }

    @Test
    fun panelMotion_usesSmallOffsetAndNoLongAnimation() {
        val motion = CameraMotionPolicy.panelMotion(fromTop = true)

        assertEquals(-18f, motion.offsetDp, 0.001f)
        assertTrue(motion.showDurationMs <= 180L)
        assertTrue(motion.hideDurationMs <= 180L)
    }

    @Test
    fun shutterMorphMotion_staysFastEnoughForRecordToggle() {
        val motion = CameraMotionPolicy.shutterMorphMotion()

        assertTrue(motion.durationMs in 120L..190L)
    }
}
