# V13 Sharpness Detail Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a truthful V13 sharpness/detail upgrade across shared preview/recording and NPV10 MOV export.

**Architecture:** Extend the existing `VideoImageQualityPipelinePolicy` with detail-specific evidence floors, wire those floors into `SceneRenderProfileEngine`, and strengthen the export-only P010 luma detail pass in `Npv10MovConverter`. NPV10 private masters are not modified.

**Tech Stack:** Kotlin, Android camera pipeline policy, JUnit unit tests, Gradle debug build.

---

### Task 1: Add V13 Policy Tests

**Files:**
- Modify: `app/src/test/java/com/neoproc/neoproccamx/pipeline/VideoImageQualityPipelinePolicyTest.kt`

- [ ] Add a test named `strictEncodedModesIncludeEliteV13SharpnessDetailStages`.
- [ ] Add a test named `cleanV13RaisesDetailLiftWhileNoisySceneStaysHaloSafe`.
- [ ] Add a test named `previewAndRecordingV13UseComparableDetailParity`.
- [ ] Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.pipeline.VideoImageQualityPipelinePolicyTest" --no-daemon --no-configuration-cache --console=plain
```

Expected before implementation: failure for missing V13 stage names or missing plan fields.

### Task 2: Implement V13 Policy Fields

**Files:**
- Modify: `app/src/main/java/com/neoproc/neoproccamx/pipeline/VideoImageQualityPipelinePolicy.kt`

- [ ] Add V13 pipeline stages after V12 texture/detail stages.
- [ ] Add plan fields: `edgeMicroContrastLiftFloor`, `fineTextureDetailBoostFloor`, `haloSafeSharpeningLiftFloor`, `recordingPreviewDetailParityFloor`.
- [ ] Compute fields from clean-detail evidence, texture-edge confidence, chroma-edge separation, detail confidence, motion, noise, and artifact risk.
- [ ] Keep RAW plan values at `0f`.
- [ ] Run the focused policy test until it passes.

### Task 3: Wire Preview/Recording Render Detail

**Files:**
- Modify: `app/src/main/java/com/neoproc/neoproccamx/pipeline/SceneRenderProfileEngine.kt`
- Modify: `app/src/test/java/com/neoproc/neoproccamx/pipeline/SceneRenderProfileEngineTest.kt`

- [ ] Add render tests for clean preview and clean recording detail lift.
- [ ] Use V13 fields in `detailFloor` while preserving `haloSafeSharpeningCeiling`.
- [ ] Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.pipeline.SceneRenderProfileEngineTest" --no-daemon --no-configuration-cache --console=plain
```

Expected after implementation: pass with higher clean-scene sharpening and bounded noisy-scene sharpening.

### Task 4: Upgrade NPV10 Export-Only Detail

**Files:**
- Modify: `app/src/main/java/com/neoproc/neoproccamx/recording/Npv10MovConverter.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/recording/Npv10IntegrityValidator.kt`
- Modify: `app/src/test/java/com/neoproc/neoproccamx/recording/Npv10MovConverterTest.kt`

- [ ] Add certificate flags: `exportV13SharpnessDetailApplied`, `exportEdgeMicroContrastLiftApplied`, `exportFineTextureDetailBoostApplied`, `exportHaloSafeSharpeningLiftApplied`.
- [ ] Increase guarded P010 medium/strong edge detail gain and keep alias-safe max delta bounded.
- [ ] Add a test proving strong edges lift more than V12 while low-contrast texture remains unchanged.
- [ ] Run the NPV10 converter tests.

### Task 5: Full Verification and Deploy

**Files:**
- No code changes.

- [ ] Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --warning-mode=fail --no-daemon --no-configuration-cache --console=plain
```

- [ ] Install and launch with ADB if a device is connected:

```powershell
& 'C:\Users\acer\AppData\Local\Android\Sdk\platform-tools\adb.exe' install -r app\build\outputs\apk\debug\app-debug.apk
& 'C:\Users\acer\AppData\Local\Android\Sdk\platform-tools\adb.exe' shell am start -n com.neoproc.neoproccamx/.ui.MainActivity
```

Expected: build succeeds with warning mode fail, install returns `Success`, and `MainActivity` is resumed.
