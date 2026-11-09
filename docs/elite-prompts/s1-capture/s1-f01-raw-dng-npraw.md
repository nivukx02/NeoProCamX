# RAW Video/Still Capture & Serialization (DNG + NPRAW) — Elite Upgrade Prompt

> Read [`../_PROMPT_STANDARD.md`](../_PROMPT_STANDARD.md) first — its global contract
> (no-downgrade, no-vendor, commit-per-feature, golden-first, ABI-locked, device-gated) applies
> here and is not restated.

| Field | Value |
|---|---|
| **Subsystem** | 1 — Capture, RAW & Multi-frame |
| **Prompt ID** | s1-f01 |
| **Maps to checkpoint(s)** | CP16 (RAW bit-depth: stop 14→12 truncation + highlight-preserving companding) |
| **Current working level** | 🔵 live-optional (runs when RAW/NPRAW mode selected) |
| **Feature rating (potential)** | 8/10 |
| **Code-quality rating (now)** | 8/10 |
| **Risk · Size** | M · M |
| **Depends on** | s1-f03 (calibration/noise), s1-f05 (session orchestration), feeds s1-f06/s1-f07 |

## 1. What this feature is
The capture-and-serialize backbone for sensor-native data. It pulls `RAW_SENSOR` Images off the
live Camera2 session, writes Adobe-compatible DNG stills (`DngCreator` + `TotalCaptureResult`,
cinema metadata, GPS), and streams a per-frame NPRAW compressed video format (spatial prediction
→ zigzag/varint → adaptive quantization → packed 12/16-bit LE → deflate, with per-frame
CRC/black/white/DR/noise header). It is the only path that preserves the full sensor word before
any ISP or grade touches it, so every downstream computational stage (MFSR, cinema-debayer,
ACEScg) is only as good as the bits this layer keeps.

## 2. How high-end cinema & IMAX cameras do this  ← THE REFERENCE BAR
Cinema RAW is defined by *preserving the sensor word at native bit-depth with reversible coding*:
- **ARRI ALEXA 35** records ARRIRAW at the full 13-stop-plus linear sensor reading; ARRI's
  premise is that the master must be a mathematically lossless representation of the photosite.
- **RED REDCODE RAW** uses wavelet compression at owner-chosen ratios (e.g. 8:1) that is visually
  lossless and fully decodable back to Bayer — never an 8-bit truncation.
- **Sony VENICE 2 X-OCN** (eXtended Original Camera Negative) is a 16-bit-class linear container;
  Blackmagic RAW and Canon Cinema RAW Light similarly keep the native word with light, reversible
  partial debayer.
- **DNG / SMPTE**: the spec carries `BlackLevel`, `WhiteLevel`, `LinearizationTable`, `NoiseProfile`,
  `ForwardMatrix1/2`, `CalibrationIlluminant1/2` so the file is self-describing and color-exact.

Engineering targets translated: (1) **no bit-depth loss** — a 14-bit sensor word is stored as 14
bits (or 16-bit container), never silently right-shifted to 12; (2) **reversible coding** — NPRAW
decode reconstructs the packed sample bit-exactly (CRC-verified) with zero quantization in the
lossless tier; (3) **complete, correct DNG tags** so a third party renders the same neutral as
our own pipeline (ΔE2000 of a gray patch < 1.0 vs reference render); (4) **no dropped frames** at
the chosen RAW frame-rate under sustained write load.

## 3. Current state in NeoProCamX (verified)
- DNG still path lives in `capture/StillRawCaptureProcessor.kt` (`DngCreator` from a `RAW_SENSOR`
  Image + `TotalCaptureResult`, cinema metadata, GPS) — verified by grep for `DngCreator`.
- NPRAW video stream + parallel-capture ring + bounded write queue + dedicated writer thread +
  backpressure/frame-drop live in `pipeline/RawVideoManager.kt` (also references
  `SENSOR_NOISE_PROFILE` for the per-frame header) — verified.
- `AsyncDirectIOEngine` streaming + MediaStore scoped storage and a software-RAW fallback package
  exist per the catalog (FEATURE_CATALOG.md §1).
- **The CP16 gap:** the roadmap flags a **14→12-bit truncation** in the RAW path with no
  highlight-preserving companding — i.e. on a 14-bit sensor we throw away 2 bits of the most
  valuable highlight headroom before serialization. (CP16, roadmap Phase 1.) This is the single
  load-bearing downgrade to fix; the surrounding machinery is solid (quality 8).

## 4. Elite target spec (the definition of "10000% best", made testable)
1. **No bit-depth loss:** for a sensor reporting 14-bit `SENSOR_INFO_WHITE_LEVEL`, the serialized
   sample retains ≥14 effective bits. A round-trip test (`pack(x) → unpack → x`) must be
   **bit-exact** for the lossless tier across the full 0..whiteLevel range.
2. **Highlight-preserving companding (when a reduced container is forced):** if a 12-bit container
   is unavoidable on a device, the 14→12 mapping is a documented perceptual/log companding curve
   (monotone, invertible LUT), not a right-shift — max highlight code-loss measured as < 0.5
   stop, verified by a golden curve test.
