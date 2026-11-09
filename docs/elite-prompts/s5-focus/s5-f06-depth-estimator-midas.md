# DepthEstimator (MiDaS Monocular Depth + Temporal Fusion + Bokeh) — Elite Upgrade Prompt

> Read [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) first. Its global contract applies and
> is not restated here.

| Field | Value |
|---|---|
| **Subsystem** | 5 — Focus / Autofocus |
| **Prompt ID** | s5-f06 |
| **Maps to checkpoint(s)** | new — propose CP-F7 (bokeh-renderer wiring + depth temporal hardening) |
| **Current working level** | 🔵 live-optional (depth feeds AF/masks; **bokeh renderer has no production caller**) |
| **Feature rating (potential)** | 6/10 now → 8/10 target |
| **Code-quality rating (now)** | 8/10 |
| **Risk · Size** | M · L |
| **Depends on** | s5-f03 (FocusEngine consumer), s5-f05 (depth prior), s7 AI pipeline |

## 1. What this feature is
`DepthEstimator` runs MiDaS v2.1-small TFLite (GPU/NNAPI/CPU fallback) to produce a per-frame
monocular depth map, then applies multi-frame temporal fusion, edge-aware refinement, and guided
upsampling, with a synthetic radial-depth fallback when inference is unavailable. The depth map
feeds subject scoring (s5-f03) and segmentation/peaking masks. A Vogel-spiral bokeh compute shader
exists (`bokeh_vogel_spiral.comp`) intended to consume this depth for a depth-of-field render —
but it has **no production caller** (no `vulkan_bridge.cpp` dispatch), so the depth-driven bokeh
capability is built and shader-contract-tested yet never reaches pixels.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
Real cinema DoF is optical — a fast prime at T1.3 yields a continuous, aberration-correct
out-of-focus field with real bokeh shape (aperture-blade-count polygons, cat's-eye vignetting at
the edges, smooth circle-of-confusion growth with defocus distance). Computational bokeh (Pixel
Portrait, iPhone Cinematic Mode) approximates this from a depth map: (1) **physically-plausible CoC**
— blur radius grows with |depth − focus-plane| per a thin-lens model, not a flat blur; (2)
**occlusion-correct compositing** — foreground edges don't bleed onto sharp background; (3)
**stable depth over time** — no per-frame depth flicker causing blur "boiling". Translate to
targets: **CoC follows a thin-lens curve vs. defocus, edges are occlusion-respecting, temporal
depth flicker σ is bounded, and depth EPE on a known-distance fixture is within the MiDaS-class
error band.**

## 3. Current state in NeoProCamX (verified)
- `DepthEstimator.kt` (`ai/DepthEstimator.kt`): real MiDaS v2.1-small interpreter, GPU/NNAPI/CPU
  selection, temporal fusion + edge-aware refine + guided upsample, radial fallback (catalog §5/§7).
- **Bokeh renderer is dead:** `bokeh_vogel_spiral.comp` exists under
  `app/src/main/cpp/shaders/vulkan/` and is exercised only by
  `VulkanBokehVogelShaderContractTest.kt` (shader-contract test). A `grep` for `bokeh` across
  `vulkan_bridge.cpp` returns **no matches** — there is no descriptor build, no dispatch, no
  production caller. The shader compiles and is contract-tested but never runs live.
- Status: **depth = live-optional; bokeh = ⚪ scaffold (no caller).** Wiring prompt for bokeh +
  temporal hardening for depth.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Bokeh wired (NEW):** `bokeh_vogel_spiral.comp` gets a real Vulkan dispatch fed by the live
   DepthEstimator output + the focus plane, behind a no-downgrade gate. Provable by a validation-
   layer run (no uninitialized-read / UNDEFINED-layout warnings) + a golden render fixture.
2. **Thin-lens CoC:** the bokeh blur radius follows a thin-lens CoC curve vs. defocus distance
   (host port of the radius math tested against the analytic curve).
3. **Occlusion-respecting edges:** foreground/background separation at a depth discontinuity does
   not bleed sharp background onto blurred foreground (golden edge fixture).
4. **Temporal depth stability:** multi-frame fused depth flicker σ on a static scene is bounded
   below a threshold (no blur boiling); test on a synthetic temporal sequence.
