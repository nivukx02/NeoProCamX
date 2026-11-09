# User-Authoritative Quality Settings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore the `200 Mbps` default recording target and ensure explicit AI/noise-reduction settings reach the active camera pipelines.

**Architecture:** Preserve the existing `PreferencesManager`, feature-pack coordinator, settings controller, and `CameraViewModel` boundaries. Correct the default constants and remove only the strict-mode coercion in the ViewModel effective-setting helpers; leave hardware capability fallbacks unchanged.

**Tech Stack:** Kotlin, Android Camera2 application wiring, JUnit4 source-contract tests, Gradle Android build/lint, adb smoke deployment.

---

## File Map

- Modify `app/src/test/java/com/neoproc/neoproccamx/recording/ManualVideoBitrateContractTest.kt`: require the restored `200 Mbps` defaults.
- Modify `app/src/test/java/com/neoproc/neoproccamx/ui/controls/SettingsRuntimeFormatterTest.kt`: require truthful target/applied manual bitrate labels.
- Modify `app/src/main/java/com/neoproc/neoproccamx/ui/controls/SettingsRuntimeFormatter.kt`: format capped manual bitrate without hiding the selected target.
- Modify `app/src/main/java/com/neoproc/neoproccamx/ui/controls/SettingsSectionFactory.kt`: use target/applied label on initial settings rendering.
- Modify `app/src/main/java/com/neoproc/neoproccamx/ui/controls/VideoSettingsFragment.kt`: use target/applied label during live state refresh.
- Modify `app/src/test/java/com/neoproc/neoproccamx/ui/CameraViewModelControllerSplitContractTest.kt`: require settings selections to remain user-authoritative at the facade/application boundary.
- Modify `app/src/main/java/com/neoproc/neoproccamx/utils/PreferencesManager.kt`: restore the fresh/default requested bitrate.
- Modify `app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModelConstants.kt`: restore the high-end pack manual bitrate choice.
- Modify `app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt`: return explicit AI/noise-reduction selections unchanged.
- Modify `app/src/test/java/com/neoproc/neoproccamx/ui/controls/SettingsOptionCatalogTest.kt`: require selected FPS presentation to remain truthful.
- Modify `app/src/main/java/com/neoproc/neoproccamx/ui/controls/SettingsOptionCatalog.kt`: stop UI-only strict promotion of an already applied FPS.

**Working Tree Note:** This shared workspace already contains approved uncommitted camera work. The requested inline execution must integrate with that state; do not revert, relocate, or commit unrelated changes.

### Task 1: Restore The 200 Mbps Default Contract

**Files:**
- Modify: `app/src/test/java/com/neoproc/neoproccamx/recording/ManualVideoBitrateContractTest.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/utils/PreferencesManager.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModelConstants.kt`

- [ ] **Step 1: Change the bitrate test to require `200 Mbps`**

```kotlin
private val constants =
    File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModelConstants.kt").readText()

@Test
fun `video starts with a user editable 200 mbps requested bitrate`() {
    assertTrue(preferences.contains("DEFAULT_VIDEO_BITRATE     = 200_000_000"))
    assertTrue(constants.contains("HIGH_END_DEFAULT_MANUAL_BITRATE_BPS = 200_000_000"))
    assertTrue(
        preferences.contains(
            "getStringCompat(KEY_VIDEO_BITRATE_MODE, VideoBitrateMode.CUSTOM.name)"
        )
    )
    assertTrue(settings.contains("160, 180, 200"))
}
```

- [ ] **Step 2: Run the targeted test to prove it fails on `180 Mbps`**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.recording.ManualVideoBitrateContractTest --no-daemon --no-configuration-cache --console=plain`

Expected: FAIL because production constants still contain `180_000_000`.

- [ ] **Step 3: Restore production defaults**

```kotlin
const val DEFAULT_VIDEO_BITRATE     = 200_000_000
const val HIGH_END_DEFAULT_MANUAL_BITRATE_BPS = 200_000_000
```

- [ ] **Step 4: Run the targeted test to prove the default contract passes**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.recording.ManualVideoBitrateContractTest --no-daemon --no-configuration-cache --console=plain`

Expected: BUILD SUCCESSFUL.

### Task 2: Make Explicit Quality Toggles Authoritative

**Files:**
- Modify: `app/src/test/java/com/neoproc/neoproccamx/ui/CameraViewModelControllerSplitContractTest.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt`

- [ ] **Step 1: Add a failing settings-application contract**

```kotlin
@Test
fun `manual enhancement and noise reduction selections remain authoritative`() {
    assertTrue(
        viewModelSource.contains(
            "private fun effectiveAiEnhancementEnabled(preferred: Boolean = prefs.aiEnhancementEnabled): Boolean =\n        preferred"
        )
    )
    assertTrue(
        viewModelSource.contains(
            "private fun effectiveNoiseReductionEnabled(preferred: Boolean = prefs.noiseReductionEnabled): Boolean =\n        preferred"
        )
    )
}
```

- [ ] **Step 2: Run the targeted test to prove strict coercion fails it**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.ui.CameraViewModelControllerSplitContractTest --no-daemon --no-configuration-cache --console=plain`

Expected: FAIL because both effective helpers force `true` under strict quality.

- [ ] **Step 3: Remove only the incorrect coercion**

```kotlin
private fun effectiveAiEnhancementEnabled(preferred: Boolean = prefs.aiEnhancementEnabled): Boolean =
    preferred

