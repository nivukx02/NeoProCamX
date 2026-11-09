package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CameraControllerFpsContractTest {

    @Test
    fun `live fps resolver enables vendor cadence reinforcement only for npv10`() {
        val source = java.io.File(
            "src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt"
        ).readText()
        val start = source.indexOf("private fun resolveCurrentStreamBasedFpsOverridePlan")
        val end = source.indexOf("private fun updateRequestedCameraFpsHint", start)
        assertTrue(start >= 0 && end > start)
        val block = source.substring(start, end)

        assertTrue(
            block.contains("preferVendorCadenceLockWhenAvailable = npv10TenBitCaptureEnabled")
        )
    }
    @Test
    fun setFpsEvaluatesStreamBasedOverrideBeforePersistingAppliedTarget() {
        val source = File("src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt").readText()
        val setFpsBlock = source
            .substringAfter("override fun setFPS(fps: Int): Int")
            .substringBefore("override fun triggerTapAF")
        val resolverSignature = source.substringAfter("private fun resolveCurrentStreamBasedFpsOverridePlan(")
            .substringBefore("): CameraStreamBasedFpsOverridePlan")

        assertTrue(resolverSignature.contains("requestedFpsOverride: Int? = null"))
        assertTrue(source.contains("requestedFps = requestedFpsOverride ?: requestedCameraFpsHint ?: targetCameraFps"))
        assertTrue(setFpsBlock.contains("resolveCurrentStreamBasedFpsOverridePlan(requestedFpsOverride = fps)"))
        assertTrue(setFpsBlock.contains("updateRequestedCameraFpsHint(fps)"))
        assertTrue(source.contains("requestedCameraFpsHint = fps"))
        assertTrue(setFpsBlock.contains("streamBasedFpsOverride = streamFpsOverride"))
        assertTrue(
            setFpsBlock.indexOf("resolveCurrentStreamBasedFpsOverridePlan(requestedFpsOverride = fps)") <
                setFpsBlock.indexOf("targetCameraFps = fpsPlan.appliedFps")
        )
        assertTrue(
            setFpsBlock.indexOf("updateRequestedCameraFpsHint(fps)") <
                setFpsBlock.indexOf("targetCameraFps = fpsPlan.appliedFps")
        )
    }

    @Test
    fun streamBasedVendorFpsKeysSupportArrayPayloadsAndFailureCaching() {
        val source = File("src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt").readText()
        val helperBlock = source
            .substringAfter("private fun applyStreamBasedFpsNumericKey(")
            .substringBefore("private fun maybeLogStreamBasedFpsOverride")

        assertTrue(source.contains("disabledStreamFpsKeySignatures"))
        assertTrue(helperBlock.contains("listOf(\"floatArray\", \"intArray\", \"float\", \"int\")"))
        assertTrue(helperBlock.contains("listOf(\"intArray\", \"floatArray\", \"int\", \"float\")"))
        assertTrue(helperBlock.contains("intArrayOf(value.toInt())"))
        assertTrue(helperBlock.contains("floatArrayOf(value)"))
        assertTrue(helperBlock.contains("disabledStreamFpsKeySignatures += signature"))
    }

    @Test
    fun npv10ReadoutKeepsStreamFpsOverrideWhenEncoderTargetIsDetached() {
        val source = File("src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt").readText()
        val resolverBlock = source
            .substringAfter("private fun resolveCurrentStreamBasedFpsOverridePlan(")
            .substringBefore("private fun updateRequestedCameraFpsHint")

        assertTrue(resolverBlock.contains("CameraStreamBasedFpsCaptureSizePolicy.resolve("))
        assertTrue(resolverBlock.contains("npv10CaptureEnabled = npv10TenBitCaptureEnabled"))
        assertTrue(resolverBlock.contains("npv10CaptureSize = npv10CaptureTargetSize?.toCameraStreamSize()"))
        assertTrue(resolverBlock.contains("encoderSize = recordingEncoderTargetSize?.toCameraStreamSize()"))
    }
}
