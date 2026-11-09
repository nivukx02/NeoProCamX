# Premium Camera Experience Upgrade Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade the existing NeoProCamX camera UI experience with smoother interactions, consistent haptics, safer animations, reduced chrome render churn, and device verification without redesigning the existing UI.

**Architecture:** Add small testable UI policy classes for haptics, motion, render diffing, and render scheduling. Wire those policies into `MainActivity` with minimal changes so existing mode rail, HDR/LOG/RAW behavior, recording flow, and layout stay intact. Validate with unit tests, lint, build, adb launch, logcat, and `gfxinfo` evidence.

**Tech Stack:** Kotlin, Android ViewBinding/XML views, Android haptic APIs, `ViewCompat.postOnAnimation`, JUnit4, Gradle Android plugin, adb.

---

## File Structure

- Create `app/src/main/java/com/neoproc/neoproccamx/ui/CameraHapticFeedbackPolicy.kt`
  - Pure mapping from camera action to haptic primitive, fallback vibration, and rate-limit decision.
- Create `app/src/main/java/com/neoproc/neoproccamx/ui/CameraMotionPolicy.kt`
  - Pure motion constants for press, panel, shutter, camera switch, and watermark transitions.
- Create `app/src/main/java/com/neoproc/neoproccamx/ui/CameraUiStateDiffPolicy.kt`
  - Pure diff helpers used before assigning text, visibility, alpha, scale, selection, or enabled state.
- Create `app/src/main/java/com/neoproc/neoproccamx/ui/CameraUiRenderScheduler.kt`
  - Frame-coalesced render scheduler with cancellable generation token.
- Modify `app/src/main/java/com/neoproc/neoproccamx/ui/MainActivity.kt`
  - Replace direct haptic helpers with policy-backed haptics.
  - Route status chrome renders through the scheduler.
  - Use motion policy durations/scales.
  - Use diff policy before repeated view writes.
- Test `app/src/test/java/com/neoproc/neoproccamx/ui/CameraHapticFeedbackPolicyTest.kt`
- Test `app/src/test/java/com/neoproc/neoproccamx/ui/CameraMotionPolicyTest.kt`
- Test `app/src/test/java/com/neoproc/neoproccamx/ui/CameraUiStateDiffPolicyTest.kt`
- Test `app/src/test/java/com/neoproc/neoproccamx/ui/CameraUiRenderSchedulerTest.kt`

## Task 1: Haptic Feedback Policy

**Files:**
- Create: `app/src/test/java/com/neoproc/neoproccamx/ui/CameraHapticFeedbackPolicyTest.kt`
- Create: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraHapticFeedbackPolicy.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/MainActivity.kt`

- [ ] **Step 1: Write the failing tests**

Add:

```kotlin
package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraHapticFeedbackPolicyTest {

    @Test
    fun resolve_mapsModeSwitchToTickWithNavigationRateLimit() {
        val result = CameraHapticFeedbackPolicy.resolve(
            action = CameraHapticAction.MODE_SWITCH,
            nowMs = 1_000L,
            lastEvent = null
        )

        assertTrue(result.shouldEmit)
        assertEquals(CameraHapticPrimitive.CLOCK_TICK, result.primitive)
        assertEquals(55L, result.minIntervalMs)
        assertEquals(CameraHapticAction.MODE_SWITCH, result.nextEvent.action)
    }

    @Test
    fun resolve_suppressesRepeatedNavigationInsideInterval() {
        val result = CameraHapticFeedbackPolicy.resolve(
            action = CameraHapticAction.MODE_SWITCH,
            nowMs = 1_020L,
            lastEvent = CameraHapticEvent(CameraHapticAction.MODE_SWITCH, emittedAtMs = 1_000L)
        )

        assertFalse(result.shouldEmit)
    }

    @Test
    fun resolve_allowsRecordStopAfterDifferentImportantAction() {
        val result = CameraHapticFeedbackPolicy.resolve(
            action = CameraHapticAction.RECORD_STOP,
            nowMs = 1_050L,
            lastEvent = CameraHapticEvent(CameraHapticAction.RECORD_START, emittedAtMs = 1_000L)
        )

        assertTrue(result.shouldEmit)
        assertEquals(CameraHapticPrimitive.HEAVY_CLICK, result.primitive)
        assertEquals(28L, result.fallbackDurationMs)
    }
}
```

- [ ] **Step 2: Verify RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.ui.CameraHapticFeedbackPolicyTest --no-daemon --console=plain
```

