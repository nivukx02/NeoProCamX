# Truthful Master Profile Reconstruction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make N-Log, NDR10+, and NIVUKX Vision execute distinct, truthful, neutral master-delivery behavior in the live Vulkan recording path with matching metadata and CPU reference math.

**Architecture:** Preserve the already implemented Vulkan hardware-import recorder, bitrate ceilings, NPV10 private-master/export path, and focus-time color stability. Close three verified defects: N-Log's invalid shader flag/formula split, NDR/NIVUKX collapsing into generic PQ shader delivery, and synthetic creative processing being applied to truthful master output.

**Tech Stack:** Kotlin/JUnit4, Android Camera2/MediaCodec policy code, core Kotlin imaging math, Vulkan GLSL fragment shader, Gradle Android unit/lint/assemble gates.

---

## Workspace Constraint

This repository already has extensive uncommitted implementation changes, including all files affected by this plan. Execute in the shared workspace because the live implementation under test is not present in a clean worktree. Commit this plan document alone; do not create source commits that accidentally capture unrelated existing modifications.

## File Map

| File | Responsibility In This Change |
| --- | --- |
| `app/src/main/java/com/neoproc/neoproccamx/rendering/VulkanToneDeliveryMode.kt` | Assign exact shader modes for each N-Log and Neo HDR profile. |
| `app/src/test/java/com/neoproc/neoproccamx/rendering/VulkanToneDeliveryModeTest.kt` | Prove selected profiles no longer collapse to invalid/generic flags. |
| `app/src/test/java/com/neoproc/neoproccamx/rendering/VulkanPreviewRenderContractPolicyTest.kt` | Prove live render contract carries distinct Neo profile modes. |
| `core-math/src/main/kotlin/com/neoproc/neoproccamx/core/NeoLogCurve.kt` | Authoritative N-Log family math and truthful metadata constants. |
| `app/src/main/java/com/neoproc/neoproccamx/ai/NLogMath.kt` | Thin app-facing delegation to authoritative N-Log math. |
| `app/src/main/java/com/neoproc/neoproccamx/recording/EliteVideoQualityContract.kt` | Report measured/unknown latitude truth instead of unsupported 16.5-stop claim. |
| `app/src/test/java/com/neoproc/neoproccamx/core/NeoLogCurveV3ContractTest.kt` | Convert old latitude claim tests into family parity/truth tests. |
| `app/src/test/java/com/neoproc/neoproccamx/recording/EliteVideoQualityContractTest.kt` | Prove metadata names the active contract and does not assert unmeasured latitude. |
| `core-math/src/main/kotlin/com/neoproc/neoproccamx/recording/HdrImagingMath.kt` | CPU reference for gamut-safe perceptual NDR/NIVUKX mapping. |
| `app/src/test/java/com/neoproc/neoproccamx/recording/HdrImagingMathTest.kt` | Prove custom HDR profile separation, gamut safety, neutral and hue behavior. |
| `app/src/main/cpp/shaders/vulkan/ycbcr_preview.frag` | Live N-Log encoding, custom HDR profile branches, and neutral master output. |
| `app/src/test/java/com/neoproc/neoproccamx/rendering/VulkanPreviewShaderContractTest.kt` | Prove shader uses authoritative contract structure and excludes creative effects from truthful delivery. |
| `app/src/main/cpp/generated/ycbcr_preview.frag.spv` and `app/src/main/cpp/generated/ycbcr_preview_frag_spv.h` | Regenerated runtime shader payload if an available GLSL compiler is found. |

### Task 1: Correct Shader Routing Flags For Neo Profiles

**Files:**
- Modify: `app/src/test/java/com/neoproc/neoproccamx/rendering/VulkanToneDeliveryModeTest.kt`
- Modify: `app/src/test/java/com/neoproc/neoproccamx/rendering/VulkanPreviewRenderContractPolicyTest.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/rendering/VulkanToneDeliveryMode.kt`

- [ ] **Step 1: Write failing mode-routing tests**

Replace generic N-Log and custom-HDR assertions with profile-specific requirements:

