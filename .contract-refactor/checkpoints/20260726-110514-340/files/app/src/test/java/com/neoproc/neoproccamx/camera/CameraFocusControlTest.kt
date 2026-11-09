package com.neoproc.neoproccamx.camera

import android.hardware.camera2.CaptureRequest
import com.neoproc.neoproccamx.core.HighEndQualityPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraFocusControlTest {
    private inline fun <T> withStrictHighEndOnly(
        enabled: Boolean,
        block: () -> T
    ): T {
        val previousEnabled = HighEndQualityPolicy.enabled
        val previousStrict = HighEndQualityPolicy.strictHighEndOnly
        HighEndQualityPolicy.enabled = true
        HighEndQualityPolicy.strictHighEndOnly = enabled
        try {
            return block()
        } finally {
            HighEndQualityPolicy.enabled = previousEnabled
            HighEndQualityPolicy.strictHighEndOnly = previousStrict
        }
    }

    @Test
    fun `continuous AF mode follows recording state`() {
        assertEquals(
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            CameraFocusControl.continuousAutoFocusMode(isRecording = false)
        )
        assertEquals(
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
            CameraFocusControl.continuousAutoFocusMode(isRecording = true)
        )
    }

    @Test
    fun `available continuous AF mode prefers video algorithm while recording`() {
        val availableModes = intArrayOf(
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
            CaptureRequest.CONTROL_AF_MODE_AUTO
        )

        assertEquals(
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
            CameraFocusControl.selectAvailableContinuousAutoFocusMode(
                availableModes = availableModes,
                isRecording = true
            )
        )
        assertEquals(
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            CameraFocusControl.selectAvailableContinuousAutoFocusMode(
                availableModes = availableModes,
                isRecording = false
            )
        )
    }

    @Test
    fun `available continuous AF mode falls back to auto when continuous modes are missing`() {
        assertEquals(
            CaptureRequest.CONTROL_AF_MODE_AUTO,
            CameraFocusControl.selectAvailableContinuousAutoFocusMode(
                availableModes = intArrayOf(CaptureRequest.CONTROL_AF_MODE_AUTO),
                isRecording = true
            )
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // s5-f01 / CP-F2: golden lock for the explicit afMode preference table.
    // The expected values below ARE today's behavior; do not relax them.
    // ─────────────────────────────────────────────────────────────────────────

    /** Reference implementation of the OLD inline single-shot branch, to prove equivalence. */
    private fun legacySingleShot(modes: IntArray, isRecording: Boolean): Int = when {
        modes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) ->
            CaptureRequest.CONTROL_AF_MODE_AUTO
        modes.contains(CaptureRequest.CONTROL_AF_MODE_MACRO) ->
            CaptureRequest.CONTROL_AF_MODE_MACRO
        else -> CameraFocusControl.selectAvailableContinuousAutoFocusMode(modes, isRecording)
    }

    @Test
    fun `continuous preference table covers full present-absent matrix`() {
        val off = CaptureRequest.CONTROL_AF_MODE_OFF
        val video = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
        val picture = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        val auto = CaptureRequest.CONTROL_AF_MODE_AUTO

        // CONTINUOUS_VIDEO present while recording -> wins.
        assertEquals(video, CameraFocusControl.selectAvailableContinuousAutoFocusMode(
            intArrayOf(off, picture, video, auto), isRecording = true))
        // CONTINUOUS_VIDEO absent while recording -> CONTINUOUS_PICTURE.
        assertEquals(picture, CameraFocusControl.selectAvailableContinuousAutoFocusMode(
            intArrayOf(off, picture, auto), isRecording = true))
        // Both continuous absent while recording -> AUTO.
        assertEquals(auto, CameraFocusControl.selectAvailableContinuousAutoFocusMode(
            intArrayOf(off, auto), isRecording = true))
        // Still: CONTINUOUS_PICTURE preferred over CONTINUOUS_VIDEO.
        assertEquals(picture, CameraFocusControl.selectAvailableContinuousAutoFocusMode(
            intArrayOf(off, picture, video, auto), isRecording = false))
        // Still: CONTINUOUS_PICTURE absent -> CONTINUOUS_VIDEO.
        assertEquals(video, CameraFocusControl.selectAvailableContinuousAutoFocusMode(
            intArrayOf(off, video, auto), isRecording = false))
        // No usable modes -> OFF (manual-only hardware).
        assertEquals(off, CameraFocusControl.selectAvailableContinuousAutoFocusMode(
            intArrayOf(off), isRecording = true))
        assertEquals(off, CameraFocusControl.selectAvailableContinuousAutoFocusMode(
            intArrayOf(), isRecording = false))
    }

    @Test
    fun `continuous preference table is idempotent for identical inputs`() {
        val modes = intArrayOf(
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
            CaptureRequest.CONTROL_AF_MODE_AUTO
        )
        repeat(8) {
            assertEquals(
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
                CameraFocusControl.selectAvailableContinuousAutoFocusMode(modes, isRecording = true)
            )
            assertEquals(
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                CameraFocusControl.selectAvailableContinuousAutoFocusMode(modes, isRecording = false)
            )
        }
    }

    @Test
    fun `single shot selector reproduces the legacy inline branch across the matrix`() {
        val off = CaptureRequest.CONTROL_AF_MODE_OFF
        val video = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
        val picture = CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        val auto = CaptureRequest.CONTROL_AF_MODE_AUTO
        val macro = CaptureRequest.CONTROL_AF_MODE_MACRO

        val matrix = listOf(
            intArrayOf(off, auto, macro, picture, video),
            intArrayOf(off, macro, picture, video),
            intArrayOf(off, picture, video),
            intArrayOf(off, video),
            intArrayOf(off, auto),
            intArrayOf(off),
            intArrayOf(),
            intArrayOf(macro)
        )
        for (modes in matrix) {
            for (recording in listOf(true, false)) {
                assertEquals(
                    "single-shot extraction must match the legacy inline branch for modes=" +
                        modes.toList() + " recording=" + recording,
                    legacySingleShot(modes, recording),
                    CameraFocusControl.selectSingleShotAutoFocusMode(modes, recording)
                )
            }
        }
    }

    @Test
    fun `single shot selector prefers auto then macro then continuous fallback`() {
        // AUTO present -> AUTO regardless of MACRO/continuous.
        assertEquals(
            CaptureRequest.CONTROL_AF_MODE_AUTO,
            CameraFocusControl.selectSingleShotAutoFocusMode(
                intArrayOf(
                    CaptureRequest.CONTROL_AF_MODE_AUTO,
                    CaptureRequest.CONTROL_AF_MODE_MACRO,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                ),
                isRecording = true
            )
        )
        // AUTO absent, MACRO present -> MACRO.
        assertEquals(
            CaptureRequest.CONTROL_AF_MODE_MACRO,
            CameraFocusControl.selectSingleShotAutoFocusMode(
                intArrayOf(
                    CaptureRequest.CONTROL_AF_MODE_MACRO,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                ),
                isRecording = true
            )
        )
        // Neither AUTO nor MACRO -> continuous fallback (video while recording).
        assertEquals(
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO,
            CameraFocusControl.selectSingleShotAutoFocusMode(
                intArrayOf(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO),
                isRecording = true
            )
        )
        // Neither AUTO/MACRO/continuous -> OFF via the continuous fallback's OFF result.
        assertEquals(
            CaptureRequest.CONTROL_AF_MODE_OFF,
            CameraFocusControl.selectSingleShotAutoFocusMode(
                intArrayOf(CaptureRequest.CONTROL_AF_MODE_OFF),
                isRecording = false
            )
        )
    }

    @Test
    fun `single shot selector is idempotent for identical inputs`() {
        val modes = intArrayOf(
            CaptureRequest.CONTROL_AF_MODE_MACRO,
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
        )
        repeat(8) {
            assertEquals(
                CaptureRequest.CONTROL_AF_MODE_MACRO,
                CameraFocusControl.selectSingleShotAutoFocusMode(modes, isRecording = false)
            )
        }
    }

    @Test
    fun `tap metering region stays centered inside sensor bounds`() {
        val region = CameraFocusControl.buildTapMeteringRegion(
            sensorRect = CameraRect(0, 0, 4000, 3000),
            mappedSensorX = 0.5f,
            mappedSensorY = 0.5f,
            focusWindowRatio = 0.42f
        )

        assertEquals(CameraRect(1160, 870, 2840, 2130), region)
    }

    @Test
    fun `tap metering region clamps cleanly near sensor edges`() {
        val region = CameraFocusControl.buildTapMeteringRegion(
            sensorRect = CameraRect(0, 0, 4000, 3000),
            mappedSensorX = 0.02f,
            mappedSensorY = 0.03f,
            focusWindowRatio = 0.42f
        )

        assertEquals(CameraRect(0, 0, 920, 720), region)
    }

    @Test
    fun `tap metering region preserves active array sensor offsets`() {
        val region = CameraFocusControl.buildTapMeteringRegion(
            sensorRect = CameraRect(64, 48, 4064, 3048),
            mappedSensorX = 0.5f,
            mappedSensorY = 0.5f,
            focusWindowRatio = 0.12f
        )

        assertEquals(CameraRect(1824, 1368, 2304, 1728), region)
    }

    @Test
    fun `recommended focus window ratio tightens in strict high-end mode`() {
        val standardRatio = run {
            withStrictHighEndOnly(enabled = false) {
                CameraFocusControl.getRecommendedFocusWindowRatio(FocusTrackingMode.WIDE_AREA)
            }
        }

        val strictRatio = run {
            withStrictHighEndOnly(enabled = true) {
                CameraFocusControl.getRecommendedFocusWindowRatio(FocusTrackingMode.WIDE_AREA)
            }
        }

        assertTrue("Strict ratio should be tighter than standard", strictRatio < standardRatio)
        assertEquals(0.25f, standardRatio, 0.0001f)
        assertEquals(0.2125f, strictRatio, 0.0001f)
    }

    @Test
    fun `focus peaking intensity is more sensitive in strict high-end mode`() {
        val standardIntensity = run {
            withStrictHighEndOnly(enabled = false) {
                CameraFocusControl.calculateFocusPeakingIntensity(
                    laplacianVariance = 85f,
                    threshold = 100f
                )
            }
        }

        val strictIntensity = run {
            withStrictHighEndOnly(enabled = true) {
                CameraFocusControl.calculateFocusPeakingIntensity(
                    laplacianVariance = 85f,
                    threshold = 100f
                )
            }
        }

        assertTrue("Strict intensity should not be lower than standard", strictIntensity >= standardIntensity)
        assertEquals(0.85f, standardIntensity, 0.0001f)
        assertEquals(1.0f, strictIntensity, 0.0001f)
    }
}
