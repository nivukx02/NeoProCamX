# Original Video Preview And Delivery Design

## Goal

NeoProCamX video preview and recording must route formats honestly:

- Normal video and PRO SDR video use a normal SDR preview.
- HLG, HDR10, HDR10+, and Dolby Vision requests use HDR preview routing that matches the selected HDR family.
- LOG mode uses LOG delivery and preview state, not an HDR substitute.
- RAW mode uses RAW delivery and preview state, not a sharpened or tone-mapped fake.
- Recorded HDR, LOG, and RAW files keep their original signal intent whenever the device exposes the required camera, encoder, and storage capability.

The app must not fake HDR, LOG, RAW, Dolby Vision, ProRes RAW, BRAW, REDCODE, 10-bit, or sensor-native output when the hardware cannot produce it.

## Architecture

`VideoFormatDeliveryPolicy` is the source of truth for the active video signal. It resolves RAW, LOG, HDR, or normal PRO SDR from UI mode, cinema mode, HDR mode, color space, and bit-depth state.

`VideoFormatPreviewLookPolicy` turns that delivery state into preview flags. Normal/PRO SDR has no special look. RAW, LOG, and HDR set only their own preview family, so one format cannot accidentally inherit another format's tone path.

`CameraViewModel.buildVulkanPreviewGrade` uses the delivery state's truthfulness instead of treating every video mode as delivery-truthful. That keeps normal/PRO preview normal while preserving original RAW/LOG/HDR shader delivery.

`RecordingHdrVideoLookPolicy` normalizes HDR aliases and maps original HDR versions to Android encoder color metadata. HLG uses HLG transfer, HDR10/HDR10+/Dolby Vision use PQ transfer, and Dolby Vision remains labelled as an HDR10-compatible fallback unless real Dolby Vision encoding is available.

`VideoRecordingContractPolicy` preserves RAW compression choices. `CINEMA_DNG` maps to the real uncompressed DNG sequence path, and legacy proprietary names map to honest Neo RAW internal modes.

## Hardware Limits

Original HDR requires an HDR-capable camera stream and 10-bit-capable encoder. Original LOG requires wide-gamut/10-bit where available, with honest 8-bit fallback labels if hardware cannot provide Main10. Original RAW requires a RAW_SENSOR stream and enough storage throughput; otherwise the app must warn or fall back instead of fabricating RAW output.

## Testing

Unit tests cover:

- Normal/PRO SDR preview state.
- HLG, HDR10, HDR10+, Dolby Vision alias preservation.
- LOG suppressing HDR while keeping LOG delivery.
- RAW suppressing HDR/LOG while keeping RAW delivery.
- Window HDR mode only for real HDR modes.
- RAW settings exposing honest original RAW options.
- Encoder color metadata and bitrate planning preserving original HDR/LOG intent.
