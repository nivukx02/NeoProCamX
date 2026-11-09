package com.neoproc.neoproccamx.quality

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProductionSourceHygieneTest {

    @Test
    fun productionCodeUsesAndroidxExifInterface() {
        val violations = productionKotlinFiles()
            .filter { it.readText().contains("import android.media.ExifInterface") }
            .map { it.toRelativeSourcePath() }
            .toList()

        assertTrue(
            "Use androidx.exifinterface.media.ExifInterface instead of platform ExifInterface:\n" +
                violations.joinToString(separator = "\n"),
            violations.isEmpty()
        )
    }

    @Test
    fun productionCodeAvoidsDeprecatedGenericBundleGetForDebugExtras() {
        val violations = productionKotlinFiles()
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    if (line.contains("intent.extras?.get(")) {
                        "${file.toRelativeSourcePath()}:${index + 1}: ${line.trim()}"
                    } else {
                        null
                    }
                }
            }
            .toList()

        assertTrue(
            "Debug intent parsing should use typed Bundle accessors instead of deprecated Bundle.get:\n" +
                violations.joinToString(separator = "\n"),
            violations.isEmpty()
        )
    }

    @Test
    fun productionKotlinSourceDoesNotContainMojibakeMarkers() {
        val markers = listOf("Ã", "â", "\uFFFD")
        val violations = productionKotlinFiles()
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    val marker = markers.firstOrNull { line.contains(it) }
                    if (marker != null) {
                        "${file.toRelativeSourcePath()}:${index + 1}: marker=$marker ${line.trim()}"
                    } else {
                        null
                    }
                }
            }
            .toList()

        assertTrue(
            "Production Kotlin source contains mojibake; keep source text readable:\n" +
                violations.joinToString(separator = "\n"),
            violations.isEmpty()
        )
    }

    @Test
    fun cameraViewModelDoesNotKeepDeadLegacyStorageFormatter() {
        val cameraViewModel = productionKotlinFiles()
            .firstOrNull { it.name == "CameraViewModel.kt" }
        assertTrue("Missing CameraViewModel.kt", cameraViewModel != null)

        val source = requireNotNull(cameraViewModel).readNormalizedText()

        assertTrue(
            "CameraViewModel should delegate storage labels to CinemaDisplayUtils; remove the dead legacy formatter.",
            !source.contains("formatStorageLabelLegacy")
        )
    }

    @Test
    fun customTouchViewsExposePerformClickForAccessibility() {
        val toneCurveView = productionKotlinFiles()
            .firstOrNull { it.name == "ToneCurveView.kt" }
        assertTrue("Missing ToneCurveView.kt", toneCurveView != null)

        val source = requireNotNull(toneCurveView).readNormalizedText()

        assertTrue(
            "ToneCurveView handles touch events and must override performClick for accessibility.",
            source.contains("override fun performClick()")
        )
    }

    @Test
    fun previewSurfaceViewExposesPerformClickForAccessibility() {
        val previewView = productionKotlinFiles()
            .firstOrNull { it.name == "CameraVulkanPreviewView.kt" }
        assertTrue("Missing CameraVulkanPreviewView.kt", previewView != null)

        val source = requireNotNull(previewView).readNormalizedText()

        assertTrue(
            "CameraVulkanPreviewView is a gesture target and must expose performClick for accessibility.",
            source.contains("override fun performClick(): Boolean")
        )
    }

    @Test
    fun mainActivityPreviewTouchReportsAccessibilityClickForTapToFocus() {
        val mainActivity = productionKotlinFiles()
            .firstOrNull { it.name == "MainActivity.kt" }
        assertTrue("Missing MainActivity.kt", mainActivity != null)

        val source = requireNotNull(mainActivity).readNormalizedText()

        assertTrue(
            "Preview touch listener should name the touched view so tap-to-focus can report performClick.",
            source.contains("View.OnTouchListener { touchedView, event ->")
        )
        assertTrue(
            "Preview touch finish should report whether tap-to-focus was handled.",
            source.contains("val tapToFocusHandled = finishTouchFocusGesture(event)")
        )
        assertTrue(
            "Tap-to-focus should call performClick exactly when the tap action is handled.",
            source.contains("if (tapToFocusHandled) touchedView.performClick()")
        )
    }

    @Test
    fun mainActivityScopesDecorativeTouchAccessibilitySuppressions() {
        val mainActivity = productionKotlinFiles()
            .firstOrNull { it.name == "MainActivity.kt" }
        assertTrue("Missing MainActivity.kt", mainActivity != null)

        val source = requireNotNull(mainActivity).readNormalizedText()

        assertTrue(
            "Mode selector touch rotation should be isolated behind a narrowly-scoped accessibility suppression.",
            source.contains("@SuppressLint(\"ClickableViewAccessibility\")\n    private fun installModeRotarySwipeControl()")
        )
        assertTrue(
            "Press feedback touch animation should keep its accessibility suppression scoped to the helper.",
            source.contains("@SuppressLint(\"ClickableViewAccessibility\")\n    private fun installPressFeedback(")
        )
    }

    @Test
    fun cameraRequestQualityProfileAvoidsDeadMinSdkBranches() {
        val profile = productionKotlinFiles()
            .firstOrNull { it.name == "CameraRequestQualityProfile.kt" }
        assertTrue("Missing CameraRequestQualityProfile.kt", profile != null)

        val source = requireNotNull(profile).readNormalizedText()

        assertTrue(
            "CameraRequestQualityProfile should not branch on APIs older than the app minSdk.",
            !source.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.P") &&
                !source.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.M")
        )
    }

    @Test
    fun mainActivityAvoidsDeadMinSdkGalleryAndHapticBranches() {
        val mainActivity = productionKotlinFiles()
            .firstOrNull { it.name == "MainActivity.kt" }
        assertTrue("Missing MainActivity.kt", mainActivity != null)

        val source = requireNotNull(mainActivity).readNormalizedText()

        assertTrue(
            "MainActivity should not keep pre-minSdk gallery or haptic fallbacks.",
            !source.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q") &&
                !source.contains("Build.VERSION.SDK_INT >= Build.VERSION_CODES.O")
        )
    }

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

    private fun File.readNormalizedText(): String = readText().replace("\r\n", "\n")
}
