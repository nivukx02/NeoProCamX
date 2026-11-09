# SnceIlluminantEstimator (9-method) + SnceCam16 Appearance Chain — Elite Upgrade Prompt

> Global contract in [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) applies and is not restated.

| Field | Value |
|---|---|
| **Subsystem** | 2 — Color science + SNCE engine |
| **Prompt ID** | s2-f10 |
| **Maps to checkpoint(s)** | CP42 (real IMU + scene-change feed; scene-change done, IMU-pitch device-gated), CP44 (Duv / green-magenta tint axis in CAT16) |
| **Current working level** | 🟡 partial (live via processFrame; only AWB gains escape to the grade) |
| **Feature rating (potential)** | 6/10 now → 9/10 target |
| **Code-quality rating (now)** | 9/10 |
| **Risk · Size** | M · M |
| **Depends on** | s2-f05 (consumes the AWB gain + confidence), s2-f06 (CAM16 viewing conditions fill the 23-float block), s2-f04 (confidence → drift ceiling) |

## 1. What this feature is
`SnceIlluminantEstimator.kt` is SNCE Stage-01 (SIEE-9): a **nine-method** illuminant estimator —
seven classical methods end-to-end (gray-world `:70`, shades-of-gray `:72`, white-patch, etc.) plus
two injected priors (CNN `:75`, IMU `:84`) — all normalized to G=1 and fused by **confidence-weighted
voting** (`fuse`, `:87`) into a single illuminant RGB and the corresponding AWB gains
(`:94-96`, `illuminantRgb` `:100`). `SnceCam16.kt` is Stage-06 (CAME): the canonical **CAM16** color
appearance model (Li/Luo/Cui 2017 — the CIE-endorsed CIECAM02 successor), with CAT16 adaptation, the
degree-of-adaptation `D` term (`SnceCam16.kt:75`), and standard surround parameters
(F/c/Nc, `SnceCam16.kt:33, 54`). Together they form the perceptual core: *what is the light, and how
does the visual system appear to see color under it.* Today only the AWB gains escape to the grade.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
This is **color constancy + color appearance**, the heart of professional color science:
- **Illuminant estimation:** robust AWB fuses multiple cues (gray-world, white-patch/Retinex,
  shades-of-gray/Minkowski-p, gamut-based, and learned FFCC/CNN). The professional metric is
  **angular error** of the estimated illuminant vs ground truth and **neutral stability** (a gray card
  stays gray under the estimate).
- **CAM16 / CAT16 chromatic adaptation:** the CIE-endorsed appearance model. The **degree-of-adaptation
  `D`** controls how fully the observer adapts to the white point (incomplete adaptation under
  artificial light); ARRI/RED/Sony color science and ACES all use von-Kries/CAT-class adaptation. The
  **tint / Duv (green-magenta) axis (CP44)** matters because real illuminants (fluorescent, LED) sit
  off the Planckian locus — a CCT-only model leaves a green/magenta cast; the elite model adapts on
  **both** CCT and Duv.
Elite bar, measurable: (a) on a synthetic scene with a known illuminant, fused angular error ≤ a target
(and ≤ the best single method); (b) a gray card under the estimate is neutral (Duv ≤ 0.001, ΔE2000 ≤ 1);
(c) CAM16 forward∘inverse round-trips XYZ within 1e-4 and `D` is in [0,1] and monotone in luminance/
surround; (d) CP44: adapting an off-locus illuminant removes the green/magenta tint (Duv → ~0) without
shifting CCT.

## 3. Current state in NeoProCamX (verified)
- **Illuminant estimator is live** via `processFrame`: 7 classical methods + CNN/IMU priors, normalized
  to G=1, confidence-weighted fusion (`SnceIlluminantEstimator.kt:8, 12-13, 21, 47, 70-100`). **But
  only the fused AWB gains escape to the grade** (catalog §2 row 10) — the rich estimate (per-method
  votes, confidence) is otherwise discarded (the s2-f05/s2-f06 wiring problem).
- **CAM16 is canonical and correct:** `SnceCam16.kt` implements the real CAM16 with CAT16 adaptation
  and degree-of-adaptation `D` (`SnceCam16.kt:12-25, 33, 54, 75`) — the header explicitly notes it is
  the unified CAM16, not the common CIECAM02-mislabeled-CAM16 hybrid. Memory confirms canonical CAM16 +
  80 SNCE tests green.
- **CP42 scene-change half is done/live**; IMU-pitch half is device-gated (no absolute-pitch source
  captured). **CP44 (Duv/green-magenta tint axis in CAT16) is NOT done** — adaptation is effectively
  CCT-centric; the green-magenta tint axis is the elevation target.
