# SNCE v2 Live Colour Controller (residual-WB + Hunt-saturation nudge) — Elite Upgrade Prompt

> Global contract in [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) applies and is not restated.

| Field | Value |
|---|---|
| **Subsystem** | 2 — Color science + SNCE engine |
| **Prompt ID** | s2-f05 |
| **Maps to checkpoint(s)** | CP47 (confidence-gated dynamic ceiling consumes this controller's output) |
| **Current working level** | 🟢 live-default-on (analyzes preview YUV thumbnail + export P010; nudges grade slots 1/4/6) |
| **Feature rating (potential)** | 6/10 now → 8/10 target |
| **Code-quality rating (now)** | 8/10 |
| **Risk · Size** | M · M |
| **Depends on** | s2-f06 (the 23-float scene block it currently discards), s2-f10 (illuminant/CAM16 it summarizes), s2-f04 (ColorDriftGovernor ceiling) |

## 1. What this feature is
`SnceLiveColorController.kt` is the only live escape hatch from the SNCE engine into visible pixels.
It analyzes the camera YUV thumbnail (preview) and the P010 frame (export), then produces **three**
small, hard-clamped multipliers applied to the 16-float preview grade: red/blue WB scale (slots 4/6)
as a residual-cast correction, and saturation (slot 1) from the Hunt low-light colourfulness effect
(`SnceLiveColorController.kt:11-14`). Every write is re-sanitized, and the Vulkan bridge re-clamps.
The clamps are `MAX_WB_NUDGE = 0.05f` and `MAX_SAT_NUDGE = 0.06f` (`SnceLiveColorController.kt:109-110`).
It is a *refinement on top of* camera AWB — not a replacement — so it can only ever cool/warm slightly
and nudge colourfulness. It also drives the P010 record/export chroma path (slots 9/11,
`SnceLiveColorController.kt:87-94`).

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
The reference behavior is **bounded perceptual color constancy**, not aggressive auto-correction:
- Cinema cameras leave white balance to the operator and apply only **gentle, hysteretic** auto-trims;
  the visual-system insight (von Kries adaptation, CAM16 degree-of-adaptation `D`) is that a small
  residual-cast correction plus a colourfulness adjustment under low light (the **Hunt effect** —
  perceived colourfulness drops as luminance drops, so saturation is nudged up to compensate) is
  exactly the right, perceptually-grounded amount.
- The discipline: corrections are **confidence-weighted and clamped** so the look never swings. ARRI/
  RED reference monitors stay stable; a 5% WB / 6% sat ceiling is conservative and correct.
Elite bar, measurable: (a) corrections always within ±0.05 WB / ±0.06 sat (hard clamp, provable);
(b) residual-cast correction *reduces* neutral ΔE2000 on a gray patch (doesn't add cast); (c) the Hunt
saturation nudge scales monotonically with measured scene luminance (more lift in dim scenes, none in
bright); (d) the nudge is temporally smooth (no per-frame jitter beyond the ColorDriftGovernor budget);
(e) confidence-gated — low-confidence frames nudge less.

## 3. Current state in NeoProCamX (verified)
- Live, default-on, in all SNCE paths (catalog §2 row 5). Clamps confirmed: `MAX_WB_NUDGE = 0.05f`,
  `MAX_SAT_NUDGE = 0.06f` (`SnceLiveColorController.kt:109-110`); slots 1/4/6 (preview) and 9/11
  (P010 export) per `SnceLiveColorController.kt:11-14, 87-94`.
- **The waste:** the controller extracts only **three scalars** from the full SNCE engine output —
  the rich 23-float scene-brain (`SnceFrameConstants`, s2-f06) and the CAM16/illuminant chain (s2-f10)
  are computed every frame and then **discarded** (catalog: "Extracts only 3 scalars; the full engine
  output is computed then discarded"). The intelligence exists; only 3 numbers reach the grade.
- **No confidence gating yet:** the nudge is not yet scaled by illuminant/estimate confidence, and it
  does not yet feed the ColorDriftGovernor's dynamic ceiling (CP47, s2-f04).
- Re-sanitization on every write + bridge re-clamp confirmed (`SnceLiveColorController.kt:14`).

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Hard clamp invariant:** for any input, the emitted WB nudges ∈ [−0.05, +0.05] and the sat nudge ∈
   [1−0.06, 1+0.06] (or the documented sense); proven by a property test over random/extreme inputs.
2. **Cast reduction:** on a gray-patch sequence with a known residual cast, the controller's WB nudge
   reduces neutral ΔE2000 (never increases it). Test with a synthetic cast.
3. **Hunt monotonicity:** the saturation nudge is a monotonic, bounded function of measured scene
   luminance (≈0 in bright scenes, rising toward the +0.06 ceiling in dim scenes).
4. **Temporal smoothness + confidence gate (elevation):** add a confidence scalar (from s2-f10
   illuminant fusion) that scales the nudge magnitude; default = today's behavior (confidence = full).
   Feed that same confidence to the ColorDriftGovernor ceiling (s2-f04 / CP47). Test: confidence=full
   reproduces today bit-for-bit; lower confidence reduces nudge magnitude monotonically.
5. **No-downgrade default:** with no confidence source wired, output is byte-identical to today; the
   ±0.05/±0.06 ceiling is never widened.
6. **Budget (ms):** thumbnail analysis stays within the existing SNCE per-frame budget (it already
   runs); no new heavy compute; off the cold-start path.

## 5. Implementation prompt (hand this to the agent)
**Scope:** lock the clamp/cast/Hunt invariants, then add a confidence scalar that gates nudge
magnitude and feeds CP47 — without widening the ceiling. **Non-goals:** do NOT raise the ±0.05/±0.06
ceiling (that is the no-downgrade contract for the whole engine); do not replace camera AWB; do not
upload the 23-float block here (that is s2-f06); no new model.

**Plan:**
1. **GOLDEN-LOCK FIRST.** Characterization test freezing the (WB_r, WB_b, sat) triple for a fixed set of
   thumbnails — proves the default nudge is unchanged.
2. **Property tests** per §4.1 (clamp), §4.2 (cast reduction), §4.3 (Hunt monotonicity).
3. **Confidence gate (elevation):** surface the illuminant-fusion confidence (s2-f10) into the
   controller; multiply the nudge by a bounded `g(confidence)` with `g(full) = 1`. Default path has no
   confidence source → `g = 1` → unchanged. Expose the confidence so s2-f04's CP47 ceiling can consume
   it.
4. **Wire confidence → ColorDriftGovernor** (coordinate with s2-f04); keep optional.

**Files:** `app/src/main/java/com/neoproc/neoproccamx/snce/SnceLiveColorController.kt`,
`SnceV2Engine.kt` (to expose confidence), `ColorDriftGovernor.kt` (consume); tests under
`app/src/test/.../snce/`. No GPU ABI change (still slots 1/4/6/9/11).

**Gating:** confidence scalar defaults to 1.0 (today); the ceiling is never widened — the new path can
only ever *reduce* the nudge.

## 6. Acceptance & verification
- **JVM:** clamp property, cast-reduction, Hunt-monotonicity, confidence-default-unchanged, and the
  characterization lock green.
- **Device (vivo):** dim scene shows a gentle saturation lift (Hunt); a slightly-cast neutral wall
  trims toward neutral; nothing swings; low-confidence (e.g. mixed-illuminant) frames nudge less. Owner
  confirms no look swing on a pan.

## 7. Risks & no-downgrade guardrails
- **Look swing:** ±0.05/±0.06 hard clamp + re-sanitize + bridge re-clamp; ceiling never widened.
- **Adding cast:** cast-reduction test forbids increasing neutral ΔE2000.
- **Chasing noise:** confidence gate reduces nudge when the estimate is unreliable.
- **Pixel regression:** confidence default 1.0 + golden-lock = byte-identical today.

## 8. References
- Roadmap CP47 (confidence-gated ceiling), CP42 (illuminant/scene feed): [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Memory: SNCE v2 engine (14-stage, hybrid math + model hooks, not yet fully wired)
- Standard canon (von Kries / CAM16 degree-of-adaptation, Hunt effect): [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) §2
- Source: `app/src/main/java/com/neoproc/neoproccamx/snce/SnceLiveColorController.kt:11-14, 87-94, 109-110`
- Related prompts: s2-f04, s2-f06, s2-f10
