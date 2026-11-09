# Camera UI/UX Rebuild Design

Date: 2026-05-09

## Scope

Rebuild the main camera UI around the approved Vivo/iQOO/Xiaomi-inspired layout while preserving the existing camera features. This spec covers the visible camera controls, mode switching behavior, pro controller layout, clean preview HUD, and required animations.

This design does not change camera capture pipeline behavior. It defines the UI contract that the implementation plan must follow.

## Modes

The bottom mode rail contains only the modes supported by this project:

- VIDEO
- PRO
- HDR
- LOG
- RAW

PHOTO must not appear in the mode rail. PRO starts as the centered active mode in the approved design.

When the user scrolls the mode rail, the mode that reaches the center becomes the active/accessed mode. The active mode is shown as a wider white pill. Non-active modes are plain text. There is no extra center marker, helper text, or instructional overlay.

## Clean Preview HUD

The live preview must not show unwanted tool information. The only persistent preview HUD content is:

- Sensor size
- Active resolution
- Aspect ratio

Example formatting:

`1/1.31" | 8192x6144 | 4:3`

The values must come from the active camera capability/session state where available. If a value is unavailable, show a conservative fallback value such as `Unknown` rather than inventing a value.

## Pro Controller

Manual controls appear only in PRO mode. Other modes keep the clean preview and bottom mode/shutter controls without the pro control strip.

The PRO controller uses the approved reference-matched landscape strip:

- Left side: circular `A` Auto/Manual control with `Manual` label below.
- Top: horizontal scrollable adjust ruler with white ticks, yellow active center tick, current value above the ruler, and neighbor values below.
- Bottom parameter row: EV, ISO, shutter speed, WB, AF, and metering.
- Active parameter uses yellow outline and yellow value text.
- Inactive parameters use white.
- Disabled/unavailable parameters use dimmed white.
- The controller sits above the bottom mode/shutter overlay and must not overlap the mode rail or shutter controls.

The adjust ruler is scrollable for the active parameter. The parameter row remains fixed.

## Bottom Controls

The bottom area contains:

- Left button: front camera switch.
- Center button: shutter/record button.
- Right button: settings.

The previous gallery/preview button is removed from this main capture screen.

The shutter button uses a large white circular camera-app style button when idle. The settings and front-camera buttons use compact circular icon buttons.

## Animations

Mode activation animation:

- When a mode reaches center, it becomes active.
- The active mode pill animates with a short pop/settle effect.
- The animation must be lightweight and avoid layout shift.

Recording animation:

- When recording starts, the shutter changes from a white circular button to a red rounded-square stop button.
- A subtle red pulse ring is shown while recording.
- When recording stops, it returns smoothly to the idle white circular button.
- The animation must not block camera preview, capture callbacks, or encoder work.

## Component Boundaries

Implementation should keep UI responsibilities separated:

- Mode rail: mode list, center detection, active mode events, mode animation.
- Preview HUD: sensor size, resolution, and aspect display only.
- Pro controls: active parameter selection, adjust ruler, value rendering, availability/disabled state.
- Bottom controls: front camera, shutter/record state, settings.
- Activity/controller glue: maps UI events to existing camera state and capture actions.

The camera pipeline should expose mode/capability state to the UI through existing project patterns or a small adapter. UI code should not re-query camera characteristics directly from view classes.

## State And Error Handling

If a mode is unavailable on the current camera, keep the mode visible only if the project already exposes it as selectable; otherwise disable it visually and prevent activation. Do not silently switch to a different mode without updating the active mode pill.

If sensor size, resolution, or aspect ratio cannot be resolved, show `Unknown` for that field.

If front camera is unavailable, disable the front-camera button.

If a pro parameter is unsupported, dim that parameter tile and prevent ruler adjustment for it.

## Testing

The implementation plan must include:

- Unit tests for mode center selection and active-mode state transitions where practical.
- UI/instrumented validation that PHOTO is absent and VIDEO, PRO, HDR, LOG, RAW are present.
- UI/instrumented validation that the preview HUD contains only sensor size, resolution, and aspect ratio.
- UI/instrumented validation that PRO controls are visible only in PRO mode.
- UI/instrumented validation that the shutter recording state changes visual state.
- Manual/emulator screenshot check for overlap between PRO controls, mode rail, and shutter controls.

## Approved Visual Decisions

The following decisions are approved:

- Reference-matched PRO controller strip.
- Clean preview HUD with only sensor size, resolution, and aspect ratio.
- No PHOTO mode.
- Center-to-access mode behavior.
- Front-camera button on the left, shutter in the center, settings on the right.
- PRO controls positioned above the bottom overlay.
- Mode activation animation and shutter recording animation.
