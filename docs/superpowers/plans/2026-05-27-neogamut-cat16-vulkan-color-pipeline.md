# NeoGamut CAT16 Vulkan Color Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver a compiled, device-verifiable NeoGamut-cg/CAT16/OkLCh Vulkan color path for preview and processed recording while preventing the observed black-preview surface ownership failure.

**Architecture:** Stabilize ownership first: Camera2 and Vulkan must never produce to the visible `Surface` concurrently, while the MediaCodec encoder path is allowed to validate Vulkan rendering independently of visible-preview promotion. Extend the existing RGBA16F Vulkan graph through a fixed std140/JNI color payload, execute CAT16 and hue-locked OkLCh operations in the active shader branches, and preserve existing LUT, enhancement, transport, RAW, and NPV10 behavior. Keep the calibrated NLogs gray anchor and add a derivative-matched toe rather than installing an incompatible replacement curve.

**Tech Stack:** Kotlin, JUnit 4, Android Camera2, Vulkan/GLSL, JNI/C++, Android NDK `glslc`, Gradle, ADB/logcat.

---

## Dirty Worktree Constraint

The target workspace already contains user and earlier agent edits in overlapping implementation files, including:

```text
app/src/main/cpp/shaders/vulkan/ycbcr_preview.frag
app/src/main/java/com/neoproc/neoproccamx/color/ColorScience.kt
app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt
core-math/src/main/kotlin/com/neoproc/neoproccamx/core/NeoLogCurve.kt
```

During execution:

- Do not use `git reset`, `git checkout --`, or any command that discards existing edits.
- Before changing an overlapping file, save its current scoped diff with `git diff -- <path>` in the task notes and edit around the existing work.
- Use task checkpoints with `git diff --stat` and focused test output. Only create a commit after inspecting the staged diff and confirming that it contains the intended integrated result.
- New focused test or policy files may be committed independently only when their staged diff is isolated and the corresponding production behavior is already present or intentionally failing for TDD.

## File Map

**Create:**

- `app/src/main/java/com/neoproc/neoproccamx/rendering/VulkanVisibleSurfaceOwnershipPolicy.kt` - pure decision logic prohibiting a Vulkan visible-surface bind while Camera2 owns that surface.
- `app/src/main/java/com/neoproc/neoproccamx/color/NeoGamutCat16Math.kt` - immutable NeoGamut matrices, CAT16 math, finite validation, and manual-white chromaticity conversion.
- `app/src/main/java/com/neoproc/neoproccamx/rendering/VulkanColorScienceUniforms.kt` - fixed 40-float std140-compatible payload used by Kotlin, JNI, and GLSL.
- `app/src/main/java/com/neoproc/neoproccamx/rendering/VulkanColorScienceUniformPolicy.kt` - builds preview/encoder payloads from color mode, transport, and white point.
- `app/src/test/java/com/neoproc/neoproccamx/rendering/VulkanVisibleSurfaceOwnershipPolicyTest.kt`
- `app/src/test/java/com/neoproc/neoproccamx/color/NeoGamutCat16MathTest.kt`
- `app/src/test/java/com/neoproc/neoproccamx/rendering/VulkanColorScienceUniformPolicyTest.kt`

**Modify:**

- `app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt` - safe visible preview handoff, independent processed-recording qualification, and color uniform dispatch.
- `app/src/main/java/com/neoproc/neoproccamx/color/ColorScience.kt` - public NeoGamut/CAT16 integration and active-profile validation.
- `app/src/main/java/com/neoproc/neoproccamx/color/AcesColorEngine.kt` - use CAT16 rather than Bradford for Neo output adaptation.
- `core-math/src/main/kotlin/com/neoproc/neoproccamx/core/NeoLogCurve.kt` - C1-continuous toe retaining calibrated family variants and gray anchor.
- `app/src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt` - carry preview and encoder color payload arrays.
- `app/src/main/java/com/neoproc/neoproccamx/rendering/VulkanBridge.kt` - validate and forward new fixed arrays through JNI.
- `app/src/main/cpp/vulkan_bridge.cpp` - extend `RenderConfig` and `PreviewUniforms`, and copy the endpoint-specific payload.
- `app/src/main/cpp/shaders/vulkan/ycbcr_preview.frag` - apply composite CAT16/OkLCh operations and matching NLogs toe in the existing active shader path.
- `app/src/main/cpp/generated/ycbcr_preview.frag.spv`
- `app/src/main/cpp/generated/ycbcr_preview_frag_spv.h` - embedded fragment shader bytecode referenced by `vulkan_bridge.cpp`.

**Modify Tests:**

- `app/src/test/java/com/neoproc/neoproccamx/ui/RecordingStartSessionRefreshContractTest.kt`
- `app/src/test/java/com/neoproc/neoproccamx/rendering/VulkanVisiblePreviewPolicyTest.kt`
- `app/src/test/java/com/neoproc/neoproccamx/color/ColorSciencePreviewLutTest.kt`
- `app/src/test/java/com/neoproc/neoproccamx/color/AcesColorEngineTest.kt`
- `app/src/test/java/com/neoproc/neoproccamx/core/NeoLogCurveV3ContractTest.kt`
- `app/src/test/java/com/neoproc/neoproccamx/rendering/VulkanPreviewShaderContractTest.kt`

### Task 0: Capture Baseline Evidence And Guard Existing Work

**Files:**

- Read: all files in the File Map that currently show modifications.
- Read: `docs/superpowers/specs/2026-05-27-neogamut-cat16-vulkan-color-pipeline-design.md`

- [ ] **Step 1: Capture current scoped worktree state**

Run:

```powershell
git status --short
git diff --stat -- app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt app/src/main/java/com/neoproc/neoproccamx/color/ColorScience.kt app/src/main/cpp/shaders/vulkan/ycbcr_preview.frag core-math/src/main/kotlin/com/neoproc/neoproccamx/core/NeoLogCurve.kt
git diff -- app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt > $env:TEMP\neogamut-pre-viewmodel.diff
git diff -- app/src/main/cpp/shaders/vulkan/ycbcr_preview.frag > $env:TEMP\neogamut-pre-shader.diff
```

Expected: output identifies existing edits; the two temporary diff snapshots are created for comparison only and are not copied into source control.

- [ ] **Step 2: Record the existing runtime failure evidence**

Run with the connected handset and the currently installed debug app:

```powershell
adb devices
adb logcat -c
adb shell am force-stop com.neoproc.neoproccamx
adb shell monkey -p com.neoproc.neoproccamx -c android.intent.category.LAUNCHER 1
Start-Sleep -Seconds 5
adb logcat -d | Select-String -Pattern 'BufferQueueProducer|vkCreateAndroidSurfaceKHR|NeoProcVulkan|Internal hardware-import recording'
```

Expected before the ownership fix: reproduce or retain the known producer collision evidence when native visible preview promotion is attempted. If the error does not reproduce, retain the command output as the pre-change baseline and continue with policy tests.

