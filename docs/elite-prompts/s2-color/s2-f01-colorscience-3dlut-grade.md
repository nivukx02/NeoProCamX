# ColorScience Preview/Record 3D-LUT Grade (Creative Look) — Elite Upgrade Prompt

> Global contract in [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) (no-downgrade, no-vendor,
> commit-per-feature, golden-first, ABI-locked, device-gated) applies and is not restated.

| Field | Value |
|---|---|
| **Subsystem** | 2 — Color science + SNCE engine |
| **Prompt ID** | s2-f01 |
| **Maps to checkpoint(s)** | new — propose CP-C1 (hardening; no roadmap CP today) |
| **Current working level** | 🔵 live-optional (CPU-baked 3D LUT, cached, uploaded to GPU) |
| **Feature rating (potential)** | 8/10 now → 8/10 target (hardening, not wiring) |
| **Code-quality rating (now)** | 8/10 |
| **Risk · Size** | M · M |
| **Depends on** | s2-f02 (AcesColorEngine bakes into this LUT), s2-f03 (GPU uniforms), s2-f05 (SNCE nudges slots 1/4/6) |

## 1. What this feature is
This is the live creative-look grade: `ColorScience.kt` bakes a 3D LUT on the CPU by running
`applyActiveColorPipeline(...)` (`ColorScience.kt:779`) per lattice cell, caches it by config
version + size, and hands it to the GPU for the preview and record surfaces. It carries the
profile look, a skin-tone-protected creative grade (`skinToneProtectionStrength`,
`ColorScience.kt:1233`), filmic curve, hue/sat/contrast/highlight/shadow controls, and a neutral
blend toward luma (`ColorScience.kt:1566-1575`). In neutral/WYSIWYG mode the LUT is nulled so the
pipeline is a passthrough. This is the *artistic* layer that sits on top of the physically-correct
ACES/NeoGamut transforms — the place a colorist's intent lives.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
Professional creative looks are applied as **3D LUTs (.cube/CLF) in a defined working space**, never
as ad-hoc per-channel curves:
- **ARRI** ships Look Library / ARRI Look Files (ALF4) authored in LogC4/AWG4; **RED IPP2** applies
  the creative look as an output-transform-aware step after the image-pipeline (so the look is gamut-
  and tone-stable). **Sony** creative looks (s709, etc.) operate on S-Gamut3.Cine/S-Log3.
- The discipline is: **grade in a wide, scene-referred space (ACEScct / log)**, apply the look as a
  tetrahedrally-interpolated 3D LUT, then the ODT. Trilinear interpolation of a coarse LUT introduces
  visible **hue twist on saturated ramps**; the cinema standard is **tetrahedral** interpolation
  (ACES CLF, DaVinci) to keep hue-linearity through the cube.
- Skin must be **protected by perceptual chroma**, not an RGB box: pros key skin in a hue/chroma
  space so the look doesn't push faces toward orange/magenta.
Elite bar, measurable: (a) LUT round-trip of a 33³ identity is bit-exact passthrough; (b) a neutral
ColorChecker gray ramp through the LUT keeps **neutral-Duv ≈ 0** and **ΔE2000 < 1.0** vs the
ungraded gray; (c) **hue-linearity** — a saturated hue sweep shows no >2° hue twist between lattice
nodes (the tetrahedral-vs-trilinear test); (d) skin patches stay within a bounded ΔE2000 of the
profile's intended skin target.

## 3. Current state in NeoProCamX (verified)
- LUT bake loop calls `applyActiveColorPipeline` per cell at `ColorScience.kt:761` and `:952`; the
  pipeline body is `ColorScience.kt:779`. Cached by config version/size, uploaded to GPU (catalog §2,
  row 1). **Live-optional, default behaves correctly.**
- Skin protection is a scalar strength function `skinToneProtectionStrength(r,g,b)`
  (`ColorScience.kt:1233`) — RGB-domain, not perceptual-chroma keyed.
- Neutral/WYSIWYG nulls the LUT (catalog) — the no-downgrade passthrough already exists.
- **GPU-side interpolation mode is the open question:** the bake is CPU, but how the GPU samples the
  uploaded LUT (trilinear vs tetrahedral) is set in the shader/sampler, not here. The hue-twist risk
  lives there, not in the bake. This prompt hardens the *bake correctness + test lock*; the
  GPU-sampler tetrahedral upgrade is shared with s2-f08 (NRLI tetrahedral).
