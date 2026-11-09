# ColorDriftGovernor Temporal White-Point Stabilizer — Elite Upgrade Prompt

> Global contract in [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) applies and is not restated.

| Field | Value |
|---|---|
| **Subsystem** | 2 — Color science + SNCE engine |
| **Prompt ID** | s2-f04 |
| **Maps to checkpoint(s)** | CP47 (confidence-gated dynamic WB/saturation ceiling) |
| **Current working level** | 🔵 live-optional (engaged under the drift-enforcement flag + lockable-auto SDR) |
| **Feature rating (potential)** | 6/10 now → 7/10 target |
| **Code-quality rating (now)** | 7/10 |
| **Risk · Size** | M · M |
| **Depends on** | s2-f05 (SNCE WB confidence), s2-f10 (illuminant confidence for CP47 gating) |

## 1. What this feature is
`ColorDriftGovernor.kt` is the per-frame temporal white-point stabilizer. Each frame it converts the
HAL's measured RGGB gains to a u'v' chromaticity (`WhiteBalanceChromaticity`), runs that through
`VideoColorTemporalStabilityPolicy` against the previously stabilized white point using the active
mode's drift budget, and tracks the stabilized white point plus whether this frame's requested motion
had to be **clamped to the budget** (i.e. AWB hunting was detected) and how often that has happened in
the shot (`clampedFrameCount`, `ColorDriftGovernor.kt:36-37`). The stabilized white point is exposed
both as u'v' and as ready-to-apply RGGB gains. It is the anti-flicker / anti-hunt governor on the WB
axis — the thing that keeps a static scene's color from breathing when AWB twitches.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
Cinema practice is to **lock or rate-limit white balance**, never let it free-run:
- Operators set a fixed Kelvin/tint (manual WB) so color is stable across a take; "auto" on a cinema
  camera is rate-limited and hysteretic, not frame-by-frame reactive. The failure mode being prevented
  is **chroma breathing / WB pumping** — visible neutral drift on a static frame.
- Where auto is used, the trajectory is **smoothed on the chromaticity manifold** (move along u'v'
  toward the target with a bounded per-frame step and hysteresis), exactly the one-pole / budgeted-step
  approach here. The professional spec is measured in **temporal flicker σ on neutrals** and
  **neutral-Duv stability**.
- IMAX DMR and DI conform demand the master be temporally stable: a neutral wall must not shimmer.
Elite bar, measurable: (a) on a static neutral scene with injected AWB jitter, output neutral u'v'
temporal σ is reduced by a target factor vs ungoverned; (b) the per-frame step never exceeds the
mode's drift budget (hard clamp); (c) a genuine lighting change is still tracked (settles within a
bounded number of frames — no permanent lag); (d) **confidence gating (CP47):** when the illuminant
estimate is low-confidence, the ceiling tightens (move less), so the governor never chases noise.

## 3. Current state in NeoProCamX (verified)
- `ColorDriftGovernor.kt` is a real per-frame consumer: HAL RGGB → u'v', temporal-stability policy vs
  previous stabilized point, budget clamp, `clampedFrameCount` tracking, dual u'v'+RGGB output
  (header `ColorDriftGovernor.kt:4-37`). **Live-optional** — engaged only under the drift-enforcement
  flag + lockable-auto SDR (catalog §2 row 4).
- The budget is published by `VideoColorStabilityPolicy`; the governor's doc notes the budget was
  previously "interpolated into a diagnostic log line — nothing measured against it" before this was
  wired (`ColorDriftGovernor.kt:7`). So it is *now* a real consumer, but its engagement is conditional.
- **CP47 (confidence-gated dynamic ceiling) is NOT done.** The ceiling/budget is mode-driven, not yet
  modulated by the SNCE illuminant confidence (s2-f10) or the SNCE WB nudge confidence (s2-f05). That
  is the elevation this prompt targets.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Hard budget clamp:** no per-frame u'v' step exceeds the active mode's drift budget — proven by a
   JVM test feeding a large requested jump and asserting the output step ≤ budget.
