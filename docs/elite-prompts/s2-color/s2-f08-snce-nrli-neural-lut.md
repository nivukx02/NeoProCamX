# SNCE Stage-11 NRLI Neural Real-Time 3D-LUT GPU Pass — Elite Upgrade Prompt

> Global contract in [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) applies and is not restated.

| Field | Value |
|---|---|
| **Subsystem** | 2 — Color science + SNCE engine |
| **Prompt ID** | s2-f08 |
| **Maps to checkpoint(s)** | CP43 (tetrahedral + extended-range NRLI 3D-LUT) |
| **Current working level** | 🟠 dormant (identity 33³ LUT dispatched at strength 0 = exact passthrough; activates only when a model LUT is loaded — none shipped) |
| **Feature rating (potential)** | 3/10 now → 7/10 target |
| **Code-quality rating (now)** | 7/10 |
| **Risk · Size** | L · M |
| **Depends on** | s2-f01 (CPU LUT bake whose creative look it can carry), s2-f09 (the LUT model hook, which stays null — no-vendor), s2-f03 (uniform discipline) |

## 1. What this feature is
`nrli_lut_apply.comp` is the SNCE Stage-11 GPU 3D-LUT applicator: it samples a 3D LUT and blends by a
`strength` term. It is **dispatched live** with an identity 33³ LUT at `strength = 0.0f`
(`vulkan_bridge.cpp:222-223`) — exact passthrough by default. The infrastructure (sampler, blend,
dispatch) is real and proven; it "activates only when a model LUT is loaded — none shipped" (catalog §2
row 8). NRLI is the slot where a learned or baked adaptive LUT *would* be applied per-frame. The
NO-VENDOR rule means **no learned model ships**; this prompt is about hardening the LUT *applicator*
(interpolation, range, range-extension) so that when a **classical** LUT (e.g. the s2-f01 creative
bake) is routed through it, it is artifact-free.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
The reference is **broadcast/DI-grade 3D-LUT application**:
- **Tetrahedral interpolation** (ACES CLF, DaVinci, Baselight) is the standard, not trilinear:
  trilinear interpolates over a cube's 8 corners and twists hue on saturated diagonals, while
  tetrahedral splits each cell into 6 tetrahedra and interpolates over 4 vertices — eliminating the
  diagonal hue error. This is the single biggest quality lever for LUT application.
- **Extended-range / scene-referred LUTs:** HDR pipelines apply LUTs in log or with input values > 1.0
  (highlights above diffuse white). A naive `[0,1]` LUT clips highlights; the elite form supports an
  input shaper (log/PQ) and extended-range output so highlights survive (RED IPP2, ACES output
  transforms operate well above 1.0).
- LUTs are applied at sufficient cube size (33³ is broadcast-typical; 65³ for fine grades) with
  **smooth, monotonic** entries.
Elite bar, measurable: (a) identity LUT at strength 0 (and at strength 1) is bit-exact passthrough;
(b) tetrahedral interpolation reduces inter-node hue twist on a saturated diagonal to ≤ 1° vs
trilinear's larger error; (c) extended-range input (values > 1.0) is handled without clipping
(highlights monotonic, no posterization); (d) a smooth ramp through any monotonic LUT shows banding
code-delta = 0.

## 3. Current state in NeoProCamX (verified)
- Shader `nrli_lut_apply.comp` compiled (`generated/nrli_lut_apply_comp_spv.h` `#include`d at
  `vulkan_bridge.cpp:50`); pipeline objects `nrliDescLayout/nrliPipelineLayout/nrliPipeline/nrliShader`
  (`vulkan_bridge.cpp:580-583`); descriptor set `nrliDescSets` (`:430`).
- **Live, default passthrough:** `NrliParams` defaults `lutSize = 33.0f`, `strength = 0.0f`
  (`vulkan_bridge.cpp:222-223`); the comment states "strength 0 with the identity LUT == exact
  passthrough (the live default)" (`vulkan_bridge.cpp:220`).
- **No LUT model shipped (and none will — NO-VENDOR).** The LUT-model hook (s2-f09) stays null. So the
  feature today is a correct-but-idle applicator. CP43 is purely about the *applicator's* quality:
  tetrahedral interpolation + extended-range, so a **classical** LUT (e.g. the s2-f01 creative bake)
  can be routed through it with broadcast-grade fidelity.
