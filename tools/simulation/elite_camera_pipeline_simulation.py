#!/usr/bin/env python3
"""Deterministic host simulation for NeoProCamX delivery invariants.

This is deliberately independent of Android and camera hardware.  It exercises the
math and state-machine properties that must be true before a device run is useful:

* every video mode preserves at least ten meaningful bits and never silently falls
  back to an eight-bit encoder;
* PQ transfer math is monotone and round-trips absolute luminance;
* project-owned RAW/NDR frames retain their own texture transform;
* mode changes retain the last proven frame until a new epoch is presented;
* reported cadence is derived from final presentation timestamps;
* chronological temporal history is independent of swapchain acquisition order;
* WB/CCM one-frame outliers are held instead of producing a colour flash;
* bitrate selection is bounded by measured complexity and codec capability;
* detail recovery is bounded to avoid halos and artificial edge overshoot.

The simulator does not claim device performance or image quality.  Those remain
device/chart gates after this host suite passes.
"""

from __future__ import annotations

import math
from dataclasses import dataclass
from enum import Enum
from typing import Iterable, Sequence


class VideoMode(str, Enum):
    VIDEO = "VIDEO"
    PRO = "PRO"
    NDR = "NDR"
    LOG = "LOG"
    RAW = "RAW"


class ModeAdmissionState(str, Enum):
    ADMITTED = "ADMITTED"
    REJECTED = "REJECTED"


@dataclass(frozen=True)
class ModeCapabilities:
    raw_capture: bool
    manual_sensor: bool
    camera_p010: bool
    gpu_ten_bit: bool
    encoder_main10: bool
    fp16_processing: bool


@dataclass(frozen=True)
class ModeAdmission:
    requested_mode: VideoMode
    state: ModeAdmissionState
    failure_codes: tuple[str, ...]

    @property
    def recording_allowed(self) -> bool:
        return self.state is ModeAdmissionState.ADMITTED


def resolve_mode_admission(
    requested_mode: VideoMode,
    capabilities: ModeCapabilities,
) -> ModeAdmission:
    """Fail closed while retaining the exact requested mode identity."""
    failures: list[str] = []
    if requested_mode is VideoMode.RAW:
        if not capabilities.raw_capture or not capabilities.manual_sensor:
            failures.append("RAW_CAPTURE_UNAVAILABLE")
    else:
        if not (
            capabilities.camera_p010
            and capabilities.gpu_ten_bit
            and capabilities.encoder_main10
        ):
            failures.append("TEN_BIT_TRANSPORT_UNAVAILABLE")
        if requested_mode is VideoMode.NDR and not (
            capabilities.raw_capture
            and capabilities.manual_sensor
            and capabilities.fp16_processing
        ):
            failures.append("SENSOR_LINEAR_NDR_UNAVAILABLE")
    return ModeAdmission(
        requested_mode=requested_mode,
        state=(
            ModeAdmissionState.ADMITTED
            if not failures
            else ModeAdmissionState.REJECTED
        ),
        failure_codes=tuple(failures),
    )


@dataclass(frozen=True)
class DeliveryProof:
    mode: VideoMode
    source_bits: int
    processing_bits: int
    storage_bits: int
    encoder_bits: int | None
    capability_proven: bool

    @property
    def minimum_meaningful_bits(self) -> int:
        stages = [self.source_bits, self.processing_bits, self.storage_bits]
        if self.mode is not VideoMode.RAW:
            stages.append(self.encoder_bits or 0)
        return min(stages)

    def validate_strict_ten_bit(self) -> None:
        if not self.capability_proven:
            raise ValueError(f"{self.mode.value}: 10-bit capability is not proven")
        if self.minimum_meaningful_bits < 10:
            raise ValueError(
                f"{self.mode.value}: silent bit-depth downgrade "
                f"({self.minimum_meaningful_bits}-bit stage)"
            )
        if self.mode is not VideoMode.RAW and self.encoder_bits != 10:
            raise ValueError(
                f"{self.mode.value}: encoded delivery must be native Main10/P010"
            )


# SMPTE ST 2084 constants.  Luminance is expressed in cd/m2 (nits).
_PQ_M1 = 2610.0 / 16384.0
_PQ_M2 = 2523.0 / 32.0
_PQ_C1 = 3424.0 / 4096.0
_PQ_C2 = 2413.0 / 128.0
_PQ_C3 = 2392.0 / 128.0


