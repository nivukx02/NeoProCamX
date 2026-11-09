package com.neoproc.neoproccamx.niv99

import org.junit.Assert.assertFalse
import org.junit.Test

class Niv99VideoPixelApplicationPolicyTest {
    @Test
    fun `processed video and pro domains cannot enter live NIV99 processing`() {
        assertFalse(Niv99VideoPixelApplicationPolicy.DEFAULT_ENABLED)
        listOf(Niv99OperatingMode.VIDEO, Niv99OperatingMode.PRO).forEach { mode ->
            listOf(
                Niv99SignalDomain.DISPLAY_RGB,
                Niv99SignalDomain.LINEAR_RGB,
                Niv99SignalDomain.P010_YUV
            ).forEach { domain ->
                assertFalse(
                    "expected $mode $domain to remain outside live NIV99 processing",
                    Niv99VideoPixelApplicationPolicy.isEligible(
                        validConfiguration(domain = domain).apply { this.mode = mode }
                    )
                )
            }
        }
    }

    @Test
    fun `raw domains and non live modes cannot enter the processed pixel path`() {
        listOf(
            Niv99SignalDomain.SENSOR_LINEAR_RAW,
            Niv99SignalDomain.SENSOR_LINEAR_DEMOSAICED
        ).forEach { domain ->
            assertFalse(Niv99VideoPixelApplicationPolicy.isEligible(validConfiguration(domain)))
        }
        Niv99OperatingMode.entries
            .filterNot { Niv99VideoPixelApplicationPolicy.supportsLiveMode(it) }
            .forEach { mode ->
                assertFalse(
                    Niv99VideoPixelApplicationPolicy.isEligible(
                        validConfiguration().apply { this.mode = mode }
                    )
                )
            }
    }

    @Test
    fun `incomplete output configuration is rejected instead of silently enabled`() {
        assertFalse(
            Niv99VideoPixelApplicationPolicy.isEligible(
                validConfiguration().apply { width = 0 }
            )
        )
        assertFalse(
            Niv99VideoPixelApplicationPolicy.isEligible(
                validConfiguration().apply { targetFps = 0 }
            )
        )
        assertFalse(
            Niv99VideoPixelApplicationPolicy.isEligible(
                validConfiguration().apply { surfaceGeneration = -1L }
            )
        )
        assertFalse(
            Niv99VideoPixelApplicationPolicy.isEligible(
                validConfiguration().apply { codecGeneration = -1L }
            )
        )
    }

    private fun validConfiguration(
        domain: Niv99SignalDomain = Niv99SignalDomain.DISPLAY_RGB
    ): Niv99LiveFrameConfiguration = Niv99LiveFrameConfiguration().apply {
        mode = Niv99OperatingMode.VIDEO
        this.domain = domain
        width = 3_840
        height = 2_160
        targetFps = 25
        colorProfileId = 0L
        surfaceGeneration = 1L
        codecGeneration = 1L
    }
}
