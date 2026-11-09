# Camera2 Session Graph Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a testable Camera2 session graph parity policy and wire recording prearm/start/stop so processed preview and recording use the same intended Camera2 graph while RAW keeps its own RAW_SENSOR path.

**Architecture:** Introduce `Camera2SessionGraphParityPolicy` in the UI layer because it consumes `VideoPipelineContract` and `RecordingPipelineParityPlan`. CameraViewModel will call the policy before touching CameraController session setters, so preview size, encoder size, analysis size, readout override, record-surface attachment, and refresh action come from one contract.

**Tech Stack:** Kotlin, Android Camera2, Android `Size`, JUnit4 unit tests, existing Gradle `:app:testDebugUnitTest`, `:app:assembleDebug`, and `:app:lintDebug`.

---

## File Structure

- Create: `app/src/main/java/com/neoproc/neoproccamx/ui/Camera2SessionGraphParityPolicy.kt`
  - Owns the pure decision for Camera2 graph kind, preview/encoder/analysis sizes, readout override, record-surface attachment, preview-continuity target, refresh action, and session signature.
- Create: `app/src/test/java/com/neoproc/neoproccamx/ui/Camera2SessionGraphParityPolicyTest.kt`
  - Verifies processed SDR/HDR/LOG parity, RAW separation, preview/encoder size independence, and refresh actions.
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt`
  - Adds helpers to resolve/log the session graph parity plan.
  - Uses the plan in prearm and recording start paths before calling CameraController setters.
  - Keeps processed recording readout override null and uses preview-continuity target updates when the encoder surface is already attached.
- Modify: `app/src/test/java/com/neoproc/neoproccamx/ui/RecordingStartSessionRefreshContractTest.kt`
  - Locks the new policy boundary into the source-level recording-start contract.

---

### Task 1: Add Failing Camera2 Session Graph Policy Tests

**Files:**
- Create: `app/src/test/java/com/neoproc/neoproccamx/ui/Camera2SessionGraphParityPolicyTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/java/com/neoproc/neoproccamx/ui/Camera2SessionGraphParityPolicyTest.kt`:

```kotlin
package com.neoproc.neoproccamx.ui

