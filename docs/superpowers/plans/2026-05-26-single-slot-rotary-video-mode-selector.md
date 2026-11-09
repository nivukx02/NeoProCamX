# Single-Slot Rotary Video Mode Selector Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the expanded video-mode rail with a swipe-controlled single active slot above the fixed shutter and restrict visible recording resolution choices to supported `1K`, `2K`, `4K`, and `8K` tiers.

**Architecture:** Keep `CameraModePresetOrderPolicy` and `MainActivity.activateModePreset()` as the behavioral sources of truth. Add one pure swipe-to-step policy for testable gesture interpretation, make `MainActivity` route gestures from the mode control rather than the preview, and collapse the existing retained mode views to a single visible selected view. Filter `SettingsOptionCatalog` device stream candidates into explicit named tiers while preferring canonical sizes.

**Tech Stack:** Kotlin, Android XML/ViewBinding, JUnit 4, Gradle Android unit tests, ADB device validation.

---

## File Map

- Create `app/src/main/java/com/neoproc/neoproccamx/ui/CameraModeRotarySwipePolicy.kt`: pure threshold/direction mapping for the rotary mode gesture.
- Create `app/src/test/java/com/neoproc/neoproccamx/ui/CameraModeRotarySwipePolicyTest.kt`: direction and no-op threshold regression tests.
- Modify `app/src/main/java/com/neoproc/neoproccamx/ui/MainActivity.kt`: install selector-only swipes, remove preview mode navigation ownership, show only the selected mode view, retain existing activation and haptics.
- Modify `app/src/main/res/layout/activity_main.xml`: render retained mode text views in one centered slot without changing shutter layout.
- Modify `app/src/test/java/com/neoproc/neoproccamx/ui/MainActivityUnifiedUiStateContractTest.kt`: replace expanded-five-label assertions with single-slot and selector-gesture contract assertions.
- Modify `app/src/test/java/com/neoproc/neoproccamx/quality/ProductionSourceHygieneTest.kt`: track the renamed, narrowly suppressed selector touch helper.
- Modify `app/src/main/java/com/neoproc/neoproccamx/ui/CameraModeRailMotionPolicy.kt`: animate wraparound direction consistently with cyclic navigation.
- Modify `app/src/test/java/com/neoproc/neoproccamx/ui/CameraModeRailMotionPolicyTest.kt`: prove forward and reverse wrap motion direction.
- Modify `app/src/main/java/com/neoproc/neoproccamx/ui/controls/SettingsOptionCatalog.kt`: expose tier labels only for supported recognized sizes.
- Modify `app/src/test/java/com/neoproc/neoproccamx/ui/controls/SettingsOptionCatalogTest.kt`: verify tier-only labels, canonical preference, and exclusion of unsupported extras.

Because these production files already contain unstaged work needed by the current deployed app, implementation validation will avoid committing their combined contents automatically. The plan and design documents are isolated commits; source edits remain reviewable in the shared worktree unless the user requests a scoped integration commit.

### Task 1: Tier-Only Resolution Options

**Files:**
- Modify: `app/src/test/java/com/neoproc/neoproccamx/ui/controls/SettingsOptionCatalogTest.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/controls/SettingsOptionCatalog.kt`

- [ ] **Step 1: Write failing device-capability filtering tests**

Update the device-stream test and add canonical selection coverage:

```kotlin
@Test
fun `active camera resolution choices expose named supported tiers only`() {
    val labels = SettingsOptionCatalog.resolutionLabelsForMode(
        mode = CinemaVideoMode.PRO,
        supportedOutputDimensions = listOf(
            RecordingDimensions(7680, 4320),
            RecordingDimensions(6144, 3456),
            RecordingDimensions(4096, 3072),
            RecordingDimensions(3840, 2160),
            RecordingDimensions(1920, 1080),
            RecordingDimensions(1280, 720)
        )
    )

    assertEquals(listOf("8K", "4K", "2K", "1K"), labels)
    assertFalse(labels.any { "x" in it || it.startsWith("6K") })
}

@Test
fun `tier filtering prefers canonical output size when device reports variants`() {
    val labels = SettingsOptionCatalog.resolutionLabelsForMode(
        mode = CinemaVideoMode.PRO,
        supportedOutputDimensions = listOf(
            RecordingDimensions(4096, 3072),
            RecordingDimensions(3840, 2160)
        )
    )
    val selected = SettingsOptionCatalog.selectedResolutionDimensionsOrDefault(
        currentSize = RecordingDimensions(4096, 3072),
        mode = CinemaVideoMode.PRO,
        supportedOutputDimensions = listOf(
            RecordingDimensions(4096, 3072),
            RecordingDimensions(3840, 2160)
        )
    )

    assertEquals(listOf("4K"), labels)
    assertEquals(RecordingDimensions(3840, 2160), selected)
}
```

