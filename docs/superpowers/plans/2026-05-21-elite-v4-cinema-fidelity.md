# Elite V4 Cinema Fidelity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade the normal encoded video pipeline and NPV10 MOV export pipeline with truthful Elite V4 cinema fidelity controls for cleaner chroma, stronger edge confidence, higher perceptual detail, and safer artifact handling.

**Architecture:** Keep the existing policy-driven architecture. Extend `VideoImageQualityPipelinePolicy` with V4 measured stages and tuning floors, extend `EliteVideoQualityContract` metadata without claiming fake native resolution, and improve `Npv10ExportEnhancementPolicy` export-only math while preserving private `.npv10` masters unchanged.

**Tech Stack:** Kotlin, Android unit tests, Gradle debug unit tests, QuickTime MOV wrapper, NPV10 P010 export path.

---

### Task 1: V4 Normal Pipeline Contract

**Files:**
- Modify: `app/src/test/java/com/neoproc/neoproccamx/pipeline/VideoImageQualityPipelinePolicyTest.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/pipeline/VideoImageQualityPipelinePolicy.kt`

- [ ] **Step 1: Write failing test**

Add a test requiring encoded SDR/HDR/LOG modes to include `SENSOR_NOISE_PROFILE`, `SHADOW_CHROMA_LOCK`, `SUBPIXEL_EDGE_DETECTION`, `ALIAS_SAFE_DETAIL_RECONSTRUCTION`, and `SCENE_COMPLEXITY_BITRATE_GUARD`, while RAW excludes encoded-only stages.

- [ ] **Step 2: Run focused policy test**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.pipeline.VideoImageQualityPipelinePolicyTest" --no-daemon --no-configuration-cache --console=plain`
Expected: FAIL because the V4 stages do not exist yet.

- [ ] **Step 3: Implement V4 policy**

Add enum stages, plan fields, encoded-mode floor calculations, and pipeline ordering. Keep RAW calibration truthful and do not add encoded post-processing stages to RAW.

- [ ] **Step 4: Re-run focused policy test**

Run the same focused test command.
Expected: PASS.

### Task 2: V4 Metadata Contract

**Files:**
- Modify: `app/src/test/java/com/neoproc/neoproccamx/recording/EliteVideoQualityContractTest.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/recording/EliteVideoQualityContract.kt`

- [ ] **Step 1: Write failing test**

Update enhancement metadata expectations to `ELITE_V4_CINEMA_FIDELITY`, require V4 flags for sensor noise profile, shadow chroma lock, subpixel edge detection, alias-safe detail reconstruction, scene complexity bitrate guard, and truthful perceptual resolution.

- [ ] **Step 2: Run focused metadata test**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.recording.EliteVideoQualityContractTest" --no-daemon --no-configuration-cache --console=plain`
Expected: FAIL because V4 metadata fields are absent.

- [ ] **Step 3: Implement metadata**

Extend `EliteVideoEnhancementPlan` and metadata serialization. Raise bitrate floors conservatively while respecting codec ceilings. Keep `nativeOutputResolutionFaked=false`.

- [ ] **Step 4: Re-run focused metadata test**

Run the same focused metadata command.
Expected: PASS.

### Task 3: V4 NPV10 Export Fidelity

**Files:**
- Modify: `app/src/test/java/com/neoproc/neoproccamx/recording/Npv10MovConverterTest.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/recording/Npv10MovConverter.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/recording/Npv10IntegrityValidator.kt`

- [ ] **Step 1: Write failing tests**

Add tests requiring MOV conversion certificates to report local chroma spike smoothing, anti-alias edge protection, and V4 export fidelity. Add a direct enhancer test proving isolated chroma spikes move closer to neutral while private masters remain unchanged.

- [ ] **Step 2: Run focused NPV10 test**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.recording.Npv10MovConverterTest" --no-daemon --no-configuration-cache --console=plain`
Expected: FAIL because V4 export certificate fields and chroma spike smoothing are absent.

- [ ] **Step 3: Implement export math and audit**

Add local chroma smoothing against neighboring chroma samples, anti-alias detail clamping, and conversion certificate fields. Do not mutate `.npv10` input files.

- [ ] **Step 4: Re-run focused NPV10 test**

Run the same focused NPV10 command.
Expected: PASS.

### Task 4: Final Verification

**Files:**
- No new files beyond tasks above.

- [ ] **Step 1: Run focused combined tests**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.pipeline.VideoImageQualityPipelinePolicyTest" --tests "com.neoproc.neoproccamx.recording.EliteVideoQualityContractTest" --tests "com.neoproc.neoproccamx.recording.Npv10MovConverterTest" --no-daemon --no-configuration-cache --console=plain`
Expected: PASS.

- [ ] **Step 2: Run full unit tests**

Run: `.\gradlew.bat :app:testDebugUnitTest --no-daemon --no-configuration-cache --console=plain`
Expected: PASS.

- [ ] **Step 3: Run debug build**

Run: `.\gradlew.bat :app:assembleDebug --no-daemon --no-configuration-cache --console=plain`
Expected: PASS.
