# Argus Hybrid Optical Flow — Elite Upgrade Prompt

> Read [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) first — its global contract
> (no-downgrade, no-vendor, commit-per-feature, golden-first, ABI-locked, device-gated) applies
> here and is not restated.

| Field | Value |
|---|---|
| **Subsystem** | 3 — Motion (OmniClarity) + Stabilization |
| **Prompt ID** | s3-f01 |
| **Maps to checkpoint(s)** | CP50 (dispatch live), CP54 (host golden EPE/occlusion); proposes CP54b (sub-pixel + scene-cut hardening) |
| **Current working level** | 🟢 live-default-on |
| **Feature rating (potential)** | 8/10 now → 9/10 target |
| **Code-quality rating (now)** | 9/10 (sound block-match + variational refine + occlusion select) |
| **Risk · Size** | M · M |
| **Depends on** | s3-f02 (Chronos consumes the flow), s3-f03 (Riven consumes the flow), s3-f04 (tuning policies) |

## 1. What this feature is
Argus is the optical-flow front-end of the OmniClarity motion stack: per-frame it estimates the
dense 2-D displacement field between the current and previous frame, then hands that field to
Chronos (temporal warp) and Riven (motion deblur). It is a three-stage hybrid: **Stage A** is
coarse-to-fine block matching (Lowe-ratio distinctiveness, texture gating, parabola sub-pixel)
run forward *and* backward; **Stage B** is a variational Horn-Schunck/Jacobi refine (8-iteration
ping-pong, edge-aware anisotropic smoothness); **selection** fuses refined-where-consistent flow
with the coarse field as an occlusion fallback. It runs at half-resolution then upsamples, and is
default-on across preview and encoder.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
Cinema cameras don't compute optical flow for capture — but every elite *computational* motion
pipeline (motion-compensated temporal denoise, frame interpolation, stabilization) is only as good
as its flow. The reference algorithms and their measurable bar:
- **Horn-Schunck** (global variational, smoothness-regularized) and **Lucas-Kanade** (local,
  windowed least-squares) are the classical baselines; **TV-L1** (Zach 2007) is the modern robust
  variational standard (L1 data term survives illumination change + outliers, total-variation
  regularizer preserves motion discontinuities at object boundaries). Argus' Stage-B is a
  Horn-Schunck/Jacobi solver; the elite bar is TV-L1-class boundary sharpness without its cost.
- **End-Point Error (EPE)** — the Euclidean distance between estimated and ground-truth flow — is
  the canonical accuracy metric (Middlebury / MPI-Sintel benchmarks). Elite target: integer
  translation EPE < 0.25 px, sub-pixel translation recovered to < 0.5 px after refine.
- **Forward/backward consistency** and **occlusion masking** are mandatory in production flow
  (disoccluded pixels have no correspondence; trusting them ghosts). Argus already runs fwd+bwd;
  the bar is a calibrated consistency threshold, not a constant.
- **Brightness-constancy residual** should *decrease* monotonically through the refine iterations
  on a real (non-pathological) scene — a direct, measurable convergence check.

## 3. Current state in NeoProCamX (verified)
- **Live and correct.** `dispatchOmniClarityMotionStack` (`vulkan_bridge.cpp:4993`, declared 664,
  called from the render path at `vulkan_bridge.cpp:6428`) runs the full stack per-frame when
  `omniClarityEnabled` and `omniClarityPipelinesReady` (deferred background build) are true.
- **Stage A:** forward block match → `omniFlowCoarse`, backward (frames swapped) → `omniFlowBackwardLow`
  (`vulkan_bridge.cpp:5052-5059`). **Stage B:** 8-iteration Jacobi ping-pong B↔A
  (`kOmniRefineIterations = 8`, `vulkan_bridge.cpp:5067-5078`). **Select:** occlusion + hybrid
  fallback → `omniFlowSelected` (`5082-5085`), then upsample to full res → `omniFlowFull` (`5088-5090`).
- **CP51 first-frame guard is live:** `previousValidPush` is derived from `historyContentValid[imageIndex]`
  and forced into block-match/refine/select push constants (`vulkan_bridge.cpp:5018-5020, 5054, 5066, 5083`),
  killing frame-0 / post-recreate ghosting against undefined history.
- **Host golden lock exists (CP54):** `ArgusFlowReference` + `ArgusFlowGoldenTest` (9 tests, commit
  `26da9c7`) port block-match→refine→select to the JVM and validate integer-translation EPE < 0.25,
  sub-pixel recovery, brightness-constancy residual reduction, fwd/bwd occlusion fallback, zero-flow guards.
- **The honest residuals:** (a) **scene-cut gating is deferred** (CP51 only handled the first-frame
  case; a flow-residual spike at a hard cut is not yet detected). (b) Push constants are
  default-constructed C++ structs (`bm{}`, `rf{}`, `sel{}` at `5053/5065/5082`) — the Kotlin tuning
  policies (s3-f04) do not reach them. (c) Sub-pixel precision relies on the refine stage by design
  (block-match deliberately distrusts Lowe-ratio-ambiguous matches).

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **EPE locked & extended:** integer-translation EPE < 0.25 px and sub-pixel translation EPE < 0.5 px
   asserted in `ArgusFlowGoldenTest`; add a **rotation** and a **divergent (zoom)** synthetic case,
   each with a stated EPE ceiling.