def pq_encode_nits(nits: float) -> float:
    normalized = min(max(nits, 0.0), 10_000.0) / 10_000.0
    powered = normalized**_PQ_M1
    return ((_PQ_C1 + _PQ_C2 * powered) / (1.0 + _PQ_C3 * powered)) ** _PQ_M2


def pq_decode_nits(code: float) -> float:
    encoded = min(max(code, 0.0), 1.0) ** (1.0 / _PQ_M2)
    numerator = max(encoded - _PQ_C1, 0.0)
    denominator = max(_PQ_C2 - _PQ_C3 * encoded, 1.0e-12)
    return 10_000.0 * (numerator / denominator) ** (1.0 / _PQ_M1)


Matrix4 = tuple[float, ...]


def choose_texture_transform(
    use_project_owned: bool,
    camera_matrix: Matrix4,
    project_owned_matrix: Matrix4,
) -> Matrix4:
    if len(camera_matrix) != 16 or len(project_owned_matrix) != 16:
        raise ValueError("SurfaceTexture transforms must contain 16 coefficients")
    return project_owned_matrix if use_project_owned else camera_matrix


def transform_uv(matrix: Matrix4, u: float, v: float) -> tuple[float, float]:
    # Column-major OpenGL mat4 * vec4(u, v, 0, 1).
    return (
        matrix[0] * u + matrix[4] * v + matrix[12],
        matrix[1] * u + matrix[5] * v + matrix[13],
    )


@dataclass(frozen=True)
class PresentationStats:
    frame_count: int
    duration_seconds: float
    achieved_fps: float
    missing_intervals: int


def presentation_stats(timestamps_ns: Sequence[int], target_fps: float) -> PresentationStats:
    if target_fps <= 0.0:
        raise ValueError("target_fps must be positive")
    if len(timestamps_ns) < 2:
        return PresentationStats(len(timestamps_ns), 0.0, 0.0, 0)
    if any(b <= a for a, b in zip(timestamps_ns, timestamps_ns[1:])):
        raise ValueError("presentation timestamps must be strictly increasing")
    duration = (timestamps_ns[-1] - timestamps_ns[0]) / 1_000_000_000.0
    achieved = (len(timestamps_ns) - 1) / duration
    target_interval = 1_000_000_000.0 / target_fps
    missing = 0
    for first, second in zip(timestamps_ns, timestamps_ns[1:]):
        represented = max(1, int(round((second - first) / target_interval)))
        missing += represented - 1
    return PresentationStats(len(timestamps_ns), duration, achieved, missing)


def simulate_latest_frame_processor(
    source_fps: float,
    service_time_ms: float,
    duration_seconds: float,
) -> list[int]:
    """Model a single-flight processor with a one-frame latest-only mailbox."""
    if source_fps <= 0 or service_time_ms <= 0 or duration_seconds <= 0:
        raise ValueError("cadence inputs must be positive")
    source_interval_ns = int(round(1_000_000_000.0 / source_fps))
    service_ns = int(round(service_time_ms * 1_000_000.0))
    end_ns = int(round(duration_seconds * 1_000_000_000.0))
    source_times = list(range(0, end_ns + 1, source_interval_ns))
    next_source = 0
    busy_until = -1
    in_flight_timestamp: int | None = None
    pending_timestamp: int | None = None
    presented: list[int] = []
    now = 0
    while next_source < len(source_times) or in_flight_timestamp is not None:
        next_input = source_times[next_source] if next_source < len(source_times) else None
        next_completion = busy_until if busy_until >= 0 else None
        candidates = [value for value in (next_input, next_completion) if value is not None]
        if not candidates:
            break
        now = min(candidates)
        if next_completion is not None and next_completion == now:
            if in_flight_timestamp is None:
                raise RuntimeError("busy processor has no in-flight frame")
            presented.append(in_flight_timestamp)
            if pending_timestamp is None:
                in_flight_timestamp = None
                busy_until = -1
            else:
                in_flight_timestamp = pending_timestamp
                pending_timestamp = None
                busy_until = now + service_ns
        while next_source < len(source_times) and source_times[next_source] == now:
            incoming = source_times[next_source]
            next_source += 1
            if busy_until < 0:
                in_flight_timestamp = incoming
                busy_until = now + service_ns
            else:
                pending_timestamp = incoming
        if now > end_ns + service_ns:
            break
    return presented