```kotlin
@Test
fun selectedNLogVariantsUseShaderReachableVariantModes() {
    fun resolveLog(curve: NeoLogCurve) = VulkanToneDeliveryMode.resolve(
        previewLutEnabled = false,
        truthfulColorDelivery = true,
        rawVideoDelivery = false,
        rawVideoActive = false,
        logCaptureActive = true,
        normalizedHdrMode = "OFF",
        logCurve = curve
    )
    val base = resolveLog(NeoLogCurve.N_LOG_BASE)
    val shadow = resolveLog(NeoLogCurve.N_LOG_SHADOW)
    val highlight = resolveLog(NeoLogCurve.N_LOG_HIGHLIGHT)
    val cinema = resolveLog(NeoLogCurve.N_LOG_CINEMA)

    assertEquals(VulkanToneDeliveryMode.LOG_N_LOG_BASE, base)
    assertEquals(VulkanToneDeliveryMode.LOG_N_LOG_SHADOW, shadow)
    assertEquals(VulkanToneDeliveryMode.LOG_N_LOG_HIGHLIGHT, highlight)
    assertEquals(VulkanToneDeliveryMode.LOG_N_LOG_CINEMA, cinema)
    listOf(base, shadow, highlight, cinema).forEach {
        assertTrue(it.shaderModeFlag in 10f..13f)
    }
}

@Test
fun neoHdrProfilesUseDistinctPqTransportShaderModes() {
    fun resolveHdr(profile: String) = VulkanToneDeliveryMode.resolve(
        previewLutEnabled = false,
        truthfulColorDelivery = true,
        rawVideoDelivery = false,
        rawVideoActive = false,
        logCaptureActive = false,
        normalizedHdrMode = profile,
        logCurve = NeoLogCurve.NONE
    )
    assertEquals(VulkanToneDeliveryMode.NDR10_PLUS_HDR, resolveHdr("NDR10+"))
    assertEquals(VulkanToneDeliveryMode.NIVUKX_VISION_HDR, resolveHdr("NIVUKXVISION"))
    assertNotEquals(
        VulkanToneDeliveryMode.NDR10_PLUS_HDR.shaderModeFlag,
        VulkanToneDeliveryMode.NIVUKX_VISION_HDR.shaderModeFlag
    )
}
```

Update `VulkanPreviewRenderContractPolicyTest` so NDR10+ and NIVUKX render contracts expect `NDR10_PLUS_HDR` and `NIVUKX_VISION_HDR`, while generic standards-compatible fallback remains `PQ_HDR`.

- [ ] **Step 2: Run tests to prove the defect**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*VulkanToneDeliveryModeTest' --tests '*VulkanPreviewRenderContractPolicyTest' --no-daemon --no-configuration-cache --console=plain
```

Expected: FAIL because `LOG_N_LOG_BASE`, `NDR10_PLUS_HDR`, and `NIVUKX_VISION_HDR` are not yet declared and current NDR/NIVUKX modes resolve to `PQ_HDR`.

- [ ] **Step 3: Implement profile-specific tone-delivery modes**

Change the enum and resolver to expose shader-reachable N-Log variant values and dedicated Neo HDR values:

```kotlin
HLG_HDR(2f),
PQ_HDR(3f),
NDR10_PLUS_HDR(21f),
NIVUKX_VISION_HDR(22f),
NDR10_PLUS_MONITOR(41f),
NIVUKX_VISION_MONITOR(42f),
LOG_N_LOG_BASE(10f),
LOG_N_LOG_SHADOW(11f),
LOG_N_LOG_HIGHLIGHT(12f),
LOG_N_LOG_CINEMA(13f),
LOG_MONITOR_N_LOG_BASE(30f),
LOG_MONITOR_N_LOG_SHADOW(31f),
LOG_MONITOR_N_LOG_HIGHLIGHT(32f),
LOG_MONITOR_N_LOG_CINEMA(33f)
```

Add helper selection:

```kotlin
private fun logDeliveryMode(curve: NeoLogCurve, monitor: Boolean): VulkanToneDeliveryMode =
    when (curve) {
        NeoLogCurve.N_LOG_SHADOW -> if (monitor) LOG_MONITOR_N_LOG_SHADOW else LOG_N_LOG_SHADOW
        NeoLogCurve.N_LOG_HIGHLIGHT -> if (monitor) LOG_MONITOR_N_LOG_HIGHLIGHT else LOG_N_LOG_HIGHLIGHT
        NeoLogCurve.N_LOG_CINEMA -> if (monitor) LOG_MONITOR_N_LOG_CINEMA else LOG_N_LOG_CINEMA
        else -> if (monitor) LOG_MONITOR_N_LOG_BASE else LOG_N_LOG_BASE
    }
