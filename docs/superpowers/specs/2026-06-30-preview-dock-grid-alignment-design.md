# Preview Dock and Grid Alignment Design

## Goal

Match the approved camera-screen mockup by anchoring the resolution/aspect dock, video-mode dock, and shutter dock to the visible preview frame, while ensuring composition grids use the exact same 4:3 bounds as the rendered preview.

## Design

`PreviewContentBoundsResolver` remains the single source of truth for fitted preview geometry. `UIOverlay` will delegate its preview rectangle calculation to that resolver instead of independently centering an aspect rectangle. This includes the existing 4:3 upward framing bias and fixes the grid, focus, masks, and detection overlays together.

Dock placement will be calculated by `CameraDockLayoutPolicy` from the resolved preview top and bottom edges plus measured dock positions. The aspect dock will slightly overlap the preview's top edge, the video-mode dock will sit just below the preview's bottom edge, and the shutter dock will sit closely below the mode dock. The policy returns translations only; existing dock dimensions, styling, controls, and constraints remain unchanged.

## Spacing

- Aspect dock: `8dp` overlap into the preview start edge.
- Preview to video-mode dock: `24dp` gap.
- Video-mode dock to shutter dock: `8dp` gap.
- If the stack would extend below the viewport, move the mode and shutter docks upward together while preserving their gap.

## Verification

- Unit-test 4:3 overlay bounds against renderer bounds.
- Unit-test dock translations and small-screen overflow behavior.
- Run focused unit tests and the full unit-test task.
- Build and install the debug app, then capture a device screenshot for visual comparison with the approved mockup.
