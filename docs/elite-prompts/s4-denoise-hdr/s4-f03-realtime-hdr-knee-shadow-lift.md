# Realtime HDR Knee + Shadow-Lift Tone Mapping (Pass 3B) — Elite Upgrade Prompt

> Global contract in [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) (no-downgrade, no-vendor,
> commit-per-feature, golden-first, ABI-locked, device-gated) applies and is not restated.

| Field | Value |
|---|---|
| **Subsystem** | 4 — Denoise/Detail + HDR/Tone |
| **Prompt ID** | s4-f03 |
| **Maps to checkpoint(s)** | CP30 (peak-nits ApexToneMapper — remove SDR-referred highlight clamp), CP33 (wire `ndr10_knee_curve` before encode), CP34 (scene-adaptive IMAX local contrast) |
| **Current working level** | 🟢 live-default-on (knee + shadow-lift dispatched every frame) |
| **Feature rating (potential)** | 7/10 |
| **Code-quality rating (now)** | 7/10 (sound SDR-referred knee; the dynamic-range honesty gap is the elite theme) |
| **Risk · Size** | H · XL |
| **Depends on** | HDR histogram (`hdr_metadata_histogram.comp` → maxCLL), s4-f05 (AI HDR tone-map policy drives params), s4-f06 (the dormant `ndr10_knee_curve.comp` is the CP33 target) |

## 1. What this feature is
Pass 3B is the live realtime tone-map stage: `knee_curve.comp` applies an adaptive
sigmoid-weighted modified-Reinhard highlight knee, then `shadow_lift.comp` does an exponential
shadow lift + contrast recovery with triangular dither. The knee point self-clamps to
[0.55, 0.75], hardness to [1.0, 3.0], driven by per-mode color-science when HDR is enabled, with
`maxCLL` supplied by the live GPU HDR histogram. This is the curve that maps the working linear
ACEScg image to the display-referred output for both preview and record. **The honesty problem:**
the live tone map is effectively **SDR-referred** — the modified-Reinhard knee normalizes around a
`peakScale = clamp(maxCLL/1000, 0.25, 4.0)` but rolls highlights into a bounded [0,~1] output, so
real highlight headroom above the knee is compressed away rather than carried as peak nits. Elite
target: a peak-nits-aware `ApexToneMapper` that maps to an explicit display peak (PQ ST 2084 nits /
BT.2390) instead of clamping to an SDR-referred ceiling.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
- **Display-referred to an explicit peak, not a normalized SDR ceiling.** HDR mastering maps scene
  linear to a known display peak luminance in **nits** using PQ (SMPTE ST 2084) or HLG (BT.2100),
  and tone-maps source→target peak with **BT.2390 EETF** (a parametrized Hermite roll-off that
  preserves the diffuse range and rolls only the top). The curve is defined by `L_min/L_max` of
  source and target — it does not clamp highlights to ~0.92 of a normalized range.
- **ACES RRT / filmic tone curves.** The ACES Reference Rendering Transform, and filmic operators
  (Hable "Uncharted 2", Reinhard-extended), define a shoulder that compresses highlights smoothly
  toward an explicit white point while keeping mid-tone contrast — the diffuse-white anchor
  (≈203 nits reference white in HDR) is principled, not a magic constant.
- **IMAX with Laser** projects ~1,000,000:1 contrast with a Rec.2020-class gamut and very high peak
  — the master must *carry* highlight information to be re-mapped per-venue, so highlights are
  rolled, never crushed.
- **Scene-adaptive local contrast.** IMAX DMR and cinema graders adapt contrast/shoulder to the
  scene (histogram-driven), not with one hardcoded pivot.
The elite bar: a tone curve parametrized by an explicit target **peak nits** with a BT.2390-style
EETF roll-off; highlight rolloff carries detail above the diffuse white (no flat clamp), mid-tone
contrast preserved (ΔE2000 of a gray ramp within tolerance), banding code-delta does not increase,
and the curve is scene-adaptive (histogram-driven), not hardcoded.

## 3. Current state in NeoProCamX (verified)
- Shaders: `app/src/main/cpp/shaders/knee_curve.comp`, `app/src/main/cpp/shaders/shadow_lift.comp`.
  SPV headers included `vulkan_bridge.cpp:32` (knee) and `33` (shadow-lift).
