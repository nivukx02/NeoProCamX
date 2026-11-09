#!/usr/bin/env python3
"""Deterministic system-level simulation for NeoProCamX runtime features.

The model intentionally covers only host-provable behavior: queue arithmetic, scheduling,
generation ownership, thermal policy monotonicity, preview extent selection, and conversion
throughput. Android Camera2, codecs, drivers, sensors, and real image quality remain device gates.
"""

from __future__ import annotations

import json
import math
from collections import deque
from dataclasses import dataclass
from enum import Enum
from typing import Iterable


class OverflowPolicy(str, Enum):
    PREVIEW_LATEST = "PREVIEW_LATEST"
    RECORDING_FAIL_CLOSED = "RECORDING_FAIL_CLOSED"


@dataclass(frozen=True)
class StageQueueResult:
    produced: int
    completed: int
    replaced_or_rejected: int
    terminal_failure: bool
    max_queue_depth: int
    max_latency_ms: float
    achieved_fps: float
    completion_times_ns: tuple[int, ...]


def simulate_stage_queue(
    *,
    source_fps: float,
    service_time_ms: float,
    duration_seconds: float,
    queue_capacity: int,
    overflow_policy: OverflowPolicy,
) -> StageQueueResult:
    """Event model for a single worker and bounded pending queue.

    Preview replaces the newest pending frame to remain current. Recording never hides a loss:
    the first overflow becomes a terminal failure.
    """
    if source_fps <= 0 or service_time_ms <= 0 or duration_seconds <= 0:
        raise ValueError("cadence inputs must be positive")
    if queue_capacity < 1:
        raise ValueError("queue_capacity must be at least one")

    source_interval_ns = int(round(1_000_000_000.0 / source_fps))
    service_ns = int(round(service_time_ms * 1_000_000.0))
    end_ns = int(round(duration_seconds * 1_000_000_000.0))
    arrivals = list(range(0, end_ns, source_interval_ns))
    pending: deque[int] = deque()
    next_arrival_index = 0
    in_flight_timestamp: int | None = None
    completion_ns: int | None = None
    completion_times: list[int] = []
    latencies_ns: list[int] = []
    produced = 0
    replaced_or_rejected = 0
    max_queue_depth = 0
    terminal_failure = False

    while next_arrival_index < len(arrivals) or in_flight_timestamp is not None:
        next_arrival = (
            arrivals[next_arrival_index]
            if next_arrival_index < len(arrivals)
            else None
        )
        # Completion wins a tie so a frame finishing exactly on budget frees capacity first.
        if completion_ns is not None and (
            next_arrival is None or completion_ns <= next_arrival
        ):
            now_ns = completion_ns
            if in_flight_timestamp is None:
                raise RuntimeError("completion without an in-flight frame")
            completion_times.append(now_ns)
            latencies_ns.append(now_ns - in_flight_timestamp)
            if pending:
                in_flight_timestamp = pending.popleft()
                completion_ns = now_ns + service_ns
            else:
                in_flight_timestamp = None
                completion_ns = None
            continue

        if next_arrival is None:
            break
        now_ns = next_arrival
        next_arrival_index += 1
        produced += 1
        if in_flight_timestamp is None:
            in_flight_timestamp = now_ns
            completion_ns = now_ns + service_ns
        elif len(pending) < queue_capacity:
            pending.append(now_ns)
            max_queue_depth = max(max_queue_depth, len(pending))
        elif overflow_policy is OverflowPolicy.PREVIEW_LATEST:
            pending[-1] = now_ns
            replaced_or_rejected += 1
        else:
            replaced_or_rejected += 1
            terminal_failure = True
            break

    achieved_fps = 0.0
    if len(completion_times) >= 2:
        elapsed = (completion_times[-1] - completion_times[0]) / 1_000_000_000.0
        achieved_fps = (len(completion_times) - 1) / elapsed if elapsed > 0 else 0.0
    return StageQueueResult(
        produced=produced,
        completed=len(completion_times),
        replaced_or_rejected=replaced_or_rejected,
        terminal_failure=terminal_failure,
        max_queue_depth=max_queue_depth,
        max_latency_ms=(max(latencies_ns) / 1_000_000.0) if latencies_ns else 0.0,
        achieved_fps=achieved_fps,
        completion_times_ns=tuple(completion_times),
    )


def raw_retained_image_budget(reader_max_images: int, strict_high_end: bool) -> int:
    if reader_max_images < 2:
        raise ValueError("RAW ImageReader requires at least two images")
    # A strict extra reserve is used only when one queued image and one active writer still fit.
    reserve = 2 if strict_high_end and reader_max_images >= 4 else 1
    return max(1, reader_max_images - reserve)