```

Resolve `"NDR10+"` and `"NIVUKXVISION"` separately for delivery and monitoring. Leave `PQ_HDR` available only for generic valid PQ transport fallback.

- [ ] **Step 4: Run focused routing tests**

Run the Step 2 command again.

Expected: PASS.

### Task 2: Establish One Truthful N-Log Family Contract

**Files:**
- Modify: `app/src/test/java/com/neoproc/neoproccamx/core/NeoLogCurveV3ContractTest.kt`
- Modify: `app/src/test/java/com/neoproc/neoproccamx/recording/EliteVideoQualityContractTest.kt`
- Modify: `core-math/src/main/kotlin/com/neoproc/neoproccamx/core/NeoLogCurve.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ai/NLogMath.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/recording/EliteVideoQualityContract.kt`

- [ ] **Step 1: Replace unsupported latitude tests with contract-truth tests**

The core test must require the authoritative family contract rather than the currently disconnected 128.0/16.5-stop claim:

```kotlin
@Test
fun nLogContractNamesSceneLinearFamilyWithoutUnmeasuredLatitudeClaim() {
    assertEquals("N-LOG-v4.0-scene-linear-family", NeoLogCurve.VERSION)
    assertEquals("DEVICE_MEASUREMENT_REQUIRED", NeoLogCurve.DYNAMIC_RANGE_EVIDENCE)
    assertEquals(0.18f, NeoLogCurve.SCENE_GRAY_18, 0f)
    assertEquals(0.50f, NeoLogCurve.GRAY_18_CODE, 0f)
}

@Test
fun nLogFamilyVariantsRoundTripAndShareGrayAnchor() {
    NeoLogCurve.Variant.entries.forEach { variant ->
        assertEquals(NeoLogCurve.GRAY_18_CODE, NeoLogCurve.encode(0.18f, variant), 0.0015f)
        listOf(0f, 0.002f, 0.018f, 0.18f, 0.55f, 1f).forEach { input ->
            assertEquals(input, NeoLogCurve.decode(NeoLogCurve.encode(input, variant), variant), 0.0015f)
        }
    }
}
```

Update `EliteVideoQualityContractTest` expectations:

```kotlin
assertEquals("N-LOG-v4.0-scene-linear-family", metadata.getValue("nLogCurveVersion"))
assertEquals("DEVICE_MEASUREMENT_REQUIRED", metadata.getValue("nLogDynamicRangeEvidence"))
assertEquals("UNVERIFIED", metadata.getValue("nLogDynamicRangeStops"))
```

- [ ] **Step 2: Run failing metadata/math tests**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*NeoLogCurveV3ContractTest' --tests '*NLogMathTest' --tests '*EliteVideoQualityContractTest' --no-daemon --no-configuration-cache --console=plain
```

Expected: FAIL because core metadata still claims `N-LOG-v3.0` and `16.5` stops and does not expose family variants/evidence.

- [ ] **Step 3: Make core math authoritative and delegate application math**

In `NeoLogCurve.kt`, replace the disconnected transfer constants with the current tested family definition:

```kotlin
const val VERSION = "N-LOG-v4.0-scene-linear-family"
const val DYNAMIC_RANGE_EVIDENCE = "DEVICE_MEASUREMENT_REQUIRED"
const val SCENE_GRAY_18 = 0.18f
const val GRAY_18_CODE = 0.50f
const val LINEAR_WHITE_POINT = 1.0f

enum class Variant { BASE, SHADOW, HIGHLIGHT, CINEMA }

private data class CurveProfile(val toeCut: Float, val toeSlope: Float, val logDensity: Float) {
    val toeHeight = toeCut * toeSlope
    val grayLog = ln(1f + logDensity * (SCENE_GRAY_18 - toeCut))
    val logScale = (GRAY_18_CODE - toeHeight) / grayLog
}
```

