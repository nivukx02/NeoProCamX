"""Deterministic host model for the CP-168 and CP-169 NPV10 contracts.

This model validates queue mathematics and lifecycle ownership only. It cannot prove physical
Camera2 cadence, flash throughput, thermals, or device image quality; those remain device gates.
"""

from __future__ import annotations

from dataclasses import dataclass
import heapq
import math


FULL_SENSOR_WIDTH = 4096
FULL_SENSOR_HEIGHT = 3072
TARGET_FPS = 25
TARGET_DURATION_SECONDS = 60
STORAGE_BIT_DEPTH = 10
PACKING_WORKERS = 3
MAX_PENDING_FRAMES = 4


def p010_decoded_bytes(width: int, height: int) -> int:
    if width <= 0 or height <= 0 or width % 2 or height % 2:
        raise ValueError("P010 geometry must be positive and even")
    # Two bytes per luma sample plus one interleaved 16-bit chroma sample per two pixels.
    return width * height * 3


def packed_p010_10_bytes(width: int, height: int) -> int:
    decoded_bytes = p010_decoded_bytes(width, height)
    # Lossless PACKED_P010_10_LE_V1 stores four 10-bit words in five bytes.
    if decoded_bytes % 8:
        raise ValueError("P010 payload cannot be represented as complete packed-10 words")
    return decoded_bytes * 5 // 8


@dataclass(frozen=True)
class SustainedThroughputResult:
    width: int
    height: int
    fps: int
    duration_seconds: int
    frames_requested: int
    frames_admitted: int
    first_rejected_frame: int | None
    maximum_pending_frames: int
    decoded_bytes_per_frame: int
    stored_bytes_per_frame: int
    required_bytes_per_second: int
    packing_capacity_bytes_per_second: float
    writer_capacity_bytes_per_second: float

    @property
    def rejected_frames(self) -> int:
        return self.frames_requested - self.frames_admitted

    @property
    def stable(self) -> bool:
        return (
            self.rejected_frames == 0
            and self.packing_capacity_bytes_per_second >= self.required_bytes_per_second
            and self.writer_capacity_bytes_per_second >= self.required_bytes_per_second
        )


def simulate_sustained_full_sensor_pipeline(
    *,
    packing_time_ms: float,
    writer_time_ms: float,
    width: int = FULL_SENSOR_WIDTH,
    height: int = FULL_SENSOR_HEIGHT,
    fps: int = TARGET_FPS,
    duration_seconds: int = TARGET_DURATION_SECONDS,
    packing_workers: int = PACKING_WORKERS,
    max_pending_frames: int = MAX_PENDING_FRAMES,
) -> SustainedThroughputResult:
    """Model fixed-rate ingress, ordered parallel packing, and one durable writer.

    Pending ownership is released only after the ordered writer finishes, matching the production
    semaphore contract. A rejected frame terminates admission because production poisons the strict
    master at the first missing frame; the remaining request count is retained for the report.
    """

    if (
        not math.isfinite(packing_time_ms)
        or not math.isfinite(writer_time_ms)
        or packing_time_ms <= 0.0
        or writer_time_ms <= 0.0
    ):
        raise ValueError("stage times must be positive")
    if fps <= 0 or duration_seconds <= 0 or packing_workers <= 0 or max_pending_frames <= 0:
        raise ValueError("pipeline dimensions must be positive")

    decoded_bytes = p010_decoded_bytes(width, height)
    stored_bytes = packed_p010_10_bytes(width, height)
    frame_period_ms = 1000.0 / fps
    requested_frames = fps * duration_seconds
    worker_available_ms = [0.0 for _ in range(packing_workers)]
    pending_writer_finishes_ms: list[float] = []
    ordered_pack_ready_ms = 0.0
    writer_available_ms = 0.0
    admitted = 0
    first_rejected: int | None = None
    maximum_pending = 0

    for frame_index in range(requested_frames):
        arrival_ms = frame_index * frame_period_ms
        while pending_writer_finishes_ms and pending_writer_finishes_ms[0] <= arrival_ms:
            heapq.heappop(pending_writer_finishes_ms)

        if len(pending_writer_finishes_ms) >= max_pending_frames:
            first_rejected = frame_index
            break

        worker_index = min(
            range(packing_workers),
            key=lambda index: worker_available_ms[index],
        )
        packing_started_ms = max(arrival_ms, worker_available_ms[worker_index])
        packing_finished_ms = packing_started_ms + packing_time_ms
        worker_available_ms[worker_index] = packing_finished_ms

        # Out-of-order workers publish only the largest contiguous ingress prefix.
        ordered_pack_ready_ms = max(ordered_pack_ready_ms, packing_finished_ms)
        writer_started_ms = max(writer_available_ms, ordered_pack_ready_ms)
        writer_finished_ms = writer_started_ms + writer_time_ms
        writer_available_ms = writer_finished_ms
        heapq.heappush(pending_writer_finishes_ms, writer_finished_ms)
        admitted += 1
        maximum_pending = max(maximum_pending, len(pending_writer_finishes_ms))

    return SustainedThroughputResult(
        width=width,
        height=height,
        fps=fps,
        duration_seconds=duration_seconds,
        frames_requested=requested_frames,
        frames_admitted=admitted,
        first_rejected_frame=first_rejected,
        maximum_pending_frames=maximum_pending,
        decoded_bytes_per_frame=decoded_bytes,
        stored_bytes_per_frame=stored_bytes,
        required_bytes_per_second=stored_bytes * fps,
        packing_capacity_bytes_per_second=(
            stored_bytes * packing_workers * 1000.0 / packing_time_ms
        ),
        writer_capacity_bytes_per_second=stored_bytes * 1000.0 / writer_time_ms,
    )


@dataclass
class RestartGeneration:
    generation: int
    pending_frames: int = 0
    boundary_established: bool = False
    sealed: bool = False

    def admit(self) -> None:
        if self.boundary_established or self.sealed:
            raise RuntimeError("ingress is closed")
        self.pending_frames += 1

    def retire(self) -> None:
        if self.pending_frames <= 0:
            raise RuntimeError("pending ownership underflow")
        self.pending_frames -= 1

    def establish_boundary(self) -> None:
        if self.pending_frames:
            raise RuntimeError("producer boundary cannot overtake accepted ownership")
        self.boundary_established = True

    def seal(self) -> None:
        if not self.boundary_established or self.pending_frames:
            raise RuntimeError("unproven generation cannot be sealed")
        self.sealed = True


class RapidRestartCoordinator:
    """Reference lifecycle that admits a new generation only after exact retirement."""

    def __init__(self) -> None:
        self._next_generation = 1
        self.active: RestartGeneration | None = None
        self.completed_generations: list[int] = []

    def start(self) -> RestartGeneration:
        if self.active is not None:
            raise RuntimeError("previous generation is not fully retired")
        generation = RestartGeneration(self._next_generation)
        self._next_generation += 1
        self.active = generation
        return generation

    def finish(self, generation: RestartGeneration) -> None:
        if self.active is not generation:
            raise RuntimeError("stale generation cannot retire a current session")
        generation.seal()
        self.completed_generations.append(generation.generation)
        self.active = None
