# SceneDetector (ML Kit ImageLabeling) — Elite Upgrade Prompt

> Read [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) first. Global contract applies; not restated.

| Field | Value |
|---|---|
| **Subsystem** | 7 — AI/ML + Vulkan rendering + Startup |
| **Prompt ID** | s7-f07 |
| **Maps to checkpoint(s)** | new — propose CP97 (scene-detector hardening) |
| **Current working level** | 🔵 live-optional |
| **Feature rating (potential)** | 6/10 |
| **Code-quality rating (now)** | 7/10 |
| **Risk · Size** | L · S |
| **Depends on** | s7-f01 (orchestrator) |

## 1. What this feature is
`SceneDetector` runs the ML Kit `ImageLabeling` client (highest-stride / least-frequent AI stage)
to classify the scene and emit HDR / low-light hints that bias exposure and tone-mapping policy.
It has a histogram-based classical fallback when ML Kit is unavailable.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
Cinema cameras don't auto-classify scenes — the DP sets the look. But computational cameras use
scene intent to pick HDR vs SDR, low-light handling, and tone intent. The elite bar here is
*stability and honesty*, not cleverness:
- **Hysteretic scene state:** scene class changes only with debounce/hysteresis so the hint
  doesn't flip-flop frame-to-frame and pump exposure/tone.
- **Confidence-gated hints:** below-confidence labels emit no hint (keep prior) rather than a
  low-confidence guess.
- **Honest fallback:** the histogram fallback is flagged distinctly from ML Kit labeling.
- **Negligible budget:** highest stride; near-zero cost; never on the imaging path.

## 3. Current state in NeoProCamX (verified)
- `app/src/main/java/com/neoproc/neoproccamx/ai/SceneDetector.kt` — real ML Kit labeling client;
  histogram fallback; scene-type → HDR/low-light hints (FEATURE_CATALOG §7).
- Hints consumed by exposure / HDR tone policy (e.g. `AIHdrToneMapPolicy.kt`,
  `SceneExposureBiasPolicy.kt`).
- Hardening gaps (verify in source): debounce/hysteresis on scene class, confidence gating on
  hints, fallback honesty flag.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Hysteresis:** scene class requires N consistent frames (or a confidence margin) to change;
   test feeds an alternating label stream and asserts no flip-flop.
2. **Confidence gating:** below-threshold labels keep the prior hint (no low-confidence guess) —
   asserted.
3. **Fallback honesty:** histogram-fallback hints flagged; consumers can treat them conservatively.
4. **Budget:** highest stride; negligible per-frame cost; never blocks imaging/cold-start.
5. **No-downgrade default:** exposure/tone behavior unchanged unless the hysteresis/gating flag is
   enabled; default reproduces current hints.

## 5. Implementation prompt (hand this to the agent)
**Scope:** add scene-class hysteresis, confidence gating, and fallback honesty.
**Non-goals:** no new model (no-vendor — keep ML Kit); do not change the exposure/tone math
itself (just the hint stability).

**Plan:**
1. **Golden-lock:** pin current scene class + hint output on fixed fixtures.
2. **Hysteresis policy:** pure `SceneStateHysteresisPolicy` (debounce/margin) behind a flag
   defaulting to current behavior.
3. **Confidence gate + fallback flag.**
4. **Tests:** no-flip-flop, confidence-gate-holds-prior, fallback-flag, golden-lock.

**Files:** `ai/SceneDetector.kt`, new `ai/SceneStateHysteresisPolicy.kt`, hint consumers
(`AIHdrToneMapPolicy.kt` / `SceneExposureBiasPolicy.kt`) read-only.

**ABI:** none.

**Gating:** hysteresis/gating behind a flag defaulting to current hints.

## 6. Acceptance & verification
- **Unit (JVM):** hysteresis no-flip-flop, confidence-gate, fallback-flag, golden-lock.
- **Device (vivo / Adreno):** panning across scene types does not pump exposure/tone; low-light
  hint engages cleanly; histogram fallback works when ML Kit unavailable.

## 7. Risks & no-downgrade guardrails
- **Exposure/tone pumping:** hysteresis + confidence gate.
- **Low-confidence guesses:** gate holds the prior hint.
- **Fake labeling from fallback:** fallback honesty flag.
- **Regression:** flag defaults to current hints; golden-lock.

## 8. References
- Roadmap: propose CP97 in [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Source: `app/src/main/java/com/neoproc/neoproccamx/ai/SceneDetector.kt`, `ai/AIHdrToneMapPolicy.kt`
- Related prompts: s7-f01, s4 (HDR/tone)