class PreviewOwner(str, Enum):
    CAMERA = "CAMERA"
    PROJECT = "PROJECT"


class ProfileCommitResult(str, Enum):
    COMMITTED = "COMMITTED"
    STALE = "STALE"
    INCOHERENT = "INCOHERENT"
    APPLY_FAILED = "APPLY_FAILED"


@dataclass
class NdrProfileGate:
    """Host model of the generation-gated NDR render-profile transaction."""

    expected_generation: int = 0
    committed_generation: int | None = None

    def begin(self, generation: int) -> None:
        if generation <= self.expected_generation:
            raise ValueError("profile generations must advance monotonically")
        self.expected_generation = generation
        self.committed_generation = None

    def commit(
        self,
        packet_generation: int,
        *,
        coherent: bool,
        applied: bool,
    ) -> ProfileCommitResult:
        if packet_generation != self.expected_generation:
            return ProfileCommitResult.STALE
        if not coherent:
            return ProfileCommitResult.INCOHERENT
        if not applied:
            return ProfileCommitResult.APPLY_FAILED
        self.committed_generation = packet_generation
        return ProfileCommitResult.COMMITTED

    def accepts_frame(self, frame_generation: int) -> bool:
        return (
            self.committed_generation is not None
            and self.committed_generation == self.expected_generation
            and frame_generation == self.expected_generation
        )


@dataclass
class PreviewHandoff:
    visible_frame: str
    owner: PreviewOwner = PreviewOwner.CAMERA
    pending_epoch: int | None = None
    committed_epoch: int | None = None

    def begin(self, epoch: int) -> None:
        if epoch <= 0:
            raise ValueError("epoch must be positive")
        self.pending_epoch = epoch

    def present_proven(self, epoch: int, frame: str) -> bool:
        if epoch != self.pending_epoch or not frame:
            return False
        self.visible_frame = frame
        self.owner = PreviewOwner.PROJECT
        self.committed_epoch = epoch
        self.pending_epoch = None
        return True

    def reject(self, epoch: int) -> None:
        if epoch == self.pending_epoch:
            self.pending_epoch = None
        # The last proven visible frame is deliberately retained.


def chronological_history(frame_ids: Iterable[int]) -> list[tuple[int, int | None]]:
    previous: int | None = None
    pairs: list[tuple[int, int | None]] = []
    for frame_id in frame_ids:
        pairs.append((frame_id, previous))
        previous = frame_id
    return pairs


def slot_history(frame_ids: Sequence[int], acquired_slots: Sequence[int]) -> list[tuple[int, int | None]]:
    if len(frame_ids) != len(acquired_slots):
        raise ValueError("frame and slot sequences must have equal length")
    slots: dict[int, int] = {}
    pairs: list[tuple[int, int | None]] = []
    for frame_id, slot in zip(frame_ids, acquired_slots):
        pairs.append((frame_id, slots.get(slot)))
        slots[slot] = frame_id
    return pairs


@dataclass
class WhiteBalanceStabilizer:
    maximum_channel_ratio: float = 1.65
    maximum_confirmation_ratio: float = 1.18
    alpha: float = 0.25
    last: tuple[float, float, float, float] | None = None
    pending_outlier: tuple[float, float, float, float] | None = None
    confirmed_target: tuple[float, float, float, float] | None = None

    def observe(self, gains: Sequence[float]) -> tuple[float, float, float, float] | None:
        if len(gains) != 4 or any(not math.isfinite(v) or v <= 0 for v in gains):
            return self.last
        candidate = tuple(float(v) for v in gains)
        if self.last is None:
            self.last = candidate
            return self.last
        ratio = max(
            max(a, b) / min(a, b)
            for a, b in zip(self.last, candidate)
        )
        if ratio > self.maximum_channel_ratio:
            confirmed_matches = self.confirmed_target is not None and self._matches(
                self.confirmed_target, candidate
            )
            if not confirmed_matches:
                if self.pending_outlier is None or not self._matches(
                    self.pending_outlier, candidate
                ):
                    self.pending_outlier = candidate
                    self.confirmed_target = None
                    return self.last
                self.confirmed_target = candidate
                self.pending_outlier = None
            else:
                self.confirmed_target = candidate
        else:
            self.pending_outlier = None
            self.confirmed_target = None
        self.last = tuple(
            math.exp(math.log(a) + (math.log(b) - math.log(a)) * self.alpha)
            for a, b in zip(self.last, candidate)
        )
        return self.last

    def _matches(
        self,
        first: Sequence[float],
        second: Sequence[float],
    ) -> bool:
        return max(max(a, b) / min(a, b) for a, b in zip(first, second)) <= (
            self.maximum_confirmation_ratio
        )


