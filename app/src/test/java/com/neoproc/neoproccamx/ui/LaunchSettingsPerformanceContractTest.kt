package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LaunchSettingsPerformanceContractTest {

    @Test
    fun platformSplashOwnsOneNineVariantLogoSequenceWithoutAnActivityOverlay() {
        val theme = File("src/main/res/values/themes.xml").readText()
        val sequence = File("src/main/res/drawable/splash_logo_sequence.xml").readText()
        val activity = File("src/main/java/com/neoproc/neoproccamx/ui/MainActivity.kt").readText()

        assertTrue(theme.contains("windowSplashScreenAnimatedIcon\">@drawable/splash_logo_sequence"))
        assertFalse(theme.contains("windowSplashScreenAnimatedIcon\">@drawable/launcher_logo_v04"))
        assertTrue(sequence.contains("android:oneshot=\"false\""))
        assertTrue(sequence.split("<item ").size - 1 == SplashLogoRotationPolicy.VARIANT_COUNT)
        assertFalse(activity.contains("showRotatingSplashLogo"))
        assertFalse(activity.contains("rotatingSplashView"))
    }

    @Test
    fun firstActuallyPresentedFrameDismissesSplashWithoutSensorOrFpsShortcuts() {
        val activity = File("src/main/java/com/neoproc/neoproccamx/ui/MainActivity.kt").readText()
        val presentationCollector = activity
            .substringAfter("vm.previewDisplayPresentEvents.collect")
            .substringBefore("vm.previewFrameTimestamps.collect")
        val sensorTimestampCollector = activity
            .substringAfter("vm.previewFrameTimestamps.collect")
            .substringBefore("vm.liveResult.collect")
        val fpsListener = activity
            .substringAfter("binding.glSurface.setOnPreviewFpsListener")
            .substringBefore("binding.glSurface.setOnSurfaceReadyListener")

        assertTrue(presentationCollector.contains("coldSplashPresentationGate.accept"))
        assertTrue(presentationCollector.contains("warmSplashPresentationGate.accept"))
        assertTrue(presentationCollector.contains("previewReadyForSplash = true"))
        assertFalse(sensorTimestampCollector.contains("previewReadyForSplash = true"))
        assertFalse(sensorTimestampCollector.contains("requestWarmLaunchSplashDismiss()"))
        assertFalse(fpsListener.contains("previewReadyForSplash = true"))
        assertFalse(fpsListener.contains("requestWarmLaunchSplashDismiss()"))
        assertTrue(activity.contains("setKeepOnScreenCondition"))
        assertTrue(activity.contains("PLATFORM_SPLASH_FADE_MS = 90L"))
    }

    @Test
    fun settingsOwnsACompleteCameraSuspensionAndDismissesThroughTheFastResumePath() {
        val activity = File("src/main/java/com/neoproc/neoproccamx/ui/MainActivity.kt").readText()
        val sheet = File("src/main/java/com/neoproc/neoproccamx/ui/CameraSettingsSheetFragment.kt").readText()
        val viewModel = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
        val openSettings = activity
            .substringAfter("private fun openSettingsSheet()")
            .substringBefore("fun launchCustomLutImport")

        assertTrue(openSettings.contains("CameraSettingsLaunchTransaction.launch("))
        assertTrue(openSettings.indexOf("onStarted = ::onSettingsSheetStarted") < openSettings.indexOf("show = {"))
        assertTrue(activity.contains("vm.suspendCameraForSettings()"))
        assertTrue(sheet.contains("(activity as? CameraSettingsLifecycleHost)?.onSettingsSheetStarted()"))
        assertFalse(sheet.contains("viewModel.suspendCameraForSettings()"))
        assertTrue(sheet.contains("override fun onDismiss"))
        assertFalse(sheet.contains("override fun onStop()"))
        assertTrue(viewModel.contains("settingsCameraLifecycle.isSuspended()"))
        assertTrue(viewModel.contains("resumeCameraAfterSettings"))
        assertTrue(viewModel.contains("retainPreviewPresentationForSettings"))
        assertTrue(activity.contains("binding.glSurface.resetPreviewFpsTracking()"))
        assertTrue(viewModel.contains("Settings fast resume bypassing cold-start ramp"))
        assertTrue(viewModel.contains("settingsCameraLifecycle.consumeFastResume()"))
        assertTrue(viewModel.contains("lastPreviewFpsSample = 0f"))
    }
}
