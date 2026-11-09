from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from tools.neo_qa_hypergrid.change_impact import (
    compute_impact,
    may_preserve_sealed_evidence,
)


class ChangeImpactTest(unittest.TestCase):
    def test_recording_change_invalidates_recording_and_final_regression(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            workspace = Path(directory)
            source = workspace / "app/src/main/java/com/example/recording/Manager.kt"
            source.parent.mkdir(parents=True)
            source.write_text("class Manager\n", encoding="utf-8")
            report = compute_impact(
                workspace,
                [source.relative_to(workspace).as_posix()],
                reverse_dependencies={"CP-151": ("CP-162",)},
            )
            self.assertIn("CP-151", report.directly_invalidated)
            self.assertIn("CP-217", report.directly_invalidated)
            self.assertIn("CP-162", report.transitively_invalidated)

    def test_evidence_preservation_requires_same_candidate_and_no_impact(self) -> None:
        self.assertTrue(
            may_preserve_sealed_evidence(
                evidence_candidate_sha256="A",
                current_candidate_sha256="A",
                checkpoint_invalidated=False,
                dependency_invalidated=False,
            )
        )
        self.assertFalse(
            may_preserve_sealed_evidence(
                evidence_candidate_sha256="A",
                current_candidate_sha256="B",
                checkpoint_invalidated=False,
                dependency_invalidated=False,
            )
        )

    def test_unclassified_app_runtime_change_fails_conservatively(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            workspace = Path(directory)
            source = workspace / "app/src/main/java/com/example/color/Science.kt"
            source.parent.mkdir(parents=True)
            source.write_text("class Science\n", encoding="utf-8")
            report = compute_impact(workspace, [source.relative_to(workspace).as_posix()])
            self.assertIn("CP-045", report.directly_invalidated)
            self.assertIn("CP-220", report.directly_invalidated)

    def test_manifest_and_device_test_changes_invalidate_installed_flows(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            workspace = Path(directory)
            manifest = workspace / "app/src/main/AndroidManifest.xml"
            device_test = workspace / "app/src/androidTest/java/com/example/FlowTest.kt"
            manifest.parent.mkdir(parents=True)
            device_test.parent.mkdir(parents=True)
            manifest.write_text("<manifest />\n", encoding="utf-8")
            device_test.write_text("class FlowTest\n", encoding="utf-8")
            report = compute_impact(
                workspace,
                [
                    manifest.relative_to(workspace).as_posix(),
                    device_test.relative_to(workspace).as_posix(),
                ],
            )
            self.assertIn("CP-061", report.directly_invalidated)
            self.assertIn("CP-151", report.directly_invalidated)
            self.assertIn("CP-219", report.directly_invalidated)

    def test_hypergrid_change_invalidates_every_derived_checkpoint_verdict(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            workspace = Path(directory)
            source = workspace / "tools/neo_qa_hypergrid/reporting.py"
            source.parent.mkdir(parents=True)
            source.write_text("# changed\n", encoding="utf-8")
            report = compute_impact(workspace, [source.relative_to(workspace).as_posix()])
            self.assertEqual(220, len(report.directly_invalidated))

    def test_default_dependency_map_expands_transitive_impact(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            workspace = Path(directory)
            metadata = workspace / ".gitattributes"
            metadata.write_text("* text=auto\n", encoding="utf-8")
            report = compute_impact(workspace, [".gitattributes"])
            self.assertNotIn("CP-004", report.directly_invalidated)
            self.assertIn("CP-004", report.transitively_invalidated)


if __name__ == "__main__":
    unittest.main()
