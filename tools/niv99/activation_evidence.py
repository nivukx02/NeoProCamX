from __future__ import annotations

import argparse
import hashlib
import json
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping, Sequence


SCHEMA = "NIV99_ACTIVATION_EVIDENCE_MANIFEST_V1"
REPORT_SCHEMA = "NIV99_ACTIVATION_READINESS_REPORT_V1"
CERTIFICATE_SCHEMA_VERSION = 1
NATIVE_ABI_VERSION = 1

CERTIFICATE_FIELDS = (
    "schemaVersion",
    "certificateId",
    "evidenceManifestSha256",
    "appBuildFingerprint",
    "shaderGraphSha256",
    "nativeLibrarySha256",
    "nativeAbiVersion",
    "deviceBuildFingerprint",
    "gpuDriverFingerprint",
    "cameraId",
    "target",
    "operatingMode",
    "signalDomain",
    "width",
    "height",
    "targetFps",
    "colorProfileId",
    "issuedAtEpochMs",
    "expiresAtEpochMs",
    "passedEvidenceMask",
    "shaderComparedValueCount",
    "shaderMaximumAbsoluteError",
    "physicalDeviceFrameCount",
    "controlledSceneCount",
    "medianFrameTimeNs",
    "p95FrameTimeNs",
    "p99FrameTimeNs",
    "maximumFrameTimeNs",
    "frameDeadlineNs",
    "deadlineMissCount",
    "maximumResidentBytes",
    "certifiedMemoryBudgetBytes",
    "thermalRunDurationNs",
    "mtf50Retention",
    "clippingIncrease",
    "clippingTolerance",
    "awbStabilityImprovement",
    "denoiseQualityImprovement",
    "unexplainedRecordingFrameLoss",
    "nonMonotonicTimestampCount",
    "temporalHistoryLeakCount",
    "visibleTileSeamCount",
    "rawByteMismatchCount",
    "conversionIntegrityFailureCount",
    "longRecordingDurationNs",
)

EVIDENCE_ORDER = (
    "HOST_UNIT_TESTS",
    "ANDROID_ADAPTER_COMPILE",
    "NATIVE_WARNING_CLEAN",
    "SHADER_REPRODUCIBLE",
    "HOST_VULKAN_PARITY",
    "ANDROID_VULKAN_PARITY",
    "MANUAL_WB_AUTHORITY",
    "AWB_LOCK_AUTHORITY",
    "METADATA_ORDER_FUZZ",
    "DEVICE_INTEGRATION",
    "QUALITY_NON_DOWNGRADE",
    "PERFORMANCE_CHARACTERIZED",
    "REALTIME_DEADLINE",
    "MEMORY_BOUNDED",
    "THERMAL_ENDURANCE",
    "LIFECYCLE_RESOURCE_SAFETY",
    "PREVIEW_RECORDING_PARITY",
    "RAW_BIT_EXACT",
    "CONVERSION_REGRESSION",
    "LONG_RECORDING",
    "PHOTO_FUSION",
    "NO_TILE_SEAMS",
    "NO_HISTORY_LEAK",
)

COMMON_HOST_EVIDENCE = frozenset(
    {
        "HOST_UNIT_TESTS",
        "ANDROID_ADAPTER_COMPILE",
        "NATIVE_WARNING_CLEAN",
        "SHADER_REPRODUCIBLE",
        "HOST_VULKAN_PARITY",
        "MANUAL_WB_AUTHORITY",
        "AWB_LOCK_AUTHORITY",
        "METADATA_ORDER_FUZZ",
    }
)
COMMON_DEVICE_EVIDENCE = frozenset(
    {
        "ANDROID_VULKAN_PARITY",
        "DEVICE_INTEGRATION",
        "QUALITY_NON_DOWNGRADE",
        "PERFORMANCE_CHARACTERIZED",
        "MEMORY_BOUNDED",
        "LIFECYCLE_RESOURCE_SAFETY",
        "NO_TILE_SEAMS",
        "NO_HISTORY_LEAK",
    }
)
REALTIME_EVIDENCE = frozenset({"REALTIME_DEADLINE", "THERMAL_ENDURANCE"})
RECORDING_EVIDENCE = frozenset({"PREVIEW_RECORDING_PARITY", "LONG_RECORDING"})
CONVERSION_EVIDENCE = frozenset({"RAW_BIT_EXACT", "CONVERSION_REGRESSION"})

