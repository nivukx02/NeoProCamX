# Standalone Vulkan Demosaic Pipeline — Elite Upgrade Prompt

> Read [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) first — its global contract
> (no-downgrade, no-vendor, commit-per-feature, golden-first, ABI-locked, device-gated) applies
> here and is not restated.

| Field | Value |
|---|---|
| **Subsystem** | 1 — Capture, RAW & Multi-frame |
| **Prompt ID** | s1-f08 |
| **Maps to checkpoint(s)** | CP10 (b: pipeline created; c: dispatch), dep CP11 (matrices), CP14 (green ordering) |
| **Current working level** | 🟠 dormant (pipeline created **and destroyed** but never dispatched) |
| **Feature rating (potential)** | 1/10 now → 6/10 target |
| **Code-quality rating (now)** | 6/10 |
| **Risk · Size** | H · L |
| **Depends on** | s1-f07 (cinema-debayer — pick ONE live demosaic), s1-f09 (ACEScg), s1-f01 (RAW import) |

## 1. What this feature is
A standalone GPU compute path: 12-bit RAW Bayer → edge-directed demosaic → ACEScg, intended as the
*recording/master* demosaic (distinct from the preview-oriented cinema-debayer of s1-f07). The
SPIR-V is compiled and the Vulkan pipeline/layout are created at init — and then torn down — but no
command buffer ever dispatches it, so it does no work. It is the closest thing in the repo to a
"ready to fire but disconnected" stage.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
The master demosaic is where information is *captured before interpolation throws it away*:
- **Edge-directed demosaic** (AHD / Hamilton-Adams / LMMSE / RCD) reconstructs the green luminance
  carrier along edges; the chroma channels are interpolated using the high-resolution green as a
  guide. This is the difference between a sharp, artifact-free cinema frame and a soft, mazed phone JPEG.
- **Working in ACEScg/scene-linear:** RED IPP2 and the ACES pipeline demosaic then immediately move
  to a wide scene-linear working space (ACEScg / AP1) so every later operation is colorimetrically
  exact — no clipping in a display space.
- **Highlight reconstruction + defect rejection:** clipped channels reconstructed from neighbors;
  hot pixels (from the defect map, s1-f03) excluded so they never seed interpolation.

Engineering targets translated: (1) demosaic **MTF50 above bilinear** at Bayer Nyquist with no
zippering/maze on a slanted edge; (2) output in **ACEScg (AP1)** scene-linear, neutral preserved
(ΔE2000 < 1.0 on gray); (3) **12-bit (and ideally 14-bit, s1-f01) input honored** — no truncation
in the import; (4) hot pixels rejected (no surviving speckle in a dark-frame golden); (5)
deterministic, validation-clean dispatch within the per-frame budget.

## 3. Current state in NeoProCamX (verified)
- The SPV is compiled and `#include`d: `vulkan_bridge.cpp:37` `#include "generated/demosaic_vulkan_comp_spv.h"`
  (verified); the generated header and `.spv` exist under `app/src/main/cpp/generated/`.
- The pipeline objects are declared at `vulkan_bridge.cpp:558-560` (`demosaicDescLayout`,
  `demosaicPipelineLayout`, `demosaicPipeline`), **created** at `4045-4062`
  (`vkCreateDescriptorSetLayout(demosaic_vulkan)` … pipeline create), and **destroyed** at
  `3778-3788` — all verified by grep.
- **The break:** there is **no `vkCmdDispatch` referencing the demosaic pipeline** anywhere — the
  12 `vkCmdDispatch` sites in the bridge belong to other passes. So CP10b created the pipeline; CP10c
  (dispatch) is unstarted. The stage is created-and-destroyed but never executed.
- Catalog (FEATURE_CATALOG.md §1, 🟠 feat 1 / quality 6): "12-bit RAW edge-directed demosaic →
  ACEScg compute pipeline *created and destroyed but never dispatched*."
- **Overlap flag (decide first):** this `demosaic_vulkan.comp` and s1-f07's `cinema_debayer.comp`
  both target RAW→RGB. Only ONE should become the live demosaic; the other stays dormant or is the
  preview-vs-master sibling. Do not dual-wire both into the visible path.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Dispatched on real RAW:** the pipeline receives a `vkCmdDispatch` reading an imported 12/14-bit
   RAW R16_UINT image (s1-f01), with every binding deterministically initialized; validation-layer
   run shows zero UNDEFINED-layout / uninitialized-read warnings.
2. **ACEScg output:** the demosaic emits scene-linear ACEScg (AP1) via the s1-f09 forward matrices;
   a neutral patch stays achromatic (ΔE2000 < 1.0).
