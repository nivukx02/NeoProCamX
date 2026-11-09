# SNCE Stage-04 OCCS Opponent Lateral-Inhibition GPU Pass — Elite Upgrade Prompt

> Global contract in [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) applies and is not restated.

| Field | Value |
|---|---|
| **Subsystem** | 2 — Color science + SNCE engine |
| **Prompt ID** | s2-f07 |
| **Maps to checkpoint(s)** | CP41 (scene-driven gains), CP46 (mesopic-aware), CP49 (scale-adaptive separable DoG) |
| **Current working level** | 🔵 live-optional (dispatched: forward DoG iGain=chromaGain=0.10, zero-gain copy-back) |
| **Feature rating (potential)** | 5/10 now → 7/10 target |
| **Code-quality rating (now)** | 7/10 |
| **Risk · Size** | M · M |
| **Depends on** | s2-f06 (23-float scene block that drives the gains), s2-f03 (uniform discipline), s4-f0x (OmniClarity deblur it shares intermediate buffers with) |

## 1. What this feature is
`occs_opponent.comp` is the SNCE Stage-04 GPU pass: opponent-color (red-green / blue-yellow / luma)
lateral inhibition via a difference-of-Gaussians, modeling retinal/cortical center-surround
processing. It is **dispatched live** every frame as a forward DoG with `iGain = chromaGain = 0.10f`
(`vulkan_bridge.cpp:215-216`) into the OmniClarity-deblur intermediate, followed by a **zero-gain
copy-back** (`occsCopyBackDescSets`, `vulkan_bridge.cpp:429`) — so today the net visible effect is the
bounded 0.10 forward pass. The point of OCCS is perceptual chroma sharpening / chroma-crawl
suppression in an opponent space, the way human vision separates color from luminance.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
The grounding is **opponent-process theory (Hering)** and **CIE opponent / CAM16 a-b** chroma
processing:
- Human vision encodes color as opponent channels (L-M ≈ red-green, S-(L+M) ≈ blue-yellow) with
  **center-surround lateral inhibition** — this is what makes edges and chroma transitions crisp and
  suppresses chroma noise/crawl. High-end color pipelines do chroma operations in an opponent /
  perceptually-uniform space (CAM16, IPT, Oklab) precisely so that sharpening and denoise don't shift
  hue.
- **Mesopic vision (CP46):** at low light the rod/cone balance shifts (Purkinje effect) — chroma
  perception drops and the opponent gains should adapt. Cinema cameras don't model this, but the
  perceptually-correct target is to **reduce chroma lateral-inhibition gain in dim scenes** so noise
  isn't amplified.
- **Scale-adaptivity (CP49):** the DoG surround radius should scale with image/feature scale; a
  separable kernel is the efficient, artifact-free form.
Elite bar, measurable: (a) the opponent transform is hue-preserving (round-trips to identity at
zero gain within 1e-5); (b) at the default 0.10 gain, neutral patches are untouched (neutral-Duv ≈ 0)
and chroma edges sharpen without hue shift (Oklab ΔHue ≤ 1°); (c) opponent-space **chroma-crawl**
(temporal) is reduced vs OCCS-off on a textured chroma sequence; (d) scene-driven gains (CP41) and
mesopic reduction (CP46) stay within a bounded range that never amplifies chroma noise.

## 3. Current state in NeoProCamX (verified)
- Shader `occs_opponent.comp` compiled (`generated/occs_opponent_comp_spv.h` `#include`d at
  `vulkan_bridge.cpp:49`); pipeline objects `occsDescLayout/occsPipelineLayout/occsPipeline/occsShader`
  (`vulkan_bridge.cpp:575-578`); forward + copy-back descriptor sets (`:428-429`).
- **Live, default-on, but constant-driven:** `OccsParams` push constants default
  `iGain = 0.10f`, `chromaGain = 0.10f` (`vulkan_bridge.cpp:215-216`); the comment notes
  `iGain==chromaGain` keeps the pass bounded and that the **copy-back is zero-gain passthrough**
  (`vulkan_bridge.cpp:208, 428-429`). So the params are **compile-time constants, not driven by the
  scene-brain** (catalog §2 row 7).
- The 23-float scene block (s2-f06) that *should* drive these gains is computed but never uploaded — so
  OCCS cannot yet be scene-adaptive. CP41 wires that; CP46 adds mesopic adaptation; CP49 makes the DoG
  separable/scale-adaptive.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Hue-preserving opponent transform:** zero-gain round-trip is identity within 1e-5 (host port of
   the opponent matrices); neutral patches untouched at any gain (neutral-Duv ≤ 0.001).
