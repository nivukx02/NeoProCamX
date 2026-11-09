"""Host contracts for device teardown; these are not physical-device test evidence."""

from pathlib import Path
import unittest


class Lq031CleanupContractTest(unittest.TestCase):
    def test_optional_exit_race_cannot_abort_authoritative_test_result_parsing(self):
        source = (Path(__file__).resolve().parents[1] / "run_lq031_instrumentation.ps1").read_text()
        self.assertIn("$waitProbe = Invoke-QABoundedAdbCleanup", source)
        self.assertIn("$watchdogRetiredProcessProbes += 1", source)
        self.assertIn("$report.watchdogProbeFailure = $waitProbe.error", source)
        self.assertIn('$instrumentationText.Contains("INSTRUMENTATION_CODE: -1")', source)
        self.assertIn('-not $instrumentationText.Contains("FAILURES!!!")', source)

    def test_absolute_evidence_root_is_not_joined_onto_workspace_drive(self):
        source = (Path(__file__).resolve().parents[1] / "run_lq031_instrumentation.ps1").read_text()
        root = source[source.index("$ResolvedRoot ="):source.index("New-Item -ItemType Directory")]
        self.assertIn("[System.IO.Path]::IsPathRooted($ArtifactRoot)", root)
        self.assertIn("} else {", root)

    def test_teardown_is_independent_bounded_and_honest(self):
        source = (Path(__file__).resolve().parents[1] / "run_lq031_instrumentation.ps1").read_text()
        cleanup = source[source.rindex("} finally {") :]
        self.assertIn("bounded_adb_cleanup.ps1", source)
        self.assertIn("force_stop = Invoke-QABoundedAdbCleanup", cleanup)
        self.assertIn("screen_off = Invoke-QABoundedAdbCleanup", cleanup)
        self.assertIn("KEYCODE_SLEEP", cleanup)
        self.assertIn("if ($report.Contains('cleanupFailure')) { $report.status = 'FAIL' }", cleanup)
        self.assertNotIn("Invoke-Adb -Arguments", cleanup)
        self.assertLess(cleanup.index("force_stop ="), cleanup.index("$report.completedAt"))
        self.assertLess(cleanup.index("screen_off ="), cleanup.index("$report.completedAt"))


if __name__ == "__main__":
    unittest.main()
