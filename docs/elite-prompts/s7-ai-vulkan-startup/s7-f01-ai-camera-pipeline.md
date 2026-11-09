# AICameraPipeline (multi-stage AI frame orchestrator) — Elite Upgrade Prompt

> Read [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) first. Its global contract
> (no-downgrade, no-vendor, commit-per-feature, golden-first, ABI-locked, device-gated) applies
> here and is not restated.

| Field | Value |
|---|---|
| **Subsystem** | 7 — AI/ML + Vulkan rendering + Startup |
| **Prompt ID** | s7-f01 |
| **Maps to checkpoint(s)** | CP90 (frame starvation — done), CP92 (sensor-paced selection) |
| **Current working level** | 🟢 live-default-on |
| **Feature rating (potential)** | 8/10 |
| **Code-quality rating (now)** | 8/10 |
| **Risk · Size** | M · M |
| **Depends on** | s7-f02 (face), s7-f03 (depth), s7-f04 (segmentation), s7-f11/f12 (Vulkan import) |

## 1. What this feature is
`AICameraPipeline` is the orchestrator that takes each preview YUV frame, fans it out to the AI
stages (face, pose, depth, segmentation, scene, object), strides each stage at its own cadence,
fuses their results (with staleness-cached fallback when a stage skipped this frame), and emits a
single per-frame analysis bundle: subject/focus target, HDR/low-light suggestions, masks. It is
the throttle that keeps AI off the real-time-critical path while still feeding the focus engine
and color/exposure policies. It also attempts a zero-copy `AHardwareBuffer` tensor handoff.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
Cinema bodies (ARRI ALEXA 35, Sony VENICE 2) run subject detection / eye-AF on dedicated silicon
budgeted so it **never** steals from the imaging path — analysis is sensor-paced, not "whatever
frame is latest". The elite bar here is a real-time scheduler discipline:
- **Sensor-paced selection** (CP92): analysis frames are chosen on a deterministic cadence tied
  to sensor timestamp, not `acquireLatestImage()` drop-whatever-arrives — so cadence is stable
  under load and frame age is bounded.
- **Bounded staleness:** a fused result must carry the age (ms / frame-count) of its oldest
  contributing stage; consumers reject results older than a budget rather than acting on stale
  geometry (causes focus drift / mask lag).
- **Hard per-stage budget:** total AI wall-time per analyzed frame ≤ budget (target ≤ 8 ms on
  the analysis thread at 60 fps preview), with the adaptive governor dropping the heaviest
  stages first (depth → segmentation → pose) under thermal/load.
- **Zero imaging-path coupling:** the render/encode frame budget is independent of AI; AI
  starvation never blocks preview and preview never blocks AI beyond its stride.

## 3. Current state in NeoProCamX (verified)
- Orchestrator: `app/src/main/java/com/neoproc/neoproccamx/pipeline/AICameraPipeline.kt`.
- Per-stage strides are real and tunable (scene/pose/segmentation/depth/enhancement), with depth
  and segmentation flagged GPU-intensive (`AICameraPipeline.kt:76-86`). `adaptiveStrideMultiplier`
  is the load-backoff lever (CP90).
- Zero-copy attempt: `val hwBuffer = image.hardwareBuffer` (`AICameraPipeline.kt:1075`) — the AHB
  tensor path is attempted but is a *candidate*, not proven zero-copy (see s7-f11 truth boundary).
- **CP90 landed:** non-strict recording branch in `FrameRealtimeAnalysisPolicy` was lowered from
  stride 60L/proxy 256 to stride 12L (~5 analyses/sec @60 fps)/proxy 480 to fix AI starvation
  during record; strict branch unchanged. (Roadmap CP90, commit `43a4695`.)
- **CP92 open:** analysis frames are still taken via acquire-latest drop semantics, not a
  sensor-timestamp-paced selector — cadence wobbles under load and frame age is unbounded.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Sensor-paced selection (CP92):** the frame selector picks analysis frames by sensor
   timestamp on a fixed cadence; unit test feeds a timestamp stream + variable arrival jitter and
   asserts selected-frame intervals stay within ±1 frame of the target stride (no drift, no burst).
