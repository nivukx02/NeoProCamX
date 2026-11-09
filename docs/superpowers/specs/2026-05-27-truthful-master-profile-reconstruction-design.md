# Truthful Master Profile Reconstruction Design

**Date:** 2026-05-27  
**Scope:** N-Log, NDR10+, NIVUKX Vision, and their shared Vulkan processed-recording delivery path.

## Objective

Build one measurable master-profile contract for NeoProCamX custom video profiles. Preview, processed recording, export metadata, and NPV10-derived publication must describe and apply the same selected profile behavior without inventing sensor precision, dynamic range, or quality claims.

The upgrade targets visible reduction of avoidable chroma instability, consistent tone/color delivery, and restrained high-detail output. It does not promise physically impossible zero sensor noise, infinite recovered detail, native 16-bit capture without hardware evidence, or superiority over another commercial pipeline without controlled measurements.

## Preserved Baseline

The following behavior already exists and remains unchanged unless tests expose a defect:

- Processed `VIDEO`, `PRO`, `NDR`, and `LOG` recording selects Vulkan internal hardware-import encoding when the runtime path is supported and validated.
- RAW and NPV10 master storage continue to preserve their truthful source/payload contracts; processed looks apply only to previews or derived exports where permitted.
- Resolution-aware encoded bitrate ceilings remain bounded by MediaCodec capabilities: ordinary 1080p/4K/8K tiers of 30/80/150 Mbps and cinema-master tiers of 45/100/180 Mbps.
- NPV10 MOV export already performs deep-color dither where required by NPV10, NDR10+, NIVUKX Vision, and N-Log-derived output.
- Existing focus-time recording color stability and manually selected color science/color-space behavior must not regress.

## Defects Being Corrected

### N-Log Contract Divergence

N-Log behavior currently exists in three independent implementations: application math, core metadata/reference math, and GLSL encoding. Independent curve implementations cannot prove that visible preview, encoded pixels, and metadata describe the same signal.

The reconstruction defines one versioned N-Log family contract and uses that definition for CPU reference calculations, shader transfer behavior, selected-profile metadata, and parity tests.

### Custom HDR Profile Delivery Separation

NDR10+ and NIVUKX Vision are NeoProCamX selected profiles carried over standards-based Android HDR transport when supported. Their reference policy exists, but the live GLSL delivery path must distinctly resolve their tone-map intent rather than treating every PQ/HLG delivery as the same custom look.

### Non-Neutral Master Effects

Synthetic halation, spectral simulation, or decorative contrast behavior cannot silently modify a neutral master recording profile. Such operations either become explicitly selected creative-look behavior or remain disabled in the truthful N-Log, NDR10+, and NIVUKX master delivery path.

## Profile Architecture

### Selection Layer

The selected user profile remains a Neo profile identity:

- `N-Log Base`, `N-Log Shadow`, `N-Log Highlight`, or `N-Log Cinema`
- `NDR10+`
- `NIVUKX Vision`

This layer controls profile math, UI selection, metadata identity, and preview/recording parity.

### Transport Layer

The encoded transport remains an Android and media-standard truth statement:

- SDR/Log output is not tagged as HDR.
- NDR10+ and NIVUKX Vision may use BT.2020 primaries plus ST2084/PQ or HLG transfer and HEVC Main10 only when that active codec/camera path supports it.
- Transport metadata does not rename a Neo profile into HDR10+ or Dolby Vision.
- Unsupported 10-bit or HDR transport must fall back explicitly and report that fallback.

### Math Layer

The shared math layer has CPU reference functions and shader-equivalent operations. Tests compare representative input/output vectors so a profile cannot silently drift between preview, recording, export, and metadata-generation paths.

## N-Log Design

N-Log is a Neo-owned, reversible scene-linear curve family. The implementation will select one existing compatible curve basis and version it as the authoritative contract instead of adding a fourth formula.

Requirements:

- Monotonic encode and decode behavior for all four N-Log variants.
- Stable 18 percent gray placement and documented highlight allocation.
- Shadow behavior must allocate usable code values without presenting lifted sensor noise as recovered detail.
- Preview and Vulkan processed recording use the same variant and coefficients.
- Metadata reports the exact selected N-Log variant and contract version.
- Transform into any working color space occurs only through an explicitly selected, valid color-science path; a curve must not silently impose ACES/CAT conversion.

## NDR10+ Design

NDR10+ is a NeoProCamX HDR rendering profile, not official HDR10+ output. It uses standards-compatible transport when supported and its own restrained color-volume/tone-map behavior.

The active Vulkan delivery pass will apply an NDR-specific perceptual tone mapping operator:

- Convert working linear RGB into an Oklab/OkLCh representation using a validated matrix for the selected working space.
- Compress lightness with a monotonic shoulder driven by scene/profile controls.
- Preserve hue as closely as legal output gamut allows.
- Apply bounded chroma compression when preserving chroma would create out-of-gamut RGB output.
- Include red-highlight and cyan-shadow guards without shifting neutral regions.