3. **Demosaic quality:** host-port golden — MTF50 > bilinear at Nyquist, no zippering/maze on a
   slanted edge, edge-directed green (CP14).
4. **Defect rejection:** hot pixels from the s1-f03 defect map are excluded; dark-frame golden shows
   no surviving speckle.
5. **Bit-depth honored:** 12-bit (and 14-bit when available) input is not truncated in the import.
6. **No-downgrade default:** dispatch activates only on the RAW master/record branch behind a flag
   defaulting **off** (existing path unchanged). If s1-f07 wins the live preview demosaic, this
   pipeline either serves the master path or stays dormant — never both into one visible surface.
7. **Budget:** per-frame dispatch within budget; pipeline compile deferred off cold-start (the
   existing deferred-compile pattern); governor backoff under thermal/load.

## 5. Implementation prompt (hand this to the agent)
**Scope:** dispatch the already-created demosaic pipeline on real RAW for the master/record path,
behind a no-downgrade gate (CP10c).
**Non-goals:** do not change the demosaic math beyond edge-directed green; do not also wire
cinema_debayer into the same visible surface (resolve s1-f07 vs s1-f08 ownership first); no new lib.

**Plan:**
1. **Decide ownership** (with s1-f07): which shader is the *live* demosaic and for which surface.
   Document it; the loser stays dormant.
2. **Golden-lock** the current master/record output for RAW mode so the fallback is provably
   unchanged.
3. **Host-port + golden** the demosaic (MTF50 > bilinear, no zipper, neutral, edge-directed green).
4. **Add the dispatch (CP10c).** Build the command-buffer dispatch for `demosaicPipeline`, import
   R16_UINT RAW, bind the std140 UBO (CP11 forward matrices from s1-f09, WB gains, black/white
   levels, defect map from s1-f03), and ensure every binding is initialized.
5. **Ordering (CP14):** WB-before-demosaic + hard-gradient green.
6. **Fallback + gate:** existing path when the flag is off or device validation fails.
7. **Device note.** DEVICE-GATED: validation-clean dispatch + visual/colorimetric correctness on
   the vivo.

**Files:** `app/src/main/cpp/vulkan_bridge.cpp` (dispatch + RAW import + UBO; reuse the
`demosaic*` objects already at 558-560 / 4045-4062), `app/src/main/cpp/shaders/vulkan/demosaic_vulkan.comp`.

**ABI:** the demosaic UBO is std140 — extend `GpuPushConstantLayout` + add a layout test in the same
commit; re-pin the shader-manifest hash for `demosaic_vulkan`.

**Gating:** dispatch behind a flag/specialization-constant defaulting **off** = today's output
unchanged until device-validated.

## 6. Acceptance & verification
- **Unit/golden (JVM):** master/record golden unchanged; host demosaic golden (MTF50 > bilinear, no
  zipper, neutral, edge-directed green); UBO layout test; defect-rejection golden.
- **Native:** `:app:externalNativeBuildDebug` green; validation-layer run on the demosaic dispatch
  reports **zero** UNDEFINED-layout / uninitialized-read warnings.
- **Device (vivo I2405):** RAW master demosaic correct, neutral achromatic, no colored speculars,
  no hot speckle; thermal fallback verified.
- **Gate keys:** RAW/Vulkan demosaic capability proves through `OmegaCapabilityGate` from real
  transport — never synthesized.

## 7. Risks & no-downgrade guardrails
- **UB / black master on RAW branch:** deterministic binding init + validation gate + off-by-default
  → impossible to ship a black master.
- **Demosaic artifacts:** edge-directed green + host golden bound.
- **Color shift / clipping:** ACEScg scene-linear working space + WB-before-demosaic + ΔE2000 check.
- **Dual-wire confusion:** explicit s1-f07/s1-f08 ownership decision; only one demosaic per surface.
- **Lag / thermal:** deferred compile + governor backoff.

## 8. References
- Roadmap: CP10 (b created `5f65c76`; c dispatch ⬜), CP11, CP14 in [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Tech: [`../technology_explanations/markdown/10-vulkan-cinema-debayer.md`](../technology_explanations/markdown/10-vulkan-cinema-debayer.md)
- Reference: AHD / Hamilton-Adams / LMMSE / RCD demosaic; ACES AP1 scene-linear working space; RED IPP2.
- Source: `app/src/main/cpp/vulkan_bridge.cpp:37, 558-560, 3778-3788, 4045-4062` (created+destroyed, no dispatch — verified), `app/src/main/cpp/shaders/vulkan/demosaic_vulkan.comp`
- Related prompts: s1-f07, s1-f09, s1-f03, s1-f01
