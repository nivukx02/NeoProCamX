#!/usr/bin/env python3
"""Host-only math model for the project-owned RAW/NDR preview minification pass."""

from __future__ import annotations

import math


def project_preview_downsample_enabled(
    delivery_mode: str,
    input_family: str,
    source_size: tuple[int, int],
    target_size: tuple[int, int],
) -> bool:
    """Mirror the native route gate without claiming device/GPU support."""
    source_width, source_height = source_size
    target_width, target_height = target_size
    if min(source_width, source_height, target_width, target_height) <= 0:
        raise ValueError("source and target dimensions must be positive")
    return (
        delivery_mode == "PROJECT_PQ_TEXTURE"
        and input_family in {"SENSOR_LINEAR", "NEO_SCENE_LOG10"}
        and (source_width > target_width or source_height > target_height)
    )


def quadrant_offset_source_pixels(minification_scale: float) -> float:
    """Return the two-sample-per-axis box-quadrature offset in source pixels."""
    if not math.isfinite(minification_scale) or minification_scale <= 0.0:
        raise ValueError("minification scale must be finite and positive")
    return minification_scale * 0.25 if minification_scale > 1.0 else 0.0


def two_tap_axis_response(minification_scale: float, angular_frequency: float) -> float:
    """Magnitude response of equal taps at +/- the ratio-derived quadrant offset."""
    offset = quadrant_offset_source_pixels(minification_scale)
    return abs(math.cos(angular_frequency * offset))


def source_footprint_box_first_zero(minification_scale: float) -> float:
    if minification_scale <= 1.0:
        raise ValueError("a minification footprint must be wider than one source pixel")
    return 2.0 * math.pi / minification_scale


def decode_intermediate_channel(value: float, preserve_extended_range: bool) -> float:
    """Model the FP16 decode pass range contract for one scene-linear channel."""
    if not math.isfinite(value):
        raise ValueError("channel value must be finite")
    if preserve_extended_range:
        return max(value, 0.0)
    return min(max(value, 0.0), 1.0)

