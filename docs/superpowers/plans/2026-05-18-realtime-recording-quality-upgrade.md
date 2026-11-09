# Realtime Recording Quality Upgrade Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade real-time recorded video quality and recording-path noise reduction while keeping preview/recording parity and truthful SDR/HDR/LOG/RAW delivery.

**Architecture:** Use the existing policy boundaries instead of adding another recording path. Camera2 continues to request high-quality ISP cleanup for encoded recording, `SceneRenderProfileEngine` raises recording-only chroma/spatial denoise floors with motion-safe temporal limits, and `RecordingEncoderFormatPolicy` preserves cleaner frames with stricter high-quality QP windows.

**Tech Stack:** Android Camera2, MediaCodec/MediaFormat, Kotlin, JUnit4, Gradle, adb device validation.

---

## File Structure

- Modify `app/src/test/java/com/neoproc/neoproccamx/recording/RecordingEncoderFormatPolicyTest.kt`
  - Owns encoder format policy regression tests for quality/QP/metadata.
- Modify `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingEncoderFormatPolicy.kt`
  - Owns MediaFormat quality hints, QP windows, B-frame hints, low-latency flags, and compatibility retry behavior.
- Modify `app/src/test/java/com/neoproc/neoproccamx/pipeline/SceneRenderProfileEngineTest.kt`
  - Owns render/denoise/sharpen profile regression tests across SDR, HDR, LOG, and RAW-like looks.
- Modify `app/src/main/java/com/neoproc/neoproccamx/pipeline/SceneRenderProfileEngine.kt`
  - Owns recording-only spatial/chroma/temporal denoise floors, sharpening limits, dither suppression, and HDR/LOG look preservation.
- Read-only verification unless a regression is found:
  - `app/src/test/java/com/neoproc/neoproccamx/camera/Camera2RecordingNoiseCleanupPolicyTest.kt`
  - `app/src/main/java/com/neoproc/neoproccamx/camera/CameraCineProcessingPolicy.kt`
  - `app/src/test/java/com/neoproc/neoproccamx/camera/Camera2PipelineRequestPolicyTest.kt`
  - `app/src/main/java/com/neoproc/neoproccamx/camera/Camera2PipelineRequestPolicy.kt`

## Task 1: Tighten Encoder Preservation For Cinema Recording

**Files:**
- Modify: `app/src/test/java/com/neoproc/neoproccamx/recording/RecordingEncoderFormatPolicyTest.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingEncoderFormatPolicy.kt`

- [ ] **Step 1: Update the failing HDR/Main10 encoder tuning test**

In `RecordingEncoderFormatPolicyTest.kt`, change the expected values inside `maxQualityFormatAppliesEncoderQuantizationAndReorderingTuning` to the stricter cinema window:

```kotlin
assertEquals(0, tuning.qpMin)
assertEquals(20, tuning.qpMax)
assertEquals(0, tuning.iFrameQpMin)
assertEquals(16, tuning.iFrameQpMax)
assertEquals(1, tuning.pFrameQpMin)
assertEquals(18, tuning.pFrameQpMax)
assertEquals(2, tuning.bFrameQpMin)
assertEquals(20, tuning.bFrameQpMax)
assertEquals(2, tuning.maxBFrames)
assertEquals(0, tuning.lowLatency)
assertEquals(3, tuning.intraRefreshPeriodFrames)
```

- [ ] **Step 2: Add a failing SDR cinema encoder tuning test**

Add this test after `maxQualityFormatAppliesEncoderQuantizationAndReorderingTuning`:

