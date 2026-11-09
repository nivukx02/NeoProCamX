# GPU Quality, Detail, and Bitrate Contract Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Apply the approved detail/noise-model tuning to the active Vulkan preview and processed-recording shader path, extend NPV10/log export dithering coverage, and prove that existing GPU recording, RAW/P010 integrity, and manual/AUTO bitrate contracts remain intact.

**Architecture:** The implementation modifies the single Vulkan fragment path already shared by preview and Vulkan-imported encoded frames. It tunes the heteroscedastic/Bayes path and boosts the existing guarded nine-sample bilateral Laplacian, leaving RAW sensor master payloads untouched and keeping P010 quantization truthful. Recording routing and bitrate ownership already satisfy the approved design; they are regression-tested rather than rewritten.

**Tech Stack:** Kotlin, JUnit 4, Android Gradle Plugin, GLSL/Vulkan SPIR-V, Android Camera2/MediaCodec runtime evidence, ADB.

---

## Execution Constraints

- Work in the existing workspace because the targeted files already contain prior approved integration work.
- The worktree contains unrelated and overlapping modifications. Do not reset, revert, or commit broad file sets. Before any implementation commit, inspect `git diff --cached --name-only` and the staged diff; defer a commit if it would absorb unrelated work.
- Do not claim literal zero sensor noise, guaranteed IMAX quality, device 10/12-bit support, or GPU recording success without captured evidence. Use the measurable contracts below.
- Keep RAW/RAW12 sensor master bytes unprocessed. Enhancement applies only to preview, processed encoded feeds, or expressly derived exports.

## Contract Summary

- Processed `PRO`, `LOG`, `NDR10+`, and `NIVUKXVISION` recording continues to use `INTERNAL_HARDWARE_IMPORT_ENCODER` only when Vulkan import is selected and runtime evidence validates encoded processed frames.
- `CUSTOM` bitrate remains user-controlled within encoder safety bounds; `AUTO` remains automatic; `CINEMA_MASTER` keeps the existing 45/100/180 Mbps master ceiling policy before encoder capability clamping.
- Native NPV10 P010 remains 10-bit aligned storage, and RAW12 remains sensor-truth storage. Dither is one final quantization/export operation for supported derived color modes, including N-Log.

### Task 1: Tune the Active Vulkan Noise and Detail Contract

**Files:**
- Modify: `app/src/test/java/com/neoproc/neoproccamx/rendering/VulkanPreviewShaderContractTest.kt`
- Modify: `app/src/main/cpp/shaders/vulkan/ycbcr_preview.frag`

- [ ] **Step 1: Replace stale shader assertions with the approved active-path contract**

In `shader_containsHeteroscedasticDenoiseAndNineTapLaplacianDetailGate()`, assert the new noise model, motion preservation, and a bounded active-path detail multiplier:

```kotlin
assertTrue(shaderSource.contains("float shotNoiseCoefficient = 0.0012"))
assertTrue(shaderSource.contains("float readNoiseCoefficient = 0.00022"))
assertTrue(shaderSource.contains("0.0000001"))
assertTrue(shaderSource.contains("float preserve = mix(0.85, 1.0, motionConfidence)"))
assertTrue(shaderSource.contains("const float ULTRA_DETAIL_AMPLIFICATION = 4.85"))
assertTrue(shaderSource.contains("adaptiveBaseGain * ULTRA_DETAIL_AMPLIFICATION"))
assertTrue(shaderSource.contains("9-tap Gaussian-weighted bilateral Laplacian"))
assertTrue(shaderSource.contains("float overshootLimit = max(0.009"))
assertTrue(shaderSource.contains("1.85"))
```

Remove the assertion on `detail * strength * 4.25`: it tests an inactive helper rather than `cinemaDetailRefine()`, the rendering path that actually executes.

