#!/usr/bin/env python3
"""Deterministic simulation of NeoProCamX playback scheduling and UI contracts.

This host model validates the player logic that does not require Android hardware:

* source aspect ratio is preserved with letterboxing only where necessary;
* left/right double taps seek exactly ten seconds and clamp to the clip bounds;
* timeline time and progress remain coherent after arbitrary seek sequences;
* sustainable decoder workloads preserve cadence through bounded latency spikes;
* impossible workloads report missed frames instead of fabricating zero-drop results;
* every app-owned video family resolves to a truthful direct or prepared route.

It does not emulate MediaCodec, storage, HDR display composition, or device thermal
behavior. Those require an installed build and real files on an adb target.
"""

from __future__ import annotations

import argparse
import json
import math
from dataclasses import asdict, dataclass
from enum import Enum
from typing import Iterable, Sequence


DOUBLE_TAP_SEEK_MS = 10_000


class PlaybackFamily(str, Enum):
    SDR_MOV = "SDR_MOV"
    NDR_MOV = "NDR_MOV"
    LOG_MOV = "LOG_MOV"
    HEVC_MAIN10_MOV = "HEVC_MAIN10_MOV"
    UNCOMPRESSED_R210_MOV = "UNCOMPRESSED_R210_MOV"
    UNCOMPRESSED_V210_MOV = "UNCOMPRESSED_V210_MOV"
    NIVUKX = "NIVUKX"
    NPV10 = "NPV10"
    NPRAW = "NPRAW"


class PlaybackRoute(str, Enum):
    MEDIA3_HARDWARE = "MEDIA3_HARDWARE"
    NIVUKX_EXTRACTOR_HARDWARE = "NIVUKX_EXTRACTOR_HARDWARE"
    DIRECT_PRIVATE_MASTER_DEADLINE = "DIRECT_PRIVATE_MASTER_DEADLINE"
    DEVICE_CODEC_REQUIRED = "DEVICE_CODEC_REQUIRED"


def resolve_playback_route(family: PlaybackFamily) -> PlaybackRoute:
    if family is PlaybackFamily.NIVUKX:
        return PlaybackRoute.NIVUKX_EXTRACTOR_HARDWARE
    if family in (PlaybackFamily.NPV10, PlaybackFamily.NPRAW):
        return PlaybackRoute.DIRECT_PRIVATE_MASTER_DEADLINE
    if family in (
        PlaybackFamily.UNCOMPRESSED_R210_MOV,
        PlaybackFamily.UNCOMPRESSED_V210_MOV,
    ):
        # Android codec support for r210/v210 cannot be assumed. The player must
        # admit it only after querying the actual device decoder catalogue.
        return PlaybackRoute.DEVICE_CODEC_REQUIRED
    return PlaybackRoute.MEDIA3_HARDWARE


@dataclass(frozen=True)
class AspectFit:
    left: int
    top: int
    width: int
    height: int

    @property
    def right(self) -> int:
        return self.left + self.width

    @property
    def bottom(self) -> int:
        return self.top + self.height


def aspect_fit(content_width: int, content_height: int, viewport_width: int, viewport_height: int) -> AspectFit:
    if min(content_width, content_height, viewport_width, viewport_height) <= 0:
        raise ValueError("aspect-fit dimensions must be positive")
    scale = min(viewport_width / content_width, viewport_height / content_height)
    width = max(1, min(viewport_width, round(content_width * scale)))
    height = max(1, min(viewport_height, round(content_height * scale)))
    return AspectFit(
        left=(viewport_width - width) // 2,
        top=(viewport_height - height) // 2,
        width=width,
        height=height,
    )


def relative_seek(position_ms: int, duration_ms: int, delta_ms: int) -> int:
    if duration_ms < 0:
        raise ValueError("duration must not be negative")
    upper = duration_ms if duration_ms > 0 else (1 << 63) - 1
    return min(max(max(position_ms, 0) + delta_ms, 0), upper)


def timeline_fraction(position_ms: int, duration_ms: int) -> float:
    if duration_ms <= 0:
        return 0.0
    return min(max(position_ms / duration_ms, 0.0), 1.0)


@dataclass(frozen=True)
class DecodeScenario:
    name: str
    fps: float
    duration_seconds: float
    base_decode_ms: float
    initial_buffer_ms: float = 350.0
    transient_spikes: tuple[tuple[int, float], ...] = ()

    @property
    def frame_budget_ms(self) -> float:
        return 1000.0 / self.fps


@dataclass(frozen=True)
class PlaybackCadence:
    scenario: str
    target_fps: float
    source_frames: int
    presented_frames: int
    late_frames: int
    achieved_fps: float
    maximum_decode_lateness_ms: float
    maximum_consecutive_late_frames: int

    @property
    def zero_drop(self) -> bool:
        return self.late_frames == 0 and self.presented_frames == self.source_frames