TARGET_MODES = {
    "VIDEO_PREVIEW": frozenset({"VIDEO"}),
    "VIDEO_RECORDING": frozenset({"VIDEO"}),
    "PRO_PREVIEW": frozenset({"PRO"}),
    "PRO_RECORDING": frozenset({"PRO"}),
    "RAW_PREVIEW": frozenset({"RAW_PREVIEW"}),
    "PHOTO": frozenset({"PHOTO"}),
    "CONVERSION": frozenset(
        {
            "CONVERSION_RAW_TO_LOG",
            "CONVERSION_RAW_TO_RAW_DEVELOPED",
            "CONVERSION_RAW_TO_XDR10",
            "CONVERSION_RAW_TO_NIVUKX",
        }
    ),
}
REALTIME_TARGETS = frozenset(
    {
        "VIDEO_PREVIEW",
        "VIDEO_RECORDING",
        "PRO_PREVIEW",
        "PRO_RECORDING",
        "RAW_PREVIEW",
    }
)
RECORDING_TARGETS = frozenset({"VIDEO_RECORDING", "PRO_RECORDING"})
RAW_DOMAINS = frozenset({"SENSOR_LINEAR_RAW"})
SHA256_LENGTH = 64
MINIMUM_SHADER_VALUES = 1
MAXIMUM_SHADER_ABSOLUTE_ERROR = 0.0015
MINIMUM_PHYSICAL_FRAMES = 1_000
MINIMUM_CONTROLLED_SCENES = 8
MINIMUM_THERMAL_DURATION_NS = 30 * 60 * 1_000_000_000
MINIMUM_LONG_RECORDING_DURATION_NS = 30 * 60 * 1_000_000_000
MINIMUM_MTF50_RETENTION = 0.90


class EvidenceFormatError(ValueError):
    pass


@dataclass(frozen=True)
class EvidenceEvaluation:
    status: str
    verified_evidence: tuple[str, ...]
    missing_evidence: tuple[str, ...]
    invalid_artifacts: tuple[str, ...]
    failed_metrics: tuple[str, ...]
    certificate: Mapping[str, Any] | None
    manifest_sha256: str

    def as_dict(self) -> dict[str, Any]:
        return {
            "schema": REPORT_SCHEMA,
            "status": self.status,
            "verifiedEvidence": list(self.verified_evidence),
            "missingEvidence": list(self.missing_evidence),
            "invalidArtifacts": list(self.invalid_artifacts),
            "failedMetrics": list(self.failed_metrics),
            "manifestSha256": self.manifest_sha256,
            "claims": {
                "physicalDeviceValidated": self.status == "APPROVED",
                "liveFrameActivationApproved": self.status == "APPROVED",
                "vendorSuperiorityProven": False,
            },
            "certificate": self.certificate,
        }


def _is_sha256(value: object) -> bool:
    if not isinstance(value, str) or len(value) != SHA256_LENGTH:
        return False
    return all(character in "0123456789abcdefABCDEF" for character in value)


def _require_mapping(container: Mapping[str, Any], key: str) -> Mapping[str, Any]:
    value = container.get(key)
    if not isinstance(value, Mapping):
        raise EvidenceFormatError(f"{key} must be an object")
    return value


def _require_string(container: Mapping[str, Any], key: str) -> str:
    value = container.get(key)
    if not isinstance(value, str) or not value.strip():
        raise EvidenceFormatError(f"{key} must be a non-empty string")
    return value


def _require_sha256(container: Mapping[str, Any], key: str) -> str:
    value = _require_string(container, key)
    if not _is_sha256(value):
        raise EvidenceFormatError(f"{key} must be a 64-character hexadecimal SHA-256")
    return value.lower()


def _require_int(container: Mapping[str, Any], key: str, minimum: int | None = None) -> int:
    value = container.get(key)
    if isinstance(value, bool) or not isinstance(value, int):
        raise EvidenceFormatError(f"{key} must be an integer")
    if minimum is not None and value < minimum:
        raise EvidenceFormatError(f"{key} must be at least {minimum}")
    return value


def _number(container: Mapping[str, Any], key: str) -> float:
    value = container.get(key)
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return float("nan")
    result = float(value)
    return result if result == result and abs(result) != float("inf") else float("nan")


def _integer_metric(container: Mapping[str, Any], key: str) -> int | None:
    value = container.get(key)
    if isinstance(value, bool) or not isinstance(value, int):
        return None
    return value