2. **Chroma-crawl reduction:** on a textured chroma test sequence, OCCS-on reduces opponent-space
   chroma-crawl (the ImageQualityMetrics `temporalChromaCrawl` metric) vs OCCS-off, with no luma SNR
   regression.
3. **Scene-driven gains (CP41):** when fed the 23-float block (s2-f06), `iGain`/`chromaGain` are derived
   from scene state within a bounded `[0, gMax]`; default (no block / gate off) = today's 0.10 exactly.
4. **Mesopic adaptation (CP46):** chroma lateral-inhibition gain decreases monotonically with measured
   scene luminance (less chroma processing in dim scenes → no chroma-noise amplification). Monotonic +
   bounded test.
5. **Scale-adaptive separable DoG (CP49):** the surround kernel is separable (two 1D passes) and its
   radius scales with a scene/scale parameter; output matches the reference non-separable DoG within a
   small ε (correctness) at lower cost.
6. **No-downgrade default:** gate off → forward 0.10 + zero-gain copy-back, bit-for-bit today.
7. **Budget (ms):** separable form must not exceed today's two-pass cost; respects the load governor.

## 5. Implementation prompt (hand this to the agent)
**Scope:** make OCCS scene-driven (CP41) + mesopic-aware (CP46) + scale-adaptive separable (CP49),
each behind a gate defaulting to today's constant 0.10 behavior. **Non-goals:** do not change the
opponent matrices' definition; do not remove the zero-gain copy-back safety; no new model; do not widen
the bounded gain range into look-swing territory.

**Plan:**
1. **GOLDEN-LOCK FIRST.** Characterization test freezing today's OCCS dispatch (gains 0.10, zero-gain
   copy-back) — the no-downgrade proof.
2. **Host port + chroma-crawl test** per §4.1-4.2 (JVM port of the opponent transform + DoG to assert
   hue-preservation and crawl reduction).
3. **CP41 scene-driving:** consume s2-f06's uploaded block; map scene state → bounded gains; spec
   constant defaults to 0.10.
4. **CP46 mesopic:** add a luminance→chroma-gain monotone curve; default neutral.
5. **CP49 separable scale-adaptive DoG:** refactor the kernel into two 1D passes with a scale param;
   prove equivalence to the reference within ε.
6. **Re-pin the shader manifest** for any GLSL change; extend the OCCS push-constant/UBO layout test.

**Files:** `app/src/main/cpp/shaders/vulkan/occs_opponent.comp`, `app/src/main/cpp/vulkan_bridge.cpp`
(gains wiring, separable dispatch), the OCCS `OccsParams` layout test; host port + tests under
`app/src/test/.../snce/`.

**ABI:** `OccsParams` is a contract surface (`vulkan_bridge.cpp:207-216`); any field add → extend its
layout test + re-pin manifest in the same commit.

**Gating:** spec constants default to today's constant gains + non-separable path until the owner
enables scene-driving / mesopic / separable on-device.

## 6. Acceptance & verification
- **JVM:** opponent round-trip, chroma-crawl reduction, scene-gain bounded + default-unchanged,
  mesopic monotonicity, separable-equivalence tests green.
- **Native:** `:app:externalNativeBuildDebug` green; validation-layer clean; manifest re-pinned.
- **Device (vivo):** gate off → bit-match today; gate on → dim scene shows less chroma noise (mesopic),
  scene change steers chroma sharpening, separable path holds frame budget. Owner confirms no hue shift
  on skin/neutrals.

## 7. Risks & no-downgrade guardrails
- **Chroma noise amplification:** bounded gains + mesopic reduction + chroma-crawl metric gate.
- **Hue shift:** opponent transform hue-preservation test + neutral-Duv check.
- **Separable kernel artifacts:** equivalence-to-reference test within ε before activation.
- **Look swing / pixel regression:** gated off by default; zero-gain copy-back retained; golden-lock.
- **Lag/thermal:** separable cost bounded; load-governor backoff.

## 8. References
- Roadmap CP41/CP46/CP49 (dep CP41): [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Standard canon (Hering opponent process, CAM16 a-b, mesopic/Purkinje, separable DoG): [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) §2
- Source: `app/src/main/cpp/vulkan_bridge.cpp:49, 207-216, 428-429, 575-578`; `app/src/main/cpp/shaders/vulkan/occs_opponent.comp`
- Related prompts: s2-f03, s2-f06, s2-f08