import android.util.Size
import com.neoproc.neoproccamx.recording.CinemaVideoMode
import com.neoproc.neoproccamx.recording.GpuRecordingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Camera2SessionGraphParityPolicyTest {

    @Test
    fun `processed recording keeps preview size independent from encoder size`() {
        val plan = resolvePlan(
            cinemaMode = CinemaVideoMode.PRO,
            encoderSize = Size(7680, 4320),
            proposedReadoutOverrideSize = Size(4032, 3024),
            recordSurfaceAttachedToSession = true,
            stage = Camera2SessionGraphStage.STARTING
        )

        assertEquals(Camera2SessionGraphKind.PROCESSED_PREVIEW_ENCODER_PARITY, plan.graphKind)
        assertEquals(VideoDeliveryKind.SDR, plan.deliveryKind)
        assertEquals(Size(1920, 1080), plan.previewSize)
        assertEquals(Size(7680, 4320), plan.encoderSize)
        assertNull(plan.readoutOverrideSize)
        assertFalse(plan.rawSensorOwnsReadout)
        assertTrue(plan.attachRecordSurfaceToSession)
        assertTrue(plan.enablePreviewContinuityRecordSurface)
        assertEquals(Camera2SessionRefreshAction.REPEATING_REQUEST_ONLY, plan.refreshAction)
    }

    @Test
    fun `processed recording without preattached encoder surface requests one session rebuild`() {
        val plan = resolvePlan(
            cinemaMode = CinemaVideoMode.PRO,
            recordSurfaceAttachedToSession = false,
            stage = Camera2SessionGraphStage.STARTING
        )

        assertEquals(Camera2SessionGraphKind.PROCESSED_PREVIEW_ENCODER_PARITY, plan.graphKind)
        assertTrue(plan.attachRecordSurfaceToSession)
        assertEquals(Camera2SessionRefreshAction.REBUILD_SESSION, plan.refreshAction)
    }

    @Test
    fun `prearmed processed graph and recording start share one session signature`() {
        val prearmed = resolvePlan(
            cinemaMode = CinemaVideoMode.PRO,
            recordSurfaceAttachedToSession = true,
            stage = Camera2SessionGraphStage.PREARMED
        )
        val starting = resolvePlan(
            cinemaMode = CinemaVideoMode.PRO,
            recordSurfaceAttachedToSession = true,
            previousSessionSignature = prearmed.sessionSignature,
            stage = Camera2SessionGraphStage.STARTING
        )

        assertEquals(prearmed.sessionSignature, starting.sessionSignature)
        assertEquals(Camera2SessionRefreshAction.REPEATING_REQUEST_ONLY, starting.refreshAction)
    }

    @Test
    fun `hdr mode change requests session rebuild for dynamic range output configuration`() {
        val sdr = resolvePlan(
            cinemaMode = CinemaVideoMode.PRO,
            requestedHdrMode = "OFF",
            encoderSurfaceAvailable = false,
            stage = Camera2SessionGraphStage.IDLE
        )
        val hdr = resolvePlan(
            cinemaMode = CinemaVideoMode.PRO,
            requestedHdrMode = "HLG",
            tenBitRequested = true,
            encoderSurfaceAvailable = false,
            previousSessionSignature = sdr.sessionSignature,
            stage = Camera2SessionGraphStage.IDLE
        )

        assertEquals(VideoDeliveryKind.HDR, hdr.deliveryKind)
        assertEquals(Camera2SessionRefreshAction.REBUILD_SESSION, hdr.refreshAction)
    }

    @Test
    fun `log processed recording stays out of hdr transport`() {
        val plan = resolvePlan(
            cinemaMode = CinemaVideoMode.LOG,
            logEnabled = true,
            requestedHdrMode = "OFF",
            tenBitRequested = true,
            recordSurfaceAttachedToSession = true,
            stage = Camera2SessionGraphStage.STARTING
        )

        assertEquals(VideoDeliveryKind.LOG, plan.deliveryKind)
        assertEquals(Camera2SessionGraphKind.PROCESSED_PREVIEW_ENCODER_PARITY, plan.graphKind)
        assertTrue(plan.sessionSignature.contains("LOG"))
        assertTrue(plan.sessionSignature.contains("OFF"))
        assertFalse(plan.sessionSignature.contains("HDR10"))
        assertEquals(Camera2SessionRefreshAction.REPEATING_REQUEST_ONLY, plan.refreshAction)
    }

    @Test
    fun `raw recording owns raw sensor readout and rebuilds readers`() {
        val plan = resolvePlan(
            cinemaMode = CinemaVideoMode.RAW,
            rawVideoActive = true,
            proposedReadoutOverrideSize = Size(4032, 3024),
            recordSurfaceAttachedToSession = true,
            stage = Camera2SessionGraphStage.STARTING
        )

        assertEquals(Camera2SessionGraphKind.RAW_SENSOR_ORIGINAL, plan.graphKind)
        assertEquals(VideoDeliveryKind.RAW, plan.deliveryKind)
        assertEquals(Size(4032, 3024), plan.readoutOverrideSize)
        assertTrue(plan.rawSensorOwnsReadout)
        assertFalse(plan.enablePreviewContinuityRecordSurface)
        assertEquals(Camera2SessionRefreshAction.REBUILD_SESSION_AND_READERS, plan.refreshAction)
    }

    private fun resolvePlan(
        cinemaMode: CinemaVideoMode,
        rawVideoActive: Boolean = false,
        logEnabled: Boolean = false,
        requestedHdrMode: String = "OFF",
        tenBitRequested: Boolean = false,
        previewSize: Size = Size(1920, 1080),
        encoderSize: Size = Size(3840, 2160),
        proposedAnalysisSize: Size = Size(1920, 1080),
        proposedReadoutOverrideSize: Size? = null,
        recordSurfaceAttachedToSession: Boolean = false,
        encoderSurfaceAvailable: Boolean = true,
        previousSessionSignature: String? = null,
        stage: Camera2SessionGraphStage
    ): Camera2SessionGraphParityPlan {
        val graphContract = VideoPipelineGraphController.resolve(
            VideoPipelineGraphRequest(
                appMode = AppMode.VIDEO,
                cinemaMode = cinemaMode,
                rawVideoActive = rawVideoActive,
                logEnabled = logEnabled,
                requestedHdrMode = requestedHdrMode,
                preferredColorSpace = "Rec.709",
                tenBitRequested = tenBitRequested,
                previewWidth = previewSize.width,
                previewHeight = previewSize.height,
                encoderWidth = encoderSize.width,
                encoderHeight = encoderSize.height,
                fps = 30,
                recordingState = GpuRecordingState.Idle
            )
        )
        val parityPlan = RecordingPipelineParityPolicy.resolve(
            graphContract = graphContract,
            rawSupported = true,
            vulkanRuntimeSupported = true
        )
        return Camera2SessionGraphParityPolicy.resolve(
            Camera2SessionGraphParityRequest(
                graphContract = graphContract,
                parityPlan = parityPlan,
                previewSize = previewSize,
                encoderSize = encoderSize,
                proposedAnalysisSize = proposedAnalysisSize,
                proposedReadoutOverrideSize = proposedReadoutOverrideSize,
                recordSurfaceAttachedToSession = recordSurfaceAttachedToSession,
                encoderSurfaceAvailable = encoderSurfaceAvailable,
                previousSessionSignature = previousSessionSignature,
                stage = stage
            )
        )
    }
}
```

- [ ] **Step 2: Run the new tests and verify they fail**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.ui.Camera2SessionGraphParityPolicyTest"
```

