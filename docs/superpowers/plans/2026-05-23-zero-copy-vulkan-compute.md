# Zero-Copy Vulkan Compute Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a truthful native Vulkan GPU-processing contract for NeoProCamX preview, recording, and NPV10 export enhancement without exposing private-vendor color science.

**Architecture:** Keep the existing `VulkanBridge` and `VulkanRuntimeManager` architecture. Add a packed Kotlin GPU-uniform contract, JNI bridge updates, shader contract files, capability/fallback reporting, and NPV10 export evidence so real processing stages can be moved to Vulkan compute while unsupported paths remain truthfully labeled.

**Tech Stack:** Kotlin, JUnit4, Android Camera2/HardwareBuffer, JNI, Android NDK C++20, Vulkan, GLSL compute shader source, Gradle.

---

## File Structure

- Create `app/src/main/java/com/neoproc/neoproccamx/rendering/GpuImageEnhancementContract.kt`
  - Owns GPU enums, packed uniforms, vendor-free profile normalization, and deterministic signatures.
- Create `app/src/test/java/com/neoproc/neoproccamx/rendering/GpuImageEnhancementContractTest.kt`
  - Verifies uniform packing, clamping, profile normalization, NPV10 profile mapping, and signatures.
- Modify `app/src/main/java/com/neoproc/neoproccamx/rendering/VulkanBridge.kt`
  - Adds `updateEnhancementUniforms` and `enhancementCapabilitySummary`.
- Modify `app/src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt`
  - Batches enhancement-uniform updates alongside existing render config updates.
- Modify `app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt`
  - Pushes the current scene render profile into the Vulkan enhancement contract from the existing render-config path.
- Modify `app/src/main/cpp/vulkan_bridge.cpp`
  - Stores the packed enhancement uniform block, validates JNI inputs, exposes processing capability truth, and includes contract status in the native capability summary.
- Create `app/src/main/cpp/shaders/vulkan/neo_p010_export_enhance.comp`
  - Adds the compute shader source contract for P010 temporal/chroma/detail processing.
- Create `app/src/test/java/com/neoproc/neoproccamx/rendering/VulkanEnhancementShaderContractTest.kt`
  - Verifies the shader source exposes the required bindings, uniforms, and processing order.
- Modify `app/src/main/java/com/neoproc/neoproccamx/recording/Npv10MovConverter.kt`
  - Adds export processing path evidence and routes through a GPU accelerator gate when available.
- Modify `app/src/main/java/com/neoproc/neoproccamx/recording/Npv10IntegrityValidator.kt`
  - Adds certificate fields for export processing path, zero-copy truth, GPU fallback reason, and GPU signature.
- Modify `app/src/test/java/com/neoproc/neoproccamx/recording/Npv10MovConverterTest.kt`
  - Verifies CPU fallback is truthful and not mislabeled as zero-copy/GPU native.
- Modify `app/src/test/java/com/neoproc/neoproccamx/rendering/VulkanPreviewShaderContractTest.kt`
  - Updates log-profile assertions to require Neo-owned profile behavior and reject private-vendor product claims.

---

### Task 1: GPU Uniform Contract and Vendor-Free Normalization

