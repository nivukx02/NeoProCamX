# Video Color Stability Shot-Lock Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop avoidable video color shifts during near/far focus changes by locking the established recording white balance while preserving user-selected color science, standards-correct Rec.709/Rec.2020 metadata, and RAW/NPV10 truth.

**Architecture:** Add one pure camera-layer color-stability policy and apply it at the Camera2 request boundary, where white balance actually changes. Use `CameraController.recordingActive`, because both processed recording and NPV10 recording invoke `startVideoCapture()`. Keep tap-focus exposure metering responsive; the request layer independently retains AWB stability. Leave existing color-space/science selection and MOV metadata policies intact.

**Tech Stack:** Kotlin, Android Camera2 `CaptureRequest`, JUnit4 unit/source-contract tests, Gradle Android build/lint.

---

## File Map

- Create `app/src/main/java/com/neoproc/neoproccamx/camera/VideoColorStabilityPolicy.kt`: pure shot-lock decision model.
- Create `app/src/test/java/com/neoproc/neoproccamx/camera/VideoColorStabilityPolicyTest.kt`: behavior tests for processed, NPV10, HDR, and RAW-monitor cases.
- Modify `app/src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt`: apply the policy when producing repeating Camera2 requests.
- Modify `app/src/test/java/com/neoproc/neoproccamx/camera/CameraControllerHdrAwbContractTest.kt`: require policy wiring and removal of HDR live-AWB override.
- Modify `app/src/main/java/com/neoproc/neoproccamx/camera/CameraHdrColorCorrectionPolicy.kt`: keep manual WB correction authoritative for HDR transports.
- Modify `app/src/test/java/com/neoproc/neoproccamx/camera/CameraHdrColorCorrectionPolicyTest.kt`: prove HDR does not overwrite a manual color transform.
- Verify `app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt`: tap focus may still release AE; Camera2 shot-lock independently retains AWB.
- Modify `app/src/test/java/com/neoproc/neoproccamx/ui/CameraViewModelRecordingColorContractTest.kt`: document retained focus exposure behavior.
- Verify existing `ManualVideoColorSelectionPolicyTest.kt`, `QuickTimeColorAtomPolicyTest.kt`, `Npv10MovConverterTest.kt`, and `RecordingPipelineParityPolicyTest.kt`: no production changes are intended to metadata or color identity.

**Working Tree Note:** The camera and recording sources already contain extensive uncommitted work. Do not create implementation commits that would sweep unrelated existing edits into a commit. Use tests and an explicit diff summary as checkpoints unless the user separately requests a consolidation commit.

### Task 1: Add A Pure Recording Color-Stability Policy

**Files:**
- Create: `app/src/test/java/com/neoproc/neoproccamx/camera/VideoColorStabilityPolicyTest.kt`
- Create: `app/src/main/java/com/neoproc/neoproccamx/camera/VideoColorStabilityPolicy.kt`

- [ ] **Step 1: Write the failing policy test**

```kotlin
package com.neoproc.neoproccamx.camera

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoColorStabilityPolicyTest {
    @Test
    fun `processed recording locks established auto white balance during focus changes`() {
        val plan = VideoColorStabilityPolicy.resolve(
            videoRecordingActive = true,
            sensorMasterActive = false,
            manualWhiteBalanceSelected = false,
            existingAeAwbLock = false
        )

        assertTrue(plan.awbLockRequested)
        assertTrue(plan.allowManualWhiteBalance)
    }

    @Test
    fun `npv10 capture uses the same stable recording color request path`() {
        val plan = VideoColorStabilityPolicy.resolve(
            videoRecordingActive = true,
            sensorMasterActive = false,
            manualWhiteBalanceSelected = true,
            existingAeAwbLock = false
        )

        assertFalse(plan.awbLockRequested)
        assertTrue(plan.allowManualWhiteBalance)
    }

    @Test
    fun `raw sensor master keeps raw truth while stabilizing its recording monitor`() {
        val plan = VideoColorStabilityPolicy.resolve(
            videoRecordingActive = true,
            sensorMasterActive = true,
            manualWhiteBalanceSelected = true,
            existingAeAwbLock = false
        )

        assertTrue(plan.awbLockRequested)
        assertFalse(plan.allowManualWhiteBalance)
    }

    @Test
    fun `idle preview may continue adapting white balance`() {
        val plan = VideoColorStabilityPolicy.resolve(
            videoRecordingActive = false,
            sensorMasterActive = false,
            manualWhiteBalanceSelected = false,
            existingAeAwbLock = false
        )

        assertFalse(plan.awbLockRequested)
    }
}
```

