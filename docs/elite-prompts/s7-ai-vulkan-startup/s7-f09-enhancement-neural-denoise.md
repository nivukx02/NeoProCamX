# EnhancementEngine + NeuralDenoiseEngine — Elite Upgrade Prompt

> Read [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) first. Global contract applies; not restated.

| Field | Value |
|---|---|
| **Subsystem** | 7 — AI/ML + Vulkan rendering + Startup |
| **Prompt ID** | s7-f09 |
| **Maps to checkpoint(s)** | new — propose CP99 (enhancement/denoise hardening) |
| **Current working level** | 🔵 live-optional (classical path live; TFLite hook optional/absent) |
| **Feature rating (potential)** | 5/10 |
| **Code-quality rating (now)** | 7/10 |
| **Risk · Size** | M · M |
| **Depends on** | s7-f08 (AINeuralEngine — TFLite hook loader) |

## 1. What this feature is
`EnhancementEngine` + `NeuralDenoiseEngine` are the CPU-side enhancement stage: classical
bilateral denoise + unsharp + micro-contrast, scene-adaptive, with bypass/small-image early-exits.
`NeuralDenoiseEngine` adds an *optional* TFLite denoise hook (loaded via `AINeuralEngine`,
`denoising` alias) that engages only if the model binary is present. This is primarily an
export-time / still enhancement helper, not the live preview denoise (that is the GPU
`nivukx_denoise.comp` path, subsystem 4).

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
IMAX DMR reconstructs grain/sharpness per-frame; pro denoise (RED IPP2, ARRI) is signal-dependent
and edge-preserving — never a flat blur, never grain-amplifying. The elite bar for this classical
stage:
- **Edge-preserving, signal-dependent strength:** bilateral / unsharp parameters track a noise
  estimate, not a constant — so flat areas clean up and edges/texture survive.
- **No over-sharpen halos / no grain pumping:** unsharp bounded; micro-contrast clamped.
- **Honest optional model:** the TFLite hook, if present, is flagged distinctly; absence falls
  back to classical with no quality claim beyond what ran.
- **Budget:** bounded; bypass/early-exit for small or already-clean images.

## 3. Current state in NeoProCamX (verified)
- `app/src/main/java/com/neoproc/neoproccamx/ai/EnhancementEngine.kt` and `ai/NeuralDenoiseEngine.kt`
  — classical bilateral + unsharp + micro-contrast, scene-adaptive, bypass/small-image early-exits;
  optional TFLite denoise hook via `AINeuralEngine` (FEATURE_CATALOG §7).
- `NeuralDenoiseEngine` is one of the real `AINeuralEngine` consumers (s7-f08), but the `denoising`
  model binary is generally not shipped ⇒ the classical path is what runs.
- Hardening gaps (verify in source): noise-estimate-driven strength, unsharp halo bound,
  optional-model honesty flag.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Signal-dependent strength:** denoise/unsharp parameters scale with a measured noise estimate;
   test asserts higher strength on a noisy fixture, near-passthrough on a clean one.
2. **No halo / no grain pump:** unsharp output bounded; test asserts overshoot near a hard edge
   stays within a clamp and flat-field noise σ does not increase.
3. **Optional-model honesty:** when the TFLite hook is absent, classical runs and the result is
   flagged classical (no neural claim); when present, the path is flagged neural.
4. **Budget:** bounded per-image time; small/clean-image early-exit asserted.
5. **No-downgrade default:** current classical output bit-for-bit unless the new
   noise-driven/clamp flag is enabled; default reproduces today.

## 5. Implementation prompt (hand this to the agent)
**Scope:** make enhancement signal-dependent and halo-safe; flag the optional model honestly.
**Non-goals:** do NOT add a denoise model (no-vendor — keep the optional hook only); do not touch
the live GPU `nivukx_denoise.comp` path (subsystem 4).

**Plan:**
1. **Golden-lock:** pin current classical enhancement output on fixed fixtures (clean + noisy).
2. **Noise-driven strength:** route a noise estimate into bilateral/unsharp strength; behind a flag
   defaulting to current constant behavior.
3. **Halo clamp:** bound unsharp overshoot + micro-contrast.
4. **Honesty flag:** distinguish classical vs neural-hook result.

**Files:** `ai/EnhancementEngine.kt`, `ai/NeuralDenoiseEngine.kt`, `ai/AiNoiseReductionPolicy.kt`.

**ABI:** none (CPU path).

**Gating:** noise-driven/clamp behind a flag defaulting to current output.

## 6. Acceptance & verification
- **Unit (JVM):** signal-dependent-strength, halo/no-grain-pump, optional-model-honesty,
  early-exit, golden-lock.
- **Device (vivo / Adreno):** export/still enhancement cleans noise without halos or grain
  amplification; absence of the optional model degrades to classical with honest status.

## 7. Risks & no-downgrade guardrails
- **Over-sharpen halos:** unsharp overshoot clamp.
- **Grain amplification:** signal-dependent strength + flat-field σ check.
- **False neural claim:** optional-model honesty flag.
- **Regression:** flag defaults to current classical output; golden-lock.

## 8. References
- Roadmap: propose CP99 in [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Source: `app/src/main/java/com/neoproc/neoproccamx/ai/EnhancementEngine.kt`, `ai/NeuralDenoiseEngine.kt`
- Related prompts: s7-f08, s4 (NIVUKX live denoise)
