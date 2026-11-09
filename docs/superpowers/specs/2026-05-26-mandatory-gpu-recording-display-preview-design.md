# Mandatory GPU Recording And Display-Aware Preview Design

Date: 2026-05-26
Project: NeoProCamX

## Purpose

This amendment supersedes the encoded-mode fallback allowance in
`2026-05-25-truthful-recorded-quality-repair-design.md`. The user requires
processed video modes to produce recordings only through the custom GPU path
and requires preview stream selection to be driven by the mobile display
resolution rather than a fixed 1080p ceiling.

It does not change the truth contract: higher bitrate, GPU routing, or a
higher-resolution preview do not by themselves prove cinema-quality output.
Runtime frame-import evidence, stable timing, and recorded-file validation
remain required.

## Scope

Processed encoded modes are Normal Video, Pro Video, N-Log, NDR10+, and
NIVUKX Vision. RAW_SENSOR and NPV10 private masters remain truthful source
captures; their masters are not replaced with processed pixels.

## Recording Contract

For every processed encoded mode:

1. The planned and active recording route must be
   `INTERNAL_HARDWARE_IMPORT_ENCODER`.
2. Recording start is rejected when Vulkan is unsupported, the internal route
   cannot be selected, or the encoder output surface cannot be bound.
3. After start, the route must obtain imported-frame evidence while the
   encoder surface remains bound and frame import remains enabled.
4. If that evidence does not arrive within a bounded startup interval, stop
   the invalid recording and surface a clear GPU-processing failure.
5. No processed mode silently writes a Camera2 bypass file that can be
   mistaken for enhanced output.

RAW capture keeps its direct Camera2 sensor-truth route. NPV10 continues to
store truthful private master data; GPU enhancement may be used only for
visible monitoring and disclosed manual export processing.

## Preview Contract

1. Preview limits are resolved from the actual display pixel bounds supplied
   by the active application resources, normalized to long-edge/short-edge
   form.
2. Camera2 stream selection chooses the best supported aspect-matching stream
   that fits the display budget.
3. The default recording state does not impose a fixed 1920x1080 preview cap.
4. Runtime thermal or sustained-FPS stability guards may lower preview load
   when necessary to preserve recording timing.
5. The native Vulkan preview may be attempted when the runtime and surface are
   available; its existing import watchdog falls back if the rendered route
   fails health checks.

## Validation

- Unit tests prove processed modes reject bypass routing and RAW remains
  direct and truthful.
- Unit tests prove display budgets larger and smaller than 1080p affect
  preview limits while thermal/performance caps still win.
- Unit tests prove default high-resolution recording no longer proactively
  forces a fixed 1080p preview budget.
- Device validation records a processed clip and requires logs showing the
  internal GPU route, bound encoder surface, enabled frame import, and
  imported frame count above zero.
- Device validation captures the selected preview size and compares it with
  the connected phone's display bounds and actual supported stream choice.

## Non-Claims

This change can make GPU-processed video better than a bypass recording because
the selected denoise, sharpness, tone, and color stages can reach encoded
frames. It does not promise zero physical noise, invented spatial detail, or
superiority over another camera without controlled device measurements.
