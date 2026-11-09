package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StrictVideoModeAdmissionIntegrationTest {
    private val source: String by lazy {
        File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
    }
    private val admissionPolicySource: String by lazy {
        File("src/main/java/com/neoproc/neoproccamx/ui/VideoModePipelineContractPolicy.kt").readText()
    }

    @Test
    fun `cinema mutation resolves explicit admission before changing active state`() {
        val block = source
            .substringAfter("private fun applyCinemaModeSelection(")
            .substringBefore("private fun deriveCinemaModeFromFlags()")

        val admissionIndex = block.indexOf("resolveCinemaModeAdmission(")
        val pipelineAdmissionIndex = block.indexOf("resolveRequestedVideoModeAdmission(")
        val mutationIndex = block.indexOf("_cinemaVideoMode.value = normalizedMode")

        assertTrue(admissionIndex >= 0)
        assertTrue(pipelineAdmissionIndex > admissionIndex)
        assertTrue(mutationIndex > pipelineAdmissionIndex)
        assertTrue(block.contains("VideoPipelineAdmissionState.ADMITTED"))
        assertTrue(block.contains("return rejectVideoModeAdmission("))
    }

    @Test
    fun `unsupported raw toggle never substitutes pro`() {
        val block = source
            .substringAfter("fun toggleRaw()")
            .substringBefore("fun setRawEnabled(enabled: Boolean)")
        val unsupportedBlock = block
            .substringAfter("if (!camera.supportsRaw)")
            .substringBefore("val enableRawVideo")

        assertTrue(unsupportedBlock.contains("resolveRequestedVideoModeAdmission(CinemaVideoMode.RAW)"))
        assertTrue(unsupportedBlock.contains("rejectVideoModeAdmission("))
        assertFalse(unsupportedBlock.contains("applyCinemaModeSelection(CinemaVideoMode.PRO)"))
    }

    @Test
    fun `recording admission checks strict mode before authoritative backend state`() {
        val startDeclaration = source.lineSequence()
            .first { it.trimStart().startsWith("fun startRecording()") }
            .trim()
        val claim = source
            .substringAfter("private fun claimRecordingStartAdmission(): Boolean {")
            .substringBefore("private fun beginClaimedRecordingStart()")
        val settingsAdmissionIndex = claim.indexOf(
            "admitSettingsCommit(proposedSettingsCommit(), source = \"recording-start\")"
        )
        val modeAdmissionIndex = claim.indexOf("validateActiveVideoModeAdmissionForRecording()")
        val backendAdmissionIndex = claim.indexOf("RecordingStartBackendAdmissionPolicy.allowsStart(")

        assertTrue(
            startDeclaration ==
                "fun startRecording() = recordingStopRestartLifecycle.requestStart()"
        )
        assertTrue(settingsAdmissionIndex >= 0)
        assertTrue(modeAdmissionIndex > settingsAdmissionIndex)
        assertTrue(backendAdmissionIndex > modeAdmissionIndex)
        assertTrue(claim.contains("hasActiveRecordingBackend()"))
        assertFalse(claim.contains("recordingController.startRecording()"))
        assertTrue(claim.contains("gpuRecording.reportExternalErrorRetainingResources(message)"))
    }

    @Test
    fun `video graph receives physical sensor identity capabilities and raw source extent`() {
        val block = source
            .substringAfter("private fun activeVideoPipelineContract(")
            .substringBefore("private fun activeVideoGraphPreviewSize()")

        assertTrue(block.contains("hardwareCapabilities = VideoModePipelineContractPolicy.resolveHardwareCapabilities("))
        assertTrue(block.contains("physicalCameraGeometryIdentity = activePhysicalCameraGeometryIdentity()"))
        assertTrue(block.contains("sourceWidth = sourceSize.width"))
        assertTrue(block.contains("sourceHeight = sourceSize.height"))
        assertTrue(block.contains("camera.fullSensorSize().normalizeForOrientation()"))
    }

    @Test
    fun `p010 capability query is guarded on pre android 12 devices`() {
        val block = admissionPolicySource
            .substringAfter("val nativeP010OutputSupported =")
            .substringBefore("val cameraTenBitTransportSupported")

        assertTrue(block.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.S"))
        assertTrue(block.contains("getOutputSizes(ImageFormat.YCBCR_P010)"))
        assertTrue(block.indexOf("Build.VERSION.SDK_INT >= Build.VERSION_CODES.S") <
            block.indexOf("getOutputSizes(ImageFormat.YCBCR_P010)"))
        assertTrue(block.contains("else {\n                false"))
    }
}
