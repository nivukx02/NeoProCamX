# Stable Startup And Truthful 4K Design

**Date:** 2026-05-27

**Problem**

Device reproduction shows a cold app launch taking approximately `5.31 s`.
During that launch, the app gives the visible surface to the Vulkan preview
path before throughput is known, skips hundreds of frames, then falls back to
Camera2. That surface ownership transition accounts for the temporary
incorrect preview presentation reported at startup.

The same device exposes `3840x2160` and `4096x3072` recording streams.
Nevertheless, selecting `3840x2160` while preview framing is `4:3` is rewritten
to `2880x2160`, then resolved and applied as `2560x1920`. Preview framing is
incorrectly changing the requested recording format.

**Required Behavior**

1. A cold start presents a stable Camera2 preview first.
2. An unqualified Vulkan visible-preview attempt must not temporarily replace
   the first stable displayed preview.
3. Preview aspect remains a viewfinder framing selection, not a recording
   resolution rewrite.
4. Selecting `4K` requests and persists standard UHD `3840x2160`, independent
   of the chosen preview framing.
5. If the device exposes its `4096x3072` stream, settings expose it separately
   as `4K Full 4:3` instead of silently using it for the `4K` selection.
6. If an exact requested output is not available, the app may apply a truthful
   lower fallback but must retain the user request rather than permanently
   overwriting it with the fallback.

**Architecture**

Keep the existing Camera2 and Vulkan runtime implementations, but make surface
ownership conservative: once the visible surface is committed to the direct
startup preview, a background quality path may not take over that same visible
surface without a separately validated promotion mechanism. This prevents a
failed GPU presentation experiment from being visible to the user.

At recording selection time, normalize only device orientation. Do not run the
selected output dimensions through viewfinder aspect cropping. Existing
Camera2/encoder resolution resolution remains authoritative for whether a
format can actually be recorded. Persist the requested selection separately
from the resolved output so fallback remains visible and truthful rather than
destructive.

GPU-processed recording qualification is independent of visible-preview
ownership. If Vulkan runtime support exists, recording may try the internal
hardware-import encoder path; its existing encoder-surface bind and processed
frame validation remains the gate that prevents a false GPU-quality recording
claim.

**Validation**

- Unit/source-contract tests fail before the repair and pass after it.
- Settings resolution tests prove `4K` is canonical UHD and that available
  `4096x3072` is exposed separately as `4K Full 4:3`.
- Startup binding tests prove direct Camera2 presentation is gated ahead of
  visible Vulkan ownership.
- Recording routing tests prove stable startup preview does not disable the
  separately validated Vulkan encoder-import attempt.
- Build and lint complete without errors or warnings.
- On the attached device, cold launch logs no longer show a visible Vulkan
  startup fallback transition, and explicit `3840x2160` selection remains
  `3840x2160` with `4:3` preview framing selected.
