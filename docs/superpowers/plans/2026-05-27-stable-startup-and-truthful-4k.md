# Stable Startup And Truthful 4K Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate transient startup preview handoff defects and preserve real UHD recording selection independently of viewfinder aspect.

**Architecture:** Keep Camera2 as the first visible preview producer and decline unvalidated Vulkan promotion once that surface is active. Treat the selected recording size as an output contract normalized only for orientation; preview framing remains a display concern, while capability fallbacks remain truthful and non-destructive.

**Tech Stack:** Kotlin, Android Camera2/Vulkan preview routing, JUnit4 source and policy tests, Gradle, adb physical-device validation.

---

## File Map

- Modify `app/src/test/java/com/neoproc/neoproccamx/ui/RecordingStartSessionRefreshContractTest.kt`: require direct visible startup preview ownership.
- Modify `app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt`: gate startup Vulkan presentation, stop aspect from rewriting requested video dimensions, and preserve the selected request across fallback.
- Modify `app/src/test/java/com/neoproc/neoproccamx/ui/CameraViewModelControllerSplitContractTest.kt`: require orientation-only recording request normalization and non-destructive persistence.
- Modify `app/src/test/java/com/neoproc/neoproccamx/ui/controls/SettingsOptionCatalogTest.kt`: require canonical UHD plus distinct supported full-sensor `4:3` option.
- Modify `app/src/main/java/com/neoproc/neoproccamx/ui/controls/SettingsOptionCatalog.kt`: expose `4K Full 4:3` only when the camera reports it.

**Working Tree Note:** This workspace already contains approved uncommitted camera changes. Inline execution must alter only the scoped behaviors above and must not revert existing work.

### Task 1: Protect Stable Startup Preview Ownership

**Files:**
- Modify: `app/src/test/java/com/neoproc/neoproccamx/ui/RecordingStartSessionRefreshContractTest.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt`

- [x] **Step 1: Replace the obsolete startup takeover contract with a failing stability contract**

```kotlin
@Test
fun `startup keeps direct camera2 preview visible instead of handing off before qualification`() {
    val activationBlock = source
        .substringAfter("private fun shouldAttemptNativeVulkanPreview(")
        .substringBefore("private fun cameraOwnsVisiblePreviewSurface(")

    assertTrue(activationBlock.contains("startupDirectPreviewActive || startupFeatureGateActive"))
    assertTrue(activationBlock.contains("directPreviewSurfaceActive"))
}
```

- [x] **Step 2: Run the focused test and observe failure**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.ui.RecordingStartSessionRefreshContractTest --no-daemon --no-configuration-cache --console=plain`

Expected: FAIL because startup/direct-preview gates are not in the activation block.

- [x] **Step 3: Gate visible Vulkan activation after direct startup presentation**

```kotlin
if (startupDirectPreviewActive || startupFeatureGateActive || directPreviewSurfaceActive) {
    return false
}
```

- [x] **Step 4: Run the focused test again**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.ui.RecordingStartSessionRefreshContractTest --no-daemon --no-configuration-cache --console=plain`

Expected: BUILD SUCCESSFUL.

- [x] **Step 5: Add a failing contract that GPU recording qualification is independent of visible preview**

```kotlin
@Test
fun `gpu recording qualification does not require visible vulkan preview takeover`() {
    val qualification = source
        .substringAfter("private fun vulkanRecordingPathQualified(): Boolean =")
        .substringBefore("private fun activeCamera2SessionGraphParityPlan(")

    assertTrue(qualification.contains("vulkanSupported.value"))
    assertFalse(qualification.contains("nativeVisiblePreviewConfirmed"))
}
```

- [x] **Step 6: Make the encoder-import path responsible for its own runtime validation**

```kotlin
private fun vulkanRecordingPathQualified(): Boolean =
    vulkanSupported.value
```