Expected: FAIL because `CameraHapticFeedbackPolicy` and related types do not exist.

- [ ] **Step 3: Implement the policy**

Create:

```kotlin
package com.neoproc.neoproccamx.ui

internal enum class CameraHapticAction {
    NAVIGATION_TICK,
    BUTTON_CLICK,
    MODE_SWITCH,
    CAPTURE_TRIGGER,
    RECORD_START,
    RECORD_STOP,
    REJECTED_ACTION
}

internal enum class CameraHapticPrimitive {
    CLOCK_TICK,
    CONFIRM,
    REJECT,
    HEAVY_CLICK,
    DOUBLE_TICK
}

internal data class CameraHapticEvent(
    val action: CameraHapticAction,
    val emittedAtMs: Long
)

internal data class CameraHapticFeedbackDecision(
    val shouldEmit: Boolean,
    val primitive: CameraHapticPrimitive,
    val fallbackDurationMs: Long,
    val minIntervalMs: Long,
    val nextEvent: CameraHapticEvent
)

internal object CameraHapticFeedbackPolicy {
    fun resolve(
        action: CameraHapticAction,
        nowMs: Long,
        lastEvent: CameraHapticEvent?
    ): CameraHapticFeedbackDecision {
        val primitive = primitiveFor(action)
        val minIntervalMs = minIntervalFor(action)
        val elapsedMs = nowMs - (lastEvent?.emittedAtMs ?: Long.MIN_VALUE / 2)
        val repeatedSameAction = lastEvent?.action == action
        val importantTransition = action == CameraHapticAction.RECORD_START ||
            action == CameraHapticAction.RECORD_STOP ||
            action == CameraHapticAction.CAPTURE_TRIGGER ||
            action == CameraHapticAction.REJECTED_ACTION
        val shouldEmit = lastEvent == null ||
            elapsedMs >= minIntervalMs ||
            (importantTransition && !repeatedSameAction && elapsedMs >= 28L)

        return CameraHapticFeedbackDecision(
            shouldEmit = shouldEmit,
            primitive = primitive,
            fallbackDurationMs = fallbackDurationFor(action),
            minIntervalMs = minIntervalMs,
            nextEvent = CameraHapticEvent(action, nowMs)
        )
    }

    private fun primitiveFor(action: CameraHapticAction): CameraHapticPrimitive =
        when (action) {
            CameraHapticAction.NAVIGATION_TICK,
            CameraHapticAction.MODE_SWITCH -> CameraHapticPrimitive.CLOCK_TICK
            CameraHapticAction.BUTTON_CLICK,
            CameraHapticAction.RECORD_START -> CameraHapticPrimitive.CONFIRM
            CameraHapticAction.CAPTURE_TRIGGER,
            CameraHapticAction.RECORD_STOP -> CameraHapticPrimitive.HEAVY_CLICK
            CameraHapticAction.REJECTED_ACTION -> CameraHapticPrimitive.REJECT
        }

    private fun fallbackDurationFor(action: CameraHapticAction): Long =
        when (action) {
            CameraHapticAction.NAVIGATION_TICK,
            CameraHapticAction.MODE_SWITCH -> 8L
            CameraHapticAction.BUTTON_CLICK,
            CameraHapticAction.RECORD_START -> 16L
            CameraHapticAction.CAPTURE_TRIGGER,
            CameraHapticAction.RECORD_STOP -> 28L
            CameraHapticAction.REJECTED_ACTION -> 34L
        }

    private fun minIntervalFor(action: CameraHapticAction): Long =
        when (action) {
            CameraHapticAction.NAVIGATION_TICK,
            CameraHapticAction.MODE_SWITCH -> 55L
            CameraHapticAction.BUTTON_CLICK -> 65L
            CameraHapticAction.CAPTURE_TRIGGER,
            CameraHapticAction.RECORD_START,
            CameraHapticAction.RECORD_STOP,
            CameraHapticAction.REJECTED_ACTION -> 140L
        }
}
```

