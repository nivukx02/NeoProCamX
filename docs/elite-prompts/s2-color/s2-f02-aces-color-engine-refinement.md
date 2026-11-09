# AcesColorEngine Per-Pixel Refinement (ACEScg + NDR10 + NivukxVision + Film Density) — Elite Upgrade Prompt

> Global contract in [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) applies and is not restated.

| Field | Value |
|---|---|
| **Subsystem** | 2 — Color science + SNCE engine |
| **Prompt ID** | s2-f02 |
| **Maps to checkpoint(s)** | CP30 (tone/gamut hardening), CP34 (NivukxVision look lock) |
| **Current working level** | 🟡 partial (baked into preview LUT; conditional on high-end + non-log + non-standards profile) |
| **Feature rating (potential)** | 7/10 now → 8/10 target |
| **Code-quality rating (now)** | 8/10 |
| **Risk · Size** | M · M |
| **Depends on** | s2-f01 (it bakes into that LUT), s2-f03 (parameters flow to GPU uniforms), s2-f11 (Oklab gamut cusp it should use) |

## 1. What this feature is
`AcesColorEngine.kt` is the per-pixel physically-motivated grade that runs *inside* the LUT bake:
wide-gamut compression (`compressNeoWideGamutRgb`, `AcesColorEngine.kt:355`), the NDR10 dynamic
tone map (`applyNdr10DynamicToneMap`, `:450`), the NivukxVision perceptual look
(`applyNivukxVisionRgb`, `:422`), a subtractive film-density model
(`applySubtractiveFilmDensity`, `:462`), and a highlight rolloff — composited into the creative LUT
(s2-f01). It is the bridge between scene-referred ACEScg/NeoWideGamut and the displayed look, doing
the gamut/tone/density work that a film-emulation pipeline would.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
The reference is the **ACES RRT/ODT chain** and modern film-emulation density models:
- **ACES:** scene-linear AP0/AP1 → RRT (a fixed look + tone curve) → ODT per display. The
  **gamut-compression** step (ACES 1.3 reference gamut compress / "GamutCompress") pulls
  out-of-gamut scene colors back to AP1 *without hue shift* — this is exactly what
  `compressNeoWideGamutRgb` must emulate, hue-preserving toward a per-hue cusp (s2-f11).
- **Tone:** the RRT's filmic S-curve and **BT.2390 EETF** roll-off define how highlights compress
  without clipping/posterizing; NDR10 here is the in-house equivalent and must hit the same
  monotonic, knee-stable behavior.
- **Subtractive film density:** ARRI/Kodak-style print emulation models dye-layer absorption
  (subtractive CMY) so saturated highlights *desaturate toward white* the way film does, instead of
  hard-clipping a channel. `applySubtractiveFilmDensity` is this model.
Elite bar, measurable: (a) gamut compression is **hue-stable** — ΔHue (Oklab) before/after ≤ 1° for
in-gamut colors, and out-of-gamut colors land on the boundary with hue preserved; (b) the NDR10 tone
curve is **monotonic** with a continuous first derivative (no knee discontinuity → no banding);
(c) film density desaturates highlights monotonically (no channel inversion / no hue flip near
clip); (d) neutral axis is preserved (gray in → gray out, neutral-Duv ≈ 0).

## 3. Current state in NeoProCamX (verified)
- All four entry points verified: `compressNeoWideGamutRgb` (`AcesColorEngine.kt:355`),
  `applyNivukxVisionRgb` (`:422`, default `sceneLux=400`, `kelvin=5500`),
  `applyNdr10DynamicToneMap` (`:450`), `applySubtractiveFilmDensity` (`:462`). The combined path
  (`:491`) chains density → … and clamps to `[0, 1.08]`.
- **Conditional activation:** baked into the preview LUT only for high-end **&&** non-log **&&**
  non-standards profiles (catalog §2 row 2). In log/standards modes it is bypassed (correct — those
  modes carry their own transport).
- **Gamut compression uses an analytic boundary, not the exact cusp.** The exact per-hue cusp work
  (`OklabGamutCusp`/`OklabGamutMapping`, s2-f11) exists and is JVM-tested but **dormant**, and the
  live export shader still uses the analytic `0.365 + 0.080·cos(3h) + 0.035·cos(6h+0.4)` guess
  (`neo_p010_export_enhance.comp:1168`). So today's compression boundary is approximate.