- [ ] **Step 3: Run baseline focused tests before edits**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.ui.RecordingStartSessionRefreshContractTest" --tests "com.neoproc.neoproccamx.rendering.VulkanVisiblePreviewPolicyTest" --tests "com.neoproc.neoproccamx.core.NeoLogCurveV3ContractTest" --no-configuration-cache
```

Expected: current tests pass or expose pre-existing failure output. Save any failure text as baseline; do not modify unrelated failing areas during this feature.

### Task 1: Prevent Visible Surface Dual Ownership

**Files:**

- Create: `app/src/main/java/com/neoproc/neoproccamx/rendering/VulkanVisibleSurfaceOwnershipPolicy.kt`
- Create: `app/src/test/java/com/neoproc/neoproccamx/rendering/VulkanVisibleSurfaceOwnershipPolicyTest.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt:2364-2368,6094-6470`
- Modify: `app/src/test/java/com/neoproc/neoproccamx/ui/RecordingStartSessionRefreshContractTest.kt`

- [ ] **Step 1: Write a failing ownership-policy test**

Create the test with the local package and JUnit conventions:

```kotlin
package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VulkanVisibleSurfaceOwnershipPolicyTest {
    @Test
    fun `visible Vulkan binding is denied until Camera2 release is confirmed`() {
        assertFalse(
            VulkanVisibleSurfaceOwnershipPolicy.mayBindVulkanVisibleSurface(
                requestNativePreview = true,
                cameraOwnsSurface = true,
                cameraReleaseConfirmed = false,
                replacementSurfaceReady = true
            )
        )
        assertFalse(
            VulkanVisibleSurfaceOwnershipPolicy.mayBindVulkanVisibleSurface(
                requestNativePreview = true,
                cameraOwnsSurface = false,
                cameraReleaseConfirmed = false,
                replacementSurfaceReady = true
            )
        )
        assertTrue(
            VulkanVisibleSurfaceOwnershipPolicy.mayBindVulkanVisibleSurface(
                requestNativePreview = true,
                cameraOwnsSurface = false,
                cameraReleaseConfirmed = true,
                replacementSurfaceReady = true
            )
        )
    }

    @Test
    fun `direct preview remains eligible while a visible handoff is incomplete`() {
        assertTrue(
            VulkanVisibleSurfaceOwnershipPolicy.mustKeepCamera2VisiblePreview(
                requestNativePreview = true,
                cameraReleaseConfirmed = false
            )
        )
        assertFalse(
            VulkanVisibleSurfaceOwnershipPolicy.mustKeepCamera2VisiblePreview(
                requestNativePreview = true,
                cameraReleaseConfirmed = true
            )
        )
    }
}
```

- [ ] **Step 2: Run the ownership test to verify it fails**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.rendering.VulkanVisibleSurfaceOwnershipPolicyTest" --no-configuration-cache
```

Expected: FAIL because `VulkanVisibleSurfaceOwnershipPolicy` does not exist.

- [ ] **Step 3: Add the pure ownership gate**

Create:

```kotlin
package com.neoproc.neoproccamx.rendering

internal object VulkanVisibleSurfaceOwnershipPolicy {
    fun mayBindVulkanVisibleSurface(
        requestNativePreview: Boolean,
        cameraOwnsSurface: Boolean,
        cameraReleaseConfirmed: Boolean,
        replacementSurfaceReady: Boolean
    ): Boolean =
        requestNativePreview &&
            !cameraOwnsSurface &&
            cameraReleaseConfirmed &&
            replacementSurfaceReady

    fun mustKeepCamera2VisiblePreview(
        requestNativePreview: Boolean,
        cameraReleaseConfirmed: Boolean
    ): Boolean = requestNativePreview && !cameraReleaseConfirmed
}
```

- [ ] **Step 4: Gate `CameraViewModel` handoff with explicit release confirmation**

Add a release token beside the existing native surface state:

```kotlin
@Volatile private var nativePreviewCameraReleaseConfirmed = false
```

When starting a native visible-preview transition, explicitly clear it before the Camera2 rebuild:

```kotlin
nativePreviewCameraReleaseConfirmed = false
camera.setPreviewSurface(null)
```

In the existing previous-session-close callback path, set the token before calling the delayed completion routine:

```kotlin
nativePreviewCameraReleaseConfirmed = true
completeNativePreviewHandoffAfterCameraRelease(surface, generation)
```

Within `applyPreviewPipelineBinding(...)`, replace the unconditional visible Vulkan bind eligibility with:

```kotlin
val mayBindVisibleNativePreview =
    VulkanVisibleSurfaceOwnershipPolicy.mayBindVulkanVisibleSurface(
        requestNativePreview = preferNativePreview,
        cameraOwnsSurface = cameraOwnsVisiblePreviewSurface(surface),
        cameraReleaseConfirmed = nativePreviewCameraReleaseConfirmed,
        replacementSurfaceReady = !nativePreviewSurfaceResizePending
    )

if (preferNativePreview && !mayBindVisibleNativePreview) {
    return false
}

if (mayBindVisibleNativePreview && vulkanRuntime.setPreviewSurface(surface)) {
    nativeVisiblePreviewConfirmed = true
    directPreviewSurfaceActive = false
    return true
}
```

In direct-preview restoration and native binding failure branches, reset:

```kotlin
nativePreviewCameraReleaseConfirmed = false
nativeVisiblePreviewConfirmed = false
camera.setPreviewSurface(surface)
directPreviewSurfaceActive = true
```

When `onSurfaceReady` receives the compositor replacement surface during a pending handoff, preserve `nativePreviewCameraReleaseConfirmed` rather than clearing it with other new-surface state. Only clear that token when restoring direct preview or initiating a new Camera2-owned transition.

- [ ] **Step 5: Add source-contract assertions for the handoff token**

In `RecordingStartSessionRefreshContractTest.kt`, add:

```kotlin
@Test
fun `visible Vulkan preview is bound only after camera session release confirmation`() {
    assertTrue(source.contains("nativePreviewCameraReleaseConfirmed = false"))
    assertTrue(source.contains("nativePreviewCameraReleaseConfirmed = true"))
    assertTrue(source.contains("VulkanVisibleSurfaceOwnershipPolicy.mayBindVulkanVisibleSurface"))
    assertTrue(source.contains("cameraOwnsVisiblePreviewSurface(surface)"))
}
```

Use the test class's existing `source(...)` helper rather than creating a second reader.

- [ ] **Step 6: Run focused tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.rendering.VulkanVisibleSurfaceOwnershipPolicyTest" --tests "com.neoproc.neoproccamx.ui.RecordingStartSessionRefreshContractTest" --tests "com.neoproc.neoproccamx.rendering.VulkanVisiblePreviewPolicyTest" --no-configuration-cache
```

Expected: PASS. A failed source-contract assertion means the ViewModel state transition is not explicitly guarded and must be corrected before moving on.

- [ ] **Step 7: Checkpoint the scoped diff**

Run:

```powershell
git diff --stat -- app/src/main/java/com/neoproc/neoproccamx/rendering/VulkanVisibleSurfaceOwnershipPolicy.kt app/src/test/java/com/neoproc/neoproccamx/rendering/VulkanVisibleSurfaceOwnershipPolicyTest.kt app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt app/src/test/java/com/neoproc/neoproccamx/ui/RecordingStartSessionRefreshContractTest.kt
git diff -- app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt
```

Expected: the ViewModel diff includes the ownership token and gate without removing existing recording, UI, or surface-health logic.

### Task 2: Qualify Processed Recording Independently Of Visible Preview

**Files:**

- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt:1602-1618,3030-3400`
- Modify: `app/src/test/java/com/neoproc/neoproccamx/ui/RecordingStartSessionRefreshContractTest.kt`

- [ ] **Step 1: Replace the obsolete source-contract test**

Replace the current expectation that requires a native visible preview with:

```kotlin
@Test
fun `processed gpu recording eligibility does not require visible preview ownership`() {
    val qualifiedBlock = source
        .substringAfter("private fun vulkanRecordingPathQualified(): Boolean =")
        .substringBefore("private fun activeCamera2SessionGraphParityPlan(")
    assertTrue(qualifiedBlock.contains("vulkanSupported.value"))
    assertFalse(qualifiedBlock.contains("nativeVisiblePreviewConfirmed"))
    assertTrue(source.contains("awaitValidatedProcessedGpuRecordingFrames"))
    assertTrue(source.contains("Internal hardware-import recording started; visible preview unchanged"))
}
```

Retain tests proving that RAW/direct modes may choose their required direct recording surface and that actual imported-frame validation remains mandatory.

- [ ] **Step 2: Run the changed test to verify it fails against existing coupling**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.ui.RecordingStartSessionRefreshContractTest.processed gpu recording eligibility does not require visible preview ownership" --no-configuration-cache
```

Expected: FAIL because `vulkanRecordingPathQualified()` currently contains `nativeVisiblePreviewConfirmed`.

- [ ] **Step 3: Decouple feed-path eligibility from visible output ownership**

Replace only the eligibility body in `CameraViewModel.kt`:

```kotlin
private fun vulkanRecordingPathQualified(): Boolean =
    vulkanSupported.value
