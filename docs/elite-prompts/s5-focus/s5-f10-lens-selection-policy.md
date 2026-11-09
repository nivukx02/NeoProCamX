# Lens Selection Policy (cinema multi-camera) — Elite Upgrade Prompt

> Read [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) first. Its global contract applies and
> is not restated here.

| Field | Value |
|---|---|
| **Subsystem** | 5 — Focus / Autofocus (lens/optics) |
| **Prompt ID** | s5-f10 |
| **Maps to checkpoint(s)** | new — propose CP-F11 (low-light / video lens-selection wiring) |
| **Current working level** | 🟡 partial (`selectPreferredCandidate` live; low-light / video / by-type selectors dormant) |
| **Feature rating (potential)** | 5/10 now → 7/10 target |
| **Code-quality rating (now)** | 7/10 (scoring is sound; the specialized selectors are unwired) |
| **Risk · Size** | L · M |
| **Depends on** | s5-f01 (afMode per lens), s1 capture (sensor/RAW caps), s7 scene detection |

## 1. What this feature is
`CameraLensSelectionPolicy` chooses which physical/logical camera to open from the device's lens
set. It classifies each candidate by type (ultra-wide / wide / standard / short-tele / tele) with
35mm-equivalent labeling, scores cinema quality (RAW, 10-bit HDR, OIS, hardware rank), and picks a
preferred camera. The general picker (`selectPreferredCandidate`) is **live** — it's what opens the
camera. But the specialized selectors — `selectLowLightCandidate`, `selectVideoCandidate`,
`selectCandidateForLensType` — are **dormant** (no production caller), so the app never picks a
brighter/OIS lens for low light or a 60fps/OIS lens for video even when one exists.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
A cinematographer chooses a lens for the shot: a fast prime (T1.3) for low light, a stabilized
zoom for handheld video, a specific focal length for the framing. Multi-sensor cinema phones and
rigs do the digital equivalent — pick the sensor whose aperture/OIS/frame-rate/bit-depth best fits
the *task*, not just a default wide. Elite behavior: (1) **task-aware selection** — low-light vs.
video vs. by-focal-length each pick the optimal sensor; (2) **deterministic, explainable scoring**
— the choice is a documented function of aperture, OIS, sensor size, RAW/10-bit, frame rate; (3)
**no-regression default** — when no task hint is present, the existing preferred-lens behavior is
unchanged. Translate to targets: **each selector is a pure, tested function of candidate
attributes that provably picks the best-scoring lens, and wiring it never changes the default
open-lens behavior.**

## 3. Current state in NeoProCamX (verified)
- `CameraLensSelectionPolicy.kt`: `selectPreferredCandidate` (158) is **live** — called from
  `CameraController.kt:3616` (`CameraLensSelectionPolicy.selectPreferredCandidate(facing, candidates)`).
- **Dormant selectors:** `selectCandidateForLensType` (175), `selectLowLightCandidate` (227),
  `selectVideoCandidate` (256) — each has well-defined scoring (e.g. low-light scores aperture×2.2,
  OIS, RAW, 10-bit, sensor pixels, hardware rank, `CameraLensSelectionPolicy.kt:230-249`) but a grep
  shows **no production caller** outside the policy file + its unit test
  (`CameraLensSelectionPolicyTest.kt`). They are tested-but-unwired.
- `classifyLensType` + 35mm-equivalent labeling are live (feed the lens-switch UI).
- Status: **🟡 partial.** Wiring + elevation prompt behind a no-downgrade gate.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Low-light selection wired (NEW):** when a low-light scene is detected (scene detector / low
   lux) and a brighter/OIS lens exists, the app can open `selectLowLightCandidate`'s pick — behind
   a no-downgrade gate. Test asserts it picks the highest-scoring low-light candidate.
2. **Video selection wired (NEW):** when entering video/record mode, `selectVideoCandidate`
   (OIS / ≥60fps / 10-bit / RAW preference) can choose the recording lens — behind a gate. Test
   asserts the pick.
3. **By-type selection wired (NEW):** the lens-switch UI / cinema multi-cam mode can request a
   specific `LensType` via `selectCandidateForLensType` (exact-type then closest-focal fallback).
   Test asserts exact-match-then-fallback.
4. **Deterministic scoring:** every selector is a pure function — same candidates → same pick;
   regression-locked over a candidate fixture.
5. **No-downgrade default:** with no task hint (and the gate off), the opened lens is exactly
   today's `selectPreferredCandidate` result — bit-for-bit. Owner enables task-aware selection
   device-side.
6. **Budget:** selection is O(n) over a handful of candidates at mode-switch time; zero per-frame
   cost.

## 5. Implementation prompt (hand this to the agent)
**Scope:** wire `selectLowLightCandidate`, `selectVideoCandidate`, `selectCandidateForLensType`
into the camera-open / mode-switch path behind a no-downgrade gate; preserve the default
`selectPreferredCandidate` behavior exactly. **Non-goals:** do not change the scoring formulae
(tested); do not change `classifyLensType` / labeling; do not enable task-aware selection by
default; no new hardware probe beyond existing characteristics.

**Plan:**
1. **Golden-lock first.** Pin the current opened-lens result of `selectPreferredCandidate` for a
   candidate fixture (the default behavior is the golden).
2. Add a gated task hint at camera-open / mode-switch (low-light from scene detector, video from
   record-mode entry, by-type from the lens-switch UI) that routes to the matching selector;
   default = no hint ⇒ `selectPreferredCandidate`.
3. Add per-selector pick tests + the default-unchanged regression lock.

**Files:** `app/src/main/java/com/neoproc/neoproccamx/camera/CameraLensSelectionPolicy.kt`
(read-only scoring), `CameraController.kt` (camera-open / mode-switch routing),
`CameraLensSelectionPolicyTest.kt` (extend), new test cases.

**ABI:** none (Kotlin only).

**Gating:** task-aware selection behind a flag defaulting OFF; default = today's `selectPreferred
Candidate` pick exactly, proven by the golden lock.

## 6. Acceptance & verification
- **Unit/golden (JVM):** low-light pick test; video pick test; by-type exact-then-fallback test;
  deterministic-scoring regression; default-unchanged golden (gate off ⇒ today's lens).
- **Device (vivo I2405 / Adreno — multi-camera):** enable task-aware selection — low-light scene
  opens the brighter/OIS lens, video mode opens the OIS/60fps lens, by-type request opens the right
  focal length; disable — opens exactly today's lens.

## 7. Risks & no-downgrade guardrails
- **Wrong default lens after wiring:** gate OFF by default; default-unchanged golden locks today's
  `selectPreferredCandidate` pick.
- **Opening an unopenable/secondary sensor:** selectors filter on `openable` / candidate validity
  (as `selectPreferredCandidate` already does); tests cover empty/degenerate candidate lists.
- **Unexpected lens switch mid-shot:** selection only at camera-open / mode-switch, never per-frame.
- **Lag:** O(n) over a few candidates at switch time; zero per-frame cost; no new probe.

## 8. References
- Roadmap: propose CP-F11 in
  [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Reference: multi-sensor cinema-phone lens selection; fast-prime low-light / stabilized-zoom video
  lens-choice craft; 35mm-equivalent focal classification.
- Source: `CameraLensSelectionPolicy.kt:158, 175, 227, 256` (only `selectPreferredCandidate` is
  live — called at `CameraController.kt:3616`), `CameraLensSelectionPolicyTest.kt`
- Related prompts: s5-f01, s1 capture prompts (sensor/RAW caps)
