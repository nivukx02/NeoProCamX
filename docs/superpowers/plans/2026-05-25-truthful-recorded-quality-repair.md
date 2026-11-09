# Truthful Recorded Quality Repair Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Repair the proven causes of soft, noisy recorded output by exposing only genuinely supported high-resolution choices, preventing Camera2 fallback from claiming processed quality parity, driving quality math with actual stream dimensions, producing a clean no-film-grain default, activating the verified GPU-to-encoder route when supported, and validating NPV10/export evidence.

**Architecture:** Capability and runtime evidence are the source of truth. Encoded modes prefer `INTERNAL_HARDWARE_IMPORT_ENCODER` only after Vulkan/encoder import proof; a Camera2 recording remains usable but is certified as ISP fallback. The existing shared scene profile remains the policy engine, with real dimensions and bounded clean-detail parameters; NPV10 private masters stay unmodified while MOV export applies only disclosed processing.

**Tech Stack:** Kotlin, Android Camera2/MediaCodec, Vulkan GLSL/native bridge, JUnit, Gradle, adb, ffprobe/MediaInfo.

---

## Worktree Constraint

The current worktree already contains extensive uncommitted camera, shader, NPV10, and test work. Execute this plan in the current worktree and do not revert or overwrite unrelated changes. Before each task, inspect the local diff of files being changed and make only additions compatible with existing work.

## File Map

- `app/src/main/java/com/neoproc/neoproccamx/ui/controls/SettingsOptionCatalog.kt`: filters resolution options against real camera/encoder capability.
- `app/src/main/java/com/neoproc/neoproccamx/ui/controls/SettingsSectionFactory.kt`: supplies active device resolution capability to settings.
- `app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt`: exposes available sizes, passes real dimensions to the render engine, chooses and records active feed evidence.
- `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingSelectionPolicy.kt`: resolves requested versus actual source/encoder output without fake resolution.
- `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingManager.kt`: writes requested/source/actual dimensions and runtime path into the certificate.
- `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingQualityEvidencePolicy.kt`: fails unsupported claims and distinguishes processed versus ISP fallback output.
- `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingRuntimeEvidencePolicy.kt`: interprets GPU import proof and Camera2 fallback truthfully.
- `app/src/main/java/com/neoproc/neoproccamx/ui/RecordingPipelineParityPolicy.kt`: prevents Camera2 processed-mode fallback from being a processed-quality `PASS`.
- `app/src/main/java/com/neoproc/neoproccamx/ui/PixelProcessingRealityPolicy.kt`: declares which stages actually reach recorded frames.
- `app/src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt`: applies the selected Camera2 ISP request plan to actual recording requests.
- `app/src/main/java/com/neoproc/neoproccamx/camera/CameraControllerHost.kt`: exposes applied ISP fallback evidence to the view model recording certificate provider.
- `app/src/main/java/com/neoproc/neoproccamx/camera/CameraProcessedVideoQualityRequestPolicy.kt`: selects maximum-supported ISP quality for encoded fallback video.
- `app/src/main/java/com/neoproc/neoproccamx/camera/CameraIspBypassApplicationPolicy.kt`: prevents RAW-only bypass intent from degrading encoded fallback recordings.
- `app/src/main/java/com/neoproc/neoproccamx/pipeline/SceneRenderProfileEngine.kt`: uses real dimensions and clean-detail bounds for all looks.
- `app/src/main/java/com/neoproc/neoproccamx/pipeline/VideoImageQualityPipelinePolicy.kt`: prevents destructive over-smoothing in noisy scenes.
- `app/src/main/java/com/neoproc/neoproccamx/pipeline/VideoNoiseReductionPolicy.kt`: provides motion-safe luma/chroma denoise intent.
- `app/src/main/java/com/neoproc/neoproccamx/pipeline/VideoDetailSharpeningPolicy.kt`: performs texture-safe edge sharpening after denoise.
- `app/src/main/cpp/shaders/vulkan/ycbcr_preview.frag`: removes synthetic moving grain from clean delivery and consumes bounded detail/noise intent.
- `app/src/main/cpp/vulkan_bridge.cpp`, `app/src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt`: prove hardware-import encoder execution and frame counts.
- `app/src/main/java/com/neoproc/neoproccamx/recording/Npv10MovConverter.kt`: applies disclosed export-only clean processing.
- `app/src/main/java/com/neoproc/neoproccamx/recording/Npv10IntegrityValidator.kt`: reports private-master versus enhanced-export truth.
- `tools/quality/run-v17-recorded-quality-repair-gate.ps1`: records modes, gathers evidence, and generates a real-device quality report.

### Task 1: Expose Only Supported Recording Resolutions While Preserving Real 8K

**Files:**
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/controls/SettingsOptionCatalog.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/controls/SettingsSectionFactory.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingSelectionPolicy.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/ui/controls/SettingsOptionCatalogTest.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/recording/RecordingSelectionPolicyTest.kt`

- [ ] **Step 1: Write failing settings tests proving 8K is capability-gated**

Replace unconditional 8K assertions with explicit capability cases:

```kotlin
@Test
fun `resolution choices show 8k only when active camera exposes real 8k output`() {
    val supported8k = SettingsOptionCatalog.resolutionChoicesForMode(
        mode = CinemaVideoMode.PRO,
        supportedOutputSizes = listOf(Size(7680, 4320), Size(3840, 2160), Size(1920, 1080))
    ).map { it.value }
    val limited = SettingsOptionCatalog.resolutionChoicesForMode(
        mode = CinemaVideoMode.PRO,
        supportedOutputSizes = listOf(Size(4096, 3072), Size(3840, 2160), Size(1920, 1080))
    ).map { it.value }

    assertTrue(supported8k.contains(Size(7680, 4320)))
    assertFalse(limited.any { it.width >= 7680 })
    assertTrue(limited.contains(Size(4096, 3072)))
}

@Test
fun `unsupported requested 8k resolves to maximum real device output without upscale`() {
    val resolved = RecordingSelectionPolicy.resolve(
        requestedSize = Size(7680, 4320),
        fps = 30,
        cameraSupportedSizes = listOf(Size(4096, 3072), Size(3840, 2160)),
        preferredCodec = "HEVC",
        resolveSupportedSize = { size, _, _ -> size }
    )

    assertEquals(Size(4096, 3072), resolved.sourceSize)
    assertEquals(Size(4096, 3072), resolved.size)
    assertFalse(resolved.encoderExactMatch)
    assertFalse(resolved.upscaledToRequested)
}
```

- [ ] **Step 2: Run the focused tests and verify they fail because the catalog has no supported-size input**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests '*SettingsOptionCatalogTest' --tests '*RecordingSelectionPolicyTest' --no-daemon --console=plain
```

