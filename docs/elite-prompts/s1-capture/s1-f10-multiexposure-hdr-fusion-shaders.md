# Multi-Exposure HDR Fusion Shaders (dual-gain / photon) — Elite Upgrade Prompt

> Read [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) first — its global contract
> (no-downgrade, no-vendor, commit-per-feature, golden-first, ABI-locked, device-gated) applies
> here and is not restated.

| Field | Value |
|---|---|
| **Subsystem** | 1 — Capture, RAW & Multi-frame |
| **Prompt ID** | s1-f10 |
| **Maps to checkpoint(s)** | CP31 (dual-gain ACEScg fusion), CP32 (photon dual-exposure motion-compensated fusion) — both `gated` |
| **Current working level** | 🟠 dormant (`dual_gain_fusion` #included for a word-count log only; `photon_hdr_fusion` not even #included) |
| **Feature rating (potential)** | 2/10 now → 6/10 target |
| **Code-quality rating (now)** | 6/10 |
| **Risk · Size** | H · XL |
| **Depends on** | s1-f01 (RAW import), s1-f09 (ACEScg), s1-f03 (noise model), s1-f06 (alignment for photon merge), CP30 (peak-nits tone map) |

## 1. What this feature is
Two GPU compute shaders for extending dynamic range at capture: **dual-gain fusion** merges the
high- and low-conversion-gain readouts of a dual-conversion-gain sensor into one high-DR ACEScg
frame; **photon HDR fusion** merges two differently-exposed frames (motion-compensated) into a
single high-DR result. Both exist as compiled SPIR-V with merge policies, but neither is built into
a live pipeline, so the recorded dynamic range is whatever a single readout gives.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
High DR is achieved by *combining two honest reads*, weighted by their noise:
- **Dual-conversion-gain / dual-base-ISO sensors:** the **ARRI ALEXA 35** reads its ALEV 4 sensor at
  two conversion gains and fuses them into 17 stops; **Sony VENICE 2** has dual base ISO (800/3200)
  — the low-gain read holds highlights, the high-gain read holds shadows, merged by SNR per region.
- **Exposure-bracket merge (Debevec/Mertens):** classic HDR fuses brackets weighted by a confidence
  function (avoid clipped/under-exposed samples); the elite version is **motion-compensated** so a
  moving subject doesn't ghost between the two exposures.
- **Scene-linear merge:** the fusion happens in scene-linear (ACEScg), so the combined signal is
  physically additive and a downstream tone map (CP30 peak-nits) renders it — never an 8-bit clip.

Engineering targets translated: (1) **measurable DR gain** — fused frame resolves highlight + shadow
detail neither single read holds (highlight clip recovered, shadow SNR improved); (2) **SNR-optimal
weighting** — the per-pixel blend uses the s1-f03 noise model so the merge tracks real sensor noise,
not a constant; (3) **zero ghosting** on motion (photon path uses s1-f06 alignment); (4) merge in
**ACEScg scene-linear**, neutral preserved (ΔE2000 < 1.0); (5) real-time-budgeted with a tone map
to display (CP30) — no SDR highlight clip.

## 3. Current state in NeoProCamX (verified)
- `dual_gain_fusion`: SPV header `#include`d at `vulkan_bridge.cpp:29`
  (`#include "generated/dual_gain_fusion_comp_spv.h"` — verified), but the only references are a
  word-count log: `vulkan_bridge.cpp:7592` `dualGainFusionShaderWords=kDualGainFusionCompSpvCount`
  and `7610` `dualGainFusionUniform=…` (verified by grep). **No pipeline is created and no dispatch
  exists** — it is compiled-and-counted, not run.
- `photon_hdr_fusion`: SPV + generated header exist under `app/src/main/cpp/generated/`, but the
  header is **NOT `#include`d in `vulkan_bridge.cpp`** (grep for `photon_hdr_fusion` in the bridge
  returns nothing) — it is not even in the native build.
- Catalog (FEATURE_CATALOG.md §1 + §4, 🟠 feat 2 / quality 6): "Dual-gain/photon merge policies +
  shaders compiled to SPIR-V; *never built into pipelines*"; §4 "`photon_hdr_fusion.comp` (header
  not even #included); `dual_gain_fusion.comp` (#included only for a word-count log)."
- Live HDR today is the still-capture bracket merge (`hdr_merge.comp` via `hdr_merge_native.cpp`,
  subsystem 4) and an **SDR-referred** live tone map that clamps highlights ~0.92 (roadmap headline).
  So these two fusion shaders are pure capability superset, not a regression risk.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **In the build + dispatched:** the targeted shader (start with `dual_gain_fusion`) has its
   pipeline created and a `vkCmdDispatch` reading two real readouts; for the photon path, `#include`
   the header first. Validation-layer run shows zero UNDEFINED-layout / uninitialized-read warnings.
2. **DR gain measured:** on a high-contrast synthetic target, the fused frame recovers highlight
   detail clipped in the high-gain read AND shadow detail noisier in the low-gain read; a host-port
   golden asserts both regions improve (no clip, shadow SNR up).
3. **SNR-optimal weighting:** the per-pixel blend weight uses the s1-f03 noise model; a test proves
   the weight tracks `σ²(signal)` (more weight to the lower-noise read per region).
4. **No ghosting (photon path):** motion-compensated merge via s1-f06 alignment; a moving-subject
   golden shows residual within baseline (no double-image).
5. **ACEScg + tone map:** merge in scene-linear ACEScg (s1-f09), neutral preserved (ΔE2000 < 1.0),
   handed to the CP30 peak-nits tone map (no SDR clamp on the fused output).
6. **No-downgrade default:** fusion output is **not blended into the visible/record path** by default
   (flag/specialization constant defaulting off = exact passthrough to today's pipeline). Both CP31
   and CP32 are `gated` — device-validated before any live use.
7. **Budget:** per-frame dual-read merge within budget; pipeline compile deferred off cold-start;
   governor backoff under thermal/load; photon's extra exposure/alignment cost is adaptive.

## 5. Implementation prompt (hand this to the agent)
**Scope:** build + dispatch the dual-gain fusion shader (CP31) on real dual-gain reads, behind a
no-downgrade off gate; foundation-only for the photon path (CP32).
**Non-goals:** do not change the live still-HDR `hdr_merge` path; do not blend fusion into the
visible output by default; do not add a new HDR model/library; do not touch the live SDR tone map
here (that's CP30).

**Plan:**
1. **Golden-lock** today's HDR/record output so the off-default is provably unchanged.
2. **Host-port the merge math** (dual-gain SNR-weighted blend) to JVM and golden it: DR gain,
   noise-tracking weight, neutral preserved.
3. **Create the pipeline + dispatch (CP31).** Promote `dual_gain_fusion` from word-count-only to a
   real pipeline: descriptor layout, pipeline create/teardown (mirror the demosaic pattern at
   `vulkan_bridge.cpp:4045-4062` / `3778-3788`), import the two readouts, dispatch, bind all inputs
   deterministically. Merge in ACEScg (s1-f09); weights from s1-f03 noise model.
4. **Photon path (CP32) foundation.** `#include` the `photon_hdr_fusion` header into the build; wire
   its pipeline but keep it dormant; the motion-compensated alignment comes from s1-f06 — device-gated.
5. **Gate + fallback.** Flag/specialization constant defaulting off (passthrough); existing path
   untouched.
6. **Device note.** DEVICE-GATED: dual-gain readout availability + DR/ghosting validation on the vivo.

**Files:** `app/src/main/cpp/vulkan_bridge.cpp` (pipeline create/teardown + dispatch + UBO; add the
photon `#include`), `app/src/main/cpp/shaders/vulkan/dual_gain_fusion.comp`,
`.../photon_hdr_fusion.comp`; the merge-policy Kotlin + its UBO mirror.

**ABI:** the fusion UBO (exposure ratios, noise coefficients, weight params) is std140 — extend
`GpuPushConstantLayout` + add a layout test in the same commit; re-pin the shader-manifest hashes
for `dual_gain_fusion` (and `photon_hdr_fusion` once included).

**Gating:** specialization constant / flag default off = exact passthrough; both CP31/CP32 are
`gated` — no live pixel change until device-validated.

## 6. Acceptance & verification
- **Unit/golden (JVM):** record golden unchanged; host dual-gain merge golden (DR gain, neutral
  preserved); noise-weighting test (weight tracks `σ²`); photon motion-compensated no-ghost golden
  (foundation); fusion UBO layout test.
- **Native:** `:app:externalNativeBuildDebug` green with the photon `#include`; validation-layer run
  on the dual-gain dispatch reports **zero** UNDEFINED-layout / uninitialized-read warnings.
- **Device (vivo I2405):** dual-gain capable → high-contrast scene shows recovered highlight +
  cleaner shadow vs single read; moving subject (photon) shows no ghosting; thermal backoff verified.
- **Gate keys:** HDR/DR capability proves through `OmegaCapabilityGate` from real transport — never
  synthesized.

## 7. Risks & no-downgrade guardrails
- **UB / black on fusion branch:** deterministic binding init + validation gate + off-by-default →
  impossible to ship a black/garbage fused frame.
- **Ghosting (photon):** motion-compensated alignment (s1-f06) + confidence weighting + dormant
  until device-validated.
- **Grain amplification:** SNR-optimal weighting from the real s1-f03 noise model bounds shadow
  boost.
- **SDR clip of fused output:** merge in ACEScg + hand to CP30 peak-nits map (don't clip at 0.92).
- **Lag / thermal:** deferred compile + governor backoff; photon cost adaptive.

## 8. References
- Roadmap: CP31, CP32 (both `gated`), dep CP30 in [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Tech: [`../technology_explanations/markdown/01-ndr10-dynamic-hdr.md`](../technology_explanations/markdown/01-ndr10-dynamic-hdr.md), [`../technology_explanations/markdown/11-pixel-reality.md`](../technology_explanations/markdown/11-pixel-reality.md)
- Reference: ARRI ALEXA 35 dual-conversion-gain (17 stops); Sony VENICE 2 dual base ISO; Debevec/Mertens exposure-bracket merge; motion-compensated HDR.
- Source: `app/src/main/cpp/vulkan_bridge.cpp:29` (dual_gain include), `7592`/`7610` (word-count log only), no `photon_hdr_fusion` include (verified); `app/src/main/cpp/shaders/vulkan/dual_gain_fusion.comp`, `.../photon_hdr_fusion.comp`
- Related prompts: s1-f01, s1-f03, s1-f06, s1-f09
