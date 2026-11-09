# NeoGamut CAT16 Vulkan Color Pipeline Design

## Purpose

Implement a proprietary, GPU-executed color pipeline for NeoProCamX without
breaking the existing preview, recording, HDR, LUT, RAW, or NPV10 paths. The
feature adds NeoGamut-cg, CAT16 chromatic adaptation, hue-constant OkLCh
grading, and a mathematically continuous NLogs transfer while fixing the
device-observed Vulkan visible-preview surface conflict that currently blocks
runtime proof.

## Current Constraints

The current runtime is not a blank slate:

- `ycbcr_preview.frag` is a Vulkan `#version 450` shader receiving a processed
  RGBA16F intermediate image. It is not a two-plane GLES Y/UV shader.
- `vulkan_bridge.cpp` exposes an existing descriptor ABI with the intermediate
  sampler, preview uniform buffer, and LUT sampler already assigned to
  bindings 0, 1, and 2.
- Preview and encoder configurations already travel through
  `VulkanRenderConfig`, `VulkanBridge`, JNI, `RenderConfig`, and
  `PreviewUniforms`.
- The handset log reports a visible-preview producer collision:
  `BufferQueueProducer: connect: already connected`, followed by
  `vkCreateAndroidSurfaceKHR failed`. Camera2 and Vulkan cannot produce to the
  same visible surface at the same time.
- The worktree contains existing pipeline work. This change must extend those
  paths and must not erase unrelated edits or replace current mode routing.

## Selected Approach

Use an incremental ABI-safe integration:

1. Make visible preview surface ownership deterministic and prevent Vulkan
   binding until Camera2 has actually released the display surface.
2. Extend the existing Vulkan render configuration and uniform buffer with
   explicit color-transform payloads and dynamic HDR controls.
3. Insert NeoGamut/CAT16/OkLCh operations into the active RGBA16F shader path,
   retaining existing final transport, LUT, detail, dither, and mode branches.
4. Enable the same working-space operations for Vulkan encoder rendering so
   processed recording can be validated independently of visible-preview
   promotion.

A wholesale shader replacement is rejected because it would break the
currently compiled Vulkan bindings and discard active transport behavior.
CPU-only grading or LUT-only adaptation is rejected because it would not meet
pixel-by-pixel GPU execution or preview/recording parity requirements.

## Milestone 1: Surface Ownership And Runtime Proof

### Ownership Rule

At any instant, a visible `Surface` has one producer:

- In stable direct-preview mode, Camera2 owns the visible preview surface.
- In native-visible-preview mode, Camera2 has released the visible preview
  surface before Vulkan calls `nativeSetPreviewSurface`.
- The Vulkan encoder surface is separate from the visible preview surface and
  may be active while Camera2 continues direct visible preview.

### Handoff State

The existing handoff state in `CameraViewModel` remains the coordinator. Its
transition is tightened so a request to promote the visible preview:

1. disables Vulkan binding for the visible surface;
2. removes the visible surface from Camera2 output selection;
3. performs a forced session rebuild and waits for the previous session close
   callback;
4. binds Vulkan only after release confirmation and the existing settle
   interval;
5. restores Camera2 direct preview immediately if Vulkan binding or imported
   frame health validation fails.

No retry may bind Vulkan to a surface that
`cameraOwnsVisiblePreviewSurface(surface)` reports as Camera2-owned. A failed
handoff keeps the display on direct preview and does not present black frames.

### Recording Independence

Processed recording does not require visible native-preview ownership. When
recording selects the internal hardware-import feed and Vulkan is supported,
camera frames may be imported and rendered to the MediaCodec encoder surface
while Camera2 owns the display surface. Recording proof requires:

- frame import enabled;
- encoder surface bound;
- positive session imported-frame count;
- no direct Camera2 recording bypass reported by the active parity plan.

This separation allows device verification of GPU color-processed recording
even if visible Vulkan promotion is unavailable on a specific surface/device
combination.

## Milestone 2: Color-Science Data Model

### NeoGamut-cg Definition

NeoGamut-cg is a proprietary D55-referenced working gamut with these CIE 1931
coordinates:

| Component | x | y |
| --- | ---: | ---: |
| Red | 0.7347 | 0.2653 |
| Green | 0.1152 | 0.8260 |
| Blue | 0.0860 | 0.0110 |
| White D55 | 0.3324 | 0.3474 |

The forward matrix mapping NeoGamut-cg RGB to XYZ is:

