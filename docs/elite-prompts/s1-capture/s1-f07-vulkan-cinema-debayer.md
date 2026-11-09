# Vulkan Cinema Debayer Preview — Elite Upgrade Prompt

> Read [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) first — its global contract
> (no-downgrade, no-vendor, commit-per-feature, golden-first, ABI-locked, device-gated) applies
> here and is not restated.

| Field | Value |
|---|---|
| **Subsystem** | 1 — Capture, RAW & Multi-frame |
| **Prompt ID** | s1-f07 |
| **Maps to checkpoint(s)** | CP10 (activate cinema-debayer/demosaic compute for RAW preview), dep CP11 (matrices), CP14 (green ordering) |
| **Current working level** | 🟠 dormant (shader exists; not in the native build; never dispatched) |
| **Feature rating (potential)** | 2/10 now → 7/10 target |
| **Code-quality rating (now)** | 7/10 (shader math sound; not compiled into the bridge) |
| **Risk · Size** | H · XL |
| **Depends on** | s1-f09 (sensor→ACEScg, CP11), s1-f08 (demosaic pipeline), s1-f03 (defect map), s1-f01 (RAW import) |

## 1. What this feature is
The GPU stage meant to turn a RAW Bayer mosaic into a viewable RGB preview *natively* — adaptive
directional interpolation + highlight reconstruction + per-channel white balance (an AHD-lite
demosaic), gated to VIDEO+RAW modes. The point is to show the operator the actual sensor-native
image (not the vendor-ISP YUV) so the preview is WYSIWYG with the RAW master. Today the RAW preview
path falls back to the standard YUV chain because this shader never runs.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
Cinema monitoring debayers in real time with a *cheap-but-correct* demosaic and shows it through
the same color transform as the master:
- **Demosaic quality bar:** the reference algorithms are **Adaptive Homogeneity-Directed (AHD)**,
  **Hamilton-Adams** hard-gradient green interpolation, **LMMSE**, and **RCD** — all of which
  interpolate the green channel *along* edges (never across), reconstructing luminance detail
  without the zippering/maze artifacts of bilinear demosaic.
- **Highlight reconstruction:** when one channel clips, pro pipelines reconstruct it from the
  unclipped channels so speculars stay neutral instead of turning magenta/cyan.
- **WYSIWYG monitoring:** ARRI/RED feed the monitor path through the same IDT→look as the recording,
  so the operator's exposure/color decisions are honest.

Engineering targets translated: (1) demosaic that beats bilinear on a resolution chart —
**MTF50 at the Bayer Nyquist measurably higher**, with no zippering on a slanted edge; (2)
**hard-gradient green** (AHD/Hamilton-Adams) so the luma carrier is edge-directed (CP14); (3)
**WB-before-demosaic ordering** so interpolation happens in a balanced signal (CP14); (4) clipped
highlights reconstruct neutral (no colored speculars, ΔE of a blown highlight bounded); (5) the
preview color transform matches the master's (s1-f09 ACEScg), real-time-budgeted.

## 3. Current state in NeoProCamX (verified)
- The shader source exists: `app/src/main/cpp/shaders/vulkan/cinema_debayer.comp`. **There is NO
  generated SPIR-V header for it and it is NOT `#include`d in `vulkan_bridge.cpp`** (grep for
  `cinema_debayer`/`debayer` in `vulkan_bridge.cpp` returns *no matches*). So the cinema-debayer
  shader is not even in the native build — it cannot be dispatched.
- A Kotlin orchestrator exists — `rendering/VulkanCinemaDebayerPipeline.kt`, referenced by
  `rendering/VulkanRuntimeManager.kt` (verified by grep) — i.e. activation is *plumbed in Kotlin*
  but the native dispatch is absent.
- Catalog framing (FEATURE_CATALOG.md §1, 🟠 feat 2 / quality 7): "Submits a generic AHB import; the
  debayer shader itself is never dispatched (RAW import 'deferred to CP10c')." **NOTE/flag:** the
  catalog's `cinema_debayer.comp` filename is accurate, but the *compiled* demosaic in the native
  build is `demosaic_vulkan.comp` (s1-f08); CP10b created the `demosaic_vulkan` pipeline, not a
  `cinema_debayer` one. The two shaders overlap in intent — clarify which becomes the live
  RAW-preview demosaic before wiring (see s1-f08).
- Roadmap CP10: **CP10a** ✅ compile shader→SPV (`5f65c76`); **CP10b** ✅ create
  pipeline+layout+teardown (native build green, **not dispatched**); **CP10c** ⬜ RAW R16_UINT import
  branch + std140 UBO (CP11 forward matrices) + dispatch + YUV fallback (device-validated).

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Shader actually in the build + dispatched:** the chosen demosaic shader (cinema_debayer or
   demosaic_vulkan — decided with s1-f08) has a generated SPV header `#include`d in
   `vulkan_bridge.cpp` and a `vkCmdDispatch` on the RAW-preview branch; a validation-layer run shows
   the dispatch with all bindings initialized (no UNDEFINED-layout reads).
2. **Real RAW import:** the RAW R16_UINT (format-0x20) branch imports the sensor frame (s1-f01),
   not a generic AHB placeholder.
3. **Demosaic quality:** on a synthetic Bayer resolution chart, demosaiced MTF50 > bilinear MTF50
   and zippering/maze artifacts absent on a slanted edge (host-port golden of the interpolation).
