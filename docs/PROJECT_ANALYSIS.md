# NeoProCamX — Project Analysis & Rating

> Generated 2026-06-26. Method: an 8-analyst parallel code audit across every subsystem (feature
> cataloging + working-level + code-quality rating, each claim verified against `file:line`), a
> synthesis pass for the overall rating, and a direct line-of-code measurement. Ratings are **honest**:
> a beautifully-written feature that is not wired into the running app scores high on *code quality* but
> low on *working level*.

---

## 1. Executive Verdict

**NeoProCamX is an ambitious, genuinely impressive, and largely well-engineered Android pro-cinema
camera app.** Its **live core actually works and would ship**: a real Vulkan compositor, a default-on
classical OmniClarity motion stack (optical flow + deblur + temporal warp) across preview/record/export,
a solid HEVC MOV encoder with honest anti-fraud quality gates, live RAW/DNG capture, default-on
MediaPipe AI face/subject autofocus, and a tested color-science LUT/uniform path.

**The critical caveat — which the ratings deliberately reflect — is that roughly 55–60% of the headline
"beyond-IMAX" feature surface is built-but-dormant**: present, often beautifully written and unit-tested,
but not yet feeding real pixels in the shipping app. This is *unfinished wiring, not sloppiness* — the
codebase is refreshingly honest about its own dormancy in-code.

---

## 2. Overall Ratings

| Dimension | Rating | Notes |
|---|---:|---|
| **Overall project** | **7.0 / 10** | Real and well-made, but far from delivering the elite feature set its surface advertises |
| **Code quality** | **8.0 / 10** | Well-engineered, well-documented, strong separation of concerns |
| **Architecture** | **8.0 / 10** | Clean policy decomposition, honest fallbacks, deferred-compile concurrency done right (2 god objects drag it) |
| **Feature completeness** | **6.0 / 10** | Live surface is solid; majority of "elite" surface is dormant/dummy-fed/test-only |
| **Test coverage** | **8.0 / 10** | 562 test files, 3,212 test methods, golden/characterization locks, all green |
| **Working level** | Mixed | *Live, high-quality camera/encode/render core under a thick band of dormant "beyond-IMAX" scaffolding* |

### Live vs. Dormant breakdown
- **~40–45% live** (default-on or live-optional) — the working product.
- **~55–60% built-but-dormant**, dummy-fed, or test-only scaffold — the inert elite layer.

---

## 3. Lines of Code

SLOC = source lines of code (blank + comment lines excluded). Machine-generated SPIR-V headers excluded
from the totals (shown separately).

| Category | Files | SLOC |
|---|---:|---:|
| Kotlin (main / production) | 625 | 136,351 |
| C++ native (Vulkan bridge etc., excl. generated) | 14 | 10,712 |
| GLSL compute/graphics shaders | 45 | 8,836 |
| `core-math` module (Kotlin) | 8 | 3,751 |
| XML resources (layout/values/drawable) | 73 | 2,111 |
| **Main subtotal (code + shaders + XML)** | **765** | **161,761** |
| &nbsp;&nbsp;↳ executable code only (no XML) | — | 159,650 |
| Test (Kotlin, unit/contract/golden/characterization) | 562 | 65,407 |
| **Grand total (main + test)** | **1,327** | **227,168** |
| *(Generated SPIR-V headers — excluded above)* | *29* | *98,726* |

**Other metrics:** 160 git commits · 37 GLSL shaders · **3,212** `@Test` methods · 18 top-level feature
packages (`ai`, `apex`, `camera`, `capture`, `cinema`, `color`, `core`, `infrastructure`, `pipeline`,
`quality`, `recording`, `rendering`, `security`, `snce`, `stabilization`, `ui`, `utils`).

> Test-to-main ratio is exceptional: **562 test files vs 625 main files** (~0.9:1), all pure-JVM (no
> Robolectric → fast), with 95 contract/golden/characterization tests that freeze exact per-mode output
> via reflection.

