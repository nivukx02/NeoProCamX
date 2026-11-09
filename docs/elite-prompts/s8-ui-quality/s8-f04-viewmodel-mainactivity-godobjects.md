# CameraViewModel / MainActivity Composite UI State + Render Path — Elite Upgrade Prompt

> Global contract in [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) applies and is not restated.

| Field | Value |
|---|---|
| **Subsystem** | 8 — UI + Quality gates + Build/Test infra |
| **Prompt ID** | s8-f04 |
| **Maps to checkpoint(s)** | new — propose CP-U4..U4n (incremental Policy decomposition) |
| **Current working level** | 🟢 live-default-on |
| **Feature rating (potential)** | 7/10 |
| **Code-quality rating (now)** | 6/10 (two god objects — the #1 maintainability liability) |
| **Risk · Size** | H · XL |
| **Depends on** | s8-f01, s8-f02, s8-f03 (all dispatch through the ViewModel) |

## 1. What this feature is
The UI control plane: `CameraViewModel` owns the composite preview/record/control state and exposes
it to the view; `MainActivity` wires lifecycle, surfaces, gestures, and the coalesced render
scheduler. Together they are the seam between Camera2/Vulkan/AI subsystems and the screen. They are
also **two god objects** and the single biggest maintainability risk in the codebase.

## 2. How high-end software engineering does this  ← THE REFERENCE BAR
This is a software-architecture upgrade, so the reference canon is engineering practice, not optics:
- **Single Responsibility / MVVM-MVI:** state is composed from many small, independently-testable
  reducers/policies, not one 10 k-line class. Pixel/optics behaviour is unchanged — this is pure
  structural refactoring under a behaviour-preservation contract.
- **Unidirectional data flow (MVI):** intents → reducers → immutable state → render; each reducer is
  a pure function with its own unit test (think Redux/MVI, Android `StateFlow`-per-slice).
- **God-object decomposition** (Fowler "Extract Class", Feathers "characterization tests first"):
  guard the current behaviour with characterization tests, then extract responsibility-cohesive
  `*Policy`/reducer classes one slice at a time, each commit behaviour-preserving.
- **Measurable structural targets:** line count, public surface (StateFlow count, function count),
  and cyclomatic complexity all trend *down* per commit, with zero behaviour change.
The project already proved this pattern: ~150 pure `*Policy` classes were extracted and unit-tested.
Elite = **continue** that decomposition until the god objects dissolve.

## 3. Current state in NeoProCamX (verified)
- `app/src/main/java/com/neoproc/neoproccamx/ui/CameraViewModel.kt` — **10,183 lines**, **102
  `StateFlow`s** (verified: `wc -l` = 10183; `grep -c StateFlow` = 102), 452 functions per catalog.
- `app/src/main/java/com/neoproc/neoproccamx/ui/MainActivity.kt` — **3,191 lines** (verified
  `wc -l` = 3191).
- ~150 extracted pure `*Policy` classes each unit-tested (e.g. `CameraManualControlStateSyncPolicy`,
  `CinemaManualControlPolicy`, `SettingsSectionResolver`); coalesced render scheduler; lifecycle-scoped
  collectors — the decomposition pattern is established and working.
- **The liability:** despite the policy extraction, the two classes remain monolithic; the remaining
  responsibilities (state composition, render scheduling, gesture routing, mode orchestration) are
  still inlined.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Behaviour-preserving:** every decomposition commit changes zero live pixels/behaviour, proven by
   a characterization test suite over the ViewModel's emitted state for a fixed intent sequence
   (snapshot equality before/after each extraction).
2. **Line-count reduction:** `CameraViewModel` ≤ 4,000 lines and `MainActivity` ≤ 1,500 lines at the
   end of the program (interim per-commit targets), with the extracted logic relocated into tested
   `*Policy`/reducer classes — measured by a meta-test asserting an upper bound on both file line counts.
2b. **StateFlow consolidation:** reduce the 102 `StateFlow`s toward a small number of composed
   `UiState` slices (target ≤ ~40 top-level flows), each slice a data class assembled by a pure
   combiner — meta-test asserts the StateFlow count is at or below the agreed ceiling and trending down.
3. **Per-slice unit tests:** each newly-extracted reducer/policy has its own unit test; no extracted
   class lands without a test (enforced by the existing source-hygiene meta-tests).
4. **No new public API leakage:** extraction does not widen the ViewModel's public surface; a test
   pins the public function/flow count to a non-increasing ceiling.
5. **No-downgrade default:** render output, control behaviour, and timing are unchanged each commit;
   the coalesced render scheduler keeps its current cadence and budget.

## 5. Implementation prompt (hand this to the agent)
**Scope:** continue the `*Policy`/reducer decomposition to dissolve both god objects, one cohesive
responsibility per commit, behaviour-preserving. **Non-goals:** do NOT change any pixel/optics path,
Camera2 orchestration semantics, or render timing; do NOT introduce an architecture framework or
dependency (no-vendor); do NOT rename public intents the view depends on without a shim.

**Plan (repeatable per slice):**
1. **Characterize first** (Feathers): capture the current emitted-state sequence for a fixed intent
   script into a golden snapshot — this is the behaviour-preservation lock.
2. Identify one cohesive responsibility (e.g. exposure-state composition, HUD telemetry coalescing,
   mode-switch orchestration, gesture→intent routing) and extract it into a pure `*Policy`/reducer.
3. Re-point the ViewModel/Activity to delegate to the new class; verify the characterization snapshot
   is byte-identical; add the new class's own unit test.
4. Commit (one slice = one CP), repeat. Track line/StateFlow/function counts each commit.

**Files:** `ui/CameraViewModel.kt`, `ui/MainActivity.kt`, new `ui/*Policy.kt` / `ui/*Reducer.kt`
per slice. No ABI/push-constant/shader change (UI layer only).

**Tests:** `CameraViewModelCharacterizationTest` (intent-script → state-snapshot equality), one
`*PolicyTest` per extracted class, `UiGodObjectSizeMetaTest` (line-count + StateFlow-count + public-
surface ceilings, monotonically non-increasing).

**Gating:** none needed — each commit is behaviour-preserving by construction (snapshot lock). The
"flag" here is the characterization test: a commit that changes the snapshot is rejected.

## 6. Acceptance & verification
- **JVM:** full existing suite green after each commit; characterization snapshot unchanged; new
  `*PolicyTest`s green; `UiGodObjectSizeMetaTest` shows counts at/under ceiling and trending down.
- **Device (vivo):** preview/record/controls behave identically; render frame-time unchanged in the
  on-device profiler (no scheduler regression).
- **Gate keys:** none — this is structural; it must not alter any quality-gate evidence.

## 7. Risks & no-downgrade guardrails
- **Behaviour drift during refactor:** characterization snapshot lock makes any drift a test failure.
- **Render timing regression:** scheduler cadence/budget pinned; device profiler check per program phase.
- **Hidden coupling:** extract one cohesive slice per commit; never bundle two (commit-per-feature).
- **Public-API breakage for the view:** non-increasing public-surface meta-test + shims for renamed intents.

## 8. References
- Reference: Fowler *Refactoring* ("Extract Class"), Feathers *Working Effectively with Legacy Code* (characterization tests), MVVM/MVI unidirectional data flow, single-responsibility principle.
- Source: `ui/CameraViewModel.kt` (10,183 lines / 102 StateFlows), `ui/MainActivity.kt` (3,191 lines); existing `ui/*Policy.kt` decomposition exemplars.
- Related prompts: s8-f01, s8-f02, s8-f03.
