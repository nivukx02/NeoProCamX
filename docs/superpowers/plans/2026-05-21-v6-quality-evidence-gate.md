# V6 Quality Evidence Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a strict V6 quality certificate that refuses to claim production 10/10 unless mode truth, preview/recording parity, file metadata, device logs, and frame stability evidence all pass.

**Architecture:** Add pure Kotlin policy files under `app/src/main/java/com/neoproc/neoproccamx/recording` so the quality gate is testable without Android runtime. The evidence policy consumes existing V5 capture truth/parity signatures plus ffprobe/MediaInfo-style metadata and produces PASS/FAIL/NOT_PROVEN with explicit gate failures.

**Tech Stack:** Kotlin, JUnit4, Android MediaFormat constants through existing policies, PowerShell adb/ffprobe script for device validation.

---

### Task 1: V6 Evidence Policy Tests

**Files:**
- Create: `app/src/test/java/com/neoproc/neoproccamx/recording/RecordingQualityEvidencePolicyTest.kt`

- [ ] **Step 1: Write failing tests for strict pass, wrong tags, missing proof, and NPV10 truth**

```kotlin
@Test
fun `strict pass requires matching app probe log and parity evidence`() {
    val certificate = RecordingQualityEvidencePolicy.evaluate(validNdrRequest())
    assertEquals(RecordingQualityEvidenceStatus.PASS, certificate.status)
    assertTrue(certificate.failures.isEmpty())
}

@Test
fun `normal and log fail when probe metadata is hdr tagged`() {
    val normal = RecordingQualityEvidencePolicy.evaluate(validSdrRequest().copy(probe = hdrProbe()))
    val log = RecordingQualityEvidencePolicy.evaluate(validLogRequest().copy(probe = hdrProbe()))
    assertTrue(normal.failures.any { it.code == RecordingQualityGateCode.NORMAL_OR_PRO_SAVED_AS_HDR })
    assertTrue(log.failures.any { it.code == RecordingQualityGateCode.LOG_SAVED_AS_HDR })
}

@Test
fun `metadata match without ffprobe and device logs remains not proven`() {
    val certificate = RecordingQualityEvidencePolicy.evaluate(
        validNdrRequest().copy(probe = null, deviceLogValidated = false)
    )
    assertEquals(RecordingQualityEvidenceStatus.NOT_PROVEN, certificate.status)
    assertTrue(certificate.failures.any { it.code == RecordingQualityGateCode.MISSING_RECORDED_FILE_PROOF })
}

@Test
fun `npv10 fallback cannot be labeled native ten bit`() {
    val certificate = RecordingQualityEvidencePolicy.evaluate(
        validNpv10Request().copy(
            npv10PayloadFormat = "YUV420_8BIT_EXPANDED",
            npv10NativeTenBitClaimed = true,
            sourceBitDepth = 8
        )
    )
    assertEquals(RecordingQualityEvidenceStatus.FAIL, certificate.status)
    assertTrue(certificate.failures.any { it.code == RecordingQualityGateCode.NPV10_FALLBACK_MISLABELED_NATIVE_10BIT })
}
```

- [ ] **Step 2: Run focused test and confirm RED**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.recording.RecordingQualityEvidencePolicyTest" --no-daemon --no-configuration-cache --console=plain`

Expected: FAIL because `RecordingQualityEvidencePolicy` and related types do not exist.

### Task 2: V6 Probe Validation Tests

**Files:**
- Create: `app/src/test/java/com/neoproc/neoproccamx/recording/RecordingProbeValidationPolicyTest.kt`

- [ ] **Step 1: Write failing tests for ffprobe-style maps and text**

```kotlin
@Test
fun `parses ffprobe main10 pq bt2020 fields`() {
    val probe = RecordingProbeValidationPolicy.fromFields(
        mapOf(
            "codec_name" to "hevc",
            "pix_fmt" to "yuv420p10le",
            "bits_per_raw_sample" to "10",
            "color_space" to "bt2020nc",
            "color_transfer" to "smpte2084",
            "color_primaries" to "bt2020",
            "width" to "3840",
            "height" to "2160",
            "avg_frame_rate" to "60000/1000",
            "bit_rate" to "220000000"
        )
    )
    assertEquals("hevc", probe.codec)
    assertEquals(10, probe.encodedBitDepth)
    assertEquals("BT.2020", probe.primaries)
    assertEquals("ST2084/PQ", probe.transfer)
    assertEquals(60, probe.fps)
}
```

- [ ] **Step 2: Run focused test and confirm RED**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.recording.RecordingProbeValidationPolicyTest" --no-daemon --no-configuration-cache --console=plain`

Expected: FAIL because `RecordingProbeValidationPolicy` does not exist.

### Task 3: Implement Pure V6 Policies

**Files:**
- Create: `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingProbeValidationPolicy.kt`
- Create: `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingQualityEvidencePolicy.kt`

- [ ] **Step 1: Implement `RecordingProbeValidationPolicy`**

Create a parser that normalizes codec, pixel format, bit depth, primaries, transfer, matrix, dimensions, fps, and bitrate from ffprobe/MediaInfo-style key/value fields.

- [ ] **Step 2: Implement `RecordingQualityEvidencePolicy`**

Create request/certificate/failure data classes and gates for SDR/HDR/LOG/RAW/NPV10 truth, preview/recording parity, resolution/FPS, bit depth, color metadata, dropped frames, and missing probe/log evidence.

- [ ] **Step 3: Run focused tests and confirm GREEN**

Run both new test classes with `:app:testDebugUnitTest`.

### Task 4: Add V6 Metadata Hook

**Files:**
- Modify: `app/src/main/java/com/neoproc/neoproccamx/recording/EliteVideoQualityContract.kt`
- Modify: `app/src/test/java/com/neoproc/neoproccamx/recording/EliteVideoQualityContractTest.kt`

- [ ] **Step 1: Add failing metadata test**

Test that `toMetadata()` includes `strict10QualityRequiresV6Evidence=true`, `strict10DeviceVerified=false`, and `qualityEvidenceGeneration=V6_QUALITY_EVIDENCE_GATE`.

- [ ] **Step 2: Implement metadata fields without claiming device verification**

Add metadata keys only. Do not change `auditStatus` to strict 10/10 based on labels.

### Task 5: Add Device Validation Script

**Files:**
- Create: `tools/quality/record-v6-quality.ps1`

- [ ] **Step 1: Add adb/ffprobe validation script**

The script finds adb, records phase6 rows through existing debug broadcasts, captures logcat, pulls MediaStore-visible files when possible, runs ffprobe if installed, and writes a `quality-report.txt` with PASS/FAIL/NOT_PROVEN per row.

### Task 6: Verification

**Files:**
- No code changes.

- [ ] **Step 1: Run focused V6 tests**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.recording.RecordingQualityEvidencePolicyTest" --tests "com.neoproc.neoproccamx.recording.RecordingProbeValidationPolicyTest" --tests "com.neoproc.neoproccamx.recording.EliteVideoQualityContractTest" --no-daemon --no-configuration-cache --console=plain`

- [ ] **Step 2: Run full unit tests**

Run: `.\gradlew.bat :app:testDebugUnitTest --no-daemon --no-configuration-cache --console=plain`

- [ ] **Step 3: Build debug APK**

Run: `.\gradlew.bat :app:assembleDebug --no-daemon --no-configuration-cache --console=plain`