private fun effectiveNoiseReductionEnabled(preferred: Boolean = prefs.noiseReductionEnabled): Boolean =
    preferred
```

- [ ] **Step 4: Run the targeted test to prove explicit selections are wired**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.ui.CameraViewModelControllerSplitContractTest --no-daemon --no-configuration-cache --console=plain`

Expected: BUILD SUCCESSFUL.

### Task 3: Report Manual Bitrate Target And Applied Output Truthfully

**Files:**
- Modify: `app/src/test/java/com/neoproc/neoproccamx/ui/controls/SettingsRuntimeFormatterTest.kt`
- Modify: `app/src/test/java/com/neoproc/neoproccamx/recording/ManualVideoBitrateContractTest.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/controls/SettingsRuntimeFormatter.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/controls/SettingsSectionFactory.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/controls/VideoSettingsFragment.kt`

- [ ] **Step 1: Write tests requiring target/applied display**

```kotlin
@Test
fun `capped custom bitrate displays selected target and applied output`() {
    assertEquals(
        "800 Mbps HQ target / 160 Mbps applied",
        SettingsRuntimeFormatter.formatBitrateStatusLabel(
            bitrateMode = VideoBitrateMode.CUSTOM,
            requestedBitrateBitsPerSecond = 800_000_000,
            appliedBitrateBitsPerSecond = 160_000_000
        )
    )
}
```

Update `ManualVideoBitrateContractTest` to require `formatBitrateStatusLabel`
usage in both settings UI paths.

- [ ] **Step 2: Run tests to prove the truthful formatter is missing**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.ui.controls.SettingsRuntimeFormatterTest --tests com.neoproc.neoproccamx.recording.ManualVideoBitrateContractTest --no-daemon --no-configuration-cache --console=plain`

Expected: FAIL because `formatBitrateStatusLabel` is not implemented or used.

- [ ] **Step 3: Implement and wire the target/applied formatter**

```kotlin
fun formatBitrateStatusLabel(
    bitrateMode: VideoBitrateMode,
    requestedBitrateBitsPerSecond: Int,
    appliedBitrateBitsPerSecond: Int
): String {
    val applied = formatBitrateLabel(bitrateMode, appliedBitrateBitsPerSecond)
    if (bitrateMode != VideoBitrateMode.CUSTOM) return applied
    val requested = formatBitrateLabel(bitrateMode, requestedBitrateBitsPerSecond)
    return if (requestedBitrateBitsPerSecond == appliedBitrateBitsPerSecond) {
        requested
    } else {
        "$requested target / ${formatBitrateLabel(VideoBitrateMode.CUSTOM, appliedBitrateBitsPerSecond)} applied"
    }
}
```

Use this function from `SettingsSectionFactory` and `VideoSettingsFragment`,
passing `requestedVideoBitrateBitsPerSecond()` and
`reportedVideoBitrateBitsPerSecond()`.

- [ ] **Step 4: Re-run the formatter and wiring tests**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.ui.controls.SettingsRuntimeFormatterTest --tests com.neoproc.neoproccamx.recording.ManualVideoBitrateContractTest --no-daemon --no-configuration-cache --console=plain`

Expected: BUILD SUCCESSFUL.

### Task 4: Verify And Deploy

**Files:**
- Verify all modified production and test files above.

Before verification, fix the additional proven selector mismatch:

- [ ] **Step 1: Add the failing applied-FPS selector contract**

```kotlin
@Test
fun `selected supported frame rate is not silently promoted when strict quality is enabled`() {
    val previousEnabled = HighEndQualityPolicy.enabled
    val previousStrict = HighEndQualityPolicy.strictHighEndOnly
    try {
        HighEndQualityPolicy.enabled = true
        HighEndQualityPolicy.strictHighEndOnly = true

        assertEquals(24, SettingsOptionCatalog.selectedFpsOrDefault(24))
    } finally {
        HighEndQualityPolicy.enabled = previousEnabled
        HighEndQualityPolicy.strictHighEndOnly = previousStrict
    }
}
```

- [ ] **Step 2: Run it to prove the current selector reports the wrong FPS**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.ui.controls.SettingsOptionCatalogTest --no-daemon --no-configuration-cache --console=plain`

Expected: FAIL because strict presentation currently promotes `24 FPS`.

- [ ] **Step 3: Make selector presentation report the actual applied value**

```kotlin
fun selectedFpsOrDefault(currentFps: Int): Int =
    fpsChoices.minByOrNull { abs(it.value - currentFps) }?.value ?: 30
```

- [ ] **Step 4: Re-run the selector test**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.ui.controls.SettingsOptionCatalogTest --no-daemon --no-configuration-cache --console=plain`

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Run the complete unit test suite**

Run: `.\gradlew.bat :app:testDebugUnitTest --no-daemon --no-configuration-cache --console=plain`

Expected: BUILD SUCCESSFUL with zero unit-test failures.

- [ ] **Step 6: Build the debug APK and run lint**

Run: `.\gradlew.bat :app:assembleDebug :app:lintDebug --no-daemon --no-configuration-cache --console=plain`

Expected: BUILD SUCCESSFUL and lint reports no new errors or warnings.

- [ ] **Step 7: Install and launch only when an adb target is present**

Run: `adb devices -l`

When a device is listed, run:

```powershell
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell monkey -p com.neoproc.neoproccamx -c android.intent.category.LAUNCHER 1
```

Expected: APK install succeeds and the launcher activity opens. If no target is present, report deployment blocked by device connectivity without claiming device validation.
