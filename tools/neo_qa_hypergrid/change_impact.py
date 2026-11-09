"""Source hashing and checkpoint invalidation for NEO-QA HyperGrid."""

from __future__ import annotations

import hashlib
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Mapping, Sequence


def checkpoint_range(start: int, end: int) -> frozenset[str]:
    return frozenset(f"CP-{number:03d}" for number in range(start, end + 1))


FINAL_REGRESSION = frozenset({"CP-217", "CP-218", "CP-219", "CP-220"})
ALL_CHECKPOINTS = checkpoint_range(1, 220)


@dataclass(frozen=True)
class ImpactRule:
    pattern: str
    checkpoints: frozenset[str]
    rationale: str

    def matches(self, path: str) -> bool:
        return re.search(self.pattern, path.replace("\\", "/"), re.IGNORECASE) is not None


DEFAULT_RULES: tuple[ImpactRule, ...] = (
    ImpactRule(
        r"^(\.gitattributes|\.gitignore|README(?:\.[^/]*)?|settings\.gradle)$",
        {"CP-001", "CP-002", "CP-003", "CP-008"} | FINAL_REGRESSION,
        "repository structure, ownership, hygiene and release documentation",
    ),
    ImpactRule(
        r"(^|/)build\.gradle$|gradle/|gradle\.properties$|settings\.gradle$",
        checkpoint_range(1, 20) | {"CP-043", "CP-044"} | FINAL_REGRESSION,
        "build, dependency, package and release candidate identity",
    ),
    ImpactRule(
        r"app/src/main/(java|res)/.*?/ui/|app/src/main/res/",
        checkpoint_range(45, 140) | checkpoint_range(201, 214) | FINAL_REGRESSION,
        "startup, settings, controls, preview and lifecycle UI",
    ),
    ImpactRule(
        r"app/src/main/java/.*/(camera|capture)/",
        checkpoint_range(34, 40)
        | checkpoint_range(56, 169)
        | checkpoint_range(201, 214)
        | FINAL_REGRESSION,
        "Camera2 capability, controls, preview, capture and camera lifecycle",
    ),
    ImpactRule(
        r"app/src/main/java/.*/(recording|pipeline)/",
        {"CP-066"}
        | checkpoint_range(100, 200)
        | checkpoint_range(205, 214)
        | FINAL_REGRESSION,
        "recording, RAW ownership, conversion, cadence and resource lifecycle",
    ),
    ImpactRule(
        r"app/src/main/java/.*/rendering/|app/src/main/cpp/|app/src/main/assets/shaders/",
        {"CP-006", "CP-014", "CP-015", "CP-016", "CP-027", "CP-028"}
        | checkpoint_range(35, 40)
        | checkpoint_range(81, 180)
        | checkpoint_range(199, 214)
        | FINAL_REGRESSION,
        "native/GPU/shader colour, transform, preview, recording and performance",
    ),
    ImpactRule(
        r"AndroidManifest\.xml$",
        checkpoint_range(11, 20) | checkpoint_range(21, 220),
        "packaging, permissions, components, services, security and all installed behavior",
    ),
    ImpactRule(
        r"app/src/main/java/.*/security/|network_security",
        {"CP-019", "CP-044", "CP-059", "CP-060", "CP-215", "CP-216"}
        | FINAL_REGRESSION,
        "runtime permissions, signing, network and security controls",
    ),
    ImpactRule(
        r"tools/neo_qa_hypergrid/|DEVICE_PRODUCTION_CHECKPOINT_LEDGER\.csv$",
        ALL_CHECKPOINTS,
        "QA compiler, scheduling, evidence interpretation and every derived verdict",
    ),
    ImpactRule(
        r"tools/simulation/|tools/calibration/",
        {"CP-007", "CP-008", "CP-009", "CP-218", "CP-220"},
        "host simulations and calibration evidence",
    ),
    ImpactRule(
        r"app/src/androidTest/",
        {"CP-020", "CP-061"} | checkpoint_range(21, 220),
        "device test APK and physical checkpoint procedures",
    ),
    ImpactRule(
        r"app/src/test/|core-math/src/test/",
        {"CP-010", "CP-011", "CP-013"} | FINAL_REGRESSION,
        "host unit-test and regression procedure change",
    ),
    ImpactRule(
        r"app/src/main/assets/|app/src/main/resources/",
        {"CP-008", "CP-011", "CP-013", "CP-017", "CP-018"}
        | checkpoint_range(45, 220),
        "packaged runtime asset or resource change",
    ),
    ImpactRule(
        r"^tools/",
        {"CP-007", "CP-008", "CP-009"} | FINAL_REGRESSION,
        "host test, calibration or build-tool procedure change",
    ),
    ImpactRule(
        r"^docs/|RELEASE_CHECKLIST|PRIVACY_POLICY|THIRD_PARTY",
        {"CP-019", "CP-220"},
        "release, legal or production-readiness documentation",
    ),
)


