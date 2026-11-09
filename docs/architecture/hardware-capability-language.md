# Hardware Capability Language

NeoProCamX can request high-end camera, encoder, Vulkan, RAW, HDR, and processing paths. Android devices decide which paths are exposed and stable for a specific session.

Use these words for implementation comments, diagnostics, and docs:

- `requested` when the app asks Camera2, MediaCodec, Vulkan, or native code for a capability.
- `device-reported` when the capability comes from Camera2 characteristics, MediaCodec capabilities, Vulkan support, or runtime probes.
- `verified for this session` when the current session actually configured and ran with the capability.
- `fallback applied` when the app continues through a lower capability path.
- `best-effort` when the app improves quality but cannot prove full hardware behavior.

Avoid absolute claims unless a runtime check proves them for the active device/session:

- `guaranteed`
- `complete ISP bypass`
- `bit-perfect`
- `true zero-copy`
- `cinema-grade`
- `hardware-native`
- `lossless`

Product-facing language may remain ambitious. Engineering comments and diagnostics must remain factual.
