# Mandatory GPU Recording And Display-Aware Preview Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Require the custom GPU encoder feed for processed video modes and choose viewfinder resolution from the mobile display budget with measured runtime fallback only for preview performance.

**Architecture:** A pure GPU-route requirement policy separates processed encoded modes from truthful RAW master capture and validates imported-frame evidence after recording starts. `CameraPreviewSessionSizingPolicy` accepts a device display budget, while `CameraViewModel` supplies application display pixel bounds and enables the health-checked Vulkan visible preview path. Existing thermal and FPS stability policies may reduce preview cost after measured degradation, but no fixed 1080p cap is applied by default.

**Tech Stack:** Kotlin, Android Camera2/MediaCodec, Vulkan runtime bridge, JUnit4, Gradle, adb/logcat.

---

## Worktree Constraint

The active `master` worktree already contains the required uncommitted camera,
Vulkan, shader, NPV10, and test integration. Execute in this worktree and do
not revert unrelated changes; stage only documentation for the design commit.

### Task 1: Require GPU Recording For Processed Modes

**Files:**
- Create: `app/src/main/java/com/neoproc/neoproccamx/ui/ProcessedGpuRecordingRequirementPolicy.kt`
- Create: `app/src/test/java/com/neoproc/neoproccamx/ui/ProcessedGpuRecordingRequirementPolicyTest.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt`
- Modify: `app/src/test/java/com/neoproc/neoproccamx/ui/RecordingStartSessionRefreshContractTest.kt`

- [ ] **Step 1: Write failing policy and wiring tests**

```kotlin
@Test
fun `processed modes require internal hardware import while raw remains direct`() {
    assertFalse(
        ProcessedGpuRecordingRequirementPolicy.canStart(
            deliveryKind = VideoDeliveryKind.SDR,
            feedPath = RecordingFeedPath.CAMERA2_PREVIEW_PARITY_RECORD_SURFACE,
            vulkanRuntimeSupported = true
        ).allowed
    )
    assertTrue(
        ProcessedGpuRecordingRequirementPolicy.canStart(
            deliveryKind = VideoDeliveryKind.RAW,
            feedPath = RecordingFeedPath.CAMERA2_DIRECT_RECORD_SURFACE,
            vulkanRuntimeSupported = false
        ).allowed
    )
}

@Test
fun `processed route is proven only after imported frames reach a bound encoder`() {
    assertTrue(
        ProcessedGpuRecordingRequirementPolicy.validateRunning(
            frameImportEnabled = true,
            encoderSurfaceBound = true,
            importedFrames = 1L
        ).validated
    )
}
```

The source contract test must reject the old text
`falling back to Camera2 direct recording` in the encoder-bind failure branch.

- [ ] **Step 2: Run tests to observe failure**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests '*ProcessedGpuRecordingRequirementPolicyTest' --tests '*RecordingStartSessionRefreshContractTest' --no-daemon --console=plain
```

Expected: FAIL because the policy does not exist and the bind failure still
falls back to Camera2 recording.

- [ ] **Step 3: Implement mandatory route selection and bounded proof**

```kotlin
internal data class ProcessedGpuRecordingStartDecision(
    val allowed: Boolean,
    val failureMessage: String?
)

internal data class ProcessedGpuRecordingRuntimeDecision(
    val validated: Boolean,
    val failureMessage: String?
)

internal object ProcessedGpuRecordingRequirementPolicy {
    const val FIRST_IMPORTED_FRAME_TIMEOUT_MS = 1_250L

    fun canStart(
        deliveryKind: VideoDeliveryKind,
        feedPath: RecordingFeedPath,
        vulkanRuntimeSupported: Boolean
    ): ProcessedGpuRecordingStartDecision =
        if (deliveryKind == VideoDeliveryKind.RAW) {
            ProcessedGpuRecordingStartDecision(true, null)
        } else if (vulkanRuntimeSupported &&
            feedPath == RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER
        ) {
            ProcessedGpuRecordingStartDecision(true, null)
        } else {
            ProcessedGpuRecordingStartDecision(
                false,
                "GPU processed recording is unavailable for this mode."
            )
        }

