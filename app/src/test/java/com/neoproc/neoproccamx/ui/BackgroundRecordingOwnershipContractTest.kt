package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BackgroundRecordingOwnershipContractTest {
    @Test
    fun cameraViewModelIsProcessOwnedAndSurfaceLossDoesNotCloseActiveDataSession() {
        val app = File("src/main/java/com/neoproc/neoproccamx/NeoProCamXApp.kt").readText()
        val activity = File("src/main/java/com/neoproc/neoproccamx/ui/MainActivity.kt").readText()
        assertTrue(app.contains("Application(), ViewModelStoreOwner"))
        assertTrue(app.contains("override val viewModelStore: ViewModelStore"))
        assertTrue(activity.contains("ViewModelProvider(application as NeoProCamXApp"))
        val nullSurfaceBranch = activity.substringAfter("Preview Surface callback detached")
            .substringBefore("binding.glSurface.addOnLayoutChangeListener")
        assertFalse(nullSurfaceBranch.contains("vm.closeCamera()"))
    }

    @Test
    fun settingsAndConversionUseTheLiveProcessOwnedCameraViewModel() {
        val sheet = File("src/main/java/com/neoproc/neoproccamx/ui/CameraSettingsSheetFragment.kt").readText()
        val videoSettings = File("src/main/java/com/neoproc/neoproccamx/ui/controls/VideoSettingsFragment.kt").readText()

        assertTrue(videoSettings.contains("(requireActivity() as MainActivity).vm"))
        assertTrue(sheet.contains("(activity as? CameraSettingsLifecycleHost)"))
        listOf(sheet, videoSettings).forEach { source ->
            assertFalse(source.contains("import androidx.fragment.app.activityViewModels"))
            assertFalse(source.contains("by activityViewModels()"))
        }
    }
}