- [ ] **Step 2: Run the focused test and confirm it fails for the old constants**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.rendering.VulkanPreviewShaderContractTest"
```

Expected: `shader_containsHeteroscedasticDenoiseAndNineTapLaplacianDetailGate` fails because `0.0012`, `0.00022`, `0.0000001`, `0.85`, and `ULTRA_DETAIL_AMPLIFICATION` are not yet present.

- [ ] **Step 3: Update the heteroscedastic and Bayes-shrink settings in the shader**

In `heteroscedasticNoiseVariance()` replace the active coefficients:

```glsl
float heteroscedasticNoiseVariance(float linearLuma) {
    float shotNoiseCoefficient = 0.0012;
    float readNoiseCoefficient = 0.00022;
    return max(
        shotNoiseCoefficient * clamp(linearLuma, 0.0, 1.0) + readNoiseCoefficient,
        0.0000001
    );
}
```

In `laplacianPyramidBayesShrink()` update the motion retention expression while preserving its clamping:

```glsl
float preserve = mix(0.85, 1.0, motionConfidence);
return clamp(lowBand + shrunk * preserve * clamp(0.70 + strength * 0.30, 0.0, 1.0), 0.0, 1.08);
```

- [ ] **Step 4: Put the 4.85 boost inside the already halo-limited nine-sample path**

Do not add a second sharpening kernel and do not route through the unguarded `highPrecisionSharpen()` helper. In `cinemaDetailRefine()`, retain the Scharr, chroma, noise, halo, local-contrast, and luma overshoot limits, and update only the active local gain:

```glsl
const float ULTRA_DETAIL_AMPLIFICATION = 4.85;
float gain = clamp(
    adaptiveBaseGain * ULTRA_DETAIL_AMPLIFICATION,
    0.0,
    1.85
);
```

Rationale: the requested coefficient increases low-amplitude micro-detail recovery, while the existing `1.85` gain ceiling and `overshootLimit` stop strong edges from developing visible halos.

- [ ] **Step 5: Run the focused shader contract test**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.rendering.VulkanPreviewShaderContractTest"
```

Expected: PASS.

### Task 2: Add N-Log to Final-Pass Export Dithering Without Changing Raw Masters

**Files:**
- Modify: `app/src/test/java/com/neoproc/neoproccamx/recording/Npv10ExportEnhancementPolicyTest.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/recording/Npv10MovConverter.kt`

- [ ] **Step 1: Write failing coverage for log export mode labels**

Extend `deep color and neo hdr modes require perceptual export dithering`:

```kotlin
assertTrue(Npv10ExportEnhancementPolicy.isDitheringRequiredFor("N-Log Cinema"))
assertTrue(Npv10ExportEnhancementPolicy.isDitheringRequiredFor("NEOLOG_PRO"))
```

- [ ] **Step 2: Confirm the current selector fails for log**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.recording.Npv10ExportEnhancementPolicyTest"
```

Expected: the two N-Log assertions fail because the selector currently enables only NPV10/NDR10+/NIVUKX Vision labels.

- [ ] **Step 3: Extend only the dither routing predicate**

Update `isDitheringRequiredFor()`; keep `enhanceP010()` ordering and its single existing `applyBlueNoiseDither()` final operation unchanged:

```kotlin
fun isDitheringRequiredFor(mode: String): Boolean =
    when (mode.trim().uppercase(Locale.US).replace(" ", "")) {
        "NPV10", "NIVUKXVISION", "NDR10+", "N-LOGCINEMA", "NEOLOG_PRO" -> true
        else -> false
    }
```

This adds log-derived export support without applying dithering or enhancement to RAW12 sensor master storage.

- [ ] **Step 4: Run the NPV10 enhancement unit test**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.recording.Npv10ExportEnhancementPolicyTest"
```

Expected: PASS, including the existing P010 `-1..1` code-value dither bound.

### Task 3: Re-Verify GPU Recording, Bitrate Ownership, ISP/Distortion Gating, and Raw Truth

**Files:**
- Test only: `app/src/test/java/com/neoproc/neoproccamx/ui/RecordingPreviewContinuityPolicyTest.kt`
- Test only: `app/src/test/java/com/neoproc/neoproccamx/ui/RecordingPipelineParityPolicyTest.kt`
- Test only: `app/src/test/java/com/neoproc/neoproccamx/recording/RecordingUserBitratePolicyTest.kt`
- Test only: `app/src/test/java/com/neoproc/neoproccamx/ui/controls/SettingsRuntimeFormatterTest.kt`
- Test only: `app/src/test/java/com/neoproc/neoproccamx/pipeline/FramePipelineZeroCopyContractTest.kt`
- Test only: `app/src/test/java/com/neoproc/neoproccamx/camera/Camera2PipelineRequestPolicyTest.kt`
- Test only: `app/src/test/java/com/neoproc/neoproccamx/recording/Npv10CaptureSourcePolicyTest.kt`

- [ ] **Step 1: Verify no production rewrite is required for Vulkan recording feed selection**

Check that these existing production expressions remain in place:

```kotlin
internalHardwareImportStable = vulkanRuntimeSupported
```

