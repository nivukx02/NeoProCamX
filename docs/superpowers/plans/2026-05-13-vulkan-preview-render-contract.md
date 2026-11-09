# Vulkan Preview Render Contract Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade the Vulkan preview/render decision path so VIDEO, PRO, HDR, LOG, and RAW modes refresh the correct render/tone contract immediately without changing preview aspect.

**Architecture:** Add a pure Kotlin render-contract policy that maps the existing video pipeline contract to Vulkan tone delivery, swapchain dynamic-range intent, preview render path, quality tier, and a stable render signature. Wire `CameraViewModel` to push render config from that contract on geometry updates and explicit mode switches. Leave `PreviewFramingResolver`, `PreviewAspectResolver`, `VulkanPreviewTransformEngine`, SurfaceView sizing, and native viewport/scissor geometry unchanged.

**Tech Stack:** Kotlin/JVM unit tests, Android Camera2 state model, Vulkan JNI bridge, existing `HdrSwapchainFormatPolicy`, `VulkanToneDeliveryMode`, and Gradle Android test/build tasks.

---

### Task 1: Render Contract Policy

**Files:**
- Create: `app/src/test/java/com/neoproc/neoproccamx/rendering/VulkanPreviewRenderContractPolicyTest.kt`
- Create: `app/src/main/java/com/neoproc/neoproccamx/rendering/VulkanPreviewRenderContractPolicy.kt`

- [x] **Step 1: Write the failing tests**

Create tests that assert:
- SDR VIDEO/PRO resolves to direct SDR render, SDR swapchain, and aspect preservation.
- HDR HLG resolves to HLG HDR tone delivery and HLG dynamic range.
- HDR10+ resolves to PQ HDR tone delivery and HDR10+ dynamic range.
- LOG resolves to a LOG delivery shader mode, not HDR monitor mode.
- RAW resolves to RAW monitor preview while preserving original RAW delivery.
- Cinema debayer active changes RAW render path/signature.
- High-load preview resolves to FAST quality; strict high-end idle resolves to MAXIMUM.
- Signatures differ across PRO, HDR, LOG, and RAW.

Run:
```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.rendering.VulkanPreviewRenderContractPolicyTest
```

Expected: compile failure because `VulkanPreviewRenderContractPolicy` does not exist.

- [x] **Step 2: Implement the minimal policy**

Add:
```kotlin
internal enum class VulkanPreviewRenderPath {
    SDR_DIRECT,
    SDR_LUT,
    HDR_HLG_NATIVE,
    HDR_PQ_NATIVE,
    LOG_ORIGINAL,
    RAW_MONITOR,
    CINEMA_DEBAYER_MONITOR
}

internal enum class VulkanPreviewRenderQuality {
    FAST,
    BALANCED,
    MAXIMUM
}

internal data class VulkanPreviewRenderContract(
    val renderPath: VulkanPreviewRenderPath,
    val swapchainDynamicRangeMode: HdrDynamicRangeMode,
    val toneDeliveryMode: VulkanToneDeliveryMode,
    val truthfulColorDelivery: Boolean,
    val previewMonitoringEnabled: Boolean,
    val rawVideoDelivery: Boolean,
    val rawVideoActive: Boolean,
    val logCaptureActive: Boolean,
    val normalizedHdrMode: String,
    val nativePreviewLutEnabled: Boolean,
    val renderQuality: VulkanPreviewRenderQuality,
    val signature: String,
    val preservesPreviewAspect: Boolean = true
)
```

Resolve from `VideoModePipelineContract` and `VideoFormatPreviewLookState` using `VulkanToneDeliveryMode.resolve(...)`. Include quality selection from `highLoadPreviewActive`, `previewFpsHint`, and `HighEndQualityPolicy`.

- [x] **Step 3: Run the policy tests**

Run the same test command. Expected: all policy tests pass.

### Task 2: CameraViewModel Render Wiring

**Files:**
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt`
- Modify: `app/src/test/java/com/neoproc/neoproccamx/rendering/VulkanPreviewRenderContractPolicyTest.kt`

- [x] **Step 1: Add test coverage for immediate render refresh input**

Extend the policy test to assert `signature` changes when only the pipeline mode changes, even if no geometry input changes. This protects the ViewModel integration from stale shader modes.

Run the policy test. Expected: pass once Task 1 is complete.

- [x] **Step 2: Wire the render contract into render config creation**

In `CameraViewModel`, add imports for:
```kotlin
import com.neoproc.neoproccamx.rendering.VulkanPreviewRenderContract
import com.neoproc.neoproccamx.rendering.VulkanPreviewRenderContractPolicy
```

Add:
```kotlin
@Volatile private var lastVulkanPreviewRenderSignature: String? = null

