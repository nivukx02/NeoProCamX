package com.neoproc.neoproccamx.rendering

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NdrSensorLinearPreviewRecordingParityContractTest {

    private fun source(pathFromApp: String): String {
        val candidates = listOf(File(pathFromApp), File("app/$pathFromApp"))
        return candidates.first { it.isFile }.readText()
    }

    @Test
    fun `one cached measured packet drives raw monitor encoder and visible preview`() {
        val runtime = source("src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt")
        val camera = source("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt")
        val renderPush = camera
            .substringAfter("private fun pushVulkanPreviewRenderConfig(")
            .substringBefore("private fun resolveGpuColorProfile")
        val profileLifecycle = source(
            "src/main/java/com/neoproc/neoproccamx/ui/NdrRenderProfileLifecycleCoordinator.kt"
        )
        val vulkan = source("src/main/cpp/vulkan_bridge.cpp")
        val offscreen = source("src/main/cpp/offscreen_egl_encoder_bridge.cpp")

        val fanOut = runtime
            .substringAfter("private fun applyNdrNivukxMeasuredCorrectionIfNeeded(profile: NdrLiveProfile)")
            .substringBefore("fun setOffscreenRecordGrade")
        assertTrue(fanOut.contains("bridge.setRawMonitorNdrNivukxCorrection(correction, profile)"))
        assertTrue(fanOut.contains("offscreenAhbEglEncoderBridge.setNdrNivukxMeasuredCorrection("))
        assertTrue(fanOut.contains("sensorLinearNdrPreviewBridge.setNdrNivukxMeasuredCorrection("))
        assertTrue(runtime.contains("packet.enhancementUniforms.detailProfileId"))
        assertTrue(fanOut.contains("acceptedRenderProfileGeneration"))
        assertTrue(fanOut.contains("if (!monitorApplied || !encoderApplied || !previewApplied)"))
        assertTrue(
            fanOut.indexOf("appliedNdrNivukxMeasuredCorrection = correction") >
                fanOut.indexOf("if (!monitorApplied || !encoderApplied || !previewApplied)")
        )
        assertTrue(camera.contains("NdrRenderProfileLifecycleCoordinator.commitForFrameIngress("))
        assertTrue(camera.contains("NdrRenderProfileLifecycleCoordinator.finalizeFrameIngress("))
        assertTrue(profileLifecycle.contains("runtime.commitNdrRenderProfilePacket(packet)"))
        assertTrue(profileLifecycle.contains("permitsOwnerPublication(result)"))
        assertTrue(
            camera.filterNot(Char::isWhitespace).contains(
                "NdrRenderProfilePacket(profileGeneration=profileGeneration," +
                    "configRevision=NdrRenderProfileRevisionSequencer.UNASSIGNED_REVISION," +
                    "correction=ndrNivukxMeasuredCorrection," +
                    "enhancementUniforms=liveGpuEnhancementUniforms," +
                    "nativeRenderConfig=VulkanNativeRenderProfileConfig("
            )
        )
        assertTrue(camera.contains("NdrNivukxMeasuredCorrectionPolicy.resolve(runtimeNormalizedHdrMode)"))
        assertTrue(camera.contains("VideoHdrRuntimeAuthorityPolicy.resolve("))
        assertTrue(renderPush.contains("activeHdrMode = hdrModeAuthority"))
        assertFalse(renderPush.contains("activeHdrMode = _videoHdrMode.value"))
        assertTrue(
            renderPush.indexOf("ndrRenderProfileRevisionSequencer.assign(") <
                renderPush.indexOf("NdrRenderProfileLifecycleCoordinator.commitForFrameIngress(")
        )
        assertTrue(
            renderPush.indexOf("NdrRenderProfileLifecycleCoordinator.commitForFrameIngress(") <
                renderPush.indexOf("NdrRenderProfileLifecycleCoordinator.finalizeFrameIngress(")
        )
        assertTrue(renderPush.contains("renderProfilePacket,"))
        assertFalse(
            camera.contains("NdrNivukxMeasuredCorrectionPolicy.resolve(prefs.videoHdrMode)")
        )

        assertTrue(vulkan.contains("npRawApplyMeasuredNdrCorrection"))
        assertTrue(vulkan.contains("npRawNdrSdrMonitor"))
        assertTrue(vulkan.contains("state->rawMonitorNdrCorrection"))
        assertTrue(vulkan.contains("rawNdrMode="))
        assertTrue(offscreen.contains("neoApplyNdrMeasuredCorrection"))
        assertTrue(offscreen.contains("neoApplyMeasuredHighlightRolloff"))
        assertTrue(offscreen.contains("uniform vec4 uNdrMeasuredRgbScale"))
        assertTrue(offscreen.contains("uniform vec4 uNdrMeasuredTone"))
        assertTrue(offscreen.contains("uniform vec2 uNdrMeasuredHue"))

        val kotlinOffscreen = source(
            "src/main/java/com/neoproc/neoproccamx/rendering/OffscreenAhbEglEncoderBridge.kt"
        )
        assertTrue(kotlinOffscreen.contains("NdrRenderProfileCoherencePolicy.evaluate("))
        assertTrue(kotlinOffscreen.contains("NDR_PROFILE_FRAME_REJECTED"))
        assertTrue(kotlinOffscreen.contains("retaining previous valid surface frame"))
    }

    @Test
    fun `normal finished recordings cannot silently reenable flat master`() {
        val flag = source("src/main/java/com/neoproc/neoproccamx/rendering/RecordDevelopModeFlag.kt")
        val defaults = source("src/main/java/com/neoproc/neoproccamx/ui/ProductDefaultsApplier.kt")
        val camera = source("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt")

        assertTrue(flag.contains("var enabled: Boolean = false"))
        assertTrue(defaults.contains("RecordDevelopModeFlag.set(false)"))
        assertFalse(defaults.contains("RecordDevelopModeFlag.set(true)"))
        assertFalse(camera.contains("RecordDevelopModeFlag.set(true)"))
    }

    @Test
    fun `camera hlg and pq buffers are never proprietary ndr ingress`() {
        val transport = source("src/main/java/com/neoproc/neoproccamx/rendering/NdrInputTransportPolicy.kt")
        val bridge = source("src/main/java/com/neoproc/neoproccamx/rendering/OffscreenAhbEglEncoderBridge.kt")
        val legacyP010 = source("src/main/cpp/shaders/vulkan/neo_p010_export_enhance.comp")

        assertTrue(transport.contains("transfer == NdrInputTransfer.NEO_SENSOR_LINEAR ||"))
        assertTrue(transport.contains("transfer == NdrInputTransfer.NEO_SCENE_LOG10"))
        assertTrue(transport.contains("primaries == NdrInputPrimaries.ACESCG"))
        assertTrue(transport.contains("fun isSupportedNdrInputProfile(profile: Long?): Boolean = false"))
        assertTrue(bridge.contains("Rejecting NDR frame: use a RAW-derived project-owned ACEScg ingress"))
        assertTrue(bridge.contains("submitSensorLinearAcesCg"))
        assertTrue(bridge.contains("submitSceneLog10AcesCg"))
        assertTrue(legacyP010.contains("#define NEO_PQ_DELIVERY_ENABLED 0"))
    }

    @Test
    fun `camera2 p010 private master cannot bypass raw derived ndr recording`() {
        val camera = source("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt")
        val route = source("src/main/java/com/neoproc/neoproccamx/ui/Npv10RecordingRoutePolicy.kt")

        val recordingStart = camera
            .substringAfter("private fun startRecordingInternal()")
            .substringBefore("private fun shouldUseUncompressedCinemaPipeline(): Boolean")
        val effectiveRoute = camera
            .substringAfter("private fun shouldUseUncompressedCinemaPipeline(): Boolean")
            .substringBefore("private fun shouldUseCustomRawRecordingPipeline(): Boolean")

        assertTrue(recordingStart.contains("if (shouldUseUncompressedCinemaPipeline())"))
        assertTrue(recordingStart.contains("val sensorLinearNdrInput = isSensorLinearNdrMode("))
        assertTrue(
            recordingStart.contains("sensorLinearNdrRecordingLifecycle.startIngress()")
        )
        assertTrue(recordingStart.contains("awaitFirstProcessedGpuRecordingFrame("))
        assertTrue(effectiveRoute.contains("Npv10RecordingRoutePolicy.shouldUseCamera2P010Master("))
        assertTrue(route.contains("!SensorLinearNdrModePolicy.isActive("))
        assertTrue(route.contains("RAW_SENSOR -> ACEScg"))
    }
}