Implement `encode(linear, variant)` and `decode(encoded, variant)` with the existing reversible toe-plus-natural-log family behavior and retain one-argument functions as `Variant.BASE` compatibility wrappers. Keep matrix/highlight utilities callable; update comments so they do not claim certified ACES interchange or sensor-measured latitude.

In `NLogMath.kt`, keep the public app-facing enum/constants but delegate all transfer calculation:

```kotlin
fun encode(linear: Float, variant: NLogVariant): Float =
    NeoLogCurve.encode(linear, variant.toCoreVariant())

fun decode(encoded: Float, variant: NLogVariant): Float =
    NeoLogCurve.decode(encoded, variant.toCoreVariant())
```

In `EliteVideoQualityContract.kt`, report:

```kotlin
"nLogDynamicRangeStops" to "UNVERIFIED",
"nLogDynamicRangeEvidence" to NeoLogCurve.DYNAMIC_RANGE_EVIDENCE
```

while continuing to report version, bit depth requirement, transport, and selected profile.

- [ ] **Step 4: Run N-Log and metadata tests**

Run the Step 2 command again plus:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*TonemapCurveFactoryTest' --tests '*ColorSciencePreviewLutTest' --tests '*VideoLogProfilePolicyTest' --no-daemon --no-configuration-cache --console=plain
```

Expected: PASS, proving Camera2 curve points, preview LUT math, and metadata use the same N-Log basis.

### Task 3: Wire Identical N-Log And Distinct Neo HDR Behavior Into GLSL

**Files:**
- Modify: `app/src/test/java/com/neoproc/neoproccamx/rendering/VulkanPreviewShaderContractTest.kt`
- Modify: `app/src/main/cpp/shaders/vulkan/ycbcr_preview.frag`

- [ ] **Step 1: Add failing shader contract assertions**

Require reachable modes, family constants, custom perceptual branches, and absence of decorative master operations:

```kotlin
@Test
fun shaderUsesAuthoritativeNLogFamilyAndReachableModeValues() {
    assertTrue(shaderSource.contains("N_LOG_SCENE_GRAY_18 = 0.18"))
    assertTrue(shaderSource.contains("N_LOG_GRAY_CODE_18 = 0.50"))
    assertTrue(shaderSource.contains("encodeNLogFamily"))
    assertTrue(shaderSource.contains("toneDeliveryMode > 9.5 && toneDeliveryMode < 13.5"))
    assertTrue(shaderSource.contains("toneDeliveryMode > 29.5 && toneDeliveryMode < 33.5"))
}

@Test
fun shaderUsesDistinctNeoHdrPerceptualBranchesInMasterDelivery() {
    assertTrue(shaderSource.contains("bool ndr10PlusDelivery"))
    assertTrue(shaderSource.contains("bool nivukxVisionDelivery"))
    assertTrue(shaderSource.contains("neoHdrPerceptualToneMap"))
    assertTrue(truthfulDeliverySource.contains("neoHdrPerceptualToneMap"))
}

@Test
fun truthfulMasterDeliveryExcludesCreativeSimulationEffects() {
    assertFalse(truthfulDeliverySource.contains("linear = spectralReflectance31ToAcesAp0"))
    assertFalse(truthfulDeliverySource.contains("linear = warmHighlightHalation"))
    assertFalse(truthfulDeliverySource.contains("linear = fitzHughNagumoChemicalHalation"))
    assertFalse(truthfulDeliverySource.contains("linear = bartenHvsContrastEnhancement"))
    assertFalse(truthfulDeliverySource.contains("linear = gaborLindebergHvsContrastEnhancement"))
}
```

Remove old assertions that require creative simulation calls inside `truthfulDeliverySource`; helper existence outside the neutral master path remains allowed.

- [ ] **Step 2: Run shader contract tests to prove current disconnection**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*VulkanPreviewShaderContractTest' --tests '*VulkanToneDeliveryModeTest' --no-daemon --no-configuration-cache --console=plain
```