Expected: FAIL because `Camera2SessionGraphParityPolicy`, `Camera2SessionGraphStage`, `Camera2SessionGraphKind`, `Camera2SessionRefreshAction`, and `Camera2SessionGraphParityPlan` do not exist.

- [ ] **Step 3: Commit the failing tests**

```powershell
git add -- app/src/test/java/com/neoproc/neoproccamx/ui/Camera2SessionGraphParityPolicyTest.kt
git commit -m "test: define camera2 session graph parity contract"
```

---

### Task 2: Implement Camera2 Session Graph Parity Policy

**Files:**
- Create: `app/src/main/java/com/neoproc/neoproccamx/ui/Camera2SessionGraphParityPolicy.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/ui/Camera2SessionGraphParityPolicyTest.kt`

- [ ] **Step 1: Add the policy implementation**

Create `app/src/main/java/com/neoproc/neoproccamx/ui/Camera2SessionGraphParityPolicy.kt`:

```kotlin
package com.neoproc.neoproccamx.ui

import android.util.Size

internal enum class Camera2SessionGraphStage {
    IDLE,
    PREARMING,
    PREARMED,
    STARTING,
    RECORDING,
    STOPPING
}

internal enum class Camera2SessionGraphKind {
    PROCESSED_PREVIEW_ENCODER_PARITY,
    RAW_SENSOR_ORIGINAL
}

internal enum class Camera2SessionRefreshAction {
    NONE,
    REPEATING_REQUEST_ONLY,
    REBUILD_SESSION,
    REBUILD_SESSION_AND_READERS
}

internal data class Camera2SessionGraphParityRequest(
    val graphContract: VideoPipelineContract,
    val parityPlan: RecordingPipelineParityPlan,
    val previewSize: Size,
    val encoderSize: Size,
    val proposedAnalysisSize: Size,
    val proposedReadoutOverrideSize: Size?,
    val recordSurfaceAttachedToSession: Boolean,
    val encoderSurfaceAvailable: Boolean,
    val previousSessionSignature: String? = null,
    val stage: Camera2SessionGraphStage
)

internal data class Camera2SessionGraphParityPlan(
    val graphKind: Camera2SessionGraphKind,
    val deliveryKind: VideoDeliveryKind,
    val previewSize: Size,
    val encoderSize: Size,
    val analysisTargetSize: Size,
    val readoutOverrideSize: Size?,
    val attachRecordSurfaceToSession: Boolean,
    val enablePreviewContinuityRecordSurface: Boolean,
    val rawSensorOwnsReadout: Boolean,
    val refreshAction: Camera2SessionRefreshAction,
    val sessionSignature: String,
    val diagnosticReason: String
)

internal object Camera2SessionGraphParityPolicy {

    fun resolve(request: Camera2SessionGraphParityRequest): Camera2SessionGraphParityPlan {
        val rawOriginal =
            request.parityPlan.deliveryKind == VideoDeliveryKind.RAW &&
                request.parityPlan.encoderRawEnabled
        val graphKind = if (rawOriginal) {
            Camera2SessionGraphKind.RAW_SENSOR_ORIGINAL
        } else {
            Camera2SessionGraphKind.PROCESSED_PREVIEW_ENCODER_PARITY
        }
        val activeRecordingStage = request.stage != Camera2SessionGraphStage.IDLE
        val attachRecordSurface =
            request.encoderSurfaceAvailable && activeRecordingStage
        val readoutOverride = if (rawOriginal) {
            request.proposedReadoutOverrideSize ?: request.encoderSize
        } else {
            null
        }
        val enablePreviewContinuity =
            graphKind == Camera2SessionGraphKind.PROCESSED_PREVIEW_ENCODER_PARITY &&
                attachRecordSurface &&
                request.stage in setOf(
                    Camera2SessionGraphStage.PREARMED,
                    Camera2SessionGraphStage.STARTING,
                    Camera2SessionGraphStage.RECORDING
                )
        val sessionSignature = buildSessionSignature(
            request = request,
            graphKind = graphKind,
            readoutOverride = readoutOverride,
            attachRecordSurface = attachRecordSurface
        )
        val sessionChanged =
            request.previousSessionSignature != null &&
                request.previousSessionSignature != sessionSignature
        val refreshAction = resolveRefreshAction(
            graphKind = graphKind,
            stage = request.stage,
            attachRecordSurface = attachRecordSurface,
            recordSurfaceAttachedToSession = request.recordSurfaceAttachedToSession,
            sessionChanged = sessionChanged
        )
        return Camera2SessionGraphParityPlan(
            graphKind = graphKind,
            deliveryKind = request.parityPlan.deliveryKind,
            previewSize = request.previewSize,
            encoderSize = request.encoderSize,
            analysisTargetSize = request.proposedAnalysisSize,
            readoutOverrideSize = readoutOverride,
            attachRecordSurfaceToSession = attachRecordSurface,
            enablePreviewContinuityRecordSurface = enablePreviewContinuity,
            rawSensorOwnsReadout = rawOriginal,
            refreshAction = refreshAction,
            sessionSignature = sessionSignature,
            diagnosticReason = diagnosticReason(
                graphKind = graphKind,
                refreshAction = refreshAction,
                sessionChanged = sessionChanged,
                recordSurfaceAttachedToSession = request.recordSurfaceAttachedToSession
            )
        )
    }

    private fun resolveRefreshAction(
        graphKind: Camera2SessionGraphKind,
        stage: Camera2SessionGraphStage,
        attachRecordSurface: Boolean,
        recordSurfaceAttachedToSession: Boolean,
        sessionChanged: Boolean
    ): Camera2SessionRefreshAction {
        if (graphKind == Camera2SessionGraphKind.RAW_SENSOR_ORIGINAL) {
            return when {
                stage == Camera2SessionGraphStage.IDLE && !sessionChanged ->
                    Camera2SessionRefreshAction.NONE
                else -> Camera2SessionRefreshAction.REBUILD_SESSION_AND_READERS
            }
        }
        if (sessionChanged) return Camera2SessionRefreshAction.REBUILD_SESSION
        if (!attachRecordSurface) return Camera2SessionRefreshAction.NONE
        return when {
            recordSurfaceAttachedToSession &&
                stage in setOf(
                    Camera2SessionGraphStage.STARTING,
                    Camera2SessionGraphStage.RECORDING
                ) -> Camera2SessionRefreshAction.REPEATING_REQUEST_ONLY
            recordSurfaceAttachedToSession -> Camera2SessionRefreshAction.NONE
            else -> Camera2SessionRefreshAction.REBUILD_SESSION
        }
    }

    private fun buildSessionSignature(
        request: Camera2SessionGraphParityRequest,
        graphKind: Camera2SessionGraphKind,
        readoutOverride: Size?,
        attachRecordSurface: Boolean
    ): String = listOf(
        graphKind.name,
        request.parityPlan.deliveryKind.name,
        request.parityPlan.encoderHdrMode,
        request.parityPlan.encoderColorSpace,
        request.parityPlan.encoderLogEnabled.toString(),
        request.parityPlan.encoderRawEnabled.toString(),
        request.parityPlan.tenBitRequestedForSessionPlan.toString(),
        "${request.previewSize.width}x${request.previewSize.height}",
        "${request.encoderSize.width}x${request.encoderSize.height}",
        "${request.proposedAnalysisSize.width}x${request.proposedAnalysisSize.height}",
        readoutOverride?.let { "${it.width}x${it.height}" } ?: "none",
        attachRecordSurface.toString(),
        request.graphContract.fps.toString(),
        request.graphContract.cameraRequestFamily.name
    ).joinToString(separator = "|")

    private fun diagnosticReason(
        graphKind: Camera2SessionGraphKind,
        refreshAction: Camera2SessionRefreshAction,
        sessionChanged: Boolean,
        recordSurfaceAttachedToSession: Boolean
    ): String =
        "kind=$graphKind refresh=$refreshAction changed=$sessionChanged " +
            "recordSurfaceAttached=$recordSurfaceAttachedToSession"
}
```

