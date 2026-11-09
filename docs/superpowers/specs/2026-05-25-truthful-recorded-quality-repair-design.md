# Truthful Recorded Quality Repair Design

Date: 2026-05-25
Project: NeoProCamX

## Goal

Repair the diagnosed causes of low-detail, noisy, over-smoothed video in every supported video mode and in both encoded recording and NPV10 workflows. The upgrade must make the highest-quality processing path reach recorded output when the device supports it, remain truthful when it cannot, and produce evidence sufficient for meaningful comparison with premium phone and cinema-camera footage.

This design does not promise physically zero sensor noise, real 8K detail from a non-8K stream, or universal superiority to an iPhone or cinema camera. Those claims require controlled captures and measured results from the active device. The product target is the strongest truthful output the exposed hardware, throughput, and thermal budget permit.

## Confirmed Root Causes

Stored device logs and source inspection establish the following causes:

- The tested device exposes a maximum safe public recording/YUV/RAW stream of `4096x3072`, although its pixel array is `8192x6144`. The app cannot claim true 8K output on that device.
- The recorded Camera2 fallback path logs `bypass=true` and lists `SHARPNESS`, `AI_ENHANCEMENT`, `LUT`, and `RAW_DEBAYER` as unsupported; quality tuning that never reaches encoded frames cannot correct the recording.
- The visible preview during affected high-resolution recordings is `1280x720` or `1024x768`, so it cannot visually demonstrate full recorded detail.
- The active Vulkan presentation shader applies very aggressive smoothing and then adds temporal grain, which conflicts with a clean-output requirement.
- Current processing decisions use fixed `3840x2160` inputs in paths that should be driven by the active preview, capture, and export dimensions.
- Current parity and quality evidence can pass based on declared state even when custom recorded-frame processing is bypassed.

## Quality Contract

### Supported Resolution Truth

- Keep `8K` as an option only when the selected camera, output stream configuration, codec, FPS, bit depth, and active session can actually sustain an 8K encoded or master path.
- If a device does not expose a valid 8K route, the UI must not present that route as recordable output. Existing migrated selections may be downgraded with an explicit message and certificate evidence.
- Every clip certificate records requested resolution, selected resolution, actual stream resolution, encoder resolution, and any downgrade reason.
- No upscaled or duplicated output may be labelled as native higher-resolution capture.

### Encoded Video Modes

The following modes use one shared scene-processing intent and truthful output metadata:

- Normal Video: SDR delivery only, clean natural detail, no HDR tagging.
- Pro Video: high-quality SDR delivery unless the user explicitly selects an HDR profile.
- N-Log family: log delivery with the selected N-Log curve, not HDR-labelled output.
- NDR10+: NeoProCamX profile delivered through a standards-compatible supported HDR transport only when active.
- NIVUKX Vision: NeoProCamX profile delivered through a standards-compatible supported HDR transport only when active.

For each mode, the app must distinguish:

- `PROCESSED_RECORDING`: custom quality stages executed on frames delivered to the encoder.
- `ISP_ENCODED_FALLBACK`: camera/ISP frames encoded without custom recorded-frame detail processing.
- `UNSUPPORTED`: the requested transport, bit depth, resolution, or session graph cannot be created.

`ISP_ENCODED_FALLBACK` may remain usable, but it must never be certified as custom denoise/sharpness parity.

### RAW And NPV10

- RAW_SENSOR and native NPV10 masters remain source-truth recordings. They are not denoised or sharpened in-place while being represented as RAW or private masters.
- NPV10 must distinguish native `P010_LE` or truthful sensor/native source from `YUV420_8BIT_EXPANDED`.
- Preview and manual MOV export may apply the same selected look and bounded enhancement contract while preserving the private master unchanged.
- NPV10 export certificates state source format, source bit depth, storage bit depth, output transport, active enhancement path, and fallback reasons.

## Architecture

### 1. Capability-Resolved Capture Plan

Create one resolved recording capability object before session construction. It carries:

- selected mode and color intent
- requested and actual dimensions
- FPS and codec capability
- bit depth and HDR/log transport support
- live hardware-buffer import and encoder-surface processing capability
- NPV10/native P010 support
- reasoned fallback or rejection state

The UI, recording session, metadata writer, and quality certificate must consume the same resolved plan. This prevents display labels, metadata, and actual streams from diverging.

### 2. Recorded-Frame Processing Route

For encoded modes, the preferred route is:

`Camera frame -> importable buffer -> temporal/chroma cleanup -> texture-safe detail -> selected color transform -> encoder surface`

The preferred route is enabled only when real device evidence proves:

- camera output can feed an importable format;
- Vulkan processing and encoder output surface are functioning;
- frame timing and drops remain within the recording budget;
- output metadata remains correct for the selected mode.

If any condition fails, route to `ISP_ENCODED_FALLBACK`, log why, and issue a certificate that explicitly denies custom recorded-frame sharpening/denoise execution.

### 3. Clean Detail Pipeline

Clean output is the default. Synthetic moving grain is disabled unless the user later selects a separate creative film-texture option.

The bounded processing order is:

1. Source-range and color-format validation.
2. Motion-aware temporal luma/chroma denoise when genuine previous frames are available.
3. Shadow chroma suppression with skin and color-edge guards.
4. Edge confidence and texture confidence estimation.
5. Edge-aware sharpening only on supported luma detail.
6. Halo, moire, aliasing, and oversmoothing guards.
7. Selected SDR, N-Log, NDR10+, or NIVUKX color transform.
8. Preview or encoder delivery with identical selected look parameters.