**Files:**
- Create: `app/src/main/java/com/neoproc/neoproccamx/rendering/GpuImageEnhancementContract.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/rendering/GpuImageEnhancementContractTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `GpuImageEnhancementContractTest.kt`:

```kotlin
package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.pipeline.SceneRenderProfile
import com.neoproc.neoproccamx.recording.Npv10ExportEnhancementProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuImageEnhancementContractTest {
    private val profile = SceneRenderProfile(
        temporalBlend = 0.42f,
        spatialDenoise = 0.73f,
        chromaDenoise = 0.96f,
        shadowBoost = 0.25f,
        sharpening = 0.31f,
        warmth = 0.02f,
        contrast = 1.01f,
        saturation = 0.99f,
        highlightCompression = 0.35f,
        logStrength = 0.68f,
        ditherStrength = 0f,
        videoHdrFusionStrength = 0.82f
    )

    @Test
    fun packsSceneAndNpv10ProfileIntoStableThirtyTwoFloatUniformBlock() {
        val uniforms = GpuImageEnhancementUniforms.fromSceneProfile(
            profile = profile,
            signalFamily = GpuSignalFamily.LOG,
            colorSpaceName = "Rec.2020-N-Log",
            colorScienceName = "N-Log Cinema",
            activeTransport = GpuActiveTransport.BT2020_LOG_SDR,
            exportProfile = Npv10ExportEnhancementProfile.LOG_VIDEO,
            iso = 1600f,
            noiseMetric = 0.48f
        )

        val packed = uniforms.toFloatArray()

        assertEquals(32, packed.size)
        assertEquals(GpuSignalFamily.LOG.id.toFloat(), packed[0], 0.0001f)
        assertEquals(GpuColorSpace.REC2020.id.toFloat(), packed[1], 0.0001f)
        assertEquals(GpuColorScience.N_LOG_CINEMA.id.toFloat(), packed[2], 0.0001f)
        assertEquals(GpuActiveTransport.BT2020_LOG_SDR.id.toFloat(), packed[3], 0.0001f)
        assertEquals(0.42f, packed[4], 0.0001f)
        assertEquals(0.73f, packed[5], 0.0001f)
        assertEquals(0.96f, packed[6], 0.0001f)
        assertEquals(Npv10ExportEnhancementProfile.LOG_VIDEO.aliasSafeMaxDetailDelta / 1023f, packed[16], 0.0001f)
        assertTrue(uniforms.signature.startsWith("gpu-v1|signal=LOG|space=REC2020|science=N_LOG_CINEMA"))
    }

    @Test
    fun privateVendorProfileNamesAreMigrationAliasesOnly() {
        val normalized = GpuColorProfileNormalizer.normalize(
            colorSpaceName = "ARRI AWG4",
            colorScienceName = "Sony S-Log3"
        )

        assertEquals(GpuColorSpace.NEO_WIDE_GAMUT, normalized.colorSpace)
        assertEquals(GpuColorScience.N_LOG_CINEMA, normalized.colorScience)
        assertTrue(normalized.migrationApplied)
        assertFalse(normalized.exportLabel.contains("ARRI", ignoreCase = true))
        assertFalse(normalized.exportLabel.contains("Sony", ignoreCase = true))
        assertFalse(normalized.exportLabel.contains("RED", ignoreCase = true))
    }

    @Test
    fun ndrAndNivukxMapToNeoHdrProfilesWithBt2020PqTransport() {
        val ndr = GpuColorProfileNormalizer.normalize("Rec.2020-NDR10+", "NDR10+")
        val nivukx = GpuColorProfileNormalizer.normalize("Rec.2020-NIVUKX", "NIVUKX Vision")

        assertEquals(GpuColorScience.NDR10_PLUS, ndr.colorScience)
        assertEquals(GpuColorScience.NIVUKX_VISION, nivukx.colorScience)
        assertEquals(GpuActiveTransport.ANDROID_PQ_BT2020_MAIN10, ndr.recommendedTransport)
        assertEquals(GpuActiveTransport.ANDROID_PQ_BT2020_MAIN10, nivukx.recommendedTransport)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.rendering.GpuImageEnhancementContractTest" --console=plain --no-daemon --no-configuration-cache
```

Expected: FAIL with unresolved references for `GpuImageEnhancementUniforms`, `GpuSignalFamily`, `GpuActiveTransport`, `GpuColorProfileNormalizer`, `GpuColorSpace`, and `GpuColorScience`.

- [ ] **Step 3: Add the contract implementation**

Create `GpuImageEnhancementContract.kt`:

```kotlin
package com.neoproc.neoproccamx.rendering

import com.neoproc.neoproccamx.pipeline.SceneRenderProfile
import com.neoproc.neoproccamx.recording.Npv10ExportEnhancementProfile
import java.util.Locale

enum class GpuSignalFamily(val id: Int) { SDR(1), HDR(2), LOG(3), RAW(4) }
enum class GpuColorSpace(val id: Int) { REC709(1), REC2020(2), NEO_WIDE_GAMUT(3) }
enum class GpuColorScience(val id: Int) {
    REC709_STANDARD(1),
    N_LOG_BASE(10),
    N_LOG_SHADOW(11),
    N_LOG_HIGHLIGHT(12),
    N_LOG_CINEMA(13),
    NDR10_PLUS(20),
    NIVUKX_VISION(21),
    NATURAL(30)
}
enum class GpuActiveTransport(val id: Int) {
    REC709_SDR(1),
    BT2020_SDR(2),
    BT2020_LOG_SDR(3),
    ANDROID_PQ_BT2020_MAIN10(4),
    RAW_MONITOR(5)
}

data class GpuColorProfileNormalization(
    val colorSpace: GpuColorSpace,
    val colorScience: GpuColorScience,
    val recommendedTransport: GpuActiveTransport,
    val migrationApplied: Boolean,
    val exportLabel: String
)

object GpuColorProfileNormalizer {
    fun normalize(colorSpaceName: String?, colorScienceName: String?): GpuColorProfileNormalization {
        val combined = listOf(colorSpaceName.orEmpty(), colorScienceName.orEmpty())
            .joinToString(separator = " ")
            .lowercase(Locale.US)
        val migrated = containsPrivateVendorToken(combined)
        val colorScience = when {
            combined.contains("nivukx") -> GpuColorScience.NIVUKX_VISION
            combined.contains("ndr10") -> GpuColorScience.NDR10_PLUS
            combined.contains("n-log cinema") || migrated -> GpuColorScience.N_LOG_CINEMA
            combined.contains("n-log shadow") -> GpuColorScience.N_LOG_SHADOW
            combined.contains("n-log highlight") -> GpuColorScience.N_LOG_HIGHLIGHT
            combined.contains("n-log") || combined.contains("log") -> GpuColorScience.N_LOG_BASE
            combined.contains("natural") -> GpuColorScience.NATURAL
            else -> GpuColorScience.REC709_STANDARD
        }
        val colorSpace = when {
            combined.contains("2020") || combined.contains("2100") -> GpuColorSpace.REC2020
            combined.contains("neo wide") || combined.contains("wide gamut") || migrated -> GpuColorSpace.NEO_WIDE_GAMUT
            else -> GpuColorSpace.REC709
        }
        val transport = when (colorScience) {
            GpuColorScience.NDR10_PLUS,
            GpuColorScience.NIVUKX_VISION -> GpuActiveTransport.ANDROID_PQ_BT2020_MAIN10
            GpuColorScience.N_LOG_BASE,
            GpuColorScience.N_LOG_SHADOW,
            GpuColorScience.N_LOG_HIGHLIGHT,
            GpuColorScience.N_LOG_CINEMA -> GpuActiveTransport.BT2020_LOG_SDR
            else -> if (colorSpace == GpuColorSpace.REC2020 || colorSpace == GpuColorSpace.NEO_WIDE_GAMUT) {
                GpuActiveTransport.BT2020_SDR
            } else {
                GpuActiveTransport.REC709_SDR
            }
        }
        return GpuColorProfileNormalization(
            colorSpace = colorSpace,
            colorScience = colorScience,
            recommendedTransport = transport,
            migrationApplied = migrated,
            exportLabel = "${colorSpace.name}:${colorScience.name}"
        )
    }

    private fun containsPrivateVendorToken(value: String): Boolean =
        listOf("arri", "sony", "red", "dolby", "aces", "display p3", "dci-p3", "s-log", "logc", "log3g")
            .any { value.contains(it) }
}

data class GpuImageEnhancementUniforms(
    val signalFamily: GpuSignalFamily,
    val colorSpace: GpuColorSpace,
    val colorScience: GpuColorScience,
    val activeTransport: GpuActiveTransport,
    val temporalBlend: Float,
    val spatialDenoise: Float,
    val chromaDenoise: Float,
    val shadowBoost: Float,
    val sharpening: Float,
    val warmth: Float,
    val contrast: Float,
    val saturation: Float,
    val highlightCompression: Float,
    val logStrength: Float,
    val videoHdrFusionStrength: Float,
    val shadowChromaDeadband: Float,
    val aliasSafeMaxDetailDelta: Float,
    val fineTextureThreshold: Float,
    val mediumEdgeThreshold: Float,
    val strongEdgeThreshold: Float,
    val fineTextureDetailGain: Float,
    val mediumDetailGain: Float,
    val strongDetailGain: Float,
    val temporalLumaBlend: Float,
    val temporalChromaBlend: Float,
    val iso: Float,
    val noiseMetric: Float,
    val signature: String
) {
    fun toFloatArray(): FloatArray = floatArrayOf(
        signalFamily.id.toFloat(),
        colorSpace.id.toFloat(),
        colorScience.id.toFloat(),
        activeTransport.id.toFloat(),
        temporalBlend,
        spatialDenoise,
        chromaDenoise,
        shadowBoost,
        sharpening,
        warmth,
        contrast,
        saturation,
        highlightCompression,
        logStrength,
        videoHdrFusionStrength,
        shadowChromaDeadband,
        aliasSafeMaxDetailDelta,
        fineTextureThreshold,
        mediumEdgeThreshold,
        strongEdgeThreshold,
        fineTextureDetailGain,
        mediumDetailGain,
        strongDetailGain,
        temporalLumaBlend,
        temporalChromaBlend,
        (iso / 12_800f).coerceIn(0f, 1f),
        noiseMetric.coerceIn(0f, 1f),
        0f, 0f, 0f, 0f, 0f
    )

    companion object {
        fun fromSceneProfile(
            profile: SceneRenderProfile,
            signalFamily: GpuSignalFamily,
            colorSpaceName: String,
            colorScienceName: String,
            activeTransport: GpuActiveTransport? = null,
            exportProfile: Npv10ExportEnhancementProfile = Npv10ExportEnhancementProfile.DEFAULT,
            iso: Float = 100f,
            noiseMetric: Float = profile.noiseMetrics.lumaNoise
        ): GpuImageEnhancementUniforms {
            val normalized = GpuColorProfileNormalizer.normalize(colorSpaceName, colorScienceName)
            val transport = activeTransport ?: normalized.recommendedTransport
            val signature = listOf(
                "gpu-v1",
                "signal=${signalFamily.name}",
                "space=${normalized.colorSpace.name}",
                "science=${normalized.colorScience.name}",
                "transport=${transport.name}",
                "tb=${profile.temporalBlend.format4()}",
                "sd=${profile.spatialDenoise.format4()}",
                "cd=${profile.chromaDenoise.format4()}",
                "sh=${profile.sharpening.format4()}",
                "ad=${exportProfile.aliasSafeMaxDetailDelta}"
            ).joinToString(separator = "|")
            return GpuImageEnhancementUniforms(
                signalFamily = signalFamily,
                colorSpace = normalized.colorSpace,
                colorScience = normalized.colorScience,
                activeTransport = transport,
                temporalBlend = profile.temporalBlend.coerceIn(0f, 1f),
                spatialDenoise = profile.spatialDenoise.coerceIn(0f, 1f),
                chromaDenoise = profile.chromaDenoise.coerceIn(0f, 1f),
                shadowBoost = profile.shadowBoost.coerceIn(0f, 1f),
                sharpening = profile.sharpening.coerceIn(0f, 1f),
                warmth = profile.warmth.coerceIn(-1f, 1f),
                contrast = profile.contrast.coerceIn(0f, 2f),
                saturation = profile.saturation.coerceIn(0f, 2f),
                highlightCompression = profile.highlightCompression.coerceIn(0f, 1f),
                logStrength = profile.logStrength.coerceIn(0f, 1f),
                videoHdrFusionStrength = profile.videoHdrFusionStrength.coerceIn(0f, 1f),
                shadowChromaDeadband = exportProfile.shadowChromaDeadband / 1023f,
                aliasSafeMaxDetailDelta = exportProfile.aliasSafeMaxDetailDelta / 1023f,
                fineTextureThreshold = exportProfile.fineTextureThreshold / 1023f,
                mediumEdgeThreshold = exportProfile.mediumEdgeThreshold / 1023f,
                strongEdgeThreshold = exportProfile.strongEdgeThreshold / 1023f,
                fineTextureDetailGain = exportProfile.fineTextureDetailGain.toFloat().coerceIn(0f, 2f),
                mediumDetailGain = exportProfile.mediumDetailGain.toFloat().coerceIn(0f, 2f),
                strongDetailGain = exportProfile.strongDetailGain.toFloat().coerceIn(0f, 2f),
                temporalLumaBlend = exportProfile.temporalLumaBlend.toFloat().coerceIn(0f, 1f),
                temporalChromaBlend = exportProfile.temporalChromaBlend.toFloat().coerceIn(0f, 1f),
                iso = iso.coerceAtLeast(0f),
                noiseMetric = noiseMetric.coerceIn(0f, 1f),
                signature = signature
            )
        }
    }
}

private fun Float.format4(): String = String.format(Locale.US, "%.4f", this)
```

- [ ] **Step 4: Run test to verify it passes**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.rendering.GpuImageEnhancementContractTest" --console=plain --no-daemon --no-configuration-cache
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/neoproc/neoproccamx/rendering/GpuImageEnhancementContract.kt app/src/test/java/com/neoproc/neoproccamx/rendering/GpuImageEnhancementContractTest.kt
git commit -m "Add GPU enhancement uniform contract"
```

---

### Task 2: VulkanBridge and Runtime Uniform Updates

**Files:**
- Modify: `app/src/main/java/com/neoproc/neoproccamx/rendering/VulkanBridge.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/rendering/GpuImageEnhancementContractTest.kt`

- [ ] **Step 1: Add failing validation tests for uniform packet size**

Append to `GpuImageEnhancementContractTest.kt`:

```kotlin
@Test
fun uniformPacketUsesNativeBridgeExpectedSize() {
    val uniforms = GpuImageEnhancementUniforms.fromSceneProfile(
        profile = profile,
        signalFamily = GpuSignalFamily.SDR,
        colorSpaceName = "Rec.709",
        colorScienceName = "Rec.709 Standard",
        activeTransport = GpuActiveTransport.REC709_SDR
    )

    assertEquals(VulkanBridge.ENHANCEMENT_UNIFORM_FLOAT_COUNT, uniforms.toFloatArray().size)
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.rendering.GpuImageEnhancementContractTest.uniformPacketUsesNativeBridgeExpectedSize" --console=plain --no-daemon --no-configuration-cache
```

Expected: FAIL because `ENHANCEMENT_UNIFORM_FLOAT_COUNT` does not exist.

- [ ] **Step 3: Add Kotlin bridge methods**

Modify `VulkanBridge.kt`:

```kotlin
class VulkanBridge : Closeable {
    companion object {
        const val ENHANCEMENT_UNIFORM_FLOAT_COUNT = 32
        init {
            System.loadLibrary("neoproc_vulkan")
        }
    }

    fun updateEnhancementUniforms(uniforms: GpuImageEnhancementUniforms): Boolean {
        val handle = nativeHandle
        if (handle == 0L) return false
        val packed = uniforms.toFloatArray()
        require(packed.size == ENHANCEMENT_UNIFORM_FLOAT_COUNT) {
            "enhancement uniforms must contain $ENHANCEMENT_UNIFORM_FLOAT_COUNT floats"
        }
        require(packed.all { it.isFinite() }) { "enhancement uniforms contain non-finite values" }
        return nativeUpdateEnhancementUniforms(handle, packed, uniforms.signature)
    }

    fun enhancementCapabilitySummary(): String {
        val handle = nativeHandle
        if (handle == 0L) return "gpuEnhancement=released"
        summaryBuffer.clear()
        val length = nativeGetEnhancementCapabilitySummary(handle, summaryBuffer)
        summaryBuffer.position(0)
        summaryBuffer.limit(length)
        summaryBuffer.get(summaryBytes, 0, length)
        return String(summaryBytes, 0, length, Charsets.UTF_8)
    }

    private external fun nativeUpdateEnhancementUniforms(
        handle: Long,
        uniforms: FloatArray,
        signature: String
    ): Boolean

    private external fun nativeGetEnhancementCapabilitySummary(handle: Long, buffer: ByteBuffer): Int
}
```

Modify `VulkanRuntimeManager.kt` by adding state and methods:

```kotlin
@Volatile private var enhancementUniforms: GpuImageEnhancementUniforms? = null
@Volatile private var enhancementDirty = true
@Volatile private var lastEnhancementSignature = ""

fun updateEnhancementUniforms(uniforms: GpuImageEnhancementUniforms) {
    enhancementUniforms = uniforms
    enhancementDirty = true
    configGenerationToken.incrementAndGet()
}

private fun pushEnhancementUniformsIfNeeded() {
    val uniforms = enhancementUniforms ?: return
    if (!enhancementDirty && uniforms.signature == lastEnhancementSignature) return
    if (bridge.updateEnhancementUniforms(uniforms)) {
        enhancementDirty = false
        lastEnhancementSignature = uniforms.signature
        _capabilitySummary.value = bridge.capabilitySummary() + " " + bridge.enhancementCapabilitySummary()
    }
}
```

Call `pushEnhancementUniformsIfNeeded()` immediately before `bridge.submitHardwareBuffer(...)` inside `submitCameraImage`.

- [ ] **Step 4: Run test to verify Kotlin contract passes**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.rendering.GpuImageEnhancementContractTest" --console=plain --no-daemon --no-configuration-cache
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/neoproc/neoproccamx/rendering/VulkanBridge.kt app/src/main/java/com/neoproc/neoproccamx/rendering/VulkanRuntimeManager.kt app/src/test/java/com/neoproc/neoproccamx/rendering/GpuImageEnhancementContractTest.kt
git commit -m "Wire GPU enhancement uniforms into Vulkan runtime"
```

---

### Task 3: Native JNI Uniform Storage and Capability Truth

**Files:**
- Modify: `app/src/main/cpp/vulkan_bridge.cpp`
- Test: `app/src/test/java/com/neoproc/neoproccamx/rendering/VulkanEnhancementShaderContractTest.kt`

- [ ] **Step 1: Add a source-level native contract test**

Create `VulkanEnhancementShaderContractTest.kt`:

```kotlin
package com.neoproc.neoproccamx.rendering

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VulkanEnhancementShaderContractTest {
    private val nativeSource = File("src/main/cpp/vulkan_bridge.cpp").readText()

    @Test
    fun nativeBridgeStoresEnhancementUniformsAndReportsTruthfulCapability() {
        assertTrue(nativeSource.contains("struct EnhancementUniforms"))
        assertTrue(nativeSource.contains("kEnhancementUniformFloatCount = 32"))
        assertTrue(nativeSource.contains("nativeUpdateEnhancementUniforms"))
        assertTrue(nativeSource.contains("nativeGetEnhancementCapabilitySummary"))
        assertTrue(nativeSource.contains("gpuEnhancement="))
        assertTrue(nativeSource.contains("zeroCopyLiveImport="))
        assertTrue(nativeSource.contains("npv10ExportPath="))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.rendering.VulkanEnhancementShaderContractTest.nativeBridgeStoresEnhancementUniformsAndReportsTruthfulCapability" --console=plain --no-daemon --no-configuration-cache
```

Expected: FAIL because native bridge does not contain the enhancement contract.

- [ ] **Step 3: Add native uniform storage and JNI functions**

Modify `vulkan_bridge.cpp` near `RenderConfig`:

```cpp
constexpr size_t kEnhancementUniformFloatCount = 32;

struct EnhancementUniforms {
    std::array<float, kEnhancementUniformFloatCount> values{};
    std::string signature = "unset";
    bool ready = false;
};
```

Add to `VulkanBridgeState`:

```cpp
EnhancementUniforms enhancementUniforms;
bool enhancementComputePipelineReady = false;
std::string enhancementFallbackReason = "compute pipeline not initialized";
```

Add JNI functions after `nativeUpdateRenderConfig`:

```cpp
extern "C" JNIEXPORT jboolean JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeUpdateEnhancementUniforms(
    JNIEnv* env,
    jobject,
    jlong handle,
    jfloatArray uniforms,
    jstring signature
) {
    auto* state = fromHandle(handle);
    if (state == nullptr || uniforms == nullptr) return JNI_FALSE;
    std::lock_guard<std::mutex> lock(state->mutex);
    if (!state->supported) return JNI_FALSE;
    const jsize length = env->GetArrayLength(uniforms);
    if (length != static_cast<jsize>(kEnhancementUniformFloatCount)) {
        setStatus(state, "enhancement uniforms rejected: wrong float count");
        return JNI_FALSE;
    }
    jfloat values[kEnhancementUniformFloatCount];
    env->GetFloatArrayRegion(uniforms, 0, length, values);
    for (size_t i = 0; i < kEnhancementUniformFloatCount; ++i) {
        if (!std::isfinite(values[i])) {
            setStatus(state, "enhancement uniforms rejected: non-finite value");
            return JNI_FALSE;
        }
        state->enhancementUniforms.values[i] = values[i];
    }
    const char* signatureChars = signature == nullptr ? nullptr : env->GetStringUTFChars(signature, nullptr);
    state->enhancementUniforms.signature = signatureChars == nullptr ? "unset" : signatureChars;
    if (signatureChars != nullptr) {
        env->ReleaseStringUTFChars(signature, signatureChars);
    }
    state->enhancementUniforms.ready = true;
    setStatus(state, "enhancement uniforms ready");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_neoproc_neoproccamx_rendering_VulkanBridge_nativeGetEnhancementCapabilitySummary(
    JNIEnv* env,
    jobject,
    jlong handle,
    jobject bufferObject
) {
    auto* state = fromHandle(handle);
    if (state == nullptr || bufferObject == nullptr) return 0;
    void* bufferAddress = env->GetDirectBufferAddress(bufferObject);
    jlong bufferCapacity = env->GetDirectBufferCapacity(bufferObject);
    if (bufferAddress == nullptr || bufferCapacity <= 0) return 0;
    std::lock_guard<std::mutex> lock(state->mutex);
    std::ostringstream out;
    out << "gpuEnhancement=" << (state->enhancementComputePipelineReady ? "compute-ready" : "contract-ready")
        << " zeroCopyLiveImport=" << (state->getAndroidHardwareBufferProperties != nullptr ? "supported" : "unsupported")
        << " npv10ExportPath=" << (state->enhancementComputePipelineReady ? "vulkan-compute" : "cpu-fallback")
        << " enhancementUniforms=" << (state->enhancementUniforms.ready ? "ready" : "unset")
        << " enhancementSignature=" << state->enhancementUniforms.signature
        << " enhancementFallbackReason=" << state->enhancementFallbackReason;
    const std::string summary = out.str();
    const size_t copyLength = std::min(summary.length(), static_cast<size_t>(bufferCapacity));
    std::memcpy(bufferAddress, summary.data(), copyLength);
    return static_cast<jint>(copyLength);
}
```

- [ ] **Step 4: Run native contract test**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.rendering.VulkanEnhancementShaderContractTest.nativeBridgeStoresEnhancementUniformsAndReportsTruthfulCapability" --console=plain --no-daemon --no-configuration-cache
```

Expected: PASS.

- [ ] **Step 5: Run native build**

Run:

```powershell
.\gradlew.bat :app:assembleDebug --console=plain --no-daemon --no-configuration-cache
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/cpp/vulkan_bridge.cpp app/src/test/java/com/neoproc/neoproccamx/rendering/VulkanEnhancementShaderContractTest.kt
git commit -m "Add native GPU enhancement capability contract"
```

---

### Task 4: Push Scene Render Profiles Into GPU Uniforms

**Files:**
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/rendering/GpuImageEnhancementContractTest.kt`

- [ ] **Step 1: Add test for mode-specific signal mapping helper**

Append to `GpuImageEnhancementContractTest.kt`:

```kotlin
@Test
fun signalFamilyResolverKeepsNormalAndProSdrLogLogAndNeoHdrHdr() {
    assertEquals(GpuSignalFamily.SDR, GpuSignalFamilyResolver.resolve(mode = "VIDEO", logEnabled = false, hdrEnabled = false, rawEnabled = false, profile = "Rec.709"))
    assertEquals(GpuSignalFamily.SDR, GpuSignalFamilyResolver.resolve(mode = "PRO", logEnabled = false, hdrEnabled = false, rawEnabled = false, profile = "Neo Wide Gamut"))
    assertEquals(GpuSignalFamily.LOG, GpuSignalFamilyResolver.resolve(mode = "LOG", logEnabled = true, hdrEnabled = false, rawEnabled = false, profile = "N-Log Cinema"))
    assertEquals(GpuSignalFamily.HDR, GpuSignalFamilyResolver.resolve(mode = "HDR", logEnabled = false, hdrEnabled = true, rawEnabled = false, profile = "NDR10+"))
    assertEquals(GpuSignalFamily.RAW, GpuSignalFamilyResolver.resolve(mode = "RAW", logEnabled = false, hdrEnabled = false, rawEnabled = true, profile = "RAW"))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.rendering.GpuImageEnhancementContractTest.signalFamilyResolverKeepsNormalAndProSdrLogLogAndNeoHdrHdr" --console=plain --no-daemon --no-configuration-cache
```

Expected: FAIL because `GpuSignalFamilyResolver` does not exist.

- [ ] **Step 3: Add resolver**

Append to `GpuImageEnhancementContract.kt`:

```kotlin
object GpuSignalFamilyResolver {
    fun resolve(
        mode: String,
        logEnabled: Boolean,
        hdrEnabled: Boolean,
        rawEnabled: Boolean,
        profile: String
    ): GpuSignalFamily {
        val key = listOf(mode, profile).joinToString(separator = " ").lowercase(Locale.US)
        return when {
            rawEnabled || key.contains("raw") -> GpuSignalFamily.RAW
            logEnabled || key.contains("n-log") -> GpuSignalFamily.LOG
            hdrEnabled || key.contains("ndr10") || key.contains("nivukx") -> GpuSignalFamily.HDR
            else -> GpuSignalFamily.SDR
        }
    }
}
```

- [ ] **Step 4: Wire `CameraViewModel` push path**

Add imports near the existing rendering imports in `CameraViewModel.kt`:

```kotlin
import com.neoproc.neoproccamx.rendering.GpuImageEnhancementUniforms
import com.neoproc.neoproccamx.rendering.GpuSignalFamilyResolver
```

Modify `pushVulkanPreviewRenderConfig(...)` after `vulkanRuntime.updateRenderConfig(...)`:

```kotlin
currentSceneRenderProfile?.let { profile ->
    val colorSpaceName = _videoColorSpace.value
    val colorScienceName = _colorProfile.value
    val signalFamily = GpuSignalFamilyResolver.resolve(
        mode = _uiMode.value.name,
        logEnabled = isVideoLogCaptureEnabled(),
        hdrEnabled = isEffectiveVideoHdrRequested(),
        rawEnabled = isRawVideoModeActive(),
        profile = "$colorSpaceName $colorScienceName"
    )
    val uniforms = GpuImageEnhancementUniforms.fromSceneProfile(
        profile = profile,
        signalFamily = signalFamily,
        colorSpaceName = colorSpaceName,
        colorScienceName = colorScienceName,
        activeTransport = null,
        iso = cameraParams.value.iso.toFloat(),
        noiseMetric = profile.noiseMetrics.lumaNoise
    )
    vulkanRuntime.updateEnhancementUniforms(uniforms)
}
```

- [ ] **Step 5: Run targeted tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.rendering.GpuImageEnhancementContractTest" --console=plain --no-daemon --no-configuration-cache
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/com/neoproc/neoproccamx/rendering/GpuImageEnhancementContract.kt app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt app/src/test/java/com/neoproc/neoproccamx/rendering/GpuImageEnhancementContractTest.kt
git commit -m "Push scene enhancement profile to Vulkan"
```

---

### Task 5: P010 Compute Shader Source Contract

**Files:**
- Create: `app/src/main/cpp/shaders/vulkan/neo_p010_export_enhance.comp`
- Modify: `app/src/test/java/com/neoproc/neoproccamx/rendering/VulkanEnhancementShaderContractTest.kt`

- [ ] **Step 1: Add failing shader-source contract test**

Append to `VulkanEnhancementShaderContractTest.kt`:

```kotlin
@Test
fun p010EnhancementShaderContainsRequiredProcessingOrderAndUniforms() {
    val source = File("src/main/cpp/shaders/vulkan/neo_p010_export_enhance.comp").readText()
    assertTrue(source.contains("layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;"))
    assertTrue(source.contains("layout(set = 0, binding = 0) readonly buffer CurrentP010"))
    assertTrue(source.contains("layout(set = 0, binding = 1) readonly buffer PreviousP010"))
    assertTrue(source.contains("layout(set = 0, binding = 2) writeonly buffer OutputP010"))
    assertTrue(source.contains("struct EnhancementUniforms"))
    assertTrue(source.indexOf("applyTemporalDenoise") < source.indexOf("applyChromaDeadband"))
    assertTrue(source.indexOf("applyChromaDeadband") < source.indexOf("applyLocalChromaSpikeSmoothing"))
    assertTrue(source.indexOf("applyLocalChromaSpikeSmoothing") < source.indexOf("applyEdgeAwareDetailLift"))
    assertTrue(source.contains("aliasSafeMaxDetailDelta"))
    assertTrue(source.contains("shadowChromaDeadband"))
    assertTrue(source.contains("temporalLumaBlend"))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.rendering.VulkanEnhancementShaderContractTest.p010EnhancementShaderContainsRequiredProcessingOrderAndUniforms" --console=plain --no-daemon --no-configuration-cache
```

Expected: FAIL because the shader file does not exist.

- [ ] **Step 3: Add shader source**

Create `neo_p010_export_enhance.comp`:

```glsl
#version 450

layout(local_size_x = 16, local_size_y = 16, local_size_z = 1) in;

layout(set = 0, binding = 0) readonly buffer CurrentP010 {
    uint currentData[];
};

layout(set = 0, binding = 1) readonly buffer PreviousP010 {
    uint previousData[];
};

layout(set = 0, binding = 2) writeonly buffer OutputP010 {
    uint outputData[];
};

layout(push_constant) uniform EnhancementPushConstants {
    uint width;
    uint height;
    uint hasPreviousFrame;
} pushData;

struct EnhancementUniforms {
    float signalFamily;
    float colorSpace;
    float colorScience;
    float activeTransport;
    float temporalBlend;
    float spatialDenoise;
    float chromaDenoise;
    float shadowBoost;
    float sharpening;
    float warmth;
    float contrast;
    float saturation;
    float highlightCompression;
    float logStrength;
    float videoHdrFusionStrength;
    float shadowChromaDeadband;
    float aliasSafeMaxDetailDelta;
    float fineTextureThreshold;
    float mediumEdgeThreshold;
    float strongEdgeThreshold;
    float fineTextureDetailGain;
    float mediumDetailGain;
    float strongDetailGain;
    float temporalLumaBlend;
    float temporalChromaBlend;
    float iso;
    float noiseMetric;
    float reserved0;
    float reserved1;
    float reserved2;
    float reserved3;
    float reserved4;
};

layout(set = 0, binding = 3) readonly buffer UniformBlock {
    EnhancementUniforms uniforms;
};

uint unpackP010(uint packedValue) {
    return clamp(packedValue >> 6u, 0u, 1023u);
}

uint packP010(uint sampleValue) {
    return clamp(sampleValue, 0u, 1023u) << 6u;
}

uint blendSample(uint currentValue, uint previousValue, float weight) {
    float blended = float(currentValue) * (1.0 - weight) + float(previousValue) * weight;
    return uint(clamp(blended + 0.5, 0.0, 1023.0));
}

uint applyTemporalDenoise(uint sampleIndex, uint currentValue, bool chromaSample) {
    if (pushData.hasPreviousFrame == 0u) return currentValue;
    uint previousValue = unpackP010(previousData[sampleIndex]);
    uint delta = uint(abs(int(currentValue) - int(previousValue)));
    uint threshold = chromaSample ? 34u : 22u;
    if (delta > threshold) return currentValue;
    float weight = chromaSample ? uniforms.temporalChromaBlend : uniforms.temporalLumaBlend;
    return blendSample(currentValue, previousValue, clamp(weight, 0.0, 1.0));
}

uint applyChromaDeadband(uint chromaValue) {
    int delta = int(chromaValue) - 512;
    int threshold = int(clamp(uniforms.shadowChromaDeadband, 0.0, 1.0) * 1023.0);
    if (abs(delta) <= threshold) return 512u;
    float retained = float(delta) * clamp(1.0 - uniforms.chromaDenoise, 0.002, 1.0);
    return uint(clamp(512.0 + retained, 0.0, 1023.0));
}

uint applyLocalChromaSpikeSmoothing(uint chromaValue) {
    int delta = int(chromaValue) - 512;
    if (abs(delta) < 80) return chromaValue;
    float retained = mix(0.16, 0.08, clamp(uniforms.noiseMetric, 0.0, 1.0));
    return uint(clamp(512.0 + float(delta) * retained, 0.0, 1023.0));
}

uint applyEdgeAwareDetailLift(uint sampleIndex, uint centerValue) {
    uint width = max(pushData.width, 1u);
    uint height = max(pushData.height, 1u);
    uint row = sampleIndex / width;
    uint column = sampleIndex - row * width;
    if (row == 0u || column == 0u || row + 1u >= height || column + 1u >= width) return centerValue;
    uint north = unpackP010(currentData[sampleIndex - width]);
    uint south = unpackP010(currentData[sampleIndex + width]);
    uint west = unpackP010(currentData[sampleIndex - 1u]);
    uint east = unpackP010(currentData[sampleIndex + 1u]);
    float neighborAverage = (float(north + south + west + east) * 0.25);
    float edgeSignal = float(centerValue) - neighborAverage;
    float magnitude = abs(edgeSignal) / 1023.0;
    float gain = 0.0;
    if (magnitude >= uniforms.strongEdgeThreshold) {
        gain = uniforms.strongDetailGain;
    } else if (magnitude >= uniforms.mediumEdgeThreshold) {
        gain = uniforms.mediumDetailGain;
    } else if (magnitude >= uniforms.fineTextureThreshold) {
        gain = uniforms.fineTextureDetailGain;
    }
    float aliasLimit = clamp(uniforms.aliasSafeMaxDetailDelta, 0.0, 1.0) * 1023.0;
    float lift = clamp(edgeSignal * gain, -aliasLimit, aliasLimit);
    return uint(clamp(float(centerValue) + lift, 0.0, 1023.0));
}

void main() {
    uint x = gl_GlobalInvocationID.x;
    uint y = gl_GlobalInvocationID.y;
    if (x >= pushData.width || y >= pushData.height) return;
    uint lumaIndex = y * pushData.width + x;
    uint yValue = unpackP010(currentData[lumaIndex]);
    yValue = applyTemporalDenoise(lumaIndex, yValue, false);
    yValue = applyEdgeAwareDetailLift(lumaIndex, yValue);
    outputData[lumaIndex] = packP010(yValue);

    uint chromaWidth = (pushData.width + 1u) / 2u;
    uint chromaHeight = (pushData.height + 1u) / 2u;
    if ((x & 1u) == 0u && (y & 1u) == 0u && x / 2u < chromaWidth && y / 2u < chromaHeight) {
        uint chromaPairIndex = pushData.width * pushData.height + ((y / 2u) * chromaWidth + (x / 2u)) * 2u;
        uint uValue = unpackP010(currentData[chromaPairIndex]);
        uint vValue = unpackP010(currentData[chromaPairIndex + 1u]);
        uValue = applyTemporalDenoise(chromaPairIndex, uValue, true);
        vValue = applyTemporalDenoise(chromaPairIndex + 1u, vValue, true);
        uValue = applyChromaDeadband(uValue);
        vValue = applyChromaDeadband(vValue);
        uValue = applyLocalChromaSpikeSmoothing(uValue);
        vValue = applyLocalChromaSpikeSmoothing(vValue);
        outputData[chromaPairIndex] = packP010(uValue);
        outputData[chromaPairIndex + 1u] = packP010(vValue);
    }
}
```

- [ ] **Step 4: Run shader contract test**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.rendering.VulkanEnhancementShaderContractTest.p010EnhancementShaderContainsRequiredProcessingOrderAndUniforms" --console=plain --no-daemon --no-configuration-cache
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/cpp/shaders/vulkan/neo_p010_export_enhance.comp app/src/test/java/com/neoproc/neoproccamx/rendering/VulkanEnhancementShaderContractTest.kt
git commit -m "Add P010 enhancement compute shader contract"
```

---

### Task 6: NPV10 Export Path Evidence and Truthful Fallback

**Files:**
- Modify: `app/src/main/java/com/neoproc/neoproccamx/recording/Npv10MovConverter.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/recording/Npv10IntegrityValidator.kt`
- Modify: `app/src/test/java/com/neoproc/neoproccamx/recording/Npv10MovConverterTest.kt`

- [ ] **Step 1: Add failing test for export processing truth**

Append to `Npv10MovConverterTest.kt`:

```kotlin
@Test
fun converterLabelsPrivateMasterExportAsCpuFallbackWhenGpuExportIsUnavailable() {
    val audit = convertTinyNpv10Master(
        mode = "PRO",
        colorProfile = "Rec.709",
        selectedColorSpace = "Rec.709",
        selectedColorScience = "Rec.709 Standard"
    )

    assertTrue(audit.conversionCertificate.contains("exportProcessingPath=CPU_P010_FALLBACK"))
    assertTrue(audit.conversionCertificate.contains("exportZeroCopy=false"))
    assertTrue(audit.conversionCertificate.contains("exportGpuFallbackReason=NPV10_PRIVATE_MASTER_BYTE_PAYLOAD"))
    assertTrue(audit.conversionCertificate.contains("exportGpuUniformSignature="))
    assertFalse(audit.conversionCertificate.contains("exportProcessingPath=ZERO_COPY"))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.recording.Npv10MovConverterTest.converterLabelsPrivateMasterExportAsCpuFallbackWhenGpuExportIsUnavailable" --console=plain --no-daemon --no-configuration-cache
```

Expected: FAIL because these certificate fields do not exist.

- [ ] **Step 3: Add export path data model**

Add near `Npv10ExportEnhancementProfile` in `Npv10MovConverter.kt`:

```kotlin
internal data class Npv10ExportProcessingEvidence(
    val processingPath: String,
    val zeroCopy: Boolean,
    val gpuFallbackReason: String,
    val gpuUniformSignature: String
) {
    companion object {
        fun privateMasterCpuFallback(signature: String): Npv10ExportProcessingEvidence =
            Npv10ExportProcessingEvidence(
                processingPath = "CPU_P010_FALLBACK",
                zeroCopy = false,
                gpuFallbackReason = "NPV10_PRIVATE_MASTER_BYTE_PAYLOAD",
                gpuUniformSignature = signature
            )
    }
}
```

Inside `convertWithAudit`, after `enhancementProfile` is created, create a signature:

```kotlin
val exportUniforms = com.neoproc.neoproccamx.rendering.GpuImageEnhancementUniforms.fromSceneProfile(
    profile = com.neoproc.neoproccamx.pipeline.SceneRenderProfile(
        temporalBlend = enhancementProfile.temporalLumaBlend.toFloat(),
        spatialDenoise = 0.0f,
        chromaDenoise = 1.0f - enhancementProfile.chromaSuppression.toFloat(),
        shadowBoost = 0.0f,
        sharpening = enhancementProfile.strongDetailGain.toFloat(),
        warmth = 0.0f,
        contrast = 1.0f,
        saturation = 1.0f,
        highlightCompression = 0.0f,
        logStrength = if (colorTagPlan.activeTransport.contains("LOG")) 0.68f else 0f,
        ditherStrength = 0f,
        videoHdrFusionStrength = if (colorTagPlan.activeTransport.contains("PQ")) 0.82f else 0f
    ),
    signalFamily = when {
        colorTagPlan.activeTransport.contains("LOG") -> com.neoproc.neoproccamx.rendering.GpuSignalFamily.LOG
        colorTagPlan.activeTransport.contains("PQ") -> com.neoproc.neoproccamx.rendering.GpuSignalFamily.HDR
        else -> com.neoproc.neoproccamx.rendering.GpuSignalFamily.SDR
    },
    colorSpaceName = colorTagPlan.selectedColorSpace,
    colorScienceName = colorTagPlan.selectedProfile,
    activeTransport = null,
    exportProfile = enhancementProfile
)
val exportProcessingEvidence = Npv10ExportProcessingEvidence.privateMasterCpuFallback(exportUniforms.signature)
```

Pass evidence fields into `Npv10IntegrityValidator.auditMovFile(...)`.

- [ ] **Step 4: Extend integrity certificate fields**

Modify `auditMovFile(...)` parameters:

```kotlin
exportProcessingPath: String = "CPU_P010_FALLBACK",
exportZeroCopy: Boolean = false,
exportGpuFallbackReason: String = "NPV10_PRIVATE_MASTER_BYTE_PAYLOAD",
exportGpuUniformSignature: String = "unset",
```

Thread the fields through `buildMovConversionCertificate(...)` and add these certificate lines before `masterEnhancementDestructive`:

```kotlin
"exportProcessingPath=$exportProcessingPath",
"exportZeroCopy=$exportZeroCopy",
"exportGpuFallbackReason=$exportGpuFallbackReason",
"exportGpuUniformSignature=$exportGpuUniformSignature",
```

- [ ] **Step 5: Run targeted converter test**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.recording.Npv10MovConverterTest.converterLabelsPrivateMasterExportAsCpuFallbackWhenGpuExportIsUnavailable" --console=plain --no-daemon --no-configuration-cache
```

Expected: PASS.

- [ ] **Step 6: Run full converter tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.recording.Npv10MovConverterTest" --console=plain --no-daemon --no-configuration-cache
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add app/src/main/java/com/neoproc/neoproccamx/recording/Npv10MovConverter.kt app/src/main/java/com/neoproc/neoproccamx/recording/Npv10IntegrityValidator.kt app/src/test/java/com/neoproc/neoproccamx/recording/Npv10MovConverterTest.kt
git commit -m "Add truthful NPV10 GPU export evidence"
```

---

### Task 7: Remove Private-Vendor Color Claims From GPU-Bound Shader Assertions

**Files:**
- Modify: `app/src/main/cpp/shaders/vulkan/ycbcr_preview.frag`
- Modify: `app/src/test/java/com/neoproc/neoproccamx/rendering/VulkanPreviewShaderContractTest.kt`

- [ ] **Step 1: Update failing shader test for Neo-owned log behavior**

Modify `shader_routesLogDeliveryThroughSelectedCinemaLogCurve`:

```kotlin
@Test
fun shader_routesLogDeliveryThroughNeoOwnedLogCurvesOnly() {
    assertTrue(shaderSource.contains("encodeLogByMode"))
    assertTrue(shaderSource.contains("encodeNeoLogCurve"))
    assertTrue(shaderSource.contains("normalizeLogToneDeliveryMode"))
    assertTrue(shaderSource.contains("encodeLogByMode(linear, normalizeLogToneDeliveryMode(toneDeliveryMode))"))
    assertFalse(shaderSource.contains("encodeRedLog3G10"))
    assertFalse(shaderSource.contains("RED_LOG3G10"))
    assertFalse(shaderSource.contains("encodeLogC4"))
    assertFalse(shaderSource.contains("SLog"))
    assertFalse(shaderSource.contains("if (logDelivery) outColor = vec4(encodeSLog3(linear), 1.0);"))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.rendering.VulkanPreviewShaderContractTest.shader_routesLogDeliveryThroughNeoOwnedLogCurvesOnly" --console=plain --no-daemon --no-configuration-cache
```

Expected: FAIL because current shader still contains private-vendor log functions.

- [ ] **Step 3: Replace private-vendor shader names with Neo-owned curves**

In `ycbcr_preview.frag`, replace product/private-vendor function names and constants:

```glsl
vec3 encodeNeoLogCurve(vec3 linear, float density, float offset, float toeLift) {
    vec3 outValue;
    for (int i = 0; i < 3; ++i) {
        float x = max(linear[i] + offset, 0.0);
        outValue[i] = log2(1.0 + x * density) / log2(1.0 + density) + toeLift;
    }
    return clamp(outValue, 0.0, 1.0);
}

vec3 encodeLogByMode(vec3 linear, int mode) {
    if (mode == 1) {
        return encodeNeoLogCurve(linear, 42.0, 0.010, 0.055);
    }
    if (mode == 2) {
        return encodeNeoLogCurve(linear, 34.0, 0.018, 0.070);
    }
    if (mode == 3) {
        return encodeNeoLogCurve(linear, 48.0, 0.006, 0.050);
    }
    if (mode == 4) {
        return encodeNeoLogCurve(linear, 55.0, 0.004, 0.045);
    }
    return encodeNeoLogCurve(linear, 40.0, 0.012, 0.060);
}
```

Remove duplicate private-vendor functions from both duplicated sections of the shader file so the source test cannot find the old names.

- [ ] **Step 4: Run shader tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.rendering.VulkanPreviewShaderContractTest" --console=plain --no-daemon --no-configuration-cache
```

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/cpp/shaders/vulkan/ycbcr_preview.frag app/src/test/java/com/neoproc/neoproccamx/rendering/VulkanPreviewShaderContractTest.kt
git commit -m "Use Neo-owned log curves in GPU shader path"
```

---

### Task 8: Full Build Verification

**Files:**
- Uses all modified files.

- [ ] **Step 1: Run focused unit tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.rendering.*" --tests "com.neoproc.neoproccamx.recording.Npv10MovConverterTest" --console=plain --no-daemon --no-configuration-cache
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run full app unit tests and debug build with warnings as failures**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --warning-mode=fail --console=plain --no-daemon --no-configuration-cache
```

Expected: BUILD SUCCESSFUL with zero Gradle warnings.

- [ ] **Step 3: Record evidence summary**

Create or update `artifacts/zero-copy-vulkan-compute-verification.txt` with:

```text
Zero-Copy Vulkan Compute Verification
Focused rendering tests: PASS
NPV10 converter tests: PASS
Full unit tests: PASS
Debug build: PASS
Warning mode: FAIL ON WARNING
Live zero-copy truth: AHardwareBuffer import only when Vulkan import succeeds
NPV10 export truth: GPU-accelerated only when native compute path is available; private byte masters remain non-zero-copy
Vendor color rule: Product path limited to Rec.709, Rec.2020, Neo Wide Gamut, N-Log, NDR10+, NIVUKX Vision
```

- [ ] **Step 4: Commit verification artifact**

```powershell
git add artifacts/zero-copy-vulkan-compute-verification.txt
git commit -m "Record zero-copy Vulkan compute verification"
```
