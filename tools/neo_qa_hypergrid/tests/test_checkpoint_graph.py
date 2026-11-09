from __future__ import annotations

import csv
from collections import Counter
from dataclasses import replace
import json
from pathlib import Path
import tempfile
import unittest

from tools.neo_qa_hypergrid.checkpoint_graph import (
    EXPECTED_CHECKPOINT_IDS,
    GraphCycleError,
    LedgerValidationError,
    compile_checkpoint_graph,
    read_canonical_ledger,
    validate_graph,
    write_graph_artifacts,
)
from tools.neo_qa_hypergrid.schema import CheckpointGraph, SUBCHECK_PHASES
from tools.neo_qa_hypergrid.scheduling import (
    build_parallel_schedule,
    exclusive_device_sequence,
)


REPOSITORY_ROOT = Path(__file__).resolve().parents[3]
CANONICAL_LEDGER = (
    REPOSITORY_ROOT
    / "artifacts"
    / "device-production-qa-2026-08-15"
    / "DEVICE_PRODUCTION_CHECKPOINT_LEDGER.csv"
)


class CheckpointGraphTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.records = read_canonical_ledger(CANONICAL_LEDGER)
        cls.graph = compile_checkpoint_graph(CANONICAL_LEDGER)

    def _write_rows(
        self,
        directory: Path,
        rows: list[dict[str, str]],
        fieldnames: tuple[str, ...] = (
            "id",
            "category",
            "checkpoint",
            "status",
            "evidence",
            "root_cause_or_note",
        ),
    ) -> Path:
        path = directory / "ledger.csv"
        with path.open("w", encoding="utf-8", newline="") as handle:
            writer = csv.DictWriter(
                handle,
                fieldnames=fieldnames,
                lineterminator="\n",
                extrasaction="ignore",
            )
            writer.writeheader()
            writer.writerows(rows)
        return path

    def _canonical_rows(self) -> list[dict[str, str]]:
        with CANONICAL_LEDGER.open("r", encoding="utf-8-sig", newline="") as handle:
            return list(csv.DictReader(handle))

    def test_reads_exact_gap_free_canonical_range(self) -> None:
        self.assertEqual(220, len(self.records))
        self.assertEqual(
            EXPECTED_CHECKPOINT_IDS,
            tuple(record.checkpoint_id for record in self.records),
        )
        self.assertEqual(tuple(range(1, 221)), tuple(record.ordinal for record in self.records))

    def test_preserves_ledger_status_evidence_and_notes_verbatim(self) -> None:
        source_by_id = {record.checkpoint_id: record for record in self.records}
        for node in self.graph.nodes:
            source = source_by_id[node.checkpoint_id]
            self.assertEqual(source.status, node.status)
            self.assertEqual(source.evidence, node.evidence)
            self.assertEqual(source.root_cause_or_note, node.root_cause_or_note)
        source_counts = Counter(record.status for record in self.records)
        self.assertEqual(
            {
                status: source_counts[status]
                for status in ("PASS", "FAIL", "BLOCKED", "PENDING")
            },
            self.graph.status_summary(),
        )

    def test_every_node_has_complete_machine_readable_contract(self) -> None:
        for node in self.graph.nodes:
            self.assertTrue(node.category)
            self.assertTrue(node.subcategory)
            self.assertTrue(node.source_areas)
            self.assertTrue(node.build_variants)
            self.assertTrue(node.execution_target)
            self.assertTrue(node.test_procedure)
            self.assertTrue(node.expected_result)
            self.assertTrue(node.pass_fail_thresholds)
            self.assertTrue(node.required_evidence)
            self.assertEqual(
                SUBCHECK_PHASES,
                tuple(subcheck.phase for subcheck in node.subchecks.ordered()),
            )
            self.assertTrue(
                all(subcheck.required_evidence for subcheck in node.subchecks.ordered())
            )

    def test_dependency_graph_is_acyclic_and_regressions_are_reverse_edges(self) -> None:
        validate_graph(self.graph)
        expected_reverse = {node.checkpoint_id: set() for node in self.graph.nodes}
        for node in self.graph.nodes:
            for prerequisite in node.prerequisites:
                expected_reverse[prerequisite].add(node.checkpoint_id)
                self.assertLess(
                    self.graph.node(prerequisite).ordinal,
                    node.ordinal,
                )
        for node in self.graph.nodes:
            self.assertEqual(expected_reverse[node.checkpoint_id], set(node.regression_links))

    def test_critical_recording_conversion_and_rerun_dependencies_are_explicit(self) -> None:
        self.assertTrue(
            {"CP-033", "CP-037", "CP-039", "CP-154", "CP-156", "CP-157"}
            .issubset(self.graph.node("CP-161").prerequisites)
        )
        self.assertTrue(
            {"CP-030", "CP-031", "CP-033", "CP-035", "CP-153"}
            .issubset(self.graph.node("CP-169").prerequisites)
        )
        self.assertTrue(
            {"CP-002", "CP-168", "CP-169", "CP-177", "CP-178", "CP-217"}
            .issubset(self.graph.node("CP-218").prerequisites)
        )
        for ordinal in range(171, 201):
            self.assertEqual("host+device", self.graph.node(f"CP-{ordinal:03d}").execution_target)
        self.assertIn("release-aab-production-signed", self.graph.node("CP-220").build_variants)

    def test_rejects_duplicate_checkpoint_id(self) -> None:
        rows = self._canonical_rows()
        rows[2]["id"] = rows[1]["id"]
        with tempfile.TemporaryDirectory() as temporary:
            path = self._write_rows(Path(temporary), rows)
            with self.assertRaisesRegex(LedgerValidationError, "Duplicate checkpoint IDs"):
                read_canonical_ledger(path)

    def test_rejects_gap_or_wrong_row_count(self) -> None:
        rows = self._canonical_rows()
        del rows[109]
        with tempfile.TemporaryDirectory() as temporary:
            path = self._write_rows(Path(temporary), rows)
            with self.assertRaisesRegex(LedgerValidationError, "exactly 220 rows"):
                read_canonical_ledger(path)

    def test_rejects_malformed_header_and_status(self) -> None:
        rows = self._canonical_rows()
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            bad_header = self._write_rows(
                directory,
                rows,
                fieldnames=("id", "category", "checkpoint", "status", "evidence"),
            )
            with self.assertRaisesRegex(LedgerValidationError, "Ledger columns"):
                read_canonical_ledger(bad_header)

            rows[0]["status"] = "MAYBE"
            bad_status = self._write_rows(directory, rows)
            with self.assertRaisesRegex(LedgerValidationError, "unsupported status"):
                read_canonical_ledger(bad_status)

    def test_detects_dependency_cycle(self) -> None:
        first = replace(self.graph.nodes[0], prerequisites=("CP-002",))
        cyclic = CheckpointGraph(
            schema_version=self.graph.schema_version,
            ledger_path=self.graph.ledger_path,
            ledger_sha256=self.graph.ledger_sha256,
            nodes=(first,) + self.graph.nodes[1:],
            edges=self.graph.edges,
        )
        with self.assertRaisesRegex(GraphCycleError, "cycle"):
            validate_graph(cyclic)

    def test_parallel_schedule_serializes_all_device_checkpoints(self) -> None:
        schedule = build_parallel_schedule(self.graph)
        scheduled = [
            checkpoint
            for wave in schedule.waves
            for checkpoint in wave.checkpoints
        ]
        self.assertEqual(220, len(scheduled))
        self.assertEqual(220, len(set(scheduled)))
        self.assertTrue(any(len(wave.host_checkpoints) > 1 for wave in schedule.waves))
        self.assertTrue(
            all(
                wave.device_checkpoint is None
                or self.graph.node(wave.device_checkpoint).requires_device
                for wave in schedule.waves
            )
        )
        expected_device = {
            node.checkpoint_id for node in self.graph.nodes if node.requires_device
        }
        self.assertEqual(expected_device, set(schedule.exclusive_device_sequence))
        self.assertEqual(
            schedule.exclusive_device_sequence,
            exclusive_device_sequence(self.graph),
        )

    def test_schedule_respects_every_dependency_wave(self) -> None:
        schedule = build_parallel_schedule(self.graph)
        wave_by_id = {
            checkpoint: wave.index
            for wave in schedule.waves
            for checkpoint in wave.checkpoints
        }
        for node in self.graph.nodes:
            for prerequisite in node.prerequisites:
                self.assertLess(wave_by_id[prerequisite], wave_by_id[node.checkpoint_id])

    def test_artifacts_are_deterministic_and_parseable(self) -> None:
        with tempfile.TemporaryDirectory() as first, tempfile.TemporaryDirectory() as second:
            first_paths = write_graph_artifacts(self.graph, first)
            second_paths = write_graph_artifacts(self.graph, second)
            for key in ("json", "csv", "validation"):
                self.assertEqual(first_paths[key].read_bytes(), second_paths[key].read_bytes())

            payload = json.loads(first_paths["json"].read_text(encoding="utf-8"))
            self.assertEqual(220, payload["ledger"]["row_count"])
            self.assertEqual("PASS", payload["validation"]["result"])
            self.assertEqual(1, payload["validation"]["max_device_controllers_per_wave"])
            with first_paths["csv"].open("r", encoding="utf-8", newline="") as handle:
                rows = list(csv.DictReader(handle))
            self.assertEqual(220, len(rows))
            self.assertEqual("CP-001", rows[0]["checkpoint_id"])
            self.assertEqual("CP-220", rows[-1]["checkpoint_id"])
            self.assertIn(
                "result=PASS",
                first_paths["validation"].read_text(encoding="utf-8"),
            )


if __name__ == "__main__":
    unittest.main()
