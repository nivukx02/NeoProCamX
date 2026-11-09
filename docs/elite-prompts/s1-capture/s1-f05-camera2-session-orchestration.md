# Camera2 Session / Stream Configuration & Capture Orchestration — Elite Upgrade Prompt

> Read [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) first — its global contract
> (no-downgrade, no-vendor, commit-per-feature, golden-first, ABI-locked, device-gated) applies
> here and is not restated.

| Field | Value |
|---|---|
| **Subsystem** | 1 — Capture, RAW & Multi-frame |
| **Prompt ID** | s1-f05 |
| **Maps to checkpoint(s)** | (hardening — no single CP; underpins CP10/CP16/CP60 and Phase-9 thermal) |
| **Current working level** | 🟢 live-default-on |
| **Feature rating (potential)** | 8/10 |
| **Code-quality rating (now)** | 8/10 |
| **Risk · Size** | M · M |
| **Depends on** | nothing upstream; everything in subsystem 1 depends on it |

## 1. What this feature is
The conductor of the camera hardware: it builds a single `CameraCaptureSession` with the right set
of output targets (preview + record encoder surface + RAW reader), assigns stream-use-case policies,
plans the per-mode stream set, selects the dynamic-range profile (SDR / HLG10 / HDR10) and rebuilds
the session on mode switch, inventories device capabilities, picks the HAL profile, and handles
session recovery/refresh. Every other capture feature (RAW, ISP-bypass, gyro, MFSR) is just a
configuration *of* this session — if orchestration drops a frame, mis-orders a reconfigure, or
silently degrades a stream, the elite work upstream is moot.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
A cinema body's signal routing is *deterministic and lossless*:
- **Simultaneous targets without compromise:** ARRI/RED/Sony feed a clean monitoring path *and* the
  full-fidelity record path from one sensor read; the monitoring branch never robs the master.
- **Mode changes are explicit and glitch-bounded:** changing resolution/framerate/dynamic-range is a
  defined transition, not a stall that drops the take.
- **Dynamic-range pipeline is end-to-end coherent:** the chosen transfer/gamut (S-Log3/HLG/PQ) is
  propagated from sensor read through every target so preview and master agree (the WYSIWYG promise).
- **Thermal/load behavior is predictable** — sustained recording does not silently drop frames or
  reconfigure the sensor mid-take.

Engineering targets translated for a phone (the floor is fixed; the orchestration must be
bulletproof): (1) **zero unintended frame drops** on the record path during steady-state and during
a mode-switch reconfigure (measurable: dropped-frame count == 0 outside an explicit reconfigure
window); (2) **reconfigure correctness** — preview+record+RAW targets reattach in the right order
with no UNCONFIGURED/black gap longer than the defined transition; (3) **dynamic-range coherence** —
the SDR/HLG10/HDR10 profile chosen is the one actually negotiated and reported, never silently
downgraded to 8-bit (honest gate); (4) **session recovery** — a HAL error/disconnect recovers to a
valid session without leaking surfaces.

## 3. Current state in NeoProCamX (verified)
- Orchestration is centered in `camera/CameraController.kt` with the request/plan policies:
  `camera/Camera2PipelineRequestPolicy.kt`, `camera/CameraCapabilityContract.kt`,
  `camera/CameraHalProfilePolicy.kt`, `camera/CameraRequestQualityProfile.kt` (verified present by
  grep across the camera package).
- Catalog rates this 🟢 feat 8 / quality 8: single session with preview+record+RAW targets,
  stream-use-case policies, per-mode stream plan, dynamic-range profile (SDR/HLG10/HDR10) session
  rebuild on mode switch, capability inventory, HAL profile, session recovery/refresh
  (FEATURE_CATALOG.md §1).
- Recent startup work hardened the *open* path: open camera on the first Surface instance to kill
  the `surfaceChanged`-storm debounce (recent commit `ff5d48d`), and deferred heavy pipeline
  compile off cold-start. So orchestration is healthy and live.
- **The hardening gap:** the reconfigure/recovery paths and the dynamic-range negotiation are not
  *characterized by tests that lock frame-drop == 0 and profile-coherence*. This is bulletproofing
  a working 🟢 feature, not wiring.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Frame-drop accounting:** the record path exposes a dropped-frame counter; a test (and a device
   soak) asserts 0 unintended drops in steady state, and that any drop during a reconfigure window
   is bounded and reported, never silent.
2. **Reconfigure ordering:** a session-rebuild characterization test pins the target
   attach/detach/order for each mode transition (SDR↔HLG10↔HDR10, resolution/fps change) so a future
   edit can't reorder it into a black gap; transition duration bounded.