```

Do not remove the existing `awaitValidatedProcessedGpuRecordingFrames()` call. It is the runtime proof that actual frames reach the encoder and remains the gate for a processed-recording success message.

- [ ] **Step 4: Run recording parity tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.ui.RecordingStartSessionRefreshContractTest" --tests "com.neoproc.neoproccamx.ui.RecordingPipelineParityPolicyTest" --tests "com.neoproc.neoproccamx.ui.RecordingPreviewContinuityPolicyTest" --no-configuration-cache
```

Expected: PASS for `RecordingStartSessionRefreshContractTest`, `RecordingPipelineParityPolicyTest`, and `RecordingPreviewContinuityPolicyTest`, all present in this checkout.

- [ ] **Step 5: Checkpoint only the changed ViewModel region**

Run:

```powershell
git diff -- app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt | Select-String -Pattern 'vulkanRecordingPathQualified|nativePreviewCameraReleaseConfirmed|VulkanVisibleSurfaceOwnershipPolicy' -Context 3,3
```

Expected: recording eligibility is relaxed only for processed encoder qualification; visible preview ownership remains protected.

### Task 3: Add Correct NeoGamut And CAT16 Math

**Files:**

- Create: `app/src/main/java/com/neoproc/neoproccamx/color/NeoGamutCat16Math.kt`
- Create: `app/src/test/java/com/neoproc/neoproccamx/color/NeoGamutCat16MathTest.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/color/ColorScience.kt:80-280`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/color/AcesColorEngine.kt:62-178,400-428`
- Modify: `app/src/test/java/com/neoproc/neoproccamx/color/ColorSciencePreviewLutTest.kt`
- Modify: `app/src/test/java/com/neoproc/neoproccamx/color/AcesColorEngineTest.kt`

- [ ] **Step 1: Write numerical math tests**

Create:

```kotlin
package com.neoproc.neoproccamx.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeoGamutCat16MathTest {
    @Test
    fun `NeoGamut matrices round trip within single precision tolerance`() {
        val product = NeoGamutCat16Math.multiply3x3(
            NeoGamutCat16Math.XYZ_TO_NEO,
            NeoGamutCat16Math.NEO_TO_XYZ
        )
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        identity.indices.forEach { index ->
            assertEquals(identity[index], product[index], 1e-5f)
        }
    }

    @Test
    fun `CAT16 returns identity for D55 source white`() {
        val matrix = NeoGamutCat16Math.calculateCat16ToD55(
            NeoGamutCat16Math.D55_X,
            NeoGamutCat16Math.D55_Y
        )
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        identity.indices.forEach { index ->
            assertEquals(identity[index], matrix[index], 1e-5f)
        }
    }

    @Test
    fun `manual white conversion and CAT16 never emit non finite values`() {
        listOf(2000f, 3200f, 5500f, 6500f, 10000f).forEach { kelvin ->
            val xy = NeoGamutCat16Math.kelvinToCieXy(kelvin)
            val matrix = NeoGamutCat16Math.calculateCat16ToD55(xy.first, xy.second)
            assertTrue(matrix.all { it.isFinite() })
            assertTrue(xy.second > 0f)
        }
    }
}
```

- [ ] **Step 2: Run tests to verify the new type is absent**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.color.NeoGamutCat16MathTest" --no-configuration-cache
```

Expected: FAIL because `NeoGamutCat16Math` does not exist.

- [ ] **Step 3: Create the focused pure math object**

Create:

```kotlin
package com.neoproc.neoproccamx.color

import kotlin.math.max

internal object NeoGamutCat16Math {
    const val D55_X = 0.3324f
    const val D55_Y = 0.3474f
    const val D60_X = 0.32168f
    const val D60_Y = 0.33767f
    const val D65_X = 0.3127f
    const val D65_Y = 0.3290f

    val NEO_TO_XYZ = floatArrayOf(
        0.697475f, 0.127814f, 0.125139f,
        0.252003f, 0.918002f, 0.016001f,
        0.000000f, 0.062402f, 1.341142f
    )

    val XYZ_TO_NEO = floatArrayOf(
        1.506115607f, -0.200307072f, -0.138142484f,
        -0.413783104f, 1.145237837f, 0.024945497f,
        0.019252915f, -0.053286775f, 0.744472510f
    )

    private val CAT16 = floatArrayOf(
        0.4012f, 0.6501f, -0.0514f,
        -0.2503f, 1.2017f, 0.0485f,
        -0.0021f, 0.0754f, 1.1712f
    )

    private val CAT16_INVERSE = floatArrayOf(
        1.86042218f, -1.01421605f, 0.12364684f,
        0.38837858f, 0.62259623f, -0.00873741f,
        -0.02166740f, -0.04190028f, 0.85460934f
    )

    fun calculateCat16Matrix(
        sourceX: Float,
        sourceY: Float,
        targetX: Float,
        targetY: Float
    ): FloatArray {
        if (!sourceX.isFinite() || !sourceY.isFinite() || sourceY <= 1e-6f ||
            !targetX.isFinite() || !targetY.isFinite() || targetY <= 1e-6f
        ) return identity3()
        val source = xyToXyz(sourceX, sourceY)
        val target = xyToXyz(targetX, targetY)
        val sourceResponse = multiplyVector(CAT16, source)
        val targetResponse = multiplyVector(CAT16, target)
        if (sourceResponse.any { !it.isFinite() || kotlin.math.abs(it) <= 1e-6f }) return identity3()
        val scaledCat16 = CAT16.copyOf()
        for (row in 0..2) {
            val scale = targetResponse[row] / sourceResponse[row]
            for (col in 0..2) scaledCat16[row * 3 + col] *= scale
        }
        return multiply3x3(CAT16_INVERSE, scaledCat16).takeIf { result ->
            result.all { it.isFinite() }
        } ?: identity3()
    }

    fun calculateCat16ToD55(sourceX: Float, sourceY: Float): FloatArray =
        calculateCat16Matrix(sourceX, sourceY, D55_X, D55_Y)

    fun kelvinToCieXy(kelvin: Float): Pair<Float, Float> {
        val t = kelvin.coerceIn(1667f, 25000f).toDouble()
        val x = if (t <= 4000.0) {
            -0.2661239e9 / (t * t * t) - 0.2343580e6 / (t * t) + 0.8776956e3 / t + 0.179910
        } else {
            -3.0258469e9 / (t * t * t) + 2.1070379e6 / (t * t) + 0.2226347e3 / t + 0.240390
        }
        val y = when {
            t <= 2222.0 -> -1.1063814 * x * x * x - 1.34811020 * x * x + 2.18555832 * x - 0.20219683
            t <= 4000.0 -> -0.9549476 * x * x * x - 1.37418593 * x * x + 2.09137015 * x - 0.16748867
            else -> 3.0817580 * x * x * x - 5.87338670 * x * x + 3.75112997 * x - 0.37001483
        }
        return x.toFloat() to y.toFloat()
    }

    fun multiply3x3(a: FloatArray, b: FloatArray): FloatArray {
        require(a.size == 9 && b.size == 9)
        return FloatArray(9) { index ->
            val row = index / 3
            val col = index % 3
            (0..2).sumOf { k -> (a[row * 3 + k] * b[k * 3 + col]).toDouble() }.toFloat()
        }
    }

    private fun multiplyVector(matrix: FloatArray, vector: FloatArray): FloatArray =
        FloatArray(3) { row ->
            matrix[row * 3] * vector[0] +
                matrix[row * 3 + 1] * vector[1] +
                matrix[row * 3 + 2] * vector[2]
        }

    private fun xyToXyz(x: Float, y: Float): FloatArray {
        val safeY = max(y, 1e-6f)
        return floatArrayOf(x / safeY, 1f, (1f - x - y) / safeY)
    }

    private fun identity3(): FloatArray =
        floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
}
```