- Interpolation mode in the shader (trilinear vs tetrahedral) needs verification; the catalog/roadmap
  imply trilinear today (CP43 = "Tetrahedral + extended-range").

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Passthrough lock:** identity LUT at strength 0 AND at strength 1 is bit-exact passthrough (host
   port + the GPU contract test). The dormant default is provably a no-op.
2. **Tetrahedral correctness:** the tetrahedral sampler matches a CPU reference tetrahedral
   interpolation within 1e-4 over random LUTs+coords; on a saturated diagonal it reduces inter-node hue
   twist to ≤ 1° (vs the trilinear baseline measured for the same LUT).
3. **Extended-range:** input values > 1.0 (and an optional log/PQ input shaper) are interpolated
   without clipping; a highlight ramp stays monotonic, banding code-delta = 0.
4. **Classical-LUT routing:** the s2-f01 creative bake can be supplied to NRLI and round-trips its
   intended look within the s2-f01 tolerances — proving the applicator is the only thing changed, not
   the look.
5. **No-downgrade default:** with no LUT loaded (hook null, NO-VENDOR), strength stays 0 → exact
   passthrough, bit-for-bit today. Tetrahedral is a spec-constant defaulting to today's mode until the
   device A/B passes.
6. **Budget (ms):** tetrahedral adds a bounded per-pixel cost (4 fetches + barycentric) vs trilinear's
   8; must fit the preview budget; respects the load governor.

## 5. Implementation prompt (hand this to the agent)
**Scope:** upgrade the NRLI *applicator* to tetrahedral + extended-range behind a gate; keep the LUT
**hook null** (NO-VENDOR). **Non-goals:** do NOT ship a learned/model LUT; do not enable a non-zero
strength by default; do not change the s2-f01 bake. The applicator can be exercised with a classical
baked LUT only.

**Plan (CP43):**
1. **GOLDEN-LOCK FIRST.** Contract test freezing today's NRLI dispatch (identity LUT, strength 0,
   trilinear) — proves the upgrade leaves the default a no-op.
2. **Tetrahedral sampler:** implement in `nrli_lut_apply.comp` behind a spec constant defaulting to the
   current mode; add a CPU reference tetrahedral interpolation + equivalence test (§4.2).
3. **Extended-range input:** support input > 1.0 + optional input shaper; highlight-monotonicity +
   banding test (§4.3).
4. **Classical-LUT route:** add a path to feed the s2-f01 creative bake into NRLI's LUT binding (still
   gated; strength owner-controlled). Round-trip test (§4.4).
5. **Re-pin the shader manifest**; extend the `NrliParams` layout test if any field changes.

**Files:** `app/src/main/cpp/shaders/vulkan/nrli_lut_apply.comp`, `app/src/main/cpp/vulkan_bridge.cpp`
(LUT binding source, spec constant), the `NrliParams` layout test; CPU reference + tests under
`app/src/test/.../snce/` or `.../color/`.

**ABI:** `NrliParams` (`vulkan_bridge.cpp:220-223`) is a contract surface; field add → layout test +
manifest re-pin in the same commit.

**Gating:** strength default 0 + identity LUT (NO-VENDOR hook null); tetrahedral/extended-range behind
spec constants defaulting to today.

## 6. Acceptance & verification
- **JVM:** passthrough lock, tetrahedral-equivalence + hue-twist, extended-range monotonicity, classical
  -LUT round-trip tests green.
- **Native:** `:app:externalNativeBuildDebug` green; validation-layer clean; manifest re-pinned.
- **Device (vivo):** strength 0 → bit-match today; routing the creative bake through NRLI with
  tetrahedral on shows no hue twist on saturated diagonals and clean highlights. Owner confirms.

## 7. Risks & no-downgrade guardrails
- **Accidental non-zero strength:** default strength 0 + hook null; passthrough lock test.
- **Hue twist (trilinear) / highlight clip:** tetrahedral + extended-range, each gated and measured.
- **Shipping a vendor model:** explicitly forbidden — the LUT hook stays null (s2-f09); only classical
  baked LUTs are routed.
- **Lag:** bounded tetrahedral cost; governor backoff; off cold-start.

## 8. References
- Roadmap CP43: [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Standard canon (ACES CLF tetrahedral, RED IPP2 extended range, broadcast 33³ LUTs): [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) §2
- Source: `app/src/main/cpp/vulkan_bridge.cpp:50, 220-223, 430, 580-583`; `app/src/main/cpp/shaders/vulkan/nrli_lut_apply.comp`
- Related prompts: s2-f01, s2-f03, s2-f09
