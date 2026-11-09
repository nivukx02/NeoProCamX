package com.neoproc.neoproccamx.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AppSecurityManifestContractTest {
    @Test
    fun productionManifest_disablesCleartextBackupAndExportedInternalComponents() {
        val manifest = File("src/main/AndroidManifest.xml").readText()

        assertTrue(manifest.contains("android:usesCleartextTraffic=\"false\""))
        assertTrue(manifest.contains("android:networkSecurityConfig=\"@xml/network_security_config\""))
        assertTrue(manifest.contains("android:allowBackup=\"false\""))
        assertTrue(manifest.contains("android.permission.HIDE_OVERLAY_WINDOWS"))
        assertFalse(exportedComponent(manifest, ".ui.GalleryActivity"))
        assertFalse(exportedComponent(manifest, ".ui.MediaPreviewActivity"))
        assertFalse(exportedComponent(manifest, ".recording.RecordingForegroundService"))
        assertTrue(
            componentHasPermission(
                manifest,
                ".ui.DebugCommandReceiver",
                "android.permission.DUMP"
            )
        )
    }

    @Test
    fun networkAndExtractionPolicies_failClosed() {
        val networkPolicy = File("src/main/res/xml/network_security_config.xml").readText()
        val extractionPolicy = File("src/main/res/xml/data_extraction_rules.xml").readText()

        assertTrue(networkPolicy.contains("cleartextTrafficPermitted=\"false\""))
        assertFalse(networkPolicy.contains("src=\"user\""))
        assertTrue(extractionPolicy.contains("<device-transfer>"))
        assertTrue(extractionPolicy.contains("<exclude domain=\"sharedpref\" path=\".\" />"))
        assertTrue(extractionPolicy.contains("<exclude domain=\"file\" path=\".\" />"))
    }

    @Test
    fun everyInteractiveActivity_rejectsTouchesAndHidesObscuringOverlays() {
        listOf("MainActivity.kt", "GalleryActivity.kt", "MediaPreviewActivity.kt").forEach { fileName ->
            val activity = File("src/main/java/com/neoproc/neoproccamx/ui/$fileName").readText()
            assertTrue(fileName, activity.contains("window.setHideOverlayWindows(true)"))
            assertTrue(fileName, activity.contains("override fun dispatchTouchEvent(event: MotionEvent)"))
            assertTrue(fileName, activity.contains("MotionEvent.FLAG_WINDOW_IS_OBSCURED"))
            assertTrue(fileName, activity.contains("MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED"))
        }
    }

    @Test
    fun releaseSecurityGate_runsAfterAndroidDslAndBlocksUnsignedArtifacts() {
        val buildScript = File("build.gradle").readText()
        val androidDsl = buildScript.indexOf("android {")
        val releaseGate = buildScript.indexOf(
            "if (releaseArtifactRequested && !releaseSigningAvailable)"
        )

        assertTrue(androidDsl >= 0)
        assertTrue(releaseGate > androidDsl)
        assertTrue(buildScript.contains("Unsigned APK/AAB output is intentionally blocked"))
        assertTrue(buildScript.contains("Play release generation requires NEOPROCAMX_PLAY_APP_SIGNING_SHA256"))
    }

    private fun exportedComponent(manifest: String, componentName: String): Boolean =
        Regex(
            "android:name=\\\"${Regex.escape(componentName)}\\\"[^>]*android:exported=\\\"true\\\"",
            setOf(RegexOption.DOT_MATCHES_ALL)
        ).containsMatchIn(manifest)

    private fun componentHasPermission(
        manifest: String,
        componentName: String,
        permission: String
    ): Boolean = Regex(
        "android:name=\\\"${Regex.escape(componentName)}\\\"[^>]*" +
            "android:permission=\\\"${Regex.escape(permission)}\\\"",
        setOf(RegexOption.DOT_MATCHES_ALL)
    ).containsMatchIn(manifest)
}
