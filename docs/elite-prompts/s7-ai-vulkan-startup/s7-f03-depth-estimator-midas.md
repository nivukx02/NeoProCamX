# DepthEstimator (MiDaS v2.1 small TFLite) — Elite Upgrade Prompt

> Read [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) first. Global contract applies; not restated.

| Field | Value |
|---|---|
| **Subsystem** | 7 — AI/ML + Vulkan rendering + Startup |
| **Prompt ID** | s7-f03 |
| **Maps to checkpoint(s)** | new — propose CP94 (depth hardening); feeds CP4A masks |
| **Current working level** | 🔵 live-optional (gated behind aiAssist; off during record/high-load) |
| **Feature rating (potential)** | 7/10 |
| **Code-quality rating (now)** | 8/10 |
| **Risk · Size** | M · M |
| **Depends on** | s7-f01 (orchestrator), s7-f04 (segmentation for mask fusion) |

## 1. What this feature is
`DepthEstimator` runs the shipped MiDaS v2.1-small TFLite monocular-depth interpreter (the 66 MB
model — **kept, per no-vendor**) on strided preview frames, applies multi-frame temporal fusion +
edge-aware refine + guided upsample, and falls back to a synthetic radial-depth map when the
model is unavailable. Its relative-depth field feeds subject scoring, focus masks, and the
(no-production-caller) bokeh renderer.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
Cinema depth comes from real sensors — dual-pixel PDAF, stereo, LiDAR, or focus-sweep — giving
*metric* depth with calibrated confidence. A monocular MiDaS field is **ordinal/relative**, not
metric; the elite bar is to use it honestly:
- **Temporal stability:** depth must not flicker frame-to-frame on a static scene (temporal σ
  below threshold) — handled by the existing temporal fusion, which must be bounded so it does
  not lag fast motion.
- **Edge alignment:** depth discontinuities align to luma edges (guided/edge-aware refine) so
  masks don't bleed across subject boundaries.
- **Honest confidence:** the field carries a confidence/validity signal; metric-depth claims are
  never synthesized from a relative model (OmegaCapabilityGate discipline — depth-driven effects
  prove from real signal or fall back).
- **Budget:** the heaviest AI stage — must be the *first* dropped by the governor; runs on GPU
  delegate / NNAPI when available, off during record/high-load.

## 3. Current state in NeoProCamX (verified)
- `app/src/main/java/com/neoproc/neoproccamx/ai/DepthEstimator.kt` — real 66 MB MiDaS interpreter;
  GPU/NNAPI/CPU selection; multi-frame temporal fusion + edge-aware refine + guided upsample;
  synthetic radial fallback; feeds subject scoring & masks (FEATURE_CATALOG §7 + §5).
- **Gated:** runs only under `aiAssist`, off during record / high-load — i.e. live-optional, not
  default-on. Self-loads its model (not via the AINeuralEngine registry — s7-f08).
- **Bokeh consumer is dormant:** the bokeh renderer has no production caller (FEATURE_CATALOG §5);
  the depth field reaches focus scoring but not a visible blur in the shipping path.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Temporal stability:** on a static-scene fixture sequence, fused-depth temporal σ stays below
   threshold (no flicker) while a step-change scene cut is followed within K frames (no lag-lock).
2. **Edge alignment:** synthetic edge fixture — depth discontinuity aligns to the luma edge within
   a pixel tolerance after guided refine (asserted).
3. **Honest confidence:** depth field exposes a validity/confidence channel; no metric claim is
   emitted from the relative model; fallback path flagged as synthetic, never as MiDaS output.
4. **Budget & governor:** DepthEstimator is dropped FIRST under load (assert via governor
   drop-order test, shared with s7-f01); GPU/NNAPI selection preferred, CPU last.
5. **No-downgrade default:** keep the 66 MB MiDaS interpreter and the aiAssist gate; any new
   refine/confidence path behind a flag whose default reproduces today's depth field.

## 5. Implementation prompt (hand this to the agent)
**Scope:** harden temporal stability, edge alignment, and confidence honesty of the existing
MiDaS depth path; keep it gated/optional. **Non-goals:** do NOT replace MiDaS or add a depth
model (no-vendor — keep the 66 MB interpreter); do not enable bokeh by default; do not claim
metric depth.

**Plan:**
1. **Golden-lock:** pin current fused-depth output on fixed input fixtures (synthetic frames).
2. **Bounded temporal fusion:** add a scene-cut / motion gate so fusion does not lag fast motion;
   behind a flag defaulting to current fusion.
3. **Confidence channel:** surface a validity/confidence signal; mark synthetic-fallback frames
   distinctly so consumers (focus scoring) can down-weight.
4. **Edge-refine test coverage:** add the edge-alignment assertion to lock the guided refine.

**Files:** `ai/DepthEstimator.kt`, new `ai/DepthTemporalGatePolicy.kt`, focus-scoring consumer.

**ABI:** none (Kotlin/TFLite).

**Gating:** new fusion/confidence path behind a flag defaulting to today's depth field; aiAssist
gate unchanged.

## 6. Acceptance & verification
- **Unit (JVM):** temporal-σ, scene-cut-follow, edge-alignment, confidence-channel, golden-lock.
- **Device (vivo / Adreno):** depth stable on a static scene, follows a real cut; GPU/NNAPI
  delegate selected when available; depth stays OFF during record / high load; thermal soak drops
  depth first with no preview spike.

## 7. Risks & no-downgrade guardrails
- **Flicker / lag:** bounded temporal fusion + scene-cut gate.
- **Mask bleed:** edge-aware refine locked by test.
- **Fake metric depth:** confidence channel + synthetic-fallback flag; no metric claim.
- **Thermal:** dropped first by governor; off during record.
- **Regression:** flag defaults to current field; golden-lock; MiDaS model unchanged.

## 8. References
- Roadmap: propose CP94; CP4A masks in [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Source: `app/src/main/java/com/neoproc/neoproccamx/ai/DepthEstimator.kt`
- Related prompts: s7-f01, s7-f04, s7-f08, s5 focus prompts