```kotlin
vulkanRuntimeSupported && (preferInternalHardwareImport || internalHardwareImportStable) ->
    RecordingFeedPath.INTERNAL_HARDWARE_IMPORT_ENCODER
```

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.ui.RecordingPreviewContinuityPolicyTest" --tests "com.neoproc.neoproccamx.ui.RecordingPipelineParityPolicyTest" --tests "com.neoproc.neoproccamx.pipeline.FramePipelineZeroCopyContractTest"
```

Expected: PASS. If a failure appears, repair only the failed contract; do not replace evidence-gated `recordingGpu=true` with an unconditional claim.

- [ ] **Step 2: Verify manual and automatic bitrate modes remain separate**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.recording.RecordingUserBitratePolicyTest" --tests "com.neoproc.neoproccamx.ui.controls.SettingsRuntimeFormatterTest"
```

Expected: PASS. Preserve the existing mode behavior:

```text
CUSTOM         user-entered bitrate, capability-clamped
AUTO           policy-derived automatic bitrate
CINEMA_MASTER  45 Mbps <=1080p, 100 Mbps 4K, 180 Mbps 8K before capability clamp
```

Do not introduce the prompt's universal 30 Mbps 1080p cap into `CINEMA_MASTER`, and do not force `CUSTOM` into `AUTO`.

- [ ] **Step 3: Verify physical ISP/distortion and raw storage truth stay capability-gated**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.camera.Camera2PipelineRequestPolicyTest" --tests "com.neoproc.neoproccamx.recording.Npv10CaptureSourcePolicyTest"
```

Expected: PASS. ISP/distortion OFF can be requested only when supported and RAW/P010 storage must remain fail-closed rather than simulated.

### Task 4: Compile GLSL to SPIR-V and Run Build Verification

**Files:**
- Generate: `app/src/main/cpp/generated/ycbcr_preview.frag.spv`
- Generate: `app/src/main/cpp/generated/ycbcr_preview_frag_spv.h`

- [ ] **Step 1: Locate the repository's shader generation convention**

Run:

```powershell
rg -n "glslc|ycbcr_preview_frag_spv|ycbcr_preview\.frag\.spv" app tools -g "*.gradle*" -g "CMakeLists.txt" -g "*.ps1" -g "*.kt"
```

If an existing project task/script is found, use it. Otherwise use the Android NDK `glslc` and regenerate the embedded header deterministically.

- [ ] **Step 2: Compile the modified fragment shader**

Fallback command when no project generator exists:

```powershell
$glslc = Join-Path $env:LOCALAPPDATA 'Android\Sdk\ndk\29.0.14206865\shader-tools\windows-x86_64\glslc.exe'
& $glslc --target-env=vulkan1.1 -fshader-stage=frag app/src/main/cpp/shaders/vulkan/ycbcr_preview.frag -o app/src/main/cpp/generated/ycbcr_preview.frag.spv
if ($LASTEXITCODE -ne 0) { throw 'glslc failed' }
```

Expected: compilation succeeds without GLSL syntax errors.

- [ ] **Step 3: Regenerate the embedded SPIR-V header using the repository convention**

If no existing generator exists, perform this mechanical regeneration only after `glslc` succeeds:

```powershell
$spv = 'app/src/main/cpp/generated/ycbcr_preview.frag.spv'
$header = 'app/src/main/cpp/generated/ycbcr_preview_frag_spv.h'
$bytes = [IO.File]::ReadAllBytes($spv)
if (($bytes.Length % 4) -ne 0) { throw 'SPIR-V byte count is not word-aligned' }
$words = for ($i = 0; $i -lt $bytes.Length; $i += 4) {
    ('0x{0:x8}u' -f [BitConverter]::ToUInt32($bytes, $i))
}
$wrapped = for ($i = 0; $i -lt $words.Count; $i += 8) {
    '    ' + (($words[$i..([Math]::Min($i + 7, $words.Count - 1))]) -join ', ') + ','
}
$text = @(
    '// Auto-generated from ycbcr_preview.frag - DO NOT EDIT'
    '#pragma once'
    ''
    '#include <cstdint>'
    '#include <cstddef>'
    ''
    'static constexpr uint32_t kYcbcrPreviewFragSpv[] = {'
    $wrapped
    '};'
    ''
    'static constexpr size_t kYcbcrPreviewFragSpvCount = sizeof(kYcbcrPreviewFragSpv) / sizeof(kYcbcrPreviewFragSpv[0]);'
) -join [Environment]::NewLine
[IO.File]::WriteAllText((Join-Path (Get-Location) $header), $text + [Environment]::NewLine, [Text.Encoding]::ASCII)
```

- [ ] **Step 4: Run the combined focused test set**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.rendering.VulkanPreviewShaderContractTest" --tests "com.neoproc.neoproccamx.recording.Npv10ExportEnhancementPolicyTest" --tests "com.neoproc.neoproccamx.ui.RecordingPreviewContinuityPolicyTest" --tests "com.neoproc.neoproccamx.ui.RecordingPipelineParityPolicyTest" --tests "com.neoproc.neoproccamx.recording.RecordingUserBitratePolicyTest" --tests "com.neoproc.neoproccamx.ui.controls.SettingsRuntimeFormatterTest" --tests "com.neoproc.neoproccamx.pipeline.FramePipelineZeroCopyContractTest" --tests "com.neoproc.neoproccamx.camera.Camera2PipelineRequestPolicyTest" --tests "com.neoproc.neoproccamx.recording.Npv10CaptureSourcePolicyTest"
```

