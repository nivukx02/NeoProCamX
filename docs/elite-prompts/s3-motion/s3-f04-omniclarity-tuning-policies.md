# OmniClarity Kotlin Tuning Policies — Elite Upgrade Prompt

> Read [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) first — its global contract applies here
> and is not restated.

| Field | Value |
|---|---|
| **Subsystem** | 3 — Motion (OmniClarity) + Stabilization |
| **Prompt ID** | s3-f04 |
| **Maps to checkpoint(s)** | CP91 (activate adaptive thermal/quality governor + OmniClarity refine-iteration lever); supports CP50–CP53 |
| **Current working level** | ⚪ test-only-scaffold |
| **Feature rating (potential)** | 2/10 now → 8/10 target |
| **Code-quality rating (now)** | 8/10 (well-factored pure policies, fully unit-tested) |
| **Risk · Size** | L · M |
| **Depends on** | s3-f01 (Argus refine-iteration lever), s3-f02 (Chronos temporal lever), s3-f03 (Riven strength lever) |

## 1. What this feature is
These are the pure-Kotlin tuning brains for the OmniClarity motion stack: per-frame they decide
*how hard* Argus/Chronos/Riven should work given thermal headroom, frame-time budget, scene-cut
state, and estimated noise. `ArgusFlowStagePolicy` governs the Stage-B refine iteration count
(thermal/budget drop), `ChronosTemporalPolicy` governs temporal blend alphas + scene-cut alpha
gating, and `RivenDeblurPolicy` governs noise-aware deblur strength. They are the adaptive layer
that turns the motion stack from "fixed cost" into "as good as the device can afford this frame".

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
Professional motion pipelines are **adaptive and thermally honest**, not fixed:
- **Quality-vs-budget scaling:** real-time systems scale iteration count / search range / kernel
  taps to hold a frame-time target (the same principle as dynamic-resolution rendering). The bar is a
  *graceful* quality ramp — never a frame-time spike, never a hard stall, never a process kill under
  thermal load.
- **Scene-cut awareness:** temporal processors detect cuts and reset their history (no blending
  across a cut) — the same gate Argus needs for flow. Measurable: zero cross-cut ghosting.
- **Noise-adaptive strength:** deblur/denoise strength should track the *measured* sensor noise
  (heteroscedastic `σ² = σ_read² + σ_shot²·signal`), not a constant — strong deblur on a clean frame,
  conservative on a noisy one, so grain is never amplified.
- The bar here is purely *engineering correctness*: the policy output must (a) keep preview real-time
  under thermal soak, (b) never degrade output below the un-tuned default, and (c) be a deterministic,
  testable function of its inputs.

## 3. Current state in NeoProCamX (verified)
- **The policies exist and are tested, but DORMANT for the live shaders.** `ArgusFlowStagePolicy`,
  `ChronosTemporalPolicy`, `RivenDeblurPolicy`, and `OmniClarityCinemaRenderingChain` live in
  `app/src/main/java/.../rendering/` and reference one another, but their outputs **never reach the
  GPU push constants**.
- **The live shaders run on hardcoded C++ defaults.** In `dispatchOmniClarityMotionStack`
  (`vulkan_bridge.cpp:4993`) every push struct is default-constructed: `ArgusBlockMatchPushConstants bm{}`
  (5053), `ArgusRefinePushConstants rf{}` (5065), `ArgusSelectPushConstants sel{}` (5082),
  `RivenDeblurPushConstants rv{}` (5093), `OmniWarpPushConstants wp{}` (5098). The refine iteration
  count is a C++ `constexpr int kOmniRefineIterations = 8` (`vulkan_bridge.cpp:5067`), not a pushed value.
- **The only dynamic values that do flow** are the CP51 first/invalid-frame guards (`previousValid`
  and the warp α force-off, `vulkan_bridge.cpp:5018-5020, 5099-5104`) — set in C++, not by these policies.
- Net: the catalog's "referenced only by their own tests; live shaders run on hardcoded C++ defaults"
  is accurate. There is no JNI bridge carrying policy output into the dispatch.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Lever wiring:** the refine iteration count (Argus), temporal alphas + Kalman iters (Chronos),
   and `rlIterations` + noise floor (Riven) are pushed from the corresponding Kotlin policy via JNI
   into the existing push structs — each **defaulting to today's C++ value** (refine=8, CP52 alphas,
   `rlIterations=1`). Provable by a unit test asserting the default policy output equals the C++ default.
2. **Thermal backoff:** under a simulated thermal/budget signal the policies reduce work
   monotonically (fewer refine iters, lower deblur strength) — host test asserting the lever decreases
   as headroom decreases, and recovers as it returns.