4. **Green ordering (CP14):** hard-gradient (AHD/Hamilton-Adams) green + WB-before-demosaic;
   asserted by a host golden that the green carrier is edge-directed.
5. **Color exactness:** demosaic output feeds the s1-f09 sensor→ACEScg transform; a neutral patch
   stays achromatic (ΔE2000 < 1.0) and matches the master path.
6. **No-downgrade default:** the RAW-preview demosaic activates **only** when RAW+VIDEO is selected
   and the device passes the dispatch validation; otherwise the existing YUV preview path runs
   bit-identically. Gated behind a specialization constant / flag defaulting to YUV-fallback.
7. **Budget:** fits the preview frame budget; demosaic compile is deferred off cold-start (existing
   deferred-compile pattern); under thermal/load the governor falls back to YUV.

## 5. Implementation prompt (hand this to the agent)
**Scope:** get the cinema-debayer demosaic actually compiled, dispatched on real RAW, behind a
no-downgrade YUV fallback (CP10c).
**Non-goals:** do not change the standard YUV preview chain; do not invent demosaic math beyond the
AHD-lite already written; no new library. Decide cinema_debayer-vs-demosaic_vulkan with s1-f08
*before* coding (don't compile both into the live path).

**Plan:**
1. **Golden-lock the YUV preview** for the RAW+VIDEO mode so the fallback is provably unchanged.
2. **Host-port the demosaic** to JVM and golden it (MTF50 > bilinear, no zippering, neutral
   preserved) — this is the algorithm lock before touching the GPU.
3. **Compile + include.** Generate the SPV header for the chosen shader and `#include` it in
   `vulkan_bridge.cpp` (cinema_debayer is currently absent from the build — fix that, or reuse
   demosaic_vulkan's already-created pipeline from CP10b).
4. **RAW import branch (CP10c).** Import R16_UINT (format-0x20), build the std140 UBO carrying the
   CP11 forward matrices (s1-f09), dispatch, and bind every input deterministically (no UNDEFINED).
5. **Ordering (CP14).** WB-before-demosaic + hard-gradient green.
6. **Fallback + gate.** YUV passthrough when RAW-preview is off or the device fails validation.
7. **Device note.** DEVICE-GATED: validation-layer clean dispatch + visual correctness on the vivo.

**Files:** `app/src/main/cpp/vulkan_bridge.cpp` (include + RAW branch + dispatch + UBO),
`app/src/main/cpp/shaders/vulkan/cinema_debayer.comp` (+ generated SPV),
`rendering/VulkanCinemaDebayerPipeline.kt`, `rendering/VulkanRuntimeManager.kt`.

**ABI:** the demosaic UBO (forward matrices, WB gains, black/white levels) is std140 — extend
`GpuPushConstantLayout` + add a layout test in the same commit; re-pin the shader-manifest hash.

**Gating:** specialization constant / flag default = YUV fallback (today's pixels unchanged) until
device-validated.

## 6. Acceptance & verification
- **Unit/golden (JVM):** YUV-preview golden unchanged; host demosaic golden (MTF50 > bilinear, no
  zippering, neutral preserved, edge-directed green); UBO layout test.
- **Native:** `:app:externalNativeBuildDebug` green with the SPV `#include`; validation-layer run
  on the RAW-preview dispatch reports **zero** UNDEFINED-layout / uninitialized-read warnings.
- **Device (vivo I2405):** RAW+VIDEO preview shows a correctly-demosaiced sensor-native image,
  neutral patch achromatic, no colored speculars; thermal fallback to YUV verified.
- **Gate keys:** RAW-preview/Vulkan capability proves through `OmegaCapabilityGate` from real
  transport — never synthesized.

## 7. Risks & no-downgrade guardrails
- **Black-out / UB on RAW branch:** deterministic binding init + validation-layer gate + YUV
  fallback default → impossible to ship a black RAW preview.
- **Demosaic artifacts (zipper/maze):** edge-directed green + host golden bound.
- **Color shift:** WB-before-demosaic + ACEScg transform + ΔE2000 neutral check.
- **Lag / thermal:** deferred compile + governor YUV fallback; off the cold-start path.
- **Shader confusion:** pick one demosaic shader for the live path (with s1-f08); don't dual-wire.

## 8. References
- Roadmap: CP10 (a/b/c), CP11, CP14 in [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Tech: [`../technology_explanations/markdown/10-vulkan-cinema-debayer.md`](../technology_explanations/markdown/10-vulkan-cinema-debayer.md), [`../technology_explanations/markdown/09-neoradiance-raw-and-npraw-modes.md`](../technology_explanations/markdown/09-neoradiance-raw-and-npraw-modes.md)
- Reference: AHD (Hirakawa-Parks), Hamilton-Adams hard-gradient green, LMMSE, RCD demosaic.
- Source: `app/src/main/cpp/shaders/vulkan/cinema_debayer.comp`, `app/src/main/cpp/vulkan_bridge.cpp` (no `cinema_debayer` include — verify), `app/src/main/java/com/neoproc/neoproccamx/rendering/VulkanCinemaDebayerPipeline.kt`, `VulkanRuntimeManager.kt`
- Related prompts: s1-f08, s1-f09, s1-f03, s1-f01, s1-f06