When previous-frame history is unavailable, the app must identify denoise as spatial/ISP rather than temporal.

### 4. Dimension-Correct Tuning

Remove fixed 4K assumptions from live quality requests. Quality policy inputs must include:

- actual source/capture dimensions;
- actual encoder dimensions;
- actual preview dimensions;
- whether a preview is only a lower-resolution visualization;
- native-versus-requested resolution relationship.

Preview can remain lower resolution for throughput, but the UI and certificate must not imply that preview inspection proves encoded spatial detail.

### 5. Parity And Evidence Gate

Preview/recording parity passes only when the actual recorded-frame processing route executes the same selected color, tone, denoise, and detail intent. A Camera2 bypass cannot pass custom processing parity simply because settings signatures match.

Every recording/export quality certificate records:

- mode, requested profile, transport, codec, bit depth, color tags, resolution, FPS, and bitrate;
- processed route or ISP fallback;
- GPU/import/encoder surface proof;
- denoise kind: temporal, spatial, ISP, or none;
- sharpening kind and whether it ran on encoded frames;
- synthetic grain status;
- dropped frames, timing variance, thermal downgrade, and fallback reason;
- NPV10 source truth and export processing truth when applicable.

## Mode Coverage

The repair applies to:

- Normal Video
- Pro Video
- N-Log Base, Shadow, Highlight, and Cinema
- NDR10+
- NIVUKX Vision
- supported RAW preview/export behavior
- NPV10 private master capture and manual MOV export

For every mode and resolution, unsupported hardware features must be disabled or reported as fallback rather than simulated.

## Implementation Slices

Because the existing branch already contains extensive camera, Vulkan, and NPV10 work, implementation proceeds in reviewable slices:

1. Capture truth and certificate correction: supported-only 8K, actual dimensions, and bypass cannot report custom-processing parity.
2. Runtime quality dimensions and clean default: remove live fixed-4K assumptions and disable injected moving grain from clean video output.
3. Processed recording activation: make the hardware-import encoder path the preferred route only when runtime proof succeeds, with safe ISP fallback.
4. Real temporal/detail processing: require historical-frame binding for temporal claims and apply guarded edge detail after denoise.
5. NPV10 export alignment: reuse the bounded enhancement contract on export only, with source master and metadata truth preserved.
6. Device benchmarking: record every mode on each available resolution tier and produce evidence reports.

Each slice must use tests first and may ship only when it preserves existing working features.

## Comparative Quality Benchmark

“Better than iPhone video” and “cinema-camera quality” are evaluation goals, not labels the app may assert by implementation alone.

A comparative report requires:

- the same controlled scene, framing, lighting, motion, and exposure targets;
- NeoProCamX device footage, a named iPhone reference device and mode, and a named cinema-camera reference if available;
- native files, not social-media recompressions;
- metadata validation using `ffprobe` or MediaInfo;
- objective measurements where possible: chroma/luma temporal noise, detail retention or edge MTF proxy, highlight clipping rate, color error on a chart, frame drops, bitrate, and SSIM/VMAF only when a valid reference capture exists;
- subjective review of skin texture, color stability, edge halos, motion smearing, low-light artifacts, and highlight rolloff.

NeoProCamX may be rated above a reference only for the measured scenes and modes where evidence supports that result.

## Testing And Verification

### Unit And Contract Tests

- 8K appears only when stream and encoder/session capability expose an actual 8K route.
- Unsupported 8K resolution resolves to the actual maximum with a truthful downgrade record.
- Camera2/ISP fallback cannot pass custom recorded-frame parity.
- Active dimensions reach detail and denoise policy calculations.
- Clean default has no synthetic moving grain stages.
- Temporal denoise is claimable only with actual history-frame input.
- Normal/Pro remain SDR; N-Log is not HDR-tagged; NDR10+/NIVUKX transport claims match actual capability.
- NPV10 metadata distinguishes native P010 from expanded 8-bit fallback and private masters remain unchanged by export enhancement.

### Build And Real-Device Gates

- Run unit tests and build with warnings treated as failures.
- Install and launch on the connected test device.
- Record short clips in every video mode at each exposed resolution tier and relevant FPS/bit-depth combination.
- Capture logs proving active route, actual dimensions, enhancement execution, fallback reason, and frame statistics.
- Pull files and validate container, codec, resolution, color metadata, and bit depth with `ffprobe` or MediaInfo.
- Compare preview screenshots with recorded output for selected-look parity, without treating the lower-resolution preview as encoded-detail evidence.
- Generate a benchmark report before claiming measured premium quality.

## Acceptance Criteria

- The app never labels downgraded resolution or fallback bit depth as native high-resolution/high-bit-depth capture.
- On capable hardware, supported encoded modes can deliver recorded frames through the active bounded quality pipeline.
- On incapable hardware, fallback is stable, explicitly reported, and not presented as custom processed cinema output.
- Clean default recordings contain no deliberately injected moving grain.
- Sharpness is detail-aware and noise-bounded; noise reduction does not intentionally erase texture or claim physically zero noise.
- Preview and recording share selected look intent, and certificates distinguish visual-intent parity from actual processed-recording parity.
- NPV10 masters remain private and truthful; enhanced MOV exports state exactly what was processed and delivered.
- Production and comparative quality claims are made only from passing device/file evidence.
