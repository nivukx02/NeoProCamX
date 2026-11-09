"""Classify every frame of an LQ-022 aspect-transition screen recording.

This oracle deliberately measures only the visible preview aperture.  It can prove that
the app did not present an intermediate crop, blank preview, or unstable aperture while
switching ratios.  It cannot prove camera-frame cadence or content-level optical warp;
those require camera telemetry and a calibrated grid target respectively.
"""

from __future__ import annotations

import argparse
import json
import subprocess
from collections import Counter
from pathlib import Path
from typing import BinaryIO

import numpy as np


EXPECTED_ASPECTS = {
    "SQUARE": 1.0,
    "FOUR_THREE": 3.0 / 4.0,
    "SIXTEEN_NINE": 9.0 / 16.0,
}
EXPECTED_SEQUENCE = [
    "SQUARE",
    "FOUR_THREE",
    "SIXTEEN_NINE",
    "FOUR_THREE",
    "SQUARE",
]


def longest_contiguous_run(indices: np.ndarray) -> tuple[int, int] | None:
    if indices.size == 0:
        return None
    start = previous = int(indices[0])
    runs: list[tuple[int, int]] = []
    for raw_index in indices[1:]:
        index = int(raw_index)
        if index != previous + 1:
            runs.append((start, previous))
            start = index
        previous = index
    runs.append((start, previous))
    return max(runs, key=lambda run: run[1] - run[0])


def read_exact(stream: BinaryIO, size: int) -> bytes:
    chunks: list[bytes] = []
    remaining = size
    while remaining:
        chunk = stream.read(remaining)
        if not chunk:
            break
        chunks.append(chunk)
        remaining -= len(chunk)
    return b"".join(chunks)


def probe_frame_pts(ffprobe: Path, clip: Path) -> list[float]:
    result = subprocess.run(
        [
            str(ffprobe),
            "-v",
            "error",
            "-select_streams",
            "v:0",
            "-show_entries",
            "frame=best_effort_timestamp_time",
            "-of",
            "csv=p=0",
            str(clip),
        ],
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
    )
    return [float(line.strip().split(",")[0]) for line in result.stdout.splitlines() if line.strip()]


def classify_frame(
    frame: np.ndarray,
    *,
    pixel_threshold: int,
    edge_band_fraction: float,
    row_coverage_threshold: float,
    aspect_tolerance: float,
) -> dict[str, object]:
    active = frame > pixel_threshold
    # Measure only the outer image bands.  Status chips and capture controls are
    # centred overlays and can momentarily bridge a black matte to the preview
    # during relayout; treating those overlays as image pixels creates a false
    # intermediate aperture.  The camera image itself spans both outer bands.
    edge_band_width = max(1, int(round(frame.shape[1] * edge_band_fraction)))
    edge_active = np.concatenate(
        (active[:, :edge_band_width], active[:, -edge_band_width:]), axis=1
    )
    row_coverage = edge_active.mean(axis=1)
    run = longest_contiguous_run(np.flatnonzero(row_coverage > row_coverage_threshold))
    p95 = float(np.percentile(frame, 95))
    active_fraction = float(active.mean())
    if run is None:
        return {
            "label": "INVALID",
            "reason": "no measurable preview rows",
            "p95_luma": p95,
            "active_fraction": active_fraction,
        }

    top, bottom = run
    height = bottom - top + 1
    measured_aspect = frame.shape[1] / height
    label, error = min(
        (
            (name, abs(measured_aspect - expected))
            for name, expected in EXPECTED_ASPECTS.items()
        ),
        key=lambda item: item[1],
    )
    blank = p95 < 10.0 or active_fraction < 0.12
    valid = error <= aspect_tolerance and not blank
    return {
        "label": label if valid else "INVALID",
        "nearest_label": label,
        "reason": None if valid else ("blank frame" if blank else "unexpected aperture"),
        "top_px": top,
        "bottom_exclusive_px": bottom + 1,
        "height_px": height,
        "measured_aspect": measured_aspect,
        "aspect_error": error,
        "p95_luma": p95,
        "active_fraction": active_fraction,
    }