Expected: FAIL because the live shader does not have distinct NDR/NIVUKX branches, uses incompatible N-Log parameters, and invokes synthetic effects in truthful delivery.

- [ ] **Step 3: Implement GLSL N-Log family matching the core contract**

Replace `encodeNeoLogCurve`/`encodeLogByMode` with:

```glsl
const float N_LOG_SCENE_GRAY_18 = 0.18;
const float N_LOG_GRAY_CODE_18 = 0.50;

vec3 encodeNLogFamily(vec3 linear, float toeCut, float toeSlope, float logDensity) {
    float toeHeight = toeCut * toeSlope;
    float grayLog = log(1.0 + logDensity * (N_LOG_SCENE_GRAY_18 - toeCut));
    float logScale = (N_LOG_GRAY_CODE_18 - toeHeight) / grayLog;
    vec3 x = clamp(linear, 0.0, 1.0);
    vec3 logPart = vec3(toeHeight) + vec3(logScale) *
        log(vec3(1.0) + vec3(logDensity) * (x - vec3(toeCut)));
    vec3 toePart = x * toeSlope;
    return clamp(mix(logPart, toePart, lessThanEqual(x, vec3(toeCut))), 0.0, 1.0);
}

vec3 encodeLogByMode(vec3 linear, float toneDeliveryMode) {
    if (toneDeliveryMode > 10.5 && toneDeliveryMode < 11.5) {
        return encodeNLogFamily(linear, 0.006, 3.7, 24.0);
    }
    if (toneDeliveryMode > 11.5 && toneDeliveryMode < 12.5) {
        return encodeNLogFamily(linear, 0.005, 2.9, 40.0);
    }
    if (toneDeliveryMode > 12.5 && toneDeliveryMode < 13.5) {
        return encodeNLogFamily(linear, 0.006, 3.5, 20.0);
    }
    return encodeNLogFamily(linear, 0.006, 3.4, 24.0);
}
```

Update log flag bounds and monitor normalization so modes `10..13` and `30..33` enter the N-Log branch.

- [ ] **Step 4: Implement distinct gamut-safe Neo HDR shader paths**

Declare booleans for modes `21` and `22`, include them as PQ deliveries, and add a perceptual profile helper:

```glsl
vec3 neoHdrPerceptualToneMap(vec3 rgb, bool nivukxVision) {
    vec3 lab = linearAcesCgToOklab(max(rgb, vec3(0.0)));
    float knee = nivukxVision ? 0.55 : 0.59;
    float strength = nivukxVision ? 4.12 : 3.72;
    float mappedL = lab.x <= knee
        ? lab.x
        : knee + (1.0 - knee) *
            (1.0 - exp(-strength * (lab.x - knee) / max(1.0 - knee, 1e-5)));
    float chroma = length(lab.yz);
    float hue = atan(lab.z, lab.y);
    float highlightCompression = smoothstep(knee, 1.0, mappedL) *
        (nivukxVision ? 0.64 : 0.54);
    float mappedChroma = chroma * (1.0 - highlightCompression);
    vec3 candidate = oklabToLinearAcesCg(
        vec3(mappedL, cos(hue) * mappedChroma, sin(hue) * mappedChroma)
    );
    return clamp(candidate, 0.0, 1.0);
}
```

Invoke `neoHdrPerceptualToneMap` in truthful delivery only for `ndr10PlusDelivery` or `nivukxVisionDelivery`, before skin-tone guards and final ST2084/PQ encoding.

- [ ] **Step 5: Remove synthetic effects from neutral master delivery**

Keep existing helpers available for separately selected creative/display paths, but remove their calls in `// TRUTHFUL DELIVERY PATH`:

```glsl
// Omit from truthful master path:
// weberFechnerLocalAdaptation
// spectralReflectance31ToAcesAp0
// bartenHvsContrastEnhancement
// gaborLindebergHvsContrastEnhancement
// warmHighlightHalation
// fitzHughNagumoChemicalHalation
```

