package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraModeRailMotionPolicyTest {

    @Test
    fun selectedItemMotion_hasFastCameraUiScaleAndFade() {
        val motion = CameraModeRailMotionPolicy.selectedItemMotion()

        assertTrue(motion.durationMs in 140L..220L)
        assertEquals(0.92f, motion.startScale, 0.001f)
        assertEquals(1.08f, motion.endScale, 0.001f)
        assertTrue(motion.startAlpha < motion.endAlpha)
    }

    @Test
    fun transitionMotion_resolvesDirectionFromModeOrder() {
        val motion = CameraModeRailMotionPolicy.transitionMotion(
            previous = CameraModePreset.VIDEO,
            next = CameraModePreset.LOG_VIDEO,
            order = listOf(
                CameraModePreset.VIDEO,
                CameraModePreset.PRO,
                CameraModePreset.HDR,
                CameraModePreset.LOG_VIDEO,
                CameraModePreset.RAW_MODE
            )
        )

        assertEquals(1, motion.direction)
        assertTrue(motion.contentOffsetDp >= 28f)
        assertTrue(motion.startAlpha <= 0.45f)
        assertTrue(motion.durationMs in 200L..280L)
    }

    @Test
    fun transitionMotion_treatsRawToVideoAsForwardWrap() {
        val motion = CameraModeRailMotionPolicy.transitionMotion(
            previous = CameraModePreset.RAW_MODE,
            next = CameraModePreset.VIDEO,
            order = CameraModePresetOrderPolicy.videoRailOrder()
        )

        assertEquals(1, motion.direction)
    }

    @Test
    fun transitionMotion_treatsVideoToRawAsReverseWrap() {
        val motion = CameraModeRailMotionPolicy.transitionMotion(
            previous = CameraModePreset.VIDEO,
            next = CameraModePreset.RAW_MODE,
            order = CameraModePresetOrderPolicy.videoRailOrder()
        )

        assertEquals(-1, motion.direction)
    }
}