private fun activeVulkanPreviewRenderContract(): VulkanPreviewRenderContract {
    val deliveryState = activeVideoFormatDeliveryState()
    val previewLookState = activeVideoPreviewLookState()
    val recordingActive =
        gpuRecordState.value is GpuRecordingState.Recording ||
            gpuRecordState.value is GpuRecordingState.Stopping
    val logCurve = if (previewLookState.logEnabled || deliveryState.logVideoActive) {
        NeoLogProfilePolicy.resolve(_colorProfile.value, logEnabled = true).curve
    } else {
        NeoLogCurve.NONE
    }
    return VulkanPreviewRenderContractPolicy.resolve(
        pipelineContract = deliveryState.pipelineContract,
        previewLookState = previewLookState,
        nativePreviewLutEnabled = nativePreviewLutEnabled,
        nativePreviewLutSize = nativePreviewLutSize,
        logCurve = logCurve,
        cinemaDebayerActive = _uiMode.value == AppMode.VIDEO &&
            _cinemaVideoMode.value == CinemaVideoMode.RAW,
        highLoadPreviewActive = isHighLoadPreviewMode() || recordingActive,
        previewFpsHint = lastPreviewFpsSample.takeIf { it > 0f }
            ?: VIDEO_IDLE_PREVIEW_FPS.toFloat()
    )
}
```

Use that contract inside `applyFrameGeometry(...)` when building preview grade. Add `refreshVulkanPreviewRenderConfig(force: Boolean)` and call it after HDR mode changes, cinema mode changes, and LUT application. It should update `vulkanRuntime.setHdrDynamicRangeMode(contract.swapchainDynamicRangeMode)` and then `vulkanRuntime.updateRenderConfig(...)` without touching preview aspect functions.

- [x] **Step 3: Keep aspect code untouched**

Do not edit:
- `CameraVulkanPreviewView.kt`
- `PreviewAspectResolver.kt`
- `PreviewFramingResolver.kt`
- `VulkanPreviewTransformEngine.kt`
- native viewport/scissor in `vulkan_bridge.cpp`

Run:
```powershell
git diff -- app/src/main/java/com/neoproc/neoproccamx/rendering/PreviewAspectResolver.kt app/src/main/java/com/neoproc/neoproccamx/ui/VulkanPreviewTransformEngine.kt app/src/main/java/com/neoproc/neoproccamx/rendering/CameraVulkanPreviewView.kt app/src/main/cpp/vulkan_bridge.cpp
```

Expected: no new diff from this task.

### Task 3: Verification

**Files:**
- No new production files.

- [x] **Step 1: Run focused tests**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.rendering.VulkanPreviewRenderContractPolicyTest --tests com.neoproc.neoproccamx.rendering.HdrSwapchainFormatPolicyTest --tests com.neoproc.neoproccamx.rendering.VulkanToneDeliveryModeTest --tests com.neoproc.neoproccamx.ui.VulkanPreviewTransformEngineTest
```

Expected: all selected tests pass.

- [x] **Step 2: Run full unit tests**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat :app:testDebugUnitTest
```

Expected: all debug unit tests pass.

- [x] **Step 3: Build and lint**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat :app:assembleDebug
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'; .\gradlew.bat :app:lintDebug
```

Expected: both commands exit 0.

- [x] **Step 4: Device smoke test**

```powershell
$adb='C:\Users\acer\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb install -r app/build/outputs/apk/debug/app-debug.apk
& $adb shell am force-stop com.neoproc.neoproccamx
& $adb shell am start -n com.neoproc.neoproccamx/.ui.MainActivity
Start-Sleep -Seconds 4
& $adb logcat -d -t 300 | Select-String -Pattern 'AndroidRuntime|FATAL EXCEPTION|VulkanRuntime|NeoProCamX_Geom'
```

Expected: app launches, no fatal crash, and no aspect-regression log evidence from this change.

---

## Self-Review

- Spec coverage: The plan upgrades Vulkan render/tone contract, immediate mode refresh, HDR/LOG/RAW preview intent, quality tiers, and explicitly protects aspect code.
- Placeholder scan: No TBD/TODO placeholders are present.
- Type consistency: Types are defined in Task 1 and imported in Task 2 with stable names.