2. **Convergence proof:** brightness-constancy residual strictly non-increasing across the 8 refine
   iterations on a non-degenerate scene (host test asserting monotonicity).
3. **Occlusion correctness:** on a synthetic occlusion scene, occluded pixels select the coarse
   fallback (not the refined field) — asserted by the host port; consistency threshold is a named
   constant, not magic.
4. **Scene-cut robustness (CP54b):** a hard cut (frame fully replaced) must not propagate garbage
   flow into Chronos/Riven — detect a global flow-residual spike and gate `previousValid`→0 for that
   frame, same mechanism as the first-frame guard. Default threshold reproduces today's behavior
   (no false cuts on normal motion) — host test with a synthetic cut + a fast-pan non-cut.
5. **Budget:** half-res flow + 8 refine iters fit the preview frame budget; refine iteration count
   becomes a governor-driven lever (s3-f04) that **defaults to 8** (today's value) so there is zero
   live change until the owner tunes device-side.
6. **No-downgrade default:** all new gates default to today's exact behavior; the golden tests prove
   the default field is bit-stable.

## 5. Implementation prompt (hand this to the agent)
**Scope:** harden the *already-live* Argus flow — extend the golden EPE coverage, add scene-cut
gating, and expose the refine-iteration count as a governor lever — without changing today's output.
**Non-goals:** do NOT rewrite the block-match/refine/select math (CP54-locked); do NOT switch to a
new flow model (no-vendor — TV-L1 ideas may inform tuning only, classically); do NOT change the
half-res divisor or descriptor allocation (must match the alloc + push divisor, `vulkan_bridge.cpp:5022`).

**Plan:**
1. **Golden-first:** extend `ArgusFlowReference`/`ArgusFlowGoldenTest` with rotation, zoom, and a
   hard-cut case BEFORE touching native. Lock the current field.
2. **Scene-cut gate (CP54b):** compute a cheap global flow-residual / mean-flow-magnitude spike
   signal and, when it exceeds a named threshold, set `previousValid`→0 for that frame (reuse the
   CP51 path at `vulkan_bridge.cpp:5018-5020`). Default threshold = no cut on normal motion.
3. **Refine-iteration lever:** make `kOmniRefineIterations` (`vulkan_bridge.cpp:5067`) a value pushed
   from Kotlin (s3-f04 `ArgusFlowStagePolicy`) via JNI, **default 8**. This wires s3-f04 for Argus.
4. **Device note:** record the on-device check (EPE vs a printed chart pan; thermal soak with refine
   count backed off).

**Files:** `app/src/main/cpp/vulkan_bridge.cpp` (scene-cut signal, refine-count plumbing),
`app/src/test/java/.../ArgusFlowReferenceTest`/`ArgusFlowGoldenTest`,
`rendering/ArgusFlowStagePolicy.kt` (the iteration lever it already models), the Argus push-constant
Kotlin mirror.

**ABI:** Argus push structs are `ArgusPushConstantLayoutTest`-locked (CP02). If the refine-iteration
count or a scene-cut flag enters a push constant, extend `GpuPushConstantLayout` + the layout test in
the same commit and re-pin the relevant shader-manifest hashes (std140).

**Gating:** scene-cut threshold + refine lever default to today's values → bit-for-bit unchanged
until the owner tunes device-side.

## 6. Acceptance & verification
- **Unit/golden (JVM):** extended `ArgusFlowGoldenTest` (rotation/zoom/cut EPE ceilings, residual
  monotonicity, occlusion fallback); `ArgusFlowStagePolicy` iteration-lever test; ABI layout test if
  push constants change.
- **Native:** `:app:externalNativeBuildDebug` green; debug validation-layer run clean on the flow passes.
- **Device (vivo I2405 / Adreno):** (1) printed chart slow pan — measured EPE within ceiling, no
  ghosting downstream in Chronos; (2) hard cut (cover/uncover lens) — no flow-garbage smear; (3)
  thermal soak with refine count backed off — no frame-time spike, no process kill.

## 7. Risks & no-downgrade guardrails
- **Scene-cut false positives** (gating flow off on fast pans): threshold tuned + host non-cut test →
  default never triggers on normal motion.
- **Refine-count regression:** default 8 + golden lock → identical field until owner changes it.
- **ABI drift:** layout test + manifest re-pin in the same commit.
- **Lag/thermal:** iteration lever is the governor's primary backoff; half-res keeps the base cost bounded.

## 8. References
- Roadmap: CP50/CP51/CP52/CP54 (+ proposed CP54b) in [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Reference: Horn & Schunck (1981); Lucas-Kanade (1981); Zach et al., "A Duality Based Approach for
  Realtime TV-L1 Optical Flow" (2007); Baker et al., Middlebury flow benchmark / EPE; MPI-Sintel.
- Source: `app/src/main/cpp/vulkan_bridge.cpp:664, 4993, 5018, 5052, 5067, 5082, 6428`;
  `app/src/main/java/.../rendering/ArgusFlowStagePolicy.kt`; `ArgusFlowReference`/`ArgusFlowGoldenTest`.
- Related prompts: s3-f02, s3-f03, s3-f04