- [ ] **Step 2: Run the focused catalog test to verify RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.ui.controls.SettingsOptionCatalogTest" --no-daemon --console=plain
```

Expected: FAIL because `4096x3072` and/or non-tier candidates are still returned as labels.

- [ ] **Step 3: Implement explicit tier mapping and canonical preference**

In `SettingsOptionCatalog`, replace raw dimension labeling for device-reported rows with a tier resolver:

```kotlin
private fun normalizedDimensions(dimensions: RecordingDimensions): RecordingDimensions =
    if (dimensions.width >= dimensions.height) dimensions
    else RecordingDimensions(dimensions.height, dimensions.width)

private fun resolutionTierLabel(dimensions: RecordingDimensions): String? = when (dimensions.width) {
    7680, 8192 -> "8K"
    3840, 4096 -> "4K"
    1920, 2048 -> "2K"
    1280 -> "1K"
    else -> null
}

private fun resolutionDimensionChoicesForMode(
    mode: CinemaVideoMode,
    supportedOutputDimensions: List<RecordingDimensions>
): List<Pair<String, RecordingDimensions>> {
    if (supportedOutputDimensions.isEmpty()) return standardResolutionDimensionChoices
    val candidatesByTier = supportedOutputDimensions
        .map(::normalizedDimensions)
        .filter { it.width > 0 && it.height > 0 }
        .distinct()
        .mapNotNull { dimensions -> resolutionTierLabel(dimensions)?.let { it to dimensions } }
        .groupBy({ it.first }, { it.second })

    return standardResolutionDimensionChoices.mapNotNull { (label, canonical) ->
        val candidates = candidatesByTier[label].orEmpty()
        if (candidates.isEmpty()) return@mapNotNull null
        val selected = candidates.firstOrNull { it == canonical }
            ?: candidates.maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: return@mapNotNull null
        label to selected
    }
}
```

- [ ] **Step 4: Run the catalog tests to verify GREEN**

Run the command from Step 2.

Expected: PASS with visible choices limited to named tiers.

### Task 2: Pure Rotary Swipe And Cyclic Motion Policies

**Files:**
- Create: `app/src/test/java/com/neoproc/neoproccamx/ui/CameraModeRotarySwipePolicyTest.kt`
- Create: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraModeRotarySwipePolicy.kt`
- Modify: `app/src/test/java/com/neoproc/neoproccamx/ui/CameraModeRailMotionPolicyTest.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraModeRailMotionPolicy.kt`

- [ ] **Step 1: Write failing swipe-policy tests**

```kotlin
package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraModeRotarySwipePolicyTest {
    @Test
    fun `left swipe advances hidden cyclic selector`() {
        assertEquals(1, CameraModeRotarySwipePolicy.resolveStep(180f, 80f, 48f))
    }

    @Test
    fun `right swipe reverses hidden cyclic selector`() {
        assertEquals(-1, CameraModeRotarySwipePolicy.resolveStep(80f, 180f, 48f))
    }

    @Test
    fun `short movement does not rotate mode`() {
        assertEquals(0, CameraModeRotarySwipePolicy.resolveStep(100f, 120f, 48f))
    }
}
```

Append cyclic animation tests:

```kotlin
@Test
fun transitionMotion_treatsRawToVideoAsForwardWrap() {
    val order = CameraModePresetOrderPolicy.videoRailOrder()
    assertEquals(
        1,
        CameraModeRailMotionPolicy.transitionMotion(CameraModePreset.RAW_MODE, CameraModePreset.VIDEO, order).direction
    )
}

@Test
fun transitionMotion_treatsVideoToRawAsReverseWrap() {
    val order = CameraModePresetOrderPolicy.videoRailOrder()
    assertEquals(
        -1,
        CameraModeRailMotionPolicy.transitionMotion(CameraModePreset.VIDEO, CameraModePreset.RAW_MODE, order).direction
    )
}
```

- [ ] **Step 2: Run the policy tests to verify RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.ui.CameraModeRotarySwipePolicyTest" --tests "com.neoproc.neoproccamx.ui.CameraModeRailMotionPolicyTest" --no-daemon --console=plain
```

Expected: FAIL because `CameraModeRotarySwipePolicy` does not exist and wrap motion uses ordinary index comparison.

- [ ] **Step 3: Add minimal swipe policy and cyclic wrap motion**

Create:

```kotlin
package com.neoproc.neoproccamx.ui

import kotlin.math.abs

