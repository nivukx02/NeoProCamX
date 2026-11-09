# Dormant Shaders (photon_hdr_fusion / dual_gain_fusion / ndr10_knee_curve / denoise / sharpen / nivukx_vision_denoise) — Elite Upgrade Prompt

> Global contract in [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) (no-downgrade, no-vendor,
> commit-per-feature, golden-first, ABI-locked, device-gated) applies and is not restated.

| Field | Value |
|---|---|
| **Subsystem** | 4 — Denoise/Detail + HDR/Tone |
| **Prompt ID** | s4-f06 |
| **Maps to checkpoint(s)** | CP31 (dual_gain ACEScg fusion), CP32 (photon dual-exposure motion-comp fusion), CP33 (wire ndr10_knee_curve before encode), CP23 (dormant detail-safe denoise pass) |
| **Current working level** | 🟠 dormant (compile to SPV; never built into pipelines — some only `#include`d for a word-count log) |
| **Feature rating (potential)** | 2/10 now → 4–5/10 target (per-shader; the WIRE ones unlock CP30-class dynamic range) |
| **Code-quality rating (now)** | 4–5/10 (the photon/dual-gain math is sound; denoise/sharpen are dead duplicates) |
| **Risk · Size** | H · XL (collectively) |
| **Depends on** | s4-f03 (knee/tone — CP33), s4-f01 (live denoise — CP23/de-dup), s3-f01 (Argus flow — photon motion comp), s1 (RAW dual-exposure source for CP31/CP32) |

## 1. What this feature is
A cluster of compiled-but-unwired shaders in the denoise/HDR subsystem. Each must be triaged
honestly as **WIRE** (real elite value, no-downgrade) or **RETIRE** (true duplicate / dead SPV):
- `photon_hdr_fusion.comp` — motion-compensated dual-exposure (long/short) radiance fusion.
- `dual_gain_fusion.comp` — ACEScg low-conversion-gain / high-conversion-gain readout fusion.
- `ndr10_knee_curve.comp` — the NDR10 sibling of the live `knee_curve.comp`.
- `denoise.comp` + `sharpen.comp` — generic denoise/sharpen.
- `nivukx_vision_denoise.comp` — a NIVUKX denoise variant, SPV-only.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
The two HDR-fusion shaders map directly to **how cinema sensors get their dynamic range**:
- **Dual-conversion-gain / dual-gain readout.** ARRI's ALEXA uses a dual-gain architecture (two
  readouts per photosite, high + low gain, merged) to reach ~17 stops; Sony VENICE 2 uses
  **dual base ISO 800/3200** (two analog conversion-gain modes). The HCG path is clean in shadows,
  the LCG path holds highlights; fusing them in scene-linear (ACEScg) recovers range no single
  readout has. `dual_gain_fusion.comp` is exactly this LCG/HCG → fused-ACEScg merge.
- **Motion-compensated dual-exposure fusion.** Where dual-gain isn't available, a long/short
  exposure pair is fused — but a *static* blend ghosts on motion. The cinema/computational standard
  aligns the short exposure to the long with optical flow **before** blending (Debevec-style
  radiance weighting + alignment). `photon_hdr_fusion.comp` does precisely this (Argus motion field,
  highlight-confidence weighting).
- **NDR10 knee** is the project's display-referred tone sibling (see s4-f03 / BT.2390 EETF).
The elite bar for the WIRE set: measurable dynamic-range gain (stops / highlight headroom) over a
single readout/exposure, **zero ghosting** on motion (flow-aligned), no color shift in ACEScg, all
behind a no-downgrade gate. The RETIRE set must be removed without touching any live pixel.

## 3. Current state in NeoProCamX (verified)
- **`photon_hdr_fusion.comp`** — `app/src/main/cpp/shaders/vulkan/photon_hdr_fusion.comp` exists and
  is **well-written** (self-documented "OmniClarity §1 Photon HDR Fusion Core … 100% classical,
  zero learned weights", flow-aligned `confidence_long = smoothstep(highlightThreshold, clipThreshold,
  luma(long_scaled))` blend, bindings long/short/motion/fused). SPV header
  `cpp/generated/photon_hdr_fusion_comp_spv.h` exists but is **NOT `#include`d in `vulkan_bridge.cpp`
  at all** (grep: only the generated header file references the name) → never compiled into a pipeline.
- **`dual_gain_fusion.comp`** — `cpp/shaders/vulkan/dual_gain_fusion.comp` (LCG/HCG ACEScg inputs →
  fused ACEScg + motion confidence). SPV header `#include`d `vulkan_bridge.cpp:29`, **but used ONLY
  in a word-count diagnostic log** — `kDualGainFusionCompSpvCount` (`:7592`) and `dualGainFusionUniform`
  (`:7610`). No pipeline, no dispatch.
