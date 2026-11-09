# NDR10+ and NIVUKX Vision Original Profiles Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make NDR10+ and NIVUKX Vision first-class NeoProCamX profiles, not HDR10+ or Dolby Vision aliases, while keeping Android HDR transport metadata truthful.

**Architecture:** Separate Neo profile identity from Android transport identity. `HdrImagingMath` owns original Neo profile math, `RecordingHdrVideoLookPolicy` owns encoder transport decisions, `NeoProfileTruthMetadataPolicy` owns truthful reporting, and focused tests prevent misleading labels or alias behavior.

**Tech Stack:** Kotlin, Android Camera2/MediaCodec metadata, Gradle unit tests, existing NeoProCamX color/recording policy classes.

---

### Task 1: Lock Truthful Metadata Labels

**Files:**
- Modify: `app/src/main/java/com/neoproc/neoproccamx/recording/NeoProfileTruthMetadata.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/recording/NeoProfileTruthMetadataTest.kt`

- [ ] **Step 1: Write the failing metadata test**

Add a test class that resolves HDR metadata for NDR10+ and NIVUKX Vision and asserts:

```kotlin
assertEquals("NDR10+", ndr.selectedProfile)
assertEquals("ANDROID_PQ_BT2020_MAIN10", ndr.activeTransport)
assertFalse(ndr.activeTransport.contains("HDR10+"))
assertFalse(ndr.selectedProfile.contains("HDR10"))

assertEquals("NIVUKX Vision", vision.selectedProfile)
assertEquals("ANDROID_PQ_BT2020_MAIN10", vision.activeTransport)
assertFalse(vision.activeTransport.contains("Dolby", ignoreCase = true))
assertFalse(vision.selectedProfile.contains("Dolby", ignoreCase = true))
```

- [ ] **Step 2: Run the focused test and verify it fails**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.recording.NeoProfileTruthMetadataTest" --no-daemon --console=plain
```

Expected: fail because the current transport label says `HDR10+/PQ`.

- [ ] **Step 3: Update metadata policy**

Change `NeoProfileTruthMetadataPolicy.activeTransport` so NDR10+ and NIVUKX Vision report `ANDROID_PQ_BT2020_MAIN10`, not `HDR10+/PQ`. Keep Android constants unchanged in Camera2/MediaCodec code.

- [ ] **Step 4: Run the focused test and verify it passes**

Run the same Gradle command. Expected: pass.

### Task 2: Upgrade Neo HDR Profile Math

**Files:**
- Modify: `core-math/src/main/kotlin/com/neoproc/neoproccamx/recording/HdrImagingMath.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/recording/HdrImagingMathTest.kt`

- [ ] **Step 1: Add failing math assertions**

Extend the HDR math test to assert:

```kotlin
val ndr = HdrImagingMath.resolveUltraHdrVersionProfile(HdrImagingMath.HdrDeliveryVersion.NDR10_PLUS)
val vision = HdrImagingMath.resolveUltraHdrVersionProfile(HdrImagingMath.HdrDeliveryVersion.NIVUKX_VISION)
val hdr10Plus = HdrImagingMath.resolveUltraHdrVersionProfile(HdrImagingMath.HdrDeliveryVersion.HDR10_PLUS)
val dolby = HdrImagingMath.resolveUltraHdrVersionProfile(HdrImagingMath.HdrDeliveryVersion.DOLBY_VISION)

assertEquals("NeoPQ", ndr.transferName)
assertEquals("NeoVisionPQ", vision.transferName)
assertTrue(ndr.targetPeakNits > hdr10Plus.targetPeakNits)
assertTrue(vision.targetPeakNits > dolby.targetPeakNits)
assertTrue(vision.colorVolumeCompression > ndr.colorVolumeCompression)
assertTrue(vision.dynamicMetadata)
```

- [ ] **Step 2: Run the focused math test and verify it fails**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.recording.HdrImagingMathTest" --no-daemon --console=plain
```

Expected: fail because transfer names still resemble generic NDR/NIVUKX labels.

- [ ] **Step 3: Update NDR10+ and NIVUKX Vision math profiles**

Set NDR10+ `transferName` to `NeoPQ`, raise target peak, keep encoded bit depth truthful at 10, and tune compression/saturation to be stronger than HDR10+ without using HDR10+ as a label.