- [ ] **Step 4: Route public color-engine Neo definitions through the tested object**

In `ColorScience.kt`, retain existing API names used elsewhere, but source values and adaptation through:

```kotlin
const val NEO_GAMUT_VERSION = "NeoGamut-cg-CAT16-v4.0"
val NEO_GAMUT_TO_XYZ: FloatArray get() = NeoGamutCat16Math.NEO_TO_XYZ.copyOf()
val XYZ_TO_NEO_GAMUT: FloatArray get() = NeoGamutCat16Math.XYZ_TO_NEO.copyOf()

fun calculateNeoCat16Matrix(sourceWhiteX: Float, sourceWhiteY: Float): FloatArray =
    NeoGamutCat16Math.calculateCat16ToD55(sourceWhiteX, sourceWhiteY)
```

Adapt the existing internal `Matrix3` construction to use these corrected values. Keep current profile registry entries and existing hue-grade callers intact.

In `AcesColorEngine.kt`, replace the Bradford cone adaptation body with CAT16 matrix application, and expose the two composite shader matrices required because the active fragment intermediate is ACEScg D60 rather than NeoGamut input:

```kotlin
internal data class NeoOklabReferenceMatrices(
    val acesCgToD65ReferenceRgb: FloatArray,
    val d65ReferenceRgbToAcesCg: FloatArray
)

private fun adaptationMatrix(sourceWhite: Chromaticity, targetWhite: Chromaticity): Matrix3 =
    NeoGamutCat16Math.calculateCat16Matrix(
        sourceWhite.x.toFloat(),
        sourceWhite.y.toFloat(),
        targetWhite.x.toFloat(),
        targetWhite.y.toFloat()
    ).toMatrix3()

private fun adaptWhite(xyz: Vec3, sourceWhite: Chromaticity, targetWhite: Chromaticity): Vec3 =
    adaptationMatrix(sourceWhite, targetWhite).multiply(xyz)

internal fun neoOklabReferenceMatrices(): NeoOklabReferenceMatrices {
    val acesToNeo = rgbToXyzMatrix(NEO_WIDE_GAMUT).inverse()
        .multiply(adaptationMatrix(ACES_D60, D55))
        .multiply(acesCgToXyz)
    val neoToD65Reference = rgbToXyzMatrix(REC709).inverse()
        .multiply(adaptationMatrix(D55, D65))
        .multiply(rgbToXyzMatrix(NEO_WIDE_GAMUT))
    val acesToReference = neoToD65Reference.multiply(acesToNeo)
    return NeoOklabReferenceMatrices(
        acesCgToD65ReferenceRgb = acesToReference.toFloatArray(),
        d65ReferenceRgbToAcesCg = acesToReference.inverse().toFloatArray()
    )
}

private fun Matrix3.toFloatArray(): FloatArray = floatArrayOf(
    m00.toFloat(), m01.toFloat(), m02.toFloat(),
    m10.toFloat(), m11.toFloat(), m12.toFloat(),
    m20.toFloat(), m21.toFloat(), m22.toFloat()
)
```

This preserves the existing ACEScg working image, traverses the NeoGamut D55 definition for color-science ownership, and feeds Oklab with a D65 reference RGB value as required by its published matrix.

- [ ] **Step 5: Add public-registry and adaptation tests**

Extend `ColorSciencePreviewLutTest.kt`:

```kotlin
@Test
fun `Neo gamut conversion and CAT16 public contract are finite and invertible`() {
    val forward = ColorScience.NEO_GAMUT_TO_XYZ
    val inverse = ColorScience.XYZ_TO_NEO_GAMUT
    val roundTrip = NeoGamutCat16Math.multiply3x3(inverse, forward)
    assertEquals(1f, roundTrip[0], 1e-5f)
    assertEquals(1f, roundTrip[4], 1e-5f)
    assertEquals(1f, roundTrip[8], 1e-5f)
    assertTrue(ColorScience.calculateNeoCat16Matrix(0.3127f, 0.3290f).all { it.isFinite() })
}
```

Extend `AcesColorEngineTest.kt`:

```kotlin
@Test
fun `Neo Oklab shader matrices round trip ACEScg samples through D65 reference`() {
    val transforms = AcesColorEngine.neoOklabReferenceMatrices()
    assertTrue(transforms.acesCgToD65ReferenceRgb.all { it.isFinite() })
    for (row in 0..2) {
        for (col in 0..2) {
            val value = (0..2).sumOf { k ->
                transforms.d65ReferenceRgbToAcesCg[row * 3 + k].toDouble() *
                    transforms.acesCgToD65ReferenceRgb[k * 3 + col].toDouble()
            }.toFloat()
            assertEquals(if (row == col) 1f else 0f, value, 1e-5f)
        }
    }
}
```

- [ ] **Step 6: Run color math tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.color.NeoGamutCat16MathTest" --tests "com.neoproc.neoproccamx.color.ColorSciencePreviewLutTest" --tests "com.neoproc.neoproccamx.color.AcesColorEngineTest" --no-configuration-cache
```

Expected: PASS with matrix identity tolerance `1e-5` and no non-finite adaptation values.

### Task 4: Make NLogs Toe C1-Continuous Without Breaking Gray Calibration

**Files:**

- Modify: `core-math/src/main/kotlin/com/neoproc/neoproccamx/core/NeoLogCurve.kt:74-140`
- Modify: `app/src/test/java/com/neoproc/neoproccamx/core/NeoLogCurveV3ContractTest.kt`

- [ ] **Step 1: Add continuity and monotonicity tests for all existing variants**

Extend `NeoLogCurveV3ContractTest.kt`:

```kotlin
@Test
fun `all variants retain gray anchor and have a continuous first derivative at the toe`() {
    val epsilon = 1e-5f
    NeoLogCurve.Variant.entries.forEach { variant ->
        val cut = NeoLogCurve.toeCut(variant)
        val leftValue = NeoLogCurve.encode(cut - epsilon, variant)
        val rightValue = NeoLogCurve.encode(cut + epsilon, variant)
        val leftSlope = (NeoLogCurve.encode(cut, variant) - leftValue) / epsilon
        val rightSlope = (rightValue - NeoLogCurve.encode(cut, variant)) / epsilon
        assertEquals(NeoLogCurve.GRAY_18_CODE, NeoLogCurve.encode(NeoLogCurve.SCENE_GRAY_18, variant), 0.0015f)
        assertEquals(leftValue, rightValue, 0.001f)
        assertEquals(leftSlope, rightSlope, 0.06f)
    }
}

@Test
fun `NLogs variants remain monotonic and invertible after toe refinement`() {
    NeoLogCurve.Variant.entries.forEach { variant ->
        var previous = -1f
        for (index in 0..512) {
            val linear = index / 512f
            val encoded = NeoLogCurve.encode(linear, variant)
            assertTrue(encoded >= previous)
            assertEquals(linear, NeoLogCurve.decode(encoded, variant), 0.002f)
            previous = encoded
        }
    }
}
```

Expose `toeCut(variant)` as an internal testing accessor from the core object:

```kotlin
internal fun toeCut(variant: Variant): Float = profileFor(variant).toeCut
```

- [ ] **Step 2: Run the test to verify current linear toe does not match the logarithmic derivative**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.core.NeoLogCurveV3ContractTest" --no-configuration-cache
```

Expected: FAIL on derivative continuity for at least one variant before the quadratic toe is added.

- [ ] **Step 3: Add calibrated quadratic coefficients to `CurveProfile`**

Within the existing `CurveProfile`, retain `toeHeight`, `grayLog`, and `logScale`, and add:

```kotlin
val logSlopeAtToe: Float = logScale * logDensity
val toeQuadratic: Float = (logSlopeAtToe * toeCut - toeHeight) / (toeCut * toeCut)
val toeLinear: Float = (2.0f * toeHeight - logSlopeAtToe * toeCut) / toeCut
```

Replace the low branch in `encode` with:

```kotlin
val encoded = if (safeLinear < profile.toeCut) {
    profile.toeQuadratic * safeLinear * safeLinear + profile.toeLinear * safeLinear
} else {
    profile.toeHeight +
        profile.logScale * ln(1.0f + profile.logDensity * (safeLinear - profile.toeCut))
}
```

Replace the low branch in `decode` with:

```kotlin
val decoded = if (safeValue < profile.toeHeight) {
    val discriminant =
        profile.toeLinear * profile.toeLinear + 4.0f * profile.toeQuadratic * safeValue
    if (kotlin.math.abs(profile.toeQuadratic) <= 1e-8f) {
        safeValue / profile.toeLinear
    } else {
        (-profile.toeLinear + kotlin.math.sqrt(kotlin.math.max(discriminant, 0.0f))) /
            (2.0f * profile.toeQuadratic)
    }
} else {
    profile.toeCut +
        (kotlin.math.exp((safeValue - profile.toeHeight) / profile.logScale) - 1.0f) /
            profile.logDensity
}
```

Clamp the returned decoded value using the existing bounds already applied by the core curve.

- [ ] **Step 4: Run log tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.core.NeoLogCurveV3ContractTest" --tests "com.neoproc.neoproccamx.ai.NLogMathTest" --tests "com.neoproc.neoproccamx.color.VideoLogProfilePolicyTest" --no-configuration-cache
```

Expected: PASS; all variants still encode 18% gray to `0.50` and round-trip within stated tolerances.

### Task 5: Define The Vulkan Color-Science Payload And Policy

**Files:**

- Create: `app/src/main/java/com/neoproc/neoproccamx/rendering/VulkanColorScienceUniforms.kt`
- Create: `app/src/main/java/com/neoproc/neoproccamx/rendering/VulkanColorScienceUniformPolicy.kt`
- Create: `app/src/test/java/com/neoproc/neoproccamx/rendering/VulkanColorScienceUniformPolicyTest.kt`

- [ ] **Step 1: Write failing payload size and dynamic-white tests**

Create:

```kotlin
package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VulkanColorScienceUniformPolicyTest {
    @Test
    fun `color science payload is std140 sized and finite`() {
        val payload = VulkanColorScienceUniformPolicy.resolve(
            enabled = true,
            hdrMappingEnabled = false,
            logDeliveryEnabled = true,
            nivukxVision = false,
            hdrKnee = 0.72f,
            hdrShoulder = 2.8f,
            volumeCompression = 0.54f
        ).pack()
        assertEquals(40, payload.size)
        assertTrue(payload.all { it.isFinite() })
        assertEquals(0f, payload[32], 0f)
        assertEquals(1f, payload[37], 0f)
    }

    @Test
    fun `disabled payload is identity while enabled payload traverses NeoGamut reference transforms`() {
        val disabled = VulkanColorScienceUniformPolicy.resolve(false, false, false, false, 0.72f, 2.8f, 0.54f).pack()
        val enabled = VulkanColorScienceUniformPolicy.resolve(true, false, false, false, 0.72f, 2.8f, 0.54f).pack()
        assertFalse(disabled.copyOfRange(0, 16).contentEquals(enabled.copyOfRange(0, 16)))
        assertEquals(1f, enabled[36], 0f)
    }
}
```

- [ ] **Step 2: Run tests to verify the payload classes are absent**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.rendering.VulkanColorScienceUniformPolicyTest" --no-configuration-cache
```

Expected: FAIL because both Vulkan color-science classes are new.

- [ ] **Step 3: Implement the fixed array container**

Create `VulkanColorScienceUniforms.kt`:

```kotlin
package com.neoproc.neoproccamx.rendering

internal data class VulkanColorScienceUniforms(
    val acesCgToOklabReference: FloatArray,
    val oklabReferenceToAcesCg: FloatArray,
    val hdrParams: FloatArray,
    val modeParams: FloatArray
) {
    init {
        require(acesCgToOklabReference.size == 16)
        require(oklabReferenceToAcesCg.size == 16)
        require(hdrParams.size == 4)
        require(modeParams.size == 4)
        require(pack().all { it.isFinite() })
    }

    fun pack(): FloatArray =
        acesCgToOklabReference + oklabReferenceToAcesCg + hdrParams + modeParams

    companion object {
        const val FLOAT_COUNT = 40
        private val IDENTITY_4 = floatArrayOf(
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )

        fun disabled(): VulkanColorScienceUniforms =
            VulkanColorScienceUniforms(
                acesCgToOklabReference = IDENTITY_4.copyOf(),
                oklabReferenceToAcesCg = IDENTITY_4.copyOf(),
                hdrParams = floatArrayOf(0f, 0.72f, 2.8f, 0f),
                modeParams = floatArrayOf(0f, 0f, 0f, 0f)
            )
    }
}
```

- [ ] **Step 4: Implement deterministic host policy**

Create `VulkanColorScienceUniformPolicy.kt`:

```kotlin
package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.color.AcesColorEngine

internal object VulkanColorScienceUniformPolicy {
    fun resolve(
        enabled: Boolean,
        hdrMappingEnabled: Boolean,
        logDeliveryEnabled: Boolean,
        nivukxVision: Boolean,
        hdrKnee: Float,
        hdrShoulder: Float,
        volumeCompression: Float
    ): VulkanColorScienceUniforms {
        if (!enabled) return VulkanColorScienceUniforms.disabled()
        val referenceMatrices = AcesColorEngine.neoOklabReferenceMatrices()
        return VulkanColorScienceUniforms(
            acesCgToOklabReference = toMat4(referenceMatrices.acesCgToD65ReferenceRgb),
            oklabReferenceToAcesCg = toMat4(referenceMatrices.d65ReferenceRgbToAcesCg),
            hdrParams = floatArrayOf(
                if (hdrMappingEnabled) 1f else 0f,
                hdrKnee.coerceIn(0.01f, 0.98f),
                hdrShoulder.coerceAtLeast(0.01f),
                volumeCompression.coerceIn(0f, 1f)
            ),
            modeParams = floatArrayOf(
                1f,
                if (logDeliveryEnabled) 1f else 0f,
                if (nivukxVision) 1f else 0f,
                0f
            )
        )
    }

    private fun toMat4(matrix: FloatArray): FloatArray = floatArrayOf(
        matrix[0], matrix[3], matrix[6], 0f,
        matrix[1], matrix[4], matrix[7], 0f,
        matrix[2], matrix[5], matrix[8], 0f,
        0f, 0f, 0f, 1f
    )
}
```

`toMat4` transposes the mathematical row-major array into GLSL column-major uniform storage order. The shader consumes the packed matrices with standard `mat4 * vec4` multiplication.

- [ ] **Step 5: Run payload policy tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.rendering.VulkanColorScienceUniformPolicyTest" --tests "com.neoproc.neoproccamx.color.NeoGamutCat16MathTest" --no-configuration-cache
```

Expected: PASS with a 40-float finite payload and an enabled ACEScg-through-NeoGamut reference transform distinct from the disabled identity payload.

### Task 6: Extend Kotlin/JNI/Native Uniform ABI And Wire Active Configuration

**Files:**

- Modify: `app/src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt:1-40,262-370`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/rendering/VulkanBridge.kt:85-115,240-260`
- Modify: `app/src/main/cpp/vulkan_bridge.cpp:56-100,1857-1875,2346-2385,3254-3300`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt:5646-5675`
- Modify: `app/src/test/java/com/neoproc/neoproccamx/rendering/VulkanPreviewShaderContractTest.kt`

- [ ] **Step 1: Add a source-level ABI contract test before implementation**

Extend `VulkanPreviewShaderContractTest.kt` with local source reads matching that test file's existing helper:

```kotlin
private val runtimeSource: String by lazy {
    File("src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt").readText()
}
private val bridgeSource: String by lazy {
    File("src/main/java/com/neoproc/neoproccamx/rendering/VulkanBridge.kt").readText()
}
private val nativeBridgeSource: String by lazy {
    File("src/main/cpp/vulkan_bridge.cpp").readText()
}

