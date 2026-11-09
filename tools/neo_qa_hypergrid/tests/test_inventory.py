from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from tools.neo_qa_hypergrid.inventory import discover_repository, write_inventory


class InventoryTest(unittest.TestCase):
    def test_discovers_adb_and_simulation_tools_without_reading_evidence_payloads(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            workspace = Path(directory)
            tools = workspace / "tools/simulation"
            tools.mkdir(parents=True)
            (tools / "test_model.py").write_text("import unittest\n", encoding="utf-8")
            (workspace / "tools/device.ps1").write_text("adb devices\n", encoding="utf-8")
            artifact = workspace / "artifacts/CP-066/run.log"
            artifact.parent.mkdir(parents=True)
            artifact.write_text("evidence\n", encoding="utf-8")
            inventory = discover_repository(workspace)
            self.assertEqual(2, len(inventory.tools))
            self.assertTrue(any(item.requires_adb for item in inventory.tools))
            self.assertIn("simulation", inventory.tools[1].capabilities)
            self.assertEqual(("CP-066",), inventory.evidence[0].checkpoint_ids)
            output = workspace / "out/inventory.json"
            write_inventory(inventory, output)
            self.assertEqual(
                "neo-qa-hypergrid/repository-inventory/v1",
                json.loads(output.read_text(encoding="utf-8"))["schema"],
            )

    def test_output_can_be_excluded_to_prevent_self_inventory_drift(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            workspace = Path(directory)
            output = workspace / "artifacts/inventory.json"
            output.parent.mkdir(parents=True)
            output.write_text("old inventory\n", encoding="utf-8")
            inventory = discover_repository(workspace, exclude_paths=(output,))
            self.assertFalse(any(item.path == "artifacts/inventory.json" for item in inventory.evidence))


if __name__ == "__main__":
    unittest.main()