```text
[ 0.697475  0.127814  0.125139 ]
[ 0.252003  0.918002  0.016001 ]
[ 0.000000  0.062402  1.341142 ]
```

The initially requested inverse does not round-trip the forward transform and
would introduce color error. The implementation uses the numerically derived
inverse:

```text
[ 1.506115607 -0.200307072 -0.138142484 ]
[-0.413783104  1.145237837  0.024945497 ]
[ 0.019252915 -0.053286775  0.744472510 ]
```

Unit tests require `XYZ_TO_NEO * NEO_TO_XYZ` and the reverse product to equal
identity within an implementation-defined single-precision tolerance no
looser than `1e-5`.

### CAT16 Chromatic Adaptation

`ColorScience.kt` exposes stable immutable NeoGamut matrices and calculates a
CAT16 adaptation matrix from a source white point to NeoGamut D55. The CAT16
matrices are:

```text
M16 =
[ 0.4012  0.6501 -0.0514 ]
[-0.2503  1.2017  0.0485 ]
[-0.0021  0.0754  1.1712 ]

M16_INV =
[ 1.86042218 -1.01421605  0.12364684 ]
[ 0.38837858  0.62259623 -0.00873741 ]
[-0.02166740 -0.04190028  0.85460934 ]
```

The equations above are row-major mathematical matrices. GLSL `mat3`
constructors must supply columns or receive packed columns from the host as
required by Vulkan/std140 layout. Tests validate transformed values rather
than textual literal ordering. Given source and target whites in XYZ, the
adaptation is:

```text
sourceResponse = M16 * sourceWhite
targetResponse = M16 * targetWhite
scale = targetResponse / sourceResponse
adaptation = M16_INV * diagonal(scale) * M16
```

All denominator components are bounded away from zero and input chromaticities
are validated as finite, positive-`y` coordinates. A D55-to-D55 request
returns identity within float tolerance.

### OkLCh Reference Path

Oklab is D65-referenced and must not be applied directly to D55 NeoGamut
values. The pixel pipeline therefore uses explicit reference conversion:

```text
NeoGamut RGB D55
  -> XYZ D55
  -> CAT16 D55-to-D65
  -> linear D65 reference RGB
  -> Oklab / OkLCh grading
  -> linear D65 reference RGB
  -> CAT16 D65-to-target-white XYZ
  -> target RGB / transport output
```

The host packs composite matrices where practical, reducing per-pixel matrix
work while retaining the defined color transformation. Saturation changes
only OkLCh chroma; exposure, contrast, HDR knees, and shoulders change only
OkLCh lightness; highlight gamut protection scales chroma without rotating hue.

## NLogs Transfer

The current application already exposes four N-Log variants and calibrates all
of them to a shared 18% gray code value of `0.50`. That grading contract is
preserved. NLogs replaces each variant's linear toe with a
derivative-matched quadratic toe while retaining its calibrated logarithmic
body. For a profile with cut `c`, declared toe value `h`, density `d`, and
gray anchor `g = 0.50`:

```text
h = c * declaredToeSlope
s = (g - h) / ln(1 + d*(0.18 - c))
body(x) = h + s*ln(1 + d*(x - c))
m = body'(c) = s*d
a = (m*c - h) / (c*c)
b = (2*h - m*c) / c

NLogs(x) =
    a*x*x + b*x,                         0 <= x < c
    h + s*ln(1 + d*(x - c)),             x >= c
```

The inverse uses the calibrated logarithmic inverse above `h` and the
monotonic quadratic root below it:

```text
inverseBody(v) = c + (exp((v - h)/s) - 1) / d
inverseToe(v)  = (-b + sqrt(b*b + 4*a*v)) / (2*a)
```

When `a` is negative, the selected root is the one in `[0, c]`. Tests prove
value continuity, derivative continuity, monotonicity, the retained `0.50`
gray anchor for all variants, and encode/decode round-trips across black,
toe, middle-gray, and highlight samples.

## GPU And JNI Contract

### Kotlin Host Payload

`VulkanRenderConfig` gains fixed-size, finite-validated color payloads for
preview and encoder rendering. Each payload supplies:

- input-to-OkLCh-reference composite matrix;
- OkLCh-reference-to-output composite matrix;
- HDR knee, shoulder scale, and NIVUKX chroma-volume amount;
- working color-science mode flag and NLogs enable flag.

Grade settings already present in `previewGrade` and `encoderGrade` remain in
place for existing UI and mode compatibility.

### Native Uniform Transport

