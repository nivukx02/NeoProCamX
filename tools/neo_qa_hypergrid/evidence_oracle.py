"""Evidence validation and sealing for NEO-QA HyperGrid.

The oracle deliberately separates requested configuration from measured wire evidence.
It does not infer PASS from a build result, a simulation, or a requested MediaFormat.
"""

from __future__ import annotations

import csv
import hashlib
import json
import math
import os
import re
import tempfile
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Iterable, Mapping, Sequence


VALID_STATUSES = frozenset({"PASS", "FAIL", "BLOCKED", "PENDING"})
VALID_EVIDENCE_KINDS = frozenset({"host", "simulation", "physical_device", "external"})
SHA256_PATTERN = re.compile(r"[0-9A-Fa-f]{64}")
PHYSICAL_ONLY_MEASUREMENTS = frozenset(
    {
        "camera_colour",
        "camera_image_quality",
        "codec_fps",
        "displayed_fps",
        "thermal_behaviour",
        "physical_jank",
        "sensor_noise",
    }
)


def sha256_file(path: Path, chunk_size: int = 1024 * 1024) -> str:
    if chunk_size <= 0:
        raise ValueError("SHA-256 chunk size must be positive")
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while chunk := source.read(chunk_size):
            digest.update(chunk)
    return digest.hexdigest().upper()


