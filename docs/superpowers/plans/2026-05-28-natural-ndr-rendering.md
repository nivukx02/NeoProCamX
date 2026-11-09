# Natural NDR Rendering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make NDR10+ and NIVUKX Vision preserve highlight texture, neutral color, and shadow detail with natural cinematic tone mapping.

**Architecture:** Add tested natural NDR math to `HdrImagingMath`, route NDR/NIVUKX preview and delivery shader paths through the same local luma/chroma strategy, and improve multi-exposure HDR merge weighting/reconstruction. Keep all changes project-owned and truthful.

**Tech Stack:** Kotlin/JUnit, GLSL 450 shader contracts, Gradle debug unit/build verification.

---

### Task 1: Math Contract

**Files:**
- Modify: `app/src/test/java/com/neoproc/neoproccamx/recording/HdrImagingMathTest.kt`
- Modify: `core-math/src/main/kotlin/com/neoproc/neoproccamx/recording/HdrImagingMath.kt`

- [ ] Add tests for natural NDR highlight rolloff, cyan/green highlight correction, and shadow preservation.
- [ ] Run focused `HdrImagingMathTest` and verify the new tests fail because the API is missing.
- [ ] Implement `toneMapNaturalDynamicRangeRgb`.
- [ ] Run focused `HdrImagingMathTest` and verify pass.

### Task 2: Shader Contracts

**Files:**
- Modify: `app/src/test/java/com/neoproc/neoproccamx/rendering/VulkanPreviewShaderContractTest.kt`
- Modify: `app/src/test/java/com/neoproc/neoproccamx/rendering/VulkanDenoiseComputeShaderContractTest.kt`
- Modify: `app/src/main/cpp/shaders/vulkan/ycbcr_preview.frag`
- Modify: `app/src/main/cpp/shaders/vulkan/hdr_merge.comp`

- [ ] Add shader contract tests for `naturalDynamicRangeToneMap`, reduced NDR sharpening, exposure-aware merge weights, ghost rejection, and raw-domain highlight reconstruction.
- [ ] Run focused rendering tests and verify failure.
- [ ] Implement shader helpers and route NDR/NIVUKX through them.
- [ ] Run focused rendering tests and verify pass.

### Task 3: Full Verification

**Files:** no additional files.

- [ ] Run focused NDR tests.
- [ ] Run `.\gradlew.bat testDebugUnitTest`.
- [ ] Run `.\gradlew.bat assembleDebug`.
