# Single-Slot Rotary Video Mode Selector Design

## Scope

This change addresses two camera UI controls:

1. Replace the exposed five-label video mode row with a single visible active mode above the existing shutter button.
2. Reduce recording resolution choices to four named tiers only: `1K`, `2K`, `4K`, and `8K`.

No change is made to the shutter's position, preview placement, recording pipeline implementation, or the underlying mode definitions.

## Mode Interaction

The fixed shutter remains centered in its existing capture controls row. A single mode slot remains centered directly above it.

The slot displays only the active mode label:

`VIDEO`, `PRO`, `NDR`, `LOG`, or `RAW`.

Inactive labels are not visible. The mode selection changes only when the user completes a horizontal swipe gesture across the mode selector area:

- Swipe left advances: `VIDEO -> PRO -> NDR -> LOG -> RAW -> VIDEO`.
- Swipe right reverses: `VIDEO -> RAW -> LOG -> NDR -> PRO -> VIDEO`.
- The cycle wraps indefinitely in both directions.

When a valid swipe completes, the outgoing active label animates horizontally away and the incoming active label rotates into the same centered slot. The shutter and its neighboring action buttons do not move.

A short swipe that does not exceed the gesture threshold does not change mode. Recording-time interaction retains the existing lock policy: the active label remains visible, but mode changes are disabled while controls are locked.

## Mode State Integration

The existing `CameraModePreset` values remain the source of truth:

- `VIDEO`
- `PRO`
- `HDR`, displayed as `NDR`
- `LOG_VIDEO`, displayed as `LOG`
- `RAW_MODE`, displayed as `RAW`

The existing order policy remains authoritative for cycling. The selector invokes the existing mode activation path so camera batching, imaging reset rules, color configuration, and state synchronization remain unchanged. Horizontal mode navigation is owned by the centered selector; a swipe on the camera preview itself no longer changes modes.

The old scroll-to-centered-item behavior is removed from the rendered UI because no multiple-item rail remains visible. The single visible slot uses gesture-based previous/next mode selection instead.

## Resolution Menu Contract

The resolution selector displays no arbitrary camera dimensions such as `4096x3072`, `1920x1080`, or other device-provided variants. It exposes at most four labels:

- `1K`
- `2K`
- `4K`
- `8K`

Each displayed tier is backed by an actually supported camera output belonging to that nominal family. Stream dimensions are normalized so the long edge is compared regardless of device orientation. Recognized long edges are explicit:

- `1K`: long edge `1280`.
- `2K`: long edge `1920` or `2048`.
- `4K`: long edge `3840` or `4096`.
- `8K`: long edge `7680` or `8192`.

For multiple supported outputs in one tier, the catalog prefers the canonical delivery size (`1280x720`, `2048x1080`, `3840x2160`, or `7680x4320`) when it is available, avoiding a silent change from an already-selected canonical format. If the canonical size is absent, it uses the largest supported output within the recognized tier while retaining only the tier label in the UI. A tier with no matching supported stream is omitted. Sizes outside these four tier families, including 5K and 6K variants, are not exposed as alternatives and are not relabeled upward.

When device capability data is not yet available, the existing canonical defaults are retained: `8K`, `4K`, `2K`, and `1K`.

## Files And Responsibilities

- `SettingsOptionCatalog.kt`: filters supported resolution rows into four named tiers and resolves the underlying selected dimensions.
- `MainActivity.kt`: installs the horizontal swipe handler and renders one visible active mode without shifting the shutter.
- `activity_main.xml`: replaces the expanded rail presentation with a centered single-slot mode control surface while preserving the capture controls geometry.
- Mode selection policy classes: retain cyclic order and provide testable swipe-step resolution where appropriate.

## Error Handling And Compatibility

- A gesture is ignored when no mode order is available or mode controls are disabled.
- Existing programmatic preset selection and state restoration update the single slot without gesture simulation.
- Existing mode configuration logic remains untouched; only mode presentation and user navigation change.
- Resolution filtering never advertises an unsupported tier and never exposes raw extra dimensions as UI labels.

## Verification

Unit tests will cover:

- The cyclic order in both directions, including wraparound from `RAW` to `VIDEO` and `VIDEO` to `RAW`.
- Only the active mode is rendered in the single-slot selector contract.
- Swipe interaction is disabled with locked controls.
- Resolution options contain only `1K`, `2K`, `4K`, and `8K`.
- Device-reported `4096` outputs map to `4K` labels, while non-tier 5K/6K sizes are excluded.

Device verification will confirm:

- Only one active mode label appears above the shutter.
- Swiping changes mode repeatedly around the full cycle.
- The shutter remains in the same screen position before and after mode swipes.
- The installed application launches without an immediate crash.
