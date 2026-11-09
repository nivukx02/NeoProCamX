# Camera Pipeline Experimental Pro Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Harden the camera pipeline so Experimental Pro tries maximum exposed camera capability first, falls back safely, and reports exact hardware limits to logs and UI warnings.

**Architecture:** Add pure policy layers for pipeline diagnostics and fallback timing, then wire those policies into the existing Camera2 capability contract, ISP-bypass policy, session creation fallback, and ViewModel warning path. Keep Android/HAL probing in `CameraController`; keep fallback decisions and user messages in testable Kotlin policy files.

**Tech Stack:** Kotlin, Android Camera2, MediaCodec/MediaRecorder stream sizing, JUnit unit tests, Gradle Android plugin, adb device validation.

---

## Scope Check

This plan implements Phase 1 from `docs/superpowers/specs/2026-05-08-whole-app-high-end-upgrade-design.md`: camera pipeline, fallback, ISP-bypass truth, and diagnostics. It does not redesign HDR/noise/color, PRO controller UI, or package architecture beyond the files needed for this camera contract.

## File Structure

- `app/src/main/java/com/neoproc/neoproccamx/camera/CameraPipelineDiagnostics.kt`
  - New pure formatter for user-visible and log-visible camera pipeline warnings.
- `app/src/test/java/com/neoproc/neoproccamx/camera/CameraPipelineDiagnosticsTest.kt`
  - Tests exact fallback warning strings.
- `app/src/main/java/com/neoproc/neoproccamx/camera/ExperimentalCameraFallbackPolicy.kt`
  - New pure timing policy for hard failure, severe lag, FPS/encoder pressure, and thermal degradation.
- `app/src/test/java/com/neoproc/neoproccamx/camera/ExperimentalCameraFallbackPolicyTest.kt`
  - Tests immediate, 3 second, 10 second, and 30 second fallback behavior.
- `app/src/main/java/com/neoproc/neoproccamx/camera/CameraCapabilityContract.kt`
  - Extend stream plans with requested-size awareness and fallback warnings.
- `app/src/test/java/com/neoproc/neoproccamx/camera/CameraCapabilityContractTest.kt`
  - Add tests proving 8K requests warn when actual streams are 4K and do not warn when full sensor is exposed.
- `app/src/main/java/com/neoproc/neoproccamx/camera/ISPBypassPolicy.kt`
  - Add exact user warning and supported/total feature counts.
- `app/src/test/java/com/neoproc/neoproccamx/camera/ISPBypassRequestPlanPolicyTest.kt`
  - Add tests for complete vs partial ISP-bypass user warning behavior.
- `app/src/main/java/com/neoproc/neoproccamx/camera/CameraControllerHost.kt`
  - Add camera-pipeline warning callback.
- `app/src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt`
  - Emit diagnostics from session fallback and ISP-bypass fallback.
- `app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt`
  - Forward camera pipeline warnings to existing `hardwareWarning`.
- `app/src/test/java/com/neoproc/neoproccamx/ui/CameraRecordingSelectionCoordinatorTest.kt`
  - Keep existing RAW full-sensor regression test in focused test suite.

---

### Task 1: Add Camera Pipeline Diagnostic Formatter

**Files:**
- Create: `app/src/main/java/com/neoproc/neoproccamx/camera/CameraPipelineDiagnostics.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/camera/CameraPipelineDiagnosticsTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/neoproc/neoproccamx/camera/CameraPipelineDiagnosticsTest.kt`:

```kotlin
package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraPipelineDiagnosticsTest {

    @Test
    fun `readout fallback warning names requested and actual sizes`() {
        val warning = CameraPipelineDiagnostics.readoutFallbackWarning(
            requested = CameraStreamSize(8192, 6144),
            actual = CameraStreamSize(4096, 3072),
            requestedLabel = "8K full sensor",
            actualLabel = "stable RAW_SENSOR"
        )

        assertEquals(
            "8K full sensor requested, but camera/encoder exposed 4096x3072. Using stable RAW_SENSOR path.",
            warning
        )
    }

    @Test
    fun `readout fallback warning is absent when actual covers requested area`() {
        val warning = CameraPipelineDiagnostics.readoutFallbackWarning(
            requested = CameraStreamSize(4096, 3072),
            actual = CameraStreamSize(8192, 6144),
            requestedLabel = "RAW full sensor",
            actualLabel = "RAW_SENSOR"
        )

        assertNull(warning)
    }

    @Test
    fun `isp warning lists unsupported keys`() {
        val warning = CameraPipelineDiagnostics.ispBypassWarning(
            unsupportedReasons = listOf("DISTORTION_CORRECTION_MODE_OFF unavailable")
        )

        assertEquals(
            "Complete ISP bypass unavailable: DISTORTION_CORRECTION_MODE_OFF unavailable.",
            warning
        )
    }

    @Test
    fun `experimental fallback warning includes fallback reason`() {
        val warning = CameraPipelineDiagnostics.experimentalFallbackWarning(
            reason = "Camera session rejected hardware import surface"
        )

        assertEquals(
            "Experimental Pro path unstable. Recording continued using stable fallback: Camera session rejected hardware import surface.",
            warning
        )
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.camera.CameraPipelineDiagnosticsTest --no-daemon --console=plain
```

Expected: FAIL or compile error because `CameraPipelineDiagnostics` does not exist.

- [ ] **Step 3: Implement the formatter**

Create `app/src/main/java/com/neoproc/neoproccamx/camera/CameraPipelineDiagnostics.kt`:

```kotlin
package com.neoproc.neoproccamx.camera

internal object CameraPipelineDiagnostics {

    fun readoutFallbackWarning(
        requested: CameraStreamSize?,
        actual: CameraStreamSize?,
        requestedLabel: String,
        actualLabel: String
    ): String? {
        if (requested == null || actual == null) return null
        if (actual.area >= requested.area) return null
        return "$requestedLabel requested, but camera/encoder exposed ${actual.width}x${actual.height}. " +
            "Using $actualLabel path."
    }

    fun ispBypassWarning(unsupportedReasons: List<String>): String? {
        if (unsupportedReasons.isEmpty()) return null
        return "Complete ISP bypass unavailable: ${unsupportedReasons.joinToString(", ")}."
    }

    fun experimentalFallbackWarning(reason: String): String =
        "Experimental Pro path unstable. Recording continued using stable fallback: $reason."
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.camera.CameraPipelineDiagnosticsTest --no-daemon --console=plain
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/neoproc/neoproccamx/camera/CameraPipelineDiagnostics.kt app/src/test/java/com/neoproc/neoproccamx/camera/CameraPipelineDiagnosticsTest.kt
git commit -m "feat: add camera pipeline diagnostics"
```

---

### Task 2: Add Experimental Fallback Timing Policy

**Files:**
- Create: `app/src/main/java/com/neoproc/neoproccamx/camera/ExperimentalCameraFallbackPolicy.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/camera/ExperimentalCameraFallbackPolicyTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/neoproc/neoproccamx/camera/ExperimentalCameraFallbackPolicyTest.kt`:

```kotlin
package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperimentalCameraFallbackPolicyTest {

    @Test
    fun `hard failure falls back immediately`() {
        val decision = ExperimentalCameraFallbackPolicy.resolve(
            signal = ExperimentalCameraInstability.HARD_FAILURE,
            elapsedMs = 0L
        )

        assertTrue(decision.shouldFallback)
        assertEquals(0L, decision.requiredElapsedMs)
        assertEquals("Camera hard failure", decision.reason)
    }

    @Test
    fun `severe lag waits three seconds`() {
        val early = ExperimentalCameraFallbackPolicy.resolve(
            signal = ExperimentalCameraInstability.SEVERE_LAG,
            elapsedMs = 2999L
        )
        val ready = ExperimentalCameraFallbackPolicy.resolve(
            signal = ExperimentalCameraInstability.SEVERE_LAG,
            elapsedMs = 3000L
        )

        assertFalse(early.shouldFallback)
        assertTrue(ready.shouldFallback)
        assertEquals(3000L, ready.requiredElapsedMs)
    }

    @Test
    fun `encoder pressure waits ten seconds`() {
        val decision = ExperimentalCameraFallbackPolicy.resolve(
            signal = ExperimentalCameraInstability.ENCODER_PRESSURE,
            elapsedMs = 10000L
        )

        assertTrue(decision.shouldFallback)
        assertEquals(10000L, decision.requiredElapsedMs)
    }

    @Test
    fun `thermal degradation waits thirty seconds`() {
        val decision = ExperimentalCameraFallbackPolicy.resolve(
            signal = ExperimentalCameraInstability.THERMAL_DEGRADATION,
            elapsedMs = 30000L
        )

        assertTrue(decision.shouldFallback)
        assertEquals(30000L, decision.requiredElapsedMs)
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.camera.ExperimentalCameraFallbackPolicyTest --no-daemon --console=plain
```

Expected: FAIL or compile error because `ExperimentalCameraFallbackPolicy` does not exist.

- [ ] **Step 3: Implement the timing policy**

Create `app/src/main/java/com/neoproc/neoproccamx/camera/ExperimentalCameraFallbackPolicy.kt`:

```kotlin
package com.neoproc.neoproccamx.camera

internal enum class ExperimentalCameraInstability {
    HARD_FAILURE,
    SESSION_INSTABILITY,
    SEVERE_LAG,
    UNSTABLE_FPS,
    ENCODER_PRESSURE,
    THERMAL_DEGRADATION
}

internal data class ExperimentalCameraFallbackDecision(
    val shouldFallback: Boolean,
    val requiredElapsedMs: Long,
    val reason: String
)

internal object ExperimentalCameraFallbackPolicy {
    fun resolve(
        signal: ExperimentalCameraInstability,
        elapsedMs: Long
    ): ExperimentalCameraFallbackDecision {
        val required = when (signal) {
            ExperimentalCameraInstability.HARD_FAILURE -> 0L
            ExperimentalCameraInstability.SESSION_INSTABILITY -> 3000L
            ExperimentalCameraInstability.SEVERE_LAG -> 3000L
            ExperimentalCameraInstability.UNSTABLE_FPS -> 10000L
            ExperimentalCameraInstability.ENCODER_PRESSURE -> 10000L
            ExperimentalCameraInstability.THERMAL_DEGRADATION -> 30000L
        }
        return ExperimentalCameraFallbackDecision(
            shouldFallback = elapsedMs >= required,
            requiredElapsedMs = required,
            reason = signal.reasonLabel()
        )
    }

    private fun ExperimentalCameraInstability.reasonLabel(): String = when (this) {
        ExperimentalCameraInstability.HARD_FAILURE -> "Camera hard failure"
        ExperimentalCameraInstability.SESSION_INSTABILITY -> "Camera session instability"
        ExperimentalCameraInstability.SEVERE_LAG -> "Severe preview lag"
        ExperimentalCameraInstability.UNSTABLE_FPS -> "Unstable frame rate"
        ExperimentalCameraInstability.ENCODER_PRESSURE -> "Encoder pressure"
        ExperimentalCameraInstability.THERMAL_DEGRADATION -> "Thermal degradation"
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.camera.ExperimentalCameraFallbackPolicyTest --no-daemon --console=plain
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/neoproc/neoproccamx/camera/ExperimentalCameraFallbackPolicy.kt app/src/test/java/com/neoproc/neoproccamx/camera/ExperimentalCameraFallbackPolicyTest.kt
git commit -m "feat: add experimental camera fallback policy"
```

---

### Task 3: Add Requested-Size Fallback Warnings To Stream Plans

