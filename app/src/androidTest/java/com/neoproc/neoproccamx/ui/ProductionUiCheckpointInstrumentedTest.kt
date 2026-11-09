package com.neoproc.neoproccamx.ui

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.neoproc.neoproccamx.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Device-owned evidence for production UI checkpoints CP-051 through CP-055.
 *
 * These assertions intentionally inspect the rendered hierarchy and resolved drawables. Source-text
 * checks alone cannot prove that the installed APK inflated a single dock, resolved the intended
 * colours, or returned every animated surface to its layout-owned position.
 */
@RunWith(AndroidJUnit4::class)
class ProductionUiCheckpointInstrumentedTest {

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.CAMERA,
        Manifest.permission.RECORD_AUDIO
    )

    @Test
    fun cp051_cp054_cp055_homeDocksAreUniqueFlatAndPositionStable() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForHomeDocksSettled(scenario)
            val before = readDockSnapshot(scenario)
            SystemClock.sleep(STABILITY_WINDOW_MS)
            val after = readDockSnapshot(scenario)

            assertDockSnapshotStable(before, after)
            scenario.onActivity { activity ->
                val root = activity.findViewById<ViewGroup>(R.id.rootLayout)
                val topDock = activity.findViewById<View>(R.id.topStatusPanel)
                val modeRail = activity.findViewById<View>(R.id.modeGroup)
                val bottomDock = activity.findViewById<View>(R.id.bottomBar)

                assertEquals("CP-051: duplicate top dock", 1, countViewsWithId(root, R.id.topStatusPanel))
                assertEquals("CP-051: duplicate mode rail", 1, countViewsWithId(root, R.id.modeGroup))
                assertEquals("CP-051: duplicate bottom dock", 1, countViewsWithId(root, R.id.bottomBar))
                assertEquals(View.VISIBLE, topDock.visibility)
                assertEquals(View.VISIBLE, modeRail.visibility)
                assertEquals(View.VISIBLE, bottomDock.visibility)

                listOf(topDock, modeRail, bottomDock).forEach { view ->
                    assertSettledTwoDimensionalSurface(view, allowLayoutOwnedTranslationY = true)
                    assertEquals(
                        "CP-055: ${resourceName(view)} retained its entrance hardware layer",
                        View.LAYER_TYPE_NONE,
                        view.layerType
                    )
                    assertFalse(
                        "CP-054: ${resourceName(view)} still resolves a layered/depth background",
                        view.background?.current is LayerDrawable
                    )
                }

                assertTrue("CP-051: top dock escaped the activity viewport", isInsideRoot(topDock, root))
                assertTrue("CP-051: mode rail escaped the activity viewport", isInsideRoot(modeRail, root))
                assertTrue("CP-051: bottom dock escaped the activity viewport", isInsideRoot(bottomDock, root))

                val publicModeViews = listOf(
                    activity.findViewById<TextView>(R.id.btnModeVideoRail),
                    activity.findViewById<TextView>(R.id.btnModeProRail),
                    activity.findViewById<TextView>(R.id.btnModeRawPreset)
                )
                assertEquals(
                    "CP-051: the single-slot mode rail rendered more than one active label",
                    1,
                    publicModeViews.count { it.visibility == View.VISIBLE }
                )
                assertEquals(View.GONE, activity.findViewById<View>(R.id.btnModeHdrScene).visibility)
                assertEquals(View.GONE, activity.findViewById<View>(R.id.btnModeLogVideo).visibility)
            }
        }
    }

    @Test
    fun cp052_cp053_cp055_richWhiteVioletLabelsAndSettingsMotionResolveAtRuntime() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForHomeDocksSettled(scenario)
            scenario.onActivity { activity ->
                val richWhiteSurface = ContextCompat.getColor(activity, R.color.glass_flat_surface)
                val richWhiteButton = ContextCompat.getColor(activity, R.color.glass_flat_button)
                val richWhiteBorder = ContextCompat.getColor(activity, R.color.glass_border)
                val violet = ContextCompat.getColor(activity, R.color.accent_violet)

                assertEquals("CP-052: rich-white dock fill changed", 0x58FFFFFF, richWhiteSurface)
                assertEquals("CP-052: rich-white button fill changed", 0x60FFFFFF, richWhiteButton)
                assertEquals("CP-052: rich-white border changed", 0x94FFFFFF.toInt(), richWhiteBorder)
                assertEquals("CP-052: violet accent changed", 0xFFB46CFF.toInt(), violet)
                assertEquals(
                    "CP-052: a legacy yellow reference no longer resolves to the violet product accent",
                    violet,
                    ContextCompat.getColor(activity, R.color.accent_yellow)
                )

                assertEquals(
                    "CP-052: top dock did not render the translucent rich-white fill",
                    richWhiteSurface,
                    renderedCenterColor(activity.findViewById(R.id.topStatusPanel))
                )
                assertEquals(
                    "CP-052: bottom dock did not render the translucent rich-white fill",
                    richWhiteSurface,
                    renderedCenterColor(activity.findViewById(R.id.bottomBar))
                )
                assertEquals(
                    "CP-052: dock button did not render the translucent rich-white fill",
                    richWhiteButton,
                    renderedCenterColor(activity.findViewById(R.id.btnPhotoCapture))
                )

                assertEquals("Video", activity.getString(R.string.mode_video))
                assertEquals("Pro", activity.getString(R.string.mode_pro))
                assertEquals("Raw", activity.getString(R.string.mode_raw))
                assertEquals("XDR", activity.getString(R.string.mode_hdr))
                assertEquals("Video", activity.findViewById<TextView>(R.id.btnModeVideoRail).text.toString())
                assertEquals("Pro", activity.findViewById<TextView>(R.id.btnModeProRail).text.toString())
                assertEquals("Raw", activity.findViewById<TextView>(R.id.btnModeRawPreset).text.toString())
                assertEquals("XDR", activity.findViewById<TextView>(R.id.btnModeHdrScene).text.toString())
                assertEquals(
                    listOf(CameraModePreset.VIDEO, CameraModePreset.PRO, CameraModePreset.RAW_MODE),
                    CameraModePresetOrderPolicy.videoRailOrder()
                )

                activity.findViewById<View>(R.id.btnModePro).performClick()
            }

            waitForSettingsSettled(scenario)
            scenario.onActivity { activity ->
                val sheet = activity.supportFragmentManager
                    .findFragmentByTag(CameraSettingsSheetFragment.TAG) as? CameraSettingsSheetFragment
                    ?: throw AssertionError("CP-055: settings sheet did not open")
                val settingsRoot = sheet.requireView() as ViewGroup
                val header = settingsRoot.getChildAt(0)
                val richWhiteSurface = ContextCompat.getColor(activity, R.color.glass_flat_surface)
                val violet = ContextCompat.getColor(activity, R.color.accent_violet)

                assertEquals(
                    "CP-052: settings header did not render the same rich-white surface family",
                    richWhiteSurface,
                    renderedCenterColor(header)
                )
                assertSettledTwoDimensionalSurface(header)

                val allViews = flatten(settingsRoot)
                val sectionDocks = allViews.filter { view ->
                    view is LinearLayout && renderedCenterColorOrNull(view) == richWhiteSurface
                }
                assertTrue("CP-052: no rich-white settings section docks were rendered", sectionDocks.isNotEmpty())
                sectionDocks.forEach { section ->
                    assertSettledTwoDimensionalSurface(section)
                    assertEquals(
                        "CP-055: settings entrance animation retained a hardware layer",
                        View.LAYER_TYPE_NONE,
                        section.layerType
                    )
                }

                val labels = allViews.filterIsInstance<TextView>().map { it.text.toString() }
                val leakedNdrLabels = labels.filter { NDR_UI_TOKEN.containsMatchIn(it) }
                assertTrue(
                    "CP-053: internal NDR naming leaked into visible settings labels: $leakedNdrLabels",
                    leakedNdrLabels.isEmpty()
                )
                assertTrue("CP-053: XDR product naming was not rendered", labels.any { it.contains("XDR") })
                assertTrue(
                    "CP-052: settings did not render any violet value/accent text",
                    allViews.filterIsInstance<TextView>().any { it.currentTextColor == violet }
                )
            }
        }
    }

    private fun waitForHomeDocksSettled(scenario: ActivityScenario<MainActivity>) {
        var lastState = "unobserved"
        waitUntil(scenario, HOME_TIMEOUT_MS, { "home docks did not settle: $lastState" }) { activity ->
            val docks = listOf(
                activity.findViewById<View>(R.id.topStatusPanel),
                activity.findViewById<View>(R.id.modeGroup),
                activity.findViewById<View>(R.id.bottomBar)
            )
            lastState = docks.joinToString(separator = "; ") { view ->
                "${resourceName(view)}[laidOut=${view.isLaidOut},visibility=${view.visibility}," +
                    "alpha=${view.alpha},scale=${view.scaleX}x${view.scaleY}," +
                    "translation=${view.translationX}x${view.translationY}," +
                    "animation=${view.animation != null},layer=${view.layerType}]"
            }
            docks.all { view ->
                view.isLaidOut && view.visibility == View.VISIBLE && view.alpha >= 0.999f &&
                    nearlyEqual(view.scaleX, 1f) && nearlyEqual(view.scaleY, 1f) &&
                    view.layerType == View.LAYER_TYPE_NONE
            }
        }
    }

    private fun waitForSettingsSettled(scenario: ActivityScenario<MainActivity>) {
        var lastState = "unobserved"
        waitUntil(scenario, SETTINGS_TIMEOUT_MS, { "settings entrance animation did not settle: $lastState" }) { activity ->
            val sheet = activity.supportFragmentManager
                .findFragmentByTag(CameraSettingsSheetFragment.TAG) as? CameraSettingsSheetFragment
            val root = sheet?.view as? ViewGroup
            val header = root?.getChildAt(0)
            val richWhiteSurface = ContextCompat.getColor(activity, R.color.glass_flat_surface)
            val sectionDocks = root?.let(::flatten)
                .orEmpty()
                .filterIsInstance<LinearLayout>()
                .filter { view -> renderedCenterColorOrNull(view) == richWhiteSurface }
            lastState = "dialog=${sheet?.dialog?.isShowing},headerLaidOut=${header?.isLaidOut}," +
                "headerAlpha=${header?.alpha},headerLayer=${header?.layerType}," +
                "sections=${sectionDocks.size},activeSections=" +
                sectionDocks.count { view ->
                    view.layerType == View.LAYER_TYPE_HARDWARE || view.alpha < 0.999f ||
                        !nearlyEqual(view.translationY, 0f) || !nearlyEqual(view.scaleX, 1f) ||
                        !nearlyEqual(view.scaleY, 1f)
                }
            sheet?.dialog?.isShowing == true && header?.isLaidOut == true &&
                header.alpha >= 0.999f && nearlyEqual(header.translationY, 0f) &&
                nearlyEqual(header.scaleX, 1f) && nearlyEqual(header.scaleY, 1f) &&
                sectionDocks.isNotEmpty() && sectionDocks.none { view ->
                    view.layerType == View.LAYER_TYPE_HARDWARE || view.alpha < 0.999f ||
                        !nearlyEqual(view.translationY, 0f) || !nearlyEqual(view.scaleX, 1f) ||
                        !nearlyEqual(view.scaleY, 1f)
                }
        }
    }

    private fun waitUntil(
        scenario: ActivityScenario<MainActivity>,
        timeoutMs: Long,
        failure: () -> String,
        predicate: (MainActivity) -> Boolean
    ) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            var satisfied = false
            scenario.onActivity { activity -> satisfied = predicate(activity) }
            if (satisfied) return
            SystemClock.sleep(POLL_INTERVAL_MS)
        }
        throw AssertionError(failure())
    }

    private fun readDockSnapshot(scenario: ActivityScenario<MainActivity>): DockSnapshot {
        lateinit var snapshot: DockSnapshot
        scenario.onActivity { activity ->
            snapshot = DockSnapshot(
                top = visualBounds(activity.findViewById(R.id.topStatusPanel)),
                mode = visualBounds(activity.findViewById(R.id.modeGroup)),
                bottom = visualBounds(activity.findViewById(R.id.bottomBar))
            )
        }
        return snapshot
    }

    private fun assertDockSnapshotStable(before: DockSnapshot, after: DockSnapshot) {
        assertRectStable("CP-055: top dock displaced after its animation", before.top, after.top)
        assertRectStable("CP-055: mode rail displaced after its animation", before.mode, after.mode)
        assertRectStable("CP-055: bottom dock displaced after its animation", before.bottom, after.bottom)
    }

    private fun assertRectStable(message: String, before: Rect, after: Rect) {
        assertTrue("$message before=$before after=$after", kotlin.math.abs(before.left - after.left) <= 1)
        assertTrue("$message before=$before after=$after", kotlin.math.abs(before.top - after.top) <= 1)
        assertTrue("$message before=$before after=$after", kotlin.math.abs(before.right - after.right) <= 1)
        assertTrue("$message before=$before after=$after", kotlin.math.abs(before.bottom - after.bottom) <= 1)
    }

    private fun assertSettledTwoDimensionalSurface(
        view: View,
        allowLayoutOwnedTranslationY: Boolean = false
    ) {
        assertEquals("${resourceName(view)} has residual elevation", 0f, view.elevation, FLOAT_EPSILON)
        assertEquals("${resourceName(view)} has residual Z translation", 0f, view.translationZ, FLOAT_EPSILON)
        assertEquals("${resourceName(view)} has residual X-axis rotation", 0f, view.rotationX, FLOAT_EPSILON)
        assertEquals("${resourceName(view)} has residual Y-axis rotation", 0f, view.rotationY, FLOAT_EPSILON)
        assertEquals("${resourceName(view)} has residual Z-axis rotation", 0f, view.rotation, FLOAT_EPSILON)
        assertEquals("${resourceName(view)} has residual X displacement", 0f, view.translationX, FLOAT_EPSILON)
        if (allowLayoutOwnedTranslationY) {
            // Home-dock Y is deliberately owned by CameraDockLayoutPolicy so the docks remain
            // anchored to the resolved preview rectangle. The two snapshots above prove that this
            // coordinate is stable; only animation-owned settings surfaces must settle back to 0.
            assertTrue(
                "${resourceName(view)} has a non-finite layout-owned Y displacement",
                view.translationY.isFinite()
            )
        } else {
            assertEquals("${resourceName(view)} has residual Y displacement", 0f, view.translationY, FLOAT_EPSILON)
        }
        assertEquals("${resourceName(view)} did not settle to 1x scale", 1f, view.scaleX, FLOAT_EPSILON)
        assertEquals("${resourceName(view)} did not settle to 1x scale", 1f, view.scaleY, FLOAT_EPSILON)
        assertEquals("${resourceName(view)} did not settle to full opacity", 1f, view.alpha, FLOAT_EPSILON)
    }

    private fun countViewsWithId(view: View, id: Int): Int {
        var count = if (view.id == id) 1 else 0
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) count += countViewsWithId(view.getChildAt(index), id)
        }
        return count
    }

    private fun flatten(root: View): List<View> {
        val output = ArrayList<View>()
        fun visit(view: View) {
            output += view
            if (view is ViewGroup) {
                for (index in 0 until view.childCount) visit(view.getChildAt(index))
            }
        }
        visit(root)
        return output
    }

    private fun isInsideRoot(view: View, root: View): Boolean {
        val viewBounds = visualBounds(view)
        val rootBounds = visualBounds(root)
        return rootBounds.contains(viewBounds)
    }

    private fun visualBounds(view: View): Rect {
        val location = IntArray(2)
        view.getLocationOnScreen(location)
        return Rect(location[0], location[1], location[0] + view.width, location[1] + view.height)
    }

    private fun renderedCenterColor(view: View): Int =
        renderedCenterColorOrNull(view)
            ?: throw AssertionError("${resourceName(view)} has no drawable background")

    private fun renderedCenterColorOrNull(view: View): Int? {
        val drawable = view.background?.current ?: return null
        return renderedCenterColor(drawable)
    }

    private fun renderedCenterColor(drawable: Drawable): Int {
        val priorBounds = Rect(drawable.bounds)
        val bitmap = Bitmap.createBitmap(DRAWABLE_SAMPLE_SIZE, DRAWABLE_SAMPLE_SIZE, Bitmap.Config.ARGB_8888)
        return try {
            drawable.setBounds(0, 0, bitmap.width, bitmap.height)
            drawable.draw(Canvas(bitmap))
            bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        } finally {
            drawable.bounds = priorBounds
            bitmap.recycle()
        }
    }

    private fun resourceName(view: View): String {
        // Settings docks are programmatic and intentionally have View.NO_ID. Asking Resources to
        // resolve 0xffffffff succeeds only by throwing and also emits noisy framework error logs.
        if (view.id == View.NO_ID) return view.javaClass.simpleName
        return runCatching { view.resources.getResourceEntryName(view.id) }
            .getOrDefault(view.javaClass.simpleName)
    }

    private fun nearlyEqual(left: Float, right: Float): Boolean =
        kotlin.math.abs(left - right) <= FLOAT_EPSILON

    private data class DockSnapshot(
        val top: Rect,
        val mode: Rect,
        val bottom: Rect
    )

    private companion object {
        val NDR_UI_TOKEN = Regex("\\bNDR(?:10(?:\\+|[_ -]*PLUS)?)?\\b", RegexOption.IGNORE_CASE)
        const val DRAWABLE_SAMPLE_SIZE = 64
        const val FLOAT_EPSILON = 0.001f
        const val POLL_INTERVAL_MS = 75L
        const val STABILITY_WINDOW_MS = 650L
        const val HOME_TIMEOUT_MS = 12_000L
        const val SETTINGS_TIMEOUT_MS = 10_000L
    }
}
