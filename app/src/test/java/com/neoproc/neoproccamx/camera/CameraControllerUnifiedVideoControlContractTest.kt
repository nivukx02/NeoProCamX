package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CameraControllerUnifiedVideoControlContractTest {
    private val source: String by lazy {
        File("src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt").readText()
    }
    private val viewModelSource: String by lazy {
        File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
    }

    @Test
    fun `live video applyAll resolves one unified decision while still capture stays legacy`() {
        val applyBody = source
            .substringAfter("private fun applyAll(")
            .substringBefore("private fun supportsCompleteCctWhiteBalanceRequest()")

        assertTrue(applyBody.contains("val unifiedControlDecision = if (videoRenderContractActive)"))
        assertTrue(applyBody.contains("resolveUnifiedVideoControlDecision("))
        assertTrue(applyBody.contains("else {\n            null\n        }"))
        assertFalse(
            source.substringAfter("private fun submitStillCaptureOnCameraThread(")
                .substringBefore("private fun dispatchJpegReady(")
                .contains("UnifiedVideoControlCoordinator.resolve")
        )
    }

    @Test
    fun `runtime mapping covers VIDEO PRO NDR LOG and RAW without confusing sensor linear NDR for RAW`() {
        val modeBody = source
            .substringAfter("private fun currentUnifiedVideoControlMode(")
            .substringBefore("private fun supportsUnifiedFixedWhiteBalance(")

        assertTrue(modeBody.contains("UnifiedVideoControlMode.fromRuntime("))
        assertTrue(modeBody.contains("rawRequestContract.sensorLinearNdrActive"))
        assertTrue(modeBody.contains("rawRequestContract.rawOriginalCaptureActive"))
        assertTrue(modeBody.contains("CinemaVideoMode.RAW"))
        assertTrue(modeBody.contains("CinemaVideoMode.LOG"))
        assertTrue(modeBody.contains("professionalVideoControlsActive"))
        assertTrue(modeBody.contains("rawSensorActive = rawRequestContract.rawOriginalCaptureActive"))
        assertTrue(modeBody.contains("professionalControlsActive = professionalVideoControlsActive"))
        assertFalse(modeBody.contains("professionalControlsActive = cinemaFidelityOverridesEnabled"))
    }

    @Test
    fun `ViewModel publishes explicit PRO identity independently from low processing overrides`() {
        val occurrences = viewModelSource.windowedSequence(
            size = "camera.setProfessionalVideoControlsActive(".length,
            step = 1
        ).count { it == "camera.setProfessionalVideoControlsActive(" }

        assertTrue(occurrences >= 2)
        assertTrue(viewModelSource.contains("_sdrQualityIntent.value == SdrQualityIntent.PRO"))
        assertTrue(viewModelSource.contains("_manualControlsVisible.value"))
        assertTrue(viewModelSource.contains("_uiMode.value == AppMode.VIDEO"))
        assertTrue(source.contains("private var professionalVideoControlsActive = false"))
        assertTrue(source.contains("override fun setProfessionalVideoControlsActive(enabled: Boolean)"))
        val intentSetter = viewModelSource
            .substringAfter("internal fun setSdrQualityIntent(intent: SdrQualityIntent)")
            .substringBefore("fun toggleHdrMode()")
        assertTrue(intentSetter.contains("camera.setProfessionalVideoControlsActive("))
        assertTrue(intentSetter.contains("intent == SdrQualityIntent.PRO"))
    }

    @Test
    fun `coordinator receives real capabilities manual controls detail profile and specialized meter signals`() {
        val resolverBody = source
            .substringAfter("private fun resolveUnifiedVideoControlDecision(")
            .substringBefore("private fun isCinemaLowProcessingRequestActive()")

        assertTrue(resolverBody.contains("UnifiedVideoControlCoordinator.resolve("))
        assertTrue(resolverBody.contains("detailProfile = selectedVideoDetailProfile"))
        assertTrue(resolverBody.contains("supportsManualSensor = supportsManualSensor"))
        assertTrue(resolverBody.contains("supportsUnifiedFixedWhiteBalance("))
        assertTrue(resolverBody.contains("supportsCaptureRequestKey(CaptureRequest.LENS_FOCUS_DISTANCE)"))
        assertTrue(resolverBody.contains("iso = manIso.takeIf { it > 0 }"))
        assertTrue(resolverBody.contains("exposureTimeNs = manExpNs.takeIf { it > 0L }"))
        assertTrue(resolverBody.contains("whiteBalanceKelvin = requestedWhiteBalanceKelvin"))
        assertTrue(resolverBody.contains("focusOverrideActive = effectiveManualFocusDistance >= 0f"))
        assertTrue(resolverBody.contains("highlightProtectionBiasStops = highlightProtectionBiasStops"))
        assertTrue(resolverBody.contains("sensorLinearNdrBiasStops = sensorLinearNdrBiasStops"))
        assertTrue(resolverBody.contains("previousExposureCompensationIndex = previousExposureCompensationIndex"))
        assertTrue(resolverBody.contains("previousFocusDistanceDiopters = previousFocusDistance"))
        assertTrue(resolverBody.contains("elapsedSincePreviousDecisionSeconds = focusDecisionIntervalSeconds"))
        assertTrue(resolverBody.contains("SystemClock.elapsedRealtimeNanos() - lastPreviewFrameCallbackElapsedNs"))
        assertTrue(resolverBody.contains("?.coerceIn(nominalFrameIntervalSeconds, 0.25f)"))
        assertTrue(
            resolverBody.contains(
                "lastUnifiedVideoControlSessionGeneration == controlSessionGeneration"
            )
        )
        assertTrue(resolverBody.contains("lastUnifiedVideoControlSessionGeneration = controlSessionGeneration"))
        assertTrue(resolverBody.contains("snapshot.provenance.sensorTimestampNs == latestSensorTimestampNs"))
        assertTrue(resolverBody.contains("?.awbConfidence"))
        assertTrue(resolverBody.contains("it.isCurrent(controlSessionGeneration)"))
        assertTrue(resolverBody.contains("?.confidence"))
        assertFalse(resolverBody.contains("if (autoWhiteBalanceConverged && measuredWhitePoint != null) 1f else 0f"))
        assertFalse(resolverBody.contains("aiFocusConfidence = if (aiDrivenFocusDist >= 0f) 1f else 0f"))
    }

    @Test
    fun `fixed white balance capability is queried only for an explicit allowed manual kelvin`() {
        val supportBody = source
            .substringAfter("private fun supportsUnifiedFixedWhiteBalance(")
            .substringBefore("private fun resolveUnifiedVideoControlDecision(")
        val resolverBody = source
            .substringAfter("private fun resolveUnifiedVideoControlDecision(")
            .substringBefore("private fun isCinemaLowProcessingRequestActive()")

        assertTrue(supportBody.contains("val kelvin = requestedKelvin ?: return false"))
        assertTrue(supportBody.contains("CaptureRequest.CONTROL_AWB_MODE_OFF"))
        assertTrue(supportBody.contains("supportsCompleteCctWhiteBalanceRequest()"))
        assertTrue(supportBody.contains("resolveManualWhiteBalanceTransform(kelvin, requestedTint)"))
        assertTrue(resolverBody.contains(".takeIf { manualWhiteBalanceAllowed && it > 0 }"))
    }

    @Test
    fun `unified focus white balance and exposure decisions own final Camera2 application points`() {
        val applyBody = source
            .substringAfter("private fun applyAll(")
            .substringBefore("private fun supportsCompleteCctWhiteBalanceRequest()")

        assertTrue(applyBody.contains("val unifiedFocus = unifiedControlDecision?.focus"))
        assertTrue(applyBody.contains("UnifiedFocusAuthority.USER_MANUAL"))
        assertTrue(applyBody.contains("UnifiedFocusAuthority.AI_LENS_DISTANCE"))
        assertTrue(applyBody.contains("unifiedControlDecision.whiteBalance.fixedKelvin"))
        assertTrue(applyBody.contains("unifiedControlDecision.whiteBalance.requestAutoLock"))
        assertTrue(applyBody.contains("UnifiedExposureAuthority.USER_MANUAL_SENSOR"))
        assertTrue(applyBody.contains("UnifiedExposureAuthority.SYSTEM_NPV10_CADENCE_GUARD"))
        assertTrue(applyBody.contains("UnifiedExposureAuthority.PLATFORM_AE"))
        assertTrue(applyBody.contains("?.exposureCompensationIndex"))
        assertTrue(applyBody.contains("builder[CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION]"))
    }

    @Test
    fun `specialized cinematic NDR RAW and tap policies retain explicit precedence`() {
        val applyBody = source
            .substringAfter("private fun applyAll(")
            .substringBefore("private fun supportsCompleteCctWhiteBalanceRequest()")

        assertTrue(applyBody.contains("CameraCinematicExposurePolicy.applyLockedShutterEv("))
        assertTrue(applyBody.contains("SensorLinearNdrExposurePolicy.resolveAppliedStops("))
        assertTrue(applyBody.contains("sensorMasterActive = rawSensorRequestActive"))
        assertTrue(applyBody.contains("ndrOrLogModeActive || rawSensorRequestActive"))
        assertTrue(applyBody.contains("tapAfScanActive && !forStillCapture"))
        assertTrue(applyBody.contains("!tapAfScanActive"))
        assertFalse(applyBody.contains("applyZoomAndAspectCrop(builder, includeAspectCrop = false)"))
    }

    @Test
    fun `processed still does not inherit video cinematic exposure cadence`() {
        val applyBody = source
            .substringAfter("private fun applyAll(")
            .substringBefore("private fun supportsCompleteCctWhiteBalanceRequest()")

        assertTrue(
            applyBody.contains(
                "cinematicActive = !forStillCapture && isCinemaLowProcessingRequestActive()"
            )
        )
    }

    @Test
    fun `NPV10 1K 30 cadence guard is scoped and feeds one unified exposure authority`() {
        val meterBody = source
            .substringAfter("private fun currentNpv10CadenceExposurePlan(")
            .substringBefore("private fun softwareAePriorityRequestedForCurrentControls()")
        val resolverBody = source
            .substringAfter("private fun resolveUnifiedVideoControlDecision(")
            .substringBefore("private fun isCinemaLowProcessingRequestActive()")

        assertTrue(meterBody.contains("Npv10CadenceExposurePolicy.resolve("))
        assertTrue(meterBody.contains("privateMasterPipeline = recordingEncoderTargetSize == null"))
        assertTrue(resolverBody.contains("cadenceGuardExposureTimeNs"))
        assertTrue(resolverBody.contains("cadenceGuardSensorSensitivityIso"))
    }

    @Test
    fun `NPV10 cadence exposure does not compete with legacy SDR request updates`() {
        val metricsBody = source
            .substringAfter("override fun setHdrExposureSceneMetrics(")
            .substringBefore("override fun setSensorLinearNdrExposureSceneMetrics(")
        val cadenceReturn = metricsBody.indexOf("if (currentNpv10CadenceExposurePlan().active)")
        val legacySdrBranch = metricsBody.indexOf(
            "if (hdrDynamicRangeMode == HdrDynamicRangeMode.SDR && !sensorLinearNdrCaptureEnabled)"
        )

        assertTrue(cadenceReturn >= 0)
        assertTrue(
            "The unified cadence authority must return before legacy SDR exposure feedback",
            legacySdrBranch >= 0 && cadenceReturn < legacySdrBranch
        )
    }

    @Test
    fun `any unified exposure decision blocks legacy cinematic authority during reframing`() {
        val applyBody = source
            .substringAfter("private fun applyAll(")
            .substringBefore("private fun supportsCompleteCctWhiteBalanceRequest()")
        val resolvedIsoBody = applyBody
            .substringAfter("val resolvedIso = when {")
            .substringBefore("val resolvedExp = when {")
        val resolvedExposureBody = applyBody
            .substringAfter("val resolvedExp = when {")
            .substringBefore("val baseManualFpsRequest")
        val compensationBody = applyBody
            .substringAfter("val manualEvCompensation = when {")
            .substringBefore("val manualFpsRequest")

        assertTrue(
            applyBody.contains(
                "unifiedControlDecision == null && cinematicExposurePlan.activateManualExposure"
            )
        )
        assertTrue(
            resolvedIsoBody.indexOf("unifiedManualSensorExposure != null") <
                resolvedIsoBody.indexOf("legacyCinematicManualExposure")
        )
        assertTrue(
            resolvedExposureBody.indexOf("unifiedManualSensorExposure != null") <
                resolvedExposureBody.indexOf("legacyCinematicManualExposure")
        )
        assertTrue(
            compensationBody.indexOf("unifiedManualSensorExposure != null") <
                compensationBody.indexOf("legacyCinematicManualExposure")
        )
    }
}