- **Both live.** Pipelines created `vulkan_bridge.cpp:1238-1242` (knee) / `1249-1253` (shadow-lift);
  push-constant structs `KneeCurvePushConstants` (`:171`) / `ShadowLiftPushConstants` (`:177`,
  `shadowLift=0.03f` default); bound + dispatched every frame — knee
  `vkCmdBindPipeline(... kneeCurvePipeline)` `6249` / `vkCmdDispatch` `6280`; shadow-lift
  `vkCmdBindPipeline(... shadowLiftPipeline)` `6323` / `vkCmdDispatch` `6346`. They sit after the
  IMAX render and before nivukx in the live chain.
- **SDR-referred clamp confirmed in source:** `knee_curve.comp` `modifiedReinhardKnee` uses
  `peakScale = clamp(maxCLL/DEFAULT_MAX_CLL(1000), 0.25, 4.0)`, `safeKnee = clamp(kneePoint,
  0.55, 0.75)`, and stores a bounded mapped RGB — highlights above the knee are compressed into a
  normalized ceiling, **not** mapped to explicit peak nits. `REFERENCE_WHITE_NITS = 203.0` is
  present but only as a floor on maxCLL, not as a display-referred anchor.
- **`ndr10_knee_curve.comp` is the superseded sibling:** compiled to SPV and `#include`d
  (`vulkan_bridge.cpp:31`) but it appears **only in a word-count diagnostic log**
  (`ndr10KneeCurveShaderWords` / `ndr10KneeCurve=adaptive-sigmoid-modified-reinhard-rgba16f`
  `vulkan_bridge.cpp:7594-7595`) — it is **never built into a pipeline or dispatched**. The plain
  `knee_curve.comp` is live; CP33 is to wire `ndr10_knee_curve` before encode (see s4-f06).
- **Hardcoded local contrast:** shadow-lift uses `DEFAULT_CONTRAST_RECOVERY = 1.10`,
  `DEFAULT_LUMINANCE_THRESHOLD = 0.4` constants — CP34 makes these scene-adaptive.
- Net: real, default-on tone map — work is **dynamic-range honesty + scene adaptivity**, the
  highest-risk live-pixel change in this subsystem → golden-first is non-negotiable.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Peak-nits ApexToneMapper (CP30):** a tone curve parametrized by explicit source/target peak
   (nits) with a BT.2390-style EETF roll-off. On an HDR golden, highlights above diffuse white are
   **rolled, not clamped** — the output preserves monotonic detail up to the target peak (no flat
   plateau at the old ~SDR ceiling). Verify with a synthetic linear ramp: post-curve output is
   strictly monotonic through the highlight region.
2. **Mid-tone fidelity:** diffuse-white anchor at the principled reference (≈203 nits / 0.18 mid
   pivot); ΔE2000 of a gray ramp through the mid-tones within baseline (the new curve must not
   shift mid-tone color/contrast vs the locked SDR path in SDR mode).
3. **Banding:** banding/posterization code-delta on a smooth ramp does not increase vs baseline
   (the EETF roll-off + existing triangular dither must not introduce contouring).
4. **Scene-adaptive local contrast (CP34):** shadow-lift `contrastRecovery`/`luminanceThreshold`
   become histogram-driven (from the live HDR histogram), not the `1.10`/`0.4` constants;
   measurable as a different curve for a low-key vs high-key golden, bounded so it never crushes.
5. **CP33 alignment:** if `ndr10_knee_curve.comp` is wired before encode (s4-f06), record and
   preview tone math stay signature-parity (preview = record = export per the parity policy).
6. **No-downgrade default:** the peak-nits path and scene-adaptive constants are gated behind a
   spec-constant/flag whose default reproduces today's `knee_curve.comp` + `shadow_lift.comp` output
   bit-for-bit; SDR mode in particular must be unchanged until the owner enables HDR-referred mapping.
7. **Budget:** Pass 3B stays within preview budget (EETF is a cheap closed-form roll-off); no extra
   passes; governor unaffected.

## 5. Implementation prompt (hand this to the agent)
**Scope:** add a peak-nits-aware, BT.2390-style tone curve and scene-adaptive local contrast behind
a no-downgrade gate, fixing the SDR-referred highlight clamp **only when the HDR-referred path is
enabled**. **Non-goals:** do not change the SDR-mode output (gate it); do not wire `ndr10_knee_curve`
here (that is s4-f06/CP33 — only ensure parity if it lands); do not add any model/library
(the AI policy that drives params is s4-f05, classical math).

**Plan:**
1. **STEP 0 — golden lock (mandatory, highest priority).** Freeze current `knee_curve.comp` +
   `shadow_lift.comp` output on SDR + HDR goldens (ramp, gray chart, high-key, low-key). This is the
   contract that proves the default path is bit-for-bit unchanged.