- No golden currently freezes the full per-cell bake output across profiles (the contract/golden net
  freezes per-mode RGB elsewhere, but not this LUT's lattice).

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Identity lock:** a 33³ identity config bakes to a bit-exact identity LUT (every node maps to
   itself within 1 code at 16-bit). Provable in a pure JVM test over the bake function.
2. **Neutral preservation:** baking the active grade and pushing a 17-step neutral gray ramp through
   it yields neutral-Duv ≤ 0.001 and ΔE2000 ≤ 1.0 vs the ungraded ramp in WYSIWYG; and ≤ profile-
   declared tolerance in graded modes (no unintended cast).
3. **Hue-linearity:** a 36-sample saturated hue sweep (constant L, C) sampled at LUT nodes vs midpoints
   shows max inter-node hue twist ≤ 2° (this is the test that *justifies* tetrahedral sampling; if
   trilinear fails it, file the tetrahedral upgrade against s2-f08).
4. **Skin bound:** the profile skin target patch round-trips within ΔE2000 ≤ profile tolerance; the
   skin-protection strength is monotonic in distance-from-skin-locus (no discontinuity).
5. **No-downgrade default:** WYSIWYG/neutral path stays exact passthrough (LUT nulled); every existing
   profile bakes byte-identical to today until a flagged change is enabled.
6. **Budget:** bake stays off the cold-start path (already cached by config version); no per-frame
   re-bake unless config changes — assert cache hit-rate in a test.

## 5. Implementation prompt (hand this to the agent)
**Scope:** lock and harden the existing CPU LUT bake with golden tests; expose interpolation-mode and
skin-keying as measured, gated improvements. **Non-goals:** do not change the default visible grade;
do not rewrite the GPU sampler here (that is s2-f08's tetrahedral upload); no new model.

**Plan:**
1. **GOLDEN-LOCK FIRST.** Add a characterization test that bakes the LUT for each shipped profile and
   freezes the lattice (hash or sampled nodes) — this is the Phase-0 lock proving every later change
   leaves the default untouched.
2. **Identity + neutral + hue-linearity + skin tests** per §4.1-4.4 against the pure bake function.
3. **Measure trilinear hue twist** at the GPU sampler; if it fails §4.3, record a device-gated note to
   move the sampler to tetrahedral (coordinate with s2-f08) behind a spec-constant defaulting to
   today's mode.
4. **Skin keying (optional, gated):** add a perceptual-chroma (Oklab a/b or CAM16 hue/chroma) skin key
   behind a flag defaulting to the current RGB strength — bit-identical until enabled.

**Files:** `app/src/main/java/com/neoproc/neoproccamx/color/ColorScience.kt`; tests under
`app/src/test/.../color/`. No ABI change (the LUT upload path/byte layout is unchanged).

**Gating:** any change to the bake math is behind a config flag whose default reproduces today's
lattice bit-for-bit; WYSIWYG passthrough is untouched.

## 6. Acceptance & verification
- **JVM golden/unit:** profile-lattice characterization green; identity/neutral/hue/skin tests green.
- **No ABI/manifest touch** unless the sampler mode moves (then extend the shader-manifest re-pin in
  the s2-f08 commit, not here).
- **Device (vivo):** A/B a saturated hue sweep + skin chart preview with the flag off (must match
  today) and on (must improve hue twist with no neutral shift). Owner confirms no visible regression.

## 7. Risks & no-downgrade guardrails
- **Hue twist / banding on ramps:** tetrahedral upgrade is gated and measured; trilinear default stays
  until the device A/B passes.
- **Neutral cast:** neutral-Duv + ΔE2000 gray-ramp tests block any unintended cast.
- **Skin shift:** perceptual-key change is flagged off; monotonicity test prevents discontinuity.
- **Re-bake lag:** cache-hit test guards against per-frame re-bake; bake stays off cold-start.

## 8. References
- Catalog §2 row 1: [`../FEATURE_CATALOG.md`](../FEATURE_CATALOG.md)
- Standard canon (ACES CLF / tetrahedral, RED IPP2, ARRI ALF, Sony s709): [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) §2
- Source: `app/src/main/java/com/neoproc/neoproccamx/color/ColorScience.kt:761, 779, 952, 1233, 1566`
- Related prompts: s2-f02, s2-f03, s2-f05, s2-f08