`VulkanBridge.updateRenderConfig`, its JNI signature,
`vulkan_bridge.cpp::RenderConfig`, and `PreviewUniforms` are extended together
with fixed-size arrays. The Vulkan descriptor binding numbers remain
unchanged; only the size and content of the uniform buffer at binding 1 is
extended. Uniform allocation and copy lengths use `sizeof(PreviewUniforms)` so
the C++ and shader blocks change atomically.

### Shader Insertion Point

`ycbcr_preview.frag` retains:

- binding 0 RGBA16F compute intermediate;
- binding 1 uniform block;
- binding 2 preview LUT;
- existing detail/noise refinement, HDR transport, LUT, dither, and RAW/LOG
  mode branches.

The new transform/OkLCh operation is inserted after the high-precision linear
intermediate is available and before transport-specific output encoding.
Preview and encoder paths receive the same working-space transforms; their
output composite matrix and transport mode may differ intentionally for SDR
monitor display versus NLogs or HDR recording delivery.

## Profile And Compatibility Policy

Active selectable color-science profiles are limited to standards-based
transport spaces and Neo proprietary rendering modes:

- Natural / Rec.709 display delivery;
- Rec.2020 and PQ/HLG transport where the platform transport requires them;
- NeoGamut-cg;
- N-LOG / NLogs;
- NDR10+;
- NIVUKX Vision;
- linear working-space paths needed internally for processing.

Legacy external profile identifiers may remain solely as non-selectable import
or migration aliases and must resolve to a Neo profile. They do not activate a
branded vendor look, appear as selectable UI profiles, or alter output
metadata to claim a vendor technology.

## Error Handling And Degradation

- Invalid CAT16 source white data resolves to a neutral identity adaptation and
  logs a diagnostic; it never introduces NaN or infinity into a render uniform.
- Unsupported Vulkan or failed encoder surface binding retains the existing
  stable Camera2 recording fallback and reports that custom GPU processing was
  not validated.
- Visible Vulkan bind failure restores Camera2 display ownership; it is a
  runtime capability failure, not permission to show a black preview.
- RAW/NPV10 modes preserve their sensor or container precision contracts;
  viewfinder tone mapping does not redefine stored RAW sample values.

## Test And Device Verification

### Unit And Contract Tests

Add or update focused tests for:

- NeoGamut matrix round-trip precision;
- CAT16 identity and finite dynamic-white adaptation;
- NLogs continuity, derivative continuity, monotonicity, and inverse
  round-trip;
- profile registry and migration-alias policy;
- fixed Vulkan render-config payload sizes and finite validation;
- shader contract presence for CAT16/OkLCh processing in the active branch;
- surface ownership policy: Vulkan visible binding is prohibited while
  Camera2 owns that surface;
- processed recording validation via encoder surface and imported-frame
  telemetry.

### Shader And Build Verification

- Recompile the updated Vulkan fragment shader and regenerate the embedded
  SPIR-V header using the existing build/generator convention.
- Run `.\gradlew.bat :app:testDebugUnitTest --no-configuration-cache`.
- Run `.\gradlew.bat :app:assembleDebug --no-configuration-cache`.

### Connected Device Verification

Install the debug APK on the connected Android handset and run:

1. startup and mode transitions with logcat monitoring, requiring no
   `BufferQueueProducer: connect: already connected` or
   `vkCreateAndroidSurfaceKHR failed` messages;
2. processed recording in a Neo mode, requiring a bound encoder surface and
   positive imported-frame telemetry before claiming GPU processing;
3. direct preview fallback exercise, requiring live preview to remain visible
   if visible Vulkan promotion is rejected;
4. recorded-file metadata and frame inspection appropriate to the selected
   transport mode.

No claim of noise elimination, perfect hue preservation, or bit-depth output
is made solely from source changes; those claims require recorded media and
device/runtime evidence.

## Implementation Boundary

This design intentionally does not:

- replace the entire current shader with a GLES sample shader;
- discard existing LUT, sharpening, denoise, HDR, RAW, or NPV10 logic;
- claim that a monitor preview is identical to encoded log/HDR transport when
  their output transforms are deliberately different;
- expose third-party branded looks as active user-selectable output profiles.

The implementation is complete when the ownership fix prevents the observed
black-preview surface collision, the corrected NeoGamut/CAT16/OkLCh/NLogs
path compiles through the existing Vulkan rendering graph, and tests plus
device evidence distinguish validated GPU output from fallback operation.
