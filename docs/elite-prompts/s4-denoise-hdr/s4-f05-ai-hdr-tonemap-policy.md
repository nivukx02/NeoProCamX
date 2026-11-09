# AI HDR Tone-Map Policy (`AIHdrToneMapPolicy` + `HdrImagingMath`) — Elite Upgrade Prompt

> Global contract in [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) (no-downgrade, no-vendor,
> commit-per-feature, golden-first, ABI-locked, device-gated) applies and is not restated.

| Field | Value |
|---|---|
| **Subsystem** | 4 — Denoise/Detail + HDR/Tone |
| **Prompt ID** | s4-f05 |
| **Maps to checkpoint(s)** | CP30 (drives the peak-nits ApexToneMapper params — the policy is the brain, s4-f03 is the GPU pass) |
| **Current working level** | 🔵 live-optional (runs CPU-side via `AIProcessor` on HDR display retone) |
| **Feature rating (potential)** | 7/10 |
| **Code-quality rating (now)** | 8/10 (rich classical policy + BT.2100 math; gap is it's CPU-only and not driving the GPU peak-nits curve) |
| **Risk · Size** | M · M |
| **Depends on** | s4-f03 (the GPU knee/tone pass the policy should drive), scene stats (`HdrLinearStats` from the live pipeline) |

## 1. What this feature is
`AIHdrToneMapPolicy` is the scene-intent brain for HDR tone mapping. It classifies a scene from
linear stats (`HdrLinearStats`: clipped/shadow/midtone ratios, mean/peak luma, DR span) into an
`HdrSceneIntent` (BALANCED / HIGHLIGHT_PRIORITY / SHADOW_PRIORITY / EXTREME_DYNAMIC_RANGE /
CINEMATIC_WIDE_DR / NATURAL_LIGHT_PRESERVE) and resolves an `HdrToneMapProfile` (exposure boost,
white point, shadow lift, midtone contrast, highlight desaturation, ACES/cascade/hybrid curve
selection, maxCLL/maxFALL, local-tone-map strength, skin-tone preservation). The actual pixel math
lives in `HdrImagingMath` (BT.2100 luma-preserving soft-shoulder tone map) plus `HdrSkinToneMath`
(skin weighting). It runs live but **CPU-side** in `AIProcessor` (per-pixel `toneMapLinearRgb`).
The elite gap: this principled policy is **not the source of truth for the live GPU tone curve**
(s4-f03's knee/shadow-lift run on per-mode constants + maxCLL histogram) — so the scene-intelligence
exists but the realtime ▶ path doesn't consume it.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
- **Content-adaptive (dynamic-metadata) tone mapping.** Dolby Vision and SMPTE ST 2094 carry
  per-scene/per-frame metadata (target peak, knee, mid anchor) so the display tone-maps each scene
  optimally rather than with one static curve. The policy → metadata → curve flow here is exactly
  that pattern, classically computed.
- **Luma-preserving tone mapping.** BT.2390 / good filmic operators preserve hue and skin while
  rolling luminance — they tone-map *luma* and re-apply chroma, avoiding the hue-twist of naive
  per-channel curves. `HdrImagingMath`'s "BT.2100 luma-preserving soft-shoulder" is this idea.
- **Scene intent as a grading decision.** A colorist chooses highlight-priority vs shadow-priority
  per scene; the automated equivalent is a robust scene classifier driving a bounded parameter set —
  never an unbounded "AI look" that can blow out or crush.
The elite bar: scene-intent classification is **stable** (no flicker/hunting between intents on a
slowly-changing scene — intent-switch hysteresis), the resolved profile is **bounded** (white point,
shadow lift, contrast within safe ranges), skin tone is **preserved** (skin ΔE2000 within tolerance
through the tone map), and — the key extension — the policy **drives the live GPU peak-nits curve**
(s4-f03) so preview/record match the intelligence.

## 3. Current state in NeoProCamX (verified)
- File: `app/src/main/java/com/neoproc/neoproccamx/ai/AIHdrToneMapPolicy.kt` — `HdrLinearStats`,
  `HdrSceneIntent` (6 intents), `HdrToneMapProfile` (full param set incl. `dynamicMetadataEnabled`,
  `maxContentLightLevel=1000f`, `maxFrameAverageLightLevel=400f`, `localToneMappingStrength`,
  `colorPreservation=0.85f`), `HdrSkinToneMath.skinToneWeight`.
- **Pixel math:** `HdrImagingMath` (in the `recording` package per the import `:4` and
  `recording/HdrImagingMathTest`) provides `toneMapBt2100LumaPreservingRgb(...)` — called from the
  policy.
- **It is genuinely live-optional, CPU-side.** Real production callers: `ai/AIProcessor.kt` uses
  `AIHdrToneMapPolicy.toneMapLinearRgb` (`:273`, `:303`), `shouldRetoneMapDisplay` (`:294`),
  `resolveProfile` (`:297`, `:497`, `:499`); also referenced by `utils/PreferencesManager.kt`.
  `HdrImagingMath` is also used by `pipeline/HdrVersionRenderPolicy`, `recording/HdrStaticMetadataPolicy`,
  `ui/CameraViewModel`.
- **The gap:** the policy/profile is consumed by the **CPU** `AIProcessor` retone path, not by the
  live **GPU** tone curve (`knee_curve.comp`/`shadow_lift.comp`, s4-f03), which runs on per-mode
  color-science constants + the maxCLL histogram. CP30's peak-nits ApexToneMapper is the natural
  consumer — the policy should resolve its params.
- Net: a strong, real, well-tested classical policy — work is **bounded hardening + wiring it to
  drive the GPU peak-nits curve**, not resurrection.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Intent-switch hysteresis:** on a slowly-ramping synthetic scene, the resolved `HdrSceneIntent`
   does not oscillate (no per-frame flip-flop between e.g. BALANCED↔HIGHLIGHT_PRIORITY); intent
   changes only past a stats threshold + dwell. Measurable: intent stable across N near-identical frames.
2. **Bounded profile:** for any `HdrLinearStats` input (incl. degenerate all-clipped / all-black),
   every `HdrToneMapProfile` field stays within safe ranges (white point, shadowLift, midtoneContrast,
   black floor) — no NaN, no blow-out, no crush. Property-test over random stats.
3. **Skin preservation:** through `toneMapBt2100LumaPreservingRgb` with skin weighting, a skin-patch
   ΔE2000 stays within tolerance and hue does not twist (luma-preserving verified).
4. **Drives the GPU curve (CP30 wiring):** the policy resolves the s4-f03 peak-nits ApexToneMapper
   params (target peak nits, knee, mid anchor, shadow lift) so preview/record tone = the policy's
   intent. Verified by a mapping test: a given `HdrToneMapProfile` → the exact push-constant set fed
   to `knee_curve`/`ApexToneMapper`.
5. **No-downgrade default:** the GPU-drive path is gated; until enabled, the CPU `AIProcessor` retone
   and the existing GPU curve are unchanged (bit-for-bit). Owner flips the GPU-drive on-device.
6. **Budget:** classification + profile resolve is cheap CPU work already on the AI path; the GPU
   curve gains no new passes (it just reads policy-resolved params instead of constants).

## 5. Implementation prompt (hand this to the agent)
**Scope:** harden the classifier/profile (hysteresis + bounds + skin), then wire the policy to drive
the live GPU peak-nits tone curve behind a no-downgrade gate. **Non-goals:** do not change the CPU
`AIProcessor` retone output (gate any change); do not introduce an ML model (no-vendor — the
"AI" here is classical scene classification); do not implement the GPU curve itself (that is s4-f03 —
this prompt supplies its params).

**Plan:**
1. **STEP 0 — golden lock.** Freeze current `resolveProfile`/`toneMapLinearRgb` outputs on a fixed
   stats matrix + a fixed bracket (proves CPU path unchanged).
2. **STEP A — hysteresis.** Add dwell/threshold to `HdrSceneIntent` selection so intent doesn't
   oscillate; unit-test stability across near-identical frames.
3. **STEP B — bounds hardening.** Clamp every `HdrToneMapProfile` field to safe ranges; property-test
   over degenerate stats (no NaN/blow-out/crush).
4. **STEP C — skin verification.** Assert luma-preserving + skin ΔE2000 through the tone map.
5. **STEP D — CP30 GPU-drive wiring.** Map `HdrToneMapProfile` → the s4-f03 peak-nits push constants
   (target peak nits / knee / mid anchor / shadow lift). Gate behind `SC_POLICY_DRIVES_GPU_TONE`
   (default off = GPU curve uses today's constants). Coordinate the param contract with s4-f03.
6. **STEP E — device note.** On-device enable + the vivo HDR scene checks (below).

**Files:** `ai/AIHdrToneMapPolicy.kt` (hysteresis + bounds), `recording/HdrImagingMath` (skin/luma
assertions), `ai/AIProcessor.kt` (unchanged default), the param-bridge to s4-f03's push constants
(new mapping function + its test).

**ABI:** the policy itself is pure Kotlin (no ABI). When it drives the GPU curve, the
`HdrToneMapProfile → push-constant` mapping must match the s4-f03 layout exactly; if s4-f03's
push-constant grows, the layout test there owns it — this prompt adds the **mapping test**, not the
layout change.

**Gating:** `SC_POLICY_DRIVES_GPU_TONE` default OFF; CPU retone + GPU curve unchanged until enabled.

## 6. Acceptance & verification
- **Unit/golden (JVM):** STEP-0 lock green; intent-hysteresis stability test; profile-bounds
  property test (no NaN/blow-out over random stats); skin ΔE2000 + luma-preserving test;
  profile→push-constant mapping test.
- **Native:** n/a for the policy; the GPU-drive path's native side is covered by s4-f03.
- **Device (vivo I2405 / Adreno, HDR mode):** (1) slowly-changing scene — no tone-map flicker
  (intent stable); (2) skin in mixed light — natural skin, no hue twist; (3) extreme-DR scene —
  highlight+shadow handled per intent, no blow-out/crush; (4) gate off — output identical to pre-change.
- **Gate keys:** HDR tone/metadata claims (maxCLL/maxFALL, BT.2100) must prove through
  `OmegaCapabilityGate` from real transport — never synthesize.

## 7. Risks & no-downgrade guardrails
- **Tone flicker / hunting:** intent hysteresis + stability golden → impossible to flip-flop.
- **Blow-out / crush / NaN:** profile-bounds property test over degenerate stats.
- **Skin hue twist:** luma-preserving tone map + skin ΔE2000 golden.
- **Live-path regression:** GPU-drive gated off by default; CPU retone unchanged; STEP-0 lock.
- **Param-contract drift with s4-f03:** explicit mapping test pins profile→push-constant.

## 8. References
- Roadmap: CP30 (peak-nits ApexToneMapper the policy drives) in [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Tech: [`../technology_explanations/markdown/01-ndr10-dynamic-hdr.md`](../technology_explanations/markdown/01-ndr10-dynamic-hdr.md), [`../technology_explanations/markdown/NDR10_PLUS_DETAILED_TECHNOLOGY.md`](../technology_explanations/markdown/NDR10_PLUS_DETAILED_TECHNOLOGY.md)
- Reference: Dolby Vision / SMPTE ST 2094 dynamic metadata; ITU-R BT.2390 EETF; BT.2100 luma-preserving tone mapping; ACES RRT; reference-white 203 nits; maxCLL/maxFALL (CTA-861).
- Source: `app/src/main/java/com/neoproc/neoproccamx/ai/AIHdrToneMapPolicy.kt`, `ai/AIProcessor.kt:273,294,297`, `recording/HdrImagingMath` (+ `HdrImagingMathTest`)
- Related prompts: s4-f03 (the GPU peak-nits curve this drives), s4-f04 (still HDR merge), s4-f06 (dormant dynamic-range shaders)
