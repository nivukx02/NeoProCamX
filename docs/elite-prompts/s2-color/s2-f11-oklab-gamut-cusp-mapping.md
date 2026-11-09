# OklabGamutCusp + OklabGamutMapping (exact cusp / hue-preserving clip) — Elite Upgrade Prompt

> Global contract in [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) applies and is not restated.

| Field | Value |
|---|---|
| **Subsystem** | 2 — Color science + SNCE engine |
| **Prompt ID** | s2-f11 |
| **Maps to checkpoint(s)** | CP40 (real per-hue/lightness gamut-cusp LUT in live shader — **foundation DONE**, GPU upload device-gated) |
| **Current working level** | 🟠 dormant (Ottosson exact cusp + Halley + per-hue LUT; pure, JVM-tested; wiring device-gated) |
| **Feature rating (potential)** | 3/10 now → 9/10 target |
| **Code-quality rating (now)** | 9/10 |
| **Risk · Size** | M · M |
| **Depends on** | s2-f02 (AcesColorEngine gamut compression that should call this), s2-f03 (GPU uniform/LUT upload), s2-f08 (NRLI shares the LUT-upload pattern) |

## 1. What this feature is
`OklabGamutCusp.kt` computes the **exact** gamut cusp (the maximum-chroma point) per hue in Oklab for
linear sRGB: an analytic max-saturation polynomial seed + one **Halley** iteration + cusp construction
(`findCusp` `:92`, `findCuspForHue` `:102`), and a per-hue cusp **LUT** (`cuspLut(bins)` `:110`).
`OklabGamutMapping.kt` does the **hue-preserving** gamut mapping: `findGamutIntersection` (`:27`, exact
analytic seed + Halley) and `gamutClipPreserveChroma` — clipping out-of-gamut colors onto the boundary
while preserving Oklab hue. This is the *correct* replacement for the live shader's analytic boundary
**guess** `0.365 + 0.080·cos(3h) + 0.035·cos(6h+0.4)` (`neo_p010_export_enhance.comp:1168`). It is
pure, deterministic, JVM-tested, and **dormant** — the GPU upload is device-gated.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
Gamut mapping is a defining quality differentiator in mastering:
- **ACES Reference Gamut Compression (RGC, ACES 1.3)** and the **ACES Output Transforms** map
  out-of-gamut scene colors back to the display gamut **along constant-hue lines**, toward a per-hue
  boundary — never by naive per-channel clipping (which shifts hue and posterizes). DaVinci/Baselight
  gamut-mapping and BT.2407 (Rec.2020→709) do the same: **hue-preserving, cusp-aware** compression.
- **Oklab (Björn Ottosson, 2020/2021)** is the modern perceptual space for exactly this: its
  "oklab gamut clipping" reference computes the per-hue cusp (max-chroma point where the first RGB
  channel leaves [0,1]) and projects toward it preserving hue — the algorithm implemented here.
- The failure mode being eliminated: the live shader's `cos(3h)+cos(6h)` **analytic guess** of the
  boundary is only approximate, so saturated colors either clip early (lost saturation) or overshoot
  (hue twist / posterization). The exact cusp fixes both.
Elite bar, measurable (already met on CPU per CP40): at the cusp the linear-sRGB max channel == 1.0
over 256 hues within 3e-3; the clip preserves Oklab hue and lands a point exactly on the boundary; the
GPU result matches the CPU reference once the LUT is uploaded.

## 3. Current state in NeoProCamX (verified)
- `OklabGamutCusp.kt`: header confirms "Ottosson exact cusp … analytic seed + one Halley iteration …
  Pure, deterministic, JVM-unit-tested; dormant" (`:11, :53`). `findCusp` `:92`, `findCuspForHue`
  `:102`, `cuspLut(bins)` `:110`.
- `OklabGamutMapping.kt`: header "analytic seed + one Halley step … Pure, JVM-tested, dormant" (`:9`);
  `findGamutIntersection` (`:27`, default cusp via `OklabGamutCusp.findCusp(a,b)` `:33`),
  `gamutClipPreserveChroma`.
- **CP40 foundation is DONE** (roadmap: 9 self-validating JVM tests; the audit-claimed "exact CPU cusp"
  did NOT previously exist — this lands it). The live shader still uses the analytic guess at
  `neo_p010_export_enhance.comp:1168` — verified: `clamp(0.365 + 0.080 * cos(3.0 * angle) + 0.035 *
  cos(6.0 * angle + 0.4), 0.18, 0.48)`.
- **DEVICE-GATED remaining work:** upload `cuspLut()` into the gamut-compression shader (+ a
  wide-gamut-primaries variant) and validate saturated-color rendering on the vivo. That is this
  prompt's elevation.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Exact-cusp lock (CPU, already passing):** at the cusp the linear-sRGB max channel == 1.0 over 256
   hues within 3e-3; keep this test green (the foundation lock).
