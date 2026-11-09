# NDR10+ and NIVUKX Vision Original Profile Upgrade Design

Date: 2026-05-21

## Goal

Upgrade NDR10+ and NIVUKX Vision as first-class NeoProCamX technologies, not aliases for HDR10+ or Dolby Vision. The app should present, process, and report these profiles as original Neo profiles while keeping Android codec and display transport metadata truthful and compatible.

## Non-Negotiable Truth Rules

- NDR10+ is not HDR10+.
- NIVUKX Vision is not Dolby Vision.
- Android transport identifiers such as `HDR10_PLUS`, `ST2084/PQ`, `BT.2020`, and `HEVC Main10` remain internal transport metadata only.
- Metadata must describe NDR10+ and NIVUKX Vision as NeoProCamX profiles using Android PQ/BT.2020/Main10 transport when that transport is active.
- The app must not claim official Dolby Vision output without certified Dolby encoding and Dolby metadata.
- The app must not claim true HDR10+ dynamic metadata unless inline HDR10+ dynamic metadata is actually written.

## User-Facing Behavior

Settings and mode UI expose only NeoProCamX profile names for the custom HDR family:

- `OFF`
- `NDR10+`
- `NIVUKX Vision`

Old saved preference strings may still be parsed internally for compatibility, but they must be normalized into Neo profile behavior where appropriate and must not appear as the current product promise.

## Architecture

The profile stack is separated into two layers:

1. Neo profile layer
   - Owns tone mapping, color volume handling, highlight compression, shadow compression, saturation protection, noise/sharpen policy, dynamic sidecar metadata, and quality budgets.
   - Uses names `NDR10+` and `NIVUKX Vision`.

2. Android transport layer
   - Owns codec/display compatibility.
   - Uses truthful Android metadata: BT.2020 primaries, ST2084/PQ transfer, HEVC Main10, and Camera2 `HDR10_PLUS` dynamic range profile when that is the best supported 10-bit path.
   - This layer is not the product name.

## Profile Intent

NDR10+ targets a clean high-dynamic-range look with strong highlight rolloff, controlled shadow noise, natural saturation, and stable preview/recording parity.

NIVUKX Vision is the highest-end NeoProCamX look. It should use stronger internal math, more careful color-volume protection, cleaner shadow compression, higher local contrast control, stronger highlight reconstruction, and stricter metadata reporting than NDR10+.

## Data Flow

Sensor/ISP input flows through the existing camera pipeline, then:

1. Preview and recording resolve the same selected Neo profile.
2. Neo profile math applies tone/color/noise/sharpness intent.
3. Encoder receives valid Android-compatible 10-bit HDR transport settings when supported.
4. Metadata records both:
   - selected Neo profile: `NDR10+` or `NIVUKX Vision`
   - active transport: Android PQ/BT.2020/Main10 or fallback

## Error Handling And Fallbacks

- If 10-bit HDR transport is unavailable, the app must fall back truthfully and report the active transport as SDR or available fallback.
- If dynamic metadata cannot be injected into the encoded stream, write a Neo dynamic sidecar and set `dynamicMetadataWritten=false` for inline metadata.
- Preview and recording must remain matched for selected profile, transfer intent, bit depth decision, and fallback reporting.

## Testing

Add or update tests to prove:

- NDR10+ is not labeled as HDR10+.
- NIVUKX Vision is not labeled as Dolby Vision.
- Metadata reports Neo profile names and Android transport separately.
- NDR10+ and NIVUKX Vision resolve to truthful 10-bit PQ/BT.2020 transport when available.
- NIVUKX Vision has stronger quality policy than NDR10+ without pretending to be Dolby Vision.
- Old internal transport code names remain available where Android requires them.

## Out Of Scope

- Certified Dolby Vision output.
- Proprietary Dolby or HDR10+ formula copying.
- Claims of native 12-bit encoded output unless a path truly writes 12-bit data.
- Breaking Android transport names in Camera2, MediaCodec, or MediaFormat code.