Expected: `SettingsOptionCatalog.resolutionChoicesForMode` cannot accept `supportedOutputSizes` or the limited-device 8K assertion fails.

- [ ] **Step 3: Implement capability-filtered settings choices**

Change the catalog API to filter named presets against active real outputs and retain an actual exposed maximum such as `4096x3072`:

```kotlin
fun resolutionChoicesForMode(
    mode: CinemaVideoMode,
    supportedOutputSizes: List<Size> = emptyList()
): List<SettingsChoice<Size>> {
    if (supportedOutputSizes.isEmpty()) return standardResolutionChoices
    val supported = supportedOutputSizes
        .map { if (it.width >= it.height) it else Size(it.height, it.width) }
        .distinctBy { it.width to it.height }
        .sortedByDescending { it.width.toLong() * it.height.toLong() }
    val presets = standardResolutionChoices.filter { choice ->
        supported.any { size -> size.width == choice.value.width && size.height == choice.value.height }
    }
    val maximum = supported.firstOrNull()?.let { size ->
        SettingsChoice(
            label = RecordingResolutionPlanner.resolutionLabel(size.width, size.height),
            value = size
        )
    }
    return (listOfNotNull(maximum) + presets).distinctBy { it.value.width to it.value.height }
}
```

Expose real sizes to settings without copying planner state:

```kotlin
internal fun availableVideoSizesForSettings(): List<Size> = availableVideoSizes()
```

Pass them from `SettingsSectionFactory`:

```kotlin
val supportedSizes = viewModel.availableVideoSizesForSettings()
val resolutionChoices = SettingsOptionCatalog.resolutionChoicesForMode(
    mode = viewModel.cinemaVideoMode.value,
    supportedOutputSizes = supportedSizes
)
val selectedResolution = SettingsOptionCatalog.selectedResolutionOrDefault(
    currentSize = currentSize,
    mode = viewModel.cinemaVideoMode.value,
    supportedOutputSizes = supportedSizes
)
```

Update `selectedResolutionOrDefault` to accept and use the same `supportedOutputSizes`.

- [ ] **Step 4: Run the focused tests and confirm true 8K remains available only when exposed**

Run the focused Gradle command from Step 2.

Expected: PASS.

- [ ] **Step 5: Commit this task only**

```powershell
git add app/src/main/java/com/neoproc/neoproccamx/ui/controls/SettingsOptionCatalog.kt app/src/main/java/com/neoproc/neoproccamx/ui/controls/SettingsSectionFactory.kt app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt app/src/main/java/com/neoproc/neoproccamx/recording/RecordingSelectionPolicy.kt app/src/test/java/com/neoproc/neoproccamx/ui/controls/SettingsOptionCatalogTest.kt app/src/test/java/com/neoproc/neoproccamx/recording/RecordingSelectionPolicyTest.kt
git commit -m "Gate recording resolutions by real device capability"
```

### Task 2: Make Processed-Quality Parity Fail Closed On Camera2 Fallback

**Files:**
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/RecordingPipelineParityPolicy.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/PixelProcessingRealityPolicy.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingRuntimeEvidencePolicy.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingQualityEvidencePolicy.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/ui/RecordingPipelineParityPolicyTest.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/ui/PixelProcessingRealityPolicyTest.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/recording/RecordingRuntimeEvidencePolicyTest.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/recording/RecordingQualityEvidencePolicyTest.kt`

- [ ] **Step 1: Write failing tests for the logged bypass path**

Add a fallback-specific status that allows stable recording but denies custom processed-quality proof:

```kotlin
@Test
fun `camera2 preview parity fallback is isp output not processed quality pass`() {
    val plan = resolveParity(
        cinemaMode = CinemaVideoMode.PRO,
        requestedHdrMode = "OFF",
        tenBitRequested = true,
        actualFeedPath = RecordingFeedPath.CAMERA2_PREVIEW_PARITY_RECORD_SURFACE
    )

    assertEquals(PreviewRecordingParityStatus.ISP_FALLBACK, plan.parityCertificate.status)
    assertTrue(plan.parityCertificate.camera2DirectFallback)
    assertFalse(plan.parityCertificate.recordingBlocked)
    assertTrue(plan.parityCertificate.failureCodes.contains(
        PreviewRecordingParityFailureCode.CAMERA2_DIRECT_FALLBACK_FOR_PROCESSED_MODE.name
    ))
}

@Test
fun `preview parity camera2 metadata maps to bypass runtime evidence`() {
    val evidence = RecordingRuntimeEvidencePolicy.fromMetadata(
        mapOf(
            "recordingUsesCustomGpu" to "false",
            "customProcessingBypassed" to "true",
            "parityFeedPath" to "CAMERA2_PREVIEW_PARITY_RECORD_SURFACE"
        )::get
    )
    assertEquals("CAMERA2_ISP_ENCODED_FALLBACK", evidence.gpuProcessingPath)
    assertFalse(evidence.gpuProcessingValidated)
}
```

Extend the fixture to show that matching signature strings do not pass when the runtime route is fallback:

```kotlin
@Test
fun `matching signatures cannot prove quality when encoded frames bypass custom processing`() {
    val certificate = RecordingQualityEvidencePolicy.evaluate(
        validNdrRequest().copy(
            processedRecordingFramesValidated = false,
            gpuProcessingPath = "CAMERA2_ISP_ENCODED_FALLBACK"
        )
    )
    assertEquals(RecordingQualityEvidenceStatus.NOT_PROVEN, certificate.status)
    assertTrue(certificate.failures.any { it.code == RecordingQualityGateCode.PROCESSED_RECORDING_NOT_PROVEN })
}
```

- [ ] **Step 2: Run the four focused test classes and verify failure**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests '*RecordingPipelineParityPolicyTest' --tests '*PixelProcessingRealityPolicyTest' --tests '*RecordingRuntimeEvidencePolicyTest' --tests '*RecordingQualityEvidencePolicyTest' --no-daemon --console=plain
```

Expected: FAIL because `ISP_FALLBACK`, `processedRecordingFramesValidated`, and `PROCESSED_RECORDING_NOT_PROVEN` do not yet exist.

- [ ] **Step 3: Implement non-blocking ISP fallback truth**

Extend parity status and detection:

```kotlin
internal enum class PreviewRecordingParityStatus {
    PASS,
    ISP_FALLBACK,
    FAIL
}
```

Within `parityCertificate`, classify either Camera2 encoded surface as a fallback for non-RAW delivery:

```kotlin
val camera2EncodedPathDetected =
    actualFeedPath == RecordingFeedPath.CAMERA2_DIRECT_RECORD_SURFACE ||
        actualFeedPath == RecordingFeedPath.CAMERA2_PREVIEW_PARITY_RECORD_SURFACE
val camera2DirectFallback = camera2EncodedPathDetected && deliveryKind != VideoDeliveryKind.RAW
val status = when {
    failures.any { it != PreviewRecordingParityFailureCode.CAMERA2_DIRECT_FALLBACK_FOR_PROCESSED_MODE } ->
        PreviewRecordingParityStatus.FAIL
    camera2DirectFallback -> PreviewRecordingParityStatus.ISP_FALLBACK
    else -> PreviewRecordingParityStatus.PASS
}
val recordingBlocked = status == PreviewRecordingParityStatus.FAIL
```

Do not mark ISP fallback as processed-frame parity. Add evidence input:

```kotlin
val processedRecordingFramesValidated: Boolean = false
```

and gate it:

```kotlin
if (!request.processedRecordingFramesValidated &&
    request.mode !in setOf(EliteVideoPipelineMode.RAW, EliteVideoPipelineMode.NPV10)
) {
    add(RecordingQualityGateFailure(
        code = RecordingQualityGateCode.PROCESSED_RECORDING_NOT_PROVEN,
        message = "Encoded frames did not prove execution of the selected custom quality pipeline.",
        blocking = false
    ))
}
```

In `RecordingRuntimeEvidencePolicy`, map either fallback feed to:

```kotlin
private const val CAMERA2_ISP_FALLBACK = "CAMERA2_ISP_ENCODED_FALLBACK"
```

- [ ] **Step 4: Run focused tests and confirm fallback remains usable but unproven**

Run the Step 2 command.

Expected: PASS.

- [ ] **Step 5: Commit this task only**

```powershell
git add app/src/main/java/com/neoproc/neoproccamx/ui/RecordingPipelineParityPolicy.kt app/src/main/java/com/neoproc/neoproccamx/ui/PixelProcessingRealityPolicy.kt app/src/main/java/com/neoproc/neoproccamx/recording/RecordingRuntimeEvidencePolicy.kt app/src/main/java/com/neoproc/neoproccamx/recording/RecordingQualityEvidencePolicy.kt app/src/test/java/com/neoproc/neoproccamx/ui/RecordingPipelineParityPolicyTest.kt app/src/test/java/com/neoproc/neoproccamx/ui/PixelProcessingRealityPolicyTest.kt app/src/test/java/com/neoproc/neoproccamx/recording/RecordingRuntimeEvidencePolicyTest.kt app/src/test/java/com/neoproc/neoproccamx/recording/RecordingQualityEvidencePolicyTest.kt
git commit -m "Report Camera2 recording as ISP quality fallback"
```

### Task 2A: Wire And Prove Maximum-Supported ISP Quality For Camera2 Fallback

**Files:**
- Modify: `app/src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/camera/CameraControllerHost.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/camera/CameraProcessedVideoQualityRequestPolicy.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingRuntimeEvidencePolicy.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingManager.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/camera/CameraProcessedVideoQualityRequestPolicyTest.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/camera/CameraIspBypassApplicationPolicyTest.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/recording/RecordingRuntimeEvidencePolicyTest.kt`

- [ ] **Step 1: Write failing tests that require fallback requests and evidence to use the ISP policy**

The existing policy already resolves supported high-quality request values. Add execution/evidence contract tests so an unused policy cannot be reported as active:

```kotlin
@Test
fun `camera2 encoded fallback requests maximum supported isp cleanup and does not apply raw bypass`() {
    val plan = CameraProcessedVideoQualityRequestPolicy.resolve(
        strictHighEndEnabled = true,
        rawVideoCaptureEnabled = false,
        availability = IspBypassAvailability(
            edgeModes = listOf(CaptureRequest.EDGE_MODE_FAST, CaptureRequest.EDGE_MODE_HIGH_QUALITY),
            noiseReductionModes = listOf(CaptureRequest.NOISE_REDUCTION_MODE_FAST, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY),
            hotPixelModes = listOf(CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY),
            shadingModes = listOf(CaptureRequest.SHADING_MODE_HIGH_QUALITY),
            tonemapModes = listOf(CaptureRequest.TONEMAP_MODE_HIGH_QUALITY),
            colorAberrationModes = listOf(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY),
            distortionCorrectionModes = listOf(CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY)
        )
    )!!

    assertEquals(CaptureRequest.EDGE_MODE_HIGH_QUALITY, plan.edgeMode)
    assertEquals(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY, plan.noiseReductionMode)
    assertFalse(CameraIspBypassApplicationPolicy.resolve(
        cinemaFidelityOverridesEnabled = true,
        rawVideoCaptureEnabled = false,
        recordingActive = true,
        readoutSidecarActive = false,
        hdrDynamicRangeMode = HdrDynamicRangeMode.SDR
    ).applyRequestKeys)
}

@Test
fun `runtime certificate names isp fallback plan only when request application is logged`() {
    val evidence = RecordingRuntimeEvidencePolicy.fromMetadata(
        mapOf(
            "parityFeedPath" to "CAMERA2_PREVIEW_PARITY_RECORD_SURFACE",
            "customProcessingBypassed" to "true",
            "ispFallbackRequestApplied" to "true",
            "ispFallbackRequestSummary" to "edge=HQ;nr=HQ;hotPixel=HQ;shading=HQ"
        )::get
    )
    assertTrue(evidence.ispFallbackRequestApplied)
    assertTrue(evidence.ispFallbackRequestSummary.contains("nr=HQ"))
}
```