def resolve_safe_raw_queue_capacity(
    *,
    requested_queue_capacity: int,
    reader_max_images: int,
    reserved_active_writes: int,
    strict_high_end: bool,
) -> int:
    """Return a queue size that cannot consume the ImageReader's protected reserve."""
    if requested_queue_capacity < 1:
        raise ValueError("requested queue capacity must be positive")
    if reader_max_images < 3:
        raise ValueError("reader must hold one HAL slot, one queued image, and one active writer")
    budget = raw_retained_image_budget(reader_max_images, strict_high_end)
    active_writes = max(0, reserved_active_writes)
    available_queue_slots = budget - active_writes
    if available_queue_slots < 1:
        raise ValueError("reader budget cannot hold a queue plus active writer")
    fill_cap = max(1, int(reader_max_images * 0.90))
    return min(requested_queue_capacity, available_queue_slots, fill_cap)


@dataclass(frozen=True)
class RawPreviewExtent:
    width: int
    height: int
    decimate: int
    recording_full_sensor: bool

    @property
    def pixels(self) -> int:
        return self.width * self.height


def resolve_raw_preview_extent(
    width: int,
    height: int,
    *,
    recording_active: bool,
    target_long_edge: int = 1536,
) -> RawPreviewExtent:
    if min(width, height, target_long_edge) <= 0:
        raise ValueError("RAW extent inputs must be positive")
    if recording_active:
        return RawPreviewExtent(width, height, 1, True)
    source_long_edge = max(width, height)
    decimate = max(1, math.ceil(source_long_edge / target_long_edge))
    output_width = (width // (2 * decimate)) * 2
    output_height = (height // (2 * decimate)) * 2
    if output_width < 64 or output_height < 64:
        return RawPreviewExtent(width, height, 1, False)
    return RawPreviewExtent(output_width, output_height, decimate, False)


def estimate_scaled_processing_ms(
    *,
    full_width: int,
    full_height: int,
    full_processing_ms: float,
    extent: RawPreviewExtent,
    fixed_overhead_ms: float = 3.0,
) -> float:
    if min(full_width, full_height, full_processing_ms) <= 0:
        raise ValueError("processing estimate inputs must be positive")
    scalable_ms = max(0.0, full_processing_ms - fixed_overhead_ms)
    ratio = extent.pixels / float(full_width * full_height)
    return fixed_overhead_ms + scalable_ms * ratio


@dataclass(frozen=True)
class ThermalPlan:
    severity: int
    analysis_fps_cap: int
    preview_long_edge: int
    stop_recording: bool


def resolve_thermal_plan(severity: int, *, recording_active: bool) -> ThermalPlan:
    """Host model of monotonic secondary-load shedding without recording-quality reduction."""
    severity = min(max(severity, 0), 5)
    analysis_caps = (30, 30, 24, 15, 10, 5)
    preview_edges = (8192, 3840, 3840, 1920, 1280, 854)
    base_cap = analysis_caps[severity]
    analysis_cap = max(5, base_cap // 2) if recording_active else base_cap
    preview_edge = preview_edges[severity]
    if recording_active and severity >= 2:
        preview_edge = min(preview_edge, 1920)
    return ThermalPlan(
        severity=severity,
        analysis_fps_cap=analysis_cap,
        preview_long_edge=preview_edge,
        stop_recording=recording_active and severity >= 5,
    )


@dataclass
class CameraOpenGateModel:
    generation: int = 0
    next_attempt_id: int = 0
    permit_owner: int | None = None

    def begin_lifecycle(self) -> int:
        self.generation += 1
        return self.generation

    def invalidate(self) -> int:
        self.generation += 1
        return self.generation

    def new_attempt(self, generation: int) -> tuple[int, int] | None:
        if generation != self.generation:
            return None
        self.next_attempt_id += 1
        return generation, self.next_attempt_id

    def claim(self, attempt: tuple[int, int]) -> bool:
        generation, attempt_id = attempt
        if generation != self.generation or self.permit_owner is not None:
            return False
        self.permit_owner = attempt_id
        return True

    def release(self, attempt: tuple[int, int]) -> bool:
        _, attempt_id = attempt
        if self.permit_owner != attempt_id:
            return False
        self.permit_owner = None
        return True


@dataclass(frozen=True)
class ModeMutation:
    compositor: str = "NONE"
    preview_pipeline: str = "NONE"


def coalesce_mode_mutations(mutations: Iterable[ModeMutation]) -> ModeMutation:
    compositor_rank = {"NONE": 0, "GEOMETRY": 1, "LUT": 2}
    pipeline_rank = {"NONE": 0, "STANDARD": 1, "FORCE_SESSION": 2}
    compositor = "NONE"
    preview_pipeline = "NONE"
    for mutation in mutations:
        if compositor_rank[mutation.compositor] > compositor_rank[compositor]:
            compositor = mutation.compositor
        if pipeline_rank[mutation.preview_pipeline] > pipeline_rank[preview_pipeline]:
            preview_pipeline = mutation.preview_pipeline
    return ModeMutation(compositor, preview_pipeline)


@dataclass(frozen=True)
class ConversionEstimate:
    sequential_ms: float
    pipelined_ms: float
    speedup: float


def estimate_conversion_pipeline(
    *,
    frames: int,
    decode_ms: float,
    process_ms: float,
    encode_ms: float,
    process_workers: int,
) -> ConversionEstimate:
    if frames < 1 or min(decode_ms, process_ms, encode_ms) <= 0 or process_workers < 1:
        raise ValueError("conversion inputs must be positive")
    sequential = frames * (decode_ms + process_ms + encode_ms)
    process_lane_ms = process_ms / process_workers
    steady_state_ms = max(decode_ms, process_lane_ms, encode_ms)
    pipelined = decode_ms + process_ms + encode_ms + (frames - 1) * steady_state_ms
    return ConversionEstimate(sequential, pipelined, sequential / pipelined)


def muxer_queue_capacity_from_bytes(
    *,
    bitrate_bps: int,
    fps: float,
    byte_capacity: int,
    sample_capacity: int,
) -> int:
    if min(bitrate_bps, fps, byte_capacity, sample_capacity) <= 0:
        raise ValueError("muxer capacity inputs must be positive")
    average_frame_bytes = max(1, math.ceil(bitrate_bps / 8.0 / fps))
    return min(sample_capacity, byte_capacity // average_frame_bytes)


def simulate_muxer(
    *,
    bitrate_bps: int,
    fps: float,
    storage_megabytes_per_second: float,
    duration_seconds: float,
    byte_capacity: int = 96 * 1024 * 1024,
    sample_capacity: int = 128,
) -> StageQueueResult:
    if storage_megabytes_per_second <= 0:
        raise ValueError("storage throughput must be positive")
    frame_bytes = bitrate_bps / 8.0 / fps
    storage_bytes_per_second = storage_megabytes_per_second * 1_000_000.0
    service_ms = frame_bytes / storage_bytes_per_second * 1000.0
    capacity = muxer_queue_capacity_from_bytes(
        bitrate_bps=bitrate_bps,
        fps=fps,
        byte_capacity=byte_capacity,
        sample_capacity=sample_capacity,
    )
    if capacity < 1:
        raise ValueError("one encoded frame exceeds the muxer resident-byte budget")
    return simulate_stage_queue(
        source_fps=fps,
        service_time_ms=service_ms,
        duration_seconds=duration_seconds,
        queue_capacity=capacity,
        overflow_policy=OverflowPolicy.RECORDING_FAIL_CLOSED,
    )


def run_feature_matrix() -> dict[str, object]:
    full_extent = resolve_raw_preview_extent(4096, 3072, recording_active=True)
    idle_extent = resolve_raw_preview_extent(4096, 3072, recording_active=False)
    idle_ms = estimate_scaled_processing_ms(
        full_width=4096,
        full_height=3072,
        full_processing_ms=235.0,
        extent=idle_extent,
    )
    preview = simulate_stage_queue(
        source_fps=25.0,
        service_time_ms=idle_ms,
        duration_seconds=12.0,
        queue_capacity=1,
        overflow_policy=OverflowPolicy.PREVIEW_LATEST,
    )
    muxer = simulate_muxer(
        bitrate_bps=240_000_000,
        fps=25.0,
        storage_megabytes_per_second=40.0,
        duration_seconds=12.0,
    )
    conversion = estimate_conversion_pipeline(
        frames=300,
        decode_ms=8.0,
        process_ms=22.0,
        encode_ms=10.0,
        process_workers=2,
    )
    return {
        "raw_idle_extent": [idle_extent.width, idle_extent.height],
        "raw_recording_extent": [full_extent.width, full_extent.height],
        "estimated_idle_raw_ms": round(idle_ms, 3),
        "estimated_idle_raw_fps": round(preview.achieved_fps, 3),
        "estimated_idle_raw_replacements": preview.replaced_or_rejected,
        "muxer_terminal_failure_at_40MBps": muxer.terminal_failure,
        "conversion_pipeline_speedup": round(conversion.speedup, 3),
        "status": "PASS" if not muxer.terminal_failure and preview.achieved_fps >= 24.9 else "FAIL",
    }


if __name__ == "__main__":
    print(json.dumps(run_feature_matrix(), indent=2, sort_keys=True))