@Test
fun `native render config carries endpoint color science payloads`() {
    assertTrue(runtimeSource.contains("previewColorScience"))
    assertTrue(runtimeSource.contains("encoderColorScience"))
    assertTrue(bridgeSource.contains("VulkanColorScienceUniforms.FLOAT_COUNT"))
    assertTrue(nativeBridgeSource.contains("previewColorScience"))
    assertTrue(nativeBridgeSource.contains("encoderColorScience"))
    assertTrue(nativeBridgeSource.contains("float colorScience[40]"))
}
```

- [ ] **Step 2: Run the ABI test to verify it fails**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.rendering.VulkanPreviewShaderContractTest.native render config carries endpoint color science payloads" --no-configuration-cache
```

Expected: FAIL because the arrays do not yet traverse the runtime bridge.

- [ ] **Step 3: Extend the Kotlin runtime configuration**

Extend the existing `VulkanRenderConfig` declaration:

```kotlin
data class VulkanRenderConfig(
    val previewTransform: FloatArray,
    val encoderTransform: FloatArray,
    val colorGain: FloatArray,
    val previewGrade: FloatArray,
    val encoderGrade: FloatArray,
    val previewColorScience: FloatArray = VulkanColorScienceUniforms.disabled().pack(),
    val encoderColorScience: FloatArray = VulkanColorScienceUniforms.disabled().pack()
)
```

Add `contentEquals` and `contentHashCode` treatment for both arrays wherever this data class currently customizes equality/hash behavior. Extend `updateRenderConfig(...)` to take the two new arguments and forward them to the bridge:

```kotlin
fun updateRenderConfig(
    previewTransform: FloatArray,
    encoderTransform: FloatArray,
    colorGain: FloatArray,
    previewGrade: FloatArray,
    encoderGrade: FloatArray,
    previewColorScience: FloatArray = VulkanColorScienceUniforms.disabled().pack(),
    encoderColorScience: FloatArray = VulkanColorScienceUniforms.disabled().pack()
) {
    require(previewColorScience.size == VulkanColorScienceUniforms.FLOAT_COUNT)
    require(encoderColorScience.size == VulkanColorScienceUniforms.FLOAT_COUNT)
    // Preserve the existing synchronization and state update, adding both arrays to VulkanRenderConfig.
}
```

Use the existing lock and update dispatch in the method body; do not replace its synchronization.

- [ ] **Step 4: Extend `VulkanBridge` validation and JNI signature**

Extend the current bridge method parameters and array checks:

```kotlin
require(previewColorScience.size == VulkanColorScienceUniforms.FLOAT_COUNT)
require(encoderColorScience.size == VulkanColorScienceUniforms.FLOAT_COUNT)
require(previewColorScience.all { it.isFinite() })
require(encoderColorScience.all { it.isFinite() })
nativeUpdateRenderConfig(
    handle,
    previewTransform,
    encoderTransform,
    colorGain,
    previewGrade,
    encoderGrade,
    previewColorScience,
    encoderColorScience
)
```

Extend the external declaration with:

```kotlin
previewColorScience: FloatArray,
encoderColorScience: FloatArray
```

- [ ] **Step 5: Extend native state and uniform copying**

In `vulkan_bridge.cpp`, extend the existing structs without changing binding indices:

```cpp
struct RenderConfig {
    std::array<float, 16> previewTransform{};
    std::array<float, 16> encoderTransform{};
    std::array<float, 4> colorGain{};
    std::array<float, 16> previewGrade{};
    std::array<float, 16> encoderGrade{};
    std::array<float, 40> previewColorScience{};
    std::array<float, 40> encoderColorScience{};
};

struct PreviewUniforms {
    float transform[16];
    float colorGain[4];
    float grade[16];
    float enhancement[4];
    float subjectDetail[4];
    float colorScience[40];
};
```

In `writeUniforms(...)`, after existing enhancement copying:

```cpp
const auto& colorScience = previewTransform
        ? state->renderConfig.previewColorScience
        : state->renderConfig.encoderColorScience;
std::copy(colorScience.begin(), colorScience.end(), uniforms.colorScience);
```

In `nativeUpdateRenderConfig`, add two `jfloatArray` arguments, validate lengths equal `40`, and copy into `state->renderConfig.previewColorScience` and `state->renderConfig.encoderColorScience` using the same existing array-copy pattern used for grade arrays. Return without applying an incomplete config if any array length is wrong.

- [ ] **Step 6: Wire the payload from `CameraViewModel`**

In `pushVulkanPreviewRenderConfig(...)`, construct endpoint payloads before the existing `updateRenderConfig` call:

```kotlin
val deliveryState = activeVideoFormatDeliveryState()
val nivukxVision =
    renderContract.normalizedHdrMode.equals("NIVUKXVISION", ignoreCase = true)
val neoColorScienceEnabled =
    _uiMode.value == AppMode.VIDEO && deliveryState.isCinemaDeliveryMode
val neoHdrMappingEnabled = renderContract.normalizedHdrMode != "OFF"
val previewColorScience = VulkanColorScienceUniformPolicy.resolve(
    enabled = neoColorScienceEnabled,
    hdrMappingEnabled = neoHdrMappingEnabled,
    logDeliveryEnabled = false,
    nivukxVision = nivukxVision,
    hdrKnee = if (nivukxVision) 0.55f else 0.59f,
    hdrShoulder = if (nivukxVision) 4.12f else 3.72f,
    volumeCompression = if (nivukxVision) 0.64f else 0.54f
).pack()
val encoderColorScience = VulkanColorScienceUniformPolicy.resolve(
    enabled = neoColorScienceEnabled,
    hdrMappingEnabled = neoHdrMappingEnabled,
    logDeliveryEnabled = renderContract.logCaptureActive,
    nivukxVision = nivukxVision,
    hdrKnee = if (nivukxVision) 0.55f else 0.59f,
    hdrShoulder = if (nivukxVision) 4.12f else 3.72f,
    volumeCompression = if (nivukxVision) 0.64f else 0.54f
).pack()
```

Then extend the existing call:

```kotlin
vulkanRuntime.updateRenderConfig(
    previewTransform = previewTransform,
    encoderTransform = encoderTransform,
    colorGain = buildPreviewColorGain(),
    previewGrade = previewGrade,
    encoderGrade = encoderGrade,
    previewColorScience = previewColorScience,
    encoderColorScience = encoderColorScience
)
```

The current imported shader input has already entered an ACEScg D60 working representation. Do not apply `camera.physicalImagingTraits.whiteBalanceKelvin` a second time on this post-correction image. `CameraController` already tracks live capture-result white balance for the sensor/IDT path; a later raw-linear shader input can pass that measured source white into `NeoGamutCat16Math.calculateCat16Matrix(...)` before it becomes ACEScg.

- [ ] **Step 7: Run ABI and Kotlin tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.rendering.VulkanColorScienceUniformPolicyTest" --tests "com.neoproc.neoproccamx.rendering.VulkanPreviewShaderContractTest" --tests "com.neoproc.neoproccamx.ui.RecordingStartSessionRefreshContractTest" --no-configuration-cache
```

Expected: PASS; no JNI signature compilation occurs until the build task, but all source contracts and Kotlin compilation pass.

### Task 7: Execute CAT16, OkLCh, And C1 NLogs In The Active Vulkan Shader

**Files:**

- Modify: `app/src/main/cpp/shaders/vulkan/ycbcr_preview.frag:11-20,351-435,670-733,1200-1560`
- Modify: `app/src/test/java/com/neoproc/neoproccamx/rendering/VulkanPreviewShaderContractTest.kt`

- [ ] **Step 1: Add shader contract assertions**

Extend `VulkanPreviewShaderContractTest.kt`:

```kotlin
@Test
fun `active fragment shader carries NeoGamut CAT16 OkLCh and C1 log operations`() {
    assertTrue(shaderSource.contains("mat4 uAcesCgToOklabReference"))
    assertTrue(shaderSource.contains("mat4 uOklabReferenceToAcesCg"))
    assertTrue(shaderSource.contains("vec4 uNeoHdrParams"))
    assertTrue(shaderSource.contains("vec4 uNeoModeParams"))
    assertTrue(shaderSource.contains("applyNeoGamutOkLchPipeline"))
    assertTrue(shaderSource.contains("toeQuadratic"))
    assertTrue(shaderSource.contains("toeLinear"))
    assertTrue(shaderSource.contains("uNeoModeParams.x > 0.5"))
}
```

- [ ] **Step 2: Run the shader contract test to verify it fails**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.rendering.VulkanPreviewShaderContractTest.active fragment shader carries NeoGamut CAT16 OkLCh and C1 log operations" --no-configuration-cache
```