- [ ] **Step 2: Run the focused tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.ui.Camera2SessionGraphParityPolicyTest"
```

Expected: PASS.

- [ ] **Step 3: Commit the policy**

```powershell
git add -- app/src/main/java/com/neoproc/neoproccamx/ui/Camera2SessionGraphParityPolicy.kt app/src/test/java/com/neoproc/neoproccamx/ui/Camera2SessionGraphParityPolicyTest.kt
git commit -m "feat: add camera2 session graph parity policy"
```

---

### Task 3: Wire CameraViewModel To Resolve And Log Session Graph Parity

**Files:**
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/ui/RecordingStartSessionRefreshContractTest.kt`

- [ ] **Step 1: Add the ViewModel cache field**

In `CameraViewModel.kt`, near the existing graph/parity signature fields, add:

```kotlin
@Volatile private var lastCamera2SessionGraphParitySignature: String? = null
```

- [ ] **Step 2: Add helper functions**

In `CameraViewModel.kt`, near `activeRecordingPipelineParityPlan`, add:

```kotlin
private fun activeCamera2SessionGraphParityPlan(
    graphContract: VideoPipelineContract = activeVideoPipelineContract(),
    parityPlan: RecordingPipelineParityPlan = activeRecordingPipelineParityPlan(graphContract),
    previewSize: Size = activeVideoGraphPreviewSize(),
    encoderSize: Size = Size(gpuRecording.videoWidth, gpuRecording.videoHeight),
    proposedAnalysisSize: Size = preferredRecordingAnalysisSize(encoderSize),
    proposedReadoutOverrideSize: Size? = null,
    recordSurfaceAttachedToSession: Boolean = prearmedRecordingSurfaceAttachedToSession,
    encoderSurfaceAvailable: Boolean = prearmedRecordingSurface != null ||
        gpuRecordState.value is GpuRecordingState.Ready ||
        gpuRecordState.value is GpuRecordingState.Recording,
    previousSessionSignature: String? = null,
    stage: Camera2SessionGraphStage = Camera2SessionGraphStage.IDLE
): Camera2SessionGraphParityPlan =
    Camera2SessionGraphParityPolicy.resolve(
        Camera2SessionGraphParityRequest(
            graphContract = graphContract,
            parityPlan = parityPlan,
            previewSize = previewSize,
            encoderSize = encoderSize,
            proposedAnalysisSize = proposedAnalysisSize,
            proposedReadoutOverrideSize = proposedReadoutOverrideSize,
            recordSurfaceAttachedToSession = recordSurfaceAttachedToSession,
            encoderSurfaceAvailable = encoderSurfaceAvailable,
            previousSessionSignature = previousSessionSignature,
            stage = stage
        )
    )

private fun logCamera2SessionGraphParityPlan(
    plan: Camera2SessionGraphParityPlan,
    reason: String,
    force: Boolean = false
) {
    if (!force && lastCamera2SessionGraphParitySignature == plan.sessionSignature) return
    lastCamera2SessionGraphParitySignature = plan.sessionSignature
    Log.i(
        "CameraViewModel",
        "Camera2 session graph[$reason]=${plan.sessionSignature} " +
            "kind=${plan.graphKind} delivery=${plan.deliveryKind} " +
            "refresh=${plan.refreshAction} preview=${plan.previewSize.width}x${plan.previewSize.height} " +
            "encoder=${plan.encoderSize.width}x${plan.encoderSize.height} " +
            "analysis=${plan.analysisTargetSize.width}x${plan.analysisTargetSize.height} " +
            "readout=${plan.readoutOverrideSize?.let { "${it.width}x${it.height}" } ?: "none"} " +
            "recordSurface=${plan.attachRecordSurfaceToSession} " +
            "previewContinuity=${plan.enablePreviewContinuityRecordSurface} " +
            "rawReadout=${plan.rawSensorOwnsReadout} reason=${plan.diagnosticReason}"
    )
}
```