**Files:**
- Modify: `app/src/main/java/com/neoproc/neoproccamx/camera/CameraCapabilityContract.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/camera/CameraCapabilityContractTest.kt`

- [ ] **Step 1: Add failing tests**

Append these tests to `CameraCapabilityContractTest`:

```kotlin
    @Test
    fun `raw request warns when requested full sensor is larger than exposed raw path`() {
        val plan = CameraStreamPlanResolver.resolve(
            request = CameraStreamPlanRequest(
                cinemaMode = CinemaVideoMode.RAW,
                requestedReadoutSize = CameraStreamSize(8192, 6144)
            ),
            capability = baseCapability(
                pixelArraySize = CameraStreamSize(8192, 6144),
                rawOutputSizes = listOf(CameraStreamSize(4096, 3072)),
                yuvOutputSizes = listOf(CameraStreamSize(4096, 3072)),
                encoderOutputSizes = listOf(CameraStreamSize(4096, 3072))
            )
        )

        assertEquals(CameraReadoutTier.SUPPORTED_RAW, plan.readoutTier)
        assertEquals(
            "8K full sensor requested, but camera/encoder exposed 4096x3072. Using stable RAW_SENSOR path.",
            plan.fallbackWarning
        )
        assertTrue(plan.diagnostics.any { it.contains("8192x6144") })
    }

    @Test
    fun `raw request does not warn when exposed raw path covers requested full sensor`() {
        val plan = CameraStreamPlanResolver.resolve(
            request = CameraStreamPlanRequest(
                cinemaMode = CinemaVideoMode.RAW,
                requestedReadoutSize = CameraStreamSize(8192, 6144)
            ),
            capability = baseCapability(
                pixelArraySize = CameraStreamSize(8192, 6144),
                rawOutputSizes = listOf(CameraStreamSize(8192, 6144)),
                yuvOutputSizes = listOf(CameraStreamSize(4096, 3072)),
                encoderOutputSizes = listOf(CameraStreamSize(4096, 3072))
            )
        )

        assertEquals(CameraReadoutTier.FULL_SENSOR_RAW, plan.readoutTier)
        assertEquals(null, plan.fallbackWarning)
    }
```

- [ ] **Step 2: Run the focused test to verify it fails**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.camera.CameraCapabilityContractTest --no-daemon --console=plain
```

Expected: FAIL because `requestedReadoutSize` and `fallbackWarning` do not exist.

- [ ] **Step 3: Extend the stream plan model**

In `CameraCapabilityContract.kt`, change `CameraStreamPlanRequest` and `CameraStreamPlan` to include:

```kotlin
internal data class CameraStreamPlanRequest(
    val cinemaMode: CinemaVideoMode,
    val preferredEncoderSize: CameraStreamSize? = null,
    val requestedReadoutSize: CameraStreamSize? = preferredEncoderSize,
    val preferFullSensorReadout: Boolean = true
)
```

```kotlin
internal data class CameraStreamPlan(
    val readoutTier: CameraReadoutTier,
    val ispBypassLevel: IspBypassLevel,
    val primaryReadoutSize: CameraStreamSize?,
    val rawSize: CameraStreamSize? = null,
    val yuvAnalysisSize: CameraStreamSize? = null,
    val jpegSize: CameraStreamSize? = null,
    val encoderSize: CameraStreamSize? = null,
    val truthfulFullSensorReadout: Boolean = false,
    val fallbackChain: List<String> = emptyList(),
    val diagnostics: List<String> = emptyList(),
    val fallbackWarning: String? = null
)
```

- [ ] **Step 4: Add fallback warning helper and use it in every non-unsupported plan**

Add this private helper inside `CameraStreamPlanResolver`:

```kotlin
    private fun fallbackWarningFor(
        request: CameraStreamPlanRequest,
        actual: CameraStreamSize?,
        actualLabel: String
    ): String? =
        CameraPipelineDiagnostics.readoutFallbackWarning(
            requested = request.requestedReadoutSize,
            actual = actual,
            requestedLabel = if (request.cinemaMode == CinemaVideoMode.RAW) {
                "8K full sensor"
            } else {
                "Requested recording readout"
            },
            actualLabel = actualLabel
        )