2. **Bounded staleness:** every fused bundle exposes `oldestStageAgeFrames`/`oldestStageAgeMs`;
   test asserts a stage that skipped N frames reports age ≥ N and a consumer-side gate rejects
   beyond the budget.
3. **Budget honesty:** per-stage and total analysis-thread wall-time recorded; governor drops
   depth→segmentation→pose in that order as load rises; test drives synthetic load and asserts the
   drop order + that face/subject (cheapest, most critical) is dropped last.
4. **No imaging coupling:** a starved/slow AI stage produces no change to render/encode frame
   timing (proven by the existing render scheduler being independent; document the boundary).
5. **No-downgrade default:** sensor-paced selector behind a flag defaulting to today's
   acquire-latest behavior bit-for-bit; CP90 strides unchanged for the strict branch.
6. **Budget:** AI analysis ≤ 8 ms/analyzed-frame target; never on the cold-start path.

## 5. Implementation prompt (hand this to the agent)
**Scope:** add CP92 sensor-paced frame selection + bounded-staleness metadata to the fused
bundle; keep all stages and CP90 strides intact.
**Non-goals:** do not add/replace any model (no-vendor); do not touch the render/encode path; do
not change the strict-branch strides.

**Plan:**
1. **Golden-lock:** characterization test pinning today's fused-bundle fields + the
   acquire-latest selection behavior, so the new selector defaults to identical output.
2. **CP92 selector:** introduce a pure `SensorPacedFrameSelector` (timestamp in → select/skip
   out) decoupled from `ImageReader`; wire it behind a flag in `AICameraPipeline`. Default flag =
   off ⇒ existing acquire-latest path.
3. **Staleness:** thread per-stage last-updated frame index into the fusion step; emit
   `oldestStageAge*`; add a consumer-side staleness gate (focus/exposure) that no-ops on excess.
4. **Governor drop-order:** make the adaptive multiplier drop depth→segmentation→pose→object
   first; assert face/subject last. Reuse the existing `adaptiveStrideMultiplier` lever.

**Files:** `pipeline/AICameraPipeline.kt`, new `pipeline/SensorPacedFrameSelector.kt`,
`ai/FrameRealtimeAnalysisPolicy.kt` (drop-order), the fused-bundle data class.

**ABI:** none (pure Kotlin orchestration; no GPU push-constant change).

**Gating:** CP92 selector + staleness gate behind a flag defaulting to current behavior.

## 6. Acceptance & verification
- **Unit (JVM):** selector cadence/jitter test; staleness-age test; governor drop-order test;
  golden-lock proving default bundle unchanged.
- **Device (vivo I2405 / Adreno):** record 60 fps — AI analyses ~5/sec under load (CP90 floor),
  no preview frame-time spike; thermal soak — depth/segmentation back off first, focus stays
  tracking; confirm no AI-induced dropped record frames.

## 7. Risks & no-downgrade guardrails
- **Stale-geometry focus drift:** bounded-staleness gate rejects old bundles → impossible to act
  on lagged masks.
- **AI starving imaging:** AI strictly off the render/encode budget; selector is read-only on
  frames.
- **Lag/thermal:** governor drop-order + CP90 floor; off cold-start path.
- **Regression:** flag defaults to acquire-latest; golden-lock on bundle fields.

## 8. References
- Roadmap: CP90 (done), CP92 in [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Tech: [`../technology_explanations/markdown/13-hardwarebuffer-zero-copy.md`](../technology_explanations/markdown/13-hardwarebuffer-zero-copy.md)
- Source: `app/src/main/java/com/neoproc/neoproccamx/pipeline/AICameraPipeline.kt:76, 1075`
- Related prompts: s7-f02, s7-f03, s7-f04, s7-f06, s7-f07, s7-f10, s7-f11, s7-f12