- **`ndr10_knee_curve.comp`** — `cpp/shaders/vulkan/ndr10_knee_curve.comp`. SPV header `#include`d
  `vulkan_bridge.cpp:31`, **also only in the word-count log** (`kNdr10KneeCurveCompSpvCount` `:7594`,
  `ndr10KneeCurve=adaptive-sigmoid-modified-reinhard-rgba16f` `:7595`). The plain `knee_curve.comp`
  is the live one (s4-f03). CP33 = wire `ndr10_knee_curve` before encode.
- **`denoise.comp` + `sharpen.comp`** — `cpp/shaders/vulkan/denoise.comp`, `.../sharpen.comp`.
  **Zero native consumers** (grep for `generated/denoise`/`generated/sharpen` references in `cpp/`
  returns nothing). The live denoise is `nivukx_denoise.comp` (s4-f01); the live detail/sharpen is
  `detail_preservation.comp` (s4-f02). These two are generic duplicates.
- **`nivukx_vision_denoise.comp`** — `cpp/shaders/vulkan/nivukx_vision_denoise.comp`. **SPV-only**:
  only `cpp/generated/nivukx_vision_denoise_comp_spv.h` references the name; never `#include`d into
  `vulkan_bridge.cpp`. The live denoise is `nivukx_denoise.comp`.

**Triage decision (honest, no-downgrade):**
- **WIRE — `photon_hdr_fusion.comp` (CP32):** real value (dynamic range + motion-comp). Device-gated.
- **WIRE — `dual_gain_fusion.comp` (CP31):** real value (dual-gain DR). Device-gated, needs a
  dual-gain RAW source — only available on capable sensors.
- **WIRE — `ndr10_knee_curve.comp` (CP33):** real value (NDR10 display-referred tone before encode),
  dependent on s4-f03/CP30; must stay signature-parity with the live curve.
- **RETIRE — `denoise.comp`, `sharpen.comp`:** true duplicates of the live nivukx/detail passes;
  no production path. Remove (or quarantine + document) — but see CP23 note below.
- **RETIRE — `nivukx_vision_denoise.comp`:** SPV-only variant superseded by the live
  `nivukx_denoise.comp`; retire unless it carries an algorithm the live one lacks (audit first).
- **CP23 caveat:** CP23 is "activate a dormant wavelet/NLM **flat-region detail-safe** pass." If
  `denoise.comp` (or a wavelet variant) contains that detail-safe algorithm, it is WIRE for CP23,
  not RETIRE — audit its math before deleting. Do not retire an algorithm s4-f01 doesn't have.

## 4. Elite target spec (the definition of "10000% best", made testable)
**WIRE set:**
1. **dual_gain (CP31):** LCG+HCG ACEScg inputs fused to a single ACEScg image with measurable DR
   gain over either input alone (highlight headroom from LCG, shadow SNR from HCG); no color shift
   (ACEScg neutrals preserved, ΔE2000 within tolerance); behind a flag defaulting OFF.
2. **photon (CP32):** long/short fused with the Argus motion field aligning short→long **before**
   blend; **no ghosting** on a moving-subject golden (vs a static-blend baseline that ghosts);
   highlight detail from the aligned short where the long clips; flag default OFF.
3. **ndr10_knee (CP33):** wired before encode, signature-parity with the live `knee_curve.comp`
   (preview = record = export); no banding increase; flag default OFF (or only-on under NDR10 mode),
   depends on s4-f03/CP30.
4. **No-downgrade for all WIRE:** until device-validated, output is not blended into the visible path
   (strength 0 / flag off); today's pixels bit-for-bit unchanged.

**RETIRE set:**
5. **denoise/sharpen/nivukx_vision_denoise:** removed (or quarantined under a clearly-dead path with
   a "retired, superseded by X" note) with **zero live-pixel change** — proven by the existing
   golden suite staying green and the dispatch-manifest characterization test (CP36) showing the
   live dispatch set unchanged. RETIRE only after the CP23 algorithm audit confirms no unique value.

## 5. Implementation prompt (hand this to the agent)
**Scope:** triage each shader, WIRE the three HDR/tone shaders behind no-downgrade gates, RETIRE the
true duplicates after an algorithm audit. **Non-goals:** do not blend any WIRE output into the visible
path by default; do not delete a shader whose algorithm the live path lacks (CP23); do not change the
live `knee_curve`/`nivukx_denoise`/`detail_preservation` passes (s4-f01/f02/f03 own those); no-vendor.

**Plan (one CP = one commit):**
1. **STEP 0 — dispatch-manifest characterization (CP36, do first).** Add a test that pins the exact
   live dispatch set (which pipelines are bound/dispatched per frame). This proves both that WIRE
   shaders are added only behind gates and that RETIRE removals don't change the live set.
2. **STEP A — CP23 algorithm audit.** Read `denoise.comp` (+ any wavelet/NLM variant) and
   `nivukx_vision_denoise.comp`; compare against the live `nivukx_denoise.comp` (s4-f01). Decide
   WIRE-for-CP23 (flat-region detail-safe pass the live path lacks) vs RETIRE (duplicate).