    fun validateRunning(
        frameImportEnabled: Boolean,
        encoderSurfaceBound: Boolean,
        importedFrames: Long
    ): ProcessedGpuRecordingRuntimeDecision =
        if (frameImportEnabled && encoderSurfaceBound && importedFrames > 0L) {
            ProcessedGpuRecordingRuntimeDecision(true, null)
        } else {
            ProcessedGpuRecordingRuntimeDecision(
                false,
                "GPU processed recording did not receive validated frames."
            )
        }
}
```

In `CameraViewModel.startRecording`, apply `canStart(...)` before starting an
encoded processed session. In `startInternalHardwareImportRecording`, replace
the encoder-binding fallback with cleanup and a user-visible GPU-processing
failure. After enabling frame import, delay by
`FIRST_IMPORTED_FRAME_TIMEOUT_MS`, validate the running import statistics, and
stop an invalid processed recording rather than continuing a bypass-quality
capture.

- [ ] **Step 4: Run focused tests**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests '*ProcessedGpuRecordingRequirementPolicyTest' --tests '*RecordingPipelineParityPolicyTest' --tests '*RecordingStartSessionRefreshContractTest' --no-daemon --console=plain
```

Expected: PASS.

### Task 2: Make Preview Size Display-Aware

**Files:**
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraPreviewSessionSizingPolicy.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/PreviewPerformanceStabilityPolicy.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt`
- Modify: `app/src/test/java/com/neoproc/neoproccamx/ui/CameraPreviewSessionSizingPolicyTest.kt`
- Modify: `app/src/test/java/com/neoproc/neoproccamx/ui/PreviewPerformanceStabilityPolicyTest.kt`

- [ ] **Step 1: Write failing display-budget tests**

```kotlin
@Test
fun `preview uses high density phone display budget when no runtime cap applies`() {
    val limits = CameraPreviewSessionSizingPolicy.resolvePreviewLimits(
        videoMode = true,
        strictHighEnd = true,
        videoSourceLongEdge = 4096,
        displayMaxLongEdge = 2800,
        displayMaxArea = 2800 * 1260
    )
    assertEquals(2800, limits.maxLongEdge)
    assertEquals(2800 * 1260, limits.maxArea)
}
```

Change the default recording-performance assertion to require `null` preview
caps until measured performance or thermal guards activate.

- [ ] **Step 2: Run tests to observe failure**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests '*CameraPreviewSessionSizingPolicyTest' --tests '*PreviewPerformanceStabilityPolicyTest' --no-daemon --console=plain
```

Expected: FAIL because the sizing API has a fixed 1080p limit and recording
applies a default fixed cap.

- [ ] **Step 3: Implement display-driven sizing**

Add `displayMaxLongEdge: Int = 1920` and
`displayMaxArea: Int = 1920 * 1080` to `resolvePreviewLimits(...)` and use
them as the absolute live display budget. Keep the pure policy boundary
integer-based so local JVM tests do not depend on Android `Size` stub values.
In `CameraViewModel`, supply values from:

```kotlin
private fun mobileDisplayPreviewBudget(): Size {
    val metrics = getApplication<Application>().resources.displayMetrics
    return Size(
        maxOf(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1),
        minOf(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1)
    )
}
```

Remove proactive fixed preview caps from the `DEFAULT` recording performance
profile; retain `STABILIZED`, `AGGRESSIVE`, and thermal caps.

- [ ] **Step 4: Run focused tests**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests '*CameraPreviewSessionSizingPolicyTest' --tests '*PreviewPerformanceStabilityPolicyTest' --no-daemon --console=plain
```

Expected: PASS.

### Task 3: Attempt Health-Checked Vulkan Preview And Verify Device Route

**Files:**
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt`
- Modify: `app/src/test/java/com/neoproc/neoproccamx/rendering/VulkanVisiblePreviewPolicyTest.kt`

- [ ] **Step 1: Write a source-contract test for preview activation**

```kotlin
@Test
fun `camera view model attempts health checked native preview instead of permanently disabling it`() {
    val source = File("src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt").readText()
    assertFalse(source.contains("hasPresentationFeedback = false"))
    assertTrue(source.contains("startNativePreviewWatchdog(surface)"))
}
```

- [ ] **Step 2: Run test to observe failure**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests '*VulkanVisiblePreviewPolicyTest' --no-daemon --console=plain
```

Expected: FAIL while the view model permanently disables native Vulkan preview.

- [ ] **Step 3: Enable watchdog-controlled preview attempts**

Pass `hasPresentationFeedback = true` when the display surface exists and
document that successful bound-surface imports are the runtime validation
signal. The existing watchdog must remain active to return to stable direct
preview if imports do not arrive or stall.

- [ ] **Step 4: Run tests, build, deploy, and collect evidence**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb logcat -c
adb shell am force-stop com.neoproc.neoproccamx
adb shell monkey -p com.neoproc.neoproccamx 1
```

Record a processed video on the connected device, then require log evidence
containing `INTERNAL_HARDWARE_IMPORT_ENCODER`, `vulkanEncoderSurfaceBound=true`,
`vulkanFrameImportEnabled=true`, and `vulkanSessionImportedFrames` above zero.
Confirm the preview stream logged by the app is within the active device
display budget rather than forced to 1920x1080.