- [ ] **Step 4: Verify GREEN**

Run the same focused test command. Expected: PASS.

- [ ] **Step 5: Wire policy into `MainActivity`**

Add `private var lastHapticEvent: CameraHapticEvent? = null`.

Replace `hapticTick`, `hapticClick`, `hapticHeavy`, and `hapticDouble` implementations with policy-backed calls:

```kotlin
private fun performCameraHaptic(action: CameraHapticAction) {
    val decision = CameraHapticFeedbackPolicy.resolve(
        action = action,
        nowMs = SystemClock.uptimeMillis(),
        lastEvent = lastHapticEvent
    )
    if (!decision.shouldEmit) return
    lastHapticEvent = decision.nextEvent
    when (decision.primitive) {
        CameraHapticPrimitive.CLOCK_TICK ->
            binding.root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        CameraHapticPrimitive.CONFIRM ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                binding.root.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            } else {
                vibrator.vibrate(VibrationEffect.createOneShot(decision.fallbackDurationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        CameraHapticPrimitive.REJECT ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                binding.root.performHapticFeedback(HapticFeedbackConstants.REJECT)
            } else {
                vibrator.vibrate(VibrationEffect.createOneShot(decision.fallbackDurationMs, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        CameraHapticPrimitive.HEAVY_CLICK ->
            vibrator.vibrate(VibrationEffect.createOneShot(decision.fallbackDurationMs, VibrationEffect.DEFAULT_AMPLITUDE))
        CameraHapticPrimitive.DOUBLE_TICK ->
            vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0L, 15L, 50L, 15L), -1))
    }
}

private fun hapticTick() = performCameraHaptic(CameraHapticAction.NAVIGATION_TICK)
private fun hapticClick() = performCameraHaptic(CameraHapticAction.BUTTON_CLICK)
private fun hapticHeavy() = performCameraHaptic(CameraHapticAction.CAPTURE_TRIGGER)
private fun hapticDouble() = performCameraHaptic(CameraHapticAction.MODE_SWITCH)
```

Update `triggerPrimaryCaptureAction()` to use `RECORD_START` or `RECORD_STOP` based on current recording state before toggling.

- [ ] **Step 6: Run focused tests after integration**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.ui.CameraHapticFeedbackPolicyTest --no-daemon --console=plain
```

Expected: PASS.

## Task 2: Motion and Diff Policies

**Files:**
- Create: `app/src/test/java/com/neoproc/neoproccamx/ui/CameraMotionPolicyTest.kt`
- Create: `app/src/test/java/com/neoproc/neoproccamx/ui/CameraUiStateDiffPolicyTest.kt`
- Create: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraMotionPolicy.kt`
- Create: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraUiStateDiffPolicy.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/MainActivity.kt`

- [ ] **Step 1: Write failing tests**

Add `CameraMotionPolicyTest`:

```kotlin
package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraMotionPolicyTest {

    @Test
    fun pressMotion_isShortAndLayoutStable() {
        val motion = CameraMotionPolicy.pressMotion()

        assertEquals(0.96f, motion.pressedScale, 0.001f)
        assertTrue(motion.durationMs in 70L..120L)
    }

    @Test
    fun panelMotion_usesSmallOffsetAndNoLongAnimation() {
        val motion = CameraMotionPolicy.panelMotion(fromTop = true)

        assertEquals(-18f, motion.offsetDp, 0.001f)
        assertTrue(motion.showDurationMs <= 180L)
        assertTrue(motion.hideDurationMs <= 180L)
    }

    @Test
    fun shutterMorphMotion_staysFastEnoughForRecordToggle() {
        val motion = CameraMotionPolicy.shutterMorphMotion()

        assertTrue(motion.durationMs in 120L..190L)
    }
}
```

Add `CameraUiStateDiffPolicyTest`:

```kotlin
package com.neoproc.neoproccamx.ui