3. **DNG color exactness:** a synthetic neutral ramp written to DNG and re-read carries
   `BlackLevel`, `WhiteLevel`, `ForwardMatrix1/2`, `NoiseProfile`, `CalibrationIlluminant1/2`
   matching the capture metadata exactly (field-equality test).
4. **NPRAW integrity:** every frame's CRC validates on decode; a corrupt/short frame is detected,
   never silently rendered.
5. **No dropped frames:** at the advertised RAW video rate, the bounded queue + writer thread
   sustains write throughput; a soak test asserts drop-count == 0 within the queue budget, and
   honest backpressure (frame-drop accounting) when the budget is exceeded — never a silent loss.
6. **Budget:** pack/encode stays off the preview cold-start path; per-frame encode cost is bounded
   and reported, with backpressure rather than preview stall.
7. **No-downgrade default:** existing RAW/NPRAW output for currently-supported devices is
   unchanged unless the device actually exposes >12-bit data; the companding LUT defaults to the
   identity on devices already at native depth.

## 5. Implementation prompt (hand this to the agent)
**Scope:** eliminate the 14→12 truncation (CP16) and lock RAW serialization fidelity with goldens.
**Non-goals:** do not change the NPRAW container layout/version on disk (back-compat); do not add
a new codec library (no-vendor); do not touch the live YUV/grade path.

**Plan:**
1. **Golden-lock first.** Add a characterization test that pins today's NPRAW pack/unpack bytes for
   a fixed synthetic frame and today's DNG tag set — proving the default path is unchanged before
   any edit.
2. **Find the truncation.** Locate the 14→12 reduction in `RawVideoManager.kt` (and any packing
   helper). Replace the right-shift with: (a) native-depth pass-through when the container supports
   the sensor word, or (b) an invertible companding LUT when a 12-bit container is forced.
3. **Companding LUT.** Implement a monotone perceptual/log curve (pure Kotlin, unit-tested for
   monotonicity + round-trip error bound). Default = identity on native-depth devices (no-downgrade).
4. **Tighten DNG tags.** Ensure `ForwardMatrix1/2`, `CalibrationIlluminant1/2`, `NoiseProfile`,
   `BlackLevel`, `WhiteLevel`, `LinearizationTable` are populated from `TotalCaptureResult` /
   `HardwareAbstractionLayer` (s1-f03 feeds this).
5. **Device note.** The actual >12-bit data only appears on sensors that report it; mark the
   high-bit-depth path DEVICE-GATED with the exact on-device check (capture a 14-bit RAW, decode,
   compare top-2-bit content).

**Files:** `pipeline/RawVideoManager.kt`, `capture/StillRawCaptureProcessor.kt`, a new
`pipeline/RawCompandingCurve.kt` (LUT), the NPRAW header writer.

**ABI:** no GPU push-constant change. If the NPRAW header gains a "companding" flag, bump the
header version with backward-compatible decode of old frames and pin both in the golden.

**Gating:** companding/native-depth path defaults to identity/today's behavior; the >12-bit branch
activates only when the sensor reports it (device-gated).

## 6. Acceptance & verification
- **Unit/golden (JVM):** pack/unpack bit-exact round-trip; companding monotonicity + round-trip
  bound; DNG tag-equality test; NPRAW CRC-detect-corruption test; back-compat decode of a pinned
  old-version frame.
- **Native:** n/a (CPU/JVM path).
- **Device (vivo I2405):** capture a 14-bit RAW still + NPRAW clip; decode externally (Adobe DNG /
  our decoder) and confirm top-2-bit highlight content present, gray-patch ΔE2000 < 1.0, and a
  60-second RAW-video soak with drop-count == 0 within queue budget.
- **Gate keys:** if RAW contributes to a recorded master, its capability proves through
  `OmegaCapabilityGate` from real transport/bit-depth — never a synthesized pass.

## 7. Risks & no-downgrade guardrails
- **Silent bit loss:** golden pack/unpack round-trip makes any future right-shift trip CI.
- **Highlight clipping:** invertible companding LUT (monotone) bounds max code-loss; identity
  default means no change on devices already at native depth.
- **Container regression:** header-version bump with pinned old-frame decode = on-disk back-compat.
- **Frame drops / lag:** bounded queue + writer thread + honest backpressure; encode off the
  cold-start path.

## 8. References
- Roadmap: CP16 (and CP15 calibration dependency) in [`../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md`](../superpowers/plans/2026-06-25-beyond-imax-upgrade-program.md)
- Tech: [`../technology_explanations/markdown/09-neoradiance-raw-and-npraw-modes.md`](../technology_explanations/markdown/09-neoradiance-raw-and-npraw-modes.md), [`../technology_explanations/markdown/08-npv10-master-container.md`](../technology_explanations/markdown/08-npv10-master-container.md)
- Reference: Adobe DNG 1.6 spec; ARRIRAW / REDCODE / Sony X-OCN container philosophy.
- Source: `app/src/main/java/com/neoproc/neoproccamx/capture/StillRawCaptureProcessor.kt`, `app/src/main/java/com/neoproc/neoproccamx/pipeline/RawVideoManager.kt`
- Related prompts: s1-f03, s1-f05, s1-f06, s1-f07