- [x] **Step 7: Run the focused test and existing bind-failure rejection contract**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.ui.RecordingStartSessionRefreshContractTest --no-daemon --no-configuration-cache --console=plain`

Expected: BUILD SUCCESSFUL; the existing processed GPU bind-failure contract continues to require rejecting an unvalidated encoder path.

### Task 2: Keep Recording Resolution Independent From Preview Framing

**Files:**
- Modify: `app/src/test/java/com/neoproc/neoproccamx/ui/CameraViewModelControllerSplitContractTest.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt`

- [x] **Step 1: Add failing wiring contracts**

```kotlin
@Test
fun `video output selection is normalized for orientation and not cropped by preview aspect`() {
    assertTrue(viewModelSource.contains("normalizeRecordingRequest = { size -> size.normalizeForOrientation() }"))
    assertFalse(viewModelSource.contains("normalizeRecordingRequest = ::aspectAdjustedRecordingRequest"))
}

@Test
fun `resolution fallback does not overwrite the requested recording selection`() {
    assertTrue(viewModelSource.contains("selectedVideoSize = requested"))
    assertTrue(viewModelSource.contains("prefs.videoSize != requested"))
}
```

- [x] **Step 2: Run the focused test and observe failure**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.ui.CameraViewModelControllerSplitContractTest --no-daemon --no-configuration-cache --console=plain`

Expected: FAIL because the current ViewModel crops requests through preview aspect and persists `effectiveRequestedSize`.

- [x] **Step 3: Wire orientation-only selection and requested-size persistence**

```kotlin
normalizeRecordingRequest = { size -> size.normalizeForOrientation() }
selectedVideoSize = requested
if (prefs.videoSize != requested) {
    prefs.videoSize = requested
}
```

- [x] **Step 4: Run the focused test again**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.ui.CameraViewModelControllerSplitContractTest --no-daemon --no-configuration-cache --console=plain`

Expected: BUILD SUCCESSFUL.

### Task 3: Expose Supported Full-Sensor 4:3 As A Separate Format

**Files:**
- Modify: `app/src/test/java/com/neoproc/neoproccamx/ui/controls/SettingsOptionCatalogTest.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/controls/SettingsOptionCatalog.kt`

- [x] **Step 1: Add/adjust the failing supported-resolution expectation**

```kotlin
assertEquals(listOf("4K", "4K Full 4:3", "2K"), limitedChoices)
assertEquals(Size(3840, 2160), choices.first { it.label == "4K" }.value)
assertEquals(Size(4096, 3072), choices.first { it.label == "4K Full 4:3" }.value)
```

- [x] **Step 2: Run the focused test and observe failure**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.ui.controls.SettingsOptionCatalogTest --no-daemon --no-configuration-cache --console=plain`

Expected: FAIL because full-sensor `4096x3072` is currently collapsed into the ordinary `4K` tier.

- [x] **Step 3: Give full-sensor 4:3 output its own supported-only label**

```kotlin
"4K Full 4:3" to RecordingDimensions(4096, 3072)
```

and classify `RecordingDimensions(4096, 3072)` as `"4K Full 4:3"` before the generic `4096`/`3840` `4K` branch.

- [x] **Step 4: Run the focused test again**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.ui.controls.SettingsOptionCatalogTest --no-daemon --no-configuration-cache --console=plain`

Expected: BUILD SUCCESSFUL.

### Task 4: Verify And Deploy

**Files:**
- Inspect: modified files and `qa-artifacts/2026-05-27-startup-resolution-repro/`

- [x] **Step 1: Run complete unit verification**

Run: `.\gradlew.bat :app:testDebugUnitTest --no-daemon --no-configuration-cache --console=plain`

Expected: BUILD SUCCESSFUL with zero failed tests.

- [x] **Step 2: Build and lint**

Run: `.\gradlew.bat :app:assembleDebug :app:lintDebug --no-daemon --no-configuration-cache --console=plain`

Expected: BUILD SUCCESSFUL and lint reports no errors or warnings.

- [ ] **Step 3: Install and validate on the connected phone**

Run: install `app/build/outputs/apk/debug/app-debug.apk`, cold launch the app, capture startup `am start -W` and `logcat`, then send a debug `4:3` plus `3840x2160` selection command.

Expected: no startup visible-Vulkan fallback transition, and log reports `requested=3840x2160 source=3840x2160 output=3840x2160` while preview aspect remains `FOUR_THREE`.