import android.view.View
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraUiStateDiffPolicyTest {

    @Test
    fun textChanged_comparesRenderedTextContent() {
        assertFalse(CameraUiStateDiffPolicy.textChanged("HDR", "HDR"))
        assertTrue(CameraUiStateDiffPolicy.textChanged("HDR", "LOG"))
    }

    @Test
    fun floatChanged_usesEpsilonForAnimationProperties() {
        assertFalse(CameraUiStateDiffPolicy.floatChanged(1.0f, 1.0004f))
        assertTrue(CameraUiStateDiffPolicy.floatChanged(1.0f, 1.02f))
    }

    @Test
    fun visibilityChanged_comparesAndroidVisibilityWithBooleanTarget() {
        assertFalse(CameraUiStateDiffPolicy.visibilityChanged(View.VISIBLE, desiredVisible = true))
        assertTrue(CameraUiStateDiffPolicy.visibilityChanged(View.GONE, desiredVisible = true))
    }
}
```

- [ ] **Step 2: Verify RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.ui.CameraMotionPolicyTest --tests com.neoproc.neoproccamx.ui.CameraUiStateDiffPolicyTest --no-daemon --console=plain
```

Expected: FAIL because the new policies do not exist.

- [ ] **Step 3: Implement the policies**

Create `CameraMotionPolicy.kt`:

```kotlin
package com.neoproc.neoproccamx.ui

internal data class CameraPressMotion(
    val pressedScale: Float,
    val durationMs: Long
)

internal data class CameraPanelMotion(
    val offsetDp: Float,
    val showDurationMs: Long,
    val hideDurationMs: Long
)

internal data class CameraScalarMotion(
    val durationMs: Long
)

internal object CameraMotionPolicy {
    fun pressMotion(): CameraPressMotion =
        CameraPressMotion(pressedScale = 0.96f, durationMs = 90L)

    fun panelMotion(fromTop: Boolean): CameraPanelMotion =
        CameraPanelMotion(
            offsetDp = if (fromTop) -18f else 18f,
            showDurationMs = 170L,
            hideDurationMs = 160L
        )

    fun controlPressMotion(): CameraScalarMotion = CameraScalarMotion(durationMs = 120L)

    fun shutterMorphMotion(): CameraScalarMotion = CameraScalarMotion(durationMs = 165L)

    fun cameraSwitchMotion(): CameraScalarMotion = CameraScalarMotion(durationMs = 130L)

    fun saveWatermarkMotion(): CameraScalarMotion = CameraScalarMotion(durationMs = 160L)
}
```

Create `CameraUiStateDiffPolicy.kt`:

```kotlin
package com.neoproc.neoproccamx.ui

import android.view.View
import kotlin.math.abs

internal object CameraUiStateDiffPolicy {
    fun textChanged(current: CharSequence?, next: String): Boolean =
        current?.toString() != next

    fun floatChanged(current: Float, next: Float, epsilon: Float = 0.001f): Boolean =
        abs(current - next) > epsilon

    fun intChanged(current: Int, next: Int): Boolean = current != next

    fun booleanChanged(current: Boolean, next: Boolean): Boolean = current != next

    fun visibilityChanged(currentVisibility: Int, desiredVisible: Boolean): Boolean {
        val desiredVisibility = if (desiredVisible) View.VISIBLE else View.GONE
        return currentVisibility != desiredVisibility
    }
}
```

- [ ] **Step 4: Verify GREEN**

Run the same focused test command. Expected: PASS.

- [ ] **Step 5: Wire motion/diff into `MainActivity`**

Use `CameraMotionPolicy` in `installPressFeedback`, `animatePanelVisibility`, `updateShutterVisual`, `switchCameraFromButton`, `animateControlPress`, `showVideoSavedWatermark`, and `hideSaveWatermark`.

Use `CameraUiStateDiffPolicy` before repeated assignments in `applyStatusHudState`, `renderTopWarningChip`, and status text handling.