```

Update each `CameraStreamPlan(...)` creation in `resolveRawRequest` and `resolveEncoderRequest` so it includes `fallbackWarning = fallbackWarningFor(...)` and appends the warning to diagnostics:

```kotlin
                fallbackWarning = fallbackWarningFor(request, rawSize, "stable RAW_SENSOR"),
                diagnostics = listOfNotNull(
                    when {
                        fullSensorRaw && trueRawBypass ->
                            "RAW_SENSOR full-sensor readout available with true sensor path"
                        fullSensorRaw ->
                            "RAW_SENSOR full-sensor readout available; ISP bypass request-key coverage is limited"
                        else ->
                            "RAW_SENSOR available but full-sensor coverage is limited"
                    },
                    fallbackWarningFor(request, rawSize, "stable RAW_SENSOR")
                )
```

For encoder plans, change `resolveEncoderRequest` signature to accept `request: CameraStreamPlanRequest` and call it from `resolve(...)`:

```kotlin
            else -> resolveEncoderRequest(
                request = request,
                capability = capability,
                yuvSize = yuvSize,
                jpegSize = jpegSize,
                encoderSize = encoderSize
            )
```

Use labels:

```kotlin
fallbackWarningFor(request, encoderSize, "stable encoder")
fallbackWarningFor(request, yuvSize, "stable YUV_420_888")
```

- [ ] **Step 5: Run the focused test to verify it passes**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.camera.CameraCapabilityContractTest --no-daemon --console=plain
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/com/neoproc/neoproccamx/camera/CameraCapabilityContract.kt app/src/test/java/com/neoproc/neoproccamx/camera/CameraCapabilityContractTest.kt
git commit -m "feat: report camera stream fallback warnings"
```

---

### Task 4: Add ISP-Bypass User Warning Contract

**Files:**
- Modify: `app/src/main/java/com/neoproc/neoproccamx/camera/ISPBypassPolicy.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/camera/ISPBypassRequestPlanPolicyTest.kt`

- [ ] **Step 1: Add failing tests**

Append these tests to `ISPBypassRequestPlanPolicyTest`:

```kotlin
    @Test
    fun `complete support has no user warning`() {
        val plan = ISPBypassRequestPlanPolicy.resolve(
            IspBypassAvailability(
                tonemapModes = listOf(CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE),
                edgeModes = listOf(CaptureRequest.EDGE_MODE_OFF),
                noiseReductionModes = listOf(CaptureRequest.NOISE_REDUCTION_MODE_OFF),
                hotPixelModes = listOf(CaptureRequest.HOT_PIXEL_MODE_OFF),
                shadingModes = listOf(CaptureRequest.SHADING_MODE_OFF),
                colorAberrationModes = listOf(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF),
                distortionCorrectionModes = listOf(CaptureRequest.DISTORTION_CORRECTION_MODE_OFF)
            )
        )

        assertEquals(7, plan.supportedFeatureCount)
        assertEquals(7, plan.totalFeatureCount)
        assertEquals(null, plan.userWarning)
    }

    @Test
    fun `partial support exposes exact user warning`() {
        val plan = ISPBypassRequestPlanPolicy.resolve(
            IspBypassAvailability(
                tonemapModes = listOf(CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE),
                edgeModes = listOf(CaptureRequest.EDGE_MODE_OFF),
                noiseReductionModes = listOf(CaptureRequest.NOISE_REDUCTION_MODE_OFF),
                hotPixelModes = listOf(CaptureRequest.HOT_PIXEL_MODE_OFF),
                shadingModes = listOf(CaptureRequest.SHADING_MODE_OFF),
                colorAberrationModes = listOf(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF),
                distortionCorrectionModes = emptyList()
            )
        )

        assertEquals(6, plan.supportedFeatureCount)
        assertEquals(7, plan.totalFeatureCount)
        assertEquals(
            "Complete ISP bypass unavailable: DISTORTION_CORRECTION_MODE_OFF unavailable.",
            plan.userWarning
        )
    }
```