def collapse_runs(labels: list[str]) -> list[dict[str, object]]:
    if not labels:
        return []
    runs: list[dict[str, object]] = []
    start = 0
    current = labels[0]
    for index, label in enumerate(labels[1:], start=1):
        if label != current:
            runs.append({"label": current, "first_frame": start, "last_frame": index - 1})
            start = index
            current = label
    runs.append({"label": current, "first_frame": start, "last_frame": len(labels) - 1})
    return runs


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("clip", type=Path)
    parser.add_argument("--ffmpeg", type=Path, required=True)
    parser.add_argument("--ffprobe", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--decode-width", type=int, default=315)
    parser.add_argument("--decode-height", type=int, default=700)
    parser.add_argument("--pixel-threshold", type=int, default=2)
    parser.add_argument("--edge-band-fraction", type=float, default=0.10)
    parser.add_argument("--row-coverage-threshold", type=float, default=0.10)
    parser.add_argument("--aspect-tolerance", type=float, default=0.006)
    parser.add_argument("--max-pts-gap-ms", type=float, default=250.0)
    parser.add_argument("--max-boundary-jitter-px", type=int, default=2)
    args = parser.parse_args()

    if not 0.0 < args.edge_band_fraction <= 0.5:
        parser.error("--edge-band-fraction must be in (0, 0.5]")

    if not args.clip.is_file():
        raise FileNotFoundError(args.clip)
    if not args.ffmpeg.is_file():
        raise FileNotFoundError(args.ffmpeg)
    if not args.ffprobe.is_file():
        raise FileNotFoundError(args.ffprobe)

    pts = probe_frame_pts(args.ffprobe, args.clip)
    frame_bytes = args.decode_width * args.decode_height
    command = [
        str(args.ffmpeg),
        "-v",
        "error",
        "-i",
        str(args.clip),
        "-map",
        "0:v:0",
        "-vf",
        f"scale={args.decode_width}:{args.decode_height}:flags=area,format=gray",
        "-fps_mode",
        "passthrough",
        "-f",
        "rawvideo",
        "pipe:1",
    ]
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    assert process.stdout is not None
    assert process.stderr is not None

    frames: list[dict[str, object]] = []
    while True:
        payload = read_exact(process.stdout, frame_bytes)
        if not payload:
            break
        if len(payload) != frame_bytes:
            process.kill()
            raise RuntimeError(f"truncated decoded frame: {len(payload)} of {frame_bytes} bytes")
        frame = np.frombuffer(payload, dtype=np.uint8).reshape(
            (args.decode_height, args.decode_width)
        )
        measured = classify_frame(
            frame,
            pixel_threshold=args.pixel_threshold,
            edge_band_fraction=args.edge_band_fraction,
            row_coverage_threshold=args.row_coverage_threshold,
            aspect_tolerance=args.aspect_tolerance,
        )
        frame_index = len(frames)
        measured["frame"] = frame_index
        measured["pts_seconds"] = pts[frame_index] if frame_index < len(pts) else None
        frames.append(measured)

    stderr = process.stderr.read().decode("utf-8", errors="replace")
    return_code = process.wait()
    if return_code != 0:
        raise RuntimeError(f"ffmpeg failed with exit {return_code}: {stderr.strip()}")

    labels = [str(frame["label"]) for frame in frames]
    runs = collapse_runs(labels)
    observed_sequence = [str(run["label"]) for run in runs]
    invalid_frames = [int(frame["frame"]) for frame in frames if frame["label"] == "INVALID"]

    positions: dict[str, list[tuple[int, int]]] = {name: [] for name in EXPECTED_ASPECTS}
    for frame in frames:
        label = str(frame["label"])
        if label in positions:
            positions[label].append(
                (int(frame["top_px"]), int(frame["bottom_exclusive_px"]))
            )
    boundary_summary: dict[str, dict[str, object]] = {}
    boundary_stable = True
    for label, pairs in positions.items():
        if not pairs:
            boundary_summary[label] = {"frame_count": 0, "stable": False}
            boundary_stable = False
            continue
        canonical, canonical_count = Counter(pairs).most_common(1)[0]
        max_jitter = max(
            max(abs(top - canonical[0]), abs(bottom - canonical[1]))
            for top, bottom in pairs
        )
        stable = max_jitter <= args.max_boundary_jitter_px
        boundary_stable = boundary_stable and stable
        boundary_summary[label] = {
            "frame_count": len(pairs),
            "canonical_top_px": canonical[0],
            "canonical_bottom_exclusive_px": canonical[1],
            "canonical_count": canonical_count,
            "max_boundary_jitter_px": max_jitter,
            "stable": stable,
        }

    pts_monotonic = len(pts) == len(frames) and all(
        later > earlier for earlier, later in zip(pts, pts[1:])
    )
    gaps_ms = [(later - earlier) * 1000.0 for earlier, later in zip(pts, pts[1:])]
    max_pts_gap_ms = max(gaps_ms, default=0.0)
    cadence_ok = pts_monotonic and max_pts_gap_ms <= args.max_pts_gap_ms
    sequence_ok = observed_sequence == EXPECTED_SEQUENCE
    passed = (
        bool(frames)
        and not invalid_frames
        and sequence_ok
        and boundary_stable
        and cadence_ok
    )

    report = {
        "oracle": "LQ22_TEMPORAL_VISIBLE_APERTURE_V1",
        "scope": {
            "proves": [
                "every encoded screen frame has a recognized non-blank preview aperture",
                "aspect changes use only the requested old/new aperture geometries",
                "visible aperture boundaries remain stable for each selected ratio",
            ],
            "does_not_prove": [
                "camera sensor or final-present FPS",
                "content-level optical distortion or temporal wobble without a calibrated grid",
                "native Vulkan execution when runtime telemetry selects GL fallback",
            ],
        },
        "clip": str(args.clip),
        "decode_size": [args.decode_width, args.decode_height],
        "thresholds": {
            "pixel_active_when_luma_gt": args.pixel_threshold,
            "outer_band_fraction_per_side": args.edge_band_fraction,
            "row_active_when_fraction_gt": args.row_coverage_threshold,
            "aspect_tolerance": args.aspect_tolerance,
            "max_pts_gap_ms": args.max_pts_gap_ms,
            "max_boundary_jitter_px": args.max_boundary_jitter_px,
        },
        "passed": passed,
        "frame_count": len(frames),
        "pts_count": len(pts),
        "invalid_frame_count": len(invalid_frames),
        "invalid_frames": invalid_frames,
        "expected_sequence": EXPECTED_SEQUENCE,
        "observed_sequence": observed_sequence,
        "runs": runs,
        "sequence_ok": sequence_ok,
        "pts_monotonic": pts_monotonic,
        "max_pts_gap_ms": max_pts_gap_ms,
        "cadence_ok": cadence_ok,
        "boundary_stable": boundary_stable,
        "boundary_summary": boundary_summary,
        "frames": frames,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: report[key] for key in (
        "passed",
        "frame_count",
        "invalid_frame_count",
        "expected_sequence",
        "observed_sequence",
        "pts_monotonic",
        "max_pts_gap_ms",
        "cadence_ok",
        "boundary_stable",
        "boundary_summary",
    )}, indent=2))
    return 0 if passed else 1


if __name__ == "__main__":
    raise SystemExit(main())