- [ ] **Step 3: Run compile-focused tests to catch missing imports**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.ui.Camera2SessionGraphParityPolicyTest"
```

Expected: PASS. If `Log` or `GpuRecordingState` imports are already present in `CameraViewModel.kt`, no import change is needed.

- [ ] **Step 4: Commit helper wiring**

```powershell
git add -- app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt
git commit -m "feat: resolve camera2 session graph parity in viewmodel"
```

---

### Task 4: Apply The Session Graph Plan During Prearm And Recording Start

**Files:**
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/ui/RecordingStartSessionRefreshContractTest.kt`

- [ ] **Step 1: Add rebuild helper for refresh actions**

Replace `awaitFreshPreviewSessionBeforeRecording` signature and its `camera.refreshOutputsAndSession` call with:

```kotlin
private suspend fun awaitFreshPreviewSessionBeforeRecording(
    timeoutMs: Long = 20_000L,
    forceRebuild: Boolean = false,
    rebuildReaders: Boolean = false
): Boolean {
    val cameraStateSnapshot = cameraState.value
    if (cameraStateSnapshot != CameraState.Streaming &&
        cameraStateSnapshot != CameraState.Open &&
        cameraStateSnapshot != CameraState.Recording) {
        return true
    }
    val refreshed = CompletableDeferred<Unit>()
    camera.resetPreviewActivityTracking()
    camera.refreshOutputsAndSession(rebuildReaders = rebuildReaders, forceRebuild = forceRebuild) {
        refreshed.complete(Unit)
    }
    return withTimeoutOrNull(timeoutMs) {
        refreshed.await()
        var waitedMs = 0L
        val maxFrameWaitMs = 4000L
        while (!camera.hasRecentPreviewActivity()) {
            if (waitedMs >= maxFrameWaitMs) {
                Log.w("CameraViewModel", "awaitFreshPreviewSession: session configured but " +
                    "no frames received after ${maxFrameWaitMs}ms - proceeding with recording")
                break
            }
            delay(50L)
            waitedMs += 50L
        }
        true
    } ?: run {
        Log.e("CameraViewModel", "awaitFreshPreviewSession: TIMEOUT after ${timeoutMs}ms " +
            "- camera state=${cameraState.value}")
        false
    }
}
```

