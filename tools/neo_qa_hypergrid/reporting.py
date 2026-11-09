"""Ledger reconciliation and required per-checkpoint HyperGrid reporting."""

from __future__ import annotations

import csv
import hashlib
import io
import json
import os
import re
import tempfile
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Iterable, Mapping

from .evidence_oracle import (
    CandidateIdentity,
    CheckpointEvidenceRecord,
    DeviceIdentity,
    EvidenceOracle,
)
from .schema import VALID_STATUSES


@dataclass(frozen=True)
class CheckpointReportRow:
    checkpoint_id: str
    category: str
    checkpoint: str
    status: str
    exact_test_performed: str
    candidate_apk_hash: str
    device_build_identity: str
    measured_result: str
    expected_threshold: str
    evidence_paths: str
    root_cause_if_failed: str
    files_changed: str
    focused_validation: str
    regression_validation: str
    remaining_blocker: str


def _atomic_write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8", newline="") as target:
            target.write(text)
            target.flush()
            os.fsync(target.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()


def _canonical_record_sha256(record: Mapping[str, Any]) -> str:
    encoded = json.dumps(
        record,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")
    return hashlib.sha256(encoded).hexdigest().upper()


def _checkpoint_evidence_record(value: Mapping[str, Any]) -> CheckpointEvidenceRecord:
    candidate_value = value.get("candidate")
    device_value = value.get("device")
    if not isinstance(candidate_value, Mapping) or not isinstance(device_value, Mapping):
        raise ValueError("sealed record candidate/device identity is malformed")
    metadata = value.get("metadata", {})
    if not isinstance(metadata, Mapping):
        raise ValueError("sealed record metadata is malformed")
    return CheckpointEvidenceRecord(
        checkpoint_id=str(value.get("checkpoint_id", "")),
        status=str(value.get("status", "")),
        exact_test=str(value.get("exact_test", "")),
        candidate=CandidateIdentity(**dict(candidate_value)),
        device=DeviceIdentity(**dict(device_value)),
        measured_result=str(value.get("measured_result", "")),
        expected_threshold=str(value.get("expected_threshold", "")),
        evidence_paths=tuple(str(item) for item in value.get("evidence_paths", ())),
        root_cause=str(value.get("root_cause", "")),
        files_changed=tuple(str(item) for item in value.get("files_changed", ())),
        focused_validation=str(value.get("focused_validation", "")),
        regression_validation=str(value.get("regression_validation", "")),
        remaining_blocker=str(value.get("remaining_blocker", "")),
        evidence_kind=str(value.get("evidence_kind", "host")),
        measurement_claims=tuple(str(item) for item in value.get("measurement_claims", ())),
        metadata={str(key): str(item) for key, item in metadata.items()},
    )


def verify_sealed_evidence_record(path: Path, workspace: Path) -> tuple[bool, tuple[str, ...]]:
    workspace = workspace.resolve()
    errors: list[str] = []
    resolved_record_path = path.resolve()
    try:
        resolved_record_path.relative_to(workspace)
    except ValueError:
        return False, ("sealed evidence record escapes workspace",)
    if not resolved_record_path.is_file():
        return False, ("sealed evidence record is missing",)
    try:
        payload = json.loads(resolved_record_path.read_text(encoding="utf-8"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        return False, (f"sealed evidence record is unreadable: {exc}",)
    if not isinstance(payload, Mapping):
        return False, ("sealed evidence payload must be an object",)
    if payload.get("schema") != "neo-qa-hypergrid/evidence-record/v1":
        errors.append("unsupported evidence schema")

    raw_record = payload.get("record")
    if not isinstance(raw_record, Mapping):
        return False, tuple(errors + ["sealed evidence record body is malformed"])
    declared_record_hash = str(payload.get("record_sha256", ""))
    actual_record_hash = _canonical_record_sha256(raw_record)
    if declared_record_hash.upper() != actual_record_hash:
        errors.append("sealed record body hash mismatch")
    try:
        record = _checkpoint_evidence_record(raw_record)
        oracle_validation = EvidenceOracle(workspace).validate(record)
    except (TypeError, ValueError) as exc:
        errors.append(f"sealed record contract is malformed: {exc}")
        oracle_validation = None
    if oracle_validation is not None and not oracle_validation.accepted:
        errors.extend(f"sealed record rejected: {error}" for error in oracle_validation.errors)

    raw_artifacts = payload.get("artifacts")
    if not isinstance(raw_artifacts, list):
        return False, tuple(errors + ["sealed artifact manifest must be a list"])
    manifest_paths: list[str] = []
    for index, artifact in enumerate(raw_artifacts):
        if not isinstance(artifact, Mapping):
            errors.append(f"artifact entry {index} is malformed")
            continue
        raw_artifact_path = artifact.get("path")
        if not isinstance(raw_artifact_path, str) or not raw_artifact_path:
            errors.append(f"artifact entry {index} has no path")
            continue
        artifact_path = (workspace / raw_artifact_path).resolve()
        try:
            relative_path = artifact_path.relative_to(workspace).as_posix()
        except ValueError:
            errors.append(f"artifact escapes workspace: {raw_artifact_path}")
            continue
        manifest_paths.append(relative_path)
        if not artifact_path.is_file():
            errors.append(f"artifact missing: {raw_artifact_path}")
            continue
        try:
            expected_bytes = int(artifact.get("bytes", -1))
        except (TypeError, ValueError):
            expected_bytes = -1
        if artifact_path.stat().st_size != expected_bytes:
            errors.append(f"artifact size mismatch: {raw_artifact_path}")
        expected_hash = str(artifact.get("sha256", "")).upper()
        if not re.fullmatch(r"[0-9A-F]{64}", expected_hash):
            errors.append(f"artifact SHA-256 is malformed: {raw_artifact_path}")
        elif _sha256(artifact_path) != expected_hash:
            errors.append(f"artifact hash mismatch: {raw_artifact_path}")
    if len(manifest_paths) != len(set(manifest_paths)):
        errors.append("sealed artifact manifest contains duplicate paths")
    if oracle_validation is not None and set(manifest_paths) != set(oracle_validation.resolved_paths):
        errors.append("sealed artifact manifest does not exactly match record evidence paths")
    return not errors, tuple(errors)


def _device_identity(record: Mapping[str, Any]) -> str:
    device = record.get("device", {})
    return "; ".join(
        f"{key}={device.get(key, '')}"
        for key in ("serial", "manufacturer", "model", "android_version", "api_level", "build_fingerprint", "abi")
    )


def _legacy_row(node: Mapping[str, Any]) -> CheckpointReportRow:
    status = str(node["status"])
    existing_evidence = str(node.get("evidence", ""))
    note = str(node.get("root_cause_or_note", ""))
    blocker = note if status in {"BLOCKED", "PENDING"} else ""
    return CheckpointReportRow(
        checkpoint_id=str(node["checkpoint_id"]),
        category=str(node["category"]),
        checkpoint=str(node["checkpoint"]),
        status=status,
        exact_test_performed="LEGACY_LEDGER_EVIDENCE; migrate to a sealed HyperGrid record",
        candidate_apk_hash="UNAVAILABLE_LEGACY_LEDGER",
        device_build_identity="UNAVAILABLE_LEGACY_LEDGER",
        measured_result=note or "See cited legacy evidence; no normalized measurement record",
        expected_threshold=" | ".join(node.get("pass_fail_thresholds", ())),
        evidence_paths=existing_evidence,
        root_cause_if_failed=note if status == "FAIL" else "",
        files_changed="UNAVAILABLE_LEGACY_LEDGER",
        focused_validation="UNAVAILABLE_LEGACY_LEDGER",
        regression_validation="UNAVAILABLE_LEGACY_LEDGER",
        remaining_blocker=blocker,
    )


def build_checkpoint_report(
    graph_json: Path,
    *,
    workspace: Path,
    evidence_records: Iterable[Path] = (),
) -> tuple[CheckpointReportRow, ...]:
    workspace = workspace.resolve()
    graph = json.loads(graph_json.read_text(encoding="utf-8"))
    graph_nodes = graph.get("nodes", [])
    if not isinstance(graph_nodes, list) or not graph_nodes:
        raise ValueError("checkpoint graph contains no nodes")
    nodes = {node["checkpoint_id"]: node for node in graph_nodes}
    if len(nodes) != len(graph_nodes):
        raise ValueError("checkpoint graph contains duplicate checkpoint IDs")
    sealed: dict[str, Mapping[str, Any]] = {}
    for path in evidence_records:
        valid, errors = verify_sealed_evidence_record(path, workspace)
        if not valid:
            raise ValueError(f"invalid evidence record {path}: {'; '.join(errors)}")
        payload = json.loads(path.read_text(encoding="utf-8"))
        record = payload["record"]
        checkpoint_id = str(record["checkpoint_id"])
        if checkpoint_id not in nodes:
            raise ValueError(f"evidence references unknown checkpoint: {checkpoint_id}")
        if checkpoint_id in sealed:
            raise ValueError(f"multiple evidence records for checkpoint: {checkpoint_id}")
        status = str(record.get("status", ""))
        execution_target = str(nodes[checkpoint_id].get("execution_target", "host"))
        evidence_kind = str(record.get("evidence_kind", "host"))
        if status == "PASS" and "device" in execution_target and evidence_kind != "physical_device":
            raise ValueError(
                f"{checkpoint_id} requires physical-device evidence for PASS"
            )
        if status == "PASS" and execution_target == "external" and evidence_kind != "external":
            raise ValueError(f"{checkpoint_id} requires external-owner evidence for PASS")
        if status == "PASS" and execution_target == "external":
            candidate = record.get("candidate", {})
            signing_hash = str(candidate.get("signing_certificate_sha256", ""))
            artifact_hash = str(candidate.get("apk_sha256", ""))
            if not re.fullmatch(r"[0-9A-Fa-f]{64}", signing_hash) or not re.fullmatch(
                r"[0-9A-Fa-f]{64}", artifact_hash
            ):
                raise ValueError(
                    f"{checkpoint_id} external PASS requires concrete artifact and signing hashes"
                )
        sealed[checkpoint_id] = record

    physical_candidate_hashes = {
        str(record.get("candidate", {}).get("apk_sha256", "")).upper()
        for record in sealed.values()
        if record.get("evidence_kind") == "physical_device"
        and record.get("status") in {"PASS", "FAIL"}
    }
    if len(physical_candidate_hashes) > 1:
        raise ValueError("physical-device evidence records reference multiple APK candidates")

    final_status = {
        checkpoint_id: str(sealed.get(checkpoint_id, node).get("status", ""))
        for checkpoint_id, node in nodes.items()
    }
    invalid_statuses = sorted(
        checkpoint_id
        for checkpoint_id, status in final_status.items()
        if status not in VALID_STATUSES
    )
    if invalid_statuses:
        raise ValueError("report contains invalid statuses: " + ", ".join(invalid_statuses))
    for checkpoint_id, record in sealed.items():
        if record.get("status") != "PASS":
            continue
        unmet = [
            prerequisite
            for prerequisite in nodes[checkpoint_id].get("prerequisites", ())
            if final_status.get(prerequisite) != "PASS"
        ]
        if unmet:
            raise ValueError(
                f"{checkpoint_id} cannot be PASS while prerequisites are not PASS: "
                + ", ".join(unmet)
            )

    rows: list[CheckpointReportRow] = []
    for checkpoint_id in sorted(nodes):
        node = nodes[checkpoint_id]
        record = sealed.get(checkpoint_id)
        if record is None:
            rows.append(_legacy_row(node))
            continue
        candidate = record.get("candidate", {})
        status = str(record["status"])
        rows.append(
            CheckpointReportRow(
                checkpoint_id=checkpoint_id,
                category=str(node["category"]),
                checkpoint=str(node["checkpoint"]),
                status=status,
                exact_test_performed=str(record.get("exact_test", "")),
                candidate_apk_hash=str(candidate.get("apk_sha256", "")),
                device_build_identity=_device_identity(record),
                measured_result=str(record.get("measured_result", "")),
                expected_threshold=str(record.get("expected_threshold", "")),
                evidence_paths="; ".join(record.get("evidence_paths", ())),
                root_cause_if_failed=str(record.get("root_cause", "")) if status == "FAIL" else "",
                files_changed="; ".join(record.get("files_changed", ())),
                focused_validation=str(record.get("focused_validation", "")),
                regression_validation=str(record.get("regression_validation", "")),
                remaining_blocker=str(record.get("remaining_blocker", "")),
            )
        )
    return tuple(rows)


def write_checkpoint_report(rows: Iterable[CheckpointReportRow], output_directory: Path) -> None:
    rows = tuple(rows)
    checkpoint_ids = [row.checkpoint_id for row in rows]
    if len(checkpoint_ids) != len(set(checkpoint_ids)):
        raise ValueError("checkpoint report rows contain duplicate IDs")
    invalid = sorted({row.status for row in rows if row.status not in VALID_STATUSES})
    if invalid:
        raise ValueError("checkpoint report rows contain invalid statuses: " + ", ".join(invalid))
    counts: dict[str, int] = {status: 0 for status in ("PASS", "FAIL", "BLOCKED", "PENDING")}
    for row in rows:
        counts[row.status] = counts.get(row.status, 0) + 1
    legacy_count = sum(
        row.exact_test_performed.startswith("LEGACY_LEDGER_EVIDENCE;") for row in rows
    )
    sealed_count = len(rows) - legacy_count
    json_payload = {
        "schema": "neo-qa-hypergrid/checkpoint-report/v1",
        "counts": counts,
        "total": len(rows),
        "sealed_hypergrid_records": sealed_count,
        "legacy_ledger_records": legacy_count,
        "checkpoints": [asdict(row) for row in rows],
    }
    _atomic_write(
        output_directory / "CHECKPOINT_REPORT.json",
        json.dumps(json_payload, indent=2, sort_keys=True) + "\n",
    )
    stream = io.StringIO(newline="")
    writer = csv.DictWriter(stream, fieldnames=list(asdict(rows[0]).keys()) if rows else [])
    if rows:
        writer.writeheader()
        writer.writerows(asdict(row) for row in rows)
    _atomic_write(output_directory / "CHECKPOINT_REPORT.csv", stream.getvalue())
    markdown = [
        "# NEO-QA HyperGrid checkpoint report",
        "",
        f"Total: {len(rows)}",
        "",
        *[f"- {status}: {counts.get(status, 0)}" for status in ("PASS", "FAIL", "BLOCKED", "PENDING")],
        f"- Sealed HyperGrid records: {sealed_count}",
        f"- Legacy ledger records: {legacy_count}",
        "",
        "A PASS is accepted only from its cited evidence. Legacy ledger rows remain explicitly marked",
        "as not yet normalized into HyperGrid evidence records.",
        "",
    ]
    _atomic_write(output_directory / "REPORT.md", "\n".join(markdown))
