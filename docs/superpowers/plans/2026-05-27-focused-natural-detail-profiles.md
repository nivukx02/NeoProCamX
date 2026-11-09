# Focused And Natural Detail Video Profiles Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add selectable `Focused Detail` and `Natural Detail` video profiles that improve visible detail truthfully: subject-restricted enhancement where a real mask exists, zero added sharpening in Natural Detail, stable denoise behavior, and evidence that distinguishes preview/export processing from unvalidated standard recording paths.

**Architecture:** A persisted video-detail selection is resolved into a pure policy plan alongside the existing noise and sharpening plans. The current AI `SubjectMask` is promoted from overlay telemetry to an optional GPU/export mask; it gates detail gain only, while temporal/chroma denoise remains full-frame. Existing Camera2 fallback recording remains functional and is never described as GPU-processed unless frame-import validation succeeds. NPV10 masters stay unchanged; derived MOV export may apply the selected processing and records exactly what happened.

**Tech Stack:** Kotlin, Android Camera2, StateFlow, Vulkan/JNI/GLSL, NPV10/MOV exporter, JUnit, Gradle, adb and `ffprobe`.

---

## Current Code Boundaries

- The output sharpening and noise policies already exist in `VideoDetailSharpeningPolicy.kt`, `VideoNoiseReductionPolicy.kt`, and `SceneRenderProfileEngine.kt`.
- `FocusEngine.kt` already produces an AI `SubjectMask`, and `CameraViewModel.kt` exposes it, but it is currently only consumed by UI overlay code.
- `GpuImageEnhancementContract.kt` sends scene parameters to the Vulkan bridge through a 40-float uniform packet; no subject-mask or detail-profile fields exist yet.
- `Npv10MovConverter.kt` already enhances converted output and records audit evidence; private NPV10 master payloads must remain unmodified.
- `vulkanRecordingPathQualified()` already protects recorded output after a connected-device test showed that unqualified internal GPU import delivered no recording frames. This gate stays in place.

## Non-Negotiable Truth Rules

- `Natural Detail` sets additive detail sharpening to zero; it is not a blurred mode and still uses color, denoise, and encoding policies.
- `Focused Detail` adds detail only inside a valid subject/tap-focus mask and fades gain at mask edges. Missing or stale mask means no subject detail gain.
- Neither mode may claim literal zero sensor noise, synthetic resolution gain, or cinema-camera equivalence.
- RAW and private NPV10 masters are not destructively denoised or sharpened.
- Standard encoded recordings only claim selected GPU detail processing when GPU-to-encoder imported frames are validated.

## Task 1: Add The User-Selectable Detail Profile Domain

**Files:**
- Create: `app/src/main/java/com/neoproc/neoproccamx/pipeline/VideoDetailProfile.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/utils/PreferencesManager.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraSettingsController.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/SettingsOptionCatalog.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/SettingsSectionFactory.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/pipeline/VideoDetailProfileTest.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/ui/controls/SettingsOptionCatalogTest.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/ui/CameraDomainControllerCleanArchitectureContractTest.kt`

- [ ] Write failing tests for preference parsing, default profile, two visible settings options, and ViewModel/settings-controller delegation.

```kotlin
enum class VideoDetailProfile(val displayName: String) {
    FOCUSED_DETAIL("Focused Detail"),
    NATURAL_DETAIL("Natural Detail");

    companion object {
        fun fromPreference(value: String?): VideoDetailProfile =
            entries.firstOrNull { entry ->
                entry.name.equals(value, ignoreCase = true) ||
                    entry.displayName.equals(value, ignoreCase = true)
            } ?: FOCUSED_DETAIL
    }
}
```

