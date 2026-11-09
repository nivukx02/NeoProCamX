# Extreme Detailed Mode Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` or `superpowers:executing-plans` to implement this plan task-by-task. Use `superpowers:test-driven-development` for each production change and `superpowers:verification-before-completion` before claiming completion. Steps use checkbox syntax for tracking.

**Goal:** Add a third video detail mode, `EXTREME_DETAIL`, that targets the highest safe perceived detail across preview, capture policy, recording budget, and NPV10 derived export while preserving RAW/private master truth and avoiding global sharpening, pixel duplication, subject-mask dependency, excessive halos, and extra noise amplification.

**Architecture:** `EXTREME_DETAIL` is a selectable user profile. It is not a subject mask mode. Kotlin policies raise detail floors only when signal/noise gates allow it, increase cleanup when noise is present, pass a dedicated profile id to GPU shaders, raise non-strict recording bitrate headroom, and apply derived-only NPV10 export enhancement without altering masters.

**Tech Stack:** Kotlin, JUnit 4, Android Camera2/MediaCodec policy code, GLSL/Vulkan shaders, SPIR-V `glslc`, Gradle, PowerShell.

---

## Scope Check

- Add exactly one new user-facing detail profile: `VideoDetailProfile.EXTREME_DETAIL`.
- Keep `FOCUSED_DETAIL` and `NATURAL_DETAIL` behavior stable except where tests explicitly compare against the new mode.
- Do not make `EXTREME_DETAIL` require a subject segmentation mask.
- Do not enable destructive enhancement of NPV10/private masters.
- Do not add frame interpolation, pixel duplication, synthetic upscaling, or global blanket sharpening.
- Do not change UI layout beyond adding the third option to the existing catalog.

## File Structure

Primary files:

- `app/src/main/java/com/neoproc/neoproccamx/pipeline/VideoDetailProfile.kt`
- `app/src/main/java/com/neoproc/neoproccamx/ui/controls/SettingsOptionCatalog.kt`
- `app/src/main/java/com/neoproc/neoproccamx/pipeline/SubjectDetailEnhancementPolicy.kt`
- `app/src/main/java/com/neoproc/neoproccamx/pipeline/VideoDetailSharpeningPolicy.kt`
- `app/src/main/java/com/neoproc/neoproccamx/pipeline/VideoNoiseReductionPolicy.kt`
- `app/src/main/java/com/neoproc/neoproccamx/pipeline/VideoImageQualityPipelinePolicy.kt`
- `app/src/main/java/com/neoproc/neoproccamx/pipeline/SceneRenderProfileEngine.kt`
- `app/src/main/java/com/neoproc/neoproccamx/rendering/GpuImageEnhancementContract.kt`
- `app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt`
- `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingVideoQualityBudgetPolicy.kt`
- `app/src/main/java/com/neoproc/neoproccamx/recording/Npv10MovConverter.kt`
- `app/src/main/cpp/shaders/vulkan/ycbcr_preview.frag`

Primary tests:

- `app/src/test/java/com/neoproc/neoproccamx/pipeline/VideoDetailProfileTest.kt`
- `app/src/test/java/com/neoproc/neoproccamx/ui/controls/SettingsOptionCatalogTest.kt`
- `app/src/test/java/com/neoproc/neoproccamx/pipeline/SubjectDetailEnhancementPolicyTest.kt`
- `app/src/test/java/com/neoproc/neoproccamx/pipeline/VideoDetailSharpeningPolicyTest.kt`
- `app/src/test/java/com/neoproc/neoproccamx/pipeline/VideoNoiseReductionPolicyTest.kt`
- `app/src/test/java/com/neoproc/neoproccamx/pipeline/VideoImageQualityPipelinePolicyTest.kt`
- `app/src/test/java/com/neoproc/neoproccamx/rendering/GpuImageEnhancementContractTest.kt`
- `app/src/test/java/com/neoproc/neoproccamx/rendering/VulkanPreviewShaderContractTest.kt`
- `app/src/test/java/com/neoproc/neoproccamx/recording/RecordingVideoQualityBudgetPolicyTest.kt`
- `app/src/test/java/com/neoproc/neoproccamx/recording/Npv10ExportEnhancementPolicyTest.kt`
- `app/src/test/java/com/neoproc/neoproccamx/recording/Npv10MovConverterTest.kt`

Generated shader outputs, only if `ycbcr_preview.frag` changes:

- `app/src/main/cpp/generated/ycbcr_preview.frag.spv`
- `app/src/main/cpp/generated/ycbcr_preview_frag_spv.h`

## Repository Safety

The worktree already contains unrelated user changes. Before each commit:

```powershell
git status --short
git diff --cached --name-only
```

Stage only files changed by the task. Each task below lists exact `git add --` commands for its own files. Do not use wildcard path staging.

If unrelated files are staged, unstage only those unrelated staged paths with `git restore --staged -- <path>` and leave their working tree content untouched.

---

## Task 1: Add The Third Profile And UI Catalog Choice

### Tests First

- [ ] Update `VideoDetailProfileTest.kt`.

Add assertions covering enum-name and display-name parsing:

```kotlin
@Test
fun `fromPreference resolves extreme detail enum and display names`() {
    assertEquals(VideoDetailProfile.EXTREME_DETAIL, VideoDetailProfile.fromPreference("EXTREME_DETAIL"))
    assertEquals(VideoDetailProfile.EXTREME_DETAIL, VideoDetailProfile.fromPreference("Extreme Detail"))
}
```

- [ ] Update `SettingsOptionCatalogTest.kt`.

Change the expected detail choices to:

```kotlin
assertEquals(
    listOf(
        VideoDetailProfile.FOCUSED_DETAIL,
        VideoDetailProfile.NATURAL_DETAIL,
        VideoDetailProfile.EXTREME_DETAIL
    ),
    choices.map { it.value }
)
assertEquals(
    listOf("FOCUSED DETAIL", "NATURAL DETAIL", "EXTREME DETAIL"),
    choices.map { it.label }
)
```