---

## 4. Subsystem Rollup

| Subsystem | Code quality | Working level |
|---|:---:|---|
| Capture, RAW & Multi-frame | 7 | Mixed — live capture backbone, dormant computational-RAW/MFSR layer |
| Color science + SNCE engine | 8 | Mixed — live LUT/uniform grade + bounded SNCE nudge; rich perceptual machinery dormant |
| Motion (OmniClarity) + Stabilization | 9 | Mixed — motion stack **live default-on**; gyro-EIS stack dormant |
| Denoise/Detail + HDR/Tone | 7 | Mixed — live realtime GPU chain; many branded shaders are dead SPV blobs |
| Focus / Autofocus | 7.5 | Mixed — live continuous/tap/AI-subject AF; cine rack/pull/DoF API dormant |
| Recording / Encode / NPV10 | 8.3 | Mixed — solid live HEVC encoder; "elite" gates/master largely opt-in/test-only |
| AI/ML + Vulkan rendering + Startup | 8 | **Mostly live** — the strongest working cluster |
| UI + Quality gates + Build/Test | 8 | Mixed — polished live UI; measurement-to-gate loop closes only in tests |

Full per-feature inventory (every feature, sub-features, and individual ratings): see
[`FEATURE_CATALOG.md`](./FEATURE_CATALOG.md).

---

## 5. Strengths

- **Production-grade Vulkan native bridge (~10.7k SLOC of C++)** — genuine `VkDevice` init,
  `AHardwareBuffer` import, live preview+encoder compositor, and a properly-engineered *deferred
  background SPIR-V compile* (moves ~2.5s off cold-start) with all-or-nothing flag publishing and
  graceful raw passthrough. One of the strongest parts of the codebase (~9/10).
- **OmniClarity motion stack is genuinely live and default-on** across preview, encoder **and** record:
  full classical Argus optical flow (forward+backward block-match + 8-iteration variational Horn-Schunck
  refine + occlusion select), Riven motion deblur, and Chronos Kalman temporal warp — geometrically
  honest after CP52, validated against ground-truth EPE.
- **Solid, honest recording core** — real HEVC MediaCodec MOV encoder + deterministic plan resolver
  (profile/level/bitrate/I-frame), live native HDR bracket-merge compute, and **anti-fraud quality gates**
  (`OmegaCapabilityGate` validates real transport/bit-depth and refuses to fake a 10/10 — it correctly
  lands `NOT_PROVEN` without external evidence rather than synthesizing a pass).
- **Live AF is real and complete** — continuous hardware AF, tap-to-focus, MediaPipe-driven AI
  subject/face/eye tracking, and the recently-wired CP-F1 anti-hunting retrigger suppression feeding
  **real** hardware lens diopters.
- **Excellent test net** — 562 test files, 3,212 tests, all pure-JVM; golden/characterization tests
  freeze exact per-mode floats via reflection; meta-tests enforce source hygiene. Suites run green.
- **Rigorous, textbook-correct math** — ISO-12233 MTF50, full CIEDE2000, CAM16, Ottosson Oklab cusp,
  BT.2100 luma-preserving tonemap — genuinely high-end even where not yet wired to pixels.
- **Disciplined, honest engineering culture** — dormant paths are self-documented as dormant in-code,
  fallbacks are graceful and truthful, and the god-object ViewModel is decomposed into ~150 unit-tested
  pure Policy classes.

---

## 6. Weaknesses

- **Large fraction of the headline "beyond-IMAX" computational-imaging surface is dormant.** MFSR runs
  every frame on a **dummy input array** with the real-frame seed in a function literally named
  `unusedRealtimeComputeDiagnostics()` that is never called; the cinema-debayer and standalone demosaic
  Vulkan pipelines are *created but never dispatched* (RAW import "deferred to CP10c"); dual-gain and
  photon HDR-fusion shaders compile to SPV but are never built into pipelines.