def _atomic_write_text(path: Path, payload: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="") as target:
            target.write(payload)
            target.flush()
            os.fsync(target.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


@dataclass(frozen=True)
class CandidateIdentity:
    apk_sha256: str
    installed_apk_sha256: str
    signing_certificate_sha256: str
    build_variant: str
    package_name: str
    version_name: str
    version_code: str

    def validate(self, physical_device_required: bool) -> list[str]:
        errors: list[str] = []
        for name, value in asdict(self).items():
            if not str(value).strip():
                errors.append(f"candidate identity field is empty: {name}")
        if physical_device_required and (
            self.apk_sha256.upper() != self.installed_apk_sha256.upper()
        ):
            errors.append("installed APK SHA-256 does not match the tested candidate")
        for name in ("apk_sha256", "installed_apk_sha256", "signing_certificate_sha256"):
            value = getattr(self, name)
            if physical_device_required and SHA256_PATTERN.fullmatch(value) is None:
                errors.append(f"physical-device {name} must be a concrete SHA-256 value")
            elif value not in {"NOT_APPLICABLE", "UNAVAILABLE"} and (
                SHA256_PATTERN.fullmatch(value) is None
            ):
                errors.append(f"{name} is not a SHA-256 value or an explicit sentinel")
        return errors


@dataclass(frozen=True)
class DeviceIdentity:
    serial: str
    manufacturer: str
    model: str
    android_version: str
    api_level: str
    build_fingerprint: str
    abi: str

    @classmethod
    def host_only(cls) -> "DeviceIdentity":
        return cls(*( ["NOT_APPLICABLE"] * 7 ))

    def validate(self, physical_device_required: bool) -> list[str]:
        if not physical_device_required:
            return []
        invalid_sentinels = {"NOT_APPLICABLE", "UNAVAILABLE"}
        return [
            f"device identity field is empty: {name}"
            for name, value in asdict(self).items()
            if not str(value).strip() or value in invalid_sentinels
        ]


@dataclass(frozen=True)
class CheckpointEvidenceRecord:
    checkpoint_id: str
    status: str
    exact_test: str
    candidate: CandidateIdentity
    device: DeviceIdentity
    measured_result: str
    expected_threshold: str
    evidence_paths: tuple[str, ...]
    root_cause: str = ""
    files_changed: tuple[str, ...] = ()
    focused_validation: str = ""
    regression_validation: str = ""
    remaining_blocker: str = ""
    evidence_kind: str = "host"
    measurement_claims: tuple[str, ...] = ()
    metadata: Mapping[str, str] = field(default_factory=dict)


@dataclass(frozen=True)
class EvidenceValidation:
    accepted: bool
    errors: tuple[str, ...]
    resolved_paths: tuple[str, ...]


class EvidenceOracle:
    def __init__(self, workspace: Path):
        self.workspace = workspace.resolve()

    def _resolve_evidence_path(self, raw_path: str) -> Path:
        candidate = Path(raw_path)
        if not candidate.is_absolute():
            candidate = self.workspace / candidate
        resolved = candidate.resolve()
        try:
            resolved.relative_to(self.workspace)
        except ValueError as exc:
            raise ValueError(f"evidence path escapes workspace: {raw_path}") from exc
        return resolved

    def validate(self, record: CheckpointEvidenceRecord) -> EvidenceValidation:
        errors: list[str] = []
        resolved_paths: list[str] = []
        checkpoint_match = re.fullmatch(r"CP-(\d{3})", record.checkpoint_id)
        if checkpoint_match is None or not 1 <= int(checkpoint_match.group(1)) <= 220:
            errors.append("invalid checkpoint ID")
        if record.status not in VALID_STATUSES:
            errors.append(f"invalid status: {record.status}")
        if record.evidence_kind not in VALID_EVIDENCE_KINDS:
            errors.append(f"invalid evidence kind: {record.evidence_kind}")
        physical = record.evidence_kind == "physical_device"
        errors.extend(record.candidate.validate(physical))
        errors.extend(record.device.validate(physical))

        if record.status in {"PASS", "FAIL"}:
            for name, value in (
                ("exact_test", record.exact_test),
                ("measured_result", record.measured_result),
                ("expected_threshold", record.expected_threshold),
                ("focused_validation", record.focused_validation),
                ("regression_validation", record.regression_validation),
            ):
                if not value.strip():
                    errors.append(f"{record.status} record is missing {name}")
            if not record.evidence_paths:
                errors.append(f"{record.status} record has no evidence paths")
        if record.status == "FAIL" and not record.root_cause.strip():
            errors.append("FAIL record is missing a root cause")
        if record.status in {"BLOCKED", "PENDING"} and not record.remaining_blocker.strip():
            errors.append(f"{record.status} record is missing its blocker/reason")
        if record.evidence_kind != "physical_device":
            invalid_claims = sorted(PHYSICAL_ONLY_MEASUREMENTS.intersection(record.measurement_claims))
            if invalid_claims:
                errors.append(
                    "host/simulation evidence cannot prove physical measurements: "
                    + ", ".join(invalid_claims)
                )

        for raw_path in record.evidence_paths:
            try:
                resolved = self._resolve_evidence_path(raw_path)
            except ValueError as exc:
                errors.append(str(exc))
                continue
            if not resolved.is_file():
                errors.append(f"evidence file does not exist: {raw_path}")
                continue
            if resolved.stat().st_size <= 0:
                errors.append(f"evidence file is empty: {raw_path}")
                continue
            resolved_paths.append(str(resolved.relative_to(self.workspace)).replace("\\", "/"))

        return EvidenceValidation(not errors, tuple(errors), tuple(resolved_paths))

    def seal(
        self,
        record: CheckpointEvidenceRecord,
        output_directory: Path,
    ) -> Path:
        validation = self.validate(record)
        if not validation.accepted:
            raise ValueError("evidence record rejected: " + "; ".join(validation.errors))
        output_directory = output_directory.resolve()
        try:
            output_directory.relative_to(self.workspace)
        except ValueError as exc:
            raise ValueError("sealed output directory must be inside workspace") from exc
        rows = []
        for relative in validation.resolved_paths:
            path = self.workspace / relative
            rows.append(
                {
                    "path": relative,
                    "bytes": path.stat().st_size,
                    "sha256": sha256_file(path),
                }
            )
        record_payload = {
            **asdict(record),
            "evidence_paths": list(record.evidence_paths),
            "files_changed": list(record.files_changed),
            "measurement_claims": list(record.measurement_claims),
            "metadata": dict(sorted(record.metadata.items())),
        }
        record_sha256 = hashlib.sha256(
            json.dumps(
                record_payload,
                ensure_ascii=False,
                separators=(",", ":"),
                sort_keys=True,
            ).encode("utf-8")
        ).hexdigest().upper()
        payload = {
            "schema": "neo-qa-hypergrid/evidence-record/v1",
            "record_sha256": record_sha256,
            "record": record_payload,
            "artifacts": rows,
        }
        output = output_directory / f"{record.checkpoint_id}-evidence.json"
        _atomic_write_text(output, json.dumps(payload, indent=2, sort_keys=True) + "\n")
        return output


def _rational(value: str | int | float | None) -> float:
    if value is None:
        return 0.0
    if isinstance(value, (int, float)):
        result = float(value)
        return result if math.isfinite(result) else 0.0
    text = value.strip()
    if not text or text == "0/0":
        return 0.0
    if "/" in text:
        numerator, denominator = text.split("/", 1)
        denominator_value = float(denominator)
        result = float(numerator) / denominator_value if denominator_value else 0.0
    else:
        result = float(text)
    return result if math.isfinite(result) else 0.0


@dataclass(frozen=True)
class RecordingWireMetrics:
    codec_name: str
    profile: str
    width: int
    height: int
    pixel_format: str
    bit_depth: int
    color_primaries: str
    color_transfer: str
    color_space: str
    stream_fps: float
    measured_fps: float
    duration_seconds: float
    frame_count: int
    timestamped_frame_count: int
    pts_monotonic: bool
    minimum_pts_delta_ms: float
    maximum_pts_delta_ms: float
    average_pts_delta_ms: float
    key_frames: int
    p_frames: int
    b_frames: int
    measured_bitrate_bps: int
    measured_bitrate_source: str
    audio_tracks: int


def _pixel_bit_depth(pixel_format: str) -> int:
    normalized = pixel_format.lower()
    packed = re.search(r"p[0-4](10|12|16)(?:le|be)?(?:$|[^0-9])", normalized)
    if packed:
        return int(packed.group(1))
    planar = re.search(r"p(8|9|10|12|14|16)(?:le|be)?(?:$|[^0-9])", normalized)
    if planar:
        return int(planar.group(1))
    if "12" in normalized:
        return 12
    if "10" in normalized:
        return 10
    return 8 if pixel_format else 0


def _positive_int(value: Any) -> int:
    try:
        parsed = int(value)
    except (TypeError, ValueError):
        return 0
    return parsed if parsed > 0 else 0


def analyze_ffprobe(
    probe: Mapping[str, Any],
    frame_probe: Mapping[str, Any] | None = None,
) -> RecordingWireMetrics:
    streams = list(probe.get("streams", []))
    video = next((stream for stream in streams if stream.get("codec_type") == "video"), {})
    audio_tracks = sum(1 for stream in streams if stream.get("codec_type") == "audio")
    video_stream_index = video.get("index")
    raw_frames = list((frame_probe or {}).get("frames", []))
    frames = []
    for frame in raw_frames:
        media_type = frame.get("media_type")
        if media_type is not None and media_type != "video":
            continue
        frame_stream_index = frame.get("stream_index")
        if (
            video_stream_index is not None
            and frame_stream_index is not None
            and frame_stream_index != video_stream_index
        ):
            continue
        frames.append(frame)
    pts_values: list[float] = []
    key_frames = p_frames = b_frames = 0
    for frame in frames:
        raw_pts = frame.get("best_effort_timestamp_time", frame.get("pts_time"))
        try:
            if raw_pts is not None:
                parsed_pts = float(raw_pts)
                if math.isfinite(parsed_pts):
                    pts_values.append(parsed_pts)
        except (TypeError, ValueError):
            pass
        pict_type = str(frame.get("pict_type", "")).upper()
        key_frames += int(frame.get("key_frame", 0) == 1 or pict_type == "I")
        p_frames += int(pict_type == "P")
        b_frames += int(pict_type == "B")
    deltas = [later - earlier for earlier, later in zip(pts_values, pts_values[1:])]
    positive_deltas = [delta for delta in deltas if delta > 0]
    pts_monotonic = len(deltas) == len(positive_deltas)
    duration = 0.0
    if len(pts_values) > 1:
        duration = pts_values[-1] - pts_values[0]
    if duration <= 0:
        duration = float(video.get("duration") or probe.get("format", {}).get("duration") or 0.0)
    frame_count = len(frames)
    if frame_count == 0:
        try:
            frame_count = int(video.get("nb_read_frames") or video.get("nb_frames") or 0)
        except (TypeError, ValueError):
            frame_count = 0
    measured_fps = (
        (len(pts_values) - 1) / (pts_values[-1] - pts_values[0])
        if len(pts_values) > 1 and pts_values[-1] > pts_values[0]
        else _rational(video.get("avg_frame_rate"))
    )
    pixel_format = str(video.get("pix_fmt", ""))
    declared_depth = _positive_int(video.get("bits_per_raw_sample"))
    stream_bitrate = _positive_int(video.get("bit_rate"))
    packet_bytes = sum(_positive_int(frame.get("pkt_size")) for frame in frames)
    if stream_bitrate:
        measured_bitrate = stream_bitrate
        bitrate_source = "video_stream.bit_rate"
    elif packet_bytes and duration > 0:
        measured_bitrate = int(round(packet_bytes * 8.0 / duration))
        bitrate_source = "video_frame_packet_sizes_over_pts_duration"
    else:
        measured_bitrate = 0
        bitrate_source = "unavailable"
    return RecordingWireMetrics(
        codec_name=str(video.get("codec_name", "")),
        profile=str(video.get("profile", "")),
        width=int(video.get("width") or 0),
        height=int(video.get("height") or 0),
        pixel_format=pixel_format,
        bit_depth=declared_depth or _pixel_bit_depth(pixel_format),
        color_primaries=str(video.get("color_primaries", "")),
        color_transfer=str(video.get("color_transfer", "")),
        color_space=str(video.get("color_space", "")),
        stream_fps=_rational(video.get("avg_frame_rate")),
        measured_fps=measured_fps,
        duration_seconds=max(duration, 0.0),
        frame_count=frame_count,
        timestamped_frame_count=len(pts_values),
        pts_monotonic=pts_monotonic,
        minimum_pts_delta_ms=min(positive_deltas, default=0.0) * 1000.0,
        maximum_pts_delta_ms=max(positive_deltas, default=0.0) * 1000.0,
        average_pts_delta_ms=(
            sum(positive_deltas) / len(positive_deltas) * 1000.0 if positive_deltas else 0.0
        ),
        key_frames=key_frames,
        p_frames=p_frames,
        b_frames=b_frames,
        measured_bitrate_bps=measured_bitrate,
        measured_bitrate_source=bitrate_source,
        audio_tracks=audio_tracks,
    )


@dataclass(frozen=True)
class RecordingThresholds:
    width: int
    height: int
    minimum_bit_depth: int
    target_fps: float
    fps_tolerance_ratio: float = 0.05
    maximum_pts_delta_ratio: float = 1.50
    require_p_frames: bool = False
    require_audio: bool | None = None
    required_codec: str = "hevc"
    required_transfer: str = ""
    required_primaries: str = ""
    required_profile_contains: str = ""
    minimum_timestamped_frames: int = 8
    minimum_measured_bitrate_bps: int = 0
    maximum_measured_bitrate_bps: int = 0


def verify_recording_wire(
    metrics: RecordingWireMetrics,
    thresholds: RecordingThresholds,
) -> tuple[str, ...]:
    numeric_thresholds = (
        ("width", thresholds.width),
        ("height", thresholds.height),
        ("minimum_bit_depth", thresholds.minimum_bit_depth),
        ("target_fps", thresholds.target_fps),
        ("minimum_timestamped_frames", thresholds.minimum_timestamped_frames),
    )
    if any(
        isinstance(value, bool)
        or not isinstance(value, (int, float))
        or not math.isfinite(float(value))
        or value <= 0
        for _, value in numeric_thresholds
    ):
        raise ValueError("recording thresholds must be finite and positive")
    if (
        not math.isfinite(thresholds.fps_tolerance_ratio)
        or not 0.0 <= thresholds.fps_tolerance_ratio < 1.0
        or not math.isfinite(thresholds.maximum_pts_delta_ratio)
        or thresholds.maximum_pts_delta_ratio <= 0.0
    ):
        raise ValueError("recording cadence tolerances are invalid")
    failures: list[str] = []
    if metrics.codec_name.lower() != thresholds.required_codec.lower():
        failures.append(f"codec {metrics.codec_name!r} != {thresholds.required_codec!r}")
    if (metrics.width, metrics.height) != (thresholds.width, thresholds.height):
        failures.append(
            f"resolution {metrics.width}x{metrics.height} != "
            f"{thresholds.width}x{thresholds.height}"
        )
    if metrics.bit_depth < thresholds.minimum_bit_depth:
        failures.append(f"bit depth {metrics.bit_depth} < {thresholds.minimum_bit_depth}")
    if thresholds.required_profile_contains and (
        thresholds.required_profile_contains.lower() not in metrics.profile.lower()
    ):
        failures.append(
            f"profile {metrics.profile!r} does not contain "
            f"{thresholds.required_profile_contains!r}"
        )
    if metrics.timestamped_frame_count < thresholds.minimum_timestamped_frames:
        failures.append(
            f"timestamped frame evidence {metrics.timestamped_frame_count} < "
            f"{thresholds.minimum_timestamped_frames}"
        )
    minimum_fps = thresholds.target_fps * (1.0 - thresholds.fps_tolerance_ratio)
    maximum_fps = thresholds.target_fps * (1.0 + thresholds.fps_tolerance_ratio)
    if metrics.measured_fps < minimum_fps:
        failures.append(f"measured FPS {metrics.measured_fps:.6f} < {minimum_fps:.6f}")
    if metrics.measured_fps > maximum_fps:
        failures.append(f"measured FPS {metrics.measured_fps:.6f} > {maximum_fps:.6f}")
    expected_delta_ms = 1000.0 / thresholds.target_fps
    if metrics.maximum_pts_delta_ms > expected_delta_ms * thresholds.maximum_pts_delta_ratio:
        failures.append(
            f"maximum PTS delta {metrics.maximum_pts_delta_ms:.3f}ms exceeds "
            f"{expected_delta_ms * thresholds.maximum_pts_delta_ratio:.3f}ms"
        )
    if not metrics.pts_monotonic:
        failures.append("frame PTS is not strictly monotonic")
    if thresholds.require_p_frames and metrics.p_frames <= 0:
        failures.append("no P-frames were observed")
    if thresholds.require_audio is True and metrics.audio_tracks <= 0:
        failures.append("required audio track is absent")
    if thresholds.require_audio is False and metrics.audio_tracks != 0:
        failures.append("audio track is present in the video-only branch")
    if thresholds.required_transfer and metrics.color_transfer != thresholds.required_transfer:
        failures.append(
            f"transfer {metrics.color_transfer!r} != {thresholds.required_transfer!r}"
        )
    if thresholds.required_primaries and metrics.color_primaries != thresholds.required_primaries:
        failures.append(
            f"primaries {metrics.color_primaries!r} != {thresholds.required_primaries!r}"
        )
    if (
        thresholds.minimum_measured_bitrate_bps > 0
        and metrics.measured_bitrate_bps < thresholds.minimum_measured_bitrate_bps
    ):
        failures.append(
            f"measured bitrate {metrics.measured_bitrate_bps} < "
            f"{thresholds.minimum_measured_bitrate_bps}"
        )
    if (
        thresholds.maximum_measured_bitrate_bps > 0
        and metrics.measured_bitrate_bps > thresholds.maximum_measured_bitrate_bps
    ):
        failures.append(
            f"measured bitrate {metrics.measured_bitrate_bps} > "
            f"{thresholds.maximum_measured_bitrate_bps}"
        )
    return tuple(failures)


@dataclass(frozen=True)
class RuntimeLogFindings:
    crash_count: int
    anr_count: int
    fatal_exception_count: int
    raw_owned_peak: int
    raw_capacity: int
    raw_capacity_abort_count: int
    dropped_frame_count: int
    encoder_cadence_statuses: tuple[str, ...]


def analyze_runtime_log(text: str) -> RuntimeLogFindings:
    ownership = [
        (int(owned), int(capacity))
        for owned, capacity in re.findall(
            r"owned=(\d+).*?(?:limit|maxImages)=(\d+)", text, flags=re.IGNORECASE
        )
    ]
    drops = [int(value) for value in re.findall(r"(?:droppedFrames|processingDroppedFrames)=(\d+)", text)]
    statuses = tuple(re.findall(r"encoderCadenceStatus=([A-Z_]+)", text))
    return RuntimeLogFindings(
        crash_count=len(re.findall(r"\bFATAL EXCEPTION\b", text)),
        anr_count=len(re.findall(r"\bANR in\b|NOT RESPONDING", text, flags=re.IGNORECASE)),
        fatal_exception_count=len(re.findall(r"\bFatal signal\b|\bFATAL EXCEPTION\b", text)),
        raw_owned_peak=max((owned for owned, _ in ownership), default=0),
        raw_capacity=max((capacity for _, capacity in ownership), default=0),
        raw_capacity_abort_count=len(re.findall(r"NDR_RECORD_INGRESS_FAILURE", text)),
        dropped_frame_count=max(drops, default=0),
        encoder_cadence_statuses=statuses,
    )


def write_sha256_manifest(paths: Iterable[Path], workspace: Path, output: Path) -> None:
    workspace = workspace.resolve()
    output = output.resolve()
    try:
        output.relative_to(workspace)
    except ValueError as exc:
        raise ValueError("SHA-256 manifest output escapes workspace") from exc
    rows = []
    for path in sorted({item.resolve() for item in paths}, key=lambda item: str(item).lower()):
        try:
            relative = path.relative_to(workspace).as_posix()
        except ValueError as exc:
            raise ValueError(f"manifest input escapes workspace: {path}") from exc
        if not path.is_file():
            raise ValueError(f"manifest input is not a file: {path}")
        rows.append((relative, path.stat().st_size, sha256_file(path)))
    lines = ["path,bytes,sha256"]
    lines.extend(
        f'"{relative.replace(chr(34), chr(34) * 2)}",{size},{digest}'
        for relative, size, digest in rows
    )
    _atomic_write_text(output, "\n".join(lines) + "\n")