2. **STEP A — CP30 peak-nits ApexToneMapper.** Add an EETF/Hermite roll-off parametrized by source
   peak (from maxCLL histogram) and target display peak (nits). Gate behind `SC_APEX_PEAK_NITS`
   (default off = current modified-Reinhard SDR-referred knee). Remove the SDR clamp **only** on the
   gated path.
3. **STEP B — CP34 scene-adaptive contrast.** Drive `shadowLift`/`contrastRecovery`/
   `luminanceThreshold` from the live histogram via a bounded policy; gate behind
   `SC_SCENE_ADAPTIVE_CONTRAST` (default off = the `0.03`/`1.10`/`0.4` constants).
4. **STEP C — parity.** Ensure preview/record/export apply the same curve params (parity policy);
   coordinate with s4-f05 (AI policy) for param source.
5. **STEP D — device note.** Per-gate enable + the vivo HDR checks (below).

**Files:** `app/src/main/cpp/shaders/knee_curve.comp`, `app/src/main/cpp/shaders/shadow_lift.comp`,
`app/src/main/cpp/vulkan_bridge.cpp` (`KneeCurvePushConstants`/`ShadowLiftPushConstants` extension,
histogram feed), a bounded scene-adaptive policy Kotlin class (unit-tested), SPV regen + headers.

**ABI:** extending `KneeCurvePushConstants`/`ShadowLiftPushConstants` (target peak nits, adaptive
contrast params) **requires** extending `GpuPushConstantLayout` + the matching
`*PushConstantLayoutTest` in the SAME commit and re-pinning the shader-manifest hashes for both
shaders. std140 alignment required — this is a contract-locked layout.

**Gating:** spec-constants default OFF; SDR output bit-for-bit unchanged; HDR-referred mapping is an
owner device-flip.

## 6. Acceptance & verification
- **Unit/golden (JVM):** STEP-0 lock green with gates off; a host port of the EETF proves monotonic
  highlight roll-off to target peak and identity-at-default; ΔE2000 gray-ramp test; banding-code-delta
  test on a smooth ramp; scene-adaptive policy bounds test (never crushes).
- **Native:** `:app:externalNativeBuildDebug` green; validation-layer clean on Pass 3B; manifest
  hashes re-pinned; layout test extended.
- **Device (vivo I2405 / Adreno, HDR10/HLG mode):** (1) high-dynamic-range scene — highlights roll
  smoothly, specular detail preserved (not a flat clip); (2) gray ramp — no banding, mid-tone neutral;
  (3) low-key vs high-key — scene-adaptive contrast differs, shadows not crushed; (4) SDR mode —
  output identical to pre-change (gate off).
- **Gate keys:** HDR transport/peak-nits claims must prove through `OmegaCapabilityGate` from real
  bit-depth/transport (BT.2100/HDR10 truth) — never synthesize a PASS.

## 7. Risks & no-downgrade guardrails
- **Highlight clip / blown whites:** EETF roll-off is monotonic to target peak + monotonic-ramp
  golden → no flat clamp on the gated path; SDR path untouched (gate off).
- **Banding from a new curve:** banding-code-delta golden + existing triangular dither preserved.
- **Mid-tone color shift:** ΔE2000 gray-ramp golden anchors diffuse white.
- **Contrast crush (scene-adaptive):** bounded policy + low-key golden proving shadows survive.
- **Parity break:** preview=record=export param-source coordination + parity policy check.
- **Silent regression:** STEP-0 dual SDR/HDR golden lock; this is the most pixel-sensitive change here.

## 8. References
- Roadmap: CP30/CP33/CP34 (and CP31/CP32 dynamic-range sources) in [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Tech: [`../technology_explanations/markdown/01-ndr10-dynamic-hdr.md`](../technology_explanations/markdown/01-ndr10-dynamic-hdr.md), [`../technology_explanations/markdown/NDR10_PLUS_DETAILED_TECHNOLOGY.md`](../technology_explanations/markdown/NDR10_PLUS_DETAILED_TECHNOLOGY.md)
- Reference: ITU-R BT.2390 EETF (HDR tone roll-off); SMPTE ST 2084 PQ; BT.2100 HLG/PQ; ACES RRT; Hable "Uncharted 2" filmic curve; extended Reinhard; IMAX with Laser contrast; reference-white 203 nits.
- Source: `app/src/main/cpp/shaders/knee_curve.comp`, `.../shadow_lift.comp`, `app/src/main/cpp/vulkan_bridge.cpp:31, 32, 33, 171, 177, 1238, 6249, 6323, 7594`
- Related prompts: s4-f05 (AI HDR tone-map policy), s4-f06 (dormant `ndr10_knee_curve` / CP33 wiring), s4-f04 (still HDR merge)