- Confidence from fusion is computed but not yet routed to s2-f05 (nudge magnitude) or s2-f04 (drift
  ceiling).

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Fusion accuracy:** on synthetic known-illuminant scenes, fused angular error ≤ target and ≤ the
   best single method; G=1 normalization holds for every method (test).
2. **Neutral under estimate:** a gray card processed with the fused AWB gains is neutral (Duv ≤ 0.001,
   ΔE2000 ≤ 1.0).
3. **CAM16 invertibility:** forward∘inverse round-trips XYZ within 1e-4 over a wide gamut; `D ∈ [0,1]`
   and monotone in adapting luminance/surround (the `SnceCam16.kt:75` term tested).
4. **CP44 Duv axis:** CAT16 adaptation gains a green-magenta (Duv) tint term; adapting an off-Planckian
   illuminant drives gray-card Duv → ~0 **without** changing CCT; default (Duv=0 input) reproduces
   today's CCT-only adaptation bit-for-bit.
5. **Confidence export:** the fusion confidence is exposed for s2-f05 (nudge gating) and s2-f04 (drift
   ceiling); a low-confidence (mixed-illuminant) scene yields lower confidence, monotonically.
6. **No-downgrade default:** the AWB gains reaching the grade are unchanged until CP44/confidence are
   enabled; CP44 Duv term defaults to neutral.
7. **Budget (ms):** estimator + CAM16 already run per frame within budget; CP44 adds a scalar tint term
   (negligible); off cold-start.

## 5. Implementation prompt (hand this to the agent)
**Scope:** lock the estimator+CAM16 quality, add the CP44 green-magenta (Duv) adaptation axis behind a
neutral default, and export fusion confidence to s2-f04/s2-f05. **Non-goals:** do not wake the IMU-pitch
half (device-gated); do not ship a new illuminant model (CNN prior stays an optional null hook —
s2-f09); do not change the bounded nudge ceiling.

**Plan:**
1. **GOLDEN-LOCK FIRST.** Characterization freezing today's fused AWB gains for a fixed thumbnail set —
   the no-downgrade proof.
2. **Estimator tests** per §4.1-4.2 (angular error, G=1, neutral-under-estimate).
3. **CAM16 tests** per §4.3 (round-trip, `D` range/monotonicity).
4. **CP44 Duv term:** extend CAT16 adaptation with a green-magenta tint axis (Duv); default 0 →
   bit-identical. Test off-locus tint removal without CCT shift (§4.4).
5. **Confidence export:** surface fusion confidence to s2-f05 and s2-f04 (optional consumers; default
   ignores it → unchanged).

**Files:** `app/src/main/java/com/neoproc/neoproccamx/snce/SnceIlluminantEstimator.kt`,
`SnceCam16.kt`, `NeoGamutCat16Math.kt` (CAT16), `SnceV2Engine.kt` (confidence plumbing); tests under
`app/src/test/.../snce/`. No GPU ABI change (acts on the AWB gains + appearance chain).

**Gating:** CP44 Duv defaults neutral; confidence export is optional; AWB gains to the grade unchanged
until enabled.

## 6. Acceptance & verification
- **JVM:** fusion-accuracy, neutral-under-estimate, CAM16 round-trip/`D`, CP44 tint-removal,
  confidence-monotonicity, and the gain characterization-lock tests green (extends the existing ~80
  SNCE tests).
- **Device (vivo):** under fluorescent/LED (off-locus) light, CP44-on removes the green/magenta cast on
  a gray card with no CCT change; CP44-off matches today. Owner confirms via gray-card waveform/Duv
  readout.

## 7. Risks & no-downgrade guardrails
- **Wrong illuminant (cast):** confidence-weighted fusion + neutral-under-estimate test; low confidence
  → smaller nudge (s2-f05) and tighter ceiling (s2-f04).
- **CAM16 non-invertibility / NaN:** round-trip + `D`-range tests; sanitized inputs.
- **CP44 over-correction:** Duv term bounded; default neutral; gray-card test forbids CCT drift.
- **Pixel regression:** AWB-gain golden-lock; all new terms default to today.

## 8. References
- Roadmap CP42 (scene-change done / IMU-pitch device-gated), CP44 (Duv tint axis): [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Memory: SNCE v2 engine (canonical CAM16, 9-method fusion, 80 tests green)
- Standard canon (color constancy / angular error, CAM16/CAT16, degree-of-adaptation, Duv off-locus): [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) §2
- Source: `app/src/main/java/com/neoproc/neoproccamx/snce/SnceIlluminantEstimator.kt:8, 70-100`; `SnceCam16.kt:12-25, 33, 54, 75`
- Related prompts: s2-f04, s2-f05, s2-f06, s2-f09