- [ ] **Step 6: Run focused tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.ui.CameraMotionPolicyTest --tests com.neoproc.neoproccamx.ui.CameraUiStateDiffPolicyTest --no-daemon --console=plain
```

Expected: PASS.

## Task 3: Frame-Coalesced Status Render Scheduler

**Files:**
- Create: `app/src/test/java/com/neoproc/neoproccamx/ui/CameraUiRenderSchedulerTest.kt`
- Create: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraUiRenderScheduler.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/MainActivity.kt`

- [ ] **Step 1: Write failing tests**

Add:

```kotlin
package com.neoproc.neoproccamx.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraUiRenderSchedulerTest {

    @Test
    fun request_coalescesMultiplePassesIntoOneFrameCallback() {
        val posted = mutableListOf<Runnable>()
        val rendered = mutableListOf<Set<CameraUiRenderPass>>()
        val scheduler = CameraUiRenderScheduler(
            framePoster = { posted.add(it) },
            render = { rendered.add(it) }
        )

        scheduler.request(CameraUiRenderPass.STATUS_CHROME)
        scheduler.request(CameraUiRenderPass.STATUS_CHROME)
        scheduler.request(CameraUiRenderPass.TOP_WARNING)

        assertEquals(1, posted.size)
        posted.single().run()
        assertEquals(setOf(CameraUiRenderPass.STATUS_CHROME, CameraUiRenderPass.TOP_WARNING), rendered.single())
    }

    @Test
    fun cancel_dropsPendingFrameWork() {
        val posted = mutableListOf<Runnable>()
        val rendered = mutableListOf<Set<CameraUiRenderPass>>()
        val scheduler = CameraUiRenderScheduler(
            framePoster = { posted.add(it) },
            render = { rendered.add(it) }
        )

        scheduler.request(CameraUiRenderPass.STATUS_CHROME)
        scheduler.cancel()
        posted.single().run()

        assertTrue(rendered.isEmpty())
    }
}
```

- [ ] **Step 2: Verify RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.ui.CameraUiRenderSchedulerTest --no-daemon --console=plain
```

Expected: FAIL because the scheduler does not exist.

- [ ] **Step 3: Implement the scheduler**

Create:

```kotlin
package com.neoproc.neoproccamx.ui

internal enum class CameraUiRenderPass {
    STATUS_CHROME,
    TOP_WARNING
}

internal class CameraUiRenderScheduler(
    private val framePoster: (Runnable) -> Unit,
    private val render: (Set<CameraUiRenderPass>) -> Unit
) {
    private val pending = linkedSetOf<CameraUiRenderPass>()
    private var posted = false
    private var generation = 0

    fun request(pass: CameraUiRenderPass) {
        pending.add(pass)
        if (posted) return
        posted = true
        val expectedGeneration = generation
        framePoster(
            Runnable {
                if (expectedGeneration != generation) return@Runnable
                val passes = pending.toSet()
                pending.clear()
                posted = false
                if (passes.isNotEmpty()) {
                    render(passes)
                }
            }
        )
    }

    fun cancel() {
        generation += 1
        pending.clear()
        posted = false
    }
}
```

- [ ] **Step 4: Verify GREEN**

Run the same focused test command. Expected: PASS.

- [ ] **Step 5: Wire scheduler into `MainActivity`**

Add:

```kotlin
private lateinit var uiRenderScheduler: CameraUiRenderScheduler
```

Initialize after `binding`:

```kotlin
uiRenderScheduler = CameraUiRenderScheduler(
    framePoster = { runnable -> ViewCompat.postOnAnimation(binding.root, runnable) },
    render = { passes ->
        if (CameraUiRenderPass.STATUS_CHROME in passes) {
            renderStatusChromeNow()
        }
        if (CameraUiRenderPass.TOP_WARNING in passes) {
            renderTopWarningChip()
        }
    }
)
```

Replace `renderStatusChrome()` body with:

```kotlin
private fun renderStatusChrome() {
    if (!::uiRenderScheduler.isInitialized) {
        renderStatusChromeNow()
        return
    }
    uiRenderScheduler.request(CameraUiRenderPass.STATUS_CHROME)
}
```

Rename the current `renderStatusChrome()` implementation to `renderStatusChromeNow()` without changing its internal camera/HUD formatting logic. The new wrapper above becomes the only function named `renderStatusChrome()`, so existing call sites request a coalesced render while the scheduler calls `renderStatusChromeNow()` for the actual view writes.

Call `uiRenderScheduler.cancel()` in `onPause()` and `onDestroy()`.

- [ ] **Step 6: Run focused scheduler tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.ui.CameraUiRenderSchedulerTest --no-daemon --console=plain
```