2. **Hue-preserving clip:** `gamutClipPreserveChroma` of any out-of-gamut color preserves Oklab hue
   (ΔHue ≤ a tiny ε) and lands on the boundary (max channel == 1.0 within 3e-3). (Already JVM-tested;
   keep + extend.)
3. **GPU == CPU:** the uploaded `cuspLut()`-driven shader boundary matches the CPU reference within a
   small tolerance over a hue/lightness sweep (host-vs-device or host-vs-emulated-shader test).
4. **Replaces the analytic guess:** with the cusp LUT enabled, the shader's boundary equals the exact
   cusp (not `0.365+0.080cos(3h)+…`); a saturated hue sweep shows reduced clip/overshoot vs the analytic
   path.
5. **Wide-gamut variant:** a primaries-parameterized cusp LUT supports the wide-gamut path (NeoWideGamut
   / Rec.2020-class) — the cusp recomputed for those primaries.
6. **No-downgrade default:** the analytic-guess path stays the default (spec constant) until the device
   A/B passes; uploading the LUT but not selecting it changes nothing.
7. **Budget (ms):** a cusp LUT fetch is O(1) per pixel (texture/UBO lookup) — cheaper than recomputing
   Halley per pixel; off cold-start.

## 5. Implementation prompt (hand this to the agent)
**Scope:** upload the CPU-exact `cuspLut()` to the gamut-compression shader (+ wide-gamut variant) and
select it behind a spec constant defaulting to today's analytic guess. **Non-goals:** do not change the
Oklab cusp math (it's correct and locked); do not change the default boundary until device-validated;
no new model.

**Plan (CP40 GPU half):**
1. **GOLDEN-LOCK FIRST.** Characterization freezing the live shader's current analytic-boundary output
   (the `:1168` path) — proves selecting the LUT is the only intentional change.
2. **Bake + upload `cuspLut(bins)`** as a UBO/texture (reuse the s2-f03 / s2-f08 upload pattern); add a
   spec constant `useExactCusp` defaulting to **false** (analytic guess = today).
3. **Shader path:** when `useExactCusp`, replace the `0.365+0.080cos(3h)+0.035cos(6h+0.4)` boundary at
   `neo_p010_export_enhance.comp:1168` with a `cuspLut` lookup; add the wide-gamut-primaries variant.
4. **Host-vs-shader equivalence test** (§4.3) + saturated-sweep improvement test (§4.4).
5. **Re-pin the shader manifest**; extend any layout test if a UBO field is added.

**Files:** `app/src/main/cpp/shaders/vulkan/neo_p010_export_enhance.comp` (boundary + LUT sample),
`app/src/main/cpp/vulkan_bridge.cpp` (LUT bake/upload + spec constant),
`app/src/main/java/com/neoproc/neoproccamx/color/OklabGamutCusp.kt` /
`OklabGamutMapping.kt` (the cusp source — unchanged math); tests under `app/src/test/.../color/`.

**ABI:** new cusp-LUT UBO/texture → std140 layout test + manifest re-pin in the same commit.

**Gating:** `useExactCusp` spec constant defaults false (analytic guess); the LUT is uploaded but not
selected until the owner enables it on-device.

## 6. Acceptance & verification
- **JVM:** the 9 existing CP40 cusp/clip tests stay green; host-vs-shader equivalence + saturated-sweep
  improvement + analytic-baseline characterization tests added and green.
- **Native:** `:app:externalNativeBuildDebug` green; validation-layer clean; manifest re-pinned.
- **Device (vivo):** `useExactCusp` off → bit-match today; on → saturated colors (deep red/blue/green
  ramps) render at the true boundary with no hue twist or premature clip; wide-gamut variant validated.
  Owner confirms.

## 7. Risks & no-downgrade guardrails
- **Premature clip / hue twist (analytic guess):** exact cusp lands colors on the true boundary; tested.
- **GPU≠CPU drift:** host-vs-shader equivalence test before activation.
- **Banding/posterization on ramps:** cusp LUT is smooth/monotonic per hue; banding metric check.
- **Pixel regression:** `useExactCusp` defaults false; analytic-baseline characterization lock.
- **ABI/std140:** layout test + manifest re-pin in the same commit.

## 8. References
- Roadmap CP40 (foundation DONE, GPU upload device-gated): [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Standard canon (ACES RGC, BT.2407, Oklab gamut clipping — Ottosson): [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) §2
- Source: `app/src/main/java/com/neoproc/neoproccamx/color/OklabGamutCusp.kt:11, 53, 92, 102, 110`; `OklabGamutMapping.kt:9, 27, 33`; `app/src/main/cpp/shaders/vulkan/neo_p010_export_enhance.comp:1168`
- Related prompts: s2-f02, s2-f03, s2-f08
