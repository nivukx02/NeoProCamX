# Continuous AF Mode Selection (hardware) — Elite Upgrade Prompt

> Read [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) first. Its global contract
> (no-downgrade, no-vendor, commit-per-feature, golden-first, ABI-locked, device-gated) applies
> and is not restated here.

| Field | Value |
|---|---|
| **Subsystem** | 5 — Focus / Autofocus |
| **Prompt ID** | s5-f01 |
| **Maps to checkpoint(s)** | new — propose CP-F2 (continuous-AF mode hardening) |
| **Current working level** | 🟢 live-default-on |
| **Feature rating (potential)** | 8/10 |
| **Code-quality rating (now)** | 8/10 |
| **Risk · Size** | L · S |
| **Depends on** | s5-f02 (tap regions), s5-f04 (AI coordinator) |

## 1. What this feature is
The base hardware-autofocus contract: every preview/record `CaptureRequest` carries a sane
`CONTROL_AF_MODE`. The policy prefers `CONTINUOUS_VIDEO` (or `CONTINUOUS_PICTURE`) for live
shooting, resolves the conflict between hardware AF and the app's manual / AI lens-distance
override, and only attaches AF metering regions when the device actually reports support
(`CONTROL_MAX_REGIONS_AF > 0`). It is the foundation every other focus feature sits on: if the
afMode is wrong, tap-to-focus, AI tracking, and the lens-distance drive all misbehave.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
Cinema bodies historically default to *manual* focus driven by a focus puller, but modern hybrid
cinema/broadcast systems (Canon Dual Pixel CMOS AF on the C70/C300 III, Sony VENICE-class AF
assist, Panasonic DFD) expose a continuous-servo mode whose elite behavior is: (1) deterministic
mode selection — the camera never silently flips between modes mid-take; (2) confidence-gated
servo — it does not hunt when confidence is low, it *holds*; (3) clean hand-off between auto and
manual so a puller can override instantly without a focus jump. Dual-Pixel/PDAF systems give
phase information that makes continuous AF directional (knows which way to drive) rather than
contrast-only hill-climbing. Translate to measurable targets: **mode is stable across a take
(zero unintended mode transitions in a 60 s clip), the selected mode matches the documented
preference table for the device's capabilities, and manual/AI override wins deterministically.**

## 3. Current state in NeoProCamX (verified)
- afMode preference + region gating live in `CameraAutofocusRequestPolicy.kt` — region attach is
  guarded by `selectedAfMode != CONTROL_AF_MODE_OFF && maxAfRegions > 0`
  (`CameraAutofocusRequestPolicy.kt:33`).
- `maxAfRegions` is read once from characteristics
  (`CameraController.kt:3775` — `CONTROL_MAX_REGIONS_AF ?: 0`) and threaded into request building
  (`CameraController.kt:1395, 1399, 1668, 3970`).
- The manual / AI-lens-distance vs afMode resolution is decided in `CameraController` request
  assembly and consumed by the AI coordinator's `maxAfRegions > 0` branch
  (`AIAutoFocusCoordinator.kt:338`).
- Status: **live, default-on.** This is a hardening prompt, not a wiring prompt — the path
  already reaches pixels on every frame.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Deterministic preference table:** given a device capability set (afAvailableModes,
   maxAfRegions, lensInfo), the selected afMode is a pure function of inputs — same inputs →
   same mode, asserted by a parameterized unit test covering CONTINUOUS_VIDEO present/absent,
   maxAfRegions 0/1/N, manual-lens-only.
2. **Override precedence:** when the owner sets manual focus or AI lens distance, afMode resolves
   to `OFF` (or the manual path) deterministically; a test asserts override wins over continuous.
3. **No region on unsupported hardware:** when `maxAfRegions == 0`, no AF region is ever attached
   (already true; lock it with a regression test).
4. **No mode thrash:** the policy never emits a different afMode for two identical consecutive
   inputs (idempotence test); device check confirms zero unintended transitions in a 60 s clip.
5. **No-downgrade default:** the current preference order and region-gating output are unchanged;
   any new tie-break is additive behind the same input contract.
6. **Budget:** zero per-frame cost beyond today (pure request-flag selection, no compute).

## 5. Implementation prompt (hand this to the agent)
**Scope:** harden and golden-lock the afMode selection + region-gating policy; make the
preference table explicit and exhaustively tested. **Non-goals:** do not change tap-region
geometry (s5-f02), do not touch the AI coordinator's drive logic (s5-f04), do not add new modes.

**Plan:**
1. **Golden-lock first.** Add a characterization test that pins the current afMode output for a
   matrix of capability inputs (the live behavior is the golden).
2. Extract any inline mode-selection branches in `CameraController` into the existing
   `CameraAutofocusRequestPolicy` as pure functions (no behavior change), so the decision is one
   testable place.
3. Add the explicit preference table as a documented constant; assert it matches today's output.
4. Add idempotence + override-precedence tests.

**Files:** `app/src/main/java/com/neoproc/neoproccamx/camera/CameraAutofocusRequestPolicy.kt`,
`CameraController.kt` (extract-only), new `CameraAutofocusRequestPolicyTest` cases.

**ABI:** none (no GPU/push-constant change).

**Gating:** pure refactor + tests; the no-downgrade default is "identical afMode for all
existing inputs", proven by the golden lock.

## 6. Acceptance & verification
- **Unit/golden (JVM):** preference-table matrix test green; idempotence test; override-precedence
  test; `maxAfRegions==0 ⇒ no region` regression test.
- **Device (vivo I2405 / Adreno):** record a 60 s handheld clip — confirm zero unintended afMode
  transitions in the capture-request log; confirm AF regions attach only when the device reports
  `maxAfRegions > 0`.

## 7. Risks & no-downgrade guardrails
- **Mode thrash / focus jump:** idempotence test + deterministic preference table make a silent
  mid-take flip impossible-by-construction.
- **Override ignored:** explicit precedence test guarantees manual/AI override wins.
- **Region on unsupported device:** `maxAfRegions > 0` guard retained and regression-locked.
- **Lag:** zero added per-frame compute.

## 8. References
- Roadmap: propose CP-F2 in [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Reference: Canon Dual Pixel CMOS AF, PDAF/Dual-Pixel continuous servo, Camera2 `CONTROL_AF_MODE`.
- Source: `CameraAutofocusRequestPolicy.kt:33`, `CameraController.kt:1395, 3775`,
  `AIAutoFocusCoordinator.kt:338`
- Related prompts: s5-f02, s5-f04