- [ ] **Step 2: Run the focused policy/evidence tests and verify missing application evidence fails**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests '*CameraProcessedVideoQualityRequestPolicyTest' --tests '*CameraIspBypassApplicationPolicyTest' --tests '*RecordingRuntimeEvidencePolicyTest' --no-daemon --console=plain
```

Expected: FAIL until recording metadata has `ispFallbackRequestApplied` and `ispFallbackRequestSummary`, and until `CameraController` calls the resolved policy in a Camera2 encoded fallback request.

- [ ] **Step 3: Apply and report the fallback ISP request plan in the actual Camera2 request builder**

Keep the existing `HardwareAbstractionLayer.resolveProfile` application as the common baseline. When a non-RAW recording uses a Camera2 encoded fallback route, resolve `CameraProcessedVideoQualityRequestPolicy` from the active characteristics and apply each non-null result to `builder` after any RAW-only bypass decision:

```kotlin
val fallbackIspPlan = CameraProcessedVideoQualityRequestPolicy.resolve(
    strictHighEndEnabled = strictHighEndEnabled(),
    rawVideoCaptureEnabled = rawVideoCaptureEnabled,
    availability = characteristics?.toIspBypassAvailability()
)
if (recordingActive && !rawVideoCaptureEnabled && fallbackIspPlan != null) {
    fallbackIspPlan.edgeMode?.let { builder[CaptureRequest.EDGE_MODE] = it }
    fallbackIspPlan.noiseReductionMode?.let { builder[CaptureRequest.NOISE_REDUCTION_MODE] = it }
    fallbackIspPlan.hotPixelMode?.let { builder[CaptureRequest.HOT_PIXEL_MODE] = it }
    fallbackIspPlan.shadingMode?.let { builder[CaptureRequest.SHADING_MODE] = it }
    fallbackIspPlan.tonemapMode?.let { builder[CaptureRequest.TONEMAP_MODE] = it }
    fallbackIspPlan.aberrationMode?.let { builder[CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE] = it }
    fallbackIspPlan.distortionCorrectionMode?.let {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder[CaptureRequest.DISTORTION_CORRECTION_MODE] = it
        }
    }
    appliedProcessedVideoQualityEvidence = mapOf(
        "ispFallbackRequestApplied" to "true",
        "ispFallbackRequestSummary" to fallbackIspPlan.toEvidenceSummary()
    )
}
```

Add `processedVideoQualityEvidence(): Map<String, String>` to `CameraControllerHost`, implement it by returning `appliedProcessedVideoQualityEvidence`, and merge it in the existing `CameraViewModel.buildRecordingRuntimeEvidenceMetadata(...)` result:

```kotlin
return linkedMapOf(
    // Existing Vulkan/feed proof entries.
).apply {
    putAll(camera.processedVideoQualityEvidence())
}
```

Do not claim custom Vulkan denoise or sharpening on this route. Report it as supported Android ISP processing only.

- [ ] **Step 4: Run focused tests and confirm Camera2 fallback has truthful HQ ISP evidence**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests '*CameraProcessedVideoQualityRequestPolicyTest' --tests '*CameraIspBypassApplicationPolicyTest' --tests '*RecordingRuntimeEvidencePolicyTest' --tests '*RecordingQualityEvidencePolicyTest' --no-daemon --console=plain
```

Expected: PASS.

- [ ] **Step 5: Commit this task only**

```powershell
git add app/src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt app/src/main/java/com/neoproc/neoproccamx/camera/CameraControllerHost.kt app/src/main/java/com/neoproc/neoproccamx/camera/CameraProcessedVideoQualityRequestPolicy.kt app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt app/src/main/java/com/neoproc/neoproccamx/recording/RecordingRuntimeEvidencePolicy.kt app/src/main/java/com/neoproc/neoproccamx/recording/RecordingManager.kt app/src/test/java/com/neoproc/neoproccamx/camera/CameraProcessedVideoQualityRequestPolicyTest.kt app/src/test/java/com/neoproc/neoproccamx/camera/CameraIspBypassApplicationPolicyTest.kt app/src/test/java/com/neoproc/neoproccamx/recording/RecordingRuntimeEvidencePolicyTest.kt
git commit -m "Wire high-quality ISP evidence for Camera2 fallback"
```

### Task 3: Drive Denoise And Detail With Actual Source And Output Dimensions

**Files:**
- Modify: `app/src/main/java/com/neoproc/neoproccamx/pipeline/SceneRenderProfileEngine.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingManager.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/pipeline/SceneRenderProfileEngineTest.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/pipeline/VideoDetailSharpeningPolicyTest.kt`

- [ ] **Step 1: Write a failing dimension-propagation test**

Add an explicit live-dimension value and verify the detail signature no longer assumes 4K:

```kotlin
@Test
fun `recording detail truth uses active encoder and source dimensions`() {
    val profile = engine.build(
        scene = scene(edgeDensity = 0.28f, perceptualSharpness = 0.68f),
        recommendation = recommendation(),
        logEnabled = false,
        hdrEnabled = false,
        colorProfile = "Natural",
        recording = true,
        dimensions = SceneRenderDimensions(
            previewWidth = 1280,
            previewHeight = 720,
            sourceWidth = 4096,
            sourceHeight = 3072,
            outputWidth = 4096,
            outputHeight = 3072
        )
    )

    assertTrue(profile.detailSharpeningSignature.contains("TRUE_NATIVE_4096x3072"))
    assertFalse(profile.detailSharpeningSignature.contains("TRUE_NATIVE_3840x2160"))
}
```

- [ ] **Step 2: Run the focused profile tests and verify they fail on the missing dimension contract**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests '*SceneRenderProfileEngineTest' --tests '*VideoDetailSharpeningPolicyTest' --no-daemon --console=plain
```

Expected: FAIL because `SceneRenderDimensions` and the `dimensions` argument are missing.

- [ ] **Step 3: Implement dimension propagation**

Add:

```kotlin
data class SceneRenderDimensions(
    val previewWidth: Int,
    val previewHeight: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val outputWidth: Int,
    val outputHeight: Int
) {
    companion object {
        val DEFAULT = SceneRenderDimensions(1920, 1080, 3840, 2160, 3840, 2160)
    }
}
```

Add `dimensions: SceneRenderDimensions = SceneRenderDimensions.DEFAULT` to `SceneRenderProfileEngine.build` and `defaultProfile`, and replace live fixed dimensions:

```kotlin
width = dimensions.outputWidth
height = dimensions.outputHeight
selectedWidth = dimensions.outputWidth
selectedHeight = dimensions.outputHeight
nativeWidth = dimensions.sourceWidth
nativeHeight = dimensions.sourceHeight
```

At the `CameraViewModel` profile call, resolve the current sizes:

```kotlin
dimensions = SceneRenderDimensions(
    previewWidth = preferredPreviewSize().width,
    previewHeight = preferredPreviewSize().height,
    sourceWidth = lastRecordingSelection?.sourceSize?.width ?: preferredVideoSourceSize().width,
    sourceHeight = lastRecordingSelection?.sourceSize?.height ?: preferredVideoSourceSize().height,
    outputWidth = gpuRecording.videoWidth,
    outputHeight = gpuRecording.videoHeight
)
```

Persist the same actual/source values into recording scene metadata for certificate output.

- [ ] **Step 4: Run focused profile tests and then the complete unit test suite**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests '*SceneRenderProfileEngineTest' --tests '*VideoDetailSharpeningPolicyTest' --no-daemon --console=plain
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
```

Expected: PASS.

- [ ] **Step 5: Commit this task only**

```powershell
git add app/src/main/java/com/neoproc/neoproccamx/pipeline/SceneRenderProfileEngine.kt app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt app/src/main/java/com/neoproc/neoproccamx/recording/RecordingManager.kt app/src/test/java/com/neoproc/neoproccamx/pipeline/SceneRenderProfileEngineTest.kt app/src/test/java/com/neoproc/neoproccamx/pipeline/VideoDetailSharpeningPolicyTest.kt
git commit -m "Use actual stream dimensions for video detail policy"
```