- [ ] Run the focused tests and confirm they fail because detail selection is not yet present:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*VideoDetailProfileTest" --tests "*CameraSettings*"
```

- [ ] Add `KEY_VIDEO_DETAIL_PROFILE`, a typed preference property, settings state, settings option row, and `CameraViewModel.setVideoDetailProfile(...)`.
- [ ] Keep the UI choice independent from manual color-space and color-science selection; choosing detail must not reset `Rec.709`, `Rec.2020`, `N-Log`, `NDR10+`, or `NIVUKX Vision`.
- [ ] Rerun focused tests and commit:

```powershell
git add app/src/main/java/com/neoproc/neoproccamx/pipeline/VideoDetailProfile.kt app/src/main/java/com/neoproc/neoproccamx/utils/PreferencesManager.kt app/src/main/java/com/neoproc/neoproccamx/ui/CameraSettingsController.kt app/src/main/java/com/neoproc/neoproccamx/ui/SettingsOptionCatalog.kt app/src/main/java/com/neoproc/neoproccamx/ui/SettingsSectionFactory.kt app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt app/src/test/java/com/neoproc/neoproccamx/pipeline/VideoDetailProfileTest.kt app/src/test/java/com/neoproc/neoproccamx/ui/controls/SettingsOptionCatalogTest.kt app/src/test/java/com/neoproc/neoproccamx/ui/CameraDomainControllerCleanArchitectureContractTest.kt
git commit -m "feat: add video detail profile selection"
```

## Task 2: Create A Pure Subject-Gated Detail Policy

**Files:**
- Create: `app/src/main/java/com/neoproc/neoproccamx/pipeline/SubjectDetailEnhancementPolicy.kt`
- Create: `app/src/test/java/com/neoproc/neoproccamx/pipeline/SubjectDetailEnhancementPolicyTest.kt`

- [ ] Write failing policy tests proving:
  - `NATURAL_DETAIL` always yields `additiveDetailGain = 0f`.
  - `FOCUSED_DETAIL` applies enhancement only with a valid focus/AI mask.
  - A valid mask is treated the same regardless of near or far focused subject distance.
  - High motion, high noise, and skin confidence reduce gain rather than introduce halos or waxy texture.
  - Missing mask yields truthful `subjectDetailApplied=false`.

```kotlin
data class SubjectDetailEnhancementRequest(
    val profile: VideoDetailProfile,
    val maskAvailable: Boolean,
    val maskTrackingId: Int?,
    val maskConfidence: Float,
    val motionScore: Float,
    val noiseFloor: Float,
    val skinToneConfidence: Float
)