- [ ] **Step 2: Use the policy in `prepareGpuRecordingForPrearmedVideoSession`**

After `val preparedEncoderSize = Size(gpuRecording.videoWidth, gpuRecording.videoHeight)`, replace the direct CameraController sizing calls with:

```kotlin
val prearmSessionGraphPlan = activeCamera2SessionGraphParityPlan(
    graphContract = initialGraphContract,
    parityPlan = initialParityPlan,
    encoderSize = preparedEncoderSize,
    proposedAnalysisSize = preferredRecordingAnalysisSize(preparedEncoderSize),
    proposedReadoutOverrideSize = resolvedReadoutOverride,
    encoderSurfaceAvailable = false,
    recordSurfaceAttachedToSession = prearmedRecordingSurfaceAttachedToSession,
    stage = Camera2SessionGraphStage.PREARMING
)
camera.setRecordingReadoutOverrideSize(prearmSessionGraphPlan.readoutOverrideSize)
camera.setRecordingEncoderTargetSize(prearmSessionGraphPlan.encoderSize)
camera.setAnalysisTargetSize(prearmSessionGraphPlan.analysisTargetSize)
logCamera2SessionGraphParityPlan(prearmSessionGraphPlan, reason = "prearm-config")
```

- [ ] **Step 3: Use the policy when attaching a prearmed persistent surface**

In `prearmRecordingSurfaceNow`, inside `if (attachToPreviewSession)`, before `camera.setRecordSurface(surface, refreshSession = false)`, add:

```kotlin
val prearmAttachPlan = activeCamera2SessionGraphParityPlan(
    encoderSize = Size(gpuRecording.videoWidth, gpuRecording.videoHeight),
    proposedAnalysisSize = preferredRecordingAnalysisSize(Size(gpuRecording.videoWidth, gpuRecording.videoHeight)),
    proposedReadoutOverrideSize = null,
    encoderSurfaceAvailable = true,
    recordSurfaceAttachedToSession = false,
    stage = Camera2SessionGraphStage.PREARMING
)
camera.setRecordingReadoutOverrideSize(prearmAttachPlan.readoutOverrideSize)
camera.setRecordingEncoderTargetSize(prearmAttachPlan.encoderSize)
camera.setAnalysisTargetSize(prearmAttachPlan.analysisTargetSize)
logCamera2SessionGraphParityPlan(prearmAttachPlan, reason = "prearm-attach", force = true)
```

Replace the hard-coded `camera.refreshOutputsAndSession(rebuildReaders = false, forceRebuild = true)` in that block with:

```kotlin
camera.refreshOutputsAndSession(
    rebuildReaders = prearmAttachPlan.refreshAction == Camera2SessionRefreshAction.REBUILD_SESSION_AND_READERS,
    forceRebuild = prearmAttachPlan.refreshAction != Camera2SessionRefreshAction.NONE
) {
    refreshed.countDown()
}
```

- [ ] **Step 4: Use the policy before recording start paths**

After `val preparedEncoderSize = Size(gpuRecording.videoWidth, gpuRecording.videoHeight)`, add:

```kotlin
val startingSessionGraphPlan = activeCamera2SessionGraphParityPlan(
    encoderSize = preparedEncoderSize,
    proposedAnalysisSize = preferredRecordingAnalysisSize(preparedEncoderSize),
    proposedReadoutOverrideSize = resolvedReadoutOverride,
    encoderSurfaceAvailable = true,
    recordSurfaceAttachedToSession = encoderSurfaceAlreadyInSession,
    previousSessionSignature = null,
    stage = Camera2SessionGraphStage.STARTING
)
logCamera2SessionGraphParityPlan(startingSessionGraphPlan, reason = "recording-start", force = true)
```

In `startCamera2DirectRecording`, replace:

```kotlin
camera.setAnalysisTargetSize(
    preferredRecordingAnalysisSize(resolvedReadoutOverride ?: preparedEncoderSize)
)
camera.setRecordSurface(encoderSurface, refreshSession = false)
```

with:

```kotlin
camera.setRecordingReadoutOverrideSize(startingSessionGraphPlan.readoutOverrideSize)
camera.setRecordingEncoderTargetSize(startingSessionGraphPlan.encoderSize)
camera.setAnalysisTargetSize(startingSessionGraphPlan.analysisTargetSize)
camera.setRecordSurface(encoderSurface, refreshSession = false)
```

In `startCamera2PreviewParityRecording`, replace the same direct analysis sizing block with the same four policy-driven setter calls.

- [ ] **Step 5: Gate refresh action in processed preview-parity recording**

In `startCamera2PreviewParityRecording`, replace the rebuild branch condition:

```kotlin
if (encoderSurfaceAlreadyInSession) {
```

with:

```kotlin
if (startingSessionGraphPlan.refreshAction == Camera2SessionRefreshAction.REPEATING_REQUEST_ONLY) {
```

Replace the `awaitFreshPreviewSessionBeforeRecording(forceRebuild = true)` call in the rebuild branch with:

```kotlin
awaitFreshPreviewSessionBeforeRecording(
    forceRebuild = true,
    rebuildReaders = startingSessionGraphPlan.refreshAction ==
        Camera2SessionRefreshAction.REBUILD_SESSION_AND_READERS
)
```

- [ ] **Step 6: Gate refresh action in direct RAW recording**

In `startCamera2DirectRecording`, replace the `awaitFreshPreviewSessionBeforeRecording(forceRebuild = true)` call with:

```kotlin
awaitFreshPreviewSessionBeforeRecording(
    forceRebuild = true,
    rebuildReaders = startingSessionGraphPlan.refreshAction ==
        Camera2SessionRefreshAction.REBUILD_SESSION_AND_READERS
)
```

- [ ] **Step 7: Keep processed video from forcing native full-sensor preview binding**

Replace the `preferNativePreviewForRecording` full-sensor condition with the session graph result:

```kotlin
val preferNativePreviewForRecording =
    RecordingPreviewBindingPolicy.resolvePreferNativePreviewOverride(
        fullSensorReadoutForced = startingSessionGraphPlan.rawSensorOwnsReadout
    )
```

- [ ] **Step 8: Run focused tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.ui.Camera2SessionGraphParityPolicyTest" --tests "com.neoproc.neoproccamx.ui.RecordingStartSessionRefreshContractTest"
```

Expected: PASS.

- [ ] **Step 9: Commit recording-start wiring**

```powershell
git add -- app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt app/src/test/java/com/neoproc/neoproccamx/ui/RecordingStartSessionRefreshContractTest.kt
git commit -m "fix: apply camera2 session graph parity at recording start"
```

---

### Task 5: Update Source Contract Tests For The New Boundary

**Files:**
- Modify: `app/src/test/java/com/neoproc/neoproccamx/ui/RecordingStartSessionRefreshContractTest.kt`

- [ ] **Step 1: Add source-contract assertions**

Add assertions to `recording start keeps visible preview live and routes encoded video through internal feed`:

```kotlin
assertTrue(source.contains("Camera2SessionGraphParityPolicy.resolve("))
assertTrue(source.contains("activeCamera2SessionGraphParityPlan("))
assertTrue(source.contains("startingSessionGraphPlan.refreshAction"))
assertTrue(source.contains("startingSessionGraphPlan.readoutOverrideSize"))
assertTrue(source.contains("startingSessionGraphPlan.analysisTargetSize"))
assertTrue(source.contains("startingSessionGraphPlan.rawSensorOwnsReadout"))
assertTrue(source.contains("Camera2SessionRefreshAction.REPEATING_REQUEST_ONLY"))
```

- [ ] **Step 2: Run the source contract test**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.ui.RecordingStartSessionRefreshContractTest"
```

Expected: PASS.

- [ ] **Step 3: Commit the contract update**

```powershell
git add -- app/src/test/java/com/neoproc/neoproccamx/ui/RecordingStartSessionRefreshContractTest.kt
git commit -m "test: lock camera2 session parity recording contract"
```

---

### Task 6: Regression Verification

**Files:**
- No code edits.

- [ ] **Step 1: Run focused Camera2/session/recording parity tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.ui.Camera2SessionGraphParityPolicyTest" --tests "com.neoproc.neoproccamx.ui.RecordingPipelineParityPolicyTest" --tests "com.neoproc.neoproccamx.ui.VideoPipelineGraphControllerTest" --tests "com.neoproc.neoproccamx.ui.RecordingStartSessionRefreshContractTest" --tests "com.neoproc.neoproccamx.camera.Camera2PipelineRequestPolicyTest" --tests "com.neoproc.neoproccamx.camera.CinemaStreamGraphPolicyTest"
```

Expected: PASS.

- [ ] **Step 2: Run full unit tests and assemble**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

Expected: PASS.

- [ ] **Step 3: Run lint**

Run:

```powershell
.\gradlew.bat :app:lintDebug
```

Expected: PASS with no lint errors.

- [ ] **Step 4: Inspect final diff**

Run:

```powershell
git diff --stat HEAD~4..HEAD
git status --short -- app/src/main/java/com/neoproc/neoproccamx/ui/Camera2SessionGraphParityPolicy.kt app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt app/src/test/java/com/neoproc/neoproccamx/ui/Camera2SessionGraphParityPolicyTest.kt app/src/test/java/com/neoproc/neoproccamx/ui/RecordingStartSessionRefreshContractTest.kt
```

Expected: Only Phase 3 implementation files are changed or newly committed.

- [ ] **Step 5: Commit verification note if any test-only cleanup was required**

If Step 1, Step 2, and Step 3 pass without extra edits, no commit is needed. If a test-only cleanup edit was required, run:

```powershell
git add -- app/src/main/java/com/neoproc/neoproccamx/ui/Camera2SessionGraphParityPolicy.kt app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt app/src/test/java/com/neoproc/neoproccamx/ui/Camera2SessionGraphParityPolicyTest.kt app/src/test/java/com/neoproc/neoproccamx/ui/RecordingStartSessionRefreshContractTest.kt
git commit -m "test: verify camera2 session graph parity"
```

Expected: working tree still contains only unrelated pre-existing changes outside the Phase 3 file set.