Expected: FAIL because the new uniforms and pipeline function are not in the shader yet.

- [ ] **Step 3: Extend the existing uniform block**

Append fields to the existing `PreviewParams` block after its current `uSubjectDetail` field:

```glsl
    mat4 uAcesCgToOklabReference;
    mat4 uOklabReferenceToAcesCg;
    vec4 uNeoHdrParams;  // enabled, knee, shoulder, volume compression
    vec4 uNeoModeParams; // enabled, NLogs delivery, NIVUKX, reserved
```

Keep bindings `0`, `1`, and `2` unchanged.

- [ ] **Step 4: Add the Neo color processing functions without removing existing enhancement functions**

Near the existing Oklab functions, add:

```glsl
float signedCbrt(float value) {
    return sign(value) * pow(abs(value), 1.0 / 3.0);
}

vec3 referenceLinearToOklab(vec3 rgb) {
    const mat3 rgbToLms = mat3(
        0.4122214708, 0.2119034982, 0.0883024619,
        0.5363325363, 0.6806995451, 0.2817188376,
        0.0514459929, 0.1073969566, 0.6299787005
    );
    const mat3 lmsToLab = mat3(
        0.2104542553, 1.9779984951, 0.0259040371,
        0.7936177850, -2.4285922050, 0.7827717662,
       -0.0040720468, 0.4505937099, -0.8086757660
    );
    vec3 lms = rgbToLms * rgb;
    return lmsToLab * vec3(signedCbrt(lms.r), signedCbrt(lms.g), signedCbrt(lms.b));
}

vec3 oklabToReferenceLinear(vec3 lab) {
    const mat3 labToLmsRoot = mat3(
        1.0, 1.0, 1.0,
        0.3963377774, -0.1055613458, -0.0894841775,
        0.2158037573, -0.0638541728, -1.2914855480
    );
    const mat3 lmsToRgb = mat3(
        4.0767416621, -1.2684380046, -0.0041960863,
       -3.3077115913,  2.6097574011, -0.7034186147,
        0.2309699292, -0.3413193965,  1.7076147010
    );
    vec3 root = labToLmsRoot * lab;
    return lmsToRgb * (root * root * root);
}

vec3 applyNeoGamutOkLchPipeline(vec3 acesCg, float contrast, float saturation) {
    if (uNeoModeParams.x <= 0.5) return acesCg;
    vec3 referenceRgb = (uAcesCgToOklabReference * vec4(acesCg, 1.0)).rgb;
    vec3 lab = referenceLinearToOklab(max(referenceRgb, vec3(0.0)));
    float chroma = length(lab.yz);
    float hue = atan(lab.z, lab.y);
    float lightness = max(lab.x, 0.0);
    float knee = clamp(uNeoHdrParams.y, 0.01, 0.98);
    if (uNeoHdrParams.x > 0.5 && lightness > knee) {
        float shoulder = max(uNeoHdrParams.z, 0.01);
        lightness = knee + (1.0 - knee) *
            tanh(shoulder * (lightness - knee) / max(1.0 - knee, 0.0001));
    }
    lightness = mix(lightness, 1.0 / (1.0 + exp(-max(contrast, 0.01) * (lightness - 0.5))), clamp(abs(contrast - 1.0), 0.0, 1.0));
    chroma *= max(saturation, 0.0);
    float volume = tanh(max(lightness, 0.0) * max(lightness, 0.0)) * clamp(uNeoHdrParams.w, 0.0, 1.0);
    chroma *= 1.0 - volume;
    chroma *= 1.0 - smoothstep(0.82, 0.98, lightness) * 0.85;
    vec3 gradedLab = vec3(lightness, chroma * cos(hue), chroma * sin(hue));
    vec3 gradedReference = oklabToReferenceLinear(gradedLab);
    return max((uOklabReferenceToAcesCg * vec4(gradedReference, 1.0)).rgb, vec3(0.0));
}
```

The matrices above follow GLSL column construction semantics. In the existing monitoring branch, replace its ACEScg round-trip block with:

```glsl
vec3 acesCg = neoDisplayLinearToAcesCg(linear, hlgMonitor || pqMonitor);
acesCg = applyNeoGamutOkLchPipeline(acesCg, contrast, saturation);
linear = neoAcesCgToDisplayLinear(acesCg, hlgMonitor || pqMonitor);
```

In the truthful-delivery branch, replace its ACEScg round-trip block with:

```glsl
vec3 acesCg = neoDisplayLinearToAcesCg(linear, hlgDelivery || pqDelivery);
acesCg = applyNeoGamutOkLchPipeline(acesCg, contrast, saturation);
linear = neoAcesCgToDisplayLinear(acesCg, hlgDelivery || pqDelivery);
```

The function therefore receives ACEScg D60 as the current shader declares, traverses the composite NeoGamut/CAT16/D65-reference matrices, and returns ACEScg for existing transport encoding. Where `ndr10PlusMonitor`, `nivukxVisionMonitor`, `ndr10PlusDelivery`, or `nivukxVisionDelivery` currently calls `neoHdrPerceptualToneMap`, use `applyNeoGamutOkLchPipeline` instead when `uNeoModeParams.x > 0.5`; do not execute both shoulders for the same pixel. Retain the current static path only when Neo color-science payloads are disabled.

- [ ] **Step 5: Match the shader NLogs toe to the Kotlin C1 curve**

Within existing `encodeNLogFamily`, retain its mode parameters and gray calibration, and replace its toe construction with:

```glsl
float toeHeight = toeCut * toeSlope;
float grayLog = log(1.0 + logDensity * (N_LOG_SCENE_GRAY_18 - toeCut));
float logScale = (N_LOG_GRAY_CODE_18 - toeHeight) / grayLog;
float logSlopeAtToe = logScale * logDensity;
float toeQuadratic = (logSlopeAtToe * toeCut - toeHeight) / (toeCut * toeCut);
float toeLinear = (2.0 * toeHeight - logSlopeAtToe * toeCut) / toeCut;
vec3 toePart = vec3(toeQuadratic) * x * x + vec3(toeLinear) * x;
```

Keep the existing logarithmic body and variant selection constants so the encoder and CPU core use matching variants.

