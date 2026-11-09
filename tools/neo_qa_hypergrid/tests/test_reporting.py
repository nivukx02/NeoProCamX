from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from tools.neo_qa_hypergrid.evidence_oracle import (
    CandidateIdentity,
    CheckpointEvidenceRecord,
    DeviceIdentity,
    EvidenceOracle,
)
from tools.neo_qa_hypergrid.reporting import (
    build_checkpoint_report,
    verify_sealed_evidence_record,
    write_checkpoint_report,
)


def host_candidate() -> CandidateIdentity:
    return CandidateIdentity(
        "NOT_APPLICABLE",
        "NOT_APPLICABLE",
        "NOT_APPLICABLE",
        "host",
        "host",
        "host",
        "host",
    )


def graph_node(checkpoint_id: str, status: str = "PENDING", **values: object) -> dict[str, object]:
    return {
        "checkpoint_id": checkpoint_id,
        "category": "Host",
        "checkpoint": "Contract",
        "status": status,
        "evidence": "",
        "root_cause_or_note": "",
        "pass_fail_thresholds": ["must pass"],
        "execution_target": "host",
        "prerequisites": [],
        **values,
    }


class ReportingTest(unittest.TestCase):
    def test_legacy_graph_rows_are_reported_without_invented_measurements(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            graph = root / "graph.json"
            graph.write_text(
                json.dumps(
                    {
                        "nodes": [
                            {
                                "checkpoint_id": "CP-001",
                                "category": "Host",
                                "checkpoint": "Readable",
                                "status": "PASS",
                                "evidence": "old.log",
                                "root_cause_or_note": "",
                                "pass_fail_thresholds": ["must be readable"],
                            }
                        ]
                    }
                ),
                encoding="utf-8",
            )
            rows = build_checkpoint_report(graph, workspace=root)
            self.assertEqual(1, len(rows))
            self.assertEqual("UNAVAILABLE_LEGACY_LEDGER", rows[0].candidate_apk_hash)
            output = root / "report"
            write_checkpoint_report(rows, output)
            payload = json.loads((output / "CHECKPOINT_REPORT.json").read_text(encoding="utf-8"))
            self.assertEqual(1, payload["counts"]["PASS"])
            self.assertEqual(1, payload["legacy_ledger_records"])
            self.assertEqual(0, payload["sealed_hypergrid_records"])

    def test_tampered_or_artifactless_sealed_record_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact = root / "result.txt"
            artifact.write_text("PASS\n", encoding="utf-8")
            record = CheckpointEvidenceRecord(
                checkpoint_id="CP-001",
                status="PASS",
                exact_test="host contract",
                candidate=host_candidate(),
                device=DeviceIdentity.host_only(),
                measured_result="passed",
                expected_threshold="must pass",
                evidence_paths=("result.txt",),
                focused_validation="focused",
                regression_validation="regression",
            )
            sealed = EvidenceOracle(root).seal(record, root / "sealed")
            payload = json.loads(sealed.read_text(encoding="utf-8"))
            payload["record"]["status"] = "FAIL"
            payload["artifacts"] = []
            sealed.write_text(json.dumps(payload), encoding="utf-8")
            valid, errors = verify_sealed_evidence_record(sealed, root)
            self.assertFalse(valid)
            self.assertIn("sealed record body hash mismatch", errors)
            self.assertIn(
                "sealed artifact manifest does not exactly match record evidence paths",
                errors,
            )

    def test_device_pass_cannot_be_replaced_by_host_simulation(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            graph = root / "graph.json"
            graph.write_text(
                json.dumps(
                    {"nodes": [graph_node("CP-066", execution_target="device")]}
                ),
                encoding="utf-8",
            )
            artifact = root / "simulation.txt"
            artifact.write_text("modeled\n", encoding="utf-8")
            record = CheckpointEvidenceRecord(
                checkpoint_id="CP-066",
                status="PASS",
                exact_test="host simulation",
                candidate=host_candidate(),
                device=DeviceIdentity.host_only(),
                measured_result="modeled permission flow",
                expected_threshold="physical flow",
                evidence_paths=("simulation.txt",),
                focused_validation="focused",
                regression_validation="regression",
                evidence_kind="simulation",
            )
            sealed = EvidenceOracle(root).seal(record, root / "sealed")
            with self.assertRaisesRegex(ValueError, "physical-device evidence"):
                build_checkpoint_report(graph, workspace=root, evidence_records=(sealed,))

    def test_pass_requires_all_declared_prerequisites_to_be_pass(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            graph = root / "graph.json"
            graph.write_text(
                json.dumps(
                    {
                        "nodes": [
                            graph_node("CP-001", status="FAIL"),
                            graph_node("CP-002", prerequisites=["CP-001"]),
                        ]
                    }
                ),
                encoding="utf-8",
            )
            artifact = root / "result.txt"
            artifact.write_text("PASS\n", encoding="utf-8")
            record = CheckpointEvidenceRecord(
                checkpoint_id="CP-002",
                status="PASS",
                exact_test="dependent test",
                candidate=host_candidate(),
                device=DeviceIdentity.host_only(),
                measured_result="passed",
                expected_threshold="must pass",
                evidence_paths=("result.txt",),
                focused_validation="focused",
                regression_validation="regression",
            )
            sealed = EvidenceOracle(root).seal(record, root / "sealed")
            with self.assertRaisesRegex(ValueError, "prerequisites are not PASS"):
                build_checkpoint_report(graph, workspace=root, evidence_records=(sealed,))


if __name__ == "__main__":
    unittest.main()