### Task 4: Make Clean Output Default And Remove Injected Moving Grain

**Files:**
- Modify: `app/src/main/cpp/shaders/vulkan/ycbcr_preview.frag`
- Modify: `app/src/test/java/com/neoproc/neoproccamx/rendering/VulkanPreviewShaderContractTest.kt`
- Modify after shader compilation: `app/src/main/cpp/generated/ycbcr_preview.frag.spv`
- Modify after shader compilation: `app/src/main/cpp/generated/ycbcr_preview_frag_spv.h`

- [ ] **Step 1: Write failing shader-contract tests for clean delivery**

Replace tests requiring film-grain execution with tests that allow helper definitions but forbid calls in normal delivery:

```kotlin
@Test
fun shader_cleanDeliveryDoesNotInjectSyntheticTemporalOrPlanckGrain() {
    val cleanStart = shaderSource.indexOf("// TRUTHFUL DELIVERY PATH")
    val cleanEnd = shaderSource.indexOf("// Standard-correct encoding", cleanStart)
    val cleanBody = shaderSource.substring(cleanStart, cleanEnd)

    assertTrue(shaderSource.contains("temporalPhaseShiftedBlueNoiseGrain"))
    assertTrue(shaderSource.contains("planckBlueNoiseJndGrain"))
    assertFalse(cleanBody.contains("linear = temporalPhaseShiftedBlueNoiseGrain"))
    assertFalse(cleanBody.contains("linear = planckBlueNoiseJndGrain"))
}
```

Keep quantization/JND output dither tests separate because bounded dither is not creative film grain.

- [ ] **Step 2: Run the shader contract test and verify it fails on current grain calls**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests '*VulkanPreviewShaderContractTest' --no-daemon --console=plain
```

Expected: FAIL because clean delivery currently calls both synthetic grain functions.

- [ ] **Step 3: Remove clean-path grain execution while preserving bounded transfer dither**

Delete these calls from the clean recording/delivery path:

```glsl
linear = temporalPhaseShiftedBlueNoiseGrain(transformed, linear, logDelivery ? 0.002 : 0.0035, uGrade3.w);
linear = planckBlueNoiseJndGrain(transformed, linear, logDelivery ? 0.18 : 0.24, uGrade3.w);
```

Do not delete `applyJndBlueNoiseDither` from final 8/10-bit transfer encoding. It prevents visible banding and is not an artificial moving film texture.

- [ ] **Step 4: Compile the changed shader through the configured NDK and regenerate its embedded header**

`app/build.gradle` pins NDK `29.0.14206865`; the native bridge includes `generated/ycbcr_preview_frag_spv.h`. Compile the fragment shader with that NDK and regenerate the little-endian `uint32_t` embedded header:

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
$glslc = "$env:LOCALAPPDATA\Android\Sdk\ndk\29.0.14206865\shader-tools\windows-x86_64\glslc.exe"
$src = 'app/src/main/cpp/shaders/vulkan/ycbcr_preview.frag'
$spv = 'app/src/main/cpp/generated/ycbcr_preview.frag.spv'
$header = 'app/src/main/cpp/generated/ycbcr_preview_frag_spv.h'
& $glslc -fshader-stage=fragment -o $spv $src
if ($LASTEXITCODE -ne 0) { throw 'glslc failed for ycbcr_preview.frag' }
$bytes = [System.IO.File]::ReadAllBytes((Resolve-Path $spv))
if (($bytes.Length % 4) -ne 0) { throw 'SPIR-V byte count is not word aligned.' }
$words = for ($i = 0; $i -lt $bytes.Length; $i += 4) {
    ('    0x{0:x8}u' -f [BitConverter]::ToUInt32($bytes, $i))
}
$body = for ($i = 0; $i -lt $words.Count; $i += 8) {
    (($words[$i..([Math]::Min($i + 7, $words.Count - 1))]) -join ', ') + $(if ($i + 8 -lt $words.Count) { ',' } else { '' })
}
$headerLines = @(
    '// Auto-generated from ycbcr_preview.frag - DO NOT EDIT'
    '#pragma once'
    ''
    '#include <cstdint>'
    '#include <cstddef>'
    ''
    'static constexpr uint32_t kYcbcrPreviewFragSpv[] = {'
) + $body + @(
    '};'
    'static constexpr size_t kYcbcrPreviewFragSpvCount = sizeof(kYcbcrPreviewFragSpv) / sizeof(kYcbcrPreviewFragSpv[0]);'
)
[System.IO.File]::WriteAllLines((Join-Path (Get-Location) $header), $headerLines, [System.Text.Encoding]::ASCII)
.\gradlew.bat :app:testDebugUnitTest --tests '*VulkanPreviewShaderContractTest' --tests '*VulkanAcesColorShaderContractTest' --no-daemon --console=plain
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain
```

Expected: PASS and shader/native compilation succeeds.

- [ ] **Step 5: Commit this task only**

```powershell
git add app/src/main/cpp/shaders/vulkan/ycbcr_preview.frag app/src/main/cpp/generated/ycbcr_preview.frag.spv app/src/main/cpp/generated/ycbcr_preview_frag_spv.h app/src/test/java/com/neoproc/neoproccamx/rendering/VulkanPreviewShaderContractTest.kt
git commit -m "Disable synthetic grain in clean video delivery"
```

### Task 5: Activate The Processed Encoder Route Only With Runtime Proof

**Files:**
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/RecordingPreviewContinuityPolicy.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingRuntimeEvidencePolicy.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingManager.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/ui/RecordingPreviewContinuityPolicyTest.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/ui/RecordingStartSessionRefreshContractTest.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/recording/RecordingRuntimeEvidencePolicyTest.kt`

- [ ] **Step 1: Write failing tests for preferred processed routing and verified fallback**

```kotlin
@Test
fun `processed video attempts hardware import when a prearmed encoder surface is eligible`() {
    assertEquals(
        RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER,
        RecordingPreviewContinuityPolicy.resolveFeedPath(
            rawVideoEnabled = false,
            vulkanRuntimeSupported = true,
            processedRouteEligible = true
        )
    )
    assertEquals(
        RecordingFeedPath.CAMERA2_PREVIEW_PARITY_RECORD_SURFACE,
        RecordingPreviewContinuityPolicy.resolveFeedPath(
            rawVideoEnabled = false,
            vulkanRuntimeSupported = true,
            processedRouteEligible = false
        )
    )
}
```

Add runtime-evidence test:

```kotlin
@Test
fun `processed encoder is proven only with imported recorded frames and bound encoder surface`() {
    val evidence = RecordingRuntimeEvidencePolicy.fromMetadata(
        mapOf(
            "recordingUsesCustomGpu" to "true",
            "customProcessingBypassed" to "false",
            "vulkanFrameImportEnabled" to "true",
            "vulkanEncoderSurfaceBound" to "true",
            "vulkanSessionImportedFrames" to "30",
            "vulkanEncodedProcessedFrames" to "30"
        )::get
    )
    assertTrue(evidence.gpuProcessingValidated)
    assertTrue(evidence.processedRecordingFramesValidated)
}
```

- [ ] **Step 2: Run focused routing/evidence tests and verify missing proof fails**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests '*RecordingPreviewContinuityPolicyTest' --tests '*RecordingStartSessionRefreshContractTest' --tests '*RecordingRuntimeEvidencePolicyTest' --no-daemon --console=plain
```