- [ ] **Step 2: Run the test to prove the policy is missing**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests '*VideoColorStabilityPolicyTest' --no-daemon --no-configuration-cache --console=plain
```

Expected: compilation fails because `VideoColorStabilityPolicy` is unresolved.

- [ ] **Step 3: Add the minimal pure policy**

```kotlin
package com.neoproc.neoproccamx.camera

internal data class VideoColorStabilityPlan(
    val awbLockRequested: Boolean,
    val allowManualWhiteBalance: Boolean,
    val evidenceLabel: String
)

internal object VideoColorStabilityPolicy {
    fun resolve(
        videoRecordingActive: Boolean,
        sensorMasterActive: Boolean,
        manualWhiteBalanceSelected: Boolean,
        existingAeAwbLock: Boolean
    ): VideoColorStabilityPlan {
        val applyManualWhiteBalance = manualWhiteBalanceSelected && !sensorMasterActive
        val lockAutoWhiteBalance =
            !applyManualWhiteBalance && (videoRecordingActive || existingAeAwbLock)
        return VideoColorStabilityPlan(
            awbLockRequested = lockAutoWhiteBalance,
            allowManualWhiteBalance = !sensorMasterActive,
            evidenceLabel = when {
                sensorMasterActive && videoRecordingActive -> "RAW_MONITOR_LOCKED_MASTER_UNMODIFIED"
                applyManualWhiteBalance && videoRecordingActive -> "MANUAL_WB_RECORDING"
                videoRecordingActive -> "AUTO_WB_SHOT_LOCKED"
                existingAeAwbLock -> "USER_AE_AWB_LOCK"
                else -> "AUTO_WB_PREVIEW_ADAPTIVE"
            }
        )
    }
}
```

- [ ] **Step 4: Run the focused policy test**

Run the command from Step 2.

Expected: `VideoColorStabilityPolicyTest` passes.

### Task 2: Apply Shot-Lock At The Camera2 White-Balance Boundary

**Files:**
- Modify: `app/src/test/java/com/neoproc/neoproccamx/camera/CameraControllerHdrAwbContractTest.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt:1230-1280`
- Modify: `app/src/test/java/com/neoproc/neoproccamx/camera/CameraHdrColorCorrectionPolicyTest.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/camera/CameraHdrColorCorrectionPolicy.kt`

- [ ] **Step 1: Write failing source-contract tests for the request path**

Append these tests to `CameraControllerHdrAwbContractTest`:

```kotlin
    @Test
    fun `recording request resolves video color stability before white balance`() {
        assertTrue(source.contains("VideoColorStabilityPolicy.resolve("))
        assertTrue(source.contains("videoRecordingActive = recordingActive"))
        assertTrue(source.contains("sensorMasterActive = rawVideoCaptureEnabled"))
        assertTrue(source.contains("awbLocked = colorStabilityPlan.awbLockRequested"))
        assertTrue(source.contains("maybeLogVideoColorStabilityRequest("))
    }

    @Test
    fun `ndr and nivukx do not discard manual white balance by forcing live awb`() {
        assertTrue(source.contains("manualKelvin = effectiveManualKelvin"))
        assertTrue(source.contains("forceAutoForHdr = false"))
        assertTrue(!source.contains("forceAutoForHdr = hdrDynamicRangeMode != HdrDynamicRangeMode.SDR"))
    }
```

- [ ] **Step 2: Run the contract test to prove current wiring is wrong**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests '*CameraControllerHdrAwbContractTest' --no-daemon --no-configuration-cache --console=plain
```

Expected: the added assertions fail because `CameraController` still passes `forceAutoForHdr = hdrDynamicRangeMode != HdrDynamicRangeMode.SDR` and has no stability plan.

- [ ] **Step 3: Resolve the policy in `CameraController.applyAll()`**

Immediately before the existing `CinemaAutoWhiteBalanceRequestPolicy.resolve(...)` call, add:

```kotlin
        val colorStabilityPlan = VideoColorStabilityPolicy.resolve(
            videoRecordingActive = recordingActive,
            sensorMasterActive = rawVideoCaptureEnabled,
            manualWhiteBalanceSelected = manWbK > 0,
            existingAeAwbLock = aeAwbLocked
        )
        val effectiveManualKelvin =
            if (colorStabilityPlan.allowManualWhiteBalance) manWbK else -1
```

Change that existing call to:

```kotlin
        val whiteBalancePlan = CinemaAutoWhiteBalanceRequestPolicy.resolve(
            manualKelvin = effectiveManualKelvin,
            manualTint = manWbTint,
            kelvinRange = CameraIntRange(manualWhiteBalanceRange.lower, manualWhiteBalanceRange.upper),
            availableAwbModes = cachedAwbModes,
            supportsCctWhiteBalance = supportsCctWhiteBalance,
            awbLocked = colorStabilityPlan.awbLockRequested,
            forceAutoForHdr = false,
            sdkInt = Build.VERSION.SDK_INT
        )
```

Do not change `builder[CaptureRequest.CONTROL_AE_LOCK] = aeAwbLocked`; auto color stability must not unexpectedly freeze exposure.

- [ ] **Step 4: Add rate-limited logging evidence for device validation**

Beside the existing `lastLoggedHdrColorRequestSignature` field, add:

```kotlin
    private var lastLoggedVideoColorStabilitySignature: String? = null
```

After `maybeLogHdrColorRequest(whiteBalancePlan, hdrColorCorrectionMode)`, add:

```kotlin
        maybeLogVideoColorStabilityRequest(colorStabilityPlan, whiteBalancePlan)
```

Add this method beside `maybeLogHdrColorRequest(...)`:

```kotlin
    private fun maybeLogVideoColorStabilityRequest(
        colorStabilityPlan: VideoColorStabilityPlan,
        whiteBalancePlan: CinemaAutoWhiteBalanceRequestPlan
    ) {
        if (!recordingActive) return
        val signature = listOf(
            colorStabilityPlan.evidenceLabel,
            whiteBalancePlan.awbMode,
            whiteBalancePlan.awbLock,
            whiteBalancePlan.colorTemperatureKelvin,
            rawVideoCaptureEnabled,
            npv10TenBitCaptureEnabled
        ).joinToString(separator = "|")
        if (lastLoggedVideoColorStabilitySignature == signature) return
        lastLoggedVideoColorStabilitySignature = signature
        Log.i(
            TAG,
            "Video color stability state=${colorStabilityPlan.evidenceLabel} " +
                "awbMode=${whiteBalancePlan.awbMode} awbLock=${whiteBalancePlan.awbLock} " +
                "manualKelvin=${whiteBalancePlan.colorTemperatureKelvin ?: -1} " +
                "rawMaster=$rawVideoCaptureEnabled npv10=$npv10TenBitCaptureEnabled"
        )
    }
```

The log distinguishes `AUTO_WB_SHOT_LOCKED`, `MANUAL_WB_RECORDING`, and
`RAW_MONITOR_LOCKED_MASTER_UNMODIFIED` without claiming RAW pixels were
converted.

- [ ] **Step 5: Write a failing test that HDR color correction does not overwrite manual WB**

Append to `CameraHdrColorCorrectionPolicyTest`:

```kotlin
    @Test
    fun `hdr manual white balance retains its transform instead of being overwritten`() {
        val mode = CameraHdrColorCorrectionPolicy.resolve(
            hdrDynamicRangeMode = HdrDynamicRangeMode.HDR10_PLUS,
            strictProcessedVideo = true,
            rawVideoCaptureEnabled = false,
            manualColorTransformActive = true
        )

        assertNull(mode)
    }
```

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests '*CameraHdrColorCorrectionPolicyTest' --no-daemon --no-configuration-cache --console=plain
```

Expected: the new test fails because HDR currently requests high-quality color correction even when manual white balance selected a matrix/CCT transform.

- [ ] **Step 6: Retain the manual color transform under HDR transport**

Change the high-quality force condition in `CameraHdrColorCorrectionPolicy.resolve(...)`:

```kotlin
        val shouldForceHighQuality =
            !manualColorTransformActive && (
                hdrDynamicRangeMode != HdrDynamicRangeMode.SDR ||
                    (strictProcessedVideo && !rawVideoCaptureEnabled)
                )
```

The HDR transport remains standards-correct. This only prevents a later Camera2 color-correction mode from superseding an explicit user-selected white balance transform.

- [ ] **Step 7: Run the Camera2 tests**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests '*VideoColorStabilityPolicyTest' --tests '*CinemaAutoWhiteBalanceRequestPolicyTest' --tests '*CameraControllerHdrAwbContractTest' --tests '*CameraHdrColorCorrectionPolicyTest' --no-daemon --no-configuration-cache --console=plain
```

Expected: all selected tests pass. The legacy `CinemaAutoWhiteBalanceRequestPolicyTest` still tests its opt-in forced-auto branch, but active NDR/NIVUKX wiring no longer invokes that branch.