def simulate_decode_deadlines(scenario: DecodeScenario) -> PlaybackCadence:
    if min(scenario.fps, scenario.duration_seconds, scenario.base_decode_ms) <= 0:
        raise ValueError("decode scenario values must be positive")
    frame_count = max(1, math.floor(scenario.duration_seconds * scenario.fps))
    interval_ms = scenario.frame_budget_ms
    spike_map = dict(scenario.transient_spikes)
    decoder_available_ms = 0.0
    late_frames = 0
    max_lateness = 0.0
    consecutive_late = 0
    max_consecutive_late = 0
    for frame_index in range(frame_count):
        arrival_ms = frame_index * interval_ms
        decode_start_ms = max(arrival_ms, decoder_available_ms)
        service_ms = scenario.base_decode_ms + spike_map.get(frame_index, 0.0)
        decoder_available_ms = decode_start_ms + service_ms
        presentation_deadline_ms = scenario.initial_buffer_ms + frame_index * interval_ms
        lateness_ms = decoder_available_ms - presentation_deadline_ms
        max_lateness = max(max_lateness, lateness_ms)
        if lateness_ms > 0.0:
            late_frames += 1
            consecutive_late += 1
            max_consecutive_late = max(max_consecutive_late, consecutive_late)
        else:
            consecutive_late = 0
    presented = frame_count - late_frames
    achieved = presented / scenario.duration_seconds
    return PlaybackCadence(
        scenario=scenario.name,
        target_fps=scenario.fps,
        source_frames=frame_count,
        presented_frames=presented,
        late_frames=late_frames,
        achieved_fps=achieved,
        maximum_decode_lateness_ms=max(0.0, max_lateness),
        maximum_consecutive_late_frames=max_consecutive_late,
    )


def sustainable_matrix() -> list[DecodeScenario]:
    scenarios: list[DecodeScenario] = []
    for fps in (24.0, 25.0, 30.0, 48.0, 50.0, 60.0, 120.0):
        budget = 1000.0 / fps
        scenarios.append(
            DecodeScenario(
                name=f"sustainable-{int(fps)}fps",
                fps=fps,
                duration_seconds=30.0,
                base_decode_ms=budget * 0.42,
                # Reserve at least ten source-frame budgets. A fixed millisecond value
                # under-protects low-FPS footage from the same frame-count spike.
                initial_buffer_ms=max(350.0, budget * 10.0),
                transient_spikes=(
                    (int(fps * 7), budget * 5.0),
                    (int(fps * 19), budget * 8.0),
                ),
            )
        )
    return scenarios


def verify_seek_sequence(duration_ms: int, start_ms: int, deltas_ms: Iterable[int]) -> int:
    position = start_ms
    for delta in deltas_ms:
        position = relative_seek(position, duration_ms, delta)
        fraction = timeline_fraction(position, duration_ms)
        if not 0.0 <= fraction <= 1.0:
            raise AssertionError("timeline escaped normalized bounds")
    return position


def run_reference_simulation() -> dict[str, object]:
    sustainable = [simulate_decode_deadlines(case) for case in sustainable_matrix()]
    impossible = simulate_decode_deadlines(
        DecodeScenario(
            name="truth-gate-unsustainable-60fps",
            fps=60.0,
            duration_seconds=10.0,
            base_decode_ms=22.0,
            initial_buffer_ms=100.0,
        )
    )
    routes = {family.value: resolve_playback_route(family).value for family in PlaybackFamily}
    aspect_cases = {
        "16:9-in-portrait": asdict(aspect_fit(3840, 2160, 1080, 2400)),
        "9:16-in-landscape": asdict(aspect_fit(1080, 1920, 2400, 1080)),
        "scope-in-landscape": asdict(aspect_fit(4096, 1716, 2400, 1080)),
    }
    seek_end = verify_seek_sequence(
        duration_ms=72_000,
        start_ms=2_000,
        deltas_ms=(
            -DOUBLE_TAP_SEEK_MS,
            DOUBLE_TAP_SEEK_MS,
            DOUBLE_TAP_SEEK_MS,
            DOUBLE_TAP_SEEK_MS,
            DOUBLE_TAP_SEEK_MS,
            DOUBLE_TAP_SEEK_MS,
            DOUBLE_TAP_SEEK_MS,
            DOUBLE_TAP_SEEK_MS,
            DOUBLE_TAP_SEEK_MS,
        ),
    )
    return {
        "status": "PASS"
        if all(result.zero_drop for result in sustainable) and not impossible.zero_drop
        else "FAIL",
        "sustainable_cadence": [asdict(result) for result in sustainable],
        "truth_gate_unsustainable": asdict(impossible),
        "routes": routes,
        "aspect_fit": aspect_cases,
        "seek_sequence_final_ms": seek_end,
        "limitations": [
            "Host simulation does not execute MediaCodec or GPU rendering.",
            "r210/v210 direct playback remains device-codec dependent.",
            "RAW/NPV10 use the read-only private-master frame player; no MOV is generated on tap.",
        ],
    }


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", help="optional JSON report path")
    args = parser.parse_args(argv)
    report = run_reference_simulation()
    encoded = json.dumps(report, indent=2, sort_keys=True)
    print(encoded)
    if args.output:
        from pathlib import Path

        output = Path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(encoded + "\n", encoding="utf-8")
    return 0 if report["status"] == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main())
