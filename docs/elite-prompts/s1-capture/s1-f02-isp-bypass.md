# ISP Bypass for Sensor-Native Capture — Elite Upgrade Prompt

> Read [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) first — its global contract
> (no-downgrade, no-vendor, commit-per-feature, golden-first, ABI-locked, device-gated) applies
> here and is not restated.

| Field | Value |
|---|---|
| **Subsystem** | 1 — Capture, RAW & Multi-frame |
| **Prompt ID** | s1-f02 |
| **Maps to checkpoint(s)** | new — propose **CP17** (ISP-bypass verification & honesty gate) |
| **Current working level** | 🔵 live-optional (engaged with RAW/sensor-native modes) |
| **Feature rating (potential)** | 8/10 |
| **Code-quality rating (now)** | 9/10 |
| **Risk · Size** | M · M |
| **Depends on** | s1-f05 (session orchestration), s1-f01 (RAW serialization), s1-f03 (calibration) |

## 1. What this feature is
ISP bypass tells the camera HAL to *stop "helping"* — it requests a linear (1:1) tonemap and
turns off the in-silicon edge enhancement, noise reduction, hot-pixel correction, lens shading
and chromatic-aberration correction so the bytes we capture are the sensor's, not the vendor
ISP's interpretation. It also drives a distortion-correction policy (sensor-native vs corrected)
and gates each request against the device's actual Camera2 characteristics, warning the user when
a device cannot fully comply. It is the precondition for honest RAW: there is no point preserving
14-bit samples if the ISP already baked in noise reduction and sharpening.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
A cinema camera has no consumer ISP to fight — the signal chain from photosite to recorded RAW is
deliberately *minimal and characterized*:
- **ARRI / RED / Sony** record before any creative or "smart" processing; sharpening, NR and tone
  shaping are explicitly downstream (LogC4, IPP2, S-Log3) and reversible, never silently applied
  in-sensor. ARRI publishes the exact processing so a colorist can invert it.
- **IMAX DMR** reconstructs grain/sharpness/contrast *per-frame in post* precisely because the
  capture stage is kept clean — you cannot reconstruct what the ISP already destroyed.
- The professional rule: **edge enhancement and NR are information-destroying** and must not occur
  before the master is written; any device claiming "RAW" must prove the ISP blocks are actually off.

Engineering targets translated: (1) **provable off-state** — for each of EDGE, NOISE_REDUCTION,
HOT_PIXEL, SHADING, ABERRATION, the request is set to OFF/no-op *and* the `TotalCaptureResult`
read back confirms the device honored it; (2) **linear tonemap** — `TONEMAP_MODE` is the identity
contrast curve, verifiable from result metadata; (3) **honest capability reporting** — a device
that silently re-enables a block lands `NOT_PROVEN`, never a faked "bypass complete"; (4)
distortion correction is an explicit, recorded policy (sensor-native geometry vs corrected), not a
hidden default.

## 3. Current state in NeoProCamX (verified)
- ISP-bypass application policy: `camera/CameraIspBypassApplicationPolicy.kt` (verified by grep for
  the bypass/EDGE/NR/HOT_PIXEL/TONEMAP cluster), with capability scoring in
  `camera/CameraBypassCapabilityScorePolicy.kt`.
- Request construction touches these blocks in `camera/CameraController.kt` and
  `camera/Camera2PipelineRequestPolicy.kt`; per-feature available-modes gating is part of the
  `camera/CameraCapabilityContract.kt` family.
- Catalog rates this 🔵 feat 8 / quality 9: linear 1:1 tonemap; EDGE/NR/HOT_PIXEL/SHADING/
  COLOR_ABERRATION off; distortion-correction policy; capability gating + user warning on
  incomplete bypass (FEATURE_CATALOG.md §1).
- **The gap to elite:** the code *requests* bypass and *gates* on advertised capability, but there
  is no closed loop that **reads back the `TotalCaptureResult` to confirm the device actually
  honored each OFF request** and surfaces a truthful per-block verdict. Today's quality is high but
  the honesty proof (a block silently re-enabled by the HAL) is not measured from results.

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **Requested-vs-honored verification:** for each ISP block, a policy compares the requested mode
   to the value in the returned `TotalCaptureResult`; a mismatch yields a per-block
   `HONORED / NOT_HONORED / UNAVAILABLE` verdict. Unit-tested against synthetic result fixtures.
