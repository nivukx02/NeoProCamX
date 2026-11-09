# NIVUKX-DR Elite NDR10+ Upgrade Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Upgrade the project-owned NDR10+/NIVUKX path with measurable NIVUKX-DR Elite math, metadata, shaders, and QA contracts without making vendor compliance claims.

**Architecture:** Add a focused Kotlin math core for PQ, ACES AP1, JzAzBz, tone anchors, R-tile/S-map policy, and TMB metadata. Wire policy metadata through the existing recording quality contract while adding standalone shader sources and contract tests. Keep runtime claims truthful: unsupported Main12/AV1/Dolby/HDR10+ vendor behavior is recorded as fallback evidence, not silently claimed.

**Tech Stack:** Kotlin/JUnit, Android MediaCodec policy metadata, Vulkan GLSL shader source contracts, JSON schema assets.

---

### Task 1: Math Core Contracts

**Files:**
- Create: `app/src/test/java/com/neoproc/neoproccamx/recording/NivukxImagingMathTest.kt`
- Create: `app/src/main/java/com/neoproc/neoproccamx/recording/NivukxImagingMath.kt`

- [ ] Write failing tests for monotonic percentile anchors, toe/knee/shoulder curve behavior, PQ round trip, and hue-preserving JzAzBz chroma compression.
- [ ] Run focused test and verify it fails because `NivukxImagingMath` is missing.
- [ ] Implement the smallest production math API needed by the tests.
- [ ] Run focused test and verify it passes.

### Task 2: Truth Metadata Block

**Files:**
- Create: `app/src/test/java/com/neoproc/neoproccamx/recording/NivukxTruthLedgerTest.kt`
- Create: `app/src/main/java/com/neoproc/neoproccamx/recording/NivukxTruthLedger.kt`
- Create: `app/src/main/java/com/neoproc/neoproccamx/recording/NivukxMetadataEmitter.kt`
- Create: `app/src/main/assets/schema/nivukx_tmb.schema.json`

- [ ] Write failing tests proving hash-chain verification, tamper detection, no vendor flags, fallback honesty, and metadata budget <= 0.4 percent.
- [ ] Run focused test and verify it fails because the classes are missing.
- [ ] Implement deterministic TMB JSON and SHA-256 hash-chain verification using platform JCA.
- [ ] Run focused test and verify it passes.

### Task 3: NDR10+ Policy Wiring

**Files:**
- Create: `app/src/test/java/com/neoproc/neoproccamx/recording/RecordingNivukxLookPolicyTest.kt`
- Create: `app/src/main/java/com/neoproc/neoproccamx/recording/NivukxVersionRenderPolicy.kt`
- Create: `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingNivukxLookPolicy.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/recording/EliteVideoQualityContract.kt`

- [ ] Write failing tests proving NDR10+ maps to NIVUKX-DR Elite metadata, Main12/AV1 are preferred only as capability labels, Main10 is marked LITE fallback, and vendor certification remains false.
- [ ] Run focused test and verify it fails.
- [ ] Implement policy classes and add metadata keys to `EliteVideoQualityContract`.
- [ ] Run focused test and verify it passes.

### Task 4: Shader Source Contracts

**Files:**
- Create: `app/src/test/java/com/neoproc/neoproccamx/rendering/NivukxDrShaderContractTest.kt`
- Create: `app/src/main/cpp/shaders/vulkan/nivukx_analyse.comp`
- Create: `app/src/main/cpp/shaders/vulkan/nivukx_denoise.comp`
- Create: `app/src/main/cpp/shaders/vulkan/nivukx_apply.frag`
- Create: `app/src/main/cpp/shaders/vulkan/nivukx_sharpen.frag`

- [ ] Write failing tests verifying shader files contain the required stages, formulas, and no Dolby/vendor flags.
- [ ] Run focused test and verify it fails.
- [ ] Add compilable GLSL source skeletons with actual tone-map, denoise, and sharpen functions.
- [ ] Run focused test and verify it passes.

### Task 5: Verification

**Files:** no additional files.

- [ ] Run focused recording/rendering tests for NIVUKX-DR.
- [ ] Run `.\gradlew.bat testDebugUnitTest`.
- [ ] Run `.\gradlew.bat assembleDebug`.
- [ ] Report any environmental or pre-existing failures with exact command output.