Expected: FAIL until stable capability and processed-frame proof are carried through.

- [ ] **Step 3: Implement bootstrappable capability-gated route selection and post-start proof**

Do not require recorded frames before selecting an internal route for its first session; that would make the route impossible to prove. Set `processedRouteEligible` from the prearmed encoder surface, valid session graph, and Vulkan import support before recording starts:

```kotlin
vulkanRuntimeSupported && processedRouteEligible ->
    RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER
else -> RecordingFeedPath.CAMERA2_PREVIEW_PARITY_RECORD_SURFACE
```

In `CameraViewModel.startInternalHardwareImportRecording`, set eligibility before attempting the bind, but publish processed-quality evidence only after encoder bind, session refresh, import enabled, and at least one frame has been counted:

```kotlin
val encoderBound = vulkanRuntime.setEncoderSurface(encoderSurface)
if (!encoderBound) {
    clearInternalRecordingFeed()
    return startCamera2PreviewParityRecording()
}
// Once recording frames arrive:
runtimeEvidence["recordingUsesCustomGpu"] = "true"
runtimeEvidence["vulkanEncoderSurfaceBound"] = "true"
runtimeEvidence["vulkanEncodedProcessedFrames"] = vulkanRuntime.encodedProcessedFrameCount().toString()
```

Add to `RecordingRuntimeEvidence`:

```kotlin
val processedRecordingFramesValidated: Boolean
```

Calculate it only from actual imported-and-encoded processed frames:

```kotlin
val encodedProcessedFrames = metadataLookup.long("vulkanEncodedProcessedFrames")
val processedRecordingFramesValidated = gpuValidated && encodedProcessedFrames > 0L
```

Forward this into `RecordingQualityEvidenceRequest`.

- [ ] **Step 4: Run focused tests and all unit tests**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests '*RecordingPreviewContinuityPolicyTest' --tests '*RecordingStartSessionRefreshContractTest' --tests '*RecordingRuntimeEvidencePolicyTest' --tests '*RecordingQualityEvidencePolicyTest' --no-daemon --console=plain
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
```

Expected: PASS.

- [ ] **Step 5: Commit this task only**

```powershell
git add app/src/main/java/com/neoproc/neoproccamx/ui/RecordingPreviewContinuityPolicy.kt app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt app/src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt app/src/main/java/com/neoproc/neoproccamx/recording/RecordingRuntimeEvidencePolicy.kt app/src/main/java/com/neoproc/neoproccamx/recording/RecordingManager.kt app/src/test/java/com/neoproc/neoproccamx/ui/RecordingPreviewContinuityPolicyTest.kt app/src/test/java/com/neoproc/neoproccamx/ui/RecordingStartSessionRefreshContractTest.kt app/src/test/java/com/neoproc/neoproccamx/recording/RecordingRuntimeEvidencePolicyTest.kt app/src/test/java/com/neoproc/neoproccamx/recording/RecordingQualityEvidencePolicyTest.kt
git commit -m "Gate processed encoder route with runtime evidence"
```

### Task 6: Bound Denoise And Sharpening To Preserve Detail In All Looks

**Files:**
- Modify: `app/src/main/java/com/neoproc/neoproccamx/pipeline/VideoImageQualityPipelinePolicy.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/pipeline/VideoNoiseReductionPolicy.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/pipeline/VideoDetailSharpeningPolicy.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/pipeline/SceneRenderProfileEngine.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/pipeline/VideoImageQualityPipelinePolicyTest.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/pipeline/VideoNoiseReductionPolicyTest.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/pipeline/VideoDetailSharpeningPolicyTest.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/pipeline/SceneRenderProfileEngineTest.kt`

- [ ] **Step 1: Write failing tests rejecting near-total smoothing of textured scenes**

Current tests accept chroma/spatial near `0.999`; change the contract so strong noise control cannot erase confident texture:

```kotlin
@Test
fun `textured noisy recording bounds denoise and retains edge detail in every look`() {
    val noisyTexture = scene(
        averageLuma = 0.30f,
        estimatedLux = 34f,
        motionScore = 0.16f,
        edgeDensity = 0.32f,
        normalizedNoiseFloor = 0.56f,
        flatRegionStdDev = 11.2f,
        realIsoNoiseFloor = 2200f,
        perceptualSharpness = 0.52f
    )
    listOf(
        Triple(false, false, "Natural"),
        Triple(false, true, "Rec.2020 NDR10+"),
        Triple(true, false, "N-Log Cinema")
    ).forEach { (log, hdr, profile) ->
        val output = engine.build(
            scene = noisyTexture,
            recommendation = recommendation(),
            logEnabled = log,
            hdrEnabled = hdr,
            colorProfile = profile,
            hdrMode = if (hdr) "NDR10+" else "OFF",
            recording = true
        )
        assertTrue(output.spatialDenoise < 0.90f)
        assertTrue(output.chromaDenoise < 0.94f)
        assertTrue(output.sharpening >= 0.22f)
    }
}
```

Also retain strong flat-shadow chroma cleanup with motion ceilings:

```kotlin
@Test
fun `flat low light shadows receive stronger chroma cleanup than textured regions without temporal smear`() {
    val flat = resolveRequest(textureConfidence = 0.02f, edgeDensity = 0.01f, motionScore = 0.36f)
    val textured = resolveRequest(textureConfidence = 0.80f, edgeDensity = 0.32f, motionScore = 0.36f)
    assertTrue(flat.chromaDenoise > textured.chromaDenoise)
    assertTrue(flat.temporalBlend <= flat.motionSafeTemporalCeiling)
}
```

- [ ] **Step 2: Run pipeline tests and observe failures from existing over-smoothing floors**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests '*VideoImageQualityPipelinePolicyTest' --tests '*VideoNoiseReductionPolicyTest' --tests '*VideoDetailSharpeningPolicyTest' --tests '*SceneRenderProfileEngineTest' --no-daemon --console=plain
```

