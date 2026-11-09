# NeoRadiance HELIOS Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add NeoRadiance v2 HELIOS as a standards-backed Rec.2020 HDR math path while preserving the current NeoRadiance v1 behavior for regression.

**Architecture:** Keep existing v1 functions in `NeoRadianceMath.kt` untouched and introduce typed HELIOS config, math helpers, validation harness, and merge policy beside them. Wire delivery through `VideoFormatDeliveryPolicy` so HDR10 PQ, HLG, and SDR decisions remain centralized. Add shader contract helpers to `ycbcr_preview.frag` without replacing the existing preview path.

**Tech Stack:** Kotlin/JUnit, Android MediaFormat policy models, Vulkan GLSL shader source, Gradle unit tests.

---

### Task 1: HELIOS Constants And Math Tests

**Files:**
- Create: `app/src/test/java/com/neoproc/neoproccamx/ai/NeoRadianceHeliosMathTest.kt`
- Create: `app/src/main/java/com/neoproc/neoproccamx/ai/NeoRadianceConfig.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ai/NeoRadianceMath.kt`

- [ ] Write tests for Rec.2020 luminance, diffuse-white PQ round-trip, C1 shadow knee, ICtCp round-trip, luminance-only cascade scaling, and v1 preservation.
- [ ] Run the tests and verify they fail because HELIOS types/functions do not exist.
- [ ] Add tagged constants to `NeoRadianceConfig.kt`.
- [ ] Add HELIOS math entry points to `NeoRadianceMath.kt`, keeping v1 functions unchanged.
- [ ] Re-run focused math tests.

### Task 2: AI Radiance Merge

**Files:**
- Create: `app/src/test/java/com/neoproc/neoproccamx/ai/RadianceMergeTest.kt`
- Create: `app/src/main/java/com/neoproc/neoproccamx/ai/RadianceMerge.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ai/AIProcessor.kt`

- [ ] Write tests for exposure weight `1-(2z-1)^12`, motion fallback below `0.5`, linear exposure-stack merge, `+6 dB` SNR evidence, and EMA alpha `0.15`.
- [ ] Run tests and verify they fail.
- [ ] Implement `RadianceMerge`.
- [ ] Expose an `AIProcessor.mergeHeliosRadianceStack(...)` wrapper that delegates to `RadianceMerge`.
- [ ] Re-run focused merge tests.

### Task 3: Delivery Policy

**Files:**
- Create: `app/src/test/java/com/neoproc/neoproccamx/ui/NeoRadianceDeliveryPolicyTest.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/VideoFormatDeliveryPolicy.kt`

- [ ] Write tests proving HDR10 PQ is selected when HDR10 is supported, HLG when only HLG is supported, and SDR Rec.709 with BT.2390 down-map when no HDR profile is available.
- [ ] Run tests and verify they fail.
- [ ] Add `NeoRadianceDeliveryPlan`, `NeoRadianceDeliveryTarget`, and resolver logic to the delivery policy.
- [ ] Re-run delivery tests.

### Task 4: Validation Harness And Docs

**Files:**
- Create: `app/src/test/java/com/neoproc/neoproccamx/ai/RadianceValidationHarnessTest.kt`
- Create: `app/src/main/java/com/neoproc/neoproccamx/ai/RadianceValidationHarness.kt`
- Create: `docs/technology_explanations/NeoRadiance_HELIOS/WHY.md`
- Create: `docs/technology_explanations/NeoRadiance_HELIOS/TRUTH_BOUNDARY.md`

- [ ] Write tests for the validation report gates required by the prompt.
- [ ] Run tests and verify they fail.
- [ ] Implement deterministic synthetic validation calculations and truthful pass/fail reporting.
- [ ] Add docs explaining changed values, source equations, measured synthetic gates, and fallback boundaries.
- [ ] Re-run validation tests.

### Task 5: Shader Contract

**Files:**
- Create: `app/src/test/java/com/neoproc/neoproccamx/rendering/NeoRadianceHeliosShaderContractTest.kt`
- Modify: `app/src/main/cpp/shaders/vulkan/ycbcr_preview.frag`

- [ ] Write a shader source contract test for HELIOS constants and functions.
- [ ] Run the test and verify it fails.
- [ ] Add GLSL helper functions for Rec.2020 luminance-only Cascade Fold, C1 shadow lift, PQ constants, and ICtCp matrices.
- [ ] Run the shader contract test and focused Gradle tests.

### Task 6: Verification

**Files:**
- No new files.

- [ ] Run focused unit tests:
  `.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.ai.NeoRadianceHeliosMathTest" --tests "com.neoproc.neoproccamx.ai.RadianceMergeTest" --tests "com.neoproc.neoproccamx.ai.RadianceValidationHarnessTest" --tests "com.neoproc.neoproccamx.ui.NeoRadianceDeliveryPolicyTest" --tests "com.neoproc.neoproccamx.rendering.NeoRadianceHeliosShaderContractTest" --no-daemon --console=plain`
- [ ] Run shader validation or project build command available in this repo.
- [ ] Report exact commands and results.
