# Riven Motion Deblur — Elite Upgrade Prompt

> Read [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) first — its global contract applies here
> and is not restated.

| Field | Value |
|---|---|
| **Subsystem** | 3 — Motion (OmniClarity) + Stabilization |
| **Prompt ID** | s3-f03 |
| **Maps to checkpoint(s)** | CP53 Path A (clamp-bounded van Cittert — DONE); CP53 Path B (multiplicative Richardson-Lucy, device-gated) |
| **Current working level** | 🟢 live-default-on |
| **Feature rating (potential)** | 7/10 now → 9/10 target |
| **Code-quality rating (now)** | 9/10 (per-pixel directional deconvolution + Wiener regularization + clamp-bounded iteration) |
| **Risk · Size** | H · M |
| **Depends on** | s3-f01 (Argus flow gives the per-pixel motion direction/length), s3-f04 (noise-aware deblur strength) |

## 1. What this feature is
Riven (`riven_motion_deblur.comp`) reduces per-pixel motion blur by deconvolving along the local
motion direction supplied by the Argus flow field. It builds a 1-D line kernel oriented along the
motion vector, applies a Wiener-style noise-regularized inverse with a length gate (pass-through
below a minimum kernel length), and integrates over 16 taps. CP53 generalized the single-step inverse
into an N-iteration clamp-bounded (van Cittert) deconvolution where each iterate is driven along the
high-frequency residual and re-clamped to the input line extremes `[lineMin, lineMax]`, making
overshoot/ringing/halos impossible at any iteration count. It runs full-res before Chronos, default-on.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
Cinema cameras avoid motion blur optically (fast global-shutter-class readout, high shutter angles
under control, stabilized rigs) rather than deblurring after the fact — so the *computational* bar
is set by deconvolution theory and its known failure modes:
- **Richardson-Lucy deconvolution** (Richardson 1972, Lucy 1974) is the canonical iterative
  *multiplicative* deblur: it maximizes Poisson likelihood, the estimate stays non-negative, and it
  recovers detail better than a linear inverse — but **uncontrolled iteration count amplifies grain
  and rings**, which is the cardinal sin against the no-downgrade rule.
- **Wiener deconvolution** is the linear MMSE inverse: it needs a noise/signal-power estimate (the
  regularization term) and below that floor it cannot recover detail without amplifying noise. Riven's
  Wiener-style regularizer is exactly this — its quality is bounded by how honestly the noise floor
  is set.
- **van Cittert** iteration (additive, residual-driven) is the conservative cousin RL improves on;
  clamping each iterate to the local signal extremes is a hard anti-ringing guarantee.
- The professional bar: **measurable sharpness recovery** (MTF50 on a moving slanted edge above the
  blurred baseline) with **zero grain amplification** (flat-field SNR not reduced) and **no ringing
  halo** at high-contrast edges — all under a real-time budget.

## 3. Current state in NeoProCamX (verified)
- **Live and bounded (CP53 Path A, commit `215a5fb`).** Riven runs full-res — `RivenDeblurPushConstants rv{}`
  → `omniRivenPipeline` into `omniDeblur`, immediately after the flow upsample and before Chronos
  (`vulkan_bridge.cpp:5092-5095`). It consumes `omniFlowFull` for the per-pixel motion direction.
- **CP53 added `int rlIterations` (default 1)** ⇒ the original single directional-inverse step
  bit-for-bit ⇒ **zero live-behavior change**; the owner raises it device-side. Each iterate is
  re-clamped to `[lineMin, lineMax]`, so overshoot/ringing/halos are impossible at any N. Struct ABI
  = 5 floats + 1 int = 24 B; SPV recompiled; contract test updated.
- **The honest residual:** true *multiplicative* Richardson-Lucy needs the **evolving estimate at
  neighbour pixels** (a multi-dispatch ping-pong with a ratio image) — Path A's clamp-bounded van
  Cittert iterate is additive and single-dispatch. **Path B (multi-dispatch RL ping-pong + ratio
  image) is deferred / DEVICE-GATED:** its iteration count + Wiener/noise floor must be tuned against
  a *measured-noise reference on-device* or it amplifies grain.
- `rv{}` is default-constructed → the s3-f04 noise-aware deblur-strength policy does not reach the
  push constants. Riven single-step was already live + no-downgrade (audit-confirmed), so CP53 Path A
  is a pure capability superset.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Sharpness recovery (device):** on a synthetic/known motion-blur target, deblurred MTF50 (along
   the motion direction) > blurred-baseline MTF50, with `rlIterations > 1`.
2. **No grain amplification:** flat-field SNR(dB) after deblur ≥ baseline − ε (named tolerance) at the
   shipped iteration count — provable by a host port over a known-noise patch.
3. **No ringing:** at a high-contrast edge the output stays within `[lineMin, lineMax]` at any N
   (clamp invariant) — host test asserting the bound holds for every iterate.