def _sha256_bytes(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            digest.update(block)
    return digest.hexdigest()


def _resolve_artifact(root: Path, relative_path: str) -> Path | None:
    candidate = (root / relative_path).resolve()
    try:
        candidate.relative_to(root.resolve())
    except ValueError:
        return None
    return candidate


def required_evidence(target: str, signal_domain: str) -> frozenset[str]:
    if target not in TARGET_MODES:
        raise EvidenceFormatError(f"unknown activation target: {target}")
    required = set(COMMON_HOST_EVIDENCE | COMMON_DEVICE_EVIDENCE)
    if target in REALTIME_TARGETS:
        required.update(REALTIME_EVIDENCE)
    if target in RECORDING_TARGETS:
        required.update(RECORDING_EVIDENCE)
    if target == "RAW_PREVIEW" or signal_domain in RAW_DOMAINS:
        required.add("RAW_BIT_EXACT")
    if target == "PHOTO":
        required.add("PHOTO_FUSION")
    if target == "CONVERSION":
        required.update(CONVERSION_EVIDENCE)
    return frozenset(required)


def evidence_mask(evidence_names: Sequence[str] | set[str] | frozenset[str]) -> int:
    selected = set(evidence_names)
    result = 0
    for index, name in enumerate(EVIDENCE_ORDER):
        if name in selected:
            result |= 1 << index
    return result


def _verified_evidence(
    manifest: Mapping[str, Any], artifact_root: Path
) -> tuple[set[str], list[str]]:
    raw_records = manifest.get("evidence")
    if not isinstance(raw_records, list):
        raise EvidenceFormatError("evidence must be an array")
    verified: set[str] = set()
    invalid: list[str] = []
    seen: set[str] = set()
    for index, raw_record in enumerate(raw_records):
        if not isinstance(raw_record, Mapping):
            raise EvidenceFormatError(f"evidence[{index}] must be an object")
        name = _require_string(raw_record, "kind")
        if name not in EVIDENCE_ORDER:
            raise EvidenceFormatError(f"evidence[{index}] has unknown kind {name}")
        if name in seen:
            raise EvidenceFormatError(f"duplicate evidence kind {name}")
        seen.add(name)
        passed = raw_record.get("passed")
        if not isinstance(passed, bool):
            raise EvidenceFormatError(f"evidence[{index}].passed must be boolean")
        relative_path = _require_string(raw_record, "path")
        expected_hash = _require_sha256(raw_record, "sha256")
        artifact_path = _resolve_artifact(artifact_root, relative_path)
        if artifact_path is None or not artifact_path.is_file():
            invalid.append(f"{name}:missing-or-outside-root")
            continue
        actual_hash = _sha256_file(artifact_path)
        if actual_hash != expected_hash:
            invalid.append(f"{name}:sha256-mismatch")
            continue
        if passed:
            verified.add(name)
    return verified, invalid


def _identity(manifest: Mapping[str, Any]) -> dict[str, Any]:
    identity = _require_mapping(manifest, "identity")
    target = _require_string(identity, "target")
    mode = _require_string(identity, "operatingMode")
    signal_domain = _require_string(identity, "signalDomain")
    if target not in TARGET_MODES:
        raise EvidenceFormatError(f"unknown activation target: {target}")
    if mode not in TARGET_MODES[target]:
        raise EvidenceFormatError(f"target {target} cannot certify operating mode {mode}")
    if signal_domain not in {
        "SENSOR_LINEAR_RAW",
        "SENSOR_LINEAR_DEMOSAICED",
        "LINEAR_RGB",
        "P010_YUV",
        "DISPLAY_RGB",
    }:
        raise EvidenceFormatError(f"unknown signal domain: {signal_domain}")
    native_abi_version = _require_int(identity, "nativeAbiVersion", 1)
    if native_abi_version != NATIVE_ABI_VERSION:
        raise EvidenceFormatError(
            f"nativeAbiVersion must be {NATIVE_ABI_VERSION}, got {native_abi_version}"
        )
    return {
        "appBuildFingerprint": _require_string(identity, "appBuildFingerprint"),
        "shaderGraphSha256": _require_sha256(identity, "shaderGraphSha256"),
        "nativeLibrarySha256": _require_sha256(identity, "nativeLibrarySha256"),
        "nativeAbiVersion": native_abi_version,
        "deviceBuildFingerprint": _require_string(identity, "deviceBuildFingerprint"),
        "gpuDriverFingerprint": _require_string(identity, "gpuDriverFingerprint"),
        "cameraId": _require_string(identity, "cameraId"),
        "target": target,
        "operatingMode": mode,
        "signalDomain": signal_domain,
        "width": _require_int(identity, "width", 1),
        "height": _require_int(identity, "height", 1),
        "targetFps": _require_int(identity, "targetFps", 1),
        "colorProfileId": _require_int(identity, "colorProfileId", 0),
    }


def _failed_metrics(target: str, signal_domain: str, metrics: Mapping[str, Any]) -> list[str]:
    failed: list[str] = []
    shader_values = _integer_metric(metrics, "shaderComparedValueCount")
    shader_error = _number(metrics, "shaderMaximumAbsoluteError")
    if (
        shader_values is None
        or shader_values < MINIMUM_SHADER_VALUES
        or shader_error != shader_error
        or shader_error < 0.0
        or shader_error > MAXIMUM_SHADER_ABSOLUTE_ERROR
    ):
        failed.append("SHADER_PARITY")

    physical_frames = _integer_metric(metrics, "physicalDeviceFrameCount")
    controlled_scenes = _integer_metric(metrics, "controlledSceneCount")
    if (
        physical_frames is None
        or physical_frames < MINIMUM_PHYSICAL_FRAMES
        or controlled_scenes is None
        or controlled_scenes < MINIMUM_CONTROLLED_SCENES
    ):
        failed.append("DEVICE_SAMPLE_SIZE")

    resident = _integer_metric(metrics, "maximumResidentBytes")
    budget = _integer_metric(metrics, "certifiedMemoryBudgetBytes")
    if resident is None or budget is None or resident <= 0 or budget <= 0 or resident > budget:
        failed.append("MEMORY_BOUND")

    mtf = _number(metrics, "mtf50Retention")
    clipping = _number(metrics, "clippingIncrease")
    clipping_tolerance = _number(metrics, "clippingTolerance")
    awb_improvement = _number(metrics, "awbStabilityImprovement")
    denoise_improvement = _number(metrics, "denoiseQualityImprovement")
    if (
        mtf != mtf
        or mtf < MINIMUM_MTF50_RETENTION
        or clipping != clipping
        or clipping < 0.0
        or clipping_tolerance != clipping_tolerance
        or clipping_tolerance < 0.0
        or clipping > clipping_tolerance
        or awb_improvement != awb_improvement
        or awb_improvement <= 0.0
        or denoise_improvement != denoise_improvement
        or denoise_improvement <= 0.0
    ):
        failed.append("QUALITY_NON_DOWNGRADE")

    exact_zero_metrics = {
        "nonMonotonicTimestampCount": "TIMESTAMP_MONOTONICITY",
        "temporalHistoryLeakCount": "TEMPORAL_HISTORY_ISOLATION",
        "visibleTileSeamCount": "TILE_CONTINUITY",
    }
    for field, failure in exact_zero_metrics.items():
        if _integer_metric(metrics, field) != 0:
            failed.append(failure)

    if target in REALTIME_TARGETS:
        median = _integer_metric(metrics, "medianFrameTimeNs")
        p95 = _integer_metric(metrics, "p95FrameTimeNs")
        p99 = _integer_metric(metrics, "p99FrameTimeNs")
        maximum = _integer_metric(metrics, "maximumFrameTimeNs")
        deadline = _integer_metric(metrics, "frameDeadlineNs")
        deadline_misses = _integer_metric(metrics, "deadlineMissCount")
        if (
            median is None
            or p95 is None
            or p99 is None
            or maximum is None
            or deadline is None
            or median <= 0
            or not median <= p95 <= p99 <= maximum <= deadline
            or deadline_misses != 0
        ):
            failed.append("FRAME_TIMING")
        thermal_duration = _integer_metric(metrics, "thermalRunDurationNs")
        if thermal_duration is None or thermal_duration < MINIMUM_THERMAL_DURATION_NS:
            failed.append("THERMAL_DURATION")

    if target == "RAW_PREVIEW" or signal_domain in RAW_DOMAINS or target == "CONVERSION":
        if _integer_metric(metrics, "rawByteMismatchCount") != 0:
            failed.append("RAW_FIDELITY")
    if target == "CONVERSION":
        if _integer_metric(metrics, "conversionIntegrityFailureCount") != 0:
            failed.append("CONVERSION_INTEGRITY")
    if target in RECORDING_TARGETS:
        recording_duration = _integer_metric(metrics, "longRecordingDurationNs")
        if (
            _integer_metric(metrics, "unexplainedRecordingFrameLoss") != 0
            or recording_duration is None
            or recording_duration < MINIMUM_LONG_RECORDING_DURATION_NS
        ):
            failed.append("RECORDING_INTEGRITY")
    return failed


def evaluate_manifest(
    manifest: Mapping[str, Any],
    manifest_bytes: bytes,
    artifact_root: Path,
    now_epoch_ms: int,
) -> EvidenceEvaluation:
    if manifest.get("schema") != SCHEMA:
        raise EvidenceFormatError(f"schema must be {SCHEMA}")
    if now_epoch_ms <= 0:
        raise EvidenceFormatError("now_epoch_ms must be positive")
    identity = _identity(manifest)
    validity = _require_mapping(manifest, "validity")
    issued_at = _require_int(validity, "issuedAtEpochMs", 1)
    expires_at = _require_int(validity, "expiresAtEpochMs", issued_at)
    metrics = _require_mapping(manifest, "metrics")
    verified, invalid_artifacts = _verified_evidence(manifest, artifact_root)
    required = required_evidence(identity["target"], identity["signalDomain"])
    missing = sorted(required - verified)
    failed_metrics = _failed_metrics(identity["target"], identity["signalDomain"], metrics)
    if now_epoch_ms < issued_at or now_epoch_ms > expires_at:
        failed_metrics.append("CERTIFICATE_VALIDITY_WINDOW")
    manifest_hash = _sha256_bytes(manifest_bytes)
    certificate: dict[str, Any] | None = None
    if not missing and not invalid_artifacts and not failed_metrics:
        certificate = {
            "schemaVersion": CERTIFICATE_SCHEMA_VERSION,
            "certificateId": "",
            "evidenceManifestSha256": manifest_hash,
            **identity,
            "issuedAtEpochMs": issued_at,
            "expiresAtEpochMs": expires_at,
            "passedEvidenceMask": evidence_mask(verified),
        }
        metric_fields = (
            "shaderComparedValueCount",
            "shaderMaximumAbsoluteError",
            "physicalDeviceFrameCount",
            "controlledSceneCount",
            "medianFrameTimeNs",
            "p95FrameTimeNs",
            "p99FrameTimeNs",
            "maximumFrameTimeNs",
            "frameDeadlineNs",
            "deadlineMissCount",
            "maximumResidentBytes",
            "certifiedMemoryBudgetBytes",
            "thermalRunDurationNs",
            "mtf50Retention",
            "clippingIncrease",
            "clippingTolerance",
            "awbStabilityImprovement",
            "denoiseQualityImprovement",
            "unexplainedRecordingFrameLoss",
            "nonMonotonicTimestampCount",
            "temporalHistoryLeakCount",
            "visibleTileSeamCount",
            "rawByteMismatchCount",
            "conversionIntegrityFailureCount",
            "longRecordingDurationNs",
        )
        for field in metric_fields:
            certificate[field] = metrics.get(field)
        if tuple(certificate) != CERTIFICATE_FIELDS:
            raise AssertionError("NIV99 certificate field order drifted from the cross-language ABI")
        certificate_payload = json.dumps(
            certificate,
            sort_keys=True,
            separators=(",", ":"),
            ensure_ascii=True,
        ).encode("utf-8")
        certificate["certificateId"] = _sha256_bytes(certificate_payload)
    return EvidenceEvaluation(
        status="APPROVED" if certificate is not None else "BLOCKED",
        verified_evidence=tuple(sorted(verified)),
        missing_evidence=tuple(missing),
        invalid_artifacts=tuple(sorted(invalid_artifacts)),
        failed_metrics=tuple(sorted(set(failed_metrics))),
        certificate=certificate,
        manifest_sha256=manifest_hash,
    )


def _parse_args(arguments: Sequence[str]) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Verify hashed NIV99 host/device evidence and issue a fail-closed activation certificate."
    )
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--artifact-root", type=Path, default=Path.cwd())
    parser.add_argument("--now-epoch-ms", type=int, required=True)
    parser.add_argument("--output", type=Path)
    return parser.parse_args(arguments)


def main(arguments: Sequence[str] | None = None) -> int:
    options = _parse_args(sys.argv[1:] if arguments is None else arguments)
    try:
        manifest_bytes = options.manifest.read_bytes()
        manifest = json.loads(manifest_bytes)
        if not isinstance(manifest, Mapping):
            raise EvidenceFormatError("manifest root must be an object")
        result = evaluate_manifest(
            manifest=manifest,
            manifest_bytes=manifest_bytes,
            artifact_root=options.artifact_root,
            now_epoch_ms=options.now_epoch_ms,
        )
        output = json.dumps(result.as_dict(), indent=2, sort_keys=True) + "\n"
        if options.output is None:
            sys.stdout.write(output)
        else:
            options.output.parent.mkdir(parents=True, exist_ok=True)
            options.output.write_text(output, encoding="utf-8")
        return 0 if result.status == "APPROVED" else 2
    except (EvidenceFormatError, OSError, json.JSONDecodeError) as error:
        sys.stderr.write(f"NIV99 activation evidence error: {error}\n")
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