2. **Linear-tonemap proof:** when bypass is on, the result's `TONEMAP_MODE`/curve is asserted to be
   the identity (or CONTRAST_CURVE == linear), not a vendor S-curve.
3. **Honest gate:** the aggregate "sensor-native capture" claim is `PROVEN` only if every required
   block reads back honored; otherwise it lands `NOT_PROVEN` and the user warning fires. Never
   synthesize a pass (HONEST-GATES contract).
4. **Distortion policy recorded:** the chosen geometry (sensor-native vs corrected) is recorded in
   the capture metadata/sidecar so downstream (DNG, NPRAW) and review know which was used.
5. **No-downgrade default:** the verification is observe-only — it changes reporting, not the
   request behavior; existing bypass requests on existing devices are byte-identical.
6. **Budget:** verification is a per-result metadata compare (negligible cost), off the per-pixel
   path.

## 5. Implementation prompt (hand this to the agent)
**Scope:** add a read-back verification + honest verdict for ISP bypass; record distortion policy.
**Non-goals:** do not change which modes are requested (no-downgrade); do not add per-pixel work;
no new model/library.

**Plan:**
1. **Golden-lock the request set.** Pin today's requested ISP-block modes for each capture profile
   so the request behavior is provably unchanged.
2. **Add the verification policy.** New `camera/CameraIspBypassVerificationPolicy.kt`: input =
   requested modes + `TotalCaptureResult`; output = per-block verdict + aggregate
   `PROVEN/NOT_PROVEN`. Pure function, fully unit-tested with synthetic results.
3. **Wire the verdict to reporting only.** Surface it through the existing user-warning path and,
   if RAW reaches a master, through `OmegaCapabilityGate` as the honest source of the bypass claim.
4. **Record distortion policy** in the capture metadata/sidecar (sensor-native vs corrected).
5. **Device note.** DEVICE-GATED: confirm on the vivo which blocks the HAL actually honors vs
   silently re-enables; record the per-block result.

**Files:** new `camera/CameraIspBypassVerificationPolicy.kt`; minimal wiring in
`camera/CameraController.kt` / `camera/CameraIspBypassApplicationPolicy.kt`; metadata sidecar writer.

**ABI:** no GPU/push-constant change.

**Gating:** verification is reporting-only; request behavior unchanged by default (no-downgrade).

## 6. Acceptance & verification
- **Unit (JVM):** per-block verdict against synthetic results (honored / silently-re-enabled /
  unavailable); aggregate `PROVEN` only when all required blocks honored; linear-tonemap assertion;
  request-set golden unchanged.
- **Device (vivo I2405):** capture in bypass mode, dump `TotalCaptureResult`, confirm each block's
  honored verdict; verify the user warning fires when a block is not honored; confirm linear
  tonemap in the result.
- **Gate keys:** sensor-native/RAW claim flows through `OmegaCapabilityGate` from the real verdict.

## 7. Risks & no-downgrade guardrails
- **False "bypass complete":** read-back verification + `NOT_PROVEN` fallback makes a silent
  re-enable impossible to report as success.
- **Behavior drift:** verification is observe-only + request-set golden — request behavior cannot
  change without tripping CI.
- **Hidden geometry:** distortion policy explicitly recorded so downstream never guesses.

## 8. References
- Roadmap: propose **CP17** under Phase 1 in [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Tech: [`../technology_explanations/markdown/09-neoradiance-raw-and-npraw-modes.md`](../technology_explanations/markdown/09-neoradiance-raw-and-npraw-modes.md), [`../technology_explanations/markdown/11-pixel-reality.md`](../technology_explanations/markdown/11-pixel-reality.md)
- Reference: ARRI/RED/Sony minimal-ISP capture philosophy; IMAX DMR per-frame reconstruction.
- Source: `app/src/main/java/com/neoproc/neoproccamx/camera/CameraIspBypassApplicationPolicy.kt`, `CameraBypassCapabilityScorePolicy.kt`, `CameraController.kt`, `Camera2PipelineRequestPolicy.kt`
- Related prompts: s1-f01, s1-f03, s1-f05
