"""Deterministic discovery of repository test tools and checkpoint evidence."""

from __future__ import annotations

import hashlib
import json
import os
import tempfile
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Iterable


@dataclass(frozen=True)
class ToolRecord:
    path: str
    kind: str
    requires_adb: bool
    requires_gradle: bool
    capabilities: tuple[str, ...]
    bytes: int
    sha256: str


@dataclass(frozen=True)
class EvidenceRecord:
    path: str
    bytes: int
    suffix: str
    checkpoint_ids: tuple[str, ...]


@dataclass(frozen=True)
class RepositoryInventory:
    tools: tuple[ToolRecord, ...]
    evidence: tuple[EvidenceRecord, ...]


def _digest(path: Path) -> str:
    hasher = hashlib.sha256()
    with path.open("rb") as source:
        while block := source.read(1024 * 1024):
            hasher.update(block)
    return hasher.hexdigest().upper()


def _classify(path: Path, sample: str) -> tuple[str, tuple[str, ...]]:
    normalized = path.as_posix().lower()
    capabilities: set[str] = set()
    if "simulation" in normalized:
        capabilities.add("simulation")
    if "calibration" in normalized:
        capabilities.add("calibration")
    if "quality" in normalized or "gate" in path.name.lower():
        capabilities.add("quality_gate")
    if "adb" in sample.lower() or "adb" in path.name.lower():
        capabilities.add("device_control")
    if "ffprobe" in sample.lower():
        capabilities.add("media_analysis")
    if "perfetto" in sample.lower() or "simpleperf" in sample.lower():
        capabilities.add("performance_trace")
    if "unittest" in sample or path.name.startswith("test_"):
        capabilities.add("unit_test")
    if path.suffix.lower() == ".py":
        kind = "python"
    elif path.suffix.lower() == ".ps1":
        kind = "powershell"
    elif path.suffix.lower() in {".sh", ".bat", ".cmd"}:
        kind = "shell"
    elif path.suffix.lower() in {".kt", ".java", ".cpp", ".c"}:
        kind = "compiled_test_source"
    else:
        kind = "configuration"
    return kind, tuple(sorted(capabilities))


def _safe_text_sample(path: Path, maximum_bytes: int = 128 * 1024) -> str:
    try:
        return path.read_bytes()[:maximum_bytes].decode("utf-8", errors="ignore")
    except OSError:
        return ""


def _relative_files(
    workspace: Path,
    roots: Iterable[Path],
    excluded: frozenset[Path] = frozenset(),
) -> Iterable[Path]:
    for root in roots:
        absolute = root if root.is_absolute() else workspace / root
        if not absolute.exists():
            continue
        for path in absolute.rglob("*"):
            if not path.is_file() or "__pycache__" in path.parts or ".gradle" in path.parts:
                continue
            resolved = path.resolve()
            try:
                resolved.relative_to(workspace)
            except ValueError:
                continue
            if resolved not in excluded:
                yield path


def discover_repository(
    workspace: Path,
    *,
    include_evidence: bool = True,
    exclude_paths: Iterable[Path] = (),
) -> RepositoryInventory:
    workspace = workspace.resolve()
    excluded = frozenset(
        (path if path.is_absolute() else workspace / path).resolve()
        for path in exclude_paths
    )
    tool_roots = (
        Path("tools"),
        Path("app/src/test"),
        Path("app/src/androidTest"),
        Path("core-math/src/test"),
    )
    tools: list[ToolRecord] = []
    for path in sorted(
        set(_relative_files(workspace, tool_roots, excluded)),
        key=lambda item: str(item).lower(),
    ):
        sample = _safe_text_sample(path)
        kind, capabilities = _classify(path, sample)
        relative = path.relative_to(workspace).as_posix()
        tools.append(
            ToolRecord(
                path=relative,
                kind=kind,
                requires_adb="device_control" in capabilities,
                requires_gradle=path.suffix.lower() in {".kt", ".java", ".cpp", ".c"}
                or "gradlew" in sample.lower(),
                capabilities=capabilities,
                bytes=path.stat().st_size,
                sha256=_digest(path),
            )
        )
    evidence: list[EvidenceRecord] = []
    if include_evidence:
        for path in sorted(
            _relative_files(workspace, (Path("artifacts"),), excluded),
            key=lambda item: str(item).lower(),
        ):
            relative = path.relative_to(workspace).as_posix()
            checkpoint_ids = tuple(
                sorted(
                    {
                        token.upper()
                        for token in __import__("re").findall(r"CP-\d{3}", relative, flags=__import__("re").I)
                    }
                )
            )
            evidence.append(
                EvidenceRecord(
                    path=relative,
                    bytes=path.stat().st_size,
                    suffix=path.suffix.lower(),
                    checkpoint_ids=checkpoint_ids,
                )
            )
    return RepositoryInventory(tuple(tools), tuple(evidence))


def write_inventory(inventory: RepositoryInventory, output: Path) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "schema": "neo-qa-hypergrid/repository-inventory/v1",
        "tools": [asdict(item) for item in inventory.tools],
        "evidence": [asdict(item) for item in inventory.evidence],
    }
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{output.name}.", dir=output.parent)
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "w", encoding="utf-8") as target:
            json.dump(payload, target, indent=2, sort_keys=True)
            target.write("\n")
            target.flush()
            os.fsync(target.fileno())
        os.replace(temporary, output)
    finally:
        temporary.unlink(missing_ok=True)