Expected: PASS.

- [ ] **Step 5: Assemble a debug APK with native shader embedding**

Run:

```powershell
.\gradlew.bat :app:assembleDebug --rerun-tasks --no-build-cache --no-configuration-cache
```

Expected: `BUILD SUCCESSFUL` and a new `app/build/outputs/apk/debug/app-debug.apk`.

### Task 5: Deploy and Capture Runtime Evidence on the Connected Mobile Device

**Files:**
- Evidence output only: `qa-artifacts/v18-gpu-quality-detail/`

- [ ] **Step 1: Confirm an ADB target is connected before installation**

Run:

```powershell
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
& $adb devices -l
```

Expected: a device entry in state `device`. If no device is listed, stop device validation and report that deployment is blocked by the disconnected handset.

- [ ] **Step 2: Install and launch the verified debug APK**

Run with the connected serial substituted for `<serial>`:

```powershell
& $adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk
& $adb -s <serial> shell am force-stop com.neoproc.neoproccamx
& $adb -s <serial> shell am start -n com.neoproc.neoproccamx/.ui.MainActivity
& $adb -s <serial> shell pidof com.neoproc.neoproccamx
```

Expected: install succeeds and a running process ID is returned.

- [ ] **Step 3: Exercise processed recording modes using the existing evidence gate**

Run:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools/quality/run-v17-recorded-quality-repair-gate.ps1 `
    -AdbPath $adb `
    -Serial <serial> `
    -OutputDir "qa-artifacts/v18-gpu-quality-detail" `
    -DurationMs 2600 `
    -PostRecordWaitSec 34 `
    -IncludeRows "Pro","N-Log","NDR10+" `
    -MaxRows 3
```

Expected evidence for processed encoded modes:

```text
feed=INTERNAL_HARDWARE_IMPORT_ENCODER
processedRecordingFramesValidated=true
recordingGpu=true
```

Do not accept `recordingGpu=true` when processed-frame validation is false.

- [ ] **Step 4: Verify no crash was introduced**

Run:

```powershell
& $adb -s <serial> logcat -d -b crash
```

Expected: no new crash trace attributable to the recording or Vulkan shader path.

### Task 6: Review the Final Delta and Report Measured Scope

- [ ] **Step 1: Audit only intended files**

Run:

```powershell
git diff -- app/src/main/cpp/shaders/vulkan/ycbcr_preview.frag `
    app/src/test/java/com/neoproc/neoproccamx/rendering/VulkanPreviewShaderContractTest.kt `
    app/src/main/java/com/neoproc/neoproccamx/recording/Npv10MovConverter.kt `
    app/src/test/java/com/neoproc/neoproccamx/recording/Npv10ExportEnhancementPolicyTest.kt `
    app/src/main/cpp/generated/ycbcr_preview.frag.spv `
    app/src/main/cpp/generated/ycbcr_preview_frag_spv.h
```

Review that:

```text
Shader edits: noise coefficients, motion preserve factor, active guarded 4.85 gain only.
NPV10 edit: log mode final-dither routing only.
No edits: recording feed routing, bitrate policy, RAW storage path, ISP/distortion capability gating.
```

- [ ] **Step 2: Report results without unsupported image-quality claims**

Report:

```text
- Source changes made and files affected.
- Focused test and build commands with pass/fail state.
- Device serial and deployment result, when connected.
- Evidence-gate results for Pro/N-Log/NDR10+, when captured.
- Any unavailable measurement (noise variance, texture retention, bitrate probe) as not measured rather than assumed.
```
