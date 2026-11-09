# Camera UI UX Rebuild Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the approved camera UI with clean preview HUD, centered mode access, reference-matched PRO controls, corrected spacing, and recording animations.

**Architecture:** Add small UI policy helpers for center-mode selection, HUD text, and shutter visual state so behavior can be tested without Android views. Then update XML drawables/layout and wire `MainActivity` to keep existing feature behavior while matching the approved visual distance.

**Tech Stack:** Kotlin, Android XML views/drawables, ViewBinding, JUnit, Gradle Android unit/build tasks.

---

### Task 1: Testable UI Policies

**Files:**
- Create: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraModeRailCenterSelectionPolicy.kt`
- Create: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraPreviewHudFormatter.kt`
- Create: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraRecordingButtonVisualPolicy.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/ui/CameraModeRailCenterSelectionPolicyTest.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/ui/CameraPreviewHudFormatterTest.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/ui/CameraRecordingButtonVisualPolicyTest.kt`

- [ ] **Step 1: Write failing tests**

Add tests proving:

```kotlin
CameraModeRailCenterSelectionPolicy.resolveCenteredPreset(
    viewportScrollX = 120,
    viewportWidth = 200,
    items = listOf(
        CameraModeRailItem(CameraModePreset.PRO, left = 0, right = 84),
        CameraModeRailItem(CameraModePreset.HDR, left = 106, right = 190),
        CameraModeRailItem(CameraModePreset.LOG_VIDEO, left = 212, right = 296)
    )
) == CameraModePreset.HDR

CameraPreviewHudFormatter.format(sensorSize = Size(8192, 6144), resolution = "8K UHD", aspect = "4:3") ==
    "8192x6144 | 8K UHD | 4:3"

CameraRecordingButtonVisualPolicy.resolve(recording = true).centerScale == 0.50f
```

- [ ] **Step 2: Verify RED**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.ui.CameraModeRailCenterSelectionPolicyTest --tests com.neoproc.neoproccamx.ui.CameraPreviewHudFormatterTest --tests com.neoproc.neoproccamx.ui.CameraRecordingButtonVisualPolicyTest --no-daemon --console=plain
```

Expected: fail because the new policy classes do not exist.

- [ ] **Step 3: Implement policies**

Add the policy classes with deterministic pure functions:

```kotlin
internal data class CameraModeRailItem(val preset: CameraModePreset, val left: Int, val right: Int)

internal object CameraModeRailCenterSelectionPolicy {
    fun resolveCenteredPreset(viewportScrollX: Int, viewportWidth: Int, items: List<CameraModeRailItem>): CameraModePreset?
}

internal object CameraPreviewHudFormatter {
    fun format(sensorSize: Size?, resolution: String?, aspect: String?): String
}

internal data class CameraRecordingButtonVisualState(
    val centerBackgroundRes: Int,
    val centerScale: Float,
    val centerAlpha: Float,
    val cornerRadiusDp: Float,
    val pulseVisible: Boolean
)
```

- [ ] **Step 4: Verify GREEN**

Run the same focused unit-test command. Expected: pass.

### Task 2: XML Layout And Drawables

**Files:**
- Modify: `app/src/main/res/layout/activity_main.xml`
- Modify: `app/src/main/res/layout/fragment_manual_controls.xml`
- Modify: `app/src/main/res/values/dimens.xml`
- Modify: `app/src/main/res/values/styles.xml`
- Create: `app/src/main/res/drawable/bg_preview_hud_chip.xml`
- Create: `app/src/main/res/drawable/bg_mode_rail_active.xml`
- Create: `app/src/main/res/drawable/bg_mode_rail_idle.xml`
- Create: `app/src/main/res/drawable/bg_bottom_capture_panel.xml`
- Create: `app/src/main/res/drawable/bg_bottom_round_button.xml`
- Create: `app/src/main/res/drawable/bg_recording_pulse_ring.xml`

- [ ] **Step 1: Update main layout spacing**

Change the main screen so:

- `topBarScroll`, left/right rails, and extra feature tray start hidden.
- `topStatusPanel` is a compact top-center HUD chip.
- `manualContainer` sits above `bottomBar` with a fixed non-overlap margin.
- `bottomBar` has fixed capture-panel height and no negative translation.
- Mode rail labels are `VIDEO`, `PRO`, `HDR`, `LOG`, `RAW`; no `PHOTO`.
- Left bottom button is `btnFlipCamera`, center is `btnShutter`, right is `btnModePro` settings.

- [ ] **Step 2: Update manual controls strip**

Make `fragment_manual_controls.xml` match the approved reference strip:

- horizontal top adjust row,
- compact Auto/Manual block,
- fixed EV/ISO/S/WB/AF/Meter parameter row,
- only active parameter section visible below the row,
- no extra drawer title or metric cards.

- [ ] **Step 3: Add drawables**

Add simple XML drawables for HUD chip, bottom panel, white active mode pill, transparent idle mode, compact round icon buttons, and recording pulse ring.

### Task 3: MainActivity Wiring

**Files:**
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/MainActivity.kt`

- [ ] **Step 1: Wire bottom buttons**

Use the existing flip-camera action on `btnFlipCamera`, move it to the left bottom button, and keep `btnModePro` as the right settings button. Do not use the old gallery button on the main capture screen.

- [ ] **Step 2: Wire center-mode access**

Replace inline center calculation with `CameraModeRailCenterSelectionPolicy`. The active mode should update only from the centered mode or a mode tap, and the selected pill should animate with a small pop.

- [ ] **Step 3: Wire HUD formatter**

Update `renderStatusChrome()` to show only:

```text
sensorSize | activeResolution | aspect
```

Hide the secondary status line permanently for this clean HUD.

- [ ] **Step 4: Wire recording animation**

Use `CameraRecordingButtonVisualPolicy` in `updateShutterVisual()` to morph idle circle to red stop square and show a subtle pulse ring while recording.

### Task 4: Verification

**Files:**
- Existing Gradle project files

- [ ] **Step 1: Run focused tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.ui.CameraModeRailCenterSelectionPolicyTest --tests com.neoproc.neoproccamx.ui.CameraPreviewHudFormatterTest --tests com.neoproc.neoproccamx.ui.CameraRecordingButtonVisualPolicyTest --no-daemon --console=plain
```

- [ ] **Step 2: Run all unit tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
```

- [ ] **Step 3: Build debug APK**

Run:

```powershell
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain
```

- [ ] **Step 4: Android visual smoke test if a device is attached**

Run:

```powershell
adb devices
```

If a device is attached, install and capture a screenshot to check spacing/overlap. If no device is attached, report that runtime visual QA could not be performed.