@dataclass(frozen=True)
class BitratePlan:
    target_bps: int
    floor_bps: int
    ceiling_bps: int
    bits_per_pixel_frame: float


def resolve_bitrate(
    width: int,
    height: int,
    fps: float,
    complexity: float,
    noise: float,
    codec_ceiling_bps: int,
) -> BitratePlan:
    if min(width, height, fps, codec_ceiling_bps) <= 0:
        raise ValueError("bitrate inputs must be positive")
    complexity = min(max(complexity, 0.0), 1.0)
    noise = min(max(noise, 0.0), 1.0)
    # Main10 HEVC cinema envelope. Noise is expensive but deliberately bounded so
    # bitrate is not wasted encoding removable sensor grain.
    bppf = 0.075 + 0.115 * complexity + 0.040 * noise
    raw_target = int(round(width * height * fps * bppf))
    floor = int(round(width * height * fps * 0.070))
    if codec_ceiling_bps < floor:
        raise ValueError(
            "codec ceiling cannot satisfy the strict quality floor without downgrade"
        )
    ceiling = codec_ceiling_bps
    return BitratePlan(min(max(raw_target, floor), ceiling), floor, ceiling, bppf)


def bounded_detail_recovery(
    samples: Sequence[float],
    amount: float,
    maximum_delta: float = 0.035,
) -> list[float]:
    if len(samples) < 3:
        return [min(max(v, 0.0), 1.0) for v in samples]
    amount = min(max(amount, 0.0), 1.0)
    maximum_delta = max(maximum_delta, 0.0)
    output = [min(max(samples[0], 0.0), 1.0)]
    for index in range(1, len(samples) - 1):
        centre = samples[index]
        local_mean = 0.25 * samples[index - 1] + 0.5 * centre + 0.25 * samples[index + 1]
        detail = max(min((centre - local_mean) * amount, maximum_delta), -maximum_delta)
        local_min = min(samples[index - 1], centre, samples[index + 1])
        local_max = max(samples[index - 1], centre, samples[index + 1])
        output.append(min(max(centre + detail, local_min), local_max))
    output.append(min(max(samples[-1], 0.0), 1.0))
    return output


def run_reference_simulation() -> dict[str, object]:
    modes = [
        DeliveryProof(mode, 10, 16, 10, 10, True)
        for mode in (VideoMode.VIDEO, VideoMode.PRO, VideoMode.NDR, VideoMode.LOG)
    ] + [DeliveryProof(VideoMode.RAW, 10, 16, 10, None, True)]
    for proof in modes:
        proof.validate_strict_ten_bit()

    slow = simulate_latest_frame_processor(25.0, 235.0, 12.0)
    fast = simulate_latest_frame_processor(25.0, 32.0, 12.0)
    slow_stats = presentation_stats(slow, 25.0)
    fast_stats = presentation_stats(fast, 25.0)
    return {
        "strict_ten_bit_modes": [mode.mode.value for mode in modes],
        "full_resolution_single_flight_fps": slow_stats.achieved_fps,
        "realtime_budget_fps": fast_stats.achieved_fps,
        "realtime_missing_intervals": fast_stats.missing_intervals,
        "target_frame_budget_ms": 40.0,
        "status": "PASS" if fast_stats.achieved_fps >= 24.9 else "FAIL",
    }


if __name__ == "__main__":
    import json

    print(json.dumps(run_reference_simulation(), indent=2, sort_keys=True))
