# Chronos Motion-Compensated Temporal Warp — Elite Upgrade Prompt

> Read [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) first — its global contract applies here
> and is not restated.

| Field | Value |
|---|---|
| **Subsystem** | 3 — Motion (OmniClarity) + Stabilization |
| **Prompt ID** | s3-f02 |
| **Maps to checkpoint(s)** | CP52 (geometrically honest warp — DONE); proposes CP52b (pyramid multi-scale warp, device-gated) |
| **Current working level** | 🟢 live-default-on |
| **Feature rating (potential)** | 7/10 now → 9/10 target |
| **Code-quality rating (now)** | 9/10 (honest backward warp + confidence-gated fusion + Kalman-Bucy accumulator) |
| **Risk · Size** | M · M |
| **Depends on** | s3-f01 (Argus flow is the correspondence), s3-f04 (temporal tuning policy) |

## 1. What this feature is
Chronos (`warp_reconstruct.comp`) is the motion-compensated temporal stage: it backward-warps the
previous frame into the current frame's geometry along the Argus flow field (`source = p − flow(p)`),
then confidence-gates the temporal blend (color consistency + local variance + forward/backward
consistency + occlusion + boundary feather) so only well-aligned history is fused. A bounded
sub-pixel detail-recovery blend and a Kalman-Bucy temporal accumulator (6 iterations) add temporal
stability without smearing motion. It runs full-res, default-on, into `intermediate`.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
The reference is **motion-compensated temporal processing** as used in cinema temporal denoise and
IMAX DMR (Digital Media Remastering reconstructs per-frame detail using neighboring frames):
- **Backward warping along true correspondence** is the geometrically correct operation — you fetch
  where each output pixel *came from*, never push pixels forward (which leaves holes). Sub-pixel
  fetch must be bilinear-or-better to avoid aliasing the recovered detail.
- **Occlusion- and consistency-gated fusion** is mandatory: a temporal blend that trusts misaligned
  or disoccluded history ghosts and smears moving edges. The professional bar is *zero motion smear*
  on a moving subject while still averaging temporal noise in static regions (measured as temporal
  flicker σ reduction in flats, with edge-sharpness MTF50 preserved on moving content).
- **Coarse-to-fine (pyramid) warping** is how production flow/warp handles large displacements: warp
  at a coarse scale, upsample the residual, refine — so a fast pan doesn't exceed the per-level search
  range. Argus already provides a half-res→full upsample (a coarse-to-fine *flow*); the elite warp
  target is a true 2–3-level *image* pyramid warp validated for large-motion correctness on-device.

## 3. Current state in NeoProCamX (verified)
- **Live and geometrically honest (CP52, commit `a4225f3`).** `warp_reconstruct.comp` backward
  correspondence is now the Argus flow field *alone* (`sourceCoord = vec2(coord) - flow`). CP52
  **removed** the fabricated cosmetic pseudo-physics that previously over-warped every moving pixel:
  a gyro/OIS displacement *synthesised from optical-flow magnitude* (never read real gyro), an
  Einstein/Riemann radial-curvature metric warp, and a per-pixel field-tensor distortion.
- **Retained correct machinery:** confidence/occlusion/Kalman-Bucy color fusion (the legitimate part).
- **Dispatch:** Chronos runs last in the stack — `OmniWarpPushConstants wp{}` →
  `omniWarpPipeline` into `intermediate` (`vulkan_bridge.cpp:5097-5106`). On a first/invalid frame the
  CP51 guard forces `wp.baseTemporalAlpha = wp.maxTemporalAlpha = 0` (pass-through of the deblurred
  current frame, `vulkan_bridge.cpp:5099-5104`).
- **ABI:** push-constant layout unchanged at CP52 (6 floats); SPV recompiled and manifest hashes
  re-pinned (both warp entries `281c8904→42d4b2dc`). `WarpReconstructShaderContractTest` was rewritten
  (CP52-fix `657f762`) to assert the honest flow-only correspondence + retained fusion, with a
  lock-out test so the pseudo-physics can never regress back.
- **The honest residual:** the **pyramid (2–3 level) image warp was descoped** — it needs multiple
  flow scales + device tuning; the existing half-res→full flow upsample only gives a *coarse flow*,
  not a multi-scale image warp. `wp{}` is default-constructed → s3-f04 temporal tuning does not reach
  it. Both are device-gated / deferred.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Pyramid warp correctness (CP52b):** a 2–3-level coarse-to-fine image warp recovers correct
   correspondence for displacements that exceed a single-level search range. Host test: synthetic
   large-translation scene, warped-prev vs ground-truth current within a stated PSNR/MAE ceiling at
   a displacement where the single-level warp fails.