### Task 3: Prove Tap Focus Leaves Exposure Responsive While Camera2 Owns AWB Stability

**Files:**
- Modify: `app/src/test/java/com/neoproc/neoproccamx/ui/CameraViewModelRecordingColorContractTest.kt`
- Verify: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt:4160-4245`

- [ ] **Step 1: Document that focus can still release exposure metering**

Append to `CameraViewModelRecordingColorContractTest`:

```kotlin
    @Test
    fun `tap focus can release exposure because recording white balance is independently shot locked`() {
        val focusBody = source.substringAfter("fun tapToFocus(nx: Float, ny: Float)")
            .substringBefore("fun lockFocusAndExposure")

        assertTrue(focusBody.contains("camera.setAeAwbLock(false)"))
    }
```

- [ ] **Step 2: Run the focused ViewModel and Camera2 contract tests**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests '*CameraViewModelRecordingColorContractTest' --tests '*CameraControllerHdrAwbContractTest' --no-daemon --no-configuration-cache --console=plain
```

Expected: passes once Task 2 is green. No `CameraViewModel` production edit is required: focus is permitted to meter exposure while `CameraController` independently requests stable AWB for recorded video.

- [ ] **Step 3: Run the focused ViewModel and parity tests**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests '*CameraViewModelRecordingColorContractTest' --tests '*RecordingPipelineParityPolicyTest' --tests '*ManualVideoColorSelectionPolicyTest' --no-daemon --no-configuration-cache --console=plain
```

Expected: all selected tests pass.

### Task 4: Prove Standards Metadata And NPV10 Truth Are Unchanged

**Files:**
- Verify: `app/src/test/java/com/neoproc/neoproccamx/recording/QuickTimeColorAtomPolicyTest.kt`
- Verify: `app/src/test/java/com/neoproc/neoproccamx/recording/Npv10MovConverterTest.kt`
- Verify: `app/src/test/java/com/neoproc/neoproccamx/recording/ManualVideoColorSelectionPolicyTest.kt`

- [ ] **Step 1: Run the color identity and export tests**

Run:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --tests '*ManualVideoColorSelectionPolicyTest' --tests '*QuickTimeColorAtomPolicyTest' --tests '*Npv10MovConverterTest' --tests '*Npv10*Test' --no-daemon --no-configuration-cache --console=plain
```

Expected: passing tests demonstrate:

- user choice is required/saved rather than silently defaulted,
- Rec.709 output remains BT.709 SDR,
- Rec.2020 SDR remains BT.2020 SDR,
- PQ/HLG outputs remain BT.2020 with correct transfer,
- NPV10 master/export correctness is not regressed.

### Task 5: Complete Local Verification And Prepare Device Measurement

**Files:**
- Verify only: final changed source and tests

- [ ] **Step 1: Run the complete debug unit suite**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:testDebugUnitTest --no-daemon --no-configuration-cache --console=plain
```

Expected: all unit tests pass.

- [ ] **Step 2: Run static analysis and APK assembly**

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:lintDebug :app:assembleDebug --no-daemon --no-configuration-cache --console=plain
```

Expected: build succeeds and lint reports `0 errors, 0 warnings`.

- [ ] **Step 3: Install and launch only when ADB lists a device**

```powershell
& 'C:\Users\acer\AppData\Local\Android\Sdk\platform-tools\adb.exe' devices -l
& 'C:\Users\acer\AppData\Local\Android\Sdk\platform-tools\adb.exe' install -r 'app\build\outputs\apk\debug\app-debug.apk'
& 'C:\Users\acer\AppData\Local\Android\Sdk\platform-tools\adb.exe' shell am force-stop com.neoproc.neoproccamx
& 'C:\Users\acer\AppData\Local\Android\Sdk\platform-tools\adb.exe' shell am start -n com.neoproc.neoproccamx/.ui.MainActivity
```

Expected: the install succeeds and the main camera activity starts.

- [ ] **Step 4: Collect real-device evidence**

Record a fixed-color object while shifting focus near/far in Normal, Pro,
N-Log, NDR10+, NIVUKX Vision, and NPV10. Capture logs for
`AUTO_WB_SHOT_LOCKED`, `MANUAL_WB_RECORDING`, or
`RAW_MONITOR_LOCKED_MASTER_UNMODIFIED`, then pull/publish eligible MOV clips
and inspect them with `ffprobe` or MediaInfo for color primaries, transfer,
matrix, resolution, FPS, and codec truth.

Expected: color metadata remains correct and the locked/manual request evidence
is present. Visual stability is reported from captured frames, not assumed from
the unit tests.
