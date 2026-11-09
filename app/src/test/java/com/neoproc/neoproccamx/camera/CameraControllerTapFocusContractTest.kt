package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class CameraControllerTapFocusContractTest {
    private val source: String by lazy {
        File("src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt").readText()
    }

    @Test
    fun `tap autofocus clears stale manual and ai lens focus before resolving af plan`() {
        val body = source.substringAfter("override fun triggerTapAF(nx: Float, ny: Float)")
            .substringBefore("override fun setDeviceRotationDegrees")
        val resetIndex = body.indexOf("clearLensFocusOverridesForTapAf()")
        val resolveIndex = body.indexOf("TapFocusRequestPolicy.resolve(")

        assertTrue(resetIndex >= 0)
        assertTrue(resolveIndex > resetIndex)
    }

    @Test
    fun `tap autofocus trigger frame uses single shot af mode before start trigger`() {
        val body = source.substringAfter("override fun triggerSubjectAutoFocus(")
            .substringBefore("override fun captureStill(")
        val applyIndex = body.indexOf("applyAll(builder, false)")
        val modeIndex = body.indexOf("builder[CaptureRequest.CONTROL_AF_MODE] = singleShotAutoFocusMode()")
        val triggerIndex = body.indexOf("CaptureRequest.CONTROL_AF_TRIGGER_START")

        assertTrue(applyIndex >= 0)
        assertTrue(
            "Tap-to-focus must force AUTO/single-shot AF on the trigger capture; some devices ignore " +
                "CONTROL_AF_TRIGGER_START when the request remains in continuous-video AF.",
            modeIndex > applyIndex
        )
        assertTrue(triggerIndex > modeIndex)
        assertTrue(body.contains("val repeatingRequest = buildSoftwareAeTrackedRequest(builder)"))
        assertTrue(body.contains("val triggerRequest = buildSoftwareAeTrackedRequestForRevision("))
        assertTrue(body.contains("val repeatingSubmitted = submitTrackedRepeatingRequest("))
        assertTrue(body.contains("val triggerSequence = if (repeatingSubmitted)"))
        assertTrue(body.contains("backgroundHandler?.postDelayed(tapAfScanTimeoutRunnable, TAP_AF_SCAN_TIMEOUT_MS)"))
        assertFalse(
            "The active scan must not be immediately cancelled by restoring continuous AF.",
            body.substringAfter("submitTrackedCaptureRequest(")
                .substringBefore("private fun maybeCompleteTapAfScan")
                .contains("updatePreview()")
        )
    }

    @Test
    fun `tap autofocus skips empty preview requests during mode transition`() {
        val body = source.substringAfter("override fun triggerSubjectAutoFocus(")
            .substringBefore("override fun captureStill(")

        assertTrue(body.contains("if (!previewBuilderHasTargets)"))
        // triggerSubjectAutoFocus now delegates to the shared runAutoFocusTrigger helper (which also
        // serves region-less tap-to-focus); the skip-empty-preview guard + log live there, inside the
        // scanned region.
        assertTrue(body.contains("runAutoFocusTrigger skipped: preview request has no active surface target"))
        assertTrue(body.contains("catch (e: IllegalArgumentException)"))
    }

    @Test
    fun `tap autofocus opens temporary ae window without overriding the white balance plan`() {
        val triggerBody = source.substringAfter("override fun triggerSubjectAutoFocus(")
            .substringBefore("override fun captureStill(")
        val applyBody = source.substringAfter("private fun applyAll(")
            .substringBefore("private fun maybeLogHdrColorRequest(")
        val lockBody = source.substringAfter("override fun setAeAwbLock(locked: Boolean)")
            .substringBefore("override fun isAeAwbLocked()")

        assertTrue(triggerBody.contains("beginTapFocusMeteringUnlock()"))
        assertTrue(triggerBody.indexOf("beginTapFocusMeteringUnlock()") <
            triggerBody.indexOf("applyAll(builder, false)"))
        assertTrue(applyBody.contains("val tapFocusMeteringUnlockActive = isTapFocusMeteringUnlockActive()"))
        assertTrue(applyBody.contains("tapFocusMeteringUnlockActive = tapFocusMeteringUnlockActive"))
        val unlockBody = source.substringAfter("private fun applyTapFocusMeteringUnlock(")
            .substringBefore("override fun triggerSubjectAutoFocus(")
        assertTrue(unlockBody.contains("builder[CaptureRequest.CONTROL_AE_LOCK] = false"))
        assertFalse(
            "Tap-focus AE re-metering must not overwrite the mode-specific AWB hold.",
            unlockBody.contains("builder[CaptureRequest.CONTROL_AWB_LOCK] = false")
        )
        assertTrue(lockBody.contains("if (locked) tapFocusMeteringUnlockUntilElapsedMs = 0L"))
    }

    @Test
    fun `tap autofocus does not route tapped highlight exposure region into awb metering`() {
        val tapBody = source.substringAfter("override fun triggerSubjectAutoFocus(")
            .substringBefore("override fun captureStill(")
        val meteringBody = source.substringAfter("override fun setSubjectMeteringRegions(")
            .substringBefore("override fun clearFocusRegion")
        val awbBody = source.substringAfter("private fun applyAutoWhiteBalanceRegions(")
            .substringBefore("private fun maybeLogHdrMeteringFallback")

        assertTrue(
            "Tap focus can legitimately meter exposure on a bright subject, but AWB must remain " +
                "independent and meter the complete visible frame.",
            tapBody.contains("persistentAwbRegion = null")
        )
        assertTrue(meteringBody.contains("persistentAwbRegion = null"))
        assertFalse(awbBody.contains("?: persistentAeRegion?.toCameraRect()"))
        assertFalse(awbBody.contains("?: persistentAfRegion?.toCameraRect()"))
        assertTrue(awbBody.contains("subjectRegion = null"))
        assertTrue(awbBody.contains("activeWhiteBalanceFrameRect()"))
    }

    @Test
    fun `autofocus request clears stale af regions and lens distance when not in use`() {
        val meteringBody = source.substringAfter("private fun applyPersistentMeteringRegions(")
            .substringBefore("private fun applyAutoWhiteBalanceRegions")
        val applyBody = source.substringAfter("val autofocusPlan = CameraAutofocusRequestPolicy.resolve(")
            .substringBefore("val normalSdrVideoPreviewActive")

        assertTrue(meteringBody.contains("else {\n            builder.set(CaptureRequest.CONTROL_AF_REGIONS, null)\n        }"))
        assertTrue(applyBody.contains("if (autofocusPlan.lensFocusDistance != null)"))
        assertTrue(applyBody.contains("builder.set(CaptureRequest.LENS_FOCUS_DISTANCE, null)"))
    }
}