- [ ] **Step 2: Run the focused test to verify it fails**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.camera.ISPBypassRequestPlanPolicyTest --no-daemon --console=plain
```

Expected: FAIL because the new plan properties do not exist.

- [ ] **Step 3: Implement warning properties**

In `IspBypassRequestPlan`, add:

```kotlin
    val supportedFeatureCount: Int = supportedFeatures.size
    val totalFeatureCount: Int = IspBypassFeature.values().size
    val userWarning: String? = CameraPipelineDiagnostics.ispBypassWarning(unsupportedReasons)
```

Keep `summary` factual:

```kotlin
    val summary: String = "complete=$complete supported=$supportedFeatureCount/$totalFeatureCount " +
        "features=${supportedFeatures.joinToString()} unsupported=${unsupportedReasons.joinToString("; ")}"
```

Add this method to `ISPBypassPolicy`:

```kotlin
    fun userWarning(): String? = requestPlan.userWarning
```

- [ ] **Step 4: Run the focused test to verify it passes**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.camera.ISPBypassRequestPlanPolicyTest --no-daemon --console=plain
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/neoproc/neoproccamx/camera/ISPBypassPolicy.kt app/src/test/java/com/neoproc/neoproccamx/camera/ISPBypassRequestPlanPolicyTest.kt
git commit -m "feat: expose truthful isp bypass warnings"
```

---

### Task 5: Wire Camera Pipeline Warnings To Controller And ViewModel

**Files:**
- Modify: `app/src/main/java/com/neoproc/neoproccamx/camera/CameraControllerHost.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt`

- [ ] **Step 1: Add callback surface to host and controller**

In `CameraControllerHost.kt`, under callback properties, add:

```kotlin
    var onCameraPipelineWarning: ((String) -> Unit)?
```

In `CameraController.kt`, near the existing callback properties, add:

```kotlin
    override var onCameraPipelineWarning: ((String) -> Unit)? = null
```

Add this helper in `CameraController.kt` near `toLogLine()`:

```kotlin
    private fun emitCameraPipelineWarning(message: String?) {
        if (message.isNullOrBlank()) return
        Log.w(TAG, message)
        onCameraPipelineWarning?.invoke(message)
    }
```

- [ ] **Step 2: Emit ISP-bypass warnings when RAW path applies request keys**

In `CameraController.kt`, inside the RAW ISP-bypass block after the existing log for `modifiedKeys`, add:

```kotlin
                    emitCameraPipelineWarning(bypassPolicy.userWarning())
```

In the branch that logs unsupported `TONEMAP_MODE_CONTRAST_CURVE`, replace the literal log-only behavior with:

```kotlin
                emitCameraPipelineWarning(
                    "Complete ISP bypass unavailable: TONEMAP_MODE_CONTRAST_CURVE unavailable."
                )
```

- [ ] **Step 3: Emit experimental session fallback warnings**

In `CameraController.kt` inside `createSession(...).onConfigureFailed`, before retrying without hints, emit:

```kotlin
                        emitCameraPipelineWarning(
                            CameraPipelineDiagnostics.experimentalFallbackWarning(
                                "Camera session rejected stream use-case hints"
                            )
                        )
```

Before retrying without hardware import, emit:

```kotlin
                        emitCameraPipelineWarning(
                            CameraPipelineDiagnostics.experimentalFallbackWarning(
                                "Camera session rejected hardware import surface"
                            )
                        )
```

Before the final `Log.e(TAG, "Camera session configuration failed")`, emit:

```kotlin
                    emitCameraPipelineWarning(
                        CameraPipelineDiagnostics.experimentalFallbackWarning(
                            "Camera session configuration failed"
                        )
                    )
```