- **The elaborate color/perceptual machinery does not feed live pixels.** Sensor→ACEScg matrices, the
  23-float SNCE scene-brain + CAM16 appearance output, NRLI neural LUT, exact Oklab gamut cusp, and
  `NeoGamutApexPipeline.processForOutput` all have only validation-harness or test callers; the live
  SNCE contribution is a bounded **±0.05 WB / ±0.06 saturation nudge** that discards the engine's rich
  output.
- **The entire custom gyro-EIS stabilization package is dormant** pure-Kotlin math with zero production
  callers; gyro is sampled live but dead-ends as a diagnostic timestamp string, and there is no GPU EIS
  warp shader for this path.
- **Live GPU motion shaders run on hardcoded C++ default push-constants** — the elegant Kotlin tuning
  policies (thermal/budget adaptation, scene-cut alpha gating, noise-aware deblur strength) are
  referenced only by their own tests and never reach the GPU (deblur `snrInverse` hardcoded 0, no live
  noise tracking).
- **The measurement-to-gate loop closes only in tests.** `EliteVisionMeasurementHarness` (the real-pixel
  MTF50/dE2000 producer) has no production caller, so the live Tier-Omega gate never receives measured
  pixels.
- **Many shaders exist purely as dead SPV blobs** referenced only in diagnostic word-count log strings
  (photon_hdr_fusion, dual_gain_fusion, ndr10_knee_curve, denoise, sharpen, nivukx_vision_denoise) — the
  well-branded variant is frequently *not* the one that runs live.
- **Two oversized god objects** — `CameraViewModel` (~10,183 lines / 102 StateFlows / 452 functions) and
  `MainActivity` (~3,191 lines) — remain a maintainability liability despite the policy decomposition.
- **NPV10 master breaks pixel-WYSIWYG by design** — it snapshots raw sensor YUV bypassing the GPU look
  stack and re-applies the look on a *separate* CPU/native export path (color *transport* is parity-
  locked, but pixel processing is a parallel reimplementation).

---

## 7. Standout Features (genuinely live & high-quality)

1. **Live OmniClarity motion stack** — classical optical flow + Riven deblur + Chronos Kalman temporal
   warp, dispatched on all three surfaces, validated against ground-truth EPE.
2. **Production-grade Vulkan bridge** with deferred background SPIR-V compile and graceful raw passthrough.
3. **`OmegaCapabilityGate`** — a genuine anti-fraud truth gate that hard-blocks unproven capability
   claims rather than synthesizing a pass.
4. **Live RAW pipeline** — native `DngCreator` DNG plus a hand-rolled compressed NPRAW stream (predictive
   encode, zigzag+varint, packed-12-bit, deflate, per-frame CRC/black/white/DR/noise header) with
   backpressure and direct-IO streaming.
5. **Rigorous objective image-quality library** — ISO-12233 slanted-edge MTF50, full CIEDE2000,
   flattest-tile SNR, opponent-space chroma-crawl.
6. **SNCE v2 14-stage human-visual-cortex color engine** — 9-method illuminant fusion, CAM16 viewing
   conditions, opponent lateral inhibition — fully built and 93-test-validated (currently wired live only
   as a fail-safe micro-nudge).

---

## 8. How to read these numbers

The 7/10 balances two opposed realities: **code quality and test discipline are high (8)** — the Vulkan
bridge, motion stack, encoder, AF, color math and measurement primitives are well-engineered and backed
by an unusually strong test net — while **feature completeness is only 6** because a large majority of the
marketed "beyond-IMAX" surface is dormant, dummy-fed or test-only. The honest result is a *fully-functional,
high-quality pro-camera app* whose live grade and processing are driven by a comparatively modest live
surface, sitting beneath an elaborate, beautifully-written, but largely inert elite layer. **Real and
shippable (which floors it well above mid), but a long device-gated wiring marathon away from the elite
product its feature surface advertises (which caps it below 8).**