Expected: FAIL on current tests/behavior that drive spatial and chroma suppression toward `0.99+`.

- [ ] **Step 3: Implement texture- and motion-bounded quality aggregation**

Use existing `textureConfidence`, `edgeDensity`, `motionProtection`, and `detailSafeSharpeningCeiling` rather than adding global sharpness:

```kotlin
val detailProtection = maxOf(textureConfidence, edgeConfidence).coerceIn(0f, 1f)
val spatialCeiling = (0.92f - detailProtection * 0.18f).coerceIn(0.62f, 0.92f)
val chromaCeiling = (0.98f - detailProtection * 0.10f).coerceIn(0.72f, 0.98f)
spatialDenoise = spatialDenoise.coerceAtMost(spatialCeiling)
chromaDenoise = chromaDenoise.coerceAtMost(chromaCeiling)
sharpening = sharpening.coerceAtMost(noisePlan.detailSafeSharpeningCeiling)
```

Apply the ceiling after all `maxOf` aggregation in `SceneRenderProfileEngine`; otherwise earlier near-ceiling floors override it. Preserve stronger flat-shadow chroma cleanup when texture confidence is low.

- [ ] **Step 4: Run focused pipeline tests and the suite**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests '*VideoImageQualityPipelinePolicyTest' --tests '*VideoNoiseReductionPolicyTest' --tests '*VideoDetailSharpeningPolicyTest' --tests '*SceneRenderProfileEngineTest' --no-daemon --console=plain
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
```

Expected: PASS and no tests continue to require destructive near-total smoothing for textured content.

- [ ] **Step 5: Commit this task only**

```powershell
git add app/src/main/java/com/neoproc/neoproccamx/pipeline/VideoImageQualityPipelinePolicy.kt app/src/main/java/com/neoproc/neoproccamx/pipeline/VideoNoiseReductionPolicy.kt app/src/main/java/com/neoproc/neoproccamx/pipeline/VideoDetailSharpeningPolicy.kt app/src/main/java/com/neoproc/neoproccamx/pipeline/SceneRenderProfileEngine.kt app/src/test/java/com/neoproc/neoproccamx/pipeline/VideoImageQualityPipelinePolicyTest.kt app/src/test/java/com/neoproc/neoproccamx/pipeline/VideoNoiseReductionPolicyTest.kt app/src/test/java/com/neoproc/neoproccamx/pipeline/VideoDetailSharpeningPolicyTest.kt app/src/test/java/com/neoproc/neoproccamx/pipeline/SceneRenderProfileEngineTest.kt
git commit -m "Bound denoise to preserve recorded texture"
```

### Task 7: Align NPV10 Export With Clean Processing Without Modifying Masters

**Files:**
- Modify: `app/src/main/java/com/neoproc/neoproccamx/recording/Npv10MovConverter.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/recording/Npv10IntegrityValidator.kt`
- Modify: `app/src/main/cpp/shaders/vulkan/neo_p010_export_enhance.comp`
- Test: `app/src/test/java/com/neoproc/neoproccamx/recording/Npv10ExportEnhancementPolicyTest.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/recording/Npv10MovConverterTest.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/recording/Npv10IntegrityValidatorTest.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/rendering/VulkanEnhancementShaderContractTest.kt`

- [ ] **Step 1: Write failing NPV10 tests for source preservation and disclosed clean export**

```kotlin
@Test
fun `clean export reports denoise and detail but no creative grain and leaves master bytes intact`() {
    val masterPayload = p010TextureFixture()
    val original = masterPayload.copyOf()
    val enhanced = Npv10ExportEnhancementPolicy.enhanceP010(
        payload = masterPayload,
        width = 8,
        height = 8,
        profile = Npv10ExportEnhancementProfile.SDR_VIDEO
    )

    assertArrayEquals(original, masterPayload)
    assertFalse(Npv10ExportEnhancementPolicy.cleanMetadata().getValue("syntheticGrainEnabled").toBoolean())
    assertNotEquals(original.toList(), enhanced.toList())
}
```

Update shader contract to require that export has real previous-frame binding but does not call any film-grain function in clean output.

- [ ] **Step 2: Run NPV10 focused tests and verify failure on missing clean metadata/current grain assumptions**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests '*Npv10ExportEnhancementPolicyTest' --tests '*Npv10MovConverterTest' --tests '*Npv10IntegrityValidatorTest' --tests '*VulkanEnhancementShaderContractTest' --no-daemon --console=plain
```

Expected: FAIL until clean-export evidence is introduced and grain-specific expectations are removed.

- [ ] **Step 3: Implement export-only disclosed processing**

Preserve `payload.copyOf()` as the processed output source and add explicit evidence:

```kotlin
fun cleanMetadata(): Map<String, String> = linkedMapOf(
    "exportEnhancementApplied" to "true",
    "temporalDenoiseKind" to "PREVIOUS_FRAME_WHEN_AVAILABLE",
    "edgeAwareDetailApplied" to "true",
    "syntheticGrainEnabled" to "false",
    "privateMasterModified" to "false"
)
```

Do not mutate bytes in `Npv10FileReader` or private master store. Apply P010 enhancement only to the MOV sample buffer. Remove creative grain from the clean export shader/CPU path while retaining format-correct bounded quantization dithering only when conversion requires it.

- [ ] **Step 4: Run NPV10 tests and validate native shader build**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests '*Npv10ExportEnhancementPolicyTest' --tests '*Npv10MovConverterTest' --tests '*Npv10IntegrityValidatorTest' --tests '*VulkanEnhancementShaderContractTest' --no-daemon --console=plain
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain
```

Expected: PASS.

- [ ] **Step 5: Commit this task only**

```powershell
git add app/src/main/java/com/neoproc/neoproccamx/recording/Npv10MovConverter.kt app/src/main/java/com/neoproc/neoproccamx/recording/Npv10IntegrityValidator.kt app/src/main/cpp/shaders/vulkan/neo_p010_export_enhance.comp app/src/test/java/com/neoproc/neoproccamx/recording/Npv10ExportEnhancementPolicyTest.kt app/src/test/java/com/neoproc/neoproccamx/recording/Npv10MovConverterTest.kt app/src/test/java/com/neoproc/neoproccamx/recording/Npv10IntegrityValidatorTest.kt app/src/test/java/com/neoproc/neoproccamx/rendering/VulkanEnhancementShaderContractTest.kt
git commit -m "Keep NPV10 masters clean and disclose export processing"
```

### Task 8: Add Real-Device Quality Gate And Run Verification

**Files:**
- Create: `tools/quality/run-v17-recorded-quality-repair-gate.ps1`
- Modify: `app/src/test/java/com/neoproc/neoproccamx/quality/RealDeviceValidationScriptContractTest.kt`
- Produce at runtime: `qa-artifacts/v17-recorded-quality-repair/quality-report.md`

- [ ] **Step 1: Write failing script-contract tests**

```kotlin
@Test
fun `v17 gate records real modes and rejects unproven premium comparison claims`() {
    val script = File("../../tools/quality/run-v17-recorded-quality-repair-gate.ps1")
        .takeIf { it.isFile } ?: File("tools/quality/run-v17-recorded-quality-repair-gate.ps1")
    val source = script.readText()
    assertTrue(source.contains("Normal"))
    assertTrue(source.contains("Pro"))
    assertTrue(source.contains("N-Log"))
    assertTrue(source.contains("NDR10+"))
    assertTrue(source.contains("NIVUKX Vision"))
    assertTrue(source.contains("ffprobe"))
    assertTrue(source.contains("processedRecordingFramesValidated"))
    assertTrue(source.contains("NOT_PROVEN"))
}
```

- [ ] **Step 2: Run the validation contract test and verify the absent script fails**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests '*RealDeviceValidationScriptContractTest' --no-daemon --console=plain
```