```kotlin
@Test
fun cinemaSdrFormatUsesTighterQuantizationWithoutChangingSdrMetadata() {
    val result = RecordingEncoderFormatPolicy.buildVideoFormat(
        request = request(
            bitrate = 900_000_000,
            iFrameIntervalSeconds = 0.10f,
            bitrateMode = MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ,
            qualityTier = RecordingCodecCatalog.QualityTier.CINEMA,
            colorEncoding = RecordingColorEncodingConfig(
                colorStandard = MediaFormat.COLOR_STANDARD_BT709,
                colorTransfer = MediaFormat.COLOR_TRANSFER_SDR_VIDEO,
                colorRange = MediaFormat.COLOR_RANGE_FULL,
                requiresTenBit = false,
                main10Requested = true
            ),
            profile = MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
        )
    )

    val tuning = requireNotNull(result.spec.qualityTuning)
    assertEquals(22, tuning.qpMax)
    assertEquals(18, tuning.iFrameQpMax)
    assertEquals(20, tuning.pFrameQpMax)
    assertEquals(22, tuning.bFrameQpMax)
    assertEquals(MediaFormat.COLOR_STANDARD_BT709, result.spec.colorStandard)
    assertEquals(MediaFormat.COLOR_TRANSFER_SDR_VIDEO, result.spec.colorTransfer)
    assertEquals(HdrStaticMetadataPolicy.StaticMetadataMode.SDR, result.staticMetadataMode)
    assertFalse(result.staticMetadataAttached)
}
```

- [ ] **Step 3: Run the focused encoder test and confirm it fails**

Run:

```powershell
.\gradlew.bat --no-daemon --console=plain :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.recording.RecordingEncoderFormatPolicyTest"
```

Expected before implementation: failure because current CINEMA QP max is `24` for HDR/Main10 and `26` for SDR.

- [ ] **Step 4: Implement the tighter QP window**

In `RecordingEncoderFormatPolicy.kt`, replace the `val maxQp = when (request.qualityTier) { ... }` block inside `resolveQualityTuning` with:

```kotlin
val maxQp = when (request.qualityTier) {
    RecordingCodecCatalog.QualityTier.CINEMA -> if (hdrOrTenBit) 20 else 22
    RecordingCodecCatalog.QualityTier.PROFESSIONAL -> if (hdrOrTenBit) 24 else 26
    RecordingCodecCatalog.QualityTier.STANDARD -> if (hdrOrTenBit) 30 else 32
}
```

Keep the existing `QuantizerWindow` structure:

```kotlin
QuantizerWindow(
    qpMin = 0,
    qpMax = maxQp,
    iFrameQpMin = 0,
    iFrameQpMax = (maxQp - 4).coerceAtLeast(16),
    pFrameQpMin = 1,
    pFrameQpMax = (maxQp - 2).coerceAtLeast(18),
    bFrameQpMin = 2,
    bFrameQpMax = maxQp
)
```

This preserves the existing compatibility retry path: if a device rejects advanced tuning, `RecordingManager` retries with `advancedQualityTuningEnabled = false`.

- [ ] **Step 5: Run the focused encoder test and confirm it passes**

Run:

```powershell
.\gradlew.bat --no-daemon --console=plain :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.recording.RecordingEncoderFormatPolicyTest"
```

Expected after implementation: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit encoder preservation changes**

Run:

```powershell
git add -- app/src/test/java/com/neoproc/neoproccamx/recording/RecordingEncoderFormatPolicyTest.kt app/src/main/java/com/neoproc/neoproccamx/recording/RecordingEncoderFormatPolicy.kt
git commit -m "Upgrade recording encoder preservation"
```

Expected: commit contains only the encoder policy and encoder policy test changes.

## Task 2: Raise Recording-Only Noise Reduction Floors Without Ghosting

**Files:**
- Modify: `app/src/test/java/com/neoproc/neoproccamx/pipeline/SceneRenderProfileEngineTest.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/pipeline/SceneRenderProfileEngine.kt`

- [ ] **Step 1: Add a failing regression test for recording RGB grain**

Add this test near the existing recording noise tests in `SceneRenderProfileEngineTest.kt`, after `strictRecordingNoiseOnlyPassPushesRgbGrainNearCeilingAcrossAllLooks`:

```kotlin
@Test
fun recordingPipelineAppliesHighEndNoiseFloorToMovingRgbGrainAcrossLooks() {
    val previousEnabled = HighEndQualityPolicy.enabled
    val previousStrict = HighEndQualityPolicy.strictHighEndOnly
    HighEndQualityPolicy.enabled = true
    HighEndQualityPolicy.strictHighEndOnly = true
    try {
        val movingRgbGrain = scene(
            averageLuma = 0.28f,
            estimatedLux = 34f,
            motionScore = 0.34f,
            edgeDensity = 0.14f,
            dynamicRangeScore = 0.64f,
            shadowRatio = 0.30f,
            highlightRatio = 0.08f,
            normalizedNoiseFloor = 0.56f,
            flatRegionStdDev = 11.2f,
            realIsoNoiseFloor = 2_200f,
            perceptualSharpness = 0.34f
        )
        val denoiseRecommendation = recommendation(
            sharpeningBoost = 0.07f,
            hdrBracketEvs = listOf(-2, -1, 0, 1, 2)
        ).copy(
            shadowDenoiseStrength = 0.78f,
            spatialDenoiseFloor = 0.64f,
            detailPreservationStrength = 0.78f
        )
        val profiles = listOf(
            engine.build(
                scene = movingRgbGrain,
                recommendation = denoiseRecommendation,
                logEnabled = false,
                hdrEnabled = false,
                colorProfile = "Natural",
                recording = true,
                previewFps = 30f
            ),
            engine.build(
                scene = movingRgbGrain,
                recommendation = denoiseRecommendation,
                logEnabled = false,
                hdrEnabled = true,
                colorProfile = "Rec.2020",
                hdrMode = "HLG",
                recording = true,
                previewFps = 30f
            ),
            engine.build(
                scene = movingRgbGrain,
                recommendation = denoiseRecommendation,
                logEnabled = true,
                hdrEnabled = false,
                colorProfile = "RED Log3G10",
                recording = true,
                previewFps = 30f
            )
        )

        profiles.forEach { profile ->
            assertEquals(0f, profile.ditherStrength, 0.0001f)
            assertTrue("temporalBlend=${profile.temporalBlend}", profile.temporalBlend < 0.55f)
            assertTrue("spatialDenoise=${profile.spatialDenoise}", profile.spatialDenoise >= 0.985f)
            assertTrue("chromaDenoise=${profile.chromaDenoise}", profile.chromaDenoise >= 0.999f)
            assertTrue("sharpening=${profile.sharpening}", profile.sharpening in 0.18f..0.32f)
        }
    } finally {
        HighEndQualityPolicy.enabled = previousEnabled
        HighEndQualityPolicy.strictHighEndOnly = previousStrict
    }
}
```

- [ ] **Step 2: Run the focused scene profile test and confirm it fails**

Run:

```powershell
.\gradlew.bat --no-daemon --console=plain :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.pipeline.SceneRenderProfileEngineTest"
```

Expected before implementation: failure on `spatialDenoise >= 0.985f` or `chromaDenoise >= 0.999f`.

- [ ] **Step 3: Implement stronger recording-only zero-noise floors**

In `SceneRenderProfileEngine.kt`, find the `if (zeroNoiseActive) {` block. Replace the `recordingBoost`, `chromaFloor`, `spatialFloor`, `temporalFloor`, and `sharpeningCeiling` calculations inside that block with:

```kotlin
val recordingBoost = if (recording) 0.105f else 0f
val chromaFloor = (
    0.996f +
        zeroNoisePressure * 0.006f +
        if (recording) 0.003f else 0f
    ).coerceIn(0.996f, 0.9997f)
val spatialFloor = (
    0.948f +
        zeroNoisePressure * 0.058f +
        staticness * 0.035f +
        recordingBoost
    ).coerceIn(0.948f, 0.995f)
val temporalCeiling = when {
    motionFactor >= 0.50f -> 0.54f
    motionFactor >= 0.30f -> 0.60f
    else -> 0.72f
}
val temporalFloor = (
    0.30f +
        zeroNoisePressure * 0.18f * staticness +
        if (recording) 0.075f * staticness else 0f
    ).coerceIn(0.22f, temporalCeiling)
val sharpeningCeiling = (
    0.30f -
        zeroNoisePressure * 0.045f -
        if (recording) 0.010f else 0f
    ).coerceIn(0.18f, 0.30f)
```

Keep the existing `edgeAwareSharpeningFloor` expression and assignments:

```kotlin
temporalBlend = maxOf(temporalBlend, temporalFloor)
spatialDenoise = maxOf(spatialDenoise, spatialFloor)
chromaDenoise = maxOf(chromaDenoise, chromaFloor)
sharpening = maxOf(minOf(sharpening, sharpeningCeiling), edgeAwareSharpeningFloor)
ditherStrength = 0f
```

This increases recording chroma/spatial cleanup for moving RGB grain while preserving the existing temporal ghosting ceiling.

- [ ] **Step 4: Run the focused scene profile test and confirm it passes**

Run:

```powershell
.\gradlew.bat --no-daemon --console=plain :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.pipeline.SceneRenderProfileEngineTest"
```

Expected after implementation: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit scene denoise changes**

Run:

```powershell
git add -- app/src/test/java/com/neoproc/neoproccamx/pipeline/SceneRenderProfileEngineTest.kt app/src/main/java/com/neoproc/neoproccamx/pipeline/SceneRenderProfileEngine.kt
git commit -m "Upgrade recording noise reduction floors"
```

Expected: commit contains only the render profile engine and render profile tests.

## Task 3: Verify Camera2 Recording Cleanup And Preview Parity Contracts

**Files:**
- Read: `app/src/test/java/com/neoproc/neoproccamx/camera/Camera2RecordingNoiseCleanupPolicyTest.kt`
- Read: `app/src/test/java/com/neoproc/neoproccamx/camera/Camera2PipelineRequestPolicyTest.kt`
- Read: `app/src/test/java/com/neoproc/neoproccamx/camera/CameraSessionSurfaceRolePolicyTest.kt`

- [ ] **Step 1: Run the focused Camera2 quality contract tests**

Run:

```powershell
.\gradlew.bat --no-daemon --console=plain :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.camera.Camera2RecordingNoiseCleanupPolicyTest" --tests "com.neoproc.neoproccamx.camera.Camera2PipelineRequestPolicyTest" --tests "com.neoproc.neoproccamx.camera.CameraSessionSurfaceRolePolicyTest"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: If a Camera2 parity test fails, restore encoded-video parity without weakening RAW**

If `Camera2PipelineRequestPolicyTest` fails, ensure `Camera2PipelineRequestPolicy.resolve(...)` maps non-RAW encoded recording to `Camera2PipelineMode.VIDEO_PREVIEW_RECORDING_PARITY` and `Camera2NoiseCleanupProfile.ENCODED_VIDEO_STRICT`, while RAW maps to `RAW_SENSOR_TRUTHFUL`.

The expected assertions in the test file must stay equivalent to:

```kotlin
assertEquals(Camera2PipelineMode.VIDEO_PREVIEW_RECORDING_PARITY, plan.mode)
assertEquals(Camera2NoiseCleanupProfile.ENCODED_VIDEO_STRICT, plan.noiseCleanupProfile)
```

For RAW, the expected assertions must stay equivalent to:

```kotlin
assertEquals(Camera2PipelineMode.RAW_SENSOR_CAPTURE, plan.mode)
assertEquals(Camera2NoiseCleanupProfile.RAW_SENSOR_TRUTHFUL, plan.noiseCleanupProfile)
```

- [ ] **Step 3: Commit only if Camera2 code or tests changed**

Run only when files changed:

```powershell
git add -- app/src/main/java/com/neoproc/neoproccamx/camera/Camera2PipelineRequestPolicy.kt app/src/test/java/com/neoproc/neoproccamx/camera/Camera2PipelineRequestPolicyTest.kt app/src/main/java/com/neoproc/neoproccamx/camera/CameraSessionSurfaceRolePolicy.kt app/src/test/java/com/neoproc/neoproccamx/camera/CameraSessionSurfaceRolePolicyTest.kt
git commit -m "Preserve recording preview parity contracts"
```

Expected: skip this commit if the focused Camera2 tests already pass without changes.

## Task 4: Full Build, Install, And Runtime Recording Validation

**Files:**
- Build artifact: `app/build/outputs/apk/debug/app-debug.apk`
- Runtime artifacts: `qa-artifacts/realtime-recording-quality-logcat.txt`

- [ ] **Step 1: Run focused policy tests together**

Run:

```powershell
.\gradlew.bat --no-daemon --console=plain :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.recording.RecordingEncoderFormatPolicyTest" --tests "com.neoproc.neoproccamx.pipeline.SceneRenderProfileEngineTest" --tests "com.neoproc.neoproccamx.camera.Camera2RecordingNoiseCleanupPolicyTest" --tests "com.neoproc.neoproccamx.camera.Camera2PipelineRequestPolicyTest" --tests "com.neoproc.neoproccamx.camera.CameraSessionSurfaceRolePolicyTest"
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Run full unit tests and assemble debug APK**

