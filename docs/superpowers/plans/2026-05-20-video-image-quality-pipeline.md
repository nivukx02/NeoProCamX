# Video Image Quality Pipeline Phase 4 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a tested Phase 4 image-quality contract for HDR, LOG, RAW, SDR/PRO denoise, tone, sharpening, and pipeline order.

**Architecture:** Add a pure pipeline policy and wire it into `SceneRenderProfileEngine` as the final profile guard. Keep existing Camera2, encoder, and Vulkan paths intact.

**Tech Stack:** Kotlin, JUnit, Android Camera2/MediaFormat constants through existing code paths.

---

### Task 1: Add Phase 4 Policy Tests

**Files:**
- Create: `app/src/test/java/com/neoproc/neoproccamx/pipeline/VideoImageQualityPipelinePolicyTest.kt`
- Modify: none

- [ ] Write tests for HDR pipeline order, LOG separation, RAW truthfulness, noisy-scene denoise floors, and clean-scene detail preservation.
- [ ] Run `.\gradlew.bat :app:testDebugUnitTest --tests "com.neoproc.neoproccamx.pipeline.VideoImageQualityPipelinePolicyTest"`.
- [ ] Expected red state before implementation: unresolved `VideoImageQualityPipelinePolicy` symbols.

### Task 2: Implement Pure Policy

**Files:**
- Create: `app/src/main/java/com/neoproc/neoproccamx/pipeline/VideoImageQualityPipelinePolicy.kt`
- Test: `app/src/test/java/com/neoproc/neoproccamx/pipeline/VideoImageQualityPipelinePolicyTest.kt`

- [ ] Add `VideoImageSignalFamily`, `VideoImagePipelineStage`, request, plan, and resolver.
- [ ] Encode per-mode pipeline order.
- [ ] Encode denoise, sharpening, tone, ISP, and RAW calibration decisions.
- [ ] Run the focused policy test and confirm green.

### Task 3: Wire Scene Render Finalization

**Files:**
- Modify: `app/src/main/java/com/neoproc/neoproccamx/pipeline/SceneRenderProfileEngine.kt`
- Create: `app/src/test/java/com/neoproc/neoproccamx/pipeline/SceneRenderProfileEnginePhase4Test.kt`

- [ ] Add tests proving HDR final profile has no LOG strength and LOG final profile has no HDR fusion.
- [ ] Add tests proving severe RGB grain in recording raises chroma/spatial denoise floors and keeps sharpening bounded.
- [ ] Call the Phase 4 policy near the end of `SceneRenderProfileEngine.build`.
- [ ] Apply the returned floors/clamps to final `SceneRenderProfile` values.
- [ ] Run focused scene render tests and confirm green.

### Task 4: Verify And Commit

**Files:**
- All files changed above.

- [ ] Run `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug --warning-mode all`.
- [ ] Run `.\gradlew.bat :app:lintDebug --warning-mode all`.
- [ ] Commit with `feat: add phase 4 video image quality contract`.
