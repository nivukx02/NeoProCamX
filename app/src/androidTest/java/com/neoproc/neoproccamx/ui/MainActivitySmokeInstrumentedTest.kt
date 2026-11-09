package com.neoproc.neoproccamx.ui

import android.Manifest
import android.os.SystemClock
import android.view.View
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.neoproc.neoproccamx.R
import com.neoproc.neoproccamx.camera.CameraState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class MainActivitySmokeInstrumentedTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    @Test
    fun mainCameraShowsOnlySupportedModeRailAndNoQuickZoomRow() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val expectedModeIds = listOf(
                R.id.btnModeProRail,
                R.id.btnModeHdrScene,
                R.id.btnModeLogVideo,
                R.id.btnModeRawPreset
            )
            // Wait for the camera UI to render before inspecting the mode rail.
            waitUntilVisible(scenario, R.id.glSurface)
            scenario.onActivity { activity ->
                // Single-slot ROTARY mode selector: each of these preset views EXISTS (a real rail,
                // not the removed legacy multi-button rail), and only the ACTIVE preset is VISIBLE
                // (MainActivity.visibleModeViews shows exactly the selected preset). The active preset
                // may be the plain VIDEO rail — which is none of these four — so the mode-agnostic
                // single-slot invariant is AT MOST ONE of them visible, never a multi-button rail, and
                // never a quick-zoom row.
                expectedModeIds.forEach { id -> assertNotNull(activity.findViewById<TextView>(id)) }
                val visibleModeCount = expectedModeIds.count { id ->
                    activity.findViewById<TextView>(id)?.visibility == View.VISIBLE
                }
                assertTrue(visibleModeCount <= 1)
                assertEquals(0, activity.resources.getIdentifier("quickZoomRow", "id", activity.packageName))
                assertEquals(0, activity.resources.getIdentifier("btnZoom1", "id", activity.packageName))
            }
        }
    }

    @Test
    fun primaryCameraControlsRenderInMainActivity() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitUntilVisible(scenario, R.id.glSurface)
            waitUntilVisible(scenario, R.id.btnShutter)
            scenario.onActivity { activity ->
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.glSurface).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.btnShutter).visibility)
                assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.btnSettings).visibility)
                // The gallery button is a CONTENT-GATED control: MainActivity sets it GONE on a fresh
                // session and only reveals it once a captured thumbnail exists. Assert it is present,
                // not that it is currently visible.
                assertNotNull(activity.findViewById<View>(R.id.btnGallery))
            }
        }
    }

    @Test
    fun primaryCameraControlsExposeStatefulLabelsAndMinimumTouchTargets() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitUntilVisible(scenario, R.id.btnShutter)
            scenario.onActivity { activity ->
                val minimumTargetPx = (48f * activity.resources.displayMetrics.density).roundToInt()
                val shutter = activity.findViewById<View>(R.id.btnShutter)
                val settings = activity.findViewById<View>(R.id.btnModePro)
                val photo = activity.findViewById<View>(R.id.btnPhotoCapture)

                listOf(shutter, settings, photo).forEach { target ->
                    assertTrue("Touch target ${target.id} is narrower than 48dp", target.width >= minimumTargetPx)
                    assertTrue("Touch target ${target.id} is shorter than 48dp", target.height >= minimumTargetPx)
                }
                assertTrue(shutter.isClickable)
                assertTrue(shutter.isFocusable)
                assertEquals(activity.getString(R.string.rec_start), shutter.contentDescription)
                assertTrue(settings.contentDescription.isNotBlank())
                assertTrue(photo.contentDescription.isNotBlank())
            }
        }
    }

    @Test
    fun returningFromBackgroundProducesFreshPreviewFramesWithoutFreeze() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var vm: CameraViewModel
            scenario.onActivity { activity -> vm = activity.vm }
            waitForStreaming(vm)

            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.onActivity { vm.camera.resetPreviewActivityTracking() }
            SystemClock.sleep(300L)
            scenario.moveToState(Lifecycle.State.RESUMED)

            waitForStreaming(vm, timeoutMs = 15_000L)
            val freshTimestampNs = runBlocking {
                withTimeout(5_000L) { vm.previewFrameTimestamps.first() }
            }
            assertTrue("Foreground recovery emitted an invalid preview timestamp", freshTimestampNs > 0L)
            assertTrue("Foreground preview activity remained frozen", vm.camera.hasRecentPreviewActivity())
        }
    }

    private fun waitUntilVisible(
        scenario: ActivityScenario<MainActivity>,
        viewId: Int,
        timeoutMs: Long = 5_000L
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var visible = false
        while (SystemClock.elapsedRealtime() < deadline && !visible) {
            scenario.onActivity { activity ->
                visible = activity.findViewById<View>(viewId)?.visibility == View.VISIBLE
            }
            if (!visible) {
                SystemClock.sleep(100L)
            }
        }
    }

    private fun waitForStreaming(vm: CameraViewModel, timeoutMs: Long = 10_000L) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (
                vm.cameraState.value == CameraState.Streaming &&
                !vm.camera.sessionRefreshPending.get() &&
                vm.camera.hasRecentPreviewActivity()
            ) {
                return
            }
            SystemClock.sleep(100L)
        }
        throw AssertionError("Camera did not resume fresh preview streaming: ${vm.cameraState.value}")
    }
}