5. **Depth accuracy:** depth EPE on a known-distance fixture within the MiDaS-class error band
   (sanity bound, not a fake claim).
6. **No-downgrade default:** bokeh dispatch defaults **OFF** (gate / specialization constant);
   with it off, the live preview/record pixels are bit-for-bit unchanged. Depth's existing AF/mask
   consumers are untouched.
7. **Budget:** bokeh runs only when enabled and within the preview frame budget; reuses the depth
   map already computed for AF (no second inference); defers off the cold-start path.

## 5. Implementation prompt (hand this to the agent)
**Scope:** wire the existing Vogel-spiral bokeh shader to live depth + focus plane behind a
no-downgrade gate, and harden depth temporal stability. **Non-goals:** do NOT replace MiDaS or add
any new depth model (no-vendor); do not change the depth consumers in s5-f03; do not enable bokeh
by default.

**Plan:**
1. **Golden-lock first.** Pin current preview/record output (bokeh off = today's pixels) as the
   golden; pin the depth map output for a fixture frame.
2. Build the Vulkan descriptor/dispatch for `bokeh_vogel_spiral.comp` in `vulkan_bridge.cpp`,
   reading the live depth image + a focus-plane / CoC-scale push constant; deterministically
   initialize every binding (no UB).
3. Add the host CoC-math port + tests; add the temporal-flicker test on a synthetic depth sequence.
4. Gate the bokeh blend behind a flag/specialization constant defaulting OFF (strength 0 =
   passthrough).

**Files:** `app/src/main/cpp/vulkan_bridge.cpp` (descriptor + dispatch + clear),
`app/src/main/cpp/shaders/vulkan/bokeh_vogel_spiral.comp` (only if a CoC push field is added),
`ai/DepthEstimator.kt` (temporal hardening), the bokeh push-constant struct + Kotlin mirror,
new tests.

**ABI:** if a focus-plane / CoC-scale push constant is added, extend `GpuPushConstantLayout` +
add a `BokehPushConstantLayoutTest` asserting slot order + int/float typing in the SAME commit;
re-pin the shader-manifest hash.

**Gating:** bokeh dispatch behind a flag defaulting OFF; default = exact passthrough, proven by the
golden lock and the validation-layer run.

## 6. Acceptance & verification
- **Unit/golden (JVM):** bokeh-off passthrough golden; CoC thin-lens math test; occlusion-edge
  golden; temporal depth-flicker σ test; depth EPE sanity bound; push-constant layout test (if added).
- **Native:** `:app:externalNativeBuildDebug` green; debug validation-layer run reports zero
  uninitialized-read / UNDEFINED-layout warnings on the bokeh pass.
- **Device (vivo I2405 / Adreno):** bokeh ON — DoF follows focus plane, no edge bleed, no blur
  boiling on a static scene, within frame budget; bokeh OFF — identical to today; thermal soak —
  bokeh backs off via governor.

## 7. Risks & no-downgrade guardrails
- **UB / black-out (new dispatch):** deterministic binding init + visible blend gated OFF + device
  verify before trusting (the MFSR pattern, s1-f06).
- **Blur boiling:** temporal-fusion σ bound + test.
- **Edge bleed / halos:** occlusion-respecting compositing + edge golden.
- **No new model:** reuses shipped MiDaS v2.1 (no-vendor).
- **Lag / thermal:** bokeh only when enabled, reuses existing depth, governor backoff, off
  cold-start path.

## 8. References
- Roadmap: propose CP-F7 in
  [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Reference: thin-lens circle-of-confusion / DoF math; Pixel Portrait, iPhone Cinematic Mode
  computational bokeh; Vogel-spiral sampling for disc kernels.
- Source: `ai/DepthEstimator.kt`, `app/src/main/cpp/shaders/vulkan/bokeh_vogel_spiral.comp`,
  `app/src/test/.../rendering/VulkanBokehVogelShaderContractTest.kt`, `vulkan_bridge.cpp` (no bokeh
  dispatch present)
- Related prompts: s5-f03, s5-f05, s1-f06 (the UB-safe dispatch pattern)