4. **Path B correctness (device-gated):** the multiplicative RL ping-pong converges to the van Cittert
   result in the low-noise limit and never reduces flat-field SNR below the Path-A result at the same
   visual sharpness — host port comparison; on-device noise-reference tuning before activation.
5. **Noise-aware strength lever:** `rlIterations` + Wiener noise floor become s3-f04-driven push
   constants, **defaulting to CP53 values** (`rlIterations = 1`) so output is bit-stable until tuned.
6. **No-downgrade default:** Path B behind a spec constant defaulting to Path A; `rlIterations`
   defaults to 1 (today's single step). Golden lock proves bit-stability.
7. **Budget:** multi-dispatch RL is the most expensive path → governor-gated and OFF by default; the
   length gate keeps low-motion pixels at pass-through cost.

## 5. Implementation prompt (hand this to the agent)
**Scope:** add the device-gated multiplicative Richardson-Lucy Path B as a *superset* of live Path A,
and wire the noise-aware strength lever — without altering today's pixels.
**Non-goals:** do NOT change Path A's clamp-bounded van Cittert math (CP53-locked); do NOT enable any
multi-iteration default; do NOT add a deblur model (no-vendor — RL/Wiener are classical math).

**Plan:**
1. **Golden-first:** extend the Riven contract test + add a host directional-deconvolution port that
   locks the current `rlIterations = 1` output bit-for-bit.
2. **Path B behind a spec constant:** implement the multiplicative RL ping-pong (ratio image,
   multi-dispatch) reading neighbour estimates; default OFF (spec constant = Path A = today). Keep the
   clamp/length-gate guards on every iterate.
3. **Noise-aware lever:** push `rlIterations` + the Wiener noise floor from Kotlin (s3-f04
   `RivenDeblurPolicy`, noise-aware strength) via the push constant, **defaulting to CP53 values**.
4. **Device note:** the on-device measured-noise-reference tuning of iteration count + noise floor is
   the explicit gate; record the MTF50/SNR/ringing checks the owner must run.

**Files:** `app/src/main/cpp/vulkan_bridge.cpp` (Riven dispatch, ping-pong, lever plumbing),
`riven_motion_deblur.comp` (+ regenerate SPV header), the Riven contract test, host deconvolution
port test, `rendering/RivenDeblurPolicy.kt`, the Riven push-constant Kotlin mirror.

**ABI:** the Riven struct is 5f + 1i = 24 B (CP53). If Path B adds the Wiener noise floor / a path
flag to the push block, extend `GpuPushConstantLayout` + its layout test in the same commit and re-pin
the Riven shader-manifest hash. std140 alignment mandatory.

**Gating:** Path B = spec constant default OFF; `rlIterations` default 1; noise floor defaults to
CP53 value → bit-for-bit unchanged until the owner enables + tunes device-side.

## 6. Acceptance & verification
- **Unit/golden (JVM):** locked `rlIterations = 1` golden; clamp-bound invariant test (no value
  escapes `[lineMin, lineMax]` at any N); flat-field SNR-not-reduced test; Path-B-converges-to-Path-A
  in the low-noise limit; `RivenDeblurPolicy` lever test; ABI layout test + manifest re-pin if push
  constants change.
- **Native:** `:app:externalNativeBuildDebug` green; validation-layer clean on the Riven pass.
- **Device (vivo I2405 / Adreno):** (1) known motion-blur target — MTF50 recovery with `rlIterations > 1`;
  (2) flat gray field — SNR not reduced; (3) high-contrast moving edge — no ringing halo; (4) thermal
  soak — iteration count backs off, no spike/kill.

## 7. Risks & no-downgrade guardrails
- **Grain amplification (the headline risk):** clamp-bounded iterate + honest Wiener noise floor +
  Path B OFF by default + device noise-reference tuning before activation → cannot ship amplified grain.
- **Ringing/halo:** per-iterate clamp to local extremes is a hard invariant at any N.
- **Iteration-count regression:** `rlIterations` defaults to 1 + golden lock → identical output until tuned.
- **ABI drift:** layout test + manifest re-pin in the same commit.
- **Lag/thermal:** multi-dispatch RL is governor-gated; length gate keeps low-motion cost at pass-through.

## 8. References
- Roadmap: CP53 Path A (done) + CP53 Path B (device-gated) in [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Reference: Richardson (1972) / Lucy (1974) iterative deconvolution; Wiener deconvolution (MMSE
  inverse + noise/signal regularization); van Cittert iteration; MTF50 + flat-field SNR as the
  recovery-vs-grain bar.
- Source: `app/src/main/cpp/vulkan_bridge.cpp:5092-5095`; `riven_motion_deblur.comp`; Riven shader
  contract test; `rendering/RivenDeblurPolicy.kt`.
- Related prompts: s3-f01, s3-f02, s3-f04
