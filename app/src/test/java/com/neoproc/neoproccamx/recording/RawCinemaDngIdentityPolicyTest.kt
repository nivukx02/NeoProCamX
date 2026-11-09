package com.neoproc.neoproccamx.recording

import org.junit.Test

class RawCinemaDngIdentityPolicyTest {
    @Test
    fun `full sensor frame is admitted without transformation`() {
        RawCinemaDngIdentityPolicy.requirePreservedSensorFrame(
            samples = intArrayOf(0, 64, 1024, 4095),
            width = 2,
            height = 2,
            frameNumber = 7
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `partial sensor frame fails closed`() {
        RawCinemaDngIdentityPolicy.requirePreservedSensorFrame(
            samples = intArrayOf(0, 64, 1024),
            width = 2,
            height = 2,
            frameNumber = 7
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `sample that would be silently clamped fails closed`() {
        RawCinemaDngIdentityPolicy.requirePreservedSensorFrame(
            samples = intArrayOf(0, 64, 1024, 65_536),
            width = 2,
            height = 2,
            frameNumber = 7
        )
    }
}