data class SubjectDetailEnhancementPlan(
    val profile: VideoDetailProfile,
    val subjectDetailRequested: Boolean,
    val subjectDetailApplied: Boolean,
    val additiveDetailGain: Float,
    val maskFeather: Float,
    val haloGuard: Float,
    val textureGuard: Float,
    val signature: String
)
```

- [ ] Run the new tests and confirm failure before implementation.
- [ ] Implement bounded gain policy with the following invariants:
  - `Natural Detail`: `0f` gain.
  - `Focused Detail`: mask required; maximum bounded gain no greater than existing `VideoDetailSharpeningPolicy` ceiling.
  - Motion/noise reduce gain; they never increase it.
  - Skin protection never drops below the existing scene policy guard.
- [ ] Run `SubjectDetailEnhancementPolicyTest` and commit.

## Task 3: Integrate Detail Selection With Scene Noise And Sharpening Decisions

**Files:**
- Modify: `app/src/main/java/com/neoproc/neoproccamx/pipeline/VideoDetailSharpeningPolicy.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/pipeline/SceneRenderProfileEngine.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/pipeline/VideoDetailSharpeningPolicyTest.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/pipeline/SceneRenderProfileEngineTest.kt`

- [ ] Add failing tests for Natural Detail zero additive sharpening, Focused Detail scene metadata, preview/recording plan equality for identical inputs, and unchanged native resolution claims.
- [ ] Extend the scene-build request with `videoDetailProfile` and `subjectDetailPlan`.
- [ ] Combine `_videoDetailProfile` and `_aiSubjectMask` in the ViewModel scene-profile flow so selected settings affect the next preview/render profile without changing color selections.
- [ ] Apply policy in the existing ordering:

```text
noise plan -> subject detail plan -> detail sharpening plan -> SceneRenderProfile metadata
```

- [ ] Add metadata values to `SceneRenderProfile` and its detail signature:

```text
selectedDetailProfile
subjectDetailRequested
subjectDetailApplied
subjectMaskPresent
subjectDetailGain
naturalZeroSharpening
```

- [ ] Ensure Natural Detail sets only additive shader sharpening to zero; Camera2 hardware behavior remains truthfully described by the processing-reality evidence.
- [ ] Run the pipeline tests and commit.

## Task 4: Carry A Real Subject Mask Into The Vulkan Preview Path

**Files:**
- Modify: `app/src/main/java/com/neoproc/neoproccamx/rendering/GpuImageEnhancementContract.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/rendering/VulkanBridge.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt`
- Modify: `app/src/main/cpp/vulkan_bridge.cpp`
- Modify: `app/src/main/cpp/shaders/vulkan/ycbcr_preview.frag`
- Test: `app/src/test/java/com/neoproc/neoproccamx/rendering/GpuImageEnhancementContractTest.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/rendering/VulkanEnhancementShaderContractTest.kt`

- [ ] First add failing tests for four appended scalar uniform fields and mask update routing:

```text
detailProfileId
subjectMaskAvailable
subjectDetailGain
subjectMaskFeather
```

- [ ] Expand `VulkanBridge.ENHANCEMENT_UNIFORM_FLOAT_COUNT` from `40` to `44` only after the packet tests fail.
- [ ] Add an explicit `updateSubjectDetailMask(maskWidth, maskHeight, alphaMask, trackingId)` JNI route; upload the existing `SubjectMask.alphaMask` as an `R8_UNORM` texture and bind it for the fragment pass.
- [ ] Bind a neutral one-pixel zero mask when no active subject mask exists; do not reuse stale tracked-object masks.
- [ ] Modify the shader so it applies subject gain only to its bounded detail residual:

```glsl
float subjectAlpha = texture(uSubjectDetailMask, inUv).r;
float gatedDetailGain = uSubjectDetailGain * smoothstep(0.0, uSubjectMaskFeather, subjectAlpha);
vec3 subjectDetail = haloSafeDetailResidual(linearColor) * gatedDetailGain;
vec3 outputColor = denoisedColor + subjectDetail;
```

- [ ] Preserve full-frame temporal/chroma denoise and all existing color/PQ/log transforms. The mask must not change color science or transport metadata.
- [ ] For Natural Detail, prove in a shader contract test that `subjectDetailGain` is zero and additive detail is bypassed.
- [ ] Compile shaders/native code and run rendering contract tests; commit.

## Task 5: Preserve Recording Truth While Passing The Selected Detail Contract

**Files:**
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/PixelProcessingRealityPolicy.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/RecordingPipelineParityPolicy.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingQualityEvidencePolicy.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingOutputDescriptionPolicy.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingManager.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/ui/PixelProcessingRealityPolicyTest.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/ui/RecordingStartSessionRefreshContractTest.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/recording/RecordingQualityEvidencePolicyTest.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/recording/RecordingOutputDescriptionPolicyTest.kt`

- [ ] Write failing tests requiring evidence fields:

```text
selectedDetailProfile
previewSubjectDetailApplied
recordingSubjectDetailApplied
recordingGpuFrameImportValidated
detailProcessingPath
```