- [ ] Run the focused failing tests:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.pipeline.VideoDetailProfileTest" --tests "com.neoproc.neoproccamx.ui.controls.SettingsOptionCatalogTest" --no-daemon --no-configuration-cache --console=plain
```

Expected result: fail because `EXTREME_DETAIL` is not defined and the catalog has only two choices.

### Implementation

- [ ] Edit `VideoDetailProfile.kt`:

```kotlin
enum class VideoDetailProfile(val displayName: String) {
    FOCUSED_DETAIL("Focused Detail"),
    NATURAL_DETAIL("Natural Detail"),
    EXTREME_DETAIL("Extreme Detail");
```

- [ ] Edit `SettingsOptionCatalog.kt`:

```kotlin
val videoDetailProfileChoices: List<SettingsChoice<VideoDetailProfile>> = listOf(
    SettingsChoice("FOCUSED DETAIL", VideoDetailProfile.FOCUSED_DETAIL),
    SettingsChoice("NATURAL DETAIL", VideoDetailProfile.NATURAL_DETAIL),
    SettingsChoice("EXTREME DETAIL", VideoDetailProfile.EXTREME_DETAIL)
)
```

### Verification

- [ ] Re-run the focused tests from this task.
- [ ] Commit:

```powershell
git status --short -- app/src/main/java/com/neoproc/neoproccamx/pipeline/VideoDetailProfile.kt app/src/main/java/com/neoproc/neoproccamx/ui/controls/SettingsOptionCatalog.kt app/src/test/java/com/neoproc/neoproccamx/pipeline/VideoDetailProfileTest.kt app/src/test/java/com/neoproc/neoproccamx/ui/controls/SettingsOptionCatalogTest.kt
git add -- app/src/main/java/com/neoproc/neoproccamx/pipeline/VideoDetailProfile.kt app/src/main/java/com/neoproc/neoproccamx/ui/controls/SettingsOptionCatalog.kt app/src/test/java/com/neoproc/neoproccamx/pipeline/VideoDetailProfileTest.kt app/src/test/java/com/neoproc/neoproccamx/ui/controls/SettingsOptionCatalogTest.kt
git commit -m "Add extreme detail video profile"
```

---

## Task 2: Make Extreme Detail Profile-Driven In Subject And Sharpening Policies

### Tests First

- [ ] Update `SubjectDetailEnhancementPolicyTest.kt`.

Add a test proving extreme mode does not require a mask:

```kotlin
@Test
fun `extreme detail is profile driven and does not require subject mask`() {
    val plan = SubjectDetailEnhancementPolicy.resolve(
        validFocusedRequest(profile = VideoDetailProfile.EXTREME_DETAIL)
    )

    assertFalse(plan.subjectDetailRequested)
    assertFalse(plan.subjectDetailApplied)
    assertEquals(0f, plan.additiveDetailGain, 0.000001f)
    assertFalse(plan.naturalZeroSharpening)
    assertEquals("EXTREME_DETAIL", plan.toMetadata().getValue("selectedDetailProfile"))
}
```

- [ ] Update `VideoDetailSharpeningPolicyTest.kt`.

Add a clean-signal comparison test:

```kotlin
@Test
fun `extreme detail raises clean stable detail beyond focused and natural profiles`() {
    val natural = plan(videoDetailProfile = VideoDetailProfile.NATURAL_DETAIL)
    val focused = plan(videoDetailProfile = VideoDetailProfile.FOCUSED_DETAIL)
    val extreme = plan(videoDetailProfile = VideoDetailProfile.EXTREME_DETAIL)

    assertTrue(extreme.sharpeningStrength > focused.sharpeningStrength)
    assertTrue(extreme.microContrast > focused.microContrast)
    assertTrue(extreme.detailReconstruction > focused.detailReconstruction)
    assertTrue(focused.sharpeningStrength > natural.sharpeningStrength)
    assertFalse(extreme.globalSharpeningApplied)
    assertFalse(extreme.pixelDuplicationApplied)
    assertEquals("EXTREME_DETAIL", extreme.toMetadata().getValue("selectedDetailProfile"))
}
```

Add a noisy-signal guard test:

```kotlin
@Test
fun `extreme detail backs off in noisy shadows and raises artifact guards`() {
    val cleanExtreme = plan(videoDetailProfile = VideoDetailProfile.EXTREME_DETAIL)
    val noisyExtreme = plan(
        videoDetailProfile = VideoDetailProfile.EXTREME_DETAIL,
        sceneNoiseEstimate = 0.78f,
        shadowLift = 0.82f,
        localContrast = 0.28f,
        edgeConfidence = 0.36f,
        motionMagnitude = 0.24f
    )

    assertTrue(noisyExtreme.sharpeningStrength < cleanExtreme.sharpeningStrength)
    assertTrue(noisyExtreme.microContrast < cleanExtreme.microContrast)
    assertTrue(noisyExtreme.haloSuppression >= cleanExtreme.haloSuppression)
    assertTrue(noisyExtreme.moireSuppression >= cleanExtreme.moireSuppression)
    assertTrue(noisyExtreme.textureProtection >= cleanExtreme.textureProtection)
}
```

- [ ] Run the focused failing tests:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.pipeline.SubjectDetailEnhancementPolicyTest" --tests "com.neoproc.neoproccamx.pipeline.VideoDetailSharpeningPolicyTest" --no-daemon --no-configuration-cache --console=plain
```

Expected result: fail because the new enum is not handled in the existing `when` branches and extreme mode has no policy tuning.

### Implementation

- [ ] Edit `SubjectDetailEnhancementPolicy.kt`.

Change focused-only mask request logic to stay focused-only:

```kotlin
val requested = request.profile == VideoDetailProfile.FOCUSED_DETAIL
val naturalZero = request.profile == VideoDetailProfile.NATURAL_DETAIL
```

No special subject mask path is added for `EXTREME_DETAIL`.

- [ ] Edit `VideoDetailSharpeningPolicy.kt`.

Add a profile flag near the other request-derived flags:

```kotlin
val extremeDetailProfile = request.videoDetailProfile == VideoDetailProfile.EXTREME_DETAIL
```

After the existing noise, edge, and strict-gate calculations are available, add:

```kotlin
val extremeCleanGate = if (extremeDetailProfile) {
    (
        ((edgeConfidence - 0.44f) / 0.36f).coerceIn(0f, 1f) *
            (1f - noisyShadow * 0.84f).coerceIn(0f, 1f) *
            (1f - noisePressure * 0.46f).coerceIn(0f, 1f)
    ).coerceIn(0f, 1f)
} else {
    0f
}

val extremeSharpening = (
    calculatedSharpeningStrength +
        extremeCleanGate * 0.20f -
        noisyShadow * 0.08f -
        noisePressure * 0.04f
).coerceIn(0.26f, ImaxStrictProfile.SHARPENING_STRICT_CEILING)

val extremeMicroContrast = (
    calculatedMicroContrast +
        extremeCleanGate * 0.20f -
        noisyShadow * 0.08f
).coerceIn(0f, 0.82f)

val extremeDetailReconstruction = (
    calculatedDetailReconstruction +
        extremeCleanGate * 0.18f -
        noisyShadow * 0.10f
).coerceIn(0f, 0.98f)
```

Extend all `when (request.videoDetailProfile)` branches:

```kotlin
VideoDetailProfile.EXTREME_DETAIL -> max(calculatedSharpeningStrength, extremeSharpening)
```

```kotlin
VideoDetailProfile.EXTREME_DETAIL -> max(calculatedMicroContrast, extremeMicroContrast)
```

```kotlin
VideoDetailProfile.EXTREME_DETAIL -> max(calculatedDetailReconstruction, extremeDetailReconstruction)
```

Increase guards for extreme mode without reducing existing non-extreme guard behavior:

```kotlin
val extremeArtifactGuard = if (extremeDetailProfile) {
    (noisePressure * 0.10f + noisyShadow * 0.16f + (1f - edgeConfidence).coerceIn(0f, 1f) * 0.08f)
        .coerceIn(0f, 0.22f)
} else {
    0f
}
```

Apply `extremeArtifactGuard` to halo, moire, and texture-protection calculations by adding it before final clamping.

### Verification

- [ ] Re-run the focused tests from this task.
- [ ] Commit:

```powershell
git status --short -- app/src/main/java/com/neoproc/neoproccamx/pipeline/SubjectDetailEnhancementPolicy.kt app/src/main/java/com/neoproc/neoproccamx/pipeline/VideoDetailSharpeningPolicy.kt app/src/test/java/com/neoproc/neoproccamx/pipeline/SubjectDetailEnhancementPolicyTest.kt app/src/test/java/com/neoproc/neoproccamx/pipeline/VideoDetailSharpeningPolicyTest.kt
git add -- app/src/main/java/com/neoproc/neoproccamx/pipeline/SubjectDetailEnhancementPolicy.kt app/src/main/java/com/neoproc/neoproccamx/pipeline/VideoDetailSharpeningPolicy.kt app/src/test/java/com/neoproc/neoproccamx/pipeline/SubjectDetailEnhancementPolicyTest.kt app/src/test/java/com/neoproc/neoproccamx/pipeline/VideoDetailSharpeningPolicyTest.kt
git commit -m "Tune extreme detail sharpening policy"
```

---

## Task 3: Route Extreme Detail Through Noise Reduction And Image Quality Floors

### Tests First

- [ ] Update `VideoNoiseReductionPolicyTest.kt`.

Extend the private `plan(...)` helper so it accepts `videoDetailProfile: VideoDetailProfile? = null` and `metrics: VideoNoiseMetrics = VideoNoiseMetrics(...)`, then forward both values into `VideoNoiseReductionRequest`.

Add a noisy-scene cleanup test:

```kotlin
@Test
fun `extreme detail raises noise cleanup for noisy high detail scenes`() {
    val noisyMetrics = VideoNoiseMetrics(
        chromaNoise = 0.58f,
        lumaNoise = 0.50f,
        temporalInstability = 0.48f,
        frameToFrameRgbVariance = 0.64f
    )
    val baseline = plan(
        lumaLevel = 0.14f,
        textureConfidence = 0.64f,
        edgeDensity = 0.30f,
        videoDetailProfile = VideoDetailProfile.FOCUSED_DETAIL,
        metrics = noisyMetrics
    )
    val extreme = plan(
        lumaLevel = 0.14f,
        textureConfidence = 0.64f,
        edgeDensity = 0.30f,
        videoDetailProfile = VideoDetailProfile.EXTREME_DETAIL,
        metrics = noisyMetrics
    )

    assertTrue(extreme.lumaDenoise > baseline.lumaDenoise)
    assertTrue(extreme.chromaDenoise >= baseline.chromaDenoise)
    assertTrue(extreme.detailSafeSharpeningCeiling >= baseline.detailSafeSharpeningCeiling)
}
```

Add a motion-safe temporal test:

```kotlin
@Test
fun `extreme detail keeps temporal denoise motion safe`() {
    val extreme = plan(
        motionScore = 0.82f,
        textureConfidence = 0.44f,
        edgeDensity = 0.22f,
        videoDetailProfile = VideoDetailProfile.EXTREME_DETAIL,
        metrics = VideoNoiseMetrics(
            chromaNoise = 0.42f,
            lumaNoise = 0.38f,
            temporalInstability = 0.72f,
            frameToFrameRgbVariance = 0.52f
        )
    )

    assertTrue("temporal=${extreme.temporalBlend}", extreme.temporalBlend <= 0.46f)
    assertTrue("ceiling=${extreme.motionSafeTemporalCeiling}", extreme.motionSafeTemporalCeiling <= 0.52f)
}
```

- [ ] Update `VideoImageQualityPipelinePolicyTest.kt`.

Add a clean SDR floor test:

```kotlin
@Test
fun `extreme detail clean sdr raises detail floors beyond focused baseline`() {
    val focused = VideoImageQualityPipelinePolicy.resolve(
        cleanDetailedSdrRequest().copy(
            edgeDensity = 0.34f,
            normalizedNoiseFloor = 0.025f,
            perceptualSharpness = 0.78f,
            videoDetailProfile = VideoDetailProfile.FOCUSED_DETAIL
        )
    )
    val extreme = VideoImageQualityPipelinePolicy.resolve(
        cleanDetailedSdrRequest().copy(
            edgeDensity = 0.34f,
            normalizedNoiseFloor = 0.025f,
            perceptualSharpness = 0.78f,
            videoDetailProfile = VideoDetailProfile.EXTREME_DETAIL
        )
    )

    assertTrue(extreme.haloSafeSharpeningLiftFloor > focused.haloSafeSharpeningLiftFloor)
    assertTrue(extreme.edgeMicroContrastLiftFloor > focused.edgeMicroContrastLiftFloor)
    assertTrue(extreme.fineTextureDetailBoostFloor > focused.fineTextureDetailBoostFloor)
    assertTrue(extreme.recordingPreviewDetailParityFloor >= focused.recordingPreviewDetailParityFloor)
}
```

Add a noisy SDR guard test:

```kotlin
@Test
fun `extreme detail noisy sdr raises cleanup while keeping halo safe sharpening bounded`() {
    val extreme = VideoImageQualityPipelinePolicy.resolve(
        cleanDetailedSdrRequest().copy(
            edgeDensity = 0.12f,
            normalizedNoiseFloor = 0.76f,
            flatRegionStdDev = 14.0f,
            realIsoNoiseFloor = 36_000f,
            perceptualSharpness = 0.34f,
            videoDetailProfile = VideoDetailProfile.EXTREME_DETAIL
        )
    )

    assertTrue(extreme.spatialDenoiseFloor >= 0.86f)
    assertTrue(extreme.chromaDenoiseFloor >= 0.992f)
    assertTrue(extreme.haloSafeSharpeningLiftFloor <= 0.58f)
}
```

Add a RAW bypass test:

```kotlin
@Test
fun `raw ignores extreme detail profile and keeps raw pipeline floors`() {
    val rawDefault = VideoImageQualityPipelinePolicy.resolve(
        noisyHdrRequest().copy(signalFamily = VideoImageSignalFamily.RAW)
    )
    val rawExtreme = VideoImageQualityPipelinePolicy.resolve(
        noisyHdrRequest().copy(
            signalFamily = VideoImageSignalFamily.RAW,
            videoDetailProfile = VideoDetailProfile.EXTREME_DETAIL
        )
    )

    assertEquals(rawDefault, rawExtreme)
}
```

- [ ] Run the focused failing tests:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.pipeline.VideoNoiseReductionPolicyTest" --tests "com.neoproc.neoproccamx.pipeline.VideoImageQualityPipelinePolicyTest" --no-daemon --no-configuration-cache --console=plain
```

Expected result: fail because both request models do not carry the new detail profile yet and no extreme floors exist.

### Implementation

- [ ] Add `videoDetailProfile` to `VideoNoiseReductionRequest` with a default:

```kotlin
val videoDetailProfile: VideoDetailProfile? = null,
```

- [ ] In `VideoNoiseReductionPolicy.resolve`, add:

```kotlin
val extremeDetailProfile = request.videoDetailProfile == VideoDetailProfile.EXTREME_DETAIL
val extremeCleanupBoost = if (extremeDetailProfile) {
    (noisePressure * 0.12f + shadow * 0.08f + iso * 0.05f).coerceIn(0f, 0.18f)
} else {
    0f
}
```

Apply `extremeCleanupBoost` to luma and chroma denoise before final clamping. Keep skin, texture, motion, and shadow guards active.

- [ ] Preserve detail ceiling for clean extreme mode:

```kotlin
val detailSafeSharpeningCeiling = if (strictSdr || extremeDetailProfile) {
    val floor = when {
        strictSdrNoiseRecovery -> 0.58f
        extremeDetailProfile && noisePressure <= 0.28f -> 0.86f
        extremeDetailProfile -> 0.72f
        else -> 0.76f
    }
    maxOf(baseDetailSafeSharpeningCeiling, floor)
        .coerceAtMost(ImaxStrictProfile.SHARPENING_STRICT_CEILING)
} else {
    baseDetailSafeSharpeningCeiling
}
```

- [ ] Add `videoDetailProfile` to `VideoImageQualityPipelineRequest` with a default:

```kotlin
val videoDetailProfile: VideoDetailProfile? = null
```

- [ ] In `VideoImageQualityPipelinePolicy.resolve`, add:

```kotlin
val extremeDetailProfile =
    request.videoDetailProfile == VideoDetailProfile.EXTREME_DETAIL &&
        signal != VideoImageSignalFamily.RAW

val extremeCleanDetailGate = if (extremeDetailProfile) {
    (
        ((request.edgeDensity.coerceIn(0f, 1f) - 0.18f) / 0.22f).coerceIn(0f, 1f) *
            (1f - noise * 0.72f).coerceIn(0f, 1f) *
            (0.45f + detailConfidence * 0.55f).coerceIn(0f, 1f)
    ).coerceIn(0f, 1f)
} else {
    0f
}

val extremeNoiseCleanupGate = if (extremeDetailProfile) {
    (noise * 0.72f + rgbGrain * 0.28f).coerceIn(0f, 1f)
} else {
    0f
}
```

Apply these floors after existing floors are computed and before the return object is built:

```kotlin
val extremeTemporalFloor = if (extremeDetailProfile) {
    (0.50f + extremeNoiseCleanupGate * 0.18f - motion * 0.12f).coerceIn(0f, 0.68f)
} else {
    0f
}
val extremeSpatialFloor = if (extremeDetailProfile) {
    (0.86f + extremeNoiseCleanupGate * 0.12f).coerceIn(0f, 0.98f)
} else {
    0f
}
val extremeChromaFloor = if (extremeDetailProfile) {
    (0.992f + extremeNoiseCleanupGate * 0.006f).coerceIn(0f, 0.999f)
} else {
    0f
}
val extremeSharpeningFloor = if (extremeDetailProfile) {
    (0.64f + extremeCleanDetailGate * 0.22f - extremeNoiseCleanupGate * 0.18f).coerceIn(0f, 0.86f)
} else {
    0f
}
val extremeHaloSafeFloor = if (extremeDetailProfile) {
    (0.50f + extremeCleanDetailGate * 0.28f - extremeNoiseCleanupGate * 0.20f).coerceIn(0f, 0.86f)
} else {
    0f
}
```

Use `maxOf(existingFloor, extremeFloor)` for temporal, spatial, chroma, sharpening, edge micro-contrast, fine texture detail, halo-safe sharpening, and recording-preview parity fields. Keep the RAW bypass before these floors are applied.

- [ ] Edit `SceneRenderProfileEngine.kt`.

Pass the selected profile into both requests:

```kotlin
videoDetailProfile = videoDetailProfile
```

Add it to `VideoImageQualityPipelineRequest(...)` and `VideoNoiseReductionRequest(...)`.

### Verification

- [ ] Re-run the focused tests from this task.
- [ ] Run the scene-render focused tests if present:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.pipeline.SceneRenderProfileEngineTest" --no-daemon --no-configuration-cache --console=plain
```

- [ ] Commit:

```powershell
git status --short -- app/src/main/java/com/neoproc/neoproccamx/pipeline/VideoNoiseReductionPolicy.kt app/src/main/java/com/neoproc/neoproccamx/pipeline/VideoImageQualityPipelinePolicy.kt app/src/main/java/com/neoproc/neoproccamx/pipeline/SceneRenderProfileEngine.kt app/src/test/java/com/neoproc/neoproccamx/pipeline/VideoNoiseReductionPolicyTest.kt app/src/test/java/com/neoproc/neoproccamx/pipeline/VideoImageQualityPipelinePolicyTest.kt
git add -- app/src/main/java/com/neoproc/neoproccamx/pipeline/VideoNoiseReductionPolicy.kt app/src/main/java/com/neoproc/neoproccamx/pipeline/VideoImageQualityPipelinePolicy.kt app/src/main/java/com/neoproc/neoproccamx/pipeline/SceneRenderProfileEngine.kt app/src/test/java/com/neoproc/neoproccamx/pipeline/VideoNoiseReductionPolicyTest.kt app/src/test/java/com/neoproc/neoproccamx/pipeline/VideoImageQualityPipelinePolicyTest.kt
git commit -m "Route extreme detail through image quality policies"
```

---

## Task 4: Pack Extreme Profile Into GPU Uniforms And Diagnostics

### Tests First

- [ ] Update `app/src/test/java/com/neoproc/neoproccamx/rendering/GpuImageEnhancementContractTest.kt`.

Add:

```kotlin
@Test
fun `packs extreme detail into dedicated uniform slot without subject mask requirement`() {
    val uniforms = GpuImageEnhancementUniforms.fromSceneProfile(
        profile = profile,
        signalFamily = GpuSignalFamily.SDR,
        colorSpaceName = "Rec.709",
        colorScienceName = "Natural",
        videoDetailProfile = VideoDetailProfile.EXTREME_DETAIL,
        subjectDetailPlan = null
    )

    val packed = uniforms.toFloatArray()

    assertEquals(3f, packed[40], 0.0001f)
    assertEquals(0f, packed[41], 0.0001f)
    assertEquals(0f, packed[42], 0.0001f)
    assertTrue(uniforms.signature.contains("detailProfile=EXTREME_DETAIL"))
}
```

- [ ] Search for an existing `CameraViewModel` diagnostic test:

```powershell
rg -n "enhancementDetailProfile|detailProfileId|LEGACY" app/src/test app/src/androidTest
```

If a diagnostic test already covers the mapping, update it to expect `3f -> EXTREME_DETAIL`. If no test exists, keep the code change scoped and rely on `GpuImageEnhancementContractTest` plus a source search.

- [ ] Run the focused failing test:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.rendering.GpuImageEnhancementContractTest" --no-daemon --no-configuration-cache --console=plain
```

Expected result: fail because the uniform mapping does not pack profile id `3f`.

### Implementation

- [ ] Edit `app/src/main/java/com/neoproc/neoproccamx/rendering/GpuImageEnhancementContract.kt`.

Extend the profile id mapping:

```kotlin
val selectedDetailProfileId = when (videoDetailProfile) {
    VideoDetailProfile.FOCUSED_DETAIL -> 1f
    VideoDetailProfile.NATURAL_DETAIL -> 2f
    VideoDetailProfile.EXTREME_DETAIL -> 3f
    null -> 0f
}
```

- [ ] Edit `app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt`.

Extend diagnostic mapping:

```kotlin
"enhancementDetailProfile" to when (enhancementUniforms?.detailProfileId) {
    1f -> VideoDetailProfile.FOCUSED_DETAIL.name
    2f -> VideoDetailProfile.NATURAL_DETAIL.name
    3f -> VideoDetailProfile.EXTREME_DETAIL.name
    else -> "LEGACY"
}
```

### Verification

- [ ] Re-run `GpuImageEnhancementContractTest`.
- [ ] Verify no stale two-profile mappings remain:

```powershell
rg -n "FOCUSED_DETAIL|NATURAL_DETAIL|detailProfileId|enhancementDetailProfile|selectedDetailProfileId" app/src/main app/src/test
```

- [ ] Commit:

```powershell
git status --short -- app/src/main/java/com/neoproc/neoproccamx/rendering/GpuImageEnhancementContract.kt app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt app/src/test/java/com/neoproc/neoproccamx/rendering/GpuImageEnhancementContractTest.kt
git add -- app/src/main/java/com/neoproc/neoproccamx/rendering/GpuImageEnhancementContract.kt app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt app/src/test/java/com/neoproc/neoproccamx/rendering/GpuImageEnhancementContractTest.kt
git commit -m "Pack extreme detail GPU profile id"
```

---

## Task 5: Activate Extreme Profile In Vulkan Preview Shader

### Tests First

- [ ] Update `app/src/test/java/com/neoproc/neoproccamx/rendering/VulkanPreviewShaderContractTest.kt`.

Add source-contract assertions:

```kotlin
@Test
fun `fragment shader declares extreme detail profile id and does not require subject mask`() {
    val source = shaderSource

    assertTrue(source.contains("bool extremeDetailProfileActive()"))
    assertTrue(source.contains("uSubjectDetail.x > 2.5 && uSubjectDetail.x < 3.5"))
    assertTrue(source.contains("extremeDetailProfileActive()"))
    assertTrue(source.contains("profileDetailAuthority"))
    assertTrue(source.contains("extremeDetail ? 12.0 : 10.0"))
}
```

- [ ] Run the focused failing shader test:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.rendering.VulkanPreviewShaderContractTest" --no-daemon --no-configuration-cache --console=plain
```

Expected result: fail because the shader only recognizes focused detail id `1f`.

### Implementation

- [ ] Edit `app/src/main/cpp/shaders/vulkan/ycbcr_preview.frag`.

Add a helper near `subjectDetailGain`:

```glsl
bool extremeDetailProfileActive() {
    return uSubjectDetail.x > 2.5 && uSubjectDetail.x < 3.5;
}
```

Keep focused mask behavior focused-only:

```glsl
bool focusedDetail = uSubjectDetail.x > 0.5 && uSubjectDetail.x < 1.5;
```

Inside `cinemaDetailRefine`, add profile-driven authority:

```glsl
bool extremeDetail = extremeDetailProfileActive();
float profileDetailAuthority = extremeDetail ? 1.22 : 1.0;
```

Apply it to detail gain and overshoot limits:

```glsl
float gain = clamp(
    adaptiveBaseGain *
        ULTRA_DETAIL_AMPLIFICATION *
        strictNormalProSdrDetailAuthority *
        profileDetailAuthority,
    0.0,
    strictNormalProSdrDetailAuthority > 1.0 ? 100.0 : (extremeDetail ? 12.0 : 10.0)
);

float overshootLimit = max(
    extremeDetail ? 0.0028 : 0.0032,
    localRange *
        strictNormalProSdrDetailAuthority *
        (extremeDetail ? 0.82 : 1.0) +
        0.010 / detailScale
);
```

Do not read subject mask channels for extreme mode.

### Shader Generation

- [ ] Confirm `glslc` path:

```powershell
$glslc = Join-Path $env:LOCALAPPDATA 'Android\Sdk\ndk\29.0.14206865\shader-tools\windows-x86_64\glslc.exe'
Test-Path $glslc
```

- [ ] Compile the fragment shader:

```powershell
& $glslc --target-env=vulkan1.1 -fshader-stage=frag app/src/main/cpp/shaders/vulkan/ycbcr_preview.frag -o app/src/main/cpp/generated/ycbcr_preview.frag.spv
```

- [ ] Regenerate `ycbcr_preview_frag_spv.h` with the same array symbol and header format already used in the repository. Preserve the `Source SHA-256` comment. Use the exact symbol currently present in `app/src/main/cpp/generated/ycbcr_preview_frag_spv.h`.

PowerShell generation pattern:

```powershell
$spvPath = 'app/src/main/cpp/generated/ycbcr_preview.frag.spv'
$srcPath = 'app/src/main/cpp/shaders/vulkan/ycbcr_preview.frag'
$headerPath = 'app/src/main/cpp/generated/ycbcr_preview_frag_spv.h'
$symbol = (Select-String -Path $headerPath -Pattern 'alignas\(4\) const uint32_t ([a-zA-Z0-9_]+)\[\]' | Select-Object -First 1).Matches.Groups[1].Value
$sourceHash = (Get-FileHash -Algorithm SHA256 $srcPath).Hash.ToLowerInvariant()
$bytes = [System.IO.File]::ReadAllBytes((Resolve-Path $spvPath))
$words = New-Object System.Collections.Generic.List[string]
for ($i = 0; $i -lt $bytes.Length; $i += 4) {
    $word = [BitConverter]::ToUInt32($bytes, $i)
    $words.Add(('0x{0:x8}u' -f $word))
}
$lines = New-Object System.Collections.Generic.List[string]
$lines.Add('#pragma once')
$lines.Add('')
$lines.Add('#include <cstdint>')
$lines.Add('')
$lines.Add("// Source SHA-256: $sourceHash")
$lines.Add("alignas(4) const uint32_t $symbol[] = {")
for ($i = 0; $i -lt $words.Count; $i += 8) {
    $count = [Math]::Min(8, $words.Count - $i)
    $lines.Add('    ' + (($words.GetRange($i, $count)) -join ', ') + ',')
}
$lines.Add('};')
[System.IO.File]::WriteAllLines((Resolve-Path $headerPath), $lines)
```

### Verification

- [ ] Re-run `VulkanPreviewShaderContractTest`.
- [ ] Verify generated header hash matches source:

```powershell
$sourceHash = (Get-FileHash -Algorithm SHA256 app/src/main/cpp/shaders/vulkan/ycbcr_preview.frag).Hash.ToLowerInvariant()
Select-String -Path app/src/main/cpp/generated/ycbcr_preview_frag_spv.h -Pattern $sourceHash
```

- [ ] Commit:

```powershell
git status --short -- app/src/main/cpp/shaders/vulkan/ycbcr_preview.frag app/src/main/cpp/generated/ycbcr_preview.frag.spv app/src/main/cpp/generated/ycbcr_preview_frag_spv.h app/src/test/java/com/neoproc/neoproccamx/rendering/VulkanPreviewShaderContractTest.kt
git add -- app/src/main/cpp/shaders/vulkan/ycbcr_preview.frag app/src/main/cpp/generated/ycbcr_preview.frag.spv app/src/main/cpp/generated/ycbcr_preview_frag_spv.h app/src/test/java/com/neoproc/neoproccamx/rendering/VulkanPreviewShaderContractTest.kt
git commit -m "Activate extreme detail preview shader path"
```

---

## Task 6: Raise Recording Budget Headroom For Extreme Detail

### Tests First

- [ ] Update `RecordingVideoQualityBudgetPolicyTest.kt`.

Add a non-strict profile headroom test:

```kotlin
@Test
fun `extreme detail raises non strict 4k hevc bitrate within codec ceiling`() {
    val hevcCeiling = RecordingCodecCatalog.maxBitrateMbps(GpuVideoCodec.HEVC) * 1_000_000
    val focused = RecordingVideoQualityBudgetPolicy.resolveTargetBitrateBps(
        request(
            width = 3840,
            height = 2160,
            fps = 30,
            codec = GpuVideoCodec.HEVC,
            hdrMode = "OFF",
            logEnabled = false,
            tenBit = false,
            colorSpace = "Rec.709",
            videoDetailProfile = VideoDetailProfile.FOCUSED_DETAIL
        )
    )
    val extreme = RecordingVideoQualityBudgetPolicy.resolveTargetBitrateBps(
        request(
            width = 3840,
            height = 2160,
            fps = 30,
            codec = GpuVideoCodec.HEVC,
            hdrMode = "OFF",
            logEnabled = false,
            tenBit = false,
            colorSpace = "Rec.709",
            videoDetailProfile = VideoDetailProfile.EXTREME_DETAIL
        )
    )

    assertTrue(extreme > focused)
    assertTrue(extreme <= hevcCeiling)
}
```

Add a strict mode stability test:

```kotlin
@Test
fun `extreme detail strict high end remains codec ceiling`() = withStrictHighEnd {
    val hevcCeiling = RecordingCodecCatalog.maxBitrateMbps(GpuVideoCodec.HEVC) * 1_000_000
    val strictExtreme = RecordingVideoQualityBudgetPolicy.resolveTargetBitrateBps(
        request(
            width = 3840,
            height = 2160,
            fps = 60,
            codec = GpuVideoCodec.HEVC,
            hdrMode = "OFF",
            logEnabled = false,
            tenBit = false,
            colorSpace = "Rec.709",
            videoDetailProfile = VideoDetailProfile.EXTREME_DETAIL
        )
    )

    assertEquals(hevcCeiling, strictExtreme)
}
```

Extend the private `request(...)` helper with `videoDetailProfile: VideoDetailProfile? = null` and pass it to `RecordingVideoQualityBudgetRequest`.

- [ ] Run the focused failing test:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.recording.RecordingVideoQualityBudgetPolicyTest" --no-daemon --no-configuration-cache --console=plain
```

Expected result: fail because the request model does not carry `videoDetailProfile`.

### Implementation

- [ ] Edit `RecordingVideoQualityBudgetPolicy.kt`.

Import the profile enum if the file does not already import it:

```kotlin
import com.neoproc.neoproccamx.pipeline.VideoDetailProfile
```

Add to `RecordingVideoQualityBudgetRequest`:

```kotlin
val videoDetailProfile: VideoDetailProfile? = null
```

In `modeHeadroom`, add:

```kotlin
if (request.videoDetailProfile == VideoDetailProfile.EXTREME_DETAIL) {
    multiplier += 0.18
}
```

Raise only this method's upper clamp:

```kotlin
return multiplier.coerceIn(1.0, 1.66)
```

- [ ] Update all `RecordingVideoQualityBudgetRequest(...)` call sites that now need named parameters because of the new field. Find them with:

```powershell
rg -n "RecordingVideoQualityBudgetRequest\(" app/src/main app/src/test
```

### Verification

- [ ] Re-run `RecordingVideoQualityBudgetPolicyTest`.
- [ ] Run recording user bitrate tests to prove manual custom bitrate behavior remains stable:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.recording.RecordingUserBitratePolicyTest" --no-daemon --no-configuration-cache --console=plain
```

- [ ] Commit:

```powershell
git status --short -- app/src/main/java/com/neoproc/neoproccamx/recording/RecordingVideoQualityBudgetPolicy.kt app/src/test/java/com/neoproc/neoproccamx/recording/RecordingVideoQualityBudgetPolicyTest.kt
git add -- app/src/main/java/com/neoproc/neoproccamx/recording/RecordingVideoQualityBudgetPolicy.kt app/src/test/java/com/neoproc/neoproccamx/recording/RecordingVideoQualityBudgetPolicyTest.kt
git commit -m "Raise recording headroom for extreme detail"
```

---

## Task 7: Add Extreme Derived Export For NPV10 MOV Conversion

### Tests First

- [ ] Update `app/src/test/java/com/neoproc/neoproccamx/recording/Npv10ExportEnhancementPolicyTest.kt`.

Add:

```kotlin
@Test
fun `extreme export profile is stronger derived detail without dither`() {
    val base = Npv10ExportEnhancementProfile.SDR_VIDEO
    val extreme = base.withExtremeDetailAuthority()

    assertTrue(extreme.fineTextureDetailGain > base.fineTextureDetailGain)
    assertTrue(extreme.mediumDetailGain > base.mediumDetailGain)
    assertTrue(extreme.strongDetailGain > base.strongDetailGain)
    assertTrue(extreme.fineTextureThreshold <= base.fineTextureThreshold)
    assertTrue(extreme.mediumEdgeThreshold <= base.mediumEdgeThreshold)
    assertTrue(extreme.chromaSuppression <= base.chromaSuppression)
    assertEquals(0.0, extreme.ditherAmplitude, 0.000001)
}
```

- [ ] Update `app/src/test/java/com/neoproc/neoproccamx/recording/Npv10MovConverterTest.kt`.

Add a converter test using the existing helper that writes a small NPV10 stream with metadata. The test must assert:

```kotlin
assertTrue(certificate.contains("selectedDetailProfile=EXTREME_DETAIL"))
assertTrue(certificate.contains("detailProcessingPath=NPV10_EXPORT_EXTREME_DETAIL_DERIVED_ONLY"))
assertTrue(certificate.contains("exportEdgeAwareDetailApplied=true"))
assertTrue(certificate.contains("masterEnhancementDestructive=false"))
```

If the test helper exposes structured certificate metadata, assert the structured fields instead of searching text.

- [ ] Run the focused failing tests:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.recording.Npv10ExportEnhancementPolicyTest" --tests "com.neoproc.neoproccamx.recording.Npv10MovConverterTest" --no-daemon --no-configuration-cache --console=plain
```

Expected result: fail because `withExtremeDetailAuthority` and the export path do not exist.

### Implementation

- [ ] Edit `app/src/main/java/com/neoproc/neoproccamx/recording/Npv10MovConverter.kt`.

Add to `Npv10ExportEnhancementProfile`:

```kotlin
fun withExtremeDetailAuthority(): Npv10ExportEnhancementProfile =
    copy(
        fineTextureThreshold = minOf(fineTextureThreshold, 4),
        mediumEdgeThreshold = minOf(mediumEdgeThreshold, 14),
        fineTextureDetailGain = maxOf(fineTextureDetailGain, 2.24),
        mediumDetailGain = maxOf(mediumDetailGain, 2.48),
        strongDetailGain = maxOf(strongDetailGain, 2.72),
        aliasSafeMaxDetailDelta = minOf(aliasSafeMaxDetailDelta, 240),
        chromaSuppression = minOf(chromaSuppression, 0.002),
        ditherAmplitude = 0.0
    )
```

Update profile selection in `convertWithAudit`:

```kotlin
val enhancementProfile = when (requestedDetailProfile) {
    VideoDetailProfile.EXTREME_DETAIL -> {
        detailProcessingPath = "NPV10_EXPORT_EXTREME_DETAIL_DERIVED_ONLY"
        baseEnhancementProfile.withExtremeDetailAuthority()
    }
    null -> baseEnhancementProfile
    else -> {
        detailProcessingPath = "NPV10_EXPORT_NO_SUBJECT_MASK"
        baseEnhancementProfile.withoutAdditiveDetail()
    }
}
```

Update edge-aware export decision:

```kotlin
edgeDetailPolicyApplied =
    (requestedDetailProfile == null || requestedDetailProfile == VideoDetailProfile.EXTREME_DETAIL) &&
        header.width >= 3 &&
        header.height >= 3
```

Keep master/private metadata unchanged:

```kotlin
masterEnhancementDestructive = false
```

Do not modify RAW frame payloads or source NPV10 payloads.

### Verification

- [ ] Re-run the NPV10 focused tests.
- [ ] Commit:

```powershell
git status --short -- app/src/main/java/com/neoproc/neoproccamx/recording/Npv10MovConverter.kt app/src/test/java/com/neoproc/neoproccamx/recording/Npv10ExportEnhancementPolicyTest.kt app/src/test/java/com/neoproc/neoproccamx/recording/Npv10MovConverterTest.kt
git add -- app/src/main/java/com/neoproc/neoproccamx/recording/Npv10MovConverter.kt app/src/test/java/com/neoproc/neoproccamx/recording/Npv10ExportEnhancementPolicyTest.kt app/src/test/java/com/neoproc/neoproccamx/recording/Npv10MovConverterTest.kt
git commit -m "Add extreme detail derived NPV10 export"
```

---

## Task 8: Integration Search, Full Unit Tests, And Build

### Integration Search

- [ ] Search for enum exhaustiveness and string mappings:

```powershell
rg -n "when \(.*VideoDetailProfile|VideoDetailProfile\.|selectedDetailProfile|detailProfile=|detailProfileId|NATURAL_DETAIL|FOCUSED_DETAIL" app/src/main app/src/test
```

- [ ] For every remaining production `when` over `VideoDetailProfile`, handle `EXTREME_DETAIL` explicitly unless the `else` branch is intentionally legacy/null behavior and tests prove it.

- [ ] Search for generated shader hash contract failures:

```powershell
rg -n "Source SHA-256|ycbcr_preview|frag_spv" app/src/main/cpp app/src/test
```

### Full Verification

- [ ] Run the full app unit test suite:

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon --no-configuration-cache --console=plain
```

- [ ] Build the app:

```powershell
.\gradlew.bat :app:assembleDebug --no-daemon --no-configuration-cache --console=plain
```

- [ ] If an Android device or emulator is already connected, run the existing quality gate:

```powershell
adb devices
.\tools\quality\run-v17-recorded-quality-repair-gate.ps1
```

If no device is connected, record that the device quality gate was not run.

### Final Commit

- [ ] If integration-only fixes were made, run `git status --short`, stage only the exact changed integration files by name, and commit with `git commit -m "Verify extreme detail integration"`.

### Completion Report

- [ ] Report:
  - New mode name and UI label.
  - Tests run and outcomes.
  - Whether shader SPIR-V was regenerated.
  - Whether device quality gate was run or skipped because no device was connected.
  - Commit hashes created during implementation.