- [ ] **Step 4: Forward warnings to existing UI hardware warning state**

In `CameraViewModel.kt`, near existing callback wiring:

```kotlin
        camera.onYuvFrame = { reader -> pipeline.enqueue(reader) }
        camera.onHardwareFrame = { reader -> pipeline.enqueueHardware(reader) }
```

Add:

```kotlin
        camera.onCameraPipelineWarning = { message ->
            runOnMainIfNeeded { setHardwareWarning(message) }
        }
```

- [ ] **Step 5: Run compile to catch interface wiring errors**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/com/neoproc/neoproccamx/camera/CameraControllerHost.kt app/src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt
git commit -m "feat: surface camera pipeline fallback warnings"
```

---

### Task 6: Pass Requested Readout Size Into Camera Stream Plans

**Files:**
- Modify: `app/src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt`

- [ ] **Step 1: Update plan construction**

In `rebuildCameraStreamPlans`, replace the `CameraStreamPlanRequest(cinemaMode = mode)` call inside the `associateWith` block with local sizing decisions before `CameraStreamPlanResolver.resolve`:

```kotlin
        cameraStreamPlans = CinemaVideoMode.values().associateWith { mode ->
            val preferredEncoderSize = inventory.encoderOutputSizes.maxWithOrNull(
                compareBy<CameraStreamSize> { it.area }.thenBy { it.width }.thenBy { it.height }
            )
            val requestedReadoutSize = when (mode) {
                CinemaVideoMode.RAW,
                CinemaVideoMode.EXPERIMENTAL_PRO -> inventory.pixelArraySize
                else -> preferredEncoderSize
            }
            CameraStreamPlanResolver.resolve(
                request = CameraStreamPlanRequest(
                    cinemaMode = mode,
                    preferredEncoderSize = preferredEncoderSize,
                    requestedReadoutSize = requestedReadoutSize
                ),
                capability = inventory
            )
        }
```

- [ ] **Step 2: Include fallback warning in camera contract log**

In `CameraStreamPlan.toLogLine()`, append:

```kotlin
        " warning=${fallbackWarning ?: "none"}"
```

The full return expression must still include tier, ISP level, primary, raw, yuv, encoder, fullSensor, fallback, and diagnostics.

- [ ] **Step 3: Emit RAW fallback warning when RAW mode is enabled**

In `setRawVideoCaptureEnabled(enabled: Boolean)`, replace the body with:

```kotlin
    override fun setRawVideoCaptureEnabled(enabled: Boolean) {
        rawVideoCaptureEnabled = enabled
        if (enabled) {
            emitCameraPipelineWarning(cameraStreamPlans[CinemaVideoMode.RAW]?.fallbackWarning)
        }
    }
```

- [ ] **Step 4: Run compile**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt
git commit -m "feat: connect requested readout diagnostics"
```

---

### Task 7: Focused Regression Tests

**Files:**
- Existing tests from previous tasks