- [ ] Preserve the existing `vulkanRecordingPathQualified()` requirement and `importedFrames > 0L` validation. Do not set the GPU recording path true solely because Vulkan preview renders.
- [ ] When imported GPU recording frames are validated, send the same uniforms and subject mask used for preview to the encoder render path and report `recordingSubjectDetailApplied=true` only for `Focused Detail` with a live mask.
- [ ] When encoded video is on Camera2 fallback, save `recordingSubjectDetailApplied=false` and `detailProcessingPath=CAMERA2_FALLBACK`; continue saving valid clips rather than forcing the known zero-frame path.
- [ ] Test Normal/Pro/HDR/NDR/NIVUKX/Log/RAW mode truth metadata is unchanged by detail choice; run tests and commit.

## Task 6: Apply Profiles To Derived NPV10 MOV Export Without Modifying Masters

**Files:**
- Modify: `app/src/main/java/com/neoproc/neoproccamx/recording/Npv10MovConverter.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/recording/Npv10ExportEnhancementPolicy.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/recording/Npv10IntegrityValidator.kt`
- Create: `app/src/main/java/com/neoproc/neoproccamx/recording/Npv10SubjectMaskSidecar.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/recording/Npv10MovConverterTest.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/recording/Npv10SubjectMaskSidecarTest.kt`

- [ ] Add failing tests proving:
  - Master frame CRC and payload remain identical before and after conversion.
  - Natural Detail MOV export has temporal/chroma cleaning but zero additive sharpening.
  - Focused Detail export applies gated detail only when a private mask sidecar matches frame timestamp/tracking evidence.
  - Missing sidecar does not claim subject enhancement.
  - MOV Rec.709/BT.2020 PQ/N-Log tags remain driven by the existing selected profile, not by detail selection.
- [ ] Store optional masks as an app-private sidecar adjacent to the private master, under the same `.nomedia` root; do not publish the sidecar or NPV10 master.
- [ ] Resolve profile and frame mask inside the existing triple-buffer export worker, feeding only derived MOV enhancement.
- [ ] Extend the conversion certificate with selected/applied profile and processing-path facts.
- [ ] Run NPV10 tests and commit.

## Task 7: Regression Build, Device Measurement, And Honest Quality Report

**Files:**
- Create: `docs/verification/2026-05-27-focused-natural-detail-device-report.md`
- Use: installed application APK and connected Android device

- [ ] Run unit tests, Android lint, and a debug build:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

- [ ] Install and launch the debug APK on the attached device:

```powershell
$adb = 'C:\Users\acer\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb install -r 'app\build\outputs\apk\debug\app-debug.apk'
& $adb shell am force-stop com.neoproc.neoproccamx
& $adb shell monkey -p com.neoproc.neoproccamx 1
```

- [ ] Record short clips for both profiles in available standard modes and produce an NPV10 derived MOV where supported.
- [ ] Capture logs showing whether GPU encoder import validated or Camera2 fallback was used:

```powershell
& $adb logcat -d | Select-String 'Internal hardware-import recording|recordingGpuFrameImportValidated|detailProcessingPath|NPV10'
```

- [ ] Pull each published MOV and inspect stream truth:

```powershell
ffprobe -v error -show_streams -show_format -of json '<recorded-file.mov>'
```

- [ ] Report measured resolution, codec, pixel format/bit depth where emitted by the device, HDR/log tag state, bitrate, frame count, selected detail profile, processing path, and whether subject enhancement was actually applied.
- [ ] Do not rate recorded detail processing as production-verified unless a saved file was produced through validated imported GPU frames or an audited NPV10 derived export.

## Completion Criteria

- The settings UI persists and applies both `Focused Detail` and `Natural Detail` without changing manual color selections.
- Preview runs full-frame denoise with profile-correct additive detail behavior.
- Subject sharpening is bounded to a real live mask and works for focused near and far objects without a distance exclusion.
- Natural Detail applies no additive sharpening.
- Standard recording metadata distinguishes validated GPU processing from Camera2 fallback.
- NPV10 master content is unchanged and derived MOV export reports its actual enhancement path.
- Unit tests, lint, build, on-device recording, logs, and `ffprobe` evidence are all captured before any production-quality claim.
