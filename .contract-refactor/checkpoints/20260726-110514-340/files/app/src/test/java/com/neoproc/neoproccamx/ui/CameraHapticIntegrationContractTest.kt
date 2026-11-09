package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CameraHapticIntegrationContractTest {

    @Test
    fun uiCodeRoutesPhysicalFeedbackThroughCameraHapticPolicy() {
        val violations = productionKotlinFiles()
            .filterNot { it.name == "CameraHapticFeedbackExecutor.kt" }
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    val directHaptic = line.contains(".performHapticFeedback(")
                    val directVibration = line.contains(".vibrate(")
                    if (directHaptic || directVibration) {
                        "${file.toRelativeSourcePath()}:${index + 1}: ${line.trim()}"
                    } else {
                        null
                    }
                }
            }
            .toList()

        assertTrue(
            "UI haptics must route through CameraHapticFeedbackPolicy/MainActivity executor:\n" +
                violations.joinToString(separator = "\n"),
            violations.isEmpty()
        )
    }

    @Test
    fun manualControlsUsePolicyActionsForDefaultsAndTicks() {
        val source = productionFile("ManualControlsFragment.kt").readText()

        assertTrue(
            "Manual controls should request default-reset feedback through SNAP_TO_DEFAULT.",
            source.contains("CameraHapticAction.SNAP_TO_DEFAULT")
        )
        assertTrue(
            "Manual controls should use the policy-backed haptic performer.",
            source.contains("CameraHapticPerformer")
        )
        assertTrue(
            "ManualControlsFragment should not call platform haptics directly.",
            !source.contains("performHapticFeedback(")
        )
    }

    @Test
    fun referenceAdjustSeekBarEmitsBoundaryStopsAndMechanicalTicks() {
        val source = productionFile("ReferenceAdjustSeekBar.kt").readText()

        assertTrue(
            "ReferenceAdjustSeekBar should expose a policy-backed haptic callback.",
            source.contains("setCameraHapticCallback")
        )
        assertTrue(
            "Seekbar progress at either end should emit SLIDER_BOUNDARY_STOP.",
            source.contains("CameraHapticAction.SLIDER_BOUNDARY_STOP")
        )
        assertTrue(
            "Seekbar incremental user movement should emit MANUAL_CONTROL_TICK.",
            source.contains("CameraHapticAction.MANUAL_CONTROL_TICK")
        )
    }

    @Test
    fun mainActivityRoutesRuntimeAlertsToCentralExecutor() {
        val source = productionFile("MainActivity.kt").readText()
        val executorSource = productionFile("CameraHapticFeedbackExecutor.kt").readText()

        assertTrue(
            "MainActivity should expose the centralized haptic executor to fragments.",
            source.contains("CameraHapticPerformer")
        )
        assertTrue(
            "MainActivity should wire target-lock feedback through AI_TARGET_LOCK.",
            source.contains("CameraHapticAction.AI_TARGET_LOCK")
        )
        assertTrue(
            "MainActivity should wire pipeline warnings through CRITICAL_PIPELINE_ALERT.",
            source.contains("CameraHapticAction.CRITICAL_PIPELINE_ALERT")
        )
        assertTrue(
            "The central executor should execute policy waveform contracts with VibrationEffect.createWaveform.",
            executorSource.contains("VibrationEffect.createWaveform(timingsMs, amplitudes, waveform.repeat)")
        )
    }

    private fun productionFile(name: String): File =
        productionKotlinFiles().firstOrNull { it.name == name }
            ?: error("Missing production file $name")

    private fun productionKotlinFiles(): Sequence<File> {
        val sourceRoot = listOf(
            File("app/src/main/java/com/neoproc/neoproccamx"),
            File("src/main/java/com/neoproc/neoproccamx")
        ).firstOrNull { it.isDirectory }
            ?: File("app/src/main/java/com/neoproc/neoproccamx")
        assertTrue("Missing production source root: ${sourceRoot.absolutePath}", sourceRoot.isDirectory)
        return sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }
    }

    private fun File.toRelativeSourcePath(): String = relativeTo(File(".")).path
}
