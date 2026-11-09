"""Typed, deterministic schema for the NEO-QA HyperGrid checkpoint graph.

The schema deliberately contains no Android, Gradle, or ADB integration.  It is
the host-side contract shared by the graph compiler and execution schedulers.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Mapping


SCHEMA_VERSION = "neo-qa-hypergrid.checkpoint-graph.v1"
VALID_STATUSES = ("PASS", "FAIL", "BLOCKED", "PENDING")
VALID_EXECUTION_TARGETS = ("host", "device", "host+device", "external")
SUBCHECK_PHASES = ("admission", "execution", "oracle", "cleanup", "evidence")


@dataclass(frozen=True, slots=True)
class LedgerRecord:
    """One row preserved from the canonical checkpoint ledger."""

    checkpoint_id: str
    ordinal: int
    category: str
    checkpoint: str
    status: str
    evidence: str
    root_cause_or_note: str

    def to_dict(self) -> dict[str, Any]:
        return {
            "checkpoint_id": self.checkpoint_id,
            "ordinal": self.ordinal,
            "category": self.category,
            "checkpoint": self.checkpoint,
            "status": self.status,
            "evidence": self.evidence,
            "root_cause_or_note": self.root_cause_or_note,
        }


@dataclass(frozen=True, slots=True)
class Subcheck:
    """A mandatory phase of one checkpoint's reproducible test contract."""

    subcheck_id: str
    phase: str
    execution_target: str
    procedure: str
    expected_result: str
    pass_fail_threshold: str
    required_evidence: tuple[str, ...]

    def to_dict(self) -> dict[str, Any]:
        return {
            "subcheck_id": self.subcheck_id,
            "phase": self.phase,
            "execution_target": self.execution_target,
            "procedure": self.procedure,
            "expected_result": self.expected_result,
            "pass_fail_threshold": self.pass_fail_threshold,
            "required_evidence": list(self.required_evidence),
        }


@dataclass(frozen=True, slots=True)
class CheckpointSubchecks:
    """The five explicit phases required for every HyperGrid checkpoint."""

    admission: Subcheck
    execution: Subcheck
    oracle: Subcheck
    cleanup: Subcheck
    evidence: Subcheck

    def ordered(self) -> tuple[Subcheck, ...]:
        return (
            self.admission,
            self.execution,
            self.oracle,
            self.cleanup,
            self.evidence,
        )

    def to_dict(self) -> dict[str, Any]:
        return {subcheck.phase: subcheck.to_dict() for subcheck in self.ordered()}


@dataclass(frozen=True, slots=True)
class CheckpointNode:
    """Fully compiled checkpoint node, including provenance and test policy."""

    checkpoint_id: str
    ordinal: int
    category: str
    subcategory: str
    checkpoint: str
    status: str
    evidence: str
    root_cause_or_note: str
    source_areas: tuple[str, ...]
    build_variants: tuple[str, ...]
    execution_target: str
    prerequisites: tuple[str, ...]
    test_procedure: str
    expected_result: str
    pass_fail_thresholds: tuple[str, ...]
    required_evidence: tuple[str, ...]
    regression_links: tuple[str, ...]
    blockers: tuple[str, ...]
    subchecks: CheckpointSubchecks

    @property
    def requires_device(self) -> bool:
        return "device" in self.execution_target

    def to_dict(self) -> dict[str, Any]:
        return {
            "checkpoint_id": self.checkpoint_id,
            "ordinal": self.ordinal,
            "category": self.category,
            "subcategory": self.subcategory,
            "checkpoint": self.checkpoint,
            "status": self.status,
            "evidence": self.evidence,
            "root_cause_or_note": self.root_cause_or_note,
            "source_areas": list(self.source_areas),
            "build_variants": list(self.build_variants),
            "execution_target": self.execution_target,
            "prerequisites": list(self.prerequisites),
            "test_procedure": self.test_procedure,
            "expected_result": self.expected_result,
            "pass_fail_thresholds": list(self.pass_fail_thresholds),
            "required_evidence": list(self.required_evidence),
            "regression_links": list(self.regression_links),
            "blockers": list(self.blockers),
            "subchecks": self.subchecks.to_dict(),
        }


@dataclass(frozen=True, slots=True)
class GraphEdge:
    """Directed edge from prerequisite to dependent checkpoint."""

    prerequisite: str
    checkpoint_id: str

    def to_dict(self) -> dict[str, str]:
        return {
            "prerequisite": self.prerequisite,
            "checkpoint_id": self.checkpoint_id,
        }


@dataclass(frozen=True, slots=True)
class CheckpointGraph:
    """Immutable compiled representation of the complete canonical ledger."""

    schema_version: str
    ledger_path: str
    ledger_sha256: str
    nodes: tuple[CheckpointNode, ...]
    edges: tuple[GraphEdge, ...]

    @property
    def node_map(self) -> Mapping[str, CheckpointNode]:
        return {node.checkpoint_id: node for node in self.nodes}

    def node(self, checkpoint_id: str) -> CheckpointNode:
        try:
            return self.node_map[checkpoint_id]
        except KeyError as error:
            raise KeyError(f"Unknown checkpoint: {checkpoint_id}") from error

    def status_summary(self) -> dict[str, int]:
        return {
            status: sum(node.status == status for node in self.nodes)
            for status in VALID_STATUSES
        }

    def category_summary(self) -> dict[str, int]:
        categories = sorted({node.category for node in self.nodes})
        return {
            category: sum(node.category == category for node in self.nodes)
            for category in categories
        }

    def to_dict(self) -> dict[str, Any]:
        return {
            "schema_version": self.schema_version,
            "ledger": {
                "path": self.ledger_path,
                "sha256": self.ledger_sha256,
                "row_count": len(self.nodes),
                "status_summary": self.status_summary(),
                "category_summary": self.category_summary(),
            },
            "nodes": [node.to_dict() for node in self.nodes],
            "edges": [edge.to_dict() for edge in self.edges],
        }