Retain bounded chroma filtering, edge-aware spatial denoise, profile tone mapping, skin/shadow protection, suppression/detail logic, and final quantization dither.

- [ ] **Step 6: Run shader/routing tests**

Run the Step 2 command again.

Expected: PASS.

### Task 4: Align CPU Neo HDR Reference Math With Live Profile Intent

**Files:**
- Modify: `app/src/test/java/com/neoproc/neoproccamx/recording/HdrImagingMathTest.kt`
- Modify: `core-math/src/main/kotlin/com/neoproc/neoproccamx/recording/HdrImagingMath.kt`

- [ ] **Step 1: Add failing reference-math tests**

Add contract tests for custom perceptual mapping:

```kotlin
@Test
fun neoHdrPerceptualMappingIsDistinctGamutSafeAndNeutralStable() {
    val neutralNdr = HdrImagingMath.toneMapUltraHdrVersionRgb(
        0.42, 0.42, 0.42, HdrImagingMath.HdrDeliveryVersion.NDR10_PLUS
    )
    val neutralVision = HdrImagingMath.toneMapUltraHdrVersionRgb(
        0.42, 0.42, 0.42, HdrImagingMath.HdrDeliveryVersion.NIVUKX_VISION
    )
    listOf(neutralNdr, neutralVision).forEach {
        assertEquals(it.r, it.g, 0.000001)
        assertEquals(it.g, it.b, 0.000001)
    }
    assertTrue(kotlin.math.abs(neutralNdr.r - neutralVision.r) > 0.000001)
}

@Test
fun neoHdrProfileSourcePeakNeverExceedsPqTransportCeiling() {
    listOf(HdrImagingMath.HdrDeliveryVersion.NDR10_PLUS, HdrImagingMath.HdrDeliveryVersion.NIVUKX_VISION)
        .forEach { version ->
            val profile = HdrImagingMath.resolveUltraHdrVersionProfile(version, 0.2, 1.0, 1.0)
            assertTrue(profile.sourcePeakNits <= 10_000.0)
        }
}
```