internal object CameraModeRotarySwipePolicy {
    fun resolveStep(downX: Float, upX: Float, minimumDistancePx: Float): Int {
        val deltaX = upX - downX
        if (abs(deltaX) < minimumDistancePx.coerceAtLeast(0f)) return 0
        return if (deltaX < 0f) 1 else -1
    }
}
```

Change the transition direction calculation:

```kotlin
val direction = if (previousIndex >= 0 && nextIndex >= 0 && order.isNotEmpty()) {
    when {
        previousIndex == order.lastIndex && nextIndex == 0 -> 1
        previousIndex == 0 && nextIndex == order.lastIndex -> -1
        nextIndex > previousIndex -> 1
        nextIndex < previousIndex -> -1
        else -> 0
    }
} else {
    0
}
```

- [ ] **Step 4: Run policy tests to verify GREEN**

Run the command from Step 2.

Expected: PASS.

### Task 3: Single Visible Mode Slot And Selector Gesture Ownership

**Files:**
- Modify: `app/src/test/java/com/neoproc/neoproccamx/ui/MainActivityUnifiedUiStateContractTest.kt`
- Modify: `app/src/test/java/com/neoproc/neoproccamx/quality/ProductionSourceHygieneTest.kt`
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/MainActivity.kt`

- [ ] **Step 1: Replace expanded-row contract tests with the approved single-slot contract**

Replace `videoModeRailShowsAllFiveModesWithoutCarouselCentering` with:

```kotlin
@Test
fun videoModeSelectorShowsOnlyTheActiveCenteredSlotAndUsesSwipeRotation() {
    val selectorLayout = layout.substringAfter("""android:id="@+id/modeRailContent"""")
        .substringBefore("</FrameLayout>")
    assertTrue(selectorLayout.contains("""android:layout_gravity="center""""))
    assertTrue(selectorLayout.windowed("""android:visibility="gone"""".length)
        .count { it == """android:visibility="gone"""" } == 4)
    assertTrue(source.contains("private fun installModeRotarySwipeControl()"))
    assertTrue(source.contains("CameraModeRotarySwipePolicy.resolveStep("))
    assertTrue(source.contains("view.visibility = if (selected) View.VISIBLE else View.GONE"))
    assertFalse(source.contains("activateCenteredModePresetFromRail"))
}

@Test
fun previewGesturesDoNotRotateRecordingModes() {
    val gestures = source.substringAfter("private fun setupGestures()")
        .substringBefore("private fun installPreviewTouchListener")
    assertFalse(gestures.contains("shiftModePreset("))
}
```

Update the locked-controls assertion to:

```kotlin
assertTrue(source.contains("binding.modeRailScroll.isEnabled = visible"))
```

In `ProductionSourceHygieneTest`, change the helper assertion to:

```kotlin
source.contains("@SuppressLint(\"ClickableViewAccessibility\")\n    private fun installModeRotarySwipeControl()")
```

- [ ] **Step 2: Run UI contract tests to verify RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.ui.MainActivityUnifiedUiStateContractTest" --tests "com.neoproc.neoproccamx.quality.ProductionSourceHygieneTest" --no-daemon --console=plain
```

Expected: FAIL because the layout remains an expanded row and the preview still owns swipe mode changes.

- [ ] **Step 3: Collapse XML presentation into one centered slot**

Retain existing IDs for state compatibility, but replace the `HorizontalScrollView` and horizontal weighted `LinearLayout` with centered `FrameLayout` containers:

```xml
<FrameLayout
    android:id="@+id/modeRailScroll"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:clickable="true"
    android:focusable="true">

    <FrameLayout
        android:id="@+id/modeRailContent"
        android:layout_width="match_parent"
        android:layout_height="@dimen/mode_rail_height">

        <TextView
            android:id="@+id/btnModeVideoRail"
            style="@style/ModeRailText"
            android:layout_gravity="center"
            android:text="@string/mode_video" />

        <TextView
            android:id="@+id/btnModeProRail"
            style="@style/ModeRailText"
            android:layout_gravity="center"
            android:visibility="gone"
            android:text="@string/mode_pro" />

        <TextView
            android:id="@+id/btnModeHdrScene"
            style="@style/ModeRailText"
            android:layout_gravity="center"
            android:visibility="gone"
            android:text="@string/mode_hdr" />

        <TextView
            android:id="@+id/btnModeLogVideo"
            style="@style/ModeRailText"
            android:layout_gravity="center"
            android:visibility="gone"
            android:text="@string/profile_log" />

        <TextView
            android:id="@+id/btnModeRawPreset"
            style="@style/ModeRailText"
            android:layout_gravity="center"
            android:visibility="gone"
            android:text="@string/control_raw" />
    </FrameLayout>
