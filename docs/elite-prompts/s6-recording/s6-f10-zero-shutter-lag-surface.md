# Zero-Shutter-Lag / Persistent Input Surface — Elite Upgrade Prompt

> Read [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) first. Its global contract applies here and
> is not restated.

| Field | Value |
|---|---|
| **Subsystem** | 6 — Recording / Encode / NPV10 export |
| **Prompt ID** | s6-f10 |
| **Maps to checkpoint(s)** | hardening (record-start latency + no-dropped-startup-frame) |
| **Current working level** | 🔵 live-optional |
| **Feature rating (potential)** | 6.5/10 |
| **Code-quality rating (now)** | 8/10 |
| **Risk · Size** | M · S |
| **Depends on** | s6-f01 (encoder) |

## 1. What this feature is
The persistent input surface lets `RecordingManager` **pre-arm** a `MediaCodec` input Surface via
`MediaCodec.createPersistentInputSurface()` *before* the encoder is configured, so when the user
hits record the codec attaches to an already-live surface — eliminating the surface-creation + first
-configure latency at record-start and the dropped startup frames that come with a cold-armed
encoder. It selects between the persistent surface and a codec-owned surface, with honest fallback
when persistent surfaces are rejected by the device.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
Professional cameras run a continuous imaging pipeline; "record" just flags frames into the file —
there is no cold-start. The cinematic expectation is **zero-shutter-lag**: the frame at the instant
of the trigger is the frame captured (Pixel/iPhone ZSL keep a rolling ring of fully-processed frames
so the press maps to the *already-captured* moment, not a future re-capture). For video, the bar is
that record-start drops **no frames** and adds no visible latency — the encoder is hot before the
trigger. Translated here: pre-armed persistent surface so record-start latency is sub-frame, the
first recorded frame corresponds to the trigger instant (no missing head frames), and an honest,
tested fallback when the device rejects persistent surfaces (no silent dropped-frame burst at start).

## 3. Current state in NeoProCamX (verified)
- `RecordingManager.kt` — `MediaCodec.createPersistentInputSurface()` pre-arm (`:849`), stored and
  reused; `persistentInputSurfaceRejected` cleared on success (`:851`), set on failure (`:856,
  895`).
- Selection state: `persistentInputSurfaceAllowed` (default true, `:735`) gates the pre-arm
  (`:840`); `persistentInputSurfaceRejected` (`:733`) tracks device rejection; the encoder picks
  `persistentEncoderSurface` only when allowed (`:881`), recording `persistentSurfaceRejected` in
  evidence (`:885`).
- Allow toggle `:862`; reset on (re)config `:877`; fallback path on `:903`.
- **Status: genuinely live and honest** (catalog §6: feat 6.5 / quality 8). It already falls back
  cleanly and records the rejection. Hardening targets: prove the no-dropped-startup-frame guarantee
  and the record-start latency budget, and that the persistent-vs-codec-owned selection never
  produces a head-frame gap.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **No dropped startup frames:** record-start with the persistent surface produces a recorded head
   frame for the trigger instant — input frame count from t0 equals muxed frame count (no missing
   head). Measurable on device.
2. **Sub-frame record-start latency:** the time from the record trigger to the first encoded frame
   is within one frame interval at the active fps (pre-armed surface ⇒ no configure-on-trigger
   stall). Device-measured.
3. **Honest fallback:** when the device rejects persistent surfaces, the codec-owned path is used,
   `persistentSurfaceRejected` is recorded in evidence, and there is still no dropped-frame burst at
   start (fallback path tested).
4. **Selection correctness:** persistent-vs-codec-owned selection is deterministic and never leaves
   the encoder attached to a stale/released surface. Unit-test the state machine
   (`allowed`/`rejected`/`reset`).
5. **No cold-start coupling:** pre-arming the surface does not block app cold-start (it happens at
   record-arm, off the splash path); respects the no-lag-regression rule.
6. **No-downgrade default:** the persistent-surface path is the existing live behavior; hardening is
   accounting/test/evidence only — recorded bytes and timing of the *correct* path are unchanged.

## 5. Implementation prompt (hand this to the agent)
**Scope:** prove and lock the no-dropped-startup-frame + sub-frame-latency guarantees of the
persistent input surface, and the honest fallback. **Non-goals:** do not change the encoder config
(s6-f01); do not add ZSL still-capture ring buffering here; do not add a library.

**Plan:**
1. **Golden-lock first.** Characterize the persistent-vs-codec-owned selection state machine
   (`persistentInputSurfaceAllowed` / `Rejected` / reset transitions) with a unit test — proves the
   selection logic is frozen before changes.
2. **Startup-frame accounting.** Instrument input-vs-muxed frame counts from t0; expose a head-frame
   counter; device check asserts no head-frame gap. Accounting only — no behavioral change.
3. **Latency budget.** Add a device-measured record-start-to-first-frame timing check (≤ one frame
   interval at fps).
4. **Fallback test.** Simulate persistent-surface rejection ⇒ assert codec-owned path,
   `persistentSurfaceRejected` evidence set, no dropped-frame burst.
5. **Stale-surface guard.** Assert the encoder never attaches to a released persistent surface
   across a reset/reconfigure.

**Files:** `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingManager.kt` (state-machine
test surface, head-frame counter); the recording evidence struct (consumed by s6-f09).

**ABI:** none.

**Gating:** purely additive accounting/test/evidence; the live persistent-surface path and its
timing are unchanged.

## 6. Acceptance & verification
- **Unit/golden (JVM):** selection state-machine characterization (allowed/rejected/reset);
  fallback-on-rejection test; stale-surface guard test.
- **Gate keys:** `persistentSurfaceRejected` + head-frame/latency evidence feed
  `RecordingQualityEvidencePolicy` (s6-f09); realtime claim cross-checked (no
  `REALTIME_PERFORMANCE_NOT_PROVEN` regression).
- **Device (vivo):** (1) record-start → no head-frame gap (input==muxed from t0); (2) record-start
  latency ≤ one frame interval; (3) force persistent rejection → codec-owned fallback, evidence
  set, still no startup drop.

## 7. Risks & no-downgrade guardrails
- **Dropped head frames:** startup frame accounting + device check → caught, not assumed.
- **Configure-on-trigger stall:** pre-armed surface keeps the encoder hot; latency budget asserts it.
- **Stale/released surface attach:** state-machine + stale-surface guard test.
- **Silent fallback drop burst:** fallback path tested for no startup drop; rejection recorded
  honestly.
- **Cold-start coupling:** pre-arm at record-arm, off the splash path → no cold-start regression.

## 8. References
- Roadmap: Phase 7 encode / Phase 9 pipeline hardening in [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Tech: [`../technology_explanations/markdown/14-imax-strict-quality-profile.md`](../technology_explanations/markdown/14-imax-strict-quality-profile.md)
- Reference: ZSL rolling-ring capture (Pixel/iPhone); continuous-pipeline cinema cameras (record = flag, not cold-start).
- Source: `app/src/main/java/com/neoproc/neoproccamx/recording/RecordingManager.kt:733, 735, 840, 849, 851, 856, 877, 881, 885, 895, 903`
- Related prompts: s6-f01, s6-f09