2. **Zero motion smear:** on a synthetic moving-edge sequence, edge-sharpness (MTF50 proxy) of the
   fused frame ≥ the current-frame baseline (no smear), while static-region temporal flicker σ is
   reduced — both asserted by a host port of the fusion/occlusion logic.
3. **Occlusion safety:** disoccluded regions select current-frame content (temporal α→0), proven by
   the host port; the boundary feather has a named width, not a magic constant.
4. **Temporal lever:** `baseTemporalAlpha`/`maxTemporalAlpha`/iteration count become s3-f04-driven
   push constants, **defaulting to today's CP52 values** so the field is bit-stable until tuned.
5. **No-downgrade default:** the pyramid path is behind a specialization constant defaulting to the
   single-level CP52 warp (exact today's output); golden lock proves bit-stability.
6. **Budget:** the extra pyramid levels fit the preview budget or are governor-gated; first/invalid
   frame stays a pass-through (CP51 invariant preserved).

## 5. Implementation prompt (hand this to the agent)
**Scope:** add a device-gated multi-scale (pyramid) image warp as a *superset* of the live CP52
single-level warp, and wire the temporal tuning lever — without altering today's pixels.
**Non-goals:** do NOT reintroduce any synthesised/pseudo-physics displacement (the CP52 lock-out test
must stay green); do NOT change the backward-correspondence formula (`source = p − flow`); do NOT add
a flow model (no-vendor).

**Plan:**
1. **Golden-first:** extend `WarpReconstructShaderContractTest` + add a host warp/fusion port that
   locks the current single-level warp output bit-for-bit.
2. **Pyramid warp behind a spec-constant:** add a 2–3-level coarse-to-fine warp path, default OFF
   (spec constant = single-level = today). Source the coarse flow from the existing half-res field.
3. **Temporal lever:** push `baseTemporalAlpha`/`maxTemporalAlpha`/Kalman iters from Kotlin
   (s3-f04 `ChronosTemporalPolicy`) via the warp push constant, **defaulting to CP52 values**. Keep
   the CP51 first/invalid-frame α=0 force-off intact.
4. **Device note:** record the large-motion + thermal on-device checks the owner must run before
   enabling the pyramid path.

**Files:** `app/src/main/cpp/vulkan_bridge.cpp` (warp dispatch, levels, lever plumbing),
`warp_reconstruct.comp` (+ regenerate SPV header), `WarpReconstructShaderContractTest`, host warp
port test, `rendering/ChronosTemporalPolicy.kt`, the warp push-constant Kotlin mirror.

**ABI:** if the temporal lever or a level count enters the 6-float push block, extend
`GpuPushConstantLayout` + its layout test in the same commit and **re-pin both warp manifest hashes**
(the CP52 precedent: `281c8904→42d4b2dc`). std140 alignment mandatory.

**Gating:** pyramid path = spec constant default OFF; temporal lever defaults to CP52 values →
bit-for-bit unchanged until the owner enables device-side.

## 6. Acceptance & verification
- **Unit/golden (JVM):** locked single-level warp golden; pyramid large-motion PSNR/MAE test;
  occlusion temporal-α→0 test; `ChronosTemporalPolicy` lever test; ABI layout test + manifest re-pin
  if push constants change; CP52 pseudo-physics lock-out test stays green.
- **Native:** `:app:externalNativeBuildDebug` green; validation-layer clean on the warp pass.
- **Device (vivo I2405 / Adreno):** (1) fast pan — pyramid-on shows correct correspondence (no
  large-motion warp artifact) vs single-level; (2) moving subject — no smear, MTF50 preserved; (3)
  static scene — temporal flicker σ reduced; (4) thermal soak — levels back off, no spike/kill.

## 7. Risks & no-downgrade guardrails
- **Pseudo-physics regression:** CP52 lock-out test forbids reintroducing synthesised displacement.
- **Motion smear:** confidence/occlusion gating + α→0 on disocclusion + boundary feather → impossible
  to fuse misaligned history.
- **Large-motion artifact:** pyramid path is OFF by default and device-validated before enabling.
- **ABI drift:** layout test + dual manifest re-pin in the same commit.
- **Lag/thermal:** level count is governor-gated; first/invalid frame stays pass-through.

## 8. References
- Roadmap: CP52 (+ proposed CP52b) in [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Reference: backward-warp motion compensation; coarse-to-fine pyramidal flow/warp (Bouguet's
  pyramidal Lucas-Kanade); IMAX DMR per-frame detail reconstruction; temporal flicker σ as the
  noise-stability metric.
- Source: `app/src/main/cpp/vulkan_bridge.cpp:5097-5106` (+ CP51 guard 5099-5104);
  `warp_reconstruct.comp`; `WarpReconstructShaderContractTest`; `rendering/ChronosTemporalPolicy.kt`.
- Related prompts: s3-f01, s3-f03, s3-f04