2. **Flicker reduction:** on a synthetic static-neutral sequence with injected AWB jitter, governed
   output neutral u'v' temporal σ ≤ a target fraction of ungoverned σ (use the temporal-flicker metric).
3. **Tracking, not lag:** a genuine step change in scene illuminant settles to within Duv tolerance in
   ≤ N frames (no permanent offset) — assert convergence.
4. **Confidence-gated ceiling (CP47):** when illuminant confidence (s2-f10) is low, the per-frame
   ceiling shrinks monotonically with confidence; high confidence restores the full mode budget. Test:
   ceiling(confidence) is monotonic and bounded; default (no confidence input) reproduces today's
   ceiling exactly.
5. **No-downgrade default:** with the drift-enforcement flag off, the governor does not alter pixels
   (today's behavior); with it on but no confidence source, the ceiling equals today's mode budget
   bit-for-bit.
6. **Budget (ms):** O(1) per frame, negligible; no allocation on the hot path.

## 5. Implementation prompt (hand this to the agent)
**Scope:** add the CP47 confidence-gated dynamic ceiling and lock the existing clamp/flicker behavior
with tests. **Non-goals:** do not change when the governor engages (flag + lockable-auto SDR stays);
do not move WB authority away from the HAL/SNCE; no new model.

**Plan:**
1. **GOLDEN-LOCK FIRST.** Characterization test freezing the governor's stabilized-output trajectory on
   a fixed jitter sequence at today's mode budget — proves the default is unchanged.
2. **Clamp + flicker + tracking tests** per §4.1-4.3.
3. **CP47 ceiling:** add a `confidence: Float` (default = the value that reproduces today's ceiling)
   that scales the per-frame budget via a monotonic, bounded curve. Feed it from the SNCE illuminant
   confidence (s2-f10) / WB-nudge confidence (s2-f05) when available. Test monotonicity + the
   default-reproduces-today property.
4. **Wire the confidence source** through the existing color-stability path; keep it optional so the
   default path (no confidence) is byte-identical.

**Files:** `app/src/main/java/com/neoproc/neoproccamx/camera/ColorDriftGovernor.kt` and the
`VideoColorStabilityPolicy`/`VideoColorTemporalStabilityPolicy` it consumes; tests under
`app/src/test/.../camera/`. No GPU ABI change (this acts on RGGB gains, not the uniform block).

**Gating:** confidence input defaults to "full budget" (today); the CP47 dynamic ceiling only tightens
below that when a real confidence source is present and the enforcement flag is on.

## 6. Acceptance & verification
- **JVM:** clamp, flicker-reduction, convergence, monotonic-ceiling, and default-unchanged tests green.
- **Device (vivo):** with enforcement off → identical to today; with it on → a panned static neutral
  wall shows no WB pumping, and a real lighting change still settles. Owner confirms via the neutral
  u'v' overlay / waveform.

## 7. Risks & no-downgrade guardrails
- **Over-stabilization (won't track real changes):** convergence test bounds settle time; ceiling
  restores to full budget at high confidence.
- **Chasing noise:** confidence gate shrinks the ceiling when the estimate is unreliable.
- **Pixel regression when disabled:** default = no-op; golden-lock proves it.
- **Lag:** O(1) per frame; no hot-path allocation.

## 8. References
- Roadmap CP47 (confidence-gated dynamic WB/saturation ceiling, dep CP42): [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Memory: reframe-washout diagnosis (binary AE/AWB lock → hysteresis) + checkpoint-2 color stability
- Standard canon (manual/rate-limited WB, flicker σ, neutral-Duv): [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) §2
- Source: `app/src/main/java/com/neoproc/neoproccamx/camera/ColorDriftGovernor.kt:4, 7, 36-37`
- Related prompts: s2-f05, s2-f10
