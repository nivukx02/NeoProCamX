# V5 Capture Truth, Parity, And Manual Color Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make recording format truth, preview/recording parity, NPV10 MOV color tagging, and manually selected color science/color space enforceable through code and tests.

**Architecture:** Extend existing policy boundaries instead of adding a new camera path. `VideoModePipelineContractPolicy` remains the truth source for SDR/HDR/LOG/RAW identity, `RecordingPipelineParityPolicy` carries preview/recording parity signatures, `Npv10MovConverter` resolves MOV `nclx` color atoms from NPV10 session metadata, and `EliteVideoQualityContract`/manual color policy continue to reject hidden defaults.

**Tech Stack:** Kotlin, Android MediaFormat color constants, QuickTime `nclx` color atoms, NPV10 P010 MOV export, JUnit4, Gradle debug unit tests.

---

### Task 1: Capture Truth Contract

**Files:**
- Modify: `app/src/test/java/com/neoproc/neoproccamx/ui/VideoModePipelineContractPolicyTest.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/VideoModePipelineContractPolicy.kt`

- [ ] **Step 1: Write the failing test**

Add a test proving:
- Normal/Pro resolves to SDR Rec.709 and does not require HDR metadata.
- NDR10+ and NIVUKX resolve to BT.2020 HDR transport and ten-bit.
- LOG resolves to Rec.2020-LOG, ten-bit, and `effectiveHdrMode=OFF`.
- RAW resolves to RAW with no HDR/LOG shortcut.

- [ ] **Step 2: Run focused test**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.ui.VideoModePipelineContractPolicyTest" --no-daemon --no-configuration-cache --console=plain`
Expected: FAIL until capture truth summary fields exist.

- [ ] **Step 3: Implement minimal truth summary**

Add a stable `captureTruthSignature`/summary to `VideoModePipelineContract` containing delivery kind, color space, HDR mode, ten-bit, log, raw, and container metadata requirements.

- [ ] **Step 4: Re-run focused test**

Expected: PASS.

### Task 2: Preview/Recording Parity Lock

**Files:**
- Modify: `app/src/test/java/com/neoproc/neoproccamx/ui/RecordingPipelineParityPolicyTest.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/RecordingPipelineParityPolicy.kt`

- [ ] **Step 1: Write the failing test**

Add a test requiring parity signatures to include delivery, feed path, color space, tone map, denoise identity, sharpness identity, and manual color selection identity.

- [ ] **Step 2: Run focused test**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.ui.RecordingPipelineParityPolicyTest" --no-daemon --no-configuration-cache --console=plain`
Expected: FAIL until parity metadata is added.

- [ ] **Step 3: Implement parity metadata**

Add parity fields/signature values without changing runtime graph routing.

- [ ] **Step 4: Re-run focused test**

Expected: PASS.

### Task 3: NPV10 MOV Color Tagging

**Files:**
- Modify: `app/src/test/java/com/neoproc/neoproccamx/recording/Npv10MovConverterTest.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/recording/Npv10MovConverter.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/recording/Npv10IntegrityValidator.kt`

- [ ] **Step 1: Write the failing tests**

Add tests proving:
- Rec.709 NPV10 exports use BT.709 transfer/matrix.
- NDR10+/NIVUKX NPV10 exports use BT.2020 primaries, PQ transfer, BT.2020-NCL matrix.
- N-Log exports carry BT.2020 primaries with a non-HDR transfer tag and explicit selected profile/certificate metadata.

- [ ] **Step 2: Run focused test**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.recording.Npv10MovConverterTest" --no-daemon --no-configuration-cache --console=plain`
Expected: FAIL because MOV export is currently hardcoded to Rec.709.

- [ ] **Step 3: Implement MOV tag policy**

Resolve `QuickTimeColorAtomConfig` from `Npv10FileHeader` color metadata, pass it into `QuickTimeMovFileWriter`, and include color atom values/profile in the conversion audit certificate.

- [ ] **Step 4: Re-run focused test**

Expected: PASS.

### Task 4: Manual Color Selection Enforcement

**Files:**
- Modify: `app/src/test/java/com/neoproc/neoproccamx/recording/EliteVideoQualityContractTest.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/recording/EliteVideoQualityContract.kt`

- [ ] **Step 1: Write the failing test**

Add a test requiring color science and color space metadata to remain `USER_MANUAL` for normal, pro, HDR/NDR, LOG, RAW, and NPV10 requests.

- [ ] **Step 2: Run focused test**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.recording.EliteVideoQualityContractTest" --no-daemon --no-configuration-cache --console=plain`
Expected: FAIL until metadata exposes manual color selection across all modes if needed.

- [ ] **Step 3: Implement missing metadata only**

Do not invent defaults. Use existing `ManualVideoColorSelectionPolicy` and require user-selected color science/color space to appear in output metadata.

- [ ] **Step 4: Re-run focused test**

Expected: PASS.

### Task 5: Final Verification

- [ ] **Step 1: Run combined focused tests**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.ui.VideoModePipelineContractPolicyTest" --tests "com.neoproc.neoproccamx.ui.RecordingPipelineParityPolicyTest" --tests "com.neoproc.neoproccamx.recording.Npv10MovConverterTest" --tests "com.neoproc.neoproccamx.recording.EliteVideoQualityContractTest" --no-daemon --no-configuration-cache --console=plain`
Expected: PASS.

- [ ] **Step 2: Run full unit tests**

Run: `.\gradlew.bat :app:testDebugUnitTest --no-daemon --no-configuration-cache --console=plain`
Expected: PASS.

- [ ] **Step 3: Run debug build**

Run: `.\gradlew.bat :app:assembleDebug --no-daemon --no-configuration-cache --console=plain`
Expected: PASS.