Set NIVUKX Vision `transferName` to `NeoVisionPQ`, raise target peak above Dolby fallback, increase color-volume protection, and keep dynamic metadata enabled.

- [ ] **Step 4: Run the focused math test and verify it passes**

Run the same Gradle command. Expected: pass.

### Task 3: Remove Misleading Recording Labels

**Files:**
- Modify: `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingHdrVideoLookPolicy.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/recording/RecordingHdrVideoLookPolicyTest.kt`

- [ ] **Step 1: Add failing recording-policy assertions**

Assert NDR10+ and NIVUKX Vision normalize to Neo profile names, require Main10 on supported HEVC, and do not surface HDR10+ or Dolby names:

```kotlin
val ndr = RecordingHdrVideoLookPolicy.resolveLookProfile(
    mime = "video/hevc",
    videoHdrMode = "NDR10+",
    videoColorSpace = "BT.2020",
    sdkInt = 35
)
assertEquals("NDR10+", ndr.requestedDynamicRangeLabel)
assertTrue(ndr.colorEncoding.requiresTenBit)

val vision = RecordingHdrVideoLookPolicy.resolveLookProfile(
    mime = "video/hevc",
    videoHdrMode = "NIVUKX Vision",
    videoColorSpace = "BT.2020",
    sdkInt = 35
)
assertEquals("NIVUKX Vision", vision.requestedDynamicRangeLabel)
assertFalse(vision.requestedDynamicRangeLabel.contains("Dolby", ignoreCase = true))
```

- [ ] **Step 2: Run the focused recording-policy test and verify it fails if labels are misleading**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.recording.RecordingHdrVideoLookPolicyTest" --no-daemon --console=plain
```

- [ ] **Step 3: Update recording policy labels**

Keep `MediaFormat.COLOR_TRANSFER_ST2084` for NDR10+ and NIVUKX Vision, but remove any requested labels that imply Dolby fallback or HDR10+ identity for Neo profiles.

- [ ] **Step 4: Run focused recording-policy test**

Expected: pass.

### Task 4: Verify Preview Render Policy Superiority Ordering

**Files:**
- Modify: `app/src/main/java/com/neoproc/neoproccamx/pipeline/HdrVersionRenderPolicy.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/pipeline/HdrVersionRenderPolicyTest.kt`

- [ ] **Step 1: Add failing render-policy ordering test**

Assert:

```kotlin
val ndr = HdrVersionRenderPolicy.resolve("NDR10+", hdrEnabled = true, recording = true)!!
val vision = HdrVersionRenderPolicy.resolve("NIVUKX Vision", hdrEnabled = true, recording = true)!!

assertEquals("NDR10+", ndr.versionLabel)
assertEquals("NIVUKX Vision", vision.versionLabel)
assertTrue(vision.fusionFloor >= ndr.fusionFloor)
assertTrue(vision.chromaDenoiseFloor >= ndr.chromaDenoiseFloor)
assertTrue(vision.highlightCompressionFloor >= ndr.highlightCompressionFloor)
```

- [ ] **Step 2: Run the focused render-policy test**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.pipeline.HdrVersionRenderPolicyTest" --no-daemon --console=plain
```

- [ ] **Step 3: Adjust policy only if the ordering test fails**

Keep NDR10+ and NIVUKX Vision labels exact. Make NIVUKX Vision the stronger profile for fusion, chroma denoise, and highlight compression.

- [ ] **Step 4: Run focused render-policy test**

Expected: pass.

### Task 5: Full Verification

**Files:**
- Verify all modified production and test files.

- [ ] **Step 1: Run focused profile tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*HdrImagingMathTest" --tests "*RecordingHdrVideoLookPolicyTest" --tests "*NeoProfileTruthMetadataTest" --tests "*HdrVersionRenderPolicyTest" --no-daemon --console=plain
```

Expected: build successful.

- [ ] **Step 2: Run full debug unit suite**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon --console=plain
```

Expected: build successful.

- [ ] **Step 3: Build debug APK**

Run:

```powershell
.\gradlew.bat :app:assembleDebug --no-daemon --console=plain
```

Expected: build successful.
