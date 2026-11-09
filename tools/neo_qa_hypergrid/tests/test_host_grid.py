from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

from tools.neo_qa_hypergrid.host_grid import HostLane, execute_host_grid, plan_host_waves


class HostGridTest(unittest.TestCase):
    def test_overlapping_build_outputs_are_serialized(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            lanes = (
                HostLane("gradle-a", ("noop",), root, root / "a", (root / "app/build",)),
                HostLane("gradle-b", ("noop",), root, root / "b", (root / "app/build",)),
                HostLane("python", ("noop",), root, root / "c", (root / "python-out",)),
            )
            waves = plan_host_waves(lanes)
            self.assertEqual(2, len(waves))
            self.assertNotEqual(
                next(index for index, wave in enumerate(waves) if lanes[0] in wave),
                next(index for index, wave in enumerate(waves) if lanes[1] in wave),
            )

    def test_read_only_lanes_still_serialize_shared_evidence_directory(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            shared = root / "evidence"
            lanes = (
                HostLane("a", ("noop",), root, shared, read_only=True),
                HostLane("b", ("noop",), root, shared, read_only=True),
            )
            self.assertEqual(2, len(plan_host_waves(lanes)))

    def test_gradle_lanes_require_isolated_cache_and_shared_checkout_is_serial(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            missing_cache = HostLane(
                "gradle-missing-cache",
                ("gradlew.bat", "help"),
                root,
                root / "missing",
                kind="gradle",
            )
            with self.assertRaisesRegex(ValueError, "isolated cache_directory"):
                plan_host_waves((missing_cache,))
            lanes = (
                HostLane(
                    "gradle-a",
                    ("gradlew.bat", "test"),
                    root,
                    root / "a",
                    cache_directory=root / "cache-a",
                    kind="gradle",
                ),
                HostLane(
                    "gradle-b",
                    ("gradlew.bat", "lint"),
                    root,
                    root / "b",
                    cache_directory=root / "cache-b",
                    kind="gradle",
                ),
            )
            self.assertEqual(2, len(plan_host_waves(lanes)))

    def test_execution_requires_explicit_authority_and_captures_hashes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            lane = HostLane(
                "python",
                (sys.executable, "-c", "print('ok')"),
                root,
                root / "evidence",
                output_roots=(root / "evidence",),
                read_only=False,
            )
            with self.assertRaises(PermissionError):
                execute_host_grid((lane,))
            result = execute_host_grid((lane,), allow_execution=True)[0]
            self.assertEqual(0, result.exit_code)
            self.assertEqual(64, len(result.stdout_sha256))
            self.assertIn("ok", Path(result.stdout_path).read_text(encoding="utf-8"))
            with self.assertRaises(FileExistsError):
                execute_host_grid((lane,), allow_execution=True)

    def test_timeout_terminates_the_owned_process_tree_truthfully(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            lane = HostLane(
                "timeout",
                (sys.executable, "-c", "import time; time.sleep(10)"),
                root,
                root / "timeout-evidence",
                output_roots=(root / "timeout-evidence",),
                timeout_seconds=0.1,
            )
            result = execute_host_grid((lane,), allow_execution=True)[0]
            self.assertTrue(result.timed_out)
            self.assertEqual(124, result.exit_code)


if __name__ == "__main__":
    unittest.main()