3. **Dynamic-range coherence:** the negotiated profile equals the requested one or the request is
   honestly rejected/downgraded with a reported reason; an 8-bit silent fallback when 10-bit was
   requested is detected (ties to `OmegaCapabilityGate` strict-10-bit, simulated-fallback detection).
4. **Recovery:** a simulated HAL error transitions to a valid session with no surface leak (resource
   accounting test); preview resumes within a bounded deadline.
5. **No-downgrade default:** all changes are hardening/observability — the live session behavior is
   byte-identical; the startup-path optimizations already landed are preserved.
6. **Budget:** orchestration stays off the per-pixel path; reconfigure cost is bounded and never
   blocks the cold-start splash beyond the existing safety deadline.

## 5. Implementation prompt (hand this to the agent)
**Scope:** harden + characterize session orchestration: lock frame-drop, reconfigure order,
DR-coherence, and recovery with tests.
**Non-goals:** do not change the stream plan or which profiles are chosen (no-downgrade); do not
touch GPU/shaders; no new dependency.

**Plan:**
1. **Golden/characterization first.** Pin the current per-mode stream plan + target set + dynamic-
   range mapping (reflection-based, JVM-pure) so the live plan is provably unchanged.
2. **Frame-drop counter.** Surface an honest dropped-frame metric on the record path; test
   steady-state == 0 and bounded-during-reconfigure.
3. **Reconfigure-order lock.** Characterization test pinning attach/detach order + transition bound
   per mode switch.
4. **DR-coherence check.** Assert negotiated == requested (or honest rejection); wire the 8-bit
   simulated-fallback detection into the existing `OmegaCapabilityGate` strict-10-bit reporting.
5. **Recovery test.** Simulated HAL error → valid session, no surface leak, bounded resume.
6. **Device note.** DEVICE-GATED: vivo soak (frame-drop), mode-switch glitch timing, HLG10/HDR10
   negotiation reality.

**Files:** `camera/CameraController.kt`, `camera/Camera2PipelineRequestPolicy.kt`,
`camera/CameraCapabilityContract.kt`, `camera/CameraHalProfilePolicy.kt`,
`camera/CameraRequestQualityProfile.kt`; new characterization tests under the camera test tree.

**ABI:** no GPU push-constant change.

**Gating:** observability + tests only; live orchestration behavior unchanged (no-downgrade).

## 6. Acceptance & verification
- **Unit/characterization (JVM):** per-mode stream-plan + DR-mapping golden; frame-drop accounting
  (steady-state 0 / bounded-during-reconfigure); reconfigure attach/detach-order lock; recovery
  no-leak test; DR negotiated==requested-or-honest-reject.
- **Device (vivo I2405):** 5-minute record soak — 0 unintended drops; mode-switch glitch within
  bound; confirm HLG10/HDR10 actually negotiated (not silent 8-bit); induce a session error and
  confirm clean recovery.
- **Gate keys:** dynamic-range / 10-bit transport truth proves through `OmegaCapabilityGate`
  (strict-10-bit, simulated-fallback detection) — never a synthesized pass.

## 7. Risks & no-downgrade guardrails
- **Silent frame drop:** honest counter + steady-state-0 test trips CI on regression.
- **Reconfigure black gap / misorder:** order-lock characterization + bounded transition.
- **Silent 8-bit fallback:** DR-coherence check + gate wiring makes a faked 10-bit claim impossible.
- **Surface leak on recovery:** resource-accounting recovery test.
- **Cold-start regression:** preserve the landed first-Surface-open + deferred-compile behavior;
  characterization pins it.

## 8. References
- Roadmap: underpins CP10/CP16/CP60 + Phase-9 (CP90-92) in [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Tech: [`../technology_explanations/markdown/12-preview-recording-parity.md`](../technology_explanations/markdown/12-preview-recording-parity.md), [`../technology_explanations/markdown/13-hardwarebuffer-zero-copy.md`](../technology_explanations/markdown/13-hardwarebuffer-zero-copy.md)
- Reference: cinema simultaneous monitor+master routing; coherent transfer/gamut propagation (WYSIWYG).
- Source: `app/src/main/java/com/neoproc/neoproccamx/camera/CameraController.kt`, `Camera2PipelineRequestPolicy.kt`, `CameraCapabilityContract.kt`, `CameraHalProfilePolicy.kt`
- Related prompts: s1-f01, s1-f02, s1-f03, s1-f04, subsystem-6 recording prompts