Expected: PASS.

## Task 4: Integration Regression Checks

**Files:**
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/MainActivity.kt`
- Existing tests:
  - `app/src/test/java/com/neoproc/neoproccamx/ui/CameraModePresetSelectionPolicyTest.kt`
  - `app/src/test/java/com/neoproc/neoproccamx/ui/CameraModeRailAutoSelectionPolicyTest.kt`
  - `app/src/test/java/com/neoproc/neoproccamx/ui/CameraModeRailMotionPolicyTest.kt`
  - `app/src/test/java/com/neoproc/neoproccamx/ui/VideoFormatPreviewLookPolicyTest.kt`

- [ ] **Step 1: Run mode and preview contract tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.ui.CameraModePresetSelectionPolicyTest --tests com.neoproc.neoproccamx.ui.CameraModeRailAutoSelectionPolicyTest --tests com.neoproc.neoproccamx.ui.CameraModeRailMotionPolicyTest --tests com.neoproc.neoproccamx.ui.VideoFormatPreviewLookPolicyTest --no-daemon --console=plain
```

Expected: PASS.

- [ ] **Step 2: Fix any compile or test regression with the smallest policy-backed change**

If a regression appears, add or update a focused unit test first, verify it fails for the regression, then change code.

- [ ] **Step 3: Run all UI unit tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.ui.* --no-daemon --console=plain
```

Expected: PASS.

## Task 5: Build, Lint, Device QA, and Performance Evidence

**Files:**
- No source file changes unless verification exposes a bug.
- Artifacts should be placed under `artifacts/ui-ux-premium-2026-05-13/`.

- [ ] **Step 1: Run full unit tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
```

Expected: PASS.

- [ ] **Step 2: Run lint**

Run:

```powershell
.\gradlew.bat :app:lintDebug --no-daemon --console=plain
```

Expected: PASS with zero new lint issues.

- [ ] **Step 3: Build debug APK**

Run:

```powershell
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain
```

Expected: PASS.

- [ ] **Step 4: Install and launch on attached device**

Run:

```powershell
adb devices
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell monkey -p com.neoproc.neoproccamx 1
```

Expected: app launches without crash.

- [ ] **Step 5: Capture logcat around UI mode switching**

Run:

```powershell
New-Item -ItemType Directory -Force artifacts\ui-ux-premium-2026-05-13 | Out-Null
adb logcat -c
adb shell input tap 250 2100
adb shell input tap 420 2100
adb shell input tap 590 2100
adb shell input tap 760 2100
adb shell input tap 930 2100
adb logcat -d > artifacts\ui-ux-premium-2026-05-13\logcat-mode-switch.txt
```

Expected: no fatal exception, no ANR, mode direct-tap logs show current tap wins.

- [ ] **Step 6: Capture quick frame stats**

Run:

```powershell
adb shell dumpsys gfxinfo com.neoproc.neoproccamx reset
adb shell input tap 250 2100
adb shell input tap 420 2100
adb shell input tap 590 2100
adb shell input tap 760 2100
adb shell input tap 930 2100
adb shell dumpsys gfxinfo com.neoproc.neoproccamx > artifacts\ui-ux-premium-2026-05-13\gfxinfo.txt
adb shell dumpsys gfxinfo com.neoproc.neoproccamx framestats > artifacts\ui-ux-premium-2026-05-13\gfxinfo-framestats.txt
```

Expected: frame stats are captured for the focused mode-switch flow. Report results with device/build caveats.

- [ ] **Step 7: Check line count**

Run:

```powershell
git diff --numstat
```

Report added/deleted lines for this upgrade only, excluding generated build outputs and `.superpowers` visual companion artifacts.