Run:

```powershell
.\gradlew.bat --no-daemon --console=plain :app:testDebugUnitTest :app:assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Install the debug APK on the connected device**

Run:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$serial = '10BF8M2NDX003P3'
& $adb -s $serial install -r app\build\outputs\apk\debug\app-debug.apk
```

Expected: `Success`.

- [ ] **Step 4: Record a normal VIDEO debug clip and capture logs**

Run:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$serial = '10BF8M2NDX003P3'
$pkg = 'com.neoproc.neoproccamx'
New-Item -ItemType Directory -Force qa-artifacts | Out-Null
& $adb -s $serial logcat -c
& $adb -s $serial shell am start -W -n "$pkg/.ui.MainActivity"
Start-Sleep -Seconds 4
& $adb -s $serial shell am broadcast -a com.neoproc.neoproccamx.DEBUG_COMMAND -n "$pkg/.ui.DebugCommandReceiver" --es debug_preset VIDEO --es debug_action record_clip --es debug_duration_ms 2400 --es debug_validation_label qa_realtime_quality
Start-Sleep -Seconds 12
& $adb -s $serial logcat -d -v time > qa-artifacts\realtime-recording-quality-logcat.txt
```

Expected:

- broadcast result reports the debug command was received
- logcat contains `Camera2 pipeline=VIDEO_PREVIEW_RECORDING_PARITY`
- logcat contains `Video format:`
- logcat contains `Recording saved`
- no fatal crash lines for `AndroidRuntime`, `CameraDevice`, or `RecordingManager`

- [ ] **Step 5: Inspect the runtime validation log**

Run:

```powershell
Select-String -Path qa-artifacts\realtime-recording-quality-logcat.txt -Pattern 'debugAction recordClip|Camera2 pipeline|HDR session profiles|Video format:|qualityTier|Recording saved|Recording started|Camera2 direct recording started|All-Intra|Prepared:|AndroidRuntime|FATAL EXCEPTION|CameraDevice.*error|RecordingManager.*error'
```

Expected:

- `Camera2 pipeline=VIDEO_PREVIEW_RECORDING_PARITY`
- normal VIDEO clip remains SDR in saved metadata
- no `FATAL EXCEPTION`
- no camera session failure during the recording clip

- [ ] **Step 6: Final git status check**

Run:

```powershell
git status --short -- app/src/main/java/com/neoproc/neoproccamx/recording/RecordingEncoderFormatPolicy.kt app/src/test/java/com/neoproc/neoproccamx/recording/RecordingEncoderFormatPolicyTest.kt app/src/main/java/com/neoproc/neoproccamx/pipeline/SceneRenderProfileEngine.kt app/src/test/java/com/neoproc/neoproccamx/pipeline/SceneRenderProfileEngineTest.kt docs/superpowers/plans/2026-05-18-realtime-recording-quality-upgrade.md
```

Expected: no uncommitted changes for the files modified by this plan after the task commits are made.

## Self-Review

- Spec coverage: Task 1 covers encoder preservation and truthful metadata. Task 2 covers recording-path RGB grain/noise reduction and controlled sharpening. Task 3 covers Camera2 ISP cleanup and preview/recording parity. Task 4 covers build, deploy, and debug recording validation.
- Red-flag scan: no incomplete implementation instructions are present.
- Type consistency: test and implementation names match existing files: `RecordingEncoderFormatPolicy`, `RecordingEncoderQualityTuning`, `SceneRenderProfileEngine`, `HighEndQualityPolicy`, `Camera2RecordingNoiseCleanupPolicy`, and `Camera2PipelineRequestPolicy`.