- [ ] **Step 6: Run shader contract and color/log tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.rendering.VulkanPreviewShaderContractTest" --tests "com.neoproc.neoproccamx.core.NeoLogCurveV3ContractTest" --tests "com.neoproc.neoproccamx.color.NeoGamutCat16MathTest" --no-configuration-cache
```

Expected: PASS. This verifies source integration and CPU math; actual shader compilation is required next.

### Task 8: Regenerate SPIR-V And Build The Integrated Android Target

**Files:**

- Modify: `app/src/main/cpp/generated/ycbcr_preview.frag.spv`
- Modify: generated shader header located by the symbol search command below.

- [ ] **Step 1: Confirm the embedded shader header consumed by the native bridge**

Run:

```powershell
rg -n "kYcbcrPreviewFragSpv|ycbcr_preview_frag_spv.h" app/src/main/cpp/vulkan_bridge.cpp app/src/main/cpp/generated/ycbcr_preview_frag_spv.h
```

Expected: output shows `vulkan_bridge.cpp` includes `generated/ycbcr_preview_frag_spv.h` and the header defines both expected symbols.

- [ ] **Step 2: Compile the updated fragment shader**

Run:

```powershell
$glslc = Join-Path $env:LOCALAPPDATA 'Android\Sdk\ndk\29.0.14206865\shader-tools\windows-x86_64\glslc.exe'
Test-Path $glslc
& $glslc --target-env=vulkan1.1 -fshader-stage=frag app/src/main/cpp/shaders/vulkan/ycbcr_preview.frag -o app/src/main/cpp/generated/ycbcr_preview.frag.spv
```

Expected: `Test-Path` returns `True`; `glslc` exits `0` with no syntax error.

- [ ] **Step 3: Regenerate the embedded SPIR-V header with the existing symbols**

```powershell
$spv = [System.IO.File]::ReadAllBytes((Resolve-Path 'app/src/main/cpp/generated/ycbcr_preview.frag.spv'))
if (($spv.Length % 4) -ne 0) { throw 'SPIR-V byte length is not word aligned.' }
$wordCount = $spv.Length / 4
$words = [System.Collections.Generic.List[string]]::new()
for ($i = 0; $i -lt $spv.Length; $i += 4) {
    $word = [BitConverter]::ToUInt32($spv, $i)
    $words.Add(('0x{0:x8}u' -f $word))
}
$body = $words -join ', '
$headerPath = 'app/src/main/cpp/generated/ycbcr_preview_frag_spv.h'
$generated = @"
#pragma once
#include <cstddef>
#include <cstdint>
static constexpr uint32_t kYcbcrPreviewFragSpv[] = { $body };
static constexpr size_t kYcbcrPreviewFragSpvCount = sizeof(kYcbcrPreviewFragSpv) / sizeof(kYcbcrPreviewFragSpv[0]);
"@
[System.IO.File]::WriteAllText((Join-Path (Get-Location) $headerPath), $generated, [System.Text.UTF8Encoding]::new($false))
```

Expected: the header continues to declare exactly `kYcbcrPreviewFragSpv` and `kYcbcrPreviewFragSpvCount`; it changes only as mechanically derived output of the compiled shader.

- [ ] **Step 4: Run all relevant unit tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-configuration-cache
```

Expected: BUILD SUCCESSFUL and all app unit tests pass.

- [ ] **Step 5: Compile native/JNI integration and APK**

Run:

```powershell
.\gradlew.bat :app:assembleDebug --rerun-tasks --no-build-cache --no-configuration-cache
```

Expected: BUILD SUCCESSFUL. Native compilation verifies that JNI parameter order, uniform struct layout usage, and shader embedding are syntactically valid.

- [ ] **Step 6: Inspect integrated diffs without discarding pre-existing changes**

Run:

```powershell
git diff --stat -- app/src/main app/src/test core-math/src/main
git diff -- app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt app/src/main/java/com/neoproc/neoproccamx/color/ColorScience.kt core-math/src/main/kotlin/com/neoproc/neoproccamx/core/NeoLogCurve.kt app/src/main/cpp/shaders/vulkan/ycbcr_preview.frag
```

Expected: the review shows ownership gating, CAT16/OkLCh data flow, C1 toe, and shader integration while preserving earlier detail/noise, LUT, mode, and NPV10 code.

### Task 9: Deploy And Validate Device Truth

**Files:**

- Build artifact: `app/build/outputs/apk/debug/app-debug.apk`
- Runtime evidence only: device logcat and recorded media.

- [ ] **Step 1: Install and launch the integrated build**

Run:

```powershell
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c
adb shell am force-stop com.neoproc.neoproccamx
adb shell monkey -p com.neoproc.neoproccamx -c android.intent.category.LAUNCHER 1
Start-Sleep -Seconds 5
```

Expected: APK installs and the camera UI launches on the connected handset.

- [ ] **Step 2: Confirm live preview no longer loses surface ownership**

Navigate through Video, Pro, NDR, and Log preview modes on-device, then run:

```powershell
adb logcat -d | Select-String -Pattern 'BufferQueueProducer: connect: already connected|vkCreateAndroidSurfaceKHR failed|NeoProcVulkan|visible preview'
```

Expected acceptance condition: no new `connect: already connected` or `vkCreateAndroidSurfaceKHR failed` during tested transitions. If either appears, report the failure and retain Camera2 direct-preview fallback; do not claim the preview fix validated.

- [ ] **Step 3: Record through the processed internal encoder path**

Start and stop a short recording in an enabled Neo processed mode, then run:

```powershell
adb logcat -d | Select-String -Pattern 'Internal hardware-import recording started|imported|encoder|fallback|bypass'
```

Expected acceptance condition: log contains `Internal hardware-import recording started; visible preview unchanged` and positive imported-frame validation, with no fallback/bypass report for that captured file.

- [ ] **Step 4: Inspect output transport metadata**

Pull the newly recorded clip after obtaining its path from device media output logs or MediaStore query, then run:

```powershell
ffprobe -v error -show_entries stream=codec_name,profile,pix_fmt,color_space,color_transfer,color_primaries -show_entries format=bit_rate,duration -of default=noprint_wrappers=1 .\recorded-neo-sample.mp4
```

Expected: values match the selected delivery mode's configured transport. A log delivery and an HDR delivery intentionally do not share identical output metadata with SDR preview.

- [ ] **Step 5: Produce the verification report**

Record in the completion response:

```text
Tests: :app:testDebugUnitTest result
Build: :app:assembleDebug result
Device: adb install/launch result and handset serial
Preview ownership: whether BufferQueue/Vulkan surface failures occurred
Processed recording: imported-frame validation log evidence
Media metadata: ffprobe fields obtained, or the exact reason media inspection could not be performed
```

Do not describe visual output as noise-free, bit-perfect, or cinema-equivalent unless recorded media analysis supplies measured evidence for those claims.

## Integration Checkpoints

After Tasks 1-2:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.ui.RecordingStartSessionRefreshContractTest" --tests "com.neoproc.neoproccamx.rendering.VulkanVisibleSurfaceOwnershipPolicyTest" --no-configuration-cache
```

After Tasks 3-5:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.color.NeoGamutCat16MathTest" --tests "com.neoproc.neoproccamx.core.NeoLogCurveV3ContractTest" --tests "com.neoproc.neoproccamx.rendering.VulkanColorScienceUniformPolicyTest" --no-configuration-cache
```

After Tasks 6-8:

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-configuration-cache
.\gradlew.bat :app:assembleDebug --rerun-tasks --no-build-cache --no-configuration-cache
```

## Planned Coverage Review

- Visible-preview runtime failure: Tasks 1 and 9 explicitly prevent and check dual producer ownership.
- Processed recording validation while direct preview remains visible: Tasks 2 and 9 decouple selection while retaining imported-frame proof.
- Correct NeoGamut forward/inverse math and CAT16: Task 3 includes numerical identity and finite-white tests.
- Active profile ownership: Task 3 reruns `ColorSciencePreviewLutTest`, whose existing contract rejects selectable third-party branded color profiles and verifies NeoGamut fallback behavior.
- White-point correctness: Task 3 implements arbitrary CAT16 and validates Kelvin-derived whites for sensor-linear use; Tasks 5-7 use the actual ACEScg D60 intermediate and do not double-apply live white balance after Camera2 color correction.
- Hue-constant GPU grading: Tasks 5-7 carry and execute the matrix/HDR payload in the active Vulkan branch.
- NLogs compatibility: Task 4 preserves the existing shared gray code while meeting C1 toe continuity, and Task 7 matches shader transport math.
- JNI and std140 integrity: Tasks 6 and 8 update both sides atomically and compile native code.
- Existing filters, LUTs, modes, RAW, and NPV10 behavior: Task 7 modifies the insertion point without replacing current mode or enhancement branches; diff inspection in Task 8 verifies retention.
- Runtime quality claims: Task 9 limits completion statements to evidence observed on the connected device and extracted recording metadata.