- [ ] **Step 2: Run math tests to prove missing behavior**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*HdrImagingMathTest' --no-daemon --no-configuration-cache --console=plain
```

Expected: FAIL because NIVUKX currently resolves a source peak greater than the PQ 10,000 nit ceiling and custom versions share the general luma-mapping implementation.

- [ ] **Step 3: Implement perceptual custom-profile reference mapping**

Clamp NIVUKX modeled source peak within PQ transport:

```kotlin
sourcePeakNits = 9_400.0 + sceneBoost * 600.0
```

Route only `NDR10_PLUS` and `NIVUKX_VISION` through a new private `toneMapNeoPerceptualRgb(...)` function. Its operations mirror the live shader intent:

```kotlin
private fun toneMapNeoPerceptualRgb(
    r: Double,
    g: Double,
    b: Double,
    profile: UltraHdrVersionProfile
): LinearBt2020Rgb {
    val neutral = toneMapBt2100LumaPreservingRgb(
        r = r,
        g = g,
        b = b,
        sourcePeakNits = profile.sourcePeakNits,
        targetPeakNits = profile.targetPeakNits,
        shadowLift = profile.shadowLift,
        saturationPreservation = profile.saturationPreservation,
        kneeRatio = profile.kneeRatio,
        shoulderStrength = profile.shoulderStrength,
        colorVolumeCompression = profile.colorVolumeCompression
    )
val aces = bt2020ToAcesCgLinear(r.coerceAtLeast(0.0), g.coerceAtLeast(0.0), b.coerceAtLeast(0.0))
val lab = linearAcesCgToOklab(aces.r, aces.g, aces.b)
val targetY = bt2020Luminance(neutral.r, neutral.g, neutral.b)
val sourceY = bt2020Luminance(r, g, b).coerceAtLeast(EPS)
val targetL = (lab.l * Math.cbrt((targetY / sourceY).coerceAtLeast(0.0))).coerceIn(0.0, 1.0)
val compression = smoothstep(profile.kneeRatio, 1.0, targetL) * profile.colorVolumeCompression
val chromaScale = (1.0 - compression).coerceIn(0.0, 1.0)
val mappedAces = oklabToLinearAcesCg(targetL, lab.a * chromaScale, lab.b * chromaScale)
val mappedBt2020 = acesCgLinearToBt2020(mappedAces.r, mappedAces.g, mappedAces.b)
val skinSafe = protectBt2100SkinRedRatio(mappedBt2020.r, mappedBt2020.g, mappedBt2020.b)
val shadowSafe = protectBt2100CyanShadowBias(skinSafe.r, skinSafe.g, skinSafe.b)
return fitBt2100RgbToUnitCube(shadowSafe.r, shadowSafe.g, shadowSafe.b, 0.965)
}
```

Finish through the existing skin-red, cyan-shadow, and gamut fitting guards before returning. Generic HLG/HDR compatibility math remains unchanged.

- [ ] **Step 4: Run HDR math tests**

Run the Step 2 command again.

Expected: PASS.

### Task 5: Regenerate Runtime Shader Payload And Verify The Whole App

**Files:**
- Modify if compiler is available: `app/src/main/cpp/generated/ycbcr_preview.frag.spv`
- Modify if compiler is available: `app/src/main/cpp/generated/ycbcr_preview_frag_spv.h`
- Verify: all scoped source and tests

- [ ] **Step 1: Locate GLSL compilation tooling**

Run:

```powershell
Get-Command glslc -ErrorAction SilentlyContinue
Get-ChildItem -Path $env:LOCALAPPDATA,'C:\VulkanSDK' -Recurse -Filter glslc.exe -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty FullName
```

Expected: identify a `glslc.exe` path or record that generated runtime SPIR-V cannot be regenerated on this machine.

- [ ] **Step 2: Regenerate shader binary/header when tooling exists**

If `glslc.exe` is available, compile:

```powershell
& $glslc -fshader-stage=frag 'app/src/main/cpp/shaders/vulkan/ycbcr_preview.frag' -o 'app/src/main/cpp/generated/ycbcr_preview.frag.spv'
```

Use the repository's existing binary-to-header generation mechanism if present; if none exists, do not invent an incompatible generated-header format. Report shader source verification as complete and native payload regeneration as blocked by absent project tooling.

- [ ] **Step 3: Run focused contract suite**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*NLogMathTest' --tests '*NeoLogCurveV3ContractTest' --tests '*EliteVideoQualityContractTest' --tests '*HdrImagingMathTest' --tests '*VulkanToneDeliveryModeTest' --tests '*VulkanPreviewRenderContractPolicyTest' --tests '*VulkanPreviewShaderContractTest' --tests '*RecordingPipelineParityPolicyTest' --tests '*Npv10ExportEnhancementPolicyTest' --tests '*VideoColorStabilityPolicyTest' --no-daemon --no-configuration-cache --console=plain
```

Expected: PASS.

- [ ] **Step 4: Run full build gates**

Run serially:

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon --no-configuration-cache --console=plain
.\gradlew.bat :app:lintDebug :app:assembleDebug --no-daemon --no-configuration-cache --console=plain
```

Expected: `BUILD SUCCESSFUL`; lint contains no errors or warnings accepted as a bypass.

- [ ] **Step 5: Attempt device evidence only when ADB target exists**

Run:

```powershell
adb devices -l
```

If a device is listed, install/launch and record profile evidence. If no device is listed, report that production/device-verified quality remains unproven.

## Completion Criteria

- N-Log selection reaches a matching live shader branch for Base, Shadow, Highlight, and Cinema.
- N-Log application math, core metadata reference, and GLSL coefficients represent one named family contract.
- N-Log metadata does not claim unmeasured dynamic-range stops.
- NDR10+ and NIVUKX Vision retain standards-compatible transport but execute distinct Neo profile branches.
- Truthful master output no longer adds synthetic halation/spectral/creative contrast behavior.
- Existing GPU recording, bitrate, NPV10 truth/export, and focus-time color stability behavior remains under passing tests.
- Device-verified quality is reported only after actual ADB recording evidence.