@dataclass(frozen=True)
class FileDigest:
    path: str
    bytes: int
    sha256: str


@dataclass(frozen=True)
class ImpactReport:
    changed_files: tuple[FileDigest, ...]
    directly_invalidated: tuple[str, ...]
    transitively_invalidated: tuple[str, ...]
    rationales: Mapping[str, tuple[str, ...]]


def hash_changed_files(workspace: Path, changed_paths: Iterable[str]) -> tuple[FileDigest, ...]:
    workspace = workspace.resolve()
    results: list[FileDigest] = []
    for raw_path in sorted(set(changed_paths), key=str.lower):
        path = (workspace / raw_path).resolve()
        try:
            relative = path.relative_to(workspace).as_posix()
        except ValueError as exc:
            raise ValueError(f"changed path escapes workspace: {raw_path}") from exc
        if not path.is_file():
            results.append(FileDigest(relative, 0, "MISSING"))
            continue
        digest = hashlib.sha256(path.read_bytes()).hexdigest().upper()
        results.append(FileDigest(relative, path.stat().st_size, digest))
    return tuple(results)


def compute_impact(
    workspace: Path,
    changed_paths: Iterable[str],
    *,
    reverse_dependencies: Mapping[str, Iterable[str]] | None = None,
    rules: Sequence[ImpactRule] = DEFAULT_RULES,
) -> ImpactReport:
    digests = hash_changed_files(workspace, changed_paths)
    direct: set[str] = set()
    rationales: dict[str, set[str]] = {}
    for digest in digests:
        matched = False
        for rule in rules:
            if rule.matches(digest.path):
                matched = True
                direct.update(rule.checkpoints)
                rationales.setdefault(digest.path, set()).add(rule.rationale)
        if not matched and digest.path.lower().startswith("app/src/main/"):
            direct.update(checkpoint_range(45, 220))
            rationales.setdefault(digest.path, set()).add(
                "unclassified application runtime change; conservatively invalidate all device behavior"
            )
    transitive = set(direct)
    if reverse_dependencies is None:
        # Import lazily to keep this module usable without loading the graph compiler at import
        # time. The canonical rules cover all 220 checkpoints and provide transitive invalidation
        # even when the CLI caller does not manually construct a reverse-edge map.
        from .checkpoint_graph import DEPENDENCY_OVERRIDES

        generated: dict[str, list[str]] = {
            f"CP-{ordinal:03d}": [] for ordinal in range(1, 221)
        }
        for dependent, prerequisites in DEPENDENCY_OVERRIDES.items():
            for prerequisite in prerequisites:
                generated[f"CP-{prerequisite:03d}"].append(f"CP-{dependent:03d}")
        reverse_dependencies = generated
    queue = list(sorted(direct))
    while queue:
        checkpoint = queue.pop(0)
        for dependent in sorted(reverse_dependencies.get(checkpoint, ())):
            if dependent not in transitive:
                transitive.add(dependent)
                queue.append(dependent)
    return ImpactReport(
        changed_files=digests,
        directly_invalidated=tuple(sorted(direct)),
        transitively_invalidated=tuple(sorted(transitive)),
        rationales={path: tuple(sorted(values)) for path, values in sorted(rationales.items())},
    )


def may_preserve_sealed_evidence(
    *,
    evidence_candidate_sha256: str,
    current_candidate_sha256: str,
    checkpoint_invalidated: bool,
    dependency_invalidated: bool,
) -> bool:
    return (
        bool(evidence_candidate_sha256)
        and evidence_candidate_sha256 == current_candidate_sha256
        and not checkpoint_invalidated
        and not dependency_invalidated
    )