Expected: FAIL because the V17 script does not yet exist.

- [ ] **Step 3: Implement V17 as a strict extension of the existing V16 real-device runner**

Call the existing V16 gate to handle install, adb recording, `ffprobe`, MediaInfo, logcat, and per-row certificate collection; then reject any V17 row that lacks the new processing truth fields:

```powershell
param(
    [string]$AdbPath = "adb",
    [string]$Serial = "",
    [string]$PackageName = "com.neoproc.neoproccamx",
    [string]$OutputDir = "qa-artifacts\v17-recorded-quality-repair",
    [string[]]$IncludeRows = @(),
    [int]$MaxRows = 0,
    [switch]$SkipInstall,
    [switch]$DryRun
)

$v16Script = Join-Path $PSScriptRoot 'run-v16-real-device-encoder-validation.ps1'
$v16Dir = Join-Path $OutputDir 'v16'
$v16Args = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $v16Script,
    '-AdbPath', $AdbPath, '-PackageName', $PackageName, '-OutputDir', $v16Dir)
if (-not [string]::IsNullOrWhiteSpace($Serial)) { $v16Args += @('-Serial', $Serial) }
if ($SkipInstall) { $v16Args += '-SkipInstall' }
if ($DryRun) { $v16Args += '-DryRun' }
if ($MaxRows -gt 0) { $v16Args += @('-MaxRows', $MaxRows.ToString()) }
if ($IncludeRows.Count -gt 0) { $v16Args += '-IncludeRows'; $v16Args += $IncludeRows }
& powershell @v16Args
$v16Exit = $LASTEXITCODE
if ($DryRun) { exit $v16Exit }

$modes = @('Normal', 'Pro', 'N-Log', 'NDR10+', 'NIVUKX Vision', 'NPV10')
$requiredEvidence = @(
    'actualResolution',
    'gpuProcessingPath',
    'processedRecordingFramesValidated',
    'syntheticGrainEnabled',
    'sourceBitDepth',
    'storageBitDepth'
)
$v16Rows = @(Get-Content (Join-Path $v16Dir 'v16-real-device-encoder-report.json') -Raw | ConvertFrom-Json)
$rows = foreach ($row in $v16Rows) {
    $certificate = if (Test-Path $row.CertificatePath) {
        Get-Content $row.CertificatePath -Raw
    } else { '' }
    $missing = @($requiredEvidence | Where-Object { $certificate -notmatch ([regex]::Escape($_) + '=') })
    $cleanDefaultFailed = $certificate -match 'syntheticGrainEnabled=true'
    $processedNotProven = $row.Mode -notmatch 'NPV10|RAW' -and
        $certificate -notmatch 'processedRecordingFramesValidated=true'
    $status = if ($missing.Count -gt 0 -or $cleanDefaultFailed) { 'FAIL' }
        elseif ($processedNotProven) { 'NOT_PROVEN' }
        else { $row.Status }
    [pscustomobject]@{ Mode=$row.Mode; Resolution=$row.Resolution; Status=$status;
        MissingEvidence=($missing -join ','); PremiumComparison='NOT_PROVEN' }
}
$rows | ConvertTo-Json -Depth 5 | Set-Content (Join-Path $OutputDir 'v17-recorded-quality-repair-report.json')
$rows | Export-Csv -NoTypeInformation (Join-Path $OutputDir 'v17-recorded-quality-repair-report.csv')
if (@($rows | Where-Object Status -eq 'FAIL').Count -gt 0) { exit 1 }
if (@($rows | Where-Object Status -eq 'NOT_PROVEN').Count -gt 0 -or $v16Exit -ne 0) { exit 2 }
exit 0
```

Add markdown report formatting following the existing V16 `System.Collections.Generic.List[string]` table pattern, with `PremiumComparison=NOT_PROVEN` until controlled reference recordings and objective measurements exist.

- [ ] **Step 4: Run build gates before device validation**

```powershell
git diff --check
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --warning-mode=fail --no-daemon --console=plain
```

Expected: PASS without build warnings or test failures.

- [ ] **Step 5: Run device validation when an adb device and file probe are available**

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices
.\tools\quality\run-v17-recorded-quality-repair-gate.ps1
```

Expected: A report under `qa-artifacts/v17-recorded-quality-repair`. Quality is `PASS` only for mode/resolution combinations whose recorded files, runtime path, and metadata validate. iPhone/cinema comparison remains `NOT_PROVEN` until controlled reference captures are provided.

- [ ] **Step 6: Commit scripts/tests and runtime report separately**

```powershell
git add tools/quality/run-v17-recorded-quality-repair-gate.ps1 app/src/test/java/com/neoproc/neoproccamx/quality/RealDeviceValidationScriptContractTest.kt
git commit -m "Add recorded quality device evidence gate"
```

Do not commit large device videos. Commit only a concise text/markdown evidence report if requested.

## Final Verification

- [ ] Run whitespace validation:

```powershell
git diff --check
```

- [ ] Run the full warning-fail build:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --warning-mode=fail --no-daemon --console=plain
```

- [ ] With an adb device connected, install and launch the APK, record each exposed mode/resolution tier, and run the V17 quality evidence script.

- [ ] Review the V17 report. State only what the report proves:

```text
Real 8K: PASS only on exposed 8K stream plus validated file metadata.
Processed recording quality: PASS only when encoder-delivered processed frames are proven.
Clean default: PASS only when no synthetic grain execution is recorded.
NPV10: PASS only for truthful source/master/export evidence.
Premium phone/cinema comparison: NOT_PROVEN until controlled reference captures are measured.
```