NDR10+ metadata reports selected Neo profile separately from active BT.2020/PQ/HLG/Main10 transport.

## NIVUKX Vision Design

NIVUKX Vision is the higher-strength Neo HDR profile. It uses the same truthful transport rules as NDR10+, with stronger but bounded internal handling:

- More protective highlight shoulder and color-volume compression for high-energy highlights.
- Stronger shadow chroma stabilization under noise risk.
- Local contrast retention that remains bounded against halos and clipped skin highlights.
- Explicit profile identification and internal-versus-encoded precision metadata.

NIVUKX Vision is not Dolby Vision and must not emit Dolby branding, RPU, enhancement-layer claims, or unsupported certification claims.

## Noise And Detail Contract

The master-profile pipeline keeps denoise before detail enhancement:

1. Apply sensor/ISO-aware or conservative generic noise estimation.
2. Suppress unstable chroma in shadows and low-confidence flat regions.
3. Use temporal history only where motion confidence allows it.
4. Apply edge-confidence detail enhancement with overshoot and halo limits.
5. Quantize/dither only at the required encoded/export boundary.

Arbitrary reductions to the modeled noise floor are not accepted as quality improvements without measurements. Unbounded global sharpening is excluded because it amplifies noise and creates halos. Existing bounded 9-tap detail handling remains the implementation base.

## Data Flow

For processed profiles:

```text
Camera2/ISP source
  -> user color-science and color-space selection
  -> noise and motion-confidence processing
  -> selected Neo profile math
  -> bounded detail pass
  -> SDR, N-Log, or HDR transport encoding
  -> MediaCodec output and truth metadata
```

For NPV10:

```text
Camera source
  -> private truthful NPV10 master
  -> user-triggered derived export only
  -> selected profile/export enhancement and dither
  -> MOV output with matching transport metadata
```

The private NPV10 master is not rewritten by look processing.

## Implementation Boundaries

The implementation will touch only the components required to close the proven gaps:

- N-Log CPU/core reference and shader contract.
- Shader tone-delivery selection and NDR/NIVUKX perceptual mapping.
- Metadata/profile parity points required to report the selected curve and transport.
- Tests proving routing, curve parity, transport truth, and neutral master-delivery constraints.

It will not duplicate already present GPU feed routing, bitrate caps, NPV10 dither, private master behavior, or unrelated UI/AI work.

## Failure And Fallback Behavior

- If Vulkan internal hardware-import recording is unavailable, the app must not report that recorded pixels used the custom GPU delivery pass.
- If 10-bit HDR transport is unavailable, NDR10+/NIVUKX output must identify the actual fallback rather than claiming Main10/PQ.
- If sensor-derived calibration values are unavailable, processing uses bounded generic parameters and reports that no device calibration evidence is available.
- If metadata and active transport disagree, quality evidence must fail rather than certify the recording.

## Verification

### Automated Tests

Implementation begins with failing tests for:

- N-Log variant monotonicity, round-trip behavior, gray-card targets, and CPU/shader contract vectors.
- No independent incompatible N-Log GLSL formula on the live output path.
- NDR10+ and NIVUKX Vision resolve distinct active tone-map branches and truthful Android transport metadata.
- Perceptual mapping remains in gamut, bounds hue error, preserves neutrals, and avoids red-highlight/cyan-shadow regressions.
- Truthful master delivery excludes synthetic halation/spectral effects unless an explicit creative-look selection enables them.
- Existing GPU-record routing, NPV10 derived-export dither, bitrate, RAW/NPV10 truth, and focus-time color stability tests remain passing.

### Build Gates

Run unit tests, lint, and debug assembly without warning/error acceptance exceptions:

```powershell
.\gradlew.bat :app:testDebugUnitTest --no-daemon --no-configuration-cache --console=plain
.\gradlew.bat :app:lintDebug :app:assembleDebug --no-daemon --no-configuration-cache --console=plain
```

### Device Evidence

When an Android device is connected through ADB:

1. Install and launch the debug APK.
2. Record N-Log, NDR10+, and NIVUKX Vision clips in controlled scenes.
3. Capture logs proving actual recording path and profile/transport selection.
4. Inspect files with `ffprobe` or MediaInfo for codec, resolution, bit depth, primaries, and transfer metadata.
5. Compare fixed-scene noise, hue stability, clipping, edge overshoot, and preview/recording appearance.

Device evidence determines measured quality claims. It does not convert a profile name into a guarantee.

## Non-Goals

- Claiming zero physical noise, infinite detail, guaranteed cinema-camera equivalence, or universal superiority over another product.
- Copying proprietary vendor curve or metadata behavior.
- Claiming native 16-bit, 12-bit, 10-bit, RAW, or HDR output without an active path and validated metadata supporting that claim.
- Replacing already tested routing, bitrate, storage, or export behavior solely to match wording in a prompt.