- No golden currently locks the per-pixel output of each stage across a representative color set.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Hue-stable gamut compression:** for 256 hues at high chroma, Oklab ΔHue in→out ≤ 1°; out-of-gamut
   inputs land within 3e-3 of the boundary (matches the s2-f11 cusp test once wired).
2. **Monotonic NDR10:** sampled tone curve is strictly non-decreasing; first-difference is continuous
   across the knee (no step > a small ε) → no banding on a smooth ramp (banding code-delta = 0 in the
   metrics harness).
3. **Film-density honesty:** highlights desaturate monotonically toward neutral; no channel re-crosses
   another (no hue inversion); output stays within the declared `[0, 1.08]` working ceiling.
4. **Neutral preservation:** an 18-step neutral ramp stays neutral (Duv ≤ 0.001, ΔE2000 ≤ 1.0) through
   the full chained path.
5. **No-downgrade default:** the conditional activation (high-end && non-log && non-standards) is
   unchanged; in every other mode this engine stays bypassed exactly as today.
6. **Budget:** runs only in the offline LUT bake (not per-frame) — assert it never enters the
   per-frame path; no cold-start impact.

## 5. Implementation prompt (hand this to the agent)
**Scope:** golden-lock each stage, then make the gamut-compression boundary the *exact* Oklab cusp
(s2-f11) behind a gate, and prove tone/density honesty. **Non-goals:** do not change the activation
condition; do not touch log/standards modes; no new model; do not wire the GPU shader here (that is
s2-f11's device-gated upload).

**Plan:**
1. **GOLDEN-LOCK FIRST.** Characterization test: a fixed palette (neutrals, primaries, skin, sky,
   foliage, out-of-gamut) through `compress`/`ndr10`/`nivukx`/`density` and the chained path; freeze
   outputs. Proves later changes are gated.
2. **Stage tests** per §4.1-4.4 (hue stability, monotonic tone, density honesty, neutral).
3. **Cusp wiring (gated):** add an option for `compressNeoWideGamutRgb` to call
   `OklabGamutMapping.gamutClipPreserveChroma` / the exact cusp (s2-f11) instead of the analytic
   boundary, behind a flag defaulting to today's analytic path (bit-identical until enabled). The GPU
   shader counterpart (`neo_p010_export_enhance.comp:1168`) is device-gated under s2-f11.
4. **Standards parity:** confirm log/standards modes still bypass via the existing condition (test).

**Files:** `app/src/main/java/com/neoproc/neoproccamx/color/AcesColorEngine.kt`,
`NeoWideGamut2.kt` / `NivukxVisionMath.kt` as needed; tests under `app/src/test/.../color/`. No GPU
ABI change here.

**Gating:** the cusp-based compression is a flag defaulting to the analytic boundary; activation
condition untouched.

## 6. Acceptance & verification
- **JVM golden/unit:** palette characterization + the four stage tests green; cusp-on path matches
  s2-f11's boundary test when enabled.
- **Metrics:** banding code-delta = 0 on a smooth ramp through the chained path (ImageQualityMetrics).
- **Device (vivo):** with cusp flag off, preview matches today exactly; with it on, saturated colors
  render at the true boundary with no hue twist or neutral shift. Owner confirms.

## 7. Risks & no-downgrade guardrails
- **Hue flip near clip:** hue-stability + density-monotonicity tests make inversion impossible to ship.
- **Banding:** monotonic-knee test + banding metric guard the tone curve.
- **Activation creep:** explicit test that log/standards modes stay bypassed.
- **Cusp regression:** cusp path gated off by default; characterization lock catches any drift.

## 8. References
- Roadmap: CP30, CP34, and CP40 (cusp foundation) in [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Tech: NDR10 / NIVUKX / NeoGamut docs in [`../technology_explanations/markdown/`](../technology_explanations/markdown/)
- Standard canon (ACES RRT/ODT, gamut compress, BT.2390 EETF, film density): [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) §2
- Source: `app/src/main/java/com/neoproc/neoproccamx/color/AcesColorEngine.kt:355, 422, 450, 462, 491`; `app/src/main/cpp/shaders/vulkan/neo_p010_export_enhance.comp:1168`
- Related prompts: s2-f01, s2-f03, s2-f11
