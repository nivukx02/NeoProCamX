# Video Mode Pipeline Contract Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make VIDEO, PRO, HDR, LOG, and RAW resolve through one truthful internal Camera2/Vulkan/encoder contract.

**Architecture:** Add a pure `VideoModePipelineContractPolicy` in the UI policy layer. Existing delivery, preview, dynamic range, and session-refresh policies consume the resolved contract instead of re-deriving mode state independently.

**Tech Stack:** Kotlin, Android Camera2 policy models, JUnit unit tests.

---

### Task 1: Contract Tests

**Files:**
- Create: `app/src/test/java/com/neoproc/neoproccamx/ui/VideoModePipelineContractPolicyTest.kt`

- [ ] Write tests proving VIDEO/PRO stay SDR when HDR is off, HDR versions select HDR preview/recording, LOG stays LOG-not-HDR while using a 10-bit transport when needed, and RAW suppresses HDR/LOG transforms.
- [ ] Run `.\gradlew.bat :app:testDebugUnitTest --tests com.neoproc.neoproccamx.ui.VideoModePipelineContractPolicyTest` and confirm the new class is missing.

### Task 2: Contract Implementation

**Files:**
- Create: `app/src/main/java/com/neoproc/neoproccamx/ui/VideoModePipelineContractPolicy.kt`

- [ ] Implement a pure resolver with explicit `deliveryKind`, `previewKind`, `effectiveHdrMode`, `cameraDynamicRangeMode`, `colorSpace`, `truthfulColorDelivery`, `requiresHdrMetadata`, and `tenBitTransportRequested`.
- [ ] Keep RAW linear/original by suppressing HDR, LOG, LUT, and recording tonemap delivery.
- [ ] Keep LOG as 10-bit LOG SDR/Rec.2020, not HDR, while allowing HLG10 only as the Camera2 transport profile when 10-bit is needed.

### Task 3: Wire Existing Policies

**Files:**
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/VideoFormatDeliveryPolicy.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/VideoFormatPreviewLookPolicy.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/camera/HdrDynamicRangePolicy.kt`
- Modify: `app/src/main/java/com/neoproc/neoproccamx/ui/CinemaModeSessionRefreshPolicy.kt`
- Modify tests beside each policy as needed.

- [ ] Make delivery state derive from the contract.
- [ ] Add preview-kind information so SDR, HDR, LOG, and RAW states are distinct.
- [ ] Add contract-based dynamic-range mapping for future callers while preserving legacy `fromUiMode`.
- [ ] Refresh sessions when the effective contract changes, not only when the enum changes.

### Task 4: Verification

**Files:**
- Existing focused tests under `app/src/test/java`.

- [ ] Run focused unit tests for the new contract and modified policies.
- [ ] Run `.\gradlew.bat :app:testDebugUnitTest`.
- [ ] Run `.\gradlew.bat :app:assembleDebug`.