- [ ] **Step 1: Run camera policy tests**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.camera.CameraPipelineDiagnosticsTest --tests com.neoproc.neoproccamx.camera.ExperimentalCameraFallbackPolicyTest --tests com.neoproc.neoproccamx.camera.CameraCapabilityContractTest --tests com.neoproc.neoproccamx.camera.ISPBypassRequestPlanPolicyTest --no-daemon --console=plain
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run recording selection regression test**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.ui.CameraRecordingSelectionCoordinatorTest --no-daemon --console=plain
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run compile and assemble**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:compileDebugKotlin --no-daemon --console=plain
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain
```

Expected: both commands finish with BUILD SUCCESSFUL.

- [ ] **Step 4: Commit validation-only state if no files changed**

If `git status --short` shows no files from this task changed, do not create an empty commit. If a test expectation was corrected during this task, commit only that correction:

```powershell
git add app/src/test/java/com/neoproc/neoproccamx/camera app/src/test/java/com/neoproc/neoproccamx/ui/CameraRecordingSelectionCoordinatorTest.kt
git commit -m "test: cover experimental camera pipeline fallback"
```

---

### Task 8: Device Deployment And Camera Pipeline Validation

**Files:**
- Existing debug APK: `app/build/outputs/apk/debug/app-debug.apk`

- [ ] **Step 1: Verify connected target**

Run:

```powershell
& 'C:\Users\acer\AppData\Local\Android\Sdk\platform-tools\adb.exe' -s 10BF8M2NDX003P3 shell getprop ro.product.model
& 'C:\Users\acer\AppData\Local\Android\Sdk\platform-tools\adb.exe' -s 10BF8M2NDX003P3 shell getprop ro.build.version.sdk
```

Expected: device responds with model and SDK.

- [ ] **Step 2: Install APK and clear logs**

Run:

```powershell
& 'C:\Users\acer\AppData\Local\Android\Sdk\platform-tools\adb.exe' -s 10BF8M2NDX003P3 install -r 'app\build\outputs\apk\debug\app-debug.apk'
& 'C:\Users\acer\AppData\Local\Android\Sdk\platform-tools\adb.exe' -s 10BF8M2NDX003P3 logcat -c
```

Expected: install prints `Success`.

- [ ] **Step 3: Run RAW/full-sensor debug validation**

Run:

```powershell
& 'C:\Users\acer\AppData\Local\Android\Sdk\platform-tools\adb.exe' -s 10BF8M2NDX003P3 shell am force-stop com.neoproc.neoproccamx
& 'C:\Users\acer\AppData\Local\Android\Sdk\platform-tools\adb.exe' -s 10BF8M2NDX003P3 shell am start -W -n com.neoproc.neoproccamx/com.neoproc.neoproccamx.ui.MainActivity -a com.neoproc.neoproccamx.DEBUG_COMMAND --es debug_preset RAW_MODE --es debug_action record_clip --ei debug_duration_ms 2500 --es debug_video_mode RAW --ez debug_raw true --es debug_validation_label full_sensor_raw_validation
Start-Sleep -Seconds 6
```

Expected: activity starts and recording finishes without crash.

- [ ] **Step 4: Capture camera pipeline logs**

Run:

```powershell
& 'C:\Users\acer\AppData\Local\Android\Sdk\platform-tools\adb.exe' -s 10BF8M2NDX003P3 logcat -d -v time CameraController:D StreamUseCasePolicy:D RecordingManager:D CameraRecordingSelectionCoordinator:D CameraViewModel:D *:S
```

Expected log evidence:

- `Camera contract RAW` includes `warning=` with either `none` or a factual fallback warning.
- If the device exposes only 4096x3072 for RAW/YUV/recording, the warning says the requested full-sensor path fell back to 4096x3072.
- `ISP bypass request applied` includes supported/total key counts and unsupported reasons.
- Recording starts and saves through fallback if the experimental path is rejected.

- [ ] **Step 5: Check crash buffer**

Run:

```powershell
& 'C:\Users\acer\AppData\Local\Android\Sdk\platform-tools\adb.exe' -s 10BF8M2NDX003P3 logcat -d -b crash
```

Expected: no crash entries for `com.neoproc.neoproccamx`.

- [ ] **Step 6: Commit validation notes**

If implementation code changed after validation, commit those changes. If only logs were inspected, do not commit generated logs unless the project already stores validation artifacts for this phase.

---

## Phase 1 Completion Criteria

Phase 1 is complete only when:

- Focused camera policy tests pass.
- `CameraRecordingSelectionCoordinatorTest` passes.
- `:app:compileDebugKotlin` passes.
- `:app:assembleDebug` passes.
- APK installs on device `10BF8M2NDX003P3`.
- RAW/full-sensor debug validation records or falls back cleanly.
- Crash buffer is clean.
- Logs show exact requested path, actual path, ISP-bypass key coverage, and fallback reason.
- User-visible warning path is wired through `CameraUiState.hardwareWarning`.
