# NIV99 ownership and integration architecture

NIV99 is an additive, generation-scoped image-analysis and rendering subsystem. It does not own RAW
recording bytes, Camera2 session lifetime, codec lifetime, crop/orientation, or user controls.

## Authority order

1. Explicit manual Kelvin/tint and manual Camera2 transform
2. Explicit AWB lock or recording shot lock
3. Existing validated Camera2/HAL request policy
4. NIV99 automatic global estimate
5. Truthfully reported unsupported state

The rendering-only local illuminant field never becomes a Camera2 request. Global gains and the matching
color matrix are published atomically with camera ID, session generation, frame number, and sensor timestamp.

## Data ownership

- Caller owns sensor planes and metadata passed to `Niv99Engine`.
- `Niv99Engine` owns fixed-capacity workspaces and history rings.
- Engine hot-path APIs copy into caller-provided output objects.
- RAW writers remain byte-exact and are never passed a writable NIV99 view.
- Conversion coordinators create new destinations and never mutate or delete source recordings.

## Reset boundaries

Camera ID, session generation, mode, dimensions, FPS, color profile, surface generation, codec generation,
timestamp discontinuity, scene cut, and flash transitions invalidate temporal history before the next fusion.

## Planned integration order

CPU reference and quality oracle → unit/property tests → Camera2 adapter → rendering/recording adapters →
Vulkan shaders and native dispatch → shader parity → conversions → host gates → device evidence.