3. **STEP B — CP31 dual_gain.** Build the pipeline/descriptor/dispatch for `dual_gain_fusion.comp`;
   feed LCG/HCG ACEScg from the dual-gain RAW source. Gate `SC_DUAL_GAIN_FUSION` default OFF; device-gated.
4. **STEP C — CP32 photon.** Build the pipeline for `photon_hdr_fusion.comp`; feed long/short + the
   Argus motion field (s3-f01). Gate `SC_PHOTON_FUSION` default OFF; device-gated.
5. **STEP D — CP33 ndr10_knee.** Build the `ndr10_knee_curve` pipeline before encode, parity-locked to
   the live curve (depends on s4-f03/CP30). Gate default OFF.
6. **STEP E — RETIRE.** Remove `denoise.comp`/`sharpen.comp`/`nivukx_vision_denoise.comp` (and their
   word-count-log-only references for dual_gain/ndr10 once they're real pipelines), proving the live
   dispatch manifest is unchanged. Document each retirement.

**Files:** `cpp/shaders/vulkan/{photon_hdr_fusion,dual_gain_fusion,ndr10_knee_curve,denoise,sharpen,
nivukx_vision_denoise}.comp`, `cpp/vulkan_bridge.cpp` (new pipelines/descriptors/dispatch for the WIRE
set; remove the word-count-log-only `#include`s on retire), the RAW dual-exposure/dual-gain source
plumbing (s1), SPV regen + headers.

**ABI:** each WIRE shader's push constants (`PhotonFusionParams`, `DualGainFusionParams`, the ndr10
knee params) need a Kotlin mirror + `GpuPushConstantLayout` extension + `*PushConstantLayoutTest` in
the SAME commit, and a shader-manifest hash entry. std140 alignment required.

**Gating:** every WIRE path behind a spec-constant defaulting OFF (strength 0 = passthrough); RETIRE
changes prove zero live-dispatch delta via the CP36 manifest test.

## 6. Acceptance & verification
- **Unit/golden (JVM):** CP36 dispatch-manifest test green (live set unchanged); host ports of the
  photon highlight-confidence blend and dual-gain fusion weighting; RETIRE proven by the full golden
  suite staying green.
- **Native:** `:app:externalNativeBuildDebug` green; validation-layer clean on any new pipeline;
  manifest hashes added; layout tests extended.
- **Device (vivo I2405 / Adreno):** (1) dual_gain — fused DR exceeds either readout, neutrals
  preserved; (2) photon — moving subject, no ghost vs static-blend; (3) ndr10_knee — parity with live
  curve, no banding; (4) all gates off — output bit-for-bit unchanged; RETIRE build runs identically.
- **Gate keys:** any DR/HDR claim from the WIRE shaders must prove through `OmegaCapabilityGate` from
  real transport/bit-depth — never synthesize a PASS.

## 7. Risks & no-downgrade guardrails
- **Black-out / UB on a newly-wired pipeline:** WIRE outputs gated off + device-verify before any
  visible blend (the s1-f06 MFSR lesson — never trust a new dispatch into the visible path blind).
- **Ghosting (photon):** flow-aligned short→long before blend + highlight-confidence weighting.
- **Color shift (dual_gain):** ACEScg neutral preservation + ΔE2000 golden.
- **Banding (ndr10_knee):** parity-lock to the live curve + banding code-delta golden.
- **Accidental live-pixel change on RETIRE:** CP36 dispatch-manifest test makes any live-set delta fail.
- **Deleting a unique algorithm:** CP23 audit gate before any retirement.

## 8. References
- Roadmap: CP31/CP32/CP33/CP23 (and CP36 manifest test) in [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Tech: [`../technology_explanations/markdown/01-ndr10-dynamic-hdr.md`](../technology_explanations/markdown/01-ndr10-dynamic-hdr.md), [`../technology_explanations/markdown/NDR10_PLUS_DETAILED_TECHNOLOGY.md`](../technology_explanations/markdown/NDR10_PLUS_DETAILED_TECHNOLOGY.md)
- Reference: ARRI ALEXA dual-gain architecture; Sony VENICE 2 dual-base-ISO 800/3200; Debevec exposure-bracket fusion; motion-compensated multi-exposure fusion; BT.2390 EETF.
- Source: `cpp/shaders/vulkan/photon_hdr_fusion.comp`, `.../dual_gain_fusion.comp`, `.../ndr10_knee_curve.comp`, `.../denoise.comp`, `.../sharpen.comp`, `.../nivukx_vision_denoise.comp`; `cpp/vulkan_bridge.cpp:29, 31, 7592, 7594, 7610` (word-count-log-only refs); `cpp/generated/photon_hdr_fusion_comp_spv.h` (not #included)
- Related prompts: s4-f01 (live nivukx denoise / CP23), s4-f02 (live detail/sharpen), s4-f03 (live knee / CP33 parity), s4-f04 (still HDR merge), s4-f07 (the test-only bilateral/log-denoise filters), s3-f01 (Argus flow)
