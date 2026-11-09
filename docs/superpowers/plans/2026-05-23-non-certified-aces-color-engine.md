# Non-Certified ACES Color Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a truthful non-certified ACEScg-based color-management engine for NeoProCamX preview, recording, and NPV10 export paths without implementing or claiming Dolby Vision, certified HDR10+, IMAX, or vendor-certified camera profiles.

**Architecture:** Add a focused Kotlin ACES math/policy module and a shared GLSL ACES helper file. Route existing shader stages through ACEScg scene-linear helpers while preserving current UI and mode behavior. Add contract tests proving non-certified labeling, ACEScg math presence, and preview/export parity hooks.

**Tech Stack:** Kotlin/JUnit, Vulkan GLSL shaders, existing Gradle Android build, current NeoProCamX color/profile policies.

---

### Task 1: Kotlin ACES Math And Truth Policy

**Files:**
- Create: `app/src/main/java/com/neoproc/neoproccamx/color/AcesColorEngine.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/color/AcesColorEngineTest.kt`

- [ ] Write failing tests for Rec.709 to ACEScg neutral preservation, highlight rolloff monotonicity, and non-certified metadata labels.
- [ ] Implement AP1/ACEScg matrix helpers, display transform selection, Hill-style preview RRT/ODT approximation, and truth labels that explicitly say `NON_CERTIFIED_INTERNAL_ACESCG`.
- [ ] Run focused tests for `AcesColorEngineTest`.

### Task 2: Scene Profile Color Management Signature

**Files:**
- Modify: `app/src/main/java/com/neoproc/neoproccamx/pipeline/SceneRenderProfileEngine.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/pipeline/SceneRenderProfileEngineTest.kt`

- [ ] Add an `acesColorSignature` string to `SceneRenderProfile`.
- [ ] Resolve signatures using only Rec.709, Rec.2020, Display P3, N-Log, NDR10+, NIVUKX Vision, and internal ACEScg.
- [ ] Prove profile output never claims Dolby Vision, certified HDR10+, IMAX, ARRI, Sony, or RED certification.

### Task 3: GLSL ACES Common Helpers

**Files:**
- Create: `app/src/main/cpp/shaders/vulkan/aces_common.glsl`
- Modify: `app/src/main/cpp/shaders/vulkan/ycbcr_preview.frag`
- Modify: `app/src/main/cpp/shaders/vulkan/denoise.comp`
- Modify: `app/src/main/cpp/shaders/vulkan/hdr_merge.comp`
- Modify: `app/src/main/cpp/shaders/vulkan/neo_p010_export_enhance.comp`
- Test: `app/src/test/java/com/neoproc/neoproccamx/rendering/VulkanAcesColorShaderContractTest.kt`

- [ ] Add failing shader contract tests for ACEScg scene-linear helper presence and forbidden certified-brand strings.
- [ ] Add GLSL helpers for Rec.709/Rec.2020 to ACEScg, ACEScg to Rec.709/Rec.2020, luminance, and fitted display mapping.
- [ ] Route preview/HDR/export shader paths through named ACEScg helpers without changing UI behavior.

### Task 4: Verification

**Commands:**
- `.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.color.AcesColorEngineTest" --tests "com.neoproc.neoproccamx.rendering.VulkanAcesColorShaderContractTest" --console=plain --no-daemon --no-configuration-cache`
- `.\gradlew.bat :app:testDebugUnitTest --warning-mode=fail --console=plain --no-daemon --no-configuration-cache`
- `.\gradlew.bat :app:assembleDebug --warning-mode=fail --console=plain --no-daemon --no-configuration-cache`
- `git diff --check`
