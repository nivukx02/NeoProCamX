"""Machine-readable non-downgrade contract for camera and conversion changes."""

from __future__ import annotations

import json
import math
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Mapping


@dataclass(frozen=True)
class QualitySnapshot:
    width: int
    height: int
    target_fps: float
    bitrate_bps: int
    bit_depth: int
    codec_profile_rank: int
    color_primaries: str
    color_transfer: str
    raw_sensor_fidelity: bool
    metadata_fields: frozenset[str] = field(default_factory=frozenset)
    audio_bitrate_bps: int = 0
    audio_sample_rate_hz: int = 0
    audio_channels: int = 0
    detail_profiles: frozenset[str] = field(default_factory=frozenset)
    conversion_quality_rank: int = 0
    recording_features: frozenset[str] = field(default_factory=frozenset)
    security_controls: frozenset[str] = field(default_factory=frozenset)
    prediction_structure: str = ""

    @classmethod
    def from_mapping(cls, value: Mapping[str, Any]) -> "QualitySnapshot":
        set_fields = {
            "metadata_fields",
            "detail_profiles",
            "recording_features",
            "security_controls",
        }
        normalized = dict(value)
        for name in set_fields:
            normalized[name] = frozenset(str(item) for item in value.get(name, ()))
        return cls(**normalized)

    @classmethod
    def from_json(cls, path: Path) -> "QualitySnapshot":
        def reject_constant(value: str) -> None:
            raise ValueError(f"non-finite JSON numeric value is forbidden: {value}")

        return cls.from_mapping(
            json.loads(
                path.read_text(encoding="utf-8"),
                parse_constant=reject_constant,
            )
        )


@dataclass(frozen=True)
class QualityViolation:
    field: str
    baseline: str
    candidate: str
    reason: str


@dataclass(frozen=True)
class QualityGuardVerdict:
    accepted: bool
    violations: tuple[QualityViolation, ...]
    prediction_structure_exception_used: bool


def _violation(field: str, baseline: object, candidate: object, reason: str) -> QualityViolation:
    return QualityViolation(field, str(baseline), str(candidate), reason)


def _snapshot_contract_violations(
    snapshot: QualitySnapshot,
    *,
    baseline: bool,
) -> list[QualityViolation]:
    violations: list[QualityViolation] = []
    positive_fields = (
        "width",
        "height",
        "target_fps",
        "bitrate_bps",
        "bit_depth",
    )
    nonnegative_fields = (
        "codec_profile_rank",
        "audio_bitrate_bps",
        "audio_sample_rate_hz",
        "audio_channels",
        "conversion_quality_rank",
    )
    prefix = "baseline." if baseline else ""
    for name in positive_fields + nonnegative_fields:
        value = getattr(snapshot, name)
        valid_number = (
            not isinstance(value, bool)
            and isinstance(value, (int, float))
            and math.isfinite(float(value))
        )
        valid_range = valid_number and (
            value > 0 if name in positive_fields else value >= 0
        )
        if not valid_number or not valid_range:
            violations.append(
                _violation(
                    prefix + name,
                    "finite positive" if name in positive_fields else "finite nonnegative",
                    value,
                    "quality snapshot field is invalid",
                )
            )
    for name in ("color_primaries", "color_transfer"):
        value = getattr(snapshot, name)
        if not isinstance(value, str) or not value.strip():
            violations.append(
                _violation(prefix + name, "non-empty identity", value, "quality identity is invalid")
            )
    if not isinstance(snapshot.raw_sensor_fidelity, bool):
        violations.append(
            _violation(
                prefix + "raw_sensor_fidelity",
                "boolean",
                snapshot.raw_sensor_fidelity,
                "RAW fidelity flag is invalid",
            )
        )
    return violations


def compare_quality(
    baseline: QualitySnapshot,
    candidate: QualitySnapshot,
    *,
    prediction_change_reason: str = "",
) -> QualityGuardVerdict:
    violations = _snapshot_contract_violations(baseline, baseline=True)
    violations.extend(_snapshot_contract_violations(candidate, baseline=False))
    numeric_floors = (
        ("width", baseline.width, candidate.width),
        ("height", baseline.height, candidate.height),
        ("target_fps", baseline.target_fps, candidate.target_fps),
        ("bitrate_bps", baseline.bitrate_bps, candidate.bitrate_bps),
        ("bit_depth", baseline.bit_depth, candidate.bit_depth),
        ("codec_profile_rank", baseline.codec_profile_rank, candidate.codec_profile_rank),
        ("audio_bitrate_bps", baseline.audio_bitrate_bps, candidate.audio_bitrate_bps),
        ("audio_sample_rate_hz", baseline.audio_sample_rate_hz, candidate.audio_sample_rate_hz),
        ("audio_channels", baseline.audio_channels, candidate.audio_channels),
        (
            "conversion_quality_rank",
            baseline.conversion_quality_rank,
            candidate.conversion_quality_rank,
        ),
    )
    for name, floor, actual in numeric_floors:
        if actual < floor:
            violations.append(_violation(name, floor, actual, "candidate is below quality floor"))

    for name in ("color_primaries", "color_transfer"):
        expected = getattr(baseline, name)
        actual = getattr(candidate, name)
        if actual != expected:
            violations.append(_violation(name, expected, actual, "colour identity changed"))
    if baseline.raw_sensor_fidelity and not candidate.raw_sensor_fidelity:
        violations.append(
            _violation(
                "raw_sensor_fidelity",
                True,
                False,
                "RAW sensor fidelity cannot be disabled or substituted",
            )
        )

    for name in (
        "metadata_fields",
        "detail_profiles",
        "recording_features",
        "security_controls",
    ):
        expected = getattr(baseline, name)
        actual = getattr(candidate, name)
        missing = sorted(expected - actual)
        if missing:
            violations.append(
                _violation(name, sorted(expected), sorted(actual), "missing: " + ", ".join(missing))
            )

    prediction_changed = baseline.prediction_structure != candidate.prediction_structure
    allowed_prediction_change = (
        prediction_changed
        and prediction_change_reason.strip().lower() == "sustainable_realtime"
        and not violations
    )
    if prediction_changed and not allowed_prediction_change:
        violations.append(
            _violation(
                "prediction_structure",
                baseline.prediction_structure,
                candidate.prediction_structure,
                "prediction changes require sustainable_realtime and preserved quality floors",
            )
        )
    return QualityGuardVerdict(
        accepted=not violations,
        violations=tuple(violations),
        prediction_structure_exception_used=allowed_prediction_change,
    )


def snapshot_to_json(snapshot: QualitySnapshot) -> str:
    payload = asdict(snapshot)
    for name in (
        "metadata_fields",
        "detail_profiles",
        "recording_features",
        "security_controls",
    ):
        payload[name] = sorted(payload[name])
    return json.dumps(payload, indent=2, sort_keys=True, allow_nan=False) + "\n"