</FrameLayout>
```

- [ ] **Step 4: Move horizontal navigation into the selector and render one item**

In `setupControls()`, invoke `installModeRotarySwipeControl()` and remove direct visible-row button assumptions. Add selector state:

```kotlin
private const val MODE_ROTARY_SWIPE_DISTANCE_DP = 48f
private var modeSelectorTouchDownX: Float? = null
```

Add the gesture helper:

```kotlin
@SuppressLint("ClickableViewAccessibility")
private fun installModeRotarySwipeControl() {
    val minimumDistancePx = dp(MODE_ROTARY_SWIPE_DISTANCE_DP)
    binding.modeRailScroll.setOnTouchListener { view, event ->
        if (!view.isEnabled) return@setOnTouchListener false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                modeSelectorTouchDownX = event.x
                true
            }
            MotionEvent.ACTION_UP -> {
                val step = modeSelectorTouchDownX?.let { startX ->
                    CameraModeRotarySwipePolicy.resolveStep(startX, event.x, minimumDistancePx)
                } ?: 0
                modeSelectorTouchDownX = null
                if (step != 0) {
                    lastGestureNavigateMs = SystemClock.elapsedRealtime()
                    performCameraHaptic(CameraHapticAction.MODE_SWITCH)
                    shiftModePreset(step)
                } else {
                    view.performClick()
                }
                true
            }
            MotionEvent.ACTION_CANCEL -> {
                modeSelectorTouchDownX = null
                true
            }
            else -> true
        }
    }
}
```

Remove preview-mode navigation from `setupGestures().onFling()` and remove centered scroll-selection state/helpers. In `renderPresetSelection()` render one item:

```kotlin
modeViews.forEach { (view, preset) ->
    val selected = activeModePreset == preset
    view.visibility = if (selected) View.VISIBLE else View.GONE
    view.setBackgroundResource(
        if (selected) R.drawable.bg_mode_rail_active else R.drawable.bg_mode_rail_idle
    )
    view.setTextColor(if (selected) selectedColor else defaultColor)
    view.typeface = Typeface.create(
        "sans-serif-medium",
        if (selected) Typeface.BOLD else Typeface.NORMAL
    )
}
```

- [ ] **Step 5: Run UI contract tests to verify GREEN**

Run the command from Step 2.

Expected: PASS.

### Task 4: Integrated Test, Build, And Device Validation

**Files:**
- Verify modified files only; do not add unrelated working-tree files.

- [ ] **Step 1: Run focused regression tests together**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.ui.controls.SettingsOptionCatalogTest" --tests "com.neoproc.neoproccamx.ui.CameraModeRotarySwipePolicyTest" --tests "com.neoproc.neoproccamx.ui.CameraModeRailMotionPolicyTest" --tests "com.neoproc.neoproccamx.ui.MainActivityUnifiedUiStateContractTest" --tests "com.neoproc.neoproccamx.quality.ProductionSourceHygieneTest" --no-daemon --console=plain
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Run full unit tests and build APK**

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon --rerun-tasks --console=plain
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain
```

Expected: both commands finish with `BUILD SUCCESSFUL`.

- [ ] **Step 3: Install and launch on the connected phone**

```powershell
$adb = '.android-home\sdk-view\platform-tools\adb.exe'
& $adb devices -l
& $adb -s 10BF8M2NDX003P3 install -r -t 'app\build\outputs\apk\debug\app-debug.apk'
& $adb -s 10BF8M2NDX003P3 logcat -c
& $adb -s 10BF8M2NDX003P3 shell am force-stop com.neoproc.neoproccamx
& $adb -s 10BF8M2NDX003P3 shell am start -W -n com.neoproc.neoproccamx/com.neoproc.neoproccamx.ui.MainActivity
```

Expected: installation reports `Success` and activity launch reports `Status: ok`.

- [ ] **Step 4: Validate the one-slot selector and repeated swipe cycle**

Use `adb shell input swipe` over the selector y-coordinate and pull UI hierarchies after each swipe:

```powershell
& $adb -s 10BF8M2NDX003P3 shell uiautomator dump /sdcard/mode-video.xml
& $adb -s 10BF8M2NDX003P3 shell input swipe 740 1815 340 1815 180
& $adb -s 10BF8M2NDX003P3 shell uiautomator dump /sdcard/mode-pro.xml
```

Repeat left swipes to observe `VIDEO`, `PRO`, `NDR`, `LOG`, `RAW`, and `VIDEO` again. Compare the `btnShutter` node bounds from all dumps and confirm they remain identical. Pull a screenshot to confirm one mode label and the black backing are visible.

- [ ] **Step 5: Report verification evidence and scope**

State test/build/install/launch results, the observed cyclic label sequence, and the unchanged shutter bounds. Mention that no unrelated dirty working-tree changes were reset or folded into the task.