3. **Scene-cut gating:** `ChronosTemporalPolicy` drops the temporal alpha to 0 on a scene-cut signal
   (shared with s3-f01's residual-spike detection) — host test on a synthetic cut.
4. **Noise-aware deblur:** `RivenDeblurPolicy` strength tracks the heteroscedastic noise estimate —
   stronger on low noise, conservative on high — host test over two noise levels.
5. **No-downgrade default:** with neutral inputs (cool device, full budget, no cut, baseline noise)
   every lever equals the current hardcoded default → bit-for-bit identical live output until the
   governor (CP91) actually moves a lever device-side.
6. **Budget:** the policy compute is trivial (scalar Kotlin) and runs off the GPU hot path; the
   *effect* of its levers is bounded by the per-shader budgets in s3-f01/f02/f03.

## 5. Implementation prompt (hand this to the agent)
**Scope:** build the JNI bridge that carries OmniClarity policy output into the live push constants,
default-preserving today's behavior, and wire the governor (CP91) to drive the levers under thermal/load.
**Non-goals:** do NOT change the shader math; do NOT change the policy algorithms (they're tested) —
only wire them; do NOT enable any non-default lever value by default.

**Plan:**
1. **Golden-first:** add tests asserting each policy's *neutral* output equals the current C++ default
   (refine=8, CP52 warp alphas, `rlIterations=1`) — this is the no-downgrade contract.
2. **JNI lever bridge:** add a setter (e.g. `nativeSetOmniClarityTuning(...)`) carrying the policy
   scalars; in `dispatchOmniClarityMotionStack` replace the relevant default-struct fields + the
   `kOmniRefineIterations` constant with the pushed values, falling back to the current default when
   unset. Preserve the CP51 guards verbatim.
3. **Governor wiring (CP91):** feed the existing adaptive load/thermal governor signal into the
   policies so the levers move under soak; default cool/full-budget = today's values.
4. **Device note:** record the thermal-soak + scene-cut on-device checks.

**Files:** `rendering/ArgusFlowStagePolicy.kt`, `rendering/ChronosTemporalPolicy.kt`,
`rendering/RivenDeblurPolicy.kt`, `rendering/OmniClarityCinemaRenderingChain.kt` (orchestration),
`rendering/VulkanBridge.kt` (JNI declaration), `app/src/main/cpp/vulkan_bridge.cpp` (setter +
dispatch field replacement at 5053/5065/5067/5082/5093/5098), the OmniClarity push-constant Kotlin mirrors.

**ABI:** these levers map onto **existing** push-constant fields (Argus locked by `ArgusPushConstantLayoutTest`;
Riven 24 B; warp 6 floats). If any new field is added, extend `GpuPushConstantLayout` + its layout
test in the same commit and re-pin the shader-manifest hashes. Prefer reusing existing slots.

**Gating:** every lever defaults to the current hardcoded value; the governor only moves them under
real thermal/load device-side → today's output unchanged until then.

## 6. Acceptance & verification
- **Unit/golden (JVM):** neutral-input-equals-C++-default test per policy (the no-downgrade lock);
  monotonic thermal-backoff test; scene-cut alpha-gating test; noise-aware strength test; JNI
  round-trip test (Kotlin value → push struct field).
- **Native:** `:app:externalNativeBuildDebug` green; validation-layer clean; dispatch reads the
  pushed values, not the bare defaults, when set.
- **Device (vivo I2405 / Adreno):** (1) cool device — output identical to pre-wiring (parity capture);
  (2) thermal soak — refine/deblur back off, frame-time held, no process kill (the CP91 goal of
  "neuter process-killing"); (3) hard cut — no cross-cut ghosting.

## 7. Risks & no-downgrade guardrails
- **Silent regression from a bad default:** neutral-equals-default golden test makes any drift from
  today's output a test failure.
- **Lever oscillation / hunting:** policies use the existing smoothed governor signal; host test
  asserts monotonic response, no oscillation.
- **ABI drift:** reuse existing slots; layout test + manifest re-pin if a field is added.
- **Lag/thermal:** the entire point is to *reduce* lag under load; the default path is unchanged cost.

## 8. References
- Roadmap: CP91 (governor + refine-iteration lever) and CP50–CP53 in [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Reference: adaptive quality-vs-budget scaling (dynamic-resolution rendering principle); scene-cut
  history reset in temporal processors; heteroscedastic noise model (`σ² = σ_read² + σ_shot²·signal`).
- Source: `app/src/main/cpp/vulkan_bridge.cpp:4993, 5053, 5065, 5067, 5082, 5093, 5098`;
  `rendering/{ArgusFlowStagePolicy,ChronosTemporalPolicy,RivenDeblurPolicy,OmniClarityCinemaRenderingChain}.kt`.
- Related prompts: s3-f01, s3-f02, s3-f03
